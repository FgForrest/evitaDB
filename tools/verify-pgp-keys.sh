#!/bin/bash

#
#
#                         _ _        ____  ____
#               _____   _(_) |_ __ _|  _ \| __ )
#              / _ \ \ / / | __/ _` | | | |  _ \
#             |  __/\ V /| | || (_| | |_| | |_) |
#              \___| \_/ |_|\__\__,_|____/|____/
#
#   Copyright (c) 2026
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

set -euo pipefail

# Requires GNU grep (-P flag for PCRE), bash 4+ (associative arrays), and
# standard tools: mvn, curl, gpg.

# ============================================================================
# Configuration
# ============================================================================

KEYSERVERS=("keys.openpgp.org" "keyserver.ubuntu.com" "pgp.mit.edu")
MAVEN_CENTRAL="https://repo.maven.apache.org/maven2"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MAP_FILE="$PROJECT_ROOT/pgp-keys-map.list"

MODE="check"  # "check" or "fix"
WORK_DIR=""

# Result tracking
declare -a FAIL_GROUPS=()
declare -A FAIL_ARTIFACT=()
declare -A FAIL_VERSION=()
declare -A FAIL_PACKAGING=()
declare -A FAIL_KEY=()

declare -A RESULT_FINGERPRINT=()
declare -A RESULT_CROSSREF=()
declare -A RESULT_CROSSREF_DETAIL=()
declare -A RESULT_KEYSERVER=()
declare -A RESULT_KEYSERVER_NAME=()
declare -A RESULT_UID=()
declare -A RESULT_OLD_EXPIRY=()
declare -A RESULT_STATUS=()
declare -A RESULT_MAP_ENTRY=()

# Stored pgpverify output
PGPVERIFY_OUTPUT=""

# ============================================================================
# Output helpers
# ============================================================================

usage() {
	cat <<-EOF
	Usage: verify-pgp-keys.sh [--check | --fix]

	Verifies PGP signatures of Maven dependencies using pgpverify-maven-plugin
	and validates any new/unknown signing keys.

	Options:
	  --check   (default) Report-only mode. Outputs validation results.
	            Exit 0 if all keys are valid, 1 if any fail validation.
	  --fix     Same as --check, but also updates pgp-keys-map.list with
	            validated keys. Does NOT add keys that fail validation.
	  --help    Show this help message.

	The script:
	  1. Runs mvn pgpverify to find disallowed signing keys
	  2. Downloads artifacts from Maven Central to extract signing fingerprints
	  3. Cross-references by verifying a different artifact from the same group
	  4. Looks up keys on public keyservers
	  5. Checks if old allowed keys have expired
	  6. Outputs a structured validation report
	  7. (--fix only) Appends validated keys to pgp-keys-map.list
	EOF
}

log_info() {
	printf '\033[32m[INFO]\033[0m %s\n' "$*"
}

log_warn() {
	printf '\033[33m[WARN]\033[0m %s\n' "$*"
}

log_error() {
	printf '\033[31m[ERROR]\033[0m %s\n' "$*"
}

log_step() {
	printf '\n\033[1m=== %s ===\033[0m\n\n' "$*"
}

# ============================================================================
# Utility
# ============================================================================

# Convert groupId dots to path slashes: "net.openhft" -> "net/openhft"
group_to_path() {
	echo "${1//./\/}"
}

# Run GPG with isolated homedir to avoid polluting the user's keyring
run_gpg() {
	gpg --homedir "$WORK_DIR/gnupg" --batch --quiet "$@"
}

# Normalize a fingerprint to 0x-prefixed uppercase
normalize_fingerprint() {
	local fp="$1"
	fp="${fp#0x}"
	fp="${fp#0X}"
	fp="${fp^^}"
	echo "0x${fp}"
}

# ============================================================================
# Core functions
# ============================================================================

# Run Maven pgpverify, capture output. Return 0 if clean, 1 if failures.
run_pgpverify() {
	log_info "Running: mvn org.simplify4u.plugins:pgpverify-maven-plugin:check"

	local output
	local exit_code=0
	output=$(cd "$PROJECT_ROOT" && mvn org.simplify4u.plugins:pgpverify-maven-plugin:check 2>&1) || exit_code=$?

	PGPVERIFY_OUTPUT="$output"

	if [ "$exit_code" -eq 0 ]; then
		return 0
	fi

	# Check if the failure is actually PGP-related
	if echo "$output" | grep -qE "Not allowed artifact|not allowed.*keyID|pgpverify"; then
		return 1
	fi

	# Non-PGP Maven failure
	log_error "Maven failed for reasons unrelated to PGP verification:"
	echo "$output" | grep -F '[ERROR]' | head -10
	exit 1
}

# Parse pgpverify output for failures. Populates FAIL_* arrays.
# Deduplicates by groupId — keeps only the first artifact per group.
parse_failures() {
	local output="$PGPVERIFY_OUTPUT"

	while IFS= read -r line; do
		# Match: [ERROR] Not allowed artifact <g>:<a>:<p>:<v> and keyID: 0x<hex>
		if [[ "$line" =~ \[ERROR\].*[Nn]ot\ allowed\ artifact\ ([^:]+):([^:]+):([^:]+):([^[:space:]]+).*[Kk]ey[[:space:]]*[Ii][Dd]:[[:space:]]*(0x[0-9A-Fa-f]+) ]]; then
			local group="${BASH_REMATCH[1]}"
			local artifact="${BASH_REMATCH[2]}"
			local packaging="${BASH_REMATCH[3]}"
			local version="${BASH_REMATCH[4]}"
			local key="${BASH_REMATCH[5]}"

			# Deduplicate by groupId
			if [[ -z "${FAIL_ARTIFACT[$group]+x}" ]]; then
				FAIL_GROUPS+=("$group")
				FAIL_ARTIFACT[$group]="$artifact"
				FAIL_VERSION[$group]="$version"
				FAIL_PACKAGING[$group]="$packaging"
				FAIL_KEY[$group]="$key"
			fi
		fi
	done <<< "$output"
}

# Download an artifact + .asc from Maven Central and extract signing fingerprint.
# Args: groupId artifactId version [packaging]
# Prints the fingerprint (0x-prefixed) or empty string on failure.
get_signing_key() {
	local group="$1"
	local artifact="$2"
	local version="$3"
	local packaging="${4:-jar}"
	local group_path
	group_path=$(group_to_path "$group")
	local base_url="$MAVEN_CENTRAL/$group_path/$artifact/$version"

	local file_ext="$packaging"
	local file_name="${artifact}-${version}.${file_ext}"
	local file_path="$WORK_DIR/$file_name"
	local asc_path="$WORK_DIR/${file_name}.asc"

	# Download the artifact
	if ! curl -sf "$base_url/$file_name" -o "$file_path" 2>/dev/null; then
		# Fall back to .pom if the primary packaging isn't available
		if [ "$file_ext" != "pom" ]; then
			file_ext="pom"
			file_name="${artifact}-${version}.pom"
			file_path="$WORK_DIR/$file_name"
			asc_path="$WORK_DIR/${file_name}.asc"
			if ! curl -sf "$base_url/$file_name" -o "$file_path" 2>/dev/null; then
				return 1
			fi
		else
			return 1
		fi
	fi

	# Download the signature
	if ! curl -sf "$base_url/${file_name}.asc" -o "$asc_path" 2>/dev/null; then
		return 1
	fi

	# Extract fingerprint via gpg --verify
	local gpg_output
	gpg_output=$(run_gpg --keyid-format long --verify "$asc_path" "$file_path" 2>&1 || true)

	# Try to extract full fingerprint from "using RSA key ..." / "using EDDSA key ..." etc.
	local fingerprint
	fingerprint=$(echo "$gpg_output" | grep -oP 'using \w+ key \K[0-9A-Fa-f]+' | head -1 || true)

	if [ -z "$fingerprint" ]; then
		# Try alternative format: "using key ID ..."
		fingerprint=$(echo "$gpg_output" | grep -oP 'key ID \K[0-9A-Fa-f]+' | head -1 || true)
	fi

	if [ -z "$fingerprint" ]; then
		return 1
	fi

	normalize_fingerprint "$fingerprint"
}

# Cross-reference: verify that a different artifact from the same group uses the same key.
# Args: groupId originalArtifact originalVersion expectedFingerprint
# Prints: "MATCH:<artifact>:<version>" or error description
cross_reference() {
	local group="$1"
	local original_artifact="$2"
	local original_version="$3"
	local expected_fingerprint="$4"
	local group_path
	group_path=$(group_to_path "$group")
	local group_url="$MAVEN_CENTRAL/$group_path/"

	# Fetch group directory listing
	local dir_html
	dir_html=$(curl -sf "$group_url" 2>/dev/null) || {
		echo "UNAVAILABLE"
		return 1
	}

	# Extract artifact directory names
	local artifacts
	artifacts=$(echo "$dir_html" | grep -oP 'href="\K[A-Za-z][^"]*(?=/")' | head -10 || true)

	local cross_artifact=""
	local cross_version=""
	local art_url art_html ver

	# Try a different artifact from the same group
	while IFS= read -r art; do
		[ -z "$art" ] && continue
		[ "$art" = "$original_artifact" ] && continue

		# Fetch artifact directory to find a version
		art_url="$MAVEN_CENTRAL/$group_path/$art/"
		art_html=$(curl -sf "$art_url" 2>/dev/null) || continue

		# Pick the latest version directory
		ver=$(echo "$art_html" | grep -oP 'href="\K[0-9][^"]*(?=/")' | tail -1 || true)

		if [ -n "$ver" ]; then
			cross_artifact="$art"
			cross_version="$ver"
			break
		fi
	done <<< "$artifacts"

	# If no different artifact, try a different version of the same artifact
	if [ -z "$cross_artifact" ]; then
		art_url="$MAVEN_CENTRAL/$group_path/$original_artifact/"
		art_html=$(curl -sf "$art_url" 2>/dev/null) || {
			echo "UNAVAILABLE"
			return 1
		}

		ver=$(echo "$art_html" | grep -oP 'href="\K[0-9][^"]*(?=/")' \
			| grep -v "^${original_version}$" | tail -1 || true)

		if [ -n "$ver" ]; then
			cross_artifact="$original_artifact"
			cross_version="$ver"
		fi
	fi

	if [ -z "$cross_artifact" ]; then
		echo "NO_OTHER_ARTIFACT"
		return 1
	fi

	# Download and verify the cross-reference artifact
	local cross_fingerprint
	cross_fingerprint=$(get_signing_key "$group" "$cross_artifact" "$cross_version" "jar") || {
		echo "VERIFY_FAILED:${cross_artifact}:${cross_version}"
		return 1
	}

	if [ -z "$cross_fingerprint" ]; then
		echo "VERIFY_FAILED:${cross_artifact}:${cross_version}"
		return 1
	fi

	if [ "$cross_fingerprint" = "$expected_fingerprint" ]; then
		echo "MATCH:${cross_artifact}:${cross_version}"
		return 0
	else
		echo "MISMATCH:${cross_artifact}:${cross_version}:${cross_fingerprint}"
		return 1
	fi
}

# Look up a key on public keyservers. Returns 0 on first success, 1 if all fail.
# Imports the key into the isolated keyring (enabling subsequent UID lookups).
# Args: fingerprint
# Prints: "server_name" on success, "NOT_FOUND" on failure
lookup_keyserver() {
	local fingerprint="$1"
	local bare_fp="${fingerprint#0x}"

	for server in "${KEYSERVERS[@]}"; do
		if run_gpg --keyserver "$server" --recv-keys "$bare_fp" 2>/dev/null; then
			echo "$server"
			return 0
		fi
	done

	echo "NOT_FOUND"
	return 1
}

# Get UID for a key that has been imported into our isolated keyring
# Args: fingerprint
get_key_uid() {
	local fingerprint="$1"
	local bare_fp="${fingerprint#0x}"
	local key_info
	key_info=$(run_gpg --list-keys --with-colons "$bare_fp" 2>/dev/null || true)

	# Extract UID from uid: line (field 10)
	local uid
	uid=$(echo "$key_info" | grep '^uid:' | head -1 | cut -d: -f10)

	if [ -n "$uid" ]; then
		echo "$uid"
	else
		echo "(no verified UID)"
	fi
}

# Find the matching entry pattern in pgp-keys-map.list for a given groupId:artifactId.
# Args: groupId artifactId mapFile
# Prints the entry pattern (e.g., "net.openhft" or "org.junit.*") or empty.
find_map_entry() {
	local group="$1"
	local artifact="$2"
	local map_file="$3"

	while IFS= read -r line; do
		# Skip empty lines and continuation lines (start with whitespace)
		[[ -z "$line" ]] && continue
		[[ "$line" =~ ^[[:space:]] ]] && continue

		# Extract the group pattern (before the = sign), trimmed
		local pattern
		pattern=$(echo "$line" | sed 's/[[:space:]]*=.*//' | sed 's/[[:space:]]*$//')
		[ -z "$pattern" ] && continue

		# Check matches in order of specificity
		if [[ "$pattern" == "${group}:${artifact}" ]]; then
			echo "$pattern"
			return 0
		elif [[ "$pattern" == "${group}:*" ]]; then
			echo "$pattern"
			return 0
		elif [[ "$pattern" == "$group" ]]; then
			echo "$pattern"
			return 0
		elif [[ "$pattern" == *".*" ]]; then
			local prefix="${pattern%.\*}"
			if [[ "$group" == "$prefix" || "$group" == "${prefix}."* ]]; then
				echo "$pattern"
				return 0
			fi
		fi
	done < "$map_file"

	return 1
}

# Check if old keys for a group entry have expired.
# Args: entryPattern mapFile
# Prints: "0xKEY:EXPIRED:YYYY-MM-DD" or "0xKEY:VALID:YYYY-MM-DD" or "0xKEY:NO_EXPIRY"
check_old_key() {
	local entry_pattern="$1"
	local map_file="$2"

	# Extract the entry's key lines
	local escaped="${entry_pattern//./\\.}"
	escaped="${escaped//\*/\\*}"
	escaped="${escaped//:/\\:}"

	# Use awk to find the full entry (first line + continuation lines)
	local keys_text
	keys_text=$(awk -v pat="$escaped" '
		BEGIN { found=0 }
		{
			if (!found && $0 ~ "^" pat "[[:space:]]") { found=1; print; next }
			if (found && /^[[:space:]]+0x/) { print; next }
			if (found) { found=0 }
		}
	' "$map_file")

	# Extract all key fingerprints
	local keys
	keys=$(echo "$keys_text" | grep -oP '0x[0-9A-Fa-f]+' || true)

	if [ -z "$keys" ]; then
		echo "NO_OLD_KEY"
		return 0
	fi

	local result=""
	while IFS= read -r key; do
		[ -z "$key" ] && continue
		local bare_key="${key#0x}"

		# Try to fetch the key from keyservers
		local fetched=false
		for server in "${KEYSERVERS[@]}"; do
			if run_gpg --keyserver "$server" --recv-keys "$bare_key" 2>/dev/null; then
				fetched=true
				break
			fi
		done

		if ! $fetched; then
			result="${result}${key}:FETCH_FAILED "
			continue
		fi

		# Check expiry via --list-keys --with-colons (field 7 is expiry timestamp)
		local key_info
		key_info=$(run_gpg --list-keys --with-colons "$bare_key" 2>/dev/null || true)

		local expiry
		expiry=$(echo "$key_info" | grep '^pub:' | head -1 | cut -d: -f7)

		if [ -n "$expiry" ]; then
			local expiry_date
			expiry_date=$(date -d "@$expiry" "+%Y-%m-%d" 2>/dev/null || echo "unknown")
			local now
			now=$(date +%s)
			if [ "$expiry" -lt "$now" ]; then
				result="${result}${key}:EXPIRED:${expiry_date} "
			else
				result="${result}${key}:VALID_UNTIL:${expiry_date} "
			fi
		else
			result="${result}${key}:NO_EXPIRY "
		fi
	done <<< "$keys"

	echo "$result"
}

# Update pgp-keys-map.list: add a new fingerprint to an existing or new entry.
# Args: entryPattern fingerprint mapFile
update_keys_map() {
	local entry_pattern="$1"
	local fingerprint="$2"
	local map_file="$3"

	# Check if fingerprint already exists in the file
	if grep -qF "$fingerprint" "$map_file"; then
		log_info "Fingerprint $fingerprint already exists in $map_file"
		return 0
	fi

	# Escape the pattern for grep
	local escaped="${entry_pattern//./\\.}"
	escaped="${escaped//\*/\\*}"
	escaped="${escaped//:/\\:}"

	# Find the first line number of the entry
	local first_line
	first_line=$(grep -n "^${escaped}[[:space:]]" "$map_file" | head -1 | cut -d: -f1)

	if [ -z "$first_line" ]; then
		# Entry not found — add new entry at end of file
		log_info "Adding new entry for '$entry_pattern'"
		# Ensure file ends with a newline
		[ -n "$(tail -c 1 "$map_file" 2>/dev/null)" ] && echo "" >> "$map_file"
		printf "%-32s= %s\n" "$entry_pattern" "$fingerprint" >> "$map_file"
		return 0
	fi

	# Find the last line of this entry (continuation lines start with whitespace + 0x)
	local total_lines
	total_lines=$(wc -l < "$map_file")
	local last_line="$first_line"
	local i=$((first_line + 1))
	while [ "$i" -le "$total_lines" ]; do
		local content
		content=$(sed -n "${i}p" "$map_file")
		if [[ "$content" =~ ^[[:space:]]+0x ]]; then
			last_line="$i"
			i=$((i + 1))
		else
			break
		fi
	done

	log_info "Appending key to existing entry '$entry_pattern' (line $last_line)"

	# Reconstruct the file: head + modified last line + continuation + tail
	{
		if [ "$last_line" -gt 1 ]; then
			head -n $((last_line - 1)) "$map_file"
		fi
		local last_content
		last_content=$(sed -n "${last_line}p" "$map_file")
		# Remove any trailing whitespace/backslash, then add continuation
		last_content=$(echo "$last_content" | sed 's/[[:space:]]*$//')
		echo "${last_content}, \\"
		echo "                                  ${fingerprint}"
		if [ "$last_line" -lt "$total_lines" ]; then
			tail -n +$((last_line + 1)) "$map_file"
		fi
	} > "${map_file}.tmp" && mv "${map_file}.tmp" "$map_file"
}

# ============================================================================
# Orchestration
# ============================================================================

# Verify a single failing group: get key, cross-reference, keyserver lookup, old key check.
verify_group() {
	local group="$1"
	local artifact="${FAIL_ARTIFACT[$group]}"
	local version="${FAIL_VERSION[$group]}"
	local packaging="${FAIL_PACKAGING[$group]}"

	log_info "Verifying: $group ($artifact:$version)"

	# 1. Get the signing fingerprint
	local fingerprint
	fingerprint=$(get_signing_key "$group" "$artifact" "$version" "$packaging") || true

	if [ -z "$fingerprint" ]; then
		log_error "  Could not extract signing fingerprint for $group:$artifact:$version"
		RESULT_STATUS[$group]="FAIL"
		RESULT_FINGERPRINT[$group]="UNKNOWN"
		RESULT_CROSSREF[$group]="SKIPPED"
		RESULT_KEYSERVER[$group]="SKIPPED"
		RESULT_UID[$group]="N/A"
		RESULT_OLD_EXPIRY[$group]="N/A"
		return
	fi

	RESULT_FINGERPRINT[$group]="$fingerprint"
	log_info "  Fingerprint: $fingerprint"

	# 2. Cross-reference against another artifact from the same group
	local crossref_result
	crossref_result=$(cross_reference "$group" "$artifact" "$version" "$fingerprint") || true

	if [[ "$crossref_result" == MATCH:* ]]; then
		local cross_detail="${crossref_result#MATCH:}"
		RESULT_CROSSREF[$group]="PASS"
		RESULT_CROSSREF_DETAIL[$group]="$cross_detail"
		log_info "  Cross-ref: $cross_detail (same key)"
	else
		RESULT_CROSSREF[$group]="FAIL"
		RESULT_CROSSREF_DETAIL[$group]="$crossref_result"
		log_warn "  Cross-ref: $crossref_result"
	fi

	# 3. Look up on public keyservers
	local keyserver_result
	keyserver_result=$(lookup_keyserver "$fingerprint") || true

	if [ "$keyserver_result" != "NOT_FOUND" ]; then
		RESULT_KEYSERVER[$group]="PASS"
		RESULT_KEYSERVER_NAME[$group]="$keyserver_result"
		log_info "  Keyserver: found on $keyserver_result"

		# Get UID
		local uid
		uid=$(get_key_uid "$fingerprint")
		RESULT_UID[$group]="$uid"
		log_info "  UID: $uid"
	else
		RESULT_KEYSERVER[$group]="FAIL"
		RESULT_KEYSERVER_NAME[$group]="none"
		RESULT_UID[$group]="N/A"
		log_warn "  Keyserver: NOT FOUND on any server"
	fi

	# 4. Find the map entry and check old key
	local map_entry
	map_entry=$(find_map_entry "$group" "$artifact" "$MAP_FILE") || true

	if [ -n "$map_entry" ]; then
		RESULT_MAP_ENTRY[$group]="$map_entry"
		local old_key_result
		old_key_result=$(check_old_key "$map_entry" "$MAP_FILE")
		RESULT_OLD_EXPIRY[$group]="$old_key_result"

		if [[ "$old_key_result" == *"EXPIRED"* ]]; then
			log_info "  Old key: expired ($old_key_result)"
		elif [[ "$old_key_result" == "NO_OLD_KEY" ]]; then
			log_info "  Old key: no existing entry"
		else
			log_info "  Old key: $old_key_result"
		fi
	else
		RESULT_MAP_ENTRY[$group]="$group"
		RESULT_OLD_EXPIRY[$group]="NO_OLD_KEY"
		log_info "  Old key: no existing entry in map"
	fi

	# 5. Determine overall status — pass if at least one check succeeded
	if [[ "${RESULT_CROSSREF[$group]}" == "PASS" || "${RESULT_KEYSERVER[$group]}" == "PASS" ]]; then
		RESULT_STATUS[$group]="PASS"
	else
		RESULT_STATUS[$group]="FAIL"
	fi
}

# Print a formatted report of all results.
print_report() {
	echo ""
	echo "=== PGP Key Verification Report ==="
	echo ""

	for group in "${FAIL_GROUPS[@]}"; do
		local status="${RESULT_STATUS[$group]}"
		local fingerprint="${RESULT_FINGERPRINT[$group]}"

		if [ "$status" = "PASS" ]; then
			printf '\033[32m[PASS]\033[0m %s\n' "$group"
		else
			printf '\033[31m[FAIL]\033[0m %s\n' "$group"
		fi

		echo "  Fingerprint:  $fingerprint"

		# Cross-ref
		if [ "${RESULT_CROSSREF[$group]}" = "PASS" ]; then
			local detail="${RESULT_CROSSREF_DETAIL[$group]}"
			# detail is "artifact:version"
			local cr_art="${detail%%:*}"
			local cr_ver="${detail#*:}"
			printf '  Cross-ref:    %s:%s \033[32m✓\033[0m (same key)\n' "$cr_art" "$cr_ver"
		else
			local detail="${RESULT_CROSSREF_DETAIL[$group]}"
			printf '  Cross-ref:    \033[31mFAILED\033[0m - %s\n' "$detail"
		fi

		# Keyserver
		if [ "${RESULT_KEYSERVER[$group]}" = "PASS" ]; then
			printf '  Keyserver:    %s \033[32m✓\033[0m\n' "${RESULT_KEYSERVER_NAME[$group]}"
		else
			printf '  Keyserver:    \033[31mNOT FOUND\033[0m on any server\n'
		fi

		# UID
		echo "  UID:          ${RESULT_UID[$group]}"

		# Old key
		local old="${RESULT_OLD_EXPIRY[$group]}"
		if [[ "$old" == "NO_OLD_KEY" ]]; then
			echo "  Old key:      (no previous entry)"
		else
			echo "  Old key:      $old"
		fi

		# Action for failures
		if [ "$status" = "FAIL" ]; then
			printf '  Action:       \033[31mMANUAL REVIEW REQUIRED\033[0m\n'
		fi

		echo ""
	done

	# Print proposed additions
	local has_additions=false
	for group in "${FAIL_GROUPS[@]}"; do
		if [ "${RESULT_STATUS[$group]}" = "PASS" ]; then
			has_additions=true
			break
		fi
	done

	if $has_additions; then
		echo "--- Proposed pgp-keys-map.list updates ---"
		for group in "${FAIL_GROUPS[@]}"; do
			if [ "${RESULT_STATUS[$group]}" = "PASS" ]; then
				local entry="${RESULT_MAP_ENTRY[$group]}"
				printf '%-32s+= %s\n' "$entry" "${RESULT_FINGERPRINT[$group]}"
			fi
		done
		echo ""
	fi
}

# Apply fixes: update pgp-keys-map.list for all PASS entries.
apply_fixes() {
	local applied=0

	for group in "${FAIL_GROUPS[@]}"; do
		if [ "${RESULT_STATUS[$group]}" = "PASS" ]; then
			local entry="${RESULT_MAP_ENTRY[$group]}"
			local fingerprint="${RESULT_FINGERPRINT[$group]}"
			update_keys_map "$entry" "$fingerprint" "$MAP_FILE"
			applied=$((applied + 1))
		else
			log_warn "Skipping $group — failed validation"
		fi
	done

	if [ "$applied" -gt 0 ]; then
		log_info "Updated $applied entry/entries in $MAP_FILE"
	else
		log_warn "No entries updated"
	fi
}

# ============================================================================
# Main
# ============================================================================

main() {
	# Parse arguments
	while [ $# -gt 0 ]; do
		case "$1" in
			--check) MODE="check"; shift ;;
			--fix)   MODE="fix"; shift ;;
			--help)  usage; exit 0 ;;
			*)
				log_error "Unknown argument: $1"
				usage
				exit 1
				;;
		esac
	done

	# Check prerequisites
	for cmd in mvn curl gpg; do
		if ! command -v "$cmd" &>/dev/null; then
			log_error "Required command not found: $cmd"
			exit 1
		fi
	done

	if [ ! -f "$MAP_FILE" ]; then
		log_error "PGP keys map not found: $MAP_FILE"
		exit 1
	fi

	# Set up temp working directory with GPG isolation
	WORK_DIR=$(mktemp -d)
	trap 'rm -rf "$WORK_DIR"' EXIT
	mkdir -p "$WORK_DIR/gnupg"
	chmod 700 "$WORK_DIR/gnupg"

	# Step 1: Run pgpverify
	log_step "Step 1: Running PGP verification"
	if run_pgpverify; then
		log_info "All PGP signatures valid — nothing to do"
		exit 0
	fi

	# Step 2: Parse failures
	log_step "Step 2: Parsing failures"
	parse_failures

	if [ ${#FAIL_GROUPS[@]} -eq 0 ]; then
		log_error "PGP verification failed but no specific failures could be parsed"
		log_error "Review the Maven output for details"
		exit 1
	fi

	log_info "Found ${#FAIL_GROUPS[@]} group(s) with PGP key issues"

	# Step 3: Verify each group
	log_step "Step 3: Verifying each failing group"
	for group in "${FAIL_GROUPS[@]}"; do
		verify_group "$group"
	done

	# Step 4: Report
	log_step "Step 4: Results"
	print_report

	# Step 5: Apply fixes if requested
	if [ "$MODE" = "fix" ]; then
		log_step "Step 5: Applying fixes"
		apply_fixes
	fi

	# Exit code: 0 if all validated, 1 if any failed
	for group in "${FAIL_GROUPS[@]}"; do
		if [ "${RESULT_STATUS[$group]}" != "PASS" ]; then
			exit 1
		fi
	done

	exit 0
}

main "$@"
