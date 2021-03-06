package ru.bmstu.sqlfornosql.adapters.mongo;

import org.bson.BsonDocument;
import org.bson.BsonValue;
import ru.bmstu.sqlfornosql.adapters.sql.selectfield.Column;
import ru.bmstu.sqlfornosql.adapters.sql.selectfield.SelectField;
import ru.bmstu.sqlfornosql.adapters.sql.selectfield.SelectFieldExpression;
import ru.bmstu.sqlfornosql.model.Row;
import ru.bmstu.sqlfornosql.model.RowType;
import ru.bmstu.sqlfornosql.model.Table;
import ru.bmstu.sqlfornosql.model.TableIterator;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import static ru.bmstu.sqlfornosql.adapters.mongo.MongoHolder.MONGO_ID;

public class MongoMapper {
    public Table map(Iterator<BsonDocument> mongoResult, MongoHolder query) {
        if (!query.getGroupBys().isEmpty() ||
                query.getSelectFields().stream().allMatch(field -> field instanceof SelectFieldExpression)
        ) {
            return mapGroupBy(mongoResult, query);
        } else {
            return mapFind(mongoResult, query);
        }
    }

    public Table mapGroupBy(Iterator<BsonDocument> mongoResult, MongoHolder query) {
        Table table = new Table();
        for (int rowCount = 0; mongoResult.hasNext() && rowCount < TableIterator.BATCH_SIZE; rowCount++) {
            BsonDocument element = mongoResult.next();
            //System.out.println(element);
            Row row = new Row(table);
            Map<SelectField, RowType> typeMap = new LinkedHashMap<>();

            if (!query.hasAggregateFunctions()) {
                fillRowFromDocument(element, row, typeMap, query);
            } else {
                if (element.get(MONGO_ID).isDocument()) {
                    fillRowFromDocument(element, row, typeMap, query);
                } else {
                    String field = MongoUtils.normalizeColumnName(query.getProjection().getString(MONGO_ID));
                    if (query.getSelectFields().stream()
                            .map(SelectField::getNonQualifiedContent)
                            .anyMatch(col -> col.equals(field))
                    ) {
                        throw new IllegalStateException("Not supposed to be here");
                    }
                }

                for (String column : element.keySet()) {
                    if (!column.equals(MONGO_ID) &&
                            query.getSelectFields().stream()
                                    .map(SelectField::getNonQualifiedContent)
                                    .map(MongoUtils::makeMongoColName)
                                    .anyMatch(col -> col.equals(column))
                    ) {
                        addValueToRow(row, typeMap, query, element, column);
                    }
                }
            }

            table.add(row, typeMap);
        }

        return table;
    }

    public Table mapCountAll(long count, MongoHolder query) {
        Column column = new Column("count");
        column.setSource(query.getFromItem());
        return new Table().add(column, count, RowType.INT);
    }

    public Table mapFind(Iterator<BsonDocument> mongoResult, MongoHolder query) {
        Table table = new Table();
        for (int rowCount = 0; mongoResult.hasNext() && rowCount < TableIterator.BATCH_SIZE; rowCount++) {
            BsonDocument mongoRow = mongoResult.next();
            //System.out.println(mongoRow);
            Row row = new Row(table);
            Map<SelectField, RowType> typeMap = new LinkedHashMap<>();

            Map<String, SelectField> columns = new HashMap<>();
            for (Map.Entry<String, BsonValue> pair : mongoRow.entrySet()) {
                if (!query.isSelectAll()) {
                    addValueToRow(row, typeMap, query.getByMongoName(pair.getKey()), pair.getValue());
                } else {
                    SelectField column;
                    if (columns.containsKey(pair.getKey())) {
                        column = columns.get(pair.getKey());
                    } else {
                        column = new Column(pair.getKey()).withSource(query.getFromItem());
                        columns.put(pair.getKey(), column);
                    }
                    addValueToRow(row, typeMap, column, pair.getValue());
                }
            }

            table.add(row, typeMap);
        }

        return table;
    }

    private void fillRowFromDocument(BsonDocument element, Row row, Map<SelectField, RowType> typeMap, MongoHolder query) {
        if (element.get(MONGO_ID).isDocument()) {
            BsonDocument idDocument = element.getDocument(MONGO_ID);
            for (String column : idDocument.keySet()) {
                addValueToRow(row, typeMap, query, idDocument, column);
            }
        } else {
            if (!query.isSelectAll()) {
                addValueToRow(
                        row,
                        typeMap,
                        query.getByMongoName(
                                MongoUtils.normalizeColumnName(query.getProjection().getString(MONGO_ID))
                        ),
                        element.get(MONGO_ID)
                );
            } else {
//                addValueToRow(
//                        row,
//                        typeMap,
//                        query.getByMongoName(
//                                MongoUtils.normalizeColumnName(query.getProjection().getString(MONGO_ID))
//                        ),
//                        element.get(MONGO_ID)
//                );
                throw new IllegalStateException("Not supposed to be here");
            }
        }
    }

    private void addValueToRow(Row row, Map<SelectField, RowType> typeMap, MongoHolder query, BsonDocument document, String column) {
        BsonValue value = document.get(MongoUtils.makeMongoColName(column));
        if (!query.isSelectAll()) {
            addValueToRow(row, typeMap, query.getByMongoName(column), value);
        } else {
            //addValueToRow(row, typeMap, query.getByMongoName(column), value);
            throw new IllegalStateException("Not supposed to be here");
        }
    }

    //TODO этот метод должен быть внутри класса Row
    private void addValueToRow(Row row, Map<SelectField, RowType> typeMap, SelectField key, BsonValue value) {
        if (value.isBoolean()) {
            row.add(key, value.asBoolean().getValue());
            typeMap.put(key, RowType.BOOLEAN);
        } else if (value.isDateTime()) {
            row.add(
                    key,
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(value.asDateTime().getValue()), ZoneId.systemDefault())
            );
            typeMap.put(key, RowType.DATE);
        } else if (value.isInt64()) {
            if (Integer.MAX_VALUE < value.asInt64().getValue()) {
                row.add(key, value.asInt64().getValue());
                typeMap.put(key, RowType.LONG);
            } else {
                row.add(key, value.asInt64().intValue());
                typeMap.put(key, RowType.INT);
            }
        } else if (value.isInt32()) {
            row.add(key, value.asInt32().getValue());
            typeMap.put(key, RowType.INT);
        } else if (value.isDouble()) {
            row.add(key, value.asDouble().doubleValue());
            typeMap.put(key, RowType.DOUBLE);
        } else if (value.isString()) {
            row.add(key, value.asString().getValue());
            typeMap.put(key, RowType.STRING);
        } else if (value.isNull()) {
            row.add(key, null);
            typeMap.put(key, RowType.NULL);
        } else if (value.isObjectId()) {
            row.add(key, value.asObjectId().getValue().toString());
            typeMap.put(key, RowType.STRING);
        } else {
            throw new IllegalArgumentException("Unsupported type of value");
        }
    }
}
