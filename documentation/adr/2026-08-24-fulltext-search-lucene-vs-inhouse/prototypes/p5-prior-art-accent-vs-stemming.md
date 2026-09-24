# P5 prior art — reconciling diacritics folding with stemming

> **Status: research record, an implementation reference for P5
> ([`p5-analyzers.md`](p5-analyzers.md)).** This document merges two artifacts of one research
> run executed on 2026-08-27: first the **assignment** (the research brief, preserved as issued —
> the deliverable path it names is superseded by this merged document), then the **findings**.
> Seven engine repositories were surveyed sequentially at the HEADs recorded in the findings'
> opening table, plus an addendum on the in-house EdeeCMS analyzers (§14 of the findings).

---

## Research brief — how other search engines reconcile diacritics folding with stemming

**For:** a Fable agent, run from the evitaDB working copy.
**Deliverable:** one markdown report at
`specifications/258-fulltext-p5-analyzers/prior-art-accent-vs-stemming.md`.
**Mode:** read-only research. Never modify anything in the surveyed repositories, and change no
evitaDB file other than writing the report.

---

### 1. The problem you are researching

evitaDB's Czech full-text chain is `StandardTokenizer → LowerCaseFilter → StopFilter →
CzechStemFilter → ASCIIFoldingFilter` — folding is appended **after** the stemmer
(`evita_engine/.../analysis/DiacriticsFoldingAnalyzerWrapper.java`).

That order is forced by `CzechStemmer`: its ending tables are written with precomposed accented
characters and it switches on literal `'á'` / `'ě'`, so a stemmer fed folded text silently stops
stemming. Folding last keeps the stemmer working.

**The cost is the mirror image on the query side.** Czech users routinely type without accents. A
bare-typed query never gets its ending stripped, because the ending the stemmer looks for is
spelled with accents. The two sides then never meet, and at 3+ edits the gap is beyond a fuzzy
lane's reach (`LevenshteinAutomata.MAXIMUM_SUPPORTED_DISTANCE = 2`).

**This is measured, not suspected.** `CzechAccentTypingTest` (in
`evita_test/evita_functional_tests/.../index/fulltext/analysis/`) measures two properties over a
30-lemma / 108-form Czech e-commerce vocabulary — *inflection convergence* (all accented forms of
a lemma share a term) and *accent-typed recall* (the accent-stripped typing shares a term with the
accented form):

| chain                              | accent-typed matches | forms converge |
|------------------------------------|----------------------|----------------|
| CzechStemFilter, fold **after**    | 83 / 108 (77 %)      | 30 / 30        |
| CzechStemFilter, fold **before**   | 108 / 108            | 12 / 30        |
| Hunspell `cs_CZ`, fold **after**   | 27 / 108 (25 %)      | 28 / 30        |
| Hunspell `cs_CZ`, fold **before**  | 108 / 108            | 0 / 30         |

Read that table carefully — it is the shape of the problem:

- **Neither pure folding order wins.** Each corner is perfect on one axis and broken on the other.
- **Hunspell does not escape it, it is worse.** A dictionary lookup on an accent-stripped word
  simply misses, so the word passes through unstemmed; and an accent-stripped Czech word is
  sometimes a *different real word*, which Hunspell then lemmatizes confidently — `bily → bít`
  (to beat, not *bílý*/white), `košili → kosit` (to mow), `stul → stulit` (to curl up). Under
  fold-before that is a false merge, not merely a miss.
- The failing forms are one systematic class, not noise: adjective gen./loc. plural `-ých`,
  instrumental `-ým`, noun `-ám`/`-ách`/`-ům`, vowel shifts (`stůl→stol`, `dřevěný→dreven`), plus
  a reverse case where the *bare* form is **over**-stemmed (`kabát→kabat` but `kabat→kab`).

**So the research question is not "should we fold before or after".** It is: *what do mature
engines actually do about the fact that both orders are wrong?*

---

### 2. The six questions to answer

Answer each **per engine**, with `path:line` evidence. "This engine does not address it" is a
first-class answer and must be stated plainly when true — do not manufacture a mechanism.

**Q1 — Is normalize-before-stem the established pattern, with the stemmer written for normalized
input?** This is the highest-value question, so do it first and do it thoroughly.

Lucene's German chain is `LowerCaseFilter → GermanNormalizationFilter → GermanLightStemFilter`:
folding comes **first**, and the stemmer is written to expect folded input. Same shape appears for
Arabic, Persian, Sorani, Scandinavian. If that is the general pattern, then Czech is the outlier
that has *no* normalization filter and a stemmer that demands accents — and the real fix is a
Czech normalization filter plus a stemmer whose tables are written in folded space, not a folding
order at all.

Survey **every** language package in `lucene/analysis/common/src/java/org/apache/lucene/analysis/*/`
and produce a table: language · does it ship a `*NormalizationFilter` · where the filter sits
relative to the stemmer in that language's `Analyzer.createComponents` · does the stemmer's javadoc
or code state an expectation about diacritics. Then state whether Czech is genuinely the exception.

**Q2 — Does the engine index more than one lane per term?** i.e. a folded *surface* form alongside
the stem, so a bare-typed query can hit an exact-match lane when the stem lane misses. Look for
`preserveOriginal` flags, `KeywordRepeatFilter` + `RemoveDuplicatesTokenFilter`, multi-field /
sub-field idioms, and whether any engine does this **by default** rather than only offering it as a
configuration an operator must know to reach for. Report the term-dictionary cost where the code or
docs state it.

**Q3 — Is query-side analysis ever deliberately different from index-side?** Asymmetric analysis is
one way out: index the stem, and at query time emit both the stem and the folded surface form.
Find whether any engine supports declaring a separate search analyzer, whether its docs warn
against it, and whether any shipped language config actually uses the asymmetry.

**Q4 — What is the escape hatch when stemming and folding cannot both be had?** Candidates to
verify or refute: fuzzy matching in folded space (ES `fuzziness` + `unicode_aware`); ICU collation
keys at primary strength as an accent-blind lane; prefix/n-gram matching instead of stemming; a
spell-check suggester used as a *query-rewrite* step (Hunspell's `.aff` `MAP`/`REP`/`TRY` tables are
suggestion machinery — does anyone wire the suggester into the query path rather than only into a
"did you mean"?); ICONV/OCONV input conversion.

**Q5 — Does any engine skip stemming entirely and pay for inflection some other way?** Meilisearch
and (historically) Typesense are the likely holders of this answer — aggressive Unicode
normalization, no stemmer, and prefix + typo tolerance carrying the inflection load. If that is
what they do, say what they give up and what their docs claim about recall on inflected languages.
This is a genuine third answer to the trade-off, not a non-answer.

**Q6 — Is there a written rationale anywhere?** A javadoc, an `.adoc`/`.md` in-repo doc, a
long-form code comment, or a commit message that explains the ordering choice and names what it
costs. Quote it. Prior reasoning is worth more than prior code here, because we already know both
orders are broken — what we need is somebody else's argument about which brokenness to accept.

---

### 3. Starting points per repository

These are anchors, not a limit — follow the code. Note each repo's HEAD (`git log -1
--format='%h %ad' --date=short`) in the report so the findings are dateable.

#### `$HOME/www/oss/lucene` (main, 2026-08-24) — do this one first, the others build on it
- `lucene/analysis/common/src/java/org/apache/lucene/analysis/*/` — every language package (Q1)
- `.../miscellaneous/ASCIIFoldingFilter.java` — the `preserveOriginal` constructor flag
- `.../miscellaneous/KeywordRepeatFilter.java`, `.../RemoveDuplicatesTokenFilter.java` — the
  canonical two-lane idiom; find which shipped analyzers, if any, use it
- `.../cz/CzechStemmer.java` + `CzechAnalyzer.java` — the accent expectation, stated in javadoc
- `.../de/GermanNormalizationFilter.java` + `GermanAnalyzer.java` — the counter-pattern
- `.../hunspell/` — `Dictionary` (ICONV/OCONV), `Stemmer`, `Suggester`, `WordFormGenerator`
- `lucene/analysis/icu/` — `ICUFoldingFilter`, `ICUCollationKeyAnalyzer` (Q4)
- `lucene/core/src/java/org/apache/lucene/util/automaton/LevenshteinAutomata.java` — the distance ceiling
- `git log` on `CzechStemmer.java` and `GermanNormalizationFilter.java` for Q6

#### `$HOME/www/oss/solr` (main, 2026-08-25) — most likely to hold a shipped opinion
- `solr/server/solr/configsets/*/conf/managed-schema.xml` — the **`text_cz` field type**: what
  order does Solr actually ship for Czech, and does it fold at all? Compare against `text_de`,
  `text_fr`, `text_es` and the generic `text_folded`/`text_general` types.
- `solr/solr-ref-guide/modules/**/pages/language-analysis.adoc` — the Czech section, and whatever
  the guide says about folding vs. stemming order (Q6)
- `solr/solr-ref-guide/**/filters.adoc` — `ASCIIFoldingFilterFactory` `preserveOriginal`,
  `KeywordRepeatFilterFactory`

#### `$HOME/www/oss/elasticsearch` (main, 2026-08-25)
- `modules/analysis-common/src/main/java/org/elasticsearch/analysis/common/` —
  `ASCIIFoldingTokenFilterFactory` (`preserve_original`), the per-language `*AnalyzerProvider`s:
  do ES's built-in language analyzers fold, and where?
- `docs/reference/**` (text-analysis / analysis) — the asciifolding and normalizer pages, the
  "language analyzers" page, and any multi-field guidance for accent-insensitive search (Q2, Q3)
- `Fuzziness.java` / match-query builders — `unicode_aware` (Q4)
- Whether a `search_analyzer` distinct from `analyzer` is supported and how the docs frame it (Q3)

#### `$HOME/www/oss/OpenSearch` (main, 2026-08-24)
A fork — so the interesting content is the **delta**. Do not re-report ES's mechanisms; report only
where OpenSearch diverged, added, or documented differently. If there is no delta, one paragraph
saying so is the correct output.

#### `$HOME/www/oss/vespa` (master, 2026-08-24)
- `linguistics/src/main/java/com/yahoo/language/process/` — `Normalizer`, `Transformer`, `StemMode`
- `linguistics/src/main/java/com/yahoo/language/simple/` — `SimpleNormalizer`, `SimpleTransformer`
  (accent removal), `SimpleLinguistics`
- `indexinglanguage/src/main/java/com/yahoo/vespa/indexinglanguage/expressions/` —
  `NormalizeExpression`, `StemExpression`/`TransformTokenExpression`. Vespa's indexing DSL makes the
  order **explicit and author-controlled** (`tokenize normalize stem:"BEST"`), which is the most
  directly comparable design to evitaDB's analyzer slots: what order do the shipped defaults and
  the sample schemas use, and is the choice explained?
- `StemMode.MULTIPLE` / `ALL` — does Vespa emit several stems per token, i.e. Q2 by another route?
- `opennlp-linguistics/` — which stemmers, and do they expect normalized input?
- Note if Vespa's prose documentation lives in a separate repo and is therefore out of scope.

#### `$HOME/www/oss/meilisearch` (main, 2026-08-13)
- `Cargo.toml` / `crates/*/Cargo.toml` — the `charabia` dependency and its version: the normalizer
  pipeline lives there. If `charabia` is not vendored in-tree, say so and confine yourself to how
  Meilisearch *uses* it — do not guess at charabia's internals.
- `crates/milli/src/update/index_documents/extract/` — what is stored per token
- `crates/milli/src/search/` — typo tolerance, prefix handling, and whether normalization is
  symmetric between index and query (Q3, Q5)
- Settings surface: is there any stemming at all? Any per-locale behaviour? `dictionary`,
  `synonyms`, `nonSeparatorTokens`, `localizedAttributes`

#### `$HOME/www/oss/typesense` (v31, 2026-08-18)
- `include/tokenizer.h`, `src/tokenizer.cpp` — the normalization path: NFKD, combining-mark
  stripping, locale-specific branches
- `include/field.h` / schema parsing — is there a `stem` / `stem_dictionary` option on this branch?
  If yes: does stemming run **before or after** normalization, and what stemmer?
- `src/stemmer_manager.cpp` (if present), `src/index.cpp` — where typo tolerance is applied
  relative to normalization (Q4, Q5)

---

### 4. Rules of evidence

1. **Cite `path:line` for every claim.** A claim without a citation does not go in the report.
2. **Read the code, not only the docs.** Where they disagree, report both and say which is which —
   a doc describing an option nobody's shipped default uses is a weaker finding than a default.
3. **Distinguish four things sharply**, because conflating them is how this kind of survey goes
   wrong: (a) what the engine does **by default**, (b) what it **makes configurable**, (c) what its
   docs **recommend**, (d) what a user must **build themselves**. A mechanism in category (d) is
   not a solution the engine has.
4. **Do not extrapolate from one language to another.** "It folds for German" says nothing about
   Czech, and Czech is the case at hand. Where an engine ships no Czech configuration at all, that
   is itself a finding — say it.
5. **Refuting a hypothesis in section 2 is a real result.** If nobody wires a spell-checker into
   the query path, say so; that closes an option and is worth as much as finding one.
6. **No fabrication.** If a file is missing or a path has moved, say what you looked for and what
   you found instead. Never write a `path:line` you did not open.

---

### 5. Report structure

Write `specifications/258-fulltext-p5-analyzers/prior-art-accent-vs-stemming.md`:

1. **Verdict first** — 5–10 sentences. Does anybody actually solve this, and if so how? If the
   honest answer is "everyone accepts one of the two brokennesses, and here is which one and why",
   say exactly that.
2. **Cross-engine matrix** — one row per engine, columns: folds diacritics by default (Y/N) ·
   position relative to stemmer · stemmer expects folded input (Y/N/N-A) · lanes per term ·
   asymmetric query analysis (Y/N) · has a shipped Czech config (Y/N) · escape hatch offered.
3. **Q1 language table for Lucene** — the normalization-filter survey, in full. This is the section
   most likely to change evitaDB's design, so give it room.
4. **Per engine**, one section: what it does, the evidence, and what it costs. Keep OpenSearch to
   its delta from Elasticsearch.
5. **Mechanisms found, ranked for evitaDB** — for each: what it is, who does it, which of our 25
   measured misses it would fix and which it would not, and what it costs (index size, query
   latency, a new stemmer to write and maintain, a term-dictionary layout decision). Be concrete
   against our failure classes; a mechanism that fixes `-ých` but not `kabát→kab` should say so.
6. **Dead ends** — options section 2 raised that the code refutes, each with why. Explicitly
   include whether Hunspell-as-lemmatizer is used anywhere in a way that would have avoided the
   25 % result we measured.
7. **Open questions** — what could not be settled from these repos, and where the answer would live.

No recommendation section, and no code. Deciding what evitaDB does is not part of this task; your
job is to make that decision well-informed. Flag anything that has to be settled **before the term
dictionary layout freezes** — that is the deadline this research exists to serve.

---

### 6. Execution discipline

- **Work one repository at a time, sequentially.** Do not fan out parallel agents.
- Order: **lucene → solr → elasticsearch → OpenSearch → vespa → meilisearch → typesense.** Lucene
  first because evitaDB is built on it and its answer frames every other; Solr second because it is
  the likeliest to ship an actual Czech opinion; the ES fork right after ES so the delta is cheap.
- These are large repositories. Use `rg` with `--glob` scoping and read the files that matter — do
  not attempt to read a repo exhaustively.
- Write the report incrementally, appending each engine's section as you finish it, so partial work
  survives if you run out of room.
- If a question turns out to be unanswerable from a given repo, record it under *Open questions*
  and move on rather than grinding.

---

## Prior art — how other engines reconcile diacritics folding with stemming

Findings for the assignment above. All citations are `path:line` relative to the named
repository checkout. Repository HEADs at survey time:

| repo | HEAD | date | branch |
|---|---|---|---|
| lucene | `972293ce92e` | 2026-08-24 | main |
| solr | `8479f0de485` | 2026-08-25 | main |
| elasticsearch | `4ee29c5118ca` | 2026-08-25 | main |
| OpenSearch | `849255b2bc2` | 2026-08-24 | main |
| vespa | `c339a245780` | 2026-08-24 | master |
| meilisearch | `577f7af28` | 2026-08-13 | main |
| typesense | `a7c94ee9` | 2026-08-18 | v31 |
| edeecms (addendum, §14) | `d358020a4c7` | 2026-08-18 | dev |

---

### 1. Verdict

Nobody solves both sides of this for a Czech-shaped language; every mature engine either
co-designs its way out or picks a corner and ships it. The established escape — Lucene's pattern
for German, Spanish, French, Italian, Portuguese and Greek — is **co-design**: fold first, with a
stemmer whose tables are *written for folded input* (often folding internally as its own first
step), so the ordering question dissolves. For languages without that co-design there is no
reconciliation anywhere: Lucene, Solr and Elasticsearch ship Czech with **no folding at all**
(accepting the bare-typed miss evitaDB measured at 77 %), while Vespa and Typesense fold **first
by default** and let stemming degrade or vanish (Czech is never stemmed in either — Vespa has no
Czech Snowball mapping, Typesense refuses `stem: true` for Czech). Typesense is the bluntest
witness that ordering is contingent, not principled: one codebase folds-then-stems for Latin
scripts and stems-then-folds for Cyrillic, with a comment, because that is what each stemmer's
alphabet demands. The only shipped mechanism that spans both axes is **indexing more than one
lane per term** — Vespa's `stemming: multiple` (original + stems, queried as weighted
alternatives, with a code-comment intent to make it the Vespa 9 default); Lucene/Solr document
the same idiom (`KeywordRepeatFilter`) but never ship it on. The third genuine answer is
Meilisearch's: no stemmer at all — aggressive normalization plus prefix and typo tolerance —
which buys perfect accent recall and pays with erratic, length-threshold-governed inflection
matching. Written rationale is scarce everywhere; the best artifacts are Lucene's Scandinavian
folding javadoc (bare keyboards are real, folding fixes it, folding also breaks things), Vespa's
`TODO Vespa 9` on multi-lane, and Typesense's per-script ordering comment. For evitaDB the
survey's net: the fix that exists as a *pattern* but not as an *implementation* is a Czech
normalization step plus a stemmer ported to folded space (§11 M1); the fix that exists as
shipped code is the second lane per term (§11 M2) — and M2 is the one with a claim on the term
dictionary layout, so it must be decided before the layout freezes even if M1 is chosen.

#### What "co-design" means

Used throughout this report: the normalization step and the stemmer are **written for each
other** — as one unit agreeing on a single alphabet — instead of being two independent filters
someone later has to put in the right order.

The contrast against evitaDB's current Czech chain makes it concrete. `CzechStemFilter` and
`ASCIIFoldingFilter` were written independently, each assuming a different alphabet: the
stemmer's ending tables are spelled with accents and it demands accented input
(`cz/CzechStemmer.java:35`), while the folding filter is a generic Unicode utility that knows
nothing about Czech. Because each assumes a world the other destroys, *any* ordering of the two
breaks one side — the ordering dilemma only exists because the components disagree about what
alphabet they operate in.

In Lucene's German pair, both components agree the alphabet is folded:

- `GermanNormalizationFilter` is not generic folding — it implements "the heuristics of the
  German snowball algorithm" (`de/GermanNormalizationFilter.java:25-38`): ä→a, ö→o, ü→u, ß→ss,
  plus ae→a, oe→o, ue→u. It folds the specific variations German users actually type, the way
  the stemmer expects.
- `GermanLightStemmer`'s suffix rules (`-ern`, `-em`, `-er`, `-es`, `-e`, `-s`) are written
  entirely in folded characters — no umlaut appears in any rule — and it re-folds internally as
  its own first step (`de/GermanLightStemmer.java:63-90`), so it works whether or not the filter
  ran before it.

The result: "schöne", "schoene" and bare-typed "schone" all normalize to `schone`, and the
stemmer strips `-e` from all three identically. No point in the pipeline exists where the two
components disagree about what a character looks like, so there is no ordering question left to
solve. The same holds for the Spanish/French/Italian/Portuguese light stemmers (Shape B in §3 —
they fold accented vowels themselves before applying suffix rules) and for Arabic/Hindi/Sorani
(Shape A — normalization filter before a stemmer whose tables assume normalized text).

Applied to Czech, co-design would mean a matched pair: a Czech folding step plus a
`CzechStemmer` variant whose tables are rewritten in folded space (`-ých` → `-ych`, `-ám` →
`-am`, …), so the bare-typed query `mladych` hits the same ending rule the indexed `mladých`
did. That is mechanism M1 in §11: it exists as a *pattern* in six Lucene languages and as an
*implementation* for Czech nowhere.

---

### 2. Cross-engine matrix

Folds = folds diacritics by default. Pos = folding position vs stemmer. FoldStem = stemmer
expects folded input. Lanes = index lanes per term (default). Asym = separate query-side
analysis supported/shipped. CZ = ships a Czech config.

| engine | folds by default | position vs stemmer | stemmer expects folded | Czech config |
|---|---|---|---|---|
| Lucene | N (per-language) | before (de/es/…); after (sr); none (cz) | Y where co-designed; cz N | Y, no fold |
| Solr | N (`text_cz` no fold) | mirrors Lucene | mirrors Lucene | Y, no fold |
| Elasticsearch | N | mirrors Lucene | mirrors Lucene | Y, no fold |
| OpenSearch | N (=ES) | =ES | =ES | Y (=ES) |
| Vespa | **Y** (ACCENT default) | **before**, hard-coded | N (Snowball fed folded) | N: folded, never stemmed |
| Meilisearch | **Y** (charabia) | no stemmer exists | — | N |
| Typesense | **Y** (iconv TRANSLIT) | Latin **before**; Cyrillic **after** | N (folded Snowball) | N: unstemmable |
| EdeeCMS (§14) | **Y** (DiacriticFilter) | Lucene stack **after**; ES stack **before** | N (both) | Y, several |

| engine | lanes per term | asymmetric query analysis | escape hatch offered |
|---|---|---|---|
| Lucene | 1 (KeywordRepeat documented) | fuzzy/wildcard get norm-not-stem | KeywordRepeat idiom; ICU folding |
| Solr | 1 (keywordRepeat documented) | Y, first-class (used for synonyms) | fold+truncate recipe; collate (manual) |
| Elasticsearch | 1 (`preserve_original` opt) | Y (`search_analyzer`) | keyword normalizer; `icu_folding` exemption |
| OpenSearch | =ES | =ES | =ES minus `serbian` analyzer |
| Vespa | 1; `stemming: multiple` opt | symmetric + 0.7-weighted alternatives | multi-lane stems (intended default) |
| Meilisearch | 1 | symmetric | last-token prefix + typo ≤2, folded space |
| Typesense | 1 | symmetric | typo ≤2; opt-in `stem`/`stem_dictionary` |
| EdeeCMS (§14) | 1 + summon prefixes; all Hunspell lemmas | Y (summon index-only) | Hunspell option; ICU sort lane |

---

### 3. Q1 — the Lucene language survey

Survey of every language package in
`lucene/analysis/common/src/java/org/apache/lucene/analysis/*/` (Lucene `972293ce92e`). The
question: is normalize-before-stem the established pattern, with the stemmer written for
normalized input?

**The answer is yes — but the mechanism is richer than "a filter placed before the stemmer".**
Five distinct shapes exist, and most Latin-script languages with diacritics get the folding
*inside the stemmer itself*:

- **Shape A — dedicated normalization filter before the stemmer**: Arabic, Bengali, Sorani,
  German, Persian, Hindi, Nepali, Romanian, Tamil, Telugu. (Greek gets the same effect from
  `GreekLowerCaseFilter`, which "removes some Greek diacritics" —
  `el/GreekLowerCaseFilter.java:25-26` — before `GreekStemFilter`.)
- **Shape B — the stemmer folds internally as its first step** (Savoy "light stemmer" family):
  the stemmer's very first loop rewrites every accented vowel to its bare form, so the suffix
  tables are written in folded space and accented and bare-typed input produce the *same stem*.
  German (`de/GermanLightStemmer.java:63-90` — ä/à/á/â→a, ö/ò/ó/ô→o, …), Spanish
  (`es/SpanishLightStemmer.java:66-97`), Italian (`it/ItalianLightStemmer.java:66-97`),
  Portuguese (`pt/PortugueseLightStemmer.java:81-115`), French
  (`fr/FrenchLightStemmer.java:214-241`, the `norm()` step also folds ç→c).
- **Shape C — stem in native orthography, fold the output**: Galician stems with accented suffix
  tables, then runs an "RSLG accent removal" loop over the result
  (`gl/GalicianStemmer.java:69-88`). Index terms come out folded, but a bare-typed query still
  misses the accented suffix tables — the same asymmetry Czech has, only smaller because the
  output space is folded.
- **Shape D — fold after the stemmer**: Serbian. `SerbianAnalyzer.createComponents` runs
  `SnowballFilter(SerbianStemmer)` then `SerbianNormalizationFilter`
  (`sr/SerbianAnalyzer.java:124-125`), which maps Cyrillic and accented Latin to "bald" Latin
  (`sr/SerbianNormalizationFilter.java:24-31`). This is exactly evitaDB's current Czech shape,
  shipped by Lucene for one language.
- **Shape E — no folding anywhere; stemmer demands native diacritics**: **Czech**
  (`cz/CzechStemmer.java:35` — "NOTE: Input is expected to be in lowercase, but with diacritical
  marks"), Latvian (suffix tables and vowel counting use ā/ē/ī/ū natively,
  `lv/LatvianStemmer.java:166-176`), Lithuanian, and the Snowball-based chains for Catalan,
  Danish, Norwegian, Swedish, Finnish, Hungarian, Russian, Turkish, etc. A bare-typed query is
  simply not addressed.

Full table. Position cites the analyzer's `createComponents`; evidence points at the analyzer
unless a stemmer detail is claimed. N/A = no stemmer, or no diacritics question for that script.

| lang | norm filter | vs stemmer | stemmer's diacritics stance | evidence |
|---|---|---|---|---|
| ar | yes | before | expects normalized | `ar/ArabicAnalyzer.java:131-149` |
| bg | no | — | Cyrillic; N/A | `bg/BulgarianAnalyzer.java:116-125` |
| bn | Indic+Bengali | before | expects normalized | `bn/BengaliAnalyzer.java:119-131` |
| br | no | — | native orthography (not inspected further) | `br/BrazilianAnalyzer.java` |
| ca | no | — | native orthography (Snowball) | `ca/CatalanAnalyzer.java` |
| cjk | width only | N/A | no stemmer | `cjk/CJKAnalyzer.java` |
| **cz** | **no** | — | **demands diacritics** | `cz/CzechStemmer.java:35` |
| da | no (exists unwired) | — | native orthography (Snowball) | `da/DanishAnalyzer.java` |
| de | yes | before | folds internally too | `de/GermanAnalyzer.java:127-133` |
| el | via lowercase filter | before | expects accent-free Greek | `el/GreekLowerCaseFilter.java:25-26` |
| en | no | — | N/A | `en/EnglishAnalyzer.java` |
| es | no | — | folds internally first | `es/SpanishLightStemmer.java:66-97` |
| et | no | — | native orthography (Snowball) | `et/EstonianAnalyzer.java` |
| eu | no | — | native orthography (Snowball) | `eu/BasqueAnalyzer.java` |
| fa | Arabic+Persian | before | expects normalized | `fa/PersianAnalyzer.java:128-140` |
| fi | no | — | native orthography (Snowball) | `fi/FinnishAnalyzer.java` |
| fr | no | — | folds internally in `norm()` | `fr/FrenchLightStemmer.java:214-241` |
| ga | no | — | native orthography (Snowball) | `ga/IrishAnalyzer.java` |
| gl | no | — | stems native, folds output | `gl/GalicianStemmer.java:69-88` |
| hi | Indic+Hindi | before | expects normalized | `hi/HindiAnalyzer.java:121-129` |
| hu | no | — | native orthography (Snowball) | `hu/HungarianAnalyzer.java` |
| hy | no | — | own script; N/A | `hy/ArmenianAnalyzer.java` |
| id | no | — | N/A (no diacritics) | `id/IndonesianAnalyzer.java` |
| it | no | — | folds internally first | `it/ItalianLightStemmer.java:66-97` |
| lt | no | — | native orthography (Snowball) | `lt/LithuanianAnalyzer.java` |
| lv | no | — | native diacritics in tables | `lv/LatvianStemmer.java:166-176` |
| ne | Indic | before | expects normalized | `ne/NepaliAnalyzer.java:122-129` |
| nl | no | — | native orthography (Snowball) | `nl/DutchAnalyzer.java` |
| no | no (exists unwired) | — | native orthography (Snowball) | `no/NorwegianAnalyzer.java:113-118` |
| pt | no | — | folds internally first | `pt/PortugueseLightStemmer.java:81-115` |
| ro | cedilla→comma only | before | native orthography (Snowball) | `ro/RomanianAnalyzer.java:118-124` |
| ru | no | — | Cyrillic; N/A | `ru/RussianAnalyzer.java` |
| sr | yes | **after** | native in, bald-Latin out | `sr/SerbianAnalyzer.java:119-125` |
| sv | no (exists unwired) | — | native orthography (Snowball) | `sv/SwedishAnalyzer.java` |
| ta | Indic | before | expects normalized | `ta/TamilAnalyzer.java:121-128` |
| te | Indic+Telugu | before | expects normalized | `te/TeluguAnalyzer.java:114-123` |
| th | no | N/A | no stemmer | `th/ThaiAnalyzer.java` |
| tr | no | — | native orthography (Snowball) | `tr/TurkishAnalyzer.java` |

**Is Czech genuinely the exception?** Not uniquely — it sits in Shape E with Latvian, Lithuanian
and the Snowball Nordic languages. But it *is* the worst-positioned member of that group for
bare-typing, because (a) Czech users routinely type without diacritics (as Scandinavian users do —
which is exactly why Lucene wrote `ScandinavianFoldingFilter`, see §4 Q6), and (b) unlike Danish
or Swedish, Lucene ships no optional Czech folding filter at all. The critical negative finding:
**no `CzechNormalizationFilter` exists, and no stemmer for Czech written in folded space exists
anywhere in Lucene.** Every language that "solved" this either had its normalization filter and
stemmer co-designed (Shape A/B) or accepts one of the two brokennesses (Shapes C/D/E).

Two further notes on the normalization filters themselves:

- `GermanNormalizationFilter` is not generic folding — it implements "the heuristics of the
  German snowball algorithm", including ae→a, oe→o, ue→u (not after vowel/q) and ß→ss
  (`de/GermanNormalizationFilter.java:25-38`). Normalizer and stemmer are co-designed per
  language; Lucene never inserts `ASCIIFoldingFilter` before a stemmer in any shipped analyzer.
- The Scandinavian filters exist precisely for the bare-typing problem but are shipped **unwired**
  — an operator must add them, and they target cross-language keyboard variance (aa/ae/oe
  digraphs), with `ScandinavianFoldingFilter` as the "more destructive" variant that also matches
  fully bare typing (`miscellaneous/ScandinavianFoldingFilter.java:26-44`,
  `miscellaneous/ScandinavianNormalizationFilter.java:30-31`).

---

### 4. Lucene (`972293ce92e`, 2026-08-24)

#### Q1 — see §3.

#### Q2 — more than one lane per term

- `ASCIIFoldingFilter(TokenStream, boolean preserveOriginal)` emits the folded token and, when the
  flag is set, the original token at the same position (position increment 0)
  (`miscellaneous/ASCIIFoldingFilter.java:91-96,154`). **No shipped analyzer uses it** — it is
  category (b), configurable only.
- The canonical two-lane idiom is `KeywordRepeatFilter` + stemmer + `RemoveDuplicatesTokenFilter`.
  It is *recommended in javadoc* by the stem filters themselves — "For including the original
  term as well as the stemmed version, see KeywordRepeatFilterFactory"
  (`snowball/SnowballFilter.java:45-46`, `hunspell/HunspellStemFilter.java:36-37`, same note in
  `en/PorterStemFilter.java`, `en/KStemFilter.java`) — but **no shipped analyzer in
  `analysis/common` wires it in** (verified: the only non-test references to
  `KeywordRepeatFilter` are those javadocs and `module-info.java`). Category (c): documented
  recommendation, not a default. No statement of term-dictionary cost appears in the javadocs.

#### Q3 — asymmetric query-side analysis

Lucene has a built-in, deliberate asymmetry — but it runs in the *opposite direction* to the one
the brief hypothesizes. `Analyzer.normalize(String, String)` produces "the representation that it
would have in the index … without tokenizing or stemming, which are undesirable if the string to
analyze is a partial word (eg. in case of a wildcard or fuzzy query)"
(`core/.../analysis/Analyzer.java:202-213`). Query parsers call it for wildcard/fuzzy/range terms
(`queryparser/.../classic/QueryParserBase.java`, multiple call sites). Each language analyzer
overrides the protected `normalize(String, TokenStream)` hook with its normalization-but-not-stem
subset: German returns `LowerCaseFilter → GermanNormalizationFilter`
(`de/GermanAnalyzer.java:137-142`), Czech returns only `LowerCaseFilter`
(`cz/CzechAnalyzer.java:123-125`). So a German fuzzy query operates in folded space; a Czech
fuzzy query does not, because there is nothing to fold with. There is no shipped mechanism that
makes a *match* query emit both stem and folded surface form — that would be an operator-built
analyzer (category d).

#### Q4 — escape hatches

- **Fuzzy ceiling**: `LevenshteinAutomata.MAXIMUM_SUPPORTED_DISTANCE = 2`
  (`core/.../util/automaton/LevenshteinAutomata.java:37`). Confirmed; fuzzy cannot bridge 3+ edit
  gaps anywhere in the Lucene family.
- **ICU folding**: `ICUFoldingFilter` applies UTR#30 foldings including "Diacritic removal
  (including stroke, hook, descender)", normalizing to NFKC
  (`analysis/icu/.../ICUFoldingFilter.java:28-63`). It is a stronger `ASCIIFoldingFilter`, not an
  ordering solution — the same placement dilemma applies.
- **ICU collation keys**: `ICUCollationKeyAnalyzer` indexes the raw `CollationKey` bytes of the
  whole field value via `KeywordTokenizer`
  (`analysis/icu/.../ICUCollationKeyAnalyzer.java:25-75`). At primary strength this is
  accent-blind, but it is a *whole-value sort/range key* (keyword tokenizer,
  collator-version-locked index), not a per-token search lane — usable as an accent-blind
  exact-match lane only for untokenized fields.
- **Hunspell ICONV/OCONV**: parsed and applied (`hunspell/Dictionary.java:384`,
  `hunspell/ConvTable.java:31`). ICONV rewrites input before lookup — in principle a dictionary
  shipping `ICONV a á`–style lines could accept bare typing, but that is dictionary authorship,
  not engine behaviour, and the stock `cs_CZ` dictionary has no such lines (see the evitaDB test
  fixture measurements in the brief).
- **Hunspell suggester as query rewrite: refuted.** `hunspell/Suggester.java` (REP/MAP/TRY-driven,
  via `ModifyingSuggester`) is instantiated only by the standalone spell-checking facade
  `hunspell/Hunspell.java`. Nothing in Lucene's indexing or query path calls it. Category (d) at
  best.

#### Q5 — skip stemming entirely

Not Lucene's design for any shipped language analyzer; every European-language analyzer stems.
(The engines that answer Q5 are Meilisearch/Typesense — see their sections.)

#### Q6 — written rationale

- The single most direct statement is the Czech stemmer's input contract:
  *"NOTE: Input is expected to be in lowercase, but with diacritical marks"*
  (`cz/CzechStemmer.java:35`). It states the expectation but not the cost.
- The richest rationale is on `ScandinavianFoldingFilter`
  (`miscellaneous/ScandinavianFoldingFilter.java:26-44`): *"They are however folded differently
  when people type them on a keyboard lacking these characters. … In that situation almost all
  Swedish people use a, a, o instead of å, ä, ö. … This filter solves that mismatch problem, but
  might also cause new."* — an explicit acknowledgment that bare-typed queries are a real user
  behaviour, that folding is the remedy, and that the remedy has a false-merge cost. Its sibling
  javadoc calls plain normalization "a semantically less destructive solution"
  (`miscellaneous/ScandinavianNormalizationFilter.java:30`).
- `GermanNormalizationFilter`'s javadoc explains *why* it folds the way it does: "It allows for
  the fact that ä, ö and ü are sometimes written as ae, oe and ue"
  (`de/GermanNormalizationFilter.java:26-28`) — i.e. the filter exists to absorb typing variance,
  and its rules are the stemmer's own (Snowball German) so the two compose.
- Commit history is unhelpful: `LUCENE-2067: Add a stemmer for Czech` (2009-11-29, `2ef402eefa1`),
  `LUCENE-6053: add Serbian analyzer` (2014-11-11, `67c1aaa9f81`) and the commit that introduced
  `GermanNormalizationFilter` (`dac1b58277c`, 2012-02-08, "SOLR-3097, SOLR-3105: add fieldtypes
  for different languages to the example") carry no ordering rationale.

#### What it costs

Lucene's answer for well-served languages is co-design: the normalizer implements the stemmer's
conventions (German) or the stemmer folds itself (Spanish/French/Italian/Portuguese light
stemmers, Shape B), so there is no ordering question left. Languages without that co-design get
either nothing (Czech, Latvian) or an unwired optional filter (Scandinavian). The two-lane idiom
(`KeywordRepeatFilter`) is documented but costs a second term per token and is never a default.

---

### 5. Solr (`8479f0de485`, 2026-08-25)

#### What Solr ships for Czech

The `_default` configset's `text_cz` is exactly Lucene's `CzechAnalyzer` shape and **does not fold
at all**: `standard → lowercase → stop → czechStem`
(`solr/server/solr/configsets/_default/conf/managed-schema.xml:621-628`; the
`sample_techproducts_configs` copy at `managed-schema.xml:802` is identical). Solr's shipped
opinion for Czech is therefore: stem in native orthography and accept the bare-typed-query miss.
Meanwhile `text_de` ships `germanNormalization` before `germanLightStem`
(`_default/conf/managed-schema.xml:643-653`) — Solr simply mirrors whichever shape Lucene's
language package has. **No shipped field type in either configset uses `asciiFolding`,
`keywordRepeat` or `preserveOriginal`** (verified by grep over both `managed-schema.xml` files;
the only `removeDuplicates` uses are WordDelimiterGraph hygiene in the English splitting types,
`_default/conf/managed-schema.xml:425-453`).

#### Q2 — two lanes

Documented as a recommendation, never a default. The ref guide has a dedicated
`KeywordRepeatFilterFactory` section with the rationale spelled out: "If placed before a stemmer,
the result will be that you will get the unstemmed token preserved on the same position as the
stemmed one. Queries matching the original exact term will get a better score while still
maintaining the recall benefit of stemming. Another advantage … is that wildcard truncation will
work as expected", plus the advice to add `RemoveDuplicatesTokenFilterFactory`
(`solr/solr-ref-guide/modules/indexing-guide/pages/language-analysis.adoc:71-80`). The same
two-lane idea recurs for lemmatization: the OpenNLP lemmatizer example "preserv[es] the original
token and emit[s] the lemma as a synonym" (`language-analysis.adoc:1047-1056`). No term-dictionary
cost figure is stated anywhere.

#### Q3 — asymmetric analysis

First-class and shipped: a field type may declare separate `<analyzer type="index">` and
`<analyzer type="query">` blocks, and the guide explicitly endorses "slightly different analysis
steps during indexing than those used at query time"
(`solr/solr-ref-guide/modules/indexing-guide/pages/analyzers.adoc:100-127`). But every shipped use
of the asymmetry is for synonyms/stopwords (e.g. `text_general`, `text_en_splitting`), never for
reconciling folding with stemming. No warning against asymmetry is present — it is presented as
normal configuration.

#### Q4 — escape hatches

- **The Turkish "diacritics-insensitive search" recipe** — the most interesting artifact in Solr's
  docs (`language-analysis.adoc:3446-3458`). It handles fold-vs-stem by **dropping the stemmer**:
  `asciiFolding preserveOriginal=true → keywordRepeat → truncate prefixLength=5 →
  removeDuplicates`. Folding gives accent-blindness, 5-char prefix truncation substitutes for
  stemming, and the two repeat-filters keep the exact surface forms as extra lanes for precision.
  Cost: up to 4 terms per input token (original, folded, and truncated variants of both). This is
  a *documented recommendation* (category c), shipped in no configset.
- **ASCII folding framed as recall/precision trade**: "This can increase recall by causing more
  matches. On the other hand, it can reduce precision because language-specific character
  differences may be lost" (`language-analysis.adoc:614-620`).
- **Spellchecker as query rewrite: refuted as an automatic mechanism.** `spellcheck.collate`
  produces re-written whole queries and `spellcheck.collateMaxCollectDocs`/`maxCollationTries`
  even *verify collations return hits* against the index
  (`solr/solr-ref-guide/modules/query-guide/pages/spell-checking.adoc:403-441`), but the collation
  is returned to the client as a suggestion; Solr never executes it in place of the user's query.
  The application must re-issue it (category d for automatic behaviour).
- **ICU collation** is offered for accent-insensitive *sorting* ("`strength=primary` … accents are
  ignored but case is taken into account", `language-analysis.adoc:368,425`), not as a search
  lane.

#### Q6 — written rationale

The ref guide restates the Scandinavian analysis verbatim from Lucene's javadoc — bare keyboards,
who types what, and the two-filters-two-costs framing: normalization is "semantically less
destructive"; folding "can in addition help with matching raksmorgas as räksmörgås"
(`language-analysis.adoc:3076-3151`). The Danish and Norwegian sections cross-reference it ("Also
relevant are the Scandinavian normalization filters", `language-analysis.adoc:1586,3323`). The
Czech section carries no such pointer and no folding discussion at all
(`language-analysis.adoc:1537-1578`) — Czech bare-typing is simply not acknowledged as a problem
anywhere in Solr's documentation. Notably, both Scandinavian filter examples in the guide show the
filter *without any stemmer in the chain* (`language-analysis.adoc:3106-3170`); the guide never
demonstrates combining them with the Snowball stemmers its Danish/Norwegian/Swedish sections
recommend, so the ordering question is left unanswered even where the bare-typing problem is
acknowledged.

---

### 6. Elasticsearch (`4ee29c5118ca`, 2026-08-25)

#### What ES ships for Czech

The built-in `czech` analyzer instantiates Lucene's `CzechAnalyzer` unmodified
(`modules/analysis-common/src/main/java/org/elasticsearch/analysis/common/CzechAnalyzerProvider.java:24-29`)
— no folding, same acceptance of the bare-typed miss. The docs' own "reimplement as custom"
recipe for `czech` is `standard → lowercase → czech_stop → czech_keywords → czech_stemmer`, again
with no folding (`docs/reference/text-analysis/analysis-lang-analyzer.md:417-456`). German gets
`german_normalization` as a named filter (`GermanNormalizationFilterFactory.java`), mirroring
Lucene Shape A. ES adds no Czech-specific mechanism of its own.

#### Q2 — two lanes

- `asciifolding` supports `preserve_original` (default `false`):
  `ASCIIFoldingTokenFilterFactory.java:27-34`; docs at
  `docs/reference/text-analysis/analysis-asciifolding-tokenfilter.md:57-59`. Category (b). One
  giveaway detail: when the filter is used inside a *normalizer* (keyword-field normalization),
  ES force-disables the flag — "Normalization should only emit a single token"
  (`ASCIIFoldingTokenFilterFactory.java:44-63`).
- Keyword-field **normalizers** may include `asciifolding` and `german_normalization` etc.
  (`docs/reference/text-analysis/normalizers.md:9`) — the standard way to get an accent-blind
  *exact-match* lane is a keyword multi-field with such a normalizer. That is an operator-built
  pattern (category d in-repo; the how-to content lives in the separate `docs-content`
  repository, out of scope here — noted under Open questions).
- No built-in language analyzer emits more than one lane per term.

#### Q3 — asymmetric query analysis

First-class: `search_analyzer` on any text field. The docs frame symmetry as the norm and
asymmetry as legitimate for named cases: "Usually, the same analyzer should be applied at index
time and at search time … Sometimes, though, it can make sense to use a different analyzer at
search time, such as when using the `edge_ngram` tokenizer for autocomplete or when using
search-time synonyms"
(`docs/reference/elasticsearch/mapping-reference/search-analyzer.md:11-15`). No shipped language
config uses the asymmetry, and no shipped or documented config uses it to reconcile folding with
stemming.

#### Q4 — escape hatches

- **`fuzziness` + `unicode_aware`: refuted as an accent bridge.** `fuzziness` is capped at 2
  edits, with `AUTO:3,6` length-scaling
  (`docs/reference/elasticsearch/rest-apis/common-options.md:278-299`). `unicode_aware` exists
  *only* in the completion suggester's `FuzzyOptions`
  (`server/src/main/java/org/elasticsearch/search/suggest/completion/FuzzyOptions.java:35,70`)
  and means "measure edits in code points rather than UTF-8 bytes" — it does not fold anything
  and is not available on `match`/`fuzzy` queries.
- **`icu_folding` with `unicode_set_filter`** (analysis-icu, bundled plugin): UTR#30 folding with
  an exemption set — the docs' example is a Swedish folder configured as
  `"unicode_set_filter": "[^åäöÅÄÖ]"`, i.e. fold everything *except* the letters the language
  treats as distinct (`docs/reference/elasticsearch-plugins/analysis-icu-folding.md:8-61`). This
  is the "protect the stemmer's alphabet" move: fold foreign diacritics, keep native ones. It
  does not help the bare-typed-native-query case at all — a query typed without å still misses.
- **ICU collation at primary strength** ignores accent differences, offered as a *keyword field
  for sorting* (`docs/reference/elasticsearch-plugins/analysis-icu-collation-keyword-field.md:88`)
  — not a text-search lane.

#### Q5 / Q6

Every built-in European language analyzer stems (they are Lucene's). No written rationale about
folding-vs-stemming order exists in this repository beyond what Lucene's javadocs already carry;
the asciifolding docs describe behaviour without discussing placement relative to stemmers
(`docs/reference/text-analysis/analysis-asciifolding-tokenfilter.md`). Note the main narrative
documentation (how-tos, e.g. any accent-insensitive-search guidance) lives in the separate
`elastic/docs-content` repository, which is not among the surveyed checkouts.

---

### 7. OpenSearch (`849255b2bc2`, 2026-08-24) — delta from Elasticsearch only

There is essentially no delta on this subject. `CzechAnalyzerProvider` and
`ASCIIFoldingTokenFilterFactory` are byte-for-byte ES code modulo package renames (verified by
diff against the ES checkout;
`modules/analysis-common/src/main/java/org/opensearch/analysis/common/CzechAnalyzerProvider.java`,
`.../ASCIIFoldingTokenFilterFactory.java`). The only divergence found: OpenSearch registers the
`serbian_normalization` token filter
(`.../CommonAnalysisModulePlugin.java:331`) but ships no `serbian` prebuilt analyzer, whereas ES
has `SerbianAnalyzerProvider` — so the one shipped fold-after-stem language chain in the Lucene
family is absent from OpenSearch's built-ins, without any documented reason in this repository.
OpenSearch's prose documentation lives in the separate `opensearch-project/documentation-website`
repository, which is not among the surveyed checkouts.

---

### 8. Vespa (`c339a245780`, 2026-08-24)

Vespa is the most instructive engine here, because it makes three deliberate choices in code.

#### The order is fixed: fold BEFORE stem, and folding is ON by default

The linguistics API separates NFKC normalization (`Normalizer.normalize`,
`linguistics/src/main/java/com/yahoo/language/process/Normalizer.java:9-19`) from accent removal
(`Transformer.accentDrop`, `.../process/Transformer.java:10-22`). The default linguistics
implementation is OpenNLP (`container-disc/src/main/java/com/yahoo/language/provider/DefaultLinguisticsProvider.java`),
whose per-token pipeline is hard-coded:

```
normalize → lowercase → accentDrop → stem      (OpenNlpTokenizer.processToken)
```

(`opennlp-linguistics/src/main/java/com/yahoo/language/opennlp/OpenNlpTokenizer.java:127-139`;
`SimpleTokenizer` orders the same way, `linguistics/.../simple/SimpleTokenizer.java:119-126`).
Accent removal is generic NFD-strip-combining-marks, language-blind
(`linguistics/.../simple/SimpleTransformer.java:12-21`). The schema default for `normalizing` is
`Level.ACCENT` — remove accents unless the author opts out
(`config-model/src/main/java/com/yahoo/schema/document/NormalizeLevel.java:22-32`). The indexing
DSL exposes the knobs (`stemming:`, `normalizing:`) but not the *order* of accentDrop vs stem —
that is fixed in the tokenizer, contrary to the brief's expectation that the DSL makes it
author-controlled.

Consequence: Vespa's Snowball stemmers (`OpenNlpTokenizer.algorithmFor`,
`OpenNlpTokenizer.java:149-174`) receive **folded** input by default, even for languages whose
Snowball algorithms are written for native orthography (French, Hungarian, Romanian…). Vespa
ships the fold-before corner and accepts degraded stemming, prioritizing accent-typed recall.

#### Czech in Vespa: folded, never stemmed

`algorithmFor` has no Czech mapping (`OpenNlpTokenizer.java:149-174` — Czech falls to
`default -> null`, so no stemmer is created). Czech text is tokenized, NFKC-normalized,
lowercased and accent-dropped, and inflection is simply not handled. That is the fourth corner of
the measured table: 108/108 accent-typed recall, 0-ish inflection convergence (only identical
forms converge).

#### Q2 — multiple lanes per term: shipped and moving toward default

With `stemming: multiple` (schema) → `StemMode.ALL` (code), Vespa genuinely indexes several lanes
per token: the index-side annotator writes the processed term, the lowercased *original*, and
every stem as separate TERM annotations on the same span
(`indexinglanguage/src/main/java/com/yahoo/vespa/indexinglanguage/linguistics/LinguisticsAnnotator.java:249-262`).
The query side mirrors it: with StemMode.ALL (or literal-boost), a term becomes a
`WordAlternativesItem` holding the original surface at weight 1.0 and each stem at 0.7
(`container-search/src/main/java/com/yahoo/prelude/querytransform/StemmingSearcher.java:325-338`).
The default is still `best` (single stem), but the code carries an explicit intent marker:
"Default is BEST … TODO Vespa 9: Change default to multiple"
(`config-model/src/main/java/com/yahoo/schema/Schema.java:218-221`,
`Stemming.MULTIPLE → StemMode.ALL` at `config-model/.../document/Stemming.java:25,57`). I.e. the
engine that measured this trade-off is migrating its default *toward* multi-lane.

#### Q3 — asymmetric analysis

Symmetric by design — the same linguistics component processes documents and queries, keyed by
the same schema settings. But the query side adds one asymmetric nuance: in
`WordAlternativesItem` blocks, `NormalizingSearcher` *adds* the accent-dropped variant as an
extra alternative at 0.7 exactness instead of replacing the original
(`container-search/.../querytransform/NormalizingSearcher.java:138-140`; plain word items are
replaced outright, `:168-172`). So where alternatives exist, accented query input searches both
its original and folded forms with the original preferred.

#### Q4 / Q5

No fuzzy-in-folded-space lane, no collation lane, no spell-rewrite lane found in the surveyed
paths. For languages without a Snowball algorithm (Czech included) Vespa *is* a Q5 engine:
folding without stemming. Note Vespa's prose documentation lives in the separate
`vespa-engine/documentation` repository and is out of scope here.

#### Q6 — written rationale

No long-form rationale in code. The two artifacts are the `TODO Vespa 9: Change default to
multiple` on the stemming default (`config-model/.../Schema.java:221`) and the 0.7 exactness
discount for folded/stemmed alternatives in the query transformers (`NormalizingSearcher.java:140`,
`StemmingSearcher.java:332`) — an implicit statement that surface matches outrank derived ones.

---

### 9. Meilisearch (`577f7af28`, 2026-08-13)

#### The design: normalize aggressively, never stem

The tokenizer/normalizer pipeline is the external `charabia` crate, `version = "0.9.9"`,
**not vendored in-tree** (`crates/milli/Cargo.toml:21`) — its internals are therefore out of
scope here; only Meilisearch's use of it is reported. What milli stores per token is
`token.lemma()` — charabia's name for the *normalized token text*, not a linguistic lemma —
both in the legacy extractor
(`crates/milli/src/update/index_documents/extract/extract_docid_word_positions.rs:241,304`) and
the new one (`crates/milli/src/update/new/extract/searchable/tokenize_document.rs:112,136`).

**There is no stemming setting and no stemmer anywhere in the codebase** — `rg -i stem` over
`crates/milli/src/update/settings.rs` and `crates/meilisearch-types/src/settings.rs` returns
nothing. The linguistic settings surface is: `stopWords`, `synonyms`, `dictionary`,
`separatorTokens`/`nonSeparatorTokens`, and `localizedAttributes`
(`crates/meilisearch-types/src/settings.rs:396-402` for the latter). Inflection is carried
entirely by:

- **Prefix search on the last query token** ("if the word is the last token of the query we push
  it as a prefix word", `crates/milli/src/search/new/query_term/parse_query.rs:70-73,258-273`);
- **Typo tolerance**: Levenshtein DFAs at distance 1/2 intersected with the indexed-word FST
  (`crates/milli/src/search/new/query_term/compute_derivations.rs:85-122`), with defaults of one
  typo from term length 5 and two typos from length 9
  (`crates/milli/src/index.rs:46-47`) — and 2 is the hard ceiling, same as Lucene;
- **Synonyms/dictionary** as operator-supplied vocabulary.

#### Q3 — symmetric

The query is parsed with the same charabia tokenizer that indexed the documents
(`crates/milli/src/search/new/query_term/parse_query.rs:30`), so normalization is symmetric by
construction, and typo distances are measured *in normalized space* — which is what makes typo
tolerance an effective accent bridge here: if charabia folds diacritics for the query exactly as
it did for documents, the accent gap is zero before Levenshtein even applies.

#### What it gives up (Q5 answered)

For Czech specifically: `mladý/mladá/mladí/mladých…` share only the prefix `mlad`, so bare-typed
and accented forms converge — but *only when the query term is a prefix of the indexed form and
prefix search applies (last token)*, or when the difference stays within 1–2 edits after
normalization. Suffix-heavy inflection where the query is *longer* than or divergent from the
indexed form (`kabátem` vs `kabát` = +2 edits — within tolerance only for terms ≥ 9 chars) is
covered erratically: length thresholds, not grammar, decide. Nothing in-repo states a recall
claim for inflected languages; that would live in Meilisearch's external documentation
(out of scope).

---

### 10. Typesense (`a7c94ee9`, v31, 2026-08-18)

#### Folding is the default; stemming is opt-in and runs on folded text

For the default (no-locale) path, `normalize=true` lowercases ASCII and pushes every non-ASCII
character through `iconv` configured as `ASCII//TRANSLIT`
(`src/tokenizer.cpp:32` — `cd = iconv_open("ASCII//TRANSLIT", "UTF-8")`; applied at
`src/tokenizer.cpp:303-345`): diacritics are transliterated away **by default**. Locale-specific
branches exist for CJK/Thai/Greek/Cyrillic (NFKD or transliteration,
`src/tokenizer.cpp:122-172`).

Stemming exists on this branch and is a per-field **opt-in**: `stem` and `stem_dictionary` field
properties (`include/field.h:72-73,152-154`, default `stem = false` at `include/field.h:152`).
`stem: true` selects a **Snowball** stemmer keyed by the field's locale
(`src/stemmer_manager.cpp:4-8` — `sb_stemmer_new(language, …)`); `stem_dictionary` is an exact
word→root lookup table supplied by the operator (`src/stemmer_manager.cpp:27-31`). Locale
validation is literally "does Snowball have it": `validate_language` calls `sb_stemmer_new` and
fails on null (`src/stemmer_manager.cpp:85-97`). **Snowball has no Czech algorithm, so Czech
cannot be stemmed in Typesense at all** — only the `stem_dictionary` route remains.

#### The ordering — split by script, decided in code

- **Latin locales: fold, then stem.** The emitted token is the case-folded/transliterated `out`,
  and `token = stemmer->stem(out)` runs on it (`src/tokenizer.cpp:206-209,245-249`). Snowball
  gets folded input — same corner as Vespa's default.
- **Cyrillic locales: stem, then transliterate.** The branch stems the raw Cyrillic word first
  and only then applies ICU `Any-Latin;Latin-ASCII`, with the comment "cyrillic is already
  stemmed prior to transliteration" (`src/tokenizer.cpp:131-143,206-208`). That is evitaDB's
  current Czech shape (stem in native orthography → fold), chosen per-script because Snowball
  Russian demands Cyrillic input.

One codebase therefore ships **both orders simultaneously**, picking per script whichever keeps
the stemmer working — the clearest concrete acknowledgment in the whole survey that the ordering
is a consequence of what alphabet the stemmer's tables are written in, not a principle.

#### Q4/Q5 — typo tolerance in folded space

Default `num_typos = {2}` (`src/collection.cpp:9531`); typo matching operates over the indexed
(normalized/folded, possibly stemmed) terms, so like Meilisearch the accent gap is closed by
folding *before* Levenshtein applies, and 2 edits is the ceiling. Historically (pre-`stem`
option) Typesense was a pure Q5 engine — normalization + typo + prefix, no stemming; on v31 the
`stem`/`stem_dictionary` options exist but default off, so out of the box it still is one.

#### Q3 / Q6

Analysis is symmetric (the same `Tokenizer` processes queries and documents). No written
rationale beyond the Cyrillic ordering comment quoted above.

---

### 11. Mechanisms found, ranked for evitaDB

Ranked by how many of the 25 measured misses (fold-after chain, `CzechAccentTypingTest`) each
would close, against what it costs. The failure classes from the brief: (i) accented endings the
bare query never triggers (`-ých`, `-ým`, `-ám`, `-ách`, `-ům`…), (ii) intra-stem vowel shifts
(`stůl→stol`, `dřevěný→dreven`), (iii) the over-stem asymmetry (`kabát→kabat` vs `kabat→kab`).

#### M1 — co-designed folding + stemmer written in folded space (the Lucene Shape A/B pattern)

*Who does it:* Lucene for German (filter + internally-folding stemmer), Spanish, French, Italian,
Portuguese, Greek; every engine that wraps those analyzers inherits it. Nobody has built it for
Czech — that is the gap, not a refutation of the pattern.

*What it would mean here:* fold **first** (both sides), and port `CzechStemmer`'s tables into
folded space, the way `GermanLightStemmer` and `SpanishLightStemmer` are written. Analysis of the
cited tables (`cz/CzechStemmer.java:46-113`) says the port is mostly mechanical: within each
removal-length group the folded endings merely collide onto themselves (`ích`/`ich` → `ich`,
`ěmi`/`emi` → `emi`, `ých` → `ych`, `ám` → `am` — all same-length, so collisions are harmless),
and the final-vowel switch folds cleanly. Two rules need real language judgment rather than
mechanical folding: `št→sk` (`CzechStemmer.java:129-133` — folded `št` is indistinguishable from
genuine `st`, and a blanket `st→sk` would corrupt every word ending in `-st`) and the `ů→o` shift
(`CzechStemmer.java:151-153` — folded `ů` is indistinguishable from `u`).

*What it fixes:* class (i) entirely — the bare-typed `mladych` hits the folded `ych` rule; class
(iii) — both sides take the same path, so the merge is consistent (if aggressive); class (ii)
partially — `dřevěný`/`dreveny` converge because the `ě` sits inside the stem and both sides fold
identically, but `stůl`/`stolu` still split unless the `ů→o` rule is generalized or dropped.
This matches the measured shape: fold-before already scores 108/108 on accent-typed recall; the
folded-tables stemmer exists to recover the 30/30 convergence that naive fold-before destroys
(12/30).

*Cost:* a Czech stemmer variant to write, test and own (no upstream exists to track); zero extra
index lanes; the `DiacriticsFoldingAnalyzerWrapper` ordering problem disappears rather than being
compensated. The evitaDB fixture (30 lemmas / 108 forms) is exactly the harness to validate the
port.

#### M2 — a second lane per term: folded surface alongside the stem

*Who does it:* Vespa, genuinely shipped — `stemming: multiple` indexes original + every stem as
TERM annotations (`LinguisticsAnnotator.java:249-262`) and queries as `WordAlternativesItem` with
the original at 1.0 / stems at 0.7 (`StemmingSearcher.java:325-338`), with a code-comment intent
to make it the default in Vespa 9 (`Schema.java:221`). Lucene/Solr document the same idea as the
`KeywordRepeatFilter` idiom (`SnowballFilter.java:45-46`,
solr `language-analysis.adoc:71-80`); ES offers `preserve_original` (default off).

*What it fixes:* every accent-typed **exact-form** miss — bare `mladych` matches the folded
surface lane of the document's `mladých` — i.e. all 25 misses *when the document contains the
typed inflection*. It does **not** connect the bare query to *other* inflections of the lemma
(`mladych` still misses a document that only says `mladý`): the stem lane stays accent-broken.
So it caps the damage rather than closing the gap; combined with M1 it is belt-and-braces, alone
it is a partial fix.

*Cost:* roughly one extra term per token whose folded surface differs from its stem (the exact
growth is corpus-dependent; no surveyed engine publishes a figure). **This is the mechanism with
a direct claim on the term-dictionary layout — if a surface lane may ever be wanted, the layout
must not assume one term per analyzed token.** Vespa's 0.7/1.0 weighting also implies the layout
must let the two lanes be distinguished at scoring time.

#### M3 — fold-before-stem and accept degraded stemming (the Vespa/Typesense default)

*Who does it:* Vespa by default (`Level.ACCENT` + hard-coded `accentDrop → stem`,
`NormalizeLevel.java:27`, `OpenNlpTokenizer.java:127-139`), Typesense for Latin scripts
(`tokenizer.cpp:206-209`). Both feed Snowball folded input that its tables were not written for.

*What it fixes / costs:* the measured fold-before row: 108/108 accent recall, 12/30 convergence.
Two production engines consider that trade acceptable as a *default*. It is the cheapest option
(reorder two filters) and the worst on inflection.

#### M4 — no stemmer: folding + prefix + typo tolerance (Meilisearch; Typesense out of the box;
Solr's Turkish recipe)

*Who does it:* Meilisearch (no stemming setting exists; last-token prefix search +
distance-1/2 DFAs in normalized space, `parse_query.rs:70-73`,
`compute_derivations.rs:85-122`, thresholds 5/9 at `index.rs:46-47`); Typesense with `stem`
off (default); Solr documents the same shape explicitly as the Turkish diacritics-insensitive
recipe — `asciifolding preserveOriginal + keywordRepeat + truncate(5) + removeDuplicates`
(`language-analysis.adoc:3446-3458`).

*What it fixes:* accent-typing completely (everything is folded); inflection only where the
query is a prefix of the indexed form or within 2 edits after folding — `kabátem` vs `kabát`
works (+2 edits, but only for terms long enough to earn 2 typos), `stůl` vs `stolu` is 2 edits
from a 4-letter word (below every threshold), and non-final query tokens get no prefix expansion
in Meilisearch at all. Length thresholds, not grammar, decide — erratic on exactly our failure
classes.

*Cost:* for evitaDB this is a different retrieval model (per-keystroke prefix posting lists or
query-time DFA intersection), not an analyzer tweak.

#### M5 — query-side expansion only (asymmetric analysis)

*Who does it:* nobody as a shipped language config. The primitives are first-class everywhere —
ES `search_analyzer` (`search-analyzer.md:11-15`), Solr `<analyzer type="query">`
(`analyzers.adoc:100-127`), Lucene's `Analyzer.normalize()` protocol (which already gives fuzzy
and wildcard terms the normalization-but-not-stem treatment, `Analyzer.java:202-213`) — but every
shipped asymmetric use is synonyms/autocomplete. Vespa's `NormalizingSearcher` is the closest
real instance: it *adds* the accent-dropped variant at 0.7 exactness where alternatives exist
(`NormalizingSearcher.java:138-140`).

*What it fixes:* emitting both the stem and the folded surface form at query time makes the bare
query reach the folded surface lane — but only if that lane exists in the index, so this is M2's
query half, not an independent mechanism. Standalone (expanding the query against a single-lane
index) it buys nothing our fuzzy lane doesn't already attempt.

#### M6 — selective folding with an exemption alphabet

*Who does it:* ES `icu_folding` + `unicode_set_filter`, docs example `[^åäöÅÄÖ]`
(`analysis-icu-folding.md:8-61`). Protects the stemmer by *not* folding the letters its tables
need. Useless for our problem: the bare-typed query needs precisely those letters folded. Listed
for completeness because it is the only shipped "reconciliation" of folding with a
native-orthography stemmer — and it reconciles by surrendering the bare-typing case.

---

### 12. Dead ends

- **Hunspell suggester as a query-rewrite step — refuted everywhere.** In Lucene the
  `Suggester`/`ModifyingSuggester`/`GeneratingSuggester` machinery is reachable only through the
  standalone spell-check facade `Hunspell.java`; nothing in any indexing or query path calls it.
  Solr's spellcheck component will even verify that a collation would return hits
  (`spell-checking.adoc:414`), but returns it as a suggestion for the *client* to re-issue. No
  engine rewrites the live query through suggestion machinery.
- **Hunspell ICONV as an accent-acceptor** — the parsing exists (`Dictionary.java:384`), no
  shipped dictionary uses it that way, and it would not fix the false-lemma class (`bily→bít`,
  `košili→kosit`): those are correct dictionary hits on the wrong word, which more input mapping
  makes worse, not better.
- **Hunspell-as-lemmatizer generally**: no surveyed engine ships Hunspell in a default analysis
  chain for any language, and none combines it with folding in a way that would have avoided the
  measured 25 % — the `HunspellStemFilter` javadoc itself points users at `KeywordRepeatFilter`
  (`HunspellStemFilter.java:36-37`), i.e. even its authors expect dictionary misses to pass
  through unstemmed and recommend keeping the surface lane.
- **ES `fuzziness` + `unicode_aware` as fuzzy-in-folded-space** — `unicode_aware` exists only on
  the completion suggester (`FuzzyOptions.java:35`) and means code-point-counted edits, not
  folding. The fuzzy ceiling is 2 edits in every surveyed engine (Lucene
  `LevenshteinAutomata.java:37`, Meilisearch `compute_derivations.rs:122`, Typesense
  `collection.cpp:9531`); a 3+-edit accent gap is unreachable by fuzzy everywhere, exactly as in
  evitaDB.
- **ICU collation keys as an accent-blind search lane** — `ICUCollationKeyAnalyzer` and ES/Solr
  collation fields are whole-value keyword lanes, collator-version-locked, for sort/range
  (`ICUCollationKeyAnalyzer.java:25-75`); no engine tokenizes text into per-term collation keys.
- **Waiting for upstream** — no `CzechNormalizationFilter`, no folded-space Czech stemmer, and no
  Czech Snowball algorithm exists in any surveyed repo; Typesense literally cannot enable `stem`
  for Czech (`stemmer_manager.cpp:85-97`). Nothing to adopt or track.

---

### 13. Open questions

- **Narrative documentation lives outside every surveyed server repo**: Elastic's how-to content
  (`elastic/docs-content`), OpenSearch's `documentation-website`, Vespa's
  `vespa-engine/documentation`, Meilisearch's docs site. Any official "accent-insensitive search"
  recipes there (the multi-field guidance Q2 asks about) were not surveyed. The in-repo evidence
  (ES normalizer page, Solr Turkish recipe) is the strongest signal available here.
- **charabia's Latin normalizer internals** (what exactly Meilisearch folds for Czech text) —
  external crate, version 0.9.9, deliberately not inspected per the brief. If M4-style behaviour
  ever matters to evitaDB, charabia's `LatinNormalizer` is where to look.
- **Whether a folded-space port of `CzechStemmer` is linguistically safe** — the two hazardous
  rules identified in M1 (`št→sk`, `ů→o`) and the collision classes need validating against the
  108-form fixture (and ideally a larger corpus). That answer lives in evitaDB's test suite, not
  in these repos.
- **Snowball upstream rationale** — the German algorithm's own ae/oe/ue handling (which
  `GermanNormalizationFilter` mirrors) is documented on snowballstem.org, not in these repos; if
  a written argument for co-designing normalizer and stemmer is wanted, that is where it would
  be.

**To settle before the term-dictionary layout freezes:** whether a term can ever carry more than
one lane (M2 — folded surface next to the stem, and Vespa-style down-weighting of the derived
lane implies the lanes must be distinguishable at scoring time), and whether the analyzer will
fold before stemming (M1/M3 — which determines if index terms are accent-free bytes, affecting
any layout assumption about term byte ranges or collation). M1 itself (a folded-space stemmer)
does not change the layout — one term per token either way — but M2 does, and choosing M1 now
without foreclosing M2 later costs a layout decision today.

---

### 14. Addendum — EdeeCMS in-house analyzers (`/Users/lho/www/p_prj/edee/edeecms`)

Surveyed on request as an eighth data point: FG Forrest's own CMS carries two independent Czech
fulltext stacks, and — without any recorded intent — they sit on **opposite corners of the same
trade-off**. Paths below are relative to the edeecms repository.

#### The Lucene stack (`prj_fulltext`): stem → fold, same shape as evitaDB

Analyzer registry: `IndexFactory.analyzerConstructorsMap`
(`prj_fulltext/lib_fulltext/src/main/java/com/fg/fulltext/core/index/IndexFactory.java:62-73`);
the plain name `czech` maps to `EdeeCzechSummonAnalyzer` (`IndexFactory.java:67`), documented as
"Default Czech (recommended for most cases)"
(`prj_fulltext/lib_fulltext/src/main/resources/META-INF/lib_fulltext/docs/claude/configuration.md:106-121`).

- **`EdeeCzechAnalyzer`** — `LowerCase → Stop → [RawTerms] → [KeywordMarker] → [SynonymGraph] →
  CzechStemFilter → DiacriticFilter`
  (`prj_fulltext/.../org/apache/lucene/analysis/cz/EdeeCzechAnalyzer.java:103-117`). Fold-after,
  with Lucene's accent-demanding `CzechStemFilter` — exactly evitaDB's current chain, including
  the same 77 % accent-typed blind spot.
- **`CzechHunspellAnalyzer`** — `LowerCase → Stop → [RawTerms] → [Synonyms] →
  HunspellStemFilter(dict, dedup=true) → Stop → DiacriticFilter`
  (`prj_fulltext/.../cz/CzechHunspellAnalyzer.java:151-168`), over the same LibreOffice `cs_CZ`
  dictionary evitaDB's test fixture uses (`CzechHunspellAnalyzer.java:32,46-50`). Fold-after
  again — the corner the evitaDB measurements put at 27/108 (25 %) accent-typed recall. The
  3-arg `HunspellStemFilter` constructor means `longestOnly=false`: every candidate lemma is
  emitted at the same position, a small multi-lane effect for morphologically ambiguous words.
  Two in-code rationale comments worth keeping: lowercase-before-Hunspell trades sentence-start
  handling against proper names (`CzechHunspellAnalyzer.java:153-154`), and stopwords are
  re-filtered *after* stemming "because the default stopwords list does not contain all forms"
  (`CzechHunspellAnalyzer.java:165-166`).
- **`FgCzechAnalyzer`** — `LocalizedLowerCase → Stop → [RawTerms] → [Synonyms] →
  DiacriticFilter` (`prj_fulltext/.../cz/FgCzechAnalyzer.java:183-194`): **no stemmer at all**,
  fold only — the Meilisearch corner (perfect accent recall, zero inflection convergence),
  shipped here since the 2003–2005 era. Despite the configuration doc labeling it "Basic Czech
  stemming" (`configuration.md:113-114`), the chain contains no stem filter — the doc is wrong.
- `DiacriticFilter` is a hand-written folding table (ß/æ/œ double-char aware) that **skips
  keyword-marked tokens** (`prj_fulltext/.../analysis/DiacriticFilter.java` — the
  `keywordAttr.isKeyword()` guard), so `RawTermsFilter`-protected and `KeywordMarker`-excluded
  terms keep their accents.

#### Two mechanisms the engine survey found only as recommendations are shipped here

- **Index-only prefix lanes** (`SummonFilter`): every prefix of each token down to a configured
  minimum is emitted at position increment 0
  (`prj_fulltext/.../analysis/SummonFilter.java:18-27`). Crucially it is wired
  **asymmetrically**: `IndexFactory.getAnalyzer(name, isForIndexing, …)` enables the filter only
  when building the *index-side* analyzer (`IndexFactory.java:132,139,173-176`), so documents
  are prefix-expanded and queries are not. That is Solr's Turkish `truncate` recipe upgraded to
  true edge-prefix lanes, plus ES-style `search_analyzer` asymmetry — both in production, at the
  cost every prefix lane pays in postings volume.
- **Protected surface lane** (`RawTermsFilter`): marker-wrapped content becomes keyword-flagged
  tokens that no later filter (stemmer or folding) may touch
  (`prj_fulltext/.../analysis/RawTermsFilter.java`) — a manual, per-term version of the
  keyword-marker idiom.

#### The Elasticsearch stack (`lib_elasticsearch`): fold → stem, the other broken corner

`customCzechAnalyzer` is `standard → czechStopWords → asciifolding → lowercase → czechStemmer`
(`lib_elasticsearch/src/test/resources/META-INF/lib_elasticsearch_test/mapping/index_settings.json:10-14`,
identical in `lib_elasticsearch/src/main/resources/META-INF/doc/index-creation.md:9-13` and the
retired `retired/prj_bundle_storage/.../mapping/index_settings.json:10-14`). This feeds ES's
`czech` stemmer — Lucene's `CzechStemmer`, which demands diacritics — **already-folded input**:
the fold-before corner the evitaDB measurements put at 12/30 inflection convergence. Sorting has
its own accent-blind lane: `customCzechSortAnalyzer` = `keyword` tokenizer + `cs_icu_collation`
(`index-creation.md:14-18`).

#### What this adds to the survey

Both stacks fold (unlike Lucene/Solr/ES upstream Czech, which don't fold at all), so EdeeCMS
always pays one of the two brokennesses — and it pays a *different one per stack*: the Lucene
stack keeps stemming and loses bare-typed queries; the ES stack keeps bare-typed queries and
loses stemming. Nothing in either stack reconciles the two (no folded-space stemmer, no
two-lane surface+stem index), which independently confirms the survey's verdict — and the
`isForIndexing` summon asymmetry is the one genuinely production-tested mechanism here that the
seven engines only document.
