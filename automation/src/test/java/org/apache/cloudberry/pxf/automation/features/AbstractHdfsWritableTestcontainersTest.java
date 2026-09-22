/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.cloudberry.pxf.automation.features;

import org.apache.cloudberry.pxf.automation.structures.tables.pxf.ReadableExternalTable;
import org.apache.cloudberry.pxf.automation.structures.tables.pxf.WritableExternalTable;
import org.apache.cloudberry.pxf.automation.utils.system.ProtocolUtils;

/** Writable-table state and cleanup for pxf-hdfs Testcontainers tests. */
public abstract class AbstractHdfsWritableTestcontainersTest extends AbstractHdfsTestcontainersTest {
    protected WritableExternalTable writableExTable;
    protected ReadableExternalTable readableExTable;
    protected String hdfsWritePath;
    protected String writableTableName = "writable_table";
    protected String readableTableName = "readable_table";

    @Override
    protected void initializeMethodEnvironment() throws Exception {
        super.initializeMethodEnvironment();
        if (hdfs != null && hdfsWritePath != null && !hdfs.doesFileExist(hdfsWritePath)) {
            hdfs.createDirectory(hdfsWritePath);
        }
    }

    @Override
    protected void cleanupMethodEnvironment() throws Exception {
        if (hdfs != null && !"true".equalsIgnoreCase(ProtocolUtils.getPxfTestKeepData()) && hdfsWritePath != null) {
            hdfs.removeDirectory(hdfsWritePath);
        }
    }
}
