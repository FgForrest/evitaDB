---
title: Only the management reads of the B+ trees are session-free, so only their walks keep reader-side guards
date: 2026-10-20
updated: 2026-10-20 11:03
status: accepted
kind: refactor
issues: [1761]
prs: [1764]
areas: [evita_engine/index/bPlusTree, evita_engine/index/array, evita_engine/core/management, evita_test/evita_functional_tests/index/bPlusTree, evita_test/evita_functional_tests/index/array, evita_test/evita_long_running_tests/index/bPlusTree]
supersedes: []
superseded-by: []
relates: [2026-09-03-content-sized-value-tree-columns, 2026-09-04-long-keyed-tree-content-sizing, 2026-08-10-catalog-and-collection-statistics]
---

# Only the management reads of the B+ trees are session-free, so only their walks keep reader-side guards

The B+ trees had grown a family of reader-side guards for a reader that shares no happens-before edge with a
warm-up writer mutating the tree in place: bounds that clamp a `peek` to the array actually held, steps over a
child slot a grow has not published, `null` answers for an emptied merge donor, cross-column bounds in point
lookups. Most of them protected point lookups, keyed walks, reverse walks, iterators and debug renderings that no
production code ever runs without such an edge. They are gone. The guards that keep the three reads the
management API actually takes - the counters, the forward walk and the heap walk - from throwing stay, and the
long-running sweep now drives only those reads.

## Why

On 2026-10-19 the weekly long-running sweep failed on the macOS/AArch64 leg, at round 2418 of the multi-level
sweep of `LongRunningBucketBPlusTreeConcurrentReadTest`:
`NullPointerException: Cannot invoke "java.lang.Comparable.compareTo(Object)" because "midVal" is null`, thrown by
the natural-order `Arrays.binarySearch` over a key slot an in-place insert had not published yet. The sweep builds
its tree through the six-argument test constructor, which keeps leaf keys in the boxed `BoxedObjectColumn`, so two
`Object[]` searches could throw it: the leaf key search, which had no `null` guard at all, and the internal node's
separator search, whose trailing-`null` trim cannot see a `null` in the middle of a reallocated array (the first of
the "three neighbours" the content-sized columns record had left open). The reader's own stack trace stayed in the
raw job log, so which of the two it was is inferred, not observed; the leaf's last slot is the likelier, because
every insert opens that window. Either way it was reached only because the sweep drove **session-free key
lookups**, which no production code does.

The next guard in line - a `null`-tolerant search, drafted as PR #1763 - would have closed this one escape and
left the family exactly as open as before. The decision taken instead (Johnny, 2026-10-19: "we don't need to be
super-proof for non-existent scenarios") is to guard the contract production actually has:

- A catalog in `WARMING_UP` admits **one** session (`SessionRegistry#addSession`, refusing a second one with
  `ConcurrentInitializationException`). That session is the only writer and the only reader that sees the trees
  mutated in place, and it reads its own writes in program order.
- In `ALIVE`, readers see published versions that are never mutated in place.
- The only readers with no happens-before edge to the warm-up writer are internal **management reads** -
  statistics, index cardinality, index detail and browse. They are not critical and **may return partly wrong
  data**; they must only return rather than throw or hang. `io.evitadb.core.management.ManagementReads` already
  states that contract for the index maps and puts the tolerance at the management boundary.

Before relaxing anything, every other off-writer-thread reader of a warm-up index was looked for. None reaches a
key lookup: the warm-up flush pops its trapped changes on the session thread and runs the rest with an
executor hand-off while the session is still registered; a mid-session flush `join()`s; the formula cache's
background re-evaluation touches only adept metadata captured on the query thread; CDC and traffic recording read
no index; a backup reads storage parts through the persistence service.

### Previous state

The guard family grew one escape at a time, each found by the long-running sweep or an adversarial review, and
recorded in `2026-09-03-content-sized-value-tree-columns` and `2026-09-04-long-keyed-tree-content-sizing`:

- bucket tree: `observableInternalPeek` and `observableChildIndex` on the keyed descent, the reverse walk, the
  previous- and next-node cursor rebuilds and the point descent (`pointLookupChild`), a trailing-`null` trim in the
  separator search, a `null` answer for an emptied donor that six point lookups translated into "absent", and the
  cross-column `observableLeafPeek` on `getRecords`, `previousRecord`, `getValueIndex` and `lastRecord`;
- the five value columns clamped their `findKeyPosition` to the array they search;
- the long, element and int-to-long trees bounded their point lookups, keyed and unkeyed iterators and verbose
  rendering by `observableLeafPeek`; `UnorderedLookupTree` did the same through `observableLeafCount` on its
  positional descent, head select, flatten, cursors, page emission and rendering.

The sweep drove `contains`, `cardinalityOf`, `valueIdOf`, `getRecordsEqualTo`, `computePreviousRecord`, the keyed
walk and the reverse walk on every pass - so it kept finding escapes in reads production never takes off the
writer's thread.

## Options considered

### Option A — narrow the contract to the readers production has (chosen)

Keep the guards on the reads the management API takes without a session, remove every guard that serves only a
read production always takes with a happens-before edge to the writer, and narrow the sweep to match.

- **Pros:** the guarded surface is finite and enumerable - three entry points - and every remaining guard has a
  production reader. A torn state reaching any other read is a bug and now fails loudly instead of being turned
  into a quietly wrong answer. Nothing changes on the write path or the hot read path except that a handful of
  `Math.min` calls, `null` checks and layer re-reads disappear.
- **Cons:** the protection rests on a contract, not on the code: a future session-free caller of a lookup would
  meet no guard. The trigger below names that case.

### Option B — keep extending the reader-side guards to every column type (declined)

Port the `null`-tolerant search, then guard the next escape, and so on, until every read of every tree survives a
torn tree.

- **Pros:** no contract to keep; any reader on any thread survives.
- **Cons:** it cannot be finished. A reader-side guard can only react to what a torn read makes visible, and on the
  primitive columns an unpublished slot reads as a legal `0` - a valid record id and, for a key, a valid value - so
  no reader-side check can tell it from data. The content-sized columns record already found that a missing bound
  in this family "can invent" data: `getRecordsEqualTo` answering `[0]`, `lastRecord` answering the sort-first
  sentinel. Every guard so far turned a crash into a silent wrong answer, for a reader that does not exist.
- **Rejected because:** the family is unbounded on exactly the columns that matter most, and each step buys
  correctness for no production reader while spending review and test effort on the sweep's own artefacts.

### Rejected outright

| Option | Rejected because | Revisit if |
|--------|------------------|------------|
| `VarHandle` release/acquire on `keys`, `children`, `peek` and every column array | A release store of `peek` does not order a freshly reallocated array's copied content; the readers load the array before the count, so closing the window takes fences on all three fields and on every column array, on every descent and every insert of the hottest paths - to protect a reader that does not exist | A production session-free key lookup appears and cannot take a happens-before edge some cheaper way |
| A per-tree seqlock (version counter, readers retry on a change) | Two volatile stores on every write and two volatile loads plus a retry loop on every read, across every tree in every index; the management reads need only to not throw, which the existing bounds already give them | A session-free reader needs a *consistent* figure rather than a best-effort one |
| Immutable `(array, size)` holders published whole | Changes the layout of every leaf and node in four tree families and allocates on every in-place mutation, which the cursor-free insert path must not do; declined on the same grounds by both content-sizing records | A leaf ever needs a genuinely consistent multi-field read |
| The `null`-tolerant key search of PR #1763 (leaf key column and separators) | Closes one escape, covers only the object-keyed searches, and the primitive columns cannot express "unpublished" at all - it is Option B's first step | Never on its own; only as part of Option B if that is ever reopened |
| Drop the guards on the management walks too | The management reads must return rather than throw; that is the one part of the contract the guards actually serve, and `ManagementReads` holds the map half of it | Never while the management API reads warm-up catalogs without a session |
| Delete the long-running sweep | Its multi-level sweep is the only regression detector for the spine bounds the forward walk keeps; its counterfactual still fails on x86 (see *Verification*) | The forward walk stops being session-free |

## Decision

**Chosen: Option A.** The drivers were the size of the guarded surface and who it protects. Option A guards three
entry points that a production reader really takes without a session, and leaves every other read with the plain
code its own thread already makes safe. Option B guards an unbounded surface for no reader, and cannot be made
complete on the primitive columns however far it is taken.

**Revisit if a production session-free key lookup appears** - a management call that looks a key up, walks keyed
or backwards, or reads values out of a boxed or front-coded key column. Reading a primitive `long` key column
through the forward walk is not one; the registry count under *Key technical details* does exactly that, and is
covered. The first answer then is to give that read a happens-before edge (run
it in a session, or on a published version), not to revive the reader-side guards; only if that is impossible does
Option B or one of the writer-side options above come back on the table, with the costs stated there.

## Key technical details

- **The contract is stated where the next reader will look**: the class javadoc of `TransactionalBucketBPlusTree`
  (and the narrowed javadoc of each remaining `observable*` helper), the class javadoc of
  `LongRunningBucketBPlusTreeConcurrentReadTest`, and `ManagementReads` for the maps.
- **What stays**, each because a management read runs it without a session:
  - the forward walk: `addLeftmostCursorLevels` (bound plus emptied-child skip), `ForwardBucketCursor`'s
    construction-time recovery, `loadCurrentLeaf` (non-leaf bottom, `observableLeafPeek`) and `moveToNextLeaf`
    (unpublished-slot skip, `observableInternalPeek`, emptied-node skip);
  - the heap walks: the bucket tree's internal node (bound plus `null` skip) and leaf (read-once fields, overflow
    bounded by `observableLiveRun`), the long and element trees' leaf `observableLeafPeek`, and the `null`-child skip
    of the long tree, `AbstractIntKeyedInternalNode` and `UnorderedLookupTree.InternalNode`;
  - `observableLiveRun` on every column and the bound in `OverflowColumn#recordsAt`, which the forward cursor and
    the heap walk index through; the `volatile` bucket count and both non-negative count guards.
- **One management walk reads keys: the registry count.** `VirtualReferencePartitionIndex#countRegisteredPartitions`
  (#1738, behind `IndexCardinality#virtualPartitionCount`) walks the registry's entry tree with `cursor()` and
  decodes each key to stop at the end of the owner slot. It needs no guard of its own: the key column is the
  primitive `LongValueColumn`, the cursor bounds every index by the column's live run, and a column array only ever
  grows in place, so a torn read yields a stale key and a miscount, never a throw. The same read on a boxed or
  front-coded key column is not covered by that argument - see the trigger under *Decision*.
- **What went**: everything listed under *Previous state*, plus `observableChildIndex`, the int-to-long tree's
  `observableLeafPeek` and `UnorderedLookupTree#observableLeafCount`, which had no caller left.
- **Looks like a bug, is not**: the forward cursor is bounded and the reverse cursor is not; the forward walk is the
  one the statistics take. `findLeafNode` returns `@Nonnull` again and its callers no longer translate `null` into
  "absent". `pointLookupChild`, `childIndexIn` and the sibling trees' `findKeyPosition` helpers survive without
  their bounds because they resolve the transactional layer once, which is a hot-path saving of its own.
  `SingleLeafBucketCursor` and `UnorderedLookupTree`'s page handle lost their bounds because page emission runs
  on the writer's thread - the long-keyed record had already declined that bound for the sibling trees.
- **One verifier had leaned on a removed bound.** `TransactionalIntToLongBPlusTree#getConsistencyReport` ran the
  inherited iterator checks before its own leaf-array coverage check, and the iterators only survived a leaf whose
  `peek` ran past its arrays because they were bounded. The coverage check now runs first, so the report still
  names the short array instead of throwing out of an iterator.
- **`RangeValueColumn`'s inverted-pair check stays**: it is not a survival guard but a loud one, turning a corrupt
  or torn date-time pair into an internal error instead of a client-facing error that blames the caller's bounds.
- **`recordCount()` has no production caller.** Its only caller, `InvertedIndex#getLength()`, is unreferenced in
  main code; the statistics take the same forward walk through `cursor()` directly. It stays as a test and sweep
  entry point onto that walk.

## Verification

- **Every removed guard was pinned, and nothing else depended on it.** With the production guards removed and the
  tests unchanged, the five tree test classes ran 699 tests with 8 failures and 36 errors. 43 of the 44 were the
  tests pinning removed guards: 36 were deleted (`UnorderedLookupTreeTest` 8, `TransactionalLongBPlusTreeTest` 8,
  `TransactionalIntToLongBPlusTreeTest` 8, `TransactionalElementBPlusTreeTest` 7, `TransactionalBucketBPlusTreeTest`
  5), and 7 kept their forward-walk and heap-walk assertions with the lookup, keyed-walk and reverse-walk halves
  cut. The 44th was the int-to-long consistency report above.
- **The narrowed tests still pin each kept guard on its own.** Each of three counterfactuals failed the narrowed
  `TransactionalBucketBPlusTreeTest.UnpublishedChildSlot`: dropping the forward walk's unpublished-slot skip failed
  the root-above-internal-nodes case (`Internal node expected!`); dropping the heap walk's `null` skip failed all
  three (`NullPointerException` on `child`); dropping the forward cursor's non-leaf-bottom check failed the two
  cases torn directly above the leaves.
- **Functional suite:** every test class under `io/evitadb/index/**` plus `ManagementReadsTest`,
  `CheapScalarStatisticsTest`, `IndexUsageStatisticsTest`, `CatalogIndexProjectionTest`, `IndexDetailTest`,
  `CatalogIndexConcurrentWriteTest`, `IndexBrowseTest`, `IndexCardinalityConcurrentWriteTest` and
  `IndexBrowseProjectionTest`: **5,371 tests, 0 failures, 0 errors, 5 skipped**, on two runs.
- **Long-running sweep:** all three sweeps green on two runs, in 8.6 s and 5.8 s. With `observableInternalPeek`
  reduced to `return peek`, the narrowed multi-level sweep failed 3 runs of 3, at rounds 13, 737 and 38 of 5,000, in
  0.10-1.11 s, every time from `ForwardBucketCursor#moveToNextLeaf` - on a 24-core x86_64 box under a load average
  of about 15.
- **Re-verified after merging #1738**, whose registry count is the forward-walk reader above. The full functional
  suite ran 29,291 tests with one failure and one error, both environmental: `EvitaServerTest`'s protocol
  restriction timed out in the sandbox, and `ExportS3ServiceTest` found no Docker. The long-running sweep was
  green in 7.2 s. The whole reactor compiled except `evita_performance_tests`, which the offline build could not
  resolve. Its benchmarks reach no package-private code of the trees, and the change removes no public member.

## Consequences & open follow-ups

- **A torn state reaching a lookup now fails loudly - or not at all.** On an object-keyed search it throws; on a
  primitive column it reads the unpublished slot as `0` and answers wrongly. Both are acceptable only because no
  such reader exists; that is the trigger stated under *Decision*.
- **Two management heap-walk sites read a nullable field twice, and a warm-up flush or mutation can null it in
  between.** `UnorderedLookupTree#getHeapSizeInBytes` reads `memoizedArray` twice, and
  `ChainIndex#getHeapSizeInBytes` reads `chainIndexChanges` twice. Either can throw a `NullPointerException` out of
  an index-detail call during warm-up. Both were found while taking this inventory and fixed in passing with a
  read-once local, like the two the content-sized record made. Neither has a test: no deterministic test can land
  a write between two adjacent loads.
- **Not examined: the bitmaps under a management walk.** `FilterIndex#size()` copies every multi-record bucket's
  record set into a transient bitmap while a warm-up writer may be adding to it. The roaring backbone's heap walk
  was made tolerant by the content-sized record; its iteration was not looked at here.

## Related work

- `2026-09-03-content-sized-value-tree-columns` - built the guard family and the sweep. Its session-free bullets
  carry an inline note pointing here; its main decision, content-sized columns, is untouched.
- `2026-09-04-long-keyed-tree-content-sizing` - added the sibling trees' leaf bounds and had already corrected its
  "query thread" reader to the heap walk; its leaf bounds now survive only on that walk.
- `2026-08-10-catalog-and-collection-statistics` - the management surface whose reads this record names as the
  only session-free ones, and the origin of `ManagementReads`, which holds the same contract for the index maps.

## Timeline

- **2026-09-02 - 2026-09-28** — the reader-side guard family built, escape by escape (see the two records above)
- **2026-10-19** — the weekly sweep's AArch64-only `midVal is null` escape; the contract narrowed to the management
  reads, the lookup-only guards and their tests removed, the sweep narrowed and re-calibrated
- **2026-10-20** — merged with #1738, whose registry count reads `long` keys through the kept forward walk;
  re-verified on the merged tree
