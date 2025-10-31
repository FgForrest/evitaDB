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
The Engine API is accessible from the main <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass> interface. Control methods usually come in two flavors - one is asynchronous, returning <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/progress/Progress.java</SourceClass>, and the other is a synchronous blocking variant. The asynchronous methods are suffixed with `WithProgress` in their names. The `Progress` object allows you to monitor the operation progress and access its [CompletionStage](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/CompletionStage.html) using the `onCompletion()` method. Synchronous methods block the current thread until the operation is finished and return the result directly, but underneath they also use the asynchronous variant.

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java" langSpecificTabOnly local>

[Defining a new catalog and making it inactive in a blocking fashion](/documentation/user/en/use/api/example/engine-control-blocking.java)

</SourceCodeTabs>

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Making a catalog alive in an asynchronous fashion](/documentation/user/en/use/api/example/engine-control-nonblocking.java)

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
    <dd>Changes the name of an existing catalog to a new one. You can also use the name of another existing catalog to replace it with the named catalog, but you need to confirm this replacement operation by setting `overwriteTarget` to `true`.</dd>
    <dt>Modify catalog schema</dt>
    <dd>Allows you to modify the catalog schema. This mutation is not allowed to be used as an engine control mutation — it is only usable within the context of a specific catalog and requires <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass> to be executed.</dd>
    <dt>Make catalog alive</dt>
    <dd>Allows transitioning a catalog from the `WARMING_UP` state to the `ALIVE` state. A catalog in the `ALIVE` state means it's fully populated to its initial state and ready to be queried. Also, all mutations to the catalog are performed in a transactional fashion.</dd>
    <dt>Restore catalog</dt>
    <dd>This operation is internal to the engine and is not expected to be used. It introduces a new "inactive" catalog to the engine. The contents of the catalog must already be present in the correct state in the data folder of the file system. This operation just "reveals" the catalog to the system.</dd>
    <dt>Set mutability</dt>
    <dd>Allows you to switch a particular catalog between `read-only` and `read-write` mode. When a catalog is in `read-only` mode, no newly created session is allowed to be created in `read-write` mode and make changes in the catalog. This engine mutation doesn't affect currently opened `read-write` sessions, nor does it force their closure.</dd>
    <dt>Set catalog state</dt>
    <dd>Allows you to load or unload catalog contents to/from memory. Catalogs in an "active" state contain all their crucial data in memory and can be queried and updated. The "inactive" catalogs, on the other hand, lie dormant in persistent storage and don't consume any system resources, but also cannot be queried or updated.</dd>
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
<LS to="e,r,g">
Engine control is currently supported only in [Java](control-engine.md?lang=java).
</LS>