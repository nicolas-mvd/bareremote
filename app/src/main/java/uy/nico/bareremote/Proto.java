package uy.nico.bareremote;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The small protobuf subset used by Android TV Remote v2; no generated runtime. */
final class Proto {
    static final int MAX_FRAME = 64 * 1024;
    private static final int MAX_FIELDS = 4096;

    private Proto() {}

    static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Writer number(int field, long value) {
            tag(field, 0);
            varint(out, value);
            return this;
        }

        Writer bytes(int field, byte[] value) {
            if (value == null || value.length > MAX_FRAME) {
                throw new IllegalArgumentException("Invalid protobuf value length");
            }
            tag(field, 2);
            varint(out, value.length);
            out.write(value, 0, value.length);
            return this;
        }

        Writer text(int field, String value) {
            return bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }

        Writer message(int field, Writer value) {
            return bytes(field, value.build());
        }

        byte[] build() {
            if (out.size() > MAX_FRAME) throw new IllegalArgumentException("Message too large");
            return out.toByteArray();
        }

        private void tag(int field, int wire) {
            if (field <= 0 || field > 0x1fffffff) {
                throw new IllegalArgumentException("Invalid protobuf field");
            }
            varint(out, ((long) field << 3) | wire);
        }
    }

    static final class Field {
        final int number;
        final int wire;
        final long value;
        final byte[] bytes;

        Field(int number, int wire, long value, byte[] bytes) {
            this.number = number;
            this.wire = wire;
            this.value = value;
            this.bytes = bytes;
        }
    }

    static final class Message {
        final List<Field> fields = new ArrayList<Field>();

        boolean has(int number) {
            return last(number) != null;
        }

        long integer(int number, long fallback) throws IOException {
            Field f = last(number);
            if (f == null) return fallback;
            if (f.wire != 0) throw new IOException("Wrong wire type for integer");
            return f.value;
        }

        byte[] bytes(int number) throws IOException {
            Field f = last(number);
            if (f == null) return null;
            if (f.wire != 2) throw new IOException("Wrong wire type for message");
            return f.bytes;
        }

        Message message(int number) throws IOException {
            byte[] data = bytes(number);
            if (data == null) throw new IOException("Missing protocol message");
            return parse(data);
        }

        private Field last(int number) {
            for (int i = fields.size() - 1; i >= 0; i--) {
                if (fields.get(i).number == number) return fields.get(i);
            }
            return null;
        }
    }

    static Message parse(byte[] data) throws IOException {
        if (data == null || data.length > MAX_FRAME) throw new IOException("Message too large");
        ByteArrayInputStream in = new ByteArrayInputStream(data);
        Message message = new Message();
        while (in.available() != 0) {
            if (message.fields.size() == MAX_FIELDS) throw new IOException("Too many fields");
            long tag = readVarint(in);
            if (tag <= 0 || tag > 0xffffffffL || (tag >>> 3) == 0) {
                throw new IOException("Invalid protobuf tag");
            }
            int number = (int) (tag >>> 3);
            int wire = (int) tag & 7;
            long value = 0;
            byte[] bytes = null;
            switch (wire) {
                case 0:
                    value = readVarint(in);
                    break;
                case 1:
                    readBytes(in, 8);
                    break;
                case 2:
                    long length = readVarint(in);
                    if (length < 0 || length > in.available()) {
                        throw new IOException("Truncated protobuf value");
                    }
                    bytes = readBytes(in, (int) length);
                    break;
                case 5:
                    readBytes(in, 4);
                    break;
                default:
                    // Neither protocol uses deprecated protobuf groups.
                    throw new IOException("Unsupported protobuf wire type");
            }
            message.fields.add(new Field(number, wire, value, bytes));
        }
        return message;
    }

    static byte[] readFrame(InputStream in) throws IOException {
        long length = readVarint(in);
        if (length <= 0 || length > MAX_FRAME) throw new IOException("Invalid frame length");
        return readBytes(in, (int) length);
    }

    static void writeFrame(OutputStream out, byte[] data) throws IOException {
        if (data.length == 0 || data.length > MAX_FRAME) throw new IOException("Invalid frame length");
        ByteArrayOutputStream prefix = new ByteArrayOutputStream(5);
        varint(prefix, data.length);
        prefix.writeTo(out);
        out.write(data);
        out.flush();
    }

    static long readVarint(InputStream in) throws IOException {
        long value = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            int b = in.read();
            if (b == -1) throw new EOFException("Truncated varint");
            if (shift == 63 && (b & 0xfe) != 0) throw new IOException("Varint overflow");
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return value;
        }
        throw new IOException("Varint overflow");
    }

    static void varint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7fL) != 0) {
            out.write((int) (value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static byte[] readBytes(InputStream in, int count) throws IOException {
        byte[] data = new byte[count];
        int at = 0;
        while (at < count) {
            int n = in.read(data, at, count - at);
            if (n < 0) throw new EOFException("Truncated frame");
            if (n == 0) {
                int one = in.read();
                if (one < 0) throw new EOFException("Truncated frame");
                data[at++] = (byte) one;
            } else {
                at += n;
            }
        }
        return data;
    }
}
