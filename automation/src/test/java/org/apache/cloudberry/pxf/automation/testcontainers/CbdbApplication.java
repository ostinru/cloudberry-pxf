package org.apache.cloudberry.pxf.automation.testcontainers;

import org.apache.cloudberry.pxf.automation.structures.tables.basic.Table;
import org.apache.cloudberry.pxf.automation.structures.tables.pxf.ExternalTable;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;

/**
 * Lightweight Cloudberry DB client for TestContainers-based tests.
 * Connects to CBDB via JDBC from the host (mapped port) &ndash; no SSH, no jsystem.
 */
public class CbdbApplication implements AutoCloseable {

    private static final int MAX_RETRIES = 10;
    private static final long RETRY_INTERVAL_MS = 5_000;

    private final PXFCBDBContainer container;
    private final String jdbcUrl;
    private final String userName;
    private Connection connection;
    private Statement statement;

    public CbdbApplication(PXFCBDBContainer container) {
        this.container = container;
        this.jdbcUrl = container.getCbdbJdbcUrl();
        this.userName = container.getCbdbUser();
    }

    public void connect() throws Exception {
        if (statement != null) {
            return;
        }
        Properties props = new Properties();
        if (userName != null) {
            props.setProperty("user", userName);
        }

        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                Class.forName("org.postgresql.Driver");
                connection = DriverManager.getConnection(jdbcUrl, props);
                statement = connection.createStatement();
                System.out.println("[CbdbApplication] Connected to " + jdbcUrl);
                return;
            } catch (Exception e) {
                lastException = e;
                System.out.println("[CbdbApplication] Connection attempt " + attempt + " failed: " + e.getMessage());
                Thread.sleep(RETRY_INTERVAL_MS);
            }
        }
        throw new RuntimeException("Failed to connect to CBDB at " + jdbcUrl + " after " + MAX_RETRIES + " attempts", lastException);
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
            System.out.println("[CbdbApplication] COPY loaded " + rows + " rows into " + table.getName());
        }
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

        String query = "INSERT INTO " + target.getName() + " VALUES " + sb.toString();
        runQuery(query);
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

    // ---- database & extension setup ----------------------------------------

    public void createDatabase(String dbName) throws Exception {
        try {
            runQuery("CREATE DATABASE " + dbName);
        } catch (Exception e) {
            if (!e.getMessage().contains("already exists")) {
                throw e;
            }
        }
    }

    public void createExtension(String extensionName) throws Exception {
        runQuery("CREATE EXTENSION IF NOT EXISTS " + extensionName);
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

    public PXFCBDBContainer getContainer() {
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

    // ---- private helpers ---------------------------------------------------

    private String buildCopyParams(String delimiter, String nullChar, boolean csv) {
        StringBuilder params = new StringBuilder();
        if (csv) {
            params.append("CSV ");
        }
        if (delimiter != null) {
            String delim = delimiter;
            if (delim.startsWith("E'")) {
                delim = delim.substring(2, delim.length() - 1);
            }
            params.append("DELIMITER '").append(delim).append("' ");
        }
        if (nullChar != null) {
            String nc = nullChar;
            if (nc.startsWith("E'")) {
                nc = nc.substring(2, nc.length() - 1);
            }
            params.append("NULL '").append(nc).append("' ");
        }
        return params.toString().trim();
    }
}
