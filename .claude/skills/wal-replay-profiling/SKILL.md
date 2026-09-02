---
name: wal-replay-profiling
description: Measure evitaDB write-path (commit/transaction) performance by replaying a real production catalog's Write-Ahead Log against an embedded instance via the WalReplayBenchmark JMH harness, with async-profiler (alloc/cpu) or JMH `-prof gc`. Invoke when profiling commit latency, allocation, or GC behavior on the write path, when a production WAL export needs re-measuring, or when a profiling run produced a suspicious/empty/inconsistent result (zero-byte collapsed dump, contended-box numbers, cross-round contradiction).
allowed-tools: Read, Edit, Write, Grep, Glob, Bash(mvn *), Bash(java *), Bash(git *), Bash(rg *), Bash(awk *), Bash(ps *), Bash(uptime *), Bash(free *), Bash(kill *), Bash(cd *), Bash(ls *), Bash(du *), Bash(tar *), Bash(diff *), Bash(cp *), Bash(mkdir *), AskUserQuestion
---

# WAL-replay performance profiling

Distilled from the #760 write-path tuning rounds (2026-07-20 → 07-27; see
`documentation/adr/2026-07-27-write-path-performance-tuning/`). The harness (`WalReplayBenchmark` +
`WalReplayState`, package `io.evitadb.performance.walreplay`) is dataset-agnostic — it takes the
catalog name as a system property, not baked in — so this skill applies to any future production
export, not just the original one.

## Bundled scripts

All three are in this skill directory and expect `ROOT=/www/oss/evita/evitaDB-dev` (edit the
`ROOT=` line if the checkout moves). All require `CATALOG_NAME=<name>` in the environment.

- **`run-wal-replay.sh`** — `dry` (plain `main()`, fast iteration) or `jmh` (real `-prof gc`
  measurement) mode.
- **`profile-wal-replay-ap.sh`** — attaches async-profiler for the replay window only.
- **`run-wal-replay-ab.sh`** — one side of an A/B, given two jars and per-side WAL source copies.

## 1. Building

```shell
mvn clean install -P full -DskipTests
```

`-P full` is mandatory — `evita_test/evita_performance_tests` is not in the default reactor.
A full build produces a long log that a tool harness may truncate; when the tail is missing, confirm
by exit code and `target/benchmarks.jar`'s timestamp rather than by grepping for `BUILD SUCCESS`.

To rebuild only the perf module afterwards (upstream already installed): `-o -P full package -pl evita_test/evita_performance_tests -DskipTests`.

**Gate the jar before spending a measurement on it.** Extract the class you changed and assert the
change is really there:
```shell
unzip -o -q target/benchmarks.jar "io/evitadb/.../Changed.class" -d /tmp/jarcheck
javap -c -p /tmp/jarcheck/io/evitadb/.../Changed.class | grep -c <expected-or-forbidden-symbol>
```

## 2. Reproducing the fixture

Requires a fresh catalog export unzipped into two siblings, each containing a `<catalogName>/`
subfolder: `pristine/` (T0 snapshot) and `walsource_full/` (WAL slice to replay).

Key system properties (full list in `WalReplayState`'s own JavaDoc):

| property | meaning |
|---|---|
| `evita.replay.catalogName` | **required** — the catalog name; both directories must contain a matching subfolder |
| `evita.replay.pristineDataDir` | T0 snapshot, copied into the work dir on setup |
| `evita.replay.walSourceDir` | WAL slice(s) to replay, comma-separated, consumed left to right |
| `evita.replay.workDir` | scratch; the fixture wipes and repopulates it itself |
| `evita.replay.maxTransactions` | transaction cap — **the JMH path does not set this**, pass it explicitly |
| `evita.replay.holdOpenSeconds` | keeps the JVM alive after the result block so an attached profiler can dump |
| `evita.replay.waitForVisibility` | block on trunk incorporation per commit — measures the true empty-pipeline floor |
| `evita.replay.perTxCsv` | one CSV row per transaction (mutation count + every stage latency) |

Pin `-Xmx24g -Xms8g` on every run; heap size drives the collation cache's heap-derived default.

**The WAL source is not mutated by a run** — three independent sources stayed byte-identical to
their origin after full 300-tx replays, so the fixture copies into its work dir. Still worth a
`diff -rq` before a run costing real wall-clock time, since the driver only checks the directory
exists.

## 3. What each instrument can and cannot answer

| question | instrument | notes |
|---|---|---|
| did allocation change? | JMH `-prof gc` → `gc.alloc.rate.norm` | primary metric; also compare `gc.count`/`gc.time` — **unit-free**, no conversion needed |
| where does allocation come from? | async-profiler `alloc` | valid even on a loaded box (§5) |
| where does CPU go? | async-profiler `cpu` | needs a quiet box |
| wall / latency | JMH single shot | ±2.0 % noise band on identical builds — make no claim inside it |

Single-shot JMH means one sample, no variance estimate. `gc.alloc.rate.norm` is steadier but not
deterministic — a few percent of drift is normal. `wall` **profiling mode** (not JMH wall time) is
useless on this harness: it samples ~160 parked threads, flattening every share to ~0.6%, and the
thread doing most of the allocation resolves to unresolved `libc` frames. Use `cpu`.

## 4. Reading a collapsed profile

- Frames use **`/` package separators** (`structure/Attributes.<init>`), not `.` — a `.`-pattern
  silently matches nothing.
- Line format is `stack<space>count`; thread names contain spaces, so take `$NF` as the count.
- In an **`alloc`** profile the leaf **is the allocated type**. Attribute a site by the deepest
  `io/evitadb/` frame; attribute *what* was allocated by the leaf — doing both separates removable
  machinery from irreducible cost.
- In a **`cpu`** profile the leaf is a method, so trim trailing whitespace when a site can also be
  the leaf, or it splits into two rows and halves its apparent share.
- Samples are **byte-weighted** in alloc mode — a leaf's share is its share of bytes, not events.
- `mawk` (the default `awk` here) has **no `asorti`** — emit `KIND<TAB>count<TAB>name` and rank
  with `sort -rn`, not an awk-side sort.

## 5. Four ways a run silently lies

1. **The profiler dump races JVM exit.** The harness tears down within seconds of printing its
   result block — too fast for a fresh `profiler stop` JVM to connect. Fixed via
   `evita.replay.holdOpenSeconds` (both bundled profile scripts already set it and check for a
   non-empty dump); if writing a new driver, do the same or the profiler leaves a **zero-byte**
   `.collapsed` while every exit code still says success.
2. **`mvn clean` deletes `target/profiling`.** Copy collapsed files out of `target/` immediately
   after a run that matters.
3. **The box is not quiet.** Check with `uptime` and `ps -eo pcpu,args --sort=-pcpu` *before*
   measuring. A foreign JVM from another session/worktree eating cores is the usual culprit —
   process alive, work dead, invisible to a liveness check. **Allocation shares are usable from a
   loaded box; wall time and CPU shares are not** — empirically: 231 s vs 145 s wall (loaded vs
   quiet, −59%) but 375,600 vs 366,349 allocation samples (−2.5%). Alloc-profiling overhead on a
   *quiet* box is only ~2.6%; a profiled run far slower than that means a loaded box, not the
   profiler.
4. **Cross-run comparison without controls.** When total allocation falls, every surviving site's
   *percentage* inflates and looks like a regression. Find 3-4 sites no change touched and check
   their **absolute** sample counts match before trusting a percentage comparison.

## 6. Interpretation rules that cost rounds to learn

- **Size a finding before ranking it.** A profile share is not a realizable saving — TLAB-sampled
  profiles rank candidates well and predict magnitude poorly.
- **GC cost tracks what SURVIVES, not allocation volume.** Cutting a third of allocation can buy a
  fraction of that off GC if what was deleted was short-lived young-gen garbage. GC's *share* of
  CPU can even rise while its absolute cost falls, if the application side shrinks faster.
- **Structure sharing moves latency; deleting an allocation site moves bytes.** Lead with
  visible-latency median for the former, `gc.alloc.rate.norm` for the latter.
- **Estimating a lazy-allocation residual: check how often the guard actually passes** — a reserved
  "still allocated in the common case" residual can be wrong by an order of magnitude if the common
  case is actually the empty one.
- **Pre-register the acceptance metric — and which metric will NOT move.** A metric that shouldn't
  move but reads as a miss if you didn't say so in advance (e.g. a fix that only affects big
  transactions won't move the small-tx floor).
- **An `Enum.ordinal`-style leaf in a hot loop is usually the inlined loop body**, not the accessor.
  Prove the frame is real (e.g. by removing the memoization and re-measuring) before optimizing it.

## 7. JMH pitfalls specific to this harness

- **A JMH run whose `@Setup` throws still exits 0** with an empty result table. Assert the result
  JSON has rows before believing a run.
- **evitaDB treats every storage subdirectory as a catalog**, and each boot leaves a UUID-scratch
  directory behind — a second fork in the same JMH run aborts with `Catalog `<uuid>` has invalid
  format`. Sweep those directories between runs; use one JVM per benchmark for suites that fork.
- Never hardcode a JDK path — `JAVA_BIN`/`JAVA_HOME` env vars, falling back to a default, is what
  the bundled scripts do; a hardcoded `/usr/lib/jvm/...` path breaks on any machine using sdkman.

## 8. Server-side diagnostic runs (boot/replay forensics, not JMH)

For investigating a specific corruption/crash against a real snapshot rather than measuring
throughput: launch `evita-server.jar` with JDWP
(`-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:8005`),
`-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints`, and a diagnostic logback raising
`io.evitadb.core.transaction` / `io.evitadb.core.catalog.Catalog` to `DEBUG` while pinning
`com.linecorp.armeria`/`io.netty` to `ERROR` and `io.micrometer` to `OFF`. Useful knobs:
`cache.enabled=false`, `server.trafficRecording.enabled=false`,
`server.closeSessionsAfterSecondsOfInactivity=0`.

**Point `storage.storageDirectory` at a scratch copy, never at the live `../data`.** Opening a real
catalog with branch code can trigger a one-way format migration, and a T0 baseline must stay intact
if it is also the source for a WAL replay.
