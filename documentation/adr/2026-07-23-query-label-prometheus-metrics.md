---
title: Export operator-named query labels as Prometheus dimensions via a runtime-configured bag, not fixed compile-time fields
date: 2026-07-23
updated: 2026-07-31 19:28
status: accepted
kind: feature
issues: []
prs: [1312]
areas: [evita_api, evita_engine, evita_external_api/evita_external_api_observability]
supersedes: []
superseded-by: []
relates: []
---

# Export operator-named query labels as Prometheus dimensions

The generic `label()` query head constraint was previously visible only in traces, traffic
recordings and JFR events — never in Prometheus. PR #1312 bridges it: operators list arbitrary label
names in a new `ObservabilityOptions.exportedQueryLabels` option, and each name they opt in becomes a
Prometheus dimension on query metrics, its value looked up out of `FinishedEvent`'s existing `labels`
bag at metric-registration time. Nothing is exported by default.

No GitHub issue is referenced by either commit or the PR description; this originated directly from
a debugging session (2026-07-20) rather than a filed ticket.

## Why

A live cluster showed `io_evitadb_query_finished_total` carrying real traffic but only
`catalogName`/`entityType`/`prefetched` dimensions — eshop's `job_name`/`rest_method` query labels
never appeared. Root cause, established during that session: `FinishedEvent.labels` captured the
attached `Label[]` as a joined string, but the field carried no `@ExportMetricLabel` annotation, so
the Prometheus bridge never saw it — unlike GraphQL's purpose-built, protocol-specific
`ExecutedEvent.operationName`, which uses a completely different mechanism.

The constraint that shaped every option: `MetricHandler` fixes a metric's Prometheus dimension *key
set* once, by reflecting over `@ExportMetricLabel`-annotated fields/getters at metric-registration
time; only *values* vary per event instance afterwards. Any design that tried to export "whatever
label names happen to be in the array this time" as separate per-sample dimensions was mechanically
impossible without deeper surgery on `MetricHandler` itself — which is exactly the surgery that ended
up shipping (see Decision).

### Previous state

`label()` constraint values flowed into traces, traffic recordings and JFR events but nowhere else.
`FinishedEvent.labels` (`evita_engine/.../core/metric/event/query/FinishedEvent.java`) was a plain,
unannotated `String` built by joining the query's `Label[]` with `Collectors.joining(",")` — visible
in a raw JFR recording, invisible to Prometheus. No configuration option existed to surface any label
as a dimension.

## Options considered

The originating plan (2026-07-20) weighed
three designs; the one that shipped is the one the plan itself rejected.

### Option A — fixed compile-time slots for `job_name`/`rest_method` (planned, not shipped)

Add exactly two new `@ExportMetricLabel` fields to `FinishedEvent`, mirroring
`ExecutedEvent.operationName`. They are always-present dimensions; a new `exportedQueryLabels`
allow-list config controls only whether the *value* is populated or forced to `"N/A"`.

- **Pros:** satisfies the fixed-key-set constraint by construction, with zero risk of cardinality
  blow-up — only two dimension names can ever exist; minimal blast radius on `MetricHandler`.
- **Cons:** hardcodes evitaDB's knowledge of two eshop-specific label names; a third named
  integration needs a code change.

### Option B — annotate the existing `labels` blob as one dimension (rejected in the plan)

Mechanically trivial: just annotate the pre-existing joined-string field.

- **Rejected because:** the string is a serialization of an arbitrary, order-and-membership-varying
  combination of names — including the auto-injected, genuinely unbounded ones (`trace-id`, `uri`,
  `ip-address`, `client-id`) — so every distinct combination becomes its own Prometheus time series.

### Option C — fully dynamic, config-driven key set (rejected in the plan; what shipped)

Let `MetricHandler` build its label exporters from runtime configuration instead of purely from
compile-time annotations, so any operator-named label can become a dimension without an evitaDB code
change.

- **Pros (as realized):** no per-name code change ever again; any operator-defined label name can be
  opted in purely through configuration.
- **Cons:** a second, config-dependent label-collection path inside `MetricHandler`
  (`@ExportConfigurableLabels`, `buildConfigurableLabelExporters`); a new name-sanitization layer
  (`PrometheusLabelNames`) that didn't exist before; and, per PR #1312's own two-round review, two new
  failure modes (metric re-registration with a stale shape; a configured name colliding with a
  built-in dimension) that had to be hardened before merge.
- **Rejected in the plan because:** "a real architectural change to a mechanism every other metric in
  the system depends on... disproportionate for two known label names; revisit only if a third or
  fourth protocol-level origin label is anticipated."

## Decision

**Chosen: Option C — the option the plan explicitly rejected, generalized further than either plan
option to any operator-named label, not just `job_name`/`rest_method`.**

No commit message, the PR description, or either round of PR #1312's review thread (both read in
full) records *why* Option A was abandoned for Option C. The reversal's rationale was not preserved —
this is stated here rather than reconstructed, per the project's rule that an unexplained reversal
must be recorded as such, not filled in with a plausible-sounding reason.

What is verifiable: the shipped design still satisfies the mechanical constraint from *Why* — the key
set is fixed once, at `MetricTask` construction (server startup), by resolving
`ObservabilityOptions.exportedQueryLabels` through reflection over `@ExportConfigurableLabels` fields
— it is just resolved from runtime configuration instead of from compile-time annotations, exactly
the trade the plan weighed under Option C and declined to take.

## Key technical details

- `evita_api/src/main/java/io/evitadb/api/observability/annotation/ExportConfigurableLabels.java` —
  marks a `String` field as a bag of arbitrary client-supplied labels; contributes **no** dimension by
  itself. The set of dimensions it contributes is decided entirely at runtime.
- `evita_engine/.../core/metric/event/query/FinishedEvent.java` — the pre-existing `labels` field is
  now `@ExportConfigurableLabels`-annotated; its construction was also rewritten from a
  `Stream`/`Collectors.joining` pipeline to a pre-sized `StringBuilder` loop, since this runs on the
  query hot path (called out favorably in review).
- `evita_external_api/.../observability/metric/PrometheusLabelNames.java` — single source of truth
  for sanitizing an arbitrary label name into a legal Prometheus dimension
  (`[a-zA-Z_][a-zA-Z0-9_]*`) and for collision detection; shared by config-time validation and by
  `MetricHandler`'s registration path so the two can't drift.
- `evita_external_api/.../observability/metric/MetricHandler.java` —
  `getExportedQueryLabelSet()` (~line 517) resolves configuration once per `MetricTask` construction;
  `buildConfigurableLabelExporters` (~line 257) sorts configured names for a deterministic dimension
  order across restarts, then each event's value is resolved via
  `QueryLabelBag.extractValue(bagValue, name)`.
- `ObservabilityOptions.exportedQueryLabels` — polarity deliberately inverted from the sibling
  `allowedEvents`: `null`/empty means nothing is exported (the safe default), because query labels are
  arbitrary client data. This safety property survived the design reversal even though the mechanism
  around it did not.
- `FORBIDDEN_QUERY_LABELS` = `trace-id`, `client-id`, `ip-address`, `uri` (reused from
  `TrafficRecordingEngine`) can never be exported. This also closes the plan's own open question about
  `client-id`'s cardinality — not by measuring it, but by prohibiting it outright alongside the other
  three known-unbounded labels.
- **No legal-name / typo validation exists.** `ObservabilityOptions`'s constructor only rejects a
  `null` list item, a `FORBIDDEN_QUERY_LABELS` member, or a dimension-name collision — unlike the
  plan's explicit Phase 1 requirement ("anything else is a config error, fail fast at startup...
  don't silently ignore typos"). Verified in `MetricHandler.buildConfigurableLabelExporters` /
  `QueryLabelBag.extractValue`: a name absent from a given event's bag simply resolves to `null` →
  the metric's `NOT_APPLICABLE` ("N/A") convention, with no distinction between "this query didn't
  carry that label" and "this configured name is never carried by any query" (e.g. a typo). See
  Consequences.
- Comma/`=` in exported label values are **not** escaped — a deliberate, reviewed trade-off (PR
  #1312 review thread, novoj, 2026-07-23): reusing the existing human-readable bag avoids
  per-character scanning of every label on the query hot path for a feature that defaults to off;
  documented instead as a "no comma/`=` in exported values" operator expectation in `observe.md`.
- Metric re-registration hardening (review round 1 finding → fixed in commit `5f4c43c5e`):
  `REGISTERED_METRICS` now stores each metric's dimension-name shape alongside it and rejects a
  same-name re-registration under a different shape, since the label set is no longer guaranteed
  constant across `registerHandlers()` calls within one JVM.
- One known async gap, left as-is by design: a configured label colliding with a metric's *built-in*
  dimension (e.g. `entityType`) is detected only inside the background `MetricTask` registration
  lambda, not synchronously at config load — `registerHandlers()` simply times out after one minute
  and logs an error instead of aborting startup. Raised in both review rounds; left open by novoj
  ("a real semantics decision... happy to add [a synchronous hard-abort] if preferred") rather than
  changed.
- Generated `documentation/user/en/operate/reference/metrics.md` was deliberately **not**
  regenerated — per the PR description, config-driven dimensions don't exist at doc-generation time.
  This is intentional, not a missed regeneration step.

## Verification

Not re-run as part of this conversion. Verified by presence and by the merged PR's own review record:

- Unit tests present: `PrometheusLabelNamesTest`, `QueryLabelBagTest`, `ObservabilityOptionsTest`
  (`evita_test/evita_functional_tests/.../externalApi/observability/...`), `FinishedEventTest`
  (`.../core/metric/event/query/`).
- PR #1312 went through two full review rounds (`claude[bot]` + `Copilot`); round 1 found 2 MAJOR + 2
  MINOR issues (metric re-registration shape drift; async-only built-in-dimension collision
  detection; a null-item NPE; a `CollectionUtils` presizing nit), all addressed or explicitly
  documented-as-accepted in `5f4c43c5e`; round 2 approved with no blockers or majors remaining and
  reported "All observability unit tests green (21)".
- Both review rounds explicitly flag that the actual `MetricHandler`/`MetricTask` wiring — a real
  collision on a live event class, and end-to-end `N/A` rendering — is **not** covered by an
  integration-level test, only by the extracted pure-function unit tests. This is an acknowledged gap
  in the merged PR, not something this record is asserting as new.

## Consequences & open follow-ups

- **The plan's "fail fast on typo" safety property does not exist in the shipped mechanism.** A
  misconfigured `exportedQueryLabels` entry is accepted, gets its own Prometheus dimension, and — since
  no query will ever carry a label by that exact misspelled name — renders `N/A` on every sample,
  indefinitely, with no error anywhere. Not caught by either review round, not covered by a test.
- Built-in-dimension collisions fail asynchronously (a 1-minute timeout plus a logged error) rather
  than aborting startup; `observe.md`/`configure.md` were corrected during review to describe this
  actual behavior rather than the behavior being changed to match an earlier draft of the docs. Left
  open by novoj as a "happy to change if preferred" item — not decided either way.
- The coupling to eshop's `job_name`/`rest_method` convention is now looser than the plan assumed:
  evitaDB reserves no label names at all, so those two strings are pure eshop-side convention that
  evitaDB's code has no knowledge of.
- Two eshop-side gaps the plan explicitly scoped out — `EvitaIncrementalIndexJob` never attaching a
  `Query`-borne label (it calls raw `EvitaSessionContract` methods that take no `Query`), and
  `EshopWebSystemIdRequestMappingHandlerAdapter` never pushing `OriginContext.REST` — live in the
  separate `eshop` repository. This repository cannot verify their current state; they are recorded
  here as still out of scope per the plan's owner decision, not as resolved.
- No comma/`=` escaping for exported label values (kept deliberately, see Key technical details): an
  exported value containing either character will mis-parse. Documented as an operator expectation,
  not enforced in code — a conscious trade-off, not an oversight.

## Timeline

- **2026-07-20** — debugging session identifies the root cause (`FinishedEvent.labels` unannotated),
  investigates the mechanism, and drafts the plan choosing fixed compile-time slots for
  `job_name`/`rest_method` while explicitly rejecting the dynamic, config-driven design implemented
  below
- **2026-07-23** — PR #1312 ships a fully dynamic, config-driven mechanism instead (`6e9b822be`);
  two-round review finds and resolves 2 MAJOR + 2 MINOR issues (`5f4c43c5e`); merged into `dev`
- **2026-07-31** — planning document retired, replaced by this record
