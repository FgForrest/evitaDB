# Brief: warm-up page-baseline defect in evitaDB — corruption + page leaks

*Self-contained briefing for an advisory model. Repo `evitaDB`, branch `dev` @ `2fac0b066`, 2026-07-17.
Everything below is either **VERIFIED** (stated with its evidence) or explicitly flagged as **INFERRED / OPEN**.*

---

## 0. What I want from you

We have a **confirmed, reproduced, and pilot-fixed** data-corruption bug, plus a **related family of page leaks
sharing the same root cause**. The pilot fix closes the corruption but deliberately leaves the leaks open.

**The ask: a design verdict on how to close corruption AND leaks together, across all 7 affected indexes.**
Concretely:

1. Is the applied pilot seam (§6) sound — in particular its durability argument? Or does one of the alternatives
   in §7 dominate it?
2. What is the right way to close the leak inventory in §5, given the constraint that we want a **per-index,
   repeatable** change (the rollout is already in flight for 6 indexes)?
3. Adjudicate the open questions in §8 — several are things I could not settle from the code alone.

Challenge the framing if it's wrong. Note that §7 rejects one option (`notifyFlushed` cascade) for reasons I'd
like checked, since I rejected it partly on rollout-shape grounds, not purely technical ones.

---

## 1. System background (minimum needed)

evitaDB is an in-memory NoSQL search index that persists to an **append-only** `OffsetIndex` store. Records are
keyed; a write with an existing key **supersedes** the old record; **a record that is neither superseded nor
explicitly removed is never reclaimed** — it is copied forward on every compaction, forever. This is why "free
the page" means "emit an explicit removal", not "stop referencing it".

**Catalog lifecycle.** A catalog starts in `WARMING_UP` (bulk load, **no transactions**), then `goLive()` makes it
`ALIVE` (transactional). Both states flush through the *same* pipeline.

**Granular index paging.** Large sub-indexes (attribute filter index, range index, price index, …) persist their
backing B+ tree as **one storage-part record per leaf page** (`PAGED` shape) plus a **root part** that carries,
among other things, the **ordered list of live leaf-page sequences**. A small (single-leaf) index instead persists
inline (`SINGLE` shape) and references no pages.

**`PageStreamRegistry`** (`evita_engine/.../index/page/PageStreamRegistry.java`) is per-index, lives **outside**
transactional memory, and holds per page-stream:
- an **advance-only allocator** + explicit **high-water** (page ids are never reused),
- the **published live-page set** = *"which leaf pages does this stream have on disk"*.

Its documented **commit handshake**: a flush **stages** the next live set (`stage`); the staged set becomes live
only when the commit is **known durable** (`publishStaged()`); `discardStaged()` exists for an abort.

**The write path** (`PageStreamRegistry.collectChangedPages`, the shared skeleton behind every paged index):

```java
for (final H handle : handles) {                       // leaf handles, ascending key order
    int pageSequence = handle.getPageSequence();
    final boolean freshLeaf = pageSequence == UNASSIGNED_PAGE_SEQUENCE;
    if (freshLeaf) { pageSequence = allocate(streamId); handle.setPageSequence(pageSequence); anyFreshLeaf = true; }
    orderedPageSequences[idx++] = pageSequence;
    nextLive.add(pageSequence);
    if (freshLeaf || handle.isDirty()) { changedPages.add(pageBuilder.build(pageSequence, handle)); handle.clearDirty(); }
}
final int[] freedPageSequences = freedPageSequences(streamId, nextLive);   // = livePages(published) − nextLive
stage(streamId, nextLive);
final boolean pageListChanged = anyFreshLeaf || freedPageSequences.length > 0;
return new PageEmission<>(changedPages, orderedPageSequences, highWater(streamId), freedPageSequences, pageListChanged);
```

`freedPageSequences` drives **explicit removals**. `pageListChanged` drives a **root-skip optimization**: the
owner skips re-emitting the root when the page list is unchanged (steady-state root cost → O(1)), e.g.
`HistogramIndex:394-399`:

```java
final boolean bucketRootStable = bucketPaged && !bucketListChanged;
if (bucketRootStable && rangeRootStable) { return; }   // ← root (and its ordered page list) not re-emitted
```

---

## 2. The confirmed defect (VERIFIED)

**`publishStaged()` is called from exactly ONE place in each of the 7 paged indexes:
`createCopyWithMergedTransactionalMemory` — the transactional commit-merge.**
(Verified by grep + reading each enclosing method: `InvertedIndex`, `RangeIndex`, `ChainIndex`,
`OwnerUniqueIndex`, `GlobalUniqueIndex`, `PriceListAndCurrencyPriceSuperIndex`,
`ReferenceTypeCardinalityIndex`.)

**A WARM_UP flush never reaches a commit-merge.** It runs the identical collect pipeline
(`DataStoreChanges.popTrappedUpdates()` → `index.getModifiedStorageParts(...)`), but the merge that publishes only
ever runs for a transaction. Therefore, on a freshly re-indexed catalog, the **published live set stays empty for
the entire warm-up** while disk moves on ⇒ `freedPageSequences() ≡ ∅` ⇒

- **(a)** merge-freed pages are **never removed** from storage, and
- **(b)** `pageListChanged = anyFreshLeaf || freed≠∅` is **false for a merge-only flush** ⇒ the **root is skipped**
  ⇒ the persisted root **still lists the dropped page**.

### Why a MERGE and not a split (this is the crux)

`AbstractTransactionalBPlusTree.consolidate` merges by mutating the **survivor in place**
(`:619 mergeWithLeft` / `:648 mergeWithRight`): the survivor keeps its own `pageSequence` (which is *structural*,
not transactional) and is marked `dirty = true` (`TransactionalBucketBPlusTree:3921/:3963`); the donor is simply
detached from the parent. **No new leaf object is born ⇒ nothing is allocated ⇒ `anyFreshLeaf == false`.**

A **split**, by contrast, creates two brand-new leaves (both `UNASSIGNED`) ⇒ allocation ⇒ `anyFreshLeaf = true` ⇒
`pageListChanged = true` ⇒ root re-emitted ⇒ **a split can never leave a stale list entry**. (An earlier round of
this investigation blamed "the left half of a split, frozen at the split moment"; that reading is **refuted** and
superseded.)

### The failure

On cold load the tree is assembled from the stale root list: survivor page (fresh, now holding the **absorbed**
keys) followed by the donor page (stale, never removed, never delisted). The survivor's range now **covers** the
donor's first key ⇒ the cross-page overlap validator throws:

```
Corrupted persisted inverted index for type `java.time.OffsetDateTime`: leaf-page sequence 0 overlaps its
successor leaf-page sequence 1 — its last key (…18:20:10.32Z) does not sort before the first key
(…18:20:08.78Z) of the next leaf page.
```

Both merge directions map onto observed production evidence: `mergeWithRight` = the current incident (donor's
range nested inside the survivor's); `mergeWithLeft` = an earlier incident's dump (*"page seq 29 = 128 buckets,
page seq 30 = identical 128-bucket prefix + 62 later keys"* — i.e. the survivor absorbed its left sibling).

### Preconditions (VERIFIED)

- **≥ 2 real flushes of the same index.** The first flush is always sound: every leaf is fresh ⇒ all pages
  allocated ⇒ `freed = ∅ − nextLive = ∅` **correctly** (nothing was on disk). In-memory splits/merges before it
  are invisible. So a single meaningful flush can neither corrupt nor leak.
  Warm-up flushes are **catalog-wide** (`Catalog.flush()` maps over every collection, asserts `WARMING_UP`) and
  fire on: session close; **collection created** (`Catalog.java:2233-2236`); collection removed (`:2283-2286`);
  collection replaced/renamed (`:2424-2430`); **goLive** (`MakeCatalogAliveMutationOperator:105` — flush, *then*
  `goLive()`). So ≥2 flushes arise from multiple sequential warm-up sessions (session-per-batch), or from a new
  collection created after an existing one already holds data.
- **Survival condition.** Any *later* flush of that stream with `pageListChanged = true` rebuilds the ordered list
  from all current handles and **silently self-heals** it (the detached donor can never re-enter the list). So the
  corruption only latches when the page-dropping merge is the stream's **last list-changing flush** — which is why
  it is intermittent and why it lands on **low-churn early pages** (with near-monotonic timestamps the tree grows
  at the right edge; the leftmost region stops splitting while re-publishes keep emptying its buckets).
- **Why `OffsetDateTime` specifically.** Near-unique values ⇒ single-record buckets ⇒ a re-publish/delete removes
  the bucket outright ⇒ leaf underflow ⇒ merge. Low-cardinality attributes almost never merge. **No concurrency
  is required** (earlier rounds hunted a concurrency writer for days — that was a dead end).

### Why every prior probe missed it

- Transactions are clean — the merge publishes, so `freed` is right and the root is re-emitted. (A full production
  WAL replay of 273 transactions was clean, which had wrongly *exonerated* the write path.)
- The live process is healthy — **the in-memory tree is correct**; only the persisted root list + the orphaned
  record are wrong. Invisible until a cold load.
- A full-scale 380k-entity warm-up **copy** simulation came back clean — it is *pure inserts*, so no bucket ever
  empties ⇒ **no merge ⇒ no bug**. That negative is now evidence *for* this diagnosis.

---

## 3. Reproduction (VERIFIED, RED → GREEN)

`evita_functional_tests` → `FilterIndexPagedPersistenceTest.shouldReloadAfterAWarmUpFlushMergedALeaf`.
No production data, no server, **no concurrency**, ~2 s. Stays in WARM_UP across two flushes (closing a
warming-up session flushes synchronously: `Catalog.flush → EntityCollection.createFlushFuture →
popTrappedChanges`):

1. session #1: 513 entities, one distinct **ascending `OffsetDateTime`** each ⇒ 4 leaves
   `[1..128] [129..256] [257..384] [385..513]` (valueBlockSize 256 ⇒ split at 128; minValueBlockSize 127); close
   ⇒ **flush #1** writes pages 0–3 + root;
2. session #2: delete pk **129** (leaf 1: 128→127, so it can no longer donate), pk **1** (leaf 0: 128→127), pk **2**
   (leaf 0: 127→126 ⇒ underflow; no left sibling; right sibling can't donate since `127 > 127` is false;
   `127+126 = 253 < 256`) ⇒ **`mergeWithRight`**; close ⇒ **flush #2**;
3. assert still PAGED (a collapse to SINGLE force-emits the root and would mask the defect), then close + reopen.

**Pre-fix**: reproduces the production error verbatim. Diagnostic detail confirming the mechanism: page 0's last
key is **pk 256** (the absorbed tail) while page 1's first key is **pk 129** — *an entity the test deleted*; page 1
on disk is the flush-#1 snapshot. **Post-fix**: green.

The test **pins the merge** (asserts live leaf-page count 4 → 3) so it cannot rot into a vacuous pass if block-size
constants drift. Re-verified by temporarily disabling the fix: the 4→3 asserts still pass and the reload still
throws.

**Merge-forcing arithmetic** (from `consolidate`, reusable for the other indexes): a leaf underflows when
`keyCount < minValueBlockSize`; a sibling can donate only when `sibling.keyCount() > minValueBlockSize`; a merge
happens when `sibling.keyCount() + node.keyCount() < valueBlockSize`. So bring the sibling to exactly the minimum
first, then push the target one below.

---

## 4. Blast radius — and a natural experiment that confirms the mechanism

All **7** paged indexes share the identical **publish** gap (each publishes only from its commit-merge):
`InvertedIndex` (attribute filter + histogram bucket axis), `RangeIndex`, `ChainIndex`, `OwnerUniqueIndex`,
`GlobalUniqueIndex`, `PriceListAndCurrencyPriceSuperIndex`, `ReferenceTypeCardinalityIndex`.

**But the corruption needs TWO conditions — `freed ≡ ∅` AND the root-skip — and they can be decoupled.**
`GlobalUniqueIndex` re-emits its `PAGED` root **unconditionally** on every dirty flush, because that root also
carries the inline `idToLocaleIndex` which moves in lockstep with the tree. Its own comment says so:

> *"unlike the pure page-list roots (Chain / OwnerUnique / OwnerSort / FilterIndex), this root also carries the
> inline idToLocaleIndex … so it is re-emitted every dirty commit and CANNOT use the
> `PageEmission.pageListChanged()` skip."*

Consequently `GlobalUniqueIndex` **can leak (L1) but cannot corrupt** — its persisted page list is always current.
(Verified by reading the code; independently found while writing its repro, whose RED assertion is the *removal*,
not an overlap.) This is an accidental natural experiment: **an index with the same empty baseline but no
root-skip produces only the leak, exactly as the mechanism in §2 predicts.** It also means §7's option G
("drop the root-skip") really would kill the corruption while leaving the leak — the two halves are separable, and
a complete fix must address both.

---

## 5. The leak inventory (the second half of the ask)

The same dead baseline produces leaks. In the append-only store a missed removal is **permanent** (copied forward
on every compaction).

| # | Leak | Status |
|---|---|---|
| **L1** | **Merge-freed pages never removed** during warm-up (`freed ≡ ∅`). | **Closed by the pilot fix** for `InvertedIndex`: flush N+1 publishes S_N, so `freed = S_N − S_{N+1} = {donor}` and the removal is emitted in the same flush that observes the merge. Rollout closes the other 6. |
| **L2** | **`PAGED → SINGLE` collapse reclaims nothing** in warm-up. The collapse branch reclaims via `livePageSequences()` (the **published** set) and then calls `forgetPageStream()`. That branch **never calls `collectChangedPages`**, so the pilot's publish never runs there ⇒ published set is stale/empty ⇒ **no removals emitted** ⇒ every prior leaf page leaks. | **OPEN** |
| **L3** | **Allocator reset after `forgetPageStream()`.** `forget()` drops the stream, so a later regrow re-issues sequences from 0 **over records that L2 never removed**. New writes supersede at the same key (so content is correct), but orphans at sequences above the regrown count linger unreferenced forever. | **OPEN** (consequence of L2) |

**Note the irony**: L1 is what made the corruption *loud* — the donor's bytes were still readable, so we got an
overlap error rather than a missing-record error.

**Already-safe by comparison** (a useful precedent — the codebase solved *this exact problem* one baseline over):
`AttributeIndex` keeps `persistedFilterInvertedLeafPages` / `persistedChainLeafPages` / … for its **empty-drop
reclaim** (when a sub-index empties and is dropped from its map, its own flush never runs again, so this snapshot
is the only remaining record of its live pages). Those snapshots are refreshed **at the end of every
`getModifiedStorageParts`**, explicitly *"so a reused instance (**warm-up** / repeated flush) stays current"*, and
they are built from **`currentLeafPageSequences()` → `PageStreamRegistry.pendingLivePageSequences()`**, i.e.
**staged-or-live** — deliberately *working around* the unpublished live set rather than fixing it.

`pendingLivePageSequences()` is exactly *"what disk holds after this commit"*: it returns `staged` when a flush has
staged this commit, else the published `live`. **This suggests an obvious candidate fix for L2** (see §7).

---

## 6. The pilot fix (APPLIED to `InvertedIndex` only; 6 more in flight)

`InvertedIndex.collectChangedPages()` now begins with:

```java
public PageEmission<LeafPage> collectChangedPages() {
    publishPreviousFlush();   // → this.pageStreamRegistry.publishStaged();
    ...
}
```

**Durability argument (please audit this).** The registry's contract says *publish when durable*. Publishing the
**previous** flush's staged set at the **start of the next** flush is claimed correct for every path because:

> flushes of a stream are **sequential** and **a flush failure is fatal**; therefore by the time flush N collects,
> flush N−1 is over and its bytes are durably on disk (or the process died — and the registry is rebuilt from disk
> at load by `fromPersistedPages`; allocation is advance-only so a burnt id is harmless). And `staged` always holds
> exactly the page set that flush wrote. So *"still staged when the next flush begins"* ⇒ *"on disk"*, regardless of
> which path staged it and regardless of whether a merge ever ran.

It is also a **no-op on the transactional path** (the merge published first, leaving `staged == null`), so the
commit handshake is untouched — but that is a side effect, **not** the reason it is safe.

**Verification**: repro RED→GREEN; regression sweep `io.evitadb.index.**` + `io.evitadb.api.functional.storage.**`
= **3743 run / 0 failures** (includes a warm-up flush-failure-on-close test and both prior twin repros).

---

## 7. Solution paths for "corruption + leaks, all 7 indexes"

**A — pilot: publish at the top of `collectChangedPages()` (per index).** *Applied.*
Closes corruption + **L1**. Self-contained (impossible for a caller to forget), per-index ⇒ fits a
pilot-then-rollout, tx path untouched. **Does not close L2** (that branch never calls collect).

**B — make the collapse-reclaim pending-aware.** Change the `PAGED → SINGLE` branch to reclaim from
`pendingLivePageSequences()` (staged-or-live) instead of `livePageSequences()` (published). At the collapse flush,
`staged` = the last PAGED flush's set = exactly what disk holds; on the tx path `staged == null` ⇒ falls back to
`live`, which is correct there. **This appears to close L2 + L3 with a one-accessor change per index, and it
reuses the pattern `AttributeIndex.currentLeafPageSequences()` already relies on.** *This is my current
recommendation to pair with A — please sanity-check it.*

**C — publish before the PAGED/SINGLE branch decision.** Closes corruption + L1 + **L2** together, because the
collapse branch then reclaims against a published baseline.

**This is where the family splits structurally — an important empirical finding from the rollout:**

- **Indexes that own their own flush branch** (`ChainIndex`, `OwnerUniqueIndex`, `GlobalUniqueIndex`): the
  `isPaged()`/`isRootInternal()` decision lives *inside* the index's own `appendStorageParts`, so a publish at the
  top of that method sits before both branches. **`ChainIndex` is already fixed this way** (`doAppendStorageParts`
  publishes, then branches) and therefore **has L2 closed**; the two unique indexes were fixed at
  `collectChangedPages()` (PAGED-only) and **still have L2 open**. Purely a placement choice — they could be moved.
- **Indexes whose branch is owned by someone else** (`InvertedIndex`, `RangeIndex`): the decision lives in
  `FilterIndex.appendBucketAxis` / `appendRangeAxis` / `HistogramIndex`, and the SINGLE branch calls
  `index.livePageSequences()` directly. These have **no pre-branch entry point of their own**, so C is only
  reachable by making each *owner* call it — per-owner, not per-index (`InvertedIndex` alone has 2 owners), hence
  forgettable.

⇒ **C cannot be applied uniformly, but B can** (it needs no publish at all in the collapse path). That asymmetry is
the main reason I lean A + B rather than A + C — please check that reasoning.

**D — `notifyFlushed()` cascade (REJECTED — please check this reasoning).** `EntityIndex.notifyFlushed()` (:927) is
invoked by `DataStoreChanges.popTrappedUpdates()` (:216-221) right after `getModifiedStorageParts`, and its javadoc
was written to close *this very bug class for a different baseline* (the manifest key sets, via
`captureOriginalsFromComponents()`). Adding an `IndexComponent.notifyFlushed()` SPI method and cascading down would
cover all 7 indexes and both branches at once. **Rejected because**: (i) it is one architectural change, so it
cannot be rolled out/validated one index at a time (the stated constraint); (ii) it fires at **collect** time —
`popTrappedChanges()` runs *before* `flushInternal()` writes — so it is **not** post-durable either and holds no
durability advantage over A; (iii) it would change publish timing for all 7 simultaneously.
*Counter-consideration: it is the only option that puts the publish in a single owner for the whole family, and
the javadoc arguably already designates it as the home for post-flush baseline advances.*

**E — a real post-durable hook.** Add a callback after `flushInternal` actually writes, and publish there. Most
faithful to the documented contract. Cost: real plumbing (the flush future completes asynchronously; the popped
indexes must be retained to call back into). **No such hook exists today.**

**F — remove the baseline dependency entirely.** Derive `freed` / `pageListChanged` by comparing the ordered page
list against the **last-persisted** list (kept on the index outside transactional memory — precisely the shape of
`persistedFilterInvertedLeafPages`). Heavier, but makes the root-skip and the reclaim independent of any
publish handshake.

**G — defence in depth (orthogonal, cheap).** `pageListChanged` currently infers "did the list change" from two
**proxies** (`anyFreshLeaf`, `freed ≠ ∅`) and both are only as good as the baseline that just failed. Comparing the
actual `orderedPageSequences` to the last-emitted list would make the root-skip unable to be wrong even if a
baseline goes stale. Alternatively, **drop the root-skip optimization** — that alone kills the *corruption* (the
root would always be correct) but not the leaks, and it costs a deliberately engineered O(1) steady-state.

---

## 8. Open questions (where I want your judgment)

1. **Is A's durability argument airtight? This is the one I most want adjudicated.** The argument leans on
   *"flushes are sequential and a flush failure is fatal"* — but **"fatal" looks like a comment, not an
   invariant**. Counter-evidence found in-tree: `WarmUpFlushFailureCloseTest` ("Warm-up close-time flush failure
   must not hang the session close") proves a warm-up flush **can throw**, and the *shipped* behaviour is
   explicitly to **surface it and keep the engine alive** — its javadoc: *"The fix must complete the session close
   future EXCEPTIONALLY on any close-time flush throw so the close terminates in bounded time … asserts the close
   surfaces the failure and `Evita.close()` still returns."* It also documents that `popTrappedChanges()` runs
   **synchronously inside `Catalog.flush()`**, i.e. the baseline advance happens *before* the write either way.
   So: if a flush's write fails and the engine then flushes that same index again (possible? a failure at
   collection-creation `Catalog.java:2236` mid-session, vs. one at close where teardown follows anyway), A would
   publish a set that never reached disk ⇒ `live` claims pages that don't exist ⇒ later `freed` diffs emit
   removals for non-existent records (harmless?) or miss real ones (a leak).
   **Calibration**: today's behaviour is *guaranteed* corruption on a merge, so A is strictly better regardless —
   the question is whether A is *right* or merely *better*, and whether E (a true post-durable hook) is worth the
   plumbing to make the contract real rather than assumed.
2. **Is B (pending-aware collapse reclaim) correct**, or does `staged`-as-truth reintroduce the very
   "what-will-be vs what-is" confusion the handshake exists to prevent? Note `AttributeIndex` already does exactly
   this for its empty-drop reclaim, so either both are right or both are wrong.
3. **`captureOriginalsFromComponents()` re-runs `collectModifiedStorageParts`** (a discardable baseline-capture
   pass). With A, that re-run can call `collectChangedPages` a second time and thus **publish this flush's own
   staged set before the write**. The end state appears identical (same `nextLive`), and the sweep is green — but
   is there a case where the double pass diverges?
4. **`discardStaged()` is never called from production code** (verified: the only references are its own
   definition/javadoc plus two calls in `PageStreamRegistryTest` — so it is tested but unused). The registry
   javadoc advertises it as the abort half of the handshake, while `InvertedIndex` separately argues no discard is
   needed ("a pre-flush abort never stages, a flush failure is fatal"). Should it be wired, or deleted? Does its
   existence imply an abort-after-stage path someone anticipated and we have not reasoned about — and does A
   interact with it (A would *publish* a stale staged set that a discard was meant to drop)?
5. **L3 / allocator reset**: is superseding a never-removed record at a re-issued page id genuinely safe, or is
   there a compaction/versioning path where the stale record can win? Page ids are advertised as advance-only
   precisely so a retained older catalog version keeps resolving the bytes it expects — `forgetPageStream()`
   violates that. Does that interact with retained versions / time-travel reads?
6. **Do we need a load-side guard too?** Today the overlap validator turns this into a hard boot failure with
   "restore from backup". Given the in-memory tree was *correct* and only the list was stale, is there a defensible
   self-heal (e.g. ignore a listed page fully covered by its predecessor) — or is fail-fast right and should the
   effort go into a repair tool?
7. **Is there any *transactional* exposure** we have missed? We believe not (the merge publishes), and a 273-tx
   production WAL replay + cold reload were clean — but that same reasoning previously led us to exonerate the
   write path entirely.

---

## 9. Constraints

- **Per-index, repeatable** changes are strongly preferred: a pilot on one index is validated, then rolled out to
  the rest, each with its own repro test. (A rollout for the remaining 6 is already in flight using seam A.)
- **TDD is mandatory**: a failing test that reproduces the defect must exist and be observed RED *before* the fix;
  a fix whose test was never red is not accepted.
- Java 17, tabs, ≤100 cols, markdown javadoc (no HTML), `final` locals, `this.` for fields, no `var`,
  `@Nonnull`/`@Nullable`. Unreachable states must **throw**, never silently no-op. No issue numbers or doc names in
  code comments.
- Nothing is committed; the fix + repro currently live in the working tree.
