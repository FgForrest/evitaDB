---
title: Never decorate a streaming gRPC channel with RetryingClient
date: 2026-08-05
updated: 2026-08-05 09:45
status: accepted
kind: fix
issues: [1388]
prs: [1389]
areas: [evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver]
supersedes: []
superseded-by: []
relates: [2026-08-03-driver-connection-resilience, 2026-08-04-client-pool-fail-fast-and-cdc-channel-isolation]
---

# Never decorate a streaming gRPC channel with RetryingClient

Armeria's `RetryingClient` freezes the call's response-timeout budget at call start. On a unary call that
is invisible. On a streaming call it is fatal, because the driver's whole design for long-lived streams is
a *rolling* deadline re-armed on every message — and a frozen deadline cannot be re-armed. The driver now
builds separate channels for unary and streaming stubs, and only the unary one is decorated.

## Why

`2026-08-03-driver-connection-resilience` (#1367) made the `RetryingClient` decorator unconditional so
that provably-unprocessed requests are always replayed. That decision is sound for unary calls and is
kept. Its unexamined side effect was that **every long-lived streaming driver call started dying at
exactly 15 s from call start**, whatever progress was flowing:

`goLive`, transaction commit (`closeNow` / `closeNowWithProgress`), `applyMutationWithProgress`,
`restoreCatalog`, `fetchFile`, mutation-history streaming, backup progress, and CDC capture streams.

Two of those are worse than the reported symptom. `closeNow` is **transaction commit** — a commit that
takes over 15 s to reach `WAIT_FOR_CHANGES_VISIBLE` reports failure for work the server completed, which
is the worst possible outcome. `restoreCatalog` and `fetchFile` are **whole-file backup transfers**, so
the cap became "whatever fits in fifteen seconds."

The failure also surfaced misleadingly, as gRPC `DEADLINE_EXCEEDED: deadline exceeded after
3600000000000ns` — naming the one-hour *gRPC* deadline, which had not expired, rather than the Armeria
*response* timeout, which had. Two clocks on one call, and the error names the wrong one. (They are not
independent — see *Previous state* — which is precisely what makes reasoning about them error-prone: the
first revision of this record got the relationship backwards in the other direction.)

### Previous state

`EvitaClient` built one `GrpcClientBuilder` per `ClientFactory` (ordinary + CDC) and installed
`RetryingClient` on both. Every stub — unary `*FutureStub` and async/streaming `*Stub` alike — came off
those decorated builders. The driver configured **no** Armeria response timeout on the builder, so the
budget `AbstractRetryingClient` captured was Armeria's own
`DefaultFlagsProvider.DEFAULT_RESPONSE_TIMEOUT_MILLIS` (15 s).

Note what this does **not** say: that the gRPC deadline and the Armeria response timeout are unrelated
mechanisms. They are bridged — `ArmeriaClientCall.start()` maps the deadline onto the response timeout —
but that mapping happens on the **root** request context, and `AbstractRetryingClient` runs each attempt
in a *derived* context whose timeout it overwrites from its own frozen `State`. That is why the
per-message re-arm, which also targets the root context via `ClientRequestContext.current()`, could not
move the deadline that was actually cancelling the stream. See *Key technical details*.

Streaming call sites re-arm per message with
`ClientRequestContext.current().setResponseTimeout(TimeoutMode.SET_FROM_NOW, streamingTimeout)`. That
re-arm executed and did nothing, because `AbstractRetryingClient$State.deadlineNanos` is `final` and
computed once at call start.

**The defect predates #1367.** Anyone who set `retry(true)` on 2026.2.0 had the same cap. #1367 did not
create the interaction; it made it the default.

## Options considered

### Option A — build separate undecorated channels for streaming stubs (chosen)

`createGrpcClientBuilder` takes the retry rule as an explicit `@Nullable` parameter, and the client builds
three builders: unary (decorated), streaming (undecorated), CDC (undecorated). The streaming builders also
seed `responseTimeout` from the client's own `streamingTimeout`.

- **Pros:** removes the freeze rather than enlarging it, so the per-message re-arm works as designed and a
  stream can outlive any fixed budget as long as it keeps producing. Preserves #1367's intent exactly
  where it can apply. Nothing is lost for streams that have *started*: the always-on rule is
  `onUnprocessed()` only, and a server-streaming call already emitting messages is by construction not
  unprocessed.
- **Cons:** streaming channels lose `onUnprocessed()` retry for calls that never reached the server at all
  (see *Consequences*).

### Option B — keep the decorator, set an explicit large response timeout (declined)

Leave `RetryingClient` everywhere and give streaming channels a generous `responseTimeout` so the frozen
deadline is large instead of 15 s.

- **Pros:** one line; preserves `onUnprocessed()` retry on every channel including stream establishment.
- **Rejected because:** it makes the frozen deadline *bigger*, not absent. A stream that legitimately
  outlives `streamingTimeout` — a large `restoreCatalog`, or a CDC capture stream, which is designed to
  live indefinitely — still dies with no way to extend it, and the per-message re-arm remains inert. It
  converts a certain failure at 15 s into a certain failure at 300 s, and silently discards the only
  mechanism the driver has for telling "slow but alive" from "dead." Worth revisiting only if the
  connect-retry loss in *Consequences* proves to matter more than unbounded stream duration, which for
  CDC it cannot.

### Option C — document the cap and require callers to configure around it (declined)

- **Rejected because:** there is no configuration that reaches it. The frozen budget comes from an Armeria
  global default, and the only lever, `-Dcom.linecorp.armeria.defaultResponseTimeoutMillis`, is JVM-wide —
  it would have to be raised for unary calls too, discarding the timeout protection everywhere to fix
  streaming.

## Decision

**Chosen: Option A.** Retry decoration is a property of *call shape*, not of configuration: unary calls
may be replayed and may carry a fixed total budget; streaming calls may not carry one, because their
duration is not knowable at call start. The split is now structural — `createGrpcClientBuilder` cannot be
called without deciding, since the retry rule is a required parameter.

## Key technical details

- `AbstractRetryingClient.execute:85` — `new State(config, ctx.responseTimeoutMillis())`;
  `AbstractRetryingClient$State:283` — `deadlineNanos` is `final`, `System.nanoTime() + responseTimeoutMillis`,
  computed once. Nothing recomputes it. (Armeria 1.40.0.)
- **Why the per-message re-arm cannot reach it.** Each retry attempt runs in a derived context —
  `DefaultClientRequestContext:638-639` builds it as `CancellationScheduler.ofClient(ctx.remainingTimeoutNanos())`,
  and `AbstractRetryingClient:179-188` then overwrites that from the frozen `State`. The driver's re-arm
  (`ClientChangeCaptureSubscriber:378-381`) calls `ClientRequestContext.current()`, which is the **root**
  context `ArmeriaClientCall` holds. Re-arming the root does nothing to the derived scheduler that
  actually cancels the stream. Removing the decorator removes the derived context, which is why the split
  works and why merely enlarging the frozen budget (Option B) would not.
- `EvitaClient.createGrpcClientBuilder(uri, factory, retryRule, responseTimeout, …)` — the decorator is
  installed only when `retryRule != null`. **`retryRule` must be NULL for any builder whose stubs issue
  streaming calls.**
- **The invariant, enforced by the type system:** every unary `*FutureStub` (3) comes off the
  `EvitaClientChannel.Unary` channel; every async/streaming `*Stub` comes off `Streaming` or `Cdc`.
  These are three distinct types (`EvitaClientChannel`, a sealed interface over the same
  `GrpcClientBuilder`), precisely so a crossover **fails to compile**. It previously did not: the three
  builders were the same type, so transposing them compiled, ran, and passed every existing test while
  silently reintroducing the cap.
- **The builder-level `responseTimeout` seeding is defence in depth, not the fix — measured, see
  *Verification*.** An earlier revision of this record claimed it was "the load-bearing half for CDC"
  and that without it a capture stream dies at 15 s before the first heartbeat. That is wrong, and the
  reason is worth knowing, because it is the same two-clocks confusion that produced #1388 in the first
  place — only inverted. `ArmeriaClientCall.start()` (Armeria 1.40.0, lines 233-245) **maps the gRPC
  deadline onto the Armeria response timeout**:

  ```java
  if (callOptions.getDeadline() != null) {
      remainingNanos = callOptions.getDeadline().timeRemaining(NANOSECONDS);
      ctx.setResponseTimeout(TimeoutMode.SET_FROM_NOW, Duration.ofNanos(remainingNanos));
  }
  // Must come after handling deadline.
  final HttpResponse res = ClientUtil.executePreClientWithFallback(preClient, ctx, ...);
  ```

  Every streaming call site in the driver applies `withDeadlineAfter(streamingTimeout)` — via
  `EvitaClient#executeWithStreamingEvitaService`, `EvitaClientSession#executeWithStreamingEvitaSessionService`
  and `EvitaClientManagement` — so on those paths the deadline mapping overwrites whatever the builder
  configured, before the decorator chain runs. The two clocks are **bridged at call start**, not
  independent. The seeding is kept because it costs nothing and covers any future call site that forgets
  the deadline, but it is not what keeps a stream alive today.
- `EvitaClientManagement`'s async stub moved too. It looks like a unary stub but
  `executeWithEvitaBlockingService` has exactly two callers — `restoreCatalog` and `fetchFile` — both
  whole-file streaming transfers.

## Verification

Two independent measurements, both against the shipped fix.

**The reported symptom.** `goLive` on a 68k-entity catalog with
`-Dcom.linecorp.armeria.defaultResponseTimeoutMillis=2000`, using a build carrying *only* the decorator
split so the JVM-wide default still reaches the channel and the comparison stays discriminating:

| build | budget | result |
|---|---|---|
| 2026.2.2 | 2000 ms | aborted after **2.005 s** |
| + decorator split | 2000 ms | completed in **4.06 s**, 3 progress events, no abort |

The pre-fix cap tracked the flag one-for-one (15000 → 15.007 / 15.008 / 15.034 s across three full
386k-entity loads; 2000 → 2.005 s), and the fixed build outlives the budget by 2×.

**CDC — and which half actually carries it.** `LongRunningCdcHeartbeatTest` against HEAD: **17
heartbeats, indices 0–16 with no gaps, over 6 min 40 s** — 26× the old cap — with zero
`ResponseTimeoutException`. The run ended on a deliberate `timeout 420` bound, not a failure.

The first revision of this record attributed that to *both* halves — the decorator split and the
`responseTimeout` seed — on the strength of an earlier reading that showed death at 14.997 s with only
the split applied. That attribution was **retracted after an A/B measurement**, run on
`shouldKeepSystemCdcSubscriberAliveViaHeartbeats` with a 2-minute bound (heartbeat interval 25 s,
`streamingTimeout` 30 s, so the run crosses Armeria's 15 s default four times):

| build | heartbeats | missed | elapsed |
|---|---|---|---|
| HEAD | 5, indices 0–4 | 0 | 124.6 s |
| HEAD with the `responseTimeout` seeding disabled | 5, indices 0–4 | 0 | 124.1 s |

Identical. If the seed were load-bearing the second run would have died before heartbeat #2 at +25 s; it
did not. **The decorator split alone carries CDC.** The superseded 14.997 s reading came from the module
that could not boot a server under Maven at all (see below), so it was never reproducible.

**Regression suite.** 266 driver tests, 0 failures, 1 skipped (`-Dgroups=driver`), including
`ClientSessionCancellationCascadeTest` and the CDC publisher/backpressure tests, which drive a real server
through the rewired stubs.

## Consequences & open follow-ups

- **Accepted cost — streaming channels lose connect-level retry.** #1367's always-on
  `onUnprocessed().thenBackoff()` no longer applies to streams. The justification "a stream already
  emitting is not unprocessed" holds for a stream that *started*, not for one that never reached the
  server (connection refused, or `GOAWAY` before the stream was accepted). CDC compensates by design — the
  `HeartBeatSensor` SPI exists so a consumer re-establishes a stale stream — but `restoreCatalog`,
  `fetchFile` and `goLive` have no equivalent layer, so a transient connect failure now surfaces to the
  caller. **If this matters, the fix is a bounded connect-level retry *outside* the retry decorator**, not
  re-decorating the channel, which would restore the freeze.
- **Closed — the invariant is now enforced by the type system.** `EvitaClientChannel.Unary` /
  `.Streaming` / `.Cdc` are distinct types over the same `GrpcClientBuilder`, so building a streaming
  stub from the retry-decorated channel no longer compiles. This was the recommended hardening in the
  first revision of this record and is the reason the "no regression test" item below is acceptable:
  the compiler is a stronger guard than the test would have been.
- **Still open — no runtime regression test for the timeout itself.** A test that discriminates needs a
  stream emitting messages over a span exceeding the initial budget, which needs a controllable slow
  server the driver harness does not have. Every cheaper shape either depends on server timing — passing
  on a fast machine whether or not the bug is present — or asserts wiring that the type system now
  guards anyway. A test that passes for the wrong reason would be worse than this note.
- **`grpcConfigurator` runs once per channel — three times, not once.** The caller-supplied
  `Consumer<GrpcClientBuilder>` is applied **last** to each of the three builders, so it can override
  everything the driver configured. Two consequences worth knowing: a side-effecting configurator runs
  three times, and a configurator that installs its own `RetryingClient` or `responseTimeout` applies it
  to the streaming and CDC channels too, reintroducing this defect from outside the driver. The type
  split cannot guard that, because the configurator receives the raw builder.
- **Fixed in passing — `evita_long_running_tests` could not boot a server at all under Maven**, because
  `jackson-module-parameter-names` and `commons-text` are declared at test scope in
  `evita_functional_tests` and test-scope dependencies are not transitive. Every server-starting test there
  died in `setUp`; masked because they are `@Disabled`. Any empirical claim produced from that module
  before this fix was not reproducible from the command line.
- **Worth knowing for the next review of this kind.** Nothing in #1367's diff revealed this. The freeze
  lives in Armeria's `AbstractRetryingClient`, not in any changed line, and that commit asked the right
  question — *which requests are safe to replay* — and answered it correctly. Reviewing the diff carefully
  and still missing this was the expected outcome. The generalisable lesson is that **installing a
  decorator changes a call's timeout semantics**, and that is the thing to check when one is added.

## Related work

- `2026-08-03-driver-connection-resilience` — the decision that made the decorator unconditional. Its
  *Key technical details* contained the mistaken premise (that the frozen budget was `ClientTimeoutOptions`
  rather than an unset Armeria default); corrected there in the same commit, with the trap recorded in its
  *Consequences*.
- `2026-08-04-client-pool-fail-fast-and-cdc-channel-isolation` — the CDC channel/event-loop isolation this
  builder split sits alongside; both landed in PR #1389, which two agents staged together.

## Timeline

- **2026-08-04** — cap observed as a `goLive` failure while benchmarking a 386k-entity production-catalog bulk load;
  root-caused the same night to `c9e72b8c4`.
- **2026-08-05** — issue #1388 filed, fixed, and verified; CDC half measured after an earlier reading
  against a half-applied fix suggested a second, non-existent cause.
