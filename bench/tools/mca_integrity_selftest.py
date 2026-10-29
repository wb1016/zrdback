#!/usr/bin/env python3
"""Self-test for mca_integrity.py: builds synthetic MCA worlds and checks the
comparator detects swaps, placement offsets, palette-order differences,
>8-bit (Global-configuration) sections, header misplacement and aux diffs.

Run: python3 bench/tools/mca_integrity_selftest.py   (exit 0 = all passed)
"""

import os
import shutil
import struct
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
COMPARATOR = os.path.join(HERE, "mca_integrity.py")
TMP = os.path.join(HERE, ".selftest-tmp")


# ---- minimal NBT writer (classic, big-endian) -----------------------------
def s(x):
    b = x.encode()
    return struct.pack(">H", len(b)) + b


def tag(t, name, payload):
    return bytes([t]) + s(name) + payload


def i32(v):
    return struct.pack(">i", v)


def i64(v):
    return struct.pack(">q", v)


def string_list(entries):
    out = bytes([9]) + s("palette") + bytes([8]) + i32(len(entries))
    for e in entries:
        out += s(e)
    return out


def long_array(name, values):
    out = bytes([12]) + s(name) + i32(len(values))
    for v in values:
        out += i64(v)
    return out


def pack_indices(indices, bits):
    vpl = 64 // bits
    cells = []
    for c in range((len(indices) + vpl - 1) // vpl):
        cell = 0
        for k, idx in enumerate(indices[c * vpl:(c + 1) * vpl]):
            cell |= idx << (k * bits)
        cells.append(cell)
    return cells


def section(y, palette, indices, bits):
    # TAG_List elements are payload-only: no type byte, no name.
    out = tag(1, "Y", bytes([y & 0xFF]))
    out += bytes([10]) + s("block_states")
    out += string_list(palette)
    out += long_array("data", pack_indices(indices, bits))
    out += bytes([0])
    out += bytes([10]) + s("biomes")
    out += string_list(["minecraft:plains"])
    out += bytes([0])
    out += bytes([0])
    return out


def chunk_nbt(cx, cz, sections, tes=()):
    out = bytes([10]) + s("")
    out += tag(3, "xPos", i32(cx)) + tag(3, "zPos", i32(cz))
    out += bytes([9]) + s("sections") + bytes([10]) + i32(len(sections))
    for sec in sections:
        out += sec
    out += bytes([9]) + s("block_entities") + bytes([10]) + i32(len(tes))
    for te in tes:
        out += tag(8, "id", s(te[0])) + tag(3, "x", i32(te[1])) \
            + tag(3, "y", i32(te[2])) + tag(3, "z", i32(te[3])) + bytes([0])
    out += bytes([0])
    return out


def write_mca(path, chunks):
    """chunks: {(lx,lz): raw_nbt}"""
    payloads = {}
    blob = b""
    offset = 2  # sectors 0/1 are the header
    for (lx, lz), nbt in sorted(chunks.items()):
        comp = zlib.compress(nbt)
        rec = struct.pack(">I", len(comp) + 1) + bytes([2]) + comp
        i = (lx & 31) + (lz & 31) * 32
        payloads[i] = (offset, len(rec))
        blob += rec
        pad = (-len(rec)) % 4096
        blob += b"\x00" * pad
        offset += (len(rec) + pad) // 4096
    header = bytearray(8192)
    for i, (off, cnt) in payloads.items():
        header[i * 4:i * 4 + 3] = off.to_bytes(3, "big")
        header[i * 4 + 3] = max(1, (cnt + 4095) // 4096)
        header[4096 + i * 4:4096 + i * 4 + 4] = struct.pack(">i", 123456)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(bytes(header) + blob)


import zlib  # noqa: E402  (used by write_mca)


def simple_section(value_b):
    # 4096 atoms: first 8 = value_b, rest = stone -> 2-entry palette, 4 bits
    idx = [1] * 8 + [0] * (4096 - 8)
    return section(0, ["minecraft:stone", value_b], idx, 4)


def big_section():
    # 300 distinct entries -> 9 disc bits (vanilla Global in-memory config)
    palette = ["minecraft:entry_%d" % i for i in range(300)]
    indices = [i % 300 for i in range(4096)]
    return section(4, palette, indices, 9)


def build_world(root, with_playerdata):
    shutil.rmtree(root, ignore_errors=True)
    r00 = {
        (0, 0): chunk_nbt(0, 0, [simple_section("minecraft:oak_log")],
                          tes=[("minecraft:chest", 1, 64, 2)]),
        (1, 0): chunk_nbt(1, 0, [simple_section("minecraft:sand")]),
        (2, 0): chunk_nbt(2, 0, [big_section()]),
    }
    write_mca(os.path.join(root, "region", "r.0.0.mca"), r00)
    with open(os.path.join(root, "level.dat"), "wb") as f:
        f.write(b"LEVELDATA-1")
    if with_playerdata:
        os.makedirs(os.path.join(root, "playerdata"), exist_ok=True)
        with open(os.path.join(root, "playerdata", "u.dat"), "wb") as f:
            f.write(b"PLAYER-1")


def run(live, restored):
    return subprocess.run(
        [sys.executable, COMPARATOR, "--live", live, "--restored", restored,
         "--max-list", "5"], capture_output=True, text=True)


def main():
    shutil.rmtree(TMP, ignore_errors=True)
    os.makedirs(TMP)
    ok = True

    # 1. identical worlds -> exit 0
    build_world(TMP + "/a", True)
    build_world(TMP + "/b", True)
    r = run(TMP + "/a", TMP + "/b")
    print("identical          ->", r.returncode, "(expect 0)")
    ok &= r.returncode == 0

    # 2. swapped chunk content -> 2 mismatches + placement offsets
    shutil.rmtree(TMP + "/b")
    os.makedirs(TMP + "/b")
    write_mca(TMP + "/b/region/r.0.0.mca", {
        (0, 0): chunk_nbt(0, 0, [simple_section("minecraft:sand")]),
        (1, 0): chunk_nbt(1, 0, [simple_section("minecraft:oak_log")],
                          tes=[("minecraft:chest", 1, 64, 2)]),
        (2, 0): chunk_nbt(2, 0, [big_section()]),
    })
    r = run(TMP + "/a", TMP + "/b")
    print("swapped chunks     ->", r.returncode, "(expect 1)")
    print("   wrongly-placed=2:", "wrongly-placed:      2" in r.stdout)
    print("   offsets +/-1,0:  ", ("-1,0" in r.stdout) and ("+1,0" in r.stdout))
    ok &= r.returncode == 1 and "wrongly-placed:      2" in r.stdout

    # 3. same content, different palette order -> equal (canonicalization)
    shutil.rmtree(TMP + "/a")
    shutil.rmtree(TMP + "/b")
    os.makedirs(TMP + "/a"); os.makedirs(TMP + "/b")
    write_mca(TMP + "/a/region/r.0.0.mca", {
        (0, 0): chunk_nbt(0, 0, [section(0, ["minecraft:stone", "minecraft:oak_log"],
                                         [1] * 8 + [0] * 4096, 4)])})
    write_mca(TMP + "/b/region/r.0.0.mca", {
        (0, 0): chunk_nbt(0, 0, [section(0, ["minecraft:oak_log", "minecraft:stone"],
                                         [0] * 8 + [1] * 4096, 4)])})
    r = run(TMP + "/a", TMP + "/b")
    print("palette reorder    ->", r.returncode, "(expect 0)")
    ok &= r.returncode == 0

    # 4. >8-bit section identical -> equal
    build_world(TMP + "/a", False)
    build_world(TMP + "/b", False)
    r = run(TMP + "/a", TMP + "/b")
    print("9-bit section      ->", r.returncode, "(expect 0)")
    ok &= r.returncode == 0

    # 5. header misplaced (xPos/zPos wrong for file location)
    shutil.rmtree(TMP + "/b")
    os.makedirs(TMP + "/b")
    write_mca(TMP + "/b/region/r.0.0.mca", {
        (0, 0): chunk_nbt(1, 0, [simple_section("minecraft:oak_log")],
                          tes=[("minecraft:chest", 1, 64, 2)]),
    })
    r = run(TMP + "/a", TMP + "/b")
    print("header misplaced   ->", r.returncode, "(expect 1)")
    ok &= "header-misplaced:    1" in r.stdout

    # 6. aux diff: playerdata missing in restored
    build_world(TMP + "/a", True)
    r = run(TMP + "/a", TMP + "/b")
    print("aux missing        ->", "AUX-MISS playerdata/u.dat" in r.stdout)
    ok &= "AUX-MISS playerdata/u.dat" in r.stdout

    shutil.rmtree(TMP, ignore_errors=True)
    print("SELF-TEST", "PASSED" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
