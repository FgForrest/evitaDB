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

# prepare-for-rag.sh
# Script to process markdown documentation files for RAG systems
# Handles language-specific content filtering and code example embedding

# Don't exit on error - handle errors gracefully
set -o pipefail

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Default values
DEFAULT_LANGS="j+e+g+r+c"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DOC_ROOT="${PROJECT_ROOT}/documentation"

# Language mappings
declare -A LANG_MAP=(
    ["j"]="java"
    ["e"]="evitaql"
    ["g"]="graphql"
    ["r"]="rest"
    ["c"]="csharp"
)

# Usage information
usage() {
    echo "Usage: $0 <target_folder> [language_spec]"
    echo ""
    echo "Arguments:"
    echo "  target_folder   Target directory for processed files (will be created if missing)"
    echo "  language_spec   Optional language specification (default: j+e+g+r+c)"
    echo ""
    echo "Language specifications:"
    echo "  j = java"
    echo "  e = evitaql"
    echo "  g = graphql"
    echo "  r = rest"
    echo "  c = csharp"
    echo ""
    echo "Example: $0 /tmp/rag-output j+g"
    exit 1
}

# Parse arguments
if [ $# -lt 1 ]; then
    echo -e "${RED}Error: Missing target folder argument${NC}"
    usage
fi

TARGET_FOLDER="$1"
LANG_SPEC="${2:-$DEFAULT_LANGS}"

# Parse language specification
IFS='+' read -ra LANG_ABBREVS <<< "$LANG_SPEC"
declare -a LANGUAGES=()

for abbrev in "${LANG_ABBREVS[@]}"; do
    if [ -n "${LANG_MAP[$abbrev]}" ]; then
        LANGUAGES+=("${LANG_MAP[$abbrev]}")
    else
        echo -e "${RED}Error: Unknown language abbreviation '$abbrev'${NC}"
        usage
    fi
done

echo -e "${GREEN}Processing documentation for languages: ${LANGUAGES[*]}${NC}"
echo -e "${GREEN}Target folder: ${TARGET_FOLDER}${NC}"

# Create target directories
mkdir -p "${TARGET_FOLDER}"
for lang in "${LANGUAGES[@]}"; do
    mkdir -p "${TARGET_FOLDER}/${lang}"
done
mkdir -p "${TARGET_FOLDER}/generic"

# Function to check if file contains any LS tags
contains_ls_tags() {
    local file="$1"
    grep -qiE '<LS[[:space:]]+to=' "$file"
}

# Function to get all languages used in LS tags
get_ls_languages() {
    local file="$1"
    local all_langs=""

    # Extract all LS tag language specifications using Perl-compatible regex
    all_langs=$(grep -ioP '<LS[[:space:]]+to=["\x27][^"\x27]+["\x27]>' "$file" 2>/dev/null | \
                grep -oP 'to=["\x27]\K[^"\x27]+' 2>/dev/null | \
                tr ',' '\n' | \
                sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | \
                sort -u)

    # Convert abbreviations to full names and filter by selected languages
    local result_langs=()
    for abbrev in $all_langs; do
        abbrev=$(echo "$abbrev" | tr '[:upper:]' '[:lower:]')
        if [ -n "${LANG_MAP[$abbrev]}" ]; then
            full_name="${LANG_MAP[$abbrev]}"
            # Check if this language is in our selected list
            for lang in "${LANGUAGES[@]}"; do
                if [ "$lang" = "$full_name" ]; then
                    result_langs+=("$full_name")
                    break
                fi
            done
        fi
    done

    # Return unique languages
    printf '%s\n' "${result_langs[@]}" | sort -u
}

# Function to get languages that have code files for SourceCodeTabs references
get_sourcecode_languages() {
    local file="$1"
    local source_dir="$(dirname "$file")"

    # Extension mapping (reverse of what we use in Python)
    declare -A ext_map=(
        ["java"]="java"
        ["evitaql"]="evitaql"
        ["graphql"]="graphql"
        ["rest"]="rest"
        ["csharp"]="cs"
    )

    local result_langs=()

    # Extract all SourceCodeTabs file references using Python for multiline matching
    local file_refs
    file_refs=$(python3 - "$file" <<'PYEOF'
import re
import sys

file_path = sys.argv[1]

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Find all SourceCodeTabs blocks
pattern = r'<SourceCodeTabs[^>]*>(.*?)</SourceCodeTabs>'
matches = re.finditer(pattern, content, re.IGNORECASE | re.DOTALL)

for match in matches:
    content_block = match.group(1)
    # Extract markdown link
    link_pattern = r'\[([^\]]+)\]\(([^\)]+)\)'
    link_match = re.search(link_pattern, content_block)
    if link_match:
        print(link_match.group(2))
PYEOF
)

    if [ -z "$file_refs" ]; then
        return 0
    fi

    # For each file reference, check which language variants exist
    while IFS= read -r file_path; do
        if [ -z "$file_path" ]; then
            continue
        fi

        # Get base path without extension
        local base_path="${file_path%.*}"

        # Check each language extension
        for lang in "${LANGUAGES[@]}"; do
            local ext="${ext_map[$lang]}"
            local test_file="${base_path}.${ext}"

            # Resolve full path
            local full_path
            # Remove leading slash if present
            test_file="${test_file#/}"
            if [[ "$test_file" == documentation/* ]]; then
                # Absolute path from project root
                full_path="${PROJECT_ROOT}/${test_file}"
            else
                # Relative path from source directory
                full_path="${source_dir}/${test_file}"
            fi

            # Check if file exists
            if [ -f "$full_path" ]; then
                # Check if this language is already in result
                local found=0
                for existing_lang in "${result_langs[@]}"; do
                    if [ "$existing_lang" = "$lang" ]; then
                        found=1
                        break
                    fi
                done

                if [ $found -eq 0 ]; then
                    result_langs+=("$lang")
                fi
            fi
        done
    done <<< "$file_refs"

    # Return unique languages
    printf '%s\n' "${result_langs[@]}" | sort -u
}

# Function to process LS tags for a specific language
# Args: input_file output_file language
process_ls_tags_for_lang() {
    local input_file="$1"
    local output_file="$2"
    local lang="$3"

    python3 - "$input_file" "$output_file" "$lang" <<'PYEOF'
import re
import sys

input_file = sys.argv[1]
output_file = sys.argv[2]
lang = sys.argv[3]

# Language mapping
lang_map = {
    "java": "j",
    "evitaql": "e",
    "graphql": "g",
    "rest": "r",
    "csharp": "c"
}

# Get abbreviation for comparison
lang_abbrev = lang_map.get(lang, lang)

# Read input file
with open(input_file, 'r', encoding='utf-8') as f:
    content = f.read()

# Find all LS tags - process from innermost to outermost
# Use negative lookahead to match tags that don't contain other LS tags
pattern = r'<LS\s+to=["\']([^"\']+)["\']>((?:(?!<LS).)*?)</LS>'

def replacer(match):
    ls_langs = match.group(1)
    inner_content = match.group(2)

    # Parse language list
    tag_langs = [l.strip().lower() for l in ls_langs.split(',')]

    # Check if current language is in the list
    if lang in tag_langs or lang_abbrev in tag_langs:
        return inner_content
    else:
        return ""

# Process in a loop to handle nested tags - keep processing until no more tags found
max_iterations = 100  # Prevent infinite loops
result = content
for iteration in range(max_iterations):
    new_result = re.sub(pattern, replacer, result, flags=re.IGNORECASE | re.DOTALL)
    if new_result == result:  # No more changes
        break
    result = new_result

# Write output
with open(output_file, 'w', encoding='utf-8') as f:
    f.write(result)
PYEOF
}

# Function to process SourceCodeTabs tags
# Args: input_file output_file language source_markdown_file
process_source_code_tabs() {
    local input_file="$1"
    local output_file="$2"
    local lang="$3"
    local source_md_file="$4"

    python3 - "$input_file" "$output_file" "$lang" "$source_md_file" "$PROJECT_ROOT" <<'PYEOF'
import re
import os
import sys

input_file = sys.argv[1]
output_file = sys.argv[2]
lang = sys.argv[3]
source_md_file = sys.argv[4]
project_root = sys.argv[5]
source_dir = os.path.dirname(source_md_file)

# Extension mapping
ext_map = {
    "java": "java",
    "evitaql": "evitaql",
    "graphql": "graphql",
    "rest": "rest",
    "csharp": "cs"
}

lang_ext = ext_map.get(lang, lang)

# Read input
with open(input_file, 'r', encoding='utf-8') as f:
    content = f.read()

# Find SourceCodeTabs blocks - match the whole block
pattern = r'<SourceCodeTabs[^>]*>(.*?)</SourceCodeTabs>'

def replacer(match):
    content_block = match.group(1)

    # Extract markdown link from the content
    link_pattern = r'\[([^\]]+)\]\(([^\)]+)\)'
    link_match = re.search(link_pattern, content_block)

    if not link_match:
        # No link found, keep original
        return match.group(0)

    link_text = link_match.group(1)
    file_path = link_match.group(2)

    # Replace extension
    base_path = os.path.splitext(file_path)[0]
    new_file_path = f"{base_path}.{lang_ext}"

    # Remove leading slash if present
    new_file_path = new_file_path.lstrip('/')

    # Resolve path - check if it's absolute (starts with 'documentation/') or relative
    if new_file_path.startswith('documentation/'):
        # Path is from project root
        full_path = os.path.join(project_root, new_file_path)
    else:
        # Path is relative to source directory
        full_path = os.path.join(source_dir, new_file_path)

    # Check if file exists
    if os.path.exists(full_path):
        try:
            with open(full_path, 'r', encoding='utf-8') as f:
                file_content = f.read().rstrip()

            # Create formatted output
            return f"**{link_text}**\n\n```{lang}\n{file_content}\n```"
        except Exception as e:
            sys.stderr.write(f"Warning: Could not read file {full_path}: {e}\n")
            return match.group(0)  # Return original if error
    else:
        # File doesn't exist, keep original tag
        return match.group(0)

result = re.sub(pattern, replacer, content, flags=re.IGNORECASE | re.DOTALL)

# Write output
with open(output_file, 'w', encoding='utf-8') as f:
    f.write(result)
PYEOF
}

# Process a single markdown file
process_markdown_file() {
    local source_file="$1"
    local rel_path="${source_file#$DOC_ROOT/}"

    echo -e "${YELLOW}Processing: ${rel_path}${NC}"

    # Get languages from LS tags
    local ls_langs
    if contains_ls_tags "$source_file"; then
        ls_langs=$(get_ls_languages "$source_file")
    else
        ls_langs=""
    fi

    # Get languages from SourceCodeTabs file existence
    local sourcecode_langs
    sourcecode_langs=$(get_sourcecode_languages "$source_file")

    # Merge both language lists (unique)
    local all_langs
    all_langs=$(printf '%s\n%s\n' "$ls_langs" "$sourcecode_langs" | sort -u | grep -v '^$' || true)

    if [ -n "$all_langs" ]; then
        # Process for each language found
        for lang in $all_langs; do
            local target_file="${TARGET_FOLDER}/${lang}/${rel_path}"
            local target_dir="$(dirname "$target_file")"

            mkdir -p "$target_dir"

            # Create temporary files
            local temp1=$(mktemp)
            local temp2=$(mktemp)

            # Process LS tags for this language (if file has LS tags)
            # Note: We need to process even if ls_langs is empty for this lang,
            # because there might be LS tags for OTHER languages that need to be removed
            if contains_ls_tags "$source_file"; then
                process_ls_tags_for_lang "$source_file" "$temp1" "$lang"
            else
                # No LS tags at all, just copy the content
                cp "$source_file" "$temp1"
            fi

            # Process SourceCodeTabs
            process_source_code_tabs "$temp1" "$temp2" "$lang" "$source_file"

            # Move to final location
            mv "$temp2" "$target_file"
            rm -f "$temp1"

            echo -e "  ${GREEN}✓${NC} Created: ${lang}/${rel_path}"
        done
    else
        # No LS tags and no SourceCodeTabs languages - copy to generic folder
        local target_file="${TARGET_FOLDER}/generic/${rel_path}"
        local target_dir="$(dirname "$target_file")"

        mkdir -p "$target_dir"

        # Still process SourceCodeTabs for generic files (use first language as default)
        local default_lang="${LANGUAGES[0]}"

        # Create temporary file
        local temp=$(mktemp)

        process_source_code_tabs "$source_file" "$temp" "$default_lang" "$source_file"

        # Move to final location
        mv "$temp" "$target_file"

        echo -e "  ${GREEN}✓${NC} Created: generic/${rel_path}"
    fi
}

# Main processing loop
echo -e "\n${GREEN}Searching for markdown files...${NC}"

# Find all markdown files in documentation folder
file_count=0
processed=0
while IFS= read -r -d '' markdown_file; do
    process_markdown_file "$markdown_file"
    ((file_count++))

    # Progress indicator every 50 files
    if [ $((file_count % 50)) -eq 0 ]; then
        echo -e "${GREEN}Progress: ${file_count} files processed...${NC}"
    fi
done < <(find "$DOC_ROOT" -type f -name "*.md" -print0)

echo -e "\n${GREEN}Processing complete!${NC}"
echo -e "${GREEN}Processed ${file_count} markdown files${NC}"
echo -e "${GREEN}Output directory: ${TARGET_FOLDER}${NC}"
