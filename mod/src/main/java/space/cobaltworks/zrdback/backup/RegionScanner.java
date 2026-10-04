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
package space.cobaltworks.zrdback.backup;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Read-only scanner for Anvil region file headers (first 8 KiB).
 *
 * <p>Anvil header layout (big-endian): 1024 chunk offsets (3-byte sector
 * number + 1-byte sector count) followed by 1024 chunk timestamps (unix
 * seconds). Chunk index {@code i = localX + localZ * 32}.
 *
 * <p>Change gating must treat {@code offset == 0} as "absent" regardless of
 * timestamp: vanilla stamps a fresh timestamp on {@code clear()} while leaving
 * the offset at zero.
 */
public final class RegionScanner {

    public record ScannedChunk(int localX, int localZ, long headerTimestamp) {}

    private RegionScanner() {}

    public static Map<Long, ScannedChunk> scan(Path regionFile) throws IOException {
        Map<Long, ScannedChunk> chunks = new HashMap<>();
        ByteBuffer header = ByteBuffer.allocate(8192);
        try (FileChannel channel = FileChannel.open(regionFile, StandardOpenOption.READ)) {
            int read = channel.read(header, 0);
            if (read < 0) {
                return chunks;
            }
            // Offsets at 0..4095, timestamps at 4096..8191; missing bytes stay zero.
            for (int i = 0; i < 1024; i++) {
                int offset = header.getInt(i * 4);
                if (offset == 0) {
                    continue; // absent, regardless of its (possibly fresh) timestamp
                }
                long timestamp = header.getInt(4096 + i * 4) & 0xFFFFFFFFL;
                int localX = i & 31;
                int localZ = i >> 5;
                chunks.put(chunkKey(localX, localZ), new ScannedChunk(localX, localZ, timestamp));
            }
        }
        return chunks;
    }

    public static long chunkKey(int localX, int localZ) {
        return ((long) localX << 32) | (localZ & 0xFFFFFFFFL);
    }

    /** Parses {@code r.{rx}.{rz}.mca}; returns null if the name doesn't match. */
    public static long[] parseRegionCoords(String fileName) {
        if (!fileName.startsWith("r.")) {
            return null;
        }
        String body = fileName.substring(2);
        int dot = body.indexOf('.');
        if (dot < 0 || !body.endsWith(".mca")) {
            return null;
        }
        try {
            return new long[] {
                    Integer.parseInt(body.substring(0, dot)),
                    Integer.parseInt(body.substring(dot + 1, body.length() - 4))
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
