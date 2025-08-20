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

# Script to process @Deprecated annotations and add since and forRemoval attributes

# Get all files with plain @Deprecated annotations (without since= or forRemoval=)
FILES=$(grep -r "@Deprecated" ../ --include="*.java" --exclude-dir=generated --exclude-dir=target | grep -v "since=" | grep -v "forRemoval=" | cut -d: -f1 | sort -u)

echo "Files with plain @Deprecated annotations:"
echo "$FILES"

# Function to get the highest semantic version tag at a given commit
get_version_at_commit() {
    local commit=$1
    local highest_tag=$(git tag --merged $commit --sort=-version:refname | head -1)
    echo $highest_tag
}

# Function to extract major.minor version from tag (e.g., v2024.8.4 -> 2024.8)
extract_major_minor() {
    local tag=$1
    echo $tag | sed 's/^v//' | cut -d. -f1,2
}

# Process each file
for file in $FILES; do
    echo "Processing: $file"

    # Get line numbers of @Deprecated annotations
    line_numbers=$(grep -n "@Deprecated" "$file" | grep -v "since=" | grep -v "forRemoval=" | cut -d: -f1)

    for line_num in $line_numbers; do
        echo "  Processing @Deprecated at line $line_num"

        # Get the commit that introduced this line
        commit=$(git blame -L $line_num,$line_num "$file" | awk '{print $1}')
        echo "    Introduced in commit: $commit"

        # Get the highest version tag at that commit
        version_tag=$(get_version_at_commit $commit)
        echo "    Highest tag at that time: $version_tag"

        # Extract major.minor version
        if [ -n "$version_tag" ]; then
            major_minor=$(extract_major_minor $version_tag)
            echo "    Using version: $major_minor"
            echo "    Would update to: @Deprecated(since = \"$major_minor\", forRemoval = true)"
        else
            echo "    No version tag found for this commit"
        fi
        echo ""
    done
done