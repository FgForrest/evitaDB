# Assignment — Deterministic change detection for granular index page storage

**Issue:** #760 Part B (granular FilterIndex storage — §3 buckets + §7 range).
**Status:** IMPLEMENTED (per-leaf dirty flag). Verified green. NOT committed.
**Date:** 2026-06-24.

---

## 0. OUTCOME — what was actually built (read this first)

The originally-agreed design below (a per-leaf STM **instance id** as the change token) was implemented,
then **disproved by the existing tests** and replaced. Two committed behavioral tests
(`{Range,Inverted}IndexTest.shouldSuppressUnchangedLeavesAfterPublish`) failed: after a real mutation
`collectChangedPages()` reported **0** changed leaves — the exact silent-data-loss outcome we set out to kill.

**Root cause:** node `id` only changes on copy-on-write at the commit-merge, but (a) `collectChangedPages`
runs at flush, *before* the merge, so a content-changed leaf still carries its old committed id, and (b) the
warm-up bulk path mutates leaves in place (no COW at all). Node identity at flush therefore cannot see a
change. (Content-hash worked only because it re-read the leaf's transaction-aware *content*.)

**Replacement (Johnny's choice, 2026-06-24): a per-leaf `dirty` flag.** Added `boolean dirty` to both leaf
node types (`TransactionalBucketBPlusTree` / `TransactionalLongBPlusTree`), routed through the leaf's own
transactional-layer swap pattern (the same mechanism `peek`/`keys` use — the leaf's diff layer *is* a cloned
leaf instance, so a plain boolean is fully transaction-isolated; a `TransactionalBoolean` would be the wrong
tool — it nests a second STM mechanism + an object per leaf). Every leaf mutation site sets it; the emitter
re-emits a leaf iff it is brand new (`UNASSIGNED_PAGE_SEQ`) or dirty, then clears it. `fromPersistedPages`
clears the flags after reconstruction (the rebuilt leaves equal what is on disk). The `PageStreamRegistry`
token map (`pageSeq -> token`) is now obsolete and was simplified to a **live-pageSeq `Set<Integer>`**
(allocator + high-water + live set, for freed-page reclaim only).

**Range-tree subtlety (fixed):** the range tree stores *value objects* (`TransactionalRangePoint`) whose
record-set bitmaps are mutated **in place** via `upsert`/`search` — bypassing the leaf's marked methods. Fixed
by marking dirty in `TransactionalLongBPlusTree.upsert` (update branch) and adding
`TransactionalLongBPlusTree.markDirty(long key)`, which `RangeIndex.removeFromPoint` calls after an in-place
point edit. (The bucket tree stores records in the leaf columns, so it has no such gap.)

**Verification:** 3377 index tests + 357 tree/registry + 180 loader/serializer/paged-e2e all green, incl. STM
soaks and the commit/reload cliff-edge soak. New regression
`RangeIndexTest.shouldReemitLeafWhenRecordRemovedFromSurvivingPoint` pins the in-place-value-mutation case.
Full rebuild + `EvitaBackwardCompatibilityTest` is the remaining final gate.

The sections below are the ORIGINAL (superseded) identity design, kept for the rationale trail.

---

---

## 1. Intent (what we are changing)

Replace the **probabilistic content-hash** change detector used by the granular page storage with a
**deterministic STM instance-identity** detector. The unit of change detection for a persisted B+ tree
leaf page becomes the leaf node's STM identity (`@Getter private final long id =
TransactionalObjectVersion.SEQUENCE.nextId()`), not a 64-bit FNV hash of its content.

Concretely: the per-stream change-detection baseline held by `PageStreamRegistry`
(`Map<Integer, Long>` keyed `pageSeq → token`) stores the **leaf node `id`** instead of
`pageContentHash(...)`. A leaf is suppressed (NOT rewritten) on a commit **iff** its current node `id`
equals the baseline `id` recorded for its `pageSeq`.

## 2. Motivation (why — the integrity argument)

The current content-hash gate has a **false-negative** failure mode: if a leaf's content genuinely
changes but the new content hashes to the same value as its prior baseline, the leaf is suppressed and
the on-disk page is left stale → **silent data loss**, surfacing only after a restart reloads the old
bytes.

- The hash is FNV-1a folding, per range point, the threshold + `getStarts().hashCode()` +
  `getEnds().hashCode()`. The bitmap contributions are only **32-bit** `hashCode()`s, so for the common
  mutation ("record-set membership changed at an existing threshold") the effective collision resistance
  is ~2^-32 per changed point, **not** the 2^-64 the envelope implies.
- The consequences are **asymmetric**: a content-hash *false negative* loses data silently (unrecoverable,
  unobservable); an identity-detector *false positive* merely rewrites an unchanged page (wasted I/O —
  recoverable, observable, self-correcting). For a storage-correctness gate, determinism beats a tiny
  probability of silent corruption. **Johnny's explicit direction (2026-06-24): elevate integrity — rare
  unnecessary rewrites are an acceptable price; the false-negative scenario must not be possible.**

## 3. Why identity is exact AND restart-safe (the key realization)

The COW tree's commit guarantees (the *"structural sharing and identity preservation on commit"* invariant,
already covered by tests):

- **Untouched leaf** → carried forward as the *same instance* → same `id`.
- **Modified leaf** → a *new instance* (`id` is `final = nextId()`) → new `id`.

A leaf is suppressed only when `baseline[pageSeq] == leaf.id`. Same id ⟹ **same object** ⟹ (committed
nodes are immutable) ⟹ identical content ⟹ the page written when that id was recorded is still correct.
There is **no input** under which a genuinely-changed leaf shares an id with its prior baseline, because a
change always mints a new id. **No false negative is possible.**

Restart safety is free and identical to today's approach: we do NOT persist the id. `fromPersistedPages`
**rebuilds the baseline from the freshly-assembled loaded leaf instances' ids** (just as it currently
rebuilds it from `pageContentHash`). After load, untouched leaves on the first commit are those very
instances (id matches → suppress); touched leaves are new instances (id differs → write). Because the
baseline is always built and compared **within one JVM run**, the per-JVM `nextId()` sequence reset across
restarts is irrelevant — we never compare a pre-restart id to a post-restart id.

## 4. Guarantees / failure modes

- **No false negatives** (the whole point): a changed leaf can never be suppressed.
- **Worst case = over-report**: a commit-fold that hands a touched leaf a fresh, content-identical instance
  causes one needless rewrite; it is **self-correcting** (next commit the id matches and it suppresses).
- **`pageSeq` is unchanged in role**: it remains the copy-stable on-disk identity (carried forward across
  COW; `id` cannot be it because `id` is deliberately fresh-per-COW). This change only swaps the
  *change-detection token*, not the *page identity*.

## 5. Invariant it rests on (must be asserted/tested)

**Committed B+ tree nodes are never mutated in place** — a change always produces a new instance. This is
the COW contract and is what the existing identity-preservation tests assert. The one mutable field on a
committed node (`pageSeq`, via `setPageSeq`) is not content and is stamp-once for split-born leaves, so it
is irrelevant to the suppression decision.

Add an explicit regression: mutate→commit, assert the touched leaf's id changed and untouched leaves' ids
did not; a no-op (clean) commit rewrites nothing; a boundary-stable reload + no-op commit rewrites nothing.

## 6. Blast radius / file-by-file plan (do RangeIndex first, prove green, then mirror to InvertedIndex)

- `TransactionalLongBPlusTree` + `TransactionalBucketBPlusTree`: add `long nodeId()` to the
  `LeafPageHandle` interface + impl (returns `leaf.getId()`).
- `RangeIndex.collectChangedPages`: replace `pageContentHash(pagePoints)` with `handle.nodeId()` in both
  the stage (`nextBaseline.put`) and the suppress comparison.
- `RangeIndex.fromPersistedPages`: build the baseline from the assembled tree's leaf ids
  (`for (handle : tree.leafPageHandles()) baseline.put(handle.getPageSeq(), handle.nodeId())`), not from
  `pageContentHash`.
- **Delete** `RangeIndex.pageContentHash` and its FNV helpers.
- Mirror all of the above in `InvertedIndex` (`collectChangedPages`, `fromPersistedPages`, delete
  `pageContentHash`).
- `PageStreamRegistry`: logic unchanged (it is agnostic to what the `long` means). Rename `baselineHash`
  → a neutral name (e.g. `baselineToken` / `baselineNodeId`) for honesty; `stage`/`publishStaged`/
  `restore`/`freedPageSeqs`/`livePageSeqs` all stay as-is.
- Tests: update any test asserting hash semantics; add the §5 identity-preservation regression for both
  trees.

This is a **net simplification** (removes all hashing) that upgrades the gate from probabilistic to exact.

## 7. Verification

1. `mvn -pl evita_engine compile` (Maven 3.9.9 — see §9).
2. Install engine, run the focused suite (the 537-test set):
   `AttributeIndexLoaderTest, FilterIndexTest, AttributeIndexTest, InvertedIndexTest, RangeIndexTest,
   TransactionalBucketBPlusTreeTest, TransactionalLongBPlusTreeTest, FilterIndexStoragePartSerializerTest,
   FilterIndexLeafPagePartSerializerTest, RangeIndexLeafPagePartSerializerTest, LeafStreamKeySerializerTest,
   PageStreamRegistryTest, FilterIndexPagedPersistenceTest`.
3. The paged persistence e2e (bucket-PAGED + range-PAGED + shrink→reopen) must stay green.
4. Re-run `EvitaBackwardCompatibilityTest` (longRunning) across 2025.1 / 2025.3 / 2025.6 / 2026.1.

## 8. Working-tree context (uncommitted, on top of `503c851b6`)

Everything for #760 Part B §3+§7 is uncommitted (one commit unit). This session also landed three
behavior-preserving refactors (all verified green, 537-test focused suite + bwc 4/4):

- `FilterIndex.appendStorageParts` split into orchestrator + `appendBucketAxis`/`appendRangeAxis`
  (with `BucketAxis`/`RangeAxis` descriptor records).
- `AttributeIndexLoader.fetchFilter` split into `loadInvertedIndex`/`loadRangeIndex`.
- `computeFreedPageSeqs`/`livePageSeqs` deduplicated into `PageStreamRegistry`
  (`freedPageSeqs(streamId, liveSeqs)` + `livePageSeqs(streamId)` over a private `baselineSeqsExcluding`).
- Removed all ephemeral `(#760 Part B §3)` / `Option A` / `2026.2-dev` references from production + test
  comments/JavaDoc (kept in throwaway `/spike/` probes only).

The bwc test passed (4/4 datasets, 2026.1 = 148,386 WAL records replayed) confirming released-format
catalogs still load through the new serializer (legacy SINGLE → old ctor, `rangePaged=false`).

## 9. Build environment

- pom version is back to `2026.2-SNAPSHOT` (the `2026.2.760-SNAPSHOT` isolation was reverted at Johnny's
  request). A **full** `mvn clean install -DskipTests` from root makes the shared `~/.m2` consistently
  yours; do that before any test run that crosses modules.
- Build with **`/tmp/apache-maven-3.9.9/bin/mvn`** (system mvn is 3.8.7 and cannot build the branch). If
  `/tmp` was wiped: `curl -fsSL -o /tmp/m.tgz
  https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz && tar xzf
  /tmp/m.tgz -C /tmp`.
- Network egress is proxy-only; pass the proxy to forked test JVMs via
  `JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=proxy -Dhttp.proxyPort=8888 -Dhttps.proxyHost=proxy
  -Dhttps.proxyPort=8888 -Dhttp.nonProxyHosts=localhost|127.0.0.1
  -Dhttps.nonProxyHosts=localhost|127.0.0.1"` (needed by `EvitaBackwardCompatibilityTest`, which downloads
  datasets from evitadb.io).

## 10. Pre-commit checklist (standing constraints — unchanged)

- Do NOT touch/commit `PriceListAndCurrencyPriceSuperIndex.java`.
- Revert the ~774 gRPC `*/generated/*` files if any reappear (currently clean).
- No (co)author/date in commit/PR. No git stash/reset/checkout/commit/push without Johnny's permission.
- serialVersionUID / bwc reader only across RELEASED minors (2025.5 / 2026.1); intra-2026.2-dev format
  changes are changed in place with no `@Serial` bump.
