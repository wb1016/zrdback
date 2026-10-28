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
package space.cobaltworks.zrdback.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import space.cobaltworks.zrdback.store.FileBlobStore;
import space.cobaltworks.zrdback.store.StoreLayout;
import space.cobaltworks.zrdback.store.StorePruner;
import space.cobaltworks.zrdback.store.StoreVerifier;
import space.cobaltworks.zrdback.store.TimestampSidecar;

/**
 * Command-line access to a ZRDBack store, without running Minecraft.
 *
 * <pre>
 *   zrdback-cli list    [--store DIR] [--days N]
 *   zrdback-cli stats   [--store DIR]
 *   zrdback-cli verify  [--store DIR]
 *   zrdback-cli prune   --store DIR --days N [--dry-run]
 * </pre>
 *
 * <p>The store directory defaults to {@code ./zrdback-backups}.
 */
public final class ZrdBackCli {

    private static final String DEFAULT_STORE = "zrdback-backups";

    private ZrdBackCli() {}

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println();
            usage(System.err);
            System.exit(2);
        }
    }

    static int run(String[] args) throws IOException {
        if (args.length == 0) {
            usage(System.err);
            return 2;
        }
        String command = args[0];
        List<String> rest = new ArrayList<>(List.of(args).subList(1, args.length));

        Path store = option(rest, "--store").map(Path::of).orElse(Path.of(DEFAULT_STORE));
        if (!Files.isDirectory(store)) {
            System.err.println("error: no such store directory: " + store);
            return 1;
        }

        switch (command) {
            case "list" -> {
                list(store, rest);
                return 0;
            }
            case "stats" -> {
                stats(store);
                return 0;
            }
            case "verify" -> {
                return verify(store);
            }
            case "prune" -> {
                return prune(store, rest);
            }
            case "help", "--help", "-h" -> {
                usage(System.out);
                return 0;
            }
            default -> {
                System.err.println("error: unknown command '" + command + "'");
                usage(System.err);
                return 2;
            }
        }
    }

    private static void list(Path store, List<String> args) throws IOException {
        int days = Integer.parseInt(option(args, "--days").orElse("0"));
        long now = System.currentTimeMillis() / 1000;
        long cutoff = days <= 0 ? Long.MIN_VALUE : now - days * 86400L;

        TreeSet<Long> timestamps = new TreeSet<>();
        timestamps.addAll(FileBlobStore.open(store).timestamps());
        for (Path file : StoreLayout.regionFiles(store)) {
            timestamps.addAll(TimestampSidecar.timestampsFor(file));
        }

        System.out.println("store: " + store.toAbsolutePath());
        System.out.println("restore points: " + timestamps.size());
        for (long timestamp : timestamps.descendingSet()) {
            if (timestamp < cutoff) {
                continue;
            }
            long age = now - timestamp;
            System.out.printf("  %d  %s  (%dh %dm ago)%n",
                    timestamp,
                    java.time.Instant.ofEpochSecond(timestamp),
                    age / 3600,
                    (age % 3600) / 60);
        }
    }

    private static void stats(Path store) throws IOException {
        FileBlobStore blobs = FileBlobStore.open(store);
        System.out.println("store:      " + store.toAbsolutePath());
        System.out.println("blobs:      " + blobs.blobCount() + " (" + blobs.blobBytes() / 1024 + " KiB)");
        System.out.println("tracked:    " + blobs.trackedFileCount() + " files");

        List<Path> files = StoreLayout.regionFiles(store);
        long bytes = 0;
        for (Path file : files) {
            bytes += Files.size(file);
        }
        System.out.println("regions:    " + files.size() + " (" + bytes / 1024 + " KiB)");
    }

    private static int verify(Path store) throws IOException {
        StoreVerifier.Report report = StoreVerifier.verify(store);
        System.out.println("checked " + report.filesChecked() + " region file(s), "
                + report.chunksChecked() + " chunk(s)");
        if (report.ok()) {
            System.out.println("OK — the store is consistent");
            return 0;
        }
        System.out.println(report.problems().size() + " problem(s) found:");
        for (StoreVerifier.Problem problem : report.problems()) {
            System.out.println("  " + problem);
        }
        return 1;
    }

    private static int prune(Path store, List<String> args) throws IOException {
        String daysOption = option(args, "--days").orElse(null);
        if (daysOption == null) {
            throw new IllegalArgumentException("prune requires --days N");
        }
        int days = Integer.parseInt(daysOption);
        boolean dryRun = args.contains("--dry-run");
        long cutoff = System.currentTimeMillis() / 1000 - days * 86400L;

        System.out.println("pruning entries older than " + days + " day(s)"
                + (dryRun ? " (dry run)" : ""));
        int removed = StorePruner.prune(store, cutoff, dryRun);
        System.out.println("removed " + removed + " chain/blob entr(ies)");
        return 0;
    }

    private static java.util.Optional<String> option(List<String> args, String name) {
        for (int i = 0; i < args.size() - 1; i++) {
            if (args.get(i).equals(name)) {
                return java.util.Optional.of(args.get(i + 1));
            }
        }
        return java.util.Optional.empty();
    }

    private static void usage(java.io.PrintStream out) {
        out.println("usage: zrdback-cli <command> [options]");
        out.println();
        out.println("commands:");
        out.println("  list    [--store DIR] [--days N]   list restore points");
        out.println("  stats   [--store DIR]             store statistics");
        out.println("  verify  [--store DIR]             check the store for corruption");
        out.println("  prune   --store DIR --days N [--dry-run]");
        out.println("                                    drop history older than N days");
        out.println();
        out.println("The store directory defaults to ./" + DEFAULT_STORE);
    }
}
