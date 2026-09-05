# RangeIndexFootprintProbe — what a catalog's range indexes actually cost

**Question.** A `RangeIndex` is backed by a `TransactionalLongBPlusTree` at a value block size of 512.
How many range points does a real index hold, and what does the index therefore occupy? No arithmetic
over the tree's shape can answer the first half — only a real catalog can.

The probe opens a catalog, walks every collection's indexes, and asks each `RangeIndex` two public
questions: `getRangePointCount()` and `getHeapSizeInBytes()`. It prints a row per entity type and a
catalog total.

## The finding

On the demo catalog, **the median range index is nearly empty and the leaf is nearly all air**:

| | |
|---|---|
| range indexes | 4,219 |
| range points | 16,876 — **4.0 per index** |
| leaf capacity | 512 slots → **0.78% occupancy** |

At 512 slots a leaf allocates `long[512]` (4,112 B) plus a `V[512]` of references (2,064 B): **6,176 B
of backing array against four live entries**, which is 78% of the 7,906 B an index reported.

## What it measured, before and after

Queue item 1 of the #1486 follow-up gave the long-keyed tree the `ColumnSizing` treatment its bucket-keyed
sibling already had. Same probe, same catalog copy, same VM layout (`reference=4B, objectHeader=12B,
arrayHeader=16B, alignment=8B`), engine built from each commit's own source:

| | `b12af5d63` (before) | `d836346f2` (after) |
|---|---|---|
| range indexes | 4,219 | 4,219 |
| range points | 16,876 | 16,876 |
| **total heap** | **31.8 MB** | **7.3 MB** |
| per index | 7,906 B | 1,810 B |
| per point | 1,976 B | 452 B |

**24.5 MB recovered, 77%.** The per-index saving of 6,096 B is exactly `long[512]` + `Object[512]` less
their four-slot replacements, so the measurement and the mechanism agree without a fitted constant
between them.

## What it deliberately does not report

It does **not** split the figure into "useful" and "wasted" bytes. That split needs a model of the
leaf's internals, and a model is what this probe exists to avoid — the #1486 campaign lost three
separate numbers to arithmetic layered on an unaudited projection. Run the probe on two commits and
subtract; the difference is the measurement.

## No reflection

Every hop is public API, which is why this probe is short and why it does not break when internals move:

- `EntityIndex` carries `@Delegate(types = AttributeIndexContract.class)`, so `getFilterIndexes()` and
  `getFilterIndex(key)` are its own methods.
- `FilterIndex#getRangeIndex()` is a Lombok getter.
- `RangeIndex#getRangePointCount()` and `#getHeapSizeInBytes()` are both public.

## Two traps this probe already fell into

**The catalog loads asynchronously.** Reading `getCatalogInstance` too early hands back an
`UnusableCatalog` and a `ClassCastException`. `awaitLoaded` polls, and short-circuits on `CORRUPTED`
rather than waiting out the timeout — a `CORRUPTED` catalog usually means `probe.compress` disagrees
with how the snapshot was written.

**The classpath is the measurement.** A first run reproduced the baseline *exactly* while measuring a
patched engine, because `dependency:build-classpath` resolved `evita_engine` from the local repository
instead of the worktree. Prepend every reactor `target/classes` ahead of the resolved classpath, and
treat an unchanged number as a suspect rather than a confirmation:

```shell
REACTOR=$(find . -maxdepth 4 -type d -path '*/target/classes' -not -path './.git/*' \
  | sed 's|^\./||' | sort | tr '\n' ':')
java -Xmx8g -cp "${REACTOR}$(cat /path/to/cp.txt)" \
  -Dprobe.catalog=evita -Dprobe.dataDir=/path/holding/the/catalog/folder \
  io.evitadb.spike.trigram.RangeIndexFootprintProbe
```

`probe.copyData` (default `true`) copies the catalog to a scratch directory so a run never mutates the
snapshot and two runs see byte-identical input. `probe.compress` (default `true`) must match the
snapshot.

## Where to point it next

`TransactionalElementBPlusTree` allocates its leaf the same way (`Array.newInstance(elementType,
blockSize)`) and backs `PriceListAndCurrencyPriceSuperIndex` / `...RefIndex`. A sibling probe over the
price indexes is the obvious next measurement, and on a price-heavy catalog it may be worth more than
this one. `TransactionalObjectBPlusTree` (traffic recording) shares the defect at lower stakes.

## Related

- [`ValueDedupCensus`](ValueDedupCensus.md) — the census that priced the *other* value-tree lever, and
  whose catalog walk this probe borrows.
