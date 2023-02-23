# GraphQL API

evitaDB's GraphQL API is dynamically built based on evitaDB's internal data schemas, thus GraphQL API schema of different
catalogs can be quite different. This way was chosen to simplify life for end-users as much as possible by providing 
API specific to their domains. This helps with intuitiveness and fewer errors.

The GraphQL API uses the [GraphQL Java](https://www.graphql-java.com/) library as it is the most used one and provides
a lot of support for building custom schemas programmatically.
Each evitaDB catalog has its own instance of GraphQL with its own schema related to the catalog's
schema and URL path. This is mainly to separate contexts as designed by evitaDB.

## Initialization

Initialization of GraphQL API is done by `io.evitadb.externalApi.graphql.GraphQLManager` executed by the
`io.evitadb.externalApi.graphql.GraphQLProviderRegistrar`. This manager handles initializing of new GraphQL instances for 
catalogs, catalog updates, as well as maintaining the HTTP path router for all of those initialized GraphQL instances.
Actual GraphQL instance building for specific catalog is delegated to `io.evitadb.externalApi.graphql.api.catalog.CatalogGraphQLBuilder`
that further delegates schema building to `io.evitadb.externalApi.graphql.api.catalog.builder.CatalogSchemaBuilder`.

## Extending a GraphQL schema

As mentioned above, `io.evitadb.externalApi.graphql.api.catalog.builder.CatalogSchemaBuilder` is the root of the GraphQL schema
building process. This builder delegates different parts of schema to different `io.evitadb.externalApi.graphql.api.builder.GraphQLSchemaBuilder`s
for better code readability. 
Each implementation of the partial schema builder is structured in following way:

- first, common types are built and registered
- then root fields (GraphQL queries or mutations) are built
- each field then usually calls some object building methods which can call other field building methods and so on

The idea is to structure building methods to reflect an order of actual schema fields and objects for better orientation.

Anyway, to add/remove/modify new field or object simply build and register it using the provided 
`io.evitadb.externalApi.graphql.api.builder.GraphQLSchemaBuildingContext` or 
`io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.EntitySchemaGraphQLSchemaBuildingContext`. Usually, when new
fields/objects are being added, [descriptors in code module](external-apis.md#describing-model-entity) must be added too.

The GraphQL API tries to automate building the schema as much as possible using the descriptor transformers which can 
automatically build whole objects if those objects have static structure. The only things that is needed in such case is
to call the transformers in schema builders.

To make the API alive, [data fetchers](https://www.graphql-java.com/documentation/data-fetching) must be implemented for
the root fields as well. All data fetchers are placed in `io.evitadb.externalApi.graphql.api.catalog.resolver` and must
be registered during the schema building process to respective fields.

### Query constraint schema

The GraphQL API uses the [query constraint schema support](external-apis.md#query-constraint-schema) from core module
and combines it with the GraphQL library capabilities. All implementations of those builders are placed at 
`io.evitadb.externalApi.graphql.api.catalog.builder.data.constraint` and respective resolvers are placed at 
`io.evitadb.externalApi.graphql.api.catalog.resolver.data.constraint`. Usually, mild changes to the core support
shouldn't break the implementations, but bigger structural changes may require reimplementation of API's side of things as
well.

## Data types support

evitaDB has wide range of custom data types. All these data types are supported by GraphQL in some form and are defined
as GraphQL scalars in the `io.evitadb.externalApi.graphql.dataType.GraphQLScalars`. For conversion between evitaDB classes 
and scalars of GraphQL, there is the `io.evitadb.externalApi.graphql.dataType.DataTypesConverter`.

## Exceptions

The GraphQL API extends the base evitaDB internal and client exceptions with specific exceptions for different GraphQL
processes. All custom exceptions can be found at `io.evitadb.externalApi.graphql.exception` and it is highly recommended
to use them across the GraphQL API because there is custom `io.evitadb.externalApi.graphql.io.GraphQLExceptionHandler` 
catching all exceptions thrown during GraphQL query handling to correctly handle such exceptions.