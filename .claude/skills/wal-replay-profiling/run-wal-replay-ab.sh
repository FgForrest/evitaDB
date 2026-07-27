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
# Runs the WalReplayBenchmark JMH harness for ONE side of an A/B comparison, for whatever catalog
# is under investigation.
#
#   CATALOG_NAME=<name> ./run-wal-replay-ab.sh <side-label> <path-to-benchmarks.jar> <forks> <logfile>
#
# Both sides read the same pristine snapshot (the fixture copies it into a side-private work dir),
# but each side gets its OWN copy of the WAL source: the fixture opens it as a live
# CatalogWriteAheadLog, which can rotate/purge, so a shared source risks feeding the second side a
# different workload than the first - and risks damaging the captured dataset. Flags are pinned
# identically so the only difference between sides is the jar.
#
# Required env var: CATALOG_NAME. Expects backups/walsrc-<label> to already hold a per-side copy
# of the WAL source (verify with `diff -rq` against the pristine WAL source before trusting a run -
# a chewed-up source from a previous pairing produces a clean-looking but meaningless number).
set -euo pipefail

LABEL=$1
JAR=$2
FORKS=$3
LOG=$4

JAVA=${JAVA_BIN:-${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}/bin/java}
MAIN=/www/oss/evita/evitaDB-dev
PRISTINE=$MAIN/backups/extracted/pristine
WALSRC=$MAIN/backups/walsrc-$LABEL
WORKDIR=$MAIN/backups/work-$LABEL

if [ -z "${CATALOG_NAME:-}" ]; then
  echo "CATALOG_NAME env var is required (e.g. CATALOG_NAME=mycatalog $0 before jar.jar 1 out.log)" >&2
  exit 1
fi

if [ ! -d "$WALSRC" ]; then
  echo "missing per-side WAL source copy: $WALSRC" >&2
  exit 1
fi

echo "=== catalog=$CATALOG_NAME side=$LABEL jar=$JAR forks=$FORKS workdir=$WORKDIR"
"$JAVA" \
  -Xmx24g -Xms8g \
  -Devita.replay.catalogName="$CATALOG_NAME" \
  -Devita.replay.pristineDataDir="$PRISTINE" \
  -Devita.replay.walSourceDir="$WALSRC" \
  -Devita.replay.workDir="$WORKDIR" \
  -cp "$JAR" org.openjdk.jmh.Main WalReplayBenchmark \
  -prof gc -f "$FORKS" -wi 0 -i 1 2>&1 | tee "$LOG"
