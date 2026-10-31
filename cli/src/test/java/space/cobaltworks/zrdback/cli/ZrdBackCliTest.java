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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the command dispatch and argument handling.
 *
 * <p>The CLI runs as a separate process in production, so the exit codes
 * matter: {@code 0} on success, {@code 1} on a store problem and {@code 2}
 * on a usage error.
 */
class ZrdBackCliTest {

    /** Runs the CLI with the given arguments and captures its output. */
    private record Result(int exitCode, String out, String err) {}

    private static Result run(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code = ZrdBackCli.run(args);
            return new Result(code, out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError("the CLI threw an unexpected exception", e);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @Test
    void noArgumentsIsAUsageError() {
        Result result = run();
        assertEquals(2, result.exitCode());
        assertEquals(true, result.err().contains("usage:"));
    }

    @Test
    void helpSucceedsWithoutAStore() {
        // The store directory must not have to exist for --help: the tool is
        // often run on a machine that does not hold the backup at all.
        Result result = run("help");
        assertEquals(0, result.exitCode());
        assertEquals(true, result.out().contains("usage:"));
        assertEquals(true, result.out().contains("verify"));
    }

    @Test
    void unknownCommandIsAUsageError() {
        Result result = run("nonsense");
        assertEquals(2, result.exitCode());
        assertEquals(true, result.err().contains("unknown command 'nonsense'"));
    }

    @Test
    void aMissingStoreIsAnError() {
        Result result = run("list", "--store", "/nonexistent/zrdback-store");
        assertEquals(1, result.exitCode());
        assertEquals(true, result.err().contains("no such store directory"));
    }

    @Test
    void listsRestorePointsOfAnEmptyStore(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp);
        Result result = run("list", "--store", tmp.toString());
        assertEquals(0, result.exitCode());
        assertEquals(true, result.out().contains("restore points: 0"));
    }

    @Test
    void reportsStatsOfAnEmptyStore(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp);
        Result result = run("stats", "--store", tmp.toString());
        assertEquals(0, result.exitCode());
        assertEquals(true, result.out().contains("blobs:"));
        assertEquals(true, result.out().contains("chains:"));
    }

    @Test
    void verifiesAnEmptyStore(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp);
        Result result = run("verify", "--store", tmp.toString());
        assertEquals(0, result.exitCode());
        assertEquals(true, result.out().contains("OK"));
    }

    @Test
    void pruneRequiresDays() {
        Result result = run("prune", "--store", "/tmp");
        assertEquals(2, result.exitCode());
        assertEquals(true, result.err().contains("prune requires --days"));
    }

    @Test
    void pruneAcceptsTheSnapshotLimitOnItsOwn(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp);
        Result result = run("prune", "--store", tmp.toString(), "--keep", "5");
        assertEquals(0, result.exitCode());
        assertEquals(true, result.out().contains("keep the history of the last 5 snapshot"));
    }

    @Test
    void aLimitBelowOneDisablesIt(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp);
        Result result = run("prune", "--store", tmp.toString(), "--days", "0", "--keep", "3");
        assertEquals(0, result.exitCode());
        assertEquals(true, result.out().contains("no age limit"));
        assertEquals(true, result.out().contains("keep the history of the last 3 snapshot"));
    }

    @Test
    void pruningWithoutAnyLimitIsAUsageError(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp);
        Result result = run("prune", "--store", tmp.toString(), "--days", "0", "--keep", "0");
        assertEquals(2, result.exitCode());
        assertEquals(true, result.err().contains("nothing to prune"));
    }

    @Test
    void diffRequiresBothTimestamps(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp);
        Result result = run("diff", "--store", tmp.toString(), "--older", "1000");
        assertEquals(2, result.exitCode());
        assertEquals(true, result.err().contains("diff requires --older"));
    }

    @Test
    void comparesTwoRestorePointsOfAnEmptyStore(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp);
        Result result = run("diff", "--store", tmp.toString(), "--older", "1000", "--newer", "2000");
        assertEquals(0, result.exitCode());
        assertEquals(true, result.out().contains("0 changed"));
    }
}
