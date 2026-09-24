# Phase B — the approach matrix for one language

The matrix is a JUnit class that builds every candidate analysis chain for the language, runs each over one
sourced vocabulary, and reports five numbers per chain. It is a **decision instrument, not a regression
guard**: its output is read by the user, its assertions pin only findings that would change the conclusion if
they reversed, and it is deleted in Phase C. The Czech matrix (`CzechAnalysisApproachMatrixTest`) and its
three siblings did exactly this; their records are `p5-approach-measurements-accent-vs-stemming.md` and
`p5-prior-art-sk-pl-ro.md` §9.

## 1. What is already in the tree, and what the language adds

The language-agnostic half of the harness is permanent test-scope code in
`evita_test/evita_functional_tests/src/test/java/io/evitadb/index/fulltext/analysis/`, kept alive by the
production recall test that runs on it. Use it, never reimplement it:

- `AnalysisApproachMeasurer` — the five metrics, `MatchStrategy` (`EXACT`, `PREFIX_FUZZY`), `Measurement`
  with `matrixRow()` / `detail(n)`, `matrixHeader()`, `analyzeWord(...)`. Read its class javadoc first.
- `Lemma` — `(lemma, forms)` plus `stripAccents(String)`, the default bare typer.
- `FoldedStemmer` / `FoldedStemFilter` — the contract of a folded-space stemmer port and the filter that
  drives one in a symmetric chain.
- `HypothesisStemFilter` — emits every stem a list of differently-switched `FoldedStemmer`s produces, at one
  position: the query half of an M7 row.
- `LexiconCoverageSweep` — the full-dictionary verifier (§7).
- `DiacriticsFoldingAnalyzerWrapper`, `TokenizingAnalyzer`, `FulltextAnalyzer`, `AnalysisMode` — production
  classes every row is built from.

What the language adds, all copied from `templates/` in this skill folder and renamed:

| template | becomes | lifetime |
|---|---|---|
| `LanguageAnalysisFixture.java` | `<Lang>AnalysisFixture` | permanent — the recall test pins against it later |
| `LanguageAnalysisApproachMatrixTest.java` | `<Lang>AnalysisApproachMatrixTest` | Phase B only, deleted in Phase C |
| — (hand-written, §5) | `Folded<Lang>Stemmer implements FoldedStemmer` | permanent, the executable specification |
| — (hand-written, §5, if nothing to adopt) | `<Lang>Stemmer` | permanent, graduates to `evita_engine` in Phase C |

The matrix test's javadoc must say **"This class is an instrument, not a guard"** (the template does), so
Phase C knows what to delete. Commit the lot as `test: <language> analysis approach matrix` with
`Ref: #<issue>`. Nothing in this phase is recovered from git history — if something seems to be missing from
the tree, it is meant to be written for the language, not dug up.

## 2. The fixture — `<Lang>AnalysisFixture`

One `final class` with `static final List<Lemma> VOCABULARY` and `static final List<Lemma> CONFUSABLE_LEMMAS`,
plus `static String bareType(String)` when the default NFD strip is wrong for the language (see §3).
`RomanianAnalysisFixture` is the template, including its javadoc that names every morphological class the
vocabulary carries **and why**.

**The vocabulary protocol** (from the sk/pl/ro plan; Czech needed native intuition *and* five fixture
corrections, so a non-native fixture needs every safeguard):

- Every inflected form is checked against a citable source (inflection tables, a dictionary site, a grammar
  reference) — nothing written from memory. Put the reference in the fixture javadoc.
- ~30 lemmas, e-commerce domain (colours, materials, garments, furniture, electronics, store vocabulary such
  as "quality", "warranty", "promotion") so false-merge floors stay comparable with the four existing records.
- Every stemmer rule the matrix targets has, in the vocabulary, **both** a case it fixes and a case it exists
  for. A rule measured against a vocabulary that cannot exercise it scores zero for the wrong reason
  (`p5-approach-measurements-accent-vs-stemming.md` §10).
- Include the language's own classes that have no Czech precedent — e.g. Romanian carried the same word in
  both orthographies (cedilla and comma-below), Slovak carried rhythmic-law pairs, Polish carried `ó`/`o`
  alternations. Look for encoding splits, alternative spellings, and letters NFD leaves alone.
- Include stem-internal alternations no suffix stripper can converge (`masă`/`mese`, `stůl`/`stolů`) so the
  convergence ceiling is measured, not assumed.
- **Confusables** are pairs of *unrelated* lemmas chosen by reading the stemmer tables for collision-prone
  endings and then finding real words that commit them — the `forma`/`formát` method. 5–6 pairs.
- Write "authored without a native speaker; not yet natively reviewed" in the javadoc when true, and carry it
  into the record's *Limits*. Ask the user whether a native review is reachable before run 1.

## 3. Bare typing and folding — two functions that must be right before anything is measured

- **`bareTyper`** reproduces what a user without the language's keyboard layout types. Default: NFD strip of
  combining marks (`Lemma.stripAccents`). It is **wrong** for letters that are not base+mark compositions —
  Polish `ł`, Nordic `ø`, Croatian/Serbian `đ`, `ß`, `æ`, `œ`, `þ`. For each such letter decide what a user
  types (`ł`→`l`, `ø`→`o`, `ß`→`ss`?) and put it in the fixture's `bareType`. A form whose bare typing equals
  the form is skipped as vacuous by the measurer, so a missing mapping silently removes a whole letter class
  from the metrics (`p5-prior-art-sk-pl-ro.md` §9.1).
- **`fold`** used by the lexicon sweep must reproduce what production's `ASCIIFoldingFilter` does for the
  language's alphabet, or the sweep compares the wrong strings. Check the filter's mapping for every
  non-composed letter; the Polish lexicon test carries the `ł` special case as the example.

## 4. The five metrics — and the one to read

Computed by `AnalysisApproachMeasurer.measure` (definitions in `p5-approach-measurements-accent-vs-stemming.md`
§3):

1. **accent-typed recall** — bare typing of a form finds that same form.
2. **inflection convergence, ordered pairs** — form A (accented) finds form B (accented) of the same lemma.
3. **inflection convergence, strict** — all forms of a lemma share a term (kept for comparability).
4. **bare-typed cross-form recall** — 1 and 2 combined: bare typing of A finds accented B. **The one that
   matters**: a two-lane chain scores perfectly on 1 and 2 with each lane alone and still fails 4.
5. **false merges** — ordered pairs from different lemmas that match anyway. Without it any mechanism wins by
   collapsing the vocabulary onto one term.

Plus `terms/form` (index-size cost) and, for asymmetric rows, the query-side fan-out (avg / max variants per
token), measured separately.

Two `MatchStrategy` values: `EXACT` (what the term dictionary does — the default for every row) and
`PREFIX_FUZZY` (Meilisearch-style prefix + length-scaled typo budget) for the no-stemmer floor and the
production baseline, to show what a prefix/typo lane would buy instead of stemming.

## 5. The row set

Pick an unused single-letter prefix for the language (A = cs, R = ro, P = pl, S = sk are taken). Build these
rows; drop a row only with a sentence in the record saying why it is not constructible for this language:

- **`X0` step 0** — the Lucene analyzer (or the survey's zero-code wiring) + `DiacriticsFoldingAnalyzerWrapper`
  (stem → fold). The production baseline of the Czech record; the *upper bound* of what wiring alone buys.
- **`X0b` bare** — the Lucene analyzer with no folding. What most engines ship; the bare-typed floor.
- **`X0n`, `X0s`, `X0m` …** — index-side alternatives when more than one stemmer exists (Romanian's
  normalization backport, Polish's Snowball vs Stempel vs Morfologik). The matrix also arbitrates the index
  stemmer when Lucene offers several; say so in the record.
- **`X1` fold → stem, naive (M3)** — the unmodified stemmer fed folded input, the Vespa/Typesense shape.
  Folding sits **after** the stop filter, because the stop list is spelled with diacritics and moving it would
  change a third property while two are measured.
- **`X2`…`Xn` fold → `Folded<Lang>Stemmer` (M1)** — the stemmer's tables rewritten into folded space, every
  fold-ambiguous rule an independent switch, one row per switch position that matters (all off, each on
  singly, all on). Only constructible over a **rule-based** stemmer (Snowball, light, in-house); statistical
  or dictionary stemmers get no M1/M7 rows and the record says so.
- **`X8` fold only, no stemmer (M4)** — under `EXACT` and `PREFIX_FUZZY`. The floor a fold-only chain stands on
  (Slovak's was 0/323 — nobody was standing on it).
- **`X-hun` Hunspell, both orders** — if a dictionary is reachable (§7). The Czech prior: excellent accented
  convergence, unusable bare-typed recall, no M7 path. Two rows, fold-after and fold-before.
- **`X20` M7 asymmetric** — `X0` (or the chosen index row) indexes; the query chain folds and runs
  `HypothesisStemFilter` over `Folded<Lang>Stemmer.allHypotheses()` — every switch combination, plus the
  surface form. Variants of `X20` drop forks (`X21`, `X22`) to price each fork's recall against its merges.
- `X0`, `X2`, `X8` again under `PREFIX_FUZZY`, as in every record.

**Building `Folded<Lang>Stemmer`.** Port the accented stemmer's tables into folded space mechanically; every
place where two accented entries collapse onto one folded string with *different* actions, or where a folded
ending now matches genuine words it never matched accented, is a **fork** — make it a constructor switch and
document the language judgment behind it in the javadoc (see `FoldedRomanianStemmer`'s four). Region
marking (Snowball `RV`/`R1`/`R2`) can drift when a vowel folds to a consonant or vice versa — verify, do not
assume. Snowball route: edit the `.sbl` and recompile if the compiler is at hand, else hand-port in the
`FoldedCzechStemmer` style. Provide `allHypotheses()` returning one instance per switch combination.

**In-house stemmer (nothing to adopt).** Build `<Lang>Stemmer` on the `CzechStemmer` architecture — ending
tables, possessive pass, `normalize()` for stem-final alternations — with tables authored against the
language's paradigms, never copied from a neighbour. Test scope first; the matrix validates the *tables* as
much as the mechanism, so expect several fixture-and-table iterations. Then port it to folded space as above.

## 6. Running and reading

```shell
mvn -pl evita_test/evita_functional_tests test -P unitAndFunctional \
    -Dtest='<Lang>AnalysisApproachMatrixTest' -DsurefireArgLine=
```

- **Read the matrix from `target/surefire-reports/TEST-*.xml`**, never from the console — console encoding
  mangles diacritics and you will misread failing cases.
- The `@BeforeAll` builds every chain through `new FulltextAnalyzer(name, AnalysisMode.ALL, analyzer)` so the
  measurement runs through production's boundary (NFC normalization included), and `@AfterAll` closes every
  chain — an unclosed Lucene analyzer retains per-thread components for the JVM's lifetime.
- Never pass `-o` to Maven; revert the rewritten `**/grpc/generated/` sources after the build.

## 7. The lexicon sweep — before believing any "no ambiguity" result

A fixture can only falsify what it carries: the Slovak matrix reported "no fold-ambiguity" for three runs and
the first full-lexicon sweep found five uncovered word classes. So before Gate B:

1. Copy the dictionary **unmodified** from `$OSS_PROJECT_PATH/hunspell-dictionaries/dictionaries/<code>/index.dic`
   and `index.aff` to `evita_test/evita_functional_tests/src/test/resources/fulltext/hunspell/<xx_YY>.dic`
   / `.aff`. If the directory is absent, ask the user for it — never download.
2. Read `dictionaries/<code>/license` and `package.json` verbatim; record upstream project, version, the
   wooorm commit (`git -C "$OSS_PROJECT_PATH"/hunspell-dictionaries log -1 --format=%H`), the licence options and which
   one evitaDB relies on, and `sha256sum` of both files — a new section in the hunspell `README.md` and a new
   block in `evita_test/evita_functional_tests/NOTICE`, in the existing format. **A GPL-only dictionary is a
   decision for the user** (the cs_CZ precedent explains the mere-aggregation argument); show it, do not take
   it.
3. Write `<Lang>FoldedStemmerLexiconTest` on `LexiconCoverageSweep.sweep(...)`: for every headword `w`,
   `fold(accentedStem(w))` must be in the union of `Folded<Lang>Stemmer.allHypotheses()` outputs for
   `fold(w)`. Every uncovered word is a fork the port does not have yet — add the fork, re-run the matrix,
   re-sweep, until zero. Report the tested count and the skipped share (non-letter entries).
4. If the dictionary lists headwords only (usual), say in the record that recall over inflected forms stays
   fixture-scale.

## 8. Iterate with a ledger from run 1

Every run's fixture corrections, table fixes and reinterpretations go into a §10-style ledger **as they
happen** — the Czech record started it too late and had to reconstruct six runs. Each entry: what was wrong,
how it was found (which metric, which failing case), what changed, which numbers moved.

Two traps the records name, both worth a paragraph if hit:

- A **symmetric folded chain mis-stems both sides identically and still converges** — only the asymmetric
  row can see such an ambiguity (the Czech epenthetic `e`, the Romanian `ă`-verb endings were found this way).
- A **fork that measures zero effect** means "this vocabulary cannot distinguish it", not "it is free" — plant
  a probe or record it as unmeasured.

## 9. Write §9 of the record

Append to `p5-prior-art-<lang>.md`, mirroring `p5-prior-art-sk-pl-ro.md` §9:

- **§9.1 Environment** — every place the pinned Lucene diverges from the surveyed HEAD and how it shaped the
  rows; anything vendored into test scope and why.
- **§9.x The language** — fixture size and sourcing status; the matrix table (`approach · matching ·
  accent-typed · bare+crossform · conv. pairs · conv. strict · false merges · terms/form`); a paragraph per row
  family on what the numbers mean; the failing cases that explain them; the query-side fan-out of M7 rows;
  the ledger.
- **What the ladder looks like after the measurements** — which survey hypotheses survived, which did not.
- **Limits** — native review pending, forks measured zero, fixture-scale bounds, anything unreachable at the
  pinned version.
- **Decisions for the user** — phrased as choices with their measured price: index stemmer (if several), which
  row ships, whether an in-house stemmer graduates ("do we own it"), reindex consequences for existing
  catalogs.

Then **Gate B** (`SKILL.md` §2): hand the record over and ask which row ships. Write the answer and its date
into the record before Phase C starts.
