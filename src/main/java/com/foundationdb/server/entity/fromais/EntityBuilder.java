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

package com.foundationdb.server.entity.fromais;

import com.foundationdb.ais.model.Column;
import com.foundationdb.ais.model.FullTextIndex;
import com.foundationdb.ais.model.GroupIndex;
import com.foundationdb.ais.model.Index;
import com.foundationdb.ais.model.IndexColumn;
import com.foundationdb.ais.model.Join;
import com.foundationdb.ais.model.JoinColumn;
import com.foundationdb.ais.model.PrimaryKey;
import com.foundationdb.ais.model.UserTable;
import com.foundationdb.server.entity.model.Entity;
import com.foundationdb.server.entity.model.EntityCollection;
import com.foundationdb.server.entity.model.FieldProperty;
import com.foundationdb.server.entity.model.IndexField;
import com.foundationdb.server.entity.model.EntityField;
import com.foundationdb.server.entity.model.EntityIndex;
import com.foundationdb.server.entity.model.Validation;
import com.foundationdb.server.types.TClass;
import com.foundationdb.server.types.TInstance;
import com.google.common.base.Predicate;
import com.google.common.collect.BiMap;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class EntityBuilder {

    public EntityBuilder(UserTable rootTable) {
        entity = Entity.modifiableEntity(uuidOrCreate(rootTable));
        buildContainer(entity, rootTable);
    }

    private void buildFields(List<EntityField> fields, UserTable table) {
        for (Column column : table.getColumns()) {
            TInstance tInstance = column.tInstance();
            TClass tClass = tInstance.typeClass();
            String type = tClass.name().unqualifiedName().toLowerCase();
            EntityField scalar = EntityField.modifiableScalar(uuidOrCreate(column), type);
            scalar.setName(column.getName());

            Map<String, Object> properties = scalar.getProperties();
            Collection<Validation> validations = scalar.getValidations();
            validations.add(new Validation("required", !tInstance.nullability()));
            for (com.foundationdb.server.types.Attribute t3Attr : tClass.attributes()) {
                String attrName = t3Attr.name().toLowerCase();
                Object attrValue = tInstance.attributeToObject(t3Attr);
                if (tClass.attributeIsPhysical(t3Attr))
                    properties.put(attrName, attrValue);
                else
                    validations.add(new Validation(attrName, attrValue));
            }
            if(column.getDefaultIdentity() != null) {
                properties.put(FieldProperty.IdentityProperty.PROPERTY_NAME, FieldProperty.IdentityProperty.create(column));
            }
            fields.add(scalar);
        }
    }

    private void buildIdentifying(List<String> identifying, UserTable rootTable) {
        PrimaryKey pk = rootTable.getPrimaryKey();
        if (pk != null) {
            for (Column c : pk.getColumns())
                identifying.add(c.getName());
        }
    }

    private void buildGroupingFields(List<String> groupingFields, UserTable table) {
        for (JoinColumn joinColumn : table.getParentJoin().getJoinColumns())
            groupingFields.add(joinColumn.getChild().getName());
    }

    private void buildCollections(Collection<EntityCollection> collections, UserTable table) {
        List<Join> childJoins = table.getChildJoins();
        for (Join childJoin : childJoins) {
            UserTable child = childJoin.getChild();
            collections.add(buildCollection(child));
        }
    }

    private EntityCollection buildCollection(UserTable table) {
        EntityCollection collection = EntityCollection.modifiableCollection(uuidOrCreate(table));
        buildContainer(collection, table);
        return collection;
    }

    private void buildContainer(Entity container, UserTable table) {
        buildFields(container.getFields(), table);
        buildIdentifying(container.getIdentifying(), table);
        if (container instanceof EntityCollection) {
            EntityCollection collection = (EntityCollection) container;
            buildGroupingFields(collection.getGroupingFields(), table);
        }
        buildCollections(container.getCollections(), table);
        Set<String> uniques = buildIndexes(container.getIndexes(), table);
        buildUniques(container.getValidations(), uniques);
        container.setName(table.getName().getTableName());
    }

    /**
     * Build the indexes, and return back the names of the unique ones. This assumes that indexes have already
     * been compiled into the "indexes" collection.
     *
     * @param out the map to insert into
     * @param table the table to build from
     * @return the json names of the unique indexes
     */
    private Set<String> buildIndexes(BiMap<String, EntityIndex> out, UserTable table) {
        Set<String> uniques = new HashSet<>(out.size());
        for (Index index : getAllIndexes(table)) {
            if (index.isPrimaryKey() || index.getIndexName().getName().startsWith("__akiban"))
                continue;
            String jsonName = index.getIndexName().getName();
            List<IndexColumn> keyColumns = index.getKeyColumns();
            List<IndexField> indexFields = new ArrayList<>(keyColumns.size());
            int spatial = index.isSpatial() ? index.firstSpatialArgument() : -1;
            for (int i = 0; i < keyColumns.size(); i++) {
                IndexField.FieldName indexFieldName = buildIndexField(keyColumns, i, table);
                IndexField indexField = indexFieldName;
                if (i == spatial) {
                    IndexField.FieldName nextIndexFieldName = buildIndexField(keyColumns, ++i, table);
                    indexField = new IndexField.SpatialField(indexFieldName, nextIndexFieldName);
                }
                indexFields.add(indexField);
            }
            EntityIndex.IndexType indexType;
            indexType = (index instanceof FullTextIndex)
                    ? EntityIndex.IndexType.FULL_TEXT
                    : EntityIndex.IndexType.valueOf(index.getJoinType());
            EntityIndex entityIndex = new EntityIndex(indexFields, indexType);
            EntityIndex old = out.put(jsonName, entityIndex);
            if (old != null)
                throw new InconvertibleAisException("duplicate index name: " + jsonName);
            if (index.isUnique())
                uniques.add(jsonName);
        }
        return uniques;
    }

    private Iterable<? extends Index> getAllIndexes(final UserTable table) {
        Collection<? extends Index> tableIndexes = table.getIndexes();
        Collection<? extends Index> ftIndexes = table.getOwnFullTextIndexes();
        Collection<? extends Index> gis = Collections2.filter(table.getGroupIndexes(), new Predicate<GroupIndex>() {
            @Override
            public boolean apply(GroupIndex input) {
                return input.leafMostTable() == table;
            }
        });
        return Iterables.concat(tableIndexes, ftIndexes, gis);
    }

    private static IndexField.FieldName buildIndexField(List<IndexColumn> indexColumns, int i, UserTable contextTable) {
        Column column = indexColumns.get(i).getColumn();
        return (column.getTable() == contextTable)
                ? new IndexField.FieldName(column.getName())
                : new IndexField.QualifiedFieldName(column.getTable().getName().getTableName(), column.getName());
    }

    private void buildUniques(Collection<Validation> validation, Set<String> uniques) {
        for (String uniqueIndex : uniques) {
            validation.add(new Validation("unique", uniqueIndex));
        }
    }

    public Entity getEntity() {
        return entity;
    }

    private static UUID uuidOrCreate(UserTable table) {
        UUID uuid = table.getUuid();
        assert uuid != null : table;
        return uuid;
    }

    private UUID uuidOrCreate(Column column) {
        UUID uuid = column.getUuid();
        assert uuid != null : column;
        return uuid;
    }

    private final Entity entity;
}