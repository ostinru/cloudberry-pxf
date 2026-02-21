package org.apache.cloudberry.pxf.automation.testcontainers;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * TestContainers wrapper around the {@code pxf/singlecluster:3} Docker image.
 * Exposes Cloudberry DB (port 7000) and PXF (port 5888), runs the full
 * entrypoint that installs Cloudberry, builds PXF, and starts Hadoop/Hive/HBase/MinIO.
 * <p>
 * Use {@link #getInstance()} to obtain a singleton that is started once per JVM.
 */
public class PXFCBDBContainer extends GenericContainer<PXFCBDBContainer> {

    private static final String IMAGE_NAME = "pxf/singlecluster:3";
    public static final int CBDB_PORT = 7000;
    public static final int PXF_PORT = 5888;
    public static final String CBDB_USER = "gpadmin";

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

            instance = new PXFCBDBContainer(repo, cbdb, deb);
            instance.start();
            Runtime.getRuntime().addShutdownHook(new Thread(instance::stop));

            try {
                instance.runEntrypoint();
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

    // ---- public accessors --------------------------------------------------

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
