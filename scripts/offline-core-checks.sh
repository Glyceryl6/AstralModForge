#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/build/offline-check"
mkdir -p "$OUT/main" "$OUT/test"
find "$ROOT/src/main/java/dev/astral/modforge/core" "$ROOT/src/main/java/dev/astral/modforge/cli" -name '*.java' -print0 |
  xargs -0 javac -encoding UTF-8 --release 21 -d "$OUT/main"
javac -encoding UTF-8 --release 21 -cp "$OUT/main" -d "$OUT/test" \
  "$ROOT/src/test/java/dev/astral/modforge/core/CoreChecks.java"
java -cp "$OUT/main:$OUT/test" dev.astral.modforge.core.CoreChecks
