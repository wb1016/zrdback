#!/usr/bin/env python3
"""Block-integrity comparator for ZRDBack restore verification.

Compares two Minecraft world directories chunk-by-chunk (semantic block
content, not bytes) and reports:
  - missing/extra region files and chunks
  - chunks whose content differs (per-section diff)
  - chunks whose content matches a DIFFERENT chunk in the reference world
    (wrong placement), with an offset histogram
  - chunk-header position mismatches (xPos/zPos vs file location)
  - block-entity (TE) diffs per chunk
  - auxiliary file diffs (level.dat, playerdata, entities, poi, data)

Disc-format semantics mirror vanilla 26.3 (decompiled PalettedContainer#pack):
the palette lists every distinct entry and the packed data holds palette
INDICES at bits = max(4, ceil(log2(n))) for blocks / ceil(log2(n)) for biomes;
a single-entry palette has no data array. Sections are canonicalized through
the palette, so palette-order differences between writer implementations do
not produce false mismatches.

Usage:
  mca_integrity.py --live WORLD_DIR --restored RESTORE_DIR
                   [--json OUT] [--max-list N] [--quiet]

Exit codes: 0 = identical, 1 = differences found, 2 = error.
"""

import argparse
import gzip
import hashlib
import json
import os
import re
import sys
import zlib
from collections import Counter

import numpy as np

try:
    from compression import zstd as _zstd

    def _zstd_decompress(data):
        return _zstd.decompress(data)
except ImportError:
    try:
        import zstandard as _zstdmod

        def _zstd_decompress(data):
            return _zstdmod.ZstdDecompressor().decompress(data)
    except ImportError:
        _zstd_decompress = None

AIR = "minecraft:air"
SECTION_BLOCKS = 4096
SECTION_BIOMES = 64


# --------------------------------------------------------------------------
# MCA container
# --------------------------------------------------------------------------

def _decompress_region_payload(comp, raw):
    if comp == 1:
        return gzip.decompress(raw)
    if comp == 2:
        return zlib.decompress(raw)
    if comp == 3:
        return raw
    if comp == 4:
        if _zstd_decompress is None:
            raise RuntimeError("zstd chunk payload but no zstd support "
                               "(install zstandard or use Python >= 3.14)")
        return _zstd_decompress(raw)
    raise RuntimeError("unknown region compression type %d" % comp)


def read_region_chunks(path):
    """Returns {(localX, localZ): (header_ts, raw_nbt_bytes)} for one .mca."""
    with open(path, "rb") as f:
        data = f.read()
    if len(data) < 8192:
        return {}
    chunks = {}
    for i in range(1024):
        off = int.from_bytes(data[i * 4:i * 4 + 3], "big")
        if off == 0:
            continue
        start = off * 4096
        if start + 5 > len(data):
            continue  # truncated file; treat as absent
        length = int.from_bytes(data[start:start + 4], "big")
        comp = data[start + 4]
        raw = data[start + 5:start + 4 + length]
        ts = int.from_bytes(data[4096 + i * 4:4096 + i * 4 + 4], "big", signed=True)
        try:
            chunks[(i & 31, i >> 5)] = (ts, _decompress_region_payload(comp, raw))
        except Exception as e:
            raise RuntimeError("%s chunk local (%d,%d): %s" % (path, i & 31, i >> 5, e))
    return chunks


# --------------------------------------------------------------------------
# NBT (classic, big-endian)
# --------------------------------------------------------------------------

class NbtReader:
    __slots__ = ("buf", "pos")

    def __init__(self, buf):
        self.buf = buf
        self.pos = 0

    def _need(self, n):
        if self.pos + n > len(self.buf):
            raise RuntimeError("NBT truncated at %d (need %d, have %d)"
                               % (self.pos, n, len(self.buf) - self.pos))

    def u1(self):
        self._need(1)
        v = self.buf[self.pos]
        self.pos += 1
        return v

    def i2(self):
        self._need(2)
        v = int.from_bytes(self.buf[self.pos:self.pos + 2], "big", signed=True)
        self.pos += 2
        return v

    def u2(self):
        self._need(2)
        v = int.from_bytes(self.buf[self.pos:self.pos + 2], "big")
        self.pos += 2
        return v

    def i4(self):
        self._need(4)
        v = int.from_bytes(self.buf[self.pos:self.pos + 4], "big", signed=True)
        self.pos += 4
        return v

    def i8(self):
        self._need(8)
        v = int.from_bytes(self.buf[self.pos:self.pos + 8], "big", signed=True)
        self.pos += 8
        return v

    def f4(self):
        import struct
        self._need(4)
        v = struct.unpack(">f", self.buf[self.pos:self.pos + 4])[0]
        self.pos += 4
        return v

    def f8(self):
        import struct
        self._need(8)
        v = struct.unpack(">d", self.buf[self.pos:self.pos + 8])[0]
        self.pos += 8
        return v

    def string(self):
        n = self.u2()
        self._need(n)
        s = self.buf[self.pos:self.pos + n].decode("utf-8", "replace")
        self.pos += n
        return s

    def i1(self):
        v = self.u1()
        return v - 256 if v > 127 else v

    def array(self, read, width):
        n = abs(self.i4())
        out = []
        for _ in range(n):
            out.append(read())
        return out

    def tag_payload(self, t):
        if t == 1:
            return self.i1()
        if t == 2:
            return self.i2()
        if t == 3:
            return self.i4()
        if t == 4:
            return self.i8()
        if t == 5:
            return self.f4()
        if t == 6:
            return self.f8()
        if t == 7:
            n = abs(self.i4())
            self._need(n)
            v = bytes(self.buf[self.pos:self.pos + n])
            self.pos += n
            return v
        if t == 8:
            return self.string()
        if t == 9:
            et = self.u1()
            n = self.i4()
            out = []
            if et == 0 and n > 0:
                raise RuntimeError("TAG_List of TAG_End with length %d" % n)
            for _ in range(max(0, n)):
                out.append(self.tag_payload(et))
            return out
        if t == 10:
            out = {}
            while True:
                st = self.u1()
                if st == 0:
                    return out
                name = self.string()
                out[name] = self.tag_payload(st)
        if t == 11:
            n = abs(self.i4())
            out = []
            for _ in range(n):
                out.append(self.i4())
            return out
        if t == 12:
            n = abs(self.i4())
            out = []
            for _ in range(n):
                out.append(self.i8())
            return out
        raise RuntimeError("unknown NBT tag type %d at %d" % (t, self.pos))


def parse_nbt(buf):
    r = NbtReader(buf)
    t = r.u1()
    if t != 10:
        raise RuntimeError("root tag type %d is not TAG_Compound" % t)
    root_name = r.string()
    return r.tag_payload(10)


# --------------------------------------------------------------------------
# Palette canonicalization (mirrors vanilla 26.3 disc format)
# --------------------------------------------------------------------------

def _unwrap(tag):
    if isinstance(tag, dict) and len(tag) == 1 and "" in tag:
        return tag[""]
    return tag


def load_block_defaults(path):
    """Block -> default property map, from vanilla's blocks.json report
    (generated via: java -cp server.jar net.minecraft.data.Main --reports).

    Needed because the 26.3 disc format writes DEFAULT states as plain id
    strings while the legacy format writes every property explicitly; without
    the defaults the two representations of the same state canonize
    differently and every chunk looks mismatched.
    """
    import json
    with open(path) as f:
        data = json.load(f)
    defaults = {}
    for name, entry in data.items():
        for state in entry.get("states", []):
            if state.get("default"):
                defaults[name] = dict(state.get("properties") or {})
                break
    return defaults


def canon_block_entry(tag, defaults=None):
    tag = _unwrap(tag)
    if isinstance(tag, str):
        # 26.3 disc format: plain id string = the block's DEFAULT state.
        props = defaults.get(tag) if defaults else None
        if props is None:
            return tag
        parts = ["%s=%s" % (k, props[k]) for k in sorted(props)]
        return "%s[%s]" % (tag, ",".join(parts))
    if isinstance(tag, dict):
        if "id" in tag:  # 26.3: {id, properties?}
            name = tag["id"]
            props = tag.get("properties") or {}
        elif "Name" in tag:  # legacy: {Name, Properties}
            name = tag["Name"]
            props = tag.get("Properties") or {}
        else:
            return "?compound:" + repr(sorted(tag.keys()))
        if not isinstance(props, dict):
            props = {}
        parts = ["%s=%s" % (k, props[k]) for k in sorted(props)]
        return "%s[%s]" % (name, ",".join(parts))
    return "?tag:" + repr(tag)


def canon_biome_entry(tag):
    tag = _unwrap(tag)
    if isinstance(tag, str):
        return tag
    return "?tag:" + repr(tag)


def disc_bits(palette_size, blocks):
    """Vanilla 26.3 Strategy: disc bits for a palette of n distinct entries."""
    n = palette_size
    bits = 0
    v = n - 1
    while v > 0:
        bits += 1
        v >>= 1
    if blocks:
        return max(4, bits)
    return bits


def unpack_indices_np(data, bits, count):
    """SimpleBitStorage layout (LSB-first, valuesPerLong = 64 // bits), vectorized."""
    values_per_long = 64 // bits
    # NBT longs are signed; reinterpret bits, never cast values.
    cells = np.asarray(data, dtype=np.int64).view(np.uint64)
    i = np.arange(count)
    cell = cells[i // values_per_long]
    shift = (i % values_per_long) * bits
    mask = np.uint64((1 << bits) - 1)
    return ((cell >> shift.astype(np.uint64)) & mask).astype(np.int64)


def section_digest(palette, data, blocks, defaults=None):
    """Order-agnostic semantic digest of a section.

    Atoms are unpacked and mapped to their canon block/biome string, then
    re-indexed through the SORTED palette, so two sections with identical
    content produce identical (sorted_palette, digest) pairs even when their
    disc palettes list entries in different orders (e.g. a world saved by a
    serializer that keeps container insertion order instead of repacking
    spatially).

    Returns ('u', value) for uniform sections or ('p', sorted_palette, sha1).
    """
    canon = [canon_block_entry(e, defaults) if blocks else canon_biome_entry(e)
             for e in palette]
    n = len(canon)
    if n == 1:
        if data:
            raise RuntimeError("palette size 1 with data array (malformed section)")
        return ("u", canon[0])
    if not data:
        raise RuntimeError("palette size %d without data array" % n)
    bits = disc_bits(n, blocks)
    size = SECTION_BLOCKS if blocks else SECTION_BIOMES
    values_per_long = 64 // bits
    if len(data) * values_per_long < size:
        raise RuntimeError("data array too short: %d longs for %d entries at %d bits"
                           % (len(data), size, bits))
    indices = unpack_indices_np(data, bits, size)
    sorted_palette = tuple(sorted(canon))
    rank_of = {s: i for i, s in enumerate(sorted_palette)}
    rank_by_index = np.array([rank_of[s] for s in canon], dtype=np.uint16)
    ranked = np.take(rank_by_index, indices)
    digest = hashlib.sha1(ranked.astype("<u2").tobytes()).hexdigest()
    return ("p", sorted_palette, digest)


def ranked_atoms(palette, data, blocks, defaults=None):
    """Semantic atom values (canon strings) per position, for diff reports."""
    canon = [canon_block_entry(e, defaults) if blocks else canon_biome_entry(e)
             for e in palette]
    n = len(canon)
    if n == 1:
        return [canon[0]] * (SECTION_BLOCKS if blocks else SECTION_BIOMES)
    bits = disc_bits(n, blocks)
    size = SECTION_BLOCKS if blocks else SECTION_BIOMES
    indices = unpack_indices_np(list(data), bits, size)
    return [canon[i] for i in indices]


def section_stats(canon_form):
    if canon_form[0] == "u":
        return "uniform=%s" % canon_form[1]
    return "palette=%d" % len(canon_form[1])


# --------------------------------------------------------------------------
# Chunk model
# --------------------------------------------------------------------------

class Chunk:
    __slots__ = ("cx", "cz", "status", "sections", "tes", "fingerprint", "hash_hex", "error")

    def __init__(self, cx, cz):
        self.cx = cx
        self.cz = cz
        self.status = None
        self.sections = {}
        self.tes = []
        self.fingerprint = None
        self.hash_hex = None
        self.error = None

    def finish(self):
        material = json.dumps(
            [{"y": y, "s": s} for y, s in sorted(self.sections.items())],
            separators=(",", ":"), default=list) + "|" + json.dumps(
            sorted(self.tes), separators=(",", ":"))
        self.fingerprint = material
        self.hash_hex = hashlib.sha1(material.encode()).hexdigest()


def parse_chunk(raw, expected_cx, expected_cz, defaults=None):
    root = parse_nbt(raw)
    c = Chunk(root.get("xPos", expected_cx), root.get("zPos", expected_cz))
    c.status = root.get("Status")
    for section in root.get("sections", []):
        y = section.get("Y", 0)
        blocks = section.get("block_states")
        biomes = section.get("biomes")
        entry = {}
        try:
            if blocks is not None and blocks.get("palette") is not None:
                entry["b"] = section_digest(blocks.get("palette"), blocks.get("data"),
                                            True, defaults)
            else:
                entry["b"] = ("u", AIR)
            if biomes is not None and biomes.get("palette") is not None:
                entry["i"] = section_digest(biomes.get("palette"), biomes.get("data"),
                                            False)
            else:
                entry["i"] = ("u", None)
        except RuntimeError as e:
            c.error = "section Y=%s: %s" % (y, e)
            return c
        # A section with no blocks and no biomes is semantically absent
        # (vanilla loads missing sections as air); drop it so chunks compare
        # equal regardless of whether a writer emitted the empty shell.
        if entry["b"] == ("u", AIR) and entry["i"] == ("u", None):
            continue
        c.sections[y] = entry
    for te in root.get("block_entities", []) or []:
        if isinstance(te, dict) and "id" in te:
            c.tes.append((te.get("id"), te.get("x"), te.get("y"), te.get("z")))
    c.finish()
    return c


def region_relpath_pattern(rel):
    m = re.match(r"^r\.(-?\d+)\.(-?\d+)\.mca$", os.path.basename(rel))
    return m


def collect_regions(root):
    """relpath (os-agnostic, '/'-separated) -> absolute path for all region files."""
    out = {}
    for dirpath, _dirnames, filenames in os.walk(root):
        for fn in filenames:
            if not fn.endswith(".mca"):
                continue
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            if re.match(r"^r\.(-?\d+)\.(-?\d+)\.mca$", fn) and "/region/" in "/" + rel:
                out[rel] = full
    return out


def load_world_regions(root, problems, jobs=1, defaults=None):
    rels = sorted(collect_regions(root).items())
    regions = {}
    if jobs > 1:
        from concurrent.futures import ProcessPoolExecutor
        args = [(full, defaults) for _rel, full in rels]
        with ProcessPoolExecutor(max_workers=jobs) as pool:
            loaded = list(pool.map(_load_region_worker, args, chunksize=1))
    else:
        loaded = [_load_region_worker((full, defaults)) for _rel, full in rels]
    for (rel, _full), (rx, rz, chunks, errs) in zip(rels, loaded):
        problems.extend(errs)
        regions[rel] = {"rx": rx, "rz": rz, "chunks": chunks, "path": _full}
    return regions


def _load_region_worker(args):
    full, defaults = args
    from pathlib import Path
    path = Path(full)
    m = re.match(r"^r\.(-?\d+)\.(-?\d+)\.mca$", path.name)
    rx, rz = int(m.group(1)), int(m.group(2))
    errs = []
    chunks = {}
    try:
        raw_chunks = read_region_chunks(path)
    except Exception as e:
        errs.append("region %s: unreadable: %s" % (path.name, e))
        return rx, rz, chunks, errs
    for (lx, lz), (ts, payload) in raw_chunks.items():
        cx, cz = rx * 32 + lx, rz * 32 + lz
        try:
            chunks[(lx, lz)] = parse_chunk(payload, cx, cz, defaults)
        except Exception as e:
            errs.append("chunk %s (%d,%d) local (%d,%d): parse failed: %s"
                        % (path.name, cx, cz, lx, lz, e))
    return rx, rz, chunks, errs


# --------------------------------------------------------------------------
# Auxiliary files
# --------------------------------------------------------------------------

AUX_SKIP = ("session.lock",)

# Directories that are not world data. FastBack keeps its git repository and
# its own state directory inside the world directory (world/.git,
# world/.fastback), which is backup-tool state, not Minecraft state, and is
# never part of a restore.
AUX_SKIP_DIRS = (".git", ".fastback")

# Files FastBack writes into the world directory. Also backup-tool state.
AUX_SKIP_FILES = (".gitattributes", ".gitignore")


def collect_aux(root):
    out = {}
    for dirpath, _dirnames, filenames in os.walk(root):
        rel_dir = os.path.relpath(dirpath, root).replace(os.sep, "/")
        parts = rel_dir.split("/") if rel_dir != "." else []
        if any(p in AUX_SKIP_DIRS for p in parts):
            continue
        for fn in filenames:
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            if "/region/" in "/" + rel:
                continue  # chunk data: compared semantically
            if os.path.basename(rel) in AUX_SKIP:
                continue
            if rel in AUX_SKIP_FILES:
                continue
            h = hashlib.sha256()
            with open(full, "rb") as f:
                for block in iter(lambda: f.read(1 << 20), b""):
                    h.update(block)
            out[rel] = (os.path.getsize(full), h.hexdigest())
    return out


# --------------------------------------------------------------------------
# Comparison
# --------------------------------------------------------------------------

def chunk_position_diff(live_regions, restored_regions, rel, key, defaults=None,
                        max_positions=8):
    """Position-level block diff for one mismatched chunk (re-reads both files)."""
    lc = live_regions[rel]["chunks"][key]
    rc = restored_regions[rel]["chunks"][key]
    out = []
    for y in sorted(set(lc.sections) | set(rc.sections)):
        ls = lc.sections.get(y)
        rs = rc.sections.get(y)
        if ls == rs or ls is None or rs is None:
            continue
        if ls["b"][0] != "p" or rs["b"][0] != "p":
            continue
        if ls["b"][1] != rs["b"][1]:
            continue  # different palette sets: already visible in the summary
        # same sorted palette but different digest -> recompute ranked atoms
        lp = _raw_section(live_regions[rel]["path"], key, y)
        rp = _raw_section(restored_regions[rel]["path"], key, y)
        if lp is None or rp is None:
            continue
        la = ranked_atoms(lp.get("palette"), lp.get("data"), True, defaults)
        ra = ranked_atoms(rp.get("palette"), rp.get("data"), True, defaults)
        diffs = [i for i in range(min(len(la), len(ra))) if la[i] != ra[i]]
        out.append("Y=%s: %d positions differ" % (y, len(diffs)))
        for i in diffs[:max_positions]:
            out.append("   (%d,%d,%d) live=%s restored=%s"
                       % (i & 15, i >> 8, (i >> 4) & 15, la[i], ra[i]))
    return "; ".join(out) if out else "no block-level diff (biomes/TE only)"


def _raw_section(path, key, y):
    for (lx, lz), (ts, payload) in read_region_chunks(path).items():
        if (lx, lz) == key:
            root = parse_nbt(payload)
            for s in root.get("sections", []):
                if s.get("Y") == y:
                    return s.get("block_states")
    return None

def compare(live_root, restored_root, max_list, jobs=1, defaults=None):
    problems = []
    report = {"problems": problems}

    live_regions = load_world_regions(live_root, problems, jobs, defaults)
    restored_regions = load_world_regions(restored_root, problems, jobs, defaults)

    live_rels = set(live_regions)
    restored_rels = set(restored_regions)
    # A region counts as missing only if it contains at least one fully
    # generated chunk; regions holding only lower-status chunks are not
    # backed up (vanilla regenerates them) and legitimately absent.
    missing_regions = []
    missing_regions_not_full = []
    for rel in sorted(live_rels - restored_rels):
        has_full = any(c.status is None or c.status == "minecraft:full"
                       for c in live_regions[rel]["chunks"].values())
        (missing_regions if has_full else missing_regions_not_full).append(rel)
    extra_regions = sorted(restored_rels - live_rels)

    # live content-hash index for placement detection
    live_by_hash = {}
    live_chunk_count = 0
    for rel, reg in live_regions.items():
        for (lx, lz), chunk in reg["chunks"].items():
            live_chunk_count += 1
            live_by_hash.setdefault(chunk.hash_hex, []).append((rel, lx, lz))

    restored_chunk_count = 0
    mismatched = []       # (rel, lx, lz, details)
    header_misplaced = []  # (rel, lx, lz, xPos, zPos)
    missing_full = []     # live chunk is fully generated but absent in restored
    missing_not_full = []  # live chunk never reached full status; vanilla
    extra_chunks = []      # regenerates it from the seed on load
    offset_hist = Counter()
    placed_wrong = []     # restored chunk whose content == a different live chunk
    te_diff_chunks = 0

    for rel in sorted(live_rels & restored_rels):
        lreg = live_regions[rel]
        rreg = restored_regions[rel]
        lchunks = lreg["chunks"]
        rchunks = rreg["chunks"]
        for key in sorted(set(lchunks) | set(rchunks)):
            restored_chunk_count += (key in rchunks)
            if key not in rchunks:
                live_status = lchunks[key].status
                if live_status is not None and live_status != "minecraft:full":
                    missing_not_full.append((rel, key, live_status))
                else:
                    missing_full.append((rel, key))
                continue
            if key not in lchunks:
                extra_chunks.append((rel, key))
                continue
            lc, rc = lchunks[key], rchunks[key]
            if (lc.cx, lc.cz) != (rc.cx, rc.cz):
                header_misplaced.append((rel, key, lc.cx, lc.cz, rc.cx, rc.cz))
            if lc.error or rc.error:
                mismatched.append((rel, key, "parse error live=%s restored=%s"
                                   % (lc.error, rc.error)))
                continue
            if lc.hash_hex == rc.hash_hex:
                continue
            # content differs, build a per-section diff
            details = []
            for y in sorted(set(lc.sections) | set(rc.sections)):
                ls = lc.sections.get(y)
                rs = rc.sections.get(y)
                if ls == rs:
                    continue
                # A section that is completely empty (no blocks, no biomes) is
                # semantically identical to an absent section, vanilla loads
                # missing sections as air. Ignore one-sided empty sections.
                def _empty(s):
                    return s is not None and s["b"] == ("u", AIR) and s["i"] == ("u", None)
                if (ls is None and _empty(rs)) or (rs is None and _empty(ls)):
                    continue
                if ls is None:
                    details.append("Y=%s only-in-restored blocks[%s] biomes[%s]"
                                   % (y, section_stats(rs["b"]), section_stats(rs["i"])))
                elif rs is None:
                    details.append("Y=%s only-in-live blocks[%s] biomes[%s]"
                                   % (y, section_stats(ls["b"]), section_stats(ls["i"])))
                else:
                    if ls["b"] != rs["b"]:
                        lb, rb = ls["b"], rs["b"]
                        if lb[0] == "p" and rb[0] == "p":
                            kind = ("same-palette-set, positions differ"
                                    if lb[1] == rb[1] else "different blocks present")
                            details.append("Y=%s blocks %s (live palette=%d restored=%d)"
                                           % (y, kind, len(lb[1]), len(rb[1])))
                        else:
                            details.append("Y=%s blocks live[%s] restored[%s]"
                                           % (y, section_stats(lb), section_stats(rb)))
                    if ls["i"] != rs["i"]:
                        details.append("Y=%s biomes live[%s] restored[%s]"
                                       % (y, section_stats(ls["i"]), section_stats(rs["i"])))
            lte, rte = set(lc.tes), set(rc.tes)
            te_note = ""
            if lte != rte:
                te_diff_chunks += 1
                te_note = " TE(live=%d restored=%d missing=%d extra=%d)" % (
                    len(lte), len(rte), len(lte - rte), len(rte - lte))
            mismatched.append((rel, key, "; ".join(details[:8]) + te_note))

            # placement: does the restored content exist elsewhere in live?
            hits = live_by_hash.get(rc.hash_hex, [])
            for hrel, hlx, hlz in hits:
                if (hrel, hlx, hlz) == (rel, key[0], key[1]):
                    continue
                dcx = (int(rel.split(".")[1]) * 32 + key[0]) - (int(hrel.split(".")[1]) * 32 + hlx)
                dcz = (int(rel.split(".")[2]) * 32 + key[1]) - (int(hrel.split(".")[2]) * 32 + hlz)
                offset_hist[(dcx, dcz)] += 1
                placed_wrong.append((rel, key, hrel, (hlx, hlz)))
                break

    report["chunks_live"] = live_chunk_count
    report["chunks_restored"] = restored_chunk_count
    report["regions_live"] = len(live_rels)
    report["regions_restored"] = len(restored_rels)
    report["missing_regions"] = missing_regions
    report["missing_regions_not_full"] = missing_regions_not_full
    report["extra_regions"] = extra_regions
    report["missing_chunks"] = [(r, k) for r, k in missing_full[:max_list]]
    report["missing_chunks_count"] = len(missing_full)
    report["missing_not_full_count"] = len(missing_not_full)
    report["missing_not_full_statuses"] = dict(Counter(s for _, _, s in missing_not_full))
    report["extra_chunks"] = [(r, k) for r, k in extra_chunks[:max_list]]
    report["extra_chunks_count"] = len(extra_chunks)
    report["header_misplaced"] = header_misplaced[:max_list]
    report["header_misplaced_count"] = len(header_misplaced)
    report["mismatched_chunks"] = [(r, k, d) for r, k, d in mismatched[:max_list]]
    report["mismatched_chunks_count"] = len(mismatched)
    # position-level diffs for the first mismatched chunks (re-reads both
    # region files; bounded by max_list)
    position_diffs = []
    for rel, key, _d in mismatched[:max_list]:
        try:
            position_diffs.append(
                (rel, key, chunk_position_diff(live_regions, restored_regions,
                                               rel, key, defaults)))
        except Exception as e:
            position_diffs.append((rel, key, "diff failed: %s" % e))
    report["position_diffs"] = position_diffs
    report["te_diff_chunks_count"] = te_diff_chunks
    report["placed_wrong"] = [
        {"restored": "%s local%s" % (r, k), "matches_live": "%s local%s" % (hr, hk)}
        for r, k, hr, hk in placed_wrong[:max_list]]
    report["placed_wrong_count"] = len(placed_wrong)
    report["offset_histogram"] = {"%+d,%+d" % k: v
                                  for k, v in offset_hist.most_common(15)}

    # auxiliary files
    live_aux = collect_aux(live_root)
    restored_aux = collect_aux(restored_root)
    aux_missing = sorted(set(live_aux) - set(restored_aux))
    aux_extra = sorted(set(restored_aux) - set(live_aux))
    aux_changed = sorted(
        rel for rel in set(live_aux) & set(restored_aux)
        if live_aux[rel][1] != restored_aux[rel][1])
    # level.dat legitimately changes between snapshot and stop (time, weather)
    aux_changed_expected = [r for r in aux_changed
                            if os.path.basename(r).startswith("level.dat")]
    aux_changed_real = [r for r in aux_changed if r not in aux_changed_expected]
    report["aux"] = {
        "missing": aux_missing[:max_list],
        "missing_count": len(aux_missing),
        "extra": aux_extra[:max_list],
        "extra_count": len(aux_extra),
        "changed": aux_changed_real[:max_list],
        "changed_count": len(aux_changed_real),
        "changed_expected_volatile": aux_changed_expected,
    }
    # Verdict: chunk problems and aux files present-in-one-but-not-the-other
    # are real failures. Aux CONTENT changes are expected point-in-time drift
    # (weather, clocks, raids, entity movement between snapshot and stop),
    # reported, but not a restore-correctness failure.
    chunk_problems = (missing_regions or extra_regions or missing_full
                      or extra_chunks or header_misplaced or mismatched)
    aux_problems = aux_missing or aux_extra
    report["pass"] = not (chunk_problems or aux_problems)
    report["chunk_problems"] = bool(chunk_problems)
    report["aux_problems"] = bool(aux_problems)
    return report


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--live", required=True, help="reference world directory")
    ap.add_argument("--restored", required=True, help="restored world directory")
    ap.add_argument("--json", help="write the full report as JSON to this path")
    ap.add_argument("--max-list", type=int, default=20,
                    help="max detailed entries per category (default 20)")
    ap.add_argument("--quiet", action="store_true", help="only print the summary")
    ap.add_argument("--jobs", type=int, default=1,
                    help="parallel region parsers (default 1)")
    ap.add_argument("--defaults", metavar="BLOCKS_JSON",
                    help="vanilla blocks.json report (net.minecraft.data.Main "
                         "--reports); expands 26.3 default-state strings so "
                         "legacy and 26.3 palettes canonize identically")
    args = ap.parse_args()

    defaults = None
    if args.defaults:
        defaults = load_block_defaults(args.defaults)
        print("block defaults: %d blocks" % len(defaults), file=sys.stderr)

    for label, path in (("--live", args.live), ("--restored", args.restored)):
        if not os.path.isdir(path):
            print("error: %s is not a directory: %s" % (label, path), file=sys.stderr)
            return 2

    try:
        report = compare(args.live, args.restored, args.max_list,
                         args.jobs, defaults)
    except Exception as e:
        print("error: %s" % e, file=sys.stderr)
        return 2

    if args.json:
        with open(args.json, "w") as f:
            json.dump(report, f, indent=1, default=list)

    print("=== block integrity: live=%s restored=%s ===" % (args.live, args.restored))
    print("regions: live=%d restored=%d (missing=%d not-full-only=%d extra=%d)"
          % (report["regions_live"], report["regions_restored"],
             len(report["missing_regions"]), len(report["missing_regions_not_full"]),
             len(report["extra_regions"])))
    print("chunks:  live=%d restored=%d" % (report["chunks_live"], report["chunks_restored"]))
    print("missing chunks (full):        %d" % report["missing_chunks_count"])
    print("not-full in live (regen):     %d %s"
          % (report["missing_not_full_count"], report["missing_not_full_statuses"]))
    print("extra chunks:        %d" % report["extra_chunks_count"])
    print("header-misplaced:    %d" % report["header_misplaced_count"])
    print("content-mismatched:  %d" % report["mismatched_chunks_count"])
    print("  of which TE-diff:  %d" % report["te_diff_chunks_count"])
    print("wrongly-placed:      %d (content found at another live chunk)"
          % report["placed_wrong_count"])
    if report["offset_histogram"]:
        print("  placement offsets (dcx,dcz -> count): %s"
              % report["offset_histogram"])
    aux = report["aux"]
    print("aux files: missing=%d extra=%d changed=%d (drift, informational)"
          % (aux["missing_count"], aux["extra_count"], aux["changed_count"]))

    if not args.quiet:
        for rel, key in report["missing_chunks"]:
            print("MISSING  %s local%s" % (rel, key))
        for rel, key in report["extra_chunks"]:
            print("EXTRA    %s local%s" % (rel, key))
        for item in report["header_misplaced"]:
            rel, key, lcx, lcz, rcx, rcz = item
            print("HEADER   %s local%s live-pos=(%d,%d) restored-pos=(%d,%d)"
                  % (rel, key, lcx, lcz, rcx, rcz))
        for rel, key, d in report["mismatched_chunks"]:
            print("DIFF     %s local%s: %s" % (rel, key, d))
        for rel, key, d in report.get("position_diffs", []):
            if d:
                print("POSITION %s local%s: %s" % (rel, key, d))
        for w in report["placed_wrong"]:
            print("PLACED   restored %s == live %s" % (w["restored"], w["matches_live"]))
        for rel in aux["missing"]:
            print("AUX-MISS %s" % rel)
        for rel in aux["extra"]:
            print("AUX-EXTRA %s" % rel)
        for rel in aux["changed"]:
            print("AUX-CHG  %s" % rel)
    for p in report["problems"]:
        print("PROBLEM  %s" % p)

    if report["pass"]:
        print("RESULT: PASS. restored world is block-identical to the live world"
              " (full chunks); aux drift: %d changed file(s)"
              % report["aux"]["changed_count"])
        return 0
    print("RESULT: FAIL. %s%s"
          % ("chunk problems " if report["chunk_problems"] else "",
             "aux missing/extra" if report["aux_problems"] else ""))
    return 1


if __name__ == "__main__":
    sys.exit(main())
