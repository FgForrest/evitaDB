---
title: Fetching
perex: |
  Fetch request constraints help control the amount of data returned in the query response. This technique is used to
  reduce the amount of data transferred over the network and to reduce the load on the server. Fetching is similar to
  joins and column selection in SQL, but is inspired by data fetching in the GraphQL protocol by incrementally following
  the relationships in the data.
date: '23.7.2023'
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'evitaql'
---

<LS to="e,j,c,r">

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
            <li>[priceContent](#price-content)</li>
            <li>[priceContentAll](#price-content-all)</li>
            <li>[priceContentRespectingFilter](#price-content-respecting-filter)</li>
            <li>[referenceContent](#reference-content)</li>
            <li>[referenceContentAll](#reference-content-all)</li>
            <li>[referenceContentWithAttributes](#reference-content-with-attributes)</li>
            <li>[referenceContentAllWithAttributes](#reference-content-all-with-attributes)</li>
        </ul>
    </dd>
</dl>

</LS>

The `entityFetch` (<LS to="e,j,r"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/EntityFetch.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/EntityFetch.cs</SourceClass></LS>)
requirement is used to trigger loading one or more entity data containers from the disk by its primary key.
This operation requires a disk access unless the entity is already loaded in the database cache (frequently fetched
entities have higher chance to stay in the cache).

<LS to="j,c">
<LS to="j">In the Java API</LS><LS to="c">In the C# client</LS>,
including the `entityFetch` requirement in the query changes the output type in the response container.
Instead of returning an <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/structure/EntityReference.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/Data/Structure/EntityReference.cs</SourceClass></LS>
for each entity, the <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/SealedEntity.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/Data/ISealedEntity.cs</SourceClass></LS>
type is returned.
</LS>

<LS to="e,j,c,r">

## Entity group fetch

```evitaql-syntax
entityGroupFetch(
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
        optional one or more constraints allowing you to instruct evitaDB to fetch the group entity contents;
        one or all of the constraints may be present:
        <ul>
            <li>[attributeContent](#attribute-content)</li>
            <li>[attributeContentAll](#attribute-content-all)</li>
            <li>[associatedDataContent](#associated-data-content)</li>
            <li>[associatedDataContentAll](#associated-data-content-all)</li>
            <li>[dataInLocales](#data-in-locales)</li>
            <li>[dataInLocalesAll](#data-in-locales-all)</li>
            <li>[hierarchyContent](#hierarchy-content)</li>
            <li>[priceContent](#price-content)</li>
            <li>[priceContentAll](#price-content-all)</li>
            <li>[priceContentRespectingFilter](#price-content-respecting-filter)</li>
            <li>[referenceContent](#reference-content)</li>
            <li>[referenceContentAll](#reference-content-all)</li>
            <li>[referenceContentWithAttributes](#reference-content-with-attributes)</li>
            <li>[referenceContentAllWithAttributes](#reference-content-all-with-attributes)</li>
        </ul>
    </dd>
</dl>

Same as the [`entityFetch`](#entity-fetch) but used for fetching entities that represents reference group.

</LS>

<LS to="g">

## Entity content

The simplest data you can fetch with an entity is its primary key and type. These are returned by evitaDB with every query,
no matter how rich you requested the entity to be.

<SourceCodeTabs langSpecificTabOnly>

[Getting code and name of the brand](/documentation/user/en/query/requirements/examples/fetching/basicEntityContent.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetch with only basic data
</NoteTitle>

The query returns the following basic data of the `Brand` entity:

<MDInclude sourceVariable="data.queryBrand.recordPage">[The result of an entity fetch with only basic data](/documentation/user/en/query/requirements/examples/fetching/basicEntityContent.graphql.json.md)</MDInclude>

</Note>

Moreover, you can fetch other fields with entity (which would lead to fetching entire entity body under the hood):

<SourceCodeTabs langSpecificTabOnly>

[Getting code and name of the brand](/documentation/user/en/query/requirements/examples/fetching/entityContent.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetch with entity body data
</NoteTitle>

The query returns the following entity body of the `Brand` entity:

<MDInclude sourceVariable="data.queryBrand.recordPage">[The result of an entity fetch with entity body](/documentation/user/en/query/requirements/examples/fetching/entityContent.graphql.json.md)</MDInclude>

</Note>

</LS>

## Attribute content

<LS to="e,j,c,r">

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

The `attributeContent` (<LS to="e,j,r"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/AttributeContent.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/AttributeContent.cs</SourceClass></LS>)
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

<LS to="e,j,c">

<MDInclude>[The result of an entity fetch with named attributes](/documentation/user/en/query/requirements/examples/fetching/attributeContent.evitaql.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with named attributes](/documentation/user/en/query/requirements/examples/fetching/attributeContent.rest.json.md)</MDInclude>

</LS>

As you can see, the name is in the English localization thanks to the `entityLocaleEquals` filter constraint in
the query.

</Note>

</LS>

<LS to="g">

To fetch entity or reference [attributes](../../use/data-model.md#attributes-unique-filterable-sortable-localized),
use the `attributes` field within an entity or reference object and specify the required attribute names as sub-fields.
[Localized attributes](../../use/data-model.md#localized-attributes) need a *locale context* in the query,
either by using the [`entityLocaleEquals`](../filtering/locale.md#entity-locale-equals) filter constraint or
by explicitly specifying `locale` argument of the `attributes` field. Using GraphQL aliases, you can fetch the same
attributes in multiple locales in a single query.

<Note type="info">

All entity attributes are fetched from disk in bulk, so specifying only a few of them in the `attributes` field
only reduces the amount of data transferred over the network. It's not bad to fetch all the attributes of
an entity.

</Note>

To select a `code` and localized `name` attribute for the `Brand` entity, use the following query:

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

As you can see, the localized attributes are available for the Czech and English locales but not for the German locale.
The entity is still present in the result, because the filter constraint enforces the Czech locale context, which is
satisfied by the entity.

</Note>

</LS>

<LS to="e,j,c,r">

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

<LS to="e,j,c">

<MDInclude>[The result of an entity fetch with all attributes](/documentation/user/en/query/requirements/examples/fetching/attributeContentAll.evitaql.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with all attributes](/documentation/user/en/query/requirements/examples/fetching/attributeContentAll.rest.json.md)</MDInclude>

</LS>

All the localized attributes are missing, because there is no localization context present in the query.

</Note>

</LS>

## Associated data content

<LS to="e,j,c,r">

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

The `associatedDataContent` (<LS to="e,j,r"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/AssociatedDataContent.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/AssociatedDataContent.cs</SourceClass></LS>)
requirement is used to retrieve one or more entity [associated data](../../use/data-model.md#associated-data).
[Localized associated data](../../use/data-model.md#localized-associated-data) are only fetched if
there is a *locale context* in the query, either by using the [`entityLocaleEquals`](../filtering/locale.md#entity-locale-equals)
filter constraint or the [`dataInLocales`](#data-in-locale) require constraint.

To select an *allActiveUrls* and localized *localization* associated data for the `Brand` entity, use the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Getting code and name of the brand](/documentation/user/en/query/requirements/examples/fetching/associatedDataContent.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetch with named associated data
</NoteTitle>

The query returns the following associated data of the `Brand` entity:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with named associated data](/documentation/user/en/query/requirements/examples/fetching/associatedDataContent.evitaql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with named associated data](/documentation/user/en/query/requirements/examples/fetching/associatedDataContent.rest.json.md)</MDInclude>

</LS>

As you can see, the *localization* related data item contains the texts in the English localization thanks
to the `entityLocaleEquals` filter constraint in the query. The *allActiveUrls* is a non-localized related
data item that contains active URL addresses for a particular brand in different languages that could
be used to generate a language selection menu for this brand record.

</Note>

</LS>

<LS to="g">

To fetch entity [associated data](../../use/data-model.md#associated-data),
use the `associatedData` field within an entity object and specify required associated data names as sub-fields.
[Localized associated data](../../use/data-model.md#localized-associated-data) need a *locale context* in the query,
either by using the [`entityLocaleEquals`](../filtering/locale.md#entity-locale-equals) filter constraint or
by explicitly specifying `locale` argument of the `associatedData` field. Using GraphQL aliases, you can fetch the same
associated data in multiple locales in a single query.

To fetch an *allActiveUrls* and localized *localization* associated data for the `Brand` entity, use the following query:

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

As you can see the localized associated data are available for the Czech and English locales but not for the German locale.
The entity is still present in the result, because the filter constraint enforces the Czech locale context, which is
satisfied by the entity.

</Note>

</LS>

<LS to="e,j,c,r">

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

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with all the associated data](/documentation/user/en/query/requirements/examples/fetching/associatedDataContentAll.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryBrand.recordPage">[The result of an entity fetch with all the associated data](/documentation/user/en/query/requirements/examples/fetching/associatedDataContentAll.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with all the associated data](/documentation/user/en/query/requirements/examples/fetching/associatedDataContentAll.rest.json.md)</MDInclude>

</LS>

All the localized associated data are missing, because there is no localization context present in the query.

</Note>

</LS>

<LS to="e,j,c,r">

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

The `dataInLocales` (<LS to="e,j,r"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/DataInLocales.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/DataInLocales.cs</SourceClass></LS>)
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

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with localized attributes](/documentation/user/en/query/requirements/examples/fetching/dataInLocales.evitaql.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with localized attributes](/documentation/user/en/query/requirements/examples/fetching/dataInLocales.rest.json.md)</MDInclude>

</LS>

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

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with localized attributes in multiple locales](/documentation/user/en/query/requirements/examples/fetching/dataInLocalesWithFilter.evitaql.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with localized attributes in multiple locales](/documentation/user/en/query/requirements/examples/fetching/dataInLocalesWithFilter.rest.json.md)</MDInclude>

</LS>

As you can see, the localized attributes are available both for the Czech and English locales.
The entity is still present in the result, because the filter constraint enforces the Czech locale context, which is
satisfied by the entity.

</Note>

</LS>

<LS to="e,j,c,r">

### Data in locales all

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

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch in all available locales](/documentation/user/en/query/requirements/examples/fetching/dataInLocalesAll.evitaql.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryBrand.recordPage">[The result of an entity fetch in all available locales](/documentation/user/en/query/requirements/examples/fetching/dataInLocalesAll.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch in all available locales](/documentation/user/en/query/requirements/examples/fetching/dataInLocalesAll.rest.json.md)</MDInclude>

</LS>

As you can see, the entity is returned with the Czech and English locales for which the localized attributes or
associated data are available.

</Note>

</LS>

## Hierarchy content

<LS to="e,j,c,r">

```evitaql-syntax
hierarchyContent(
    requireConstraint:(entityFetch|stopAt)*
)
```

<dl>
    <dt>requireConstraint:(entityFetch|stopAt)*</dt>
    <dd>
        optional one or more constraints that allow you to define the completeness of the hierarchy entities and
        the scope of the traversed hierarchy tree;
        any or both of the constraints may be present:
        <ul>
            <li>[entityFetch](fetching.md#entity-fetch)</li>
            <li>[stopAt](hierarchy.md#stop-at)</li>
        </ul>
    </dd>
</dl>

The `hierarchyContent` (<LS to="e,j,r"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/HierarchyContent.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/HierarchyContent.cs</SourceClass></LS>)
requirement allows you to access the information about the hierarchical placement of the entity.

If no additional constraints are specified, entity will contain a full chain of parent primary keys up to the root of
a hierarchy tree. You can limit the size of the chain by using a `stopAt` constraint - for example, if you're only
interested in a direct parent of each entity returned, you can use a `stopAt(distance(1))` constraint. The result is
similar to using a [`parents`](hierarchy.md#parents) constraint, but is limited in that it doesn't provide information
about statistics and the ability to list siblings of the entity parents. On the other hand, it's easier to use - since
the hierarchy placement is directly available in the retrieved entity object.

If you provide a nested [`entityFetch`](#entity-fetch) constraint, the hierarchy information will contain the bodies of
the parent entities in the required width. The [`attributeContent`](#attribute-content) inside the `entityFetch` allows
you to access the attributes of the parent entities, etc.

To fetch an entity with basic hierarchy information, use the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Getting localized name of the brand](/documentation/user/en/query/requirements/examples/fetching/hierarchyContent.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with its hierarchy placement
</NoteTitle>

The query returns the following hierarchy of the `Category` entity:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with hierarchy placement](/documentation/user/en/query/requirements/examples/fetching/hierarchyContent.evitaql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with hierarchy placement](/documentation/user/en/query/requirements/examples/fetching/hierarchyContent.rest.json.md)</MDInclude>

</LS>

The `Category` entity is returned with the hierarchy information up to the root of the hierarchy tree.

</Note>

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

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with hierarchy placement](/documentation/user/en/query/requirements/examples/fetching/hierarchyContentViaReference.evitaql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The result of an entity fetch with hierarchy placement](/documentation/user/en/query/requirements/examples/fetching/hierarchyContentViaReference.rest.json.md)</MDInclude>

</LS>

This quite complex example uses the [`referenceContent`](#reference-content) requirement that is described in a following
chapter.

</Note>

</LS>

<LS to="g">

To access the information about the hierarchical placement of an entity, use either the `parentPrimaryKey` or the `parents` field (or both).

The `parentPrimaryKey` field returns only the primary key of the direct parent of the entity. If the entity has no parent
(i.e. it's a root entity), the field returns `null`.

The `parents` fields allows you to access the entire chain of parent entities up to the root of a hierarchy tree. Inside
you can use standard entity fields besides the `parents` field for the fetched parent entities. If no additional arguments
are specified the list will contain a entire chain of parent entities up to the root of a hierarchy tree. You can limit the
size of the chain by using a `stopAt` argument - for example, if you're only interested in a direct parent of each entity
returned, you can use a `stopAt: { distance: 1 }` argument. The arguments takes special require constraints to specify
on which node to stop the hierarchy traversal.
The result is similar to using a [`parents`](hierarchy.md#parents) requirement, but is limited in that it doesn't provide
information about statistics and the ability to list siblings of the entity parents. On the other hand, it's easier to
use - since the hierarchy placement is directly available in the retrieved entity object.

To fetch an entity with basic hierarchy information, use the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Getting localized name of the brand](/documentation/user/en/query/requirements/examples/fetching/hierarchyContent.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with its hierarchy placement
</NoteTitle>

The query returns the following hierarchy of the `Category` entity:

<MDInclude sourceVariable="data.queryCategory.recordPage">[The result of an entity fetch with hierarchy placement](/documentation/user/en/query/requirements/examples/fetching/hierarchyContent.graphql.json.md)</MDInclude>

The `Category` entity is returned with the hierarchy information up to the root of the hierarchy tree.

</Note>

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

<MDInclude sourceVariable="data.queryProduct.recordPage">[The result of an entity fetch with hierarchy placement](/documentation/user/en/query/requirements/examples/fetching/hierarchyContentViaReference.graphql.json.md)</MDInclude>

This quite complex example uses a [category reference field](#reference-content) that is described in a following
chapter.

</Note>

</LS>

## Price content

<LS to="e,j,c,r">

```evitaql-syntax
priceContent(
    argument:enum(NONE|RESPECTING_FILTER|ALL),
    argument:string*
)
```

<dl>
    <dt>argument:enum(NONE|RESPECTING_FILTER|ALL)</dt>
    <dd>
        optional argument of type <LS to="e,j,r"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/PriceContentMode.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/PriceContentMode.cs</SourceClass></LS>
        enum allowing you to specify whether to fetch all, selected or no price records for the entity:

        - **NONE**: no prices will be fetched for the entity (even if the filter contains a price constraint)
        - **RESPECTING_FILTER**: only a prices in price lists selected by a filter constraint will be fetched
        - **ALL**: all prices of the entity will be fetched (regardless of the price constraint in a filter)

    </dd>
    <dt>argument:string*</dt>
    <dd>
        optional one or more string arguments representing price list names to add to the list of price lists passed in
        a filter price constraint, which together form a set of price lists for which to fetch prices for the entity
    </dd>
</dl>

The `priceContent` (<LS to="e,j,r"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/PriceContent.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/PriceContent.cs</SourceClass></LS>)
requirement allows you to access the information about the prices of the entity.

If the `RESPECTING_FILTER` mode is used, the `priceContent` requirement will only retrieve the prices selected by
the [`priceInPriceLists`](../filtering/price.md#price-in-price-lists) constraint. If the enum `NONE` is specified, no
prices are returned at all, if the enum `ALL` is specified, all prices of the entity are returned regardless of the
`priceInPriceLists` constraint in the filter (the constraint still controls whether the entity is returned at all).

You can also add additional price lists to the list of price lists passed in the `priceInPriceLists` constraint by
specifying the price list names as string arguments to the `priceContent` requirement. This is useful if you want to
fetch non-indexed prices of the entity that cannot (and are not intended to) be used to filter the entities, but you
still want to fetch them to display in the UI for the user.

<LS to="r">

For entities that have either `LOWEST_PRICE` or `SUM` inner record handling, the `multiplePricesForSaleAvailable` 
property is returned, indicating whether there are multiple _unique_ prices for sale (grouped by the `innerRecordId`). 
It is important to note that it doesn't simply return the count of all prices for sale.
Instead, it uses the [`priceType`](../requirements/price.md#price-type) constraint to determine the uniqueness of each
price value. This means that even if there are, say, 3 prices for sale, but they all have the same value, this property
will return `false`. This is especially useful for the
UI to determine whether to display a price range or just a single price without having to fetch all prices for sale.

</LS>

To get an entity with prices that you filter by, use the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Getting entity with prices and reference price](/documentation/user/en/query/requirements/examples/fetching/priceContent.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with its selected prices
</NoteTitle>

The query returns the following list of prices of the `Product` entity:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The result of an entity fetched with its selected prices](/documentation/user/en/query/requirements/examples/fetching/priceContent.evitaql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The result of an entity fetched with its selected prices](/documentation/user/en/query/requirements/examples/fetching/priceContent.rest.json.md)</MDInclude>

</LS>

As you can see, the prices for the filtered price lists *employee-basic-price* and *basic* are returned. This query is
equivalent to using the [`priceContentRespectingFilter`](#price-content-respecting-filter) alias.

</Note>

### Price content respecting filter

```evitaql-syntax
priceContent(
    argument:string*
)
```

<dl>
    <dt>argument:string*</dt>
    <dd>
        optional one or more string arguments representing price list names to add to the list of price lists passed in
        a filter price constraint, which together form a set of price lists for which to fetch prices for the entity
    </dd>
</dl>

The `priceContentRespectingFilter` (<LS to="e,j,r"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/PriceContent.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/PriceContent.cs</SourceClass></LS>)
requirement allows you to access the information about the prices of the entity. It fetches only the prices selected by
the [`priceInPriceLists`](../filtering/price.md#price-in-price-lists) constraint.

You can also add additional price lists to the list of price lists passed in the `priceInPriceLists` constraint by
specifying the price list names as string arguments to the `priceContent` requirement. This is useful if you want to
fetch non-indexed prices of the entity that cannot (and are not intended to) be used to filter the entities, but you
still want to fetch them to display in the UI for the user.

This requirement is only a variation of the generic [`priceContent`](#price-content) requirement.

To get an entity with prices that you filter by and a *reference* price on top of it, use the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Getting entity with filtered prices and reference price](/documentation/user/en/query/requirements/examples/fetching/priceContentRespectingFilter.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with its selected prices and reference price
</NoteTitle>

The query returns the following list of prices of the `Product` entity:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The result of an entity fetched with its selected prices and reference price](/documentation/user/en/query/requirements/examples/fetching/priceContentRespectingFilter.evitaql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The result of an entity fetched with its selected prices and reference price](/documentation/user/en/query/requirements/examples/fetching/priceContentRespectingFilter.rest.json.md)</MDInclude>

</LS>

As you can see, the prices for the filtered price lists *employee-basic-price* and *basic* are returned, as well as
the price in the *reference* price lists requested by the `priceContent` requirement.

</Note>

### Price content all

```evitaql-syntax
priceContentAll()
```

The `priceContentAll` (<LS to="e,j,r"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/PriceContent.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/PriceContent.cs</SourceClass></LS>)
requirement allows you to access all of the entity's price information regardless of the filtering constraints specified
in the query.

This requirement is only a variation of the generic [`priceContent`](#price-content) requirement.

To get an entity with all of the entity's prices, use the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Getting entity with prices and reference price](/documentation/user/en/query/requirements/examples/fetching/priceContentAll.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with all its prices
</NoteTitle>

The query returns the following list of prices of the `Product` entity:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The result of an entity fetched with all its prices](/documentation/user/en/query/requirements/examples/fetching/priceContentAll.evitaql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The result of an entity fetched with all its prices](/documentation/user/en/query/requirements/examples/fetching/priceContentAll.rest.json.md)</MDInclude>

</LS>

As you can see, all prices of the entity are returned in all available currencies - not only the filtered price lists
*employee-basic-price* and *basic*. Thanks to `priceContentAll` you have an overview of all prices of the entity.

</Note>

</LS>
<LS to="g">

To fetch price information for an entity, there are several different fields available within an entity: `priceForSale`, 
`allPricesForSale`, `multiplePricesForSaleAvailable`, `price` and `prices`.
Each has a different purpose and returns different information.

The price object returns various data that can be formatted by the server for you to display to the user. Specifically,
the actual price numbers within the price object can be retrieved formatted according to the specified locale and can even
include the currency symbol. This is controlled by the `formatted` and `withCurrency` arguments on the respective price object fields.
The locale is either resolved from the context of the query
(either from a localized unique attribute in the filter or from the `entityLocaleEquals` constraint in the filter) or can be specified
directly on the parent price field by the `locale` argument.

### Prices for sale

The `priceForSale` field returns a single price object representing the price for sale of the entity.
By default, this price is [computed based on input filter constraints](../filtering/price.md), more specifically:
`priceInPriceLists`, `priceInCurrency` and `priceValidIn`. This is expected to be the most common use case, as
it also filters returned entities by these conditions.

<SourceCodeTabs langSpecificTabOnly>

[Getting entity with price for sale](/documentation/user/en/query/requirements/examples/fetching/priceForSaleField.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with its price for sale based on filter constraints
</NoteTitle>

The query returns the following price for sale of the `Product` entity:

<MDInclude sourceVariable="data.queryProduct.recordPage">[The result of an entity fetched with its price for sale](/documentation/user/en/query/requirements/examples/fetching/priceForSaleField.graphql.json.md)</MDInclude>

As you can see, the price for sale matching the filter constraints is returned.

</Note>

Alternatively, if you don't want to filter entities by the price filter constraints, but you still want to compute and fetch
specific price for sale, you can specify which price for sale you want to compute by using the `priceList`, `currency` and
`validIn`/`validNow` arguments directly on the `priceForSale` field. You can even combine these two approaches,
in which case the arguments on the `priceForSale` fields simply override the corresponding price constraints used in the filter.
Theoretically, you can then filter entities by different price conditions than you use to compute the returned price for sale.

<SourceCodeTabs langSpecificTabOnly>

[Getting entity with price for sale based on custom arguments](/documentation/user/en/query/requirements/examples/fetching/priceForSaleFieldWithArguments.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with its price for sale based on custom arguments
</NoteTitle>

The query returns the following price for sale of the `Product` entity:

<MDInclude sourceVariable="data.queryProduct.recordPage">[The result of an entity fetched with its price for sale](/documentation/user/en/query/requirements/examples/fetching/priceForSaleFieldWithArguments.graphql.json.md)</MDInclude>

As you can see, the price for sale matching the custom arguments is returned.

</Note>

Similarly, you can use `allPricesForSale`, which is almost the same as `priceForSale`, but returns all prices for sale
of the entity grouped by the `innerRecordId`. This usually only makes sense for master products with variants 
(i.e. `LOWEST_PRICE` inner record handling) where the master product has prices for all of its variants, where you may 
want to know (and display) prices for sale for each variant (or some kind of range). For the `NONE` inner record handling,
this will always return at most the actual single price for sale. For the `SUM` inner record handling, this will return prices for sale
for each `innerRecordId` in the same way as for the `FIRST_OCCURNCE`, but the use cases are limited.

The returned list of prices is sorted by the price value from the lowest to the highest depending on the [`priceType`](../requirements/price.md#price-type)
constraint used.

There is also the simpler `multiplePricesForSaleAvailable` field, which returns a boolean indicating whether there are 
multiple _unique_ prices for sale. It is important to note that it doesn't simply return the count of `allPricesForSale`. 
Instead, it uses the [`priceType`](../requirements/price.md#price-type) constraint to determine the uniqueness of each 
price value. This means that even if there are, say, 3 prices for sale, but they all have the same value, this field 
will return `false` (as opposed to `allPricesForSale`, which would return all prices). This is especially useful for the 
UI to determine whether to display a price range or just a single price without having to fetch all prices for sale.

<SourceCodeTabs langSpecificTabOnly>

[Getting entity with all prices for sale](/documentation/user/en/query/requirements/examples/fetching/allPricesForSaleField.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with all of its prices for sale based on filter constraints
</NoteTitle>

The query returns the following prices for sale of the `Product` entity:

<MDInclude sourceVariable="data.queryProduct.recordPage">[The result of an entity fetched with all of its prices for sale](/documentation/user/en/query/requirements/examples/fetching/allPricesForSaleField.graphql.json.md)</MDInclude>

As you can see, the price for sale matching the filter constraints is returned, as well as all other prices for sale and
flag indicating that there are multiple prices for sale available.

</Note>

### Accompanying prices

There may be times when you need not only the concrete [price for sale](#prices-for-sale), but also it's accompanying prices, e.g.
reference price (usually not sellable, but displayed for comparison because it's usually higher than the price for sale). 
This calculation becomes quite complex when we need to handle prices with different inner record handling 
([LOWEST_PRICE, SUM](https://evitadb.io/documentation/query/filtering/price#price-for-sale-selection-in-a-nutshell)).
In such cases, the accompanying prices must correctly reflect the prices for sale for each inner record.

You can easily calculate these prices with the `accompanyingPrice` field within the `priceForSale` or `allPricesForSale` fields.
This way the requested accompanying price will always refer to the parent price for sale (even a custom one). The only 
possible argument is `priceLists`, which defines for which price lists the accompanying price should be calculated 
(the order of the price lists defines a priority in the same way as for the `priceForSale` and `allPricesForSale` fields).
Other parameters will be inherited from the parent price for sale request.

The following query requests calculation of price for sale as well as a reference price for the calculated price for sale:

<SourceCodeTabs langSpecificTabOnly>

[Getting entity with price for sale as well as reference price](/documentation/user/en/query/requirements/examples/fetching/priceForSaleFieldWithReferencePrice.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with its price for sale and reference price
</NoteTitle>

The query returns the following price for sale and reference price of the `Product` entity:

<MDInclude sourceVariable="data.queryProduct.recordPage">[The result of an entity fetched with its price for sale and reference price](/documentation/user/en/query/requirements/examples/fetching/priceForSaleFieldWithReferencePrice.graphql.json.md)</MDInclude>

As you can see, the price for sale as well as custom reference price are returned.

</Note>

The next query is almost identical to the previous one, except that it calculates all the prices for sale and their reference prices for
inner records:

<SourceCodeTabs langSpecificTabOnly>

[Getting entity with all prices for sale as well as reference prices](/documentation/user/en/query/requirements/examples/fetching/allPricesForSaleFieldWithReferencePrices.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with its prices for sale and reference prices
</NoteTitle>

The query returns the following all prices for sale and references price of the `Product` entity:

<MDInclude sourceVariable="data.queryProduct.recordPage">[The result of an entity fetched with its prices for sale and reference prices](/documentation/user/en/query/requirements/examples/fetching/allPricesForSaleFieldWithReferencePrices.graphql.json.md)</MDInclude>

As you can see, the prices for sale as well as custom reference prices are returned.

</Note>

### Prices

The `prices` field returns all prices of the entity. Both sellable and non-sellable.

<SourceCodeTabs langSpecificTabOnly>

[Getting entity with all prices](/documentation/user/en/query/requirements/examples/fetching/pricesField.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with all its prices
</NoteTitle>

The query returns the following list of all prices of the `Product` entity:

<MDInclude sourceVariable="data.queryProduct.recordPage">[The result of an entity fetched with all its prices](/documentation/user/en/query/requirements/examples/fetching/pricesField.graphql.json.md)</MDInclude>

As you can see, the price list is returned.

</Note>

However, if you only need a specific list of prices, you can filter the returned prices with `priceLists` and `currency` arguments.
Unlike the other price fields, this field doesn't fall back to data from filter constraints because that would make it
difficult to return all the prices.

<SourceCodeTabs langSpecificTabOnly>

[Getting entity with filtered all prices](/documentation/user/en/query/requirements/examples/fetching/pricesFieldFiltered.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with filtered all its prices
</NoteTitle>

The query returns the following list of all prices of the `Product` entity:

<MDInclude sourceVariable="data.queryProduct.recordPage">[The result of an entity fetched with all its prices](/documentation/user/en/query/requirements/examples/fetching/pricesFieldFiltered.graphql.json.md)</MDInclude>

As you can see, the filtered price list is returned.

</Note>

</LS>

## Reference content

<LS to="e,j,c,r">

```evitaql-syntax
referenceContent(
    argument:string+,
    filterConstraint:any,
    orderConstraint:any,
    requireConstraint:entityFetch,
    requireConstraint:entityGroupFetch
)
```

<dl>
    <dt>argument:string+</dt>
    <dd>
        mandatory one or more string arguments representing the names of the references to fetch for the entity;
        if more than one name is given in the argument, any corresponding constraints in the same `referenceContent`
        container will apply to all of them
    </dd>
    <dt>filterConstraint:any</dt>
    <dd>
        optional filter constraint that allows you to filter the references to be fetched for the entity;
        the filter constraint is targeted at the reference attributes, so if you want to filter by properties of the referenced
        entity, you must use the [`entityHaving`](../filtering/references.md#entity-having) constraint
    </dd>
    <dt>orderConstraint:any</dt>
    <dd>
        optional ordering constraint that allows you to sort the fetched references; the ordering constraint is targeted
        at the reference attributes, so if you want to order by properties of the referenced entity, you must use the
        [`entityProperty`](../ordering/references.md#entity-property) constraint
    </dd>
    <dt>requireConstraint:entityFetch</dt>
    <dd>
        optional requirement constraint that allows you to fetch the referenced entity body; the `entityFetch`
        constraint can contain nested `referenceContent` with an additional `entityFetch` / `entityGroupFetch`
        constraints that allows you to fetch the entities in a graph-like manner to an "infinite" depth
    </dd>
    <dt>requireConstraint:entityGroupFetch</dt>
    <dd>
        optional requirement constraint that allows you to fetch the referenced entity group body; the `entityGroupFetch`
        constraint can contain nested `referenceContent` with an additional `entityFetch` / `entityGroupFetch`
        constraints that allows you to fetch the entities in a graph-like manner to an "infinite" depth
    </dd>
</dl>

The `referenceContent` (<LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/ReferenceContent.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/ReferenceContent.cs</SourceClass></LS>)
requirement allows you to access the information about the references the entity has towards other entities (either
managed by evitaDB itself or by any other external system). This variant of `referenceContent` doesn't return
the attributes set on the reference itself - if you need those attributes, use the [`referenceContentWithAttributes`](#reference-content-with-attributes)
variant of it.

</LS>
<LS to="g">

Reference fields allow you to access the information about the references the entity has towards other entities (either
managed by evitaDB itself or by any other external system). However, reference fields are a bit different from the other
entity fields. They are dynamically generated based on the reference schemas, so you can access them directly from
the entity object by a schema-defined name, and also, each reference object has a slightly different structure based on
the specific reference schema.

</LS>

To get an entity with reference to categories and brand, use the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Getting entity category and brand references](/documentation/user/en/query/requirements/examples/fetching/referenceContent.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with category and brand references
</NoteTitle>

The returned `Product` entity will contain primary keys of all categories and brand it references:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The result of an entity fetched with category and brand references](/documentation/user/en/query/requirements/examples/fetching/referenceContent.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[The result of an entity fetched with category and brand references](/documentation/user/en/query/requirements/examples/fetching/referenceContent.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The result of an entity fetched with category and brand references](/documentation/user/en/query/requirements/examples/fetching/referenceContent.rest.json.md)</MDInclude>

</LS>

</Note>

#### Referenced entity (group) fetching

In many scenarios, you'll need to fetch not only the primary keys of the referenced entities, but also their bodies and
the bodies of the groups the references refer to. One such common scenario is fetching the parameters of a product:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Getting entity parameter values](/documentation/user/en/query/requirements/examples/fetching/referenceContentBodies.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with referenced parameter bodies and group bodies
</NoteTitle>

The returned `Product` entity will contain a list of all parameter codes it references and the code of the group to
which each parameter belongs:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The result of an entity fetched with referenced parameter bodies and group bodies](/documentation/user/en/query/requirements/examples/fetching/referenceContentBodies.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[The result of an entity fetched with referenced parameter bodies and group bodies](/documentation/user/en/query/requirements/examples/fetching/referenceContentBodies.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The result of an entity fetched with referenced parameter bodies and group bodies](/documentation/user/en/query/requirements/examples/fetching/referenceContentBodies.rest.json.md)</MDInclude>

</LS>

The example lists only a *code* attribute for each referenced entity and group for brevity, but you can retrieve any of
their content - associated data, prices, hierarchies, or nested references as well.

</Note>

To demonstrate graph-like fetching of multiple referenced levels, let's fetch a product with its group assignment and
for each group fetch the group's tags and for each tag fetch the tag's category name. The query contains 4 levels of
related entities: product → group → tag → tag category. The query looks like this:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Getting entity groups and their tags](/documentation/user/en/query/requirements/examples/fetching/referenceContentNested.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with referenced groups, their tags and tag categories
</NoteTitle>

The returned `Product` entity will contain a list of all groups it references, for each group a list of all its tags and
for each tag its category assignment:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The result of an entity fetched with referenced parameter bodies and group bodies](/documentation/user/en/query/requirements/examples/fetching/referenceContentNested.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[The result of an entity fetched with referenced parameter bodies and group bodies](/documentation/user/en/query/requirements/examples/fetching/referenceContentNested.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The result of an entity fetched with referenced parameter bodies and group bodies](/documentation/user/en/query/requirements/examples/fetching/referenceContentNested.rest.json.md)</MDInclude>

</LS>

The tag category is not an entity managed by evitaDB and that's why we retrieve only its primary key.

</Note>

<LS to="g">

#### Reference attributes fetching

Besides fetching the referenced entity bodies, you can also fetch the attributes set on the reference itself. Attributes
of each reference can be fetched using `attributes` field where all possible attributes of particular reference are
available, similarly to entity attributes.

To obtain an entity with reference to a parameter value that reveals which association defines the unique product-variant
combination and which parameter values are merely informative, use the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Getting entity with references and their attributes](/documentation/user/en/query/requirements/examples/fetching/referenceContentWithAttributes.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with parameter references and their attributes
</NoteTitle>

The returned `Product` entity will contain references to parameter values and for each of it, it specifies the type
of the relation between the product and the parameter value:

<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[The result of an entity fetched with all references](/documentation/user/en/query/requirements/examples/fetching/referenceContentWithAttributes.graphql.json.md)</MDInclude>

</LS>

As you can see, the *cellular-true*, *display-size-10-2*, *ram-memory-4*, *rom-memory-256* and *color-yellow* parameter
values define the product variant, while the other parameters only describe the additional properties of the product.

</Note>

</LS>

#### Filtering references

Sometimes your entities have a lot of references and you don't need all of them in certain scenarios. In this case, you
can use the filter constraint to filter out the references you don't need.

<Note type="info">

The <LS to="e,j,r,c">`referenceContent`</LS> filter
<LS to="g">on reference fields</LS>
implicitly targets the attributes on the same reference it points to, so you don't need to
specify a [`referenceHaving`](../filtering/references.md#reference-having) constraint. However, if you need to declare
constraints on referenced entity attributes, you must wrap them in the [`entityHaving`](../filtering/references.md#entity-having)
container constraint.

</Note>

For example, your product has got a lot of parameters, but on product detail page you need to fetch only those that are
part of group which contains an attribute *isVisibleInDetail* set to *TRUE*.To fetch only those parameters, use the
following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Getting entity parameter values visible on detail page](/documentation/user/en/query/requirements/examples/fetching/referenceContentFilter.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with referenced parameter bodies that belong to group visible on detail page
</NoteTitle>

The returned `Product` entity will contain a list of all parameter codes it references and the code of the group to
which each parameter belongs:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The result of an entity fetched with referenced parameter bodies that belong to group visible on detail page](/documentation/user/en/query/requirements/examples/fetching/referenceContentFilter.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[The result of an entity fetched with referenced parameter bodies that belong to group visible on detail page](/documentation/user/en/query/requirements/examples/fetching/referenceContentFilter.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The result of an entity fetched with referenced parameter bodies that belong to group visible on detail page](/documentation/user/en/query/requirements/examples/fetching/referenceContentFilter.rest.json.md)</MDInclude>

</LS>

As you can see only the parameters of the groups having *isVisibleInDetail* set to *TRUE* are returned.

</Note>

#### Ordering references

By default, the references are ordered by the primary key of the referenced entity. If you want to order the references
by a different property - either the attribute set on the reference itself or the property of the referenced entity -
you can use the order constraint inside the `referenceContent` requirement.

<Note type="info">

The <LS to="e,j,r,c">`referenceContent`</LS> ordering
<LS to="g">on reference fields</LS> implicitly targets the attributes on the same reference
it points to, so you don't need to specify a [`referenceProperty`](../ordering/reference.md#reference-property) constraint.
However, if you need to declare
constraints on referenced entity attributes, you must wrap them in the [`entityProperty`](../ordering/reference.md#entity-property)
container constraint.

</Note>

Let's say you want your parameters to be ordered by an English name of the parameter. To do this, use the following
query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Getting entity parameter values ordered by name](/documentation/user/en/query/requirements/examples/fetching/referenceContentOrder.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with referenced parameter ordered by name
</NoteTitle>

The returned `Product` entity will contain a list of all parameters in the expected order:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The result of an entity fetched with referenced parameter ordered by name](/documentation/user/en/query/requirements/examples/fetching/referenceContentOrder.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[The result of an entity fetched with referenced parameter ordered by name](/documentation/user/en/query/requirements/examples/fetching/referenceContentOrder.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The result of an entity fetched with referenced parameter ordered by name](/documentation/user/en/query/requirements/examples/fetching/referenceContentOrder.rest.json.md)</MDInclude>

</LS>

</Note>

<LS to="e,j,c">

### Reference content all

```evitaql-syntax
referenceContentAll(
    filterConstraint:any,
    orderConstraint:any,
    requireConstraint:entityFetch,
    requireConstraint:entityGroupFetch
)
```

<dl>
    <dt>filterConstraint:any</dt>
    <dd>
        optional filter constraint that allows you to filter the references to be fetched for the entity;
        the filter constraint is targeted at the reference attributes, so if you want to filter by properties of the referenced
        entity, you must use the [`entityHaving`](../filtering/references.md#entity-having) constraint
    </dd>
    <dt>orderConstraint:any</dt>
    <dd>
        optional ordering constraint that allows you to sort the fetched references; the ordering constraint is targeted
        at the reference attributes, so if you want to order by properties of the referenced entity, you must use the
        [`entityProperty`](../ordering/references.md#entity-property) constraint
    </dd>
    <dt>requireConstraint:entityFetch</dt>
    <dd>
        optional requirement constraint that allows you to fetch the referenced entity body; the `entityFetch`
        constraint can contain nested `referenceContent` with an additional `entityFetch` / `entityGroupFetch`
        constraints that allows you to fetch the entities in a graph-like manner to an "infinite" depth
    </dd>
    <dt>requireConstraint:entityGroupFetch</dt>
    <dd>
        optional requirement constraint that allows you to fetch the referenced entity group body; the `entityGroupFetch`
        constraint can contain nested `referenceContent` with an additional `entityFetch` / `entityGroupFetch`
        constraints that allows you to fetch the entities in a graph-like manner to an "infinite" depth
    </dd>
</dl>

The `referenceContentAll` (<LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/ReferenceContent.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/ReferenceContent.cs</SourceClass></LS>)
is a variation of the [`referenceContent`](#reference-content) requirement that allows you to access the information
about the references the entity has towards other entities (either managed by evitaDB itself or by any other external
system). The `referenceContentAll` is a shortcut that simply targets all references defined for the entity. It can be
used to quickly discover all the possible references of an entity.

For detail information, see the [`referenceContent`](#reference-content) requirement chapter.

To get an entity with all the references available, use the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Getting entity with all references](/documentation/user/en/query/requirements/examples/fetching/referenceContentAll.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with all references
</NoteTitle>

The returned `Product` entity will contain primary keys and codes of all its references:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The result of an entity fetched with all references](/documentation/user/en/query/requirements/examples/fetching/referenceContentAll.evitaql.json.md)</MDInclude>

</LS>

</Note>

</LS>

<LS to="e,j,c,r">

### Reference content with attributes

```evitaql-syntax
referenceContentWithAttributes(
    argument:string+,
    filterConstraint:any,
    orderConstraint:any,
    requireConstraint:attributeContent,
    requireConstraint:entityFetch,
    requireConstraint:entityGroupFetch
)
```

<dl>
    <dt>argument:string+</dt>
    <dd>
        mandatory one or more string arguments representing the names of the references to fetch for the entity
    </dd>
    <dt>filterConstraint:any</dt>
    <dd>
        optional filter constraint that allows you to filter the references to be fetched for the entity;
        the filter constraint is targeted at the reference attributes, so if you want to filter by properties of the referenced
        entity, you must use the [`entityHaving`](../filtering/references.md#entity-having) constraint
    </dd>
    <dt>orderConstraint:any</dt>
    <dd>
        optional ordering constraint that allows you to sort the fetched references; the ordering constraint is targeted
        at the reference attributes, so if you want to order by properties of the referenced entity, you must use the
        [`entityProperty`](../ordering/references.md#entity-property) constraint
    </dd>
    <dt>requireConstraint:attributeContent</dt>
    <dd>
        optional requirement constraint that allows you to limit the set of reference attributes to be fetched;
        if no `attributeContent` constraint is specified, all attributes of the reference will be fetched
    </dd>
    <dt>requireConstraint:entityFetch</dt>
    <dd>
        optional requirement constraint that allows you to fetch the referenced entity body; the `entityFetch`
        constraint can contain nested `referenceContent` with an additional `entityFetch` / `entityGroupFetch`
        constraints that allows you to fetch the entities in a graph-like manner to an "infinite" depth
    </dd>
    <dt>requireConstraint:entityGroupFetch</dt>
    <dd>
        optional requirement constraint that allows you to fetch the referenced entity group body; the `entityGroupFetch`
        constraint can contain nested `referenceContent` with an additional `entityFetch` / `entityGroupFetch`
        constraints that allows you to fetch the entities in a graph-like manner to an "infinite" depth
    </dd>
</dl>

The `referenceContentWithAttributes` (<LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/ReferenceContent.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/ReferenceContent.cs</SourceClass></LS>)
is a variation of the [`referenceContent`](#reference-content) requirement that allows you to access the information
about the references the entity has towards other entities (either managed by evitaDB itself or by any other external
system) and the attributes set on those references. The `referenceContentWithAttributes` allows you to specify the list
of attributes to fetch, but by default it fetches all attributes on the reference.

For detail information, see the [`referenceContent`](#reference-content) requirement chapter.

To obtain an entity with reference to a parameter value that reveals which association defines the unique product-variant
combination and which parameter values are merely informative, use the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Getting entity with references and their attributes](/documentation/user/en/query/requirements/examples/fetching/referenceContentWithAttributes.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with parameter references and their attributes
</NoteTitle>

The returned `Product` entity will contain references to parameter values and for each of it, it specifies the type
of the relation between the product and the parameter value:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The result of an entity fetched with all references](/documentation/user/en/query/requirements/examples/fetching/referenceContentWithAttributes.evitaql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The result of an entity fetched with all references](/documentation/user/en/query/requirements/examples/fetching/referenceContentWithAttributes.rest.json.md)</MDInclude>

</LS>

As you can see, the *cellular-true*, *display-size-10-2*, *ram-memory-4*, *rom-memory-256* and *color-yellow* parameter
values define the product variant, while the other parameters only describe the additional properties of the product.

</Note>

</LS>

<LS to="e,j,c">

### Reference content all with attributes

```evitaql-syntax
referenceContentAllWithAttributes(
    filterConstraint:any,
    orderConstraint:any,
    requireConstraint:attributeContent,
    requireConstraint:entityFetch,
    requireConstraint:entityGroupFetch
)
```

<dl>
    <dt>filterConstraint:any</dt>
    <dd>
        optional filter constraint that allows you to filter the references to be fetched for the entity;
        the filter constraint is targeted at the reference attributes, so if you want to filter by properties of the referenced
        entity, you must use the [`entityHaving`](../filtering/references.md#entity-having) constraint
    </dd>
    <dt>orderConstraint:any</dt>
    <dd>
        optional ordering constraint that allows you to sort the fetched references; the ordering constraint is targeted
        at the reference attributes, so if you want to order by properties of the referenced entity, you must use the
        [`entityProperty`](../ordering/references.md#entity-property) constraint
    </dd>
    <dt>requireConstraint:attributeContent</dt>
    <dd>
        optional requirement constraint that allows you to limit the set of reference attributes to be fetched;
        if no `attributeContent` constraint is specified, all attributes of the reference will be fetched
    </dd>
    <dt>requireConstraint:entityFetch</dt>
    <dd>
        optional requirement constraint that allows you to fetch the referenced entity body; the `entityFetch`
        constraint can contain nested `referenceContent` with an additional `entityFetch` / `entityGroupFetch`
        constraints that allows you to fetch the entities in a graph-like manner to an "infinite" depth
    </dd>
    <dt>requireConstraint:entityGroupFetch</dt>
    <dd>
        optional requirement constraint that allows you to fetch the referenced entity group body; the `entityGroupFetch`
        constraint can contain nested `referenceContent` with an additional `entityFetch` / `entityGroupFetch`
        constraints that allows you to fetch the entities in a graph-like manner to an "infinite" depth
    </dd>
</dl>

The `referenceContentAllWithAttributes` (<LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/ReferenceContent.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/ReferenceContent.cs</SourceClass></LS>)
is a variation of the [`referenceContent`](#reference-content) requirement that allows you to access the information
about the references the entity has towards other entities (either managed by evitaDB itself or by any other external
system) and the attributes set on those references. The `referenceContentAllWithAttributes` allows you to specify the list
of attributes to fetch, but by default it fetches all attributes on the reference. It doesn't allow you to specify
the reference names - because it targets all of them, and so you can specify the constraints and the attributes that are
shared by all of the references. This constraint is only useful in exploration scenarios.
</LS>

For detail information, see the [`referenceContent`](#reference-content) requirement chapter.

To obtain an entity with all the references and their attributes, use the following query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Getting entity with all of the references and their attributes](/documentation/user/en/query/requirements/examples/fetching/referenceContentAllWithAttributes.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### The result of an entity fetched with all the references and their attributes
</NoteTitle>

The returned `Product` entity will contain all the references and the attributes set on this relation:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[The result of an entity fetched with all references](/documentation/user/en/query/requirements/examples/fetching/referenceContentAllWithAttributes.evitaql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[The result of an entity fetched with all references](/documentation/user/en/query/requirements/examples/fetching/referenceContentAllWithAttributes.rest.json.md)</MDInclude>

</LS>

</Note>
