---
name: roaring-bitmap-sync
description: Replay upstream RoaringBitmap changes onto the vendored evita_roaring_bitmap module. Walks upstream/master since the recorded base, triages each commit against the vendored subset, incorporates relevant fixes, and updates the sync ledger. Invoke when checking for or applying upstream RoaringBitmap updates.
allowed-tools: Read, Edit, Write, Grep, Glob, Bash(git *), Bash(grep *), Bash(rg *), Bash(batcat *), Bash(cd *), Bash(ls *), Bash(mvn *), AskUserQuestion
---

# RoaringBitmap Upstream Sync

`evita_roaring_bitmap/` vendors a **subset** of [RoaringBitmap](https://github.com/RoaringBitmap/RoaringBitmap)
(Apache-2.0), reshaped into one persistent bitmap per data type. This skill replays upstream
changes onto that vendored copy: identify the upstream commit our sources correspond to, walk every
upstream commit since, **understand** each change, and **incorporate** the ones that touch code we kept.

The authoritative state lives in **`evita_roaring_bitmap/UPSTREAM_SYNC.md`** — read it first, update it last.

## Key facts (do not re-derive from scratch)

- **Vendored module**: `evita_roaring_bitmap/` (standalone Maven module, NOT in the root reactor — build from its own dir).
- **Upstream working clone**: `documentation/RoaringBitmap/` (untracked in the evita repo). It has two remotes:
  `origin` = fork `novoj/RoaringBitmap`, `upstream` = `RoaringBitmap/RoaringBitmap`. Upstream default branch is `master`.
  If this clone is absent, clone `https://github.com/RoaringBitmap/RoaringBitmap.git` somewhere and add it as `upstream`.
- **Indentation**: the vendored sources keep upstream's **2-space** indent (deliberate exception to evita's tab rule) for re-sync friendliness. Preserve it when porting.
- **COW fold**: `PersistentRoaringBitmap` folds in `CopyOnWriteRoaringBitmapV2` — it carries a parallel
  `boolean[] shared` array alongside `highLowContainer`. **Any ported code that populates `highLowContainer`
  must keep `shared` sized in lockstep** (call `ensureSharedCapacity(highLowContainer.size())` after growth,
  or `new boolean[...]`). This is the single most common porting landmine — see "Porting caveats".

## Procedure

### 1. Read the ledger and establish coordinates

```bash
batcat documentation/RoaringBitmap/../../evita_roaring_bitmap/UPSTREAM_SYNC.md   # or just Read it
```

Note the **base commit** (where our sources forked) and **reviewed-through** (last commit triaged).

### 2. Fetch upstream and find the new range

```bash
cd documentation/RoaringBitmap
git fetch upstream --tags
git rev-parse upstream/master                      # current tip
git log --reverse --oneline <reviewed-through>..upstream/master
```

If the range is empty, we are current — report that and stop. Otherwise that list is the work queue.
(Sanity-check the base: `git merge-base origin/master upstream/master` should equal the recorded base commit.)

### 3. Triage each commit (files changed → disposition)

```bash
git log --reverse --stat --format='%n>>> %h %s' <reviewed-through>..upstream/master
```

For each commit, classify by the files it touches, using the **exclusions** and **class mapping** in
`UPSTREAM_SYNC.md`:

- **Skip immediately** (record as N/A) if it only touches dropped subsets: `buffer/*`, `insights/*`,
  `FrozenRoaringBitmap`, `FastRankRoaringBitmap`, `longlong/Roaring64NavigableMap`, or build/docs files
  (`gradle.properties`, `build.gradle.kts`, `README.md`, `AGENTS.md`, `jmh/`, `fuzz-tests/`, `examples/`, `bsi/`).
- **Investigate** if it touches a class we kept. The two that matter most:
  - `org.roaringbitmap.RoaringBitmap` → our `PersistentRoaringBitmap`
  - `org.roaringbitmap.longlong.Roaring64Bitmap` → our `PersistentLongRoaringBitmap` (**ART-based**)
  - any other kept class → same name, package rename only.

⚠️ **`Roaring64NavigableMap` is NOT our 64-bit class.** Our `PersistentLongRoaringBitmap` derives from
the ART-based `Roaring64Bitmap`. A fix to NavigableMap's cumulative-high cache (`minHigh`,
`firstHighNotValid`, `ensureCumulatives`, `ensureOne`, `sortedHighs`) does **not** apply — verify with a
grep that the symbol genuinely doesn't exist in our sources before declaring N/A, but expect N/A.

### 4. For each investigate-worthy commit: understand, then check applicability

```bash
git show <hash> -- roaringbitmap/src/main/java/org/roaringbitmap/<File>.java
git log -1 --format='%B' <hash>
```

Read the full diff and the message. Then confirm whether the changed code path **exists in our vendored
copy** (it may target an overload/constructor/field we dropped, e.g. anything taking an
`ImmutableRoaringBitmap`). Map the upstream line to ours:

```bash
grep -n "<method-or-symbol>" evita_roaring_bitmap/src/main/java/io/evitadb/roaringbitmap/<MappedFile>.java
```

Decide:
- **Already satisfied** — our code already does the equivalent (e.g. already pre-sizes). Record no-op.
- **Targets dropped code** — the path doesn't exist in our subset. Record N/A.
- **Applicable** — port it (step 5).

### 5. Incorporate applicable changes

Apply the upstream hunk to the mapped file by hand (don't `git cherry-pick` — packages and class names
differ). Preserve 2-space indent. Rename `org.roaringbitmap` → `io.evitadb.roaringbitmap`,
`RoaringBitmap` → `PersistentRoaringBitmap`, `Roaring64Bitmap` → `PersistentLongRoaringBitmap`.

⚠️ **Respect the JPMS encapsulation reshaping** documented in `UPSTREAM_SYNC.md` (its "JPMS
encapsulation reshaping" section): the 64-bit bitmap + its API interfaces live in the **root**
package (not `longlong`); `longlong`/`art` are **not exported**; `ArraysShim` is gone (use
`java.util.Arrays`); and several core classes were demoted `public` → package-private. If an upstream
edit re-introduces `public` on a demoted class or re-adds `ArraysShim`, re-apply the evita change.

If upstream shipped a **test** for the fix, port it too (into `src/test/java/io/evitadb/roaringbitmap/`)
with the same renames, unless it targets a dropped type.

#### Porting caveats

- **`shared[]` invariant (the recurring landmine).** Any builder that grows `highLowContainer` via
  RoaringArray-level `append`/`insertNewKeyValueAt` must keep `shared` sized to match. Static/all-owned
  builders that leave `shared` at default capacity have caused AIOOBE four times. After any growth, ensure
  `shared.length >= highLowContainer.size` (via `ensureSharedCapacity`) before any in-place op reads
  `shared[i]` by raw index. Treating an index ≥ `shared.length` as "owned/false" is correct because only the
  three sharing paths (`newAllSharedResult` / `borrowAndInsert` / `appendTailWithSharing`) ever create shared
  containers and all of them size `shared[]`.
- **Defensive design** (evita rule): never silently no-op an unexpected branch; throw
  `GenericEvitaInternalError` on truly-unreachable paths.

### 6. Update the ledger and NOTICE

In `UPSTREAM_SYNC.md`:
- Append a new `### Review N — <old-tip> → <new-tip>` section with a disposition row per commit.
- Bump **Reviewed through** to the new upstream tip (and effective version coverage).
- If sources were actually changed, also update **Base commit** only if you re-based onto a new upstream
  point; normally the base stays put and only "reviewed through" advances.

In `NOTICE`: update the "reviewed through commit … (effective coverage vX.Y.Z)" line.

### 7. Verify and commit

```bash
cd evita_roaring_bitmap && mvn -o test         # standalone module; not in root reactor
```

Module suite must stay green (17,8xx+ tests). Then — **only with Johnny's go-ahead** (the standing
no-git-without-permission rule applies) — commit on the task branch:

```
docs: sync RoaringBitmap vendoring through <short-hash> (review N)

<one line per incorporated change, or "no functional changes; subset already current">

Ref: #1252
```

## Output

Report a short table: each upstream commit since the previous reviewed-through, its kind
(code/build/docs), what it touches, and the disposition (incorporated / no-op / N/A-dropped). State the
net result and the new reviewed-through commit. If anything was genuinely ambiguous (a fix that *might*
apply to a kept class but is non-obvious), surface it to Johnny rather than guessing.
