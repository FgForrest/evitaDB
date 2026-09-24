# TrigramCorpusStatistics — what a trigram index over this corpus would cost

**Question.** For each `entityType / attributeName / locale` group in a corpus: how big would a
trigram index over it be, and is it worth flagging?

This is the instrument the per-attribute opt-in decision is made with. It is re-run for **every new
catalog**, because the answer is a property of the corpus and not of evitaDB.

## What it computes

Everything is measured on a structure that was really built — never estimated from a formula.

- `N` (occurrences), `V` (distinct normalized values) and the reuse factor `N/V`;
- value-length percentiles in Unicode code points, over the **distinct** values, because it is `V`
  values and not `N` occurrences that the index stores;
- `U` (distinct trigrams per value), `K` (distinct trigram keys), `E` (trigram-to-value memberships);
- the posting-cardinality distribution, which decides whether a small-posting representation is worth
  having (see [`TrigramPostingStoreSpike`](TrigramPostingStoreSpike.md));
- **both index variants, really built**: `A` = `trigram → RoaringBitmap<entityPK>` and
  `B` = `trigram → RoaringBitmap<valueId>`, each measured for serialized size, Roaring container mix
  and JOL deep-retained heap;
- the **case-fold delta**: what a locale-aware fold would merge away
  ([#545](https://github.com/FgForrest/evitaDB/issues/545)).

Postings are `PersistentRoaringBitmap` — the vendored copy-on-write implementation the engine really
holds — so the heap figure includes the per-bitmap `shared[]` flag array a production posting carries.
The container mix is **parsed out of the serialized bitmap header**, not inferred, so nothing here can
disagree with Roaring's own container-selection rules.

## Conclusions it produced

**The per-attribute memory table is the whole argument for a per-attribute capability.** On a
production CMS catalog (972,611 articles), variant B, heap by JOL deep-retained walk:

| attribute shape | N/V | heap | A/B serialized | verdict |
|---|---|---|---|---|
| article title (cs) | 1.03 | 158.8 MB | 1.02× | the attribute one would actually flag |
| keywords | 17.5 | 21.1 MB | **8.27×** | value-id compression works |
| authors | 134 | 2.6 MB | **20.99×** | value-id compression shines |
| url / path | ~1.0 | ~160 MB each | 1.01× | expensive — do not flag |
| content hash | 1.0 | 138.7 MB | 1.00× | a hex hash; never flag |
| category / section names | ~1.0 | ~9 MB total | ~1.00× | cheap |

The realistic opt-in set is **~184 MB**; flagging everything would be **743 MB**, almost all of it
wasted on hashes, ids and URLs. The aggregate cannot substitute for this table — it does not say which
attribute is which.

**`A/B` is bounded by `N/V` sub-linearly and is never equal to it.** Measured: 134 → 21×,
17.5 → 8.3×, 8.1 → 4.0×, ~1.0 → 1.00–1.14×. Any cost model predicting the ratio from `N/V` alone is
wrong in both directions. The residual advantage at `N/V ≈ 1` comes from dense value ids producing
fewer Roaring containers than sparse entity primary keys.

**Case folding collapses the key space, not the value space** — the opposite emphasis to the one the
research brief assumed. Distinct values fall 0.5% and memberships 0.1%, while trigram keys fall 18.5%
overall and 35.5% on the title attribute. Still a net saving, never a loss, but the benefit lives in
the keys.

**Heap is 1.1×–3.2× serialized** depending on container density, so a budget stated in serialized
bytes understates by up to three times.

**One catalog's shape is not a universal ratio.** The same measurement on two e-commerce corpora
totals 10.5 MB and 2.4 MB serialized, at very different `N/V`. This is why the instrument is kept
rather than its output.

## Running it

```shell
java -Xmx16g -Djol.magicFieldOffset=true \
  -Devita.trigram.corpusFile=/path/to/corpus.tsv \
  -cp evita_test/evita_performance_tests/target/benchmarks.jar \
  io.evitadb.spike.trigram.TrigramCorpusStatistics
```

`-Devita.trigram.measureHeap=false` skips the JOL walks, which are the slow part on a large corpus.

## Related

- [`TrigramCorpusExtractor`](TrigramCorpusExtractor.md) — produces the input.
- [ADR §"P8 — measured, not planned"](../../../../../../../../../documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/README.md) — where these figures are recorded.
