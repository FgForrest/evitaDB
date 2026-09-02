# Trigram and value-dedup measurement tooling

This package holds the measurement instruments behind the trigram substring index
([#1454](https://github.com/FgForrest/evitaDB/issues/1454)) and the value-representation follow-up
([#1486](https://github.com/FgForrest/evitaDB/issues/1486)). Everything here is run **by hand**
against a named catalog snapshot or corpus file; nothing in the build or in CI depends on any of it.

## What is kept here, and what is not

A spike earns a place in the tree only when it can still be **re-run against new input** — a new
catalog, a new corpus, a new engine build — and produce an answer somebody will act on. A spike that
answered a fork once, where the fork is now settled and shipped, is not kept: its conclusion lives in
the decision record and its code would only rot against the engine it no longer resembles.

Nineteen prototype classes were removed under that rule when the index shipped — the in-memory
`TrigramSpikeIndex` / `TrigramMutableIndex` pair, the B3 value-dictionary variants, the B4/B6 query
matrix over them, and the B5 update micro-bench. Their conclusions are recorded in
[`p8-trigram-substring-index.md` §35](../../../../../../../../../documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/prototypes/p8-trigram-substring-index.md),
and the ADR header above §35 records which of them the implementation later reversed. Read that
before re-deriving anything they measured.

**The package is git-ignored.** A new file here stays out of git until somebody force-adds it
(`git add -f`), which is the moment to ask whether it belongs in the tree at all. The files below were
added that way, deliberately.

## The instruments

Each has a companion `.md` beside it stating the question it answers, how to run it, what it measured
and what was concluded.

| instrument | answers | still live because |
|---|---|---|
| [`TrigramCorpusExtractor`](TrigramCorpusExtractor.md) | dumps every filter-indexed `String` attribute of a real catalog as a TSV corpus | every other corpus tool reads its output; the fulltext core (P1) is unimplemented and will need it |
| [`TrigramCorpusStatistics`](TrigramCorpusStatistics.md) | what would a trigram index over this corpus cost? | the per-attribute opt-in decision is per catalog, so this is re-run for every new one |
| [`TrigramReplicationCensus`](TrigramReplicationCensus.md) | where does attribute-index heap actually sit — global index or reduced ones? | the fan-out share drives #1486 and differs per catalog shape |
| [`TrigramPostingStoreSpike`](TrigramPostingStoreSpike.md) | which key map, and at what cardinality does `int[]` beat Roaring? | the `T = 128` threshold is open under [#1455](https://github.com/FgForrest/evitaDB/issues/1455) and its latency half was never measured on a quiet box |
| [`TrigramArmSweep`](TrigramArmSweep.md) | does the **shipped** accelerator beat the scan it displaces, and is the gate set right? | measures production code against real corpora; the only instrument that can re-derive the gate constant |
| [`ValueDedupCensus`](ValueDedupCensus.md) | what would replacing small value trees with exact arrays save? | #1486 acceptance criterion 3 requires re-running it against the projection |
| [`ValueDedupRepresentationSpike`](ValueDedupRepresentationSpike.md) | are the census's byte projections real? | validates the model any future change to it must still satisfy |
| [`ValueDedupReadBenchmark`](ValueDedupReadBenchmark.md) | does the array container regress the read path? | #1486 acceptance criterion 4 |

Three support classes carry no `.md` of their own because they measure nothing — they are structures
the instruments above are built from: `SpikeTrigramCodec` (the 63-bit trigram packing and the NFD
normalization contract, named apart from the engine's own `TrigramCodec` it was cloned from), `TrigramKeyIndex` and `TrigramOrdinalMap` (the primitive-keyed maps
`TrigramPostingStoreSpike` compares and builds with).

## Running anything here

All of it is driven by system properties and needs the shaded benchmark jar:

```shell
mvn -P full -pl evita_test/evita_performance_tests -am package -DskipTests
```

The `--add-opens` flags in each class's *Running it* section are the ones the module's shade manifest
declares; an embedded Evita fails to boot without them because Byte Buddy generates classes
reflectively during startup. Keep `-Xmx` **below 32 GB** whenever comparing against published heap
figures — above it the JVM leaves the compressed-oops regime and every object grows by ~9%.

## Reading the numbers in the companion documents

Two caveats travel with every figure recorded here, and dropping either makes the numbers wrong
rather than merely imprecise:

- **Latency was often measured on a shared box.** Where that is so the document says it. Ratios
  between two arms measured in one interleaved run survive it; absolute µs values are upper bounds.
- **Heap figures are JOL deep-retained deltas against an empty structure of the same type**, in the
  compressed-oops regime, and are 1.1×–3.2× the serialized size depending on container density. State
  budgets in heap, never in serialized bytes.
