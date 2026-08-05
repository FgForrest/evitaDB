---
title: Align client/server keep-alive timing and always retry provably-unprocessed gRPC calls
date: 2026-08-03
updated: 2026-08-03 16:30
status: accepted
kind: fix
issues: [1367, 1368]
prs: [1371]
areas: [evita_external_api_grpc/client/driver, evita_external_api_core/configuration, evita_external_api_core/http, evita_server/resources, documentation/user/en]
supersedes: []
superseded-by: []
relates: [2026-07-16-client-session-cancellation-cascade, 2026-08-04-http2-connection-teardown-observability, 2026-08-04-client-pool-fail-fast-and-cdc-channel-isolation]
---

# Align client/server keep-alive timing and always retry provably-unprocessed gRPC calls

Two independently-filed driver defects shared a root cause — the client/server keep-alive contract
introduced by the 2026-07-16 cascade fix was only half-closed — so they are fixed together. #1368: the
driver's keep-alive ping could never keep a connection alive because the server both shipped a tighter
idle timeout than documented and ignored inbound pings as activity. #1367: the driver retries nothing by
default, so a routine server-side connection close (a graceful restart's `GOAWAY`, or a `Connection
refused` while the server is coming back up) hard-fails whatever the application was doing.

## Why

Both issues were filed from reading the shipped 2026.2.1 defaults, not a single reproduced incident, but
the arithmetic is real: a client keep-alive ping that structurally cannot land before the server reaps
the connection is not a tuning problem, it is dead code. And a driver whose only two responses to a lost
connection are the default answer ("time out and blow up") and the ADR-designed answer ("say the outcome
is indeterminate and give up") never actually gets to "retry the small class of calls that are safe to
retry" — because nothing turns retry on by default.

### Previous state

- `ApiOptions.DEFAULT_IDLE_TIMEOUT` was `20000` ms in code, but the shipped `evita-configuration.yaml`
  bound `idleTimeoutInMillis` to `${api.idleTimeoutInMillis:2K}` — `2000` ms via
  `SpecialConfigInputFormatsHandler`'s decimal `K` suffix (confirmed by reading the parser directly, not
  assumed). Neither issue caught this; it was found by reading the actual shipped template while
  investigating #1368, and made the real-world mismatch an order of magnitude worse than described.
- `ExternalApiServer` called Armeria's single-argument `.idleTimeoutMillis(millis)` overload, so the
  server's `keepAliveOnPing` rode Armeria's global default (`false`). Verified directly against Armeria
  1.40.0 sources: `Http2RequestDecoder.onPingRead` calls `keepAliveHandler.onPing()` on every inbound
  client ping, but `AbstractKeepAliveHandler.onPing()` only resets the connection's idle clock
  `if (connectionIdleTimeNanos > 0 && keepAliveOnPing)`. With the flag false, an actively-pinging client
  connection was still reaped on schedule — the server-side half of the keep-alive contract the
  2026-07-16 ADR believed it had closed.
- `ClientConnectionOptions` defaulted to a `30000` ms ping against a `300000` ms client-side idle timeout
  — internally consistent, but never checked against the server's own idle timeout, so the pair was safe
  on paper and inert in practice against either the `20000` ms code constant or the `2000` ms shipped
  default. The `30000` ms ping is not an arbitrary number: the 2026-07-16 ADR set it deliberately as a
  *stall budget* — Armeria couples a ping's ack-wait deadline to the ping interval itself
  (`AbstractKeepAliveHandler.PingWriteListener` schedules the connection's forced close after exactly
  `pingIntervalMillis` if no ack arrives), so a shorter interval directly reintroduces the "GC pause kills
  a live connection" self-inflicted failure that ADR fixed. Any fix here has to keep the ping at or above
  that stall budget, not shrink it to satisfy the idle-timeout arithmetic.
- `EvitaClientConfiguration.retry` defaulted to `false`, and `EvitaClient` only installed the
  `RetryingClient` decorator at all when it was `true`. The installed rule set (`onTimeoutException`,
  `onStatus(SERVICE_UNAVAILABLE, GATEWAY_TIMEOUT, UNKNOWN)`, `onStatus(TOO_MANY_REQUESTS)`) was adequate
  for the reported failures, but simply never ran for the default caller.

## Options considered

### Retry scope: unconditional `onUnprocessed()` vs. flipping the whole flag (chosen: unconditional)

- **Option A — always install `onUnprocessed().thenBackoff()`; keep the broader rule set behind the
  existing `retry` flag, default unchanged (`false`) (chosen).** `UnprocessedRequestException` is
  Armeria's own "certain the server never saw this request" signal (a refused connection, or a `GOAWAY`
  received before the request's stream was accepted) — replaying it can never duplicate an
  already-applied mutation, so it is safe unconditionally.
  - **Pros:** fixes both traces in #1367 (connection-refused-during-restart, and GOAWAY before the
    request was accepted) with zero duplicate-application risk; changes nothing for callers who chose
    `retry=false` on purpose.
  - **Cons:** does not, by itself, retry a request that was aborted *after* the server may have started
    processing it (that stays behind `retry(true)`, unchanged from before).
- **Option B — default `retry` to `true` (the issue's literal suggested fix) (declined).**
  - **Pros:** matches the issue text exactly; simplest possible change.
  - **Rejected because:** the existing rule set includes `onStatus(..., HttpStatus.UNKNOWN).thenBackoff()`
    — Armeria's sentinel for *any* transport abort with no response headers, which includes a request the
    server already processed (e.g. a mutation whose response was lost to a `GOAWAY` mid-stream). The
    2026-07-16 ADR deliberately chose *not* to auto-retry transport failures for exactly this reason
    (`TransportException`'s Javadoc documents the outcome as indeterminate, "a blind retry can duplicate
    an already-applied change"). Flipping the whole flag would silently re-enable that risk for every
    driver user by default, reopening a question that PR already closed.

### Keep-alive numbers: shrink the client's ping vs. raise the server's idle timeout (chosen: raise the server)

- **Option A — lower `ClientConnectionOptions` defaults (ping `30000`→`5000`, idle `300000`→`15000`);
  leave the server's `20000` ms code constant alone; fix the two wiring bugs (declined — first choice,
  reversed after review).**
  - **Pros:** matches #1368's own suggested numbers at face value; a healthy connection's ping/ack traffic
    resets both idle clocks well inside either threshold.
  - **Rejected because:** the ping interval doubles as the ack-wait stall budget (see *Previous state*
    above) — shrinking it to 5 s means any GC pause or event-loop stall over 5 s now self-kills the
    connection, which is precisely the failure class the 2026-07-16 ADR widened the ping from 1 s to 30 s
    to fix. This was caught in review, not before implementation: the first pass shipped this option, ran
    150 tests green, and only a second look connected `PingWriteListener`'s deadline scheduling (already
    read while investigating #1368) to the choice of number. The green tests were not evidence against
    it — the entire dataset-based test lane runs with the ping disabled
    (`EvitaParameterResolver.pingIntervalMillis(0)`), so nothing in the suite can stall an event loop for
    5+ seconds while a ping is in flight to catch this.
- **Option B — raise the server's idle timeout instead (`20000`→`60000` ms, code constant and shipped YAML
  both) and keep the client's ping/idle defaults exactly as they were (`30000`/`300000`) (chosen).**
  - **Pros:** the 30 s ping keeps the full production-derived stall budget; the server's new 60 s idle
    timeout gives it 2× margin over that ping, so a healthy, actively-pinging connection is never reaped by
    either side; with `keepAliveOnPing` now fixed on both sides, the server backstop only matters once
    keep-alive itself has already stopped working (a genuinely dead peer) — the same case Option A also
    only affected, just detected a little slower now (up to 60 s instead of 15 s).
  - **Cons:** a connection that never pings at all (the driver supports `pingIntervalMillis(0)`, and
    several test-lane clients use it) now waits up to 60 s of true silence before the server reclaims it,
    instead of 20 s. Accepted: this was already true relative to the client's own 300 s idle default before
    this fix, or the server's original 2 s shipped bug provided no meaningful protection either way.

## Decision

**Chosen: `onUnprocessed()` unconditionally (retry dimension) and raising the server's idle timeout
(keep-alive dimension).** Unconditional `onUnprocessed()` retry closes the reported failures without
reopening the duplicate-mutation risk the 2026-07-16 ADR deliberately avoided. Raising the server's idle
timeout — rather than shrinking the client's ping — keeps the 30 s stall budget that same ADR chose for a
specific, evidenced reason (production GC/event-loop stalls), while still closing the "ping never lands
before the server reaps" gap: the fix moves to the side of the equation with no lower bound instead of
the side with a hard lower bound.

## Key technical details

- `evita_external_api/evita_external_api_core/.../http/ExternalApiServer.java` — `.idleTimeoutMillis(apiOptions.idleTimeoutInMillis())`
  changed to the two-argument `(millis, true)` overload, mirroring the comment/pattern `EvitaClient`
  already used for the client side.
- `evita_external_api/evita_external_api_core/.../configuration/ApiOptions.java` —
  `DEFAULT_IDLE_TIMEOUT` `20_000` → `60_000`.
- `evita_server/src/main/resources/evita-configuration.yaml` — `idleTimeoutInMillis` default `2K` → `60K`,
  now matching the raised `ApiOptions.DEFAULT_IDLE_TIMEOUT`; `documentation/user/en/operate/configure.md`
  updated to match (hand-edited; the Czech mirror regenerates via Comenius, not touched here).
  `requestTimeoutInMillis` (`2K`) is untouched — a different Armeria setting, out of scope for either issue.
- `evita_external_api/evita_external_api_grpc/client/.../driver/config/ClientConnectionOptions.java` —
  `DEFAULT_PING_INTERVAL_MILLIS` (`30_000`) and `DEFAULT_IDLE_TIMEOUT_MILLIS` (`300_000`) are **unchanged**
  from the 2026-07-16 ADR's values; only their Javadoc gained the cross-reference to the server's new
  `60000` ms default.
- `evita_external_api/evita_external_api_grpc/client/.../driver/EvitaClient.java` — retry-rule
  construction extracted into `static RetryRule createRetryRule(boolean retryEnabled)`; the
  `RetryingClient` decorator is now installed unconditionally (previously only under `if
  (configuration.retry())`), with the `onUnprocessed()` rule always present and the broader rule set
  appended only when `retryEnabled`. `EvitaClientConfiguration.retry` keeps its `false` default —
  its meaning narrowed to "the broader, potentially-duplicating rule set," not "any retry at all." The
  unconditional retry uses Armeria's default backoff and `maxTotalAttempts` (10), but the whole retry
  sequence — attempts plus backoff delays combined — is bounded by the call's existing response timeout
  (`ClientTimeoutOptions`, 5 s by default): `AbstractRetryingClient`'s per-attempt deadline is `min` of any
  per-attempt override (unset here) and the *original* call's remaining deadline, so a down server fails
  no later than today's configured per-call timeout, just with a few backoff attempts inside that budget
  instead of one immediate failure.
- **Invariant a future change must preserve:** the client ping must stay (a) at or above the worst
  tolerable event-loop stall / GC pause — production evidence in the 2026-07-16 ADR put this at multiple
  seconds, hence `30000` ms, not a much shorter probe frequency — **and** (b) strictly below the client's
  own idle timeout (checked today, `EvitaClient`'s existing precondition warning) **and** (c) strictly
  below the server's shipped idle timeout (not checked anywhere — no runtime handshake carries the
  server's value to the client). Constraint (a) has a hard lower bound; (b) and (c) don't, which is why
  this fix raised the server's idle timeout rather than lowering the ping.
- Confirmed via Armeria 1.40.0 sources (not assumed): `Http2RequestDecoder.java:450-451`
  (`onPingRead` → `keepAliveHandler.onPing()`), `AbstractKeepAliveHandler.java:197-213` (`onPing()` only
  resets the idle clock when `keepAliveOnPing` is true), `AbstractKeepAliveHandler.java:320`
  (`PingWriteListener` schedules the forced close after `pingIdleTimeNanos` — the ping interval **is** the
  ack-wait deadline, the source of constraint (a) above), `AbstractRuleBuilder.java:287-288`
  (`onUnprocessed()` is `onException(UnprocessedRequestException.class)`), `RuleFilter.java:98-101` (the
  exception filter matches on the raw `cause` argument, no completed `RequestLog` required — which is what
  makes `EvitaClient.createRetryRule` unit-testable without a network), and
  `DefaultFlagsProvider.java:98` (`DEFAULT_MAX_TOTAL_ATTEMPTS = 10`).

## Verification

- `EvitaClientRetryRuleTest` (new, 3 tests): `createRetryRule(false)` retries an
  `UnprocessedRequestException` and does not retry a bare `CANCELLED` `StatusRuntimeException`;
  `createRetryRule(true)` still retries the unprocessed case. Built directly against
  `ClientRequestContext.of(...)` and `RetryRule.shouldRetry(...)`, Armeria's own sanctioned unit-testing
  seam — no network involved, so no timing dependency.
- `ClientConnectionOptionsTest` — default-value assertions confirm `30_000`/`300_000` are unchanged; the
  existing clamp/fallback tests (already symbolic, not literal) needed no change.
- `EvitaServerTest` — 14/14, confirms the server still boots and answers readiness/liveness/status probes
  with the two-argument `idleTimeoutMillis` overload and the raised `60000` ms default.
- `ClientSessionCancellationCascadeTest` — 1/1 unchanged, confirming the always-installed `RetryingClient`
  decorator does not interfere with a fault injected at the gRPC `ClientInterceptor` layer (that layer
  sits above Armeria's HTTP-level decorators, including `RetryingClient`, so it was never a candidate for
  exercising the retry rule in the first place — the reason a unit-level test was used for the retry rule
  instead).
- `EvitaClientReadOnlyTest` + `EvitaClientReadWriteTest` (the 2026-07-16 ADR's real-thread-pool island) —
  150 tests, 0 failures, 1 pre-existing skip. Re-run after reverting the client ping/idle numbers to their
  original values; note this suite runs in well under a minute per class and never stalls an event loop
  for seconds, so it cannot by itself validate the stall-budget property discussed above — that property
  rests on the unchanged 2026-07-16 production evidence, not on this suite.

## Consequences & open follow-ups

- **Still open — no cross-side precondition check.** #1368 suggested the server could advertise its idle
  timeout during the client handshake so `EvitaClient`'s existing warning could compare against it instead
  of only the client's own value. That is a real protocol change, not a config-default fix, and was
  deliberately left out of this PR's scope.
- **Still open — the server-side `keepAliveOnPing` fix has no automated regression test.** Proving it
  end-to-end needs a real idle-timeout window to elapse, the same class of timing-sensitive test the
  2026-07-16 ADR deliberately declined to write for the client-side stall-kill case. Verified instead by
  direct primary-source reading of Armeria 1.40.0 (cited above under *Key technical details*).
- The 2026-07-16 ADR's "Resolved, not open: the inert-ping defect" entry only ever covered the *client's*
  half of the contract (Armeria silently dropping a ping whose interval isn't strictly below the client's
  own idle timeout). It did not cover the server ignoring inbound pings as activity — that half was an
  unrecognized gap in the original fix, not a regression; this record's `relates` link and this bullet
  are the fix-up.
- **Still open — a fully-silent connection (ping disabled) now waits up to 60 s before the server reclaims
  it**, up from 20 s (or the buggy 2 s shipped default). Accepted for this PR — see the *Options
  considered* rejection of Option A — but if connection-count pressure ever becomes a real operational
  concern, the next lever is a shorter idle timeout specifically for connections that never negotiate a
  ping, not a shorter ping for everyone.

## Related work

- Relates to `2026-07-16-client-session-cancellation-cascade.md`, which introduced the client-side
  `pingIntervalMillis`/`idleTimeoutMillis` knobs and the transport-failure-as-session-loss design this
  record builds directly on. That record's front matter and follow-ups are updated in the same commit to
  point here.

## Timeline

- **2026-08-03** — issues #1367 and #1368 filed; root-caused, fixed and merged the same day.
