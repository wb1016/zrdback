#!/usr/bin/env bash
#
# ZRDBack vs FastBack backup benchmark.
#
# Each round grows the world to a chunk radius c_k = floor(sqrt(k*10000)/2)
# (Chunky's radius is in BLOCKS: block_radius = c_k * 16), then measures:
#   - chunky generation wall time
#   - ZRDBack backup (duration from its own log, ms)
#   - FastBack backup (wall time; completion = new git commit in world/.git)
#   - store sizes (du -sb)
#   - server overload per backup: count of "Can't keep up" warnings and the
#     worst behind-ms in each backup's log window (+ tick query samples in
#     out/bench.log)
#
# Results: bench/out/results.csv   Progress: bench/out/bench.log
#
# The benchmark is local-only: it needs ~10 GB of disk and a large heap, so
# it does not run in CI.
#
# Usage: bench/bench.sh [--rounds N] [--server-dir DIR] [--keep-world]
set -u

# ---- configuration ---------------------------------------------------------
BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="${SERVER_DIR:-$BENCH_DIR/server}"
TOOLS="${TOOLS:-$BENCH_DIR/tools}"
OUT_DIR="${OUT_DIR:-$BENCH_DIR/out}"
RESULTS="${RESULTS:-$OUT_DIR/results.csv}"
BLOG="${BLOG:-$OUT_DIR/bench.log}"

JAVA_BIN="${JAVA_BIN:-java}"
[ -x "$JAVA_BIN" ] || JAVA_BIN="$(command -v java || true)"

# Heap: the world reaches ~2 GB and the backup runs mostly off-heap, but
# vanilla's region cache and the JVM itself need headroom.
HEAP_MIN="${HEAP_MIN:-16G}"
HEAP_MAX="${HEAP_MAX:-18G}"
# ZGC with an explicit heap. Note: the old -XX:+AllocatePrefetchStyle=1
# flag is gone. on JDK 24+ AllocatePrefetchStyle is an int option, so the old
# boolean form makes the JVM refuse to start.
JVM_FLAGS="${JVM_FLAGS:---enable-preview -XX:+UnlockExperimentalVMOptions -XX:+UnlockDiagnosticVMOptions \
-XX:+DisableExplicitGC -XX:+UseNUMA -XX:+UseZGC -XX:-ZProactive \
-Xms$HEAP_MIN -Xmx$HEAP_MAX}"

RCON_HOST="${RCON_HOST:-localhost}"
RCON_PORT="${RCON_PORT:-25585}"
RCON_PASSWORD="${RCON_PASSWORD:-7GATCDri4QqT}"
RCON="python3 $TOOLS/rcon.py $RCON_HOST $RCON_PORT $RCON_PASSWORD"

ROUNDS=20
KEEP_WORLD=0
while [ $# -gt 0 ]; do
    case "$1" in
        --rounds) ROUNDS="$2"; shift 2 ;;
        --server-dir) SERVER_DIR="$2"; shift 2 ;;
        --keep-world) KEEP_WORLD=1; shift ;;
        -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

[ -n "$JAVA_BIN" ] || { echo "error: no java found (set JAVA_BIN)" >&2; exit 1; }
[ -d "$SERVER_DIR" ] || { echo "error: no server dir: $SERVER_DIR (run bench/setup/setup-server.sh)" >&2; exit 1; }
[ -f "$SERVER_DIR/fabric-server-launch.jar" ] || { echo "error: $SERVER_DIR is not a Fabric server" >&2; exit 1; }

mkdir -p "$OUT_DIR"

# Fail fast on JVM flags the selected JDK rejects. otherwise the start-up
# wait below just times out with an unhelpful message.
if ! "$JAVA_BIN" $JVM_FLAGS -version >/dev/null 2>&1; then
    echo "error: the JVM rejects these flags:" >&2
    echo "  $JVM_FLAGS" >&2
    echo "  ($JAVA_BIN)" >&2
    "$JAVA_BIN" $JVM_FLAGS -version 2>&1 | head -5 >&2
    exit 1
fi

# ---- singleton lock: a second instance would interleave rcon commands
# ---- (chunky radii, two concurrent fastback backups -> git lock stall) -----
LOCK="${TMPDIR:-/tmp}/zrdback-bench.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    echo "ERROR: another bench.sh instance is running (lock: $LOCK)" >&2
    exit 1
fi
trap 'rmdir "$LOCK" 2>/dev/null' EXIT

log() { echo "[$(date '+%m-%d %H:%M:%S')] $*" | tee -a "$BLOG" >&2; }

rcon() {
    local out
    out=$($RCON "$@" 2>/dev/null)
    echo "$out"
}

# ---- clean chunky saved-task state (avoids start-confirm prompts) ----------
rm -rf "$SERVER_DIR/config/chunky/tasks"
mkdir -p "$SERVER_DIR/config/chunky/tasks"

# ---- start server ----------------------------------------------------------
cd "$SERVER_DIR" || exit 1
if ! ps aux | grep -q "[f]abric-server-launch.jar"; then
    log "starting server (fabric-server-launch.jar, Xmx$HEAP_MAX, $(basename "$JAVA_BIN"))"
    nohup "$JAVA_BIN" $JVM_FLAGS -jar fabric-server-launch.jar nogui > server.log 2>&1 &
fi
for i in $(seq 1 120); do
    if grep -aq "Done (" server.log 2>/dev/null; then break; fi
    sleep 2
done
grep -aq "Done (" server.log || { log "server failed to start"; exit 1; }
log "server up"

# ---- CSV header (fresh run overwrites) -------------------------------------
echo "step,block_radius,chunk_radius,total_chunks,new_chunks,gen_secs,zrd_ms,zrd_wall,zrd_bytes,fb_wall,fb_bytes,world_bytes,zrd_warn,zrd_behind_ms,fb_warn,fb_behind_ms" > "$RESULTS"

count_zrd() { grep -ac "Backup finished" "$SERVER_DIR/server.log" 2>/dev/null; true; }
# FastBack commits each snapshot to its OWN ORPHAN BRANCH (one commit per branch),
# so `rev-list --count HEAD` never grows. Count snapshot branches instead, that increments once per completed backup.
count_fb()  { [ -d "$SERVER_DIR/world/.git" ] && git -C "$SERVER_DIR/world" for-each-ref --format='%(refname:short)' refs/heads/ 2>/dev/null | wc -l || echo 0; }
log_lines() { wc -l < "$SERVER_DIR/server.log" 2>/dev/null || echo 0; }

# Overload warnings in the log window since line $1 -> "count max_behind_ms"
overload_stats() {
    tail -n +"$(( $1 + 1 ))" "$SERVER_DIR/server.log" 2>/dev/null \
        | grep -aoE "Running [0-9]+ms or [0-9]+ ticks behind" \
        | awk '{c++; if ($2+0 > m) m = $2+0} END {print c+0, m+0}'
}

sample_tick() {  # raw /tick query sample into bench.log (TPS trace)
    local tick
    tick=$(rcon "tick query" 2>/dev/null)
    if [ -n "$tick" ]; then
        log "  tick: $(echo "$tick" | tr '\n' ' ' | head -c 150)"
    fi
}

wait_chunky() {  # max 90 min
    for i in $(seq 1 360); do
        local out
        out=$(rcon "chunky progress" 2>/dev/null)
        if echo "$out" | grep -q "No tasks running"; then return 0; fi
        if (( i % 12 == 0 )); then log "  chunky: $(echo "$out" | head -c 120)"; fi
        sleep 15
    done
    return 1
}

wait_zrd() {  # $1 = previous log-line count; echoes duration ms; max 60 min
    local before=$1
    for i in $(seq 1 360); do
        local now
        now=$(count_zrd)
        if (( now > before )); then
            grep -a "Backup finished" "$SERVER_DIR/server.log" | tail -1 \
                | grep -oE "in [0-9]+ ms" | grep -oE "[0-9]+"
            return 0
        fi
        sample_tick
        sleep 5
    done
    echo "TIMEOUT"
}

wait_fb() { # $1 = previous commit count; max 10 min (incremental stalls are
            # a known FastBack failure mode. don't hang the bench on them)
    local before=$1
    for i in $(seq 1 120); do
        local now
        now=$(count_fb)
        if (( now > before )); then return 0; fi
        sample_tick
        sleep 5
    done
    return 1
}

# ---- fastback init (idempotent) --------------------------------------------
rcon "backup init" > /dev/null 2>&1
for i in $(seq 1 60); do
    [ -d "$SERVER_DIR/world/.git" ] && break
    sleep 2
done

# ---- baseline row (step 0) -------------------------------------------------
zrd_b=$(du -sb "$SERVER_DIR/zrdback-backups" 2>/dev/null | cut -f1)
fb_b=$(du -sb "$SERVER_DIR/world/.git" 2>/dev/null | cut -f1)
w_b=$(du -sb --exclude=.git "$SERVER_DIR/world" 2>/dev/null | cut -f1)
zrd_b=${zrd_b:-0}; fb_b=${fb_b:-0}; w_b=${w_b:-0}
echo "0,0,0,0,0,0,0,0,$zrd_b,0,$fb_b,$w_b,0,0,0,0" >> "$RESULTS"
log "baseline: zrd=$zrd_b fb=$fb_b world=$w_b"

# ---- main loop -------------------------------------------------------------
for k in $(seq 1 "$ROUNDS"); do
    c_r=$(python3 -c "import math; print(int(math.sqrt($k*10000)/2))")
    b_r=$((c_r * 16))
    total=$(( (2*c_r+1) * (2*c_r+1) ))
    log "=== round $k: chunk radius $c_r (block radius $b_r, total $total) ==="

    # generation
    t0=$(date +%s)
    rcon "chunky radius $b_r" > /dev/null
    start_out=$(rcon "chunky start")
    if echo "$start_out" | grep -q "already started"; then
        rcon "chunky start confirm" > /dev/null
    fi
    if wait_chunky; then
        t1=$(date +%s); gen=$((t1 - t0))
        log "  generation done in ${gen}s"
    else
        log "  CHUNKY TIMEOUT at round $k"; gen=-1
    fi

    # ZRDBack backup (overload window = log lines added during the backup)
    zrd_before=$(count_zrd)
    zrd_log0=$(log_lines)
    t2=$(date +%s)
    rcon "zrdback now" > /dev/null
    zrd_ms=$(wait_zrd "$zrd_before")
    t3=$(date +%s); zrd_wall=$((t3 - t2))
    read -r zrd_warn zrd_behind <<< "$(overload_stats "$zrd_log0")"
    log "  zrdback: ${zrd_ms} ms (wall ${zrd_wall}s, overload: ${zrd_warn} warns, max ${zrd_behind}ms behind)"

    # fastback backup (skip after 2 consecutive incremental stalls)
    if [ "${FB_SKIP:-0}" = 1 ]; then
        fb_wall=-1; fb_warn=0; fb_behind=0
        log "  fastback: skipped (disabled after repeated stalls)"
    else
        fb_before=$(count_fb)
        fb_log0=$(log_lines)
        t4=$(date +%s)
        rcon "backup local" > /dev/null
        if wait_fb "$fb_before"; then
            t5=$(date +%s); fb_wall=$((t5 - t4)); FB_TIMEOUTS=0
        else
            log "  FASTBACK TIMEOUT at round $k"; fb_wall=-1
            FB_TIMEOUTS=$(( ${FB_TIMEOUTS:-0} + 1 ))
            if [ "$FB_TIMEOUTS" -ge 2 ]; then
                FB_SKIP=1
                log "  fastback disabled for remaining rounds (repeated incremental stalls)"
            fi
        fi
        read -r fb_warn fb_behind <<< "$(overload_stats "$fb_log0")"
        log "  fastback: wall ${fb_wall}s (overload: ${fb_warn} warns, max ${fb_behind}ms behind)"
    fi

    # sizes
    zrd_bytes=$(du -sb "$SERVER_DIR/zrdback-backups" 2>/dev/null | cut -f1)
    fb_bytes=$(du -sb "$SERVER_DIR/world/.git" 2>/dev/null | cut -f1)
    world_bytes=$(du -sb --exclude=.git "$SERVER_DIR/world" 2>/dev/null | cut -f1)
    zrd_bytes=${zrd_bytes:-0}; fb_bytes=${fb_bytes:-0}; world_bytes=${world_bytes:-0}

    # new chunks this round = total - previous total
    if [ "$k" = 1 ]; then prev=0; else
        prev_c=$(python3 -c "import math; print(int(math.sqrt(($k-1)*10000)/2))")
        prev=$(( (2*prev_c+1) * (2*prev_c+1) ))
    fi
    new=$((total - prev))

    echo "$k,$b_r,$c_r,$total,$new,$gen,$zrd_ms,$zrd_wall,$zrd_bytes,$fb_wall,$fb_bytes,$world_bytes,$zrd_warn,$zrd_behind,$fb_warn,$fb_behind" >> "$RESULTS"
    log "  round $k recorded: gen=${gen}s zrd=${zrd_ms}ms fb=${fb_wall}s zrd=${zrd_bytes}B fb=${fb_bytes}B"
done

# ---- stop server -----------------------------------------------------------
rcon "stop" > /dev/null 2>&1
sleep 10
log "benchmark complete. results in $RESULTS"
