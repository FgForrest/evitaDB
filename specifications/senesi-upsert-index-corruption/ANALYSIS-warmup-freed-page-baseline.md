# Stale leaf-page twin — root cause: the page-live baseline is never published on the WARM_UP flush

*Preliminary analysis, 2026-07-17, branch `dev` @ `2fac0b066`. Incident: senesi reindexed (WARM_UP), live process
healthy, transactions processed, **restart refuses to load**:*

```
Corrupted persisted inverted index for type `java.time.OffsetDateTime`: leaf-page sequence 0 overlaps its
successor leaf-page sequence 1 — its last key (2026-07-16T18:20:10.321455914Z) does not sort before the first
key (2026-07-16T18:20:08.787024949Z) of the next leaf page.
```

This is the **twin producer left open** by `AUDIT-bplustree-family-block-size-confusion.md` §4 (D1–D4 were a
different, now-fixed bug: they crash, they never mis-order). It is a **write-path defect**, diagnosed by code
reading and **confirmed by a failing unit test** (§6) — no WAL analysis needed. **The twin is not a B+ tree bug at
all: the in-memory tree is correct. What is corrupt is the persisted *page list* + the *freed-page reclaim*, both
of which hang off one baseline that the warm-up flush never advances.**

Scope modelled below: a **fresh catalog** reindexed in WARM_UP (the senesi case) — the published live set starts
empty and stays empty. A catalog *loaded from disk* and then mutated in warm-up is broken by the same root cause
but with different arithmetic: `restore()` seeds `live` non-empty, so the baseline is stale-but-populated and
`freed` can both miss real drops and name already-removed pages.

## 1. The defect in one paragraph

`PageStreamRegistry` keeps, per page stream, a **published live-page set** = "which leaf pages this stream has on
disk". A flush **stages** the next live set (`stage`), and the set becomes live only via **`publishStaged()`**.
`publishStaged()` is called from **exactly one place in each of the seven paged indexes: their
`createCopyWithMergedTransactionalMemory`** — i.e. the *transactional commit-merge*. **A WARM_UP flush never
reaches a commit-merge**, so on a freshly reindexed catalog the published live set stays **empty for the whole
warm-up**, while every flush re-stages and disk moves on. Everything derived from that baseline is therefore
dead on the warm-up path:

```java
// PageStreamRegistry.collectChangedPages
final int[] freedPageSequences = freedPageSequences(streamId, nextLive); // = livePages(∅) − nextLive ≡ ∅ !!
stage(streamId, nextLive);
final boolean pageListChanged = anyFreshLeaf || freedPageSequences.length > 0; // ⇒ false for a merge-only flush
```

## 2. Why that yields *exactly* this error

A **leaf merge** is the one structural event that removes a page without creating one:

- `AbstractTransactionalBPlusTree.consolidate` (:619 `mergeWithLeft`, :648 `mergeWithRight`) mutates the
  **survivor in place** — same object, so it **keeps its `pageSequence`** (`pageSequence` is structural, not
  transactional) — and detaches the donor from the parent. **No new leaf object is born.**
- Both merge methods set `dirty = true` on the survivor (`TransactionalBucketBPlusTree` :3921, :3963).

So a warm-up flush that merged a leaf does this:

| step | correct (transactional) | actual (WARM_UP) |
|---|---|---|
| survivor's page rewritten (it is dirty) | ✅ yes, with the absorbed keys | ✅ **yes, with the absorbed keys** |
| `anyFreshLeaf` | false (no allocation) | false |
| `freedPageSequences` | `{donor}` → donor's record **removed** | **∅** → donor's record **stays on disk** |
| `pageListChanged` | **true** (freed ≠ ∅) → root re-emitted **without** the donor | **false** → **root part skipped** |
| persisted root's ordered page list | correct | **still lists the donor's page** |

The root skip is explicit — `HistogramIndex.appendHistogramStorageParts` :394-399 (and the twin gate in
`FilterIndex` :1190 / :1235):

```java
final boolean bucketRootStable = bucketPaged && !bucketListChanged;
if (bucketRootStable && rangeRootStable) { return; }   // ← root (and its page list) never re-emitted
```

**On reload** the loader replays the stale root list and finds: the survivor's page (fresh, now containing the
absorbed keys) followed by the donor's page (stale, never removed, never delisted). The survivor's range now
**covers** the donor's first key ⇒ the cross-page overlap assert fires.

### Precondition: ≥ 2 real warm-up flushes of the same index

The **first** flush of an index is always sound: every leaf is fresh (`UNASSIGNED`), so all pages are allocated
and `freed = livePages(∅) − nextLive = ∅` — correctly, since nothing was on disk yet. Splits/merges before it
happen purely in memory, before any page exists. **A single meaningful flush therefore cannot corrupt or leak**;
the defect needs the stale page to have been written by an *earlier* flush.

Warm-up flushes are **catalog-wide** (`Catalog.flush()` maps over every `entityCollections` entry and asserts
`WARMING_UP`) and fire on more than session close:

| trigger | site |
|---|---|
| session close | `EvitaSession` close, while `WARMING_UP` |
| entity collection **created** | `Catalog.java:2233-2236` ("immediate flush when collection is created") |
| collection removed | `Catalog.java:2283-2286` |
| collection replaced / renamed | `Catalog.java:2424-2430` |
| goLive | `MakeCatalogAliveMutationOperator:105` (flush, *then* `goLive()`) |

So the ≥2-flush precondition is met by (a) **multiple sequential warm-up sessions** (one per batch — the usual
shape for a large re-index), or (b) **a new collection created after an existing one already holds data**, which
re-flushes the older collection's indexes mid-session. The goLive flush alone is harmless: the close-flush left
everything clean, so it pops nothing.

**Falsifiable prediction for senesi:** since that dataset corrupted, ≥2 real flushes of the affected index
provably happened — so the round-1 note "fresh catalog, ONE warm-up session + goLive" cannot be the whole story.
Check whether `EvitaFullReindexJob` (FG client) opens a session per batch, or defines schemas lazily as it loads.
Corollary workaround (fragile — do not rely on it): a single-session re-index with all schemas defined up-front
cannot hit this.

### Survival condition (why this is intermittent, and why it lands on the *early* pages)

A merge-only flush does **not** latch the corruption by itself. `collectChangedPages` rebuilds
`orderedPageSequences` from **all** current handles on every flush, so **any later flush of that stream with
`pageListChanged = true` re-emits the full, correct list and silently delists the detached donor** — leaving only
a leaked record, not an overlap. (The donor is gone from the tree, so it can never re-enter the list.)

The corruption therefore reaches disk only when **no subsequent `pageListChanged = true` flush follows the
page-dropping merge for that stream** — i.e. the merge lands in the *last* flush that stream ever sees, or the
stream stops growing (no split ⇒ no `anyFreshLeaf`) before warm-up ends. That is a *required* condition, and it
predicts the two things the incident actually shows:

- **it is intermittent** — most reindexes self-heal, which is why this survived many clean runs;
- **it lands on pages 0/1** — with near-monotonic timestamps the tree grows at the **right** edge, so the leftmost
  region stops splitting early while re-publishes keep emptying its buckets. The low-churn early pages are exactly
  where a merge can be the stream's last list-changing event.

The two merge directions map 1:1 onto the two incidents observed so far:

- **`mergeWithRight`** — survivor's *last* key rises to the absorbed tail; the stale donor is listed **after** it.
  ⇒ `page0.last (18:20:10.32) > page1.first (18:20:08.78)`, donor's whole range now nested inside page 0.
  **This is today's incident, exactly.**
- **`mergeWithLeft`** — survivor absorbs the left sibling, so its content becomes `[donor's keys] + [its own]`,
  and the stale donor is listed **before** it. ⇒ round-1's evidence verbatim: *"page seq 29 = 128 buckets, page
  seq 30 = identical 128-bucket prefix + 62 later keys, same record ids"*. **Round 1 read this as "the left half
  of a split, frozen at the split moment"; it is actually the left sibling that a merge absorbed.**

## 3. Why every prior probe missed it (all four negatives now explained)

- **Transactions are clean** — the commit-merge publishes, so `freed` is right, the donor page is removed and the
  root is re-emitted. Matches the clean 273-tx WAL replay + clean cold reload of the previous round.
- **The live process is healthy** — the in-memory tree is *correct*. Only the persisted root list and the
  orphaned donor record are wrong. Corruption is invisible until a cold load. Matches "reindex looked fine".
- **The full-scale `WarmupTwinSimulation` (380,016 entities, single-threaded) came back CLEAN** — it is a *pure
  copy*: inserts only ⇒ no bucket ever empties ⇒ **no underflow ⇒ no merge ⇒ no bug**. The negative was real, and
  it is now evidence *for* this diagnosis rather than against it.
- **Why `OffsetDateTime` specifically** — a near-unique per-entity timestamp gives **single-record buckets**, so
  re-publishing an entity (`t1 → t2`) *deletes* bucket `t1` outright. Leaf occupancy falls below
  `minValueBlockSize` and consolidates. A low-cardinality attribute's buckets almost never vanish, so its leaves
  almost never merge. This is why the timestamp index is the one that breaks — and it matches round 1's
  "same entity re-published within tens of ms" quads. **No concurrency is required.**

## 4. Blast radius

**Family-wide.** All seven paged indexes publish only from `createCopyWithMergedTransactionalMemory`, so all seven
carry the same warm-up gap: `InvertedIndex` :938, `RangeIndex` :847, `ChainIndex` :1122, `OwnerUniqueIndex` :418,
`GlobalUniqueIndex` :675, `PriceListAndCurrencyPriceSuperIndex` :446, `ReferenceTypeCardinalityIndex` :643.

Two further consequences of the same dead baseline (secondary, not today's crash):

- **Freed pages leak on disk for the entire warm-up** — the append-only OffsetIndex never reclaims a record that
  is neither superseded nor explicitly removed. (Ironically this leak is what makes the crash *loud*: the donor's
  bytes are still readable. Had the removal worked, the failure would be a missing-record error instead.)
- **The `PAGED → SINGLE` collapse removes nothing in warm-up** — it reclaims via `livePageSequences()` (published
  ⇒ ∅) and then `forgetPageStream()`, which resets the allocator; a later regrow re-issues sequences from 0 over
  records that were never removed.

## 5b. PILOT FIX APPLIED — `InvertedIndex` only (2026-07-17)

Per Johnny: fix **one** implementation, confirm, then roll out to the rest with a repro test each. The pilot is
`InvertedIndex` (the index that actually broke in production).

**The fix (one behavioural line).** `InvertedIndex.collectChangedPages()` now begins with
`publishPreviousFlush()` → `pageStreamRegistry.publishStaged()`:

```java
public PageEmission<LeafPage> collectChangedPages() {
    publishPreviousFlush();   // promote the PREVIOUS flush's staged set to live before diffing this one
    ...
}
```

**Why this seam** (rather than the `notifyFlushed` cascade sketched in §5):

- **It is self-contained and per-index** — exactly the shape a pilot-then-rollout needs. The `notifyFlushed`
  route is a single architectural change (SPI + `EntityIndex` + components) that cannot be rolled out one index
  at a time, and it would have silently changed the publish timing for all seven at once.
- **It cannot be forgotten.** The publish lives inside the method that stages, so no caller has to remember it.
- **It does not touch the transactional path at all.** The commit-merge still publishes; by the next collect
  `staged` is already `null`, so the call is a no-op. The commit handshake is unchanged.
- **Durability holds, path-independently.** The invariant is: *flushes of a stream are sequential and a flush
  failure is fatal*, so by the time flush N collects, flush N−1 is over and its bytes are durably on disk (or the
  process died and `fromPersistedPages` rebuilds the registry from disk — allocation is advance-only, so a burnt
  id is harmless). And `staged` always holds exactly what that flush wrote. So *"still staged when the next flush
  begins"* ⇒ *"on disk"*, whichever path staged it and whether or not a merge ever ran. Note this is the reason it
  is safe — **not** the fact that it happens to be a no-op on the transactional path; the six rollout copies
  should inherit this reasoning, not the coincidence.
- `notifyFlushed` fires at *collect* time (`popTrappedChanges` runs before `flushInternal` writes), so it is
  **not** a post-durable hook either — it holds no durability advantage over this seam.

**Known gap left for the rollout (deliberate, pre-existing):** the `PAGED → SINGLE` collapse reclaims via
`livePageSequences()` (the *published* set) in a branch that never calls `collectChangedPages`, so in warm-up it
still removes nothing and then `forgetPageStream()` resets the allocator. That is a **leak, not corruption** —
the SINGLE root carries its buckets inline and references no pages, and a regrow supersedes the old records at
the same keys. Worth closing during the rollout.

## 5. Fix direction (superseded by §5b for `InvertedIndex`; still the map for the other six)

The seam already exists and is already documented for **this exact bug class, one baseline over**:
`EntityIndex.notifyFlushed()` (:927), invoked by `DataStoreChanges.popTrappedUpdates()` (:216-221) *"once,
immediately after `getModifiedStorageParts` has collected this index's parts … so it runs exactly when the parts
are actually written"*. Its javadoc describes the warm-up→transactional baseline gap it was written to close —
for the *manifest* key sets, via `captureOriginalsFromComponents()`. **The page-live set is the same kind of
baseline and was simply not routed through it.**

Preferred fix: cascade `notifyFlushed()` down to the paged indexes so it publishes the staged page sets on
**every** flush (warm-up and transactional alike); the existing publish in the commit-merge then becomes a
no-op second publish (harmless — `publishStaged` clears `staged`) and should be removed for a single owner.

**Verify, don't assume, one ordering detail before adopting that seam:** `notifyFlushed()` fires at *collection*
time inside `popTrappedUpdates()` — the parts are in `TrappedChanges`, not yet durably written — whereas the
registry's handshake is specified as publish-**when-durable**. That is probably fine (a warm-up flush failure is
fatal, and restart rebuilds the registry from disk — the rationale `InvertedIndex` :934-937 already relies on),
but it is the one thing the implementer must confirm rather than take on faith.

Defence in depth worth considering in the same change: derive `pageListChanged` from an actual comparison of
`orderedPageSequences` against the last-persisted list, rather than from the two proxies `anyFreshLeaf ||
freed ≠ ∅` — the proxies are only as good as the baseline, which is what failed here.

## 6. Proof — end-to-end repro, RED before the fix, GREEN after (2026-07-17)

`evita_functional_tests` → `io.evitadb.api.functional.storage.FilterIndexPagedPersistenceTest`
→ **`shouldReloadAfterAWarmUpFlushMergedALeaf`**. No senesi, no server, **no concurrency**, ~2 s.

It stays in WARM_UP across two flushes (closing a warming-up session flushes synchronously:
`Catalog.flush → EntityCollection.createFlushFuture → popTrappedChanges`):

1. warm-up session #1 — 513 entities, one distinct ascending `OffsetDateTime` each → four leaves
   `[1..128] [129..256] [257..384] [385..513]`, close ⇒ **flush #1** lands pages 0–3 + the root;
2. warm-up session #2 — delete pk **129** (leaf 1: 128→127, so it can no longer donate), then pk **1** (leaf 0:
   128→127), then pk **2** (leaf 0: 127→126 ⇒ underflow; no left sibling, right sibling can't donate since
   `127 > 127` is false, and `127+126 = 253 < 256`) ⇒ **`mergeWithRight`**. Close ⇒ **flush #2**;
3. assert the tree is still PAGED (a collapse to SINGLE force-emits the root and would mask the defect), then
   close + reopen and read every surviving value back.

**Before the fix** — Johnny's production error, reproduced verbatim:

```
Tests run: 3, Failures: 0, Errors: 1
Corrupted persisted inverted index for type `java.time.OffsetDateTime`: leaf-page sequence 0 overlaps its
successor leaf-page sequence 1 — its last key (2026-07-16T18:24:16Z) does not sort before the first key
(2026-07-16T18:22:09Z) of the next leaf page.
```

The arithmetic nails the mechanism: page 0's last key is **pk 256** (leaf 0 absorbed leaf 1's tail), while page
1's first key is **pk 129** — *an entity the test had deleted*. Page 1 on disk is the pre-deletion snapshot from
flush #1: the stale twin.

**After the fix** — `Tests run: 3, Failures: 0, Errors: 0`.

**The test cannot rot into a vacuous pass.** It pins the merge itself — `timestampIndexLeafPageCount()` (via
`InvertedIndex.currentLeafPageSequences()`) must read **4** after flush #1 and **3** after flush #2. Without that,
a future drift in the block-size constants would stop the deletes from underflowing a leaf, and the test would
sail through green while exercising nothing. Re-verified by temporarily disabling `publishPreviousFlush()`: the
4→3 assertions still pass and the reload still throws the production error — so the guard is real, not incidental.

**Coverage note for the rollout:** this repro drives `mergeWithRight` only. The fix is merge-direction-agnostic
(it repairs the `live` baseline; nothing in it is direction-specific), so one direction proves it — but the
rollout docs should not imply `mergeWithLeft` (round-1's shape) is covered by a test.

**Regression sweep:** `io.evitadb.index.**` + `io.evitadb.api.functional.storage.**` ⇒ **3743 run / 0 failures /
0 errors** (includes `WarmUpFlushFailureCloseTest`, `StaleLeafPageTwinReproductionTest`, both paged-persistence
round-trips and `PageStreamRegistryTest`).

**Scaffolding removed:** the interim `PageStreamRegistryTest$WarmUpFlush` lemma test (which proved
`freedPageSequences` came back empty without a publish — `expected: <1> but was: <0>`) was **deleted** once the
end-to-end repro existed: it asserted at the registry layer a duty the registry does not own (the caller
publishes), so it could not go green under any correct fix. Do **not** re-add it by making `freed` diff against
the **staged** set — that would break publish-when-durable on the transactional path.
