---
title: gRPC
perex:
date: '26.7.2023'
author: 'Ing. Tomáš Pozler'
---

This gRPC API was designed to provide a very powerful and unified way to control the evitaDB database from different programming languages, using the existing evitaDB [Java API](https://github.com/FgForrest/evitaDB/tree/dev/evita_api/src/main/java/io/evitadb/api). 
The main idea behind the design was to define a universal communication protocol that follows design of [Java API](https://github.com/FgForrest/evitaDB/tree/dev/evita_api/src/main/java/io/evitadb/api).
Unlike [REST](./rest.md) and [GraphQL](./graphql.md), this API is not intended for direct use by end users, but primarily as a building block for evitaDB drivers or similar tools created by database consumers.

# API structure
evitaDB is manipulated using the contracts exposed in [evita_api](https://github.com/FgForrest/evitaDB/tree/dev/evita_api/src/main/java/io/evitadb/api), specifically the <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass> and <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass> interfaces. 
The proposed gRPC API aims at defining its corresponding services with as little variation as possible, but in some cases it was necessary to adapt the model to possibilities of gRPC. 
In the design it was not possible to model generics, inheritance or lambda functions directly, but these problems could have been circumvented by adapting the defined models at the cost of redundant information. 
It is also not possible to call methods on instances of objects located on the server, such as sessions, which are used to perform most data manipulation and retrieval operations. 
But these problems can be solved by implementing rich frontend for the driver.
The use of these methods on the session object has been replaced by the need to specify the identifier of the user-created session, which must then be passed in the query metadata for all methods based on the EvitaSessionContract interface.
For example, the use of these methods on the session object has been replaced by the need to specify the identifier of the user-created session, which must then be passed in the query metadata for all methods based on the EvitaSessionContract interface. 
One way to simplify this process is to somehow globally store the session ID to be sent with every method call (example class <SourceClass>evita_external_api/evita_external_api_grpc/shared/src/main/java/io/evitadb/externalApi/grpc/interceptor/ClientSessionInterceptor.java</SourceClass>) and to them set it to metadata with every method call using a <SourceClass>evita_external_api/evita_external_api_grpc/shared/src/main/java/io/evitadb/externalApi/grpc/interceptor/ClientSessionInterceptor.java</SourceClass>.

gRPC works on the principle of Remote Method Calling (RPC), i.e. the client uses methods defined in protobuf services in its code and their implementation is called on the server, where the request is processed and the response is sent to the client. 
For the above-mentioned contracts <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass> and <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass> there are corresponding services in the form of <SourceClass>evita_external_api/evita_external_api_grpc/server/src/main/java/io/evitadb/externalApi/grpc/services/EvitaService.java</SourceClass> and <SourceClass>evita_external_api/evita_external_api_grpc/server/src/main/java/io/evitadb/externalApi/grpc/services/EvitaSessionService.java</SourceClass> with the included methods supporting same functionality as the base [Java API](https://github.com/FgForrest/evitaDB/tree/dev/evita_api/src/main/java/io/evitadb/api), although in so convenient way due to the gRPC protocol limitations.

## Protocol buffers
Protocol buffers have already been mentioned above, and there has been some discussion of how they are used. 
However, it should be made clear that this mechanism is not an alternative to JSON/XML formats into which data can be serialised. 
It is a language-neutral and platform-neutral way of defining the form of data through messages using supported data types and structures. 
Objects corresponding to type-defined messages can be serialised into a stream of bytes using the appropriate library. 
When used in combination with the gRPC library, there is no need to manipulate protobuf files directly, in addition to compiling protobuf files into native classes and serialising objects, serialisation is handled implicitly by the gRPC library.

All protobuf files that define the gRPC protocol can be found [here](https://github.com/FgForrest/evitaDB/tree/dev/evita_external_api/evita_external_api_grpc/shared/src/main/resources/META-INF/io/evitadb/externalApi/grpc). Below is an example of the <SourceClass>evita_external_api/evita_external_api_grpc/server/src/main/java/io/evitadb/externalApi/grpc/services/EvitaSessionService.java</SourceClass> design and few selected procedures in the protobuf file <SourceClass>evita_external_api/evita_external_api_grpc/shared/src/main/resources/META-INF/io/evitadb/externalApi/grpc/GrpcEvitaSessionAPI.proto</SourceClass>:

```protobuf
service EvitaSessionService {
  rpc GoLiveAndClose(google.protobuf.Empty) returns (GrpcGoLiveAndCloseResponse);
  rpc Query(GrpcQueryRequest) returns (GrpcQueryResponse);
}
```

together with the definition of the messages used, except for the [Empty](https://protobuf.dev/reference/protobuf/google.protobuf/#empty) type, which comes from [the collection of standard message types](https://protobuf.dev/reference/protobuf/google.protobuf/) that are part of the protocol and are therefore included in the library.

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
Although gRPC provides some backward compatibility, there is no guarantee that the client will be able to communicate with the server if the protocol is not the same.
Unchanged RPCs should work even if the protocol is not the same, any changes or additions will not work.
</Note>

# Querying the database
One of the design challenges was to find a way to represent database queries using protobuf capabilities.
We chose to use parameterised string form, where the parameters can be replaced by the `?` symbol, and a list of parameters can be passed with such a query. 
An alternative is to use named parameters with the `@` prefix, which are used with a unique name in the query and are also embedded in the map as keys, where each named parameter has a corresponding value. 
In this form, the user passes the query to the server, which processes it with the query parser mentioned above, creating an object representing the requested query, ready for execution by the database.

```java
final List<QueryParam> params = new ArrayList<>();
params.add(QueryConverter.convertQueryParam("Product"));
params.add(QueryConverter.convertQueryParam(Currency.getInstance("USD")));
params.add(QueryConverter.convertQueryParam("vip"));
params.add(QueryConverter.convertQueryParam("basic"));
params.add(QueryConverter.convertQueryParam("PARAMETER"));
params.add(QueryConverter.convertQueryParam(1));
params.add(QueryConverter.convertQueryParam(1));
params.add(QueryConverter.convertQueryParam(20));
params.add(QueryConverter.convertQueryParam(FacetStatisticsDepth.IMPACT));
params.add(QueryConverter.convertQueryParam("code"));

final String stringQuery = """
    query(
        collection(?),
        filterBy(
            and(
                priceInCurrency(?),
                priceInPriceLists(?, ?),
                userFilter(
                    facetHaving(?, entityPrimaryKeyInSet(?))
                )
            )
        ),
        require(
            page(?, ?),
            facetSummary(?,entityFetch(priceContentRespectingFilter(), attributeContent(?)),entityGroupFetch())
        )
     )
     """;

final GrpcQueryResponse response = evitaSessionBlockingStub.query(GrpcQueryRequest.newBuilder()
    .setQuery(stringQuery)
    .addAllPositionalQueryParams(params)
    .build());
```

Executing queries is the main purpose of evitaDB and as you can see from the example above, it is certainly not the most pleasant way to work with the database.
There is no form of intellisense or query validation available (so far, we don't have any tools for IDEs that would eliminate this drawback), so we recommend and emphasise the fact that for optimal work with the gRPC API it is advisable to either use one of our implemented drivers or to create your own tool, at least to facilitate querying.

# Recommended usage
Our gRPC API primarily serves as a protocol for evitaDB drivers, so it is not designed for a user-friendly experience. 
If your use case allows it, we recommend using one of our official drivers or other user-focused APIs to get the most comfortable and efficient database experience. 
Otherwise, we recommend that you create your own intermediate layer between your application and the gRPC API to suit your purposes and simplify your programming and database usage. 
In this case, however, we recommend that you study our EvitaClient driver, which has many things to inspire you for better performance, such as how to handle channel pooling or how to create and use schema caches.

It is also important not to forget about TLS certificates, which need to be set up correctly on both the server and the client side. 
gRPC communicates over HTTP/2 and data is sent over a TCP connection, and it is generally recommended to use TLS to encrypt bidirectional communication. 
Our API enforces this, so it is recommended that you read the [instructions](../../operate/tls.md) regarding TLS and set it up on the server if necessary. 
It is important to add that with TLS enabled, you must provide the certificate that the server presents when a connection is made, whether from code or a tool, or the connection will not be made.

## Recommended tools
It's important to note that gRPC is designed to be used from any of the supported programming languages using protobuf-based generated classes, optionally with a friendly IDE that provides benefits such as intellisense, so using it is pretty straightforward. 
For testing purposes without writing code, i.e. trying out the available services and calling the procedures they provide, it is possible to use classic API testing tools such as [Postman](https://www.postman.com/) or [Insomnia](https://insomnia.rest/), which already support communication with the gRPC API.
However, if you're new to gRPC, we recommend the [BloomRPC](https://github.com/bloomrpc/bloomrpc) tool, even though it's deprecated, because it's more intuitive and dedicated to this technology. 
Alternatively, there are many great alternatives for testing gRPC APIs, which can be found [here](https://github.com/grpc-ecosystem/awesome-grpc).

## Recommended libraries
You can find all the officially maintained libraries on the [gRPC](https://grpc.io) website, from which you can choose a library for your own programming language. 
Along with a library, you also need to download a suitable protocol [compiler](https://grpc.io/docs/protoc-installation/), which is not directly included in any of the libraries. 
It comes in the form of plugins, tools or completely separate libraries - it just depends on the language you are using.