#!/bin/bash

#
#
#                         _ _        ____  ____
#               _____   _(_) |_ __ _|  _ \| __ )
#              / _ \ \ / / | __/ _` | | | |  _ \
#             |  __/\ V /| | || (_| | |_| | |_) |
#              \___| \_/ |_|\__\__,_|____/|____/
#
#   Copyright (c) 2026
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

# Verifies that this checkout reads an existing evitaDB storage directory completely and correctly, at three levels:
#
#   --records   every record of every WAL and data file, read by an independent oracle and by the engine's reader
#               (ObservableInput + StorageRecord), compared payload byte for payload byte and stream offset for
#               stream offset (tools/storage-verify/StorageRecordVerifier.java)
#   --wal       every catalog write-ahead log replayed through the production reader from many start versions, with
#               every mutation deserialized (tools/storage-verify/WalReplayVerifier.java)
#   --entities  the storage opened by the engine and every entity of every collection fetched with its full content,
#               with a content digest per collection (tools/storage-verify/EntityFetchVerifier.java)
#
# Without a mode flag all three run. The source storage directory is never written to: --wal and --entities work on
# copies in the work directory, which is deleted afterwards. Full documentation, including how to compare two builds
# of the reader: documentation/developer/storage-verification.md
#
# Usage:
#   tools/verify-storage.sh --storage <dir> [--records] [--wal] [--entities] [--buffer-sizes 16384,4096]
#       [--threads N] [--wal-start-points N] [--heap 8g] [--shadow-source <File.java>]... [--work-dir <dir>]
#       [--keep-work] [--skip-build] [--offline]
#
#   --storage           the engine's storage directory (`storage.storageDirectory`) or any folder below it; --entities
#                       needs the engine's storage directory itself
#   --buffer-sizes      reader buffer sizes --records verifies every file with (default 16384,4096; 16384 is the
#                       engine's)
#   --threads           files --records verifies in parallel (default: half of the CPUs)
#   --wal-start-points  start versions --wal replays from, besides the first one (default 30)
#   --heap              heap of the verifying JVMs (default 8g; a catalog of several GB needs 24g for --entities)
#   --shadow-source     a Java source compiled in front of the checkout's classes, to verify a modified class -
#                       typically the previous version of ObservableInput - against the same data; repeatable
#   --work-dir          where copies and compiled classes go (default: a fresh temporary directory)
#   --keep-work         do not delete the work directory
#   --skip-build        reuse the compiled modules instead of running `mvn compile` first
#   --offline           run Maven offline
#
# The exit code is 0 when every selected level verified, 1 when any did not, 2 on a usage error.
#
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOL_SOURCES="${PROJECT_ROOT}/tools/storage-verify"
TOOL_PACKAGE="io.evitadb.tools.storage"
# the modules the verifiers need: the storage implementation, the file-system export the engine configuration
# requires, and the traffic engine the engine loads as a service
MODULES="evita_store/evita_store_server,evita_export/evita_export_fs,evita_store/evita_traffic_engine"
CLASS_DIRS="evita_store/evita_store_server evita_store/evita_store_key_value evita_store/evita_store_entity \
evita_engine evita_query evita_roaring_bitmap evita_common evita_api evita_export/evita_export_fs \
evita_store/evita_traffic_engine"

STORAGE=""
RUN_RECORDS=false
RUN_WAL=false
RUN_ENTITIES=false
BUFFER_SIZES="16384,4096"
THREADS=""
WAL_START_POINTS=30
HEAP="8g"
SHADOW_SOURCES=()
WORK=""
KEEP_WORK=false
SKIP_BUILD=false
MAVEN_OFFLINE=""

usage() {
	sed -n '/^# Usage:/,/^# The exit code/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
	exit 2
}

while [[ $# -gt 0 ]]; do
	case "$1" in
		--storage) STORAGE="$2"; shift 2 ;;
		--records) RUN_RECORDS=true; shift ;;
		--wal) RUN_WAL=true; shift ;;
		--entities) RUN_ENTITIES=true; shift ;;
		--buffer-sizes) BUFFER_SIZES="$2"; shift 2 ;;
		--threads) THREADS="$2"; shift 2 ;;
		--wal-start-points) WAL_START_POINTS="$2"; shift 2 ;;
		--heap) HEAP="$2"; shift 2 ;;
		--shadow-source) SHADOW_SOURCES+=("$2"); shift 2 ;;
		--work-dir) WORK="$2"; shift 2 ;;
		--keep-work) KEEP_WORK=true; shift ;;
		--skip-build) SKIP_BUILD=true; shift ;;
		--offline) MAVEN_OFFLINE="-o"; shift ;;
		-h|--help) usage ;;
		*) echo "unknown argument: $1" >&2; usage ;;
	esac
done

[[ -n "$STORAGE" ]] || usage
[[ -d "$STORAGE" ]] || { echo "no such directory: $STORAGE" >&2; exit 2; }
STORAGE="$(cd "$STORAGE" && pwd)"
if ! $RUN_RECORDS && ! $RUN_WAL && ! $RUN_ENTITIES; then
	RUN_RECORDS=true; RUN_WAL=true; RUN_ENTITIES=true
fi
for source in "${SHADOW_SOURCES[@]}"; do
	[[ -f "$source" ]] || { echo "no such file: $source" >&2; exit 2; }
done

if [[ -z "$WORK" ]]; then
	WORK="$(mktemp -d)"
else
	mkdir -p "$WORK"
	WORK="$(cd "$WORK" && pwd)"
fi
cleanup() {
	if $KEEP_WORK; then
		echo "work directory kept: $WORK"
	else
		rm -rf "$WORK"
	fi
}
trap cleanup EXIT

# --- classpath --------------------------------------------------------------------------------------------------------

cd "$PROJECT_ROOT"
if ! $SKIP_BUILD; then
	echo "compiling $MODULES (log: $WORK/build.log)"
	mvn $MAVEN_OFFLINE compile -pl "$MODULES" -am > "$WORK/build.log" 2>&1 \
		|| { echo "the build failed:" >&2; tail -n 40 "$WORK/build.log" >&2; exit 1; }
fi
# the third-party dependencies come from Maven; the evitaDB modules come from this checkout's target/classes, never
# from the local repository, which may hold a snapshot of a different checkout
mvn $MAVEN_OFFLINE -q dependency:build-classpath -pl "$MODULES" -am -Dmdep.includeScope=runtime \
	-Dmdep.outputFile="$WORK/module-classpath.txt" -Dmdep.appendOutput=true > "$WORK/classpath.log" 2>&1 \
	|| { echo "resolving the classpath failed:" >&2; tail -n 40 "$WORK/classpath.log" >&2; exit 1; }
DEPENDENCIES="$(tr ':' '\n' < "$WORK/module-classpath.txt" | grep -v '/io/evitadb/' | grep -v '^$' \
	| sort -u | paste -sd:)"
MODULE_CLASSES=""
for dir in $CLASS_DIRS; do
	[[ -d "$PROJECT_ROOT/$dir/target/classes" ]] \
		|| { echo "$dir is not compiled - run without --skip-build" >&2; exit 1; }
	MODULE_CLASSES="$MODULE_CLASSES:$PROJECT_ROOT/$dir/target/classes"
done
CLASSPATH="${MODULE_CLASSES#:}:$DEPENDENCIES"

if [[ ${#SHADOW_SOURCES[@]} -gt 0 ]]; then
	# shadow sources may use Lombok like the module they come from - it is a provided dependency, so Maven resolves
	# it separately from the runtime classpath above
	mvn $MAVEN_OFFLINE -q dependency:build-classpath -pl evita_store/evita_store_key_value \
		-Dmdep.includeScope=provided -Dmdep.outputFile="$WORK/provided-classpath.txt" > "$WORK/lombok.log" 2>&1 \
		|| { echo "resolving Lombok failed:" >&2; tail -n 40 "$WORK/lombok.log" >&2; exit 1; }
	LOMBOK_JAR="$(tr ':' '\n' < "$WORK/provided-classpath.txt" | grep '/lombok-[^/]*\.jar$' | head -1)"
	[[ -n "$LOMBOK_JAR" && -f "$LOMBOK_JAR" ]] || { echo "Lombok is not among the provided dependencies" >&2; exit 1; }
	mkdir -p "$WORK/shadow-classes"
	javac -nowarn -encoding UTF-8 -proc:full -processorpath "$LOMBOK_JAR" -cp "$CLASSPATH" \
		-d "$WORK/shadow-classes" "${SHADOW_SOURCES[@]}"
	CLASSPATH="$WORK/shadow-classes:$CLASSPATH"
	echo "shadowing: ${SHADOW_SOURCES[*]}"
fi

mkdir -p "$WORK/tool-classes"
javac -nowarn -encoding UTF-8 -cp "$CLASSPATH" -d "$WORK/tool-classes" "$TOOL_SOURCES"/*.java
CLASSPATH="$WORK/tool-classes:$CLASSPATH"

# --- verification -----------------------------------------------------------------------------------------------------

FAILED=false

if $RUN_RECORDS; then
	echo
	echo "=== records: $STORAGE"
	THREAD_OPTION=()
	[[ -n "$THREADS" ]] && THREAD_OPTION=("-Dthreads=$THREADS")
	java "-Xmx$HEAP" "${THREAD_OPTION[@]}" -cp "$CLASSPATH" "$TOOL_PACKAGE.StorageRecordVerifier" \
		"$BUFFER_SIZES" "$STORAGE" || FAILED=true
fi

if $RUN_WAL; then
	echo
	echo "=== wal: $STORAGE"
	# the verifier finds the catalog WALs itself, by the engine's own file naming, and replays each from a copy
	java "-Xmx$HEAP" -cp "$CLASSPATH" "$TOOL_PACKAGE.WalReplayVerifier" \
		"$STORAGE" "$WORK/wal" "$WAL_START_POINTS" || FAILED=true
fi

if $RUN_ENTITIES; then
	echo
	echo "=== entities: $STORAGE"
	rm -rf "$WORK/entities"
	mkdir -p "$WORK/entities/work" "$WORK/entities/export"
	cp -r "$STORAGE" "$WORK/entities/storage"
	java "-Xmx$HEAP" -cp "$CLASSPATH" "$TOOL_PACKAGE.EntityFetchVerifier" \
		"$WORK/entities/storage" "$WORK/entities/work" "$WORK/entities/export" || FAILED=true
	rm -rf "$WORK/entities"
fi

echo
if $FAILED; then
	echo "VERIFICATION FAILED"
	exit 1
else
	echo "VERIFICATION PASSED"
fi
