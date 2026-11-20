#!/usr/bin/env bash
# Compiles the pure-Kotlin core + the tests and runs them on the JVM (no Android needed).
# Usage: KOTLINC=/path/to/kotlinc tools/run-sim.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
"$KOTLINC" "$HERE"/../app/src/main/java/com/lightchat/core/*.kt "$HERE/SimTest.kt" "$HERE/MsgTest.kt" "$HERE/E2ETest.kt" "$HERE/GroupTest.kt" -include-runtime -d "$OUT/sim.jar"
java -cp "$OUT/sim.jar" MsgTestKt
java -cp "$OUT/sim.jar" SimTestKt
java -cp "$OUT/sim.jar" E2ETestKt
java -cp "$OUT/sim.jar" GroupTestKt
