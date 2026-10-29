#!/usr/bin/env python3
"""Emits forceload batches for existing chunks only.

Vanilla /forceload is capped at 256 forced chunks and loading a rectangle
GENERATES missing chunks inside it. so batches must be horizontal runs of
existing chunks (no gaps), packed up to the limit.

Output: one batch per line; each line is space-separated run rectangles
"x0 z0 x1 z1" (block coords, inclusive) for one /forceload add <from> <to>.

Usage: forceload_existing.py <world_dir> [max_chunks_per_batch]
"""

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mca_integrity import collect_regions, read_region_chunks  # noqa: E402


def main():
    root = sys.argv[1]
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 256
    runs = []  # (x0_block, z0_block, x1_block) per horizontal run, z fixed
    for rel, full in sorted(collect_regions(root).items()):
        m = re.match(r"r\.(-?\d+)\.(-?\d+)\.mca", os.path.basename(rel))
        rx, rz = int(m.group(1)), int(m.group(2))
        present = read_region_chunks(full)
        for lz in range(32):
            run_start = None
            for lx in range(33):  # 33 = sentinel past the last column
                if lx < 32 and (lx, lz) in present:
                    if run_start is None:
                        run_start = lx
                else:
                    if run_start is not None:
                        runs.append((rx * 32 + run_start, rz * 32 + lz,
                                     rx * 32 + lx - 1))
                        run_start = None
    # pack runs into batches of <= limit chunks; runs are joined with ';'
    # and fields with ',' for shell-friendly parsing
    batch = []
    count = 0
    for x0, z, x1 in runs:
        width = x1 - x0 + 1
        if count + width > limit and batch:
            print(";".join("%d,%d,%d" % r for r in batch))
            batch = []
            count = 0
        batch.append((x0, z, x1))
        count += width
    if batch:
        print(";".join("%d,%d,%d" % r for r in batch))


if __name__ == "__main__":
    main()
