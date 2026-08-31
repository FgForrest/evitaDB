---
title: Drop the netty version override instead of pinning tcnative to take netty 4.2.17
date: 2026-08-31
updated: 2026-08-31 14:52
status: accepted
kind: infrastructure
issues: []
prs: [1474]
areas: [pom.xml]
supersedes: []
superseded-by: []
relates: []
---

# Drop the netty version override instead of pinning tcnative to take netty 4.2.17

The root `pom.xml` carried a `netty.version` property and five `netty-codec-*` `dependencyManagement`
entries that pulled netty ahead of the version armeria depends on, so that netty security fixes could
be picked up before armeria's next release. Both are now removed. netty resolves from armeria's own
`netty-bom`, and the netty 4.2.17.Final bump that Dependabot proposed is deliberately not taken,
because taking it through that override breaks every TLS-bearing external-API test.

## Why

The override was written to close netty advisories ahead of armeria, and it carried its own exit
condition in a comment: *drop this override once armeria itself depends on >= 4.2.16.Final*. armeria
1.41.0 pins netty 4.2.16.Final, so the condition was met by a routine dependency bump.

The constraint that made this non-obvious is that the override was not merely redundant at that
point — it had become **actively harmful**, and would have shipped a broken build had the next netty
bump been applied the way every previous one was. The override pins only the `netty-codec-*`
artifacts. `netty-codec-http` depends on `netty-handler`, which owns `SslHandler`, so raising the
codecs silently raises `netty-handler` too. `netty-tcnative` is not a codec and is not pinned, so it
stays on whatever armeria depends on. netty 4.2.17 expects tcnative 2.0.81.Final; armeria 1.41.0
brings 2.0.78.Final. The result is a `netty-handler` that is three tcnative releases ahead of its
native library, and every armeria client TLS handshake fails with `UnprocessedRequestException` /
`StacklessClosedChannelException`.

This is not visible at compile time. `mvn clean install -DskipTests` passes on the broken
combination; only running the external-API tests surfaces it.

### Previous state

`netty.version` sat in `<properties>` with a comment explaining that it overrode armeria's netty to
pick up `netty-codec-*` advisories, and that a full `netty-bom` import was deliberately avoided
because it manages every `io.netty:*` artifact — which was observed to flip `netty-handler`'s
mediated scope from `provided` to `runtime` in `evita_external_api_grpc_shared` and break its
`module-info.java` (`requires io.netty.handler` needs compile-time visibility). Five
`dependencyManagement` entries bound `netty-codec-http`, `-http2`, `-compression`, `-haproxy` and
`-dns` to that property.

That comment also asserted that *mixing patch versions within the same netty 4.2.x line is
binary-compatible, so pinning just the affected artifacts is sufficient and safer*. For
4.2.16 → 4.2.17 that assertion is false. It is true of the Java bytecode and false of the system as a
whole, because the coupling that breaks travels with `netty-handler` and its native library rather
than with the codecs.

## Options considered

### Option A — remove the override entirely, stay on armeria's netty (chosen)

Delete the `netty.version` property and the five `netty-codec-*` entries. netty resolves uniformly
from armeria's `netty-bom` at 4.2.16.Final with tcnative 2.0.78.Final — the combination armeria was
tested against.

- **Pros:** No version skew of any kind. Nothing to keep in sync. The exit condition the original
  comment specified is honoured rather than quietly ignored. Removes a mechanism whose failure mode
  is invisible until runtime.
- **Cons:** Leaves GHSA-8c42-7qj2-3j46 unpatched until armeria ships netty >= 4.2.17, and gives up
  the ability to close a future netty advisory ahead of armeria without recreating the override.

### Option B — keep the override at 4.2.17 and pin tcnative to 2.0.81.Final (declined)

Add six `dependencyManagement` entries — `netty-tcnative-classes` plus `netty-tcnative-boringssl-static`
for each of the five platform classifiers armeria ships — so the native library moves in lockstep
with `netty-handler`.

- **Pros:** Verified to work; closes GHSA-8c42-7qj2-3j46 now.
- **Cons:** Pins native TLS binaries ahead of what armeria was tested against, and the classifier
  list is a hand-maintained duplicate of armeria's platform matrix.
- **Rejected because:** a classifier-keyed pin covers only the classifiers it names. If armeria adds
  a platform, that platform silently resolves to the *old* tcnative while `netty-handler` stays new —
  reproducing exactly the failure being fixed, on one platform only, discoverable only by running the
  TLS tests on that platform. Paying that for GHSA-8c42-7qj2-3j46 is a bad trade, because the
  advisory is a flaw in netty's own `CorsHandler` and evitaDB does not use it (see below). **Revisit
  if** a netty advisory lands that evitaDB demonstrably *does* reach and armeria has not yet shipped
  the fix.

### Option C — keep the override, pinned at 4.2.16.Final (declined)

Leave the property and entries in place, set to the version armeria already supplies.

- **Pros:** Smallest diff; the machinery stays ready for the next advisory.
- **Rejected because:** it is a no-op that reads as load-bearing. Its comment would have to claim a
  reason that no longer holds, and the next person to bump the property would hit the tcnative
  breakage with nothing to warn them. A mechanism that does nothing but looks like it does something
  is worse than its absence.

## Decision

**Chosen: Option A.** The driver is that the override's failure mode is silent at compile time and
catastrophic at runtime, and the thing it was buying — closing netty advisories ahead of armeria — is
not currently worth buying. Exposure to GHSA-8c42-7qj2-3j46 appears to be nil: the advisory is a
`Vary`-header overwrite in `io.netty.handler.codec.http.cors.CorsHandler`, and evitaDB implements
CORS itself in `io.evitadb.externalApi.http.CorsService`, an armeria `SimpleDecoratingHttpService`.
No evitaDB code path reaches netty's `CorsHandler`.

For Option B to win, a future netty advisory would have to affect a component evitaDB genuinely
exercises, with armeria lagging. At that point the override should be recreated **including
tcnative**, not codec-only — and this record exists so that whoever recreates it knows that.

## Key technical details

- The override lived in the root `pom.xml` only; no module pinned netty independently.
- The trap is that `netty-codec-http` → `netty-handler` is a *transitive* edge, so pinning codecs
  moves `SslHandler` without naming it. Any future partial netty pin must either include
  `netty-tcnative-*` or avoid raising `netty-handler` at all.
- `netty-bom` remains unsuitable as the mechanism: importing it manages every `io.netty:*` artifact
  and was previously observed to flip `netty-handler`'s mediated scope from `provided` to `runtime`
  in `evita_external_api_grpc_shared`, breaking its `module-info.java`.
- Verify any netty change with `mvn dependency:tree -Dincludes=io.netty` and check that the
  `netty-*` artifacts and `netty-tcnative-*` agree with each other, not merely with the property.

## Verification

`mvn clean install -DskipTests` — BUILD SUCCESS. `mvn -pl evita_test/evita_functional_tests test -P
unitAndFunctional -Dgroups="(external_api | grpc | rest | graphql | observability) & !slow"` — 2326
tests, 0 failures, 0 errors, 5 skipped.

The four configurations were run against that same suite, changing only dependency versions:

| armeria | netty | tcnative | Failures | Errors |
|---|---|---|---|---|
| 1.40.0 | 4.2.16.Final | 2.0.78.Final | 0 | 0 |
| 1.41.0 | 4.2.16.Final | 2.0.78.Final | 0 | 0 |
| 1.41.0 | 4.2.17.Final | 2.0.78.Final | 6 | **927** |
| 1.41.0 | 4.2.17.Final | 2.0.81.Final | 0 | 0 |

Rows two and three isolate netty as the sole cause; rows three and four isolate tcnative as the sole
mechanism. The 927 errors are concentrated in the GraphQL, REST and gRPC functional tests, all with
the same root cause: `com.linecorp.armeria.client.UnprocessedRequestException:
io.netty.channel.StacklessClosedChannelException`, raised from
`HttpSessionHandler.tryFailSessionPromise` under `SslHandler.channelInactive`.

## Consequences & open follow-ups

- **GHSA-8c42-7qj2-3j46 stays open** against `io.netty:netty-codec-http` until armeria ships netty
  >= 4.2.17. Dependabot will keep proposing netty bumps; each one needs the tcnative check above
  before it is taken, and a bump that only raises the codecs must not be merged on a green compile.
- **Dependabot's netty PRs (#1424, #1472) were closed undone.** They will reappear on netty 4.2.18,
  which is intended — the reappearing PR is the prompt to re-check whether armeria has caught up.
- **The tcnative version skew is not detectable by the compile gate.** Any future dependency work
  that touches the web stack needs the external-API test groups run, not just `install -DskipTests`.
