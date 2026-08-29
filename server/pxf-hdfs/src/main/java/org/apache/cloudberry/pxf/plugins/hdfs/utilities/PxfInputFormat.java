package org.apache.cloudberry.pxf.plugins.hdfs.utilities;

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

import org.apache.cloudberry.pxf.plugins.hdfs.HcfsType;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.GlobFilter;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.CompressionCodecFactory;
import org.apache.hadoop.io.compress.SplittableCompressionCodec;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.InvalidInputException;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reporter;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PxfInputFormat is not intended to read a specific format, hence it implements
 * a dummy getRecordReader Instead, its purpose is to apply
 * FileInputFormat.getSplits from one point in PXF and get the splits which are
 * valid for the actual InputFormats, since all of them we use inherit
 * FileInputFormat but do not override getSplits.
 */
public class PxfInputFormat extends FileInputFormat {

    /**
     * Limits the number of files a single fragmenter request will resolve. On
     * object stores a wildcard or a directory may match an unbounded number of
     * objects, and {@link FileSystem#globStatus} materializes them all into one
     * array, which can exhaust the heap. A non-positive value disables the limit.
     */
    public static final String FILES_LIMIT_PROPERTY = "pxf.fs.fragmenter.files.limit";
    public static final String FILES_LIMIT_OPTION = "FILES_LIMIT";
    public static final int DEFAULT_FILES_LIMIT = 10_000_000;

    private static final PathFilter HIDDEN_FILE_FILTER = path -> {
        String name = path.getName();
        return !name.startsWith("_") && !name.startsWith(".");
    };

    @Override
    public RecordReader getRecordReader(InputSplit split,
                                        JobConf conf,
                                        Reporter reporter) {
        throw new UnsupportedOperationException("PxfInputFormat should not be used for reading data, but only for obtaining the splits of a file");
    }

    @Override
    public FileStatus[] listStatus(JobConf job) throws IOException {
        int limit = job.getInt(FILES_LIMIT_PROPERTY, DEFAULT_FILES_LIMIT);
        if (limit <= 0 || job.getBoolean(INPUT_DIR_RECURSIVE, false) || !isLimitApplicable(job)) {
            return super.listStatus(job);
        }
        return limitedListStatus(job, limit);
    }

    /**
     * The limited listing streams a single directory level via a
     * {@link RemoteIterator}; it can only honor a glob in the terminal path
     * component on an object store. Anything else is left to the parent's
     * Globber-based implementation.
     */
    protected boolean isLimitApplicable(JobConf job) throws IOException {
        for (Path path : getInputPaths(job)) {
            String scheme = path.toUri().getScheme();
            if (scheme == null || !HcfsType.fromString(scheme.toUpperCase()).isObjectStore()) {
                return false;
            }
            Path parent = path.getParent();
            if (parent != null && new GlobFilter(parent.toString()).hasPattern()) {
                return false;
            }
        }
        return true;
    }

    private FileStatus[] limitedListStatus(JobConf job, int limit) throws IOException {
        Path[] dirs = getInputPaths(job);
        if (dirs.length == 0) {
            throw new IOException("No input paths specified in job");
        }
        PathFilter inputFilter = inputFilter(job);

        List<FileStatus> result = new ArrayList<>();
        List<IOException> errors = new ArrayList<>();
        for (Path path : dirs) {
            collectFiles(result, path.getFileSystem(job), path, inputFilter, limit, errors);
        }
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
        return result.toArray(new FileStatus[0]);
    }

    private void collectFiles(List<FileStatus> result, FileSystem fs, Path path,
                              PathFilter inputFilter, int limit, List<IOException> errors) throws IOException {
        GlobFilter nameGlob = new GlobFilter(path.getName());
        Path baseDir;
        PathFilter glob;
        if (nameGlob.hasPattern()) {
            baseDir = path.getParent();
            glob = nameGlob;
        } else {
            FileStatus status;
            try {
                status = fs.getFileStatus(path);
            } catch (FileNotFoundException e) {
                errors.add(new IOException("Input path does not exist: " + path));
                return;
            }
            if (!status.isDirectory()) {
                if (inputFilter.accept(status.getPath())) {
                    add(result, status, limit);
                }
                return;
            }
            baseDir = path;
            glob = null;
        }

        RemoteIterator<LocatedFileStatus> iterator;
        try {
            iterator = fs.listLocatedStatus(baseDir);
        } catch (FileNotFoundException e) {
            errors.add(new IOException("Input path does not exist: " + path));
            return;
        }
        int matched = 0;
        while (iterator.hasNext()) {
            LocatedFileStatus child = iterator.next();
            if (!inputFilter.accept(child.getPath())) {
                continue;
            }
            if (glob != null && !glob.accept(child.getPath())) {
                continue;
            }
            add(result, child, limit);
            matched++;
        }
        if (matched == 0 && glob != null) {
            errors.add(new IOException("Input Pattern " + path + " matches 0 files"));
        }
    }

    private void add(List<FileStatus> result, FileStatus status, int limit) throws IOException {
        if (result.size() >= limit) {
            throw new IOException(String.format(
                    "The number of files to process exceeds the configured limit of %d. "
                            + "Raise the %s external table option or the %s server property, "
                            + "or narrow the path or wildcard.",
                    limit, FILES_LIMIT_OPTION, FILES_LIMIT_PROPERTY));
        }
        result.add(status);
    }

    private PathFilter inputFilter(JobConf job) {
        PathFilter jobFilter = getInputPathFilter(job);
        if (jobFilter == null) {
            return HIDDEN_FILE_FILTER;
        }
        return path -> HIDDEN_FILE_FILTER.accept(path) && jobFilter.accept(path);
    }

    /**
     * Returns true if the needed codec is splittable. If no codec is needed
     * returns true as well.
     *
     * @param fs       the filesystem
     * @param filename the name of the file to be read
     * @return if the codec needed for reading the specified path is splittable.
     */
    @Override
    protected boolean isSplitable(FileSystem fs, Path filename) {
        CompressionCodecFactory factory = new CompressionCodecFactory(fs.getConf());
        CompressionCodec codec = factory.getCodec(filename);

        return null == codec || codec instanceof SplittableCompressionCodec;
    }
}
