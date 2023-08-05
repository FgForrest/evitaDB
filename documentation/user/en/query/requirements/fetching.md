---
title: Fetching
perex: |
  Fetch request constraints help control the amount of data returned in the query response. This technique is used to 
  reduce the amount of data transferred over the network and to reduce the load on the server. Fetching is similar to 
  joins and column selection in SQL, but is inspired by data fetching in the GraphQL protocol by incrementally following
  the relationships in the data.
date: '23.7.2023'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---

To demonstrate a more complex and useful example let's fetch a product with its category reference and for the category
fetch its full hierarchy placement up to the root of the hierarchy tree with `code` and `name` attributes of these
categories. The query looks like this:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>
[Getting localized name of the brand](/documentation/user/en/query/requirements/examples/fetching/hierarchyContentViaReference.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with its hierarchy placement
</NoteTitle>

The query returns the following product with the reference to the full `Category` entity hierarchy chain:

<LanguageSpecific to="evitaql,java">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with hierarchy placement](/documentation/user/en/query/requirements/examples/fetching/hierarchyContentViaReference.evitaql.json.md)</MDInclude>

</LanguageSpecific>

This quite complex example uses the [`referenceContent`](#reference-content) requirement that is described in a following
chapter.

</Note>

## Price content
## Reference content
