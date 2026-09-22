/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cloudberry.pxf.automation.applications;

import org.apache.cloudberry.pxf.automation.structures.tables.basic.Table;
import org.apache.cloudberry.pxf.automation.structures.tables.hive.HiveTable;
import org.apache.cloudberry.pxf.automation.testcontainers.SingleClusterContainer;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Hive operations over the mapped HiveServer2 JDBC endpoint. */
public class HiveApplication implements AutoCloseable {
    private static final int MAX_CONNECTION_ATTEMPTS = 10;
    private static final long CONNECTION_RETRY_MILLIS = 1_000L;

    private final SingleClusterContainer container;
    private Connection connection;
    private Statement statement;

    public HiveApplication(SingleClusterContainer container) throws Exception {
        this.container = container;
        connect();
    }

    private void connect() throws Exception {
        Class.forName("org.apache.hive.jdbc.HiveDriver");
        Properties properties = new Properties();
        properties.setProperty("user", SingleClusterContainer.USER);
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= MAX_CONNECTION_ATTEMPTS; attempt++) {
            try {
                connection = DriverManager.getConnection(container.getHostHiveJdbcUrl(), properties);
                statement = connection.createStatement();
                return;
            } catch (Exception e) {
                lastFailure = e;
                Thread.sleep(CONNECTION_RETRY_MILLIS);
            }
        }
        throw new IllegalStateException("Could not connect to " + container.getHostHiveJdbcUrl(), lastFailure);
    }

    public void runQuery(String query) throws Exception {
        statement.execute(query);
    }

    public void loadData(HiveTable table, String filePath, boolean local) throws Exception {
        String path = local ? filePath : (filePath.startsWith("/") ? filePath : "/" + filePath);
        runQuery("LOAD DATA " + (local ? "LOCAL " : "") + "INPATH '" + path
                + "' INTO TABLE " + table.getFullName());
    }

    public void loadData(HiveTable table, String filePath) throws Exception {
        loadData(table, filePath, true);
    }

    public void loadDataToPartition(HiveTable table, String filePath, boolean local,
                                    String[] partitions) throws Exception {
        if (ArrayUtils.isEmpty(partitions)) {
            throw new IllegalArgumentException("No partitions to load data to");
        }
        String path = local ? filePath : (filePath.startsWith("/") ? filePath : "/" + filePath);
        runQuery("LOAD DATA " + (local ? "LOCAL " : "") + "INPATH '" + path
                + "' INTO TABLE " + table.getFullName() + " PARTITION("
                + StringUtils.join(partitions, ", ") + ")");
    }

    public void insertData(Table source, Table target) throws Exception {
        runQuery("INSERT INTO TABLE " + target.getFullName() + " SELECT * FROM "
                + source.getFullName());
    }

    public void insertDataToPartition(Table source, Table target, String[] partitions,
                                      String[] columns) throws Exception {
        insertDataToPartition(source, target, partitions, columns, null);
    }

    public void insertDataToPartition(Table source, Table target, String[] partitions,
                                      String[] columns, String filter) throws Exception {
        if (ArrayUtils.isEmpty(partitions)) {
            throw new IllegalArgumentException("No partitions to insert data to");
        }
        runQuery("INSERT INTO TABLE " + target.getFullName() + " PARTITION ("
                + StringUtils.join(partitions, ", ") + ") SELECT "
                + StringUtils.join(columns, ", ") + " FROM " + source.getFullName()
                + (filter == null ? "" : " WHERE " + filter));
    }

    public void alterTableAddPartition(HiveTable table, String[] partitions) throws Exception {
        runQuery("ALTER TABLE " + table.getFullName() + " ADD PARTITION ("
                + StringUtils.join(partitions, ",") + ")");
    }

    public void dropTable(Table table, boolean cascade) throws Exception {
        runQuery(table.constructDropStmt(false));
    }

    public void createDataBase(String schemaName, boolean ignoreFailure) throws Exception {
        try {
            runQuery("CREATE DATABASE " + (ignoreFailure ? "IF NOT EXISTS " : "") + schemaName);
        } catch (Exception e) {
            if (!ignoreFailure) {
                throw e;
            }
        }
    }

    public boolean checkTableExists(Table table) throws Exception {
        String query = "SHOW TABLES";
        if (table.getSchema() != null) {
            query += " IN " + table.getSchema();
        }
        query += " LIKE '" + table.getName() + "'";
        try (ResultSet result = statement.executeQuery(query)) {
            return result.next();
        }
    }

    public void queryResults(Table table, String query) throws Exception {
        try (ResultSet result = statement.executeQuery(query)) {
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

    public void createTableAndVerify(Table table) throws Exception {
        runQuery(table.constructDropStmt(false));
        runQuery(table.constructCreateStmt());
        try (ResultSet result = statement.executeQuery("SHOW TABLES LIKE '" + table.getName() + "'")) {
            if (!result.next()) {
                throw new IllegalStateException("Hive table was not created: " + table.getFullName());
            }
        }
    }

    /** Docker-network hostname intended for URLs consumed by PXF. */
    public String getHost() {
        return SingleClusterContainer.NETWORK_ALIAS;
    }

    public String getInternalJdbcUrl() {
        return container.getInternalHiveJdbcUrl();
    }

    @Override
    public void close() throws Exception {
        if (statement != null) {
            statement.close();
            statement = null;
        }
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }
}
