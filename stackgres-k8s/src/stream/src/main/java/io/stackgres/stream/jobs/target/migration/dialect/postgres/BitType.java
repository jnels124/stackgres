/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.stackgres.stream.jobs.target.migration.dialect.postgres;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.debezium.connector.jdbc.ValueBindDescriptor;
import io.debezium.connector.jdbc.dialect.DatabaseDialect;
import io.debezium.connector.jdbc.relational.ColumnDescriptor;
import io.debezium.connector.jdbc.type.AbstractType;
import io.debezium.connector.jdbc.type.Type;
import io.debezium.data.Bits;
import io.debezium.util.Strings;
import org.apache.kafka.connect.data.Schema;

/**
 * An implementation of {@link Type} for {@link Bits} types.
 *
 * @author Chris Cranford
 */
class BitType extends AbstractType {

    public static final BitType INSTANCE = new BitType();

    @Override
    public String[] getRegistrationKeys() {
        return new String[]{ Bits.LOGICAL_NAME, "BIT", "VARBIT" };
    }

    @Override
    public String getQueryBinding(ColumnDescriptor column, Schema schema, Object value) {
        if (isBitOne(schema)) {
            final Optional<String> columnType = getSourceColumnType(schema);
            if (columnType.isPresent() && "BIT".equals(columnType.get())) {
                final Optional<String> columnSize = getSourceColumnSize(schema);
                if (columnSize.isPresent() && "1".equals(columnSize.get())) {
                    return "cast(? as bit)";
                }
            }
        }
        return "cast(? as bit varying)";
    }

    @Override
    public String getDefaultValueBinding(DatabaseDialect dialect, Schema schema, Object value) {
        // todo: add support for BIT/VARBIT/BIT VARYING(n) default values
        return null;
    }

    @Override
    public String getTypeName(DatabaseDialect dialect, Schema schema, boolean key) {
        if (isBitOne(schema)) {
            return "bit";
        }

        final int bitSize = Integer.parseInt(schema.parameters().get(Bits.LENGTH_FIELD));
        if (Integer.MAX_VALUE == bitSize) {
            // this is the only way to detect a varying bit field without type propagation
            return "bit varying";
        }

        final Optional<String> columnType = getSourceColumnType(schema);
        if (columnType.isPresent() && "VARBIT".equals(columnType.get())) {
            return String.format("bit varying(%d)", bitSize);
        }

        return String.format("bit(%d)", bitSize);
    }

    @Override
    public List<ValueBindDescriptor> bind(int index, Schema schema, Object value) {

        if (value == null) {
            return List.of(new ValueBindDescriptor(index, null));
        }

        if (isBitOne(schema) && (value instanceof Boolean)) {
            return List.of(new ValueBindDescriptor(index, ((boolean) value) ? '1' : '0'));
        }

        //this is a simple varbit case
        if ((value instanceof byte[]) && ((byte[]) value).length == 0) {
            return List.of(new ValueBindDescriptor(index, '0'));
        }

        final int length = Integer.parseInt(schema.parameters().get(Bits.LENGTH_FIELD));
        final String binaryBitString = new BigInteger((byte[]) value).toString(2);
        if (length == Integer.MAX_VALUE) {
            return List.of(new ValueBindDescriptor(index, binaryBitString));
        }

        return List.of(new ValueBindDescriptor(index, Strings.justifyRight(binaryBitString, length, '0')));
    }

    private boolean isBitOne(Schema schema) {
        return Objects.isNull(schema.name());
    }

}
