# FrontCodedStringColumn allocation attack — prioritized hypothesis doc

Branch `760-more-optimized-data-structures-in-indexes-more-granular-storage-parts`, tip `a97ba014e`.
Analysis only — **no code changed, nothing measured** (a JMH run held the box; implement + re-measure later).
Grounded in the ALIVE-phase allocation profile in
`docs/reports/2026-07-09-invertedindex-bucket-flyweight-remeasure.md` (79.75 GB total heap churn).

After the InvertedIndex bucket-flyweight fix, **`FrontCodedStringColumn` is the #1 allocator: 33.6 GB
(42.2 % of total ALIVE heap churn)** and untouched by any prior fix. This doc decomposes that 33.6 GB
by call site and lays out the attack, prioritized by (GB eliminated × safety) ÷ complexity.

The class stores one B+ tree leaf's String keys as a single front-coded UTF-8 `byte[]` blob
(Lucene term-dict layout: per entry `varint(sharedPrefixLen) varint(suffixLen) suffixBytes`, restart
point every 16 entries). Leaf block size is **64**, so a live leaf holds ~30–48 keys.

---

## 1. Where the 33.6 GB actually goes (async-profiler `-e alloc`, tip `a97ba014e`)

| Call site | Allocated | GB | Bucket |
|---|---|--:|---|
| `decodeAllBytes` — per-entry `Arrays.copyOf(cur,total)` | `byte[]` | **20.28** | **Write round-trip** |
| `decodeAllBytes` — outer `new byte[size][]` | `byte[][]` | **1.82** | Write round-trip |
| `insertKeyAt`/`removeKeyAt`/`copyRangeTo` — grown `byte[][]` | `byte[][]` | **1.65** | Write round-trip |
| `encode` — final `Arrays.copyOf(buf,len)` trimmed blob | `byte[]` (retained) | **2.02** | Write re-encode |
| `decodeAt` — `new String(cur,0,curLen,UTF_8)` backing bytes | `byte[]` | **4.38** | **Read decode** |
| `decodeAt` — the `String` object header | `String` | **2.15** | Read decode |
| `duplicate` — `Arrays.copyOf(data)` + `restartOffsets.clone()` | `byte[]`+`int[]` | **1.04** | **MVCC COW** |
| misc (`encode` restart `int[]`, `findKeyPosition` result, ctor) | — | ~0.26 | — |

Three **independent** buckets at three different call sites, addressable separately:

- **Write round-trip ≈ 23.7 GB (70 %)** — the decode-all → mutate-one-slot → encode-all cycle.
- **Read decode ≈ 6.5 GB (19 %)** — one `String` per binary-search hop (Johnny's incoming idea).
- **MVCC COW ≈ 1.04 GB (3 %)** — eager blob copy on transactional duplicate.
- **Write re-encode ≈ 2.02 GB** — the retained trimmed-blob copy (partly inherent, partly gated follow-up).

Key reframing: Johnny arrived with the read-side idea; the profile shows the **write path is ~3× larger
and has none of the UTF-8 correctness subtlety**. Both are worth doing and land independently.

---

## H1 — Flat decode buffer (write path). **PRIMARY. ~23.75 GB. Safe. Do first.**

**Mechanism.** Every single-slot mutation (`insertKeyAt`/`removeKeyAt`/`clearAt`/`copyRangeTo`/`fillEmpty`)
currently decodes the whole blob into a `byte[][]` of `size` freshly allocated arrays, applies
`System.arraycopy` slot semantics, and re-encodes a fresh blob. Replace the transient `byte[][]`
representation with **one reused thread-local flat `byte[]`** (all keys concatenated) **+ a reused `int[]`
offset table**. Slot operations become `memmove` within the flat buffer + offset-array shifts; `encode`
reads straight from the flat buffer. Two reusable allocations instead of `size + 2` per mutation.

**Eliminates:** 20.28 (per-entry copyOf) + 1.82 (outer `byte[][]`) + 1.65 (grown `byte[][]`) = **23.75 GB**
(94 % of the addressable write round-trip). Reuses the existing `DecodeScratch` thread-local (line 138).

**Why it is unconditionally safe.**
- Pure-H1 still ends in `this.data = Arrays.copyOf(buf, len)` — a whole-field replacement identical to
  today. It changes only the *transient* decode representation, so it inherits the current MVCC aliasing
  story wholesale — **zero MVCC audit required**.
- **No serializer / wire / BWC change:** `FrontCodedStringColumn` is referenced by no Kryo serializer;
  the blob is purely in-memory, leaves persist real `String`s. (Confirmed by two independent agents.)
- Read path (`findKeyPosition`/`decodeAt`) untouched → **orthogonal to H2**, they compose.

**Blast radius:** ~5 methods + a `decodeAllBytes`→flat helper.

**One fiddly method — `copyRangeTo`.** Used by leaf split/merge/steal rebalance
(`TransactionalBucketBPlusTree` :3309/:3314/:3365). Two cases: `dst==this` overlapping right-shift
(`stealFromLeft`) needs a *directional* memmove or a slice snapshot (as the current code already does at
line 265); `dst!=this` cross-leaf needs src+dst flat buffers live at once (a second scratch slot, or
decode the small moved slice into an owned temp). This is the one place to get right and test hard.

**Gated follow-up (+2.02 GB, do NOT fold into H1's headline).** Stop trimming `data` on every encode —
encode in place with geometric slack (the `dataLength` field already tracks live length vs backing
length). This removes the retained `encode` copy, but in-place editing of the *retained* blob is only
safe if every mutation is preceded by the COW decouple (`decoupleTransactionalArrays` :4287 →
`duplicate`). Verified for `addRecord`/`addLongRecord`; the steal/merge rebalance paths still need that
audit. **Also directly conflicts with H3** (see H3's dependency note) — the two cannot both assume the
blob is value-semantic; co-design or pick one.

---

## H2 — ASCII / BMP byte-compare on the search path (read path). **SECONDARY. ~6.5 GB write-side + speculative query win. Safe with the corrected gate.**

This is Johnny's hypothesis, and it is sound — with **one critical correction** the investigation surfaced.

**Mechanism.** `findKeyPosition` binary-searches by calling `decodeAt(mid)`, which allocates
`new String(cur,0,curLen,UTF_8)` per hop. But `decodeAt`'s scratch `cur[0..curLen]` **already holds the
decoded UTF-8 bytes immediately before the `new String`** (line 393). When the column is known
byte-order-safe, compare `cur[0..curLen]` against the probe's UTF-8 bytes with an unsigned
byte-lexicographic compare and **skip the `String` entirely**. The restart-chain walk, varint decode,
scratch reuse, and corrupt-blob check are all unchanged — near-zero-risk.

**The correctness predicate ("which characters are safe?").**
- UTF-8 byte-wise lexicographic order **== Unicode codepoint order** (guaranteed property of UTF-8).
- `String.compareTo` is **UTF-16 code-unit order**, which disagrees with codepoint order **only** when a
  supplementary char (> U+FFFF, surrogate pair) is compared against a BMP char in U+E000–U+FFFF.
- ⇒ raw-byte compare == `String.compareTo` order **iff no key contains a supplementary/surrogate char
  (BMP-only)**. **Pure ASCII (all bytes < 0x80)** is the trivially-detectable safe subset — exactly the
  codes / EANs / URLs / slugs Johnny named.
- **The probe must satisfy the predicate too**, not just the stored corpus — gate on a per-column flag
  *and* a one-time O(len) scan of the probe's encoded bytes.

**THE CRITICAL CORRECTION — the gate is NOT `comparator == null`.** The high-cardinality String trees
that dominate the profile pass `Comparator.naturalOrder()` (a singleton), never `null`:
- Unique index: `UniqueIndexBPlusTreeSupport.NATURAL_ORDER` (:63) = `Comparator.naturalOrder()`.
- Filter/inverted index: `FilterIndex.getComparator` → `DEFAULT_COMPARATOR = Comparator.naturalOrder()`
  (`FilterIndex.java:107,295`) for non-localized strings; `LocalizedStringComparator` only when a locale
  is present.

A `== null` gate would be **safe but dead** — the fast path would never fire. The gate must be
`comparator == null || comparator == Comparator.naturalOrder()`, i.e. the existing
`ValueColumnFactory.isNaturalOrder(...)` predicate. Localized (collator) columns correctly fail it and
keep the slow `String` path (byte order is meaningless under collation).

**Comparator identity confirmed unwrapped end-to-end** for both the only two String→FrontCoded consumers
(SortIndex uses sorted arrays; ReferenceTypeCardinality is Long): the naturalOrder singleton reaches
`findKeyPosition` by reference through `OwnerFilterIndex:96 → new InvertedIndex(…,comparator) :115 →
InvertedIndex.createEmptyTree:230 → new TransactionalBucketBPlusTree(…,comparator)`. Both leaf and
internal-node search use the same comparator, so leaf byte-compare stays consistent with internal-node
`String.compareTo` — precisely under the BMP predicate.

**Where the flag lives.** A single per-column `boolean allAscii`, authored **only in `encode(...)`** —
the one choke point every slot op funnels through — so it recomputes on every re-encode and needs **no
threading through mutators or split/merge/steal**. Only `duplicate()` (adopt-state ctor, doesn't call
`encode`) must copy it. **Hardening:** also thread a construction-time `naturalOrderSafe` boolean from
`ValueColumnFactory.isNaturalOrder` rather than an identity check per query (an identity check would
silently die if a caller ever wrapped naturalOrder).

**Persistence:** none — pure derived state, recomputed on load (trees rebuilt by re-insertion → `encode`).

**Upside:** close to the full ~6.5 GB ALIVE `decodeAt` allocation (2.15 GB headers + 4.38 GB backing
arrays); the hot binary-search descent is exactly where it fires. **Query-side upside is speculative** —
the attributeFiltering −60 % regression was root-caused to `RoaringArray` cold-walk in
`SortedRecordsSupplier`, *not* FrontCoded decode; confirm with a *read-path* alloc profile before
claiming a query win.

---

## H3 — Share the blob on `duplicate()` (MVCC COW). **QUICK WIN. ~1.04 GB. Trivially safe today.**

**Mechanism.** `duplicate()` (:217–223) does `Arrays.copyOf(this.data,dataLength)` + `restartOffsets.clone()`.
Share both references outright instead:
`new FrontCodedStringColumn<>(capacity, size, dataLength, this.data, this.restartOffsets)`.

**Why safe today.** Every write to `data`/`restartOffsets` is a **whole-reference replacement** (ctor +
`encode` at :478/:513/:480/:515); every byte access is read-only; both fields are `private`. So two
instances sharing the arrays can never observe each other's mutations — `encode`'s reallocation already
provides the isolation the eager copy was buying, including for concurrent readers of the committed base.
Distinct object identity is preserved, so the `layer.keys == this.keys` COW decouple check
(`TransactionalBucketBPlusTree` :3534/:3165/:3554) still works. Benefits both the `…ForUpdate` COW path
and `snapshot()` (:3725) savepoints (whose eager copy is pure waste on the commit path).

**Do NOT generalize** to sibling columns that mutate in place — `BoxedObjectColumn`, `LongValueColumn`,
`IntValueColumn`, `RecordColumn` genuinely need their clone. This share is specific to FrontCoded
*because* it reallocates via `encode`.

**Cost:** must ship with a load-bearing class comment ("share is safe only while every mutator reallocates
via `encode()`") and softened `ValueColumn.duplicate()` JavaDoc (currently promises "deep copy with new
backing array(s)"; change to "independent, non-aliasing copy").

**⚠ Dependency / conflict.** H3 relies on the blob being value-semantic (never edited in place). **H1's
gated in-place follow-up would break exactly that invariant.** If that follow-up ever lands, H3's outright
share must be upgraded to a `shared`-flag COW (mutator copies once before its first in-place edit). H1's
*core* flat-buffer change does not conflict (it still reallocates via `encode`). Land H3 with the pure
flat-buffer H1; treat the in-place follow-up as mutually exclusive with plain H3.

---

## Rejected / deprioritized

- **Pure in-place blob splice (write path, design "b").** "Exactly every 16th" restart rule reshuffles
  restart membership across the whole tail on any insert → must still decode+re-encode the tail; only the
  prefix is skippable, so it saves only ~10–12 GB vs H1's 23.75 with more MVCC risk. Rejected.
- **Block-independent re-encode ("≤16/block, split on overflow", design "c").** Would give true O(16)
  re-encode, but half-full blocks carry more uncompressed restart keys → a **retained-heap regression**,
  directly against this class's ~10 B/bucket purpose, plus a blob format redesign. H1 + gated follow-up
  dominates it. Rejected.
- **Append-fast-path (`insertKeyAt` at index==size).** Hit rate ~1/size under the random churn of the
  ALIVE scenario — only sorted bulk-load makes appends common. The flat-buffer H1 wins regardless of
  insert position, so it's the robust primary. Not worth a separate bet.

---

## Recommended sequencing

1. **H1 (flat decode buffer)** — biggest prize (~23.75 GB), safe, no BWC, self-contained. Test
   `copyRangeTo` split/merge/steal paths hard.
2. **H3 (share on duplicate)** — trivial ~1 GB quick win; ships cleanly *with* pure H1.
3. **H2 (ASCII byte-compare)** — Johnny's idea, ~6.5 GB write-side, orthogonal to H1/H3; use the
   `isNaturalOrder` gate, not `== null`. Separately profile the query path to confirm the read-side win.
4. **Defer** H1's in-place follow-up (+2 GB) until the rebalance-path COW audit is done — and note it is
   mutually exclusive with plain H3.

Combined realistic ALIVE-churn reduction from H1+H2+H3 ≈ **~31 GB of 79.75 GB total (~39 %)**, i.e.
FrontCoded largely neutralized as the dominant allocator, before touching the retained-blob follow-up.

## Artifacts (this session's scratchpad, not committed)
- `scratchpad/frontcoded-writepath-analysis.md` — H1 design comparison, file:line refs.
- `scratchpad/frontcoded-readpath-ascii-analysis.md` — H2 predicate verification + gate correction.
- `scratchpad/frontcoded-duplicate-cow-analysis.md` — H3 safety proof.
