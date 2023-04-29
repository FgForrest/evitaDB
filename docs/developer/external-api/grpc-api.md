# gRPC API

evitaDB's gRPC API unlike GraphQL or REST has static structure that copies core evitaDB API (embedded evitaDB API).
It isn't exact copy of the core evitaDB API because gRPC is quite limited compared to Java (generics, inheritance, lambdas, ...),
and thus the idea is to provide same functionality like in the core evitaDB API but without a user-friendliness.
It is more supposed to be a backbone of remote drivers (like our Java driver) than to use it directly.

Because of this and the fact that gRPC comes with `.proto` files that describes the API, the model descriptor framework
from core cannot be used, unfortunately.

## Modules

The gRPC API is split in 4 modules:

- `evita_external_api_grpc`
  - actual implemented API server
- `evita_external_api_shared`
  - shared classes (converters and so on) between API server and the Java driver
- `test`
  - gRPC related tests as workaround for Java modules because the gRPC Java library doesn't come with Java modules support
- `workaround`
  - pseudo module for "fixing" missing Java modules support of the gRPC library (as described in module description)

There is one last module `evita_java_driver` which is not part of the API server but uses a lot of same converters and
other classes from the shared module, and it is essentially wrapped gRPC client.

## Initialization

The gRPC Java library comes with custom HTTP server based on Netty, and thus it cannot run on the main HTTP server which runs
on Undertow. Therefore, the gRPC provider (`io.evitadb.externalApi.grpc.GrpcProvider`) is responsible for starting and
stopping the custom server set up by the `io.evitadb.externalApi.grpc.GrpcProviderRegistrar`. Actual server setup
is happening in the `io.evitadb.externalApi.grpc.utils.GrpcServer`.

## Extending the gRPC schema

All `.proto` files defining the gRPC schema are placed in shared module in `resources` at `META-INF/io/evitadb/externalApi/grpc`.
Individual gRPC messages should copy naming and structure of the core evitaDB API with prefix `Grpc` to distinguish them
from the core evitaDB API.
Java classes are automatically generated from these `.proto` files on Maven build (`mvn clean install`). These classes
are generated into shared module into package `io.evitadb.externalApi.grpc.generated`

To make the API alive, there is a set of services in server module at `io.evitadb.externalApi.grpc.services` which
copy the `io.evitadb.core.Evita` and `io.evitadb.core.EvitaSession` objects of the core evitaDB API. These services
are trimmed only to the most basic needed functionality without most of the helper methods found in `io.evitadb.core.EvitaSession`
or `io.evitadb.core.Evita`.

## Data types support

evitaDB has wide range of custom data types. All these data types are supported by gRPC is some form.
Main converted that supports converting Java classes into gRPC enums and actual values is `io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter`.
Definitions of these enums and value messages are defined in `META-INF/io/evitadb/externalApi/grpc/GrpcEvitaDataTypes.proto`.

## Converters

There is large number of converters for almost all the core evitaDB API classes. This is because we cannot use
gRPC generated classes in the core evitaDB, therefore everything has to be converted from gRPC objects to evitaDB
objects (same goes for the Java driver, where all Java objects have to be converted into gRPC objects).

Actual converters usually support both conversion ways and are placed in same packages as objects they are converting
(just in different module).

## Query language

Unlike in the GraphQL or REST APIs, there isn't first class support for query constraints directly in the gRPC API schema.
Query constraints have quite a complex tree structure (inheritance, different constructors, and so on) and gRPC
doesn't support needed tools for building equivalent framework of constraints. Instead, the [query language parser](../query/query_language_parser.md)
is used to pass queries between clients and server via strings. However, there is custom 
`io.evitadb.externalApi.grpc.query.GrpcConverter` in shared module which is responsible for preparing arguments for such queries
because they are sent separately to actual queries (just like in SQL). There is also helper class
`io.evitadb.externalApi.grpc.utils.QueryUtil` in server module to further simplify query converting from strings.

## SSL support

todo tpo docs