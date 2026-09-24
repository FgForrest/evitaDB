---
name: consolidate-version-bumps
description: Use when there are open Dependabot "bump" PRs (maven and/or github_actions, authored by app/dependabot) to assess, test locally, and consolidate into one commit on dev, or when reviewing/resolving Dependabot security alerts. Triggers include "consolidate the bump PRs", "handle the dependabot PRs", "bump dependencies", "close the version-bump PRs". Leaves feature/fix PRs and frozen research artifacts untouched.
allowed-tools: Read, Edit, Write, Grep, Glob, AskUserQuestion, Bash(tools/consolidate-version-bumps.sh list*), Bash(tools/consolidate-version-bumps.sh diffs*), Bash(tools/consolidate-version-bumps.sh verify-pins*), Bash(tools/consolidate-version-bumps.sh verify-tag*), Bash(tools/consolidate-version-bumps.sh alerts*), Bash(mvn *), Bash(git status*), Bash(git diff*), Bash(git log*), Bash(git fetch*), Bash(gh pr list *), Bash(gh pr diff *), Bash(rg *), Bash(grep *), Bash(jq *)
---

# Consolidate Version Bumps

## Overview

Dependabot opens one PR per dependency/action update. This skill assesses **all
open bump PRs together**, applies their exact changes on a fresh branch off
`origin/dev`, verifies GitHub Action SHA pins against their release tags, builds
and runs focused tests, and lands everything as a **single consolidated commit
on `dev`** — then closes the individual PRs and resolves any Dependabot security
alerts. It follows the repository's established consolidated-bump convention: a
single `chore: bump Maven and GitHub Actions dependencies` commit (search
`git log` for the previous one if you want a concrete template).

The mechanical, error-prone steps are automated by
`tools/consolidate-version-bumps.sh`. The judgement calls (which bumps are safe,
how to test, what to commit, whether to push) stay with you.

## Hard Rules

- **Only `app/dependabot` bump PRs.** Never fold a `feat`/`fix`/`refactor`/etc.
  PR into the consolidation. If unsure, `dependabot/` head-branch prefix + the
  `app/dependabot` author is the signal.
- **GitHub Actions MUST be SHA-pinned to a SHA that resolves from the version
  tag.** Dependabot already pins by SHA + `# vX.Y.Z` comment; you must *verify*
  each SHA equals the commit its tag points at (`verify-pins`). A bump that
  fails verification does not go in the commit.
- **Never edit frozen research artifacts.** Anything under
  `documentation/research/**` (e.g. `.../nosql/spike_tests`) is frozen in time
  and NOT in the Maven reactor. Do not upgrade it, even to clear an alert.
- **Ask before pushing.** Committing is fine; the push is the operator's
  decision (use `AskUserQuestion`, or an explicit override when non-interactive —
  see *Running from CI*).
- **Do not disturb the operator's working tree.** Work in a `git worktree`, not
  by switching the current branch.
- **Close individual PRs only AFTER the commit is on `dev`** — otherwise
  Dependabot recreates them on its next run.
- **A real, merged fix is resolved as "fixed", not dismissed.** Only dismiss
  alerts that will genuinely never be fixed (see Step 9).

## When to Use

- There are open Dependabot bump PRs to process (`gh pr list` shows
  `app/dependabot` authors), or the user asks to "consolidate/handle the bump
  PRs" or "bump dependencies".
- The user asks to review or clean up the Dependabot security alerts.
- Invoked as `/consolidate-version-bumps`.

## Tooling

`tools/consolidate-version-bumps.sh <subcommand>` (run from the repo root; needs
`gh` authenticated + `jq`). Run `--help` for the full list.

| Subcommand | Purpose |
|---|---|
| `list` | List open Dependabot bump PRs (maven / action). |
| `diffs [PR...]` | Print each bump PR's diff (all open bump PRs if none given). |
| `verify-pins [workflows\|prs]` | **Security gate:** verify every Action is SHA-pinned and each SHA resolves from its `# vX.Y.Z` tag. `workflows` (default) audits the tree; `prs` audits the added `uses:` lines in the open Action PRs. Exit 0 = all good. |
| `verify-tag OWNER/REPO TAG SHA` | Verify one pin (use when hand-applying an Action bump). |
| `alerts` | Group open Dependabot security alerts by advisory. |
| `close-prs COMMIT PR [PR...]` | Close each PR with a comment pointing at COMMIT (**after** the push). |
| `dismiss-alert NUMBER REASON COMMENT` | Dismiss an alert (`REASON` ∈ fix_started, inaccurate, no_bandwidth, not_used, tolerable_risk; COMMENT ≤ 280 chars). |

## Workflow

Copy this checklist and tick items off as you go:

```
- [ ] 1. Enumerate + read the bumps (list, diffs)
- [ ] 2. Assess each (changelogs, coupling, runtime risk)
- [ ] 3. Verify Action SHA pins (verify-pins prs)
- [ ] 4. Fresh worktree off the default branch
- [ ] 5. Apply the exact diffs; confirm the combined diff == sum of the PRs
- [ ] 6. Build (compile gate) + focused runtime tests
- [ ] 7. Commit (chore: bump ...; + separate fix(security): if needed)
- [ ] 8. Ask, then push to the default branch
- [ ] 9. Close PRs + resolve alerts (after the push lands)
- [ ] 10. Clean up the worktree
```

Execute in order. Stop and report on any failure.

### Step 1 — Enumerate and read the bumps
```shell
tools/consolidate-version-bumps.sh list
tools/consolidate-version-bumps.sh diffs
```
Split into **Maven** (pom.xml `<version>`/property changes) and **GitHub
Actions** (`.github/workflows/*.yml` `uses:` SHA changes). Note every changed
file and value — the consolidated diff must equal the sum of the PRs exactly.

### Step 2 — Assess each bump
- Flag **major-version** bumps for extra scrutiny; **read the changelog** before
  assuming breakage rather than guessing (e.g. a major of an assertion library
  may be only a module reorg with no API change).
- Note coupling: some deps must move with a sibling (e.g. `opentelemetry` SDK
  vs `opentelemetry.semconv` — check the SDK compiles against the pinned
  semconv).
- Note which bumps are compile-only vs behavioural at runtime — that drives the
  test plan in Step 6.

### Step 3 — Verify the Action SHA pins (security)
```shell
tools/consolidate-version-bumps.sh verify-pins prs   # the incoming bumps
```
Every Action pin must be ✅. This is the "action pinning best practice" check
and the only local verification Actions can get (they run in CI, not locally).
Do not proceed with any pin that is ❌/❓/⚠️ without resolving it.

### Step 4 — Fresh worktree off the default branch
Compute the worktree path and branch name **once** and reuse the `$WT`/`$BR`/`$BASE`
variables in every later step (the worktree is a sibling of the checkout, so
nothing in the operator's working tree is disturbed and no path is hardcoded):
```shell
BASE="${EVITA_DEFAULT_BRANCH:-dev}"            # this repo's default branch
WT="$(git rev-parse --show-toplevel)-bumps"    # e.g. /path/to/evitaDB -> /path/to/evitaDB-bumps
BR="chore-dependency-bumps-$(date +%Y-%m)"
git fetch origin "$BASE"
git worktree add -b "$BR" "$WT" "origin/$BASE"
```
Branch off the default branch so the final commit fast-forwards cleanly.

### Step 5 — Apply the exact diffs
In the worktree, edit each file to the new version/SHA. Then confirm the diff is
**precisely** the union of the PRs (no extra changes):
```shell
git -C "$WT" diff --stat
```

### Step 6 — Build and test locally
Compile gate (compiles + test-compiles the whole reactor; catches API breaks):
```shell
mvn clean install -DskipTests            # expect exit 0
```
Then **focused runtime tests** for the risky bumps — compile alone does not
catch behavioural changes. Map each bump to its consumers and run them, e.g.:
```shell
mvn -pl evita_test/evita_functional_tests test -Dtest="<consumer test classes>" -DfailIfNoTests=false
```
Find consumers with `rg` (e.g. `rg -l "assertThatJson" evita_test`). Read the
surefire `.txt` reports for the authoritative pass/fail count — do not grep the
Maven console (it truncates). See `.claude/rules/testing.md`.

### Step 7 — Commit
One commit for the bumps, in the established message shape (Maven list +
Actions list + `Ref: #<all bump PR numbers>`):
```
chore: bump Maven and GitHub Actions dependencies
```
A **security-only** bump that has *no* Dependabot PR (e.g. a `logback.version`
patch flagged only by an alert) goes in a **separate** commit so the bump
commit's `Ref:` stays honest:
```
fix(security): bump <dep> <old> -> <new>
```

### Step 8 — Ask, then push to the default branch
Confirm the fast-forward, then ask the operator how to land it (`AskUserQuestion`) —
unless running non-interactively (see *Running from CI / non-interactively*).
House style for these consolidated bump commits is a **direct commit on the
default branch**, which requires branch-protection **bypass rights**. If the
push is rejected (no bypass rights, or a CI token subject to protection), fall
back to opening a single consolidated PR from `$BR` instead.
```shell
git -C "$WT" log --oneline "HEAD..origin/$BASE"   # must be empty (clean FF)
git -C "$WT" push origin "HEAD:$BASE"             # if rejected -> open a PR from "$BR"
```

### Step 9 — Close PRs and resolve alerts (after the push lands on dev)
```shell
tools/consolidate-version-bumps.sh close-prs <commit-sha> <bump-PR-numbers>   # all bump PRs
tools/consolidate-version-bumps.sh alerts                                      # re-check
```
Alert resolution — pick the *correct* resolution per group:
- **A dep you just fixed and merged to `dev`** → do NOT dismiss. Dependabot
  auto-closes it as **"fixed"** on its next rescan of the default branch (may
  lag minutes/hours). There is no "fixed" dismissal reason; forcing
  `fix_started` mislabels a completed fix.
- **A vuln that will never be fixed here** — e.g. one confined to a component
  outside the Maven reactor (such as a frozen research artifact) while the
  shipped product already uses a fixed version — dismiss with the accurate
  reason:
  ```shell
  tools/consolidate-version-bumps.sh dismiss-alert <number> not_used "<=280-char reason>"
  ```

### Step 10 — Clean up
Once the commit(s) are on the default branch, remove the worktree and branch
(the interactive default is to *offer* this; the `--yes` CI override cleans up
automatically):
```shell
git worktree remove "$WT"
git branch -d "$BR"
```

## Running from CI / non-interactively

This skill is interactive by default. To run it headlessly (e.g. a future CI
action) account for:

- **No prompts.** Replace the `AskUserQuestion` gates (Steps 8, 10) with explicit
  overrides — e.g. `CONSOLIDATE_AUTO_PUSH=1` to authorize the push and `--yes`
  to auto-clean the worktree. Never push without an explicit signal.
- **Auth + scopes.** Read-only subcommands need only repo read scope; the
  mutating ones (`close-prs`, `dismiss-alert`) need pull-request write and, for
  alerts, `security_events` — the ambient `GITHUB_TOKEN` usually lacks the
  latter, so supply a PAT / GitHub App token.
- **Branch protection.** A CI `GITHUB_TOKEN` is subject to branch protection and
  cannot push to a protected default branch; detect the rejected push and fall
  back to opening a consolidated PR (Step 8).
- **Repo / branch / remote.** The tool derives the repo (`gh repo view`) and the
  default branch; override with `EVITA_REPO` / `EVITA_DEFAULT_BRANCH`. The shell
  steps assume the canonical remote is `origin` — set that accordingly on a fork.

## Common Mistakes

| Mistake | Correct approach |
|---|---|
| Trusting Dependabot's SHA comment blindly | `verify-pins` — confirm the SHA resolves from the tag. |
| Assuming a major bump breaks things | Read the changelog first; many majors are module reorgs. |
| Compile-only testing a behavioural bump | Run the consumer tests at runtime (Step 6). |
| Folding a security-only bump into the `chore:` commit | Separate `fix(security):` commit. |
| Closing the Dependabot PRs before the commit is on `dev` | Close only after the push — else they get recreated. |
| Dismissing a genuinely-fixed alert | Let it auto-close as "fixed". |
| Editing a research spike pom to clear an alert | Never touch `documentation/research/**`; dismiss `not_used`. |
| Switching the operator's branch to do the work | Use a `git worktree`. |

## `v`-prefix note

`verify-pins` tolerates the common mismatch where a workflow comment says
`# v0.7.6` but the action's real tag is `0.7.6` (or vice versa) — it retries
with the `v` toggled. A ❓ therefore means the tag genuinely does not resolve
(renamed/deleted repo or tag), which warrants a manual look.
