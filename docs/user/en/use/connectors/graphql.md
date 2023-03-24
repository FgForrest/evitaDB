---
title: GraphQL
perex:
date: '21.3.2023'
author: 'Lukáš Hornych'
---

The [GraphQL](https://graphql.org/) API has been developed to allow users to easily query domain-specific data from
evitaDB with a high degree of customisation of the queries and the self-documentation that GraphQL APIs provide.

The main idea behind our GraphQL API implementation is that the API schema is dynamically generated based on
evitaDB's [internal schemas](https://evitadb.io/documentation/use/schema). This means that users only see the data they
can actually retrieve. They do not see some generic strings of names. For example, if you have defined in evitaDB an
entity called `product` with attributes `code` and `name`, the GraphQL API allows you to query only these two attributes
like this:

```graphql
getProduct {
    attributes {
        code
        name
    }
}
```

and on top of that, they will have data types that are equivalent to the ones specified in evitaDB, and not some generic
ones.

## GraphQL API instances

<UsedTerms>
    <h4>Used terms</h4>
   <dl>
      <dt>data API</dt>
      <dd>
         A GraphQL API instance that provides ways to query and update actual data (typically entities and related data)
        of a single catalog.
      </dd>
      <dt>schema API</dt>
      <dd>
         A GraphQL API instance that provides ways to fetch and modify the internal structure of a single catalog.
      </dd>
      <dt>system API</dt>
      <dd>
         A GraphQL API instance that provides ways to manage evitaDB itself.
      </dd>
   </dl>
</UsedTerms>

There isn't a single GraphQL API instance for the whole evitaDB instance. Instead, each evitaDB [catalog](https://evitadb.io/documentation/use/data-model#catalog)
has its own GraphQL API (actually each catalog has two GraphQL 
API instances, but more on that later) on its own URL with data only from that particular catalog. 
In addition, there is one another GraphQL API instance that is reserved for evitaDB administration
(e.g. creating new catalogs, removing existing catalogs) called <Term>system API</Term>.

Each GraphQL API instance URL starts with `/gql`, followed by the URL-formatted catalog name for specific 
catalog APIs, or with the reserved `system` keyword for the above-mentioned system API. For each catalog there is a 
<Term>data API</Term> located at the `/gql/{catalog name}` base URL and a <Term>schema API</Term> located at the
`/gql/{catalog name}/schema` URL. The <Term>data API</Term> is used to retrieve and update the actual data of a given catalog.
The <Term>schema API</Term> is more of an "introspection" API, as it allows users to view and modify internal evitaDB
schemas, which in turn affects the GraphQL API schema.

<Note type="example">

<NoteTitle toggles="true">

##### What URLs will be exposed for a set of defined catalogs?
</NoteTitle>

Suppose you have catalogs `fashion` and `electronics`. evitaDB would expose the following GraphQL API instances, each 
with its own relevant GraphQL schema:

- `/gql/fashion` - a <Term>data API</Term> to query or update the actual data of the `fashion` catalog
- `/gql/fashion/schema` - a <Term>schema API</Term> to view and modify the internal structure of the `fashion` catalog
- `/gql/electronic` - a <Term>data API</Term> to query or update the actual data of the `electronic` catalog
- `/gql/electronic/schema` - a <Term>schema API</Term> to view and modify the internal structure of the `electronic` catalog
- `/gql/system` - the <Term>system API</Term> to manage evitaDB itself

</Note>

## Structure of APIs

### Structure of data APIs

A single <Term>data API</Term> for a single catalog contains only a few types of queries and mutations, but most of them are "duplicated" for 
each [collection](https://evitadb.io/documentation/use/data-model#collection) within that catalog. 

Base queries are:

- `collections` - returns a list of all collection names
- `get{collection name}` - returns a single entity from the specified collection by a set of attributes or primary key
- `list{collection name}` - returns a simple list of entities from the specified collection by full query, but without pagination or any extra results
- `query{collection name}` - returns a page of entities from the specified collection by full query with possible extra results
- `count{collection name}` - returns the size of the specified collection (i.e., the number of entities)

and base mutations are:

- `upsert{collection name}` - inserts a new or updates an existing entity in the specified collection with a set of mutations
- `delete{collection name}` - deletes a single entity or a list of entities in the specified collection by full query 

Each query or mutation then takes arguments and returns data specific to a given collection and [its schema](https://evitadb.io/documentation/use/schema#entity).

However, in addition to user-defined collections, there is a "virtual" simplified collection in the GraphQL API called `entity`
that allows users to retrieve entities by global attributes without knowing the target collection. For this `entity` "collection", 
only `get` and `list` queries are available.

### Structure of schema APIs

A single <Term>schema API</Term> for a single catalog contains one query and one mutation for each [collection](https://evitadb.io/documentation/use/data-model#collection):

- `get{collection name}Schema` - returns the [internal evitaDB entity schema](https://evitadb.io/documentation/use/schema#entity) of the specified collection
- `update{collection name}Schema` - updates the existing [internal evitaDB entity schema](https://evitadb.io/documentation/use/schema#entity) of the specified collection

and one query and one mutation for catalog schema specified by the URL:

- `getCatalogSchema` - returns the [internal evitaDB catalog schema](https://evitadb.io/documentation/use/schema#catalog) of the catalog
- `updateCatalogSchema` - updates the existing [internal evitaDB catalog schema](https://evitadb.io/documentation/use/schema#catalog) of the catalog

### Structure of system API

There is nothing special about the <Term>system API</Term>, just a set of basic queries and mutations.

## Query language

<UsedTerms>
    <h4>Used terms</h4>
   <dl>
      <dt>implicit container</dt>
      <dd>
         A JSON object with all possible constraints defined as properties of that JSON object. All of these constraints are linked
         by logical [AND](https://en.wikipedia.org/wiki/Logical_conjunction) during processing.
      </dd>
   </dl>
</UsedTerms>

evitaDB is shipped with its own [query language](https://evitadb.io/documentation/query/basics), for which our GraphQL API has its own facade.
The main difference between these two is that the evitaDB's original language is generic set of constraints and doesn't 
care about concrete [collection](https://evitadb.io/documentation/use/data-model#collection) data structure, where the 
GraphQL version has the same constraints but customized based on [collection](https://evitadb.io/documentation/use/data-model#collection) data structure
to provide concrete available constraint for defined data structure.

This custom version of the query language is possible because in our GraphQL API, the query language is dynamically generated
based on [internal collection schemas](https://evitadb.io/documentation/use/schema#entity) to display only constraints that 
can actually be used to query data (which also changes based on context of nested constraints). This also provides constraint arguments with data types that match 
the internal data. This helps with the self-documentation because you don't necessarily need to know about 
the domain model, since most of GraphQL IDEs will auto-complete the available constraints from the GraphQL API schema.

### Syntax of query and constraints

A [query](https://evitadb.io/documentation/query/basics#grammar), although custom for each [collection](https://evitadb.io/documentation/use/data-model#collection), has
a predefined set of rules/structure. It is basically a tree of individual constraints that ultimately define how
the queried data will be filtered, ordered and returned.
The basic constraints are the same as for the evitaDB [query language](https://evitadb.io/documentation/query/basics), but
they are written differently and not every single one may be available for your domain-specific data types. There are two main 
reasons for this: the first one is that we want to help the user as much as possible
with code completion, so we use dynamically generated queries, the second one is due to the limitations of JSON
objects, mainly that JSON objects don't have names to reference them by.

Therefore, we have come up with the following syntax for constraints: each constraint consists of:

- `key` - basically a property of a parent JSON object
  - defines targeted property type
  - possible classifier of targeted data
  - a constraint name
- `value` - an object/value of that property
  - a scalar, an array or an object of arguments

Thanks to the GraphQL API schemas, you don't need to worry about details the individual parts of the syntax, because the GraphQL API
will hand you only those constraint that are valid.

<Note type="info">

<NoteTitle toggles="true">

##### Constraint key syntax in detail
</NoteTitle>

However, if you want to know more about the underlying syntax, read on.
Each key consists of 3 parts as mentioned above:

- a property type
- a classifier of data
- a constraint name

Only the constraint name is required for all the supported constraints, the rest depends on the context and the type of constraint.


The **property type** defines where the query processor will look for data to compare. There is a finite set of possible property types:

- `generic` - generic constraint that typically doesn't work with concrete data, it's more for containers like `and`, `or` or `not`
  - for simplicity, this property type is not written in the key of an actual constraint
- `entity` - handles properties directly accessible from an [entity](https://evitadb.io/documentation/use/data-model#entity) like the `primary key`
- `attribute` - can operate on an entity’s [attribute values](https://evitadb.io/documentation/use/data-model#attributes-unique-filterable-sortable-localized)
- `associatedData` - can operate on an entity’s [associated data values](https://evitadb.io/documentation/use/data-model#associated-data)
- `price` - can operate on entity [prices](https://evitadb.io/documentation/use/data-model#prices)
- `reference` - can operate on entity [references](https://evitadb.io/documentation/use/data-model#references)
- `hierarchy` - can operate on an entity’s [hierarchical data](https://evitadb.io/documentation/use/data-model#hierarchical-placement) (the hierarchical data may be even referenced from other entities)
- `facet` - can operate on referenced [facets](https://evitadb.io/documentation/use/data-model#references) to an entity

The **classifier** specifies exactly which data of the specified property type the constraint will operate on, if supported by the
property type. This is used e.g.
for attributes, where simply defining the property type doesn't tell us which attribute we want to compare. But without the property type
we don't know what the classifier represents. Therefore, we need *both* the property type and the classifier. But in cases like price comparison,
these constraints operate on single computed price so the evitaDB query processor implicitly knows which price we want to compare.


Finally, the **constraint name** actually defines what the query processor will do with the target data (i.e., how to compare the passed
data with data in the database).


All possible parts combinations are:
```
{constraint name} -> `and` (only usable for generic constraints)
{property type}{constraint name} -> `hierarchyWithinSelf` (in this case the classifier of used hierarchy is implicitly defined by rest of a query)
{property type}{classifier}{constraint name} -> `attributeCodeEquals` (key with all metadata)
```

</Note>

<Note type="example">

<NoteTitle toggles="true">

##### Example of a simple constraint
</NoteTitle>

A single constraint to return only entities that contain the `deviceType` attribute equal to the string `phone` would look like this:
```json
attributeDeviceTypeEquals: "phone"
```

</Note>

As mentioned above, JSON objects don't have names, and we can't define the constraint `key` in the body of a generic JSON object because 
we would lose the strictly-typed query language backed by the GraphQL API schema. Instead, the `key` is defined as a property
in the parent container (parent JSON object). Such containers contain all possible constraints in a given context.
These containers also contain some generic constraints such as `and`, `or` or `not` that accept inner containers to
combine constraints into trees of complex queries.

However, this complicates things when you need to pass child constraints into arguments of another constraint, because you
cannot simply pass the object representing the constraint, you need to wrap it in the above-mentioned container with all available constraints
to be able to define the constraint `key`. We call these necessary wrapping containers <Term name="implicit container">implicit containers</Term>,
and they can look like this:

```json
{
  and: ...,
  or: ...,
  attributeCodeEquals: ...,
  ...
}
```

Unfortunately, this means that you can define multiple constraints in one go in such a container, and we need to somehow define
relational logic between these child constraints. We chose to have all these <Term name="implicit container">implicit containers</Term> 
define logical [`AND`](https://en.wikipedia.org/wiki/Logical_conjunction)
between passed child constraints, ultimately resulting in the `and` constraint under the hood.

Unfortunately, there is another small drawback if you need to define the same constraint multiple times in a single list
with different arguments.
In such a case, you need to wrap each such constraint into a separate <Term>implicit container</Term> and pass it like 
array to the parent constraint, like so:
```json
or: [
  {
    attributeCodeStartsWith: "ipho"       
  },
  {
    attributeCodeStartsWith: "sams"
  }
]
```

This is mainly because JSON objects don't support multiple properties with the same name.

<Note type="example">

<NoteTitle toggles="true">

##### Example of a complex constraint
</NoteTitle>

A complex constraint tree with simple constraints, containers, and <Term name="implicit container">implicit containers</Term>
to return only entities with specific primary keys or other more complex constraints:
```json
filterBy: {
   or: [
      {
         entityPrimaryKeyInSet: [100, 200]
      },
      {
         attributeCodeStartsWith: "ipho",
         hierarchyCategoryWithin: {
            parentOf: 20
         },
         priceBetween: ["100.0", "250.0"]
      }
   ]
}
```

</Note>

<Note type="info">

<NoteTitle toggles="true">

##### Want to know more about the decisions behind the query language design?
</NoteTitle>

We have written a whole [blog post](https://evitadb.io/blog/02-designing-evita-query-language-for-graphql-api) about how we
approached the whole issue of representing the [evitaDB query language](https://evitadb.io/documentation/query/basics) in
the GraphQL APIs, the possible syntax variants, limitations of JSON, etc. 

</Note>

## Recommended usage

Our GraphQL API schemas conform to the official specification, thus it can be used like any other regular GraphQL API, i.e. with
standard tools. However, below are our recommendations for tools etc. that we use at evitaDB.

### Recommended IDEs

During development, we have come across and tried several tools for consuming GraphQL APIs, but there are only a few that we can recommend.

For a desktop IDE to test and explore GraphQL APIs, the [Altair](https://altairgraphql.dev/) client proved to be a great help. It is a
fantastic desktop client for GraphQL APIs with excellent code-completion and a nice API schema explorer.
You can also use a more general HTTP desktop client like [Insomnia](https://insomnia.rest/), which also features GraphQL
tools with an API schema explorer, albeit limited. [Postman](https://www.postman.com/) can also be used.

If you are looking for a web-based client that you can integrate into your application, there is the official lightweight
[GraphiQL](https://github.com/graphql/graphiql), which provides all the basic tools you might need.

The basic idea of using the IDE is to first fetch the GraphQL API schema from one of the above-mentioned URLs that are
exposed by evitaDB. Then explore the API schema using the IDE's docs/API schema explorer and start typing a query or mutation to send to the server.
In case of e.g. the [Altair](https://altairgraphql.dev/) you don't even need to explore the API schema manually because
Altair has, like many others, has a code-completion in its editor based on the retrieved API schema.

### Recommended client libraries

You can find all the available libraries on the [official GraphQL website](https://graphql.org/code/#language-support) for you to
choose from for your own client language. Some can even generate classes/types based on the API schema for you to use in
your codebase.