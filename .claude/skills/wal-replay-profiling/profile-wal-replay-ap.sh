#!/usr/bin/env bash
#
#                         _ _        ____  ____
#               _____   _(_) |_ __ _|  _ \| __ )
#              / _ \ \ / / | __/ _` | | | |  _ \
#             |  __/\ V /| | || (_| | |_| | |_) |
#              \___| \_/ |_|\__\__,_|____/|____/
#
#   Copyright (c) 2023-2026
#
#   Licensed under the Business Source License, Version 1.1 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.
#
# Runs the WAL-replay dry-run for whatever catalog is under investigation and attaches
# async-profiler (via ap-loader) for the replay window ONLY (boot + WAL recovery would otherwise
# dominate and pollute the attribution).
#
#   CATALOG_NAME=<name> ./profile-wal-replay-ap.sh <event: wall|alloc|cpu|itimer> <maxTx> <tag> [extra -D opts...]
#
# Required env var: CATALOG_NAME - both PRISTINE_DIR and WAL_SOURCE_DIRS must contain a subfolder
# with exactly this name.
#
# Produces target/profiling/wal-replay/<tag>-<event>.collapsed plus a GC log and run log.
set -euo pipefail

ROOT=/www/oss/evita/evitaDB-dev
JAVA=${JAVA_BIN:-${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}/bin/java}
JAR=$ROOT/evita_test/evita_performance_tests/target/benchmarks.jar
AP=$HOME/.m2/repository/me/bechberger/ap-loader-all/4.0-10/ap-loader-all-4.0-10.jar
OUT=$ROOT/evita_test/evita_performance_tests/target/profiling/wal-replay
PRISTINE=${PRISTINE_DIR:-$ROOT/backups/extracted/pristine}
WALSRC=${WAL_SOURCE_DIRS:-$ROOT/backups/extracted/walsource_full}
WORKDIR=${WORK_DIR:-$ROOT/backups/work}

if [ -z "${CATALOG_NAME:-}" ]; then
  echo "CATALOG_NAME env var is required (e.g. CATALOG_NAME=mycatalog $0 alloc 300 mytag)" >&2
  exit 1
fi

EVENT=$1; shift
MAXTX=$1; shift
TAG=$1; shift

mkdir -p "$OUT"
RUNLOG=$OUT/$TAG-$EVENT.log
GCLOG=$OUT/$TAG-$EVENT.gc.log
COLLAPSED=$OUT/$TAG-$EVENT.collapsed
rm -f "$RUNLOG" "$GCLOG" "$COLLAPSED"

"$JAVA" -Xmx24g -Xms8g \
  "-Xlog:gc*:file=$GCLOG:time,uptime:filecount=0" \
  -Devita.replay.catalogName="$CATALOG_NAME" \
  -Devita.replay.pristineDataDir="$PRISTINE" \
  -Devita.replay.walSourceDir="$WALSRC" \
  -Devita.replay.workDir="$WORKDIR" \
  -Devita.replay.maxTransactions="$MAXTX" \
  -Devita.replay.holdOpenSeconds="${HOLD_OPEN_SECONDS:-90}" \
  "$@" \
  -cp "$JAR" io.evitadb.performance.walreplay.WalReplayBenchmark > "$RUNLOG" 2>&1 &
PID=$!
echo "benchmark pid=$PID, run log=$RUNLOG"

# the harness logs this exactly when the measured replay window opens
until grep -q "Opening source WAL" "$RUNLOG" 2>/dev/null; do
  if ! kill -0 $PID 2>/dev/null; then echo "JVM died before replay started" >&2; tail -20 "$RUNLOG" >&2; exit 1; fi
  sleep 1
done
echo "replay window opened - starting $EVENT profiler"
# -t keeps samples split per thread so replay-thread vs trunk-thread attribution stays sound
"$JAVA" -jar "$AP" profiler start -t -e "$EVENT" -o collapsed -f "$COLLAPSED" "$PID"

# ... and this when it closes. The benchmark then holds the JVM open (holdOpenSeconds above) purely so the
# stop below can still reach it - without that hold it exits within a few seconds of printing the result and
# the dump is lost silently, leaving a zero-byte file behind while every exit code still says success.
until grep -q "WAL REPLAY RESULT" "$RUNLOG" 2>/dev/null; do
  if ! kill -0 $PID 2>/dev/null; then echo "JVM died during replay" >&2; break; fi
  sleep 1
done
echo "replay window closed - stopping profiler"
# repeat the format + target file on stop: passing them only on `start` has been observed to dump the
# profile to stdout instead of writing the file, which silently loses the run
"$JAVA" -jar "$AP" profiler stop -o collapsed -f "$COLLAPSED" "$PID" || true
wait $PID || true
# the dump is the whole point of the run - refuse to report success on an empty one
if [ ! -s "$COLLAPSED" ]; then
  echo "PROFILE DUMP IS EMPTY - the profiler could not write $COLLAPSED" >&2
  exit 2
fi
echo "collapsed profile: $COLLAPSED"
grep -A 12 "WAL REPLAY RESULT" "$RUNLOG" | head -20 || true
