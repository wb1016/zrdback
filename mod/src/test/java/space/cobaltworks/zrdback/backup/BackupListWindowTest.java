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

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Windowing behind {@code /zrdback list <min> <max> <skip> <keep>} —
 * pure logic, no store. Ages are exact to the second: a stamp exactly
 * {@code min} or {@code max} days old is inside the window (inclusive).
 */
class BackupListWindowTest {

    private static final long NOW = Instant.parse("2026-10-06T12:00:00Z").getEpochSecond();

    private static long ts(String iso) {
        return Instant.parse(iso).getEpochSecond();
    }

    @Test
    void dayWindowMatchesCommandLineExample() {
        // /zrdback list days 2 3 ... on Oct 06: backups between Oct 03 and Oct 04.
        List<Long> store = List.of(
                ts("2026-10-06T08:00:00Z"), // 4h old — too young
                ts("2026-10-04T12:00:00Z"), // exactly 2d — in
                ts("2026-10-03T12:00:00Z"), // exactly 3d — in
                ts("2026-10-02T12:00:00Z"), // 4d — too old
                ts("2026-10-01T00:00:00Z")); // 5.5d — too old
        BackupService.BackupList result = BackupService.window(store, NOW, 2, 3, 0, 50);
        assertEquals(List.of(ts("2026-10-04T12:00:00Z"), ts("2026-10-03T12:00:00Z")),
                result.timestamps());
        assertEquals(2, result.totalInRange());
        assertEquals(5, result.totalStore());
        assertEquals(0, result.skipped());
    }

    @Test
    void defaultsListEverythingNewestFirst() {
        List<Long> store = List.of(ts("2026-10-01T00:00:00Z"), ts("2026-10-06T00:00:00Z"),
                ts("2026-10-03T00:00:00Z"));
        BackupService.BackupList result = BackupService.window(store, NOW, 0, 0, 0, 50);
        assertEquals(List.of(ts("2026-10-06T00:00:00Z"), ts("2026-10-03T00:00:00Z"),
                ts("2026-10-01T00:00:00Z")), result.timestamps());
        assertEquals(3, result.totalInRange());
        assertEquals(3, result.totalStore());
    }

    @Test
    void keepCapsOutput() {
        List<Long> store = List.of(ts("2026-10-01T00:00:00Z"), ts("2026-10-02T00:00:00Z"),
                ts("2026-10-03T00:00:00Z"));
        BackupService.BackupList result = BackupService.window(store, NOW, 0, 0, 0, 2);
        assertEquals(3, result.totalInRange());
        assertEquals(2, result.timestamps().size());
        assertEquals(ts("2026-10-03T00:00:00Z"), result.timestamps().get(0));
    }

    @Test
    void skipSkipsNewestThenKeepPages() {
        List<Long> store = List.of(
                ts("2026-10-06T00:00:00Z"), ts("2026-10-05T00:00:00Z"),
                ts("2026-10-04T00:00:00Z"), ts("2026-10-03T00:00:00Z"),
                ts("2026-10-02T00:00:00Z"), ts("2026-10-01T00:00:00Z"));
        // skip the 4 newest, then show at most 9
        BackupService.BackupList result = BackupService.window(store, NOW, 0, 0, 4, 9);
        assertEquals(6, result.totalInRange());
        assertEquals(4, result.skipped());
        assertEquals(List.of(ts("2026-10-02T00:00:00Z"), ts("2026-10-01T00:00:00Z")),
                result.timestamps());
    }

    @Test
    void skipBeyondRangeYieldsEmptyPage() {
        List<Long> store = List.of(ts("2026-10-05T00:00:00Z"), ts("2026-10-04T00:00:00Z"));
        BackupService.BackupList result = BackupService.window(store, NOW, 0, 0, 5, 9);
        assertEquals(0, result.timestamps().size());
        assertEquals(2, result.skipped());
        assertEquals(2, result.totalInRange());
    }

    @Test
    void maxDaysZeroMeansUnbounded() {
        List<Long> store = List.of(
                ts("2026-10-06T00:00:00Z"), // 12h — too young
                ts("2026-10-04T00:00:00Z"), // 2.5d — in
                ts("2025-10-06T00:00:00Z")); // a year — in
        BackupService.BackupList result = BackupService.window(store, NOW, 2, 0, 0, 50);
        assertEquals(List.of(ts("2026-10-04T00:00:00Z"), ts("2025-10-06T00:00:00Z")),
                result.timestamps());
    }

    @Test
    void futureTimestampsAreNeverListed() {
        List<Long> store = List.of(
                ts("2026-10-07T00:00:00Z"), // clock skew: "in the future"
                ts("2026-10-05T00:00:00Z"));
        BackupService.BackupList result = BackupService.window(store, NOW, 0, 0, 0, 50);
        assertEquals(List.of(ts("2026-10-05T00:00:00Z")), result.timestamps());
        // the skewed stamp still exists in the store, it is just not listable
        assertEquals(2, result.totalStore());
        assertEquals(1, result.totalInRange());
    }

    @Test
    void emptyStoreYieldsEmptyListing() {
        BackupService.BackupList result = BackupService.window(List.of(), NOW, 0, 0, 0, 50);
        assertEquals(0, result.totalStore());
        assertEquals(0, result.totalInRange());
        assertEquals(0, result.timestamps().size());
    }
}
