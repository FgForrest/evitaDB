# Typo tolerance in the fulltext term dictionary

> Status: **interim result of the typo-tolerance analysis**, migrated into this record on 2026-10-02 from
> the working notes of branch `258-fulltext-support-typo-tolerance` (written 2026-09-24, split 2026-09-25,
> proof of concept 2026-09-25, JMH numbers 2026-09-30, follow-up questions 2026-10-05, threshold approach
> and measurements 2026-10-06, prefix rules 2026-10-08 and 2026-10-14, query types, precomputed prefixes and
> consistency 2026-10-15). Read [`typo-tolerance-prior-art.md`](typo-tolerance-prior-art.md) first — it defines the
> vocabulary, the three algorithm families and what each engine does; this document covers only what is
> specific to evitaDB's fulltext index. Facts are read from the local Lucene checkout and from evitaDB's code
> on the branch. The decisions taken so far are few and marked where they stand: prefix on the last word only,
> on by default for both query types (§7.8, 2026-10-08, kept 2026-10-15); a minimum fragment length of 3 and at
> most one typo per word while typing (§7.8, 2026-10-14; §11.4); pattern 1 for dropdown versus results-page
> consistency (§11.6, 2026-10-15); and, on 2026-10-20, the frozen first letter, the open-ended walk for a mistyped
> unfinished word, Damerau distance in the dictionary, surface forms in the dictionary and a cap of 50 terms
> per leg (§2.2, §7.1); on 2026-10-21, one edit from 4 letters and two from 8 in the submitted search (§7.1, §9).
> §10.7 repeats the measurements on a real corpus. On 2026-10-21 the review of the implementation specification
> decided the rest of §9 (see there), and §5.4.1 measured the intra-leaf seek as worth building. The decided
> algorithm alone, without the reasoning, is in
> [`typo-tolerance-fulltext-dictionary-algorithm.md`](typo-tolerance-fulltext-dictionary-algorithm.md).
> Everything else is proposal or measurement.
> **Amended 2026-10-30 (§13):** per-field analyzer profiles, which F1 builds before typo tolerance, give the
> surface-form lane a word-phase prefix and the profile's own fold; the cap of 50 counts per profile group and a
> summed-postings safety net bounds the whole query; `EXACTNESS` ranks above impact.
> The Damerau metric for fuzzy `contains` is decided in the sibling document
> [`typo-tolerance-fuzzy-contains.md`](typo-tolerance-fuzzy-contains.md).
>
> **Provenance of the parts.** The requirements (§2.2), the pipeline map (§3.2), the approaches not pursued
> (§5.5), the parameter list (§7.1, §7.4–§7.7) and the next steps (§9) are the surviving parts of the first,
> superseded analysis of 2026-09-24. The end-to-end flow (§3.3), the Lucene inventory (§4.2–§4.3), the
> diacritics analysis (§6), Lucene's length rules (§7.2) and Lucene's fuzzy scoring (§8.1–§8.2) answer four
> follow-up questions of 2026-10-05. Their sources are the `lucene-core` and `lucene-analysis-common` 9.12.3
> **sources jars** from the Maven repository (the version `pom.xml` pins; line numbers refer to them), the
> analysis package of `evita_engine` on this branch, and the two Czech chains **run** on sample input through
> the compiled classes (`BuiltInAnalyzers.definitionFor` → `FulltextAnalyzer#analyze`, a throwaway probe that
> is not committed). The `lucene-suggest` sources are not in the local repository; the facts about
> `FuzzySuggester` and `DirectSpellChecker` are the ones §4.1 cites. Each of those answers ends with a note on
> **what of Lucene's is usable here and what is not** — used as-is, portable as an algorithm only, or bound to
> Lucene's index and unusable — and §4.3 collects those notes class by class. The prefix rules (§7.8), the
> measurements of §10–§12 and the consistency survey (§11.6) were added between 2026-10-06 and 2026-10-15; the
> engine facts in §7.8, §11 and §12 are read from the local engine checkouts.

**Reading guide.**

| § | Topic |
|---|---|
| 1 | Summary — what is settled, proposed and open |
| 2 | What typo tolerance means here, and the requirements |
| 3 | Where it sits in the pipeline, with one query worked end to end, and what a surface-form lane changes in it (§3.4) |
| 4 | What Lucene provides, and what of it is usable |
| 5 | The mechanism: proof of concept, measurements, approaches not pursued |
| 6 | Diacritics in the query and the typo budget |
| 7 | The parameters: thresholds, the stemming fork, opt-outs, configuration |
| 8 | Relevance of a fuzzy hit: Lucene's scoring and what evitaDB builds instead |
| 9 | Next steps |
| 10 | Measurements: prefix vs exact, prefix × typo, thresholds, cap, behaviour examples (2026-10-06), other languages (§10.6), and all of it repeated on a real corpus (§10.7, 2026-10-20) |
| 11 | The submitted search and the as-you-type query: engines, budgets, measurement, rules per type, consistency, the engines' answers to the open questions |
| 12 | Precomputed short-prefix postings versus the query-time scan: read latency, size, write cost |

---

## 1. Summary

What is **settled**, what is **proposed** and what is **open**:

1. **Settled — the mechanism**, by the ecosystem, by what we already have, and by measurement (§5):
   `LevenshteinAutomata` from `lucene-core` walked against a sorted, seekable term dictionary. No new
   dependency, no new index structure, and the walk is inside the latency budget on a quarter-million-word
   vocabulary before any optimisation, with a wide margin.
2. **Settled — the placement** (§3.1): it is a matching-time term expansion, not an analyzer step.
   `BuiltInAnalyzers` already does the only part that concerns it — defining and applying the normalization
   space — and needs no change.
3. **Proposed — the parameter set** of §7.1, inherited from `p3-suggester.md` §4.3. Every parameter is a
   product decision with a known range (prior-art document §5, and §7 here); the engines disagree only on the
   numbers.
4. **Measured, ready to decide — the stem base with a surface-form lane** (§7.3, §10.3, §10.6, §10.7). On the
   real corpus, thresholds read from the stem on the stem dictionary, plus a walk of the surface forms with the
   typed length, find more mistyped words than the typed base (95.5–99.4 % against 83.8–97.5 %) at about half the
   noise, and they recover every typo a stem-only dictionary cannot reach (3.0–5.8 % on real text, 6.8–19.4 % on
   the four fixtures). The stem base is what every engine that stems uses (§11.7). The lane doubles the dictionary
   keys and adds no postings: +141.5 MB, +19.9 % on the 972,611-article corpus in the design of §3.4. The bands: one edit from 4
   letters (the measured knee), two from 8 in the submitted search (§10.7 g).
5. **Decided — rules per query type** (§7.8, §11.5, §11.6). Both query types match the last word as a prefix from
   three characters, on by default, with one switch. An as-you-type query allows at most one typo on every word;
   a submitted search keeps the full thresholds and runs its last word as the as-you-type query did, so the
   results page is always a superset of the dropdown preview (pattern 1 of §11.6).
6. **Measured, not decided — the as-you-type budget at three and four letters** (§11.3, §12, §10.7). On the real
   corpus the budget is met from five letters, and from four once the open-ended walk starts at five letters and
   the cap of 50 applies (p99 3.3 ms). Three letters stays at 8.6–14.8 ms p99 under every lever, because the cost is
   the thousands of terms a three-letter prefix reaches. Precomputed short-prefix postings meet it with a wide
   margin at about the base index's memory but 4–11 times its bytes rewritten per document (§12). Whether to
   revisit the README's rejection of index-time materialization for that is open.
7. **Decided 2026-10-21 — the intra-leaf seek is worth building** (§5.4, §5.4.1). On the real corpus and the
   production tree shape, 55 % of walk time is root-to-leaf descents, and 96 % of rejections land in the current
   leaf. The upper bound on the saving is 54 % of walk time, and 63–65 % for two-edit walks.

The behaviour we want is written as fourteen executable examples (§10.4), and every open question has a
measurement or a stated reason why it cannot have one yet. §9 lists them in order.

---

## 2. What typo tolerance means here, and what we expect from it

The first analysis of 2026-09-24 started from first principles and mapped every point onto what this record
had already decided or deferred; it was superseded as a *starting point* by the three documents of
2026-09-25, because its engine conventions read as requirements before any behavioural assignment existed.
Its requirements list, its pipeline map and its parameter inventory are still the only place those live, so
they are kept here (§2, §3.2, §5.5, §7). Sources, all in this record: `../research.md` §4.6 and O3;
`p1-index-core.md` K5, §5.2, §5.3; `p3-suggester.md` §4.2–§4.4; `p5-analyzers.md` §6, §7.3;
`schema-design.md` §2, §7.2; `query-design.md` §6, §10; `../lucene/engine-comparison.md` (Meilisearch,
Typesense sections).

### 2.1 Typo tolerance is a term-expansion strategy

Typo tolerance is one of three **expansion sources** for a query token; the other two are **prefix**
(search-as-you-type: the unfinished last word) and **hypothesis fan-out** (the folded-stemmer variants that
make accent-stripped Czech typing reach accented stems, P1 §2 / M7). All three produce a set of dictionary
terms per query token, and everything downstream (matching, ranking, suggesting, highlighting) consumes that
set the same way.

That framing settles the first general question: typo tolerance is **not a stage of the pipeline** with its
own structure. It is a query-time *term expansion strategy* over the existing term dictionary, and its output
is a set of `(term, editDistance)` pairs. No index structure is added or changed for it. The record fixed
this on 2026-08-12 (README decision table: "take linguistic analysis and the typo automaton from Lucene as a
dependency") and every prototype since builds on it; §3.1 and §5 confirm it by code.

### 2.2 Requirements

Collected from the research, the prototypes and the sponsor's hard requirements. Each item carries its
source and its status as of 2026-10-02: *decided* (a record chose and the choice shipped or is binding),
*proposed* (a record chose but nothing measured or shipped), *open* (nobody chose).

**User-visible behaviour**

- **U1 — one edit in an ordinary word still finds the product.** A single substituted, inserted or deleted
  character (`cerne` → `cerna`). Source: research §4.6. Status: decided.
- **U2 — an adjacent transposition costs one edit, not two.** `blakc` → `black` is the most frequent
  keyboard typo. Source: P3 §4.3 (Damerau, `DEFAULT_TRANSPOSITIONS = true`). Status: **decided** for fuzzy
  `contains` (sibling document §6.1) and, on 2026-10-20, for the dictionary as well — the two must not disagree,
  and every walk measured in §5 and §10–§12 already ran with transpositions on.
- **U3 — typing without diacritics is not a typo.** `cerna` must match `černá` at distance zero and keep the
  whole typo budget for real typos. Source: P5 §7.3, O3. Status: decided — `ASCIIFoldingFilter` after the
  stemmer, on by default for cs/sk, shipped in PR #1453. §6 shows it holds more broadly than asked.
- **U4 — short words are not typo-tolerant.** `bike` must not find `like`; a two-letter code must match
  exactly. Source: research §4.6, Algolia/Meilisearch convention. Status: proposed thresholds, §7.1.
- **U5 — a typo in the first letter is not tolerated.** The user knows how the word starts, and the rule
  prunes the search space to one subtree — measured in §5.2 as a 3× cost lever. Source: P3 §4.3;
  Meilisearch counts a first-letter typo double. Status: **decided** 2026-10-20 (`nonFuzzyPrefix = 1`).
- **U6 — a word with a typo and an unfinished end is still found while typing.** `blakc` on the way to
  `black…`. Source: P3 §4.2.3. Status: **decided** by the prefix rules — the unfinished last word gets the
  open-ended typo walk, at most one typo (§7.8, §11.5), and the submitted search keeps it (§11.6). P3's option 2,
  the union of a prefix and a whole-token typo expansion, was measured at 18 % recall and is not the mechanism
  (§10.2).
- **U7 — exact ranks above prefix, prefix above typo, fewer typos above more.** Deterministic and
  explainable. Source: research §4.3, query-design §6. Status: decided (TYPO and EXACTNESS lanes of the
  composite).
- **U8 — the user can see why a result matched.** The explain extra result exposes the typo penalty per
  token. Source: query-design §10. Status: decided shape, unbuilt.
- **U9 — suggestions are shown in surface form.** `černá`, never the folded stem `cern` the match ran in.
  Source: P3 §4.3, P5 §6 variant 3. Status: **decided** 2026-10-20 — the dictionary carries surface forms
  beside the terms (P5 §6 variant 3); §3.4 gives the design and its size.

**Product and operational**

- **O1 — thresholds are free to change.** Evaluated only over the query, never baked into stored tokens;
  changing them never triggers a reindex. Source: schema-design §2, §7.2. Status: decided — query-side or
  hot-swap configuration, never schema.
- **O2 — the suggester meets p99 ≤ 5 ms per keystroke including typo expansion**, the term leg alone
  ≤ 1.5 ms. Source: research §7, P3 §1.1. Status: criterion; the typo leg alone is measured in §5.3 at
  12–630 µs per query word on a quarter-million-word vocabulary, the whole-query budget is not.
- **O3 — a hard cap on expanded terms.** Typo expansion is a query that can generate unbounded work on its
  own. Source: research §4.6; opensearch background ("expensive queries need a budget"). Status: agreed;
  **50 terms per query word to start with** (2026-10-20), Lucene's `FuzzyQuery` default; the order it keeps is
  measured in §10.7.
- **O4 — never offer a completion that yields no results** under the user's must-match filter. Applies to
  typo-derived suggestions as much as to prefix ones. Source: query-design §10 (sponsor, 2026-08-14).
  Status: hard requirement.
- **O5 — same query, same data, same result on every replica.** No corpus statistics, no floating point.
  Source: research §4.3. Status: decided by the composite design.
- **O6 — there is no existing behaviour to preserve.** The current solution has no typo tolerance at all
  (P3 §4.3 verified it against all fourteen modules of the existing client), so anything shipped is a pure
  increment. Status: fact.

**Things explicitly not expected in the first round**

- Distance > 2. `LevenshteinAutomata` caps at 2 and no e-commerce engine offers more.
- Phonetic matching (Soundex/Metaphone). Not requested; no engine in the comparison uses it for e-commerce.
- Spelling *correction* ("did you mean") as a separate feature. The suggester's typo expansion covers the
  use case implicitly.
- Typo tolerance in the vector leg (F2). Embeddings are typo-robust by construction and out of scope.

---

## 3. Where it sits in the pipeline

### 3.1 Not in `BuiltInAnalyzers` — and it should not be

`BuiltInAnalyzers` (`evita_engine/src/main/java/io/evitadb/index/fulltext/analysis/`) builds Lucene
`Analyzer` chains and `FulltextAnalyzer` runs them: text in, a list of `AnalyzedTerm(term, surfaceForm,
offsets, positionIncrement)` out. An analyzer is a **normalizer**: the same chain runs over an attribute
value at index time and over the query string at query time, so both sides land on the same term. It has
no access to the dictionary and no notion of "another term that is close to this one".

Typo tolerance is the opposite kind of thing: it needs *one* query term and *the whole dictionary*, and it
runs after analysis, at matching time. There is no Lucene `TokenFilter` that "adds the typo variants of a
token", because such variants can only be enumerated against an index. (The token filters that look
similar — n-gram filters, phonetic filters — are *index-time* expansions that blow up the dictionary and
are a different trade-off entirely; see the prior-art document's §2.3 and its fourth family.)

So the analyzer pipeline's role is fixed and already correct for the job:

1. It defines **the space** the distance is measured in. On this branch the index chain is
   tokenize → lowercase → stopwords → stem → **fold diacritics** (`DiacriticsFoldingAnalyzerWrapper`),
   so the dictionary holds folded stems and `cerna` vs `černá` is distance 0 by construction.
2. It normalizes **the query word the same way** through the search-slot analyzer (`czech-search` etc.,
   `AnalysisMode.SEARCH_TIME`), so the automaton is built from a string in the dictionary's space.
3. It exposes the **surface form** alongside the term, which is what a suggester needs to show the user
   `černá` when the match ran on `cern`.

The typo component itself therefore sits between the analyzed query and the term dictionary, as a
**term-expansion step**: query term → automaton → walk the dictionary → set of `(term, distance)`. It has
no counterpart in `BuiltInAnalyzers` and needs none. What it needs from evitaDB is a sorted, seekable term
dictionary — which is what the P1 prototype (`p1-index-core.md`, K3/K5) builds — and `LevenshteinAutomata` from
`lucene-core`, which is already on the classpath.

One consequence worth stating for developers coming from Lucene: because our dictionary keys are `String`
and the automaton is built over code points (`alphaMax = Character.MAX_CODE_POINT`), the UTF-32 → UTF-8
conversion and the determinization Lucene does for its byte-keyed dictionary (`FuzzyAutomatonBuilder.java`,
`UTF32ToUTF8`) fall away. Less code, and `ě` → `e` costs one edit instead of two.

### 3.2 The pipeline map

The pipeline, index side and query side, with the typo-relevant contract at each step and the record that
owns the decision.

- **I1 — index analysis** (`evita_engine/…/index/fulltext/analysis`, `BuiltInAnalyzers`; owner P5 §7.3,
  shipped). Tokenize → lowercase → (capture surface form) → stopwords → stem → **fold**. The dictionary is
  therefore in **folded stem space**; distance is measured in that space, so diacritics cost nothing. The
  surface form is captured *before* folding if variant 3 of P5 §6 ships.
- **I2 — term dictionary** (planned, P1 K3/K5; owner P1 §3). Sorted `String` keys → postings bitmap and
  cardinality. Must support `cursor(K)` seek plus sequential `next()`, must be in natural `String` (code
  point) order, and cardinality must be readable without touching postings. §5.1 verified the order
  assumption on every word of the fixture vocabulary.
- **Q1 — query analysis** (`analysis` package, `czech-search` and siblings; owner P5 measurement record,
  P1 K5). Same chain with the search-slot analyzer; emits hypotheses per token (M7 fan-out, average 1.3,
  maximum 2–4). Each hypothesis is a separate string to expand; typo expansion runs **per hypothesis**,
  after fan-out.
- **Q2 — threshold decision** (query planner, fulltext translator; owner P3 §4.3 and §7 here). For every
  typed token decide `maxEdits ∈ {0, 1, 2}` and `nonFuzzyPrefix`. Computed from the typed token's folded
  length, once per token, before any expansion. Configurable and hot-swappable, never in schema.
- **Q3 — term expansion** (planned, P1 K5 / P3 §4.2; prototyped in §5). Per (hypothesis, maxEdits):
  prefix scan; Levenshtein DFA ∩ dictionary via the guided walk; cascade ed0 → ed1 → ed2. Output: a set of
  `(term, bestDistance, exactness)` per query token, capped at N terms. Naive enumeration is kept as the
  test oracle (it is what `FuzzyDictionaryWalkTest` compares against).
- **Q4 — matching and accumulation** (planned, P1 §5.3 [2026-09-02]). OR of the expansions' postings per
  token, AND across tokens (must-match). **The unit is the query token, not the expanded term**: a token
  counts once however many expansions hit, and its typo lane carries the *best* distance among the hits.
- **Q5 — ranking** (planned, P1 §5.3, query-design §6; lane order owned by P7). Lanes packed into one
  `long`: matched words → typos → impact → exactness → context. `TYPO` = "255 − weighted sum of typos";
  `EXACTNESS` orders exact > prefix > fuzzy. Lane order is a rank-profile choice, hot-swappable. *Amended
  2026-10-30 (§13.4): the default order puts exactness above impact.*
- **Q6 — suggester** (planned, P3). Term leg: prefix + typo expansion over the dictionary only, scored by a
  cascade; entity leg: OR of the top-M terms ∧ filter. Same expansion as Q3 but latency-capped per keystroke,
  and candidate terms must be displayable (surface form).
- **Q7 — highlighting** (planned, P4). Re-analyze the stored values of the returned page and mark the
  matched tokens. Must know *which expanded term* matched to mark a typo-matched surface word, so the
  expansion set of Q3 has to survive to this step.
- **Q8 — explain** (planned, query-design §10). Extra result with the score decomposed per feature; exposes
  the typo penalty and the matched variant per token.

The answer to "which part of the pipeline solves it" is therefore: **Q2 and Q3 produce it, Q4–Q8 consume
it, I1 decides the space it is measured in.** Nothing on the index side is *for* typo tolerance; the only
index-side dependency is that the dictionary is seekable and in the same normalized space as the query.

### 3.3 One query, end to end

The map of §3.2 drawn as a flow and worked on one query. The user typed `cerne kalhtoy` meaning
`černé kalhoty` — no diacritics, and `t`/`o` swapped in the second word. Stems and hypotheses are the real
output of the `czech` and `czech-search` chains (§6 has the full table); distances are restricted
Damerau–Levenshtein over code points, which §5.1 verified the automaton implements; the thresholds are the
proposal of §7.1. Q1 exists on this branch; everything from Q2 downwards is planned, Q3 is prototyped in §5.

```
 user types  "cerne kalhtoy"                                          intended: černé kalhoty
     │
     ▼
 Q1  query analysis    czech-search (SEARCH_TIME)                                        shipped
     NFC → tokenize → lowercase → stop words → fold diacritics → variant stems (all at one position)
       cerne    ──▶  hypotheses { cern, cerne }        ← identical for "černé", "cerne", "cerné"
       kalhtoy  ──▶  hypotheses { kalhto, kalhtoy }
     surface forms kept beside the terms (AnalyzedTerm.surfaceForm) for Q6–Q8
     │
     ▼
 Q2  threshold decision    per typed token, once, before any expansion          §7.1; open: §7.3
       cerne    : 5 folded chars → maxEdits 1, frozen prefix 1
       kalhtoy  : 7 folded chars → maxEdits 1, frozen prefix 1
       (a token containing a digit → maxEdits 0, proposed in §7.5)
     │
     ▼
 Q3  term expansion    per hypothesis, against the sorted dictionary of folded stems    §5 prototype
     exact lookup ed0 ─▶ [prefix scan, only for the unfinished last word] ─▶ DFA walk ed1 ─▶ [ed2]
     stop when the per-token cap is full; the distance of a hit is learned by the ed2 → ed1 → ed0 cascade
       cern     ──▶  cern@0
       cerne    ──▶  cern@1 (deletion), …              hypotheses merged per token, best distance kept:
       kalhto   ──▶  kalhot@1 (transposition)              token 1 → { cern@0 }      exact
       kalhtoy  ──▶  nothing within 1 edit (kalhot is 2)   token 2 → { kalhot@1 }    fuzzy(1)
     cost on a 241 494-key dictionary, warm: 12–28 µs per hypothesis at ed1 / prefix 1 (§5.3)
     │
     ▼
 Q4  matching    OR of the expansions' postings inside a token, AND across tokens (must-match)
       candidates = postings(cern) ∩ postings(kalhot) ∩ filter
     │
     ▼
 Q5  ranking    per candidate the lanes are accumulated, then packed into one long       P1 §5.3, P7
       matched words = 2 │ TYPO = 255 − (0 + 1·w₁) │ impact from the sidecar │ EXACTNESS = fuzzy(1) │ context
       top-N by heap; the token, not the expanded term, is the unit of every lane
     │
     ├──▶ Q6  suggester     same Q3 expansion, latency-capped per keystroke; shows "černé", never "cern"
     ├──▶ Q7  highlighting  needs to know kalhot ← kalhtoy to mark the typed word in the stored value
     └──▶ Q8  explain       per token: hypothesis, matched term, distance, exactness class
```

Three readings of the flow:

- **Typo tolerance occupies two boxes, Q2 and Q3, and owns no data.** Everything above them (Q1) decides the
  space the distance is measured in; everything below (Q4–Q8) consumes a set of `(term, distance, exactness)`
  per token and does not care how it was produced. That is the §2.1 framing drawn out.
- **The second word is found only because expansion runs per hypothesis and the token keeps its best hit.**
  The surface hypothesis `kalhtoy` is two edits from `kalhot` and out of budget; the stem hypothesis `kalhto`
  is one transposition away. Had the walk run on the typed word alone, or had the token been charged the
  *worst* of its hypotheses, the query would miss. The 2026-09-02 rule in P1 §5.3 is therefore a correctness
  rule for typo tolerance, not a scoring nicety.
- **Diacritics never reach the automaton.** Both hypothesis sets would be the same had the user typed
  `černé kalhtoy`; §6 shows this on more words.

### 3.4 The pipeline with the surface-form lane (proposal, 2026-10-21)

§10.7 measured a **surface-form lane** on the real corpus. It reaches every typo a stem-only dictionary cannot reach
(3.0–5.8 % of stem and suffix typos), and every unfinished keystroke no stem input reaches (2.1 %). This section
says what it changes in the pipeline of §3.2, so that the decision to build it can be taken with its cost in view.
**Nothing here is implemented**; the memory figures are JOL owned sizes of the harness structures over corpus A of
P1 (972,611 articles, 229 million tokens), `CorpusTypoMeasurementTest#shouldMeasureSurfaceLaneMemory`.

**The idea.** A second dictionary maps every word as written — lowercased and folded, `cerne` — to the stem the
index chain made of it, `cern`. It holds **no postings**: a hit in it is replaced by its stem, and the stem's
postings are read as before. Postings on the surface form would only tell *which* form of a word a document
contains, and no step of the query uses that; the search matches stems anyway, so `kabelkami` in a document is
found through `kabelk` together with every other form of the word. This is what separates the lane from the
variant P1 part 6.2 measured, where surface forms were full terms with their own postings (+717 MB, 2.33× the
dictionary).

**Index side.**

- **I1 — index analysis: no change to the chain.** The chain already hands each term's surface form to its
  consumer (`AnalyzedTermConsumer#accept`, the `surfaceForm` supplier); `FulltextIndex#analyze` currently ignores
  it. The consumer additionally lowercases and folds the surface form with the same folding the search chain applies
  (`ASCIIFoldingFilter`), giving the pair `cerne → cern`. Stop words never reach the consumer, so they never reach
  the lane. *Amended 2026-10-30 (§13.2): the fold is the one of the field's analyzer profile, not a fixed
  `ASCIIFoldingFilter`.*
- **I2 — a second tree beside the term dictionary.** Today `FulltextIndex` holds one
  `TransactionalBucketBPlusTree` per (collection, locale, scope), keyed by field prefix + stem, whose bucket holds
  the postings and the impact column. The lane is a second tree in the same index:
  - **Key: `folded␀stem␀accented`**, one key per distinct pair of folded form and stem. The folded part is what the
    walks search; the accented part is the form U9 shows (below). A folded form that stems two ways is two keys, so **every
    stem is kept**: dropping any would lose the documents of that stem. On the corpus 0.64 % of folded forms
    (10,296 of 1,601,674) have more than one stem, which makes 1,612,114 pairs. The dictionary has no term ids, so
    putting the stem into the key spares an id table, and the shared prefix of the keys is what the front-coded
    string column of the tree compresses.
  - **No field prefix.** The analyzer is chosen per (entity type, locale), so a surface form stems the same way in
    every field; one entry serves `title`, `perex` and `body`. A per-attribute analyzer seam (P1 measurements part 5)
    would break this and would need the field prefix back. *Superseded 2026-10-30 (§13.1): per-field analyzer
    profiles arrive in F1, and the key gains a word-phase prefix rather than a field prefix.*
  - **Value: a reference counter** — the number of (entity, field) values containing the pair. `addValue` increments
    the counter of every pair of the value, `removeValue` re-analyses the old value (it already does, to find the
    postings to remove) and decrements; at zero the key is removed. Without the counter the lane cannot tell when
    the last document with a form is gone, and stale keys would accumulate until a full reindex. They would be
    harmless for results — a stale key points to a stem whose postings are empty or still valid — but unbounded in
    memory, so the counter is the proposal.
  - **Transactional and persisted like the term dictionary**: the same bucket tree, versioned through its node
    layers, rolled back by the same per-entity savepoint, and stored as one more part of the index. That is a change
    of the persisted format (a new storage part and its Kryo serializer).

**Query side.**

- **Q1 — query analysis: no change.** The folded typed word is already available (`QueryWord.typed` in the
  prototype).
- **Q2 — thresholds** (decided 2026-10-21, §7.1): stem variants by the stem's length on the term dictionary, the
  typed word by its own length on the lane; 0 edits below 4 letters, 1 from 4, 2 from 8 (submitted search only).
- **Q3 — term expansion: the one real change.** Beside today's walks over the stems, a **typo walk of the typed
  word over the lane**, and for the unfinished last word a **prefix scan of the fragment over the lane**. The walk
  accepts `surface␀anything`, the same open-ended acceptance the as-you-type walk already uses, so a key is matched
  on its surface part only. Every hit yields its stem; the stems of both dictionaries are merged per query word, a
  stem reached both ways keeping the smaller distance, and a lane hit at distance 0 counts as exact. The per-leg cap
  of 50 (§7.1) applies after the merge.
- **Q4, Q5 — matching and ranking: no change.** They still receive `(stem, distance, exactness)` per query word and
  read postings from the term dictionary only.
- **Q6 — suggester:** completions can come from the lane; see *display* below.
- **Q7 — highlighting: no change.** It re-analyses the stored values and marks tokens whose stem matched.
- **Q8 — explain:** can additionally report that a word was reached through the surface form `x`.

**Memory**, corpus A, harness structures:

| structure | keys | heap | on top of stems + postings |
|---|---|---|---|
| term dictionary, stems (one int per key) | 804,858 | 52.3 MB | — |
| postings of the stems | 159.9 M ids | 659.0 MB | — |
| lane, one stem per folded form (§10.7 h) | 1,601,674 | 106.0 MB | +14.9 % |
| lane, `folded␀stem` keys, every stem, a counter per key | 1,612,114 | 120.5 MB | +16.9 % |
| **lane as proposed: the same with the accented form in the key (U9)** | **1,612,114** | **141.5 MB** | **+19.9 %** |

P1 part 2.4 measured the production structures of this corpus — dictionary, postings and impacts — at 0.70 GB, so the
proposed lane is about **+20 % of the fulltext index**; 21 MB of it is the accented form U9 requires. A catalog of short product names has fewer distinct word
forms per document; the share there is expected to be lower and is not measured.

**Display — decided by U9 (2026-10-20).** Suggestions show the word as written, `černé`, never the stem `cern`
nor the folded `cerne`; P5 §6 variant 3, which U9 adopts, captures the surface form before folding for exactly
this. The lane therefore carries the accented form: the most frequent accented form of each pair, appended to the
key when it differs from the folded one (816,891 of the 1,612,114 pairs). It costs 21 MB on this corpus and is
answered from memory, inside the keystroke budget. The lane is thus the structure that satisfies U9 as well as the
typo cases — one structure, not two.

**Open for the discussion:**

- whether the lane is built at all — the case for it is §10.7 (a), (f), the cost is the table above and the format
  change;
- the counter's exact unit (per (entity, field) value as proposed, or per entity), which decides how `removeValue`
  updates it;
- whether the lane needs a field prefix after all, if per-attribute analyzers arrive. *Answered 2026-10-30: a
  word-phase prefix, not a field prefix (§13.1).*

---

## 4. What Lucene provides

### 4.1 By module

evitaDB already depends on `lucene-core` and `lucene-analysis-common` 9.12.3 (`pom.xml:153`). It does
**not** depend on `lucene-suggest`.

In **`lucene-core`** (available today):

- `org.apache.lucene.util.automaton.LevenshteinAutomata` — the DFA construction. Index-free: it takes a
  `String` (or `int[]` code points), a flag for transpositions, and returns an `Automaton` for distance
  *n* via `toAutomaton(n)` or `toAutomaton(n, prefix)` where `prefix` is the frozen prefix. Hard cap
  `MAXIMUM_SUPPORTED_DISTANCE = 2` (`LevenshteinAutomata.java:37`). Marked `@lucene.experimental`.
- `Automaton`, `Operations`, `CompiledAutomaton`, `RunAutomaton` / `CharacterRunAutomaton` — generic
  automaton algebra and fast acceptors. Index-free.
- `FuzzyQuery`, `FuzzyTermsEnum`, `FuzzyAutomatonBuilder`, `AutomatonTermsEnum` — the query-side machinery
  that walks a **Lucene** terms dictionary. Tied to Lucene's `Terms` / `TermsEnum` index abstractions, so
  they do not run over our B+ tree as they are. What *is* portable is the algorithm: `AutomatonTermsEnum`
  is the "read while accepted, seek on rejection" walk of the prior-art document's §2.1, and
  `FuzzyTermsEnum` shows how to learn the exact edit distance of a hit for free (enumerate with the
  distance-2 automaton, then test the hit against the distance-1 and distance-0 automata as plain
  acceptors, `FuzzyTermsEnum.java:236-248`).
- Lucene's defaults for a fuzzy query (`FuzzyQuery.java:54-57`): max edits 2, frozen prefix 0,
  max expansions 50, transpositions on.

In **`lucene-suggest`** (not a dependency, would have to be added):

- `DirectSpellChecker` — "did you mean" over a live terms dictionary using the same automata; defaults:
  max edits 2, frozen prefix 1, minimum query length 4, accuracy 0.5, only for terms rarer than 1 % of
  documents (`DirectSpellChecker.java:61-97`).
- `StringDistance` implementations — `LevenshteinDistance`, `LuceneLevenshteinDistance` (with
  transpositions), `JaroWinklerDistance`, `NGramDistance`. Plain functions on two strings; trivially
  reimplementable if the dependency is unwanted.
- `FuzzySuggester` / `FuzzyCompletionQuery` — search-as-you-type over an FST with typo tolerance. Their
  defaults are the most conservative in the family: max edits **1**, no fuzziness below 3 characters,
  frozen prefix 1, transpositions on (`FuzzySuggester.java:82-91`).

### 4.2 By pipeline stage — what is Lucene's for free, and what is not

Only Q1 runs Lucene code, and all of it is already on the classpath and shipped: `StandardTokenizer`,
`LowerCaseFilter`, `StopFilter`, `CzechAnalyzer` with its `CzechStemmer` tables, `ASCIIFoldingFilter`, and the
`Analyzer#normalize` hook. The two classes that make the asymmetric query chain work — `VariantStemFilter` and
`CzechVariantStemmer` — are ours: Lucene has no "emit every stem the folded word could have had" filter, only
the one-stem `CzechStemFilter`, whose rule tables the variant stemmer re-walks. From Q2 down Lucene contributes
exactly one thing, `LevenshteinAutomata` with `Automaton` and `CharacterRunAutomaton` as its acceptors, used
as-is in Q3. Everything else Lucene has for these boxes is bound to its own index and does not transfer:

- **Q3** — `FuzzyQuery`, `FuzzyTermsEnum`, `AutomatonTermsEnum` and `Terms#intersect` walk a `TermsEnum` over
  `BytesRef` keys. The *walk* was ported as `LevenshteinDictionaryWalker` (§5.1); the `nextString`
  backtracking is the only non-trivial part of it. `CompiledAutomaton` and the UTF-8 conversion are not needed
  at all (§3.1, P3 §3.5).
- **Q4–Q5** — `BooleanQuery`, the scorers and `BM25Similarity` presuppose Lucene postings; the bitmap formula
  engine and the composite replace them, and O5 would forbid BM25 anyway.
- **Q6** — `FuzzySuggester` / `FuzzyCompletionQuery` live in `lucene-suggest`, need an FST copy of the
  dictionary, and were kept as an analogy only (P3 §3.7).
- **Q7** — the `lucene-highlighter` module is not a dependency and needs a Lucene `Query` to know what to mark;
  the re-analysis of stored values P4 plans needs only the `OffsetAttribute` that already feeds
  `AnalyzedTerm.startOffset` / `endOffset`, which is free.
- **Q2, Q8** — Lucene has nothing: no length rule in `lucene-core` (§7.2), and `Explanation` is tied to
  `Weight#explain`.

The honest count: one box entirely Lucene (Q1), one class reused as-is (Q3), one algorithm ported (Q3), and
the rest our own code — none of it large, and none of it something Lucene could have supplied over our
dictionary.

### 4.3 Inventory, class by class

The "what is usable" notes of §4.2, §6, §7.2 and §8.1 in one table. "Used as-is" means the class runs in
evitaDB today or in the §5 prototype; "portable" means the algorithm was or can be ported but the class cannot
be used; "not usable" means it is bound to a Lucene `IndexReader`, `TermsEnum` or `Query` and has no seam for
our dictionary.

| Lucene piece | Module | Status for evitaDB | Where |
|---|---|---|---|
| `StandardTokenizer`, `LowerCaseFilter`, `StopFilter`, `CzechAnalyzer` / `CzechStemmer`, `ASCIIFoldingFilter`, `GermanNormalizationFilter`, `Analyzer#normalize`, `OffsetAttribute` | `lucene-core`, `lucene-analysis-common` | **used as-is**, shipped | Q1, Q7; `BuiltInAnalyzers`, `FulltextAnalyzer` |
| `LevenshteinAutomata` (`toAutomaton(n, prefix)`, transpositions, cap 2), `Automaton`, `CharacterRunAutomaton` | `lucene-core` | **used as-is** in the prototype; `@lucene.experimental` | Q3; §5.1 |
| `AutomatonTermsEnum`'s `nextString` walk; `FuzzyTermsEnum`'s exact-distance cascade, cap-driven narrowing and lexicographic tie-break | `lucene-core` | **portable** — walk ported, the three rules to adopt; classes bound to `TermsEnum` / `BytesRef` | Q3; `LevenshteinDictionaryWalker`, §8.1 |
| `FuzzyQuery`, `FuzzyAutomatonBuilder`, `CompiledAutomaton`, `UTF32ToUTF8`, `Terms#intersect` / `IntersectTermsEnum` | `lucene-core` | **not usable, not needed** — Lucene index and byte keys | §3.1; P3 §3.5–3.6 |
| `TopTermsRewrite`, `BlendedTermQuery`, `BoostQuery`, `BM25Similarity`, `TermStates`, `BoostAttribute` | `lucene-core` | **not usable, not wanted** (O5) | §8.1 |
| `FuzzyQuery#floatToEdits` | `lucene-core` | callable, **wrong rule** | §7.2 |
| `BooleanQuery` and scorers, `Explanation` | `lucene-core` | **not usable** — presuppose Lucene postings / `Weight#explain` | Q4, Q5, Q8 |
| `FuzzySuggester` / `FuzzyCompletionQuery`, `DirectSpellChecker`, `StringDistance` | `lucene-suggest` | **not a dependency**; analogy and constants only, `StringDistance` trivially reimplementable (§4.1) | Q6; §4.1, P3 §3.7 |
| `ICUNormalizer2Filter` | `lucene-analysis-icu` | **not a dependency**; JDK `Normalizer` NFC instead | `FulltextAnalyzer` |
| `UnifiedHighlighter` and kin | `lucene-highlighter` | **not a dependency**; needs a Lucene `Query`, P4 re-analyzes with offsets instead | Q7 |

---

## 5. The mechanism: proof of concept and measurements

Both questions of §3.1 and §4.1 — does it plug into the analyzers, and can Lucene's automaton walk our
dictionary — were settled by code rather than argument. Two test classes live in
`evita_test/evita_functional_tests/src/test/java/io/evitadb/index/fulltext/typo/` (committed 2026-09-25 as
`f3d98af5`); the walker and its two companions — `CzechLexicon`, which loads the hunspell `cs_CZ.dic`
fixture in the folded or the NFD shape, and `EditDistances`, the reference Levenshtein / restricted
Damerau distances plus Sellers approximate substring matching, cross-checked against brute force — were
moved to `evita_test/evita_test_support/src/main/java/io/evitadb/test/fulltext/` on 2026-10-02
(`07d05df7`, together with the JMH benchmarks measured on 2026-09-30) so that the functional tests and the
JMH spikes drive the same code. Nothing in `evita_engine` was touched.

### 5.1 What was tested (2026-09-25)

**`LevenshteinAutomatonStandaloneTest`** (8 tests) — the automaton without any Lucene index. It uses only
`org.apache.lucene.util.automaton` from `lucene-core`, already a dependency of `evita_engine`, and checks:

- the automaton is deterministic and has no dead states (the two properties the guided walk relies on);
- distance 1 accepts one substitution, insertion, deletion **and** one adjacent transposition, and rejects
  two edits; distance 2 accepts two and rejects three;
- `toAutomaton(n, prefix)` freezes the prefix: no edit inside it, the prefix cannot be dropped;
- the exact distance of a hit is learned by cascading the narrower acceptors (distance 2 → test with 1 → 0),
  as `FuzzyTermsEnum` does;
- acceptance equals `osaDistance(query, candidate) <= maxEdits` on 36 000 random pairs against a textbook
  restricted Damerau–Levenshtein DP — the automaton implements exactly that distance;
- in code point space `černá` ↔ `cerna` is distance 2; in UTF-8 byte space it is beyond 2. Confirms the
  code-point alphabet (`alphaMax = Character.MAX_CODE_POINT`) is the right one for our `String` keys.

Two library facts worth knowing: `toAutomaton(3)` does **not** throw, it returns `null`, so the cap of 2 has
to be enforced by the caller; and `LevenshteinAutomata` is marked `@lucene.experimental`.

**`LevenshteinDictionaryWalker`** (`evita_test_support`, `io.evitadb.test.fulltext`) — a port of Lucene's
`AutomatonTermsEnum` walk over `TransactionalBucketBPlusTree<String>` through its public `cursor(K)` and
`next()`: read while the automaton accepts; on rejection compute the smallest string above the rejected key
that the automaton could still accept (`nextString`, backtracking over saved automaton states) and seek there.
Alphabet is code points, the language is finite so Lucene's visited-state bookkeeping is dropped, and the tree
is assumed to be in natural `String` order (true inside the BMP, asserted by the test on every word).

**`FuzzyDictionaryWalkTest`** (32 tests) — the walk over a real vocabulary: the hunspell `cs_CZ.dic` test
fixture, letters only, lowercased and diacritics-folded with `ASCIIFoldingFilter` to mirror this branch's
index chain — 241 494 distinct keys in a tree with leaf block 63. It checks:

- the guided walk returns **exactly** the set a linear scan with the same acceptor returns, for 14
  combinations of query × maxEdits × frozen prefix, including a transposed query (`bnuda`) and a query with
  no neighbours (`xqzv`);
- every hit carries the distance the reference DP computes;
- `bnuda` finds `bunda` at distance 1;
- a bounded sequential read-ahead (see §5.4) returns the identical hit list for limits 1, 8, 63 and 252;
- the cost of the walk, which is the number that matters.

### 5.2 Measured cost of the plain walk (JUnit, 2026-09-25)

On 241 494 keys, after JIT warm-up, one query word:

| query | maxEdits | frozen prefix | hits | keys read | seeks | time |
|---|---|---|---|---|---|---|
| kalhoty | 1 | 1 | 2 | 131 (0.05 %) | 129 | 0.3 ms |
| kalhoty | 2 | 1 | 7 | 1 509 (0.62 %) | 1 502 | 1.9–2.5 ms |
| cerny | 2 | 1 | — | 1 132 (0.47 %) | 1 030 | 1.5 ms |
| kalhoty | 2 | 0 | 7 | 4 240 (1.76 %) | 4 234 | 5.9–12.7 ms |
| bnuda | 2 | 0 | — | 2 675 (1.11 %) | 2 552 | 6.0 ms |

Hits for `kalhoty` at distance 2: `kalhoty@0, kalhotky@1, kalhotka@2, kalhotovy@2, kalhous@2, kalhov@2,
kalovy@2`.

Three readings:

- **The walk is guided, not a scan.** Under 2 % of the dictionary is touched even with two edits and no
  frozen prefix; with the first letter frozen and one edit it is 0.05 %.
- **The frozen first letter is a 3× cost lever**, not just a quality choice (4 240 → 1 509 reads at two
  edits).
- **Every configuration with a frozen first letter is inside the P3 budget** (5 ms per keystroke for both
  legs together) on a quarter-million-word dictionary, before any optimisation.

### 5.3 JMH numbers (2026-09-30)

The JUnit timings above are single cold-ish runs. `LevenshteinDictionaryWalkBenchmark` (JMH,
`evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/`) measures the same walker on the same
241 494-key tree, warmed; short run (1 warm-up, 2 × 1 s), single fork, average time per query word —
**indicative**, three significant digits without error bars, timings not better than ±20 %:

| query | maxEdits | frozen prefix | walk, seek per rejection | walk, read-ahead 63 | scan |
|---|---|---|---|---|---|
| kalhoty | 1 | 1 | 28 µs | 37 µs | 1.65 ms |
| cerny | 1 | 1 | 25 µs | 25 µs | 1.52 ms |
| bnuda | 1 | 1 | 12 µs | 13 µs | 1.43 ms |
| mikina | 1 | 1 | 21 µs | 22 µs | 1.57 ms |
| kalhoty | 2 | 1 | 629 µs | 295 µs | 1.89 ms |
| cerny | 2 | 1 | 212 µs | 187 µs | 2.0–4.1 ms |
| bnuda | 2 | 1 | 138 µs | 149 µs | 1.23 ms |
| mikina | 2 | 1 | 200 µs | 142 µs | 1.42 ms |
| kalhoty | 2 | 0 | 1.22 ms | 1.14 ms | 2.33 ms |
| mikina | 2 | 0 | 1.28 ms | 0.73 ms | 1.77 ms |

Warmed, the walk is **5–10× cheaper than the JUnit run suggested**: one edit with a frozen first letter
is 12–28 µs per query word, two edits 140–630 µs, and the scan settles at 1.1–2.3 ms. The budget question
is therefore closed with a wide margin on this vocabulary. Read-ahead stays mixed: up to 2× faster on the
seek-heaviest rows (`kalhoty` 2/1: 629 → 295 µs) and slightly slower on the light ones, which is the
same conclusion as §5.4 below with better numbers.

### 5.4 The seek-per-rejection question, and what a real optimisation entails

The walk issues a root-to-leaf seek on almost every rejection (4 234 seeks for 4 240 reads). Checking the
Lucene original showed this is **not** a shortcut of the port: `AutomatonTermsEnum`'s sequential `linear`
mode is entered only on a looping transition, i.e. for infinite languages (wildcards, regexes). For a finite
Levenshtein automaton Lucene returns `YES_AND_SEEK` / `NO_AND_SEEK` on every term and calls `seekCeil` each
time. Lucene is fast because `seekCeil` on its block-tree dictionary first tries the current block — the
"intra-leaf fast path" P3 §3.2 anticipated — not because it reads sequentially.

To find out how much such a fast path would catch **without touching the engine**, the walker got a
bounded read-ahead: after a rejection it advances the cursor with `next()` while the key is below the
target, at most one leaf block (63) times, and only then seeks. Correct by construction (every key between
the rejected one and the target is rejected), tested for equivalence at four limits. Measured against the
plain walk, same run:

| query | maxEdits | prefix | plain: reads / seeks / ms | read-ahead(63): reads / seeks / resolved in-leaf / ms |
|---|---|---|---|---|
| kalhoty | 1 | 1 | 131 / 129 / 0.32 | 3 873 / 38 / 91 (71 %) / 0.60 |
| kalhoty | 2 | 1 | 1 509 / 1 502 / 2.53 | 14 103 / 58 / 1 444 (96 %) / 2.92 |
| kalhoty | 2 | 0 | 4 240 / 4 234 / 5.92 | 78 482 / 631 / 3 602 (85 %) / 6.20 |
| cerny | 2 | 1 | 1 132 / 1 030 / 1.51 | 7 188 / 23 / 1 007 (98 %) / 0.88 |
| bnuda | 2 | 0 | 2 675 / 2 552 / 5.99 | 60 164 / 584 / 1 967 (77 %) / 3.86 |

Two conclusions, and they point in different directions:

- **Reach: 71–98 % of rejections resolve inside the current leaf.** An intra-leaf seek would remove most
  root descents. The reach argument for building it is settled.
- **Gain: none from the side-by-side version.** Reading a leaf linearly costs about as much as a root
  descent — each `next()` materialises a key from the front-coded column and compares a `String` — so the
  time is flat or worse. The gain only appears with a **binary search inside the leaf** (log instead of
  linear), and the leaf's key column is private to `ForwardBucketCursor`. The side version can measure the
  reach; it cannot deliver the gain.

**What the real optimisation entails** — a change to `TransactionalBucketBPlusTree`, not to the walker:

1. A new `BucketCursor#seek(K)` with the contract "advance to the first bucket ≥ K; K must be greater than
   the current value". `ForwardBucketCursor` already holds the current leaf's columns, `leafPeek`,
   `currentIndex` and the path with sibling arrays per level, so:
   - if K ≤ the leaf's last key: `findKeyPosition` (the binary search the cursor's own constructor uses)
     over `[currentIndex + 1, leafPeek]`, set `currentIndex`; no descent;
   - otherwise: climb the saved path to the lowest ancestor whose range covers K and descend from there
     (usually the next sibling leaf). For a first version a fresh root descent — today's `cursor(K)` — is an
     acceptable fallback.
2. `SingleLeafBucketCursor` and `ReverseBucketCursor` have to implement or explicitly reject the method;
   the interface is shared.
3. The cursor invariants (`exhausted`, `positioned`, and the "leftmost descent can end inside a node being
   unlinked from its parent" recovery the constructor documents) have to hold after a seek exactly as after
   a `next()`. This is where the real effort is: understanding an 8 000-line MVCC class, not writing the
   binary search.
4. Tests, two layers: tree-level in `evita_functional_tests/.../index/bPlusTree/` — seek within a leaf, on a
   leaf boundary, across several leaves, to an absent key, past the end, and to a key below the current
   position (must fail) — each cross-checked against a fresh `cursor(K)`; and the walker test extended with
   an in-leaf-vs-descent counter and a timing comparison against the plain walk.

**Decision deferred, on purpose.** The numbers above come from a hunspell lemma list, not from a term
dictionary of a production catalog, and the dictionary layout itself (stems only vs. stems plus surface
forms, P5 §6 / P1 K3) is not decided. Whether the fast path is worth its footprint in the tree is a question
to answer over the real dataset once P1's dictionary exists and the whole-query budget is measured. Until
then the read-ahead stays in the POC as the record of the reach, and the plain walk is the reference.

### 5.4.1 The intra-leaf seek on the real corpus — worth building (2026-10-21)

The deferral above waited for a real dictionary. §10.7's corpus A (972,611 articles, 804,858 stems, 1,601,674
folded surface forms) is that dictionary, so the reach and an upper bound on the gain were measured on it, together
with a fact §5.4 could not see: the earlier trees were not production-shaped.

**Setup.** `CorpusTypoMeasurementTest#shouldMeasureIntraLeafSeekReach`, slow-tagged, on the corpus of §10.7.

- **Walks.** These are the walks the decided algorithm runs (§7.1, §11.5), for 1,000 words of each cohort, every
  other word with one synthetic typo after the first letter: 26,825 walks with at least one edit.
  - A **finished word** walks every stem variant over the stems, budgeted by the variant's length. It walks the
    typed word over the lane by its typed length. The thresholds are 4 / 8. A word of eight or more letters is also
    walked capped at one edit, as while typing.
  - Every **fragment of four or more letters** walks the same dictionaries **open-ended**. From four letters it uses
    the submitted-search thresholds, and from five letters it is also capped at one edit.
  - Exact lookups (zero edits) are not walks and are left out.
- **Two tree shapes.**
  - The **harness** shape is the one of every measurement before this one: leaves of 63 keys in a boxed `String`
    column.
  - The **production** shape is the one `FulltextIndex` builds: leaves of 256 keys, its minimum sizes, and the
    front-coded string column (`ValueColumnFactory.forKey(String.class, null)`).

  Both shapes hold the same keys. The production copy carries no field prefix.
- **Three runs per walk:**
  - plain, which gives the walk time;
  - plain with every root-to-leaf descent timed (`LevenshteinDictionaryWalker#timingSeeks`), which gives the share
    of walk time spent descending;
  - with a read-ahead of one leaf (63 or 256 keys), which gives how many rejections land in the current leaf, and
    what reading the leaf sequentially costs.

  The read-ahead run returned exactly the plain run's hits for every walk (asserted).
- **The upper bound** assumes every rejection resolved inside the leaf is a descent saved at no cost: the number of
  in-leaf rejections × the mean descent time ÷ the plain walk time. The binary search inside the leaf is not free,
  so the bound is optimistic on purpose. Single-threaded JUnit wall-clock after a warm-up pass over every walk. The
  run took 505 s, 179 s of it analysing the corpus.

```shell
mvn -nsu -pl evita_test/evita_functional_tests test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='CorpusTypoMeasurementTest#shouldMeasureIntraLeafSeekReach' -Dsurefire.maxHeapSize=14g \
  -Dfulltext.corpus.file=<flat file>
```

**Results, production shape:**

| dictionary | walk | edits | walks | seeks per walk | descent | descents of walk time | rejections in the leaf | upper bound: walk time saved | incl. automaton build | read-ahead / plain | walk p50 / p99, plain |
|---|---|---|---|---|---|---|---|---|---|---|---|
| stems | finite | 1 | 4,121 | 194 | 0.64 µs | 63.3 % | 86.6 % | 52.6 % | 39.7 % | 8.21× | 0.20 / 0.33 ms |
| stems | finite | 2 | 1,629 | 3,245 | 0.64 µs | 62.0 % | 99.3 % | 64.9 % | 46.7 % | 1.76× | 3.17 / 4.88 ms |
| stems | open-ended | 1 | 8,153 | 179 | 0.65 µs | 36.3 % | 85.1 % | 30.2 % | 25.9 % | 5.59× | 0.23 / 2.09 ms |
| stems | open-ended | 2 | 1,784 | 3,293 | 0.64 µs | 60.9 % | 99.3 % | 63.4 % | 46.7 % | 1.75× | 3.31 / 5.13 ms |
| lane | finite | 1 | 1,903 | 201 | 0.69 µs | 64.5 % | 81.5 % | 49.0 % | 37.9 % | 9.11× | 0.23 / 0.37 ms |
| lane | finite | 2 | 886 | 3,432 | 0.68 µs | 63.2 % | 97.9 % | 64.7 % | 47.8 % | 2.65× | 3.54 / 5.63 ms |
| lane | open-ended | 1 | 6,837 | 181 | 0.70 µs | 24.3 % | 79.0 % | 18.9 % | 17.3 % | 4.57× | 0.28 / 4.78 ms |
| lane | open-ended | 2 | 1,512 | 3,495 | 0.68 µs | 61.9 % | 97.9 % | 63.4 % | 48.2 % | 2.66× | 3.72 / 5.90 ms |
| **all** | | | **26,825** | **871** | **0.66 µs** | **54.8 %** | **96.1 %** | **54.2 %** | **41.7 %** | **3.10×** | 0.26 / 4.82 ms |

**The same walks, harness shape** (totals only): a descent costs 0.39 µs, and descents are 51.8 % of walk time.
90.2 % of rejections land in the current leaf, and the upper bound is 42.0 % of walk time (29.6 % with the
automaton build). Read-ahead is 0.93× plain. A walk takes 0.19 ms at p50 and 3.92 ms at p99.

**Readings.**

- **Descents are half the walk on the real dictionary.** 55 % of walk time goes into root-to-leaf descents, and
  96 % of the rejections that cause them have their target in the leaf the cursor is already on. The reach of §5.4
  (71–98 % on the hunspell lemma list) holds and is higher. With leaves of 256 keys it is 79–99 %.
- **The upper bound is large where the cost is.** Two-edit walks are the expensive band of §9 item 5 and §10.7 (i):
  3.2–3.7 ms at p50 per walk. 98–99 % of their rejections stay in the leaf, and up to 63–65 % of their time is
  descents an intra-leaf seek would avoid. For one-edit finite walks the bound is 49–53 %. It is lowest for
  open-ended one-edit walks (19–30 %), whose p99 tail is spent reading the keys of whole subtrees, not seeking.
- **The production shape gains more than the harness suggested.** A descent through the front-coded tree costs
  0.64–0.70 µs, against 0.33–0.44 µs through boxed keys, because each level decodes keys to compare them. Bigger
  leaves keep more targets inside one leaf. The bound is 54 % of walk time against 42 % on the harness shape, so
  the §5.4 numbers understated it.
- **Reading the leaf sequentially is not the optimisation.** On the production shape the read-ahead makes walks
  3.1× slower: every `next()` decodes a front-coded key, and a 256-key leaf is long. That confirms the second
  conclusion of §5.4 more strongly than the harness could. The gain exists only with a **binary search inside the
  leaf**, which needs the engine change §5.4 describes.
- **What the bound is not.** The in-leaf binary search decodes a few keys of the front-coded block, and the real
  saving is below the bound by that cost. Walks are also only part of the expansion: the prefix scans, the postings
  union and the ranking are untouched. The bound is an argument to build, not a forecast. Only the engine change
  can measure the real number.

**Decision (2026-10-21): worth building, proposed and not scheduled.** `BucketCursor#seek(K)` with a binary search
over the current leaf, as §5.4 specifies, falling back to a descent when the target lies beyond the leaf. It changes
`TransactionalBucketBPlusTree` and is not part of the current line of work. Its verification compares it against
this bound on the same corpus and the same walks.

### 5.5 Approaches not pursued

Every approach below was measured, adopted or rejected somewhere in this record; the README's *Rejected
outright* table carries the first two with their revisit conditions. Listed here so the fork is visible in
one place next to the approach that won (§5.1–§5.4).

- **Trie walk with a dynamic-programming Levenshtein table** (Typesense). Asymptotically the same pruning as
  the automaton walk with the cost distributed differently. Rejected because our dictionary is a paged
  B+ tree of front-coded keys, not a character trie, and the DP walk needs per-character edges; P3 §3.6
  rejected the equivalent (variant T3, simultaneous descent à la `IntersectTermsEnum`) for exactly this
  reason — the tree does not expose label boundaries. Fallback only if measurement shows seeks dominant
  after the intra-leaf fast path of §5.4.
- **Index-time materialization of the neighbourhood** (SymSpell-style deletion indexes, Meilisearch's
  materialized prefix databases). Rejected because it is write amplification for a read-time feature — P8
  measured what materialized derived postings cost on the write path (a touched key carries 30.7× the bytes
  of an average key) — and because the configuration rule (schema-design §2) says typo parameters must be
  free to change; anything baked into the index locks them. Meilisearch pays this only for prefixes, and
  only because it has no seekable dictionary walk.
- **The trigram index as a candidate generator for dictionary terms.** Not pursued for the *term
  dictionary*: it answers a different question (substring containment over raw attribute values), it is
  opt-in per attribute, and it produces a candidate superset that still has to be verified by distance,
  where the automaton walk produces the exact set in one pass. This is **not** contradicted by the sibling
  document, which measures the trigram index for fuzzy *`attributeContains`* over raw values — the question
  the trigram index was built for — and finds it worth building there.
- **Naive enumeration** — every key of the field's range run through the automaton as an acceptor. Linear
  in dictionary size (1.1–2.3 ms on 241 494 keys, §5.3), unusable at 400–500 k keys within the per-keystroke
  budget — but it is the reference implementation the guided walk is tested against. Kept as the test oracle.

---

## 6. Diacritics in the query — missing, present or wrong — and the typo budget

**What Lucene does.** `FuzzyQuery` knows nothing about diacritics; it measures the distance over whatever the
analyzer emitted. Three facts bound the behaviour:

- The automaton is built over **code points** (`FuzzyAutomatonBuilder.java:50-55`,
  `alphaMax = Character.MAX_CODE_POINT`) and only then converted to a byte automaton for the UTF-8 dictionary
  (`CompiledAutomaton.java:236`, `UTF32ToUTF8`), so `č` → `c` is one edit in `FuzzyQuery` even though it is two
  bytes. The byte-space trap of P3 §4.2.2 is real only in `FuzzySuggester` (`alphaMax = 255`, `unicodeAware`
  off by default).
- Without a folding filter in the analyzer, a missing diacritic **is** a typo: `cerna` is 2 edits from `černá`
  (P5 §7.3), the whole budget, and a longer word with three accents is unreachable. Elasticsearch's documented
  advice is therefore an `asciifolding` filter on both sides — the arrangement this branch ships.
- Lucene does not normalize Unicode unless an `ICUNormalizer2Filter` is in the chain, and `ASCIIFoldingFilter`
  folds **precomposed** characters only — its table has no case for combining marks (nothing from the `U+0300`
  block appears in it). NFD input (`c` + combining caron) is therefore neither folded nor stemmed, and `černá`
  in NFC against `černá` in NFD is 4 edits even in code-point space. That is the trap
  `FulltextAnalyzer#analyze` closes by normalizing to NFC first (`FulltextAnalyzer.java:159-161`; the javadoc
  at `:134-150` says why the line must stay).

**What this branch does — run, not read.** The two Czech chains on words with and without diacritics and with
typos in different places:

| typed | `czech` (index chain: stem, then fold) | `czech-search` (query chain: fold, then every possible stem) |
|---|---|---|
| `černé` | `cern` | `{ cern, cerne }` |
| `cerne` | `cern` | `{ cern, cerne }` |
| `černá` | `cern` | `{ cern, cerna }` |
| `cerna` | `cern` | `{ cern, cerna }` |
| `kalhoty` | `kalhot` | `{ kalhot, kalhoty }` |
| `kalhtoy` (transposition) | `kalhto` | `{ kalhto, kalhtoy }` |
| `pánských` | `pansk` | `{ panskych, pansk }` |
| `pansek` (typo in the suffix) | `pansk` | `{ pansk, pansek }` |
| `pansych` (letter dropped) | `pansych` | `{ pansych, pans }` |
| `pánksých` (typo in the stem) | `panks` | `{ panksych, panks }` |

The index column is what the dictionary holds when the word occurs in an attribute. The `pansych` row shows the
index chain receiving unaccented text and not stemming it — the symmetric arrangement the chain asymmetry exists
to avoid; not a typo-tolerance matter, but visible here.

Readings, in the order they matter:

1. **Diacritics are handled before typo tolerance begins, and the automaton never sees one.** The query chain
   folds before it stems, so `černé`, `cerne` and a wrongly accented `cerné` produce the *same* hypothesis set,
   each at distance 0 from the indexed `cern`. U3 ("typing without diacritics is not a typo") holds by
   construction, and more broadly than the requirement asks: **wrong or extra diacritics are free as well**,
   where Lucene without folding would charge one edit each. The entire `maxEdits` budget stays for real typos.
2. **Where diacritics still cost something is the fan-out, not the distance.** Because the query arrives
   folded, the variant stemmer cannot know which accented word was meant and emits every stem consistent with
   *some* restoration — two hypotheses per token in every row above, the stem and the surface form. Typo
   expansion runs per hypothesis (Q3), so the §7.4 multiplication is the price of diacritic freedom. It is paid
   in microseconds (§5.3), not in recall.
3. **Typos and stemming interact exactly as §7.3 predicts, and the table puts cases on it.** A suffix typo is
   absorbed for free (`pansek` → `pansk`, distance 0). A typo in the stem survives at its true distance
   (`panks` ↔ `pansk`, one transposition). A dropped letter can change the rule path: `pansych` stems to
   `pans`, one deletion from `pansk`, while its surface form is three edits away — only the stem hypothesis
   finds it. Same for `kalhtoy`: `kalhto` is one transposition from `kalhot`, the surface form is two. The
   per-token best-distance rule (§3.3, second reading) is what lets the accent-free query chain and typo
   tolerance coexist; drop either half and recall goes with it.
4. **The exactness signal for diacritics is gone, by design.** `černá` and `cerna` are indistinguishable once
   folded, so an accented exact match cannot rank above a bare one and the `EXACTNESS` lane cannot see the
   difference (`DiacriticsFoldingAnalyzerWrapper` javadoc; P5 §7.3). The surface form is captured before
   folding (`AnalyzedTerm.surfaceForm`) precisely so that variant 3 of P5 §6 — a surface-form lane in the
   dictionary — can restore it later if wanted; nothing else can.
5. **Folding can change the length, which the §7.3 question has to be read with.** `ASCIIFoldingFilter` maps
   `ß` → `ss`, `œ` → `oe`, `æ` → `ae` (`ASCIIFoldingFilter.java:1251-1254` for `ß`), so "length of the typed
   token" in §7.1 must mean the length of the **folded** token in code points — which is what Q1 emits anyway.
   For Czech, Slovak, Polish and Romanian every fold is one-to-one (`ě` → `e`, `ł` → `l`, `ț` → `t`,
   `đ` → `d`), and German has no folding wrapper at all (`GermanNormalizationFilter` owns `ß`), so no supported
   language is affected; it is a rule to state, not a problem to solve.
6. **Highlighting and suggestions still show diacritics.** The match ran on `cern`, the user is shown `černá`:
   `AnalyzedTerm.surfaceForm` is a slice of the NFC-normalized input taken before folding, and Q7/Q8 need the
   expansion set to say *which* dictionary term the typed word reached (§3.2).

The short answer to the question as asked: the presence or absence of diacritics in the query is **not handled
by typo tolerance at all**. The query chain removes it one step earlier, the typo budget is untouched by it, and
the one thing lost — ranking an accented exact hit above a bare one — is a dictionary-layout decision (P5 §6
variant 3), not a threshold.

**What of this is usable here.** The folding is entirely Lucene's and already shipped: `ASCIIFoldingFilter` on
both sides (`DiacriticsFoldingAnalyzerWrapper` for the Czech index chain, inline in the other chains),
`GermanNormalizationFilter` through `GermanAnalyzer`, and the stemmer tables the folding has to run *after*.
`ASCIIFoldingFilter(in, preserveOriginal = true)` would additionally emit the accented token at the same
position for free — but it buys nothing while the dictionary holds folded stems only, so it is the query-side
half of variant 3 (P5 §6), not a substitute for it. `LevenshteinAutomata` in code-point space is free and is
the part of Lucene that makes a diacritic one edit rather than two; `UTF32ToUTF8` and `CompiledAutomaton`,
which Lucene needs on top for its byte dictionary, are not needed and deliberately not used (§3.1, P3 §3.5).
`ICUNormalizer2Filter` lives in `lucene-analysis-icu`, not a dependency; the JDK's `java.text.Normalizer`
does the NFC step instead (`FulltextAnalyzer.java:161`). Nothing in `FuzzyQuery` deals with diacritics at all,
so on the matching side there was nothing to take and nothing is missed.

---

## 7. The parameters

**How the parameters are applied — proposed approach (2026-10-06, detail in §7.6–§7.7).** Thresholds are not a
Lucene feature to configure; `lucene-core` has none (§7.2). They are a small query-side function of our own,
evaluated once per typed token before expansion. It takes the folded token's length in code points and returns
`maxEdits` (0, 1 or 2) and the frozen prefix, clamped to the token's length. Those two values go straight into
`LevenshteinAutomata#toAutomaton(n, prefix)` and the walker. The function reads one immutable parameter record:
minimum length for one typo, minimum length for two typos, frozen prefix, transpositions, cap, the digit rule,
disabled attributes. That record is configured in three layers: a built-in default in code, the rank or query
profile, and a per-query override. A higher layer replaces an item, never merges it. Nothing is stored in the
index or the schema, so every value below can change without a reindex (O1). The only part not yet decided is
which length the function reads (§7.3). That changes the function's input, not its shape.

### 7.1 The current proposal, and what is genuinely open

P3 §4.3 proposes an answer to the research's question O3. Restated here with the pieces that are still to be
challenged, status as of 2026-10-02:

- **Distance cap: 2.** `LevenshteinAutomata.MAXIMUM_SUPPORTED_DISTANCE`; §5.1 adds that `toAutomaton(3)`
  returns `null` rather than throwing, so the cap is the caller's to enforce. Not open.
- **Length 1–3 → 0 edits.** `FuzzySuggester.DEFAULT_MIN_FUZZY_LENGTH = 3`. **Decided 2026-10-21**, with the two
  bands below. **Measured on the real corpus (§10.7 g):** one edit from 4 letters is the knee — from 3 it buys 1.3 points for 10 % more noise, from 5 it
  loses 2.9.
- **Length 4–7 → 1 edit.** Algolia `minWordSizefor1Typo = 4`, Meilisearch `oneTypo = 5`. As above.
- **Length ≥ 8 → 2 edits.** Algolia `minWordSizefor2Typos = 8`, Meilisearch `twoTypos = 9`. **Measured (§10.7 g,
  i):** the second edit matters only for two typos in one word (17 % → 94.5 % found); each letter it starts
  earlier gains 8–12 points there and costs 16–81 % more noise on correctly typed words. No knee — 8 is the
  convention and the middle; only the real frequency of double typos could move it. Submitted search only; the
  as-you-type query never uses it (§11.5). **Decided 2026-10-21**; its cost is in §9 item 5.
- **Which length the bands read — measured (§7.3, §10.7 a):** the **stem's** length on the stem dictionary and
  the **typed** length on the surface-form lane. P3 §4.3 proposed the typed length for both; on the real corpus
  the combination finds more mistyped words than that, at about half the noise.
- **Non-editable prefix: 1 character.** `FuzzySuggester.DEFAULT_NON_FUZZY_PREFIX = 1`; Meilisearch's
  first-letter penalty; a 3× cost lever by §5.2. Not open.
- **Transpositions: on (Damerau).** `FuzzySuggester.DEFAULT_TRANSPOSITIONS = true`; decided for fuzzy
  `contains` on 2026-10-02 and for the dictionary on 2026-10-20. Not open.
- **Prefix × typo for search-as-you-type: the open-ended typo walk.** P3 §4.2.3 proposed option 2, the union of
  two independent expansions; measured on 2026-10-06 (§10.2) it finds 18 % of mistyped targets while three or more
  letters are missing, where an open-ended typo walk finds 93–95 % at every keystroke. **Decided** with the prefix
  rules (§7.8, §11.5): the open-ended walk, at most one typo. Not open.
- **Cascade ed0 → ed1 → ed2, stop when the candidate budget is full.** P3 §4.2.4. Not open.
- **Maximum expanded terms: 50 per leg of each query word** (decided 2026-10-20). The value is Lucene's
  `FuzzyQuery` default; the split is Meilisearch's, which caps each kind of derivation separately. The rules:
  - **every exact term is kept**, uncapped — a word has at most a handful of stem variants;
  - **the prefix leg keeps 50 terms**, the most frequent ones (by document frequency);
  - **the typo leg keeps 50 terms**, nearest first (by distance), then the most frequent;
  - the cap counts the **query word** — the union over its stem variants — not each variant, because what it
    protects is the postings union, and that is per word;
  - nothing flags a result whose expansion was cut, as in every engine (§11.7).

  Why split: one cap of 50 over all legs ranks every prefix completion above every typo match, and on the
  unfinished last word the completions crowd the typo match out — the submitted search then loses 3.3 points of
  recall on mistyped words; split, it loses 0.3. Why this order: nearest-then-most-frequent cut a found target
  once in about 4,400 finished words; alphabetical order cut it 12–44 times as often. On the real corpus the typo
  leg's cap binds on 2.5–5.3 % of mistyped words (§10.7 c).
- **Typo lane weights: "weighted sum of typos".** Research §4.3. **Open** — what the weights are (ed1 = 1,
  ed2 = 2? a first-letter typo = 2?). §8.2 and §8.3 discuss the options.

### 7.2 How the Lucene family handles the threshold by word length

**`lucene-core` has no length rule.** `FuzzyQuery` takes a fixed `maxEdits` (default 2, `FuzzyQuery.java:54`), a
fixed frozen prefix (0, `:55`) and a cap (50, `:56`); the only short-circuit is `maxEdits == 0`, which becomes
an exact lookup (`:205-207`). Whoever builds the query decides the distance, and in Lucene proper that is the
application. The length of the query word nevertheless enters in four places, none of which is a threshold:

| Where | What depends on the length | Lines |
|---|---|---|
| the boost | `1 − ed / min(len(hit), len(query))` — on a short word every fuzzy hit is cheap | `FuzzyTermsEnum.java:251-255` |
| the dynamic narrowing | the ed-2 band is abandoned once 50 hits beat `1 − 2 / len(query)`; for a 3-letter word that is any one-edit hit (0.67 > 0.33) | `FuzzyTermsEnum.java:202-207` |
| the frozen prefix | clamped to the word's length (`min(prefixLength, len)`); a word shorter than the prefix gets an empty fuzzy suffix, i.e. "this prefix plus up to `maxEdits` further characters" | `FuzzyAutomatonBuilder.java:52-56` |
| the legacy conversion | `floatToEdits(sim, len) = min(⌊(1 − sim) · len⌋, 2)` — what remains of Lucene 3's `minimumSimilarity`, kept for the fractional `~0.8` syntax of the classic query parser | `FuzzyQuery.java:261-270` |

The last row is the historical length rule and is worth reading as one: with `sim = 0.5` a word of 2–3
characters gets 1 edit and a word of 4 or more gets 2; with `sim = 0.8` it is 0 edits up to 4 characters, 1 from
5 to 9, 2 from 10. Lucene 4 replaced it with explicit edits; the function survives for the parser. (The
query-parser side is not re-verified here — `lucene-queryparser` sources are not in the local repository.)

The boost table says how Lucene "tolerates" a short word without refusing it: a two-edit hit on a word of six
letters is worth exactly as much as a one-edit hit on a word of three.

| `len(query)` | ed 1 | ed 2 |
|---|---|---|
| 3 | 0.67 | 0.33 |
| 4 | 0.75 | 0.50 |
| 5 | 0.80 | 0.60 |
| 6 | 0.83 | 0.67 |
| 8 | 0.88 | 0.75 |
| 10 | 0.90 | 0.80 |

**The thresholds live one layer up**, and every layer chose differently:

| Layer | Rule | Length measured on |
|---|---|---|
| Elasticsearch / OpenSearch `fuzziness: AUTO` | 0 edits for 0–2 characters, 1 for 3–5, 2 from 6; `AUTO:low,high` moves both bounds | code points of the **analyzed** term — the stem, when the field stems (prior-art §3.2) |
| `lucene-suggest` `FuzzySuggester` | no edits until 3 characters are typed, then at most 1; frozen prefix 1 | the typed prefix, in UTF-8 **bytes** unless `unicodeAware` (§4.1, prior-art §3.2) |
| `lucene-suggest` `DirectSpellChecker` | no suggestion for words under 4 characters; `accuracy` 0.5 | the query word (§4.1) |
| Solr, Vespa, plain `FuzzyQuery` | none — the caller passes the distance | — |

Two consequences for this record:

- **The Elasticsearch rule is the only one in the Lucene family that is applied to a stemmed term, and it does
  not compensate.** `AUTO` reads the length of whatever the analyzer emitted, so on a stemming field a user's
  8-letter `pánských` is judged as the 5-letter `pansk` and gets 1 edit, not 2. That is precisely the §7.3
  question, answered by default and by accident, and nobody measured it. (The local Elasticsearch documentation
  warns only that fuzziness is not applied when analysis stacks several tokens at one position,
  `query-dsl-match-query.md:157`; a warning about stemmed fields as such is not in the checkout.)
- **Lucene's *scoring* already contains the thing the thresholds approximate** — the shorter the word, the less
  a fuzzy hit is worth — and it ships no threshold *because* of that: a bad short match is admitted and sinks
  rather than being refused. The product engines refuse instead, and the research chose the product model
  (prior-art §3.8, "who owns the decision"). So the §7.1 thresholds are not something Lucene would have given
  us had we used `FuzzyQuery`; they are the rule we have to write. Within the family the `AUTO` boundaries
  (3/6) are the loosest and the `FuzzySuggester` ones (3, at most 1 edit) the tightest; §7.1's 4/8 sit between
  them and next to Meilisearch's 5/9.

**What of this is usable here.** Nothing, by construction — `lucene-core` has no threshold to reuse, which is
the point of the section. `FuzzyQuery#floatToEdits` is public and callable, but it is the fractional rule
Lucene itself abandoned. Elasticsearch's `Fuzziness` is not Lucene and not on the classpath; the
`FuzzySuggester` and `DirectSpellChecker` constants sit in `lucene-suggest`, which is not a dependency and
would not be worth adding for three integers. What Lucene *does* give Q2 for free is the hard cap —
`LevenshteinAutomata#toAutomaton` returns `null` above 2 (§5.1), so no rule can overshoot — and the measure
itself, `String#codePointCount`, which is the length every layer in the table uses. The rule of §7.1 is a dozen
lines of our own, and that is where it belongs: §7.7 wants it hot-swappable with the rank profile, which no
library constant could be.

### 7.3 The one real fork: thresholds on a stemmed dictionary

**The problem.** Meilisearch does not stem, and Vespa's attribute fuzzy matching does not either; their
thresholds are measured on surface forms. Typesense stems only when a field sets `stem: true` (default false,
`include/field.h:152`), and then reads the thresholds from the stemmed word (corrected 2026-10-16, §11.7).
Lucene-family engines apply fuzziness to the *analyzed* term, so with a stemming analyzer the distance is
measured on stems (§7.2). evitaDB's
dictionary holds folded **stems**, which are shorter than what the user typed — `CzechStemmer` removes suffixes
and can rewrite (`č` → `k`, `ž` → `h`) — so the thresholds of the prior-art document's §3.8 cannot be copied
without deciding which length they apply to. P3 §4.3 chose "measure on the typed token" without measuring.
The engines that stem all answer it the same way by default — the stem — but none of them measured it (§11.7).

Concretely, on this branch the Czech index chain produces folded stems: the typed word `pánských` (8
characters, 2 edits by every convention) becomes `pansk` (5 characters, 1 edit by the same conventions).
Three consequences follow, none of which the surface-form engines had to face (§6, reading 3, puts real cases
on each):

1. **Which length the threshold reads.** Measured on the typed word, a 5-character stem is searched with 2
   edits — looser than any engine allows for a 5-character word. Measured on the stem, the user's
   8-character word gets 1 edit — tighter than they expect.
2. **The stemmer already absorbs some typos and not others.** A typo in the suffix (`pánskych`) may be
   stemmed away for free; a typo in the stem (`pánksých`) survives. The effective tolerance is asymmetric
   before any threshold applies.
3. **A typo can change the stemmer's rule path**, producing a stem further than 2 from the correct one
   even though the surface forms differ by 1. This is the same failure class the folded-stemmer fan-out
   (M7 in the P5 measurement record) fixed for missing diacritics; for typos nobody has measured how
   often it happens.

**The measurement that settles it:** on the P5 fixture vocabulary and on the real corpora P1 uses, (a) the
distribution of typed-length vs. stem-length, (b) for a synthetic typo set (one substitution / insertion /
deletion / transposition per position class), the share of typos that survive stemming at distance ≤ 1, ≤ 2,
and > 2, split by whether the typo fell in the suffix or in the stem. That number decides whether thresholds
are computed on the typed token, on the stem, or on a combination (e.g. `min(thresholdByTyped,
thresholdByStem + 1)`), and whether variant 3 of P5 §6 (a surface-form lane in the dictionary) is a
*correctness* requirement for typo tolerance the way it already is for accent-stripped typing (P1 §2,
2026-08-25). **Measured on 2026-10-06 in §10.3** over 4.8 million inflected forms generated from the hunspell
fixture, and **on 2026-10-20 in §10.7** on P1's CMS corpus (229 million tokens). On the real corpus the answer is
the stem base on the stem dictionary together with a surface-form lane walked with the typed length, which beats
the typed base on recall and noise at once; a combination of the two lengths adds nothing.

### 7.4 Interaction with the hypothesis fan-out (M7)

Each query token becomes 1.3 hypotheses on average (max 2–4). Typo expansion runs per hypothesis, so the
expansion width multiplies. Two questions no record answers:

- Does a hypothesis that is itself a *guess* (the folded stemmer's alternative rule) deserve the full typo
  budget, or should only the primary hypothesis be typo-expanded? A wrong guess at distance 2 is a recall
  trap the Algolia/Meilisearch conventions never had to consider.
  **Answered on the real corpus (§10.7 b): every variant keeps the full budget.** The variant stemmer has no
  primary hypothesis, so the measurement took the *straight* one, Lucene's `CzechStemmer` on the folded word, which
  was always among them. The other variants are the only route to the target for 8.6–9.1 % of mistyped words;
  trimming their budget or dropping it costs 6–9 points of recall to save about a quarter of the noise.
- The cap on expanded terms (O3 in §2.2) — is it per token or per hypothesis? **Per token (query word)**, over
  the union of its variants: it bounds the postings union, which is per word (§10.7 c).

### 7.5 Where typo tolerance must be switched off

Every e-commerce engine has an opt-out because typo tolerance over **codes, SKUs, EANs, model numbers**
produces confidently wrong matches (`AB1234` and `AB1284` are different products, and a user typing a code
is typing it from a label, not from memory). Meilisearch has `disableOnAttributes`, `disableOnWords`,
`disableOnNumbers`; Algolia has `disableTypoToleranceOnAttributes` / `…OnWords` and `typoTolerance:
strict|min`. No record in this folder mentions it yet. It needs a decision on:

- **A token-shape rule** (cheap, no configuration): tokens containing a digit get 0 edits. Covers SKUs and
  model numbers without touching the schema. It is one `Character.isDigit` scan.
- **Per-attribute disable.** By the record's cost rule this is *locked* only if it changes stored tokens; if
  it merely tells the query planner "no expansion for terms of this field", it is free and belongs beside
  field weights in the rank profile or hot-swap artefact, not in the schema. Needs the same classification
  exercise schema-design §2.1 did for the other options. The planner knows the field when it decides, since
  expansion runs per field dictionary.
- **Per-word disable** (a brand list) — a hot-swap artefact like the synonym dictionary, which needs the
  artefact channel P7 §6 designs.

### 7.6 Building the threshold function ourselves

**The mechanism really is just a length comparison.** The walker already takes the three inputs a threshold
decides: the query word, `maxEdits` and `nonFuzzyPrefix` (`LevenshteinDictionaryWalker` constructor).
Transpositions are a constructor flag of `LevenshteinAutomata`. Q2 is therefore this, per typed token:

```
len       = codePointCount(folded typed token)
maxEdits  = token contains a digit ? 0                      // §7.5 opt-out, proposed
          : len < minLengthOneTypo  ? 0
          : len < minLengthTwoTypos ? 1
          : 2                                               // LevenshteinAutomata caps at 2 anyway
prefix    = min(nonFuzzyPrefix, len)                        // the clamp Lucene does, §7.2
```

That is about twenty lines with validation (`minLengthOneTypo ≤ minLengthTwoTypos`, `maxEdits ≤ 2`, prefix
`≥ 0`). The decision is made once per typed token, before expansion, and every hypothesis of the token inherits
it (§3.2 Q2). The automata are built per query, so changing a threshold invalidates nothing stored. The one cache
that may appear later, of built automata, keys on all four parameters. Nothing here touches the index, which is
why O1 holds.

**What is not trivial lies around the comparison, not in it:**

- **Which length.** This is the open fork of §7.3, and it needs measurement, not code. Once decided, the
  formula above only changes its input. If the answer is "the typed token", the length is available without
  extra work. `AnalyzedTerm.surfaceForm` is the typed word in NFC, and for cs/sk/pl/ro folding maps one
  character to one character (§6, reading 5), so its code-point count equals the folded length. If the answer
  is a combination, such as `min(byTyped, byStem + 1)`, the stem hypotheses are also at hand, from Q1.
- **The unfinished last word.** In search-as-you-type the last token grows with every keystroke, and its typo
  expansion runs beside the prefix expansion (P3 §4.2.3). The threshold reads the typed prefix length, so a word
  gains its first allowed typo at the keystroke that reaches `minLengthOneTypo`. That is the behaviour to state
  in the behaviour examples of §9 step 1, not extra code.
- **Opt-outs beyond the digit rule** — see §7.5 for what each needs.
- **Configuration** — see §7.7.

### 7.7 Configuration surface

By schema-design §7.2 the thresholds are **free** (query-side). Three candidate places, in the order
query-design §6.3 proposed for rank profiles: a query constraint (per query, for experimentation), a named
profile on the server (hot-swap), and a catalog-level default. The typo parameters should travel with the
rank profile — the artefact that already carries field weights and lane order — rather than grow a fourth
configuration channel. The exact DSL shape is O4 territory and is not decided here.

**Configuration is plumbing that does not exist yet.** The parameters fit one small immutable record: minimum
length for one typo, minimum length for two typos, frozen prefix, transpositions, cap, the digit rule, and the
disabled attributes. By schema-design §7.2 and query-design §6.1 it never goes into the schema. Its layers are
a built-in default in code, a value in the rank or query profile (issue #12, not built), and a per-query
override. The per-query override is a new constraint, which carries the full `new-constraint` recipe: query
model, EvitaQL grammar, Kryo serializer, GraphQL, REST and gRPC. Composing the layers follows query-design
§6.1: an item set in a higher layer replaces the lower one, nothing is merged.

So the honest split of §7.6–§7.7 is: the threshold logic is an afternoon's work. The configuration surface
costs what any new query-side option costs, and it should ride on the rank-profile work rather than open a
channel of its own. The length fork is the only part whose answer is not known yet, and it changes one input
of the formula, not the shape of the code.

### 7.8 Prefix matching on the last word — proposed approach for the production implementation (2026-10-08)

This is the approach proposed for the production implementation, agreed on 2026-10-08 on the evidence of §10.1
and §10.2. It answers when the prefix leg of the expansion runs, which no record had stated.

> **Superseded in part on 2026-10-15 by §11.** These rules were written for the as-you-type query. §11 separates
> the submitted search from the as-you-type query and measures both. It keeps rule 3 (prefix on by default, the
> same for both types) and revises rule 8: the one-typo cap applies to **every word of an as-you-type query**,
> not only the unfinished one. §11.6 adds that a submitted search runs its last word as the as-you-type query
> did, including the open-ended typo walk. §11.5 has the rules per query type.

**The question is not "prefix or exact".** Exact lookup, and typo expansion where the thresholds allow it, runs
for every query word, always. Prefix is an **additional** expansion source, never a replacement. The
`EXACTNESS` lane orders exact before prefix before fuzzy, so a prefix hit never outranks an exact hit with the
same number of typos (behaviour example "while typing, exact ranks above prefix", §10.4). The decision is
therefore only whether the prefix leg is **added**.

**The rules:**

1. **Only the last query word can get a prefix expansion.** A word followed by another word is finished and
   gets exact and typo expansion only. Prefix on a finished word does not help recall: stemming already covers
   inflection, so `kalhoty` finds `kalhotami`. What a prefix adds on top is derivations and compounds, which are
   usually different products: `kabat` reaches `kabatek`, a doll's coat. It is also the expensive leg — a
   two-letter fragment reaches thousands of keys (§10.1). Typesense and Meilisearch also match only the last word
   as a prefix (prior-art §3.5, query-design §10.3).
2. **A trailing separator closes the last word.** The engine cannot tell from the text whether the last word is
   finished: `kalhot` is a complete stem and also the start of `kalhoty`. When the raw query ends with whitespace
   or punctuation, the user has finished the word and no prefix leg runs. Otherwise the last word is treated as
   unfinished whenever prefix matching is enabled.
3. **On by default, configurable in the database configuration, overridable per query.** The switch has three
   layers, and a higher one replaces a lower one, as in §7.7:

   | layer | where | value |
   |---|---|---|
   | built-in default | code | **on** |
   | database configuration | `EvitaConfiguration` (`evita-configuration.yaml`), a new fulltext section | engine-wide default for every catalog, on or off |
   | query | a parameter of the fulltext filter constraint (name is O4 territory) | on or off for one query |

   The query override works in both directions. A classic "type, press Enter, see results" page can switch the
   prefix leg off; an instant-results box keeps it on even when the operator turned the default off. The
   suggester (query-design §10) always uses the prefix leg on the last word whatever the setting, because a
   suggest request is by definition made while the user types.
4. **The switch never lives in the schema.** The prefix leg is a query-time walk over the dictionary and nothing
   is written for it, so changing it never needs a reindex (O1, schema-design §7.2). That is the difference from
   the existing client's practice of writing every prefix into the index (P3 §4.1).
5. **A minimum fragment length guards the first keystrokes; the default is 3** (decided 2026-10-14). No prefix
   leg runs for a last word shorter than the configured minimum, because short fragments are where the cost is
   (17,572 keys on average inside the stem, §10.1; 4,510 for `ka` alone). The value sits beside the switch in the
   same two configuration layers, so an operator can lower it to 2 once the real-catalog measurement shows it fits
   the budget. The default of 3 matches what users of the existing client get today: its `SummonFilter` writes
   prefixes down to 3 characters (P3 §4.1).

   **No surveyed engine has a query-time minimum** (read from the local checkouts, 2026-10-14). They make short
   prefixes cheap instead, either by precomputing them at index time or by capping the candidates:

   | engine | minimum prefix length | how short prefixes are served | source |
   |---|---|---|---|
   | Lucene `AnalyzingInfixSuggester` | none | prefixes shorter than `minPrefixChars` (default 4) are looked up in edge n-grams of 1–4 characters written at index time; longer ones run a `PrefixQuery` | `AnalyzingInfixSuggester.java:154, 409, 543-549` |
   | Elasticsearch / OpenSearch `text` | none | opt-in `index_prefixes` precomputes prefixes of 2–5 characters; otherwise a query-time prefix query | `TextFieldMapper.java:130-131` (ES), `:127-128` (OpenSearch) |
   | Elasticsearch `search_as_you_type` | none | edge n-grams of 1–20 characters precomputed at index time | `SearchAsYouTypeFieldMapper.java:93-94` |
   | Meilisearch | none | prefixes of up to 4 characters shared by at least 100 words come from a prefix database built at indexing time; otherwise a dictionary range scan capped at 1,000 derivations | `index.rs:1886`, `limits.rs:5`, `compute_derivations.rs:194-219` |
   | Typesense | none | `prefix` defaults to true; the candidates per query token are capped at 10, or 4 for collections over 500,000 documents | `collection.cpp:9534, 9877-9880`, `index.h:736-737` |
   | Vespa | none | prefix is an operator the application chooses; no default behaviour | `PrefixItem.java` |

   evitaDB rejected index-time precomputation for the main index (README, *Rejected outright*: write
   amplification for a read-time feature, and parameters locked into stored data). A query-time dictionary walk
   therefore pays most exactly where those engines pay least, on one- and two-letter fragments. The minimum length
   is the substitute for their precomputation. A Typesense-style cap on candidates is the other substitute; P3
   §4.1 warns that a cap truncates alphabetically unless the walk is best-first, so it is insurance, not the
   primary guard.
6. **The prefix scan starts from both the typed fragment and its stem hypotheses** —
   `prefix(fragment) ∪ prefix(hypotheses)`, §10.1. The fragment has to be taken from the raw input, around the
   stop filter, because 2.6 % of fragments on the way to longer words are stop words that the query chain drops.
7. **An unfinished last word also gets an open-ended typo walk** (§10.2), so that a word that is mistyped and
   unfinished at once is found: 93–95 % at every keystroke against 18 % without it. It is bounded by the same
   minimum length and by the expansion cap of §7.1.
8. **An unfinished last word gets at most one typo** (decided 2026-10-14), whatever its length. *Revised by
   §11.4: in an as-you-type query every word gets at most one typo, because a finished word is expanded on the
   keystroke that finishes it.* The thresholds of
   §7.1 still decide between zero and one edit; the cap of one overrides their two-edit band for this one word
   only. Finished words keep the full thresholds, including two edits from eight characters. The reason is the
   per-keystroke budget: the suggester has p99 ≤ 5 ms per keystroke for everything, of which the term expansion
   gets 1.5 ms (O2). One edit fits it with a wide margin; two do not.

   **What would change if two edits were enabled for the unfinished word**, measured in §10.2 on the
   865,287-key dictionary, single-threaded, per word with 2.56 stem variants on average:

   | | one edit (proposed) | two edits |
   |---|---|---|
   | automaton construction per variant | 33 µs | 1,247 µs, of which Lucene's `toAutomaton(2)` alone is 433 µs |
   | dictionary walk per variant | 41 µs, 100 keys read | 952 µs, 1,693 keys read, 1,683 seeks |
   | term expansion per word | about 0.19 ms | about 5.6 ms; about 4.5 ms once the walker stops building the distance-2 automaton twice (0.43 ms per variant) |
   | candidate terms per word (§10.3 d) | p50 1, p99 35, max 101 | p50 8, p99 410, max 1,220 |
   | recall gained | — | the typos that need a second edit: 2.1 % of stem typos and 15.6 % of suffix typos (§10.3 b) |

   - **Latency:** the last word alone would cost three to four times the whole term-leg budget on every
     keystroke. The open-ended walk of rule 7 comes on top, and it reads more keys than the finite walk.
   - **Entity leg:** with about eight times as many candidate terms at the median and twelve times at p99, the
     postings union that fetches the products grows in step. That leg has its own share of the 5 ms.
   - **Construction is a fixed cost:** it does not shrink with the dictionary, the walk does. Even after the
     construction fix, building the automata alone costs about 2.1 ms per word (0.81 ms per variant), above the
     1.5 ms term-leg budget before a single key is read. A smaller production dictionary cannot bring two edits
     into the budget.
   - **What it buys is small while typing:** under the §7.1 thresholds only fragments of eight or more letters
     would reach two edits at all, so the gain arrives on the last few keystrokes of long words. The finished word
     gets two edits anyway the moment the user types a space or submits the search.

   The cap is a configuration value beside the prefix switch, so an operator with a small dictionary can raise it
   to two once a measurement on their own catalog fits the budget.

**What it costs to build.** The database-configuration layer is a new field in `EvitaConfiguration` with its
YAML binding and its entry in the configuration documentation (`documentation/user/en/operate/configure.md`). The
query layer is a parameter of the fulltext filter constraint, which does not exist yet, so it comes with that
constraint rather than as a change of its own. The rule logic itself — last word, trailing separator, minimum
length — is a few lines in the query planner.

**Relation to §7.7.** The typo thresholds are proposed to travel with the rank profile, because they shape
ranking. The prefix switch describes the **input mode** of the query rather than ranking, so it belongs to the
database configuration and the query. If the query profiles of issue #12 are built, a profile may carry it too,
as a layer between the two.

---

## 8. Relevance of a fuzzy hit

### 8.1 How Lucene scores a fuzzy term

A fuzzy hit's relevance is built in three stages, all in `lucene-core`:

1. **A per-term boost at enumeration time** (`FuzzyTermsEnum#next`, `FuzzyTermsEnum.java:236-255`). The
   enumerator walks with the widest automaton, learns the exact distance `ed` of each hit by testing it against
   the narrower acceptors (`:240-246`), and sets

   `boost = 1` for `ed = 0`, otherwise `boost = 1 − ed / min(len(hit), len(query))`, lengths in code points.

   The boost is *relative to length*: one edit on a 3-letter word is worth 0.67, on a 10-letter word 0.9 (the
   full table is in §7.2). It can go negative when the hit is shorter than the distance (`ed = 2` against a
   1-letter term gives −1); such boosts are kept while collecting and clamped to `0.0f` when the query is built
   (`TopTermsRewrite.java:166-170`), so the term still matches but scores nothing.
2. **Collection of the best `maxExpansions` terms** (`TopTermsRewrite#rewrite`, `:63-172`). A priority queue of
   size 50 (`FuzzyQuery.defaultMaxExpansions`, `FuzzyQuery.java:56`) is ordered by boost; ties go to the
   lexicographically **smaller** term (`ScoreTerm#compareTo`, `:200-203`, and the early reject at `:117-120`),
   which makes the expansion set deterministic for a given dictionary. Once the queue is full its worst boost
   is pushed back to the enumerator (`:150-152`), and `FuzzyTermsEnum#bottomChanged` (`:192-218`) lowers
   `maxEdits` as soon as the worst competitive boost reaches `1 − maxEdits / len(query)` (`:202-207`) — with a
   full queue of one-edit hits the two-edit automaton is never walked again. This is the ancestor of the
   ed0 → ed1 → ed2 cascade of P3 §4.2.4, run from the wide end.
3. **A query that scores each expansion with a shared document frequency.** The default rewrite,
   `TopTermsBlendedFreqScoringRewrite` (`MultiTermQuery.java:198-243`), builds a `BlendedTermQuery` with
   `BOOLEAN_REWRITE` (`:219`): every expansion becomes a `SHOULD` clause, a `TermQuery` wrapped in a
   `BoostQuery(boost)`. Before that, `BlendedTermQuery#rewrite` (`BlendedTermQuery.java:270-300`) replaces each
   expansion's document frequency with the **maximum** over all of them and the total term frequency with the
   **sum** (`:285-286`, applied at `:290`). The reason is BM25's IDF: without blending, a rare misspelling
   present in the index would carry a huge IDF and outscore the correctly spelled word. A document's score for
   the fuzzy clause is therefore `Σ boost(expansion) × BM25(tf of that expansion, blended df)` over the
   expansions it contains; a document holding both `kalhoty` and `kalhotky` is paid twice.

What falls out of this: Lucene has **no notion of "number of typos"** as a ranking criterion. The distance
enters as a continuous multiplier, the length of the word scales it, and corpus statistics (`tf`, blended `df`)
weigh in after it. Elasticsearch exposes the rewrite as `fuzzy_rewrite` and keeps this one as the default
(prior-art §3.2).

**What of this is usable here.** As code, almost nothing — and that is not a loss. Stage 1 lives inside a
`TermsEnum` and talks to stage 2 through `BoostAttribute` / `MaxNonCompetitiveBoostAttribute` on an
`AttributeSource`; stage 2 is `TopTermsRewrite` over `TermStates`; stage 3 is `BlendedTermQuery`, `BoostQuery`
and `BM25Similarity` over Lucene postings. None of these can be instantiated without a Lucene `IndexReader`,
and even if they could, O5 forbids what they compute. What *is* reusable: the acceptor test behind the
exact-distance cascade is `CharacterRunAutomaton#run`, free and already used in §5.1; the formula
`1 − ed / min(len)` is one line if ever wanted; and the two rules worth copying — narrow the automaton when the
cap fills, break ties on the term — are ideas, not classes. The table in §8.2 is therefore not "Lucene's
scoring versus ours" as an alternative we could switch to; it is what the design has to provide itself, with
Lucene's as the reference for what each decision costs.

### 8.2 What the evitaDB design proposes instead — a theoretical proposal

> **Status: a theoretical proposal, not an implementation.** The right-hand column below is assembled from the
> design records (research §4.3, P1 §5.3, P3 §4.4); no engine code implements any of it, and no engine test
> covers it. The integer lanes, the per-word best distance and the 64-bit composite exist only on paper until P1
> and P7 build the dictionary, the accumulator walk and the composite. Its only executable form is the test-side
> reference ranker in `TypoToleranceBehaviourTest` (2026-10-06, §10.4), which implements the lane order over a
> small catalog to check the behaviour examples — a model of the proposal, not a prototype of the engine.

The two cannot be mixed:

| Aspect | Lucene `FuzzyQuery` | evitaDB (research §4.3, P1 §5.3, P3 §4.4) |
|---|---|---|
| Unit that is scored | each expanded term, independently | the query token; the *best* distance among its hits |
| Distance → relevance | `1 − ed / min(len)`, a float | two integer lanes: `TYPO = 255 − Σ weighted typos`; `EXACTNESS` exact > prefix > fuzzy(1) > fuzzy(2) |
| Corpus statistics | BM25 on `tf` and the blended `df` | none (O5); the impact byte comes from the sidecar and is independent of the distance |
| Two expansions hit one document | both contribute (`SHOULD` sum) | the token counts once |
| Cap | 50 best by boost, ties → smaller term | N per token, value open (O3); the cascade stops when full |
| Length of the word | scales the boost continuously | decides `maxEdits` once, before expansion (Q2) |
| Determinism | within one index; through `df` it differs between indexes with different statistics | by construction: no statistics, no floating point |

Three things from Lucene's scoring are worth taking, and three are deliberately not:

- Take: the exact-distance cascade (already in the walker, §5.1); the collection cap that narrows the automaton
  as it fills (P3 §4.2.4 has it as the cascade ed0 → ed1 → ed2, the same pruning without a priority queue);
  and the **lexicographic tie-break** when the cap cuts through a band of equal distance — it is what makes
  "same query, same data, same result" (O5) hold at the cap boundary, and no record states it yet.
- Leave: the length-relative float (it is Lucene's substitute for a length threshold, §7.2, and the composite
  has an integer lane for it); document-frequency blending (there is no IDF to protect); and the per-expansion
  sum (the 2026-09-02 rule in P1 §5.3 forbids it, for the reason given there).

For the suggester's *candidate terms* the composition is P3 §4.4's cascade — exactness class, then the best
field weight, then postings cardinality — and the exactness class is exactly the `ed` the cascade learns.

The one open item this touches is the **typo-lane weights** (§7.1, last bullet). Lucene's only principled input
is that an edit should weigh less on a longer word. That could be carried into the lane as an integer (two
edits on a word of eight or more characters counted as one unit, say), but Meilisearch and Typesense both count
raw typos and stay explainable, and the lane is 8 bits wide. It stays open; the measurement of §7.3 is the
right place to see whether length-relative weighting changes any ordering that matters.

### 8.3 What it takes to build the relevance side in evitaDB

**Start from the other end: what the automaton gives per term.** One bit. A Lucene `Automaton` is a set of states
with transitions and an accept flag. `CharacterRunAutomaton#run` answers "is this string within `n` edits", and
`RunAutomaton#step` / `#isAccept` expose the same thing state by state (`RunAutomaton.java:141, 174`). There is
no distance on the result, no edit position and no edit type. The number that would carry it, the minimum error
count of a parametric state, exists only while the DFA is being built. It sits in the private `minErrors` array of
the package-private `LevenshteinAutomata.ParametricDescription` (`LevenshteinAutomata.java:249-274`). There it
only decides which states are accepting, and it is gone once `toAutomaton` returns. Every relevance signal
therefore comes from something we run or look up next to the automaton:

| Signal per expanded term | How it is obtained | Cost | Status |
|---|---|---|---|
| accepted within `maxEdits` | the widest automaton, during the walk | the walk itself | in the walker (§5.1) |
| exact edit distance 0 / 1 / 2 | cascade: test the hit with the narrower acceptors, as `FuzzyTermsEnum` does (§8.1); distance 0 is string equality | one extra `run` per hit and band | in the walker (`Hit.distance`) |
| exactness class: exact / prefix / fuzzy | which leg produced the term: exact lookup, prefix scan or DFA walk | free | to build with the legs (P3 §4.2) |
| which hypothesis of the token produced it | the walk runs per hypothesis (Q3) | free | to carry through |
| postings cardinality (popularity) | the dictionary bucket, `BucketCursor.size()` (P3 §3.1) | free during the walk | the walker has only `1` / `-1` today |
| the postings themselves | the dictionary bucket | needed anyway for matching | P1 |
| query and term lengths | `codePointCount` | trivial | — |
| edit type and position (transposition, substitution, first letter, suffix) | not from the automaton; a dynamic-programming pass over the hit, `EditDistances` in `evita_test_support` | `O(len²)` per hit, negligible at a capped hit count | only if a weight ever needs it |

What follows for the relevance computation:

- **The distance is the only typo-specific value, and the walker already delivers it.** Everything the composite
  needs downstream comes from it or from the dictionary. The `TYPO` lane needs the distance, the `EXACTNESS` lane
  needs the leg, and P3 §4.4's term cascade adds the cardinality.
- **Anything finer than the distance is our own computation.** The candidates are a per-edit-type weight (a
  transposition cheaper than a substitution), a keyboard-adjacency weight, and a "typo in the stem or in the
  suffix" flag. Lucene could not supply them even if we used `FuzzyQuery`, because it never computes them either.
  None is proposed. If one ever is, the DP pass is the place, run only on the capped hit list.
- **Lucene's own float boost is derived, not given.** `1 − ed / min(len)` is the cascade distance plus two
  lengths (§8.1). Anything we might want from Lucene's scoring is therefore reproducible from the table above.

**Most of the work is not typo-specific, and it is already planned elsewhere.** Typo relevance is a thin layer on
top of four things that do not exist on this branch yet: the P1 term dictionary per field with a postings bitmap
and a readable cardinality per term (P1 K3/K5, P1 §3.1), the phase-1 accumulator walk (P1 §5.3), the 64-bit
composite and its lane packing (research §4.3, P7), and the sorter seam it plugs into (P1 §5.4). Today the
engine has only the `analysis` package. Until those land, the typo layer can be built and tested only against
the §5 test fixture.

**The typo-specific pieces**, given those four:

1. **The walker moves into the engine.** `LevenshteinDictionaryWalker` lives in `evita_test_support` today. It
   already returns `(term, distance)` per hit in dictionary order. Two changes come with the move. `Hit.records`
   must carry the real postings cardinality, which is `BucketCursor.size()` per P3 §3.1. Today the field only
   holds `1` or `-1`. The walk also needs a cap, and it has none today.
2. **The cap, with a deterministic order.** Order the hits by distance ascending, then by term ascending.
   Lucene breaks ties the same way (§8.1), and it is what keeps O5 true when the cap cuts through a band of
   equal distance. Run the cascade from the narrow end, as P3 §4.2.4 states: exact lookup first, then the
   distance-1 walk, then distance 2 only if the cap is not full. The distance-2 walk also returns the distance-1
   hits again, so it must skip terms already collected. Two things are still open: the cap value, and whether the
   cap applies per token or per hypothesis (§7.4).
3. **Per-token merge into distance bands, as bitmaps.** This is the one piece of real design here, and it is
   cheap. For a query token with hits at distances 0–2, build three bitmaps with RoaringBitmap operations:

   ```
   B0 = OR postings(hits at ed 0)
   B1 = OR postings(hits at ed 1)  ANDNOT B0
   B2 = OR postings(hits at ed 2)  ANDNOT (B0 OR B1)
   token matches  = B0 OR B1 OR B2          → feeds the must-match AND across tokens (Q4)
   token distance = 0 / 1 / 2 by band membership
   ```

   That is the 2026-09-02 rule of P1 §5.3 ("the token counts once, with the best distance among its hits")
   implemented without a loop over documents. Hits from all hypotheses of the token go into the same bands. The
   exactness lane is built the same way, with a prefix band between exact and fuzzy. During the accumulator walk
   of P1 §5.3, a candidate's band then decides its contribution.
4. **The lane value.** Per candidate, `TYPO = 255 − Σ over tokens of w(distance)`, an 8-bit lane. The weight
   table `w` is the open decision of §7.1. The simplest choice is `w = (0, 1, 2)`, which counts raw typos as
   Meilisearch and Typesense do. With at most 2 per token, 8 bits hold any query of up to 127 words. A
   first-letter penalty is unnecessary while the frozen prefix is 1, because no first-letter typo is ever found.
5. **Keep the expansion for later stages.** The per-token hit list, meaning each term with its distance, must
   survive into Q7 (which typed word to highlight) and Q8 (explain). It is small and is already produced by step
   1, so this is plumbing rather than computation.
6. **Tests.** The walk keeps the linear scan as its oracle, as §5.1 does now. New tests cover determinism at the
   cap boundary, where equal-distance hits are cut by the term order. The band merge gets a cross-check against a
   per-document loop. Ranking-order tests come from the behaviour examples of §9 step 1.

**What is not needed:** a float boost, BM25, document-frequency blending, a priority queue, or any Lucene class
beyond the automaton already in use. Steps 1–5 together are a few hundred lines of engine code. Their size is
small compared with the P1 and P7 prerequisites they depend on.

---

## 9. Next steps of the analysis

What this document leaves to other records: the suggester's scoring cascade for candidate terms (P3 §4.4);
the surface-form vs. stem dictionary layout (P5 §6 variant 3 — P1 K3 measures the size, §7.3 contributes
only the typo-correctness argument); lane order in the composite (P7); DSL naming (O4).

**Done:**

- Behaviour examples, fourteen in `TypoToleranceBehaviourTest`, all passing (§10.4).
- The synthetic typo corpus for Czech and the threshold measurement (§10.3).
- Prefix versus exact, and prefix × typo while typing (§10.1, §10.2).
- The two query types, their cost, and the rules per type (§11); dropdown versus results-page consistency
  (§11.6, pattern 1 decided).
- Precomputed short prefixes versus the query-time scan (§12).
- Thresholds, stem variants, the cap, the as-you-type budget, two edits and the noise refinements on a real corpus
  of 972,611 articles (§10.7).

**Decided 2026-10-21 in the review of the implementation specification**
([`typo-tolerance-fulltext-dictionary-algorithm.md`](typo-tolerance-fulltext-dictionary-algorithm.md)), besides
items 1, 4 and 11 below:

- the submitted search's open-ended walk on the last word uses the full thresholds, two edits from 8 letters;
- the prefix leg runs unconditionally whenever the rules allow it, never only when exact and typo matching find
  too little (§11.4);
- two edits are governed by the length threshold alone, never a fallback when one edit finds too little;
- the opt-outs beyond the digit rule (§7.5) are a question of the developer-facing API, not of the algorithm,
  whose only hook is 0 edits for an opted-out word or attribute;
- the typo-lane weights (item 6) need real-world queries, judged results or click data, before any value other
  than raw typo counts is chosen;
- the lane's counter unit and field prefix (§3.4, *Open for the discussion*) are implementation details.

**Open, in order of what blocks the most.** Items 1–5 and 7 now carry the real-corpus answer of §10.7
(2026-10-20); what is left of each is a decision, not a measurement.

1. **The as-you-type budget at three letters — Decided 2026-10-21: no prefix leg below four letters.** The
   minimum fragment length rises from 3 to 4, because precomputed prefix postings stay rejected (README, *Rejected
   outright*). The open-ended walk starts at **5 letters while typing** and at **4 in a submitted search**, which
   runs once and has the 25 ms phase-1 budget. A submitted search tolerating more than the preview keeps pattern 1
   (§11.6). The measurement behind it: on the real corpus (§10.7 d) the budget is met from five letters
   with the proposal. It is met from four once the open-ended walk starts at five letters and the 50-term cap
   applies: p99 3.3 ms, with four-letter typo recall falling from 98.7 % to 59.8 % until the fifth letter. Three
   letters stays at 8.6–14.8 ms p99 under every lever. The choice was between precomputed 3-letter prefix postings
   (§12, which would have revisited the README's *Rejected outright* row) and no prefix leg below four letters; the
   latter was taken. With the 50-per-leg cap, four- and five-letter keystrokes still measured 7.5–7.7 ms at p99
   with the open-ended walk from five letters (§10.7 d), over the 5 ms target. No engine merges every term a short prefix reaches. They precompute short prefixes
   (Meilisearch, by the same rule as §12 arm C) or keep a few best-first terms (Typesense) (§11.7).
2. **The expansion cap — Done (2026-10-20), §7.1.** 50 per leg of each query word: all exact terms, the 50 most
   frequent prefix terms, the 50 nearest-then-most-frequent typo terms (§10.7 c).
3. **The thresholds — Decided 2026-10-21 (4 / 8)** (§7.1, §7.3, §10.7 a, g):
   - **0 edits below 4 letters, 1 edit from 4, 2 edits from 8** (submitted search only), first letter frozen,
     words with a digit exact. 4 is the measured knee; 8 is the convention, the data shows a trade-off without a
     knee there.
   - **The length read is the stem's on the stem dictionary and the typed word's on the surface-form lane.** That
     finds 95.5–99.4 % of mistyped words against 83.8–97.5 % for the typed length everywhere, at about half the
     noise. Every engine that stems reads the stem (§11.7).
4. **A surface-form lane walked by typo and prefix expansion — Decided 2026-10-21: built**, as designed in §3.4.
   On the unfinished last word the lane is walked open-ended as the stems are, and nowhere else (P5 §6 variant 3, U9
   decided 2026-10-20; §10.7 a, f, h). It recovers every typo a stem-only dictionary cannot reach (3.0–5.8 % on real
   text, 6.8–19.4 % on the fixtures) and the 2.1 % of unfinished keystrokes no stem input reaches. **The design for
   the discussion is §3.4**: keys and stems, no postings, every stem per folded form, the accented form U9 shows,
   and a reference counter per pair — **+141.5 MB on this corpus, +19.9 % over the stem dictionary with its
   postings**. Indexing surface forms as full terms instead would cost +717 MB (P1 part 6.2).
5. **Two edits in a submitted search — Decided 2026-10-21: kept, from 8 letters** (§10.7 e, g, i). With the lane,
   a single typo in such a word never needs the second edit (one edit finds 100 %); the second edit exists for
   **two typos in one word**, where it lifts recall from 17 % to 94.5 %. **The cost accepted with it**, measured on
   the real corpus:
   - **per long word:** 6.6 ms at p50 and 16.2 ms at p99 of term expansion with the lane, against 0.5 and 1.2 ms
     with one edit — about 13 times as much;
   - **per submitted query:** 6.3 ms at p50 and 24.9 ms at p99 with postings, against 1.8 and 7.1 ms with one edit;
     1 % of queries exceed the 25 ms phase-1 budget, and a query with two long words approaches it on its own;
   - **noise:** 24.1 wrong stems per correctly typed word, against 13.8 with no two-edit band (§10.7 g).

   To revisit if the budget bites: the Typesense-style fallback, two edits only when one finds too little, would pay
   the cost only when needed; it is unmeasured.
6. **The typo-lane weights** (§7.1, §8.2) — how much a typo pushes a result down in ranking: does a two-edit match
   count 2 against 1, does a first-letter typo count double, does a prefix-only match cost anything — and **the
   opt-out rules** (§7.5) — where typo tolerance is switched off: words with a digit (codes, SKUs; already in the
   thresholds), whole attributes such as `code` or `ean`, and a list of words such as brands. The engines count raw typos per word in an integer lane (Typesense: a prefix-only
   match costs 1, a typo 2); Meilisearch counts a first-letter typo double. Their opt-outs are settings, off by
   default (§11.7). A corpus cannot answer this; it needs judged results.
7. **Two noise refinements of the prefix leg — measured** (§10.7 f). Both are about the unfinished last word,
   whose prefix leg expands the typed fragment *and* its stem variants (§7.8 rule 6), not about the minimum
   fragment length of 3:
   - **The length guard.** A stem variant much shorter than the fragment drags in unrelated words: typing `kabat`
     yields the variant `kab`, whose prefix reaches `kabelka` (§11.4). The guard skips variants more than one
     letter shorter than the fragment. It removes such noise on 8.3 % of keystrokes but loses the target on 1.48 %
     — words reachable only through the short variant. The surface-form lane reaches those words anyway, because
     the prefix of the typed fragment over whole folded words finds the word being typed. **Decided 2026-10-21:
     not the default; recorded as a possibility once the surface-form lane exists.**
   - **Mid-ending keystrokes** that neither the fragment nor its variants reach as a prefix: 2.1 % on real text,
     not the fixture's 11.2 %, and the lane reaches all of them.
8. **Prefix on every word — not pursued (2026-10-21).** Prefix on the last word only is the decision (§7.8,
   2026-10-08). The existing client prefixes every word; that would be revisited only if real queries or click
   data showed the decision losing results, which this line has neither of.
9. **Slovak, Polish and Romanian — Done (2026-10-16), §10.6.** The typo mechanics carry over unchanged. The
   stemmer interaction does not: the share of typos after the first letter out of reach on a stem-only
   dictionary is 6.8 % for Slovak, 12.9 % for Polish and 19.4 % for Romanian, against 8.3 % for Czech, which
   strengthens item 4 most for Romanian.
10. **Everything on P1's real catalog — Done for the CMS corpus (2026-10-20), §10.7.** The e-commerce corpus of P1
    (short names and codes, two locales) is not on the development machine; its shape differs most for the
    as-you-type budget, where short fields mean sparser postings.
11. **The intra-leaf seek — Done (2026-10-21): worth building** (§5.4.1). It was measured on the real corpus in
    the production tree shape. The upper bound is 54 % of walk time, and the real gain is measured once
    `BucketCursor#seek(K)` exists.
12. Fold the outcome into `p3-suggester.md` §4.3, or into its own record superseding that section — postponed on
    2026-10-06.

---

## 10. Measurements of 2026-10-06

Four questions of the 2026-10-06 round, answered by JUnit measurement rather than argument: how prefix and exact
expansion of the unfinished last word should work (§10.1), how prefix and typo combine while typing (§10.2),
which length the thresholds should read on a stemmed dictionary (§10.3, the fork of §7.3, together with the cap
distribution of §9 step 5), and whether the proposal behaves as the behaviour examples demand (§10.5).

**The harness.** Nothing here touches `evita_engine`; everything is test code.

- **A realistic dictionary.** Lucene 9.12.3 ships `org.apache.lucene.analysis.hunspell.WordFormGenerator` in
  `lucene-analysis-common`, which is already a dependency. It expands the hunspell `cs_CZ` fixture's headwords
  with their affix rules (the `.aff` file next to the `.dic`) into **4,832,620 inflected forms** (4.35 million
  distinct) in about 30 s. Each form goes through the index side of the Czech chain (stop words dropped,
  `CzechStemmer` on the accented form, diacritics folded afterwards). That gives **865,287 folded stems**,
  loaded into `TransactionalBucketBPlusTree`. That is the order of a production catalog's dictionary
  (400–500 thousand terms, P3 §4.1) rather than of the 241,494-headword list of §5. Classes:
  `CzechWordForms` and `CzechStemDictionary` in `evita_test_support` (`io.evitadb.test.fulltext`).
- **Queries.** A deterministic sample of one distinct form in 500 (8,443 forms), typed bare and run through the
  production `czech-search` chain. Synthetic typos come from `SyntheticTypos`, seeded. The dictionary shortcut
  was checked against the production `czech` index analyzer on all 8,443 sampled forms: **0 mismatches**.
- **The expansion under test** is `TypoExpansion`, a reference implementation of stages Q1–Q3 (§3.2): exact
  lookup of the stem hypotheses, prefix scan of the typed fragment, the guided Levenshtein walk per hypothesis,
  and, new in this round, an **open-ended** walk (`LevenshteinDictionaryWalker` with `openEnd = true`). The open
  end accepts a term when some prefix of it is within the budget. Thresholds are `TypoThresholds.PROPOSAL`
  (§7.1: 0/1/2 edits from 1/4/8 characters, first letter frozen, words with digits exact).
- **What the numbers are not.** Typos are synthetic and uniform, not drawn from query logs. "Noise" counts
  dictionary terms a query word also matches; it is a precision proxy, not judged irrelevance, and a
  production dictionary is smaller. Timings are single-threaded JUnit wall-clock after warm-up, indicative only.

The measurement classes are tagged `slow` and skipped by the fast loop. They take about seven minutes together,
half a minute of it the dictionary build, which is shared:

```shell
mvn -nsu -pl evita_test/evita_functional_tests test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='PrefixVersusExactExpansionTest*,PrefixTypoMeasurementTest,TypoThresholdMeasurementTest'
```

### 10.1 Prefix versus exact expansion of the unfinished last word

**State of the code.** Neither exists. The engine has no term dictionary, no fulltext query and no prefix leg;
the existing `attributeStartsWith` works on raw values in the filter index and is unrelated. The design is P3
§4.1: a prefix scan over the dictionary for the last, unfinished word only, beside the exact lookup of its stem
hypotheses, both at query time. What no record decided is **what string the prefix scan starts from**. The
dictionary holds stems, and the user types a word that grows past its stem once the suffix begins, and that the
stemmer may rewrite at its end (`muž` → `muh`).

`PrefixVersusExactExpansionTest` compares four inputs at every keystroke (from the second letter) of the 8,443
sampled forms, bucketed by where the keystroke falls relative to the form's index stem:

| keystroke position | keystrokes | S1 prefix(fragment) | S2 exact(hypotheses) | S3 prefix(hypotheses) | S1 ∪ S2 | mean keys S1 | mean keys S3 |
|---|---|---|---|---|---|---|---|
| inside the stem | 67,176 | 100.0 % | 9.7 % | 97.4 % | 100.0 % | 17,572 | 18,540 |
| past the stem, unfinished | 10,031 | 0.0 % | 81.2 % | 88.8 % | 81.2 % | 4 | 26 |
| complete word | 8,443 | 6.8 % | 100.0 % | 100.0 % | 100.0 % | 1 | 9 |

The columns give the share of keystrokes at which the target stem is among the candidates; "mean keys" is the
number of dictionary keys the input reaches.

Readings:

- **Typing inside the stem, the typed fragment is the only input that always works.** Its stem hypotheses miss
  the target 2.6 % of the time, and every one of those misses is a fragment the stop filter dropped (the trap
  below). Stemming the unfinished fragment itself never lost the target inside the stem.
- **Typing into the suffix, the typed fragment never works**, because it is longer than the stem or rewritten
  at its end. The fragment's hypotheses must take over: as exact terms they reach 81.2 %, used as prefixes
  88.8 %.
- **The proposal should therefore be `prefix(fragment) ∪ prefix(hypotheses)`**, with exact lookup as the special
  case of a prefix that equals the key. It reaches 100 % inside the stem and on the complete word, and 88.8 %
  in between, for 26 keys on average past the stem. The remaining 11.2 % are partial suffixes the stemmer
  turns into a stem that is no prefix of the target. They stay open; this round did not measure a remedy.
- **The first keystrokes are the expensive ones**, as P3 §4.1 warned: inside the stem the fragment reaches
  17,572 keys on average, because two- and three-letter fragments open whole subtrees. P3's minimum prefix
  length is a requirement, not a nicety.
- **A trap for the implementation:** 1,741 fragments inside the stem (2.6 %) are Czech **stop words** on the way
  to longer words — mostly `za`, `po`, `vy`, `do`, `na`, `pro` and `od`. The `czech-search` chain drops them, so a pipeline that takes the last
  word from the analyzer output never runs its prefix scan. The unfinished last word has to reach the prefix
  scan from the raw input, around the stop filter.
- **The M7 invariant holds on inflected forms.** Every one of the 8,443 complete forms finds its own index stem
  among its query hypotheses (S2 = 100 %, 0 misses). The lexicon sweep of the P5 record proved this only for
  headwords; this is the first check on the forms users actually type.

### 10.2 Prefix × typo for search-as-you-type

`PrefixTypoMeasurementTest` measures the three options of P3 §4.2.3. The corpus has 1,000 sampled forms of six
or more letters, each with one synthetic typo after the first letter, cut at every keystroke from just past the
typo to the end: 5,170 keystrokes. Thresholds read the typed fragment.

| option | keystroke | keystrokes | target found | candidates p50 | candidates p95 | latency p50 | latency p99 |
|---|---|---|---|---|---|---|---|
| 3: prefix only | 3+ letters missing | 2,562 | 0.0 % | 0 | 11 | 0.01 ms | 0.09 ms |
| 3: prefix only | 1–2 letters missing | 1,636 | 0.2 % | 0 | 1 | 0.01 ms | 0.04 ms |
| 3: prefix only | complete word | 972 | 0.8 % | 0 | 1 | 0.01 ms | 0.04 ms |
| 2: prefix ∪ typo | 3+ letters missing | 2,562 | 18.0 % | 5 | 112 | 2.06 ms | 42.41 ms |
| 2: prefix ∪ typo | 1–2 letters missing | 1,636 | 83.9 % | 6 | 71 | 11.75 ms | 54.66 ms |
| 2: prefix ∪ typo | complete word | 972 | 94.9 % | 7 | 84 | 17.33 ms | 75.75 ms |
| 2 + open end | 3+ letters missing | 2,562 | 94.8 % | 43 | 4,904 | 8.17 ms | 59.88 ms |
| 2 + open end | 1–2 letters missing | 1,636 | 93.2 % | 12 | 222 | 19.38 ms | 71.02 ms |
| 2 + open end | complete word | 972 | 94.9 % | 8 | 113 | 26.17 ms | 86.86 ms |

Readings:

- **Option 3 finds nothing once there is a typo**, as expected. It is the baseline, not a candidate.
- **Option 2, the current proposal, works only near the end of the word**: 18 % while three or more letters are
  missing, 84 % at one or two, 95 % on the complete word. P3 named the gap ("it will not find a term that has a
  typo *and* is unfinished"); it is the majority of keystrokes.
- **The open end closes the gap**: 93–95 % at every keystroke. Its price is candidates. A short mistyped
  fragment opened at the end reaches whole subtrees (p95 of 4,904 terms with three or more letters missing).
  The open end therefore needs the expansion cap and the ranking cascade of P3 §4.4. A minimum fragment length
  before the open end starts is the cheap alternative.
- **About 5 % stays out of reach even on the complete word.** Those are suffix typos that knock the stem off its
  rule path, measured directly in §10.3.
- **The open-ended walk is exact.** `FuzzyDictionaryWalkTest` (nested `OpenEnded`) checks that it returns exactly
  the linear scan's set and reports the smallest distance over a term's prefixes. The seek logic of the finite
  walk carries over unchanged; the walker's javadoc gives the argument.

**Latency — what the milliseconds are made of.** They are far above the per-walk numbers of §5.3, so a separate
probe split them, over all 8,271 mistyped sampled forms on the same 865,287-key dictionary, second warm round:

| step | cost |
|---|---|
| query analysis (`czech-search`) | 8 µs per word |
| stem hypotheses per mistyped word | 2.56 on average |
| distance 1, per hypothesis: automata built / walk | 33 µs / 41 µs (100 keys read) |
| distance 2, per hypothesis: automata built / walk | 1,247 µs / 952 µs (1,693 keys read, 1,683 seeks) |
| Lucene `LevenshteinAutomata#toAutomaton(2, prefix)` alone | 433 µs |
| `CharacterRunAutomaton` over that automaton | 11 µs |

- **One edit fits the budget**: about 0.19 ms per word over all hypotheses, against the 1.5 ms term-leg budget
  of O2.
- **Two edits over every hypothesis do not**: about 5.6 ms per word on this dictionary. Almost half of it is
  **building** the automaton, not walking. Lucene's `toAutomaton(2)` costs 0.43 ms in Java, and the reference
  walker builds it twice, once for the walk and once as the distance-2 acceptor that classifies hits. Lucene's
  own `FuzzyQuery` pays the same construction per query (`FuzzyAutomatonBuilder#buildAutomatonSet`).
- **The levers, in order of cost:** build the widest automaton once and classify hits by the DP of
  `EditDistances` instead of by narrower automata (saves about 0.43 ms per hypothesis); give distance 2 only to
  the primary hypothesis (§7.4); read the thresholds from the stem (§10.3, fewer words reach distance 2).
  Caching automata across keystrokes helps little, because the fragment changes with every keystroke.

### 10.3 Thresholds on a stemmed dictionary

`TypoThresholdMeasurementTest` measures the fork of §7.3 on 3,000 sampled forms of three or more letters. Each
form gets one typo in each of three position classes relative to its index stem: the **first** letter, inside the
**stem** (before the end of the prefix the form shares with its stem), and in the **suffix**. The correctly spelt
form is the control. Each stem hypothesis is walked once at distance 2, and each policy admits the hits within
the budget it assigns.

**(a) Typed length against stem length.** Stems are shorter than what the user types, as §7.3 predicted:

| typed − stem (letters) | 0 | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---|---|---|---|---|---|---|
| share of forms | 7.0 % | 39.7 % | 19.0 % | 26.0 % | 5.3 % | 2.7 % | 0.3 % |

The two bases disagree on the budget for **637 of 3,000 forms (21.2 %)**: 620 get two edits by typed length and
one by stem length, and 17 get one against none.

**(b) Does the typo survive stemming?** The best distance between the target stem and any hypothesis of the
mistyped word:

| typo position | words | absorbed (0) | 1 | 2 | out of reach (> 2) |
|---|---|---|---|---|---|
| none (control) | 3,000 | 100.0 % | 0.0 % | 0.0 % | 0.0 % |
| first letter | 3,000 | 0.0 % | 0.9 % | 0.0 % | 99.1 % |
| stem | 2,982 | 0.3 % | 97.0 % | 2.1 % | 0.6 % |
| suffix | 2,358 | 23.6 % | 42.7 % | 15.6 % | 18.1 % |

**(c) The three length bases.** Found is the share of words whose target is admitted; noise counts the other
dictionary terms admitted:

| typo position | length base | found | noise mean | noise p50 | noise p95 |
|---|---|---|---|---|---|
| none (control) | typed | 100.0 % | 46.2 | 20 | 185 |
| none (control) | hypothesis | 100.0 % | 25.7 | 13 | 96 |
| stem | typed | 98.9 % | 25.3 | 7 | 118 |
| stem | hypothesis | 97.9 % | 11.7 | 4 | 50 |
| suffix | typed | 80.4 % | 14.5 | 4 | 63 |
| suffix | hypothesis | 78.4 % | 10.3 | 3 | 42 |
| first letter | typed | 0.9 % | 9.6 | 0 | 50 |
| first letter | hypothesis | 0.9 % | 2.5 | 0 | 14 |

The combined base `min(edits by typed, edits by stem + 1)` produced the typed base's numbers to the decimal in
every row. It is left out of the table and can be dropped from the options.

Readings:

- **A typo in the stem survives at its true distance** (97 % at distance 1), so for stem typos the threshold base
  barely matters.
- **A typo in the suffix is the hard class, and §7.3 point 3 is real.** About a quarter of suffix typos are
  absorbed by the stemmer for free. But **18.1 % are out of reach at any budget**: the typo knocks the stemmer
  off its rule path, and the hypotheses keep the suffix it no longer recognizes. Examples from the run:
  `agitujícího` typed `agitujiicho` gives `agitujiich` against the index stem `agitujik`, and `anxiolytikem`
  typed `anxiolytikexm` is left unstemmed against `anxiolytik`. Over all stem and suffix typos that is
  **about 8.3 %** (about 445 of 5,340) that no threshold can recover. Their surface distance is one by construction. This is
  the correctness argument §7.3 asked for: **a surface-form lane in the dictionary** (P5 §6 variant 3) would
  reach them at distance one, and nothing on the query side can.
- **Reading the thresholds from the typed word buys 1–2 points of recall for about twice the noise.** The typed
  base finds 98.9 % against 97.9 % of stem typos and 80.4 % against 78.4 % of suffix typos. It admits 25.3
  against 11.7 terms on average for a stem typo, and 46.2 against 25.7 for a correctly typed word. The stem
  base is also cheaper, because 21 % of words drop from two edits to one, the expensive band of §10.2. This
  evidence leans against P3's untested choice of the typed word; the decision stays open for a product call
  against the examples of §10.5.
- **Typo tolerance costs noise even when there is no typo.** A correctly typed word still admits 13–20 other
  terms at the median. The ranking lanes put the exact hit first, but every query pays for the expansion.
  Typesense's `typo_tokens_threshold` (look for typos only when the exact match finds too little, prior-art
  §3.6) is the lever if that cost matters.
- **The frozen first letter works as U5 intends:** 99.1 % of first-letter typos are not found.

**(d) How many terms a query word expands to** — the input for the cap of §7.1, over all hypotheses of the
mistyped words:

| within distance | p50 | p90 | p99 | max | more than 10 | more than 50 |
|---|---|---|---|---|---|---|
| 1 | 1 | 10 | 35 | 101 | 9.9 % | 0.3 % |
| 2 | 8 | 119 | 410 | 1,220 | 44.3 % | 20.3 % |

The cap is a distance-2 question. A cap of 50, Lucene's default, binds 0.3 % of words at one edit and 20 % at
two. That matches Meilisearch's split of 150 one-typo and 50 two-typo derivations (prior-art §3.5). The value
and the behaviour when it binds are still open, but they now have a distribution to be chosen against.

### 10.4 Behaviour examples (§9 step 1)

`TypoToleranceBehaviourTest` writes the behaviour we want as fourteen examples over an 18-product catalog. Each
example has a query, the expected products in order, and the products that must not match. The catalog is
indexed through the production `czech` chain and queries go through `czech-search`. Expansion is
`TypoExpansion` with the §7.1 thresholds on the typed length. An unfinished word uses option 2 plus the open
end. The ranking is a test-side model of the §8.2 lane order: every query word must match, then fewer typos,
then better exactness, then primary key. It is fast and runs in the default loop.

| requirement | query | expected | must not match |
|---|---|---|---|
| U3 missing diacritics are not a typo | `cerne kalhoty` | Černé pánské kalhoty | Modré dámské kalhoty, Černá kožená kabelka |
| U3 wrong diacritics are not a typo | `čérne kalhóty` | Černé pánské kalhoty | Modré dámské kalhoty |
| U1 one substitution | `kalhoti` | both trousers | — |
| U2 adjacent transposition | `kalhtoy` | both trousers | — |
| U2 transposition inside the stem | `bnuda` | both jackets | — |
| U3 + U2 together | `cerne kalhtoy` | Černé pánské kalhoty | Modré dámské kalhoty |
| U4 no typo on three letters | `pes` | Plyšový pes | Pás na nářadí, Kožený pásek |
| U5 no typo on the first letter | `vunda` | nothing | both jackets |
| §7.5 digits match exactly | `ab1234` | Nabíječka AB1234 | Nabíječka AB1284 |
| U7 exact above typo | `triko` | Triko s dlouhým rukávem, then Bílé tričko | — |
| must-match | `panska mikina` | Pánská mikina s kapucí | Dětská mikina, Pánská zimní bunda |
| typing: prefix first | `pansk…` | the three men's products, then Kožený pásek | the women's products |
| U7 typing: exact above prefix | `kabat…` | Dámský vlněný kabát, then Kabátek pro panenky | — |
| U6 typing: unfinished word with a typo | `mikna…` | both sweatshirts | — |

All fourteen pass. One needed its expectation corrected after the first run, and it is a finding rather than a
fix. While the user types `pansk`, the proposal also returns **Kožený pásek**. Its stem `pask` is one deletion
from `pansk`, and a five-letter word gets one typo. It ranks last, below every prefix match, so the lane order
does its job. But it is the noise of §10.3 made visible: the shortest words that still get a typo pull in their
neighbours. Raising `minLengthOneTypo` to 5 (Meilisearch's value) or 6 would remove it. That is a threshold
question for the examples to settle.

### 10.5 What this changes

- **Prefix input (§10.1):** `prefix(fragment) ∪ prefix(hypotheses)` replaces "prefix of the fragment" as the
  proposal, and the unfinished last word bypasses the stop filter.
- **Prefix × typo (§10.2):** option 2 alone misses most mistyped keystrokes. The open-ended walk fixes recall and
  is implemented and verified in the reference walker. It brings candidate volume that the cap and the ranking
  have to bound.
- **Length base (§10.3):** measured. The stem base gives half the noise and a cheaper distance-2 band for 1–2
  points of recall; the combined base adds nothing. The decision is now a product call with numbers.
- **Surface-form lane:** 8.3 % of typos after the first letter are out of reach on a stem-only dictionary. This
  is the typo-correctness argument for P5 §6 variant 3, to be weighed with P1 K3's size measurement.
- **Cap (§10.3 d):** a distance-2 question; a cap of 50 binds a fifth of words at distance 2 and almost none at
  distance 1.
- **Latency (§10.2):** distance 1 fits the suggester budget, distance 2 over every hypothesis does not. Half of
  the distance-2 cost is automaton construction, which has a cheap fix.
- **The behaviour examples exist and pass** (§10.4). They now guard every later change to the thresholds.

### 10.6 Thresholds in Slovak, Polish and Romanian (2026-10-16)

`LanguageTypoThresholdMeasurementTest` repeats §10.3 for the other three folding languages. Its method body is
`TypoThresholdMeasurementTest`'s: the same corpus size, typo generator and seed (`Random(7)`), the same four
position classes and the thresholds `TypoThresholds.PROPOSAL`. Czech runs through the same harness as a
reproduction check. Every Czech number of §10.3 came out identical, to the decimal.

**Setup.**

- **Forms.** `HunspellWordForms` is the language-neutral sibling of `CzechWordForms`. It expands the `sk_SK`,
  `pl_PL` and `ro_RO` fixtures with Lucene's `WordFormGenerator`. All three `.aff` files declare `SET UTF-8`, and
  all three load and expand without errors. Forms are lowercased, and any form with a non-letter is dropped, as
  for Czech.
- **Index term.** `LanguageStemDictionary` takes no stemmer shortcut. It runs every generated form through the
  **production index analyzer** of the language, as `FulltextAnalyzerRegistry#getIndexAnalyzer` resolves it, so
  the term needs no separate check against the analyzer. No form was split into two tokens. The forms the chain
  drops are stop words; Slovak has no stop filter, so it drops none.
- **Sample and corpus.** The sample is one distinct form in 500, chosen by hash, as in Czech. The Romanian fixture
  yields too few forms for that, so Romanian samples one form in 250. The corpus is then 3,000 forms evenly spread
  over the eligible sample (three or more letters) for every language.
- **Queries.** Queries go through the production search analyzer, so the stem hypotheses are the ones the
  language's variant stemmer emits. The typed length is read from `TypoExpansion.analyze`, which folds the surface
  with `CzechLexicon.fold`, that is with `ASCIIFoldingFilter.foldToASCII`. This is the same function the search
  chains apply after lowercasing. It maps Polish `ł` to `l`. Romanian comma-below `ș`/`ț` and cedilla `ş`/`ţ` both
  fold to `s`/`t`, so the chain's `CommaBelowNormalizationFilter` cannot change the folded result. The test
  asserts that every folded corpus form is pure ASCII: 0 exceptions in every language.

| | cs | sk | pl | ro |
|---|---|---|---|---|
| forms generated | 4,832,620 | 1,944,324 | 3,581,871 | 1,267,517 |
| dropped by the index chain (stop words) | 221 | 0 | 264 | 278 |
| dictionary (distinct folded stems) | 865,287 | 444,615 | 484,531 | 311,364 |
| dictionary build (production analyzer per form) | 30.5 s | 5.5 s | 17.7 s | 5.9 s |
| sample (1 form in …) | 8,443 (500) | 3,471 (500) | 7,028 (500) | 4,220 (250) |
| corpus | 3,000 | 3,000 | 3,000 | 3,000 |

The four languages take about three and a half minutes together:

```shell
mvn -nsu -pl evita_test/evita_functional_tests test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='LanguageTypoThresholdMeasurementTest'
```

**(a) Typed length against stem length.**

| typed − stem (letters) | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7+ |
|---|---|---|---|---|---|---|---|---|
| cs | 7.0 % | 39.7 % | 19.0 % | 26.0 % | 5.3 % | 2.7 % | 0.3 % | 0.0 % |
| sk | 13.3 % | 32.1 % | 28.6 % | 16.0 % | 7.5 % | 2.4 % | 0.0 % | 0.0 % |
| pl | 5.5 % | 28.4 % | 18.8 % | 27.2 % | 7.8 % | 6.2 % | 2.6 % | 3.5 % |
| ro | 13.8 % | 18.6 % | 14.4 % | 18.3 % | 19.3 % | 5.9 % | 7.2 % | 2.4 % |

The two length bases disagree on the budget as follows:

| | cs | sk | pl | ro |
|---|---|---|---|---|
| forms where typed and stem bases disagree | 637 (21.2 %) | 459 (15.3 %) | 567 (18.9 %) | 1,072 (35.7 %) |
| two edits by typed, one by stem | 620 | 438 | 547 | 1,047 |
| one edit by typed, none by stem | 17 | 21 | 15 | 24 |
| two edits by typed, none by stem | 0 | 0 | 5 | 1 |

**Stem variants per word.** This is the mean number of hypotheses the search chain emits per query word. Each
hypothesis costs one walk.

| | cs | sk | pl | ro |
|---|---|---|---|---|
| correctly typed | 2.71 | 1.07 | 2.18 | 2.79 |
| typo in the stem | 2.67 | 1.07 | 2.16 | 2.72 |
| typo in the suffix | 1.91 | 1.02 | 1.71 | 1.98 |
| over all mistyped words | 2.47 | 1.06 | 2.04 | 2.53 |

**(b) Does the typo survive stemming?** Each cell is the best distance between the target stem and any hypothesis,
given as absorbed (0) / 1 / 2 / out of reach (> 2):

| typo position | cs | sk | pl | ro |
|---|---|---|---|---|
| none (control) | 100.0 / 0.0 / 0.0 / 0.0 % | 100.0 / 0.0 / 0.0 / 0.0 % | 100.0 / 0.0 / 0.0 / 0.0 % | 100.0 / 0.0 / 0.0 / 0.0 % |
| first letter | 0.0 / 0.9 / 0.0 / 99.1 % | 0.0 / 1.0 / 0.0 / 99.0 % | 0.0 / 1.0 / 0.0 / 99.0 % | 0.0 / 1.0 / 0.0 / 99.0 % |
| stem | 0.3 / 97.0 / 2.1 / **0.6 %** | 0.1 / 97.4 / 2.2 / **0.3 %** | 0.0 / 97.5 / 1.8 / **0.7 %** | 0.0 / 92.1 / 2.8 / **5.1 %** |
| suffix | 23.6 / 42.7 / 15.6 / **18.1 %** | 23.0 / 44.6 / 16.9 / **15.4 %** | 20.5 / 35.5 / 16.2 / **27.8 %** | 18.6 / 28.1 / 15.3 / **38.0 %** |
| words (stem / suffix) | 2,982 / 2,358 | 2,990 / 2,267 | 2,995 / 2,444 | 2,984 / 2,297 |
| **out of reach, stem and suffix together** | 445 of 5,340 (**8.3 %**) | 358 of 5,257 (**6.8 %**) | 700 of 5,439 (**12.9 %**) | 1,026 of 5,281 (**19.4 %**) |

Examples from the run, given as form → typed → hypotheses, against the index stem:

- Slovak: `barokovom` typed `barokovmo` → `[barokovm]`, index `bark`; `bazénoch` typed `bazenohc` →
  `[bazenohk]`, index `bazen`.
- Polish: `arabach` typed `arabahc` → `[arabahc]`, index `arab`; `amnestiami` typed `amnestiamxi` →
  `[amnestiamx, amnestiamxi]`, index `amnest`.
- Romanian, suffix typo: `acetonemicelor` typed `acetonemicelro` → `[acetonemicelro]`, index `acetonem`.
- Romanian, stem typo: `aclimata` typed `aclmiata` → `[aclmiata, aclmiat]`, index `aclim`.

**(c) Length bases.** `COMBINED` gave the typed base's *found* to the decimal again in every row of every language.
Its noise is equal or up to 1.2 terms lower (Polish stem typos: 11.6 against 12.8). It is left out, as in §10.3.

Found:

| typo position | base | cs | sk | pl | ro |
|---|---|---|---|---|---|
| none (control) | typed / hypothesis | 100.0 / 100.0 % | 100.0 / 100.0 % | 100.0 / 100.0 % | 100.0 / 100.0 % |
| first letter | typed / hypothesis | 0.9 / 0.9 % | 1.0 / 1.0 % | 1.0 / 1.0 % | 1.0 / 1.0 % |
| stem | typed / hypothesis | 98.9 / 97.9 % | 99.2 / 98.2 % | 98.9 / 97.7 % | 93.8 / 93.1 % |
| suffix | typed / hypothesis | 80.4 / 78.4 % | 83.5 / 82.5 % | 71.5 / 70.5 % | 60.6 / 58.8 % |

Noise, given as mean / p50 / p95:

| typo position | base | cs | sk | pl | ro |
|---|---|---|---|---|---|
| none (control) | typed | 46.2 / 20 / 185 | 25.9 / 8 / 109 | 22.8 / 8 / 92 | 38.1 / 13 / 170 |
| none (control) | hypothesis | 25.7 / 13 / 96 | 13.9 / 6 / 59 | 11.2 / 6 / 40 | 11.9 / 9 / 32 |
| first letter | typed | 9.6 / 0 / 50 | 3.0 / 0 / 15 | 7.1 / 0 / 34 | 13.8 / 1 / 75 |
| first letter | hypothesis | 2.5 / 0 / 14 | 1.1 / 0 / 6 | 1.4 / 0 / 8 | 2.4 / 0 / 13 |
| stem | typed | 25.3 / 7 / 118 | 13.4 / 3 / 64 | 12.8 / 3 / 52 | 20.8 / 5 / 96 |
| stem | hypothesis | 11.7 / 4 / 50 | 6.2 / 2 / 27 | 4.7 / 2 / 19 | 5.6 / 3 / 18 |
| suffix | typed | 14.5 / 4 / 63 | 7.6 / 1 / 34 | 5.9 / 1 / 24 | 11.3 / 4 / 46 |
| suffix | hypothesis | 10.3 / 3 / 42 | 5.5 / 1 / 25 | 3.8 / 1 / 16 | 5.4 / 3 / 19 |

**(d) Terms within distance 1 and 2 per query word**, over all hypotheses of the mistyped words. Each cell is
p50 / p90 / p99 / max, then the share above 10 and the share above 50:

| within | cs | sk | pl | ro |
|---|---|---|---|---|
| 1 | 1 / 10 / 35 / 101; 9.9 %, 0.3 % | 1 / 6 / 21 / 52; 4.3 %, 0.0 % | 1 / 5 / 23 / 71; 4.1 %, 0.2 % | 2 / 9 / 33 / 86; 8.6 %, 0.2 % |
| 2 | 8 / 119 / 410 / 1,220; 44.3 %, 20.3 % | 3 / 59 / 260 / 594; 31.3 %, 11.3 % | 4 / 50 / 274 / 621; 29.5 %, 9.9 % | 8 / 103 / 352 / 728; 42.8 %, 18.1 % |

**The M7 invariant holds in all four languages.** Every correctly typed corpus form finds its own index stem
among its hypotheses at distance 0: 3,000 of 3,000 in each language, with no misses to report.

Readings:

- **Slovak behaves like Czech, only cheaper.** The typo classes match Czech within three points: 15.4 % of suffix
  typos are out of reach (Czech 18.1 %), 6.8 % over stem and suffix (Czech 8.3 %). It emits 1.06 hypotheses per
  mistyped word against 2.47. It has about half the noise in every row, and a dictionary half the size. The low
  variant count is a property of `SlovakVariantStemmer`, and the M7 check shows it loses nothing to it.
- **Polish loses more suffix typos.** 27.8 % of its suffix typos are out of reach, and found drops to 71 %. Polish
  forms carry long suffix chains: 3.5 % of forms are seven or more letters longer than their stem, as with
  `adiustowałybyśmy` → `adiustow`. A typo inside such a chain leaves the word unstemmed. Stem typos behave like
  Czech (0.7 % out of reach). Noise and expansion are the lowest of the four.
- **Romanian is materially worse, and it is the only language where stem typos break too.** 38.0 % of suffix typos
  and 5.1 % of stem typos are out of reach, against 18.1 % and 0.6 % in Czech, and only 60.6 % of suffix typos are
  found. That makes 19.4 % over stem and suffix, more than twice Czech's 8.3 %. A plausible mechanism, not
  isolated by this run: Snowball's Romanian rules are gated by the R1/R2 regions, which are counted from vowel and
  consonant positions, so a typo inside the stem moves the region and changes which suffix is removed (`aclmiata`
  → `aclmiat` against `aclim`). Romanian stems are also the shortest relative to the typed word, so its bases
  disagree for 35.7 % of forms.
- **The TYPED versus HYPOTHESIS conclusion of §10.3 holds in every language, and is stronger outside Czech.** The
  typed base buys 0.7–1.8 points of recall in stem and suffix typos. It costs roughly twice the noise in Slovak and
  Polish, and about three to four times in Romanian (a mean of 38.1 against 11.9 on a correctly typed word, 20.8
  against 5.6 on a stem typo). `COMBINED` still adds nothing.
- **For §9 item 4 (the surface-form lane):** the share of typos after the first letter that a stem-only dictionary
  cannot reach at any budget is 6.8 % for Slovak, 8.3 % for Czech, 12.9 % for Polish and 19.4 % for Romanian. The
  argument for the lane holds for every folding language and grows with how aggressively the stemmer strips. In
  Romanian, one typo in five after the first letter depends on it. The lane's size is still P1 K3's to measure, and
  these dictionaries do not answer it.
- **The cap distribution (§10.3 d) is no harder anywhere than in Czech.** A cap of 50 at distance 2 binds 9.9 %
  (Polish) to 20.3 % (Czech) of words. At distance 1 it binds at most 0.3 % of words.

### 10.7 The measurements repeated on a real corpus (2026-10-20)

Every number before this section comes from a synthetic catalog built on the hunspell fixture. This section repeats
the measurements that decide §7.3, §7.4, the expansion cap and §9 items 1, 4, 5 and 7 on **corpus A of P1**, a
production CMS catalog of 972,611 articles. The vocabulary, the word frequencies and the postings are all real.

**Setup.**

- **Text.** `CorpusTextExtractionTest` opens a copy of the catalog in an embedded engine and writes `title` and
  `perex` (localized attributes) and `body` (associated data) of every article to a flat file. Markup is stripped
  with Lucene's `HTMLStripCharFilter`, which is P1's gate arm. The run extracted 2,884,415 fields, the same count as
  P1 part 1, and 2.1 billion characters, in 246 s.
- **Dictionary.** `CorpusTypoMeasurementTest` runs every field through the **production Czech index chain**, which
  produces 229,182,398 tokens. The postings are 159,947,638 article ids in 804,858 stems, one dictionary per locale
  as P1 builds it. A second dictionary holds the **folded surface forms**: 1,601,674 keys with 14.3 million
  characters, against 6.4 million for the stems. A folded surface form maps to the stem of its most frequent
  accented form. Analysis takes 179 s and building the trees 4 s.
- **Query words.** The words are drawn from the corpus itself, because there is no query log. Two cohorts of 3,000
  words each are drawn from words of three or more letters with letters only:
  - **by token** — a word is drawn as often as it occurs, so frequent words dominate;
  - **by vocabulary** — a uniform draw over distinct words occurring at least five times, which is closer to the
    content words people search for.

  The typos are the synthetic ones of §10.3: the same four kinds and the same three position classes. Neither
  cohort is a query distribution. The two bracket the effect.
- **Entity leg.** The latency replay ORs the postings of every expanded term per word with the production
  `OrFormula`, then ANDs the words with `AndFormula`. It does no scoring, so the cost is a lower bound for phase 1.
  The corpus is long prose, so its postings are denser than an e-commerce catalog of the same entity count.
- **Machine and variance.** The machine is the same development box as §11.3, and it is not quiet. Three runs of
  the latency block differed by up to about 15 % at p99. The tables below are from the last run.

```shell
mvn -nsu -pl evita_test/evita_functional_tests test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='CorpusTextExtractionTest' -Dsurefire.maxHeapSize=10g \
  -Dfulltext.corpus.dataDir=<a copy of the storage directory> -Dfulltext.corpus.catalog=<catalog> \
  -Dfulltext.corpus.output=<flat file>
mvn -nsu -pl evita_test/evita_functional_tests test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='CorpusTypoMeasurementTest' -Dsurefire.maxHeapSize=14g -Dfulltext.corpus.file=<flat file>
```

**(a) Length base and the surface-form lane — §7.3, §9 items 3 and 4.** The threshold reads the stem
(`HYPOTHESIS`) or the typed word (`TYPED`), with thresholds `TypoThresholds.PROPOSAL`. The **lane** row adds a walk
over the folded surface forms with the typed word and its typed-length budget, and maps each hit to its stem.
`COMBINED` came out within 0.6 noise of `TYPED` in every row and is left out.

| typo position | length base | found, by token | noise mean | p95 | found, by vocabulary | noise mean | p95 |
|---|---|---|---|---|---|---|---|
| none (control) | typed | 100.0 % | 61.4 | 159 | 100.0 % | 57.3 | 220 |
| none (control) | stem | 100.0 % | 29.9 | 72 | 100.0 % | 18.8 | 58 |
| none (control) | stem + lane | 100.0 % | 35.1 | 80 | 100.0 % | 23.9 | 70 |
| stem | typed | 93.8 % | 35.9 | 116 | 97.5 % | 37.1 | 159 |
| stem | stem | 86.4 % | 14.1 | 47 | 94.7 % | 9.9 | 39 |
| stem | stem + lane | **95.5 %** | 17.2 | 52 | **98.8 %** | 13.0 | 48 |
| suffix | typed | 86.5 % | 24.8 | 82 | 83.8 % | 17.7 | 70 |
| suffix | stem | 84.0 % | 16.8 | 52 | 81.3 % | 8.9 | 36 |
| suffix | stem + lane | **99.0 %** | 18.9 | 57 | **99.4 %** | 10.7 | 41 |

| | by token | by vocabulary | hunspell fixture (§10.3) |
|---|---|---|---|
| typed and stem bases disagree on the budget | 27.6 % | 29.7 % | 21.2 % |
| stem variants per mistyped word | 2.08 | 2.28 | 2.47 |
| stem and suffix typos out of reach on stems | **3.0 %** (147 of 4,906) | **5.8 %** (295 of 5,113) | 8.3 % |
| suffix typos out of reach on stems | 6.6 % | 13.0 % | 18.1 % |
| of those, recovered by the lane | **all** (147) | **all** (295) | — |
| terms within distance 1 per word, p50 / p99, over 50 | 12 / 135, 18.3 % | 3 / 109, 6.7 % | 1 / 35, 0.3 % |
| terms within distance 2 per word, p50 / p99, over 50 | 149 / 1,542, 66.4 % | 28 / 1,398, 41.9 % | 8 / 410, 20.3 % |

Every correctly typed word reached its own stem at distance 0 (the M7 invariant). A first-letter typo is reached
0.8–1.0 % of the time, as on the fixture.

**(b) Which stem variant finds a mistyped word — §7.4.** The *straight* variant is what Lucene's `CzechStemmer`
makes of the folded word, the one path through the rules that does not fork. It was among the hypotheses for
every word. The table covers stem and suffix typos with the stem base.

| | by token | by vocabulary |
|---|---|---|
| words with two or more variants | 74.0 % | 78.9 % |
| target reached by the straight variant only | 49.0 % | 41.0 % |
| by other variants only | 8.6 % | 9.1 % |
| by both | 27.9 % | 39.5 % |
| by none | 14.5 % | 10.5 % |

| policy | found, by token | noise mean | noise on correct words | found, by vocabulary | noise mean | noise on correct words |
|---|---|---|---|---|---|---|
| every variant, full budget | **85.5 %** | 15.5 | 30.3 | **89.5 %** | 9.4 | 19.0 |
| other variants one edit less | 78.6 % | 12.0 | 21.7 | 83.7 % | 7.4 | 14.4 |
| straight variant only | 77.7 % | 11.9 | 21.4 | 80.8 % | 7.3 | 14.1 |

**(c) The cap of 50 — where it binds and what it should keep.** Under the stem base, a cap of 50 binds on 5.3 %
(by token) and 2.5 % (by vocabulary) of mistyped finished words when it counts the union of all variants of the
word. Counted per variant, it binds on 3.9 % and 1.9 %. The order the cap keeps decides whether the target
survives:

| order of a 50-term cap per word (finished words) | target cut off, by token | by vocabulary |
|---|---|---|
| distance, then alphabetical | 37 of 4,191 (0.88 %) | 11 of 4,606 (0.24 %) |
| distance, then document frequency | **1 of 4,191 (0.02 %)** | **1 of 4,606 (0.02 %)** |
| document frequency only | 0 of 4,191 | 1 of 4,606 |

The unfinished last word puts a third leg into the same list, the prefix completions. In the submitted search, one
cap of 50 over all three legs ranks every prefix term above every typo term, and the completions crowd the typo
match out. One cap per leg fixes that, as Meilisearch caps its derivations per kind: all exact terms, the best 50
prefix terms and the best 50 typo terms.

| submitted search, two edits | terms p50 / p99 | mistyped word's target found (461 queries) |
|---|---|---|
| no cap | 145 / 2,840 | 91.8 % |
| one cap of 50 over all legs | 66 / 150 | 88.5 % |
| 50 per leg | 89 / 204 | **91.5 %** |

**(d) The as-you-type budget on real postings — §9 item 1.** The replay is 1,000 queries of one to three words
from the by-token cohort. Half of them carry one typo after the first letter. Finished words get one typo, and the
last word gets the §7.8 rules. The table gives total time per keystroke, p50 / p95 / p99 in ms, with the share of
keystrokes over 5 ms and the recall of the unfinished word without and with a typo.

| fragment | proposal (open end from 4) | open end from 5 | open end from 5, 50-term cap | open end from 5, 50 per leg |
|---|---|---|---|---|
| 3 | 1.02 / 9.43 / 14.79, 9.8 %; 98.6 / 60.2 % | 0.93 / 9.14 / 13.95, 9.0 %; 98.6 / 60.2 % | 0.81 / 5.46 / 8.61, 5.5 %; 92.7 / 54.1 % | 1.15 / 7.21 / 11.84, 8.9 %; 92.7 / 54.1 % |
| 4 | 2.78 / 14.64 / 21.60, 23.6 %; 100.0 / 98.7 % | 0.97 / 3.34 / 5.48, 1.6 %; 99.8 / 65.4 % | 0.70 / 2.19 / 3.33, 0.2 %; 97.9 / 59.8 % | 1.22 / 4.79 / 7.53, 4.0 %; 97.9 / 63.1 % |
| 5 | 0.95 / 3.85 / 6.87, 2.3 %; 99.3 / 96.6 % | 0.95 / 3.49 / 6.33, 1.7 %; 99.3 / 96.6 % | 0.77 / 2.05 / 3.16, 0.4 %; 98.7 / 89.7 % | 1.22 / 4.74 / 7.67, 4.2 %; 98.7 / 94.0 % |
| 6–7 | 0.65 / 1.75 / 3.30, 0.3 %; 97.5 / 97.2 % | 0.63 / 1.71 / 2.69, 0.1 %; 97.5 / 97.2 % | 0.56 / 1.23 / 1.74, 0 %; 97.5 / 95.8 % | 0.76 / 3.05 / 4.66, 0.7 %; 97.5 / 96.5 % |
| 8+ | 0.65 / 1.58 / 3.09, 0.2 %; 94.9 / 90.5 % | 0.62 / 1.32 / 1.88, 0 %; 94.9 / 90.5 % | 0.57 / 1.10 / 1.39, 0 %; 94.9 / 90.5 % | 0.74 / 2.41 / 3.74, 0.3 %; 94.9 / 90.5 % |

The split between expansion and postings at three letters, with the proposal, is 3.14 ms of term expansion and
11.75 ms of postings union at p99, over 4,679 terms at p95. The 50-term cap moves the cost from the union into
the harness's expansion, which sorts every term of the prefix by document frequency before cutting: 6.35 ms. A
keystroke that finishes a word costs at most 1.3 ms at p99 in every configuration.

**(e) Two edits in the submitted search — §9 item 5.**

| submitted search | term expansion p50 / p95 / p99 | total with postings p50 / p95 / p99 | over 25 ms | mistyped target found | in words of 8+ letters (181) |
|---|---|---|---|---|---|
| two edits from 8 letters | 4.69 / 16.95 / 22.22 ms | 6.25 / 19.00 / 24.90 ms | 1.0 % | 91.8 % | 98.3 % |
| one edit everywhere | 0.93 / 1.85 / 2.27 ms | 1.82 / 4.69 / 7.11 ms | 0 % | 90.9 % | 96.1 % |
| two edits, 50 per leg | 4.93 / 17.94 / 23.88 ms | 6.35 / 19.33 / 26.23 ms | 1.3 % | 91.5 % | 98.3 % |

**(f) The two noise refinements — §9 item 7.** The sample is every keystroke of a correctly typed word with three
or more letters typed and at least one still missing: 5,551 keystrokes.

- **Mid-ending keystrokes.** `prefix(fragment) ∪ prefix(variants)` reaches the target on 97.9 % of keystrokes. A
  prefix scan of the surface-form lane reaches the other 2.1 %, and no keystroke is left unreached. The fixture
  measured 11.2 % unreached (§10.1), with every generated form weighted equally.
- **The length guard on `prefix(variants)`.** Variants more than one letter shorter than the fragment add terms
  that nothing else reaches on 8.3 % of keystrokes: p95 28, p99 397. Dropping them loses the target on 1.48 % of
  keystrokes. Those are keystrokes whose own word is in the corpus, so the lane's prefix scan reaches them by
  construction (not measured separately).

**(g) Where the bands start — the breakpoint sweep (2026-10-21).** This is the question §7.1 leaves open: from
how many letters a word gets one edit, and from how many two. Stems are budgeted by the stem's length and the
surface-form lane by the typed length, the recommended combination from (a). First letter frozen, words with a digit
exact. Sample: the by-vocabulary cohort, 3,000 words of every length, each also with one typo and with two typos
after the first letter. *Noise* is the number of wrong stems admitted; *two-edit walks* is the share of words for
which some walk runs with two edits — the cost driver.

| one edit from | two edits from | correct word: noise | one typo: found | noise | two typos: found | noise | two-edit walks |
|---|---|---|---|---|---|---|---|
| 3 | 8 | 26.6 | 99.8 % | 13.9 | 75.6 % | 7.9 | 63.1 % |
| **4** | 6 | 67.8 | 98.5 % | 38.5 | 92.3 % | 24.3 | 88.8 % |
| **4** | 7 | 37.5 | 98.5 % | 19.3 | 84.5 % | 10.7 | 78.2 % |
| **4** | **8** | **24.1** | **98.5 %** | **11.7** | **75.1 %** | **6.3** | **63.1 %** |
| **4** | 9 | 18.4 | 98.5 % | 8.7 | 62.9 % | 4.7 | 48.0 % |
| **4** | 10 | 15.8 | 98.5 % | 7.2 | 52.1 % | 4.1 | 32.8 % |
| **4** | never | 13.8 | 98.5 % | 6.3 | 21.4 % | 3.7 | 0.0 % |
| 5 | 8 | 19.9 | 95.6 % | 9.1 | 74.1 % | 4.7 | 63.1 % |
| 6 | 8 | 16.1 | 89.7 % | 7.3 | 71.8 % | 3.6 | 63.1 % |

The one-typo column does not depend on where two edits start, and the two-typo column barely depends on where one
edit starts; the full grid (one edit from 3–6 × two edits from 6–10 and never) is in the test output.

- **One edit from 4 letters is the knee.** From 3 it buys 1.3 points for 10 % more noise; from 5 it loses 2.9
  points, from 6 it loses 8.8.
- **Where two edits start is a trade between double typos and noise, with no knee.** Each letter earlier gains
  8–12 points on double typos and costs 16–81 % more noise on every correctly typed word. 8 is the convention
  (Algolia 8, Meilisearch 9) and sits in the middle. The share of real queries with two typos in one word is what
  would move it, and there is no query log to read it from.

**(h) The memory of the surface-form lane (2026-10-21).** JOL owned sizes of the harness structures:

| structure | keys | heap |
|---|---|---|
| stem dictionary (B+ tree, one int per key) | 804,858 | 52.3 MB |
| postings of the stems (one bitmap per stem) | 159.9 M ids | 659.0 MB |
| surface-form lane (B+ tree, folded surface → stem id) | 1,601,674 | **106.0 MB** |

The lane costs **+106 MB, 14.9 % on top of the stem dictionary with its postings**, because it holds keys and a
stem id, never postings: a lane hit is resolved to its stem and reads the stem's postings.

How a lane without postings works, and what it leaves open:

- **Structure.** A second sorted dictionary per (collection, locale), keyed by the **folded surface form**
  (`cerna`, `kabelkami`). Its value is the id of the stem the index chain made of that word, nothing else. The
  postings stay where they are, on the stem.
- **Query.** The typo walk and the prefix scan run over both dictionaries: over the stems with the stem's length,
  over the lane with the typed length. Every lane hit is replaced by its stem id. The union of stems from both is
  the word's expansion, and the postings are read once per stem. A lane hit carries its own edit distance, so the
  `TYPO` lane takes the smaller of the two distances that reached a stem.
- **Why that loses nothing.** The search matches stems anyway: a document containing `kabelkami` is found by the
  stem `kabelk`, and so is every other form of the word. Postings on the surface form would only say *which* form
  a document contains, which nothing in the query uses. That is the difference to P1 part 6.2, where surface forms
  were full terms with postings so that a suggester could complete to them.
- **Indexing.** Each analysed token writes its folded surface → stem pair into the lane when the pair is new. No
  per-document work beyond that lookup.
- **Several stems per folded form, removal and display** are resolved in §3.4, which proposes
  `folded␀stem␀accented` keys (every stem kept, 0.64 % of folded forms have more than one; the accented form for
  U9) with a reference counter per key: 141.5 MB, +19.9 %. P1 part 6.2 measured
the other design, surface forms as full terms with their own postings: +717 MB, 2.33× the dictionary. On the
production structure, whose dictionary and postings P1 part 2.4 measured at 0.70 GB for this corpus, the lane
would be roughly +15 %. A catalog of short product names has far fewer distinct surface forms per document, so the
share there is expected lower, but that is not measured.

**(i) Two edits on words of eight or more letters (2026-10-21).** §9 item 5 asks what the two-edit band buys. Two
edits are only ever given to words of eight or more letters, so this sample takes 3,000 such words from the
by-vocabulary cohort, each with one typo and with two typos after the first letter, expanded as a finished word.

| | one typo: found | expansion p50 / p99 | two typos: found | expansion p50 / p99 |
|---|---|---|---|---|
| two edits, typed length, stems only | 94.6 % | 5.1 / 13.4 ms | 79.9 % | 4.6 / 11.8 ms |
| two edits, stem length, stems only | 93.7 % | 4.1 / 12.3 ms | 66.0 % | 3.7 / 11.0 ms |
| two edits, stem length + lane | **100.0 %** | 6.6 / 16.2 ms | **94.5 %** | 6.2 / 15.1 ms |
| one edit, stem length + lane | **100.0 %** | **0.5 / 1.2 ms** | 17.0 % | 0.5 / 1.0 ms |

With the lane, **a single typo never needs the second edit**: one edit finds all of them, at a thirteenth of the
cost. The second edit is there for double typos only, where it lifts recall from 17 % to 94.5 %. It costs about 6
ms per long word at p50 and 16 ms at p99, so a query with two long words approaches the 25 ms budget. The
§10.7 (e) numbers above, taken with single typos, could therefore not show what two edits are for.

**Readings.**

1. **The stem base with a surface-form lane beats the typed base on both axes.** On every typo class and in both
   cohorts it finds more mistyped targets than the typed base (95.5–99.4 % against 83.8–97.5 %) at roughly half
   the noise. On a correct word, the typed base admits 57–61 noise terms and the stem base with the lane 24–35.
   Without the lane, the stem base costs 2.8–7.4 points on stem typos, more than the 1–2 points the fixture showed
   (§10.3). Real text is dominated by short, frequent words whose stems lose a budget band. The lane is what pays
   for the stem base. **§9 items 3 and 4 have the same answer: thresholds read the stem on the stem dictionary,
   and the typed length on the surface-form lane.**
2. **On real text the lane is a recall lane, not an edge case.** 3.0–5.8 % of stem and suffix typos are out of
   reach on stems, fewer than the fixture's 8.3 %, and the lane recovered every one. It also reaches the 2.1 % of
   mid-ending keystrokes no stem input reaches. It doubles the key count (1.60 million against 0.80 million) and
   key characters (2.2×). Its postings are those of the stem, so the extra memory is in the keys and the
   key → stem map, not in the postings.
3. **§7.4: every stem variant gets the full budget.** The variants other than the straight one are the only route
   to the target for 8.6–9.1 % of mistyped words. Trimming their budget costs 6–9 points of recall to save about a
   quarter of the noise. The cap counts the **word**, not the variant, because it bounds the union of postings,
   and that union is per word.
4. **The cap: 50, per leg, best-first by distance, then document frequency.** In that order the cap cut a found
   target once in about 4,400 finished words. Alphabetical order cuts 12–44 times as often. Counted over all legs
   of an unfinished word, it costs 3.3 points of submitted-search recall; per leg it costs 0.3.
5. **As-you-type on real postings (§9 item 1): the budget is met from five letters.** Two levers are measured:
   - **Starting the open-ended walk at five letters** brings four-letter keystrokes from 21.6 ms to 5.5 ms at p99.
     It costs four-letter typo recall, 98.7 % → 65.4 %, which returns on the fifth letter.
   - **The 50-term cap** halves the remaining cost. With both levers, four letters reach 3.3 ms at p99 and every
     length from four letters stays under 3.4 ms at p99.

   **Three letters stays at 8.6–14.8 ms at p99 under every configuration, with 5.5–9.8 % of keystrokes over 5 ms.**
   Neither lever helps there, because the cost is enumerating and merging the thousands of terms a three-letter
   prefix reaches. That is exactly what §12's precomputed short-prefix postings answer, at p99 ≤ 0.1 ms. So the
   real catalog confirms the fixture: precomputation for three letters, or no typo-tolerant prefix leg below four.
6. **Two edits in the submitted search (§9 item 5) fit the budget and buy little.** Two edits from eight letters
   cost 6.3 ms at p50 and 24.9 ms at p99 against 1.8 and 7.1 ms with one edit. That is within the 25 ms phase-1
   budget, but 1 % of queries exceed it. They lift the mistyped-word recall from 90.9 % to 91.8 %, and in words of
   eight or more letters from 96.1 % to 98.3 % (4 of 181 queries). With single synthetic typos, the second edit
   matters only where stemming moved the word a second edit away. Real double typos are not in this corpus.
7. **The length guard on `prefix(variants)` (§9 item 7) is not needed once the lane exists.** It removes noise on
   8.3 % of keystrokes and loses 1.48 % of targets, and the lane would recover those. Without the lane it is a
   loss.

---

## 11. Two query types: the submitted search and the as-you-type query (2026-10-15)

§7.8 and §10 mixed two query types that have different budgets, different inputs and, as measured here,
different answers. This section separates them. Where it disagrees with §7.8, this section is current.

### 11.1 What the two types are

- **Submitted search.** The user types a query and presses Enter, or picks a suggestion; the results page is
  rendered once. Every word is finished. It is an ordinary query with `textMatches` in the filter and
  `relevance()` in the order (query-design §4–§5).
- **As-you-type query.** The query is sent on every keystroke: the suggester's dropdown (completion terms, a
  preview of the first few products, recognized facets) or an instant-results page. The last word is unfinished.
  In evitaDB this is **not a separate API**: query-design §10.2 decided that suggest is a `suggest(n)` require
  inside an ordinary query, so the dropdown's suggestions and its product preview come from one query per
  keystroke. The two types share the query shape and differ in cadence and in budget.

**How the engine tells them apart.** A query carrying `suggest(n)` is as-you-type by definition. Any other query
is a submitted search, unless the caller marks it as typed live — an instant-results page that sends an ordinary
query per keystroke without `suggest`. That mark is the per-query parameter of §7.8 rule 3.

**Budgets** (research §7, P1, P3):

| query type | budget | what it covers |
|---|---|---|
| submitted search | no end-to-end target; phase 1 ≤ 25 ms per 1 million candidates, single thread | scoring the candidate set; term expansion is not budgeted separately |
| as-you-type | p99 ≤ 5 ms per keystroke including typo expansion, ≤ 2 ms without | everything, of which the term leg gets 1.5 ms (O2) |

### 11.2 How the engines handle the two types

Read from the local checkouts on 2026-10-15. The engines fall into two families.

| engine | submitted search | as-you-type |
|---|---|---|
| Elasticsearch | `match` has no prefix and no typo tolerance unless `fuzziness` is set (`MatchQueryBuilder.java:66`) | separate tools: `match_bool_prefix` and `match_phrase_prefix` turn the last term into a prefix, capped at 50 expansions (`MatchBoolPrefixQueryBuilder.java:38-40, 61`); the `search_as_you_type` field precomputes edge n-grams of 1–20 characters (§7.8 rule 5); the completion suggester is a separate in-memory FST that matches the whole input as a prefix; it has no typo tolerance unless `fuzzy` is passed, and with it one edit by default (two configurable), no typo below three characters and the first unit frozen (`FuzzyOptions.java:201-205, 214-216`, Lucene `FuzzyCompletionQuery.java:64-70`); `fuzziness: AUTO` there always resolves to one edit, because no text length is passed (`Fuzziness.java:192`); in `match_bool_prefix` the last, prefix term is never fuzzy |
| OpenSearch | the same as Elasticsearch (prior-art §3.3) | the same as Elasticsearch |
| Solr | `edismax` makes no prefix of its own (`ExtendedDismaxQParser`); a prefix needs explicit `term*` | `SuggestComponent` with its own lookup structures; the infix lookup serves prefixes below 4 characters from index-time n-grams (`AnalyzingInfixLookupFactory.java:95-98`) |
| Lucene | `FuzzyQuery` and `PrefixQuery` as building blocks, no default behaviour | the suggest module: `AnalyzingInfixSuggester` makes only the last token a prefix and none after trailing whitespace (`AnalyzingInfixSuggester.java:668-680`) and has no typo tolerance; `FuzzySuggester` and `FuzzyCompletionQuery` allow one edit by default, two configurable, counted in bytes unless `unicodeAware` (`FuzzyCompletionQuery.java:61-73`) |
| Meilisearch | one search route for both: the last word is a prefix unless a separator follows it (`parse_query.rs:70-112`), typos 1 from 5 and 2 from 9 characters (prior-art §3.5) | the same query; the `prefixSearch` index setting turns prefix off (`index.rs:2173-2176`) |
| Typesense | one search for both: `prefix` true and `num_typos` 2 by default, one typo from 4 and two from 7 bytes (`collection.cpp:9531-9534`, length taken in bytes at `index.cpp:7102-7111`); typos run only as a fallback, see §11.6 | the same query |
| Vespa | the application chooses the operators; no default | the same |

- **The Lucene family separates the types.** Its standard search is exact: no prefix and no typos unless asked.
  Search-as-you-type is a different query type or a different structure, and every suggester that tolerates
  typos defaults to **one edit**, configurable to two (`FuzzyCompletionQuery`, `FuzzySuggester`, Elasticsearch's
  completion suggester).
- **The product engines merge them.** Meilisearch and Typesense run one query for both, with prefix on the last
  word by default and the full typo budget. They can afford it because short prefixes are precomputed (Meilisearch)
  or the candidates per word are capped at 4 or 10 (Typesense, §7.8 rule 5).
- evitaDB's API is the product engines' shape — one query, with `suggest` as a require — but its dictionary walk
  has the cost profile of the Lucene family. The rules therefore follow the query type, not the API.

### 11.3 Measurement

`QueryTypeLatencyMeasurementTest` (slow-tagged) runs 1,000 queries of one to three words on the 865,287-stem
dictionary of §10. The words are drawn from the sampled inflected forms and typed without diacritics; half the
queries carry one synthetic typo after the first letter of one word. The submitted search expands the whole
query once. The as-you-type query replays the text keystroke by keystroke, 23,120 keystrokes in all. It expands
finished words once and caches them, and expands the unfinished last word on every keystroke under the §7.8
rules: prefix from three characters, prefix of the fragment and of its stem variants, the open-ended typo walk,
and at most one typo. A stop-word fragment reaches the prefix scan from the raw input (§10.1).

What is measured is query analysis plus term expansion. The postings union that fetches the products is not:
the test dictionary has no real postings, so the number of candidate terms stands in for it. Timings are
single-threaded JUnit wall-clock after a warm-up pass, indicative only. The walker now builds its widest
automaton once (the construction fix of §10.2), so these numbers include that fix.

**Cost per submitted query and per keystroke:**

| query type | unit | p50 | p95 | p99 | over 1.5 ms | over 5 ms |
|---|---|---|---|---|---|---|
| submitted, no prefix | query | 9.09 ms | 21.93 ms | 27.43 ms | 93.9 % | 74.5 % |
| submitted, prefix on the last word | query | 8.45 ms | 22.46 ms | 28.02 ms | 93.7 % | 69.8 % |
| as-you-type, finished words up to 2 typos | keystroke finishing a word | 4.51 ms | 12.47 ms | 16.36 ms | 87.6 % | 44.0 % |
| as-you-type, every word at most 1 typo | keystroke finishing a word | 0.26 ms | 0.59 ms | 0.85 ms | 0.1 % | 0.0 % |
| as-you-type, either variant | keystroke inside a word | 0.29 ms | 10.24 ms | 43.94 ms | 10.5 % | 7.6 % |

**Candidate terms per submitted query**, the proxy for the cost of fetching products:

| | p50 | p95 | p99 | max |
|---|---|---|---|---|
| no prefix | 44 | 270 | 483 | 685 |
| prefix on the last word | 46 | 281 | 549 | 1,321 |
| terms the prefix leg adds | 0 | 11 | 116 | 1,083 |

**As-you-type keystrokes inside a word, by the length of the typed fragment:**

| fragment length | keystrokes | p50 | p95 | p99 | over 5 ms | last-word terms p50 | p95 | p99 |
|---|---|---|---|---|---|---|---|---|
| 1–2 (no prefix leg) | 3,972 | 0.00 ms | 0.01 ms | 0.01 ms | 0.0 % | 0 | 1 | 1 |
| 3 | 1,986 | 0.24 ms | 24.06 ms | 28.35 ms | 26.4 % | 1,001 | 62,686 | 62,686 |
| 4 | 1,983 | 0.78 ms | 55.98 ms | 75.44 ms | 34.4 % | 2,166 | 108,715 | 145,095 |
| 5 | 1,968 | 0.28 ms | 15.13 ms | 26.28 ms | 18.8 % | 398 | 33,498 | 61,508 |
| 6–7 | 3,807 | 0.24 ms | 2.04 ms | 10.42 ms | 2.5 % | 61 | 5,877 | 27,050 |
| 8 and more | 8,418 | 0.36 ms | 0.69 ms | 0.92 ms | 0.0 % | 8 | 117 | 596 |

**Whether the unfinished last word reaches its target** under the §7.8 rules, from the third letter:

| last word | 3+ letters missing | 1–2 letters missing | complete |
|---|---|---|---|
| typed correctly | 100.0 % | 95.9 % | 100.0 % |
| with a typo | 95.5 % | 88.5 % | 88.9 % |

### 11.4 Readings

**The submitted search.**

- **For a complete last word the prefix leg buys no recall**, because a complete word always finds its own stem
  exactly (§10.1: 100 % over 8,443 forms). It adds only derivations and compounds: none at the median, but 116
  terms at p99 and up to 1,083, which the product fetch has to union and the ranking has to bury. Its latency is
  in the noise (8.45 against 9.09 ms at p50). The corpus held only complete words, though. **A submitted
  fragment** — `kalh` and Enter, or a partial product code — is found only by the prefix leg.
- **Prefix stays on by default for the submitted search too**, keeping the 2026-10-08 default of §7.8 rule 3. An
  as-you-type dropdown shows a product preview from the same query shape (query-design §10.2), and the results
  page after Enter must never match less than that preview showed: with prefix on while typing and off after
  Enter, typing `kalh` would preview trousers and pressing Enter would show nothing. Typo rules may differ
  between the types, because one typo while typing and two after Enter only make the results page a superset of
  the preview. The tail noise of the prefix leg is the price; a prefix leg that runs only when exact and typo
  matching find too little (as Typesense's `typo_tokens_threshold` does for typos) is the option to reduce it,
  unmeasured.
- **Term expansion costs more than expected, and most of it is the two-edit band.** A submitted query spends
  9 ms at the median on expanding its words. One word under two edits costs about 4.5 ms; under one edit,
  0.26 ms. Against phase 1's 25 ms per million candidates it is affordable, but it is the part to watch. The
  levers of §10.2 apply: two edits only on the primary stem variant (§7.4), and the stem-length base of §10.3,
  which moves a fifth of words from two edits to one.

**The as-you-type query.**

- **Two typos on finished words break the budget on the keystroke that finishes them.** The space after a long
  word is when its expansion is computed: with two edits that keystroke costs 4.51 ms at the median and exceeds
  5 ms 44 % of the time; with one edit, 0.26 ms and never. The cap of one typo has to apply to **every word of an
  as-you-type query**, not only the unfinished one. §7.8 rule 8 claimed finished words keep two edits; that holds
  for the submitted search only.
- **The tail is the third and fourth letter, not the typos.** Inside a word 7.6 % of keystrokes exceed 5 ms, and
  nearly all of them are fragments of three to five letters. A three-letter fragment's prefix scan reaches
  62,686 terms at p95; at four letters the open-ended typo walk starts — one edit is allowed from four
  characters — and admits whole subtrees, 108,715 terms at p95. From eight letters on, 99 % of keystrokes stay
  under 1 ms; six and seven letters still reach 10 ms at p99. The hunspell expansion inflates these ranges: every adjective is generated with its `ne-` and `nej-`
  forms, so `nej…` alone spans a large part of the dictionary. A product catalog's dictionary is narrower, but the
  shape — short fragments dominate — is the one P3 §4.1 predicted.
- **The cap is not optional for as-you-type.** The reference expansion applies no cap. Tens of thousands of
  candidate terms per keystroke cannot be unioned into postings within 5 ms whatever the scan costs, so the
  expansion cap of §7.1 is a correctness requirement of this query type. P3 §4.1 warns that a cap cuts the
  lexicographic walk alphabetically; a best-first order for the short-fragment case is the open design question.
- **The open end should start later.** At four letters it multiplies the candidates without a useful signal.
  Starting it at five or six letters, as a separate parameter from the one-typo threshold, is the cheapest fix and
  needs one more run of this measurement.
- **Precomputing short prefixes is worth re-examining for this query type.** The README rejected index-time
  materialization for the main index. Its argument was write amplification for a read-time feature, and that
  Meilisearch pays it only because it has no seekable walk. This measurement shows the seekable walk is not
  enough for three- and four-letter fragments on a broad dictionary. P3 §4.1 kept "precomputed bitmaps of hot
  prefixes" as an option to build only when a measurement shows short prefixes overrun the budget; on this
  dictionary they do. Whether they do on a real catalog is the deciding measurement, over P1's dictionary.
- **Recall stays high with one typo.** A correctly typed last word is found at 95.9–100 % of keystrokes; the gap is
  the past-the-stem keystrokes of §10.1. A mistyped one is found at 88.5–95.5 %. Against §10.2, where the
  thresholds allowed two edits, capping at one costs about five points on the last keystrokes of long words.
- **`prefix(hypotheses)` has a noise side.** In the behaviour examples, typing `kabat` now also returns
  `Černá kožená kabelka`, ranked last. The variant stemmer forks `kabat` into `kabat` and the over-stripped `kab`,
  and the prefix scan from `kab` reaches `kabelk`. That is the cost of the 7.6 points of past-the-stem recall that
  `prefix(hypotheses)` buys in §10.1. Restricting it to variants not much shorter than the fragment is the
  candidate refinement, unmeasured.

### 11.5 The rules per query type

| rule | submitted search | as-you-type query |
|---|---|---|
| prefix leg on the last word | **on by default**, the same as as-you-type, configurable; finds a submitted fragment and keeps the results page a superset of the dropdown preview | **on**, required — the last word is unfinished; a trailing separator closes it |
| minimum fragment length for the prefix leg | the same as as-you-type | 3 by default, configurable (§7.8 rule 5) |
| prefix input | the same as as-you-type | the typed fragment from the raw input, around the stop filter, plus its stem variants (§7.8 rule 6) |
| typos | the §7.1 thresholds, up to two edits | **at most one typo on every word**, finished or not (revises §7.8 rule 8) |
| open-ended typo walk | on the last word, as while typing, so that the results page keeps everything the preview showed (§11.6) | on the unfinished word; the starting length is open, measured too costly at four letters |
| expansion cap | 50 per leg of each word: all exact, 50 prefix, 50 typo (§7.1) | the same; required |
| budget | phase 1 ≤ 25 ms / 1M candidates; term expansion 9 ms at p50 on the fixture, 4.7 ms on the real corpus (§10.7 e) | p99 ≤ 5 ms per keystroke; on the real corpus met from five letters, from four with the open end at five plus the cap, not at three (§10.7 d) |
| consistency | a superset of the as-you-type result for the same text (§11.6) | a subset of the submitted result; differs only by the two-edit band |

**What remains open for the as-you-type type**, in order: the starting length of the open-ended walk; the
expansion cap and its best-first order; and, if those two do not bring three- to seven-letter keystrokes under
5 ms on P1's real dictionary, precomputed postings for short prefixes as P3 §4.1 foresaw.

### 11.6 Consistency between the dropdown and the results page (decided 2026-10-15)

The as-you-type query allows one typo per word and the submitted search two, so the same text can return
different products in the dropdown preview and on the results page after Enter. A survey of the seven local
engine checkouts (2026-10-15, read-only) found that **no engine guarantees the two are identical**, and three
ways of living with it:

| pattern | engines | typo rules | same products for the same text? |
|---|---|---|---|
| 1. a separate suggester with a smaller typo budget | Lucene suggesters, Solr `SuggestComponent`, the Elasticsearch and OpenSearch completion suggester | the suggester: one edit by default, no typo below three characters, first unit frozen; the search: none unless asked, `AUTO` up to two from six characters | no; neither code nor shipped docs address it |
| 2. the same query language with a prefix variant | Elasticsearch `match_bool_prefix` and `search_as_you_type`, Vespa | whatever the caller sends; Elasticsearch never makes the prefix term fuzzy | only if the caller sends the same parameters |
| 3. one query for both, cost controlled inside it | Meilisearch, Typesense | the same everywhere; Meilisearch caps derivations at 150 one-typo, 50 two-typo and 1,000 prefix terms per word (`limits.rs`); Typesense enumerates per-word typo counts with the last word varying fastest (`[0,0,0]`, `[0,0,1]`, `[0,0,2]`, `[0,1,0]`…, at most 10 combinations, `index.cpp:5026-5032`, `index.h:734`) and stops at the first that finds a result (`typo_tokens_threshold` 1, `index.cpp:5253-5260`, `index.h:725`) | yes, by construction |

**Decision: pattern 1**, the rules of §11.5. evitaDB's API has the shape of pattern 3 — one query for both,
`suggest` as a require — but the per-keystroke budget does not allow two edits per word (about 4.5 ms against
0.26 ms for one, §11.3), and the Typesense fallback, which would keep one rule set, would also drop two-edit
matches from the results page whenever cheaper ones exist.

Pattern 1 is acceptable here only because of one property, which the rules must keep: **the results page is a
superset of the dropdown preview for the same text.** A product the user saw while typing never disappears
after Enter; the results page may only add products, those reachable with two edits on words of eight or more
characters, and they rank below exact and one-edit matches in the typo lane. Three rules follow, and each is in
§11.5:

- **The prefix rule is the same in both types**, one switch for both (§7.8 rule 3). With prefix on while typing
  and off after Enter, typing `kalh` would preview trousers and pressing Enter would find nothing.
- **The submitted search's typo budget is never smaller than the as-you-type one.** Two edits after Enter and one
  while typing satisfy it; the reverse would not.
- **The submitted search runs its last word the way the as-you-type query ran it**: prefix leg, prefix of the
  stem variants and the open-ended typo walk, plus the wider thresholds. Without the open-ended walk, a word that
  is mistyped and unfinished — found in the preview — would vanish after Enter.

A caller that overrides the switch per query (§7.8 rule 3) can break the property for its own queries; that is
its responsibility, not the engine's.

The middle option the survey suggested — two edits while typing only as a Typesense-style fallback when one
edit finds too little — would keep the property and recover part of the five recall points the one-typo cap
costs (§11.4). It is not part of the decision and is unmeasured.

### 11.7 What the engines answer for the open questions (2026-10-16)

A read-only survey of the seven local engine checkouts, one question at a time. It answers most of the open
questions of §9 by precedent, though no engine measured its own choice.

| open question (§9) | what the engines do | what it means here |
|---|---|---|
| 1. short fragments while typing | none merges every term a 3–4-letter prefix reaches. Meilisearch precomputes product lists for prefixes of up to 4 bytes shared by at least 100 words, on by default, and walks longer prefixes alphabetically with a cap of 1,000 (`word_fst_builder.rs:103-131`, `index.rs:1886, 2173-2176`, `compute_derivations.rs:40-73`); Typesense keeps 4–10 candidate terms per word, best first by a per-term value (`art.cpp:78-96, 1144-1236, 1868-1884`, `collection.cpp:9877-9881`); Lucene's infix suggester and Elasticsearch's `index_prefixes` precompute short prefixes (§7.8 rule 5); Lucene's FST suggesters search best-first by weight (`Util.java:167-189`) | Meilisearch's rule is arm C of §12. Either precomputation or a best-first cap; where engines cap, they order by popularity or document count, never alphabetically |
| 2. the expansion cap | Typesense 4–10 terms per word; Lucene 50 across all distances, ties to the smaller term (`FuzzyQuery.java:56`, `TopTermsRewrite.java:117-120`); Meilisearch 150 one-typo, 50 two-typo and 1,000 prefix terms (`limits.rs:5-9`); Lucene `PrefixQuery` and Elasticsearch `match_bool_prefix` uncapped. No engine flags a cut-off result | the §10.3 (d) distribution falls inside this range; the deterministic tie-break of §8.2 has Lucene's precedent |
| 3. which length the thresholds read | every engine that stems reads the **stem**: Elasticsearch `match` with `AUTO` (`MatchQueryParser.java:535-546`, `StringFieldType.java:92`), Typesense with `stem: true`, in bytes (`collection.cpp:3260-3279`, `index.cpp:7085-7113`), Lucene's `FuzzySuggester` (`FuzzySuggester.java:245`). Meilisearch reads the typed word because it does not stem | the stem base, measured cheaper in §10.3 and §10.6, is the engines' default; the typed-word base of P3 §4.3 has no precedent |
| 4. a surface-form lane | no engine keeps the surface form by default. Lucene offers `KeywordRepeatFilter` (`KeywordRepeatFilter.java:26-30`), Elasticsearch `keyword_repeat` — but a `match` query then turns the stacked tokens into a synonym group with **no fuzziness and no prefix** (`MatchQueryParser.java:564-571, 606-622`). The documented pattern is a separate unstemmed field (Elasticsearch multi-field docs; Solr's sample spell-check and suggester run on unstemmed fields, `solrconfig.xml:706-730, 834-844`) | supports P5 §6 variant 3 as a **separate lane**. If stems and surface forms ever share one dictionary, typo and prefix expansion must still run on the surface entries |
| 5. the cost of two edits | Lucene switches to the one-edit automaton once its 50-term queue holds only better matches (`FuzzyTermsEnum.java:194-227`); Meilisearch caps two-typo terms at 50 and walks once (`compute_derivations.rs:109-133`); Typesense tries a second typo only when cheaper combinations find nothing (§11.6). No engine gives two edits to only some stem variants of a word | "two edits on the primary variant only" (§7.4) would be new and needs its own measurement; the fallback has precedent |
| 6. typo weights and opt-outs | Meilisearch sums raw typos per word (0, 1, 2), a first-letter typo counts 2 (`typo/mod.rs:50-76`, `compute_derivations.rs:139-142`); Typesense packs `255 − total cost` above proximity, where a prefix-only match costs 1 and each typo 2 (`match_score.h:56-65`, `index.cpp:7334`); Lucene weights by word length (`FuzzyTermsEnum.java:250-257`). Opt-outs are settings, off by default: Meilisearch `disableOnNumbers`, `disableOnWords`, `disableOnAttributes` (`settings.rs:876-891`); Typesense `enable_typos_for_numerical_tokens` true (`collection.cpp:9595-9596`) | raw typo counts in an integer lane are the norm, and Typesense's lane is the proposed `TYPO` lane; evitaDB's always-on digit rule would be stricter than either engine |
| 7. noise from stemming while typing | engines that stem prefix the single analyzed stem with no length guard (Elasticsearch `match_bool_prefix`, `MatchQueryParser.java:590-612`; Typesense `stem: true`). Elasticsearch's `query_string` deliberately does not stem a wildcard term: "it would be wrong to perform stemming on a word that is missing some of its letters" (`query-dsl-query-string-query.md:248`); Lucene's `SuggestStopFilter` keeps an unfinished last word that looks like a stop word (`SuggestStopFilter.java:30-33`) | the length guard on `prefix(hypotheses)` would be evitaDB's own rule. The engines' stated position — never stem a partial word — and their practice of unstemmed fields for as-you-type point at the surface-form lane, which would cover both the unreachable typos (8.3 % in Czech, up to 19.4 % in Romanian, §10.6) and the 11.2 % of mid-ending keystrokes (§10.1) |

Not verified from source: that Meilisearch's tokenizer has no stemmer (its source is not local, only its feature
flags are); when Meilisearch's lazy typo derivations can be skipped; whether Typesense freezes the first letter;
and caps on Vespa's attribute fuzzy matching.

## 12. Precomputed short prefixes versus the query-time scan (2026-10-15)

§11.4 ended with "precomputing short prefixes is worth re-examining for this query type". §11.3 had measured that
the prefix scan of a three-letter fragment reaches 62,686 terms at p95, but it could not measure what fetching the
products of those terms costs, because its dictionary had no real postings. The README rejects index-time
materialization for the main index (*Rejected outright*, the row on Meilisearch's prefix databases): write
amplification for a read-time feature, with P8's measurement that a touched derived key carries 30.7× the bytes of
an average key, and parameters locked into stored data. P3 §4.1 kept "precomputed bitmaps of hot prefixes" as an
option to measure, not to build. This section measures it for short prefixes: the entity leg of a prefix fragment
on real postings, with and without precomputed bitmaps, and what the bitmaps cost in size and on write.

### 12.1 Setup

`ShortPrefixPrecomputationMeasurementTest` (slow-tagged, `evita_functional_tests`, package
`io.evitadb.index.fulltext.typo`).

- **Catalog.** 1,000,000 synthetic products with one field, `name`: a title of 3 to 8 words (uniform), every word
  drawn from the 4,294,861 distinct inflected forms of the hunspell `cs_CZ` fixture (`CzechWordForms`) with a Zipf
  distribution of exponent 1.0 over a seeded random ranking, so a few words are very common and most are rare. The
  titles go through the production `czech` index analyzer into a real `FulltextIndex`, so the postings are real:
  **428,919 terms** (the order of a production dictionary, P3 §4.1), 5,415,731 postings, the longest posting list
  297,788 products, 34.7 % of terms with a single posting. Indexing took 14.4 s. Everything is seeded and
  deterministic.
- **Arms.** All four answer "the products having a word whose term starts with fragment F":
  - **A, the scan** (the current design): `FulltextIndex#forEachTerm(field, F)`, then the postings of every
    visited term OR-ed by the production `OrFormula`.
  - **B, all prefixes of 1 to 4 characters** (Lucene `AnalyzingInfixSuggester`, Elasticsearch
    `search_as_you_type`): a map from prefix to a `PersistentRoaringBitmap` holding the union of the postings of
    every term with that prefix. A fragment of up to four characters is one lookup; a longer one falls back to A.
  - **C, prefixes of 1 to 4 characters shared by at least 100 terms** (Meilisearch's `max_prefix_length` 4 and
    `prefix_count_threshold` 100, `index.rs:1886`): everything else falls back to A.
  - **D, all prefixes of 3 and 4 characters**, matching the minimum fragment length of three (§7.8 rule 5).
- **Fragments.** 1,000 forms of the §10 sample (one distinct form in 500), folded with `CzechLexicon.fold`, each
  typed letter by letter up to eight letters: 1,000 fragments per length from 1 to 4 and 3,828 of length 5 to 8.
  200 further forms warm the paths up. Only the typed fragment is scanned, without stem variants or a typo walk,
  so this is the entity leg of one prefix fragment, not a whole keystroke.
- **Correctness.** Every arm's answer was asserted identical to the scan's for all 7,828 fragments, and again for
  1,200 fragments of length 1 to 4 after the maintenance cycle below.
- **Size**, by two methods: the roaring-serialized size of every posting list or bitmap after `runOptimize()` plus
  the UTF-8 bytes of its key, applied the same way to the base postings and to the arms; and a JOL ownership walk
  (`JolHeapSize.ownedSize`) of the whole `FulltextIndex` without its analyzer, and of each arm's map.
- **Write cost.** The bulk build of each arm from the index's dictionary (one walk over the terms; two for C, which
  counts terms per prefix first). Per document: 2,000 random products are removed and added back, in the base
  index through `removeValue` and `addValue` (analysis included) and in each arm through `remove` and `add` on the
  bitmap of every prefix of the document's terms that the arm holds. For each document the test also sums the
  serialized size of the postings and bitmaps it touches, which is what a persistence that rewrites whole postings
  would write (P8's question).
- **What is not measured.** The arms are plain in-place bitmaps: transactional (MVCC) maintenance of precomputed
  bitmaps, their diff layers and the commit merge are P2 territory and not measured here, nor is persistence beyond
  the byte count above. The AND with the rest of the query and the ranking are not measured; they depend on the
  result cardinality, which is the same in every arm. Arm C does not re-evaluate its threshold on write: a prefix
  that crosses 100 terms does not get a bitmap. The catalog is synthetic, Zipf-distributed hunspell forms, not a
  production catalog, and its skew differs from a real one. Timings are single-threaded JUnit wall-clock after a
  warm-up pass, indicative only.

The run takes about four minutes; `-DshortPrefix.products=100000` runs a smaller catalog:

```shell
mvn -nsu -pl evita_test/evita_functional_tests test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='ShortPrefixPrecomputationMeasurementTest'
```

### 12.2 Results

**What the scan has to union**, per fragment length:

| fragment length | fragments | terms the scan visits p50 | p95 | p99 | max | products in the result p50 | p95 | p99 | max |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 1,000 | 24,021 | 180,407 | 180,407 | 180,407 | 339,266 | 843,454 | 843,454 | 843,454 |
| 2 | 1,000 | 7,825 | 170,608 | 170,608 | 170,608 | 84,198 | 806,050 | 806,050 | 806,050 |
| 3 | 1,000 | 595 | 28,643 | 28,643 | 28,643 | 6,111 | 302,393 | 302,393 | 302,393 |
| 4 | 1,000 | 97 | 10,064 | 17,596 | 17,596 | 933 | 82,465 | 230,311 | 230,311 |
| 5–8 | 3,828 | 6 | 436 | 6,456 | 11,747 | 33 | 3,103 | 53,564 | 149,990 |

**Read latency of the entity leg** (the matching product set for one fragment):

| fragment length | arm | p50 | p95 | p99 | max | over 3.5 ms | over 5 ms | served precomputed |
|---|---|---|---|---|---|---|---|---|
| 1 | A: scan | 11.054 ms | 84.191 ms | 122.038 ms | 235.579 ms | 88.0 % | 80.0 % | 0 % |
| 1 | B: all prefixes 1–4 | 0.002 ms | 0.005 ms | 0.009 ms | 0.044 ms | 0.0 % | 0.0 % | 100 % |
| 1 | C: prefixes 1–4 shared by ≥ 100 terms | 0.001 ms | 0.004 ms | 0.008 ms | 0.021 ms | 0.0 % | 0.0 % | 100 % |
| 1 | D: all prefixes 3–4 | 11.256 ms | 83.678 ms | 121.873 ms | 212.614 ms | 86.5 % | 78.2 % | 0 % |
| 2 | A: scan | 2.754 ms | 80.384 ms | 116.969 ms | 193.348 ms | 45.7 % | 39.0 % | 0 % |
| 2 | B: all prefixes 1–4 | 0.002 ms | 0.010 ms | 0.020 ms | 0.110 ms | 0.0 % | 0.0 % | 100 % |
| 2 | C: prefixes 1–4 shared by ≥ 100 terms | 0.001 ms | 0.008 ms | 0.022 ms | 0.287 ms | 0.0 % | 0.0 % | 99 % |
| 2 | D: all prefixes 3–4 | 2.673 ms | 79.208 ms | 110.432 ms | 157.600 ms | 45.4 % | 39.0 % | 0 % |
| 3 | A: scan | 0.327 ms | 10.222 ms | 12.677 ms | 37.964 ms | 25.3 % | 18.4 % | 0 % |
| 3 | B: all prefixes 1–4 | 0.003 ms | 0.012 ms | 0.025 ms | 0.235 ms | 0.0 % | 0.0 % | 100 % |
| 3 | C: prefixes 1–4 shared by ≥ 100 terms | 0.003 ms | 0.049 ms | 0.096 ms | 2.153 ms | 0.0 % | 0.0 % | 78 % |
| 3 | D: all prefixes 3–4 | 0.002 ms | 0.010 ms | 0.034 ms | 1.297 ms | 0.0 % | 0.0 % | 100 % |
| 4 | A: scan | 0.073 ms | 4.517 ms | 6.740 ms | 27.222 ms | 7.7 % | 4.5 % | 0 % |
| 4 | B: all prefixes 1–4 | 0.005 ms | 0.012 ms | 0.038 ms | 1.069 ms | 0.0 % | 0.0 % | 100 % |
| 4 | C: prefixes 1–4 shared by ≥ 100 terms | 0.006 ms | 0.043 ms | 0.086 ms | 0.153 ms | 0.0 % | 0.0 % | 50 % |
| 4 | D: all prefixes 3–4 | 0.004 ms | 0.016 ms | 0.036 ms | 0.613 ms | 0.0 % | 0.0 % | 100 % |
| 5–8 | A: scan | 0.004 ms | 0.188 ms | 2.191 ms | 4.796 ms | 0.3 % | 0.0 % | 0 % |
| 5–8 | B: all prefixes 1–4 | 0.003 ms | 0.165 ms | 2.166 ms | 5.005 ms | 0.3 % | 0.0 % | 0 % |
| 5–8 | C: prefixes 1–4 shared by ≥ 100 terms | 0.003 ms | 0.152 ms | 2.156 ms | 5.548 ms | 0.3 % | 0.1 % | 0 % |
| 5–8 | D: all prefixes 3–4 | 0.003 ms | 0.149 ms | 2.149 ms | 4.983 ms | 0.3 % | 0.0 % | 0 % |

**Size and bulk build:**

| structure | keys | serialized bytes | heap bytes (JOL) | serialized / base | build time |
|---|---|---|---|---|---|
| base `FulltextIndex` (terms, postings) | 428,919 | 30,865,287 | 46,852,480 | 1.00× | 14.4 s (indexing) |
| B: all prefixes 1–4 | 28,842 | 29,801,935 | 56,212,360 | 0.97× | 0.72 s |
| C: prefixes 1–4 shared by ≥ 100 terms | 1,324 | 18,689,000 | 24,332,288 | 0.61× | 0.67 s |
| D: all prefixes 3–4 | 28,295 | 20,958,353 | 46,101,360 | 0.68× | 0.39 s |

The heap figure of the base covers the whole index (dictionary tree, impacts, length table); the serialized figure
covers terms and postings only.

| structure | keys of prefix length 1 | 2 | 3 | 4 | precomputed postings (sum of cardinalities) |
|---|---|---|---|---|---|
| B: all prefixes 1–4 | 28 | 519 | 4,653 | 23,642 | 19,415,283 |
| C: prefixes 1–4 shared by ≥ 100 terms | 24 | 229 | 562 | 509 | 14,721,960 |
| D: all prefixes 3–4 | 0 | 0 | 4,653 | 23,642 | 10,640,041 |

**Per-document maintenance**, removing a product and adding it back (2,000 products):

| structure | postings / bitmaps touched per document p50 | p95 | max | add p50 | p95 | p99 | remove p50 | p95 | p99 |
|---|---|---|---|---|---|---|---|---|---|
| base `FulltextIndex` (analysis included) | 5 | 8 | 8 | 13.8 µs | 32.9 µs | 48.0 µs | 33.8 µs | 127.7 µs | 232.3 µs |
| analysis of the title alone | 5 | 8 | 8 | 7.6 µs | 25.5 µs | 72.8 µs | 7.6 µs | 25.5 µs | 72.8 µs |
| B: all prefixes 1–4 | 20 | 29 | 32 | 4.0 µs | 8.3 µs | 14.2 µs | 9.0 µs | 41.4 µs | 93.8 µs |
| C: prefixes 1–4 shared by ≥ 100 terms | 15 | 22 | 28 | 2.7 µs | 6.9 µs | 11.8 µs | 4.8 µs | 28.8 µs | 66.2 µs |
| D: all prefixes 3–4 | 10 | 16 | 16 | 2.0 µs | 5.5 µs | 7.4 µs | 6.0 µs | 31.4 µs | 80.3 µs |

The analysis row is timed separately, so its p99 above the base add's p99 is measurement noise rather than a cost
the add does not include.

**Bytes one document touches**, if each touched posting or bitmap were rewritten whole:

| structure | serialized bytes touched per document p50 | p95 | mean | mean bytes per touched key | mean bytes per key of the structure | ratio |
|---|---|---|---|---|---|---|
| base `FulltextIndex` | 132,092 | 322,124 | 132,215 | 24,233 | 72 | 336.8× |
| B: all prefixes 1–4 | 1,492,786 | 2,257,180 | 1,492,162 | 76,304 | 1,033 | 73.8× |
| C: prefixes 1–4 shared by ≥ 100 terms | 1,334,084 | 2,002,418 | 1,337,143 | 90,101 | 14,116 | 6.4× |
| D: all prefixes 3–4 | 540,148 | 1,037,156 | 551,091 | 51,441 | 741 | 69.4× |

### 12.3 Readings

- **The cost of a short fragment is the union, not the walk, and the minimum length of three does not contain
  it.** With real postings, the scan's entity leg alone exceeds 5 ms for 18.4 % of three-letter fragments (p95
  10.2 ms, p99 12.7 ms) and for 4.5 % of four-letter ones (p99 6.7 ms). That comes on top of the term leg §11.3
  measured. One and two letters, which rule 5 of §7.8 excludes, exceed 5 ms for 80 % and 39 % of fragments. From
  five letters on the scan stays under the budget (p99 2.2 ms, max 4.8 ms), so precomputing longer prefixes would
  buy nothing on this catalog.
- **Precomputation brings three- and four-letter fragments under the budget with a wide margin.** Arms B and D
  answer them at p99 0.025–0.038 ms, against 6.7–12.7 ms for the scan. Arm C answers at p99 0.086–0.096 ms: it
  serves 78 % of three-letter and 50 % of four-letter fragments from a bitmap, and the rest fall back to scans of
  fewer than 100 terms. What precomputation removes is the union; the result is as large as before (p95 302,393 of
  1,000,000 products for three letters), and whatever the rest of the query does per matching product is not
  measured here and is the same for every arm.
- **Size is of the order of the base index.** B serializes to 0.97× the base terms and postings (29.8 MB
  against 30.9 MB) and takes 56.2 MB of heap against 46.9 MB for the whole index. D, which omits the 547 keys of
  one and two letters, is 0.68× serialized and 46.1 MB of heap; those 547 keys alone carry about 8.8 MB, nearly a
  third of B. C keeps 1,324 keys (4.6 % of B's) at 0.61× serialized and 24.3 MB of heap. The precomputed bitmaps hold
  3.6 times as many postings as the base (B: 19.4 million against 5.4 million), and roaring's run compression is
  what keeps them small: the bitmap of a short prefix is dense.
- **In memory the write cost is small; in bytes rewritten it is large.** Per document, B touches 20 bitmaps at
  p50 (32 at most) where the base touches 5 postings, and adds 4.0 µs at p50 to an add that costs 13.8 µs (9.0 µs
  to a 33.8 µs remove). D touches 10 and adds 2.0 µs. Rebuilding an arm from the dictionary takes 0.4–0.7 s for a
  million products. In bytes, a whole-bitmap rewrite of what one document touches is 1.49 MB for B, 1.34 MB for C
  and 0.55 MB for D, against 132 KB for the base postings: 11.3×, 10.1× and 4.2× the base. A touched prefix
  bitmap carries 74× the bytes of an average one in B, the same phenomenon as P8's 30.7×; on this Zipf catalog
  the base postings show it even more strongly (337×), so the delta-journal conclusion of P8 applies to both.
- **C against B.** C has about a twentieth of B's keys and about two thirds of its bytes, reads within the
  budget at every length from 1 to 4, and touches 15 bitmaps per document instead of 20. It does not save much
  on write in bytes (1.34 MB against 1.49 MB), because the bitmaps it omits are the small ones and the large,
  often-touched ones stay. It also needs a threshold kept on write, which this test does not measure.
  **D against B**: identical for three and four letters, about two thirds of the size, half of the bitmaps
  touched and about a third of the bytes; it offers nothing for one and two letters, which rule 5 does not serve
  anyway.
- **What this means for the README rejection.** The rejection rests on three claims. The claim that Meilisearch
  needs precomputation "only because it has no seekable dictionary walk" does not hold for short prefixes on this
  catalog: the walk is cheap (§11.3), but the union of the postings it reaches is not, and that is what
  precomputation removes. The write-amplification claim holds in bytes: 4 to 11 times the bytes the base touches
  per document, if bitmaps are persisted whole. It is small in memory. The parameter lock-in claim is weaker for
  these arms than for a typo neighbourhood: they are derived from the dictionary and its postings alone, so
  changing the prefix lengths or the threshold means rebuilding them from the index (0.4–0.7 s here), not
  reindexing the documents. What remains unmeasured is the transactional maintenance of precomputed bitmaps
  (P2), the persistence format, and the same measurement on P1's real catalog, whose skew this synthetic catalog
  only imitates.

---

## 13. Amendments of 2026-10-30: per-field analyzer profiles and the phase-1 budget

> Decided on 2026-10-30, when the implementation specification was reviewed against the F1 plan of the fulltext
> query side. Two things the extraction of 2026-10-21 could not take into account: **per-field analyzer profiles**,
> which F1 builds before typo tolerance, and the **measured phase-1 cost** of the real query path. The algorithm
> document carries the rules; this section carries the reasons and the options that lost.

**The profiles, in short.** Every searchable field references a named analyzer profile defined at catalog level
(`searchable().analyzer("html-text")`, default `text`). Only word splitting - the tokenizer - stays shared per
catalog and language, because `textMatches` requires word *i* to mean the same in every searched field. Everything
that keeps word positions may differ per field: character filters such as HTML stripping (the *text phase*), and
the word steps - stop lists, protected words, the stemmer, folding (the *word phase*). A query is analysed once per
distinct profile among the searched fields, a **profile group** (typically one to three), and each group expands
its words against its own fields only.

### 13.1 The surface-form lane gains a word-phase prefix

§3.4 keys the lane without a field because "the analyzer is chosen per (entity type, locale), so a surface form
stems the same way in every field". With profiles that premise is gone, and a lane hit leaks a stem into fields
whose analyzer never produced it. Example: `description` uses the default Czech profile, which stems; `brand` uses
a profile without stemming. "černé" in a description puts `cerne␀cern␀černé` into the lane, and some brand is
literally "Cern". The query "černé boty" hits the lane at distance 0, the hit yields `cern`, `cern` is looked up in
`brand` as well, and the "Cern" brand matches - ranked as an exact hit. `brand`'s own query side produces `cerne`
and would have found nothing.

**Decided:** the key is `wordPhase␀folded␀stem␀accented`. `wordPhase` is a short fixed-width id of the **index side
of a profile's word phase** - its steps, their parameters and the contents of the resources they read; the text
phase is not part of it, so `text` and `html-text`, which differ only in HTML stripping, share entries. Each id
names one such definition in a small table persisted with the index. A walk runs once per distinct id among the
searched fields and stays inside that id's key range - the fixed-width-prefix technique the stem dictionary already
uses for fields. When all searchable fields of a collection share one word phase, the common case, the lane holds
exactly the keys §3.4 measured, plus the prefix. A field retired because its index side changed keeps its lane
entries under the old id, read by no group, for as long as its postings stay (until #409 rebuilds it).

Options that lost:

- **A field prefix**, `field␀folded␀stem␀accented` - the fix §3.4 itself named. Rejected because it multiplies the
  walks by the searched fields (three on the CMS corpus, where the 1.5 ms term-leg budget of §11 was measured with
  one lane walk per word) and stores a surface form once for every field holding it - unmeasured, and largest for
  the long-text fields that hold most forms. Revisit if real schemas give nearly every field its own word phase, at
  which point the word-phase prefix degenerates into a field prefix anyway.
- **No prefix, the value holding a counter per word phase.** One walk in total and the least memory, but the walk
  cannot skip another group's entries, every hit needs a filter, and the counter becomes a small map. Rejected
  because the lane is walked under the keystroke budget, where skipping a key range beats filtering inside it, and
  the memory it saves exists only when profiles differ - the uncommon case.
- **Forbidding differences that change the word-to-term mapping** (every searchable field of one collection and
  locale shares stemmer, protected words and folding). Keeps §3.4 as written, but reverses the per-field analyzer
  design agreed for F1 on 2026-10-09 and 2026-10-15, which lets everything that keeps word positions differ per
  field.

### 13.2 The lane folds the way the field's profile folds

§3.4 I1 folds the surface form with the `ASCIIFoldingFilter` of the Czech search chain. That is a Czech assumption
even without profiles: the built-in English chain folds nothing, and German folds its umlauts and `ß` in
`GermanNormalizationFilter` but keeps `é`. A fixed fold makes the lane accent-insensitive where the analyzer is not:
in an English index the typed `cafe` would hit `cafe␀café␀café` at distance 0 and match "café" as an exact hit, which
the English chain itself never does. With profiles, folding is a step of the profile (`asciiFolding()`, or the fold
inside cs/sk/pl/ro stemming).

**Decided:** the `folded` part of the key is the surface form as the profile's **query side** hands it to its
stemmer - lowercased, and folded exactly when the profile folds. The typed word the lane walk searches is normalized
by the same steps, so both sides of the comparison agree by construction. The `accented` part is unchanged. The
name `folded` is kept for continuity; for a profile that does not fold it is merely lowercased.

### 13.3 The cap counts per profile group, and a postings safety net bounds the query

**Scope of the cap.** §7.1 caps each leg at 50 terms per query word, counted across the word's variants and both
dictionaries. With profile groups, the stems one group produces are looked up only in that group's fields, so a
union across groups mixes stems that never meet, and a shared cap would let the group with the largest dictionary
(a body text) crowd out a small one (a title). **Decided:** 50 per leg per (query word, profile group). A catalog
without per-field profiles has one group, and nothing changes for it.

**Postings safety net.** P1 measured that a cap must hold summed postings, never only a term count
([`p1-index-core-measurements.md`](p1-index-core-measurements.md) §3.4): expansion widths are so skewed - median 146
postings, p99 1,046,822 in one cell - that a term cap admits one term carrying a million postings, and §7.1's
document-frequency order keeps exactly the largest posting lists first. With two edits and the term cap, 1 % of
submitted queries already exceed the 25 ms budget (§9 item 5, p99 24.9 ms). **Decided:** both caps. The term cap of §7.1 applies first, unchanged; then
the summed postings of the whole query - exact expansions included - are held under the limit the phase-1 cost
model gives for the budget of the query type. P1's prototype coefficients (`2.4 ns × candidates + 32 ns × postings +
21 ns × matched`) do not describe the real query path: they overpredict common-word queries 4-52× and have no fixed
cost per query. The model was therefore refitted on 2026-10-30.

**The refit.** `TextMatchesQueryBenchmark` (`evita_performance_tests`) ran `textMatches` through the query planner,
plain and with `orderBy(relevance())`, on the CMS corpus (972,611 articles) and the e-commerce corpus (133,879
products): query length 1-3 words × three frequency classes, 32 sampled queries per cell, 3 forks, each query's
median taken across forks (288 queries per method and corpus). The fit is on the CMS corpus, the check on the
e-commerce one (median predicted/measured ratio 0.90; that run had a busier machine).

| model | fixed | per extra word | per posting | per matched document |
|---|---|---|---|---|
| ranked, sparse posting lists, both corpora - **used for the cap** | 12.1 µs | 4.5 µs | 7.0 ns | 13.5 ns |
| the same, conservative | 12.1 µs | 4.5 µs | 12.5 ns | 13.5 ns |
| ranked, all CMS queries | 13.1 µs | 3.8 µs | 4.0 ns | 13.1 ns |
| plain (filter only), all CMS queries | 12.0 µs | 3.8 µs | 0.3 ns | 0 |

Median error 22 % ranked, 38 % plain. The cost per posting follows the **density** of the posting lists, not the
corpus size: sparse lists cost 6-8 ns per posting, dense ones 0.3-4 ns (RoaringBitmap array containers run scalar
code, bitmap containers vectorized code); P1's L2-cache effect did not reproduce. Typo and prefix expansions add
mostly sparse lists, so the cap uses the sparse rate. With the budget at 25 ns × N (N documents in the searched
collection and locale) and the matched-document term bounded before execution by the smallest word's postings:

| N | postings cap, 1 / 2 / 3 words | conservative (12.5 ns per posting) |
|---|---|---|
| 1,000,000 | 1.64 M / 1.82 M / 2.18 M | 0.96 M / 1.30 M / 1.47 M |
| 133,879 | 0.22 M / 0.24 M / 0.29 M | 0.13 M / 0.17 M / 0.20 M |

Limits of the refit: the sparse fit covers queries of at most 177,000 summed postings (CMS) and 25,000 (e-commerce),
about 10× short of the caps; it does not include the cost of unioning the ~150 posting lists a 50-term expansion
brings per word; pricing each word by its own list density (known before execution) would tighten it. The cap trims
expansions and never rejects a query. Both the coefficients and the caps are to be measured again once typo
tolerance is built.

**Still open:** which expansions the net drops when it bites. Exact expansions are never dropped. Two candidate
rules for the typo and prefix expansions, both deterministic:

- *skip and continue* - visit the expansions in their leg order and skip any whose postings would push the sum past
  the limit; smaller ones behind it still fit, so the giants go first;
- *stop at the limit* - keep expansions in leg order until the limit is reached, drop the rest; the most frequent
  survive, which suits completions on the as-you-type path.

### 13.4 `EXACTNESS` ranks above impact

§3.2 Q5 and §3.3 order the composite matched words → typos → impact → exactness → context. §7.8 states that "a
prefix hit never outranks an exact hit with the same number of typos", and behaviour example U7 ("while typing,
exact ranks above prefix", §10.4) checks it - but `TypoToleranceBehaviourTest` leaves the impact lane out (one field,
no weights), and with impact above exactness the statement does not hold. Example: a submitted search for `kabel`.
"Kabel USB-C" matches exactly; "Kabelka kožená" matches through the prefix leg of the last word (stem `kabelk`). Both
have zero typos, so with impact above exactness the handbag outranks the cable whenever its impact is higher - a
shorter name is enough.

F1 also turns the impact lane into accumulated evidence - a weighted, saturating sum over every field and every
referenced entity that matches - under the rule that the quality lanes (matched words, typos, exactness) rank
lexicographically above evidence, so that no amount of evidence can outbid quality. **Decided:** the default order
is matched words → typos → exactness → impact → context. The `TYPO` lane as a sum over query words and the four
`EXACTNESS` classes (exact > prefix > fuzzy(1) > fuzzy(2)) stay as specified, and lane order stays a rank-profile
choice.

Rejected: **impact above exactness** - the order of §3.2, and where Meilisearch puts its `exactness` rule (last).
Rejected because it contradicts §7.8's own rule as soon as impact is present, and because accumulated evidence would
let many weak partial matches outbid an exact one.

### 13.5 What did not change

The thresholds (§7.1), the frozen first letter, transpositions, the cap value of 50 and its order, the prefix and
open-ended-walk rules of the last word (§7.8, §11), the two query types and their consistency invariant (§11.6), the
configuration layers (§7.7), and the lane's purpose and accepted cost (§3.4) - except that the cost now applies per
distinct word phase.
