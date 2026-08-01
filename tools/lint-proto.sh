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

# Lints the gRPC shared module's .proto files with `buf lint` (config:
# evita_external_api/evita_external_api_grpc/shared/buf.yaml). That file is kept at the Maven
# module root, NOT under src/main/resources - everything under that tree is packaged as-is into
# the shipped JAR (alongside the .proto sources themselves, for downstream non-Java clients), and
# a lint config has no business shipping to consumers of the JAR. `buf.yaml`'s own `modules[].path`
# points at the actual proto directory a few levels down.
#
# Uses the official `bufbuild/buf` Docker image so a local `buf` install isn't required, matching
# the pattern of the other schema-diff scripts in this directory (diff-openapi-schemas.sh,
# diff-graphql-schemas.sh).
#
# Works on Linux and macOS. Requires: bash, docker.

set -euo pipefail

# ============================================================================
# Configuration (overridable via environment variables)
# ============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
MODULE_DIR="${MODULE_DIR:-${PROJECT_DIR}/evita_external_api/evita_external_api_grpc/shared}"

DOCKER_IMAGE="${DOCKER_IMAGE:-bufbuild/buf:1.72.0}"

# ============================================================================
# CLI parsing
# ============================================================================

usage() {
	cat <<EOF
Usage: $(basename "$0") [--module-dir DIR]

Runs 'buf lint' against the gRPC shared module, using the buf.yaml at its root (which in turn
points at the actual .proto directory a few levels down via modules[].path).

Options:
  --module-dir DIR   Maven module directory containing buf.yaml.
                      Defaults to: ${MODULE_DIR}
  -h, --help          Show this help.

Environment overrides: MODULE_DIR, DOCKER_IMAGE.
EOF
}

while [[ $# -gt 0 ]]; do
	case "$1" in
		--module-dir) MODULE_DIR="$2"; shift 2 ;;
		-h|--help)    usage; exit 0 ;;
		*) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
	esac
done

# ============================================================================
# Prerequisite checks
# ============================================================================

if ! command -v docker >/dev/null 2>&1; then
	echo "error: required command 'docker' is not available on PATH" >&2
	echo "       (alternatively, install buf locally: https://buf.build/docs/cli/installation)" >&2
	exit 1
fi

if [[ ! -f "${MODULE_DIR}/buf.yaml" ]]; then
	echo "error: no buf.yaml found in ${MODULE_DIR}" >&2
	exit 1
fi

# ============================================================================
# Run buf lint via Docker
# ============================================================================

echo "==> Linting ${MODULE_DIR} (docker image: ${DOCKER_IMAGE})"

set +e
docker run --rm \
	--volume "${MODULE_DIR}:/workspace:ro" \
	--workdir /workspace \
	"${DOCKER_IMAGE}" \
	lint
LINT_EXIT=$?
set -e

echo "==> buf lint exit code: ${LINT_EXIT} (non-zero means lint violations were found)"

exit "${LINT_EXIT}"
