# Phase C — graduating the chosen approach to production

The worked example is what the four shipped languages look like today: `BuiltInAnalyzers` and, for one
language, its classes and tests — Slovak for the in-house-stemmer shape (`SlovakStemmer`,
`SlovakVariantStemmer`, `SlovakAnalysisFixture`, `SlovakVariantStemmerLexiconTest`,
`BranchingSlovakStemmerEquivalenceTest`, the Slovak nested classes of `FulltextAnalyzerTest` and
`LanguageAnalyzerPairRecallTest`), Romanian for the adopted-Lucene-stemmer-plus-normalization shape, Polish
for the vendored-stemmer and bundled-stop-list shape. Every item below points at the file to mirror. Work
through it in the order given — the instruments are deleted **last**, so nothing a new test needs disappears
early.

The user's Gate B decision fixes which of the three shapes ships:

- **Step 0 only** — a uniform assignment (`AnalyzerAssignment.uniform(<NAME>)`) or the folding wrapper around
  a Lucene analyzer. Only §1 (partially), §2, §3.1, §3.3 (smoke tests), §5 and §6 apply.
- **Symmetric chain** — one chain, both slots, `AnalysisMode.ALL`. As above plus any production stemmer or
  filter classes.
- **Asymmetric M7 pair** — an index chain and a `<language>-search` chain declared `SEARCH_TIME`. Everything
  below.

## 1. Production classes — `evita_engine`, package `io.evitadb.index.fulltext.analysis`

Naming policy: **no prototype vocabulary**. "Hypothesis", "folded", "branching" (except where it names the
mechanism of one walk taking both branches of a fork) disappear from production names. The fan-out is named
for what it is — *stem variants*.

- `<Lang>Stemmer` — only when the language has an in-house accented stemmer (nothing to adopt). Package-private
  `final class` on the `CzechStemmer` architecture with a nested `<Lang>StemFilter`; `SlovakStemmer` is the
  template. Input contract: lowercased **accented** text.
- `<Lang>VariantStemmer implements VariantStemmer` — the production form of the folded port: **one walk** over
  the accented stemmer's tables read over the folded alphabet, forking at every fold-ambiguous rule, zero
  allocation per call, results kept as `(length, final-chars)` scratch. Read `CzechVariantStemmer`
  (simplest), `SlovakVariantStemmer` (chained normalization rules), `PolishVariantStemmer` (merged static
  table), `RomanianVariantStemmer` (staged worklist) and pick the structure the language's tables need. State
  the variant-count bound in the javadoc. Whether the **surface** (unstemmed) variant is emitted is a measured
  decision — Slovak emits none, the other three do.
- Normalization filters the chain needs (`CommaBelowNormalizationFilter` is the precedent) — `final class
  extends TokenFilter`, javadoc saying **where in the chain it must sit and why** (before the stop filter,
  before the stemmer, …).
- Vendored code (a stemmer the pinned Lucene lacks): keep the generated algorithm untouched except for
  package/class rename and any runtime shims; upstream-licence paragraph in the file header; a section in
  `evita_engine/NOTICE` naming the source commit and every adaptation (`PolishSnowballStemmer` precedent).
- Stop list the pinned Lucene ships only inside a jar you do not want to depend on: copy the resource
  verbatim (header included) to `evita_engine/src/main/resources/io/evitadb/index/fulltext/analysis/
  <language>-stopwords.txt`, load it lazily through a nested holder class (`BuiltInAnalyzers.PolishStopWords`),
  and add the NOTICE section stating the **original** author and licence (Lucene's Polish list is carrot2/BSD,
  not Apache — check the header). No list anywhere → the chain has no stop filter; say so in the class table.

**Javadoc policy** for every moved class: documented as a standalone unit — what it does, its contract and
constraints (input folded + lowercased; the folded image of the index-side stem is always among the variants;
`VariantStemFilter` must stay the **last** filter of its chain; variants share one position). No prototype
history, no matrix-row numbers, no "flat union vs branching" narrative. One pointer:
`See documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/ for the measurements …`.

Code style: `.claude/rules/code-style.md` (tabs, `@Nonnull`, defensive design, no allocation on the hot path
for the stemmers — they run per token).

## 2. `BuiltInAnalyzers`

- Constants: `<LANG>_ANALYZER_NAME = "<language>"` and, for a pair, `<LANG>_SEARCH_ANALYZER_NAME =
  "<language>-search"` — the codebase's vocabulary for the slot (`AnalyzerSlot.SEARCH`, `getSearchAnalyzer`).
  Run the naming check from `CLAUDE.md` before minting anything else.
- `ANALYZERS_BY_NAME`: `allModes(...)` for the index/uniform chain, `searchTime(...)` for the search chain —
  the mode is the type-level guard that a variant fan-out can never be assigned to the indexing slot.
- `ASSIGNMENTS_BY_LANGUAGE`: `"<xx>" → new AnalyzerAssignment(index, search, null)` for a pair,
  `AnalyzerAssignment.uniform(name)` otherwise. Key by ISO language, never by locale.
- Chain factories as private static methods returning an anonymous `Analyzer` with **both**
  `createComponents` and `normalize` overridden — `normalize` is the single-term prefix/fuzzy path and must
  fold and lowercase (and run any normalization filter that changes letters the fold does not). Mirror the
  measured row exactly; do not invent a composition the matrix never measured.
- Class javadoc: add the language's row to the "What each language gets" table; if the language broke a
  stated rule (e.g. "German must not get the folding wrapper"), add its paragraph.
- Remove nothing else. `FulltextAnalyzerRegistry` needs no change for a new language — it already resolves
  through `assignmentForLocale` and `definitionFor`.

## 3. Tests — `evita_test/evita_functional_tests/.../index/fulltext/analysis/`, tags `ENGINE` + `FULLTEXT`

### 3.1 Extend the production suites

- `FulltextAnalyzerTest` — a new `@Nested class <Lang>` following the existing per-language classes: index
  chain smoke expectations seeded from Lucene's own `Test<Lang>Analyzer` / `Test<Lang>Stemmer`
  (`p5-analyzers.md` §10.2), with at least one word carrying **a diacritic and a capital letter** (the NFC +
  lowercase guard); stop words dropped on both sides; a declension convergence; for a pair, the search chain's
  variant fan-out on one or two words with `positionIncrement 0` after the first variant; both orthographies
  converging if the language has an encoding split. If a stop list was bundled, a test that the resource is
  the complete upstream list, not a truncated copy.
- `FulltextAnalyzerRegistryTest` — add the language to the "defaults are asymmetric between the slots" test
  (`shouldResolveAsymmetricBuiltInAssignmentForFoldingLanguages`) for a pair, or to the uniform test for
  step 0/symmetric; the search built-in appears in "cannot be assigned to the indexing slot" and "resolvable
  by name from a schema assignment".
- `LanguageAnalyzerPairRecallTest` — a new `@Nested class <Lang>` pinning accent-typed recall, bare-typed
  cross-form recall and the false-merge ceiling at the measured numbers, over the fixture's `VOCABULARY` and
  `CONFUSABLE_LEMMAS` (and the fixture's `bareType` when it has one). These pins replace the matrix as the
  regression guard. When the false-merge count is not zero, a comment must say which planted confusables
  account for every merge (the Polish 24 is the precedent).
- `<Lang>VariantStemmerLexiconTest` — the lexicon sweep retargeted from the flat union to the **production**
  `VariantStemmer` (`LexiconCoverageSweep.sweep(dic, fold, accentedStem, variantStemmer)`), asserting
  six-figure tested count and zero uncovered words. `SlovakVariantStemmerLexiconTest` is the template.
- `Branching<Lang>StemmerEquivalenceTest` on `BranchingEquivalenceSupport` — the production walk equals the
  flat `Folded<Lang>Stemmer.allHypotheses()` union over every folded headword, on the boundary words, and
  filter-level per position (`VariantStemFilter` vs `HypothesisStemFilter`). If the full-lexicon method
  exceeds the fast loop's budget, `@Tag(SLOW)` that method only.

### 3.2 Keep in test scope — the executable specification

`Folded<Lang>Stemmer` (flat port with switches and `allHypotheses()`), `<Lang>AnalysisFixture`, the Hunspell
dictionary pair. The records are explicit that both forms stay: an edit to the flat port that reopens a
coverage gap fails the lexicon sweep, one that breaks the walk's mirror fails the equivalence test.

### 3.3 Delete — every instrument that does not test production code

- `<Lang>AnalysisApproachMatrixTest`. **Not** `AnalysisApproachMeasurer`, `Lemma`, `FoldedStemmer`,
  `FoldedStemFilter`, `HypothesisStemFilter` or `LexiconCoverageSweep` — the shared harness stays; the recall,
  lexicon and equivalence tests run on it.
- Any prototype-only filter, stemmer configuration or chain built for a single row (dictionary-stemmer rows,
  legacy-shape rows) and any **test dependency** added for one row (root `dependencyManagement` entry + the
  functional-tests `pom.xml` entry — the `lucene-analysis-morfologik` precedent).
- Any JMH benchmark that compared prototype forms; the production cost census is
  `FulltextAnalysisChainBenchmark` — add the language to its `@Param`, `QUERY_TEXT` (a 3-token bare query)
  and `DESCRIPTION_TEXT` (~50 accented words with stop words) instead.
- Any test-scope copy of a class that now lives in `evita_engine` (a vendored stemmer moved to production
  must not stay duplicated in tests).

`git rm`, never comment out (`CLAUDE.md`: no commented-out code, no TODOs).

## 4. Provenance and licences

- `evita_engine/NOTICE` — one section per vendored file or copied resource: source project, licence, exact
  upstream commit/version, every modification, why the copy exists. The two existing sections are the format.
- `evita_test/evita_functional_tests/src/test/resources/fulltext/hunspell/README.md` — a per-dictionary
  section (upstream, retrieval path with wooorm commit, licence and which option evitaDB relies on) plus the
  sha256 lines; update the sentence counting the dictionaries and the list of tests that read them.
- `evita_test/evita_functional_tests/NOTICE` — the dictionary's licence statement verbatim.
- Anything GPL-only or licence-ambiguous is reported to the user in the same message as the finished work,
  not resolved silently.

## 5. Records and the plan

No new ADR: this is the same line of work as `documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/`
(`.claude/rules/adr.md`, batching). Instead:

- Append **"Graduated to production (<date>)"** to `p5-prior-art-<lang>.md`: what shipped (chain per slot, the
  name constants), what was renamed from the prototype vocabulary (a two-column table), the test disposition
  (what was deleted, what replaced it, the pinned numbers), the reindex consequence for catalogs indexed with
  any earlier chain, and every decision the user took at Gates A and B with its reason.
- Add the language to `p5-analyzers.md` §5 "Language coverage" — a subsection or a resolution block in the
  style of the Slovak one (`> **Superseded (date):** …`), and fix its `BuiltInAnalyzers` name count if stated.
- Update `.claude/skills/new-language-analyzer/` only if the procedure itself changed (a new trap, a new
  precedent) — not for the language's outcome, which belongs in the records.
- **Delete the plan folder** `specifications/<issue>-fulltext-<lang>-analyzer/` — read it once against the
  record first and move anything unrecoverable (measurements that cannot be regenerated, ordering hazards)
  into the record. Deletion is irreversible; the folder is not in git.
- Check `documentation/user/en/` for any page enumerating supported full-text languages
  (`rg -n "czech-search|romanian-search|built-in analyzer" documentation/user/en`); if one exists, extend the
  English source only (`.claude/rules/documentation.md`).

## 6. Verify and commit

```shell
mvn -pl evita_test/evita_functional_tests test -P unitAndFunctional -Dgroups="fulltext"
mvn -pl evita_test/evita_functional_tests test -P unitAndFunctional -Dgroups="fulltext & slow"   # once
```

Then, if a search chain was added, run `FulltextAnalysisChainBenchmark#main` once and confirm the query
chain stays within the envelope the records quote (≤ 2× the index chain per analyze); paste the numbers into
the record's graduation section.

Before committing: revert `**/grpc/generated/` if the build rewrote it; `rg -n "TODO|hypothes" evita_engine/
src/main/java/io/evitadb/index/fulltext/analysis/` must return nothing; no customer name anywhere
(`CLAUDE.md`). Commit shape, all with `Ref: #<issue>`:

- `feat: add the <language> built-in analyzer pair` (or `… analyzer`) — production classes, `BuiltInAnalyzers`,
  new/extended production tests, NOTICE, resources.
- `test: drop the <language> analysis instruments` — the deletions of §3.3 (may be folded into the first
  commit if the diff stays reviewable).
- `docs: record the <language> analyzer graduation` — the record updates of §5.

Report to the user: which shape shipped, the pinned numbers, what was deleted, every licence or naming
decision they still own, and that catalogs indexed with any earlier chain for the language need a reindex.
