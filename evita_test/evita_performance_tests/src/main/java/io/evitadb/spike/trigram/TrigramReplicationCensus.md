# TrigramReplicationCensus — where attribute-index heap actually sits

**Question.** Of a catalog's attribute-index heap, how much is in the one `GLOBAL` index per
collection, and how much is replicated across the reduced indexes that copy its values?

The answer decides whether hosting the trigram index globally is a saving or a restriction, and it is
the measurement the value-representation follow-up
([#1486](https://github.com/FgForrest/evitaDB/issues/1486)) rests on.

## Why the numbers come from where they do

**Enumeration goes through the management surface; measurement does not.** Indexes are listed with
`EntityCollection#browseIndexes` in `MAP_ORDER` — the ordering documented as the cheap exhaustive
walk. That matters for a census: index primary keys are sparse, so scanning keys upwards until
`getIndexCount()` of them have been found can silently stop short, and a census that misses indexes
answers the wrong question.

The heap figures cannot come from the same place, because the browse surface carries no memory reading
and `IndexDetail` carries only the index total. Each browsed row is therefore resolved back to its
live `EntityIndex` and measured against the engine objects directly.

**One reflective read.** `AttributeIndex#getHeapSizeInBytes()` is the authoritative figure but the
field holding it is `protected` with no accessor. It is read reflectively rather than re-derived from
the public per-family accessors, because re-deriving understates by ~7% — it misses the map spines, the
key objects and the persisted leaf-page snapshots. A census whose headline drifts from the engine's own
accounting is worse than no census.

## Conclusions it produced

| corpus | attribute heap | in the global index | replicated across reduced indexes | reduced indexes |
|---|---|---|---|---|
| production CMS catalog (972,611 articles) | 749.0 MB | 99.8% | **0.2%** (1.5 MB) | 2,388 |
| e-commerce corpus A | 88.0 MB | 35.8% | **64.2%** (56.5 MB) | 6,317 |
| e-commerce corpus B | 169.8 MB | 3.7% | **96.3%** (163.5 MB) | 20,835 |

Three findings, none derivable from an aggregate:

**Global-only hosting is validated rather than assumed.** A per-reduced trigram structure would have
multiplied by more than twenty thousand on corpus B. The decision to host at the global level was taken
*before* this was measured; the measurement is what makes it defensible.

**Attribute data is 59–98% of all index heap** across the three shapes, so this line of work targets the
dominant term rather than a corner of it.

**The value-duplication problem is entirely a function of reference fan-out.** A CMS shape does not have
one — 1.5 MB out of 749 MB — while a fan-out shape has almost nothing else. Any future deduplication has
to be justified per corpus shape, never in general. That conclusion is what
[`ValueDedupCensus`](ValueDedupCensus.md) was built to act on, and it is why the CMS column of every
table in #1486 reads zero.

## Why it is still live

The fan-out share is a property of the catalog, so a new catalog needs a new census. It is also the
cross-check [`ValueDedupCensus`](ValueDedupCensus.md) computes itself against — the two definitions of
"reduced value trees" differ exactly by the reference-type indexes, which this census measured at
≤ 0.2% of attribute heap.

## Running it

Takes the same properties as [`TrigramCorpusExtractor`](TrigramCorpusExtractor.md), so one command
line serves both — `catalogName`, `dataDir`, `workDir`, `copyData`. Needs the same
`--add-opens` set, since it boots an embedded Evita.

## Related

- [ADR §"The replication census"](../../../../../../../../../documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/README.md) — where these figures are recorded.
- [Issue #1486](https://github.com/FgForrest/evitaDB/issues/1486) — the follow-up this motivated.
