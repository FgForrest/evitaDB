---
title: Create first database
perex: |
    This article will guide you through the basics of the evitaDB API for creating, updating, querying and 
    deleting entities in the catalog.  
date: '17.1.2023'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---

We assume you already have the following snippet of the code from the [previous chapter](run-evitadb.md):

<SourceCodeTabs>
[Example of starting the evitaDB server](/docs/user/en/get-started/example/complete-startup.java)
</SourceCodeTabs>

So the web API server is up and running and ready to communicate.

## Define a new catalog with a schema

<LanguageSpecific to="java">
Now you can use <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass> to define a new 
catalog and create predefined schemas for multiple collections: `brand`, `category` and `product`. Each collection 
contains some attributes (either localized or non-localized), category is marked as a hierarchical entity that forms 
a tree, product is enabled to have prices:

<SourceCodeTabs requires="/docs/user/en/get-started/example/complete-startup.java">
[Example of defining catalog and schema for entity collections](/docs/user/en/get-started/example/define-catalog-with-schema.java)
</SourceCodeTabs>

## Open session to catalog and insert your first entity

When the catalog is created and schema known, you might insert a first entity to it:

<SourceCodeTabs requires="/docs/user/en/get-started/example/complete-startup.java,/docs/user/en/get-started/example/define-test-catalog.java">
[Example of inserting an entity](/docs/user/en/get-started/example/create-first-entity.java)
</SourceCodeTabs>

The session is opened implicitly for the scope of the `updateCatalog` method. The analogous method `queryCatalog` on 
the evitaDB contract also opens a session, but only in read-only mode, which doesn't allow updating the catalog. 
Differentiating between read-write and read-only sessions allows evitaDB to optimize query processing and distribute 
the load in the cluster.

Let's see how you can retrieve the entity you just created in another read-only session.

<SourceCodeTabs requires="/docs/user/en/get-started/example/create-first-entity.java">
[Example of reading an entity by primary key](/docs/user/en/get-started/example/read-entity-by-pk.java)
</SourceCodeTabs>

## Create a small dataset

Once you learn the basics, you can create a small dataset to work with:

<SourceCodeTabs requires="/docs/user/en/get-started/example/complete-startup.java,/docs/user/en/get-started/example/define-test-catalog.java">
[Example of creating a small dataset](/docs/user/en/get-started/example/create-small-dataset.java)
</SourceCodeTabs>

That's a lot of code, but in reality you'd probably write a transformation function from the primary model you already
have in the relational database. The example shows how to define attributes, associated data, references, and prices.

## List existing entities

To get a better idea of the data, let's list the existing entities from the database.

<SourceCodeTabs requires="/docs/user/en/get-started/example/create-small-dataset.java">
[Example of listing entities](/docs/user/en/get-started/example/list-entities.java)
</SourceCodeTabs>

You can also filter and sort the data:

<SourceCodeTabs requires="/docs/user/en/get-started/example/create-small-dataset.java">
[Example of filtering and ordering entities](/docs/user/en/get-started/example/filter-order-entities.java)
</SourceCodeTabs>

Or you can filter all products by price in EUR greater than 300€ and order by price with the cheapest products first:

<SourceCodeTabs requires="/docs/user/en/get-started/example/create-small-dataset.java">
[Example of filtering and ordering products by price](/docs/user/en/get-started/example/filter-order-products-by-price.java)
</SourceCodeTabs>

## Update any of existing entities

Updating an entity is similar to creating a new entity:

<SourceCodeTabs requires="/docs/user/en/get-started/example/create-small-dataset.java">
[Example of listing entities](/docs/user/en/get-started/example/update-entity.java)
</SourceCodeTabs>

The main difference is that you first fetch the entity with all the data you want to update from the evitaDB server and
apply changes to it. The fetched entity is immutable, so you need to open it for writing first. This action creates a
builder that wraps the original immutable object and allows the changes to be captured. These changes are eventually
collected and passed to the server in the `upsertVia` method.

For more information, see the [write API description](../use/api/write-data.md#upsert). 

## Delete any of existing entities

You can delete entity by is primary key:

<SourceCodeTabs requires="/docs/user/en/get-started/example/create-small-dataset.java">
[Example of deleting entity by PK](/docs/user/en/get-started/example/delete-entity-by-pk.java)
</SourceCodeTabs>

Or, you can issue a query that removes all the entities that match the query:

<SourceCodeTabs requires="/docs/user/en/get-started/example/create-small-dataset.java">
[Example of deleting entity by query](/docs/user/en/get-started/example/delete-entity-by-query.java)
</SourceCodeTabs>

When you delete a hierarchical entity, you can choose whether or not to delete it with all of its child entities:

<SourceCodeTabs requires="/docs/user/en/get-started/example/create-small-dataset.java">
[Example of deleting hierarchical entity](/docs/user/en/get-started/example/delete-hierarchical-entity.java)
</SourceCodeTabs>

For more complex examples and explanations, see the [write API chapter](../use/api/write-data.md#removal).

</LanguageSpecific>
<LanguageSpecific to="evitaql,graphql,rest,csharp">

Creating new catalog in other APIs than Java is being prepared.

</LanguageSpecific>

## What's next?

If you don't want to fiddle with your own data, you [can play with our dataset](query-our-dataset.md).
You can also go into detail in the following chapters on how to use specific parts of
our [query API](../use/api/query-data.md), [write API](../use/api/write-data.md), or [schema API](../use/schema.md).
You can also familiarize yourself with the [entity Data Structure](../use/data-model.md) or other aspects of our database.