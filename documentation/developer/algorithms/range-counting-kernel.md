# Range counting kernel

Every interval query evitaDB answers — "which products are on sale right now", "which price lists are valid at this
moment", "which entities overlap this week" — is resolved by one algorithm: a **signed count over two families of
bitmaps**. This page explains how it works, why it is a counting problem rather than a set problem, and how the
implementation stays cache-resident while doing it.

The code is `RangeIndex`, `RangeCountFormula` and `RangeCountKernel`. The decision history — what was replaced,
which alternatives lost and why — is in
[the ADR](../../adr/2026-09-18-range-index-counting-kernel.md); this page is the explanation, not the record.

## The question a range query asks

An entity carries a `Range` attribute — a validity window, a price applicability period, any `[a, b]` interval.
A query asks which entities satisfy a predicate about that interval:

| Query | Means |
|---|---|
| `attributeInRangeNow(validity)` | the entity's interval contains *now* |
| `attributeInRange(validity, <moment>)` | the entity's interval contains that moment |
| `attributeBetween(validity, from, to)` | the entity's interval **overlaps** `[from, to]` |

The complication that shapes everything below: **one entity may hold several intervals.** A product can be on sale
in March and again in June; a price list can have a dozen disjoint validity windows. The index must answer "is this
entity inside *any* of its intervals" without materialising them.

## What the index stores

`RangeIndex` does not store intervals. It stores their **endpoints**, in a `TransactionalLongBPlusTree` keyed by
threshold. Each key holds two bitmaps of **entity primary keys**:

- `starts` — the PKs whose interval *begins* at this threshold
- `ends` — the PKs whose interval *ends* at this threshold

Four products with a validity range:

```
  PK      3 : valid [10 .. 20]
  PK      9 : valid [10 .. 15]
  PK  70000 : valid [12 .. 30]
  PK      5 : valid [25 .. 40]
```

become seven threshold points:

```
  threshold │ starts (PKs) │ ends (PKs)
  ──────────┼──────────────┼───────────
      10    │ {3, 9}       │ {}
      12    │ {70000}      │ {}
      15    │ {}           │ {9}
      20    │ {}           │ {3}
      25    │ {5}          │ {}
      30    │ {}           │ {70000}
      40    │ {}           │ {5}
```

An entity with three intervals simply appears in three `starts` bitmaps and three `ends` bitmaps. Nothing in the
structure records that they belong together — and nothing needs to.

## Why counting, not set operations

Walk the thresholds up to a queried moment `t` and collect every `starts` bitmap into one family, every `ends`
bitmap into another. The obvious formulation is a set expression:

> *entities that have started, minus entities that have ended*

**This is wrong**, and the reason is the multi-interval case. Give PK 3 a second interval and query `t = 18`:

```
  PK 3 : valid [5 .. 8]  AND  [10 .. 20]

  it appears in  starts@5, starts@10   and   ends@8
```

PK 3 is in an `ends` bitmap, so set subtraction removes it — yet 18 sits squarely inside `[10 .. 20]`. The entity
is valid and the set formulation says it is not.

Counting gets it right, because it tracks *how many times*:

```
  starts at  5  ->  +1
  starts at 10  ->  +1
  ends   at  8  ->  -1
                   ────
                    +1   ->  strictly positive  ->  VALID
```

The primitive is therefore a **signed multiplicity**:

```
  result = { v : count(plus, v) − count(minus, v) > 0 }        count(F, v) = |{ i : v ∈ F[i] }|
```

Read plainly: *an entity is inside one of its intervals when it has started more times than it has ended.* Each
`−1` cancels exactly one earlier `+1`, so what survives is the number of intervals currently open. Strictly
positive means at least one is.

## Four queries, one counting pass

Because `a ≤ b` holds for every interval and **both** endpoints are indexed, three of the four public queries reduce
to the same prefix walk with different bounds. Only the *strictness* of the two bounds differs:

| Query | `plus` family | `minus` family | Selects |
|---|---|---|---|
| `getRecordsTo(t)` | starts at key ≤ t | ends at key ≤ t | `a ≤ t < b` |
| `getRecordsEnvelopingInclusive(t)` | starts at key ≤ t | ends at key **< t** | `a ≤ t ≤ b` |
| `getRecordsWithRangesOverlapping(f, t)` | starts at key ≤ t | ends at key **< f** | `[a,b] ∩ [f,t] ≠ ∅` |

All three are `createPrefixCountFormula(startsBound, startsInclusive, endsBound, endsInclusive)`. The walk stops at
the starts bound — keys ascend, and nothing past it can contribute to either family, which is why a query near the
start of the index is cheap and one near the end is not.

`getRecordsFrom(t)` is the exception: it walks the **suffix** instead, and swaps the families — `plus` becomes the
`ends` bitmaps and `minus` the `starts`. An entity ending at-or-after `t` more often than it starts at-or-after `t`
must have an interval straddling `t`. Same kernel, mirrored.

> The choice of *which direction to walk* is still fixed rather than adaptive. A query whose threshold sits late in
> the index scans nearly everything, where a suffix walk would have read a short tail. Doing better needs the B+
> tree to expose how many points lie either side of a key; see the ADR's open follow-ups.

## The kernel

The families arrive at `RangeCountKernel` as two `Bitmap[]`. Now the vocabulary shifts, and this is the step most
worth reading carefully:

- an **operand** is one threshold point's bitmap — `plus[i]` is the PKs that start at the i-th collected threshold
- the **contents** of every operand are entity primary keys
- everything from here on partitions **primary keys**, never thresholds

By the time the kernel runs, thresholds have already done their only job: deciding which bitmaps got passed in.

### Chunking — over primary keys

The kernel wants a counter per PK. One counter for the whole `int` PK space would be 8 GiB, so a PK is split into a
chunk and an offset:

```
  chunk  = pk >>> 16          offset = pk & 0xFFFF

  chunk 0 = PK      0 ..  65 535
  chunk 1 = PK 65 536 .. 131 071
  ...
```

Only one chunk is ever open, and its counters are a single reusable `short[65536]` — **128 KiB**, cache-resident,
indexed directly by the offset. 65 536 is also roaring's own container granularity, so a batch of ids rarely
straddles a boundary.

### The four phases

Every operand bitmap gets a cursor that yields its PKs in ascending order, in batches, carrying the sign its family
contributes (`+1` for `plus`, `−1` for `minus`). Ascending order is the property everything below depends on. Then,
for each chunk in ascending order:

1. **Take the chunk's cursors** — every cursor whose next PK falls in this chunk is already *filed* under it, so
   opening a chunk means reading one array slot and walking an intrusive list. Nothing scans the operands; see
   [driving the merge](#driving-the-merge-without-scanning-the-operands) for why that is the difference that matters.
2. **Scatter** — every cursor in the bucket drains its PKs for this chunk, doing `counters[offset] += sign` and
   appending the offset to a *touched list*, and nothing else. No comparisons between cursors, no ordering, no
   merging; just adds into an array and a sequential append. A cursor that survives the chunk re-files itself under
   the chunk its next PK belongs to.
3. **Emit** — the inputs ascend and every cursor has now moved past this chunk, so no PK in it can be touched
   again: the counters *are* the final signed differences. The touched list is walked once; each offset reads its
   counter, is zeroed, and contributes a bit to a 1 024-word bitmap when the counter is `> 0`.
4. **Hand the chunk over whole** — that word bitmap is the chunk's output in the same `long[1024]` layout the
   bitmap writer keeps internally, so it crosses as one container rather than one `add` per PK.

### A complete worked example

Same four products as above. Query: **which are valid at `t = 18`?**

`getRecordsEnvelopingInclusive(18)` walks thresholds ascending and stops at the bound:

```
  t=10   starts {3,9}    -> plus
  t=12   starts {70000}  -> plus
  t=15   ends   {9}      -> minus
  t=20   20 > 18         -> STOP

  plus  = [ {3, 9} , {70000} ]        2 operands
  minus = [ {9} ]                     1 operand
```

Three cursors open:

```
  C1  {3, 9}   +1          C2  {70000}  +1          C3  {9}  -1
```

**Round 1** — chunk **0**'s bucket holds C1 (at 3) and C3 (at 9); C2 starts at 70 000 = chunk 1 and was filed
there, so it is never looked at this round:

```
  counters[]  —  one short[65536], indexed by  pk & 0xFFFF

    C1 reads 3   ->  counters[3] += 1   ->  +1
    C1 reads 9   ->  counters[9] += 1   ->  +1
    C3 reads 9   ->  counters[9] -= 1   ->   0
                                              minLow = 3 , maxLow = 9

  emit:  counters[3] = +1  > 0   ->  0<<16 | 3  =  3        ✔
         counters[9] =  0  not>  ->  dropped                ✘
```

C1 and C3 are exhausted.

**Round 2** — chunk **1**'s bucket holds only C2:

```
    70000 >>> 16 = 1        70000 & 0xFFFF = 4464

    C2 reads 70000  ->  counters[4464] += 1  ->  +1

  emit:  counters[4464] = +1 > 0  ->  1<<16 | 4464  =  70000    ✔
```

**Result = `{3, 70000}`.** Checking against the source data: `3` → `[10..20]` contains 18 ✔, `70000` → `[12..30]`
contains 18 ✔, `9` → `[10..15]` ended at 15 ✘, `5` → `[25..40]` had not started ✘.

PK 9 is the whole design in miniature: it vanished by **arithmetic**, with no cancellation step anywhere.

### Why the scatter appends instead of setting a bit

A word bitmap written *during the scatter* would be strictly tidier: duplicates would collapse for free, the
capacity bound below would disappear, and the map would already be in the writer's layout. It was built that way
first and **measured 5× more expensive in the scatter loop's own time** — 12 µs → 69 µs at k = 64, N = 29 159 —
because `words[low >>> 6] |= 1L << low` is a random read-modify-write with a load-use dependency, where
`touched[n++] = low` is a sequential store the store buffer absorbs. Across the sweep that cost about 300 µs at
every k, which is what a per-PK cost looks like when N is held constant.

The list therefore records **every** write, duplicates included, and still needs no visited-set: the first visit to
an offset zeroes its counter, so a later duplicate reads zero and contributes nothing. Appending only on a
zero-to-non-zero transition would *not* be safe — a counter can pass back through zero mid-scatter (`+1`, `−1`,
`+1`).

### The capacity bound is not a density threshold

Emission walks the touched list, and scans the chunk's whole span only when that list **overflowed**. While the
list fits, walking it is unconditionally cheaper — at most 8 192 reads against up to 65 536 — so there is no
crossover to estimate and no constant to sweep. A chunk that overruns the bound is by construction denser than one
write per eight offsets, which is exactly the chunk a span scan handles well.

Either path gathers the survivors into a `long[1024]` bitmap rather than emitting PKs one at a time, because the
touched list is unordered while the bitmap writer requires ascending input. That gather costs nothing extra: the
words are the writer's own internal layout, so the chunk is handed over whole instead of being decomposed into
ints for the writer to re-set bit by bit. The range is cleared on the way *in*, so nothing has to tidy up after.


### Driving the merge without scanning the operands

The shape this kernel exists for is **many tiny operands**: a production range query was measured at k = 7 602
operands over N = 29 159 endpoints — 3.8 PKs per operand. At that shape the cost of *driving* the merge can dwarf
the counting, and two decisions keep it from doing so.

**Cursors are filed, not searched.** Each cursor sits in a bucket keyed by the chunk its next PK belongs to, so
opening a chunk costs one array read. A cursor only ever moves to a *higher* chunk and chunks are consumed in
ascending order, so re-filing always lands in a bucket that has not been visited yet. The alternative — sweeping
all k cursors per chunk to find the minimum and again to drain it — is `2 × k × chunks`: roughly 213 000
operations at the production shape, against ~15 000, none of which moves a record id.

**Cursor state is parallel arrays, not objects.** Every per-cursor field is a slot in a pooled primitive array, and
every cursor's batch lives in one shared arena sized to the operands, filled through the bounded
`BatchIterator.nextBatch(buffer, offset, length)`. One object per operand, each with its own batch buffer, would put
around a megabyte of small scattered objects between the kernel and 114 KB of actual data. The arrays are reused
across calls, so the steady-state allocation for cursor state is one `BatchIterator` per operand and nothing else.

### Counter width

The accumulation is deliberately allowed to wrap. Two's-complement addition is exact modulo the counter width
whatever order the operands are applied in, so an intermediate that overflows cancels back correctly — only the
**final** difference must be representable.

What bounds that difference is the **operand count**, not anything about intervals. A `Bitmap` is a set, so one PK
is scattered at most once per operand, which leaves the final value for any slot inside `[−minus.length,
+plus.length]`. `compute` therefore compares `plus.length + minus.length` against `Short.MAX_VALUE` once, before the
first scatter; at or below it the `short` kernel runs, above it an `int` sibling does.

> A tempting but **wrong** bound is "the number of intervals one entity holds inside one chunk". A chunk partitions
> primary *keys*, and an entity has exactly one key — so every interval it holds lands in that one chunk, and the
> qualifier bounds nothing. That error shipped briefly and silently dropped a valid record; see the ADR.

## Why this beats the merge it replaced

The previous implementation was `DisentangleFormula(JoinFormula(plus), JoinFormula(minus))`. `JoinFormula`
materialised a duplicate-carrying array in a k-way merge — a `PriorityQueue` poll-and-offer with a virtual call for
**every element** — and `DisentangleFormula` then cancelled the duplicates in a second merge. The duplicates were
never an output; they were only an encoding of the counts, so the kernel accumulates the counts directly and never
materialises them at all.

Measured end to end against that pair, on a production e-commerce catalog's replayed operands and on generated
shapes (one fork per cell, `-prof gc`):

| Query | Shape | Before | After | Total | Allocation |
|---|---|---|---|---|---|
| enveloping | `Product` | 483.0 µs | 36.3 µs | **13.3×** | 717.9 → 91.3 kB |
| enveloping | `wide` | 19,571.9 µs | 1,330.7 µs | **14.7×** | 9,334.8 → 1,814.4 kB |
| overlapping | `Product` | 306.0 µs | 65.5 µs | 4.67× | 703.1 → 129.1 kB |
| overlapping | `wide` | 12,290.2 µs | 1,701.6 µs | 7.22× | 7,490.6 → 1,686.6 kB |

Two stacked changes produced that, and the ADR measures them separately: the counting kernel accounts for roughly
4.5–4.9× and the prefix-count collapse — which deleted one of two kernel invocations outright — for a further ~3×.
The full three-way breakdown, the conditions, and the noise floor the numbers should be read at are in the ADR.

**Planning alone** — building the formula without computing it, which every matching index pays during planning
whether or not the result is ever used — falls 3.2–3.3×. That is the half of the win that lands on queries whose
formula the planner discards.

## Where the code lives

| Concern | Type |
|---|---|
| Endpoint storage, bound selection, the four public queries | `RangeIndex` |
| Formula node, cost model, cache identity | `RangeCountFormula` |
| Chunking, scatter, emission, cursor bookkeeping, counter width | `RangeCountKernel` |
| An independent implementation kept as a cross-check | `RangeBitSlicedKernel` |

Tests worth reading before changing any of it:

- `RangeCountKernelTest` — holds every implementation against a `TreeMap`-based reference that shares no code with
  any of them, over hand-written edge cases and randomised operand families
- `RangeIndexQueryOracleTest` — generates entities with overlapping and nested intervals, the shapes where a signed
  count and a parity fold disagree
- `RangeIndexTest` — the bound table above, case by case

## See also

- [Formula framework](../formula/formula_framework.md) — how a formula is planned, costed and cached
- [Index data structures](../indexes/data-structures.md) — where `RangeIndex` sits among the attribute indexes
- [ADR: the range index computes its signed multiplicity in one counting pass](../../adr/2026-09-18-range-index-counting-kernel.md)
