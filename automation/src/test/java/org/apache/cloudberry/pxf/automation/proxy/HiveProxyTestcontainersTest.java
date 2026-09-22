package org.apache.cloudberry.pxf.automation.proxy;

import annotations.SkipForFDW;
import annotations.WorksWithFDW;
import org.apache.cloudberry.pxf.automation.applications.HdfsApplication;
import org.apache.cloudberry.pxf.automation.applications.HiveApplication;
import org.apache.cloudberry.pxf.automation.features.AbstractHdfsTestcontainersTest;
import org.apache.cloudberry.pxf.automation.structures.tables.basic.Table;
import org.apache.cloudberry.pxf.automation.structures.tables.hive.HiveTable;
import org.apache.cloudberry.pxf.automation.structures.tables.pxf.ReadableExternalTable;
import org.apache.cloudberry.pxf.automation.structures.tables.utils.TableFactory;
import org.testng.annotations.Test;

/**
 * Basic PXF on small Hive table using non-gpadmin user
 */
@WorksWithFDW
@SkipForFDW // FDW setup does not create the non-impersonating Hive server required by this proxy test.
public class HiveProxyTestcontainersTest extends AbstractHdfsTestcontainersTest {
    private static final String TEST_USER = "testuser";
    private static final String[] FIELDS = {
            "name text",
            "num integer",
            "dub double precision",
            "longNum bigint",
            "bool boolean"
    };
    public static final String[] HIVE_FIELDS = {
            "name string",
            "num int",
            "dub double",
            "longNum bigint",
            "bool boolean"
    };

    private HiveTable hiveTableAllowed;
    private HiveTable hiveTableProhibited;
    private String hdfsLocationProhibited, hdfsLocationAllowed;

    protected HdfsApplication getHdfsTarget() throws Exception {
        return hdfs;
    }

    protected HiveApplication getHiveTarget() throws Exception {
        return hive;
    }

    protected String getTableInfix() {
        return "";
    }

    protected String getServerName() {
        return "default";
    }

    protected String getAdminUser() {
        return gpdb.getUserName();
    }


    protected void prepareData() throws Exception {
        // get HDFS and Hive to work against, it is "hdfs" and "hive" defined in SUT file by default,
        // but subclasses can choose a different HDFS to use, such as HDFS in IPA-based Hadoop cluster
        HdfsApplication hdfsTarget = getHdfsTarget();
        HiveApplication hiveTarget = getHiveTarget();

        // Create Hive tables
        hiveTableAllowed = TableFactory.getHiveByRowCommaTable("hive_table_allowed", HIVE_FIELDS);
        hiveTableProhibited = TableFactory.getHiveByRowCommaTable("hive_table_prohibited", HIVE_FIELDS);

        hiveTarget.createTableAndVerify(hiveTableAllowed);
        hiveTarget.createTableAndVerify(hiveTableProhibited);

        // Generate Small data, write to HDFS and load to Hive
        Table dataTable = getSmallData();
        String fileName = "hiveSmallData.txt";
        hdfsTarget.writeTableToFile((hdfsTarget.getWorkingDirectory() + "/" + fileName), dataTable, ",");
        hiveTarget.loadData(hiveTableAllowed, (hdfsTarget.getWorkingDirectory() + "/" + fileName), false);
        hdfsTarget.writeTableToFile((hdfsTarget.getWorkingDirectory() + "/" + fileName), dataTable, ",");
        hiveTarget.loadData(hiveTableProhibited, (hdfsTarget.getWorkingDirectory() + "/" + fileName), false);

        // update permissions on HDFS directory for prohibited table to be only readable by gpadmin user
        hdfsLocationProhibited = "/hive/warehouse/hive_table_prohibited/hiveSmallData.txt";
        hdfsTarget.setPermission(hdfsLocationProhibited, "700");
        hdfsTarget.setOwner(hdfsLocationProhibited, getAdminUser(), getAdminUser());

        // update permissions on HDFS directory for allowed table to only be readable by testuser
        hdfsLocationAllowed = "/hive/warehouse/hive_table_allowed/hiveSmallData.txt";
        hdfsTarget.setPermission(hdfsLocationAllowed, "700");
        hdfsTarget.setOwner(hdfsLocationAllowed, TEST_USER, TEST_USER);
    }

    protected void createTables() throws Exception {
        String serverName = getServerName();

        // --- PXF tables pointing to the location prohibited to be read by TEST_USER (allowed for ADMIN_USER only) ---
        // server with impersonation
        createReadablePxfTable(serverName, "_small_data_prohibited", hiveTableProhibited);
        // server without impersonation but with a service user
        createReadablePxfTable(serverName + "-no-impersonation", "_small_data_prohibited_no_impersonation", hiveTableProhibited);

        // --- PXF tables pointing to the location allowed to be read by the TEST_USER only ---
        // server with impersonation
        createReadablePxfTable(serverName, "_small_data_allowed", hiveTableAllowed);
        // server without impersonation but with a service user
        createReadablePxfTable(serverName + "-no-impersonation", "_small_data_allowed_no_impersonation", hiveTableAllowed);
    }

    private void createReadablePxfTable(String serverName, String tableSuffix, HiveTable location) throws Exception {
        ReadableExternalTable exTable =
                TableFactory.getPxfHiveReadableTable("pxf_proxy_hive" + getTableInfix() + tableSuffix,
                        FIELDS, location, true);
        exTable.setHost(pxfHost);
        exTable.setPort(pxfPort);
        if (!serverName.equalsIgnoreCase("default")) {
            exTable.setServer("SERVER=" + serverName);
        }
        gpdb.createTableAndVerify(exTable);
    }

    protected void queryResults() throws Exception {
        runSqlTest("proxy/hive_small_data");
    }

    private void runTest() throws Exception {
        prepareData();
        createTables();
        queryResults();
    }

    @Test(groups = {"testcontainers", "testcontainers-hive"})
    public void test() throws Exception {
        runTest();
    }
}
