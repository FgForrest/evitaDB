# CatalogIndexFootprintCensus — which index structure is worth optimizing first

**Question.** [`RangeIndexFootprintProbe`](RangeIndexFootprintProbe.md) and
[`PriceIndexFootprintProbe`](PriceIndexFootprintProbe.md) each price **one** family, which answers "is this port
worth doing" but never "which port is worth doing first". A memory campaign needs the second answer, on a real
catalog, before it spends a night on the wrong structure.

## Why the engine's own statistics could not answer it

The management API stops one step short. `EntityCollection#describeIndex` returns an
`IndexDetail` carrying a single `heapSizeInBytes` per **named** index, and by design it does not decompose that
figure — the [statistics ADR](../../../../../../../../../documentation/adr/2026-08-10-catalog-and-collection-statistics/README.md)
records the decision that an exact heap figure is reached only by naming one index, precisely so a browse page
cannot cost 200 ms nobody asked for. There is no per-structure breakdown to reuse, so this census walks one.

## What it charges exactly, and what it refuses to guess

Every family is charged by asking the structure's own `getHeapSizeInBytes()` — the same accounting `IndexDetail`
reports and the one `EntityIndexHeapSizeTest` cross-checks against a JOL walk. Nothing here models a layout.

Some of an entity index's state has no public accessor: the hierarchy index, the entity-id bitmaps, the
`ReferencedTypeEntityIndex` cardinality and histogram state, the `FacetIndex` shell above its per-reference
indexes, and the `AttributeIndex` map scaffolding that [`AttributeIndexScaffoldingProbe`](AttributeIndexScaffoldingProbe.md)
prices separately. None of it is estimated. The census sums `EntityIndex#getHeapSizeInBytes()` independently and
prints the difference as one **residual** row — a subtraction between two measured quantities, which is the only
honest thing to say about a part the public surface cannot reach.

## The finding

Measured on a production e-commerce catalog (18 collections, 564,187 entity indexes, 33.8 M price-record
references), at `-Xmx24g`, `VMLayout{reference=4B, objectHeader=12B, arrayHeader=16B, alignment=8B}`:

| family | distinct | heap | share | B / instance |
|---|---:|---:|---:|---:|
| residual (unreached) | 564,187 indexes | 1,970.4 MB | 31.0 % | 3,662 |
| price ref index | 283,002 | 1,296.0 MB | 20.4 % | 4,801 |
| price super index | 273 | 946.4 MB | 14.9 % | 3,635,167 |
| attribute sort | 294,152 | 836.1 MB | 13.2 % | 2,980 |
| attribute value tree | 600,815 | 615.6 MB | 9.7 % | 1,074 |
| attribute chain | 33,382 | 297.1 MB | 4.7 % | 9,330 |
| facet reference index | 26,865 | 194.9 MB | 3.1 % | 7,606 |
| attribute range | 92,229 | 167.6 MB | 2.6 % | 1,905 |
| attribute filter | 600,815 | 27.5 MB | 0.4 % | 48 |
| attribute unique | 27,100 | 1.0 MB | 0.0 % | 40 |
| **entity index total** | **564,187** | **6,352.7 MB** | **100 %** | 11,806 |

Catalog-level global-unique indexes add 22.4 MB on top (17.6 MB live, 4.8 MB archived). The entity-index
accounting explains **85.5 %** of the 7,428.6 MB the JVM still held after the load and two collections.

**Nothing is shared between entity indexes on this catalog** — every family reports a 1.00x
distinct-to-referenced ratio, so the double-counting machinery cost nothing here and confirmed rather than
corrected the naive sum. That is a property of this catalog, not a guarantee.

**The largest single row is the residual**, and it is four times the 366 MiB floor
[`AttributeIndexScaffoldingProbe`](AttributeIndexScaffoldingProbe.md) measured for the attribute-index maps. The
rest of it is the hierarchy index, the per-index entity-id bitmaps, the reference-type cardinality and histogram
state and the facet shell — none of which any probe has yet priced separately, and which sit almost entirely in
the three high-fan-out collections (Product 1,069.4 MB, ParameterValue 530.4 MB, Category 282.6 MB).

**The price records themselves are shared 5.96x.** 33,806,439 references resolve to **5,673,881 distinct
instances**, every one of them a plain `PriceRecord` — this catalog uses no inner-record price handling at all, so
`PriceRecordInnerRecordSpecific` never appears. Any arithmetic that priced price bodies at 33.8 M objects was
counting reference slots, not objects.

## The decomposition phase

`probe.decompose` (default on) splits the four largest rows into the components their own `getHeapSizeInBytes()`
charges. A row is **exact** when an accessor handed out the very instance the index charges; a row marked
`(inferred)` is the implementation's arithmetic applied to a publicly observable count. Every table ends with an
`other (unreached)` row, which is a subtraction between two measured quantities.

Two of its readings are worth knowing before running it again:

**The entity index has four implementations, not three.** `ReducedGroupEntityIndex` is a sibling of
`ReducedEntityIndex` under `AbstractReducedEntityIndex` and declares six extra reference fields plus its own
cardinality, referenced-primary-key and histogram state. The census switches over all four and **throws** on a
fifth rather than charging it the base's field count, which is how this one was found: the first decomposition run
failed on it instead of silently understating its shell.

## The three accounting hazards it is built around

**Shared instances.** A reduced entity index's `PriceRefIndex` and a collection's super index hold the same price
records; a filter view and its value tree are reached from every index that borrows them. Every structure is
folded through an `IdentityHashMap` and charged once, and the reference count is printed beside the distinct
count so the sharing ratio is visible rather than assumed.

**Two totals, used for two different things.** The Pareto column reports **distinct** bytes, because that is the
heap really occupied. The residual arithmetic uses **referenced** bytes, because that is what the entity-index
figures it is subtracted from actually charge. Mixing the two makes the residual meaningless.

**An owner filter index is not a view.** `OwnerFilterIndex#getHeapSizeInBytes` charges its value tree and range
index inside its own figure; `FilterIndexView` charges neither. The census tells them apart by type and charges
the tree and range index again only for a view. A third implementation reaching that branch throws rather than
being folded into whichever arm comes first — getting this wrong moves hundreds of megabytes between two rows of
one table without changing the total, which is exactly the kind of error a total cannot catch.

## The price-record census that rides along

The same walk answers a second question: how many **distinct** `PriceRecordContract` instances the catalog holds
against how many times they are referenced, split by concrete class (`PriceRecord` carries five `int`s,
`PriceRecordInnerRecordSpecific` six). That is the input to any columnar-layout arithmetic over price bodies, and
it cannot be inferred from the index counts. Only a **first-sighted** price index contributes its records, so a
shared price index does not inflate the reference count with references that do not exist.

It costs an identity map over tens of millions of entries. `probe.priceRecords=false` switches it off.

## Running it

```shell
java -Xmx24g -cp "${REACTOR}$(cat cp.txt)" \
  -Dprobe.catalog=<name> -Dprobe.dataDir=/path/holding/the/catalog/folder -Dprobe.copyData=false \
  io.evitadb.spike.trigram.CatalogIndexFootprintCensus
```

Same properties and the same two warnings as the sibling probes. Prepend every reactor `target/classes` ahead of
the resolved classpath, or the run measures whichever build the local repository holds. **Keep `-Xmx` below
32 GB** — above it compressed oops turn off, every reference becomes 8 bytes, and the figures stop being
comparable with every other number in this package.

The JVM used-heap line is taken after the load and two collections but **before** the walk, because the walk's own
identity maps are hundreds of megabytes and would otherwise be reported as catalog residency.

## Related

- [`RangeIndexFootprintProbe`](RangeIndexFootprintProbe.md) — the browse walk this borrows, and the one family it
  priced first.
- [`PriceIndexFootprintProbe`](PriceIndexFootprintProbe.md) — the sibling whose identity-folding this generalizes.
- [`AttributeIndexScaffoldingProbe`](AttributeIndexScaffoldingProbe.md) — prices part of what this census reports
  as residual. The two are complementary and must not be summed carelessly.
