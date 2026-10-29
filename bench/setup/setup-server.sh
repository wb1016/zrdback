#!/usr/bin/env bash
#
# Installs a Fabric server for the benchmark under bench/server.
#
# Downloads (at setup time, never committed):
#   - the Fabric installer (fabricmc.net)
#   - the Fabric loader and its libraries (Maven, via the installer)
#   - the Minecraft server jar (Mojang piston-meta, via the installer)
#
# Versions come from the repository's gradle.properties, so the benchmark
# server can never drift from the build it measures.
#
# Usage: bench/setup/setup-server.sh [--force]
set -euo pipefail

BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="$(cd "$BENCH_DIR/.." && pwd)"
SERVER_DIR="$BENCH_DIR/server"
PROPERTIES="$REPO_DIR/gradle.properties"
INSTALLER_VERSION="1.1.2"

FORCE=0
while [ $# -gt 0 ]; do
    case "$1" in
        --force) FORCE=1; shift ;;
        -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

# ---- environment ------------------------------------------------------------
JAVA_BIN="${JAVA_BIN:-java}"
if [ ! -x "$JAVA_BIN" ]; then
    JAVA_BIN="$(command -v java || true)"
fi
[ -n "$JAVA_BIN" ] || { echo "error: no java found (set JAVA_BIN)" >&2; exit 1; }

command -v curl >/dev/null || { echo "error: curl is required" >&2; exit 1; }
[ -f "$PROPERTIES" ] || { echo "error: missing $PROPERTIES" >&2; exit 1; }

prop() {
    sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$PROPERTIES" | tail -1
}

MC_VERSION="$(prop minecraft_version)"
LOADER_VERSION="$(prop loader_version)"
[ -n "$MC_VERSION" ] || { echo "error: minecraft_version not found in gradle.properties" >&2; exit 1; }
[ -n "$LOADER_VERSION" ] || { echo "error: loader_version not found in gradle.properties" >&2; exit 1; }

# ---- install ---------------------------------------------------------------
if [ -f "$SERVER_DIR/fabric-server-launch.jar" ] && [ "$FORCE" -eq 0 ]; then
    echo "server already installed in $SERVER_DIR (use --force to reinstall)"
else
    if [ "$FORCE" -eq 1 ] && [ -d "$SERVER_DIR" ]; then
        echo "removing existing $SERVER_DIR"
        rm -rf "$SERVER_DIR"
    fi
    mkdir -p "$SERVER_DIR"

    installer="$SERVER_DIR/fabric-installer-$INSTALLER_VERSION.jar"
    echo "downloading fabric installer $INSTALLER_VERSION"
    curl -fsSL -o "$installer" \
        "https://maven.fabricmc.net/net/fabricmc/fabric-installer/$INSTALLER_VERSION/fabric-installer-$INSTALLER_VERSION.jar"

    echo "installing fabric loader $LOADER_VERSION for minecraft $MC_VERSION"
    "$JAVA_BIN" -jar "$installer" server \
        -mcversion "$MC_VERSION" \
        -loader "$LOADER_VERSION" \
        -dir "$SERVER_DIR" \
        -downloadMinecraft
fi

# ---- server configuration ---------------------------------------------------
mkdir -p "$SERVER_DIR/config"

# The benchmark talks to the server over RCON; the port/password are the
# defaults of bench/tools/rcon.py.
if [ ! -f "$SERVER_DIR/server.properties" ]; then
    cat > "$SERVER_DIR/server.properties" <<'EOF'
enable-rcon=true
rcon.port=25585
rcon.password=7GATCDri4QqT
server-port=25565
online-mode=false
level-name=world
level-type=minecraft\:normal
simulation-distance=10
view-distance=10
max-players=20
motd=ZRDBack benchmark
# The server must not pause when nobody is connected: chunk generation is
# driven over RCON and a paused server would stall the whole benchmark.
pause-when-empty-seconds=0
EOF
fi

# C2ME's global executor parallelism. The default expression caps the pool
# at the core count, which starves chunk generation on a 16-thread CPU
# (8 cores with SMT). it is roughly 4x slower there. Set it to the thread
# count so the generation phases stay short.
#
# The `version` key is required: C2ME rewrites the whole file on startup and
# resets any value it cannot associate with a known config version, so a
# file without it comes back as "default".
C2ME_PARALLELISM="${C2ME_PARALLELISM:-$(nproc 2>/dev/null || echo 16)}"
C2ME_CONFIG="$SERVER_DIR/config/c2me.toml"
if [ -f "$C2ME_CONFIG" ]; then
    # Already initialised by C2ME: patch the value in place, keeping the
    # rest of the file (and the version key) intact.
    if grep -q '^globalExecutorParallelism' "$C2ME_CONFIG"; then
        sed -i "s/^globalExecutorParallelism[[:space:]]*=.*/globalExecutorParallelism = $C2ME_PARALLELISM/" "$C2ME_CONFIG"
    else
        printf '\nglobalExecutorParallelism = %s\n' "$C2ME_PARALLELISM" >> "$C2ME_CONFIG"
    fi
else
    cat > "$C2ME_CONFIG" <<EOF
# Benchmark config: C2ME's default parallelism expression caps the global
# executor at the core count, which makes chunk generation ~4x slower on a
# 16-thread CPU. Pin it to the thread count instead.
#
# The version key must be present: C2ME rewrites this file on startup and
# drops values it cannot match against a known config version.
version = 3
globalExecutorParallelism = $C2ME_PARALLELISM
EOF
fi

# The eula has to be accepted by the operator, not by a script. Write the
# placeholder so the location is obvious; the operator flips it to true.
if [ ! -f "$SERVER_DIR/eula.txt" ]; then
    cat > "$SERVER_DIR/eula.txt" <<'EOF'
# Minecraft EULA: change to true to accept it (see https://aka.ms/MinecraftEULA)
eula=false
EOF
fi
if ! grep -q '^eula=true' "$SERVER_DIR/eula.txt"; then
    echo "note: set 'eula=true' in $SERVER_DIR/eula.txt before the server will start"
fi

# Scheduled backups must never fire mid-benchmark and overlap a FastBack
# measurement. Manual `/zrdback now` is unaffected.
if [ ! -f "$SERVER_DIR/config/zrdback.properties" ]; then
    cat > "$SERVER_DIR/config/zrdback.properties" <<'EOF'
# Benchmark config: scheduled backups must never fire mid-bench and
# overlap a fastback measurement. Manual /zrdback now is unaffected.
interval-minutes=720
EOF
fi

# Chunky keeps its task state in config/chunky/tasks; a stale task makes the
# benchmark start with a confirmation prompt.
rm -rf "$SERVER_DIR/config/chunky/tasks"
mkdir -p "$SERVER_DIR/config/chunky/tasks"

echo "server ready in $SERVER_DIR (minecraft $MC_VERSION, loader $LOADER_VERSION)"
