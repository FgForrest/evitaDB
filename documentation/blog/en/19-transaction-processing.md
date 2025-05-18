---
title: Listening to the pulse of transaction processing
perex: |
  Data writes in evitaDB are specific, as it treats data on disk as immutable. All changes are appended incrementally to the end of files. This approach has its advantages and disadvantages. One drawback is the need for data compaction, as files gradually fill with outdated records. Compaction happens synchronously during transaction processing once certain conditions indicating an excessive amount of outdated data are met. This can impact transaction commit latency. This article discusses how clients can respond to this scenario.
date: '16.05.2025'
author: 'Jan Novotný'
motive: assets/images/19-transaction-processing.png
proofreading: 'done'
---

Let's start with the simplest form of transactional data writing in evitaDB:

```java
final int appleBrandPk = evita.updateCatalog(
    "evita-tutorial",
    session -> {
        // create a new brand
        return session.createNewEntity(BrandEditor.class)
            .setName("Apple", Locale.ENGLISH)
            .upsertVia(session)
            .getPrimaryKey();
    }
);
```

From a client's perspective, these lines seem straightforward, but beneath the surface, the database server performs [many operations](https://evitadb.io/documentation/deep-dive/transactions#lifecycle-of-a-transaction). By slightly expanding the example, new interesting questions emerge:

```java
final int appleBrandPk = evita.updateCatalog(
    "evita-tutorial",
    session -> {
        // create a new brand
        return session.createNewEntity(BrandEditor.class)
            .setName("Apple", Locale.ENGLISH)
            .upsertVia(session)
            .getPrimaryKey();
    },
    CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
);
```

The second example, equivalent to the first, explicitly states that the client wishes to wait synchronously after the session/transaction ends until changes propagate to the shared state of the database, becoming visible to all clients. An asynchronous version of this example would look like this:

```java
final CompletionStage<Integer> appleBrandPk = evita.updateCatalogAsync(
    "evita-tutorial",
    session -> {
        // create a new brand
        return session.createNewEntity(BrandEditor.class)
                .setName("Apple", Locale.ENGLISH)
                .upsertVia(session)
                .getPrimaryKey();
    },
    CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
);
appleBrandPk.thenAccept(pk -> System.out.println("Brand created with pk: " + pk));
```

In this case, the client doesn't wait for transaction completion. Instead, it immediately receives a "promise" (`CompletionStage`) after the lambda ends, allowing reactions upon successful transaction completion and processing the primary key assigned to the *Apple* brand. This approach is commonly used in reactive programming, aiming to minimize thread blocking and waiting for operations to finish.

## Transaction processing phases

The enumeration <SourceClass>evita_api/src/main/java/io/evitadb/api/TransactionContract.java</SourceClass> outlines other options clients can choose from. Let's briefly introduce them:

- **`WAIT_FOR_CONFLICT_RESOLUTION`**: Completes when the server confirms the transaction doesn't conflict with parallel transactions that completed earlier.
- **`WAIT_FOR_WAL_PERSISTENCE`**: Completes when the server safely stores transaction changes on disk and completes an [fSync](https://en.wikipedia.org/wiki/Sync_(Unix)). After this phase, the client can rely on the fact that data will eventually reach the shared state.
- **`WAIT_FOR_CHANGES_VISIBLE`**: Completes when all transaction changes are propagated to the database's shared state and become visible to other clients.

As mentioned in the introduction, the most complex phase of the transaction process is merging changes into the shared database state. At the end of this phase, data [compaction](https://evitadb.io/documentation/deep-dive/storage-model#cleaning-up-the-clutter) may occur, requiring significant IOPS and thus potentially causing noticeable latency during data writes.

Most clients don't need immediate visibility of their changes after a transaction completes. Usually, it's enough to ensure changes are securely accepted and written to persistent storage without getting lost, avoiding the need to repeat operations due to write failures. Such clients only need to wait until the `WAIT_FOR_WAL_PERSISTENCE` phase, thus shortening their perceived transaction time.

## Issues with timeout in the gRPC protocol

If using evitaDB in a client/server architecture (not embedded within your Java application), communication occurs via the [gRPC protocol](https://grpc.io/docs/). gRPC is built on HTTP/2 and facilitates efficient client-server communication. One of gRPC’s features is setting a timeout for individual calls. If the server doesn't respond within the specified timeout, the client receives an error.

Transaction/session termination operations are implemented as unary calls in gRPC—clients send a request and wait for a response. If the server doesn’t respond within the timeout, the client receives an error. If a client awaits propagation to the shared state with a strict timeout, the client might not receive a response in time, resulting in uncertainty about whether the transaction was successfully processed. The transaction might have been successfully accepted, confirmed, and logged to WAL, yet not propagated into the shared state. The client may decide to retry the transaction unnecessarily, as changes might have already been applied.

<Note type="info">

Of course, connection loss between client and server could still happen before the client receives confirmation of the `WAIT_FOR_WAL_PERSISTENCE` phase. Typically, gRPC clients and servers handle this by throwing exceptions. On the server, transaction processing stops, while the client receives an exception, necessitating a retry. If the `WAIT_FOR_WAL_PERSISTENCE` phase completes successfully, the server continues processing, and the client knows the transaction advanced sufficiently, avoiding retries.

</Note>

For complete control over transaction processing, there's a third variant:

```java
final CommitProgress commitProgress = evita.updateCatalogAsync(
    "evita-tutorial",
    session -> {
        // create a new brand
        session.createNewEntity(BrandEditor.class)
                .setName("Apple", Locale.ENGLISH)
                .upsertVia(session);
    }
);
commitProgress.onConflictResolved()
    .thenAccept(
        commitVersions -> System.out.println(
            "Tx accepted, changes will be visible in version: " + commitVersions.catalogVersion() + "."
        )
    );
commitProgress.onWalAppended()
    .thenAccept(
        commitVersions -> System.out.println(
            "Tx written to WAL."
        )
    );
commitProgress.onChangesVisible()
    .thenAccept(
        commitVersions -> System.out.println(
            "Tx changes visible to all now."
        )
    );
```

This call returns a <SourceClass>evita_api/src/main/java/io/evitadb/api/CommitProgress.java</SourceClass> object, providing promises (`CompletionStage`) for each transaction processing phase. Clients gain complete visibility into the server's transaction processing status. In gRPC implementations, this method uses [streaming](https://grpc.io/docs/what-is-grpc/core-concepts/#server-streaming-rpc), enabling extended connection via heartbeat messages. Using the Java client interface, these implementation details are typically abstracted.

The above example outputs these messages to the console:

```
Tx accepted, changes will be visible in version: 2.
Tx written to WAL.
Tx changes visible to all now.
```

## Conclusion

A slightly more complex version is available in our [tutorial](https://github.com/FgForrest/evitaDB-tutorial/blob/01.07-listening-to-transaction-processing/src/main/java/io/evitadb/tutorial/Main.java). Another benefit of evitaDB’s approach is that after transaction acceptance (1st phase), you immediately receive information about the version (`catalogVersion`) in which changes will appear in the shared state. Based on this information, clients can draw clear conclusions. For example, upon opening a session to read data, you can check the catalog version and compare it with the version received upon transaction acceptance:

```java
final long currentCatalogVersion = evita.queryCatalog(
    "evita-tutorial",
    EvitaSessionContract::getCatalogVersion
);
```

If the returned catalog version is lesser, you know changes aren't visible yet and can decide whether to wait or continue working with the current data.

In many scenarios, such transparency is beneficial — but detailed knowledge of server-side processes isn't necessary for everyday database usage. Most clients are likely satisfied with synchronous write operations without considering detailed transaction processing. However, it's good to know you can dive deeper and maintain full control over what's happening on the server.