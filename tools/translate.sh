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

# Regenerates the Czech mirror under documentation/user/cs from the English source under
# documentation/user/en, by running the Comenius translation plugin on the root project.
# Usage: ./translate.sh
#
# Requires OPENAI_API_KEY in the environment - the root pom passes it to the plugin as
# ${env.OPENAI_API_KEY}, and an unset variable reaches OpenAI as a literal token and comes
# back as an opaque authorization failure, so the variable is checked here first.
#
# Scope: the plugin re-translates EVERY English file whose commit recorded in its Czech
# counterpart's front matter is no longer the English file's current commit - not just the
# file you last edited. Run it when a Czech sync is actually wanted, and expect files
# unrelated to your change to move with it. To see what a run would cover beforehand:
#
#   for cs in $(find documentation/user/cs -name '*.md'); do \
#     en="documentation/user/en/${cs#documentation/user/cs/}"; \
#     rec=$(sed -n "s/^commit: *'\{0,1\}\([0-9a-f]\{7,40\}\)'\{0,1\} *$/\1/p" "$cs" | head -1); \
#     [ "$rec" = "$(git log -1 --format=%H -- "$en")" ] || echo "$en"; \
#   done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_ROOT" || { echo "Failed to change to project root: $PROJECT_ROOT"; exit 1; }

if [ -z "${OPENAI_API_KEY:-}" ]; then
	echo "Error: OPENAI_API_KEY is not set."
	echo "The root pom hands it to the Comenius plugin as \${env.OPENAI_API_KEY}; without it every"
	echo "translation request fails authorization. Export it and re-run:"
	echo "    export OPENAI_API_KEY=sk-..."
	exit 1
fi

if ! mvn -N comenius:run -Dcomenius.action=translate; then
	echo
	echo "Translation failed. Check that OPENAI_API_KEY is valid and that the account has credit;"
	echo "the plugin reports an authorization failure the same way it reports a missing token."
	exit 1
fi
