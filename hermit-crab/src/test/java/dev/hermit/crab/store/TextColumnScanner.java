package dev.hermit.crab.store;

import dev.hermit.crab.testing.SecretChecks;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

final class TextColumnScanner {
    private TextColumnScanner() {
    }

    static void assertAbsent(DataSource dataSource, String... secrets) throws Exception {
        for (String secret : secrets) {
            if (secret == null || secret.length() < 8) {
                throw new AssertionError("scanner secret is too short");
            }
        }
        List<Column> columns = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE
                     FROM INFORMATION_SCHEMA.COLUMNS
                     WHERE TABLE_SCHEMA = 'PUBLIC'
                     """)) {
            while (resultSet.next()) {
                String table = resultSet.getString("TABLE_NAME");
                String column = resultSet.getString("COLUMN_NAME");
                String type = resultSet.getString("DATA_TYPE");
                if ("CREDENTIALS".equalsIgnoreCase(table) && "CIPHERTEXT".equalsIgnoreCase(column)) {
                    continue;
                }
                if (type != null && (type.contains("CHAR") || type.contains("CLOB"))) {
                    columns.add(new Column(table, column));
                }
            }
        }
        try (Connection connection = dataSource.getConnection()) {
            for (Column column : columns) {
                if (!column.table.matches("[A-Za-z0-9_]+") || !column.column.matches("[A-Za-z0-9_]+")) {
                    throw new AssertionError("unexpected identifier " + column.table + "." + column.column);
                }
                String sql = "SELECT \"" + column.column + "\" FROM \"" + column.table + "\"";
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery(sql)) {
                    while (rows.next()) {
                        String value = rows.getString(1);
                        for (String secret : secrets) {
                            SecretChecks.absent(value, secret);
                        }
                    }
                }
            }
        }
    }

    private record Column(String table, String column) {
    }
}
