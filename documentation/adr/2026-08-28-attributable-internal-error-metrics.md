---
title: Count each evitaDB error once, at the hierarchy root, and record where it was created
date: 2026-08-28
updated: 2026-08-29 05:59
status: accepted
kind: fix
issues: [1461]
prs: []
areas: [evita_common/src/main/java/io/evitadb/exception, evita_external_api/evita_external_api_observability]
supersedes: []
superseded-by: []
relates: [2026-07-23-query-label-prometheus-metrics, 2026-07-24-traffic-discard-reason-attribution]
---

# Count each evitaDB error once, at the hierarchy root, and record where it was created

evitaDB's error metrics are produced by a Byte Buddy agent that instruments exception constructors. Three defects
made them untrustworthy at once: the counter double-counted, the error code that was supposed to identify an error's
origin was a constant, and nothing anywhere recorded where a counted error actually came from. The agent now
instruments only the two roots of the error hierarchy (counting each instance exactly once), `getErrorCode()`
resolves the real construction site lazily, and newly-seen origins are logged with a stack trace under a new
`errorOriginLogging` setting. The metric names and label sets are unchanged.

## Why

A production server (2026.2.5, FG test cluster) showed `io_evitadb_errors_total{error_type="GenericEvitaInternalError"}`
growing by 134 in 48 hours, 81 of them inside a single hour, while in that same window there were **zero** non-OK
gRPC responses out of 2.38M calls, zero GraphQL `responseStatus="ERROR"`, zero error spans in Tempo, and no stack
trace in the container log.

The counter is incremented from constructor advice, so it counts an exception being **built**, not thrown. An
exception that is constructed and swallowed - or thrown at a caller that has already disconnected - is
indistinguishable from one that failed a request. The metric carries a single label, the class simple name, so it
cannot say where the error came from.

Nor could the code say. `Assert.isPremiseValid(boolean, String)` throws `GenericEvitaInternalError` and has **824**
call sites; together with ~403 explicit constructions the static candidate set is over 1200 places, all collapsing
onto one unlabelled counter. Every strong swallow candidate found by a code sweep - the transaction rollback path,
the seven `TrafficRecordingEngine` catches, the HTTP/2 connection monitor, the session proxy - logs the throwable,
so the observation of "no stack trace in the log" excluded all of them. A separate sweep for the never-thrown shape
found its only real instances in the gRPC **client driver**, which does not run in the server process.

The constraint that made this non-obvious: the advice sits on *every* `EvitaInternalError` constructor, so nothing
on that path may allocate or do IO unconditionally, and `ErrorMonitor` is injected into the **bootstrap
classloader**, so it may reference only `java.*`.

### Previous state

`getProperStackLine()` in both hierarchy roots skipped leading stack frames only while a frame's class equalled
`this.getClass()` - the **runtime** class. Its own frame belongs to the *declaring* class, so the loop exited on the
first iteration and returned that frame. The resulting code was constant per base class: identical for every
`EvitaInternalError` ever created, and for 121 of the 122 concrete `EvitaInvalidUsageException` subtypes. Only a
direct instance of `EvitaInvalidUsageException` produced a usable code. Both roots paid a full
`Thread.currentThread().getStackTrace()` walk plus two MD5 hashes in four of five constructors to compute it, on top
of the backtrace `fillInStackTrace()` had already captured.

The agent matched `isSubTypeOf(root).and(not(isAbstract()))`, i.e. every concrete subtype. Advice on a constructor
fires once per constructor *entered*, so a concrete class extending another concrete class was counted once per
level, and a constructor delegating to a sibling via `this(...)` was counted twice.

## Options considered

### Option A — instrument only the hierarchy roots, and flatten their constructors (chosen)

Match `EvitaInternalError` (abstract) and `EvitaInvalidUsageException` themselves rather than their subtypes. Every
error, at any depth, passes through exactly one root constructor, and `@Advice.This` still yields the runtime class
so the metric label is unaffected. Root-only matching alone is not sufficient - it collapses the inheritance
multiplier but not the delegation one - so the four delegating constructors in the two roots were changed to call
`super(...)` directly.

- **Pros:** counts each instance exactly once at any depth; shrinks the instrumented surface from ~170 classes to 2;
  the label is still the concrete runtime type.
- **Cons:** the `NotMonitored` opt-out can no longer be evaluated while instrumenting, because it sits on the
  concrete subtype rather than on the woven root; a future `this(...)` in either root silently reintroduces
  double counting.

### Option B — keep per-subtype matching and deduplicate per instance (declined)

Keep the existing matchers and suppress the extra firings, e.g. by comparing `@Advice.Origin("#t")` against
`thiz.getClass().getName()` so only the most-derived constructor reports.

- **Pros:** no change to the exception classes at all.
- **Cons:** fixes only the inheritance multiplier.
- **Rejected because:** measured, not reasoned - a delegating and a delegated constructor share the same declaring
  type, so the origin comparison cannot tell them apart and `EvitaInvalidUsageException` still fired twice. There is
  no Byte Buddy matcher for "constructor that does not delegate", so the delegation multiplier can only be removed
  in the source. Once the source is being changed anyway, Option A is strictly simpler.

### Option C — add a `source` label to the existing counter (declined)

Expose the construction site as a Prometheus label instead of logging it.

- **Pros:** queryable and aggregatable; no log volume.
- **Cons:** unbounded cardinality (1200+ possible sites, and an error code can arrive from the wire).
- **Rejected because:** adding a label changes series identity, so `increase()` across the transition is wrong and
  every exact-match alert breaks. The requirement was explicitly that these counters stay readable by existing
  dashboards. Revisit as a *separate* metric if per-site aggregation is ever wanted; that keeps
  `io_evitadb_errors_total` byte-identical.

### Option D — emit a JFR event carrying the stack trace (declined)

`MetricHandler` already consumes a `RecordingStream`, so a JFR event would be architecturally native.

- **Pros:** no log volume; structured; zero cost when no recording runs.
- **Cons:** only pays off while a recording is active, which is exactly not the case during an unattended incident.
- **Rejected because:** the incident that motivated this had no recording running and would have produced nothing.
  Revisit as an addition if origin logging proves too noisy in `ALL` mode.

### Option E — fix the error code eagerly, keeping construction-time resolution (declined)

Patch the skip loop in place and keep computing the code in the constructor.

- **Pros:** smallest diff.
- **Cons:** keeps a full stack walk and two MD5 hashes on every client-error construction.
- **Rejected because:** patching the loop is not enough on its own. `Assert` frames sit at position zero for 824
  call sites, so a merely-corrected loop produces a *second* constant at `Assert.java:90`; the skip rule has to
  exist regardless. Given the code must change anyway, moving resolution to first read removes a cost that was
  being paid for a value almost nobody reads.

## Decision

**Chosen: Option A**, with lazy site resolution (against Option E) and log delivery (against C and D).

The three changes reinforce each other. Correcting `getErrorCode()` is what gives the origin logger a dedup key
that is already cached on the exception, so recognising a repeated origin costs a map lookup rather than a stack
walk. Making that resolution lazy is what keeps the whole feature affordable: with `errorOriginLogging: NONE` the
engine is now *cheaper* than before this work, because the eager walk is gone.

Option C would win if operators needed to aggregate origins across a fleet rather than diagnose one server, and if
a new metric name were acceptable. Option D would win if recordings were routinely running.

## Key technical details

- `ErrorCodeResolver` (`evita_common/.../exception/`) derives the code from `Throwable#getStackTrace()`. The JVM
  omits the throwable hierarchy's own constructor frames, so frame zero is already the creating statement - the old
  skip loop is gone rather than patched. **One skip rule remains:** frames declared by `io.evitadb.utils.Assert`,
  which throws on its callers' behalf. Helpers that merely *build* an exception and let the caller throw it are
  deliberately not skipped; a named factory is a meaningful origin.
- **`errorCode` is non-final and the race on it is benign.** Every thread derives the same value from the same
  immutable stack trace, and `String` is safely publishable through a race because all its fields are final. This is
  the `String#hashCode` pattern, and it is why the field is not `volatile`.
- **No constructor of either hierarchy root may delegate via `this(...)`.** This is the invariant the whole
  counting fix rests on, it fails silently (nothing breaks; the number is merely wrong again), and it is guarded by
  `EvitaErrorMonitoringTest`. Both roots carry a comment saying so.
- **`ErrorMonitor` is injected into the bootstrap classloader** and may name only `java.*` types in its signatures
  and bodies. It gave up Lombok for hand-written setters because a generated member carries `lombok.Generated`.
  The failure mode is a `NoClassDefFoundError` visible only in an agent-attached server, so
  `ErrorMonitorBootstrapVisibilityTest` parses the compiled constant pool instead.
- `NotMonitored` retention is now **load-bearing**: the marker is read reflectively at runtime (cached per class in
  a `ClassValue`, checked *before* any counter moves), so `CLASS` retention would compile and silently stop opting
  anything out.
- `ErrorOriginLogger` runs inside an exception constructor on a partially constructed object. Its whole body is
  under `catch (Throwable)` and it calls nothing but `getErrorCode()` and `getStackTrace()`. An escape there would
  surface from `new SomeException(...)` at a call site that cannot handle it.
- `VirtualMachineError` is deliberately left matched per concrete subtype and excluded from origin logging: the
  JVM throws pre-allocated `OutOfMemoryError` instances without running a constructor, so `jvm_errors_total`
  *under*-counts by nature, and allocating a log message inside an OOM constructor turns a survivable failure fatal.

## Verification

Measured with Byte Buddy 1.17.8 on JDK 17 against `evita_common/target/classes`. Advice firings per single
constructed instance:

| construction | before | root-only matching | root-only **+ flattened roots** |
| --- | --- | --- | --- |
| `GenericEvitaInternalError(msg)` | 1 | 2 | **1** |
| `EvitaInvalidUsageException(msg)` | 2 | 2 | **1** |
| subclass of `EvitaInvalidUsageException` | 3 | 2 | **1** |

The middle column is the measurement that killed Option B. Error codes before the fix:

| construction | errorCode |
| --- | --- |
| `new GenericEvitaInternalError("A")` | `62663481…:af7e0329…:84` |
| `new GenericEvitaInternalError("B")` — a different site | `62663481…:af7e0329…:84` |
| `Assert.isPremiseValid(false, …)` | `62663481…:af7e0329…:84` |
| subclass of `EvitaInvalidUsageException` | `73a20adb…:af7e0329…:92` |
| `new EvitaInvalidUsageException("exact")` | `07f1a5e8…:fad58de7…:25` ← the only correct one |

Line 84 was `EvitaInternalError.getProperStackLine` itself.

Tests: `EvitaErrorMonitoringTest` (one increment per instance across both roots, both delegating constructor
shapes, an `Assert`-raised error and two levels of subclassing, driving the *production* type matchers so it cannot
drift), `EvitaInternalErrorTest` and `EvitaInvalidUsageExceptionTest` (origin names the creating class and method;
distinct sites differ; an assertion failure is attributed to its caller; a wire-supplied code is preserved),
`ErrorOriginLoggerTest` (mode gating, dedup, the tracked-origin cap, and that no failure propagates),
`ErrorMonitorBootstrapVisibilityTest` (constant-pool purity), `ObservabilityOptionsTest` (default and explicit
`errorOriginLogging`).

## Consequences & open follow-ups

- **Existing series step.** `io_evitadb_errors_total{error_type="GenericEvitaInternalError"}` is unchanged at one
  increment per instance, but the 18 nested internal error types drop 2-3x and `io_evitadb_client_errors_total`
  drops 2-6x across its 121 subtypes. Those series were wrong before; anything reading them still steps at deploy.
- **Client-visible error codes change value.** The `hash:hash:line` shape is unchanged, so the gRPC status message
  and the GraphQL/REST error payloads are structurally unaffected, but the values differ. They were constants that
  could not locate anything, so nothing could meaningfully depend on them.
- **The Java driver does not currently recover the code from a gRPC status, and has not since Dec 2024.** The server
  sets the status description to `errorCode + ": " + publicMessage`
  (`GlobalExceptionHandlerInterceptor`), but `EvitaClient#transformStatusRuntimeException` prepends the status name
  before matching (`statusCode.name() + ": " + description`), so `ERROR_MESSAGE_PATTERN` - anchored on
  `(\w+:\w+:\w+): (.*)` - cannot match the resulting `"INTERNAL: <code>: <message>"`. The driver therefore falls
  through to the no-code branch and `createExceptionWithErrorCode` is effectively dead on that path. This is
  **pre-existing and untouched by this work** - it predates issue #1461 - but it is worth knowing before anyone
  relies on codes reaching a Java client. GraphQL and REST are unaffected: both expose `errorCode` as a structured
  JSON field rather than a regex-parsed composite string. Needs its own ticket, because fixing it changes the
  message text clients see.
- `io_evitadb_probe_health_problem{problem_type="EVITA_DB_INTERNAL_ERRORS"}` still flips to 1 whenever the counter
  moves between probes, including for a swallowed exception. It does not affect liveness or readiness
  (`ObservabilityProbesDetector.checkEvitaErrors` uses the `String` constructor, so it never enters the `EnumSet`),
  and it was left alone deliberately - with origins now logged, a flap is diagnosable rather than mysterious.
- **The 81 errors from the original incident are still unattributed.** The leading hypothesis is delivery to a
  caller that has already gone: the workload was hourly 20-minute k6 runs at 100 VU, so ~100 clients disconnect at
  once at each run boundary, which reproduces all five observed negatives and the burstiness. This ships the
  instrument that would settle it in one run; it does not settle it. A second, pre-existing lever is worth knowing:
  `TrafficRecordingEngine` already records `ex.getClass().getName() + ": " + message` into
  `TrafficRecording#finishedWithError()` for every query, fetch, enrichment and session close, so if traffic
  recording happens to be on, `getTrafficHistory` filtered client-side on that field names the failing query
  directly. `TrafficRecordingCaptureRequest` has no server-side error filter, and this sees only errors that
  propagate out of a recorded operation.
- **The `Assert` skip list is a maintenance surface.** It has exactly one entry today. If another high-fan-out
  throw helper appears, its frames will become the reported origin for all of its call sites - visibly, in the log,
  rather than silently.
- **`ErrorMonitoringAgent` deliberately keeps Byte Buddy's default discovery and description strategies, and
  `EvitaErrorMonitoringTest` deliberately does not.** The test attaches the same matchers to a running surefire
  fork, and on the defaults that wedged the fork completely: eight hours of no progress, 105 threads blocked on jar
  and classloader monitors, 56 of them inside the transformer. It now pins
  `RedefinitionStrategy.DiscoveryStrategy.Explicit` (the two roots only) and `DescriptionStrategy.POOL_ONLY`, and
  passes the same discovery strategy to `reset` - the two-argument overload runs its own pass on the default
  strategy, which relocates the hang to `@AfterAll` rather than removing it.

  The divergence is intentional and the production side needs no equivalent change, which is worth stating because
  the obvious inference from the test is the wrong one. `DescriptionStrategy.HYBRID` does not load classes to
  describe types: its bytecode describes from the type pool when `classBeingRedefined == null` and from the
  already-in-hand `Class` otherwise, and the pool reads class files through `getResourceAsStream`, never
  `loadClass`. The `loadClass` frames in the thread dump are the JVM resolving Byte Buddy's *own* classes on first
  use. All of that is agent warm-up, and it is paid once - at `premain`, single-threaded, before the application
  starts. Only attaching mid-flight, into a fork whose threads are already contending on the same jars, turns it
  into a convoy. Adopting `POOL_ONLY` in production would trade a real behaviour (`HYBRID` describes retransformed
  types from the loaded `Class`) for no benefit on that path.

  Rejected because: the hazard is a property of mid-flight attachment under concurrency, not of the strategies.
  Revisit only if the agent ever gains a runtime-attach path, where premain's single-threaded warm-up guarantee
  disappears.
- **The functional-test fork does not run the Byte Buddy the reactor pins.** The root pom sets
  `byteBuddy.version` to `1.17.8` and `evita_engine` resolves exactly that, but the version in the test fork's
  thread dump is `1.18.3` (matched by line number - `TypePool$LazyFacade.doDescribe` sits at 9994 there and 9436 in
  1.17.8), most plausibly dragged in transitively by `proxycian_bytebuddy`. Pre-existing and unrelated to this
  work, but it means the suite exercises instrumentation code that production does not ship. Needs its own ticket.

## Related work

- `2026-07-23-query-label-prometheus-metrics` — the other place where Prometheus label cardinality dictated a
  design; the same reasoning is why a `source` label was refused here.
- `2026-07-24-traffic-discard-reason-attribution` — the traffic recorder's `NotMonitored` control-flow signal is
  the one existing user of the opt-out whose semantics this change had to preserve exactly.

## Timeline

- **2026-08-27** — production counter growth observed on the FG test cluster
- **2026-08-28** — investigation, three defects identified and measured; implemented, issue #1461 filed
