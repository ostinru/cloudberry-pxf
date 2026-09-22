/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.cloudberry.pxf.automation.features;

import org.apache.cloudberry.pxf.automation.AbstractTestcontainersTest;
import org.apache.cloudberry.pxf.automation.applications.CloudberryApplication;
import org.apache.cloudberry.pxf.automation.applications.HdfsApplication;
import org.apache.cloudberry.pxf.automation.applications.HiveApplication;
import org.apache.cloudberry.pxf.automation.applications.PXFApplication;
import org.apache.cloudberry.pxf.automation.structures.tables.basic.Table;
import org.apache.cloudberry.pxf.automation.structures.tables.pxf.ReadableExternalTable;
import org.apache.cloudberry.pxf.automation.structures.tables.pxf.WritableExternalTable;
import org.apache.cloudberry.pxf.automation.structures.tables.utils.TableFactory;
import org.apache.cloudberry.pxf.automation.testcontainers.SingleClusterContainer;
import org.apache.cloudberry.pxf.automation.utils.system.ProtocolUtils;
import org.apache.commons.lang.StringUtils;
import org.postgresql.util.PSQLException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Shared, JSystem-free lifecycle and helpers for pxf-hdfs Testcontainers tests. */
public abstract class AbstractHdfsTestcontainersTest extends AbstractTestcontainersTest {
    protected CloudberryApplication gpdb;
    protected HdfsApplication hdfs;
    protected HiveApplication hive;
    protected PXFApplication pxf;
    protected ReadableExternalTable exTable;
    protected String localDataResourcesFolder = "src/test/resources/data";
    protected String dataTempFolder;
    protected String fileName = "data.txt";

    private SingleClusterContainer singleCluster;

    @Override
    protected final void initializeEnvironment() throws Exception {
        singleCluster = SingleClusterContainer.getInstance(container.getSharedNetwork());
        pxf = new PXFApplication(container);
        pxf.configureSingleCluster(singleCluster);
        hdfs = new HdfsApplication(singleCluster);
        hive = new HiveApplication(singleCluster);
        gpdb = cloudberry;

        dataTempFolder = "target/testcontainers-data/" + getClass().getSimpleName()
                + "-" + UUID.randomUUID();
        Files.createDirectories(Paths.get(dataTempFolder));
        initializeWorkingDirectory();
    }

    @Override
    protected void initializeMethodEnvironment() throws Exception {
        if (hdfs == null) {
            return;
        }
        if (!hdfs.doesFileExist(hdfs.getWorkingDirectory())) {
            initializeWorkingDirectory();
        }
    }

    @Override
    protected final void closeEnvironment() throws Exception {
        Exception failure = null;
        try {
            if (hdfs != null && !"true".equalsIgnoreCase(ProtocolUtils.getPxfTestKeepData())) {
                hdfs.removeDirectory(hdfs.getWorkingDirectory());
                if (dataTempFolder != null) {
                    deleteLocalDirectory(Paths.get(dataTempFolder));
                }
            }
        } catch (Exception e) {
            failure = e;
        }
        try {
            if (hive != null) {
                hive.close();
            }
        } catch (Exception e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        try {
            if (hdfs != null) {
                hdfs.close();
            }
        } catch (Exception e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    protected void runSqlTest(String sqlTestPath) throws Exception {
        try {
            regress.runSqlTest(sqlTestPath);
        } catch (Exception e) {
            throw new Exception("Regress Failure (" + e.getMessage() + ")", e);
        }
    }

    protected void createTable(ReadableExternalTable table) throws Exception {
        table.setHost(pxfHost);
        table.setPort(pxfPort);
        gpdb.createTableAndVerify(table);
    }

    protected ReadableExternalTable getHdfsReadableTable(String name, String[] fields,
                                                          String path, String fileFormat) {
        ReadableExternalTable table = TableFactory.getPxfHcfsReadableTable(
                name, fields, path, "", fileFormat);
        table.setServer(null);
        table.setProfile("hdfs:" + fileFormat);
        return table;
    }

    protected WritableExternalTable getHdfsWritableTable(String name, String[] fields,
                                                          String path, String fileFormat) {
        WritableExternalTable table = TableFactory.getPxfHcfsWritableTable(
                name, fields, path, "", fileFormat);
        table.setServer(null);
        table.setProfile("hdfs:" + fileFormat);
        return table;
    }

    protected Table getSmallData(String uniqueName, int numRows) {
        List<List<String>> data = new ArrayList<>();
        for (int i = 1; i <= numRows; i++) {
            List<String> row = new ArrayList<>();
            row.add(String.format("%s%srow_%d", uniqueName,
                    StringUtils.isBlank(uniqueName) ? "" : "_", i));
            row.add(String.valueOf(i));
            row.add(Double.toString(i));
            row.add(Long.toString(100000000000L * i));
            row.add(String.valueOf(i % 2 == 0));
            data.add(row);
        }
        Table table = new Table("dataTable", null);
        table.setData(data);
        return table;
    }

    protected Table getSmallData(String uniqueName) {
        return getSmallData(uniqueName, 100);
    }

    protected Table getSmallData() throws IOException {
        return getSmallData("");
    }

    public interface ThrowingConsumer {
        void accept() throws Exception;
    }

    protected void attemptInsert(ThrowingConsumer operation, String path, int retryCount)
            throws Exception {
        PSQLException lastTimeout = null;
        for (int attempt = 1; attempt <= retryCount; attempt++) {
            try {
                operation.accept();
                return;
            } catch (PSQLException e) {
                if (!e.getMessage().contains("Operation could not be completed within the specified time")) {
                    throw e;
                }
                lastTimeout = e;
                if (attempt < retryCount) {
                    System.out.printf("Operation timed out, retrying (%d left): %s%n",
                            retryCount - attempt, e.getMessage());
                    hdfs.removeDirectory(path);
                }
            }
        }
        throw lastTimeout;
    }

    private void initializeWorkingDirectory() throws Exception {
        hdfs.removeDirectory(hdfs.getWorkingDirectory());
        hdfs.createDirectory(hdfs.getWorkingDirectory());
        hdfs.setOwner(hdfs.getWorkingDirectory(), gpdb.getUserName(), gpdb.getUserName());
    }

    private static void deleteLocalDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }
}
