---
title: GraphQL
perex:
date: '21.3.2023'
author: 'Bc. Lukáš Hornych'
---

**Work in progress**

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

There isn't a single GraphQL API instance for entire evitaDB instance. Instead, each evitaDB [catalog](https://evitadb.io/documentation/use/data-model#catalog)
has its own GraphQL API (in fact, each catalog has two GraphQL 
API instances but more on that later) on its own URL with data only from that particular catalog. 
On top of that, there is one another GraphQL API instance reserved for evitaDB management
(e.g. creating new catalog, removing existing catalog) called <Term>system API</Term>.

Each GraphQL API instance URL starts with `/gql` which is followed by URL-formatted name of catalog for specific 
catalog APIs or with reserved `system` keyword for above-mentioned system API. For each catalog there is a 
<Term>data API</Term> located at base `/gql/{catalog name}` URL and <Term>schema API</Term> located at the
`/gql/{catalog name}/schema` URL. The <Term>data API</Term> is used to query and update actual data of particular catalog.
The <Term>schema API</Term> is more of "introspection" API because it let user view and modify internal evitaDB
schemas which in turn affects the GraphQL API schema.

<Note type="example">

<NoteTitle toggles="true">

##### What URLs will be exposed for set of defined catalogs?
</NoteTitle>

Suppose you have catalogs `fashion` and `electronics`. evitaDB would expose following GraphQL API instances, each 
with its own relevant GraphQL schema:

- `/gql/fashion` - <Term>data API</Term> to query or update the actual data of the `fashion` catalog
- `/gql/fashion/schema` - <Term>schema API</Term> to view and modify the internal structure of the `fashion` catalog
- `/gql/electronic` - <Term>data API</Term> to query or update the actual data of the `electronic` catalog
- `/gql/electronic/schema` - <Term>schema API</Term> to view and modify the internal structure of the `electronic` catalog
- `/gql/system` - <Term>system API</Term> to manage evitaDB itself

</Note>

## Structure of APIs

### Structure of <Term name="data API">data APIs</Term>

A single <Term>data API</Term> for a single catalog contains only several types of queries and mutations, but most of them are "duplicated" for 
each [collection](https://evitadb.io/documentation/use/data-model#collection) inside that catalog. 

Base queries are:

- `collections` - returns list of all collection names
- `get{collection name}` - returns a single entity from the specified collection by set of attributes or primary key
- `list{collection name}` - returns a simple list of entities from the specified collection by full query but without pagination or any extra results
- `query{collection name}` - returns page of entities from the specified collection by full query with possible extra results
- `count{collection name}` - returns size of the specified collection (i.e. number of entities)

and base mutations are:

- `upsert{collection name}` - inserts a new or updates existing entity in the specified collection with set of mutations
- `delete{collection name}` - deletes single or list of entities in the specified collection by full query 

Each query or mutation then contains arguments and returns data specific to particular collection and [its schema](https://evitadb.io/documentation/use/schema#entity).

There is however another sort of "virtual" simplified collection in the GraphQL API called `entity` which let users fetch entities
by global attributes without knowing target collection. For this `entity` "collection", only `get` and `list` queries are available.

### Structure of <Term name="schema API">schema APIs</Term>

A single <Term>schema API</Term> for a single catalog contains one query and mutation for each [collection](https://evitadb.io/documentation/use/data-model#collection):

- `get{collection name}Schema` - returns the [internal evitaDB entity schema](https://evitadb.io/documentation/use/schema#entity) of the specified collection
- `update{collection name}Schema` - updates the existing [internal evitaDB entity schema](https://evitadb.io/documentation/use/schema#entity) of the specified collection

and one query and mutation for catalog schema specified by the URL:

- `getCatalogSchema` - returns the [internal evitaDB catalog schema](https://evitadb.io/documentation/use/schema#catalog) of the catalog
- `updateCatalogSchema` - updates the existing [internal evitaDB catalog schema](https://evitadb.io/documentation/use/schema#catalog) of the catalog

### Structure of <Term name="system API">system API</Term>

There is nothing special about the <Term>system API</Term>, just set of basic queries and mutations.

## Query language

<UsedTerms>
    <h4>Used terms</h4>
   <dl>
      <dt>implicit container</dt>
      <dd>
         A JSON object with all possible constraints defined as properties of that JSON object. All these constraints are joined
         via logical [AND](https://en.wikipedia.org/wiki/Logical_conjunction) when processing.
      </dd>
   </dl>
</UsedTerms>

evitaDB comes with its own [query language](https://evitadb.io/documentation/query/basics) which GraphQL has its own facade for.
The main difference between these two is that the evitaDB's original language is generic set of constraints and doesn't 
care about concrete [collection](https://evitadb.io/documentation/use/data-model#collection) data structure, where the 
GraphQL version has same constraints but customized based on [collection](https://evitadb.io/documentation/use/data-model#collection) data structure
to provide concrete available constraint for defined data structure.

This custom version of the query language is possible because in our GraphQL API, the query language is dynamically generated
based on [internal collection schemas](https://evitadb.io/documentation/use/schema#entity) to show only constraints that 
can be truly used to query data (which also changes based on context of nested constraints). This also provide constraint arguments with data types that match 
the internal data. This helps with the self-documentation because one doesn't necessarily have to know about 
the domain model because most of GraphQL IDEs code-completes the available constraints from the GraphQL API schema.

### Syntax of query and constraints

A [query](https://evitadb.io/documentation/query/basics#grammar), although custom for each [collection](https://evitadb.io/documentation/use/data-model#collection), has
pre-defined set of rules/structure. It is basically a tree of individual constraints which ultimately define how
the queried data will be filtered, ordered and what structure they will output in.
Basic constraint are same as for the evitaDB [query language](https://evitadb.io/documentation/query/basics) but
are differently written down and not every single one may be available for domain-specific data types. There are two main 
reason for that: the first one is that we want to help the user as much as possible
with code completion, therefore we use dynamically generated queries, the second one is due to the limitations of JSON
objects, mainly that JSON object doesn't have names to reference them by.

Therefore, we have come up with following syntax for constraints: each constraint consists of:

- `key` - basically property of parent JSON object
  - defines targeted property type
  - possible classifier of targeted data
  - constraint name
- `value` - object/value of that property
  - scalar, array or object of arguments

<Note type="info">

<NoteTitle toggles="true">

##### Constraint key syntax in detail
</NoteTitle>

Each key consists of 3 parts as mentioned above:

- property type
- classifier of data
- constraint name

Only the constraint name is required for all the supported constraints, rest depends on a context and type of constraint.

**Property type** defines where the query processor will look for data to compare. There is
a set of finite possible property types:

- `generic` - generic constraint that typically doesn't work with concrete data, it is more for containers like `and`, `or` or `not`
  - for simplicity, this property type is not written in the key of an actual constraint
- `entity` - handles properties directly accessible from an [entity](https://evitadb.io/documentation/use/data-model#entity) like the `primary key`
- `attribute` - can operate on an entity’s [attribute values](https://evitadb.io/documentation/use/data-model#attributes-unique-filterable-sortable-localized)
- `associatedData` - can operate on an entity’s [associated data values](https://evitadb.io/documentation/use/data-model#associated-data)
- `price` - can operate on entity [prices](https://evitadb.io/documentation/use/data-model#prices)
- `reference` - can operate on entity [references](https://evitadb.io/documentation/use/data-model#references)
- `hierarchy` - can operate on an entity’s [hierarchical data](https://evitadb.io/documentation/use/data-model#hierarchical-placement) (the hierarchical data may be even referenced from other entities)
- `facet` - can operate on referenced [facets](https://evitadb.io/documentation/use/data-model#references) to an entity

**Classifier** specifies exactly which data of specified property type the constraint will operate on, if supported by the
property type. This is used e.g.
for attributes whereby simply defining property type we still don't know which attribute we want to compare. But without property type
we wouldn't know what the classifier represents. Therefore, we need *both* property type and classifier. But in cases like price comparison
these constraints operate on single computed price so the evitaDB query processor implicitly knows what price we want to compare.

Lastly, **constraint name** actually defines what will the query processor do with the targeted data (i.e. how to compare passed
data with data in database).

All possible parts combinations are:
```
{constraint name} -> `and` (only usable for generic constraints)
{property type}{constraint name} -> `hierarchyWithinSelf` (in this case the classifier of used hierarchy is implicitly defined by rest of a query)
{property type}{classifier}{constraint name} -> `attributeCodeEquals` (key with all metadata)
```

</Note>

<Note type="example">

<NoteTitle toggles="true">

##### Example of simple constraint
</NoteTitle>

A single constraint to return only entities that contain attribute `deviceType` that equals to string `phone` would look like this:
```json
attributeDeviceTypeEquals: "phone"
```

</Note>

As mentioned above, JSON object don't have names, and we can't define constraint `key` in body of generic JSON object because 
we would lose the strictly-typed query langauge backed by the GraphQL API schema. Instead, the `key` is defined in
parent container (parent JSON object) as a property. Such containers hold all possible constraints in particular context.
These containers also contain some generic constraints like `and`, `or` or `not` which accepts inner containers to
nest constraint into trees of complex queries.

However, this complicates things when you need to pass child constraints into arguments of another constraint because you
cannot simply pass object representing constraint, you need to wrap it in above-mentioned container with all available constraints
to be able to define the constraint `key`. We call these necessary wrapping containers <Term name="implicit container">implicit containers</Term>
and they may look like this:

```json
{
  and: ...,
  or: ...,
  attributeCodeEquals: ...,
  ...
}
```

Unfortunately, this means that one can define multiple constraints in one go in such a container, and we need to somehow define
relation logic between these child constraints. We chose that all these <Term name="implicit container">implicit containers</Term> define logical [`AND`](https://en.wikipedia.org/wiki/Logical_conjunction)
between passed child constraint, ultimately resulting in `and` constraint under the hood.

Unfortunately, there is another little drawback if one needs to define list of same constraint with different arguments.
In such a case one needs to wrap every such constraint into separate <Term>implicit container</Term> and pass it like array into parent
constraint, like so:
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

This is mainly because JSON objects don't support multiple properties with same names.

<Note type="example">

<NoteTitle toggles="true">

##### Example of complex constraint
</NoteTitle>

A complex constraint tree with simple constraints, containers and <Term name="implicit container">implicit containers</Term>
to return only entities with specific primary key or other more complex constraints:
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

We have written an entire [blog post](https://evitadb.io/blog/02-designing-evita-query-language-for-graphql-api) about how we
approached the whole issue of representing the [evitaDB query language](https://evitadb.io/documentation/query/basics) in
the GraphQL APIs, the possible syntax variants, limitations of JSON, etc. 

</Note>

## Recommended usage

Our GraphQL API schemas conform to official specification, thus it can be used like any other regular GraphQL API, i.e. with
standard tools. However, below are our recommendations for tools etc. we use at evitaDB.

### Recommended IDEs

During development, we came across and tried multiple tools for consuming GraphQL APIs, but there are only few we can recommend.

For desktop IDE to test and explore GraphQL API, the [Altair](https://altairgraphql.dev/) client proved to be of great help. It is an 
awesome desktop client for GraphQL APIs with excellent code-completion and nice API schema explorer.
You can also use more general HTTP desktop client like [Insomnia](https://insomnia.rest/) which also features, although
limited, GraphQL tools with an API schema explorer. [Postman](https://www.postman.com/) can be used as well.

If you are looking for web-based client that you can integrate into your application, there is the official lightweight
[GraphiQL](https://github.com/graphql/graphiql) which provide all basic tools you might need.

The basic idea of using the IDE is to first fetch the GraphQL API schema from one of the above-mentioned URLs that are
exposed by evitaDB. Then explore API schema using IDE's docs/API schema explorer and start typing query or mutation to send to the server.
In case of e.g. the [Altair](https://altairgraphql.dev/) you don't even need to explore the API schema manually because
Altair has, like many other, code-completion in its editor based on that fetched API schema.

### Recommended client libraries

You can find all available libraries on the [official GraphQL website](https://graphql.org/code/) which you can
choose from for your own client language. Some can even generate classes/types based on the API schema for you to use in
your codebase.