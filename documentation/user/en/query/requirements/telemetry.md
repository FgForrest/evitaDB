---
title: Telemetry
date: '7.12.2023'
perex: |
  When you operate a complex database system, you often need to know what is happening under the hood of the database engine,
  so you can optimize your queries and so on. Telemetry is a toolset that helps you to understand how your actions
  are planned and executed.
author: 'Bc. Lukáš Hornych'
proofreading: 'done'
preferredLang: 'evitaql'
---

## Query telemetry

<LS to="e,j,r,c">

```evitaql-syntax
queryTelemetry(
    argument:enum(TIMINGS|PLAN)
)
```

<dl>
	<dt>argument:enum(TIMINGS|PLAN)</dt>
	<dd>
		How much detail to profile at, `TIMINGS` being the default and an implicit argument — `queryTelemetry()`
		and `queryTelemetry(TIMINGS)` are the same constraint, and both print as the former.
		`PLAN` additionally returns the formula plan the query engine built — see
		<LS to="j,e,r">[the formula plan](#the-formula-plan)</LS><LS to="c">the formula plan</LS> below. The two are
		levels rather than flags: a profile carries the timings, or the timings *and* the plan.
	</dd>
</dl>

</LS>

The <LS to="j,e,r"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/QueryTelemetry.java</SourceClass> requirement</LS>
<LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/QueryTelemetry.cs</SourceClass> requirement</LS>
<LS to="g">`queryTelemetry` extra result field</LS>
requests the computed query telemetry for the current query. The telemetry contains detailed information about the query
processing time and its decomposition to single operations.

The query telemetry object represents a single executed operation with possibly nested other operations and consists of
the following data:

<dl>
	<dt>operation</dt>
	<dd>
		Phase of the query execution.
		Possible values can be found in the <LS to="j,e,r,g"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/extraResult/QueryTelemetry.java</SourceClass> class</LS><LS to="c"><SourceClass>EvitaDB.Client/Models/ExtraResults/QueryTelemetry.cs</SourceClass> class</LS>.
	</dd>
	<dt>start</dt>
	<dd>
		When this step began, in nanoseconds. This is <strong>not</strong> a wall-clock timestamp and must never be
		rendered as a date.
		<LS to="j,e,c">Embedded, it is a raw monotonic counter reading with no defined epoch — meaningful only
		relative to another reading taken in the same JVM.</LS>
		<LS to="g,r">It is the number of nanoseconds elapsed since the root step of the tree began, so the root
		itself always reports <code>0</code>.</LS>
	</dd>
	<dt>startedAt</dt>
	<dd>
		The wall-clock instant at which the query began. Carried by the <strong>root</strong> step only — it is what
		anchors the whole tree in time, so a profile can be correlated with logs, traces or another query. Every other
		node reports <code>null</code>, and its own wall-clock position is <code>startedAt</code> plus that node's
		<code>start</code> offset.
	</dd>
	<LS to="j,e,c,r">
	<dt>steps</dt>
	<dd>
		Internal steps of this telemetry step (operation decomposition). Same structure as the parent telemetry object.
	</dd>
	</LS>
	<LS to="g">
	<dt>level</dt>
	<dd>
		Depth of this step in the profile, the root always being <code>1</code>. GraphQL returns the profile as a
		<strong>flat list</strong> rather than a nested tree — see the note below — and this is the property that
		carries the structure. The steps arrive in pre-order, so the parent of any step is the closest preceding
		step with a lower <code>level</code>.
	</dd>
	<dt>stepsCount</dt>
	<dd>
		Number of direct sub-steps this step decomposed into, so a leaf is recognizable without looking ahead in
		the list. Legitimately <code>0</code> on the root as well, for a query whose planning short-circuited.
	</dd>
	</LS>
	<dt>arguments</dt>
	<dd>
		Arguments of the processing phase — for example, which index was selected and at what estimated cost.
	</dd>
	<dt>spentTime</dt>
	<dd>
		Duration in nanoseconds, covering this step and everything nested below it.
	</dd>
	<LS to="g,r">
	<dt>selfTime</dt>
	<dd>
		Duration in nanoseconds this step spent on its own work — its <code>spentTime</code> less the time accounted
		for by its direct children. A parent's <code>spentTime</code> is <strong>not</strong> the sum of its
		children's, so this is the number that says how much of a phase is the phase itself rather than the phases
		inside it.
	</dd>
	<dt>formattedSpentTime, formattedSelfTime</dt>
	<dd>
		The same two durations rendered in a human-readable form (e.g. <code>16.6 ms</code>), so a client does not
		have to format them itself.
	</dd>
	</LS>
	<LS to="j,e,g,r">
	<dt>plan</dt>
	<dd>
		Structure of the formula the engine built for this phase — see
		<LS to="j,e,r">[the formula plan](#the-formula-plan)</LS><LS to="g">the formula plan</LS> below. Present
		only when the query asked for it, and then only on the phases that own a formula.
	</dd>
	<dt>metrics</dt>
	<dd>
		Typed numeric measurements the engine computed while answering the query. Where the durations above say
		<em>where</em> the time went, these say <em>why</em> — see the table below. Recorded on the <strong>root</strong>
		step only, so every other node reports <LS to="j,e">no metrics at all</LS><LS to="g,r"><code>null</code></LS>.
	</dd>
	</LS>
</dl>

<LS to="j,e,g,r">

The metrics themselves:

| Metric | Meaning |
|---|---|
| `estimatedCardinality` | How many records the planner **expected** the filter to match |
| `actualCardinality` | How many records the filter **really** matched, before paging |
| `estimatedCost` | Cost the planner estimated for the formula it chose |
| `actualCost` | Cost that formula really incurred once it ran |
| `recordsReturned` | How many records were handed back, i.e. the size of the requested page |
| `ioFetchCount` | How many times the storage was read while assembling the response |
| `ioFetchedSizeBytes` | How many bytes were read from the storage |
| `prefetched` | Whether the planner filtered over prefetched entity bodies instead of consulting indexes — read this before interpreting a plan, see below |

The pair worth looking at first is `estimatedCardinality` against `actualCardinality`. An estimate that is off by
orders of magnitude is *why* the engine chose the index it chose, and it is the usual explanation for a plan that
looks wrong — no amount of timing data reveals it. `estimatedCost` and `actualCost` are the same comparison on the
planner's own unitless scale: comparable between plans of the same query, meaningless in absolute terms.

<Note type="warning">

Every metric is **optional, and a missing one is not a zero**. A metric is recorded where the engine happens to
compute the number, so its absence means "not measured for this phase" — which is deliberately different from a
measured `0`. Several of these are legitimately zero: a query answered entirely from indexes really does perform
`ioFetchCount: 0` storage reads. A client that defaults absent metrics to zero will report a query that fetched
nothing as one that found nothing.

</Note>

</LS>

<LS to="g">

<Note type="info">

**GraphQL returns the profile flattened.** The other APIs nest each step's sub-steps inside it; GraphQL returns a
single list of steps in pre-order, each carrying its `level`. The reason is that in GraphQL the *client* decides how
deep it selects, so a nested `steps` field would force you to write a selection set as deep as the deepest query you
ever expect to profile — and would silently truncate anything deeper. A flat list has no depth limit, and it is
already the shape a flame chart consumes. The `hierarchy` extra result makes the same trade for the same reason.

Reconstruct the tree by walking the list and attaching each step to the closest preceding step with a lower `level`.

Note also that the nanosecond durations arrive as **strings**, not numbers: `Long` is a custom scalar in this API so
that values beyond JavaScript's safe integer range survive intact.

</Note>

</LS>

<LS to="r">

<Note type="warning">

**The require shape changed.** `queryTelemetry` used to be written `"queryTelemetry": true`, the form a constraint
with no arguments takes. Now that it carries an argument it is published as that argument's bare value:

```json
"queryTelemetry": "TIMINGS"   // timings only — the equivalent of the old `true`
"queryTelemetry": "PLAN"      // timings plus the formula plan
```

This is a deliberate breaking change, made while nothing depends on the old shape yet rather than layering a
compatible workaround over a shape that had to change regardless.

</Note>

</LS>

<LS to="j,e,g,r">

## The formula plan

The timings say *where* the query spent itself; the plan says *what it was doing*. Ask for it by parametrizing the
constraint — <LS to="j,e">`queryTelemetry(PLAN)`</LS><LS to="r">`"queryTelemetry": "PLAN"`</LS><LS to="g">selecting
the `plan` field, which is how this API opts in</LS> — and the steps that own a formula additionally carry the
structure of that formula:

- every **index-selection alternative** carries the candidate the planner costed, *including the ones that lost*
- the **root** carries the plan that actually ran

That first point is the one worth the trouble. Every engine will tell you what it did; very few will tell you what it
considered and rejected, and at what estimated cost. That is the information that explains a plan which looks wrong.

Each node of the plan reports:

| Property | Meaning |
|---|---|
| `id` | Identity of the formula **instance**, stable across its occurrences in the plan |
| `refTo` | Set only on a repeat occurrence, pointing back at the `id` that describes it |
| `hash` | Structural hash — what the cache keys on |
| `description` | What the formula is, in human-readable form |
| `estimatedCost` | What the planner expected this part to cost |
| `actualCost` | What it really cost, or **absent** if it never ran |
| `resultCount` | How many records it produced, or **absent** if it never ran |

<Note type="info">

**Why `refTo` exists.** The plan is a directed acyclic graph, not a tree: a formula's result is memoized per
*instance*, so a sub-formula reachable by two paths is computed **once** and every later occurrence of it is free.
Without the back-reference you would see the same expensive subtree twice and reasonably conclude it cost twice as
much. A node with `refTo` set carries no detail and no children — resolve it against the node with that `id`.

</Note>

<Note type="warning">

**An absent `actualCost` is not a zero cost — it means the formula never ran.** The planner costs every candidate
index but executes only the winner, so a rejected alternative legitimately reports no real cost at all, and so does a
branch of the winning plan that was short-circuited past.

There is a third case, and it is the one most often misread: when the planner decides it is cheaper to fetch a small
number of entity bodies and filter over those, the node described `APPLY PREDICATE ON PREFETCHED ENTITIES IF POSSIBLE`
answers the query from the fetched bodies and **never evaluates the index branch beneath it**. That whole sub-tree is
therefore reported with no `actualCost` and no `resultCount`, inside a plan that really did run. The metric that tells
you this is what happened is `prefetched` — check it before concluding that a large part of your plan was skipped for
some other reason.

This is deliberate and is the reason rendering the plan is safe: **the renderer never computes anything.** Were it to
call `compute()` to fill those fields in, asking for a profile would execute the plans the engine had decided to
skip — telemetry would stop observing the query and start changing it.

Note also that asking for the plan **changes the profile's own numbers**, because the rendering happens inside the
query being measured. A run made with the plan is not directly comparable with one made without it; re-running the
query to get the deeper view is the expected workflow.

</Note>

</LS>

<Note type="warning">

The set of phases is **not** guaranteed. A query whose index selection short-circuits, or a dry run, legitimately
returns a bare root step with no children at all — clients must tolerate that rather than assume a fixed tree shape.

Note also that with telemetry enabled the absolute numbers are **not** production latency: instrumenting every phase
costs something, and that cost is included in what you are reading. Use the profile to find where the time goes
relative to the rest of the query, not to quote an absolute figure.

</Note>


To demonstrate the information the query telemetry is providing, we will use the following query that filters and sorts
entities:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Example query to compute query telemetry for complex filtering and ordering](/documentation/user/en/query/requirements/examples/telemetry/queryTelemetry.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Result query telemetry for filtered and ordered entities

</NoteTitle>

The result contains query telemetry and some products (which we omitted here for brevity):

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.QueryTelemetry">[Result query telemetry for filtered and ordered entities](/documentation/user/en/query/requirements/examples/telemetry/queryTelemetryResult.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.queryTelemetry">[Result query telemetry for filtered and ordered entities](/documentation/user/en/query/requirements/examples/telemetry/queryTelemetryResult.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.queryTelemetry">[Result query telemetry for filtered and ordered entities](/documentation/user/en/query/requirements/examples/telemetry/queryTelemetryResult.rest.json.md)</MDInclude>

</LS>

</Note>