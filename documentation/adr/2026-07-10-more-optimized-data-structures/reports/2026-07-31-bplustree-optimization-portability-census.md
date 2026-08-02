# B+ tree optimization portability census

Which of the recent B+ tree optimizations port to the rest of the family, which do not, and what the
measurements say about whether porting is worth it.

Written against `dev` at `c27c7bb5f`. Issue: [#1333](https://github.com/FgForrest/evitaDB/issues/1333)
(milestone 2026.3), which asks specifically about cursor allocation; this document is wider, because
several unrelated optimizations landed on the same trees in the same period.

## Headline

1. **Phase 1's gate passes, comfortably.** A cursor is 192–232 B measured; a cursor-free descent
   allocates nothing. That is 14.7% of an ALIVE-mode `Bucket` insert — the one figure where numerator
   and denominator come from the same fixed tree — and 60% of a warm-up `Bucket` insert. The 5%
   threshold is not close.
2. **The blocker #1333 defers to Phase 3 is a prerequisite for one tree and irrelevant for another,
   and the source says which is which.** `Element`'s inserts are ascending *by documented contract*,
   so every insert is a tail insert, the boundary assert fires every time, and lazy capture ported
   naively saves nothing. `Bucket`'s keys are unsorted attribute values, so the same asserts already
   skip ~99% of inserts and it ports today. Opposite recommendations, no measurement needed to tell
   them apart.
3. **The assert blocker is mechanical, not a design project.** Both asserts read the cursor purely as
   index arithmetic over the descent; a top-down descent carrying two extra locals reproduces both at
   zero allocation.
4. **One candidate died on measurement.** The `InsertionPosition` fold looked like the wider win —
   it is paid on reads as well as writes — but all five trees allocate identically per descent whether
   or not they construct the record. Escape analysis already removes it.

## The family

| tree | base class | production users |
|---|---|---|
| `TransactionalBucketBPlusTree` | none (standalone) | `InvertedIndex`, `FilterIndex`, `OwnerUniqueIndex`, `GlobalUniqueIndex`, `ReferenceTypeCardinalityIndex` |
| `TransactionalElementBPlusTree` | `AbstractIntKeyedBPlusTree` | `PriceListAndCurrencyPriceSuperIndex` / `…RefIndex` |
| `TransactionalLongBPlusTree` | `AbstractTransactionalBPlusTree` | `RangeIndex` |
| `TransactionalObjectBPlusTree` | `AbstractTransactionalBPlusTree` | `TrafficRecordingIndex` (traffic engine only) |
| `TransactionalIntToLongBPlusTree` | `AbstractIntKeyedBPlusTree` | `UnorderedLookupTree` / `TransactionalUnorderedIntArray` |

`CumulativeWeightBPlusTree` (in `evita_common`) is a separate order-statistic tree and is treated
separately below.

## Census

| # | optimization | landed in | Bucket | Element | Long | Object | IntToLong |
|---|---|---|---|---|---|---|---|
| O1 | lazy cursor capture on insert | `7869c5fdc` + `46b4fecf3` | **ports as-is** (unsorted keys) | **needs fenced descent first** | ports; key order unknown | **ports as-is** (no asserts) | done |
| O2 | drop `InsertionPosition` from internal-node routing | `b19d3956a` | ports, **no measured gain** | done (shared base) | ports, **no measured gain** | ports, **no measured gain** | done |
| O3 | route rank by binary search instead of linear scan | `0eedfaa16` | no analogue | no analogue | no analogue | no analogue | no analogue |
| O4 | fuse two descents into one | `0eedfaa16` | no analogue | no analogue | no analogue | no analogue | no analogue |
| O5 | answer boundary asserts from the insertion index | `8b6c2a2e8` | done | ports, low value | ports, no value | n/a (no asserts) | n/a |
| O6–O8 | `FrontCodedStringColumn` scratch / `Arrays.compareUnsigned` / `Arrays.mismatch` | `8b6c2a2e8`, `950c803f9` | done | nowhere to port | nowhere to port | nowhere to port | nowhere to port |
| O9 | cursor path capacity heuristic | *not yet done anywhere* | **applies** | **applies** | **applies** | **applies** | **applies** |

## Measurements

New harness: `evita_test/evita_performance_tests/…/spike/BPlusTreeCursorAllocationBenchmark.java`.
JDK 17, single fork, 3×1 s warm-up + 5×1 s measurement, `-prof gc`, `-Xmx12g`. Trees hold 200,000
entries. The judged metric is `gc.alloc.rate.norm`, which is deterministic here — every reported error
bar is ±0.1–65 B on totals of 2–70 MB.

Nothing in a measured method allocates on the harness's behalf: keys are pre-boxed, elements
pre-built, the payload is one shared instance, and the shuffle happens in `@Setup`.

### 1. How big is a cursor? (measured, not modelled)

`TransactionalIntToLongBPlusTree` exposes the same descent twice — `search(int)` captures a `Cursor`
path, `searchOrDefault(int, long)` uses the allocation-free `findLeafNode`. Same tree, same leaf, same
comparisons, absent probe key so neither result allocates. The difference is the cursor and nothing
else. Bytes per descent (`alloc.rate.norm` ÷ 10,000):

| block size | cursor-free descent | cursor descent | delta = cursor |
|---|---|---|---|
| 64 | **0.64 B** | 216.0 B | **215 B** |
| 256 | **0.55 B** | 208.0 B | **207 B** |

The cursor-free arm is zero to within the profiler's floor. The other three cursor-based trees measure
the same cursor at the same fan-out: `Element` / `Long` / `Object` all 232 B at block 64 and 208 B at
block 256; `Bucket` 232 B / **192 B**. `Bucket`'s 16 B deficit at block 256 is unexplained and is left
as measured — using 208 B for it instead would only raise every share below.

### 2. Bytes per insert, warm-up (bulk) path, by key order

`alloc.rate.norm` ÷ 200,000. Both key orders insert exactly the same key set — only arrival order
differs.

| tree | 64 ASC | 64 RND | 256 ASC | 256 RND |
|---|---|---|---|---|
| `Bucket` | 352.8 | 335.8 | 320.9 | 304.9 |
| `Long` | 318.1 | 301.8 | 288.3 | 272.5 |
| `Object` | 276.8 | 264.8 | 248.0 | 237.3 |
| `Element` | 251.5 | 246.5 | 223.5 | 220.3 |
| `IntToLong` *(already lazy)* | **102.4** | **84.4** | **91.3** | **75.6** |

The already-optimized tree allocates **2.4×–3.5× less per insert** than its four siblings. Payload
type differs across the family, so this is not a clean A/B — but no other structural difference in the
insert path accounts for a gap that size.

### 3. Bytes per insert, ALIVE (transactional) path

`Bucket` only, 5,000 fresh keys inserted into a 200,000-entry tree inside a transaction that is then
rolled back — so the insert-side diff layers are measured and the commit-time merge (which scales with
dirtied leaves, not with inserts) is not. Per-iteration times are flat across all five iterations
(2.65–2.77 ms at block 256), confirming the rollback leaves the base tree pristine and no layers leak
between invocations.

| block size | B / transactional insert |
|---|---|
| 64 | 1,135.9 |
| 256 | 1,309.9 |

### 4. Phase 1 gate — is the cursor ≥ 5% of insert allocation?

**Passed, by more than an order of magnitude, in every arm measured.**

The single fully rigorous figure — both numerator and denominator measured on the *same fixed
200,000-entry tree*, so no extrapolation is involved:

> `Bucket`, ALIVE mode, block size 256: **192 / 1,309.9 = 14.7%** (block 64: 232 / 1,135.9 = **20.4%**).

The warm-up shares are larger but are **upper bounds**: they divide the final-tree cursor by the
build's mean per-insert allocation, while during a build both the path depth and the
`Math.log(size())` capacity are smaller for early inserts, so the true mean cursor is somewhat below
the quoted 192–232 B.

| tree (block 256, warm-up) | ASC | RND |
|---|---|---|
| `Element` | ≤ 93% | ≤ 94% |
| `Object` | ≤ 84% | ≤ 88% |
| `Long` | ≤ 72% | ≤ 76% |
| `Bucket` | ≤ 60% | ≤ 63% |

**These four shares are not like-for-like, and the difference is the fixture, not the trees.** The
harness inserts pre-built payloads — `Element` gets an already-allocated `KeyedElement`, `Long` and
`Object` a single shared instance — whereas production allocates a `PriceRecordContract` per price
insert and a real value per range point. So the denominators for `Element`, `Long` and `Object` are
missing payload allocation their production callers do pay, which inflates their share. `Bucket` is
the least affected (its payload is an `int`, genuinely allocation-light), so **only `Bucket`'s share
approximates its production ratio.**

What the column does establish, per tree, is that the cursor dominates the *tree's own* per-insert
allocation everywhere — which is the quantity O1 removes. Do not read `Element`'s 93% against
`Bucket`'s 60% as "Element has 1.5× more to gain".

### 5. What the measurement disproved

**`InsertionPosition` allocation is invisible at runtime.** `Long` and `Object` allocate one per routed
level in source; `Element` allocates one in the leaf; `IntToLong` allocates none anywhere. At block
size 256 all four measure **208 B** per descent — identical to ±1.2 B. Escape analysis scalar-replaces
the record. (`Bucket` also constructs it and measures 192 B; its unexplained 16 B deficit keeps it out
of this comparison — see O2.)

### 6. Secondary signal — time

Not the judged metric (single-fork wall time), but consistent across arms: the cursor costs ~22 ns per
descent (1.027 vs 0.804 ms per 10,000 descents at block 256), a ~28% overhead on a read descent. On
the insert path, RANDOM key order is 2.2×–3.1× slower than ASCENDING in every tree — cache behaviour,
not assert cost, since allocation is flat between the two orders.

## O1 — lazy cursor capture

### What it does

`createCursor(key)` allocates an `ArrayList`, its backing `Object[]`, a one-element root sibling
array, a `CursorLevel` per level and the `Cursor` itself. The path exists **only** so a split can
cascade upward. `7869c5fdc` made `TransactionalIntToLongBPlusTree` descend via the allocation-free
`findLeafNode` and capture the path only when the leaf `isNearlyFull()` — roughly one insert in
`valueBlockSize`.

### What blocks the port

Two things, and the issue text names both. The second is the one that matters.

**(a) `findLeafNode` exists only on `AbstractIntKeyedBPlusTree`.** So `Element` and `IntToLong` have
it; `Object`, `Long` and `Bucket` do not. This is not a real obstacle — the cursor descent in all
three is literally `children[node.searchIndex(key)]` repeated while the node is internal
(`TransactionalBucketBPlusTree.addCursorLevels:403`, `TransactionalLongBPlusTree:220`,
`TransactionalObjectBPlusTree:244`), so an allocation-free variant is under ten lines in each.

**(b) The boundary asserts consume the cursor on every successful insert.** `Bucket`, `Element` and
`Long` all call `assertInsertBoundaries(cursor, …)` from the insert path, ungated. `Object` does not
— it has no boundary asserts and no dirty-scope registration, so for `Object` the port is exactly the
`IntToLong` change with a `findLeafNode` added.

### The part the issue gets wrong: key order decides everything

`assertInsertBoundaries` fires the tail assert when the inserted key becomes the leaf's **last** key
and the head assert when it becomes the leaf's **first** key.

- **Random keys**: a boundary insert is roughly `2 / (leaf occupancy)` of inserts — on the order of
  1% at `valueBlockSize` 256. Lazy capture skips the cursor on ~99% of inserts, and the residual
  1% needs it. Full benefit.
- **Ascending keys**: **every** insert is a tail insert. The assert fires 100% of the time, needs the
  cursor 100% of the time, and lazy capture buys **exactly nothing**.

The discriminator is not a workload guess — for two of the trees it is stated in the source:

- **`Element` is ascending, documented.** `AbstractPriceListAndCurrencyPriceIndex:486`: *"The array is
  expected ascending by internal price id, so the inserts land at the right edge and never need to
  re-sort."* A naive O1 port to `Element` is provably worth zero.
- **`Bucket` is unsorted.** `InvertedIndex` / `FilterIndex` key on the **attribute value**, and values
  arrive in whatever order entities do. A naive O1 port to `Bucket` gets essentially the full win.
- **`Long` is unknown.** `RangeIndex` keys are range thresholds (validity instants, scaled prices);
  order depends entirely on the data. Do not guess — measure before deciding, or take the fenced
  descent and stop caring.
- **`Object` has no asserts**, so key order is irrelevant to it.

So the two trees at the extremes need **opposite recommendations**, and the "2.25 GB / 60 s across
this tree family" figure that motivates #1333 is dominated by the tree where the naive port cannot
help.

**This inverts the issue's phase ordering for `Element`.** #1333 lists "resolve boundary assertions"
as Phase 3, after both measurement gates. For an ascending workload it is a **prerequisite**: without
it there is nothing to gate, because the cursor is needed on every insert regardless.

### Resolving the assert blocker is mechanical, not a design project

Both asserts read the cursor path purely as *index arithmetic over the descent*, and a top-down
descent can carry everything they need in a handful of locals at zero allocation.

`assertTailBoundary` (`TransactionalBucketBPlusTree:1646`, `TransactionalLongBPlusTree:537`, and the
`Element` twin) walks the path bottom-up looking for the deepest level whose descent was **not** into
the rightmost child, and reads that ancestor's separator at the child index as the fence. A top-down
descent computes the same value by keeping one `fence` local and overwriting it at every internal node
where `childIndex < node.getPeek()` — the last write is the deepest such level, which is exactly what
the bottom-up loop finds first. If it is never written, there is no fence and the assert is vacuous.

`assertHeadBoundary` needs the predecessor leaf. Common case (`childIndex > 0`): it is
`deepestInternalNode.getChildren()[childIndex - 1]`, available directly during the descent. Rare case
(`childIndex == 0` all the way down to the leaf): walk to the nearest ancestor with `childIndex > 0`
and follow its left neighbour's right spine — the same "deepest node where `childIndex > 0`" local
gives that ancestor.

So a `findLeafNodeWithBoundaryContext` returning `(leaf, fenceOrAbsent, predecessorOrNull)` replaces
the cursor for assert purposes, allocates nothing, and leaves the cursor needed only for splits —
which is what O1 requires.

Note the payoff shape this creates: under ascending inserts the descent is rightmost at every level,
so no fence is ever recorded and the tail assert becomes a no-op *and* the cursor is never captured.
The ascending regime goes from "lazy capture is worthless" to "lazy capture is free".

### Recommendation

| tree | do what | why |
|---|---|---|
| **`Bucket`** | port O1 **as-is** | keys are unsorted attribute values, so the asserts already skip ~99% of inserts. Highest production value (every indexed attribute). The fenced descent is optional polish here. |
| **`Object`** | port O1 **as-is** | no asserts at all — this is the `IntToLong` change verbatim plus a `findLeafNode`. Cheapest of the four, but its only user is `TrafficRecordingIndex`. |
| **`Element`** | fenced descent is a **hard prerequisite** | ascending inserts by documented contract; O1 alone is provably worth zero. Its ≤93% share overstates the production ratio (the fixture omits per-insert payload allocation, §4) — but the cursor is still the dominant term in the tree's own allocation. |
| **`Long`** | measure the key order first, or take the fenced descent | genuinely unknown; both outcomes are plausible. |

Ordering by expected value: `Bucket` first (cheap port × widest use), `Element` second (largest share,
but gated on the fenced descent), `Long` third, `Object` last.

## O2 — drop `InsertionPosition` from internal-node routing

`b19d3956a` folded `Arrays.binarySearch` directly into the child-index computation on
`AbstractIntKeyedInternalNode.searchIndex(int)`, removing a per-level record allocation that existed
only to be collapsed to a single `int` in both branches.

`Object`, `Long` and `Bucket` still allocate it, in code that is structurally identical to what was
fixed:

- `TransactionalObjectBPlusTree.BPlusInternalTreeNode.searchIndex:1680`
- `TransactionalBucketBPlusTree.BPlusInternalTreeNode.searchIndex:3239`
- `TransactionalLongBPlusTree.BPlusInternalTreeNode.searchIndex:1974`

All three have the same shape:

```java
final InsertionPosition insertionPosition = findKeyPosition(key, this.keys, 0, this.peek);
return insertionPosition.alreadyPresent()
    ? insertionPosition.position() + 1
    : insertionPosition.position();
```

and the underlying helper already *is* an `Arrays.binarySearch` wrapper
(`TransactionalObjectBPlusTree.findKeyIndex:2409`,
`ArrayUtils.computeInsertPositionOfLongInOrderedArray:811`), so the fold is
`idx >= 0 ? idx + 1 : -idx - 1` and nothing else.

### Measured verdict: no allocation benefit — escape analysis already removes it

This was expected to be the *wider* win of the two, because the allocation is paid on every routed
level of every descent, reads included, not just on inserts. **The measurement says otherwise.**

At block size 256 the per-descent allocation is 208 B for `Element` (one `InsertionPosition` in the
leaf, allocation-free routing), 208 B for `Long` and `Object` (one per routed level *plus* the leaf),
and 208 B for `IntToLong` (none anywhere). Identical, to within ±1.2 B on a 2 MB total.

That is the whole argument, and it rests on those **four** trees: three construct the record, one does
not, and all four measure the same. `Bucket` — which also constructs it — measures 192 B and is
deliberately **excluded** from the comparison, because its 16 B deficit is unexplained (§1) and an
unexplained residual cannot be evidence either way. Excluding it does not weaken the conclusion; the
`Element` ↔ `IntToLong` pair alone (one leaf record vs none, same 208 B) already demonstrates it.

`InsertionPosition` is a small record that never escapes the descent, and the JIT scalar-replaces it.
The same holds at block size 64, where all four measure 232 B.

`b19d3956a` was taken on strictly-fewer-operations grounds with no measured number, and on that basis
it was a fine change. But the case for **porting** it rests on an allocation saving that does not
exist at runtime.

**Recommendation: do not port O2 for allocation.** If it is ported at all, port it as a readability /
fewer-operations change and say so — it removes a branch and a record construction per level, which
may show up in time on a descent that inlines poorly (a deep production call stack does not
necessarily inline like this microbenchmark's tight loop). That is a hypothesis this harness cannot
test; it would need a time-judged A/B in situ.

If it is ported, **scope the fold to `searchIndex` only.** The leaf-path callers of `findKeyPosition`
consume the `alreadyPresent` bit and must keep the record; internal-node routing is the only place
that collapses it to a pure child index. That is precisely the boundary `b19d3956a` draws.

This also revises a claim in the bulk-ingest proposals document, which lists "~7.3 s (2%) in the
internal-node descent over boxed `String[]` keys" as an open item with no design. Whatever that 7.3 s
is, it is not `InsertionPosition` allocation.

## O3 / O4 — `rankOf` binary search and the fused rank+weight descent

`0eedfaa16` fixed `CumulativeWeightBPlusTree.rankOf`, which walked an internal node's separators
linearly (up to 63 comparisons) while `descend()` five lines away already binary-searched them, and
added `rankAndWeightOf` so `computePreviousRecord` makes one root-to-leaf descent instead of three.

**Neither ports.** Both are order-statistic operations — cumulative subtree weights, rank-by-position
— and no member of the engine's five-tree family maintains a weight or exposes a rank. A sweep for
linear separator scans and for comparator calls inside loops across all five turned up exactly one
hit, and it is a structural validation loop (`TransactionalBucketBPlusTree:1575`), not a hot path.

The *pattern* was worth checking for and is now checked: nothing in the family has one method
linear-scanning what a sibling method binary-searches.

## O5 — answer the boundary asserts from the insertion index

`8b6c2a2e8` stopped `TransactionalBucketBPlusTree.assertInsertBoundaries` from decoding the leaf's
first and last key (two front-coded decodes, two `String` materializations, two comparator calls, the
last a full collation on a localized attribute) and answered "head insert?" / "tail insert?" from
`findKeyPosition`'s insertion index instead.

**`Element` — ports, low value.** `assertInsertBoundaries:720` calls `leaf.getLeftBoundaryKey()` and
`this.keyExtractor.applyAsInt(leaf.getValues()[leaf.getPeek()])`: an array read plus a lambda call,
not a decode. The transformation applies (the leaf `insert` would have to return its insertion index)
but it buys a lambda call per insert.

**`Long` — ports, no value.** `assertInsertBoundaries:838` reads `keys[peek]` and `keys[0]` from a
`long[]`. Two array reads. `8b6c2a2e8`'s own closing note makes exactly this point about
integer-keyed leaves.

**`Object` — not applicable**, it has no boundary asserts.

The value of O5 was never the index; it was that a `FrontCodedStringColumn` key costs a decode to
read. Only `Bucket` has one.

## O6–O8 — the `FrontCodedStringColumn` changes

`encode` taking the caller's `DecodeScratch` instead of re-fetching the `ThreadLocal`,
`compareUnsignedBytes` collapsing to `Arrays.compareUnsigned`, and `commonPrefix` moving to
`Arrays.mismatch` (1.26×–2.95× measured on JDK 17).

**Nowhere to port them to.** `FrontCodedStringColumn` is reachable only from
`TransactionalBucketBPlusTree` via `ValueColumnFactory`; the other four trees key on primitives or on
boxed `Comparable` arrays and never construct one.

## O9 — the cursor path capacity heuristic (new, applies to all five)

Every `createCursor` in the family sizes its path list as:

```java
new ArrayList<>(this.size() == 0 ? 1 : (int) (Math.log(this.size()) + 1))
```

Eight sites: `AbstractIntKeyedBPlusTree:248`, `AbstractTransactionalBPlusTree:493` and `:512`,
`TransactionalLongBPlusTree:1201`, `TransactionalObjectBPlusTree:859`,
`TransactionalBucketBPlusTree:2219`, `:2239`, `:2261`.

That is a **natural** logarithm of the entry count, but the quantity wanted is the tree's height —
`log_branchingFactor(size)`. At one million entries with `valueBlockSize` 256 the real depth is 3–4
while the heuristic asks for 14, so the backing `Object[]` is roughly four times larger than the path
that goes into it. The waste is on every cursor, read paths included.

Measured, the backing array is 72 B of a 208 B cursor at block 256 / 200k entries, against ~32 B if it
were sized to the real depth — so O9 is worth roughly **40 B of 208**, about 19% of a cursor.

It is orthogonal to O1 and they compose, but note where each lands: O1 removes the cursor from
~`1 - 1/valueBlockSize` of *inserts* and does nothing for reads; O9 shrinks every cursor that remains,
**including every read cursor**, which O1 never touches. On a read-heavy index the two are close to
independent wins.

Rank it below O1 and above O2 — but it is the highest risk-per-byte item here, because the fix needs
the tree's height and no tree currently tracks it. Either maintain a `height` field (incremented on
root split, decremented on root collapse — new mutable state on the structural path, which is exactly
where this family's historical defects live) or derive it as
`log(size) / log(minInternalNodeBlockSize)`, which needs no new state and only has to be an
over-estimate to stay correct. Prefer the second unless the first falls out of other work.

## Not portable, and not an optimization: what actually produced the biggest recent win

`f193d7b83` — the sort-index change — did not make a tree faster. It deleted a derived structure
(`SortIndexChanges.valueLocationTree`, a whole `CumulativeWeightBPlusTree` rebuilt per transaction)
after establishing that the only thing its consumer needed was the inserted record's immediate
predecessor, which the authoritative `InvertedIndex` could name directly. A single-entity write went
from 1,182 ms to 16.8 ms at 320k distinct values.

The transferable lesson is the question, not the code: *what derived structure is being maintained,
and does its consumer actually need what it provides?* Applied to this family, the candidate that
answers "not exactly" is the cursor path itself — it is a full root-to-leaf spine captured for a split
that happens once per `valueBlockSize` inserts. O1 is that question already asked and answered once.

The second-order version of the same question is what produced the fenced-descent proposal: the
boundary asserts do not need *a path*, they need a fence key and a predecessor leaf. The path is how
they currently get them, not what they require.

## Implementation

Landed on `dev` for **2026.2**, in the order below — each step compiles and passes the tree suites on its
own, so any one of them can be reverted without the others.

### 1. Allocation-free descent on read paths

`findLeafNode(key)` added to `Bucket`, `Long` and `Object` (`Element` and `IntToLong` already inherited
one from `AbstractIntKeyedBPlusTree`), and every lookup that used nothing but `cursor.leafNode()`
switched to it:

| tree | call sites |
|---|---|
| `Bucket` | `getRecordsEqualTo`, `getLongRecordEqualTo`, `cardinalityOf`, `contains` |
| `Object` | `search` |
| `Long` | `search`, `markDirty` |
| `Element` | `search` |
| `IntToLong` | `search` (`searchOrDefault` was already allocation-free) |

This is §1's A/B applied verbatim, and it is the widest change here: `getRecordsEqualTo` and `contains`
are on the query path of every indexed attribute. Structural operations — `delete`, `removeRecord`,
`consolidate`, `updateParentKeys`, the iterators and `validateDirtyScope` — still capture a path,
because they read more of it than the leaf.

### 2. Cursor path capacity (O9)

All eight sites now call `estimatedPathLength()` (`2 + log(size)/log(internalNodeBlockSize)`) instead of
`(int)(Math.log(size()) + 1)`. Shared via `AbstractTransactionalBPlusTree` for four of the trees;
`Bucket` is standalone and carries its own copy. The value is an `ArrayList` capacity hint, so an
under-estimate costs one array grow and never correctness — which is why it is derived from the
node's *maximum* fan-out rather than the worst-case bound `minInternalNodeBlockSize` would give.

### 3. `Bucket.computePreviousRecord`

Descends allocation-free and captures a path only in the cross-leaf branch, where the in-leaf anchor
misses and the climb genuinely needs one. Nothing mutates between the two descents, so the re-descent
takes the same route.

### 4. The fenced descent, and lazy capture on insert

`findLeafNodeWithBoundaryContext(key)` added to `Bucket`, `Element` and `Long`. It reaches the same leaf
as `findLeafNode` and, in the same pass, records the fence (last level where `childIndex < peek`) and
the deepest level where `childIndex > 0`, from which the predecessor leaf is resolved. It returns a
`BoundaryContext` record that never escapes the insert method.

Two details worth keeping:

- **The predecessor is resolved behind `BoundaryContext.predecessor()`, not during the descent.** The
  head assert fires only when the inserted key becomes the leaf's first; resolving eagerly would make
  every other insert pay a transactional child-array resolution for an answer nobody reads.
- **The comparison lives in one place per tree** (`checkTailBoundary` / `checkHeadBoundary`); the cursor
  path and the descent are two *resolvers* feeding it. `validateDirtyScope` still uses the cursor-based
  resolver, so both stay live and a new test pins that they agree.

With the asserts no longer consuming the path, the insert methods capture a cursor only when
`leaf.isNearlyFull()` — `Bucket.addRecord` ×2 and `addLongRecord`, `Element.insert`, `Long.insert` and
`upsert`, `Object.insert` and `upsert`. Each `isNearlyFull()` mirrors its own tree's `isFull()`,
reading `peek` and the capacity from the *same* resolved state — `Bucket`'s leaves are column-backed,
so it reads `records.capacity()`, not the tree's `valueBlockSize`. A leaf that turns out full with no
captured path throws a named error rather than a bare `NullPointerException`.

Under ascending keys the descent is rightmost at every level, so no fence is recorded, the tail assert
becomes a no-op *and* the cursor is never captured — which is exactly the regime `Element` runs in.

### Measured after the port

Same harness, same fixture, same JDK. `gc.alloc.rate.norm`; every figure below is `B/op ÷ batch size`.
**`IntToLong` is the control** — its insert path was already lazy and was not touched — and it moved by
**≤ 1.5 % in all four arms** across the two runs, which is what makes the before / after columns
comparable at all.

**Read descents, per descent, block 256.** The cursor is simply gone:

| arm | before | after |
|---|---|---|
| `descendIntToLongWithCursor` | 208 B | **0.0006 B** |
| `descendBucket` | 192 B | **0.0007 B** |
| `descendElement` | 208 B | **0.0008 B** |
| `descendLong` | 208 B | **0.0006 B** |
| `descendObject` | 208 B | **0.0010 B** |

All five are now at the profiler floor, indistinguishable from the already-allocation-free
`descendIntToLongCursorFree` (0.0006 B). A read descent no longer allocates.

**Bytes per insert, warm-up path, block 256:**

| tree | ASC before → after | RND before → after | RND change |
|---|---|---|---|
| `Bucket` | 320.9 → **124.0** | 304.9 → **108.1** | **−64.6 %** |
| `Long` | 288.3 → **131.4** | 272.5 → **115.7** | **−57.5 %** |
| `Object` | 248.0 → **51.0** | 237.3 → **40.3** | **−83.0 %** |
| `Element` | 223.5 → **58.3** | 220.3 → **55.3** | **−74.9 %** |
| `IntToLong` *(control)* | 91.3 → 90.8 | 75.6 → 75.4 | −0.3 % |

At block 64: `Bucket` 335.8 → 119.1, `Long` 301.8 → 193.2, `Object` 264.8 → 48.0, `Element` 246.5 →
61.2 (RANDOM), control 84.4 → 83.8.

**Bytes per insert, ALIVE path (`Bucket`):**

| block | before | after | change | §4 predicted |
|---|---|---|---|---|
| 64 | 1,135.9 | **871.9** | −23.2 % | 20.4 % |
| 256 | 1,309.9 | **1,101.9** | −15.9 % | 14.7 % |

The measured drop slightly **exceeds** the predicted cursor share in both arms, which is what O9 should
do — it shrinks the cursors that are still captured on splits. That the two agree to ~1.5 points is the
best evidence the §1 cursor sizing was right.

**`BoundaryContext` is scalar-replaced.** The decisive arm is `Bucket` warm-up at block 256 RANDOM: it
fell by **196.8 B** against a cursor measured at **192 B** — the saving is *larger* than the entire
object that was removed. A 32-byte record surviving on every insert cannot be reconciled with that.
(The residual per-insert cost is not decomposable from these arms for `Element` and `Long`, whose
split-time node allocations differ; the claim rests on `Bucket`, where the cursor and the insert were
measured on the same tree.)

### Latency

Measured separately at **3 forks × 5 iterations (15 samples)** without `-prof gc`, so the profiler is
not perturbing the timings. The allocation sweep above ran under heavy foreign load and its `ms/op`
figures are discarded; these replace them. Background load was still not zero, which is exactly why the
fork count was raised — the confidence intervals below are the evidence that the numbers survived it.

**Read descents, block 256, ms per 10,000 descents:**

| arm | before | after | change |
|---|---|---|---|
| `descendIntToLongWithCursor` | 1.027 ± 0.101 | **0.793 ± 0.012** | **−22.8 %** |
| `descendBucket` | 1.441 ± 0.009 | **1.148 ± 0.114** | **−20.3 %** |
| `descendLong` | 1.083 ± 0.059 | **0.901 ± 0.010** | **−16.8 %** |
| `descendObject` | 1.606 ± 0.230 | 1.433 ± 0.079 | −10.8 % |
| `descendElement` | 1.351 ± 0.166 | 1.221 ± 0.109 | −9.6 % |
| `descendIntToLongCursorFree` *(control, code unchanged)* | 0.804 ± 0.031 | 0.821 ± 0.029 | +2.1 % |

**The cleanest result in this document is the A/B collapsing.** Before the port, the cursor-capturing
descent cost 1.027 against the cursor-free 0.804 — the ~28 % overhead §6 reported. After it, the same
two arms read 0.793 and 0.821: **the with-cursor arm is now indistinguishable from the cursor-free one**
(nominally faster, which is noise). The overhead did not shrink, it disappeared, and the control's
+2.1 % drift bounds how much of that could be machine state.

**Inserts, block 256, ms per 200,000 inserts:**

| tree | ASC before → after | ASC | RND before → after | RND |
|---|---|---|---|---|
| `Bucket` | 17.576 → **10.568** | **−39.9 %** | 41.284 → **33.403** | **−19.1 %** |
| `Element` | 13.600 → **9.175** | **−32.5 %** | 36.639 → 35.647 | −2.7 % *(noise)* |
| `Long` | 14.775 → **10.539** | **−28.7 %** | 33.513 → **29.405** | **−12.3 %** |
| `Object` | 13.927 → **11.342** | **−18.6 %** | 43.115 → **39.615** | **−8.1 %** |
| `IntToLong` *(control)* | 7.692 → 8.003 | +4.0 % | 25.810 → 26.073 | +1.0 % |

ALIVE-mode `Bucket`: 2.704 → **2.524** at block 256 and 2.514 → **2.346** at block 64, both **−6.7 %**.

**ASCENDING gains roughly twice what RANDOM does, and that is the design showing through.** Under
ascending keys the descent is rightmost at every level, so no fence is recorded, the tail assert becomes
a no-op *and* the cursor is never captured — the regime in which lazy capture alone was worth zero is
the one that gains most. RANDOM inserts are dominated by cache misses (§6: 2.2×–3.1× slower than
ASCENDING at identical allocation), which no amount of removed bookkeeping addresses. `Element` under
RANDOM is the one arm where the change is not distinguishable from noise — and `Element`'s production
workload is ascending by documented contract, so that arm is not the one that matters for it.

### What was deliberately not done

- **O2 (`InsertionPosition` fold)** — measured at zero (§5). Not ported, not even for readability.
- **O3 / O4 / O6–O8** — no analogue, or `Bucket`-only. Unchanged.
- **`delete` / `removeRecord` / `consolidate` / iterator cursors** — they consume the path.
- **A `height` field** — rejected in favour of the derived estimate; it would add mutable state on the
  structural path, which is where this family's historical defects live.

### Tests

The existing boundary-assert tests kept their cursor-based form (they now cover the resolver
`validateDirtyScope` still uses) and three tests were added — one per tree carrying asserts — that pin
the descent against the captured path: same leaf, same predecessor, same fence, plus explicit values
for the fence and predecessor themselves. That last part is the point: a descent that always answered
"no fence" would keep every other test green, because in a sound tree the asserts never fire.

The five `InsertionIndexBranchSelectionTest` cases in `Bucket` — the randomized collated ones — now
drive the new descent instead of a cursor, so the equivalence they pin is exercised under a collator
and across post-split leaves.

## What this does and does not settle

**Settled:**
- The cursor is 192–232 B and a cursor-free descent allocates nothing (§1).
- Phase 1's ≥5% gate passes — 14.7% on the one arm where numerator and denominator are measured on
  the same fixed tree, and far higher on every warm-up arm (§4).
- O2 has no allocation benefit; the record is scalar-replaced (§5). This closes a line of work that
  looked like the wider win.
- O3/O4/O6/O7/O8 have nowhere to port to, for structural reasons, not measurement.
- `Element`'s ascending key order is a documented source contract, so its recommendation does not
  depend on any measurement.

**Settled by the port itself (see `Implementation`):**
- A read descent no longer allocates on any of the five trees — 192–232 B → the profiler floor.
- Warm-up insert allocation fell 57–83 % on the four ported trees, against a control that moved ≤1.5 %.
- The ALIVE `Bucket` insert fell 15.9 % at block 256, against the 14.7 % this document predicted from
  the cursor's measured size — the two agree to ~1.5 points.
- `BoundaryContext` is scalar-replaced: `Bucket`'s per-insert allocation fell by more than the whole
  cursor it removed.
- Latency, at 3 forks: read descents 10–23 % faster, inserts 19–40 % faster under ascending keys and
  8–19 % under random, ALIVE `Bucket` 6.7 % — against a control arm that drifted +1 % to +4 %. The ~28 %
  cursor overhead on a read descent is gone, not reduced: the two `IntToLong` descent arms now measure
  the same.
- The correctness of the fenced descent, to the extent 20,743 functional tests plus nine time-bounded
  generational / savepoint fuzz classes can establish it (all green, no boundary or missing-path error).

**Not settled:**
- The boundary-assert **fire rate** under random keys is modelled (`2 / occupancy`), not measured.
  It no longer gates anything — the fenced descent removed the dependency — but the model is untested.
- `Long`'s production key order.
- **`Element` inserts under RANDOM keys** — the only latency arm where the change is not distinguishable
  from noise (−2.7 %, overlapping CIs). Its production workload is ascending, where it gains 32.5 %.
- Whether the residual per-insert allocation on `Element` and `Long` (55.3 B / 115.7 B at block 256) is
  split-time node allocation or something avoidable — these arms cannot decompose it.
- Whether O2 buys anything in *time* in a production call stack that inlines worse than this
  benchmark's loop.
- The warm-up shares in §4 are upper bounds; the exact build-mean cursor was not measured.
- The `Element` / `Long` / `Object` denominators exclude the payload allocation their production
  callers pay, so their shares are inflated by an unquantified amount and are not comparable to
  `Bucket`'s. Sizing that would need a fixture that allocates a realistic payload per insert.
- Only `Bucket` has an ALIVE-mode arm. The other three trees are warm-up only.
- `Bucket`'s 16 B per-descent deficit against the other three trees is unexplained.

## Reproducing

Build the WHOLE reactor, not a `-pl` subset — a partial install leaves the rest of the modules
resolving from `$HOME/.m2/repository`, which on a shared machine is another agent's bytecode:

```shell
mvn -P full clean install -DskipTests
java -Xmx12g -cp evita_test/evita_performance_tests/target/benchmarks.jar \
  org.openjdk.jmh.Main "io\.evitadb\.spike\.BPlusTreeCursorAllocationBenchmark" -prof gc
```

Note `-cp`, not `-jar` — the shaded jar's main ignores its arguments.

Runtime is about 11 minutes for the full sweep (34 arms). To re-check just the cursor A/B:
`… BPlusTreeCursorAllocationBenchmark.descendIntToLong.* -prof gc -p blockSize=256`.

## Scope note

This document is the analysis that preceded the port: the census, the measured Phase 1 gate, and the
fenced descent as a design. The work was subsequently pulled into **2026.2** and implemented — see
`implementation` below for what landed and what was deliberately left out.
