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
queryTelemetry()
```

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
	<dt>steps</dt>
	<dd>
		Internal steps of this telemetry step (operation decomposition). Same structure as the parent telemetry object.
	</dd>
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
</dl>

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