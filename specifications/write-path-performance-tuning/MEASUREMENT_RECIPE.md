# Performance measurement recipe

Distilled from the #760 tuning rounds (2026-07-20 → 2026-07-27). The driver scripts these rules came from
have been deleted; they are archived verbatim in `backups/profiles/harness-archive.tar.gz` together with the
JMH harness sources, so nothing here has to be reconstructed from memory.

The individual round write-ups (`SENESI_*`, `ALLOCATION_TRIO_*`, `WARMUP_*`, …) stay as the record of *what*
was measured. This file is the *how*, and specifically the list of ways a run silently lies.

---

## 1. Building

```shell
rtk mvn clean install -P full -DskipTests
```

**`-P full` is mandatory.** `evita_test/evita_performance_tests` is not in the default reactor; without the
profile the build fails with *"Could not find the selected project in the reactor"*, or worse, silently uses a
stale `benchmarks.jar`.

`rtk` truncates long build logs and **drops the `BUILD SUCCESS` line**. Confirm a build by its exit code and
by the artifact timestamp (`target/benchmarks.jar`), never by grepping the log.

To rebuild only the benchmark module afterwards, omit `-am` — upstream is already installed:

```shell
rtk mvn -o -P full package -pl evita_test/evita_performance_tests -DskipTests
```

**Gate the jar before spending a measurement on it.** Extract the classes you changed and assert the change is
really there — a wrong-jar run is indistinguishable from a null result:

```shell
unzip -o -q target/benchmarks.jar "io/evitadb/.../Changed.class" -d /tmp/jarcheck
javap -c -p /tmp/jarcheck/io/evitadb/.../Changed.class | grep -c <expected-or-forbidden-symbol>
```

---

## 2. Reproducing the fixture

The senesi dataset and WAL were deleted once tuning finished. To measure again you need a fresh catalog
backup exported from the live deployment (there is a `senesi` kube context), unzipped into two siblings:

- `pristine/` — the T0 snapshot the fixture boots from
- `walsource_full/` — the WAL slice to replay

The harness is driven entirely by system properties:

| property | meaning |
|---|---|
| `evita.senesi.replay.pristineDataDir` | T0 snapshot, copied into the work dir on setup |
| `evita.senesi.replay.walSourceDir` | WAL slice to replay |
| `evita.senesi.replay.workDir` | scratch; the fixture wipes and repopulates it itself |
| `evita.senesi.replay.maxTransactions` | transaction cap — **the JMH path does not set this**, pass it explicitly |
| `evita.senesi.replay.holdOpenSeconds` | keeps the JVM alive after the result block so an attached profiler can dump |

Pin `-Xmx24g -Xms8g` on every run; heap size changes results (it drives the collation cache size, see §6).

**The WAL source is not mutated by a run.** This was long assumed otherwise and drove per-side copies. Three
sources stayed byte-identical to their origin after full 300-tx replays — the fixture copies into the work
dir. Verifying with `diff -rq` before a run is still cheap insurance, but separate per-side copies are not
required.

---

## 3. What each instrument can and cannot answer

| question | instrument | notes |
|---|---|---|
| did allocation change? | JMH `-prof gc` → `gc.alloc.rate.norm` | primary metric; also compare `gc.count`/`gc.time`, which are **unit-free** and need no conversion |
| where does allocation come from? | async-profiler `alloc` | valid even on a loaded box (§5) |
| where does CPU go? | async-profiler `cpu` | needs a quiet box |
| wall / latency | JMH single shot | ±2.0 % noise band; **make no claim inside it** |

Single-shot means one sample per side and no variance estimate. Empirically measured noise bands on identical
builds: **wall ±2.0 %**, **changes-visible median ±5.5 %**. `gc.alloc.rate.norm` is much steadier but *not*
deterministic — a few percent of drift is normal (concurrent trunk incorporation, JIT, TLAB waste), so do not
call it deterministic and then treat a 2 % move as signal.

`wall` profiling mode is **useless on this harness**: it samples ~160 parked threads, flattening every share
to ~0.6 %, and the thread doing most of the allocation resolves to unresolved `libc` frames. Use `cpu`.

---

## 4. Reading a collapsed profile

- Frames use **`/` package separators** (`structure/Attributes.<init>`), not `.`. A `.`-based pattern silently
  matches nothing and reports 0 %.
- The line is `stack<space>count`, and thread names contain spaces — take `$NF` as the count and everything
  before it as the stack.
- In an **`alloc`** profile the collapsed **leaf is the allocated type**, not a method. Attribute a site by
  the deepest `io/evitadb/` frame; attribute *what* was allocated by the leaf. Doing both is what separates
  removable machinery from irreducible cost.
- In a **`cpu`** profile the leaf is a method, so the deepest-evitaDB-frame site can *be* the leaf — trim
  trailing whitespace or one site splits into two rows and halves its apparent share.
- Samples are **byte-weighted**, so a leaf's share is its share of *bytes*, not of allocation events.
- A self-heavy leaf inside a hot loop is usually the inlined loop body, not an expensive method.

Ranking scripts are archived as `alloc-census.awk` / `cpu-census.awk` in `backups/profiles/`. Note `mawk` is
the default `awk` here and has **no `asorti`** — emit `KIND<TAB>count<TAB>name` and rank with `sort -rn`.

---

## 5. The four silent corrupters

Each of these produces a confident, wrong, green-looking result.

**a. The profiler dump races JVM exit.** The driver watches for the result block to know the replay window
closed, but the harness tears down and exits within seconds of printing it — the `profiler stop` JVM cannot
connect in time and async-profiler leaves a **zero-byte** `.collapsed` while every exit code reports success.
Fixed by `holdOpenSeconds` (the driver passed 90) plus a `[ -s "$COLLAPSED" ] || exit 2` guard. The hold
cannot distort the measurement: replay is finished and the main thread only sleeps, and both modes sample
work, not time.

**b. `mvn clean` deletes `target/profiling`.** A full rebuild destroyed one round's raw profiles, leaving only
percentages in a markdown file — and an anomaly that then could not be reconciled. **Copy collapsed files out
of `target/` immediately after a run.**

**c. The box is not quiet.** Check with `uptime` and `ps -eo pcpu,args --sort=-pcpu` *before* measuring, not
by intuition. An orphaned `evita_long_running_tests` fork from another session — parent `systemd`, launching
shell gone — sat OOM-thrashing on ~10 of 24 cores for nearly two hours producing nothing. Foreign JVMs from
other sessions' worktrees are the usual culprit: process alive, work dead.

**d. Cross-run comparison without controls.** When total allocation falls, every surviving site's *percentage*
inflates and reads as a regression. Before comparing rounds, find 3–4 sites that no change touched and check
their **absolute** sample counts match (they held to ±2.5 %). Only then compare absolutes. If a site's two
instruments disagree on direction, trust neither — that is what happened to the collation figures between
rounds 4 and 5, and it was unresolvable because of (b).

**Bonus, empirically settled:** allocation attribution is **contention-independent**. The same profile run
loaded vs quiet gave 231.1 s vs 145.6 s wall (−59 %) but 375 600 vs 366 349 samples (−2.5 %). Allocation
shares are usable from a loaded box; wall and latency are not. And alloc-profiling overhead on a *quiet* box
is only **2.6 %** (145.6 s profiled vs 142.0 s not) — so a profiled run far slower than that indicates a
loaded box, not the profiler.

---

## 6. Interpretation rules that were learned the hard way

- **GC cost tracks what SURVIVES, not allocation volume.** Cutting a third of allocation bought only a
  seventh off GC CPU, because what was deleted was short-lived young-gen garbage — the cheap kind. Never
  predict a GC-share change from an allocation delta. (GC's *share* can even rise while its absolute cost
  falls, if the application side shrinks faster.)
- **Structure sharing moves latency; deleting an allocation site moves bytes.** Lead with visible-latency
  median for the former, `gc.alloc.rate.norm` for the latter. Predicting the wrong one cost two rounds.
- **Estimating a lazy-allocation residual: check how often the guard actually passes.** A "the collection is
  still allocated in the common case" reservation was wrong by 20× because the common case turned out to be
  the empty one.
- **Write the prediction down before the run.** Attributing the profile by allocated leaf type makes a
  falsifiable number available *before* measuring; that is what turned a 35.2 % prediction into a 34.7 %
  result rather than a post-hoc story.
- The collation cache is sized from the heap (`maxMemory/50/256`, clamped to 1 048 576 slots per locale) and
  is already at that maximum under `-Xmx24g`. Cache *sizing* is no longer a lever.

---

## 7. JMH pitfalls specific to this suite

- **A JMH run whose `@Setup` throws still exits 0** and prints an empty result table. Always assert the
  result JSON has rows before believing a run — the archived query-bench drivers do this in a `python3`
  post-step and it caught real failures.
- **evitaDB treats every sub-directory of the storage directory as a catalog**, and each boot leaves a
  UUID-named scratch directory behind. A second fork in the same run then aborts with
  ``Catalog `<uuid>` has invalid format``. Sweep `????????-????-????-????-????????????` directories between
  runs, and use **one JVM per benchmark** for the query-path suites.
- `java.io.tmpdir` drives `EvitaTestSupport.BASE_PATH`, i.e. the benchmark's storage directory — that is how
  the query benchmarks are pointed at a private catalog copy.
- `EvitaCatalogReusableSetup` does **not** wipe the generated catalog between benchmarks, so only the first
  benchmark of a sweep pays generation cost.
- Never hardcode a JDK path. `/usr/lib/jvm/java-17-openjdk-amd64` does not exist on this machine; JDK 17 comes
  from sdkman. Use `${JAVA_BIN:-${JAVA_HOME}/bin/java}`.

---

## 8. Server-side diagnostic runs

For boot/replay forensics against a real snapshot, launch `evita-server.jar` with JDWP
(`-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:8005`),
`-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints`, and a diagnostic logback that raises
`io.evitadb.core.transaction` and `io.evitadb.core.catalog.Catalog` to `DEBUG` while pinning
`com.linecorp.armeria`/`io.netty` to `ERROR` and `io.micrometer` to `OFF` (archived as `logback-diag.xml`).
Useful knobs: `cache.enabled=false`, `server.trafficRecording.enabled=false`,
`server.closeSessionsAfterSecondsOfInactivity=0`, and `transaction.waitForTransactionAcceptanceInMillis` to
stretch the dangling-record watchdog.

**Point `storage.storageDirectory` at a scratch copy, never at the live `../data`.** Opening a real catalog
with branch code can trigger a one-way format migration, and a T0 baseline must stay intact if it is also the
source for a WAL replay.
