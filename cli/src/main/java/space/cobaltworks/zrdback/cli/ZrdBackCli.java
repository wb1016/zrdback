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
package space.cobaltworks.zrdback.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import space.cobaltworks.zrdback.store.FileBlobStore;
import space.cobaltworks.zrdback.store.RetentionPolicy;
import space.cobaltworks.zrdback.store.StoreInspector;
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
 *   zrdback-cli diff    --store DIR --older TS --newer TS
 *   zrdback-cli prune   --store DIR --days N [--keep N] [--dry-run]
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
        }
    }

    static int run(String[] args) throws IOException {
        try {
            return dispatch(args);
        } catch (IllegalArgumentException e) {
            // A usage error, not a store problem.
            System.err.println("error: " + e.getMessage());
            System.err.println();
            usage(System.err);
            return 2;
        }
    }

    private static int dispatch(String[] args) throws IOException {
        if (args.length == 0) {
            usage(System.err);
            return 2;
        }
        String command = args[0];
        List<String> rest = new ArrayList<>(List.of(args).subList(1, args.length));

        // help and unknown commands must be reported without requiring a
        // store to exist
        switch (command) {
            case "help", "--help", "-h" -> {
                usage(System.out);
                return 0;
            }
            case "list", "stats", "verify", "diff", "prune" -> {
                // handled below
            }
            default -> {
                System.err.println("error: unknown command '" + command + "'");
                usage(System.err);
                return 2;
            }
        }

        // Validate the command's own options before touching the store, so
        // a usage error does not depend on a store being present.
        if (command.equals("prune") && option(rest, "--days").isEmpty()
                && option(rest, "--keep").isEmpty()) {
            throw new IllegalArgumentException("prune requires --days N and/or --keep N");
        }
        if (command.equals("diff")
                && (option(rest, "--older").isEmpty() || option(rest, "--newer").isEmpty())) {
            throw new IllegalArgumentException("diff requires --older TS and --newer TS");
        }

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
            case "diff" -> {
                return diff(store, rest);
            }
            default -> {
                return prune(store, rest);
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
        StoreInspector.Stats s = StoreInspector.stats(store);
        System.out.println("store:      " + store.toAbsolutePath());
        System.out.println("files:      " + s.files() + " (" + s.fileBytes() / 1024 + " KiB)");
        System.out.println("segments:   " + s.segments());
        System.out.println("chains:     " + s.chains() + " (entries " + s.chainEntries()
                + ", avg " + String.format("%.1f", s.avgChain())
                + ", min " + s.minChain() + ", max " + s.maxChain() + ")");
        System.out.println("te-deltas:  " + s.teDeltas());
        System.out.println("palettes:   " + s.distinctPalettes() + " distinct / " + s.paletteRefs()
                + " refs (" + String.format("%.1f", 100.0 * (1.0 - s.paletteDedupRatio()))
                + "% dedup)");
        System.out.println("blobs:      " + s.blobCount() + " (" + s.blobBytes() / 1024 + " KiB)");
        System.out.println("tracked:    " + s.trackedFiles() + " files");
    }

    /** Compares two restore points. */
    private static int diff(Path store, List<String> args) throws IOException {
        long older = parseNumber("--older", requireOption(args, "--older"));
        long newer = parseNumber("--newer", requireOption(args, "--newer"));
        StoreInspector.Diff d = StoreInspector.diff(store, older, newer);
        System.out.println("store:      " + store.toAbsolutePath());
        System.out.println("diff:       " + older + " -> " + newer);
        System.out.println("chunks:     " + d.chunksChanged() + " changed, "
                + d.chunksOnlyInNew() + " only in the newer point, "
                + d.chunksOnlyInOld() + " only in the older point");
        System.out.println("files:      " + d.filesChanged() + " changed");
        System.out.println("duration:   " + d.durationMs() + " ms");
        return 0;
    }

    private static int verify(Path store) throws IOException {
        StoreVerifier.Report report = StoreVerifier.verify(store);
        System.out.println("checked " + report.filesChecked() + " region file(s), "
                + report.chunksChecked() + " chunk(s)");
        if (report.ok()) {
            System.out.println("OK: the store is consistent");
            return 0;
        }
        System.out.println(report.problems().size() + " problem(s) found:");
        for (StoreVerifier.Problem problem : report.problems()) {
            System.out.println("  " + problem);
        }
        return 1;
    }

    private static int prune(Path store, List<String> args) throws IOException {
        int days = 0;
        int keep = 0;
        if (option(args, "--days").isPresent()) {
            days = parseNumber("--days", option(args, "--days").get());
        }
        if (option(args, "--keep").isPresent()) {
            keep = parseNumber("--keep", option(args, "--keep").get());
        }
        RetentionPolicy policy = new RetentionPolicy(days, keep);
        if (policy.isUnbounded()) {
            System.err.println("error: nothing to prune: give --days N and/or --keep N "
                    + "(a value below 1 disables that limit)");
            usage(System.err);
            return 2;
        }
        boolean dryRun = args.contains("--dry-run");

        System.out.println("pruning entries older than " + policy.describe()
                + (dryRun ? " (dry run)" : ""));
        int removed = StorePruner.prune(store, policy, dryRun);
        System.out.println("removed " + removed + " chain/blob entr(ies)");
        return 0;
    }

    /** Parses a numeric option; negative values are allowed and mean "no limit". */
    private static int parseNumber(String name, String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number, got '" + raw + "'");
        }
    }

    /** Reads an option that the caller already checked is present. */
    private static String requireOption(List<String> args, String name) {
        return option(args, name)
                .orElseThrow(() -> new IllegalArgumentException(name + " is required"))
                .trim();
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
        out.println("  diff    --store DIR --older TS --newer TS");
        out.println("                                    compare two restore points");
        out.println("  prune   --store DIR [--days N] [--keep N] [--dry-run]");
        out.println("                                    drop history older than N days and/or");
        out.println("                                    keep the history of the last N backups");
        out.println();
        out.println("A limit below 1 is ignored, so 'prune --days 0 --keep 5' keeps the");
        out.println("history of the last five backups regardless of their age.");
        out.println();
        out.println("The store directory defaults to ./" + DEFAULT_STORE);
    }
}
