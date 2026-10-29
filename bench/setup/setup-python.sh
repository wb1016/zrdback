#!/usr/bin/env bash
#
# Creates the Python environment for the bench tools.
#
# The tools need numpy (palette decoding in mca_integrity.py) and a zstd
# implementation (Python 3.14's stdlib `compression.zstd`, or the
# `zstandard` package on older interpreters).
#
# Usage: bench/setup/setup-python.sh
set -euo pipefail

BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV_DIR="${VENV_DIR:-$BENCH_DIR/.venv}"
PYTHON_VERSION="${PYTHON_VERSION:-3.14}"

command -v uv >/dev/null || { echo "error: uv is required (https://docs.astral.sh/uv/)" >&2; exit 1; }

if [ ! -d "$VENV_DIR" ]; then
    echo "creating venv at $VENV_DIR (python $PYTHON_VERSION)"
    uv venv --python "$PYTHON_VERSION" "$VENV_DIR"
else
    echo "reusing venv at $VENV_DIR"
fi

echo "installing dependencies"
uv pip install --python "$VENV_DIR/bin/python" numpy

"$VENV_DIR/bin/python" - <<'PY'
import sys
import numpy

print("python  :", sys.version.split()[0])
print("numpy   :", numpy.__version__)
try:
    from compression import zstd  # Python 3.14 stdlib
    print("zstd    : stdlib compression.zstd")
except ImportError:
    try:
        import zstandard
        print("zstd    : zstandard", zstandard.__version__)
    except ImportError:
        print("zstd    : MISSING (install the 'zstandard' package)")
        raise SystemExit(1)
PY

echo
echo "environment ready. Use it with:"
echo "  $VENV_DIR/bin/python bench/tools/mca_integrity.py --help"
