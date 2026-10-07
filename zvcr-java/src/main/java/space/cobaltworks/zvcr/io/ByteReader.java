/*
 * ZRDBack — Zstd Reverse Delta Backup
 * Copyright (C) 2026 CobaltDev
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * The ZVCR-3D format and the reference implementation (zvcr, zvcr_utils)
 * by crane are licensed under the GNU Lesser General Public License v3.
 */
package space.cobaltworks.zvcr.io;

/**
 * Little-endian byte reader with bounds checking, matching the C++
 * {@code ReadHandle} primitives. Errors carry the read offset for diagnostics.
 */
public final class ByteReader {

    /** Thrown when a ZVCR file cannot be parsed. */
    public static final class ReadException extends RuntimeException {
        public ReadException(String message) {
            super(message);
        }

        public ReadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private byte[] data;
    private int offset = 0;

    public ByteReader(byte[] data) {
        this.data = data;
    }

    /** Internal: rebinds onto a new buffer and rewinds (used after Zstd decompression). */
    void reset(byte[] newData) {
        this.data = newData;
        this.offset = 0;
    }

    public int offset() {
        return offset;
    }

    public int remaining() {
        return data.length - offset;
    }

    private void require(int bytes, String what) {
        if (offset + bytes > data.length) {
            throw new ReadException("Read out of bounds while reading " + what + ": "
                    + offset + " + " + bytes + " > " + data.length);
        }
    }

    public int readByte(String what) {
        require(1, what);
        return data[offset++] & 0xFF;
    }

    public int readUInt16(String what) {
        require(2, what);
        int value = (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8;
        offset += 2;
        return value;
    }

    public long readUInt32(String what) {
        require(4, what);
        long value = (data[offset] & 0xFFL)
                | (data[offset + 1] & 0xFFL) << 8
                | (data[offset + 2] & 0xFFL) << 16
                | (data[offset + 3] & 0xFFL) << 24;
        offset += 4;
        return value;
    }

    public long readUInt64(String what) {
        require(8, what);
        long value = 0;
        for (int i = 7; i >= 0; i--) {
            value = (value << 8) | (data[offset + i] & 0xFFL);
        }
        offset += 8;
        return value;
    }

    public byte[] readBytes(int length, String what) {
        require(length, what);
        byte[] out = new byte[length];
        System.arraycopy(data, offset, out, 0, length);
        offset += length;
        return out;
    }

    public long[] readUInt64Array(int length, String what) {
        long[] out = new long[length];
        for (int i = 0; i < length; i++) {
            out[i] = readUInt64(what);
        }
        return out;
    }

    public int[] readUInt16Array(int length, String what) {
        int[] out = new int[length];
        for (int i = 0; i < length; i++) {
            out[i] = readUInt16(what);
        }
        return out;
    }

    public void skipBytes(long count, String what) {
        if (count < 0 || offset + count > data.length) {
            throw new ReadException("Read out of bounds while skipping " + what + ": "
                    + offset + " + " + count + " > " + data.length);
        }
        offset += (int) count;
    }
}
