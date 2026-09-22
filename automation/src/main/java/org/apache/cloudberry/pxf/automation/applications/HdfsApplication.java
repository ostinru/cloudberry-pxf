/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.cloudberry.pxf.automation.applications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.Schema;
import org.apache.avro.file.CodecFactory;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.tool.DataFileGetMetaTool;
import org.apache.avro.tool.DataFileReadTool;
import org.apache.avro.tool.DataFileWriteTool;
import org.apache.avro.tool.Tool;
import org.apache.cloudberry.pxf.automation.fileformats.IAvroSchema;
import org.apache.cloudberry.pxf.automation.structures.tables.basic.Table;
import org.apache.cloudberry.pxf.automation.testcontainers.SingleClusterContainer;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.SequenceFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** HDFS fixture and result operations performed through the mapped HttpFS port. */
public class HdfsApplication implements AutoCloseable {
    private static final int BUFFER_SIZE = 8192;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SingleClusterContainer container;
    private final Configuration configuration;
    private final String workingDirectory;

    public HdfsApplication(SingleClusterContainer container) {
        this.container = container;
        // Hadoop's data-format classes still need a Configuration. HDFS access deliberately
        // uses the HttpFS REST API: old Hadoop UGI clients do not run on current host JDKs.
        configuration = new Configuration(false);
        configuration.setInt("io.file.buffer.size", BUFFER_SIZE);
        workingDirectory = "tmp/pxf_automation_data/" + UUID.randomUUID();
    }

    private String dataPath(String path) {
        if (path.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*$")) {
            path = URI.create(path).getPath();
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    public ArrayList<String> list(String path) throws Exception {
        ArrayList<String> result = new ArrayList<>();
        listRecursively(dataPath(path), result);
        return result;
    }

    private void listRecursively(String path, List<String> result) throws Exception {
        byte[] response = request("GET", path, parameters("op", "LISTSTATUS"), null, 200);
        JsonNode statuses = OBJECT_MAPPER.readTree(response).path("FileStatuses").path("FileStatus");
        for (JsonNode status : statuses) {
            String child = path.endsWith("/") ? path + status.path("pathSuffix").asText()
                    : path + "/" + status.path("pathSuffix").asText();
            if ("DIRECTORY".equals(status.path("type").asText())) {
                listRecursively(child, result);
            } else {
                result.add(container.getHostHttpFsUri() + child);
            }
        }
    }

    public void copyFromLocal(String source, String destination) throws Exception {
        File file = new File(source);
        createParent(destination);
        try (InputStream input = new FileInputStream(file)) {
            request("PUT", dataPath(destination), parameters("op", "CREATE", "overwrite", "true"),
                    input, 201);
        }
    }

    public void copyToLocal(String source, String destination) throws Exception {
        File destinationFile = new File(destination);
        File parent = destinationFile.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("Could not create local directory " + parent);
        }
        byte[] contents = request("GET", dataPath(source), parameters("op", "OPEN"), null, 200);
        try (OutputStream output = new FileOutputStream(destinationFile)) {
            output.write(contents);
        }
    }

    public void appendFromLocal(String source, String destination) throws Exception {
        File file = new File(source);
        try (InputStream input = new FileInputStream(file)) {
            request("POST", dataPath(destination), parameters("op", "APPEND"), input, 200);
        }
    }

    public void createDirectory(String path) throws Exception {
        request("PUT", dataPath(path), parameters("op", "MKDIRS"), null, 200);
    }

    public void removeDirectory(String path) throws Exception {
        String normalized = dataPath(path);
        if (doesFileExist(normalized)) {
            request("DELETE", normalized, parameters("op", "DELETE", "recursive", "true"), null, 200);
        }
    }

    public void setOwner(String path, String userName, String groupName) throws Exception {
        Map<String, String> parameters = parameters("op", "SETOWNER");
        if (userName != null) {
            parameters.put("owner", userName);
        }
        if (groupName != null) {
            parameters.put("group", groupName);
        }
        request("PUT", dataPath(path), parameters, null, 200);
    }

    public void setPermission(String path, String mode) throws Exception {
        request("PUT", dataPath(path), parameters("op", "SETPERMISSION", "permission", mode),
                null, 200);
    }

    public boolean doesFileExist(String path) throws Exception {
        try {
            request("GET", dataPath(path), parameters("op", "GETFILESTATUS"), null, 200);
            return true;
        } catch (HttpFsException e) {
            if (e.statusCode == 404) {
                return false;
            }
            throw e;
        }
    }

    public void waitForFile(String path, int maxSecondsToWait) throws Exception {
        long deadline = System.currentTimeMillis() + maxSecondsToWait * 1_000L;
        do {
            if (doesFileExist(path)) {
                return;
            }
            Thread.sleep(20L);
        } while (System.currentTimeMillis() < deadline);
        throw new IllegalStateException("HDFS file did not appear within " + maxSecondsToWait
                + " seconds: " + path);
    }

    public void writeTableToFile(String path, Table table, String delimiter) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(output, false, "UTF-8")) {
            for (int rowIndex = 0; rowIndex < table.getData().size(); rowIndex++) {
                List<String> row = table.getData().get(rowIndex);
                stream.print(StringUtils.join(row, delimiter));
                if (rowIndex < table.getData().size() - 1) {
                    stream.print('\n');
                }
            }
        }
        createFile(path, output.toByteArray());
    }

    public String getConfiguredNameNodeAddress() {
        return container.getInternalHdfsUri();
    }

    public void writeAvroInSequenceFile(String pathToFile, String schemaName,
                                        IAvroSchema[] data) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        IntWritable key = new IntWritable();
        try (FSDataOutputStream fsOutput = new FSDataOutputStream(output);
             SequenceFile.Writer writer = SequenceFile.createWriter(configuration,
                     SequenceFile.Writer.stream(fsOutput),
                     SequenceFile.Writer.keyClass(IntWritable.class),
                     SequenceFile.Writer.valueClass(BytesWritable.class))) {
            for (IAvroSchema datum : data) {
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                datum.serialize(stream);
                writer.append(key, new BytesWritable(stream.toByteArray()));
            }
        }
        createFile(pathToFile, output.toByteArray());
    }

    public void writeAvroFile(String pathToFile, String schemaName,
                              String codecName, IAvroSchema[] data) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (FileInputStream schemaInput = new FileInputStream(schemaName)) {
            Schema schema = new Schema.Parser().parse(schemaInput);
            DatumWriter<GenericRecord> datumWriter = new GenericDatumWriter<>(schema);
            try (DataFileWriter<GenericRecord> writer = new DataFileWriter<>(datumWriter)) {
                if (!StringUtils.isEmpty(codecName)) {
                    writer.setCodec(CodecFactory.fromString(codecName));
                }
                writer.create(schema, output);
                for (IAvroSchema datum : data) {
                    writer.append(datum.serialize());
                }
            }
        }
        createFile(pathToFile, output.toByteArray());
    }

    public void writeAvroFileFromJson(String pathToFile, String schemaName,
                                      String jsonFileName, String codecName) throws Exception {
        List<String> arguments = new ArrayList<>(Arrays.asList(
                "--schema-file", schemaName, jsonFileName));
        if (codecName != null) {
            arguments.add("--codec");
            arguments.add(codecName);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream printStream = new PrintStream(output)) {
            runAvroTool(new DataFileWriteTool(), printStream, arguments);
        }
        createFile(pathToFile, output.toByteArray());
    }

    public void writeJsonFileFromAvro(String pathToFile, String pathToJson) throws Exception {
        try (PrintStream output = new PrintStream(new FileOutputStream(new File(pathToJson)))) {
            runAvroTool(new DataFileReadTool(), output, Collections.singletonList(pathToFile));
        }
    }

    public void writeAvroMetadata(String pathToFile, String pathToMetadata) throws Exception {
        try (PrintStream output = new PrintStream(new FileOutputStream(new File(pathToMetadata)))) {
            runAvroTool(new DataFileGetMetaTool(), output, Collections.singletonList(pathToFile));
        }
    }

    private void createFile(String path, byte[] contents) throws Exception {
        createParent(path);
        request("PUT", dataPath(path), parameters("op", "CREATE", "overwrite", "true"),
                new ByteArrayInputStream(contents), 201);
    }

    private void createParent(String path) throws Exception {
        String normalized = dataPath(path);
        int separator = normalized.lastIndexOf('/');
        if (separator > 0) {
            createDirectory(normalized.substring(0, separator));
        }
    }

    private byte[] request(String method, String path, Map<String, String> parameters,
                           InputStream requestBody, int expectedStatus) throws Exception {
        StringBuilder url = new StringBuilder(container.getHostHttpFsUrl())
                .append(new URI(null, null, dataPath(path), null).getRawPath())
                .append("?user.name=").append(encode(SingleClusterContainer.USER));
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            url.append('&').append(encode(parameter.getKey())).append('=').append(encode(parameter.getValue()));
        }

        HttpURLConnection connection = (HttpURLConnection) URI.create(url.toString()).toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(120_000);
        if (requestBody != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            try (OutputStream output = connection.getOutputStream()) {
                copy(requestBody, output);
            }
        }

        int status = connection.getResponseCode();
        InputStream responseStream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        byte[] response = responseStream == null ? new byte[0] : readFully(responseStream);
        connection.disconnect();
        if (status != expectedStatus) {
            throw new HttpFsException(status, method + " " + path + " returned HTTP " + status
                    + ": " + new String(response, StandardCharsets.UTF_8));
        }
        return response;
    }

    private static Map<String, String> parameters(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put(values[i], values[i + 1]);
        }
        return result;
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static byte[] readFully(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(source, output);
            return output.toByteArray();
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    private void runAvroTool(Tool tool, PrintStream output, List<String> arguments) throws Exception {
        int exitCode = tool.run(null, output, System.err, arguments);
        if (exitCode != 0) {
            throw new IllegalStateException(tool.getClass().getSimpleName()
                    + " failed with exit code " + exitCode);
        }
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public String getRelativeWorkingDirectory() {
        return workingDirectory;
    }

    public String getBasePath() {
        return "";
    }

    @Override
    public void close() {
        // HttpURLConnection instances are disconnected after every request.
    }

    private static final class HttpFsException extends IOException {
        private final int statusCode;

        private HttpFsException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }
}
