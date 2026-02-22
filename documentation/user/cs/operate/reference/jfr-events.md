---
commit: '0197da1799074ccf582750c7096c21cec0705568'
translated: true
---
### Události Java Flight Recorder (JFR)

#### API

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/event/ReadinessEvent.java</SourceClass> Readiness probe</dt>
  <dd>Událost, která je vyvolána, když je readiness probe buď vykonána klientem, nebo vyvolána na straně serveru.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/event/RequestEvent.java</SourceClass> Request</dt>
  <dd>Událost, která je vyvolána, když je readiness probe buď vykonána klientem, nebo vyvolána na straně serveru.</dd>
</dl>

#### API / GraphQL / Instance / Schema

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/graphql/metric/event/instance/BuiltEvent.java</SourceClass> GraphQL instance built</dt>
  <dd>Událost, která je vyvolána, když je vytvořena instance GraphQL.</dd>
</dl>

#### API / gRPC

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/grpc/metric/event/EvitaProcedureCalledEvent.java</SourceClass> gRPC evitaDB procedure called</dt>
  <dd>Událost, která je vyvolána, když je volána gRPC procedura evitaDB.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/grpc/metric/event/SessionProcedureCalledEvent.java</SourceClass> gRPC session procedure called</dt>
  <dd>Událost, která je vyvolána, když je volána gRPC procedura relace.</dd>
</dl>

#### CDC

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/cdc/ChangeCatalogCaptureStatisticsEvent.java</SourceClass> Overall CDC - catalog statistics</dt>
  <dd>Událost, která je v pravidelných intervalech vyvolána pro zachycení základních statistik CDC.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/cdc/ChangeCatalogCaptureStatisticsPerAreaEvent.java</SourceClass> CDC catalog statistics per area</dt>
  <dd>Událost, která je v pravidelných intervalech vyvolána pro zachycení základních statistik CDC podle oblasti.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/cdc/ChangeCatalogCaptureStatisticsPerEntityTypeEvent.java</SourceClass> CDC catalog statistics per entity type</dt>
  <dd>Událost, která je v pravidelných intervalech vyvolána pro zachycení základních statistik CDC podle typu entity.</dd>
</dl>

#### Cache

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/cache/AnteroomRecordStatisticsUpdatedEvent.java</SourceClass> Anteroom statistics updated</dt>
  <dd>Událost, která je periodicky vyvolána pro aktualizaci statistik o záznamech čekajících v předsálí.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/cache/AnteroomWastedEvent.java</SourceClass> Anteroom wasted</dt>
  <dd>Událost, která je vyvolána, když je celý obsah předsálí zahozen.</dd>
</dl>

#### ExternalAPI / GraphQL / Request

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/graphql/metric/event/request/ExecutedEvent.java</SourceClass> GraphQL request executed</dt>
  <dd>Událost, která je vyvolána, když je vykonán GraphQL požadavek.</dd>
</dl>

#### ExternalAPI / REST / Instance / Schema

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/rest/metric/event/instance/BuiltEvent.java</SourceClass> REST API instance built</dt>
  <dd>Událost, která je vyvolána, když je vytvořena instance REST API.</dd>
</dl>

#### ExternalAPI / REST / Request

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/rest/metric/event/request/ExecutedEvent.java</SourceClass> REST request executed</dt>
  <dd>Událost, která je vyvolána, když je vykonán REST požadavek.</dd>
</dl>

#### Query

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/query/EntityEnrichEvent.java</SourceClass> Entity enriched</dt>
  <dd>Událost vyvolaná při přímém obohacení entity.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/query/EntityFetchEvent.java</SourceClass> Entity fetched</dt>
  <dd>Událost vyvolaná při přímém načtení entity.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/query/FinishedEvent.java</SourceClass> Query finished</dt>
  <dd>Událost, která je vyvolána, když je dotaz dokončen.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/store/traffic/event/TrafficRecorderStatisticsEvent.java</SourceClass> Traffic recorder statistics</dt>
  <dd>Událost, která pravidelně monitoruje statistiky traffic recorderu.</dd>
</dl>

#### Session

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/session/ClosedEvent.java</SourceClass> Session closed</dt>
  <dd>Událost, která je vyvolána, když je relace uzavřena.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/session/KilledEvent.java</SourceClass> Session killed</dt>
  <dd>Událost, která je vyvolána, když je relace ukončena z důvodu vypršení časového limitu.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/session/OpenedEvent.java</SourceClass> Session opened</dt>
  <dd>Událost, která je vyvolána, když je relace zahájena.</dd>
</dl>

#### Storage

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/CatalogStatisticsEvent.java</SourceClass> Catalog flushed</dt>
  <dd>Událost, která je vyvolána, když je nová verze katalogu zapsána na disk.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/DataFileCompactEvent.java</SourceClass> OffsetIndex compaction</dt>
  <dd>Událost, která je vyvolána, když je soubor OffsetIndex zkomprimován.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/ObservableOutputChangeEvent.java</SourceClass> ObservableOutput buffers</dt>
  <dd>Událost, která je vyvolána, když se změní počet bufferů ObservableOutput.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/OffsetIndexFlushEvent.java</SourceClass> OffsetIndex flushed to disk</dt>
  <dd>Událost, která je vyvolána, když je soubor OffsetIndex zapsán na disk.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/OffsetIndexHistoryKeptEvent.java</SourceClass> OffsetIndex last record kept</dt>
  <dd>Událost vyvolaná při změně dat historie uložených v paměti.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/OffsetIndexNonFlushedEvent.java</SourceClass> OffsetIndex non-flushed records</dt>
  <dd>Událost vyvolaná při změně počtu nezapsaných záznamů v offset indexu.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/OffsetIndexRecordTypeCountChangedEvent.java</SourceClass> OffsetIndex record type count changed</dt>
  <dd>Událost, která je vyvolána při změně počtu záznamů určitého typu v souboru OffsetIndex.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/ReadOnlyHandleClosedEvent.java</SourceClass> File read handles closed</dt>
  <dd>Událost, která je vyvolána, když je uzavřen handle pro čtení souboru.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/ReadOnlyHandleOpenedEvent.java</SourceClass> File read handles opened</dt>
  <dd>Událost, která je vyvolána, když je otevřen nový handle pro čtení souboru.</dd>
</dl>

#### System

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/BackgroundTaskFinishedEvent.java</SourceClass> Background task finished</dt>
  <dd>Událost, která je vyvolána, když je dokončen úkol na pozadí.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/BackgroundTaskRejectedEvent.java</SourceClass> Background task rejected</dt>
  <dd>Událost vyvolaná, když je úkol na pozadí odmítnut z důvodu plných front.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/BackgroundTaskStartedEvent.java</SourceClass> Background task started</dt>
  <dd>Událost, která je vyvolána, když je spuštěn úkol na pozadí.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/BackgroundTaskTimedOutEvent.java</SourceClass> Background task timed out</dt>
  <dd>Událost, která je vyvolána, když úkol na pozadí překročí časový limit a je zrušen.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/EvitaStatisticsEvent.java</SourceClass> Evita started</dt>
  <dd>Událost, která je spuštěna při startu instance evitaDB.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/RequestForkJoinPoolStatisticsEvent.java</SourceClass> Request executor statistics</dt>
  <dd>Událost, která je v pravidelných intervalech vyvolána pro sledování statistik vykonavatele požadavků.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/RingBufferStatisticsEvent.java</SourceClass> Ring buffer statistics</dt>
  <dd>Událost, která pravidelně monitoruje statistiky kruhového bufferu v paměti.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/ScheduledExecutorStatisticsEvent.java</SourceClass> Scheduled executor statistics</dt>
  <dd>Událost, která je v pravidelných intervalech vyvolána pro sledování statistik plánovaného vykonavatele.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/TransactionForkJoinPoolStatisticsEvent.java</SourceClass> Transaction executor statistics</dt>
  <dd>Událost, která je v pravidelných intervalech vyvolána pro sledování statistik vykonavatele transakcí.</dd>
</dl>

#### Transaction

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/CatalogGoesLiveEvent.java</SourceClass> Catalog goes live</dt>
  <dd>Událost, která je vyvolána, když katalog přechází do ostrého provozu (stává se transakčním).</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/IsolatedWalFileClosedEvent.java</SourceClass> Isolated WAL file closed</dt>
  <dd>Událost vyvolaná při uzavření a smazání souboru pro izolované WAL úložiště.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/IsolatedWalFileOpenedEvent.java</SourceClass> Isolated WAL file opened</dt>
  <dd>Událost vyvolaná při otevření nového souboru pro izolované WAL úložiště.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/NewCatalogVersionPropagatedEvent.java</SourceClass> New catalog version propagated</dt>
  <dd>Událost, která je vyvolána, když je nová verze katalogu propagována do sdíleného pohledu.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/OffHeapMemoryAllocationChangeEvent.java</SourceClass> Off-heap memory allocation change</dt>
  <dd>Událost, která je vyvolána při změně alokace paměti mimo heap.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionAcceptedEvent.java</SourceClass> Transaction accepted</dt>
  <dd>Událost vyvolaná, když transakce projde fází řešení konfliktů.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionAppendedToWalEvent.java</SourceClass> Transaction appended to WAL</dt>
  <dd>Událost vyvolaná, když transakce projde fází řešení konfliktů.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionFinishedEvent.java</SourceClass> Transaction finished</dt>
  <dd>Událost vyvolaná při dokončení transakce, ať už potvrzením nebo vrácením zpět, a uzavření příslušné relace. Zahrnuje také čekání na dosažení požadovaného stavu zpracování transakce.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionIncorporatedToTrunkEvent.java</SourceClass> Transaction incorporated to trunk</dt>
  <dd>Událost vyvolaná při začlenění transakce do sdílené datové struktury.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionProcessedEvent.java</SourceClass> Transaction processed and visible</dt>
  <dd>Událost vyvolaná, když transakce dosáhne sdíleného pohledu.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionQueuedEvent.java</SourceClass> Transaction waiting in a queue</dt>
  <dd>Událost vyvolaná v každé fázi zpracování transakce, která indikuje, jak dlouho transakce čekala ve frontě před zahájením zpracování.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionStartedEvent.java</SourceClass> Transaction started</dt>
  <dd>Událost, která je vyvolána, když je transakce zahájena.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/WalCacheSizeChangedEvent.java</SourceClass> WAL cache size changed</dt>
  <dd>Událost vyvolaná při změně velikosti cache sdíleného umístění WAL.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/WalRotationEvent.java</SourceClass> WAL rotated</dt>
  <dd>Událost, která je vyvolána, když je sdílený WAL rotován.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/WalStatisticsEvent.java</SourceClass> WAL statistics</dt>
  <dd>Událost, která je vyvolána při načtení katalogu a kontrole WAL.</dd>
</dl>