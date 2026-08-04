---
title: Turn query telemetry into an actionable profile, and render the formula plan without ever computing it
date: 2026-08-04
updated: 2026-08-04 13:48
status: accepted
kind: feature
issues: [1341]
prs: [1385]
areas: [evita_query/api/query/require, evita_api/api/requestResponse/extraResult, evita_engine/core/query, evita_engine/core/query/algebra, evita_store/evita_store_server/store/query, evita_external_api]
supersedes: []
superseded-by: []
relates: [2026-07-23-query-label-prometheus-metrics]
---

# Turn query telemetry into an actionable profile, and render the formula plan without ever computing it

`queryTelemetry` used to answer one question — how long each phase took. It now answers *why*: each
step carries typed numeric metrics (estimated vs. actual cardinality, estimated vs. actual cost, I/O
counts, whether bodies were prefetched), a derived self-time, and — on request — the **formula plan**
the planner built, including the index-selection alternatives it costed and **rejected**. The
governing constraint throughout was that a query which does not ask for telemetry must pay nothing,
and a query that asks for timings must not pay for the plan.

## Why

A phase timer tells you *where* a slow query spent itself and nothing about *what it was doing*. The
single most common cause of a query that looks wrong is a planner cardinality estimate that is off by
orders of magnitude — and no amount of timing data reveals it. The information that explains such a
plan is the one thing the engine had but never exposed: the alternatives it considered, the costs it
estimated for them, and which one it picked.

The constraint that made this non-obvious is that the plan is not sitting in memory waiting to be
serialised. The planner builds one formula per candidate index and computes **only the winner**. Any
renderer that reaches for a node's result in order to describe it would execute plans the engine had
deliberately decided to skip. Telemetry would stop observing the query and start changing it — and
would do so most dramatically on exactly the pathological queries it exists to diagnose.

### Previous state

The constraint carried no arguments and no state. Phase timings were the entire payload; the REST
descriptor declared six properties for a DTO that had nine; documentation described behaviour the
code did not have. Telemetry arguments were built eagerly even when telemetry was switched off, so
the "costs nothing when disabled" claim was not quite true — the strings describing steps that were
never recorded were still assembled.

## Options considered

The fork was entirely about **item 5, the formula plan**. The rest of the work had no comparable
branch point; the smaller forks that did exist are tabulated after the decision.

### Option A — a non-forcing renderer built on a new `Formula#getMemoizedResult()` (chosen)

Add one primitive to the `Formula` contract: return the memoized result if one exists, and *never*
produce one. `FormulaPlanVisitor` reads it exactly once per node and derives everything from it — a
node with no memoized result reports no `actualCost` and no `resultCount` at all.

- **Pros:** rendering is provably free of side effects on the query being measured; rejected
  alternatives can be described in full (structure, description, estimated cost) without being run;
  the "never ran" state becomes representable rather than being papered over with a zero.
- **Cons:** a new method on a widely-implemented interface; `absent` and `zero` become two different
  answers that every client has to distinguish.

### Option B — reuse `PrettyPrintingFormulaVisitor` (declined)

The engine already had a formula renderer, and it already produced human-readable output.

- **Pros:** no new visitor, no new interface method, immediate.
- **Cons:** `PrettyPrintingFormulaVisitor:114` calls `formula.compute()` unconditionally.
- **Rejected because:** it would execute every rejected alternative. The planner costs each candidate
  index and computes only the winner, so a forcing renderer turns a diagnostic into a multiplier on
  the cost of the very query being diagnosed. This is not a tuning problem — the visitor's contract
  is to render a computed formula, and the plan's whole value is in the formulas that were *not*
  computed.

### Option C — render only the plan that actually ran (declined)

Attach the plan to the root step alone, after execution, where every node is memoized and every
number is real.

- **Pros:** no unmemoized nodes, so no absent-vs-zero distinction; a simpler DTO.
- **Cons:** silent about everything the planner considered.
- **Rejected because:** "what did it do" is the question every engine already answers. "What did it
  consider, and at what estimated cost" is the one that explains a plan which looks wrong, and it is
  only answerable at planning time. Dropping it would have left the feature without its reason to
  exist. Revisit only if rendering alternatives ever shows up as a measurable cost on the planning
  path.

## Decision

**Chosen: Option A.** It is the only option that satisfies the governing constraint — a profile must
observe the query, not perturb it — while still exposing the rejected alternatives that motivate the
feature. The cost is a new interface method and an absent-vs-zero distinction pushed onto clients;
both are documented, and the second is the honest encoding of a state that genuinely exists.

Option B becomes viable only if `PrettyPrintingFormulaVisitor` ever grows a non-computing mode, at
which point the two renderers should merge rather than coexist.

### Other forks resolved

| Decision | Why | Rejected alternative |
|---|---|---|
| GraphQL publishes the profile **flattened** into a pre-order list with `level`/`stepsCount`; REST keeps it nested | In GraphQL the *client* chooses selection depth, so a recursive `steps` field forces a selection set as deep as the deepest query ever profiled and silently truncates anything below. `LevelInfo` already makes this trade for `hierarchy`, and a flat list is what a flame chart consumes | A recursive `steps` field on both APIs — truncates silently, which is worse than not offering the depth |
| Metrics are a **nested object** on all APIs, not flattened into the step | Only the root step carries any; flattening would put eight always-null properties on every other node | Flattening, for a shallower JSON shape |
| **8** metrics, not the 10 the issue listed | `LOOPS` had no writer anywhere in the engine, and `RECORDS_FOUND` duplicated `ACTUAL_CARDINALITY` | Shipping all ten — two would have been permanently absent, teaching clients that absent metrics are normal |
| Metrics **round-trip** through `ResponseConverter` | So an embedded caller and a gRPC caller see the same numbers | Deriving them client-side, as `selfTime` is — it is a pure function of the tree, metrics are not |
| `QueryTelemetryContent` is a single-valued **level** (`TIMINGS`, `PLAN`) following `ConstraintWithDefaults` | A one-constant enum cannot express its own default and publishes as a single-value enum in the generated schemas. As a set, `queryTelemetry(TIMINGS, PLAN)` would be self-contradictory | A vararg content set, which is what `StatisticsType` does — right for genuine flag sets, wrong for levels |
| Item 2's orphaned node fixed by **appending an argument** | The issue's own recommendation — relaxing `QueryTelemetry.finish()`'s one-shot assert — throws on the `NestedContextSorter` path | Relaxing the assert |
| The telemetry level **is persisted** by the Kryo serializer, breaking older traffic recordings | A recording exists to reproduce what happened; a recorded `queryTelemetry(PLAN)` replaying as `queryTelemetry()` reproduces something else, silently and forever. A stale recording failing to read is loud and bounded | Writing nothing (implemented first, then reverted) — kept old recordings readable at the cost of a permanent silent degradation; a `SerialVersionBasedSerializer` compat reader — impossible, constraints carry no `serialVersionUID` and Kryo binds one id per class |

## Key technical details

- **`Formula#getMemoizedResult()`** (`evita_engine/.../algebra/Formula.java`) is the whole safety
  property. Read it as "a result is available for free", **not** "this ran during this query": a
  cached `FlattenedFormula` reports one having computed nothing. That `getCost()` happens to return
  `Long.MAX_VALUE` for an uncomputed formula is a coincidence, not a contract — it is also what an
  estimate returns on arithmetic overflow.
- **What actually makes the renderer non-forcing is call *ordering*, not the formula types.** The
  visitor reads `getCost()` on any node that is memoized, and `AbstractFormula.getCostInternal()`
  computes every inner formula unconditionally. Several types skip children in `computeInternal()` —
  `AndFormula` (sorted-conjunction short-circuit) and `NotFormula` (empty superset) override the cost
  path to match, `SelectionFormula` skips its delegate on the prefetch path and overrides
  consistently, but `DisentangleFormula`'s X\X guard returns empty without computing its inner
  formulas while its cost path falls through to `super.getCostInternal()`, which computes both. So
  "no type skips a child" is **not** the invariant, and must not be relied on.

  The guarantee holds because of where the calls sit. At the execution site
  `QueryPlan.recordQueryMetrics` reads `this.filter.getCost()` for the `ACTUAL_COST` metric *before*
  it renders the plan, so anything the cost path would force has already been forced — by the metric,
  not by the renderer. At the planning site nothing is memoized yet, so the visitor calls `getCost()`
  on no node at all. **Move the plan rendering above the cost metric, or render a memoized tree from
  anywhere else, and the renderer starts executing branches the engine skipped.**
  `FormulaPlanVisitorTest.shouldReportAShortCircuitedBranchAsNeverHavingRun` pins the `AndFormula`
  case; the ordering itself is not pinned by any test.
- **Prefetch produces a third kind of unexecuted node, and it is the one users will misread.**
  `SelectionFormula` answers from prefetched entity bodies when the planner judges that cheaper, and
  then never computes its delegate — so the whole index sub-tree below it reports no `actualCost` and
  no `resultCount` *inside the plan that ran*. That is honest, and it is what the `PREFETCHED` metric
  exists to explain; the two must be read together.
  `QueryTelemetryRootFunctionalTest.shouldReportTheIndexBranchAsUnexecutedWhenFilteringOverPrefetchedBodies`
  pins it, forcing the path with `DebugMode.PREFER_PREFETCHING` because the cost-based selector will
  not prefetch for a test-sized catalog.

  A related trap sits one level down and is **pre-existing**, not introduced here:
  `SelectionFormula.computeInternal()` branches on `isPrefetchExecution()` while its
  `getCostInternal()` branches on `getPrefetchedEntities() != null`. Those are different questions,
  and `verifyConsistentResultsInAllPlans` drives them apart — it runs the index plan with prefetched
  entities still present, so the cost path returns the prefetch constant for work the index branch
  actually did. Any `actualCost` read off a `SelectionFormula` node under
  `VERIFY_ALTERNATIVE_INDEX_RESULTS` is understated for that reason.

- **The plan is a DAG, not a tree.** Memoization is per *instance*, so a sub-formula reachable by two
  paths is computed once. Repeat occurrences are emitted as childless `refTo` pointers; without them
  a reader would count an expensive shared subtree twice. Identity, not structural equality, is what
  "computed once" means — `FormulaPlanVisitor` keys on an `IdentityHashMap`.
- **Two recording sites, deliberately.** Alternatives are rendered at their `popStep` in
  `QueryPlanner` (nothing computed yet — structure and estimate only); the winner at
  `QueryPlan.recordQueryMetrics` (memoized — real costs and result counts). A node with no
  `actualCost` is therefore not necessarily a rejected alternative; a branch of the *winning* plan
  that the computation short-circuited past is legitimately unmemoized too.
- **`QueryTelemetrySerializer` persists the level, and knowingly breaks older traffic recordings
  doing so.** `QuerySerializationKryoConfigurer` has exactly two production users, both traffic
  recording; the remote drivers send EvitaQL as a string and never reach it, so replay is the only
  path affected. Before the level existed the serializer emitted **zero bytes**, and the recording
  format carries no version, magic or length stamp, so a reader that consumes an enum eats into the
  *next* element of a recording written by an earlier build. There is no compatible middle ground to
  take instead: query constraints are registered directly rather than through
  `SerialVersionBasedSerializer`, so there is no `serialVersionUID` to dispatch a backward-compatible
  reader on, and Kryo binds one registration id per class, so the two forms cannot be told apart at
  all. That finding is what makes the break clean rather than avoidable.

  Writing nothing was implemented first and reverted: it kept old recordings readable, but at the
  price of every recorded `queryTelemetry(PLAN)` replaying as `queryTelemetry()` — a debugging
  constraint changing meaning, silently, on the one path whose entire purpose is faithful
  reproduction. A stale recording failing loudly is bounded; a recording that reproduces something
  else is not. A compatibility mechanism for query constraints comparable to the one the data
  structures already have is planned and deliberately out of this line of work's scope; this
  serializer is one of its first customers when it lands.

  **`QueryTelemetryContent` is registered at the end of the enum block, not inside it.** Registration
  ids are assigned positionally by `index++`, so inserting anywhere above `TraversalMode` renumbers
  every enum below it and breaks recordings this change has no business touching.
- **GraphQL `Long` is a custom scalar serialising to a JSON string** (`LongCoercing`), so telemetry
  durations and metrics arrive quoted. This is deliberate — the values exceed JavaScript's safe
  integer range — but it surprises every first-time consumer.

## Verification

- `FormulaPlanVisitorTest` — 6 tests. The defining one,
  `shouldLeaveEveryFormulaUncomputedAfterRenderingThePlan`, asserts `getMemoizedResult()` is still
  null on every node *after* rendering. `shouldReportAShortCircuitedBranchAsNeverHavingRun` pins the
  partially-computed case described above: an `AndFormula` over a cheap empty-yielding branch and an
  expensive constant, where the expensive branch stays unmemoized through rendering.
- `QueryTelemetryRootFunctionalTest` — 10 tests, including
  `shouldDescribeRejectedAlternativesWithoutExecutingThem`, which asserts a real
  `PLANNING_FILTER_ALTERNATIVE` step carries a description but no `actualCost`, and
  `shouldNotBuildFormulaPlanUnlessRequested` for the zero-cost-when-off guarantee.
- Wire-level coverage on both JSON APIs (`shouldReturnQueryTelemetry` in the REST and GraphQL
  catalog query tests), asserted over HTTP rather than on the DTO — a property the schema declares
  but the serializer never emits would pass every unit test and still break every generated client.
- `QuerySerializationTest` gained a `queryTelemetry` block: the round-trip variants cover the bare
  form, the spelled-out default and `PLAN`, and `shouldPreservePlanLevel` pins the property the
  format break was taken for — a replayed `queryTelemetry(PLAN)` still asks for a plan.
- Full reactor `mvn install` exit 0. Full `unitAndFunctional` suite: **20869 tests, 0 failures**. The
  single error is `ExportS3ServiceTest`, which fails in `beforeAll` with
  `Could not find a valid Docker environment` — environmental, and identical on `dev`.
- `QuerySerializationTest` alone: **272 tests, 0 failures**, the run that pins the serializer format
  break. A targeted run suffices for it because the break is asymmetric — old bytes read by a new
  reader — so no test that writes and reads within one build can exercise it. What could have failed
  is a checked-in binary recording fixture; there is none, and no traffic-engine test constructs a
  `queryTelemetry` constraint.

## Consequences & open follow-ups

**Five breaking changes, not the two the issue's compatibility section names.** All were taken
deliberately rather than layering a compatible workaround over a shape that had to change regardless:

1. The REST telemetry response schema gained the three properties it always emitted (item 7).
2. The REST *require* shape: `"queryTelemetry": true` → `"queryTelemetry": "TIMINGS"` or `"PLAN"`.
   A single-argument constraint publishes unwrapped, so the old form is now an HTTP 400.
3. GraphQL: a bare `queryTelemetry` field selection is no longer valid, and the field returns a list.
4. `QueryTelemetry`'s Java constructor signature.
5. **The traffic-recording format.** A recording containing `queryTelemetry` written before this
   change cannot be read after it.

The first four are safe today on the issue's own grounds — nothing consumes those surfaces yet. **The
fifth is not, and is accepted on different grounds**: recordings exist on disk and replay does read
them. It is taken because the alternative is not compatibility but a silent lie — see the
`QueryTelemetrySerializer` note above — and because a general compatibility mechanism for query
constraints is planned separately. A recording that fails to read is diagnosable; one that replays a
different query is not.

Open:

- **`@SourceHash` on `QueryConstraints.queryTelemetry(...)` is stale**, and the new single-argument
  overload has none. `JavaDocSummarizer` regenerates both; it needs `OPENAI_API_KEY` and the
  `documentation` Maven profile. Never hand-edit the hash — that only lies to the drift detector.
- **The `.json.md` documentation result samples still show the old payload.** They are generated
  against the public demo server and cannot show fields that have not been released; they must not be
  hand-edited. They will refresh once a release carrying this work reaches the demo server.
- **Query constraints have no serialization-compatibility mechanism**, unlike the stored data
  structures, which dispatch on a `serialVersionUID` prefix through `SerialVersionBasedSerializer`.
  Every constraint that gains, loses or reorders an argument therefore breaks the traffic-recording
  format the same way this one did, with no way to read the older form. Building the equivalent for
  constraints was declared out of scope here; `QueryTelemetrySerializer` is a ready first customer.
- **The C# driver has not been updated** for the constraint argument. It lives in a separate
  repository, so the user documentation deliberately makes no claim either way for the `c` tab.
- **Asking for the plan changes the profile's own numbers**, because rendering happens inside the
  measured query. This is accepted and documented: re-running the query to get the deeper view is the
  expected workflow, and it is strictly better than charging every telemetry consumer for a plan they
  did not ask for.

## Related work

- [`2026-07-23-query-label-prometheus-metrics`](2026-07-23-query-label-prometheus-metrics.md) — the
  other half of query observability, and the same trade in the opposite direction: labels are cheap
  enough to always export, a formula plan is not.

## Timeline

- **2026-08-04** — implemented in four stages on one branch: lazy arguments and the orphaned
  annotation node (`8e9f269a4`), the REST descriptor correction, derived self-time and the false
  prose (`8e9f269a4`), typed step metrics (`5aa39e9d8`), the flattened GraphQL profile and the
  formula plan (`6b4a535cd`). `dd11ee505` reverted the telemetry level out of the Kryo payload to
  keep older traffic recordings readable; that revert was itself reversed once the silent-degradation
  cost was weighed against a clean format break, so the shipped behaviour is the one described above.
