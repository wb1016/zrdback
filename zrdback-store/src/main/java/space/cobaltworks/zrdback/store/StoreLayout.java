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
 * by crayne are licensed under the GNU Lesser General Public License v3.
 */
package space.cobaltworks.zrdback.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import space.cobaltworks.zvcr.region.Dimension;
import space.cobaltworks.zvcr.region.RegionLocation;

/**
 * The on-disk layout of a backup store.
 *
 * <pre>
 * &lt;store&gt;/
 *   overworld/&lt;sectorX&gt;/&lt;sectorZ&gt;/r.&lt;rx&gt;.&lt;rz&gt;.zvcr3d
 *   nether/...
 *   end/...
 *   blobs/&lt;sha256&gt;.zst        content-addressed auxiliary files
 *   files-index.json             per-file {timestamp, hash} history
 *   registry/&lt;fingerprint&gt;.manifest  registry ID → name tables
 * </pre>
 */
public final class StoreLayout {

    /** Directory holding the content-addressed blob store. */
    public static final String BLOBS_DIRECTORY = "blobs";

    /** File holding the per-file history of the blob store. */
    public static final String INDEX_FILE = "files-index.json";

    /** Directory holding restored worlds. */
    public static final String RESTORE_DIRECTORY = "restore";

    private StoreLayout() {}

    /** Directory of one dimension inside the store. */
    public static Path dimensionDirectory(Path storeRoot, Dimension dimension) {
        return storeRoot.resolve(dimension.directoryName);
    }

    /** Every {@code .zvcr3d} file in the store, across all dimensions. */
    public static List<Path> regionFiles(Path storeRoot) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Dimension dimension : Dimension.values()) {
            Path dimDir = dimensionDirectory(storeRoot, dimension);
            if (!Files.isDirectory(dimDir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dimDir)) {
                walk.filter(f -> f.getFileName().toString().endsWith(".zvcr3d"))
                        .forEach(files::add);
            }
        }
        return files;
    }

    /** The region coordinates encoded in a {@code .zvcr3d} file name. */
    public static RegionLocation locationOf(Path file, Dimension dimension) {
        return RegionLocation.fromFileName(dimension, file.getFileName().toString())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Not a region file name: " + file.getFileName()));
    }
}
