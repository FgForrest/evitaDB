# Radix-trie memory spike — results & verdict (#760)

**Question.** Can storing distinct inverted-index attribute *values* in a prefix-shared, order-preserving
**compressed radix trie** reclaim meaningful heap versus the current `TransactionalObjectBPlusTree`
(which keeps one full value object per distinct value)? Primary targets per the design note: **String** and
**OffsetDateTime** values "with frequently shared prefixes".

**Verdict: NO-GO for the stated targets.** Confirmed against **real evitaDB demo-catalog `Product` data**
(section below): the trie regresses on **all four** target attributes — `code`, `url`, `published`,
`changed` — by **1.13×–2.27×**. The earlier synthetic "win" on non-localized strings (~29%) was an
**artifact of unrealistically long synthetic URLs**; on the real, *short* slugs/codes (~22 B) the per-node
overhead dwarfs the prefix bytes saved even at 87–89 % prefix sharing. Detail and root causes below.

## Real evitaDB demo data — the decisive test

Synthetic generators flattered the trie (long keys amortize node overhead). The real test pulls the actual
distinct values from the public demo catalog (`https://demo.evitadb.io:5555/gql/evita`, ~4.2 k `Product`s)
for the four attributes Johnny named, and measures the same way (JOL delta vs empty, shared sentinel payload,
production B+ tree geometry). Run: `… RadixTrieMemorySpike --real <dir>`; data fetched via GraphQL.

Distinct-value prefix profile (sorted UTF-8): `code` avg **21.9 B**, 87.6 % of bytes shared with neighbour;
`url_en` avg **25.1 B**, 89.4 % shared; `published` 4 161 distinct crammed into a **~3.5-minute** window;
`changed` only **781 distinct** (heavy exact-second duplication) over ~15 min.

| attribute (production codec) | distinct | trie footprint | btree footprint | trie / btree | trie B/key | btree B/key | nodes/key |
|---|--:|--:|--:|--:|--:|--:|--:|
| `code` — String, **utf8** (non-localized) | 4 161 | 398,680 B | 339,096 B | **1.18×** | 95.8 | 81.5 | 1.27 |
| `url_en` — String, **utf8** (sharing upper bound) | 4 161 | 400,928 B | 355,008 B | **1.13×** | 96.4 | 85.3 | 1.28 |
| `url_en` — String, **collation** (localized → prod) | 4 161 | 805,152 B | 355,008 B | **2.27×** | 193.5 | 85.3 | 1.29 |
| `published` — **OffsetDateTime** (clustered ~3.5 min) | 4 161 | 277,464 B | 170,120 B | **1.63×** | 66.7 | 40.9 | 1.06 |
| `changed` — **OffsetDateTime** (781 distinct) | 781 | 48,192 B | 31,528 B | **1.53×** | 61.7 | 40.4 | 1.01 |

Footprint numbers are JOL-deterministic (byte-identical across runs). Build-time allocation is an aside, but
note the collation B+ tree churns ~89 MB transient (the `Collator` allocates on every `O(N log N)` compare)
vs the trie's ~10 MB (collation key encoded once) — an insert-throughput point, not retained memory.

**Why even `code` (87.6 % prefix sharing) loses.** The trie produces ~1.27 nodes/key; at ~48–60 B fixed
overhead per node object (header + prefix `byte[]` + child arrays) that is **~70–95 B/key of pure overhead**,
which *exceeds the entire ~22 B raw key*. Prefix sharing removes duplicate *character* bytes (a few B/key for
short slugs) but cannot remove *node* overhead. The B+ tree stores the same `String` objects packed into a
handful of dense 256-slot blocks at ~40–85 B/key and adds almost no structural overhead per key. For
`changed`/`published` the trie barely compresses at all (≈1.0 node/key) because distinct timestamps diverge
in their low bytes — the classic dense-numeric-key anti-pattern. An aggressively optimized ART (inlined short
prefixes, no per-node child array) could *narrow* the `code`/`url-utf8` gap, but (a) it would still carry
≥1 node/key, (b) it cannot help the localized-collation or timestamp cases, and (c) it only chases the two
columns that are *already closest*, never the ones that lose 1.5–2.3×.

## Method (synthetic, superseded by the real-data table above)

- Spike code: `evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/radixtrie/`
  (`RadixTrie`, `TrieKeyCodec`, `RadixTrieMemorySpike`). Non-transactional, single-threaded.
- Footprint = JOL `GraphLayout.totalSize()` measured as **delta vs an empty structure of the same type**,
  which cancels each structure's constant framework graph (notably the B+ tree's `Class<K>`/`Comparator`
  reference fields that JOL would otherwise follow into the `ClassLoader` graph). Isolates the genuine
  "heap to store N distinct keys" cost on both sides.
- Payload is a single shared sentinel, so the number reflects **key storage only** (in production both
  structures additionally carry an identical per-value record bitmap, so the absolute saving equals this
  key-storage delta).
- Codecs are order-preserving and self-verified before measuring: signed-long trap handled
  (`epoch ^ MIN_VALUE`), UTF-8 = code-point order, collation = `CollationKey` bytes.
- B+ tree geometry mirrors `InvertedIndex` exactly (block 256, min 127, internal 127/63).

## Results (N = 50,000 distinct values)

| profile | trie footprint | B+ tree footprint | trie / btree | per-key (trie → btree) |
|---|--:|--:|--:|---|
| strings shared-prefix (UTF-8) | 4.61 MB | 6.46 MB | **0.71×** | 92 B → 129 B |
| strings shared-prefix (collation) | 18.95 MB | 6.46 MB | **2.93×** | 379 B → 129 B |
| uuid control (UTF-8, no sharing) | 6.09 MB | 4.58 MB | 1.33× | 122 B → 92 B |
| timestamps clustered | 4.06 MB | 1.80 MB | **2.26×** | 81 B → 36 B |

Build-time allocation was ~equal except the collation B+ tree, which churned ~5.2 GB transient (the
`Collator` comparator allocates on every one of the `O(N log N)` compares); the trie pre-encodes each
collation key once (~291 MB). That is an insert-throughput aside, not a retained-memory result.

### N = 200,000 confirmation (ratios stable)

| profile | trie | B+ tree | trie / btree |
|---|--:|--:|--:|
| strings shared-prefix (UTF-8) | 18.43 MB | 26.07 MB | **0.71×** |
| strings shared-prefix (collation) | 76.34 MB | 26.07 MB | **2.93×** |
| uuid control | 24.35 MB | 18.37 MB | 1.33× |
| timestamps clustered | 13.95 MB | 7.20 MB | **1.94×** |

The 4× size increase leaves every ratio essentially unchanged (timestamps improve marginally
2.26× → 1.94× as denser clustering shares a few more date bytes, but remain a ~2× loss). The verdict is
not a small-N artifact.

## Why the targets lose (structural, not a tuning artifact)

1. **Locale-collated Strings — fundamental mismatch (2.93× worse).** The B+ tree stores the plain (NFD)
   String and computes collation order *on demand* with a `Collator`; it never stores collation keys. A
   byte-keyed trie must **materialize** `CollationKey.toByteArray()` as its path to get locale-correct
   range order — and collation keys are ~3–4× longer than the source and share prefixes poorly (primary /
   secondary / tertiary weight bytes interleave). Keying on plain UTF-8 instead would give the wrong
   locale order for range/ordering queries — unacceptable. So for the *primary* stated target the trie is
   structurally disadvantaged.

2. **Clustered timestamps — dense numeric keys (2.26× worse).** evitaDB normalizes `OffsetDateTime` →
   `Instant`; the B+ tree packs 50k `Instant`s in ~1.8 MB (~36 B/key). The 12-byte temporal key shares
   only its high ~6 date bytes; the sub-second/nanos tail diverges per record, producing ~1.2 nodes/key.
   Per-node overhead (~48 B object + a prefix array) dwarfs the 12-byte key. Dense fixed-width numeric
   keys are the classic radix-trie anti-pattern; B+ trees pack them better.

3. **Non-localized Strings with shared literal prefixes — *not* a win on real data.** The synthetic profile
   suggested a 29 % win, but it used long (~60–75 B) URLs that amortize node overhead. The **real** demo
   `code` and `url` slugs are short (~22–25 B) with 87–89 % prefix sharing and *still lose* (1.13×–1.18×),
   because ~1.27 nodes/key × ~48–60 B node overhead exceeds the whole short key. Prefix sharing removes
   duplicate characters, not node overhead — and short keys have little character mass to remove.

## Recommendation

- **Do not** adopt the radix trie as a general inverted-index value store, and **do not** build the
  transactional implementation. Validated against **real demo-catalog data**, the trie regresses on **all
  four** named target attributes (`code`, `url`, `published`, `changed`) by 1.13×–2.27×; the gate (≥25 %
  reduction) fails everywhere.
- The earlier "niche" carve-out (non-localized prefix-heavy strings) **does not survive real data** — real
  e-commerce slugs/codes are too short for node overhead to pay off. A heavily ART-optimized node could at
  best *narrow* the `code`/`url-utf8` gap toward break-even, but never wins the collation or timestamp cases.
  Not worth the transactional-implementation cost for a maybe-break-even on one column.
- Larger memory levers for the inverted index more likely lie in the **value/payload** side (value-object
  dedup, bitmap representation) than in the key structure.
