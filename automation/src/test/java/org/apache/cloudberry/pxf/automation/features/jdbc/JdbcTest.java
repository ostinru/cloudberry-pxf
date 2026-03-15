package org.apache.cloudberry.pxf.automation.features.jdbc;

import java.io.File;

import annotations.FailsWithFDW;
import annotations.WorksWithFDW;
import org.apache.cloudberry.pxf.automation.BasePXFTest;
import org.apache.cloudberry.pxf.automation.structures.tables.basic.Table;
import org.apache.cloudberry.pxf.automation.structures.tables.pxf.ExternalTable;
import org.apache.cloudberry.pxf.automation.structures.tables.utils.TableFactory;
import org.apache.cloudberry.pxf.automation.testcontainers.CbdbApplication;
import org.apache.cloudberry.pxf.automation.testcontainers.RegressApplication;
import org.apache.cloudberry.pxf.automation.testcontainers.PXFCBDBContainer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import org.apache.cloudberry.pxf.automation.enums.EnumPartitionType;

@WorksWithFDW
public class JdbcTest extends BasePXFTest {

    private static final String POSTGRES_DRIVER_CLASS = "org.postgresql.Driver";
    private static final String CBDB_PXF_AUTOMATION_DB_JDBC = "jdbc:postgresql://";

    private static final String INTERNAL_CBDB_HOST = "localhost";
    private static final int INTERNAL_CBDB_PORT = PXFCBDBContainer.CBDB_PORT;
    private static final String INTERNAL_JDBC_URL =
            CBDB_PXF_AUTOMATION_DB_JDBC + INTERNAL_CBDB_HOST + ":" + INTERNAL_CBDB_PORT + "/pxfautomation";
    private static final String INTERNAL_USER = PXFCBDBContainer.CBDB_USER;

    private static final String[] TYPES_TABLE_FIELDS = new String[]{
            "t1    text",
            "t2    text",
            "num1  int",
            "dub1  double precision",
            "dec1  numeric",
            "tm    timestamp",
            "r     real",
            "bg    bigint",
            "b     boolean",
            "tn    smallint",
            "sml   smallint",
            "dt    date",
            "vc1   varchar(5)",
            "c1    char(3)",
            "bin   bytea",
            "u     uuid",
            "tmz   timestamp with time zone"
    };
    private static final String[] PGSETTINGS_VIEW_FIELDS = new String[]{
            "name    text",
            "setting text"};
    private static final String[] TYPES_TABLE_FIELDS_SMALL = new String[]{
            "t1    text",
            "t2    text",
            "num1  int"};
    private static final String[] COLUMNS_TABLE_FIELDS = new String[]{
            "t text",
            "\"num 1\" int",
            "\"n@m2\" int"};
    private static final String[] COLUMNS_TABLE_FIELDS_IN_DIFFERENT_ORDER_SUBSET = new String[]{
            "\"n@m2\" int",
            "\"num 1\" int"};
    private static final String[] COLUMNS_TABLE_FIELDS_SUPERSET = new String[]{
            "t text",
            "\"does_not_exist_on_source\" text",
            "\"num 1\" int",
            "\"n@m2\" int"};
    private static final String[] NAMED_QUERY_FIELDS = new String[]{
            "name  text",
            "count int",
            "max  int"};

    private static final String localDataResourcesFolder = "src/test/resources/data";

    private PXFCBDBContainer container;
    private CbdbApplication cbdb;
    private RegressApplication regress;
    private String pxfHost;
    private String pxfPort;

    private ExternalTable pxfJdbcSingleFragment;
    private ExternalTable pxfJdbcDateWideRangeOn;
    private ExternalTable pxfJdbcDateWideRangeOff;
    private ExternalTable pxfJdbcMultipleFragmentsByInt;
    private ExternalTable pxfJdbcMultipleFragmentsByDate;
    private ExternalTable pxfJdbcMultipleFragmentsByEnum;
    private ExternalTable pxfJdbcReadServerConfigAll; // all server-based props coming from there, not DDL
    private ExternalTable pxfJdbcReadViewNoParams, pxfJdbcReadViewSessionParams;
    private ExternalTable pxfJdbcWritable;
    private ExternalTable pxfJdbcDateTimeWritableWithDateWideRangeOn;
    private ExternalTable pxfJdbcDateTimeWritableWithDateWideRangeOff;
    private ExternalTable pxfJdbcWritableNoBatch;
    private ExternalTable pxfJdbcWritablePool;
    private ExternalTable pxfJdbcColumns;
    private ExternalTable pxfJdbcColumnProjectionSubset;
    private ExternalTable pxfJdbcColumnProjectionSuperset;
    private ExternalTable pxfJdbcNamedQuery;

    private Table gpdbNativeTableTypes, gpdbNativeTableTypesWithDateWideRange, gpdbNativeTableColumns, gpdbWritableTargetTable, dateTimeWritableTargetTableWithDateWideRangeOn, dateTimeWritableTargetTableWithDateWideRangeOff;
    private Table gpdbWritableTargetTableNoBatch, gpdbWritableTargetTablePool;
    private Table gpdbDeptTable, gpdbEmpTable;

    @BeforeClass(alwaysRun = true)
    public void setup() throws Exception {
        container = PXFCBDBContainer.getInstance();

        pxfHost = container.getPxfInternalHost();
        pxfPort = String.valueOf(container.getPxfInternalPort());

        cbdb = new CbdbApplication(container);
        cbdb.connect();
        cbdb.createExtension("pxf");

        regress = new RegressApplication(container);

        prepareData();
    }

    @AfterClass(alwaysRun = true)
    public void teardown() throws Exception {
        if (cbdb != null) {
            cbdb.close();
        }
    }

    protected void prepareData() throws Exception {
        prepareTypesData();
        prepareSingleFragment();
        prepareMultipleFragmentsByInt();
        prepareMultipleFragmentsByDate();
        prepareMultipleFragmentsByEnum();
        prepareServerBasedMultipleFragmentsByInt();
        prepareViewBasedForTestingSessionParams();
        prepareWritable();
        prepareColumns();
        prepareColumnProjectionSubsetInDifferentOrder();
        prepareColumnProjectionSuperset();
        prepareFetchSizeZero();
        prepareDateWideRange();
        prepareNamedQuery();
    }

    private void prepareTypesData() throws Exception {
        // create a table prepared for partitioning
        gpdbNativeTableTypes = new Table("gpdb_types", TYPES_TABLE_FIELDS);
        gpdbNativeTableTypes.setDistributionFields(new String[]{"t1"});
        cbdb.createTableAndVerify(gpdbNativeTableTypes);
        cbdb.copyFromFile(gpdbNativeTableTypes, new File(localDataResourcesFolder
                + "/gpdb/" + gpdbTypesDataFileName), "E'\\t'", "E'\\\\N'", true);

        // create a table that is the same as above but with timestamp with time zone
        gpdbNativeTableTypesWithDateWideRange = new Table("gpdb_types_with_date_wide_range", TYPES_TABLE_FIELDS);
        gpdbNativeTableTypesWithDateWideRange.setDistributionFields(new String[]{"t1"});
        cbdb.createTableAndVerify(gpdbNativeTableTypesWithDateWideRange);
        cbdb.copyFromFile(gpdbNativeTableTypesWithDateWideRange, new File(localDataResourcesFolder
                + "/gpdb/" + gpdbTypesWithDateWideRangeDataFileName), "E'\\t'", "E'\\\\N'", true);

        // create a table to be filled by the writable test case
        gpdbWritableTargetTable = new Table("gpdb_types_target", TYPES_TABLE_FIELDS);
        gpdbWritableTargetTable.setDistributionFields(new String[]{"t1"});
        cbdb.createTableAndVerify(gpdbWritableTargetTable);

        // create a table for testing datetime values when DateWideRange is turned on
        dateTimeWritableTargetTableWithDateWideRangeOn = new Table("datetime_writable_with_date_wide_range_on", TYPES_TABLE_FIELDS);
        dateTimeWritableTargetTableWithDateWideRangeOn.setDistributionFields(new String[]{"t1"});
        cbdb.createTableAndVerify(dateTimeWritableTargetTableWithDateWideRangeOn);

        // create a table for testing datetime values when DateWideRange is turned off
        dateTimeWritableTargetTableWithDateWideRangeOff = new Table("datetime_writable_with_date_wide_range_off", TYPES_TABLE_FIELDS);
        dateTimeWritableTargetTableWithDateWideRangeOff.setDistributionFields(new String[]{"t1"});
        cbdb.createTableAndVerify(dateTimeWritableTargetTableWithDateWideRangeOff);

        // create a table to be filled by the writable test case with no batch
        gpdbWritableTargetTableNoBatch = new Table("gpdb_types_nobatch_target", TYPES_TABLE_FIELDS_SMALL);
        gpdbWritableTargetTableNoBatch.setDistributionFields(new String[]{"t1"});
        cbdb.createTableAndVerify(gpdbWritableTargetTableNoBatch);

        // create a table to be filled by the writable test case with pool size > 1
        gpdbWritableTargetTablePool = new Table("gpdb_types_pool_target", TYPES_TABLE_FIELDS_SMALL);
        gpdbWritableTargetTablePool.setDistributionFields(new String[]{"t1"});
        cbdb.createTableAndVerify(gpdbWritableTargetTablePool);

        // create a table with special column names
        gpdbNativeTableColumns = new Table("gpdb_columns", COLUMNS_TABLE_FIELDS);
        gpdbNativeTableColumns.setDistributionFields(new String[]{"t"});
        cbdb.createTableAndVerify(gpdbNativeTableColumns);
        cbdb.copyFromFile(gpdbNativeTableColumns, new File(localDataResourcesFolder
                + "/gpdb/" + gpdbColumnsDataFileName), "E'\\t'", "E'\\\\N'", true);

        // create emp and dept tables for named query test
        String[] deptTableFields = new String[]{"name text", "id int"};
        gpdbDeptTable = new Table("gpdb_dept", deptTableFields);
        gpdbDeptTable.setDistributionFields(new String[]{"name"});
        cbdb.createTableAndVerify(gpdbDeptTable);
        String[][] deptRows = new String[][] {
                { "sales", "1"},
                { "finance", "2"},
                { "it", "3"}};
        Table dataTable = new Table("data", deptTableFields);
        dataTable.addRows(deptRows);
        cbdb.insertData(dataTable, gpdbDeptTable);

        String[] empTableFields = new String[]{"name text", "dept_id int", "salary int"};
        gpdbEmpTable = new Table("gpdb_emp", empTableFields);
        gpdbEmpTable.setDistributionFields(new String[]{"name"});
        cbdb.createTableAndVerify(gpdbEmpTable);
        final String[][] empRows = new String[][] {
                { "alice", "1", "115" },
                { "bob", "1", "120" },
                { "charli", "1", "93" },
                { "daniel", "2", "87" },
                { "emma", "2", "100" },
                { "frank", "2", "103" },
                { "george", "2", "90" },
                { "henry", "3", "96" },
                { "ivanka", "3", "70" }};
        dataTable = new Table("data", empTableFields);
        dataTable.addRows(empRows);
        cbdb.insertData(dataTable, gpdbEmpTable);
    }

    private static final String gpdbTypesWithDateWideRangeDataFileName = "gpdb_types_with_date_wide_range.txt";
    private static final String gpdbTypesDataFileName = "gpdb_types.txt";
    private static final String gpdbColumnsDataFileName = "gpdb_columns.txt";

    private void prepareSingleFragment() throws Exception {
        pxfJdbcSingleFragment = TableFactory.getPxfJdbcReadableTable(
                "pxf_jdbc_single_fragment",
                TYPES_TABLE_FIELDS,
                gpdbNativeTableTypes.getName(),
                POSTGRES_DRIVER_CLASS,
                INTERNAL_JDBC_URL,
                INTERNAL_USER);
        pxfJdbcSingleFragment.setHost(pxfHost);
        pxfJdbcSingleFragment.setPort(pxfPort);
        cbdb.createTableAndVerify(pxfJdbcSingleFragment);
    }

    private void prepareMultipleFragmentsByEnum() throws Exception {
        pxfJdbcMultipleFragmentsByEnum = TableFactory
                .getPxfJdbcReadablePartitionedTable(
                        "pxf_jdbc_multiple_fragments_by_enum",
                        TYPES_TABLE_FIELDS,
                        gpdbNativeTableTypes.getName(),
                        POSTGRES_DRIVER_CLASS,
                        INTERNAL_JDBC_URL,
                        13,
                        "USD:UAH",
                        "1",
                        INTERNAL_USER,
                        EnumPartitionType.ENUM,
                        null);
        pxfJdbcMultipleFragmentsByEnum.setHost(pxfHost);
        pxfJdbcMultipleFragmentsByEnum.setPort(pxfPort);
        cbdb.createTableAndVerify(pxfJdbcMultipleFragmentsByEnum);
    }

    private void prepareMultipleFragmentsByInt() throws Exception {
        pxfJdbcMultipleFragmentsByInt = TableFactory
                .getPxfJdbcReadablePartitionedTable(
                        "pxf_jdbc_multiple_fragments_by_int",
                        TYPES_TABLE_FIELDS,
                        gpdbNativeTableTypes.getName(),
                        POSTGRES_DRIVER_CLASS,
                        INTERNAL_JDBC_URL,
                        2,
                        "1:6",
                        "1",
                        INTERNAL_USER,
                        EnumPartitionType.INT,
                        null);
        pxfJdbcMultipleFragmentsByInt.setHost(pxfHost);
        pxfJdbcMultipleFragmentsByInt.setPort(pxfPort);
        cbdb.createTableAndVerify(pxfJdbcMultipleFragmentsByInt);
    }

    private void prepareMultipleFragmentsByDate() throws Exception {
        pxfJdbcMultipleFragmentsByDate = TableFactory
                .getPxfJdbcReadablePartitionedTable(
                        "pxf_jdbc_multiple_fragments_by_date",
                        TYPES_TABLE_FIELDS,
                        gpdbNativeTableTypes.getName(),
                        POSTGRES_DRIVER_CLASS,
                        INTERNAL_JDBC_URL,
                        11,
                        "2015-03-06:2015-03-20",
                        "1:DAY",
                        INTERNAL_USER,
                        EnumPartitionType.DATE,
                        null);
        pxfJdbcMultipleFragmentsByDate.setHost(pxfHost);
        pxfJdbcMultipleFragmentsByDate.setPort(pxfPort);
        cbdb.createTableAndVerify(pxfJdbcMultipleFragmentsByDate);
    }

    private void prepareServerBasedMultipleFragmentsByInt() throws Exception {
        pxfJdbcReadServerConfigAll = TableFactory
                .getPxfJdbcReadablePartitionedTable(
                        "pxf_jdbc_read_server_config_all",
                        TYPES_TABLE_FIELDS,
                        gpdbNativeTableTypes.getName(),
                        null,
                        null,
                        2,
                        "1:6",
                        "1",
                        null,
                        EnumPartitionType.INT,
                        "database");
        pxfJdbcReadServerConfigAll.setHost(pxfHost);
        pxfJdbcReadServerConfigAll.setPort(pxfPort);
        cbdb.createTableAndVerify(pxfJdbcReadServerConfigAll);
    }

    private void prepareViewBasedForTestingSessionParams() throws Exception {
        pxfJdbcReadViewNoParams = TableFactory.getPxfJdbcReadableTable(
                "pxf_jdbc_read_view_no_params",
                PGSETTINGS_VIEW_FIELDS,
                "pg_settings",
                "database");
        pxfJdbcReadViewNoParams.setHost(pxfHost);
        pxfJdbcReadViewNoParams.setPort(pxfPort);
        cbdb.createTableAndVerify(pxfJdbcReadViewNoParams);

        pxfJdbcReadViewSessionParams = TableFactory.getPxfJdbcReadableTable(
                "pxf_jdbc_read_view_session_params",
                PGSETTINGS_VIEW_FIELDS,
                "pg_settings",
                "db-session-params");
        pxfJdbcReadViewSessionParams.setHost(pxfHost);
        pxfJdbcReadViewSessionParams.setPort(pxfPort);
        cbdb.createTableAndVerify(pxfJdbcReadViewSessionParams);
    }

    private void prepareWritable() throws Exception {
        pxfJdbcWritable = TableFactory.getPxfJdbcWritableTable(
                "pxf_jdbc_writable",
                TYPES_TABLE_FIELDS,
                gpdbWritableTargetTable.getName(),
                POSTGRES_DRIVER_CLASS,
                INTERNAL_JDBC_URL,
                INTERNAL_USER, null);
        pxfJdbcWritable.setHost(pxfHost);
        pxfJdbcWritable.setPort(pxfPort);
        pxfJdbcWritable.addUserParameter("date_wide_range=false");
        cbdb.createTableAndVerify(pxfJdbcWritable);

        pxfJdbcDateTimeWritableWithDateWideRangeOn = TableFactory.getPxfJdbcWritableTable(
                "pxf_jdbc_datetime_writable_date_wide_range_on",
                TYPES_TABLE_FIELDS,
                dateTimeWritableTargetTableWithDateWideRangeOn.getName(),
                POSTGRES_DRIVER_CLASS,
                INTERNAL_JDBC_URL,
                INTERNAL_USER, null);
        pxfJdbcDateTimeWritableWithDateWideRangeOn.setHost(pxfHost);
        pxfJdbcDateTimeWritableWithDateWideRangeOn.setPort(pxfPort);
        pxfJdbcDateTimeWritableWithDateWideRangeOn.addUserParameter("date_wide_range=true");
        cbdb.createTableAndVerify(pxfJdbcDateTimeWritableWithDateWideRangeOn);

        pxfJdbcDateTimeWritableWithDateWideRangeOff = TableFactory.getPxfJdbcWritableTable(
                "pxf_jdbc_datetime_writable_date_wide_range_off",
                TYPES_TABLE_FIELDS,
                dateTimeWritableTargetTableWithDateWideRangeOff.getName(),
                POSTGRES_DRIVER_CLASS,
                INTERNAL_JDBC_URL,
                INTERNAL_USER, null);
        pxfJdbcDateTimeWritableWithDateWideRangeOff.setHost(pxfHost);
        pxfJdbcDateTimeWritableWithDateWideRangeOff.setPort(pxfPort);
        pxfJdbcDateTimeWritableWithDateWideRangeOff.addUserParameter("date_wide_range=false");
        cbdb.createTableAndVerify(pxfJdbcDateTimeWritableWithDateWideRangeOff);

        pxfJdbcWritableNoBatch = TableFactory.getPxfJdbcWritableTable(
                "pxf_jdbc_writable_nobatch",
                TYPES_TABLE_FIELDS_SMALL,
                gpdbWritableTargetTableNoBatch.getName(),
                POSTGRES_DRIVER_CLASS,
                INTERNAL_JDBC_URL,
                INTERNAL_USER, "BATCH_SIZE=1");
        pxfJdbcWritableNoBatch.setHost(pxfHost);
        pxfJdbcWritableNoBatch.setPort(pxfPort);
        cbdb.createTableAndVerify(pxfJdbcWritableNoBatch);

        pxfJdbcWritablePool = TableFactory.getPxfJdbcWritableTable(
                "pxf_jdbc_writable_pool",
                TYPES_TABLE_FIELDS_SMALL,
                gpdbWritableTargetTablePool.getName(),
                POSTGRES_DRIVER_CLASS,
                INTERNAL_JDBC_URL,
                INTERNAL_USER, "POOL_SIZE=2");
        pxfJdbcWritablePool.setHost(pxfHost);
        pxfJdbcWritablePool.setPort(pxfPort);
        cbdb.createTableAndVerify(pxfJdbcWritablePool);
    }

    private void prepareColumns() throws Exception {
        pxfJdbcColumns = TableFactory.getPxfJdbcReadableTable(
                "pxf_jdbc_columns",
                COLUMNS_TABLE_FIELDS,
                gpdbNativeTableColumns.getName(),
                POSTGRES_DRIVER_CLASS,
                INTERNAL_JDBC_URL,
                INTERNAL_USER);
        pxfJdbcColumns.setHost(pxfHost);
        pxfJdbcColumns.setPort(pxfPort);
        cbdb.createTableAndVerify(pxfJdbcColumns);
    }

    private void prepareColumnProjectionSubsetInDifferentOrder() throws Exception {
        pxfJdbcColumnProjectionSubset = TableFactory.getPxfJdbcReadableTable(
                "pxf_jdbc_subset_of_fields_diff_order",
                COLUMNS_TABLE_FIELDS_IN_DIFFERENT_ORDER_SUBSET,
                gpdbNativeTableColumns.getName(),
                POSTGRES_DRIVER_CLASS,
                INTERNAL_JDBC_URL,
                INTERNAL_USER);
        pxfJdbcColumnProjectionSubset.setHost(pxfHost);
        pxfJdbcColumnProjectionSubset.setPort(pxfPort);
        cbdb.createTableAndVerify(pxfJdbcColumnProjectionSubset);
    }

    private void prepareColumnProjectionSuperset() throws Exception {
        pxfJdbcColumnProjectionSuperset = TableFactory.getPxfJdbcReadableTable(
                "pxf_jdbc_superset_of_fields",
                COLUMNS_TABLE_FIELDS_SUPERSET,
                gpdbNativeTableColumns.getName(),
                POSTGRES_DRIVER_CLASS,
                INTERNAL_JDBC_URL,
                INTERNAL_USER);
        pxfJdbcColumnProjectionSuperset.setHost(pxfHost);
        pxfJdbcColumnProjectionSuperset.setPort(pxfPort);
        cbdb.createTableAndVerify(pxfJdbcColumnProjectionSuperset);
    }

    private void prepareFetchSizeZero() throws Exception {
        pxfJdbcSingleFragment = TableFactory.getPxfJdbcReadableTable(
                "pxf_jdbc_readable_nobatch",
                TYPES_TABLE_FIELDS,
                gpdbNativeTableTypes.getName(),
                POSTGRES_DRIVER_CLASS,
                INTERNAL_JDBC_URL,
                INTERNAL_USER, "FETCH_SIZE=0");
        pxfJdbcSingleFragment.setHost(pxfHost);
        pxfJdbcSingleFragment.setPort(pxfPort);
        cbdb.createTableAndVerify(pxfJdbcSingleFragment);
    }

    private void prepareDateWideRange() throws Exception {
        pxfJdbcDateWideRangeOn = TableFactory.getPxfJdbcReadableTable(
                "pxf_jdbc_readable_date_wide_range_on",
                TYPES_TABLE_FIELDS,
                gpdbNativeTableTypesWithDateWideRange.getName(),
                POSTGRES_DRIVER_CLASS,
                INTERNAL_JDBC_URL,
                INTERNAL_USER);
        pxfJdbcDateWideRangeOn.setHost(pxfHost);
        pxfJdbcDateWideRangeOn.setPort(pxfPort);
        pxfJdbcDateWideRangeOn.addUserParameter("date_wide_range=true");
        cbdb.createTableAndVerify(pxfJdbcDateWideRangeOn);

        pxfJdbcDateWideRangeOff = TableFactory.getPxfJdbcReadableTable(
                "pxf_jdbc_readable_date_wide_range_off",
                TYPES_TABLE_FIELDS,
                gpdbNativeTableTypesWithDateWideRange.getName(),
                POSTGRES_DRIVER_CLASS,
                INTERNAL_JDBC_URL,
                INTERNAL_USER);
        pxfJdbcDateWideRangeOff.setHost(pxfHost);
        pxfJdbcDateWideRangeOff.setPort(pxfPort);
        pxfJdbcDateWideRangeOff.addUserParameter("date_wide_range=false");
        cbdb.createTableAndVerify(pxfJdbcDateWideRangeOff);
    }

    private void prepareNamedQuery() throws Exception {
        pxfJdbcNamedQuery = TableFactory.getPxfJdbcReadableTable(
                "pxf_jdbc_read_named_query",
                NAMED_QUERY_FIELDS,
                "query:report",
                "database");
        pxfJdbcNamedQuery.setHost(pxfHost);
        pxfJdbcNamedQuery.setPort(pxfPort);
        cbdb.createTableAndVerify(pxfJdbcNamedQuery);

        pxfJdbcNamedQuery = TableFactory.getPxfJdbcReadablePartitionedTable(
                "pxf_jdbc_read_named_query_partitioned",
                NAMED_QUERY_FIELDS,
                "query:report",
                null,
                null,
                1,
                "1:5",
                "1",
                null,
                EnumPartitionType.INT,
                "database");
        pxfJdbcNamedQuery.setHost(pxfHost);
        pxfJdbcNamedQuery.setPort(pxfPort);
        cbdb.createTableAndVerify(pxfJdbcNamedQuery);
    }

    @Test(groups = {"jdbc"})
    public void singleFragmentTable() throws Exception {
        regress.runSqlTest("features/jdbc/single_fragment");
    }

    @Test(groups = {"jdbc"})
    public void multipleFragmentsTables() throws Exception {
        regress.runSqlTest("features/jdbc/multiple_fragments");
    }

    @Test(groups = {"jdbc"})
    public void readServerConfig() throws Exception {
        regress.runSqlTest("features/jdbc/server_config");
    }

    @Test(groups = {"jdbc"})
    public void readViewSessionParams() throws Exception {
        regress.runSqlTest("features/jdbc/session_params");
    }

    @FailsWithFDW
    @Test(groups = {"jdbc"})
    public void jdbcWritableTable() throws Exception {
        regress.runSqlTest("features/jdbc/writable");
    }

    @FailsWithFDW
    @Test(groups = {"jdbc"})
    public void jdbcWritableTableWithDateWideRange() throws Exception {
        regress.runSqlTest("features/jdbc/writable_date_wide_range");
    }

    @FailsWithFDW
    @Test(groups = {"jdbc"})
    public void jdbcWritableTableNoBatch() throws Exception {
        regress.runSqlTest("features/jdbc/writable_nobatch");
    }

    @FailsWithFDW
    @Test(groups = {"jdbc"})
    public void jdbcWritableTablePool() throws Exception {
        regress.runSqlTest("features/jdbc/writable_pool");
    }

    @Test(groups = {"jdbc"})
    public void jdbcColumns() throws Exception {
        regress.runSqlTest("features/jdbc/columns");
    }

    @Test(groups = {"jdbc"})
    public void jdbcColumnProjection() throws Exception {
        regress.runSqlTest("features/jdbc/column_projection");
    }

    @Test(groups = {"jdbc"})
    public void jdbcReadableTableNoBatch() throws Exception {
        regress.runSqlTest("features/jdbc/readable_nobatch");
    }

    @Test(groups = {"jdbc"})
    public void jdbcReadableTableWithDateWideRange() throws Exception {
        regress.runSqlTest("features/jdbc/readable_date_wide_range");
    }

    @Test(groups = {"jdbc"})
    public void jdbcNamedQuery() throws Exception {
        regress.runSqlTest("features/jdbc/named_query");
    }
}
