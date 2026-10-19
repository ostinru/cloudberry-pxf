package org.apache.cloudberry.pxf.plugins.hdfs;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapred.InvalidInputException;
import org.apache.hadoop.mapred.JobConf;
import org.apache.cloudberry.pxf.api.model.Fragment;
import org.apache.cloudberry.pxf.api.model.Fragmenter;
import org.apache.cloudberry.pxf.api.model.RequestContext;
import org.apache.cloudberry.pxf.plugins.hdfs.utilities.PxfInputFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class HdfsDataFragmenterTest {

    private final RequestContext context = new RequestContext();

    @BeforeEach
    public void setup() {
        Configuration configuration = new Configuration();
        configuration.set("pxf.fs.basePath", "/");
        context.setConfiguration(configuration);
    }

    @Test
    public void testFragmenterReturnsListOfFiles() throws Exception {
        String path = this.getClass().getClassLoader().getResource("csv/").getPath();

        context.setConfig("default");
        context.setUser("test-user");
        context.setDataSource(path);

        Fragmenter fragmenter = getFragmenter(context);

        List<Fragment> fragmentList = fragmenter.getFragments();
        assertNotNull(fragmentList);
        // empty.csv gets ignored
        assertEquals(8, fragmentList.size());
    }

    @Test
    public void testFragmenterWilcardPath() throws Exception {
        String path = this.getClass().getClassLoader().getResource("csv/").getPath();

        context.setConfig("default");
        context.setUser("test-user");
        context.setDataSource(path + "*.csv");

        Fragmenter fragmenter = getFragmenter(context);

        List<Fragment> fragmentList = fragmenter.getFragments();
        assertNotNull(fragmentList);
        // empty.csv gets ignored
        assertEquals(8, fragmentList.size());
    }

    @Test
    public void testInvalidInputPath() {
        context.setConfig("default");
        context.setUser("test-user");
        context.setDataSource("/tmp/non-existent-path-on-disk/*.csv");

        Fragmenter fragmenter = getFragmenter(context);
        Exception e = assertThrows(InvalidInputException.class,
                fragmenter::getFragments);
        assertEquals("Input Pattern file:/tmp/non-existent-path-on-disk/*.csv matches 0 files", e.getMessage());
    }

    @Test
    public void testInvalidInputPathIgnored() throws Exception {
        context.setConfig("default");
        context.setUser("test-user");
        context.addOption("IGNORE_MISSING_PATH", "true");
        context.setDataSource("/tmp/non-existent-path-on-disk/*.csv");

        Fragmenter fragmenter = getFragmenter(context);

        List<Fragment> fragmentList = fragmenter.getFragments();
        assertNotNull(fragmentList);
        assertEquals(0, fragmentList.size());
    }

    @Test
    public void testFileFragmentsLimitOptionOverridesJobConf() {
        context.setConfig("default");
        context.setUser("test-user");
        context.setDataSource("/some/path");
        context.addOption("FILES_LIMIT", "42");

        HdfsDataFragmenter fragmenter = new HdfsDataFragmenter();
        fragmenter.setRequestContext(context);
        fragmenter.afterPropertiesSet();

        JobConf jobConf = fragmenter.getJobConf();
        assertEquals(42, jobConf.getInt(PxfInputFormat.FILES_LIMIT_PROPERTY, -1));
    }

    @Test
    public void testNoFileFragmentsLimitOptionLeavesJobConfUntouched() {
        context.setConfig("default");
        context.setUser("test-user");
        context.setDataSource("/some/path");

        HdfsDataFragmenter fragmenter = new HdfsDataFragmenter();
        fragmenter.setRequestContext(context);
        fragmenter.afterPropertiesSet();

        JobConf jobConf = fragmenter.getJobConf();
        assertEquals(-1, jobConf.getInt(PxfInputFormat.FILES_LIMIT_PROPERTY, -1));
    }

    private Fragmenter getFragmenter(RequestContext context) {
        HdfsDataFragmenter fragmenter = new HdfsDataFragmenter();
        fragmenter.setRequestContext(context);
        fragmenter.afterPropertiesSet();
        return fragmenter;
    }
}
