# AttributeIndexScaffoldingProbe — what an entity index costs before it holds anything

**Question.** Every `AttributeIndex` allocates twelve maps — seven sub-index maps and five persisted
leaf-page snapshots — the moment it is constructed, whatever the index goes on to hold. On a catalog
with hundreds of thousands of reduced indexes, what does that scaffolding cost, and how much of it is
allocated-but-empty?

The follow-up this prices had been carried as "~19 MB, estimated". It is not 19 MB.

## The two measured inputs

**An empty `AttributeIndex` reports 680 B**, and reports it identically for `GlobalEntityIndex`,
`ReducedEntityIndex` and `ReferencedTypeEntityIndex` — all three allocate the same twelve maps
regardless of type. Measured directly against the engine's own accounting; `EntityIndexHeapSizeTest`
cross-checks that shape against a JOL walk, so the figure is not self-referential. Decomposed against
the arithmetic in `AttributeIndex#getHeapSizeInBytes`: **80 B** for the object itself
(`sizeOfObject(long + 14 refs)`), leaving **600 B over seven maps ≈ 86 B per empty map**. The five
`persisted*LeafPages` snapshots are already free — an empty one parks on a shared `Map.of()`.

**The index count comes from the same browse walk the sibling probes use**, `EntityCollection#browseIndexes`
in `MAP_ORDER`, for the reason [`RangeIndexFootprintProbe`](RangeIndexFootprintProbe.md) gives: index
primary keys are sparse, so scanning keys upward until `getIndexCount()` hits can stop short.

## The finding

Measured on a production e-commerce catalog (18 collections, 564,187 entity indexes):

| | |
|---|---|
| entity indexes | **564,187** |
| empty-attribute-index floor | **365.9 MiB** (564,187 x 680 B) |
| observable family slots used | 803,827 |
| observable family slots **empty** | **1,452,921 — 64.4 %** |
| indexes carrying no attribute index at all | 16,104 (2.9 %) |

Concentrated where the reference fan-out is: Product 281,784 indexes, ParameterValue 161,980,
Category 87,934, Parameter 29,088.

**What is measured and what is not.** The 366 MiB floor is the product of two measured quantities and
is not a model. The **recoverable** share is smaller and partly inferred: a populated map is needed, so
only the empty ones are waste. If the observed 64.4 % empty ratio holds across all seven maps —
**an inference, since only four are observable through the public contract** — lazy allocation is worth
on the order of **218 MB**, plus **11 MB** from the 16,104 indexes whose attribute index is entirely
empty and could drop all 680 B.

`sharedValueIndex`, `sharedRangeIndex` and `uniqueViewIndex` have no accessor on
`AttributeIndexContract`, which is why the percentage is reported over four rather than seven. The
probe prints that caveat itself rather than leaving it to a reader.

## What it deliberately does not do

**It does not reflect.** The sibling `TrigramReplicationCensus` reads the `protected attributeIndex`
field reflectively and documents why; this probe does not need to, because the per-index constant is
obtained from a directly constructed empty index rather than from a live one, and the multiplier comes
from the public browse surface. The cost is that the constant is pinned in source rather than
re-measured per run — if `AttributeIndex` gains or loses a field, `EMPTY_ATTRIBUTE_INDEX_BYTES` must be
re-measured.

**It does not price the fix.** Every one of those maps is a `TransactionalMap` or
`PersistentTransactionalProducerMap` wired into the MVCC layer. Null-until-first-use means every read
site and the commit-merge has to handle absence, and nothing here has scoped that.

## Running it

Same properties and the same classpath warning as the sibling probes — prepend every reactor
`target/classes` ahead of the resolved classpath, or the run silently measures whichever build the
local repository holds.

```shell
java -Xmx24g -cp "${REACTOR}$(cat cp.txt)" \
  -Dprobe.catalog=<name> -Dprobe.dataDir=/path/holding/the/catalog/folder -Dprobe.copyData=false \
  io.evitadb.spike.trigram.AttributeIndexScaffoldingProbe
```

**Keep the heap under 32 GB.** At 32 GB the VM turns compressed oops off, every reference becomes 8 B,
and the `VMLayout` line the probe prints stops matching the one the figures assume. 24 GB loads a
3.3 GB catalog in about 25 s with room to spare.

## Related

- [`RangeIndexFootprintProbe`](RangeIndexFootprintProbe.md) — the browse walk this borrows.
- [`PriceIndexFootprintProbe`](PriceIndexFootprintProbe.md) — the sibling that priced the price indexes.
- [`TrigramReplicationCensus`](TrigramReplicationCensus.md) — measures the *contents* of these maps;
  this one measures the maps themselves. The two are additive and must not be summed carelessly: the
  census counts payload, this counts the scaffolding the payload sits in.
