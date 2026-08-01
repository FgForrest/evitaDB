---
title: Fix the gRPC session-cancellation cascade, and move the test-only executor switch off public config into a per-dataset real-pool opt-in
date: 2026-07-16
updated: 2026-07-31 21:50
status: accepted
kind: fix
issues: []
prs: [1284]
areas: [evita_external_api_grpc/client/driver, evita_external_api_core/configuration, evita_engine/core, evita_api/exception, evita_api/configuration, evita_server, evita_test_support, evita_test/evita_functional_tests]
supersedes: []
superseded-by: []
relates: []
---

# Fix the gRPC keep-alive self-kill and session-cancellation cascade

The months-old "random CANCELLED gRPC calls under load" and a newly-observed
`ConcurrentSessionAccessException` (CSAE) cascade in `EvitaClient*Test` shared one root cause: a
1-second HTTP/2 keep-alive ping with its ack deadline coupled to the interval, on connections whose
event loop could be held for seconds by a synchronously-dispatched call. The fix widens the
keep-alive tolerance, makes the driver treat a transport failure as session loss instead of
retrying a remote close, and fixes a real change-data-capture (CDC) ordering bug the investigation
surfaced along the way. A separate, genuinely contested question — how the *test lane* should
execute async work now that real thread pools were on the table — was resolved by keeping the test
default synchronous and opting individual datasets into real pools rather than flipping the whole
suite.

## Why

Two symptoms, one mechanism. In every test JVM, `ServerOptions.directExecutor` defaulted to
`DevelopmentConstants.isTestRun()` (`true`), which swapped the engine's real thread pools for an
`ImmediateExecutorService` — a deliberate determinism choice so async handoffs don't flake under
CPU-saturated parallel test runs. But all 43 gRPC service methods offload their body to
`evita.getRequestExecutor()`, so under the immediate executor that body ran inline on the Armeria
event-loop thread that dispatched the call. A slow call (a multi-second schema update during
dataset setup, a large query) then stalled the loop for every connection pinned to it. Both client
and server pinged every 1000 ms, and Armeria couples the ack deadline to the interval — a
stalled loop misses the ack, and the peer kills its own connection
(`ClosedSessionException` → driver-mapped `CANCELLED`). The driver only marked a session dead
locally on `UNAUTHENTICATED`, so after a CANCELLED it still believed the session active and issued
a *remote* close, racing the still-running orphaned server-side invocation — tripping
`EvitaSessionProxy`'s per-invocation ownership guard as a CSAE.

The constraint that made this non-obvious: production suffers a narrower version of the same
self-kill under GC pauses even with real thread pools (no test-only executor involved), so the
production fix (widen the ping tolerance) and the test-only trigger (the direct executor) needed
separate treatment — and touching the direct-executor switch at all raised a second question with
real trade-offs: if tests start using real pools to avoid this exact bug, does the suite lose the
determinism the direct executor exists for? That second question is this record's genuine fork in
the road; see *Options considered*.

### Previous state

- `pingIntervalMillis` was hard-coded to `1000` on both `EvitaClient` and `ExternalApiServer`, with
  no way to widen it; Armeria's minimum non-zero value is also `1000`, so the interval doubled as
  the entire stall budget.
- `ServerOptions.directExecutor` was a **public, YAML-bound** config field (leaked as a
  user-settable `server.directExecutor` key) defaulting to `isTestRun()` — every test JVM ran
  gRPC bodies inline on the event loop, with no per-dataset control.
- `EvitaClient` deadified a session locally only on `Code.UNAUTHENTICATED`; any other transport
  failure left `isActive()` true, so the try-with-resources close raced the orphaned invocation.
- `ClientChangeCapturePublisher.subscribe()` returned before the server confirmed the CDC
  subscription; under the direct executor everything ran serially on one thread, so the resulting
  register-then-mutate race never manifested in tests, masking a real production bug (a mutation
  fired before the subscriber was wired in, or raced the still-pending registration into a CSAE).

## Options considered

The genuinely contested decision — captured verbatim from `A2_ASSESSMENT_REQUEST.md` /
`A2_ASSESSMENT_RESPONSE.md`, an external senior-reviewer assessment retained as the decision record
— was how the **test lane** should execute async work, now that fixing the cascade put real thread
pools in web-API test datasets (a change nicknamed "A2"). Empirical measurement: flipping A2 on
made `CatalogRestUpsertEntityMutationFunctionalTest` fail 9/12 in isolation and the full
`unitAndFunctional` suite (20 017 tests) return 23 failures + 9 errors — all `FieldUndefined`
(GraphQL) / HTTP 404 (REST) on collection endpoints, because the GraphQL/REST API-regeneration
subscriber also moved off the synchronous scheduler and the rebuild-after-schema-change race it
exposed is production-correct but violates tests that assumed instant rebuild.

### Option A — drop A2 entirely; direct executor everywhere; disable the test ping (declined as pure form, adopted as the default posture)

Restores full determinism and zero web-API regressions.

- **Pros:** zero regressions; matches the established "direct executor everywhere in tests"
  philosophy exactly.
- **Cons:** leaves the keep-alive fix, the transport-failure-as-session-loss fix, and the CDC
  ordering fix with **zero integration coverage** of the exact conditions they fix — a future
  regression in any of them would pass CI silently.

### Option B — keep A2 broadly; add async-readiness barriers to the ~23 affected tests (declined)

Await endpoint/schema readiness explicitly after a schema mutation in every affected web-API test
class.

- **Pros:** maximizes fidelity — the whole networked suite runs production-like.
- **Cons:** fights the determinism rule under CPU saturation with unknown blast radius; retrofits
  8+ test classes to work around an async lag that is itself production-correct behavior, not a
  defect.
- **Rejected because:** "the 23 failures are deterministic contract violations against the
  'rebuild is synchronous' promise; retrofitting readiness barriers into 8+ classes buys fidelity
  for a subsystem whose async lag is production-correct, at maximum cost / minimum value."

### Option C — narrow A2 to only the datasets that reproduce the cascade (absorbed into D)

A per-dataset opt-in that keeps a small real-pool island instead of a suite-wide flip.

- **Pros:** targeted; keeps the regression coverage Option A discards.
- **Cons (as originally framed):** left open exactly which datasets and how the opt-in would be
  expressed.
- **Not rejected outright** — the response calls the chosen path "the kernel of (C)": same idea,
  made concrete.

### Option D — hybrid: direct executor as the default, `@DataSet(useRealThreadPools = true)` as a per-dataset opt-in island (chosen)

Revert the blanket `directExecutor(false)` for all web-API test servers; keep
`ServerOptions.Builder.directExecutor(boolean)` as a capability; disable the test-lane ping through
the existing A1 configuration knobs (not a special-cased test-env flag); pin the real-pool-only
behaviours by opting a small, deliberately chosen set of datasets into real pools — the
`EvitaClient*` driver family (already green under real pools) plus the two CDC ordering tests.

- **Pros:** the 23 web-API regressions disappear (they were caused by A2's blanket flip, not by
  anything genuinely needing real pools); the 7 original CSAE/CANCELLED failures and the CDC
  ordering tests stay covered by a real-pool island whose cost is near zero; the island is a
  **deliberate** canary instead of an accidental one.
- **Cons:** two code paths for test execution (direct vs. real) to reason about; the island's
  coverage is only as good as the datasets chosen for it.

## Decision

**Chosen: Option D.** The deciding driver: dropping A2 (pure Option A) leaves the three real fixes
this work stream shipped with no end-to-end guard-rail, while keeping A2 broadly (Option B) spends
disproportionate effort defending a subsystem (API regeneration lag) whose async behavior is
already correct — it's the *test's* assumption of synchrony that was wrong, not the code. A
deliberately small, already-green real-pool island (the `EvitaClient*` datasets) gets the coverage
at negligible cost, without reopening the CPU-saturation flakiness the direct-executor default
exists to prevent.

## Key technical details

**Keep-alive (production fix, applies regardless of the test-lane decision):**
- `evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/config/ClientConnectionOptions.java`
  — `pingIntervalMillis` (`DEFAULT_PING_INTERVAL_MILLIS = 30_000`) and a new, **decoupled**
  `idleTimeoutMillis` (`DEFAULT_IDLE_TIMEOUT_MILLIS = 300_000`); the canonical constructor clamps
  negative → default, `0` → disabled, otherwise `max(x, 1000)` (Armeria's floor).
- `evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/configuration/ApiOptions.java`
  — server `pingIntervalMillis` (`DEFAULT_PING_INTERVAL = 0`, disabled).
- **Invariant a future change must preserve:** Armeria silently drops the ping whenever
  `idle > 0 && ping > 0 && max(ping, 1000) >= idle` — the ping must stay strictly below the idle
  timeout or the watchdog goes inert with no error and no log. `EvitaClient` sets the idle timeout
  explicitly with `keepAliveOnPing = true` and logs a warning mirroring that exact rule.

**Driver — transport failure as session loss:**
- `EvitaClient.isTransportFailure(Throwable)` — classifies `CANCELLED` / `UNAVAILABLE` /
  `DEADLINE_EXCEEDED`, or a cause chain carrying Armeria's `ClosedSessionException` /
  `ClosedStreamException`.
- `EvitaClientSession.terminateLocally()` — completes the close future with no server round-trip
  and flips `isActive()` false, making a subsequent try-with-resources close a local no-op.
- `evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/exception/TransportException.java`
  (extends `EvitaClientServerCallException`) — thrown instead of the previous generic
  `GenericEvitaInternalError: CANCELLED`; Javadoc documents the at-most-once/at-least-once
  ambiguity (the orphaned server-side call may still complete after the client observes failure).
- `evita_api/src/main/java/io/evitadb/api/exception/ConcurrentSessionAccessException.java` — the
  CSAE type itself moved/formalized to the engine's exception package.

**CDC register-then-mutate ordering:**
- `ClientChangeCaptureSubscriber` gained a `CompletableFuture<Void> acknowledged`, completed on the
  ACK and completed exceptionally on any terminal-before-ACK transition.
- `ClientChangeCapturePublisher.subscribe()` now blocks on `awaitAcknowledgement()` before
  returning (Javadoc: "This call is **blocking**: it returns only once the server has
  acknowledged…"), so a caller that registers a capture and immediately mutates the same session
  can no longer race the still-pending server-side registration.

**Test-lane execution model (the A2 decision, Option D):**
- **The switch had to leave public configuration to make the opt-in possible.**
  `ServerOptions` is a public record bound directly to YAML, so `directExecutor` was implicitly a
  user-settable `server.directExecutor` key — a production deployment could turn off all async
  execution by setting it. There was no real alternative to moving it: nothing below `Evita` can
  derive the choice, because `EvitaConfiguration` carries no API-enablement information and
  `ApiOptions` (which knows whether the instance is networked) lives one layer up in
  `evita_server`. The only open question was which seam carries the boolean.
- `evita_api/.../configuration/ServerOptions.java` — the `directExecutor` component,
  `DEFAULT_DIRECT_EXECUTOR` and the `DevelopmentConstants` import are gone; the record drops from
  **11 to 10 components**.
- `evita_engine/.../core/Evita.java:409` — a 5-arg constructor
  `Evita(configuration, scheduleCatalogLoading, onSessionCreationCallback, onSessionTerminationCallback, boolean directExecutor)`
  that all four narrower public constructors delegate to, passing `DevelopmentConstants.isTestRun()`.
- `evita_server/.../EvitaServer.java:658` — `new Evita(config, false, null, null, false)`;
  `EvitaServer` is always networked, so it always forces real pools.
- `evita_test/evita_test_support/.../annotation/DataSet.java` — the new
  `boolean useRealThreadPools() default false`. Its JavaDoc records the two behaviours that only
  manifest under real pools (this cascade, and the CDC ordering bug) **and warns against enabling
  it for datasets that mutate a catalog schema then immediately query the regenerated REST/GraphQL
  API** — that rebuild is asynchronous under real pools and would race the follow-up call. That
  warning is the distilled form of the 23 regressions Option B would have had to paper over.
- `EvitaParameterResolver.createEvita(catalogName, randomFolderName, useRealThreadPools)` passes
  `!useRealThreadPools` to the new constructor. Anonymous (no-`@DataSet`) instances never open web
  APIs and keep the deterministic direct executor.
- What matters here: confirmed set on both `EvitaClientReadOnlyTest` and
  `EvitaClientReadWriteTest`'s `@DataSet` — this pair (plus the CDC tests it hosts) **is** the
  real-pool island described in the Decision above; no other dataset opts in.
- The test-lane ping is disabled through the same A1 knob production uses
  (`EvitaParameterResolver`'s client builder calls `pingIntervalMillis(0)`), not a special-cased
  engine flag. A side effect of decoupling the idle timeout (above) is that several **self-built**
  test clients that were previously protected by the old inert-ping bug got a real 30 s ping by
  default once the bug was fixed; those were given explicit `pingIntervalMillis(0)`:
  `WalReplayAgainstLocalServerTest`, `LongRunningEvitaClientReadWriteTest`,
  `LongRunningCdcHeartbeatTest`. `SenesiUpsertFuzzer` (performance tests) kept its ping armed on
  purpose, as production-faithful.
- The `-Devita.test.disableRealPools` scaffolding toggle used to compare A2-on vs. A2-off was
  removed once the decision landed — confirmed absent from the tree.

**The same posture applied one level down, to `ProgressRecordTest`** (commit `a0730268b`), the one
flake the mandated full-suite rerun surfaced. Its tests were split by what they actually assert:
*semantic* ones (progress arithmetic, observer notification, completion state, nested-future
aggregation — properties that hold regardless of which thread runs the task) were converted to
`Runnable::run`, while the genuinely concurrent ones moved into a `@Nested`
`RealExecutorIntegrationTest` owning its own pool. **The declined alternative was to keep real pools
everywhere and raise every timeout to 60 s — rejected because it treats the symptom (budget too
short) rather than the cause (a probabilistic assertion on a property that does not need concurrency
to hold);** a 60 s budget can still starve a handoff under saturation, and it would have left the
`tearDown` pool leak in place. The house 60-second rule was kept, but only for the minority of tests
where cross-thread behaviour *is* the thing under test.
- **Invariant this rests on, stated in the test JavaDoc so a future change breaks it loudly:**
  `ProgressRecord`'s constructor wires the progress consumer and `whenComplete` **before** calling
  `execute(...)`, which is the only reason a same-thread executor exercises identical wiring.
  Reordering that constructor silently turns these tests into no-ops if the JavaDoc note is lost.
- The nested class's teardown replaced a dead `instanceof AutoCloseable` guard with
  `if (this.executor instanceof ExecutorService es) { es.shutdownNow(); }` — the original never ran
  on JDK 17, where `ExecutorService` is not yet `AutoCloseable`. Zero `CountDownLatch` usages remain
  in the file.

## Verification

`git show d760fc166` (merge of PR #1284) carries `045305232` (keep-alive config),
`2b6258235` (cascade fix + CDC ordering + `ClientSessionCancellationCascadeTest`), and `92d9a1dbe`
(the `directExecutor`/`useRealThreadPools` split) as ancestors, alongside unrelated work the same
branch bundled (storage/index fixes) — the PR title itself reads "perf/fix: upsert allocation cuts
and transactional-structure and gRPC-session hardening". All classes and flags named above were
confirmed present in the current tree by direct file/grep lookup, not assumed from the source
documents.

The numbers below are as reported in `RESULTS.md`; this record does not re-run the suite to
reconfirm them — they were the merge-gating numbers, not re-measured for this record:

- `ClientSessionCancellationCascadeTest` (new, fault-injecting `ClientInterceptor` forcing
  `CANCELLED` mid-call) — green; asserts `TransportException` thrown with cause preserved,
  `isActive() == false`, and 0 remote-close RPCs.
- Real-pool island — `EvitaClientReadOnlyTest` + `EvitaClientReadWriteTest`
  (`useRealThreadPools = true`): **149 tests, 0F/0E** (1 pre-existing skip), including the 2 CDC
  ordering tests.
- Web-API regression class `CatalogRestUpsertEntityMutationFunctionalTest` (direct executor):
  **12/0/0**.
- `ClientChangeCapturePublisherTest` (CDC unit, harness rewritten to drive the now-blocking
  `subscribe()` off the caller thread): **8/0/0**.
- Full `unitAndFunctional` suite: an earlier run of this stream's final configuration (pre-idle-
  timeout-decoupling) was **20 017 tests, 0F/0E**; the configuration including the decoupled-idle-
  timeout fix was **20 029 tests, 0F/1E, 37 skips** — the lone `1E` being the `ProgressRecordTest`
  flake, which the same PR then fixed, taking the suite to **20 030 tests, 0F/0E**. The `+1` is the
  added real-executor test, a genuine coverage gain rather than dropped coverage.
- `ProgressRecordTest` — **29/0/0 in isolation** after the conversion (`+195/-303` lines), up from
  28 tests.

## Consequences & open follow-ups

Re-checked against the current tree, not just carried from `RESULTS.md`:

- **Still open — no dedicated real-pool CI stage.** `RESULTS.md` deferred "a dedicated sequential
  real-pool CI stage" as a separate follow-up; confirmed still absent from `.github/workflows/`.
  The real-pool island runs as part of the ordinary functional-test job today, not as an isolated
  always-real-pools tier.
- **Still open — the timing-based end-to-end keep-alive stall-kill test was deliberately deferred**
  (it would need to stall an event loop, the exact nondeterminism the test lane forbids) and is
  confirmed absent from `evita_test/.../driver/`. The transport-failure *outcome* it would have
  asserted is covered deterministically by `ClientSessionCancellationCascadeTest` instead.
- **Resolved, not open:** CDC gate hardening (blocking-semantics Javadoc + timeout) — confirmed
  present in `ClientChangeCapturePublisher`'s Javadoc.
- **Resolved, not open:** the inert-ping defect (client ping silently disabled by the old coupled
  idle timeout) — confirmed fixed via the decoupled `idleTimeoutMillis` knob described above.
- Part C (bounded-wait before throwing CSAE on guard collision) was **not implemented** — the CDC
  ordering fix removed the register-then-mutate race that was the only observed source of guard
  collisions, so RESULTS.md records the decision to skip it rather than build unneeded hardening.
  `EvitaSessionProxy`'s existing `closingSequence.awaitFinish(500ms)` → `SessionBusyException` path
  is a different, pre-existing mechanism (close vs. concurrent invocation) and was not touched.
- **`ServerOptions` is a source/binary break** — its canonical constructor drops from 11 to 10
  components. Accepted because this line had not been released; it is the kind of change that needs
  a release-note entry rather than an ADR of its own.
- **A second flag the same removal plan targeted never existed.** The plan called for removing a
  B+-tree pre-commit-validation kill switch (`PRE_COMMIT_DIRTY_LEAF_VALIDATION` /
  `evita.bPlusTree.preCommitValidation`). `git log --all -S` across the full history for that name,
  for `dirtyLeafScopes` and for `TierBKillSwitchTest` returns **only the commit that added the plan
  document itself** — nothing ever implemented them. The validation it expected to gate shipped
  unconditionally in `dd193f25d` (same PR, same day) as
  `TransactionalLayerMaintainer.dirtyScopes` / `validateDirtyScopesBeforeCommit()`, with no kill
  switch in the design. There is nothing left to remove; if a switch ever existed it lived only in
  an uncommitted working tree.
- The client connection idle timeout changed from 5 s (accidentally, via the old coupling to the
  per-call timeout) to a deliberate 300 s default — pooled connections now linger longer; the
  per-call timeout itself (5 s) is unchanged and still bounds every request.

## Related work

PR #1284 was a nine-commit grab-bag branch that also carried unrelated storage and index work —
notably `dd193f25d` (unconditional B+ tree dirty-scope validation), which belongs to
**`2026-07-18-paged-index-corruption-and-flush-failure-boundary`**. Only the three commits named in
*Verification* are this record's.

## Timeline

- **2026-07-14** — root-caused; the original hypothesis (a missing `useBlockingTaskExecutor(true)`)
  was raised and then retracted the same day after being challenged, in favor of the keep-alive/
  directExecutor mechanism described above.
- **2026-07-14/15** — Parts A1 and B implemented; A2 (blanket real pools for web-API tests)
  implemented, then found to cause 23 web-API regressions in the mandated full-suite rerun; the
  disagreement escalated to an external senior-reviewer assessment (`A2_ASSESSMENT_REQUEST.md` /
  `_RESPONSE.md`).
- **2026-07-15** — owner adopts path (D); CDC register-then-mutate ordering identified as a real
  production bug (not a flake) and fixed at the source; `RESULTS.md` written, reporting `20 017`
  tests green from an earlier run of this stream's final configuration (pre-idle-timeout-
  decoupling) and describing the branch as uncommitted.
- **2026-07-16** — PR #1284 merged into `dev`, carrying `045305232` (keep-alive), `2b6258235`
  (cascade + CDC), `92d9a1dbe` (the `ServerOptions`/`useRealThreadPools` split) and `a0730268b`
  (the `ProgressRecordTest` conversion), alongside unrelated work bundled on the same branch.
- **2026-07-31** — planning documents retired, replaced by this record. Two narrower records written
  the same day (`remove-test-only-production-flags`, `progress-record-test-stabilization`) were
  folded in here: they are the same line of work in the same PR, and neither cleared the ADR bar
  standing alone — the first recorded no genuine fork, the second's durable invariant already lives
  in the test JavaDoc where it belongs.
