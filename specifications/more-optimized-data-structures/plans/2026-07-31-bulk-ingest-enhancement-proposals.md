# Bulk ingest — enhancement proposals

Six contained optimizations for the bulk-ingest write path, ordered by **implementation ease**
(easiest first), not by payoff. Ordered this way deliberately so the reader can draw a line and take
a prefix.

## Provenance

Measured on branch `1342-sort-index-bucket-anchored-inserts` at HEAD `5f4cfc287`, ingesting the
972,611-article reference corpus (5,731 MB of source payload) through an external bulk-ingest harness
with async-profiler attached. Profiled run 6m06s vs 6m10s unprofiled, so profiler overhead is in the
noise.

Baseline: **366 s wall**, of which the ingest thread is 345 s CPU — **94% saturated, and 78% of all
process CPU on 1.18 of 24 cores.** Wall time is essentially that one thread, so nothing helps unless
it removes work *from that thread*; GC (0.8% of wall) and the transaction thread already run
concurrently and cost no wall time.

The two dominant blocks on that thread: **deflate 107 s (29%)** and **`FrontCodedStringColumn`
78 s (22% inclusive)**. Items 2–6 all target the second.

All figures are from the **WARM_UP (bulk) ingest** profile. ALIVE (transactional) was not analysed
and none of these estimates transfer to it unexamined.

Every `file:line` anchor below was re-checked against the code at that same HEAD in a later pass, which
also resolved item 5's open question and corrected three claims (item 3B's call path, item 5's return
shape, and the "items 2–6 all live in the same class" note under cross-cutting).

## Not included here

A seventh, much larger item was assessed and deferred as a separate piece of work: **moving deflate
off the ingest thread entirely** (parallel compress + strictly in-order append), worth an estimated
90–110 s. It is deferred because it is real engineering rather than a contained change — record
N+1's file offset is arithmetic on record N's *compressed* length, which forbids out-of-order
completion. See the session notes on ingest parallelism for the constraint analysis.

Also out of scope: ~7.3 s (2%) in the internal-node descent over boxed `String[]` keys
(`TransactionalBucketBPlusTree.java:2739`, searched at `:2500-2505`). No design exists for it yet —
noted so it is not mistaken for "already covered" by items 2–6.

## Status tags
- **IMPLEMENTED** — landed on this branch: items 2, 3B and 5 in commit `8b6c2a2e8`, items 1 and 3A
  in the commit that also added this document.
- **READY** — verified against the code; the change is well-defined.
- **PROPOSAL — needs own research** — the direction is sound but there is an unsettled question the
  implementing agent must answer before writing code.

## Triage — can this change lose?

The status tag says whether the change is *specified*; it does not say whether the change can make
things worse. That second axis decides what may be taken on the strength of "it does less work" alone,
with no measurement to justify it. Sorted that way, the six items are:

| tier | items | meaning |
|---|---|---|
| **cannot regress** | 2, 3B, 5 | strict subtraction of work, semantics unchanged — take without measuring |
| **simpler, gate now PASSED** | 3A | the distribution gate was real; it was measured and 3A cleared it |
| **less work, changed invariant** | 4 | buys the saving by making a flag permanently pessimistic — not free |
| **more complex** | 6 | excluded by its own terms from "do it because it is simpler" |
| **ratio-for-CPU, gate PASSED** | 1 | not a work reduction — a deliberate trade; measured and taken |

Items 2, 3B and 5 are the prefix a reader can take unconditionally — and they **have been taken**:
all three landed on `1342-sort-index-bucket-anchored-inserts` in commit `8b6c2a2e8`.

**Items 3A and 1 have since been measured and taken as well.** See "Measurement" below for 3A's
numbers and for the two errors in this document's own statement of its gate, and item 1 above for the
deflate numbers and the third error. **Items 4 and 6 remain untouched** and are as specified below —
and note that neither is the next thing worth doing: the deferred seventh item (moving deflate off the
ingest thread, 90–110 s) is worth more than every item in this document combined.

Code style is mandatory for all of these: tabs (never spaces), no `var`, explicit types, `final`
locals, `@Nonnull`/`@Nullable` on params and returns, JavaDoc in Markdown. See
`.claude/rules/code-style.md`.

---

## 1. Lower the storage deflate level — IMPLEMENTED

**Landed as a hardcoded level, deliberately NOT as configuration.** The original proposal here was to
add a `compressionLevel` component to `StorageOptions` so the trade-off would only exist for whoever
opted in. That was rejected in favour of simply changing the level, for two reasons: the measurement
below shows one level wins on every data shape tested, so there is no deployment that would want a
different value; and a knob would advertise a tuning axis whose correct value is already known while
adding a configuration surface to maintain.

**Change made.** `ZipCompressionFactory` gained a private `COMPRESSION_LEVEL = 3` and
`createCompressor()` builds `new Deflater(COMPRESSION_LEVEL, true)` instead of
`new Deflater(Deflater.DEFAULT_COMPRESSION, true)` (level 6). Nothing else moved.

**Why this is safe to hardcode.** DEFLATE level affects only the *encoder*. Every level emits a stream
the same `Inflater` reads, so catalogs written at level 6 stay readable, catalogs written at level 3
are readable by older code, and nothing migrates. This is not a format change and needs no version
bump or BWC handling.

**Measure it per RECORD, not as a stream.** `ObservableOutput` does `reset()` / `setInput(buffer,
payloadStart, payloadLength)` / `finish()` and a single `deflate()` per storage record, so the
dictionary never carries across records. Benchmarking the payload as one long stream measures a
system that does not exist here.

Level 3 against level 6, on real serialized entity records and on prose (standing in for text-heavy
attribute bodies), at three record sizes:

| data | 4 KB | 16 KB | 64 KB |
|---|---|---|---|
| serialized entity records | +1.4 % size, 1.46x faster | +2.4 %, 1.65x | +3.2 %, 1.79x |
| prose | +4.5 %, 1.31x | +7.6 %, 1.68x | +11.2 %, 2.35x |

**Level 1 was rejected** even though it is faster still (up to 3.1x): it costs **+20.4 %** on 64 KB
prose-like records, and text-heavy bodies are exactly the case this change exists to help. Level 9 is
strictly bad — 1–6 % size for 1.3x to 5.5x *slower*.

**The "3–5× less CPU for 10–15 % worse ratio" figure quoted in the original proposal is zlib folklore
and is wrong here.** Level 3 buys 1.3–2.4x, not 3–5x; level 1 reaches 3.1x only where it also breaches
the size budget. The direction was right, the magnitude was not.

**What the measurement does NOT cover — read this before quoting an end-to-end number.**

- **Compression CPU only.** `Inflater` was never run. A lower level means larger payloads, so every
  future *read* inflates more bytes; that cost is unmeasured.
- **No I/O.** +11 % payload is +11 % bytes written and read. If any phase is I/O-bound rather than
  deflate-CPU-bound, the win shrinks or inverts.
- **Fixed 4/16/64 KB chunks**, not the real variable record-size distribution — and the trade improves
  with record size, so the chunk choice partly determines the answer.
- **Bracketed data, not the reference corpus** — real serialized records plus prose, because the corpus
  is not available on the measuring machine.
- **Hand-rolled `nanoTime` loop, not JMH.** A level-6-vs-level-6 control in the same run came out at
  0.98x–1.06x, so run-to-run noise is roughly ±6 %. Size deltas are deterministic and solid; the
  speed *direction* is far outside noise, but individual speedup figures are worth ±6 % at best.

Deflate is 107 s of the 366 s baseline, so a ~1.5x compression speedup extrapolates to roughly 36 s
(~10 % of wall) — **extrapolated, not measured**. An end-to-end ingest is the experiment that would
settle it, and it is the outstanding gap on this item.

---

## 2. Remove the duplicate scratch-buffer lookup — IMPLEMENTED

**What.** Every mutator fetches the thread-local decode scratch, then calls `encode(...)`, which
fetches the *same* thread-local again:

| mutator | first `SCRATCH.get()` | `encode` refetches at |
|---|---|---|
| `insertKeyAt` | `:327` | `:847` |
| `removeKeyAt` | `:363` | `:847` |
| `copyRangeTo` | `:392` | `:847` |

**Change.** Add a `@Nonnull DecodeScratch scratch` parameter to
`encode(byte[] flat, int[] offsets, int n)` (`:841`) and pass the caller's instance. The cold
`encode(byte[][], int)` adapter (`:826`) must fetch scratch itself and pass it through.

**Problematic parts / risks**

- Genuinely near-zero. It is a strict subtraction of work — no behaviour changes.
- The only wrinkle: `encode` also calls `acquireEncodeBuf(scratch, n)` (`:848`) and writes the grown
  buffer back through `finishEncode`. Passing the same instance the caller already holds is exactly
  what the existing code does after two lookups, so the aliasing is unchanged.
- Payoff is small (1–2 s). Worth doing because it is free, not because it matters on its own.

---

## 3. Vectorized byte comparisons — change B IMPLEMENTED, change A open

**What.** Two hand-written per-byte loops on the hot path can each be replaced by a single JDK
intrinsic. There is precedent in this repository at `Art.java:374`.

**Change A — `commonPrefix` (`:968-975`)**

```java
final int m = Arrays.mismatch(arr, aStart, aStart + aLen, arr, bStart, bStart + bLen);
return m < 0 ? Math.min(aLen, bLen) : m;
```

Two things the implementing agent will get wrong if not told:
- **The same array is passed twice.** This is legal and intended — both ranges live in one flat
  buffer. Do not "fix" it into two arrays.
- **`Arrays.mismatch` returns a RELATIVE index**, measured from the start of the ranges, not an
  absolute offset into `arr`. And it returns `-1` (not the common length) when no mismatch is found
  within the shorter range — hence the normalization above. `Art.java`'s use of it does **not** carry
  the same normalization, so it is not a copy-paste source.

**Change B — `compareUnsignedBytes` (`:1036-1045`)**

The whole method body collapses to:

```java
return Arrays.compareUnsigned(a, 0, aLen, b, 0, bLen);
```

The JDK's documented behaviour when one range is a prefix of the other is to return the difference of
the range lengths — which is exactly the current `return aLen - bLen` at `:1044`. And on a mismatch it
returns `Byte.compareUnsigned(...)`, matching the current `(a[i] & 0xFF) - (b[i] & 0xFF)`. This is a
true drop-in; only the sign matters to callers, and both agree on sign everywhere.

**Problematic parts / risks**

- **The real gate is the prefix-length distribution, not whether the intrinsic fires.**
  `-XX:+PrintIntrinsics` proves it compiled, not that it won. Below roughly 8 shared bytes the
  intrinsic's setup can lose to a scalar loop, and front-coded attribute values may well share short
  prefixes — restart points reset the shared prefix to 0 every 16 entries by construction
  (`RESTART_INTERVAL = 16`, `:121`), so a meaningful fraction of calls compare *short* runs.
  **Instrument the returned lengths on a real ingest first, or JMH both implementations at the
  observed distribution.** If the distribution is dominated by short prefixes, change A may be a wash
  and only change B is worth taking.
- **Change B is not read-only — it is on the write path too, which is the stronger half of its case.**
  `findKeyPosition` is called from six sites in `TransactionalBucketBPlusTree`
  (`:4570`, `:4584`, `:4620`, `:4633`, `:4715`, `:4729`), i.e. from *every* leaf insert, each paying a
  binary search of roughly `log2(VALUE_BLOCK_SIZE) ≈ 8` comparisons. Change A is on the write path only
  (`encode`, `:863`). They have independent value; they can land separately.
- **Change B does nothing for collated attributes.** `compareUnsignedBytes` is reached only when the
  BMP-safe byte fast path is armed, which requires `ValueColumnFactory.isNaturalOrder(comparator)`
  (`ValueColumnFactory.java:143-145` — `comparator == null || comparator == Comparator.naturalOrder()`).
  A localized attribute carries a collator, so it takes the `String` path and is unaffected. The win is
  real but confined to natural-order columns.

---

## 4. Incremental BMP-safe flag — PROPOSAL, needs own research

**What.** `encode` recomputes `bmpSafe` from scratch on every call by scanning suffix bytes
(`:870-872`). Inserting one key re-scans the corpus. For an insert only one key is new, so in
principle `newFlag = oldFlag && isBmpSafe(newKeyBytes)`.

**The scan is already short-circuited** (`if (bmpSafe) { bmpSafe = isBmpSafe(...); }` at `:870`), so
once the flag goes false the scan stops for the remainder of that encode. The measured 9.4 s is
therefore paid **only by fully-BMP corpora** — which is the common case, so the win is real, but it
means the change buys nothing for columns that already contain a supplementary character.

**Why the failure direction is safe.** The flag only gates a fast path (`:484-487`): a stale `false`
costs speed, a stale `true` is a correctness bug. A conservative (over-pessimistic) flag is sound.

**Unsettled questions the implementing agent must answer**

1. **The one-way latch.** `removeKeyAt` / `clearAt` / `fillEmpty` / `copyRangeTo` cannot cheaply know
   that the last supplementary key just left. So a column that *ever* held one permanently loses the
   fast path — a lasting degradation on a long-lived leaf, not a transient one. Is that acceptable, or
   does the removal path need a periodic re-derivation (e.g. re-derive on the next full re-encode
   that happens anyway)? Decide this before writing code.
2. **`encode` is the single funnel for every mutator** — `insertKeyAt`, `removeKeyAt`, `copyRangeTo`,
   `clearAt`, `fillEmpty`, and `bulkLoad` all route through it. Today it *derives* the flag, which is
   why it cannot be wrong. Making it *accept* a precomputed flag means a future mutator can silently
   inherit a stale one. The proposal must say **how that contract is enforced**, not merely that
   incremental is sound for inserts. This codebase has already had to hard-code a mutator contract
   once in prose — see the `duplicate()` comment at `:303-307`, which warns that structural sharing
   is only sound while every mutator replaces by whole reference. Do not add a second unenforced one.
   Suggested shape: keep the deriving overload as the default and add an explicit
   `encode(..., boolean knownBmpSafe)` that only `insertKeyAt` may call.
3. **Use the byte scan, not the String scan.** `isBmpSafe(byte[], int, int)` (`:991`) on the new key's
   UTF-8 bytes — *not* `isBmpSafe(String)` (`:1015`). The two deliberately disagree on lone
   surrogates; the JavaDoc at `:1000-1010` explains exactly why, and picking the wrong one
   reintroduces the bug that JavaDoc exists to prevent.

**This one is not free, and does not qualify as "take it because it does less work".** The other two
items in that class (2 and 3B) are strict subtractions; this one buys its saving by *changing an
invariant*. The latch above is a genuine behaviour regression for any column that ever held a
supplementary character, and accepting a precomputed flag turns `encode` from "derives it, so it cannot
be wrong" into "trusts its caller". Weigh it on measured numbers, not on the principle.

**Cheaper alternative worth pricing first.** Keep `encode` as the single deriving funnel — no latch, no
new contract — and make the derivation itself cheaper instead: `isBmpSafe(byte[], int, int)` (`:991`)
is a per-byte `>= 0xF0` test over the suffix bytes and can be done word-at-a-time. Same win category,
none of the design risk above. It needs its own correctness test (the byte-scan / `String`-scan
disagreement at `:1000-1010` must survive verbatim), so it is not free either — but it is a much
smaller thing to get wrong.

**Coupling.** Item 6 removes the derivation entirely (see below). If both are taken, item 4 must land
first or they must land together.

**How to verify.** A randomized-mutation property test that re-derives the flag from scratch after
each step and asserts the incremental flag is never *more permissive* than the derived one. Equality
is the wrong assertion — the latch makes them legitimately diverge.

---

## 5. Skip boundary decoding on insert — IMPLEMENTED

**What.** `assertInsertBoundaries` (`TransactionalBucketBPlusTree.java:1850-1859`) runs on every
new-bucket insert and does this:

```java
final int peek = leaf.getPeek();
if (compareKeys(key, leaf.keyAt(peek), this.comparator) == 0) { assertTailBoundary(cursor, key); }
if (compareKeys(key, leaf.keyAt(0), this.comparator) == 0) { assertHeadBoundary(cursor, key); }
```

Two full front-coded decodes, two `String` materializations, and two comparator invocations — on
localized attributes each comparator call is a collator call — purely to ask *"was this a tail
insert?"* and *"was this a head insert?"*. Both questions are answered by an integer.

The insertion index is already computed one frame down: `BPlusLeafTreeNode.addRecord` calls
`findKeyPosition(...)` and uses `insertionPosition.position()` at `:4569` (non-transactional branch)
and `:4583` (transactional branch). It is simply not propagated back to the caller.

**Change.** Propagate the insertion index out of the leaf add methods, and replace the two key
comparisons with `position == peek` and `position == 0`.

**What is actually saved, and what stays.** The saving is the *guard*, not the asserts — the
boundary validation itself is untouched. That matters because the two retained bodies are cheaper than
the guard that selects them: `assertTailBoundary` (`:1637`) is pure index arithmetic over the cursor
arrays plus **one** `compareKeys` against an already-boxed internal separator (no decode at all), and
`assertHeadBoundary` (`:1671`) is one `keyAt()` decode of the *predecessor* leaf plus one
`compareKeys`, and fires only on head inserts. Bulk sequential ingest is almost entirely tail inserts,
so the retained cost is one comparator call while the removed guard is two decodes and two comparator
calls paid **unconditionally, on every new-bucket insert**.

### Coordinate-space proof

The position index and the post-insert `peek` are in the same coordinate space in **both** branches:

- **Non-transactional.** The position comes from `this.keys.findKeyPosition` (`:4570`); the insert runs
  through `insertNewSingleBucket` / `insertNewBucket` (`:4867-4874`, `:4884-4898`), which do
  `this.keys.insertKeyAt(position, value)` and `this.peek++`. The reader accessors `getPeek()`
  (`:3688`) and `keyAt()` (`:3666`, via `getKeyColumn()` `:3676`) resolve to `this.*` because
  `getTransactionalMemoryLayerIfExists` returns `null`. Same column, same counter.
- **Transactional.** `decoupleTransactionalArrays()` (`:4962`) runs **before** the position is
  computed, and it is the call that may replace `layer.keys` (with `this.keys.duplicate()`). So the
  position is computed on the *post*-decouple column (`:4584`), and `layer.insertNewSingleBucket`
  mutates that same column. The layer **instance** is never replaced — `decoupleTransactionalArrays`
  re-fetches it through the idempotent `getOrCreateTransactionalMemoryLayer` and swaps only the column
  references — so the later `getPeek()` / `getKeyColumn()` resolve the same layer. Same column, same
  counter.

**Why `position` and the key comparison are exactly equivalent** (this is the step that survives a
collator, and the reason an ASCII-only reading of it is not good enough): no two keys in a leaf can be
*comparator*-equal, because `findKeyPosition` returns `alreadyPresent()` on `cmp == 0` under that same
comparator, and the `alreadyPresent` branch inserts no new bucket and therefore never reaches
`assertInsertBoundaries`. The comparator is literally the same object on both sides — every
`BPlusLeafTreeNode` construction site (`:642`, `:690`, `:846`, `:2171`, `:2281`, `:2297`, `:4372`,
`:4506`, `:4515`, `:4527`) passes the tree's own `comparator`. Hence `position == 0` ⟺ the key is
`keyAt(0)`, and `position == postPeek` ⟺ the key is `keyAt(peek)` — exact, including a
primary-strength collator under which e.g. `a` and `á` compare equal.

**The 0→1 transition still triggers both branches**, as the JavaDoc at `:1841-1845` requires: an empty
leaf has `peek == -1`, so `findKeyPosition(value, 0, 0, ...)` returns `0`, and the post-insert `peek`
is `0` — satisfying `position == 0` and `position == peek` simultaneously.

**The signature change is uniform and contained.** All three leaf methods that compute the position
already return `boolean`: `addRecord` (`:4553`), `addRecords` (`:4699`) and `addLongRecord` (`:4604`)
— it is the *outer* `addLongRecord` (`:1000`) that returns `void`, and it simply discards the leaf
method's result today. Those three leaf methods have exactly three callers, all in this file
(`:863`, `:892`, `:1004`). So the change is `boolean` → `int` on three methods with a `-1` sentinel for
"no new bucket" — no `InsertionOutcome` record is needed.

**Remaining risk — test coverage, not design.** The largest saving is on collated attributes, where a
mistake is least likely to be caught: an ASCII-only test exercises the natural-order path and passes
regardless. Tests must cover localized attributes explicitly.

**How to verify.** Keep the existing decode-based check available behind an assertion flag and
cross-check that the index-based and key-based answers agree over a randomized insert workload that
includes localized attributes, duplicate keys, and inserts into empty leaves (the 0→1 transition,
which the JavaDoc at `:1841-1845` says must trigger *both* branches).

---

## 6. Re-encode only from the enclosing restart point — PROPOSAL, needs own research

**What.** Inserting one key currently decodes all live entries to a flat buffer, splices in the new
one, and re-encodes *everything* (`insertKeyAt:326-347` → `encode:841-878`). With
`VALUE_BLOCK_SIZE = 256` in the inverted index (`InvertedIndex.java:130`), that is up to 256 entries
re-encoded per single insert. Front-coding stores a full key at every 16th entry
(`RESTART_INTERVAL = 16`, `:121`) and each entry depends only on its immediate predecessor, so blocks
before the insert point are byte-identical and could be memcpy'd verbatim.

**The catch that halves the payoff.** Inserting one entry shifts every subsequent entry's *index* by
one, so entry 16 (a restart point, stored in full) becomes entry 17 (a delta) and so on down the
line. Every downstream block's **encoding** changes, not merely its position. So "memcpy the tail" is
wrong. The realistic scope is: memcpy the blocks *before* the enclosing restart point, re-encode from
that restart point to the end. That averages a ~50% saving, not ~94% — which is why the estimate is
~5 s and not ~20 s. Confirm this reasoning before budgeting the work; if it holds, the item is worth
noticeably less than a naive reading suggests.

**Unsettled questions the implementing agent must answer**

1. Is the ~50% average saving worth the complexity, given the class currently has a single, simple,
   obviously-correct encode path? This is a real question, not a formality.
2. **It breaks `bmpSafe` derivation.** Memcpy'd bytes are never scanned, so `encode` can no longer
   derive the flag at `:870-872`. Item 6 without item 4 leaves the flag wrong — and wrong in the
   *unsafe* direction (stale `true`), which is a correctness bug, not a slowdown. Item 4 first, or
   both together.
3. **The MVCC contract must survive.** `data` and `restartOffsets` are structurally shared across
   transaction layers by `duplicate()` (`:302-315`) and are sound *only* because every mutator
   replaces them by whole reference. Building into `scratch.encodeBuf` and copying out through
   `finishEncode` preserves that. An "optimization" that edits `data` in place to reuse slack would
   silently corrupt other transaction layers — the comment at `:303-307` says exactly this and it is
   the single most dangerous mistake available in this file.
4. The restart index must be rebuilt consistently — its length is `ceil(n / 16)` (`:900`), so an
   insert can change the table's length, not just its contents.

**How to verify.** A property test: for random key sets and random insert positions, assert the
incrementally-encoded blob is **byte-identical** to a full `encode()` of the same logical content.
Byte-identity, not just decode-equivalence — the format must not fork.

---

## Measurement — what harness exists, what is left to build

None of these should be re-measured with the 6-minute bulk ingest; it is far too coarse to attribute a
1–5 s change. What the repository already has, and what it does not:

**DONE — item 3A measured, gate passed, change landed.** `FrontCodedFindKeyBenchmark` gained four
`@Benchmark` methods: `commonPrefix_scalar` / `commonPrefix_intrinsic` and
`findKey_byteCompareIntrinsic_hit` / `_miss`.

Two corrections to this document's own statement of the gate, both found while building the harness:

1. **"Adding two variants settles change A" was wrong.** `commonPrefix` has exactly one caller —
   `encode` — which runs inside `@Setup`, so it was never on any measured path. Change A needed a *new*
   measured path: `encode` now hands back its flat key buffer and offsets, and the benchmark replays the
   exact `(aStart, aLen, bStart, bLen)` quads it produced.
2. **The stated rationale for the gate describes a mechanism that does not exist.** The claim was that
   restart points "reset the shared prefix to 0 every 16 entries, so a meaningful fraction of calls
   compare short runs". A restart entry assigns `shared = 0` **without calling `commonPrefix` at all**
   (`FrontCodedStringColumn.java:861-865`). Restarts contribute no short calls — they contribute none.
   Every call compares two *adjacent* keys.

Measured on **JDK 17** (the toolchain JDK; an earlier JDK 21 run agreed), ns per `commonPrefix` call:

Ranges span the three `leafSize` settings (16 / 48 / 64); the per-call cost is a property of the call,
not of the leaf, so the three agree closely and a range is more honest than picking one cell:

| shape | mean shared bytes | scalar | intrinsic | speedup |
|---|---|---|---|---|
| `CODE` | 6.9 | 4.56 – 5.35 | 3.38 – 3.62 | 1.26 – 1.58× |
| `EAN13` | 11.9 | 5.74 – 7.21 | 3.63 – 4.33 | 1.58 – 1.76× |
| `ACCENTED` | 24.9 | 9.55 – 9.82 | 4.30 – 4.61 | 2.13 – 2.27× |
| `URL` | 30.9 | 10.98 – 11.42 | 3.86 – 4.10 | 2.78 – 2.95× |

One cell is excluded as noise and should not be quoted: `ACCENTED` / `leafSize=64` reported the
intrinsic at 383.13 ns **± 213.11** (a 56 % error bar), which would read as 1.50× against a scalar
measured to ±21.49 in the same run. The other two `ACCENTED` cells have tight errors and agree.

**The intrinsic is flat in prefix length; the scalar loop is linear in it.** That is the finding. The
gate's worry was that short prefixes would make the intrinsic a wash below ~8 shared bytes — `CODE`
sits at 6.9, entirely in the 4–7 bucket, and still wins 1.33×. Winning at both ends of the range means
a mixed real distribution wins too. Change A is landed in `FrontCodedStringColumn.commonPrefix`.

Scope the number honestly: the benchmark loops uniform-length compares over one buffer, while `encode`
calls `commonPrefix` once per non-restart entry interleaved with `writeVarInt` / `System.arraycopy`.
So 2.95× is the ceiling of the primitive in isolation, **not** an `encode` speedup.

**Change B (3B) cannot be confirmed by this harness** — `findKey_byteCompare*` versus its intrinsic twin
is mixed with every error bar overlapping, because the compare is a small part of a loop dominated by
the restart-walk decode and a per-call `probe.getBytes()`. 3B stands on its "cannot regress" triage,
which is how it was taken.

`@Setup` prints the shared-prefix min / mean / max and a `0-3 / 4-7 / 8-15 / 16-31 / 32+` histogram per
fixture; read it together with the ns/op, never alone.

**Exists but does not cover any of this** — do not mistake these for coverage:
- `FrontCodedSerializationBenchmark` — serialize / deserialize only; it never calls `insertKeyAt`.
- `BucketBPlusTreePayloadBenchmark.mutate` — keyed by `Integer`, so it never constructs a
  `FrontCodedStringColumn` at all.
- `SortIndexTimingBenchmark.insertRecord` and `SortAttributeIngestBenchmark.warmUpIngest` are the
  closest end-to-end ingest harnesses, but **neither uses a `Locale` or a collator**. For item 5 that is
  the wrong instrument, since its largest saving is exactly the collated case.

**Left to build:**
1. **A `FrontCodedStringColumn` insert-path micro-benchmark** — `insertKeyAt` into a column near
   `VALUE_BLOCK_SIZE` (~256 entries), `@Param` over key shape. Items 2, 4 and 6 have *no* harness
   today; this one covers all three. It is also the natural place to instrument the `commonPrefix`
   return-length histogram that item 3A's gate wants.
2. **A collated variant of the sort-index insert benchmark** for item 5 — either a `@Param` locale on
   `SortIndexTimingBenchmark.insertRecord` or a localized attribute in
   `SortAttributeIngestBenchmarkState`. Before building it, confirm the state produces a *paged*
   (B+-tree-backed) sort index — with too few distinct values it stays on the array-backed form and
   never enters `TransactionalBucketBPlusTree`, which would stack a second wrong instrument on the
   first.
3. **Item 1 is not a JMH job** — sample real `body` bytes from the corpus and measure deflate
   throughput *and* output size at levels 1, 3 and 6 standalone, as that item already says.

## Cross-cutting notes

- **Items 2, 3, 4 and 6 live in the same class and their gains overlap.** The individual figures sum to
  ~24 s; budget **15–20 s** combined until measured. Each one shrinks the work the next one does.
  **Item 5 is the exception** — its change site is `TransactionalBucketBPlusTree`, and the cost it
  removes lands in `FrontCodedStringColumn`'s *decode* path, while items 4 and 6 target the *encode*
  path. So item 5's saving is largely additive to theirs rather than overlapping, and the 15–20 s
  discount should not be applied to it.
- **Item 6 reduces item 3's win** (fewer `commonPrefix` calls). If item 6 lands, re-measure before
  investing in change A of item 3.
- **Item 4 becomes more valuable if compression moves off-thread** (the deferred item 7):
  `FrontCodedStringColumn` becomes the single largest remaining cost (~32%) once deflate is gone.
- **Do not tune `RESTART_INTERVAL` (`:121`) on a CPU number alone.** A restart entry stores its key in
  full, so halving the interval doubles the unshared-key population and hands back the ~10× retained-
  heap win that justifies this class existing (class JavaDoc `:48-51`).
- **Not covered here:** ~7.3 s (2%) in `Arrays.binarySearch0` is the internal-node descent over boxed
  `String[]` keys (`TransactionalBucketBPlusTree.java:2739`, searched at `:2500-2505`), not this
  class. No design exists for it yet.
