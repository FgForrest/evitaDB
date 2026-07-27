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

# Download the OpenAPI schema from the public evitaDB demo and a local evitaDB
# instance and run openapi-diff (via Docker) to produce a diff between them.
# The diff is written to a file inside the project tree so it can be reviewed
# or attached to a pull request.
#
# Works on Linux and macOS. Requires: bash, curl, docker.

set -euo pipefail

# ============================================================================
# Configuration (overridable via environment variables or CLI flags)
# ============================================================================

ORIGINAL_URL="${ORIGINAL_URL:-https://demo.evitadb.io/rest/evita}"
NEW_URL="${NEW_URL:-https://localhost:5555/rest/evita}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
OUTPUT_FILE="${OUTPUT_FILE:-${PROJECT_DIR}/openapi-schema-diff.txt}"

DOCKER_IMAGE="openapitools/openapi-diff:latest"

# ============================================================================
# CLI parsing
# ============================================================================

usage() {
	cat <<EOF
Usage: $(basename "$0") [--original URL] [--new URL] [--output FILE]

Downloads two OpenAPI schemas and writes an openapi-diff report to a file.

Options:
  --original URL   URL of the original (baseline) schema endpoint.
                   Defaults to: ${ORIGINAL_URL}
  --new URL        URL of the new schema endpoint.
                   Defaults to: ${NEW_URL}
  --output FILE    Path where the diff output is written.
                   Defaults to: ${OUTPUT_FILE}
  -h, --help       Show this help.

Environment overrides: ORIGINAL_URL, NEW_URL, OUTPUT_FILE.
EOF
}

while [[ $# -gt 0 ]]; do
	case "$1" in
		--original) ORIGINAL_URL="$2"; shift 2 ;;
		--new)      NEW_URL="$2";      shift 2 ;;
		--output)   OUTPUT_FILE="$2";  shift 2 ;;
		-h|--help)  usage; exit 0 ;;
		*) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
	esac
done

# ============================================================================
# Prerequisite checks
# ============================================================================

for cmd in curl docker; do
	if ! command -v "$cmd" >/dev/null 2>&1; then
		echo "error: required command '$cmd' is not available on PATH" >&2
		exit 1
	fi
done

# ============================================================================
# Prepare a temporary working directory that Docker can mount
# ============================================================================

TMP_DIR="$(mktemp -d 2>/dev/null || mktemp -d -t 'evita-openapi-diff')"
trap 'rm -rf "${TMP_DIR}"' EXIT

ORIGINAL_FILE="${TMP_DIR}/original.json"
NEW_FILE="${TMP_DIR}/new.json"

# ============================================================================
# Download both schemas
# ============================================================================

download_schema() {
	local url="$1"
	local dest="$2"
	echo "==> Downloading schema from ${url}"
	# -k tolerates self-signed certificates (local dev typically uses one);
	# --fail-with-body prints server errors instead of a silent empty file.
	if ! curl --silent --show-error --fail-with-body --location \
	          --insecure \
	          --header 'Accept: application/json' \
	          --output "$dest" \
	          "$url"; then
		echo "error: failed to download ${url}" >&2
		exit 1
	fi
	if [[ ! -s "$dest" ]]; then
		echo "error: downloaded schema at ${dest} is empty" >&2
		exit 1
	fi
}

download_schema "${ORIGINAL_URL}" "${ORIGINAL_FILE}"
download_schema "${NEW_URL}"      "${NEW_FILE}"

# ============================================================================
# Run openapi-diff via Docker and capture output
# ============================================================================

echo "==> Running openapi-diff (docker image: ${DOCKER_IMAGE})"

set +e
docker run --rm -t \
	-v "${TMP_DIR}:/specs:ro" \
	"${DOCKER_IMAGE}" \
	--fail-on-incompatible /specs/original.json /specs/new.json \
	>"${OUTPUT_FILE}" 2>&1
DIFF_EXIT=$?
set -e

echo "==> Diff written to ${OUTPUT_FILE}"
echo "==> openapi-diff exit code: ${DIFF_EXIT} (non-zero means incompatible changes were found)"

# openapi-diff returns a non-zero exit code (with --fail-on-incompatible) when
# it finds breaking changes — that is the normal, successful outcome of the
# diff, not a script failure. We always exit 0 here; callers should inspect
# OUTPUT_FILE for the findings.
exit 0
