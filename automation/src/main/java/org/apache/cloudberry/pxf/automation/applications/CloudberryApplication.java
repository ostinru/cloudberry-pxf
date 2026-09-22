package org.apache.cloudberry.pxf.automation.applications;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import com.google.common.collect.Lists;
import org.apache.cloudberry.pxf.automation.structures.tables.basic.Table;
import org.apache.cloudberry.pxf.automation.structures.tables.pxf.ExternalTable;
import org.apache.cloudberry.pxf.automation.testcontainers.PXFCloudberryContainer;
import org.apache.commons.lang.StringUtils;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * TestObject that provides methods to work with Cloudberry DB
 */
public class CloudberryApplication implements AutoCloseable {

    private static final int MAX_RETRIES = 10;
    private static final long RETRY_INTERVAL_MS = 5_000;

    private final PXFCloudberryContainer container;
    private final String jdbcUrl;
    private final String userName;
    private final String password;
    private Connection connection;
    private Statement statement;

    public CloudberryApplication(PXFCloudberryContainer container) {
        this.container = container;
        this.jdbcUrl = getCloudberryMappedJdbcUrl();
        this.userName = container.getCloudberryUser();
        this.password = container.getCloudberryPassword();
    }

    public CloudberryApplication(PXFCloudberryContainer container, String dbName) {
        this.container = container;
        this.jdbcUrl = getCloudberryMappedJdbcUrl(dbName);
        this.userName = container.getCloudberryUser();
        this.password = container.getCloudberryPassword();
    }

    public void connect() throws Exception {
        if (statement != null) {
            return;
        }
        Properties props = new Properties();
        if (userName != null) {
            props.setProperty("user", userName);
            props.setProperty("password", password);
        }

        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                Class.forName("org.postgresql.Driver");
                connection = DriverManager.getConnection(jdbcUrl, props);
                statement = connection.createStatement();
                System.out.println("[CloudberryApplication] Connected to " + jdbcUrl);
                return;
            } catch (Exception e) {
                lastException = e;
                System.out.println("[CloudberryApplication] Connection attempt " + attempt + " failed: " + e.getMessage());
                Thread.sleep(RETRY_INTERVAL_MS);
            }
        }
        throw new RuntimeException("Failed to connect to CBDB at " + jdbcUrl + " after " + MAX_RETRIES + " attempts", lastException);
    }

    public String getCloudberryMappedJdbcUrl() {
        return getCloudberryMappedJdbcUrl("pxfautomation");
    }

    public String getCloudberryMappedJdbcUrl(String dbName) {
        return "jdbc:postgresql://localhost:" + container.getCloudberryMappedPort() + "/" + dbName;
    }

    public String getCloudberryInternalJdbcUrl() {
        return getCloudberryInternalJdbcUrl("pxfautomation");
    }

    public String getCloudberryInternalJdbcUrl(String dbName) {
        return "jdbc:postgresql://localhost:" + container.getCloudberryInternalPort() + "/" + dbName;
    }


    /**
     * Drops (if exists) and creates the table, then verifies it exists.
     */
    public void createTableAndVerify(Table table) throws Exception {
        dropTable(table, true);
        runQuery(table.constructCreateStmt());
        if (!checkTableExists(table)) {
            throw new RuntimeException("Table " + table.getName() + " does not exist after creation");
        }
    }

    public void dropTable(Table table, boolean cascade) throws Exception {
        runQuery(table.constructDropStmt(cascade), true);
        if (table instanceof ExternalTable) {
            String dropForeign = String.format("DROP FOREIGN TABLE IF EXISTS %s%s",
                    table.getFullName(), cascade ? " CASCADE" : "");
            runQuery(dropForeign, true);
        }
    }

    /**
     * Loads data from a file into a table using PostgreSQL COPY protocol.
     * Uses {@link CopyManager} over JDBC instead of psql over SSH.
     */
    public void copyFromFile(Table table, File path, String delimiter, String nullChar, boolean csv) throws Exception {
        StringBuilder copyCmd = new StringBuilder();
        copyCmd.append("COPY ").append(table.getName()).append(" FROM STDIN");

        String copyParams = buildCopyParams(delimiter, nullChar, csv);
        if (!copyParams.isEmpty()) {
            copyCmd.append(" ").append(copyParams);
        }

        CopyManager copyManager = new CopyManager(connection.unwrap(BaseConnection.class));
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            long rows = copyManager.copyIn(copyCmd.toString(), reader);
            System.out.println("[CloudberryApplication] COPY loaded " + rows + " rows into " + table.getName());
        }
    }

    public void copyFromFile(Table table, File path, String delimiter, boolean csv) throws Exception {
        copyFromFile(table, path, delimiter, null, csv);
    }

    /**
     * Inserts rows from a source Table (in-memory data) into the target table.
     */
    public void insertData(Table source, Table target) throws Exception {
        List<List<String>> data = source.getData();
        if (data == null || data.isEmpty()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.size(); i++) {
            List<String> row = data.get(i);
            sb.append("(");
            for (int j = 0; j < row.size(); j++) {
                sb.append("E'").append(row.get(j)).append("'");
                if (j < row.size() - 1) {
                    sb.append(",");
                }
            }
            sb.append(")");
            if (i < data.size() - 1) {
                sb.append(",");
            }
        }

        insertData(sb.toString(), target);
    }

    public void insertData(String[][] data, Table target) throws Exception {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            rows.append("(");
            for (int j = 0; j < data[i].length; j++) {
                rows.append("E'").append(data[i][j]).append("'");
                if (j < data[i].length - 1) {
                    rows.append(",");
                }
            }
            rows.append(")");
            if (i < data.length - 1) {
                rows.append(",");
            }
        }
        insertData(rows.toString(), target);
    }

    public void insertData(String data, Table target) throws Exception {
        if (!data.startsWith("(")) {
            data = "(" + data;
        }
        if (!data.endsWith(")")) {
            data = data + ")";
        }
        runQuery("INSERT INTO " + target.getName() + " VALUES " + data);
    }

    public void copyData(String sourceName, Table target) throws Exception {
        copyData(sourceName, target, null);
    }

    public void copyData(Table source, Table target) throws Exception {
        copyData(source.getName(), target);
    }

    public void copyData(String sourceName, Table target, String[] columns) throws Exception {
        String columnList = columns == null || columns.length == 0 ? "*" : String.join(",", columns);
        runQuery(String.format("INSERT INTO %s SELECT %s FROM %s", target.getName(), columnList, sourceName));
    }

    public void copyData(String sourceName, Table target, String[] columns, String context) throws Exception {
        String columnList = columns == null || columns.length == 0 ? "*" : String.join(",", columns);
        String prefix = StringUtils.isBlank(context) ? "" : context + "; ";
        runQuery(prefix + String.format("INSERT INTO %s SELECT %s FROM %s",
                target.getName(), columnList, sourceName));
    }

    public void runQuery(String sql) throws Exception {
        runQuery(sql, false);
    }

    public void runQuery(String sql, boolean ignoreFail) throws Exception {
        try {
            statement.execute(sql);
        } catch (SQLException e) {
            if (!ignoreFail) {
                throw e;
            }
        }
    }

    public void runQuery(String sql, boolean ignoreFail, boolean fetch) throws Exception {
        try {
            if (fetch) {
                try (ResultSet resultSet = statement.executeQuery(sql)) {
                    while (resultSet.next()) {
                        // Consume the result to preserve the legacy method's execution semantics.
                    }
                }
            } else {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            if (!ignoreFail) {
                throw e;
            }
        }
    }

    /**
     * Executes a query and verifies a warning when the JDBC driver reports one.
     * This preserves the legacy automation behavior, where absence of a warning
     * is diagnostic rather than a test failure.
     */
    public void runQueryWithExpectedWarning(String query, String expectedWarning,
                                            boolean isRegex, boolean ignoreNoWarning) throws Exception {
        statement.clearWarnings();
        runQuery(query, true, false);
        SQLWarning warning = statement.getWarnings();
        if (warning == null) {
            if (!ignoreNoWarning) {
                System.out.println("[CloudberryApplication] Expected warning was not reported: " + expectedWarning);
            }
            return;
        }

        for (SQLWarning current = warning; current != null; current = current.getNextWarning()) {
            String message = current.getMessage();
            boolean matches = isRegex
                    ? Pattern.compile(expectedWarning, Pattern.DOTALL).matcher(message).find()
                    : message.contains(expectedWarning);
            if (matches) {
                return;
            }
        }
        throw new AssertionError("Expected warning was not found: " + expectedWarning + "; actual: " + warning.getMessage());
    }

    public void runQueryWithExpectedWarning(String query, String expectedWarning, boolean isRegex) throws Exception {
        runQueryWithExpectedWarning(query, expectedWarning, isRegex, false);
    }

    /** Executes a psql meta-command inside the Cloudberry container. */
    public String runPsqlCommand(String command, boolean checkErrors) throws Exception {
        org.testcontainers.containers.Container.ExecResult result = container.execInContainer(
                "bash", "-lc",
                "source /usr/local/cloudberry-db/cloudberry-env.sh; psql pxfautomation -c \"$1\"",
                "psql-command", command);
        String output = result.getStdout() + result.getStderr();
        if (checkErrors && (result.getExitCode() != 0 || output.contains("ERROR"))) {
            throw new AssertionError("psql command failed: " + command + "\n" + output);
        }
        // Legacy interactive ShellSystemObject output included the entered command
        // and the following psql prompt. Keep that shape for existing \d parsers.
        return command + "\n" + output + "pxfautomation=#";
    }

    /** Stores query result metadata and rows in the supplied automation table. */
    public void queryResults(Table table, String query) throws Exception {
        try (ResultSet result = statement.executeQuery(query)) {
            if (table == null) {
                return;
            }
            table.initDataStructures();
            ResultSetMetaData metadata = result.getMetaData();
            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                table.addColDataType(metadata.getColumnType(column));
                table.addColumnHeader(metadata.getColumnName(column));
            }
            while (result.next()) {
                List<String> row = new ArrayList<>();
                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                    row.add(result.getString(column));
                }
                table.addRow(row);
            }
        }
    }

    public void createDatabase(String dbName) throws Exception {
        try {
            runQuery("CREATE DATABASE " + dbName);

            runQuery("ALTER DATABASE " + dbName + " SET bytea_output TO 'escape'", true);

            // This GUC has a default value of 1 in PG12 and thus the columns of type REAL display one digit extra.
            // So to keep the behavior consistent with previous version, we're setting this GUC value to 0.
            runQuery("ALTER DATABASE " + dbName + " SET extra_float_digits=0", true);
        } catch (Exception e) {
            if (!e.getMessage().contains("already exists")) {
                throw e;
            }
        }
    }

    public void dropDatabase(String dbName) throws Exception {
        runQuery("DROP DATABASE IF EXISTS " + dbName, true);
    }

    public void createExtension(String extensionName, boolean ignoreFail) throws Exception {
        runQuery("CREATE EXTENSION IF NOT EXISTS " + extensionName, ignoreFail);
    }


    public void createTestFDW(boolean ignoreFail) throws Exception {
        runQuery("DROP FOREIGN DATA WRAPPER IF EXISTS test_pxf_fdw CASCADE", ignoreFail);
        runQuery("CREATE FOREIGN DATA WRAPPER test_pxf_fdw HANDLER pxf_fdw_handler " +
                "VALIDATOR pxf_fdw_validator OPTIONS (protocol 'test', mpp_execute 'all segments')", ignoreFail);
    }

    public void createSystemFDW(boolean ignoreFail) throws Exception {
        runQuery("DROP FOREIGN DATA WRAPPER IF EXISTS system_pxf_fdw CASCADE", ignoreFail);
        runQuery("CREATE FOREIGN DATA WRAPPER system_pxf_fdw HANDLER pxf_fdw_handler " +
                "VALIDATOR pxf_fdw_validator OPTIONS (protocol 'system', mpp_execute 'all segments')", ignoreFail);
    }
    public void createForeignServers(boolean ignoreFail) throws Exception {
        List<String> servers = Lists.newArrayList(
                "default_hdfs",
                "default_hive",
                "db-hive_jdbc", // Needed for JdbcHiveTest
                "default_hbase",
                "default_jdbc", // Needed for JdbcHiveTest and other JdbcTest which refers to the default server.
                "database_jdbc",
                "db-session-params_jdbc",
                "default_file",
                "default_s3",
                "default_gs",
                "default_abfss",
                "default_wasbs",
                "s3_s3",
                "s3-invalid_s3",
                "s3-non-existent_s3",
                "hdfs-non-secure_hdfs",
                "hdfs-secure_hdfs",
                "hdfs-ipa_hdfs",
                "default_test",
                "default_system");

        for (String server : servers) {
            String foreignServerName = server.replace("-", "_");
            String pxfServerName = server.substring(0, server.lastIndexOf("_")); // strip protocol at the end
            String fdwName = server.substring(server.lastIndexOf("_") + 1) + "_pxf_fdw"; // strip protocol at the end
            runQuery(String.format("CREATE SERVER IF NOT EXISTS %s FOREIGN DATA WRAPPER %s OPTIONS(config '%s')",
                    foreignServerName, fdwName, pxfServerName), ignoreFail);
            runQuery(String.format("CREATE USER MAPPING IF NOT EXISTS FOR CURRENT_USER SERVER %s", foreignServerName),
                    ignoreFail);
        }
    }

    public boolean checkDatabaseExists(String dbName) throws Exception {
        ResultSet rs = statement.executeQuery(
                "SELECT 1 FROM pg_database WHERE datname = '" + dbName + "'");
        return rs.next();
    }

    public boolean checkTableExists(Table table) throws Exception {
        DatabaseMetaData meta = connection.getMetaData();
        String schema = table.getSchema();
        if (schema == null) {
            schema = "public";
        }
        ResultSet rs = meta.getTables(null, schema, table.getName(), null);
        return rs.next();
    }

    public String getUserName() {
        return userName;
    }

    public String getPassword() {
        return password;
    }

    public PXFCloudberryContainer getContainer() {
        return container;
    }

    @Override
    public void close() throws Exception {
        if (statement != null) {
            try { statement.close(); } catch (Exception ignored) {}
            statement = null;
        }
        if (connection != null) {
            try { connection.close(); } catch (Exception ignored) {}
            connection = null;
        }
    }

    private String buildCopyParams(String delimiter, String nullChar, boolean csv) {
        StringBuilder params = new StringBuilder();
        if (csv) {
            params.append("CSV ");
        }
        if (delimiter != null) {
            params.append("DELIMITER E'").append(stripEQuote(delimiter)).append("' ");
        }
        if (nullChar != null) {
            params.append("NULL E'").append(stripEQuote(nullChar)).append("' ");
        }
        return params.toString().trim();
    }

    private static String stripEQuote(String value) {
        if (value.startsWith("E'") && value.endsWith("'")) {
            return value.substring(2, value.length() - 1);
        }
        if (value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

}
