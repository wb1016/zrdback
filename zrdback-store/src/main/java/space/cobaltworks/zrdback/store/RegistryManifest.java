/*
 * ZRDBack: Zstd Reverse Delta Backup
 * Copyright (C) 2026 CobaltWorks
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import space.cobaltworks.zvcr.io.ZstdCodec;

/**
 * A snapshot of the numeric-ID -> namespaced-name mapping a backup was written
 * against the "registry epoch" of that backup.
 *
 * <p>Chunks store block states and biomes as numeric registry IDs. Those IDs
 * are only meaningful for the exact registry that produced them: a Minecraft
 * update or a mod change shifts the ID space, so an old backup would otherwise
 * restore the <em>wrong blocks</em> while still looking valid.
 *
 * <p>A manifest records the ID -> canonical name mapping of one epoch. To
 * restore a file written in a different epoch, the stored IDs are translated
 * through the manifest into the current registry by name; blocks that no
 * longer exist fall back to air.
 *
 * <p>Manifests live in {@code <store>/registry/<fingerprint>.manifest}, are
 * compressed with Zstd, and are immutable: a fingerprint names its content.
 */
public final class RegistryManifest {

    /** Format marker of the manifest text format. */
    public static final String MAGIC = "ZRDREG1";

    /** Directory (relative to the store root) holding the manifests. */
    public static final String DIRECTORY = "registry";

    private final List<String> blockNames;
    private final List<String> biomeNames;
    private final String fingerprint;
    private final int protocolVersion;

    private RegistryManifest(String fingerprint, int protocolVersion,
                             List<String> blockNames, List<String> biomeNames) {
        this.fingerprint = fingerprint;
        this.protocolVersion = protocolVersion;
        this.blockNames = List.copyOf(blockNames);
        this.biomeNames = List.copyOf(biomeNames);
    }

    /**
     * Builds a manifest and computes its fingerprint.
     *
     * @param protocolVersion the ZVCR protocol version the backup was written with
     * @param blockNames canonical block-state names indexed by registry ID
     * @param biomeNames canonical biome names indexed by registry ID
     */
    public static RegistryManifest of(int protocolVersion, List<String> blockNames,
                                      List<String> biomeNames) {
        return new RegistryManifest(fingerprintOf(protocolVersion, blockNames, biomeNames),
                protocolVersion, blockNames, biomeNames);
    }

    /** Fingerprint over the whole ID -> name mapping. */
    public static String fingerprintOf(int protocolVersion, List<String> blockNames,
                                       List<String> biomeNames) {
        MessageDigest digest = sha256();
        digest.update(Integer.toString(protocolVersion).getBytes(StandardCharsets.UTF_8));
        for (String name : blockNames) {
            digest.update((byte) 0);
            digest.update(name.getBytes(StandardCharsets.UTF_8));
        }
        digest.update((byte) 0xff);
        for (String name : biomeNames) {
            digest.update((byte) 0);
            digest.update(name.getBytes(StandardCharsets.UTF_8));
        }
        return toHex(digest.digest());
    }

    public String fingerprint() {
        return fingerprint;
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    /** Block-state names indexed by registry ID. */
    public List<String> blockNames() {
        return blockNames;
    }

    /** Biome names indexed by registry ID. */
    public List<String> biomeNames() {
        return biomeNames;
    }

    /** Path of the manifest file for {@code fingerprint} inside {@code storeRoot}. */
    public static Path pathFor(Path storeRoot, String fingerprint) {
        return storeRoot.resolve(DIRECTORY).resolve(fingerprint + ".manifest");
    }

    /**
     * Writes the manifest if it does not exist yet. Existing manifests are
     * never overwritten: a fingerprint names its content, so the file is
     * immutable.
     */
    public void writeIfAbsent(Path storeRoot) throws IOException {
        Path target = pathFor(storeRoot, fingerprint);
        if (Files.exists(target)) {
            return;
        }
        Files.createDirectories(target.getParent());
        Files.write(target, ZstdCodec.compress(serialize()));
    }

    /** Serializes the manifest to its (uncompressed) text form. */
    byte[] serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append(MAGIC).append('\n');
        sb.append("fingerprint ").append(fingerprint).append('\n');
        sb.append("protocol ").append(protocolVersion).append('\n');
        sb.append("blocks ").append(blockNames.size()).append('\n');
        for (String name : blockNames) {
            sb.append(name).append('\n');
        }
        sb.append("biomes ").append(biomeNames.size()).append('\n');
        for (String name : biomeNames) {
            sb.append(name).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Reads a manifest previously written by {@link #writeIfAbsent}. */
    public static RegistryManifest read(Path storeRoot, String fingerprint) throws IOException {
        Path target = pathFor(storeRoot, fingerprint);
        byte[] raw = ZstdCodec.decompress(Files.readAllBytes(target));
        return parse(new String(raw, StandardCharsets.UTF_8), fingerprint);
    }

    /** True when a manifest for {@code fingerprint} exists in the store. */
    public static boolean exists(Path storeRoot, String fingerprint) {
        return Files.isRegularFile(pathFor(storeRoot, fingerprint));
    }

    /** All manifest fingerprints present in the store, sorted. */
    public static List<String> listFingerprints(Path storeRoot) throws IOException {
        Path dir = storeRoot.resolve(DIRECTORY);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> fingerprints = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".manifest")).toList()) {
                String name = file.getFileName().toString();
                fingerprints.add(name.substring(0, name.length() - ".manifest".length()));
            }
        }
        fingerprints.sort(null);
        return fingerprints;
    }

    static RegistryManifest parse(String text, String expectedFingerprint) throws IOException {
        String[] lines = text.split("\n");
        if (lines.length == 0 || !lines[0].equals(MAGIC)) {
            throw new IOException("Not a registry manifest: " + lines[0]);
        }
        String fingerprint = null;
        int protocol = 0;
        int index = 1;
        while (index < lines.length && !lines[index].startsWith("blocks ")) {
            String line = lines[index++];
            int space = line.indexOf(' ');
            if (space < 0) {
                throw new IOException("Malformed manifest header: " + line);
            }
            String key = line.substring(0, space);
            String value = line.substring(space + 1);
            if (key.equals("fingerprint")) {
                fingerprint = value;
            } else if (key.equals("protocol")) {
                protocol = Integer.parseInt(value);
            }
        }
        if (fingerprint == null) {
            throw new IOException("Manifest has no fingerprint");
        }
        if (expectedFingerprint != null && !expectedFingerprint.equals(fingerprint)) {
            throw new IOException("Manifest fingerprint mismatch: expected " + expectedFingerprint
                    + " but found " + fingerprint);
        }

        List<String> blocks = readEntries(lines, index, "blocks");
        index += 1 + blocks.size();
        List<String> biomes = readEntries(lines, index, "biomes");
        return new RegistryManifest(fingerprint, protocol, blocks, biomes);
    }

    private static List<String> readEntries(String[] lines, int index, String header)
            throws IOException {
        if (index >= lines.length || !lines[index].startsWith(header + " ")) {
            throw new IOException("Manifest is missing the '" + header + "' section");
        }
        int count = Integer.parseInt(lines[index].substring(header.length() + 1).trim());
        List<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int lineIndex = index + 1 + i;
            if (lineIndex >= lines.length) {
                throw new IOException("Manifest truncated in the '" + header + "' section");
            }
            names.add(lines[lineIndex]);
        }
        return names;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
