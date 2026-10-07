# Typo tolerance in the fulltext term dictionary

> Status: **interim result of the typo-tolerance analysis**, migrated into this record on 2026-10-02 from
> the working notes of branch `258-fulltext-support-typo-tolerance` (written 2026-09-24, split 2026-09-25,
> proof of concept 2026-09-25, JMH numbers 2026-09-30, follow-up questions 2026-10-05, threshold approach
> and measurements 2026-10-06). Read [`typo-tolerance-prior-art.md`](typo-tolerance-prior-art.md) first — it defines the
> vocabulary, the three algorithm families and what each engine does; this document covers only what is
> specific to evitaDB's fulltext index. Facts are read from the local Lucene checkout and from evitaDB's code
> on the branch. Nothing in this document is a product decision; the one decision the typo-tolerance line has
> taken so far (the Damerau metric for fuzzy `contains`) is in the sibling document
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
> Lucene's index and unusable — and §4.3 collects those notes class by class.

**Reading guide.**

| § | Topic |
|---|---|
| 1 | Summary — what is settled, proposed and open |
| 2 | What typo tolerance means here, and the requirements |
| 3 | Where it sits in the pipeline, with one query worked end to end |
| 4 | What Lucene provides, and what of it is usable |
| 5 | The mechanism: proof of concept, measurements, approaches not pursued |
| 6 | Diacritics in the query and the typo budget |
| 7 | The parameters: thresholds, the stemming fork, opt-outs, configuration |
| 8 | Relevance of a fuzzy hit: Lucene's scoring and what evitaDB builds instead |
| 9 | Next steps |
| 10 | Measurements of 2026-10-06: prefix vs exact, prefix × typo, thresholds, cap, behaviour examples |

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
4. **Measured, not yet decided — which length the thresholds read on a stemmed dictionary** (§7.3, §10.3). The
   stem base halves the noise and the distance-2 cost for 1–2 points of recall; 8.3 % of typos are out of reach
   on a stem-only dictionary whatever the base, which is the argument for a surface-form lane.
5. **Deferred — the intra-leaf seek optimisation** has proven reach and unproven gain (§5.4); it is a decision
   for the real dataset, not for now.

The behaviour we want is now written as fourteen executable examples (§10.4), and the open questions of the
parameter set have measurements behind them (§10). What remains is to decide on them (§9).

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
  keyboard typo. Source: P3 §4.3 (Damerau, `DEFAULT_TRANSPOSITIONS = true`). Status: proposed for the
  dictionary; **decided** for fuzzy `contains` (sibling document §6.1), and the two should not disagree.
- **U3 — typing without diacritics is not a typo.** `cerna` must match `černá` at distance zero and keep the
  whole typo budget for real typos. Source: P5 §7.3, O3. Status: decided — `ASCIIFoldingFilter` after the
  stemmer, on by default for cs/sk, shipped in PR #1453. §6 shows it holds more broadly than asked.
- **U4 — short words are not typo-tolerant.** `bike` must not find `like`; a two-letter code must match
  exactly. Source: research §4.6, Algolia/Meilisearch convention. Status: proposed thresholds, §7.1.
- **U5 — a typo in the first letter is not tolerated.** The user knows how the word starts, and the rule
  prunes the search space to one subtree — measured in §5.2 as a 3× cost lever. Source: P3 §4.3;
  Meilisearch counts a first-letter typo double. Status: proposed (`nonFuzzyPrefix = 1`).
- **U6 — a word with a typo and an unfinished end is still found while typing.** `blakc` on the way to
  `black…`. Source: P3 §4.2.3. Status: proposed — union of a prefix expansion and a whole-token typo
  expansion (option 2); quality unmeasured.
- **U7 — exact ranks above prefix, prefix above typo, fewer typos above more.** Deterministic and
  explainable. Source: research §4.3, query-design §6. Status: decided (TYPO and EXACTNESS lanes of the
  composite).
- **U8 — the user can see why a result matched.** The explain extra result exposes the typo penalty per
  token. Source: query-design §10. Status: decided shape, unbuilt.
- **U9 — suggestions are shown in surface form.** `černá`, never the folded stem `cern` the match ran in.
  Source: P3 §4.3, P5 §6 variant 3. Status: open — needs the dictionary to carry surface forms (P1 K3).

**Product and operational**

- **O1 — thresholds are free to change.** Evaluated only over the query, never baked into stored tokens;
  changing them never triggers a reindex. Source: schema-design §2, §7.2. Status: decided — query-side or
  hot-swap configuration, never schema.
- **O2 — the suggester meets p99 ≤ 5 ms per keystroke including typo expansion**, the term leg alone
  ≤ 1.5 ms. Source: research §7, P3 §1.1. Status: criterion; the typo leg alone is measured in §5.3 at
  12–630 µs per query word on a quarter-million-word vocabulary, the whole-query budget is not.
- **O3 — a hard cap on expanded terms.** Typo expansion is a query that can generate unbounded work on its
  own. Source: research §4.6; opensearch background ("expensive queries need a budget"). Status: agreed,
  value open.
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
  `EXACTNESS` orders exact > prefix > fuzzy. Lane order is a rank-profile choice, hot-swappable.
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
- **Length 1–3 → 0 edits.** `FuzzySuggester.DEFAULT_MIN_FUZZY_LENGTH = 3`. Open, see §7.3.
- **Length 4–7 → 1 edit.** Algolia `minWordSizefor1Typo = 4`, Meilisearch `oneTypo = 5`. Open, §7.3.
- **Length ≥ 8 → 2 edits.** Algolia `minWordSizefor2Typos = 8`, Meilisearch `twoTypos = 9`. Open, §7.3.
- **Length measured on the typed token (folded), not the dictionary term.** P3 §4.3's argument: the
  thresholds model keyboard behaviour, not morphology. Open, §7.3.
- **Non-editable prefix: 1 character.** `FuzzySuggester.DEFAULT_NON_FUZZY_PREFIX = 1`; Meilisearch's
  first-letter penalty; a 3× cost lever by §5.2. Not open.
- **Transpositions: on (Damerau).** `FuzzySuggester.DEFAULT_TRANSPOSITIONS = true`; decided for fuzzy
  `contains` on 2026-10-02. Not open.
- **Prefix × typo for search-as-you-type: two independent expansions, union.** P3 §4.2.3 option 2. **Measured
  2026-10-06 (§10.2):** it finds 18 % of mistyped targets while three or more letters are missing; an open-ended
  typo walk finds 93–95 % at every keystroke.
- **Cascade ed0 → ed1 → ed2, stop when the candidate budget is full.** P3 §4.2.4. Not open.
- **Maximum expanded terms per token: unset.** Research §4.6 asks for "an upper limit". **Open** — the
  value and the behaviour when it is hit. The distribution is measured (§10.3 d): a cap of 50 binds 0.3 % of
  words at distance 1 and 20 % at distance 2.
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
  question, answered by default and by accident; its documentation warns about fuzziness on stemmed fields for
  this reason, and nobody measured it.
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

**The problem.** None of Meilisearch, Typesense or Vespa's attribute fuzzy stems; their thresholds are measured
on surface forms (Algolia, Meilisearch, Typesense all work on surface forms; P5 §6). Lucene-family engines
apply fuzziness to the *analyzed* term, so with a stemming analyzer the distance is measured on stems — and
Elasticsearch's own documentation warns that fuzziness on stemmed fields behaves unexpectedly (§7.2). evitaDB's
dictionary holds folded **stems**, which are shorter than what the user typed — `CzechStemmer` removes suffixes
and can rewrite (`č` → `k`, `ž` → `h`) — so the thresholds of the prior-art document's §3.8 cannot be copied
without deciding which length they apply to. P3 §4.3 chose "measure on the typed token" without measuring.
This is the one genuinely open design question, and it is not answered anywhere in the engines.

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
fixture; the P5 fixture and the real corpora of P1 are still to be added.

### 7.4 Interaction with the hypothesis fan-out (M7)

Each query token becomes 1.3 hypotheses on average (max 2–4). Typo expansion runs per hypothesis, so the
expansion width multiplies. Two questions no record answers:

- Does a hypothesis that is itself a *guess* (the folded stemmer's alternative rule) deserve the full typo
  budget, or should only the primary hypothesis be typo-expanded? A wrong guess at distance 2 is a recall
  trap the Algolia/Meilisearch conventions never had to consider.
- The cap on expanded terms (O3 in §2.2) — is it per token or per hypothesis?

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

What remains in this line of work, in order:

1. ~~Write the behaviour we want as concrete examples.~~ **Done 2026-10-06** — fourteen examples in
   `TypoToleranceBehaviourTest`, all passing (§10.4).
2. ~~Build the synthetic typo corpus and measure §7.3 (a) and (b).~~ **Done for Czech 2026-10-06** (§10.3), over
   inflected forms generated from the hunspell fixture. Slovak, Polish and Romanian remain; their `.aff` files
   are in the same fixture folder.
2a. ~~Measure prefix × typo for search-as-you-type~~ (§7.1 said "quality unmeasured"). **Done 2026-10-06**
   (§10.2), together with prefix versus exact (§10.1).
3. From the measurement, decide the length base for thresholds and whether §7.3 point 3 needs a mitigation
   (a surface-form lane, or a typo-aware fan-out). The data is in §10.3; the decision is open.
4. Decide the opt-out rules of §7.5 — at least the digit-token rule, which costs nothing.
5. Decide the cap and the typo-lane weights (§7.1, last two items) and record them beside the thresholds.
6. Decide the intra-leaf seek (§5.4) over P1's real dictionary, not before.
7. Fold the outcome into `p3-suggester.md` §4.3 (it owns O3) or, if the stemming fork changes the answer
   materially, into its own record superseding that section.

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
