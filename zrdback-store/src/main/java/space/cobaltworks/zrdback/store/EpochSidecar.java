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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Records which registry epoch a backup file was written against.
 *
 * <p>Each {@code .zvcr3d} file gets a small {@code r.X.Z.zvcr3d.ep} sidecar
 * holding the fingerprint of the registry the file's atoms were encoded with.
 * At restore time the sidecar selects the {@link RegistryManifest} used to
 * translate the stored IDs into the current registry.
 *
 * <p>The sidecar is written next to the file it describes, so it stays valid
 * when the file is rewritten (a prune rewrite keeps the atoms, hence the
 * epoch). A missing sidecar means the epoch is unknown — the caller then
 * falls back to the protocol version recorded in the file header.
 */
public final class EpochSidecar {

    private static final String SUFFIX = ".ep";

    private EpochSidecar() {}

    /** Path of the epoch sidecar belonging to {@code zvcrPath}. */
    public static Path sidecarPath(Path zvcrPath) {
        return zvcrPath.resolveSibling(zvcrPath.getFileName().toString() + SUFFIX);
    }

    /**
     * Reads the epoch fingerprint of a backup file.
     *
     * @return the fingerprint, or {@code null} when it cannot be determined
     *     (no sidecar, unreadable, or malformed)
     */
    public static String read(Path zvcrPath) {
        Path sidecar = sidecarPath(zvcrPath);
        try {
            if (!Files.isRegularFile(sidecar)) {
                return null;
            }
            String fingerprint = Files.readString(sidecar, StandardCharsets.UTF_8).trim();
            return fingerprint.isEmpty() ? null : fingerprint;
        } catch (IOException e) {
            return null;
        }
    }

    /** Records the epoch fingerprint of a backup file. Failures are ignored. */
    public static void write(Path zvcrPath, String fingerprint) {
        if (fingerprint == null) {
            return;
        }
        try {
            Files.writeString(sidecarPath(zvcrPath), fingerprint, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // advisory metadata — a missing sidecar only costs remapping accuracy
        }
    }
}
