/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.sql.pg;

import com.foundationdb.qp.operator.QueryBindings;
import com.foundationdb.server.error.NoSuchSchemaException;
import com.foundationdb.server.error.UnsupportedConfigurationException;
import com.foundationdb.server.error.UnsupportedSQLException;
import com.foundationdb.sql.aisddl.SchemaDDL;
import com.foundationdb.sql.optimizer.plan.CostEstimate;
import com.foundationdb.sql.parser.AccessMode;
import com.foundationdb.sql.parser.ParameterNode;
import com.foundationdb.sql.parser.SetConfigurationNode;
import com.foundationdb.sql.parser.SetSchemaNode;
import com.foundationdb.sql.parser.SetTransactionAccessNode;
import com.foundationdb.sql.parser.ShowConfigurationNode;
import com.foundationdb.sql.parser.StatementNode;
import com.foundationdb.sql.parser.StatementType;

import java.util.Arrays;
import java.io.IOException;
import java.util.List;

/** SQL statements that affect session / environment state. */
public class PostgresSessionStatement implements PostgresStatement
{
    enum Operation {
        USE, SET_CONFIGURATION, SHOW_CONFIGURATION,
        BEGIN_TRANSACTION, COMMIT_TRANSACTION, ROLLBACK_TRANSACTION,
        TRANSACTION_ISOLATION, TRANSACTION_ACCESS;
        
        public PostgresSessionStatement getStatement(StatementNode statement) {
            return new PostgresSessionStatement (this, statement);
        }
    };

    public static final String[] ALLOWED_CONFIGURATION = new String[] {
      "columnAsFunc",
      "client_encoding", "DateStyle", "geqo", "ksqo", "application_name",
      "queryTimeoutSec", "zeroDateTimeBehavior", "maxNotificationLevel", "OutputFormat",
      "parserInfixBit", "parserInfixLogical", "parserDoubleQuoted",
      "newtypes", "transactionPeriodicallyCommit",
      "extra_float_digits", "ssl_renegotiation_limit"
    };

    private Operation operation;
    private StatementNode statement;
    private long aisGeneration;
    
    protected PostgresSessionStatement(Operation operation, StatementNode statement) {
        this.operation = operation;
        this.statement = statement;
    }

    public StatementNode getStatement() {
        return statement;
    }

    @Override
    public PostgresType[] getParameterTypes() {
        return null;
    }

    static final PostgresType SHOW_PG_TYPE = 
        PostgresEmulatedMetaDataStatement.DEFVAL_PG_TYPE;

    @Override
    public void sendDescription(PostgresQueryContext context,
                                boolean always, boolean params)
            throws IOException {
        if (always || (operation == Operation.SHOW_CONFIGURATION)) {
            PostgresServerSession server = context.getServer();
            PostgresMessenger messenger = server.getMessenger();
            if (params) {
                messenger.beginMessage(PostgresMessages.PARAMETER_DESCRIPTION_TYPE.code());
                messenger.writeShort(0);
                messenger.sendMessage();
            }
            switch (operation) {
            case SHOW_CONFIGURATION:
                messenger.beginMessage(PostgresMessages.ROW_DESCRIPTION_TYPE.code());
                messenger.writeShort(1); // single column
                messenger.writeString(((ShowConfigurationNode)statement).getVariable()); // attname
                messenger.writeInt(0); // attrelid
                messenger.writeShort(0);  // attnum
                messenger.writeInt(SHOW_PG_TYPE.getOid()); // atttypid
                messenger.writeShort(SHOW_PG_TYPE.getLength()); // attlen
                messenger.writeInt(SHOW_PG_TYPE.getModifier()); // atttypmod
                messenger.writeShort(0);
                break;
            default:
                messenger.beginMessage(PostgresMessages.NO_DATA_TYPE.code());
                break;
            }
            messenger.sendMessage();
        }
    }

    @Override
    public TransactionMode getTransactionMode() {
        return TransactionMode.ALLOWED;
    }

    @Override
    public TransactionAbortedMode getTransactionAbortedMode() {
        switch (operation) {
            case USE:
            case ROLLBACK_TRANSACTION:
            case SET_CONFIGURATION:
            case SHOW_CONFIGURATION:
                return TransactionAbortedMode.ALLOWED;
            default:
                return TransactionAbortedMode.NOT_ALLOWED;
        }
    }

    @Override
    public AISGenerationMode getAISGenerationMode() {
        return AISGenerationMode.ALLOWED;
    }

    @Override
    public int execute(PostgresQueryContext context, QueryBindings bindings, int maxrows) throws IOException {
        PostgresServerSession server = context.getServer();
        doOperation(context, server);
        {        
            PostgresMessenger messenger = server.getMessenger();
            messenger.beginMessage(PostgresMessages.COMMAND_COMPLETE_TYPE.code());
            messenger.writeString(statement.statementToString());
            messenger.sendMessage();
        }
        return (operation == Operation.SHOW_CONFIGURATION) ? 1 : 0;
    }

    @Override
    public boolean hasAISGeneration() {
        return aisGeneration != 0;
    }

    @Override
    public void setAISGeneration(long aisGeneration) {
        this.aisGeneration = aisGeneration;
    }

    @Override
    public long getAISGeneration() {
        return aisGeneration;
    }

    @Override
    public PostgresStatement finishGenerating(PostgresServerSession server,
                                              String sql, StatementNode stmt,
                                              List<ParameterNode> params, int[] paramTypes) {
        return this;
    }

    @Override
    public boolean putInCache() {
        return false;
    }

    @Override
    public CostEstimate getCostEstimate() {
        return null;
    }

    protected void doOperation(PostgresQueryContext context, PostgresServerSession server) throws IOException {
        switch (operation) {
        case USE:
            {
                SetSchemaNode node = (SetSchemaNode)statement;
                String schemaName = (node.statementType() == StatementType.SET_SCHEMA_USER ? 
                                     server.getProperty("user") : node.getSchemaName());
                if (server.getAIS().getSchema(schemaName) != null) {
                    server.setDefaultSchemaName(schemaName);
                } 
                else {
                    throw new NoSuchSchemaException(schemaName);
                }
            }
            break;
        case BEGIN_TRANSACTION:
            server.beginTransaction();
            break;
        case COMMIT_TRANSACTION:
            server.commitTransaction();
            break;
        case ROLLBACK_TRANSACTION:
            server.rollbackTransaction();
            break;
        case TRANSACTION_ACCESS:
            {
                SetTransactionAccessNode node = (SetTransactionAccessNode)statement;
                boolean current = node.isCurrent();
                boolean readOnly = (node.getAccessMode() == 
                                    AccessMode.READ_ONLY_ACCESS_MODE);
                if (current)
                    server.setTransactionReadOnly(readOnly);
                else
                    server.setTransactionDefaultReadOnly(readOnly);
            }
            break;
        case SET_CONFIGURATION:
            {
                SetConfigurationNode node = (SetConfigurationNode)statement;
                setVariable (server, node.getVariable(), node.getValue());
            }
            break;
        case SHOW_CONFIGURATION:
            {
                ShowConfigurationNode node = (ShowConfigurationNode)statement;
                showVariable (context, server, node.getVariable());
            }
            break;
        default:
            throw new UnsupportedSQLException("session control", statement);
        }
    }
    
    protected void setVariable(PostgresServerSession server, String variable, String value) {
        String cased = allowedConfiguration(variable);
        if (cased == null)
            throw new UnsupportedConfigurationException (variable);
        server.setProperty(cased, value);
    }
    
    protected void showVariable(PostgresQueryContext context, PostgresServerSession server, String variable) throws IOException {
        String cased = allowedConfiguration(variable);
        if (cased != null)
            variable = cased;
        String value = server.getSessionSetting(variable);
        if (value == null)
            throw new UnsupportedConfigurationException (variable);
        PostgresMessenger messenger = server.getMessenger();
        messenger.beginMessage(PostgresMessages.DATA_ROW_TYPE.code());
        messenger.writeShort(1); // single column
        PostgresEmulatedMetaDataStatement.writeColumn(context, server, messenger,  
                                                      0, value, SHOW_PG_TYPE);
        messenger.sendMessage();
    }

    /** Check for known variables <em>and</em> standardize their case. */
    public static String allowedConfiguration(String key) {
        for (String allowed : ALLOWED_CONFIGURATION) {
            if (allowed.equalsIgnoreCase(key)) {
                return allowed;
            }
        }
        return null;
    }
}