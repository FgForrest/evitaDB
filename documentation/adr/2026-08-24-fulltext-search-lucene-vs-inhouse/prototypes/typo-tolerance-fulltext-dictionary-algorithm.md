# Typo tolerance in the fulltext term dictionary — the algorithm to implement

> Status: implementation specification, extracted on 2026-10-21 from
> [`typo-tolerance-fulltext-dictionary.md`](typo-tolerance-fulltext-dictionary.md) (the **analysis**). This document
> states only *what* to build. Why each rule was chosen, the alternatives and the measurements are in the analysis;
> every rule here points to its section there as **[A §x]**. Where this document and the analysis disagree, the
> analysis is the source and this document has a bug.
>
> The metric for fuzzy `attributeContains` over raw values is a separate feature, specified in
> [`typo-tolerance-fuzzy-contains.md`](typo-tolerance-fuzzy-contains.md). Vocabulary (Levenshtein automaton, frozen
> prefix, open-ended walk, …) is defined in [`typo-tolerance-prior-art.md`](typo-tolerance-prior-art.md).

Every rule is marked **decided** or **proposed** (designed, not yet signed off). Where this document fills a gap the
analysis leaves unstated, the rule is marked *(inferred)*. Questions nobody has answered are listed in §10, not mixed
into the rules.

**Budgets the implementation must meet** [A §2.2 O2, §11.1]:

| Query type | Budget |
|---|---|
| as-you-type | p99 ≤ 5 ms per keystroke for everything; the term leg ≤ 1.5 ms |
| submitted search | phase 1 ≤ 25 ms per 1 million candidates, single thread; term expansion not budgeted separately |

---

## 1. Overview

Typo tolerance is a **query-time term expansion** over the existing term dictionary. It adds nothing to the analyzer
chain and owns no data of its own, apart from the surface-form lane of §3 [A §2.1, §3.1].

```
query text
  │
  ├─ Q1  query analysis (existing czech-search etc.)  → per query word: typed folded form + stem variants
  ├─ Q2  thresholds (§4)                              → maxEdits and frozen prefix per walk
  ├─ Q3  term expansion (§5, §6)                      → per query word: set of (stem, distance, exactness)
  │        exact lookup · prefix scan · Levenshtein walk; over the stem dictionary and the surface-form lane
  ├─ Q4  matching (§8)                                → OR inside a word, AND across words
  ├─ Q5  ranking (§8)                                 → TYPO and EXACTNESS lanes of the composite
  ├─ Q6  suggester                                    → same expansion, shows the accented surface form
  ├─ Q7  highlighting                                 → needs which stem each typed word reached
  └─ Q8  explain                                      → per word: variant, matched term, distance, exactness
```

The unit of everything from Q3 down is the **query word**, never the expanded term: a word counts once however many
of its expansions hit, with the best distance among them [A §3.2 Q4, §3.3].

---

## 2. Building blocks

| Piece | Source | Notes |
|---|---|---|
| `LevenshteinAutomata`, `Automaton`, `CharacterRunAutomaton` | `lucene-core` (already a dependency) | used as-is [A §4.3] |
| Dictionary walker | port of Lucene's `AutomatonTermsEnum` walk; prototype `LevenshteinDictionaryWalker` in `evita_test_support` (`io.evitadb.test.fulltext`) | moves into the engine [A §5.1, §8.3 step 1] |
| Threshold function | own code; prototype `TypoThresholds` | ~20 lines [A §7.6] |
| Expansion reference | prototype `TypoExpansion` (with `UnfinishedWordRules`) | test oracle, not engine code [A §10] |
| Distance oracle | `EditDistances` (restricted Damerau–Levenshtein DP) | tests only |
| Stem dictionary | `TransactionalBucketBPlusTree<String>` in `FulltextIndex`, keyed by field prefix + folded stem | exists (P1) |

**Not used:** `FuzzyQuery`, `FuzzyTermsEnum`, `CompiledAutomaton`, `UTF32ToUTF8`, `BM25Similarity`,
`lucene-suggest`, `lucene-highlighter` [A §4.3].

### 2.1 Automaton parameters — decided

- **Alphabet: Unicode code points** (`alphaMax = Character.MAX_CODE_POINT`). Dictionary keys are `String`, so no
  UTF-8 conversion; `ě`→`e` would cost one edit, not two [A §3.1, §5.1].
- **Transpositions on (restricted Damerau).** An adjacent swap is one edit (`blakc`→`black`). Same metric as fuzzy
  `contains` [A §2.2 U2, §7.1].
- **Maximum distance 2.** `toAutomaton(3)` returns `null` instead of throwing — the caller enforces the cap
  [A §5.1, §7.1].
- **Frozen prefix 1** (`nonFuzzyPrefix = 1`): no edit in the first letter. Clamped to the word length:
  `prefix = min(1, len)` [A §2.2 U5, §7.1, §7.6].
- `LevenshteinAutomata` is `@lucene.experimental` — pin and cover by tests [A §5.1].

### 2.2 The guided walk — decided

Port of `AutomatonTermsEnum` over the B+ tree using only `cursor(K)` (seek) and `next()` [A §5.1]:

1. Seek to the smallest acceptable string (the frozen prefix).
2. Read keys sequentially while the automaton accepts them; every accepted key is a hit.
3. On a rejected key `k`, compute `nextString(k)` — the smallest string `> k` the automaton could still accept,
   by backtracking over the saved automaton states — and seek there.
4. Stop when no such string exists or the frozen prefix is left.

Constraints:

- The tree must be in natural `String` order, which equals code-point order inside the BMP [A §3.2 I2].
- The Levenshtein language is finite, so Lucene's visited-state bookkeeping is dropped [A §5.1].
- The seek of step 3 is today a fresh `tree.cursor(K)`, a root-to-leaf descent. §2.4 proposes replacing it with an
  intra-leaf seek.
- Build the widest automaton **once** per walk; never build the distance-2 automaton twice (once for the walk and
  once as an acceptor) — construction of `toAutomaton(2)` alone costs ~0.43 ms [A §10.2, §11.3].
- **Exact distance of a hit** is learned by testing it against the narrower acceptors, `ed2 → ed1 → ed0`
  (distance 0 is string equality), as `FuzzyTermsEnum` does [A §5.1, §8.3]. A restricted Damerau DP over the hit
  is an equivalent alternative [A §10.2].
- When the cascade runs a distance-2 walk after a distance-1 walk, the wider walk returns the distance-1 hits again;
  it **must skip terms already collected** [A §8.3 step 2].
- A cache of built automata, if one is ever added, keys on all of query string, `maxEdits`, frozen prefix and
  transpositions [A §7.6].
- The **linear scan with the same acceptor** stays as the test oracle; the walk must return exactly its set
  [A §5.5, §8.3 step 6].

### 2.3 The open-ended walk — decided

For an unfinished word: a key is accepted when **some prefix of it** is within `maxEdits` (language `L·Σ*`), so
`blakc` reaches `blackberry` [A §10.2].

- Seek logic is identical to §2.2; only the acceptance test changes: step the key through the automaton and accept
  at the first accepting state.
- The reported distance is the smallest over the key's prefixes.
- Prototype: `LevenshteinDictionaryWalker(query, maxEdits, nonFuzzyPrefix, readAhead, openEnd = true)`; its javadoc
  has the correctness argument.

### 2.4 Intra-leaf seek — proposed to build

An optimisation of the walk's seek (§2.2 step 3). It does not change what the walk returns, only how the cursor
reaches the next target. It is measured as worth building, but not scheduled, because it is a change to
`TransactionalBucketBPlusTree` [A §5.4, §5.4.1].

**Why.** The walk seeks after almost every rejected key: 180 times per walk with one edit, about 3,300 with two.
Each seek is a root-to-leaf descent that decodes and compares front-coded keys at every level, about 0.65 µs on
the production tree shape. On the real corpus the descents are 55 % of walk time. Yet 96 % of seek targets lie in
the leaf the cursor is already on, because the next acceptable string is usually a few keys further along. The upper
bound on the saving is 54 % of walk time, and 63–65 % for two-edit walks, the expensive band of the submitted search
[A §5.4.1].

**The algorithm.** A new method on the cursor, `BucketCursor#seek(K)`, with the contract "advance to the first
bucket ≥ `K`; `K` is greater than the current key" [A §5.4]:

```
seek(K):
    if K ≤ last key of the current leaf:
        i = binary search for K over the current leaf's keys [currentIndex + 1, leafPeek]
        currentIndex = i                     // no descent
    else:
        climb the saved path to the lowest ancestor whose key range covers K, descend from there
        (first version: a fresh descent from the root, i.e. today's cursor(K))
```

- The binary search is the one the cursor's constructor already uses (`findKeyPosition`). `ForwardBucketCursor`
  already holds the leaf's columns, `leafPeek`, `currentIndex` and the path with sibling arrays per level.
- The walk then calls `cursor.seek(target)` instead of `tree.cursor(target)`.

**Constraints** [A §5.4]:

- **No sequential read-ahead as a substitute.** Advancing with `next()` up to the target decodes every front-coded
  key on the way. Measured on the production tree it is 3.1× slower than descending, so the gain exists only with
  the binary search [A §5.4.1].
- **The cursor invariants must hold after a seek exactly as after `next()`.** These are `exhausted`, `positioned`,
  and the recovery for a leftmost descent ending inside a node being unlinked from its parent. This is where the
  effort lies, not in the binary search.
- `SingleLeafBucketCursor` and `ReverseBucketCursor` share the interface and must implement the method or reject it
  explicitly.

**Tests** [A §5.4]:

- tree-level, in `evita_functional_tests/.../index/bPlusTree/`: seek within a leaf, on a leaf boundary, across
  several leaves, to an absent key, past the end, and below the current position (must fail), each cross-checked
  against a fresh `cursor(K)`;
- the walker's equivalence test against the linear scan, unchanged.

**Verification.** Rerun `CorpusTypoMeasurementTest#shouldMeasureIntraLeafSeekReach` and compare the real saving
with the upper bound of [A §5.4.1].

---

## 3. Surface-form lane — decided

The lane is built (decided 2026-10-21), together with its display purpose (U9: suggestions show the accented word).
Its cost was accepted: +141.5 MB, +19.9 % over the stem dictionary with its postings on the measured corpus, and a
change of the persisted format [A §3.4, §9 item 4]. The details still open are listed in §10.

A second dictionary beside the stem dictionary. It maps every word **as written** (lowercased, folded) to the stem
the index chain made of it. It holds **no postings** — a hit is replaced by its stem and the stem's postings are
read [A §3.4, §10.7 h].

It exists for three reasons: typos the stemmer pushes out of reach on stems (3.0–5.8 % on real text), mid-ending
keystrokes no stem reaches (2.1 %), and showing the accented word in suggestions (U9) [A §3.4, §10.7 a, f].

### 3.1 Structure [A §3.4]

- **One more `TransactionalBucketBPlusTree` per (collection, locale, scope)** in `FulltextIndex`.
- **Key: `folded␀stem␀accented`**, one key per distinct (folded form, stem) pair:
  - `folded` — the surface form lowercased and folded with the same `ASCIIFoldingFilter` folding the search chain
    applies; this is the part walks and scans search;
  - `stem` — the stem the index chain produced; a folded form that stems two ways is two keys, **every stem is kept**;
  - `accented` — the most frequent accented form of the pair, appended **only when it differs** from `folded`.
- **No field prefix.** The analyzer is per (entity type, locale), so one entry serves all fields. (Must be revisited
  if per-attribute analyzers arrive.)
- **Value: a reference counter** — number of (entity, field) values containing the pair. The exact unit, per
  (entity, field) value or per entity, is an implementation detail. Any unit works if the key is removed exactly
  when the last occurrence of the pair leaves the index.
- **Transactional and persisted like the stem dictionary**: same versioned node layers, same per-entity savepoint
  rollback, a new storage part with its Kryo serializer (persisted-format change).

### 3.2 Maintenance [A §3.4]

- **I1, no change to the analyzer chain.** `FulltextIndex#analyze` starts using the `surfaceForm` supplier of
  `AnalyzedTermConsumer#accept` (ignored today), lowercases and folds it, and forms the pair `cerne → cern`. Stop
  words never reach the consumer and so never reach the lane.
- **`addValue`**: increment the counter of every pair in the value (create the key at 1).
- **`removeValue`**: re-analyse the old value (already done to find postings) and decrement; **remove the key at 0**.

---

## 4. Thresholds (Q2) — decided

A pure function evaluated per walk, before expansion. Lengths are in **code points of the folded string**
[A §6 reading 5, §7.6].

```
maxEdits(word, len, queryType):
    if word contains a digit        → 0                       // codes, SKUs, EANs
    edits = len < minLengthOneTypo  ? 0                       // default 4
          : len < minLengthTwoTypos ? 1                       // default 8
          : 2                                                 // automaton cap
    if queryType == AS_YOU_TYPE     → min(edits, maxTyposWhileTyping)   // default 1
    else                            → edits
frozenPrefix = min(nonFuzzyPrefix, len)                       // default 1
```

| Folded length | Submitted search | As-you-type |
|---|---|---|
| 1–3 | 0 | 0 |
| 4–7 | 1 | 1 |
| ≥ 8 | 2 | 1 |

**Which length is read** [A §7.1, §7.3, §9 item 3, §10.7 a]:

- **walks over the stem dictionary** — each stem variant is budgeted by **its own length** (not the typed word's);
- **walks over the surface-form lane** — the typed word is budgeted by the **typed (folded) length**.

**Every stem variant gets the full budget** for its length; no variant is treated as secondary [A §7.4, §10.7 b].

The digit rule tests the query word (`Character.isDigit` scan) [A §7.5].

Validation of the parameter record: `minLengthOneTypo ≥ 1`, `minLengthTwoTypos ≥ minLengthOneTypo`,
`maxEdits ≤ 2`, `nonFuzzyPrefix ≥ 0` [A §7.6].

---

## 5. Term expansion of a finished word (Q3) — decided

Input: one query word = typed folded form `t` + stem variants `v₁…vₙ` from the search analyzer (1–4 variants,
mean ~2–2.5 in Czech) [A §3.2 Q1, §10.6].

Legs, all run:

1. **Exact leg** — exact lookup of every `vᵢ` in the stem dictionary → distance 0, exactness `EXACT`.
   An exact surface-form hit of `t` in the lane (distance 0) also counts as `EXACT` [A §3.4 Q3].
2. **Typo leg over stems** — for every `vᵢ`: walk the stem dictionary with `maxEdits(vᵢ, len(vᵢ))`.
3. **Typo leg over the lane** — walk the lane with `t` and `maxEdits(t, len(t))`. The walk accepts
   `surface␀anything`: a key matches on its `folded` part only. Each hit yields its `stem`.

Cascade order is `ed0 → ed1 → ed2`; stop when the leg's cap is full [A §7.1].

**Merge per query word** [A §3.4 Q3, §10.7 h]:

- union of stems from all legs and both dictionaries, across all variants;
- a stem reached several ways keeps the **smallest distance**, and the **best exactness** *(inferred)*.

**Cap — 50 per leg of each query word** [A §7.1, §10.7 c]:

| Leg | Kept | Order |
|---|---|---|
| exact | **all**, uncapped | — |
| prefix (§6) | 50 | document frequency desc |
| typo | 50 | distance asc, then document frequency desc |

- The cap counts the **query word** (union over its variants and both dictionaries), not each variant; it is applied
  **after** the merge.
- Final tie-break: term ascending, so the cut is deterministic (same query, same data, same result on every replica)
  [A §8.2, §8.3 step 2] *(inferred: the analysis gives distance → term and, later, distance → document frequency,
  never the three-level chain)*.
- Never alphabetical order as the primary cut — it loses the target 12–44× as often [A §10.7 c].
- Nothing flags a result whose expansion was cut [A §7.1].
- Document frequency is the postings cardinality, readable from the bucket without touching postings
  (`BucketCursor.size()`) [A §8.3 step 1].

Output per query word: `(stem, distance ∈ {0,1,2}, exactness ∈ {EXACT, PREFIX, FUZZY})`, plus, for Q7/Q8, which
variant or surface form produced each stem [A §8.3 step 5].

---

## 6. The last query word — decided

### 6.1 When the last word is "unfinished" [A §7.8 rules 1–2, 5]

- Only the **last** query word may get a prefix leg. Every earlier word is finished (§5 only).
- A **trailing separator** (whitespace or punctuation at the end of the raw query) closes the last word → no
  prefix leg.
- **Minimum fragment length 4**: a shorter last word gets no prefix leg and no open-ended walk, only the legs of §5.
  This replaces the earlier default of 3 (2026-10-14). Without precomputed prefix postings, which the README rejects,
  a three-letter prefix reaches thousands of terms and cannot meet the 5 ms keystroke budget
  [A §9 item 1, §10.7 d, §12].
- The prefix switch must be **on** (default; §9).

### 6.2 Extra legs of an unfinished last word [A §7.8 rules 6–7, §3.4 Q3, §10.1]

On top of §5:

4. **Prefix leg over stems** — `prefix(fragment) ∪ prefix(v₁) ∪ … ∪ prefix(vₙ)` over the stem dictionary.
   Exact lookup is the special case of a prefix equal to the key.
5. **Prefix leg over the lane** — prefix scan of the folded fragment over the lane's `folded` part; hits yield stems.
6. **Open-ended typo walk** (§2.3) instead of the finite one, over **both** the stem dictionary (each variant with
   its own length) and the lane (the typed fragment with its typed length). This is the last word only; every
   earlier word keeps the finite walks of §5. The thresholds are those of §4 for the query type: at most one edit
   while typing, and in a submitted search **two edits from 8 letters**, as for any other word. The walk starts at
   **5 letters while typing** and at **4 letters in a submitted search**. Below its start, the last word gets the
   finite typo walk of §5 together with the prefix legs 4 and 5. Starting later while typing keeps four-letter
   keystrokes affordable; four-letter typo recall then returns on the fifth letter [A §10.7 d, §11.4]. The submitted
   search runs once and fits a four-letter start in its budget, and tolerating more than the preview keeps the
   §7.3 invariant.

The prefix leg runs **unconditionally** whenever §6.1 allows it. It is not a fallback that runs only when exact and
typo matching find too little [A §11.4, §11.5].

Exactness of prefix hits is `PREFIX`; of typo hits `FUZZY`.

### 6.3 The fragment comes from the raw input — decided [A §7.8 rule 6, §10.1]

The search chain **drops stop words** (`za`, `po`, `vy`, `do`, `na`, `pro`, `od`, …), and 2.6 % of fragments on the
way to longer words are stop words (most of them shorter than the minimum fragment length of §6.1, but not
all). The last word's fragment must reach the prefix leg **from the raw input, around
the stop filter**, not from the analyzer output.

### 6.4 Not part of the algorithm

- **Length guard** on `prefix(vᵢ)` (skip variants more than one letter shorter than the fragment): **not the
  default**; a possible option once the lane exists [A §9 item 7].
- **Prefix on every word**: not pursued [A §9 item 8].
- Union of a prefix scan and a finite typo walk (P3's option 2) instead of the open-ended walk: rejected
  [A §10.2].
- Two edits only as a fallback when one edit finds too little: not pursued. The length threshold of §4 alone
  decides when two edits apply [A §9 item 5].
- A prefix leg that runs only when exact and typo matching find too little: not pursued (§6.2).

---

## 7. Rules per query type — decided

### 7.1 Telling the types apart [A §11.1]

- A query with **`suggest(n)`** is **as-you-type**.
- Any other query is a **submitted search**, unless the caller marks it as typed live (a per-query parameter of the
  fulltext constraint — e.g. an instant-results page).

### 7.2 The rules [A §11.5]

| Rule | Submitted search | As-you-type |
|---|---|---|
| prefix leg on the last word | on by default (configurable) | on |
| minimum fragment length | 4 | 4 |
| prefix input | raw fragment + stem variants + lane | same |
| typos per word | thresholds of §4, **up to 2** (from 8 letters) | **at most 1 on every word**, finished or not |
| open-ended walk on the last word | **yes, from 4 letters** | yes, **from 5 letters** |
| cap | 50 per leg (§5) | same; **mandatory** |

### 7.3 Consistency invariant — pattern 1 [A §11.6]

**The results page is always a superset of the as-you-type preview for the same text.** Implementations must keep:

- the prefix switch is **one switch for both types**;
- the submitted search's typo budget is **never smaller** than the as-you-type one;
- the submitted search runs its **last word exactly as the as-you-type query did** (prefix leg, variant prefixes,
  open-ended walk), plus the wider thresholds.

A caller overriding the switch per query may break the invariant for its own queries; that is its responsibility.

---

## 8. Matching and ranking (Q4, Q5)

### 8.1 Distance bands per query word — proposed [A §8.3 step 3]

Built with RoaringBitmap operations, no per-document loop. All variants and both dictionaries feed the same bands:

```
B0 = OR postings(stems at distance 0)
B1 = OR postings(stems at distance 1)  ANDNOT B0
B2 = OR postings(stems at distance 2)  ANDNOT (B0 OR B1)
word matches  = B0 OR B1 OR B2                → AND across query words (must-match), then ∧ filter
word distance = 0 / 1 / 2 by band membership
```

The exactness bands are built the same way, with a prefix band between exact and fuzzy: `EXACT`, then
`PREFIX ANDNOT EXACT`, then `FUZZY ANDNOT (EXACT OR PREFIX)` *(the exact formulas are inferred)*.

### 8.2 Lanes — decided shape, weights open [A §2.2 U7, §3.2 Q5, §8.2, §8.3 step 4]

- **`TYPO = 255 − Σ over query words of w(distance)`**, an 8-bit lane; fewer typos rank higher.
- **`EXACTNESS`**: exact > prefix > fuzzy(1) > fuzzy(2).
- Lane order in the composite (matched words → typos → impact → exactness → context) is owned by the rank
  profile.
- No floats, no BM25, no document-frequency blending, no per-expansion sum [A §8.2].
- A first-letter penalty is unnecessary: the frozen prefix makes first-letter typos unreachable.

### 8.3 Downstream consumers [A §3.2 Q6–Q8, §8.3 step 5]

- **Suggester (Q6)** — the same expansion; candidate terms are shown in the **accented surface form** from the lane
  (`černé`), never the stem `cern` or the folded `cerne` [A §2.2 U9, §3.4 Display]. Candidate terms are ordered by
  exactness class, then best field weight, then postings cardinality (P3 §4.4) [A §8.2]. Never offer a completion
  that yields no results under the must-match filter [A §2.2 O4].
- **Highlighting (Q7)** — re-analyses stored values; needs the per-word list of stems with their distances.
- **Explain (Q8)** — per word: variant or surface form, matched stem, distance, exactness class.

---

## 9. Configuration — plumbing not built

**Decided:** nothing goes into the schema; every parameter can change without a reindex [A §2.2 O1, §7.7]; a higher
layer replaces an item of a lower one, nothing is merged. **Proposed:** where each group lives; the DSL shape is
not decided.

**Typo parameters** — one immutable record, proposed to travel with the rank profile [A §7, §7.7]:

| Parameter | Default |
|---|---|
| `minLengthOneTypo` | 4 |
| `minLengthTwoTypos` | 8 |
| `nonFuzzyPrefix` | 1 |
| transpositions | on |
| cap per leg | 50 |
| digits exact | on |

Layers: built-in default → rank / query profile → per-query override. **A higher layer replaces an item; nothing is
merged.**

**Opt-outs beyond the digit rule** — switching typos off per attribute (`code`, `ean`) or for a list of words
(brands) — are a question of the developer-facing API, not of the algorithm. The algorithm's only hook is that an
opted-out word, or the terms of an opted-out attribute, get 0 edits in §4. The candidate shapes are a per-attribute
setting beside the field weights in the rank profile (never in the schema), and a word list as a hot-swap artefact
[A §7.5, §9 item 6].

**Prefix switch and unfinished-word rules** — input mode, not ranking [A §7.8 rule 3, 5, 8]:

| Parameter | Default | Layers |
|---|---|---|
| prefix leg on the last word | on | code → `EvitaConfiguration` (new fulltext section, YAML, documented in `configure.md`) → fulltext constraint parameter |
| minimum fragment length | 4 | same |
| max typos while typing (`maxTyposWhileTyping`) | 1 | same |
| start length of the open-ended walk, as-you-type | 5 | same; a parameter separate from `minLengthOneTypo` [A §11.4] |
| start length of the open-ended walk, submitted search | 4 | same |

The per-query override works **in both directions**: a classic results page can switch prefix off, an
instant-results box can switch it on when the operator default is off. If query profiles (issue #12) are built, a
profile may sit as a layer between the database configuration and the query. The suggester always uses the prefix
leg regardless of the switch.

---

## 10. Open — not part of the algorithm yet

| Item | State | Analysis |
|---|---|---|
| Typo-lane weights `w` | **needs real-world queries** — judged results or click data, which no corpus measurement can replace. Until then `(0, 1, 2)`, raw typo counts as Meilisearch and Typesense use, is the simplest default | [A §7.1, §8.2, §9 item 6] |

---

## 11. Tests the implementation must carry

- **Walk equivalence** — guided walk (finite and open-ended) returns exactly the linear scan's set, with the distance
  the reference DP computes [A §5.1, §10.2].
- **Automaton properties** — deterministic, no dead states; accepts one substitution / insertion / deletion /
  transposition at distance 1; frozen prefix holds; `toAutomaton(3) == null` [A §5.1].
- **Cap determinism** — equal-distance hits cut at the cap boundary in a stable order [A §8.3 step 6].
- **Band merge** — cross-checked against a per-document loop [A §8.3 step 6].
- **Behaviour examples** — the fourteen examples of `TypoToleranceBehaviourTest` (missing/wrong diacritics, one
  substitution, transposition, no typo on 3 letters, no first-letter typo, digits exact, exact above typo,
  must-match, prefix while typing, exact above prefix, unfinished word with a typo) [A §10.4].
- **Lane maintenance** — counter increments, decrements, key removal at zero, rollback with the entity savepoint.
- **Stop-word fragment** — a stop word of four or more letters typed as the last word (e.g. `jako…` on the way to
  `jakost`) still runs the prefix leg (§6.3).
