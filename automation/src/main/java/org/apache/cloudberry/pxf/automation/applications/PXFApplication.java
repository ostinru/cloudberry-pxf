package org.apache.cloudberry.pxf.automation.applications;

import org.apache.cloudberry.pxf.automation.testcontainers.MinIOContainer;
import org.apache.cloudberry.pxf.automation.testcontainers.PXFCloudberryContainer;
import org.testcontainers.containers.Container.ExecResult;

import java.io.IOException;

/**
 * Manages PXF server configuration inside the container.
 * Writes config files (jdbc-site.xml, s3-site.xml, etc.) and restarts the PXF process.
 */
public class PXFApplication {

    private static final String SCRIPTS_PREFIX =
            "/home/gpadmin/workspace/cloudberry-pxf/automation/src/main/resources/testcontainers/pxf-cbdb/script";

    private final PXFCloudberryContainer container;

    public PXFApplication(PXFCloudberryContainer container) {
        this.container = container;
    }

    public void configureJdbcServers() throws IOException, InterruptedException {
        System.out.println("[PXFApplication] Configuring JDBC servers (database, db-session-params, db-hive)...");

        String script = String.join("\n",
                "set -e",
                "source " + SCRIPTS_PREFIX + "/pxf-env.sh",
                "PXF_BASE_SERVERS=${PXF_BASE}/servers",
                "TEMPLATES_DIR=${PXF_HOME}/templates",

                "mkdir -p ${PXF_BASE_SERVERS}/database",
                "cp ${TEMPLATES_DIR}/jdbc-site.xml ${PXF_BASE_SERVERS}/database/",
                "sed -i 's|YOUR_DATABASE_JDBC_DRIVER_CLASS_NAME|org.postgresql.Driver|' ${PXF_BASE_SERVERS}/database/jdbc-site.xml",
                "sed -i 's|YOUR_DATABASE_JDBC_URL|jdbc:postgresql://localhost:7000/pxfautomation|' ${PXF_BASE_SERVERS}/database/jdbc-site.xml",
                "sed -i 's|YOUR_DATABASE_JDBC_USER||' ${PXF_BASE_SERVERS}/database/jdbc-site.xml",
                "sed -i 's|YOUR_DATABASE_JDBC_PASSWORD||' ${PXF_BASE_SERVERS}/database/jdbc-site.xml",
                "cp ${PXF_BASE_SERVERS}/database/jdbc-site.xml ${PXF_BASE_SERVERS}/database/testuser-user.xml",
                "sed -i 's|pxfautomation|template1|' ${PXF_BASE_SERVERS}/database/testuser-user.xml",
                "cp /home/gpadmin/workspace/cloudberry-pxf/automation/src/test/resources/report.sql ${PXF_BASE_SERVERS}/database/",

                "mkdir -p ${PXF_BASE_SERVERS}/db-session-params",
                "cp ${TEMPLATES_DIR}/jdbc-site.xml ${PXF_BASE_SERVERS}/db-session-params/",
                "sed -i 's|YOUR_DATABASE_JDBC_DRIVER_CLASS_NAME|org.postgresql.Driver|' ${PXF_BASE_SERVERS}/db-session-params/jdbc-site.xml",
                "sed -i 's|YOUR_DATABASE_JDBC_URL|jdbc:postgresql://localhost:7000/pxfautomation|' ${PXF_BASE_SERVERS}/db-session-params/jdbc-site.xml",
                "sed -i 's|YOUR_DATABASE_JDBC_USER||' ${PXF_BASE_SERVERS}/db-session-params/jdbc-site.xml",
                "sed -i 's|YOUR_DATABASE_JDBC_PASSWORD||' ${PXF_BASE_SERVERS}/db-session-params/jdbc-site.xml",
                "sed -i 's|</configuration>|<property><name>jdbc.session.property.client_min_messages</name><value>debug1</value></property></configuration>|' ${PXF_BASE_SERVERS}/db-session-params/jdbc-site.xml",
                "sed -i 's|</configuration>|<property><name>jdbc.session.property.default_statistics_target</name><value>123</value></property></configuration>|' ${PXF_BASE_SERVERS}/db-session-params/jdbc-site.xml",

                "mkdir -p ${PXF_BASE_SERVERS}/db-hive",
                "cp ${TEMPLATES_DIR}/jdbc-site.xml ${PXF_BASE_SERVERS}/db-hive/",
                "sed -i 's|YOUR_DATABASE_JDBC_DRIVER_CLASS_NAME|org.apache.hive.jdbc.HiveDriver|' ${PXF_BASE_SERVERS}/db-hive/jdbc-site.xml",
                "sed -i 's|YOUR_DATABASE_JDBC_URL|jdbc:hive2://localhost:10000/default|' ${PXF_BASE_SERVERS}/db-hive/jdbc-site.xml",
                "sed -i 's|YOUR_DATABASE_JDBC_USER||' ${PXF_BASE_SERVERS}/db-hive/jdbc-site.xml",
                "sed -i 's|YOUR_DATABASE_JDBC_PASSWORD||' ${PXF_BASE_SERVERS}/db-hive/jdbc-site.xml",
                "cp /home/gpadmin/workspace/cloudberry-pxf/automation/src/test/resources/hive-report.sql ${PXF_BASE_SERVERS}/db-hive/"
        );

        ExecResult result = container.execInContainer("bash", "-l", "-c", script);
        if (result.getExitCode() != 0) {
            throw new RuntimeException(
                    "JDBC server configuration failed (exit " + result.getExitCode() + "):\n"
                            + result.getStdout() + "\n" + result.getStderr());
        }

        restartPxf();

        System.out.println("[PXFApplication] JDBC servers configured and PXF restarted");
    }

    public void configureS3Servers(MinIOContainer minio) throws IOException, InterruptedException {
        String endpoint = minio.getInternalEndpoint();
        String accessKey = minio.getAccessKey();
        String secretKey = minio.getSecretKey();

        String script = String.join("\n",
                "set -e",
                "source " + SCRIPTS_PREFIX + "/pxf-env.sh",
                "PXF_BASE_SERVERS=${PXF_BASE}/servers",
                "",
                "mkdir -p ${PXF_BASE_SERVERS}/s3",
                "cat > ${PXF_BASE_SERVERS}/s3/s3-site.xml <<'S3SITEEOF'",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<configuration>",
                "  <property><name>fs.s3a.endpoint</name><value>" + endpoint + "</value></property>",
                "  <property><name>fs.s3a.access.key</name><value>" + accessKey + "</value></property>",
                "  <property><name>fs.s3a.secret.key</name><value>" + secretKey + "</value></property>",
                "  <property><name>fs.s3a.path.style.access</name><value>true</value></property>",
                "  <property><name>fs.s3a.connection.ssl.enabled</name><value>false</value></property>",
                "  <property><name>fs.s3a.impl</name><value>org.apache.hadoop.fs.s3a.S3AFileSystem</value></property>",
                "  <property><name>fs.s3a.aws.credentials.provider</name><value>org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider</value></property>",
                "</configuration>",
                "S3SITEEOF",
                "",
                "cat > ${PXF_BASE_SERVERS}/s3/core-site.xml <<'CORESITEEOF'",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<configuration>",
                "  <property><name>fs.defaultFS</name><value>s3a://</value></property>",
                "  <property><name>fs.s3a.endpoint</name><value>" + endpoint + "</value></property>",
                "  <property><name>fs.s3a.access.key</name><value>" + accessKey + "</value></property>",
                "  <property><name>fs.s3a.secret.key</name><value>" + secretKey + "</value></property>",
                "  <property><name>fs.s3a.path.style.access</name><value>true</value></property>",
                "  <property><name>fs.s3a.connection.ssl.enabled</name><value>false</value></property>",
                "  <property><name>fs.s3a.impl</name><value>org.apache.hadoop.fs.s3a.S3AFileSystem</value></property>",
                "  <property><name>fs.s3a.aws.credentials.provider</name><value>org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider</value></property>",
                "</configuration>",
                "CORESITEEOF",
                "",
                "cp ${PXF_BASE_SERVERS}/s3/s3-site.xml ${PXF_BASE_SERVERS}/default/s3-site.xml",
                "cp ${PXF_BASE_SERVERS}/s3/core-site.xml ${PXF_BASE_SERVERS}/default/core-site.xml",
                "",
                "mkdir -p ${PXF_BASE_SERVERS}/s3-invalid",
                "cat > ${PXF_BASE_SERVERS}/s3-invalid/s3-site.xml <<'INVALIDEOF'",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<configuration>",
                "  <property><name>fs.s3a.access.key</name><value>INVALID_KEY</value></property>",
                "  <property><name>fs.s3a.secret.key</name><value>INVALID_SECRET</value></property>",
                "  <property><name>fs.s3a.fast.upload</name><value>true</value></property>",
                "</configuration>",
                "INVALIDEOF",
                "",
                "mkdir -p /home/gpadmin/.aws",
                "cat > /home/gpadmin/.aws/credentials <<'AWSEOF'",
                "[default]",
                "aws_access_key_id = " + accessKey,
                "aws_secret_access_key = " + secretKey,
                "AWSEOF",
                ""
        );

        System.out.println("[PXFApplication] Configuring PXF S3 servers (endpoint=" + endpoint + ")...");
        ExecResult result = container.execInContainer("bash", "-l", "-c", script);
        if (result.getExitCode() != 0) {
            throw new RuntimeException(
                    "S3 server configuration failed (exit " + result.getExitCode() + "):\n"
                            + result.getStdout() + "\n" + result.getStderr());
        }

        restartPxf();

        System.out.println("[PXFApplication] PXF S3 servers configured and PXF restarted");
    }

    public void restartPxf() throws IOException, InterruptedException {
        String script = String.join("\n",
                "set -e",
                "source " + SCRIPTS_PREFIX + "/pxf-env.sh",
                "$PXF_HOME/bin/pxf restart"
        );
        ExecResult result = container.execInContainer("bash", "-l", "-c", script);
        if (result.getExitCode() != 0) {
            throw new RuntimeException(
                    "PXF restart failed (exit " + result.getExitCode() + "):\n"
                            + result.getStdout() + "\n" + result.getStderr());
        }
        System.out.println("[PXFApplication] PXF restarted");
    }
}
