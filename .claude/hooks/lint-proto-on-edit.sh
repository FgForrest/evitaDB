#!/bin/bash

# PostToolUse hook: runs `buf lint` immediately after Claude edits or writes a .proto file, so
# comment-coverage violations (.claude/rules/proto-documentation.md) surface within the same turn
# instead of waiting for CI (tools/lint-proto.sh, wired into .github/workflows/ci-dev.yml). Reuses
# that same script/buf.yaml/Docker image rather than duplicating the buf invocation.
#
# The `if` filter on the hook entries in .claude/settings.json already restricts invocation to
# Edit(*.proto)/Write(*.proto) calls; the suffix check below is a cheap belt-and-braces fallback in
# case that filter is ever loosened.

set -euo pipefail

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"

INPUT="$(cat)"
FILE_PATH="$(printf '%s' "${INPUT}" | jq -r '.tool_input.file_path // empty' 2>/dev/null || true)"

[[ "${FILE_PATH}" == *.proto ]] || exit 0

if ! command -v docker >/dev/null 2>&1; then
	echo "note: skipping buf lint for ${FILE_PATH} - docker not available on PATH" >&2
	exit 0
fi

set +e
LINT_OUTPUT="$("${PROJECT_DIR}/tools/lint-proto.sh" 2>&1)"
LINT_EXIT=$?
set -e

if [[ ${LINT_EXIT} -ne 0 ]]; then
	echo "buf lint failed after editing ${FILE_PATH} - fix the violation(s) below before continuing:" >&2
	echo "${LINT_OUTPUT}" >&2
	exit 2
fi

exit 0
