# Typo tolerance in the fulltext term dictionary

> Status: **interim result of the typo-tolerance analysis**, migrated into this record on 2026-10-02 from
> the working notes of branch `258-fulltext-support-typo-tolerance` (written 2026-09-24, split 2026-09-25,
> proof of concept 2026-09-25, JMH numbers 2026-09-30). Read
> [`typo-tolerance-prior-art.md`](typo-tolerance-prior-art.md) first — it defines the vocabulary, the
> three algorithm families and what each engine does; this document covers only what is specific to
> evitaDB's fulltext index. Facts are read from the local Lucene checkout and from evitaDB's code on the
> branch.
>
> What is **settled** here is the mechanism (§4, by code and measurement: `LevenshteinAutomata` from
> `lucene-core` walked against the sorted term dictionary, inside the P3 budget with a wide margin) and the
> placement (§2: a matching-time term expansion, not an analyzer step). What is **proposed** is the
> parameter set of §7, inherited from `p3-suggester.md` §4.3. What is **open** is §3 — which length the
> thresholds read on a stemmed dictionary — and it has to be measured, not chosen. Nothing in this document
> is a product decision; the one decision the typo-tolerance line has taken so far (the Damerau metric for
> fuzzy `contains`) is in the sibling document. §6–§8 fold in the surviving parts of the first, superseded
> analysis of 2026-09-24: the requirements with their status, the pipeline map, the parameter list and the
> next steps.

---

## 1. What Lucene provides, by module

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
  `FuzzyTermsEnum` shows how to learn
  the exact edit distance of a hit for free (enumerate with the distance-2 automaton, then test the hit
  against the distance-1 and distance-0 automata as plain acceptors, `FuzzyTermsEnum.java:236-248`).
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


## 2. Can it plug into `BuiltInAnalyzers`? No — and it should not

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


## 3. The open question the engines do not answer: thresholds on a stemmed dictionary

None of Meilisearch, Typesense or Vespa's attribute fuzzy stems; their thresholds are measured on surface
forms. Lucene-family engines apply fuzziness to the *analyzed* term, so with a stemming analyzer the
distance is measured on stems — and Elasticsearch's own documentation warns that fuzziness on stemmed
fields behaves unexpectedly. evitaDB's dictionary holds folded stems, so the thresholds of the prior-art
document's §3.8 cannot be copied without deciding which length they apply to. This is the one genuinely open design
question, and it is not answered anywhere in the engines.

Concretely, on this branch the Czech index chain produces folded stems: the typed word `pánských` (8
characters, 2 edits by every convention) becomes `pansk` (5 characters, 1 edit by the same conventions).
Three consequences follow, none of which the surface-form engines had to face:

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

§7.1 below states the measurement that would settle it: a synthetic typo set over the P5 fixture
vocabulary, split by whether the typo fell in the suffix or the stem, and the share that survives stemming
at distance ≤ 1, ≤ 2 and > 2.

## 4. Proof of concept in tests (2026-09-25)

Both questions of §1–§2 were settled by code rather than argument. Two test classes live in
`evita_test/evita_functional_tests/src/test/java/io/evitadb/index/fulltext/typo/` (committed 2026-09-25 as
`f3d98af5`); the walker and its two companions — `CzechLexicon`, which loads the hunspell `cs_CZ.dic`
fixture in the folded or the NFD shape, and `EditDistances`, the reference Levenshtein / restricted
Damerau distances plus Sellers approximate substring matching, cross-checked against brute force — were
moved to `evita_test/evita_test_support/src/main/java/io/evitadb/test/fulltext/` on 2026-10-02
(`07d05df7`, together with the JMH benchmarks measured on 2026-09-30) so that the functional tests and the
JMH spikes drive the same code. Nothing in `evita_engine` was touched.

### 4.1 What was tested

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
`AutomatonTermsEnum` walk over
`TransactionalBucketBPlusTree<String>` through its public `cursor(K)` and `next()`: read while the automaton
accepts; on rejection compute the smallest string above the rejected key that the automaton could still
accept (`nextString`, backtracking over saved automaton states) and seek there. Alphabet is code points, the
language is finite so Lucene's visited-state bookkeeping is dropped, and the tree is assumed to be in natural
`String` order (true inside the BMP, asserted by the test on every word).

**`FuzzyDictionaryWalkTest`** (32 tests) — the walk over a real vocabulary: the hunspell `cs_CZ.dic` test
fixture, letters only, lowercased and diacritics-folded with `ASCIIFoldingFilter` to mirror this branch's
index chain — 241 494 distinct keys in a tree with leaf block 63. It checks:

- the guided walk returns **exactly** the set a linear scan with the same acceptor returns, for 14
  combinations of query × maxEdits × frozen prefix, including a transposed query (`bnuda`) and a query with
  no neighbours (`xqzv`);
- every hit carries the distance the reference DP computes;
- `bnuda` finds `bunda` at distance 1;
- a bounded sequential read-ahead (see 4.3) returns the identical hit list for limits 1, 8, 63 and 252;
- the cost of the walk, which is the number that matters.

### 4.2 Measured cost of the plain walk

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

### 4.2a JMH numbers (2026-09-30)

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
same conclusion as §4.3 below with better numbers.

### 4.3 The seek-per-rejection question, and what a real optimisation entails

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

## 5. Where this leaves the fulltext decision

1. The mechanism is settled by the ecosystem, by what we already have, and now by measurement (§4):
   `LevenshteinAutomata` from `lucene-core` walked against a sorted, seekable term dictionary. No new
   dependency, no new index structure, and the walk is inside the latency budget on a quarter-million-word
   vocabulary before any optimisation.
2. It is a matching-time term expansion, not an analyzer step. `BuiltInAnalyzers` already does the only
   part that concerns it — defining and applying the normalization space — and needs no change.
3. Every parameter is a product decision with a known range (prior-art document §5, and §7 below); the
   engines disagree only on the numbers.
4. The one question the engines do not answer is §3, and it has to be measured, not chosen.
5. The intra-leaf seek optimisation has proven reach and unproven gain (§4.3); it is a decision for the
   real dataset, not for now.

What remains before any number is picked is to write the behaviour we want as concrete examples — query,
expected match, expected non-match, expected order — so that thresholds, opt-outs and the stemming answer
are checked against something.


---

## 6. Requirements and the pipeline map (folded from the 2026-09-24 analysis)

The first analysis of 2026-09-24 started from first principles and mapped every point onto what this record
had already decided or deferred; it was superseded as a *starting point* by the three documents of
2026-09-25, because its engine conventions read as requirements before any behavioural assignment existed.
Its requirements list, its pipeline map and its parameter inventory are still the only place those live, so
they are kept here. Sources, all in this record: `../research.md` §4.6 and O3; `p1-index-core.md` K5,
§5.2, §5.3; `p3-suggester.md` §4.2–§4.4; `p5-analyzers.md` §6, §7.3; `schema-design.md` §2, §7.2;
`query-design.md` §6, §10; `../lucene/engine-comparison.md` (Meilisearch, Typesense sections).

### 6.1 What typo tolerance means here

Typo tolerance is one of three **expansion sources** for a query token; the other two are **prefix**
(search-as-you-type: the unfinished last word) and **hypothesis fan-out** (the folded-stemmer variants that
make accent-stripped Czech typing reach accented stems, P1 §2 / M7). All three produce a set of dictionary
terms per query token, and everything downstream (matching, ranking, suggesting, highlighting) consumes that
set the same way.

That framing settles the first general question: typo tolerance is **not a stage of the pipeline** with its
own structure. It is a query-time *term expansion strategy* over the existing term dictionary, and its output
is a set of `(term, editDistance)` pairs. No index structure is added or changed for it. The record fixed
this on 2026-08-12 (README decision table: "take linguistic analysis and the typo automaton from Lucene as a
dependency") and every prototype since builds on it; §2 and §4 above confirm it by code.

### 6.2 What we expect from it — requirements

Collected from the research, the prototypes and the sponsor's hard requirements. Each item carries its
source and its status as of 2026-10-02: *decided* (a record chose and the choice shipped or is binding),
*proposed* (a record chose but nothing measured or shipped), *open* (nobody chose).

**User-visible behaviour**

- **U1 — one edit in an ordinary word still finds the product.** A single substituted, inserted or deleted
  character (`cerne` → `cerna`). Source: research §4.6. Status: decided.
- **U2 — an adjacent transposition costs one edit, not two.** `blakc` → `black` is the most frequent
  keyboard typo. Source: P3 §4.3 (Damerau, `DEFAULT_TRANSPOSITIONS = true`). Status: proposed for the
  dictionary; **decided** for fuzzy `contains` (sibling document §5.4), and the two should not disagree.
- **U3 — typing without diacritics is not a typo.** `cerna` must match `černá` at distance zero and keep the
  whole typo budget for real typos. Source: P5 §7.3, O3. Status: decided — `ASCIIFoldingFilter` after the
  stemmer, on by default for cs/sk, shipped in PR #1453.
- **U4 — short words are not typo-tolerant.** `bike` must not find `like`; a two-letter code must match
  exactly. Source: research §4.6, Algolia/Meilisearch convention. Status: proposed thresholds, §7.
- **U5 — a typo in the first letter is not tolerated.** The user knows how the word starts, and the rule
  prunes the search space to one subtree — measured in §4.2 as a 3× cost lever. Source: P3 §4.3;
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
  ≤ 1.5 ms. Source: research §7, P3 §1.1. Status: criterion; the typo leg alone is measured in §4.2a at
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

### 6.3 Where it sits in the pipeline

The pipeline, index side and query side, with the typo-relevant contract at each step and the record that
owns the decision.

- **I1 — index analysis** (`evita_engine/…/index/fulltext/analysis`, `BuiltInAnalyzers`; owner P5 §7.3,
  shipped). Tokenize → lowercase → (capture surface form) → stopwords → stem → **fold**. The dictionary is
  therefore in **folded stem space**; distance is measured in that space, so diacritics cost nothing. The
  surface form is captured *before* folding if variant 3 of P5 §6 ships.
- **I2 — term dictionary** (planned, P1 K3/K5; owner P1 §3). Sorted `String` keys → postings bitmap and
  cardinality. Must support `cursor(K)` seek plus sequential `next()`, must be in natural `String` (code
  point) order, and cardinality must be readable without touching postings. §4.1 verified the order
  assumption on every word of the fixture vocabulary.
- **Q1 — query analysis** (`analysis` package, `czech-search` and siblings; owner P5 measurement record,
  P1 K5). Same chain with the search-slot analyzer; emits hypotheses per token (M7 fan-out, average 1.3,
  maximum 2–4). Each hypothesis is a separate string to expand; typo expansion runs **per hypothesis**,
  after fan-out.
- **Q2 — threshold decision** (query planner, fulltext translator; owner P3 §4.3 and §7 below). For every
  typed token decide `maxEdits ∈ {0, 1, 2}` and `nonFuzzyPrefix`. Computed from the typed token's folded
  length, once per token, before any expansion. Configurable and hot-swappable, never in schema.
- **Q3 — term expansion** (planned, P1 K5 / P3 §4.2; prototyped in §4 above). Per (hypothesis, maxEdits):
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

### 6.4 Approaches not pursued

Every approach below was measured, adopted or rejected somewhere in this record; the README's *Rejected
outright* table carries the first two with their revisit conditions. Listed here so the fork is visible in
one place next to the approach that won (§4 / §5).

- **Trie walk with a dynamic-programming Levenshtein table** (Typesense). Asymptotically the same pruning as
  the automaton walk with the cost distributed differently. Rejected because our dictionary is a paged
  B+ tree of front-coded keys, not a character trie, and the DP walk needs per-character edges; P3 §3.6
  rejected the equivalent (variant T3, simultaneous descent à la `IntersectTermsEnum`) for exactly this
  reason — the tree does not expose label boundaries. Fallback only if measurement shows seeks dominant
  after the intra-leaf fast path of §4.3.
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
  in dictionary size (1.1–2.3 ms on 241 494 keys, §4.2a), unusable at 400–500 k keys within the per-keystroke
  budget — but it is the reference implementation the guided walk is tested against. Kept as the test oracle.

## 7. The parameters — the current proposal and what is genuinely open

P3 §4.3 proposes an answer to the research's question O3. Restated here with the pieces that are still to be
challenged, status as of 2026-10-02:

- **Distance cap: 2.** `LevenshteinAutomata.MAXIMUM_SUPPORTED_DISTANCE`; §4.1 adds that `toAutomaton(3)`
  returns `null` rather than throwing, so the cap is the caller's to enforce. Not open.
- **Length 1–3 → 0 edits.** `FuzzySuggester.DEFAULT_MIN_FUZZY_LENGTH = 3`. Open, see §7.1.
- **Length 4–7 → 1 edit.** Algolia `minWordSizefor1Typo = 4`, Meilisearch `oneTypo = 5`. Open, §7.1.
- **Length ≥ 8 → 2 edits.** Algolia `minWordSizefor2Typos = 8`, Meilisearch `twoTypos = 9`. Open, §7.1.
- **Length measured on the typed token (folded), not the dictionary term.** P3 §4.3's argument: the
  thresholds model keyboard behaviour, not morphology. Open, §7.1.
- **Non-editable prefix: 1 character.** `FuzzySuggester.DEFAULT_NON_FUZZY_PREFIX = 1`; Meilisearch's
  first-letter penalty; a 3× cost lever by §4.2. Not open.
- **Transpositions: on (Damerau).** `FuzzySuggester.DEFAULT_TRANSPOSITIONS = true`; decided for fuzzy
  `contains` on 2026-10-02. Not open.
- **Prefix × typo for search-as-you-type: two independent expansions, union.** P3 §4.2.3 option 2. Quality
  unmeasured.
- **Cascade ed0 → ed1 → ed2, stop when the candidate budget is full.** P3 §4.2.4. Not open.
- **Maximum expanded terms per token: unset.** Research §4.6 asks for "an upper limit". **Open** — the
  value and the behaviour when it is hit.
- **Typo lane weights: "weighted sum of typos".** Research §4.3. **Open** — what the weights are (ed1 = 1,
  ed2 = 2? a first-letter typo = 2?).

### 7.1 The one real fork: thresholds on a stemmed dictionary

§3 states the problem; this is what has to be done about it. The conventions above come from engines that
**do not stem** (Algolia, Meilisearch, Typesense all work on surface forms; P5 §6). Our dictionary holds
folded **stems**, which are shorter than what the user typed — `CzechStemmer` removes suffixes and can
rewrite (`č` → `k`, `ž` → `h`) — and P3 §4.3 chose "measure on the typed token" without measuring.

**The measurement:** on the P5 fixture vocabulary and on the real corpora P1 uses, (a) the distribution of
typed-length vs. stem-length, (b) for a synthetic typo set (one substitution / insertion / deletion /
transposition per position class), the share of typos that survive stemming at distance ≤ 1, ≤ 2, and > 2,
split by whether the typo fell in the suffix or in the stem. That number decides whether thresholds are
computed on the typed token, on the stem, or on a combination (e.g. `min(thresholdByTyped,
thresholdByStem + 1)`), and whether variant 3 of P5 §6 (a surface-form lane in the dictionary) is a
*correctness* requirement for typo tolerance the way it already is for accent-stripped typing (P1 §2,
2026-08-25). `CzechLexicon` and `EditDistances` in `evita_test_support` are the harness it would be built
on.

### 7.2 Interaction with the hypothesis fan-out (M7)

Each query token becomes 1.3 hypotheses on average (max 2–4). Typo expansion runs per hypothesis, so the
expansion width multiplies. Two questions no record answers:

- Does a hypothesis that is itself a *guess* (the folded stemmer's alternative rule) deserve the full typo
  budget, or should only the primary hypothesis be typo-expanded? A wrong guess at distance 2 is a recall
  trap the Algolia/Meilisearch conventions never had to consider.
- The cap on expanded terms (O3 in §6.2) — is it per token or per hypothesis?

### 7.3 Where typo tolerance must be switched off

Every e-commerce engine has an opt-out because typo tolerance over **codes, SKUs, EANs, model numbers**
produces confidently wrong matches (`AB1234` and `AB1284` are different products, and a user typing a code
is typing it from a label, not from memory). Meilisearch has `disableOnAttributes`, `disableOnWords`,
`disableOnNumbers`; Algolia has `disableTypoToleranceOnAttributes` / `…OnWords` and `typoTolerance:
strict|min`. No record in this folder mentions it yet. It needs a decision on:

- **A token-shape rule** (cheap, no configuration): tokens containing a digit get 0 edits. Covers SKUs and
  model numbers without touching the schema.
- **Per-attribute disable.** By the record's cost rule this is *locked* only if it changes stored tokens; if
  it merely tells the query planner "no expansion for terms of this field", it is free and belongs beside
  field weights in the rank profile or hot-swap artefact, not in the schema. Needs the same classification
  exercise schema-design §2.1 did for the other options.
- **Per-word disable** (a brand list) — a hot-swap artefact like the synonym dictionary.

### 7.4 Configuration surface

By schema-design §7.2 the thresholds are **free** (query-side). Three candidate places, in the order
query-design §6.3 proposed for rank profiles: a query constraint (per query, for experimentation), a named
profile on the server (hot-swap), and a catalog-level default. The typo parameters should travel with the
rank profile — the artefact that already carries field weights and lane order — rather than grow a fourth
configuration channel. The exact DSL shape is O4 territory and is not decided here.

## 8. Next steps of the analysis

What this document leaves to other records: the suggester's scoring cascade for candidate terms (P3 §4.4);
the surface-form vs. stem dictionary layout (P5 §6 variant 3 — P1 K3 measures the size, §7.1 contributes
only the typo-correctness argument); lane order in the composite (P7); DSL naming (O4).

What remains in this line of work, in order:

1. Write the behaviour we want as concrete examples — query, expected match, expected non-match, expected
   order — so that thresholds, opt-outs and the stemming answer are checked against something (§5).
2. Build the synthetic typo corpus over the P5 fixture vocabulary (Czech first, then sk/pl/ro) and measure
   §7.1 (a) and (b). This is the one input no record has and everything else in O3 waits on it.
3. From the measurement, decide the length base for thresholds and whether §3 point 3 needs a mitigation
   (a surface-form lane, or a typo-aware fan-out).
4. Decide the opt-out rules of §7.3 — at least the digit-token rule, which costs nothing.
5. Decide the cap and the typo-lane weights (§7, last two items) and record them beside the thresholds.
6. Decide the intra-leaf seek (§4.3) over P1's real dictionary, not before.
7. Fold the outcome into `p3-suggester.md` §4.3 (it owns O3) or, if the stemming fork changes the answer
   materially, into its own record superseding that section.
