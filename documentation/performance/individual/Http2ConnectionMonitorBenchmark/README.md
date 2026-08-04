# Http2ConnectionMonitor — cost of watching every HTTP/2 connection

**Benchmark source** (the executable counterpart of this document — its class JavaDoc links back here):
`evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/Http2ConnectionMonitorBenchmark.java`

The tables below are the authoritative record — raw JMH JSON is not retained. Re-run the benchmark (command below) to
regenerate the numbers, and update this document whenever the inbound walker or the outbound recognition changes so
the two never drift apart.

## Why this benchmark exists

`Http2ConnectionMonitor` (see [the ADR](../../../adr/2026-08-04-http2-connection-teardown-observability.md)) installs a
handler into **every** child channel pipeline, between the TLS handler and the HTTP/2 codec. Every inbound and every
outbound byte of every connection — gRPC, GraphQL, REST, system API — passes through it. That is the worst possible
place to be wrong about cost, and the design rests on two claims that are cheap to assert and easy to break silently:

1. **The inbound walk is O(frames), not O(bytes).** It reads the 9-byte frame header one byte at a time (so it survives
   frames split across arbitrary socket reads) and then skips the entire payload in a single index jump. A regression
   to a per-byte walk would not fail any test — it would just quietly tax every large response.
2. **Neither direction allocates.** A per-frame allocation on the hot path would be invisible in latency and lethal in
   GC pressure at load.

Both are measured here rather than argued.

## Method

Every operation is an **A/B against the same pipeline**: `monitored = true` installs the monitor, `monitored = false`
runs an otherwise identical `EmbeddedChannel`. Only the **difference** between the arms is the handler's cost — the
absolute numbers include `EmbeddedChannel` plumbing a real connection does not have.

The `payloadSize` sweep (256 B → 16 KiB) is what proves claim 1: it changes the bytes walked per operation by
**48.7×** (2 704 → 131 728 B/op) while leaving the frame count identical at 16.

| operation | shape | what it isolates |
|---|---|---|
| `dataFrames` | 8 × `HEADERS`(64 B) + 8 × `DATA`(`payloadSize`) | the ordinary request path |
| `resetFrames` | 16 × `RST_STREAM`(4 B) | the cancellation storm — the only op where `recordResetFrame` runs |
| `outboundFrames` | one write of 16 × `DATA`(`payloadSize`) | the response path's `GOAWAY` recognition |

**Denominators differ by direction.** The inbound ops push 16 frames whose headers are each parsed, so they divide by
16 for a per-frame figure. `outboundFrames` hands the whole buffer to a **single** `writeOutbound`, so the recognition
runs **once per write** and rejects on the first frame's type byte — that figure is per-write and must not be divided.

The throttled reporting path (log line + JFR event) is deliberately excluded: the monitor used here raises its
reporting threshold out of reach, leaving exactly the code that runs on every connection all the time.

Run environment: JDK 21.0.11 (OpenJDK 64-Bit Server VM, Ubuntu), JMH 1.37, no VM options, 8 × 2 s warm-up,
6 × 2 s measurement, 2 forks, `-prof gc`, on an idle 24-core machine.

## Result — the walk is per-frame, and it does not allocate

### Time (`avgt`, ns/op — lower is better)

| operation | payload | monitored | unmonitored | delta/op | per unit |
|---|---|---|---|---|---|
| `dataFrames` | 256 B | 164.60 ± 2.76 | 52.22 ± 3.47 | +112.4 | **7.0 ns / frame** |
| `dataFrames` | 16 KiB | 167.45 ± 7.80 | 52.54 ± 2.50 | +114.9 | **7.2 ns / frame** |
| `resetFrames` | 256 B | 437.88 ± 5.95 | 50.49 ± 0.86 | +387.4 | **24.2 ns / frame** |
| `resetFrames` | 16 KiB | 434.40 ± 5.08 | 50.74 ± 1.43 | +383.7 | **24.0 ns / frame** |
| `outboundFrames` | 256 B | 69.19 ± 1.26 | 67.98 ± 4.10 | +1.2 | **1.2 ns / write** |
| `outboundFrames` | 16 KiB | 69.49 ± 0.96 | 66.48 ± 0.81 | +3.0 | **3.0 ns / write** |

**Claim 1 holds.** Multiplying the bytes walked by 48.7× moves the inbound cost from 112.4 to 114.9 ns/op —
**+2.2 %**, itself well inside the 16 KiB arm's own error bar. That bounds the per-byte component at roughly
**0.02 ns per KiB skipped**; a per-byte walk would have shown near a 48× increase. `resetFrames` is a control: its
frames are 4-byte-payload regardless of `payloadSize`, so its two rows are an independent repeat measurement of the
same quantity and agree to 0.9 %.

**The reset path's extra 17 ns is one `System.nanoTime()`.** Both paths parse a frame header; the only thing
`resetFrames` does on top is `recordResetFrame`, whose sole non-trivial operation is the clock read for the window
check — and 24.1 − 7.1 = 17.0 ns/frame is exactly that call on this machine. It is deliberately not optimised away:
Netty's own rate limiter pays the same clock read, and a reset means a cancelled request that already cost orders of
magnitude more.

### Allocation (`gc.alloc.rate.norm`, B/op — the regression oracle)

| operation | monitored | unmonitored | delta |
|---|---|---|---|
| `dataFrames` | 24.001 | 24.000 | +0.001 |
| `resetFrames` | 24.002 | 24.000 | +0.002 |
| `outboundFrames` | 152.000 | 152.000 | **identical** |

**Claim 2 holds, with one honest caveat.** The handler allocates nothing — neither the walker, nor
`recordResetFrame` (a clock read and integer arithmetic), nor the outbound recognition (absolute `ByteBuf` getters)
constructs an object. The ≤0.002 B/op residual is **not** the handler: it tracks how much *slower* each arm is,
because a fixed background allocation (JIT, profiler threads) is amortised over fewer operations. The ordering is
exactly what that predicts — 0.000 where the arms run at equal speed, 0.001 at 3.2× slower, 0.002 at 8.6× slower. One
24-byte object per ~12 000 operations is below the profiler's ability to attribute.

Do **not** compare `gc.count` between arms: the monitored arm completes far fewer operations in the same wall clock, so
its collection count is lower for reasons that have nothing to do with allocation per operation.

## Conclusion

The monitor costs **~7 ns per inbound frame**, **~1–3 ns per outbound write**, and allocates nothing measurable. A
typical unary gRPC request is two inbound frames (`HEADERS` + `DATA`), so the monitor adds roughly **14 ns** to a
request whose end-to-end cost is measured in hundreds of microseconds — on the order of 0.01 %. Response size does not
matter, which is the property that makes the design safe on a database that returns large result sets.

This benchmark is the standing guard on both claims. If someone replaces the single-jump payload skip with a per-byte
loop, or adds an allocation to the walker, the `payloadSize` sweep and the `gc.alloc.rate.norm` column are where it
surfaces — nothing in the functional test suite would notice either.

Regenerate with:
`java -cp evita_test/evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main
'io\.evitadb\.spike\.Http2ConnectionMonitorBenchmark' -prof gc`

**Measure on an idle machine and validate the run before quoting it.** The A/B arms run sequentially, not
concurrently, so background load lands on them unequally. The tell is the *unmonitored* baselines: they should come in
near 52 / 50 / 67 ns/op (`dataFrames` / `resetFrames` / `outboundFrames`). A run whose baselines are inflated, or whose
per-fork `rawData` drifts monotonically within a fork, is contended — discard it rather than publishing it.
