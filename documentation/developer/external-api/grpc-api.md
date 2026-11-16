# gRPC API

evitaDB's gRPC API unlike GraphQL or REST has static structure that copies core evitaDB API (embedded evitaDB API).
It isn't exact copy of the core evitaDB API because gRPC is quite limited compared to Java (generics, inheritance, lambdas, ...),
and thus the idea is to provide same functionality like in the core evitaDB API but without a user-friendliness.
It is more supposed to be a backbone of remote drivers (like our Java driver) than to use it directly.

Because of this and the fact that gRPC comes with `.proto` files that describes the API, the model descriptor framework
from core cannot be used, unfortunately.

## Modules

The gRPC API is split in 2 modules (both in the `evita_external_api/evita_external_api_grpc` folder):

- `evita_external_api_grpc` (folder `server`)
  - actual implemented API server
- `evita_external_api_grpc_shared` (folder `shared`)
  - shared classes (converters and so on) between API server and the Java driver

The same folder contains two more modules which are not part of the API server: `evita_java_driver` (folder `client`)
uses a lot of same converters and other classes from the shared module, and it is essentially wrapped gRPC client,
and `evita_java_driver_observability` (folder `client_observability`) adds observability context to the Java driver.

## Initialization

The gRPC API runs on the main Armeria HTTP server. The
<SourceClass>evita_external_api/evita_external_api_grpc/server/src/main/java/io/evitadb/externalApi/grpc/GrpcProviderRegistrar.java</SourceClass>
builds an Armeria `GrpcService` with all evitaDB gRPC services, and the gRPC provider
(<SourceClass>evita_external_api/evita_external_api_grpc/server/src/main/java/io/evitadb/externalApi/grpc/GrpcProvider.java</SourceClass>)
registers it (and the optional gRPC documentation service) as a service that routes requests by itself
(`PathHandlingMode.FIXED_PATH_HANDLING`).

## Extending the gRPC schema

All `.proto` files defining the gRPC schema are placed in shared module in
<SourceClass>evita_external_api/evita_external_api_grpc/shared/src/main/resources/META-INF/io/evitadb/externalApi/grpc/</SourceClass>.
Individual gRPC messages should copy naming and structure of the core evitaDB API with prefix `Grpc` to distinguish them
from the core evitaDB API.
Java classes are automatically generated from these `.proto` files on Maven build (`mvn clean install`). These classes
are generated into shared module into package
<SourceClass>evita_external_api/evita_external_api_grpc/shared/src/main/java/io/evitadb/externalApi/grpc/generated/</SourceClass>.

To make the API alive, there is a set of services in server module at
<SourceClass>evita_external_api/evita_external_api_grpc/server/src/main/java/io/evitadb/externalApi/grpc/services/</SourceClass>
which copy the <SourceClass>evita_engine/src/main/java/io/evitadb/core/Evita.java</SourceClass> and
<SourceClass>evita_engine/src/main/java/io/evitadb/core/session/EvitaSession.java</SourceClass> objects of the core
evitaDB API. These services are trimmed only to the most basic needed functionality without most of the helper methods
found in <SourceClass>evita_engine/src/main/java/io/evitadb/core/session/EvitaSession.java</SourceClass> or
<SourceClass>evita_engine/src/main/java/io/evitadb/core/Evita.java</SourceClass>.

## Data types support

evitaDB has wide range of custom data types. All these data types are supported by gRPC is some form.
Main converted that supports converting Java classes into gRPC enums and actual values is
<SourceClass>evita_external_api/evita_external_api_grpc/shared/src/main/java/io/evitadb/externalApi/grpc/dataType/EvitaDataTypesConverter.java</SourceClass>.
Definitions of these enums and value messages are defined in
<SourceClass>evita_external_api/evita_external_api_grpc/shared/src/main/resources/META-INF/io/evitadb/externalApi/grpc/GrpcEvitaDataTypes.proto</SourceClass>.

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
<SourceClass>evita_external_api/evita_external_api_grpc/shared/src/main/java/io/evitadb/externalApi/grpc/query/QueryConverter.java</SourceClass>
in shared module which is responsible for preparing arguments for such queries because they are sent separately to
actual queries (just like in SQL). There is also helper class
<SourceClass>evita_external_api/evita_external_api_grpc/server/src/main/java/io/evitadb/externalApi/grpc/utils/QueryUtil.java</SourceClass>
in server module to further simplify query converting from strings.

## SSL support

TLS and mTLS of the gRPC API are handled by the main Armeria HTTP server, the same way as for the other APIs, and are
configured by the `tlsMode` and `mTLS` settings of the `gRPC` endpoint. See
[TLS Configuration and Principles](/documentation/user/en/operate/tls.md) for details.
