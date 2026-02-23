package org.apache.cloudberry.pxf.automation.testcontainers;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.io.File;
import java.time.Duration;

/**
 * TestContainers wrapper around a standalone HBase 2.3.7 instance.
 * Runs in standalone mode (embedded ZooKeeper, local FS — no HDFS needed).
 * <p>
 * The container joins a shared Docker network with alias {@code hbase},
 * so PXF inside the CBDB container can reach it at {@code hbase:2181}.
 * Test code on the host reaches it via the mapped ZooKeeper port.
 */
public class HBaseContainer extends GenericContainer<HBaseContainer> {

    private static final String IMAGE_NAME = "pxf/hbase:1";
    public static final int ZK_PORT = 2181;
    public static final int MASTER_PORT = 16000;
    public static final int REGIONSERVER_PORT = 16020;
    public static final String NETWORK_ALIAS = "hbase";

    private static HBaseContainer instance;

    public HBaseContainer(Network network) {
        super(IMAGE_NAME);
        withNetwork(network);
        withNetworkAliases(NETWORK_ALIAS);
        withExposedPorts(ZK_PORT, MASTER_PORT, REGIONSERVER_PORT);
        waitingFor(
                Wait.forListeningPort()
                        .withStartupTimeout(Duration.ofMinutes(3)));
    }

    /**
     * ZooKeeper connect string reachable from the host (uses the mapped port).
     */
    public String getZookeeperConnectString() {
        return "localhost:" + getMappedPort(ZK_PORT);
    }

    /**
     * ZooKeeper connect string reachable from other containers on the shared
     * Docker network (e.g. PXF inside CBDB container).
     */
    public String getInternalZookeeperQuorum() {
        return NETWORK_ALIAS;
    }

    public int getInternalZookeeperPort() {
        return ZK_PORT;
    }

    /**
     * Copies a JAR file into the HBase lib directory inside the container.
     * Use this to add pxf-hbase.jar for filter comparator classes.
     */
    public void copyJarToLib(File jarFile) {
        copyFileToContainer(MountableFile.forHostPath(jarFile.getAbsolutePath()),
                "/opt/hbase/lib/" + jarFile.getName());
    }

    /**
     * Returns a singleton container, starting it on first access.
     *
     * @param network shared Docker network (must be the same network the PXF container uses)
     */
    public static synchronized HBaseContainer getInstance(Network network) {
        if (instance == null) {
            ensureImageExists();
            instance = new HBaseContainer(network);
            instance.start();
            Runtime.getRuntime().addShutdownHook(new Thread(instance::stop));
            System.out.println("[HBaseContainer] Started — ZK at "
                    + instance.getZookeeperConnectString()
                    + " (host) / " + NETWORK_ALIAS + ":" + ZK_PORT + " (internal)");
        }
        return instance;
    }

    private static void ensureImageExists() {
        String repoRoot = System.getProperty("pxf.test.repo.path");
        if (repoRoot == null) {
            repoRoot = findRepoPath();
        }
        File contextDir = new File(repoRoot, "ci/docker/hbase");
        if (!contextDir.isDirectory()) {
            throw new IllegalStateException(
                    "HBase Docker context not found at " + contextDir.getAbsolutePath());
        }
        dockerBuild(contextDir, IMAGE_NAME);
    }

    private static void dockerBuild(File contextDir, String tag) {
        try {
            System.out.println("[HBaseContainer] Building image " + tag
                    + " from " + contextDir.getAbsolutePath() + " ...");
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "build", "--cache-from", tag, "-t", tag, ".")
                    .directory(contextDir)
                    .redirectErrorStream(true);
            Process process = pb.start();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[docker build] " + line);
                }
            }
            int exit = process.waitFor();
            if (exit != 0) {
                throw new RuntimeException(
                        "docker build failed for '" + tag + "' (exit " + exit + ")");
            }
            System.out.println("[HBaseContainer] Image " + tag + " built successfully");
        } catch (Exception e) {
            throw new RuntimeException("Failed to build HBase Docker image", e);
        }
    }

    private static String findRepoPath() {
        File dir = new File(System.getProperty("user.dir"));
        while (dir != null) {
            if (new File(dir, "server").isDirectory()
                    && new File(dir, "automation").isDirectory()
                    && new File(dir, "ci").isDirectory()) {
                return dir.getAbsolutePath();
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException(
                "Cannot find cloudberry-pxf repo root. Set -Dpxf.test.repo.path");
    }
}
