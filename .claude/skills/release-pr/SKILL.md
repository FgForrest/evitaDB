---
name: release-pr
description: Prepare or update a release PR from dev to master with auto-generated release notes
allowed-tools: Read, Edit, Grep, WebFetch, AskUserQuestion, Bash(git *), Bash(grep *), Bash(./tools/verify-pgp-keys.sh *), Bash(./tools/list-issues.sh *), Bash(./tools/list-commits.sh *), Bash(gh pr list --repo FgForrest/evitaDB *), Bash(gh pr create --repo FgForrest/evitaDB *), Bash(gh pr edit *), Bash(gh api --method POST /repos/FgForrest/evitaDB/pulls/*)
---

# Release PR

## Overview

Creates or updates a GitHub pull request from `dev` (main branch) to `master` (release branch) with auto-generated release notes. The release notes are assembled from GitHub milestone issues and git commit history, following the established evitaDB release notes format.

## When to Use

- You are ready to cut a release and need a PR from `dev` to `master`
- You want to refresh the release notes on an existing release PR
- Invoked as `/release-pr`

## Workflow

Execute the following steps **in order**. Stop and report to the user if any step fails.

### Preliminary Phase: PGP Dependency Signature Verification

Verify that all dependency signatures are valid before proceeding with the release. This catches PGP key rotations that would break the CI/CD `PGP Keys Check` step.

#### P1: Run PGP verification

```shell
./tools/verify-pgp-keys.sh --check
```

If it exits 0 (all signatures valid), skip to Step 1.

If it exits non-zero, the script outputs a structured report for each failing group with:
- New signing fingerprint
- Cross-reference result (same key signs other group artifacts)
- Public keyserver lookup result (which server, UID)
- Old key expiry status

#### P2: Present findings to the user

For each group in the script output, present a summary table:

| Check | Result |
|---|---|
| New key fingerprint | `0x...` (from script output) |
| Cross-ref: same key signs other group artifacts | Yes/No |
| Key found on public keyserver | Yes/No (which server) |
| Key UID | From script output |
| Old key expired | Yes (date) / No |

#### P3: Apply validated keys

**For `[PASS]` entries** (cross-reference succeeded AND/OR key exists on a keyserver):
- Ask the user for confirmation using AskUserQuestion before applying changes.
- If confirmed, run:
  ```shell
  ./tools/verify-pgp-keys.sh --fix
  ```
  This updates `pgp-keys-map.list` with the validated fingerprints.
- Commit the change: `git add pgp-keys-map.list && git commit -m "chore: update PGP keys for <groupIds> after key rotation"`
- Proceed to P4 to re-verify.

**For `[FAIL]` entries** (cross-reference failed AND key not on any keyserver):
- **Stop and warn the user.** Do NOT proceed with the release. Report exactly which checks failed and advise manual investigation. This could indicate a supply-chain compromise.

If the report contains a mix of PASS and FAIL entries, apply the PASS fixes first, then stop and report the FAIL entries. Do not proceed to Step 1 until all entries are resolved.

#### P4: Re-run verification

After applying fixes, re-run the check:

```shell
./tools/verify-pgp-keys.sh --check
```

If it passes, proceed to Step 1. If it still fails, repeat from P2 for the remaining failures.

Note: Any PGP key update commits from the Preliminary Phase will be part of the working tree when Step 2 checks for a clean state. The user must push those commits before proceeding.

---

### Step 1: Validate branch

Run `git branch --show-current` and verify the result is `dev`. If not, stop and tell the user they must be on the `dev` branch.

### Step 2: Validate working tree is clean and pushed

1. Run `git status --porcelain` — if output is non-empty, stop and tell the user to commit or stash changes.
2. Run `git fetch origin dev` then compare `git rev-parse dev` vs `git rev-parse origin/dev` — if they differ, stop and tell the user to push their commits first.

### Step 3: Extract release version

1. Read the root `pom.xml` and extract the version from the first `<version>` tag.
2. Strip the `-SNAPSHOT` suffix if present (e.g. `2026.1-SNAPSHOT` becomes `2026.1`).
3. Format the PR title as `Release {version}` (e.g. `Release 2026.1`).
4. **Ask the user to confirm** the version using AskUserQuestion before proceeding.

### Step 4: Determine base version for dependency comparison

Run the following to find the latest release tag on `master`:

```shell
git fetch origin master
git describe --tags --abbrev=0 origin/master
```

This returns a tag like `v2025.8.15`. Strip the leading `v` to get the base version (e.g. `2025.8.15`). This is passed to `list-issues.sh` as the second argument for dependency comparison.

### Step 5: Generate release notes

Run both scripts and capture their output:

```shell
./tools/list-issues.sh "{version}" "{base_version}"
./tools/list-commits.sh "{base_version}"
```

Where `{version}` is the milestone title from Step 3 (e.g. `2026.1`) and `{base_version}` is from Step 4 (e.g. `2025.8.15`).

Then **aggregate** the two outputs into a single release notes body following these rules:

1. **Issues from `list-issues.sh` are authoritative** — they have proper issue numbers and milestone categorization. Always include all of them.
2. **Commits from `list-commits.sh` supplement** — include commit entries that represent meaningful user-facing features or bug fixes not already covered by an issue entry. Match by description similarity to avoid duplicates.
3. **Filter out non-user-facing items** from the commits list:
   - CI/CD fixes (GitHub Actions, Docker workflows)
   - Internal code quality refactors (toString fixes, code style)
   - Compilation fixes
   - Test infrastructure changes
4. **Keep these sections** in order:
   - `### ☢️ Breaking changes`
   - `### 🚀 Features`
   - `### 🐛 Bug Fixes`
   - `### ⛓ Dependencies upgrades`
5. **Omit** documentation (`📝`) and test (`🧪`) sections from the final release notes — they are internal-facing.
6. Omit empty sections entirely.
7. The final body must start with `## What's Changed`.

### Step 6: Create or update the PR

1. Check if a PR from `dev` to `master` already exists:
   ```shell
   gh pr list --repo FgForrest/evitaDB --head dev --base master --state open --json number,title --jq '.[0]'
   ```
2. **If no PR exists**: create one:
   ```shell
   gh pr create --repo FgForrest/evitaDB --head dev --base master \
     --title "Release {version}" \
     --body "<release notes body>"
   ```
   Then request Copilot review:
   ```shell
   gh api --method POST /repos/FgForrest/evitaDB/pulls/{PR_NUMBER}/requested_reviewers \
     -f 'reviewers[]=copilot-pull-request-reviewer[bot]'
   ```
3. **If PR exists**: update its title and body:
   ```shell
   gh pr edit {PR_NUMBER} --repo FgForrest/evitaDB \
     --title "Release {version}" \
     --body "<release notes body>"
   ```

### Step 7: Report result

Print the PR URL and a summary of what was done (created vs updated).

## Important Notes

- Always use a HEREDOC to pass the PR body to `gh pr create` or `gh pr edit` to preserve markdown formatting.
- The `list-issues.sh` script requires the `gh` CLI to be authenticated.
- If `list-issues.sh` reports no milestone found, tell the user to verify the milestone title exists on GitHub.
- If the base version tag doesn't exist, tell the user and ask them to provide the previous release version manually.
