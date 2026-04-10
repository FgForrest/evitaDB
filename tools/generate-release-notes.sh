#!/bin/bash

#
#
#                         _ _        ____  ____
#               _____   _(_) |_ __ _|  _ \| __ )
#              / _ \ \ / / | __/ _` | | | |  _ \
#             |  __/\ V /| | || (_| | |_| | |_) |
#              \___| \_/ |_|\__\__,_|____/|____/
#
#   Copyright (c) 2025
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

# Generate the deterministic skeleton of release notes for an evitaDB release.
#
# Combines tools/list-issues.sh (milestone/issue based, used for MAJOR releases)
# and tools/list-commits.sh (conventional-commit based, used for PATCH releases
# and to supplement the issue list for MAJORs) into a single markdown body
# matching the established release-notes format. Always appends the GitHub
# "Full Changelog" compare trailer.
#
# This script is intentionally LLM-free; it produces the structural skeleton
# that the .claude/skills/release-notes skill then enriches with prose
# descriptions for the user-facing items.
#
# Usage:
#   ./generate-release-notes.sh --version vX.Y.Z [--base vA.B.C] [--milestone X.Y]
#
# --version    Required. The release being prepared (e.g. v2026.1.3 or v2026.1.0).
# --base       Optional. Previous release tag to compare against. If omitted,
#              the script picks the highest v* tag strictly less than --version
#              by listing matching tags and version-sorting them with `sort -V`.
# --milestone  Optional. GitHub milestone title (e.g. "2026.1"). When provided
#              and the milestone exists, the script runs in MAJOR mode and pulls
#              issues from list-issues.sh + supplements with commits. When
#              omitted (or the milestone does not exist), the script runs in
#              PATCH mode and uses commits only.

set -e
set -o pipefail

REPO="FgForrest/evitaDB"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

VERSION=""
BASE=""
MILESTONE=""

# Parse named arguments
while [ $# -gt 0 ]; do
  case "$1" in
    --version)
      VERSION="$2"
      shift 2
      ;;
    --base)
      BASE="$2"
      shift 2
      ;;
    --milestone)
      MILESTONE="$2"
      shift 2
      ;;
    -h|--help)
      sed -n '/^# Usage:/,/^# omitted/p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

if [ -z "$VERSION" ]; then
  echo "Error: --version is required (e.g. --version v2026.1.3)" >&2
  exit 1
fi

# Normalize VERSION to start with 'v'
case "$VERSION" in
  v*) ;;
  *) VERSION="v$VERSION" ;;
esac

# Auto-resolve --base if not provided: pick the highest v* tag that is
# strictly less than the version being released, using semantic version sort.
# We append the target version into the candidate list and read the line
# immediately before it after `sort -V`, so this works whether or not the
# target version has already been tagged in the repository.
if [ -z "$BASE" ]; then
  BASE=$( (git tag -l 'v*'; printf "%s\n" "$VERSION") \
    | sort -V -u \
    | awk -v v="$VERSION" 'BEGIN { prev = "" }
                            $0 == v { print prev; exit }
                            { prev = $0 }' )
  if [ -z "$BASE" ]; then
    echo "Error: could not auto-detect previous release tag (no v* tag earlier than $VERSION)." >&2
    echo "       Provide --base explicitly." >&2
    exit 1
  fi
  echo "Resolved --base to $BASE" >&2
fi

# Strip the leading 'v' from versions for the helper scripts (they expect
# bare semver). Keep the v-prefixed values for the compare URL trailer.
BASE_BARE="${BASE#v}"
VERSION_BARE="${VERSION#v}"

# Decide MAJOR vs PATCH mode. If a milestone is supplied, verify it exists in
# the repository before trusting it. An invalid milestone falls back to PATCH
# mode rather than aborting (the workflow always treats the prose pipeline as
# best-effort), but we distinguish a real "not found" answer from a gh API
# failure (auth, rate-limit, network) so CI diagnostics show the actual cause.
mode="patch"
if [ -n "$MILESTONE" ]; then
  if command -v gh >/dev/null 2>&1; then
    if milestones_json=$(gh api --paginate -H "Accept: application/vnd.github+json" \
        "/repos/$REPO/milestones?state=all&per_page=100"); then
      if printf '%s\n' "$milestones_json" | jq -e --arg milestone "$MILESTONE" \
          '.[] | select(.title == $milestone)' >/dev/null 2>&1; then
        mode="major"
      else
        echo "Warning: milestone '$MILESTONE' not found — falling back to patch mode." >&2
      fi
    else
      echo "Warning: failed to query milestones for '$REPO' via gh CLI — falling back to patch mode." >&2
    fi
  else
    echo "Warning: gh CLI unavailable — falling back to patch mode." >&2
  fi
fi

# Run the underlying generators and capture their output. Both scripts print
# their own '## What's Changed' header and section blocks; we strip those and
# re-emit a single header so we can merge them. Failures from either helper
# must propagate (they return 0 on legitimately empty output, so a non-zero
# exit always indicates a real error worth surfacing) and stderr is left
# untouched so the build log shows the actual cause.
issues_output=""
commits_output=""

if [ "$mode" = "major" ]; then
  issues_output=$("$SCRIPT_DIR/list-issues.sh" "$MILESTONE" "$BASE_BARE")
fi
commits_output=$("$SCRIPT_DIR/list-commits.sh" "$BASE_BARE" "$VERSION_BARE")

# Helper: extract a section from a script's output. Sections are introduced by
# `### <emoji> <name>` and terminated by the next `### ` line or EOF. Trims
# trailing blank lines.
_extract_section() {
  local input="$1"
  local heading_pattern="$2"
  printf "%s\n" "$input" \
    | awk -v pat="$heading_pattern" '
        BEGIN { capturing = 0 }
        /^### / {
          if (capturing) { exit }
          if (index($0, pat) > 0) { capturing = 1; next }
        }
        capturing { print }
      ' \
    | sed -e '/./,$!d' -e :a -e '/^$/{$d;N;ba' -e '}'
}

# Helper: dedupe bullet lines that already appear in `existing` from `incoming`,
# keyed by lower-cased description (the part after the leading `* `, with any
# trailing `(#NNN)` issue ref stripped). The dedupe is intentionally fuzzy
# so commits with the same wording as an issue title are dropped.
_dedupe_bullets() {
  local existing="$1"
  local incoming="$2"
  local existing_keys
  existing_keys=$(printf "%s\n" "$existing" \
    | sed -n 's/^\* \(.*\)$/\1/p' \
    | sed -E 's/[[:space:]]*\(#[0-9]+\)[[:space:]]*$//' \
    | tr '[:upper:]' '[:lower:]')

  printf "%s\n" "$incoming" | while IFS= read -r line; do
    case "$line" in
      \*\ *)
        key=$(printf "%s" "${line#\* }" \
          | sed -E 's/[[:space:]]*\(#[0-9]+\)[[:space:]]*$//' \
          | tr '[:upper:]' '[:lower:]')
        if printf "%s\n" "$existing_keys" | grep -Fxq "$key"; then
          continue
        fi
        printf "%s\n" "$line"
        ;;
      *)
        printf "%s\n" "$line"
        ;;
    esac
  done
}

# Helper: filter commit-derived bullets to drop non-user-facing items
# (CI/CD, build infrastructure, internal refactors, compilation fixes, etc.).
# This is a conservative pass — uncertain items are kept. The patterns are
# unique enough that POSIX-style substring matching works (no gawk-specific
# word boundaries) and the script remains portable to BSD/macOS awk.
_filter_user_facing() {
  printf "%s\n" "$1" | awk '
    /^\* / {
      lc = tolower($0)
      if (lc ~ /ci\/cd/) next
      if (lc ~ /github actions?/) next
      if (lc ~ /workflow file/) next
      if (lc ~ /workflow yaml/) next
      if (lc ~ /docker( |-)?(file|workflow|build)/) next
      if (lc ~ /compilation fix/) next
      if (lc ~ /tostring/) next
      if (lc ~ /code style/) next
      if (lc ~ /dependabot/) next
      if (lc ~ /bump .* version/ && lc !~ /dependency/) next
      print
      next
    }
    { print }
  '
}

# Build merged sections.
# Order: Breaking → Features → Bug Fixes → Dependencies upgrades.
# In MAJOR mode the issues output is authoritative; commits supplement.
# In PATCH mode there is no issues output, so commits are the only source.

issues_breaking=$(_extract_section "$issues_output" "☢️ Breaking changes")
issues_features=$(_extract_section "$issues_output" "🚀 Features")
issues_bugfixes=$(_extract_section "$issues_output" "🐛 Bug Fixes")
issues_deps=$(_extract_section "$issues_output" "⛓ Dependencies upgrades")

commits_breaking=$(_extract_section "$commits_output" "☢️ Breaking changes")
commits_features=$(_extract_section "$commits_output" "🚀 Features")
commits_bugfixes=$(_extract_section "$commits_output" "🐛 Bug Fixes")

commits_breaking=$(_filter_user_facing "$commits_breaking")
commits_features=$(_filter_user_facing "$commits_features")
commits_bugfixes=$(_filter_user_facing "$commits_bugfixes")

# Merge issues (authoritative) with commits (supplemental, deduped).
merged_breaking="$issues_breaking"
extra=$(_dedupe_bullets "$issues_breaking" "$commits_breaking")
if [ -n "$extra" ] && [ -n "$merged_breaking" ]; then
  merged_breaking=$(printf "%s\n%s" "$merged_breaking" "$extra")
elif [ -n "$extra" ]; then
  merged_breaking="$extra"
fi

merged_features="$issues_features"
extra=$(_dedupe_bullets "$issues_features" "$commits_features")
if [ -n "$extra" ] && [ -n "$merged_features" ]; then
  merged_features=$(printf "%s\n%s" "$merged_features" "$extra")
elif [ -n "$extra" ]; then
  merged_features="$extra"
fi

merged_bugfixes="$issues_bugfixes"
extra=$(_dedupe_bullets "$issues_bugfixes" "$commits_bugfixes")
if [ -n "$extra" ] && [ -n "$merged_bugfixes" ]; then
  merged_bugfixes=$(printf "%s\n%s" "$merged_bugfixes" "$extra")
elif [ -n "$extra" ]; then
  merged_bugfixes="$extra"
fi

# Print final body.
printf "## What's Changed\n"

if [ -n "$merged_breaking" ]; then
  printf "\n### ☢️ Breaking changes\n\n%s\n" "$merged_breaking"
fi

if [ -n "$merged_features" ]; then
  printf "\n### 🚀 Features\n\n%s\n" "$merged_features"
fi

if [ -n "$merged_bugfixes" ]; then
  printf "\n### 🐛 Bug Fixes\n\n%s\n" "$merged_bugfixes"
fi

if [ -n "$issues_deps" ]; then
  printf "\n### ⛓ Dependencies upgrades\n\n%s\n" "$issues_deps"
fi

# Compare URL trailer — always last, always present.
printf "\n**Full Changelog**: https://github.com/%s/compare/%s...%s\n" \
  "$REPO" "$BASE" "$VERSION"
