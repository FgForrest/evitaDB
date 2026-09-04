---
title: Size the value tree's leaf columns to their live content instead of adding a second array-backed representation
date: 2026-09-03
updated: 2026-09-04 18:40
status: accepted
kind: optimization
issues: [1486]
prs: []
areas: [evita_engine/index/bPlusTree, evita_engine/index/invertedIndex, evita_engine/core/session, evita_common/dataType, evita_test/evita_performance_tests/spike/trigram]
supersedes: []
superseded-by: []
relates: [2026-09-04-long-keyed-tree-content-sizing, 2026-08-01-bplustree-cursor-free-insert-path, 2026-07-10-more-optimized-data-structures, 2026-08-31-front-coded-column-stores-wtf8, 2026-08-31-trigram-query-path-optimization, 2026-08-10-stored-value-normalization-split, 2026-07-18-paged-index-corruption-and-flush-failure-boundary, 2026-09-04-millisecond-temporal-precision]
---

# Size the value tree's leaf columns to their live content instead of adding a second representation

A value tree holding one key cost 3472 bytes, and 3056 of those were empty array slots: every leaf
column was allocated at the leaf block size of 256 whatever it held. The issue proposed a second,
array-backed container selected per index by a cardinality rule. Instead the tree stays the only
representation and every column gains the split `FrontCodedStringColumn` already had — a **logical**
`capacity()` that keeps reporting the block size the split logic reads, and a **physical** backing array
that follows the live entry count, grown geometrically on insert and trimmed when a committed leaf is
rebuilt at the commit merge. Two further changes on the same lever followed once the columns were
content-sized: range keys moved from boxed objects into parallel primitive bound arrays, and the tree's
bucket count moved off a boxed transactional holder onto the tree's own diff layer. The same one-key
tree now measures **408 bytes**.

## Why

The reduced attribute indexes of a production e-commerce catalog carry ~1.55 GB of heap, and the census
that measured it charges most of that to slack rather than to payload. For a one-key integral inverted
index the decomposition is 3472 B total: 176 B of index shell plus the one live entry, **3056 B of
column capacity slack**, and 240 B of object scaffolding — the tree, its two transactional references,
the boxed bucket count and the leaf. The slack is 92.7 % of everything a second representation could
remove. Three allocations make it up: a 256-slot key column, a 256-slot `int[]` record column (1056 B,
charged on every leaf even when every bucket is a multi-record bitmap whose record slot is never read),
and — as soon as any bucket holds two records — a 256-slot `TransactionalBitmap[]` overflow array (1040
B). An internal node adds a further 1104 B of the same shape. Of the 240 B of scaffolding, 56 B turned
out to be reachable without a second representation as well: one of the two transactional references
and the box behind it (see *Decision*).

The constraint that made the choice non-obvious is that `capacity()` is not an accounting figure: the
leaf's `isFull()`, `isNearlyFull()` and the split's copy range all read it, and the tree's persisted
shape switches from inline to paged the moment a root splits. Shrinking the arrays without keeping the
logical figure intact would turn a memory optimization into a storage-format change.

The prize also lands wider than the census prices it: four structures build the same 256-slot shape
through the same constructor — the filter index, the owner-unique index, the global-unique index and the
reference-type cardinality index — and the census walks only the first. An empty, never-written tree
paid the same 3472 B and now pays for its structure alone.

### Previous state

Every `ValueColumn` and `RecordColumn` allocated its backing array at the block size and returned
`array.length` from `capacity()`. `getHeapSizeInBytes()`'s javadoc stated the figure "does not move as
keys are inserted" and named `FrontCodedStringColumn` as "the one exception"; `ColumnHeapSizeTest`
pinned the same rule as "Capacity, not cardinality". Inserts shifted the tail out to the physical array
end, 256 slots regardless of live count, and `duplicate()`, on the first write to a leaf inside a
transaction, copied 256 slots to protect four.

A filter index over a `Range` attribute kept its keys in the universal boxed column: every bucket key
held a live `DateTimeRange` or `NumberRange` instance, and for the temporal one the whole
`OffsetDateTime` graph behind it, when the tree only ever reads the two comparison longs each range type
already exposes. And `TransactionalBucketBPlusTree` kept its bucket count in a
`TransactionalReference<Integer>` — a holder wrapping an `AtomicReference` wrapping a boxed `Integer`,
three objects for a number that fits in four bytes — while `BucketBPlusTree` declared
`TransactionalLayerProducer<Void, …>` and returned `null` from `createLayer()`, so the tree never
registered a diff layer of its own.

## Options considered

### Option A — a second, array-backed container selected by cardinality (declined)

The issue's design: one small object holding parallel exact-length key, posting and optional id arrays,
chosen per index by a cardinality rule, with promotion and demotion at a safe boundary.

- **Pros:** removes the object scaffolding as well as the slack, reaching ~168 B for a one-key integral
  index. Its read path measures better everywhere, and its posting shape — an inlined `int` with a
  lazily allocated reference array — is strictly better than the leaf's.
- **Cons:** the surface is about thirty `buckets.*` entry points the inverted index delegates to, the
  whole nine-method `BucketCursor` contract, a leaf-version token equivalent that the formula cache keys
  staleness on (a container has no leaves, so the token collapses to the whole index and every write
  invalidates every cached range of that attribute — a behaviour no test asserts today), the value-id
  directory that packs `leafId << 32 | slot`, the paged-leaf handles, the reclaim obligation when an
  index collapses back to inline, and a `Snapshotable` transactional layer `InvertedIndex` does not
  have. It reintroduces the bug class where a structure's representation stops being a function of its
  cardinality, which the trigram postings hybrid already paid for with a `ClassCastException` on a plain
  query — and two identity assumptions elsewhere rest on today's shape: an inverted index compared by
  reference in `AttributeIndex`, and a memoized bitmap charged to heap by `FilterIndex` only when a tree
  has more than one bucket.
- **Rejected because:** the prize no longer pays for that surface. Content sizing took **86 %** of the
  same lever's gross prize before Option A was priced at all (removable bytes 1.73 GB → 303.9 MB), and
  the census then measured Option A's whole residual at **225.3 MB, 7.7 % of the reduced attribute heap,
  ≈ 260 B per tree** over 862,478 trees. *Revisit if* a workload shows that residual above ~20 % of
  reduced attribute heap, or if a container becomes necessary for an unrelated reason.

### Option B — content-sized columns behind a logical capacity (chosen)

- **Pros:** recovers ~91.5 % of the same bytes. Changes no API outside `io.evitadb.index.bPlusTree`,
  where `capacity()` has no caller; nothing downstream moves — no cursor, no leaf version token, no
  value-id directory, no formula-cache key, no persistence, no paging predicate. One representation
  means no hysteresis and no representation-versus-cardinality bug class. It makes writes cheaper as
  well as smaller, the insert shift and the decouple copy becoming O(live count) instead of O(256). And
  the pattern was already implemented, reviewed and tested here on the front-coded string column.
- **Cons:** every column gains a size field to maintain across seven operations; the heap estimator's
  stated contract inverts; and a grow allocates on the insert path, which is the one path the
  cursor-free insert design says must not gain allocations.

### Option C — ship B, then price A against measurements (the path taken)

- **Rejected because (doing A first):** it front-loads the largest and riskiest change to buy the last 9
  %, and forecloses the cheap measurement that would say whether the 9 % was worth it.

### Rejected outright

| Option | Rejected because | Revisit if |
|--------|------------------|------------|
| Trim inside `duplicate()` | It is both the transactional decouple primitive and the savepoint memento primitive; a trimmed memento would change a leaf's physical shape as a side effect of a rollback | Never — the memento must be a faithful pre-image |
| Trim on the commit merge's `return this` fast path | Every commit would rebuild every leaf and dirty every page, destroying the boundary-stable leaf paging the 2026-07-10 campaign was built for | Never |
| Two private helpers on the leaf instead of an owning `OverflowColumn` type | Sixteen raw-array touch points in a 6,000-line region; the first written inventory got two of the three hand-written sizing sites wrong, prescribing the live count where the post-rebalance count was needed | Never |
| A compile-time `static final` switch folding the column-alignment assert away | Leaves the invariant unchecked in every production run and re-enabled only by a rebuild. Keeping the assert on structural paths costs per leaf rather than per insert, which is where the risk it guards actually lives | Never |
| `VarHandle` release/acquire, or an immutable `(array, size)` holder, for the torn pair | Changes the field layout of the hottest write paths to close a window that is warm-up-only and management-API-only. The reader-side bound closes it at zero write-path cost | The window widens past warm-up, or a weak-memory-model measurement shows the reader-side bound is not enough |
| Three wider fixes for the single-session admission: promoting it to the registration gate's write lock, a CAS reservation flag, or taking the admission lock in `removeSession` too | The write lock serialises every session creation including the ALIVE path and contends with the suspension barrier holding the same lock; the reservation must be released on every failure path between reserve and register, more failure surface than the rule it protects; and the third serialises every ALIVE session close to fix a refusal-message race a weakly consistent iterator fixes for free | Never |
| Raising the columns' minimum physical length, or allocating split-born halves at the block size, to close the transactional first-touch cost | Both give back resident bytes on every tree to save transient bytes on the third of leaves that happen to be exactly full | Never — the headroom decouple pays only on the leaf that is about to grow |
| Lowering `ColumnSizing.MIN_PHYSICAL_LENGTH` below 4 | It is not in the measured residual at all — `bulkLoad` sizes a loaded column exactly at `count`, so a catalog loaded from disk pays no floor — and it would add a reallocation to the majority of trees on the write path, where the cursor benchmark already measures +16 % allocation on random inserts. The floor exists only for trees mutated in a live session, and there it earns its keep | Never on this workload |
| Rebuild `DateTimeRange` closed at UTC from two longs | The open-bound sentinel is derived from the *other* bound's zone offset, so this throws `DateTimeException` for a positive-offset open-from and a negative-offset open-to | Never — it is wrong, not merely lossy |
| Carry only the surviving bound's offset in an `int[]` | Range consolidation clones an open range against a closed one's bound, so the offsets of **closed** bounds are load-bearing too. A both-closed range rebuilt at UTC consolidates to a threshold the range index never had, and the removal path then asks it to drop one it never added: silent corruption, no exception | Never |
| Store nanos and both offsets so the persisted bytes stay identical | +16 B per key to preserve an arbitrary representative the tree already discards, since comparator-equal keys are deduplicated and only the first-inserted instance survives | Never |
| Add a `DateTimeRange` internal-build factory for symmetry with the numeric ranges | The public `between` / `since` / `until` factories reproduce both comparison longs by construction, and consolidation re-derives them from the precise bounds regardless, so a new factory on a public data type would buy only a skipped recomputation | A faithful reconstruction ever cannot be expressed through the public factories — then add the factory openly |
| Set the private range bounds reflectively | Sits outside the data type's own invariants, breaks under any field rename, and needs `--add-opens` in every process that loads a catalog | Never |
| The dictionary lever — replace front-coded string keys with 4-byte ids against a shared dictionary | Re-measured against the shipped tree-shaped spine at **+103.3 MB, 3.5 % of the post-change reduced attribute heap**, under the 5 % refuse gate agreed in advance, and 33 domains of 33 need the expensive ordered-dictionary-plus-membership shape rather than the cheap id-keyed one | A corpus where the tree-shaped marginal clears 5 % in domains that take the simple shape |
| A range dictionary | 0.79 % marginal over the pair-column container even at a replication factor of 82,521; once the scaffolding is gone a 16-byte key has nothing left to give up to a 4-byte id | New evidence |
| Raise the paging boundary above 256 | At or below 256 buckets the whole index persists inline on the storage part root; above it a container must either force itself inline, losing granular per-leaf writes, or synthesize page boundaries stable across restarts | Never on this workload; the read benchmark put the knee at 256 |
| Fold issue #1455's small sorted-array bucket tier into this work | It changes what a bucket's payload *is*, touching the cursor contract and the leaf page serializer's per-bucket kind byte — a wider blast radius than this change has | Now enabled and handed over: the census histogram supplies the count its prize was missing, and `OverflowColumn` is the seam it should use |

## Decision

**Chosen: Option B, on the path of Option C.** The decision rests on where the bytes are, not on which
design is more elegant: 92.7 % of what any redesign could reclaim is column slack, and content sizing
takes it without touching a single contract outside one package. Option A's remaining advantage was
estimated at 230–290 B per tree — not flat, but rising with how many arrays a key family uses — and the
census re-run then measured it at 225.3 MB. That is bought with a second representation, a bug class the
repository has already been bitten by, thirty entry points to re-implement, and a promotion boundary
that would have to be built first.

The measured residual came out in line with that arithmetic, so the fork stands declined rather than
reopened. If Option A is ever revived, the issue's own first phase — a mutable container prototype
benchmarked against the live tree to yield the crossover cardinality per key type — becomes its first
prerequisite. That work was **dropped rather than deferred**: with one representation there is no
switch, no threshold to measure, and nothing for the issue's own gate to gate.

**One allocation cost had to be bought back before the choice held.** Content sizing was expected to
lower allocation everywhere, and on the transactional path it first did the opposite: a committed leaf
whose columns are exactly full — every split-born half, and after a restart every bulk-loaded page — was
duplicated for the transactional layer at its short length and then grown by the very next insert. Two
allocations where block-sized arrays paid one, **+489 B per live insert, +44 %**. The answer is to
decouple with headroom. Every column gained a second duplication method that copies straight into the
grown length when the live run already fills the array, and the leaf's layer decouple uses it only when
a genuinely new bucket is about to be inserted; the two record-adding methods therefore search the key
column before decoupling and pass the answer down, so a record joining an existing bucket decouples
verbatim and a small tree never keeps headroom it did not use. Deletes, steals and merges decouple
verbatim, and the savepoint memento keeps the verbatim-length contract it depends on. That returned the
cost to **+0.4 % at block 256**; the two cheaper-looking fixes are in the table above.

**Range keys became primitive bound columns on the same lever.** Once a column's cost is its content,
the boxed key column's content is the expensive part: a `Range` key is two comparison longs the type
already exposes, wrapped in an object graph nothing reads. `RangeValueColumn` stores those longs in
parallel `long[]` arrays and rebuilds the key on read through the public factories only. This was drawn
up as a follow-up and shipped inside the same line of work, because the census had already priced it at
+242 MB across 87,099 range trees in five domains — the single largest remaining slice of the projection.

**Option A declined, and one carve-out taken from its residual.** The residual is 225.3 MB against the
surface listed under Option A above, and it does not pay for it. But 56 B per tree of that residual is
reachable without any second representation, and there the plan's own proposal lost to a better one:

- **Declined variant — a `TransactionalInt`-style holder** modelled on `TransactionalBoolean`: one
  24-byte object per tree replacing three. Accounted, it saves 32 B/tree (~27 MB); resident, only about
  16 B/tree (~14 MB), because a bucket count below 128 boxes to the JVM's shared `Integer` cache and the
  old boxed word was largely notional. **Rejected because** it keeps a per-tree object alive to hold
  four bytes that the tree can hold itself.
- **Chosen — the tree owns its own diff layer.** `BucketBPlusTree` had declared
  `TransactionalLayerProducer<Void, …>` with `createLayer()` returning `null`, so the tree never
  registered a layer; it now returns a `BucketCountChanges` and the count is a plain `volatile int`
  field on the tree. **Zero** per-tree objects: 56 B/tree accounted (**~48 MB** on the census corpus),
  ~40 B/tree resident (**~34 MB**).

Three corrections to the issue's premises drove the shape of all of this, and none of them is visible in
the code:

1. **Generation compaction does not exist.** It is named in two places, both saying it is unimplemented;
   there is no method, class, scheduler or call site. The real boundary is the inverted index's commit
   merge, where the merged index is provably unreachable until the method returns.
2. **The warm-up bulk-load path has no boundary at all.** It mutates the published index in place,
   outside any transaction.
3. **There is no per-value bucket wrapper left to remove.** The bucket views are transient flyweights
   materialized on demand and the 2026-07 flyweight work already banked that saving; the 256-slot record
   column belongs on that cost list instead, and the issue omits it.

One note for anyone re-reading the earlier spike: its finding that a per-tree value tree measures
3.2×–10.2× an exact-array spine retires *a second, smaller tree instance* — its own tree object,
transactional references, boxed size, leaf and columns — and does not price exact-sizing the tree every
index already pays for. A large ratio over a ~168 B base is a small absolute number, the same 230–290 B
this record prices, measured a second way.

## Key technical details

- **Entry points:** `ColumnSizing` (package-private) holds all the arithmetic — a floor of 4 slots on
  first allocation, doubling, a jump straight to the logical capacity once past half, a trim at a 4:1
  slack ratio down to `max(4, nextPowerOfTwo(size))`, and the headroom length the insert decouple uses.
  Nothing else may re-derive it. `trimmed()` is called only from
  `createCopyWithMergedTransactionalMemory`, on its three leaf-building branches, never on `return
  this`.
- **`capacity()` is logical and must never return the physical length.** Two failure modes follow from
  getting this wrong, the second worse: a five-value tree would split, gain an internal root and start
  persisting leaf pages; and the split's copy end would collapse to the midpoint, so the right leaf
  would copy an empty range and half the leaf would vanish silently. Pinned by
  `shouldNotSplitALeafWhoseBackingArraysAreShorterThanTheBlockSize` and
  `shouldKeepEveryBucketOfBothHalvesWhenALeafSplits`.
- **The load-bearing invariant is `column.size() == leaf.peek + 1`.** It makes a size-authoritative
  `fillEmpty` safe on a *committed* column, because layer creation passes the leaf's own columns as both
  origin and target. It does not hold in two transient windows inside one mutation, both named in the
  column contract: the bucket inserts grow the columns before incrementing `peek`, and the delete
  removes from the columns before decrementing it.
- **The alignment assert deliberately does not run per insert.** It sits on every structural path — both
  arms of `setPeek`, the four rebalances, the split-copy constructor, `restore`, and the private leaf
  constructor the bulk page load and the three merge branches use. It was taken off the per-insert and
  per-delete exits because `ValueColumn.size()` has five permitted implementations, and a megamorphic
  call inside the two hottest insert methods is verbatim the escape-analysis failure mode the
  cursor-free insert record names: a per-insert allocation with no test failing. Tests pin it instead.
- **The insert decouple reads the key column before it decouples.** That reorder is what lets a record
  joining an existing bucket decouple verbatim while a genuinely new bucket gets headroom. It is safe
  because the layer's key column aliases the committed one until the first decouple and is the layer's
  own copy afterwards, and the search result is read straight out into primitives, so no insertion
  position outlives the call — the lifetime hazard the cursor-free insert record names.
- **Every column exposes `observableLiveRun()`, the smaller of its size and its array length**, and the
  three bucket cursors take it once per leaf load for all four columns. A grow stores the longer array
  first and raises the size last, as two plain writes, so a session-free management read racing an
  in-place warm-up write could otherwise see the new size against the old array. Nothing ever shortens
  an array in place — the trim allocates a new one, on a leaf being rebuilt anyway — which is why a
  bound taken once stays valid. It costs nothing on the write path.
- **Two column details look like oversights and are not.** `OverflowColumn.duplicate()` is a shallow
  clone because each `TransactionalBitmap` carries its own transactional layer, and since a `null` slot
  *is* that column's single-versus-multi discriminator it refuses an over-long source range that would
  donate a multi-record bucket as a single one. `BoxedObjectColumn` keeps its own zero-length typed
  array, 16 B, because the array is assigned to a typed local that `Object[0]` would not narrow to.
- **Exactness in the bulk page load is a correctness matter, not an accounting one.** A value-id column
  attached shorter than the leaf's live run reaches layer creation, where the split-copy constructor's
  self-copy would reallocate and re-size a **committed** column that other holders alias.
- **Content sizing lowers allocation on the transactional path and raises it slightly on the warm-up
  one.** The decouple copy that ran on every first write per transaction copied 256 slots per column and
  now copies the live count. Against that, a leaf built in place by random-order warm-up inserts pays
  the amortised 4→8→…→256 growth, **+17 B per insert at block 256, +16 %** — bounded at six
  reallocations per leaf, and accepted as the price of the resident prize. A leaf loaded from disk is
  sized exactly at once.

### The range column

- **`RangeValueColumn` + `RangeKind`.** Two parallel `long[]` arrays hold the comparison bounds. For
  `DateTimeRange` only, a third `long[] meta` packs **both** bound zone offsets — from-offset in the
  high half, to-offset in the low, `Integer.MIN_VALUE` marking an open side. Both offsets are required
  because `Range.consolidateRange` derives an open bound's sentinel from the *surviving* bound's offset;
  the two rejected narrower encodings are in the table above, and one of them corrupts silently. The
  five numeric kinds park `meta` on the shared empty array and cost two arrays.
- **Selection is by exact class equality against the six concrete range types, through
  `ValueColumnFactory.forFilterKey`.** It is a second entry point rather than a widened `forKey` on
  purpose: rebuilding a `BigDecimalNumberRange` needs the index's `indexedDecimalPlaces`, and two of
  `forKey`'s callers — `UniqueIndexBPlusTreeSupport.buildTree` and `ReferenceTypeCardinalityIndex` —
  have no scale to give. A `Range` attribute declared `unique` would otherwise rebuild every key at
  whatever default the parameter carried, at the wrong scale and with no error anywhere. Keeping the
  branch out of `forKey` makes that impossible by construction rather than by convention.
- **One data-type change rode along:** `DateTimeRange.assertFromLesserThanTo` now compares **instants**
  instead of using offset-sensitive `equals` plus nano-sensitive `isBefore`. It was a latent bug the
  column merely made reachable — a zero-width range whose two bounds name the same moment at two
  different offsets got in through the nanoseconds and could not be rebuilt once the column truncated
  them away. The type's `equals`, `hashCode` and `compareTo` already derive from the two epoch-second
  longs alone, so the assertion had been stricter than the type's own notion of equality. Reversed
  bounds are still rejected.
- **The persisted bytes of a range key change although the format does not**, because the rebuilt key is
  a faithful comparator-equal reconstruction rather than the original instance. Verify by
  persist–reload–persist, never by byte comparison. Reconstructed bounds also lose sub-second precision,
  the comparison long being whole seconds, and separator ownership flips to true — a range tree is the
  one shape whose separators are freshly minted objects rather than aliases of a leaf key.

### The bucket count on the tree's own diff layer

- **`BucketCountChanges` is the tree's transactional layer**; the count itself is a plain `volatile int`
  field on `TransactionalBucketBPlusTree`. The sweep paths needed no new wiring — `copyWithOwnLayer`
  already passes whatever layer exists and `removeLayer` already called
  `removeTransactionalMemoryLayerIfExists(this)`, a line that was dead under the `Void` layer and is
  load-bearing now.
- **`volatile` is required, not decorative.** The statistics and management walks reach `bucketCount()`
  from a request thread with no transaction bound, concurrently with a warm-up bulk load mutating the
  tree in place, and the `AtomicReference` being removed is what gave those readers their visibility.
  `TransactionalBoolean`'s plain field is not a precedent to copy — nothing reads it off-thread. The
  un-transacted write branch stays single-writer by contract, exactly as the `AtomicReference.set(get()
  + 1)` it replaces was.
- **`getHeapSizeInBytes` stopped over-charging.** Its old comment justified charging the boxed `Integer`
  unconditionally because the sharing threshold moves with `-XX:AutoBoxCacheMax`. There is no box now,
  so the estimate is exact by construction. One over-report term elsewhere fell out with it:
  `LeafIndexHeapSizeTest` pinned an empty owner sort index as exceeding JOL by *two* boxed zeroes, three
  structures sharing one cached `Integer`; the bucket tree is no longer one of them, so it is one box.
- **Both decrement paths refuse to go below zero** — the layer's and the tree's un-transacted branch. The
  un-transacted one is guarded too because it is the `WARM_UP` bulk-load path and therefore the *common*
  one; guarding only the transactional arm would have guarded the rarer of the two. A drifted count
  surfaces far from its cause: as a wrong `FilterIndex#getDistinctValueCount`, and as an
  `estimatedPathLength()` that collapses through `NaN` to a silent `2`.

### Why tests here must read the representation back

In this column family, equality derives from the comparison longs alone, so **equality-based assertions
cannot see representation-level loss**. `meta` (the zone offsets) and `indexedDecimalPlaces` (the scale)
were each invisible to the entire suite until tests were written that read the representation back: a
decoder hard-coding scale 0 passed everything, and `meta` handling could be deleted from six mutators
with the suite still green. Two findings from this branch say what that costs in practice, and both
constrain how anyone tests this family in future:

1. **A comment asserting an invariant is not evidence of it.** A high-severity regression — `keyAt`
   mapping each `LongNumberRange` sentinel to a `null` bound independently, so the saturated `(MIN,
   MAX)` pair rebuilt with *both* precise bounds null and `Range.consolidateRange` threw — survived an
   adversarial review **and** an independent review, because the comment above the kind claimed the
   substitution was "invisible to … consolidation alike". Consolidation was precisely where it was
   visible. It was caught by running the boxed column as a counterfactual through the real
   `OwnerFilterIndex`, not by reading.
2. **A counterfactual proves the assertion you broke, and nothing else.** Two of the bucket-count MVCC
   tests passed with the mechanism broken *and* survived three probes, because a net-zero mutation (`+1`
   then `−1`) makes the in-transaction count and the committed count the same number. Each probe broke a
   different half of the mechanism than the inert assertions covered.

The working rule that follows: **a new invariant in this family needs a representation-reading assertion
whose expected value differs from what a broken implementation would produce** — not a comment, and not
only a probe.

### Two defects fixed in passing

Both under the same issue rather than filed separately. The inverted index's class javadoc attributed a
multi-reader race to the warm-up window when it belongs to the first reads after a catalog goes live, on
an index carried across by reference. And the single-session admission for non-live catalogs was a
check-then-act under the registration gate's *read* lock, which admits registrations concurrently, so
two threads could both find the session map empty and both be admitted; a dedicated lock, taken only on
the non-transactional path and shared by reference into derived registries, now wraps the emptiness
check together with the registration. That matters here because the argument that no query thread races
the warm-up writer leans on the rule it enforces.

## Verification

**Heap gates**, deterministic and asserted both ways: each gate keeps an `assertEquals(measured,
index.getHeapSizeInBytes())` beneath the budget, so JOL and the engine's own estimate must agree exactly
and a regression cannot hide inside the ceiling.

| Fixture | Before | Now | Budget |
|---|--:|--:|--:|
| one-key integral inverted index | 3472 B | **408 B** | 424 B |
| one-key `DateTimeRange` index | — | **512 B** | 528 B |
| one-key numeric-range index | — | **464 B** | 528 B |
| the bucket tree's own object | 80 B | 80 B | — |

The tree object is unchanged because a reference slot was traded for an `int`. Of the 408 B, 80 B are
the two four-slot backing arrays and the rest is index, tree, leaf and transactional-reference structure;
the byte-by-byte composition sits in a comment at each assertion. Gates:
`LeafIndexHeapSizeTest#shouldKeepAOneKeyIntegralIndexWithinItsSizingBudget`,
`#shouldKeepAOneKeyRangeIndexWithinItsSizingBudget`, and `BucketBPlusTreeHeapSizeTest`. The Stage 4 diff
layer took 56 B out of every shape at once.

**Live heap on real corpora.** The census sums the engine's own heap estimates over the reduced indexes,
so its figures are deterministic and unaffected by machine load. JDK 17, `-Xmx24g`, one run per corpus
per side, the branch point as baseline.

| Production e-commerce catalog: 564,136 reduced indexes, 862,478 value trees, 8.09 M buckets | Before | After |
|---|--:|--:|
| Reduced attribute heap | 4.61 GB | **2.85 GB** |
| Reduced value trees within it | 1.83 GB | **587.2 MB** |
| What Option A would still buy on top | +1.66 GB | +225.3 MB |

The value trees gave back 1.24 GB, 68 % of themselves and 80 % of the 1.55 GB projected. **These figures
predate the range column and the diff layer**, both of which landed after the measurement window closed
and neither of which was re-measured — no further quiet window was available — so the shipped state is
below the 587.2 MB on record by the range column's projected ~242 MB and the diff layer's ~48 MB
accounted. The attribute heap fell further than the value trees alone because the same columns serve the
other bucket trees inside those indexes. On the demo dataset the reduced attribute heap fell from 164.3
MB to 109.8 MB and its value trees from 57.7 MB to 15.5 MB; a second production catalog has no
filterable attribute value trees at all and contributes nothing either side.

**Allocation on the write path**, from the benchmark that guards the cursor-free insert chain's escape
analysis. JDK 17.0.20, one fork, five one-second measurement iterations, `-Xmx8g`, allocation profiler
on, 24-core x86_64, quiet box; the baseline reproduced the 2026-08-01 record's figures to the decimal,
which is what makes the runs comparable. Bytes per insert:

| Arm | Baseline | Content-sized | With headroom decouple |
|---|--:|--:|--:|
| Warm-up bucket insert, block 256, ascending keys | 124.0 | 124.5 | 124.5 |
| Warm-up bucket insert, block 256, random keys | 108.1 | 125.2 | 125.2 |
| Live bucket insert, block 256 | 1106.9 | 1596.0 | **1111.8** |
| Live bucket insert, block 64 | 884.9 | 1291.2 | **929.9** |
| Control, an insert path this change does not touch, block 256, random keys | 75.3 | 75.3 | — |

The mitigated jar re-runs at **+0.4 %** against baseline on ALIVE first touch at block 256. The control
moved at most 0.7 % and the ascending bucket arm 0.5 B, so nothing that used to be scalar-replaced
escaped; a lost `BoundaryContext` would have shown as about 192 B on every arm. Descent arms sit at the
profiler's floor on both sides. Latency at one fork is indicative only, and in the first run it was
noise — untouched trees swung between −60 % and +92 % on identical allocation. On the quiet re-run every
bucket arm beat the baseline, by 41 % ascending, 14 % random and 7 % live at block 256.

**Read path** unchanged, as expected, the key search being the same code over a shorter array: point
lookup and range scan at 1,000,000 distinct values and block 256 moved between −0.9 % and +7.1 %, every
delta inside its own error bar, and that run was taken under foreign load with error bars reaching the
whole score. The quiet-box figures on record are 0.539 µs and 28.66 µs.

**Tests.** The full functional suite on the branch head reports **22,569 tests, 0 failures, 1 error, 39
skipped**. The single error is the Docker-dependent `ExportS3ServiceTest`, environmental and present on
every run in this environment. The run uses the opt-in `largeMachine` profile (`mvn test -P
unitAndFunctional,largeMachine`) at a 12 GB test fork; the **default deliberately stays 8 GB**, because
on the GitHub-hosted runners — two cores, little memory, high test concurrency — a larger heap is
actively harmful and a prior `-Xmx12g` attempt got the whole build step OS-killed. That profile is its
own commit and its pom comment records the episode.

Three long-running guards carry their calibration in their javadoc:

- Session admission: with the lock removed, two barrier-released threads were both admitted **in the
  first round on five runs out of five**; with it in place all 50,000 rounds pass, in 4443 / 2909 / 2808
  ms over three runs.
- The concurrent session-free walk under a warm-up load: the counterfactual **did not fail**, and the
  reason is reported rather than papered over. On x86_64 total store order forbids exactly the
  reordering the reader-side bound guards, so 500,000 rounds, ten times the green side, produced no
  escape on OpenJDK 17.0.20 (37.9 s) or 21.0.12 (20.5 s); the bound's own arithmetic is pinned
  deterministically in the fast loop instead. Green side: 50,000 rounds in 2.4 s idle, 3.4 s loaded.
- The value-id directory's concurrency test, re-calibrated because a shorter insert path could narrow
  the race window it depends on: green 5 of 5 in 2.8–4.1 s, while the counterfactual — the index
  compiled without the synchronisation, shadowed onto the test classpath — fails 3 of 3 in under a
  second, a round-attributing driver failing 23 of 23 at latest round 16 of 2000 against 267–450
  recorded. About 26× sharper than recorded, so the round budget stays; why the window widened is not
  isolated, and the javadoc says that rather than guessing.

Read the census figures correctly: `removable` is `treeBytes − bitmapBytes` and `treeBytes` is what this
change reduces, so `removable` **shrinks** by design — the criterion is absolute live heap against the
pre-change baseline, never a percentage of a moving denominator. Two distortions survive in the absolute
figure: the engine's self-report under-count (Boolean −1.76 %, `DateTimeRange` −3.08 %) grows
proportionally larger against a smaller total, and the census charged the temporal shape as one
12-byte-stride array rather than the engine's two parallel arrays, a 0.336 % optimistic bias.

## Consequences & open follow-ups

- **Option A's residual is priced and the fork is closed, but not empty.** The census puts it at
  **225.3 MB, 7.7 % of the reduced attribute heap, about 260 B per tree** against the post-Stage-1
  state. Of that, the 56 B per tree behind the boxed bucket count has since been taken without a second
  representation (~48 MB accounted, ~34 MB resident); the other cheap-looking slice — lowering the
  four-slot floor, once estimated at ~25 MB — was measured out of existence and refused, see the table.
  The remainder needs the container, with everything Option A's cons carry, and reopens only on the
  trigger stated there.
- **Issue #1455 is adjacent and additive, not competing, and its prize is now counted.** This work
  removes slot slack and excludes record bitmaps from its ledger; #1455 shrinks the bitmaps themselves.
  Of the production catalog's 8,088,957 buckets, 95.1 % hold one record; 274,755 hold 2–8 at 67.8 MB of
  Roaring, 258 B each; 102,596 hold 9–32 at 40.4 MB, 413 B each; 12,353 hold 33–128 (6.8 MB) and 5,909
  more than 128 (14.8 MB). The 377k buckets holding 2–32 records carry **108.2 MB** of Roaring overhead,
  and a small-array tier at T = 32 attacks **~90–100 MB** of it — four to five times what the whole of
  Option A's residual was worth, behind one type instead of thirty entry points. The histogram has been
  posted on the issue. Three of its open items are settled by the analysis here and should be cited
  rather than re-derived: the transactional discipline for immutable payloads, the consequence of
  promoting and demoting at different thresholds, and the commit merge as the safe point;
  `OverflowColumn` is the seam it should use, not new ad-hoc sites on the leaf.
- **The four sibling tree families keep their `Void` layer.** `TransactionalObjectBPlusTree`,
  `TransactionalLongBPlusTree`, `TransactionalIntToLongBPlusTree` and `TransactionalElementBPlusTree`
  still hold their bucket count in a `TransactionalReference<Integer>`. That is deliberate, not an
  oversight: they exist in the thousands rather than the hundreds of thousands, so the same change there
  buys tens of kilobytes.
  **Do not generalise this bullet past the bucket count.** It is a per-tree cost of ~56 B, and the
  arithmetic that makes it not worth doing does not carry to structures that cost kilobytes per tree.
  Applying it to *leaf sizing* would have been wrong by three orders of magnitude: content-sizing
  `TransactionalLongBPlusTree`'s leaves was measured at **≈49.5 MB on the demo dataset** and is recorded
  in `2026-09-04-long-keyed-tree-content-sizing`, which also declines the same change for
  `TransactionalElementBPlusTree` on measured occupancy.
- **The tree-side negative-count guard has no covering test.** It is unreachable through the public API
  — `removeRecord` for an absent key never touches the counter — and reaching it would need reflection
  or a test-only setter, both of which this project avoids. The identical invariant on the constructible
  arm is covered by `TransactionalBucketBPlusTreeTest.BucketCountChangesTest#shouldRefuseToCountBelowZero`.
  The same applies to the torn date-time read guarded at the head of `RangeValueColumn`'s decoder:
  recorded as fixed without coverage rather than claimed as covered.
- **The dictionary lever is refused, not deferred, and the numbers decide it.** It had been held on a
  +128.2 MB marginal computed with the container spine on *both* sides, which this change invalidated in
  both directions at once. Against the tree-shaped spine that actually ships it is worth **+103.3 MB,
  3.5 % of the post-change reduced attribute heap**, under the 5 % refuse gate — and all of it, 33
  domains of 33, sits in domains needing the ordered-dictionary-plus-membership shape rather than the
  cheap id-keyed one. Both refuse conditions hold; the container-shaped counterfactual the issue quoted
  (+213.7 MB, 7.3 %) overstated it by the container's own share. It would still cover the ~66 MB trigram
  opt-in it was meant to offset, but only through the expensive shape. Per-index usage statistics could
  not inform the split — never persisted, so a census boot reads zeros — so the schema served as a
  conservative proxy, treating every filterable string domain as prefix-capable.
- **The functional suite's fork heap is now a profile, and the default is deliberately low.** Three full
  runs of the branch exhausted the build's 8 GB fork three to four minutes in on a 24-core box, once
  from a quiet start, cascading into errors and once into a truncated run; the suite fans out one test
  class per core inside a single fork, so this is heap marginality on big hardware rather than a leak.
  `largeMachine` opts in at 12 GB. **Never raise the default** without reading the surefire comment
  first: on the CI runners the off-heap footprint is what runs out, and a bigger heap only removes the
  backpressure that used to surface it as a contained heap OOM.
- **The torn-pair counterfactual is unverifiable on x86.** The reader-side bound is sound by the
  argument above, but its stress test can never fail on a total-store-order machine. Re-measure the
  counterfactual on an AArch64 box before drawing any conclusion about the bound from an x86 run.
- **Session admission is atomic; the catalog-state snapshot is not.** A catalog transitioning to live
  concurrently with a session creation can still hand two callers different answers about whether it is
  transactional, because that value is read before the branch. That predates this work and is untouched.
- **The management API's leaf walks are advisory by contract.** The cursors clamp their bound by the
  column's observable live run, which turns a *permanent* misalignment into a quietly stale count rather
  than a failure. A leaf constructor assert catches the permanent case where it would be created.
- **A latent loader defect is recorded, not fixed.** The legacy Kryo path rebuilds an inverted index
  with no normalization and natural ordering, discarding the plain type, so a tree round-tripped through
  it lands on the boxed column whatever its type. Loaders go through the attribute index loader instead,
  so it is unreachable today — but a column selected off the plain type must know it exists.
- **The reader-side guard family was completed in a follow-up, and the gap was in the sweep rather than
  in the reasoning.** The first round bound seven cursor sites against the arrays they index; two more
  shapes survived it. `findLeafNode` — the allocation-free descent behind every point lookup — wrote
  `getChildren()[searchIndex(key)]`, and because Java evaluates the array expression *before* the index
  expression, that captures the pre-growth array and indexes it with an index computed from a freshly
  read `keys`/`peek` pair. Separately, all five `findKeyPosition` implementations took the caller's
  `peek + 1` as their bound while reading their own key array later, inside the call. Both are now bound
  to the array actually indexed. **What let the first round miss them is worth more than the fix:** the
  concurrency sweep's reader took the three cursor descents and the heap walk, and no reader in it ever
  called a point lookup — so the one descent that is not a cursor was never executed at all. A sweep
  proves nothing about an entry point it does not call, and "the tree is covered" is not the same claim
  as "every way into the tree is covered". The sweep now probes `contains`, `cardinalityOf`, `valueIdOf`,
  `getRecordsEqualTo` and `computePreviousRecord` on every pass.
- **Two `getChildren()[getPeek()]` pairs are deliberately left unguarded.** `predecessorLeaf` and
  `predecessorLeafOf` carry the same array-first shape, but both are reached only from the insert path's
  boundary asserts, which run with a happens-before edge to the writer. The clamp would be a provable
  no-op there, so decorating them would suggest a hazard that does not exist at those sites. The four
  reads in the consistency-check report are a different case and remain genuinely open: that report
  catches `IllegalStateException` only, so a concurrent warm-up writer can produce an escaped
  `ArrayIndexOutOfBoundsException` or a false BROKEN verdict.
- **`FrontCodedStringColumn`'s publish is still torn, and its bound guards only half of it.** Its search
  is now bounded by the observable live run like its siblings, but `restartOffsets`, `data`, `size` and
  `hasEncodedSurrogate` are four separate field stores with no combined publish, so a reader can still
  pair a new blob with a stale restart offset, or decode new WTF-8 through a stale
  `hasEncodedSurrogate == false` and silently mangle lone surrogates. That predates this work, belongs to
  `2026-08-31-front-coded-column-stores-wtf8`, and the comment at the search site says so explicitly so
  the partial guard cannot be read as a complete one.
- **The public go-live path does not drain incumbent warm-up sessions.** Adjacent to the session-admission
  note above and also untouched here. `Evita#makeCatalogAliveWithProgress` applies the mutation directly;
  `closeAllSessionsAndSuspend` has exactly one caller in the main tree, the session-driven
  `EvitaSession#goLiveAndCloseWithProgress`, and neither `MakeCatalogAliveMutationOperator` nor
  `Catalog#goLive()` fences a session. The operator does install an `UnusableCatalog(GOING_ALIVE)`
  synchronously, which refuses *new* sessions for the duration — so the exposure is narrower than it
  first looks — but a session opened *before* the transition holds the superseded `Catalog` instance and
  nothing drains it, so warm-up writes racing the `flush()`/`goLive()` pair can be lost. **Filed as
  issue #1495** rather than folded in here: it is session and catalog lifecycle rather than index work,
  and no commit on this branch touches it.
- **The site inventory needed a second sweep, and that is the process lesson.** One reading of a
  6,000-line region produced four classes of omission, two of which would have thrown on first
  execution: a missing column mutator with four call sites, ten unlisted raw overflow-array moves, a
  copy-site count understated by 14, and a prescription that would have thrown on the value-id back-fill
  path.

## Related work

- `2026-08-01-bplustree-cursor-free-insert-path` — same code area, and the source of the constraints
  that shaped the alignment assert and the insert decouple: its cursor-free insert chain depends on
  escape analysis, and its allocation benchmark is the only detector for a regression in it.
- `2026-07-10-more-optimized-data-structures` — built this tree family and its paged leaf storage; its
  boundary-stable paging is why the trim never fires on the commit merge's fast path.
- `2026-08-31-front-coded-column-stores-wtf8` — the front-coded string column, which already had the
  logical-versus-physical split this record generalises to the rest of the family.
- `2026-08-31-trigram-query-path-optimization` — the trigram postings hybrid, whose promote/demote
  thresholds produced the representation-versus-cardinality bug class that Option A would reintroduce.
- `2026-07-18-paged-index-corruption-and-flush-failure-boundary` — the warm-up write path is the one
  this record leaves without a boundary. That corruption turns out **not** to have been a race: a
  warm-up flush never reaches a commit merge, so an empty page baseline surfaced on a later cold load.
- `2026-08-10-stored-value-normalization-split` — the normalizer that rewrites a range key to the schema
  scale before it reaches the tree, which is what makes a scale-carrying range column sound.
- `2026-09-04-millisecond-temporal-precision` — the other half of issue #1486, and what this record made
  findable: once every column was sized to its live content, `InstantValueColumn`'s second parallel array
  and `RangeValueColumn`'s third one were the largest remaining oddities in the family. Both are now gone.

## Timeline

- **2026-09-01** — the issue's design analysed against the census; the plan written and adversarially
  reviewed
- **2026-09-02** — Option B taken on the Option C path; the column family, the leaf and tree, the atomic
  single-session admission, the census extension and the hardening pass committed
- **2026-09-03** — measurement window: the allocation gate, the census re-run on three corpora, the
  decouple-with-headroom mitigation and its re-measurement, and the value-id concurrency
  re-calibration; the dictionary lever refused on numbers; range keys moved to primitive bound columns
  and gated; the opt-in `largeMachine` test profile added; Option A declined and the bucket count moved
  onto the tree's own diff layer
