/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cloudberry.pxf.automation.testcontainers;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

/**
 * Testcontainers lifecycle wrapper for the Hadoop/Hive services copied from
 * {@code ci/singlecluster}. Domain operations live in Application classes.
 */
public class SingleClusterContainer extends GenericContainer<SingleClusterContainer> {
    public static final String USER = "gpadmin";
    public static final int RPC_PORT = 8020;
    public static final int HTTPFS_PORT = 14000;
    public static final int HIVE_PORT = 10000;
    public static final int METASTORE_PORT = 9083;
    public static final long BLOCK_SIZE = 16L * 1024 * 1024;
    public static final String NETWORK_ALIAS = "singlecluster";
    private static final String IMAGE_REPOSITORY = "pxf/singlecluster-testcontainer";
    private static final String RESOURCE_DIRECTORY = "testcontainers/singlecluster";
    private static SingleClusterContainer instance;
    private static final String[] RESOURCES = {
            "Dockerfile", "entrypoint.sh", "httpfs-site.xml",
            "bin/gphd-env.sh", "bin/hadoop", "bin/hadoop-datanode.sh", "bin/hdfs",
            "bin/hive", "bin/hive-service.sh", "bin/init-gphd.sh", "bin/restart-gphd.sh",
            "bin/start-gphd.sh", "bin/start-hdfs.sh", "bin/start-hive.sh",
            "bin/start-yarn.sh", "bin/stop-gphd.sh", "bin/stop-hdfs.sh",
            "bin/stop-hive.sh", "bin/stop-yarn.sh", "bin/yarn-nodemanager.sh",
            "conf/gphd-conf.sh",
            "templates/hadoop/etc/hadoop/core-site.xml",
            "templates/hadoop/etc/hadoop/hadoop-env.sh",
            "templates/hadoop/etc/hadoop/hdfs-site.xml",
            "templates/hadoop/etc/hadoop/mapred-site.xml",
            "templates/hadoop/etc/hadoop/yarn-env.sh",
            "templates/hadoop/etc/hadoop/yarn-site.xml",
            "templates/hive/conf/hive-env.sh", "templates/hive/conf/hive-site.xml",
            "templates/tez/conf/tez-site.xml"
    };

    public SingleClusterContainer(Network network) {
        super(DockerImageName.parse(image()));
        withNetwork(network)
                .withNetworkAliases(NETWORK_ALIAS)
                .withCreateContainerCmdModifier(command -> command.withHostName(NETWORK_ALIAS))
                .withExposedPorts(HTTPFS_PORT, HIVE_PORT)
                .withLogConsumer(frame -> System.out.print(frame.getUtf8String()))
                .waitingFor(org.testcontainers.containers.wait.strategy.Wait
                        .forLogMessage(".*SingleCluster is ready: HDFS, HttpFS, YARN, Tez and Hive.*\\n", 1))
                .withStartupTimeout(Duration.ofMinutes(8));
    }

    public static synchronized SingleClusterContainer getInstance(Network network) {
        if (instance == null) {
            instance = new SingleClusterContainer(network);
            try {
                instance.start();
                Runtime.getRuntime().addShutdownHook(new Thread(instance::stop));
            } catch (RuntimeException e) {
                instance.stop();
                instance = null;
                throw e;
            }
        }
        return instance;
    }

    private static String image() {
        String image = IMAGE_REPOSITORY + ":3.1.2-" + resourceHash();
        ClasspathDockerContainerBuilder.ensureImageExists(image, RESOURCE_DIRECTORY, RESOURCES);
        return image;
    }

    private static String resourceHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ClassLoader classLoader = SingleClusterContainer.class.getClassLoader();
            byte[] buffer = new byte[8192];
            for (String resource : RESOURCES) {
                digest.update(resource.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                try (InputStream input = classLoader.getResourceAsStream(RESOURCE_DIRECTORY + "/" + resource)) {
                    if (input == null) {
                        throw new IllegalStateException("Classpath resource not found: " + resource);
                    }
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            byte[] bytes = digest.digest();
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", bytes[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("Cannot fingerprint SingleCluster image resources", e);
        }
    }

    /** Native HDFS endpoint for PXF on the shared Docker network. */
    public String getInternalHdfsUri() {
        return "hdfs://" + NETWORK_ALIAS + ":" + RPC_PORT;
    }

    /** Gateway endpoint for the test JVM, including remote Docker hosts. */
    public String getHostHttpFsUri() {
        return "webhdfs://" + getHost() + ":" + getMappedPort(HTTPFS_PORT);
    }

    public String getHostHttpFsUrl() {
        return "http://" + getHost() + ":" + getMappedPort(HTTPFS_PORT) + "/webhdfs/v1";
    }

    public String getInternalHiveJdbcUrl() {
        return "jdbc:hive2://" + NETWORK_ALIAS + ":" + HIVE_PORT + "/default;auth=noSasl";
    }

    public String getHostHiveJdbcUrl() {
        return "jdbc:hive2://" + getHost() + ":" + getMappedPort(HIVE_PORT) + "/default;auth=noSasl";
    }
}
