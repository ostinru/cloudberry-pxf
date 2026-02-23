package org.apache.cloudberry.pxf.automation.testcontainers;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * TestContainers wrapper around the {@code pxf/cbdb-dev:1} Docker image.
 * Exposes Cloudberry DB (port 7000) and PXF (port 5888), runs the full
 * entrypoint that installs Cloudberry, builds PXF, and starts Hadoop/Hive/HBase.
 * <p>
 * Use {@link #getInstance()} to obtain a singleton that is started once per JVM.
 * The container shares a Docker {@link Network} with other test containers
 * (e.g. {@link MinIOContainer}) so they can communicate by hostname.
 */
public class PXFCBDBContainer extends GenericContainer<PXFCBDBContainer> {

    private static final String IMAGE_NAME = "pxf/cbdb-dev:1";
    private static final String SINGLECLUSTER_IMAGE = "pxf/singlecluster:3";
    public static final int CBDB_PORT = 7000;
    public static final int PXF_PORT = 5888;
    public static final String CBDB_USER = "gpadmin";

    private static final Network network = Network.newNetwork();
    private static PXFCBDBContainer instance;

    private final String repoPath;
    private final String cloudberryPath;
    private final String debPath;

    public PXFCBDBContainer(String repoPath, String cloudberryPath, String debPath) {
        super(DockerImageName.parse(IMAGE_NAME));
        this.repoPath = repoPath;
        this.cloudberryPath = cloudberryPath;
        this.debPath = debPath;
        init();
    }

    private void init() {
        withNetwork(network);
        withNetworkAliases("mdw");
        withExposedPorts(CBDB_PORT, PXF_PORT);
        withFileSystemBind(repoPath, "/home/gpadmin/workspace/cloudberry-pxf", BindMode.READ_WRITE);
        withFileSystemBind(cloudberryPath, "/home/gpadmin/workspace/cloudberry", BindMode.READ_WRITE);
        if (debPath != null) {
            withCopyFileToContainer(
                    MountableFile.forHostPath(debPath),
                    "/tmp/" + new File(debPath).getName());
        }
        withCommand("tail", "-f", "/dev/null");
        withCreateContainerCmdModifier(cmd -> cmd.withHostName("mdw"));
        waitingFor(new AbstractWaitStrategy() {
            @Override
            protected void waitUntilReady() {
                // Services (CBDB, PXF) are started via execInContainer after container boots
            }
        });
        withStartupTimeout(Duration.ofMinutes(15));
        withPrivilegedMode(true);
    }

    /**
     * Returns a singleton container, starting it and running the full environment
     * setup on first access. Thread-safe.
     */
    public static synchronized PXFCBDBContainer getInstance() {
        if (instance == null) {
            String repo = resolveProperty("pxf.test.repo.path", findRepoPath());
            String cbdb = resolveProperty("pxf.test.cloudberry.path", findCloudberryPath(repo));
            String deb = resolveProperty("pxf.test.deb.path", findDebPath());

            ensureImageExists(repo);

            instance = new PXFCBDBContainer(repo, cbdb, deb);
            instance.start();
            Runtime.getRuntime().addShutdownHook(new Thread(instance::stop));

            try {
                instance.runEntrypoint();
                instance.createTestDatabases();
                instance.configureJdbcServers();
            } catch (Exception e) {
                instance.stop();
                instance = null;
                throw new RuntimeException("Failed to initialize PXF container", e);
            }
        }
        return instance;
    }

    // ---- environment initialization ----------------------------------------

    private void createTestDatabases() throws IOException, InterruptedException {
        logger().info("Creating test databases...");
        String script = String.join("\n",
                "set -e",
                "source /usr/local/cloudberry-db/cloudberry-env.sh",
                "export COORDINATOR_DATA_DIRECTORY=/home/gpadmin/workspace/cloudberry/gpAux/gpdemo/datadirs/qddir/demoDataDir-1",
                "createdb -p " + CBDB_PORT + " pxfautomation || echo 'pxfautomation may already exist'",
                "createdb -p " + CBDB_PORT + " -T template0 -E UTF8 pxfautomation_encoding || echo 'pxfautomation_encoding may already exist'",
                "psql -p " + CBDB_PORT + " -d pxfautomation -c 'SELECT 1'");
        ExecResult result = execInContainer("bash", "-l", "-c", script);
        System.out.println("[createTestDatabases] stdout: " + result.getStdout());
        System.out.println("[createTestDatabases] stderr: " + result.getStderr());
        if (result.getExitCode() != 0) {
            throw new RuntimeException("Failed to create test databases (exit " + result.getExitCode() + ")");
        }
        logger().info("Test databases created");
    }

    private void runEntrypoint() throws IOException, InterruptedException {
        logger().info("Running entrypoint.sh inside container (this takes several minutes)...");
        int exitCode = execWithLiveOutput(
                "bash", "-l", "-c",
                "cd /home/gpadmin/workspace/cloudberry-pxf/ci/docker/pxf-cbdb-dev/ubuntu "
                        + "&& ./script/entrypoint.sh 2>&1");
        if (exitCode != 0) {
            throw new RuntimeException("entrypoint.sh failed (exit " + exitCode + ")");
        }
        logger().info("entrypoint.sh completed successfully");
    }

    /**
     * Runs a command inside the container, streaming stdout/stderr to
     * {@code System.out} in real time. Returns the process exit code.
     */
    private int execWithLiveOutput(String... command) throws InterruptedException {
        DockerClient client = DockerClientFactory.instance().client();
        ExecCreateCmdResponse exec = client.execCreateCmd(getContainerId())
                .withCmd(command)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

        client.execStartCmd(exec.getId())
                .exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        System.out.print(new String(frame.getPayload(), StandardCharsets.UTF_8));
                    }
                })
                .awaitCompletion();

        Long exitCode = client.inspectExecCmd(exec.getId()).exec().getExitCodeLong();
        return exitCode != null ? exitCode.intValue() : -1;
    }

    /**
     * Replicates the Makefile {@code sync_jdbc_config} target: creates the
     * {@code database} and {@code db-session-params} PXF server directories
     * with the appropriate jdbc-site.xml files, then restarts PXF.
     */
    private void configureJdbcServers() throws IOException, InterruptedException {
        logger().info("Configuring JDBC servers (database, db-session-params)...");

        String script = String.join("\n",
                "set -e",
                "source /home/gpadmin/workspace/cloudberry-pxf/ci/docker/pxf-cbdb-dev/ubuntu/script/pxf-env.sh",
                "PXF_BASE_SERVERS=${PXF_BASE}/servers",
                "TEMPLATES_DIR=${PXF_HOME}/templates",

                // --- database server ---
                "mkdir -p ${PXF_BASE_SERVERS}/database",
                "cp ${TEMPLATES_DIR}/jdbc-site.xml ${PXF_BASE_SERVERS}/database/",
                "sed -i 's|YOUR_DATABASE_JDBC_DRIVER_CLASS_NAME|org.postgresql.Driver|' ${PXF_BASE_SERVERS}/database/jdbc-site.xml",
                "sed -i 's|YOUR_DATABASE_JDBC_URL|jdbc:postgresql://localhost:7000/pxfautomation|' ${PXF_BASE_SERVERS}/database/jdbc-site.xml",
                "sed -i 's|YOUR_DATABASE_JDBC_USER||' ${PXF_BASE_SERVERS}/database/jdbc-site.xml",
                "sed -i 's|YOUR_DATABASE_JDBC_PASSWORD||' ${PXF_BASE_SERVERS}/database/jdbc-site.xml",
                "cp ${PXF_BASE_SERVERS}/database/jdbc-site.xml ${PXF_BASE_SERVERS}/database/testuser-user.xml",
                "sed -i 's|pxfautomation|template1|' ${PXF_BASE_SERVERS}/database/testuser-user.xml",
                "cp /home/gpadmin/workspace/cloudberry-pxf/automation/src/test/resources/report.sql ${PXF_BASE_SERVERS}/database/",

                // --- db-session-params server ---
                "mkdir -p ${PXF_BASE_SERVERS}/db-session-params",
                "cp ${TEMPLATES_DIR}/jdbc-site.xml ${PXF_BASE_SERVERS}/db-session-params/",
                "sed -i 's|YOUR_DATABASE_JDBC_DRIVER_CLASS_NAME|org.postgresql.Driver|' ${PXF_BASE_SERVERS}/db-session-params/jdbc-site.xml",
                "sed -i 's|YOUR_DATABASE_JDBC_URL|jdbc:postgresql://localhost:7000/pxfautomation|' ${PXF_BASE_SERVERS}/db-session-params/jdbc-site.xml",
                "sed -i 's|YOUR_DATABASE_JDBC_USER||' ${PXF_BASE_SERVERS}/db-session-params/jdbc-site.xml",
                "sed -i 's|YOUR_DATABASE_JDBC_PASSWORD||' ${PXF_BASE_SERVERS}/db-session-params/jdbc-site.xml",
                "sed -i 's|</configuration>|<property><name>jdbc.session.property.client_min_messages</name><value>debug1</value></property></configuration>|' ${PXF_BASE_SERVERS}/db-session-params/jdbc-site.xml",
                "sed -i 's|</configuration>|<property><name>jdbc.session.property.default_statistics_target</name><value>123</value></property></configuration>|' ${PXF_BASE_SERVERS}/db-session-params/jdbc-site.xml",

                // Restart PXF to pick up the new server configurations
                "$PXF_HOME/bin/pxf restart"
        );

        ExecResult result = execInContainer("bash", "-l", "-c", script);
        if (result.getExitCode() != 0) {
            throw new RuntimeException(
                    "JDBC server configuration failed (exit " + result.getExitCode() + "):\n"
                            + result.getStdout() + "\n" + result.getStderr());
        }
        logger().info("JDBC servers configured and PXF restarted");
    }

    /**
     * Configures PXF S3 servers ({@code s3}, {@code s3-invalid}, and {@code default})
     * to point at a MinIO container reachable via the shared Docker network.
     * Removes HDFS/Hive/HBase configs from the default server so that S3 tests
     * that rely on the default server path work correctly, then restarts PXF.
     */
    public void configureS3Servers(MinIOContainer minio) throws IOException, InterruptedException {
        String endpoint = minio.getInternalEndpoint();
        String accessKey = minio.getAccessKey();
        String secretKey = minio.getSecretKey();

        String script = String.join("\n",
                "set -e",
                "source /home/gpadmin/workspace/cloudberry-pxf/ci/docker/pxf-cbdb-dev/ubuntu/script/pxf-env.sh",
                "PXF_BASE_SERVERS=${PXF_BASE}/servers",
                "",
                "# --- s3 server ---",
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
                "# --- default server (S3 config) ---",
                "cp ${PXF_BASE_SERVERS}/s3/s3-site.xml ${PXF_BASE_SERVERS}/default/s3-site.xml",
                "cp ${PXF_BASE_SERVERS}/s3/core-site.xml ${PXF_BASE_SERVERS}/default/core-site.xml",
                "",
                "# --- s3-invalid server (bogus credentials) ---",
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
                "# --- AWS credentials file ---",
                "mkdir -p /home/gpadmin/.aws",
                "cat > /home/gpadmin/.aws/credentials <<'AWSEOF'",
                "[default]",
                "aws_access_key_id = " + accessKey,
                "aws_secret_access_key = " + secretKey,
                "AWSEOF",
                "",
                "# Remove HDFS/Hive/HBase configs from default so it is treated as S3-only",
                "for f in hdfs-site.xml mapred-site.xml yarn-site.xml hive-site.xml hbase-site.xml; do",
                "  [ -f \"${PXF_BASE_SERVERS}/default/${f}\" ] && rm -f \"${PXF_BASE_SERVERS}/default/${f}\"",
                "done",
                "",
                "$PXF_HOME/bin/pxf restart"
        );

        logger().info("Configuring PXF S3 servers (endpoint={})...", endpoint);
        ExecResult result = execInContainer("bash", "-l", "-c", script);
        if (result.getExitCode() != 0) {
            throw new RuntimeException(
                    "S3 server configuration failed (exit " + result.getExitCode() + "):\n"
                            + result.getStdout() + "\n" + result.getStderr());
        }
        logger().info("PXF S3 servers configured and PXF restarted");
    }

    /**
     * Writes a {@code hbase-site.xml} into PXF's default server directory
     * pointing the ZooKeeper quorum to the HBase container on the shared
     * Docker network, then restarts PXF.
     */
    public void configureHBaseServer(HBaseContainer hbase) throws IOException, InterruptedException {
        String zkQuorum = hbase.getInternalZookeeperQuorum();
        int zkPort = hbase.getInternalZookeeperPort();

        String script = String.join("\n",
                "set -e",
                "source /home/gpadmin/workspace/cloudberry-pxf/ci/docker/pxf-cbdb-dev/ubuntu/script/pxf-env.sh",
                "PXF_BASE_SERVERS=${PXF_BASE}/servers",
                "",
                "cat > ${PXF_BASE_SERVERS}/default/hbase-site.xml <<'HBASEEOF'",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<configuration>",
                "  <property><name>hbase.zookeeper.quorum</name><value>" + zkQuorum + "</value></property>",
                "  <property><name>hbase.zookeeper.property.clientPort</name><value>" + zkPort + "</value></property>",
                "</configuration>",
                "HBASEEOF",
                "",
                "$PXF_HOME/bin/pxf restart"
        );

        logger().info("Configuring PXF HBase server (ZK={}:{})...", zkQuorum, zkPort);
        ExecResult result = execInContainer("bash", "-l", "-c", script);
        if (result.getExitCode() != 0) {
            throw new RuntimeException(
                    "HBase server configuration failed (exit " + result.getExitCode() + "):\n"
                            + result.getStdout() + "\n" + result.getStderr());
        }
        logger().info("PXF HBase server configured and PXF restarted");
    }

    // ---- public accessors --------------------------------------------------

    /** The shared Docker network used by all test containers. */
    public static Network getSharedNetwork() {
        return network;
    }

    /** JDBC URL reachable from the host (uses the mapped port). */
    public String getCbdbJdbcUrl() {
        return "jdbc:postgresql://localhost:" + getMappedPort(CBDB_PORT) + "/pxfautomation";
    }

    /** JDBC URL as seen from inside the container. */
    public String getCbdbInternalJdbcUrl() {
        return "jdbc:postgresql://localhost:" + CBDB_PORT + "/pxfautomation";
    }

    public int getCbdbMappedPort() {
        return getMappedPort(CBDB_PORT);
    }

    /** Default database user inside the container. */
    public String getCbdbUser() {
        return CBDB_USER;
    }

    /** PXF host as seen from inside the container (for external table DDL). */
    public String getPxfInternalHost() {
        return "localhost";
    }

    /** PXF port as seen from inside the container. */
    public int getPxfInternalPort() {
        return PXF_PORT;
    }

    // ---- Docker image auto-build --------------------------------------------

    /**
     * Always runs {@code docker build} for all images so that any Dockerfile
     * or script changes are picked up. Docker layer cache makes this near-instant
     * when nothing has changed.
     */
    private static void ensureImageExists(String repoPath) {
        dockerBuild(new File(repoPath, "ci/singlecluster"), SINGLECLUSTER_IMAGE);
        dockerBuild(new File(repoPath, "ci/docker/pxf-cbdb-dev/ubuntu"), IMAGE_NAME);
    }

    private static void dockerBuild(File contextDir, String tag) {
        System.out.println("=== docker build -t " + tag + " " + contextDir + " ===");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "build",
                    "--cache-from", tag,
                    "-t", tag, ".")
                    .directory(contextDir)
                    .redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException(
                        "docker build failed for '" + tag + "' (exit " + exitCode + "). "
                                + "Context dir: " + contextDir.getAbsolutePath());
            }
            System.out.println("=== Image '" + tag + "' built successfully ===");
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to build Docker image '" + tag + "'", e);
        }
    }

    // ---- path resolution helpers -------------------------------------------

    private static String resolveProperty(String key, String fallback) {
        String value = System.getProperty(key);
        return (value != null && !value.isEmpty()) ? value : fallback;
    }

    private static String findRepoPath() {
        File dir = new File(System.getProperty("user.dir"));
        for (int i = 0; i < 5; i++) {
            if (new File(dir, "automation/build.gradle").exists()) {
                return dir.getAbsolutePath();
            }
            if (new File(dir, "build.gradle").exists() && new File(dir, "pxf_regress").exists()) {
                return dir.getParentFile().getAbsolutePath();
            }
            dir = dir.getParentFile();
            if (dir == null) break;
        }
        throw new IllegalStateException(
                "Cannot auto-detect cloudberry-pxf repo root. Set -Dpxf.test.repo.path=...");
    }

    private static String findCloudberryPath(String repoPath) {
        File sibling = new File(new File(repoPath).getParentFile(), "cloudberry");
        if (sibling.isDirectory()) {
            return sibling.getAbsolutePath();
        }
        throw new IllegalStateException(
                "Cannot find cloudberry source at " + sibling.getAbsolutePath()
                        + ". Set -Dpxf.test.cloudberry.path=...");
    }

    private static String findDebPath() {
        File tmpDir = new File("/tmp");
        File[] debs = tmpDir.listFiles((d, name) -> name.startsWith("apache-cloudberry-db") && name.endsWith(".deb"));
        if (debs != null && debs.length > 0) {
            return debs[0].getAbsolutePath();
        }
        return null;
    }
}
