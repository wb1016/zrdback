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
package space.cobaltworks.zrdback.zvcr.io;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;

/**
 * Zstd compression for the region container. Mirrors the C++
 * {@code compressZstd}/{@code decompressZstd}: default level 8, and
 * decompression requires the content size to be stored in the frame.
 *
 * <p>A frame checksum is enabled on compression (P2 robustness). Checksums are
 * part of the Zstd frame format: the C++ reader verifies them transparently,
 * and frames without one (e.g. from the C++ writer) decompress identically.
 * Compressed bytes may still differ across zstd builds/implementations; only
 * decompression compatibility and the container layout are format guarantees.
 */
public final class ZstdCodec {

    public static final int COMPRESSION_LEVEL_DEFAULT = 8;

    private ZstdCodec() {}

    public static byte[] compress(byte[] input, int level) {
        try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
            ctx.setLevel(level);
            ctx.setChecksum(true);
            return ctx.compress(input);
        }
    }

    public static byte[] compress(byte[] input) {
        return compress(input, COMPRESSION_LEVEL_DEFAULT);
    }

    public static byte[] decompress(byte[] input) {
        long contentSize = Zstd.getFrameContentSize(input);
        if (contentSize == -1 || contentSize == -2) {
            // -1: ZSTD_CONTENTSIZE_UNKNOWN (size not stored in the frame);
            // -2: ZSTD_CONTENTSIZE_ERROR (not a valid frame). The C++ reader
            // rejects both.
            throw new ByteReader.ReadException(
                    "Zstd frame does not carry a content size (or is not a valid frame): " + contentSize);
        }
        if (contentSize > Integer.MAX_VALUE) {
            throw new ByteReader.ReadException("Zstd content size too large: " + contentSize);
        }
        byte[] out = new byte[(int) contentSize];
        long result = Zstd.decompress(out, input);
        if (result != contentSize) {
            throw new ByteReader.ReadException("Zstd decompressed size mismatch: " + result
                    + " != " + contentSize);
        }
        return out;
    }
}
