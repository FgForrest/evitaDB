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
MILESTONE_NUMBER=$(gh api -H "Accept: application/vnd.github+json" \
  "/repos/$REPO/milestones" | \
  jq ".[] | select(.title == \"$MILESTONE\") | .number")

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
groups["breaking"]="⛓️‍💥 Breaking changes"
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