---
name: release-notes
description: Generate enriched release notes for a major or patch release of evitaDB. Runs the deterministic skeleton generator and adds short user-facing prose to all breaking changes and to the features deemed "interesting", then writes the result to release-notes.md. Invokable locally as /release-notes and headlessly from the CI release workflow.
allowed-tools: Read, Write, Bash(./tools/generate-release-notes.sh:*), Bash(./tools/list-issues.sh:*), Bash(./tools/list-commits.sh:*), Bash(git tag:*), Bash(git log:*), Bash(git describe:*), Bash(git rev-parse:*), Bash(git show:*), Bash(gh issue view:*), Bash(gh api:*)
---

# Release Notes

## Overview

Produces the final release notes body for an evitaDB release. Combines a deterministic markdown skeleton (built by `tools/generate-release-notes.sh`) with short, user-facing prose summaries that the model writes for the bullets that warrant explanation. The result is written to `release-notes.md` in the working tree so the calling workflow can `PATCH` it onto the GitHub draft release.

The deterministic skeleton already contains the four canonical sections in the correct order — the skill never fabricates new sections or new bullets. It only **rewrites existing bullets** to bold their title and append a prose continuation line where appropriate.

## When to Use

- Invoked locally as `/release-notes` to preview the notes for the next release
- Invoked headlessly from `.github/workflows/ci-release.yml` after the draft release is created
- Useful for both MAJOR releases (e.g. `v2026.1.0`) and PATCH releases (e.g. `v2026.1.3`)

## Inputs

The skill resolves its inputs in this order:

1. **Explicit arguments** passed in the prompt: `version=v2026.1.3 base=v2026.1.2 milestone=2026.1`
2. **Environment variables** (set by the CI workflow): `RELEASE_VERSION`, `BASE_VERSION`, `MILESTONE`
3. **Auto-detection**:
   - `version` — read the first `<version>` tag from the root `pom.xml`, strip `-SNAPSHOT`, prepend `v`, append `.0` if no patch component is present (so `2026.1-SNAPSHOT` becomes `v2026.1.0`)
   - `base` — let `generate-release-notes.sh` resolve it (highest `v*` tag strictly less than `version`)
   - `milestone` — strip the leading `v` and trailing `.0` from `version`; if the resulting string identifies an existing GitHub milestone, use it, otherwise leave empty

If both `RELEASE_VERSION` ends with `.0` **and** the matching `MILESTONE` exists on GitHub, treat the run as **MAJOR mode**. Otherwise treat it as **PATCH mode**.

## Workflow

Execute these steps **in order**. Stop and report to the user if any step fails. **Do not** modify any file other than `release-notes.md`. **Do not** stage, commit, push, or create branches.

### Step 1: Resolve inputs

Determine `VERSION`, `BASE`, and `MILESTONE` per the rules above. Echo them to the run output for traceability.

### Step 2: Build the deterministic skeleton

Run the orchestrator and capture stdout. Use `--milestone` only when in MAJOR mode.

```shell
./tools/generate-release-notes.sh --version "<VERSION>" --base "<BASE>" [--milestone "<MILESTONE>"]
```

If the orchestrator exits non-zero, abort the skill and report the error. The CI workflow will treat this as a soft failure and keep the skeleton body already attached to the draft release.

The skeleton always begins with `## What's Changed` and ends with a `**Full Changelog**: ...` line. Between them are zero or more of these sections, in this fixed order:

1. `### ☢️ Breaking changes`
2. `### 🚀 Features`
3. `### 🐛 Bug Fixes`
4. `### ⚡ Performance`
5. `### ⛓ Dependencies upgrades`

### Step 3: Identify enrichment candidates

Parse the skeleton and collect the list of bullets that need a prose continuation:

- **Every bullet** in `### ☢️ Breaking changes` — breaking changes always get prose, with no exceptions.
- **Every "interesting" bullet** in `### 🚀 Features`. A feature is interesting when it changes what the user can do, query, store, observe, or operate. Use this checklist:
  - ✅ adds or removes a query constraint or query operator
  - ✅ adds or removes a public API endpoint, gRPC method, REST route, GraphQL field, or evitaQL token
  - ✅ adds or changes a public Java API on a published interface
  - ✅ introduces a new storage feature (export, backup, replication, transaction handling)
  - ✅ introduces a new observability surface (metric, trace, log field, dashboard)
  - ✅ changes a default that users can perceive (limits, timeouts, sort order, response shape)
  - ❌ pure internal refactor with no user-visible effect
  - ❌ developer-experience-only improvements (build, test infra, code quality)
  - ❌ small ergonomic tweaks to internal helpers
  - ❌ minor wording or formatting changes

  When unsure, **include** the bullet — over-enrichment is cheaper than under-enrichment for the maintainer to clean up.

- **Every "noticeable" bullet** in `### ⚡ Performance`. Pure micro-optimisations should stay as plain bullets; enrich a perf bullet only when the change has a user-perceivable effect:
  - ✅ order-of-magnitude or otherwise substantial improvement on a documented benchmark or canonical query
  - ✅ removes a known cost spike or hot-path bottleneck users have reported or that the issue body quantifies
  - ✅ changes the asymptotic complexity of a common request pattern (e.g. introduces caching that turns repeated work into O(1) hits)
  - ❌ small constant-factor speedups with no quantified impact in the source issue
  - ❌ memory-allocation reductions without measured latency/throughput effect

- **Never** enrich bullets in `### 🐛 Bug Fixes` or `### ⛓ Dependencies upgrades`.

### Step 4: Look up source material for each candidate

For each candidate bullet:

1. Extract the issue number from the trailing `(#NNN)` if present.
2. If an issue number was found, fetch the issue:
   ```shell
   gh issue view <NNN> --repo FgForrest/evitaDB --json title,body,labels,url
   ```
   Read the issue body — it is the primary source of user-facing context.
3. If no issue number is present (typical for PATCH-mode commits without `Ref: #NNN`), look up the commit body instead. Use `git log --grep="<bullet description>" -1 --format=%B` to find the matching commit. The commit body is a weaker source than an issue body — keep the prose conservative.
4. If neither lookup yields meaningful prose-worthy content, **skip** the bullet (do not invent text). For breaking-change bullets that have no source material, fall back to a one-sentence factual paraphrase of the bullet itself.

### Step 5: Generate prose continuation lines

For each candidate, write a 1-2 sentence prose continuation that meets all of these rules:

1. **Voice**: user-facing. Explain what the user can now do, what changed for them, or what they need to migrate. Never describe internal implementation.
2. **Vocabulary**: never mention internal Java class names, package paths, gRPC stub names, internal method names, or `BASH_REMATCH`-style implementation jargon. Public API names (e.g. `entityFetch`, `referenceContent`, `evitaQL`, `EntityContract`) are fine when they correspond to documented API surface.
3. **Length**: maximum 2 sentences. Aim for one when possible. No bullet lists, no headings, no code fences inside the prose.
4. **Tone**: neutral and factual, matching the voice of the v2025.1.0 release notes. Avoid marketing words ("powerful", "blazing", "exciting", "robust").
5. **Completeness**: prose must stand alone — a reader should not need to click the issue link to understand what the change is.

### Step 6: Rewrite the bullets in place

For each enriched bullet, replace the original line:

```
* Added X for Y (#123)
```

with the bolded-title two-line continuation form:

```
* **Added X for Y** (#123)<br/>
  Short user-facing explanation of what this unlocks for the user.
```

Format rules — follow exactly:
- Wrap **only the title text** in `**...**`. The title is everything between the leading `* ` and the trailing ` (#NNN)` reference (or end-of-line if there is no issue ref). Do **not** bold the `(#NNN)` part.
- End the bullet line with `<br/>` so GitHub renders the prose as a continuation of the same list item.
- Indent the prose line with two spaces so GitHub keeps it inside the same list item.
- No blank line between the bullet and the prose.

Bullets that were not selected for enrichment are left **byte-for-byte unchanged** — they remain plain (no bolding, no `<br/>`, no prose continuation). The order of bullets within each section is preserved.

### Step 7: Apply light dedupe (best-effort)

Within each section, if the same change appears as both an issue-derived bullet (with `(#NNN)`) and a commit-derived bullet (without an issue number, lower in the section), drop the commit-derived duplicate. Match conservatively — only drop when the descriptions are clearly the same change written two ways. Never delete a bullet that has a unique issue number you have not seen before.

This step is **purely subtractive** and runs only on sections that contain bullets from both sources (i.e. MAJOR mode). PATCH-mode runs skip this step.

### Step 8: Write `release-notes.md`

Write the final body to `release-notes.md` in the current working directory using the Write tool. Overwrite any existing file. Do not append a trailing newline beyond what is already present in the body. Do not modify any other file.

### Step 9: Report

Print a one-paragraph summary to the run output:

- mode (MAJOR or PATCH)
- version, base, milestone (if any)
- number of bullets enriched, broken down by section
- number of bullets dropped by dedupe
- absolute path of `release-notes.md`

The CI workflow uses this summary only as human-readable log output — no machine parsing.

## Important Notes

- **Soft-failure contract**: this skill runs in CI under `continue-on-error: true`. A failure here must never block the release pipeline. If you cannot complete a step, abort with a clear error message and do not write a partial `release-notes.md`.
- **No commits, no pushes, no branches**: the skill only writes a single file. The workflow handles publishing.
- **Idempotent**: running the skill twice with the same inputs must produce byte-identical output. Do not rely on randomness, time-of-day, or any other non-deterministic input.
- **Token discipline**: limit `gh issue view` calls to candidate bullets only. Never crawl unrelated issues. Never read source code from the repository — the goal is to summarize what was already written in the issue, not to reverse-engineer it.
- **No guesswork on breaking changes**: if a breaking-change bullet has no usable source material, write a single factual sentence paraphrasing the bullet itself rather than inventing migration guidance.
- **Patch release without features**: if both the breaking and features sections are empty after Step 3, skip Steps 4–7 entirely and write the unchanged skeleton to `release-notes.md`. This is the normal happy path for a small bugfix patch.
