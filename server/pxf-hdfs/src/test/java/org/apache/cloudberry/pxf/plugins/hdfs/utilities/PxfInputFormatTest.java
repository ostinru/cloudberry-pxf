package org.apache.cloudberry.pxf.plugins.hdfs.utilities;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.InvalidInputException;
import org.apache.hadoop.mapred.JobConf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PxfInputFormatTest {

    @Test
    public void testGetRecordReader() throws IOException {
        Exception e = assertThrows(UnsupportedOperationException.class,
                () -> new PxfInputFormat().getRecordReader(null, null, null));
        assertEquals("PxfInputFormat should not be used for reading data, but only for obtaining the splits of a file", e.getMessage());
    }

    @Test
    public void isSplittableCodec() throws IOException {
        testIsSplittableCodec("no codec - splittable",
                "some/innocent.file", true);
        testIsSplittableCodec("gzip codec - not splittable",
                "/gzip.gz", false);
        testIsSplittableCodec("default codec - not splittable",
                "/default.deflate", false);
        testIsSplittableCodec("bzip2 codec - splittable",
                "bzip2.bz2", true);
    }

    private void testIsSplittableCodec(String description, String pathName, boolean expected)
            throws IOException {
        Path path = new Path(pathName);
        Configuration configuration = new Configuration();
        FileSystem fs = path.getFileSystem(configuration);

        boolean result = new PxfInputFormat().isSplitable(fs, path);
        assertEquals(result, expected, description);
    }

    @Test
    public void testListingUnderLimit(@TempDir java.nio.file.Path dir) throws IOException {
        createFiles(dir, "a.csv", "b.csv", "c.json");
        Files.createFile(dir.resolve("_SUCCESS"));

        FileStatus[] result = listStatus(dir, new Path(dir.toUri()), 10);
        assertEquals(3, result.length);
    }

    @Test
    public void testThrowsWhenLimitExceeded(@TempDir java.nio.file.Path dir) throws IOException {
        createFiles(dir, "a.csv", "b.csv", "c.csv");

        IOException e = assertThrows(IOException.class,
                () -> listStatus(dir, new Path(dir.toUri()), 1));
        assertTrue(e.getMessage().contains("exceeds the configured limit of 1"), e.getMessage());
        assertTrue(e.getMessage().contains(PxfInputFormat.FILES_LIMIT_OPTION), e.getMessage());
        assertTrue(e.getMessage().contains(PxfInputFormat.FILES_LIMIT_PROPERTY), e.getMessage());
    }

    @Test
    public void testAppliesGlobFilter(@TempDir java.nio.file.Path dir) throws IOException {
        createFiles(dir, "a.csv", "b.csv", "c.json");

        FileStatus[] result = listStatus(dir, new Path(dir.toUri() + "*.csv"), 10);
        assertEquals(2, result.length);
    }

    @Test
    public void testWildcardMatchingNothingThrows(@TempDir java.nio.file.Path dir) throws IOException {
        createFiles(dir, "a.csv");

        assertThrows(InvalidInputException.class,
                () -> listStatus(dir, new Path(dir.toUri() + "*.parquet"), 10));
    }

    private FileStatus[] listStatus(java.nio.file.Path dir, Path input, int limit) throws IOException {
        JobConf job = new JobConf(new Configuration());
        job.setInt(PxfInputFormat.FILES_LIMIT_PROPERTY, limit);
        FileInputFormat.setInputPaths(job, input);
        return new LimitedPxfInputFormat().listStatus(job);
    }

    private void createFiles(java.nio.file.Path dir, String... names) throws IOException {
        for (String name : names) {
            Files.createFile(dir.resolve(name));
        }
    }

    /**
     * Forces the streaming limited path so the limit can be exercised against
     * the local filesystem, which is otherwise gated out as a non-object-store.
     */
    private static class LimitedPxfInputFormat extends PxfInputFormat {
        @Override
        protected boolean isLimitApplicable(JobConf job) {
            return true;
        }
    }
}
