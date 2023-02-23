# Developing external APIs

_External API_ is a term for those APIs that enable a communication with evitaDB remotely (usually via the HTTP protocol),
and thus, they all should support possibly all features of the core evitaDB in a way that particular API is supposed to be designed in.

There currently 4 implementations of such APIs:

- [GraphQL](graphql-api.md)
- [REST](rest-api.md)
- [gRPC](grpc-api.md)
- Java Driver (user-friendly wrapper for Java gRPC client)

All of these APIs are settled in module `evita_external_api` with an additional core module `evita_external_api_core` which
contains common classes for all or in some cases at least for several APIs.

Each external API has its own module which contains all classes needed for providing the API. 

## Data API and schema API

Both GraphQL and REST APIs (as well as core) are split into data API and schema API for more readable code and to
prevent naming collisions. The data API part includes queries and mutations for actual evitaDB stored data.
On the other hand the schema API includes queries and mutations for handling internal schemas of evitaDB.

## External API core

Mainly contains [HTTP server](#api-server) implementation, common serialization utils, exception basis, 
configuration basis and common descriptors and converters of API model.
It is also responsible for starting up the API server with all of implemented APIs.

There should be only classes that are generic and used by several APIs.

### API server

The HTTP server (`io.evitadb.externalApi.http.ExternalApiServer`) is implemented using the 
[Undertow server](https://undertow.io/documentation.html) and is responsible
for initializing an HTTP communication base as well as starting up all registered APIs. The server is configured
using the `io.evitadb.externalApi.configuration.ApiOptions` which contains common server options and specific options
for each API.

The server first loads all APIs (each represented by the `io.evitadb.externalApi.http.ExternalApiProviderRegistrar`) 
to be registered using `java.util.ServiceLoader`. Found registrars are executed and their resulted providers are
stored in server's memory for further initialization and API lifecycle management.

Then, the server initializes all needed HTTP listeners for registered APIs. The server has support for initializing and
managing multiple HTTP/HTTPS listeners for APIs, because each API may
need separate port or host or both (or end-user can define so). Each such listener has its own router 
(`io.undertow.server.handlers.PathHandler`) into which APIs own routers are automatically registered.

todo tpo ssl support

### API model

Because evitaDB has a rich data model which we need to somehow represent in the APIs, a relatively simple "descriptor" framework
has been developed to complexly and universally describe the evitaDB data model for generated API schemas. 

A "descriptor" is an object that describes a part of a model entity. There are several types of such descriptors, each
is responsible for describing different part:

- `io.evitadb.externalApi.api.catalog.model.ObjectDescriptor`
  - describes whole model entity object
  - usually has linked several other descriptors (fields, ...)
- `io.evitadb.externalApi.api.catalog.model.PropertyDescriptor`
  - describes fields (or arguments of fields) of a model entity
- `io.evitadb.externalApi.api.catalog.model.EndpointDescriptor`
  - describes action endpoints of an API (in REST they are actual endpoints, in GraphQL they are represented by special fields)

Each descriptor has its own transformer interface to be implemented by an API to transform described model to API-specific
schema. The idea behind the transformers is to automate transformation of model to API-specific schema as much as possible.

#### Describing model entity

To describe model entity, recommended approach is to create new interface with name of entity suffixed by `Descriptor` keyword.
In this interface, there should be statically defined all field descriptors of the entity and one object descriptor
named `THIS` (optionally even with suffix, if there are multiple versions of same object) describing the entity
and potentially linking static field descriptors. This is useful for self-documentation of the model and can automate
building schema in API using above-mentioned transformers.

Described entities can reference each other using type descriptors, which further helps with automation of schema generation.

When it comes to structuring entity descriptors, the structure should copy the base evitaDB core API structure, so that is
easier to find original classes (same goes for actual API modules).

### Query constraint schema

Because query language and its constraints are quite complex (because of its tree structure) and we want to simplify
querying for end-users by showing only those constraints in specific contexts which make sense as much as possible without losing functionality,
the [annotation framework has been developed](../query/query_constraint_description_framework.md) which has similar purpose
as the model description framework above. But because building API-specific schema for whole query langauge by hand
would be unrealistic, automated generic builder has been built. This `io.evitadb.externalApi.api.catalog.dataApi.builder.constraint.ConstraintSchemaBuilder`
is responsible for going through existing constraints and their rules and constructing them together into tree schema.
The builder is constructed in such way that it doesn't care about API's serialization form, this is responsibility of 
an API implementation of the builder.

Important parts to mention are `io.evitadb.externalApi.api.catalog.dataApi.builder.constraint.CachableElementKey` for
caching and reusing built objects by the builder, `io.evitadb.externalApi.api.catalog.dataApi.builder.constraint.ConstraintSchemaBuildingContext` 
that mainly holds shared data across multiple builder implementations and instances to share cached objects.
For correctly changing context of domains while building the constraint tree, `io.evitadb.externalApi.api.catalog.constraint.DataLocator`
classes can hold locator of different evitaDB data schemas to locate data (attributes, references, ...) while building
API-specific schemas.

For parsing query inputs conforming to the constraint schema, the `io.evitadb.externalApi.api.catalog.resolver.data.constraint.ConstraintResolver`
has been developed. This resolver uses same constraint schema rules as the above-mentioned builder, and thus if there
are any changes to those rules, both the builder and the resolver must be correctly updated.
The resolver is generic one (same as with the builder) and APIs have to implement it to provide their own serialization
rules (same as in the builder).

## Creating new API

Creating base for a brand-new API and registering it is quite simple. There is even support for APIs that can't use
the main HTTP server because they come with their own servers (like gRPC).

There are 3 main classes that each API have to implement in order to be registered, rest is up to the API implementer.
The main interface is `io.evitadb.externalApi.http.ExternalApiProvider`, and it is basically descriptor of an API. It contains
an identification info, can have its own router to be registered in the main HTTP server, and it can register callbacks
on start and stop actions of the main HTTP server (which is useful e.g. to start and stop API's own server).
The second interface is basically factory for the provider class `io.evitadb.externalApi.http.ExternalApiProviderRegistrar`
which is being called when the main HTTP server is being started, and it is provided with client configurations to create
and configure the provider from it.
Finally, the `io.evitadb.externalApi.configuration.AbstractApiConfiguration` has to be implemented to provide
API-specific client configuration options for the factory. This class is automatically parsed from
client's YAML configuration during the evitaDB initialization.

### Registering API

To tell the Undertow server to load the new API, the factory class must be specified in resources in file 
`META-INF/services/io.evitadb.externalApi.http.ExternalApiProviderRegistrar` in API's module. Also, because we use 
Java modules, the factory class must be registered in `module-info.java` with:

```
provides ExternalApiProviderRegistrar with io.evitadb.externalApi.myApi.MyApiProviderRegistrar;
```