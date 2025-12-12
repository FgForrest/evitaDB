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

# Script to move misplaced test files to correct directories based on their tested class location
# Usage: ./move_misplaced_tests.sh [--execute]
#   --execute: Actually move files (default is dry-run mode)

set -euo pipefail

# Configuration
TEST_DIR="evita_test/evita_functional_tests/src/test/java"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Parse arguments
DRY_RUN=true
if [[ "${1:-}" == "--execute" ]]; then
    DRY_RUN=false
fi

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "=================================================="
if $DRY_RUN; then
    echo -e "${YELLOW}DRY RUN MODE${NC} - No files will be moved"
    echo "Run with --execute to actually move files"
else
    echo -e "${RED}EXECUTION MODE${NC} - Files will be moved"
fi
echo "=================================================="
echo ""

# Find all test files
cd "$PROJECT_ROOT"
test_files=$(find "$TEST_DIR" -type f -name "*Test.java" 2>/dev/null || true)

move_count=0
not_found_count=0
already_correct_count=0

for test_file in $test_files; do
    # Extract test class name (remove Test.java suffix)
    test_basename=$(basename "$test_file")
    class_name="${test_basename%Test.java}.java"

    # Get the package path of the test file relative to test/java
    test_package_path=$(dirname "$test_file" | sed "s|^$TEST_DIR/||")

    # Search for the corresponding source class in all main/java directories
    source_files=$(find . -type f -name "$class_name" -path "*/src/main/java/*" ! -path "./evita_test/*" 2>/dev/null || true)

    # Count matches
    if [[ -z "$source_files" ]]; then
        file_count=0
    else
        file_count=$(echo "$source_files" | wc -l)
    fi

    if [[ $file_count -eq 0 ]]; then
        # Source file not found - skip
        not_found_count=$((not_found_count + 1))
        continue
    elif [[ $file_count -gt 1 ]]; then
        # Multiple matches found - report and skip
        echo -e "${YELLOW}WARNING:${NC} Multiple source files found for $test_basename:"
        echo "$source_files" | sed 's/^/  /'
        echo "  Skipping to avoid ambiguity"
        echo ""
        not_found_count=$((not_found_count + 1))
        continue
    fi

    # Extract the source package path (relative to src/main/java)
    source_file="$source_files"
    source_package_path=$(echo "$source_file" | sed 's|.*/src/main/java/||' | xargs dirname)

    # Check if test is in the wrong package
    if [[ "$test_package_path" != "$source_package_path" ]]; then
        # Calculate correct test file path
        correct_test_path="$TEST_DIR/$source_package_path/$test_basename"

        echo -e "${GREEN}MISMATCH FOUND:${NC}"
        echo "  Test:    $test_file"
        echo "  Source:  $source_file"
        echo "  Current package:  $test_package_path"
        echo "  Expected package: $source_package_path"
        echo "  -> Move to: $correct_test_path"

        if ! $DRY_RUN; then
            # Create target directory if it doesn't exist
            target_dir=$(dirname "$correct_test_path")
            mkdir -p "$target_dir"

            # Move the file
            git mv "$test_file" "$correct_test_path" 2>/dev/null || mv "$test_file" "$correct_test_path"
            echo -e "  ${GREEN}✓ MOVED${NC}"
        fi

        echo ""
        move_count=$((move_count + 1))
    else
        already_correct_count=$((already_correct_count + 1))
    fi
done

# Summary
echo "=================================================="
echo "SUMMARY:"
echo "=================================================="
echo "  Files to move:        $move_count"
echo "  Already correct:      $already_correct_count"
echo "  Skipped/not found:    $not_found_count"
echo ""

if $DRY_RUN && [[ $move_count -gt 0 ]]; then
    echo -e "${YELLOW}This was a dry run. Run with --execute to actually move files.${NC}"
elif ! $DRY_RUN && [[ $move_count -gt 0 ]]; then
    echo -e "${GREEN}Files have been moved successfully!${NC}"
    echo "Don't forget to commit these changes."
elif [[ $move_count -eq 0 ]]; then
    echo -e "${GREEN}All test files are in the correct locations!${NC}"
fi
