# Individual (local) JMH benchmark results

This folder holds **isolated, locally-run JMH microbenchmark results** — the developer-facing measurements that back
specific engine implementation decisions (data-structure choices, block sizes, hot-path tuning). They are distinct
from the product-facing performance documentation in the parent folder
([`performance_comparison.md`](../performance_comparison.md), [`vertical_scalability.md`](../vertical_scalability.md)),
which compares evitaDB as a whole against other databases.

## Layout

One subfolder **per JMH benchmark class**, named after the class:

```
individual/
└── <JmhBenchmarkClassName>/
    └── README.md     ← the writeup: why it exists, what is measured, results, the decision it drove
```

The writeup's `Benchmark source` line points to the JMH class, and that class's JavaDoc links back to its folder, so
the test and its results stay discoverable from either side.

## What to keep (and what not to)

- **The `README.md` writeup is the authoritative artifact.** Transcribe the deciding numbers into its tables. It —
  together with the re-runnable benchmark class — is what future readers rely on.
- **Do not archive JMH `.log` files.** They are verbose console transcripts (warmup/iteration spam, ETA); their only
  durable content is the run environment (JDK, VM options, warmup/measurement/fork config), which belongs in the
  writeup's preamble and in the benchmark class's `@Warmup`/`@Measurement`/`@Fork` annotations.
- **Keep at most one CSV, and only the one that backs the *published/final* numbers.** Never keep CSVs of superseded
  or rejected runs — they back dead ends and mislead. If the published numbers have no matching CSV, either re-run the
  final config once to produce a clean one or rely on the writeup's tables; do not backfill with obsolete runs.

## Contents

- [`SortIndexArrayVsBPlusTreeBenchmark/`](SortIndexArrayVsBPlusTreeBenchmark/README.md) — `SortIndex` distinct-values
  backing: contiguous array vs. consolidated `TransactionalObjectBPlusTree`. Drove `SortIndex.VALUE_BLOCK_SIZE = 256`
  plus the leaf-array-caching and software-prefetch optimizations in `TransactionalObjectBPlusTree`.
- [`Http2ConnectionMonitorBenchmark/`](Http2ConnectionMonitorBenchmark/README.md) — cost of the HTTP/2 connection
  monitor that sits in every child channel pipeline. Proved the inbound frame walk is O(frames) rather than O(bytes)
  (48.7× more bytes → +2.2 % time) at ~7 ns/frame, the outbound `GOAWAY` recognition ~1–3 ns/write, and neither
  direction allocating.
- [`BucketBPlusTreePayloadBenchmark/`](BucketBPlusTreePayloadBenchmark/README.md) — neutrality A/B for generalizing the
  `TransactionalBucketBPlusTree` single-record column from raw `int[]` to the pluggable `RecordColumn` SPI
  (`IntRecordColumn` / `LongRecordColumn`). Proved allocation- and time-neutral (deterministic `gc.alloc.rate.norm`
  byte-identical per op; sole cost is a ~16 B wrapper per leaf on construction).

## Adding a new result

1. Create `individual/<JmhBenchmarkClassName>/`.
2. Add a `README.md` (mirror the structure of an existing one: *why → what is measured → how to run → results →
   verdict/decision*), transcribing the deciding numbers into tables.
3. Optionally drop a single CSV backing the final/published numbers beside it — no logs, no superseded-run CSVs.
4. Add a back-reference from the benchmark class JavaDoc to the folder, and list the folder above.
