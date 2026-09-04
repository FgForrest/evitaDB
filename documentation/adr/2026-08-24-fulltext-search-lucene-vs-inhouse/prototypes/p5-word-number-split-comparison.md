# P5 — splitting a word/number token: the old client's splitter versus Lucene's, compared

> **Status: comparison record**, the evidence behind the 2026-09-02 revision of
> [`p5-analyzers.md`](p5-analyzers.md) §4.6 point 2. Measured on branch `258-fulltext-support-p5` with
> Lucene 9.12.3 (evitaDB) and, for the query-side shapes, Lucene 9.12.1 (the version the old client runs).
> The old client is the EdeeCMS fulltext library (`prj_fulltext/lib_fulltext`, internal); its splitter is
> ported verbatim into the test sources as `LegacyWordWithNumberSplitFilter` so that the numbers below are
> measured, not remembered.
>
> **Reproduce:**
> ```shell
> mvn -pl evita_test/evita_functional_tests test -P unitAndFunctional -Dtest=WordNumberSplitAnalysisTest
> ```
> The table in §4 is printed to stdout by `shouldReportSplitterComparison`; every other claim is an
> assertion in the same class.

---

## 1. The problem

Product data glues letters and digits into one token: `UHD7800`, `XC90`, `iPhone15`, `GTX1080Ti`.
Lucene's `StandardTokenizer` — the tokenizer under every analyzer discussed here — keeps such a token
whole, so the index holds one term, `uhd7800`, and three natural queries miss it:

| user types  | query terms          | finds `UHD7800`? |
|-------------|----------------------|------------------|
| `UHD7800`   | `uhd7800`            | yes              |
| `7800`      | `7800`               | **no**           |
| `UHD 7800`  | `uhd`, `7800`        | **no**           |

The old client's documentation names the second row as the motivation, translated: *"a helper analyzer,
switched on by the `enableWordNumberAnalyzer` attribute, which detects a number at the beginning or the end
of a token. If found, the token is split into two parts — the number and the rest — and both are added to
the index separately. Useful when the data holds combined expressions identifying goods, say `UHD7800`,
and users are used to searching by the number alone, i.e. `7800`."*

`p5-analyzers.md` §4.6 point 2 carried this as a capability the new engine must not lose. This document
answers **how** to provide it.

## 2. How the old client does it

**Code.** `WordWithNumberSplitFilter` (2020), a `TokenFilter` built on the library's own
`AbstractStackTokenFilter`: for every token coming from the chain it computes the parts, pushes each onto a
stack as a captured token state, and emits them after the original. `WordWithNumberAnalyzerWrapper`
attaches the filter **after the last component** of whatever analyzer it wraps. The only unit test covers
the string-splitting helper, not the stream.

**Algorithm.** Three regular expressions decide:

1. a token of a **single** digit is skipped (`^\d$` — one digit, not a digit run; a longer digits-only
   token passes this check and is left alone anyway because the scans below find nothing to split at);
2. a token that **starts** with a digit run is split into that run and the rest: `123xyz` → `123`, `xyz`;
3. otherwise a token that **ends** with a digit run is split into the rest and that run: `abc789` → `abc`,
   `789`.

At most **one** split is ever made and a digit run **inside** the token is ignored: `123abc345xyz678` →
`123`, `abc345xyz678`; `GTX1080Ti` is not split at all. Both parts carry position increment **0** (the
original's position) and the **original's offsets**; the type is reset to Lucene's default. Because the
parts come off a stack they are emitted in reverse order — `123xyz`, `xyz`, `123` — which the index does
not care about.

**Wiring.** `IndexFactory.getAnalyzerInstance` wraps **every** analyzer name — the Czech ones and the
`universal` one alike — when `IndexConfig.enableWordNumberAnalyzer` is `true` (default `false`), and does so
for **both** the indexing and the searching analyzer. The default `czech` analyzer's chain is tokenizer →
lowercase → stop words → (raw-terms marker) → (keyword marker) → (synonyms) → `CzechStemFilter` →
`DiacriticFilter` → (prefix "summon" tokens, index side only); the splitter is appended after all of that,
i.e. **after the stemmer**. The admin index browser switches the flag off explicitly, so browsing shows the
data without the extra terms.

**Query side.** Queries go through Lucene's classic `QueryParser` with the same wrapped analyzer and
`AND` as the default operator. Several terms at one position become a `SynonymQuery`, i.e. an **OR**:

| query        | old client's parsed query               |
|--------------|-----------------------------------------|
| `UHD7800`    | `Synonym(7800 uhd uhd7800)`             |
| `TV UHD7800` | `+tv +Synonym(7800 uhd uhd7800)`        |
| `boty42`     | `Synonym(42 boty boty42)`               |
| `7800`       | `7800`                                  |
| `UHD 7800`   | `+uhd +7800`                            |

So a query for `UHD7800` also matches every document containing just `uhd` or just `7800`, and `boty42`
matches every document containing the word `boty`. That is the price of putting the parts at one position
and running the splitter on the query side too.

## 3. The candidates

Two independent choices are in play, and keeping them apart is what makes the comparison readable:

- **Algorithm** — the old client's single split (**L**), or Lucene's `WordDelimiterGraphFilter` (**W**)
  configured with `GENERATE_WORD_PARTS | GENERATE_NUMBER_PARTS | SPLIT_ON_NUMERICS | PRESERVE_ORIGINAL`,
  offsets adjusted to the part, graph flattened by `FlattenGraphFilter` because evitaDB's term contract
  (`AnalyzedTerm`) carries a position increment but no position length.
- **Placement** — **appended** after the finished analyzer, which is all a wrapper can do and is what the
  old client did; or **in the chain**, directly after the tokenizer and before stop words and the stemmer,
  which requires composing the chain from its components instead of wrapping `CzechAnalyzer`.

The five chains measured:

| column                 | algorithm | placement | base chain                                                     |
|------------------------|-----------|-----------|----------------------------------------------------------------|
| `czech`                | —         | —         | evitaDB's built-in Czech chain today (`CzechAnalyzer` + folding)|
| `czech-legacy-split`   | L         | appended  | the built-in Czech chain, wrapped as the old client wrapped it |
| `generic-legacy-split` | L         | appended  | tokenizer + lowercase + folding — closest to the old `universal`|
| `czech-split-appended` | W         | appended  | the built-in Czech chain                                        |
| `czech-split-in-chain` | W         | in chain  | tokenizer → **W** → lowercase → stop → `CzechStemFilter` → folding|

## 4. Measured terms

Terms as `FulltextAnalyzer.getTerms` returns them — after NFC normalization, lowercasing, Czech stop words,
`CzechStemFilter` and ASCII folding where the chain has them. The Czech stemmer's palatalization rewrite
turns a final `c` into `k` and a final `z` into `h` on any token, which is why `xyz` reads `xyh` and `abc`
reads `abk` wherever the stemmer saw the part.

| input | czech | czech-legacy-split | generic-legacy-split | czech-split-appended | czech-split-in-chain |
|---|---|---|---|---|---|
| `UHD7800` | uhd7800 | uhd7800, 7800, uhd | uhd7800, 7800, uhd | uhd7800, uhd, 7800 | uhd7800, uhd, 7800 |
| `123xyz` | 123xyh | 123xyh, xyh, 123 | 123xyz, xyz, 123 | 123xyh, 123, xyh | 123xyh, 123, xyh |
| `abc789` | abc789 | abc789, 789, abc | abc789, 789, abc | abc789, abc, 789 | abc789, abk, 789 |
| `123abc345xyz678` | 123abc345xyz678 | 123abc345xyz678, abc345xyz678, 123 | 123abc345xyz678, abc345xyz678, 123 | 123abc345xyz678, 123, abc, 345, xyz, 678 | 123abc345xyz678, 123, abk, 345, xyh, 678 |
| `boty42` | boty42 | boty42, 42, boty | boty42, 42, boty | boty42, boty, 42 | boty42, bot, 42 |
| `iPhone15` | iphone15 | iphone15, 15, iphone | iphone15, 15, iphone | iphone15, iphone, 15 | iphone15, iphon, 15 |
| `GTX1080Ti` | gtx1080t | gtx1080t | gtx1080ti | gtx1080t, gtx, 1080, t | gtx1080t, gtx, 1080, ti |
| `XC90` | xc90 | xc90, 90, xc | xc90, 90, xc | xc90, xc, 90 | xc90, xk, 90 |
| `3.5mm` | 3.5mm | 3.5mm, .5mm, 3 | 3.5mm, .5mm, 3 | 3.5mm, 3, 5, mm | 3.5mm, 3, 5, mm |
| `AB-123` | ab, 123 | ab, 123 | ab, 123 | ab, 123 | ab, 123 |
| `8594001234567` | 8594001234567 | 8594001234567 | 8594001234567 | 8594001234567 | 8594001234567 |
| `boty` | bot | bot | boty | bot | bot |
| `iPhone 15` | iphon, 15 | iphon, 15 | iphone, 15 | iphon, 15 | iphon, 15 |

How to read the rows:

- **`UHD7800`, `7800`** — the documented use case. Every splitting chain delivers `7800`; a digit run has
  nothing for a stemmer to change, so the number half is safe whatever the algorithm and placement. The
  choice between the candidates is decided by the **word** half.
- **`boty42` against `boty`** — the row that rejects the appended placement on a stemming chain. Appended
  (legacy or Lucene) the word part is `boty`; the same word typed alone is stemmed to `bot`. They never
  meet, so a product named `boty42` is not found by the inflected word. In the chain, the part is `bot` —
  identical to the standalone term. `iPhone15` against `iPhone 15` shows the same: `iphone` versus `iphon`.
  The `generic-legacy-split` column is internally consistent (`boty` both times) only because that chain
  has no stemmer at all.
- **`GTX1080Ti`** — two independent defects on display. The old algorithm never splits it, because the
  digit run is inside the token, so `1080` is not searchable at all. The Lucene filter appended after the
  stemmer splits it, but the stemmer has already shortened `gtx1080ti` to `gtx1080t`; the word part becomes
  `t` and, because the term is now shorter than the span its offsets cover, the filter cannot adjust the
  offsets and every part reports `GTX1080Ti` as its surface form. In the chain the parts are `gtx`,
  `1080`, `ti` with their own offsets.
- **`123abc345xyz678`** — one split versus every transition. Only the in-chain and appended Lucene
  variants make `678` and `345` searchable.
- **`3.5mm`** — the tokenizer keeps the dot; the old algorithm produces the junk part `.5mm`, the Lucene
  filter treats the dot as a delimiter and produces `3`, `5`, `mm`. Neither yields `3.5`. Symmetric
  analysis keeps either harmless; the Lucene result is at least made of real parts.
- **`AB-123`, `8594001234567`** — nothing to do. The tokenizer already split the hyphen, and a barcode is
  digits only. An EAN is never affected by this step in any variant.

## 5. Property by property

| property | old algorithm, appended (as in the old client) | Lucene filter, appended | Lucene filter, in chain |
|---|---|---|---|
| number part searchable alone (`7800`) | yes | yes | yes |
| word part meets the stemmed standalone word (`boty42` ↔ `boty`) | **no** on stemming chains | **no** on stemming chains | yes |
| inner digit runs (`GTX1080Ti` → `1080`) | **never** | yes | yes |
| part offsets → surface form of the part | no — parts report the whole token | only while the stemmer left the length alone | yes |
| positions | all parts at the original's position | original spans the parts; parts at consecutive positions | same |
| phrase `UHD 7800` matches indexed `UHD7800` | no (parts share one position) | yes | yes |
| query Lucene's own parser would build for `UHD7800` (evitaDB does not use it — §6) | `7800 OR uhd OR uhd7800` | `uhd7800 OR (uhd AND 7800)` | same |
| parts attributable to their original by the query builder | yes — same position and offsets | only while the stemmer left the length alone | yes — every part's offsets nest inside the original's |
| extra terms per split token | 2 | number of runs (2 for a two-run code) | same |
| code owned by evitaDB | a filter of our own (60 lines + a stack base class) | none — configuration of an upstream filter | none |
| tested upstream | no (the old client tests the string helper only) | yes, `TestWordDelimiterGraphFilter` | yes |
| can be enabled by wrapping the built-in analyzers | yes | yes | **no** — the chain has to be composed from components |

## 6. The query side — evitaDB chooses the shape, and can keep the old client's

The `Synonym(...)` and `uhd7800 OR (uhd AND 7800)` shapes in §2 and §5 are what **Lucene's** query parser
makes of the token stream. evitaDB uses Lucene for analysis only; the query is built by our own code
(`query-design.md`), from the `AnalyzedTerm` records the analyzer emits. So the shape is not inherited from
the filter — it is a decision, and the analyzer's job is merely to hand the builder enough to make it.

It does. Every part's offsets nest inside its original's offsets (`TV UHD7800` → `tv` 0–2, `uhd7800` 3–10,
`uhd` 3–6, `7800` 6–10), so the builder can regroup the stream into "original + its parts" by offset
containment. It must **not** group by position increment: the flattened graph gives the second part an
increment of 1, as if it were a new word. `shouldLetQueryBuilderRebuildGroupFromOffsets` pins this.

Given the group, the builder can produce any of these for `UHD7800`; what each finds:

| document holds | user types | index side only, plain query | Lucene's shape `uhd7800 OR (uhd AND 7800)` | old client's shape `uhd7800 OR uhd OR 7800` |
|---|---|---|---|---|
| `UHD7800` | `7800` | found | found | found |
| `UHD7800` | `UHD 7800` | found | found | found |
| `UHD7800` | `UHD7800` | found (original preserved) | found | found |
| `UHD 7800` | `UHD7800` | **missed** | found | found |
| `7800` | `UHD7800` | missed | **missed** | found |
| `UHD` (only) | `UHD7800` | missed | missed | found — noise |

The last two rows are one trade-off seen from both sides: the old client's OR is the only shape that finds
a product listed under the bare number when the user typed the full code, and it is also the shape that
returns every `UHD` television for that query. Dropping it is a recall regression against the old client;
keeping it verbatim inherits the noise. Neither is forced:

- **Keep the old client's OR, rank by how much of the group matched.** All alternatives are OR'd, but a
  document matching the original (or both parts) outranks one matching a single part, and a bare word part
  ranks last. `p7-rank-profiles-and-boost-channel.md` already ranks by the number of query terms found, so
  this costs the query builder nothing new. This preserves every row of the old client's recall and moves
  the `UHD`-only noise to the tail instead of removing it.
- **Number part alone counts, word part alone does not**: `uhd7800 OR 7800 OR (uhd AND 7800)`. Keeps the
  `7800` row, drops the `UHD`-only row. Justified by the documented use case being about the number, and by
  the word half of a code (`uhd`, `xc`, `gtx`) rarely being a meaningful query on its own — though `boty`
  from `boty42` is, which is the argument for the ranked variant above.
- **Index side only.** No query expansion at all; misses the two "glued" rows. The first genuine candidate
  for the registry's `INDEX_TIME` mode, and the cheapest — but it is the one that loses recall against the
  old client, so it should not be the default for a field where the step is on.

The recommendation is the first: the old client's recall is a documented capability, and ranking rather
than exclusion is how the noise it brought along is handled. Whichever is chosen, the filter and its
placement stay as §7 says — they decide *which terms exist*, the builder decides *how they combine*.

## 7. Verdict

- **Take the Lucene filter, in the chain.** The old algorithm's two limits — one split, no inner runs — are
  not properties anybody asked for, and its placement after the stemmer is what breaks the word half. The
  Lucene filter placed after the tokenizer has no such defect, adds no code of ours, and is tested
  upstream. The reasoning is the same as §4.6 point 3's rejection of the old `DiacriticFilter`.
- **Consequence for `BuiltInAnalyzers`.** The in-chain placement cannot be reached by wrapping
  `CzechAnalyzer` (or `EnglishAnalyzer`, `GermanAnalyzer`, `PolishAnalyzer`); enabling the step for a
  language means composing that chain from tokenizer, filters and stemmer. This work travels with the
  per-attribute switch that turns the step on (`schema-design.md` §6.5, analyzer parameters). Until both
  exist the filter has no production caller; `WordNumberSplitAnalysisTest` pins the contract it is wired
  against.
- **For an attribute that holds codes only**, the right answer is a chain **without a stemmer** plus the
  step — placement then stops mattering and nothing is palatalized. This matches the earlier decision to
  keep exact-match codes in their own attribute.
- **Off by default**, as before: every mixed token costs two or more extra postings and the CMS profile has
  no use for them.
- **The query shape is the query builder's, not the filter's** (§6): run the step on both sides and OR the
  group's alternatives, ranked by how much of the group matched, so nothing the old client found is lost.

## 8. Sources

- evitaDB: `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/fulltext/analysis/WordNumberSplitAnalysisTest.java`
  (assertions and the §4 report) and `LegacyWordWithNumberSplitFilter.java` (the port) in the same package;
  `evita_engine/src/main/java/io/evitadb/index/fulltext/analysis/BuiltInAnalyzers.java` (the chains that
  would have to be composed).
- Old client (internal, EdeeCMS `prj_fulltext/lib_fulltext`): `org.apache.lucene.analysis.WordWithNumberSplitFilter`,
  `WordWithNumberAnalyzerWrapper`, `AbstractStackTokenFilter`; `com.fg.fulltext.core.configuration.IndexConfig`
  (`enableWordNumberAnalyzer`); `com.fg.fulltext.core.index.IndexFactory` (`wrapWithWordNumberAnalyzer`,
  `getCreatorAnalyzer` / `getSearcherAnalyzer`, `parse`); documentation
  `META-INF/lib_fulltext/docs/cs/sections/analyzers.md` and `configuration.md`.
