---
title: Control Engine
perex: |
    The evitaDB database engine can be fully controlled programmatically through its Control API. This API allows developers to manage catalogs, monitor the engine's health, and perform various administrative tasks directly from their applications. Our web console evitaLab uses this API for all its management functionalities. This document provides an overview of all supported engine-level operations available to you.
date: '31.10.2025'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
preferredLang: 'java'
---

<LS to="j">
The Engine API is accessible from the main <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass> interface. Control methods usually come in two flavors - one is asynchronous, returning <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/progress/Progress.java</SourceClass>, and the other is a synchronous blocking variant. The asynchronous methods are suffixed with `WithProgress` in their names.

The non-blocking variants return `Progress` object allowing you to monitor the operation progress and access its [CompletionStage](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/CompletionStage.html) using the `onCompletion()` method. Synchronous methods block the current thread until the operation is finished and return the result directly, but underneath they also use the asynchronous variant.

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Making a catalog alive in an asynchronous fashion](/documentation/user/en/use/api/example/engine-control-nonblocking.java)

</SourceCodeTabs>

Now, let's look how to define a new catalog and make it inactive using the blocking approach:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java" langSpecificTabOnly local>

[Defining a new catalog and making it inactive in a blocking fashion](/documentation/user/en/use/api/example/engine-control-blocking.java)

</SourceCodeTabs>

There is also a generic method `applyMutation` that accepts engine control mutations and returns <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/progress/Progress.java</SourceClass>. This method is the actual implementation of all control operations. Each control operation has its own mutation class that is passed to this method.

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Making a catalog alive via the generic method](/documentation/user/en/use/api/example/engine-control-generic.java)

</SourceCodeTabs>

The following operations are supported at the engine level:

<dl>
    <dt>Create new catalog</dt>
    <dd>Defines a new catalog within the engine. You need to provide the name of the catalog and its initial schema definition.</dd>
    <dt>Duplicate existing catalog</dt>
    <dd>Creates a copy of an existing catalog under a new name. The duplicated catalog is a binary copy of the original catalog and is created in an "inactive" state, which means it cannot be immediately queried or updated unless you make it active.</dd>
    <dt>Modify catalog name</dt>
    <dd>Changes the name of an existing catalog to a new one (`renameCatalog`). To take the name of another existing catalog, replacing it with the named catalog, use the separate replace operation (`replaceCatalog`) instead.</dd>
    <dt>Modify catalog schema</dt>
    <dd>Allows you to modify the catalog schema. This mutation is not allowed to be used as an engine control mutation — it is only usable within the context of a specific catalog and requires <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass> to be executed.</dd>
    <dt>Make catalog alive</dt>
    <dd>Allows transitioning a catalog from the `WARMING_UP` state to the `ALIVE` state. A catalog in the `ALIVE` state means it's fully populated to its initial state and ready to be queried. Also, all mutations to the catalog are performed in a transactional fashion.</dd>
    <dt>Restore catalog</dt>
    <dd>This operation is internal to the engine and is not expected to be used. It introduces a new "inactive" catalog to the engine. The contents of the catalog must already be present in the correct state in the data folder of the file system. This operation just "reveals" the catalog to the system.</dd>
    <dt>Set mutability</dt>
    <dd>Allows you to switch a particular catalog between `read-only` and `read-write` mode. When a catalog is in `read-only` mode, no newly created session is allowed to be created in `read-write` mode and make changes in the catalog. This engine mutation doesn't affect currently opened `read-write` sessions, nor does it force their closure.</dd>
    <dt>Set catalog state</dt>
    <dd>Allows you to load or unload catalog contents to/from memory. Catalogs in an "active" state contain all their crucial data in memory and can be queried and updated. The "inactive" catalogs, on the other hand, lie dormant in persistent storage and don't consume any system resources, but also cannot be queried or updated. The engine also makes a catalog inactive on its own when a failure during [bulk indexing](../../deep-dive/bulk-vs-incremental-indexing.md#failures-that-cannot-be-reverted) cannot be reverted - activating it again loads the last state the catalog published.</dd>
</dl>

<Note type="info">

<NoteTitle toggles="false">

##### List of all control engine mutations
</NoteTitle>

- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/engine/CreateCatalogSchemaMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/engine/DuplicateCatalogMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/engine/MakeCatalogAliveMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/engine/ModifyCatalogSchemaMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/engine/ModifyCatalogSchemaNameMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/engine/RestoreCatalogSchemaMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/engine/SetCatalogMutabilityMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/engine/SetCatalogStateMutation.java</SourceClass>**

</Note>

</LS>
<LS to="g,r">
The engine is controlled through the <LS to="g">[system API](/documentation/user/en/use/connectors/graphql.md#graphql-api-instances)</LS><LS to="r">[system API](/documentation/user/en/use/connectors/rest.md#rest-api-instances)</LS>.
Every operation comes in two flavors, the same as in the Java API: a blocking one, which waits until the operation
finishes and returns the resulting catalog, and an asynchronous one, which returns as soon as the engine has accepted
the operation, with a handle carrying the `progressId` of the operation. The `progressId` is then used to subscribe to
the progress and the outcome of the operation.

<LS to="g">

| Operation                        | Blocking mutation        | Asynchronous mutation                |
|----------------------------------|--------------------------|--------------------------------------|
| Create new catalog               | `createNewCatalog`       | -                                    |
| Activate catalog                 | `activateCatalog`        | `activateCatalogWithProgress`        |
| Deactivate catalog               | `deactivateCatalog`      | `deactivateCatalogWithProgress`      |
| Delete catalog                   | `deleteCatalogIfExists`  | `deleteCatalogIfExistsWithProgress`  |
| Duplicate catalog                | `duplicateCatalog`       | `duplicateCatalogWithProgress`       |
| Make catalog alive               | `makeCatalogAlive`       | `makeCatalogAliveWithProgress`       |
| Make catalog read-only           | `makeCatalogImmutable`   | `makeCatalogImmutableWithProgress`   |
| Make catalog writable            | `makeCatalogMutable`     | `makeCatalogMutableWithProgress`     |
| Rename catalog                   | `renameCatalog`          | `renameCatalogWithProgress`          |
| Replace catalog with another one | `replaceCatalog`         | `replaceCatalogWithProgress`         |

Renaming and replacing require the catalogs to be loaded in memory (not `INACTIVE`).

The blocking mutations return the resulting catalog as `CatalogUnion` - a loaded `Catalog`, or an `UnusableCatalog`
when the catalog is not loaded in memory (e.g. after it was deactivated or duplicated); `deleteCatalogIfExists` returns
whether the catalog existed. This is how to define a new catalog and make it inactive using the blocking mutations:

</LS>
<LS to="r">

| Operation                        | Blocking endpoint                                           | Asynchronous endpoint                            |
|----------------------------------|-------------------------------------------------------------|--------------------------------------------------|
| Create new catalog               | `POST /catalogs` with `{"name": "..."}`                     | -                                                |
| Activate catalog                 | `PATCH /catalogs/{name}` with `{"state": "ALIVE"}`          | `PATCH /catalogs-async/{name}`                   |
| Deactivate catalog               | `PATCH /catalogs/{name}` with `{"state": "INACTIVE"}`       | `PATCH /catalogs-async/{name}`                   |
| Make catalog alive               | `PATCH /catalogs/{name}` with `{"state": "ALIVE"}`          | `PATCH /catalogs-async/{name}`                   |
| Make catalog read-only/writable  | `PATCH /catalogs/{name}` with `{"readOnly": true/false}`    | `PATCH /catalogs-async/{name}`                   |
| Rename catalog                   | `PATCH /catalogs/{name}` with `{"name": "newName"}`         | `PATCH /catalogs-async/{name}`                   |
| Delete catalog                   | `DELETE /catalogs/{name}`                                   | `DELETE /catalogs-async/{name}`                  |
| Duplicate catalog                | `POST /catalogs/{name}/actions/duplicate/{newName}`         | `POST /catalogs-async/{name}/actions/duplicate/{newName}` |
| Replace catalog `{newName}`      | `POST /catalogs/{name}/actions/replace/{newName}`           | `POST /catalogs-async/{name}/actions/replace/{newName}`   |

A `PATCH` request must contain exactly one of `name`, `state` and `readOnly` - one request performs exactly one
operation. `ALIVE` activates an inactive catalog, or switches a catalog in the `WARMING_UP` state to the `ALIVE` state.
A catalog returns from activation in the state it was deactivated in, so a catalog deactivated during warm-up comes back
`WARMING_UP` and needs `ALIVE` once more. The replace action makes the catalog `{name}` take the place of the catalog
`{newName}`, whose original contents are dropped. Renaming and replacing require the catalogs to be loaded in memory
(not `INACTIVE`).

The blocking endpoints return the resulting catalog (a loaded `Catalog`, or an `UnusableCatalog` when the catalog is not
loaded in memory), the `DELETE` endpoint answers `204 No Content`. A missing catalog is answered with `404 Not Found`.
This is how to duplicate a catalog using the blocking endpoint:

</LS>

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Changing catalogs in a blocking fashion](/documentation/user/en/use/api/example/system-api-engine-control-blocking.graphql)

</SourceCodeTabs>

The asynchronous <LS to="g">mutations</LS><LS to="r">endpoints answer `202 Accepted` and</LS> return a handle with
the `progressId` of the accepted operation<LS to="g"> - here making a catalog alive</LS><LS to="r"> - here duplicating
a catalog</LS>:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Starting an operation in an asynchronous fashion](/documentation/user/en/use/api/example/system-api-engine-control-nonblocking.graphql)

</SourceCodeTabs>

<LS to="g">

The progress of the operation is then observed by the `onEngineMutationProgressChange` subscription:

```plain
subscription {
  onEngineMutationProgressChange(progressId: "c2d0b1e4-8f3a-4c6e-9b1d-2a7f5e3c8d90") {
    progressId
    percentCompleted
    finished
    catalogVersion
    catalogSchemaVersion
  }
}
```

</LS>
<LS to="r">

The progress of the operation is then observed through the WebSocket endpoint `GET /catalogs-async/progress`, using
the same sub-protocol as the [change data capture](/documentation/user/en/use/connectors/rest.md) stream, with the
subscription payload:

```json
{
  "progressId": "c2d0b1e4-8f3a-4c6e-9b1d-2a7f5e3c8d90"
}
```

</LS>

The first message carries the state of the operation at the moment of the subscription, the following ones its
changes - intermediate percentages may be skipped when the client consumes them slower than the operation progresses.
A successful operation ends the stream with a message with `finished` set to `true` (carrying the resulting
`catalogVersion` and `catalogSchemaVersion` for operations that report them, such as making a catalog alive, renaming
or replacing it), a failed one with an error carrying the failure. Subscribing after the operation has finished still
delivers its outcome, as long as the server retains it - see the
[`engineMutationProgressRetentionInSeconds` and `engineMutationProgressRetentionLimit`](/documentation/user/en/operate/configure.md#server-configuration)
settings. The outcomes are kept in memory only: after a restart of the server, the `progressId` is unknown and the
stream ends with an error - read the state of the catalog instead.

<Note type="warning">

<NoteTitle toggles="false">

##### What the asynchronous variant does not save
</NoteTitle>

The asynchronous variant does not wait for the operation, but it still waits for its *acceptance*: the engine verifies
that the operation can be applied, and - for renaming, replacing and making a catalog alive - closes the sessions open
on the catalog and lets the commits in progress finish. This usually takes milliseconds; when the sessions or commits do
not finish in time, the request itself is refused. Failures detected during the acceptance (a missing catalog, a name
clash, a read-only server) fail the request directly; failures after the acceptance reach the client only through the
progress stream.

The blocking variant holds a server thread for the whole operation and is subject to the request timeout of the API -
prefer the asynchronous variant for long-running operations, such as activating a large catalog.
</Note>

</LS>
<LS to="e,c">
Engine control is currently supported only in [Java](control-engine.md?lang=java), [GraphQL](control-engine.md?lang=graphql) and [REST](control-engine.md?lang=rest).
</LS>