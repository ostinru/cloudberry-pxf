package org.apache.cloudberry.pxf.automation.features.hbase;

import org.apache.cloudberry.pxf.automation.components.hbase.HBase;
import org.apache.cloudberry.pxf.automation.structures.tables.hbase.HBaseTable;
import org.apache.cloudberry.pxf.automation.structures.tables.hbase.LookupTable;
import org.apache.cloudberry.pxf.automation.structures.tables.pxf.ReadableExternalTable;
import org.apache.cloudberry.pxf.automation.structures.tables.utils.TableFactory;
import org.apache.cloudberry.pxf.automation.datapreparer.hbase.HBaseDataPreparer;
import org.apache.cloudberry.pxf.automation.datapreparer.hbase.HBaseLongQualifierDataPreparer;
import org.apache.cloudberry.pxf.automation.testcontainers.CbdbApplication;
import org.apache.cloudberry.pxf.automation.testcontainers.HBaseContainer;
import org.apache.cloudberry.pxf.automation.testcontainers.PXFCBDBContainer;
import org.apache.cloudberry.pxf.automation.testcontainers.RegressApplication;
import org.apache.cloudberry.pxf.automation.utils.exception.ExceptionUtils;
import org.apache.cloudberry.pxf.automation.BasePXFTest;
import org.apache.hadoop.conf.Configuration;
import org.postgresql.util.PSQLException;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Functional cases for PXF HBase connector, running via TestContainers.
 */
public class HBaseTest extends BasePXFTest {

    private PXFCBDBContainer container;
    private HBaseContainer hbaseContainer;
    private CbdbApplication cbdb;
    private RegressApplication regress;
    private HBase hbase;
    private String pxfHost;
    private String pxfPort;

    private HBaseTable hbaseTable;
    private HBaseTable hbaseTableWithNulls;
    private LookupTable lookupTable;
    private ReadableExternalTable exTable;
    private ReadableExternalTable exTableNullHBase;

    private final String NO_FILTER = "No filter";
    private HBaseDataPreparer dataPreparer = new HBaseDataPreparer();
    private String testPackage = "org.apache.cloudberry.pxf.automation.testplugin.";

    private String[] hbaseTableQualifiers =
            new String[]{"q1", "q2", "q3", "q4", "q5", "q6", "q7", "q8", "q9", "q10", "q11", "q12"};

    private String[] exTableFields = new String[]{
            "recordkey TEXT",
            "\"cf1:q1\" VARCHAR",
            "\"cf1:q2\" TEXT",
            "\"cf1:q3\" INT",
            "\"cf1:q4\" BYTEA",
            "\"cf1:q5\" REAL",
            "\"cf1:q6\" FLOAT",
            "\"cf1:q7\" BYTEA",
            "\"cf1:q8\" SMALLINT",
            "\"cf1:q9\" BIGINT",
            "\"cf1:q10\" BOOLEAN",
            "\"cf1:q11\" NUMERIC",
            "\"q12\" TIMESTAMP"
    };

    private String[] exTableDifferentFieldsNames = new String[]{
            "recordkey TEXT",
            "\"cf1:q1\" VARCHAR",
            "\"q2\" TEXT",
            "\"q3\" INT",
            "\"cf1:q4\" BYTEA",
            "\"cf1:q5\" REAL",
            "\"cf1:q6\" FLOAT",
            "\"cf1:q7\" BYTEA",
            "\"cf1:q8\" SMALLINT",
            "\"cf1:q9\" BIGINT",
            "\"cf1:q10\" BOOLEAN",
            "\"cf1:q11\" NUMERIC",
            "\"cf1:q12\" TIMESTAMP"
    };

    @BeforeClass(alwaysRun = true)
    public void setup() throws Exception {
        container = PXFCBDBContainer.getInstance();
        hbaseContainer = HBaseContainer.getInstance(PXFCBDBContainer.getSharedNetwork());
        container.configureHBaseServer(hbaseContainer);

        pxfHost = container.getPxfInternalHost();
        pxfPort = String.valueOf(container.getPxfInternalPort());

        cbdb = new CbdbApplication(container);
        cbdb.connect();
        cbdb.createExtension("pxf");

        regress = new RegressApplication(container);

        Configuration hbaseConfig = new Configuration();
        String[] zkParts = hbaseContainer.getZookeeperConnectString().split(":");
        hbaseConfig.set("hbase.zookeeper.quorum", zkParts[0]);
        hbaseConfig.setInt("hbase.zookeeper.property.clientPort", Integer.parseInt(zkParts[1]));
        hbase = new HBase(hbaseConfig);
        hbase.connect();

        hbase.grantGlobalForUser("pxf");

        hbaseTable = new HBaseTable("hbase_table", new String[]{"cf1"});
        exTable = prepareDataChain(hbaseTable, dataPreparer, 100);

        hbaseTableWithNulls = new HBaseTable("hbase_null_table", new String[]{"cf1"});
        dataPreparer.setUseNull(true);
        exTableNullHBase = prepareDataChain(hbaseTableWithNulls, dataPreparer, 100);

        String[] fields = exTableFields.clone();
        fields[12] = "\"cf1:q12\" TIMESTAMP";
        ReadableExternalTable exTableFullColumnNames = TableFactory.getPxfHBaseReadableTable(
                "pxf_hbase_full_names", fields, hbaseTable);
        exTableFullColumnNames.setHost(pxfHost);
        exTableFullColumnNames.setPort(pxfPort);
        cbdb.createTableAndVerify(exTableFullColumnNames);

        prepareLookupTable();
    }

    @AfterClass(alwaysRun = true)
    public void teardown() throws Exception {
        if (hbase != null) hbase.close();
        if (cbdb != null) cbdb.close();
    }

    private void prepareLookupTable() throws Exception {
        lookupTable = new LookupTable();
        hbase.createTableAndVerify(lookupTable);
        lookupTable.addMapping(hbaseTable.getName(), "q12", "cf1:q12");
        lookupTable.addMapping(hbaseTableWithNulls.getName(), "q12", "cf1:q12");
        hbase.put(lookupTable);
    }

    @Test(groups = {"hbase", "features", "sanity", "gpdb"})
    public void sanity() throws Exception {
        verifyFilterResults(hbaseTable, exTable, "", NO_FILTER, "sanity", false);
    }

    @Test(groups = {"hbase", "features", "gpdb"})
    public void lowerFilter() throws Exception {
        verifyFilterResults(hbaseTable, exTable,
                " WHERE \"cf1:q3\" < '00000030'", "a3c23s2d30o1", "lower");
    }

    @Test(groups = {"hbase", "features", "gpdb"})
    public void rangeFilter() throws Exception {
        verifyFilterResults(hbaseTable, exTable,
                " WHERE \"cf1:q3\" > '00000090' AND \"cf1:q3\" <= '00000103'",
                "a3c23s2d90o2a3c23s3d103o3l0", "range");
    }

    @Test(groups = {"hbase", "features", "gpdb"})
    public void specificRowFilter() throws Exception {
        verifyFilterResults(hbaseTable, exTable,
                " WHERE \"cf1:q3\" = 4", "a3c23s1d4o5", "specificRow");
    }

    @Test(groups = {"hbase", "features", "gpdb"})
    public void notEqualsFilter() throws Exception {
        verifyFilterResults(hbaseTable, exTable,
                " WHERE \"cf1:q3\" != 30", "a3c23s2d30o6", "notEquals");
    }

    @Test(groups = {"hbase", "features", "gpdb"})
    public void rowkeyEqualsFilter() throws Exception {
        verifyFilterResults(hbaseTable, exTable,
                " WHERE recordkey = '00000090'", "a0c25s8d00000090o5", "rowkeyEquals");
    }

    @Test(groups = {"hbase", "features", "gpdb"})
    public void rowkeyRangeFilter() throws Exception {
        verifyFilterResults(hbaseTable, exTable,
                " WHERE recordkey > '00000090' AND recordkey <= '00000103'",
                "a0c25s8d00000090o2a0c25s8d00000103o3l0", "rowkeyRange");
    }

    @Test(groups = {"hbase", "features", "gpdb"})
    public void multipleQualifiersPushdownFilter() throws Exception {
        verifyFilterResults(hbaseTable, exTable,
                " WHERE recordkey != '00000002' AND \"cf1:q3\" > 6  AND \"cf1:q8\" < 10 AND \"cf1:q9\" > 0",
                "a0c25s8d00000002o6a3c23s1d6o2a8c23s2d10o1a9c23s1d0o2l0l0l0",
                "multipleQualifiers");
    }

    @Test(groups = {"hbase", "features", "gpdb"})
    public void partialFilterPushdown() throws Exception {
        verifyFilterResults(hbaseTable, exTable,
                " WHERE \"cf1:q3\" > 6  AND \"cf1:q7\" = '42'",
                "No filter", "partialFilterPushdown", false);
    }

    @Test(groups = {"hbase", "features", "gpdb"})
    public void textFilter() throws Exception {
        verifyFilterResults(hbaseTable, exTable,
                " WHERE \"cf1:q2\" = 'UTF8_計算機用語_00000024'",
                "a2c25s29dUTF8_計算機用語_00000024o5", "text");
    }

    @Test(groups = {"hbase", "features", "gpdb"})
    public void doubleFilter() throws Exception {
        verifyFilterResults(hbaseTable, exTable,
                " WHERE \"cf1:q5\" > 91.92 AND \"cf1:q6\" <= 99999999.99",
                "a5c701s5d91.92o2a6c701s11d99999999.99o3l0", "double", false);
    }

    @Test(groups = {"hbase", "features", "gpdb"})
    public void orFilter() throws Exception {
        verifyFilterResults(hbaseTable, exTable,
                " WHERE \"cf1:q3\" < 10 OR \"cf1:q5\" > 90",
                "a3c23s2d10o1a5c701s2d90o2l1", "or", false);
        verifyFilterResults(hbaseTable, exTable,
                " WHERE (((recordkey > '00000090') AND (recordkey <= '00000103')) OR (recordkey = '00000005'))",
                "a0c25s8d00000090o2a0c25s8d00000103o3l0a0c25s8d00000005o5l1", "andOr", false);
    }

    @Test(groups = {"hbase", "features", "gpdb"})
    public void mixedFilterPushdownOrAnd() throws Exception {
        verifyFilterResults(hbaseTable, exTable,
                " WHERE (\"cf1:q3\" < 10 OR \"cf1:q5\" > 90) AND (\"cf1:q3\" > 5 AND \"cf1:q8\" < 30)",
                "a3c23s2d10o1a5c701s2d90o2l1a3c23s1d5o2a8c23s2d30o1l0l0",
                "partialFilterPushdown", false);
        verifyFilterResults(hbaseTable, exTable,
                " WHERE (recordkey > '00000001') AND ((recordkey <= '00000093') AND (recordkey >= '00000080') OR recordkey = '0')",
                "a0c25s8d00000001o2a0c25s8d00000093o3a0c25s8d00000080o4l0a0c25s1d0o5l1l0",
                "partialFilterPushdownAndOr", false);
    }

    @Test(groups = {"hbase", "features", "gpdb"})
    public void isNullFilter() throws Exception {
        verifyFilterResults(hbaseTableWithNulls, exTable,
                " WHERE \"cf1:q3\" is null", "a3o8", "isNull", false);
    }

    @Test(groups = {"hbase", "features", "gpdb"})
    public void differentColumnNames() throws Exception {
        exTableNullHBase = TableFactory.getPxfHBaseReadableTable(
                "pxf_hbase_different_columns_names", exTableDifferentFieldsNames, hbaseTable);
        exTableNullHBase.setHost(pxfHost);
        exTableNullHBase.setPort(pxfPort);
        cbdb.createTableAndVerify(exTableNullHBase);
        regress.runSqlTest("features/hbase/errors/differentColumnsNames");
    }

    @Test(groups = {"hbase", "features", "gpdb"})
    public void disableLookupTable() throws Exception {
        try {
            hbase.disableTable(lookupTable);
            regress.runSqlTest("features/hbase/errors/lookupTable");
        } finally {
            hbase.enableTable(lookupTable);
        }
    }

    @Test(groups = {"hbase", "features", "gpdb"})
    public void noLookupTable() throws Exception {
        try {
            hbase.dropTable(lookupTable, false);
            regress.runSqlTest("features/hbase/errors/lookupTable");
        } finally {
            prepareLookupTable();
        }
    }

    @Test(groups = {"hbase", "features", "gpdb"})
    public void removeColumnFromLookupTable() throws Exception {
        try {
            hbase.addColumn(lookupTable, new String[]{"no_mapping"});
            hbase.removeColumn(lookupTable, new String[]{"mapping"});
            regress.runSqlTest("features/hbase/errors/lookupTable");
        } finally {
            prepareLookupTable();
        }
    }

    @Test(groups = {"hbase", "features", "gpdb"})
    public void recordkeyAsInteger() throws Exception {
        String[] fields = exTableFields.clone();
        fields[0] = "recordkey INTEGER";
        ReadableExternalTable integerRecordKeyExtTable = TableFactory.getPxfHBaseReadableTable(
                "pxf_hbase_integer_key", fields, hbaseTable);
        integerRecordKeyExtTable.setHost(pxfHost);
        integerRecordKeyExtTable.setPort(pxfPort);
        cbdb.createTableAndVerify(integerRecordKeyExtTable);
        verifyFilterResults(hbaseTable, integerRecordKeyExtTable,
                " WHERE recordkey > 90 AND recordkey <= 103",
                "a0c23s2d90o2a0c23s3d103o3l0", "recordkeyAsInteger");
    }

    @Test(groups = {"hbase", "features", "gpdb"})
    public void notExistingHBaseTable() throws Exception {
        ReadableExternalTable notExistsHBaseTableExtTable = TableFactory.getPxfHBaseReadableTable(
                "pxf_not_existing_hbase_table", exTableFields, new HBaseTable("dummy", null));
        notExistsHBaseTableExtTable.setHost(pxfHost);
        notExistsHBaseTableExtTable.setPort(pxfPort);
        cbdb.createTableAndVerify(notExistsHBaseTableExtTable);
        regress.runSqlTest("features/hbase/errors/notExistingHBaseTable");
    }

    @Test(groups = {"hbase", "features", "gpdb"})
    public void multiRegionsData() throws Exception {
        HBaseTable multiDataHBaseTable = new HBaseTable(
                "hbase_table_multi_regions", new String[]{"cf1"});
        int numberOfRegions = 100;
        int rowsPerRegion = 300;
        multiDataHBaseTable.setNumberOfSplits(numberOfRegions);
        multiDataHBaseTable.setRowsPerSplit(rowsPerRegion);
        LookupTable additionalMapping = new LookupTable();
        additionalMapping.addMapping(multiDataHBaseTable.getName(), "q12", "cf1:q12");
        hbase.put(additionalMapping);
        dataPreparer.setNumberOfSplits(numberOfRegions);
        prepareDataChain(multiDataHBaseTable, dataPreparer, rowsPerRegion);
        regress.runSqlTest("features/hbase/multiRegionsData");
    }

    @Test(groups = {"hbase", "features", "gpdb"})
    public void longHBaseQualifierNameNoLookupTable() throws Exception {
        HBaseTable longQualifiersNamesHBaseTable = new HBaseTable(
                "long_qualifiers_hbase_table", new String[]{"cf1"});
        String[] qualifiers = new String[]{
                "very_long_qualifier_name_that_gpdb_will_probaly_is_going_to_cut",
                "short_qualifier"
        };
        String[] gpdbFields = new String[]{
                "recordkey TEXT",
                "\"cf1:very_long_qualifier_name_that_gpdb_will_probaly_is_going_to_cut\" TEXT",
                "\"cf1:short_qualifier\" TEXT"
        };
        longQualifiersNamesHBaseTable.setQualifiers(qualifiers);
        hbase.createTableAndVerify(longQualifiersNamesHBaseTable);
        HBaseLongQualifierDataPreparer dp = new HBaseLongQualifierDataPreparer();
        dp.prepareData(10, longQualifiersNamesHBaseTable);
        hbase.put(longQualifiersNamesHBaseTable);
        ReadableExternalTable externalTable = TableFactory.getPxfHBaseReadableTable(
                "long_qualifiers_hbase_table", gpdbFields, longQualifiersNamesHBaseTable);
        externalTable.setHost(pxfHost);
        externalTable.setPort(pxfPort);
        try {
            cbdb.createTableAndVerify(externalTable);
            Assert.fail("Exception should have been thrown");
        } catch (Exception e) {
            ExceptionUtils.validate(null, e,
                    new PSQLException("identifier \"cf1:very_long_qualifier_name_that_gpdb_will_probaly_is_going_to_cut\" "
                            + "will be truncated to \"cf1:very_long_qualifier_name_that_gpdb_will_probaly_is_going_to\"",
                            null), false, true);
        }
        regress.runSqlTest("features/hbase/longQualifierNoLookup");
    }

    @Test(groups = {"hbase", "features", "gpdb"})
    public void longHBaseQualifierNameUsingLookupTable() throws Exception {
        HBaseTable longQualifiersNamesHBaseTable = new HBaseTable(
                "long_qualifiers_hbase_table", new String[]{"cf1"});
        String[] qualifiers = new String[]{
                "very_long_qualifier_name_that_gpdb_will_probaly_is_going_to_cut",
                "short_qualifier"
        };
        String[] gpdbFields = new String[]{
                "used_to_be_long TEXT",
                "short TEXT"
        };
        longQualifiersNamesHBaseTable.setQualifiers(qualifiers);
        hbase.createTableAndVerify(longQualifiersNamesHBaseTable);
        HBaseLongQualifierDataPreparer dp = new HBaseLongQualifierDataPreparer();
        dp.prepareData(10, longQualifiersNamesHBaseTable);
        hbase.put(longQualifiersNamesHBaseTable);
        LookupTable additionalMapping = new LookupTable();
        additionalMapping.addMapping(longQualifiersNamesHBaseTable.getName(),
                "used_to_be_long", "cf1:" + qualifiers[0]);
        additionalMapping.addMapping(longQualifiersNamesHBaseTable.getName(),
                "short", "cf1:" + qualifiers[1]);
        hbase.put(additionalMapping);
        ReadableExternalTable externalTable = TableFactory.getPxfHBaseReadableTable(
                "long_qualifiers_hbase_table", gpdbFields, longQualifiersNamesHBaseTable);
        externalTable.setHost(pxfHost);
        externalTable.setPort(pxfPort);
        cbdb.createTableAndVerify(externalTable);
        regress.runSqlTest("features/hbase/longQualifierWithLookup");
    }

    @Test(groups = {"hbase", "features", "gpdb"})
    public void emptyHBaseTable() throws Exception {
        HBaseTable emptyTable = new HBaseTable("empty_table", new String[]{"cf1"});
        String[] fields = new String[]{
                "recordkey TEXT",
                "\"cf1:q1\" VARCHAR",
                "\"cf1:q2\" TEXT",
                "\"cf1:q3\" INT"
        };
        hbase.createTableAndVerify(emptyTable);
        ReadableExternalTable et = TableFactory.getPxfHBaseReadableTable(
                "empty_hbase_table", fields, emptyTable);
        et.setHost(pxfHost);
        et.setPort(pxfPort);
        cbdb.createTableAndVerify(et);
        verifyFilterResults(emptyTable, et, "", NO_FILTER, "empty", false);
    }

    // ---- helper methods ----

    private void verifyFilterResults(HBaseTable hbaseTable, ReadableExternalTable externalTable,
                                     String whereClause, String filterString, String sqlTestPath)
            throws Exception {
        verifyFilterResults(hbaseTable, externalTable, whereClause, filterString, sqlTestPath, true);
    }

    private void verifyFilterResults(HBaseTable hbaseTable, ReadableExternalTable externalTable,
                                     String whereClause, String filterString, String sqlTestPath,
                                     boolean verifyFilterString) throws Exception {
        cbdb.runQuery("SET gp_external_enable_filter_pushdown = off");
        regress.runSqlTest("features/hbase/" + sqlTestPath);
        cbdb.runQuery("SET gp_external_enable_filter_pushdown = on");
        regress.runSqlTest("features/hbase/" + sqlTestPath);
        createAndQueryPxfGpdbFilterTable(hbaseTable, externalTable.getFields(), whereClause, filterString);
        if (verifyFilterString) {
            createPxfHBaseFilterTable(filterString, hbaseTable, externalTable.getFields());
            regress.runSqlTest("features/hbase/filter_accessor/" + sqlTestPath);
        }
    }

    private void createAndQueryPxfGpdbFilterTable(HBaseTable hbaseTable, String[] fields,
                                                  String whereClause, String expectedFilter)
            throws Exception {
        ReadableExternalTable externalTableFilterPrinter = new ReadableExternalTable(
                "hbase_pxf_print_filter", fields, hbaseTable.getName(), "CUSTOM");
        externalTableFilterPrinter.setFragmenter("org.apache.cloudberry.pxf.plugins.hbase.HBaseDataFragmenter");
        externalTableFilterPrinter.setAccessor(testPackage + "FilterPrinterAccessor");
        externalTableFilterPrinter.setResolver("org.apache.cloudberry.pxf.plugins.hbase.HBaseResolver");
        externalTableFilterPrinter.setFormatter("pxfwritable_import");
        externalTableFilterPrinter.setHost(pxfHost);
        externalTableFilterPrinter.setPort(pxfPort);
        cbdb.createTableAndVerify(externalTableFilterPrinter);

        try {
            cbdb.runQuery("SELECT * FROM " + externalTableFilterPrinter.getName()
                    + " " + whereClause + " ORDER BY recordkey ASC");
        } catch (Exception e) {
            expectedFilter = expectedFilter.replace("\"", "&quot;");
            ExceptionUtils.validate(null, e,
                    new Exception("ERROR.*Filter string: '" + expectedFilter + "'.*"), true, true);
        }
    }

    private void createPxfHBaseFilterTable(String filter, HBaseTable hbaseTable, String[] fields)
            throws Exception {
        ReadableExternalTable externalTableHBaseWithFilter = new ReadableExternalTable(
                "hbase_pxf_with_filter", fields, hbaseTable.getName(), "CUSTOM");
        externalTableHBaseWithFilter.setFragmenter("org.apache.cloudberry.pxf.plugins.hbase.HBaseDataFragmenter");
        externalTableHBaseWithFilter.setAccessor(testPackage + "HBaseAccessorWithFilter");
        externalTableHBaseWithFilter.setResolver("org.apache.cloudberry.pxf.plugins.hbase.HBaseResolver");
        externalTableHBaseWithFilter.setFormatter("pxfwritable_import");
        externalTableHBaseWithFilter.setUserParameters(new String[]{"TEST-HBASE-FILTER=" + filter});
        externalTableHBaseWithFilter.setHost(pxfHost);
        externalTableHBaseWithFilter.setPort(pxfPort);
        cbdb.createTableAndVerify(externalTableHBaseWithFilter);
    }

    private ReadableExternalTable prepareDataChain(HBaseTable hbaseTable,
                                                   HBaseDataPreparer dataPreparer, int rows)
            throws Exception {
        hbaseTable.setRowsPerSplit(rows);
        hbaseTable.setRowKeyPrefix("row");
        hbaseTable.setQualifiers(hbaseTableQualifiers);
        hbase.createTableAndVerify(hbaseTable);
        dataPreparer.setColumnFamilyName(hbaseTable.getFields()[0]);
        dataPreparer.prepareData(hbaseTable.getRowsPerSplit(), hbaseTable);
        hbase.put(hbaseTable);
        ReadableExternalTable et = TableFactory.getPxfHBaseReadableTable(
                "pxf_" + hbaseTable.getName(), exTableFields, hbaseTable);
        et.setHost(pxfHost);
        et.setPort(pxfPort);
        cbdb.createTableAndVerify(et);
        return et;
    }
}
