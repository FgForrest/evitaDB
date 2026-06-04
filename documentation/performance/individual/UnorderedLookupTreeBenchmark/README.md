# UnorderedLookupTree — write-scaling benchmark

**Benchmark source** (the executable counterpart of this document — its class JavaDoc links back here):
`evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/UnorderedLookupTreeBenchmark.java`

Every number below was produced by running that benchmark. It guards a single property of the two-tree backing
introduced under issue #760 — the **asymptotic cost of writes**. When the tree's write path or block size changes,
re-run the benchmark and update this document so the two never drift apart.

## Why this benchmark exists

`TransactionalUnorderedIntArray` is backed by the count-augmented position tree `UnorderedLookupTree` paired with the
no-boxing `int → long` value index `TransactionalIntToLongBPlusTree`. It replaces an **array delegate** that stores the
logical order in a contiguous `int[]`: every positional insert or move on that array shifts/renumbers an `O(N)` suffix,
so building and churning a long chain is `O(N²)`. That quadratic cost is the exact reason the two-tree backing was
built — it touches only the `O(log N)` root→leaf cursor path per mutation.

This benchmark is the proof that the new backing actually delivers that asymptotic behaviour:

- **build** must be `O(N)` overall (constant per-record cost as the chain grows), and
- **churn** (a predecessor move) must be `O(log N)` per operation (near-constant per-move cost as the chain grows).

Read addressing and structural correctness are covered elsewhere (the functional oracle suite
`UnorderedLookupTreeTest` and the generational soak `LongRunningUnorderedLookupTreeTest`); this is purely a write
hot-path scaling check.

## What is measured

Both phases run in `Mode.SingleShotTime` — the natural unit is "time to apply the whole batch".

1. **`buildChain`** — builds a single chain `1 → 2 → … → N` via `recordCount` individual `insertAfter` writes,
   starting from an empty backing on every invocation. Reading the **per-record** cost across growing `recordCount`
   reveals whether the build is linear.
2. **`churnChain`** — over a pre-built chain of length `recordCount` (built once per trial, outside the measured
   method), repeatedly moves a random record to sit after another random record (a predecessor update = remove +
   re-insert), `churnOperations` times. Comparing the **per-move** cost at different chain lengths reveals whether the
   move cost is logarithmic.

### Parameters
- `recordCount` — chain length. Build sweeps `{500 000, 1 000 000, 2 000 000, 4 000 000}` (a doubling sequence, so a
  linear build shows a ~2× step and an `O(N²)` build would show a ~4× step). Churn uses `{1 000 000, 4 000 000}`.
- `churnOperations` — moves applied per measured churn invocation, fixed at `500 000` so the per-move cost is read
  directly against a constant op count while the chain length varies.

## How to run

```bash
# build the benchmarks jar (performance module is outside the default reactor)
mvn -pl evita_test/evita_performance_tests -am -P full -DskipTests package

# run only this benchmark (the jar uses a custom main, so go through JMH's runner)
java -cp evita_test/evita_performance_tests/target/benchmarks.jar \
  org.openjdk.jmh.Main 'io\.evitadb\.spike\.UnorderedLookupTreeBenchmark'
```

Useful selectors: target one phase with `'…UnorderedLookupTreeBenchmark\.buildChain'`, pin sizes with
`-p recordCount=1000000 -p churnOperations=500000`, and `-f 1 -wi 1 -i 3` for a quick run. On the shared machine,
`-Djmh.ignoreLock=true` (and `rm -f /tmp/jmh.lock`) may be needed.

## Results

JDK 21.0.11 (OpenJDK 64-Bit Server VM), JMH 1.37, 1 fork, 1 warmup + 3 measurement single-shot iterations, single
thread. The `Error` column is JMH's 99.9% confidence half-width. `SingleShotTime` with few iterations has **high
variance** (the first measured shot still carries JIT compilation), so the absolute `Error` margins are wide; the
signal is the **monotonic trend and the per-record / per-move normalization**, which are stable, not the individual
absolute milliseconds. Re-run to regenerate fresh data — raw JMH output is not retained.

### Build — `buildChain`, `ms/op` (full build of `recordCount` records)

| recordCount | ms/op | Error (±) | µs / record | step vs ½N |
|--:|--:|--:|--:|--:|
| 500 000 | 192.06 | 644.93 | 0.384 | — |
| 1 000 000 | 332.72 | 817.62 | 0.333 | 1.73× |
| 2 000 000 | 686.24 | 1373.14 | 0.343 | 2.06× |
| 4 000 000 | 1292.39 | 1733.50 | 0.323 | 1.88× |

**Per-record cost is flat at ~0.33 µs** across an 8× range of chain lengths, and each doubling of `N` multiplies the
total time by ~1.9× — i.e. linear. An `O(N²)` build (the array delegate's behaviour) would multiply by ~4× per
doubling and the per-record column would itself double each row. It does not. **Build is `O(N)`.**

### Churn — `churnChain`, `ms/op` (500 000 predecessor moves over a chain of length `recordCount`)

| recordCount | churnOperations | ms/op | Error (±) | µs / move |
|--:|--:|--:|--:|--:|
| 1 000 000 | 500 000 | 821.81 | 148.38 | 1.644 |
| 4 000 000 | 500 000 | 1145.66 | 569.31 | 2.291 |

The op count is identical (500 000 moves) in both rows; only the chain length changes. A **4× longer chain costs only
1.39× more** (per-move cost 1.64 → 2.29 µs). A per-move cost that is `O(N)` — as the array delegate's suffix-renumber
move is — would have grown ~4×. The observed ~1.4× growth is consistent with `O(log N)` per move (a deeper tree plus
more heap-scattered leaves at 4M); **churn is logarithmic per operation.**

## Verdict: **PASS — the two-tree backing scales as designed**

- **Build is linear** (`~0.33 µs/record`, constant), versus the `O(N²)` array delegate it replaces.
- **Churn is logarithmic per move** (`~4×` chain growth → `~1.4×` cost), versus the array delegate's `O(N)`-per-move
  suffix renumber.

This is the asymptotic guarantee the migration was built to deliver, measured directly. The commit-side win
(path-copying `O(Δ·log N)` instead of rebuilding a humongous `int[]`) is the same structural-sharing argument already
established for the other #760 trees and is not measured here.
