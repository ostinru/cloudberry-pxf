package org.apache.cloudberry.pxf.automation.applications;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.cloudberry.pxf.automation.testcontainers.PXFCloudberryContainer;
import org.apache.cloudberry.pxf.automation.testcontainers.SingleClusterContainer;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.utility.MountableFile;

import java.io.File;
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

    /** Configures PXF to use the separate Hadoop/Hive container over the shared Docker network. */
    public void configureSingleCluster(SingleClusterContainer singleCluster)
            throws IOException, InterruptedException {
        String coreSite = String.join("\n",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<configuration>",
                "  <property><name>fs.defaultFS</name><value>" + singleCluster.getInternalHdfsUri() + "</value></property>",
                "  <property><name>ipc.client.fallback-to-simple-auth-allowed</name><value>true</value></property>",
                "</configuration>");
        String hdfsSite = String.join("\n",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<configuration>",
                "  <property><name>dfs.permissions.enabled</name><value>true</value></property>",
                "  <property><name>dfs.client.use.datanode.hostname</name><value>false</value></property>",
                "</configuration>");
        String hiveSite = String.join("\n",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                "<configuration>",
                "  <property><name>hive.metastore.uris</name><value>thrift://"
                        + SingleClusterContainer.NETWORK_ALIAS + ":" + SingleClusterContainer.METASTORE_PORT
                        + "</value></property>",
                "</configuration>");

        String script = String.join("\n",
                "set -e",
                "source " + SCRIPTS_PREFIX + "/pxf-env.sh",
                "for server in default default-no-impersonation; do",
                "  mkdir -p \"${PXF_BASE}/servers/${server}\"",
                "  cat > \"${PXF_BASE}/servers/${server}/core-site.xml\" <<'CORE_XML'",
                coreSite,
                "CORE_XML",
                "  cat > \"${PXF_BASE}/servers/${server}/hdfs-site.xml\" <<'HDFS_XML'",
                hdfsSite,
                "HDFS_XML",
                "  cat > \"${PXF_BASE}/servers/${server}/hive-site.xml\" <<'HIVE_XML'",
                hiveSite,
                "HIVE_XML",
                "done",
                "if [ -f \"${PXF_BASE}/servers/db-hive/jdbc-site.xml\" ]; then",
                "  sed -i 's#jdbc:hive2://localhost:10000/default#"
                        + singleCluster.getInternalHiveJdbcUrl() + "#' \"${PXF_BASE}/servers/db-hive/jdbc-site.xml\"",
                "fi");
        assertSuccess(container.execInContainer("bash", "-l", "-c", script),
                "SingleCluster PXF configuration");
        restartPxf();
    }

    public void copyFile(String source, String targetDirectory)
            throws IOException, InterruptedException {
        assertSuccess(container.execInContainer("mkdir", "-p", targetDirectory),
                "creating PXF target directory " + targetDirectory);
        String target = targetDirectory + "/" + new File(source).getName();
        container.copyFileToContainer(MountableFile.forHostPath(source), target);
    }

    public void addPathToPxfClassPath(String path) throws IOException, InterruptedException {
        String script = String.join("\n",
                "set -e",
                "source " + SCRIPTS_PREFIX + "/pxf-env.sh",
                "setting='export PXF_LOADER_PATH=file:" + path + "'",
                "grep -Fqx \"${setting}\" \"${PXF_BASE}/conf/pxf-env.sh\" || echo \"${setting}\" >> \"${PXF_BASE}/conf/pxf-env.sh\"");
        assertSuccess(container.execInContainer("bash", "-l", "-c", script),
                "updating PXF loader path");
    }

    public String getPxfConfLocation() {
        return "/home/gpadmin/pxf-base/conf";
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
        assertSuccess(result, "JDBC server configuration");

        restartPxf();

        System.out.println("[PXFApplication] JDBC servers configured and PXF restarted");
    }

    public void restartPxf() throws IOException, InterruptedException {
        String script = String.join("\n",
                "set -e",
                "source " + SCRIPTS_PREFIX + "/pxf-env.sh",
                "$PXF_HOME/bin/pxf restart"
        );
        ExecResult result = container.execInContainer("bash", "-l", "-c", script);
        assertSuccess(result, "PXF restart");
        System.out.println("[PXFApplication] PXF restarted");
    }

    private static void assertSuccess(ExecResult result, String operation) {
        if (result.getExitCode() != 0) {
            throw new RuntimeException(operation + " failed (exit " + result.getExitCode() + "):\n"
                    + result.getStdout() + "\n" + result.getStderr());
        }
    }
}
