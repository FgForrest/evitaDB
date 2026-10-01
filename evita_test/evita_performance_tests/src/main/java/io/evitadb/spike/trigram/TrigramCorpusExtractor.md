# TrigramCorpusExtractor — the corpus dump

**Question.** What string values does a real catalog actually hold, in a form that can be analysed
many times without paying to boot the catalog again? And, since the fulltext line was added to it:
**which fields hold them, and how are those fields stored?**

## Why it exists

Booting a production-shaped catalog costs minutes. Every analysis that repeats that boot is an
analysis that does not get run, so the corpus is extracted **once** into a flat TSV and every other
tool in this package reads that file instead of a catalog.

```text
entityType TAB attributeName TAB locale TAB entityPrimaryKey TAB value
```

One line per **occurrence**, not per distinct value — `N` and `V` are both derivable, and the
`N/V` reuse factor the whole value-id argument rests on is exactly their ratio. Array-typed
attributes contribute one line per element, because that is how the filter index sees them.

## Two modes

`-Devita.trigram.mode=census` boots the catalog, prints every text-bearing field it holds — attributes
and associated data, with type, localization and indexes — and writes nothing. `extract` (the default)
writes the TSV.

The census is not a convenience. The allow-lists the extraction takes **cannot be written without it**:
the default sweep sees only filter-indexed `String` attributes, so on any catalog whose long text lives
elsewhere, the fields the fulltext line needs are precisely the ones no run has ever listed. It is the
instrument that answers P1's question Q10 — *is the CMS article body stored, and as what?* — which
gates step K1.

## Two decisions inside it that the numbers depend on

**The extracted set is wider than "filterable".** Every attribute whose plain type is `String` and
which carries a filter index in some scope is dumped — `filterable`, but also `unique` and
`unique within locale`, because uniqueness implies filterability and therefore a `FilterIndex`
that `attributeContains` could be accelerated against. A `sortable`-only attribute is excluded: a
`SortIndex` answers no substring predicate. This is the difference between extracting the
identifier-shaped attributes that turned out to be the interesting ones and silently missing them.

**Naming a field opts it in whatever its index status — and that is the K1 widening.** The sweep above
is right for the substring line and blind for the fulltext one: a fulltext field is as a rule *not*
filter indexed, because nobody filters by a long description, so the very fields whose memory and scan
cost P1 exists to measure are the ones the sweep cannot see. An explicitly named `String` attribute is
therefore extracted regardless of its indexes, and named **associated data** with it — the CMS profile
is the case that forced it, its article body not being among the filter-indexed attributes the P8
census saw.

Associated data is never swept: an empty list means *none*, not *all*. It carries no index to narrow a
sweep by, and a blanket one would pull in image descriptors and layout payloads. A `ComplexDataObject`
field can be named but not extracted — the run refuses and names the type, because which leaf of a
complex object is the body text is a question about the document model, not about extraction.

Associated-data values are written under a `$`-prefixed field name, so an attribute and an associated
data sharing a name stay two distinct fields downstream. A classifier can never begin with `$`, so the
prefix cannot collide with a real name.

**Values are written raw, never normalized.** Normalization is the analyser's business, and the
analyser has to be able to measure *several* normalizations of the same corpus — NFD as stored today,
and the case-folded form [#545](https://github.com/FgForrest/evitaDB/issues/545) would store. A corpus
that had already been folded could not answer that question.

**The catalog is copied before boot** by default. Opening a snapshot in place is not read-only in the
sense that matters: boot replays the write-ahead log and the storage layer may compact, so the folder
that was measured would no longer be the folder the next run measures. `evita.trigram.copyData=false`
opts out for a snapshot that is already disposable.

## Running it

```shell
java -Xmx8g \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.lang.invoke=ALL-UNNAMED \
  --add-opens java.base/java.math=ALL-UNNAMED \
  --add-opens java.base/java.util=ALL-UNNAMED \
  -Devita.trigram.catalogName=demo \
  -Devita.trigram.dataDir=/path/to/snapshot \
  -Devita.trigram.workDir=/path/to/work \
  -Devita.trigram.corpusFile=/path/to/demo-corpus.tsv \
  -cp evita_test/evita_performance_tests/target/benchmarks.jar \
  io.evitadb.spike.trigram.TrigramCorpusExtractor
```

`entityTypes`, `attributes` and `associatedData` select what is dumped; everything else defaults. The
full property table is in the class JavaDoc. A census run needs no `corpusFile`.

## Conclusion

No verdict of its own — it is the input stage. It is kept because **every other corpus instrument
depends on its output format**, and because it now serves both lines it was written for: the trigram
substring index (P8, shipped) and the fulltext core (P1, step K1). The class JavaDoc notes it was
written to serve both from the start.

## Related

- [`TrigramCorpusStatistics`](TrigramCorpusStatistics.md) — the first consumer of the corpus.
- [ADR: fulltext search in evitaDB](../../../../../../../../../documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/README.md) — the line of work this serves.
