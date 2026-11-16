package com.jagent.plugins.sermantbackend.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.zip.GZIPOutputStream;

/** Encodes the Sermant NettyMessage/ServiceData protobuf wire format without linking protobuf classes. */
public final class SermantMessageCodec {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private SermantMessageCodec() { }

    public static byte[] frame(int dataType, String json) throws IOException {
        byte[] raw = json.getBytes(UTF8);
        ByteArrayOutputStream compressedBuffer = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(compressedBuffer);
        gzip.write(raw);
        gzip.finish();
        gzip.close();

        ByteArrayOutputStream serviceData = new ByteArrayOutputStream();
        writeVarint(serviceData, (1 << 3));
        writeVarint(serviceData, dataType);
        writeBytes(serviceData, 2, compressedBuffer.toByteArray());

        ByteArrayOutputStream message = new ByteArrayOutputStream();
        // NettyMessage.messageType is SERVICE_DATA (enum zero), so proto3 omits field 1.
        writeBytes(message, 2, serviceData.toByteArray());

        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        writeVarint(framed, message.size());
        framed.write(message.toByteArray());
        return framed.toByteArray();
    }

    private static void writeBytes(ByteArrayOutputStream out, int field, byte[] value) throws IOException {
        writeVarint(out, (field << 3) | 2);
        writeVarint(out, value.length);
        out.write(value);
    }

    private static void writeVarint(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7f) != 0) {
            out.write((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }
}
