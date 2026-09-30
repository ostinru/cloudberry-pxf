package org.apache.cloudberry.pxf.automation.testcontainers;

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

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * TestContainers wrapper around RustFS for S3 / S3 Select automation tests.
 * The container joins a shared Docker network with alias rustfs, so PXF inside the
 * Cloudberry container can reach it at http://rustfs:9000.
 *
 * This class only manages the container lifecycle and exposes endpoint /
 * credential accessors. S3 API access (buckets, objects) lives in
 * {@link org.apache.cloudberry.pxf.automation.applications.S3Application}.
 */
public class RustFSContainer extends GenericContainer<RustFSContainer> {

    private static final String DEFAULT_IMAGE = "rustfs/rustfs:1.0.0@sha256:8cc9801755448b71a786705ce76692c77e14936cccd87cf2fc31842e58f4d1ff";
    private static final String NETWORK_ALIAS = "rustfs";

    public static final int API_PORT = 9000;

    public static final String ACCESS_KEY = "admin";
    public static final String SECRET_KEY = "password";
    public static final String DEFAULT_BUCKET = "gpdb-ud-scratch";

    public RustFSContainer(Network network) {
        super(DockerImageName.parse(DEFAULT_IMAGE));

        withNetwork(network)
                .withNetworkAliases(NETWORK_ALIAS)
                .withExposedPorts(API_PORT)
                .withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY)
                .withEnv("RUSTFS_SECRET_KEY", SECRET_KEY)
                .withEnv("RUSTFS_ADDRESS", ":" + API_PORT)
                .withEnv("RUSTFS_CONSOLE_ENABLE", "false")
                .withCommand("/data")
                .waitingFor(Wait.forHttp("/health/ready").forPort(API_PORT));
    }

    /** S3 API endpoint reachable from the test JVM (mapped port). */
    public String getHostEndpoint() {
        return "http://localhost:" + getMappedPort(API_PORT);
    }

    /** S3 API endpoint for PXF and other containers on the same Docker network. */
    public String getInternalEndpoint() {
        return "http://" + NETWORK_ALIAS + ":" + API_PORT;
    }

    public String getAccessKey() {
        return ACCESS_KEY;
    }

    public String getSecretKey() {
        return SECRET_KEY;
    }
}
