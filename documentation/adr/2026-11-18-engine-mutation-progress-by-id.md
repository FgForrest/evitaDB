---
title: Every engine mutation is tracked by its transaction id, its outcome retained as a compact record, and the GraphQL/REST system APIs expose blocking and asynchronous engine operations as a hard replace
date: 2026-11-18
updated: 2026-11-18 14:30
status: accepted
kind: feature
issues: [995]
prs: []
areas: [evita_api/src/main/java/io/evitadb/api/requestResponse/progress, evita_engine/src/main/java/io/evitadb/core/transaction/engine, evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/api/system/model/progress, evita_external_api/evita_external_api_graphql/src/main/java/io/evitadb/externalApi/graphql/api/system, evita_external_api/evita_external_api_rest/src/main/java/io/evitadb/externalApi/rest/api/system]
supersedes: []
superseded-by: []
relates: [2026-11-06-catalog-handover-waits-for-commit-pipeline]
---

# Engine operations are observable by a progress id, and the system APIs offer each one blocking and asynchronous

The GraphQL (`/gql/system`) and REST (`/rest/system`) system APIs expose every engine operation of `EvitaContract`
(create, activate, deactivate, delete, duplicate, make alive, make (im)mutable, rename, replace) in a blocking variant
and - except create - an asynchronous one. The asynchronous variant returns a handle `{progressId}` as soon as the
engine has accepted the operation (`202 Accepted` in REST); the client then subscribes to the progress by the id
(GraphQL subscription `onEngineMutationProgressChange`, REST WebSocket `GET /catalogs-async/progress`). To make that
possible, the engine now tracks **every** engine mutation under its engine transaction id, exposed as
`Progress#progressId()`, and retains the outcome of a finished one for a configurable time.

## Why

Issue #995 asked for the engine operations in the system APIs; the agreed scope is the issue comment
[3522438817](https://github.com/FgForrest/evitaDB/issues/995#issuecomment-3522438817). Long operations (activating a
large catalog, the go-live flush) cannot be served by a blocking HTTP call alone: it holds a transaction-pool thread and
is cut by the request timeout. An asynchronous call needs an id the client can come back with - and `Progress` had none.
The only existing lookup, `EngineTransactionManager#currentCatalogMutations`, is keyed by **catalog name** and the entry
is **removed on completion**: rename/replace change the name, and a client subscribing a moment late finds nothing
(gRPC `GetProgress` documents exactly this as `found=false`).

### Previous state

The GraphQL system API had `createCatalog`, `switchCatalogToAliveState`, `renameCatalog`, `replaceCatalog`
(returning `Catalog!`) and `deleteCatalogIfExists`; REST had `PATCH /catalogs/{name}` with
`{name, overwriteTarget, catalogState}` chaining rename/replace and go-live in one call. No asynchronous variant, no
progress, and no way to activate, deactivate, duplicate or change the mutability of a catalog.

## Options considered

### Registry in the engine, tracking every mutation automatically, id = transaction id (chosen)

`EngineMutationProgressRegistry` is owned by `EngineTransactionManager` and hooked into `applyMutationInternal`; the id
travels back on the returned `Progress` (`Progress#progressId()`, a default method returning empty).

- **Pros:** no registration race (both callbacks are wired before the operator future is submitted); one registry
  shared by all APIs - an id issued to a REST client can be observed over GraphQL and vice versa; the id correlates the
  progress with the engine transaction in system CDC; costs one new default method in `evita_api`.
- **Cons:** gRPC and Java-API mutations are retained too, so the retention limit is shared by all callers.

### Registry in `evita_external_api_core`, registering only API-issued operations (declined)

- **Pros:** no change in `evita_api` / `evita_engine`.
- **Cons:** the API layer learns the operation only from the returned `Progress`, i.e. after submission - a fast
  operation can finish before it is registered; needs an API-generated id injected into the engine to avoid it.
- **Rejected because:** the race needs a hook the engine already has (`onProgressExecution`), and wiring an external id
  into the engine is more surface than one default method on `Progress`.

### Keeping the finished `Progress` instead of a compact record (declined)

- **Pros:** no conversion. The retained `ProgressRecord` does not pin the catalog (examined: no field reaches the
  operator closure once the future completes).
- **Cons:** a failed one retains the throwable graph (several KB) including the **private** message; its size is not
  deterministic; it holds third-party listeners.
- **Rejected because:** the entry must be replaced on completion anyway (that write switches its expiry and weight), and
  only the public part of a failure may ever leave the server. Keeping `{Progress, finishedAt}` would be acceptable if
  the result type ever needs to be richer.

### Hand-written `Flow.Subscription` (as in CDC) instead of JDK `SubmissionPublisher` (declined)

- **Rejected because:** CDC's back-pressure took a lot of tuning precisely because demand, buffering and signal
  serialisation are hand-written there. `SubmissionPublisher` does all of it; what is left as own code is the snapshot
  on subscribe, fan-out under the entry lock and one reserved buffer slot for the terminal item.

### Fire-and-forget at the API layer (answering before admission) (declined)

- **Pros:** the asynchronous call would never wait for the engine-state lock or the session/pipeline drain.
- **Rejected because:** it needs an id before admission, moves admission failures (missing catalog, name clash, drain
  timeout) from an immediate HTTP/GraphQL error into the stream, and still occupies a pool thread for the drain - it only
  hides the wait. The real fix is Option E of `2026-11-06-catalog-handover-waits-for-commit-pipeline` (non-blocking
  drain), declined there; revisit together with it.

### Deprecating the old GraphQL fields / REST properties instead of removing them (declined)

- **Rejected because:** the system API has few users, and keeping `switchCatalogToAliveState` / `overwriteTarget` next
  to the new operations would keep two vocabularies for one resource. The removal is labelled `breaking change`.

## Decision

**Chosen: automatic tracking in the engine, compact terminal records retained by Caffeine, one `SubmissionPublisher`
per subscription, hard replace of the old system-API operations.** Retention (`engineMutationProgressRetentionInSeconds`,
default 300) and the limit of finished records (`engineMutationProgressRetentionLimit`, default 100) are `ServerOptions`;
`0` disables retention. Revisit the limit policy if clients report evicted outcomes under bursts (see follow-ups).

## Key technical details

- **Hook:** `EngineTransactionManager#applyMutationInternal` registers in `onProgressExecution` and completes in
  `onProgressCompletion`. The completion callback is built **after** `operatorFuture` and reads the outcome from it (the
  record's own `onCompletion()` is not complete inside that callback); it runs in a `finally` after the conflict-key
  release; registration and completion are guarded so a registry failure never fails the mutation. A record that cannot
  be submitted is removed again (its caller got an exception and no id). `ServerModifyCatalogSchemaMutation` (the
  early-return path) is deliberately not tracked.
- **Retention:** running entries have weight 0 and an unlimited expiry, so Caffeine never evicts or expires them; a
  finished entry has weight 1 and expires after the retention counted from completion; reads never extend it.
  Construction mirrors the formula cache supervisor of the (unmerged) `feat/cache` branch, and the Caffeine dependency
  lines are byte-identical to that branch.
- **Publisher:** delivery runs on `ProgressingFuture.unrejectableExecutor(requestExecutor)` - a rejecting executor makes
  `SubmissionPublisher` drop a subscriber without a signal. An intermediate item is offered only while
  `estimateMaximumLag() < getMaxBufferCapacity() - 1`, so the terminal item always fits. The terminal state is kept on
  the running entry too, so a subscriber that found the entry just before it was replaced still gets the outcome. 100 %
  from the listener is ignored (engine progress reports it also right before failing).
- **Signals:** success → final item + `onComplete`; failure → `onError(EngineMutationFailedException)` carrying the
  original error code and public message only; unknown/expired id → `onError(EngineMutationProgressNotFoundException)`;
  engine shutdown → `onComplete` **without** a final item (`SubmissionPublisher` cannot end silently) - a client that saw
  no finished item must treat the outcome as unknown.
- **Async still pays the admission:** the asynchronous variants return only after the engine-state lock, applicability
  checks and - for rename, replace and make alive - the session close and pipeline drain on the caller's thread.
- **APIs:** blocking operations return `CatalogUnion` (`Catalog` or `UnusableCatalog`), because deactivate and duplicate
  produce an inactive catalog. (The plan also assumed rename/replace can target an inactive catalog; they cannot - the
  operator reads the schema of the catalog and an `UnusableCatalog` throws `CatalogInactiveException` at admission, so
  the call fails directly. It is documented on the operations.) REST: `PATCH` takes exactly one of `name` / `state` (`CatalogState`; `ALIVE` activates an
  inactive catalog or makes a warming-up one alive, `INACTIVE` deactivates) / `readOnly`; replace and duplicate are
  `POST /catalogs/{name}/actions/{replace|duplicate}/{newName}` (for replace, `{name}` replaces `{newName}`); the
  `/catalogs-async/...` twins answer `202` (`AcceptedEndpointResponse`, `OpenApiAccepted`). Both catalog types expose
  `readOnly`, resolved from the engine state so it is known for inactive catalogs too.

## Verification

- `EngineMutationProgressRegistryTest` (15 tests, fake ticker, inline and asynchronous delivery): live updates and
  terminal item with versions, failure keeps only code + public message and is never reported as success, slow
  subscriber skips intermediate values but receives the terminal item, cancel, close, retention expiry (running entries
  never expire), limit (running entries never evicted), retention `0`, limit `0`, removal.
- `EngineMutationProgressTrackingTest`: all nine `*WithProgress` operations on a real engine carry distinct ids and
  their outcome is delivered to a late subscriber.
- `SystemGraphQLMutationsFunctionalTest`, `SystemGraphQLSubscriptionsFunctionalTest`,
  `SystemRestEndpointFunctionalTest`, `SystemRestStreamingFunctionalTest`: every operation, `readOnly` on active and
  inactive catalogs, 400 for a multi-field `PATCH`, 404s, replace argument order, routing of catalogs named `progress`
  and `actions`, 202 handles, late subscription by id over both transports (REST observes an id issued through the
  Java API - the registry is the one in the engine), unknown id as an error frame.
- Full `evita_functional_tests` run (39,200 tests): the only failures need Docker (S3, JFR) or the release fixtures
  (build run with `-DreleaseFixtures.skip=true`).

## Consequences & open follow-ups

- **The newest finished outcome may be the one evicted at the limit.** Caffeine's TinyLFU admission gives a fresh entry
  the lowest frequency, so under a burst of more than `engineMutationProgressRetentionLimit` operations within the
  retention, a just-finished outcome is the likeliest to go. Live subscribers are unaffected. If clients hit it, replace
  the size eviction by an insertion-ordered trim of finished records.
- No failure *after acceptance* is exercised end to end through the real engine (no seam to fail an accepted operator on
  demand); it is covered at the registry level only. Neither is a server shutdown with an open progress stream.
- The executed documentation examples (`use/api/example/system-api-engine-control-*`,
  `finalization-of-warmup-mode.*`, `get-started/example/define-catalog.graphql`) were not run - they need the local
  documentation rig.
- `UnusableCatalog`'s `CatalogIdentity` reports `readOnly = false` (`UnusableCatalog.java`, `EvitaManagement.java`)
  although the flag stays valid for an inactive catalog - found here, not fixed.
- The Java driver's `Progress` has no id (the server does not pass it through gRPC); a possible follow-up.
- No poll endpoint (`GET /catalogs-async/progress/{id}`) - it comes with its first caller.

## Related work

- `2026-11-06-catalog-handover-waits-for-commit-pipeline` - the drain the asynchronous rename/replace still pay at
  admission; its declined Option E is what would make them return before it.

## Timeline

- **2026-11-13** - plan reviewed against the code
- **2026-11-18** - design decisions settled, implemented on branch `995-new-engine-mutation-support-in-graphql-and-rest-apis`
