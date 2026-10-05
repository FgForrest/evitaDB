# Typo tolerance — what it is and how search engines do it

> Status: **interim result of the typo-tolerance analysis**, migrated into this record on 2026-10-02 from
> the working notes of branch `258-fulltext-support-typo-tolerance` (written 2026-09-24, split into three
> documents 2026-09-25). It is the shared background of the two typo-tolerance documents beside it and
> assumes no knowledge of the rest of this record. Everything factual below was read from the local
> engine checkouts (lucene, elasticsearch, OpenSearch, solr, meilisearch, typesense, vespa); file
> references are relative to those checkouts. Where a number comes from an engine's documentation rather
> than a line of code, it says so. This document decides nothing — it is the shared ground for two
> separate decisions, each with its own document:
>
> - [`typo-tolerance-fulltext-dictionary.md`](typo-tolerance-fulltext-dictionary.md) — typo tolerance in
>   the fulltext term dictionary (Lucene tooling, our analyzer pipeline, the stemming question, the
>   requirements and the parameter space), with the proof of concept and its measurements.
> - [`typo-tolerance-fuzzy-contains.md`](typo-tolerance-fuzzy-contains.md) — typo tolerance for
>   `attributeContains` over the trigram index, with the measurements and the one decision taken so far
>   (the Damerau metric).

---

## 1. What "typo tolerance" is

A query word matches an indexed word that is *not identical* to it but is within a small **edit distance**:
the minimum number of single-character operations that turn one into the other.

- **Levenshtein distance** counts insertions, deletions and substitutions. `cerna` → `cerne` is 1.
- **Damerau–Levenshtein** (Levenshtein "with transpositions") additionally counts swapping two adjacent
  characters as one operation. `blakc` → `black` is 1 with transpositions and 2 without. Every engine
  below turns transpositions on by default, because a swap is the most frequent keyboard mistake.

Every production implementation bounds the distance at **2**. Above that the number of strings within reach
explodes combinatorially and the matches stop being what the user meant.

Four knobs recur in every engine, and they are the whole design space a product decision has to cover:

1. **Maximum edits** (0, 1 or 2), usually derived from the length of the query word — a short word gets no
   tolerance because one edit in a 3-letter word reaches a different word, not a misspelling of the same one.
2. **Frozen prefix** — the first *k* characters in which no edit is allowed. Cheap and effective: users rarely
   mistype the first letter, and freezing it confines the dictionary search to one subtree.
3. **Transpositions** on or off.
4. **Expansion cap** — the maximum number of dictionary words one query word may expand into. Without it a
   short common word at distance 2 matches thousands of terms and the query does unbounded work.

Plus two things that are *not* knobs but interact with all of them:

- **The normalization space.** Distance is measured between the query word *after* normalization and the
  dictionary word *after* normalization. If the dictionary lowercases, folds diacritics or stems, the
  distance is measured on lowercased, folded or stemmed strings — and a "typo" the normalizer already
  removed costs nothing. Conversely, if the dictionary keeps diacritics and the user types without them,
  every missing accent is one edit and the budget is spent before any real typo is reached.
- **Opt-outs.** Product codes, SKUs, EANs and numbers must never be fuzzy-matched: `AB1234` and `AB1284`
  are different products, and a user typing a code copies it from a label. Every e-commerce engine has a
  switch for this.


## 2. The three ways to find the matches

Typo tolerance is a *query-time* problem: given one query word, find all dictionary words within distance
*d*. There are three families of algorithm, and which one an engine uses is dictated by what its dictionary
looks like.

### 2.1 Levenshtein automaton intersected with a sorted dictionary

Build a deterministic finite automaton (DFA) that accepts exactly the strings within distance *d* of the
query word (Schulz & Mihov 2002; the construction is precomputed per *d*, so building one is microseconds).
Then walk the **sorted** dictionary: read the next term, feed it to the automaton; on rejection ask the
automaton for the smallest string greater than the rejected one that it *could* accept, and **seek** the
dictionary there. The walk visits only the neighbourhood of accepted terms, never the whole dictionary.

Used by **Lucene** (`FuzzyTermsEnum` over the terms dictionary), **Elasticsearch / OpenSearch / Solr**
(they are Lucene), **Meilisearch** (automaton ∩ FST) and **Vespa** (`LevenshteinDfa` with a "successor
string" over the attribute dictionary). Requires only that the dictionary is sorted and seekable.

### 2.2 Trie walk with a dynamic-programming table

Walk a character trie depth-first carrying one row of the classic Levenshtein DP matrix per level; prune a
subtree as soon as the best value in its row exceeds *d*. Used by **Typesense** (`art_fuzzy_search` over an
adaptive radix tree). Asymptotically the same pruning as 2.1, but it needs a per-character trie — it does
not work over a B+ tree of whole keys.

### 2.3 N-gram candidates, then verify

Index every word (or value) by its character n-grams. A word within distance *d* of the query shares all
but at most *d·n* of the query's n-grams, so "values sharing at least *k* of the query's n-grams" is a
candidate superset; each candidate is then verified by computing the real distance. This is how classic
spell checkers work (Lucene's old `SpellChecker` in `lucene-suggest` indexes 1–4-grams of every word;
`NGramDistance` scores them) and it is the only family that can be made to answer *substring* questions,
which is why it matters for `typo-tolerance-fuzzy-contains.md`. Its weakness is the candidate set: an n-gram
shared by many values is cheap to look up and expensive to verify.

A fourth family — precomputing every deletion-variant of every word at index time (SymSpell) — trades a
large write-time and memory cost for O(1) lookups. No engine in the comparison uses it for the main index;
Meilisearch materializes *prefix* bitmaps at index time for search-as-you-type, which is the same trade-off
in a narrower place.


## 3. How the engines do it

For each engine: mechanism, the user-facing parameters and their defaults, where it applies, opt-outs, and
how a fuzzy match affects ranking. The file paths are in the local checkouts.

### 3.1 Lucene

- **Mechanism:** §2.1. `FuzzyQuery` builds one automaton per distance (`FuzzyAutomatonBuilder`), walks
  the terms dictionary with the widest one (`FuzzyTermsEnum`), collects up to `maxExpansions` terms and
  rewrites into a boolean OR of term queries.
- **Parameters:** `maxEdits` 2, `prefixLength` 0, `maxExpansions` 50, `transpositions` true.
- **Length rule:** none in `FuzzyQuery` itself — the caller decides. `FuzzySuggester` has one (no
  fuzziness under 3 characters, and max edits 1).
- **Ranking:** a fuzzy hit is boosted by `1 − distance / min(len(query), len(term))`, i.e. a closer,
  longer term scores higher, then BM25 applies.
- **Opt-outs:** none built in; the application chooses which fields get a `FuzzyQuery`.

### 3.2 Elasticsearch

- **Mechanism:** Lucene's, exposed through `fuzziness` on `match`, `multi_match`, `query_string`, `fuzzy`
  and the completion suggester; `term` and `phrase` suggesters wrap `DirectSpellChecker`.
- **Parameters and defaults** (`server/src/main/java/org/elasticsearch/index/query/FuzzyQueryBuilder.java`,
  `common/unit/Fuzziness.java`): `fuzziness` AUTO, `prefix_length` 0, `max_expansions` 50,
  `fuzzy_transpositions` true, `fuzzy_rewrite` (how the expansion is scored).
- **Length rule:** `AUTO` = 0 edits for words of 0–2 characters, 1 for 3–5, 2 for 6 and more
  (`Fuzziness.java:40-41, 170-180`); `AUTO:low,high` lets the caller move the two boundaries. Length is
  counted in code points of the *analyzed* term.
- **Completion suggester fuzzy options:** edits 1, min length 3, frozen prefix 1, transpositions on,
  `unicode_aware` false (edits counted in UTF-8 bytes unless switched on — the same byte-vs-code-point
  trap `typo-tolerance-fulltext-dictionary.md` §2 mentions).
- **Term suggester:** max edits 2, prefix 1, min word length 4, accuracy 0.5, only suggests terms rarer
  than 1 % of documents and only for query words *not* in the index by default
  (`search/suggest/DirectSpellcheckerSettings.java:24-34`).
- **Ranking:** Lucene's boost, then BM25. A fuzzy hit is a weaker hit, but there is no explicit "number of
  typos" criterion.
- **Opt-outs:** per query — you simply do not set `fuzziness` on the field. No index-level switch.

### 3.3 OpenSearch

The fork carries the same `Fuzziness` class with the same AUTO rule (`server/src/main/java/org/opensearch/
common/unit/Fuzziness.java`), the same query builders and the same suggesters. No divergence relevant to
typo tolerance was found in the checkout.

### 3.4 Solr

- **Mechanism:** Lucene's. The standard and edismax query parsers accept `word~` and `word~1`
  (`SolrQueryParserBase.java:141-142`, default max edits 2, prefix 0).
- **Spellcheck component:** `DirectSolrSpellChecker` wraps `DirectSpellChecker` with the same parameters
  (`maxEdits` 2, `minPrefix` 1, `minQueryLength` 4, `maxQueryFrequency` 0.01,
  `solr/core/src/java/org/apache/solr/spelling/DirectSolrSpellChecker.java`); `WordBreakSolrSpellChecker`
  handles missing or extra spaces; the collator re-runs the corrected query to check it returns results.
- **Ranking and opt-outs:** as Lucene. Typo tolerance in Solr is something the application requests per
  term, not a property of the engine's default matching.

### 3.5 Meilisearch

The engine whose product behaviour is closest to what an e-shop expects, and the source of most
"conventions" quoted in the fulltext ADR.

- **Mechanism:** §2.1 with the Rust `levenshtein_automata` crate over an FST dictionary
  (`crates/milli/src/search/mod.rs:32-34, 639`). Three prebuilt builders for distance 0, 1, 2, each with
  transpositions; a `build_prefix_dfa` variant for the last, unfinished word of a query.
- **Length rule** (`crates/milli/src/index.rs:46-47`): 1 typo from **5** characters, 2 from **9**;
  configurable per index as `typoTolerance.minWordSizeForTypos.{oneTypo,twoTypos}`.
- **First-letter rule:** a derivation whose first character differs from the query's counts as **two**
  typos regardless of its real distance (`query_term/compute_derivations.rs:137-141`). It is not a
  frozen prefix — the term is still found — it is ranked as if it were worse.
- **Expansion caps** (`search/new/limits.rs`): at most 150 one-typo derivations, 50 two-typo
  derivations, 1000 prefix derivations per query word.
- **Opt-outs** (`crates/meilisearch-types/src/settings.rs:121-137, 349`): `disableOnWords` (a list),
  `disableOnAttributes`, `disableOnNumbers` (default false — numbers *are* fuzzy unless switched off),
  and `enabled` globally. A word in `exactWords` gets zero typos (`parse_query.rs:204-216`).
- **Ranking:** a `typo` ranking rule in a fixed cascade (default `words, typo, proximity, attribute, sort,
  exactness`): documents are bucketed by the total number of typos across query words, fewer first; the
  next rule only orders within a bucket. Fully explainable, no score.
- **Search-as-you-type:** the last word is matched as a prefix; prefix *and* typo combine through the
  prefix DFA.

### 3.6 Typesense

- **Mechanism:** §2.2 — a DP-table walk of an adaptive radix trie (`src/art.cpp:1599-1680`,
  `art_fuzzy_search`), one row per trie level, subtree pruned when the row exceeds `max_cost`. The walk
  is best-first by a per-node `max_score`, so the most promising variants are expanded first.
- **Length rule** (`src/index.cpp:7085-7112`, `get_bounded_typo_cost`): 0 typos below `min_len_1typo`,
  1 below `min_len_2typo`, else up to `num_typos`. The code comment says 2 typos "only at token length of
  7 chars"; the documented defaults are `num_typos` 2, `min_len_1typo` 4, `min_len_2typo` 7 (docs, not
  read from a constant in the checkout).
- **Opt-outs** (same function): `enable_typos_for_numerical_tokens` (all-digit tokens) and
  `enable_typos_for_alpha_numerical_tokens` (tokens containing any non-alphanumeric character) — both
  per request; plus `num_typos` per field in the query.
- **Extras:** `typo_tokens_threshold` — only look for typo variants when fewer than this many results
  were found with exact matches (default 1, i.e. typos are a fallback, not always-on);
  `drop_tokens_threshold` — drop query words when too few results; `split_join_tokens` — try splitting
  or joining adjacent words.
- **Ranking:** `_text_match` is one 64-bit integer packed lexicographically: words matched, unique words,
  **typo cost** (fewer better), proximity, exact-match flag, field position. A fuzzy hit is simply a
  document with a higher typo cost, which sits above proximity and below word count in the ladder.

### 3.7 Vespa

- **Mechanism:** §2.1 with its own `LevenshteinDfa` (`vespalib/src/vespa/vespalib/fuzzy/
  levenshtein_dfa.h`), which additionally emits a **successor string** on mismatch for dictionary
  skipping — the same idea as Lucene's `nextString`. Two DFA representations, explicit (faster, more
  memory) and implicit (sparse), chosen by term length. Matching is Unicode-aware on code points.
- **Where it applies:** the `fuzzy()` YQL operator on **attribute** fields (`searchlib/.../attribute/
  dfa_fuzzy_matcher.h`, `string_search_helper.cpp`) and in streaming search (`query/streaming/
  fuzzy_term.cpp`) — i.e. over a dictionary of whole attribute values, not over the posting-list index
  of a `text` field. Fuzzy matching of an indexed text field is not in the checkout.
- **Parameters** (`container-search/.../prelude/query/FuzzyItem.java:22-23`, `YqlParser.java:1697-1715`):
  `maxEditDistance` 2, `prefixLength` 0 (frozen prefix), `prefix` false (when true, the query is matched
  as a fuzzy *prefix* of the value — search-as-you-type over a whole value).
- **Length rule and opt-outs:** none built in; the application decides per query which fields get a
  `fuzzy()` item. Casing is a per-query flag (`cased`).
- **Ranking:** none intrinsic — `fuzzy()` is a match operator, and rank expressions decide what a match
  is worth.

### 3.8 Side by side

| Engine | Algorithm | 1 typo from | 2 typos from | First letter | Cap per word |
|---|---|---|---|---|---|
| Lucene `FuzzyQuery` | DFA ∩ dictionary | caller | caller | prefix 0 | 50 |
| Elasticsearch / OpenSearch | Lucene, `AUTO` | 3 chars | 6 chars | prefix 0 | 50 |
| Solr | Lucene | caller | caller | prefix 0 | 50 |
| Meilisearch | DFA ∩ FST | 5 chars | 9 chars | counts as 2 | 150 / 50 |
| Typesense | DP over trie | 4 chars (docs) | 7 chars | none | best-first |
| Vespa | DFA + successor | caller | caller | prefix 0 | none |
| Lucene `FuzzySuggester` | DFA over FST | 3 chars, max 1 | never | prefix 1 | — |


And the one thing the table cannot show:

- **Who owns the decision.** The Lucene family and Vespa are *libraries*: typo tolerance is something the
  application asks for, per query and per field, and the engine ships no opinion. Meilisearch and
  Typesense are *products*: typo tolerance is on by default with tuned thresholds, opt-outs by attribute
  and word, and a place in the ranking cascade. evitaDB's fulltext ADR chose the product model (cascade
  ranking, explainability), so Meilisearch and Typesense are the relevant precedents for behaviour, and
  the Lucene family is the precedent for the mechanism — the automaton it already ships (§2.1) is what
  the dictionary walk is built from.

## 4. Vocabulary

- **Edit distance / Levenshtein / Damerau–Levenshtein** — §1.
- **Levenshtein automaton (DFA)** — a finite automaton accepting all strings within distance *d* of a fixed
  word; precomputed tables make building one cheap. `LevenshteinAutomata` in Lucene.
- **Frozen (non-fuzzy) prefix** — the first *k* characters where no edit is permitted (`prefixLength`,
  `nonFuzzyPrefix`, `prefix_size`).
- **Prefix DFA / prefix match** — the automaton variant accepting any *extension* of a string within
  distance *d*; what search-as-you-type over a mistyped last word needs.
- **Expansion** — the set of dictionary terms one query word maps to (exact + prefix + typo variants).
- **Successor / next string** — the smallest string greater than a rejected one that the automaton might
  accept; what turns a scan into a seek.
- **Surface form** — the word as it appeared in the text, before stemming and folding.
- **Candidate generation vs. verification** — an index that returns a superset, and the exact test that
  trims it; the trigram index and every n-gram scheme work this way.


## 5. What the two follow-up documents take from here

- The mechanism question is settled by the ecosystem: a Levenshtein automaton walked against a sorted,
  seekable dictionary (§2.1), or a DP walk where the dictionary is a trie (§2.2). N-gram candidate
  generation (§2.3) is the only family that reaches *substring* questions.
- Every parameter is a product decision with a known range: max edits ≤ 2, length thresholds somewhere in
  3–5 / 6–9, a frozen first letter or a penalty for it, transpositions on, an expansion cap, and opt-outs
  for numeric and code-like tokens. The engines disagree only on the exact numbers (§3.8).
- What none of them answers — how thresholds apply on a **stemmed** dictionary — is fulltext-specific and
  is taken up in `typo-tolerance-fulltext-dictionary.md` §3 and §7.1.
