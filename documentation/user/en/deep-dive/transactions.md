---
title: Transactions
perex: |
  Transactions are a fundamental part of the database system. They ensure that the database remains in a consistent state even in the event of failures or concurrent access by multiple users. In this article, we will explore the concept of transactions, their properties, and how they are implemented in evitaDB.
date: '16.5.2025'
author: 'Jan Novotný'
---

Readers who are familiar with database isolation levels may remember that evitaDB only supports [snapshot isolation](https://en.wikipedia.org/wiki/Snapshot_isolation), so they can skip this introductory chapter, which describes the context relating to this level of isolation.

The key concept of a transaction is that data written by a transaction is only visible within that transaction, and not in other simultaneous reader sessions/transactions. Once a transaction has been committed, its changes become visible to any readers opening a new transaction afterwards. In the terminology of multi-version concurrency control, this is called 'snapshot isolation': each client behaves as if it has a full copy of the database at the time the transaction starts. If the client does not open the transaction explicitly, it is opened implicitly for each query or update statement and closed automatically when the statement finishes. This level of isolation prevents write-write conflicts — if two parallel transactions attempt to update the same record, one of them will be rolled back. However, it does not prevent read-write conflicts, also known as write skew. For example, if there are two transactions A and B, transaction A reads record X, adds 1 to the read value and stores the result to record Y, and transaction B reads record Y, subtracts 1 from the value and stores the result to record X, both transactions will be successfully committed without a conflict. We don't recommend using evitaDB for banking systems, but for most other use cases, this level of isolation is sufficient.

## Atomicity of individual entity mutations

Snapshot isolation describes how an entire transaction is isolated from the others. Within a single transaction, evitaDB additionally guarantees that **each entity write is atomic on its own**. An `upsertEntity` or `deleteEntity` call, together with all the index changes it implies (attributes, references, facets, prices, hierarchy placement, reflected references), either applies completely or not at all.

If a single entity mutation fails part-way through — typically because it violates a unique constraint or another consistency rule after some of its index entries have already been written — the engine reverts exactly that entity's partial changes and keeps the rest of the transaction intact. The failing call throws an exception that the client may catch and then continue issuing further writes in the same transaction before committing. Entities written before the failure stay valid, and no half-applied index entry (such as an orphaned facet or a phantom price) is left behind.

This per-entity guarantee is finer-grained than the transaction-wide [atomicity described below](#atomic-transactions), and it does not depend on a transaction being open: writes made during bulk indexing in the WARM-UP phase behave exactly the same way — see [Bulk vs. incremental indexing](bulk-vs-incremental-indexing.md#atomicity-of-individual-writes).

## Lifecycle of a transaction

First, let's fast forward to the point at which the transaction is committed. Then, we will explain how we isolate the changes made by non-committed parallel transactions. A transaction commit is a multi-step process that ensures all changes made during the transaction are safely written to the database and made visible to other readers once fully integrated into all indexes. The commit process consists of the following steps:

1. parallel transaction conflict resolution
2. persistence of the transaction to the write-ahead log (WAL)
3. processing contents of the WAL and building a new "version" of the database
   - incorporating changes to the indexes and writing record payloads to the data storage files
   - persisting changes in indexes to the data storage files
   - compaction of the data storage files if necessary ([described in more detail here](storage-model.md#cleaning-up-the-clutter))
4. swapping the new version of the database with the current one
5. propagating the changes to the read nodes in a cluster setup

In the following sections, we will describe each of these steps in detail.

### 1. Conflict resolution

The first step is for the transaction processor to check that changes made by parallel transactions are not mutually exclusive. Each transaction knows the version of the database (`catalogVersion`) that it started with — its *snapshot version*. When a transaction is committed, it is assigned a new `catalogVersion` — its *commit version* — in which the changes made by that transaction will be incorporated. The catalog version is a monotonically increasing number that increments by one for each transaction.

These two versions define exactly which pairs of transactions can conflict. Two transactions are **successors** when the later transaction's snapshot version is greater than or equal to the earlier transaction's commit version — the later transaction already saw the earlier one's changes, so no conflict between them is possible. They are **concurrent** when the earlier transaction's commit version is greater than the later transaction's snapshot version — the earlier transaction's changes were invisible to the later one's snapshot. The conflict resolver therefore examines every change committed after the committing transaction's snapshot version, up to the latest version written to the write-ahead log — including transactions that are already durable but not yet visible in the live view. All mutations in the transaction produce a so-called conflict key, which is compared with the conflict keys registered by the transactions committed in that window (each registered under its commit version). If there is a conflict, an exception is raised and the incoming transaction is rolled back — the transaction that committed first always wins.

<Note type="info">

Recently committed conflict keys are kept in an in-memory ring buffer of configurable size. When a transaction commits from a snapshot so old that the buffer no longer covers it (e.g. a very long-running session under heavy write traffic), the resolver falls back to replaying the conflict keys of the missing window directly from the write-ahead log, so the concurrency check is never silently skipped.

</Note>

#### Conflict resolution levels

A conflict policy has two parts: a mandatory **coarse scope** (the level at which two writes are considered to collide) and an optional set of **granular refinements** that narrow the `ENTITY` scope down to individual parts of an entity.

The coarse scope is a single mutually exclusive value:

| Coarse scope | Two transactions conflict when they both touch… |
|---|---|
| `NONE` | never — conflicting changes are allowed (last writer wins) |
| `CATALOG` | the same catalog (any change to any entity conflicts) |
| `COLLECTION` | the same entity collection |
| `ENTITY` | the same entity (the default) |

When the coarse scope is `ENTITY`, it can be refined so that only writes to the *same part* of an entity collide. Each granular refinement admits a specific write-skew anomaly in exchange for fewer rollbacks:

| Granular refinement | Conflicts narrowed to the same… |
|---|---|
| `ENTITY_ATTRIBUTE` | attribute of the entity |
| `ASSOCIATED_DATA` | associated data of the entity |
| `PRICE` | price of the entity |
| `HIERARCHY` | hierarchy placement of the entity |
| `REFERENCE` | reference of the entity |
| `REFERENCE_ATTRIBUTE` | attribute of a reference of the entity |

Granular refinements are only meaningful under the `ENTITY` scope — a `CATALOG` or `COLLECTION` lock already subsumes every finer scope.

#### Where a policy is declared, and how it is resolved

For every write, evitaDB answers two questions in turn:

1. **Which `ConflictResolution` governs this entity type?** — resolved once per entity type.
2. **Given that policy, what conflict key does *this particular field* emit?** — decided per written item.

Keeping these two steps apart is the key to reading the model. Step 1 picks the *policy*; step 2 turns the policy into the actual *keys* that are compared for collisions.

##### Step 1 — resolving the policy (most specific wins, outright)

The policy comes from the **schema**, never from the session — so two concurrent transactions touching the same entity type always resolve to the *same* policy, which is exactly what lets the write-time and recompute-time key generators agree. evitaDB looks in three places, from most specific to least, and takes the **first** that declares a `ConflictResolution`:

1. **Entity schema** — an optional `ConflictResolution` on the entity collection.
2. **Catalog schema** — an optional `ConflictResolution` on the catalog.
3. **Engine default** — the `conflictPolicy` setting in the [transaction configuration](#configuring-conflict-resolution) (`ENTITY` out of the box).

This is a **whole-record override**: the first declared `ConflictResolution` wins *entirely* — its coarse scope *and* its granular set — with no field-by-field merging with the levels below it. A `ConflictResolution` is a coarse scope (`NONE` / `CATALOG` / `COLLECTION` / `ENTITY`) plus, only under `ENTITY`, a set of granular refinements such as `ENTITY_ATTRIBUTE`.

<Note type="question">

<NoteTitle toggles="true">

##### What do the coarse scopes mean in practice?
</NoteTitle>

The coarse scope decides *how wide a net* a single write casts when looking for a competing transaction. From widest to narrowest:

- **`CATALOG`** — any two transactions that both change *anything* in the catalog conflict, even if they touch completely unrelated entities in different collections. If transaction A adds a `Brand` and transaction B, started from the same catalog version, edits a `Product` price, one of them is rolled back. This effectively serializes all writes to the catalog: at most one write transaction per catalog version survives. Use it only when a write must observe a fully consistent, frozen snapshot of the *entire* catalog (bulk imports, global re-pricing) — it trades throughput for the strongest guarantee.

- **`COLLECTION`** — the net is drawn around a single entity collection. Two transactions conflict only when they both change an entity of the *same type* — two `Product` writes collide (even on different products, e.g. product #42 and product #99), but a `Product` write and a concurrent `Category` write proceed side by side. Reach for it to defend a **type-wide invariant that entity-level locking cannot see because the competing writes touch *different* entities** — a write-skew anomaly. Classic case: *"at most one `Product` may be flagged as the storefront hero."* Two transactions that each set a *different* product's hero flag land on different entities, so under `ENTITY` both commit and you end up with two heroes; `COLLECTION` makes the two `Product` writes collide, so one is rolled back. The cost is bluntness — it serializes *every* write to the collection, including products in unrelated categories — so prefer it only when the invariant truly has no single owning entity. If it does have one — for example *"exactly one default product per category"* belongs to the **category** — model the flag on that owner entity (a `defaultProductId` on `Category`) so the competing writes hit the *same* entity, and stay on the cheaper `ENTITY` scope instead. evitaDB has no "group of entities sharing an attribute value" scope between `ENTITY` and `COLLECTION`.

- **`ENTITY`** (the default) — the net is drawn around a single entity. Two transactions conflict only when they change the *same* entity (same type **and** same primary key); concurrent edits to two different products never collide. This is the sweet spot for most workloads, and it is the only scope that granular refinements can narrow further (see [Step 2](#step-2--turning-the-policy-into-keys-per-written-field)).

- **`NONE`** — no net at all: concurrent writes never conflict and the last commit silently wins (see the last-writer-wins warning below).

Each coarser scope *contains* the finer ones: a `CATALOG` conflict subsumes any `COLLECTION`, `ENTITY` or granular collision within it, and a `COLLECTION` conflict subsumes any per-entity collision in that collection. That containment is what the [next section](#containment--a-coarse-policy-always-beats-a-finer-one) formalizes.

</Note>

##### Step 2 — turning the policy into keys (per written field)

Once the entity's policy is known, each written field decides which conflict key it contributes:

- Under a coarse `NONE`, `CATALOG` or `COLLECTION` policy, individual fields have no say — the coarse scope dominates (no keys under `NONE`; one catalog/collection key otherwise). **Per-item overrides are ignored here.**
- Under the coarse `ENTITY` policy, each field emits either its own fine-grained key or falls back to the whole-entity key, according to:
  - the field's **per-item `ConflictResolutionOverride`** (`INHERITED` / `GRANULAR` / `ENTITY`) declared on the attribute / associated-data / reference schema, and, when the field inherits,
  - the entity policy's **granular set** (e.g. does it contain `ENTITY_ATTRIBUTE`?).

  `GRANULAR` opts that one field into its own key; `ENTITY` pins it to the whole-entity key; `INHERITED` (the default) follows the granular set. Because per-item overrides only bite under a coarse `ENTITY` policy, a `GRANULAR` override on a field of an entity resolved to `NONE` does **nothing** — to re-enable detection you must raise the *coarse* policy, not the field.

<Note type="question">

<NoteTitle toggles="true">

##### How do I make edits to the *same* attribute conflict, but edits to *different* attributes coexist?
</NoteTitle>

Suppose two editors save a `Product` at the same time: one changes `quantity`, the other changes `name`. You want two concurrent edits to **the same** attribute to conflict (so a stock update is never silently lost), but edits to **different** attributes to coexist. The switch that does this is the `ENTITY_ATTRIBUTE` refinement, declared on the entity schema:

```java
session.defineEntitySchema("Product")
	.withConflictResolution(
		new ConflictResolution(
			ConflictPolicy.ENTITY,
			EnumSet.of(GranularConflictPolicy.ENTITY_ATTRIBUTE)
		)
	)
	.withAttribute("quantity", Integer.class)
	.withAttribute("name", String.class)
	.updateVia(session);
```

Now every attribute write carries a key scoped to *that attribute* (`quantity of Product #42`) rather than to the whole product, and two transactions collide only when their keys match:

| Transaction A writes | Transaction B writes | Conflict? | Why |
|---|---|---|---|
| `quantity` of #42 | `quantity` of #42 | **yes** | identical key — `quantity of Product #42` |
| `quantity` of #42 | `name` of #42 | no | different keys — independent parts of the same entity |
| `name` of #42 | `name` of #42 | **yes** | identical key — `name of Product #42` |
| `quantity` of #42 | `quantity` of #99 | no | different entity |

One subtlety worth stating plainly: this makes *different* attributes independent, but it does **not** exempt any single attribute from detection — two writers editing the same `name` still conflict (row 3). There is deliberately no per-attribute "last writer wins"; the finest exemption evitaDB offers is *"collide only on the same attribute,"* and it applies symmetrically to every attribute. To drop detection for a field entirely you must step the *coarse* policy down to `NONE` (see the warning below), which switches it off for the whole entity, not one field.

Drop the `ENTITY_ATTRIBUTE` refinement (a bare `new ConflictResolution(ConflictPolicy.ENTITY)`) and every attribute write falls back to the whole-entity key — row 2 would then conflict too, because the entity as a whole is the unit of contention.

</Note>

<Note type="question">

<NoteTitle toggles="true">

##### How do I relax contention on just one field, keeping the rest of the entity serialized?
</NoteTitle>

The schema above flips *every* attribute of `Product` to attribute-level detection. If instead you want the entity to stay serialized as a unit but relax contention on a single hot field — say a `description` that many editors touch independently — declare the refinement per item with a `ConflictResolutionOverride` rather than on the whole entity:

```java
session.defineEntitySchema("Product")
	// the entity as a whole stays at whole-entity contention…
	.withConflictResolution(new ConflictResolution(ConflictPolicy.ENTITY))
	.withAttribute(
		"description", String.class,
		// …except this one field, which gets its own attribute-scoped key
		whichIs -> whichIs.withConflictResolutionOverride(ConflictResolutionOverride.GRANULAR)
	)
	.withAttribute("quantity", Integer.class) // INHERITED → whole-entity contention
	.updateVia(session);
```

Here `quantity` (and every other `INHERITED` field) still collides with *any* concurrent write to the same product, while two `description` edits collide only with each other. The mirror image — pinning one consistency-critical field to the whole entity while the rest of the entity runs at attribute granularity — uses `ConflictResolutionOverride.ENTITY` on that one field.

</Note>

##### Schema mutations

Schema mutations don't allow customizable conflict policies and always use the same conflict key strategy: entity schema mutations deny changes to the same entity collection, and catalog schema mutations deny changes to the same catalog.

#### Containment — a coarse policy always beats a finer one

Conflict detection is based on **containment, not just equality**. A coarse conflict key conflicts with every finer key it contains, in **both** temporal directions (regardless of which transaction committed first). The key hierarchy climbs `reference-attribute → reference → entity`, `attribute / associated-data / price / hierarchy → entity`, and `entity → collection`; a `CATALOG` key contains everything in the catalog.

This is what makes the following pairs conflict even though the two sides operate at different granularities:

- an entity **deletion** vs. a granular attribute update of that entity,
- a **scope change** (moving an entity between scopes) vs. a granular update,
- a **reference removal** vs. an update to that reference's attribute,
- two concurrent **creations** of the same client-assigned primary key with disjoint attribute sets (a creation always emits an entity-level key),
- an entity deletion vs. a commutative **delta** write.

<Note type="info">

There are also special types of safe mutations that can help minimize conflict. The most useful is the **"delta" attribute mutation** (add/subtract a value): two delta mutations of the same attribute *commute*, so they never conflict with each other. Reach for delta mutations first whenever several transactions increment or decrement the same hot value (stock counts, counters), and only fall back to absolute writes when you genuinely need to overwrite. A delta that carries a post-application range check (for example "the result must stay ≥ 0") still conflicts with any *absolute* overwrite of the same attribute, because the overwrite would invalidate the range guard.

</Note>

<Note type="warning">

**`NONE` (last-writer-wins) disables conflict detection.** Under `NONE`, no conflict keys are produced (the only exception being a range-constrained delta, whose post-application check must still run), so write-write conflicts are silently resolved by whichever transaction commits last. The same caveat applies, in a narrower form, to every granular refinement: opting an attribute out of entity-level detection means two transactions can concurrently change *different* parts of the same entity and both succeed, which can break cross-part invariants (the classic write-skew anomaly). Choose the finest granularity only for parts whose independence you can guarantee.

</Note>

#### Diagnosing a conflict

When a conflict rolls back a transaction, the raised `ConflictingCatalogMutationException` reports the colliding **conflict key**, the **catalog version** at which the conflicting change committed, the **effective policy** that was in force, and the **schema layer** it was resolved from (entity schema / catalog schema / engine default). This makes it possible to tell at a glance whether a rollback came from an over-broad default or from a deliberately coarse schema declaration.

#### Configuring conflict resolution

The engine-wide default lives under `transaction.conflictPolicy` in the [server configuration](../operate/configure.md). It accepts an object with a coarse `policy` and an optional `granularity` list:

```yaml
transaction:
  conflictPolicy:
    policy: ENTITY
    granularity: [ ENTITY_ATTRIBUTE, PRICE ]
```

A bare scalar is accepted as shorthand for a coarse-only policy (`conflictPolicy: ENTITY`).

### 2. Write ahead log persistence

All transactions and their mutations in evitaDB databases are first written to [the write-ahead log](https://en.wikipedia.org/wiki/Write-ahead_logging). The write-ahead log (WAL for short) is described in more detail in [the storage model article](storage-model.md#write-ahead-log-wal). Transactions are appended to the WAL in the order they are committed, with the catalog versions of subsequent transactions always being greater than those of previous transactions. Writing to the WAL is a blocking operation, meaning the transaction processor waits until the entire transaction has been successfully written to a WAL file and synchronized with the disk. This ensures that the transaction is durable and can be recovered in the event of a crash.

<Note type="info">

In fact, there are two types of WAL file. The first type, an "isolated" WAL, is created for each transaction in a separate file. This enables transactions to be written to their respective isolated WALs simultaneously (in-parallel without blocking each other). The second type is the "global" WAL file, to which the isolated transaction WAL files are copied in a single low-level operation. This copy operation is executed sequentially in the "WAL persistence" step as each transaction is processed. Isolated WAL files are removed immediately after their contents are copied to the global WAL file.

**Side note:** We made life easier by enforcing a single writer for all data files. In other words, this means we can process transactions sequentially. If we knew that the transactions were being written to non-overlapping data files, we could write the data in parallel. However, as this is not expected to be a common use case, we decided not to implement it.

</Note>

WAL does not participate in the standard [compaction process](storage-model.md#cleaning-up-the-clutter), so it would grow indefinitely. This is why a threshold is configured to limit the maximum size of the WAL file. If the threshold is reached, evitaDB starts writing to a separate file (segment), but leaves the original file in place. A single transaction must always be fully written to the same WAL segment, so huge transactions may cause WAL file sizes to exceed their configured limits. The number of WAL files kept is limited, and this limit can only be exceeded if the changes in them have not yet been applied to the indexes. The WAL files are removed once the transaction processor confirms that all changes have been applied to the indexes (and if running in distributed mode, also propagated to all other nodes).

WAL files are a crucial part of the database and are used for the following:

* applying a missed committed transaction to the indexes on the primary node (recovery after a crash).
* change replication across multiple read nodes.
* [point-in-time backup and restore (PITR)](../operate/backup-restore.md)
* [change data capture (CDC)](../use/api/capture-changes.md) — streaming changes to external systems
* auditing in evitaLab

Up to this point, if an exception occurs, the transaction is effectively rolled back, but the world keeps spinning.

### 3. Processing contents of the WAL

In this phase, we read the unprocessed contents of the WAL file and apply changes to the indexes of the current catalog version and create payload records in shared data files. These changes are isolated and still invisible to other readers. This phase operates in a "time-window" manner, meaning that it tries to replay as many transactions as possible within a dedicated time window. When the time limit is reached or the entire contents of the WAL file have been processed, the transaction processor will create a new instance of the catalog data structure with a particular version (i.e. the last transaction processed will be assigned the value of the variable catalogVersion). This new instance is then passed to the next phase.

If the engine fails at this stage, all transaction processing will be halted. The engine cannot skip the committed transaction persisted in the WAL file, nor can it progress to the next one. It will try indefinitely to replay the last transaction from the WAL file. This ultimately indicates an error in the database engine that needs to be analyzed and corrected. However, the mechanism does not trap the thread in an infinite loop; it always attempts to process the problematic transaction again once a new transaction has been committed.

<Note type="info">

The storage model of evitaDB is strictly append-only. Once a record has been written to the data file, it cannot be changed or deleted. This fundamental design decision allows us to avoid the complexity of synchronization between writer and reader threads. Records in the file are located using pointers stored in the indexes. If there is no pointer to a record in the index, the record is considered garbage and is automatically removed during the next compaction.

</Note>

### 4. New catalog version propagation

This phase is very quick and simply involves replacing the root pointer of the "current" catalog instance with the newly created one. Once complete, the newly created sessions/transactions will see the changes made in that particular transaction. The old catalog instance is not removed immediately but kept in memory until all sessions using this version of the catalog are closed (as required by the snapshot isolation level). Once there are no more sessions using the old catalog instance, it is removed from memory, after which the Java garbage collector will take care of it.

## Horizontal scaling

<Note type="warning">

Distributed mode of evitaDB is still a work in progress (see [issue #109](https://github.com/FgForrest/evitaDB/issues/109)).

</Note>

We plan to implement replication model similar to [streaming replication in PostgreSQL](https://scalegrid.io/blog/comparing-logical-streaming-replication-postgresql/)
or [statement-based replication in MySQL](https://dev.mysql.com/doc/refman/8.0/en/replication-sbr-rbr.html). evitaDB is designed for read-heavy environments, so we plan to stick to single master, multiple reader nodes model with dynamic master (leader) election. All writes will target the master node, which will maintain the primary [WAL](storage-model.md#write-ahead-log-wal).

All read nodes maintain an open connection to the master node, streaming and replaying changes in the WAL file locally. This connection is always open and downloads all changes present in the WAL file to each replica node. This means that, once the transaction processing has reached the final phase [catalog propagation](#4-new-catalog-version-propagation), mutations will start to be streamed to the replicas. As all conflicts are resolved on the master node before the transaction is committed and written to the WAL file, all mutations are expected to be successfully processed by all replicas.

When a new replica node is added to the cluster, it selects another replica or master node and fetches a binary version of their data storage files, which are cleaned of obsolete records (see [active backup](storage-model.md#backup-and-restore)). The streaming producer discards obsolete records from the storage file maintainer (the selected replica or master node) on the fly, so no obsolete data is streamed to the new node. The backup process is always locked to valid data in a particular catalog version (including the position in the WAL file), and, due to the append-only nature of the data files, processing new transactions on this node does not need to be paused, and the node operates as usual. As all these operations work at a "binary level", creating a new replica is a reasonably fast process.

## Parallel transaction isolation in detail

Two areas relating to transactions need to be addressed:

1. record payload data
2. indexes referring to that data

### Isolation of non-committed record payload data

Non-committed transactions write their record payload data to temporary memory, which is cleared when the transaction is finished (either through a commit or a rollback). Long transactions with large payloads can currently affect the health of the database engine, so we recommend avoiding such transactions.

<Note type="warning">

The transactional memory resides on the Java heap alongside all the other key evitaDB data structures. If the transaction is very large, it could consume all the available memory, causing an `OutOfMemoryException` and affecting the rest of the system, including read-only sessions. To avoid this, we need to limit the scope of the transaction. However, retrieving information about the size of data structures is not an easy task on the [Java platform](https://dzone.com/articles/java-object-size-estimation-measuring-verifying), and we can only retrieve a rough estimate. We plan to calculate an estimate of the transaction size and limit the total size of the transaction, as well as the total size of all transactions processed in parallel, to avoid exhausting all free memory. But this is still a work in progress - [see issue #877](https://github.com/FgForrest/evitaDB/issues/877).

</Note>

### Isolation of changes in the memory indexes

The second part — the indexes — is where all the complexity lies. A frequently used approach in databases is a table/row locking mechanism or a transaction ID number stored alongside the record pointer in a B-tree. This is consulted when a record is about to be read/updated in a particular transaction (see [Goetz Graefe Modern B-Tree Techniques](https://w6113.github.io/files/papers/btreesurvey-graefe.pdf), or the Helsinki University of Technology team's [Concurrency Control for B-Trees with Differential Indices](https://citeseerx.ist.psu.edu/viewdoc/download?doi=10.1.1.85.602&rep=rep1&type=pdf)).

Since we keep all indexes in memory keeping 8B `catalogVersion` next to each record would require a lot of memory, and we can take a different approach: software transactional memory. The idea is to keep the original data structure immutable and create a diff layer that is applied on the fly when reading modified data, but only by the thread that is handling the open transaction. This way, we can avoid locking the indexes and allow multiple transactions to read from and write to the shared "immutable" base concurrently.

Our indexes comprise multiple transactional data structures, which are explained in more detail in [the reference section](#transactional-data-structures).

#### Software transactional memory

We isolate concurrent updates to the indexes made by different threads, placing them in separate memory blocks in the form of a read-write diff overlay that envelops the original, immutable data structure. When a thread reads data during a transaction, it accesses the data via an overlay that applies the diff in real time, enabling the transaction to dynamically view its own changes. If there are no updates in the transaction, there are also no diff layers, meaning the transaction reads directly from the underlying immutable data structure. As the diff overlays are stored in a ThreadLocal object bound to the thread processing a specific transaction, transactions cannot see each other's changes. This approach is often labelled software transactional memory [STM](https://en.wikipedia.org/wiki/Software_transactional_memory).

##### Atomic transactions

The only way to make transactional changes atomic is to gather all changes in a volatile diff layer that is only used for the particular transaction. When a transaction is committed, a new instance of the entire catalog data structure must be built (i.e. new instances of updated entity collections, indexes, etc.). This new instance then replaces the current catalog instance with a single call to the `AtomicReference#compareAndExchange` method.

<Note type="info">

Although we state that the entire catalog data structure is to be reinstantiated, this is not entirely true. If that were the case, the transactions would be too expensive for large datasets, and the mechanism would not be feasible. In reality, we only create new instances for the modified parts of the catalog data structures and the catalog itself. Imagine the catalog data structure as a tree, with the catalog instance at the top and all the inner data as a directed acyclic graph. You will realize that the new instances are required only for the changed data structure itself, plus all its "parents" towards the catalog instance at the top of the graph. This technique is called [path copying](https://en.wikipedia.org/wiki/Persistent_data_structure#Path_copying).

</Note>

If the transaction is rolled back, we simply discard the entire memory block of the diff layer from the ThreadLocal variable and allow the Java garbage collector to take over.

<Note type="info">

The diff layer approach is not used in the special case of <SourceClass>evita_store/evita_store_key_value/src/main/java/io/evitadb/store/offsetIndex/OffsetIndex.java</SourceClass>. This index tracks all record positions in a data file within a single hash map, providing quick O(1) access to the payload records. Rebuilding this index using the diff layer would involve constantly reallocating the entire hash map, which would be inefficient. Therefore, this index only keeps the most recent record pointers, and tracks all changes to this map between the current and previous catalog versions. This history is retained for as long as there is a session using a particular old catalog version. Sessions targeting old catalog versions land on a value they should not see, so they analyze the history of this record and recover the correct pointer for their catalog version.

</Note>

##### Preventing update loss

The key problem with the described approach is that updates can easily be lost if the diff layer is not applied to the original data structure and included in the new catalog instantiation process.

To avoid this issue, we track every diff layer created in a particular transaction and mark them as consumed by the instantiation process when the transaction is committed. Finally, at the end of the transaction, we check that all diff layers have been marked as processed; if not, an exception is thrown and the transaction is rolled back. This resembles [double-entry accounting](https://www.investopedia.com/terms/d/double-entry.asp), where each positive operation must be accompanied by a negative one.

Such issues occur during development, so there must be a way to identify and solve these problems. This is actually very tricky, since there may be thousands of diff layers and assigning a specific layer to its creator/owner is challenging. Therefore, we assign a unique transactional object version ID when the diff layer is created and include it in the exception thrown for non-collected diff layers. When we replicate the problematic transaction in a test, the diff layer gets the same version ID repeatedly and we can track the exact moment and place of the layer's creation by placing a conditional breakpoint at the version ID generator class.

##### Testing

The integrity of the data structures is vital for the database. In addition to the standard unit tests, there is always one "generational" test that uses [a property based testing approach](https://en.wikipedia.org/wiki/Property_testing) approach. These tests use two data structures: our tested STM implementation and a [test double](https://en.wikipedia.org/wiki/Test_double) represented by a well-proven external implementation. For instance, in the generational test of the <SourceClass>evita_engine/src/main/java/io/evitadb/index/map/TransactionalMap.java</SourceClass> data structure, we use the JDK HashMap implementation as the test double.

The testing sequence is always similar.

1. at the start of the test, both the tested instance and the test double instance are created
2. both are filled with the same initial randomized data
3. in an iteration with a randomized number of repetitions:
   - a random operation is selected and executed with a randomized value on both the tested instance and the test double (an example of such an operation might be "insert value X" or "remove value Y")
   - a test then checks that the changes are immediately visible on the tested instance
   - the transaction is committed
4. after the commit, the contents of both data structures are compared and must be equal
5. new instances of the data structures are created with initial data taken from the result of the commit
6. steps 3-5 are repeated infinitely

Once this generational test has run for a few minutes without any problems, we can be confident that the STM data structure is correctly implemented. However, there is always a small chance that the test itself is incorrect. [Quis custodiet ipsos custodes?](https://en.wikipedia.org/wiki/Quis_custodiet_ipsos_custodes%3F)

## Transactional data structures

<Note type="warning">

**Data structures are planned to be replaced**

Most transactional data structures are suboptimal because they copy the entire contents to a new instance of the class at the moment of committing (the original instance must remain unchanged for other readers). Our primary focus was on read performance, so write performance was not a priority. We plan to improve the performance of these data structures in the future (see issue [#760](https://github.com/FgForrest/evitaDB/issues/760)), and to create Clojure data structures that are proven and work in an immutable-friendly fashion (for example [HAMT](https://en.wikipedia.org/wiki/Hash_array_mapped_trie)).

</Note>

### Transactional array (ordered)

The transactional array (e.g. <SourceClass>evita_engine/src/main/java/io/evitadb/index/array/TransactionalIntArray.java</SourceClass> and similar) mimics the behavior of a plain array, and there are multiple implementations of it.

- TransactionalIntArray: for storing primitive `int` numbers
- TransactionalObjectArray: for storing plain objects of any type
- TransactionalComplexObjectArray: for storing nested object structures that allow merging and the automatic removal of "empty" containers.

All arrays are naturally ordered by default. In the case of object implementations, the object must be comparable. The array implementation does not allow duplicate values. Therefore, in the event of any insertions/removals, the array knows which indexes will be affected internally. It is not possible to set a value on an index passed from outside logic.

All these implementations share the same idea: in transactional mode, all updates go to the transactional overlay that traps:

- inserts on certain indexes in an internal array of inserted values
- deletions on certain indexes in an internal array of removed indexes.

Using this information, the STM array can build a new array combining the original values with all the changes. To avoid creating a new array (and memory allocations) for each operation, there are optimized methods that operate directly on the diff:

* `indexOf`
* `contains`
* `length`

The <SourceClass>evita_engine/src/main/java/io/evitadb/index/array/TransactionalComplexObjArray.java</SourceClass> class is much more complex — it accepts functions that operate on nested structures.

- `BiConsumer producer`: this function takes two containers and combines them into one output container containing an aggregate of their nested data
- `BiConsumer reducer`: this function takes two containers and removes/subtracts the nested data of the second container from that of the first
- `Predicate obsoleteChecker`: this function tests whether the container contains any nested data. If not, the container may be considered removed; the predicate is consulted after the reduce operation

This implementation provides the ability to partially update the objects held within it. For example, consider a record with the following structure:

* `String: label`
* `int[]: recordIds`

If we then insert two such records into the TransactionalComplexObjectArray with the following data:

* label = `a` recordIds = `[1, 2]`
* label = `a` recordIds = `[3, 4]`

The array will produce the result with one record: `a`: `[1, 2, 3, 4]`.

If we remove the record `a`: `[2, 4]`, the array will produce the result: `a`: `[1, 3]`.

If we apply the removal of the record again - `a`: `[1, 3]`, the array will produce an empty result.

Unfortunately, with this implementation, we cannot provide optimized methods such as:

* `indexOf`
* `length`

We have to compute the entire merged array first in order to access these properties. While this data structure could be subject to significant optimizations, it is also quite challenging to implement correctly due to the nature of nested structures.

### Transactional unordered array

This version of the transactional array differs from the [previous one](#transactional-array-ordered) in that it allows duplicate values. It is also unordered and enables the client to control where new values are inserted or existing ones removed.

The database requires only a single implementation of this structure: <SourceClass>evita_engine/src/main/java/io/evitadb/index/array/TransactionalUnorderedIntArray.java</SourceClass>.

The diff layer implementation for the unordered array is essentially the same as for the [ordered array](#transactional-array-ordered), with one exception. The inserted values retain information about the relative index within the segment inserted at a specific position within the original array.

This array has a special, fast implementation that works on the diff layer for the following methods:

* `indexOf`
* `contains`
* `length`

### Transactional list

The <SourceClass>evita_engine/src/main/java/io/evitadb/index/list/TransactionalList.java</SourceClass> mimics the behaviour of the Java util List interface, enabling it to contain any object. The list can contain duplicates and is unordered. The current implementation is suboptimal and could be improved in the same way as the [unordered array](#transactional-unordered-array).

The diff layer contains a sorted set of indexes that were removed from the original list, as well as a map of new values and the indexes at which they were inserted. When a new item is inserted or removed from the diff layer, all the indexes after this value need to be incremented or decremented. Therefore, the "add/remove first" operation always has O(N) complexity. Conversely, the unordered array splits inserts into multiple segments, so the complexity is usually lower — O(N) is only the worst-case complexity for an unordered array.

### Transactional map

The <SourceClass>evita_engine/src/main/java/io/evitadb/index/map/TransactionalMap.java</SourceClass> class mimics the behaviour of the Java `java.util.Map` interface, allowing it to contain any key/value pairs. In this case, the implementation is straightforward: the diff layer contains a set of all keys removed from the original map, as well as a map of all key/value pairs that have been updated or inserted into the map.

When logic attempts to retrieve a value from the map, the diff layer is first consulted to determine whether the key exists in the original map with no removal order in the layer or whether it has been added to the layer. The iterator of entries/keys/values first iterates over all existing, non-removed entries, and then iterates through entries added to the diff layer.

### Transactional set

The <SourceClass>evita_engine/src/main/java/io/evitadb/index/set/TransactionalSet.java</SourceClass> class mimics the behaviour of the Java `java.util.Set` interface. In this case, the implementation is straightforward: the diff layer contains a set of all keys removed from the original map, as well as a set of added keys.

### Transactional bitmap

A bitmap is a set of unique integers in ascending order. This data structure is similar to a [transactional array](#transactional-array-ordered), but is limited to integers only and enables much faster operations on the number set. This implementation wraps an instance of the internal class RoaringBitmap. The reasons for using this data structure, and more detailed information about [RoaringBitmaps](https://github.com/RoaringBitmap/RoaringBitmap), are stated in the [the query evaluation chapter](https://evitadb.io/research/in-memory/thesis#boolean-algebra). RoaringBitmap is a mutable data structure and a third-party library. As we have no control over it, we wanted to hide it with our interface. This is why the <SourceClass>evita_engine/src/main/java/io/evitadb/index/bitmap/Bitmap.java</SourceClass> interface was created, to ensure that the entire codebase works with it instead of `RoaringBitmap` directly.

The <SourceClass>evita_engine/src/main/java/io/evitadb/index/bitmap/TransactionalBitmap.java</SourceClass> allows insertions and deletions to be trapped from the original bitmap in the diff layer. When the bitmap needs to be used for reading, a new `RoaringBitmap` is computed by applying insertions by boolean AND on the original bitmap and applying removals by boolean AND NOT on the fly. To avoid this costly operation, the result is cached for subsequent read requests, but must be cleared upon the first subsequent write.

This computational method clones the entire original `RoaringBitmap` twice and is therefore suboptimal. Unfortunately, `RoaringBitmap` does not provide us with better options. An ideal implementation would require `RoaringBitmap` to be internally immutable and produce a new instance every time a write operation occurs. As `RoaringBitmaps` work internally with separate blocks of data, the new immutable version could reuse all the blocks that were not affected by the write action, cloning or altering only the blocks where the changes occurred. However, this would require substantial changes to the internal implementation and would probably be dismissed by the authoring team.

### B+ tree

This is a standard B+ tree implementation with a transactional diff layer. The implementation creates a diff layer for each modified leaf segment of the tree, as well as for the parent chain to the root, with new instances that refer to the underlying diff layers (and the original, non-modified leaves). The new B+ tree is materialized at the moment of commit. The entire process resembles copy-on-write data structures, but the copied blocks are quite small compared to the entire tree. The B+ tree is used for all indexes that require a sorted order of keys.

### Sequences

evitaDB uses several sequences to assign unique, monotonic identifiers to various objects. These sequences are not part of the transaction process and their progress is not rolled back. All sequences are implemented internally as either an `AtomicLong` or an `AtomicInteger`, which allow the retrieval of incremented values in a thread-safe manner.

Currently, we do not plan to support multiple writer mode in the [distributed setup](#horizontal-scaling), which means we can safely use Java's internal atomic types to manage sequences because new numbers will always be issued by a single master node (JVM).

The sequences managed by the evitaDB are:

* entity type sequence: assigns a new ID to each entity collection, allowing the collection to be addressed by a small number instead of duplicating and increasing the size of the string value
* entity primary key sequence: assigns a new ID to each created entity, in case the entity schema requires it automatic primary key assignment
* entity primary key sequence: assigns a new ID to each created entity, in case the entity schema requires automatic primary key assignment
* index primary key sequence: assigns a new ID to each created internal index
* transaction ID Sequence: assigns a new ID to each opened transaction