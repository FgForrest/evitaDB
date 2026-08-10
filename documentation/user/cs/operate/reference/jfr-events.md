---
translated: 'true'
commit: ab1f0b542c8221dfa52db009d89f0ff2305e278f
---
### Události Java Flight Recorder (JFR)

#### API

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/event/Http2GoAwayEvent.java</SourceClass> HTTP/2 spojení uzavřeno s GOAWAY</dt>
  <dd>Událost, která je vyvolána, když je HTTP/2 spojení uzavřeno chybovým rámcem GOAWAY, buď odeslaným serverem, nebo přijatým od protistrany.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/event/Http2RstFloodEvent.java</SourceClass> HTTP/2 RST_STREAM záplava</dt>
  <dd>Událost, která je vyvolána, když jedno HTTP/2 spojení odešle více rámců RST_STREAM (zrušený požadavek) v jednom okně, než dovoluje prahová hodnota pro hlášení.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/event/ReadinessEvent.java</SourceClass> Readiness probe</dt>
  <dd>Událost, která je vyvolána, když je readiness probe buď provedena klientem, nebo vyvolána na straně serveru.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/event/RequestEvent.java</SourceClass> Požadavek</dt>
  <dd>Událost, která je vyvolána po dokončení požadavku, sleduje počty úspěchů/chyb, časování a velikosti dat.</dd>
</dl>

#### API / GraphQL / Instance / Schéma

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/graphql/metric/event/instance/BuiltEvent.java</SourceClass> GraphQL instance vytvořena</dt>
  <dd>Událost, která je vyvolána při vytvoření GraphQL instance.</dd>
</dl>

#### API / gRPC

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/grpc/metric/event/EvitaProcedureCalledEvent.java</SourceClass> gRPC evitaDB procedura volána</dt>
  <dd>Událost, která je vyvolána při volání evitaDB gRPC procedury.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/grpc/metric/event/SessionProcedureCalledEvent.java</SourceClass> gRPC session procedura volána</dt>
  <dd>Událost, která je vyvolána při volání session gRPC procedury.</dd>
</dl>

#### CDC

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/cdc/ChangeCatalogCaptureStatisticsEvent.java</SourceClass> Celkové CDC - statistiky katalogu</dt>
  <dd>Událost, která je v pravidelných intervalech vyvolána za účelem zachycení základních statistik CDC.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/cdc/ChangeCatalogCaptureStatisticsPerAreaEvent.java</SourceClass> CDC statistiky katalogu dle oblasti</dt>
  <dd>Událost, která je v pravidelných intervalech vyvolána za účelem zachycení základních statistik CDC dle oblasti.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/cdc/ChangeCatalogCaptureStatisticsPerEntityTypeEvent.java</SourceClass> CDC statistiky katalogu dle typu entity</dt>
  <dd>Událost, která je v pravidelných intervalech vyvolána za účelem zachycení základních statistik CDC dle typu entity.</dd>
</dl>

#### Cache

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/cache/AnteroomRecordStatisticsUpdatedEvent.java</SourceClass> Statistiky čekárny aktualizovány</dt>
  <dd>Událost, která je periodicky vyvolána pro aktualizaci statistik o záznamech čekajících v čekárně.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/cache/AnteroomWastedEvent.java</SourceClass> Obsah čekárny zahozen</dt>
  <dd>Událost, která je vyvolána, když je celý obsah čekárny zahozen.</dd>
</dl>

#### ExternalAPI / GraphQL / Požadavek

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/graphql/metric/event/request/ExecutedEvent.java</SourceClass> GraphQL požadavek vykonán</dt>
  <dd>Událost, která je vyvolána při vykonání GraphQL požadavku.</dd>
</dl>

#### ExternalAPI / REST / Instance / Schéma

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/rest/metric/event/instance/BuiltEvent.java</SourceClass> REST API instance vytvořena</dt>
  <dd>Událost, která je vyvolána při vytvoření REST API instance.</dd>
</dl>

#### ExternalAPI / REST / Požadavek

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/externalApi/rest/metric/event/request/ExecutedEvent.java</SourceClass> REST požadavek vykonán</dt>
  <dd>Událost, která je vyvolána při vykonání REST požadavku.</dd>
</dl>

#### Dotaz

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/query/EntityEnrichEvent.java</SourceClass> Entita obohacena</dt>
  <dd>Událost vyvolaná při přímém obohacení entity.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/query/EntityFetchEvent.java</SourceClass> Entita načtena</dt>
  <dd>Událost vyvolaná při přímém načtení entity.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/query/FinishedEvent.java</SourceClass> Dotaz dokončen</dt>
  <dd>Událost, která je vyvolána při dokončení dotazu.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/store/traffic/event/TrafficRecorderSkippedRecordsEvent.java</SourceClass> Traffic recorder přeskočené záznamy</dt>
  <dd>Událost, která hlásí záznamy a relace provozu, které byly přeskočeny nebo zahozeny, rozdělené podle důvodu.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/store/traffic/event/TrafficRecorderStatisticsEvent.java</SourceClass> Statistiky traffic recorderu</dt>
  <dd>Událost, která pravidelně monitoruje statistiky traffic recorderu.</dd>
</dl>

#### Relace

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/session/ClosedEvent.java</SourceClass> Relace uzavřena</dt>
  <dd>Událost, která je vyvolána při uzavření relace.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/session/KilledEvent.java</SourceClass> Relace ukončena</dt>
  <dd>Událost, která je vyvolána při ukončení relace z důvodu vypršení časového limitu.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/session/OpenedEvent.java</SourceClass> Relace otevřena</dt>
  <dd>Událost, která je vyvolána při zahájení relace.</dd>
</dl>

#### Úložiště

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/CatalogCheckpointEvent.java</SourceClass> Katalog checkpointován</dt>
  <dd>Událost, která je vyvolána, když jsou soubory dat katalogu zajištěny a je zapsán bootstrap záznam.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/CatalogStatisticsEvent.java</SourceClass> Katalog vyprázdněn</dt>
  <dd>Událost, která je vyvolána při vyprázdnění nové verze katalogu.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/DataFileCompactEvent.java</SourceClass> Komprimace OffsetIndex</dt>
  <dd>Událost, která je vyvolána při komprimaci souboru OffsetIndex.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/ObservableOutputChangeEvent.java</SourceClass> Buffery ObservableOutput</dt>
  <dd>Událost, která je vyvolána při změně počtu bufferů ObservableOutput.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/OffsetIndexFlushEvent.java</SourceClass> OffsetIndex vyprázdněn na disk</dt>
  <dd>Událost, která je vyvolána při vyprázdnění souboru OffsetIndex.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/OffsetIndexHistoryKeptEvent.java</SourceClass> OffsetIndex poslední záznam uchován</dt>
  <dd>Událost vyvolaná při změně dat historie uložených v paměti.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/OffsetIndexNonFlushedEvent.java</SourceClass> OffsetIndex nevyprázdněné záznamy</dt>
  <dd>Událost vyvolaná při změně počtu nevyprázdněných záznamů v offset indexu.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/OffsetIndexRecordTypeCountChangedEvent.java</SourceClass> OffsetIndex změna počtu typů záznamů</dt>
  <dd>Událost, která je vyvolána při změně počtu záznamů určitého typu v souboru OffsetIndex.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/ReadOnlyHandleClosedEvent.java</SourceClass> Čtecí handly souborů uzavřeny</dt>
  <dd>Událost, která je vyvolána při uzavření čtecího handle souboru.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/storage/ReadOnlyHandleOpenedEvent.java</SourceClass> Čtecí handly souborů otevřeny</dt>
  <dd>Událost, která je vyvolána při otevření nového čtecího handle souboru.</dd>
</dl>

#### Systém

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/BackgroundTaskFinishedEvent.java</SourceClass> Pozadí úloha dokončena</dt>
  <dd>Událost, která je vyvolána při dokončení úlohy na pozadí.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/BackgroundTaskRejectedEvent.java</SourceClass> Pozadí úloha odmítnuta</dt>
  <dd>Událost vyvolaná při odmítnutí úlohy na pozadí z důvodu plných front.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/BackgroundTaskStartedEvent.java</SourceClass> Pozadí úloha spuštěna</dt>
  <dd>Událost, která je vyvolána při spuštění úlohy na pozadí.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/BackgroundTaskTimedOutEvent.java</SourceClass> Pozadí úloha vypršela</dt>
  <dd>Událost, která je vyvolána, když úloze na pozadí vyprší čas a je zrušena.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/EvitaStatisticsEvent.java</SourceClass> Evita spuštěna</dt>
  <dd>Událost, která je spuštěna při startu instance evitaDB.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/RequestThreadPoolStatisticsEvent.java</SourceClass> Statistiky vykonavatele požadavků</dt>
  <dd>Událost, která je v pravidelných intervalech vyvolána pro sledování statistik vykonavatele požadavků.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/RingBufferStatisticsEvent.java</SourceClass> Statistiky kruhového bufferu</dt>
  <dd>Událost, která pravidelně monitoruje statistiky kruhového bufferu v paměti.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/ScheduledExecutorStatisticsEvent.java</SourceClass> Statistiky plánovaného vykonavatele</dt>
  <dd>Událost, která je v pravidelných intervalech vyvolána pro sledování statistik plánovaného vykonavatele.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/system/TransactionThreadPoolStatisticsEvent.java</SourceClass> Statistiky vykonavatele transakcí</dt>
  <dd>Událost, která je v pravidelných intervalech vyvolána pro sledování statistik vykonavatele transakcí.</dd>
</dl>

#### Transakce

<dl>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/CatalogGoesLiveEvent.java</SourceClass> Katalog spuštěn</dt>
  <dd>Událost, která je vyvolána, když je katalog spuštěn (stává se transakčním).</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/IsolatedWalFileClosedEvent.java</SourceClass> Izolovaný WAL soubor uzavřen</dt>
  <dd>Událost vyvolaná při uzavření a smazání souboru pro izolované WAL úložiště.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/IsolatedWalFileOpenedEvent.java</SourceClass> Izolovaný WAL soubor otevřen</dt>
  <dd>Událost vyvolaná při otevření nového souboru pro izolované WAL úložiště.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/NewCatalogVersionPropagatedEvent.java</SourceClass> Nová verze katalogu propagována</dt>
  <dd>Událost, která je vyvolána, když je nová verze katalogu propagována do sdíleného pohledu.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/OffHeapMemoryAllocationChangeEvent.java</SourceClass> Změna alokace off-heap paměti</dt>
  <dd>Událost, která je vyvolána při změně alokace off-heap paměti.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionAcceptedEvent.java</SourceClass> Transakce přijata</dt>
  <dd>Událost vyvolaná, když transakce projde fází řešení konfliktů.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionAppendedToWalEvent.java</SourceClass> Transakce přidána do WAL</dt>
  <dd>Událost vyvolaná, když transakce projde fází řešení konfliktů.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionConflictEvent.java</SourceClass> Konflikt transakce</dt>
  <dd>Událost vyvolaná, když je transakce vrácena zpět, protože její změny byly v konfliktu s paralelně potvrzenou transakcí dle platné politiky řešení konfliktů. Rozděleno podle hrubé politiky, vrstvy schématu a rozsahu konfliktu.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionFinishedEvent.java</SourceClass> Transakce dokončena</dt>
  <dd>Událost vyvolaná při dokončení transakce, buď potvrzením nebo vrácením zpět, a uzavření příslušné relace. Zahrnuje čekání na dosažení požadovaného stavu zpracování transakce.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionIncorporatedToTrunkEvent.java</SourceClass> Transakce začleněna do stromu</dt>
  <dd>Událost vyvolaná při začlenění transakce do sdílené datové struktury.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionProcessedEvent.java</SourceClass> Transakce zpracována a viditelná</dt>
  <dd>Událost vyvolaná, když transakce dosáhne sdíleného pohledu.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionQueuedEvent.java</SourceClass> Transakce čekající ve frontě</dt>
  <dd>Událost vyvolaná v každé fázi zpracování transakce, která indikuje dobu čekání transakce ve frontě před jejím zpracováním.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/TransactionStartedEvent.java</SourceClass> Transakce zahájena</dt>
  <dd>Událost, která je vyvolána při zahájení transakce.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/WalCacheSizeChangedEvent.java</SourceClass> Změna velikosti WAL cache</dt>
  <dd>Událost vyvolaná při změně velikosti cache sdíleného WAL umístění.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/WalRotationEvent.java</SourceClass> WAL rotován</dt>
  <dd>Událost, která je vyvolána při rotaci sdíleného WAL.</dd>
  <dt><SourceClass>evita_engine/src/main/java/io/evitadb/core/metric/event/transaction/WalStatisticsEvent.java</SourceClass> Statistiky WAL</dt>
  <dd>Událost, která je vyvolána při načtení katalogu a kontrole WAL.</dd>
</dl>