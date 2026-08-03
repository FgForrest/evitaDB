---
title: Readiness discovery-phase probe failures log at DEBUG; only a known-good endpoint failing logs ERROR
date: 2026-08-03
updated: 2026-08-03 09:17
status: proposed
kind: fix
issues: [1364]
prs: [1366]
areas: [evita_external_api/evita_external_api_core, evita_external_api/evita_external_api_rest, evita_external_api/evita_external_api_graphql, evita_external_api/evita_external_api_system, evita_external_api/evita_external_api_observability, evita_external_api/evita_external_api_lab, evita_external_api/evita_external_api_grpc]
supersedes: []
superseded-by: []
relates: []
---

# Readiness discovery-phase probe failures log at DEBUG; only a known-good endpoint failing logs ERROR

Every external API provider (`RestProvider`, `GraphQLProvider`, `SystemProvider`,
`ObservabilityProvider`, `LabProvider`, `GrpcProvider`) tries several candidate URLs on boot until one
answers, then caches it. Individual candidate failures during that discovery phase now log at `DEBUG`
instead of `ERROR`; a full discovery round that stays empty for more than a 60s grace period logs one
consolidated `WARN` and then goes quiet. Once a provider has a known-good URL, a failure there still
logs `ERROR` immediately — that part is unchanged.

## Why

`AbstractApiOptions#getBaseUrls()` lists the `exposeOn` hostname (a publicly exposed hostname, e.g.
`demo.evitadb.io`) *before* the actual bind addresses. On every boot, the first candidate(s) predictably
fail — the public hostname doesn't route back to the container that just started — before the loop
reaches the working local address seconds later. Each failed candidate logged at `ERROR` with a raw
`Failed to connect to demo.evitadb.io/[...]` message, producing several alarming lines on every single
boot of an otherwise healthy server. Docker's `HEALTHCHECK` (and any other readiness poller) re-triggers
the same discovery loop on every probe until the catalog finishes loading, so a slower boot compounds
the noise across multiple rounds.

### Previous state

All six providers logged `ERROR` unconditionally from inside the `NetworkUtils.fetchContent` /
`isReachable` failure callbacks, with no distinction between "trying candidates, some are expected to
fail" and "the endpoint I already proved reachable just went dark".

## Options considered

### Option A — DEBUG during discovery, WARN once after a grace period, ERROR unchanged in steady state (chosen)

Individual candidate failures are DEBUG while a provider has never found a reachable URL. If an entire
round exhausts and a 60s grace period has elapsed since the first attempt, log one `WARN` summarizing
all attempted URLs and their failure reasons, then suppress further warnings (edge-triggered). Once
`reachableUrl` is set, a failure there logs `ERROR` immediately, exactly as before.

- **Pros:** silences the expected noise without hiding a genuinely stuck server; a stuck server still
  produces exactly one clear signal instead of zero.
- **Cons:** introduces an invented grace-period constant with no natural home in existing config.

### Option B — DEBUG only, no escalation, rely on `/system/readiness` and Docker/k8s health status

- **Pros:** no magic number to justify; readiness/liveness endpoints already exist for exactly this
  purpose.
- **Rejected because:** relies entirely on an operator (or Docker) actively watching the HTTP endpoint;
  it removes the only in-log signal that a server is stuck, and Johnny asked for exactly that signal to
  stay.

### Option C — log one ERROR the first time an entire discovery round is exhausted

The initial, simpler design: downgrade per-candidate logs to DEBUG but log `ERROR` as soon as one full
round comes back empty.

- **Pros:** simplest possible change, no timer needed.
- **Rejected because:** it doesn't fix the reported symptom. The example boot log showed the discovery
  round failing completely three times (07:xx, 09:xx, 12:xx) before the catalog finished loading at
  15:xx — a perfectly healthy boot. Logging on the first exhausted round still produces an alarming
  `ERROR` during ordinary startup.

## Decision

**Chosen: Option A.** A time-based grace period is the only frequency-independent way to distinguish
"still booting" from "genuinely stuck" — round-counting doesn't work because `isReady()`'s call
frequency is driven entirely by external callers (Docker's `HEALTHCHECK --interval`, k8s probes,
evitaLab), not by the provider itself.

A shared full probing abstraction across the 6 providers was considered and declined: they differ in
HTTP method, content type, request body and success predicate, so a shared `probe()` signature would
carry all of that as parameters for little savings. Only the genuinely identical piece — the grace-period
timer and edge-triggered warn — was extracted into `ReadinessDiscoveryStallTracker`
(`evita_external_api_core`); each provider keeps its own local `probe()`/`checkReachable()` helper.

## Key technical details

- `io.evitadb.externalApi.http.ReadinessDiscoveryStallTracker` — holds the 60s `GRACE_PERIOD` constant
  and `shouldWarnAboutStall()`, which records the first-attempt timestamp and returns `true` exactly
  once per instance, the first time the grace period has elapsed. A one-arg constructor overrides the
  grace period (used by the unit test to avoid a real 60s wait). State is held in `AtomicLong`/
  `AtomicBoolean` with `compareAndSet` rather than plain fields, since a single tracker instance is
  shared by its provider across concurrent readiness probes (e.g. an overlapping Docker health check
  and Kubernetes readiness probe) and the exactly-once guarantee must hold under that concurrency.
- Each provider's `isReady()` branches on `reachableUrl == null` (discovery) vs `!= null` (steady
  state); both branches call the same local `probe(url, Consumer<String> failureLogger)` /
  `checkReachable(uri, failureLogger)` helper, differing only in what the failure callback does
  (`log.debug` + collect vs `log.warn` summary vs `log.error`).
- `GrpcProvider` doesn't use `getBaseUrls()`/`exposeOn` (only `getHost()`), so it never hit the original
  bug, but got the same DEBUG/WARN/ERROR treatment for consistency. It also already re-tries the other
  configured hosts when the cached `reachableUrl` fails in steady state (a pre-existing self-healing
  fallback the other five providers don't have) — preserved as-is, not extended to the others.
- `reachableUrl` is still never reset to `null` once set, in any of the six providers (pre-existing).
  A provider that goes from ready to permanently unreachable keeps re-testing the same dead URL and
  logging `ERROR` forever rather than re-discovering an alternate — except `GrpcProvider`, which already
  falls back. Left as-is; out of scope for this change (see follow-ups).
- `ReadinessEvent.finish(Result.ERROR/TIMEOUT/READY)` fires identically in every branch — this is a
  logging-only change, the readiness metrics/telemetry are untouched.

## Verification

`mvn compile` on each touched module (`evita_external_api_core`, `_rest`, `_graphql`, `_system`,
`_observability`, `_lab`, `_grpc/server`) succeeds. `ReadinessDiscoveryStallTrackerTest`
(`evita_test/evita_functional_tests`) covers: no warning before the grace period, exactly one warning
after it elapses, and exactly one winner among 16 threads calling `shouldWarnAboutStall()` concurrently
past the grace period — 3/3 passing.

## Consequences & open follow-ups

- A provider whose known-good URL goes permanently dark logs `ERROR` on every subsequent poll forever
  (unchanged pre-existing behavior for Rest/GraphQL/System/Observability/Lab). Re-discovering an
  alternate host the way `GrpcProvider` already does would need `reachableUrl` reset semantics
  reconsidered — a genuine fork, deliberately left untouched here since it wasn't part of the reported
  symptom.
- The 60s grace period is a single named constant (`ReadinessDiscoveryStallTracker.GRACE_PERIOD`); if a
  deployment's catalog load routinely exceeds it, the only symptom is one extra (calm) `WARN` line, not
  a functional regression.

## Timeline

- **2026-08-03** — reported, designed (with advisor review), implemented, ADR written.
