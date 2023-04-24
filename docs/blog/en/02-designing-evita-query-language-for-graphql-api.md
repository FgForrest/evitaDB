---
title: Designing the Evita Query Language for the GraphQL API
perex: In evitaDB (like in many other databases), in order to get any data, you need to somehow ask which data you want. The GraphQL language is, however, specific and needs a specific syntax.
date: '2022-01-12'
author: 'Lukáš Hornych'
motive: assets/images/02-designing-evita-query-language-for-graphql-api.png
---

A set of these questions is called a _query_. Each _query_ contains several questions or some sort of hints to filter, sort,
return or format the desired data. We call these _constraints_. We have 4 basic types of _constraints_: _head_, _filter_,
_order_ and _require_. _Head_ constraints specify some metadata, such as: which collection of entities will the query be
searching. _Filter_ constraints simply filter entities by the defined conditions. _Order_ constraints sort entities by their
entity properties (attributes, prices, etc.). At last, _require_ constraints define what the output data will contain:
will it be just entities? How rich will these entities be? Will there be some other data like a facet summary, parent
entities and so on?

## Original Java Query Language

Initially, because evitaDB is embeddable, we created the query language only in Java using basic POJOs and static factory
methods for easier creation of individual constraints. These constraints can be nested inside constraint containers to
represent more complex queries.

``` java
Query query = query(
  collection("brand"),
  filterBy(
     and(
        equals("code", "samsung"),
        inRange("validity", OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
        priceInCurrency("EUR")
     )
  ),
  orderBy(
     attribute("name")
  ),
  require(
     page(1, 5),
     entityBody()
  )
);
```

This enabled us to use quite a variety of all kinds of conditions and their combinations and allowed Java developers using the
embedded version of evitaDB and us to test evitaDB in a type-safe manner with code completion.

## The Query Language for external APIs

Unfortunately, this Java approach cannot be used with external APIs like _GraphQL_, _REST_ or _gRPC_. Therefore, we needed
another way of declaring _queries_. Our first attempt was to create
a [DSL](https://en.wikipedia.org/wiki/Domain-specific_language) with a parser that would copy the Java design of
_constraints_, but it could be parsed from any string. We achieved this by using
the [ANTLR4](https://github.com/antlr/antlr4) library to define
our [DSL](https://en.wikipedia.org/wiki/Domain-specific_language) from which the lexer and the parser were generated. Thanks to
this parser, we were able to parse a _query_ from any string from any API, although it lacked type-safety and code
completion (there was no time to build a custom plugin or [LSP](https://en.wikipedia.org/wiki/Language_Server_Protocol)
for IDE syntax validation and autocompletion). We used it only to build the _gRPC_ API because _gRPC_ doesn’t allow
simple objects with generics and arbitrary parameters like Java. But _GraphQL_ and _REST_ APIs work with JSON objects,
so we wanted to come up with something a little bit different that would fit the JSON language and could be
potentially backed by the _GraphQL_ or _REST_ schema (unlike the plain string query).

## GraphQL API Query Language

When we were building the _GraphQL_ API we were amazed by the code completion and documentation right at your hands when
writing _GraphQL_ queries and how much you can customize the form of the returned data. This gave us an idea to use our
internal _evitaDB_ schemas for catalogs and entities to build a mechanism that would generate an entire _GraphQL_ schema
from our internal schemas. It would bring an “automated” documentation and enable intelligent code completion that would
lead the users on what and how to query and help them avoid mistakes in the query. But we didn’t stop there, we also
wanted to take our query language and generate _GraphQL_ schema for it so it can be used with code completion as well as
the rest of _GraphQL_ queries as mentioned above.

### Our requirements

In order to support all features of our original query language we wanted:

* the _GraphQL_ Query Language be similar to the original _Java_ Query Language
* to be able to define classifiers for each _constraint_ (to locate data)
* to be able to define values for comparison
* to be able to define child _constraints_ inside parents _constraints_
* to have both, a single _constraint_, or an array of multiple _constraints_
* to be able to combine all of the above

In addition to that we wanted to use power of _GraphQL_ schema and editors and enhance it with the following criteria:

* see only those constraints that make sense for a particular entity collection
* in nested constraints, see only constraints that make sense in that particular context
* instead of rather generic constraints where a classifier and any comparable value are inserted as arguments, generate
  specific versions of those generic constraints based on entity schemas to further tell client what data and data types
  they can query

### Inspiration

We started by researching what other database designers have come up with in this subject of interest. We found several
articles and documentations about attempts of creating
such [DSL](https://en.wikipedia.org/wiki/Domain-specific_language)s.

Following examples are something we didn’t like too much because this way the editor cannot provide any code completion for
a client and would be annoyed writing the query with all the special characters.

* [https://www.linkedin.com/pulse/building-your-own-query-language-my-first-career-project-batra/](https://www.linkedin.com/pulse/building-your-own-query-language-my-first-career-project-batra/)
* [https://www.elastic.co/guide/en/elasticsearch/reference/current/query-filter-context.html](https://www.elastic.co/guide/en/elasticsearch/reference/current/query-filter-context.html)

Then we came across some other examples which we quite liked for its expressiveness and possibility of code completion.

* [https://nordicapis.com/review-of-groq-a-new-json-query-language/](https://nordicapis.com/review-of-groq-a-new-json-query-language/)
* [https://github.com/clue/json-query-language/blob/master/SYNTAX.md](https://github.com/clue/json-query-language/blob/master/SYNTAX.md)
* [https://www.edgedb.com/docs/graphql/graphql](https://www.edgedb.com/docs/graphql/graphql)

### Our approach

Mainly, we took inspiration from the _EdgeDB_ approach as they were also generating a _GraphQL_ schema for querying from
their internal database schema. What we didn’t like about several of these approaches very much was the condition
definition being nested inside an object with other conditions with implicit _AND_ condition between them. This creates an
unnecessary difficulty for developers to write lots of curly brackets to define simple _constraints_ and complicates
stating multiple conditions for the same data in _OR_ conditions (well, it doesn’t but you would have to wrap it in
another object). Therefore, our idea was to basically combine those conditions with the key to create a composite key
containing a data locator and condition:

```
{attributeName}_{condition} -> code_equals
```

A value to this composite key would then be simply a comparable value (or nested child constraints) in some supported data
type (in this case data type of attribute `code` in our entity schema). This would also allow for having multiple statements
in the same parent object with different conditions. But we weren’t the first ones to think of that.
The [article about the GORQ](https://nordicapis.com/review-of-groq-a-new-json-query-language/) language is showing a similar
_GraphQL_ syntax.

Unfortunately, unlike _EdgeDB_, where there are only object types and their fields, and thus the query
language must handle only “generic” fields and object types, _evitaDB_ contains more types of data in entities to
query. For example, each entity can have _attributes_, _prices_, _references_, _hierarchy references_, _facets_ and so
on. Each type of data has its own _constraints_ and you can even query inside some of them with other _constraints_. On
top of that, each entity can support only some of these types based on its data.

To easily distinguish between each type of entity data and to prevent having to duplicate conditions for multiple types of
data when writing a query, we came up with prefixes for _constraints_. Each prefix represents on what type of data a
constraint can operate:

* _generic_ - generic constraint, usually some kind of wrapper like _and_, _or_ or _not_
* _entity_ - handles properties directly accessible from the entity like the _primary key_
* _attribute_ - can operate on an entity’s attribute values
* _associatedData_ - can operate on an entity’s associated data values
* _price_ - can operate on entity prices
* _reference_ - can operate on entity references
* _hierarchy_ - can operate on an entity’s hierarchical data (the hierarchical data may be even referenced from other
  entities)
* _facet_ - can operate on referenced facets to an entity

On top of that, we decided that _generic_ constraints will not use explicit prefixes for more readability and some
constraints will not need any classifier. Which led us to following 3 formats of composite keys we support and use:

```
{condition} -> and (only usable for generic constraints)
{propertyType}_{condition} -> hierarchy_withinSelf (in this case the classifier of used hierarchy is implicitly defined by rest of a query)
{propertyType}_{classifier}_{condition} -> attribute_code_equals (key with all metadata)
```

A complete single simple constraints would look like this:

```json
attribute_code_equals: "iphone7s"
```

```json
entity_primaryKey_inSet: [10, 20]
```

#### JSON limitations

This is all due to the fact that JSON objects are _very_ limited and don’t allow for creating some sort of named
constructors or at the very least factory methods which would tell us which constraint we are dealing with when parsing the
query. That's why we and other developers decided to use JSON keys for this purpose, where the key contains the name (or
in our case multiple metadata) of a _constraint_ and a value that contains only comparable values for that _constraint._

But this introduced a few new problems - mainly with the child constraints. In Java, we just specify if we want to support a
list of _constraints_ or a single _constraint_ as constructor parameters. In JSON, if we want to do that, we first need to
wrap each child constraint in another JSON object to have access to names of child constraints but then we have a
problem that a client can specify multiple constraints in that wrapper object, even though the constraint may accept
only one child constraint. We could just throw an error when the client does that but that would be quite unintuitive
and would require a client to submit a query to find out if it has the correct structure. Instead, we decided that each
such wrapper container would be translated into an implicit _and_ constraint with an implicit _AND_ relation between
inner _constraints_ (and would throw error only in edge cases when this wrapper _AND_ container doesn’t make sense). Such
an approach introduces new complexity to the query resolver but on the other hand, it solves nearly all of the problems
with child constraints. As a bonus, clients don’t have to use explicit _and_ constraints if they are okay with the
default _AND_ relation. This can be useful in constraints, such as the _filterBy_ constraint, which takes only one child constraint, but
because the child has to be wrapped inside an implicit _and_ constraint, the client doesn’t have to use the _and_
constraint at all.

#### Examples of final solution

A generated _GraphQL_ schema of a query looks similar to this:

```graphql
type Query {
    productQuery(filterBy: FilterConstraint): [Product!]!
}

input FilterConstraint1 {
    and: [FilterConstraint1!]
    or: [FilterConstraint1!]
    attribute_code_equals: String
    attribute_priority_equals: Int
    hierarchy_category_within: NestedObject1
    ...
}

input NestedObject1 {
  ofParent: Int!
  with: [FilterConstraint2!]
}
```

To illustrate how it can be used in practice, next snippet shows the implicit _and_ condition between _equals_ and
_startsWith_ constraints inside a _filterBy_ constraint container:

```json
filterBy: {
    attribute_code_equals: "iphone7s",
    attribute_url_startsWith: "https://"
}
```

Other more complex example of _or_ constraint container with inner implicit _and_ containers:

```json
filterBy: {
   or: [
      {
         entity_primaryKey_inSet: [100, 200]
      },
      {
         attribute_code_startsWith: "ipho",
         hierarchy_category_within: {
            parentOf: 20
         },
         price_between: ["100.0", "250.0"]
      }
   ]
}
```

Finally, an example with nested child constraints, that, in this case, would allow completely different constraints than
the parent _filterBy_ container allows (there is different set of attributes specified in the relation and the entity
scope):

```json
filterBy: {
    reference_brand_having: {
       attribute_code_equals: "apple"
    }
}
```

## Conclusion

In the end, we chose this format in the hope that it would require less special characters and would read more like
English, which could greatly help with the intuitiveness of the language. The disadvantage is the verbosity of the
GraphQL query API (and we, of course, didn’t want
to [bring back COBOL](https://www.quora.com/Why-do-they-say-that-that-old-programming-language-COBOL-is-very-verbose-How-was-it-verbose)),
but we believe that most of the query will be auto-completed by an editor and developers would need to write only a
few characters for each constraint. Another argument is that with our approach, most complex queries fit onto one
screen without scrolling, because the simple _constraints_ usually take just one line vs. a minimum of three lines, such as
in the case of an [EdgeDB approach](https://www.edgedb.com/docs/graphql/graphql). We discussed this format with
several front-end and back-end developers, and they all seemed to agree that in our case, this approach could work much
better than the earlier mentioned ones. We applied this approach to the _order_ and _require_ constraints as well, and it
worked out quite nicely in comparison to the above-mentioned approaches.
