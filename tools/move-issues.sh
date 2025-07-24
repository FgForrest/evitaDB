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

# Usage: ./move-issues.sh "from milestone" "to milestone"
FROM_MILESTONE="$1"
TO_MILESTONE="$2"

if [ -z "$FROM_MILESTONE" ] || [ -z "$TO_MILESTONE" ]; then
  echo "Usage: $0 \"from milestone\" \"to milestone\""
  exit 1
fi

REPO="FgForrest/evitaDB"

# Fetch milestone numbers for both milestones
FROM_MILESTONE_NUMBER=$(gh api -H "Accept: application/vnd.github+json" \
  "/repos/$REPO/milestones" | \
  jq ".[] | select(.title == \"$FROM_MILESTONE\") | .number")

if [ -z "$FROM_MILESTONE_NUMBER" ]; then
  echo "From milestone '$FROM_MILESTONE' not found in repository '$REPO'."
  exit 1
fi

TO_MILESTONE_NUMBER=$(gh api -H "Accept: application/vnd.github+json" \
  "/repos/$REPO/milestones" | \
  jq ".[] | select(.title == \"$TO_MILESTONE\") | .number")

if [ -z "$TO_MILESTONE_NUMBER" ]; then
  echo "To milestone '$TO_MILESTONE' not found in repository '$REPO'."
  exit 1
fi

# Fetch issues with from milestone
issues=$(gh issue list --repo "$REPO" --milestone "$FROM_MILESTONE" --state all --limit 1000 \
  --json number,title \
  --jq '.[] | {number, title}')

if [ -z "$issues" ]; then
  echo "No issues found in milestone '$FROM_MILESTONE'."
  exit 0
fi

# Display issues
echo "Issues in milestone '$FROM_MILESTONE':"
echo "======================================"
while read -r issue; do
  number=$(echo "$issue" | jq -r '.number')
  title=$(echo "$issue" | jq -r '.title')
  echo "* $title (#$number)"
done < <(echo "$issues" | jq -c '.')

echo ""
echo "Do you want to change milestone of these issues to \`$TO_MILESTONE\`? y/n"
read -r response

if [[ "$response" =~ ^[Yy]$ ]]; then
  echo "Moving issues to milestone '$TO_MILESTONE'..."

  # Move each issue to the new milestone
  while read -r issue; do
    number=$(echo "$issue" | jq -r '.number')
    title=$(echo "$issue" | jq -r '.title')

    echo "Moving issue #$number: $title"
    gh issue edit "$number" --repo "$REPO" --milestone "$TO_MILESTONE"

    if [ $? -eq 0 ]; then
      echo "  ✓ Successfully moved issue #$number"
    else
      echo "  ✗ Failed to move issue #$number"
    fi
  done < <(echo "$issues" | jq -c '.')

  echo "Done!"
else
  echo "Operation cancelled."
fi