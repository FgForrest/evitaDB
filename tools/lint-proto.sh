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
# Prefers a natively installed `buf` on PATH (fastest - no container start), and falls back to the
# official `bufbuild/buf` Docker image when there isn't one, matching the pattern of the other
# schema-diff scripts in this directory (diff-openapi-schemas.sh, diff-graphql-schemas.sh). Neither
# is mandatory, but at least one must be present.
#
# Install buf natively with any of:
#   curl -fsSL -o ~/.local/bin/buf \
#     https://github.com/bufbuild/buf/releases/download/v1.72.0/buf-Linux-x86_64 && chmod +x ~/.local/bin/buf
#   npm install -g @bufbuild/buf
#   brew install bufbuild/buf/buf
#   go install github.com/bufbuild/buf/cmd/buf@v1.72.0
# Keep the version matched to DOCKER_IMAGE below so local runs and CI cannot disagree; the script
# warns when they differ.
#
# Exit codes: 0 = clean, 3 = no linter available at all (neither buf nor a reachable Docker daemon),
# anything else = buf's own exit code, i.e. lint violations were found.
#
# Works on Linux and macOS. Requires: bash, and either buf or docker.

set -euo pipefail

# ============================================================================
# Configuration (overridable via environment variables)
# ============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
MODULE_DIR="${MODULE_DIR:-${PROJECT_DIR}/evita_external_api/evita_external_api_grpc/shared}"

DOCKER_IMAGE="${DOCKER_IMAGE:-bufbuild/buf:1.72.0}"

# Explicit path to a `buf` binary. When unset, any `buf` on PATH is used; set FORCE_DOCKER=1 to skip
# the native binary entirely and always go through the container.
BUF_BIN="${BUF_BIN:-}"
FORCE_DOCKER="${FORCE_DOCKER:-0}"

# Exit code reserved for "no linter available", so callers (the PostToolUse hook) can tell a missing
# toolchain apart from an actual lint violation.
readonly NO_LINTER_EXIT=3

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

Runs a natively installed 'buf' when one is on PATH, otherwise falls back to the ${DOCKER_IMAGE}
container. Exits ${NO_LINTER_EXIT} when neither is available.

Environment overrides: MODULE_DIR, DOCKER_IMAGE, BUF_BIN, FORCE_DOCKER.
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

if [[ ! -f "${MODULE_DIR}/buf.yaml" ]]; then
	echo "error: no buf.yaml found in ${MODULE_DIR}" >&2
	exit 1
fi

# resolve a native buf unless the caller pinned one or forced the container path
if [[ "${FORCE_DOCKER}" != "1" && -z "${BUF_BIN}" ]] && command -v buf >/dev/null 2>&1; then
	BUF_BIN="$(command -v buf)"
fi

# a docker binary on PATH is not enough - with the daemon stopped, `docker run` fails with a
# connection error that is indistinguishable from a lint violation by exit code alone
DOCKER_AVAILABLE=0
if [[ -z "${BUF_BIN}" ]] && command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
	DOCKER_AVAILABLE=1
fi

if [[ -z "${BUF_BIN}" && "${DOCKER_AVAILABLE}" -eq 0 ]]; then
	echo "error: no linter available - install buf (https://buf.build/docs/cli/installation)" >&2
	echo "       or start a Docker daemon so ${DOCKER_IMAGE} can be used instead" >&2
	exit "${NO_LINTER_EXIT}"
fi

# ============================================================================
# Run buf lint
# ============================================================================

set +e
if [[ -n "${BUF_BIN}" ]]; then
	# warn rather than fail on a version skew - a mismatched local buf still lints usefully, it just
	# may not agree with CI, and hard-failing would punish contributors for a newer install
	PINNED_VERSION="${DOCKER_IMAGE##*:}"
	LOCAL_VERSION="$("${BUF_BIN}" --version 2>/dev/null || echo unknown)"
	if [[ "${LOCAL_VERSION}" != "${PINNED_VERSION}" ]]; then
		echo "warning: local buf ${LOCAL_VERSION} differs from the pinned ${PINNED_VERSION}" >&2
		echo "         (CI uses ${DOCKER_IMAGE}; results may diverge)" >&2
	fi

	echo "==> Linting ${MODULE_DIR} (native buf ${LOCAL_VERSION}: ${BUF_BIN})"
	(cd "${MODULE_DIR}" && "${BUF_BIN}" lint)
	LINT_EXIT=$?
else
	echo "==> Linting ${MODULE_DIR} (docker image: ${DOCKER_IMAGE})"
	docker run --rm \
		--volume "${MODULE_DIR}:/workspace:ro" \
		--workdir /workspace \
		"${DOCKER_IMAGE}" \
		lint
	LINT_EXIT=$?
fi
set -e

echo "==> buf lint exit code: ${LINT_EXIT} (non-zero means lint violations were found)"

exit "${LINT_EXIT}"
