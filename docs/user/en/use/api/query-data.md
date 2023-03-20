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
    how many of them are needed, and what other calculations should be performed on them</dd>
</dl>

The *evitaQL* (evitaDB Query Language) entry point is represented by 
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/Query.java</SourceClass> class, and looks like this 
a [Lisp flavored language](https://en.wikipedia.org/wiki/Lisp_(programming_language)). It always starts with 
the name of the function, followed by a set of arguments in parentheses. You can even use other functions 
in those arguments. An example of such a query might look like this:

<SourceCodeTabs>
[EvitaQL example](docs/user/en/use/api/example/evita-query-example.java)
</SourceCodeTabs>

> The query will return the first page of 20 products in the category "local food" and its subcategories that have 
> Czech localization and a valid price in one of the price lists "VIP", "loyal customer" or "regular prices" in the 
> currency CZK. It also filters only products with a selling price between 600 and 1,600 CZK including VAT and with the 
> parameters "gluten-free" and "original recipe".
> The so-called price histogram will also be calculated for all matching products with a maximum of 30 columns so that 
> they can be displayed on the dedicated space. In addition, a summary of parametric filters (facets )will be 
> calculated with an impact analysis of how the result would look if the user selected some other parameters in addition 
> to the two selected ones.

evitaQL is represented by a simple
[String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html) which is parsed to
an abstract syntax tree consisting of constraints 
(<SourceClass>evita_query/src/main/java/io/evitadb/api/query/Constraint.java</SourceClass>) encapsulated in 
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/Query.java</SourceClass> object.

We have designed the *evitaQL* string representation to look similar to a query defined directly in the *Java* language. 
We also try to preserve the "look & feel" of the original evitaQL in different languages / APIs like REST, GraphQL or C#
while respecting the conventions and capabilities of the respective language.

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

The query may also contain "dirty" parts - that is, null constraints and unnecessary parts:

<SourceCodeTabs>
[Java query example](docs/user/en/use/api/example/java-dirty-query-example.java)
</SourceCodeTabs>

The query is automatically cleaned and unnecessary constraints are removed before it is processed by the evitaDB engine.

There are several handy visitors (more will be added) that allow you to work with the query. They are placed in the package
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/visitor</SourceClass>, and some have shortcut methods in the
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/QueryUtils.java</SourceClass> class.

The query can be "pretty-printed" by using the `prettyPrint` method on the
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/Query.java</SourceClass> class.

</LanguageSpecific>

## Data fetching

Only primary keys of the entities are returned to the query result by default. Each entity in this simplest case is
represented by <SourceClass>[EntityReferenceContract.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/data/EntityReferenceContract.java)</SourceClass>
interface.

Client application can request returning entity bodies instead, but this must be explicitly requested by using specific
require constraint:

- [entity fetch](querying/query_language#entity-body)
- [attribute fetch](querying/query_language#attributes)
- [associated data fetch](querying/query_language#associated-data)
- [price fetch](querying/query_language#prices)

When such a require constraint is used, data are fetched *greedily* during initial query. Response object will then
contain entities in the form of <SourceClass>[EntityContract.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/data/EntityContract.java)</SourceClass>.

### Lazy fetching (enrichment)

Attributes, associated data and prices can be fetched separately by providing primary key of the entity. Initial entity
loaded by [entity fetch](querying/query_language#entity-body) or by limited set of requirements can be lazily expanded (enriched)
with additional data by so-called *lazy loading*.

This process loads above-mentioned data separately and adds them to the entity object anytime after it was initially
fetched from evitaDB. Due to immutability characteristics enforced by database design, the entity object enrichment
leads to a new instance.

Lazy fetching may not be necessary for frontend designed using MVC architecture, where all requirements for the page
are known prior to rendering. But different architectures might fetch thinner entity forms and later discover that
they need more data in it. While this approach is not optimal performance-wise, it might make the life for developers
easier, and it's much more optimal to just enrich existing query (using lookup by primary key and fetching only missing
data) instead of re-fetching entire entity again.

## Conversion of evitaQL from String to AST and back

The <SourceClass>evita_query/src/main/java/io/evitadb/api/query/QueryParser.java</SourceClass> class allows to parse
the query from [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html) to the AST
form of the <SourceClass>evita_query/src/main/java/io/evitadb/api/query/Query.java</SourceClass> class.
The string notation of *evitaQL* can be created at any time by calling the `toString()` method on the
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/Query.java</SourceClass> object.

The parser supports passing values by reference, copying the proven approach from a JDBC
[prepared statement](https://docs.oracle.com/javase/tutorial/jdbc/basics/prepared.html)
which allows the use of the `?` character in the query and returns an array of correctly sorted input parameters.

It also supports so-called [named queries](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/core/namedparam/NamedParameterJdbcTemplate.html),
which are widely used in the [Spring framework](https://spring.io/projects/spring-data-jdbc), using variables in the
query with the format `:name` and providing a [map](https://docs.oracle.com/javase/8/docs/api/java/util/Map.html) with
the named input parameters.

In the opposite direction, it offers the `toStringWithParameterExtraction` method on the
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/Query.java</SourceClass> class object, which allows to
create the string format for *evitaQL* in the form of a *prepared statement* and extract all parameters in a separate
array.