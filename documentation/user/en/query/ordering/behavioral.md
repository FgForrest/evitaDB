---
title: Behavioral ordering containers
date: '29.11.2024'
perex: |
  Special behavioural order constraint containers are used to define a order constraint scope. 
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'evitaql'
---

## In Scope

```evitaql-syntax
scope(
    argument:enum(LIVE|ARCHIVED)
    orderConstraint:any+
)
```

<dl>
    <dt>argument:enum(LIVE|ARCHIVED)</dt>
    <dd>
        mandatory enum argument representing the scope to which the order constraints in the second and subsequent
        arguments are applied
    </dd>
    <dt>orderConstraint:any+</dt>
    <dd>
        one or more mandatory order conditions, combined by a logical link, used to order entities only in 
        a specific scope
    </dd>
</dl>

The `inScope` (<LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/ordering/OrderInScope.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/Queries/Ordering/OrderInScope.cs</SourceClass></LS>) order container is used
to restrict order conditions so that they only apply to a specific scope.

The evitaDB query engine is strict about indexes and does not allow you to order or sort on data (attributes, references,
etc.) for which no index has been prepared in advance (it tries to avoid situations where a full scan would degrade query
performance). Scopes, on the other hand, allows us to get rid of unnecessary indexes when we know we will not need them
(archived data is not expected to be queried as extensively as live data) and free up some resources for more important
tasks.

The [scope](../filtering/behavioral.md#scope) order constraint allows us to query entities in both scopes at once,
which would be impossible if we couldn't tell which order constraint to apply to which scope. The `inScope` container 
is designed to handle this situation.

<Note type="info">

It's obvious that the `inScope` container is not necessary if we are only querying entities in one scope. However, if
you do use it in this case, it must match the scope of the query. If you use the `inScope` container with the `LIVE`
scope, but the query is executed in the `ARCHIVED` scope, the engine will return an error.

</Note>

For example, in our demo dataset we have only a few attributes indexed in the archive - namely `url` and `code` and 
a few others. No attribute has a sort index in the archive scope. So if we were to select a few entities from both 
scopes and try to sort them by `name`, we'd get an error. However, if we use the `inScope` container, we can sort 
the entities by `name` in the live scope and let the archived entities sort by the order in the input query.

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Disginguishing orders in different scopes](/documentation/user/en/query/ordering/examples/behavioral/archived-entities-ordering.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of sorted entities when filtering from multiple scopes
</NoteTitle>

The result contains two live entities ordered by the `code` attribute. The other entities come from the `ARCHIVED` scope, 
which contains no sort index, and are ordered by their primary keys.

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[##### The result of sorted entities when filtering from multiple scopes
](/documentation/user/en/query/ordering/examples/behavioral/archived-entities-ordering.evitaql.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="data.queryProduct.recordPage">[##### The result of sorted entities when filtering from multiple scopes
](/documentation/user/en/query/ordering/examples/behavioral/archived-entities-ordering.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[##### The result of sorted entities when filtering from multiple scopes
](/documentation/user/en/query/ordering/examples/behavioral/archived-entities-ordering.rest.json.md)</MDInclude>

</LS>

</Note>

<Note type="info">

Similar `inScope` containers are available for [filter constraints](../filtering/behavioral.md#in-scope)
and [requirements constraints](../requirements/behavioral.md#in-scope) with the same purpose and meaning.

</Note>