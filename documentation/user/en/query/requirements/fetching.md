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

**Work in progress**

If no content requirement is used in the query, the result will contain only the primary key of the entity. While this 
may be sufficient for some queries, it is usually necessary to fetch some data from the entity or even other entities
related to the entity. For this purpose, the [`entityFetch`](#entity-fetch) request and nested *content* requests 
described in this section are used:

## Entity fetch

```evitaql-syntax
entityFetch(
    requireConstraint:(
        attributeContent|
        attributeContentAll|
        associatedDataContent|
        associatedDataContentAll|
        dataInLocale|
        hierarchyContent|       
        priceContent|
        priceContentAll|
        priceContentRespectingFilter|
        referenceContent|
        referenceContentWithAttributes|
        referenceContentAll|
        referenceContentAllWithAttributes
    )*   
)
```

<dl>
    <dt>requireConstraint:(...)*</dt>
    <dd>
        optional one or more constraints allowing you to instruct evitaDB to fetch the entity contents; 
        one or all of the constraints may be present:
        <ul>
            <li>[attributeContent](#attribute-content)</li>
            <li>[attributeContentAll](#attribute-content-all)</li>
            <li>[associatedDataContent/associatedDataContentAll](#associated-data-content)</li>
            <li>[dataInLocale](#data-in-locale)</li>
            <li>[hierarchyContent](#hierarchy-content)</li>
            <li>[priceContent/priceContentAll/priceContentRespectingFilter](#price-content)</li>
            <li>[referenceContent/referenceContentWithAttributes/referenceContentAll/referenceContentAllWithAttributes](#reference-content)</li>
        </ul>
    </dd>
</dl>

The `entityFetch` (<SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/EntityFetch.java</SourceClass>)
requirement is used to trigger loading one or more entity data containers from the disk by its primary key. 
This operation requires a disk access unless the entity is already loaded in the database cache (frequently fetched
entities have higher chance to stay in the cache).

<LanguageSpecific to="java">
In the Java API including the `entityFetch` requirement in the query changes the output type in the response container.
Instead of returning a <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/structure/EntityReference.java</SourceClass>
for each entity, the <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/SealedEntity.java</SourceClass>
type is returned.
</LanguageSpecific>

## Attribute content

```evitaql-syntax
attributeContent(
    argument:string+
)
```

<dl>
    <dt>argument:string+</dt>
    <dd>
        one or more mandatory entity or reference attribute names to be fetched along with the entity
    </dd>
</dl>

The `attributeContent` (<SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/AttributeContent.java</SourceClass>) 
requirement is used to retrieve one or more entity or reference attributes. Localized attributes are only fetched if 
there is a *locale context* in the query, either by using the [`entityLocaleEquals`](../filtering/locale.md#entity-locale-equals) 
filter constraint or the [`dataInLocale`](#data-in-locale) require constraint. All entity attributes are fetched from 
disk in bulk, so specifying only a few of them in the `attributeContent` requirement only reduces the amount of data 
transferred over the network.

To select a `code` and localized `name` attribute for the `product` entity, use the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>
[Getting code and name of the product](/documentation/user/en/query/requirements/examples/fetching/attributeContent.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetch with named attributes
</NoteTitle>

The query returns the following attributes of the `Product` entity:

<LanguageSpecific to="evitaql,java">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with named attributes](/documentation/user/en/query/requirements/examples/fetching/attributeContent.evitaql.md)</MDInclude>

</LanguageSpecific>
<LanguageSpecific to="graphql">

<MDInclude sourceVariable="data.queryBrand.recordPage">[The result of an entity fetch with named attributes](/documentation/user/en/query/requirements/examples/fetching/attributeContent.graphql.json.md)</MDInclude>

</LanguageSpecific>
<LanguageSpecific to="rest">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with named attributes](/documentation/user/en/query/requirements/examples/fetching/attributeContent.rest.json.md)</MDInclude>

</LanguageSpecific>

As you can see, the name is in the English localization thanks to the `entityLocaleEquals` filter constraint in 
the query.

</Note>

### Attribute all content

```evitaql-syntax
attributeContentAll()
```

This constraint is a shorthand for the `attributeContent` constraint with all entity or reference attributes defined in 
the entity or reference schema. This constraint variant is an alternative to using the SQL wildcard `*` in the `SELECT` 
clause. 

To select all non-localized attributes for the `Product` entity, use the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>
[Getting code and name of the product](/documentation/user/en/query/requirements/examples/fetching/attributeContentAll.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetch with all attributes
</NoteTitle>

The query returns the following attributes of the `Product` entity:

<LanguageSpecific to="evitaql,java">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with all attributes](/documentation/user/en/query/requirements/examples/fetching/attributeContentAll.evitaql.md)</MDInclude>

</LanguageSpecific>
<LanguageSpecific to="graphql">

<MDInclude sourceVariable="data.queryBrand.recordPage">[The result of an entity fetch with all attributes](/documentation/user/en/query/requirements/examples/fetching/attributeContentAll.graphql.json.md)</MDInclude>

</LanguageSpecific>
<LanguageSpecific to="rest">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with all attributes](/documentation/user/en/query/requirements/examples/fetching/attributeContentAll.rest.json.md)</MDInclude>

</LanguageSpecific>

All the localized attributes are missing, because there is no localization context present in the query.

</Note>

## Associated data content
## Data in locale
## Hierarchy content
## Price content
## Reference content
