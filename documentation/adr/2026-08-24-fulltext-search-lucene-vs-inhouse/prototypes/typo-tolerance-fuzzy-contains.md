# Typo tolerance for `attributeContains` over the trigram index

> Status: **interim result of the typo-tolerance analysis**, migrated into this record on 2026-10-02 from
> the working notes of branch `258-fulltext-support-typo-tolerance` (written 2026-09-24, split 2026-09-25,
> measured 2026-09-30 and 2026-10-02). Read [`typo-tolerance-prior-art.md`](typo-tolerance-prior-art.md)
> first — it defines edit distance, the three algorithm families and what each engine does. This document
> covers only whether and how those tools apply to the exact substring predicates over attribute values,
> which have nothing to do with the fulltext index; its sibling
> [`typo-tolerance-fulltext-dictionary.md`](typo-tolerance-fulltext-dictionary.md) covers the term
> dictionary. Facts are read from evitaDB's code on the branch.
>
> **Read it in layers, the way the P8 brief is read.** §1–§3 are the analysis as first written, and §3's
> cost argument against option A is **wrong** — kept on purpose, with the correction announced in place.
> §4 (2026-09-30) carries the measurement that corrected it; §4.3 still states the 6 / 9 cut-off of a
> single threshold, which §5.5 later moves to 4 for one edit by enumeration. §5–§6 (2026-10-02) carry the
> rule that follows, the one decision taken, the two further mechanisms measured, and a worked example; §8
> is the current recommendation. Reviewed 2026-10-02 by an independent reviewer and the advisor; their
> corrections are folded in (the bound depends on the metric and on *distinct* trigrams, case H was wrong,
> the gate bound was wrong). The benchmark is `FuzzyContainsCandidateBenchmark`
> (`evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/`, committed 2026-10-02 as
> `07d05df7`); its reference distances are `EditDistances` in `evita_test_support`.
>
> **Decided 2026-10-02: the metric is Damerau** (an adjacent swap costs one edit) — §5.4 records it and the
> two ways to implement it. **Measured, not yet decided:** the tiered shape of §8 (full one-edit
> enumeration, swap expansion at two edits) is the recommendation; confirmation on a real corpus, and the
> case and diacritics semantics, are still owed. Everything else here is analysis.

---

## 1. What `attributeContains` is today

`attributeContains` / `attributeEndsWith` / `attributeStartsWith` are exact substring predicates over the
**distinct values** of a filterable `String` attribute (`core/query/filter/translator/attribute/
AbstractAttributeStringSearchTranslator.java`). Values are stored in a shared sorted value tree normalized to
Unicode **NFD** and **case-sensitive** (`index/attribute/FilterIndex.java:366-376`). The baseline path scans
every distinct value and applies `String.contains`.

Where an attribute declares the `SUBSTRING_SEARCH` accelerator, a `TrigramIndex`
(`index/trigram/TrigramIndex.java`) maps every trigram — three consecutive code points of the NFD value — to
the **value ids** containing it. `TrigramSubstringSearch.match` (`index/trigram/TrigramSubstringSearch.java`)
then: extracts the pattern's distinct trigrams, **intersects** their postings (a value must contain *all*
of them), resolves the candidate value ids back to values, and runs the **exact** predicate over each. The
index is a candidate generator only; correctness comes from the verification step. It declines (falls back
to the scan) when a transaction is open, when the pattern has fewer than 3 code points, or when the cheapest
posting is not at least 12× narrower than the scan it would replace (`REQUIRED_NARROWING_FACTOR`).
`attributeStartsWith` does not use it at all — the sorted tree answers a prefix by a range walk.

Two properties of this design matter for any fuzzy variant: it holds **membership only** (no positions, no
counts), and its economics rest on the intersection being *nearly exact* (measured worst case 0.36 false
candidates per true match), so verification is cheap.

## 2. What "fuzzy contains" would even mean

Substring matching with errors has a precise definition — approximate string matching (Sellers 1980): the
pattern matches a value if some substring of the value is within distance *d* of the pattern. It is
computed by the Levenshtein DP with a free start and free end in the text, O(|pattern| × |value|) per
value. The semantics are well defined; the cost model is what changes.

## 3. The options and what each entails

**Option A — count-threshold trigram candidates + approximate verification.** One edit inside the pattern
destroys at most 3 of its trigrams (an insertion or deletion at the edge, fewer). So a value within
distance *d* of some substring of the pattern shares at least `n − 3d` of the pattern's *n* trigrams. Replace
the intersection (all *n*) by a **counted union** (at least `n − 3d`), then verify each candidate with the
approximate-substring DP instead of `String.contains`.

What it entails in code:

- `PatternPostings` / `TrigramPostingAccumulator`: a counting merge over the trigram postings (Roaring has no
  native "at least k of n"; it is a merge of sorted bitmaps with a per-id counter, or a k-way heap).
- A new verification predicate (approximate substring, Damerau optional) in the translator, with the
  distance computed over NFD code points — which makes a missing háček a 1-edit difference (the combining
  mark is one code point), a property that falls out of NFD for free.
- A new constraint or a parameter on the existing one (`attributeContains(name, "cerna", typo(1))` or
  similar), through the query model, EvitaQL grammar, gRPC/GraphQL/REST — the `new-constraint` skill lists
  the layers.
- The A/B gate re-tuned: the candidate set of a counted union is bounded by the *largest* posting among the
  survivors, not the smallest, and the "nearly exact" premise is gone.

What it costs, and why it is the weak point — **as first argued, before measurement; §4 corrects it**: a
6-code-point pattern has 4 trigrams; at *d* = 1 it needs `4 − 3 = 1` shared trigram, and a 5-code-point
pattern needs `3 − 3 = 0`, i.e. nothing is guaranteed and the candidate set is the corpus. The measured
reality is a hard cut-off by pattern length rather than a gradual collapse: see §4. Case-sensitivity is a second
problem: today's predicate is
case-sensitive, and a typo-tolerant search that rejects `Cerna` against `cerna` would look broken, so the
fuzzy variant would need a case-folded trigram index or pay a case-insensitive verification over
candidates the case-sensitive index never produced.

**Option B — Levenshtein automaton over the sorted value tree.** The automaton walk of the prior-art
document's §2.1
works over *any* sorted dictionary, and the value tree is one. It answers **fuzzy equality** (`attributeEquals` within
distance
*d*) and **fuzzy prefix** (`attributeStartsWith` within *d*, via the prefix-DFA idea Meilisearch and Vespa
use) efficiently — that is exactly Vespa's `fuzzy()` on attributes. It cannot answer *contains*: the
automaton for "any string containing something within *d* of the pattern" starts with an unconstrained
Σ\*, so the guided walk has nothing to seek by and degenerates to running the acceptor over every value —
a scan with a more expensive predicate.

What it entails: `LevenshteinAutomata` from `lucene-core`, a "next accepted string" seek over
`InvertedIndex`'s value tree (the same port P3 plans for the term dictionary and the sibling document
prototyped, reusable
here), NFD and case handling as in A, and the same constraint plumbing. Cheap to build once the fulltext
walk exists; useful for whole-value fields (brand, code, short names) — but that is fuzzy *equals*, not
fuzzy *contains*.

**Option C — do not make `attributeContains` fuzzy; route typo tolerance through the fulltext index.** A
typo is a property of a *word*, and every engine in the prior-art document's §3 applies tolerance per word
over a term dictionary.
`attributeContains` is a substring predicate over raw values; attaching edit distance to it produces a
predicate no engine offers, whose cost model (A) or expressiveness (B) is poor, and whose semantics for
multi-word values ("does `cerna bnda` fuzzily contain `černá bunda`?") reduce to word-level matching anyway.
The fulltext dictionary already tokenizes, lowercases, folds and stems, so the space is right; the trigram
index stays what it is.

What it entails: nothing for the trigram index. The decision is a product one: whether "contains with
typos" is a feature anyone asked for, or whether "search with typos" (fulltext) is the actual request.

## 4. Measured (2026-09-30): the hypothesis of §3 option A, corrected

`FuzzyContainsCandidateBenchmark` (JMH, `evita_test/evita_performance_tests/.../spike/`) tests option A on the
Czech vocabulary in the attribute index's own shape (lowercase, NFD, diacritics kept). Two value shapes,
because the hypothesis is about how much trigrams are shared, and that depends on how long the values are:

- `WORD` — every dictionary word is one distinct value: 256 708 short values (5–12 code points, 3–10
  trigrams each). Models a `code`-like or short-name attribute: many values, each small.
- `NAME` — 100 000 values of three random words joined by a space (20–30 code points, 20–30 trigrams
  each). Models a product `name`: fewer values, each long and sharing trigrams with many others. This is
  the shape where the hypothesis should hold *most*; `WORD` the one where it should hold least.

Patterns are fragments of real values (5, 8 or 12 code points) damaged by 1 or 2 random substitutions,
never in the first character. The undamaged fragment is kept so that today's exact path can be measured on
the same corpus. Four arms: the counted union alone, the counted union followed by approximate-substring
verification (Sellers), the full approximate scan, and today's exact intersection on the undamaged
fragment for scale. Short JMH run (1 warm-up, 2 × 1 s), single fork — **indicative numbers**, three significant
digits without error bars; the ratios are stable, the timings are not better than ±20 %.

**Metric caveat for the table below.** These numbers were produced by the first version of the benchmark, which
verified with plain Levenshtein (a swap costs two), damaged patterns by substitution only, and used the
`u − 3d` threshold. Under Damerau (a swap costs one, which is what the fulltext walker and every engine in
the prior-art document's §3 use) the sound threshold is `u − 4d` and the cut-offs move to 7 / 11 — see §5.1.
The corrected
benchmark carries both metrics; its matrix is in §4.4.

### 4.1 Results (plain Levenshtein, `u − 3d`)

| shape | L | d | union candidates | true matches | union + verify | full scan |
|---|---|---|---|---|---|---|
| WORD | 5 | 1 | whole corpus (cut-off) | 1 973 | 22.3 ms | 15.6 ms |
| WORD | 8 | 1 | 1 261 (0.49 %) | 115 | 0.53 ms | 23.8 ms |
| WORD | 12 | 1 | 11 (0.00 %) | 1 | 0.24 ms | 30.9 ms |
| WORD | 8 | 2 | whole corpus (cut-off) | 179 | 28.2 ms | 21.2 ms |
| WORD | 12 | 2 | 760 (0.30 %) | 4 | 0.34 ms | 33.1 ms |
| NAME | 5 | 1 | whole corpus (cut-off) | 4 846 | 20.0 ms | 18.8 ms |
| NAME | 8 | 1 | 1 207 (1.21 %) | 73 | 0.54 ms | 27.8 ms |
| NAME | 12 | 1 | 18 (0.02 %) | 2 | 0.19 ms | 38.1 ms |
| NAME | 8 | 2 | whole corpus (cut-off) | 367 | 27.3 ms | 30.6 ms |
| NAME | 12 | 2 | 431 (0.43 %) | 5 | 0.36 ms | 36.7 ms |

Today's exact intersection on the intact fragment is 2–6 µs in every row.

### 4.2 What the numbers say

**The hypothesis as written was wrong, and the way it is wrong matters.** Candidate sets do *not* creep
towards the corpus as patterns get shorter. There is a hard **cut-off** instead: the union guarantees at
least `n − 3d` shared trigrams, and when that is zero or negative it guarantees nothing, so the only sound
candidate set is the whole corpus. Below the cut-off option A is a scan *by definition*; above it, it is
nearly exact:

- **Above the cut-off, option A is 40–150× faster than the scan**, and the candidate set is 1–10× the true
  match count, not "most of the corpus". The value shape barely matters: `NAME` has a somewhat larger
  candidate share (1.2 % vs 0.5 % at L = 8, d = 1), which is the effect of longer values, but it is the
  same order and nowhere near a scan.
- **Below the cut-off there is no candidate generation at all.** A 5-character typed fragment with one
  typo cannot be served by trigrams under any edit distance; an 8-character fragment cannot be served at two
  edits. Those rows cost a scan of 15–30 ms per pattern on a quarter-million values, growing linearly with
  the corpus.
- **The first version of the benchmark clamped the threshold to one and produced a fast, wrong answer**
  (fewer candidates than true matches at L = 5, d = 2). The second version asserted the superset property,
  but only over 32 substitution-damaged patterns of one seed under plain Levenshtein, so it could not see
  a transposition or a repeated-trigram pattern. The current version damages patterns with all four edit
  kinds, sweeps several seeds, runs under both metrics with the matching threshold, and fails the trial on
  the first missed match. An implementation has to carry the same assertion in its tests, because the
  failure is silent — missing results, no exception.

### 4.3 What this means for an implementation

Fuzzy `attributeContains` on the trigram index **does make sense, as an opt-in**, with these properties:

- it is cheap and sound for patterns of 6 code points and more at one edit, 9 and more at two — sub-millisecond
  on a quarter-million values, where the exact predicate is microseconds and a scan is tens of milliseconds;
- below those lengths it must not be attempted at all; the exact predicate runs alone, exactly as today.
  This is not a tuning knob — it is where the index stops being able to guarantee anything;
- it is an opt-in because it changes the predicate's semantics (edit distance over NFD code points, so a
  missing diacritic is one edit — §6 example H), its cost profile (verification by a dynamic programme
  instead of `String.contains`), and because today's `SUBSTRING_SEARCH` accelerator already is an opt-in
  per attribute. The cheapest honest shape is a parameter on the constraint, enabled only where the
  attribute declares the accelerator;
- the candidate generator is new code; everything else — trigram extraction, postings, value-id → value
  resolution, the A/B gate — exists. The right primitive is **not** a dense per-value counter (that is
  linear in the corpus, so the 40–150× of §4.1 is a constant-factor win, not an asymptotic one). The
  asymptotically right one is the pigeonhole bound: a value holding at least `u − c·d` of `u` trigrams holds at least
  one of **any**
  `c·d + 1` of them, so iterate only the `c·d + 1` *smallest* postings and confirm each id's count by
  membership tests against the rest (prefix filtering, Li–Lu–Lu 2008). That also gives the A/B gate its
  number — the sum of the `c·d + 1` smallest cardinalities against the corpus — in place of today's
  cheapest-posting bound, which does not apply to a union. Measured at 10⁵–2.5·10⁵ values the dense
  counter is nevertheless 1–2.5× faster (§4.4), so the choice is for the real corpus. The dense counter
  also assumes value ids are dense `1..N`; an implementer has to check how the shared value tree mints
  ids and what a deleted value leaves behind before indexing an array by them.

The product question of §8 stands, sharpened: a user has to be told that short fragments get no typo
tolerance. Every product engine says the same thing for a different reason (§5.2).

### 4.4 Corrected matrix (2026-10-02): both metrics, all four edit kinds, soundness asserted

Same corpus, same short run; patterns now damaged by substitution, insertion, deletion **and** adjacent swap,
verification under the metric of the row (`c = 3` Levenshtein, `c = 4` Damerau), threshold `u − c·d` over
*distinct* trigrams, and the superset property asserted on 96 extra patterns (3 seeds) per row before any
timing — **it held in every row**. Because insertions and deletions change the typed length, a nominal L = 5
fragment can come out at 6 and clear the threshold, which is why the "scan-only" column is fractional.
"union" is the dense counter, "pf" the smallest-postings prefix filter (identical candidates by
construction), times are union + verify / pf + verify / full scan.

| shape | L | d | metric | scan-only | candidates (union = pf) | true | union+verify | pf+verify | scan |
|---|---|---|---|---|---|---|---|---|---|
| WORD | 5 | 1 | Lev | 18/32 | 57 % | 2 218 | 12.7 ms | 13.6 ms | 19.7 ms |
| WORD | 8 | 1 | Lev | 0/32 | 1.0 % (2 682) | 100 | 0.70 ms | 0.68 ms | 25.0 ms |
| WORD | 12 | 1 | Lev | 0/32 | 0.02 % (44) | 2 | 0.39 ms | 0.26 ms | 34.7 ms |
| WORD | 8 | 2 | Lev | 20/32 | 64 % | 648 | 19.1 ms | 22.3 ms | 28.3 ms |
| WORD | 12 | 2 | Lev | 0/32 | 0.45 % (1 160) | 5 | 0.73 ms | 0.95 ms | 31.2 ms |
| WORD | 5 | 1 | Dam | 32/32 | 100 % | 2 476 | 33.7 ms | 29.4 ms | 24.8 ms |
| WORD | 8 | 1 | Dam | 0/32 | 2.3 % (5 808) | 101 | 1.23 ms | 1.60 ms | 32.2 ms |
| WORD | 12 | 1 | Dam | 0/32 | 0.11 % (271) | 3 | 0.50 ms | 0.49 ms | 40.7 ms |
| WORD | 8 | 2 | Dam | 32/32 | 100 % | 654 | 41.9 ms | 38.2 ms | 36.2 ms |
| WORD | 12 | 2 | Dam | 1/32 | 8.9 % (22 762) | 6 | 5.3 ms | 7.2 ms | 42.8 ms |
| NAME | 5 | 1 | Lev | 24/32 | 78 % | 1 985 | 16.4 ms | 18.1 ms | 21.2 ms |
| NAME | 8 | 1 | Lev | 0/32 | 2.3 % (2 335) | 109 | 0.92 ms | 1.12 ms | 30.3 ms |
| NAME | 12 | 1 | Lev | 0/32 | 0.01 % (7) | 1 | 0.34 ms | 0.32 ms | 39.2 ms |
| NAME | 8 | 2 | Lev | 20/32 | 68 % | 2 174 | 19.9 ms | 21.1 ms | 29.0 ms |
| NAME | 12 | 2 | Lev | 0/32 | 0.60 % (602) | 6 | 0.57 ms | 1.10 ms | 38.2 ms |
| NAME | 5 | 1 | Dam | 32/32 | 100 % | 2 498 | 30.5 ms | 29.0 ms | 28.7 ms |
| NAME | 8 | 1 | Dam | 0/32 | 5.2 % (5 169) | 112 | 2.74 ms | 3.29 ms | 42.5 ms |
| NAME | 12 | 1 | Dam | 0/32 | 0.07 % (74) | 1 | 0.35 ms | 0.53 ms | 53.4 ms |
| NAME | 8 | 2 | Dam | 32/32 | 100 % | 2 175 | 41.5 ms | 40.6 ms | 38.7 ms |
| NAME | 12 | 2 | Dam | 0/32 | 10.2 % (10 183) | 7 | 6.65 ms | 9.56 ms | 51.1 ms |

Readings, on top of §4.2:

- **The bound holds under both metrics with the matching constant** — the assertion that failed the first
  benchmark passed every row here, including transposition-damaged patterns under Damerau with `c = 4`.
- **Damerau costs real selectivity.** At one edit L = 8 the candidate share doubles (1.0 → 2.3 % `WORD`,
  2.3 → 5.2 % `NAME`); at two edits L = 12 it goes from under 1 % to 9–10 %, and the end-to-end time from
  0.6–0.7 ms to 5–7 ms — still 7–8× under the scan, but no longer "nearly exact". The 7 / 11 cut-off is
  not the only price of Damerau; the thresholds above it are looser too.
- **The prefix filter is not faster at this corpus size.** It is asymptotically the right primitive
  (§4.3) — it never iterates a large posting — but at 100 000–257 000 values the Roaring `contains` probes
  cost more than a dense counter's sequential sweep, and it comes out 1.0–2.5× slower on candidate
  generation. Which primitive wins is a property of the real corpus size and posting skew; it has to be
  measured there, not decided here.
- **Below the cut-off the union arms are a scan plus bookkeeping** (30–42 ms against a 25–39 ms scan):
  those rows measure the overhead of pretending, nothing else.

## 5. The rule: edit distance follows from pattern length

### 5.1 The formula

Let `u` be the number of **distinct** trigrams of the typed pattern after the attribute's normalization (NFD,
case kept). The index can only count distinct trigrams (`TrigramCodec.extractUniqueTrigrams` deduplicates),
so the rule has to be stated in `u`, not in the pattern length: `abcabc` has L = 6 but only `u = 3`.

One edit touches a character that sits in at most `c` trigram windows:

- `c = 3` for a substitution, insertion or deletion (one character, three windows);
- `c = 4` for an adjacent transposition, which touches two characters — so under **Damerau** (a swap costs
  one edit, which is what Lucene's `LevenshteinAutomata(..., withTranspositions = true)`, the fulltext walker
  and every product engine use) the per-edit constant is 4, not 3. `kalhoty` vs `kahloty` is the
  counter-example to `u − 3d`: Damerau distance 1, one shared trigram, threshold 2.

A value containing a substring within distance `d` of the pattern therefore shares **at least `u − c·d`** of
its distinct trigrams. That bound is the whole method:

- it is sound only while `u − c·d ≥ 1`;
- turned around, the largest edit distance the index can guarantee is `d_max = (u − 1) / c`, integer
  division, capped at the product maximum of 2.

For a pattern without repeated trigrams `u = L − 2`, which gives the length table (repeats only make it
stricter — a repetitive pattern gets *less* tolerance than its length suggests):

| typed length L (no repeats) | distinct trigrams u | d_max, Levenshtein (c = 3) | d_max, Damerau (c = 4) |
|---|---|---|---|
| 1–2 | 0 | — (no trigram; exact scan, as today) | — |
| 3–5 | 1–3 | 0 | 0 |
| 6 | 4 | 1 | 0 |
| 7–8 | 5–6 | 1 | 1 |
| 9–10 | 7–8 | 2 | 1 |
| 11 and more | 9+ | 2 | 2 |

In words: **plain Levenshtein gives 6 / 9, Damerau gives 7 / 11** — for a *single* threshold. The metric is
decided (Damerau, §5.4); whether the cut-offs are 7 / 11 or 6 / 9 depends on how the transposition is handled,
and §5.4 shows the second way. Under plain Levenshtein `bnuda` → `bunda` would be distance 2 and need L ≥ 9
to be found at all, which is the practical reason the decision went to Damerau.

So the configuration carries **one number, the maximum `d`** (1 or 2), and each query derives its own:
`d = min(maxConfigured, (u − 1) / c)`. The same `d` is then used in both places it appears — as the
threshold of the candidate generator and as the distance handed to the verification — which is what keeps
the superset guarantee intact.

### 5.2 How it compares to the engines' length rules

The engines in the prior-art document's §3 have length thresholds chosen for *quality* ("bike" must not
find "like"); ours falls out of what a trigram can *guarantee*. They land close together:

| | 1 edit from | 2 edits from | reason |
|---|---|---|---|
| evitaDB fuzzy `contains`, plain Levenshtein | 6 | 9 | structural: below it the index proves nothing |
| evitaDB fuzzy `contains`, Damerau | 7 | 11 | structural, a swap touches four windows |
| Meilisearch `minWordSizeForTypos` | 5 | 9 | product default |
| Typesense `min_len_1typo` / `min_len_2typo` | 4 | 7 | product default (docs) |
| Elasticsearch `fuzziness: AUTO` | 3 | 6 | product default |
| Lucene `FuzzySuggester` | 3 (max 1 edit) | never | library default |

Ours is a notch stricter than Meilisearch (two notches under Damerau) and cannot be loosened by
configuration. For the fulltext term
dictionary (`typo-tolerance-fulltext-dictionary.md`) no structural cut-off exists — the automaton handles two
edits on a four-letter word — so the thresholds there are a quality decision; using the same 6/9 for both
would keep search and `contains` from disagreeing, but there it is a choice and here it is a necessity.

### 5.3 Expected behaviour, as a flow

1. Normalize the typed pattern as the attribute's index does; measure `L`.
2. Run the **exact** path first: today's all-trigram intersection and `contains` (2–6 µs). Enough results →
   done. (Typesense's `typo_tokens_threshold` is the same idea: typos are a fallback, not always on.)
3. Extract the distinct trigrams, `u` of them, and derive the tolerance from the length (§5.1, §5.4, §5.5):
   - L ≤ 3: exact only — nothing to enumerate, nothing to threshold;
   - `d = 1`, any L ≥ 4: **enumerate** the one-edit neighbourhood (deletions, substitutions and insertions
     over the index's alphabet, swaps) and look each variant up exactly; no threshold is involved;
   - `d = 2` (when configured) and L ≥ 9: **swap expansion** — Levenshtein-2 neighbourhood of the pattern
     at threshold `u − 6`, Levenshtein-1 neighbourhood of each swapped variant at `u' − 3`, doubly swapped
     variants exactly; below L = 9 the second edit is not offered.
4. Verify each candidate with the approximate substring distance under Damerau; stop at the first `d'` that
   yields results. An empty candidate set from a sound generator is a **proof** of an empty result — no scan
   follows.
5. A scan of the whole value set appears nowhere in this flow.

Expectations that follow: a single typo in any fragment of 4+ characters is found (by enumeration — with a
threshold it would take 6+, or 7+ under Damerau, and land exactly on the threshold, example G); two typos
need 9+ characters; a short mistyped fragment (`saki`
for `sako`) is not found, by design; and a missing diacritic costs one edit *when it sits inside the matched
region* and nothing when it sits at its end, because `contains` does not fold and Sellers has a free end
(example H) — that position-dependence is the one semantic question the numbers do not settle.

### 5.4 Decision: Damerau — and the two ways to get it

**Decided 2026-10-02: a fuzzy `contains` counts an adjacent swap as one edit.** Reasons: it is the most
frequent keyboard mistake; the fulltext walker on this branch already uses it (`LevenshteinAutomata(...,
withTranspositions = true)`); every engine in the prior-art document's §3 does; and under plain Levenshtein
the showcase
`bnuda` → `bunda` is a two-edit case that a 5–8 character fragment can never reach. (The variant in use is
the *restricted* Damerau–Levenshtein — optimal string alignment, a swapped pair is not edited again — which
is exactly what Lucene implements; the unrestricted form differs only in contrived cases.)

The per-edit constant cannot be chosen from the input. The threshold has to cover every way the typed
pattern *could* have arisen within `d` edits, and `kahloty` looks the same whether it came from one swap or
from two substitutions. So with one threshold the constant is 4 and the cut-offs are 7 / 11 — fixed. What
*can* be chosen is how the transposition enters the candidate generation.

#### The mechanism: a threshold is a sieve on the data, an enumerated variant is a key

The threshold is **not a property of the typed word**. The word always has the same `u` trigrams; the
threshold says how many of them a *value in the index* must contain to become a candidate, and "at least 1
of 5" and "at least 2 of 5" admit very different sets of values. Take `kahloty` (`kah ahl hlo lot oty`):

- **A, one threshold `5 − 4 = 1`.** Every value containing *any one* of the five trigrams is a candidate:
  `kalhoty` (it has `oty`), but also `pilot`, `balot`, `lotos` (`lot`), `kahan` (`kah`) — thousands of
  values in a corpus of 100 000 names. The threshold had to drop to 1 because the one edit *might* have been
  a swap, which destroys four windows; the price is paid on every value, whatever the edit actually was.
- **B, Levenshtein query with threshold `5 − 3 = 2`.** A value must share *two* of the five trigrams.
  `pilot` has one and falls through; so does `kalhoty` — correctly, it is at Levenshtein distance 2.
- **B, the `L − 1 = 6` swapped variants, looked up exactly.** `akhloty`, `khaloty`, `kalhoty`, `kaholty`,
  `kahltoy`, `kahloyt` — each as today's all-trigram intersection (2–6 µs), i.e. "contains this string
  verbatim". Five of them occur nowhere; `kalhoty` occurs in exactly the value that should be found. No
  letter is guessed: a swap of two neighbours is one of only `L − 1` strings, so the swap is *enumerated*
  rather than *tolerated by a looser sieve*.

Why nothing else is needed, and why B ⊆ A always: a value within restricted Damerau distance 1 of the
pattern is either within Levenshtein distance 1 (shares ≥ `u − 3` trigrams — caught by the first query) or
contains one swapped variant verbatim (caught by one exact query); there is no third case. And every
candidate of B shares at least one trigram with the pattern, so it is a candidate of A too. The same
structure holds at `d = 2`, one level up.

**A — single threshold, `c = 4`.** One counted union (or prefix filter) with `u − 4d`.

**B — swap expansion, `c = 3` plus explicit variants.** At `d = 1`: one candidate generation with
threshold `u − 3` plus `L − 1` exact lookups of the swapped variants. At `d = 2`: Levenshtein-2
neighbourhood of the pattern (threshold `u − 6`) ∪ Levenshtein-1 neighbourhood of each of the `L − 1`
swapped variants (threshold `u' − 3` each) ∪ exact occurrences of each doubly swapped variant (two
non-overlapping swaps, as restricted Damerau never edits a swapped pair again) — `L − 1` extra counted
unions and about `(L − 1)²/2` exact lookups. Cut-offs 6 / 9 instead of 7 / 11.

#### Measured (2026-10-02, arm `swapExpansion` of `FuzzyContainsCandidateBenchmark`, Damerau rows, short run)

The superset assertion held for B in every row — the decomposition misses nothing the verification finds.
This run was 1.5–3× noisier than §4.4 (a loaded machine), so only the ratios inside a row are meaningful.
Times are candidate generation / generation + verification:

| shape | L | d | A candidates | B candidates | true | A: gen / +verify | B: gen / +verify |
|---|---|---|---|---|---|---|---|
| WORD | 8 | 1 | 2.3 % (5 808) | 1.0 % (2 682) | 101 | 0.21 / 1.15 ms | 0.28 / 0.83 ms |
| NAME | 8 | 1 | 5.2 % (5 169) | 2.3 % (2 338) | 112 | 0.35 / 2.61 ms | 0.19 / 1.21 ms |
| WORD | 12 | 1 | 0.11 % (271) | 0.02 % (44) | 3 | 0.38 / 0.50 ms | 0.44 / 0.39 ms |
| NAME | 12 | 1 | 0.07 % (74) | 0.01 % (7) | 1 | 0.24 / 0.33 ms | 0.24 / 0.29 ms |
| WORD | 12 | 2 | 8.9 % (22 762) | 0.46 % (1 169) | 6 | 0.34 / 4.94 ms | 2.40 / 3.17 ms |
| NAME | 12 | 2 | 10.2 % (10 183) | 0.61 % (609) | 7 | 0.23 / 7.59 ms | 1.25 / 2.94 ms |
| WORD | 8 | 2 | whole corpus | 64 % (20/32 whole) | 654 | — / 49 ms | 0.93 / 36 ms |
| NAME | 8 | 2 | whole corpus | 68 % (20/32 whole) | 2 175 | — / 48 ms | 0.63 / 40 ms |
| WORD | 5 | 1 | whole corpus | 57 % (18/32 whole) | 2 476 | — / 29 ms | 0.20 / 16 ms |
| NAME | 5 | 1 | whole corpus | 78 % (24/32 whole) | 2 498 | — / 39 ms | 0.12 / 24 ms |

Readings:

- **B's candidate set is 2–20× smaller than A's** at the same Damerau semantics — the sieve effect above.
- **Why more queries end up faster:** the expensive phase is not the index lookup (hundreds of µs) but the
  verification, ~0.5–1 µs per candidate, and that phase scales with the number of candidates. At L = 12,
  d = 2, A verifies 22 762 values (~4.6 ms) and B 1 169 (~0.8 ms); B pays ~2 ms more in generation and
  saves ~4 ms in verification. Where A is already nearly exact (L = 12, d = 1) the two tie.
- **B lowers the cut-off, but only partly.** At L = 5–6 and d = 1 the Levenshtein query is sound for the
  typed variants an insertion stretched to six (18–24 of 32 patterns) and B lands under the scan; at L = 8,
  d = 2 it is a scan with extra work. The 6 / 9 cut-off of B is real, and the rows just above it are still
  expensive.

Trade-off in one line: A is one index query with looser thresholds and a higher cut-off; B is up to `L`
index queries with tighter thresholds and the lower cut-off, its candidate set is a subset of A's, and on
this corpus it is never slower end to end. The remaining reason to prefer A is simplicity; the remaining
reason to measure again is the real corpus, whose posting skew decides how much B's extra queries cost.

### 5.5 Pushing the mechanism further: enumerating the other edits

If a swap can be enumerated and looked up exactly, so can the other edits — the only difference is how many
strings there are. For one edit on a pattern of length L over the corpus alphabet Σ (every code point that
occurs in the indexed values; Czech in NFD is a few dozen):

| edit | variants | known content? |
|---|---|---|
| adjacent swap | L − 1 | yes |
| deletion | L | yes |
| substitution | L · (\|Σ\| − 1) | the letter has to be enumerated |
| insertion | (L + 1) · \|Σ\| | the letter has to be enumerated |

Full enumeration of the restricted Damerau-1 neighbourhood is therefore roughly `(2L + 1)·|Σ| + 2L` exact
lookups — for L = 5 and |Σ| ≈ 45 about 500, at 2–6 µs each. **No threshold is involved at all**, so the
cut-off disappears: the only requirement is that a variant has a trigram, i.e. L ≥ 4. Candidates are exact
occurrences of neighbourhood strings, hence nearly all true matches, and the verification is cheap. This is
the query-side half of the classic spelling-corrector trick (generate all edits, look each up), applied to a
substring index.

Two limits before the numbers:

- **`d = 2` does not enumerate.** The two-edit neighbourhood is the square — hundreds of thousands of
  strings — and the hybrid "enumerate one edit, threshold the other" costs `|neighbourhood|` counted unions,
  which is scan territory. Below the 9 / 11 cut-off two edits stay a scan.
- **The alphabet can be pruned by the index itself.** A substituted or inserted letter `x` at position `i`
  only matters if the trigrams it creates exist in the index, and the trigram store is an ordered tree keyed
  by the packed `(a, b, c)` with `a` in the high bits, so a range scan on `(p[i−2], p[i−1], ·)` lists every
  `x` the corpus actually continues with — typically a handful, not |Σ|. Not implemented in the benchmark
  (`TrigramIndex` exposes no range scan today); it is the trigram-side analogue of the automaton walking the
  term dictionary instead of testing every key.

#### Measured (2026-10-02, arm `editEnumeration`, Damerau, one edit, short run on a loaded machine)

The superset assertion held for the enumeration in every row. Alphabet of the corpus: 38 code points.
`enum` is the full one-edit enumeration with exact lookups; A and B as in §5.4:

| shape | L | A cand. | B cand. | enum cand. (lookups) | true | A +verify | B +verify | enum gen / +verify | scan |
|---|---|---|---|---|---|---|---|---|---|
| WORD | 5 | whole corpus | 57 % | 0.98 % (2 527; 439) | 2 476 | 52 ms | 22.7 ms | 0.41 / 0.93 ms | 33 ms |
| NAME | 5 | whole corpus | 78 % | 2.65 % (2 651; 430) | 2 498 | 55 ms | 39.0 ms | 0.89 / 1.33 ms | 33 ms |
| WORD | 8 | 2.3 % | 1.0 % | 0.04 % (102; 673) | 101 | 1.76 ms | 1.11 ms | 1.27 / 0.88 ms | 42 ms |
| NAME | 8 | 5.2 % | 2.3 % | 0.17 % (169; 656) | 112 | 3.97 ms | 1.65 ms | 1.00 / 0.75 ms | 46 ms |

(The generation-only column exceeding the generation-plus-verification one at L = 8 is run noise; both are
about 1 ms.)

Readings:

- **Below every threshold cut-off, where A and B are scans, the enumeration answers in ~1 ms** — 25–35×
  under the scan on a quarter-million values — and its candidate set is essentially the set of true matches
  (2 527 vs 2 476; 2 651 vs 2 498). Four to five hundred exact lookups at 2 µs each is simply cheap.
- **At L = 8 it is better than B too** (0.75–0.88 ms vs 1.1–1.65 ms), because its candidates are nearly
  exact (102 vs 101 true, 169 vs 112) and verification all but disappears; the ~650 lookups cost about
  what B's one counted union plus seven exact lookups cost.
- **The one-edit cut-off therefore moves from 6 / 7 to 4**, and the "short search-box fragment" case that
  §4.2 called unservable is served — at one edit. Two edits keep the 9 / 11 cut-off (§5.5, first limit).
- The lookups scale with `L · |Σ|`; on a corpus with a larger alphabet (mixed scripts, many digits and
  symbols in codes) the index-pruned alphabet of §5.5 is what keeps the count in the hundreds.

## 6. Worked example, step by step

Five values of a `name` attribute, configuration "exact first, then up to one edit" (`maxConfigured = 1`),
plain Levenshtein unless a case says otherwise (`c = 3`, cut-off `u ≥ 4`, i.e. L ≥ 6 without repeats).
Patterns are lowercase NFD; trigrams are written with `␣` for a space. Lengths count code points, so a
character with a diacritic in NFD counts two (`ě` = `e` + combining caron).

```text
1  kalhoty modrý lampa
2  kalhotky dětské
3  kabát zimní
4  mikina
5  sako
```

**A. `ka` (L = 2).** Below the three code points a trigram needs. The trigram index is not consulted; the
exact predicate runs as a scan over the values, as today. Result 1, 2, 3. No typo tolerance offered.

**B. `kalh` (L = 4, n = 2).** Exact path: `kal` ∩ `alh` → candidates 1, 2 → `contains` confirms both →
result 1, 2. Fuzzy would need `2 − 3 < 0`, so it is not offered; `kalx` would return nothing and the UI
would say "typo tolerance from 6 characters".

**C. `kalhoty` (L = 7, n = 5, no typo).** Exact path: all five trigrams intersect → candidate 1 → confirmed
→ result 1. Enough results, fuzzy never runs.

**D. `kalhoky` (L = 7, n = 5, `t` → `k`).** Exact: trigrams `kal alh lho hok oky`; `hok` and `oky` occur in
no value → empty. Fuzzy, `d = (7 − 3) / 3 = 1`, threshold `5 − 3 = 2`: counters — value 1: `kal alh lho`
= 3; value 2: `kal alh lho` = 3; values 3, 4, 5: 0. Candidates 1, 2. Verification (substring within 1):
value 1 holds `kalhoty`, one substitution from `kalhoky` → match; value 2 holds `kalhotky`, one insertion
→ match. Result 1, 2, both found through the typo.

**E. `kahoty` (L = 6, n = 4, dropped `l`).** Trigrams `kah aho hot oty`. Exact: `kah` nowhere → empty.
Fuzzy, `d = 1`, threshold `4 − 3 = 1`: value 1 has `hot oty` = 2, value 2 has `hot` = 1 → both candidates.
Verification: value 1 holds `kalhoty`, one insertion from `kahoty` → match. Value 2: its closest substring
`kalhotk` is at distance 2 (insert `l`, substitute `k`/`y`) → **rejected**. Result 1. Value 2 is the
false candidate the verification exists for — the benchmark sees 1–10× more candidates than matches.

**F. `saki` (L = 4).** As B: `sak` ∩ `aki` → `aki` nowhere → empty; fuzzy below the cut-off. Empty result
although `sako` is one character away. That is the price of the cut-off, and Meilisearch pays the same one
(`saki` has four characters, tolerance there starts at five).

**G. `kalhotkz dětské` (L = 17 in NFD, u = 15, one substitution).** Exact: `tkz`, `kz␣`, `z␣d` nowhere →
empty. Fuzzy, `d = 1`, threshold `15 − 3 = 12`: value 2 shares exactly 12 trigrams (15 minus the 3 the typo
destroyed) → candidate → verification: one substitution → match. One typo always lands exactly on the
threshold; that is the guarantee of §5.1 in practice.

**H. `kalhotkz detske` (L = 15, diacritics dropped).** In NFD, `ě` and `é` are a base letter plus a
combining mark, so each missing mark is one deleted code point. Against value 2 (`kalhotky dětské`, L = 17
in NFD) the typed string differs by the `z` and by the two missing marks — but Sellers matches a
*substring* with a free end, and the trailing mark of `é` sits outside the matched region
`kalhotky de◌̌tske`, so it costs nothing. Distance is **2** (the `z` and the inner caron): nothing at
`d = 1`; at `d = 2` (`maxConfigured = 2`, threshold `13 − 6 = 7` on the typed pattern's 13 trigrams)
value 2 is a candidate and the verification **accepts** it. Two lessons: a missing diacritic costs one edit
inside the matched region and none at its end, so the semantics are position-dependent; and `contains`
does not fold, so a fuzzy `contains` spends its edits on diacritics — exactly what the fulltext chain avoids
by folding in the analyzer. This is the semantic question of §3 and §8.

**I. `maxConfigured = 2` and `kalhokz` (L = 7, two typos).** Cascade: `d' = 0` empty; `d' = 1`, threshold 2,
candidates 1, 2, verification at distance 1 rejects both (they are at 2); `d' = 2` requires L ≥ 9 and the
pattern has 7 → not permitted. Empty result. Two typos in a seven-character word are beyond what trigrams
can guarantee, and that is a hard limit of the method, not a tuning parameter.

**J. `kahloty` (L = 7, u = 5, adjacent swap `lh` → `hl`) — why the metric matters.** Trigrams `kah ahl hlo
lot oty`; value 1 holds `kalhoty` and shares only `oty` (the swap touched two characters and destroyed
four windows). Under plain Levenshtein the swap is distance 2, so at `maxConfigured = 1` the pattern is
simply not within tolerance and the empty result is correct. Under Damerau the swap is distance 1 and value
1 **is** a true match — but with the Levenshtein threshold `5 − 3 = 2` it has one shared trigram and would
be dropped silently. With the Damerau constant the threshold is `5 − 4 = 1`, the value is a candidate and
the verification accepts it. Same pattern, same data, and the answer is right only if the per-edit constant
matches the metric of the verification.

## 7. What the trigram index *could* still contribute to the fulltext side

If option B of §3 (an automaton walk over the value tree, i.e. fuzzy *equals* / *prefix*) is ever built, the
trigram index is a cheap **pre-filter** for it in the shape where the counted union is most selective —
long patterns against a large dictionary of short values — and a **test oracle** for the automaton walk
(the counted union is a proven superset; the walk's result must be a subset of it and must survive
verification). Neither is a reason to build it; both are reasons not to rip it out if B lands.

## 8. Where this leaves the contains decision

Fuzzy `attributeContains` over the trigram index is **worth building as an opt-in**, with the rule of §5
baked in rather than configurable:

- above the cut-off (6 / 9 distinct-trigram lengths under plain Levenshtein, 7 / 11 under Damerau) it is
  sound and roughly **7–150× cheaper than a scan** (§4.4): ~100× at one edit on long fragments, 12–25× at one
  edit on 8-character fragments, and only 7–8× for Damerau at two edits, where the looser threshold admits
  9–10 % of the corpus as candidates. Under Damerau it is not only the cut-off that moves up — every
  threshold above it is looser by `d` trigrams. All of it is a constant-factor win with the dense counter
  the benchmark used; the asymptotically better prefix filter lost to it at this corpus size (§4.3, §4.4);
- below the cut-off — now 4 for one edit, 9 for two — there is no typo tolerance and the exact predicate
  runs alone, which has to be said to the user, as every product engine does;
- the superset property must be asserted in tests under the metric actually used, with all four edit
  kinds, because its failure is silent;
- **the metric is decided: Damerau** (§5.4), and the shape is tiered by edits (§5.3 step 3): at one edit,
  **full enumeration** of the neighbourhood with exact lookups — no threshold, cut-off L ≥ 4, ~1 ms per
  pattern on a quarter-million values, candidates ≈ true matches (§5.5); at two edits, **swap expansion**
  (threshold `u − 3` queries plus enumerated swaps, cut-off 9). The single `c = 4` threshold (A) is
  dominated on every measured row and stays only as the simplest correct baseline. Confirmation on the real
  corpus — in particular the alphabet size, which drives the lookup count — is still owed;
- the open semantic questions are case (today's predicate is case-sensitive) and diacritics (one edit per
  missing mark under NFD) — both are product decisions about what "contains with typos" should mean, not
  engineering ones, and they decide whether the feature is useful for Czech input at all.

What it is **not**: a replacement for "search with typos". A typo is a property of a word, the fulltext
dictionary already tokenizes, lowercases, folds and stems, and `typo-tolerance-fulltext-dictionary.md` covers
that. Fuzzy `contains` is the narrower feature for the attributes that are substring-searched today.
