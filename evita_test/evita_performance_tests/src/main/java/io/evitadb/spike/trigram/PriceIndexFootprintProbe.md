# PriceIndexFootprintProbe — what a catalog's price indexes cost

**Question.** Every `PriceListAndCurrencyPriceIndex` owns a `RangeIndex` of price validity and a
`TransactionalElementBPlusTree` of price records. The element tree allocates
`Array.newInstance(elementType, blockSize)` per leaf regardless of occupancy — the same defect the
long-keyed tree had. Is it worth porting?

Sibling of [`RangeIndexFootprintProbe`](RangeIndexFootprintProbe.md), same method: open a real catalog
and ask the engine's own accounting.

## Two findings, and the second one is a "no"

**1. The long-tree content sizing already saved 25.0 MB here, invisibly.** Each price index owns a
`RangeIndex`, which no filter index points at — so
[`RangeIndexFootprintProbe`](RangeIndexFootprintProbe.md) never counted it and the 24.5 MB it reported
was **less than half** the change's true effect on this catalog.

| demo catalog | `b12af5d63` (before) | `d836346f2` (after) |
|---|---|---|
| distinct price indexes | 4,292 | 4,292 |
| price records | 435,580 | 435,580 |
| **total heap** | **56.4 MB** | **31.4 MB** |
| per record | 135 B | 75 B |

Added to the 24.5 MB the filter-side range indexes gave up, the one commit is worth **≈49.5 MB on the
demo catalog**. The two sets are disjoint objects.

**2. The element tree is NOT the obvious next prize — do not port it on this evidence.** Its block size
is **64**, and the catalog holds 435,580 records across 4,292 indexes: a mean of ~101 records, i.e.
roughly two full leaves. That is nothing like the range tree's **0.78%** occupancy, which is what made
that port pay 77%.

The mean is not the whole story — 96 of the indexes are super indexes and 4,196 are reference indexes
that borrow their records, so the records are certainly not spread evenly and some ref indexes may hold
very few. **But nothing here establishes that, and the remaining 7,316 B per index is not obviously leaf
over-allocation.** Anyone picking this up needs a per-index occupancy histogram first, not another
estimate.

## The double-counting hazard the probe is built around

**A reduced entity index's `PriceRefIndex` stores the very instances the collection's super index
holds.** Summing naively across entity indexes counts the same bytes many times and yields a figure
that grows with the number of reduced indexes rather than with the data. Everything is folded through
an `IdentityHashMap` first, and the probe prints distinct-vs-references so the ratio is visible rather
than assumed. On the demo catalog it is **1.0x** — each entity index holds its own instance — but that
is a property of this catalog, not a guarantee.

## Running it

Identical to [`RangeIndexFootprintProbe`](RangeIndexFootprintProbe.md): same `probe.*` properties, and
the same warning about the classpath. Prepend every reactor `target/classes` ahead of the resolved
classpath, or the run silently measures whichever build the local repository holds.

## Related

- [`RangeIndexFootprintProbe`](RangeIndexFootprintProbe.md) — the sibling, and the reason this one
  exists.
