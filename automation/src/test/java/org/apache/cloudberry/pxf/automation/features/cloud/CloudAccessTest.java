package org.apache.cloudberry.pxf.automation.features.cloud;

import annotations.WorksWithFDW;
import org.apache.cloudberry.pxf.automation.BasePXFTest;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.cloudberry.pxf.automation.components.hdfs.Hdfs;
import org.apache.cloudberry.pxf.automation.structures.tables.basic.Table;
import org.apache.cloudberry.pxf.automation.structures.tables.pxf.ExternalTable;
import org.apache.cloudberry.pxf.automation.structures.tables.utils.TableFactory;
import org.apache.cloudberry.pxf.automation.testcontainers.CbdbApplication;
import org.apache.cloudberry.pxf.automation.testcontainers.MinIOContainer;
import org.apache.cloudberry.pxf.automation.testcontainers.PXFCBDBContainer;
import org.apache.cloudberry.pxf.automation.testcontainers.RegressApplication;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Functional CloudAccess Test
 */
@WorksWithFDW
public class CloudAccessTest extends BasePXFTest {

    private static final String PROTOCOL_S3 = "s3a://";
    private static final String FILE_NAME = "data.txt";

    private static final String[] PXF_MULTISERVER_COLS = {
            "name text",
            "num integer",
            "dub double precision",
            "longNum bigint",
            "bool boolean"
    };

    private static final String[] PXF_WRITE_COLS = {
            "name text",
            "score integer"
    };

    private PXFCBDBContainer container;
    private MinIOContainer minio;
    private CbdbApplication cbdb;
    private RegressApplication regress;
    private Hdfs s3Server;
    private String s3PathRead, s3PathWrite;
    private ExternalTable exTable;

    @BeforeClass(alwaysRun = true)
    public void setup() throws Exception {
        container = PXFCBDBContainer.getInstance();
        minio = MinIOContainer.getInstance(PXFCBDBContainer.getSharedNetwork());

        container.configureS3Servers(minio);

        cbdb = new CbdbApplication(container);
        cbdb.connect();
        cbdb.createExtension("pxf");

        regress = new RegressApplication(container);

        String random = UUID.randomUUID().toString();
        s3PathRead  = String.format("gpdb-ud-scratch/tmp/pxf_automation_data_read/%s/", random);
        s3PathWrite = String.format("gpdb-ud-scratch/tmp/pxf_automation_data_write/%s/", random);

        Configuration s3Config = new Configuration();
        s3Config.set("fs.s3a.access.key", minio.getAccessKey());
        s3Config.set("fs.s3a.secret.key", minio.getSecretKey());
        s3Config.set("fs.s3a.endpoint", minio.getHostEndpoint());
        s3Config.set("fs.s3a.path.style.access", "true");
        s3Config.set("fs.s3a.connection.ssl.enabled", "false");
        s3Config.set("fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem");
        s3Config.set("fs.s3a.aws.credentials.provider",
                "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider");

        FileSystem fs = FileSystem.get(URI.create(PROTOCOL_S3 + s3PathRead + FILE_NAME), s3Config);
        s3Server = new Hdfs(fs, s3Config, true);
    }

    @AfterClass(alwaysRun = true)
    public void teardown() throws Exception {
        if (cbdb != null) {
            cbdb.close();
        }
    }

    @BeforeMethod(alwaysRun = true)
    public void prepareData() throws Exception {
        Table dataTable = getSmallData();
        s3Server.writeTableToFile(PROTOCOL_S3 + s3PathRead + FILE_NAME, dataTable, ",");
        s3Server.createDirectory(PROTOCOL_S3 + s3PathWrite);
    }

    @AfterMethod(alwaysRun = true)
    public void cleanupData() throws Exception {
        if (s3Server != null) {
            s3Server.removeDirectory(PROTOCOL_S3 + s3PathRead);
            s3Server.removeDirectory(PROTOCOL_S3 + s3PathWrite);
        }
    }

    /*
     * Tests below assume the "default" server has NO Hadoop config (S3-only).
     * Part of the "s3" group -- run with ./gradlew test -Dgroups=s3
     */

    @Test(groups = {"s3"})
    public void testCloudAccessFailsWhenNoServerNoCredsSpecified() throws Exception {
        runTestScenario("no_server_no_credentials", null, false);
    }

    @Test(groups = {"s3"})
    public void testCloudAccessFailsWhenServerNoCredsNoConfigFileExists() throws Exception {
        runTestScenario("server_no_credentials_no_config", "s3-non-existent", false);
    }

    @Test(groups = {"s3"})
    public void testCloudAccessOkWhenNoServerCredsNoConfigFileExists() throws Exception {
        runTestScenario("no_server_credentials_no_config", null, true);
    }

    @Test(groups = {"s3"})
    public void testCloudAccessFailsWhenServerNoCredsInvalidConfigFileExists() throws Exception {
        runTestScenario("server_no_credentials_invalid_config", "s3-invalid", false);
    }

    @Test(groups = {"s3"})
    public void testCloudAccessOkWhenServerCredsInvalidConfigFileExists() throws Exception {
        runTestScenario("server_credentials_invalid_config", "s3-invalid", true);
    }

    @Test(groups = {"s3"})
    public void testCloudAccessOkWhenServerCredsNoConfigFileExists() throws Exception {
        runTestScenario("server_credentials_no_config", "s3-non-existent", true);
    }

    /*
     * Tests below assume there IS a Hadoop cluster on the "default" server.
     * Part of "gpdb"/"security" groups -- they require a different PXF
     * server layout and are not run as part of the "s3" group.
     */

    @Test(groups = {"gpdb", "security"})
    public void testCloudAccessWithHdfsFailsWhenNoServerNoCredsSpecified() throws Exception {
        runTestScenario("no_server_no_credentials_with_hdfs", null, false);
    }

    @Test(groups = {"gpdb", "security"})
    public void testCloudAccessWithHdfsOkWhenServerNoCredsValidConfigFileExists() throws Exception {
        runTestScenario("server_no_credentials_valid_config_with_hdfs", "s3", false);
    }

    @Test(groups = {"gpdb", "security"})
    public void testCloudWriteWithHdfsOkWhenServerNoCredsValidConfigFileExists() throws Exception {
        runTestScenarioForWrite("server_no_credentials_valid_config_with_hdfs_write", "s3", false);
    }

    @Test(groups = {"gpdb", "security"})
    public void testCloudAccessWithHdfsFailsWhenServerNoCredsNoConfigFileExists() throws Exception {
        runTestScenario("server_no_credentials_no_config_with_hdfs", "s3-non-existent", false);
    }

    @Test(groups = {"gpdb", "security"})
    public void testCloudAccessWithHdfsFailsWhenNoServerCredsNoConfigFileExists() throws Exception {
        runTestScenario("no_server_credentials_no_config_with_hdfs", null, true);
    }

    @Test(groups = {"gpdb", "security"})
    public void testCloudAccessWithHdfsFailsWhenServerNoCredsInvalidConfigFileExists() throws Exception {
        runTestScenario("server_no_credentials_invalid_config_with_hdfs", "s3-invalid", false);
    }

    @Test(groups = {"gpdb", "security"})
    public void testCloudAccessWithHdfsOkWhenServerCredsInvalidConfigFileExists() throws Exception {
        runTestScenario("server_credentials_invalid_config_with_hdfs", "s3-invalid", true);
    }

    private void runTestScenario(String name, String server, boolean creds) throws Exception {
        String tableName = "cloudaccess_" + name;
        exTable = TableFactory.getPxfReadableTextTable(tableName, PXF_MULTISERVER_COLS, s3PathRead + FILE_NAME, ",");
        exTable.setProfile("s3:text");
        String serverParam = (server == null) ? null : "server=" + server;
        exTable.setServer(serverParam);
        if (creds) {
            exTable.setUserParameters(new String[]{
                    "accesskey=" + minio.getAccessKey(),
                    "secretkey=" + minio.getSecretKey()});
        }
        cbdb.createTableAndVerify(exTable);

        regress.runSqlTest("features/cloud_access/" + name);
    }

    private void runTestScenarioForWrite(String name, String server, boolean creds) throws Exception {
        String tableName = "cloudwrite_" + name;
        exTable = TableFactory.getPxfWritableTextTable(tableName, PXF_WRITE_COLS, s3PathWrite, ",");
        exTable.setProfile("s3:text");
        String serverParam = (server == null) ? null : "server=" + server;
        exTable.setServer(serverParam);
        if (creds) {
            exTable.setUserParameters(new String[]{
                    "accesskey=" + minio.getAccessKey(),
                    "secretkey=" + minio.getSecretKey()});
        }
        cbdb.createTableAndVerify(exTable);

        tableName = "cloudaccess_" + name;
        exTable = TableFactory.getPxfReadableTextTable(tableName, PXF_WRITE_COLS, s3PathWrite, ",");
        exTable.setProfile("s3:text");
        exTable.setServer(serverParam);
        if (creds) {
            exTable.setUserParameters(new String[]{
                    "accesskey=" + minio.getAccessKey(),
                    "secretkey=" + minio.getSecretKey()});
        }
        cbdb.createTableAndVerify(exTable);

        regress.runSqlTest("features/cloud_access/" + name);
    }

}
