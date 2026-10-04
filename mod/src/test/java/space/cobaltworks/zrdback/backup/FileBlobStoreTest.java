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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBlobStoreTest {

    private static void write(Path path, byte[] content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, content);
    }

    @Test
    void storeDeduplicatesByContent(@TempDir Path tmp) throws Exception {
        Path world = tmp.resolve("world");
        Path backup = tmp.resolve("backups");
        write(world.resolve("level.dat"), new byte[] {1, 2, 3});

        FileBlobStore store = FileBlobStore.open(backup);
        assertTrue(store.store(world, "level.dat", 1000));
        assertFalse(store.store(world, "level.dat", 2000)); // unchanged content
        store.flush();

        FileBlobStore reopened = FileBlobStore.open(backup);
        assertEquals(1, reopened.trackedFileCount());
        assertEquals(1, reopened.blobCount());
        write(world.resolve("level.dat"), new byte[] {4, 5, 6});
        assertTrue(reopened.store(world, "level.dat", 3000));
        reopened.flush();
        assertEquals(2, reopened.blobCount()); // two distinct contents
    }

    @Test
    void restoreTimeTravel(@TempDir Path tmp) throws Exception {
        Path world = tmp.resolve("world");
        Path backup = tmp.resolve("backups");
        write(world.resolve("players/data/a.dat"), new byte[] {1});
        FileBlobStore store = FileBlobStore.open(backup);
        store.store(world, "players/data/a.dat", 1000);
        write(world.resolve("players/data/a.dat"), new byte[] {2, 2});
        store.store(world, "players/data/a.dat", 2000);
        write(world.resolve("players/data/b.dat"), new byte[] {3});
        store.store(world, "players/data/b.dat", 2500);
        store.flush();

        // state at 1500: only a.dat (v1) existed
        Path target = tmp.resolve("restore-1500");
        assertEquals(1, store.restore(1500, target));
        assertTrue(Arrays.equals(new byte[] {1}, Files.readAllBytes(target.resolve("players/data/a.dat"))));

        // state at 3000: both files, a.dat at v2
        Path target2 = tmp.resolve("restore-3000");
        assertEquals(2, store.restore(3000, target2));
        assertTrue(Arrays.equals(new byte[] {2, 2}, Files.readAllBytes(target2.resolve("players/data/a.dat"))));
        assertTrue(Arrays.equals(new byte[] {3}, Files.readAllBytes(target2.resolve("players/data/b.dat"))));
    }

    @Test
    void pruneKeepsNewestAndGcsBlobs(@TempDir Path tmp) throws Exception {
        Path world = tmp.resolve("world");
        Path backup = tmp.resolve("backups");
        write(world.resolve("f.dat"), new byte[] {1});
        FileBlobStore store = FileBlobStore.open(backup);
        store.store(world, "f.dat", 1000);
        write(world.resolve("f.dat"), new byte[] {2});
        store.store(world, "f.dat", 2000);
        write(world.resolve("f.dat"), new byte[] {3});
        store.store(world, "f.dat", 3000);
        store.flush();
        assertEquals(3, store.blobCount());

        int removed = store.prune(2500); // drop entries older than 2500, keep newest
        assertEquals(2, removed);
        assertEquals(1, store.blobCount()); // GC removed both unreferenced blobs
        store.flush();

        // newest entry survives pruning
        Path target = tmp.resolve("restore");
        assertEquals(1, store.restore(9999, target));
        assertTrue(Arrays.equals(new byte[] {3}, Files.readAllBytes(target.resolve("f.dat"))));
    }
}
