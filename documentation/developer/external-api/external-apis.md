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

The HTTP server
(<SourceClass>evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/http/ExternalApiServer.java</SourceClass>)
is implemented using the [Armeria server](https://armeria.dev/docs/server-basics) and is responsible
for initializing an HTTP communication base as well as starting up all registered APIs. The server is configured
using the
<SourceClass>evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/configuration/ApiOptions.java</SourceClass>
which contains common server options and specific options for each API.

The server first loads all APIs (each represented by the
<SourceClass>evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/http/ExternalApiProviderRegistrar.java</SourceClass>)
to be registered using `java.util.ServiceLoader`. Found registrars are executed and their resulted providers are
stored in server's memory for further initialization and API lifecycle management.

Then, the server initializes all needed HTTP listeners for registered APIs. The server has support for initializing and
managing multiple HTTP/HTTPS listeners for APIs, because each API may
need separate port or host or both (or end-user can define so). A service that routes requests by itself
(`PathHandlingMode.FIXED_PATH_HANDLING`, e.g. gRPC) is registered directly on the Armeria server. The other services
(`PathHandlingMode.DYNAMIC_PATH_HANDLING`) are registered under their path prefix in a
<SourceClass>evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/http/routing/PrefixRoutingService.java</SourceClass>,
which can add, replace and remove prefixes at runtime without disturbing the other ones; APIs dispatch their own
endpoints through an immutable
<SourceClass>evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/http/routing/EndpointRoutingService.java</SourceClass>.

### API model

Because evitaDB has a rich data model which we need to somehow represent in the APIs, a relatively simple "descriptor" framework
has been developed to complexly and universally describe the evitaDB data model for generated API schemas.

A "descriptor" is an object that describes a part of a model entity. There are several types of such descriptors, each
is responsible for describing different part:

- <SourceClass>evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/api/model/ObjectDescriptor.java</SourceClass>
  - describes whole model entity object
  - usually has linked several other descriptors (fields, ...)
- <SourceClass>evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/api/model/PropertyDescriptor.java</SourceClass>
  - describes fields (or arguments of fields) of a model entity
- <SourceClass>evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/api/model/EndpointDescriptor.java</SourceClass>
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

#### Linking models

You can easily define links to other models in properties. Property descriptor supports either Java class type or
reference to other model defined by its own descriptor. You can leverage the latter as so:

```java
PropertyDescriptor UNIQUE_GLOBALLY_IN_SCOPES = PropertyDescriptor.builder()
	.name("uniqueGloballyInScopes")
	.description("...")
	.type(nonNullListRef(ScopedGlobalAttributeUniquenessTypeDescriptor.THIS))
	.build();
```

This way a property transformer automatically creates a proper reference in target API to that model.
However, keep in mind, that the target model must be manually registered into the API schema. The property transformer
only handles the reference.

### Query constraint schema

In order to provide better DX when constructing input queries by providing them with documentation and code completion,
entire subsystem has been developed to accommodate for it. In the most basic form, there is
a builder responsible for generating API schema for all possible query constraint combinations, and then there is
a resolver that can parse and convert the input query into the original Java query that the evitaDB engine can process.
Not all APIs use this subsystem, but currently all APIs using JSON documents use this subsystem.

More details about the subsystem are described [here](/documentation/developer/external-api/constraint-schema-api-subsystem.md).

### Mutations

Data and schema mutations are described by descriptor as well as any other model. However, because mutations are
input objects (unlike most of the other models which are output object handled by data fetchers), they need to converted
into evitaDB's structures.

For that, there are individual
<SourceClass>evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/api/resolver/mutation/MutationConverter.java</SourceClass>
implementations for each mutation and its aggregates. Each converter must convert mutation from raw key-value structure
into a specific evitaDB's mutation implementation class. For more convention implementation there is an abstraction on
top of the key-value structure
<SourceClass>evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/api/resolver/mutation/Input.java</SourceClass>
which handles of the common conversions and provides ways customize the non-common stuff. For example, to convert
nested objects, there are:

- <SourceClass>evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/api/resolver/mutation/PropertyObjectMapper.java</SourceClass>
- <SourceClass>evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/api/resolver/mutation/PropertyObjectListMapper.java</SourceClass>

Of course, you can define your own mapper for each value.

These core common converters, however, don't do anything on their own. It is responsibility of each API implementation
to hook these converters to its own workflow. Usually an API uses aggregates as a root of conversion.

#### Aggregates

There is an external API specific concept: aggregates. These group together individual mutations. This exists because
of JSON limitation of not being able to define object names other than having parent object with properties naming the
nested objects (similar problem is in the query language as well).

Aggregates usually copy base mutation interfaces from evitaDB's core API and lists all its implementations as their
possible object fields. Each aggregate must have descriptor and corresponding
<SourceClass>evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/api/resolver/mutation/MutationInputAggregateConverter.java</SourceClass>.

## Creating new API

Creating base for a brand-new API and registering it is quite simple. Every API runs on the main HTTP server; an API
that routes requests by itself (like gRPC) registers its service with `PathHandlingMode.FIXED_PATH_HANDLING`.

There are 3 main classes that each API have to implement in order to be registered, rest is up to the API implementer.
The main interface is
<SourceClass>evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/http/ExternalApiProvider.java</SourceClass>,
and it is basically descriptor of an API. It contains
an identification info and the HTTP services to be registered in the main HTTP server, and it can react to lifecycle
events of the main HTTP server (e.g. to emit observability events once all APIs are initialized, or to release API's
resources before the server stops).
The second interface is basically factory for the provider class
<SourceClass>evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/http/ExternalApiProviderRegistrar.java</SourceClass>
which is being called when the main HTTP server is being started, and it is provided with client configurations to create
and configure the provider from it.
Finally, the
<SourceClass>evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/configuration/AbstractApiOptions.java</SourceClass>
has to be implemented to provide
API-specific client configuration options for the factory. This class is automatically parsed from
client's YAML configuration during the evitaDB initialization.

### Registering API

To tell the API server to load the new API, the factory class must be specified in resources in file
`META-INF/services/io.evitadb.externalApi.http.ExternalApiProviderRegistrar` in API's module. Also, because we use
Java modules, the factory class must be registered in `module-info.java` with:

```
provides ExternalApiProviderRegistrar with io.evitadb.externalApi.myApi.MyApiProviderRegistrar;
```
