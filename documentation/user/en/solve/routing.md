---
title: Routing
perex: |
  Routing in e-commerce catalogs is a complex issue. It plays a critical role in SEO and user experience, and URLs are 
  typically derived from entity names without any additional information of meaningful structure. As much as we as 
  developers don't like it, the business dictates the rules and we have to follow them. In this article, we will look
  at some approaches to solving routing problems in e-commerce catalogs.
date: '4.2.2024'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---

We expect entities that can be reached via URL to have a [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html) 
attribute that contains either the full absolute URL or the relative one. The decision to use absolute or relative URLs 
has consequences and should be thought through. Absolute URLs stored in the database aren't usually a good idea, as this 
makes it difficult to use the same record in different environments (production/testing/dev) that have different
domains. We generally recommend storing relative URLs without protocol and domain parts in the database and constructing 
full absolute URLs in the application.

## URL uniqueness

According to the [URL](https://en.wikipedia.org/wiki/URL) standard, the URL is a unique identifier of a resource. So it 
should be marked as unique in the database. Since we are dealing with multiple entities, we probably need to define 
a catalog-wide URL attribute to be used by each entity. Then the attribute needs to be marked as either 
`UNIQUE_WITHIN_CATALOG` or `UNIQUE_WITHIN_CATALOG_LOCALE`, depending on whether the catalog is multilingual or not, and 
whether the locale is part of the URL or not. In our practice we've encountered both of these scenarios:

1. the locale is encoded in the relative part of the URL, e.g. `/en/product-name` or `/cs/product-name`
2. the locale is encoded in the domain part of the URL, e.g. `https://example.com/product-name` or `https://example.cz/product-name`

In the first scenario, we can use the `UNIQUE_WITHIN_CATALOG` uniqueness type, because the relative URLs are unique 
across all locales. In the second scenario, we must use the `UNIQUE_WITHIN_CATALOG_LOCALE` uniqueness type, because we
may have the same relative URL for different locales, but when booking for the target entity by URL, we must also 
specify the locale derived from the domain name.

If the attribute is marked as unique, we can simply search for the owner entity by the attribute value. The following 
query will return the entity by the code, which is a simple unique attribute of the entity (not catalog-wide):

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Retrieve product by unique attribute](documentation/user/en/solve/examples/routing/get-by-unique-attribute.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Result of the query for the entity by the globally unique attribute
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Result for unique attribute](documentation/user/en/solve/examples/routing/get-by-unique-attribute.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Result for unique attribute](documentation/user/en/solve/examples/routing/get-by-unique-attribute.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Result for unique attribute](documentation/user/en/solve/examples/routing/get-by-unique-attribute.rest.json.md)</MDInclude>

</LS>

</Note>

As you can see, we need to specify the collection name to get the entity through the code. Since the URL is unique 
across the catalog we can search for the entity without specifying the collection name:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" ignoreTest>

[Retrieve product by globaly unique attribute](documentation/user/en/solve/examples/routing/get-by-globally-unique-attribute.evitaql)
</SourceCodeTabs>

<Note type="warning">

This query will fail on the evitaDB demo dataset because the URL is marked as `UNIQUE_WITHIN_CATALOG_LOCALE` which 
requires the locale to be specified when searching for the entity via the URL. But this query is correct if your dataset
uses the `UNIQUE_WITHIN_CATALOG` instead. See the next example.

</Note>

If the URL is unique only within the locale, we need to specify the `entityLocaleEquals` constraint as well:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Retrieve product by globaly unique locale specific attribute](documentation/user/en/solve/examples/routing/get-by-globally-unique-locale-specific-attribute.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Result of the query for the entity by the globally unique locale specific attribute
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Result for localy specific globally unique attribute](documentation/user/en/solve/examples/routing/get-by-globally-unique-locale-specific-attribute.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Result for localy specific globally unique attribute](documentation/user/en/solve/examples/routing/get-by-globally-unique-locale-specific-attribute.evitaql.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Result for localy specific globally unique attribute](documentation/user/en/solve/examples/routing/get-by-globally-unique-locale-specific-attribute.evitaql.rest.json.md)</MDInclude>

</LS>

</Note>

## Fetching unknown entities by URL

Another problem is related to the fact that we don't know the entity type before we fetch it from the database and so we
don't know what data (attributes/associated data, etc.) to fetch. We have different possibilities in different evitaDB 
protocols.

<LS to="e,j,c">

In plain evitaQL you can either use "wildcard" definition to fetch all available data or you can specify exactly 
the data (attributes / associated data etc.) you want to fetch. When querying for the entity by the globally unique 
attribute, the query parser doesn't validate the existence of the attribute in the schema and returns only the data 
it finds for a particular entity.

<Note type="warning">

You can even specify an attribute that doesn't exist anywhere and evitaDB won't complain. So be careful, because 
the safety net of the query validator is not available for this kind of query.

</Note>

To demonstrate the behavior of such a query, let's define a query that makes no sense for either the `Product` 
or `Category` collection, but runs successfully for the globally unique attribute query:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Retrieve product with data by globaly unique locale specific attribute](documentation/user/en/solve/examples/routing/get-product-with-data.evitaql)
</SourceCodeTabs>

Note that the query contains a reference to the `level` attribute, which is certainly not defined in the `Product`
entity schema. This query would fail if we specified the collection name, but since we don't, the name is accepted and 
just not returned in the result. The same goes for the `hierarchyContent` requirement, which doesn't make sense for the 
`Product` entity because it's not hierarchical. Look at the result of the query:

<Note type="info">

<NoteTitle toggles="true">

##### Result of the query for the product by the globally unique attribute with data fetch
</NoteTitle>

<MDInclude>[Result of the query for the product by the globally unique attribute with data fetch](documentation/user/en/solve/examples/routing/get-product-with-data.evitaql.md)</MDInclude>

</Note>

The response contains all the data that matches the schema of the `Product` entity, and the rest is simply ignored.
Now let's look at the same query, but for the URL of the `Category` entity:

<SourceCodeTabs requires="evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Retrieve category with data by globaly unique locale specific attribute](documentation/user/en/solve/examples/routing/get-category-with-data.evitaql)
</SourceCodeTabs>

You can see that in the result there is information about the `level` attribute and the `parent` information that 
doesn't make sense for the `product` entity, but does for the `category` entity:

<Note type="info">

<NoteTitle toggles="true">

##### Result of the query for the product by the globally unique attribute with data fetch
</NoteTitle>

<MDInclude>[Result of the query for the product by the globally unique attribute with data fetch](documentation/user/en/solve/examples/routing/get-category-with-data.evitaql.md)</MDInclude>

</Note>

evitaDB concepts try to minimize the number of client-server round trips, but in this case the possibilities are limited
and you would probably need another query to get detailed data if you know the entity type.

</LS>

<LS to="g">

**Work in progress**

</LS>

<LS to="r">

**Work in progress**

</LS>