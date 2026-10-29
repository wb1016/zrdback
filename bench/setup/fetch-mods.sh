#!/usr/bin/env bash
#
# Downloads the pinned benchmark mods from Modrinth into bench/server/mods.
#
# Every version id in mods.lock is immutable, so a pinned id always resolves
# to the same file; the sha512 from the lock is verified after download.
#
# Usage: bench/setup/fetch-mods.sh [--server-dir DIR]
set -euo pipefail

BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$BENCH_DIR/server"
LOCK_FILE="$BENCH_DIR/mods.lock"
MODRINTH_API="https://api.modrinth.com/v2"
USER_AGENT="zrdback-bench/1.0 (https://github.com/wb1016/zrdback)"

while [ $# -gt 0 ]; do
    case "$1" in
        --server-dir) SERVER_DIR="$2"; shift 2 ;;
        -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

command -v curl >/dev/null || { echo "error: curl is required" >&2; exit 1; }
command -v sha512sum >/dev/null || { echo "error: sha512sum is required" >&2; exit 1; }
[ -f "$LOCK_FILE" ] || { echo "error: missing $LOCK_FILE" >&2; exit 1; }

MODS_DIR="$SERVER_DIR/mods"
mkdir -p "$MODS_DIR"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

sha512_of() {
    sha512sum "$1" | cut -d' ' -f1
}

count=0
while IFS='|' read -r name project_id version_id filename expected_sha; do
    # skip comments and blank lines
    case "$name" in ''|'#'*) continue ;; esac

    target="$MODS_DIR/$filename"
    if [ -f "$target" ] && [ "$(sha512_of "$target")" = "$expected_sha" ]; then
        echo "  $name: already present ($filename)"
        count=$((count + 1))
        continue
    fi

    echo "  $name: downloading $filename"
    url="$(curl -fsSL -H "User-Agent: $USER_AGENT" \
        "$MODRINTH_API/version/$version_id" \
        | python3 -c "
import json, sys
v = json.load(sys.stdin)
for f in v['files']:
    if f.get('primary'):
        print(f['url'])
        break
else:
    raise SystemExit('no primary file for version ' + v['id'])
")"

    curl -fsSL -H "User-Agent: $USER_AGENT" -o "$tmp/$filename" "$url"

    actual="$(sha512_of "$tmp/$filename")"
    if [ "$actual" != "$expected_sha" ]; then
        echo "error: sha512 mismatch for $filename" >&2
        echo "  expected $expected_sha" >&2
        echo "  actual   $actual" >&2
        exit 1
    fi

    mv "$tmp/$filename" "$target"
    count=$((count + 1))
done < "$LOCK_FILE"

echo "fetched $count mod(s) into $MODS_DIR"
