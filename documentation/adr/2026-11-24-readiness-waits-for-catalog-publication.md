---
title: Readiness waits until every start-up catalog is published or can no longer be, decided in the observability stack
date: 2026-11-24
updated: 2026-11-25 13:53
status: accepted
kind: fix
issues: [2082]
prs: []
areas: [evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/api/system,
  evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/http,
  evita_external_api/evita_external_api_observability/src/main/java/io/evitadb/externalApi/observability,
  evita_external_api/evita_external_api_graphql, evita_external_api/evita_external_api_rest,
  evita_external_api/evita_external_api_system, evita_external_api/evita_external_api_grpc/server,
  evita_engine/src/main/java/io/evitadb/core/Evita.java, docker/Dockerfile]
supersedes: []
superseded-by: []
relates: [2026-08-03-readiness-discovery-log-level, 2026-11-16-copy-on-write-path-routing]
---

# Readiness waits until every start-up catalog is published or can no longer be

`/system/readiness` (and the readiness in the gRPC `serverStatus`) answered `200 READY` as soon as the engine's
initial catalog load futures completed, while GraphQL and REST were still building and registering the catalogs'
endpoints - so `/gql/<catalog>` and `/rest/<catalog>` answered `404` on a node the orchestrator already routed
traffic to. Readiness now waits until every catalog is either active and published by every enabled API that routes
per catalog, failed to load, or not supposed to load. The core reports only facts (catalog states, and per API
whether a catalog's routing has settled); the policy - the scan, a configurable publication timeout, the latch and
its timer - lives in the observability module next to the rest of the probe logic.

## Why

The engine emits `CatalogInstalledIntoLiveView` after a catalog loads; its delivery is asynchronous (CDC executor),
and the GraphQL/REST observers build the catalog's schemas before they register its routes. The load future
completes without waiting for that delivery, so the window is real (reproduced red on `dev` `ea21066199`) and grows
with the number of catalogs and the size of their schemas, because each API processes its events one at a time.

Three constraints made the fix non-obvious:

- **Liveness is derived from readiness.** `ObservabilityProbesDetector#checkApiReadiness` reports
  `EXTERNAL_API_UNAVAILABLE` when the probe's API readiness is not `READY`, and the Docker image's `HEALTHCHECK`
  probes liveness. Anything that makes API readiness wait for catalogs makes a container that loads a large catalog
  look dead and get restarted.
- **A single instance must always become ready.** There is no cluster: nothing may hold readiness forever, so a
  catalog that cannot be published must be reported and then stop blocking - the way a catalog that fails to load
  becomes `CORRUPTED` and stops blocking.
- **Loading time is not under our control.** It depends on data size and WAL backlog, so a timeout must never count
  loading against the publication budget.

### Previous state

`SystemProviderRegistrar#renderReadinessResponse` answered `200` when `Evita#isFullyInitialized()` (all initial load
futures done) held and the probe reported `READY`; `EvitaManagementService#serverStatus` reported the probe's state
alone. `GraphQLManager#isFullyInitialized()` existed but had no caller.

## Options considered

### Option A - per-API facts in the core, a latched scan with a publication timeout in the observability stack (chosen)

`ExternalApiProvider#isCatalogRoutingSettled(String)` (default `true`; GraphQL and REST delegate to their managers)
says whether one API owes a catalog nothing more: registered, failed, not owed, or the subscription terminated.
`ObservabilityProbesDetector` scans the engine state: a catalog in a transitional state is pending; an active catalog
some API has not settled is pending until the publication timeout; everything else is settled. The first time
nothing is pending the result latches.

- **Pros:** liveness untouched; always converges; the timeout clock starts only when the catalog is already active,
  so loading and WAL replay never count against it; runtime catalog operations cannot flap a serving node.
- **Cons:** a `200` means "every active catalog is published, or its publication cannot complete and was logged",
  not "every active catalog is routable"; readiness depends on the observability module being present.

### Option B - fold the check into `ExternalApiProvider#isReady()` or `ProbesProvider#getReadiness` (declined)

- **Pros:** no new SPI; one readiness notion.
- **Cons:** liveness and the Docker `HEALTHCHECK` read the same state; `getReadiness` also caches `READY` for 30 s,
  which does not mix with a latch.
- **Rejected because:** a container loading a large catalog would be reported dead and killed. Revisit only if
  `checkApiReadiness` stops deriving liveness from API readiness.

### Option C - gate on the load futures and await the publication with a timeout (declined)

- **Pros:** reuses `isFullyInitialized()`; a plain `await(N)` is simple.
- **Cons:** when the request pool rejects the install event, CDC delivers it synchronously on the loading thread, so
  the registration runs - and can hang - inside the load future; the timeout clock would never start. An await's
  clock would also start when the waiting starts, counting loading and WAL replay against `N`.
- **Rejected because:** the backstop could not be guaranteed to start. The engine state already shows the catalog
  active before the event is emitted, which the scan uses.

### Option D - count in-flight host-event deliveries (declined)

- **Pros:** structurally always converges; no per-API code.
- **Cons:** counts deliveries to every host-area subscriber, including external gRPC/GraphQL CDC streams whose
  `onNext` can wait on a slow client (`GrpcOutboundGate`).
- **Rejected because:** a remote consumer could hold readiness. Restricting the count to the API managers'
  subscriptions needs a new public subscription contract; revisit if one appears for another reason.

### Option E - stay not-ready while a catalog cannot be published (declined)

- **Rejected because:** with a single instance there is nothing else to serve; the node must become ready and report
  the catalog. Revisit with clustering, when replicas can serve while one node is held back.

### Option F - managers register already-active catalogs when they are constructed (declined)

- **Pros:** heals late wiring instead of excusing it.
- **Rejected because:** it races the observer's own registration and breaks the embedding contract
  `CatalogRenameRoutingTest` relies on; the documented two-phase boot (`new Evita(config, false)`, then
  `ExternalApiServer#start()`) already avoids late wiring.

### Option G - synchronous host-event delivery, or load futures that wait for the observers (declined)

- **Rejected because:** engine load time would include external schema builds; the asynchrony is deliberate
  (`ChangeSystemCaptureSharedPublisher#processHostEvent`).

### Option H - a live, unlatched predicate (declined)

- **Rejected because:** creating, activating or renaming a catalog at runtime would take a serving node out of
  rotation for the length of a schema build.

### Option I - the timeout and its configuration in the core (declined)

- **Rejected because:** readiness is the observability stack's concern; the core keeps facts and SPI contracts only.
  The cost is accepted: without the observability module there is no probe provider and readiness answers
  `503 UNKNOWN`, which was already the case.

## Decision

**Chosen: Option A.** It is the only option that keeps liveness untouched, converges on every path (a failed or
hanging publication is bounded by the timeout, a failed load by `CORRUPTED`), and never counts data size against a
budget. Option B becomes viable only if liveness stops depending on API readiness; Option D if a subscription
contract scoped to the API managers appears; Option E with clustering.

## Key technical details

- **Facts:** `ExternalApiProvider#isCatalogRoutingSettled`, implemented by `GraphQLManager` / `RestManager`
  (registered, failed, not owed, subscription abandoned). The observers' `onError` / `onComplete` call
  `catalogRoutingSubscriptionTerminated`; only `onError` logs, naming the active catalogs left unpublished.
- **Failed mark:** set when building or publishing a catalog fails in `registerCatalog` or `refreshCatalog`, cleared
  **only** by a later successful registration or refresh - never when a retry starts (a probe could read one API
  settled by failure, the retry clears the mark, the other API settles, and the latch closes over a pair that was
  never settled together), never by `unregisterCatalog` (the rename branch calls it in `finally` exactly when
  publication failed, and nothing re-marks the catalog). The `UnusableCatalog` skip and `CatalogGoingLiveException`
  are not failures: another event follows.
- **gRPC, system, lab and observability** keep the default `true`: they have no per-catalog registration step (a gRPC
  session resolves the catalog in the engine on every call), so the engine-state scan covers them.
- **A rename onto a name that is already published** counts as published while the replacement is rebuilt: the old
  target's routes stay until the new ones swap in, so the name never answers `404`. Declined as a finding in review.
- **Not owed:** catalogs already active when the manager subscribed (late wiring); their event was emitted before
  the subscription existed and never arrives. Snapshotted right after `subscribe`, so a race can only make readiness
  early in that misuse, never stuck.
- **Policy:** `CatalogRoutingReadiness` (scan, per-catalog first-seen time, overdue logging once per catalog and API,
  latch) behind `ProbesProvider#onServerStarted` / `#getCatalogRoutingSettlement`. `ExternalApiServer#start()` calls
  the hook after the ports open and before `scheduleInitialCatalogLoading()`; the detector then scans every second on
  `evita.getServiceExecutor()` until the latch closes, and cancels the task on settlement or close. Without the timer
  the clock would start only at the first readiness request, and a node probed only for liveness would latch whenever
  that first request happened to come.
- **Setting:** `api.endpoints.observability.readinessCatalogActivationTimeoutInMillis`, default `300K` (5 minutes,
  the same as the Docker `--start-period`, which cannot be fed from an environment variable); values `<= 0` are
  rejected. It is read even when the observability endpoint is disabled. Each catalog has its own clock, started when
  its load ends, so several catalogs never add up: they hold readiness at most one timeout past the last load.
- **Two vocabularies:** "publication" is this record's internal word, and it stays in the code's internals. User-facing
  text - the setting, its docs, its log messages - calls loading a catalog and building its APIs together *catalog
  activation*, after `CatalogState.BEING_ACTIVATED`; the `readiness` prefix says the timeout only gates readiness.
- **One status rule:** `CatalogRoutingSettlement#resolveReadinessState` - while unsettled, only `READY` becomes
  `STARTING`; HTTP readiness and gRPC `serverStatus` both use it. The `503` body adds `catalogsPending` (a count, no
  catalog names on an unauthenticated endpoint); the `200` body is byte-identical.
- **Invariants:** liveness must never read the settlement; all scan state is per detector instance (tests run several
  servers in one JVM, and `HEALTH_CHECK_RUNNING` is static); the SPI returns a plain value (`settled`,
  `catalogsPending`) - the scan's latch future stays private to `CatalogRoutingReadiness`, so no caller can complete
  it. An awaitable future was considered and dropped: no production code awaits it.
- **Looks like a bug, is not:** transitional states (`BEING_ACTIVATED`, `GOING_ALIVE`, ...) have no timeout - loading
  is unbounded by design. They must block at all: a catalog booting `WARMING_UP` whose client calls go-live before the
  first latch sits in a `GOING_ALIVE` placeholder, and an active-only scan would latch over a publication still to
  come. Boot-time WAL replay is part of loading (the catalog stays a `BEING_ACTIVATED` placeholder until replay ends),
  and readiness must wait for it: a ready node serving the pre-replay state would hide transactions whose commit was
  already acknowledged. `BEING_UPGRADED` (the storage protocol <= 4 boot upgrade, being retired) is neither
  transitional nor active, so readiness does **not** wait for that upgrade.
- `Evita#isFullyInitialized()` lost its only caller and is `@Deprecated(since = "2026.3")`; it reports the initial
  load futures only. `GraphQLManager#isFullyInitialized()` was deleted.

## Verification

- `CatalogReadinessRoutingTest` - real engine and API server with the GraphQL/REST registration held at a gate:
  readiness `503` `STARTING` with `catalogsPending: 1` and gRPC `serverStatus` `STARTING` while `/gql/products` and
  `/rest/products` answer `404` and liveness answers `200`; `200` after release; the latch holds through a deactivate
  and re-activate; transitional states block; an overdue publication settles and logs once per API; late wiring
  becomes ready; with a short configured timeout the detector's periodic scan settles a held publication and logs it
  without any readiness request. Red on `dev` `ea21066199` before the fix (readiness `200` with both routes `404`).
  The test needs the engine's real thread pools: with the test harness's direct executor the event is delivered
  inline and the window never opens.
- `CatalogRoutingSettlementTest` (18) - per-manager settlement: registered, failed (`EvitaInternalError` and
  `IllegalStateException`, on registration and on refreshing a replaced target), later success clears the mark,
  failed rename stays settled, skip and go-live pending, abandoned, not owed.
- `CatalogRoutingReadinessTest` (6, fake clock) - exact timeout boundary, fresh budget when a catalog is owed again,
  latch permanence, `whenSettled` once, overdue logged once per catalog and API, inactive catalogs never block.
- `ObservabilityOptionsTest` rejects `0` and `-1`; `EvitaServerTest` keeps the `200` body byte-identical.
- Every new guard was proven by a counterfactual (revert it, watch the matching test fail, restore by content). With
  only the latch broken, the end-to-end test fails while the re-activated catalog's publication is held: readiness
  `503` `STARTING`, `catalogsPending: 1`.
- Full functional suite on the final code (`-P unitAndFunctional -am`, 8 parallel classes): 40,399 tests, 0 failures;
  the only errors were the 195 release-upgrade tests whose released fixtures the sandbox build skips. An earlier run
  had caught the end-to-end test itself: a readiness probe outlived its request timeout under that load, the server
  answered `Request timed out.`, and the test's poll treated it as fatal. Its polls now keep waiting on such an
  answer.

## Consequences & open follow-ups

- After a restart readiness arrives later than before - by the GraphQL and REST publication of the slowest catalog.
  Startup-probe budgets must cover loading plus publication; liveness is unaffected.
- A throw inside `Evita#markCatalogCorrupted` itself would leave a `BEING_ACTIVATED` placeholder, and readiness would
  never become ready; no such path is known.
- Late wiring (an embedding that starts the API server after the catalogs loaded) becomes ready, but those catalogs
  stay unpublished (`404`), as before; only the two-phase boot publishes them.
- With clustering, "must always become ready" may no longer hold (Option E).

## Related work

- [2026-08-03-readiness-discovery-log-level](2026-08-03-readiness-discovery-log-level.md) - the same readiness
  probe. Its endpoint discovery runs inside the providers' `isReady()`, which this change leaves untouched; the
  catalog gate is evaluated separately, so waiting for catalogs neither prolongs discovery nor raises its stall
  warning.
- [2026-11-16-copy-on-write-path-routing](2026-11-16-copy-on-write-path-routing.md) - the routers into which
  `registerCatalog` publishes the routes whose registration readiness now waits for.

## Timeline

- **2026-11-24** - reported, reproduced red on `dev`, designed with adversarial review, implemented.
