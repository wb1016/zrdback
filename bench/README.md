# ZRDBack benchmark

Measures ZRDBack against [FastBack](https://modrinth.com/mod/fastback).

Each round grows the world to a chunk radius `c_k = floor(sqrt(k*10000)/2)`
(Chunky's radius is in blocks: `block_radius = c_k * 16`), then measures:

- chunky generation wall time
- ZRDBack backup duration (from its own log, in ms)
- FastBack backup wall time (completion = a new orphan branch in `world/.git`)
- store sizes (`du -sb`)
- server overload during each backup: count of `Can't keep up!` warnings and
  the worst "behind" time in the backup's log window

Results land in `bench/out/results.csv`, progress in `bench/out/bench.log`.

## Setup

`bench/setup/setup.sh` nests three scripts:
- `setup/setup-server.sh`: installs a Fabric server into `bench/server/`.
   The Minecraft version and loader version are read from the repository's
   `gradle.properties`, so the server can never drift from the build it
   measures. The Minecraft server jar is downloaded from Mojang's piston-meta
   and the loader libraries from Maven. neither is committed, and the
   Minecraft EULA has to be accepted by you.

- `setup/fetch-mods.sh`: downloads the pinned mods from Modrinth into
   `bench/server/mods/`. The pins live in `bench/mods.lock`; every entry
   carries the immutable Modrinth version id and the expected sha512, which
   is verified after download.

- `setup/install-zrdback.sh`: copies the mod under test from
  `mod/build/libs/` into the server. Build it first:

   ```sh
   ./gradlew :mod:build
   ```

The Python tools live in their own environment:

```sh
bench/setup/setup-python.sh    # uv venv with numpy
```

## Running

```sh
bench/bench.sh                 # full 20-round benchmark
bench/bench.sh --rounds 1      # quick smoke run
```

A full run grows the world to ~200k chunks (about 8 GB of region files) and
takes roughly 15~20 minutes plus generation time.
The JVM flags default to ZGC with a fixed heap.

Useful environment overrides:

| Variable | Default | Meaning |
|---|---|---|
| `JAVA_BIN` | `java` | JVM to run the server with |
| `HEAP_MIN` / `HEAP_MAX` | `16G` / `18G` | server heap |
| `SERVER_DIR` | `bench/server` | server directory |
| `RCON_HOST` / `RCON_PORT` / `RCON_PASSWORD` | `localhost` / `25585` / `7GATCDri4QqT` | RCON connection |
| `C2ME_PARALLELISM` | thread count | C2ME global executor parallelism |

### C2ME and server settings

Two settings in `bench/server/` are not defaults, and both matter:

- `config/c2me.toml`: `globalExecutorParallelism` is pinned to the
  thread count. C2ME's default expression caps the pool at the *core*
  count, which starves chunk generation on a 16-thread CPU (8 cores with
  SMT) and makes the generation phase roughly 4x slower.

  The config file must contain a `version = 3` key: C2ME rewrites the
  whole file on startup and resets any value it cannot associate with a
  known config version. a file without it silently comes back as
  `"default"`. `setup/setup-server.sh` writes the key, and patches the
  value in place if C2ME has already initialised the file.

- `server.properties`: `pause-when-empty-seconds=0`. The server would
  otherwise pause when nobody is connected, and chunk generation is driven
  over RCON with no players online, so the benchmark would stall.


## The mod set

Only the mods that matter are installed:

| Mod | Why |
|---|---|
| ZRDBack | the mod under test, built from this repository |
| FastBack | the comparison target |
| Chunky | generates the N chunks each round needs |
| C2ME | concurrent chunk generation. roughly 4x faster on an 8-core/16-thread CPU, which is what makes a 20-round run practical |
| Lithium | the de-facto standard compatibility/performance target for the community; a benchmark run without it would not reflect what servers actually run |
| Fabric API | required by ZRDBack, FastBack and Chunky |

Nothing else is installed. In particular there is no world-modifying mod, so
the generated terrain is vanilla and the two backup solutions see identical
worlds.

### Dependency notes

The only external dependency is Fabric API. The others are satisfied
without extra downloads, which is why the closure is hardcoded in
`mods.lock` rather than resolved automatically. Modrinth's own dependency
metadata does not describe nested jars:

- C2ME depends on `c2me-base`, which ships inside the C2ME jar as a
  nested jar (`META-INF/jars/c2me-fabric-base-...-all.jar`), together with
  caffeine, jctools, rxjava and mixinsquared.
- Lithium depends on `mixinextras >= 0.5.5`, which `fabric-loader` ships
  itself (`META-INF/jars/mixinextras-fabric-0.5.5.jar`). It is not on
  Modrinth and must not be downloaded separately.
- FastBack depends on `fabric-permissions-api-v0`, a nested jar inside
  the FastBack jar.

## Tools

| Tool | Purpose |
|---|---|
| `tools/rcon.py` | small, robust RCON client (no dependencies) |
| `tools/mca_integrity.py` | compares two world directories chunk-by-chunk on semantic block content; used to verify a restore against the live world |

It skips the backup tools' own state inside the world directory (`world/.git`,
`world/.fastback`, `world/.gitattributes`, `world/.gitignore`). those belong to
FastBack, not to Minecraft, and are never part of a restore.
| `tools/mca_integrity_selftest.py` | self-test for the comparator. synthetic worlds, no server needed |
| `tools/forceload_existing.py` | emits `/forceload` batches for chunks that already exist |

`mca_integrity.py` needs numpy and a zstd implementation; the selftest and
`rcon.py` need neither.

### `mca_integrity.py`

Compares a live world against a restored one:

```sh
python3 bench/tools/mca_integrity.py \
    --live bench/server/world \
    --restored bench/server/zrdback-backups/restore/<timestamp>
```

It reports missing/extra region files and chunks, chunks whose content
differs, chunks whose content matches a different chunk in the reference
world (wrong placement, with an offset histogram), chunk-header position
mismatches, block-entity diffs and auxiliary-file diffs. Exit code 0 means
the restored world is block-identical to the live one.

Optionally pass `--defaults <blocks.json>`, the vanilla block-state report
(`java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports`)
, to expand default-state strings so legacy and 26.3 palettes canonicalize
identically. It is ~7 MB and therefore not committed; regenerate it from the
server jar.

## Files

```
bench/
-   bench.sh              the benchmark runner
-   mods.lock             pinned mod versions + sha512
-   setup/
-   -   setup.sh          runs all three setup steps
-   -   setup-server.sh   fabric + minecraft server
-   -   fetch-mods.sh     modrinth download with hash verification
-   -   install-zrdback.sh copies the build under test into the server
-   -   setup-python.sh   uv venv for the tools
-   tools/                rcon client and world comparator
-   out/                  results.csv, bench.log  (gitignored)
```
