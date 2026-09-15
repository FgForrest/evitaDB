---
title: Push release_* branches from CI as a GitHub App on the ruleset bypass list, not as GITHUB_TOKEN
date: 2026-09-15
updated: 2026-09-15 21:55
status: accepted
kind: infrastructure
issues: [1591]
prs: [1592]
areas: [.github/workflows]
supersedes: [2026-08-02-ci-release-pipeline-patch-versioning-fix]
superseded-by: []
relates: []
---

# Push release_* branches from CI as a GitHub App on the ruleset bypass list, not as GITHUB_TOKEN

`CI Master branch` still fast-forwards (or creates) the target `release_*` branch after every code
merge to `master`, but it now does so as a dedicated org-owned GitHub App whose installation token
is minted at the start of the job, rather than as the job's default `GITHUB_TOKEN`. The App is the
only "always" bypass actor on the repository's `Protected branches` ruleset; repository admins keep
their "pull requests only" bypass, so no user token — classic or fine-grained — can push to `dev`,
`master` or `release_*` directly. Because App-authored pushes fire `on: push` workflows like a
human's, `ci-release.yml` now starts from its own push trigger and the explicit `workflow_dispatch`
hand-off from `ci-master.yml` is gone.

## Why

The first release cut after the `Protected branches` ruleset gained a "require a pull request"
rule on `release_*` (2026-09-09) failed at the fast-forward push:

```
remote: error: GH013: Repository rule violations found for refs/heads/release_2026-2.
remote: - Changes must be made through a pull request.
 ! [remote rejected]     release_2026-2 -> release_2026-2 (push declined due to repository rule violations)
```

Two GitHub constraints made this non-obvious:

- **Rulesets evaluate the user, not the token.** A fine-grained PAT and a classic PAT of the same
  admin account get the same bypass treatment, so there is no ruleset that lets one push and
  blocks the other. The only way to keep the agent's fine-grained token off protected branches is
  to keep *every* user token off them — admins at "pull requests only" — and give the workflow an
  identity of its own.
- **`github-actions[bot]` can never be a bypass actor.** GitHub deliberately refuses to list the
  identity behind `GITHUB_TOKEN`, because any workflow on any branch could then bypass every rule.
  Bypass actors are repository roles, teams, GitHub Apps, deploy keys and Dependabot.

### Previous state

`ci-master.yml` pushed the release branch with `GITHUB_TOKEN` and, because such pushes never
trigger `on: push` workflows, dispatched `ci-release.yml` with `gh workflow run` afterwards. That
design (`2026-08-02-ci-release-pipeline-patch-versioning-fix`, Option A) was chosen over a real
credential explicitly because nobody could provision a repository secret at the time. It kept
working until the ruleset started requiring pull requests on `release_*`; the ruleset itself was
created on 2026-08-01, one day before that record, but without the pull-request rule.

## Options considered

### Option A — org-owned GitHub App on the bypass list, token minted in the job (chosen)

Register an App under the organization with repository permissions Contents and Workflows
(read and write), install it on this repository only, add it to the ruleset's bypass list in
"always" mode, and mint an installation token in `ci-master.yml` with
`actions/create-github-app-token` before checkout.

- **Pros:** a named bot identity in the audit log; short-lived tokens (one hour, revoked when the
  job ends) scoped to exactly two permissions; App-authored pushes trigger `on: push`, so the
  dispatch step and its `actions: write` permission disappear and the pipeline has one trigger
  path instead of two; admins stay at "pull requests only".
- **Cons:** an App registration and a private key live outside version control and must be
  rotated by hand; the fix only takes effect once the workflow file is on `master`.

### Option B — deploy key on the bypass list (declined)

A write deploy key can be a bypass actor (`DeployKey`, always-mode only) and the workflow would
push over SSH with it.

- **Pros:** needs no organization owner; one key per repository.
- **Rejected because:** the audit trail names only "deploy key"; the credential is long-lived,
  unscoped full write access; and pushing needs SSH-agent plumbing in the job. Revisit only if the
  organization can no longer host a GitHub App.

### Option C — admins bypass "always" and push with an admin PAT (declined)

- **Pros:** no new identity; a one-line ruleset change.
- **Rejected because:** rulesets evaluate the account's role, so the same relaxation lets the
  agent's fine-grained PAT push straight to `dev`, `master` and `release_*` — precisely what the
  ruleset exists to prevent. A dedicated machine user would avoid that but is a licensed seat and
  a PAT to rotate, which is what Option A does better.

### Option D — merge `master` into `release_*` through a pull request (declined)

- **Pros:** no bypass actor at all; the release branch changes only through reviewed merges.
- **Rejected because:** GitHub never fast-forwards a pull-request merge — even "rebase and merge"
  rewrites the commits with new SHAs — so `release_*` would diverge from `master`, the next
  `git merge --ff-only origin/master` would fail, and release tags would stop pointing at commits
  reachable from `master`. The whole release model rests on the two branches sharing history.

## Decision

**Chosen: Option A.** It is the only option that keeps the fast-forward model, keeps every user
token off the protected branches, and gives the pipeline a single trigger path. The trade — one
App registration and one secret to rotate — is exactly the cost the 2026-08-02 record declined to
pay, and it became payable once the organization owner provisioned the App. Option A would lose
its reason to exist if GitHub ever allowed `github-actions[bot]` onto bypass lists (a long-standing
community request); until then the App stays.

## Key technical details

- `.github/workflows/ci-master.yml` — `Mint release bot token` runs **before** `actions/checkout`
  and its token is passed to checkout's `token:` input. This order is load-bearing: checkout
  persists whatever credential it is given for every later git command, so a token minted after
  checkout is never the one `git push` uses. The job's permission is `contents: read`; the App
  token carries the write. The `Dispatch release build` step and `actions: write` are gone.
- The App token is minted with `permission-workflows: write` because `.github/**` is in the
  master workflow's `paths` filter — a fast-forward can carry workflow-file changes, which GitHub
  refuses from a token without that permission.
- `.github/workflows/ci-release.yml` — `on: push` for `release_*` is now the production trigger;
  `workflow_dispatch` remains for manual re-runs. Its `paths` filter is a **superset** of
  `ci-master.yml`'s and must stay one: anything that makes `CI Master branch` move the release
  branch must also make `CI Release branch` build it, or the branch moves and no release follows.
- **Never reintroduce a dispatch of `ci-release.yml` from `ci-master.yml` alongside the push
  trigger.** App-authored pushes are not suppressed, so both would fire and the release would build
  — and attempt to publish — twice.
- The explicit `Dispatch Docker publish` step in `ci-release.yml` stays. Its original reason (a
  `GITHUB_TOKEN`-authored run's completion never cascades into `workflow_run`) no longer applies to
  App-triggered runs, but the dispatch also passes the run id that lets `docker-latest.yml` pull
  artifacts from a draft release, which a `workflow_run` listener would have to rediscover.
- Ruleset state this depends on: `Protected branches` (id 20180081) targets `refs/heads/release_*`,
  `refs/heads/master` and the default branch; rules `deletion`, `non_fast_forward`,
  `pull_request`; bypass actors `RepositoryRole` admin in `pull_request` mode and `Integration`
  App ID 4958064 in `always` mode. The App is installed on this repository only, with Contents and
  Workflows read/write. Its ID is the repository variable `RELEASE_APP_ID`; its private key is the
  repository secret `RELEASE_APP_PRIVATE_KEY`.
- Not verified: whether the `pull_request` rule also rejects a human's *first* push that creates
  a `release_*` branch. The workflow does not care — the App bypasses it either way — and no human
  creates release branches by hand.

## Verification

- All three workflow files parse as YAML (SnakeYAML 2.3, `yaml.load` on each file); `actionlint`
  is still not available locally, so GitHub Actions expression semantics were checked by review
  only.
- Ruleset and App wiring checked through the REST API before the change: `bypass_actors` held only
  the admin role in `pull_request` mode and `current_user_can_bypass` reported
  `pull_requests_only` for a fine-grained admin PAT — confirming that the ruleset already blocked
  direct user pushes and that the only broken piece was the workflow's identity.
- Live verification is the first `dev` → `master` merge carrying this change: `CI Master branch`
  must fast-forward `release_2026-2` as the App, `CI Release branch` must start from the push
  trigger exactly once, and `DockerHub-deploy` must follow. Nothing from the failed run needs
  cleanup — it died at the push, before any Maven Central deploy.

## Consequences & open follow-ups

- The private key rotates by hand: generate a new key on the App's settings page, replace
  `RELEASE_APP_PRIVATE_KEY`, delete the old key. Nothing in the repository can automate this.
- If the ruleset is ever recreated or the App uninstalled, the release push fails again with
  `GH013` and the message above; the fix is the bypass entry, not the workflow.
- Widening `ci-release.yml`'s `paths` filter to `.github/**` and `docker/**` means a pipeline-only
  or Docker-only merge to `master` now cuts a release, matching what `ci-master.yml` already did
  by fast-forwarding the branch for those changes. That was the intent of the master filter all
  along; the release filter had merely fallen behind it.
- The open item from the 2026-08-02 record — watch `DockerHub-deploy`'s artifact download on a
  fresh dispatch and add a retry if it ever fails — is unaffected and still open.

## Related work

- `2026-08-02-ci-release-pipeline-patch-versioning-fix` — superseded **in part**: its Option A
  (`workflow_dispatch` hand-off from `ci-master.yml`) is replaced by App-authored pushes. Its
  tag-scanning version resolution, the `MAKE_LATEST` fix and the explicit DockerHub-deploy
  dispatch all still stand and are relied on here.

## Timeline

- **2026-09-15** — release cut rejected by the ruleset; organization owner registered and
  installed the App; workflows changed; issue #1591 opened; PR #1592 opened.
