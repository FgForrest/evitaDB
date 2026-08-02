---
title: Route release cuts through workflow_dispatch on the release_* branch, not workflow_run from master
date: 2026-08-02
updated: 2026-08-02 21:25
status: accepted
kind: infrastructure
issues: [1359]
prs: []
areas: [.github/workflows]
supersedes: []
superseded-by: []
relates: []
---

# Route release cuts through workflow_dispatch on the release_* branch, not workflow_run from master

`CI Master branch` no longer hands off to `CI Release branch` via a `workflow_run` trigger. It now
fast-forwards (or creates) the target `release_*` branch as before, then explicitly dispatches
`CI Release branch` on that branch with `gh workflow run`. The dispatched run resolves its own
version by scanning existing tags for that major.minor — the same logic that already handled direct
hotfix pushes to a release branch — so both a brand-new minor and the Nth patch on an
already-released one are versioned correctly by one unified path.

## Why

Investigating whether merging `dev` → `master` would produce a `2026.2.1` hotfix (`dev`'s `pom.xml`
was still `2026.2-SNAPSHOT`, unchanged since `v2026.2.0` shipped) surfaced that the answer was no —
and that the pipeline had **no working mechanism at all** for cutting a patch release from a
`dev` → `master` merge. It only ever looked like one existed because every `v2026.1.x` hotfix
(twenty of them) was actually produced by a human merging a fix PR **directly onto**
`release_2026-1`, never through `master`.

### Previous state

`ci-master.yml` fast-forwarded (or created) the release branch with a plain `git push`, authenticated
by the default `GITHUB_TOKEN` (no `persist-credentials: false` override). GitHub does not trigger
downstream `on: push` workflows from `GITHUB_TOKEN`-authored pushes — an anti-recursion guard — so
that push never fired `ci-release.yml`'s `push: branches: [release_*]` trigger. The only trigger that
*did* fire, `workflow_run` (from a successful `CI Master branch` run), computed the release version
by stripping `-SNAPSHOT` off `pom.xml` and always appending `.0` — never scanning existing tags. That
path could only ever mint a fresh `.0` cut; any second merge onto an already-released minor would
recompute the same already-published version and hit the "release already exists — refusing to
overwrite" guard, but only *after* `mvn deploy` had already re-attempted publishing that version to
Maven Central.

**Falsifying evidence** (this is what overturned the initial "it'll just make v2026.2.1" read):
- `v2026.1.5` is reachable from `origin/release_2026-1` but not from `origin/master`
  (`git branch -r --contains v2026.1.5`) — proof the 2026.1.x hotfixes never touched master.
- `gh run list --workflow="CI Release branch"` shows **zero** `push`-triggered runs against
  `release_2026-2`, ever — proof the fast-forward push never fired anything.
- `release_2026-2`, `origin/master`, and tag `v2026.2.0` were all still the same commit days after
  two further PRs (#1329, #1331) merged to master — proof nothing re-triggered after the first cut.

## Options considered

### Option A — dispatch `workflow_dispatch` from `ci-master.yml` onto the release branch (chosen)

`workflow_dispatch` (and `repository_dispatch`) are explicitly exempted from GitHub's
`GITHUB_TOKEN` anti-recursion suppression, so `gh workflow run ci-release.yml --ref <branch>` reaches
the target workflow reliably without any new credentials. It also lets `ci-release.yml` collapse to a
single version-resolution path (tag-scan by branch name) instead of the previous dual-path
(trusted-artifact vs. tag-scan), removing the class of bug this ADR fixes.

- **Pros:** no new secret to provision; unifies first-cut and hotfix under one code path; keeps the
  release branch as the sole thing being built (no artifact hand-off to trust).
- **Cons:** requires `permissions: actions: write` on the `CI Master branch` job; the repository's
  default branch must carry the `workflow_dispatch:` trigger definition for `gh workflow run` to
  find it at all — true here since `dev` (this repo's default branch) always merges into `dev` before
  reaching `master`.

### Option B — authenticate the fast-forward push with a real token (declined)

Store a PAT or GitHub App installation token as a secret and use it for `actions/checkout`/`git push`
in `ci-master.yml`, so the existing `push: branches: [release_*]` trigger fires normally.

- **Pros:** no change needed to `ci-release.yml` at all.
- **Rejected because:** requires provisioning and rotating a new credential outside version control
  — Claude Code cannot create repository secrets, only the repository owner can. `workflow_dispatch`
  achieves the same result with zero new secrets.

## Decision

**Chosen: Option A.** It fixes the trigger gap with a workflow-only change, and forces
`ci-release.yml`'s version resolution down to one path instead of two — the two-path version was
exactly what made the `workflow_run` side silently wrong (it never scanned tags) while the
`push`-triggered side was silently unreachable from `master`.

## Key technical details

- `.github/workflows/ci-master.yml` — `Resolve new release version` still computes `vX.Y.0` from
  `pom.xml`, but purely to derive the release branch name (`Determine release branch name`); it is no
  longer read anywhere as the actual version to publish. `Dispatch release build` is the new final
  step.
- `.github/workflows/ci-release.yml` — `on:` is now `workflow_dispatch` + the pre-existing `push:
  branches: [release_*]`; the job-level `if` is just `startsWith(github.ref, 'refs/heads/release_')`.
  `Resolve new release version` always tag-scans; it now also emits `is_major` (true only when the
  resolved patch is `0`) and `is_latest_line` (true when this major.minor is the highest of any
  published release).
- `IS_MAJOR` (gates whether `list-issues.sh` pulls from a GitHub milestone) now reads
  `steps.release_version.outputs.is_major` instead of the removed `workflow_run` check.
- `MAKE_LATEST` now reads `steps.release_version.outputs.is_latest_line` instead of
  `github.ref_name == 'master'` — see below, this is a second, related bug fix riding along.
- `docker-latest.yml`'s `workflow_run: workflows: ['CI Release branch']` trigger is unaffected: it
  subscribes to that workflow's completion regardless of what triggered it, so it still fires whether
  `ci-release.yml` ran via `push` or `workflow_dispatch`.
- **Known limitation, kept deliberately:** `ci-master.yml`'s `push` trigger still carries a `paths:`
  filter (`evita*/**/*.java`, `evita*/**/pom.xml`, …). A `dev` → `master` merge touching only
  `documentation/**` or `tools/**` will not trigger `CI Master branch` at all, so nothing dispatches
  and no release is cut. This was already true before this change; it now matters more because the
  dispatch path is the *only* route to a release. Left as-is — a docs-only or tooling-only merge
  genuinely doesn't warrant a release — but recorded here so it reads as a decision, not an oversight.
- `tools/list-commits.sh` was silently dropping `perf:`-prefixed conventional commits from patch-mode
  release notes — they matched its type regex but the categorisation `case` statement had no branch
  for them (major-mode releases never noticed, since `list-issues.sh` catches performance work via
  the `performance` issue label instead). Found while curating this hotfix's own release notes by
  hand; fixed alongside this change by routing `perf` into the same bucket as `feat`.

### The `MAKE_LATEST` bug this also fixes

`MAKE_LATEST` used to be `github.ref_name == 'master' && 'true' || 'legacy'`. That was only ever true
via the (broken) `workflow_run` path, whose `github.ref_name` mirrors the run that triggered it
(`push` to `master`). Once release cuts run against a `release_*` ref instead, `github.ref_name` is
never literally `master`, so every future release — including a hotfix on the *current* line, e.g.
`v2026.2.1` — would have been marked `legacy` and stopped showing as the GitHub-visible latest
release. Fixed by comparing this release's major.minor against the highest major.minor among all
published `v*` tags instead of trusting the ref name.

## Verification

- `documentation/adr/2026-08-02-ci-release-pipeline-patch-versioning-fix.md` fixture scripts (not
  committed — ad hoc bash extracted from the workflow's own tag-scan logic) exercised: a hotfix onto
  an already-`.0`-tagged minor → correct `N+1` patch, `is_major=false`; a brand-new minor with no
  prior tags → `.0`, `is_major=true`; a second hotfix on top of the first → `N+2`; the pre-existing
  direct-push-to-`release_2026-1` path → unchanged `v2026.1.21`; a draft-but-untagged patch left by a
  failed prior run → correctly skipped over.
- `is_latest_line` fixture-tested against the repo's real tag set: `release_2026-2` (current line) →
  `true`; `release_2026-1` (superseded) → `false`; a hypothetical new `release_2026-3` → `true`; an
  old `release_2025-3` → `false`.
- Both workflow YAML files parse (`yaml.safe_load`); `actionlint` was not available locally to
  validate GitHub Actions expression semantics beyond plain YAML syntax.
- Not verified end-to-end against live GitHub Actions (would require pushing and observing a real
  dispatch) — the first real release cut after this merges is the actual integration test.

## Consequences & open follow-ups

- The first `dev` → `master` merge after this lands is the first real exercise of the new path;
  watch its `CI Master branch` and `CI Release branch` runs directly rather than assuming success.

## Timeline

- **2026-08-02** — investigated why `dev` → `master` wouldn't produce `2026.2.1`; found the trigger
  gap; implemented and verified the `workflow_dispatch` fix.
