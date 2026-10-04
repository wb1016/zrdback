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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import space.cobaltworks.zrdback.zvcr.region.RegionLocation;
import space.cobaltworks.zrdback.zvcr.region.ZvcrFile;

/**
 * File-level read/write helpers. Writes are atomic: temp file in the target
 * directory, fsync, rename, then fsync of the parent directory — stronger than
 * the C++ CLI's {@code writeFileSafely} (which skips fsync).
 */
public final class ZvcrFiles {

    private ZvcrFiles() {}

    public static ZvcrFile readFile(Path path) throws IOException {
        return ZvcrReader.read(Files.readAllBytes(path));
    }

    public static ZvcrFile readFile(Path path, long maxDeltas) throws IOException {
        return ZvcrReader.read(Files.readAllBytes(path), maxDeltas);
    }

    public static byte[] writeFileBytes(ZvcrFile file) {
        return ZvcrWriter.serialize(file);
    }

    /** Non-atomic write (matches the C++ library-level {@code writeFile}). */
    public static void writeFile(ZvcrFile file, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, ZvcrWriter.serialize(file));
    }

    /** Atomic write at the standard directory-layout location. */
    public static void writeFileAt(ZvcrFile file, Path parentDirectory, RegionLocation location)
            throws IOException {
        writeFileAt(file, parentDirectory, location, ZstdCodec.COMPRESSION_LEVEL_DEFAULT);
    }

    public static void writeFileAt(ZvcrFile file, Path parentDirectory, RegionLocation location,
                                   int compressionLevel) throws IOException {
        Path target = location.filePath(parentDirectory);
        atomicWrite(target, ZvcrWriter.serialize(file, compressionLevel));
    }

    /**
     * Temp file + fsync + rename + parent-dir fsync. The rename is atomic on
     * POSIX; the fsyncs make the sequence crash-consistent.
     */
    public static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = target.getParent().resolve(target.getFileName() + ".tmp");
        try (var channel = Files.newByteChannel(temp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(java.nio.ByteBuffer.wrap(bytes));
            ((java.nio.channels.FileChannel) channel).force(true);
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        fsyncDirectory(target.getParent());
    }

    private static void fsyncDirectory(Path directory) throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            // Some filesystems refuse to open directories read-only; the file
            // fsync + atomic rename already provide the important guarantees.
        }
    }

    /** Quick magic check without full parsing. */
    public static boolean hasMagic(byte[] data) {
        if (data.length < ZvcrWriter.MAGIC.length()) return false;
        String prefix = new String(data, 0, ZvcrWriter.MAGIC.length(), StandardCharsets.US_ASCII);
        return prefix.equals(ZvcrWriter.MAGIC);
    }
}
