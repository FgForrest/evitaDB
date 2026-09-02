# ValueDedupCensus — what a different value-tree representation would save

**Question.** One semantic domain at a time: if this domain's reduced value trees were replaced by
exact-sized arrays, how many bytes would actually be saved — measured current bytes against a
byte-exact projection of the candidate representation?

A **domain** is `(entityType, scope, AttributeIndexKey, kind)`. The census walks a real catalog and
emits a per-domain, tree-size-stratified decision table as TSV.

## The finding that reshaped the design

Reduced entity indexes replicate an `InvertedIndex` value tree per (index × attribute). The replicated
heap turned out **not** to be mostly key payload — it is per-tree B+ scaffolding: tree and node objects,
leaf key columns allocated at full block capacity (256 slots) while the median tree holds 1–4 values,
one bucket wrapper per value, version bookkeeping.

The extreme case is the clearest: one Boolean attribute holding **two distinct values** costs
**677.3 MB across 157,408 reduced indexes**, and keeping the keys exactly where they are while removing
only the scaffolding recovers **97.7%** of it.

That splits the problem into two *independent* levers, which must never be summed per domain:

| lever | what it does | production e-commerce catalog | production CMS catalog (972k articles) | demo dataset |
|---|---|---|---|---|
| **container** | exact-sized arrays, keys kept in place | **+1.55 GB (36.0% of reduced attribute heap)** | 0 B | +58.4 MB (35.5%) |
| **dictionary** | hoist string keys to a canonical owner, leave 4-byte ids | +502.7 MB headline, but only **+128.2 MB over the container** | 0 B | +0.2 MB marginal |

The trigram opt-in set costs ~66 MB on the e-commerce catalog; the container lever alone over-balances
it roughly 20×. The CMS zero is not a measurement gap — that shape has no reference fan-out and
therefore no reduced value trees at all, which
[`TrigramReplicationCensus`](TrigramReplicationCensus.md) had already established.

## Three results that are easy to get wrong

**Ranges are container-eligible, and a range dictionary is not worth building.** A range key has no
primitive leaf column today and is stored as a full boxed object graph, but its entire comparison
identity is already two `long` fields. Priced as a `(from[], to[])` pair: five range domains, 87,099
reduced trees, **+242.4 MB net, 96.3% of removable**. A range *dictionary* over the same runs was
rejected at a **0.79% marginal** even at replication r = 82,521 — once scaffolding is gone, shrinking a
16-byte key to a 4-byte id has nothing left to take. Do not revisit without new evidence.

**Reference-level sort-only attributes have no canonical owner anywhere.** The owner-resolution chain
originally consulted only the GLOBAL index, which by construction holds no sort tree for a reference
attribute, so every such domain reported MISSING regardless of catalog health. The fix appends a
reference-type resolution step — and on three corpora it fires **zero times**. That zero is the finding:
the dictionary lever has no host for these domains.

**The engine's own `getHeapSizeInBytes()` under-reports by key type** — Boolean by 1.76%,
`DateTimeRange` by 3.08% — so the real prizes are slightly *larger* than the engine self-reports. That
is a separate defect, not a census artefact.

## Why it is still live

Acceptance criterion 3 of [#1486](https://github.com/FgForrest/evitaDB/issues/1486) requires re-running
this census on a production-shaped dataset to confirm that **≥ 90% of the projected recovery actually
materializes in live heap**. It is the gate, not just the motivation.

## Running it

Takes the same properties as [`TrigramCorpusExtractor`](TrigramCorpusExtractor.md) — `catalogName`,
`dataDir`, `workDir` — plus one of its own. Keep `-Xmx` **below 32 GB** when comparing to the figures
above: they assume the compressed-oops regime. Three private/protected engine fields are read
reflectively, for the same reason the replication census does it: a spike may not edit the engine.

A cross-check against the replication census's own definition of "reduced value trees" is computed
**inside** the run rather than by re-running that census — the same walk accumulates both definitions,
which differ exactly by the reference-type indexes.

## Related

- [`ValueDedupRepresentationSpike`](ValueDedupRepresentationSpike.md) — validates the projection models
  this census prices with, byte-exactly.
- [`ValueDedupReadBenchmark`](ValueDedupReadBenchmark.md) — the read-path half of the verdict.
- [Issue #1486](https://github.com/FgForrest/evitaDB/issues/1486) — the design this produced.
