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

# Usage: ./list-commits.sh "2025.7.1" or ./list-commits.sh "2025.7.0" "2025.7.1"
VERSION_FROM="$1"
VERSION_TO="$2"

if [ -z "$VERSION_FROM" ]; then
  echo "Usage: $0 \"version\" [\"version_to\"]"
  echo "Example: $0 \"2025.7.1\" or $0 \"2025.7.0\" \"2025.7.1\""
  exit 1
fi

# Convert version to tag format (prepend 'v')
TAG_FROM="v$VERSION_FROM"
TAG_TO=""
if [ -n "$VERSION_TO" ]; then
  TAG_TO="v$VERSION_TO"
fi

# Determine git range
if [ -n "$TAG_TO" ]; then
  # Two tags specified: range between them
  GIT_RANGE="$TAG_FROM..$TAG_TO"
else
  # One tag specified: from tag to HEAD
  GIT_RANGE="$TAG_FROM..HEAD"
fi

# Fetch git log
git_log=$(git log --pretty=format:"%s|||%b" "$GIT_RANGE" 2>/dev/null)

if [ $? -ne 0 ]; then
  echo "Error: Failed to retrieve git log for range '$GIT_RANGE'"
  exit 1
fi

# Associative arrays to store deduplicated commits by type
declare -A feat_commits
declare -A fix_commits
declare -A doc_commits
declare -A test_commits

# Process each commit
while IFS='|||' read -r subject body; do
  # Check if commit follows conventional commit format
  if [[ "$subject" =~ ^(feat|fix|doc|test|docs|refactor|perf|style|chore|build|ci|revert)(\(.+\))?:\ (.+)$ ]]; then
    commit_type="${BASH_REMATCH[1]}"
    commit_desc="${BASH_REMATCH[3]}"

    # Normalize commit type (docs -> doc)
    if [ "$commit_type" = "docs" ]; then
      commit_type="doc"
    fi

    # Extract issue number from subject or body
    issue_number=""
    if [[ "$subject" =~ \(#([0-9]+)\) ]]; then
      issue_number="${BASH_REMATCH[1]}"
    elif [[ "$body" =~ Ref:\ #([0-9]+) ]] || [[ "$body" =~ Ref:#([0-9]+) ]]; then
      issue_number="${BASH_REMATCH[1]}"
    fi

    # Create entry
    if [ -n "$issue_number" ]; then
      entry="$commit_desc (#$issue_number)"
    else
      entry="$commit_desc"
    fi

    # Store in appropriate array (using description as key for deduplication)
    case "$commit_type" in
      feat)
        feat_commits["$commit_desc"]="$entry"
        ;;
      fix)
        fix_commits["$commit_desc"]="$entry"
        ;;
      doc)
        doc_commits["$commit_desc"]="$entry"
        ;;
      test)
        test_commits["$commit_desc"]="$entry"
        ;;
    esac
  fi
done <<< "$git_log"

# Print sections
printed_header=false

if [ "${#feat_commits[@]}" -gt 0 ]; then
  if [ "$printed_header" = false ]; then
    echo "## What's Changed"
    printed_header=true
  fi
  echo -e "\n### 🚀 Features\n"
  for key in "${!feat_commits[@]}"; do
    echo "* ${feat_commits[$key]}"
  done | sort -f
fi

if [ "${#fix_commits[@]}" -gt 0 ]; then
  if [ "$printed_header" = false ]; then
    echo "## What's Changed"
    printed_header=true
  fi
  echo -e "\n### 🐛 Bug Fixes\n"
  for key in "${!fix_commits[@]}"; do
    echo "* ${fix_commits[$key]}"
  done | sort -f
fi

if [ "${#doc_commits[@]}" -gt 0 ]; then
  if [ "$printed_header" = false ]; then
    echo "## What's Changed"
    printed_header=true
  fi
  echo -e "\n### 📝 Documentation\n"
  for key in "${!doc_commits[@]}"; do
    echo "* ${doc_commits[$key]}"
  done | sort -f
fi

if [ "${#test_commits[@]}" -gt 0 ]; then
  if [ "$printed_header" = false ]; then
    echo "## What's Changed"
    printed_header=true
  fi
  echo -e "\n### 🧪 Tests\n"
  for key in "${!test_commits[@]}"; do
    echo "* ${test_commits[$key]}"
  done | sort -f
fi
