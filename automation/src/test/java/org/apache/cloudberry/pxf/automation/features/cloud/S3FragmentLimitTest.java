package org.apache.cloudberry.pxf.automation.features.cloud;

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

import org.apache.cloudberry.pxf.automation.AbstractTestcontainersTest;
import org.apache.cloudberry.pxf.automation.applications.S3Application;
import org.apache.cloudberry.pxf.automation.structures.tables.pxf.ReadableExternalTable;
import org.apache.cloudberry.pxf.automation.testcontainers.MinIOContainer;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * End-to-end test for the per-table {@code FILES_LIMIT} option (backed
 * by the {@code pxf.fs.fragmenter.files.limit} property): a query over
 * an S3 prefix that matches more files than the limit must fail with a clear
 * error instead of risking an out-of-memory while listing.
 */
public class S3FragmentLimitTest extends AbstractTestcontainersTest {

    private static final String[] SINGLE_INT_COLUMN = {"n int"};

    private MinIOContainer s3Server;
    private S3Application s3Application;
    private String bucket;
    private String prefix;
    private String wildcardLocation;

    @Override
    public void beforeClass() throws Exception {
        s3Server = new MinIOContainer(container.getSharedNetwork());
        s3Server.start();
        s3Application = new S3Application(s3Server);

        bucket = MinIOContainer.DEFAULT_BUCKET;
        s3Application.createBucket(bucket);

        prefix = "fragment-limit/" + UUID.randomUUID() + "/";
        uploadLine("a.txt", "10");
        uploadLine("b.txt", "20");
        wildcardLocation = "/" + bucket + "/" + prefix + "*.txt";
    }

    @Override
    public void afterClass() throws Exception {
        if (s3Application != null) {
            if (prefix != null) {
                s3Application.deletePrefix(bucket, prefix);
            }
            s3Application.shutdown();
        }
        if (s3Server != null) {
            s3Server.stop();
        }
    }

    @Test(groups = {"testcontainers", "pxf-s3"})
    public void testReadFailsWhenFilesExceedLimit() throws Exception {
        ReadableExternalTable table = externalTable("fragment_limit_exceeded", "FILES_LIMIT=1");
        cloudberry.createTableAndVerify(table);

        try {
            cloudberry.runQuery("SELECT * FROM " + table.getName());
            Assert.fail("Query should have failed because the prefix matches more files than the limit");
        } catch (Exception e) {
            Assert.assertTrue(e.getMessage().contains("exceeds the configured limit"),
                    "Unexpected error message: " + e.getMessage());
        }
    }

    @Test(groups = {"testcontainers", "pxf-s3"})
    public void testReadSucceedsWhenWithinLimit() throws Exception {
        ReadableExternalTable table = externalTable("fragment_limit_within", "FILES_LIMIT=10");
        cloudberry.createTableAndVerify(table);

        // The two files are within the limit; the streaming listing must return
        // them without error.
        cloudberry.runQuery("SELECT count(*) FROM " + table.getName());
    }

    private ReadableExternalTable externalTable(String name, String... userParameters) {
        ReadableExternalTable table = new ReadableExternalTable(name, SINGLE_INT_COLUMN, wildcardLocation, "CSV");
        table.setProfile("s3:csv");
        table.setServer("server=s3");
        table.setHost(pxfHost);
        table.setPort(pxfPort);
        table.setUserParameters(userParameters);
        return table;
    }

    private void uploadLine(String filename, String content) throws Exception {
        Path tempFile = Files.createTempFile("pxf-fragment-limit-", ".txt");
        try {
            Files.write(tempFile, (content + "\n").getBytes());
            s3Application.putObject(bucket, prefix + filename, tempFile);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
