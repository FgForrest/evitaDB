---
title: A catalog CDC resume position names the catalog incarnation it belongs to, and a position the catalog cannot serve is refused with a typed reason
date: 2026-10-08
updated: 2026-10-08 18:30
status: accepted
kind: fix
issues: [1680]
prs: []
areas: [evita_api/src/main/java/io/evitadb/api/exception, evita_api/src/main/java/io/evitadb/api/requestResponse/cdc, evita_engine/src/main/java/io/evitadb/core/cdc, evita_engine/src/main/java/io/evitadb/core/transaction, evita_engine/src/main/java/io/evitadb/core/catalog, evita_external_api/evita_external_api_grpc/shared/src/main/java/io/evitadb/externalApi/grpc/requestResponse, evita_external_api/evita_external_api_grpc/server/src/main/java/io/evitadb/externalApi/grpc/services, evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver, evita_external_api/evita_external_api_rest/src/main/java/io/evitadb/externalApi/rest/api/catalog/cdcApi, evita_external_api/evita_external_api_graphql/src/main/java/io/evitadb/externalApi/graphql/api]
supersedes: []
superseded-by: []
relates: [2026-10-01-cdc-catch-up-delivers-everything-owed-or-fails, 2026-09-12-restore-catalog-to-earlier-version, 2026-08-06-catalog-folder-decoupling]
---

# A catalog CDC resume position names the catalog incarnation it belongs to, and a position the catalog cannot serve is refused with a typed reason

A catalog change-capture subscription resumes from `(sinceVersion, sinceIndex)`, a position in a version sequence
that exists only within one incarnation of a catalog. The position now also carries `catalogId`: every
`ChangeCatalogCapture` is stamped with the identity of the incarnation that produced it, a request may state the
identity it expects, and registration refuses a position recorded on another incarnation - and, with or without an
identity, a position more than one version ahead of the catalog. Both refusals, and the WAL-retention refusal of
#1446, are one exception type, `ChangeCaptureResumePositionInvalidException`, a subtype of
`TemporalDataNotAvailableException` with a reason. It reaches Java driver consumers typed, which no asynchronous CDC
failure did before.

## Why

A production deployment (evitaDB 2026.2, Java driver 2026.2) uses catalog CDC to invalidate its caches of published
data. An application upgrade triggered a full reindex that ends with `replaceCatalog`. The shared CDC publisher of
the replaced catalog closed (#1124), and 27 ms later the consumer re-subscribed with `sinceVersion = 1878` - the
last version of the **replaced** incarnation. The replacing catalog had its own, much lower version sequence. For the
next 26 hours it propagated 261 versions, the subscription stayed open with one subscriber and zero lagging ones,
ACK and heartbeats were healthy - and not one capture was delivered. At that rate the stream would have started
delivering after roughly a week. Nothing was logged above DEBUG on either side; the problem surfaced only when a new
entity was visible in evitaDB but not in the application, whose caches served stale data until their TTL expired.

Had the new incarnation been ahead of the checkpoint, the subscription would have delivered versions of an unrelated
lineage with no signal at all. This is the failure class of #1446 - a position the catalog cannot serve, accepted
silently - reached by another road.

The constraint that made it non-obvious: `catalogId` already existed (#609, and #649 made it the field clients use to
decide whether a cached view still holds), but it is exactly the right identity only because of how replacement
works. `replaceCatalog` builds the result from the *replacing* catalog (`Catalog#replace`), so the name ends up with
the replacing catalog's `catalogId` and version lineage; a rename keeps both; restore and duplicate mint a new id
(`2026-08-06-catalog-folder-decoupling`). `catalogId` is therefore precisely "the version lineage a
`(sinceVersion, sinceIndex)` belongs to".

### Previous state

- `ChangeCatalogCaptureRequest` and `GrpcRegisterChangeCatalogCaptureRequest` carried only the version, index,
  criteria and content. Neither the capture, the ACK nor the heartbeat carried the identity, so the only race-free
  way to detect a replacement was to read `EvitaSessionContract#getCatalogId()` in the very session that registers
  the subscription and compare it with a separately stored value - and every consumer had to discover that on its
  own.
- Nothing on the registration path compared the position with the catalog. A position past the newest version was
  served as an empty stream that waited for the version to appear.
- The gRPC heartbeat supplier looked the catalog up **by name** on every beat
  (`evita.getCatalogInstanceOrThrowException(catalogName).getVersion()`), so a stream that outlived a replacement
  reported the replacing catalog's version, and after a rename it threw.
- Asynchronous CDC failures bypassed the gRPC error mapping: `AbstractChangeCaptureSubscriber#onError` handed the raw
  throwable to the response observer, so the client saw `UNKNOWN` with no error code - the #1446 retention failure
  included. The driver passed the raw `StatusRuntimeException` to `onError`, and a failure before the ACK came out of
  `subscribe()` wrapped in `GenericEvitaInternalError`. A consumer could not react to either in code.

## Options considered

### F1 - the error type

**Chosen: a subtype of `TemporalDataNotAvailableException`** with `Reason` `DIFFERENT_INCARNATION` /
`AHEAD_OF_CATALOG` / `OUTSIDE_RETENTION`, the current `catalogId`, the current version and the requested position.
The #1446 retention path of the catalog publisher switches to the subtype with `OUTSIDE_RETENTION`; system CDC keeps
the plain type, because it has no catalog identity to report. The inherited `getCatalogVersion()` keeps its meaning
- the oldest available version - and the current version is a separate field.

- **Standalone exception type (declined).** **Rejected because:** #1446 shipped (dev and the 2026.2 backport) as
  `TemporalDataNotAvailableException`; consumers that already handle it would miss the new refusals and need a second
  catch for what is one failure class. Changing #1446's type outright would break them, so only subtyping keeps them
  working.
- **Reuse `UnexpectedCatalogIncarnationException` (declined).** **Rejected because:** its identity is the **folder
  token**, its constructor and messages are about long operations (restore/replace) checked at submission, and it is
  unrelated to #1446's type - a consumer would again need two handlers. The folder-token choice there
  (`2026-09-12-restore-catalog-to-earlier-version`) answers "was the target substituted"; this case asks "does this
  version sequence still apply", which is what the UUID answers.

### F2 - a position ahead of the catalog

**Chosen: always refuse** a subscription whose `sinceVersion` is more than one version past the catalog's last
finalized version (`AHEAD_OF_CATALOG`), with or without `catalogId`. Exactly one version ahead is the version the next
change will carry - the default start of a subscription - and stays valid.

- **Refuse only when no `catalogId` is given (declined).** **Rejected because:** with a matching `catalogId` such a
  position cannot have been recorded on this incarnation either; accepting it reproduces the silent wait for a
  version that may take days to arrive.
- **Only log it loudly (declined).** **Rejected because:** the production stall was invisible precisely because
  nobody reads DEBUG/WARN on a healthy-looking stream; a log line does not change what the consumer's code sees.
- **Cost accepted:** a deliberate "start at a future version" subscription stops working. It is indistinguishable
  from a stale checkpoint. Mutation-history reads keep their documented
  behaviour - a forward `sinceVersion` past the newest version yields an empty stream - and honour `catalogId` only.

### F3 - where the identity travels to the consumer

**Chosen: on every capture** (`ChangeCatalogCapture#catalogId`, a new first record component), so the full resume
position can be stored from the stream itself. The engine stamps it once per shared publisher through the
`MutationPredicateContext` the publisher builds - ring-buffer captures are shared objects, so a per-subscriber copy
was never an option.

- **Identity on the subscription only (ACK / heartbeat) (declined).** **Rejected because:** the consumer stores
  positions per processed capture; keeping the identity in a separate, connection-scoped place reintroduces the
  side-channel bookkeeping the issue complains about, and a capture handed on inside the application would lose it.
- **Session-only identity (`getCatalogId()` as today) (declined).** **Rejected because:** it is correct only when read
  in the registering session, which the driver's publisher may open by name on its own - the consumer cannot even
  reach that session.
- **A per-capture gRPC wire field (declined).** **Rejected because:** every capture of one stream belongs to the same
  incarnation, so the ACK carries it once and the driver stamps each capture from it; the history
  RPCs are stamped from the executing session. Repeating the id on every capture message buys nothing.

### F4 - an upgraded driver talking to an older server

An older server ignores the unknown proto field, so a consumer sending `catalogId` would believe it is protected when
it is not. **Chosen: the driver checks itself.** An ACK carrying `catalogId` means the server enforced the check; an
ACK without it (older server) makes the driver compare the expected id with the `catalogId` of the session the RPC
actually ran in - including a session it re-opened by name - and fail with the same exception. History reads check
locally before the RPC, on every server.

- **Refuse to subscribe against an older server (declined).** **Rejected because:** it breaks CDC for everyone who
  upgrades the driver before the server, which is the usual order.
- **Expose a capability flag only (declined).** **Rejected because:** every consumer would re-implement the same
  comparison, which is the burden this change exists to remove.

### Judging "the live version" for F2

- **The session's or the change observer's catalog version, or the larger of the two (declined).** Initially chosen,
  then refuted by review. **Rejected because:** both can lag publication. `Evita#replaceCatalogReference` exposes a
  new snapshot to new sessions before `notifyCatalogPresentInLiveView` reaches the observer, so an old session and
  the observer can both still read V while a consumer has learnt V+1 from a fresh session and legitimately asks for
  V+2 - a false `AHEAD_OF_CATALOG`. The transaction manager's last finalized version is set at finalization, which
  precedes every way a version becomes visible, so no consumer can know a version past it.

### Where the check runs

- **In `Catalog#registerChangeCatalogCapture` only (declined).** **Rejected because:** `TransactionManager#registerObserver`
  is public and would have returned a publisher for any request - a different incarnation or an arbitrary future
  version included. No production caller bypassed `Catalog`, but the check belongs at the boundary every
  registration passes, and the transaction manager knows both the incarnation and the finalized version.

### What to advise a refused consumer

- **"Rebuild the derived state, then subscribe from the head" (declined).** It was the first wording of the
  exception message. **Rejected because:** it loses changes - rebuild from snapshot V, V+1 commits during the
  rebuild, the head subscription starts at V+2, and V+1 never reaches the projection. The advice is to rebuild from
  one session's snapshot and resume at `(session.getCatalogId(), session.getCatalogVersion() + 1, 0)`, rebuilding
  again if that is refused, or to subscribe first and buffer. Silently rewinding the position in the driver was never
  on the table: the consumer must learn that its derived state has a gap.

## Decision

**F1 subtype, F2 always refuse, F3 on every capture, F4 driver self-check**, judged against the last finalized
version, enforced in the transaction manager. Together they make the failure loud, typed and handled by code that
already handles #1446, and they cover the consumer that sends no identity as far as anything can (F2) without
forcing an upgrade order (F4). The per-capture wire field would win only if one gRPC stream could ever carry captures
of two incarnations - which `TransactionManager#notifyCatalogPresentInLiveView` now refuses by premise.

## Key technical details

- `TransactionManager#registerObserver` calls `ResumePositionValidator#assertSubscriptionPosition` (identity, then
  `sinceVersion > lastFinalized + 1`) before the observer creates anything; history reads use
  `#assertSameIncarnation` from `EvitaSession`. The oldest available version is looked up only on the refusal path
  (it lists the WAL folder), and a failure of that lookup is attached as suppressed, never replaces the refusal.
- **Stamp-once relies on an invariant:** a transaction manager serves exactly one incarnation (catalog copies inherit
  identity and manager together; rename keeps both; restore and duplicate get a new manager).
  `TransactionManager#notifyCatalogPresentInLiveView` asserts it before touching any state; dropping that premise
  would let shared publishers stamp captures with an identity they no longer describe.
- Both predicate contexts are stamped: the live one and the WAL catch-up one built separately in
  `ChangeCatalogCaptureSharedPublisher#readWal`. The retention refusal is built by a factory the catalog publisher
  hands to `WalReadResult` (shared with the system publisher, which keeps the plain type).
- `Catalog#createLiveVersionSupplier` feeds the gRPC heartbeat: it follows the incarnation through its transaction
  manager, keeps the max it has seen, never throws (`AbstractChangeCaptureSubscriber#sendHeartbeat` treats only a
  closed stream as terminal) and captures the manager, never a `Catalog` snapshot, so a days-long stream pins no
  catalog in memory (#557).
- gRPC: `catalogId` is field 5 of `GrpcRegisterChangeCatalogCaptureRequest`, `GetMutationsHistoryRequest` and the
  ACK/heartbeat response, field 8 of `GetMutationsHistoryPageRequest`. CDC `onError` goes through
  `GlobalExceptionHandlerInterceptor#sendErrorToClient`; `close()`'s direct `UNAVAILABLE` (#1124) is untouched.
- `ErrorInfoConverter` defines stable `ErrorInfo` metadata keys. The driver rebuilds an exception only when
  `exceptionClass` names one of the two recognised types **and** every required field parses; anything else -
  absent, malformed, unknown, or an older server that puts only the simple class name into `domain` - keeps today's
  handling, so transport statuses are never turned into a refusal.
- Driver identity lives on each `ClientChangeCatalogCaptureSubscriber` (one per `subscribe()`), bound before the RPC
  starts and verified before the ACK future completes or any credit is granted - never on the shared publisher,
  whose streams may straddle a replacement.
- `ChangeCatalogCapture` has hand-written `equals`/`hashCode` and an `as(HEADER)` copy; all three include
  `catalogId`. The 8-argument capture constructor is deprecated for removal (`catalogId` null); the 4-argument
  request constructor stays undeprecated, because a request without an identity is legitimate.

## Verification

- Engine (phase 1): `CatalogChangeCaptureResumePositionTest` - replacement with a **lower** and with a **higher**
  version, history reads, `AHEAD_OF_CATALOG` with and without identity, the next version accepted right after go-live,
  in a session older than the observer, and while both the session and the observer lag a paused notification;
  direct registration with the transaction manager; rename and restart (WAL catch-up) stay valid; stamping on live,
  catch-up and history captures; the incarnation premise; heartbeat through commits, rename, reuse of the old name and
  replacement. Plus `ResumePositionValidatorTest`, `ChangeCaptureResumePositionInvalidExceptionTest`, and the
  retention subtype in `CatalogChangeCaptureWalCatchUpTest` (and its absence in `SystemChangeCaptureWalCatchUpTest`).
  710 tests green, 13 counterfactuals. Highlights: with the identity check off, the higher-version case
  fails with "nothing was thrown" - the refusal really is identity-driven; with `>=` instead of `>`, seven tests
  refuse the legitimate next version; with the catalog retention path left on the plain type, "expected subtype but
  was TemporalDataNotAvailableException"; with the catch-up predicate unstamped, captures replayed from the WAL lose
  their identity.
- Phase 1's own fix round moved the incarnation premise into `TransactionManager#notifyCatalogPresentInLiveView`
  (checked before any state is touched) and made renewing a shared publisher through an observer closed by a
  replacement fail with `InstanceTerminatedException` instead of a `NullPointerException`; 296 tests green,
  3 counterfactuals.
- gRPC and driver (phase 2): `EvitaClientChangeCaptureResumePositionTest` (driver end to end: typed refusal from
  `subscribe()` **and** `onError`, both replacement directions, `AHEAD_OF_CATALOG`, stamping in a session re-opened by
  name, history), `ClientChangeCatalogCaptureSubscriberTest` (older-server match / none / mismatch, server identity
  wins, two streams own their ids, `UNAVAILABLE` still wrapped), `ErrorInfoConverterTest` (malformed, unknown,
  older-server and transport statuses), `ChangeCaptureConverterCatalogIdentityTest`,
  `ChangeCatalogCaptureSubscriberTest`, `EvitaClientErrorTransformationTest`; `AbstractChangeCaptureSubscriberTest`
  changed deliberately (single emission, `INTERNAL` with cause, typed failures as `INVALID_ARGUMENT`). 1,169 tests,
  0 failures; 14+ counterfactuals.
- Review fix round: the lagging-session-and-observer case, direct transaction-manager registration and the lossless
  advice; 1,292 tests green, 5 counterfactuals: "live" judged by the session/observer maximum again refuses the
  next version while both lag ("lies ahead … which is at version 1"); without the non-negative floor guard a position
  below the `-1` retention sentinel is reported as removed; the old "subscribe from the head" advice fails the
  message test for every reason; a remedy test following that advice loses the change committed during the rebuild
  ("expected <[3, 4]> but was <[4]>"); validation left in `Catalog` only lets a direct transaction-manager
  registration of a foreign incarnation through ("nothing was thrown").
- REST and GraphQL (phase 3): `CatalogRestCdcFunctionalTest`, `CatalogGraphQLDataSubscriptionsFunctionalTest`,
  `CatalogGraphQLSchemaSubscriptionsFunctionalTest` and `SystemGraphQLSubscriptionsFunctionalTest` - captures carry
  `catalogId`, the current identity is accepted, a foreign one and a position past the next version are refused
  with a terminal error frame and no capture. REST + engine CDC 662 tests, GraphQL 645 tests, 0 failures. With the
  `catalogId` pass-through dropped in the five fetchers and the REST request, all six different-incarnation tests
  receive the probe capture instead of the error (`expected: <"error"> but was: <"next">`). The REST capture
  serializer wrote `entityPrimaryKey` under the `entityType` key and lost it; fixed and covered on the way.

## Consequences & open follow-ups

- **Behaviour change:** a subscription starting more than one version ahead of the catalog is refused, with or
  without `catalogId`.
- **A consumer that sends no `catalogId` is protected only against a position that lies ahead.** A stale position
  behind the replacing catalog's current version is still served from an unrelated lineage; only the identity closes
  that, which is why the user documentation tells everyone to send it.
- **Pre-existing, not fixed:** `CatalogChangeObserver` can create a shared publisher concurrently with `close()`'s
  sweep and clear, leaving a returned publisher detached and unclosed. The sequential renewal is covered; that
  interleaving is not.
- **The server's error code is not transported** to the driver; the rebuilt exception computes its own on the client
  (`EvitaInvalidUsageException` has no setter, so carrying it would need an `evita_common` change).
- **REST and GraphQL deliver only the message** of a refusal over the stream, not its fields (GraphQL adds the evitaDB
  error code in `extensions.errorCode`, REST not even that); the message is composed from the fields alone, so it
  names reason, position and current identity/version.
- **The driver does no `AHEAD_OF_CATALOG` check against an older server** - only the identity self-check. An older
  server keeps serving a future position as an empty stream.
- **No end-to-end retention test over a live driver stream.** Typed `OUTSIDE_RETENTION` delivery is covered at the
  unit level (converter, subscriber) and in the engine; there is no embedded-server fixture that purges WAL under a
  running gRPC subscription.
- **The Java `HeartBeat` record does not expose `catalogId`.** The gRPC heartbeat carries it; captures carry it to the
  consumer, so it was not needed.
- **Driver-side refusals** (older-server self-check, history) report `oldestAvailableCatalogVersion` as null and the
  current version from the ACK or the session.

## Related work

- `2026-10-01-cdc-catch-up-delivers-everything-owed-or-fails` - #1446's `TemporalDataNotAvailableException`, which
  the refusals here subtype; its retention branch now raises `OUTSIDE_RETENTION`.
- `2026-09-12-restore-catalog-to-earlier-version` - introduced `UnexpectedCatalogIncarnationException` (folder-token
  identity), declined here as the error type; its restore-then-replace flow is one of the ways a CDC checkpoint goes
  stale.
- `2026-08-06-catalog-folder-decoupling` - defines which operations keep `catalogId` (rename) and which mint a new one
  (copied bytes), the property the resume position relies on.

## Timeline

- **2026-10-08** — #1680 reported from the production stall; forks F1-F4 decided; design reviewed (Codex, go with
  changes); engine, gRPC and driver implemented; a second review found the lagging-view false refusal, the public
  transaction-manager bypass and the lossy recovery advice, all fixed the same day
