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

package com.foundationdb.sql.aisddl;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.foundationdb.ais.model.AISBuilder;
import com.foundationdb.ais.model.AkibanInformationSchema;
import com.foundationdb.ais.model.Column;
import com.foundationdb.ais.model.DefaultIndexNameGenerator;
import com.foundationdb.ais.model.Group;
import com.foundationdb.ais.model.Index;
import com.foundationdb.ais.model.IndexColumn;
import com.foundationdb.ais.model.IndexNameGenerator;
import com.foundationdb.ais.model.PrimaryKey;
import com.foundationdb.ais.model.Table;
import com.foundationdb.ais.model.TableName;
import com.foundationdb.ais.model.Type;
import com.foundationdb.ais.model.Types;
import com.foundationdb.ais.model.UserTable;
import com.foundationdb.qp.operator.QueryContext;
import com.foundationdb.server.api.DDLFunctions;
import com.foundationdb.server.error.*;
import com.foundationdb.server.service.session.Session;
import com.foundationdb.sql.optimizer.FunctionsTypeComputer;
import com.foundationdb.sql.parser.ColumnDefinitionNode;
import com.foundationdb.sql.parser.ConstantNode;
import com.foundationdb.sql.parser.ConstraintDefinitionNode;
import com.foundationdb.sql.parser.CreateTableNode;
import com.foundationdb.sql.parser.CurrentDatetimeOperatorNode;
import com.foundationdb.sql.parser.DropGroupNode;
import com.foundationdb.sql.parser.DropTableNode;
import com.foundationdb.sql.parser.ExistenceCheck;
import com.foundationdb.sql.parser.FKConstraintDefinitionNode;
import com.foundationdb.sql.parser.IndexColumnList;
import com.foundationdb.sql.parser.IndexConstraintDefinitionNode;
import com.foundationdb.sql.parser.IndexDefinition;
import com.foundationdb.sql.parser.RenameNode;
import com.foundationdb.sql.parser.ResultColumn;
import com.foundationdb.sql.parser.ResultColumnList;
import com.foundationdb.sql.parser.SpecialFunctionNode;
import com.foundationdb.sql.parser.TableElementNode;
import com.foundationdb.sql.parser.ValueNode;
import com.foundationdb.sql.types.DataTypeDescriptor;
import com.foundationdb.sql.types.TypeId;

import static com.foundationdb.sql.aisddl.DDLHelper.convertName;

/** DDL operations on Tables */
public class TableDDL
{
    //private final static Logger logger = LoggerFactory.getLogger(TableDDL.class);
    private TableDDL() {
    }

    public static void dropTable (DDLFunctions ddlFunctions,
                                  Session session, 
                                  String defaultSchemaName,
                                  DropTableNode dropTable,
                                  QueryContext context) {
        TableName tableName = convertName(defaultSchemaName, dropTable.getObjectName());
        ExistenceCheck existenceCheck = dropTable.getExistenceCheck();

        AkibanInformationSchema ais = ddlFunctions.getAIS(session);
        
        if (ais.getUserTable(tableName) == null) {
            if (existenceCheck == ExistenceCheck.IF_EXISTS)
            {
                if (context != null)
                    context.warnClient(new NoSuchTableException (tableName.getSchemaName(), tableName.getTableName()));
                return;
            }
            throw new NoSuchTableException (tableName.getSchemaName(), tableName.getTableName());
        }
        ViewDDL.checkDropTable(ddlFunctions, session, tableName);
        ddlFunctions.dropTable(session, tableName);
    }

    public static void dropGroup (DDLFunctions ddlFunctions,
                                    Session session,
                                    String defaultSchemaName,
                                    DropGroupNode dropGroup,
                                    QueryContext context)
    {
        TableName tableName = convertName(defaultSchemaName, dropGroup.getObjectName());
        ExistenceCheck existenceCheck = dropGroup.getExistenceCheck();
        AkibanInformationSchema ais = ddlFunctions.getAIS(session);
        
        if (ais.getUserTable(tableName) == null) {
            if (existenceCheck == ExistenceCheck.IF_EXISTS) {
                if (context != null) {
                    context.warnClient(new NoSuchTableException (tableName));
                }
                return;
            }
            throw new NoSuchTableException (tableName);
        } 
        if (!ais.getUserTable(tableName).isRoot()) {
            throw new DropGroupNotRootException (tableName);
        }
        
        final Group root = ais.getUserTable(tableName).getGroup();
        for (UserTable table : ais.getUserTables().values()) {
            if (table.getGroup() == root) {
                ViewDDL.checkDropTable(ddlFunctions, session, table.getName());
            }
        }
        ddlFunctions.dropGroup(session, root.getName());
    }
    
    public static void renameTable (DDLFunctions ddlFunctions,
                                    Session session,
                                    String defaultSchemaName,
                                    RenameNode renameTable) {
        TableName oldName = convertName(defaultSchemaName, renameTable.getObjectName());
        TableName newName = convertName(defaultSchemaName, renameTable.getNewTableName());
        ddlFunctions.renameTable(session, oldName, newName);
    }

    public static void createTable(DDLFunctions ddlFunctions,
                                   Session session,
                                   String defaultSchemaName,
                                   CreateTableNode createTable,
                                   QueryContext context) {
        if (createTable.getQueryExpression() != null)
            throw new UnsupportedCreateSelectException();

        com.foundationdb.sql.parser.TableName parserName = createTable.getObjectName();
        String schemaName = parserName.hasSchema() ? parserName.getSchemaName() : defaultSchemaName;
        String tableName = parserName.getTableName();
        ExistenceCheck condition = createTable.getExistenceCheck();

        AkibanInformationSchema ais = ddlFunctions.getAIS(session);

        if (ais.getUserTable(schemaName, tableName) != null)
            switch(condition)
            {
                case IF_NOT_EXISTS:
                    // table already exists. does nothing
                    if (context != null)
                        context.warnClient(new DuplicateTableNameException(schemaName, tableName));
                    return;
                case NO_CONDITION:
                    throw new DuplicateTableNameException(schemaName, tableName);
                default:
                    throw new IllegalStateException("Unexpected condition: " + condition);
            }

        AISBuilder builder = new AISBuilder();
        builder.userTable(schemaName, tableName);
        UserTable table = builder.akibanInformationSchema().getUserTable(schemaName, tableName);
        IndexNameGenerator namer = DefaultIndexNameGenerator.forTable(table);

        int colpos = 0;
        // first loop through table elements, add the columns
        for (TableElementNode tableElement : createTable.getTableElementList()) {
            if (tableElement instanceof ColumnDefinitionNode) {
                addColumn (builder, (ColumnDefinitionNode)tableElement, schemaName, tableName, colpos++);
            }
        }
        // second pass get the constraints (primary, FKs, and other keys)
        // This needs to be done in two passes as the parser may put the 
        // constraint before the column definition. For example:
        // CREATE TABLE t1 (c1 INT PRIMARY KEY) produces such a result. 
        // The Builder complains if you try to do such a thing. 
        for (TableElementNode tableElement : createTable.getTableElementList()) {
            if (tableElement instanceof FKConstraintDefinitionNode) {
                FKConstraintDefinitionNode fkdn = (FKConstraintDefinitionNode)tableElement;
                if (fkdn.isGrouping()) {
                    addParentTable(builder, ddlFunctions.getAIS(session), fkdn, schemaName, tableName);
                    addJoin (builder, fkdn, schemaName, schemaName, tableName);
                } else {
                    throw new UnsupportedFKIndexException();
                }
            }
            else if (tableElement instanceof ConstraintDefinitionNode) {
                addIndex (namer, builder, (ConstraintDefinitionNode)tableElement, schemaName, tableName, context);
            }
        }
        builder.basicSchemaIsComplete();
        builder.groupingIsComplete();
        
        ddlFunctions.createTable(session, table);
    }
    
    static void addColumn (final AISBuilder builder, final ColumnDefinitionNode cdn,
                           final String schemaName, final String tableName, int colpos) {

        // Special handling for the "SERIAL" column type -> which is transformed to 
        // BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1) UNIQUE
        if (cdn.getType().getTypeName().equals("serial")) {
            // BIGINT NOT NULL 
            DataTypeDescriptor bigint = new DataTypeDescriptor (TypeId.BIGINT_ID, false);
            addColumn (builder, schemaName, tableName, cdn.getColumnName(), colpos,
                       bigint, false, null, null);
            // GENERATED BY DEFAULT AS IDENTITY
            setAutoIncrement (builder, schemaName, tableName, cdn.getColumnName(), true, 1, 1);
            // UNIQUE (KEY)
            String constraint = Index.UNIQUE_KEY_CONSTRAINT;
            builder.index(schemaName, tableName, cdn.getColumnName(), true, constraint);
            builder.indexColumn(schemaName, tableName, cdn.getColumnName(), cdn.getColumnName(), 0, true, null);
        } else {
            String[] defaultValueFunction = getColumnDefault(cdn, schemaName, tableName);
            addColumn(builder, schemaName, tableName, cdn.getColumnName(), colpos,
                      cdn.getType(), cdn.getType().isNullable(), 
                      defaultValueFunction[0], defaultValueFunction[1]);
            if (cdn.isAutoincrementColumn()) {
                setAutoIncrement(builder, schemaName, tableName, cdn);
            }
        }
    }

    public static void setAutoIncrement(AISBuilder builder, String schema, String table, ColumnDefinitionNode cdn) {
        // if the cdn has a default node-> GENERATE BY DEFAULT
        // if no default node -> GENERATE ALWAYS
        Boolean defaultIdentity = cdn.getDefaultNode() != null;
        setAutoIncrement(builder, schema, table, cdn.getColumnName(),
                         defaultIdentity, cdn.getAutoincrementStart(), cdn.getAutoincrementIncrement());
    }

    public static void setAutoIncrement(AISBuilder builder, String schemaName, String tableName, String columnName,
                                        boolean defaultIdentity, long start, long increment) {
        // make the column an identity column 
        builder.columnAsIdentity(schemaName, tableName, columnName, start, increment, defaultIdentity);
    }
    
    static String[] getColumnDefault(ColumnDefinitionNode cdn, 
                                     String schemaName, String tableName) {
        String defaultValue = null, defaultFunction = null;
        if (cdn.getDefaultNode() != null) {
            ValueNode valueNode = cdn.getDefaultNode().getDefaultTree();
            if (valueNode == null) {
            }
            else if (valueNode instanceof ConstantNode) {
                defaultValue = ((ConstantNode)valueNode).getValue().toString();
            }
            else if (valueNode instanceof SpecialFunctionNode) {
                defaultFunction = FunctionsTypeComputer.specialFunctionName((SpecialFunctionNode)valueNode);
            }
            else if (valueNode instanceof CurrentDatetimeOperatorNode) {
                defaultFunction = FunctionsTypeComputer.currentDatetimeFunctionName((CurrentDatetimeOperatorNode)valueNode);
            }
            else {
                throw new BadColumnDefaultException(schemaName, tableName, 
                                                    cdn.getColumnName(), 
                                                    cdn.getDefaultNode().getDefaultText());
            }
        }
        return new String[] { defaultValue, defaultFunction };
    }

    static void addColumn(final AISBuilder builder,
                          final String schemaName, final String tableName, final String columnName,
                          int colpos, DataTypeDescriptor type, boolean nullable,
                          final String defaultValue, final String defaultFunction) {
        Long[] typeParameters = new Long[2];
        Type builderType = columnType(type, typeParameters, schemaName, tableName, columnName);
        String charset = null, collation = null;
        if (type.getCharacterAttributes() != null) {
            charset = type.getCharacterAttributes().getCharacterSet();
            collation = type.getCharacterAttributes().getCollation();
        }
        builder.column(schemaName, tableName, columnName, 
                       colpos,
                       builderType.name(), typeParameters[0], typeParameters[1],
                       nullable,
                       false,
                       charset, collation,
                       defaultValue, defaultFunction);
    }

    static Type columnType(DataTypeDescriptor type, Long[] typeParameters,
                           String schemaName, String tableName, String columnName) {
        Type builderType = typeMap.get(type.getTypeId());
        if (builderType == null) {
            throw new UnsupportedDataTypeException(new TableName(schemaName, tableName), columnName, type.getTypeName());
        }
        
        if (builderType.nTypeParameters() == 1) {
            typeParameters[0] = (long)type.getMaximumWidth();
            typeParameters[1] = null;
        } else if (builderType.nTypeParameters() == 2) {
            typeParameters[0] = (long)type.getPrecision();
            typeParameters[1] = (long)type.getScale();
        } else {
            typeParameters[0] = typeParameters[1] = null;
        }
        return builderType;
    }

    private static final Logger logger = LoggerFactory.getLogger(TableDDL.class);


    public static String addIndex(IndexNameGenerator namer, AISBuilder builder, ConstraintDefinitionNode cdn,
                                  String schemaName, String tableName, QueryContext context)  {
        // We don't (yet) have a constraint representation so override any provided
        UserTable table = builder.akibanInformationSchema().getUserTable(schemaName, tableName);
        final String constraint;
        String indexName = cdn.getName();
        int colPos = 0;

        if (cdn.getConstraintType() == ConstraintDefinitionNode.ConstraintType.CHECK) {
            throw new UnsupportedCheckConstraintException ();
        }
        else if (cdn.getConstraintType() == ConstraintDefinitionNode.ConstraintType.PRIMARY_KEY) {
            indexName = constraint = Index.PRIMARY_KEY_CONSTRAINT;
        }
        else if (cdn.getConstraintType() == ConstraintDefinitionNode.ConstraintType.UNIQUE) {
            constraint = Index.UNIQUE_KEY_CONSTRAINT;
        } 
        // Indexes do things a little differently because they need to support Group indexes, Full Text and Geospacial
        else if (cdn.getConstraintType() == ConstraintDefinitionNode.ConstraintType.INDEX) {
            return generateTableIndex(namer, builder, cdn, table, context);
        } else {
            throw new UnsupportedCheckConstraintException ();
        }

        if(indexName == null) {
            indexName = namer.generateIndexName(null, cdn.getColumnList().get(0).getName(), constraint);
        }
        
        builder.index(schemaName, tableName, indexName, true, constraint);
        
        for (ResultColumn col : cdn.getColumnList()) {
            if(table.getColumn(col.getName()) == null) {
                throw new NoSuchColumnException(col.getName());
            }
            builder.indexColumn(schemaName, tableName, indexName, col.getName(), colPos++, true, null);
        }
        return indexName;
    }

    public static TableName getReferencedName(String schemaName, FKConstraintDefinitionNode fkdn) {
        return convertName(schemaName, fkdn.getRefTableName());
    }

    public static void addJoin(final AISBuilder builder, final FKConstraintDefinitionNode fkdn,
                               final String defaultSchemaName, final String schemaName, final String tableName)  {
        TableName parentName = getReferencedName(defaultSchemaName, fkdn);
        String joinName = String.format("%s/%s/%s/%s",
                                        parentName.getSchemaName(),
                                        parentName.getTableName(),
                                        schemaName, tableName);

        AkibanInformationSchema ais = builder.akibanInformationSchema();
        // Check parent table exists
        UserTable parentTable = ais.getUserTable(parentName);
        if (parentTable == null) {
            throw new JoinToUnknownTableException(new TableName(schemaName, tableName), parentName);
        }
        // Check child table exists
        UserTable childTable = ais.getUserTable(schemaName, tableName);
        if (childTable == null) {
            throw new NoSuchTableException(schemaName, tableName);
        }
        // Check that we aren't joining to ourselves
        if (parentTable == childTable) {
            throw new JoinToSelfException(schemaName, tableName);
        }
        // Check that fk list and pk list are the same size
        String[] fkColumns = columnNamesFromListOrPK(fkdn.getColumnList(), null); // No defaults for child table
        String[] pkColumns = columnNamesFromListOrPK(fkdn.getRefResultColumnList(), parentTable.getPrimaryKey());

        int actualPkColCount = parentTable.getPrimaryKeyIncludingInternal().getColumns().size();
        if ((fkColumns.length != actualPkColCount) || (pkColumns.length != actualPkColCount)) {
            throw new JoinColumnMismatchException(fkdn.getColumnList().size(),
                                                  new TableName(schemaName, tableName),
                                                  parentName,
                                                  parentTable.getPrimaryKeyIncludingInternal().getColumns().size());
        }

        int colPos = 0;
        while((colPos < fkColumns.length) && (colPos < pkColumns.length)) {
            String fkColumn = fkColumns[colPos];
            String pkColumn = pkColumns[colPos];
            if (childTable.getColumn(fkColumn) == null) {
                throw new NoSuchColumnException(String.format("%s.%s.%s", schemaName, tableName, fkColumn));
            }
            if (parentTable.getColumn(pkColumn) == null) {
                throw new JoinToWrongColumnsException(new TableName(schemaName, tableName),
                                                      fkColumn,
                                                      parentName,
                                                      pkColumn);
            }
            ++colPos;
        }

        builder.joinTables(joinName, parentName.getSchemaName(), parentName.getTableName(), schemaName, tableName);

        colPos = 0;
        while(colPos < fkColumns.length) {
            builder.joinColumns(joinName,
                                parentName.getSchemaName(), parentName.getTableName(), pkColumns[colPos],
                                schemaName, tableName, fkColumns[colPos]);
            ++colPos;
        }
        builder.addJoinToGroup(parentTable.getGroup().getName(), joinName, 0);
    }
    
    /**
     * Add a minimal parent table (PK) with group to the builder based upon the AIS.
     */
    public static void addParentTable(final AISBuilder builder, final AkibanInformationSchema ais,
                                      final FKConstraintDefinitionNode fkdn, final String schemaName, String tableName) {

        TableName parentName = getReferencedName(schemaName, fkdn);
        // Check that we aren't joining to ourselves
        if (parentName.equals(schemaName, tableName)) {
            throw new JoinToSelfException(schemaName, tableName);
        }
        // Check parent table exists
        UserTable parentTable = ais.getUserTable(parentName);
        if (parentTable == null) {
            throw new JoinToUnknownTableException(new TableName(schemaName, tableName), parentName);
        }

        builder.userTable(parentName.getSchemaName(), parentName.getTableName());
        
        builder.index(parentName.getSchemaName(), parentName.getTableName(), Index.PRIMARY_KEY_CONSTRAINT, true,
                      Index.PRIMARY_KEY_CONSTRAINT);
        int colpos = 0;
        for (Column column : parentTable.getPrimaryKeyIncludingInternal().getColumns()) {
            builder.column(parentName.getSchemaName(), parentName.getTableName(),
                    column.getName(),
                    colpos,
                    column.getType().name(),
                    column.getTypeParameter1(),
                    column.getTypeParameter2(),
                    column.getNullable(),
                    false, //column.getInitialAutoIncrementValue() != 0,
                    column.getCharsetAndCollation() != null ? column.getCharsetAndCollation().charset() : null,
                    column.getCharsetAndCollation() != null ? column.getCharsetAndCollation().collation() : null);
            builder.indexColumn(parentName.getSchemaName(), parentName.getTableName(), Index.PRIMARY_KEY_CONSTRAINT,
                    column.getName(), colpos++, true, 0);
        }
        final TableName groupName;
        if(parentTable.getGroup() == null) {
            groupName = parentName;
        } else {
            groupName = parentTable.getGroup().getName();
        }
        builder.createGroup(groupName.getTableName(), groupName.getSchemaName());
        builder.addTableToGroup(groupName, parentName.getSchemaName(), parentName.getTableName());
    }


    private static String[] columnNamesFromListOrPK(ResultColumnList list, PrimaryKey pk) {
        String[] names = (list == null) ? null: list.getColumnNames();
        if(((names == null) || (names.length == 0)) && (pk != null)) {
            Index index = pk.getIndex();
            names = new String[index.getKeyColumns().size()];
            int i = 0;
            for(IndexColumn iCol : index.getKeyColumns()) {
                names[i++] = iCol.getColumn().getName();
            }
        }
        if(names == null) {
            names = new String[0];
        }
        return names;
    }
    
    private static String generateTableIndex(IndexNameGenerator namer, 
            AISBuilder builder, 
            ConstraintDefinitionNode cdn, 
            Table table,
            QueryContext context) {
        IndexDefinition id = ((IndexConstraintDefinitionNode)cdn);
        IndexColumnList columnList = id.getIndexColumnList();
        Index tableIndex;
        String indexName = ((IndexConstraintDefinitionNode)cdn).getIndexName();
        if(indexName == null) {
            indexName = namer.generateIndexName(null, columnList.get(0).getColumnName(), Index.KEY_CONSTRAINT);
        }

        if (columnList.functionType() == IndexColumnList.FunctionType.FULL_TEXT) {
            logger.debug ("Building Full text index on table {}", table.getName()) ;
            tableIndex = IndexDDL.buildFullTextIndex (builder, table.getName(), indexName, id);
        } else if (IndexDDL.checkIndexType (id, table.getName()) == Index.IndexType.TABLE) {
            logger.debug ("Building Table index on table {}", table.getName()) ;
            tableIndex = IndexDDL.buildTableIndex (builder, table.getName(), indexName, id);
        } else {
            logger.debug ("Building Group index on table {}", table.getName());
            tableIndex = IndexDDL.buildGroupIndex (builder, table.getName(), indexName, id);
        }

        boolean indexIsSpatial = columnList.functionType() == IndexColumnList.FunctionType.Z_ORDER_LAT_LON;
        // Can't check isSpatialCompatible before the index columns have been added.
        if (indexIsSpatial && !Index.isSpatialCompatible(tableIndex)) {
            throw new BadSpatialIndexException(tableIndex.getIndexName().getTableName(), null);
        }
        return tableIndex.getIndexName().getName();
    }

    private final static Map<TypeId, Type> typeMap  = typeMapping();
    
    private static Map<TypeId, Type> typeMapping() {
        HashMap<TypeId, Type> types = new HashMap<>();
        types.put(TypeId.BOOLEAN_ID, Types.BOOLEAN);
        types.put(TypeId.TINYINT_ID, Types.TINYINT);
        types.put(TypeId.SMALLINT_ID, Types.SMALLINT);
        types.put(TypeId.INTEGER_ID, Types.INT);
        types.put(TypeId.MEDIUMINT_ID, Types.MEDIUMINT);
        types.put(TypeId.BIGINT_ID, Types.BIGINT);
        
        types.put(TypeId.TINYINT_UNSIGNED_ID, Types.U_TINYINT);
        types.put(TypeId.SMALLINT_UNSIGNED_ID, Types.U_SMALLINT);
        types.put(TypeId.MEDIUMINT_UNSIGNED_ID, Types.U_MEDIUMINT);
        types.put(TypeId.INTEGER_UNSIGNED_ID, Types.U_INT);
        types.put(TypeId.BIGINT_UNSIGNED_ID, Types.U_BIGINT);
        
        types.put(TypeId.REAL_ID, Types.FLOAT);
        types.put(TypeId.DOUBLE_ID, Types.DOUBLE);
        types.put(TypeId.DECIMAL_ID, Types.DECIMAL);
        types.put(TypeId.NUMERIC_ID, Types.DECIMAL);
        
        types.put(TypeId.REAL_UNSIGNED_ID, Types.U_FLOAT);
        types.put(TypeId.DOUBLE_UNSIGNED_ID, Types.U_DOUBLE);
        types.put(TypeId.DECIMAL_UNSIGNED_ID, Types.U_DECIMAL);
        types.put(TypeId.NUMERIC_UNSIGNED_ID, Types.U_DECIMAL);
        
        types.put(TypeId.CHAR_ID, Types.CHAR);
        types.put(TypeId.VARCHAR_ID, Types.VARCHAR);
        types.put(TypeId.LONGVARCHAR_ID, Types.VARCHAR);
        types.put(TypeId.BIT_ID, Types.BINARY);
        types.put(TypeId.VARBIT_ID, Types.VARBINARY);
        types.put(TypeId.LONGVARBIT_ID, Types.VARBINARY);
        
        types.put(TypeId.DATE_ID, Types.DATE);
        types.put(TypeId.TIME_ID, Types.TIME);
        types.put(TypeId.TIMESTAMP_ID, Types.DATETIME); // TODO: Types.TIMESTAMP?
        types.put(TypeId.DATETIME_ID, Types.DATETIME);
        types.put(TypeId.YEAR_ID, Types.YEAR);
        
        types.put(TypeId.BLOB_ID, Types.LONGBLOB);
        types.put(TypeId.CLOB_ID, Types.LONGTEXT);
        types.put(TypeId.TEXT_ID, Types.TEXT);
        types.put(TypeId.TINYBLOB_ID, Types.TINYBLOB);
        types.put(TypeId.TINYTEXT_ID, Types.TINYTEXT);
        types.put(TypeId.MEDIUMBLOB_ID, Types.MEDIUMBLOB);
        types.put(TypeId.MEDIUMTEXT_ID, Types.MEDIUMTEXT);
        types.put(TypeId.LONGBLOB_ID, Types.LONGBLOB);
        types.put(TypeId.LONGTEXT_ID, Types.LONGTEXT);
        return Collections.unmodifiableMap(types);
        
    }        
}