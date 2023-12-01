---
title: Create first database
perex: |
    This article will guide you through the basics of the evitaDB API for creating, updating, querying and 
    deleting entities in the catalog.  
date: '17.1.2023'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---

<LanguageSpecific to="java">

We assume you already have the following snippet of the code from the [previous chapter](run-evitadb.md):

<SourceCodeTabs local>
    
[Example of starting the evitaDB server](/documentation/user/en/get-started/example/complete-startup.java)
</SourceCodeTabs>

So the evitaDB instance is now up and running and ready to communicate.

</LanguageSpecific>

<LanguageSpecific to="graphql,rest">

We assume that you already have the following Docker image up and running from the [previous chapter](run-evitadb.md):

```shell
# Linux variant: run on foreground, destroy container after exit, use host ports without NAT
docker run --name evitadb -i --rm --net=host \ 
index.docker.io/evitadb/evitadb:latest

# Windows / MacOS: there is open issue https://github.com/docker/roadmap/issues/238 
# and you need to open ports manually
docker run --name evitadb -i --rm -p 5555:5555 -p 5556:5556 -p 5557:5557 \
       -e "api.exposedOn=localhost" \ 
       index.docker.io/evitadb/evitadb:latest
```

So the web API server is now up and running and ready to communicate.

</LanguageSpecific>

<LanguageSpecific to="java">

## Define a new catalog with a schema

Now you can use <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass> to define a new 
catalog and create predefined schemas for multiple collections: `Brand`, `Category` and `Product`. Each collection 
contains some attributes (either localized or non-localized), category is marked as a hierarchical entity that forms 
a tree, product is enabled to have prices:

<SourceCodeTabs requires="/documentation/user/en/get-started/example/complete-startup.java" local>

[Example of defining catalog and schema for entity collections](/documentation/user/en/get-started/example/define-catalog-with-schema.java)
</SourceCodeTabs>

</LanguageSpecific>
<LanguageSpecific to="graphql">

## Define a new catalog with a schema

Now you can use the [system API](/documentation/user/en/use/connectors/graphql.md#graphql-api-instances) via the URL
`https://your-server:5555/gql/system` to create a new empty catalog:

<SourceCodeTabs requires="ignoreTest" langSpecificTabOnly local>

[Example of creating empty catalog](/documentation/user/en/get-started/example/define-catalog.graphql)
</SourceCodeTabs>

and fill it with new predefined schemas for multiple collections: `Brand`, `Category` and `Product` by
modifying its schema via the [catalog schema API](/documentation/user/en/use/connectors/graphql.md#graphql-api-instances) at URL
`https://your-server:5555/gql/test-catalog/schema`. Each collection
contains some attributes (either localized or non-localized), category is marked as a hierarchical entity that forms
a tree, product is enabled to have prices:

<SourceCodeTabs requires="ignoreTest" langSpecificTabOnly local>

[Example of creating empty catalog](/documentation/user/en/get-started/example/define-schema-for-catalog.graphql)
</SourceCodeTabs>

</LanguageSpecific>

<LanguageSpecific to="rest">

## Define a new catalog with a schema

Now you can use the [system API](/documentation/user/en/use/connectors/rest.md#rest-api-instances) via the URL
`https://your-server:5555/rest/system/catalogs` to create a new empty catalog:

<SourceCodeTabs requires="ignoreTest" langSpecificTabOnly local>

[Example of creating empty catalog](/documentation/user/en/get-started/example/define-catalog.rest)
</SourceCodeTabs>

and fill it with new predefined schemas for multiple collections: `Brand`, `Category` and `Product` by
modifying its schema via the [catalog schema API](/documentation/user/en/use/connectors/rest.md#rest-api-instances) at URL
`https://your-server:5555/rest/test-catalog/schema`. Each collection
contains some attributes (either localized or non-localized), category is marked as a hierarchical entity that forms
a tree, product is enabled to have prices:

<SourceCodeTabs requires="ignoreTest" langSpecificTabOnly local>

[Example of creating empty catalog](/documentation/user/en/get-started/example/define-schema-for-catalog.rest)
</SourceCodeTabs>

</LanguageSpecific>

<LanguageSpecific to="java">

## Open session to catalog and insert your first entity

Once the catalog is created and the schema is known, you can insert a first entity to the catalog:

<SourceCodeTabs requires="ignoreTest,/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Example of inserting an entity](/documentation/user/en/get-started/example/create-first-entity.java)
</SourceCodeTabs>

The session is implicitly opened for the scope of the `updateCatalog` method. The analogous method `queryCatalog` on 
the evitaDB contract also opens a session, but only in read-only mode, which doesn't allow updating the catalog. 
Differentiating between read-write and read-only sessions allows evitaDB to optimize query processing and distribute 
the load in the cluster.

Let's see how you can retrieve the entity you just created in another read-only session.

<SourceCodeTabs requires="ignoreTest,/documentation/user/en/get-started/example/create-first-entity.java" langSpecificTabOnly local>

[Example of reading an entity by primary key](/documentation/user/en/get-started/example/read-entity-by-pk.java)
</SourceCodeTabs>

</LanguageSpecific>
<LanguageSpecific to="graphql">

## Open session to catalog and insert your first entity

Once the catalog is created and the schema is known, you can insert a first entity to the catalog via the
[catalog data API](/documentation/user/en/use/connectors/graphql.md#graphql-api-instances) at the
`https://your-server:5555/gql/test-catalog` URL:

<SourceCodeTabs requires="ignoreTest" langSpecificTabOnly local>

[Example of inserting an entity](/documentation/user/en/get-started/example/create-first-entity.graphql)
</SourceCodeTabs>

The session is implicitly opened for the scope of a single GraphQL request and is also automatically closed when the
request is processed. Depending on the body of the request, evitaDB either creates
a read-only session (for queries) or a read-write session (for mutations).
Differentiating between read-write and read-only sessions allows evitaDB to optimize query processing and distribute
the load in the cluster.

Let's see how you can retrieve the entity you just created in another read-only session via the same catalog data API
as mentioned above.

<SourceCodeTabs requires="ignoreTest" langSpecificTabOnly local>

[Example of reading an entity by primary key](/documentation/user/en/get-started/example/read-entity-by-pk.graphql)
</SourceCodeTabs>

</LanguageSpecific>
<LanguageSpecific to="rest">

## Open session to catalog and insert your first entity

Once the catalog is created and the schema is known, you can insert a first entity to the catalog via the
[catalog data API](/documentation/user/en/use/connectors/rest.md#rest-api-instances) at the URL
`https://your-server:5555/rest/test-catalog/brand`:

<SourceCodeTabs requires="ignoreTest" langSpecificTabOnly local>

[Example of inserting an entity](/documentation/user/en/get-started/example/create-first-entity.rest)
</SourceCodeTabs>

The session is implicitly opened for the scope of a single REST request and is also automatically closed when the
request is processed. Depending on the type of the request, evitaDB either creates
a read-only session (for queries) or a read-write session (for mutations).
Differentiating between read-write and read-only sessions allows evitaDB to optimize query processing and distribute
the load in the cluster.

Let's see how you can retrieve the entity you just created in another read-only session via the same catalog data API
as mentioned above.

<SourceCodeTabs requires="ignoreTest" langSpecificTabOnly>

[Example of reading an entity by primary key](/documentation/user/en/get-started/example/read-entity-by-pk.rest)
</SourceCodeTabs>

</LanguageSpecific>

<LanguageSpecific to="java,graphql,rest">

## Create a small dataset

Once you learn the basics, you can create a small dataset to work with:

<SourceCodeTabs requires="ignoreTest,/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly>

[Example of creating a small dataset](/documentation/user/en/get-started/example/create-small-dataset.java)
</SourceCodeTabs>

That's a lot of code, but in reality you'd probably write a transformation function from the primary model you already
have in the relational database. The example shows how to define attributes, associated data, references, and prices.

</LanguageSpecific>

<LanguageSpecific to="java,graphql,rest">

## List existing entities

To get a better idea of the data, let's list the existing entities from the database.

<SourceCodeTabs requires="ignoreTest,/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly>

[Example of listing entities](/documentation/user/en/get-started/example/list-entities.java)
</SourceCodeTabs>

You can also filter and sort the data:

<SourceCodeTabs requires="ignoreTest,/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly>

[Example of filtering and ordering entities](/documentation/user/en/get-started/example/filter-order-entities.java)
</SourceCodeTabs>

Or you can filter all products by price in EUR greater than €300 and order by price with the cheapest products first:

<SourceCodeTabs requires="ignoreTest,/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly>

[Example of filtering and ordering products by price](/documentation/user/en/get-started/example/filter-order-products-by-price.java)
</SourceCodeTabs>

</LanguageSpecific>

<LanguageSpecific to="java">

## Update any of existing entities

Updating an entity is similar to creating a new entity:

<SourceCodeTabs requires="ignoreTest,/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly>

[Example of listing entities](/documentation/user/en/get-started/example/update-entity.java)
</SourceCodeTabs>

The main difference is that you first fetch the entity with all the data you want to update from the evitaDB server and
apply changes to it. The fetched entity is immutable, so you need to open it for writing first. This action creates a
builder that wraps the original immutable object and allows the changes to be captured. These changes are eventually
collected and passed to the server in the `upsertVia` method.

For more information, see the [write API description](../use/api/write-data.md#upsert). 

</LanguageSpecific>
<LanguageSpecific to="graphql">

## Update any of existing entities

Updating an entity is similar to creating a new entity:

<SourceCodeTabs requires="ignoreTest" langSpecificTabOnly>

[Example of listing entities](/documentation/user/en/get-started/example/update-entity.graphql)
</SourceCodeTabs>

The main difference is that you must pass a primary key of an existing entity to modify and specify only those mutations
that mutate already existing data.

For more information, see the [write API description](../use/api/write-data.md#upsert).

</LanguageSpecific>
<LanguageSpecific to="rest">

## Update any of existing entities

Updating an entity is similar to creating a new entity:

<SourceCodeTabs requires="ignoreTest" langSpecificTabOnly>

[Example of listing entities](/documentation/user/en/get-started/example/update-entity.rest)
</SourceCodeTabs>

The main difference is that you must pass a primary key of an existing entity to modify and specify only those mutations
that mutate already existing data.

For more information, see the [write API description](../use/api/write-data.md#upsert).

</LanguageSpecific>

<LanguageSpecific to="java">

## Delete any of existing entities

You can delete entity by is primary key:

<SourceCodeTabs requires="ignoreTest,/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly>

[Example of deleting entity by PK](/documentation/user/en/get-started/example/delete-entity-by-pk.java)
</SourceCodeTabs>

Or, you can issue a query that removes all the entities that match the query:

<SourceCodeTabs requires="ignoreTest,/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly>

[Example of deleting entity by query](/documentation/user/en/get-started/example/delete-entity-by-query.java)
</SourceCodeTabs>

When you delete a hierarchical entity, you can choose whether or not to delete it with all of its child entities:

<SourceCodeTabs requires="ignoreTest,/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/create-small-dataset.java">

[Example of deleting hierarchical entity](/documentation/user/en/get-started/example/delete-hierarchical-entity.java)
</SourceCodeTabs>

For more complex examples and explanations, see the [write API chapter](../use/api/write-data.md#removal).

</LanguageSpecific>
<LanguageSpecific to="graphql">

## Delete any of existing entities

You can issue a query that removes all the entities that match the query using the same catalog data API that you
would use to insert, update or retrieve entities:

<SourceCodeTabs requires="ignoreTest" langSpecificTabOnly>

[Example of deleting entity by query](/documentation/user/en/get-started/example/delete-entity-by-query.graphql)
</SourceCodeTabs>

For more complex examples and explanations, see the [write API chapter](../use/api/write-data.md#removal).

</LanguageSpecific>
<LanguageSpecific to="rest">

## Delete any of existing entities

You can delete entity by is primary key:

<SourceCodeTabs requires="ignoreTest" langSpecificTabOnly>

[Example of deleting entity by PK](/documentation/user/en/get-started/example/delete-entity-by-pk.rest)
</SourceCodeTabs>

Or, you can issue a query that removes all the entities that match the query using the same catalog data API that you
would use to insert, update or retrieve entities:

<SourceCodeTabs requires="ignoreTest" langSpecificTabOnly>

[Example of deleting entity by query](/documentation/user/en/get-started/example/delete-entity-by-query.rest)
</SourceCodeTabs>

For more complex examples and explanations, see the [write API chapter](../use/api/write-data.md#removal).

</LanguageSpecific>

<LanguageSpecific to="evitaql,csharp">

Creating new catalog in other APIs than Java, GraphQL and REST is being prepared.

</LanguageSpecific>

## What's next?

If you don't want to fiddle with your own data, you [can play with our dataset](query-our-dataset.md).
You can also go into detail in the following chapters on how to use specific parts of
our [query API](../use/api/query-data.md), [write API](../use/api/write-data.md), or [schema API](../use/schema.md).
You can also familiarize yourself with the [entity Data Structure](../use/data-model.md) or other aspects of our database.
