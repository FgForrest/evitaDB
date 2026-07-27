# Issue #1314 — attribute post-discard trailing records/close to the discard reason, not SAMPLING

> **Status: DECIDED — implementing Option A** (side discard-reason map). The A-vs-B comparison and the
> cost profile below are retained as the rationale (useful for the PR description and review). Part 0
> (isolated worktree + version) still applies.

## Context

Follow-up to PR #1313 (traffic-recorder observability rework, already merged to `dev`). After a
session is discarded by `discardSession(...)` under real resource pressure (`MEMORY_SHORTAGE` /
`SERIALIZATION_ERROR`), the session is removed from `trackedSessionsIndex`. Any **subsequent**
activity for that session id is then mis-attributed to the benign `SAMPLING` reason:

- trailing `record*` calls fall into `doRecord(...)`'s miss branch (`SessionTraffic == null`) →
  `missedRecordsByReason[SAMPLING]++`
- the eventual `closeSession(...)` hits the "not tracked" branch → `missedRecordsByReason[SAMPLING]++`

Two consequences: (1) genuine resource-pressure fallout is partly masked as benign sampling in the
`io_evitadb_store_traffic_skipped_records{reason="SAMPLING"}` series; (2) that trailing activity
secondarily feeds the `SAMPLING` term of `computeCurrentSamplingRate()`'s denominator. #1313 already
excluded the *bulk* failure-drop from the rate (the whole in-flight `recordCount` is booked under the
failure reason at discard time); only the *trailing tail* leaks. Issue: enhancement, milestone 2026.2.

The goal: recover the original discard reason for a session id and attribute its trailing
records/close to that reason, plus a regression test. This is a small, contained correctness fix.

---

## Part 0 — Isolated worktree + isolated Maven version (do FIRST, same as #1313)

**Critical wrinkle discovered during exploration:** this local checkout at
`/www/oss/evita/evitaDB-dev` is **stale** — the #1313 merge is on **remote** `dev` but NOT in the
local repo (verified: `TrafficRecorderMissReason.java` exists on neither local `HEAD` nor the local
`origin/dev` ref, but IS present on remote `dev` via the GitHub API). So the new worktree must branch
from a **freshly fetched** `origin/dev`, or it will start from pre-#1313 code and the whole feature
base will be missing.

Also note: `/www/oss/evita/evitaDB-mnae` (branch `mnae-traffic-metrics`, the old #1313 worktree)
still exists at `81a8719b8` and still carries the MNAE version bump. It is used **read-only** during
planning as the authoritative post-#1313 source. Decision point below: reuse vs. fresh.

Steps (all are execution-time; NOT run during planning):

1. **Fetch remote so the merged #1313 is available locally** (local refs are stale):
   ```
   git -C /www/oss/evita/evitaDB-dev fetch origin dev
   ```
2. **Create a dedicated worktree off the fetched `origin/dev`** (fresh branch, distinct path):
   ```
   git -C /www/oss/evita/evitaDB-dev worktree add -b 1314-post-discard-reason \
       /www/oss/evita/evitaDB-1314 origin/dev
   ```
   Do all work in `/www/oss/evita/evitaDB-1314`.
3. **Rename the reactor version** to a private GAV so `mvn install` never clobbers another agent's
   `~/.m2` artifacts (the stale-m2-jar trap). Use a fresh tag distinct from the still-present MNAE
   one:
   ```
   rtk mvn -f /www/oss/evita/evitaDB-1314 versions:set \
       -DnewVersion=2026.2.PDR-SNAPSHOT -DgenerateBackupPoms=false -DprocessAllModules
   ```
   (PDR = post-discard-reason.) Keep this as a single dedicated commit at the base of the branch.
4. **Restore the version before opening the PR** (rebase the bump out, or `versions:set` back to
   dev's current release version) so the merge carries only the feature change.

*(Cleanup housekeeping, optional: the old `/www/oss/evita/evitaDB-mnae` worktree + `mnae-traffic-metrics`
branch are now dead — #1313 is merged. Decide whether to `git worktree remove` it to avoid confusion.
Not required for this task.)*

---

## What exploration established (facts both options build on)

All line refs are the merged #1313 code in `/www/oss/evita/evitaDB-mnae/.../store/traffic/`.

- **Two mis-attribution sinks, both hard-coded to `SAMPLING`:**
  - `doRecord(...)` else branch — `OffHeapTrafficRecorder.java:969` (trailing `record*` after discard)
  - `closeSession(...)` else branch — `:403` (the post-discard close)
- **Root cause:** both `discardSession` (`:868`) and `closeSession` (`:367`) *remove* the session from
  `trackedSessionsIndex` (`Map<UUID,SessionTraffic>`, `:139`); afterwards a `get`/`remove` returns
  `null` with **no memory of why** the session vanished.
- **`discardSession(SessionTraffic, TrafficRecorderMissReason reason)` already carries the real
  reason** (`:858`); its callers pass `MEMORY_SHORTAGE` / `SERIALIZATION_ERROR` (`:347/349/387/389/956/959`).
  So the reason is *known at discard time* — it's just discarded along with the index entry.
- **All 6 `record*` methods already hold `UUID sessionId`** and resolve `trackedSessionsIndex.get(sessionId)`
  themselves (`recordQuery :421`, `recordFetch :480`, `recordEnrichment :504`, `recordMutation :525`,
  `setupSourceQuery :547`, `closeSourceQuery :576`) — so threading `sessionId` into `doRecord` is a
  mechanical 6-site edit, no new plumbing.
- **`SessionTraffic` already goes "finished" on discard:** `discard()` sets `finished=DISCARDED`
  (`SessionTraffic.java:295`), `isFinished()` returns `finished != null` (`:332`), and `doRecord`'s gate
  is `sessionTraffic != null && !sessionTraffic.isFinished()` (`:952`) — a retained discarded session is
  therefore *write-safe* (it can never re-enter `record()`). But its buffer is already freed to the pool
  at discard (`:859/863-866`), so a retained instance holds a **dangling pooled-buffer reference**.
  There is **no** existing `TrafficRecorderMissReason` field on it (`FinishReason` is a lossy 3-value
  proxy with no `SERIALIZATION_ERROR`).
- **`activeSessions` gauge's sole feed is `trackedSessionsIndex.size()`** (`:1067` →
  `TrafficRecorderStatisticsEvent.java:142`).
- **Shared side effect (both options):** reattributing the trailing tail out of `SAMPLING` also removes
  it from `computeCurrentSamplingRate()`'s denominator (the `SAMPLING` counter, `:887`) and from
  `setSamplingPercentage`'s baseline (`:310`). This is the *intended* secondary win (failure-trailing
  records stop nudging the admission gate), but it is a behavioral change and must be asserted by a test.
- **Reusable test fixture:** `OffHeapTrafficRecorderTest.shouldReturnMemoryBlocksWhenSessionDiscardedOn
  MemoryShortage` (`:955`) admits a session then forces a mid-session `MEMORY_SHORTAGE` discard — extend
  it with a trailing `recordFetch` + `closeSession` and assert the trailing miss lands in
  `MEMORY_SHORTAGE`. Helpers: `newIsolatedRecorder(...)`, `readReasonCounter(recorder, "missedRecordsByReason", reason)`,
  `captureReasonBaseline(...)`. (The metrics-test fixture that opens 200 sessions forces the shortage at
  *session start* — those never enter the index, so it can't exercise trailing attribution.)

---

## Design options (decision material)

Every viable fix must **retain the discard reason keyed by `sessionId`** from discard until the session's
close (or give up gracefully). The two options differ in *where* that memory lives.

### Cost profile — allocation & performance first

Discards are rare (only under off-heap block / serialization pressure), so the honest split is
*steady state* (≈always) vs an *under-pressure window* (rare).

**Steady state (healthy, sampling < 100%):**
- Per-record allocation: **zero for both**.
- Per-record CPU: **A** adds one guarded `isEmpty()` on the common miss branch (a few volatile reads;
  **no** map probe while the map is empty, which is ≈always); **B** adds nothing. A's check scales with
  the sampled-out fraction, so it is largest in the high-throughput / low-sampling regime — but stays a
  few nanoseconds.
- Fixed footprint: **A** one empty `ConcurrentHashMap` at construction; **B** +8 B reference field per
  `SessionTraffic`.

**Under-pressure window (discards happening):**
- Allocation per discard: **A** ~1 CHM node (~64–80 B on JVM heap — note the actual constraint is
  off-heap blocks, not heap, so this does not aggravate it); **B** zero (reuses the existing entry).
- Retained per outstanding discard: **A** ~64–80 B (Node + UUID key); **B** the **whole `SessionTraffic`**
  object (hundreds of B–KB, incl. a now-dangling pooled-buffer reference).
- Worst-case leak: **A bounded** (cap the map → degrade to `SAMPLING`); **B unbounded** if `closeSession`
  never arrives (the live index cannot be capped).

**Off-hot-path:** **A** none — `activeSessions` stays O(1) `trackedSessionsIndex.size()`; **B** must
rework the gauge (an O(n)/flush scan or extra per-session atomics), new code on a metric #1313 just
shipped.

**Net:** pure hot-path CPU marginally favours **B** (adds nothing); allocation is a wash in steady state;
retained memory + bounded worst case favour **A**. **Chosen A** — its only hot-path cost is a guarded
check that is ≈free whenever no discard is outstanding, and it stays correct-by-construction on the
`activeSessions` gauge that B would otherwise have to touch.

### Option A — side "discard-reason" map (CHOSEN)

A dedicated `Map<UUID, TrafficRecorderMissReason>` (`discardedSessionReasons`), populated in
`discardSession`, consulted in the two else branches, evicted on close.

- `discardSession` (`:858`): after the existing bookkeeping, `discardedSessionReasons.put(sessionId, reason)`
  (keep the `trackedSessionsIndex.remove` at `:868` exactly as-is — memory still freed immediately).
- `doRecord`: change signature to `doRecord(UUID sessionId, SessionTraffic, factory)` (6 mechanical call
  sites, all already hold `sessionId`); in the else branch book
  `discardedSessionReasons.getOrDefault(sessionId, SAMPLING)` instead of hard `SAMPLING` (`:969`). Do **not**
  remove here (there can be several trailing records).
- `closeSession` else branch (`:403`): book `discardedSessionReasons.remove(sessionId)` (falling back to
  `SAMPLING` when absent) — the `remove` is the natural cleanup.
- **Bounding:** cap the map (e.g. `1024`, FIFO/size-guard); on overflow, oldest trailing records degrade
  gracefully to `SAMPLING`. Under normal operation the map holds only discarded-but-not-yet-closed
  sessions (small), because every admitted session is closed.

**Pros:** `trackedSessionsIndex` stays "live sessions only" ⇒ **`activeSessions` gauge needs zero change**
(correct by construction); memory freed at discard exactly as today; only a `UUID→enum` entry retained;
trivially bounded; no new field on `SessionTraffic`, its semantics untouched.
**Cons:** threads `sessionId` through `doRecord` (mechanical); a second map + an extra lookup on the
`doRecord`/`closeSession` **miss branch — which is the *common* path under sampling < 100%** (a
sampled-out session isn't in the index, so its `record*` calls all land there). #1313 specifically
trimmed recording-path overhead, so this isn't free. **Mitigation:** guard the lookup with
`if (!discardedSessionReasons.isEmpty())` — the map is empty except during the rare windows when a
discard is outstanding, so the steady-state healthy path (no pressure) pays only an O(1) `isEmpty()`
check and never touches the map. With that guard the con is small and A stays recommended.

**Implementation notes (so the implementer doesn't rediscover them):**
- The reason map **must be `ConcurrentHashMap`** — `discardSession` and `record*` run on different threads.
- In `discardSession`, `put` into the reason map **before** the `trackedSessionsIndex.remove` (`:868`) —
  minimizes the window where a concurrent trailing record sees neither and leaks to `SAMPLING`.
- `registerRecordMissedOut()` (`:965`) becomes a no-op for discarded sessions under A (the session is
  `null`) — harmless, since a discarded session never emits its SessionClose missed-out count; call it out
  so it isn't flagged as a dropped concern in review.
- Start-failure discards (`createSession :347/349`) are attributed **for free** under A (the reason map is
  keyed by `sessionId` whether or not the session ever entered the index) — arguably correct, since the
  memory shortage is exactly why nothing recorded.

### Option B — tombstone the `SessionTraffic` in the index + reason field

Stop removing at discard; leave the finished `SessionTraffic` in `trackedSessionsIndex` as a tombstone
carrying the reason, and let the existing null/finished routing read it.

- Add `private TrafficRecorderMissReason discardReason` to `SessionTraffic`, set from `discardSession`.
- `discardSession`: drop the `trackedSessionsIndex.remove` at `:868` (free memory as before, but keep the
  entry).
- `doRecord` else branch (`:969`): `sessionTraffic` is now the non-null finished tombstone → book
  `sessionTraffic.getDiscardReason()`. No signature change.
- `closeSession` (`:367` remove-and-return): the else branch (`:403`) receives the tombstone → book its
  reason; the `remove` cleans it up. **Must not re-free** the buffer (already freed at discard `:859`).
- **Must fix the `activeSessions` gauge** (`:1067`) to exclude finished tombstones (e.g. a separate live
  counter, or count only non-finished entries) — otherwise it over-reports live sessions.

**Pros:** no `doRecord` signature change; single source of truth (no second map); reuses the existing
`if (sessionTraffic != null)` handling in both else branches; **no extra hot-path lookup** — a sampled-out
session simply has no tombstone (`get → null → SAMPLING` directly), so the common miss path is untouched
(the mirror of A's main con).
**Cons:** **must** patch the `activeSessions` gauge (one of #1313's new metrics) or it silently
over-counts; retains a dead `SessionTraffic` (with a dangling pooled-buffer reference) in the *live* index
until close — memory freed less promptly in the never-closed case, and harder to bound than a side map;
new `SessionTraffic` field; a subtle double-free hazard on the tombstone-close path to guard against;
`createSession` start-failures never entered the index (`put` is in `onSuccess` only, `:354`) so a tombstone
can't be placed there without extra work.

### Scope sub-decision (applies to whichever option)

`createSession` start-failure discards (`:347/349`) happen *before* the session is admitted to the index.
**Option A handles these for free** (map keyed by `sessionId` regardless of admission); **Option B would
need extra work** (insert a tombstone on the start-failure path). A session that never recorded anything
has no meaningful "trailing tail", so this is a marginal case either way — just note the chosen behavior in
the PR so it isn't mistaken for an oversight.

### Decision — Option A (chosen)

**Chosen: Option A.** It keeps the live index and its new `activeSessions` gauge pristine, frees off-heap
promptly exactly as today, is trivially bounded, and leaves `SessionTraffic` semantics untouched — at the
cost of a purely mechanical `sessionId` thread-through that the callers already satisfy, plus one
`isEmpty()`-guarded lookup on the miss branch. Option B was declined because it trades that mechanical
edit for subtler hazards (gauge over-count + tombstone double-free) on the very metrics #1313 just added.

---

## Verification (applies to whichever option is chosen)

1. **TDD first** (repo rule): failing test before the fix. New test beside
   `evita_test/evita_functional_tests/.../store/traffic/OffHeapTrafficRecorderMetricsTest.java`
   (or `OffHeapTrafficRecorderTest.java`): force a `MEMORY_SHORTAGE` discard, then drive trailing
   `record*` calls + `closeSession` on that same session id, and assert the trailing activity is
   booked under `MEMORY_SHORTAGE` (not `SAMPLING`). Reuse the unique-catalog-name JFR-isolation
   pattern from the metrics test (JFR recordings are JVM-wide).
2. **Build** the touched module in the isolated worktree at `2026.2.PDR-SNAPSHOT`:
   `rtk mvn -pl evita_store/evita_traffic_engine install` then the functional-tests module.
3. **Full parallel run** of the traffic tests (the only condition that reproduces JFR-global-capture
   pollution) — do not trust an isolated green.
4. **Before commit**: `rtk mvn -P full`. **Before PR**: restore the reactor version.

---

## Implementation status (2026-07-24)

- Isolated worktree `/www/oss/evita/evitaDB-1314` (branch `1314-post-discard-reason`) off freshly fetched
  `origin/dev` (`b4d850c19`, #1313 merged); reactor renamed to `2026.2.PDR-SNAPSHOT` (uncommitted, build isolation).
- **Option A implemented** in `OffHeapTrafficRecorder`: field `discardedSessionReasons` (ConcurrentHashMap)
  + `MAX_DISCARDED_SESSION_REASONS` cap; put-before-remove in `discardSession`; `isEmpty()`-guarded lookup
  in `doRecord`; remove-and-evict in `closeSession`; `sessionId` threaded into `doRecord` at all 6 call
  sites; cleared in `close()`. Public `TrafficRecorder` API unchanged (`doRecord` is private) → no downstream impact.
- **TDD**: new `OffHeapTrafficRecorderTest#shouldAttributePostDiscardTrailingRecordsAndCloseToDiscardReason`
  — RED confirmed against unmodified code (MEMORY_SHORTAGE delta `expected 2 but was 0`), GREEN after the
  fix. Both traffic test classes pass (`OffHeapTrafficRecorderTest` 14/14 (1 pre-existing skip),
  `OffHeapTrafficRecorderMetricsTest` 6/6).
- **Sampling-rate side effect**: covered by the same test — `samplingDelta == 0` and the SAMPLING counter
  IS the sole miss term of `computeCurrentSamplingRate()`'s denominator, so it proves trailing failure
  records can't inflate the rate. No extra assertion needed.
- **No `metrics.md` / JFR-doc regeneration**: #1314 adds no event, metric, label, or `EvitaJfrEventRegistry`
  entry — it only shifts existing counters across existing `reason` values. Generated docs unchanged.
- **Pending**: `-P full` compile check (running); restore `2026.2.RC1-SNAPSHOT` before PR; commit/PR await go-ahead.
