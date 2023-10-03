---
title: Monitor
perex:
date: '17.1.2023'
author: 'Ing. Jan Novotný'
---

**Work in progress**

This article will contain description of Evita monitoring facilities - would it be directly Prometheus or OpenTelemetry.
There should be also information how to log slow queries or see other problems within application (logging).
The functionality is not finalized - [see issue #18](https://github.com/FgForrest/evitaDB/issues/18).


## Client and request identification

In order to monitor which requests each client executes against evitaDB, each client and each request can be identified by
a unique identifier. In this way, evitaDB calls can be grouped by requests and clients. This may be useful, for example, 
to see if a particular client is executing queries optimally and not creating unnecessary duplicate queries.

Both identifiers are provided by the client itself. The client identifier is expected to be a constant for a particular
client, e.g. `Next.js application`, and will group together all calls to a evitaDB from this client.
The request identifier is expected to be a [UUID](https://en.wikipedia.org/wiki/Universally_unique_identifier)
but can be any string value, and will group together all evitaDB calls with this request identifier for a particular client. 
The request definition (what a request identifier represents) is up to the client to decide, for example, a single request 
for JavaScript client may group together all evitaDB calls for a single page render.

### Usage

<LanguageSpecific to="evitaql">

This mechanism is not part of an evitaQL language. Check documentation for your specific client for more information.

</LanguageSpecific>
<LanguageSpecific to="java">

If you are using the Java remote client, you are suggested to provide the `clientId` in
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/config/EvitaClientConfiguration.java</SourceClass>
for all requests. The `requestId` is then provided by wrapping your code in a lambda passed to `executeWithRequestId`
method on <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass> interface.

<SourceCodeTabs langSpecificTabOnly>

[Provide the client and request ids to the server](/documentation/user/en/operate/example/call-server-with-ids.java)
</SourceCodeTabs>

If you use embedded variant of evitaDB server there is no sense to provide `clientId` since there is only one client.
The `requestId` is then provided the same way as described above.

</LanguageSpecific>
<LanguageSpecific to="graphql">

To pass the request identification using GraphQL API, our GraphQL API utilizes [GraphQL extensions](https://github.com/graphql/graphql-over-http/blob/main/spec/GraphQLOverHTTP.md#request-parameters).
Therefore, to pass request identification information to the evitaDB, pass the following JSON object within the `extensions`
property of a GraphQL request:

```json
"clientContext": {
  "clientId": "Next.js application",
  "requestId": "05e620b2-5b40-4932-b585-bf3bb6bde4b3"
}
```

Both identifiers are optional.

</LanguageSpecific>
<LanguageSpecific to="rest">

In order to pass request identification using REST API, our REST API utilizes [HTTP headers](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers).
Therefore, to pass request identification information to the evitaDB, pass the following HTTP headers:

```
X-EvitaDB-ClientID: Next.js application
X-EvitaDB-RequestID: 05e620b2-5b40-4932-b585-bf3bb6bde4b3
```

Both headers are optional.

</LanguageSpecific>