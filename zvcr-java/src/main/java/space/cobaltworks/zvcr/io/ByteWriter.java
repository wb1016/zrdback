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

import java.nio.charset.StandardCharsets;

/**
 * Little-endian byte writer matching the C++ {@code WriteHandle} primitives.
 * All ZVCR fields are little-endian; NBT buffers are written raw (big-endian
 * inside the buffer).
 */
public final class ByteWriter {

    private byte[] data = new byte[256];
    private int size = 0;

    public int size() {
        return size;
    }

    public byte[] toByteArray() {
        byte[] out = new byte[size];
        System.arraycopy(data, 0, out, 0, size);
        return out;
    }

    private void ensure(int extra) {
        if (size + extra > data.length) {
            int newCapacity = Math.max(data.length * 2, size + extra);
            byte[] grown = new byte[newCapacity];
            System.arraycopy(data, 0, grown, 0, size);
            data = grown;
        }
    }

    public void writeByte(int value) {
        ensure(1);
        data[size++] = (byte) value;
    }

    public void writeUInt16(int value) {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException("uint16 out of range: " + value);
        }
        ensure(2);
        data[size++] = (byte) value;
        data[size++] = (byte) (value >>> 8);
    }

    public void writeUInt32(long value) {
        if (value < 0 || value > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("uint32 out of range: " + value);
        }
        ensure(4);
        data[size++] = (byte) value;
        data[size++] = (byte) (value >>> 8);
        data[size++] = (byte) (value >>> 16);
        data[size++] = (byte) (value >>> 24);
    }

    public void writeUInt64(long value) {
        ensure(8);
        data[size++] = (byte) value;
        data[size++] = (byte) (value >>> 8);
        data[size++] = (byte) (value >>> 16);
        data[size++] = (byte) (value >>> 24);
        data[size++] = (byte) (value >>> 32);
        data[size++] = (byte) (value >>> 40);
        data[size++] = (byte) (value >>> 48);
        data[size++] = (byte) (value >>> 56);
    }

    public void writeBytes(byte[] bytes) {
        ensure(bytes.length);
        System.arraycopy(bytes, 0, data, size, bytes.length);
        size += bytes.length;
    }

    public void writeAscii(String ascii) {
        writeBytes(ascii.getBytes(StandardCharsets.US_ASCII));
    }
}
