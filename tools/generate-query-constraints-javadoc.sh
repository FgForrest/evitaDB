#!/bin/bash
set -euo pipefail

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

# Generates concise JavaDoc summaries for QueryConstraints factory methods
# using OpenAI API. Requires OPENAI_API_KEY environment variable.
# Usage: ./generate-query-constraints-javadoc.sh [--limit=N]
#
# The generator (io.evitadb.documentation.javadoc.JavaDocSummarizer) lives in
# evita_test/evita_documentation_tests, which is also the only module declaring the
# `generate-javadoc` profile supplying its mainClass and test classpath scope - hence
# the -pl below. The module is built alone, without -am: a goal named on the command
# line runs for every module in the reactor, so dragging the upstream modules in would
# invoke exec:java on each of them too, none of which configures a mainClass.
#
# Prerequisite: the io.evitadb SNAPSHOT artifacts this module depends on must already be
# in the local repository - run `mvn install -DskipTests` from the project root once
# before the first use. The generator itself parses the constraint *sources* from disk,
# so a stale install does not affect the summaries it produces.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_ROOT" || { echo "Failed to change to project root: $PROJECT_ROOT"; exit 1; }

# Retrieve from GNOME Keyring if not already set
if [ -z "${OPENAI_API_KEY:-}" ]; then
	if command -v secret-tool &>/dev/null; then
		# Write secret-tool output to a temp file to avoid subshell issues with keyring sessions
		_tmp="$(mktemp)"
		trap 'rm -f "$_tmp"' EXIT
		if secret-tool lookup service openai key api_key > "$_tmp" 2>/dev/null; then
			OPENAI_API_KEY="$(cat "$_tmp")"
			export OPENAI_API_KEY
		fi
		rm -f "$_tmp"
	fi
fi

if [ -z "${OPENAI_API_KEY:-}" ]; then
	echo "Error: OPENAI_API_KEY not found in environment or GNOME Keyring."
	echo "Either export it: export OPENAI_API_KEY=sk-..."
	echo "Or store it:      secret-tool store --label='OpenAI API key' service openai key api_key"
	exit 1
fi

if ! mvn -pl evita_test/evita_documentation_tests test-compile exec:java -Pgenerate-javadoc -q -Dexec.args="$*"; then
	echo
	echo "Maven failed. If it could not resolve io.evitadb:*:*-SNAPSHOT artifacts, the modules this"
	echo "generator depends on are missing from your local repository - run 'mvn install -DskipTests'"
	echo "from the project root first, then re-run this script."
	exit 1
fi
