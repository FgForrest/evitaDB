---
title: Change data capture
perex: |
    Change data capture (CDC) is a design pattern used to track and capture changes made to schema and data in a database. evitaDB supports CDC through all its APIs, allowing developers to monitor and respond to data changes very easily in near real-time in their preferred programming language. This document explains how to implement CDC using our API.
date: '21.10.2025'
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'java'
---
The database maintains a so-called [Write-Ahead Log (WAL)](https://en.wikipedia.org/wiki/Write-ahead_logging) that records all changes made to the database. This log is used to ensure data integrity and durability, but it can (and actually is) also be leveraged to implement change data capture (CDC) functionality. Once the catalogue is switched to the `ACTIVE` (transactional) stage, clients can start consuming information about changes made to both the schema and the data in the catalogue.

There is also a special CDC available for the entire database engine that allows clients to monitor high-level operations such as catalogue creation, deletion, and other global events (for more details, consult the [Control Engine chapter](control-engine.md)).

<Note type="warning">

Change data capture is not available for catalogues in the `WARMING_UP` stage since the WAL is not being recorded during that phase.
This phase is considered "introductory" and clients should not work (query) with the data in that phase anyway. Clients should wait until the catalogue reaches the `ACTIVE` stage and perceive all the data at that moment as a consistent snapshot of the first version of the catalogue. 

</Note>

<Note type="info">

Engine and catalogue-level CDCs cannot be combined into a single stream since they operate on different levels (engine vs. catalogue). Catalogue-level CDC is always tied to a particular catalogue (name). If you need to capture all changes across all catalogues, you need to subscribe to engine-level CDC and then for each catalogue separately to catalogue-level CDC. The engine-level CDC notifies about catalogue creation/deletion events, so clients can dynamically subscribe/unsubscribe to catalogue-level CDCs as catalogues are created/deleted.

</Note>

The basic principle in all APIs is the same:

1. clients define a predicate/condition that specifies which changes they are interested in,
2. define a starting point in the form of a catalogue version from which they want to start receiving changes,
3. and subscribe to the change stream.

From that point onwards, clients will receive notifications about all changes that match their criteria. The changes are delivered in the order they were made, ensuring that clients can process them sequentially. The second step is optional — if no starting version is specified, the change stream will start from the next version of the catalogue.

## Hierarchy of mutations

Not all mutations operate on the same level and some mutations may encapsulate others. For example, when an entity is upserted, it may contain multiple mutations within it (multiple attribute, associated data, price operations etc.). The hierarchy of mutations is as follows:

- <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/EngineMutation.java</SourceClass> ([complete listing](control-engine.md), available in [engine change capture](#engine-change-capture))
    - <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/CatalogBoundMutation.java</SourceClass> ([complete listing](../schema.md), available in [catalog schema change capture](#catalogue-change-capture))
        - <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/LocalCatalogSchemaMutation.java</SourceClass>
            - <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/LocalEntitySchemaMutation.java</SourceClass>
                - <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/reference/ModifyReferenceAttributeSchemaMutation.java</SourceClass> 
    - <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/EntityMutation.java</SourceClass> ([complete listing](../data-model.md), available in [catalog data change capture](#catalogue-change-capture))
        - <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/LocalMutation.java</SourceClass>
- <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass> (available in all change capture streams)

When you don't specify any filtering criteria, you will receive all mutations in flattened form, i.e. you will receive all mutations regardless of their hierarchy. So, for example, an entity attribute upsert will be delivered once as part of the entity upsert mutation and once as a standalone attribute upsert mutation. In practice, a client usually wants either high-level information about entity changes (so only entity mutations) or very specific low-level changes (e.g. only changes to attributes of a particular name). The approach with a simple flattened stream that is filtered by a single predicate covers all these use cases very well, and it is very easy to understand and implement.

<LS to="g">

## GraphQL client setup

In order to consume the change capture streams, you need to set up a GraphQL client to send the subscription requests 
to the server via WebSockets using the [GraphQL over WebSocket](https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md) protocol. 
The WebSocket URLs are the same as for the query/mutation requests.

Each [API instance](/documentation/user/en/use/connectors/graphql.md#graphql-api-instances) provides specific subscriptions
for the API instance domain (described below); however, all the API instances provide some CDC subscriptions.

An important specific of the GraphQL implementation is that each subscription is provided in two variants:

- typed
- untyped

This exists because the GraphQL specification requires the client to specify all the desired output fields of each subscription. 
Which can be quite cumbersome in case of the mutations within the CDC streams because
there are tens of mutation implementations which all can be sent by the server. In traditional GraphQL API, the client
would have to specify all the fields of all the mutation implementations. This can be useful when the filtering
strategy focuses only on a specific set of mutation types, however, the client may need to support wide range of mutations
or even all of them. Therefore, we have implemented the above mentioned two version of each subscription.
The typed version fully complies with the GraphQL specification and requires the client to specify all
desired output fields for each mutation type (although there are some restrictive unions). 
The untyped version has the `body` with mutation set as generic `Object` type.
This way the client receives all the mutation types with all the data with a single output field. This comes with obvious drawbacks:
the client is responsible for mapping the JSON object and extracting the desired data. This should be used only if the client
really needs all the data.

</LS>
<LS to="r">

## REST client setup

In order to consume the change capture streams, you need to set up a WebSocket client to send the subscription requests
to the server using our [custom WebSocket protocol](/documentation/user/en/use/connectors/rest-over-websocket-protocol.md) based on the
[GraphQL over WebSocket](https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md) protocol.
The WebSocket URLs are the same as for the query/mutation requests.

<Note type="info">

<NoteTitle toggles="true">

##### REST over WebSocket protocol

</NoteTitle>

The OpenAPI specification doesn't directly specify
any standard for real-time updates APIs, nor is it possible to document one within the base OpenAPI specification. Therefore,
we have decided to create the [custom WebSocket procol](/documentation/user/en/use/connectors/rest-over-websocket-protocol.md)
based on the [GraphQL over WebSocket](https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md) protocol.
Although the base OpenAPI specification doesn't allow us to directly document the custom protocol, for now, we have
included the CDC types in the OpenAPI specification so that there is at least some building ground for the client developers
(e.g., mutation objects, CDC event objects, etc.).

</Note>

</LS>

<LS to="j,g,r">

## Engine change capture

<LS to="j,r">

<LS to="j">

The engine-level capture stream accepts <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeSystemCaptureRequest.java</SourceClass> 
for creating the [Java Flow Publisher](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Flow.Publisher.html). One or more clients may then subscribe to this publisher to receive 
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeSystemCapture.java</SourceClass> 
instances representing the changes made to the engine.

</LS>
<LS to="r">

The engine-level capture stream accepts <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeSystemCaptureRequest.java</SourceClass>
for creating the CDC stream. Clients then receive <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeSystemCapture.java</SourceClass>
instances representing the changes made to the engine.

</LS>

Request allows you to specify the following parameters:

<dl>
  <dt>long `sinceVersion` (optional)</dt>
  <dd>
    The catalogue version (inclusive) from which you want to start receiving changes. If not specified, the change stream will start from the next version of the catalogue (i.e. the changes made to the catalogue in the future).
  </dd>
  <dt>int `sinceIndex` (optional)</dt>
  <dd>
    The index of the mutation within the same transaction from which you want to start receiving changes. If not specified, the change stream will start from the first mutation of the specified version. The index allows you to precisely specify the starting point in case you have already processed some mutations of the specified version.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCaptureContent.java</SourceClass> `content`</dt>
  <dd>
    Enumeration that specifies whether the client wants detailed information about each mutation or only high-level information that a particular type of mutation occurred. The enumeration has the following values:
    <ul>
        <li>`HEADER` - only the header of the event is sent</li>
        <li>`BODY` - the entire body of the mutation triggering the event is sent</li>
    </ul>
  </dd>
</dl>

Engine capture events are represented by <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeSystemCapture.java</SourceClass> instances that contain the following information:

<LS to="j">

<dl>
  <dt>long `version`</dt>
  <dd>
    The version of the evitaDB where the mutation occurs.
  </dd>
  <dt>int `index`</dt>
  <dd>
    The index of the mutation within the same transaction. Index `0` is always infrastructure mutations of type <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass>.
  </dd>
  <dt>`operation`</dt>
  <dd>
    Classification of the mutation defined by enumeration:
    <ul>
        <li>`UPSERT` - Create or update operation. If there was data with such identity before, it was updated. If not, it was created.</li>
        <li>`REMOVE` - Remove operation - i.e. there was data with such identity before, and it was removed.</li>
        <li>`TRANSACTION` - Delimiting operation signaling the beginning of a transaction.</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/EngineMutation.java</SourceClass> `body` (optional)</dt>
  <dd>
    Optional body of the operation when it is requested by the requested <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCaptureContent.java</SourceClass>.
  </dd>
</dl>

</LS>
<LS to="r">

<dl>
  <dt>long `version`</dt>
  <dd>
    The version of the evitaDB where the mutation occurs.
  </dd>
  <dt>int `index`</dt>
  <dd>
    The index of the mutation within the same transaction. Index `0` is always infrastructure mutations of type <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass>.
  </dd>
  <dt>`operation`</dt>
  <dd>
    Classification of the mutation defined by enumeration:
    <ul>
        <li>`UPSERT` - Create or update operation. If there was data with such identity before, it was updated. If not, it was created.</li>
        <li>`REMOVE` - Remove operation - i.e. there was data with such identity before, and it was removed.</li>
        <li>`TRANSACTION` - Delimiting operation signaling the beginning of a transaction.</li>
    </ul>
  </dd>
  <dt>`EngineMutationUnion` `body` (optional)</dt>
  <dd>
    Optional body of the operation when it is requested by the requested <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCaptureContent.java</SourceClass>.
  </dd>
</dl>

</LS>

### How to set up a new engine change capture

<LS to="j">

Setup is quite straightforward and consists of three steps:

1. create [Java Flow Publisher](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Flow.Publisher.html) using <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass>
2. define subscriber implementing [Java Flow Subscriber](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Flow.Subscriber.html)
3. subscribe the subscriber to the publisher

Example of setting up the engine change capture in Java:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java,/documentation/user/en/use/api/example/finalization-of-warmup-mode.java" langSpecificTabOnly local>

[Setting up a minimal engine change capture](/documentation/user/en/use/api/example/engine-change-capture.java)

</SourceCodeTabs>

The subscriber will start receiving change events as soon as they occur in the engine. The subscriber's `onComplete` method is never called since the change stream is infinite.

</LS>
<LS to="r">

The engine-level capture stream is available in the system API via the `/rest/system/change-captures` endpoint.

Setup is quite straightforward:
1. open the WebSocket connection but sending `GET` request with connection upgrade,
2. send `connection_init` message within the WebSocket connection
3. send `subscribe` message within the WebSocket connection with a `ChangeSystemCaptureRequest` defining the filtering strategy (as specified in the [WebSocket specification](/documentation/user/en/use/connectors/rest-over-websocket-protocol.md)).

The CDC stream will now send `ChangeSystemCapture` objects wrapped into `next` messages to the client.

Example of setting up the engine change capture in REST over WebSocket API:

<SourceAlternativeTabs variants="rest">

[Setting up a minimal engine change capture](/documentation/user/en/use/api/example/engine-change-capture-rest.json)

</SourceAlternativeTabs>

The subscriber will start receiving change events as soon as they occur in the engine. The subscriber's `Complete` method is never called since the change stream is infinite.

</LS>
</LS>
<LS to="g">

The engine-level capture stream allows clients to subscribe to `ChangeSystemCapture` (or `GenericChangeSystemCapture` based on the chosen subscription type) 
instances representing the changes made to the engine.

Request allows you to specify the following parameters:

<dl>
  <dt>long `sinceVersion` (optional)</dt>
  <dd>
    The catalogue version (inclusive) from which you want to start receiving changes. If not specified, the change stream will start from the next version of the catalogue (i.e. the changes made to the catalogue in the future).
  </dd>
  <dt>int `sinceIndex` (optional)</dt>
  <dd>
    The index of the mutation within the same transaction from which you want to start receiving changes. If not specified, the change stream will start from the first mutation of the specified version. The index allows you to precisely specify the starting point in case you have already processed some mutations of the specified version.
  </dd>
</dl>

Engine capture events are represented by the `ChangeSystemCapture` (or `GenericChangeSystemCapture` based on the chosen subscription type) object that contains the following information:

<dl>
  <dt>long `version`</dt>
  <dd>
    The version of the evitaDB where the mutation occurs.
  </dd>
  <dt>int `index`</dt>
  <dd>
    The index of the mutation within the same transaction. Index `0` is always infrastructure mutations of type <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass>.
  </dd>
  <dt>`operation`</dt>
  <dd>
    Classification of the mutation defined by enumeration:
    <ul>
        <li>`UPSERT` - Create or update operation. If there was data with such identity before, it was updated. If not, it was created.</li>
        <li>`REMOVE` - Remove operation - i.e. there was data with such identity before, and it was removed.</li>
        <li>`TRANSACTION` - Delimiting operation signaling the beginning of a transaction.</li>
    </ul>
  </dd>
  <dt>`EngineMutationUnion` `body`</dt>
  <dd>
    Body of the operation.
  </dd>
</dl>

### How to set up a new engine change capture

The engine-level capture stream is available in the system API via the following subscription types:

- `onSystemChange`
- `onSystemChangeUntyped`

The setup is quite straightforward: simply define one subscription with the desired parameters and subscribe to the stream
via the WebSocket protocol. The WebSocket stream will then send the change events to the client based on the defined
output.

<SourceCodeTabs langSpecificTabOnly>

[Setting up a minimal engine change capture](/documentation/user/en/use/api/example/engine-change-capture.graphql)

</SourceCodeTabs>

The subscriber will start receiving change events as soon as they occur in the engine. The subscriber's `Complete` method is never called since the change stream is infinite.

</LS>

<Note type="info">

Currently, multiple engine mutations cannot be wrapped into a single transaction. Each engine operation is represented by a separate transaction mutation. So, you can expect that the engine mutation stream will always contain a transaction mutation, followed by a single top-level engine mutation.

</Note>


## Catalogue change capture

<LS to="j,r">

<LS to="j">

The catalogue-level capture stream accepts <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCaptureRequest.java</SourceClass>
for creating the [Java Flow Publisher](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Flow.Publisher.html). One or more clients may then subscribe to this publisher to receive 
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCapture.java</SourceClass> 
instances representing the changes made to the catalogue.

</LS>
<LS to="r">

The catalogue-level capture stream accepts <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCaptureRequest.java</SourceClass>
for creating the CDC stream. Clients then receive
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCapture.java</SourceClass>
instances representing the changes made to the catalogue.

</LS>

Request allows you to specify the following parameters:

<dl>
  <dt>long `sinceVersion` (optional)</dt>
  <dd>
    The catalogue version (inclusive) from which you want to start receiving changes. If not specified, the change stream will start from the next version of the catalogue (i.e. the changes made to the catalogue in the future).
  </dd>
  <dt>int `sinceIndex` (optional)</dt>
  <dd>
    The index of the mutation within the same transaction from which you want to start receiving changes. If not specified, the change stream will start from the first mutation of the specified version. The index allows you to precisely specify the starting point in case you have already processed some mutations of the specified version.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCaptureCriteria.java</SourceClass>[] `criteria` (optional)</dt>
  <dd>
    Array of criteria that specify which changes you are interested in. If not specified, all changes are captured. If multiple criteria are specified, matching any of them is sufficient (OR logic). Each criterion consists of:
    <ul>
        <li>`area` - the capture area (<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/CaptureArea.java</SourceClass>)</li>
        <li>`site` - the capture site (<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/CaptureSite.java</SourceClass>) providing fine-grained filtering</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCaptureContent.java</SourceClass> `content`</dt>
  <dd>
    Enumeration that specifies whether the client wants detailed information about each mutation or only high-level information that a particular type of mutation occurred. The enumeration has the following values:
    <ul>
        <li>`HEADER` - only the header of the event is sent</li>
        <li>`BODY` - the entire body of the mutation triggering the event is sent</li>
    </ul>
  </dd>
</dl>

Catalogue capture events are represented by <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCapture.java</SourceClass> instances that contain the following information:

<LS to="j">

<dl>
  <dt>long `version`</dt>
  <dd>
    The version of the catalogue where the mutation occurs.
  </dd>
  <dt>int `index`</dt>
  <dd>
    The index of the mutation within the same transaction. Index `0` is always infrastructure mutations of type <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass>.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/CaptureArea.java</SourceClass> `area`</dt>
  <dd>
    The area of the operation that was performed:
    <ul>
        <li>`SCHEMA` - changes in the schema are captured</li>
        <li>`DATA` - changes in the data are captured</li>
        <li>`INFRASTRUCTURE` - infrastructural mutations that are neither schema nor data</li>
    </ul>
  </dd>
  <dt>String `entityType` (optional)</dt>
  <dd>
    The name of the entity type that was affected by the operation. This field is null when the operation is executed on the catalog schema itself.
  </dd>
  <dt>Integer `entityPrimaryKey` (optional)</dt>
  <dd>
    The primary key of the entity that was affected by the operation. Only present for data area operations.
  </dd>
  <dt>`operation`</dt>
  <dd>
    Classification of the mutation defined by enumeration:
    <ul>
        <li>`UPSERT` - Create or update operation. If there was data with such identity before, it was updated. If not, it was created.</li>
        <li>`REMOVE` - Remove operation - i.e. there was data with such identity before, and it was removed.</li>
        <li>`TRANSACTION` - Delimiting operation signaling the beginning of a transaction.</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/CatalogBoundMutation.java</SourceClass> `body` (optional)</dt>
  <dd>
    Optional body of the operation when it is requested by the requested <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCaptureContent.java</SourceClass>.
  </dd>
</dl>

</LS>
<LS to="r">

<dl>
  <dt>long `version`</dt>
  <dd>
    The version of the catalogue where the mutation occurs.
  </dd>
  <dt>int `index`</dt>
  <dd>
    The index of the mutation within the same transaction. Index `0` is always infrastructure mutations of type <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass>.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/CaptureArea.java</SourceClass> `area`</dt>
  <dd>
    The area of the operation that was performed:
    <ul>
        <li>`SCHEMA` - changes in the schema are captured</li>
        <li>`DATA` - changes in the data are captured</li>
        <li>`INFRASTRUCTURE` - infrastructural mutations that are neither schema nor data</li>
    </ul>
  </dd>
  <dt>String `entityType` (optional)</dt>
  <dd>
    The name of the entity type that was affected by the operation. This field is null when the operation is executed on the catalog schema itself.
  </dd>
  <dt>Integer `entityPrimaryKey` (optional)</dt>
  <dd>
    The primary key of the entity that was affected by the operation. Only present for data area operations.
  </dd>
  <dt>`operation`</dt>
  <dd>
    Classification of the mutation defined by enumeration:
    <ul>
        <li>`UPSERT` - Create or update operation. If there was data with such identity before, it was updated. If not, it was created.</li>
        <li>`REMOVE` - Remove operation - i.e. there was data with such identity before, and it was removed.</li>
        <li>`TRANSACTION` - Delimiting operation signaling the beginning of a transaction.</li>
    </ul>
  </dd>
  <dt>`CatalogBoundMutationUnion` `body` (optional)</dt>
  <dd>
    Optional body of the operation when it is requested by the requested <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCaptureContent.java</SourceClass>.
  </dd>
</dl>

</LS>

### Capture areas and sites

Catalogue CDC distinguishes between three different **capture areas** that correspond to different types of operations:

#### Schema capture area

The schema capture area tracks changes to the catalogue schema and entity schemas. This includes operations like:

- Creating, updating, or removing entity schemas
- Modifying entity attributes, references, and associated data definitions
- Changing catalogue-level schema settings

The schema area uses <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/SchemaSite.java</SourceClass> for filtering, which allows you to specify:

<dl>
  <dt>String `entityType` (optional)</dt>
  <dd>
    Filter by specific entity type name. If not specified, changes to all entity types are captured.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/Operation.java</SourceClass>[] `operation` (optional)</dt>
  <dd>
    Filter by operation type. If not specified, all operations are captured. Possible values:
    <ul>
      <li>`UPSERT` - Create or update operation</li>
      <li>`REMOVE` - Remove operation</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ContainerType.java</SourceClass>[] `containerType` (optional)</dt>
  <dd>
    Filter by container type. If not specified, changes to all container types are captured. Possible values:
    <ul>
      <li>`CATALOG` - Catalogue-level schema changes</li>
      <li>`ENTITY` - Entity schema changes</li>
      <li>`ATTRIBUTE` - Attribute schema changes</li>
      <li>`ASSOCIATED_DATA` - Associated data schema changes</li>
      <li>`PRICE` - Price schema changes</li>
      <li>`REFERENCE` - Reference schema changes</li>
    </ul>
  </dd>
</dl>

#### Data capture area

The data capture area tracks changes to entity data within the catalogue. This includes operations like:

- Creating, updating, or removing entities
- Modifying entity attributes, references, and associated data values
- Updating prices and hierarchical placement

The data area uses <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/DataSite.java</SourceClass> for filtering, which allows you to specify:

<dl>
  <dt>String `entityType` (optional)</dt>
  <dd>
    Filter by specific entity type name. If not specified, changes to all entity types are captured.
  </dd>
  <dt>Integer `entityPrimaryKey` (optional)</dt>
  <dd>
    Filter by specific entity primary key. If not specified, changes to all entities are captured.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/Operation.java</SourceClass>[] `operation` (optional)</dt>
  <dd>
    Filter by operation type. If not specified, all operations are captured. Possible values:
    <ul>
      <li>`UPSERT` - Create or update operation</li>
      <li>`REMOVE` - Remove operation</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ContainerType.java</SourceClass>[] `containerType` (optional)</dt>
  <dd>
    Filter by container type. If not specified, changes to all container types are captured. Possible values:
    <ul>
      <li>`ENTITY` - Entity-level changes</li>
      <li>`ATTRIBUTE` - Attribute value changes</li>
      <li>`ASSOCIATED_DATA` - Associated data value changes</li>
      <li>`PRICE` - Price changes</li>
      <li>`REFERENCE` - Reference changes</li>
    </ul>
  </dd>
  <dt>String[] `containerName` (optional)</dt>
  <dd>
    Filter by specific container name (e.g., specific attribute name like `name`, `code`). If not specified, changes to all containers are captured.
  </dd>
</dl>

#### Infrastructure capture area

The infrastructure capture area tracks transaction-related and other infrastructural mutations that don't fit into schema or data categories. This includes:

- Transaction delimiting operations
- System-level operations

The infrastructure area does not use any capture site for filtering — currently, it captures all infrastructure mutations represented by <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass>.

<dl>
  <dt>No filtering parameters</dt>
  <dd>
    Infrastructure area captures all transaction and system-level mutations without any filtering options. To capture infrastructure mutations, specify `CaptureArea.INFRASTRUCTURE` in your criteria without a capture site.
  </dd>
</dl>

This area exists separately because transaction boundaries and system operations are orthogonal to both schema and data changes, and clients may need to track transaction boundaries independently for proper event grouping and consistency guarantees.

### How to set up a new catalogue change capture

Setting up catalogue change capture differs from engine change capture in that it operates on the catalogue level.

<LS to="j">

The setup consists of:

1. Open a session (read-only or read-write) to the catalogue
2. Call `registerChangeCatalogCapture` with <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCaptureRequest.java</SourceClass>
3. Process the returned stream of <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCapture.java</SourceClass> events

Example of retrieving catalogue change history in Java:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java,/documentation/user/en/use/api/example/finalization-of-warmup-mode.java" langSpecificTabOnly local>

[Setting up a minimal catalog change capture](/documentation/user/en/use/api/example/catalog-change-capture.java)

</SourceCodeTabs>

You can find also additional helpful examples in the below:

<Note type="info">

<NoteTitle toggles="true">

##### Retrieving transaction delimiters and changes for all entities of a specific type

</NoteTitle>

This publisher will deliver all transaction delimiters and all changes made to entities of type `Product` starting from 
the next version of the catalogue.

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java,/documentation/user/en/use/api/example/finalization-of-warmup-mode.java" langSpecificTabOnly local>

[Requesting entity level changes in transaction blocks](/documentation/user/en/use/api/example/capture-entity-mutations-with-transaction.java)

</SourceCodeTabs>

</Note>

<Note type="info">

<NoteTitle toggles="true">

##### Retrieving changes for attribute with name `quantityOnStock` of particular entity of type `Product`

</NoteTitle>

Following publisher will deliver all changes made to attribute `quantityOnStock` of entity type `Product` with primary 
key `745` starting from the next version of the catalogue.

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java,/documentation/user/en/use/api/example/finalization-of-warmup-mode.java" langSpecificTabOnly local>

[Requesting entity level changes](/documentation/user/en/use/api/example/capture-attribute-mutation.java)

</SourceCodeTabs>

</Note>

</LS>
<LS to="r">

The catalogue-level capture stream is available in the catalog API via the `/rest/{catalogName}/change-captures` endpoint.

Setup is quite straightforward:
1. open the WebSocket connection but sending `GET` request with connection upgrade
2. send `connection_init` message within the WebSocket connection
3. send `subscribe` message within the WebSocket connection with a `ChangeCatalogCaptureRequest` defining the filtering strategy (as specified in the [WebSocket specification](/documentation/user/en/use/connectors/rest-over-websocket-protocol.md)).

The CDC stream will now send `ChangeCatalogCapture` objects wrapped into `next` messages to the client.

Example of retrieving catalogue change history in WebSocket protocol for REST:

<SourceAlternativeTabs variants="rest">

[Setting up a minimal catalog change capture](/documentation/user/en/use/api/example/catalog-change-capture-rest.json)

</SourceAlternativeTabs>

You can find also additional helpful examples in the below:

<Note type="info">

<NoteTitle toggles="true">

##### Retrieving transaction delimiters and changes for all entities of a specific type

</NoteTitle>

This subscription will deliver all transaction delimiters and all changes made to entities of type `Product` starting from
the next version of the catalogue.

<SourceAlternativeTabs variants="rest">

[Requesting entity level changes in transaction blocks](/documentation/user/en/use/api/example/capture-entity-mutations-with-transaction-rest.json)

</SourceAlternativeTabs>

</Note>

<Note type="info">

<NoteTitle toggles="true">

##### Retrieving changes for attribute with name `quantityOnStock` of particular entity of type `Product`

</NoteTitle>

Following subscription will deliver all changes made to attribute `quantityOnStock` of entity type `Product` with primary
key `745` starting from the next version of the catalogue.

<SourceAlternativeTabs variants="rest">

[Requesting entity level changes](/documentation/user/en/use/api/example/capture-attribute-mutation-rest.json)

</SourceAlternativeTabs>

</Note>

</LS>

### Frequently asked questions regarding a change capture mechanism

<LS to="j">

<Note type="question">

<NoteTitle toggles="true">

##### Do I need to keep the publisher instance?

</NoteTitle>

No — you can let it be garbage collected. The publisher is just a factory for creating subscribers. Once the subscriber is created and subscribed, it maintains its own state and connection to the engine. A reference to the subscriber is kept in the evitaDB (client) instance, which prevents it from being garbage collected as long as the instance is alive.

You only need to keep the reference to the publisher if you plan to subscribe multiple subscribers to it.

</Note>

<Note type="question">

<NoteTitle toggles="true">

##### Do I need a valid session to subscribe to the catalogue change capture?

</NoteTitle>

No, you only need a session to create the publisher. Once the publisher is created, subscribers can subscribe to it without an active session. The publisher opens up a dedicated session for each subscriber internally if the subscription is not created within an active session.

</Note>

<Note type="question">

<NoteTitle toggles="true">

##### What if I subscribe to the publisher later — from which point will I receive changes?

</NoteTitle>

The publisher freezes the CDC request parameters (including the starting version) at the moment of its creation. If the request contains a starting catalogue version, each subscriber will receive changes starting from the version specified in the CDC request used to create the publisher, regardless of when the subscriber subscribes to the publisher. If the request does not contain a starting version, each subscriber will receive changes starting from the next version of the catalogue at the moment of its subscription.

</Note>

<Note type="question">

<NoteTitle toggles="true">

##### How to properly close and release resources?

</NoteTitle>

If your subscriber class implements the [AutoCloseable](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/AutoCloseable.html) interface, you can rely on the evitaDB (client) instance to automatically close it when the client instance is closed. Close will be automatically called when the subscription is cancelled or when the client instance is closed.

</Note>

</LS>
</LS>

<LS to="g">

The catalogue-level capture stream provides access to
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCapture.java</SourceClass>
instances representing the changes made to the catalogue.

Catalogue capture events are represented by <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCapture.java</SourceClass> instances that contain the following information:

<dl>
  <dt>long `version`</dt>
  <dd>
    The version of the catalogue where the mutation occurs.
  </dd>
  <dt>int `index`</dt>
  <dd>
    The index of the mutation within the same transaction. Index `0` is always infrastructure mutations of type <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass>.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/CaptureArea.java</SourceClass> `area`</dt>
  <dd>
    The area of the operation that was performed:
    <ul>
        <li>`SCHEMA` - changes in the schema are captured</li>
        <li>`DATA` - changes in the data are captured</li>
        <li>`INFRASTRUCTURE` - infrastructural mutations that are neither schema nor data</li>
    </ul>
  </dd>
  <dt>String `entityType` (optional)</dt>
  <dd>
    The name of the entity type that was affected by the operation. This field is null when the operation is executed on the catalog schema itself.
  </dd>
  <dt>Integer `entityPrimaryKey` (optional)</dt>
  <dd>
    The primary key of the entity that was affected by the operation. Only present for data area operations.
  </dd>
  <dt>`operation`</dt>
  <dd>
    Classification of the mutation defined by enumeration:
    <ul>
        <li>`UPSERT` - Create or update operation. If there was data with such identity before, it was updated. If not, it was created.</li>
        <li>`REMOVE` - Remove operation - i.e. there was data with such identity before, and it was removed.</li>
        <li>`TRANSACTION` - Delimiting operation signaling the beginning of a transaction.</li>
    </ul>
  </dd>
  <dt>`CatalogBoundMutationUnion` `body` (optional)</dt>
  <dd>
    Optional body of the operation when it is requested by the requested <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCaptureContent.java</SourceClass>.
  </dd>
</dl>

There are several ways to access the catalogue change capture stream, each with a slightly different purpose:

### System API

The GraphQL system API exposes the `onCatalogChange`/`onCatalogChangeUntyped` subscription. Which allows you to subscribe
to the catalogue change capture stream of any catalogue with fully custom filtering criteria.

This is useful if you need to react to _all_ the changes (transactional, data, schema) in a catalogue.

The subscription accepts the following parameters:

<dl>
  <dt>String `catalogName`</dt>
  <dd>
    The name of the catalogue to subscribe to.
  </dd>
  <dt>long `sinceVersion` (optional)</dt>
  <dd>
    The catalogue version (inclusive) from which you want to start receiving changes. If not specified, the change stream will start from the next version of the catalogue (i.e. the changes made to the catalogue in the future).
  </dd>
  <dt>int `sinceIndex` (optional)</dt>
  <dd>
    The index of the mutation within the same transaction from which you want to start receiving changes. If not specified, the change stream will start from the first mutation of the specified version. The index allows you to precisely specify the starting point in case you have already processed some mutations of the specified version.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCaptureCriteria.java</SourceClass>[] `criteria` (optional)</dt>
  <dd>
    Array of criteria that specify which changes you are interested in. If not specified, all changes are captured. If multiple criteria are specified, matching any of them is sufficient (OR logic). Each criterion consists of:
    <ul>
        <li>`area` - the capture area (<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/CaptureArea.java</SourceClass>)</li>
        <li>`site` - the capture site (<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/CaptureSite.java</SourceClass>) providing fine-grained filtering</li>
    </ul>
  </dd>
</dl>

#### Capture areas and sites

Catalogue CDC distinguishes between three different **capture areas** that correspond to different types of operations:

##### Schema capture area

The schema capture area tracks changes to the catalogue schema and entity schemas. This includes operations like:

- Creating, updating, or removing entity schemas
- Modifying entity attributes, references, and associated data definitions
- Changing catalogue-level schema settings

The schema area uses <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/SchemaSite.java</SourceClass> for filtering, which allows you to specify:

<dl>
  <dt>String `entityType` (optional)</dt>
  <dd>
    Filter by specific entity type name. If not specified, changes to all entity types are captured.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/Operation.java</SourceClass>[] `operation` (optional)</dt>
  <dd>
    Filter by operation type. If not specified, all operations are captured. Possible values:
    <ul>
      <li>`UPSERT` - Create or update operation</li>
      <li>`REMOVE` - Remove operation</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ContainerType.java</SourceClass>[] `containerType` (optional)</dt>
  <dd>
    Filter by container type. If not specified, changes to all container types are captured. Possible values:
    <ul>
      <li>`CATALOG` - Catalogue-level schema changes</li>
      <li>`ENTITY` - Entity schema changes</li>
      <li>`ATTRIBUTE` - Attribute schema changes</li>
      <li>`ASSOCIATED_DATA` - Associated data schema changes</li>
      <li>`PRICE` - Price schema changes</li>
      <li>`REFERENCE` - Reference schema changes</li>
    </ul>
  </dd>
</dl>

##### Data capture area

The data capture area tracks changes to entity data within the catalogue. This includes operations like:

- Creating, updating, or removing entities
- Modifying entity attributes, references, and associated data values
- Updating prices and hierarchical placement

The data area uses <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/DataSite.java</SourceClass> for filtering, which allows you to specify:

<dl>
  <dt>String `entityType` (optional)</dt>
  <dd>
    Filter by specific entity type name. If not specified, changes to all entity types are captured.
  </dd>
  <dt>Integer `entityPrimaryKey` (optional)</dt>
  <dd>
    Filter by specific entity primary key. If not specified, changes to all entities are captured.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/Operation.java</SourceClass>[] `operation` (optional)</dt>
  <dd>
    Filter by operation type. If not specified, all operations are captured. Possible values:
    <ul>
      <li>`UPSERT` - Create or update operation</li>
      <li>`REMOVE` - Remove operation</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ContainerType.java</SourceClass>[] `containerType` (optional)</dt>
  <dd>
    Filter by container type. If not specified, changes to all container types are captured. Possible values:
    <ul>
      <li>`ENTITY` - Entity-level changes</li>
      <li>`ATTRIBUTE` - Attribute value changes</li>
      <li>`ASSOCIATED_DATA` - Associated data value changes</li>
      <li>`PRICE` - Price changes</li>
      <li>`REFERENCE` - Reference changes</li>
    </ul>
  </dd>
  <dt>String[] `containerName` (optional)</dt>
  <dd>
    Filter by specific container name (e.g., specific attribute name like `name`, `code`). If not specified, changes to all containers are captured.
  </dd>
</dl>

##### Infrastructure capture area

The infrastructure capture area tracks transaction-related and other infrastructural mutations that don't fit into schema or data categories. This includes:

- Transaction delimiting operations
- System-level operations

The infrastructure area does not use any capture site for filtering — currently, it captures all infrastructure mutations represented by <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass>.

<dl>
  <dt>No filtering parameters</dt>
  <dd>
    Infrastructure area captures all transaction and system-level mutations without any filtering options. To capture infrastructure mutations, specify `CaptureArea.INFRASTRUCTURE` in your criteria without a capture site.
  </dd>
</dl>

This area exists separately because transaction boundaries and system operations are orthogonal to both schema and data changes, and clients may need to track transaction boundaries independently for proper event grouping and consistency guarantees.

#### How to set up a new catalogue change capture in system API

The setup is quite straightforward: simply define one subscription with the desired parameters and subscribe to the stream
via the WebSocket protocol. The WebSocket stream will then send the change events to the client based on the defined
output.

Example of retrieving catalogue change history in GraphQL system API:

<SourceCodeTabs langSpecificTabOnly local>

[Setting up a minimal catalog change capture](/documentation/user/en/use/api/example/catalog-change-capture.graphql)

</SourceCodeTabs>

You can find also additional helpful examples in the below:

<Note type="info">

<NoteTitle toggles="true">

##### Retrieving transaction delimiters and changes for all entities of a specific type

</NoteTitle>

This publisher will deliver all transaction delimiters and all changes made to entities of type `Product` starting from
the next version of the catalogue.

<SourceCodeTabs langSpecificTabOnly local>

[Requesting entity level changes in transaction blocks](/documentation/user/en/use/api/example/capture-entity-mutations-with-transaction.graphql)

</SourceCodeTabs>

</Note>

<Note type="info">

<NoteTitle toggles="true">

##### Retrieving changes for attribute with name `quantityOnStock` of particular entity of type `Product`

</NoteTitle>

Following subscription will deliver all changes made to attribute `quantityOnStock` of entity type `Product` with primary
key `745` starting from the next version of the catalogue.

<SourceCodeTabs langSpecificTabOnly local>

[Requesting entity level changes](/documentation/user/en/use/api/example/capture-attribute-mutation.graphql)

</SourceCodeTabs>

</Note>

### Catalogue data API

If you don't need the fully-featured system API's catalogue CDC subscriptions, the GraphQL data API exposes two simplified subscriptions:

First one is the `onDataChange`/`onDataChangeUntyped` subscription that allows you to subscribe to the _data_ change capture stream
of the entire API-specified catalogue (with all entity collections).

This is useful if you only need to react to data changes and nothing more. If so, this subscription provides a simpler interface
with a smaller set of mutations to worry about.

The subscription accepts the following parameters:

<dl>
  <dt>long `sinceVersion` (optional)</dt>
  <dd>
    The catalogue version (inclusive) from which you want to start receiving changes. If not specified, the change stream will start from the next version of the catalogue (i.e. the changes made to the catalogue in the future).
  </dd>
  <dt>int `sinceIndex` (optional)</dt>
  <dd>
    The index of the mutation within the same transaction from which you want to start receiving changes. If not specified, the change stream will start from the first mutation of the specified version. The index allows you to precisely specify the starting point in case you have already processed some mutations of the specified version.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/Operation.java</SourceClass>[] `operation` (optional)</dt>
  <dd>
    Filter by operation type. If not specified, all operations are captured. Possible values:
    <ul>
      <li>`UPSERT` - Create or update operation</li>
      <li>`REMOVE` - Remove operation</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ContainerType.java</SourceClass>[] `containerType` (optional)</dt>
  <dd>
    Filter by container type. If not specified, changes to all container types are captured. Possible values:
    <ul>
      <li>`CATALOG` - Catalogue-level schema changes</li>
      <li>`ENTITY` - Entity schema changes</li>
      <li>`ATTRIBUTE` - Attribute schema changes</li>
      <li>`ASSOCIATED_DATA` - Associated data schema changes</li>
      <li>`PRICE` - Price schema changes</li>
      <li>`REFERENCE` - Reference schema changes</li>
    </ul>
  </dd>
  <dt>String[] `containerName` (optional)</dt>
  <dd>
    Filter by specific container name (e.g., specific attribute name like `name`, `code`). If not specified, changes to all containers are captured.
  </dd>
</dl>

Second one is the `on{entityType}DataChange`/`on{entityType}DataChangeUntyped` subscription that allows you to subscribe 
to the _data_ change capture stream of a _specific_ entity collection within the API-specified catalogue.

This is useful if you need to react to data changes of a _specific entity collection_ only and nothing more. If so, 
this subscription provides a simpler interface with a smaller set of mutations to worry about.

The subscription accepts the following parameters:

<dl>
  <dt>Integer `entityPrimaryKey` (optional)</dt>
  <dd>
    Filter by specific entity primary key. If not specified, changes to all entities are captured.
  </dd>
  <dt>long `sinceVersion` (optional)</dt>
  <dd>
    The catalogue version (inclusive) from which you want to start receiving changes. If not specified, the change stream will start from the next version of the catalogue (i.e. the changes made to the catalogue in the future).
  </dd>
  <dt>int `sinceIndex` (optional)</dt>
  <dd>
    The index of the mutation within the same transaction from which you want to start receiving changes. If not specified, the change stream will start from the first mutation of the specified version. The index allows you to precisely specify the starting point in case you have already processed some mutations of the specified version.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/Operation.java</SourceClass>[] `operation` (optional)</dt>
  <dd>
    Filter by operation type. If not specified, all operations are captured. Possible values:
    <ul>
      <li>`UPSERT` - Create or update operation</li>
      <li>`REMOVE` - Remove operation</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ContainerType.java</SourceClass>[] `containerType` (optional)</dt>
  <dd>
    Filter by container type. If not specified, changes to all container types are captured. Possible values:
    <ul>
      <li>`CATALOG` - Catalogue-level schema changes</li>
      <li>`ENTITY` - Entity schema changes</li>
      <li>`ATTRIBUTE` - Attribute schema changes</li>
      <li>`ASSOCIATED_DATA` - Associated data schema changes</li>
      <li>`PRICE` - Price schema changes</li>
      <li>`REFERENCE` - Reference schema changes</li>
    </ul>
  </dd>
  <dt>String[] `containerName` (optional)</dt>
  <dd>
    Filter by specific container name (e.g., specific attribute name like `name`, `code`). If not specified, changes to all containers are captured.
  </dd>
</dl>

#### How to set up a new catalogue change capture in catalogue data API

The setup is quite straightforward: simply define one subscription with the desired parameters and subscribe to the stream
via the WebSocket protocol. The WebSocket stream will then send the change events to the client based on the defined
output.

Example of retrieving catalogue change history in GraphQL catalogue data API:

<SourceCodeTabs langSpecificTabOnly local>

[Setting up a minimal catalog change capture](/documentation/user/en/use/api/example/catalog-change-capture-data-api.graphql)

</SourceCodeTabs>

You can find also additional helpful examples in the below:

<Note type="info">

<NoteTitle toggles="true">

##### Retrieving changes for attribute with name `quantityOnStock` of particular entity of type `Product`

</NoteTitle>

Following subscription will deliver all changes made to attribute `quantityOnStock` of entity type `Product` with primary
key `745` starting from the next version of the catalogue.

<SourceCodeTabs langSpecificTabOnly local>

[Requesting entity level changes](/documentation/user/en/use/api/example/capture-attribute-mutation-data-api.graphql)

</SourceCodeTabs>

</Note>

### Catalogue schema API

If you don't need the fully-featured system API's catalogue CDC subscriptions, the GraphQL schema API exposes two simplified subscriptions:

First one is the `onSchemaChange`/`onSchemaChangeUntyped` subscription that allows you to subscribe to the _schema_ change capture stream
of the entire API-specified catalogue (with all entity collections).

This is useful if you only need to react to schema changes and nothing more. If so, this subscription provides a simpler interface
with a smaller set of mutations to worry about.

The subscription accepts the following parameters:

<dl>
  <dt>long `sinceVersion` (optional)</dt>
  <dd>
    The catalogue version (inclusive) from which you want to start receiving changes. If not specified, the change stream will start from the next version of the catalogue (i.e. the changes made to the catalogue in the future).
  </dd>
  <dt>int `sinceIndex` (optional)</dt>
  <dd>
    The index of the mutation within the same transaction from which you want to start receiving changes. If not specified, the change stream will start from the first mutation of the specified version. The index allows you to precisely specify the starting point in case you have already processed some mutations of the specified version.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/Operation.java</SourceClass>[] `operation` (optional)</dt>
  <dd>
    Filter by operation type. If not specified, all operations are captured. Possible values:
    <ul>
      <li>`UPSERT` - Create or update operation</li>
      <li>`REMOVE` - Remove operation</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ContainerType.java</SourceClass>[] `containerType` (optional)</dt>
  <dd>
    Filter by container type. If not specified, changes to all container types are captured. Possible values:
    <ul>
      <li>`CATALOG` - Catalogue-level schema changes</li>
      <li>`ENTITY` - Entity schema changes</li>
      <li>`ATTRIBUTE` - Attribute schema changes</li>
      <li>`ASSOCIATED_DATA` - Associated data schema changes</li>
      <li>`PRICE` - Price schema changes</li>
      <li>`REFERENCE` - Reference schema changes</li>
    </ul>
  </dd>
</dl>


Second one is the `on{entityType}SchemaChange`/`on{entityType}SchemaChangeUntyped` subscription that allows you to subscribe
to the _schema_ change capture stream of a _specific_ entity collection within the API-specified catalogue.

This is useful if you need to react to schema changes of a _specific entity collection_ only and nothing more. If so,
this subscription provides a simpler interface with a smaller set of mutations to worry about.

The subscription accepts the following parameters:

<dl>
  <dt>long `sinceVersion` (optional)</dt>
  <dd>
    The catalogue version (inclusive) from which you want to start receiving changes. If not specified, the change stream will start from the next version of the catalogue (i.e. the changes made to the catalogue in the future).
  </dd>
  <dt>int `sinceIndex` (optional)</dt>
  <dd>
    The index of the mutation within the same transaction from which you want to start receiving changes. If not specified, the change stream will start from the first mutation of the specified version. The index allows you to precisely specify the starting point in case you have already processed some mutations of the specified version.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/Operation.java</SourceClass>[] `operation` (optional)</dt>
  <dd>
    Filter by operation type. If not specified, all operations are captured. Possible values:
    <ul>
      <li>`UPSERT` - Create or update operation</li>
      <li>`REMOVE` - Remove operation</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ContainerType.java</SourceClass>[] `containerType` (optional)</dt>
  <dd>
    Filter by container type. If not specified, changes to all container types are captured. Possible values:
    <ul>
      <li>`CATALOG` - Catalogue-level schema changes</li>
      <li>`ENTITY` - Entity schema changes</li>
      <li>`ATTRIBUTE` - Attribute schema changes</li>
      <li>`ASSOCIATED_DATA` - Associated data schema changes</li>
      <li>`PRICE` - Price schema changes</li>
      <li>`REFERENCE` - Reference schema changes</li>
    </ul>
  </dd>
</dl>

#### How to set up a new catalogue change capture in catalogue schema API

The setup is quite straightforward: simply define one subscription with the desired parameters and subscribe to the stream
via the WebSocket protocol. The WebSocket stream will then send the change events to the client based on the defined
output.

Example of retrieving catalogue change history in GraphQL catalogue schema API:

<SourceCodeTabs langSpecificTabOnly local>

[Setting up a minimal catalog change capture](/documentation/user/en/use/api/example/catalog-change-capture-schema-api.graphql)

</SourceCodeTabs>

You can find also additional helpful examples in the below:

<Note type="info">

<NoteTitle toggles="true">

##### Retrieving changes for attributes of particular entity of type `Product`

</NoteTitle>

Following subscription will deliver all schema changes made to attributes of entity type `Product` starting from the
next version of the catalogue.

<SourceCodeTabs langSpecificTabOnly local>

[Requesting entity level changes](/documentation/user/en/use/api/example/capture-attribute-mutation-schema-api.graphql)

</SourceCodeTabs>

</Note>

</LS>

</LS>

<LS to="c">
Subscribing to CDC is not supported in C# client yet.
</LS>