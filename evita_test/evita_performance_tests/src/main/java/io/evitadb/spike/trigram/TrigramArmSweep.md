# TrigramArmSweep — the shipped accelerator against the scan it displaces

**Question.** On a real corpus, at every point of the selectivity axis the corpus can supply: is the
trigram path faster than the scan, and is the selectivity gate declining in the right places?

This is the only instrument here that measures **production code**. Both arms enter at the seam the
query engine enters at, so the ratio it reports is the ratio a query sees.

## The two arms, and the three deliberate divergences

- **scan** — `FilterIndex#getRecordsWhoseValuesContains` then `Formula#compute()`, which is what the
  translator resolves through when no accelerator exists;
- **trigram** — `TrigramSubstringSearch#match` then `InvertedIndex#toFormula` then
  `Formula#compute()`, taken verbatim from `AbstractAttributeStringSearchTranslator`, with the exact
  predicate taken from `AttributeContainsTranslator#createPredicate()` rather than restated.

Both fold through the same `toSortedOrFormula`, so the fold is not charged to one side. Three places
diverge from the translator on purpose, each because doing otherwise would hide the thing being
measured:

1. **The timed trigram arm forces the gate**, by handing `match` a counter that answers
   `Long.MAX_VALUE`. The translator lets the gate decline and falls back; a driver that did the same
   would produce no trigram number for the declined half of the axis — which is exactly the half that
   decides whether the gate sits in the right place. The real verdict is observed **separately**, by a
   second untimed `match` carrying the honest counter, and recorded in its own column. Every row is
   therefore a test of whether the gate's call was right, not a report of what it chose.
2. **The honest counter is the bucket count**, not the translator's walk over a target set. This driver
   measures the single-index case the translator's sum reduces to.
3. **Nothing is memoised.** What is timed is the uncached cost of one query, on both arms.

With no synthetic oracle available on production data, **arm parity is the oracle** — both arms are
verified element-identical for every pattern before anything is read off.

## Conclusions it produced

Measured on a production e-commerce catalog (157,410 products, 18 collections) over three
identifier-shaped `String` attributes: **159 real patterns, both arms measured and verified
element-identical for all 159.**

**Corpus character decides everything, and it is visible in the census the run prints:**

| attribute shape | n (distinct) | trigrams/value | distinct trigrams | hottest trigram | verdict |
|---|---:|---:|---:|---:|---|
| fixed-width 6-char ASCII code | 157,410 | 4.07 | 3,182 | 1.12% of n | near-ideal; cannot degenerate |
| free-form catalog number, 0–30 chars with separators and diacritics | 155,832 | 7.62 | 31,867 | 12.14% | the ordinary identifier |
| 13-digit EAN, alphabet of 10 | 116,978 | 10.95 | **1,132** | **33.39%** | the adversarial shape |

The shape that hurts is a **long value over a tiny alphabet**: 13 digits over ten symbols give 11
trigrams per value but only 1,132 distinct trigrams, so postings are enormous (median 642, p99 10,366)
and a 3-character query returns a third of the corpus.

**The `n`-scaling reading of the synthetic ladder is dead.** The synthetic corpus produced
`1/f*` = 7.8 → 11.0 → 17.8 at n = 10⁴/10⁵/10⁶, roughly `n^0.18`, predicting that the crossover
*falls* as `n` grows. The prediction registered in writing before this run was 8.3–8.7%. Measured, the
crossover sits **above** the synthetic figure, at 10.85–13.09%, wanting a factor of 7.6–9.2. The three
synthetic points remain honest measurements within their own corpus; what is dead is treating them as a
scaling law transferable across corpora. **Over this range corpus character dominates `n` outright.**

**`1/f* ≈ 10` is the crossover, and it has a physical reading.** `f*` over every pure-regime row above
1% width across all three attributes is 0.088–0.112 — near-constant, as the model says. A candidate costs
the trigram path roughly ten times what a bucket costs the scan, because the scan steps a cursor through
one contiguous sorted array while each candidate costs a directory probe plus, for each survivor, a tree
descent.

**Zero regressions in 159 patterns.** `admitted-but-slower-than-the-scan` = **0** across the whole run —
the asymmetry the gate constant's JavaDoc argues from, upheld on real data rather than asserted. Measured
end to end through the public query API, **every declined cell lands within 2% of 1.00×**: a decline is
free.

## Why it is still live

The gate constant is a single number in production code whose correct value is a property of the corpus,
and this is the only instrument that can re-derive it. Any change to `TrigramSubstringSearch`, to the
translator, or to the posting representation is measurable here against the scan it has to beat — and any
new corpus shape can be checked for the adversarial profile above before an attribute is flagged.

## Running it

Needs the corpus TSV from [`TrigramCorpusExtractor`](TrigramCorpusExtractor.md). It builds one
throwaway embedded catalog per measured attribute, one entity per distinct value, with the attribute
declared `filterable()` and `acceleratedFor(AttributeFilterAccelerator.SUBSTRING_SEARCH)` so a single
built index serves **both** arms — the scan reads the shared value tree the trigram postings name value
ids in. Writes one TSV row per pattern, so the crossover is read off the data rather than argued from a
constant.

## Related

- [ADR §"P8 on a production corpus — 159 real patterns"](../../../../../../../../../documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/README.md) — where these figures are
  recorded, and the correction they forced on the record above them.
