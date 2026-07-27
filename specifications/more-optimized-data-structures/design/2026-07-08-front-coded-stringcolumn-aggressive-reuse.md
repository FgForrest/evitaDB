# FrontCodedStringColumn allocation reduction — SAFE (ship now) + AGGRESSIVE (deferred)

Issue: #760 (ALIVE churn refinement of the front-coded string column). Not a correctness gap —
the column is already correct; this targets the dominant allocation hotspot on the unique/ALIVE
warmup path.

Status: **SAFE variant = SHIP (this revision implements it).** AGGRESSIVE variant = DESIGN,
deferred behind the OffsetIndex `long[]` fix. Author dialogue: Johnny + Claude. Measurement
context is cited from prior async-profiler runs, not re-derived here.

This revision rewrites the earlier "aggressive-only" plan to: (a) split the work into a low-risk
SAFE step and a high-risk/low-payoff AGGRESSIVE step; (b) fold in the design corrections D1–D4
found in review; (c) add an allocation budget with a 32-thread footprint; (d) settle the scratch
pooling question (ThreadLocal vs Kryo `Pool`); (e) expand the test gate.

## 1. Problem & scope

`FrontCodedStringColumn` (`evita_engine/src/main/java/io/evitadb/index/bPlusTree/FrontCodedStringColumn.java`)
is a `ValueColumn<String>` backing String-keyed B+tree leaves — most importantly the
GlobalUniqueIndex `url→pk` tree. It stores each leaf's distinct values as a single front-coded
`byte[]` blob (Lucene term-dictionary layout: `varint(shared) varint(suffix) suffixBytes`, a
restart point every `RESTART_INTERVAL = 16` entries). Two paths allocate transient scratch:

- the **search/read path** — `decodeAt` (via `findKeyPosition` / `keyAt` / `appendKey`) allocates
  a `byte[]` decode buffer per call;
- the **mutation path** — every slot mutator (`insertKeyAt` / `removeKeyAt` / `copyRangeTo` /
  `clearAt` / `fillEmpty`) does a full `decodeAllBytes()` into a fresh `byte[size][]` (one
  `byte[]` per entry) + a full re-`encode`.

**Non-goals (both variants).** No change to the on-disk format (the front-coded blob layout is
unchanged), no change to `RESTART_INTERVAL`, no change to the `ValueColumn` interface, no change
to any other column kind, no `serialVersionUID` bump (intra-dev format is byte-identical; consistent
with the bwc policy — bumps only across released minors 2024.11 / 2025.5 / 2026.1).

## 2. The two allocation paths (the framing that drives the split)

The two paths differ by **frequency**, and that decides which fix pays off in wall-clock:

| Path | Frequency | Pre-fix per-call alloc | SAFE removes | AGGRESSIVE additionally removes |
|---|---|---|---|---|
| **Search/read** (`decodeAt`) | **very high** — every binary-search hop of every mutation | `new byte[48]` + `new String` | the `byte[48]` (ThreadLocal `cur`); `String` stays (needed to compare) | nothing further |
| **Mutation** (`decodeAllBytes` + `encode`) | **low** — only on real structural change | `byte[size][]` shell + `size` per-entry `byte[]` + encode buf | the encode buf (ThreadLocal `encodeBuf`); `decodeAllBytes` untouched | `decodeAllBytes` entirely (packed arena) |

**Key insight.** The measured 3.31× wall-clock win comes almost entirely from the **search path**
— eliminating a small allocation that fires enormously often (young-gen pressure that was driving
the 31 % GC). The mutation-path `decodeAllBytes` churn is **large in volume but infrequent and
young-gen-cheap** — removing it drops ~19 GB of total garbage on the profiled run yet moves
wall-clock ~0, because that garbage dies in Eden immediately and the real stall is now `OffsetIndex`
`long[]` allocations (`OffsetLocationChampMap` + `copySnapshotTo`, ~50 % of allocs post-SAFE).

**Allocation volume ≠ wall-clock** when the allocations are ultra-short-lived. That is why SAFE
ships and AGGRESSIVE waits.

## 3. SAFE variant — thread-local `cur` + `encodeBuf` (SHIP)

### 3.1 DecodeScratch (thread-local, two buffers)

```java
private static final class DecodeScratch {
    byte[] cur = new byte[DECODE_SCRATCH_BYTES]; // per-hop decode buffer, reused across hops AND calls
    byte[] encodeBuf = EMPTY_BYTES;              // encode buffer, trimmed into a fresh data blob each encode
}
private static final ThreadLocal<DecodeScratch> SCRATCH = ThreadLocal.withInitial(DecodeScratch::new);
```

Both buffers grow on demand (doubling) and are reused across calls, never shrunk. One holder per
thread.

### 3.2 Changes

- **`decodeAt`** — borrow `SCRATCH.get().cur` instead of `new byte[DECODE_SCRATCH_BYTES]`; grow on
  demand; write the (possibly grown) buffer back. Returns `new String(cur, 0, curLen, UTF_8)`
  (unchanged — the `String` copy is what makes the reuse safe).
- **`decodeAllBytes`** — borrow the same `cur` for its per-entry scratch; each `out[i]` stays a
  fresh `Arrays.copyOf`, so nothing thread-local escapes; write the buffer back.
- **`encode`** — borrow `SCRATCH.get().encodeBuf` (pre-sized via `ensureCapacity`, grown in-loop);
  write it back; then **always** `this.data = Arrays.copyOf(buf, len)`.

### 3.3 The load-bearing invariant

`encode` MUST always copy `encodeBuf` into a fresh trimmed `data` — it must **never** adopt the
scratch buffer into `data` (the pre-fix code did `len == buf.length ? buf : copyOf` because `buf`
was freshly allocated; now `buf` is shared thread-local scratch, so adopting it would alias
retained column state to the scratch and the next `encode` on the thread would corrupt `data`).
This is the single invariant that keeps the reuse MVCC-safe. See §7.

### 3.4 What does NOT change in SAFE

The mutators still call `decodeAllBytes` → `byte[size][]` + `size` per-entry `byte[]`. SAFE leaves
that mutation-path volume intact (that is AGGRESSIVE's job). `duplicate()`, `copyRangeTo` slot
logic, restart layout, on-disk format — all unchanged.

## 4. AGGRESSIVE variant — packed arena + (offset, length) int pointers (DEFERRED)

Extends `DecodeScratch` with a packed byte arena and two `int[]` pointer arrays; every mutator
`decodeAllIntoArena()` once, rearranges **integer pointers** (never bytes) via `System.arraycopy`,
then `encodeFromArena(n)`. This removes the per-entry `byte[]` explosion that SAFE leaves behind.

### 4.1 DecodeScratch (aggressive fields)

```java
private static final class DecodeScratch {
    byte[] cur;        // SAFE fix's per-hop decode scratch (retained)
    byte[] encodeBuf;  // SAFE fix's encode scratch (retained)
    byte[] arena;      // packed raw UTF-8 bytes of all live entries, densely packed
    int[]  offsets;    // arena byte-offset of each entry — GROWS ON DEMAND (see D1)
    int[]  lengths;    // byte-length of each entry — GROWS ON DEMAND (see D1)
    int    arenaLen;   // live byte length of arena (reset to 0 by decodeAllIntoArena)
}
```

### 4.2 decodeAllIntoArena

Replaces `decodeAllBytes`. Resets `arenaLen = 0`, walks all `size` entries sequentially from
`this.data` (it does **not** need `restartOffsets` — that is only for random access), copying each
entry's shared prefix from the predecessor's arena slot and its suffix from `this.data`, recording
`(offset, length)`. The in-arena prefix copy `System.arraycopy(arena, prevOff, arena, arenaLen,
shared)` is provably non-overlapping: dense packing gives `prevOff + prevLen == arenaLen` and the
front-coding invariant gives `shared ≤ prevLen`, so `src.end = prevOff+shared ≤ arenaLen =
dst.start`. Assert `shared ≤ prevLen` per entry (corrupt-blob guard, constant message).

### 4.3 Mutators — shift integers

Each mutator `decodeAllIntoArena()`, rearranges `offsets[]`/`lengths[]` via `System.arraycopy` on
the `int[]`, then `encodeFromArena(n)`. The removed/overwritten arena bytes stay as dead space
(overwritten on the next decode).

### 4.4 encodeFromArena

Replaces `encode(byte[][], int)`. Walks the first `n` entries in pointer order, computes the shared
prefix between consecutive entries via the region `commonPrefix` (§4.6), writes the blob into the
borrowed `encodeBuf`, then **always** `this.data = Arrays.copyOf(buf, len)` (same invariant as SAFE
§3.3 — no adopt-vs-copy ternary; see D3).

### 4.5 copyRangeTo

- **`dst == this`** (hot steal/merge rebalance): `decodeAllIntoArena()` **first**, *then* snapshot
  the source range's `(offset, length)` pairs into temp `int[]`, rearrange the pointer arrays,
  splice the snapshot into `[dstPos, dstPos+length)`, fill any gap `[target.size, dstPos)` with
  `(0, 0)` placeholders, `encodeFromArena(newSize)`. Only integer pointers move; arena bytes are
  stable. (Ordering is load-bearing — see D2.)
- **`dst != this`**: source `decodeAllIntoArena()`, materialize the slice as a fresh
  `byte[length][]` (small — `length`, not `size`), then destination `decodeAllIntoArena()` into the
  same thread-local scratch (safe because the slice is now independent), splice, `encodeFromArena`.

### 4.6 commonPrefix (region version)

6-arg `commonPrefix(byte[] a, int aOff, int aLen, byte[] b, int bOff, int bLen)` — needed because
`encodeFromArena` compares two slots within one arena array. Replaces the 2-arg version.

### 4.7 Design corrections applied in this revision (vs the deleted prototype)

- **D1 — pointer arrays grow on demand.** `offsets`/`lengths` are **NOT** fixed at `capacity`.
  `insertKeyAt` transiently holds `size + 1` entries (a leaf sits at `size == capacity` right
  before the split that the insert triggers), and `copyRangeTo` computes `newSize =
  max(target.size, dstPos + length)` which the current boxed path already supports via
  `Arrays.copyOf(dstAll, newSize)` — both exceed `capacity`. Fixed-capacity arrays → AIOOBE. Grow
  by doubling to at least `size + 1` / `newSize`.
- **D2 — decode before snapshot.** In `copyRangeTo(dst == this)`, `decodeAllIntoArena()` must run
  **before** the source `(offset, length)` pairs are snapshotted — the pointer arrays are shared
  thread-local scratch and hold a *different column's* pointers until this column decodes. Snapshot
  first = read foreign garbage = silent corruption.
- **D3 — no adopt-vs-copy ternary.** `encodeFromArena` (and SAFE `encode`) must be
  `this.data = Arrays.copyOf(buf, len)` unconditionally. The scratch buffer can never be adopted.
- **D4 — "zero-alloc" is per-`byte[]` only.** The self-copy still allocates the temp `int[]`
  snapshot, and every mutation still allocates `restarts` (`int[]`) + the `data` trim copy. That
  ~3.2 KB/mutation floor is intrinsic to re-materializing a front-coded blob and is present in
  **both** variants (§5).

## 5. Allocation budget & 32-thread footprint

Analytic estimates grounded in the profiled shares (FC alloc share 20.7 % → 6.3 % post-aggressive),
using production-ish leaf params **B = 256 entries/leaf, avg key L ≈ 40 B**, front-coded blob
≈ 12 B/entry. Constants are tunable; the shape holds.

### 5.1 Per-operation transient (young-gen) allocation

Search hop (dominant frequency):

| | per hop |
|---|--:|
| Pre-fix | `byte[48]` (~64 B) + `String` (~56 B) ≈ **120 B** |
| SAFE / AGGRESSIVE | `String` only ≈ **56 B** (~2× less) |

Mutation (`insertKeyAt`, size 256):

| component | SAFE | AGGRESSIVE |
|---|--:|--:|
| `decodeAllBytes` shell `byte[256][]` | ~1.0 KB | — |
| 256 × per-entry `byte[]` (16+40) | ~14.3 KB | — |
| grown shell + new-key `getBytes` | ~1.1 KB | ~0.06 KB (`getBytes` only) |
| `restarts` `int[16]` | ~0.08 KB | ~0.08 KB |
| `this.data` trim copy (retained, unavoidable) | ~3.1 KB | ~3.1 KB |
| **≈ total per mutation** | **~19.7 KB** | **~3.2 KB** |
| `copyRangeTo(self)` variant | ~30+ KB | ~5.2 KB (temp `int[]` snapshot) |

Mutation churn: **~20 KB → ~3–5 KB (~85 % less)**. The **~3.2 KB/mutation floor** (`data` +
`restarts`) is intrinsic and crossed by neither variant — going lower needs in-place blob editing
(out of scope).

### 5.2 Retained ThreadLocal footprint (high-water, never shrunk) — 32-thread tx pool

| per thread | SAFE | AGGRESSIVE |
|---|--:|--:|
| `cur` (longest key seen) | ~0.25 KB | ~0.25 KB |
| `encodeBuf` (largest blob) | ~3–4 KB | ~3–4 KB |
| `arena` (largest leaf raw bytes, 256×40) | — | ~10 KB (≤ ~20 KB long-key) |
| `offsets` + `lengths` `int[256]` ×2 | — | ~2 KB |
| **per thread** | **~4 KB** | **~16 KB** (≤ ~26 KB) |
| **× 32 threads** | **~128 KB** | **~512 KB** (≤ ~832 KB) |

Both are **negligible** against a multi-GB heap. AGGRESSIVE retains ~4× SAFE and is sticky
(high-water per thread), but sub-megabyte either way — not a reason to reject it, just the honest
number.

## 6. Scratch pooling — ThreadLocal vs Kryo `Pool` vs combined

**Decision: `ThreadLocal` for both variants. Do not use `com.esotericsoftware.kryo.util.Pool`.**

1. **Usage is strictly stack-scoped and non-reentrant.** The scratch is acquired at the top of a
   mutator and dead by return; nothing hands it across method boundaries; there is no nested
   `FrontCodedStringColumn` mutation on the same thread (even `copyRangeTo(dst != this)` uses the
   arena twice *sequentially* within one call, after extracting the slice to fresh arrays). This is
   the textbook `ThreadLocal` shape, not the `Pool` shape — `Pool` earns its keep when an object's
   lifetime crosses call boundaries or construction is expensive (Kryo instances). Neither applies.
2. **`Pool` forces `obtain()`/`free()` discipline.** Every mutator would need `try/finally` to
   return the object; miss a `free()` on any path (including the `CORRUPT_BLOB` throw) and it leaks
   from the pool, degrading to per-call allocation. `ThreadLocal` has zero such discipline.
3. **A *shared* `Pool` adds a monitor on the per-mutation critical section** (`Pool(true, …)`
   synchronizes) — the wrong direction for a churn hotspot. A *thread-local* `Pool` is just
   `ThreadLocal` with mandatory `free()` — strictly worse.
4. **The only `Pool` advantages don't apply.** Reentrancy (a fresh instance per `obtain`) does not
   occur here; if it ever did, the fix is a reentrancy assert, not a pool. Soft-reference reclaim
   under memory pressure is irrelevant for sub-megabyte, leaf-bounded scratch on a bounded pool.
5. **Consistency.** SAFE and AGGRESSIVE share one `DecodeScratch` holder; switching mechanisms
   mid-design fragments the model for no gain.

**When `Pool`/a passed context *would* be right** (not now): if a future refactor threads one
scratch context through the tree API per *transaction commit* (amortizing `ThreadLocal.get()` and
releasing at commit end). That changes the `ValueColumn` interface (an explicit non-goal) for ~0
wall-clock payoff — not worth it.

## 7. Safety argument (MVCC)

- **Scratch is never retained.** `decodeAt` copies into a fresh `String`; `decodeAllBytes` copies
  each entry into a caller-owned `byte[]`; `encode`/`encodeFromArena` always trim into a freshly
  allocated `data` blob (§3.3 / D3). No retained column state aliases the scratch, so a mutation on
  one column — or one MVCC layer — cannot leak into another that later reuses the same thread's
  scratch.
- **`duplicate()` is unchanged** — deep-copies `this.data` + clones `restartOffsets`; never touches
  the scratch. Base column and transactional layer have independent blobs.
- **Concurrency.** Columns are thread-confined during mutation (MVCC); each thread has its own
  `DecodeScratch`. Two threads mutating different columns never share scratch.
- **(AGGRESSIVE only) int-pointer aliasing is a loud logic bug**, not silent corruption: a
  duplicate `(offset, length)` pair decodes the wrong key and fails tests immediately — unlike the
  `byte[][]`-pool prototype, where two columns sharing a `byte[]` reference corrupted state
  silently. This is why the arena design is admissible where the pool was not.

## 8. Test gates

### 8.1 Existing column tests (`FrontCodedStringColumnTest`, 20) — must stay 0F/0E

Boxed-column parity across insert/remove/find/duplicate/copyRange (incl. overlapping self-copy and
right-shift-past-live-end merge), restart-block decode, >255-byte varint keys, stale-tail reuse,
empty/zero-shared keys, drain-to-empty-then-regrow, `fillEmpty` boundaries, UTF-8, plus the MVCC
commit/rollback tests and the randomized tree oracle.

### 8.2 New SAFE regression tests (added this revision)

These target the shared-thread-local reuse SAFE introduces (the existing tests never interleave
columns or cross threads):

- **`shouldNotBleedScratchBetweenColumnsSharingTheThread`** — two columns with deliberately distinct
  key prefixes, mutated alternately in one thread so each op overwrites the shared decode/encode
  scratch left by the other; every op asserts both columns still match their own boxed oracle. Any
  scratch bleed or an accidental `data`-aliases-scratch regression (D3) surfaces as a wrong prefix.
- **`shouldMatchBoxedColumnUnderRandomizedMutations`** — single-column randomized insert/remove
  differential fuzz vs `BoxedObjectColumn`, capacity 64 (crosses the restart interval), stress keys
  (empty, >255-byte suffix, short long→short transitions, multi-byte UTF-8). 5 000 ops, full-slot
  compare after each.
- **`shouldKeepScratchIsolatedAcrossThreads`** — N threads each run the fuzz on their own column +
  oracle; asserts no thread observed a mismatch. Guards `ThreadLocal` isolation and catches any
  accidental `static` shared mutable state.

### 8.3 Additional AGGRESSIVE gates (when that variant is implemented)

On top of 8.1/8.2, add: `shouldInsertAtCapacityBoundary` (D1, `size+1`),
`shouldCopyRangePastCapacity` (D1, `newSize > capacity`), `shouldRecomputeSharedPrefixAfterShift`
(re-front-code against the *new* predecessor), `shouldReanchorRestartPointsAfterShift` (restart
points rebuilt at new `i%16==0` positions), and a two-column arena-bleed fuzz. The
snapshot-before-rearrange ordering (D2) is gated by the existing `shouldCopyOverlappingRangeIntoSelf`
+ the two-column fuzz.

### 8.4 Broader tree/unique gate (both variants)

`TransactionalBucketBPlusTreeTest` (88), `GlobalUniqueIndexTest` (12), `UniqueIndexTest` (27),
`UniqueIndexFoldTest` (10), and the long-running randomized/savepoint bucket-tree harnesses —
all 0F/0E.

## 9. Backward compatibility — none

On-disk format is byte-identical; scratch is pure in-memory. `duplicate()` deep-copies `data`
exactly as before. No `serialVersionUID` bump, no bwc reader (intra-dev change; the column persists
via Kryo through the tree's storage-part serializer, which reads/writes the `byte[] data` blob —
unchanged).

## 10. Risks / gains / losses

### SAFE (ship)

- **Gains** — 3.31× wall-clock (measured); search hop ~120 B → ~56 B; encode buffer no longer
  allocated per mutation; trivial retained footprint (~128 KB / 32 threads); low-risk, green.
- **Losses** — leaves the mutation-path `decodeAllBytes` churn (~20 KB/mutation, the 20.7 % FC
  residual); tiny high-water retention.
- **Risks** — minimal: same ThreadLocal reuse invariant, `String`-per-compare stays; the only
  load-bearing rule is the always-copy in `encode` (D3), pinned by 8.2.

### AGGRESSIVE (deferred)

- **Gains** — mutation churn ~20 KB → ~3–5 KB (~85 %); FC alloc share 20.7 % → 6.3 % (~19 GB less
  garbage on the profiled run); removes the last per-entry `byte[]` explosion; eliminates the
  aliasing class that killed the `byte[][]` pool.
- **Losses** — **~0 wall-clock gain over SAFE** (bottleneck is now OffsetIndex `long[]`); ~4×
  retained scratch (~512 KB / 32 threads, sticky); materially more complex code; still allocates
  the ~3.2 KB/mutation floor + temp `int[]` snapshots (not "zero-alloc").
- **Risks** — D1 (AIOOBE) and D2 (silent corruption) are must-fix-before-run; D3 invites a
  re-alias regression; correctness of shared-arena reuse rests on the non-reentrancy assumption
  (gated by the fuzz). Net: high implementation-risk for ~0 wall-clock payoff at the current
  bottleneck position.

## 11. Recommendation & sequencing

1. **Ship SAFE now** (this revision). It captures the entire measured wall-clock win at minimal
   risk.
2. **Fix `OffsetIndex` `long[]` allocation next** — it is the new dominant allocator (~50 % post-SAFE)
   and the actual wall-clock bottleneck.
3. **Only then re-measure** unique/ALIVE. Implement AGGRESSIVE **only if** FC's mutation-path alloc
   re-emerges as a wall-clock stall once the bigger allocator is gone. If it does, apply D1–D4 and
   land the §8.3 gates first.

## 12. Implementation steps

### 12.1 SAFE (now)

1. Add `DecodeScratch` (`cur`, `encodeBuf`) + `SCRATCH` ThreadLocal.
2. `decodeAt` — borrow `cur`, grow on demand, write back.
3. `decodeAllBytes` — borrow `cur`, grow on demand, write back (per-entry `byte[]` copies stay).
4. `encode` — borrow `encodeBuf` (pre-size via `ensureCapacity`, grow in-loop), write back, then
   `this.data = Arrays.copyOf(buf, len)` **unconditionally**.
5. Update the class javadoc "Scratch contract" paragraph.
6. Add the three §8.2 tests.
7. Run column gate: `mvn -pl evita_engine -am install -DskipTests` then
   `mvn -pl evita_test/evita_functional_tests test -Dtest=FrontCodedStringColumnTest` (0F/0E).
8. Run the §8.4 tree/unique gate.

### 12.2 AGGRESSIVE (deferred — do not start until §11 step 3 justifies it)

Extend `DecodeScratch` (arena + grow-on-demand pointer arrays), add `decodeAllIntoArena` /
`encodeFromArena` / region `commonPrefix`, rewrite the mutators + `copyRangeTo` per §4 with D1–D4
applied, remove `decodeAllBytes` / `decodeAll` / 2-arg `encode` / 2-arg `commonPrefix`, add the
§8.3 gates, re-measure alloc share (compare SHARES, not absolute sample counts).
