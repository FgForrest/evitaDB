---
title: Attribute post-discard traffic to the real discard reason via a side map, not a session tombstone
date: 2026-07-24
updated: 2026-07-31 21:20
status: accepted
kind: fix
issues: [1314]
prs: [1315]
areas: [evita_store/evita_traffic_engine]
supersedes: []
superseded-by: []
relates: [2026-07-18-traffic-recording-on-demand-export]
---

# Attribute post-discard traffic to the real discard reason

When the traffic recorder discards a session under resource pressure, any activity that arrives
for that session *afterwards* used to be counted as benign `SAMPLING`. A side map remembers the
real discard reason (`MEMORY_SHORTAGE` / `SERIALIZATION_ERROR`) from discard until close, so the
trailing tail is booked against the reason that actually caused it.

## Why

`discardSession(...)` removes the session from `trackedSessionsIndex`, and once it is gone nothing
remembers *why* it vanished. Both call sites that handle an unknown session id hard-coded
`SAMPLING`, so genuine resource-pressure fallout was partly masked as ordinary sampling in the
`io_evitadb_store_traffic_skipped_records{reason="SAMPLING"}` series — precisely the signal an
operator reaches for when diagnosing off-heap pressure.

There was a second-order effect: `computeCurrentSamplingRate()` uses the `SAMPLING` miss counter
as its denominator term, so trailing records from a *failed* session were nudging the admission
gate that decides how much traffic to record next. PR #1313 had already excluded the bulk
failure-drop from that rate; only the trailing tail still leaked.

The constraint that made this non-obvious: the reason must survive from discard to close, but the
recorder had just been through an observability rework (#1313) that trimmed the recording hot path
and added an `activeSessions` gauge fed directly by `trackedSessionsIndex.size()`. Any memory of a
dead session either costs something on the hot miss path or corrupts that gauge.

### Previous state

`discardSession` freed the session's off-heap buffer and removed the index entry in one step.
Afterwards `doRecord(...)` and `closeSession(...)` both saw `SessionTraffic == null` and
incremented `missedRecordsByReason[SAMPLING]`. `SessionTraffic` carried a `FinishReason` — a lossy
three-value proxy with no `SERIALIZATION_ERROR` — and no `TrafficRecorderMissReason` field at all.

## Options considered

Every viable fix has to retain the discard reason keyed by session id from discard until close (or
degrade gracefully). The options differ in *where* that memory lives.

### Option A — side discard-reason map (chosen)

A dedicated `Map<UUID, TrafficRecorderMissReason>` populated in `discardSession`, consulted in the
two miss branches, evicted on close. `trackedSessionsIndex` keeps meaning "live sessions only".

- **Pros:** the `activeSessions` gauge needs zero change and stays correct by construction;
  off-heap memory is still freed at discard exactly as before; only a `UUID → enum` entry is
  retained (~64–80 B) instead of a whole session; trivially bounded by capping the map;
  `SessionTraffic` semantics untouched; start-failure discards (which never entered the index) get
  attributed for free, since the map is keyed by session id regardless of admission.
- **Cons:** threads `sessionId` through `doRecord` (mechanical, six call sites that already hold
  it); adds a lookup on the miss branch — which is the *common* path whenever sampling < 100 %,
  and #1313 had just trimmed that path. Mitigated by guarding with `isEmpty()`: the map is empty
  except during the rare windows when a discard is outstanding, so the healthy steady state pays
  one `isEmpty()` check and never probes the map.

### Option B — tombstone the `SessionTraffic` in the index (declined)

Stop removing at discard; leave the finished `SessionTraffic` in `trackedSessionsIndex` as a
tombstone carrying a new `discardReason` field, and let the existing null/finished routing read it.

- **Pros:** no `doRecord` signature change; single source of truth, no second map; genuinely zero
  extra hot-path cost — a sampled-out session simply has no tombstone, so the common miss path is
  untouched (the mirror of A's main con).
- **Cons:** the `activeSessions` gauge must be reworked or it silently over-counts live sessions —
  new code on a metric #1313 had just shipped; retains a dead `SessionTraffic` holding a dangling
  pooled-buffer reference inside the *live* index until close, unbounded if the close never
  arrives (a live index cannot be capped); introduces a double-free hazard on the tombstone-close
  path; and start-failure discards never entered the index at all, so they would need extra work.
- **Rejected because:** it buys a few nanoseconds on the miss branch by taking on a gauge rework,
  an unbounded retention path and a double-free hazard — all in code #1313 had shipped days
  earlier. The saving is unmeasurable; the blast radius is not.

## Decision

**Chosen: Option A.** The deciding driver was blast radius on code that had just changed: B's only
real advantage is a few nanoseconds on the miss branch, and it buys them by taking on a gauge
rework, an unbounded retention path and a double-free hazard — all on the metrics #1313 had
shipped days earlier. A's cost is a mechanical thread-through the callers already satisfied, and
its `isEmpty()` guard makes the hot-path difference vanish whenever no discard is outstanding,
which is almost always.

B becomes the better option only if the miss branch ever turns out to be hot enough that a single
`isEmpty()` volatile read is measurable, *and* the `activeSessions` gauge is decoupled from
`trackedSessionsIndex.size()` for some other reason. Both would need to be true.

## Key technical details

All in `evita_store/evita_traffic_engine/.../store/traffic/OffHeapTrafficRecorder.java`:

- `discardedSessionReasons` — a `ConcurrentHashMap<UUID, TrafficRecorderMissReason>`, built via
  `CollectionUtils.createConcurrentHashMap(64)` per project convention. Concurrent because
  `discardSession` and the `record*` methods run on different threads.
- `MAX_DISCARDED_SESSION_REASONS = 1024` — a **best-effort soft cap**, not a strict bound: the
  guard reads a non-atomic `mappingCount()` snapshot. On overflow, trailing records degrade to
  `SAMPLING` rather than growing without limit.
- **Ordering invariant:** `discardSession` puts into the reason map *before* removing from
  `trackedSessionsIndex`. Reversing this opens a window where a concurrent trailing record sees
  neither and leaks back to `SAMPLING`.
- `doRecord` takes `sessionId` explicitly and resolves the reason only when the map is non-empty —
  `isEmpty() ? SAMPLING : getOrDefault(sessionId, SAMPLING)`. It must **not** evict here; one
  discarded session can produce several trailing records.
- `closeSession` uses `remove(sessionId)` — the close is the natural eviction point.
- `close()` clears the map.
- `registerRecordMissedOut()` is a no-op for discarded sessions under this design (the session is
  `null`). Harmless — a discarded session never emits its SessionClose missed-out count — but it
  looks like a dropped concern on review, so it is called out here.

The public `TrafficRecorder` API is unchanged; `doRecord` is private, so there is no downstream
impact.

## Verification

Written test-first: `OffHeapTrafficRecorderTest#shouldAttributePostDiscardTrailingRecordsAndCloseToDiscardReason`
forces a `MEMORY_SHORTAGE` discard mid-session, then drives trailing `record*` calls plus
`closeSession` on the same session id. RED confirmed against unmodified code (`MEMORY_SHORTAGE`
delta *expected 2 but was 0*), GREEN after the fix.

The same test asserts `samplingDelta == 0`, which is what proves the sampling-rate side effect: the
`SAMPLING` counter is the sole miss term of `computeCurrentSamplingRate()`'s denominator, so a zero
delta means trailing failure records can no longer inflate the rate.

Both traffic test classes pass under a full parallel run — `OffHeapTrafficRecorderTest` 14/14 (one
pre-existing skip), `OffHeapTrafficRecorderMetricsTest` 6/6. The parallel run matters: JFR
recordings are JVM-wide, so an isolated green does not prove absence of capture pollution.

## Consequences & open follow-ups

- Failure-trailing records no longer feed the admission gate — an intended behavioural change, not
  just a metrics relabel.
- No generated-documentation regeneration was needed: this adds no event, metric, label or
  `EvitaJfrEventRegistry` entry, it only shifts existing counters between existing `reason` values.
- The cap is soft by design. If discard storms ever exceed 1024 outstanding-but-unclosed sessions,
  attribution silently degrades to `SAMPLING` — acceptable, but it means the metric under-reports
  exactly when pressure is most extreme.

## Related work

- **PR #1313** (traffic-recorder observability rework) — the direct predecessor. It introduced
  `TrafficRecorderMissReason`, the `activeSessions` gauge and the hot-path trimming that shaped
  both options here.
- **`2026-07-18-traffic-recording-on-demand-export`** (#1282) — same recorder subsystem, its JMH
  baseline is the reference for the hot-path costs weighed above.

## Timeline

- **2026-07-24** — issue #1314 filed as an enhancement, milestone 2026.2; explored, both options
  costed, Option A chosen and implemented test-first
- **2026-07-24** — PR #1315 merged (`e36d11c62` fix, `ad20e58a1` review nits: soft-cap wording,
  `CollectionUtils.createConcurrentHashMap`, `mappingCount()`)
- **2026-07-31** — planning document retired, replaced by this record
