package org.apache.cloudberry.pxf.automation.features.jdbc;

import annotations.WorksWithFDW;
import org.apache.cloudberry.pxf.automation.applications.HdfsApplication;
import org.apache.cloudberry.pxf.automation.applications.HiveApplication;
import org.apache.cloudberry.pxf.automation.features.AbstractHdfsTestcontainersTest;
import org.apache.cloudberry.pxf.automation.structures.tables.basic.Table;
import org.apache.cloudberry.pxf.automation.structures.tables.hive.HiveTable;
import org.apache.cloudberry.pxf.automation.structures.tables.pxf.ExternalTable;
import org.apache.cloudberry.pxf.automation.structures.tables.utils.TableFactory;
import org.testng.annotations.Test;

import java.io.File;

@WorksWithFDW
public class JdbcHiveTestcontainersTest extends AbstractHdfsTestcontainersTest {

    private static final String HIVE_JDBC_DRIVER_CLASS = "org.apache.hive.jdbc.HiveDriver";
    private static final String HIVE_JDBC_URL_PREFIX = "jdbc:hive2://";

    private static final String GPDB_TYPES_TABLE_NAME = "pxf_jdbc_hive_types_table";
    private static final String GPDB_QUERY_TABLE_NAME = "pxf_jdbc_hive_types_server_table";

    private static final String HIVE_TYPES_TABLE_NAME = "jdbc_hive_types_table";
    private static final String HIVE_TYPES_FILE_NAME_1 = "hive_types_no_binary.txt";

    private static final String HIVE_WRITE_TYPES_TABLE_NAME = "hive_pxf_jdbc_target";
    private static final String GPDB_TABLE_HIVE_WRITE_SUPPORTED_TYPES_NAME = "jdbc_write_hive_supported_types";

    private static final String[] GPDB_TYPES_TABLE_FIELDS = {
            "s1    TEXT",
            "s2    TEXT",
            "n1    INTEGER",
            "d1    DOUBLE PRECISION",
            "dc1   NUMERIC",
            "tm    TIMESTAMP",
            "f     REAL",
            "bg    BIGINT",
            "b     BOOLEAN",
            "tn    SMALLINT",
            "sml   SMALLINT",
            "dt    DATE",
            "vc1   VARCHAR(5)",
            "c1    CHAR(3)"
    };
    private static final String[] GPDB_QUERY_FIELDS = {
            "n1    INTEGER",
            "c     INTEGER",
            "s     INTEGER"
    };
    static final String[] HIVE_TYPES_TABLE_FIELDS = {
            "s1    STRING",
            "s2    STRING",
            "n1    INT",
            "d1    DOUBLE",
            "dc1   DECIMAL(38,18)",
            "tm    TIMESTAMP",
            "f     FLOAT",
            "bg    BIGINT",
            "b     BOOLEAN",
            "tn    TINYINT",
            "sml   SMALLINT",
            "dt    DATE",
            "vc1   VARCHAR(5)",
            "c1    CHAR(3)"
    };

    /*
     * GPDB columns when writing from GPDB to Hive with the JDBC profile
     */
    private static final String[] GPDB_WRITE_TYPES_TABLE_FIELDS = new String[] {
            "t1    text",
            "t2    text",
            "num1  int",
            "dub1  double precision",
            // Hive JDBC driver 1.1.0 does not support BigDecimal
            // https://issues.apache.org/jira/browse/HIVE-13614
            // fixed in 2.3.0
            // "dec1   numeric",
            // Hive JDBC driver does not quote value as required
            // https://issues.apache.org/jira/browse/HIVE-11748
            // fixed in 2.0.0
            // "tm    timestamp",
            "r     real",
            "bg    bigint",
            "b     boolean",
            "tn    smallint",
            "sml   smallint",
            // Hive JDBC driver does not quote value as required
            // https://issues.apache.org/jira/browse/HIVE-11024
            // fixed in 1.3.0, 2.0.0
            // "dt    date",
            "vc1   varchar(5)",
            "c1    char(3)",
            // Hive JDBC driver does not support setBytes()
            // https://github.com/apache/hive/blob/dc8891ec9459d2eff5a23154383ec3bd19481fd2/jdbc/src/java/org/apache/hive/jdbc/HivePreparedStatement.java#L251-L254
            // not yet fixed
            //"bin   bytea"
    };

    private static final String[] HIVE_WRITE_TYPES_TABLE_FIELDS = new String[] {
            "t1    string",
            "t2    string",
            "num1  int",
            "dub1  double",
            "r     float",
            "bg    bigint",
            "b     boolean",
            "tn    tinyint",
            "sml   smallint",
            "vc1   varchar(5)",
            "c1    char(3)"
    };

    private ExternalTable pxfJdbcHiveTypesTable, pxfJdbcHiveTypesServerTable;

    @Override
    protected void beforeClass() throws Exception {
        prepareData(hive, hdfs, HIVE_TYPES_FILE_NAME_1);
        createTables(hive, "db-hive", GPDB_TYPES_TABLE_NAME, GPDB_QUERY_TABLE_NAME);
    }

    protected void prepareData(HiveApplication hive, HdfsApplication hdfs, String hiveTypesFileName) throws Exception {
        // Create Hive table
        HiveTable hiveTypesTable = TableFactory.getHiveByRowCommaTable(HIVE_TYPES_TABLE_NAME, HIVE_TYPES_TABLE_FIELDS);
        hive.dropTable(hiveTypesTable, false);
        hive.createTableAndVerify(hiveTypesTable);
        // copy file with types data to hdfs
        hdfs.copyFromLocal(localDataResourcesFolder + "/hive/" + hiveTypesFileName, hdfs.getWorkingDirectory() + "/" + hiveTypesFileName);
        // load to hive table
        hive.loadData(hiveTypesTable, hdfs.getWorkingDirectory() + "/" + hiveTypesFileName, false);
    }

    protected void createTables(HiveApplication hive, String serverName, String gpdbTypesTableName, String gpdbQueryTableName) throws Exception {
        String jdbcUrl = HIVE_JDBC_URL_PREFIX + hive.getHost() + ":10000/default";
        String user = null;

        // Create GPDB external table pointing to Testcontainers HiveServer2.
        pxfJdbcHiveTypesTable = TableFactory.getPxfJdbcReadableTable(
                gpdbTypesTableName, GPDB_TYPES_TABLE_FIELDS, HIVE_TYPES_TABLE_NAME,
                HIVE_JDBC_DRIVER_CLASS, jdbcUrl, user);
        pxfJdbcHiveTypesTable.setHost(pxfHost);
        pxfJdbcHiveTypesTable.setPort(pxfPort);
        gpdb.createTableAndVerify(pxfJdbcHiveTypesTable);

        pxfJdbcHiveTypesServerTable = TableFactory.getPxfJdbcReadableTable(
                gpdbQueryTableName, GPDB_QUERY_FIELDS, "query:hive-report", serverName);
        pxfJdbcHiveTypesServerTable.setHost(pxfHost);
        pxfJdbcHiveTypesServerTable.setPort(pxfPort);
        gpdb.createTableAndVerify(pxfJdbcHiveTypesServerTable);
    }

    protected void prepareDataForWriteTest() throws Exception {
        // create GPDB table with data for inserting into writable external table
        Table gpdbDataTable = new Table(GPDB_TABLE_HIVE_WRITE_SUPPORTED_TYPES_NAME, GPDB_WRITE_TYPES_TABLE_FIELDS);
        gpdbDataTable.setDistributionFields(new String[]{"t1"});
        gpdb.createTableAndVerify(gpdbDataTable);
        gpdb.copyFromFile(gpdbDataTable, new File(localDataResourcesFolder + "/gpdb/jdbc_write_hive_supported_types.txt"), "E'\\t'", "E'\\\\N'", true);
    }

    protected void createTablesForWriteTest(HiveApplication hive, String hiverServerName, String serverName) throws Exception {
        // create Hive table to write to via JDBC profile
        HiveTable targetHiveTable = TableFactory.getHiveByRowCommaTable(HIVE_WRITE_TYPES_TABLE_NAME, HIVE_WRITE_TYPES_TABLE_FIELDS);
        hive.createTableAndVerify(targetHiveTable);

        String hiveWritableName = String.format("pxf_jdbc_%s_writable", hiverServerName);
        String hiveReadableName = String.format("pxf_jdbc_%s_readable", hiverServerName);

        ExternalTable hiveWritable;
        ExternalTable hiveReadable;

        String jdbcUrl = String.format("%s%s:10000/default", HIVE_JDBC_URL_PREFIX, hive.getHost());
        hiveWritable = TableFactory.getPxfJdbcWritableTable(
                hiveWritableName, GPDB_WRITE_TYPES_TABLE_FIELDS, targetHiveTable.getFullName(),
                HIVE_JDBC_DRIVER_CLASS, jdbcUrl, null, null);
        hiveReadable = TableFactory.getPxfJdbcReadableTable(
                hiveReadableName, GPDB_WRITE_TYPES_TABLE_FIELDS, targetHiveTable.getFullName(),
                HIVE_JDBC_DRIVER_CLASS, jdbcUrl, null);

        hiveWritable.setHost(pxfHost);
        hiveWritable.setPort(pxfPort);
        gpdb.createTableAndVerify(hiveWritable);

        hiveReadable.setHost(pxfHost);
        hiveReadable.setPort(pxfPort);
        gpdb.createTableAndVerify(hiveReadable);
    }

    // TODO: pxf_regress shows diff for this test. Should be fixed.
    @Test(enabled = false, groups = {"testcontainers"})
    public void jdbcHiveRead() throws Exception {
        runSqlTest("features/jdbc/hive");
    }

    @Test(enabled = false, groups = {"testcontainers"})
    public void jdbcHiveWrite() throws Exception {
        prepareDataForWriteTest();
        createTablesForWriteTest(hive, "hive", "db-hive");
        runSqlTest("features/jdbc/hive_writable");
    }

}
