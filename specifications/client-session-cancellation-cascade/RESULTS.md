# Results — random CANCELLED + client-session cancellation cascade

Implementation of the (rewritten) `ASSIGNMENT.md`. Status as of 2026-07-15. Branch
`warmup-upsert-alloc-optimization`, uncommitted.

## Summary

- **Part A1 (configurable keep-alive) — DONE.** Client 30 s, server 0 (disabled). Test lane disables the client ping.
- **Part A2 — SUPERSEDED.** The blanket `directExecutor(false)` for web-API test servers was reverted; see the
  §3a triage below and `A2_ASSESSMENT_REQUEST.md` / `A2_ASSESSMENT_RESPONSE.md`. Replaced by a **per-dataset
  `@DataSet(useRealThreadPools = true)` opt-in** (the "real-pool island").
- **Part B (driver: transport failure → session loss) — DONE + test green** (executor-independent).
- **Part C (guard bounded-wait) — not implemented** (the CDC ordering fix subsumes it).
- **CDC ordering fix — DONE + verified.**
- **Inert client-ping defect (found during assessment) — FIXED (option 1): a dedicated, decoupled
  `ClientConnectionOptions.idleTimeoutMillis` knob (default 300 s) so the 30 s ping is now strictly below the idle
  timeout and the keep-alive watchdog is active out of the box; the warning now fires only on genuine misconfig.**

**Final decision (owner, via external assessment): path (D)** — direct executor everywhere in the test lane by
default (the determinism posture for CPU-saturated parallel tests); real pools opted in per-dataset only where a
production behaviour is real-pool-only (the gRPC cancellation cascade, the CDC register-then-mutate ordering). The
23 web-API regressions A2 caused disappear; A1 + CDC alone keep the 7 CSAE/CANCELLED gone.

The 7 original `EvitaClient*Test` CSAE/CANCELLED errors are **eliminated**. `EvitaClientReadOnlyTest` +
`EvitaClientReadWriteTest` (now `useRealThreadPools = true`) = **149, 0F/0E** (1 pre-existing skip);
`CatalogRestUpsertEntityMutationFunctionalTest` (direct executor) = 12/0/0 (regression gone);
`ClientChangeCapturePublisherTest` = 8/0/0.

## Part A1 — keep-alive is now configurable

Both hard-coded `pingIntervalMillis(1000)` values are gone.

- **Client:** `ClientConnectionOptions.pingIntervalMillis` (record + builder + copy-ctor), constant
  `DEFAULT_PING_INTERVAL_MILLIS = 30_000`; convenience setter/accessor on `EvitaClientConfiguration`;
  `EvitaClient.java:391` now reads `configuration.connection().pingIntervalMillis()`.
- **Server:** `ApiOptions.pingIntervalMillis` (record + both ctors + builder + copy-ctor), constant
  `DEFAULT_PING_INTERVAL = 0` (disabled); `ExternalApiServer.java:599` now reads `apiOptions.pingIntervalMillis()`.
  Absent YAML key binds to 0 (paramnames module; mirrors `idleTimeoutInMillis`). Negative → 0 in the ctor.
- **Defaults (owner-reviewed): client 30 s, server 0 (disabled).** Armeria accepts only `0` or `>= 1000`
  (verified in `ServerBuilder`/`ClientFactoryBuilder`); in-between throws, so misconfig surfaces loudly at startup.
- **Idle-timeout knob — decoupled (option 1, owner-approved).** Previously the client Armeria idle timeout was
  wired from `ClientTimeoutOptions.timeout()` (the per-call deadline, default 5 s), so the 30 s ping was `>=` the
  idle timeout and Armeria **silently disabled the ping** — the default watchdog was inert. Verified against
  Armeria 1.27.1 bytecode (`ClientFactoryBuilder`): the ping is dropped iff `idle > 0 && ping > 0 &&
  max(ping,1000) >= idle`. Fix: a dedicated `ClientConnectionOptions.idleTimeoutMillis` (record + builder +
  copy-ctor + convenience accessor/setter on `EvitaClientConfiguration`), constant
  `DEFAULT_IDLE_TIMEOUT_MILLIS = 300_000`. `EvitaClient` now reads it directly and calls the **two-arg**
  `idleTimeoutMillis(idle, keepAliveOnPing = true)` — set explicitly (Armeria's global
  `Flags.defaultClientKeepAliveOnPing` is `false` and system-property-flippable) so acknowledged pings count as
  activity and a healthy connection is never idle-reaped. The startup `log.warn` now mirrors Armeria's exact rule
  (`idle > 0 && ping > 0 && max(ping,1000) >= idle`) and points at both knobs.
- **Behaviour change (not just a bugfix):** the client connection idle timeout goes from **5 s → 300 s**. Idle
  pooled connections now linger up to 300 s instead of being reaped after 5 s (cheap for multiplexed HTTP/2; saves
  reconnects, keeps NAT/LB paths warm). No test asserted the old 5 s idle-close (grep clean), so the shift is
  behaviour-safe; the per-call `timeout` (5 s) is unchanged and still bounds every request.
- **Deployment note:** behind third-party gRPC infra (Envoy/nginx/managed LBs) the ping interval must stay below
  any LB/NAT idle window and below the idle timeout; some LBs enforce a 5-min minimum-ping policy.
- This is the **production fix** — production already uses real pools, so its residual CANCELLED lane (GC pauses
  under load) is addressed by the wider ping interval, now backed by a coherent, active watchdog.
- Coverage: `ClientConnectionOptionsTest` (defaults 30 s / 300 s, custom + disable-via-0 for both knobs, copy-ctor);
  `EvitaClientConfigurationTest` (ping + idle delegate accessors and top-level builder setters). User docs updated:
  `documentation/user/{en,cs}/use/connectors/java.md` now document both `pingIntervalMillis` and `idleTimeoutMillis`.

## Part A2 — test-lane execution collapse (Option 1)

Root cause (owner-corrected): the offload design is correct — all gRPC service methods offload their body to
`evita.getRequestExecutor()` via `executeWithClientContext`. But `ServerOptions.DEFAULT_DIRECT_EXECUTOR =
isTestRun()`, and with `directExecutor=true` the `Evita` ctor swaps the request pool + scheduler for
`ImmediateExecutorService`, which caller-runs every service body on the Armeria event loop — so in test JVMs a
slow call stalls the loop and the keep-alive kills the connection.

- Added `ServerOptions.Builder.directExecutor(boolean)` (its `build()` previously hard-coded
  `DEFAULT_DIRECT_EXECUTOR`; the option was builder-invisible before).
- `EvitaParameterResolver.createEvita(catalog, folder, boolean webApiEnabled)` sets `directExecutor(false)` when
  the dataset opens web APIs (`!isEmpty(dataSetInfo.webApi())`, the `:1068` call site); anonymous embedded
  instances (`:995`) keep the deterministic direct executor. Scope limited to web-API test setups.
- **Coverage gap (known):** web-API tests that build a server WITHOUT `EvitaParameterResolver` — `EvitaServerTest`,
  `EvitaTest` — are not touched by A2, but are protected by A1 (server ping disabled / client ping 30 s).

## Part B — driver treats transport failure as session loss

- `EvitaClient.isTransportFailure(Throwable)`: true for a `StatusRuntimeException` with `CANCELLED` /
  `UNAVAILABLE` / `DEADLINE_EXCEEDED`, or a cause chain carrying Armeria's `ClosedSessionException` /
  `ClosedStreamException` (whole chain walked).
- Both session call sites (`executeWithBlockingEvitaSessionService` — the load-bearing blocking path — and
  `executeWithStreamingEvitaSessionService`): on a transport failure call `terminateLocally()` (clean local death:
  completes the close future with no server round-trip, flips `isActive()` false, so the try-with-resources close
  becomes a local no-op) and throw a new `TransportException extends EvitaClientServerCallException` preserving the
  original cause. The `UNAUTHENTICATED` path is untouched.
- `TransportException` javadoc documents the at-most-once / at-least-once ambiguity (the orphaned server call may
  still complete).
- The clean-close-after-`terminateLocally()` path is already exercised in production by
  `EvitaClient.evictLocalSessionsForCatalog` (catalog delete/rename/replace).
- **Test:** `ClientSessionCancellationCascadeTest` (new). A fault-injecting `ClientInterceptor` (via the
  `grpcConfigurator` constructor hook) fails a `QueryOne` with `CANCELLED` — a mid-call drop without touching the
  wire — and counts any close-family RPC afterwards. Asserts: `TransportException` thrown with cause preserved,
  session `isActive()==false`, and **0** remote-close RPCs (the CSAE-cascade check). Green. Fail-first is
  self-evident by construction — `TransportException` is thrown only by the new branch, so pre-fix the CANCELLED
  maps to `GenericEvitaInternalError` and the assertion cannot pass.

## The 2 remaining failures — a real CDC race A2 exposed (owner chose to fix at source)

`EvitaClientReadWriteTest.shouldRenameCollection` (`:1768`, expected 1 got 0) and
`shouldCancelCatalogChangeSubscriberAndEvictPublisherOnServerSide` (`:2980`, CSAE between real request-pool
threads) share one root cause: both do, in one session/`updateCatalog` lambda,
`session.registerChangeCatalogCapture(...).subscribe(sub)` **then** a schema mutation. The server's
`registerChangeCatalogCapture` (`EvitaSessionService.java:2385`) offloads its `subscribe(...)` to the request pool
and the client's registration returns before that subscribe completes. Under the old immediate executor everything
ran serially on one thread so this always "worked"; under real pools the client's next same-session call races the
still-running registration → CSAE (defineEntitySchema) or a missed event (rename: the mutation fired before the
subscriber attached — a delivery-ordering race, no CSAE). This is a **real production CDC bug** that A2 surfaced.

**Owner decision (2026-07-15): fix the CDC ordering client-side** so registration does not complete until the
server confirms the subscription. Part C (guard bounded-wait) would only fix the CSAE half, not the rename
delivery-miss, so it is not implemented; the source fix subsumes it.

### CDC ordering fix — DONE

**Why the client ACK-gate is strictly correct (not a load-flake).** The `EvitaSessionProxy` ownership guard is
**per-proxied-method-invocation** with same-thread reentrancy (`EvitaSessionProxy.java:607` CAS-acquires on each
call; `:614`/`:631` `outermostInvocation` releases when the outermost call unwinds). The server lambda is
`s -> s.registerChangeCatalogCapture(req).subscribe(subscriber)`: the proxied `s.registerChangeCatalogCapture(req)`
acquires ownership, wires the publisher into the transaction-manager change observer
(`Catalog.registerChangeCatalogCapture` → `TransactionManager.registerObserver` → `CatalogChangeObserver`), and
**releases** ownership on return — all *before* `.subscribe(subscriber)` runs on the returned (non-proxy) engine
publisher. The ACK is emitted from within `.subscribe()` (`AbstractChangeCaptureSubscriber.onSubscribe` →
`emitOnNext`), i.e. **after** ownership is already released. So when the client sees the ACK and issues the next
same-session call, the registration RPC has provably released ownership: the follow-up acquires cleanly with **no**
timing/GC dependency. The same wiring guarantees the subscriber is in the delivery path before the client's
mutation is even issued, so the rename event is delivered.

Implementation (client only, in `evita_external_api_grpc/client`):

- `ClientChangeCaptureSubscriber` — added `CompletableFuture<Void> acknowledged`; completed in `onNext` when the
  ACK is processed (subscription id set, credit window opened); completed exceptionally on any terminal-before-ACK
  transition (`onError`, `onComplete`, `notifyClientFailureAndClose`, `close`) so a waiter never hangs out the full
  streaming timeout. New package-private `awaitAcknowledgement()` blocks on the future up to `streamingTimeout`,
  translating timeout / execution / interrupt into a `GenericEvitaInternalError`.
- `ClientChangeCapturePublisher.subscribe()` (base class → covers **both** catalog and system capture) — after
  `internalSubscriber.onSubscribe(...)`, calls `internalSubscriber.awaitAcknowledgement()`; on failure it
  `subscription.cancel()`s the half-open subscription (removes it + cancels the gRPC stream) before rethrowing.
- No deadlock: the ACK is delivered on the Armeria client event-loop thread; `subscribe()` runs on the caller/app
  thread (the client's `updateCatalog`/`queryCatalog` are synchronous on the caller, never an event loop). Distinct
  threads. Embedded/engine CDC is synchronous (no network) → unaffected.

**Verified:** `EvitaClientReadWriteTest.shouldRenameCollection` +
`shouldCancelCatalogChangeSubscriberAndEvictPublisherOnServerSide` green; full
`EvitaClientReadOnlyTest,EvitaClientReadWriteTest` = **149 tests, 0F/0E** (1 pre-existing skip), no CDC-subscribe
regressions from the added ACK latency.

## §3a full-suite triage — A2 is unnecessary AND causes a web-API regression

The mandated full `unitAndFunctional` rerun (20 017 tests) came back **23 failures + 9 errors**. Triage:

- **7 errors — self-inflicted, fixed.** `ClientChangeCapturePublisherTest` (the CDC unit test) drives the now-blocking
  `subscribe()` single-threaded, so it hung 60 s/test. Fixed: the harness runs `subscribe()` on a background thread
  (mirroring production, where the ACK lands off the caller thread) and joins after the ACK; `onNext` now completes
  the acknowledgement future **exceptionally** on a protocol violation (first message not an ACK), so a blocked
  caller fails fast instead of waiting the streaming timeout. `ClientChangeCapturePublisherTest` → 8/0/0.
- **2 errors — pre-existing / unrelated.** `ProgressRecordTest.shouldTrackProgressFromProgressingFuture` (a
  `CompletableFuture` unit timeout) and `CatalogRestCdcFunctionalTest.shouldReceiveCatalogCaptureWithBody` (REST
  **WebSocket** CDC, awaitility timeout) — neither touches the gRPC client publisher; not caused by this stream.
- **23 failures — caused by A2.** All are `FieldUndefined` (GraphQL) / `404` (REST) on collection endpoints across
  8+ web-API classes. Root cause (confirmed at source): `Evita.java:372` makes `serviceExecutor` an
  `ImmediateScheduledThreadPoolExecutor` when `directExecutor=true` and a real async `Scheduler` otherwise; the
  `SystemChangeObserver` that drives GraphQL/REST **API regeneration on schema change** (`GraphQLManager` /
  `RestManager` subscribe to `registerSystemChangeCapture`) runs on that `serviceExecutor` (`Evita.java:451`). With
  the old test default (`directExecutor=true`) the API rebuilt **synchronously**, so a test mutating a schema then
  immediately hitting the regenerated endpoint always found it ready. A2's `directExecutor(false)` makes that rebuild
  **async** (production-like), so the follow-up call races it → `FieldUndefined`/`404`. This is a **latent
  test-fragility A2 exposed**, not a production bug (production always runs async).

**Empirical decision data** (branch = A1+A2+B+CDC; toggle A2 via `-Devita.test.disableRealPools`):

| Run | A2 on | A2 off (A1+B+CDC only) |
|---|---|---|
| `CatalogRestUpsertEntityMutationFunctionalTest` (isolation) | **9F / 12** | **0F / 12** |
| `EvitaClientReadOnlyTest` | 0F | 0F |
| `EvitaClientReadWriteTest` (incl. the 2 CDC + the 7 CSAE) | 0F | **0F** |

**⇒ A1 (ping config) + the CDC fix alone keep the 7 CSAE/CANCELLED gone; A2 is unnecessary and is the sole cause of
the 23 web-API regressions.** A2's only residual value is test *fidelity* (real pools exposed the real CDC bug).

**RESOLVED — owner decision path (D)** (see `A2_ASSESSMENT_RESPONSE.md`): revert the blanket `directExecutor(false)`;
keep the `ServerOptions.Builder.directExecutor(boolean)` knob; add a per-dataset `@DataSet(useRealThreadPools = true)`
opt-in; opt in the `EvitaClientReadOnly/ReadWrite` datasets (the real-pool island — covers the 7 CSAE, the 2 CDC
tests and the client family, all green under real pools); disable the client ping in the test lane
(`pingIntervalMillis(0)` on the resolver-injected client; server ping already `0`); delete the toggle. Motivation:
CPU-saturated parallel tests + any async handoff = flakiness → direct executor is the deterministic default, real
pools opt-in only where a behaviour is genuinely real-pool-only.

## Verification / acceptance status (final)

1. **Full `unitAndFunctional` rerun (final config, incl. option-1 idle knob) — `20029` tests, `0F / 1E`, 37 skips.**
   The 23 web-API regressions and the 7 CDC-unit errors stay gone. The lone `1E` was the known pre-existing async
   flake `ProgressRecordTest.shouldTrackProgressFromProgressingFuture` (`CompletableFuture.get()` `TimeoutException`
   under `parallel=all` CPU saturation) — never attributable to this change stream (no gRPC/client involvement) and
   **since FIXED** under `specifications/progress-record-test-stabilization/`: `ProgressRecordTest` was rewritten so
   its semantic tests run the task inline via `Runnable::run` (no latches, no cross-thread 2-second budgets),
   eliminating the probabilistic handoff; the one genuinely cross-thread scenario keeps a real pool with a ≥ 60 s
   budget. `ProgressRecordTest` is now 29/0/0 in isolation, and a fresh full functional-tests `unitAndFunctional`
   run under `parallel=all` is **`20030` tests, `0F / 0E`, 37 skips (BUILD SUCCESS)** — the flake is gone and the
   `0F / 0E` baseline restored (the `+1` vs `20029` is the added real-executor test). An earlier run of the final
   config (pre-option-1) was `20017`, `0F / 0E`.
2. Part B connection-drop test (`ClientSessionCancellationCascadeTest`) — green, and green on the **direct executor**
   (fault injection is executor-independent — confirms it needs neither real pools nor a real ping).
3. Real-pool island: `EvitaClientReadOnlyTest` + `EvitaClientReadWriteTest` (`useRealThreadPools = true`) = **149,
   0F/0E** (1 pre-existing skip), incl. the 2 CDC ordering tests; `ClientChangeCapturePublisherTest` (unit) = 8/0/0.
4. Web-API regression class `CatalogRestUpsertEntityMutationFunctionalTest` (direct executor) = 12/0/0.
5. A1 config coverage (deterministic): client `ClientConnectionOptionsTest` (ping + idle: defaults, custom,
   disable-via-0, copy-ctor) = 12/0/0; `EvitaClientConfigurationTest` (ping + idle delegate accessors + top-level
   builder setters) = 42/0/0; new server-side `ApiOptionsPingIntervalTest` (6/0/0 — default 0, custom, explicit-0,
   negative→default, copy-builder).
6. Inert-ping defect — **FIXED (option 1):** decoupled `ClientConnectionOptions.idleTimeoutMillis` (default 300 s)
   so the 30 s ping is strictly below the idle timeout and the watchdog is active on defaults; `EvitaClient` passes
   the two-arg `idleTimeoutMillis(idle, keepAliveOnPing = true)` and the `log.warn` now mirrors Armeria's exact
   drop rule and fires only on genuine misconfig. Both knobs documented in the client JavaDoc and the user docs
   (`use/connectors/java.md`, en + cs).
7. CDC gate hardening — blocking semantics + timeout JavaDoc'd; ACK-completion path verified off the caller thread.
8. **Deliberately deferred (owner decision):** the timing-based end-to-end keep-alive stall-kill test. It would have
   to stall an event loop (the async nondeterminism the test lane forbids) and it exercises Armeria's mechanism,
   which A1 only *configures*; the transport-failure outcome it would assert is already covered deterministically by
   `ClientSessionCancellationCascadeTest`. A dedicated sequential real-pool CI stage is likewise deferred.

## Working-tree note

Production edits: `ServerOptions` (evita_api); `ApiOptions`, `ExternalApiServer` (external_api_core);
`config/ClientConnectionOptions` (+`pingIntervalMillis`, +`idleTimeoutMillis`), `config/EvitaClientConfiguration`
(ping+idle delegates/setters), `EvitaClient` (idle knob + `keepAliveOnPing=true` + corrected warn), `EvitaClientSession`,
new `exception/TransportException`, `cdc/ClientChangeCapturePublisher`, `cdc/ClientChangeCaptureSubscriber` (grpc
client); `EvitaParameterResolver`, `annotation/DataSet` (evita_test_support).

Docs (user-facing): `documentation/user/en/use/connectors/java.md` and `documentation/user/cs/use/connectors/java.md`
(both `pingIntervalMillis` + `idleTimeoutMillis` documented).

Tests: `config/ClientConnectionOptionsTest` (ping+idle defaults/setter/copy), `config/EvitaClientConfigurationTest`
(ping+idle delegate accessors + top-level builder setters), new `ClientSessionCancellationCascadeTest`, new
`externalApi/configuration/ApiOptionsPingIntervalTest`, harness rewrite in `cdc/ClientChangeCapturePublisherTest`,
`@DataSet(useRealThreadPools = true)` on the two `EvitaClient*` datasets. **Option-1 fallout** (the decoupled idle knob
re-armed the default keep-alive ping — 30 s ping now < 300 s idle — for self-built test clients that were previously
protected by the inert-ping bug): added `pingIntervalMillis(0)` to the self-built clients in
`store/catalog/WalReplayAgainstLocalServerTest` (functional), and `driver/LongRunningEvitaClientReadWriteTest` +
`driver/LongRunningCdcHeartbeatTest` (evita_long_running_tests). `spike/SenesiUpsertFuzzer` (evita_performance_tests)
gained `TransportException` absorption (keeps its ping armed on purpose — production-faithful). The four gRPC-services
tests and `EvitaServerTest:516` were assessed and **left unchanged** — they use raw stubs / a raw Armeria `WebClient`
factory with Armeria's default ping (0), so option 1 did not arm them.

All compile; the changed non-test modules are installed to `~/.m2`. This branch also carries the unrelated
stale-leaf-page-twin + senesi-WAL + warmup + collation + GroupHaving work — do NOT fold those into a commit of this
stream.
