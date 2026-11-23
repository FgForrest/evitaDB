---
title: Behavioral require containers
date: '11.5.2026'
perex: |
  Special behavioural require constraint containers are used to define a require constraint scope. 
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'evitaql'
---

## In Scope

```evitaql-syntax
inScope(
    argument:enum(LIVE|ARCHIVED)
    requireConstraint:any+
)
```

<dl>
    <dt>argument:enum(LIVE|ARCHIVED)</dt>
    <dd>
        mandatory enum argument naming one of the scopes requested by the query
    </dd>
    <dt>requireConstraint:any+</dt>
    <dd>
        one or more require constraints whose extra results are checked against the indexes of the named scope only
    </dd>
</dl>

The `inScope` (<LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/RequireInScope.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/Queries/Require/RequireInScope.cs</SourceClass></LS>) require container plans
the extra results it wraps, such as facet summaries, histograms, and hierarchy statistics, in the named scope: they are
checked against the indexes of that scope only.

An extra result requires its indexes in every scope it is planned in. A query that searches both scopes can wrap an
extra result in `inScope` naming the scope that has them.

The main `entityFetch` fetches its requested content for every returned entity, regardless of scope, even when
it appears inside a require-level `inScope`. Its content constraints are checked in the scopes the content is fetched
in - every scope the query searches, unless a reference filter selects the scopes of the referenced entities.

<Note type="info">

The scope named by `inScope` must be one the query requests. A query that searches only `ARCHIVED` rejects
`inScope(LIVE, ...)`.

An `inScope` container cannot be nested in another `inScope` container within the same requirement context. With
a different scope the inner requirements would apply only to entities that are in both scopes at once, which none are,
and with the same scope the inner container would be redundant - so the query is rejected with an error in both cases.
Place the containers side by side instead. The rule does not reach into a requirement that describes the content of
other entities - such as `entityFetch`, `referenceContent`, `facetSummary` or `hierarchyOfReference`.

</Note>

<LS to="g">

<Note type="info">

The GraphQL API doesn't express most of the require constraints directly but through the output fields, so the `inScope`
container is available for the extra results only. The references and the parents of entities need no
such container: they're fetched for the entities of every queried scope, because fetching them doesn't need any index
of the scope the entity lives in (see [reference content](fetching.md#reference-content) and
[hierarchy content](fetching.md#hierarchy-content)). Only the constraints nested in the reference or parents fields
that do read an index are refused: an `entityHaving` or a `stopAt` node filter by an attribute filterable in one
scope only, and an ordering of references that aren't indexed in every queried scope. Wrap such a filter in
the `inScope` container of the nested `filterBy` argument (or name the scope in the `entityHaving` itself), and such
an ordering in the `inScope` container of the nested `orderBy` argument.

</Note>

</LS>

For example, in our demo dataset we haven't created facet or hierarchy indexes for archived entities. The price 
information is also not indexed. So if you tried to calculate facet summary or histogram information for entities in
the archive scope, you'd get an error from the query engine. If you are querying entities in multiple scopes, you should
use the `inScope` container and limit these calculations to only those scopes where the indexes are prepared:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Distinguishing requires in different scopes](/documentation/user/en/query/requirements/examples/behavioral/archived-entities-requirements.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Price histogram requested for entities in the LIVE scope
</NoteTitle>

As you can see, the result contains calculations for the data that the engine can calculate.

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.PriceHistogram">[##### The result of requested price histogram only for entities in live scope
](/documentation/user/en/query/requirements/examples/behavioral/archived-entities-requirements.evitaql.string.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.inScope.priceHistogram">[##### The result of requested price histogram only for entities in live scope
](/documentation/user/en/query/requirements/examples/behavioral/archived-entities-requirements.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.priceHistogram">[##### The result of requested price histogram only for entities in live scope
](/documentation/user/en/query/requirements/examples/behavioral/archived-entities-requirements.rest.json.md)</MDInclude>

</LS>

</Note>

Place the main `entityFetch` beside the `inScope` container. When querying products in both scopes, this require
section fetches the code of every returned product and checks the price histogram against the price indexes of the
live scope only:

```evitaql
require(
  entityFetch(attributeContent("code")),
  inScope(LIVE, priceHistogram(5))
)
```

<Note type="info">

Similar `inScope` containers are available for [filter constraints](../filtering/behavioral.md#in-scope)
and [ordering constraints](../ordering/behavioral.md#in-scope) with the same purpose and meaning.

</Note>

<Note type="info">

Some require constraints allow results from multiple references to be combined. For example, [reference summary](reference.md#reference-summary),
[attribute histogram](histogram.md#attribute-histogram) and [price histogram](histogram.md#price-histogram) can be 
computed for both live and archived entities if the appropriate indices are available.

</Note>
