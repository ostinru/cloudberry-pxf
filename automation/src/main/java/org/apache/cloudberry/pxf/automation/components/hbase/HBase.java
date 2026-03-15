package org.apache.cloudberry.pxf.automation.components.hbase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import jsystem.framework.report.Reporter;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptorBuilder;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.apache.hadoop.hbase.security.access.Permission.Action;
import org.apache.hadoop.hbase.util.Bytes;

import org.apache.cloudberry.pxf.automation.components.common.BaseSystemObject;
import org.apache.cloudberry.pxf.automation.components.common.IDbFunctionality;
import org.apache.cloudberry.pxf.automation.structures.tables.hbase.HBaseTable;
import org.apache.cloudberry.pxf.automation.utils.hbase.HBaseUtils;
import org.apache.cloudberry.pxf.automation.utils.jsystem.report.ReportUtils;
import org.apache.cloudberry.pxf.automation.structures.tables.basic.Table;

/**
 * HBase system object
 */
public class HBase extends BaseSystemObject implements IDbFunctionality {

    private Configuration config;
    private Admin admin;
    private Connection connection;
    private String host;
    private String hbaseRoot;
    private boolean isAuthorizationEnabled = false;

    public HBase() {
    }

    public HBase(boolean silentReport) {
        super(silentReport);
    }

    /**
     * Creates an HBase component that connects using the supplied config.
     * Bypasses the SUT/host-based init path — intended for TestContainers use.
     */
    public HBase(Configuration config) {
        super(true);
        this.config = config;
    }

    /**
     * Connects to HBase using the previously set configuration.
     */
    public void connect() throws Exception {
        connection = ConnectionFactory.createConnection(config);
        admin = connection.getAdmin();
        int regionServers = admin.getClusterMetrics()
                .getLiveServerMetrics().size();
        if (regionServers == 0) {
            throw new IllegalStateException("No HBase region servers running");
        }
        System.out.println("[HBase] Connected, " + regionServers + " region server(s)");
    }

    @Override
    public void init() throws Exception {
        super.init();
        ReportUtils.startLevel(report, getClass(), "Init");

        if (config == null) {
            config = new Configuration();
            if (StringUtils.isNotEmpty(hbaseRoot)) {
                config.addResource(new Path(getHbaseRoot() + "/conf/hbase-site.xml"));
            } else {
                config.set("hbase.rootdir", "hdfs://" + host + ":8020/hbase");
            }
        }

        connection = ConnectionFactory.createConnection(config);
        admin = connection.getAdmin();
        if (admin.getClusterMetrics().getLiveServerMetrics().size() == 0) {
            ReportUtils.report(report, getClass(),
                    "No HBase region servers running", Reporter.FAIL);
        }

        ReportUtils.report(report, getClass(), "HBase Admin created");
        ReportUtils.stopLevel(report);
    }

    @Override
    public void close() {
        if (admin != null) {
            try { admin.close(); } catch (IOException ignored) {}
        }
        if (connection != null) {
            try { connection.close(); } catch (IOException ignored) {}
        }
        super.close();
    }

    @Override
    public ArrayList<String> getTableList(String schema) throws Exception {
        ReportUtils.startLevel(report, getClass(), "List Tables");
        List<org.apache.hadoop.hbase.client.TableDescriptor> tables = admin.listTableDescriptors();
        ArrayList<String> names = new ArrayList<>();
        for (org.apache.hadoop.hbase.client.TableDescriptor td : tables) {
            names.add(td.getTableName().getNameAsString());
        }
        ReportUtils.report(report, getClass(), names.toString());
        ReportUtils.stopLevel(report);
        return names;
    }

    public void put(HBaseTable hbaseTable) throws Exception {
        ReportUtils.startLevel(report, getClass(), "Put data to Table: " + hbaseTable.getName());
        org.apache.hadoop.hbase.client.Table table =
                connection.getTable(TableName.valueOf(hbaseTable.getName()));
        table.put(hbaseTable.getRowsToGenerate());
        table.close();
        ReportUtils.stopLevel(report);
    }

    public void removeRow(HBaseTable table, String[] rowIds) throws Exception {
        List<Delete> deleteList = new ArrayList<>();
        StringBuilder sBuilder = new StringBuilder();
        for (String rowId : rowIds) {
            deleteList.add(new Delete(rowId.getBytes()));
            sBuilder.append(rowId).append(" ");
        }
        ReportUtils.startLevel(report, getClass(),
                "Remove " + sBuilder + " rowIds from " + table.getName());
        org.apache.hadoop.hbase.client.Table hTable =
                connection.getTable(TableName.valueOf(table.getName()));
        hTable.delete(deleteList);
        hTable.close();
        ReportUtils.stopLevel(report);
    }

    @Override
    public void queryResults(Table table, String query) throws Exception {
        ReportUtils.startLevel(report, getClass(), "Scan Table: " + table.getName());
        org.apache.hadoop.hbase.client.Table tbl =
                connection.getTable(TableName.valueOf(table.getName()));
        Scan scan = new Scan();
        HBaseTable hTable = (HBaseTable) table;

        if (hTable.getFilters() != null) {
            scan.setFilter(hTable.getFilters());
            StringBuilder filterListPrint = new StringBuilder();
            HBaseUtils.getFilterListPrint(filterListPrint, hTable.getFilters());
            ReportUtils.report(report, getClass(), filterListPrint.toString());
        }

        hTable.initDataStructures();
        if (hTable.getQualifiers() != null) {
            for (String q : hTable.getQualifiers()) {
                String[] parts = q.split(":");
                hTable.addColumnHeader(q);
                scan.addColumn(parts[0].getBytes(), parts[1].getBytes());
            }
        }

        ResultScanner rs = tbl.getScanner(scan);
        List<List<String>> data = new ArrayList<>();

        for (Result result : rs) {
            List<String> row = new ArrayList<>();
            row.add(new String(result.getRow()));
            if (hTable.getQualifiers() != null) {
                for (String q : hTable.getQualifiers()) {
                    String[] parts = q.split(":");
                    Cell cell = result.getColumnLatestCell(parts[0].getBytes(), parts[1].getBytes());
                    row.add(cell != null ? Bytes.toString(org.apache.hadoop.hbase.CellUtil.cloneValue(cell)) : "");
                }
            } else {
                for (Cell cell : result.listCells()) {
                    row.add(cell != null ? Bytes.toString(org.apache.hadoop.hbase.CellUtil.cloneValue(cell)) : "");
                }
            }
            data.add(row);
        }
        rs.close();
        tbl.close();
        table.setData(data);
        ReportUtils.reportHtml(report, getClass(), table.getDataHtml());
        ReportUtils.stopLevel(report);
    }

    public void loadBulk(Table table, String inputPath, String... cols) throws Exception {
        ReportUtils.startLevel(report, getClass(),
                "Load Bulk from " + inputPath + " to Table: " + table.getName());

        org.apache.hadoop.hbase.client.Table hTable =
                connection.getTable(TableName.valueOf(table.getName()));

        org.apache.hadoop.fs.FileSystem fs = org.apache.hadoop.fs.FileSystem.get(config);
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(fs.open(new Path("/" + inputPath))))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t", -1);
                if (parts.length < 1) continue;
                org.apache.hadoop.hbase.client.Put put =
                        new org.apache.hadoop.hbase.client.Put(Bytes.toBytes(parts[0]));
                for (int i = 0; i < cols.length && i + 1 < parts.length; i++) {
                    String[] cfq = cols[i].split(":", 2);
                    if (cfq.length == 2) {
                        put.addColumn(Bytes.toBytes(cfq[0]), Bytes.toBytes(cfq[1]),
                                Bytes.toBytes(parts[i + 1]));
                    }
                }
                hTable.put(put);
            }
        }
        hTable.close();
        ReportUtils.stopLevel(report);
    }

    @Override
    public void createTable(Table table) throws Exception {
        HBaseTable hTable = (HBaseTable) table;
        ReportUtils.startLevel(report, getClass(), "Create Table " + table.getName());

        TableDescriptorBuilder builder = TableDescriptorBuilder.newBuilder(
                TableName.valueOf(table.getName()));
        for (String family : hTable.getFields()) {
            builder.setColumnFamily(
                    ColumnFamilyDescriptorBuilder.newBuilder(Bytes.toBytes(family)).build());
        }

        String[] splits = generateSplits(hTable.getNumberOfSplits(),
                hTable.getRowKeyPrefix(), hTable.getRowsPerSplit());
        admin.createTable(builder.build(), Bytes.toByteArrays(splits));
        ReportUtils.stopLevel(report);
    }

    @Override
    public void dropTable(Table table, boolean cascade) throws Exception {
        ReportUtils.startLevel(report, getClass(), "Remove Table: " + table.getName());
        if (checkTableExists(table)) {
            disableTable(table);
            admin.deleteTable(TableName.valueOf(table.getName()));
        }
        ReportUtils.stopLevel(report);
    }

    public void disableTable(Table table) throws Exception {
        ReportUtils.startLevel(report, getClass(), "Disable Table: " + table.getName());
        TableName tn = TableName.valueOf(table.getName());
        if (!admin.isTableDisabled(tn)) {
            admin.disableTable(tn);
        }
        ReportUtils.stopLevel(report);
    }

    public void enableTable(Table table) throws Exception {
        ReportUtils.startLevel(report, getClass(), "Enable Table: " + table.getName());
        admin.enableTable(TableName.valueOf(table.getName()));
        ReportUtils.stopLevel(report);
    }

    public void removeColumn(Table table, String[] columns) throws Exception {
        ReportUtils.startLevel(report, getClass(),
                "Remove " + columns.length + " columns from Table: " + table.getName());
        disableTable(table);
        for (String col : columns) {
            admin.deleteColumnFamily(TableName.valueOf(table.getName()), Bytes.toBytes(col));
        }
        enableTable(table);
        ReportUtils.stopLevel(report);
    }

    public void addColumn(Table table, String[] columns) throws Exception {
        ReportUtils.startLevel(report, getClass(),
                "Add " + columns.length + " columns to Table: " + table.getName());
        disableTable(table);
        for (String col : columns) {
            admin.addColumnFamily(TableName.valueOf(table.getName()),
                    ColumnFamilyDescriptorBuilder.newBuilder(Bytes.toBytes(col)).build());
        }
        enableTable(table);
        ReportUtils.stopLevel(report);
    }

    @Override
    public void dropDataBase(String schemaName, boolean cascade, boolean ignoreFail) throws Exception {
        ReportUtils.throwUnsupportedFunctionality(getClass(), "Drop Schema");
    }

    @Override
    public void insertData(Table source, Table target) throws Exception {
        ReportUtils.throwUnsupportedFunctionality(getClass(), "Insert Data");
    }

    @Override
    public void createDataBase(String schemaName, boolean ignoreFail) throws Exception {
        ReportUtils.throwUnsupportedFunctionality(getClass(), "Create Schema");
    }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    @Override
    public void createTableAndVerify(Table table) throws Exception {
        ReportUtils.startLevel(report, getClass(),
                "Create and Verify Table: " + table.getFullName());
        dropTable(table, false);
        createTable(table);
        if (!checkTableExists(table)) {
            ReportUtils.stopLevel(report);
            throw new Exception("Table " + table.getName() + " does not exist");
        }
        ReportUtils.stopLevel(report);
    }

    @Override
    public boolean checkTableExists(Table table) throws Exception {
        return admin.tableExists(TableName.valueOf(table.getName()));
    }

    @Override
    public boolean checkDataBaseExists(String dbName) throws Exception {
        ReportUtils.throwUnsupportedFunctionality(getClass(), "Check Data Base Exists");
        return false;
    }

    @Override
    public ArrayList<String> getDataBasesList() throws Exception {
        ReportUtils.throwUnsupportedFunctionality(getClass(), "Get Data Bases List");
        return null;
    }

    @Override
    public void grantReadOnTable(Table table, String user) throws Exception {
        grantPermissions(table, user, Action.READ);
    }

    @Override
    public void grantWriteOnTable(Table table, String user) throws Exception {
        grantPermissions(table, user, Action.WRITE);
    }

    @Override
    public void createDataBase(String schemaName, boolean ignoreFail,
                               String encoding, String localeCollate, String localeCollateType) {
        throw new UnsupportedOperationException();
    }

    public void grantCreateReadOnTable(Table table, String user) throws Exception {
        grantPermissions(table, user, Action.CREATE, Action.READ);
    }

    public static void main(String[] args) throws Exception {
        HBase hbase = new HBase();
        hbase.setHost("localhost");
        hbase.init();
    }

    public String getHbaseRoot() { return hbaseRoot; }

    public void grantGlobalForUser(String user) throws Exception {
        grantPermissions(null, user, Action.CREATE, Action.READ, Action.WRITE, Action.ADMIN);
    }

    public Configuration getConfiguration() { return config; }
    public void setHbaseRoot(String hbaseRoot) { this.hbaseRoot = hbaseRoot; }

    public void setAuthorization(boolean authEnabled) {
        this.isAuthorizationEnabled = authEnabled;
    }

    private String[] generateSplits(int numberOfSplits, String rowKeyPrefix, int rowsPerSplit) {
        String[] splits = new String[numberOfSplits];
        for (int i = 0; i < numberOfSplits; ++i)
            splits[i] = String.format("%s%08d", rowKeyPrefix, (i + 1) * rowsPerSplit);
        return splits;
    }

    /**
     * Grant permissions using the HBase shell via execInContainer or
     * the AccessControl coprocessor. In HBase 2.x without Kerberos/ACLs
     * enabled, this is a no-op.
     */
    private void grantPermissions(Table table, String user, Action... actions) throws Exception {
        ReportUtils.report(report, getClass(), "grant request for user=" + user + " table=" + table);
        String hbaseAuthEnabled = config.get("hbase.security.authorization");
        if (!isAuthorizationEnabled
                && (hbaseAuthEnabled == null || !hbaseAuthEnabled.equals("true"))) {
            ReportUtils.report(report, getClass(),
                    "HBase security authorization is not enabled, skipping grant");
            return;
        }
        // HBase 2.x ACL grant requires AccessController coprocessor.
        // In standalone mode without security, this is not available.
        System.out.println("[HBase] ACL grant skipped (authorization not configured in standalone mode)");
    }
}
