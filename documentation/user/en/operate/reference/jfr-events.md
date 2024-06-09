### Java Flight Recorder (JFR) Events

#### API / gRPC

<dl>
  <dt>gRPC procedure called (<SourceClass>io.evitadb.externalApi.grpc.metric.event.ProcedureCalledEvent</SourceClass>)</dt>
  <dd>Event that is fired when a gRPC procedure is called.</dd>
</dl>

#### Cache

<dl>
  <dt>Anteroom statistics updated (<SourceClass>io.evitadb.core.metric.event.cache.AnteroomRecordStatisticsUpdatedEvent</SourceClass>)</dt>
  <dd>Event that is fired in regular intervals to update statistics about records waiting in anteroom.</dd>
  <dt>Anteroom wasted (<SourceClass>io.evitadb.core.metric.event.cache.AnteroomWastedEvent</SourceClass>)</dt>
  <dd>Event that is fired when an entire anteroom contents were thrown away.</dd>
</dl>

#### Query

<dl>
  <dt>Entity fetched (<SourceClass>io.evitadb.core.metric.event.query.EntityEnrichEvent</SourceClass>)</dt>
  <dd>Event that is fired when an entity is enriched directly.</dd>
  <dt>Entity fetched (<SourceClass>io.evitadb.core.metric.event.query.EntityFetchEvent</SourceClass>)</dt>
  <dd>Event that is fired when an entity is fetched directly.</dd>
  <dt>Catalog finished (<SourceClass>io.evitadb.core.metric.event.query.FinishedEvent</SourceClass>)</dt>
  <dd>Event that is fired when a query is finished.</dd>
</dl>

#### Session

<dl>
  <dt>Session closed (<SourceClass>io.evitadb.core.metric.event.session.ClosedEvent</SourceClass>)</dt>
  <dd>Event that is fired when a session is closed.</dd>
  <dt>Session killed (<SourceClass>io.evitadb.core.metric.event.session.KilledEvent</SourceClass>)</dt>
  <dd>Event that is fired when a session is killed due to timeout.</dd>
  <dt>Session opened (<SourceClass>io.evitadb.core.metric.event.session.OpenedEvent</SourceClass>)</dt>
  <dd>Event that is fired when a session is started.</dd>
</dl>

#### Storage

<dl>
  <dt>Catalog flushed (<SourceClass>io.evitadb.core.metric.event.storage.CatalogStatisticsEvent</SourceClass>)</dt>
  <dd>Event that is fired when a new catalog version is flushed.</dd>
  <dt>OffsetIndex compaction (<SourceClass>io.evitadb.core.metric.event.storage.DataFileCompactEvent</SourceClass>)</dt>
  <dd>Event that is fired when an OffsetIndex file is compacted.</dd>
  <dt>Evita composition changed (<SourceClass>io.evitadb.core.metric.event.storage.EvitaDBCompositionChangedEvent</SourceClass>)</dt>
  <dd>Event that is fired when evitaDB composition changes.</dd>
  <dt>ObservableOutput buffers (<SourceClass>io.evitadb.core.metric.event.storage.ObservableOutputChangeEvent</SourceClass>)</dt>
  <dd>Event that is fired when an ObservableOutput buffer count is changed.</dd>
  <dt>OffsetIndex flushed to disk (<SourceClass>io.evitadb.core.metric.event.storage.OffsetIndexFlushEvent</SourceClass>)</dt>
  <dd>Event that is fired when an OffsetIndex file is flushed.</dd>
  <dt>OffsetIndex last record kept (<SourceClass>io.evitadb.core.metric.event.storage.OffsetIndexHistoryKeptEvent</SourceClass>)</dt>
  <dd>Event that is fired when history data kept in memory change.</dd>
  <dt>OffsetIndex non-flushed records (<SourceClass>io.evitadb.core.metric.event.storage.OffsetIndexNonFlushedEvent</SourceClass>)</dt>
  <dd>Event that is fired when non flushed record count changes in offset index.</dd>
  <dt>OffsetIndex record type count changed (<SourceClass>io.evitadb.core.metric.event.storage.OffsetIndexRecordTypeCountChangedEvent</SourceClass>)</dt>
  <dd>Event that is fired when number of records of a particular type in OffsetIndex file changes.</dd>
  <dt>File read handles closed (<SourceClass>io.evitadb.core.metric.event.storage.ReadOnlyHandleClosedEvent</SourceClass>)</dt>
  <dd>Event that is fired when a file read handle is closed.</dd>
  <dt>File read handles opened (<SourceClass>io.evitadb.core.metric.event.storage.ReadOnlyHandleOpenedEvent</SourceClass>)</dt>
  <dd>Event that is fired when a new file read handle is opened.</dd>
</dl>

#### System

<dl>
  <dt>Background task finished (<SourceClass>io.evitadb.core.metric.event.system.BackgroundTaskFinishedEvent</SourceClass>)</dt>
  <dd>Event that is fired when a background task is finished.</dd>
  <dt>Background task started (<SourceClass>io.evitadb.core.metric.event.system.BackgroundTaskStartedEvent</SourceClass>)</dt>
  <dd>Event that is fired when a background task is started.</dd>
  <dt>Evita started (<SourceClass>io.evitadb.core.metric.event.system.EvitaStartedEvent</SourceClass>)</dt>
  <dd>Event that is fired when evitaDB instance is started.</dd>
</dl>

#### Transaction

<dl>
  <dt>Catalog goes live (<SourceClass>io.evitadb.core.metric.event.transaction.CatalogGoesLiveEvent</SourceClass>)</dt>
  <dd>Event that is fired when a catalog goes live (transactional).</dd>
  <dt>Isolated WAL file closed (<SourceClass>io.evitadb.core.metric.event.transaction.IsolatedWalFileClosedEvent</SourceClass>)</dt>
  <dd>Event that is fired when a file for isolated WAL storage is closed and deleted.</dd>
  <dt>Isolated WAL file opened (<SourceClass>io.evitadb.core.metric.event.transaction.IsolatedWalFileOpenedEvent</SourceClass>)</dt>
  <dd>Event that is fired when new file is opened for isolated WAL storage.</dd>
  <dt>New catalog version propagated (<SourceClass>io.evitadb.core.metric.event.transaction.NewCatalogVersionPropagatedEvent</SourceClass>)</dt>
  <dd>Event that is fired when a new catalog version is propagated shared view.</dd>
  <dt>Off-heap memory allocation change (<SourceClass>io.evitadb.core.metric.event.transaction.OffHeapMemoryAllocationChangeEvent</SourceClass>)</dt>
  <dd>Event that is fired when off-heap memory allocation changes.</dd>
  <dt>Transaction accepted (<SourceClass>io.evitadb.core.metric.event.transaction.TransactionAcceptedEvent</SourceClass>)</dt>
  <dd>Event that is fired when a transaction passed conflict resolution stage.</dd>
  <dt>Transaction appended to WAL (<SourceClass>io.evitadb.core.metric.event.transaction.TransactionAppendedToWalEvent</SourceClass>)</dt>
  <dd>Event that is fired when a transaction passed conflict resolution stage.</dd>
  <dt>Transaction finished (<SourceClass>io.evitadb.core.metric.event.transaction.TransactionFinishedEvent</SourceClass>)</dt>
  <dd>Event that is fired when a transaction is finished either by commit or rollback and corresponding session is closed. This also includes waiting for transaction reaching requested stage of processing.</dd>
  <dt>Transaction incorporated to trunk (<SourceClass>io.evitadb.core.metric.event.transaction.TransactionIncorporatedToTrunkEvent</SourceClass>)</dt>
  <dd>Event that is fired when a transaction was incorporated into a shared data structures.</dd>
  <dt>Transaction processed and visible (<SourceClass>io.evitadb.core.metric.event.transaction.TransactionProcessedEvent</SourceClass>)</dt>
  <dd>Event that is fired when a transaction reached the shared view.</dd>
  <dt>Transaction waiting in queue (<SourceClass>io.evitadb.core.metric.event.transaction.TransactionQueuedEvent</SourceClass>)</dt>
  <dd>Event that is fired in each transaction processing stage to reflect the time transaction waited in the queue before it was picked up for processing.</dd>
  <dt>Transaction started (<SourceClass>io.evitadb.core.metric.event.transaction.TransactionStartedEvent</SourceClass>)</dt>
  <dd>Event that is fired when a transaction is started.</dd>
  <dt>WAL cache size changed (<SourceClass>io.evitadb.core.metric.event.transaction.WalCacheSizeChangedEvent</SourceClass>)</dt>
  <dd>Event that is fired when a shared WAL location cache size is changed.</dd>
  <dt>WAL rotated (<SourceClass>io.evitadb.core.metric.event.transaction.WalRotationEvent</SourceClass>)</dt>
  <dd>Event that is fired when a shared WAL is rotated.</dd>
  <dt>WAL statistics (<SourceClass>io.evitadb.core.metric.event.transaction.WalStatisticsEvent</SourceClass>)</dt>
  <dd>Event that is fired when a catalog is loaded and WAL examined.</dd>
</dl>

