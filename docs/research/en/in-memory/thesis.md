---
title: In-memory Implementation of Evita
perex: |
	The document contains the original research paper for the in-memory implementation of the evitaDB prototype. 
	This version of the prototype proved to be viable and was transformed into the final evitaDB implementation.
date: '15.12.2022'
author: 'Ing. Jan Novotný (FG Forrest, a.s.)'
proofreading: 'done'
---

The in-memory implementation is built from the ground up to achieve the desired evitaDB functionality. The key
principles embodied in design process were:

* **Immutability:**
	All key data structures are designed to be immutable. We
	use [Java records](https://www.baeldung.com/java-record-keyword) everywhere it makes sense - records could open up a
	significant memory savings once [project Valhalla](#project-valhalla-and-lilliput) is merged into the Java mainline.
	The immutability introduces implicit thread safety and opens the path to lock-free concurrent processing.
* **Mutations:**
	When immutable data needs to be updated, we wrap them into a temporary mutable object using
	a [builder pattern](https://en.wikipedia.org/wiki/Builder_pattern). The builder is capable of generating a set of
	mutation operations that gradually modify the source data structure to the fully updated form. These mutations
	represent atomic operations upon the data structure and can be translated to a modification
	protocol [sent over the network](#distributed-model), stored in a [WAL](#write-ahead-log) and so on.
* **Append-only approach:**
	File write operations are all designed to be append-only. There is no single place that would require seek & write in
	the file. Append-only approach guarantees very good performance characteristics and ability to
	easily [back up the data on the fly](#backup-&-restore). The disadvantage is that the size of the file quickly
	increases and the file gets fragmented over time (see [vacuuming](#fragmentation-and-vacuuming) for mitigation steps).
* **Minimize external dependencies:**
	We purposefully limit using external dependencies to a bare minimum. However, we take a lot of inspiration from them.
	There are a lot of functions inspired or copied
	from [Apache Commons](https://commons.apache.org/), [Spring Framework](https://spring.io/)
	or [Guava](https://github.com/google/guava). Using those functions from the original sources would grow the size (
	though, we'd use only a tiny fragment of original libraries) of the evitaDB binary and complicate the usage as an
	embedded database, which is something we want to support. We aim for evitaDB to be as small a library as possible.
* **Stick to primitives:**
	Wherever possible we use [java primitive types](https://www.baeldung.com/java-primitives) and operate on them. The
	smaller the data type, the better. Since our implementation keeps all crucial data in memory, we need to carefully
	consider what data structures / data types to use to fit as much data in memory as possible. All indexes take
	advantage of the 32-bit `int` data type that offers a reasonable range while consuming moderate memory space. See
	chapter [indexes](#indexes) for tactics used in this area.
* **Indexes fully in memory:**
	All data needed to resolve the primary keys of the entities matching the query are maintained in RAM. This is the main
	advantage of our implementation since RAM is pretty fast. Also, it is the main disadvantage - if our hardware does not
	have enough memory to host all the database indexes, evitaDB would be unusable. This is the key difference between our
	database system and other database systems available on the market. Alternative database systems usually operate with
	much larger data than the memory can hold and use data-structures / algorithms friendly to the rotating / solid state
	disk drives. They trade off querying speed for much larger data size limits, we do the opposite.

All the above-mentioned principles allow us to achieve exceptional performance in all e-commerce use-cases which we
decided to solve in evitaDB. Green field implementation allowed us to be in control of any detail of the query
processing and transaction handling.

## Storage model

Data is stored in a [flat file](https://en.wikipedia.org/wiki/Flat-file_database) on a disk. Each entity collection is
stored in a single file (entity records, indexes, schemas, mem-tables) and catalog shared data is stored in an extra
file (shared indexes, catalog schemas, entity collection headers, mem-tables). And finally, there is one special file
called a "header file". A header file contains a pointer to the last mem-table fragment. All files related to a single
catalog are placed in one flat directory.

On top of all these main files there is also a [WAL data file](#write-ahead-log) that is described in a separate section
of this document.

### Binary format

All files except the header file use the same format. The data file is a record collection of variable record sizes
capped to a maximal write buffer size. Simply said - if our write buffer is limited to 2MB, the file will contain
records of different sizes that never exceed 2MB. The records themselves are binary representation of Java objects
serialized using the [Kryo](https://github.com/EsotericSoftware/kryo) library and specialized serializer
implementations. Kryo is the "state of the art" library widely used in the Java ecosystem for very fast object-to-binary
and vice-versa conversion. Storage record can hold any Java object, and it is our universal atom for data persistence.

#### Storage record structure

Each record follows this structure:

* int(4B) length: length of the record
* byte(1B) nodeId: reserved byte for identification of the master node where the record originated from (it may be used
in a multi-master setup if we ever go this way)
* long(8B) transactionId: consistency transaction mark - see consistency verification
* byte(?B) payload: real record payload written by the Kryo serializer
* byte(1B) control: control byte (covered by CRC)
* long(8B) crc:[ CRC-32C](https://www.ietf.org/rfc/rfc3720.txt) checksum of the record from the control (exclusive) to
end of the payload (inclusive) - see consistency verification

The storage record size is limited by the size of the output stream buffer. The reason for this is the fact that the
MemTable is an append-only file, and we only need to set the length of the record prior to flushing the record to the
disk. Information of the record length must be stored as the first information in the record so that we can skip over
records quickly without reading them all and deserializing them with Kryo deserializers. The length of the record serves
as a consistency verification point and can also be used by the vacuuming process to skip over entire records if they're
not found in the actual file index.

The reading process also uses a buffer (different from the write buffer), but this one is not limited by the buffer size
and with a relatively small buffer (let's say 8kB) it can read storage records of any size.

##### Control byte

The control byte bits have the following meaning:

* 1st bit: if set to 1 the record represents the last record of the transaction
* 2nd bit: if set to 1 the record is part of a chain of multiple consequent records ( see chained records)
* 3rd - 8th bit: not used yet

#### Chained records

The output buffer size greatly limits the content possible to be stored in a single record. evitaDB indexes quickly
become larger than a reasonably sized output buffer. There is a built-in mechanism allowing to automatically split
larger payloads into multiple consequently placed storage records and join them automatically during reading. The Kryo
serialization library doesn't help much here. evitaDB hijacks the "require" check (which the Kryo library uses to verify
that there is enough space in the write buffer) to interrupt the serialization process, finish the record and create a
new empty one. Usually there is no limit on the payload size, that can be stored in the storage file - there is only a
single limitation: the serializer must not store more bytes than the buffer size in a single write call (such as
writeBytes(...) or writeInts(...)).

#### Consistency verification

The data file is just a file on the disk and can be corrupted any time for a variety of reasons:

* a process can finish prematurely in the middle of the record writing,
* the disk can be damaged,
* the payload might be corrupted when synchronized in the cluster,
* and other issues

Therefore, we need to detect these situations quickly to avoid corrupting valid files or detect file corruption as early
as possible, which would be either during the evitaDB start-up sequence or immediately after the record is read and
hasn't yet been passed on to the client.

Here is the list of mechanisms checking that the contents of the file are valid:

##### Write interruption resistance

The first level of defense is the Kryo deserialization process. Kryo will fail if it doesn't finish deserializing the
record, and the input prematurely ends.

The second level of the defense is the "transaction" check. Transactions are atomic and need to be performed either
completely or not at all. We need to check that the data file contains all the records the transaction consists of. For
this reason we use a bit in the [control byte](#control-byte).

The transaction ID is the same for all the records in the same transaction and the transaction record list is closed by
a record with the transactional control bit set to one. The transaction ID is monotonically increasing between different
transactions. There is only one exception and that is "warm-up" mode, which is when evitaDB is initialized (filled up
with an initial data set) and the transactional process hasn't yet been enabled. At this time the transactional ID is
zero and has no ending record with the transactional bit set. At the warming up stage (non-transactional), the database
is being created in isolation by a single process and problems can be easily detected by not being able to finish flush
and transition to the go-live state.

When we read the data from the disk, we can be sure the entire transaction is complete at the moment when we read a
record of the last known transaction that has a transactional bit set to one (the ID of the last known transaction is
stored in the header stored in a different file). If not, we know that we encountered a problematic situation that
requires a recovery procedure.

##### Disk / data corruption resistance

At the end of each record, there is a[ CRC-32C checksum](https://www.ietf.org/rfc/rfc3720.txt) of the entire record,
except the leading length and the first control byte information. When the record is being read, this checksum is
computed and verified at the moment the payload has been successfully parsed by Kryo deserializers. We also check, that
the deserializers consumed the entire contents of the payload.

If the checksum doesn't match, an exception is thrown, and we know that we encountered a problematic situation that
requires a recovery procedure.

There is also an easy and very fast verification process that can scan an entire data file and tell whether it's
correct. The process just reads the first 4 bytes of each record which contains the length of the record, reads the
number of bytes corresponding to the length and computes the [CRC-32C checksum](https://www.ietf.org/rfc/rfc3720.txt),
then it reads the next 8 bytes that contains the checksum stored at the time record was written. If there's a
match, it continues with the next record, otherwise it signals a corrupted record in a data file. This process can be
very fast because it doesn't involve the data deserialization - just reading the bytes in an optimal fashion.

#### Fragmentation and vacuuming

The data file is an append only file - it means that the data is only appended to the tail of the file and no data is
ever written in the middle of the file. If we need to remove the record, we have to write a new record at the tail of
the file that will contain the information that the record is considered to be removed from this moment on. Such a
record is often called a tombstone. If we need to update the record, we have to write it again at the tail of the file
combining old and updated contents.

An append-only approach is expected to be faster on HDD file systems and also allows us to avoid file gap management,
which could be tricky. The disadvantage of this solution is that the file is going to get fragmented and grow larger and
larger over time. To solve this problem, we need a vacuuming process that will scan the file and create a new,
non-fragmented one.

When fragmentation of the file reaches a certain point (for example when the dead weight is greater than the weight of
living records), we need to start the vacuuming process, which will create a new, non-fragmented data file. The process
will use the current [mem-table index](#mem-table-index) to read records from file A and write sequentially to file B.
During the rewrite, the process may use statistics to place frequently read records together better utilizing OS file
page cache. When file B is completely written and flushed to the disk, file A can be moved to trash, and file B can be
renamed to file A. Next writes / reads will access the non-fragmented file A.

**Note:**

Similar data stores use [LSM trees](https://medium.com/swlh/log-structured-merge-trees-9c8e2bea89e8), so why doesn't
evitaDB do the same? It builds on the premise that all necessary data fits into the RAM. evitaDB should not manage
historical or analytical data - only the working set that needs to be served on a daily basis to the e-commerce clients
with as low read latency as possible. LSM trees on the other hand are more suitable for write intensive workloads and
reads are penalized. Their management is also much more complex than current evitaDB storage engine implementation. The
storage engine is otherwise key-value based and this decision might be reconsidered in the future.

### Mem-table index

Because the data file is a stream of variable record sizes, there are multiple "versions" of the logically same record,
that are continuously updated, we need to maintain an index that will tell us where the exact record locations are in
the file. We call this index "mem-table".

The mem-table index is a simple map with O(1) access time, that maps living records to their location in the data file
and is kept in memory. It looks like this:

* `RecordKey`:
  * `FileLocation`
  * int 4B `version`: version of the record, that gets incremented when a new storage record sharing the
same `RecordKey` gets written to the file

Where the `RecordKey` consists of:

* long (8B) `primaryKey`: unique identification of the record within its recordType
* byte (1B) `recordType`: number representation of the enum MemTableRecordType that can be translated to a Java
container class with a known serializer and deserializer

The `FileLocation` consists of:

* long (8B) `startPosition`: position of the first byte of the record in the data file
* int (4B) `length`: size of the record

With this information, any variable-length record can be located in the data file. Thanks to the consistency checks, we
make sure, that the block we read represents a valid record. This mem-table index represents the key for reading the data
file contents at a certain moment of the time.

#### Persistence procedure

The mem-table index is stored in the data file along with other types of records. It's the same record as any other. The
mem-table index could get huge and exceed the maximum buffer size easily. Also, due to append-only characteristics of
the data file, we don't want to rewrite the entire mem-table index everytime it changes. That's why we keep the
mem-table index in the data file in the form of an append-only log that can be split into multiple separated records
pointing from the tail to the head.

The file index record consist of:

* long (8B) startPosition: location of the previous file index block
* int (4B) length: length of the previous file index block
* long (8B) lastTransactionId: information about the last transaction id known at the time the block is written
* collection of record pointers (1..N):
  * long (8B) primaryKey
  * byte (1B) recordType (negative when the record represents REMOVAL, i.e. tombstone)
  * long (8B) startPosition
  * int (4B) length

When the entity collection is created, the first mem-table index block is written, and its coordinates are saved. Then
one or more transactions occur and a new mem-table index block is written. This block references the location of the
previous mem-table block and contains only changes in the mem-table index. This includes coordinates of newly added
records, updated records and also removed records (with the negative record type).

We need to always keep the location of the last mem-table index block written to the data file. This information is
crucial for the mem-table index reconstruction and is usually kept in some external file - i.e. the catalog header.

##### Transaction persistence procedure

The details of transaction handling are in [a different chapter](#transactions). But let's describe at least the order
of persistence regarding the data files. A single transaction may contain changes of records from multiple collections
or shared catalog objects altogether. We need to ensure that the transaction is stored either completely or not at all.

We achieve this by the following mechanism when a transaction is committed:

* each modified data file must append a new tail mem-table index block with the information of the last transaction id
in that data file
* this produces the new information about the tail block, which needs to be written to the header
* the entity collections headers are written into the catalog index, whose modification produces its own tail mem-table
block and the information about last transaction id
* the catalog tail mem-table block is finally written into a special "header file", which contains the fixed-size
records containing the location of the tail mem-table block in catalog data file

This way, we can freely write to multiple data files simultaneously and in case things go wrong, we know that the last
transaction is automatically rolled back, because the final operation - the writing location of the tail mem-table block
in the catalog data file didn't occur. When the database is restarted, it reads the last complete location from the
"header file". And that always represents the consistent committed state of all data files.

##### The ultimate header file

The header file is a binary file with fixed record length. It contains 20B records of following structure written by the
Kryo serialization process:

* long (8B) startPosition: the location of the last catalog data file mem-table block
* int (4B) length: the length of the last catalog data file mem-table block
* long (8B) lastTransactionId: information about the last transaction id known at the time the block is written

Each transaction appends a new 20B record to this file. In case the [vacuuming process](#fragmentation-and-vacuuming)
didn't happen, this file can also provide access to any moment in the history of the database (but not easily - it would
require restoring a full database snapshot to RAM, evitaDB is not optimized for accessing historical versions of the
entities).

#### Database reconstruction

When evitaDB is restarted and the mem-table index needs to be reconstructed, we only need the information about the
location of the last mem-table index block to recreate the entire file index. Blocks are read from last to first (tail
to head) and a file index is created during the process. When the RecordKey is read:

* it is looked up in the created mem-table index - if it's already present, it means it is an older version of the
record and is ignored
* it is looked up in the removed records set - if it's there, it was removed
* when it's not present in any above places:
  * if its recordType is negative it means it's record removal, and it's added to the removed records set
  * otherwise, it's added to the created mem-table index

When a block is exhausted and there is a pointer to the previous block, the algorithm proceeds with that block until the
head block is reached. When the head is processed we have completed the actual mem-table index and the contents of the
data file can be safely used.

Due to the nature of the file index record chain we can also recover evitaDB at any point of time, provided that we have
the proper "last" file index record fragment kept for such an occasion. The vacuuming process also limits our ability to
go back in time in evitaDB contents, but when used properly this could be the key for implementing a safe recovery
process.

In case there are a lot of record updates / removals, the mem-table index reconstruction may take a long time, most of
which will be spent by skipping already present or removed records. The closer the block is to the head block, the more
it will happen. This problem could be mitigated by the vacuuming process, which would create a new data file with actual
data only. But if we need to keep historical records in place and still shorten the period needed for mem-table index
reconstruction, we may create a new "snapshot" of the mem-table index in the data file by simply creating a new "head"
block and writing all "actual" record locations after it valid for the time being.

##### Entire catalog reconstruction

The in-memory data structures of the catalog are reconstructed by following procedure:

1. locating the catalog tail in the mem-table block, which is read from [the header file](#the-ultimate-header-file)
2. the catalog data file mem-table index is reconstructed
3. the entity collection headers are read from it - their tail mem-table blocks are located within their headers
4. the entity collection data file mem-table indexes are reconstructed
5. the catalog and entity collection search indexes are fully loaded from the data files, and into the memory

Many of these operations can be performed in parallel, but currently, the entire catalog loading procedure happens
sequentially.

### Backup & restore

Due to the nature of [transaction handling](#transaction-persistence-procedure), we may freely back up the entire catalog
by simply copying the file contents of the files located in the catalog directory, provided that the header file is
copied first.

Also, we need to ensure that during the copying process, [the vacuuming process](#fragmentation-and-vacuuming) doesn't
start, because it may rewrite contents of the data file with newer content than our already copied header file refers
to. Therefore, we need to create a simple lock file before we start copying (backing up the files), and delete it
afterwards. When the lock file is present, the vacuuming process will not start on such a catalog.

Restoring the database requires only a simple copy of the entire catalog directory to the original place and starting
evitaDB from it.

The current state of the [WAL data](#write-ahead-log) file can be part of the backup & restore - or it might not. If it
is, there is a chance, that it would contain only partially written the last transaction. This is an expected situation
and the system just doesn't apply such incomplete transaction when WAL is replayed after restore.

## Data ingestion

The premise of the evitaDB is that it represents a secondary data store - a fast search index for e-commerce catalogs.
The primary data is stored somewhere else - probably in the form of
a [RDBMS](https://en.wikipedia.org/wiki/Relational_database) that is optimized for easier writes and strong consistency.
The main goal of evitaDB is providing fast access to the same data that is optimized for reading, aggregation and
filtering. We also want to be able to drop our index at any moment and recreate it from the primary data store quickly.

In that sense, we want to provide facilities for two scenarios:

1. quick initial filling of the database
2. incremental updates of existing dataset

We could speed up the filling process if we can relax on a few constraints. We can do that because we know that the
initial filling will be executed by a single process and that no other process will access the data at that time. When
anything goes wrong we can afford dropping our previous work and start ingesting from the start again. This stage is
called [warm-up indexing](#warm-up-indexing).

When the database is filled up from the primary data source, we need to switch it to
the [transactional state](#transactions) and start playing by the [ACID](https://en.wikipedia.org/wiki/ACID) rules. We
expect that the primary data store will have some sort of journaling ([Debezium](https://debezium.io/) is one of the
rising stars in that space), which allows monitoring for changes that occur in the primary data. If no journaling is
available, the primary data store should be able to provide at least last change timestamps that may allow change
propagation to secondary indexes.

In the second - transactional phase - we expect that evitaDB is under heavy reading load and that there are multiple
simultaneous write requests as well. There may be one or more processes that propagate changes from the primary data
store, and many other processes that update for example the quantity of goods in stock when orders are being placed.

The secondary transactional phase needs to ensure that all transactions are properly ordered and committed and that
after each transaction the system is left in a consistent state, and that all user-defined constraints remain intact (
such as that the quantity of goods in stock remains greater than or equal to zero).

### Warm-up indexing

During warm-up ingestion, the entity bodies are immediately written into the data files and the mem-table index is
created as a simple hash map in the memory. When the schema contains attributes marked as unique, filterable, sortable
or indexed references, the appropriate [index structures](#indexes) are created on the fly in the memory using simple
data structures that don't support concurrent access, but are fast and transparent.

When the warm-up ingestion is done, and the evitaDB session is closed, all index structures are persisted into data
files and finally, the mem-table indexes. Since evitaDB needs to keep all working and mem-table indexes in the RAM, this
initial phase serves us also for checking that the system has enough memory to host all the necessary data.

The downside is, that in case the system is already operating one evitaDB database, and we want to replace it with a
freshly created one, that we built in the background process, the system needs to have enough memory to accommodate two
evitaDB catalogs at the same time. We can work around this situation by using a different machine for creating the
replacement database and switching the machines once the index is created using load-balancer. Or moving the index files
to the production machine, closing the original catalog there and loading the new one which would involve a few seconds
long downtime.

The warm-up phase is finished by the so-called "go-live" command. After this command is issued, the catalog starts
processing all mutations in a [transactional mode](#transactions) and there is no way of returning back to the warm-up
state.

### Transactions

The key concept of the transaction is that the data written by the transaction is visible only within that transaction,
and it is not visible to the other simultaneous readers. Once the transaction is committed, the changes become visible
to all readers that open a new transaction after the commit. In the multi version concurrency control terminology this
is called the snapshot isolation - each client behaves as if it has a full copy of the database at the time of
transaction start. If the client doesn't open the transaction explicitly, the transaction is opened implicitly for each
query or execute statement and closed automatically when the statement is finished.

There are two places which needs to be taken care of in relation to transactions:

1. record payload data
2. indexes referring to that data

The first part is trivial - we can store records to our data files without the fear of breaking ACID properties. The
records on the disk are not visible until indexes (more so the memory-table index) are aware of them. During
transaction, the records are appended directly to the end of the data files and indexes are held and updated in volatile
memory.

**Note:**

We made our life easier by enforcing a single writer for all data files together. In other words, this means we can
process transactions sequentially. If we knew that the transactions were written into non-overlapping data files we
could write the data in parallel. But since this is not expected to be a common use-case, we decided not to implement
it.

The second part - indexes are the place where all the complexity lies. The frequently used approach in databases is
either a table / row locking mechanism, or the transaction id number stored along with the record pointer in some kind
of the B-tree that is consulted when the record is about to be read / updated in a particular transaction (
see [Goetz Graefe Modern B-Tree Techniques](https://w6113.github.io/files/papers/btreesurvey-graefe.pdf), or
the [Concurrency Control for B-Trees with Differential Indices](https://citeseerx.ist.psu.edu/viewdoc/download?doi=10.1.1.85.602&rep=rep1&type=pdf)
by the Helsinki University of Technology team). The indexes consist of multiple,
so-called, [transactional data structures](#transactional-data-structures) described in more detail in following
sections.

#### Software transactional memory

Our approach is totally different. We build on the premise that all indexes are held in memory, and we have a strong ally
on our side - the Java garbage collector. We isolate the concurrent updates of the indexes made by different threads
into separate memory blocks in the form of read-write diff overlay enveloping the original immutable data structure.

When the thread reads the data in the transaction, it accesses the data through an overlay which applies the diff on the
fly, so that the transaction dynamically sees its own changes. If there are no updates in the transaction, there are
also no diff layers and the transaction reads directly from the underlying immutable data structure. Since the diff
overlays are held in `ThreadLocal`, which is bound to a thread processing a specific transaction, the transactions
cannot see each other's changes. This approach is often labeled
as [software transactional memory](https://en.wikipedia.org/wiki/Software_transactional_memory), in particular we use a
technique called [fat-node](https://en.wikipedia.org/wiki/Persistent_data_structure#Fat_node).

**Note:**

Our implementation is somewhat naive and certainly suboptimal. But since we concentrate on read performance in the
first place, the suboptimal write / update techniques are something that could be addressed later. Some great
inspiration could be data structures and [algorithms used by Clojure](https://www.youtube.com/watch?v=7BFF50BHPPo),
which are engineered with much more
thoughtfulness. [Michael J. Steindorfer Efficient Immutable Collections](https://michael.steindorfer.name/publications/phd-thesis-efficient-immutable-collections.pdf)
might also bring a lot of inspiration.

Each transactional data structure (see listing of [transactional data structures](#transactional-data-structures) in
following chapters) in case of write request asks for its own diff layer, wherever it records all write changes. The
diff layer is created on the first write request to the data structure, so the data structures that haven't been
addressed by the write request don't issue a new diff layer.

##### Atomic transactions

The only way to make transactional changes atomic is to gather all changes in a volatile diff layer that is only used
for the particular transaction. When a transaction is committed, a new instance of the entire catalog data structure (
i.e. also new instances of updated entity collections, their indexes etc.) must be built aside and finally replace the
currently working catalog instance by single `AtomicReference#compareAndExchange` call.

**Note:**

Although we state, that the entire catalog data structure is to be re-instantiated, it's not entirely true. If it were
to be like that, the transactions would be too expensive for large datasets, for which the mechanism wouldn't be
feasible. The reality is that we create new instances only for the modified parts of the catalog data structures and the
catalog itself. If you imagine the catalog data structure as a tree with the catalog instance at the top and all the
inner data as a directed acyclic graph, you'll realize that the new instances are required only for the changed data
structure itself plus all its "parents" towards the catalog instance at the top of the graph.

In case the transaction is rolled back we just throw out the entire diff layer memory block from the `ThreadLocal`
variable and let the Java garbage collector do its work.

**Note:**

The transactional memory resides on the Java heap along with all the other key evitaDB data structures. This means that
if the transaction is very large, it could consume all available memory and cause an OutOfMemoryException, affecting the
rest of the system - even the read-only sessions. To avoid such a situation we would want to limit the scope of the
transaction. Retrieving information about data structure size
is [not an easy task on the Java platform](https://dzone.com/articles/java-object-size-estimation-measuring-verifying),
and we can retrieve only a rough estimate of it. The system calculates an estimate of the transaction size and limits
the total size of the transaction as well as all transactions processed in parallel to avoid exhausting all free
memory.

##### Preventing update loss

The key problem with the described approach is that the updates can be easily lost if the diff layer is omitted to be
applied on the original data structure and included in the new catalog instantiation process.

To avoid this issue, we track each diff layer ever created in the particular transaction and mark them in case they were
consumed by the instantiating process, when the transaction commit is being executed. Finally, we check that all diff
layers were marked as processed at the end of the transaction and if not, an exception is thrown and the transaction is
rolled back. This resembles [double entry accounting](https://www.investopedia.com/terms/d/double-entry.asp), where each
positive operation must be accompanied by a negative one.

During development, such issues occur, and therefore there must be a way to unfold and solve these problems. This is
actually very tricky since there may be thousands of diff layers, and assigning a specific layer to its creator/owner is
not easy. Therefore, we assign a unique transactional object version ID at the time the diff layer is created, and
include it in the exception thrown for non-collected diff layers. When we replicate the problematic transaction in a
test, the diff layer will obtain the same version ID repeatedly, and we can track the origin (at the exact moment and
place) of the layer creation by placing a conditional breakpoint at the version ID generator class.

##### Testing

The integrity of the data structures is vital for the database. On top of base unit tests, there is always one
"generational" test, that applies [a property based testing approach](https://en.wikipedia.org/wiki/Property_testing).
The tests always use two data structures - our tested STM implementation and
a [test double](https://en.wikipedia.org/wiki/Test_double) that is represented by an external well-proven
implementation. For example - in the generational test of the [TransactionalMap](#transactional-map) data structure, we
use a JDK HashMap implementation as a test double.

The testing sequence is always similar:

1. at the start of the test, both tested and test-double instances are created
2. both are filled with the same initial randomized data
3. in an iteration with randomized count of repetitions
	1. a random operation is selected and is executed with a randomized value both on the tested and test double
	instances (an example of such operation might be "insert value X" or "remove value Y")
    2. a test checks, that the changes are immediately visible on the tested instance
    3. the transaction is committed
4. after the commit occurred, contents of both data structures are compared and must be equal one to another
5. new instances of the data structures are created with initial data taken from the product of the commit
6. steps 3. - 5. are repeated infinitely

When this generational test runs for a few minutes without a problem we can be confident, that the STM data structure is
correctly implemented. There is always a small chance, that the test itself is incorrect -
but [quis custodiet ipsos custodes?](https://en.wikipedia.org/wiki/Quis_custodiet_ipsos_custodes%3F)

##### Transactional data structures

###### Transactional array (ordered)

The transactional array mimics plain array behavior, and there are multiple implementations if it:

* TransactionalIntArray: for storing primitive `int` numbers
* TransactionalObjectArray: for storing plain Objects of any type
* TransactionalComplexObjectArray: for storing nested Objects structures that allow merging and automatic removal of
"empty" containers

All arrays are implicitly naturally ordered. In the case of object implementations, the Object is required to be
Comparable. The array implementation doesn't allow duplicates in values. So in case of any insertions / removals, the
array knows which indexes will be affected internally. There is no possibility to set value on an index passed from
outside logic.

All these implementations share the same idea; in transactional mode, all updates go to the transactional overlay that
traps:

* inserts on certain indexes in an internal array of inserted values
* deletions on certain indexes in an internal array of removed indexes

From this information, the STM array is able to build up a new array that combines the original values with all the
changes. To avoid creating a new array (and memory allocations) for all operations there are optimized and frequently
used methods that operate on the diff directly:

* `indexOf`
* `contains`
* `length`

The `TransactionalComplexObjectArray` is much more complex - it accepts these functions that operates on these nested
structures:

* `BiConsumer&lt;T, T> producer` - this function takes two containers and combines them together into one output
container that contains an aggregate of their nested data
* `BiConsumer&lt;T, T> reducer` - this function takes two containers and removes / subtracts nested data of the second
container from the nested data of the first container
* `Predicate&lt;T> obsoleteChecker` - this function that tests whether the container contains any nested data - if not,
the container might be considered as removed; the predicate is consulted after the reduce operation

This implementation provides the ability to partially update the objects held in it. For example let's have a record
with the following structure:

* `String: label`
* `int[]: recordIds`

Then, if we insert two such records into `TransactionalComplexObjectArray` with the following data:

* label = `"a": recordIds = [1,2]`
* label = `"a": recordIds = [3,4]`

The array will produce the result with one record: `"a": [1,2,3,4]`

If we apply the removal of the record: `"a": [2, 4]` the array will produce the result: `"a": [1,3]`

if we apply the removal of the record: `"a": [1, 3]` on it again, the array would produce an empty result.

Unfortunately in this implementation, we cannot provide optimized methods, such as:

* `indexOf`
* `length`

And we have to compute the entire merged array first in order to access these properties. This data structure might be
subject to big optimizations, but is also quite hard to implement correctly due to the nature of nested structures.

###### Transactional unordered array

This version of the transactional array differs from the [previous one](#transactional-array-ordered) in the sense that
it allows duplicate values. It also is not ordered and allows the client side to control the indexes where new values
are inserted or where existing ones are removed.

The database requires only a single implementation of this type of structure - `TransactionalUnorderedIntArray`.

The diff layer implementation for the unordered array is principally the same as for
the [ordered one](#transactional-array-ordered) with one exception. The inserted values keep information about the
relative index in the segment inserted at a certain position of the original array.

This array has a special and fast implementation working on the diff layer for methods:

* `indexOf`
* `contains`
* `length`

###### Transactional list

The `TransactionalList` mimics the behavior of the `java.util.List` interface, allowing it to contain any Object. The
list can contain duplicates and is unordered. The implementation is currently suboptimal, and it could be implemented
the way [the unordered array](#transactional-unordered-array) is implemented.

The dif layer contains a sorted set of indexes that were removed from the original list and a map of new values along
with indexes, which they were inserted at. When a new item is inserted or removed from the diff layer, all the indexes
after this value need to be incremented or decremented. So, the operation "add/remove first" always has O(N) complexity.
On the contrary, the unordered array splits inserts into multiple segments and the complexity is usually lower - the O(
N) is only the worst case complexity for an unordered array.

###### Transactional map

The `TransactionalMap` mimics the behavior of the `java.util.Map` interface, allowing it to contain any key / value
pairs. The implementation is straightforward in this case - the diff layer contains a set of all keys removed from the
original map and a map of all key/value pairs updated or inserted to the map.

When the logic tries to retrieve a value from the map, the diff layer is first consulted to resolve whether the key
exists in the original map and has no removal order in the layer, or whether it was added to the layer. The iterator of
entries / keys / values first iterates over all existing non-removed entries and then iterates through entries added to
the diff layer.

###### Transactional set

The `TransactionalSet` mimics the behavior of the `java.util.Set` interface. The implementation is straightforward in
this case - the diff layer contains a set of all keys removed from the original map and a set of added keys.

###### Transactional bitmap

A bitmap is a set of unique integers in ascending order. The data structure is similar to
a [transactional array](#transactional-array-ordered), but is limited to integers only, and allows much faster
operations upon the number set. This implementation wraps an internally held instance of `RoaringBitmap`. The reasons
for using this data structure and more detailed information
about [RoaringBitmaps](https://github.com/RoaringBitmap/RoaringBitmap) are stated
in [the query evaluation chapter](#boolean-algebra). The RoaringBitmap is internally a mutable data structure, and also
a 3rd party library. We have no control over it and wanted to hide it with our interface. That is why the `Bitmap`
interface was created to ensure the entire codebase works with it instead of RoaringBitmap directly.

The `TransactionalBitmap` allows to trap insertions and deletions from the original bitmap in the diff layer, and when
the bitmap needs to be used for reading, it computes new RoaringBitmap by applying `insertions` by boolean AND
on `original bitmap`, and applying `removals` by boolean AND NOT on the fly. To avoid this costly operation, the result
is memoized for additional read calls, but must be cleared with the first next write.

The computational method clones the entire original RoaringBitmap two times and thus is more than suboptimal.
Unfortunately, the RoaringBitmap doesn't provide us with better options. The ideal implementation would require the
RoaringBitmap to be internally immutable, producing a new instance every time a write operation occurs. Because
RoaringBitmaps internally work with separate blocks of data, the new immutable version could reuse all former blocks
that were not affected by the write action and clone / alter only a few blocks where the changes really occurred.
However, this would require substantial changes to the internal implementation and would be probably dismissed by
the authoring team.

#### Sequences

evitaDB uses several sequences to assign unique monotonic identificators to various objects. The sequences are not part
of a transaction process, and their progress is not rolled back. All sequences are internally implemented
as `AtomicLong` or `AtomicInteger` that allow retrieval of incremented values in a thread-safe manner.

Currently, we don't plan to support multiple writer mode in [distributed setup](#distributed-model), and that means we
can afford to safely use the Java internal atomic types for managing sequences, because the new numbers will always be
issued only by a single master node (JVM).

The sequences managed by the evitaDB are:

* entity type sequence: assigns a new id to each created entity collection allowing to address the collection by a small
number instead of a duplicating and larger String value
* entity primary key sequence: assigns a new id to each created entity - just in case the entity schema requires
automatic primary key assignment
* index primary key sequence: assigns a new id to each created internal index
* transaction id sequence: assigns a new id to each opened transaction

#### Write ahead log

All mutations in evitaDB database are written first to [the write-ahead log](https://en.wikipedia.org/wiki/Write-ahead_logging).
This procedure wouldn't be necessary (because the storage layer is designed as
[write interruption resistant](#write-interruption-resistance)) if we didn't want to deal running the database in
a cluster environment.

Write ahead log is just simple [storage file](#storage-format) that keeps bodies of mutation data transfer records.
The storage file changes are flushed frequently and no transaction can be marked successfully committed unless it
obtains signal, that the WAL has been successfully synced on disk.

All parallel transactions use single writer thread and single output WAL file, therefore the transaction mutations are
first collected in memory of the [transaction processor](#transaction-processor), and transaction mutations are
forwarded to the writer thread only after the transaction is marked for commit. This means that all mutations for single
transaction are continuously stored in the WAL.

WAL doesn't participate in standard [vacuuming process](#fragmentation-and-vacuuming), and therefore it would grow
indefinitely. That's why there is a configured threshold limiting the maximum size of the WAL file. If the threshold
is reached, evitaDB starts writing to a separate file (segment) but leave the original file exist. Single transaction
must be always fully written into the same WAL segment, so if the huge transactions might cause WAL file sizes to
overflow their configured limits.

Vacuuming process removes all WAL files in case they have no future use. WAL files are used for:

* applying missed committed mutations on master node indexes (recovery after crash)
* change replication across multiple read nodes

When we know that all mutations are safely in the master indexes and readers nodes applied all changes to their indexes,
we might safely remove entire WAL segment.

#### Transaction processor

The purpose of a transaction processor is to concurrently handle all parallel transactions and verify that their
mutations are not mutually conflicting. It also serves as a buffer for all mutations of the opened and not yet committed
transactions. Each mutation provide a key, that is compared to keys of all other mutations in the transaction processor.
If there is a mutation with the same key, the mutation is asked to handle conflict with previously recorded mutation.
Some mutation can safely write to the same attribute (those that apply some form of delta information to it), while
other can not and will signal an exception which causes the transaction to be rolled back.

Conflict handling is a work in progress, but initially we want to guarantee [snapshot isolation level](https://en.wikipedia.org/wiki/Snapshot_isolation)
and resolve conflicts on level of:

* single entity attribute
* single associated data
* single price
* single reference
* single entity (for direct entity properties, such as hierarchy placement, price inner record handling strategy and so on)

We believe that this approach with combination with "delta" mutations might minimize the number of conflicts and
rolled back transactions. No writes will also ever cause problems for the readers, who always read consistent version
of the database contents.

#### Distributed model

We plan to implement replication model similar to [streaming replication in PostgreSQL](https://scalegrid.io/blog/comparing-logical-streaming-replication-postgresql/)
or [statement-based replication in MySQL](https://dev.mysql.com/doc/refman/8.0/en/replication-sbr-rbr.html). evitaDB
is designed for read heavy environments, so we plan to stick to single master, multiple reader nodes model with
dynamic master (leader) election. All writes will target the master node, that will maintain the primary [WAL](#write-ahead-log).

All the read nodes will maintain an open connection to the master node and will stream the changes in WAL and replay
them locally. The connection is always open and streams immediately all changes present in the WAL file to each
replica node. When the WAL reaches mutation that finalizes the transaction, the mutations are passed to the local
[transaction processor](#transaction-processor) to process them. Because all conflicts are resolved on the master node
before the transaction is allowed to be committed and written in WAL, all the mutations are expected to be successfully
processed by the replica.

When new replica node is added to the cluster it selects any other replica or master node and fetches binary version
of their data storage files cleaned from the obsolete records. Obsolete records are discarded on the fly by
the streaming producer of the storage file maintainer (selected replica or master node). All files will be consistent
and will refer to the last committed transaction id known to the data storage file maintainer node at the moment of
replica sync start. When there are additional changes recorder meanwhile the replica is syncing the database, they're
written to the data storage file maintainer WAL and when all the files are correctly synced, the new replica will fetch
also contents of the WALL file from the point of the last known transaction id it knows about. Because all those
operations work on "binary level", spinning a new replica might be reasonably fast process.

### Indexes

Indexes are key data structures for fast query processing. One of the key selling points of evitaDB is exceptional read
throughput. Therefore, the database can only process queries that hit already prepared indexes. On the contrary,
relational databases allow processing queries targeting non-indexed columns, which results in a full table scan that is
unbearably slow on large tables.

#### Range index

The range index allows us to resolve the queries for range types. A range type is a composite type that defines the
start and the end in the form of a long type (64-bit numeric type). The evitaDB recognizes multiple numeric based ranges
as well as the date time range - all of which are convertible to the long type.

The range index stores the range information in the form of the long threshold and two arrays that contain record
primary keys that start and that end at the threshold. Considering following data ranges:

<Table>
	<Thead>
		<Tr>
			<Th>Range</Th>
			<Th>Primary keys</Th>
		</Tr>
	</Thead>
	<Tbody>
		<Tr>
			<Td>1 - 10</Td>
			<Td>1, 2</Td>
		</Tr>
		<Tr>
			<Td>4 - 5</Td>
			<Td>3</Td>
		</Tr>
		<Tr>
			<Td>8 - 9</Td>
			<Td>3, 4</Td>
		</Tr>
		<Tr>
			<Td>10 - 12</Td>
			<Td>5, 6</Td>
		</Tr>
	</Tbody>
</Table>


we would end up with the following range index:


<Table>
	<Thead>
		<Tr>
			<Th>Threshold</Th>
			<Th>Starts</Th>
			<Th>Ends</Th>
		</Tr>
	</Thead>
	<Tbody>
		<Tr>
			<Td>1</Td>
			<Td>1, 2</Td>
			<Td>&nbsp;</Td>
		</Tr>
		<Tr>
			<Td>4</Td>
			<Td>3</Td>
			<Td>&nbsp;</Td>
		</Tr>
		<Tr>
			<Td>5</Td>
			<Td>&nbsp;</Td>
			<Td>3</Td>
		</Tr>
		<Tr>
			<Td>8</Td>
			<Td>3, 4</Td>
			<Td>&nbsp;</Td>
		</Tr>
		<Tr>
			<Td>9</Td>
			<Td>&nbsp;</Td>
			<Td>3, 4</Td>
		</Tr>
		<Tr>
			<Td>10</Td>
			<Td>5, 6</Td>
			<Td>1, 2</Td>
		</Tr>
		<Tr>
			<Td>12</Td>
			<Td>&nbsp;</Td>
			<Td>5, 6</Td>
		</Tr>
	</Tbody>
</Table>

Starts and ends are maintained as a [bitmap](#transactional-bitmap). This implies that the single record primary key
cannot have two ranges that share the same start / end range threshold. Such a situation would require having the same
primary key in a bitmap stated twice, which is not possible. Consider following example:

<Table>
	<Thead>
		<Tr>
			<Th>Range</Th>
			<Th>Primary keys</Th>
		</Tr>
	</Thead>	
	<Tbody>
		<Tr>
			<Td>1 - 2</Td>
			<Td>1</Td>
		</Tr>
		<Tr>
			<Td>1 - 4</Td>
			<Td>1</Td>
		</Tr>
	</Tbody>
</Table>

Would result in range index of:

<Table>
	<Thead>
		<Tr>
			<Th>Threshold</Th>
			<Th>Starts</Th>
			<Th>Ends</Th>
		</Tr>
	</Thead>
	<Tbody>
		<Tr>
			<Td>1</Td>
			<Td>1</Td>
			<Td>&nbsp;</Td>
		</Tr>
		<Tr>
			<Td>2</Td>
			<Td>&nbsp;</Td>
			<Td>1</Td>
		</Tr>
		<Tr>
			<Td>4</Td>
			<Td>&nbsp;</Td>
			<Td>1</Td>
		</Tr>
	</Tbody>
</Table>


Then, if the range [1-4] is removed from the record, we end up with invalid range index:


<Table>
	<Thead>
		<Tr>
			<Th>Threshold</Th>
			<Th>Starts</Th>
			<Th>Ends</Th>
		</Tr>
	</Thead>
	<Tbody>
		<Tr>
			<Td>2</Td>
			<Td>&nbsp;</Td>
			<Td>1</Td>
		</Tr>
	</Tbody>
</Table>

As such, the information of the record with the primary key 1 would be completely lost. Therefore, the non-overlapping
rule was introduced. Primary record MUST NOT be part of two ranges that overlap one another. Such a situation doesn't
make sense, because having ranges A-B & C-D that overlap, could be easily transformed to an A-D range. This requirement
is also mandatory, so that the index works correctly. The rule is only an internal one - the record may have the ranges
that violate this rule - the conversion onto the non-overlapping ranges is handled internally by the engine.

The range index is used to handle two types of queries (for Range data type) described in more detail later:

* [within query](#within-query)
* [overlap query](#overlap-query)

#### Inverted index

The inverted index is based on the [Inverted index](https://en.wikipedia.org/wiki/Inverted_index) data structure. It is
organized as a set of values referencing a bitmap of record ids using that value, ordered from minimal to maximal value

A search in an inverted index is possible via a binary search with **O(log(n))** complexity, due its sorted nature. A
set of records with a particular value is easily available, as the bitmap is assigned to that value. An auxiliary help
index is created in an enveloping FilterIndex, where the key is the attribute value and the value is the index of the
record in the inverted index. By using this auxiliary map, we achieve a **O(1)** access time for records with a
particular value.

The range search is also available as a disjunction operation (boolean OR) of all bitmaps collected from/to the index of
the found threshold.

The inverted index cannot contain the same record ID in multiple bitmaps. This prerequisite is not checked internally by
this data structure, and we check, that on the entity contract level. If this prerequisite is not met, the inverted may
return confusing results.

The inverted index is used for resolving constraints, such as:

* equal to
* greater than
* greater than or equal to
* lesser than
* lesser than or equal to
* between
* in range
* in set

#### Facet index

A facet index provides fast **O(1)** access to the bitmaps of the entity primary keys that refer to the faceted entity.
The facet index holds in cascade the following nested maps:

* entity reference name
  * non grouped facets
  * facet id -> record IDs
  * group ID
  * facet id -> record IDs

This index allows for the processing of query constraints, such as:

* facet in set
* compute require constraint facet summary

#### Hierarchy index

The hierarchy index collocates information about the hierarchical tree structure of the entities. The index itself
doesn't keep the information in the form of a tree, because we don't have a tree implementation that is transactional
memory compliant.

The index allows out-of-order hierarchy tree creation, where children can be indexed before their parents. Such entities
are collected in an `orphans` array, until their parent dependency is fulfilled. When that time comes, they are moved
from `orphans` to `levelIndex`.

The `levelIndex` is a simple map, where a key is a parent record ID and a value array of its children sorted by
the `orderAmongSiblings` value. It contains information about children of a shared parent. There is also a `roots`
collection that contains a list of root nodes ordered by the `orderAmongSiblings` value. If the record is not reachable
from any root node, it is placed into an `orphans` set and is not present in this index.

The full hierarchical tree can be reconstructed by traversing the `roots` collection, acquiring their children
from `levelIndex` and scanning deep - level by level using information from the  `levelIndex`. Nodes in `roots` and
the `levelIndex` values are sorted by `orderAmongSiblings`, so that the entire hierarchy tree is available immediately
after the scan.

This index allows processing of query constraints, such as:

* within hierarchy
* within root hierarchy
* compute require constraint parents
* compute require constraint hierarchy statistics

#### Price index

The price index contains data structures that allow for the processing of price related filtering and sorting
constraints, such as:

* price in currency
* price in price list
* price between
* price valid in
* sorting by price

For each combination of the `priceList`, `innerRecordHandling` and `currency`, it maintains a
separate [filtering index](#price-list-and-currency-index). This information is stored in a map with **O(1)** access
complexity. Pre-sorted indexes are maintained for all prices, regardless of their price list relation, because there is
no guarantee that a currency or price list will be a part of the query.

In order to decrease memory consumption in case high price cardinality, there are two types of price indexes:

1. super index - this index (or its [inner indexes](#price-list-and-currency-index)) contains the full price dataset (
price bodies) and is self-sufficient. The super index relates to a global entity index of which there is only one per
entity collection
2. ref index - this index (or its [inner indexes](#price-list-and-currency-index)) contains the subset of price pointers
to the price bodies in the super index. Ref indexes are used in auxiliary entity indexes maintained for filtering
with hierarchy or reference constraints (see entity [index organization](#price-record))

##### Price list and currency index

The index contains information used for filtering by price that is related to a specific price list and currency
combination. Real world use-cases usually filter entities by price that is part of a specific set of price lists and
currency. As such, we can greatly minimize the working set by separating the price indexes by this combination.

This index maintains multiple data structures, which allows for fast filtering, while also minimizing memory
requirements.

* `priceRecords` is an array of all simplified [price records](#price-record)
* `indexedPriceEntityIds` contains a bitmap of all record IDs with particular priceList-innerRecordHandling-currency
combinations. This data structure can be used for queries, which lack `price valid in` constraint or indexes that
maintain prices for innerRecordHandling equals to NONE
* `indexedPriceIds` contains a bitmap of all price IDs with particular priceList-innerRecordHandling-currency
combinations. This data structure must be used for complex queries (except the ones handled by a previous paragraph)
* `entityPrices` contains a map where a key's price id and value is a [compound structure](#price-record) `entityPrices`
* `validityIndex` is a [range index](#range-index), that maintains validity of information about particular price IDs

##### Price record

The price record maintains only basic information required for filtering:

* `internalPriceId` - assigned internal price ID (see [internal price identification](#internal-price-identification))
* `priceId` - external price ID
* `entityPrimaryKey` - record ID of the entity the price belongs to
* `priceWithTax` - price with tax in a primitive integer form
* `priceWithoutTax` - price without tax in a primitive integer form
* `innerRecordId` - inner record ID grouping entity prices in subgroups

##### Entity prices

This data structure keeps information of all prices assigned to the same entity. It allows for the answering of the
following questions:

* return all lowest price records in entity for the distinct inner IDs in it
* identify, whether a particular price ID is a part of the entity prices
* identify, whether a particular inner record ID is used by any of the entity prices

In order to decrease memory consumption, there are multiple variants of this structure, depending on entities having
only a single price, or multiple prices without an inner record specification or multiple prices with it.

##### Internal price identification

Each price has an external price ID identification. Unfortunately, we cannot count on price ID uniqueness among multiple
entities of the same type. And thus, we require price IDs to be unique only within the same entity, due to business
reasons. In real situations, a single price can be part of multiple indexed entities - there are often "virtual
products" that aggregate multiple real products, and the client wants to index them both and filter different products
in different situations.

**Example:**

If an e-shop sells T-shirts, there is usually a shirt with the same motive in multiple sizes and colors. To avoid
combination explosions in product listings, e-shops usually maintain a virtual product that aggregates all combinations
of those products, including all their (possibly different) prices. These prices are distinguished by a `innerRecordId`
that is mapped to the real record id of the product that can be really bought. On the other hand, there is also a need
for indexing the real products themselves, only with prices that belong solely to them (for example for external search
engines feed generation). That's one of the situations when the same price with the same id is a part of two different
products - virtual and real.

In order to unequivocally identify the price, we would need a combination of the entity ID and the price ID - each of
integer type (4B). Together they'd form a long type (8B). The original implementation worked with this interpretation,
but was discarded, because:

1. it led to significantly larger memory consumption
2. the RoaringBitmaps working with long types are considerably slower that of their integer counterparts
3. it required multiple [formula](#query-evaluation-in-detail) implementations (which required much more source code and
tests) and complicated the code itself

Therefore, we generate our own internal primitive integer price IDs, which are used for computational and indexing
purposes, and can be anytime translated back to a full entity/price combination.

#### Unique index

The unique index serves as a data structure for unique attribute values. It guarantees the uniqueness of each value /
record ID combination. It maintains two internal data structures: a map of `uniqueValueToRecordId` that allows for each
value to return a record with an ID with **O(1)** complexity, and a `recordIds` bitmap, that keeps all record IDs from
having any value of this attribute.

The unique index is used for operations such as:

* equal to
* in set
* is (null / not null)

#### Filter index

The filter index allows to filter all filterable, but non-unique attributes (there is one filter index per one attribute
schema). Internally it holds:

* `invertedIndex` - [inverted index](#inverted-index) of values (used when value is non Range type)
* `rangeIndex` - [range index](#range-index) of validity values (used when value is Range type)
* `valueIndex` - auxiliary data structure map allowing to translate value to record to index in `histogram` in **O(1)**
complexity

The index delegates the key functionality either to a histogram or a range index, depending on the accepted task and
attribute type it manages.

#### Sort index

The sort index is used by our [presorted sorter implementation](#presorted-record-sorter). It contains a record ID
bitmap, named: `sortedRecords`, where IDs are sorted in ascending order by a certain attribute value. The cost for an
insertion sort is paid during data indexing, and not querying (reading). For using this kind of index, see
the [presorter implementation chapter](#presorted-record-sorter).

In order to minimize the memory footprint, we maintain the distinct attribute values in sorted array
named `sortedRecordsValues` and their cardinality in `valueCardinalities` (we maintain only the cardinalities > 1).
These data structures are used when a new record id / value is inserted or when an existing record is removed.

**Note:**

This is not a final version of the index implementation - we expect that maintaining the values in
the [trie data structure](https://en.wikipedia.org/wiki/Trie#Compressed_tries) would bring significant memory savings.
Also, the values in the form of trie could be shared among the [filter](#filter-index) and [unique](#unique-index)
indexes, which would lead to additional savings.

When a new value / record is about to be indexed, we find the proper insertion place by binary search
on `sortedRecordsValues` and adding all cardinalities of values before it. Using this simple algorithm we now know the
exact place where the new record should be inserted. A similar process happens when values are removed.

#### Entity index

Each entity collection maintains a set of entity indexes that collect all information required to filter and sort the
entities, as well as computing extra results on top of them. All previously documented indexes are part of one of the
entity indexes in various forms.

##### Global entity index

Each entity collection has a single "global" entity index that maintains all the information about all the
entities in the collection. Because the real life queries frequently use filters by hierarchical placement of reference
to another entity (join), we maintain special "ref" indexes in each entity collection, which maintain information only
about a subset of entities that possess such a relation or hierarchy placement. This fact allows us to discard large
quantities of data on the top level.

Using the global index for filtering/sorting is a "worst case" scenario - still much better than a full-scan, but it
still needs to cope with all the data in the collection in some form or another. There are these flavors of our "ref"
entity indexes:

##### Hierarchy node entity index

It only contains data about entities, which are directly related to the same referenced parent entity identified by the
key, which consists of:

* `relationName` - name of the relation from the entity schema
* `referencedPrimaryKey` - primary key of the hierarchical entity (parent) of which the index relates to

When a query contains a hierarchy related constraint, the [index selector](#phase-selecting-indexes) retrieves multiple
hierarchy node entity indexes, which form a targeted hierarchy tree and applies the filtering logic on each of them, and
combines the result by a disjunction (boolean OR) operation.

##### Referenced entity index

It only contains data about entities that are directly related to the same referenced entity identified by the key,
which consists of:

* `relationName` - name of the relation from the entity schema
* `referencedPrimaryKey` - primary key of the hierarchical entity (parent) of which the index relates to

This index is almost identical to the [hierarchical entity index](#hierarchy-node-entity-index), except it targets
non-hierarchical entity types (collections), and also requires an additional "reference type" index, which uses
following information as its discriminator:

* `relationName` - name of the relation from the entity schema

Reference type index contains the same data as referenced entity index, but instead of using primary keys of indexed
entities it uses (indexes) primary keys of referenced entities of indexed entities.

The reference type index allows us to answer this question: given the input query, what reference indexes need to be
selected to fully cover the working data set? The reference type index returns the `referencedPrimaryKey` set that,
combined with `relationName`, leads to a set of referenced entity indexes, on which the filtering logic needs to be
applied. Their result is combined by a disjunction (boolean OR) operation.

## Implementation of query algorithms

The query processing has three stages and every stage contains multiple phases. The processing might end prematurely in
the first stage if there is an error in the query, or it can also end in the second stage, but only if the system
identifies that there will be no results. The query planning stage is performed in `QueryPlanner` that
creates `QueryPlan` which is invoked by the `execute` method afterwards.

### Stage: query parsing

#### Phase: query deserialization

The database offers multiple ways (APIs) to pass the input query:

* Java API - used when evitaDB is running as an embedded database
* gRPC API - fast, but still a system API that is used in the Java driver when evitaDB runs in client / server mode.
This API can also be used for microservice integration or fast inter-platform communication (for example C#
application communicates with evitaDB)
* GraphQL - a rich API for web oriented applications, primarily in the JavaScript domain. This API is designed for ease
of use, comprehensibility and querying / passing data using as few request/response turnarounds as possible
* REST API - a rich API for other domains than JavaScript that don't want to use the gRPC style. The API represents a
conservative approach, since the REST API, even though it has several disadvantages, is still one of the most used
protocols out there

This phase allows for accepting the API call in some form of input query, which is optimal for the protocol being used.

#### Phase: query parsing

In this phase, the input form of the query is translated to the common query language, used through evitaDB. Some
protocols pass the query in a string form (gRPC), and for such, we use the [ANTLR](https://www.antlr.org/) grammar for
parsing. When syntax errors occur, the query processing ends here.

The query in evitaDB is represented by a tree of nested "constraints" divided into three parts:

* filterBy - limits the amount of returned results
* orderBy - defines the order in which the results will be returned
* require - allows the passing additional information on how much data the returned entities will have, how many of them
are required and what other computations should occur upon them

The description of query language constraints and possibilities is a part of the developer documentation, and thus is
not part of this document.

#### Phase: query validation

This phase works on an already parsed query syntax tree. In this phase, we try to reveal logical errors in the query
that are otherwise syntactically correct.

### Stage: query planning

#### Phase: selecting indexes

As the first query planning step, evitaDB consults the target entity collection / catalog and scans the syntax tree for
constraints that would allow us to use smaller indexes than the [global one](#global-entity-index). All possible index
alternatives are collected here.

There is a single expensive operation that might happen. In order to select
proper[ referenced entity indexes](#referenced-entity-index), we must actively evaluate a part of the query on the
referenced type index.

#### Phase: formulating filtering tree

For each index set selected in the previous phase, we create an appropriate "formula computational tree". The formula
syntax tree reflects somehow the input query, but is significantly different. It represents the calculation procedure
that needs to be performed in order to get the results for the input query. Each formula contains either pointers to a
part of the [indexes](#indexes), which will be used for the computation or pointers to other formulas that will return
the inputs for the formula to use. No real computation happens here, so the formulation is quite fast. The entire
formulation path is optimized to find appropriate parts of the indexes in **O(1)** or **O(log(n))** complexity.

After each formula computational tree is created, it is scanned and checked from top to bottom, for whether the result
for any part of the tree is found in the [cache](#cache) (not all nodes of the computational tree are suitable /
worthwhile to cache results). Hot (often repeated) parts of the computational tree have a significantly high chance to
be found in cache. This means that the execution of the query would require real computation only for the part of the
query. More details are present in a separate [cache chapter](#cache).

After possibly replacing part of the computation tree with the cached results, each of these trees calculates its
computational complexity. Each formula implementation has an artificial performance test in `FormulaCostMeasurement`
that tries to test the formula / algorithm performance on 100k record ids. The cost is computed as follows:

```

1.000.000 / average requests/s

```

This cost is then multiplied by the estimated cardinality of the record ids that are expected in the input of the
formula (using the `getEstimatedCardinality` method). Because we haven't yet computed the tree, we can't be sure how
many records will be in the input, and so we work with worst case scenario:

* boolean AND operations estimate their input counts as the lowest input record id count
* boolean NOT operation estimate the input count as a super set count minus the subtracted count
* other operations estimate their inputs as a sum of all input record id counts

The overall estimated cost of the formula is the sum of costs of all internal formulas, plus estimated cardinality
multiplied by the cost of the formula itself. Using this computation, we're able to calculate the overall estimated cost
of the entire formula calculation tree, and compare it with other variants that use different indexes. When the formula
is replaced with the already calculated result in the cache, its estimated cost is `1`, and also, such a formula has no
inner formulas, so the formulas that can take advantage of cached results are naturally more preferred against the
others. That means that even the "worst case" formula using a global index might win the selection in case it can reuse
the results of the large parts of the calculation tree.

During the formulation of the calculation tree, there is also a special type of formula - called `SelectionFormula` -
this formula provides access to two alternating internal formulas. One takes advantage of the data in
the [indexes](#indexes), while the other contains
the [predicate](https://www.geeksforgeeks.org/java-8-predicate-with-examples/) that could be applied on the entity,
providing that such an entity is available. Each predicate brings along information about what parts of the entity need
to be fetched from the disk/cache in order to successfully apply the predicate. See
the [prefetching entities phase](#phase-prefetch-availability-analysis) for more details.

#### Phase: creating sorter (optional)

In case sorting is specified in the input query, we create an instance of the sorter allowing us to order the entities
accordingly. If no sorting is specified, the entities will be returned in their natural order, which is always ascending
order by entity primary key.

Creating the sorter is very cheap - it requires finding a [proper index](#sort-index) at most.

During this phase, the comparator that works directly on entity data is also created, and the information for entity
prefetching about what parts of the entity need to be fetched from the disk/cache is extended (in order to successfully
apply the comparator). See the [prefetching entities phase](#phase-prefetch-availability-analysis) for more details.

#### Phase: prefetch availability analysis

In case there is a selection formula present in the conjunction scope, we estimate the costs of fetching the necessary
entity data from the disk and consider, whether it wouldn't be more optimal to prefetch the part of the entities from
the disk to apply the predicate on them, instead of merging thousands of record bitmaps, which are present in indexes.

**What does the conjunction scope mean?**

A conjunction scope is a part of the formula calculation tree that begins at the root and covers all formulas that are
internally combined by conjunction (boolean AND). Let's see an example:

Root of formula calculation tree:

* and
* attributeEquals(‘age', 18)
* entityPrimaryKeyInSet(45, 12, 33, 19)
* or
* attributeEquals(‘sex', ‘female')
* entityPrimaryKeyInSet(66, 78)

The conjunction scope covers only constraints: `attributeEquals(‘age', 18)`
and `entityPrimaryKeyInSet(45, 12, 33, 19)`

If there are any constants present in the input query in the conjunction scope (as there are primary
keys `45, 12, 33, 19` in above-mentioned example), we know that the result must be either one of them or neither of
them. Also, when the client requires returning entity attributes in the result, we'd need to fetch those entity parts
from the disk anyway.

We may take a speculative approach and optimistically prefetch those entities upfront, believing that all/most of them
would be returned, and filter them using the predicate. This approach might be way faster than combining, let's say
thousands record ids from the indexes.

During the planning phase, we collect all information necessary to make the decision of whether the prefetch is
worthwhile or not. It may be counterintuitive, but accessing entities by one or by set of their primary keys is quite a
common use case, and this specific handling brings a significant performance boost for these cases.

#### Phase: preparing extra result producers

The final preparation phase creates instances of extra result producers. The producers capture pointers to all the data
necessary to calculate the extra result object requested by the client. The creation is a fast operation, since no
computation doesn't occur yet.

Extra results in evitaDB provide access to additional data that is somehow interlinked with the current query result. By
calculating both the main result and the additional data in one run, we could take advantage of intermediate sub-results
and significantly reduce the computational cost compared to the situation when we would have to issue separate queries
for all the data necessary (which is common in the relational databases world).

Some examples of extra results are:

* retrieve parent hierarchy trees for each returned record
* retrieve a set of facets present on the selected records and calculate their counts or selection impact
* compute the value of a histogram for attributes or prices of selected records

The extra result producer may internally manage multiple "computers" that handle the single computational case.

**Example:**

The client asks for following extra results:

```

require(

  attributeHistogram(age, 20),

  attributeHistogram(height, 30),

  attributeHistogram(width, 30)

)

```

The server instantiates single extra result producer for attribute histogram computation that holds three computer
instances:

1. a computer for the attribute `age` histogram with 20 buckets requested
2. a computer for the attribute `height` histogram with 30 buckets requested
3. a computer for the attribute `width` histogram with 30 buckets requested

### Stage: query execution

When the `QueryPlan` is created, we know how to compute the result, and we're going to do that in this stage. In the
current implementation, we don't cache the prepared query plans for the same input queries. Because the data might
change frequently, it wouldn't be cost-effective.

#### Phase: prefetching entities (optional)

As a first step, we compare the estimated costs of prefetch vs. index related approaches, and when the prefetch is
available and promises lesser costs, we prefetch the entities. There is also a chance, that the required entity parts are
already present in the [cache](#cache), so our prefetch doesn't need to read the data from disk, but uses the data
already present in the memory. This fact is a part of our cost estimation and comparison.

#### Phase: filter computation

After phase 1 is complete, the `compute` method on the root of the selected formula calculation tree is called, and it
invokes the computations on all its children. When the prefetch occurred, the selection formulas in the tree use the
predicate on the prefetched entities and avoid calling inner formulas, thus discarding the necessity to combine high
cardinality bitmaps.

Each node of the formula calculation tree [memoizes](https://en.wikipedia.org/wiki/Memoization) its computed result. Any
subsequent compute call, returns an already calculated result without any performance penalties. The extra result
producers benefit from this fact a lot. Each extra result producer can focus on a different part of the computational
tree and read its result or reuse it (along with its computed results) in a new formula calculation tree, producing the
data for a requested extra result. Although the calculation formula tree differs from the input query tree, the key
constructs, such as a user filter container or facet constraints, remain distinguishable from other formulas, and serve
as pivots for the transformation operations on the calculation tree.

#### Phase: sorting and slicing

In case the filtering produces any result, the sorter implementation is used for retrieving the requested slice of data
in the defined order. In case the entities are prefetched, some sorters (currently
only [presorted records sorter](#presorted-record-sorter)) might behave differently. Instead of working with the full
index data, they might choose to compare the entity data directly - which in low cardinalities also brings a lot of
performance savings.

#### Phase: entity fetching (optional)

Entity fetching occurs only when a client requests for any part of the entity body data. In case the client requests
only entity primary keys, this step is omitted.

##### Entity storage decomposition and decoration

An entity can be decomposed into multiple storage parts that are stored as key value records
to [the storage](#storage-model). The storage parts' decomposition represents a compromise between data size, and
typical expected usage patterns and is as follows:

1. **entity body:** maintains the primary key. Hierarchical placement, a set of entity locales and a set of all
available associated data keys
2. **global attributes**: identified by the entity primary key. It contains the locale agnostic key-value tuples of all
entity global attributes
3. **localized attributes**: identified by the entity primary key and a locale. It contains locale specific key-value
tuples of attributes for single locale (there as many storage parts of this type as entity locales)
4. **associated data**: identified by entity primary key and the associated data key (associated data name with optional
locale). It contains the value of a single piece of associated data
5. **prices**: identified by the entity primary key. It contains an entity price inner record handling value and
all [price records](#price-record) of the entity
6. **references and reference attributes**: identified by the entity primary key. It contains the data about all the
entity references (reference name, referenced entity primary key, referenced group primary key and all attributes
specific for this reference).

For fetching each of the storage parts, there is a separate `require` constraint available, so that the client can
pick only those that it really needs to use.

Each storage part is the smallest indivisible part, that must be entirely fetched. When the client requires only one
global attribute out of ten, the entire global attributes storage part must be fetched from disk (the same fact is valid
for prices or references). When entity is reconstructed, we differentiate between the `Entity` that contains all the
data fetched (the entire content of fetched storage parts) and `EntityDecorator` that envelopes the `Entity` and filters
the data through a set of predicates, narrowing the visibility scope to exactly the one requested in the input query.

The entities might have been also [prefetched](#phase-prefetching-entities-optional) or captured in the [cache](#cache).
In such a situation, no physical read from the disk occurs.

**Note:**

Entities might be read and transformed to a full entity or left in a storage related "binary" form. The gRPC protocol
and Java driver uses an "experimental mode", where the unparsed binary entity parts are transported through the gRPC
protocol directly to the client that uses the same Kryo deserializers to reconstruct the entity to a form it could work
with. This approach could be used only if the client is Java based, since it could reuse our Kryo deserializers
implementations (Kryo is not portable easily to other platforms). On the other hand, this allows bypassing several
transformation steps, and we hope it brings some performance gains. This needs to be tested and measured first.

#### Phase: extra results computation (optional)

Last step of the computation is the extra results fabrication. The process is quite different for each producer and is
described in detail in a [separate chapter](#extra-result-computation-in-detail). Extra result producers build up on
prepared index pointers and the product of the filtering phase.

### Cache

Cache is one of the key components of evitaDB. Cache provides a common API to cache results of many of the expensive
operations using common concepts and an API. Making all internal data structures immutable allows us to effectively
determine whether the cached result can be used as a result of our computation or not, because the data has changed.

#### Cacheable elements

The following data structures are subject to caching:

##### Formula calculation results

Products of all expensive formulas can be cached. All formulas at the leaves of the formula calculation tree either
refer to a bitmap in the [indexes](#indexes) or contain constants passed in the input query. Formulas higher in the
calculation tree refer to the formulas below.

We can say that any part of the formula calculation tree is defined by its structure, the set of constants from the
input query and the set of transactional ids that are associated with the bitmaps at the tree leaves (either directly or
transitively). This fact allows us to compute a single hash number that is computed recursively from the formula node,
while taking into account:

* the formula type (class)
* the child formula hashes
* the transactional ids of referenced bitmaps from indexes

Certain formulas are sensitive to inner formula order (for example NOT formula), while others are not (for example
AND/OR). In such cases we can first sort the numbers before passing them to the hashing function and make the hash
resistant to situations when the conditions in the input query are swapped (for example `1 OR 2` vs `2 OR 1`, that
produce the same result but would produce a different hash if the formula children were not sorted first).

**Note:**

For computing the hashes, we use [zero allocation implementation](https://github.com/OpenHFT/Zero-Allocation-Hashing)
of the [xxHash](https://github.com/Cyan4973/xxHash) function. This hash function promises good performance metrics
and [low collisions](https://github.com/Cyan4973/xxHash/wiki/Collision-ratio-comparison), even on small input data, and
has good results even for a [birthday paradox problem](https://en.wikipedia.org/wiki/Birthday_problem).

By applying the above rules, we can compute a single long number that uniquely identifies each formula in the tree even
before its output is calculated at impressive speeds.

**Note:**

Why is the caching based on a formula calculation tree way better than the one based on input query tree? Using some
form of the input query as the cache key brings a lot of disadvantages. We wouldn't be able to cache partial
computational results of the query easily, since the transformation from a query tree to a formula calculation tree is
not 1:1. The cache also would be inefficient for moving input parameters - for example, when the query requires prices
valid "now", the "now" is constantly changing and that would always mean the cache misses in processing of such queries.
On the other hand, the formula calculation tree stays the same, even for different "now" moments, providing the "now"
doesn't cross the next threshold in the [range index](#range-index), which would ultimately mean bringing new bitmaps
into the calculation, and producing different results (and that's when the cache miss is totally fine and desired).

##### Extra results

The products of the extra result computers are subjects to caching too. Input for the computer instance is a
configuration of the require constraint - i.e. a few constants, the result of filtering constraint, or its part at least
and references to additional bitmaps in [indexes](#indexes). The calculation of the unique hash for each one of this
input type is possible, and thus, we can compute a single long hash for an entire computer instance.

The hash of the computer instance is computed from:

* the computer type (class)
* the constants from require constraint
* the transactional ids of referenced bitmaps from indexes
* the hash of the referenced formula or its part

##### Entities

The cache can also hold entity bodies that are frequently fetched from the disk storage. The problem with the entity is
that it is composed of multiple "storage parts", which can be modified and fetched separately. Multiple readers may read
different parts of the entity with different query parameters, and that means that the returned entity is expected to
contain a different structure for each one of the clients.

Let's discuss each of these problems separately. First, the problem with different views on the same entity.

The entity [storage structure](#entity-storage-decomposition-and-decoration) dictates how the `Entity` is fetched from
the disk and `EntityDecorator` controls what data is really visible from the outside. The cache contains the `Entity`
instances that are generally input query agnostic. We don't want to force the entity to be fully fetched from the disk
before putting it into the cache. So when the entity is retrieved from the cache, we need to check whether all the
already fetched storage parts in the entity would satisfy the current input query. If not, the missing storage parts are
fetched and integrated into the entity (the entity in the cache is replaced with a new, richer, instance). Then we need
to limit the scope of the entity (the entity in the cache may be way richer than what the client currently requests) via
the `EntityDecorator`, to reflect the current input query.

Second, the problem with potential obsoleteness of the already fetched parts of the `Entity` in the cache.

If a transaction that alters one of the entity storage parts is committed, we need to ensure that transactions opened
after this commit don't read the cached instance of the entity that contains the already obsolete storage part. That's
why we maintain original versions of the storage parts in the `Entity` instance. When the entity is retrieved from the
cache, we need to compare the versions in it with the versions for those storage parts in the
current [mem-table index](#mem-table-index) that maintains version information in the memory (with O(1) access
complexity) next to the primary key of a particular storage part. If all versions match, we can safely use the `Entity`
as a result for the current input query. If not, we need to create a new `Entity` instance by cloning the existing one
and re-fetching all the entity storage parts, whose versions don't match.

#### Cache filling and invalidation

The process of filling and invalidating cache needs to handle the following actions:

* it needs to keep track of all the [cacheable elements](#cacheable-elements) that are requested by the clients and
collect the count of their repeated usage
* it needs to decide, which of these elements are worth to be cached and propagate them to the cache
* it needs to invalidate / remove the existing cached elements that are no longer worth to keep there
* it needs to fit to the space, that is allocated to the cache to avoid Java OutOfMemory situations

##### Anteroom

When the query is processed, there are exactly specified phases where the processing logic consults the cache whether
there is a cached result for a certain cacheable element. The call is issued on the `CacheSupervisor` interface and
delegated to the so-called "anteroom". The anteroom serves as a temporary space where all those requests for cached
results get trapped in case they could not be satisfied.

When an anteroom is asked for a cached value, it first asks Eden whether there is a cached record for the element (the
key is a long produced by the hash function). If there is, it just increments its usage count and returns it. If there
is no cached record, it looks at its own space (concurrent hash map) whether there is an adept record for this element.
If there is, the original element is returned without change. If there is no adept present, it clones the element in the
input and adds to it a new "onCompute" callback, that will register a new adept to the anteroom once the element is
computed/fetched.

The adept record carries following data:

* long(8B) `recordHash` - product of the hash function for the cacheable element
* long(8B) `costToPerformanceRatio` - key part for computing the worthiness of the cacheable element
* int(4B) `sizeInBytes` - estimated size of the object in memory in bytes
* int(12B + 4B) `timesUsed` - `AtomicInteger` that tracks number of usages of the record adept

When the amount of adepts in the anteroom exceeds a specified threshold (let's say 100k adepts), or it's older than a
specified period of time (let's say 60 seconds), the entire collection is replaced with a new concurrent hash map, and
the original map is passed to the [Eden](#eden) for [evaluation](#cache-re-evaluation).

As you can see, the cache adept doesn't keep the result data - only the meta-information about them, so it's fairly
small - 100k cache adepts represent around 8MB of memory (including size estimation for concurrent hash map carrier).

**Note:**

There is a chance, that the Anteroom will fill up faster than the Eden can keep up with the adepts' recalculations.
Because the collection for re-evaluation is held in AtomicReference and the Eden's recalculation is never executed in
parallel (it figures as a single task in SchedulerExecutor), when such a situation occurs, the latter collection just
rewrites the previous one causing the previous collection just being ignored. Nonetheless, this fact is reported in the
metrics, because it probably means that the cache is not correctly configured or the system just can't keep up.

##### Eden

The Eden is a real cache maintainer - the cache is represented by a single concurrent hash map, where the
key (`recordHash`) is the long number produced by the cacheable element hash function, and its value is the cacheable
element result wrapped into the following envelope:

* long(8B) `recordHash` - a product of the hash function for the cacheable element
* long(8B) `costToPerformanceRatio` - a key part for computing the worthiness of a cacheable element
* int(4B) `sizeInBytes` - an estimated size of the object in memory in Bytes
* int(12B + 4B) `timesUsed` - an `AtomicInteger` that tracks number of usages of the record adept
* int(12B + 4B) `cooling` - an `AtomicInteger` that tracks number of iterations, where this element is not worthy enough
to stay in cache
* pointer `payload` - the cached result payload

When the Eden is asked for cached value, it retrieves it using the long key (`recordHash`) and increases its usage
counter.

###### Cache re-evaluation

The Eden is responsible for the re-evaluation of the cached elements. The cache [Anteroom](#anteroom) periodically asks
the Eden to evaluate its adepts.

The Eden first combines a passed adept collection with existing cached records into one big array. It excludes all
adepts whose size in bytes exceeds a maximal allowed size, and the usage count is lesser than minimal usage threshold.
When usage count is zero for the existing cached record, it is not immediately removed from the considered list, but its
"coolness" factor gets increased. Only after this "coolness" factor exceeds the configured threshold, the cached record
is removed from the cache.

When the collection of the competing adept records is finished, it is sorted by the worthiness value in descending order
and iterated. The sum of all adept record expected memory requirements is calculated during the iteration, and when the
size reaches the maximal allowed limit, the current index in the adept array is remembered. All cached records after
that index are removed from the cache, and all non-cached records before that index are inserted into the cache.

New records that are freshly inserted into the cache contain no payload - i.e. computed results that could be used for
that cached record in query processing. They only contain the estimated size of such a result, which was observed when
the adept was registered by the anteroom. For initializing the payload, we use a similar mechanism as the Anteroom does
for initializing the estimated size. On the first use of the cached record, we only clone the input cacheable element in
the input and add to it a new "onCompute" callback that will fill the cached record payload once the element is
computed/fetched.

###### Cache worthiness calculation functions

During [cache re-evaluation](#cache-re-evaluation) the system calculates the worthiness factor. It's calculated either
for each adept or existing cached record as follows:

```

(costToPerformanceRatio * Math.max(0, timesUsed - minimalUsageThreshold)) / sizeInBytes

```

Where `costToPerformanceRatio` is computed for:

**Formula calculation result as:**

```

sum(childFormula.costToPerformanceRatio) + (operationalCost * numberOfInputRecords / operationalCost * numberOfOutputRecords)

```

Where `operationalCost` is derived from an artificial performance test in `FormulaCostMeasurement` that tries to test
the formula / algorithm performance on 100k record ids. The cost is computed as follows:

```

1.000.000 / average requests/s

```

**Extra result as:**

```

operationalCosts * numberOfInputRecords / operationalCost * numberOfOutputRecords

```

In this formula,`operationalCosts` is also computed in the `FormulaCostMeasurement` performance test the same way as
it's done for [computing formula costs](#phase-formulating-filtering-tree). In case of extra result computers, the
efficiency might be implemented slightly differently for each implementation.

**Entity as:**

```

fetchedStoragePartCount * operationalCost

```

Where the `operationalCost` is based on a benchmark of reading data from disk and the following reasoning. Reading a 4kB
storage part from the SSD disk while
simultaneously [dropping the Linux file cache](https://www.linuxquestions.org/questions/linux-kernel-70/how-to-disable-filesystem-cache-627012/#post3504687),
the reading performance was 6782 reads/sec. Recomputed on 1 million operations, it's a cost of `148`.

###### Invalidation

All cacheable elements and their input data are immutable. Bitmaps in indexes carry their transactional ids and when
their data are changed and committed, a new bitmap is created with the new transactional id. There is also a new
instance of the parent index, that points to that new bitmap and all other non-changed bitmaps (whose instances can be
reused) and which is propagated to the new version of the entire catalog. So, a new transaction that issues the exact
same query to the catalog will have the same formula calculation tree except for the one leaf formula, that
will now point to the new bitmap with the new transactional id. This fact will cause the hash of the entire formula to
differ from the previous one.

Similar rules apply to entity bodies - bodies are stored in immutable parts. When an entity changes, the modified new
versions of their modified parts will be created and stored. The actual versions of the entity storage parts can be
checked against mem-table index at any time, and we can safely verify whether the value in cache is a valid one.

This fact is the basis for automatic invalidation. If the data is modified, the new queries will try to fetch the
results by the different hash values, due to the change in transactional ids of the referenced bitmaps. The old cached
values will stop being retrieved, and their worthiness, due to their decreasing usage, decreases as well, and soon, they
will fail to reach the necessary threshold to be kept in cache. They may also sooner fail to reach the minimal usage
threshold and their "cooling period" might kick in faster (and when they're cool enough, they're removed from the
cache).

#### Cache persistence (future work)

In the case of a database restart, the newly started instance would start with the empty cache that could be fully
warmed up after multiple iterations of adept evaluation that could take minutes. But, we could also serialize the cache
along with the catalog data into a separate file and reconstruct it immediately after database restart. Loading the
cache would add a few seconds to the start of the database (it depends on the size of the cache), but could immediately
save a lot of computational resources that would be otherwise wasted for computing the results again.

#### External cache support (future work)

The best queries are those that will never arrive at the evitaDB API. Many "state of the art" web frameworks support
generating parts or whole pages on the disk as a part of their caching mechanism
or [publishing process](https://nextjs.org/docs/basic-features/data-fetching/incremental-static-regeneration). The
problem is that e-commerce sites usually contain highly dynamic and interlinked content and a single entity change may
affect many pages. This change should lead to an invalidation of generated content, that is hard to guess and track.

**Example:**

The single product is sold out and shouldn't be displayed to the users anymore. If such a product was last in the
category, the category itself should stop displaying to the users, the same goes for the brand in the brand listing (the
product might have been the only product of its manufacturer). The product might have been among the most selling
products of the main category, and it should also stop being displayed there, or it might be referenced by other products
as their alternative or spare part. As you can see, a tiny change can have a big consequences on the site, and that's
why the caching is a hard task for e-commerce sites.

What if the database could proactively invalidate the external cache - or mark statically generated pages as obsolete?
Would it open up a possibility to use statically generated resources a little more?

The evitaDB could use the similar system that it uses for internal cache even for the external one. The external system
might generate a unique random token at the start of the page rendering and associate it with all the queries issued
when the page is rendered. evitaDB would record all bitmap transaction ids associated with such a token. When the
transactions containing some updates are committed, and a bitmap with an assigned transaction id is going to be garbage
collected, evitaDB may retrieve all tokens associated with this transaction id and invalidate them.

We don't know whether this concept would be feasible in practice, and it would require a lot of additional proofing and
optimization. It may turn out that the cardinality of the transactional ids per token/page is too high, or that a single
bitmap invalidation leads to too many page invalidations than is practical.

## Query evaluation in detail

This chapter describes the [filter computation phase](#phase-filter-computation) in more detail.

### Boolean algebra

The boolean algebra is the heart of the query evaluation. evitaDB builds on an existing, extra fast library
called "[RoaringBitmap](https://github.com/RoaringBitmap/RoaringBitmap)", originally written
in [C](https://github.com/RoaringBitmap/CRoaring) by [Daniel Lemire](https://lemire.me/blog/), and a team, led
by [Richard Statin](https://richardstartin.github.io/), managed to port it to Java. The library is used in many existing
databases for similar purposes (Lucene, Druid, Spark, Pinot and many others).

There are two main reasons why we chose this library:

1. it allows us to store int arrays in a more compressed format than a plain array of primitive integers,
2. and contains the algorithms for fast boolean operations with these integer sets

The main prerequisite is that the integers are sequence based - i.e., there is high probability that the integers in the
array will be close to one another. This prerequisite is true in the case of evitaDB datasets because we require each
entity to have a unique int primary key, that is either generated by our internal sequence or by an external sequence.

**Note: all performance results were measured on an Intel® Core™ i7-10750H CPU @ 2.60GHz base frequency (up to 5GHz
boost frequency) × 6 cores and 12 threads running Ubuntu 20.04 LTS**

#### Conjunction (AND) / disjunction (OR)

Conjunction and disjunction operations are directly supported by the RoaringBitmaps API. The operations are translated
either to an `AndFormula` or an `OrFormula`. Our performance tests show the following results for two 100k integer
arrays of pseudorandom numbers that make a monotonic row:

* AND 116094.151 operations / sec
* OR 80640.199 operations / sec

#### Negation (NOT)

Negation is also directly supported by the RoaringBitmaps API. There is a special formula handling when the formula
calculation tree is constructed via a [visitor pattern](https://en.wikipedia.org/wiki/Visitor_pattern). The negation
operation requires two arguments - a superset part and a subtracted (negated) part. The subtracted part is represented
by the product of the inner constraints of the `not` container. The superset is the "rest", which is the product of the
constraints / formulas on the same level as the `not` container formula, and that cannot be easily referenced at the
time the `not` formula is created from the `not` constraint. That's why the `not` constraint is initially transformed to
a `FutureNotFormula`, and this formula is then post processed to a real `NotFormula` object. When a `not` constraint is
a root constraint, the superset is a set of all available records taken from the selected index.

**Why is it so complex?**

The implementation might be a lot simpler if we used the [negation](https://en.wikipedia.org/wiki/Negation) of the
formula children against superset of all records in the index at the moment we process the `not` constraint. But this
approach would slow down the calculation in cases there is a lot of records in the index (let's say 1 million) and the
product of the inner constraints is very small (consider constraint like `not(eq(entityPrimaryKey, 1))` - then the
negation would result in 999.999 possible records we're pushing further to the calculation pipeline). If we take the
superset from the output of other constraints in the translated query (which is not possible in every case) we may work
with considerably lower cardinalities and be much faster.

Our performance tests show following results for two 100k integer arrays of pseudorandom numbers that make a monotonic
row:

* NOT 148431.438 operations / sec

### String queries on attributes (starts with / ends with / contains)

The current implementation is highly inefficient, both performance and memory consumption wise. The implementation goes
through all distinct string values registered for the attribute, and applies a predicate on them. Those who match are
translated to a set of integer bitmaps via an [inverted index](#inverted-index) and joined by
an [OR formula](#conjunction-and-disjunction-or).

We need to introduce new data structures for the sake of these operations - probably the compressed trie structures that
would allow us to store values more efficiently and also would allow for much faster lookups for appropriate bitmaps.

### Comparator queries on attributes (equals / in set / greater / lesser / between)

The comparator queries are applicable on non-range types only (either simple data types or arrays). Each constraint
requires a slightly different approach:

* `equals` - requires a single **O(1)** lookup in the [filter index](#filter-index) to retrieve the appropriate element
index in the [inverted index](#inverted-index), and retrieving a bitmap of all record ids on that index, equals
operation handling is therefore pretty efficient
* `in set` - requires multiple applications of the process described for the equals
* `greater than` / `lesser than` - uses the [inverted index](#inverted-index) directly, it applies a binary search on
values in it to identify the element index that represents the threshold for the results (either exclusive or
inclusive depending on the constraint type), then it collects all bitmaps of record ids before / after the threshold
and joins them using an [OR formula](#conjunction-and-disjunction-or)
* `between` - applies the process described for greater and lesser constraints. It performs two separate lookups into
the [inverted index](#inverted-index) and joins a bitmap of record ids between the two found thresholds using
an [OR formula](#conjunction-and-disjunction-or)

### Range queries on attributes (equals / in set / greater / lesser / between / in range)

Range queries can operate only on Range types. `Greater than` / `lesser than` constraints don't make much sense with
range types, because they first compare the left bounds against the passed value and if they are equal, the right bound
is compared. We don't see much practical value in such use-case, but our implementation corresponds
to [the PostgreSQL one](https://www.postgresql.org/docs/9.3/functions-range.html).

Operations `equals`, `in set`, `greater` and `lesser` operate on the [inverted index](#inverted-index) the same way as
the [comparator queries](#comparator-queries-on-attributes-equals-in-set-greater-lesser-between). The
operation `between` on range types is translated to an [overlap query](#overlap-query). The operation `in range` can be
used exclusively on range types, and is translated to a [within query](#within-query).

#### Within query

The within query allows to return all primary keys that have a range that envelopes (inclusively) the threshold stated
in the query. In other words, it returns all records that are valid at a certain moment in time.

This query is computed in a following way:

1. all the record IDs that start and end before the threshold (inclusive) are collected into a dual array of
   bitmaps: `array of recordIdsThatStartBefore` and `array of recordIdsThatEndBefore`
	1. both arrays of the bitmaps (that contain only distinct record ids) are merged together into a sorted array with
	possible duplicates using a [JOIN formula](#join-formula) producing two bitmaps: `recordIdsThatStartBefore`
	and `recordIdsThatEndBefore`
	2. these two bitmaps are merged together using a [DISENTANGLE formula](#disentangle-formula), that eliminate
	duplicate record IDs on the same positions in both of the bitmaps and produces a single RoaringBitmap with
	distinct record ids - this product represents all the record IDs that possess at least one range starting
	before (inclusive) the threshold and not ending before it as well - let's name this product
	as `recordIdsThatStartBeforeWithElimination`
2. all the record IDs that start and end after the threshold (inclusive) are collected into a dual array of
bitmaps: `array of recordIdsThatStartAfter` and `array of recordIdsThatEndAfter`
	1. both arrays of bitmaps (that contain only distinct record ids) are merged together into a sorted array with
	possible duplicates using a [JOIN formula](#join-formula), producing two bitmaps: `recordIdsThatStartAfter`
	and `recordIdsThatEndAfter`
	2. these two bitmaps are merged together using a [DISENTANGLE formula](#disentangle-formula), that eliminates
	duplicate record IDs on the same positions in both bitmaps and produces a single RoaringBitmap with distinct
	record ids - this product represents all the record IDs that possess at least one range ending after (inclusive)
	the threshold and not starting after it as well - let's name this product
	as `recordIdsThatEndAfterWithElimination`
3. now we can simply apply a RoaringBitmap conjunction (boolean AND) operation on both of the
bitmaps, `recordIdsThatStartBeforeWithElimination` and `recordIdsThatEndAfterWithElimination`, and retrieve only the
record IDs, in which at least one range contains the specified threshold

This query is manifested by a constraint targeting Range type:

* price valid in
* in range

#### Overlap query

The overlap query allows to return all primary keys whose range overlaps (including boundaries) the specified range. In
other words, this returns all records that were valid in a certain period of time (at least partially).

This query is computed in the following way:

1. we compute the result the same way as for the [within query](#within-query) and name
it `recordIdsOverlappingWithElimination` with these exceptions:
	1. for the computation of `recordIdsThatStartBeforeWithElimination`, we use as a threshold of the start threshold of
	the input range
	2. for the computation of `recordIdsThatEndAfterWithElimination`, we use as a threshold for the end threshold of the
	input range
2. we collect all the record IDs that start within the start and end threshold of the input range and name
them `recordIdsStarting`
	1. we collect all the record IDs that end the within start and end threshold of the input range and name
	them `recordIdsEnding`
	2. we compute the disjunction (with a boolean OR) for `recordIdsOverlappingWithElimination`, `recordIdsStarting`
	and `recordIdsEnding`

This query is manifested by this constraint targeting Range type:

* between

#### Join formula

This formula accepts a set of bitmaps with distinct record ids and produces a bitmap with possibly duplicate record ids
that still maintain ascending order.

**Example:**

Input bitmaps:

```

[1, 2, 3, 4, 5 ]

[   2, 4        ]

[1, 6]

```

produce following result for JOIN operation:

```

[1, 1, 2, 2, 3, 4, 4, 5, 6]

```

This formula creates a separate Iterator for each bitmap that implements a Comparable interface, comparing a
next-to-be-returned value from the iterator with the value of another iterator. These iterators are added to
the [PriorityQueue](https://www.geeksforgeeks.org/priority-queue-class-in-java/) that sorts them by the lowest
next-to-be-returned value. From this priority queue iterators are polled one by one and each polled iterator produces
one record ID and as long as it is not entirely exhausted, it is offered back to the queue with a changed internal
state, moving the internal pointer to the next value in the iterator.

This way, we combine all the record IDs from all input bitmaps into a single ordered array bitmap with duplicates.

The worst case is an **O(m * log(n))** complexity, where m is the overall number of record IDs in all bitmaps and n is
the number of bitmaps. Unfortunately, the complexity is still too high, but we haven't come up with a better solution.

#### Disentangle formula

The `disentangle formula` accepts two bitmaps of numbers and produces a bitmap of integers that are present in the first
array, but are not duplicated on the same indexes in the second array.

**Example:**

Input bitmaps:

```

[   3, 3, 6, 9, 12],

[2, 3, 4, 6, 8, 10, 12]

```

produce output:

```

[3, 9]

```

The algorithm picks a record ID from both bitmaps and skips it when both IDs are equal. Then it picks another one and
compares it again. A second bitmap pointer advances only when it encounters an ID that is less than or equal to a record
ID from the first bitmap.

Its complexity is **O(m + n)**, where m is the count of record IDs in the first bitmap and n is the count of record IDs
in the second one.

### Queries on reference attributes (reference having)

The queries on reference attributes work the same as queries on entity attributes with a single difference. They always
target the [referenced entity indexes](#referenced-entity-index), and may also
trigger [selecting these reduced indexes](#phase-selecting-indexes) as the main index set for handling the entire query.
In that case, they are not selected, they just produce an [OR formula](#conjunction-and-disjunction-or) that joins all
the record IDs present in all records referencing the entity.

### Hierarchical queries (within / within root)

The hierarchical queries use the [hierarchy index](#hierarchy-index) to traverse the node tree by
the [depth first](https://en.wikipedia.org/wiki/Depth-first_search) search algorithm. During the tree traversal, the
subtrees stated in `excluding` constraints are immediately discarded. They always
target [hierarchy entity indexes](#hierarchy-node-entity-index) and may also
trigger [selecting these reduced indexes](#phase-selecting-indexes) as the main index set for handling the entire query.
In that case, they are not selected, they just produce an [OR formula](#conjunction-and-disjunction-or) that joins all
the record IDs present in all records referencing the selected hierarchy node.

### Facet queries

The facet queries consult the [facet index](#facet-index) to get bitmaps of record IDs that target the reference with
specified name. There is, usually, only one bitmap with record IDs per facet ID, but there may be multiple ones if the
facet is a part of multiple facet groups. All the found bitmaps are joined by an [OR formula](#conjunction-and-disjunction-or).

The facet query handling is special in the sense that it produces different formulas depending on the require
constraints:

* facet groups negation
* facet groups conjunction
* facet groups disjunction

The query handling strictly respects the structure from the facet index regarding the formula isolation by respective
facet groups. The constraint translator produces either a `FacetGroupAndFormula` or a `FacetGroupOrFormula` - these
formulas are similar to [generic formulas](#conjunction-and-disjunction-or), but also carry the `referenceName`,
the `facetGroupId` and the requested `facetIds` information. Such information and formula structure is the key for
processing the [facet summary computation](#facet-summary), should it be required.

### Price queries

The price queries are the most complex queries in evitaDB.
The [selling price selection algorithm](../assignment/querying/price_computation.md)
is quite complex itself and the cardinality of prices is often much larger than the cardinality of attributes. The topic
is already discussed in [the price index chapter](#price-index).

There are these following price related constraints:

1. price in currency
2. price in price lists
3. price valid in
4. price between

Only one of the above listed constraints is really translated into the formulas along the priority of the listing. If
constraints `price in currency`, `price in price lists` and `price valid in` are used in the input query,
only `price valid in` is, in fact translated, and all the others are omitted. The translation of `price valid in`
takes into account all omitted constraints, however.

#### Simple price evaluation

Let's focus on simple prices (without inner record related grouping) and the formula calculation tree. The evaluation
differs when `price valid in` is a part of the query and when it is not.

##### No price valid in constraint

If the `price valid in` constraint is not present, we can work directly with the entity primary keys as record IDs. When
the price is needed later in the process (for example for filtering prices in range or sorting along the price), the
lowest sellable prices will be retrieved from the `[EntityPrices](#entity-prices)` data structure that is connected with
the `[PriceListAndCurrencyIndex](#price-list-and-currency-index)`, which is where the filtered entity primary key came
from.

The next problem is the priority of the price lists passed in with the `price in price lists` constraint. The order of
the price lists controls which price will be used as the selling price. When the entity has the product in all requested
price lists, only the price from the foremost price list from the query constraint will be used for the calculation and
others are ignored completely. We solve this requirement by joining the entity primary keys by price list priority,
where the next price list result produces only the primary keys that are not part of the previous result using the
negation operation.

This is an example formula calculation tree for the query
constraint: `and(priceInCurrency(‘CZK'), priceInPriceLists(‘vip', ‘b2b', ‘basic'))`

```

[#0] NO FILTER PREDICATE

   [#1] DO WITH NONE

      [#2] OR

         [#3] DO WITH PRICE INDEX: vip/CZK/NONE

            [#4] [22, 26, 32]

         [#5] WITH PRICE IN b2b WHEN NO PRICE EXISTS IN vip

            [Ref to #3] DO WITH PRICE INDEX: vip/CZK/NONE

               [Ref to #4] [22, 26, 32]

            [#6] DO WITH PRICE INDEX: b2b/CZK/NONE

               [#7] [6]

         [#8] WITH PRICE IN basic WHEN NO PRICE EXISTS IN b2b, vip

            [Ref to #5] WITH PRICE IN b2b WHEN NO PRICE EXISTS IN vip

               [Ref to #3] DO WITH PRICE INDEX: vip/CZK/NONE

                  [Ref to #4] [22, 26, 32]

               [Ref to #6] DO WITH PRICE INDEX: b2b/CZK/NONE

                  [Ref to #7] [6]

            [#9] DO WITH PRICE INDEX: basic/CZK/NONE

               [#10] [9, 15, 16, 25, 30, 31, 38, 40, 45, 46, 48, 49, 50]

```

As you can see, certain nodes are marked with unique numbers (for example `#6`), while others refer to existing
numbers (for example `Ref to #6`). The nodes marked with plain numbers are the nodes that contain the primary
computation - i.e. they compute and produce the record IDs. The nodes marked as `Ref to` are just "pointers" to those
computation nodes, so that they reuse their calculation result, but also actively don't compute anything. The nodes
without text that contain numbers in square brackets (for example `[22, 26, 32]`) represent the origin bitmaps from
the `[PriceListAndCurrencyIndex](#price-list-and-currency-index)`.

##### Price valid in present

If `price valid in` is present, we will obtain internal price IDs from the price index, and we will have to work with
those until the price between is resolved. After that, we need to translate the price IDs to entity primary keys - which
is taken care of in a `PriceIdToEntityIdTranslateFormula` that envelopes the internal formula, keeping the bitmaps with
price ids and applying the price between predicate.

The query is similar to the previous
example: `and(priceInCurrency(‘CZK'), priceInPriceLists(‘vip', ‘b2b', ‘basic'), priceValidIn(now))`

Translates to the following formula calculation tree:

```

[#0] NO FILTER PREDICATE

   [#1] DO WITH NONE

      [#2] OR

         [#3] TRANSLATE PRICE ID TO ENTITY ID

            [#4] DO WITH PRICES IN INDEX vip/CZK/NONE

               [#5] [16, 19, 50, 88] … shortened

         [#12] WITH PRICE IN b2b WHEN NO PRICE EXISTS IN vip

            [Ref to #3] TRANSLATE PRICE ID TO ENTITY ID

               [Ref to #4] DO WITH PRICES IN INDEX vip/CZK/NONE

                  [Ref to #5] [16, 19, 50, 88] … shortened

            [#13] TRANSLATE PRICE ID TO ENTITY ID

               [#14] DO WITH PRICES IN INDEX b2b/CZK/NONE

                  [#15] [5] … shortened

         [#22] WITH PRICE IN basic WHEN NO PRICE EXISTS IN b2b, vip

            [Ref to #12] WITH PRICE IN b2b WHEN NO PRICE EXISTS IN vip

               [Ref to #3] TRANSLATE PRICE ID TO ENTITY ID

                  [Ref to #4] DO WITH PRICES IN INDEX vip/CZK/NONE

                     [Ref to #5] [16, 19, 50, 88] … shortened

               [Ref to #13] TRANSLATE PRICE ID TO ENTITY ID

                  [Ref to #14] DO WITH PRICES IN INDEX b2b/CZK/NONE

                     [#15] [5] … shortened

            [#23] TRANSLATE PRICE ID TO ENTITY ID

               [#24] DO WITH PRICES IN INDEX basic/CZK/NONE

                  [#25] [33, 35, 40, 45, 47, 55, 56, 60, 76, 79] … shortened

```

The excerpt is a shortened version of the formula calculation tree and the subtrees
with [disentangle](#disentangle-formula) and [join](#join-formula) formulas that originate in
the [range index](#range-index) are omitted for clarity. As you can see, the tree is similar, but contains translation
to entity primary keys at the appropriate levels of the tree.

##### Price between present

When a `price between` filtering constraint is used, the top most price formula will contain a predicate that filters
out entity primary keys, whose associated price is not within the required number range, as you can see in the following
example of the formula calculation tree:

```

[#0] ENTITY PRICE WITH TAX BETWEEN 80000 AND 100000

   [#1] DO WITH NONE

      [#2] OR

         [#3] DO WITH PRICE INDEX: vip/CZK/NONE

            [#4] [22, 26, 32]

         [#5] WITH PRICE IN b2b WHEN NO PRICE EXISTS IN vip

            [Ref to #3] DO WITH PRICE INDEX: vip/CZK/NONE

               [Ref to #4] [22, 26, 32]

            [#6] DO WITH PRICE INDEX: b2b/CZK/NONE

               [#7] [6]

         [#8] WITH PRICE IN basic WHEN NO PRICE EXISTS IN b2b, vip

            [Ref to #5] WITH PRICE IN b2b WHEN NO PRICE EXISTS IN vip

               [Ref to #3] DO WITH PRICE INDEX: vip/CZK/NONE

                  [Ref to #4] [22, 26, 32]

               [Ref to #6] DO WITH PRICE INDEX: b2b/CZK/NONE

                  [Ref to #7] [6]

            [#9] DO WITH PRICE INDEX: basic/CZK/NONE

               [#10] [9, 15, 16, 25, 30, 31, 38, 40, 45, 46, 48, 49, 50]

```

Because the filtering occurs on the level where the bitmaps contain only entity primary keys, we need to carry
information about the prices selected for each of those entity primary keys and pass it through some side channel. For
this purpose there is the `FilteredPriceRecordAccessor` interface that is implemented by a certain price related formula
in the formula calculation tree and provides access to such information. These prices are sifted on the way up to the
root formula along with the entity primary keys.

#### Inner record related price evaluation

Prices with inner record IDs and different price inner record handling mode require much more complex treatment. The
complexity from the [simple price evaluation](#simple-price-evaluation) stays, but on top of it, we need to aggregate
prices by the inner record ID and select or calculate the overall price from them.

##### Find first selling price calculation

The price inner record handling mode "FIRST_OCCURRENCE" selects a single price for each inner record ID (that means that
a single entity primary key may have one or more price records to work with). The price with the least amount is then
selected as a representative price for the entity primary key for the sake of sorting or evaluating the price between
the predicate.

The single change in the formula calculation tree is on position `#1`. You can see a formula describing itself
as `DO WITH FIRST_OCCURRENCE` instead of `DO WITH NONE`, which is present for simple calculation, as in the following
example of the formula calculation tree:

```

[#0] ENTITY PRICE WITH TAX BETWEEN 8000 AND 15000

   [#1] DO WITH FIRST_OCCURRENCE

      [#2] OR

         [#3] TRANSLATE PRICE ID TO ENTITY ID

            [#4] DO WITH PRICES IN INDEX vip/CZK/FIRST_OCCURRENCE

               [#5] [51, 139, 154, 170, 455, 562, 598, 638] … shortened

         [#12] WITH PRICE IN basic WHEN NO PRICE EXISTS IN vip

            [Ref to #3] TRANSLATE PRICE ID TO ENTITY ID

               [Ref to #4] DO WITH PRICES IN INDEX vip/CZK/FIRST_OCCURRENCE

                  [Ref to #5] [51, 139, 154, 170, 455, 562, 598, 638] … shortened

            [#13] TRANSLATE PRICE ID TO ENTITY ID

               [#14] DO WITH PRICES IN INDEX basic/CZK/FIRST_OCCURRENCE

                  [#15] [26, 60, 62, 63, 71, 76, 81, 83, 103, 104, 108, 114, 137] … shortened

```

This is an instance of a `FirstVariantPriceTerminationFormula`. This formula iterates over the prices associated with
the entity primary keys and for each entity pk it selects only the first price for each group of prices sharing the same
inner record ID (all the prices are correctly ordered by requested price list priorities). At the end of processing each
entity primary key, it has a map where the key is the inner record ID and the value is a [price record](#price-record).
The `FirstVariantPriceTerminationFormula` then selects the price with the least amount that passes the price between
predicate (if such a predicate is required). If such a price is found, the entity primary key may be propagated upwards
in the formula calculation tree along with the selected price as the associated price.

##### Sum price calculation

The price inner record handling SUM selects the single price for each inner record ID (that means that a single entity
primary key may have one or more price records to work with) and creates a new virtual price as a sum of all amounts of
entity prices. This virtual price is then selected as the representative price for the entity primary key for the sake
of sorting or evaluating the price between predicate.

The single change in the formula calculation tree is on position `#1`. You can see a formula describing itself
as `DO WITH SUM` instead of `DO WITH NONE` that is present for simple calculation, as in the following example of the
formula calculation tree:

```

[#0] ENTITY PRICE WITH TAX BETWEEN 8000 AND 15000

   [#1] DO WITH SUM

      [#2] OR

         [#3] TRANSLATE PRICE ID TO ENTITY ID

            [#4] DO WITH PRICES IN INDEX vip/CZK/SUM

               [#5] [51, 139, 154, 170, 455, 562, 598, 638] … shortened

         [#12] WITH PRICE IN basic WHEN NO PRICE EXISTS IN vip

            [Ref to #3] TRANSLATE PRICE ID TO ENTITY ID

               [Ref to #4] DO WITH PRICES IN INDEX vip/CZK/SUM

                  [Ref to #5] [51, 139, 154, 170, 455, 562, 598, 638] … shortened

            [#13] TRANSLATE PRICE ID TO ENTITY ID

               [#14] DO WITH PRICES IN INDEX basic/CZK/SUM

                  [#15] [26, 60, 62, 63, 71, 76, 81, 83, 103, 104, 108, 114, 137] … shortened

```

This is an instance of `SumPriceTerminationFormula`. This formula iterates over the prices associated with the entity
primary keys and for each entity pk it selects only the first price for each group of prices sharing the same inner
record ID (all the prices are correctly ordered by requested price list priorities). At the end of processing each
entity primary key, it has a map where the key is the inner record ID and the value is a [price record](#price-record).
The `SumPriceTerminationFormula` then creates a new instance of a [price record](#price-record), that will contain a sum
of all amounts of all inner record prices per each entity primary key. The cumulated virtual price must pass the price
between predicate (if such a predicate is required) test and then the entity primary key may be propagated upwards in
the formula calculation tree along with the cumulated price as the associated price.

#### Complex price query

All of the above rules are usually combined in a much more complicated formula calculation tree. The formulas may come
from multiple [reduced entity indexes](#entity-index) that are combined by
an [OR formula](#conjunction-and-disjunction-or) and the entities in the dataset might combine all three available price
inner record handling methods, whose results are also combined by an [OR formula](#conjunction-and-disjunction-or). The
resulting formula tree would then make a several pages long listing, and it makes no sense to include it in this paper.

## Sorting

### Presorted record sorter

The presorted sorter is available for sortable attributes. It first creates a so-called "mask" that contains indexes in
a presorted array taken from a [sort index](#sort-index). Then, it applies pagination / slicing on the mask - taking the
required result count from a certain offset, and after that it just locates the record IDs in the presorted array on the
index specified in the mask.

**Note:**

We've written a performance test, that compares quicksort on 100k pseudo random strings with a length of 10 to 50
characters with the following results:

QuickSortOrPresort.quickSort thrpt 17.576 ops/s

QuickSortOrPresort.preSort thrpt 226.016 ops/s

We guess that this sorting algorithm is still suboptimal and more research is needed here.

### Random sorter

The random sorter takes an array of sorted record IDs, and for each record in the currently returned slice/page ID, it
randomly picks a number between >= 0 and &lt; record ID count. Then, it swaps the record IDs on the current index with
the record ID with the randomly chosen index. This produces a randomly sorted record set.

### Price sorter

The price sorter must apply full sorting in real time. We found no data structure that could bring a different approach
for sorting. The problem is that the client specifies the multiple price lists in the input query and the order of the
price lists represents their priority. The price found in the foremost price list will be used and prices in the
following price lists are just ignored. The logic is too complex for preparing some kind of presorted result and the
price cardinality is also very high.

This sorter picks up the [price records](#price-list-and-currency-index) that were used for filtering the records and
uses their `priceWithTax` or `priceWithoutTax` to sort them with the
Java [TIM algorithm](https://svn.python.org/projects/python/trunk/Objects/listsort.txt). When there was
no `price valid in` constraint used in filtering (that would require working with the exact price records), the lowest
prices from [entity prices](#price-record) structure are used for the sorting. The correct price selection is described
in [the price queries](#price-queries) chapter and is quite complex for prices with inner handling set to
FIRST_OCCURRENCE or SUM.

When the prices are sorted, the result is sliced to a particular viewport and entity primary keys are retrieved from the
price records - i.e., the price records are translated into entity record IDs.

## Extra result computation in detail

Extra results bring additional information along with the primary result of the query. Extra result computation might
take advantage of the already computed sub-results in the formula calculation tree and this fact reduces the total
demand on the server and the latency compared to the demand and the sum of the latencies of separate server requests.

### Facet summary

The facet summary contains a list of all facets (referenced entity IDs) that any of the entities returned in the query
result refer to (pagination does not play a role in this case). Returned facets are grouped by their `reference name`
and within it by `group id`.

For the computation it uses [facet indexes](#facet-index) from all the [entity indexes](#entity-index) that were used
for filtering the result. It traverses all the faceted references and of the all facets recorded in those indexes and
for each one it computes either [count of entities](#count-overview) referring to it or the [impact](#impact-overview)
of selecting this facet, which takes the current state of the user filter into an account.

#### Count overview

The basic form of a facet summary contains for each facet a single number that represents the number of entities in the
result, should this facet be requested in a future query. The calculation computes how many entities possess the
specified facet respecting current filtering constraint except contents of the `user filter`. It means that it respects
all the mandatory filtering constraints, which get enriched by an additional constraint that targets a single facet.
The result of the formula calculation tree represents the number of entities having such a facet.

For each facet in the referenced [facet indexes](#facet-index), it creates a new formula calculation tree that clones
original formula calculation tree, strips `UserFilterFormula` (formula container that contains user filter selection)
contents and adds a new facet filtering formula that targets the facet (i.e. it adds the new element to the calculation
that refers bitmaps of record IDs = entity primary keys that reference to that particular facet ID).

The added formula must respect these requirements in constraints:

* facet groups negation
* facet groups conjunction
* facet groups disjunction

What is important in this derived formula calculation process is the fact that the new tree carries along all the
memoized sub-results from the original formula calculation tree and that for each facet (and there may be hundreds of
them) only a part of the formula tree needs to be recalculated.

##### Facet computation performance optimization

Even if the original formula calculation tree sub-results are preserved, there still may be a lot of computation
happening. Consider following tree composition:

```

AND

   formula A (100k of results)

   formula B (200k of results)

   formula C (300k of results)

   facet group formula D  (50k of results)

```

Even if we have results of formulas A, B and C memoized, and we only modify the contents of the formula D and the
enveloping container AND, we need to process 650k integers over and over. But if we first reconstruct the formula
calculation tree to an optimized form, such as:

```

AND

   AND (75k of results)

      formula A (100k of results)

      formula B (200k of results)

      formula C (300k of results)

   facet group formula D  (50k of results)

```

We use this formula calculation tree as our base tree to derive alternate facet trees from. As such, we may reuse the
memoized result of the new AND formula that consists of only 75k records which would allow us to process only 125k
integers for each facet calculation. This tiny change can lead to saving 52.5 million of integers on a mere 100 facets
that don't need to be processed at all!

#### Impact overview

The impact overview is an add-on for computing simple counts for each facet. For each facet in this summary, it computes
its selection impact on the result entity count - i.e., how many entities would be returned, should this particular
facet be selected. This calculation doesn't ignore existing contents of the `user filter` but enriches them.

The added formula must respect these requirements in constraints:

* facet groups negation
* facet groups conjunction
* facet groups disjunction

So, when the FacetGroupFormula (a formula container that contains inner formulas related to the facet selection related
to the same reference name and group id) is not yet present in the formula calculation tree and a new one is appended,
it must create a proper composition.

There might be the following compositions for placing a new disjunction facet ID in the disjunction form to the existing
formula calculation tree:

**1. no, OR container is present**

```

USER FILTER

  FACET PARAMETER OR (zero or multiple formulas)

```

which will be transformed to:

```

USER FILTER

  OR

    AND

       FACET PARAMETER OR (zero or multiple original formulas)

    FACET PARAMETER OR (newFormula)

```

**2. existing OR container is present**

```

USER FILTER

  OR

     FACET PARAMETER OR (zero or multiple formulas)

```

which will be transformed to:

```

USER FILTER

  OR

    FACET PARAMETER OR (zero or multiple original formulas)

    FACET PARAMETER OR (newFormula)

```

**3. user filter with combined facet relations**

```

USER FILTER

   COMBINED AND+OR

      FACET PARAMETER OR (one or multiple original formulas) - AND relation

      FACET PARAMETER OR (one or multiple original formulas) - OR relation

```

which will be transformed to:

```

USER FILTER

   COMBINED AND+OR

      FACET PARAMETER OR (one or multiple original formulas) - AND relation

      FACET PARAMETER OR (one or multiple original formulas) - OR relation + newFormula

```

The transformation process also needs to cope with complicated compositions in case multiple source indexes are used -
in case of such an occasion, the above-mentioned composition is nested within the OR containers that combine results
from multiple source indexes.

A similar process is used for conjunction and negated facet IDs that could be found in source codes of
the `AbstractFacetFormulaGenerator` class.

The [optimizations](#facet-computation-performance-optimization) that apply on count overview apply here as well.

### Hierarchy statistics

The hierarchy statistics producer computes the cardinalities of entities that match the passed query for each hierarchy
node of specified reference.

The producer uses the [hierarchy index](#hierarchy-index) to traverse the referenced tree and in each node it retrieves
a bitmap of all entity primary keys that relate to that node, and it combines them by
a [conjunction (AND)](#conjunction-and-disjunction-or) with the result of the current query filter (ignoring
pagination). The calculated number represents the count of entities that match the query filter and are referencing this
hierarchy node. The result data transfer object contains the aggregate counts where the parent hierarchy node count is
calculated as: `entityCountForTheNode + sum(entityCountForChildrenNode)`.

### Histograms

Histograms display distribution of values in the entire available range. Histograms are useful for attributes / prices
that have high cardinality of unique values. The end user can see what effect narrowing the filter will have on the
result entity count even before the selection is made. The histograms complement
the [impact calculation](#impact-overview) for facets with low cardinality.

### Attribute histogram

The attribute histogram producer takes data from the [inverted index](#inverted-index) of targeted attributes. It needs
to take the current formula calculation tree and remove the formula that is inside the `user filter` and targets the
very same attribute. This formula tree alteration is necessary in order to compute the histogram for the entire range of
attribute values and not only the narrowed part.

The altered formula tree is then used to filter out all values that don't make sense for the current query. The producer
takes a bitmap of the entity primary keys assigned to each attribute value and combines it using
a [conjunction (AND)](#conjunction-and-disjunction-or) with the altered formula tree. If the result is empty, the
attribute value is not present in any filtered entity, and thus it must be omitted from the histogram. Otherwise, the
count of matching entities is remembered for the attribute value.

The next step is to aggregate all the attribute values with associated counts to the histogram with the maximal
number of buckets requested by the require constraint settings. This is the task for the `HistogramDataCruncher` that
splits the range into exactly the same intervals and aggregates the data.

When the histogram contains long gaps (i.e. multiple empty buckets in a row), we try to optimize the histogram by
lowering the number of buckets from the input settings. Long gaps, although correct, are not good from the user
experience perspective. Narrowing down the number of buckets leads to a new histogram with a reduced number of gaps.
This, however, means that we usually need two passes through `HistogramDataCruncher` to compute the histogram for each
attribute.

#### Price histogram

The price histogram is very similar to the [attribute histogram](#attribute-histogram), but instead of data from the
indexes, it works with data from the [filtered price records](#price-queries) that are retrievable from the formula
calculation tree. Before the histogram is calculated, the producer needs to create an altered formula calculation tree
with a removed `price between` constraint from the `user filter`. If we don't do that, the user would never see the
entire available price range.

Next, the producer needs to withdraw all price records that relate to the entity primary keys in the query result and
compute the histogram using `HistogramDataCruncher` for them. An optimization that allows avoiding long gaps is also
applied here.

### Parents

The parents producer adds an array of parent IDs for each entity returned in the response (taking pagination into an
account). This feature is used, in practice, for rendering breadcrumb navigation menus.

The producer takes the requested information from the [hierarchy index](#hierarchy-index), traversing the tree from the
referenced entity ID up to the root. The producer may only return parent entity primary keys or, optionally, may also
return full bodies of such parent entities. Entity fetching may hit entities already present in [the cache](#cache).

## Obstacles encountered

When the work is done, a lot of things seem simple and clear, but the path to the final version was often more
complicated than necessary, and involved venturing to a lot of dead ends. There was probably double the code written
than is currently present in the main branch.

### Kryo streams

evitaDB relies heavily on the [Kryo](https://github.com/EsotericSoftware/kryo) serialization library. While the library
itself is extraordinarily performant and stable, our usage required modifications in the core, that were complicated and
required a lot of tuning.

#### Output

We use Kryo to write data to the append only file. When the Java object is passed to Kryo to serialize it, it's not
possible to determine the size of the serialized output upfront. But our data store
requires [the first bytes](#binary-format) to contain the size of the record. So, we needed the record size to be at
most as large as the buffer size. This requirement is hard to enforce since there is no easy way on how to control the
size of the Java objects we need to serialize. Therefore, we needed to add support
for [chained records](#chained-records) (unfortunately due to other requirements on
the [storage format](#binary-format), we couldn't use the `OutputChunked` implementation). We needed to hook into
the `Output` internals to capture the `require` call that was called by the original implementation to check whether
there is enough space left in the buffer before writing new content. When there was no space left (excluding the space
needed for our record tail information - such as record checksums), we needed to issue a chained record finalization and
create a new record on the fly.

One of the problems we encountered is in the buffer compaction process. Because the buffer is reused, the `Output` might
start writing data not from the start of the buffer, but from any offset in that buffer. When there is a lack of space
left, the `Output` first tries to "compact" the buffer, which means moving the active data starting at some offset and
ending close to the end of the buffer to the beginning of the buffer. This move might free enough space at the end of
the buffer to fit the requested data. Correctly tweaking a part of this logic was a hard task, because it was vulnerable
to off-by-one errors. And the errors could be found only during deserialization that involved more [tweaking](#input)
and brought a lot of other, different errors by itself.

Another problem was the serialization of UTF-8 Strings. Because of UTF-8 characteristics and the way Kryo serializes
them, it cannot say upfront how many bytes it would need, and writes the String byte by byte. If the String is long, and
we run into the end of the buffer, we'd need to split the record into multiple [chained records](#chained-records), but
by doing so, we'd also have the String split unnaturally between two records, and we wouldn't be able to persuade Kryo
to deserialize it properly. So, we need to remember the offset in the buffer when the String is starting to be
serialized and when there is not enough space, revert (jump back in the buffer) the entire String serialization, finish
the current record, rewrite the already written part to the next record and then resume writing of the rest of the
String.

#### Input

Overriding the Kryo `Input` was even more difficult. We need to compute the checksum from the part of the record and
also react to the end of the record to finalize the current one and move to the next record in the file (properly
handling the header part).

In order to do so, many original `Input` methods needed to be overridden - mainly the `require` and `optional` ones that
are called when the current data in the buffer has been fully processed. Also, as we found out that in many situations,
the `require` and `optional` methods are not called by the Kryo implementation before reading the bytes, but we need
this check to happen early, so that we can detect the end of the record situation.

Tweaking the Kryo `Input` was a hard task - the checksum detected the problem in every single byte and the behavior of
Kryo was revealed gradually. When we got rid of most of the errors and only a few rare ones were left, we needed to
examine the problems in the binary contents of a multi-gigabyte file, because it was the only way to encounter the
problem. The problem was hard to simulate in artificially written tests.

### Roaring bitmap

Honestly, RoaringBitmap is a fabulous piece of software performance wise. They replaced our original implementation and
brought us two orders of magnitude speed up. We really are building on the shoulders of giants here.

#### Immutability

There are, however, certain differences between our immutability approach and the one, that the RoaringBitmap uses.
RoaringBitmap is, by design, a mutable data structure. There is an interface, `ImmutableRoaringBitmap`, but that only
represents an interface with a limited API that doesn't contain update operations - a method that "creates" immutable
versions of RoaringBitmaps looks like this:

``` java

public class MutableRoaringBitmap extends ImmutableRoaringBitmap … shortened … {

 public ImmutableRoaringBitmap toImmutableRoaringBitmap() {

    return this;

  }

  … rest of the class is omitted …

}

```

If a pointer to the original RoaringBitmap leaks, the mutation can still occur and since the RoaringBitmap is not thread
safe, it manifests the concurrent related problems by producing weird results (there is no such safe net as
ConcurrentModificationException).

When we work with RoaringBitmap, we need to pay extra attention to return only the immutable interface of
the `TransactionalBitmap` and when an updated version is needed, we need to clone the original RoaringBitmap and apply
the modifications there. Unfortunately, the clone creates a full identical copy of the original RoaringBitmap - even if
we only need to update a single number. For our style of use, it would be much better if the RoaringBitmap could
exchange only a few internal containers (chunks) and reuse the majority of others which don't get touched.

#### Missing partial evaluation

All [boolean operations](#boolean-algebra) with RoaringBitmap are designed to fully process and combine the input
bitmaps. There is no possibility for an iterative approach that may lead to computational savings in case only the head
of the results is required.

The first evitaDB conceptual spike implementation modeled the formula computational tree
from [iterators](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Iterator.html). This allowed us
to compute only those results that were actually needed - if the client requested the first page of 20 elements, we may
have interrupted the iteration/calculation at the moment the 20 entities were found and never pay CPU ticks for
calculating the rest.

The idea with iterators seemed just right, but in reality, it didn't bring expected results. The problem with the
iterator approach is that there are too many random memory reads necessary (so it's hard to get an advantage of CPU
caches) and the logic is too complex for [JIT](https://en.wikipedia.org/wiki/Just-in-time_compilation) to optimize - all
for computing a single element in the result.

Next problem with this approach is that the most complex queries in e-commerce include sorting and sorting inherently
requires calculation of all filtered elements - even the entity with the highest primary key (i.e. the last entity
primary key in the filtering result bitmap) may come out as the first element in the result sorted by a different
property. In this case, calculating the entire result in one pass is more effective than an iterative approach.

Anyway, the room for optimization of queries requesting non-sorted limited result count still remains. The RoaringBitmap
could provide support similar to `BatchIterator` that would allow boolean operations iteratively on each internal
container (chunk) that could keep up to 2&lt;sup><sup>16</sup>&lt;/sup> integers. In this case, we could finish the
computation prematurely when the requested element count is resolved and avoid unnecessary calculations for the tail,
that would be thrown out. This approach is currently not possible and would require major interventions in the source
code and API of the RoaringBitmaps (that's why we didn't even open the issue in the RoaringBitmaps GitHub repository).

#### Long bitmaps memory exhaustion

Before we "invented" the [internal price IDs](#internal-price-identification) we worked with a composed ID that
consisted of the entity primary key and the external price ID. Because both of them were integers we needed to combine
them in a long type so that the RoaringBitmap could compute the result for us.

This approach had three major disadvantages:

* the memory requirements went up more than twice for data sets with high price cardinality (we needed 24GB of RAM for
the same data set that we now fit into 8GB, but there were also other optimizations besides this one)
* the RoaringBitmap performance went down considerably
* working with the long type required a lot of duplication of the data structures we worked with - because we stick to
the primitive types, many classes (and tests) needed to be duplicated just for changing the primitive type (generics
are not usable for primitive types in Java)

The first problem is to be expected, the second one is not so obvious. The RoaringBitmap works best when the numbers are
close one to the other and when we composed the long from the integers that were "close to one another" in their
"namespace", we created longs that were really distant one from another. The RoaringBitmap couldn't shine with such a
series of numbers. Introducing the internal price IDs resolved both of these problems.

### Performance degradation repeated id translations

The pricing computation was one of the hardest parts in the query processing. The formula calculation tree might get
pretty complex in this part and for the proper price inner record handling, we have to take the inner record ID
aggregation into account. First versions of this calculation worked with following multiple IDs translation mechanism:

```

priceId -> innerRecordId -> entityPrimaryKey

```

Which took many CPU cycles and brought non-essential complexity to the computation. We underwent a number of rounds of
cutting the computational tree to a bare minimum.

### Tuning transactional memory

Transactions and the transactional memory took a lot of time to tune. The problems in the transactional structures
cannot be anticipated, and must be tested by a
generational [random approach](https://en.wikipedia.org/wiki/Random_testing) (where random mutations build up on the
structures created by a previous generation of random mutations) and verified against a solid implementation based on
simple Java data structures. The testing must be done both for low level data structures and for high level indexes that
use them. The errors that arise from [sheep counting](#preventing-update-loss) have haunted us throughout the
implementation. In addition, we were aware of the importance of correct work with transactional data structure, because
even a small error cumulatively led to a big divergence in the data.

All generational random tests follow a similar structure. First we create a random instance with a seed, that is
remembered. Then, we create a random data set to start with using that random source. The tested transactional data
structure and the reference "stable" data structure is fed with that initial random data. Then, in an infinite loop, we
repeat this sequence:

1. randomly select one of the available operations on the transactional data structure
2. apply the operation with random inputs both on the transactional data structure and the reference Java data structure
3. repeat steps 1 and 2 random number of times
4. commit the transaction
5. verify that the committed data structure and Java data structure remain equivalent
6. create new empty instances of both data structures and feed them with products from step 5. and repeat the entire
iteration

We also maintain a StringBuilder where we print the source code corresponding to the described process using generated
random values. The StringBuilder is emptied at step 5. and starts from the beginning. This allows us in case of an error
to easily reproduce the problem. We could capture the contents of the StringBuilder and create a new JUnit test case
based on the source in it and have the problem reproduced.

The rollback doesn't need to be tested because it involves only dropping the change set that is held in a separate layer
over the immutable data structure. Because the change layer is isolated, the rollback never represented a source of any
error.

## Opportunities for improvement

Well, there are way too many of them - more than we know of and that we mention in the following paragraphs. I,
personally, am more than 20 years in the business, and I'm still learning a lot and know too little.

### Slow formulas improvement

We measured the performance of all formulas in the `FormulaCostMeasurement` performance test, and therefore we know what
kind of formula implementations are slow.

#### Join / disentangle formulas

[Join](#join-formula) and [disentangle](#disentangle-formula) formulas are not efficient - they are always used together
in a composition, so it may be beneficial to merge them together in some way. Or maybe find a different algorithm that
will deliver the same results. The computation is not so complicated - it's similar to
a [disjunction (OR)](#conjunction-and-disjunction-or) operation, but it maintains duplicates in the result array, which
is eliminated by additional processing. We are almost sure, that there is a better way to calculate this, but it hasn't
come to our mind yet.

The join / disentangle formulas are used to compare the range queries against entity data that might have multiple non
overlapping ranges. We need to evaluate whether at least one range of the entity meets our constraint, and discard the
other ranges that don't make sense for the query. Maybe there is a more clever approach for this kind of problem than
our join / disentangle combination.

The current implementation works, but it's not as fast as we would like it to be. Our advantage is a cache that captures
the calculations of repeated range queries and dilutes the negative impact of these formulas.

#### Price termination / translation formulas

There is a lot of logic implemented in the price related formulas and this means that there might be a lot of room for
improvement. The early concepts of evitaDB worked with priorities set directly to prices during indexing. This allowed
for the preparation of better indexes for querying but also complicated changes in the price list priorities that were
the main source of the price priorities in the first place. A single change in price list priority led to tens of
thousands updates of prices in indexes, and we realized that this approach is unmanageable. That's why the priority of
the prices is controlled by the order of price list names mentioned in the input filtering constraint now.

The price calculation logic (except for the "none" price inner record handling mode) is quite complex and
hard to optimize. In addition to an output result of the entity primary keys, we need to filter out associated price
records, so the costs are counted twice.

We've invested a lot of time into the implementation of `PriceRecordIterator#forEachPriceOfEntity`
and `PriceListAndCurrencyPriceIndex#getPriceRecords`, but there may be better alternatives. We stick to primitive binary
searches with some sub-optimizations to narrow down the scope for the binary search, but there are alternatives such
as [merge sort join](https://en.wikipedia.org/wiki/Sort-merge_join), but that approach is not easily combinable
with `BatchIterator` available from the RoaringBitmaps (we would have to materialize all compressed integers from the
RoaringBitmap into the array first to do that - which may cost a lot of CPU cycles and memory).

What we do know is that each optimization in these formulas leads to huge performance gains in overall performance on
datasets with high price cardinalities.

### Roaring bitmap range indexes

RoaringBitmap offers their solution to [range queries](https://richardstartin.github.io/posts/range-bitmap-index) that
might be an order of magnitude better than ours. This part should be investigated and extracted to replace our naive
histogram [disjunction (OR)](#conjunction-and-disjunction-or) operations on
the [inverted index records](#inverted-index) that are the more expensive to calculate the more distinct values the
histogram has.

The change is not trivial since it includes changes in indexes, their transactional memory support and their
serialization and deserialization. That's why we did not include RangeBitmap in this version of evitaDB, but this could
bring a lot of calculation saving in the future.

### Roaring bitmaps binary search / mask extension

We use a masking technique for filtering out records from the pre-sorted array that are not part of the filtered result
set. We need to hold extra data structures that allow us to quickly find out the positions of the records in the
pre-sorted array that would be completely unnecessary if RoaringBitmap supported returning indexes of values matching
the boolean disjunction (AND) operation.

Currently, the RoaringBitmaps can perform conjunction operation with following output:

```

Bitmap A: [1, 3, 7, 9, 12, 15, 18, 20]

Bitmap B: [2, 3, 8, 9, 11, 16, 18, 21]

Result: [3, 9, 18]

```

It would help us greatly if it could return only indexes of the matching records from the first bitmap like this:

```

Result: [1, 3, 6]

```

This would allow us to directly look for indexes in the pre-sorted array without maintaining another useless array data
structure.

### Vectorization

The Java compiler vectorizes some code on its own but the vectorization cannot be enforced and can be easily
broken (in the sense of compiling to non-vectorized code instead) by subtle changes in the code. There
is [JEP 417](https://openjdk.org/jeps/417), which brings direct vectorization support to the Java ecosystem that allows
developers to directly write vectorized algorithms in Java which cannot be downgraded by the Java compiler.

The direct vectorization support might bring additional performance improvements to the RoaringBitmap that we use,
and we could also find new ways for implementing certain formulas in a vectorized approach. The [join](#join-formula)
and [disentangle](#disentangle-formula) formulas may be the first of them due to their simple task.

### Project Valhalla and Lilliput

Project [Valhalla](https://openjdk.org/projects/valhalla/) is another Java ecosystem enhancement that could greatly
benefit evitaDB. We already use a few [record](https://docs.oracle.com/en/java/javase/18/language/records.html) classes
in evitaDB and primarily the [price record](#price-record) or [entity prices](#entity-prices) would benefit a lot if the
record could become a primitive type itself. There is another initiative and that's
project [Lilliput](https://openjdk.org/projects/lilliput/). Project [Lilliput](https://openjdk.org/projects/lilliput/)
aims to decrease the costs of object headers in JDK. Both of these projects - if successful - might bring a lot of
memory savings to the in-memory based evitaDB database.

The amount of RAM memory available to rent from the cloud providers gets higher for the same price each year and when we
factor in that the mandatory requirements of the Java ecosystem gets lower over the time, the costs of in-memory Java
databases might become bearable even for large data sets.

### Fulltext search

The evitaDB implementation focuses on the relational aspects of the e-commerce data. The approach is more natural for
the Czech e-commerce ecosystem - the birthplace of the evitaDB, but there are a lot of alternatives that focus on
fulltext search or artificial intelligence driven fulltext search, such
as [Algolia](https://www.algolia.com/), [Typesense](https://typesense.org/) or various flavors
of [Lucene](https://lucene.apache.org/) spin-offs, such
as [Elasticsearch](https://www.elastic.co/), [Solr ](https://solr.apache.org/)and others.

We haven't yet stepped into this territory, and we're still evaluating whether the incorporation of the Lucene engine is
a good way for us to go. The Lucene engine has its own requirements for the storage layer and incorporates its own view
on transactions which we would have to align with ours. But it's the state of the art in the field of fulltext search
and many people are comfortable working with it. So, there are pros and cons to consider. Fulltext is a crucial part of
the e-commerce ecosystem, so we will have to incorporate it one way or the other.

Currently, we're working around this problem on the client level (outside evitaDB) by first selecting the records by a
fulltext engine and then passing the set of the selected entity primary keys to an evitaDB query that computes the final
result along with facets, parents and all the necessary information. This integration is, however, far from the ideal
one.

### Personalization

Personalization in the e-commerce sector gains a lot of traction. It's kind of a fashionable thing, but it brings real
earnings at the end of the day. In the terms of the search engine, the personalization only affects
the [sorting phase](#phase-sorting-and-slicing) - all other phases remain the same.

The real inventions and enhancements occur outside the e-commerce database and the database only needs to adapt its
sorting phase to allow a similarity search using vectors computed for a particular user that looks at the data. Elastic
search
incorporates [k-nearest neighbors ordering](https://www.elastic.co/guide/en/elasticsearch/reference/master/knn-search.html)
that can take advantage of the vectors created outside the search engine allowing the results of AI driven analysis
of user behavior to be applied.

This area should be explored more in the future to enable personalization in the evitaDB but was not in the scope of the
current project.

### Graal - native image compilation

The recent developments in the Java ecosystem (thanks to another Czech person named Jaroslav Tulach) allows compiling
the Java source code directly to native binary code. The compiled code bypasses the natural middle-step of byte-code the
Java ecosystem invented years ago and uses the native code of the target platform.

The native compilation offers faster startup time and lower memory footprint than the regular Java byte code with JIT.
We don't know yet how Graal native image compilation would affect the evitaDB performance and memory consumption, but
it's worth trying it.

The startup times might become crucial for the situations when a new replica starts from scratch and tries to catch up
with another node state to take part of the traffic on itself as soon as possible.
