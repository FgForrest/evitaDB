---
title: Route external API requests with own copy-on-write routers instead of the Undertow-derived tree or Armeria reconfigure
date: 2026-11-14
updated: 2026-11-14 11:10
status: accepted
kind: refactor
issues: [1956]
prs: [1957]
areas: [evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/http/routing, evita_external_api/evita_external_api_rest, evita_external_api/evita_external_api_graphql, evita_external_api/evita_external_api_system, evita_external_api/evita_external_api_lab, evita_external_api/evita_external_api_observability, evita_test/evita_performance_tests]
supersedes: []
superseded-by: []
relates: [2026-08-02-editorconfig-formatting-parity]
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
  requirement. Revisit only if Armeria gains a per-route add/remove API.

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
- **Parameters travel in a request-context attribute** (`RouteParameters`), not in request headers.

## Key technical details

- Entry points: `http/routing/PrefixRoutingService` (the only mutable level; `replacePrefixPath` is one
  publication), `EndpointRoutingService.Builder`, `RegionRoutingTable` (lookup of a path region without a
  substring), `RoutingPath` (scan, split and decode), `RouteParameters`.
- Invariant: a published table and everything reachable from it is never written again. A writer must build a
  complete new table and assign the field once; assigning twice in one call reintroduces the missing-route window
  (the long-running test's calibration mutation).
- Invariant: a new table re-uses the handler instances of every path not named in the call. Rebuilding or
  re-decorating them breaks isolation without failing any status-code assertion — the unit tests compare
  identity for this reason.
- The prefix router rewrites `:path` to the encoded remainder for the nested level and drops the query there;
  handlers read the query from `ctx.query()`. Lab and system file serving depend on the rewritten `:path`.
- Looks like a bug but is intended: literal > parameter > wildcard with backtracking; an identical template
  registered twice for one method silently keeps the first handler, while an equivalent template with different
  parameter names throws; a `CONNECT` entry that exists only through the WebSocket auto-registration neither
  appears in `Allow` nor turns a 404 into a 405; a malformed escape or invalid UTF-8 answers 404.
- A custom TLS certificate whose file name equals a fixed system route (`status`, `liveness`, `readiness`,
  `server-name`) fails start-up instead of shadowing or being shadowed.

## Verification

Functional: `PrefixRoutingServiceTest`, `EndpointRoutingServiceTest`, `RegionRoutingTableTest`, `RoutingPathTest`,
`RestRouterTest`, `GraphQLRouterTest`, `RestParameterRoutingTest`, `CustomCertificateDownloadTest`. Until its
deletion a differential test fed the old and the new routers the same generated route sets and requests; the only
permitted difference was the 405 oracle.

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

The new routers were faster and less allocating in all 45 full-path configurations (3.3–83×, geometric mean
5.1×). Two places are worse than before: a catalog replacement at 100 catalogs is 15–28 % slower, and a
wrong-method request allocates 130–190 B more.

## Consequences & open follow-ups

- **Behaviour changes for clients** (upgrade notes are on issue #1956): 405 with `Allow` instead of 404 for a
  known path with an unsupported method; a malformed escape or invalid UTF-8 answers 404; `internal_*` request
  headers and the `?internal_<name>` query alias no longer supply parameters; a custom certificate is served at
  `/system/<file>` (it was registered with a doubled slash and could not be downloaded).
- **A write rebuilds the prefix table**, so its cost grows with the number of catalogs (29 µs at 100). Writes
  happen only on catalog lifecycle and schema changes. The cause of the slowdown at 100 catalogs was not isolated
  from reader pressure; a writer-only benchmark would settle it if catalog counts grow by orders of magnitude.
- **The region-hashed table is not faster than a plain `HashMap` with `substring`** (equal within error, about
  9 % slower on a hit at 100 catalogs); it allocates 8–16 % less on a hit. It stays for the allocation; the
  `HASHMAP` benchmark arm is kept so the choice can be re-measured.
- **About 1.7–1.9 kB per request remain** on the full path. Each prefix level copies the request headers to
  rewrite `:path`. Passing an offset in the request context instead would remove the copies, but lab and system
  file serving read the rewritten `:path`; not attributed by a profile and not attempted.
- **`RestManager` records a catalog before registering its API.** A failed registration leaves it recorded, and a
  later refresh publishes it and then fails on missing build statistics. Older than this change, not touched.
- **A REST request to a catalog that is being replaced can answer HTTP 500** ("Session has been already closed")
  from `RestEndpointExecutionContext#session`, because the engine terminates the session under the handler. Found
  by `LongRunningExternalApiChurnTest` (5 of 44,872 requests) and filed as #1959; not shown to predate this
  change, although the code involved is not part of it. A later run also saw one HTTP 400 "Schema for entity
  with name `Product` was not found!" on a catalog being replaced, which the test does not list as a lifecycle
  response; not analysed.
- **The churn test's margin is thin**: the non-atomic refresh mutation was caught once per 60-second run. The
  duration that makes detection reliable was not measured.
- `removePrefixPath` does not remove an exact path of the same spelling; no production caller unregisters exact
  paths (they are added at start-up only).

## Related work

- [2026-08-02-editorconfig-formatting-parity](2026-08-02-editorconfig-formatting-parity.md) — carved the
  Undertow routing tree out of formatting as vendored code; that carve-out is removed with the tree here.

## Timeline

- **2026-11-12** — analysis of Armeria `reconfigure`, design reviewed, decisions on 405 and GraphQL publish-once
- **2026-11-13** — routers, switched callers, encoded-path matching and tests; PR #1957 opened
- **2026-11-14** — benchmarks and long-running tests executed; Undertow-derived tree deleted
