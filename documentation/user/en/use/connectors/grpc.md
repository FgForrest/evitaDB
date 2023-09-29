---
title: gRPC
perex: |
  The gRPC API was designed to provide a very powerful and unified way to control the evitaDB database from different 
  programming languages, using the existing evitaDB Java API.
date: '26.7.2023'
author: 'Ing. Tomáš Pozler'
---

The main idea behind the design was to define a universal communication protocol that follows design of [Java API](https://github.com/FgForrest/evitaDB/tree/dev/evita_api/src/main/java/io/evitadb/api).
Unlike [REST](rest.md) and [GraphQL](graphql.md), this API is not intended for direct use by end users / developers, but
primarily as a building block for evitaDB drivers or similar tools created for the database consumers.

# API structure

evitaDB is manipulated using the contracts exposed in [evita_api](https://github.com/FgForrest/evitaDB/tree/dev/evita_api/src/main/java/io/evitadb/api), 
specifically the <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass> and 
<SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass> interfaces.
The proposed gRPC API aims to define its corresponding services with as little variation as possible, but in some cases 
it was necessary to adapt the model to the capabilities of gRPC. In the design, it was not possible to model generics, 
inheritance, or lambda functions directly, but these problems could have been avoided by adapting the defined models at 
the cost of redundant information. 

It is also not possible to call methods on instances of objects located on the server, such as sessions, which are used
to perform most data manipulation and retrieval operations. However, these problems can be solved by implementing 
a driver library with a rich API. The use of these methods on the session object has been replaced by the need 
to specify the identifier of the user-created session, which must then be passed in the query metadata for all methods 
based on the EvitaSessionContract interface.

One way to simplify this process is to store the session ID in a shared memory scope (example class
<SourceClass>evita_external_api/evita_external_api_grpc/shared/src/main/java/io/evitadb/externalApi/grpc/interceptor/ClientSessionInterceptor.java</SourceClass>) 
and set it to the metadata with every method call using a <SourceClass>evita_external_api/evita_external_api_grpc/shared/src/main/java/io/evitadb/externalApi/grpc/interceptor/ClientSessionInterceptor.java</SourceClass>.
In addition to `sessionId`, additional identifiers can be set in the metadata for [monitoring](../../operate/monitor.md) purposes. 
Specifically, these are the `clientId` and `requestId` parameters, whose settings are used to add information about the 
client that uses the database in a given instance (for example, it can be the name of the application using evitaDB) and 
possibly an additional identifier for each query or method executed. Setting and using both of these parameters is 
completely optional.

gRPC works on the principle of Remote Method Calling (RPC), i.e. the client uses methods defined in protobuf services in
its code and their implementation is called on the server, where the request is processed and the response is sent back 
to the client. For the above-mentioned contracts <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass>
and <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass> there are corresponding
services in the form of <SourceClass>evita_external_api/evita_external_api_grpc/server/src/main/java/io/evitadb/externalApi/grpc/services/EvitaService.java</SourceClass> 
and <SourceClass>evita_external_api/evita_external_api_grpc/server/src/main/java/io/evitadb/externalApi/grpc/services/EvitaSessionService.java</SourceClass>
with the included methods supporting the same functionality as the base [Java API](https://github.com/FgForrest/evitaDB/tree/dev/evita_api/src/main/java/io/evitadb/api), but in a less convenient way
due to the limitations of the gRPC protocol.

## Protocol buffers

Protocol buffers are a way to define the structure of data through messages using supported data types and structures. 
They are not a replacement for JSON or XML formats used for data serialization, but rather a language-neutral and 
platform-neutral alternative.

The main benefit of using protocol buffers is that they allow you to represent data in a structured way. Objects that
match the defined message types can be serialized into a stream of bytes using the appropriate library.

When combined with the gRPC library, protocol buffers make the serialization process even more straightforward. You 
don't need to directly manipulate protobuf files, and the gRPC library takes care of compiling protobuf files into 
native classes and handling the serialization of objects automatically.

All the *protobuf* files that define the gRPC protocol can be found [in a META-INF folder](https://github.com/FgForrest/evitaDB/tree/dev/evita_external_api/evita_external_api_grpc/shared/src/main/resources/META-INF/io/evitadb/externalApi/grpc).
Below is an example of the <SourceClass>evita_external_api/evita_external_api_grpc/server/src/main/java/io/evitadb/externalApi/grpc/services/EvitaSessionService.java</SourceClass>
design and few selected procedures in the protobuf file <SourceClass>evita_external_api/evita_external_api_grpc/shared/src/main/resources/META-INF/io/evitadb/externalApi/grpc/GrpcEvitaSessionAPI.proto</SourceClass>:

```protobuf
service EvitaSessionService {
  rpc GoLiveAndClose(google.protobuf.Empty) returns (GrpcGoLiveAndCloseResponse);
  rpc Query(GrpcQueryRequest) returns (GrpcQueryResponse);
}
```

together with the definition of the used input/output messages 
(except for the [Empty](https://protobuf.dev/reference/protobuf/google.protobuf/#empty) type, which comes from
[the collection of standard message types](https://protobuf.dev/reference/protobuf/google.protobuf/) that are part of
the protocol and are therefore included in the library):

```protobuf
message GrpcGoLiveAndCloseResponse {
  bool success = 1;
}

message GrpcQueryRequest {
  string query = 1;
  repeated QueryParam positionalQueryParams = 2;
  map<string, QueryParam> namedQueryParams = 3;
}

message GrpcQueryResponse {
  GrpcDataChunk recordPage = 1;
  GrpcExtraResults extraResults = 2;
}
```

<Note type="warning">

All protobuf files that define the gRPC API protocol should always be synchronised between client and server.
Although gRPC provides some mechanisms for backward compatibility, there is no guarantee that the client will be able to 
communicate with the server if the protocol is not the same. Unchanged RPCs should work even if the protocol is not 
the same, any changes or additions will not work.

</Note>

# Querying the database

One of the design challenges was to find a way to represent database queries using protobuf capabilities. While it is 
possible to define a message type that represents a query and pass the serialized query in a binary representation, we 
decided to use the parameterized string form, where the parameters can be replaced by the `?` symbol, and a list of 
parameters can be passed with such a query. An alternative is to use named parameters with the `@` prefix, which are 
used with a unique name in the query and are also embedded in the map as keys, where each named parameter has 
a corresponding value. This form is well known in the developer community and is used by many database drivers - namely 
in the form of JDBC statements, or [named queries](https://www.baeldung.com/spring-jdbc-jdbctemplate#2-queries-with-named-parameters)
introduced by Spring framework.

In this form, the user submits the query to the server, which parses the string form and creates an object representing 
the requested query, ready for execution by the database.

<SourceCodeTabs requires="/documentation/user/en/use/connectors/examples/channel-and-session-creation.java">

[Example of creating gRPC channel and a service operating upon it and executing a query](/documentation/user/en/use/connectors/examples/grpc-client-query-call.java)
</SourceCodeTabs>

The example uses the `convertQueryParam` method from the <SourceClass>evita_external_api/evita_external_api_grpc/shared/src/main/java/io/evitadb/externalApi/grpc/query/QueryConverter.java</SourceClass> class. A similar method must be implemented in the gRPC client language to register
input parameters of a specific gRPC data type for the query.

The primary purpose of evitaDB is to execute queries. However, as shown in the example above, working with the database 
in this way is not the most user-friendly experience. Unfortunately, there is currently no support for intellisense or
query validation, and we haven't developed any IDE tools to address this limitation.

<Note type="info">

<NoteTitle toggles="false">

##### Is there a better way to call queries via the gRPC API?
</NoteTitle>

Yes, there is, and you will not see this kind of usage in our integration tests. Instead, we work with query in its
original type-safe form:

<SourceCodeTabs requires="/documentation/user/en/use/connectors/examples/channel-and-session-creation.java">

[Example of alternative gRPC query invocation](/documentation/user/en/use/connectors/examples/grpc-optimized-client-query-call.java)
</SourceCodeTabs>

However, this approach requires that the query language model be implemented in the target gRPC language, and this 
requires a significant amount of effort. We always recommend using appropriate client drivers, if they are available for 
the target language, as this shifts the developer experience to a higher level.

</Note>

# Recommended usage

To make the most of the gRPC API, we highly recommend using one of our implemented drivers or building your own tool to 
facilitate querying. This will significantly improve your experience with evitaDB. Direct use of the gRPC API by 
developers was not intended, and was designed to be more of a "machine-to-machine" interface.

When you start developing a driver for your preferred language, we recommend that you study the 
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass> driver,
which has many things to inspire you for better performance, such as how to handle channel pooling or how to create and
use schema caches.

It is also important not to forget about TLS certificates, which need to be set up correctly on both the server and
the client side. gRPC communicates over HTTP/2 and data is sent over a TCP connection, and it is generally recommended 
to use TLS to encrypt bidirectional communication. Our API enforces this, so it is recommended that you read
the [instructions](../../operate/tls.md) regarding TLS and set it up on the server if necessary.

If you are considering developing a custom driver, we invite you to reach out to us for support and guidance throughout 
the process. We are more than willing to provide the necessary feedback, recommendations, and assistance to ensure 
the seamless functioning of your driver. Your success is important to us, and we are committed to working closely with 
you to make your driver integration with our API a successful and rewarding experience. Don't hesitate to contact us.
We're here to help!

## Recommended tools

It's important to note that gRPC is designed to be used from any of the supported programming languages using 
protobuf-based generated classes, optionally with a friendly IDE that provides benefits such as intellisense, so using
it is pretty straightforward and there are a lot of resources and guides available on the web. 
For testing purposes without writing code, i.e. trying out the available services and calling the procedures they provide,
it is possible to use classic API testing tools such as [Postman](https://www.postman.com/) or 
[Insomnia](https://insomnia.rest/), which already support communication with the gRPC API.
However, if you're new to gRPC, we recommend the [BloomRPC](https://github.com/bloomrpc/bloomrpc) tool, even though it's 
deprecated, because it's more intuitive and dedicated to this technology. Alternatively, there are many great
alternatives for testing gRPC APIs, which can be found [here](https://github.com/grpc-ecosystem/awesome-grpc).

## Recommended libraries

You can find all the officially maintained libraries on the [gRPC](https://grpc.io) website, from which you can choose a library for
your own programming language. Along with a library, you also need to download a suitable protocol [compiler](https://grpc.io/docs/protoc-installation/), 
which is not directly included in any of the libraries. It comes in the form of plugins, tools or completely separate
libraries - it just depends on the language you are using.