---
title: Route external API requests with own copy-on-write routers instead of the Undertow-derived tree or Armeria reconfigure
date: 2026-11-16
updated: 2026-11-24 15:20
status: accepted
kind: refactor
issues: [1956, 1959, 1967, 1968]
prs: [1957, 1974]
areas: [evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/http/routing, evita_external_api/evita_external_api_rest, evita_external_api/evita_external_api_graphql, evita_external_api/evita_external_api_system, evita_external_api/evita_external_api_lab, evita_external_api/evita_external_api_observability, evita_test/evita_performance_tests]
supersedes: []
superseded-by: []
relates: [2026-08-02-editorconfig-formatting-parity, 2026-11-24-readiness-waits-for-catalog-publication]
---

# Route external API requests with own copy-on-write routers

The path routing copied from Undertow (`io.evitadb.externalApi.utils.path`, 20 files, 5,997 lines, no tests) is
deleted. REST, GraphQL, system, lab and observability requests are dispatched by five small classes in
`io.evitadb.externalApi.http.routing`: a prefix router whose whole table is immutable and published through one
volatile field, and an immutable endpoint router built once per API. Routes are matched on the still-encoded
request path.

## Why

The hard requirement of the external APIs is that changing the endpoints of one catalog never disturbs requests
to any other catalog, and that a replaced catalog is never observable as missing. The Undertow-derived tree met
the first half by construction but had no tests, 3,459 unreachable lines (an LRU cache that no caller enabled),
and several defects that could only be fixed by understanding foreign code. The request path is latency and
allocation sensitive, so a replacement had to be measured against it, not assumed.

### Previous state

Three nested levels, all behind one Armeria service registered at `glob:/**`: API prefix → catalog prefix →
method and path template. Only the catalog level changes at runtime. Known defects of that tree:

- A REST refresh was unregister then register, so the refreshed catalog answered 404 in between. A GraphQL
  catalog was published with its data endpoint first and its `/schema` endpoint later.
- Every routing level parsed a new `java.net.URI` and percent-decoded the whole path before splitting it, so
  `%2F` became a segment separator and `%3F` cut the path as if it were the query separator; nested levels decoded
  twice.
- Every prefix match formatted a debug message with `String.format` even with debug logging off.
- A known path with a wrong method answered 404 (the method probe matched the method name, not the path).
- Path and query parameters travelled to REST handlers as `internal_<name>` request headers, which a client could
  also send.
- Matching a localized REST path was linear in the number of collections of the catalog.

## Options considered

### Option A — own routers: immutable table behind one volatile field (chosen)

`PrefixRoutingService` keeps exact and prefix tables in one immutable object. A request reads the field once and
takes no lock; a writer holds a `ReentrantLock`, copies the table and publishes it with one write, re-using the
same handler objects for every path the call does not name. `EndpointRoutingService` is immutable: one segment
trie per HTTP method, built by a `Builder`.

- **Pros:** isolation holds by construction and is testable deterministically; replace is one publication; small
  enough to own and test (5 classes); 3.3–83× the throughput of the old tree on the full path.
- **Cons:** routing code we maintain ourselves; a write costs a rebuild of the table (see Consequences).

### Option B — Armeria `Server#reconfigure` (declined)

- **Pros:** no routing code of our own.
- **Rejected because:** it is the only dynamic mechanism Armeria has (1.43) and it replaces the whole server
  configuration. One catalog's change rebuilds the routes, decorators and virtual host of every catalog, turns
  the JVM-wide route cache cold for all of them, and publishes the new configuration before `serviceAdded` runs —
  a window Armeria itself fixed twice as a 500 in stock decorators (#6832, #7008). That contradicts the hard
  requirement. Judged from source only (no load probe): unchanged routes see no 404 window, but the swap takes no
  lock and goes through a non-volatile field, replaced configurations are never closed (one TLS provider leaks per
  call), stale route-cache entries keep a replaced catalog's API reachable, and the API is `@UnstableApi`.
  Revisit only if Armeria gains a per-route add/remove API.

### Option C — own prefix dispatcher, Armeria `Route#apply` for templates (declined)

- **Pros:** template grammar maintained upstream.
- **Rejected because:** Armeria's routing trie is package-private, so matching would be a linear scan over all
  routes of a catalog, each `apply` allocating a routing result and needing a path-rewriting context wrapper.
  That keeps the linear cost this change removes. Revisit only if Armeria makes `Routers` public.

### Option D — keep the tree, delete only its dead cache (declined)

- **Pros:** no behaviour risk.
- **Rejected because:** about 2,500 untested foreign lines and all the defects above would remain.

## Decision

**Chosen: Option A.** It is the only option that satisfies the isolation requirement by construction, and the
measurements below show it is faster and less allocating than what it replaces. Decisions inside it:

- **One trie per HTTP method, not one shared trie.** A shared trie can stop at a more specific terminal that has
  no handler for the request method. A generated route set shows it: collections named `en` and `get`, the second
  localized, give GET `/en/get/{primaryKey}` and POST `/{locale}/get/list`; POST `/en/get/list` must reach the
  second. The 405 answer is a cold-path probe of the other methods' tries.
- **Narrow wildcard grammar.** `*` is accepted only as the last segment of a node without a parameter child, and
  the builder rejects anything else. Undertow ordered `/foo/*` before `/foo/{id}`; forbidding the combination
  removes the ambiguity. Only lab's `/*` uses a wildcard.
- **The endpoint router is immutable.** No production caller adds a route after publication once GraphQL builds
  a catalog's data and schema endpoints together, so there is no lock, no volatile and no copy-on-write `add`.
- **Match on the encoded path.** Delimiters are found before decoding (RFC 3986); a segment is decoded only when
  it contains `%`, once, as strict UTF-8, and `+` stays a plus. Captures are decoded after the route is chosen.
  Registrations stay decoded: matching encoded bytes against encoded registrations was declined because it would
  have to reproduce Armeria's internal normaliser (`DefaultRequestTarget`) exactly. The design relies on Armeria
  keeping `%2F` / `%3F` escaped, decoding unreserved characters and collapsing raw `//` — recheck on upgrade.
- **Parameters travel in a request-context attribute** (`RouteParameters`), not in request headers.

## Key technical details

- Entry points: `http/routing/PrefixRoutingService` (the only mutable level; `replacePrefixPath` is one
  publication), `EndpointRoutingService.Builder`, `RegionRoutingTable` (lookup of a path region without a
  substring), `RoutingPath` (scan, split and decode), `RouteParameters`.
- Invariant: a published table and everything reachable from it is never written again. A writer must build a
  complete new table and assign the field once; assigning twice in one call reintroduces the missing-route window
  (the long-running test's calibration mutation). Removing `volatile` from the table field is not reliably caught
  by any test.
- Invariant: a new table re-uses the handler instances of every path not named in the call. Rebuilding or
  re-decorating them breaks isolation without failing any status-code assertion — the unit tests compare
  identity for this reason.
- The prefix router rewrites `:path` to the encoded remainder for the nested level and drops the query there;
  handlers read the original query from `ctx.query()`. The Prometheus adapter now does so too, and returns the
  values of the requested header name. `PrometheusEndpointRoutingTest` proves over the production server's two
  prefix levels that two `name[]` filters each keep their own metric and exclude the other's, and that `Accept`
  selects OpenMetrics with its end marker. Passing real header values made the exporter's gzip, debug and
  empty-result branches reachable; each calls the adapter with a body length of 0, and the adapter set `:status`
  only for a positive length, so a default Prometheus server scrape (it always sends `Accept-Encoding: gzip`) would
  have answered 500. The adapter now always sets the status; the same test class covers gzip, `debug`, an empty
  filter and `HEAD` (each red before). The rewritten `:path` is read
  by lab's `GuiHandler` and error logging; certificate download and `FileService` select their file without it.
- An upgrade to an ordinary REST GET closes with WebSocket code **1008**, reason
  `WebSocket connection is not available for this path.`, with no server ERROR. HTTP decorators implement
  `WebSocketHandler` even for HTTP-only leaves, so their shared `unwrapWebSocketHandler` returns the existing
  not-found handler when the leaf lacks that capability. Endpoint capability detection and automatic CONNECT
  registration stay unchanged. A temporary Armeria client test reproduced HTTP 500 handshakes and one
  ``HttpService `CorsService` is not WebSocket handler.`` server ERROR per transport before the fix; after it,
  HTTP/1.1 upgrade and HTTP/2 extended CONNECT both closed with 1008 and zero ERRORs. The real CDC control
  acknowledged on both transports (101 and 200 respectively). The probe was deleted after passing;
  `EndpointRoutingServiceTest.shouldCloseWebSocketsForWrappedHttpOnlyHandler` retains the fallback and CONNECT
  forwarding assertions.
- Looks like a bug but is intended: literal > parameter > wildcard with backtracking; an identical template
  registered twice for one method silently keeps the first handler, while an equivalent template with different
  parameter names throws; a `CONNECT` entry that exists only through the WebSocket auto-registration neither
  appears in `Allow` nor turns a 404 into a 405; a malformed escape or invalid UTF-8 answers 404 in-process.
  Over the wire Armeria 1.43 answers 400 for a malformed `%` triplet before routing (by reading its source), so a
  client sees our 404 only for invalid UTF-8.
- The server's public certificate is served at the fixed route `/system/server.crt` in both certificate modes
  (generated, or `api.certificate.custom.certificate`) and advertised whenever an endpoint requires TLS;
  `client.crt` / `client.key` only in generated mode (#1968). Serving a custom certificate under its own file name
  was built first and dropped: it needed literal routes and start-up conflict checks against the fixed system
  routes, no client could discover the name, and the same registration served client credentials stored next to
  the certificate. A certificate downloaded over plain HTTP must be checked against the start-up fingerprint
  (`documentation/user/en/operate/tls.md`), which the server now prints in both modes
  (`ServerCertificateFingerprintTest`). `CustomCertificateDownloadTest` covers both modes, a server without TLS
  endpoints and the Java client, whose "server does not distribute client certificates" message is reserved for the
  server's 404 (a local write failure stays a download failure).
- Path-traversal safety rests on Armeria rejecting dot segments (also `%2e%2e`) before routing and on lab's
  `GuiHandler` checking the remainder against an ASCII whitelist; the routers resolve no dot segments and pass
  escaped control characters such as TAB through to captures.

## Verification

Functional: `PrefixRoutingServiceTest`, `EndpointRoutingServiceTest`, `RegionRoutingTableTest`, `RoutingPathTest`,
`RestRouterTest`, `GraphQLRouterTest`, `CatalogRenameRoutingTest`, `RestParameterRoutingTest`,
`CustomCertificateDownloadTest`, `PrometheusEndpointRoutingTest`, `PrometheusServerRestartTest`,
`CatalogSessionRegistryHandoverTest`, `StaleCatalogHostEventRoutingTest`, `ServerCertificateFingerprintTest`. Until its
deletion a differential test fed the old and the new routers the same generated route sets and requests; the only
permitted difference was the 405 oracle. The churn test's opt-in saturation ramp (`api.churn.mode=ramp`) has never
been run, so no latency-under-load comparison of the two routers exists.

Long-running: `LongRunningPrefixRoutingPublicationTest` was calibrated by two mutations (publish an empty table
first; remove before replace) — each turned it red with a 404 within its 25-second sweep.
`LongRunningExternalApiChurnTest` detected a non-atomic REST refresh once in a 60-second run, and on the code
before this change it reported the GraphQL data-without-schema window.

JMH, 2026-11-14, JDK 21.0.13, 24 cores, 2 forks, 3 × 1 s warm-up, 5 × 1 s measurement, `-prof gc`, old and new
router in the same invocation, background load of about 1.7 cores (ratios are the finding, absolutes ±10 %):

| Scenario | Old | New |
|---|---|---|
| Full path, GraphQL / REST literal / REST parameter | 0.44–0.60 ops/µs, 5.1–6.3 kB/op | 1.56–2.66 ops/µs, 1.7–1.9 kB/op |
| Full path, localized REST, 5 / 50 / 500 collections | 0.37 / 0.15 / 0.02 ops/µs, 7.7 / 20 / 142 kB/op | 1.5–1.7 ops/µs, 1.9 kB/op |
| One prefix level, hit | 3.0–3.7 ops/µs, 1.4–1.7 kB/op | 4.2–5.1 ops/µs, 0.75–0.85 kB/op |
| Endpoint literal | 12.5–14.1 ops/µs, 160–208 B/op | 13.9–14.2 ops/µs, 0 B/op |
| System / lab / GraphQL literal routes | 3.6–15.3 ops/µs, 152–1,368 B/op | 20.7–32.5 ops/µs, 0 B/op |
| Reader during catalog churn (4 threads) | 1,064–1,587 ns/op | 299–431 ns/op |
| Catalog replacement, 10 / 100 catalogs | 19–21 / 23–25 µs | 19 / 29 µs |
| Wrong method | 1,680–1,776 B/op (404) | 1,840–1,944 B/op (405 with `Allow`) |

One endpoint router, old → new, ops/µs (B/op): parameter 2.7–3.1 → 8.1–8.3 (~1,490 → 96); localized REST at 5 /
50 / 500 collections 1.12 / 0.19 / 0.023 → 6.3–6.6 (3.3k–138k → 144); near miss 0.72 / 0.12 / 0.012 → 1.9–2.0
(4.2k–139k → ~1,580); prefix miss 2.9–3.2 → 3.2–3.9 (~2,120 → ~1,590). The old router formatted a debug message on
every prefix match, which cost as much as the match itself.

The new routers were faster and less allocating in all 45 full-path configurations (3.3–83×, geometric mean
5.1×). Two places are worse than before: a catalog replacement at 100 catalogs is 15–28 % slower, and a
wrong-method request allocates 130–190 B more.

## Consequences & open follow-ups

- **Behaviour changes for clients** (upgrade notes are on issue #1956): 405 with `Allow` instead of 404 for a
  known path with an unsupported method; an escape that decodes to invalid UTF-8 answers 404; `internal_*` request
  headers and the `?internal_<name>` query alias no longer supply parameters; a REST parameter given in both
  the path and the query answers 400 instead of 500. The certificate download changes have their own upgrade
  notes on #1968.
- **A write rebuilds the prefix table**, so its cost grows with the number of catalogs (29 µs at 100). Writes
  happen only on catalog lifecycle and schema changes. The cause of the slowdown at 100 catalogs was not isolated
  from reader pressure; a writer-only benchmark would settle it if catalog counts grow by orders of magnitude. The
  comparison is not like for like: the old writer removed and added, the new one replaces once, and only the
  target catalog had a full route set (the others were decoys), so all rows describe warm repeated hits.
- **The region-hashed table is not faster than a plain `HashMap` with `substring`** (equal within error, about
  9 % slower on a hit at 100 catalogs); it allocates 8–16 % less on a hit. It stays for the allocation; the
  `HASHMAP` benchmark arm is kept so the choice can be re-measured.
- **About 1.7–1.9 kB per request remain** on the full path. Each prefix level copies the request headers to
  rewrite `:path`. Passing an offset in the request context instead would remove the copies, but lab's
  `GuiHandler` and error logging read the rewritten `:path` and would each need an adapter;
  not attributed by a profile and not attempted.
- **Levels that never change after start-up** (server-wide, system, lab, observability) could use stock Armeria
  routes, as gRPC does with `serviceUnder`; left for a separate change. GraphQL WebSockets still need the single
  root `WebSocketEnablingService`.
- **Manager bookkeeping is unsynchronised.** `RestManager` and `GraphQLManager` keep plain sets and maps and rely on
  the change-capture subscription running each observer's callbacks one at a time; a second caller would need a
  lifecycle owner with its own lock. Cleanup after a failure is not transactional.
- **Rename and overwrite publish the new name before removing the old one.** The observers reuse the refresh path: REST
  replaces the target's prefix in one publication; GraphQL builds both instances and then swaps the references behind
  its stable routes (two adjacent writes, so data can briefly be newer than schema), which is what lets handlers of open
  WebSocket connections see a refreshed instance. Replacing the GraphQL prefix once, which would make data and schema
  atomic as REST is, was built and reverted: handlers obtained before the refresh kept serving the old instance
  (`GraphQLRouterTest` red; open WebSockets by reading). A failed build removes target and source, because the catalogs
  they served no longer exist. `CatalogRenameRoutingTest` and `GraphQLRouterTest` guard ordering, failure cleanup and
  the instance swap for requests routed before a refresh; that an open WebSocket sees the swap, and the
  failed-registration cleanup in `GraphQLManager`, rest on reading the code. The churn test fails on a routing 404 of a
  replace target: 589 misses in 1,918 protected probes in one run with the old order restored, none in 976 (and in 961
  on the final tree) without. With the 500 acceptance removed and the engine fixes in, a 60-second run on 2026-11-15
  had 46,757 requests, no failure, no routing miss in 1,028 protected probes and no ERROR in the log (the run before
  the engine fix: two REST 500s and three session-close ERRORs). The guarantee is routing continuity, not query
  success: while a rename is built the old name stays routed but its sessions fail with "catalog not found" (400),
  and an overwritten target briefly serves its previous API over the new catalog's data (by reading).
- **A late "inactive" or "removed" host event no longer unregisters a newer catalog of the same name.** Host
  events and engine mutations reach a CDC subscriber as separate executor tasks, so one drain can deliver
  "deactivate B" and "replace B with A" before the older INACTIVE(B) event; the observers used to unregister the
  new B then, leaving a routing 404 until the next refresh. They now ask the engine first and keep the routes when
  the name is bound to an active catalog. `StaleCatalogHostEventRoutingTest` reproduces the order through the real
  CDC pipeline (deactivate/replace and delete/rename, REST and GraphQL). The `HostSystemEvent` JavaDoc no longer
  claims that host events are strictly ordered with engine mutations.
- **Open, from reading only:** the reverse order — a host ALIVE event processed before an older eager
  "deactivate" or "remove" engine mutation, which then unregisters an active catalog — is not guarded; activation
  emits a second ALIVE event that would repair it, and no run has shown it.
- **A REST request whose session the engine terminates now answers HTTP 400 with an error code**, fixed in
  PR #1957 for #1959 by throwing `InstanceTerminatedException("session")` from the inactive-session check.
  GraphQL already reports a terminated session as a coded error in an HTTP 200 response (by reading).
  `RestEndpointExecutionContextTest` guards the real session drain and the REST error response. A later run
  also saw one HTTP 400 "Schema for entity with name `Product` was not found!" on a catalog being replaced;
  not analysed. The test accepts any error
  carrying an error code on catalogs under churn and reports unlisted ones as `other`, because it guards
  routing; catalogs that are not being changed stay strict.
- **The churn test also exposed an engine defect in a renamed/replaced session registry.** Its supplier cast
  a transitional `UnusableCatalog` to `Catalog` while delete/deactivate drained sessions, skipping the termination
  callback and proxy finalization. Fresh and derived registries now share the existing placeholder-exception
  mapping in `Evita#getCatalogForSessionRegistry`. `CatalogSessionRegistryHandoverTest` proves complete public
  termination callbacks in `shouldCompleteSessionTerminationWhenDeletingReplacedCatalog` and
  `shouldCompleteSessionTerminationWhenDeactivatingRenamedCatalog`; restoring only the operator's cast makes
  both fail again. A read-write session with an open transaction hit the same teardown one step later, for fresh
  and derived registries alike: the drain marks the transaction rollback-only and counting the rollback asked the
  supplier for the catalog being deleted. `SessionRegistry#reportTransactionResolution` now skips the count while
  the catalog is transitioning (two transactional tests in the same class; removing the absorb makes them red).
- **A stopped server releases its Prometheus meters** (#1967). Each `ExternalApiServer` creates a Micrometer
  registry over the global Prometheus registry; it was never released, so after a second server start in one JVM
  an unfiltered scrape answered 500 (`DuplicateLabelsException` for `armeria_build`). The server now closes and
  clears it once Armeria has stopped, and also when construction fails after the registry exists;
  `PrometheusServerRestartTest` guards both. A failed construction still leaves its worker event-loop group
  running (by reading, not addressed). **Open:** two servers alive at the
  same time in one JVM still register `armeria_build` twice (by reading). The functional-test JVM is such a case:
  shared datasets keep their servers alive across classes, so an unfiltered scrape there answers 500 even with
  `@Isolated` (the first full run showed it). The regression test therefore counts its own servers' series in the
  global registry against a baseline instead of scraping over HTTP.
- **Seen while fixing, not addressed (both predate this work):** the Java client reuses its cached per-server
  certificate folder when all three files exist, so after a server switches from generated to custom mode it keeps
  the old files and skips the new "server does not distribute client certificates" message (by reading); and
  `close()` on a read-write session whose transaction a forced drain already rolled back throws
  `RollbackException` saying the rollback was requested by the user (seen in a test).
- **The churn test's margin for a non-atomic REST refresh is thin**: that mutation was caught once per
  60-second run. The duration that makes detection reliable was not measured.
- `removePrefixPath` does not remove an exact path of the same spelling; no production caller unregisters exact
  paths (they are added at start-up only).

## Related work

- [2026-08-02-editorconfig-formatting-parity](2026-08-02-editorconfig-formatting-parity.md) — carved the
  Undertow routing tree out of formatting as vendored code; that carve-out is removed with the tree here.
- [HTTP request routing](../developer/external-api/routing.md) — developer guide to how both routers work.
- [2026-11-24-readiness-waits-for-catalog-publication](2026-11-24-readiness-waits-for-catalog-publication.md) —
  readiness waits until `registerCatalog` has published each start-up catalog's routes into these routers.

## Timeline

- **2026-11-12** — analysis of Armeria `reconfigure`, design reviewed, decisions on 405 and GraphQL publish-once
- **2026-11-13** — routers, switched callers, encoded-path matching and tests; PR #1957 opened
- **2026-11-14** — benchmarks and long-running tests executed; Undertow-derived tree deleted
- **2026-11-15** — churn-found engine supplier fixed; REST duplicate parameters now answer 400; GraphQL build
  events use instance durations; Prometheus query/header transport and HTTP-only REST WebSocket fallback fixed;
  Prometheus meters released on server stop (#1967); public certificate at `/system/server.crt` in both modes
  (#1968)
- **2026-11-16** — catalog bookkeeping made safe for change-capture callbacks that arrive while the web API is
  still being built; `/` no longer matches a lone `/{id}`; developer guide to the routers written; PR #1957
  merged, followed by PR #1974 (routing path bounds packed with `NumberUtils#pack`)
