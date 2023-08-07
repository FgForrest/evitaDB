---
title: REST
perex: |
  The Representational State Transfer (REST) API protocol is a standardized approach to building web services that 
  employ HTTP methods to create, read, update, and delete data. The protocol is designed around resources, which are any 
  kind of object, data, or service that can be accessed by the client. Its simplicity, scalability, and performance
  make REST the most popular protocol for APIs, used extensively in cloud services, mobile services, and social networks.
date: '24.3.2023'
author: 'Lukáš Hornych'
---

The [REST](https://restfulapi.net/) API with an [OpenAPI schema](https://swagger.io/specification/v3/) in evitaDB has
been developed to allow users and developers to easily query domain-specific data from evitaDB via universal well-known 
API standard that REST APIs provide.

The main idea behind our REST API implementation is that the [OpenAPI schema](https://swagger.io/specification/v3/) is dynamically generated based on
evitaDB's [internal schemas](/documentation/user/en/use/schema.md). This means that users only see the data they
can actually retrieve. For example, if you have defined in evitaDB an entity called `product` with attributes `code` and
`name`, the OpenAPI schema will contain only these to attributes in the `Product` entity model with data types
equivalent to the ones specified in evitaDB instead of some generic ones.

## REST API instances

<UsedTerms>
    <h4>Used terms</h4>
   <dl>
      <dt>catalog API</dt>
      <dd>
         A REST API instance that provides ways to query and update actual data (typically entities and related data)
        of a single catalog, as well as ways to fetch and modify the internal structure of a single catalog.
      </dd>
      <dt>system API</dt>
      <dd>
         A REST API instance that provides ways to manage evitaDB itself.
      </dd>
   </dl>
</UsedTerms>

There isn't a single REST API instance for the whole evitaDB instance. Instead, each evitaDB [catalog](/documentation/user/en/use/data-model.md#catalog)
has its own REST API on its own URL with data only from that particular catalog.
In addition, there is one another REST API instance that is reserved for evitaDB administration
(e.g., creating new catalogs, removing existing catalogs) called <Term>system API</Term>.

Each REST API instance URL starts with `/rest`, followed by the URL-formatted catalog name for specific
catalog APIs, or with the reserved `system` keyword for the above-mentioned <Term>system API</Term>. 
You can download [OpenAPI schema](https://swagger.io/specification/v3/) of each REST API instance by calling `GET`
HTTP request to that base URL which then lists all available endpoints. 

URLs of these REST API instances with above-mentioned base URLs are then further suffixed with specific resources.
In case of <Term>system API</Term> those are typically catalogs. In case of <Term>catalog API</Term> 
there are resources for individual [collections](/documentation/user/en/use/data-model.md#collection)
and their actions.

<Note type="example">

<NoteTitle toggles="true">

##### What URLs will be exposed for a set of defined catalogs?
</NoteTitle>

Suppose you have catalogs `fashion` and `electronics`. evitaDB would expose the following REST API instances, each
with its own relevant [OpenAPI schema](https://swagger.io/specification/v3/):

- `/rest/fashion` - a <Term>catalog API</Term> to query or update the actual data of the `fashion` catalog or its structure
- `/rest/electronic` - a <Term>catalog API</Term> to query or update the actual data of the `electronic` catalog or its structure
- `/rest/system` - the <Term>system API</Term> to manage evitaDB itself

</Note>

## Structure of APIs

### Structure of catalog APIs

A single <Term>catalog API</Term> for a single catalog contains only a few types of endpoints to retrieve and update data or its
internal schema, but most of them are "duplicated" for
each [collection](/documentation/user/en/use/data-model.md#collection) within that catalog.
Also, there is a set of endpoints for retrieving and updating schema of the parent [catalog](/documentation/user/en/use/data-model.md#catalog).
Each endpoint then takes arguments and returns data specific to a given collection and [its schema](/documentation/user/en/use/schema.md#entity).

In addition to user-defined collections, there is a "virtual" simplified collection for each catalog in the REST API called `entity`
that allows users to retrieve entities by global attributes without knowing the target collection. However, the `entity` "collection",
has only limited set of endpoints available.

### Structure of system API

There is nothing special about the <Term>system API</Term>, just a set of basic endpoints.

## Query language

evitaDB is shipped with its own [query language](/documentation/user/en/query/basics.md), for which our REST API has its own facade.
The main difference between these two is that the evitaDB's original language has generic set of constraints and doesn't
care about concrete [collection](/documentation/user/en/use/data-model.md#collection) data structure, where the
REST version has the same constraints but customized based on [collection](/documentation/user/en/use/data-model.md#collection) data structure
to provide concrete available constraint for defined data structure.

This custom version of the query language is possible because in our REST API, the query language is dynamically generated
based on [internal collection schemas](/documentation/user/en/use/schema.md#entity) to display only constraints that
can actually be used to query data (which also changes based on context of nested constraints). This also provides constraint arguments with data types that match
the internal data. This helps with the self-documentation because you don't necessarily need to know about
the domain model, since the [OpenAPI](https://swagger.io/specification/v3/) schema can be used to auto-complete the available constraints.

### Syntax of query and constraints

<MDInclude>[Syntax of query and constraints](/documentation/user/en/use/connectors/assets/dynamic-api-query-language-syntax.md)</MDInclude>

## Recommended usage

Our goal is to design the REST APIs to conform to the [RESTful best practices](https://restfulapi.net/), however this is
not always easy, especially when a rather generic database is under the hood, thus there some not so RESTful endpoints.
But ultimately, the REST APIs can be used like any other regular REST API, i.e. with
standard tools. However, below are our recommendations for tools etc. that we use at evitaDB.

### Recommended IDEs

For a desktop IDE to test and explore the REST APIs you can use [Insomnia](https://insomnia.rest/) or [Postman](https://www.postman.com/).
For generating documentation from the [OpenAPI schema](https://swagger.io/specification/v3/) we recommend either 
[Redocly](https://redocly.com/docs/cli/commands/preview-docs/) tool or official [Swagger Editor](https://github.com/swagger-api/swagger-editor) 
which can even generate clients for your programming language.

### Recommended client libraries

You can use any basic HTTP client your programming language supports. If you are looking for more, there are
code generators that can generate whole typed client in your preferable language from the evitaDB's OpenAPI schemas.
Official way of doing this is by using the [Swagger Codegen](https://swagger.io/tools/swagger-codegen/).