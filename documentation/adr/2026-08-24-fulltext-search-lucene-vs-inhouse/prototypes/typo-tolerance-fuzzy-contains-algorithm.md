# Typo tolerance for `attributeContains` — the algorithm to implement

> Status: implementation specification, extracted on 2026-10-21 from
> [`typo-tolerance-fuzzy-contains.md`](typo-tolerance-fuzzy-contains.md) (the **analysis**). This document states
> only *what* to build. Why each rule was chosen, the alternatives and the measurements are in the analysis; every
> rule here points to its section there as **[A §x]**. Where this document and the analysis disagree, the analysis
> is the source and this document has a bug.
>
> Typo tolerance in the fulltext term dictionary is a separate feature, specified in
> [`typo-tolerance-fulltext-dictionary-algorithm.md`](typo-tolerance-fulltext-dictionary-algorithm.md). Vocabulary
> (edit distance, restricted Damerau–Levenshtein, approximate substring matching) is defined in
> [`typo-tolerance-prior-art.md`](typo-tolerance-prior-art.md).

Every rule is marked **decided** or **proposed** (designed, not yet signed off). Where this document fills a gap the
analysis leaves unstated, the rule is marked *(inferred)*. Questions nobody has answered are listed in §9, not mixed
into the rules.

**Budget.** No latency budget is set for a fuzzy `contains`; the measured costs of the decided shape are in
[A §6.3, §6.5].

---

## 1. Overview

A fuzzy `attributeContains` finds the values that contain a substring within `d` edits of the typed pattern. It is
an **opt-in** on top of today's `SUBSTRING_SEARCH` accelerator: the trigram index stays a candidate generator, and
correctness comes from verifying every candidate [A §2.1, §5.4]. Nothing new is stored; the index is the existing
`TrigramIndex` [A §5.4].

```
typed pattern
  │
  ├─ 1  normalize as the attribute index does                  → code points, L, distinct trigrams u   (§2)
  ├─ 2  exact path (today's attributeContains)                  → results? done                         (§6)
  ├─ 3  tolerance from the length                               → d = 0, 1 or 2                         (§3)
  ├─ 4  candidate generation: swap expansion                    → candidate value ids                   (§4)
  │        Levenshtein trigram query + swapped variants looked up exactly
  ├─ 5  verification                                            → values within d edits                 (§5)
  └─ 6  cascade                                                 → first d that yields results           (§6)
```

A scan of the whole value set appears nowhere in this flow [A §7 step 5]; the exceptions are the existing declines
of §7.

---

## 2. Normalization and length — decided

- The pattern is normalized **exactly as the attribute's value tree normalizes values** [A §2.1, §4.1]: the
  attribute's declared `normalized(...)` chain, if any, then Unicode NFD (#545, 2026-10-27, after this document was
  extracted). That is the shared value tree's normalizer, which `TrigramSubstringSearch.match` already applies
  (`sharedValueTree.getNormalizer()`); the trigram index was built from keys normalized the same way, and
  verification (§5) compares normalized value with normalized pattern.
- `L` is the length of the normalized pattern in **code points**. In NFD a letter with a diacritic counts two
  (`ě` = `e` + combining caron) [A §8].
- `u` is the number of **distinct** trigrams of the normalized pattern (`TrigramCodec.extractUniqueTrigrams`); a
  repeated trigram counts once [A §4.1].
- **Diacritics are not handled by the algorithm — decided.** `contains` does not fold on its own. Distances are
  measured over the NFD code points, so a missing or wrong mark is an ordinary edit when it falls inside the matched
  region and none at its end [A §1, §7, §8 example H]. An attribute that declares `asciiFolding()` in its
  `normalized(...)` chain is folded on both sides before any of this runs.
- **Case follows the attribute's normalization — decided 2026-10-30.** An attribute declaring `lowercase()` in its
  `normalized(...)` chain is matched case-insensitively, by the exact and the fuzzy path alike; one without it stays
  case-sensitive, exactly as its exact `contains` is. The fuzzy path adds no case rule of its own, so the two paths
  of the cascade (§6) never disagree about case. The user documentation of fuzzy `contains` should recommend
  `normalized(lowercase())` for attributes meant for typo-tolerant search.

---

## 3. Limits — decided

| Normalized length `L` | Tolerance |
|---|---|
| 1–5 | exact only |
| 6–11 | up to 1 edit |
| 12 and more | up to 2 edits, **when configured** |

[A §1, §7 step 3]

- The limits are **fixed**, not configurable [A §1].
- The one-edit limit is the structural floor of the generator: its Levenshtein query needs `u − 3 ≥ 1`, i.e.
  `L ≥ 6` without repeated trigrams [A §4.1, §7 step 3]. The two-edit limit sits above its structural floor of 9
  [A §4.2, §6.5].
- The metric is **restricted Damerau–Levenshtein** (optimal string alignment): an adjacent swap costs one edit and a
  swapped pair is not edited again [A §6.1]. The same metric is used by the generator's decomposition and by the
  verification [A §6.1, §6.2], with the same `d` [A §4.1].
- Below the limits there is no typo tolerance, and the user has to be told so, as every product engine does
  [A §1, §5.4].

---

## 4. Candidate generation — swap expansion — decided

A value within restricted Damerau distance `d` of the pattern is either within Levenshtein distance `d` of it, or
contains a swapped variant within Levenshtein distance `d − 1` [A §6.2]. The candidate set is therefore a union of
**Levenshtein trigram queries** — values sharing at least `u − 3·k` of a string's `u` distinct trigrams, for `k`
remaining edits — and **exact lookups** through today's all-trigram intersection
(`TrigramIndex#resolveCandidateValueIds`) [A §6.2, §7 step 3].

### 4.1 One edit

1. the **Levenshtein-1 neighbourhood of the pattern**: values sharing at least `u − 3` of its distinct trigrams;
2. the **exact occurrences** of each of the `L − 1` **single swaps** of the pattern.

### 4.2 Two edits

1. the **Levenshtein-2 neighbourhood of the pattern**: values sharing at least `u − 6` of its distinct trigrams;
2. for each of the `L − 1` single swaps, its **Levenshtein-1 neighbourhood**: values sharing at least `u' − 3` of
   that variant's `u'` distinct trigrams;
3. the **exact occurrences** of every **double swap** — two non-overlapping adjacent swaps, `(L − 2)(L − 3) / 2`
   strings (the analysis rounds it to about `(L − 1)² / 2`).

### 4.3 Rules common to both

- Every threshold query uses the Levenshtein constant `c = 3` on its own distinct trigram count [A §6.2].
- A threshold query whose threshold is `≤ 0` guarantees nothing and would take the whole corpus [A §4.1]. Above the
  limits of §3 that happens only through repeated trigrams, which give a pattern less tolerance than its length
  suggests [A §4.1]. In that case the edit level is **not offered** for this pattern and the cascade stops one level
  lower *(inferred: the flow of [A §7] admits no scan)*.
- **Not used** — both measured, neither adopted:
  - **full enumeration** of the one-edit neighbourhood over the corpus alphabet, which would serve one edit below 6
    characters [A §1, §6.4, §6.5];
  - the **single `u − 4d` threshold** (variant A of the analysis), kept only as the simplest correct baseline
    [A §1, §6.3] — in tests, the one the others are compared against *(inferred)*.

### 4.4 Counting the threshold queries — to be decided during implementation

Every threshold query of §4.1–§4.2 asks which values hold at least `k` of a string's `u` distinct trigrams. Two
sound ways to answer it return **identical** candidates [A §5.3, §5.4, §6.6]:

- **Dense counter** — a counter array indexed by value id: for each trigram, increment the counter of every id in
  its posting; then keep the ids whose count reaches `k`. Every posting is read once, sequentially; the array is
  filled and swept once per threshold query, so the cost grows linearly with the attribute's distinct values. It
  needs a counter array per concurrent query, sized by the largest value id (`byte[]` suffices, a count never
  exceeds `u`), and it assumes value ids are dense `1..N` — check how the shared value tree mints ids and what a
  deleted value leaves behind before indexing an array by them [A §5.4].
- **Prefix filter** — a value holding `k` of `u` trigrams appears in at least one of any `u − k + 1` of them, so
  iterate only the `u − k + 1` **smallest** postings (4 at one edit, 7 at two) and count each id found by
  membership tests against the other postings; collect the candidates in a bitmap. It never reads a large posting
  and never touches all values, so its cost follows the small postings rather than the attribute size, and it
  needs no scratch array; each membership test is a random lookup rather than a sequential increment [A §5.4].

Measured inside swap expansion on a real attribute of 942,632 distinct values [A §6.6]:

| edits | L | generation: dense counter | generation: prefix filter | end to end: dense counter | end to end: prefix filter |
|---|---|---|---|---|---|
| 1 | 6 | 1.5 ms | 4.5 ms | 274 ms* | 276 ms* |
| 1 | 7 | 2.0 ms | 5.1 ms | — | — |
| 1 | 8 | 1.8 ms | 3.8 ms | 9.5 ms | 10.3 ms |
| 1 | 12 | 2.3 ms | 4.4 ms | 2.2 ms | 4.8 ms |
| 2 | 12 | 11.6 ms | 28.3 ms (±61 ms) | 90 ms | 87 ms |
| 2 | 16 | 21.1 ms | 22.1 ms | 22.6 ms | 22.0 ms |

\* Inflated for both alike by patterns the benchmark left below 6 typed code points [A §6.6].

On that attribute the dense counter is never slower and about 2× faster at generation; end to end the two are
close, because verification dominates. The prefix filter's advantage — a cost independent of the attribute size —
did not show at this size. **Which one ships is decided during implementation**, with the dense-id check as its
precondition for the dense counter and the expected attribute sizes as the deciding input. The benchmark arms
`swapExpansion` (dense counter) and `swapExpansionPrefixFilter` of `FuzzyContainsCandidateBenchmark` are the
reference implementations.

---

## 5. Verification — decided

- Every candidate is verified by **approximate substring matching** (Sellers): the pattern matches a value when some
  substring of the value is within `d` edits — a free start and a free end in the value [A §2.2].
- Under restricted Damerau, over the normalized code points of §2, with the **same `d`** the generator used
  [A §4.1, §6.1].
- Prototype: `EditDistances.approximatelyContains(text, pattern, maxEdits, transpositions = true)` in
  `evita_test_support` (`io.evitadb.test.fulltext`); it moves into the engine as the verification predicate, in
  place of `String.contains` on this path [A §3 option A, §5.4].

---

## 6. Cascade — decided

1. **Exact path first** — today's all-trigram intersection and `contains`. If it yields results, the query is done;
   typos are a fallback, not always on [A §7 step 2]. *(Inferred: "enough results" there is read as "any result",
   the rule step 4 states for the fuzzy steps.)*
2. Then `d' = 1` (`L ≥ 6`), then `d' = 2` (only when configured and `L ≥ 12`): generate (§4), verify (§5), and
   **stop at the first `d'` that yields results** [A §7 step 4].
3. An empty candidate set from a sound generator is a **proof** of an empty result — no scan follows [A §7 step 4].

---

## 7. Declines and the gate

**No gate of its own — decided.** Above the limits of §3 the fuzzy path always takes the trigram index. The existing
A/B gate (`REQUIRED_NARROWING_FACTOR`) weighs the index against the exact scan; the fuzzy path's alternative is a
full approximate scan, which every measured configuration above the limits beats [A §1, §6.5].

The existing declines of `TrigramSubstringSearch.match` stay:

| Situation | Behaviour | Source |
|---|---|---|
| attribute without the `SUBSTRING_SEARCH` accelerator | fuzzy `contains` not offered — **proposed**: the parameter is accepted only where the attribute declares the accelerator | [A §5.4] |
| normalized pattern shorter than 6 code points | exact only (§3) | [A §7] |
| fewer than 256 distinct values in the attribute (`MINIMAL_ACCELERATED_DISTINCT_VALUE_COUNT`) | declined today, because one leaf block is scanned faster than any candidate generation; the fuzzy path inherits it and scans with the approximate predicate *(inferred from the existing decline)* | `TrigramSubstringSearch` javadoc |
| a transaction open on the calling thread | declined today and the caller scans; the fuzzy path inherits that — the scan applies the approximate predicate to every value. Answers stay correct, only the cost changes *(inferred from the existing decline)* | `TrigramSubstringSearch` javadoc |

---

## 8. Configuration and query surface

**Decided:** the configuration carries **one number, the maximum `d`** (`maxConfigured`, 1 or 2); each query
derives its own `d` from the limits of §3 [A §4.1, §1]. Nothing goes into the stored index — the trigram index is
used as it is [A §5.4].

**Proposed:** a parameter on the existing constraint rather than a new one, for example
`attributeContains(name, "cerna", typo(1))`; the exact DSL shape is not decided [A §3 option A, §5.4]. It goes
through every layer of a query constraint — query model, EvitaQL grammar, Kryo serializer, gRPC, GraphQL and REST
(the `new-constraint` skill lists them) [A §3 option A].

**Scope — decided:** `attributeContains` only.

- `attributeEndsWith` shares the accelerator but gets **no fuzzy form**.
- `attributeStartsWith` is answered by a range walk over the sorted value tree; its fuzzy form would be fuzzy
  *prefix* — option B of the analysis, not part of this feature [A §2.1, §3 option B].

---

## 9. Open — not part of the algorithm yet

| Item | State | Analysis |
|---|---|---|
| Counting primitive | dense counter or prefix filter — decided during implementation (§4.4) | [A §5.3, §5.4, §6.6] |
| Transaction fallback cost | inside a transaction the fuzzy path is a full approximate scan (§7) — the costliest arm measured | [A §6.5] |

---

## 10. Tests the implementation must carry

- **Superset property** — every value within `d` edits of the pattern is among the candidates; asserted under the
  metric actually used, on patterns damaged by **all four** edit kinds (substitution, insertion, deletion,
  adjacent swap), over several seeds. Its failure is silent — missing results, no exception — so the test fails on
  the first missed match [A §1, §5.2, §5.3].
- **Equivalence to the scan** — the fuzzy path returns exactly the set a full approximate scan returns, for one and
  two edits [A §5.2].
- **Limits** — 5 code points: exact only; 6: one edit; 11: one edit only; 12: two edits when configured, one when
  not; the length counted in NFD code points; a pattern of 6 or more code points whose repeated trigrams leave
  `u − 3 ≤ 0` gets no tolerance [§2, §3, §4.3].
- **Cascade** — an exact hit stops before any fuzzy work; a `d' = 1` hit stops before `d' = 2`; an empty sound
  candidate set returns empty without a scan [§6].
- **Swaps** — at one edit a transposition the Levenshtein query cannot reach is found through its swapped variant
  (`kahloty` → `kalhoty`); at two edits the double swaps never overlap [A §6.2, §8 example J].
- **Behaviour examples** — the five-value example of the analysis under the decided algorithm: `kalhoky` finds
  values 1 and 2, `kahoty` finds value 1 and rejects candidate value 2 in verification, `kalx` and `saki` not found
  (below 6), `kalhokz` not found (two edits below 12) [A §8].
- **No diacritics handling** — a pattern without a mark that the value carries inside the matched region costs one
  edit [§2, A §8 example H].
- **Declines** — inside a transaction, and on an attribute with fewer than 256 distinct values, the answer equals
  the accelerated answer [§7].
