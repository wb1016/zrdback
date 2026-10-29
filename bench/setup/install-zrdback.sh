#!/usr/bin/env bash
#
# Installs the ZRDBack build from this repository into the benchmark server.
#
# The mod under test is never downloaded. it is built from the working tree,
# so the benchmark always measures the current code.
#
# Usage: bench/setup/install-zrdback.sh [--jar PATH]
set -euo pipefail

BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="$(cd "$BENCH_DIR/.." && pwd)"
SERVER_DIR="$BENCH_DIR/server"
MODS_DIR="$SERVER_DIR/mods"

JAR=""
while [ $# -gt 0 ]; do
    case "$1" in
        --jar) JAR="$2"; shift 2 ;;
        -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

if [ -z "$JAR" ]; then
    # Pick the newest jar from the build output.
    JAR="$(ls -t "$REPO_DIR"/mod/build/libs/zrdback-*.jar 2>/dev/null | head -1 || true)"
    if [ -z "$JAR" ]; then
        echo "error: no zrdback jar in mod/build/libs. run ./gradlew :mod:build first" >&2
        exit 1
    fi
fi

[ -f "$JAR" ] || { echo "error: no such jar: $JAR" >&2; exit 1; }

mkdir -p "$MODS_DIR"
# Remove older builds so the server never loads two zrdback versions.
rm -f "$MODS_DIR"/zrdback-*.jar
cp "$JAR" "$MODS_DIR/"

echo "installed $(basename "$JAR") into $MODS_DIR"
