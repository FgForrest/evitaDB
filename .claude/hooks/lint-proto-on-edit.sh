#!/bin/bash

# PostToolUse hook: runs `buf lint` immediately after Claude edits or writes a .proto file, so
# comment-coverage violations (.claude/rules/proto-documentation.md) surface within the same turn
# instead of waiting for CI (tools/lint-proto.sh, wired into .github/workflows/ci-dev.yml). Reuses
# that same script/buf.yaml/Docker image rather than duplicating the buf invocation.
#
# The hook is registered in .claude/settings.json against every Edit/Write with no per-hook path
# filter, so the .proto suffix check below is the only thing scoping it - a deliberate choice, since
# a path filter that silently fails to match would leave the hook installed but dead.
#
# The linter itself is a soft dependency: tools/lint-proto.sh prefers a native `buf` and falls back
# to Docker, and signals "neither available" with exit 3 - which this hook reports and passes on.
# Only actual buf violations block, so a contributor with neither installed can still edit .proto
# files (CI remains the hard gate).

set -euo pipefail

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"

INPUT="$(cat)"
FILE_PATH="$(printf '%s' "${INPUT}" | jq -r '.tool_input.file_path // empty' 2>/dev/null || true)"

[[ "${FILE_PATH}" == *.proto ]] || exit 0

set +e
LINT_OUTPUT="$("${PROJECT_DIR}/tools/lint-proto.sh" 2>&1)"
LINT_EXIT=$?
set -e

# tools/lint-proto.sh reserves exit 3 for "no linter available" (no buf on PATH and no reachable
# Docker daemon) - that is a missing toolchain, not a violation, so it must never block an edit
if [[ ${LINT_EXIT} -eq 3 ]]; then
	echo "note: skipping buf lint for ${FILE_PATH} - no linter available" >&2
	echo "${LINT_OUTPUT}" >&2
	exit 0
fi

if [[ ${LINT_EXIT} -ne 0 ]]; then
	echo "buf lint failed after editing ${FILE_PATH} - fix the violation(s) below before continuing:" >&2
	echo "${LINT_OUTPUT}" >&2
	exit 2
fi

exit 0
