#!/bin/bash

# PostToolUse hook: verifies that lines Claude just added to a file obey the mechanical subset of
# .editorconfig, so formatting drift between IntelliJ IDEA and Claude's edits surfaces in the same
# turn instead of in review. Mirrors the structure of lint-proto-on-edit.sh.
#
# WHERE THE THRESHOLDS COME FROM
# Nothing about the project style is hardcoded below. indent_style, indent_size/tab_width,
# max_line_length, trim_trailing_whitespace and end_of_line are resolved out of .editorconfig at run
# time, applying sections in file order with last-matching-section-wins, exactly as EditorConfig
# itself does. Re-exporting .editorconfig from IDEA with different values therefore changes what
# this hook enforces without anyone editing this script. The path carve-outs at the end of
# .editorconfig (max_line_length = off, trim_trailing_whitespace = false) are honoured through the
# same resolution.
#
# WHY ONLY *ADDED* LINES, AND NOT THE WHOLE FILE
# .editorconfig describes the style the project is converging on, not the style every existing file
# already has. Measured across the 5,766 product/test .java files (generated, vendored and
# documentation trees excluded):
#   -   243 files (4.2%, 11,140 lines) are space-indented against the tab standard
#   - 3,118 files (54.1%) contain at least one line over 120 columns
#   -    55 files carry trailing whitespace
# Checking whole files would therefore block edits on pre-existing violations the author never
# touched. The hook takes the added side of the diff against HEAD; a file that is untracked (newly
# created) is checked in full, since every one of its lines is new.
#
# Note that "added" means *changed since HEAD*, not "written by this tool call". Edits already
# sitting uncommitted in the working tree are in scope too, so a violation may point at a line a
# human wrote earlier in the same session. That is the intended trade-off: the alternative is
# tracking per-call line ranges, which the tool input does not carry.
#
# WHY THESE FOUR CHECKS AND NOT MORE
# .editorconfig holds 1,143 properties, and 1,111 of them are ij_* keys that only IntelliJ can
# apply. No external tool can verify those, which is why .idea/codeStyles/Project.xml stays the
# source of truth for wrapping, alignment, blank lines and import layout. Of the core properties
# that remain, these four are the ones checkable per line without running a formatter engine.
#
# Do NOT replace the indentation check with editorconfig-checker: it has no concept of IntelliJ's
# SMART_TABS (tabs to indent, spaces to align) and reports every aligned multi-catch and parameter
# list as "spaces instead of tabs". It is still the right tool for a whole-repository audit, since
# it reads .editorconfig directly and cannot drift:
#   npx --yes editorconfig-checker@latest --disable-indentation --disable-max-line-length
#
# All per-line work happens inside awk on purpose. A "while IFS=$'\t' read -r lineno content" loop
# silently strips the content's leading tabs (tab is IFS whitespace), which turns every
# tab-indented, space-aligned SMART_TABS line into a false "space indentation" report.

set -euo pipefail

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
EDITORCONFIG="${PROJECT_DIR}/.editorconfig"

# An internal failure must never be mistaken for "no violations". Under `set -e` a failing command
# substitution would abort the hook with that command's status, and any non-zero status other than 2
# is treated as a *non-blocking* hook error -- enforcement would switch itself off in silence.
# Reporting it as a blocking error is the only way it reaches Claude at all.
fail_internal() {
	echo "editorconfig-check-on-edit.sh: $1" >&2
	echo "The .editorconfig check could not run, so this edit is unverified - fix the hook." >&2
	exit 2
}

INPUT="$(cat)"
FILE_PATH="$(printf '%s' "${INPUT}" | jq -r '.tool_input.file_path // empty' 2>/dev/null || true)"

[[ -n "${FILE_PATH}" ]] || exit 0
[[ -f "${FILE_PATH}" ]] || exit 0

# No .editorconfig, nothing to enforce. This is a silent no-op rather than an error because the hook
# has to stay usable in a checkout that predates the file.
[[ -f "${EDITORCONFIG}" ]] || exit 0

# Normalise to a repository-relative path; ignore anything outside the project.
ABS_PATH="$(cd "$(dirname "${FILE_PATH}")" 2>/dev/null && pwd)/$(basename "${FILE_PATH}")" || exit 0
case "${ABS_PATH}" in
	"${PROJECT_DIR}"/*) REL_PATH="${ABS_PATH#"${PROJECT_DIR}"/}" ;;
	*) exit 0 ;;
esac

# Build output, and trees whose whitespace is deliberately not ours to normalise. Vendored upstream
# code (evita_roaring_bitmap, the Undertow routing tree) must keep upstream's exact formatting or
# every future upstream replay becomes a merge conflict. The documentation examples are editorial
# and measurably space-indented (406 of 680 .java files), so tab enforcement there is pure noise --
# this is a deliberate superset of .editorconfig's own carve-outs for the same paths.
#
# The single-star patterns below already match nested paths (e.g. "evita_roaring_bitmap/*" matches
# "evita_roaring_bitmap/src/main/Foo.java"): bash `case` does not apply the pathname-expansion rule
# that stops `*` at `/`, unlike filename globbing (`echo *`). Verified with `case ... in pattern)`.
case "${REL_PATH}" in
	*/target/*|target/*) exit 0 ;;
	*/generated/*) exit 0 ;;
	evita_roaring_bitmap/*) exit 0 ;;
	documentation/*) exit 0 ;;
	evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/utils/path/*) exit 0 ;;
esac

# Only text sources this project actually formats.
case "${REL_PATH}" in
	*.java|*.graphql|*.graphqls|*.proto|*.json|*.xml|*.yml|*.yaml|*.md|*.sh|*.sql|*.evitaql|*.cs) ;;
	*) exit 0 ;;
esac

# indent_style is enforced only for Java, the one language where this hook knows every legitimate
# exemption (JavaDoc continuation lines, SMART_TABS alignment). Other file types in this repository
# mix tabs and spaces too freely for a per-line rule to be anything but noise.
IS_INDENT_CHECKED=0
if [[ "${REL_PATH}" == *.java ]]; then
	IS_INDENT_CHECKED=1
fi

# Resolve the properties .editorconfig assigns to this path. Sections are applied in file order and
# later matches win, so the carve-out sections at the end of the file override the earlier ones.
resolve_properties() {
	awk -v target="$1" '
		# Translate an EditorConfig glob into an ERE. ** spans directory separators, * does not,
		# {a,b} is an alternation, and everything else that means something to a regex is escaped.
		function glob_to_regex(pattern,   out, i, c, len, depth) {
			out = ""
			depth = 0
			len = length(pattern)
			for (i = 1; i <= len; i++) {
				c = substr(pattern, i, 1)
				if (c == "\\") {
					i++
					out = out "\\" substr(pattern, i, 1)
				} else if (c == "*") {
					if (substr(pattern, i + 1, 1) == "*") {
						out = out ".*"
						i++
					} else {
						out = out "[^/]*"
					}
				} else if (c == "?") {
					out = out "[^/]"
				} else if (c == "{") {
					depth++
					out = out "("
				} else if (c == "}") {
					depth--
					out = out ")"
				} else if (c == "," && depth > 0) {
					out = out "|"
				} else if (index(".^$+()|[]", c) > 0) {
					out = out "\\" c
				} else {
					out = out c
				}
			}
			return out
		}
		/^[ \t]*[#;]/ { next }
		/^[ \t]*\[.*\][ \t]*$/ {
			pattern = $0
			sub(/^[ \t]*\[/, "", pattern)
			sub(/\][ \t]*$/, "", pattern)
			regex = glob_to_regex(pattern)
			# A pattern containing no separator matches the file name in any directory.
			regex = (index(pattern, "/") == 0) ? "^(.*/)?" regex "$" : "^" regex "$"
			active = (target ~ regex)
			next
		}
		/^[ \t]*[A-Za-z0-9_]+[ \t]*=/ {
			if (!active) next
			key = $0
			sub(/[ \t]*=.*$/, "", key)
			sub(/^[ \t]+/, "", key)
			value = $0
			sub(/^[^=]*=[ \t]*/, "", value)
			sub(/[ \t]+$/, "", value)
			properties[tolower(key)] = tolower(value)
		}
		END {
			for (key in properties) {
				print key "=" properties[key]
			}
		}
	' "${EDITORCONFIG}"
}

if ! PROPERTIES="$(resolve_properties "${REL_PATH}")"; then
	fail_internal "could not resolve .editorconfig properties for ${REL_PATH}"
fi

property() {
	printf '%s\n' "${PROPERTIES}" | awk -v key="$1" -F'=' '$1 == key { print substr($0, length(key) + 2); exit }'
}

INDENT_STYLE="$(property indent_style)"
MAX_LINE_LENGTH="$(property max_line_length)"
TRIM_TRAILING="$(property trim_trailing_whitespace)"
END_OF_LINE="$(property end_of_line)"
TAB_WIDTH="$(property tab_width)"
[[ -n "${TAB_WIDTH}" ]] || TAB_WIDTH="$(property indent_size)"
[[ "${TAB_WIDTH}" =~ ^[0-9]+$ ]] || TAB_WIDTH=4

# Emit "<lineno><TAB><content>" for every line this edit added. For a tracked file that means the
# added side of the diff against HEAD; for an untracked file, the whole thing.
collect_added_lines() {
	if git -C "${PROJECT_DIR}" ls-files --error-unmatch "${REL_PATH}" >/dev/null 2>&1; then
		git -C "${PROJECT_DIR}" diff -U0 --no-color HEAD -- "${REL_PATH}" | awk '
			/^@@/ {
				# hunk header: @@ -old,count +new,count @@ -- take the start of the new-side range
				split($3, range, ",")
				lineno = substr(range[1], 2) + 0
				next
			}
			/^\+\+\+/ { next }
			/^\+/ {
				printf "%d\t%s\n", lineno, substr($0, 2)
				lineno++
				next
			}
		'
	else
		awk '{ printf "%d\t%s\n", NR, $0 }' "${ABS_PATH}"
	fi
}

if ! VIOLATIONS="$(collect_added_lines | awk \
	-v file="${REL_PATH}" \
	-v indent_style="${INDENT_STYLE}" \
	-v max_line_length="${MAX_LINE_LENGTH}" \
	-v tab_width="${TAB_WIDTH}" \
	-v trim_trailing="${TRIM_TRAILING}" \
	-v end_of_line="${END_OF_LINE}" \
	-v check_indent="${IS_INDENT_CHECKED:-0}" '
	# Columns occupied on screen: a tab advances to the next tab stop rather than counting as one
	# character, which is how IDEA measures a line against the hard wrap.
	function display_width(text,   i, len, column) {
		column = 0
		len = length(text)
		for (i = 1; i <= len; i++) {
			if (substr(text, i, 1) == "\t") {
				column += tab_width - (column % tab_width)
			} else {
				column++
			}
		}
		return column
	}
	function report(lineno, message) {
		printf "  %s:%d: %s\n", file, lineno, message
	}
	{
		lineno = $1 + 0
		content = substr($0, length($1) + 2)

		# indent_style. Only checked where the exemptions are known: in Java a leading space is
		# legitimate as a block-comment continuation (" * ..."), and tabs followed by spaces are
		# intended -- that is SMART_TABS alignment, not a violation.
		if (check_indent && indent_style == "tab" && content ~ /^ [^*]/) {
			report(lineno, "space indentation - .editorconfig sets indent_style = tab here")
		} else if (check_indent && indent_style == "space" && content ~ /^\t/) {
			report(lineno, "tab indentation - .editorconfig sets indent_style = space here")
		}

		if (trim_trailing == "true" && content ~ /[ \t]+$/) {
			report(lineno, "trailing whitespace (.editorconfig trim_trailing_whitespace = true)")
		}

		if (end_of_line == "lf" && index(content, "\r") > 0) {
			report(lineno, "carriage return - line endings must be LF (.editorconfig end_of_line = lf)")
		}

		if (max_line_length ~ /^[0-9]+$/) {
			width = display_width(content)
			if (width > max_line_length + 0) {
				report(lineno, sprintf("line is %d columns, over the %s limit (.editorconfig max_line_length)", \
					width, max_line_length))
			}
		}
	}
')"; then
	fail_internal "the line checker failed while examining ${REL_PATH}"
fi

if [[ -n "${VIOLATIONS}" ]]; then
	echo "The lines just written to ${REL_PATH} do not match .editorconfig - fix them before continuing:" >&2
	printf '%s\n' "${VIOLATIONS}" >&2
	exit 2
fi

exit 0
