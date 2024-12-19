---
title: Behavioral require containers
date: '29.11.2024'
perex: |
  Special behavioural require constraint containers are used to define a require constraint scope. 
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'evitaql'
---

## In Scope

```evitaql-syntax
scope(
    argument:enum(LIVE|ARCHIVED)
    requireConstraint:any+
)
```

<dl>
    <dt>argument:enum(LIVE|ARCHIVED)</dt>
    <dd>
        mandatory enum argument representing the scope to which the require constraints in the second and subsequent
        arguments are applied
    </dd>
    <dt>requireConstraint:any+</dt>
    <dd>
        one or more mandatory require conditions, combined by a logical link, used to require entities only in 
        a specific scope
    </dd>
</dl>

The `inScope` (<LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/RequireInScope.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/Queries/Require/RequireInScope.cs</SourceClass></LS>) require container is used
to restrict require conditions so that they only apply to a specific scope.

The evitaDB query engine is strict about indexes and does not allow you to require or sort on data (attributes, references,
etc.) for which no index has been prepared in advance (it tries to avoid situations where a full scan would degrade query
performance). Scopes, on the other hand, allows us to get rid of unnecessary indexes when we know we will not need them
(archived data is not expected to be queried as extensively as live data) and free up some resources for more important
tasks.

The [scope](../filtering/behavioral.md#scope) require constraint allows us to query entities in both scopes at once,
which would be impossible if we couldn't tell which require constraint to apply to which scope. The `inScope` container
is designed to handle this situation.

<Note type="info">

It's obvious that the `inScope` container is not necessary if we are only querying entities in one scope. However, if
you do use it in this case, it must match the scope of the query. If you use the `inScope` container with the `LIVE`
scope, but the query is executed in the `ARCHIVED` scope, the engine will return an error.

</Note>

For example, in our demo dataset we haven't created facet or hierarchy indexes for archived entities. The price 
information is also not indexed. So if you tried to calculate facet summary or histogram information for entities in
the archive scope, you'd get an error from the query engine. If you are querying entities in multiple scopes, you should
use the `inScope` container and limit these calculations to only those scopes where the indexes are prepared:

<LS to="e,j,c,r">

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Disginguishing requires in different scopes](/documentation/user/en/query/requirements/examples/behavioral/archived-entities-requirements.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of requested facet summary and price histogram only for entities in live scope
</NoteTitle>

As you can see, the result contains calculations for the data that the engine can calculate.

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.PriceHistogram">[##### The result of requested price histogram only for entities in live scope
](/documentation/user/en/query/requirements/examples/behavioral/archived-entities-requirements.evitaql.string.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.priceHistogram">[##### The result of requested price histogram only for entities in live scope
](/documentation/user/en/query/requirements/examples/behavioral/archived-entities-requirements.rest.json.md)</MDInclude>

</LS>

</Note>

</LS>
<LS to="g">

<Note type="warn">

Using `inScope` constraint container is not supported by the GraphQL API yet. You can check the status of the implementation
in the issue [#752](https://github.com/FgForrest/evitaDB/issues/752). Otherwise, you can check out other languages/APIs.

</Note>

</LS>

<Note type="info">

Similar `inScope` containers are available for [filter constraints](../filtering/behavioral.md#in-scope)
and [ordering constraints](../ordering/behavioral.md#in-scope) with the same purpose and meaning.

</Note>

<Note type="info">

Some require constraints allow results from multiple facets to be combined. For example, [facet summary](facet.md#facet-summary),
[attribute histogram](histogram.md#attribute-histogram) and [price histogram](histogram.md#price-histogram) can be 
computed for both live and archived entities if the appropriate indices are available.

</Note>