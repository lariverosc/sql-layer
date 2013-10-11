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

package com.foundationdb.qp.rowtype;

import com.foundationdb.ais.model.HKey;
import com.foundationdb.ais.model.Index;
import com.foundationdb.ais.model.TableName;
import com.foundationdb.ais.model.UserTable;
import com.foundationdb.server.explain.*;
import com.foundationdb.server.types.TInstance;
import com.foundationdb.util.FilteringIterator;

import java.util.ArrayList;
import java.util.Iterator;

public class UserTableRowType extends AisRowType
{
    // Object interface

    @Override
    public String toString()
    {
        return table.toString();
    }

    // RowType interface

    @Override
    public int nFields()
    {
        return table.getColumnsIncludingInternal().size();
    }

    @Override
    public HKey hKey()
    {
        return table.hKey();
    }

    @Override
    public TInstance typeInstanceAt(int index) {
        return table.getColumnsIncludingInternal().get(index).tInstance();
    }

    @Override
    public ConstraintChecker constraintChecker()
    {
        return constraintChecker;
    }

    @Override
    public CompoundExplainer getExplainer(ExplainContext context)
    {
        CompoundExplainer explainer = super.getExplainer(context);
        TableName tableName = table.getName();
        explainer.addAttribute(Label.TABLE_SCHEMA, PrimitiveExplainer.getInstance(tableName.getSchemaName()));
        explainer.addAttribute(Label.TABLE_NAME, PrimitiveExplainer.getInstance(tableName.getTableName()));
        return explainer;
    }

    // UserTableRowType interface
    @Override
    public UserTable userTable()
    {
        return table;
    }

    @Override
    public boolean hasUserTable() {
        return table != null;
    }

    public IndexRowType indexRowType(Index index)
    {
        return indexRowTypes.get(index.getIndexId());
    }

    public void addIndexRowType(IndexRowType indexRowType)
    {
        Index index = indexRowType.index();
        int requiredEntries = index.getIndexId() + 1;
        while (indexRowTypes.size() < requiredEntries) {
            indexRowTypes.add(null);
        }
        indexRowTypes.set(index.getIndexId(), indexRowType);
    }

    public Iterable<IndexRowType> indexRowTypes() {
        return new Iterable<IndexRowType>() {
            @Override
            public Iterator<IndexRowType> iterator() {
                return new FilteringIterator<IndexRowType>(indexRowTypes.iterator(), false) {
                    @Override
                    protected boolean allow(IndexRowType item) {
                        return item != null;
                    }
                };
            }
        };
    }

    public UserTableRowType(Schema schema, UserTable table)
    {
        super(schema, table.getTableId());
        this.table = table;
        typeComposition(new SingleBranchTypeComposition(this, table));
        constraintChecker = new UserTableRowChecker(this);
    }

    // Object state

    private final UserTable table;
    // Type of indexRowTypes is ArrayList, not List, to make it clear that null values are permitted.
    private final ArrayList<IndexRowType> indexRowTypes = new ArrayList<>();
    private final ConstraintChecker constraintChecker;
}