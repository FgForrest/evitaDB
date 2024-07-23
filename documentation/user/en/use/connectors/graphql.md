---
title: GraphQL
perex: |
  GraphQL is an open-source data query and manipulation language for APIs, providing a powerful alternative to REST by
  allowing clients to specify exactly what data they need, reducing over-fetching and under-fetching of data. Developed
  by Facebook in 2012 and open-sourced in 2015, it enables declarative data fetching where the client can ask for what
  it needs and get exactly that.
date: '21.3.2023'
author: 'Lukáš Hornych'
preferredLang: 'graphql'
---

<LS to="e,j,c,r">
This chapter describes the GraphQL protocol for evitaDB and doesn't make sense for other languages. If you're interested
in the details of the GraphQL implementation, please change your preferred language in the upper right corner.
</LS>
<LS to="g">
The [GraphQL](https://graphql.org/) API has been developed to allow users to easily query domain-specific data from
evitaDB with a high degree of customisation of the queries and the self-documentation that GraphQL APIs provide.

The main idea behind our GraphQL API implementation is that the API schema is dynamically generated based on
evitaDB's [internal schemas](/documentation/user/en/use/schema.md). This means that users only see the data they
can actually retrieve. They do not see some generic strings of names. For example, if you have defined in evitaDB an
entity called `Product` with attributes `code` and `name`, the GraphQL API allows you to query only these two attributes
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
        <dt>catalog data API</dt>
        <dd>
			A GraphQL API instance that provides ways to query and update actual data (typically entities and related data)
            of a single catalog.

	        URL pattern: `/gql/{catalog-name}`
        </dd>
        <dt>catalog schema API</dt>
        <dd>
            A GraphQL API instance that provides ways to fetch and modify the internal structure of a single catalog.

	        URL pattern: `/gql/{catalog-name}/schema`
        </dd>
        <dt>system API</dt>
        <dd>
            A GraphQL API instance that provides ways to manage evitaDB itself.

            URL pattern: `/gql/system`
        </dd>
    </dl>
</UsedTerms>

There isn't a single GraphQL API instance for the whole evitaDB instance. Instead, each evitaDB [catalog](/documentation/user/en/use/data-model.md#catalog)
has its own GraphQL API (actually each catalog has two GraphQL
API instances, but more on that later) on its own URL with data only from that particular catalog.
In addition, there is one another GraphQL API instance that is reserved for evitaDB administration
(e.g. creating new catalogs, removing existing catalogs) called <Term>system API</Term>.

Each GraphQL API instance URL starts with `/gql`, followed by the URL-formatted catalog name for specific
catalog APIs, or with the reserved `system` keyword for the above-mentioned <Term>system API</Term>. For each catalog there is a
<Term>catalog data API</Term> located at the `/gql/{catalog name}` base URL and a <Term>catalog schema API</Term> located at the
`/gql/{catalog name}/schema` URL. The <Term>catalog data API</Term> is used to retrieve and update the actual data of a given catalog.
The <Term>catalog schema API</Term> is more of an "introspection" API, as it allows users to view and modify internal evitaDB
schemas, which in turn affects the GraphQL API schema.

Each GraphQL API instance supports only `POST` HTTP method for executing queries and mutations.

### GraphQL schema fetching

Each GraphQL API instance supports standard [introspection capabilities](https://graphql.org/learn/introspection/) for GraphQL schema
reconstruction. On top of that, each instance supports fetching reconstructed GraphQL schema in DSL format using
`GET` HTTP request on the instance URL. For example, to fetch the GraphQL schema of the `fashion` catalog, you can issue
the following HTTP request:
```http request
GET /gql/fashion
```

<Note type="info">

<NoteTitle toggles="true">

##### `GET` method meaning in the GraphQL spec
</NoteTitle>

We are aware that this implementation is not the right way of using `GET` method according to the GraphQL spec.
However, we opted for this implementation because we use the same approach for fetching the OpenAPI specifications of REST API.
Also, we don't plan to support executing queries and mutations using `GET` method anyway, because it involves unnecessary
escaping of the query string and so on.

</Note>

<Note type="example">

<NoteTitle toggles="true">

##### What URLs will be exposed for a set of defined catalogs?
</NoteTitle>

Suppose you have catalogs `fashion` and `electronics`. evitaDB would expose the following GraphQL API instances, each
with its own relevant GraphQL schema:

- `/gql/fashion` - a <Term>catalog data API</Term> to query or update the actual data of the `fashion` catalog
- `/gql/fashion/schema` - a <Term>catalog schema API</Term> to view and modify the internal structure of the `fashion` catalog
- `/gql/electronic` - a <Term>catalog data API</Term> to query or update the actual data of the `electronic` catalog
- `/gql/electronic/schema` - a <Term>catalog schema API</Term> to view and modify the internal structure of the `electronic` catalog
- `/gql/system` - the <Term>system API</Term> to manage evitaDB itself

</Note>

## Structure of APIs

### Structure of catalog data APIs

A single <Term>catalog data API</Term> for a single catalog contains only a few types of queries and mutations, but most of them are "duplicated" for
each [collection](/documentation/user/en/use/data-model.md#collection) within that catalog.
Each query or mutation then takes arguments and returns data specific to a given collection and [its schema](/documentation/user/en/use/schema.md#entity).

In addition to user-defined collections, there is a "virtual" simplified collection for each catalog in the GraphQL API called `entity`
that allows users to retrieve entities by global attributes without knowing the target collection. However, the `entity` "collection",
has only limited set of queries available.

### Structure of catalog schema APIs

A single <Term>catalog schema API</Term> for a single catalog contains only basic queries and mutations for each
[collection](/documentation/user/en/use/data-model.md#collection) and parent [catalog](/documentation/user/en/use/data-model.md#catalog)
to retrieve or change its schema.

### Structure of system API

There is nothing special about the <Term>system API</Term>, just a set of basic queries and mutations.

## Query language

evitaDB is shipped with its own [query language](/documentation/user/en/query/basics.md), for which our GraphQL API has its own facade.
The main difference between these two is that the evitaDB's original language has generic set of constraints and doesn't
care about concrete [collection](/documentation/user/en/use/data-model.md#collection) data structure, where the
GraphQL version has the same constraints but customized based on [collection](/documentation/user/en/use/data-model.md#collection) data structure
to provide concrete available constraint for defined data structure.

This custom version of the query language is possible because in our GraphQL API, the query language schema is dynamically generated
based on [internal collection schemas](/documentation/user/en/use/schema#entity) to display only constraints that
can actually be used to query data (which also changes based on context of nested constraints). This also provides constraint arguments with data types that match
the internal data. This helps with the self-documentation because you don't necessarily need to know about
the domain model, since most of GraphQL IDEs will auto-complete the available constraints from the GraphQL API schema.

### Syntax of query and constraints

<MDInclude>[Syntax of query and constraints](/documentation/user/en/use/connectors/assets/dynamic-api-query-language-syntax.md)</MDInclude>

## Recommended usage

Our GraphQL APIs conform to the official specification, thus it can be used like any other regular GraphQL API, i.e. with
standard tools. However, below are our recommendations for tools etc. that we use at evitaDB.

### Recommended IDEs

We've developed our own GUI tool called [evitaLab](https://evitadb.io/blog/09-our-new-web-client-evitalab) which supports GraphQL with usefull tools (e.g. data visualisations).
It also has other useful tools for exploring evitaDB instances, not just using GraphQL API.
Therefore, this is our recommened chose of IDE for our APIs.

However if you want to use a generic GraphQL tool, we have recommendations for that too.
During development, we have come across and tried several tools for consuming GraphQL APIs, but there are only a few that we can recommend.

For a desktop IDE to test and explore GraphQL APIs, the [Altair](https://altairgraphql.dev/) client proved to be a great help. It is a
fantastic desktop client for GraphQL APIs with excellent code-completion and a nice API schema explorer.
You can also use a more general HTTP desktop client like [Insomnia](https://insomnia.rest/), which also features GraphQL
tools with an API schema explorer, albeit limited. [Postman](https://www.postman.com/) can also be used.
Typically, you can even use GraphQL plugins in your code IDE, e.g. the [IntelliJ IDEA](https://www.jetbrains.com/idea/)
offers the [GraphQL plugin](https://plugins.jetbrains.com/plugin/8097-graphql) that nicely integrates into your workflow,
although without an API documentation like other standalone IDEs offer.

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
</LS>