# Phase A — the prior-art brief for one language

This generalizes the research brief at the top of `p5-prior-art-accent-vs-stemming.md` and the per-language
layout of `p5-prior-art-sk-pl-ro.md`. Read both before starting; this file only tells you what to fill in.

**Mode:** read-only research. Never modify anything in the surveyed repositories; change no evitaDB file
other than the record and the plan.

## 1. Frame the problem for this language first

Before opening a foreign repository, write the record's §2 "Where evitaDB stands today" from
`BuiltInAnalyzers.java`: is the language mapped (which chain, folding where, stemming what) or unmapped
(falls to `generic`)? Then classify the language, because it decides which questions below matter:

- **Latin script, diacritics users routinely omit when typing** (cs, sk, pl, ro) — the full brief applies:
  folding vs stemming order, mechanisms M1–M7, the asymmetric pair.
- **Latin script, few or no diacritics** (en) — stemmer choice and stop words; the folding rows of Phase B
  are vacuous and are skipped.
- **Latin script, diacritics handled by a Lucene normalization filter** (de) — Q1 decides whether
  `DiacriticsFoldingAnalyzerWrapper` must **not** be applied (German's `ß`→`ss` is the precedent).
- **Non-Latin script** (none yet) — folding means something else (Cyrillic `ё`→`е`, Greek final sigma, CJK
  segmentation); say so plainly and survey what the engines *normalize*, not what they "fold".

State the classification in §1 of the record. Do not force the M-mechanism vocabulary onto a language it does
not fit.

## 2. The questions to answer, per engine

Answer each with `path:line` evidence. "This engine does not address it" is a first-class answer.

- **Q0 — What does Lucene ship, at the pinned version and at HEAD?** Analyzer class, stemmer (Snowball /
  light / minimal / dictionary), normalization filter, stop list (and its orthography), and the
  `createComponents` order. Read the pinned tag **and** `main`; every divergence goes into the record's §9.1
  later. Check the Snowball ext folder (`lucene/analysis/common/src/java/org/tartarus/snowball/ext/`) and the
  non-core modules (`lucene/analysis/{stempel,morfologik,icu,opennlp,kuromoji,nori,smartcn}`) — a stemmer may
  exist outside `analysis/common`. Read `lucene/CHANGES.txt` for when it arrived.
- **Q1 — Is normalize-before-stem the shipped pattern for this language, with the stemmer written for
  normalized input?** Where the normalization filter sits relative to the stemmer in `createComponents`; what
  the stemmer's javadoc or code says about diacritics (literal accented chars in tables = accents required).
- **Q2 — More than one lane per term?** `preserveOriginal`, `KeywordRepeatFilter` +
  `RemoveDuplicatesTokenFilter`, multi-field idioms; by default or only configurable.
- **Q3 — Asymmetric query-side analysis?** Separate search analyzer supported, warned against, or used by a
  shipped language config.
- **Q4 — Escape hatches** when stemming and folding cannot both be had: fuzzy in folded space, collation keys,
  prefix/n-gram, spell-check suggester in the query path, ICONV/OCONV.
- **Q5 — Skip stemming entirely** and pay for inflection otherwise (Meilisearch, Typesense default).
- **Q6 — Written rationale** — javadoc, `.adoc`/`.md`, long comment, commit message explaining the order and
  its cost. Quote it.
- **Q7 — Stop words.** Does Lucene ship a list for the language, in which orthography, and from where (Snowball
  `*_stop.txt`, a per-package `stopwords.txt`, a module resource)? If none ships anywhere, the production
  chain will have none (the Slovak precedent) unless the user sources one — an owned artefact with provenance.
- **Q8 — Dictionaries.** Which Hunspell (or Morfologik-like) dictionary exists in
  `$OSS_PROJECT_PATH/hunspell-dictionaries/dictionaries/<code>/`, its `license` file verbatim, its headword count
  (`wc -l index.dic`). This is the lexicon Phase B sweeps. Also check if `lucene/analysis/common/.../hunspell`
  tests reference the language.
- **Q9 — Licence and vendoring exposure.** Anything that would have to be copied into `evita_engine` because
  the pinned Lucene lacks it (a newer Snowball stemmer, a stop list from a module jar) — what licence it
  carries and what the NOTICE entry would say (`evita_engine/NOTICE` has the two precedents).

## 3. Starting points per repository (verified paths)

Anchors, not a limit — follow the code. Record every HEAD in the record's header table.

### `$OSS_PROJECT_PATH/lucene` — first, the others build on it
- `lucene/analysis/common/src/java/org/apache/lucene/analysis/<xx>/` — the language package, if any
- `lucene/analysis/common/src/java/org/tartarus/snowball/ext/<Lang>Stemmer.java` — Snowball, generated
- `lucene/analysis/common/src/resources/org/apache/lucene/analysis/snowball/<lang>_stop.txt` and
  `.../analysis/<xx>/stopwords.txt` — stop lists
- `lucene/analysis/common/src/test/org/apache/lucene/analysis/<xx>/Test<Lang>Analyzer.java`,
  `Test<Lang>Stemmer.java` — **the smoke-test expectations Phase C reuses** (`p5-analyzers.md` §10.2)
- `lucene/analysis/{stempel,morfologik,icu,opennlp,kuromoji,nori,smartcn}/` — module-level stemmers
- `lucene/CHANGES.txt` — when a component arrived and in which release section
- `.../miscellaneous/ASCIIFoldingFilter.java`, `KeywordRepeatFilter.java`, `RemoveDuplicatesTokenFilter.java`
- `.../hunspell/` — `Dictionary`, `Stemmer`, `SortingStrategy.inMemory()`
- `git log` on the language package for Q6

### `$OSS_PROJECT_PATH/solr` — the likeliest shipped opinion
- `solr/server/solr/configsets/_default/conf/managed-schema.xml` — the `text_<xx>` field type, if any
- `solr/server/solr/configsets/_default/conf/lang/` — stop lists Solr ships per language
- `solr/solr-ref-guide/modules/indexing-guide/pages/language-analysis.adoc` — the language's section
- `solr/solr-ref-guide/modules/indexing-guide/pages/filters.adoc`

### `$OSS_PROJECT_PATH/elasticsearch`
- `modules/analysis-common/src/main/java/org/elasticsearch/analysis/common/<Lang>AnalyzerProvider.java` —
  exists for ~40 languages; absent means "no built-in language analyzer"
- `docs/reference/text-analysis/analysis-lang-analyzer.md` — the documented built-in chain per language
- plugins (`plugins/analysis-*`) for module-level stemmers; `Fuzziness`, `search_analyzer` for Q3/Q4

### `$OSS_PROJECT_PATH/OpenSearch` — delta from Elasticsearch only
- `modules/analysis-common/src/main/java/org/opensearch/analysis/common/`, `plugins/analysis-*`
  (`analysis-ukrainian`, `analysis-stempel`, … — note plugins ES does not have). No delta → one paragraph.

### `$OSS_PROJECT_PATH/vespa`
- `linguistics/src/main/java/com/yahoo/language/simple/` — `SimpleNormalizer`, `SimpleTransformer`,
  `SimpleLinguistics`, the language → stemmer mapping (which languages are folded but never stemmed)
- `linguistics/src/main/java/com/yahoo/language/process/` — `StemMode`, `Normalizer`, `Transformer`
- `opennlp-linguistics/` — which Snowball languages OpenNLP-Vespa wires and whether input reaches them folded
- `indexinglanguage/.../expressions/` — `NormalizeExpression`, `StemExpression` order in shipped defaults

### `$OSS_PROJECT_PATH/meilisearch`
- `crates/milli/Cargo.toml` — the `charabia` version and feature flags per language (which scripts get a
  specialized segmenter/normalizer); charabia itself is **not** in-tree — confine yourself to how it is used
- `crates/milli/src/search/` — typo tolerance, prefix handling, symmetry of normalization

### `$OSS_PROJECT_PATH/typesense`
- `src/tokenizer.cpp`, `include/tokenizer.h` — normalization path, locale branches
- `src/stemmer_manager.cpp`, `include/stemmer_manager.h` — which Snowball languages `stem: true` accepts, and
  whether the stemmer receives folded input

## 4. Rules of evidence

1. Cite `path:line` for every claim; a claim without one does not go in.
2. Read the code, not only the docs; where they disagree, report both and say which is which.
3. Distinguish **default / configurable / recommended / build-it-yourself**. Category four is not a solution
   the engine has.
4. Never extrapolate from one language to another. "Folds for German" says nothing about the language at
   hand; "ships no config for it" is itself the finding.
5. Refuting a hypothesis is a result. Say when nobody does a thing.
6. No fabrication. Missing file or moved path → say what you looked for and what you found instead.
7. "Expected to transfer from Czech" is a **hypothesis to measure in Phase B**, never a conclusion. Mark every
   such claim so §9 can resolve it (`p5-prior-art-sk-pl-ro.md` did exactly this and several did not survive).

## 5. Record structure — `p5-prior-art-<lang>.md`

Mirror `p5-prior-art-sk-pl-ro.md`:

0. **Header block** — status line, companions it builds on, the HEAD table of every checkout with date and
   branch, any source consulted outside the checkouts (name it and its revision).
1. **Verdict** — 5–10 sentences: which rung of the ladder the language lands on and why.
2. **Where evitaDB stands today** — the current row of `BuiltInAnalyzers` for the language, with line numbers.
3. **Cross-engine matrix** — one row per engine: fold by default · stemmer kind · order · lanes · asymmetric ·
   shipped config for this language · escape hatch.
4. **The language** — *What Lucene ships* (pinned **and** HEAD, divergences called out) · *What the other
   engines do* · *How to make it better* (step 0 with zero new code; each applicable mechanism with what it
   needs built, its expected cost and its unknowns).
5. **The ladder, summarized** — ordered steps and what each buys.
6. **Limits of this survey** — what could not be settled from the checkouts and where it would be settled.
7. **Dead ends** — options the code refutes, each with why.
8. **Open questions** — for the user, phrased as decisions with the information needed to take them.

No recommendation section, no code, no decision taken. Flag anything that has to be settled before an index
freeze. The record is committed (it is a research record of the umbrella ADR, like its siblings); the plan
with the working steps stays in `specifications/` and is never committed.

## 6. Execution discipline

- One repository at a time, sequentially, lucene → solr → elasticsearch → OpenSearch → vespa → meilisearch →
  typesense. No parallel agents.
- Scope `rg` with `--glob`; do not attempt to read a repository exhaustively.
- Write the record incrementally, appending each engine's section as it is finished.
- An unanswerable question goes to *Limits* or *Open questions* and you move on.
- Finish with **Gate A** (see `SKILL.md` §1): present the verdict and ladder, ask the user which way to go.
