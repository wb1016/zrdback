#!/usr/bin/env bash
#
# Sets up everything the benchmark needs:
#   1. a Fabric server under bench/server (downloaded, never committed)
#   2. the pinned mods from bench/mods.lock (downloaded from Modrinth)
#   3. the ZRDBack build from this repository (copied from mod/build/libs)
#
# Usage: bench/setup/setup.sh [--force-server]
set -euo pipefail

BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

FORCE_SERVER=0
while [ $# -gt 0 ]; do
    case "$1" in
        --force-server) FORCE_SERVER=1; shift ;;
        -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

echo "== installing the fabric server"
if [ "$FORCE_SERVER" -eq 1 ]; then
    "$BENCH_DIR/setup/setup-server.sh" --force
else
    "$BENCH_DIR/setup/setup-server.sh"
fi

echo "== fetching the pinned mods"
"$BENCH_DIR/setup/fetch-mods.sh"

echo "== installing the zrdback build"
"$BENCH_DIR/setup/install-zrdback.sh"

cat <<'EOF'

setup complete.

Before the first run:
  - accept the Minecraft EULA:  set eula=true in bench/server/eula.txt
  - make sure bench/server/world is empty (a fresh world is generated)

Then run:
  bench/bench.sh                # full 20-round benchmark
  bench/bench.sh --rounds 1     # quick smoke run
EOF
