# Research Brief: Trigram Indexing for Fast `contains()` Using Distinct Value IDs

**Status:** Research brief, adopted into the fulltext record on 2026-08-24 and **implemented in full on
2026-08-30** — read the 2026-08-30 note at the end of this header first. It originated as a separate
discussion outside the #258 research line — about accelerating today's naive `attributeContains` /
`attributeEndsWith` scan — and is adopted here because it converges with the fulltext prototypes on
substrate, memory risks and open questions; the mapping is in §32–§33. The descriptions of PostgreSQL,
SQLite FTS5 and Roaring Bitmap behavior were verified against primary sources on 2026-08-24 (the pg_trgm
documentation and `trgm_gin.c`, sqlite.org/fts5.html, the RoaringFormatSpec) and all hold. The proposed
evitaDB architecture, memory estimates and optimizations are design hypotheses that need to be
benchmarked against real evitaDB datasets — and §32 corrects the three assumptions that do not match the
current code.

**Update 2026-08-25:** the spike measurements were executed on real corpora and the design passed its
performance gate. §35 records the measured results, closes the forks this brief left open (§13/§14
dictionary shape, §15 positions, §16/§29 posting representation, §17 early exit) and corrects the
brief's claims that the measurements falsified. Read §35 before acting on §5–§17 — several of their
hypotheses are now settled, some against the brief's expectation.

**Update 2026-08-30 — IMPLEMENTED. This document is now history, not intent.** The design shipped in
full as issue [#1454](https://github.com/FgForrest/evitaDB/issues/1454) (a sub-issue of #258): the
`SUBSTRING` filter capability, the value-id column on the shared value tree, `TrigramIndex` as a
component of `GlobalEntityIndex`, `attributeContains` / `attributeEndsWith` served from it, and
reduced-index plans composed through the subset invariant of §34. The outcome — the decisions, the
options that lost and why, the measured numbers and the open follow-ups — lives in the record's
[README](../README.md); it is the authority from here on, and **nothing below has been rewritten**. The
body still reads as it did before implementation, on purpose: it is what was believed at the time, and
a reader has to be able to tell that apart from what turned out to be true.

**Four things the implementation settled against this brief, §35 included:**

1. **§11 / §35.2 — the posting store is a `TransactionalLongBPlusTree`, not the open-addressing table.**
   §35.2's 40–60× probe advantage was real and was not the deciding quantity: a published flat table is
   immutable, so one touched posting clones both spine arrays on every commit — the large short-lived
   allocation this codebase moved its index structures onto B+ trees to escape; and a pattern issues
   only 2–15 lookups, so the whole probe penalty is under a microsecond against a query whose
   verification phase runs for tens to hundreds of them.
   §35.5's own "the production key structure must be a resizable/persistable tree" already pointed here
   — the two halves of §35 disagreed with each other.
2. **§27's cold-cache rows and §35.5's persisted-postings item are closed as *never*, not deferred.**
   The trigram index has a storage surface of exactly zero and is re-derived on catalog open. The
   rebuild cost that would have justified persisting it was under-measured: the ~220 ns/insert constant
   behind the projection came from synthetic corpora whose mean posting holds 25–244 members, applied to
   an attribute whose largest posting holds 444 437 — the real incremental figure is ~77 s on
   `article/title/cs`, 3.6× the projection. An ordered-append bulk build does the same work in 4.0 s,
   after which persistence buys a second of load and costs whole-posting rewrites forever (measured: one
   new value rewrites ~4.8 MB, 4 285× what a delta journal would append).
3. **§35.4's crossovers were spike-harness numbers, and the gate constant derived from them was too
   permissive by a factor of three.** Measured end to end through the real engine, the accelerated path
   is *slower* than the scan it replaces above ~9.5 % posting width at n = 100 000; the shipped
   `REQUIRED_NARROWING_FACTOR = 4` admitted everything up to 25 %, where it ran 2.1–2.3× slower. It
   now stands at 12. §35.4's structural reading — that the crossover is a *ratio* and that verification
   dominates — held; only its calibration did not.
4. **§21 / §32 / §35.5's missing bucket-death hook now exists**, as `ValueLifecycleSink` — a sink
   threaded through the write call rather than a listener the index stores, because every commit
   re-shells the structures a stored listener would have to be re-bound to.

**What held, and was not re-litigated:** §15 (positions never), §13/§14 (tree-attached dictionary),
§16/§29 (hybrid postings, T = 128 — with demotion at T/2 added for hysteresis), §17 (cardinality-
ascending intersection mandatory, the verification-cost bound off), §6/§21/§28 (churn on an existing
value costs zero trigram writes), §5/§8 (the per-attribute flag *is* the memory story, and the budget
numbers are unchanged), §18 (short patterns fall back), §32 (`startsWith` keeps its anchored path), and
§34.4's subset invariant, which is exactly what the reduced-index composition was built on.

**Still open, and named in the record rather than here:** `endsWith` shares the whole candidate path and
its correctness is proven against the engine, but it still has no latency column of its own; localized
attributes, non-ASCII values and reduced-index plans have functional coverage but no benchmark; and #545
(§19) was deliberately kept out of this line of work.

## 1. Goal

Investigate a specialized optional index for accelerating substring predicates such as:

```text
contains(attribute, "iphone")
LIKE '%iphone%'
```

while preserving the existing execution path as a fallback:

```text
Trigram index exists
    → use trigram candidate generation
    → exact verification

Trigram index does not exist
    → scan distinct values in the existing B+Tree
    → exact verification
```

The most important design requirement is to avoid random entity I/O during candidate verification.

The proposed key idea is therefore:

> Do not index `trigram → entity PK`. Index `trigram → internal distinct value ID`.

This may simultaneously reduce the trigram index size, reduce verification work, significantly reduce index-update work for repeated values, and eliminate the need to fetch complete entities during exact verification.

---

# 2. External evidence

## PostgreSQL `pg_trgm`

PostgreSQL accelerates `LIKE`, `ILIKE`, regex and similarity predicates using trigram indexes backed by GIN or GiST.

A GIN index conceptually stores:

```text
key → posting list of row IDs
```

and `pg_trgm` extracts trigrams from the query and searches their posting lists. PostgreSQL documentation explicitly describes this mechanism for queries such as:

```sql
SELECT *
FROM test_trgm
WHERE t LIKE '%foo%bar';
```

The query does not need to be left-anchored.

The important limitation is that the trigram match is not exact. PostgreSQL's `pg_trgm` GIN implementation explicitly sets:

```text
recheck = true
```

and comments that all cases handled by the consistency function are inexact. Candidate heap tuples therefore have to be evaluated against the original predicate.

Conceptually:

```text
"abcd"

abc → {PK1, PK2, PK3}
bcd → {PK1, PK2, PK3}

intersection
    ↓
PK1, PK2, PK3
    ↓
fetch original values
    ↓
PK1: "xxxabcdyyy"  → true
PK2: "abc---bcd"   → false
PK3: "bcd---abc"   → false
```

This candidate recheck is exactly the operation we want to avoid performing against entity storage in evitaDB.

## Precedent check (added 2026-08-24)

No mainstream system was found that indexes `trigram → distinct-value-id`. The closest relatives are
ClickHouse's `ngrambf_v1` (n-gram → per-granule bloom filter — coarser: it skips blocks, then scans) and
dictionary predicate pushdown in dictionary-encoded column stores such as Parquet (the predicate is
evaluated once per dictionary entry, then expanded to rows). The proposal combines two established
techniques — a trigram inverted index and dictionary-level predicate evaluation — in a way that appears
novel in combination. The consequence: no external system's benchmarks can be borrowed, so the
measurements of §25–§27 are the only evidence there will be.

---

# 3. SQLite FTS5 provides useful evidence about positions

SQLite FTS5 supports a dedicated trigram tokenizer:

```sql
CREATE VIRTUAL TABLE tri USING fts5(
    text,
    tokenize='trigram'
);
```

and can use it to accelerate:

```sql
WHERE text LIKE '%something%'
```

and `GLOB` queries.

FTS5 supports three index-detail levels:

```text
detail=full
    rowId + column + token position

detail=column
    rowId + column

detail=none
    rowId only
```

Crucially, trigram-based `LIKE` and `GLOB` continue to work with `detail=none`. Positions are therefore not required for substring candidate generation.

SQLite publishes one useful size comparison from an email corpus:

```text
detail=full     743 MiB
detail=column   340 MiB
detail=none     134 MiB
```

This is **not a pure trigram benchmark**, so the exact ratio must not be generalized. However, it provides strong empirical evidence that positional information can dominate the storage cost of an inverted index.

Two caveats from the same documentation belong here: with `detail=none` or `detail=column` the trigram
`LIKE`/`GLOB` optimization still works but "may be slightly slower", and setting `remove_diacritics` on
the trigram tokenizer **forfeits indexed `LIKE`/`GLOB` entirely** — a warning that normalization choices
can silently disable the very optimization the index exists for (see §19).

### Initial conclusion

For an evitaDB index whose only purpose is accelerating literal `contains()`, positions should probably **not** be stored initially.

Start with:

```text
trigram → valueId set
```

and perform exact verification against the distinct value.

Only add positional information later if benchmarks demonstrate that exact verification remains a significant bottleneck.

---

# 4. Proposed evitaDB architecture

Assume the existing index already conceptually stores distinct attribute values:

```text
"Apple iPhone 17"
    → PK bitmap {10, 17, 83, ...}

"iPhone case"
    → PK bitmap {4, 91, ...}

"Samsung Galaxy"
    → PK bitmap {7, 22, ...}
```

Assign a stable internal ID to each distinct indexed value:

```text
valueId=14 → "Apple iPhone 17"
valueId=27 → "iPhone case"
valueId=81 → "Samsung Galaxy"
```

The existing value index becomes conceptually:

```text
valueId
   │
   ├── normalized/original value
   └── RoaringBitmap<entityPK>
```

The trigram index becomes:

```text
trigram → RoaringBitmap<valueId>
```

Example:

```text
"iphone"

iph → {14, 27, ...}
pho → {14, 27, ...}
hon → {14, 27, ...}
one → {14, 27, ...}
```

A query executes as:

```text
contains(name, "iphone")

        ↓ normalize

iph, pho, hon, one

        ↓ retrieve posting cardinalities

        ↓ intersect from most selective

candidate valueIds

        ↓ exact string verification ONCE PER DISTINCT VALUE

matching valueIds

        ↓ retrieve existing PK bitmap for each value

OR all PK bitmaps

        ↓

final entity PK bitmap
```

No entity needs to be loaded for substring verification.

---

# 5. Why `valueId` is potentially much better than entity PK

Define:

```text
N = number of indexed value occurrences / entities
V = number of distinct normalized values
R = N / V = average value reuse ratio

L = average normalized value length in Unicode code points
U = average number of UNIQUE trigrams per value
```

For a simple string:

```text
U <= L - 2
```

because repeated trigrams within the same value only need one membership entry.

With a conventional entity-based trigram index:

```text
number of trigram incidences ≈ N × U
```

With a distinct-value trigram index:

```text
number of trigram incidences ≈ V × U
```

Therefore the potential reduction is approximately:

```text
(N × U) / (V × U)
    =
N / V
    =
R
```

So if ten entities share an average distinct value:

```text
R = 10
```

the trigram posting population can theoretically be roughly 10× smaller.

This benefit naturally varies by attribute.

For example:

```text
brand
category
manufacturer
material
color
```

may have very high reuse.

Product names may have considerably lower reuse.

Descriptions may be nearly unique:

```text
V ≈ N
```

In that case `valueId` provides little posting-size reduction, although it can still prevent random entity reads during verification.

**The `N / V` ratio should therefore be one of the first metrics collected for real evitaDB attributes.**

---

# 6. An additional major benefit: update amplification

The `valueId` design has another property that may be even more important than memory consumption.

Suppose:

```text
valueId 14
"Apple iPhone"
→ {PK1, PK5, PK20}
```

and another entity starts using the same value:

```text
→ {PK1, PK5, PK20, PK77}
```

The trigram index does not change at all:

```text
app → {14, ...}
ppl → {14, ...}
iph → {14, ...}
...
```

Only the existing value-to-PK bitmap changes.

Therefore trigram postings need modification only when a **distinct value enters or leaves the value dictionary**, not whenever an entity using an existing value changes.

Conceptually:

```text
entity gets an already existing value
    → update PK bitmap only
    → zero trigram writes

first occurrence of a new distinct value
    → allocate valueId
    → add valueId to its trigram postings

last occurrence of a distinct value disappears
    → remove/tombstone valueId
    → eventually remove it from trigram postings
```

For highly reused attribute values, this can make write amplification dramatically lower than a conventional:

```text
trigram → entityPK
```

index.

This is a design inference and should be validated experimentally.

---

# 7. Roaring Bitmap memory model

Roaring Bitmap is a particularly interesting representation for `valueId` postings.

A 32-bit Roaring bitmap partitions IDs by their high 16 bits. Within each `2^16 = 65,536` value range it uses different container representations.

The serialized Roaring specification uses:

- an **array container** up to cardinality 4096,
- a **bitmap container** above cardinality 4096,
- optionally a **run container** for suitable ranges.

An array container stores each low 16-bit value explicitly, effectively requiring 2 bytes per entry.

A bitmap container represents all 65,536 possible values using 65,536 bits:

```text
65,536 / 8 = 8,192 bytes
```

per dense container.

Thus rare trigrams are represented approximately as:

```text
2 bytes × number of valueIds
```

plus container/index metadata.

Frequent trigrams eventually switch to bitmaps, where adding more members does not increase the container beyond 8 KiB.

This is a good match for trigram frequency distributions:

```text
rare trigram
    → compact sorted arrays

common trigram
    → dense bitmap
```

---

# 8. First-order memory estimate

The following is **napkin math, not a complete memory estimate**.

Let:

```text
E = V × U
```

be the number of `(trigram, valueId)` memberships.

If all memberships happened to be represented in sparse Roaring array containers, the raw membership payload would be approximately:

```text
2 × E bytes
```

This excludes:

- Roaring container metadata,
- bitmap-level metadata,
- trigram key lookup structures,
- Java object overhead,
- unused allocation capacity,
- persistence metadata,
- valueId-to-value mapping.

Dense containers may use **less** than this estimate, because an 8 KiB bitmap can represent far more than 4096 entries.

Assuming for simplicity `U ≈ L - 2`:

| Distinct values `V` | Avg. length `L` | Approx. memberships | Sparse payload at 2 B/member |
|---:|---:|---:|---:|
| 100k | 20 | 1.8 M | ~3.4 MiB |
| 100k | 40 | 3.8 M | ~7.2 MiB |
| 1 M | 20 | 18 M | ~34 MiB |
| 1 M | 40 | 38 M | ~72 MiB |
| 1 M | 80 | 78 M | ~149 MiB |
| 10 M | 40 | 380 M | ~725 MiB |

These numbers are useful only as a first-order estimate.

They show that the design appears plausible at approximately one million distinct values, but a ten-million-value high-cardinality attribute deserves serious measurement.

---

# 9. Comparison against direct entity-PK indexing

Consider:

```text
10 M entities
1 M distinct values
average length = 40
average U ≈ 38
```

Direct entity indexing:

```text
10M × 38
= 380M trigram/PK memberships
```

Sparse-payload approximation:

```text
~725 MiB
```

Distinct-value indexing:

```text
1M × 38
= 38M trigram/valueId memberships
```

Sparse-payload approximation:

```text
~72 MiB
```

This is approximately the expected 10× difference resulting from:

```text
N / V = 10
```

Again, neither number represents complete Java heap or persisted-index size.

---

# 10. Do not underestimate Java object overhead

The previous estimates describe mostly the bitmap **payload**, not Java heap usage.

A naïve implementation such as:

```java
Map<Long, RoaringBitmap>
```

may create:

- one map entry per trigram,
- one `RoaringBitmap` object per trigram,
- arrays of container references,
- individual container objects,
- backing arrays.

For a small normalized alphabet this may be harmless.

For a messy Unicode dataset containing hundreds of thousands or millions of observed trigram keys, object overhead may become material.

The implementation experiment should therefore measure at least two values independently:

```text
serialized index size
Java live-heap size
```

They may be very different.

Potential mitigations include:

```text
serialized/off-heap Roaring bitmaps

paged immutable representations

primitive long-key index instead of boxed Map<Long,...>

hybrid small-posting-list / Roaring representation
```

---

# 11. A trigram key itself does not require an FST

A Unicode code point requires at most 21 bits.

Therefore three Unicode code points fit in:

```text
21 × 3 = 63 bits
```

A trigram can therefore be encoded directly in a Java `long`:

```text
cp1: bits  0..20
cp2: bits 21..41
cp3: bits 42..62
```

Conceptually:

```java
long trigram =
      ((long) cp1)
    | ((long) cp2 << 21)
    | ((long) cp3 << 42);
```

This needs validation against the exact normalization semantics, but it means that a separate string dictionary or FST is unnecessary for fixed trigrams.

The index may simply be:

```text
long trigramKey
    →
posting representation
```

This also avoids UTF-16 surrogate-pair mistakes. Trigrams should be generated over **Unicode code points**, not Java `char` values, unless the predicate is intentionally defined in UTF-16 code units.

---

# 12. Exact verification is still required

Without positions:

```text
abc → valueId 42
bcd → valueId 42
```

does not prove that value 42 contains `"abcd"`.

It may contain:

```text
abc..........bcd
```

or the trigrams in the opposite order.

Therefore:

```text
trigram intersection
```

is only a candidate generator.

The exact `contains()` predicate must still run against each candidate distinct value.

The difference from PostgreSQL is where that verification happens:

```text
PostgreSQL

candidate row TID
    ↓
fetch tuple
    ↓
read value
    ↓
LIKE recheck
```

versus the proposed evitaDB design:

```text
candidate valueId
    ↓
read distinct value
    ↓
contains() recheck ONCE
    ↓
reuse existing entity-PK bitmap
```

---

# 13. `valueId` alone does not automatically solve random I/O

This is an important caveat.

If:

```text
valueId → original string
```

requires a random B+Tree page read for every candidate value, the design may merely move the random-I/O problem from entities to distinct values.

The value dictionary therefore needs an I/O-friendly lookup path.

Several options should be investigated.

## Option A — values already resident in memory

If the existing read representation of the value index already keeps distinct values cheaply accessible in memory, simply attach the stable ID to it.

This is ideal.

## Option B — compact ordinal-addressable value store

Maintain:

```text
valueId → offset/length
```

into packed value blocks:

```text
value block
┌───────────────────────────────┐
│ value 1000 │ value 1001 │ ... │
└───────────────────────────────┘
```

Candidate value IDs are naturally sorted after bitmap intersection and can be verified in ID order.

This allows page/chunk reads to be coalesced instead of performing independent random B+Tree lookups.

## Option C — memory-mapped / off-heap value dictionary

Keep:

```text
valueId → offset
```

small and memory-resident while normalized strings live in paged or memory-mapped storage.

Only candidate pages need to be faulted in.

## Option D — duplicate normalized values in the trigram subsystem

This increases storage but may still be worthwhile if it completely removes expensive random access to another index.

For example, with:

```text
1M distinct values
40 bytes average normalized value
```

the raw string payload is approximately:

```text
40 MB
```

plus offsets and block metadata.

Whether this duplication is acceptable should be measured rather than assumed.

---

# 14. Approximate valueId dictionary overhead

A 32-bit value ID requires:

```text
4 bytes × V
```

raw.

For one million distinct values:

```text
~4 MB
```

If an additional 64-bit offset is required:

```text
8 bytes × V
≈ 8 MB
```

So a rough packed representation could require:

```text
4 MB IDs
+ 8 MB offsets
+ normalized string bytes
```

per million distinct values.

These numbers exclude object and storage metadata.

A representation that stores the ID directly alongside the already-existing distinct value may avoid some or all of this additional structure.

---

# 15. Positions are probably the wrong first optimization

An alternative would be:

```text
trigram
   →
(valueId, positions)
```

Then `"abcd"` could potentially be confirmed from:

```text
abc @ position 12
bcd @ position 13
```

without loading the string.

This is technically attractive, but positional posting lists can be expensive.

SQLite FTS5 is useful evidence here: its published email-corpus experiment reduced index size from 743 MiB with full positional details to 134 MiB with row IDs only. The exact ratio is workload-specific, but the direction is clear.

The proposed implementation order should therefore be:

```text
Phase 1
trigram → valueId
+ compact value dictionary
+ exact value recheck

Phase 2, only if justified by measurements
consider positions
```

The `valueId` indirection may make rechecking cheap enough that positions never become worthwhile.

---

# 16. PostgreSQL suggests another useful representation idea

PostgreSQL GIN does not use one representation for all posting-list sizes.

Its implementation stores a small posting list directly with the index key; when the posting set becomes too large, it stores a pointer to a separate posting tree. PostgreSQL also has a pending-entry mechanism (`fastupdate`) that batches inverted-index updates.

This suggests benchmarking an evitaDB representation such as:

```text
rare trigram
    → compact sorted int[] / delta-coded list

frequent trigram
    → RoaringBitmap
```

Roaring already performs a similar transformation **inside each 65k container**, using array containers for sparse regions and bitmap containers for dense regions.

However, an entire `RoaringBitmap` still has top-level/container overhead.

For extremely rare trigrams, for example:

```text
xyz → {12873}
```

a standalone primitive posting list may still be cheaper than constructing a complete Java bitmap object.

This is an implementation detail worth benchmarking rather than deciding upfront.

---

# 17. Query-time optimization

There is no requirement to intersect query trigrams in textual order.

Suppose:

```text
iph → cardinality 800,000
pho → cardinality 400,000
hon → cardinality 30,000
one → cardinality 700,000
```

Start with:

```text
hon
∩ pho
∩ one
∩ iph
```

instead of:

```text
iph
∩ pho
∩ hon
∩ one
```

The trigram index should therefore expose posting cardinality cheaply.

Even more aggressively, it may not always be necessary to intersect every trigram.

For example:

```text
hon
∩ pho

→ 45 candidate values
```

If exact verification of 45 small strings is cheaper than intersecting two additional large bitmaps, execution can stop candidate generation early and proceed to exact verification.

This suggests a cost model balancing:

```text
cost(next bitmap intersection)
versus
cost(exactly verify current candidate values)
```

A simple first implementation can intersect all trigrams in ascending cardinality order and optimize this later.

---

# 18. Short patterns

A trigram index cannot help directly with:

```text
contains("a")
contains("ab")
```

because no complete trigram exists.

Recommended behavior:

```text
length >= 3
    → trigram index

length < 3
    → existing B+Tree distinct-value scan
```

A separate bigram index should only be considered if real usage demonstrates an important need for two-character searches.

Bigram postings are considerably less selective.

---

# 19. Normalization semantics are critical

The trigram generator and the exact predicate must have compatible semantics.

Possible dimensions include:

- Unicode normalization,
- case folding,
- locale,
- diacritics,
- punctuation,
- whitespace,
- multi-code-point case mappings.

The index may generate false positives because exact verification removes them.

It **must not generate false negatives**.

Therefore every exact match must necessarily produce all query trigrams used to filter candidates.

This is particularly important if the indexed representation strips or changes characters.

For literal `contains()` semantics, blindly cloning PostgreSQL's trigram extraction is probably wrong. PostgreSQL `pg_trgm`, for example, ignores non-word characters and adds padding around words as part of its trigram model.

evitaDB should define trigram extraction according to its own `contains()` normalization/collation contract.

That contract is concrete today (verified in code, see §32): stored keys and search terms are both
normalized to Unicode NFD by the same normalizer (`FilterIndex.getNormalizer`), matching is
case-sensitive and accent-sensitive, and semantics are per code point; collation affects ordering only,
never matching. Two consequences:

- **NFD splits `é` into base + combining mark**, so naive code-point trigrams straddle grapheme
  boundaries. Extraction must be defined over NFD grapheme clusters, or accept the resulting trigram
  inflation; and it must not case-fold on its own, because a folding the exact predicate does not perform
  produces false negatives that verification cannot recover.
- **Issue [#545](https://github.com/FgForrest/evitaDB/issues/545) (case-insensitive String attributes) is
  this same seam.** The requested per-attribute case-insensitivity is a change of exactly this
  normalization contract — fold at indexing time, fold the query term, and both the exact predicate and
  the trigram extraction follow automatically, because both already run behind the same normalizer. If
  #545 lands first, the trigram index inherits it for free; if the trigram index lands first, #545
  remains a normalizer change plus a schema flag, not an index rewrite. Case folding must be locale-aware
  (the Turkish dotless-i class of problems); the value trees are already partitioned per (attribute,
  locale), so the fold has a locale to consult.

  Because #545's semantics are schema-static, the fold happens at the canonical form: values enter the
  shared tree already folded, so distinct values differing only by case **merge into one bucket → one
  valueId → one set of trigram postings**. `V` shrinks, and with it the valueId space, the memberships
  `E`, and the trigram key count `K` — case-insensitivity is a small memory *saving*, not a second
  dictionary. No `value → valueId` structure is duplicated anywhere.

  A dual-semantics variant (the same attribute answering both case-sensitive and case-insensitive
  queries) is **not** what #545 asks and should not be offered lightly — but if it ever is, the trigram
  layer gives it to `contains()` almost free: always extract trigrams from the *folded* text (folding is
  many-to-one, so folded query trigrams are guaranteed present for a case-sensitive match too — no false
  negatives, per this section's rule), and let the per-query verification predicate decide sensitivity.
  The expensive half of duality is `equals`/sort: either a collator-strength comparator over an unfolded
  tree (no duplication, pricier comparisons — and collation is the known top write-CPU item) or a second
  folded tree (the duplication this design otherwise avoids). Fulltext itself is unaffected: the P5
  analyzers lowercase during tokenization, so the fulltext branch is inherently case-insensitive and
  #545 concerns only the exact-match attribute path. One semantic consequence to state out loud: under a
  folded canonical form, a **unique** case-insensitive attribute makes "Apple" and "apple" collide —
  which is the point, but it must be documented as intended.

---

# 20. MVCC and valueId lifecycle

Stable value IDs introduce a lifecycle problem.

Suppose:

```text
valueId=123 → "iphone"
```

disappears in the current transaction while an older snapshot can still see it.

`123` must not immediately be reused for another value if old snapshots may still access the old trigram index generation.

Potential strategies include:

### Monotonic IDs

Never immediately reuse value IDs.

Deleted IDs become holes:

```text
100
101
<deleted>
103
...
```

Roaring handles sparse integer spaces reasonably well.

Periodic major compaction can reclaim them.

### Generation-scoped IDs

A compaction can rebuild:

```text
value dictionary
+
trigram bitmaps
```

and assign dense IDs for the new generation.

Old snapshots continue referencing the old generation.

This may fit naturally with COW/snapshot-oriented persistence.

### Delayed reuse

Reuse an ID only after no snapshot can reference the previous owner.

The implementation agent should explicitly model this before choosing the ID allocator.

---

# 21. Update algorithm

For an entity changing:

```text
oldValue → newValue
```

the preferred update path is approximately:

```text
1. Remove entity PK from oldValue's existing PK bitmap.

2. If oldValue still has at least one entity:
      no trigram change.

3. If oldValue became unused:
      mark/remove its valueId from all unique trigrams of oldValue
      according to MVCC lifecycle rules.

4. Look up newValue.

5. If newValue already exists:
      add entity PK to its PK bitmap
      no trigram change.

6. If newValue is a new distinct value:
      allocate valueId
      generate UNIQUE trigrams
      add valueId to each trigram posting
      create its entity-PK bitmap.
```

The word **UNIQUE** matters.

For:

```text
"aaaaa"
```

the trigram:

```text
aaa
```

occurs multiple times, but the membership index only needs:

```text
aaa → valueId
```

once.

---

# 22. Interaction with other evitaDB filters

One trade-off needs explicit attention.

An entity-based trigram index:

```text
trigram → entityPK
```

can immediately participate in arbitrary entity-level bitmap algebra:

```text
categoryBitmap
AND
trigramBitmap
```

A valueId-based index operates in a different ID space:

```text
trigram → valueId
```

so it cannot directly intersect with an entity-PK filter.

The flow becomes:

```text
trigrams
    ↓
matching values
    ↓
OR entity-PK bitmaps
    ↓
AND category/price/etc. bitmap
```

For normal searches this may be perfectly acceptable.

But if another predicate already reduces the entity set to, for example:

```text
20 entities
```

it may be cheaper to inspect the relevant values for those 20 entities directly rather than execute the trigram index.

Therefore the query planner should eventually consider both strategies.

Example:

```text
Strategy A

trigram index
→ matching valueIds
→ entity bitmap
→ AND structured constraints
```

versus:

```text
Strategy B

structured constraints
→ very small PK candidate set
→ evaluate contains directly
```

This is a query-planner optimization, not a reason to reject the valueId architecture.

---

# 23. Optional index and backward-compatible execution

The trigram index should remain an explicit schema-level optimization.

Conceptually:

```text
attribute:
    filterable: true
    containsIndex: true
```

Exact schema syntax is outside this research scope.

Execution can remain transparent to the query:

```text
contains(name, "iphone")
```

Planner:

```text
if TrigramIndex(name) exists
    use optimized trigram path
else
    use existing B+Tree value scan
```

This follows the PostgreSQL philosophy where the predicate semantics do not change merely because a specialized index exists.

---

# 24. Recommended prototype

The first prototype should intentionally remain simple.

## Data structures

```text
DistinctValueIndex

valueId → {
    normalized value,
    existing entity PK bitmap
}
```

and:

```text
TrigramIndex

packed 63-bit Unicode trigram
    →
RoaringBitmap<valueId>
```

No:

- positions,
- frequencies,
- scoring,
- FST,
- fuzzy matching.

Store only membership.

## Query

```text
1. Normalize pattern.
2. If fewer than 3 code points:
      fallback.
3. Generate unique query trigrams.
4. Resolve posting cardinalities.
5. Sort trigrams ascending by cardinality.
6. Intersect valueId postings.
7. Retrieve candidate distinct values in valueId order.
8. Run exact contains() against each value.
9. Obtain existing entity-PK bitmap for each matching value.
10. OR those bitmaps.
11. Continue normal evitaDB bitmap filtering.
```

---

# 25. Measurements the prototype must collect

Before deciding whether the index is viable, collect statistics from **real catalog snapshots**.

For each candidate attribute:

```text
N = indexed value occurrences
V = distinct values
N / V = reuse ratio

average / P50 / P95 / P99 value length

average unique trigrams per distinct value

number of distinct trigram keys K

total trigram/valueId memberships E
```

Also collect trigram posting cardinality distribution:

```text
P50
P90
P95
P99
max
```

and container distribution:

```text
array containers
bitmap containers
run containers
```

---

# 26. Memory measurements

Measure independently:

```text
1. trigram key structure
2. serialized posting size
3. Java heap posting size
4. valueId mapping
5. normalized value dictionary
6. persistence/COW metadata
7. complete index size
```

Do not infer Java heap usage solely from serialized Roaring size.

Run the same benchmark for:

```text
A. trigram → entityPK
B. trigram → valueId
```

The difference will directly quantify the benefit of deduplicating values.

---

# 27. Query benchmark matrix

At minimum test:

```text
pattern length:
3
4
6
10
20+
```

with:

```text
very common substring
medium-selectivity substring
rare substring
non-existing substring
```

Measure:

```text
number of query trigrams

candidate values after first trigram

candidate values after each intersection

final candidate values

false-positive candidates

exact string checks

matching distinct values

matching entities

bitmap-intersection CPU time

value-verification CPU time

value page reads / cache misses

total query latency
```

Run both:

```text
warm cache
cold cache
```

Random-I/O behavior is especially important for the cold-cache test.

---

# 28. Update benchmark matrix

Measure:

```text
new entity using existing value

new entity introducing new distinct value

update existing → existing value

update existing → new distinct value

delete entity while value remains referenced

delete last entity referencing a value
```

The expectation is that most operations involving already-existing values should require **no trigram-posting modification**.

If real catalogs exhibit strong value reuse, this may be a major advantage over entity-PK postings.

---

# 29. Important alternative to benchmark: small posting lists

If the heap profile shows significant overhead from many small `RoaringBitmap` instances, benchmark:

```text
cardinality <= threshold
    → sorted packed int[]

cardinality > threshold
    → RoaringBitmap
```

A similar high-level idea exists in PostgreSQL GIN: small postings are kept as simple posting lists while large ones become separate posting trees.

The threshold should be determined empirically.

---

# 30. Recommended working hypothesis

The current preferred architecture should be:

```text
                 existing distinct-value index
                          │
                 ┌────────┴─────────┐
                 │                  │
              valueId           PK bitmap
                 │
                 │
         compact value lookup
                 │
                 ↑
trigram → RoaringBitmap<valueId>
```

rather than:

```text
trigram → RoaringBitmap<entityPK>
```

The primary reasons are:

1. **Posting cardinality scales with distinct values rather than entity occurrences.**
2. **Exact verification happens once per candidate distinct value.**
3. **Entity data does not have to be loaded for verification.**
4. **Repeated values require no trigram update when another entity starts/stops using them.**
5. **Existing value → PK bitmaps can be reused to produce the final entity result.**
6. **Positions can initially be omitted, greatly reducing index complexity and likely memory consumption.**
7. **The slow existing B+Tree scan remains a natural fallback when the specialized index is absent.**

The primary unresolved risks are:

1. memory consumption for high-cardinality attributes,
2. Java object/container overhead,
3. efficient `valueId → string` lookup without random I/O,
4. MVCC-safe valueId lifecycle,
5. high write amplification when nearly every value is unique,
6. planner behavior when another structured predicate is already extremely selective.

---

# 31. Key experiment to run first

Before implementing the full index, build an offline analyzer over a real evitaDB catalog that computes only:

```text
V
N/V
K
E
trigram frequency histogram
estimated / actual Roaring serialized size
```

for selected string attributes.

This should be enough to answer the first crucial question:

> Does `trigram → valueId` fit into the acceptable memory/storage budget on real e-commerce data?

Only after that result should the implementation invest in persistence, transactional updates and query-planner integration.

The second prototype should add the packed value dictionary and measure:

> Can exact verification of candidate distinct values be done without meaningful random I/O?

If both answers are positive, the architecture has a strong path toward a production implementation.

---

# 32. Correlation with the evitaDB codebase (verified 2026-08-24)

The brief was written against an idealized model of the engine. Verified against the sources on branch
`258-fulltext-support`, three load-bearing assumptions need correction and several findings sharpen the
design. File references are anchors, not re-descriptions — the code is the source of truth.

| Brief assumption | Reality |
|---|---|
| "scan distinct values in the existing B+Tree" (§1) | ✅ Correct — `AttributeIndex.sharedValueIndex` holds one `InvertedIndex` (a `TransactionalBucketBPlusTree`, leaf block 256) per (attribute, locale); `FilterIndex.getRecordsWhoseValuesContains` is a full predicate scan over all buckets, marked `TOBEDONE JNO naive and slow` in the source |
| Values resident in memory — Option A of §13 | ✅ True — evitaDB is in-memory; the `PAGED` storage-part concept is flush granularity, not lazy query-time paging, so §13's random-I/O options B–D solve a problem the engine does not have. ⚠️ But string keys live as **front-coded byte blobs** (`FrontCodedStringColumn`, restart point every 16 entries): random access by ordinal costs a restart-walk decode, and leaf slots renumber on every insert/split/merge — there is no per-value slot to "simply attach" an ID to |
| "the existing index already maps distinct value → PK bitmap" (§4) | ❌ False for singletons — a bucket keeps a lone record id as a bare primitive `int` and promotes to a `TransactionalBitmap` only on the second id; `ValueToRecord*` objects are transient flyweights. For the high-cardinality attributes trigram search targets, most values are singletons (~10 B key + 4 B int). The valueId win is posting compression (dense ID space vs. sparse PK space) and once-per-value verification — not reuse of pre-existing bitmaps |
| A stable valueId exists or can be attached cheaply (§4, §13) | ❌ None exists; the only ID-like things are the per-tree `InvertedIndex.id` and per-leaf version tokens, both runtime-only and regenerated on load. A persisted valueId is new storage surface (storage part, Kryo serializer, WAL serializer) plus an allocator that must itself be a `TransactionalLayerProducer` and satisfy `Snapshotable` for partial rollback |
| A hook fires when a distinct value disappears (§21 step 3) | ❌ None — the bucket is deleted inside the leaf, immediately, with no tombstone tier and no notification to any registry. The reclamation ledger the §20 MVCC lifecycle needs is new machinery; the precedent for "structure vanished but its resources must still be reclaimed outside transactional memory" is `persistedFilterInvertedLeafPages` in `AttributeIndex` |
| Sizing model of §8–§9 | ⚠️ Models **one** index. Each of `GlobalEntityIndex`, `ReducedEntityIndex`, `ReducedGroupEntityIndex`, `ReferencedTypeEntityIndex` owns its own `AttributeIndex` per (attribute, locale) per scope, so an un-hoisted trigram structure would be replicated per entity index. §22's ID-space observation is therefore a **memory-multiplier decision** (hoist trigram postings to the catalog/global level, intersect with the scope's entity bitmap afterwards), to be settled before the sizing math — not a query-planner optimization afterwards |

Additional constraints the design must absorb:

- **Do not regress `startsWith`.** It already has an anchored range-scan fast path with early break
  (except under a localized comparator, where it falls back to the full scan). The trigram index is for
  `contains` and `endsWith` only.
- **Formula-cache contract.** `InvertedIndex.getRecordsMatchingFormula` seeds its result with per-leaf
  version tokens so cached formulas invalidate precisely (with an `EXCESSIVE_HIGH_CARDINALITY` collapse
  to the whole-index id). A trigram-backed path must produce an equivalent cache key, or it will either
  over-invalidate or — worse, silently — under-invalidate.
- **Schema flag shape.** Attribute capabilities are scope-scoped (`Set<Scope>` / `ScopedX` pattern), not
  boolean; §23's `containsIndex: true` sketch becomes a scoped flag, and the capability should register
  in `SchemaCapabilityUsageRegistry` so operators can see whether the index earns its memory.
- **The natural home of the valueId** is the shared value tree itself: `FilterIndex`, `SortIndex` and
  `UniqueIndex` are all views over `AttributeIndex.sharedValueIndex`, so an ID attached there is
  automatically consistent across the three roles and across the one canonical NFD form they share.

---

# 33. Relation to the fulltext prototypes (P1, P2, bitmap-memory-optimizations)

This brief originated outside the #258 research line but converges with it on four layers; it is adopted
into this record so the shared questions are decided once, not twice.

1. **Same substrate.** `p1-index-core.md` §3.1 establishes `TransactionalBucketBPlusTree` as a finished
   term dictionary — including the primitive single-`int` bucket that §29 of this brief proposes to
   benchmark as a "small posting list" representation. A trigram index lives on the same tree family,
   with a primitive `long` key column (§11) instead of the front-coded String one.
2. **Same indirection as the stem tree.** `trigram → valueId → PK bitmaps` is structurally the O1 design
   of `bitmap-memory-optimizations.md` §2 (`stem → surface-form keys → postings`, lazy OR at query time),
   and both buy the same update-amplification collapse: postings change only when a dictionary entry is
   born or dies (§6 here ≙ §2.5a there). The difference is reference-list cardinality: a stem maps to
   single-digit surface forms, so suffix-encoded key lists win and numeric term ids were rejected there
   (§2.7, §5.1); a trigram maps to up to hundreds of thousands of values, so only a dense numeric ID
   space that Roaring can compress is viable. **Cardinality of the reference list is the discriminator**
   between the two shapes — and if the fulltext line ever wants dense term ordinals (impact sidecar
   addressing, the P3 suggester), this valueId infrastructure is the shared answer and should be designed
   with both consumers at the table.
3. **Same measurement harness.** The §31 offline analyzer is P1's K1 corpus extractor plus a K3-style
   statistics pass with a different token generator. One extractor, two tokenizations, one report that
   feeds both the P1 gate and the trigram viability question (V, N/V, K, E, the Zipf and container
   histograms — plus the replication factor of §32).
4. **Complementary semantics — and the answer to P1's Q6.** `p1-index-core.md` §7.5 and Q6 name the
   honest regression of tokenized fulltext: `attributeContains("5000")` finds "5000mAh", a term index
   does not unless the tokenizer split the token. The trigram index answers Q6 from the other side — it
   makes the literal-substring path fast instead of asking P5's tokenizer to absorb infix semantics. Each
   line then stays semantically clean: fulltext = tokenized + ranked, `contains`/`endsWith` = literal +
   unranked, both fast. (`endsWith` falls out of the trigram design uniformly; a RadixTree — the in-code
   `TOBEDONE` proposal — would fix only prefix-shaped problems and would need a second, reversed tree for
   suffixes.)

---

# 34. The valueId and the reduced-index value duplication (added 2026-08-24)

The valueId question turns out to be larger than this brief. Introduced only for the trigram index it is
a small net memory **loss** (an id column plus an allocator plus persistence surface for every distinct
value); but the same infrastructure is the key to the largest known value duplication in the engine — and
there it can be a large net **win**. Verified against the code on 2026-08-24:

## 34.1 What is duplicated today

- For every reference marked `FOR_FILTERING_AND_PARTITIONING`, **all filterable and sortable entity-level
  attribute values** are copied into every `ReducedEntityIndex` (one per distinct referenced entity) and
  every `ReducedGroupEntityIndex` (one per group) — not only reference attributes
  (`AttributeMutationFanOut`, `ReferenceIndexMutator.indexAllEntityLevelAttributes`). References marked
  only `FOR_FILTERING` do not copy entity attributes down — the gate already exists in the schema.
- Each reduced index owns its own `AttributeIndex`, hence its own front-coded trees: the string bytes are
  replicated per index, **on heap and on disk** (each index emits its own `FilterIndexStoragePart` leaf
  pages). No sharing mechanism exists for attribute values. The `ReducedEntityIndex` javadoc claiming
  "all memory expensive objects are referred and maintained in `GlobalEntityIndex`" is true **only for
  prices** (`PriceListAndCurrencyPriceRefIndex` shares immutable `PriceRecord` instances and persists ids
  only) — the javadoc is stale and should be fixed.
- Measured scale on a production e-commerce catalog: **~179 000 reduced indexes** for one collection
  (write-path tuning ADR, 2026-07-27). Front-coding costs ~10 B/bucket for code-like attributes but
  ~125 B/bucket for localized names — the expensive case is exactly the attribute class trigram search
  targets.
- A second duplication layer exists even inside one entity index: a **sort-only** attribute and every
  **sortable attribute compound** get an `OwnerSortIndex` owning its own private `InvertedIndex`
  (the `SortIndex` view-vs-owner split), storing the values a second time.

## 34.2 Why a naive `valueId → bitmap` map cannot replace a reduced tree

When a `referenceHaving` / `hierarchyWithin` plan wins, the **whole** filter tree — including range scans
(`between`, `greaterThan`, `startsWith`), `attributeNatural` sorting and `attributeHistogram` — executes
against the reduced index's own value side, in comparator order (`QueryPlanner.createFilterFormula`
translates the full `filterBy` per candidate index set; `FilterByVisitor.applyOnFilterIndexes`,
`AttributeHistogramTranslator`). An unordered id→bitmap map cannot serve those, and a dense
*order-preserving* id cannot survive inserts of intermediate values without a global renumber.

## 34.3 The shape that can work — ordering hoisted once, membership local

Keep the ordered dictionary **once** — in the global index's shared value tree, which for entity-level
attributes is already the superset of every reduced index's values — attach the stable valueId there, and
let a reduced index hold only:

```text
RoaringBitmap<valueId>          values present in this index
valueId → PK bitmap / bare int  postings, keyed by id, unordered
```

Ordered operations then walk the global tree's cursor over the range and skip valueIds absent from the
reduced index's membership bitmap; equality and facet-style operations never touch the tree at all. This
is the price super/ref pattern applied to attributes — payload in the super structure, ids in the reduced
one, looked up per operation, never held by reference — including the persistence shape (the reduced
index persists ids only and re-attaches on load, exactly as `restorePriceRecordsFrom` does). The cost
model shifts: a range scan over a sparse reduced index pays for walking the global range, so selectivity
decides which side drives — a planner question, not a correctness one.

Reference-level attributes have no global-tree superset (they exist only in the reduced and
referenced-type indexes), so their dictionary would live on `ReferencedTypeEntityIndex` — which holds
the union of reference-attribute values across its reduced indexes, so the same subset invariant holds
with a different host.

## 34.4 What the subset invariant buys

Reduced indexes never invent values: every value — and every posting PK per value — is a subset of what
the global index holds for the same attribute. That invariant, combined with the hard requirement that
final PK algebra stays fed from the reduced indexes' small bitmaps (the reason reduced indexes exist),
makes the §34.3 shape not merely workable but in places faster than today:

1. **One allocator, no distributed lifecycle.** Ids are minted only in the global upsert; the reduced
   write in the same transaction reuses the id — the reduced side never allocates. And because reduced
   postings per value are subsets of the global postings for that value, "the global bucket died"
   implies every reduced membership for that id is already gone in the same committed version — global
   bucket death is the **single** reclamation trigger, and the distributed refcounting feared as blocker
   3 in the earlier analysis never materializes. Snapshot safety remains the §20 monotonic/generation
   problem, unchanged.
2. **The reduced write path becomes string-free.** Today each fanned-out reduced upsert performs a
   comparator insert into its own front-coded tree — and collation was the most expensive item of the
   write CPU profile (write-path tuning ADR, 2026-07-27), across ~179 k re-shelled indexes per commit.
   With ids, a reduced upsert is "set the membership bit + add the PK to the id-keyed postings": two
   int/bitmap operations, no string comparison, no leaf splits.
3. **Range and prefix predicates evaluate once, not per reduced index.** Compute
   `range → RoaringBitmap<valueId>` by one global cursor walk, then AND it with each reduced index's
   membership bitmap. The result is a valid superset filter for every reduced index precisely because
   reduced ⊆ global. Today a plan over N reduced indexes runs N independent tree scans; this shape runs
   one scan plus N tiny ANDs — and the global range bitmap is cacheable in the formula cache (keyed by
   the global tree's version tokens), shared across reduced indexes **and across queries**. The same
   single-evaluation argument applies to `attributeHistogram` (one global ordered bucket stream drives
   all reduced indexes) and to ordered traversal for sorting.
4. **The trigram index composes with reduced scoping for free.** One global trigram index produces
   candidate valueIds; one AND with the membership bitmap scopes them to any reduced context. This
   resolves §32's replication-multiplier concern by construction: the trigram structure is hoisted once,
   never replicated, and yet every reduced-index plan can use it.
5. **All cross-index coordination happens in valueId space, not PK space.** Membership and range bitmaps
   have cardinality bounded by V (distinct values), not N (entities) — the same `R = N/V` compression of
   §5 — so the "global-side" work never touches large PK bitmaps. Those are read only from the reduced
   index, at the final OR, honoring the rule that the query is fed from the small structures.
6. **The planner's direction choice is cheap.** Membership cardinality is O(1) from Roaring; when the
   membership set is far smaller than the value range, evaluation can instead iterate the membership
   bitmap and verify each candidate with an O(1)/O(log n) id → value probe into the global dictionary.
   Both directions end in the same reduced postings; which side drives is a cost-model choice, not a
   correctness one.

What the invariant does not solve: the id → value random-access path (§13/§14) is still needed — for
probe-driven evaluation and for trigram verification — and valueIds remain allocation-ordered, so a
membership bitmap alone can never answer an ordered predicate; the global tree stays the only ordered
structure.

## 34.5 Order of work

1. **Measure before designing.** `EntityIndex.getHeapSizeInBytes()` and the `browseIndexes` API (ADR
   2026-08-16) can attribute heap per index type today. Sum the attribute-index share of reduced indexes
   on a real catalog and add it to the §31 analyzer's first report, next to N/V.
2. **A zero-engine-change win exists first.** References marked `FOR_FILTERING_AND_PARTITIONING` whose
   reduced indexes never win a query plan can be downgraded to `FOR_FILTERING`, dropping whole attribute
   copies; the per-index usage statistics are exactly the instrument for finding them.
3. **Introduce the valueId in the global shared value tree** — per (attribute, locale, scope) tree,
   monotonic, persisted, with the allocator a `TransactionalLayerProducer` scoped **per tree**, not one
   catalog-global hot point on a write path that re-shells ~180 k reduced indexes per commit. The trigram
   index is the first consumer.
4. **Reduced-index payload dedup is its own optimization line** — like the lazy-loading "storey 1" of
   `bitmap-memory-optimizations.md` §3.7, a general serving-layer change deserving its own issue outside
   #258 — with the valueId as its prerequisite. Only at that step does the valueId turn from a small net
   loss into a large net win.

---

# 35. Spike measurements and settled forks (added 2026-08-25)

The §25–§28 measurement program was executed as a two-stage spike (offline analyzer per §31, then
prototype structures + JMH per §24/§27) on real corpora: a production CMS catalog (972K articles;
`title` V = 943,410, `url` V = 966,488 distinct values) and the evita-demo-dataset. The measurement
harness lives in `io.evitadb.spike.trigram` under `evita_performance_tests`; the latency baseline is
the **actual engine scan** (`FilterIndex.getRecordsWhoseValuesContains` invoked through the real
`InvertedIndex`), not a re-implementation, and every trigram result cell was cross-checked against
that baseline for exact agreement before anything was timed.

Two measurement caveats that any reuse of these numbers must carry:

- **Latency figures were taken on a partially loaded box** (58–65% CPU idle over the run). Every
  baseline-vs-trigram ratio comes from one JMH invocation with both benchmarks' forks interleaved,
  so the *ratios* are solid; the absolute µs values are upper bounds, not latency claims.
- **JOL heap figures depend on the compressed-oops regime**, which flips above 32 GB heaps (+~9%
  object overhead). Every heap budget derived from this section must state the regime it assumes.

## 35.1 The gate — passed on all three criteria

| criterion | bound | measured | verdict |
|---|---|---|---|
| `contains` vs. baseline scan at P50, medium-selectivity patterns | ≥ 10× | `title` median 3,987× (min 2,350×, max 5,059×); `url` median 2,176× (min 1,044×) | pass |
| never slower than baseline on any measured class | ≥ 1× | all 35 cells of the §27 matrix faster; worst cell 3.49× (`url` L3/common) | pass |
| false-positive candidates within 10× of true matches | ≤ 10× | worst cell 0.36 per true match (`title` L4/common: 6,249 fp / 17,245 true); 26 of 35 cells exactly 0 | pass |
| memory within the analyzer-confirmed budget | §8 model | realistic opt-in set (`title`+`keywords`+`authors`+category names) ≈ 184 MB heap on 972K articles; flag-everything would be 743 MB — the per-attribute flag *is* the memory story | pass |

Representative `title` cells (µs/op, contended-box upper bounds; the ratio is the result): a 3-letter
medium pattern 307,180 → 130.7 (2,350×); rare patterns ≈ 1 µs; a **nonexistent** pattern 0.02 µs vs
205,935 — a missing trigram kills the query before any verification.

## 35.2 Forks closed by measurement

- **§13/§14 value dictionary → tree-attached wins; packed blocks rejected as the default.** The
  dictionary is id columns attached to the already-existing front-coded value tree (leaf block 256,
  restart 16) plus a packed `(leaf, slot)` live-id directory: **~8 B/value marginal** heap, since the
  value bytes already live there. Option B/D packed blocks (§13) decode 1.77× faster in isolation
  (~418 vs ~738 ns/value), but end-to-end that dilutes to a **mean 1.38×** on the only cells that
  verify in bulk — while duplicating the whole value corpus at 80–121 B/value (~120 MB extra on
  `title` alone, vs +9.5 MB total for tree-attached across the realistic opt-in set). **Rejected
  because** 1.4× on a ~50 ms worst-case query does not buy 8× dictionary memory. It would flip only
  for a workload dominated by very common substrings; the cheap middle path — packed blocks as a
  *derived, rebuilt-on-load* cache over the tree — stays available and needs no persistence format
  (note: a single-blob layout hits the 2 GiB `byte[]` ceiling near ~2 GB of value bytes; block it).
- **§15 positions → never.** Confirmed empirically, stronger than the SQLite argument: trigram
  intersection on real corpora is nearly exact (worst 0.36 fp/true match; a 20-code-point `url`
  pattern left 14 false candidates out of 68,831). Phase 2 of §15 is cancelled, not deferred.
- **§16/§29 posting representation → hybrid, and the threshold is a constant, T = 128.** Sorted
  `int[]` up to T entries, RoaringBitmap above (−51% postings heap on the demo corpus, −6.4% on the CMS corpus
  at the knee). The follow-up sweep (2026-08-25, six groups spanning a 31× V range, JOL only)
  falsified the first-guess scaling rule `T = 32 × ⌈V/65536⌉` — wrong coefficient *and* wrong
  variable; the crossover is linear in the containers a posting *actually spans*
  (`T* ≈ 26 + 103·c`, R² = 0.965, exactly the cost algebra of `4n+16` vs `F + c·C + ~2n`). But the
  heap curve is so flat that a single **T = 128 lands within 1.7% of every per-group optimum**, and
  the whole stake is ~4% of the opt-in set's heap — no scaling machinery is worth that. Latency
  caveat (unmeasurable on the contended box): T bounds the worst-case linear probe length of the
  intersection, so 128 is 4× the T=32 worst case — confirm on a quiet box before the constant lands
  in production code.
- **§11 key structure → open-addressing long-keyed table, load ≤ 0.75.** 1.1–1.6 ns/lookup vs
  59–95 ns binary search over sorted `long[]` (40–60×) vs 4.5–28 ns boxed `HashMap`, for +0.4% heap
  on large attributes. `-1L` is a safe empty sentinel: §11's packing fills bits 0..62, so no legal
  trigram is all-ones. The persisted form stays the sorted `long[]`; the table is rebuilt on load.
- **§17 early exit → off by default.** A verification-cost bound of 1024 made medium-selectivity
  cells 2–4× *slower*; bounds 8–32 were neutral. What §17 got right and *is* mandatory is
  cardinality-ascending intersection: on `url`, `://`-class trigrams post against up to 966,488 of
  966,488 values (universal trigrams are real), so intersecting in textual order is not viable.
- **§6/§21/§28 update model → confirmed.** Churn on an existing value costs **zero** trigram-posting
  writes, zero dictionary writes, zero id allocations (asserted in the harness — the run fails if a
  write occurs); a genuinely new value costs 1.4–8.2 µs touching 22–121 postings; removing a value's
  last occurrence 3.1–14.1 µs.

## 35.3 Corrections to the brief's claims

- **§5's `A/B ≈ R` is an upper bound, reached only asymptotically.** Measured serialized A/B:
  N/V = 134 → 21×; 17.5 → 8.3×; 8.1 → 4.0×; N/V ≈ 1 → 1.00–1.14×. The residual B advantage at
  N/V ≈ 1 comes from dense valueIds vs sparse entity PKs (fewer Roaring containers). Any cost model
  predicting A/B from N/V alone is wrong in both directions.
- **§8's serialized model holds (1.6–2.4 B/membership measured vs ~2 B predicted), but heap is
  1.1×–3.2× serialized** depending on container density — budgets must be stated in heap.
- **§13's "candidates are naturally sorted after intersection and can be verified in ID order,
  coalescing reads" is false for the tree-attached dictionary.** Live valueIds are
  allocation-ordered, not tree-ordered, so id order does not imply locality; resolving a live id
  needs the 4 B/value `(leaf, slot)` directory. Only the rejected packed-blocks layout had the
  claimed property.
- **§19's "#545 shrinks V and with it K and E" has the emphasis backwards.** Case folding barely
  merges values (the CMS corpus V −0.5%, E −0.1%) — it collapses the *key space* (K −18.5% overall, −35.5% on
  `title`). Still a net saving, never a loss, but the benefit lives in the trigram keys.
- **§27's cold-cache rows are structurally N/A for now** — the engine is in-memory and the spike
  structures are heap-resident; those rows become live the moment postings are persisted rather than
  rebuilt.

## 35.4 The structural cost model (input for the planner)

- **Verification dominates: 55–87% of query time** on common/medium cells. Every dictionary decision
  is a query-latency decision, and the design's honest ceiling is on very common substrings
  (matching ≥ 1% of values): 3.5–11× over the baseline, because tens of thousands of candidates are
  verified by random-access decode while the baseline walks the same values sequentially. This is
  the shape of the design, not noise.
- **The §22 planner crossover, measured** (pre-reduced entity count below which scanning the reduced
  set beats the trigram path, `title`): common patterns ≈ 17,500–68,500; medium ≈ 58–353; rare 1–2;
  nonexistent 0.
- **Non-ASCII verification costs ~2× per candidate**: Czech NFD `title` verifies at ~441
  ns/candidate vs ~208 ns for pure-ASCII `url` — same corpus size, same code. Compact-Latin1 does
  not apply to decomposed Czech; ASCII-calibrated estimates are optimistic by 2× on localized
  attributes.
- **`RoaringBitmap.runOptimize()` cuts serialized size of structured attributes 30–52%** and the
  engine never calls it. This is deliberate, not an oversight (sponsor, 2026-08-25): the call has
  its own CPU cost, the containers were assumed not to pay it back, and — decisively — there is no
  single controlled call site; sprinkling it across dozens of mutation paths would mean losing
  control over where that CPU is spent. The measurement adds one nuance to the density assumption:
  the 30–52% wins came from *run-heavy* containers (consecutive-id ranges on code/URL-like
  attributes), not from density per se. If it is ever revisited, the race-free seams are the places
  where a bitmap is thread-private by construction — the freshly merged immutable version produced
  at commit, or right after deserialization on load — never in-place on a published bitmap; and the
  trigram index's own persisted postings (a new write path anyway) are the contained place to gather
  real-world evidence first without touching any existing path.

## 35.5 Known gaps the spike did not close

- The spike's key table is sized once and never resizes; its insert numbers exclude rehash and are a
  floor. The production key structure must be a resizable/persistable tree (§32's substrate).
- The bucket-death hook of §21 step 3 / §32 still does not exist in the engine; the spike measured
  what it will cost once it does.
- ~~The cost of rewriting the `(leaf, slot)` directory on a leaf split~~ — closed by operation
  counting (2026-08-25): the question was posed at the wrong event. Splits are under 1% of directory
  traffic (0.7 of ~94 writes/insert; one split per ~175 inserts moving exactly 128 values); the
  dominant term is the in-leaf slot shift, which invalidates ~half a leaf's directory entries on
  *every* insert. Total write cost: **~749 B/insert** (directory + id column) against the ~6 KB of
  front-coded blob the same insert already re-encodes today — P8 adds ~12% to bytes moved, trivial.
  The packed `(leaf, slot)` directory stays (a read-side decision the write path is not expensive
  enough to overrule); a leaf-only directory remains available as a 2×-total-bytes lever for a
  write-heavy attribute (the "54× less directory traffic" figure counts only the directory — both
  designs pay the id-column memmove). **One hard constraint surfaced: the directory must reference
  a stable leaf id, never a leaf-array position** — a positional reference renumbers every leaf
  after a split (`O(V)` per split, design-ending). The implementation needs a `leafId → leaf`
  indirection with a monotonic leaf-id allocator, same shape as the value-id allocator.
- `endsWith` shares the whole candidate path and its correctness is proven against the engine, but
  it has no latency column of its own.