# Implementation plan — query labels (`job_name`/`rest_method`) → Prometheus metrics

Audience: the implementing session (any model). This plan is deliberately **coarse**: it fixes the
diagnosis, the chosen mechanism, the phase order, and the safety invariant; local micro-decisions
(exact config key name, exact getter naming) are the implementer's to make within the constraints
stated here. File:line anchors marked "verified" were read directly from source during this
investigation on 2026-07-20 (evitaDB-dev current checkout; eshop repo `/www/p_prj/edee/eshop`,
branch `dev`, live in a running k3d dev cluster, catalog `demo`). Anchors marked "reported" came
from a research pass and were not independently re-read — re-verify before relying on them for
anything beyond background context, since none of them sit on the implementation surface below.

Originating conversation: debugging session investigating why eshop's `job_name`/`rest_method`
query labels never appear in evitaDB's Prometheus output, triggered by a live cluster where
`io_evitadb_query_finished_total` was observed carrying real traffic but only `catalogName`/
`entityType`/`prefetched` dimensions.

## Ground truth about the existing code (verified — do not re-derive)

### The label pipeline splits into two unrelated mechanisms

evitaDB has **two independent ways a value becomes a Prometheus label**, and this investigation's
core confusion (GraphQL's `operationName` clearly works — why doesn't a generic query label?) is
resolved by understanding they don't share machinery:

1. **Protocol-specific, purpose-built fields** — e.g. GraphQL's `ExecutedEvent`
   (`evita_external_api/evita_external_api_graphql/src/main/java/io/evitadb/externalApi/graphql/metric/event/request/ExecutedEvent.java`,
   verified full read). `operationName` (line 85), `graphQLOperationType` (line 57), `catalogName`
   (line 66), `responseStatus` (line 92) are all `@ExportMetricLabel`-annotated fields, populated by
   dedicated `provideXxx(...)` methods called from GraphQL-engine-level instrumentation
   (`RequestMetricInstrumentation.java:100`: `requestExecutedEvent.provideOperationName(GraphQLOperationNameResolver.resolve(operationDefinition))`
   — verified via grep). Source is the GraphQL request's own parsed AST — has nothing to do with
   evitaDB's generic query-label constraint.

2. **The generic `label()` query head constraint** — `io.evitadb.api.query.QueryConstraints.label(String, T)`
   / `io.evitadb.api.query.head.Label` (reported, not re-read — but its shape is confirmed
   indirectly: `QueryLabelEnhancer.java` in eshop imports and calls exactly this API). By its own
   Javadoc (reported), this constraint is documented as feeding only OpenTelemetry traces and
   traffic recordings — **Prometheus is never mentioned**. `EvitaRequest.getLabels()` (reported,
   `evita_api/.../EvitaRequest.java:583-594`) extracts the attached `Label[]` from the query head.

### Where the generic mechanism dead-ends

`FinishedEvent` (`evita_engine/src/main/java/io/evitadb/core/metric/event/query/FinishedEvent.java`,
verified full read):

- Constructor (lines 129-143) receives `Label[] labels` and joins them into a single string field:
  `labels = Arrays.stream(labels).map(l -> l.getLabelName() + "=" + l.getLabelValue()).collect(joining(","))`.
- That `labels` field (lines 60-62) has **no `@ExportMetricLabel`** — unlike sibling fields
  `entityType` (line 57-58, annotated) and `prefetched` (line 78, annotated). This is the entire
  root cause: the value is captured, visible in a raw JFR recording, but the Prometheus bridge never
  sees it.
- **Live-confirmed**: `curl http://localhost:5555/observability/metrics | grep query_finished` in
  the running dev cluster showed real traffic (`io_evitadb_query_finished_total{catalogName="demo",entityType="Product",prefetched="no"} 358.0`
  etc.) with no label-derived dimension anywhere.

### How the Prometheus bridge actually works (verified — this constrains every design option below)

`MetricHandler` (`evita_external_api/evita_external_api_observability/src/main/java/io/evitadb/externalApi/observability/metric/MetricHandler.java`,
verified — read lines 1-140 and 280-500):

- For each allowed JFR event **class** (not instance), `MetricTask`'s constructor (around line
  447+) reflects **once**, at server startup / metric-task initialization, over that class's
  `@ExportMetricLabel`-annotated fields and getters (`ReflectionLookup.getFields` +
  `findAllGettersHavingAnnotationDeeply`, lines ~471-489) to build a fixed `labelNames: String[]`
  array and a matching `labelValueExporters: Function<RecordedEvent,String>[]` array.
- `labelNames` is passed straight into the Prometheus client's builder —
  `Counter.builder().name(...).labelNames(metric.labels()).register()` (`buildAndRegisterMetric`,
  lines ~313-350) — which **fixes the label key set for that metric family at registration time**.
  Per-instance, only `labelValueExporters` run (`recordedEvent -> ...`), filling in *values* for the
  already-fixed keys.
- Absent/null label values already render as the literal string `"N/A"`
  (`NOT_APPLICABLE = "N/A"`, line 122, used via `.orElse(NOT_APPLICABLE)` around lines 289-299) —
  **reuse this convention**, don't invent a new empty-string convention.
- **Conclusion, load-bearing for the whole plan**: Prometheus (and this specific bridge) has no
  concept of a per-sample-variable label key set. A metric's dimensions must be a fixed, small,
  code-declared set decided at class-authoring time. This rules out any design that tries to export
  "whatever label names happen to be in the array this time" as separate dynamic keys — mechanically
  impossible without deeper surgery on `MetricHandler` itself (rejected below as unnecessary).

### `ObservabilityOptions` — existing config shape (verified full read)

`evita_external_api/evita_external_api_observability/src/main/java/io/evitadb/externalApi/observability/configuration/ObservabilityOptions.java`:
immutable, Lombok `@Getter`, Jackson `@JsonCreator`/`@JsonProperty` constructor, extends
`AbstractApiOptions`. Already has exactly one config list of this shape to mirror:
`@Nullable List<String> allowedEvents` (line 57) — `null` means "all known events" (per
`MetricHandler.getAllowedEventSet()`, `Objects.requireNonNullElseGet(allowedEventsFromConfig, () -> knownEvents...)`).
**Note the polarity**: `allowedEvents == null` → permissive (everything). For the new option we add,
polarity must be inverted for safety — see Design below.

### Auto-injected labels that share the same `Label[]` — confirmed unbounded, must never get a dimension slot

- `LabelAppender` (`evita_external_api/evita_external_api_grpc/server/.../EvitaSessionService.java:2396-2417`,
  verified read) merges an HTTP-header-derived `Label[]` directly into the query's `HeadConstraint`
  — same pipeline as caller-supplied `label()` calls, indistinguishable downstream.
- `TrafficRecordingEngine` (`evita_engine/.../core/traffic/TrafficRecordingEngine.java:93-96`,
  verified grep) defines the label names actually used: `trace-id`, `client-id`, `ip-address`
  (`LABEL_IP_ADDRESS`), `uri` (`LABEL_URI`), constructed at lines 685-690 as real `Label` instances
  from `tracingContext.getClientId()` / `getClientIpAddress()` / etc.
- `trace-id` is a new value **every request, by design** (distributed-tracing correlation ID).
  `uri`/`ip-address` are effectively unbounded across real traffic. These are the literal "bad
  label" examples in `ExportMetricLabel`'s own Javadoc (`evita_api/.../annotation/ExportMetricLabel.java:46-50`,
  verified full read): *"Bad labels include user IDs, session IDs, timestamps, or arbitrary
  strings."* `client-id`'s cardinality was **not** resolved this session — see Open Questions.
- This guidance exists **only** in that Javadoc — developer-facing, read by whoever authors a new
  JFR event inside evitaDB. It does **not** appear anywhere in `documentation/user/` (verified via
  repo-wide grep for "cardinality" — every hit there is the unrelated schema reference-cardinality
  concept, `Cardinality.ZERO_OR_ONE` etc.). Nothing tells an external caller of `label()` that its
  values need to stay bounded.

### eshop side (client) — accepted as-is, not part of this plan

Reported, not re-verified beyond `QueryLabelEnhancer.java` (verified full read) and
`EvitaIncrementalIndexJob.java` (verified via grep — zero references to `Query`/`AbstractEvitaService`/
`QueryLabelEnhancer`):

- `QueryLabelEnhancer` (`lib_eshop_evita/src/main/java/com/fg/eshop/evita/service/util/QueryLabelEnhancer.java`)
  defines `JOB_NAME_LABEL_NAME = "job_name"`, `REST_METHOD_LABEL_NAME = "rest_method"`, reads a
  per-thread `OriginContext` stack, and is wired into `AbstractEvitaService`'s query-building
  methods — covers `EdeeShopFeedJob` and most REST controllers correctly.
- **`EvitaIncrementalIndexJob` bypasses this entirely** — its evitaDB calls are raw
  `EvitaSessionContract` methods (`getEntity`, `upsertEntity`, `deleteEntity`, `queryCatalog`/
  `updateCatalog` lambdas) that take no `Query` object, so there is structurally no attachment
  point. **Owner decision (2026-07-20): accepted as a known implementation shortcoming, not a bug,
  out of scope for this plan.**
- A second, minor gap (`EshopWebSystemIdRequestMappingHandlerAdapter` never pushing
  `OriginContext.REST`) was noted but has no current impact and is likewise out of scope.

## Owner decisions (this session, 2026-07-20)

1. Update user-facing documentation with an explicit cardinality-safety note for query labels.
2. Propagate query labels to Prometheus metrics via a **configurable allow-list in
   `ObservabilityOptions`** — not a hardcoded pair of fields, not an unconditional blanket export.
3. Naturally high-cardinality labels (`trace-id`, `uri`, `ip-address`, and `client-id` pending
   verification) must never be exportable, regardless of configuration.
4. eshop-side `EvitaIncrementalIndexJob` gap: explicitly out of scope, accepted as-is.

## Design

**Chosen mechanism: fixed dimension slots, config-gated value population.** Add exactly two new
`@ExportMetricLabel` fields to `FinishedEvent` — `jobName` and `restMethod` — mirroring the
`ExecutedEvent.operationName` pattern exactly. They are **always** present as Prometheus dimensions
(satisfies the "fixed key set" mechanical constraint from `MetricHandler`, no changes needed to
`MetricHandler` itself). What varies by configuration is not *whether the key exists* but *whether
its real value is populated* — if the corresponding name isn't in the new allow-list, the field is
populated with `"N/A"` (existing convention) regardless of what the query actually carried.

This sidesteps the `trace-id`/`uri`/`ip-address` risk **by construction**, not by validation: those
names never get a field at all in this design, so there is nothing to accidentally allow-list. A
deny-list was considered and rejected as unnecessary complexity — the exportable set is a small,
code-fixed enum of two names, not an open string space.

**Rejected alternative — export the `labels` blob field as one dimension**: mechanically trivial
(just annotate the existing field) but reintroduces exactly the danger flagged during this
investigation: the string is a serialization of an arbitrary, order-and-membership-varying
combination of names (including the auto-injected unbounded ones), so every distinct combination is
a new time series. Rejected.

**Rejected alternative — fully dynamic, config-driven key set** (i.e. Prometheus dimensions that
literally match whatever list an operator configures, with no code change to add a new supported
name): would require `MetricHandler`'s reflection loop to build label exporters from runtime config
instead of compile-time annotations — a real architectural change to a mechanism every other metric
in the system depends on. Rejected as disproportionate for two known label names; revisit only if a
third or fourth protocol-level origin label is anticipated.

**Config polarity — deliberately inverted from `allowedEvents`**: unset/empty must mean "export
nothing" (safe default), not "export everything" like `allowedEvents`' `null`. Flag this explicitly
in the new field's Javadoc so it isn't "fixed" later to match the sibling option by mistake.

## Coarse implementation plan

### Phase 1 — `ObservabilityOptions` config

- Add `@Nullable List<String> exportedQueryLabels` (name is the implementer's call), same
  Jackson/Lombok shape as `allowedEvents`. Default (no config / empty list) = nothing exported.
  Legal values: `"job_name"`, `"rest_method"` — anything else is a config error, fail fast at
  startup with a clear message (don't silently ignore typos).
- Wire into `evita-configuration.yaml` under `api.endpoints.observability`, alongside the existing
  `allowedEvents` key, as documented config surface.

### Phase 2 — `FinishedEvent` changes

- Add `jobName`/`restMethod` fields, `@ExportMetricLabel`, populated in the constructor (or a
  `provideXxx` method, matching `ExecutedEvent`'s style) by scanning the existing `Label[] labels`
  parameter for entries named `job_name`/`rest_method` — same array already used to build the
  `labels` blob string; don't remove that field, this is additive.
- Gate population against `ObservabilityOptions.exportedQueryLabels`: if the config doesn't list the
  name, populate `"N/A"` instead of the real value, even if the query carried it. `FinishedEvent` (or
  its constructor caller, `QueryPlanningContext.java:238-242` per prior investigation — not
  independently reverified this session) will need access to the resolved config; check how other
  config-dependent event behavior is threaded through today rather than inventing a new path.
- Do not touch the existing `labels` string field's behavior — traces and traffic recording must
  keep seeing everything they see today.

### Phase 3 — Documentation

- `documentation/user/en/query/header/label.md` (+ `cs/` translation): add a cardinality-safety
  section — what `label()` values are safe (bounded, enum-like, route/name-like) vs unsafe
  (identifiers, timestamps, free text), and that only two specific label names
  (`job_name`/`rest_method`) can ever reach Prometheus, and only when explicitly configured.
- `documentation/user/en/operate/observe.md` (+ `cs/`): document the new
  `exportedQueryLabels`/equivalent config key, with a worked example.
- Check whether `documentation/user/en/operate/reference/metrics.md` is generated or hand-maintained
  before touching it — if generated, verify the generator picks up the new fields correctly, and
  regenerate rather than hand-editing.

### Phase 4 — Tests

- `ObservabilityOptions` config test: unknown label name in `exportedQueryLabels` fails config
  loading with a clear message.
- Regression test: default config (nothing configured) — confirm `jobName`/`restMethod` render as
  `"N/A"` even when the query genuinely carried those labels, and that the pre-existing `labels`
  blob field is unaffected. Protects deployments upgrading with no config changes from silently
  gaining new time series.
- Positive test: with `exportedQueryLabels: [job_name, rest_method]` configured, a query carrying
  `label("job_name", "x")` produces a scraped sample with `job_name="x"`, and a query carrying
  neither label produces `job_name="N/A",rest_method="N/A"`.

### Explicitly out of scope (owner decision)

- eshop's `EvitaIncrementalIndexJob` label-attachment gap.
- eshop's `EshopWebSystemIdRequestMappingHandlerAdapter` origin-context gap.

## Open questions for the implementer

- `client-id`'s actual cardinality was not resolved this session (source: `tracingContext.getClientId()`
  — not read). Resolve before considering it for any future third exportable name; it is **not**
  part of this plan's scope (only `job_name`/`rest_method`).
- Exact config key name/YAML path — follow whatever naming convention the `ObservabilityOptions`
  maintainer prefers; `exportedQueryLabels` above is illustrative, not prescriptive.
- How `FinishedEvent`'s constructor should reach `ObservabilityOptions` at construction time — not
  investigated this session; look at how `QueryPlanningContext`/`Evita` already thread
  server-level config into per-query event construction, if at all, before inventing a new path.
