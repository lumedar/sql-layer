/**
 * Copyright (C) 2009-2013 Akiban Technologies, Inc.
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

package com.akiban.server.entity.changes;

import com.akiban.ais.model.AISBuilder;
import com.akiban.ais.model.AkibanInformationSchema;
import com.akiban.ais.model.CharsetAndCollation;
import com.akiban.ais.model.Column;
import com.akiban.ais.model.Index;
import com.akiban.ais.model.Join;
import com.akiban.ais.model.TableName;
import com.akiban.ais.model.Type;
import com.akiban.ais.model.Types;
import com.akiban.ais.model.UserTable;
import com.akiban.server.entity.model.Entity;
import com.akiban.server.entity.model.EntityCollection;
import com.akiban.server.entity.model.EntityField;
import com.akiban.server.entity.model.EntityVisitor;
import com.akiban.server.entity.model.IndexField;
import com.akiban.server.entity.model.EntityIndex;
import com.akiban.server.entity.model.Validation;
import com.akiban.server.types3.TClass;
import com.akiban.server.types3.TInstance;
import com.akiban.server.types3.texpressions.Serialization;
import com.google.common.base.Function;
import com.google.common.collect.BiMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

public class EntityToAIS implements EntityVisitor {
    private static final Logger LOG = LoggerFactory.getLogger(EntityToAIS.class);

    private static final boolean ATTR_REQUIRED_DEFAULT = false;
    private static final Index.JoinType GI_JOIN_TYPE_DEFAULT = Index.JoinType.LEFT;

    private final String schemaName;
    private final AISBuilder builder = new AISBuilder();
    private final List<TableInfo> tableInfoStack = new ArrayList<>();
    private TableName groupName = null;
    private TableInfo curTable = null;
    private Set<String> uniqueValidations = new HashSet<>();

    private static final Function<String, TypeInfo> typeNameResolver = createTypeNameResolver();

    private static final class TypeInfo {

        private TypeInfo(Type type, TClass tClass) {
            this.type = type;
            this.tClass = tClass;
        }

        private final Type type;
        private final TClass tClass;
    }

    public EntityToAIS(String schemaName) {
        this.schemaName = schemaName;
    }

    //
    // EntityVisitor
    //

    @Override
    public void enterTopEntity(Entity entity) {
        String name = entity.getName();
        builder.createGroup(name, schemaName);
        groupName = new TableName(schemaName, name);
        beginTable(entity);
        builder.addTableToGroup(groupName, schemaName, name);
    }

    @Override
    public void leaveTopEntity() {
        curTable = null;
        groupName = null;
        uniqueValidations.clear();
    }

    private void visitField(EntityField field, Entity entity) {
        String name = field.getName();
        TypeInfo scalarType = typeNameResolver.apply(field.getType());
        assert scalarType != null : name;
        ColumnInfo info = getColumnInfo(scalarType,
                                        field.getProperties(),
                                        field.getValidations());
        if (entity.getIdentifying().contains(name))
            info.nullable = false;
        Column column = builder.column(schemaName, curTable.name,
                                       name, curTable.nextColPos++,
                                       scalarType.type.name(), info.param1, info.param2,
                                       info.nullable, false /*isAutoInc*/,
                                       info.charset, info.collation);
        column.setUuid(field.getUuid());
    }

    @Override
    public void enterCollections() {
        // Nothing
    }

    @Override
    public void enterCollection(EntityCollection collection) {
        TableInfo parent = curTable;
        beginTable(collection);
        curTable.fkFields = collection.getGroupingFields();
        parent.childTables.add(curTable);
    }

    @Override
    public void leaveCollection() {
        endTable();
    }

    @Override
    public void leaveCollections() {
        endTable();
        builder.basicSchemaIsComplete();
        builder.groupingIsComplete();
    }

    private void visitEntityValidations(Set<Validation> validations) {
        for(Validation v : validations) {
            if("unique".equals(v.getName())) {
                String indexName = (String)v.getValue();
                uniqueValidations.add(indexName);
            } else {
                LOG.warn("Ignored entity validation on {}: {}", groupName, v);
            }
        }
    }

    private void visitIndexes(BiMap<String, EntityIndex> indexes, Entity context) {
        for(Map.Entry<String,EntityIndex> entry : indexes.entrySet()) {
            String indexName = entry.getKey();
            List<IndexField> columns = entry.getValue().getFields();
            boolean isGI = isMultiTable(columns, context);
            boolean isUnique = uniqueValidations.contains(indexName);
            if(isGI) {
                if(isUnique) {
                    throw new IllegalArgumentException("Unique group index not allowed");
                }
                builder.groupIndex(groupName, indexName, false, GI_JOIN_TYPE_DEFAULT);
                int pos = 0;
                for(IndexField col : columns) {
                    TableName colName = getFieldName(col, context);
                    builder.groupIndexColumn(groupName, indexName, schemaName,
                            colName.getSchemaName(), colName.getTableName(), pos++);
                }
            } else {
                builder.index(schemaName, context.getName(), indexName, isUnique, Index.KEY_CONSTRAINT);
                int pos = 0;
                for(IndexField col : columns) {
                    TableName colName = getFieldName(col, context);
                    builder.indexColumn(schemaName, colName.getSchemaName(), indexName,
                            colName.getTableName(), pos++, true, null);
                }
            }
        }
    }

    private TableName getFieldName(IndexField indexField, Entity context) {
        TableName theColName;
        if (indexField instanceof IndexField.QualifiedFieldName) {
            IndexField.QualifiedFieldName qualified = (IndexField.QualifiedFieldName) indexField;
            theColName = new TableName(qualified.getEntityName(), qualified.getFieldName());
        }
        else if (indexField instanceof IndexField.FieldName) {
            IndexField.FieldName fieldName = (IndexField.FieldName) indexField;
            theColName = new TableName(context.getName(), fieldName.getFieldName());
        }
        else throw new AssertionError(indexField);
        return theColName;
    }

    //
    // EntityToAIS
    //

    public AkibanInformationSchema getAIS() {
        return builder.akibanInformationSchema();
    }

    //
    // Helpers
    //

    private void beginTable(Entity entity) {
        UserTable table = builder.userTable(schemaName, entity.getName());
        visitIndexes(entity.getIndexes(), entity);
        visitEntityValidations(entity.getValidations());
        table.setUuid(entity.getUuid());
        curTable = new TableInfo(entity.getName(), table, entity.getIdentifying());
        tableInfoStack.add(curTable);
        for (EntityField field : entity.getFields())
            visitField(field, entity);
    }

    private void endTable() {
        // Create joins to children.
        // Parent spinal columns are automatically propagated to each child.
        if(curTable.pksFields.isEmpty() && !curTable.childTables.isEmpty()) {
            throw new IllegalArgumentException("Has collections but no spine: " + curTable.name);
        }
        for(TableInfo child : curTable.childTables) {
            String joinName = child.name + "_" + curTable.name;
            builder.joinTables(joinName, schemaName, curTable.name, schemaName, child.name);

            List<String> pksFields = curTable.pksFields;
            List<String> fkFields = child.fkFields;
            if (fkFields == null || fkFields.isEmpty() || fkFields.size() != pksFields.size())
                throw new IllegalArgumentException("Grouping fields don't match parent's PK: " + fkFields
                        + " reference PK " + pksFields);
            for (int i = 0; i < pksFields.size(); i++) {
                String parentColName = pksFields.get(i);
                String childColName = fkFields.get(i);
                Column parentCol = curTable.table.getColumn(parentColName);
                Column newCol = Column.create(child.table, parentCol, childColName, child.nextColPos++);
                // Should be exactly the same *except* UUID
                newCol.setUuid(null);
                builder.joinColumns(joinName,
                        schemaName, curTable.name, parentColName,
                        schemaName, child.name, childColName);
            }
        }
        if(tableInfoStack.size() == 1) {
            // Create PKs at the end (root to leaf) so IDs are ordered as such. Shouldn't matter but is safe.
            createPrimaryKeys(builder, schemaName, curTable);
            addJoinsToGroup(builder, groupName, curTable);
        }
        tableInfoStack.remove(tableInfoStack.size() - 1);
        curTable = tableInfoStack.isEmpty() ? null : tableInfoStack.get(tableInfoStack.size() - 1);
    }

    private static void addJoinsToGroup(AISBuilder builder, TableName groupName, TableInfo curTable) {
        for(TableInfo child : curTable.childTables) {
            List<Join> joins = child.table.getCandidateParentJoins();
            assert joins.size() == 1 : joins;
            builder.addJoinToGroup(groupName, joins.get(0).getName(), 0);
            addJoinsToGroup(builder, groupName, child);
        }
    }

    private static void createPrimaryKeys(AISBuilder builder, String schemaName, TableInfo table) {
        if(!table.pksFields.isEmpty()) {
            builder.index(schemaName, table.name, Index.PRIMARY_KEY_CONSTRAINT, true, Index.PRIMARY_KEY_CONSTRAINT);
            int pos = 0;
            for(String column : table.pksFields) {
                builder.indexColumn(schemaName, table.name, Index.PRIMARY_KEY_CONSTRAINT, column, pos++, true, null);
            }
        }
        for(TableInfo child : table.childTables) {
            createPrimaryKeys(builder, schemaName, child);
        }
    }

    private static ColumnInfo getColumnInfo(TypeInfo type, Map<String,Object> props, Collection<Validation> validations)
    {
        ColumnInfo info = new ColumnInfo();

        Map<String,Object> fullProps = new HashMap<>();
        for (Map.Entry<String, Object> entry : props.entrySet())
            fullProps.put(entry.getKey().toUpperCase(), entry.getValue());

        for(Validation v : validations) {
            if("required".equals(v.getName())) {
                boolean isRequired = (Boolean)v.getValue();
                info.nullable = !isRequired;
            }
            else {
                fullProps.put(v.getName().toUpperCase(), v.getValue());
            }
        }

        for (Map.Entry<? extends com.akiban.server.types3.Attribute, ? extends Serialization> t3Attr
                : type.tClass.attributeSerializations().entrySet())
        {
            Serialization serialization = t3Attr.getValue();
            if (serialization != null) {
                switch (serialization) {
                case CHARSET:
                    info.charset = maybeString(fullProps, t3Attr.getKey());
                    break;
                case COLLATION:
                    info.collation = maybeString(fullProps, t3Attr.getKey());
                    break;
                case LONG_1:
                    info.param1 = maybeLong(fullProps, t3Attr.getKey());
                    break;
                case LONG_2:
                    info.param2 = maybeLong(fullProps, t3Attr.getKey());
                    break;
                default:
                    throw new AssertionError(serialization + " for attribute " + t3Attr);
                }
            }
        }
        if (!fullProps.isEmpty()) {
            LOG.warn("unused properties or validations for column of type {}: {}", type.tClass, fullProps);
        }
        return info;
    }

    private static boolean isMultiTable(List<IndexField> columns, Entity context) {
        for (IndexField field : columns) {
            if (field instanceof IndexField.QualifiedFieldName) {
                IndexField.QualifiedFieldName qualified = (IndexField.QualifiedFieldName) field;
                String fieldEntity = qualified.getEntityName();
                if (!fieldEntity.equals(context.getName()))
                    return true;
            }
        }
        return false;
    }

    private static Long maybeLong(Map<String, Object> props, com.akiban.server.types3.Attribute attribute) {
        Object o = null;
        if (props.containsKey(attribute.name().toUpperCase())) {
            o = props.remove(attribute.name());
        }
        if (o == null)
            return null;
        else if (o.getClass() == Long.class)
            return (Long) o;
        else
            return ((Number)o).longValue();
    }

    private static String maybeString(Map<String, Object> props, com.akiban.server.types3.Attribute attribute) {
        Object o = null;
        if (props.containsKey(attribute.name().toUpperCase()))
            o = props.remove(attribute.name());
        return (o != null) ? o.toString() : null;
    }


    private static class TableInfo {
        public final String name;
        public final UserTable table;
        public final List<TableInfo> childTables;
        public final List<String> pksFields;
        public int nextColPos;
        public List<String> fkFields;

        public TableInfo(String name, UserTable table, List<String> pkFields) {
            this.name = name;
            this.table = table;
            this.childTables = new ArrayList<>();
            this.pksFields = pkFields;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static Function<String, TypeInfo> createTypeNameResolver() {
        AkibanInformationSchema ais = new AkibanInformationSchema();
        Collection<Type> aisTypes = ais.getTypes();
        final Map<String, TypeInfo> types = new HashMap<>(aisTypes.size());
        CharsetAndCollation dummyCharset = ais.getCharsetAndCollation();
        Set<Type> unsupportedTypes = Types.unsupportedTypes();
        for (Type type : aisTypes) {
            if (unsupportedTypes.contains(type))
                continue;
            // We create a dummy instance using values we don't care about, but which will be valid for all types.
            // All we need from it is the TClass's name
            TInstance dummyInstance = Column.generateTInstance(dummyCharset, type, 3L, 3L, true);
            TClass tClass = dummyInstance.typeClass();
            String typeName = tClass.name().unqualifiedName();
            if (null != types.put(typeName.toLowerCase(), new TypeInfo(type, tClass)))
                throw new RuntimeException("can't compute (name -> Type) map because of conflict: " + typeName);
        }
        return new Function<String, TypeInfo>() {
            @Override
            public TypeInfo apply(String input) {
                TypeInfo type = types.get(input.toLowerCase());
                if (type == null)
                    throw new NoSuchElementException(input);
                return type;
            }
        };
    }

    private static class ColumnInfo {
        public Long param1;
        public Long param2;
        public String charset;
        public String collation;
        public boolean nullable = !ATTR_REQUIRED_DEFAULT;
    }
}