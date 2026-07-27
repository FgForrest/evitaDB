# Assessment response: test-lane execution model (decision record)

External senior-reviewer assessment of `A2_ASSESSMENT_REQUEST.md`. Verbatim, retained as the decision record.
Confirmed locally: the inert-ping defect (sub-question 3) is real — `EvitaClient.java:419` wires `idleTimeoutMillis`
from `clientTimeouts.timeout()` (default 5 s) while `:425` sets `pingIntervalMillis` to the A1 default (30 s);
Armeria zeroes the ping when `pingInterval >= idleTimeout`, so the 30 s client-ping default is currently inert.

## Primary decision — (D): adopt (A) as default, keep A2's mechanism as a per-dataset opt-in island (kernel of (C))

Revert the blanket `directExecutor(false)` in `EvitaParameterResolver`, keep `ServerOptions.Builder.directExecutor(boolean)`
as a capability, disable pings in the test lane via the A1 knobs, and pin the real-pool-only behaviours with a small,
deliberately chosen set of tests that opt into real pools.

- **Not pure (A):** dropping A2 entirely leaves A1 / Part B / CDC with zero integration coverage of the exact
  conditions they fix — and the cost of the island is near zero. `EvitaClientReadOnlyTest`/`EvitaClientReadWriteTest`
  are already green under real pools; the 23 regressions came *only* from GraphQL/REST schema-mutation tests hitting
  the async API-rebuild race. Give the client-driver family real pools, leave the web-API family synchronous.
- **Not (B):** the 23 failures are deterministic contract violations against the "rebuild is synchronous" promise;
  retrofitting readiness barriers into 8+ classes buys fidelity for a subsystem whose async lag is production-correct,
  at maximum cost / minimum value.

## Maintainer leaning (drop A2, disable test pings) — endorsed, with two amendments

Under direct executor, dataset setup routinely holds the event loop inline for minutes (bulk `updateCatalog` builds),
so *any* finite ping is eventually tripped by a legitimate inline call — "long-but-finite test ping" is the same bug
at a lower repro rate (worse). Amendments: (1) disable pings through the **A1 production knobs** (test resolver/client
sets client `pingIntervalMillis=0`; server already 0), not a test-env special case in engine code; (2) replace the
accidental canary with a **deliberate** one (the island).

## Sub-questions

1. **Disable ping entirely in tests? Yes.** With direct executor, ping kills are ~100% false positives. Real
   deadlocks/livelocks are already caught by better detectors (driver `.get(timeout)` 5 s, server `requestTimeoutMillis`,
   surefire timeout) with better diagnostics. Minimal safer alternative: ping enabled only in the dedicated keep-alive
   test (`pingIntervalMillis(1000)`), which is part of the island.
2. **Regression-guard the real-pool-only behaviours — three behaviours, three answers:**
   - **Part B** (transport failure → local deadify, no remote close): already covered by
     `ClientSessionCancellationCascadeTest`'s fault-injecting interceptor (needs neither real pools nor real ping). No
     further work.
   - **CDC register-then-mutate ordering:** the one behaviour that genuinely needs a real-pool integration test (under
     direct executor the race cannot occur and the test passes vacuously). Mark `shouldRenameCollection` +
     `shouldCancelCatalogChangeSubscriberAndEvictPublisherOnServerSide` (or a dedicated subscribe-then-mutate dataset)
     as the real-pool opt-in, generous awaitility timeouts.
   - **A1 keep-alive mechanics:** one slow-call test, real pools + `pingIntervalMillis(1000)`, pinning that a >1 s
     inline stall kills the connection and the widened default survives it. Keep small; if it flakes under saturation,
     move to the isolated/sequential tier rather than deleting.
3. **Client 30 s / server 0 — mostly sound, but the client 30 s ping is currently inert** (see header;
   `ClientFactoryBuilder` zeroes ping when `pingInterval >= idleTimeout`, and the client idle timeout defaults to the
   5 s per-call `timeout()`). Net default behaviour (no client ping, polite idle reaping, server reaper) is fine and
   matches gRPC's client-keepalive-disabled default — but A1's client default is documentation-ware. Remedy: either
   (a) **decouple** the connection idle timeout from the per-call timeout (wire from the streaming timeout / a
   dedicated connection-idle option) so the 30 s ping actually functions and keeps NAT/LB paths warm for long-lived
   CDC streams; or (b) minimally, **document** the `ping < idleTimeout` precondition and log a warning when violated.
   **Server 0 is unambiguously right** (Armeria default; gRPC convention; polite idle close + SessionKiller reap).
4. **Does sync-everywhere mask other concurrency bugs? Yes — the CDC bug is the existence proof.** Answer is not real
   pools everywhere; it's a small **always-real-pools smoke tier** as a separate CI stage with reduced parallelism:
   the `EvitaClient*` family (already green) + the CDC ordering tests + the keep-alive test. Cheap, immediately green,
   first place a future "works sync, races async" bug surfaces. CI-gating but sequential/isolated.
5. **Blocking `subscribe()` still right? Yes** — production correctness isn't contingent on test coverage, and the
   island restores coverage. The 7 self-inflicted unit errors are a genuine design signal: `subscribe()` now
   deadlocks-until-timeout for any caller running on the ACK-delivery executor. So: (a) keep the streaming-timeout
   bound; (b) verify the ACK completion path can never run on the subscriber's calling thread; (c) JavaDoc the
   blocking semantics + timeout. A `subscribeAsync(): CompletableFuture` variant is a reasonable future addition, not
   a blocker.

## Implied follow-up work

1. Revert the resolver's blanket `directExecutor(false)`; keep the `ServerOptions.Builder` knob; add a per-dataset
   opt-in (e.g. a `@DataSet` attribute or resolver flag).
2. Test lane sets client ping 0 via `ClientConnectionOptions` (server already 0); delete the
   `-Devita.test.disableRealPools` scaffolding toggle once the decision lands.
3. Build the real-pool island: cascade test (ping 1000 variant), the 2 CDC ordering tests, `EvitaClient*` family;
   sequential CI stage.
4. Fix or document the inert-ping defect: client `idleTimeoutMillis` decoupled from per-call timeout (or a logged
   warning + JavaDoc precondition).
5. CDC gate hardening: thread-safety verification of the ACK executor + JavaDoc on blocking semantics.
6. Full `unitAndFunctional` rerun with the final configuration (the standing §3a mandate) — the 23 failures disappear
   by construction; expected green modulo the 2 known pre-existing errors.

**Confidence:** high on the primary decision and sub-questions 1, 2, 4, 5; medium-high on sub-question 3's remedy
choice (decoupling idle timeout has its own small blast radius — connection lifetime for all clients — unmeasured).
