/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */
package com.akiban.server.service.restdml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.akiban.ais.model.AkibanInformationSchema;
import com.akiban.ais.model.Column;
import com.akiban.ais.model.IndexColumn;
import com.akiban.ais.model.PrimaryKey;
import com.akiban.ais.model.Sequence;
import com.akiban.ais.model.TableName;
import com.akiban.ais.model.Types;
import com.akiban.ais.model.UserTable;
import com.akiban.qp.operator.API;
import com.akiban.qp.operator.Operator;
import com.akiban.qp.row.BindableRow;
import com.akiban.qp.rowtype.UserTableRowType;
import com.akiban.server.t3expressions.OverloadResolver;
import com.akiban.server.t3expressions.OverloadResolver.OverloadResult;
import com.akiban.server.types.AkType;
import com.akiban.server.types3.TCast;
import com.akiban.server.types3.TExecutionContext;
import com.akiban.server.types3.TInstance;
import com.akiban.server.types3.TPreptimeValue;
import com.akiban.server.types3.mcompat.mtypes.MString;
import com.akiban.server.types3.pvalue.PValue;
import com.akiban.server.types3.pvalue.PValueSources;
import com.akiban.server.types3.texpressions.TCastExpression;
import com.akiban.server.types3.texpressions.TNullExpression;
import com.akiban.server.types3.texpressions.TPreparedExpression;
import com.akiban.server.types3.texpressions.TPreparedField;
import com.akiban.server.types3.texpressions.TPreparedFunction;
import com.akiban.server.types3.texpressions.TPreparedLiteral;
import com.akiban.server.types3.texpressions.TPreparedParameter;
import com.akiban.server.types3.texpressions.TValidatedScalar;

public class InsertGenerator extends OperatorGenerator{

    private UserTable table;
    
    public InsertGenerator (AkibanInformationSchema ais) {
        super(ais);
    }
    
    @Override
    protected Operator create(TableName tableName) {
        
        table = ais().getUserTable(tableName);

        RowStream stream = assembleValueScan (table);
        stream = assembleProjectTable (stream, table);
        stream.operator = API.insert_Returning(stream.operator, true);

        List<TPreparedExpression> pExpressions = null;
        if (table.getPrimaryKey() != null) {
            PrimaryKey key = table.getPrimaryKey();
            int size  = key.getIndex().getKeyColumns().size();
            pExpressions = new ArrayList<>(size);
            for (IndexColumn column : key.getIndex().getKeyColumns()) {
                int fieldIndex = column.getColumn().getPosition();
                pExpressions.add (new TPreparedField(stream.rowType.typeInstanceAt(fieldIndex), fieldIndex));
            }
            stream.operator = API.project_Table(stream.operator,
                    stream.rowType,
                    schema().userTableRowType(table),
                    null,
                    pExpressions);
        }
        return stream.operator; 
    }
    
    protected RowStream assembleValueScan(UserTable table) {
        RowStream stream = new RowStream();
        List<BindableRow> bindableRows = new ArrayList<>();
        
        int nfields = table.getColumns().size();
        TInstance[] types = new TInstance[nfields];
        TInstance varchar = Column.generateTInstance(null, Types.VARCHAR, 65535L, null, false);
        List<TPreparedExpression> tExprs = new ArrayList<>();
        for (int index = 0; index < table.getColumns().size(); index++) {
            tExprs.add(index, new TPreparedParameter(index, varchar));
            types[index] = varchar;
        }
        stream.rowType =  schema().newValuesType(types);
        bindableRows.add(BindableRow.of(stream.rowType, null, tExprs, queryContext()));
        stream.operator = API.valuesScan_Default(bindableRows, stream.rowType);
        return stream;
    }
    
    protected RowStream assembleProjectTable (RowStream input, UserTable table) {
        
        int nfields = input.rowType.nFields();
        List<TPreparedExpression> insertsP = null;
        UserTableRowType targetRowType = schema().userTableRowType(table);
        insertsP = new ArrayList<>(targetRowType.nFields());
        
        for (int i = 0; i < nfields; ++i) {
            insertsP.add(new TPreparedField(input.rowType.typeInstanceAt(i), i));
        }

        TPreparedExpression[] row = new TPreparedExpression[targetRowType.nFields()];
        for (int i = 0; i < nfields; i++) {
            row[i] = new TPreparedField (input.rowType.typeInstanceAt(i), i);
        }
        // Insert the sequence generator and column default values
        for (int i = 0, len = targetRowType.nFields(); i < len; ++i) {
            Column column = table.getColumnsIncludingInternal().get(i);

            if (row[i] == null) {
                TInstance tinst = targetRowType.typeInstanceAt(i);
                final PValue defaultValueSource = new PValue(tinst);
                defaultValueSource.putNull();
                row[i] = new TPreparedLiteral(tinst, defaultValueSource);
            } else if (!column.tInstance().equals(row[i].resultType())) {
                TCast cast = registryService().getCastsResolver().cast(row[i].resultType().typeClass(), 
                        column.tInstance().typeClass()); 
                row[i] = new TCastExpression(row[i], cast, column.tInstance(), queryContext());
            }
            
            if (column.getIdentityGenerator() != null) {
                Sequence sequence = table.getColumn(i).getIdentityGenerator();
                row[i] = sequenceGenerator(sequence, column, row[i]);
            } else if (column.getDefaultValue() != null) {
                final String defaultValue = column.getDefaultValue();
                final PValue defaultValueSource;
                TInstance tinst = targetRowType.typeInstanceAt(i);
                TCast cast = tinst.typeClass().castFromVarchar();
                if (cast != null) {
                    defaultValueSource = new PValue(tinst);
                    TInstance valInst = MString.VARCHAR.instance(defaultValue.length(), false);
                    TExecutionContext executionContext = new TExecutionContext(
                            Collections.singletonList(valInst),
                            tinst, queryContext());
                    cast.evaluate(executionContext,
                                  new PValue(MString.varcharFor(defaultValue), defaultValue),
                                  defaultValueSource);
                } else {
                    defaultValueSource = new PValue (tinst, defaultValue);
                }
                row[i] = generateIfNull (insertsP.get(i), new TPreparedLiteral(tinst, defaultValueSource));
            }
        }
        insertsP = Arrays.asList(row);
        
        input.operator = API.project_Table(input.operator, input.rowType,
                targetRowType, null, insertsP);
        input.rowType = targetRowType;
        return input;
    }

    public TPreparedExpression sequenceGenerator(Sequence sequence, Column column, TPreparedExpression expression) {
        OverloadResolver<TValidatedScalar> resolver = registryService().getScalarsResolver();
        TInstance instance = column.tInstance();
        
        List<TPreptimeValue> input = new ArrayList<>(2);
        input.add(PValueSources.fromObject(sequence.getSequenceName().getSchemaName(), AkType.VARCHAR));
        input.add(PValueSources.fromObject(sequence.getSequenceName().getTableName(), AkType.VARCHAR));
    
        TValidatedScalar overload = resolver.get("NEXTVAL", input).getOverload();
    
        List<TPreparedExpression> arguments = new ArrayList<>(2);
        arguments.add(new TPreparedLiteral(input.get(0).instance(), input.get(0).value()));
        arguments.add(new TPreparedLiteral(input.get(1).instance(), input.get(1).value()));
    
        TInstance overloadResultInstance = overload.resultStrategy().fixed(column.getNullable());
        TPreparedExpression seqExpr =  new TPreparedFunction(overload, overloadResultInstance,
                        arguments, queryContext());
    
        if (!instance.equals(overloadResultInstance)) {
            TCast tcast = registryService().getCastsResolver().cast(seqExpr.resultType(), instance);
            seqExpr = 
                    new TCastExpression(seqExpr, tcast, instance, queryContext());
        }
        // If the row expression is not null (i.e. the user supplied values for this column)
        // and the column is has "BY DEFAULT" as the identity generator
        // replace the SequenceNextValue is a IFNULL(<user value>, <sequence>) expression. 
        if (expression != null && 
                column.getDefaultIdentity() != null &&
                column.getDefaultIdentity().booleanValue()) { 
            seqExpr = generateIfNull (expression, seqExpr);
        }
        
        return seqExpr;
    }
    
    private TPreparedExpression generateIfNull(TPreparedExpression expr1, TPreparedExpression expr2 ) {
        List<TPreptimeValue> ifNullInput = new ArrayList<>(2);
        ifNullInput.add(new TNullExpression(expr1.resultType()).evaluateConstant(queryContext()));
        ifNullInput.add(new TNullExpression(expr2.resultType()).evaluateConstant(queryContext()));

        OverloadResult<TValidatedScalar> ifNullResult = registryService().getScalarsResolver().get("IFNULL", ifNullInput);
        TValidatedScalar ifNullOverload = ifNullResult.getOverload();
        List<TPreparedExpression> ifNullArgs = new ArrayList<>(2);
        ifNullArgs.add(expr1);
        ifNullArgs.add(expr2);
        return new TPreparedFunction(ifNullOverload, ifNullResult.getPickedInstance(),
                ifNullArgs, queryContext());
        
    }
}
