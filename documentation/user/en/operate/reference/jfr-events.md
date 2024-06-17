### Java Flight Recorder (JFR) Events

#### API / GraphQL / Request

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/graphql/metric/event/request/ExecutedEvent.java</SourceClass> GraphQL request executed</dt>
  <dd>Event that is fired when a GraphQL request is executed.</dd>
</dl>

#### API / GraphQL / Schema

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/graphql/metric/event/schema/BuiltEvent.java</SourceClass> GraphQL schema built</dt>
  <dd>Event that is fired when a GraphQL schema is built.</dd>
</dl>

#### API / gRPC

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/grpc/metric/event/ProcedureCalledEvent.java</SourceClass> gRPC procedure called</dt>
  <dd>Event that is fired when a gRPC procedure is called.</dd>
</dl>

#### Cache

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/cache/AnteroomRecordStatisticsUpdatedEvent.java</SourceClass> Anteroom statistics updated</dt>
  <dd>Event that is fired in regular intervals to update statistics about records waiting in anteroom.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/cache/AnteroomWastedEvent.java</SourceClass> Anteroom wasted</dt>
  <dd>Event that is fired when an entire anteroom contents were thrown away.</dd>
</dl>

#### Query

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/query/EntityEnrichEvent.java</SourceClass> Entity fetched</dt>
  <dd>Event that is fired when an entity is enriched directly.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/query/EntityFetchEvent.java</SourceClass> Entity fetched</dt>
  <dd>Event that is fired when an entity is fetched directly.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/query/FinishedEvent.java</SourceClass> Catalog finished</dt>
  <dd>Event that is fired when a query is finished.</dd>
</dl>

#### Session

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/session/ClosedEvent.java</SourceClass> Session closed</dt>
  <dd>Event that is fired when a session is closed.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/session/KilledEvent.java</SourceClass> Session killed</dt>
  <dd>Event that is fired when a session is killed due to timeout.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/session/OpenedEvent.java</SourceClass> Session opened</dt>
  <dd>Event that is fired when a session is started.</dd>
</dl>

#### Storage

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/CatalogStatisticsEvent.java</SourceClass> Catalog flushed</dt>
  <dd>Event that is fired when a new catalog version is flushed.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/DataFileCompactEvent.java</SourceClass> OffsetIndex compaction</dt>
  <dd>Event that is fired when an OffsetIndex file is compacted.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/EvitaDBCompositionChangedEvent.java</SourceClass> Evita composition changed</dt>
  <dd>Event that is fired when evitaDB composition changes.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/ObservableOutputChangeEvent.java</SourceClass> ObservableOutput buffers</dt>
  <dd>Event that is fired when an ObservableOutput buffer count is changed.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/OffsetIndexFlushEvent.java</SourceClass> OffsetIndex flushed to disk</dt>
  <dd>Event that is fired when an OffsetIndex file is flushed.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/OffsetIndexHistoryKeptEvent.java</SourceClass> OffsetIndex last record kept</dt>
  <dd>Event that is fired when history data kept in memory change.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/OffsetIndexNonFlushedEvent.java</SourceClass> OffsetIndex non-flushed records</dt>
  <dd>Event that is fired when non flushed record count changes in offset index.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/OffsetIndexRecordTypeCountChangedEvent.java</SourceClass> OffsetIndex record type count changed</dt>
  <dd>Event that is fired when number of records of a particular type in OffsetIndex file changes.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/ReadOnlyHandleClosedEvent.java</SourceClass> File read handles closed</dt>
  <dd>Event that is fired when a file read handle is closed.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/ReadOnlyHandleOpenedEvent.java</SourceClass> File read handles opened</dt>
  <dd>Event that is fired when a new file read handle is opened.</dd>
</dl>

#### System

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/BackgroundTaskFinishedEvent.java</SourceClass> Background task finished</dt>
  <dd>Event that is fired when a background task is finished.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/BackgroundTaskStartedEvent.java</SourceClass> Background task started</dt>
  <dd>Event that is fired when a background task is started.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/EvitaStartedEvent.java</SourceClass> Evita started</dt>
  <dd>Event that is fired when evitaDB instance is started.</dd>
</dl>

#### Transaction

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/CatalogGoesLiveEvent.java</SourceClass> Catalog goes live</dt>
  <dd>Event that is fired when a catalog goes live (transactional).</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/IsolatedWalFileClosedEvent.java</SourceClass> Isolated WAL file closed</dt>
  <dd>Event that is fired when a file for isolated WAL storage is closed and deleted.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/IsolatedWalFileOpenedEvent.java</SourceClass> Isolated WAL file opened</dt>
  <dd>Event that is fired when new file is opened for isolated WAL storage.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/NewCatalogVersionPropagatedEvent.java</SourceClass> New catalog version propagated</dt>
  <dd>Event that is fired when a new catalog version is propagated shared view.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/OffHeapMemoryAllocationChangeEvent.java</SourceClass> Off-heap memory allocation change</dt>
  <dd>Event that is fired when off-heap memory allocation changes.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionAcceptedEvent.java</SourceClass> Transaction accepted</dt>
  <dd>Event that is fired when a transaction passed conflict resolution stage.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionAppendedToWalEvent.java</SourceClass> Transaction appended to WAL</dt>
  <dd>Event that is fired when a transaction passed conflict resolution stage.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionFinishedEvent.java</SourceClass> Transaction finished</dt>
  <dd>Event that is fired when a transaction is finished either by commit or rollback and corresponding session is closed. This also includes waiting for transaction reaching requested stage of processing.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionIncorporatedToTrunkEvent.java</SourceClass> Transaction incorporated to trunk</dt>
  <dd>Event that is fired when a transaction was incorporated into a shared data structures.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionProcessedEvent.java</SourceClass> Transaction processed and visible</dt>
  <dd>Event that is fired when a transaction reached the shared view.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionQueuedEvent.java</SourceClass> Transaction waiting in queue</dt>
  <dd>Event that is fired in each transaction processing stage to reflect the time transaction waited in the queue before it was picked up for processing.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionStartedEvent.java</SourceClass> Transaction started</dt>
  <dd>Event that is fired when a transaction is started.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/WalCacheSizeChangedEvent.java</SourceClass> WAL cache size changed</dt>
  <dd>Event that is fired when a shared WAL location cache size is changed.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/WalRotationEvent.java</SourceClass> WAL rotated</dt>
  <dd>Event that is fired when a shared WAL is rotated.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/WalStatisticsEvent.java</SourceClass> WAL statistics</dt>
  <dd>Event that is fired when a catalog is loaded and WAL examined.</dd>
</dl>

