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

# Script to update all @Deprecated annotations with since and forRemoval attributes

# Function to get the version tag a commit actually first shipped in.
#
# NOTE: this used to be `git tag --merged $commit --sort=-version:refname | head -1`,
# which finds the newest tag ALREADY RELEASED BEFORE the commit - i.e. the last
# version still valid at deprecation time, not the version the deprecation
# itself ships in. That is off by (at least) one release in the wrong
# direction; @Deprecated#since must be the version something BECAME
# deprecated (see tools/audit-deprecated-since.sh for the full writeup and a
# tool that verifies already-annotated since= values against this rule).
get_version_at_commit() {
    local commit=$1
    local shipped_tag=$(git describe --tags --contains --match 'v20*' $commit 2>/dev/null | sed -E 's/[~^].*$//')
    echo $shipped_tag
}

# Function to extract major.minor version from tag (e.g., v2024.8.4 -> 2024.8)
extract_major_minor() {
    local tag=$1
    echo $tag | sed 's/^v//' | cut -d. -f1,2
}

# Function to update @Deprecated annotation in a file
update_deprecated_in_file() {
    local file=$1
    echo "Processing: $file"

    # Get line numbers of plain @Deprecated annotations
    local line_numbers=$(grep -n "^[[:space:]]*@Deprecated$" "$file" | cut -d: -f1)

    if [ -z "$line_numbers" ]; then
        echo "  No plain @Deprecated annotations found"
        return
    fi

    # Process each @Deprecated annotation
    for line_num in $line_numbers; do
        echo "  Processing @Deprecated at line $line_num"

        # Get the commit that introduced this line
        local commit=$(git blame -L $line_num,$line_num "$file" | awk '{print $1}')
        echo "    Introduced in commit: $commit"

        # Get the version tag this commit actually first shipped in
        local version_tag=$(get_version_at_commit $commit)
        echo "    First shipped in: $version_tag"

        if [ -n "$version_tag" ]; then
            local major_minor=$(extract_major_minor $version_tag)
            echo "    Using version: $major_minor"

            # Update the @Deprecated annotation
            sed -i "${line_num}s/@Deprecated/@Deprecated(since = \"$major_minor\", forRemoval = true)/" "$file"
            echo "    Updated to: @Deprecated(since = \"$major_minor\", forRemoval = true)"
        else
            echo "    No version tag found for this commit"
        fi
        echo ""
    done
}

# Get all files with plain @Deprecated annotations (excluding generated files and already processed ones)
FILES=$(grep -r "^[[:space:]]*@Deprecated$" ../ --include="*.java" --exclude-dir=generated --exclude-dir=target | grep -v "Lexer.java" | grep -v "Parser.java" | cut -d: -f1 | sort -u)

echo "Files with plain @Deprecated annotations to process:"
echo "$FILES"
echo ""

# Process each file
for file in $FILES; do
    update_deprecated_in_file "$file"
done

echo "All files processed!"