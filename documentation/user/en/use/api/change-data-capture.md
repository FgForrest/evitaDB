---
title: Change data capture
perex: |
    Change data capture (CDC) is a design pattern used to track and capture changes made to schema and data in a database. evitaDB supports CDC through all its APIs, allowing developers to monitor and respond to data changes very easily in near real-time in their preferred programming language. This document explains how to implement CDC using our API.
date: '21.10.2025'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
preferredLang: 'java'
---
Database maintains so-called [Write-Ahead Log (WAL)](https://en.wikipedia.org/wiki/Write-ahead_logging) that records all changes made to the database. This log is used to ensure data integrity and durability, but it can (and it actually is) also be leveraged to implement change data capture (CDC) functionality. Once the catalog is switched to `ACTIVE` (transactional) stage, clients can start consuming information about changes made to both the schema and the data in the catalog.

There is also a special CDC available for the entire database engine that allows clients to monitor high-level operations such as catalog creation, deletion, and other global events (for more details consult the [Control Engine chapter](control-engine.md)).

<Note type="warning">

Change data capture is not available for catalogs in `WARMING_UP` stage since the WAL is not being recorded during that phase.
This phase is considered as "introductory" and clients should not work (query) with the data in that phase anyway. Clients should wait until the catalog reaches `ACTIVE` stage and perceive all the data at that moment as a consistent snapshot of the first version of the catalog. 

</Note>

<Note type="info">

Engine and catalog-level CDCs cannot be combined into a single stream since they operate on different levels (engine vs. catalog). Catalog level CDC is always tied to particular catalog (name). If you'd need to capture all changes across all catalogs, you'd need to subscribe to engine-level CDC and then for each catalog separately to catalog-level CDC. The engine level CDC notifies about catalog creation/deletion events, so clients can dynamically subscribe/unsubscribe to catalog-level CDCs as catalogs are created/deleted.

</Note>

The basic principle in all APIs is the same:

1. clients define a predicate / condition that specifies which changes they are interested in,
2. define a starting point in the form of a catalog version from which they want to start receiving changes,
3. and subscribe to the change stream.

From that point onward, clients will receive notifications about all changes that match their criteria. The changes are delivered in the order they were made, ensuring that clients can process them sequentially. The second step is optional — if no starting version is specified, the change stream will start from the current version of the catalog.

## Hierarchy of mutations

Not all mutations operate on the same level and some mutations may encapsulate others. For example, when an entity is upserted, it may contain multiple mutations within it (multiple attribute, associated data, price operations etc.). The hierarchy of mutations is as follows:

- <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/EngineMutation.java</SourceClass> ([complete listing](control-engine.md), available in [engine change capture](#engine-change-capture))
    - <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/CatalogBoundMutation.java</SourceClass> ([complete listing](../schema.md), available in [catalog schema change capture](#catalog-schema-change-capture))
        - <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/LocalCatalogSchemaMutation.java</SourceClass>
            - <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/LocalEntitySchemaMutation.java</SourceClass>
                - <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/reference/ModifyReferenceAttributeSchemaMutation.java</SourceClass> 
    - <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/EntityMutation.java</SourceClass> ([complete listing](write-data.md), available in [catalog data change capture](#catalog-data-change-capture))
        - <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/LocalMutation.java</SourceClass>
- <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass> (available in all change capture streams)

When you don't specify any filtering criteria, you will receive all mutations in flattened form, i.e., you will receive all mutations regardless of their hierarchy. So for example, entity attribute upsert will be delivered once as a part of the entity upsert mutation and once as a standalone attribute upsert mutation. In practice client usually wants either high-level information about entity changes (so only entity mutations) or very specific low-level changes (e.g., only changes attribute of particular name). The approach with simple flattened stream that is filtered by a single predicate covers all these use-cases very well, and it is very easy to understand and implement.

## Engine change capture

The engine-level capture stream accepts <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeSystemCaptureRequest.java</SourceClass> for creating the [Java Flow Publisher](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Flow.Publisher.html). One or more clients may then subscribe to this publisher to receive <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeSystemCapture.java</SourceClass> instances representing the changes made to the engine.

Request allows you to specify the following parameters:

<dl>
  <dt>long <code>sinceVersion</code> (optional)</dt>
  <dd>
    The catalog version (inclusive) from which you want to start receiving changes. If not specified, the change stream will start from the next version of the catalog (i.e. the changes made to the catalog in the future).
  </dd>
  <dt>int <code>sinceIndex</code> (optional)</dt>
  <dd>
    The index of the mutation within the same transaction from which you want to start receiving changes. If not specified, the change stream will start from the first mutation of the specified version. Index allows you to precisely specify the starting point in case you have already processed some mutations of the specified version.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCaptureContent.java</SourceClass></dt>
  <dd>
    Enumeration that specifies whether the client wants detailed information about each mutation or only high-level information that particular type of the mutation occurred. Enumeration has the following values:
    <ul>
        <li><code>HEADER</code> - only the header of the event is sent
        <li><code>BODY</code> - the entire body of the mutation triggering the event is sent
    </ul>
  </dd>
</dl>

Engine capture events are represented by <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeSystemCapture.java</SourceClass> instances that contain the following information:
<SourceClass></SourceClass>

<dl>
  <dt>long <code>version</code></dt>
  <dd>
    The version of the evitaDB where the mutation occurs.
  </dd>
  <dt>int <code>index</code></dt>
  <dd>
    The index of the mutation within the same transaction. Index `0` is always infrastructure mutations of type <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass>.
  </dd>
  <dt><code>operation</code></dt>
  <dd>
    Classification of the mutation defined by enumeration:
    <ul>
        <li><code>UPSERT</code> - Create or update operation. If there was data with such identity before, it was updated. If not, it was created.</li>
        <li><code>REMOVE</code> - Remove operation - i.e. there was data with such identity before, and it was removed.</li>
        <li><code>TRANSACTION</code> - Delimiting operation signaling the beginning of a transaction.</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/EngineMutation.java</SourceClass> <code>body</code> (optional)</dt>
  <dd>
    Optional body of the operation when it is requested by the requested <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCaptureContent.java</SourceClass>.
  </dd>
</dl>

<LS to="j">

### How to set up a new engine change capture

Set up is quite straightforward and consists of three steps:

1. create [Java Flow Publisher](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Flow.Publisher.html) using <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass>
2. define subscriber implementing [Java Flow Subscriber](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Flow.Subscriber.html)
3. subscribe the subscriber to the publisher

Example of setting up the engine change capture in Java:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java,/documentation/user/en/get-started/example/finalization-of-warmup-mode.java" langSpecificTabOnly local>

[Setting up a minimal engine change capture](/documentation/user/en/use/api/example/engine-change-capture.java)

</SourceCodeTabs>

The subscriber will start receiving change events as soon as they occur in the engine. Subscriber `onComplete` method is never called since the change stream is infinite.

<Note type="info">

Currently, multiple engine mutations cannot be wrapped into a single transaction. Each engine operation is represented by a separate transaction mutation. So you can expect that the engine mutation stream will always contain transaction mutation, followed by a single top level engine mutation.

</Note>

</LS>

## Catalog change capture

The catalog-level capture stream accepts <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCaptureRequest.java</SourceClass> for creating the [Java Flow Publisher](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Flow.Publisher.html). One or more clients may then subscribe to this publisher to receive <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCapture.java</SourceClass> instances representing the changes made to the catalog.

Request allows you to specify the following parameters:

<dl>
  <dt>long <code>sinceVersion</code> (optional)</dt>
  <dd>
    The catalog version (inclusive) from which you want to start receiving changes. If not specified, the change stream will start from the next version of the catalog (i.e. the changes made to the catalog in the future).
  </dd>
  <dt>int <code>sinceIndex</code> (optional)</dt>
  <dd>
    The index of the mutation within the same transaction from which you want to start receiving changes. If not specified, the change stream will start from the first mutation of the specified version. Index allows you to precisely specify the starting point in case you have already processed some mutations of the specified version.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCaptureCriteria.java</SourceClass>[] <code>criteria</code> (optional)</dt>
  <dd>
    Array of criteria that specify which changes you are interested in. If not specified, all changes are captured. If multiple criteria are specified, matching any of them is sufficient (OR logic). Each criterion consists of:
    <ul>
        <li><code>area</code> - the capture area (<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/CaptureArea.java</SourceClass>)
        <li><code>site</code> - the capture site (<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/CaptureSite.java</SourceClass>) providing fine-grained filtering
    </ul>
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCaptureContent.java</SourceClass></dt>
  <dd>
    Enumeration that specifies whether the client wants detailed information about each mutation or only high-level information that particular type of the mutation occurred. Enumeration has the following values:
    <ul>
        <li><code>HEADER</code> - only the header of the event is sent
        <li><code>BODY</code> - the entire body of the mutation triggering the event is sent
    </ul>
  </dd>
</dl>

Catalog capture events are represented by <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCapture.java</SourceClass> instances that contain the following information:

<dl>
  <dt>long <code>version</code></dt>
  <dd>
    The version of the catalog where the mutation occurs.
  </dd>
  <dt>int <code>index</code></dt>
  <dd>
    The index of the mutation within the same transaction. Index `0` is always infrastructure mutations of type <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass>.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/CaptureArea.java</SourceClass> <code>area</code></dt>
  <dd>
    The area of the operation that was performed:
    <ul>
        <li><code>SCHEMA</code> - changes in the schema are captured</li>
        <li><code>DATA</code> - changes in the data are captured</li>
        <li><code>INFRASTRUCTURE</code> - infrastructural mutations that are neither schema nor data</li>
    </ul>
  </dd>
  <dt>String <code>entityType</code> (optional)</dt>
  <dd>
    The name of the entity type that was affected by the operation. This field is null when the operation is executed on the catalog schema itself.
  </dd>
  <dt>Integer <code>entityPrimaryKey</code> (optional)</dt>
  <dd>
    The primary key of the entity that was affected by the operation. Only present for data area operations.
  </dd>
  <dt><code>operation</code></dt>
  <dd>
    Classification of the mutation defined by enumeration:
    <ul>
        <li><code>UPSERT</code> - Create or update operation. If there was data with such identity before, it was updated. If not, it was created.</li>
        <li><code>REMOVE</code> - Remove operation - i.e. there was data with such identity before, and it was removed.</li>
        <li><code>TRANSACTION</code> - Delimiting operation signaling the beginning of a transaction.</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/CatalogBoundMutation.java</SourceClass> <code>body</code> (optional)</dt>
  <dd>
    Optional body of the operation when it is requested by the requested <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCaptureContent.java</SourceClass>.
  </dd>
</dl>

### Capture areas and sites

Catalog CDC distinguishes between three different **capture areas** that correspond to different types of operations:

#### Schema capture area

Schema capture area tracks changes to the catalog schema and entity schemas. This includes operations like:

- Creating, updating, or removing entity schemas
- Modifying entity attributes, references, associated data definitions
- Changing catalog-level schema settings

The schema area uses <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/SchemaSite.java</SourceClass> for filtering, which allows you to specify:

<dl>
  <dt>String <code>entityType</code> (optional)</dt>
  <dd>
    Filter by specific entity type name. If not specified, changes to all entity types are captured.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/Operation.java</SourceClass>[] <code>operation</code> (optional)</dt>
  <dd>
    Filter by operation type. If not specified, all operations are captured. Possible values:
    <ul>
      <li><code>UPSERT</code> - Create or update operation</li>
      <li><code>REMOVE</code> - Remove operation</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ContainerType.java</SourceClass>[] <code>containerType</code> (optional)</dt>
  <dd>
    Filter by container type. If not specified, changes to all container types are captured. Possible values:
    <ul>
      <li><code>CATALOG</code> - Catalog-level schema changes</li>
      <li><code>ENTITY</code> - Entity schema changes</li>
      <li><code>ATTRIBUTE</code> - Attribute schema changes</li>
      <li><code>ASSOCIATED_DATA</code> - Associated data schema changes</li>
      <li><code>PRICE</code> - Price schema changes</li>
      <li><code>REFERENCE</code> - Reference schema changes</li>
    </ul>
  </dd>
</dl>

#### Data capture area

Data capture area tracks changes to entity data within the catalog. This includes operations like:

- Creating, updating, or removing entities
- Modifying entity attributes, references, associated data values
- Updating prices, hierarchical placement

The data area uses <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/DataSite.java</SourceClass> for filtering, which allows you to specify:

<dl>
  <dt>String <code>entityType</code> (optional)</dt>
  <dd>
    Filter by specific entity type name. If not specified, changes to all entity types are captured.
  </dd>
  <dt>Integer <code>entityPrimaryKey</code> (optional)</dt>
  <dd>
    Filter by specific entity primary key. If not specified, changes to all entities are captured.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/Operation.java</SourceClass>[] <code>operation</code> (optional)</dt>
  <dd>
    Filter by operation type. If not specified, all operations are captured. Possible values:
    <ul>
      <li><code>UPSERT</code> - Create or update operation</li>
      <li><code>REMOVE</code> - Remove operation</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ContainerType.java</SourceClass>[] <code>containerType</code> (optional)</dt>
  <dd>
    Filter by container type. If not specified, changes to all container types are captured. Possible values:
    <ul>
      <li><code>ENTITY</code> - Entity-level changes</li>
      <li><code>ATTRIBUTE</code> - Attribute value changes</li>
      <li><code>ASSOCIATED_DATA</code> - Associated data value changes</li>
      <li><code>PRICE</code> - Price changes</li>
      <li><code>REFERENCE</code> - Reference changes</li>
    </ul>
  </dd>
  <dt>String[] <code>containerName</code> (optional)</dt>
  <dd>
    Filter by specific container name (e.g., specific attribute name like `name`, `code`). If not specified, changes to all containers are captured.
  </dd>
</dl>

#### Infrastructure capture area

Infrastructure capture area tracks transaction-related and other infrastructural mutations that don't fit into schema or data categories. This includes:

- Transaction delimiting operations
- System-level operations

Infrastructure area does not use any capture site for filtering — currently, it captures all infrastructure mutations represented by <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass>.

<dl>
  <dt>No filtering parameters</dt>
  <dd>
    Infrastructure area captures all transaction and system-level mutations without any filtering options. To capture infrastructure mutations, specify <code>CaptureArea.INFRASTRUCTURE</code> in your criteria without a capture site.
  </dd>
</dl>

This area exists separately because transaction boundaries and system operations are orthogonal to both schema and data changes, and clients may need to track transaction boundaries independently for proper event grouping and consistency guarantees.

### How to set up a new catalog change capture

Setting up catalog change capture differs from engine change capture in that it operates on the catalog level.

<LS to="j">

The setup consists of:

1. Open a session (read-only or read-write) to the catalog
2. Call `registerChangeCatalogCapture` with <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCaptureRequest.java</SourceClass>
3. Process the returned stream of <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCapture.java</SourceClass> events

Example of retrieving catalog change history in Java:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java,/documentation/user/en/get-started/example/finalization-of-warmup-mode.java" langSpecificTabOnly local>

[Setting up a minimal catalog change capture](/documentation/user/en/use/api/example/catalog-change-capture.java)

</SourceCodeTabs>

</LS>

### Frequently asked questions regarding a change capture mechanism

<Note type="question">

<NoteTitle toggles="true">

##### Do I need to keep the publisher instance?

</NoteTitle>

No - you can let it be garbage collected. The publisher is just a factory for creating subscribers. Once the subscriber is created and subscribed, it maintains its own state and connection to the engine. Reference to the subscriber is kept in the evitaDB (client) instance, which prevents it from being garbage collected as long as the instance is alive.

You need to keep the reference to the publisher only if you plan to subscriber multiple subscribers to it.

</Note>

<Note type="question">

<NoteTitle toggles="true">

##### Do I need a valid session to subscribe to the catalog change capture?

</NoteTitle>

No, you need a session only to create the publisher. Once the publisher is created, subscribers can subscribe to it without an active session. The publisher opens up a dedicated session for each subscriber internally if the subscription is not created within an active session.

</Note>

<Note type="question">

<NoteTitle toggles="true">

##### What if I subscribe to the publisher later — from which point will I receive changes?

</NoteTitle>

Publisher freezes the CDC request parameters (including the starting version) at the moment of its creation. If the request contains starting catalog version, each subscriber will receive changes starting from the version specified in the CDC request used to create the publisher, regardless of when the subscriber subscribes to the publisher. If the request does not contain starting version, each subscriber will receive changes starting from the next version of the catalog at the moment of its subscription.

</Note>

<Note type="question">

<NoteTitle toggles="true">

##### How to properly close and release resources?

</NoteTitle>

If your subscriber class implements [AutoCloseable](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/AutoCloseable.html) interface, you can rely on the evitaDB (client) instance to automatically close it when the client instance is closed. Close will be automatically called when subscription is canceled or when the client instance is closed.

</Note>