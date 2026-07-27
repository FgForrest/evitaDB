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
# Drives the WalReplayBenchmark dry-run main() or JMH harness against a pristine snapshot + WAL
# source pair, for whatever catalog is under investigation.
#
#   CATALOG_NAME=<name> ./run-wal-replay.sh dry   <maxTx> <logFile> [extra java opts...]
#   CATALOG_NAME=<name> ./run-wal-replay.sh jmh   <forks> <logFile> [extra java opts...]
#
# Required env var: CATALOG_NAME - both PRISTINE_DIR and WAL_SOURCE_DIRS must contain a subfolder
# with exactly this name.
#
# Everything is pinned to JDK 17 and the documented heap so runs stay comparable.
set -euo pipefail

ROOT=/www/oss/evita/evitaDB-dev
JAVA=${JAVA_BIN:-${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}/bin/java}
JAR=$ROOT/evita_test/evita_performance_tests/target/benchmarks.jar
PRISTINE=${PRISTINE_DIR:-$ROOT/backups/extracted/pristine}
WALSRC=${WAL_SOURCE_DIRS:-$ROOT/backups/extracted/walsource_full}
WORKDIR=${WORK_DIR:-$ROOT/backups/work}

if [ -z "${CATALOG_NAME:-}" ]; then
  echo "CATALOG_NAME env var is required (e.g. CATALOG_NAME=mycatalog $0 dry 300 out.log)" >&2
  exit 1
fi

MODE=$1; shift
ARG=$1; shift
LOG=$1; shift

COMMON=(
  -Xmx24g -Xms8g
  -Devita.replay.catalogName="$CATALOG_NAME"
  -Devita.replay.pristineDataDir="$PRISTINE"
  -Devita.replay.walSourceDir="$WALSRC"
  -Devita.replay.workDir="$WORKDIR"
)

case "$MODE" in
  dry)
    exec "$JAVA" "${COMMON[@]}" -Devita.replay.maxTransactions="$ARG" "$@" \
      -cp "$JAR" io.evitadb.performance.walreplay.WalReplayBenchmark 2>&1 | tee "$LOG"
    ;;
  jmh)
    exec "$JAVA" "${COMMON[@]}" "$@" \
      -cp "$JAR" org.openjdk.jmh.Main WalReplayBenchmark \
      -prof gc -f "$ARG" -wi 0 -i 1 2>&1 | tee "$LOG"
    ;;
  *)
    echo "unknown mode: $MODE" >&2
    exit 1
    ;;
esac
