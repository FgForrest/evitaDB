#!/usr/bin/env bash
#
# consolidate-version-bumps.sh
# --------------------------------------------------------------------------
# Helper toolkit for consolidating Dependabot "bump" PRs into a single commit
# and resolving Dependabot security alerts. Companion to the
# `consolidate-version-bumps` skill (.claude/skills/consolidate-version-bumps).
#
# The mechanical, error-prone, security-critical steps are automated here;
# the judgement calls (which bumps are safe, how to test, what to commit)
# stay with the operator following the skill.
#
# Read-only subcommands (safe to run anytime):
#   list                 List open Dependabot bump PRs (maven + github_actions).
#   diffs [PR...]        Print the diff of each bump PR (all open bump PRs if none given).
#   verify-pins [MODE]   Verify every GitHub Action is SHA-pinned AND that the
#                        pinned SHA resolves from its `# vX.Y.Z` tag.
#                          MODE=workflows (default) audits .github/workflows/*.
#                          MODE=prs audits the added `uses:` lines in open
#                          Dependabot github_actions PRs.
#   verify-tag OWNER/REPO TAG SHA
#                        Verify a single pin: does TAG resolve to SHA?
#   alerts               Group open Dependabot security alerts by advisory.
#
# Mutating subcommands (run deliberately, per the skill):
#   close-prs COMMIT PR [PR...]
#                        Close each PR with a comment pointing at COMMIT.
#   dismiss-alert NUMBER REASON COMMENT
#                        Dismiss a Dependabot alert. REASON is one of:
#                        fix_started | inaccurate | no_bandwidth | not_used |
#                        tolerable_risk. COMMENT max 280 chars.
#
# Requirements: bash, gh (authenticated), jq. Run from the repository root.
# --------------------------------------------------------------------------
set -euo pipefail

REPO="${EVITA_REPO:-FgForrest/evitaDB}"
WORKFLOW_DIR=".github/workflows"

# ---- helpers -------------------------------------------------------------

die()  { printf 'error: %s\n' "$*" >&2; exit 1; }
note() { printf '%s\n' "$*" >&2; }

need() { command -v "$1" >/dev/null 2>&1 || die "required tool not found: $1"; }
preflight() { need gh; need jq; }

# Resolve a git ref (tag/branch/sha) in OWNER/REPO to the commit SHA it points at.
# `/commits/{ref}` dereferences annotated tags for us, so this is the correct
# check for "does this version tag really point at this pinned SHA?".
# Only a real 40-char SHA is echoed; error bodies / null / missing tags -> empty.
resolve_ref_sha() {
	local out
	out="$(gh api "repos/$1/commits/$2" --jq '.sha' 2>/dev/null || true)"
	printf '%s' "$out" | grep -oE '^[0-9a-fA-F]{40}$' || true
}

# Resolve a version TAG to its SHA, tolerating the common `v`-prefix mismatch
# between a workflow's `# vX.Y.Z` comment and an action whose real tag is `X.Y.Z`
# (or vice versa). Tries the tag as written, then with the `v` prefix toggled.
resolve_tag_sha() {
	local repo="$1" tag="$2" sha alt
	sha="$(resolve_ref_sha "$repo" "$tag")"
	if [ -z "$sha" ]; then
		case "$tag" in
			v*) alt="${tag#v}" ;;
			*)  alt="v$tag" ;;
		esac
		sha="$(resolve_ref_sha "$repo" "$alt")"
	fi
	printf '%s' "$sha"
}

# ---- read-only subcommands ----------------------------------------------

cmd_list() {
	preflight
	note "Open Dependabot bump PRs on $REPO:"
	gh pr list --repo "$REPO" --limit 200 \
		--json number,title,headRefName,author \
		--jq '.[]
			| select(.author.login == "app/dependabot" or (.headRefName | startswith("dependabot/")))
			| [ (.number|tostring),
			    (if (.headRefName|test("github_actions")) then "action" else "maven " end),
			    .title ]
			| @tsv' \
		| sort -n \
		| awk -F'\t' '{ printf "#%-6s [%s] %s\n", $1, $2, $3 }'
}

# Return the list of open Dependabot bump PR numbers, optionally filtered to a kind.
# $1 (optional): "github_actions" | "maven" to filter by ecosystem.
_bump_pr_numbers() {
	local filter="${1:-}"
	gh pr list --repo "$REPO" --limit 200 \
		--json number,headRefName,author \
		--jq --arg f "$filter" '.[]
			| select(.author.login == "app/dependabot" or (.headRefName | startswith("dependabot/")))
			| select($f == "" or (.headRefName | test($f)))
			| .number' \
		| sort -n
}

cmd_diffs() {
	preflight
	local prs=("$@")
	if [ ${#prs[@]} -eq 0 ]; then
		mapfile -t prs < <(_bump_pr_numbers)
	fi
	[ ${#prs[@]} -eq 0 ] && { note "no open bump PRs"; return 0; }
	local pr
	for pr in "${prs[@]}"; do
		printf '========== PR #%s ==========\n' "$pr"
		gh pr diff "$pr" --repo "$REPO"
		printf '\n'
	done
}

# Verify a set of "OWNER/REPO SHA TAG" triples on stdin.
# Prints a per-pin verdict and exits non-zero if any pin fails.
_verify_stream() {
	local fail=0 total=0 owner_repo sha tag actual
	while read -r owner_repo sha tag; do
		[ -z "${owner_repo:-}" ] && continue
		total=$((total + 1))
		if [ -z "${tag:-}" ]; then
			printf '  ⚠️  %-42s pinned @%s but NO version comment — cannot verify\n' "$owner_repo" "${sha:0:12}"
			fail=1
			continue
		fi
		if ! printf '%s' "$sha" | grep -qE '^[0-9a-fA-F]{40}$'; then
			printf '  ⚠️  %-42s @%s is NOT a 40-char SHA (tag/branch ref) — not immutable\n' "$owner_repo" "$sha"
			fail=1
			continue
		fi
		actual="$(resolve_tag_sha "$owner_repo" "$tag")"
		if [ -z "$actual" ]; then
			printf '  ❓ %-42s %-10s could not resolve tag (renamed? deleted? network?)\n' "$owner_repo" "$tag"
			fail=1
		elif [ "$actual" = "$sha" ]; then
			printf '  ✅ %-42s %-10s %s\n' "$owner_repo" "$tag" "${sha:0:12}"
		else
			printf '  ❌ %-42s %-10s pinned=%s tag=%s  MISMATCH\n' \
				"$owner_repo" "$tag" "${sha:0:12}" "${actual:0:12}"
			fail=1
		fi
	done
	note "checked $total pin(s)"
	return $fail
}

# Extract "OWNER/REPO SHA TAG" triples from a stream of `uses:` lines on stdin.
# Handles:  uses: owner/repo@<40-hex> # vX.Y.Z   (the good case)
#     and:  uses: owner/repo@<ref>               (flagged: no comment)
_extract_uses() {
	grep -oE 'uses:[[:space:]]*[^@[:space:]]+@[^[:space:]]+([[:space:]]*#[[:space:]]*v?[^[:space:]]+)?' \
		| sed -E 's/^uses:[[:space:]]*//' \
		| awk '{
			n = split($1, a, "@");
			repo = a[1]; sha = a[2];
			tag = "";
			for (i = 2; i <= NF; i++) if ($i == "#") { tag = $(i+1); break }
			print repo, sha, tag
		}'
}

cmd_verify_pins() {
	preflight
	local mode="${1:-workflows}"
	case "$mode" in
		workflows)
			[ -d "$WORKFLOW_DIR" ] || die "no $WORKFLOW_DIR (run from repo root)"
			note "Auditing GitHub Action pins in $WORKFLOW_DIR/ against their version tags:"
			grep -rhoE 'uses:[[:space:]]*[^@[:space:]]+@[^[:space:]]+([[:space:]]*#[[:space:]]*v?[^[:space:]]+)?' "$WORKFLOW_DIR" \
				| _extract_uses | sort -u | _verify_stream
			;;
		prs)
			note "Auditing added action pins in open Dependabot github_actions PRs:"
			local prs pr
			mapfile -t prs < <(_bump_pr_numbers github_actions)
			[ ${#prs[@]} -eq 0 ] && { note "no open github_actions bump PRs"; return 0; }
			for pr in "${prs[@]}"; do
				gh pr diff "$pr" --repo "$REPO" \
					| grep -E '^\+' | grep -E 'uses:' | sed -E 's/^\+//'
			done | _extract_uses | sort -u | _verify_stream
			;;
		*) die "unknown verify-pins mode: $mode (use 'workflows' or 'prs')" ;;
	esac
}

cmd_verify_tag() {
	preflight
	[ $# -eq 3 ] || die "usage: verify-tag OWNER/REPO TAG SHA"
	printf '%s %s %s\n' "$1" "$3" "$2" | _verify_stream
}

cmd_alerts() {
	preflight
	note "Open Dependabot security alerts on $REPO (grouped by advisory):"
	gh api "repos/$REPO/dependabot/alerts" --paginate -X GET -f state=open \
		| jq -r '
			group_by(.security_advisory.ghsa_id)[]
			| "\(.[0].security_advisory.severity | ascii_upcase)\t"
			+ "\(.[0].dependency.package.name)\t"
			+ "patched=\(.[0].security_vulnerability.first_patched_version.identifier // "NONE")\t"
			+ "\(.[0].security_advisory.ghsa_id)\t"
			+ "\(length) manifest(s): \([.[].dependency.manifest_path] | unique | join(", "))"' \
		| sort \
		| awk -F'\t' '{ printf "[%-6s] %-45s %-16s %s\n           %s\n", $1, $2, $3, $4, $5 }'
	local n
	n=$(gh api "repos/$REPO/dependabot/alerts" --paginate -X GET -f state=open | jq 'length')
	note "total open alert rows: $n"
}

# ---- mutating subcommands ------------------------------------------------

cmd_close_prs() {
	preflight
	[ $# -ge 2 ] || die "usage: close-prs COMMIT PR [PR...]"
	local commit="$1"; shift
	local comment pr
	comment="Superseded by the consolidated dependency-bump commit on \`dev\`: ${commit}. This exact version change is included there and has been built and tested locally, so closing this individual PR."
	for pr in "$@"; do
		note "closing #$pr -> $commit"
		gh pr close "$pr" --repo "$REPO" --comment "$comment"
	done
}

cmd_dismiss_alert() {
	preflight
	[ $# -eq 3 ] || die "usage: dismiss-alert NUMBER REASON COMMENT"
	local number="$1" reason="$2" comment="$3"
	case "$reason" in
		fix_started|inaccurate|no_bandwidth|not_used|tolerable_risk) ;;
		*) die "invalid reason: $reason (fix_started|inaccurate|no_bandwidth|not_used|tolerable_risk)" ;;
	esac
	[ "${#comment}" -le 280 ] || die "comment is ${#comment} chars; GitHub allows max 280"
	gh api --method PATCH "repos/$REPO/dependabot/alerts/$number" \
		-f state=dismissed \
		-f dismissed_reason="$reason" \
		-f dismissed_comment="$comment" \
		--jq '"#\(.number) -> state=\(.state) reason=\(.dismissed_reason)"'
}

# ---- dispatch ------------------------------------------------------------

usage() {
	sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//'
}

main() {
	local cmd="${1:-}"; shift || true
	case "$cmd" in
		list)          cmd_list "$@" ;;
		diffs)         cmd_diffs "$@" ;;
		verify-pins)   cmd_verify_pins "$@" ;;
		verify-tag)    cmd_verify_tag "$@" ;;
		alerts)        cmd_alerts "$@" ;;
		close-prs)     cmd_close_prs "$@" ;;
		dismiss-alert) cmd_dismiss_alert "$@" ;;
		-h|--help|help|"") usage ;;
		*) die "unknown subcommand: $cmd (run --help)" ;;
	esac
}

main "$@"
