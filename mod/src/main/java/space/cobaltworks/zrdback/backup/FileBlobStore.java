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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import space.cobaltworks.zvcr.io.ZstdCodec;

/**
 * Content-addressed blob store for files ZVCR does not model semantically
 * (entities/, poi/, players/, level.dat — HANDOFF §3.6).
 *
 * <p>Layout under the backup output directory:
 * <ul>
 *   <li>{@code blobs/<sha256-hex>.zst} — Zstd-compressed raw file bytes,
 *       deduplicated by content hash</li>
 *   <li>{@code files-index.json} — per-file history of {@code {ts, hash}}
 *       entries; the newest entry ≤ T selects the version for time travel</li>
 * </ul>
 *
 * <p>Pruning drops history entries older than the cutoff and garbage-collects
 * blobs no longer referenced by any history.
 */
public final class FileBlobStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int INDEX_VERSION = 1;

    /** Per-file history entry: backup timestamp + content hash. */
    public record Entry(long timestamp, String hash) {}

    private static final class IndexData {
        int version = INDEX_VERSION;
        Map<String, List<Entry>> files = new LinkedHashMap<>();
    }

    private final Path blobsDir;
    private final Path indexPath;
    private IndexData index;

    private FileBlobStore(Path blobsDir, Path indexPath, IndexData index) {
        this.blobsDir = blobsDir;
        this.indexPath = indexPath;
        this.index = index;
    }

    public static FileBlobStore open(Path backupRoot) throws IOException {
        Path blobsDir = backupRoot.resolve("blobs");
        Files.createDirectories(blobsDir);
        Path indexPath = backupRoot.resolve("files-index.json");
        IndexData index = new IndexData();
        if (Files.isRegularFile(indexPath)) {
            index = GSON.fromJson(Files.readString(indexPath), IndexData.class);
            if (index == null || index.files == null) {
                index = new IndexData();
            }
        }
        return new FileBlobStore(blobsDir, indexPath, index);
    }

    /** World files tracked by the store, relative to the world root. */
    public interface WorldFileVisitor {
        void visit(String relativePath) throws IOException;
    }

    /**
     * Stores the current content of one world file. No-op when the content hash
     * equals the newest history entry (unchanged file).
     *
     * @return true if a new history entry was recorded
     */
    public boolean store(Path worldRoot, String relativePath, long timestamp) throws IOException {
        Path file = worldRoot.resolve(relativePath);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        return storeContent(worldRoot, relativePath, Files.readAllBytes(file), timestamp);
    }

    /**
     * Stores already-read content. Callers that race with the server's writes
     * should pass content verified stable (see BackupService.readStableBytes).
     *
     * @return true if a new history entry was recorded
     */
    public boolean storeContent(Path worldRoot, String relativePath, byte[] content,
                                long timestamp) throws IOException {
        String hash = sha256Hex(content);

        Path blob = blobsDir.resolve(hash + ".zst");
        if (!Files.exists(blob)) {
            Files.write(blob, ZstdCodec.compress(content));
        }

        List<Entry> history = index.files.computeIfAbsent(relativePath, k -> new ArrayList<>());
        if (!history.isEmpty() && history.get(history.size() - 1).hash().equals(hash)) {
            return false; // unchanged since the last backup
        }
        history.add(new Entry(timestamp, hash));
        return true;
    }

    /** Persists the index (call once per backup run after storing). */
    public void flush() throws IOException {
        Files.writeString(indexPath, GSON.toJson(index));
    }

    /**
     * Restores every tracked file as of {@code timestamp} (newest entry ≤ T)
     * into {@code targetDir}, preserving the world-relative paths.
     *
     * @return number of files restored
     */
    public int restore(long timestamp, Path targetDir) throws IOException {
        int restored = 0;
        for (Map.Entry<String, List<Entry>> file : index.files.entrySet()) {
            Entry best = null;
            for (Entry entry : file.getValue()) {
                if (entry.timestamp() <= timestamp && (best == null || entry.timestamp() > best.timestamp())) {
                    best = entry;
                }
            }
            if (best == null) {
                continue; // file did not exist yet at that timestamp
            }
            Path blob = blobsDir.resolve(best.hash() + ".zst");
            byte[] content = ZstdCodec.decompress(Files.readAllBytes(blob));
            Path target = targetDir.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            restored++;
        }
        return restored;
    }

    /**
     * Drops history entries older than {@code cutoff} (always keeping the
     * newest entry per file), then garbage-collects blobs referenced by no
     * remaining history.
     *
     * @return number of history entries removed
     */
    public int prune(long cutoff) throws IOException {
        int removed = 0;
        for (List<Entry> history : index.files.values()) {
            for (int i = history.size() - 2; i >= 0; i--) {
                if (history.get(i).timestamp() < cutoff) {
                    history.remove(i);
                    removed++;
                }
            }
        }

        // Reference count over the remaining history.
        Map<String, Integer> refs = new LinkedHashMap<>();
        for (List<Entry> history : index.files.values()) {
            for (Entry entry : history) {
                refs.merge(entry.hash(), 1, Integer::sum);
            }
        }
        try (Stream<Path> blobs = Files.list(blobsDir)) {
            for (Path blob : blobs.filter(f -> f.getFileName().toString().endsWith(".zst")).toList()) {
                String name = blob.getFileName().toString();
                String hash = name.substring(0, name.length() - 4);
                if (!refs.containsKey(hash)) {
                    Files.delete(blob);
                }
            }
        }
        return removed;
    }

    /** Number of tracked files. */
    public int trackedFileCount() {
        return index.files.size();
    }

    /**
     * Distinct backup timestamps recorded across all tracked-file histories.
     * A backup run that changed no tracked file is absent — but then it also
     * stored no new state, so there is nothing to restore for it.
     */
    public Set<Long> timestamps() {
        Set<Long> stamps = new TreeSet<>();
        for (List<Entry> history : index.files.values()) {
            for (Entry entry : history) {
                stamps.add(entry.timestamp());
            }
        }
        return stamps;
    }

    /** Oldest/newest recorded state timestamp across all tracked-file
     *  histories; MAX_VALUE / MIN_VALUE when the index is empty. */
    public long oldestTimestamp() {
        return index.files.values().stream()
                .flatMap(List::stream)
                .mapToLong(Entry::timestamp)
                .min().orElse(Long.MAX_VALUE);
    }

    public long newestTimestamp() {
        return index.files.values().stream()
                .flatMap(List::stream)
                .mapToLong(Entry::timestamp)
                .max().orElse(Long.MIN_VALUE);
    }

    /** Number of distinct blobs on disk. */
    public int blobCount() throws IOException {
        try (Stream<Path> blobs = Files.list(blobsDir)) {
            return (int) blobs.count();
        }
    }

    /** Total bytes occupied by blobs. */
    public long blobBytes() throws IOException {
        try (Stream<Path> blobs = Files.list(blobsDir)) {
            return blobs.mapToLong(f -> {
                try {
                    return Files.size(f);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        }
    }

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder(digest.getDigestLength() * 2);
            for (byte b : digest.digest(content)) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
