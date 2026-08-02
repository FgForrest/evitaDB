# CRC32C combine — GF(2) power ladder (measured)

Issue: #760 (ALIVE churn CPU refinement of the CRC32C cumulative-checksum combine). Not a
correctness gap — the combine is already correct; this targets the **second-largest CPU
hotspot** on the unique/ALIVE write path.

Status: DESIGN, awaiting GO. Author dialogue: Johnny + Claude. Measurement context for the *hotspot*
is cited from the async-profiler run captured in memory `alive-cpu-hotspots-1gb-0-4`; the *design
decision* is now backed by a dedicated JMH benchmark
(`evita_test/evita_performance_tests/.../spike/Crc32CombineCacheBenchmark.java`), so the numbers
below are measured, not estimated.

> **Scope note — this revision reverses the earlier scope call.** The previous draft respected
> Johnny's decision to *remove the static `combine` and replace it with an instance method + a
> per-`len2` power-matrix cache* (`HashMap<Long,int[]>`). The JMH benchmark (added for this review at
> Johnny's request) shows that design is **dominated on every axis** by a much simpler one: keep the
> static `combine`, and replace its per-call matrix rebuild with a single **workload-independent 8 KB
> static "doubling ladder"**. It is faster, allocates **zero** bytes per call (the `HashMap<Long>`
> allocates 21.75 B/op), needs **no cache**, and has a **fixed 8 KB worst-case memory** instead of an
> unbounded one. The instance-method + cache design is retained in §7 as a documented alternative.
> The final call is Johnny's — this plan recommends the ladder.

## 1. Problem & scope

`Crc32CWrapper.combine` is a **static** method
(`evita_common/src/main/java/io/evitadb/utils/Crc32CWrapper.java:105`) that combines two CRC32C
values into the CRC of their concatenation via GF(2) matrix exponentiation. It allocates
`new int[32], new int[32]` (128 B each) **per call** and, more importantly, **rebuilds the GF(2)
power matrix from scratch on every call**. It is invoked **per record** from
`ObservableOutput.finishRecord` (`:537`) and the flush path (`:798`):

```java
this.cumulativeChecksum = Crc32CWrapper.combine(
    this.cumulativeChecksum, fullRecordChecksum, fullRecordLength
);
```

**CPU profile** (async-profiler `-e cpu`, EvitaWarmUpInsertionTest unique/ALIVE, 1 GB/0.4 config,
19 380 samples; ALIVE-only share):

| source | CPU share | notes |
|---|--:|---|
| CRC32C (all) | 11.8 % | second-largest hotspot after GC (31 %) |
| `gf2MatrixTimes` | 9.1 % | the do-while square loop + per-set-bit matrix×vector |
| `combineInternal` | 1.1 % | driver of the square loop |
| `reverseCrc32c` | 0.9 % | shift-register reverse inside `forceValue` (stateful path only) |

The FORWARD per-record checksum uses hardware `java.util.zip.CRC32C` (`Crc32CWrapper` field
`:75`) — that is **not** the cost. The cost is `combineInternal` (`:123`): the do-while over
`len2`'s bits (`gf2MatrixSquare` per power, `gf2MatrixTimes` per set bit). Each call **re-derives
the ladder of squared operators M², M⁴, M⁸, …** even though that ladder is a fixed function of the
polynomial alone — identical on every single call. Only *which* rungs are folded into `crc1`
depends on `len2`. That redundant re-squaring is the 9 %.

**Non-goals.** No change to the on-disk format (the cumulative checksum VALUE is unchanged — the
combine is a mathematical identity). No change to the forward CRC32C path. No change to the
`Checksum` interface contract. No `serialVersionUID` bump.

## 2. The GF(2) identity and the key observation

CRC32C combine is the GF(2) identity (reflected Castagnoli, polynomial `CRC32C_POLY`):

```
CRC(d1 ‖ d2) = shift(CRC(d1), 8·len2) ⊕ CRC(d2)
```

`shift(crc, n)` advances the CRC register by `n` zero bits = multiplying the state vector by `Mⁿ`
(M = the one-zero-bit operator). Write `8·len2` in binary: `Mᐟ⁸·len2⁾ = ∏ M⁽⁸·2ʲ⁾` over the set bits
`j` of `len2`. **Define the ladder** `L[j] = M⁽⁸·2ʲ⁾`. Then:

```
combine(crc1, crc2, len2) = ( ∏_{j : bit j of len2 set} L[j] · crc1 ) ⊕ crc2
```

The ladder `L[0..63]` (M⁸, M¹⁶, M³², …) depends **only** on the polynomial — never on `crc1`,
`crc2`, or `len2`. `L[j+1] = square(L[j])`. So it can be built **once** at class init and reused
forever. The current code rebuilds it inside every call; that is the entire waste.

- **`L[0] = M⁸`** (three squarings of M).
- **`L[j] = square(L[j-1])`.**
- A combine is then `popcount(len2)` matrix×vector products applied to `crc1`, plus one XOR — no
  squaring, no allocation.

Because all rungs are powers of M they commute, so the fold order is irrelevant — the result is
bit-identical to today's `combineInternal` (empirically verified against the production method for
20 000 random `(crc1, crc2, len2)` triples in the benchmark's `verifyCorrectness()` gate).

## 3. Measured design decision (JMH)

Benchmark `Crc32CombineCacheBenchmark` reproduces the exact GF(2) math (cross-checked against the
production `Crc32CWrapper.combine`) and measures the steady-state per-record combine cost of every
candidate on a fully-warmed cache — i.e. the `ObservableOutput.finishRecord` hot path where every
distinct record length has already been seen. `distinctLengths` = how many distinct record sizes the
workload cycles through (1 = fixed-size records; 128 = a realistic size spread).

JDK 21, AverageTime, 2 forks × 6 iterations, `-prof gc`:

| strategy | ns/op (d=1) | ns/op (d=128) | **B/op (d=128)** | per-`len2` memory |
|---|--:|--:|--:|---|
| `recompute` (**today**) | 1112.5 | 1903.7 | **288** | none |
| **`ladderNoCache`** (recommended) | 39.3 | 89.0 | **≈0** | **none — 8 KB static** |
| `denseArray` (per-instance `int[][]`) | 13.6 | 13.6 | ≈0 | O(maxLen) / instance |
| `denseArraySharedAtomic` (static) | 14.2 | 14.6 | ≈0 | O(maxLen) shared |
| `openIntMap` (per-instance, no box) | 13.9 | 16.9 | ≈0 | unbounded / instance |
| `hashMapInt` (per-instance) | 14.1 | 16.8 | 14.5 | unbounded / instance |
| `chmSharedInt` (**static shared CHM**) | 14.4 | 16.9 | 14.5 | unbounded shared |
| `hashMapLong` (**the previous plan**) | 14.3 | 17.7 | **21.75** | unbounded / instance |

(JDK 21, AverageTime, 2 forks × 6 iterations, `-prof gc`; `d` = distinct record sizes cycled, cache
100 % warm. `openIntMap` = a self-contained box-free open-addressing `int`→`int[]` map — the codebase
uses no primitive-collection library, so the benchmark ships its own rather than add a dependency.)

**Reading the table — this answers the three questions Johnny raised:**

**Q1 — precompute "common" matrices and ship them on the classpath?** No. A per-`len2` miss (build
one matrix) is microseconds and happens *once per distinct length*; with any warm cache the hit rate
is ~100 %, so precomputation would save only the first sighting of each length — a rounding error.
"Common lengths" are also workload-specific and unknowable at build time. **The mathematically clean
version of the idea is the ladder itself**: the ~40–64 rung doubling ladder *is* the universal,
workload-independent precomputed basis (8 KB), and it collapses *every* length in `popcount(len2)`
steps — no guessing, no classpath resource, no format/versioning surface. Build it in a `static`
initializer, not as a serialized artifact.

**Q2 — single static shared `ConcurrentHashMap`?** The container barely matters: it is a 0–5 ns
effect on a ~15 ns op (the `gf2MatrixTimes` floor is ~13.7 ns — the `denseArray` number — and
dominates). Crucially, **`chmSharedInt` (17.2 ns) is not slower than the per-instance `HashMap`
(17.4–18.6 ns)** — the previous plan's stated reason for rejecting a static shared cache
("ConcurrentHashMap overhead") is **refuted by measurement**. `ConcurrentHashMap.get` on immutable
values is a lock-free read; safe publication of a never-mutated `int[]` is automatic. So *if* a cache
is used, a static shared one is fine and bounds memory better than per-instance. But see Q3/§4: the
ladder removes the need for any cache, so this question becomes moot.

**Q3 — worst-case cache memory?** This is the decisive axis and it favours the ladder:
- **`ladderNoCache`: fixed 8 KB, full stop** — 64 rungs × `int[32]` (128 B) = 8 KB, one immutable
  static instance for the whole JVM, independent of workload, instance count, or record sizes.
- **Any `HashMap`/CHM cache is unbounded** in principle: entries = distinct `len2` values seen. Per
  entry ≈ 200 B (`int[32]` 144 B + `Node` 32 B + boxed key 16 B). The previous plan's "tens to a few
  hundred KB per instance" ignores the **instance multiplier**: `ObservableOutputKeeper` caches one
  long-lived `Crc32CWrapper` **per open file** (hundreds). Per-instance worst case therefore scales
  as `instances × distinct-lengths × 200 B` — hundreds of MB under an adversarial distinct-length
  workload, GBs if uncapped. A static shared cache caps that at a few MB; the ladder caps it at 8 KB.
- **A dense length-indexed array is memory-unsafe** here: `len2` is a record/flush length that can be
  large and varied (the flush path passes `flushLength`, up to the output buffer size), so
  `array[len2]` risks an O(MB) skeleton. Only a hash cache or the ladder handle arbitrary `len2`
  safely — and the ladder does it with zero per-`len2` state.

**Allocation is the sleeper finding.** This optimization exists inside a **31 %-GC-bound** path. The
previous plan's `HashMap<Long,int[]>` **allocates 21.75 B/op** (Long boxing, not eliminated by escape
analysis once the map is non-trivial); `HashMap<Integer>`/CHM allocate 14.5 B/op. The ladder (and the
box-free `denseArray`/`fastutil`/`AtomicReferenceArray`) allocate **zero**. Choosing the `HashMap<Long>`
cache would re-inject per-record churn into the exact workload we are trying to de-churn.

**Conclusion.** `ladderNoCache` captures essentially the whole win: **20–48× faster than today,
288 B/op → 0, fixed 8 KB memory, arbitrary-`len2`-safe, no container / boxing / eviction /
per-instance-vs-static / thread-safety design surface.** It is 3–6× slower than a warm cache hit,
which is worth **~0.4 % of the ALIVE run's wall-clock** (§8) — noise. The ladder is the
recommendation; the box-free cache (§7) is a "squeeze the last few ns" fallback; the `HashMap<Long>`
is dominated on every axis and is dropped.

## 4. Design — static ladder, static `combine` kept

Because the ladder is a single immutable static structure, there is **no per-instance state** and
therefore **no reason to convert `combine` to an instance method**. The static `combine` stays; only
its body changes. This is a far smaller and safer change than the previous instance-migration plan:
**no call-site migration, no new `ObservableOutput` field, no test rewrites.**

### 4.1 The ladder (static, built once)

```java
/**
 * Doubling ladder of GF(2) operators {@code L[j] = M^(8·2^j)} where {@code M} is the one-zero-bit
 * CRC32C shift operator. Depends only on {@link #CRC32C_POLY}, so it is built once at class init and
 * shared immutably across all threads. {@code L[0] = M^8} (one byte of zero-shift); each subsequent
 * rung is the square of the previous. 64 rungs cover every {@code long} {@code len2}. Fixed 8 KB.
 */
private static final int[][] COMBINE_LADDER = buildCombineLadder();

@Nonnull
private static int[][] buildCombineLadder() {
	// M = operator for one zero bit
	final int[] m = new int[32];
	m[0] = CRC32C_POLY;
	int row = 1;
	for (int i = 1; i < 32; i++) {
		m[i] = row;
		row <<= 1;
	}
	// L[0] = M^8 (three squarings: M -> M^2 -> M^4 -> M^8)
	final int[] m2 = new int[32];
	final int[] m4 = new int[32];
	final int[] l0 = new int[32];
	gf2MatrixSquare(m2, m);
	gf2MatrixSquare(m4, m2);
	gf2MatrixSquare(l0, m4);
	final int[][] ladder = new int[Long.SIZE][];
	ladder[0] = l0;
	for (int j = 1; j < ladder.length; j++) {
		final int[] next = new int[32];
		gf2MatrixSquare(next, ladder[j - 1]);   // L[j] = square(L[j-1]) = M^(8·2^j)
		ladder[j] = next;
	}
	return ladder;
}
```

### 4.2 The new static `combine` (zero-alloc, no square loop)

```java
/**
 * Combines two CRC32C values into the CRC32C of (data1 ‖ data2). Applies the precomputed GF(2)
 * doubling ladder ({@link #COMBINE_LADDER}) to {@code crc1} once per set bit of {@code len2} — no
 * per-call matrix rebuild, no allocation. Bit-identical to the previous implementation (the ladder
 * is a mathematical identity; see the module design note / benchmark correctness gate).
 *
 * @param crc1 CRC32C of data1 (unsigned 32-bit in a long)
 * @param crc2 CRC32C of data2 (unsigned 32-bit in a long)
 * @param len2 number of bytes in data2
 * @return CRC32C of data1 ‖ data2, as an unsigned 32-bit value in a long
 */
public static long combine(long crc1, long crc2, long len2) {
	if (len2 <= 0) {
		return crc1 & 0xFFFFFFFFL;
	}
	int shifted = (int) crc1;
	long remaining = len2;
	int j = 0;
	while (remaining != 0) {
		if ((remaining & 1L) != 0) {
			shifted = gf2MatrixTimes(COMBINE_LADDER[j], shifted);
		}
		remaining >>>= 1;
		j++;
	}
	return (shifted ^ (int) crc2) & 0xFFFFFFFFL;
}
```

`gf2MatrixTimes` and `gf2MatrixSquare` stay exactly as they are (pure static functions). The
`private static long combineInternal(int, int, long, int[], int[])` (`:123`) is deleted — its logic
is now split into the one-time `buildCombineLadder` and the per-call `combine`.

### 4.3 Instance scratch `odd`/`even` — remove

With the ladder static and `combineInternal` gone, the per-instance scratch fields
`private final int[] odd = new int[32]` (`:86`) and `even` (`:92`) have no remaining reader and are
**deleted** (they existed only to feed `combineInternal`). This drops two `int[32]` allocations from
every `Crc32CWrapper` construction. `withAnotherChecksum` (§4.4) no longer needs them.

### 4.4 `withAnotherChecksum` — unchanged behaviour, routed through the fast `combine`

The stateful instance path keeps its contract (mutate internal state so `getValue()` returns the
combined value) and simply calls the now-fast static `combine`:

```java
@Nonnull
public Crc32CWrapper withAnotherChecksum(long checksum, int contentLength) {
	final long combined = combine(getValue(), checksum, contentLength);  // fast, zero-alloc
	forceValue(combined);                                                // keeps the stateful contract
	return this;
}
```

`forceValue`/`reverseCrc32c` remain only on this WAL/`Checksum.combine` path (the 0.9 % residual),
exactly as today — the `ObservableOutput` hot path never touches them (it keeps the cumulative as a
bare `long` and calls the static `combine` directly).

## 5. Call sites — no migration required

Because `combine` stays static with the same signature, **every existing call site is unchanged**:

| call site | file:line | effect of this change |
|---|---|---|
| `ObservableOutput.finishRecord` | `ObservableOutput.java:537` | same call, now 20–48× faster + zero-alloc |
| `ObservableOutput.writeDataToOutputStream` | `ObservableOutput.java:798` | same |
| `withAnotherChecksum` → WAL suppliers, `ObservableInput`, `AbstractMutationLog` (`Checksum.combine`) | §previous inventory | same, routed through fast `combine` |
| `ObservableOutputTest:715`, `Crc32CWrapperTest` (~13 sites), `LongRunningCrc32CWrapperTest:209` | tests | **no change** — they already call `Crc32CWrapper.combine(...)` |

This is the big simplification over the previous draft: no new `checksumCombiner` field on
`ObservableOutput`, no static→instance test migration, no per-instance cache lifecycle. The change is
localized to `Crc32CWrapper` (build the ladder, rewrite `combine`, delete `combineInternal` +
`odd`/`even`).

## 6. `reverseCrc32c` — unchanged (defer)

`reverseCrc32c` (`:210`) is called only from `forceValue` on the stateful WAL path (0.9 %). The new
`combine` never calls `forceValue`, so the hot `ObservableOutput` path drops CRC cost without touching
it. A table-based reverse is possible but the input is a 32-bit CRC delta (no cache-able key, LUT
only) and 0.9 % does not justify it. Out of scope, as before.

## 7. Alternative (documented, not recommended): box-free lazy cache

If a future profile shows the ladder's per-call `popcount(len2)` folds are themselves a hotspot
(they are not today — combine drops from 11.8 % to ~0.5 %), add a lazy cache of the *folded* matrix
`M⁽⁸·len2⁾` keyed by `len2`, built from the ladder on a miss (no squaring). Per the benchmark:

- Use a **box-free, static, shared** structure — `ConcurrentHashMap<Integer,int[]>` is fine (16.9 ns,
  and CHM is not slower than a per-instance `HashMap`); a primitive `int`-keyed open-addressing map
  behind the existing single-thread confinement is faster but per-instance.
- **Do not** use `HashMap<Long,int[]>` (the previous design): slowest cache *and* 21.75 B/op.
- **Do not** use a dense length-indexed array: `len2` (flush length) can be large/varied → O(MB)
  skeleton.
- Cap it (e.g. clear at 4096 entries) — misses rebuild deterministically from the ladder.

This buys ~0.4 % of ALIVE wall-clock over the ladder at the cost of a container, boxing/eviction
policy, and unbounded-until-capped memory. Not worth it now; recorded so the trade-off is explicit.

## 8. Expected payoff (measured)

- **CPU:** the combine hot path drops from ~1133–1884 ns/call to **38.8–93.0 ns/call** (20–48×).
  Against the async-profiler baseline (combine ≈ 11.8 % of ALIVE CPU, ≈ 38 M calls, ≈ 57 s of a
  ≈ 480 s run), the ladder is ≈ 2.5 s (**~0.5 % of the run**, from 11.8 %). CRC32C leaves the hotspot
  list.
- **Allocation:** `288 B/op → ~0`. In a 31 %-GC-bound run this removes ~38 M × 288 B ≈ **10.5 GB of
  transient allocation** across the run, plus two `int[32]` per `Crc32CWrapper` construction.
- **Memory:** one fixed **8 KB** static ladder for the whole JVM. No per-instance, no per-file, no
  unbounded cache.
- **No format change, no BWC, no `serialVersionUID` bump** (§9).

## 9. Backward compatibility

**None required — and none performed.** The ladder produces a bit-identical CRC32C value (§2 proof +
20 000-case benchmark gate). The on-disk cumulative checksum (trailing record CRC in `ObservableOutput`,
trailing WAL transaction CRC in `AbstractMutationLog`) is the **same value**; existing files verify
cleanly (`ObservableInput:862-866` unchanged). Per the `serialVersionUID` policy: no bump, no bwc
reader — this is an intra-dev computation-path change with zero on-disk-format delta. The persisted
artifact is the checksum *value*, not the calculator.

## 10. Risks

1. **Ladder correctness (load-bearing).** Proven in §2; empirically gated by the benchmark's
   `verifyCorrectness()` (20 000 random triples vs production `combine`, all three paths) and by
   `Crc32CWrapperTest` (associativity, `len2 ≤ 0`, three-way, cross-check vs `java.util.zip.CRC32C`).
   A ladder-vs-recompute divergence fails these loudly.
2. **Ladder coverage of `len2` bit-width.** `len2` is a `long`; the ladder has `Long.SIZE = 64` rungs,
   so every set bit of any `long` `len2` indexes a valid rung. (Record/flush lengths are `int` in
   practice; 64 rungs are defensive headroom at negligible 8 KB cost.)
3. **Class-init cost / thread-safety of the static ladder.** Built once in a `static` initializer
   (64 squarings ≈ microseconds), immutable thereafter; JLS class-init + `final` semantics give safe
   publication to all threads. No `ConcurrentHashMap`, no happens-before reasoning, no mutable static.
4. **`odd`/`even` removal.** Verified they have no reader outside the deleted `combineInternal`
   (the only references are `:84`, `:90` JavaDoc + `combineInternal` params). `grep` gate in §11.
5. **`len2 <= 0` guard** preserved verbatim (returns `crc1 & 0xFFFFFFFFL`), matching today.

## 11. Step-by-step

1. **`Crc32CWrapper`** — add `COMBINE_LADDER` + `buildCombineLadder()` (§4.1); rewrite `combine`
   (§4.2); delete `combineInternal` (`:123-164`) and the instance `odd`/`even` fields (`:86`, `:92`);
   route `withAnotherChecksum` through `combine` (§4.4). Update the `combine` JavaDoc (`:94-104`).
   Confirm no dangling `odd`/`even` refs: `rg '\bodd\b|\beven\b' Crc32CWrapper.java`.
2. **No call-site or test edits.** `combine` stays `public static` with the same signature (§5).
3. **Add one benchmark-parity unit test** to `Crc32CWrapperTest`: `shouldMatchLegacyCombineAcrossLengths`
   — assert the new `combine` equals `java.util.zip.CRC32C` of the real concatenation for a spread of
   `len2` (including large/odd lengths and boundary bit patterns). The existing associativity/edge
   tests remain the primary oracle.
4. **Run gates** (§12). Confirm 0 failures / 0 errors.
5. **Re-profile** (optional): async-profiler `-e cpu` on EvitaWarmUpInsertionTest unique/ALIVE
   1 GB/0.4; expect CRC32C 11.8 % → ~0.5 % and the `new int[32]` alloc samples on the combine path to
   vanish.

## 12. Test gates

- **`Crc32CWrapperTest`** (`evita_functional_tests`, `@Tag(STORAGE) @Tag(SERIALIZATION)`): associativity,
  `len2 == 0`, `len2 < 0`, three-way combine, cross-check vs hardware `CRC32C` of the real byte
  concatenation. Primary correctness oracle — must be 100 % green, unchanged in intent. Plus the new
  §11.3 length-spread parity test.
- **`ObservableOutputTest.CumulativeChecksumTests`**: single-/multi-record, compression on/off
  cumulative checksum vs manual — gates the (now behaviour-preserving) `ObservableOutput` path.
- **WAL round-trip** (`@Tag(WAL)`): `EngineMutationLogTest`, `CatalogWriteAheadLogTest`,
  `CatalogWriteAheadLogIntegrationTest`, `LongRunningCatalogWriteAheadLogIntegrationTest` — write/read
  transactions, verify cumulative checksum + corruption detection. Gates `withAnotherChecksum`.
- **`LongRunningCrc32CWrapperTest`**: long-running combine.
- **`Crc32CombineCacheBenchmark`** (`evita_performance_tests`): the design oracle; its
  `verifyCorrectness()` fails the run on any divergence from production `combine`.

### Build / run recipe

```shell
# unit + functional (fast loop)
rtk mvn -pl evita_test/evita_functional_tests test -P unitAndFunctional \
  -Dgroups="(storage | serialization | wal) & !slow"

# long-running combine + WAL integration
rtk mvn -P longRunning -pl evita_test/evita_long_running_tests test \
  -Dgroups="(storage | wal) & !slow"

# the design benchmark (standalone; JDK-any, relative numbers)
java -cp evita_test/evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main \
  'io\.evitadb\.spike\.Crc32CombineCacheBenchmark' -prof gc
```
