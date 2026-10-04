# ZRDBack - Zstd Reverse Delta Backup

ZRDBack (Zstd Reverse Delta Backup) is a server-side Fabric mod that backs up worlds in the ZVCR-3D format -
semantic, blockstate-level version control of region files with reverse deltas and Zstd
compression.

Unlike git-based backup mods (which version raw region files) or tar-snapshot mods (which
duplicate the whole world), ZRDBack understands the chunk data it stores:

- Backups are incremental at the block level: only chunks whose region-file timestamp
  changed are read, and only the differences against the previous backup are stored.
- Full history with time travel: any past state can be reconstructed.
- Backups are byte-compatible with the C++ ZVCR tooling (`zvcr_utils`): the same files
  can be read, rendered, merged and exported with the original tools.

```
Fabric mod "ZRDBack" (Java 25, MC 26.3)
├── zvcr-java            pure-Java ZVCR-3D format library (byte-compatible)
│   ├── paletted pack/unpack (4/8/16-bit, uint64 cells, LSB-first)
│   ├── reverse delta chains + mid-chain checkpoints
│   ├── block/biome palette tables (canonical ordering, dedup)
│   ├── tile entity history (put/erase, canonical NBT)
│   ├── region container serialization + Zstd level 8 (frame checksum)
│   └── directory layout + floorDiv32 sector math
├── BackupService        header scan → change gate → extract → insert → atomic write
├── FileBlobStore        content-addressed blobs for non-chunk files (level.dat, players, …)
├── WorldRestorer        reconstructs a bootable world from any timestamp
 └── /zrdback             now | prune | restore | stats | status
```

---

## How it works

### Change detection

An Anvil region file starts with an 8 KiB header: 1024 chunk offsets + 1024 chunk
timestamps. ZRDBack reads only that header (8 KiB per region file, no chunk data) and
compares each chunk's timestamp against the newest timestamp stored in the backup for that
chunk:

```
chunk changed  ⇔  offset ≠ 0  &&  header timestamp > chain-head timestamp
```

Unchanged chunks cost an in-memory comparison. Only changed chunks are decompressed and
parsed.

### Semantic storage (ZVCR-3D)

For every changed chunk, the mod extracts:

- 24 block sections (overworld) as arrays of 4096 blockstate IDs each
- 24 biome sections as arrays of 64 biome IDs each
- the tile entity list (type ID + canonical NBT per TE)

and inserts them into the chunk's reverse-delta chain in its `.zvcr3d` file:

- The newest entry of a chain is always a full snapshot (palette-packed).
- Every older entry is a reverse delta: at each changed position it stores the
  *previous* value; unchanged positions store `0xFFFF`.
- Older states are reconstructed by unpacking the newest snapshot and applying deltas
  backwards until the target timestamp is reached.
- Checkpoints: every N deltas (default 32) the oldest state is materialized as a full
  snapshot, bounding reconstruction cost to O(checkpoint interval).

Everything is palette-packed (4/8/16-bit entries, LSB-first in `uint64` cells) and the whole
region container is compressed with Zstd level 8 (with frame checksum). Palettes are
built in canonical (ascending) order so identical section content deduplicates in the
palette table.

### Non-chunk files (blob store)

Files ZVCR does not model semantically are stored in a content-addressed blob store:

- `level.dat`, `level.dat_old`
- `players/` (player data)
- `data/` (world gen settings, scoreboard, game rules, weather, …)
- per-dimension `entities/`, `poi/`, `data/` (raids, world border, dragon fight, …)

Each file is Zstd-compressed into `blobs/<sha256>.zst`, deduplicated by content hash, and
tracked per-file in `files-index.json` as a `{timestamp, hash}` history. these files
support time travel and pruning too.

### Atomic writes

Every backup file is written to a temp file, fsynced, atomically renamed, and the parent
directory is fsynced. A crash mid-backup never corrupts the previous backup.

### Restore

`/zrdback restore <timestamp|latest>` reconstructs a complete, bootable world into
`<output>/restore/<timestamp>/` - never into the live world:

- Region files are rebuilt from the chains at the target timestamp (vanilla
  `PalettedContainer`s, written through vanilla `RegionFile`).
- Tile entities are rebuilt from the stored canonical NBT.
- Auxiliary files come from the blob store at the same timestamp.
- Restored chunks carry the current `DataVersion` and `isLightOn=false`; vanilla recomputes
  light and heightmaps on load.

To use a restore: stop the server, replace the world folder with the restore directory's
contents, start the server.

---

## Usage

### Configuration - `config/zrdback.properties`

| Property | Default | Meaning |
|---|---|---|
| `output-directory` | *(empty)* | Backup storage root. Empty = `zrdback-backups/` next to the world save (never inside it). |
| `interval-minutes` | `60` | Minutes between automatic backups. |
| `checkpoint-interval` | `32` | Full snapshot every N deltas per chain (bounds reconstruction cost). |
| `retention-days` | `0` | Auto-prune entries older than N days after each backup. `0` = keep forever. |

### Commands (permission level: server operators / moderators)

| Command | Effect |
|---|---|
| `/zrdback now` | Run an incremental backup immediately. |
| `/zrdback restore <timestamp\|latest>` | Reconstruct the world as of a unix timestamp into `<output>/restore/<timestamp>/`. |
| `/zrdback prune <days>` | Drop chain/blob history older than N days (newest state is always kept), GC unreferenced blobs. |
| `/zrdback stats` | Chain lengths, palette dedup ratio, blob store size for tuning `checkpoint-interval`. |
| `/zrdback status` | Config summary + blob store size. |

### Backup layout

```
zrdback-backups/
├── overworld/<sectorX>/<sectorZ>/r.<regionX>.<regionZ>.zvcr3d
├── nether/…  end/…
├── blobs/<sha256>.zst          # deduplicated auxiliary files
└── files-index.json            # per-file {timestamp, hash} history
```

Region coordinates use the standard Anvil mapping; sector directories are
`floorDiv(region, 32)` (correct for negative coordinates).

### Restoring

```
/zrdback restore latest
# → …/zrdback-backups/restore/1790683304/
```

Stop the server, replace the world folder with the contents of the restore directory,
start the server. The restored world boots like the original (verified: zero load errors,
block states and tile entities intact).

## Credits

- crayne - author of the reference ZVCR implementation
  ([zvcr](https://github.com/2b2tplace/zvcr) and
  [zvcr_utils](https://github.com/2b2tplace/zvcr_utils)), the ZVCR-3D file format, and the
  C++ tooling this mod is byte-compatible with. The format specification, the reverse-delta
  design, the palette packing scheme and the directory layout all come from the reference
  implementation; this project's Java library is a faithful port of it, cross-validated
  against it with golden-file tests.

## License

This project is licensed under the GNU General Public License v3.0 or later. see
[LICENSE](LICENSE).

The underlying ZVCR-3D format and the reference implementation (`zvcr`, `zvcr_utils`) by
crayne are licensed under the GNU Lesser General Public License v3.0. The Java format
library in this repository is a port of concepts from that LGPL-licensed reference; it is
redistributed as part of this GPL-licensed work in accordance with the LGPL.
