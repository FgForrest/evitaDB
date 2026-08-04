---
title: Report HTTP/2 RST_STREAM floods instead of enforcing against them, and turn the Rapid-Reset defence off by default
date: 2026-08-04
updated: 2026-08-04 09:35
status: accepted
kind: fix
issues: [1369]
prs: [1383]
areas: [evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/http, evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/event]
supersedes: []
superseded-by: []
relates: [2026-08-03-driver-connection-resilience, 2026-07-16-client-session-cancellation-cascade]
---

# Report HTTP/2 connection-level teardowns; stop enforcing the Rapid-Reset limit by default

evitaDB now watches the HTTP/2 frames of every connection and emits a throttled `WARN` plus a
metric for three otherwise-silent connection-level events: a peer resetting streams en masse, the
server tearing a connection down with an erroneous `GOAWAY`, and a **peer** tearing one down with
an erroneous `GOAWAY`. At the same time Netty's Rapid-Reset defence (CVE-2023-44487), which stock
Armeria enables at 400 resets per minute, is **disabled by default** — the flood is reported, not
punished. Two undocumented system properties turn enforcement back on for deployments with a
different threat model.

## Why

A client with an unbounded CDC re-subscription loop issued ~300 `RegisterSystemChangeCapture`
calls per second and cancelled each one. Armeria's default Rapid-Reset limit tripped roughly every
1.4 s, and each trip sent `GOAWAY(ENHANCE_YOUR_CALM)` and closed the connection. Because a `GOAWAY`
is connection-level, every in-flight request on that connection died with it — including calls
entirely unrelated to the flood. On the application side this surfaced as `TransportException`,
`StatusRuntimeException: CANCELLED` and a 5-second timeout on a plain `getCatalogNames`, all
against a server that was answering other traffic normally.

The server logged **nothing**. The access log kept showing `200`s for the individual gRPC calls,
because at the HTTP layer they were fine. Identifying the cause took most of a day and ultimately
required attaching a JDWP logpoint to `Http2GoAwayHandler` **in the client JVM** — something an
operator of an evitaDB deployment generally cannot do.

Two separate problems hide behind that. The teardown was invisible, and the teardown should not
have happened at all.

### Previous state

Armeria *does* log a `WARN` for this, from
`com.linecorp.armeria.internal.common.AbstractHttp2ConnectionHandler` and
`Http2GoAwayHandler`. evitaDB's own `logback.xml` pins `com.linecorp.armeria` (and `io.netty`) to
`ERROR`, which is what actually produced the silence. That suppression is deliberate — those
loggers are noisy — and it is also why "just un-suppress it" is not the fix: the Armeria line
carries a stack trace, fires once per teardown (43 times a minute at the reporter's cadence), names
no threshold, and would drag every other Armeria `WARN` in with it.

Nothing configured the Rapid-Reset limit either. The effective threshold was whatever Armeria
defaults to (`Flags.defaultServerHttp2MaxResetFramesPerMinute()` = 400 per 60 s), invisible and
untunable.

## Options considered

### Option A — inspect the frames on the wire, report without enforcing (chosen)

A per-connection Netty handler inserted between the TLS handler and the HTTP/2 connection handler.
Inbound it walks the frame stream and counts `RST_STREAM`; outbound it recognizes erroneous
`GOAWAY` frames. Enforcement is handed to Netty at `0` (off) by default.

- **Pros:** sees every reset regardless of stream state or routing; covers gRPC, GraphQL, REST and
  every other port identically; the report carries the peer, the count, the window and the
  consequence; throttled per peer, so it cannot become a flood itself; detaches detection from
  enforcement, which is what makes turning enforcement off safe.
- **Cons:** couples to a pipeline position and to HTTP/2 framing, neither of which Armeria
  guarantees; needs a test against a real server to stay honest.

### Option B — narrow the logback suppression to the two Armeria classes (declined)

- **Pros:** a three-line configuration change, ships instantly, works on existing 2026.2.1
  installations with no upgrade.
- **Cons:** stack trace per teardown at 43 lines/min; no threshold, no count, no throttle; the
  logger names are Armeria-internal; and it only ever fires *after* the connection has been killed,
  so it cannot support turning enforcement off.
- **Rejected because:** it reports the symptom the defence causes, and this record's main decision
  is to stop causing it. It remains the correct **workaround** for anyone on 2026.2.1 today, and is
  documented as such on the issue.

### Option C — count cancellations at the Armeria service layer (declined)

Hook `ServiceRequestContext.log().whenComplete()` and count requests whose cause is a
`ClosedStreamException`, the way `HttpMetricDecorator` already derives `Result.CANCELLED`.

- **Pros:** public Armeria API only; no framing, no pipeline-position coupling; gets the request
  path and the `clientId` header for free.
- **Cons:** the decorator only runs for requests that were routed to a service, and the
  cancellation classification is reliable only on the gRPC branch (`CompletableRpcResponse`).
- **Rejected because:** a Rapid-Reset flood is precisely `HEADERS` immediately followed by
  `RST_STREAM` — the stream dies before dispatch, which is the exact case this misses. It would
  have been blind to the incident that prompted the issue. Revisit if Armeria ever exposes a
  connection-level reset counter.

### Option D — keep enforcement on, only make it visible and configurable (declined)

- **Pros:** zero behavioural change in a patch release; the CVE mitigation stays; the smallest
  possible diff.
- **Cons:** leaves the amplification in place as the shipped default.
- **Rejected because:** the defence's failure mode is worse than what it prevents on evitaDB's
  deployment. See the Decision below.

## Decision

**Chosen: Option A, with enforcement off by default.**

Netty counts *inbound* `RST_STREAM` frames — that is, **cancellations**, not requests. A client
doing 50k req/s with no cancellations never comes near the limit. But a well-behaved client emits
one `RST_STREAM` for every client-side timeout and every closed server-stream, so the defence fires
hardest exactly when the server is already slow: requests time out → the client cancels them →
resets accumulate → the connection is killed → every in-flight request on it dies → the client
reconnects and retries. A slow period becomes a metastable failure. 400 cancellations per minute
(6.7/s) is well within reach of one busy trusted client during an incident.

The threat model the defence exists for is an untrusted internet-facing peer. evitaDB is a database
that sits behind the application layer and talks to a small number of trusted clients. On that
deployment the mitigation costs more than it buys — the reporter's outage was *caused* by the
defence firing, not prevented by it.

Turning it off is only safe because detection no longer depends on it: the flood is still counted
and reported at the same 400-per-window threshold Armeria would have enforced at, so the operator
learns exactly what they learned before, minus the severed connection.

The knobs are **system properties, not configuration keys, and are absent from the user
documentation** — deliberately. They are a last resort for a deployment with an unusual threat
model. Promoting them to `ApiOptions` would advertise a dial whose safe setting is "leave it
alone", and would add two components to a public record in a patch release.

This would flip back if evitaDB were ever positioned as directly internet-facing, or if a
connection-level reset counter appeared in Armeria's public API that made Option C viable without
the dispatch blind spot.

## Key technical details

- `Http2ConnectionMonitor` — one shared instance per server, holding the thresholds and the
  per-peer report throttle; `install(ChannelPipeline)` is handed to Armeria's
  `childChannelPipelineCustomizer` by `ExternalApiServer`, and produces one stateful handler per
  connection.
- **Pipeline position is the load-bearing assumption.** The handler must sit *after* the TLS
  handler (or it inspects ciphertext) and *before* the HTTP/2 connection handler (or it sees
  decoded messages, not frames). **Appending it is wrong** — it lands behind everything Armeria
  installs and sees nothing at all, which is how the first implementation failed. The measured
  layouts are:
  - plaintext, at customizer time: `Flush, ReadSuppressing, TrafficLogging,
    Http2PrefaceOrHttpHandler, HttpServerHandler`
  - TLS, at customizer time: `Flush, ReadSuppressing, HttpsConnectionAcceptHandler, TrafficLogging,
    Http2OrHttpHandler`

  In both, the protocol handler's slot is later taken in place by `Http2ServerConnectionHandler`,
  and the TLS handler takes the accept handler's slot in front of it. Inserting immediately ahead
  of the first handler whose class name starts with `Http2` is therefore the one position that
  works on every port. If no such handler is found the monitor logs once and installs nothing —
  no monitoring beats monitoring the wrong bytes.
- **Inbound walking starts only after the 24-byte HTTP/2 connection preface.** That is what keeps
  HTTP/1.1 traffic, a PROXY protocol header and an h2c upgrade handshake from being misread as
  frames. A connection whose preface never arrives is simply never inspected.
- Every buffer is read with **absolute** getters and forwarded untouched; a relative read would
  consume bytes the codec (or the socket) still needs.
- The walker disables itself for a connection if it ever reads a frame length above 1 MiB —
  Armeria advertises a 16 KiB max frame size, so that only happens after losing frame boundaries.
- The outbound `GOAWAY` check tolerates Netty's buffer layout: Netty writes the header, last stream
  id and error code as one buffer and the debug data as a second, so the declared payload length is
  larger than the buffer. Only the fixed part must be contiguous.
- **Both directions are covered.** Outbound `GOAWAY` recognition is a stateless sniff of the write
  path; inbound recognition rides the frame walker that is already there for `RST_STREAM`. The
  inbound case is the more alarming of the two — the client is stating that *evitaDB* violated the
  protocol, so it may point at a server-side defect rather than at a misbehaving peer — and it was
  the last blind spot: everything Netty rejects at connection level (control-frame floods, empty-
  frame floods, HPACK desync, oversized frames) now surfaces on one side or the other.
- **The bound on error codes differs by direction, on purpose.** Outbound recognition only accepts
  codes 1–13, because it inspects arbitrary write buffers and the bound is what keeps a payload
  from being mistaken for a frame. Inbound recognition accepts any non-zero code, because the
  walker knows the frame boundaries and an unknown extension code there is genuine.
- Metrics: `io_evitadb_external_api_http2_rst_flood_total` and
  `io_evitadb_external_api_http2_go_away_total` (labelled by `errorCode` and `direction`). The peer
  address is on the JFR event but **not** a metric label — unbounded Prometheus cardinality.
- **Monitoring can never take traffic down, by construction.** An exception escaping `channelRead` becomes a Netty
  `exceptionCaught` that very likely closes the connection — and worse, the buffer would never be forwarded, stalling
  the read and leaking it. Both directions are therefore wrapped: on any `Throwable` the monitor disables *itself*
  for that connection (separately per direction), logs once, and the buffer is forwarded **outside** the guarded
  block so it reaches the codec regardless. `install()` is wrapped for the same reason — it runs while a connection
  is being accepted, so letting anything escape would reject the connection outright. This is a deliberate exception
  to the project's "never silently skip an unexpected state" rule: the rule protects correctness of the engine, and
  here the correct behaviour of an observability side-channel *is* to get out of the way. The price is paid as a
  loud warning rather than as silence.
- **The report says how many occurrences the throttle swallowed.** A peer that is torn down every
  1.4 s would otherwise produce a once-a-minute line indistinguishable from a single incident.

## Verification

`Http2ConnectionMonitorTest` — 22 tests, all green:

- frame recognition against `EmbeddedChannel`: threshold behaviour, frames split one byte at a
  time, a DATA payload containing bytes that look exactly like a `RST_STREAM` frame (must not be
  counted), HTTP/1.1 traffic (must never be walked), buffers forwarded with every byte intact;
- outbound `GOAWAY` recognition for `ENHANCE_YOUR_CALM(11)`, `PROTOCOL_ERROR(1)` and
  `COMPRESSION_ERROR(9)`, and non-recognition of a graceful `NO_ERROR` shutdown;
- inbound `GOAWAY` recognition: an erroneous peer teardown is reported, the graceful `NO_ERROR`
  shutdown every client sends is not, an unknown extension code is reported, and a frame delivered
  one byte at a time is both read correctly and leaves the walker aligned on the frames that
  follow its debug data;
- `fromSystemProperties()`: the shipped default is enforcement `0` / window `60`, a valid pair is
  honoured, a malformed or negative value falls back rather than enabling enforcement, and a `0`
  window is clamped so it can never silently disable an enforcement that was asked for. This is
  the configuration every user gets and nothing else covers it;
- **against a real Armeria server on both a plaintext and a TLS port**: three client-side timeouts
  on one multiplexed connection are counted as three `RST_STREAM` frames. This is the test that
  caught the wrong pipeline position — the first implementation appended the handler and observed
  zero inbound bytes;
- **against a real server with enforcement switched on**: the flood is reported on the very frame
  that trips Netty's limit, *and* the resulting `GOAWAY(ENHANCE_YOUR_CALM)` is recognized on the
  outbound path. The inbound handler necessarily runs before the codec that throws, so the flood
  report always wins the per-peer throttle and the (redundant) `GOAWAY` line is correctly
  suppressed;
- **resilience**: a monitor whose every report throws must not disturb the connection — the channel
  stays active, no exception reaches the pipeline, and every inbound and outbound buffer is still
  forwarded with its bytes intact, both on the failing frame and on the ones after it. A pipeline
  with no HTTP/2 handler at all is left unmonitored rather than rejected.

`EvitaServerTest` (14 tests) boots the real server through `ExternalApiServer`, exercising the
installation path in production wiring. 67 tests green across `io.evitadb.externalApi.http` and
`io.evitadb.server`.

`Http2ConnectionMonitorBenchmark` (`evita_test/evita_performance_tests`) is the standing guard on the
two performance claims the design rests on, each an A/B against the same pipeline without the
handler. Full writeup and tables:
[`Http2ConnectionMonitorBenchmark`](../performance/individual/Http2ConnectionMonitorBenchmark/README.md).

- **The inbound walk is O(frames), not O(bytes).** Multiplying the bytes walked per operation by
  48.7× (256 B → 16 KiB payloads, frame count held at 16) moved the cost from 112.4 to 114.9 ns/op —
  **+2.2 %**, inside the error bar. **~7 ns per inbound frame**, so ~14 ns on a unary gRPC request
  against an end-to-end cost measured in hundreds of microseconds. Response size does not matter,
  which is what makes the handler safe on a database that returns large result sets.
- **Neither direction allocates.** `gc.alloc.rate.norm` is identical on the outbound path
  (152.000 B/op both arms) and within 0.002 B/op inbound — and that residual is not the handler but
  fixed background allocation amortised over fewer operations in the slower arm (0.000 at equal
  speed, 0.001 at 3.2× slower, 0.002 at 8.6× slower). Nothing on either path constructs an object.
- The cancellation path costs **~24 ns per `RST_STREAM`**; the extra ~17 ns over a data frame is the
  single `System.nanoTime()` in `recordResetFrame`'s window check. Left unoptimised on purpose —
  Netty's own limiter pays the same clock read, and a reset means a cancelled request that already
  cost orders of magnitude more. The outbound `GOAWAY` recognition costs **~1–3 ns per write**.

Measured on JDK 21.0.11 / JMH 1.37, 8 × 2 s warm-up, 6 × 2 s measurement, 2 forks, idle 24-core
machine. The A/B arms run sequentially, so a contended run inflates them unequally — the writeup
records the baseline values that identify one.

Netty's default (400 per 60 s) was read out of `DefaultFlagsProvider` rather than assumed, and
matches the reporter's observation: 400 resets at ~300/s is ~1.33 s, against the ~1.4 s cadence
they measured.

## Consequences & open follow-ups

- **The defence is off by default.** A pathological client can now churn streams indefinitely
  without being disconnected. That is the intended trade — it will be reported once a minute per
  peer and shows up in `io_evitadb_external_api_http2_rst_flood_total`. Anyone exposing evitaDB to
  untrusted peers must set `evitadb.http2.maxRstFramesPerWindow`.
- **The two system properties are intentionally absent from `documentation/user/`.** A future
  contributor documenting them, or promoting them to `ApiOptions`, is reversing a decision, not
  filling a gap.
- **The pipeline-position coupling will break on an Armeria refactor.** It fails safe (one warning,
  no monitoring), and `Http2ConnectionMonitorTest`'s real-server tests fail loudly. They are the
  regression gate for any Armeria upgrade.
- **`clientId` is not in the report.** The issue asked for it "if it is known for that connection";
  it is not — the evitaDB client header is per-request and nothing on the server records it per
  channel. The remote address is reported instead, and the access log (`%a`) correlates by it.
- **Server-sent stream-level `RST_STREAM` is deliberately not reported.** It is per-request, not
  connection-level, and is already visible through the access log and `RequestEvent` status codes.
- **The h2c *upgrade* path is untested.** Prior-knowledge h2c and ALPN h2 are both covered; an
  HTTP/1.1 `Upgrade: h2c` connection may place the codec elsewhere, in which case the monitor
  silently observes nothing. evitaDB's own clients do not use it.
- **`logback.xml` was not changed.** With enforcement off by default, Armeria's own
  `ENHANCE_YOUR_CALM` line can no longer fire at all, so the suppression is not hiding the
  headline case. What it still hides is Armeria's view of the *other* connection errors — which
  this monitor now reports itself, from the outbound side, with a peer and a throttle. Narrowing
  the suppression would therefore only duplicate lines. It becomes worth revisiting if the monitor
  is ever removed or its pipeline hook stops working.

## Related work

- `2026-08-03-driver-connection-resilience` — the driver-side half of the same incident (issues
  #1367/#1368); this record covers what the server should have said while that was happening.
- `2026-07-16-client-session-cancellation-cascade` — the other place where a cancelled client
  stream has server-side consequences; shares the `RST_STREAM`-as-cancellation semantics relied on
  here.

## Timeline

- **2026-08-03** — issue #1369 reported, alongside the driver-side #1367/#1368
- **2026-08-04** — implemented; first implementation appended the handler and saw nothing, the
  real-server test caught it and the pipeline layout was measured rather than inferred
- **2026-08-04** — scope widened from `ENHANCE_YOUR_CALM` to every erroneous `GOAWAY` in both
  directions, enforcement flipped off by default, and the handler hardened so that no failure of
  its own can disturb the connection it watches
- **2026-08-04** — the two performance claims measured rather than asserted, and the numbers
  written down so nobody has to re-measure them
