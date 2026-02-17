package org.apache.cloudberry.pxf.plugins.hdfs.utilities;

import java.nio.ByteBuffer;
import java.util.UUID;

public class ParquetUUID {

    public static byte[] toBytes(UUID uuid) {
        // UUID annotates a 16-byte FIXED_LEN_BYTE_ARRAY primitive type.
        // The value is encoded using big-endian, so that 00112233-4455-6677-8899-aabbccddeeff
        // is encoded as the bytes 00 11 22 33 44 55 66 77 88 99 aa bb cc dd ee ff
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.order(java.nio.ByteOrder.BIG_ENDIAN);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }
}
