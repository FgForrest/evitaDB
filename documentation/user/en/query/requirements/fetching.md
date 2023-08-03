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

<LanguageSpecific to="evitaql,java,rest">

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
        dataInLocales|
        dataInLocalesAll|
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
            <li>[associatedDataContent](#associated-data-content)</li>
            <li>[associatedDataContentAll](#associated-data-content-all)</li>
            <li>[dataInLocales](#data-in-locales)</li>
            <li>[dataInLocalesAll](#data-in-locales-all)</li>
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

</LanguageSpecific>

<LanguageSpecific to="java">
In the Java API including the `entityFetch` requirement in the query changes the output type in the response container.
Instead of returning a <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/structure/EntityReference.java</SourceClass>
for each entity, the <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/SealedEntity.java</SourceClass>
type is returned.
</LanguageSpecific>

<LanguageSpecific to="graphql">

## Entity content

The most simple data you can fetch in an entity is its primary key and type. These are returned from evitaDB with every query no matter how rich
you requested the entity to be.

<SourceCodeTabs langSpecificTabOnly>
[Getting code and name of the brand](/documentation/user/en/query/requirements/examples/fetching/basic-entity-content.graphql)
</SourceCodeTabs>

Additionally, you can fetch other entity fields which triggers fetching entire entity body:

<SourceCodeTabs langSpecificTabOnly>
[Getting code and name of the brand](/documentation/user/en/query/requirements/examples/fetching/entity-content.graphql)
</SourceCodeTabs>

</LanguageSpecific>

## Attribute content

<LanguageSpecific to="evitaql,java,rest">

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
requirement is used to retrieve one or more entity or reference [attributes](../../use/data-model.md#attributes-unique-filterable-sortable-localized). [Localized attributes](../../use/data-model.md#localized-attributes) 
are only fetched if there is a *locale context* in the query, either by using the [`entityLocaleEquals`](../filtering/locale.md#entity-locale-equals) 
filter constraint or the [`dataInLocales`](#data-in-locale) require constraint.

<Note type="info">

All entity attributes are fetched from disk in bulk, so specifying only a few of them in the `attributeContent`
requirement only reduces the amount of data transferred over the network. It's not bad to fetch all the attributes of 
an entity using [`attributeContentAll`](#attribute-content-all).

</Note>

To select a `code` and localized `name` attribute for the `Brand` entity, use the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>
[Getting code and name of the brand](/documentation/user/en/query/requirements/examples/fetching/attributeContent.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetch with named attributes
</NoteTitle>

The query returns the following attributes of the `Brand` entity:

<LanguageSpecific to="evitaql,java">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with named attributes](/documentation/user/en/query/requirements/examples/fetching/attributeContent.evitaql.md)</MDInclude>

</LanguageSpecific>
<LanguageSpecific to="rest">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with named attributes](/documentation/user/en/query/requirements/examples/fetching/attributeContent.rest.json.md)</MDInclude>

</LanguageSpecific>

As you can see, the name is in the English localization thanks to the `entityLocaleEquals` filter constraint in 
the query.

</Note>

</LanguageSpecific>

<LanguageSpecific to="graphql">

To fetch entity or reference [attributes](../../use/data-model.md#attributes-unique-filterable-sortable-localized), 
simply use `attributes` output field in an entity or reference object with specific attribute names as sub-fields.
[Localized attributes](../../use/data-model.md#localized-attributes) need a *locale context* in the query,
either by using the [`entityLocaleEquals`](../filtering/locale.md#entity-locale-equals) filter constraint or
by explicitly specifying `locale` argument of the `attributes` field. Using GraphQL aliases, you can fetch the same
attributes in multiple locales in a single query.

<SourceCodeTabs langSpecificTabOnly>
[Getting code and name of the brand](/documentation/user/en/query/requirements/examples/fetching/attributeContent.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetch with named attributes
</NoteTitle>

<MDInclude sourceVariable="data.queryBrand.recordPage">[The result of an entity fetch with named attributes](/documentation/user/en/query/requirements/examples/fetching/attributeContent.graphql.json.md)</MDInclude>

As you can see, the name is in the English localization thanks to the `entityLocaleEquals` filter constraint in
the query.

</Note>

If the locale filter is missing in the query, but you still want to access the localized data, you can specify the `locale` argument
on the `attributes` field as mentioned above:

<SourceCodeTabs langSpecificTabOnly>
[Getting localized name of the brand](/documentation/user/en/query/requirements/examples/fetching/localizedAttributes.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with explicit locale
</NoteTitle>

The query returns the following localized attributes of the `Brand` entity:

<MDInclude sourceVariable="data.queryBrand.recordPage">[The result of an entity fetch with localized attributes](/documentation/user/en/query/requirements/examples/fetching/localizedAttributes.graphql.json.md)</MDInclude>

If the `locale` argument was not used in the query, accessing the *name* attribute would return an error.
In the example above, the *name* attribute is accessible in the Czech locale even though the `entityLocaleEquals` filter
constraint was not used at all.

</Note>

To demonstrate the second scenario, let's say you want to filter a brand that has a Czech localization, but you want to
get Czech, German, and English *name* attribute values. The following query will do the job:

<SourceCodeTabs langSpecificTabOnly>
[Getting code and name of the brand in multiple locales](/documentation/user/en/query/requirements/examples/fetching/localizedAttributesWithFilter.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with multiple locales
</NoteTitle>

The query returns the following localized attributes of the `Brand` entity:

<MDInclude sourceVariable="data.queryBrand.recordPage">[The result of an entity fetch with localized attributes in multiple locales](/documentation/user/en/query/requirements/examples/fetching/localizedAttributesWithFilter.graphql.json.md)</MDInclude>

<ToDo>LHO: GQL currently returns error for the missing german locale</ToDo>

As you can see the localized attributes are available for the Czech and English locales but not for the German locale.
The entity is still present in the result, because the filter constraint enforces the Czech locale context, which is
satisfied by the entity.

</Note>

</LanguageSpecific>

<LanguageSpecific to="evitaql,java,rest">

### Attribute content all

```evitaql-syntax
attributeContentAll()
```

This constraint is a shorthand for the `attributeContent` constraint with all entity or reference attributes defined in 
the entity or reference schema. This constraint variant is an alternative to using the SQL wildcard `*` in the `SELECT` 
clause. 

To select all non-localized attributes for the `Brand` entity, use the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>
[Getting code and name of the brand](/documentation/user/en/query/requirements/examples/fetching/attributeContentAll.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetch with all attributes
</NoteTitle>

The query returns the following attributes of the `Brand` entity:

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

</LanguageSpecific>

## Associated data content

<LanguageSpecific to="evitaql,java,rest">

```evitaql-syntax
associatedDataContent(
    argument:string+
)
```

<dl>
    <dt>argument:string+</dt>
    <dd>
        one or more mandatory entity associated data names to be fetched along with the entity
    </dd>
</dl>

The `associatedDataContent` (<SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/AssociatedDataContent.java</SourceClass>)
requirement is used to retrieve one or more entity [associated data](../../use/data-model.md#associated-data). 
[Localized associated data](../../use/data-model.md#localized-associated-data) are only fetched if
there is a *locale context* in the query, either by using the [`entityLocaleEquals`](../filtering/locale.md#entity-locale-equals)
filter constraint or the [`dataInLocales`](#data-in-locale) require constraint.

To select a *allActiveurls* and localized *localization* associated data for the `Brand` entity, use the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>
[Getting code and name of the brand](/documentation/user/en/query/requirements/examples/fetching/associatedDataContent.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetch with named associated data
</NoteTitle>

The query returns the following associated data of the `Brand` entity:

<LanguageSpecific to="evitaql,java">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with named associated data](/documentation/user/en/query/requirements/examples/fetching/associatedDataContent.evitaql.json.md)</MDInclude>

</LanguageSpecific>
<LanguageSpecific to="graphql">

<MDInclude sourceVariable="data.queryBrand.recordPage">[The result of an entity fetch with named associated data](/documentation/user/en/query/requirements/examples/fetching/associatedDataContent.graphql.json.md)</MDInclude>

</LanguageSpecific>
<LanguageSpecific to="rest">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with named associated data](/documentation/user/en/query/requirements/examples/fetching/associatedDataContent.rest.json.md)</MDInclude>

</LanguageSpecific>

As you can see, the *localization* related data item contains the texts in the English localization thanks
to the `entityLocaleEquals` filter constraint in the query. The *allActiveUrls* is a non-localized related
data item that contains active URL addresses for a particular brand in different languages that could
be used to generate a language selection menu for this brand record.

</Note>

</LanguageSpecific>

<LanguageSpecific to="graphql">

To fetch entity [associated data](../../use/data-model.md#associated-data),
simply use `associatedData` output field in an entity object with specific associated data names as sub-fields.
[Localized associated data](../../use/data-model.md#localized-associated-data) need a *locale context* in the query,
either by using the [`entityLocaleEquals`](../filtering/locale.md#entity-locale-equals) filter constraint or
by explicitly specifying `locale` argument of the `associatedData` field. Using GraphQL aliases, you can fetch the same
associated data in multiple locales in a single query.

<SourceCodeTabs langSpecificTabOnly>
[Getting code and name of the brand](/documentation/user/en/query/requirements/examples/fetching/associatedDataContent.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetch with named associated data
</NoteTitle>

The query returns the following associated data of the `Brand` entity:

<MDInclude sourceVariable="data.queryBrand.recordPage">[The result of an entity fetch with named associated data](/documentation/user/en/query/requirements/examples/fetching/associatedDataContent.graphql.json.md)</MDInclude>

</Note>

If the locale filter is missing in the query, but you still want to access the localized data, you can specify the `locale` argument
on the `associatedData` field as mentioned above:

<SourceCodeTabs langSpecificTabOnly>
[Getting localized name of the brand](/documentation/user/en/query/requirements/examples/fetching/localizedAssociatedData.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with explicit locale
</NoteTitle>

The query returns the following localized associated data of the `Brand` entity:

<MDInclude sourceVariable="data.queryBrand.recordPage">[The result of an entity fetch with localized attributes](/documentation/user/en/query/requirements/examples/fetching/localizedAssociatedData.graphql.json.md)</MDInclude>

If the `locale` argument was not used in the query, accessing the *localization* associated data would return an error.
In the example above, the *localization* associated data is accessible in the Czech locale even though the `entityLocaleEquals` filter
constraint was not used at all.

</Note>

To demonstrate the second scenario, let's say you want to filter a brand that has a Czech localization, but you want to
get Czech, German, and English *localization* associated data values. The following query will do the job:

<SourceCodeTabs langSpecificTabOnly>
[Getting code and name of the brand in multiple locales](/documentation/user/en/query/requirements/examples/fetching/localizedAssociatedDataWithFilter.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with multiple locales
</NoteTitle>

The query returns the following localized associated data of the `Brand` entity:

<MDInclude sourceVariable="data.queryBrand.recordPage">[The result of an entity fetch with localized attributes in multiple locales](/documentation/user/en/query/requirements/examples/fetching/localizedAssociatedDataWithFilter.graphql.json.md)</MDInclude>

<!-- TODO LHO: GQL currently returns error for the missing german locale -->

As you can see the localized associated data are available for the Czech and English locales but not for the German locale.
The entity is still present in the result, because the filter constraint enforces the Czech locale context, which is
satisfied by the entity.

</Note>

</LanguageSpecific>

<LanguageSpecific to="evitaql,java,rest">

### Associated data content all

```evitaql-syntax
associatedDataContentAll()
```

This constraint is a shorthand for the `associatedDataContent` constraint with all entity associated data defined in
the entity schema. This constraint variant is an alternative to using the SQL wildcard `*` in the `SELECT` clause.

<Note type="warning">

Because the associated data is expected to store large amounts of unstructured data, each of the data is stored as
a separate record. You should always fetch only the associated data you need, as fetching all of it will slow down
the processing of the request. The [`associatedDataContentAll`](#associated-data-content-all) request should only be
used for debugging or exploratory purposes and should not be included in production code.

</Note>

To select all non-localized associated data for the `Brand` entity, use the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>
[Getting code and name of the brand](/documentation/user/en/query/requirements/examples/fetching/associatedDataContentAll.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetch with all the associated data
</NoteTitle>

The query returns the following associated data of the `Brand` entity:

<LanguageSpecific to="evitaql,java">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with all the associated data](/documentation/user/en/query/requirements/examples/fetching/associatedDataContentAll.evitaql.json.md)</MDInclude>

</LanguageSpecific>
<LanguageSpecific to="graphql">

<MDInclude sourceVariable="data.queryBrand.recordPage">[The result of an entity fetch with all the associated data](/documentation/user/en/query/requirements/examples/fetching/associatedDataContentAll.graphql.json.md)</MDInclude>

</LanguageSpecific>
<LanguageSpecific to="rest">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with all the associated data](/documentation/user/en/query/requirements/examples/fetching/associatedDataContentAll.rest.json.md)</MDInclude>

</LanguageSpecific>

All the localized associated data are missing, because there is no localization context present in the query.

</Note>

</LanguageSpecific>

<LanguageSpecific to="evitaql,java,rest">

## Data in locales

```evitaql-syntax
dataInLocales(
    argument:string+
)
```

<dl>
    <dt>argument:string+</dt>
    <dd>
        a mandatory specification of the one or more [locales](https://en.wikipedia.org/wiki/IETF_language_tag) in which
        the localized entity or reference localized attributes and entity associated data will be fetched; examples of 
        a valid language tags are: `en-US` or `en-GB`, `cs` or `cs-CZ`, `de` or `de-AT`, `de-CH`, `fr` or `fr-CA` etc.
    </dd>
</dl>

The `dataInLocales` (<SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/DataInLocales.java</SourceClass>)
requirement is used in two scenarios:

1. there is no *locale context* in the filter part of the query, because you don't want to exclude entities without
   the requested locale from the result, but you want to fetch the localized data in one or more languages if they
   are available for the entity or reference
2. there is a *locale context* in the filter part of the query, but you want to fetch the localized data in different
   or additional languages than the one specified in the *locale context*

If the locale filter is missing in the query, but you still want to access the localized data, you can use the following 
query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>
[Getting localized name of the brand](/documentation/user/en/query/requirements/examples/fetching/dataInLocales.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with explicit locale
</NoteTitle>

The query returns the following localized attributes of the `Brand` entity:

<LanguageSpecific to="evitaql,java">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with localized attributes](/documentation/user/en/query/requirements/examples/fetching/dataInLocales.evitaql.md)</MDInclude>

</LanguageSpecific>
<LanguageSpecific to="rest">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with localized attributes](/documentation/user/en/query/requirements/examples/fetching/dataInLocales.rest.json.md)</MDInclude>

</LanguageSpecific>

If the `dataInLocales` requirement was not used in the query, accessing the *name* attribute would throw an exception. 
In the example above, the *name* attribute is accessible in the Czech locale even though the `entityLocaleEquals` filter 
constraint was not used at all.

</Note>

To demonstrate the second scenario, let's say you want to filter a brand that has a Czech localization, but you want to
get Czech and English *name* attribute values. The following query will do the job:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>
[Getting code and name of the brand in multiple locales](/documentation/user/en/query/requirements/examples/fetching/dataInLocalesWithFilter.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with multiple locales
</NoteTitle>

The query returns the following localized attributes of the `Brand` entity:

<LanguageSpecific to="evitaql,java">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with localized attributes in multiple locales](/documentation/user/en/query/requirements/examples/fetching/dataInLocalesWithFilter.evitaql.md)</MDInclude>

</LanguageSpecific>
<LanguageSpecific to="rest">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with localized attributes in multiple locales](/documentation/user/en/query/requirements/examples/fetching/dataInLocalesWithFilter.rest.json.md)</MDInclude>

</LanguageSpecific>

As you can see the localized attributes are available both for the Czech and English locales.
The entity is still present in the result, because the filter constraint enforces the Czech locale context, which is
satisfied by the entity.

</Note>

</LanguageSpecific>

<LanguageSpecific to="evitaql,java,rest">

## Data in locales all

```evitaql-syntax
dataInLocalesAll()
```

The `dataInLocalesAll` allows you to retrieve attributes and associated data in all available locales. This is usually 
useful in scenarios where you are publishing the data from the primary data source and you need to create/update all 
the data in one go. If you are accessing the data as a client application, you will probably always want to fetch 
the data in a specific locale, which means you will use the `dataInLocales` requirement with a single locale 
or `entityLocaleEquals` filtering constraint instead.

To fetch entity in all locales available, use the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>
[Getting localized name of the brand](/documentation/user/en/query/requirements/examples/fetching/dataInLocalesAll.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched in all available locales
</NoteTitle>

The query returns the following localized attributes of the `Brand` entity:

<LanguageSpecific to="evitaql,java">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch in all available locales](/documentation/user/en/query/requirements/examples/fetching/dataInLocalesAll.evitaql.md)</MDInclude>

</LanguageSpecific>
<LanguageSpecific to="graphql">

<MDInclude sourceVariable="data.queryBrand.recordPage">[The result of an entity fetch in all available locales](/documentation/user/en/query/requirements/examples/fetching/dataInLocalesAll.graphql.json.md)</MDInclude>

</LanguageSpecific>
<LanguageSpecific to="rest">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch in all available locales](/documentation/user/en/query/requirements/examples/fetching/dataInLocalesAll.rest.json.md)</MDInclude>

</LanguageSpecific>

As you can see, the entity is returned with the Czech and English locales for which the localized attributes or 
associated data are available.

</Note>

</LanguageSpecific>

## Hierarchy content
## Price content
## Reference content
