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

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

/**
 * Reads chunk NBT from live Anvil region files using the server's own
 * {@link RegionFile} class — full per-chunk codec dispatch (gzip/deflate/
 * none/lz4) and external {@code .mcc} oversized-chunk support for free.
 *
 * <p>The region file is opened without the DSYNC flag and never written to;
 * the mod remains a read-only observer of the world save.
 */
public final class ChunkReader implements AutoCloseable {

    private final RegionFile regionFile;

    private ChunkReader(RegionFile regionFile) {
        this.regionFile = regionFile;
    }

    public static ChunkReader open(Path regionFilePath, String levelName,
                                   ResourceKey<Level> dimension) throws IOException {
        RegionStorageInfo info = new RegionStorageInfo(levelName, dimension, "region");
        Path externalDir = regionFilePath.getParent();
        if (!Files.isDirectory(externalDir)) {
            throw new IOException("Expected region directory: " + externalDir);
        }
        // sync=false: no DSYNC open option; we never write through this handle.
        RegionFile file = new RegionFile(info, regionFilePath, externalDir, false);
        return new ChunkReader(file);
    }

    public CompoundTag readChunk(int localX, int localZ) throws IOException {
        try (DataInputStream in = regionFile.getChunkDataInputStream(new ChunkPos(localX, localZ))) {
            if (in == null) {
                return null;
            }
            return NbtIo.read(in);
        }
    }

    @Override
    public void close() throws IOException {
        regionFile.close();
    }
}
