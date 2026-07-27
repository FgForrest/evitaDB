#!/usr/bin/env bash
#
# audit-deprecated-since.sh
# --------------------------------------------------------------------------
# Audits @Deprecated(since = "X") annotations against the release in which the
# annotated element actually became deprecated.
#
# Convention (JDK's own @Deprecated#since javadoc, the de facto industry
# standard): "since" is the version in which the element BECAME deprecated -
# the first release that ships the deprecation notice. It is NOT the last
# version the element was still valid, and NOT the version it was introduced.
#
# For each @Deprecated(since = "X") occurrence, this script:
#   1. Walks the FULL git history of that annotation line (git log --reverse
#      -L) to find the oldest commit whose added text already contains
#      "@Deprecated" - the true first-deprecation commit. This correctly
#      skips later commits that only reformatted the annotation or added the
#      since= clause to a pre-existing bare @Deprecated (a plain `git blame`
#      would misattribute those - this was the actual bug found in the first
#      version of this tool).
#   2. Resolves the first release tag that actually contains that commit
#      (git describe --contains) - the authoritative "first shipped in"
#      version. Older sibling tools in this directory (process_deprecated.sh,
#      update_deprecated.sh) instead use `git tag --merged`, which finds the
#      newest tag BEFORE the commit - i.e. the last version already released
#      at deprecation time, always one (or more) release behind the correct
#      answer. That is the root cause of most of the drift this tool finds.
#   3. Reports any declared "since" that doesn't match.
#
# Known limitation: `git log -L` does not follow renames/moves across files,
# and can lose tracking across very large same-file diffs (a method physically
# relocated by a big refactor can look "freshly inserted"). A flagged mismatch
# is a lead, not a verdict - always read the origin commit's diff
# (`git show <hash> -- <file>`) before changing anything.
#
# The Kryo backward-compatibility reader family (*Serializer_20XX_Y.java,
# Migration_20XX_Y.java) is excluded by default: those deliberately encode a
# *different* meaning in "since" - the last on-disk format vintage the frozen
# reader can still parse, baked into the class name - not when the class was
# created. Pass --include-bwc to audit them anyway (expect mostly false
# positives; see the exclusion note in the output).
#
# Usage:
#   tools/audit-deprecated-since.sh [--include-bwc] [--verbose] [path ...]
#
# With no path arguments, scans the whole repository. One or more paths (files
# or directories) restrict the scan, e.g.:
#   tools/audit-deprecated-since.sh evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java
#
set -euo pipefail

INCLUDE_BWC=0
VERBOSE=0
PATHS=()

for arg in "$@"; do
	case "$arg" in
		--include-bwc) INCLUDE_BWC=1 ;;
		--verbose) VERBOSE=1 ;;
		-h|--help)
			sed -n '2,45p' "$0" | sed 's/^# \{0,1\}//'
			exit 0
			;;
		*) PATHS+=("$arg") ;;
	esac
done

REPO_ROOT=$(git rev-parse --show-toplevel)
cd "$REPO_ROOT"

# This environment may have a global gitconfig overriding the default `git log`
# pretty-format (seen in the wild: a custom "Commit:  ..." header instead of
# "commit <hash>"), which silently breaks any parsing that assumes the
# standard format. Force it explicitly on every invocation.
git() { command git -c format.pretty=medium "$@"; }

BWC_PATTERN='(Serializer|Migration)_20[0-9]{2}_[0-9]+\.java$'

normalize_version() {
	# "v2026.2.RC1-SNAPSHOT" / "2026.2.RC1-SNAPSHOT" -> "2026.2"
	echo "$1" | grep -oE '[0-9]{4}\.[0-9]+' | head -1
}

if [ "${#PATHS[@]}" -eq 0 ]; then
	mapfile -t FILES < <(git ls-files '*.java' | xargs -r grep -l '@Deprecated(since' 2>/dev/null || true)
else
	mapfile -t FILES < <(git ls-files -- "${PATHS[@]}" | grep '\.java$' | xargs -r grep -l '@Deprecated(since' 2>/dev/null || true)
fi

TOTAL=0
EXCLUDED_BWC=0
MATCHED=0
UNRESOLVED=0
declare -a MISMATCHES=()

for file in "${FILES[@]}"; do
	if [ "$INCLUDE_BWC" -eq 0 ] && [[ "$file" =~ $BWC_PATTERN ]]; then
		count=$(grep -c '@Deprecated(since' "$file" || true)
		EXCLUDED_BWC=$((EXCLUDED_BWC + count))
		continue
	fi

	while IFS=: read -r lineno since_val; do
		[ -z "$lineno" ] && continue
		TOTAL=$((TOTAL + 1))

		# Oldest commit whose ADDED text at this line already contains "@Deprecated".
		origin=$(
			git log --reverse -L"${lineno},${lineno}:${file}" 2>/dev/null | awk '
				/^commit / { hash=$2 }
				/^\+/ && !/^\+\+\+/ && /@Deprecated/ { print hash; exit }
			'
		)

		if [ -z "$origin" ]; then
			UNRESOLVED=$((UNRESOLVED + 1))
			[ "$VERBOSE" -eq 1 ] && echo "UNRESOLVED  ${file}:${lineno}  since=${since_val}  (could not trace origin - check for renames)"
			continue
		fi

		shipped_tag=$(git describe --tags --contains --match 'v20*' "$origin" 2>/dev/null | sed -E 's/[~^].*$//' || true)
		if [ -n "$shipped_tag" ]; then
			correct=$(normalize_version "$shipped_tag")
			basis="released in ${shipped_tag}"
		else
			# Not yet released: fall back to the reactor pom.xml version in effect
			# at the origin commit (best-effort - the commit may still ship one
			# train later than this SNAPSHOT version suggests).
			pom_version=$(git show "${origin}:pom.xml" 2>/dev/null | grep -oE '<version>[^<]+</version>' | head -1 | sed -E 's/<\/?version>//g' || true)
			correct=$(normalize_version "${pom_version:-}")
			basis="not yet released; dev version at origin was ${pom_version:-unknown}"
		fi

		declared=$(normalize_version "$since_val")

		if [ -n "$correct" ] && [ "$declared" = "$correct" ]; then
			MATCHED=$((MATCHED + 1))
		else
			origin_date=$(git show -s --format=%ci "$origin" 2>/dev/null | cut -d' ' -f1)
			MISMATCHES+=("${file}:${lineno}  declared=${since_val}  correct=${correct:-?}  origin=${origin:0:10} (${origin_date}, ${basis})")
		fi
	done < <(grep -noE '@Deprecated\(since *= *"[^"]+"' "$file" | sed -E 's/@Deprecated\(since *= *"([^"]+)"/\1/')
done

echo "# Checked ${TOTAL} @Deprecated(since = ...) occurrence(s)"
[ "$INCLUDE_BWC" -eq 0 ] && echo "# Excluded ${EXCLUDED_BWC} Kryo BWC reader/migration occurrence(s) (--include-bwc to check them too)"
echo "# ${MATCHED} match, ${#MISMATCHES[@]} flagged, ${UNRESOLVED} unresolved"
echo

if [ "${#MISMATCHES[@]}" -gt 0 ]; then
	echo "Flagged (verify by reading the origin commit's diff before changing anything):"
	for line in "${MISMATCHES[@]}"; do
		echo "  ${line}"
	done
fi

exit 0
