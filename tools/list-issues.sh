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

# Usage: ./script.sh "v1.0.0"
MILESTONE="$1"

if [ -z "$MILESTONE" ]; then
  echo "Usage: $0 \"milestone title\""
  exit 1
fi

REPO="FgForrest/evitaDB"

# Fetch milestone number
MILESTONE_NUMBER=$(gh api --paginate -H "Accept: application/vnd.github+json" "/repos/$REPO/milestones?state=all&per_page=100" | jq ".[] | select(.title == \"$MILESTONE\") | .number" | head -n 1)

if [ -z "$MILESTONE_NUMBER" ]; then
  echo "Milestone '$MILESTONE' not found in repository '$REPO'."
  exit 1
fi

# Fetch issues with milestone
issues=$(gh issue list --repo "$REPO" --milestone "$MILESTONE" --state all --limit 1000 \
  --json number,title,labels \
  --jq '.[] | {number, title, labels: [.labels[].name]}')


# Grouping logic
declare -A groups
groups["breaking"]="☢️ Breaking changes"
groups["feature"]="🚀 Features"
groups["bugfix"]="🐛 Bug Fixes"

declare -a breaking=()
declare -a feature=()
declare -a bugfix=()

# Process each issue
while read -r issue; do
  number=$(echo "$issue" | jq -r '.number')
  title=$(echo "$issue" | jq -r '.title')
  labels=$(echo "$issue" | jq -r '.labels[]' 2>/dev/null || echo "")

  category=""
  while read -r label; do
    if [ -z "$label" ]; then
      continue
    fi

    if [ "$label" = "breaking change" ]; then
      category="breaking"
      break
    elif [ "$label" = "enhancement" ] || [ "$label" = "performance" ]; then
      [ -z "$category" ] && category="feature"
    elif [ "$label" = "bug" ] || [ "$label" = "maintenance" ]; then
      [ -z "$category" ] && category="bugfix"
    fi
  done <<< "$labels"

  entry="* $(echo "$title" | sed 's/^feat:/feat:/') (#$number)"

  if [ "$category" = "breaking" ]; then
    breaking+=("$entry")
  elif [ "$category" = "feature" ]; then
    feature+=("$entry")
  elif [ "$category" = "bugfix" ]; then
    bugfix+=("$entry")
  fi
done < <(echo "$issues" | jq -c '.')

# Print sections
echo "## What’s Changed"
if [ "${#breaking[@]}" -gt 0 ]; then
  echo -e "\n### ${groups["breaking"]}\n"
  printf "%s\n" "${breaking[@]}"
fi

if [ "${#feature[@]}" -gt 0 ]; then
  echo -e "\n### ${groups["feature"]}\n"
  printf "%s\n" "${feature[@]}"
fi

if [ "${#bugfix[@]}" -gt 0 ]; then
  echo -e "\n### ${groups["bugfix"]}\n"
  printf "%s\n" "${bugfix[@]}"
fi

# ========================
# Dependencies upgrades
# ========================
# Extract all properties ending with .version from current and previous pom.xml,
# where previous pom.xml is taken from the latest release branch derived from the
# latest semantic tag vYYYY.MAJOR.MINOR in the current branch.

# Helper: extract k=v lines for *.version from pom content passed on stdin
_extract_version_props() {
  sed -n '/<properties>/,/<\/properties>/{ s/.*<\([^><]*\.version\)>\([^><]*\)<.*/\1=\2/p }'
}

# Determine repo root and current pom
ROOT_DIR=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
CURRENT_POM="$ROOT_DIR/pom.xml"

if [ -f "$CURRENT_POM" ]; then
  current_props=$(cat "$CURRENT_POM" | _extract_version_props)
else
  current_props=""
fi

# Find latest semantic tag in current branch and map to release branch
last_tag=""
release_branch=""
# fetch tags quietly (ignore errors if any)
(git fetch --tags -q >/dev/null 2>&1) || true
# list tags reachable from HEAD and pick the latest matching vYYYY.M.M
last_tag=$(git tag --merged 2>/dev/null | grep -E '^v[0-9]{4}\.[0-9]+\.[0-9]+$' | sort -V | tail -n 1)

if [ -n "$last_tag" ]; then
  major=$(echo "$last_tag" | sed -E 's/^v([0-9]{4})\..*/\1/')
  minor=$(echo "$last_tag" | sed -E 's/^v[0-9]{4}\.([0-9]+)\..*/\1/')
  release_branch="release_${major}-${minor}"
fi

previous_props=""
if [ -n "$release_branch" ]; then
  # Try to fetch remote release branch pom.xml
  (git fetch origin "$release_branch":"refs/remotes/origin/$release_branch" -q >/dev/null 2>&1) || true
  previous_pom_content=$(git show "origin/$release_branch:pom.xml" 2>/dev/null || git show "$release_branch:pom.xml" 2>/dev/null || echo "")
  if [ -n "$previous_pom_content" ]; then
    previous_props=$(printf "%s" "$previous_pom_content" | _extract_version_props)
  fi
fi

# Compare and collect differences
if [ -n "$current_props" ] && [ -n "$previous_props" ]; then
  diffs=()
  while IFS='=' read -r key cur_val; do
    [ -n "$key" ] || continue
    prev_val=$(printf "%s\n" "$previous_props" | awk -F'=' -v k="$key" '$1==k{print $2; exit}')
    if [ -n "$prev_val" ] && [ "$prev_val" != "$cur_val" ]; then
      name="${key%.version}"
      diffs+=("$name ... $prev_val → $cur_val")
    fi
  done <<< "$current_props"

  if [ ${#diffs[@]} -gt 0 ]; then
    echo -e "\n### ⛓ Dependencies upgrades\n"
    printf "%s\n" "${diffs[@]}" | sort -f | while IFS= read -r line; do
      echo "- $line"
    done
  fi
fi