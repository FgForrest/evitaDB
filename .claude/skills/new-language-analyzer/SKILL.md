---
name: new-language-analyzer
description: Analyse the analyzer options for a language evitaDB's full-text pipeline does not yet cover, and/or add that language as a built-in analyzer pair. Surveys the seven local OSS engine checkouts (lucene, solr, elasticsearch, OpenSearch, vespa, meilisearch, typesense) for prior art, builds the JUnit comparison matrix that measured cs/sk/pl/ro, writes the measurement record for the user to decide on, and graduates the chosen approach into `BuiltInAnalyzers` the way the final Czech pipeline shipped. Invoke when asked to analyse or research analyzer/stemmer options for a new language, to add a new language or Lucene analyzer to the built-in table, to compare full-text analysis approaches for a language, or any combination of those.
allowed-tools: Read, Edit, Write, Grep, Glob, Bash(git *), Bash(rg *), Bash(grep *), Bash(ls *), Bash(find *), Bash(cat *), Bash(head *), Bash(sed *), Bash(wc *), Bash(sha256sum *), Bash(cp *), Bash(mkdir *), Bash(cd *), Bash(mvn *), AskUserQuestion
---

# New built-in language analyzer

evitaDB's full-text analysis is a Lucene chain per language, declared in
`evita_engine/src/main/java/io/evitadb/index/fulltext/analysis/BuiltInAnalyzers.java`. A language the table
does not know falls back to `generic` (tokenize + lowercase, nothing else) with a one-time warning. Adding a
language is **not** "wire the Lucene analyzer and go": for cs/sk/pl/ro the survey-then-measure procedure
recorded under `documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/prototypes/` found that the
obvious wiring was wrong for every one of them, and each ended up with an **asymmetric pair** — an index chain
that stems accented text and folds afterwards, and a `<language>-search` chain that folds first and emits every
stem variant (`VariantStemmer`). This skill replays that procedure for a new language, with the user deciding at
each gate.

**The skill has three phases and two decision gates. Never skip a gate — the decisions are the user's.**

| Phase | Deliverable | Who decides what happens next |
|---|---|---|
| **A. Prior-art survey** | `p5-prior-art-<lang>.md` §1–§8: what Lucene (pinned + HEAD) and six other engines ship for the language, evitaDB's current state, the ladder of options | **Gate A** — the user: adopt a Lucene analyzer as it is (step 0), run the approach analysis (Phase B), or stop |
| **B. Approach matrix** | the same record's §9: a JUnit matrix over a sourced per-language fixture, every candidate mechanism measured on five metrics, a ledger of corrections | **Gate B** — the user: which approach ships, or none |
| **C. Production** | the chosen chain(s) in `BuiltInAnalyzers`, production tests, instruments deleted, records/NOTICE updated, plan removed | — |

Detail per phase lives in the three companion files; this file is the procedure and the rules that hold across
all of it.

## 0. Preconditions — check before anything else

### 0.1 The engine checkouts must be on disk. Never clone them.

The survey reads code, so the repositories have to exist locally, side by side under one root named by the
environment variable `OSS_PROJECT_PATH` (e.g. `export OSS_PROJECT_PATH="$HOME/www/oss"`). Every path in this
skill is written against that variable. **If it is unset, ask the user where the checkouts live** — never
guess a root, and never fall back to a hardcoded one. Then check all of them:

```shell
test -n "$OSS_PROJECT_PATH" || echo "OSS_PROJECT_PATH is not set - ask the user"
for p in lucene solr elasticsearch OpenSearch vespa meilisearch typesense hunspell-dictionaries; do
  d="$OSS_PROJECT_PATH/$p"
  if [ -d "$d/.git" ]; then
    echo "$p: $(git -C "$d" -c safe.directory='*' log -1 --format='%h %ad %D' --date=short)"
  else
    echo "$p: MISSING ($d)"
  fi
done
```

- **Any of the seven engines missing → stop and ask the user** (AskUserQuestion) to put a checkout there, or
  to name the path where it already is. **Never `git clone`, `curl`, `wget` or otherwise fetch a repository
  yourself** — the user does that, deliberately, and records which revision they fetched.
- `hunspell-dictionaries` is the local clone of `github.com/wooorm/dictionaries`
  (`dictionaries/<code>/index.dic`, `index.aff`, `license`). It is needed only from Phase B on (the lexicon
  sweep); if absent, ask for it at that point, not before.
- Record every HEAD (`%h %ad %D`) in the record's header table, exactly as `p5-prior-art-sk-pl-ro.md` does —
  every `path:line` citation is against those revisions and must be dateable.
- If `git` refuses with "dubious ownership" (a sandbox artefact), read with `git -C <dir> -c safe.directory='*'
  …`; never change the user's global git config.

### 0.2 The pinned Lucene is not the checkout's HEAD

The project pins `lucene.version` in the root `pom.xml` (9.12.3 at the time of writing); the checkout at
`$OSS_PROJECT_PATH/lucene` is `main`. **Three of the SK/PL/RO survey's statements about "what Lucene ships" were untrue
at the pinned version** (`p5-prior-art-sk-pl-ro.md` §9.1). So for every Lucene claim, read **both**:

```shell
LUCENE_VERSION=$(sed -n 's/.*<lucene.version>\(.*\)<\/lucene.version>.*/\1/p' pom.xml)
git -C "$OSS_PROJECT_PATH"/lucene -c safe.directory='*' show "releases/lucene/$LUCENE_VERSION:lucene/analysis/common/src/java/org/apache/lucene/analysis/<xx>/<Lang>Analyzer.java"
```

If the `releases/lucene/<version>` tag is missing from the checkout, ask the user to fetch it — do not fetch.
A component that exists at HEAD but not at the pinned tag is a **vendoring** question (the
`PolishSnowballStemmer` precedent, `evita_engine/NOTICE`), not an adoption.

### 0.3 Read the records before writing a line

Search `documentation/adr/` for the language first (`rg -il "<language>|<xx>" documentation/adr/`). Then
read, in this order, taking from each what the table says:

| Record (all under `documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/prototypes/`) | Take from it |
|---|---|
| `p5-prior-art-accent-vs-stemming.md` — the **Research brief** (its first ~250 lines) and §11 (mechanisms M1–M7) | the six questions, the rules of evidence, the mechanism vocabulary every later record uses |
| `p5-prior-art-sk-pl-ro.md` §1–§8 and §9.1, §9.7 | how a multi-language survey is laid out; the pinned-vs-HEAD trap; the limits section |
| `p5-approach-measurements-accent-vs-stemming.md` §3, §4, §6, §10 | the five metrics and **the trap in them**, how a matrix is read, what M7 is, why a ledger of fixture corrections is kept from run 1 |
| `p5-analyzers.md` §4.4, §4.6, §5, §7.2, §10.2 | index/query chain contract, the filter catalog, per-language coverage decisions, the NFC trap, where Lucene's own test expectations are |
| `p5-word-number-split-comparison.md` | only if the language's tokenization raises a splitting question |

Also read the current production shape: `BuiltInAnalyzers.java` (class javadoc + one chain pair),
`VariantStemmer.java`, `VariantStemFilter.java`, and one in-house pair (`SlovakStemmer` + `SlovakVariantStemmer`)
if the language may need its own stemmer.

## 1. Phase A — prior-art survey

Follow [PRIOR-ART-BRIEF.md](PRIOR-ART-BRIEF.md). In short:

1. **State where evitaDB stands** for the language today (`BuiltInAnalyzers.ASSIGNMENTS_BY_LANGUAGE` — usually
   "unmapped, falls to `generic`"), and whether the language even has the bare-typing problem: a Latin-script
   language whose users omit diacritics (the cs/sk/pl/ro shape), a language without diacritics (the folding
   axis is vacuous — the question collapses to stemmer choice), or a non-Latin script (folding is a different
   question altogether; say so, and do not force the M-mechanism frame onto it).
2. **Survey the repositories one at a time, sequentially**, in the order lucene → solr → elasticsearch →
   OpenSearch (delta only) → vespa → meilisearch → typesense. Never fan out parallel agents.
3. **Write the record incrementally** to
   `documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/prototypes/p5-prior-art-<lang>.md`
   (one record per language, or per batch of languages handled together — the sk/pl/ro record is the shape),
   and keep the working plan in `specifications/<issue>-fulltext-<lang>-analyzer/plan.md` (git-ignored,
   deleted at the end — `.claude/rules/adr.md`).
4. **End with the ladder, not a recommendation**: step 0 (what can be wired today with zero new code and what
   it buys), the M-mechanisms that apply, what each needs built, what is unknown without measuring.

**Gate A.** Present the verdict and the ladder to the user and ask (AskUserQuestion), with the options:
(a) wire the step-0 chain as it is and stop — the user judges the Lucene analyzer sufficient; (b) run the
approach matrix (Phase B); (c) stop here. If (a): do Phase C with only the step-0 chain (a uniform
`AnalyzerAssignment`, or the folding wrapper — whichever the survey's Q1 answer says), skipping every
variant-stemmer item. Note in the record that the matrix was not run and why.

## 2. Phase B — approach matrix

Follow [MATRIX-HARNESS.md](MATRIX-HARNESS.md). In short:

1. **Use the harness that is in the tree.** `AnalysisApproachMeasurer` (the five metrics), `Lemma`,
   `FoldedStemmer`, `FoldedStemFilter`, `HypothesisStemFilter` and `LexiconCoverageSweep` are permanent
   test-scope classes next to the production tests. What is new per language is copied from
   `templates/` in this skill folder: the fixture and the matrix test. Nothing is recovered from git history.
2. **Author the fixture under the vocabulary protocol** — every form sourced and citable, e-commerce domain,
   every stemmer rule given both a benefit case and a cost case, confusables found from the stemmer tables.
   Flag "not natively reviewed" in the fixture javadoc when true.
3. **Build the candidate chains as matrix rows** with a language-specific row prefix, measure them on the five
   metrics, iterate on fixture corrections with a ledger from run 1.
4. **Run the lexicon sweep** over the Hunspell `.dic` before trusting any "no fold-ambiguity" finding — the
   fixture can only falsify what it carries (the Slovak lesson, `p5-prior-art-sk-pl-ro.md` §9.4).
5. **Write §9 of the record**: environment divergences, per-language matrix table, ledger, what the ladder
   looks like after measuring, limits. Numbers, failing cases, and the decisions the user has to take —
   no decision taken for them.

**Gate B.** Hand the record to the user and ask which row ships (AskUserQuestion, options built from the
measured rows — typically: the step-0 chain, the best symmetric row, the M7 asymmetric pair, none). Record the
decision and its date in the record before touching production code.

## 3. Phase C — production

Follow [PRODUCTION-CHECKLIST.md](PRODUCTION-CHECKLIST.md). The shape is the one the four shipped languages
have today — read `BuiltInAnalyzers` and the Slovak or Romanian pair of classes and tests as the worked
example; the checklist names every file. In short:

1. Production classes in `evita_engine` — no prototype vocabulary in names or javadoc, one ADR pointer each.
2. `BuiltInAnalyzers` — name constants, table entries, assignment, chain factories, class-javadoc table row.
3. Tests — extend the production suites (`FulltextAnalyzerTest`, `FulltextAnalyzerRegistryTest`,
   `LanguageAnalyzerPairRecallTest`, lexicon + equivalence tests), **then delete every instrument that does not
   test production code** (the language's matrix test, prototype-only filters and benchmarks, test
   dependencies added for a single row). The shared harness stays — the recall test runs on it.
4. NOTICE / provenance for anything vendored or copied; hunspell `README.md` + sha256 for a new dictionary.
5. Records: resolution notes appended to the language's record and to `p5-analyzers.md` §5; the plan in
   `specifications/` deleted. No new ADR — this is the same line of work as the 2026-08-24 record.
6. Verify: `mvn -pl evita_test/evita_functional_tests test -P unitAndFunctional -Dgroups="fulltext"`, then a
   manual run of `FulltextAnalysisChainBenchmark` if a search chain was added.

## 4. Rules that hold across all phases

- **Sequential, one repository / one language / one row at a time.** No parallel agents for the survey; when
  several languages are requested, finish one language's Phase A before starting the next, and never share
  a fixture or a matrix between languages.
- **Row IDs are per-language** (`R`/`P`/`S` are taken by ro/pl/sk, `A` by cs). Pick an unused prefix and never
  reuse a number for a different chain — the existing IDs are load-bearing in three records.
- **Cite `path:line`; read code, not only docs; distinguish default / configurable / recommended /
  build-it-yourself; refuting a hypothesis is a result; never write a citation you did not open.**
- **Console output mangles diacritics.** Read matrix output from
  `evita_test/evita_functional_tests/target/surefire-reports/*.xml`, not from the terminal.
- **Maven**: never pass `-o`; every build rewrites the committed gRPC generated sources under
  `**/grpc/generated/` — revert them unless the change was intended. The fast loop is
  `mvn -pl evita_test/evita_functional_tests test -P unitAndFunctional -Dtest='<Class>'`; tag every test
  method `@Tag(ENGINE) @Tag(FULLTEXT)` (`.claude/rules/testing.md`).
- **Licences are verified, never estimated** — per dictionary, per vendored file, from the upstream `license`
  file itself. A GPL-only dictionary can sit in test resources under the mere-aggregation argument the hunspell
  `README.md` makes, but that is a decision to show the user, not to make.
- **No customer names** anywhere in the record, the fixture, the code or the commits — describe the workload
  (`CLAUDE.md`, "Customer names"). The fixture vocabulary is generic e-commerce, never sample values from a
  customer dataset.
- **Commits**: `test:` for instruments and fixtures, `feat:` for the production graduation, `docs:` for record
  updates, each with `Ref: #<issue>` (`.claude/rules/git-workflow.md`). No co-author lines.

## 5. Where everything lives

| What | Path |
|---|---|
| Built-in table, chains, production stemmers | `evita_engine/src/main/java/io/evitadb/index/fulltext/analysis/` |
| Bundled stop lists (resource per language) | `evita_engine/src/main/resources/io/evitadb/index/fulltext/analysis/` |
| Vendored-code / copied-resource attribution | `evita_engine/NOTICE` |
| Production tests, fixtures, flat specification ports, the shared metric harness, sweeps | `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/fulltext/analysis/` |
| Templates of the per-language fixture and matrix test | `.claude/skills/new-language-analyzer/templates/` |
| Hunspell dictionaries + provenance `README.md` | `evita_test/evita_functional_tests/src/test/resources/fulltext/hunspell/` |
| Dictionary licence statements | `evita_test/evita_functional_tests/NOTICE` |
| Cost census benchmark | `evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/FulltextAnalysisChainBenchmark.java` |
| Research + measurement records | `documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/prototypes/p5-prior-art-<lang>.md` |
| Language coverage decisions | `documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/prototypes/p5-analyzers.md` §5 |
| In-flight plan (git-ignored, deleted at the end) | `specifications/<issue>-fulltext-<lang>-analyzer/` |
| Engine checkouts to survey | `$OSS_PROJECT_PATH/{lucene,solr,elasticsearch,OpenSearch,vespa,meilisearch,typesense}` |
| Hunspell dictionary source | `$OSS_PROJECT_PATH/hunspell-dictionaries/dictionaries/<code>/{index.dic,index.aff,license}` |
