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

```evitaql-syntax
queryTelemetry()
```

The <LanguageSpecific to="java,evitaql,rest"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/QueryTelemetry.java</SourceClass> requirement</LanguageSpecific>
<LanguageSpecific to="csharp"><SourceClass>EvitaDB.Client/Queries/Requires/QueryTelemetry.cs</SourceClass> requirement</LanguageSpecific>
<LanguageSpecific to="graphql">`queryTelemetry` extra result field</LanguageSpecific>
requests the computed query telemetry for the current query. The telemetry contains detailed information about the query
processing time and its decomposition to single operations.

The query telemetry object represents a single executed operation with possibly nested other operations and consists of
the following data:

<dl>
	<dt>operation</dt>
	<dd>
		Phase of the query execution.
		Possible values can be found in the <LanguageSpecific to="java,evitaql,rest"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/extraResult/QueryTelemetry.java</SourceClass> class</LanguageSpecific>
		<LanguageSpecific to="csharp"><SourceClass>EvitaDB.Client/Models/ExtraResults/QueryTelemetry.cs</SourceClass> class</LanguageSpecific>.
	</dd>
	<dt>start</dt>
	<dd>
		Date and time of the start of this step in nanoseconds.
	</dd>
	<dt>steps</dt>
	<dd>
		Internal steps of this telemetry step (operation decomposition). Same structure as the parent telemetry object.
	</dd>
	<dt>arguments</dt>
	<dd>
		Arguments of the processing phase.
	</dd>
	<dt>spentTime</dt>
	<dd>
		Duration in nanoseconds.
	</dd>
</dl>

<LanguageSpecific to="graphql">
By default the number values of the telemetry object are returned in raw form. You can change that in the GraphQL by
using argument `format` on the `queryTelemetry` field. This way human-readable values are returned.
</LanguageSpecific>

To demonstrate the information the query telemetry is providing, we will use the following query that filters and sorts
entities:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Example query to compute query telemetry for complex filtering and ordering](/documentation/user/en/query/requirements/examples/telemetry/queryTelemetry.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Result query telemetry for filtered and ordered entities

</NoteTitle>

The result contains query telemetry and some products (which we omitted here for brevity):

<LanguageSpecific to="evitaql,java,csharp">

<MDInclude sourceVariable="extraResults.QueryTelemetry">[Result query telemetry for filtered and ordered entities](/documentation/user/en/query/requirements/examples/telemetry/queryTelemetry.evitaql.json.md)</MDInclude>

</LanguageSpecific>
<LanguageSpecific to="graphql">

<MDInclude sourceVariable="data.queryProduct.extraResults.queryTelemetry">[Result query telemetry for filtered and ordered entities](/documentation/user/en/query/requirements/examples/telemetry/queryTelemetry.graphql.json.md)</MDInclude>

</LanguageSpecific>
<LanguageSpecific to="rest">

<MDInclude sourceVariable="extraResults.queryTelemetry">[Result query telemetry for filtered and ordered entities](/documentation/user/en/query/requirements/examples/telemetry/queryTelemetry.rest.json.md)</MDInclude>

</LanguageSpecific>

</Note>