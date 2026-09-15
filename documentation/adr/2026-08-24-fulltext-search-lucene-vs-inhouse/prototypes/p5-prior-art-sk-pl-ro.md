# P5 prior art — Slovak, Polish and Romanian: engine support and the path to parity with Czech

> **Status: research record, a companion to
> [`p5-prior-art-accent-vs-stemming.md`](p5-prior-art-accent-vs-stemming.md) (the seven-engine Czech
> survey) and [`p5-approach-measurements-accent-vs-stemming.md`](p5-approach-measurements-accent-vs-stemming.md)
> (the Czech measurements that produced the M7 verdict).** This document answers the same two
> questions those documents answered for Czech, for **Slovak (`sk`), Polish (`pl`) and Romanian
> (`ro`)**: what do the surveyed engines actually ship for each language, and — where Lucene core has
> no first-class support — what would it take to reach the quality bar the Czech M7 work set.
>
> **Sections 1–8 are a code survey; §9 is the measurement that followed it (2026-09-04).** The survey's
> "expected to transfer" claims inherited *Czech* measurements by analogy; §9 re-measured them per
> language over per-language fixtures, and several transferred claims **did not survive contact** —
> each such case is flagged where the survey states it and resolved in §9. The Czech record's own
> history (its §10 — six runs, five corrections, all fixture artifacts) predicted this. All survey
> citations are `path:line` against the same repository checkouts the Czech survey used, re-verified
> on 2026-09-01:
>
> | repo | HEAD | date | branch |
> |---|---|---|---|
> | lucene | `972293ce92e` | 2026-08-24 | main |
> | solr | `8479f0de485` | 2026-08-25 | main |
> | elasticsearch | `4ee29c5118ca` | 2026-08-25 | main |
> | OpenSearch | `849255b2bc2` | 2026-08-24 | main |
> | vespa | `c339a245780` | 2026-08-24 | master |
> | meilisearch | `577f7af28` | 2026-08-13 | main |
> | typesense | `a7c94ee9` | 2026-08-18 | v31 |
> | edeecms (addendum) | `d358020a4c7` | 2026-08-18 | dev |
>
> One source outside the local checkouts was consulted: the upstream
> [snowballstem/snowball](https://github.com/snowballstem/snowball) repository via the GitHub API, at
> commit `2c40f9d` (2026-04-22, the commit Typesense pins) — to establish which languages official
> Snowball covers and when Polish entered it.

---

## 1. Verdict

**The three languages land on three different rungs of the same ladder, and Czech's M7 playbook
transfers to all of them — with a different amount of missing groundwork on each rung.**

- **Romanian is the best-served: it is the only one of the three with a first-class analyzer in
  Lucene core** (`RomanianAnalyzer` — Snowball stemmer plus a normalization filter). But the
  normalization filter solves a *different* problem than the one that broke Czech: it unifies the
  legacy cedilla spellings (`ş`/`ţ`) with the correct comma-below ones (`ș`/`ț`) — an encoding-era
  artifact — and does nothing for bare typing. The Snowball tables are written in native orthography
  (`ă`, `ț` — 78 diacritic-bearing lines), so a bare-typed Romanian query has exactly the Czech
  disease: the endings it needs are spelled with diacritics it didn't type. **evitaDB currently maps
  `ro` to nothing at all** — it falls to the `generic` analyzer, no stemming, no folding — so the
  first step is free: wire `RomanianAnalyzer` plus the folding wrapper and Romanian is where Czech
  production was before M7. The M7 upgrade is then a folded-space port of a rule-based stemmer, same
  as Czech.
- **Polish has no core analyzer but three module-level options, and one of them is brand new and
  changes the calculus:** Stempel (statistical, what evitaDB ships today), Morfologik (dictionary
  lemmatizer), and — since 2025-10 upstream, vendored into Lucene 2025-12 — an **official Snowball
  Polish stemmer**. None of the three folds, and **evitaDB's `pl` chain today has no folding wrapper
  either**, which makes Polish currently *worse off than Czech ever was*: a bare-typed `zolty` finds
  nothing, not even the same form. The M7 route exists for Polish but **only over the Snowball
  stemmer**: M7's correctness invariant (the query-side hypothesis set must cover the folded image of
  the index-side stemmer's output) is enumerable for a rule-based stemmer and not constructible over
  Stempel's statistical trie or Morfologik's dictionary. Choosing M7 for Polish therefore implies
  switching the index side from Stempel to Snowball — a reindex and a relevance change that needs its
  own measurement.
- **Slovak has nothing, anywhere.** No Lucene package, no Snowball algorithm (upstream has neither
  Slovak nor Czech as of 2026-04), no Solr/ES/OpenSearch config (the string "slovak" does not appear
  in Solr's ref guide or ES's text-analysis docs at all), no Vespa stemmer mapping, and Typesense
  refuses `stem: true` for it. evitaDB's tokenize-plus-fold chain for `sk` is already as good as any
  surveyed engine offers — which is the finding: **the only path to Slovak inflection is an in-house
  stemmer**, and the Czech work already built its template (the ~230-line table-walk architecture of
  `CzechStemmer`/`FoldedCzechStemmer`), its deployment shape (M7 — the accented stemmer serves the
  index, the folded hypothesis port serves queries, so every table correction is a query-time change
  needing no reindex, which matters most for a from-scratch stemmer that *will* need corrections),
  and its validation instrument (the five-metric fixture methodology).

The engines that fold by default confirm the Czech survey's verdict transfers unchanged: Vespa feeds
its Romanian Snowball folded input the tables were not written for and never stems Polish or Slovak;
Typesense (whose pinned Snowball *does* have Polish and Romanian) folds Latin scripts before
stemming, so its opt-in stemmers for both languages receive input their diacritic tables cannot
match. Nobody reconciles the two sides for any of these three languages; the reconciliation evitaDB
measured into existence for Czech (M7) would be first-of-kind for all three.

---

## 2. Where evitaDB stands today

From `evita_engine/.../index/fulltext/analysis/BuiltInAnalyzers.java` (branch `258-fulltext-support-p5`):

| lang | chain | folding | stemming | gap |
|---|---|---|---|---|
| `cs` | `CzechAnalyzer` + folding wrapper (`BuiltInAnalyzers.java:112`) | after stem | accented `CzechStemFilter` | the measured A0 baseline; M7 is the planned fix |
| `pl` | bare `PolishAnalyzer` (Stempel) (`BuiltInAnalyzers.java:115`) | **none** | Stempel, native orthography | bare-typed query matches **nothing** — no fold lane at all |
| `sk` | `TokenizingAnalyzer` + folding wrapper (`BuiltInAnalyzers.java:116`) | yes | **none** | no inflection convergence (the Czech A8 corner: 16/348 crossform under exact matching) |
| `ro` | **unmapped** — falls to `generic` (`BuiltInAnalyzers.java:124-130,147-162`) | **none** | **none** | neither folding nor stemming, despite Lucene core shipping both |

Two of the four rows are immediately improvable with zero new analysis code (§7, step 0).

> **Update 2026-09-04:** both step-0 wirings shipped after being measured (§9.6): `ro` now maps to
> `RomanianAnalyzer` + folding wrapper (measured as row R0), and `pl` gained the folding wrapper around
> Stempel (measured as row P0 — with a caveat the survey could not predict, §9.3). The `pl` and `ro`
> rows above describe the state *before* that change and are kept as the baseline the measurements are
> read against.

---

## 3. Cross-engine matrix for the three languages

"fold" = does the shipped/default chain fold diacritics; "stem" = is a stemmer shipped for the
language and what kind; "order" = folding position relative to the stemmer where both exist.

| engine | Slovak | Polish | Romanian |
|---|---|---|---|
| Lucene core | nothing | nothing in `analysis/common`; stempel module: Stempel + **Snowball (new)**; morfologik module: dictionary — none fold | `RomanianAnalyzer`: Snowball, native orthography; normalization = cedilla→comma only; no fold |
| Solr | nothing (0 hits in ref guide) | no field type; documented via `analysis-extras` (Stempel/Morfologik), no fold | `text_ro` ships: `standard → lowercase → stop → snowballPorter(Romanian)`, no normalization filter, no fold |
| Elasticsearch | nothing (0 hits in text-analysis docs) | `polish` via `analysis-stempel` plugin, no fold | `romanian` built-in = Lucene's analyzer (Lucene-10-gated cedilla normalization), no fold |
| OpenSearch | nothing | = ES (`analysis-stempel` present) | = ES minus the version gate |
| Vespa | recognized, folded, **never stemmed** | recognized, folded, **never stemmed** | Snowball stemmer fed **folded** input (degraded) |
| Meilisearch | no stemmer (no stemmer exists for any language) | no stemmer | no stemmer |
| Typesense | `stem: true` **refused** (no Snowball Slovak) | opt-in Snowball, fed **folded** input (degraded) | opt-in Snowball, fed **folded** input (degraded) |
| EdeeCMS | nothing | bare Lucene `PolishAnalyzer`, no fold | bare Lucene `RomanianAnalyzer`, no fold |

Not one cell in this table reconciles folding with stemming; every cell that has a stemmer either
skips folding (accepting the bare-typed miss) or folds first (degrading the stemmer). The Czech
survey's conclusion is fully general across these languages.

---

## 4. Romanian — first-class in core, Czech-shaped nonetheless

### What Lucene ships

`RomanianAnalyzer` is in `analysis/common` proper: `StandardTokenizer → LowerCaseFilter → StopFilter
→ RomanianNormalizationFilter → SnowballFilter(RomanianStemmer)`
(`ro/RomanianAnalyzer.java:118-126`); the `normalize()` hook for wildcard/fuzzy terms is lowercase
plus the normalization filter (`ro/RomanianAnalyzer.java:129-133`).

**The normalization filter is not a folding filter.** `RomanianNormalizer` rewrites exactly four
characters: `ş`→`ș`, `ţ`→`ț` and their capitals (`ro/RomanianNormalizer.java:25-33,42-64`) — the
cedilla forms (`U+015E..U+0163`) to the comma-below forms (`U+0218..U+021B`). This exists because
Romanian text is written in two encodings of the same letters (the cedilla forms are a legacy of
early code pages; comma-below is correct), so the same word arrives spelled two ways *with
diacritics both times*. It is the Czech survey's "Shape A" slot filled by a filter that solves an
orthography-variant problem, not the bare-typing problem. The 2026 Snowball 3.0 stemmer even
duplicates it internally — its first table maps the cedilla forms itself
(`org/tartarus/snowball/ext/RomanianStemmer.java:17`) — so on current Lucene the filter is nearly
redundant.

> **Measured (§9.1): the pinned Lucene 9.12.3 is the opposite situation.** It has *no* normalization
> filter and a Snowball *2.0* stemmer whose tables are cedilla-only, so comma-below text — correct
> modern Romanian — passes through the stemmer effectively unstemmed. The matrix carries R0n/R20n
> rows that backport the Lucene 10 normalization *as a test-scope chain* to measure the repair —
> production shipped the plain R0 wrapper, and R0n is the index shape for when M7 lands (§9.2, §9.6).

**The stemmer's tables are native-orthography.** 78 lines of the generated stemmer carry `ă`
(`ă`) or `ț` (`ț`) — `ația`/`ație` (`RomanianStemmer.java:25,28`), `ățiune`
(`:48-49`), `ătoare`/`ători` (`:52,64`), the `-ități`/`-ăi` abstract-noun families (`:71-72`), and
the verb and plural endings beyond them. A bare-typed query (`masina` for `mașină`, `bataturi` for
`bătături`) never matches these endings — the identical mechanism that put Czech at 77 %
accent-typed recall. The vowel grouping also contains the diacritic vowels
(`RomanianStemmer.java:261`), so folded input additionally mis-marks stemming regions.

### What the other engines do

- **Solr** ships `text_ro` in the `_default` configset as `standard → lowercase → stop →
  snowballPorter(Romanian)` (`server/solr/configsets/_default/conf/managed-schema.xml:969-977`) —
  note: **without** the normalization filter Lucene's own analyzer has, and without folding. The ref
  guide's Romanian section is the Snowball recipe and nothing more
  (`solr-ref-guide/.../language-analysis.adoc:2986-3028`).
- **Elasticsearch**'s `romanian` built-in wraps Lucene's analyzer, gated by index version: indices
  created on Lucene 10+ get the comma-normalizing analyzer, older ones a `LegacyRomanianStemmer`
  without normalization (`modules/analysis-common/.../RomanianAnalyzerProvider.java:38-52`) — ES
  considered the cedilla unification a **breaking change worth a compatibility shim**, which is a
  measure of how real the two-spellings problem is in Romanian corpora. The generic `stemmer` filter
  also offers `romanian` (`StemmerTokenFilterFactory.java:265-266`). No folding anywhere.
- **OpenSearch**: same, minus the version gate (`RomanianAnalyzerProvider.java:47-53`).
- **Vespa** maps `ROMANIAN → SnowballStemmer.ALGORITHM.ROMANIAN`
  (`opennlp-linguistics/.../OpenNlpTokenizer.java:148-173`) but its hard-coded per-token order is
  `normalize → lowercase → accentDrop → stem`, so the stemmer receives folded input its tables were
  not written for — the degraded M3 corner, shipped as the default.
- **Typesense**: its pinned Snowball (`WORKSPACE:341-347`, upstream commit `2c40f9d`) includes
  `romanian.sbl` with aliases `ro`/`ron`/`rum` (upstream `libstemmer/modules.txt`), so `stem: true`
  is accepted — but the Latin-script path folds via `iconv ASCII//TRANSLIT` *before* stemming
  (Czech survey §10), the same degraded corner as Vespa.
- **Meilisearch**: no stemmer exists for any language (Czech survey §9); nothing Romanian-specific.
- **EdeeCMS** registers bare `RomanianAnalyzer` with no folding wrapper
  (`prj_fulltext/.../IndexFactory.java:91`) — its `DiacriticFilter` is wired only into the Czech
  chains.

### How to make it better

Romanian needs no new prior art — it is Czech with the serial numbers filed off, plus one extra
orthography wrinkle:

1. **Step 0 (no new analysis code):** map `ro` in `BuiltInAnalyzers` to
   `DiacriticsFoldingAnalyzerWrapper(new RomanianAnalyzer())` — the A0 production shape. This alone
   moves Romanian from "no folding, no stemming" to the Czech-production baseline (index-side
   perfect, bare-typed query partly broken). `ASCIIFoldingFilter` covers the full Romanian set —
   `ă` (`ASCIIFoldingFilter.java:244`), both `ş`/`ș` (`:1222,1225`), `ț` (`:1281`) — and folding
   after the comma-normalizing stemmer also collapses the cedilla/comma split on the fold lane for
   free.
2. **M7:** port the Snowball Romanian tables into folded space as the query-side hypothesis stemmer,
   forking on the rules that become ambiguous when folded. The candidates visible from the tables:
   final `-ă` vs `-a` (folded, the indefinite `-ă` ending and the definite-article `-a` ending are
   one string — Romanian's likely analogue of Czech's `-at` dilemma), `ț`↔`t` inside the `-ație`/
   `-ițiune` families, `ș`↔`s`, and the region-marking drift from folded vowels. Which of these are
   real forks and what they cost is precisely what a Romanian fixture must measure — the Czech
   record's warning applies verbatim: *a mechanism can only be measured against a failure it is
   given the chance to commit.*
3. **An implementation option Czech didn't have:** because the Romanian stemmer is *generated* from
   `romanian.sbl` by the Snowball compiler, the folded-space port can be authored as a modified
   `.sbl` source and regenerated, rather than hand-porting Java the way `FoldedCzechStemmer` was.
   That keeps the port diffable against upstream when Snowball revises the algorithm. (Czech had no
   such option — `CzechStemmer` is hand-written Java with no Snowball source.)

---

## 5. Polish — three stemmers in the modules, none in core, none folding

### What Lucene ships

No `pl` package exists in `analysis/common`. Polish lives in two optional modules:

- **Stempel** (`analysis/stempel`): `PolishAnalyzer` = `StandardTokenizer → LowerCase → Stop →
  StempelFilter(StempelStemmer(stemTable))` (`pl/PolishAnalyzer.java:135-140`), where the stem table
  is a 2.1 MB trained trie (the Egothor statistical stemmer — "there is nothing language-specific
  here", `stempel/StempelStemmer.java:31-34`, except the shipped table is trained on Polish). Its
  `normalize()` hook is lowercase only (`pl/PolishAnalyzer.java:145-147`) — no normalization filter,
  no folding. Being statistical, it produces *some* stem for any input; on bare-typed input it
  applies patch commands learned from accented Polish to strings that never occur in accented
  Polish — the Hunspell failure mode from the Czech measurements (confidently wrong), not the
  CzechStemmer one (silently inert).
- **Morfologik** (`analysis/morfologik`): a dictionary lemmatizer, "applies to Polish only"
  (`morfologik/MorfologikFilter.java:38-39`), emitting **every** candidate lemma at position
  increment 0 (`MorfologikFilter.java:121-127`) — a shipped multi-lane mechanism, but lanes of
  *lemmas*, not surface forms. A dictionary lookup on a bare-typed word simply misses (and the token
  passes through unstemmed), the exact shape the Czech Hunspell measurements scored at 25 %.
- **Snowball Polish — the new fact in the landscape.** Upstream Snowball merged `polish.sbl`
  (author Dmitry Shachnev) on **2025-10-06** — after Snowball 3.0.1, so it is in no tagged release
  yet — and Lucene vendored the generated `PolishStemmer` with the snowball upgrade commit
  `b26f7672981` (2025-12-18), together with a ready-made analyzer:
  `PolishSnowballAnalyzer` = `StandardTokenizer → LowerCase → Stop → SnowballFilter(PolishStemmer)`
  (`stempel/.../pl/PolishSnowballAnalyzer.java:84-92`). Its tables are native-orthography: the
  `.sbl` declares `ą ę ł ć ń ó ś ź` as its working alphabet (upstream `algorithms/polish.sbl:5-12`),
  the participle, past-tense and conditional endings are spelled with them (`-ąc`/`-ając`,
  `-ała`/`-iła`, `-łyście`, `-ałam` — `PolishStemmer.java:28-87`; `byś`/`byście` in the `.sbl`'s
  conditional table), and the vowel grouping includes `ą`, `ę`, `ó`
  (`PolishStemmer.java:163-165`; `define v 'a{ą}e{ę}io{ó}uy'` in the `.sbl`). No folding, no
  normalization filter — Shape E, exactly like Czech.

`ASCIIFoldingFilter` covers the letters plain NFD-stripping misses — notably `Ł`/`ł`, which is a
*stroke*, not a combining diacritic (`ASCIIFoldingFilter.java:812,833`) — so evitaDB's existing
folding wrapper is sufficient infrastructure for Polish.

### What the other engines do

- **Solr**: no shipped `text_pl` field type in any configset; Polish is documented as an
  `analysis-extras` recipe — `StempelPolishStemFilterFactory` or `MorfologikFilterFactory`
  (`language-analysis.adoc:2857-2920`). One rationale worth quoting survives there: with Morfologik,
  *lowercasing must run after the stemmer* "because the Polish dictionary contains proper names and
  then proper term case may be important to resolve disambiguities" (`language-analysis.adoc:2915`)
  — dictionary lemmatizers constrain the chain around them, one more reason they compose badly with
  folding. No folding appears in either recipe.
- **Elasticsearch**: `polish` exists only via the `analysis-stempel` plugin, which instantiates bare
  `PolishAnalyzer` (`plugins/analysis-stempel/.../PolishAnalyzerProvider.java:18-25`); the generic
  `stemmer` filter has **no** Polish option at this HEAD (`StemmerTokenFilterFactory.java` — the new
  Snowball Polish postdates the bundled Lucene). A separate `analysis-ukrainian` plugin uses
  Morfologik's engine, but for Polish the plugin route is Stempel only. No folding.
- **OpenSearch**: identical — `analysis-stempel` plugin present, nothing more.
- **Vespa**: `POLISH` is in the `Language` enum (`Language.java:328`) but `algorithmFor` has no
  mapping for it (`OpenNlpTokenizer.java:148-173`, `default -> null`) — Polish is folded, never
  stemmed. (Vespa predates upstream Polish Snowball; the mapping may appear once Vespa bumps its
  Snowball dependency.)
- **Typesense**: its pinned Snowball includes `polish.sbl` with aliases `pl`/`pol` (upstream
  `libstemmer/modules.txt` at `2c40f9d`), so `stem: true` validates — and then the Latin-script
  fold-before-stem order feeds the native-orthography tables folded input. Degraded, like Romanian.
- **Meilisearch**: nothing Polish-specific; no stemmer.
- **EdeeCMS**: bare Lucene `PolishAnalyzer` under the name `polish`
  (`prj_fulltext/.../IndexFactory.java:77`), no folding wrapper.

### How to make it better

1. **Step 0 (one line):** wrap evitaDB's `pl` chain in `DiacriticsFoldingAnalyzerWrapper`. Today
   Polish has no fold lane at all, so a bare-typed query misses even the *same form* — behind where
   Czech started. Fold-after-Stempel gives the A0 shape (accent-typed exact-form recall through the
   fold lane, convergence through the stem lane, the combined case still broken).
2. **The strategic decision — which stemmer owns the index side — must precede any M7 work.** M7's
   correctness invariant is: *for every word, the query-side hypothesis set must contain the folded
   image of whatever the index-side stemmer emits.* That set is enumerable only when the index-side
   stemmer is a rule cascade whose ambiguous-in-folded-space rules can be identified and forked:
   - **Stempel**: a statistical trie — there is no rule table to port, and no way to bound the
     folded image of its output. **M7 over Stempel is not constructible.**
   - **Morfologik**: a dictionary — a folded query-side cannot enumerate dictionary hits it cannot
     look up. Same verdict, plus the Czech Hunspell numbers as the transfer prior.
   - **Snowball Polish**: a rule cascade like the Czech and Romanian stemmers. **This is the only
     M7-compatible index side for Polish.**
   Switching `pl` from Stempel to `PolishSnowballAnalyzer` is therefore the gate to M7 — and it is a
   reindex plus an unmeasured relevance change (Stempel and Snowball produce different stems; which
   is better for Polish e-commerce vocabulary is an open question a fixture must answer, *before*
   the M7 investment).
3. **M7 for Polish**, once on Snowball: fold the query, fork on the rules folding makes ambiguous.
   The candidates visible from the tables: `ó`↔`o` (a genuine morphological alternation in Polish —
   the same character class as Czech's `ů→o` shift), `ą`↔`a` and `ę`↔`e` (instrumental/accusative
   endings folding onto bare vowels), `ł`↔`l` (the entire past-tense system folds onto `-al`/`-il`
   strings), and the sibilant collapse (`ś`,`ź`,`ż`→`s`,`z`). Polish plausibly has **more fork
   points than Czech's four** — the measured Czech fan-out (1.30 average, ≤ 2) does not transfer,
   and the fan-out cap the Czech record names as the natural knob (its §6) may actually be needed
   here. That is the Polish-specific risk to measure first.

> **Measured (§9.3): the risk did not materialize.** The folded port needed four switch groups — the
> same count as Czech — and the M7 fan-out came out at 1.12 terms/form average, ≤ 2, *below* the
> Czech 1.30. The cap stays unneeded. Separately, the feared `ó`↔`o` hazard inverted: folding
> *repairs* the `stół`/`stołu` alternation for free, because Polish spells both alternants with
> letters that fold onto each other.

---

## 6. Slovak — nothing to adopt, so the Czech template is the plan

### The negative findings, stated plainly

- **Lucene**: no `sk` package, no Slovak stemmer, no Slovak stopword list, nothing in any module.
  The only "slovak" hits in the whole repository are an unrelated benchmark country list and a
  KStem comment.
- **Snowball upstream**: no `slovak.sbl` at commit `2c40f9d` (2026-04-22) — and no Czech either;
  the two languages share the gap. Nothing pending to wait for.
- **Solr**: zero occurrences of "slovak" in the entire ref guide. No field type, no recipe.
- **Elasticsearch / OpenSearch**: no analyzer provider, no `stemmer` filter option, zero
  occurrences in ES's text-analysis docs.
- **Vespa**: `SLOVAK` is in the `Language` enum (`Language.java:388`) and gets the default
  treatment — folded, never stemmed (`OpenNlpTokenizer.java:148-173`).
- **Typesense**: `validate_language` calls `sb_stemmer_new("sk")` and fails
  (`src/stemmer_manager.cpp:85-97`) — `stem: true` is refused; only the operator-supplied
  `stem_dictionary` word→root table remains.
- **Meilisearch**: no stemmer, as for every language.
- **EdeeCMS**: no Slovak analyzer registered at all (`IndexFactory.analyzerConstructorsMap` —
  `IndexFactory.java:59-93`), despite FG Forrest serving Slovak-market shops.
- **Hunspell `sk_SK`** dictionaries exist (LibreOffice lineage, like the `cs_CZ` one the Czech
  fixture measured) — this is the one adoptable artifact, and the Czech measurements are the prior
  against it: A10 (Hunspell then fold) scored 74/348 combined recall, A12 (fold then Hunspell)
  20/348, and the false-lemma hazard (a bare-typed word being a *different real word* the dictionary
  confidently lemmatizes) is a property of dictionary lookup, not of Czech. Expected to transfer;
  cheap to confirm once a Slovak fixture exists, since the harness already supports Hunspell rows.

evitaDB's current `sk` chain — tokenize, lowercase, fold — is therefore already at parity with
every surveyed engine. That is the problem: the bar is on the floor. The Czech A8 row (fold only,
no stemmer) measured 16/348 crossform convergence under exact matching; Slovak, a similarly
suffix-heavy inflecting language, sits in that corner today.

### How to make it better

There is no third option: adopt (nothing exists), or build. Building is smaller than it sounds,
because the Czech work already paid the architectural costs:

> **Measured (§9.4): built, and it measures better than the template predicted.** The from-scratch
> stemmer converges 319/323 bare-typed cross-form pairs at zero false merges — and its folded port
> turned out to need **no ambiguity switches at all**, so the symmetric and M7 deployments are
> measurement-identical and the M7 shape is chosen (if at all) purely for reindex-free corrections.
> The rhythmic-law expectation below held, and more: the short-variant endings the accented table must
> carry make the accented stemmer serve bare-typed endings too.

1. **Write an in-house Slovak light stemmer in accented space**, patterned on `CzechStemmer`'s
   architecture — case-ending tables walked by exact `endsWith`, a possessive pass, a small
   `normalize()` for stem-final alternations. Czech and Slovak are closely related West Slavic
   languages with structurally parallel declension systems, so the *shape* of the stemmer (and the
   ~230-line size class of the `FoldedCzechStemmer` prototype) transfers; the tables do not, and
   must be authored against Slovak paradigms. Candidate Slovak-specific decisions the tables will
   force (flagged from grammar, to be validated by fixture, not asserted): the `ô` diphthong
   alternation (`stôl`/`stola` — the analogue of Czech `stůl`/`stolu`, and `ô` folds to `o`), the
   `ä` folding onto `a`, rhythmic-law length variation in endings (`-ých`/`-ych` are *both* valid
   accented spellings depending on the preceding syllable — folding actually *removes* a
   distinction the tables would otherwise need twice), and whatever the confusable-pair probes turn
   up. Expect the count of ambiguous folded rules to differ from Czech's four in both directions.
2. **Deploy it M7-shaped from day one.** For a from-scratch stemmer the M7 asymmetry is worth even
   more than it was for Czech: the accented stemmer serves only the index, the folded hypothesis
   port serves only queries, and every table correction — which a new stemmer will need repeatedly —
   is a query-time change requiring no reindex. A symmetric deployment would re-index every Slovak
   catalog on every correction during exactly the period the tables are least trustworthy.
3. **Build the Slovak fixture first** — vocabulary of e-commerce lemmas containing each ending
   class the tables claim to handle, plus confusable-lemma pairs for the precision metric, scored
   with the same five metrics (the Czech record's §3; metric 4, bare-typed cross-form recall, is
   the one that matters). The Czech record's §10 is the checklist of fixture mistakes not to repeat:
   every rule needs both a case it fixes and a case it exists for, in the vocabulary, before its
   measurement means anything.
4. **A Slovak stopword list** must come from somewhere — Lucene ships none (unlike Romanian and
   Polish, whose analyzers bundle `stopwords.txt`). Options: derive from the Czech list by
   translation plus review, or adopt a public list; either way it is a curated artifact evitaDB
   owns, like the stemmer tables.

---

## 7. The ladder, summarized — what to do in what order

| step | `ro` | `pl` | `sk` |
|---|---|---|---|
| 0. wire what exists (no new analysis code) | map `ro` → `RomanianAnalyzer` + folding wrapper | add folding wrapper to the Stempel chain | already done (fold-only) |
| 1. decide the index-side stemmer | Snowball Romanian (already the analyzer's) | **decision needed**: Stempel (status quo, forecloses M7) vs Snowball Polish (enables M7, costs a reindex + relevance re-check) | **build**: in-house accented Slovak light stemmer |
| 2. build the fixture | Romanian vocabulary + confusables, 5 metrics | Polish vocabulary + confusables — also arbitrates Stempel vs Snowball | Slovak vocabulary + confusables — gates the stemmer's tables |
| 3. M7 query side | folded port of Snowball Romanian (authorable as a modified `.sbl`) with hypothesis forks | folded port of Snowball Polish, same route; watch the fan-out — likely more fork points than Czech | folded port of the in-house stemmer, exactly the `FoldedCzechStemmer` pattern |

Step 0 is uncontroversial and immediately shippable for `ro` and `pl`. Everything past it inherits
the two open decisions the Czech measurements already posed (its §8): whether evitaDB owns
language stemmers with no upstream — now asked three more times, loudest for Slovak where owning
one is the *only* option — and the query pipeline's one-token-to-OR'd-terms expansion capability,
which all three M7 deployments share with Czech's and with synonym support. Nothing in this survey
changes those decisions; it shows they are the same decisions at triple the leverage.

---

## 8. Limits of this survey

- **No measurements.** Every recall/precision expectation above is a transfer from the Czech
  matrix by structural analogy. The Czech record needed six runs to get its own numbers right;
  assume the analogies here are wrong in at least one place per language until a fixture says
  otherwise.
- **Linguistic claims are flagged, not established.** The candidate folded ambiguities named for
  each language were read off the stemmer tables (Romanian, Polish) or standard grammar (Slovak)
  by inspection, not measured. The fixture-construction step exists precisely because inspection
  missed two of Czech's four ambiguous rules.
- **Snowball Polish is unreleased.** It sits in upstream master past 3.0.1 and in Lucene's vendored
  snapshot; its tables may still change upstream (they were already revised once, 2026-04-18,
  apostrophe handling). Depending on it means tracking upstream until it lands in a tagged release.
- **Narrative documentation out of scope**, as in the Czech survey: Elastic's `docs-content`,
  OpenSearch's `documentation-website`, Vespa's and Meilisearch's doc sites were not surveyed;
  in-repo code and reference docs were.
- **charabia internals not inspected** (Meilisearch's normalizer, external crate), same boundary as
  the Czech survey.
- **Bare-typing prevalence per language is assumed, not sourced.** The economic weight of the
  bare-typed-query problem differs by market (keyboard layouts, mobile share); the survey takes the
  Czech finding's relevance to `sk`/`pl`/`ro` markets as given. If any language's users
  overwhelmingly type with diacritics, that language's M7 case weakens to a nice-to-have.

---

## 9. Measurements — the three matrices, run 2026-09-04

The plan the survey fed into (`sk-pl-ro-analysis-matrix-plan.md`) was executed in full on 2026-09-04:
the Czech harness was extracted into a language-agnostic instrument, and each language got its own
fixture and matrix, in the agreed order RO → PL → SK. This section is the measurement record; the
matrix tests themselves live next to the Czech ones in
`evita_test/evita_functional_tests/src/test/java/io/evitadb/index/fulltext/analysis/`
(`RomanianAnalysisApproachMatrixTest`, `PolishAnalysisApproachMatrixTest`,
`SlovakAnalysisApproachMatrixTest`) and re-produce every number below on demand; read their output
from the surefire XML, not the console — console encoding mangles diacritics, exactly as the Czech
record warns.

The five metrics are the Czech record's, unchanged (accent-typed recall / bare-typed cross-form
recall / pairwise convergence / strict convergence / false merges), computed by the extracted
`AnalysisApproachMeasurer`. The Czech tests were re-run against the extracted harness with
**byte-identical matrix output** (54,787 characters compared) before any new language was measured.

### 9.1 Environment — where the pinned Lucene diverges from the surveyed HEAD

The survey read Lucene **main**; the project pins **Lucene 9.12.3**, and three of the survey's
statements about "what Lucene ships" are simply not yet true at that version. These divergences
shaped the matrices and are facts a reader of §§4–5 must overlay:

1. **The 9.12.3 `RomanianAnalyzer` has no normalization filter at all** — `RomanianNormalizationFilter`
   is a Lucene 10 addition — and its Snowball stemmer is the **2.0-generation algorithm whose tables
   are written exclusively in the legacy cedilla orthography** (`ş` U+015F, `ţ` U+0163; not one
   comma-below character in the class). The stop list is cedilla-spelled too. Consequence: on 9.12.3
   the Romanian stemmer effectively **does not stem correctly-spelled (comma-below) Romanian** — the
   survey's remark that the normalization filter is "nearly redundant" on current Lucene is the exact
   opposite of the 9.12.3 situation. The matrix carries R0n/R20n rows that backport the Lucene 10
   normalization to measure what it buys — a chain built **inside the matrix test only**
   (`normalizedProductionChain()` with its nested `CommaBelowNormalizationFilter`, in front of the
   stop filter and stemmer); nothing of it shipped to production, which got the plain R0 wrapper.
   Note the direction is inverted against Lucene 10: its Snowball 3.0 tables are comma-spelled so it
   normalizes toward comma-below, while on 9.12.3 the stop list and tables are cedilla-spelled and
   the only useful direction is toward cedilla.
2. **Snowball Polish exists in no released Lucene at all** — the Lucene main checkout files its
   CHANGES entry (GITHUB#15505) under the unreleased **11.0.0** section, with no 10.x backport as of
   the checkout HEAD, and upstream Snowball has it in no tagged release either (post-3.0.1). The
   generated `PolishStemmer` was therefore vendored from the local Lucene main checkout into test
   scope (`PolishSnowballStemmer`) with exactly two adaptations — and the vendored copy can only be
   retired when evitaDB reaches a Lucene version that ships it: the package/class name, and private
   `go_out_grouping`/`go_in_grouping` shims copied from Lucene main's `SnowballProgram`, because the
   9.12.3 Snowball runtime predates the 3.0 generated-code contract. Everything else the generated
   code calls is present and compatible in 9.12.3.
3. **Two rows were blocked on missing artifacts in the 2026-09-04 environment and were measured on
   2026-09-07 once the artifacts arrived.** The Slovak Hunspell rows needed only the `sk_SK`
   dictionary pair as test resources (added next to the Czech one; §9.4). The Morfologik row P0m
   needed the `lucene-analysis-morfologik` Maven dependency — unreachable on 2026-09-04, fetched on
   2026-09-07 through Maven's canonical central host `repo.maven.apache.org` via the corporate Nexus
   mirror, and pinned to `${lucene.version}` in the root `dependencyManagement` with the other three
   Lucene artifacts (test scope in `evita_functional_tests`); §9.3. One operational hazard worth
   recording: running Maven *online* for the fetch let it replace several locally-installed evitaDB
   SNAPSHOT artifacts with stale remote ones (the fulltext work is unmerged, so the remote snapshots
   predate it), which broke compilation until the local modules were reinstalled — measurement runs
   should stay `-o` (offline) and go online only for the one fetch.

One harness correction was forced *before* any Polish number existed and is worth its own paragraph:
**the measurer's NFD-based bare-typing helper silently cannot express Polish.** `ł` is a stroked
letter, not a base-plus-combining-mark composition, so NFD stripping leaves it alone — and a fixture
measured that way would have *excluded the entire `ł` class from every bare-typed metric* (each
`ł`-bearing form's "bare typing" would equal the form itself, which the measurer treats as vacuous
and skips). `AnalysisApproachMeasurer.measure` therefore gained a per-language `bareTyper` parameter;
the Polish fixture passes NFD-plus-`ł`→`l`, the Czech, Romanian and Slovak fixtures keep the default.
This is the Czech record's §10 lesson in a new costume: *a metric can only measure a failure it is
able to express.*

### 9.2 Romanian

**Fixture:** 33 lemmas / 6 confusable lemmas (`RomanianAnalysisFixture`), e-commerce domain, forms
following standard DEX declension paradigms — **authored without a native speaker and not yet
natively reviewed** (dexonline.ro is the verification pointer; the numbers are a first run until that
review happens). Romanian-only class with no Czech precedent: **cedilla-vs-comma encoding probes**
(`mașină`/`maşină`, `garanție`/`garanţie` carried as forms of one lemma), because real Romanian
corpora contain both encodings and the pinned Lucene reconciles nothing.

| approach | matching | accent-typed | bare+crossform | conv. pairs | conv. strict | false merges |
|---|---|---|---|---|---|---|
| R0 stem→fold (survey step 0) | exact | 47/49 | 94/120 | 160/230 | 18/33 | 0 |
| R0n norm→stem→fold | exact | 44/49 | 94/120 | 160/230 | 18/33 | 0 |
| R0b bare `RomanianAnalyzer` | exact | 10/49 | 21/120 | 116/230 | 12/33 | 0 |
| R1 fold→stem (naive M3) | exact | 49/49 | 94/120 | 160/230 | 19/33 | 0 |
| R2 fold→foldedStem[---] | exact | 49/49 | 86/120 | 146/230 | 16/33 | 0 |
| R3 …[tiune] | exact | 49/49 | 86/120 | 146/230 | 16/33 | 0 |
| R4 …[sverb] | exact | 49/49 | 91/120 | 152/230 | 17/33 | 0 |
| R5 …[am] | exact | 49/49 | 86/120 | 146/230 | 16/33 | 0 |
| R6 …[tiune,sverb,am] | exact | 49/49 | 91/120 | 152/230 | 17/33 | 0 |
| R8 fold only, no stemmer | exact | 49/49 | 13/120 | 14/230 | 0/33 | 0 |
| R20 R0-index / hypotheses[verified full set] | exact | **49/49** | 98/120 | 166/230 | 18/33 | **0** |
| **R20n R0n-index / hypotheses[verified full set]** | exact | **49/49** | **99/120** | **166/230** | 18/33 | **0** |
| R21n R0n-index / hypo[sverb,averb] (fork subset) | exact | 47/49 | 93/120 | 152/230 | 18/33 | 0 |
| R22n R0n-index / hypo[averb] (fork subset) | exact | 45/49 | 90/120 | 149/230 | 18/33 | 0 |
| R0 | prefix+fuzzy | 47/49 | 102/120 | 192/230 | 18/33 | 35 |
| R2 | prefix+fuzzy | 49/49 | 102/120 | 185/230 | 16/33 | 35 |
| R8 | prefix+fuzzy | 49/49 | 91/120 | 167/230 | 0/33 | 20 |

M7 query fan-out over 116 bare-typed forms: **2.15 terms/form average, 4 at most** with the lexicon-verified
full hypothesis set of §9.8 (the pre-sweep four-fork set measured 1.09 / 2; the increase is dominated by the
surface hypothesis, which adds one term whenever stemming changed the token at all).

**Findings.**

- **The Czech playbook transfers: M7 wins.** R20n reaches total accent-typed recall, the best
  bare-typed cross-form recall of any exact-matching row, and zero false merges — strictly dominating
  the R0 production shape (94→99 of 120) with a fan-out *below* Czech's. The folded port needed
  **four** switchable ambiguities, not the survey's predicted set: `tiune` (`ţiune`→`t` vs genuine
  `-tiune`), `sverb` (the `ş`-spelled verb endings vs genuine plain-`s` nouns), `am` (conditional
  `am` vs unconditional `âm`/`ăm`), and — **found by the first M7 run, not predicted** — `averb`,
  the partnerless `ă`/`â`-spelled verb endings (`ară`, `ăsc`, `ează`, the `arăm` family, plus folded
  `ati` ⟵ `ăţi`) that fold onto plain strings genuine words end with (`cumpara` → `cump` where the
  accented chain strips one vowel). The same discovery dynamic as Czech's epenthetic-`e`: symmetric
  chains mis-stem both sides identically and hide it; only the asymmetric mechanism exposes it.
- **Which forks matter is now measured, not argued**: on the fixture, dropping `tiune` and `am` loses
  nothing among the rule forks, while dropping `sverb` loses the `-ești` adjective plurals (R22n vs
  R21n, −2 accent forms). The R20/R20n rows have since moved to the lexicon-verified full hypothesis
  set (§9.8), which additionally carries the step gates and the surface hypothesis and therefore
  dominates every fork subset.
- **The survey's region-drift worry is dead**: Romanian folding maps vowels to vowels (`ă`/`â`→`a`,
  `î`→`i`), so RV/R1/R2 marking is identical on folded and accented input. Verified in the port, not
  assumed.
- **The encoding break is real but is masked by fold-after and by the M7 fan-out on this vocabulary**:
  R0n (normalized index) beats R0 by exactly one bare-typed pair once the hypothesis query is in
  place (99 vs 98), and R20 over the *unnormalized* index still reaches 49/49 because the
  conservative hypothesis often coincides with the folded unstemmed index term. The normalization is
  still the correct index shape — it makes index terms encoding-uniform and the M7 invariant hold by
  construction rather than by coincidence — but its measured payoff here was small. Note the
  survey's expectation that the cedilla rewrite "solves a different problem than bare typing" held;
  what it missed is that on 9.12.3 there is no rewrite at all and the *stemmer itself* is
  encoding-broken (`lucrați` passes through unstemmed; `lucraţi` stems to `lucr`).
- **The Snowball Romanian algorithm is weak on short adjectives regardless of mechanism** — it never
  strips final `-u` (`negru` cannot meet `negri`), and short feminines like `alba` keep their `-a`
  outside RV. Every stemmer row inherits ~66 convergence misses from this plus the ablaut splits
  (`masă`/`mese`); prefix matching recovers most of them directionally (R0 fuzzy: 192/230). This caps
  what any Snowball-based Romanian chain can deliver and belongs in expectations for the production
  rollout.

**Corrections ledger (Romanian):** run 1 → run 2: added the `averb` switch (folded `ară`-family
over-firing found via M7 accent misses `cumpara → cump`) and the R0n/R20n normalized-index rows
(encoding break found via `lucrați → lucrat` vs hypothesis `lucr`). Run 2 → run 3: moved folded
`ati` into the `averb` group — it also arises from `ăţi` (`calități` → index `calitat`, query
hypothesis previously only `calit`). Run 3 is the table above; its assertions are pinned in the test.

### 9.3 Polish

**Fixture:** 32 lemmas / 8 confusable lemmas (`PolishAnalysisFixture`), forms following standard
WSJP/Wiktionary paradigms — **authored without a native speaker and not yet natively reviewed**.
Polish-only fixture property: the `bareType` function (`ł`→`l` on top of NFD; see §9.1). The
confusables plant the survey's own pairs: `łoś`/`los` and `skała`/`skala` (stroke-only collisions —
the floor folding itself pays), `pączek`/`paczka`, `liść`/`lista`.

| approach | matching | accent-typed | bare+crossform | conv. pairs | conv. strict | false merges |
|---|---|---|---|---|---|---|
| P0 stempel→fold (survey step 0) | exact | 20/62 | 37/175 | 234/344 | 19/32 | 8 |
| P0b bare stempel (**production before this work**) | exact | **0/62** | **1/175** | 234/344 | 19/32 | 4 |
| P0s snowball→fold | exact | 50/62 | 117/175 | 298/344 | 23/32 | 20 |
| P0m morfologik→fold | exact | 19/62 | 48/175 | **344/344** | **32/32** | 36 |
| P1 fold→snowball (naive M3) | exact | 62/62 | 126/175 | 260/344 | 17/32 | 20 |
| P2 fold→foldedStem[----] | exact | 62/62 | 126/175 | 260/344 | 17/32 | 20 |
| P3 …[l] | exact | 62/62 | 121/175 | 258/344 | 17/32 | 22 |
| P4 …[nasal] | exact | 62/62 | 126/175 | 260/344 | 17/32 | 20 |
| P5 …[soft] | exact | 62/62 | 122/175 | 256/344 | 16/32 | 20 |
| P6 …[ow] | exact | 62/62 | **149/175** | **300/344** | 23/32 | 20 |
| P7 …[l,nasal,soft,ow] | exact | 62/62 | 140/175 | 294/344 | 22/32 | 22 |
| P8 fold only, no stemmer | exact | 62/62 | **0/175** | **0/344** | 0/32 | 8 |
| **P20 P0s-index / hypotheses[all four forks]** | exact | **62/62** | **148/175** | **303/344** | 23/32 | 24 |
| P20m P0m-index / hypotheses[verified full set] | exact | 27/62 | 72/175 | 165/344 | 32/32 | 20 |
| P21 P0s-index / hypo[-ów] | exact | 55/62 | 128/175 | 283/344 | 23/32 | 24 |
| P22 P0s-index / hypo[ł only] | exact | 55/62 | 128/175 | 283/344 | 23/32 | 24 |
| P0 | prefix+fuzzy | 42/62 | 97/175 | 291/344 | 19/32 | 20 |
| P7 | prefix+fuzzy | 62/62 | 161/175 | 328/344 | 22/32 | 60 |
| P8 | prefix+fuzzy | 62/62 | 88/175 | 199/344 | 0/32 | 19 |

M7 query fan-out over 139 bare-typed forms: **1.95 terms/form average, 4 at most** with the lexicon-verified
full hypothesis set of §9.8 (the pre-sweep four-fork set measured 1.12 / 2). The P20 recall and precision
numbers were unchanged by the enlargement — the extra hypotheses fire on word classes the fixture does not
carry.

**Findings.**

- **Today's production is exactly as broken as the survey said**: bare Stempel serves zero
  accent-typed queries and one bare-typed cross-form pair (a coincidence) out of 175. Polish
  inflection also means fold-only under exact matching converges *nothing* (P8: 0/175, 0/344 —
  compare Slovak's identical corner in §9.4), so nothing short of a stemmer helps.
- **The measurement the survey could not predict: the step-0 folding wrapper rescues Stempel only
  partially (20/62), because Stempel mangles the bare-typed *query*.** The statistical trie stems any
  input, and on bare-typed text it applies patch commands learned from accented Polish to strings
  that never occur in accented Polish — `zolty` → `zsc` — which no folded index lane can meet. This
  is the "confidently wrong" Hunspell failure mode from the Czech measurements, surfacing on the
  query side. The wrapper still shipped (strictly better than nothing on every recall metric), but
  the survey's implicit assumption that step 0 buys Polish what it bought Czech is **refuted**.
- **The index-side arbitration is one-sided: Snowball dominates Stempel on every recall metric** —
  accent-typed 50 vs 20, bare cross-form 117 vs 37, convergence 298 vs 234, strict 23 vs 19. Its
  extra false merges (20 vs 8) are entirely the deliberately planted stroke-collision confusables
  (`łoś`/`los`, `skała`/`skala` — identical *folded surface forms*, merged by any folded chain).
  On this fixture there is no metric under which Stempel is worth keeping; the remaining case for
  caution is only that the fixture is small and not natively reviewed. The Stempel→Snowball switch
  (a reindex plus a relevance change) is decision-ready, and M7 for Polish is constructible only on
  the Snowball side of it.
- **The Morfologik row (measured 2026-09-07) completes the index-side comparison with a textbook
  dictionary profile — and rules itself out.** On accented text it is the best lemmatizer measured in
  any of the four languages: a *perfect* 344/344 pairwise and 32/32 strict convergence, which is what
  a dictionary buys when the word is in it. On bare-typed text it is nearly blind (19/62 accent-typed,
  48/175 cross-form — a bare-typed word is not a dictionary entry and passes through unstemmed), it
  carries the most false merges of any exact row (36, partly because it emits every candidate lemma:
  1.28 terms/form), and — decisively — it offers M7 no rule table to fork over, exactly as §5 argued.
  The Czech Hunspell transfer prior is confirmed in both directions: unmatched convergence, unusable
  bare-typed recall.
- **The "M7 is not constructible over a dictionary" claim (§5) is now measured, not argued — P20m
  (Morfologik index under the hypothesis query) collapses on convergence, the metric a lemmatizer
  owns**: 165/344 against the symmetric row's perfect 344/344, with cross-form recall at 72/175. The
  failure shape is exactly the predicted invariant break: the dictionary indexes *lemmas*, which are
  words (`żółty` → folded `zolty`), while the rule-based hypotheses emit *stems* (`zolt`) —
  systematically apart by the lemma's own ending. The surface hypothesis the verified set carries
  (§9.8) rescues the cases where the lemma equals the surface or a truncation stem — accent-typed
  recall rose from 11/62 to 27/62 when it was added — but no enlargement of the fork set can close
  the lemma-vs-stem language gap, because it is not an un-forked ambiguity but a different target
  language for the query to hit. (The row's strict metric stays 32/32 because it is a property of
  the index side alone.)
- **Port fidelity is pinned by a test, not claimed**: P2 (folded port, all four groups off) is
  measurement-identical to P1 (the real Snowball stemmer fed folded input), because the real
  stemmer's diacritic-spelled entries never fire on folded text. Any future divergence is a port
  bug by definition (`shouldShowFoldedPortFidelity`).
- **M7 wins again**: P20 reaches 62/62 accent-typed, 148/175 bare cross-form and 303/344 convergence
  — better than any symmetric row — at +4 false merges over P0s, all four inside the planted
  `skała`/`skala` family. **The `ów` fork is indispensable** (dropping it loses 7 accent-typed
  genitive plurals and 20 cross-form pairs); the `ł` fork carries the past-tense family; the nasal
  and soft forks bought nothing measurable on this vocabulary beyond those two (P21 ≡ P22).
- **Two survey fears did not materialize.** The predicted fork-point explosion: Polish needed **four**
  switch groups — the same count as Czech — and the measured fan-out (1.12 avg, ≤ 2) is *below*
  Czech's 1.30, so the fan-out cap the Czech record holds in reserve stays unneeded. And the `ó`↔`o`
  stem alternation (`stół`/`stołu`, the feared Czech-`ů` analogue): **folding repairs it for free**,
  because Polish spells both alternants with letters that fold onto each other — where Czech's `ů`
  needed a lossy rewrite, Polish needs nothing.

**Corrections ledger (Polish):** run 0 (before any measurement): the measurer's bare-typing function
was made per-language after the `ł`/NFD analysis showed the default would exclude the whole `ł` class
(§9.1). Run 1 is the table above; no fixture corrections were needed within it (the Romanian and
harness lessons were already applied). The `sza`/`szą` and `sze`/`szę` action conflicts are resolved
in favour of the plain spelling and deliberately not modelled as switches — the difference is
confined to the sub-R1 region; revisit only if a native-reviewed fixture shows it mattering. Run 1 →
run 2 (2026-09-07): the P0m Morfologik row was added once the dependency became fetchable (§9.1), and
P20m (the hypothesis query over the Morfologik index) with it — turning the §5 non-constructibility
argument into a measurement; no fixture or stemmer change.

### 9.4 Slovak

**Fixture:** 37 lemmas / 8 confusable lemmas (`SlovakAnalysisFixture`), forms following the standard
paradigms (juls.savba.sk is the verification pointer) — native review is realistic here and should
happen; the fixture grew twice during the runs (see the ledger). The confusables deliberately reuse
the Czech pairs that exist in Slovak (`ruka`/`rok`, `buk`/`bok`, `cesta`/`český`, `forma`/`formát`)
so the two languages' precision floors are directly comparable.

**Artifacts built, not adopted** (nothing exists to adopt — §6): `SlovakStemmer`, an accented-space
light stemmer on the `CzechStemmer` architecture with tables authored fresh against Slovak paradigms,
and `FoldedSlovakStemmer`, its folded port with three switchable fold-ambiguities. **A third
instrument exists for Slovak that no other language has yet**: `SlovakFoldedStemmerLexiconTest`
verifies the M7 correctness invariant — `fold(SlovakStemmer(w))` must be contained in the folded
port's hypothesis set over `fold(w)` — for **every word of the sk_SK Hunspell lexicon (264,838
headwords)**, and it stands at **0 uncovered words** with 4.44 % of the lexicon genuinely forked.

| approach | matching | accent-typed | bare+crossform | conv. pairs | conv. strict | false merges |
|---|---|---|---|---|---|---|
| S0 fold only (**production**) | exact | 125/125 | **0/355** | **0/390** | 0/37 | 0 |
| S1 stem→fold | exact | 118/125 | 338/355 | 386/390 | 36/37 | 0 |
| S1b stem, no fold | exact | 28/125 | 91/355 | 368/390 | 34/37 | 0 |
| S2 fold→foldedStem[om,ie,ek] | exact | 125/125 | 349/355 | 384/390 | 35/37 | 0 |
| S3 …[ie,ek] (om off) | exact | 125/125 | 343/355 | 378/390 | 34/37 | 0 |
| S4 …[om,ek] (ie off) | exact | 125/125 | 336/355 | 368/390 | 32/37 | 0 |
| S5 …[om,ie] (ek off) | exact | 125/125 | 320/355 | 352/390 | 29/37 | 0 |
| **S20 S1-index / hypotheses[om,ie,ek]** | exact | **125/125** | **351/355** | 386/390 | **36/37** | **0** |
| S10 hunspell→fold | exact | 39/125 | 95/355 | 378/390 | 35/37 | 0 |
| S12 fold→hunspell | exact | 125/125 | 6/355 | 28/390 | 2/37 | 0 |
| S0 | prefix+fuzzy | 125/125 | 189/355 | 205/390 | 0/37 | 18 |
| S1 | prefix+fuzzy | 125/125 | **355/355** | **390/390** | 36/37 | 37 |
| S2 | prefix+fuzzy | 125/125 | 354/355 | 389/390 | 35/37 | 37 |

M7 query fan-out over the fixture: **1.15 terms/form average, 4 at most**; over the whole lexicon,
**4.44 % of words fork at all**. Slovak is the one language whose hypothesis set needed neither step gates nor
the surface hypothesis (§9.8) — its sweep reached zero uncovered words on the three rule forks alone.

**Findings.**

- **The bar really was on the floor, and the built stemmer clears it at zero precision cost**:
  today's fold-only production converges *nothing* cross-form under exact matching (0/355 — Slovak's
  version of the Czech A8 corner); the M7 deployment takes that to 351/355 with **zero false
  merges** on the fixture. The four residual misses are one deliberate trade (`slúchadlá`/
  `slúchadiel`, the genitive-plural lengthening before a final **`l`** — converging it would extend
  the epenthesis rule to `l` and mangle `hotel`/`model`).
- **The first three runs reported the folded port as switch-free and all three deployments as
  measurement-identical — that was a false positive of the fixture, exposed the moment the sk_SK
  lexicon became available as a property-test corpus.** A ~265k-word sweep found five fold-hazard
  classes the 32-lemma vocabulary could not commit, and the record keeps the correction prominent
  because it is the strongest argument for lexicon-scale verification the whole campaign produced:
  a fixture can only falsify what it carries. Two hazards were **resolved outright**: the `in`
  possessive was removed from both stemmers (its folded image ate the `-ín`/`-ína` loanword classes
  — `vitamín`, `vitrína` — while the possessive itself is marginal in shop text), and the `i`-stem
  vs soft-plural ambiguity (`kategóri+ám` vs `ulic+iam`, one folded string) **dissolved** once both
  stemmers began trimming a stem-final `i` — with the trim, both segmentations land on one stem, so
  no fork is needed. Three hazards became **switches the M7 query forks**: `om` (the core
  instrumental ending vs the `-óm` nominals: `tričkom` vs `chróm`), `ie`-shortening (genitive-plural
  `stoličiek` vs the `ié` loanwords `bariéra`, `ateliér`), and `ek`-epenthesis (`darček` vs the
  `-téka` nominals `hypoték`). A last round of lexicon misses were plain **accented-table gaps**, not
  ambiguities — long-vowel ending variants missing next to their short twins (`-ó`/`-ô` finals,
  `-éj`, `-ôch`, `-ámi`) — fixed by adding the entries; the marginal `mi` instrumental was removed
  from both tables instead (folded it over-matched `m+í` verb forms, and its own class — `ľuďmi`,
  `koňmi` — is irregular-animate and rare in shop text).
- **After the corrections, Slovak looks like every other measured language: M7 wins.** S1 loses
  accent-typed recall on exactly the ambiguous classes (118/125 — the accented table applied to
  bare-typed `chrom`, `kategoriam` over-strips), each symmetric switch position loses one class
  (S3–S5), and only the forked hypothesis query covers everything at once. What remains genuinely
  Slovak-specific is *why the gap is so narrow*: the rhythmic law forces the plain short-ending
  variants into the accented table as legitimate spellings (`-ych` in `čiernych`, `-ach` in
  `topánkach`), so the accented stemmer strips most bare-typed endings without being designed to —
  traced word by word:

  | | Czech, stem only | Slovak, stem only |
  |---|---|---|
  | accented gen. pl. | `černých → čern` | `čiernych → čiern` |
  | bare-typed gen. pl. | `cernych → cernych` — ending kept, **misses** | `ciernych → ciern` — same table entry, **hits** |
  | accented vowel alternation | `stůl → stol` (`ů→o` rewrite) | `stôl → stol` (`ô→o` rewrite) |
  | bare-typed vowel alternation | `stul → stul` — plain `u`, no rewrite, **misses** | `stol → stol` — already the rewrite's output, **hits** |

  Czech cannot behave this way: its orthography admits only the long ending spelling (bare `-ych` is
  not a word form, so `CzechStemmer`'s tables rightly lack it) and its alternation letter `ů` folds
  onto ambiguous `u`, while Slovak's `ô` folds onto its own alternant. That is why Slovak's fold
  ambiguities hide in loanword classes (`-óm`, `-ié-`, `-téka`) rather than in the core declension
  where Czech's live — and why a fixture built from core declension paradigms could not see them.
- **The adoptable-artifact check (Hunspell `sk_SK`, rows S10/S12) reproduces its Czech shape and
  loses to the in-house stemmer on every recall metric.** With folding after the lookup the
  dictionary lemmatizes accented text well (378/390) but is nearly blind to bare typing (39/125,
  95/355); with folding first it collapses outright (28/390). The Czech false-lemma hazard did not
  commit on this vocabulary (0 merges in both orders — the confusables carry no bare-typed form that
  is a different real Slovak word; a native-reviewed fixture should plant one). Verdict unchanged:
  the dictionary is not a path to Slovak inflection, and "build" beats "adopt" on the only artifact
  there was to adopt.

**Corrections ledger (Slovak):** run 1 → run 2: added the genitive-plural `ie`-shortening rule after
run 1 showed every residual convergence miss was that one class; the final-`l` variant was considered
and declined (`hotel`/`model`). Run 2 → run 3 (2026-09-07): the S10/S12 Hunspell rows were added once
the `sk_SK` dictionary pair became available as a test resource. Runs 4–7 (2026-09-07, prompted by a
review question about the S1 ≡ S20 identity): the same dictionary became the corpus of the lexicon
coverage test, which falsified the switch-free headline and drove four correction rounds — removed
the `in` possessive, added the stem-final-`i` trim, made `om`/`ie`-shortening/`ek`-epenthesis
switchable and forked, added the missing long-vowel table entries (`-ó`, `-ô`, `-éj`, `-ôch`,
`-ámi`), removed the `mi` entry, and added the fixture probes for every class (`vitamín`, `chróm`,
`kategória`, `bariéra`, `hypotéka`). The sweep now stands at 0 uncovered words over 264,838.

### 9.5 What §7's ladder looks like after the measurements

| step | `ro` | `pl` | `sk` |
|---|---|---|---|
| 0. wire what exists | **shipped**: `ro` → `RomanianAnalyzer` + folding wrapper (measured R0: 10/49 → 47/49 accent-typed) | **shipped**: folding wrapper on Stempel (measured P0: 0/62 → 20/62 — partial, Stempel mangles bare queries) | already done (fold-only; measured S0: the 0/323 floor) |
| 1. index-side stemmer | Snowball RO stays; add comma→cedilla normalization when M7 lands (R0n) | **decision ready**: Snowball dominates Stempel on every recall metric (§9.3); switching costs a reindex + relevance re-check on a native-reviewed fixture | **built**: `SlovakStemmer` (test scope); graduation to `evita_engine` is the open "do we own it" decision, now with numbers (0→351/355 at 0 merges, lexicon-verified) |
| 2. fixture | built, 33+6 lemmas — **needs native review** | built, 32+8 lemmas — **needs native review** | built, 32+8 lemmas — native review realistic and pending |
| 3. M7 query side | **works**: 49/49 accent-typed, fan-out 1.09/2; needs the `sverb`+`averb` forks only | **works, only over Snowball**: 62/62, fan-out 1.12/2; needs the `ów`+`ł` forks | **works and wins** (351/355 vs S1's 338): forks `om`, `ie`-shortening, `ek`-epenthesis; fan-out 1.15/4, 4.44 % of the lexicon forks at all; the M7 invariant is lexicon-verified (0 uncovered / 264,838 words) |

The two §8 open decisions of the Czech record read differently now: evitaDB owning language stemmers
with no upstream is no longer hypothetical — the Slovak one exists and measures well — and the
query-pipeline one-token-to-OR'd-terms expansion is needed by exactly two of the three languages
(RO, PL), with measured fan-outs at or below the Czech 1.30 that decision was already priced at.

### 9.6 Production changes shipped with this record

- `BuiltInAnalyzers`: `ro` → `DiacriticsFoldingAnalyzerWrapper(new RomanianAnalyzer())` (new
  `ROMANIAN_ANALYZER_NAME`), `pl` → `DiacriticsFoldingAnalyzerWrapper(new PolishAnalyzer())`. Both
  measured before shipping (rows R0 / P0); both are pure additions to the analysis chain output —
  folded terms extend what a schema's index sees, no existing term disappears — but Polish and
  Romanian catalogs still need reindexing to *benefit*.
- Test-scope only (deliberately not production): `PolishSnowballStemmer` (vendored),
  `FoldedRomanianStemmer`, `FoldedPolishStemmer`, `SlovakStemmer`, `FoldedSlovakStemmer`, the three
  fixtures and matrices, and the extracted `AnalysisApproachMeasurer`/`FoldedStemmer`/
  `FoldedStemFilter`/`HypothesisStemFilter` harness (the Czech fixture and matrix now share it;
  their numbers were verified byte-identical across the extraction).

### 9.7 Limits of these measurements

- **No native review yet, three languages.** Every number above is a first run over a
  non-native-authored vocabulary; the Czech record needed six runs and native intuition to stabilize.
  The per-language ledgers (§9.2–9.4) exist so the next run starts where this one stopped.
- **The Romanian `tiune` benefit case is unreachable on 9.12.3 for comma-spelled text** (the accented
  rule requires a literal `ţ`), so the fork measured zero on both sides; re-measure after a Lucene
  upgrade or with the R0n normalization in production.
- **The fixture-scale numbers are upper-bounded by what the fixture can express — proven, not
  suspected**: the Slovak "switch-free port" headline survived three runs and fell to the first
  lexicon-scale sweep (§9.4). The sweep has since been generalized and run for all four languages to
  zero uncovered words (§9.8), so every fork set is now lexicon-verified; the remaining bound is that
  the `.dic` files carry headwords, not inflected forms — the recall/precision numbers stay
  fixture-scale until a full-form corpus exists.
- **Both dictionary rows have now been measured** — Slovak Hunspell (§9.4) and Polish Morfologik
  (§9.3), each confirming the Czech dictionary prior: excellent accented convergence, unusable
  bare-typed recall, no M7 path.
- **The Polish nasal/soft forks measured zero effect** — that is "this vocabulary cannot distinguish
  them", not "they are free/useless"; a native-reviewed fixture should plant probes for both before
  the M7 fork set is frozen.
- **Cost of the shipped `pl` wrapper**: +4 false merges on the fixture (8 vs 4), all inside the
  planted stroke-collision confusables — the price of having a fold lane at all, paid identically by
  every folded chain.

### 9.8 Lexicon-scale verification — all four languages swept to zero (2026-09-07)

The Slovak sweep of §9.4 was generalized (`LexiconCoverageSweep`) and run for every language once the
Hunspell `ro_RO` and `pl_PL` dictionaries joined `cs_CZ` and `sk_SK` in the test resources. The
invariant per word: `fold(accentedStem(w))` — the index term — must be contained in the union of the
folded-stemmer hypotheses over the bare-typed word, i.e. exactly the term set the M7 query emits. One
per-language `*FoldedStemmerLexiconTest` pins it, and each language's hypothesis set now has a single
source of truth (`Folded*Stemmer.allHypotheses()`) shared by the matrix M7 rows and the sweep, so the
production-shaped chain and the verifier can never drift apart.

| lexicon | headwords tested | words that fork | uncovered | correction rounds to zero |
|---|---|---|---|---|
| cs_CZ | 260,926 | 86.5 % | **0** | 4 |
| ro_RO | 175,547 | 77.2 % | **0** | 3 |
| pl_PL | 279,452 | 79.8 % | **0** | 3 |
| sk_SK | 264,838 | 4.4 % | **0** | 4 (§9.4) |

**What the sweeps found, per language.** Every port's fixture-validated fork set was incomplete —
the Slovak lesson repeated three more times, at scale:

- **Czech** (2,678 uncovered at first sweep): the `FoldedCzechStemmer`'s four fixture-era switches
  grew to **ten positions**. Two new possessive forks (`in` ⟵ `-ín`/`-ína` loanwords — `balerína`;
  `uv`/`ov` ⟵ exposed stem tails — `docouvat`, `forróvý`), a `finalConsonantRewrite` fork (folding
  maps foreign `ć` onto the `c` the `c→k` rule reads — `ilićová`, `kopeć`), a `longMiEndings` fork
  (`imi`/`ymi` have no plain twin in the accented table, and `emi` also reads as `é+mi` —
  `surimi`, `veszprémi`), and a `caseEndings` gate producing the vowel-strip-only hypothesis for the
  whole family of long-vowel-sourced endings the accented stemmer handles by the final-vowel rule
  alone (`dojetí`, `bardáma`, `budižkničemu`). **On the Czech fixture every A-row number is
  unchanged** — including A20's 119/119, 348/348 and 54 false merges — because the fixture never
  carried these classes; what changed is the hypothesis-set definition and the fan-out (1.30 → 2.32
  average, 2 → 4 max). The Czech measurement record carries its own ledger entry for this.
- **Romanian** (2,889): one genuine **port bug** — the verb step aborted on a region/condition
  failure where Snowball's windowed matching falls through to shorter entries (`barați`: `arati`
  reaches below RV, `ati` fits) — plus deliberate backtracking divergences in the combo/standard
  steps (folding creates entry pairs like `icator`/`ator` whose accented sources were never both
  matchable, so a Snowball-faithful abort loses coverage the accented side had — `picător`), and
  **four step gates** (`combo`, `a_3`, `verb`, `step0`+`vowel`) whose off-positions reproduce the
  accented stemmer's vowel-strip-only path on `ţ`/`ş`/`ă`-stem words (`bogdăniţa`, `borşei`,
  `frecăţei`, `pârâul`).
- **Polish** (1,661): the `sza`/`szą` and `sze`/`szę` action conflicts the port had **dismissed as
  sub-R1-only turned out to bite** (`paszą` → accented `pas`, folded `pa`), and a whole
  nasal-homograph family emerged: folded `ie`/`acie`/`ecie`/`cie`/`ales` each read two or three ways
  (`arabię`/`ziemie`, `okęcie`/`kopcie`/`dziecię`, `-nięcie` deverbals, `-ąłeś` past forms). Three
  new positions (`nasalHomographs`, `szNasalActions`, `cieHomograph`) cover all readings.
- **All three also needed the surface hypothesis**: the accented stemmers are inert on whole
  loanword and name classes whose folded image matches a folded table entry (`album`, `almanach`,
  `akronym`; `-ău`/`-ăţ` toponyms), and the folded surface is the only term that can meet such an
  index term. Slovak alone needed neither gates nor the surface term — its sweep reached zero on
  the three rule forks (§9.4).

**Costs, measured.** The verified sets grew the fixture fan-outs from 1.09–1.30 to 1.95–2.32
average (max 4 everywhere; Slovak unchanged at 1.15/4) — the surface hypothesis adds one term
whenever stemming changed the token. On the fixtures the enlargement changed almost nothing else:
Czech A20 and Polish P20 rows are identical to their pre-sweep numbers, Romanian R20n gained two
convergence pairs, and no fixture false-merge count moved. The per-word fork rates over the full
lexicons (table above) put the real-world fan-out in context: 77–87 % of Czech/Romanian/Polish
words have at least two distinct hypotheses, against 4.4 % of Slovak ones.

**What this changes about the M7 verdicts: nothing in direction, much in confidence.** The
correctness invariant that was previously argued from table inspection and validated on ~40 lemmas
per language is now machine-checked over 980,763 dictionary headwords with zero exceptions, and
guarded by four tests that fail the moment a stemmer edit reopens a gap. The remaining honesty
bounds: headwords are not inflected forms (the sweep proves coverage, not recall), and the
dictionaries themselves are the usual Hunspell community lists, not e-commerce corpora.

### 9.9 Runtime cost of the flat-union M7 query chain — JMH-measured (2026-09-07)

The verified hypothesis sets raised an obvious cost question: the prototype `HypothesisStemFilter`
is a **flat union** — per token it copies the buffer, runs the stem and materializes a `String`
once per configuration, deduplicating afterwards into the 1–4 distinct terms that survive. For
Czech that is 1,025 runs per token (RO 513, PL 129, SK 8). `CzechAnalysisPipelineBenchmark`
(`evita_test/evita_performance_tests`, package `io.evitadb.index.fulltext.analysis`) measures the
worst case — the Czech full-hypothesis query chain against the production Czech chain
(`CzechAnalyzer` + fold, which is also the unchanged M7 *index* side) — through the production
`FulltextAnalyzer.analyze()` entry point, NFC boundary included. JDK 21, JMH avgt fork 1,
`-prof gc`; both chains reuse their per-thread stream components exactly as in production:

| input | metric | production chain | M7 flat union | ratio |
|---|---|---|---|---|
| 3-token bare query | time / analyze | 0.20 µs | **90 µs** | ~450× |
| 3-token bare query | allocation / analyze | 160 B | **146 KB** | ~930× |
| ~48-token description | time / analyze | 5.7 µs | **1,490 µs** | ~260× |
| ~48-token description | allocation / analyze | 2.4 KB | **2.4 MB** | ~1,000× |
| retained footprint (once per analyzer) | | 3.1 KB | 29.2 KB | — |

Readings, in decreasing order of consequence:

- **Query-side-only use survives in absolute terms.** ~30 µs and ~49 KB of short-lived Eden
  garbage per token, paid once per search request: at 1,000 fulltext qps that is ~9 % of one core
  and ~146 MB/s allocation. Noticeable, not disqualifying — GC time only tripled under JMH's
  saturated loop, far beyond real query rates.
- **The flat union is disqualified from anything index-side or symmetric.** 1.5 ms + 2.4 MB per
  50-word text would be paid per stored attribute value per entity. M7's asymmetry is what makes
  the prototype viable at all.
- **The retained cost is irrelevant** — the 1,025 stemmer configurations are built once per
  analyzer (29 KB) and shared by all threads; the churn is entirely per-token scratch.
- **~99 % of the per-token work is provably redundant** (1,025 runs to find ≤4 distinct terms;
  fixture fan-out averages 2.32). If the query-side numbers ever matter, the production form is
  the **branching stemmer** — one pass that forks only where an ambiguous rule actually fires.
  *Since built and measured — §9.10; the redundancy estimate was, if anything, conservative.*

One measurement trap worth recording: an analyzer's JOL footprint must be taken **before** its
first use — Lucene's `CloseableThreadLocal` keys stream components by `Thread`, so a graph walk
from a primed analyzer swallows the entire thread-locals closure (~10.8 MB for either chain) and
the comparison collapses. The benchmark's `main` documents this at the site.

### 9.10 The branching stemmer closes the gap — built, proven, JMH-measured (2026-09-07)

The remedy §9.9 named is now a prototype next to the flat one: `BranchingFoldedCzechStemmer` +
`BranchingHypothesisStemFilter` (functional-tests scope, same package). One walk over the folded
ending tables per token; at each guarded decision point whose pattern actually matches it takes
*both* branches instead of committing, and everything unambiguous runs once. Two structural
properties of the Czech port make the walk allocation-free and the equivalence exact: the case and
possessive stages never mutate the buffer (they only compute shorter lengths), and every `normalize`
rule rewrites at most the stem's **final two characters** and then returns — so every hypothesis is
fully described by a `(length, final-two-characters)` triple over the untouched token, deduplicated
by triple comparison and materialized straight into the term attribute. No strings, no set, no
`captureState`; steady-state allocation is zero.

**Equivalence is machine-checked, not argued.** `BranchingCzechStemmerEquivalenceTest` pins
set-equality against the flat 1,025-configuration union word by word over all 260,926 folded cs_CZ
headwords, over hand-picked ending-table boundary words, and per token position at the filter level;
the benchmark's census prints emitted-term parity through the production entry point (QUERY 7 = 7,
DESCRIPTION 121 = 121). The walk stays exact because no switch can decide twice on one word — every
guarded entry strips and returns when it fires, and the skip-branch's surviving suffix never matches
a second entry of the same family.

Same harness as §9.9 (JDK 21, JMH avgt fork 1, `-prof gc`, all three pipelines in one run — the flat
numbers re-measured within noise of §9.9's):

| input | metric | production chain | M7 flat union | M7 branching | branching vs flat | vs production |
|---|---|---|---|---|---|---|
| 3-token bare query | time / analyze | 0.19 µs | 87 µs | **0.32 µs** | ~270× less | 1.7× |
| 3-token bare query | allocation / analyze | 160 B | 149 KB | **352 B** | ~420× less | 2.2× |
| ~48-token description | time / analyze | 5.7 µs | 1,587 µs | **11.4 µs** | ~140× less | 2.0× |
| ~48-token description | allocation / analyze | 2.4 KB | 2.45 MB | **6.0 KB** | ~410× less | 2.5× |
| retained footprint (once per analyzer) | | 3.1 KB | 29.2 KB | **488 B** | | |

Readings:

- **The M7 query side is now effectively free.** Within 2× of the production chain on both inputs,
  and the residual time and allocation track the *output*, not the mechanism: the branching chain
  emits 2.3–2.5× more terms (7 vs 3; 121 vs 48), and the per-term `String` handed to the consumer
  is where the remaining bytes go. GC time under JMH's saturated loop is back at baseline (32 ms vs
  the flat union's 61 ms vs baseline's 29 ms). §9.9's "~9 % of one core at 1,000 qps" becomes
  ~0.03 % — the cost argument against M7 is gone entirely.
- **§9.9's index-side disqualification no longer binds the mechanism, only the flat prototype.**
  11.4 µs / 6 KB per 50-word value is index-plausible (2× the production chain, again mostly the
  larger term count). M7's asymmetry remains preferable for its own reasons — the index format and
  every indexed catalog stay untouched, hypothesis-set tuning needs no reindex — but runtime cost
  no longer forces the choice.
- **The trick is Czech-shaped, the argument is not.** The union-equals-branching-walk equivalence
  holds for any of the four ports, but the `(length, final-two-characters)` representation rests on
  the Czech port's tail-only mutations — a Romanian or Polish branching stemmer needs its own
  representation analysis. *Since done for all three — §9.11; the Romanian verb table even breaks
  the "no flag decides twice" property the Czech walk leans on, and needed the heavier machinery.*

The flat union keeps its role: it is the *specification* — trivially correct by construction, the
thing the sweeps verify and the equivalence test compares against — and the branching walk is the
*implementation*. Both stay in the tree; an edit to `FoldedCzechStemmer` that reopens a gap fails
the lexicon sweep, and one that breaks the walk's mirror fails the equivalence test.

### 9.11 Branching walks for Slovak, Polish and Romanian — built and lexicon-proven (2026-09-15)

Before any production change, the branching form now exists for **all four** languages, each proven
set-equivalent to its flat union word by word over its whole lexicon (980,763 headwords in total,
zero divergences: cs 260,926 / sk 264,838 / pl 279,452 / ro 175,547), plus per-language boundary
words chosen from the tables' guard edges and per-position filter output. One
`BranchingStemmer` interface now fronts all four walks and `BranchingHypothesisStemFilter` drives
any of them; the flat unions stay in the tree as the executable specifications, and the four
`Branching*StemmerEquivalenceTest`s chain each pair together. The three new walks are *not* copies
of the Czech one — each language demanded its own structural analysis, and the differences are the
finding:

- **Slovak** (`BranchingFoldedSlovakStemmer`) is the Czech shape with one wrinkle: `normalize`
  **chains** — the `ie`-shortening does not return but falls through into the epenthesis check, so
  one path can apply both (`stoliciek` → `stolicek` → `stolick`). The composition still rewrites
  only the result's final two characters, so the `(length, final-two-characters)` triples and the
  zero-allocation walk carry over. No surface hypothesis, matching the Slovak set (§9.4). Bound:
  8 outcomes.
- **Polish** (`BranchingFoldedPolishStemmer`) could not reuse the Czech shape at all: its switches
  select **which entries exist in the table** (and one entry's R1 condition), under
  longest-suffix-first, first-match-wins scanning — two configurations differing in one switch can
  fire entirely different entries. The exact walk is a **constraint scan**: one merged table whose
  entries carry `needOn`/`needOff` flag masks, scanned by *cells* of configuration space; a matched
  conditional entry partitions its cell into a firing sub-cell and continuation sub-cells. The
  three-way `sza`/`sze`/`e` readings become disjoint predicate variants of one suffix. Every action
  writes at most one character at the final position, so outcomes are `(length, final character)` —
  still zero-allocation.
- **Romanian** (`BranchingFoldedRomanianStemmer`) needed the heaviest walk, for two reasons. Its
  five step gates and buffer-rewriting actions (`icator`→`ic`, `ism`→`ist`, the combo repeat loop)
  make hypotheses full strings rather than prefix-plus-tail, so the walk is a **staged worklist** of
  pooled buffer copies — fork at every gate, deduplicate identical states immediately (a stage that
  matched nothing collapses its fork back to one state, which keeps ordinary words at one or two
  states). And its verb table is the one place in all four ports where **a flag genuinely can be
  consulted twice on one path** (`asesi` skipped → the shorter `sesi` matches, both
  `sVerbEndings`-gated), so the verb scan uses the Polish constraint-cell machinery — the
  commitment bookkeeping that is provably inert in the Czech walk is load-bearing here.

**Costs, JMH-measured** (`SkPlRoAnalysisPipelineBenchmark`, same harness as §9.9/§9.10 — JDK 21,
avgt fork 1, `-prof gc`, through `FulltextAnalyzer.analyze()`; per-language 3-token bare query,
chains in the matrix tests' minimal shape, so the Czech numbers of §9.10 are not directly
comparable row-for-row — its chains carry a stop filter):

| language (union size) | pipeline | time / analyze | allocation / analyze | retained footprint |
|---|---|---|---|---|
| Slovak (8) | flat union | 0.64 µs | 1.9 KB | 680 B |
| Slovak (8) | **branching** | **0.23 µs** | **160 B** | 504 B |
| Polish (129) | flat union | 48.5 µs | 20.0 KB | 295.7 KB |
| Polish (129) | **branching** | **0.58 µs** | **304 B** | 504 B |
| Romanian (513) | flat union | 234 µs | 75.5 KB | 897.2 KB |
| Romanian (513) | **branching** | **1.84 µs** | **352 B** | 504 B |

Emitted-term parity holds on every query (SK 3 = 3 — the Slovak benchmark words fork nowhere — PL
6 = 6, RO 7 = 7). Readings:

- **Branching pays at every union size, including Slovak's 8.** Even the cheapest flat union costs
  2.8× the walk's time and 12× its allocation; the Slovak walk's 160 B per query is exactly the
  three emitted term strings — the mechanism itself is allocation-free, as designed.
- **The flat unions' cost is not proportional to configuration count — per-configuration price
  dominates.** Romanian's 513 configurations cost 234 µs where Czech's 1,025 cost ~90 µs (§9.9):
  each Romanian run is a full multi-step Snowball pipeline with region marking, against Czech's
  single table walk. Polish (129 → 48.5 µs) tells the same story. The flat prototype is priced by
  *stemmer complexity × configurations*, the branching walk by stemmer complexity alone.
- **The Polish and Romanian flat unions retain real memory** — 296 KB and 897 KB per analyzer,
  because every configuration instance holds its own assembled copy of the ending tables (Czech's
  configurations keep their tables in code as `endsWith` calls and retain only 29 KB, §9.9). This
  is structural, not sloppy: a flat Polish instance *must* materialize the specific list its seven
  flags select, because all seven shape the table's content — 128 configurations, 128 distinct
  lists, nothing to share (Romanian could share — its verb table depends on only three of the nine
  switches, eight distinct tables behind 512 instances — but stays per-instance as the faithful
  mirror of its constructor, which is the flat port's whole job). The branching walks dissolve the
  problem instead of optimizing it: the switch dependence moves out of the table's *content* into
  per-entry `needOn`/`needOff` predicate masks, so each walk holds **one `static final` merged
  table** shared by every instance, and the measured 504 B per instance is purely the mutable
  per-stream scratch (outcome arrays, scan-cell stacks, token buffers) that cannot be shared by
  design — one instance per token stream, like any Lucene stemmer.
- **The Romanian walk's heavier machinery costs what it looks like**: 1.84 µs against the Czech
  walk's 0.32 µs — the pooled-buffer stage forks — yet still ~127× under its own flat union and
  well inside query-side noise.

One more JOL trap for the file: measuring a graph that contains Java **records** needs
`-Djol.magicFieldOffset=true`, or `Unsafe` refuses their field offsets and the census dies mid-walk
— it bit here because the Polish and Romanian ports keep their table entries in records where the
Czech port uses plain fields.

### 9.12 Graduated to production (2026-09-15)

Everything §9.6 listed as "test-scope only" is now shipped, and the `sk`/`pl`/`ro` index chains of
§9.6 were replaced. `BuiltInAnalyzers` holds eleven names instead of seven: each of `cs`, `sk`, `pl`
and `ro` gained a `<language>-search` twin, declared `AnalysisMode.SEARCH_TIME`, and
`nameForLocale(Locale)` became `assignmentForLocale(Locale)` returning an asymmetric
`(index, search, null)` assignment for those four and a uniform one for everything else. The mode is
the type-level guard the asymmetry needs: a schema assigning `czech-search` to the INDEX slot is
rejected at mutation time, so the fan-out can never be baked into an index.

What moved into `evita_engine`, renamed away from the prototype vocabulary — "hypothesis" is gone,
the fan-out is named for what it is, **stem variants**:

| prototype | production |
|---|---|
| `BranchingStemmer` (`hypothesize`) | `VariantStemmer` (`stem`) |
| `BranchingFolded{Czech,Slovak,Polish,Romanian}Stemmer` | `{Czech,Slovak,Polish,Romanian}VariantStemmer` |
| `BranchingHypothesisStemFilter` | `VariantStemFilter` |
| `SlovakStemmer`, `PolishSnowballStemmer` | unchanged names |
| nested class of the Romanian matrix test | `CommaBelowNormalizationFilter` |

Index chains now: `sk` = lowercase → `SlovakStemmer` → fold; `pl` = lowercase → stop → Snowball →
fold (Stempel dropped — no rule table to fork over, and §9.3 measured Snowball dominating it on every
recall metric; `PolishAnalyzer` stays referenced for its stop set alone, so the stempel jar stays);
`ro` = lowercase → `CommaBelowNormalizationFilter` → stop → Snowball → fold (the R0n shape). `cs` is
unchanged. Any catalog indexed with the §9.6 `sk`/`pl`/`ro` chains produces different terms now; no
migration was written, fulltext being pre-release.

Vendored-code attribution for `PolishSnowballStemmer` follows the `evita_roaring_bitmap` pattern: an
upstream-license paragraph in the file header (Apache-2.0 from Lucene, BSD-3-Clause from Snowball)
plus `evita_engine/NOTICE` naming the source commit and the two adaptations.

**Test disposition.** The decision instruments are gone — the four `*AnalysisApproachMatrixTest`s,
`AnalysisApproachMeasurer`, `CzechAccentTypingTest`, `LegacyWordWithNumberSplitFilter` with the
rejected-placement and side-by-side-report classes of `WordNumberSplitAnalysisTest`, both JMH
pipeline benchmarks, the `lucene-analysis-morfologik` test dependency and the
`evita_functional_tests` test-jar dependency of `evita_performance_tests`. Their conclusions are in
this record and in `p5-approach-measurements-accent-vs-stemming.md` /
`p5-word-number-split-comparison.md`; the instruments themselves are recoverable from git history.

What replaced them:

- `*VariantStemmerLexiconTest` ×4 (renamed from `*FoldedStemmerLexiconTest`) now sweep the **production**
  walk rather than the flat union — the same invariant, two orders of magnitude cheaper: 1.9–2.2 s per
  language where the flat sweeps took 14–26 s.
- `Branching*StemmerEquivalenceTest` ×4 keep the flat ports honest, unchanged in substance.
- `LanguageAnalyzerPairRecallTest` is new and pins, per language, what the pair buys: accent-typed
  recall **cs 119/119, sk 125/125, pl 62/62, ro 49/49**, bare-typed cross-form recall **cs 348/348,
  sk 351/355, pl 148/175, ro 99/120**, false merges **cs 54, sk 0, pl 24, ro 0**. The four fixtures
  survive as its vocabulary; their pending native review (§9.7) may legitimately move the sk/pl/ro
  numbers.

  The Polish **24** is not comparable to the **8** §9.7 records for the shipped `pl` wrapper, and it
  is not a precision cost of the Stempel → Snowball switch. Every one of the 24 sits inside three
  planted confusable pairs whose members fold to the *same string before any stemmer runs* —
  `łoś`/`los` and `skała`/`skala` (18, the stroked `ł`) and `pączek`/`paczka` (6, the nasal `ą`); the
  merged terms are identical folded surfaces. The count rose because the *query side* changed: it now
  emits the surface variant and every stem fork, so it reaches more of the same planted collisions
  than the old symmetric chain did. Slovak and Romanian reach 0 merges on the same measurement.
- `FulltextAnalyzerTest` gained per-language index-chain expectations and a `VariantChains` class
  pinning the fan-out shape (every variant at position increment 0, surface variant present for
  cs/pl/ro and absent for sk).
- `io.evitadb.spike.FulltextAnalysisChainBenchmark` replaces both deleted JMH benchmarks with the one
  piece that has a future consumer: the index-vs-search cost census per language, to re-check when a
  stemmer table or chain composition changes.

**Carried, not solved.** The query pipeline must OR the terms sharing a position; on this branch the
analyzers still have no engine consumer, so the integration on `258-fulltext-support` has to consume
variant terms as synonym-shaped alternatives before any of the recall above is what a user sees. The
note lives in `VariantStemFilter`'s javadoc, where the next reader of that code will hit it.
