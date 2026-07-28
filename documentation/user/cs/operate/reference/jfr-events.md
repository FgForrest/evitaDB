---
translated: 'true'
commit: '29a17914b4069515a48b06254b839c82c19cf440'
---
### Události Java Flight Recorder (JFR)

#### API

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/event/ReadinessEvent.java</SourceClass> Readiness probe</dt>
  <dd>Událost, která je vyvolána, když je readiness probe buď spuštěn klientem, nebo vyvolán na straně serveru.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/event/RequestEvent.java</SourceClass> Request</dt>
  <dd>Událost, která je vyvolána po dokončení požadavku, sleduje počty úspěchů/chyb, časování a velikosti datových přenosů.</dd>
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

#### Dotaz

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/query/EntityEnrichEvent.java</SourceClass> Entita obohacena</dt>
  <dd>Událost, která je vyvolána, když je entita přímo obohacena.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/query/EntityFetchEvent.java</SourceClass> Entita načtena</dt>
  <dd>Událost, která je vyvolána, když je entita přímo načtena.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/query/FinishedEvent.java</SourceClass> Dotaz dokončen</dt>
  <dd>Událost, která je vyvolána, když je dotaz dokončen.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/store/traffic/event/TrafficRecorderSkippedRecordsEvent.java</SourceClass> Traffic recorder přeskočené záznamy</dt>
  <dd>Událost, která hlásí záznamy a relace provozu, které byly přeskočeny nebo vyřazeny, rozdělené podle důvodu.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/store/traffic/event/TrafficRecorderStatisticsEvent.java</SourceClass> Statistiky traffic recorderu</dt>
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

#### Úložiště

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/CatalogCheckpointEvent.java</SourceClass> Katalog zajištěn kontrolním bodem</dt>
  <dd>Událost, která je vyvolána, když jsou datové soubory katalogu zapsány trvale a je vytvořen záznam pro spuštění.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/CatalogStatisticsEvent.java</SourceClass> Katalog vyprázdněn</dt>
  <dd>Událost, která je vyvolána, když je vyprázdněna nová verze katalogu.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/DataFileCompactEvent.java</SourceClass> Komprimace OffsetIndex</dt>
  <dd>Událost, která je vyvolána, když je soubor OffsetIndex zkomprimován.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/ObservableOutputChangeEvent.java</SourceClass> Buffery ObservableOutput</dt>
  <dd>Událost, která je vyvolána, když se změní počet bufferů ObservableOutput.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/OffsetIndexFlushEvent.java</SourceClass> OffsetIndex vyprázdněn na disk</dt>
  <dd>Událost, která je vyvolána, když je soubor OffsetIndex vyprázdněn.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/OffsetIndexHistoryKeptEvent.java</SourceClass> OffsetIndex poslední záznam zachován</dt>
  <dd>Událost vyvolaná při změně dat historie uložených v paměti.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/OffsetIndexNonFlushedEvent.java</SourceClass> OffsetIndex nezapsané záznamy</dt>
  <dd>Událost vyvolaná při změně počtu nezapsaných záznamů v offset indexu.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/OffsetIndexRecordTypeCountChangedEvent.java</SourceClass> OffsetIndex změna počtu typů záznamů</dt>
  <dd>Událost, která je vyvolána, když se změní počet záznamů určitého typu v souboru OffsetIndex.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/ReadOnlyHandleClosedEvent.java</SourceClass> Uzavření read handle souboru</dt>
  <dd>Událost, která je vyvolána, když je uzavřen read handle souboru.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/ReadOnlyHandleOpenedEvent.java</SourceClass> Otevření read handle souboru</dt>
  <dd>Událost, která je vyvolána, když je otevřen nový read handle souboru.</dd>
</dl>

#### Systém

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/BackgroundTaskFinishedEvent.java</SourceClass> Dokončení background úlohy</dt>
  <dd>Událost, která je vyvolána při dokončení background úlohy.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/BackgroundTaskRejectedEvent.java</SourceClass> Zamítnutí background úlohy</dt>
  <dd>Událost vyvolaná při zamítnutí background úlohy z důvodu plných front.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/BackgroundTaskStartedEvent.java</SourceClass> Spuštění background úlohy</dt>
  <dd>Událost, která je vyvolána při spuštění background úlohy.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/BackgroundTaskTimedOutEvent.java</SourceClass> Vypršení času background úlohy</dt>
  <dd>Událost, která je vyvolána, když background úloze vyprší čas a je zrušena.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/EvitaStatisticsEvent.java</SourceClass> Evita spuštěna</dt>
  <dd>Událost, která je vyvolána při spuštění instance evitaDB.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/RequestThreadPoolStatisticsEvent.java</SourceClass> Statistiky vykonavatele požadavků</dt>
  <dd>Událost, která je pravidelně vyvolávána pro sledování statistik vykonavatele požadavků.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/RingBufferStatisticsEvent.java</SourceClass> Statistiky kruhového bufferu</dt>
  <dd>Událost, která pravidelně monitoruje statistiky kruhového bufferu v paměti.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/ScheduledExecutorStatisticsEvent.java</SourceClass> Statistiky plánovaného vykonavatele</dt>
  <dd>Událost, která je pravidelně vyvolávána pro sledování statistik plánovaného vykonavatele.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/TransactionThreadPoolStatisticsEvent.java</SourceClass> Statistiky vykonavatele transakcí</dt>
  <dd>Událost, která je pravidelně vyvolávána pro sledování statistik vykonavatele transakcí.</dd>
</dl>

#### Transakce

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/CatalogGoesLiveEvent.java</SourceClass> Katalog spuštěn</dt>
  <dd>Událost, která je vyvolána, když je katalog spuštěn (stane se transakčním).</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/IsolatedWalFileClosedEvent.java</SourceClass> Izolovaný WAL soubor uzavřen</dt>
  <dd>Událost vyvolaná při uzavření a smazání souboru pro izolované WAL úložiště.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/IsolatedWalFileOpenedEvent.java</SourceClass> Izolovaný WAL soubor otevřen</dt>
  <dd>Událost vyvolaná při otevření nového souboru pro izolované WAL úložiště.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/NewCatalogVersionPropagatedEvent.java</SourceClass> Nová verze katalogu rozšířena</dt>
  <dd>Událost, která je vyvolána, když je nová verze katalogu rozšířena do sdíleného pohledu.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/OffHeapMemoryAllocationChangeEvent.java</SourceClass> Změna alokace off-heap paměti</dt>
  <dd>Událost, která je vyvolána při změně alokace off-heap paměti.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionAcceptedEvent.java</SourceClass> Transakce přijata</dt>
  <dd>Událost vyvolaná, když transakce projde fází řešení konfliktů.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionAppendedToWalEvent.java</SourceClass> Transakce připojena do WAL</dt>
  <dd>Událost vyvolaná, když transakce projde fází řešení konfliktů.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionConflictEvent.java</SourceClass> Konflikt transakce</dt>
  <dd>Událost vyvolaná, když je transakce vrácena zpět, protože její změny byly v konfliktu se současně potvrzenou transakcí podle aktuální politiky řešení konfliktů. Událost je rozčleněna podle použité hrubé politiky, vrstvy schématu, ze které byla vyřešena, a rozsahu konfliktu.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionFinishedEvent.java</SourceClass> Transakce dokončena</dt>
  <dd>Událost vyvolaná, když je transakce dokončena, ať už potvrzením nebo vrácením zpět, a příslušná relace je uzavřena. Zahrnuje také čekání na dosažení požadovaného stavu zpracování transakce.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionIncorporatedToTrunkEvent.java</SourceClass> Transakce začleněna do stromu</dt>
  <dd>Událost vyvolaná, když je transakce zahrnuta do sdílené datové struktury.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionProcessedEvent.java</SourceClass> Transakce zpracována a viditelná</dt>
  <dd>Událost vyvolaná, když transakce dosáhne sdíleného pohledu.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionQueuedEvent.java</SourceClass> Transakce čekající ve frontě</dt>
  <dd>Událost vyvolaná v každé fázi zpracování transakce, která udává, jak dlouho transakce čekala ve frontě před zahájením zpracování.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionStartedEvent.java</SourceClass> Transakce spuštěna</dt>
  <dd>Událost, která je vyvolána při spuštění transakce.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/WalCacheSizeChangedEvent.java</SourceClass> Změna velikosti cache WAL</dt>
  <dd>Událost vyvolaná při změně velikosti cache sdíleného umístění WAL.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/WalRotationEvent.java</SourceClass> WAL rotován</dt>
  <dd>Událost, která je vyvolána při rotaci sdíleného WAL.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/WalStatisticsEvent.java</SourceClass> Statistiky WAL</dt>
  <dd>Událost, která je vyvolána při načtení katalogu a kontrole WAL.</dd>
</dl>