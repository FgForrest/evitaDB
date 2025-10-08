### Java Flight Recorder (JFR) Events

#### API

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/event/ReadinessEvent.java</SourceClass> Readiness probe</dt>
  <dd>Event that is fired when a readiness probe is either executed by client or invoked on the server side.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/event/RequestEvent.java</SourceClass> Request</dt>
  <dd>Event that is fired when a readiness probe is either executed by client or invoked on the server side.</dd>
</dl>

#### API / GraphQL / Instance / Schema

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/graphql/metric/event/instance/BuiltEvent.java</SourceClass> GraphQL instance built</dt>
  <dd>Event that is fired when a GraphQL instance is built.</dd>
</dl>

#### API / gRPC

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/grpc/metric/event/EvitaProcedureCalledEvent.java</SourceClass> gRPC evitaDB procedure called</dt>
  <dd>Event that is fired when evitaDB gRPC procedure is called.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/grpc/metric/event/SessionProcedureCalledEvent.java</SourceClass> gRPC session procedure called</dt>
  <dd>Event that is fired when a session gRPC procedure is called.</dd>
</dl>

#### CDC

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/cdc/ChangeCatalogCaptureStatisticsEvent.java</SourceClass> Overall CDC - catalog statistics</dt>
  <dd>Event that is fired in regular intervals capturing base statistics of CDC.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/cdc/ChangeCatalogCaptureStatisticsPerAreaEvent.java</SourceClass> CDC catalog statistics per area</dt>
  <dd>Event that is fired in regular intervals capturing base statistics of CDC per area.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/cdc/ChangeCatalogCaptureStatisticsPerEntityTypeEvent.java</SourceClass> CDC catalog statistics per entity type</dt>
  <dd>Event that is fired in regular intervals capturing base statistics of CDC per entity type.</dd>
</dl>

#### Cache

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/cache/AnteroomRecordStatisticsUpdatedEvent.java</SourceClass> Anteroom statistics updated</dt>
  <dd>Event that is fired periodically to update statistics about records waiting in the anteroom.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/cache/AnteroomWastedEvent.java</SourceClass> Anteroom wasted</dt>
  <dd>Event that is fired when the entire contents of an anteroom have been discarded.</dd>
</dl>

#### ExternalAPI / GraphQL / Request

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/graphql/metric/event/request/ExecutedEvent.java</SourceClass> GraphQL request executed</dt>
  <dd>Event that is fired when a GraphQL request is executed.</dd>
</dl>

#### ExternalAPI / REST / Instance / Schema

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/rest/metric/event/instance/BuiltEvent.java</SourceClass> REST API instance built</dt>
  <dd>Event that is fired when a REST API instance is built.</dd>
</dl>

#### ExternalAPI / REST / Request

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/rest/metric/event/request/ExecutedEvent.java</SourceClass> REST request executed</dt>
  <dd>Event that is fired when a REST request is executed.</dd>
</dl>

#### Query

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/query/EntityEnrichEvent.java</SourceClass> Entity enriched</dt>
  <dd>Event fired when an entity is directly enriched.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/query/EntityFetchEvent.java</SourceClass> Entity fetched</dt>
  <dd>Event fired when an entity is directly fetched.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/query/FinishedEvent.java</SourceClass> Query finished</dt>
  <dd>Event that is fired when a query is finished.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/store/traffic/event/TrafficRecorderStatisticsEvent.java</SourceClass> Traffic recorder statistics</dt>
  <dd>Event that regularly monitors traffic recorder statistics.</dd>
</dl>

#### Session

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/session/ClosedEvent.java</SourceClass> Session closed</dt>
  <dd>Event that is fired when a session is closed.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/session/KilledEvent.java</SourceClass> Session killed</dt>
  <dd>Event that is fired when a session is terminated due to a timeout.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/session/OpenedEvent.java</SourceClass> Session opened</dt>
  <dd>Event that is fired when a session is started.</dd>
</dl>

#### Storage

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/CatalogStatisticsEvent.java</SourceClass> Catalog flushed</dt>
  <dd>Event that is fired when a new catalog version is flushed.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/DataFileCompactEvent.java</SourceClass> OffsetIndex compaction</dt>
  <dd>Event that is fired when an OffsetIndex file is compacted.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/ObservableOutputChangeEvent.java</SourceClass> ObservableOutput buffers</dt>
  <dd>Event that is fired when an ObservableOutput buffer count changes.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/OffsetIndexFlushEvent.java</SourceClass> OffsetIndex flushed to disk</dt>
  <dd>Event that is fired when an OffsetIndex file is flushed.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/OffsetIndexHistoryKeptEvent.java</SourceClass> OffsetIndex last record kept</dt>
  <dd>Event fired when history data stored in memory changes.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/OffsetIndexNonFlushedEvent.java</SourceClass> OffsetIndex non-flushed records</dt>
  <dd>Event fired when the number of unflushed records in the offset index changes.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/OffsetIndexRecordTypeCountChangedEvent.java</SourceClass> OffsetIndex record type count changed</dt>
  <dd>Event that is raised when the number of records of a certain type in the OffsetIndex file changes.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/ReadOnlyHandleClosedEvent.java</SourceClass> File read handles closed</dt>
  <dd>Event that is fired when a file read handle is closed.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/ReadOnlyHandleOpenedEvent.java</SourceClass> File read handles opened</dt>
  <dd>Event that is fired when a new file read handle is opened.</dd>
</dl>

#### System

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/BackgroundTaskFinishedEvent.java</SourceClass> Background task finished</dt>
  <dd>Event that is fired when a background task is completed.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/BackgroundTaskRejectedEvent.java</SourceClass> Background task rejected</dt>
  <dd>Event raised when a background task is rejected due to full queues.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/BackgroundTaskStartedEvent.java</SourceClass> Background task started</dt>
  <dd>Event that is fired when a background task is started.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/BackgroundTaskTimedOutEvent.java</SourceClass> Background task timed out</dt>
  <dd>Event that is raised when a background task has timed out and has been canceled.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/EvitaStatisticsEvent.java</SourceClass> Evita started</dt>
  <dd>Event that is triggered when the evitaDB instance is started.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/RequestForkJoinPoolStatisticsEvent.java</SourceClass> Request executor statistics</dt>
  <dd>Event that is fired on regular intervals to track request executor statistics.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/ScheduledExecutorStatisticsEvent.java</SourceClass> Scheduled executor statistics</dt>
  <dd>Event that is fired on regular intervals to track scheduled executor statistics.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/TransactionForkJoinPoolStatisticsEvent.java</SourceClass> Transaction executor statistics</dt>
  <dd>Event that is fired on regular intervals to track transaction executor statistics.</dd>
</dl>

#### Transaction

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/CatalogGoesLiveEvent.java</SourceClass> Catalog goes live</dt>
  <dd>Event that is fired when a catalog goes live (becomes transactional).</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/IsolatedWalFileClosedEvent.java</SourceClass> Isolated WAL file closed</dt>
  <dd>Event fired when a file is closed and deleted for isolated WAL storage.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/IsolatedWalFileOpenedEvent.java</SourceClass> Isolated WAL file opened</dt>
  <dd>Event fired when a new file is opened for isolated WAL storage.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/NewCatalogVersionPropagatedEvent.java</SourceClass> New catalog version propagated</dt>
  <dd>Event that is fired when a new catalog version is propagated to a shared view.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/OffHeapMemoryAllocationChangeEvent.java</SourceClass> Off-heap memory allocation change</dt>
  <dd>Event that is fired when the off-heap memory allocation changes.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionAcceptedEvent.java</SourceClass> Transaction accepted</dt>
  <dd>Event fired when a transaction passes the conflict resolution phase.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionAppendedToWalEvent.java</SourceClass> Transaction appended to WAL</dt>
  <dd>Event fired when a transaction passes the conflict resolution phase.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionFinishedEvent.java</SourceClass> Transaction finished</dt>
  <dd>Event fired when a transaction is completed, either by commit or rollback, and the corresponding session is closed. This includes waiting for the transaction to reach the desired state of processing.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionIncorporatedToTrunkEvent.java</SourceClass> Transaction incorporated to trunk</dt>
  <dd>Event fired when a transaction is included in a shared data structure.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionProcessedEvent.java</SourceClass> Transaction processed and visible</dt>
  <dd>Event fired when a transaction reaches the shared view.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionQueuedEvent.java</SourceClass> Transaction waiting in a queue</dt>
  <dd>Event fired at each stage of transaction processing to indicate the amount of time the transaction waited in the queue before being picked up for processing.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionStartedEvent.java</SourceClass> Transaction started</dt>
  <dd>Event that is fired when a transaction is started.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/WalCacheSizeChangedEvent.java</SourceClass> WAL cache size changed</dt>
  <dd>Event fired when the cache size of a shared WAL location is changed.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/WalRotationEvent.java</SourceClass> WAL rotated</dt>
  <dd>Event that is fired when a shared WAL is rotated.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/WalStatisticsEvent.java</SourceClass> WAL statistics</dt>
  <dd>Event that is fired when a catalog is loaded and WAL examined.</dd>
</dl>

