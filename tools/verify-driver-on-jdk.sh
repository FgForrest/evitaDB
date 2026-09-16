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

# Proves the Java driver runs on the oldest JDK it supports. Starts an evitaDB server from
# `evita-server.jar` on the JDK the server requires (21), then compiles and runs
# tools/driver-smoke/DriverSmoke.java against the shaded all-in-one driver jar on another JDK
# (17 by default) - a client with nothing but the driver on its classpath and no build tooling.
# The smoke creates a catalog, defines a schema, upserts entities, goes live, reads them back and
# drops the catalog; each step prints a SMOKE line, so a failure shows how far it got.
#
# The server listens on the default port 5555 with the default (generated) certificate, which the
# driver downloads through the system API - exactly what a fresh client installation does.
#
# Usage:
#   tools/verify-driver-on-jdk.sh --server-jar <evita-server.jar> --driver-jar <all-in-one.jar> \
#       --client-java-home <JDK dir> [--server-java-home <JDK dir>] [--expect-java <feature>]
#
#   --server-java-home defaults to $JAVA_HOME, --expect-java to 17. The smoke fails when the client
#   JVM's feature version differs from --expect-java, so it cannot silently run on the build JDK.
#   The exit code is the smoke's; on failure the tail of the server log is printed.
#
# CI: .github/actions/verify-driver-on-jdk17 runs this after the build job has uploaded both jars.
#
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SMOKE_SOURCE="${PROJECT_ROOT}/tools/driver-smoke/DriverSmoke.java"
SMOKE_CLASS="io.evitadb.tools.smoke.DriverSmoke"
PORT=5555

SERVER_JAR=""
DRIVER_JAR=""
CLIENT_JAVA_HOME=""
SERVER_JAVA_HOME="${JAVA_HOME:-}"
EXPECT_JAVA=17

usage() {
	sed -n '/^# Usage:/,/^# CI:/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
	exit 2
}

while [[ $# -gt 0 ]]; do
	case "$1" in
		--server-jar) SERVER_JAR="$2"; shift 2 ;;
		--driver-jar) DRIVER_JAR="$2"; shift 2 ;;
		--client-java-home) CLIENT_JAVA_HOME="$2"; shift 2 ;;
		--server-java-home) SERVER_JAVA_HOME="$2"; shift 2 ;;
		--expect-java) EXPECT_JAVA="$2"; shift 2 ;;
		*) echo "unknown argument: $1" >&2; usage ;;
	esac
done

[[ -n "$SERVER_JAR" && -n "$DRIVER_JAR" && -n "$CLIENT_JAVA_HOME" ]] || usage
[[ -f "$SERVER_JAR" ]] || { echo "no such file: $SERVER_JAR" >&2; exit 1; }
[[ -f "$DRIVER_JAR" ]] || { echo "no such file: $DRIVER_JAR" >&2; exit 1; }
[[ -f "$SMOKE_SOURCE" ]] || { echo "no such file: $SMOKE_SOURCE" >&2; exit 1; }
[[ -n "$SERVER_JAVA_HOME" ]] || { echo "--server-java-home not given and JAVA_HOME is unset" >&2; exit 1; }
[[ -x "$CLIENT_JAVA_HOME/bin/javac" ]] || { echo "no javac under $CLIENT_JAVA_HOME" >&2; exit 1; }
[[ -x "$SERVER_JAVA_HOME/bin/java" ]] || { echo "no java under $SERVER_JAVA_HOME" >&2; exit 1; }

# absolute paths, the server runs from the working directory below
SERVER_JAR="$(cd "$(dirname "$SERVER_JAR")" && pwd)/$(basename "$SERVER_JAR")"
DRIVER_JAR="$(cd "$(dirname "$DRIVER_JAR")" && pwd)/$(basename "$DRIVER_JAR")"

WORK="$(mktemp -d)"
SERVER_PID=""
cleanup() {
	if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
		kill "$SERVER_PID" 2>/dev/null || true
		wait "$SERVER_PID" 2>/dev/null || true
	fi
	rm -rf "$WORK"
}
trap cleanup EXIT

echo "server JDK: $("$SERVER_JAVA_HOME/bin/java" -version 2>&1 | head -1) [$SERVER_JAVA_HOME]"
echo "client JDK: $("$CLIENT_JAVA_HOME/bin/java" -version 2>&1 | head -1) [$CLIENT_JAVA_HOME]"

# the server keeps its generated certificate relative to the working directory, so run it from a
# throwaway one; nothing of this run survives the script
mkdir -p "$WORK/server" "$WORK/classes"
(
	cd "$WORK/server" \
		&& exec "$SERVER_JAVA_HOME/bin/java" -Xmx2g -jar "$SERVER_JAR" "storage.storageDirectory=$WORK/server/data"
) > "$WORK/server.log" 2>&1 &
SERVER_PID=$!
echo "server started (pid $SERVER_PID), log: $WORK/server.log"

# -encoding: JDK 17's javac still defaults to the platform charset (UTF-8 became the default in 18)
"$CLIENT_JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$DRIVER_JAR" -d "$WORK/classes" "$SMOKE_SOURCE"
echo "smoke compiled with $("$CLIENT_JAVA_HOME/bin/javac" -version 2>&1)"

if ! kill -0 "$SERVER_PID" 2>/dev/null; then
	echo "server exited before the smoke could start:" >&2
	tail -n 60 "$WORK/server.log" >&2
	exit 1
fi

set +e
timeout 300 "$CLIENT_JAVA_HOME/bin/java" -cp "$WORK/classes:$DRIVER_JAR" "$SMOKE_CLASS" localhost "$PORT" "$EXPECT_JAVA"
SMOKE_EXIT=$?
set -e

if [[ $SMOKE_EXIT -ne 0 ]]; then
	echo "smoke failed with exit code $SMOKE_EXIT; server log tail:" >&2
	tail -n 60 "$WORK/server.log" >&2
fi
exit $SMOKE_EXIT
