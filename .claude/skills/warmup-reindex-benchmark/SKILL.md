---
name: warmup-reindex-benchmark
description: Measure how long a full catalog reindex-and-publish takes — re-upsert every entity of a real production catalog into a fresh catalog in WARM_UP mode through the gRPC driver, then transition it to ALIVE via goLive. Reports load wall-clock, per-collection and per-upsert latency distributions, and the goLive transition separately. Invoke when asked how long publishing a dataset takes, when verifying a full reindex still completes after write-path or driver changes, when comparing ingestion throughput between two builds, or when profiling the server-side write path under a realistic bulk load.
allowed-tools: Read, Edit, Write, Grep, Glob, Bash(mvn *), Bash(rtk *), Bash(java *), Bash(git *), Bash(rg *), Bash(awk *), Bash(sed *), Bash(ps *), Bash(uptime *), Bash(free *), Bash(kill *), Bash(pkill *), Bash(cd *), Bash(ls *), Bash(du *), Bash(df *), Bash(unzip *), Bash(python3 *), Bash(cp *), Bash(mkdir *), Bash(chmod *), Bash(grep *), Bash(tail *), Bash(head *), AskUserQuestion
---

# WARM_UP reindex + goLive benchmark

Measures the operation an e-commerce operator calls **publishing**: rebuild a catalog from scratch in
`WARM_UP` state, then flip it to `ALIVE`. The harness
(`IsolatedWarmupLoadBenchmark` + `CatalogCopySupport`, package `io.evitadb.performance.warmupload`) is
dataset-agnostic — the catalog name is a system property, not baked in — so it applies to any
production export, not just the "senesi" one it was built for.

Complements `wal-replay-profiling`: that one measures **transactional commit** cost against an ALIVE
catalog, this one measures **bulk ingest** into a WARM_UP catalog. They exercise different write paths
and their numbers are not interchangeable.

## Why the two processes

The reader and the writer must not share a JVM. In a single-process copy the same thread deserializes
source entities and applies target mutations, so no thread filter separates them — read-path Kryo work
lands in the same flame graph as index maintenance. Running the writer as its own server means a
profiler attached to it sees the ingestion path and nothing else. It also halves the writer's live set:
the server holds only the catalog being built.

The trade-off is that client-observed upsert latency then includes protobuf serialization, the loopback
round trip and server-side deframing — **end-to-end ingestion latency, not write-path latency**. For
`Product` at milliseconds each the wire is noise; for cheap collections (`Tag`, `Stock`, `Voucher`) it
dominates and those rows must not be read as write-path costs. `TARGET_MODE=embedded` gives the
transport-free control; the difference between the two modes is what gRPC costs.

## Bundled scripts

All three take `ROOT=<checkout>` (default `/www/oss/evita/release_2026-2`).

- **`run-warmup-reindex.sh`** — the entry point. Starts the server, waits for readiness, runs the load,
  tears down, prints the report. One command, one number.
- **`run-warmup-target-server.sh`** — the write side alone. Use when profiling: attach to **this**
  process. Supports `PROFILE=jfr|alloc|cpu` with `AP_LIB`.
- **`run-warmup-load.sh`** — the read side alone. Use to drive several loads against one server.

## 1. Building

```shell
mvn clean install -P full -DskipTests
```

`-P full` is mandatory — `evita_test/evita_performance_tests` is not in the default reactor. To rebuild
only the perf module afterwards: `mvn -o -P full package -pl evita_test/evita_performance_tests -DskipTests`.

## 2. Reproducing the fixture

The source is a catalog export zip. Unzip it once into a directory that will be treated as read-only:

```shell
mkdir -p /var/tmp/senesi-bench/pristine
python3 -c "
import zipfile
with zipfile.ZipFile('<export>.zip') as z: z.extractall('/var/tmp/senesi-bench/pristine')
"
```

`PRISTINE_DIR` must be the **parent** of the `<CATALOG>/` folder. The harness copies it into a
disposable working directory on every run and the embedded engine only ever opens that copy, so the
snapshot is never mutated — but it is also never re-verified, so keep the zip as the source of truth.

`WORK_DIR` is wiped twice per run — once before the copy, once at teardown — so the harness deletes it
only after proving it is its own: absent, empty, or carrying the `.evita-warmup-workdir` marker file it
drops there. Point `WORK_DIR` at a directory holding anything else and the run refuses to start instead
of wiping it.

Key system properties (full list in the class JavaDoc):

| property | meaning |
|---|---|
| `evita.warmup.pristineDataDir` | **required** — parent of the `<catalogName>/` snapshot folder |
| `evita.warmup.catalogName` | source catalog name |
| `evita.warmup.targetMode` | `remote` (default, profileable) or `embedded` (transport-free control) |
| `evita.warmup.targetCatalog` | catalog to create and populate |
| `evita.warmup.maxPerCollection` | cap per collection, `0` = unlimited — use a small value to smoke-test |
| `evita.warmup.collections` | comma-separated subset, for isolating one collection |
| `evita.warmup.perEntityCsv` | one CSV row per upsert |
| `evita.warmup.holdOpenSeconds` | keeps the JVM alive after the report so a profiler can dump |

## 3. Running

```shell
RUN=my-label .claude/skills/warmup-reindex-benchmark/run-warmup-reindex.sh
```

**Run it backgrounded and let the harness wake you** — a full production catalog takes tens of minutes.
Never poll for completion with `ps`: a finished JVM lingers as a zombie and `ps` still sees it. Detect
by content — the script prints `RUN_STATUS=MEASUREMENT-DONE-<run>` once the report is out, then exits
with the loader's own status. The marker says the run *finished*; the exit code says whether it
finished *well* (§4).

Smoke-test the whole chain first, it costs a minute:

```shell
MAX_PER_COLL=200 RUN=smoke .claude/skills/warmup-reindex-benchmark/run-warmup-reindex.sh
```

## 4. What the report separates, and why

Four things that are routinely conflated:

- **load wall-clock** — read + upsert + loop overhead: how long the rebuild actually takes.
- **upsert (pure)** — the sum of the individual `upsertEntity` calls, with mean/median/p95/p99.
- **source read** — every source fetch, reported so it can be subtracted rather than silently
  inflating throughput.
- **goLive → ALIVE** — measured separately, **never** folded into the per-upsert statistics.

Schema reconstruction happens before the clock starts and is excluded deliberately.

**A failed goLive still prints a duration.** When the transition throws client-side the report marks it
`*** CLIENT-SIDE FAILURE ***` and the figure is *time to failure*, not transition time — adding it to
the load and calling the sum a publishing time is wrong. This is not hypothetical: it is exactly what
issue #1388 produced, a 15.0 s "goLive" that was Armeria's response timeout expiring. Check the marker
before quoting a TOTAL, and check the **server** log for `is now alive!` — the transition may have
completed server-side regardless.

**Three exit codes, because there are three outcomes.** After the report the harness re-queries both
catalogs and compares per-collection entity counts, and says what it found in its status:

| exit | meaning |
|---|---|
| `0` | counts verified against the source — or verification switched off by `SKIP_VERIFY=true`, which is the operator declining it on purpose |
| `2` | the load finished and its figures stand, but verification never got its answer (target gone, session refused, catalog terminated by a client-side goLive timeout). Counts **unknown**, not known-bad |
| `1` | the run failed: counts compared and genuinely differ — a short load, which is both wrong and flatteringly fast — or something else aborted it |

Both `run-warmup-load.sh` and `run-warmup-reindex.sh` propagate the loader's status, so automation can
gate on the exit code alone: accept `0`, reject `1`, and treat `2` as *measurement present,
completeness unproven* — never as either. An unverified load is exactly the kind of run that quietly
becomes a baseline.

## 5. Comparing two builds

- **Never compare runs taken at different heaps.** Heap size feeds the collation-key cache's
  heap-derived default sizing, so it changes the code path, not just the GC pressure. Pin
  `SERVER_XMX` and `READER_XMX` and state them next to any number.
- **The GC share is reported for the reader JVM only.** In `remote` mode the writer's GC has to be read
  from the server's own GC log (`<BENCH_DIR>/server/<run>/gc.log`); the script prints the young/full
  counts at teardown.
- **A single run resolves about ±9 %, no better.** Two runs of *identical* code differed by 8.5 % on the
  dominant collection, while another same-code pair agreed to 0.2 % — so the spread is not a fixed
  property of the harness, and **what causes it is not known**. Page-cache state on the multi-GB
  pristine snapshot was the obvious suspect, but the `a808b4e46` run was taken eight minutes after a
  fresh unzip and came out fastest of all, which does not fit. Until someone pins it down, treat any
  single-run gap under ~9 % as unresolved rather than reaching for an explanation — and prefer two runs
  per build when the answer actually matters.
- Numbers are not comparable to the gRPC baseline in
  `documentation/adr/2026-07-27-write-path-performance-tuning/`: that harness also *read* over gRPC.

## 6. Reference measurements

Senesi production export (386,369 entities; 118,772 `Product`, which dominates), writer 23g, reader
14g/8g, all collections, `storage.compress=true`, traffic recording and cache off, loopback gRPC.

Every cell below is a figure the harness printed. Where a run's total was not isolated from the logs
the cell is empty rather than reconstructed — a table that mixes measured and derived numbers under
one header is how a wrong baseline gets quoted with confidence.

| build | Product load | total load | goLive | TOTAL |
|---|---|---|---|---|
| v2026.2.0 | 1,158.1 s | 1,239.5 s | 30.1 s | 1,269.6 s |
| v2026.2.0 | 1,059.9 s | 1,142.9 s | 29.8 s | 1,173.3 s |
| v2026.2.2 | 1,009.7 s | — | — | — |
| v2026.2.2 | 1,007.7 s | 1,084.6 s | **failed @ 15.0 s** ¹ | — ¹ |
| dev @ `a808b4e46` | 870.4 s | 978.7 s | 27.7 s | **1,006.4 s** |

**Compare on Product**: it is the only column measured identically in all five runs, and at ~89 % of
load time it is what moves the total anyway.

¹ issue #1388 — the retry decorator froze Armeria's 15 s response timeout at call start, so goLive
could not outlive it. Fixed in PR #1389; a goLive that *completes* is part of what this benchmark
verifies, not just how long it takes.

`a808b4e46` carries the PR #1395 write-path schema/cardinality optimizations on top of v2026.2.2:
Product mean upsert 8,281.6 → 7,115.5 µs (−14.1 %), and the writer took **0 full GCs** across the
whole load. Its goLive is the first in this table that both completed *and* was measured post-#1388.

## 7. Two ways a run silently lies

1. **`evitaDB treats every storage subdirectory as a catalog.`** Leftovers in `TARGET_DATA_DIR` from an
   earlier run can be picked up as catalogs. `run-warmup-reindex.sh` passes `RESET_DATA=true`; the
   standalone server script does not by default, and only wipes paths under `/var/tmp` or `/tmp`.
2. **Both JVMs must fit the cgroup.** Writer 23g + reader 14g is 37 GiB of heap before overhead. Check
   `/sys/fs/cgroup/memory.max` — the server script prints it in its header. An undersized heap turns
   this workload GC-bound and you end up measuring the collector.
