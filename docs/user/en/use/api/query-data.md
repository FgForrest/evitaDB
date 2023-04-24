---
title: Query data
perex: |
    This article contains the main principles for querying data in evitaDB, the description of the data API regarding
    entity fetching and related recommendations.
date: '17.1.2023'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---

The query in evitaDB is represented by a tree of nested "constraints" divided into for parts:

<dl>
    <dt>`collection`</dt>
    <dd>it identifies the collection to query</dd>
    <dt>`filterBy`</dt>
    <dd>it limits the number of results returned</dd>
    <dt>`orderBy`</dt>
    <dd>it specifies the order in which the results are returned</dd>
    <dt>`require`</dt>
    <dd>it allows you to pass additional information about how much data the returned entities should have, 
    (how complete the returned entity should be) how many of them are needed, and what other calculations should be 
    performed on them</dd>
</dl>

The *evitaQL* (evitaDB Query Language) entry point is represented by 
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/Query.java</SourceClass> class, and looks like this 
a [Lisp flavored language](https://en.wikipedia.org/wiki/Lisp_(programming_language)). It always starts with 
the name of the function, followed by a set of arguments in parentheses. You can even use other functions 
in those arguments. An example of such a query might look like this:

<SourceCodeTabs>
[EvitaQL example](docs/user/en/use/api/example/evita-query-example.java)
</SourceCodeTabs>

> *The query will return the first page of 20 products in the category "local food" and its subcategories that have* 
> *Czech localization and a valid price in one of the price lists "VIP", "loyal customer" or "regular prices" in the* 
> *currency CZK. It also filters only products with a selling price between 600 and 1,600 CZK including VAT and with*  
> *the parameters "gluten-free" and "original recipe".*

> *The so-called price histogram will also be calculated for all matching products with a maximum of 30 columns so* 
> *that they can be displayed on the dedicated space. In addition, a summary of parametric filters (facets )will be* 
> *calculated with an impact analysis of how the result would look if the user selected some other parameters in* 
> *addition to the two selected ones.*

evitaQL is represented by a simple
[String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html) which is parsed to
an abstract syntax tree consisting of constraints 
(<SourceClass>evita_query/src/main/java/io/evitadb/api/query/Constraint.java</SourceClass>) encapsulated in 
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/Query.java</SourceClass> object.

We have designed the *evitaQL* string representation to look similar to a query defined directly in the *Java* language. 
We also try to preserve the "look & feel" of the original evitaQL in different languages / APIs like REST, GraphQL or C#
while respecting the conventions and capabilities of the respective language.

evitaQL is used in the gRPC protocol and can optionally be used for the embedded Java environment. It can also be used 
in evitaDB console (once it's implemented). The GraphQL and REST Web API use a similar format, but adapted to 
the protocol conventions (so that we can take advantage of the Open API / GQL schema).

<LanguageSpecific to="java">

## Defining queries in Java code

In order to create a query, use the static `query` methods in the
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/Query.java</SourceClass> class to create a query and then
compose inner constraints from the static methods in
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/QueryConstraints.java</SourceClass>.

When this class is imported statically, the Java query definition looks like the string form of the query.
Thanks to type inference, the IDE will help you with auto-completion of the constraints that make sense in a particular
context.

This is an example of how the query is composed and how evitaDB is called. The example imports two classes statically:
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/Query.java</SourceClass> and
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/QueryConstraints.java</SourceClass>.

<SourceCodeTabs>
[Java query example](docs/user/en/use/api/example/java-query-example.java)
</SourceCodeTabs>

### Automatic query cleaning

The query may also contain "dirty" parts - that is, null constraints and unnecessary parts:

<SourceCodeTabs>
[Java dirty query example](docs/user/en/use/api/example/java-dirty-query-example.java)
</SourceCodeTabs>

The query is automatically cleaned and unnecessary constraints are removed before it is processed by the evitaDB engine.

### Query parsing

The <SourceClass>evita_query/src/main/java/io/evitadb/api/query/QueryParser.java</SourceClass> class allows to parse
[String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html) query to the AST
form of the <SourceClass>evita_query/src/main/java/io/evitadb/api/query/Query.java</SourceClass> class.
The string notation of *evitaQL* can be created at any time by calling the `toString()` method on the
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/Query.java</SourceClass> object.

The parser supports passing values by reference, copying the proven approach of a JDBC
[prepared statement](https://docs.oracle.com/javase/tutorial/jdbc/basics/prepared.html)
which allows the use of the `?` character in the query and returns an array of correctly sorted input parameters.

It also supports so-called 
[named queries](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/core/namedparam/NamedParameterJdbcTemplate.html),
which are widely used in the [Spring framework](https://spring.io/projects/spring-data-jdbc), using variables in the
query with the format `:name` and providing a [map](https://docs.oracle.com/javase/8/docs/api/java/util/Map.html) with
the named input parameters.

In the opposite direction, it offers the `toStringWithParameterExtraction` method on the
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/Query.java</SourceClass> class object, which allows to
create the string format for *evitaQL* in the form of a *prepared statement* and extract all parameters in a separate
array.

### Query manipulation

There are several handy visitors (more will be added) that allow you to work with the query. They are placed in the package
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/visitor/</SourceClass>, and some have shortcut methods 
in the <SourceClass>evita_query/src/main/java/io/evitadb/api/query/QueryUtils.java</SourceClass> class.

The query can be "pretty-printed" by using the `prettyPrint` method on the
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/Query.java</SourceClass> class.

### Data fetching

By default, only primary keys of entities are returned in the query result. In this simplest case, each entity is 
represented by the 
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/EntityReferenceContract.java</SourceClass> 
interface.

<SourceCodeTabs>
[Default query example](docs/user/en/use/api/example/default-query-example.java)
</SourceCodeTabs>

The client application can request returning entity bodies instead, but this must be explicitly requested using 
a specific require constraint (or their combination):

- [entity fetch](../../query/requirements/fetching.md)
- [attribute fetch](../../query/requirements/fetching.md#attributes)
- [associated data fetch](../../query/requirements/fetching.md#associated-data)
- [price fetch](../../query/requirements/fetching.md#prices)
- [reference fetch](../../query/requirements/fetching.md#references)

When such a require constraint is used, data will be fetched *greedily* during the initial request. The response object 
will then contain entities in the form of 
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/SealedEntity.java</SourceClass>.

<SourceCodeTabs>
[Fetching example](docs/user/en/use/api/example/fetching-example.java)
</SourceCodeTabs>

#### Lazy fetching (enrichment)

Attributes, associated data, prices and references can be fetched separately by providing the primary key of the entity.
The initial entity loaded by [entity fetch](../../query/requirements/fetching.md) with a limited set of requirements 
can be enriched later with missing data.

To enrich, a.k.a. lazy fetch missing data to an existing entity, you must pass the existing entity to an `enrichEntity` 
method and specify a set of additional require constraints that should be satisfied. Due to immutability properties 
enforced by database design, enriching an entity object returns a new instance of the entity.

<SourceCodeTabs>
[Lazy loading example](docs/user/en/use/api/example/lazy-fetch-example.java)
</SourceCodeTabs>

Lazy fetching may not be necessary for a frontend designed using an MVC architecture, where all requirements for the 
page are known before rendering. But different architectures might fetch thinner entity forms and later discover that 
they need more data in them. While this approach is not optimal performance-wise, it may make life easier for 
developers, and it's much more optimal to just enrich the existing entity (using lookup by primary key and fetching 
only missing data) instead of fetching the entire entity again.

<Note type="warning">
Lazy Fetching is currently only fully implemented for embedded evitaDB. If you are using evitaDB remotely via 
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass>
you can still use the `enrichEntity` method on the 
<SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass> interface, but the entity
will be fully fetched again. However, we plan to optimize this scenario in the future.
</Note>

### Caching considerations

If you're using embedded evitaDB and [don't disable the feature](../../operate/configure.md#cache-configuration), 
the evitaDB engine automatically caches intermediate calculation results and frequently used entity bodies up to the 
defined memory limit. Details about caching are [described here](../../deep-dive/cache.md). For embedded environments
the implementation of an own cache on top of the evitaDB cache is not recommended.

If you are using 
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass>, 
implementing the local cache may save you network costs and give you better latency. The problem is related to cache 
invalidation. You'd have to query only the entity references that contain version information and fetch the entities 
that are not in the cache with a separate request. So instead of one network request, you have to make two. The benefit 
of the local cache is therefore somewhat questionable.

</LanguageSpecific>