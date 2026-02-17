package org.apache.cloudberry.pxf.plugins.hdfs.utilities;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class ParquetUUIDTest {

    @Test
    public void testUUID() {
        UUID uuid = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        assertArrayEquals(
                new byte[] {
                        0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
                        (byte) 0x88, (byte) 0x99, (byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD, (byte) 0xEE, (byte) 0xFF},
                ParquetUUID.toBytes(uuid)
        );
    }
}
