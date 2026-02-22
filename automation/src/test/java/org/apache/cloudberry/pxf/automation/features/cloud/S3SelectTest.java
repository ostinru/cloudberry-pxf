package org.apache.cloudberry.pxf.automation.features.cloud;

import org.apache.cloudberry.pxf.automation.BasePXFTest;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.cloudberry.pxf.automation.components.hdfs.Hdfs;
import org.apache.cloudberry.pxf.automation.structures.tables.pxf.ReadableExternalTable;
import org.apache.cloudberry.pxf.automation.testcontainers.CbdbApplication;
import org.apache.cloudberry.pxf.automation.testcontainers.MinIOContainer;
import org.apache.cloudberry.pxf.automation.testcontainers.PXFCBDBContainer;
import org.apache.cloudberry.pxf.automation.testcontainers.RegressApplication;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.URI;
import java.util.UUID;

import static org.apache.cloudberry.pxf.automation.features.tpch.LineItem.LINEITEM_SCHEMA;

/**
 * S3 Select tests driven by TestContainers (MinIO + CBDB/PXF).
 */
public class S3SelectTest extends BasePXFTest {

    private static final String PROTOCOL_S3 = "s3a://";

    private static final String[] PXF_S3_SELECT_INVALID_COLS = {
            "invalid_orderkey       BIGINT",
            "invalid_partkey        BIGINT",
            "invalid_suppkey        BIGINT",
            "invalid_linenumber     BIGINT",
            "invalid_quantity       DECIMAL(15,2)",
            "invalid_extendedprice  DECIMAL(15,2)",
            "invalid_discount       DECIMAL(15,2)",
            "invalid_tax            DECIMAL(15,2)",
            "invalid_returnflag     CHAR(1)",
            "invalid_linestatus     CHAR(1)",
            "invalid_shipdate       DATE",
            "invalid_commitdate     DATE",
            "invalid_receiptdate    DATE",
            "invalid_shipinstruct   CHAR(25)",
            "invalid_shipmode       CHAR(10)",
            "invalid_comment        VARCHAR(44)"
    };

    private static final String sampleCsvFile = "sample.csv";
    private static final String sampleGzippedCsvFile = "sample.csv.gz";
    private static final String sampleBzip2CsvFile = "sample.csv.bz2";
    private static final String sampleCsvNoHeaderFile = "sample-no-header.csv";
    private static final String sampleParquetFile = "sample.parquet";
    private static final String sampleParquetSnappyFile = "sample.snappy.parquet";
    private static final String sampleParquetGzipFile = "sample.gz.parquet";

    private static final String localDataResourcesFolder = "src/test/resources/data";

    private PXFCBDBContainer container;
    private MinIOContainer minio;
    private CbdbApplication cbdb;
    private RegressApplication regress;
    private Hdfs s3Server;
    private String s3Path;
    private ReadableExternalTable exTable;

    @BeforeClass(alwaysRun = true)
    public void setup() throws Exception {
        container = PXFCBDBContainer.getInstance();
        minio = MinIOContainer.getInstance(PXFCBDBContainer.getSharedNetwork());

        container.configureS3Servers(minio);

        cbdb = new CbdbApplication(container);
        cbdb.connect();
        cbdb.createExtension("pxf");

        regress = new RegressApplication(container);

        s3Path = String.format("gpdb-ud-scratch/tmp/pxf_automation_data/%s/s3select/", UUID.randomUUID());
        Configuration s3Config = new Configuration();
        s3Config.set("fs.s3a.access.key", minio.getAccessKey());
        s3Config.set("fs.s3a.secret.key", minio.getSecretKey());
        s3Config.set("fs.s3a.endpoint", minio.getHostEndpoint());
        s3Config.set("fs.s3a.path.style.access", "true");
        s3Config.set("fs.s3a.connection.ssl.enabled", "false");
        s3Config.set("fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem");
        s3Config.set("fs.s3a.aws.credentials.provider",
                "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider");

        FileSystem fs = FileSystem.get(URI.create(PROTOCOL_S3 + s3Path), s3Config);
        s3Server = new Hdfs(fs, s3Config, true);
    }

    @AfterClass(alwaysRun = true)
    public void teardown() throws Exception {
        if (s3Server != null) {
            s3Server.removeDirectory(PROTOCOL_S3 + s3Path);
        }
        if (cbdb != null) {
            cbdb.close();
        }
    }

    @Test(groups = {"s3"})
    public void testPlainCsvWithHeaders() throws Exception {
        String[] userParameters = {"FILE_HEADER=IGNORE", "S3_SELECT=ON"};
        runTestScenario("csv", "s3", "csv", s3Path,
                localDataResourcesFolder + "/s3select/", sampleCsvFile,
                "|", userParameters);
    }

    @Test(groups = {"s3"})
    public void testPlainCsvWithHeadersUsingHeaderInfo() throws Exception {
        String[] userParameters = {"FILE_HEADER=USE", "S3_SELECT=ON"};
        runTestScenario("csv_use_headers", "s3", "csv", s3Path,
                localDataResourcesFolder + "/s3select/", sampleCsvFile,
                "|", userParameters);
    }

    @Test(groups = {"s3"})
    public void testCsvWithHeadersUsingHeaderInfoWithWrongColumnNames() throws Exception {
        String[] userParameters = {"FILE_HEADER=USE", "S3_SELECT=ON"};
        runTestScenario("errors/", "csv_use_headers_with_wrong_col_names", "s3", "csv", s3Path,
                localDataResourcesFolder + "/s3select/", sampleCsvFile, "/" + s3Path + sampleCsvFile,
                "|", userParameters, PXF_S3_SELECT_INVALID_COLS);
    }

    @Test(groups = {"s3"})
    public void testPlainCsvWithNoHeaders() throws Exception {
        String[] userParameters = {"FILE_HEADER=NONE", "S3_SELECT=ON"};
        runTestScenario("csv_noheaders", "s3", "csv", s3Path,
                localDataResourcesFolder + "/s3select/", sampleCsvNoHeaderFile,
                "|", userParameters);
    }

    @Test(groups = {"s3"})
    public void testGzipCsvWithHeadersUsingHeaderInfo() throws Exception {
        String[] userParameters = {"FILE_HEADER=USE", "S3_SELECT=ON", "COMPRESSION_CODEC=gzip"};
        runTestScenario("gzip_csv_use_headers", "s3", "csv", s3Path,
                localDataResourcesFolder + "/s3select/", sampleGzippedCsvFile,
                "|", userParameters);
    }

    @Test(groups = {"s3"})
    public void testBzip2CsvWithHeadersUsingHeaderInfo() throws Exception {
        String[] userParameters = {"FILE_HEADER=USE", "S3_SELECT=ON", "COMPRESSION_CODEC=bzip2"};
        runTestScenario("bzip2_csv_use_headers", "s3", "csv", s3Path,
                localDataResourcesFolder + "/s3select/", sampleBzip2CsvFile,
                "|", userParameters);
    }

    @Test(groups = {"s3"})
    public void testParquet() throws Exception {
        String[] userParameters = {"S3_SELECT=ON"};
        runTestScenario("parquet", "s3", "parquet", s3Path,
                localDataResourcesFolder + "/s3select/", sampleParquetFile,
                null, userParameters);
    }

    @Test(groups = {"s3"})
    public void testParquetWildcardLocation() throws Exception {
        String[] userParameters = {"S3_SELECT=ON"};
        runTestScenario("", "parquet", "s3", "parquet", s3Path,
                localDataResourcesFolder + "/s3select/", sampleParquetFile, "/" + s3Path + "*e.parquet",
                null, userParameters, LINEITEM_SCHEMA);
    }

    @Test(groups = {"s3"})
    public void testSnappyParquet() throws Exception {
        String[] userParameters = {"S3_SELECT=ON"};
        runTestScenario("parquet_snappy", "s3", "parquet", s3Path,
                localDataResourcesFolder + "/s3select/", sampleParquetSnappyFile,
                null, userParameters);
    }

    @Test(groups = {"s3"})
    public void testGzipParquet() throws Exception {
        String[] userParameters = {"S3_SELECT=ON"};
        runTestScenario("parquet_gzip", "s3", "parquet", s3Path,
                localDataResourcesFolder + "/s3select/", sampleParquetGzipFile,
                null, userParameters);
    }

    private void runTestScenario(
            String name, String server, String format, String s3Path,
            String srcPath, String filename, String delimiter,
            String[] userParameters) throws Exception {

        runTestScenario("", name, server, format, s3Path, srcPath, filename,
                "/" + s3Path + filename, delimiter, userParameters, LINEITEM_SCHEMA);
    }

    private void runTestScenario(
            String qualifier, String name, String server, String format,
            String s3Path, String srcPath, String filename, String locationPath,
            String delimiter, String[] userParameters, String[] fields) throws Exception {

        String tableName = "s3select_" + name;
        String serverParam = (server == null) ? null : "server=" + server;

        s3Server.copyFromLocal(srcPath + filename, PROTOCOL_S3 + s3Path + filename);

        exTable = new ReadableExternalTable(tableName, fields, locationPath, "CSV");
        exTable.setProfile("s3:" + format);
        exTable.setServer(serverParam);

        if (delimiter != null)
            exTable.setDelimiter(delimiter);
        if (userParameters != null)
            exTable.setUserParameters(userParameters);

        cbdb.createTableAndVerify(exTable);

        regress.runSqlTest(String.format("features/s3_select/%s%s", qualifier, name));
    }
}
