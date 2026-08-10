---
title: Konfigurace
perex: Tento článek je kompletním průvodcem konfigurací instance evitaDB.
date: '14.7.2024'
author: Ing. Jan Novotný
proofreading: done
translated: 'true'
commit: fd07cee44cf344113bd19e9c9ef7d17f27a13fe2
---
evitaDB server je konfigurován ve formátu YAML a jeho výchozí nastavení je nejlépe popsáno následujícím
kódem:

```yaml
name: evitaDB                                     # [viz konfigurace Name](#name)

server:                                           # [viz konfigurace Server](#server-configuration)
  requestThreadPool:
    minThreadCount: 4
    maxThreadCount: 16
    threadPriority: 5
    queueSize: 100
  transactionThreadPool:
    minThreadCount: 4
    maxThreadCount: 16
    threadPriority: 5
    queueSize: 100
  serviceThreadPool:
    minThreadCount: 4
    maxThreadCount: 16
    threadPriority: 5
    queueSize: 100
  queryTimeoutInMilliseconds: 5s
  transactionTimeoutInMilliseconds: 5m
  closeSessionsAfterSecondsOfInactivity: 60
  dropCollationKeysAfterSecondsOfInactivity: 300
  readOnly: false
  quiet: false
  trafficRecording:
    enabled: false
    sourceQueryTracking: false
    trafficMemoryBufferSizeInBytes: 4MB
    trafficDiskBufferSizeInBytes: 32MB
    exportFileChunkSizeInBytes: 16MB
    trafficSamplingPercentage: 100
    trafficFlushIntervalInMilliseconds: 1m

storage:                                          # [viz konfigurace Storage](#storage-configuration)
  storageDirectory: "./data"
  workDirectory: "/tmp"
  lockTimeoutSeconds: 60
  waitOnCloseSeconds: 60
  outputBufferSize: 4MB
  maxOpenedReadHandles: 80
  syncWrites: true
  computeCRC32C: true
  compress: false
  minimalActiveRecordShare: 0.5
  fileSizeCompactionThresholdBytes: 100MB
  timeTravelEnabled: false
  minCompactionIntervalMilliseconds: 1m
  maxWasteActiveShare: 0.1

export:                                           # [viz konfigurace Export](#export-configuration)
  fileSystem:
    enabled: null
    sizeLimitBytes: 1G
    historyExpirationSeconds: 7d
    directory: "./export"
  s3:
    enabled: null
    sizeLimitBytes: 1G
    historyExpirationSeconds: 7d
    endpoint: null
    bucket: null
    accessKey: null
    secretKey: null
    region: null
    requestTimeoutInMillis: 30s

transaction:                                      # [viz konfigurace Transaction](#transaction-configuration)
  transactionWorkDirectory: /tmp/evita/transaction
  transactionMemoryBufferLimitSizeBytes: 16MB
  transactionMemoryRegionCount: 256
  walFileSizeBytes: 16MB
  walFileCountKept: 8
  waitForTransactionAcceptanceInMillis: 20s
  flushFrequencyInMillis: 10s
  checkpointIntervalInMillis: 1s
  conflictPolicy: ENTITY

cache:                                            # [viz konfigurace Cache](#cache-configuration)
  enabled: false
  reflection: CACHE
  reevaluateEachSeconds: 60
  anteroomRecordCount: 100K
  minimalComplexityThreshold: 10K
  minimalUsageThreshold: 2
  cacheSizeInBytes: null

api:                                              # [viz konfigurace API](#api-configuration)
  workerGroupThreads: 4
  idleTimeoutInMillis: 60K
  requestTimeoutInMillis: 2K  
  maxEntitySizeInBytes: 2MB
  accessLog: false
  headers:
    forwardedUri: ["X-Forwarded-Uri"]
    forwardedFor: ["Forwarded", "X-Forwarded-For", "X-Real-IP"]
    label: ["X-EvitaDB-Label"]
    clientId: ["X-EvitaDB-ClientID"]
    traceParent: ["traceparent"]
  certificate:                                    # [viz konfigurace TLS](#tls-configuration) 
    generateAndUseSelfSigned: true
    folderPath: './evita-server-certificates/'
    custom:
      certificate: null
      privateKey: null
      privateKeyPassword: null
  endpointDefaults:
    enabled: true
    host: ":5555"
    exposeOn: "localhost:5555"
    tlsMode: FORCE_TLS
    keepAlive: true
    mTLS:
      enabled: false
      allowedClientCertificatePaths: []
  endpoints:
    system:                                       # [viz konfigurace System API](#system-api-configuration)
      enabled: null
      host: null
      exposeOn: null
      tlsMode: FORCE_NO_TLS
      keepAlive: null
      mTLS:
        enabled: null
        allowedClientCertificatePaths: null
    graphQL:                                      # [viz konfigurace GraphQL API](#graphql-api-configuration)
      enabled: null
      host: null
      exposeOn: null
      tlsMode: null
      keepAlive: null
      parallelize: true
      mTLS:
        enabled: null
        allowedClientCertificatePaths: null
    rest:                                         # [viz konfigurace REST API](#rest-api-configuration)
      enabled: null
      host: null
      exposeOn: null
      tlsMode: null
      keepAlive: null
      mTLS:
        enabled: null
        allowedClientCertificatePaths: null
    gRPC:                                         # [viz konfigurace gRPC API](#grpc-api-configuration)
      enabled: null
      host: null
      exposeOn: null
      tlsMode: null
      keepAlive: null
      exposeDocsService: false
      mTLS:
        enabled: null
        allowedClientCertificatePaths: null
    lab:                                          # [viz konfigurace evitaLab](#evitalab-configuration)
      enabled: null
      host: null
      exposeOn: null
      tlsMode: null
      keepAlive: null
      gui:
        enabled: true
        readOnly: false    
      mTLS:
        enabled: null
        allowedClientCertificatePaths: null
    observability:                                # [viz konfigurace Observability](#observability-configuration)
      enabled: null
      host: null
      exposeOn: null
      tlsMode: null
      keepAlive: null
      tracing:
        serviceName: evitaDB
        endpoint: null
        protocol: grpc
      allowedEvents: null
      exportedQueryLabels: null
      mTLS:
        enabled: null
        allowedClientCertificatePaths: null
```

<Note type="info">

<NoteTitle toggles="true">

##### Existují nějaké zkratky pro velká čísla?
</NoteTitle>

Ano, existují – můžete použít standardizované zkratky metrického systému pro počty a velikosti (všechny zkratky jsou
**case-sensitive**). Viz následující tabulka:

<Table caption="Formáty čísel">
    <Thead>
        <Tr>
            <Th>Zkratka</Th>
            <Th>Význam</Th>
            <Th>Příklad</Th>
        </Tr>
    </Thead>
    <Tbody>
        <Tr>
            <Td>K</Td>
            <Td>tisíc</Td>
            <Td>1K &rightarrow; 1 000</Td>
        </Tr>
        <Tr>
            <Td>M</Td>
            <Td>milion</Td>
            <Td>1M &rightarrow; 1 000 000</Td>
        </Tr>
        <Tr>
            <Td>G</Td>
            <Td>miliarda</Td>
            <Td>1G &rightarrow; 1 000 000 000</Td>
        </Tr>
        <Tr>
            <Td>T</Td>
            <Td>bilion</Td>
            <Td>1T &rightarrow; 1 000 000 000 000</Td>
        </Tr>
    </Tbody>
</Table>

<Table caption="Formáty velikostí">
    <Thead>
        <Tr>
            <Th>Zkratka</Th>
            <Th>Význam</Th>
            <Th>Příklad</Th>
        </Tr>
    </Thead>
    <Tbody>
        <Tr>
            <Td>KB</Td>
            <Td>kilobajt</Td>
            <Td>1KB &rightarrow; 1 024</Td>
        </Tr>
        <Tr>
            <Td>MB</Td>
            <Td>megabajt</Td>
            <Td>1MB &rightarrow; 1 048 576</Td>
        </Tr>
        <Tr>
            <Td>GB</Td>
            <Td>gigabajt</Td>
            <Td>1GB &rightarrow; 1 073 741 824</Td>
        </Tr>
        <Tr>
            <Td>TB</Td>
            <Td>terabajt</Td>
            <Td>1TB &rightarrow; 1 099 511 627 776</Td>
        </Tr>
    </Tbody>
</Table>

<Table caption="Formáty času">
    <Thead>
        <Tr>
            <Th>Zkratka</Th>
            <Th>Význam</Th>
            <Th>Příklad</Th>
        </Tr>
    </Thead>
    <Tbody>
        <Tr>
            <Td>1s</Td>
            <Td>jedna sekunda</Td>
            <Td>1s &rightarrow; 1 sekunda</Td>
        </Tr>
        <Tr>
            <Td>m</Td>
            <Td>jedna minuta</Td>
            <Td>1m &rightarrow; 60 sekund</Td>
        </Tr>
        <Tr>
            <Td>h</Td>
            <Td>jedna hodina</Td>
            <Td>1h &rightarrow; 3 600 sekund</Td>
        </Tr>
        <Tr>
            <Td>d</Td>
            <Td>jeden den</Td>
            <Td>1d &rightarrow; 86 400 sekund</Td>
        </Tr>
        <Tr>
            <Td>d</Td>
            <Td>jeden týden</Td>
            <Td>1w &rightarrow; 604 800 sekund</Td>
        </Tr>
        <Tr>
            <Td>y</Td>
            <Td>jeden rok</Td>
            <Td>1y &rightarrow; 31 556 926 sekund</Td>
        </Tr>
    </Tbody>
</Table>

</Note>

<Note type="info">

<NoteTitle toggles="true">

##### Kde je výchozí konfigurace přiložená k Docker image?
</NoteTitle>

Výchozí konfigurační soubor se nachází v souboru <SourceClass>evita_server/src/main/resources/evita-configuration.yaml</SourceClass>.
Jak můžete vidět, obsahuje proměnné, které umožňují propagaci argumentů z příkazové řádky / proměnných prostředí,
které jsou přítomny při spuštění serveru. Formát použitý v tomto souboru je:

```
${argument_name:defaultValue}
```
</Note>

## Přepisování výchozího nastavení

Existuje několik způsobů, jak přepsat výchozí hodnoty uvedené v souboru <SourceClass>evita_server/src/main/resources/evita-configuration.yaml</SourceClass>
na classpath.

### Proměnné prostředí

Jakoukoli konfigurační vlastnost lze přepsat nastavením proměnné prostředí se speciálně vytvořeným názvem. Název
proměnné lze vypočítat z proměnné použité ve výchozím konfiguračním souboru, která je vždy sestavena z
cesty k vlastnosti v konfiguračním souboru. Výpočet spočívá ve změně názvu proměnné na velká písmena,
nahrazení všech teček podtržítky a přidání prefixu `EVITADB_`. Například vlastnost `server.requestThreadPool.minThreadCount`
lze přepsat nastavením proměnné prostředí `EVITADB_SERVER_REQUESTTHREADPOOL_MINTHREADCOUNT`.

### Argumenty příkazové řádky

Jakoukoli konfigurační vlastnost lze také přepsat nastavením argumentu příkazové řádky v následujícím formátu

```shell
java -jar "target/evita-server.jar" "storage.storageDirectory=../data"
```

Argumenty aplikace mají přednost před proměnnými prostředí.

<Note type="info">

<NoteTitle toggles="true">

##### Jak nastavit argumenty aplikace v Docker kontejneru?

</NoteTitle>

Při použití Docker kontejnerů můžete argumenty aplikace nastavit v proměnné prostředí `EVITA_ARGS` – například

```shell
docker run -i --rm --net=host -e EVITA_ARGS="storage.storageDirectory=../data" index.docker.io/evitadb/evitadb:latest
```

</Note>

### Vlastní konfigurační soubor

Nakonec lze konfigurační soubor přepsat zadáním vlastního konfiguračního souboru ve složce konfigurace
určené argumentem aplikace `configDir`. Vlastní konfigurační soubor musí být ve stejném formátu YAML jako
výchozí konfigurace, ale může obsahovat pouze podmnožinu vlastností, které mají být přepsány. Je také možné
definovat více přepisovacích souborů. Soubory jsou aplikovány v abecedním pořadí podle jejich názvů. Pokud si stavíte
vlastní Docker image, můžete použít následující příkaz pro přepsání konfiguračního souboru:

```shell
COPY "your_file.yaml" "$EVITA_CONFIG_DIR"
```

Pokud máte složitější řetězec pipeline, můžete do této složky kopírovat více souborů v různých fázích
pipeline – ale musíte zachovat správné abecední pořadí souborů, aby se přepisování aplikovalo tak, jak chcete.

## Název

Název serveru je unikátní název instance serveru evitaDB a měl by být jedinečný pro každou instanci (prostředí)
instalace evitaDB. Pokud není zadán žádný název a ponechán výchozí `evitaDB`, je automaticky
doplněn o hash hodnotu vypočtenou z názvu hostitele serveru, cesty k hlavnímu adresáři úložiště serveru a
časového razítka vytvoření adresáře úložiště. To je provedeno proto, aby byl zajištěn unikátní název serveru i v případě,
že je server spuštěn vícekrát na stejném stroji. Název serveru se používá v klientech pro rozlišení jednoho serveru
od druhého a pro správné zacházení s unikátními certifikáty serveru.

## Konfigurace serveru

Tato sekce obsahuje obecná nastavení pro server evitaDB. Umožňuje konfigurovat thread pooly, fronty, timeouty:

<dl>
    <dt>requestThreadPool</dt>
    <dd>
        <p>Nastavuje limity pro hlavní thread pool používaný pro obsluhu všech příchozích požadavků. Vlákna z tohoto poolu zpracovávají všechny
        dotazy a aktualizace až do potvrzení/zrušení transakce. Více informací viz [samostatná kapitola](#thread-pool-configuration).</p>
    </dd>
    <dt>transactionThreadPool</dt>
    <dd>
        <p>Nastavuje limity pro thread pool transakcí používaný ke zpracování transakcí při jejich potvrzení. Tj. řešení konfliktů,
        začlenění do hlavní větve a nahrazení sdílených indexů. Více informací viz [samostatná kapitola](#thread-pool-configuration).</p>
    </dd>
    <dt>serviceThreadPool</dt>
    <dd>
        <p>Nastavuje limity pro service thread pool používaný pro servisní úlohy jako údržba, vytváření záloh,
        obnovení záloh apod. Více informací viz [samostatná kapitola](#thread-pool-configuration).</p>
    </dd>
    <dt>queryTimeoutInMilliseconds</dt>
    <dd>
        <p>**Výchozí:** `5s`</p>
        <p>Nastavuje timeout v milisekundách, po jehož uplynutí by měly vlákna provádějící read-only session požadavky vypršet a
        ukončit svou činnost.</p>
    </dd>
    <dt>transactionTimeoutInMilliseconds</dt>
    <dd>
        <p>**Výchozí:** `5m`</p>
        <p>Nastavuje timeout v milisekundách, po jehož uplynutí by měly vlákna provádějící read-write session požadavky vypršet a
        ukončit svou činnost.</p>
    </dd>
    <dt>closeSessionsAfterSecondsOfInactivity</dt>
    <dd>
        <p>**Výchozí:** `60`</p>
        <p>Určuje maximální přípustnou dobu
        <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass> nečinnosti před
        vynuceným uzavřením ze strany serveru.</p>
    </dd>
    <dt>dropCollationKeysAfterSecondsOfInactivity</dt>
    <dd>
        <p>**Výchozí:** `300` (5 minut); `0` ponechá klíče po dobu životnosti procesu</p>
        <p>Určuje, jak dlouho může být uložený collation key bez porovnání, než jej server uvolní. Řazení nebo
        indexování lokalizovaného atributu znamená konzultaci JVM collatoru pro pravidla daného jazyka, což je přibližně o dva
        řády náročnější než porovnání dvou předpočítaných collation klíčů, proto evitaDB tyto klíče pro jednotlivé jazyky ukládá
        do cache. Zátěž, která porovnává téměř každou odlišnou hodnotu v korpusu – hromadný import nebo velká
        transakce nad řaditelným lokalizovaným atributem – tuto cache zaplní a výrazně zrychlí, za cenu
        paměti úměrné počtu odlišných hodnot. Běžný provoz porovnává mnohem menší horkou podmnožinu a nemá důvod
        dále platit za stopu importu; tento timeout omezuje, jak dlouho se nevyužitý zbytek drží.</p>

        <Note type="info">

        <NoteTitle toggles="true">

        ##### Kdy má smysl nastavit?
        </NoteTitle>

        Uvolnění klíčů nestojí hromadný import nic – lokalizovaný import 972 000 článků měl stejnou dobu
        s uvolňováním i bez něj, zatímco uvolnění vrací přibližně 146 MB na jazyk.

        Jediné místo, kde to dříve stálo čas, byla **první zápisová transakce po klidovém období**, která musela znovu vypočítat
        klíče, které byly uvolněny: na katalogu s 640 000 odlišnými hodnotami narostla transakce ze 7,4 s na
        12,9 s. Původně byla retence neomezená právě z tohoto důvodu. Tento náklad vznikal tím, že transakce znovu budovala
        celou strukturu odlišných hodnot svého sort indexu, což už se neděje – insert nyní ukotví pouze svůj vlastní
        bucket hodnot a dotkne se jen několika hodnot – takže omezení retence se stalo lepším výchozím nastavením.

        Nasazení, která řadí podle velmi mála odlišných hodnot, nebo která chtějí držet klíče po celou dobu běhu procesu,
        mohou stále nastavit `0` pro obnovení neomezené retence.
        </Note>

        <p>Poznámka: *velikost* cache – strop, pod kterým tento timeout funguje – je počet slotů na jazyk
        nastavitelný systémovou vlastností `evita.collationKeyCache.size`, nikoli tímto souborem, protože musí být
        známa při načtení třídy cache, tedy dávno před načtením této konfigurace; `0` zde cache zcela vypne. Výchozí hodnota
        je odvozena z maximální velikosti heapu, takže obvykle není třeba ji měnit.</p>
    </dd>
    <dt>readOnly</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Přepíná server evitaDB do režimu pouze pro čtení, kdy nejsou povoleny žádné aktualizace a server poskytuje pouze
           čtení dat katalogů přítomných v datovém adresáři při startu instance serveru.</p>
    </dd>
    <dt>quiet</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Vypíná logování pomocných informačních zpráv (např. informace o startu). Upozorňujeme, že to nevypíná hlavní logování
           zajišťované [Slf4j](https://www.slf4j.org/) logging facade.</p>
         <Note type="warning">
            Toto nastavení by nemělo být použito při běhu více instancí serveru v rámci jedné JVM, protože aktuálně
            není thread-safe.            
        </Note>
    </dd>
</dl>

### Konfigurace thread poolu

<dl>
    <dt>minThreadCount</dt>
    <dd>
        <p>**Výchozí:** `4`</p>
        <p>Definuje minimální počet vláken v hlavním thread poolu evitaDB, vlákna jsou používána pro zpracování dotazů,
        transakčních aktualizací a servisních úloh (údržba, revalidace cache). Hodnota by měla být alespoň rovna
        počtu jader stroje.</p>
    </dd>
    <dt>maxThreadCount</dt>
    <dd>
        <p>**Výchozí:** `16`</p>
        <p>Definuje maximální počet vláken v hlavním thread poolu evitaDB. Hodnota by měla být násobkem hodnoty
        `minThreadCount`.</p>
    </dd>
    <dt>threadPriority</dt>
    <dd>
        <p>**Výchozí:** `5`</p>
        <p>Definuje prioritu vláken vytvářených v poolu (pro budoucí použití).</p> 
    </dd>
    <dt>queueSize</dt>
    <dd>
        <p>**Výchozí:** `100`</p>
        <p>Definuje maximální počet úloh, které se mohou nahromadit ve frontě čekající na volné vlákno z thread poolu,
        které je zpracuje. Úlohy, které tento limit překročí, budou zahazovány (nové požadavky/ostatní úlohy selžou
        s výjimkou).</p>
    </dd>
</dl>

### Konfigurace záznamu provozu

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Pokud je nastaveno na `true`, server zaznamenává veškerý provoz do databáze (všechny katalogy) do jednoho sdíleného
        paměťového a diskového bufferu, který lze volitelně uložit do souboru. Pokud je záznam provozu vypnutý, lze jej
        stále zapnout na vyžádání přes API (ale nebude automaticky zapnut a zaznamenán). Záznam je optimalizován pro nízkou
        režii výkonu, ale neměl by být zapnut na produkčních systémech (proto je výchozí hodnota `false`).</p>
    </dd>
    <dt>sourceQueryTracking</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Pokud je nastaveno na `true`, server bude zaznamenávat dotaz v jeho původní podobě (GraphQL / REST / gRPC) a sledovat
        poddotazy související s původním dotazem. To je užitečné pro ladění a analýzu výkonu, ale není to
        nutné pro přehrávání provozu.</p>
    </dd>
    <dt>trafficMemoryBufferSizeInBytes</dt>
    <dd>
        <p>**Výchozí:** `4MB`</p>
        <p>Nastavuje velikost paměťového bufferu v bajtech používaného pro záznam provozu. I když je `enabled` nastaveno na `false`,
        tato vlastnost se použije, pokud je záznam provozu vyžádán. Tato vlastnost ovlivňuje počet
        paralelních session, které jsou zaznamenávány. Všechny požadavky v rámci jedné session musí být nejprve shromážděny v tomto
        paměťovém bufferu, než jsou sekvenčně uloženy do diskového bufferu.</p> 
    </dd>
    <dt>trafficDiskBufferSizeInBytes</dt>
    <dd>
        <p>**Výchozí:** `32MB`</p>
        <p>Nastavuje velikost diskového bufferu v bajtech používaného pro záznam provozu. I když je `enabled` nastaveno na `false`,
        tato vlastnost bude použita, pokud je záznam provozu vyžádán. Diskový buffer představuje kruhový
        buffer, který je indexován a dostupný k prohlížení v rozhraní evitaLab. Čím větší buffer, tím více
        historických dat může uchovávat.</p>
    </dd>
    <dt>exportFileChunkSizeInBytes</dt>
    <dd>
        <p>**Výchozí:** `16MB`</p>
        <p>Nastavuje velikost chunku exportovaného souboru v bajtech. Soubor je při exportu obsahu záznamu provozu rozdělen
        na chunk o této velikosti. Chunky jsou pak komprimovány a ukládány do exportního adresáře.</p>
    </dd>
    <dt>trafficSamplingPercentage</dt>
    <dd>
        <p>**Výchozí:** `100`</p>
        <p>Určuje procento provozu, které má být zachyceno. Hodnota je mezi 0 a 100 – nula znamená, že není zachycen žádný
           provoz (ekvivalentní `enabled: false`) a 100 znamená, že je snaha zachytit veškerý provoz.</p>
    </dd>
    <dt>trafficFlushIntervalInMilliseconds</dt>
    <dd>
        <p>**Výchozí:** `1m`</p>
        <p>Nastavuje interval v milisekundách, po kterém je buffer provozu zapsán na disk. Pro vývoj
        (tj. nízký provoz, okamžité ladění) může být nastaven na 0. Pro produkci by měl být nastaven na rozumnou
        hodnotu (např. 60000 = minuta).</p>
    </dd>
</dl>

## Konfigurace úložiště

Tato sekce obsahuje možnosti konfigurace pro úložnou vrstvu databáze.

<dl>
    <dt>storageDirectory</dt>
    <dd>
        <p>**Výchozí:** `./data`</p>
        <p>Definuje složku, kam evitaDB ukládá data svých katalogů. Cestu lze zadat relativně k pracovnímu
        adresáři aplikace nebo v absolutní podobě (doporučeno).</p>
    </dd>
    <dt>workDirectory</dt>
    <dd>
        <p>**Výchozí:** Java temp directory (systémová vlastnost `java.io.tmpdir`)</p>
        <p>Definuje složku, kde evitaDB vytváří dočasné infrastrukturní soubory s krátkou životností – maximálně
        po dobu běhu jedné instance evitaDB. Cestu lze zadat relativně k pracovnímu adresáři
        aplikace nebo v absolutní podobě (doporučeno). Ve výchozím nastavení se používá Java temp directory, ale lze ji
        přesměrovat, pokud je temp složka příliš malá nebo nevhodná pro dočasné pracovní soubory.</p>
    </dd>
    <dt>lockTimeoutSeconds</dt>
    <dd>
        <p>**Výchozí:** `60`</p>
        <p>Určuje maximální dobu, po kterou může vlákno čekat na získání exkluzivního WRITE zámku na soubor pro zápis
        dat. Změna této hodnoty by neměla být nutná, pokud je vše v pořádku.</p>
    </dd>
    <dt>waitOnCloseSeconds</dt>
    <dd>
        <p>**Výchozí:** `60`</p>
        <p>Určuje timeout, po který evitaDB čeká na uvolnění read handle na soubor. Pokud není handle uvolněn
        v rámci timeoutu, volající proces dostane výjimku. Změna této hodnoty by neměla být nutná,
        pokud vše funguje správně.</p>
    </dd>
    <dt>outputBufferSize</dt>
    <dd>
        <p>**Výchozí:** `4MB`</p>
        <p>Velikost výstupního bufferu určuje, jak velký buffer je držen v paměti pro výstupní účely. Velikost
        bufferu omezuje maximální velikost jednotlivého záznamu v key/value datovém úložišti.</p>
    </dd>
    <dt>maxOpenedReadHandles</dt>
    <dd>
        <p>**Výchozí:** `počet CPU * 20`</p>
        <p>Definuje maximální počet současně otevřených read handle na soubory.</p>
        <Note type="warning">
            Toto nastavení by mělo být v souladu s nastavením file handle v operačním systému.
            Přečtěte si tyto články pro [Linux](https://www.baeldung.com/linux/limit-file-descriptors) nebo
            [MacOS](https://gist.github.com/tombigel/d503800a282fcadbee14b537735d202c)            
        </Note>
    </dd>
    <dt>syncWrites</dt>
    <dd>
        <p>**Výchozí:** `true`</p>
        <p>Určuje, zda úložná vrstva vynucuje operačnímu systému flush interních bufferů na disk v
        pravidelných "bezpečných bodech" či nikoli. Výchozí je true, aby nedošlo ke ztrátě dat při výpadku napájení.
        Existují situace, kdy vypnutí této funkce může zlepšit výkon a klient může akceptovat riziko
        ztráty dat (např. při automatizovaných testech apod.).</p>
    </dd>
    <dt>computeCRC32C</dt>
    <dd>
        <p>**Výchozí:** `true`</p>
        <p>Určuje, zda se pro zapisované záznamy v key/value store počítají CRC32C kontrolní součty, a také zda
        se kontrolní součet CRC32C ověřuje při čtení záznamu.</p>
        <p>Toto nastavení také určuje **jak write-ahead log přežívá pád**, což znamená, že jeho vypnutí je mnohem
        nákladnější než samotný výpočet kontrolního součtu. Pád může zanechat záznam s *dírou* – chybějícími bajty uprostřed,
        zatímco pozdější bajty se dostaly na zařízení – a přežití této situace vyžaduje buď detekci díry, nebo
        záruku, že nemůže nastat:</p>
        <ul>
            <li>**`true`** – kumulativní CRC32C řetězec WAL detekuje díru a při obnově ořízne log na poslední neporušenou
                transakci. Log tak lze zapisovat **jedním syncem zařízení na batch transakcí**, což umožňuje škálovat
                propustnost commitů s počtem souběžných zapisovatelů.</li>
            <li>**`false`** – nic nepozná díru od platných dat, protože délka souboru se nezmění a záznam
                stále projde, takže poškození by bylo přehráno jako skutečná historie. WAL je proto otevřen s
                `DSYNC`, což synchronizuje **každý jednotlivý zápis** a tím zaručuje, že pád může zanechat jen čistý
                prefix. To je správně, ale stojí přibližně jeden sync zařízení na zápis místo na batch.</li>
        </ul>
        <Note type="warning">
            Důrazně doporučujeme toto nastavení ponechat na `true`. Kromě včasného hlášení potenciálně poškozených záznamů
            umožňuje write-ahead logu dávkovat zápisy na disk – vypnutí této volby tak stojí mnohem více propustnosti zápisu,
            než samotný výpočet kontrolního součtu.
        </Note>
    </dd>
    <dt>compress</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Určuje, zda se data budou komprimovat. Pokud je nastaveno na true, budou všechna data komprimována, ale pouze ta,
        jejichž komprimovaná velikost je menší než původní, budou uložena v komprimované podobě. Nastavení této vlastnosti
        na `true` může zpomalit zápisy (ale ne výrazně) a zvýšit rychlost a propustnost čtení, protože je
        méně pomalých diskových I/O. Aktuálně se používá standardní metoda ZIP/deflate.</p>
    </dd>
    <dt>minimalActiveRecordShare</dt>
    <dd>
        <p>**Výchozí:** `0.5` (při plýtvání nad 50 % se soubor komprimuje)</p>
        <p>Minimální podíl aktivních záznamů v datovém souboru. Pokud je podíl nižší a velikost souboru překročí také
            limit `fileSizeCompactionThresholdBytes`, soubor bude komprimován. To znamená, že nový soubor obsahující pouze
            aktivní záznamy bude zapsán vedle původního souboru.</p>
    </dd>
    <dt>fileSizeCompactionThresholdBytes</dt>
    <dd>
        <p>**Výchozí:** `100MB`</p>
        <p>Minimální velikost souboru pro komprimaci. Pokud je velikost souboru menší, nebude komprimován, i když
            je podíl aktivních záznamů nižší než minimální podíl.</p>
    </dd>
    <dt>timeTravelEnabled</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Pokud je nastaveno na true, datové soubory nejsou po komprimaci ihned odstraněny, ale zůstávají na disku tak dlouho,
        dokud je k dispozici historie ve WAL logu. To umožňuje vytvořit snapshot databáze v libovolném bodě
        historie pokryté WAL logem. Ze snapshotu lze databázi obnovit do přesného bodu v čase se všemi daty,
        která byla v té době k dispozici.</p>
    </dd>
    <dt>minCompactionIntervalMilliseconds</dt>
    <dd>
        <p>**Výchozí:** `1m` (60000 ms)</p>
        <p>Minimální čas v reálném čase, který musí uplynout od poslední komprimace datového souboru, než může být znovu
            komprimován pouze kvůli nízkému `minimalActiveRecordShare`. Komprimovat datový soubor častěji nemá praktický smysl –
            náklady na kompletní přepis souboru výrazně převyšují jakoukoli úsporu. Soubor je komprimován
            nejdříve po tomto intervalu **pokud** jeho podíl aktivních záznamů neklesne pod `maxWasteActiveShare`, v
            takovém případě je komprimován okamžitě bez ohledu na interval. Hodnota `0` bránu zcela vypne,
            což znamená, že ke komprimaci dochází, jakmile je soubor vhodný ke komprimaci
            (`minimalActiveRecordShare` a `fileSizeCompactionThresholdBytes` rozhodují, odpovídá chování před verzí 2026.2).
            Má efekt pouze pokud je `maxWasteActiveShare` nastaveno pod `minimalActiveRecordShare` –
            jinak je interval fakticky nečinný.</p>
    </dd>
    <dt>maxWasteActiveShare</dt>
    <dd>
        <p>**Výchozí:** `0.1` (90 % plýtvání)</p>
        <p>Podíl aktivních záznamů, pod který je komprimace vynucena okamžitě, bez ohledu na
            `minCompactionIntervalMilliseconds`. Toto je "nouzový" strop plýtvání – musí být nastaven nižší než
            `minimalActiveRecordShare`, aby měl `minCompactionIntervalMilliseconds` nějaký efekt. Výchozí hodnota
            `0.1` ponechává 1minutový interval smysluplně aktivní hned po instalaci, místo aby jej přepis vždy přebil.</p>
    </dd>
</dl>

## Konfigurace exportu

Tato sekce obsahuje možnosti konfigurace pro funkci exportu. evitaDB podporuje export dat buď do místního souborového systému, nebo do S3-kompatibilního úložiště. V jeden okamžik může být aktivní pouze jeden exportní backend – pokud je více backendů nastaveno na `enabled: true`, při startu dojde k chybě.

### Konfigurace exportu do souborového systému

Konfigurace pro exportní backend místního souborového systému. Toto je výchozí backend, pokud není explicitně povolen jiný backend.

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Výchozí:** `null` (výchozí je true, pokud není povolen jiný backend)</p>
        <p>Pokud je nastaveno na `true`, povolí exportní backend do místního souborového systému. Pokud jsou oba `fileSystem.enabled` a
        `s3.enabled` nastaveny na `null`, backend souborového systému je použit jako výchozí.</p>
    </dd>
    <dt>sizeLimitBytes</dt>
    <dd>
        <p>**Výchozí:** `1G`</p>
        <p>Určuje maximální celkovou velikost všech exportovaných souborů uložených tímto backendem. Pokud celková velikost
        překročí tento limit, nejstarší soubory jsou odstraněny, dokud celková velikost neklesne pod limit.</p>
    </dd>
    <dt>historyExpirationSeconds</dt>
    <dd>
        <p>**Výchozí:** `7d`</p>
        <p>Určuje maximální stáří exportovaných souborů pro tento backend. Soubory starší než definované stáří budou
        automaticky odstraněny.</p>
    </dd>
    <dt>directory</dt>
    <dd>
        <p>**Výchozí:** `./export`</p>
        <p>Definuje složku, do které evitaDB ukládá exportované soubory. Cestu lze zadat relativně vůči pracovnímu
        adresáři aplikace nebo v absolutní podobě (doporučeno). Soubory jsou automaticky odstraňovány podle limitů
        definovaných v `historyExpirationSeconds` a `sizeLimitBytes`.</p>
    </dd>
</dl>

### Konfigurace exportu do S3

Konfigurace pro exportní backend do S3-kompatibilního úložiště. Vyžaduje modul `evita_export_s3` na classpath.

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Výchozí:** `null` (zakázáno)</p>
        <p>Pokud je nastaveno na `true`, povolí exportní backend do S3-kompatibilního úložiště. Pole `endpoint`, `bucket`,
        `accessKey` a `secretKey` jsou povinná při povolení S3.</p>
    </dd>
    <dt>sizeLimitBytes</dt>
    <dd>
        <p>**Výchozí:** `1G`</p>
        <p>Určuje maximální celkovou velikost všech exportovaných souborů uložených tímto backendem. Pokud celková velikost
        překročí tento limit, nejstarší soubory jsou odstraněny, dokud celková velikost neklesne pod limit.</p>
    </dd>
    <dt>historyExpirationSeconds</dt>
    <dd>
        <p>**Výchozí:** `7d`</p>
        <p>Určuje maximální stáří exportovaných souborů pro tento backend. Soubory starší než definované stáří budou
        automaticky odstraněny.</p>
    </dd>
    <dt>endpoint</dt>
    <dd>
        <p>**Výchozí:** `null`</p>
        <p>URL endpointu S3-kompatibilního úložiště (např. `https://s3.amazonaws.com` pro AWS S3 nebo
        `https://play.min.io` pro MinIO). Povinné při povolení S3.</p>
    </dd>
    <dt>bucket</dt>
    <dd>
        <p>**Výchozí:** `null`</p>
        <p>Název S3 bucketu, do kterého budou exportované soubory ukládány. Povinné při povolení S3.</p>
    </dd>
    <dt>accessKey</dt>
    <dd>
        <p>**Výchozí:** `null`</p>
        <p>Přístupový klíč pro autentizaci do S3. Povinné při povolení S3.</p>
    </dd>
    <dt>secretKey</dt>
    <dd>
        <p>**Výchozí:** `null`</p>
        <p>Tajný klíč pro autentizaci do S3. Povinné při povolení S3.</p>
    </dd>
    <dt>region</dt>
    <dd>
        <p>**Výchozí:** `null`</p>
        <p>AWS region pro S3 bucket (např. `us-east-1`). Volitelné – některé S3-kompatibilní služby
        nemusí region vyžadovat.</p>
    </dd>
    <dt>requestTimeoutInMillis</dt>
    <dd>
        <p>**Výchozí:** `30s`</p>
        <p>Určuje timeout použitý pro všechny externí S3 operace prováděné exportní službou.
        Timeout se používá při čekání na dokončení asynchronních volání MinIO klienta, jako je
        vytvoření bucketu, nahrání objektu, stažení, smazání a čtení metadat. Zvyšte tuto hodnotu, pokud
        váš S3 poskytovatel nebo síť vykazuje vyšší latence.</p>
    </dd>
</dl>

## Konfigurace transakcí

Tato sekce obsahuje možnosti konfigurace pro úložiště databáze určené pro zpracování transakcí.

<dl>
    <dt>transactionWorkDirectory</dt>
    <dd>
        <p>**Výchozí:** `/tmp/evita/transaction`</p>
        <p>Adresář na místním disku, kde Evita vytváří dočasné složky a soubory pro transakční zpracování. 
            Ve výchozím nastavení je použita dočasná složka – ale je vhodné nastavit vlastní adresář, abyste předešli problémům 
            s místem na disku.</p>
    </dd>
    <dt>transactionMemoryBufferLimitSizeBytes</dt>
    <dd>
        <p>**Výchozí:** `16MB`</p>
        <p>Počet bajtů, které jsou alokovány v off-heap paměti pro transakční paměťový buffer. Tento buffer slouží k 
            dočasnému ukládání (izolovaných) transakčních dat před jejich potvrzením do databáze.
            Pokud je buffer plný, transakční data jsou ihned zapsána na disk a zpracování transakce se zpomalí.</p>
    </dd>
    <dt>transactionMemoryRegionCount</dt>
    <dd>
        <p>**Výchozí:** `256`</p>
        <p>Počet částí (slice) bufferu `transactionMemoryBufferLimitSizeBytes`.
            Čím více částí, tím menší jsou a tím vyšší je pravděpodobnost, že buffer bude plný a bude 
            nutné jej zkopírovat na disk.</p>
    </dd>
    <dt>walFileSizeBytes</dt>
    <dd>
        <p>**Výchozí:** `16MB`</p>
        <p>Velikost souboru Write-Ahead Log (WAL) v bajtech před jeho rotací.</p>
    </dd>
    <dt>walFileCountKept</dt>
    <dd>
        <p>**Výchozí:** `8`</p>
        <p>Počet uchovávaných WAL souborů. Zvyšte tuto hodnotu v kombinaci s `walFileSizeBytes`, pokud chcete
            uchovávat delší historii změn.</p>
    </dd>
    <dt>waitForTransactionAcceptanceInMillis</dt>
    <dd>
        <p>**Výchozí:** `20s`</p>
        <p>Maximální čas v milisekundách, po který systém čeká na přijetí zapisovací transakce,
            tj. na zapsání do sdíleného transakčního WAL. Toto období zahrnuje fázi řešení konfliktů
            i připojení do sdíleného WAL souboru. Pokud operace vyprší, celá transakce bude
            vrácena zpět (rollback).</p>
    </dd>
    <dt>flushFrequencyInMillis</dt>
    <dd>
        <p>**Výchozí:** `10s`</p>
        <p>Frekvence flushování transakčních dat na disk při sekvenčním zpracování.
            Pokud databáze zpracuje (malou) transakci velmi rychle, může se rozhodnout zpracovat další transakci před
            flushnutím změn na disk. Pokud klient čeká na `WAIT_FOR_CHANGES_VISIBLE`, může čekat celých
            `flushFrequencyInMillis` milisekund, než dostane odpověď.</p>
    </dd>
    <dt>checkpointIntervalInMillis</dt>
    <dd>
        <p>**Výchozí:** `1s`</p>
        <p>Jak často jsou datové soubory zajištěny (fsync) a je zapsán bootstrap záznam na ně ukazující.
            Zpracování transakcí vždy zapisuje svá data, ale mezi checkpointy se dostanou pouze do page cache operačního
            systému – flush na zařízení je to, co tento interval omezuje. Nastavte na `0` pro checkpoint na konci
            každého kola zpracování transakcí.</p>
        <p>Toto je záměrně jiná kadence než `flushFrequencyInMillis`: ta určuje, kdy se změny stanou
            **viditelnými**, tato určuje, kdy se stanou **trvale uloženými v datových souborech**. Trvalost potvrzeného
            commitu na tom nezávisí – write-ahead log je zdrojem pravdy, a vše zapsané po posledním checkpointu je při restartu
            znovu načteno z WAL. Interval tedy ovlivňuje retenci WAL a čas přehrání při restartu (obojí omezeno intervalem)
            výměnou za propustnost zápisu.</p>
        <p>Zisk je největší u málo zatížených systémů. Checkpoint stojí pevný počet flushů na zařízení
            bez ohledu na počet transakcí, které pokrývá, takže když je málo transakcí, náklady se rozpočítají mezi ně:
            měřeno přibližně na 57 % kola zpracování se dvěma souběžnými zápisy a zanedbatelné se čtyřiašedesáti.
            Zvýšení intervalu pomáhá hlavně v prvním případě; druhý je již ovlivněn jinou zátěží.</p>
        <p>Nastavení nemá žádný efekt, pokud je `storage.syncWrites` nastaveno na `false`, protože pak není co
            flushovat na zařízení.</p>
    </dd>
    <dt>conflictPolicy</dt>
    <dd>
        <p>**Výchozí:** `{ policy: ENTITY }`</p>
        <p>Výchozí engine-wide politika řešení konfliktů používaná k řešení konfliktů s jinými paralelními sezeními během
            potvrzení transakce. Řídí granularitu, na které jsou detekovány a serializovány zápisové konflikty:
            čím jemnější rozsah, tím více mutací lze zpracovat souběžně bez blokování; čím hrubší rozsah,
            tím méně konfliktů je možné, ale za cenu nižší souběžnosti. Výchozí nastavení lze
            přepsat na úrovni katalogu, typu entity a položky schématu (atribut / asociovaná data / reference) – viz
            [detailní popis řešení konfliktů](../deep-dive/transactions.md#1-conflict-resolution) pro celý model.
            Viz sekci [Konfliktní politiky](#conflict-policies) pro popis dostupných politik.</p>
        <p>Hodnota je objekt s povinnou hrubou `policy` (`NONE` / `CATALOG` / `COLLECTION` / `ENTITY`) a
            volitelným seznamem `granularity` upřesňujícím rozsah `ENTITY`. Samotná hodnota bez objektu je akceptována jako zkratka pro
            pouze hrubou politiku. Příklady:</p>
        <ul>
            <li>`{ policy: ENTITY }` (nebo jednoduše `ENTITY`) – výchozí, konflikty detekovány na úrovni entity</li>
            <li>`{ policy: ENTITY, granularity: [ENTITY_ATTRIBUTE, REFERENCE_ATTRIBUTE] }` – konflikty na úrovni entity,
                 upřesněné tak, že zápisy do různých atributů stejné entity nejsou v konfliktu</li>
            <li>`{ policy: NONE }` (nebo jednoduše `NONE`) – žádná detekce konfliktů (vyhrává poslední zápis)</li>
        </ul>
    </dd>
</dl>

## Konfliktní politiky

Konfliktní politiky řídí granularitu, na které jsou v evitaDB detekovány a serializovány zápisové konflikty. Pokud se více
transakcí pokouší současně upravit stejná data, konfliktní politika určuje, zda tyto operace
jsou v konfliktu, nebo mohou proběhnout nezávisle.

EvitaDB odvozuje konfliktní klíč pro každou příchozí zápisovou mutaci. Rozsah tohoto klíče je řízen konfliktní
politikou: čím jemnější rozsah, tím více mutací lze zpracovat souběžně bez blokování; čím hrubší rozsah,
tím méně konfliktů je možné, ale za cenu nižší souběžnosti.

### Dostupné konfliktní politiky

<dl>
    <dt>CATALOG</dt>
    <dd>
        <p>Tato politika generuje konfliktní klíče, které jsou vázány na celý katalog. Každý zápis do katalogu bude
            považován za potenciálně konfliktní s jakýmkoli jiným zápisem do stejného katalogu, což v praxi znamená,
            že nebude povolen žádný souběžný zápis do stejného katalogu.</p>
        <p>**Použití:** Maximální bezpečnost, pokud potřebujete zajistit striktní pořadí všech úprav katalogu,
            za cenu nejnižší souběžnosti.</p>
    </dd>
    <dt>COLLECTION</dt>
    <dd>
        <p>Tato politika generuje konfliktní klíče, které jsou vázány na kolekce v rámci katalogu. Mutace cílené
            na různé kolekce mohou být zpracovány souběžně, zatímco souběžné mutace cílené na stejnou kolekci
            budou v konfliktu.</p>
        <p>**Použití:** Pokud potřebujete zajistit konzistenci v rámci každé kolekce nezávisle, ale zároveň povolit
            souběžné úpravy různých kolekcí.</p>
    </dd>
    <dt>ENTITY</dt>
    <dd>
        <p>**Výchozí politika.** Tato politika generuje konfliktní klíče, které jsou vázány na jednotlivé entity v rámci
            kolekce. Mutace cílené na různé entity mohou být zpracovány souběžně, zatímco souběžné
            mutace cílené na stejnou entitu budou v konfliktu.</p>
        <p>**Použití:** Doporučeno pro většinu aplikací. Poskytuje dobrý kompromis mezi souběžností a bezpečností,
            zajišťuje, že úpravy stejné entity jsou správně serializovány.</p>
    </dd>
    <dt>ENTITY_ATTRIBUTE</dt>
    <dd>
        <p>Tato politika generuje konfliktní klíče, které jsou vázány na konkrétní atributy entit. Souběžné mutace
            cílené na stejný atribut stejné entity budou v konfliktu, zatímco mutace cílené na různé
            atributy, části stejné entity nebo různé entity mohou být zpracovány souběžně.</p>
        <p>**Poznámka:** Tato politika nepokrývá atributy referencí, viz <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/conflict/ConflictPolicy.java</SourceClass> pro tuto oblast.</p>
        <p>**Použití:** Maximální souběžnost, pokud lze různé části stejné entity bezpečně upravovat
            nezávisle (např. současná aktualizace popisu a skladového množství produktu).</p>
    </dd>
    <dt>REFERENCE</dt>
    <dd>
        <p>Tato politika generuje konfliktní klíče, které jsou vázány na konkrétní reference entit. Souběžné mutace
            cílené na stejnou referenci stejné entity budou v konfliktu, zatímco mutace cílené na různé
            reference, části stejné entity nebo různé entity mohou být zpracovány souběžně.</p>
        <p>**Použití:** Pokud potřebujete jemně řídit vztahy mezi entitami a chcete povolit souběžné
            úpravy různých referencí stejné entity.</p>
    </dd>
    <dt>REFERENCE_ATTRIBUTE</dt>
    <dd>
        <p>Tato politika generuje konfliktní klíče, které jsou vázány na konkrétní atributy referencí v rámci entit.
            Souběžné mutace cílené na stejný atribut stejné reference stejné entity budou v konfliktu, zatímco mutace
            cílené na různé atributy, reference, části stejné entity nebo různé entity mohou být zpracovány souběžně.</p>
        <p>**Použití:** Nejjemnější granularita pro atributy referencí, umožňuje maximální souběžnost při úpravách
            různých atributů referencí entity.</p>
    </dd>
    <dt>ASSOCIATED_DATA</dt>
    <dd>
        <p>Tato politika generuje konfliktní klíče, které jsou vázány na asociovaná data entit. Souběžné mutace
            cílené na stejná asociovaná data stejné entity budou v konfliktu, zatímco mutace cílené
            na různá asociovaná data, části stejné entity nebo různé entity mohou být zpracovány souběžně.</p>
        <p>**Použití:** Pokud potřebujete povolit souběžné úpravy různých položek asociovaných dat
            stejné entity.</p>
    </dd>
    <dt>PRICE</dt>
    <dd>
        <p>Tato politika generuje konfliktní klíče, které jsou vázány na ceny entit. Souběžné mutace
            cílené na stejnou cenu stejné entity budou v konfliktu, zatímco mutace cílené na různé
            ceny, části stejné entity nebo různé entity mohou být zpracovány souběžně.</p>
        <p>**Použití:** Pokud potřebujete povolit souběžné úpravy různých cen stejné entity
            (např. nezávislá aktualizace různých ceníků).</p>
    </dd>
    <dt>HIERARCHY</dt>
    <dd>
        <p>Tato politika generuje konfliktní klíče, které jsou vázány na hierarchii entit. Souběžné mutace
            cílené na stejnou pozici v hierarchii stejné entity budou v konfliktu, zatímco mutace
            cílené na různé pozice, části stejné entity nebo různé entity mohou být zpracovány souběžně.</p>
        <p>**Použití:** Pokud potřebujete zajistit konzistenci hierarchických vztahů a zároveň povolit
            souběžné úpravy různých částí hierarchie.</p>
    </dd>
</dl>

### Výběr správné konfliktní politiky

Při výběru konfliktních politik pro vaši aplikaci zvažte:

1. **Požadavky na souběžnost**: Jemnější politiky (jako `ENTITY_ATTRIBUTE`, `REFERENCE_ATTRIBUTE`) umožňují více
   souběžných operací, ale vyžadují pečlivé zvážení závislostí dat.

2. **Požadavky na konzistenci dat**: Hrubší politiky (jako `ENTITY`, `COLLECTION`) poskytují silnější
   záruky konzistence, ale mohou omezit souběžnost.

3. **Vzor použití aplikace**: Pokud vaše aplikace často současně upravuje různé části stejné entity,
   zvažte použití více jemnozrnných politik dohromady.

4. **Kompromis výkon vs. bezpečnost**: Začněte s výchozí politikou `ENTITY` a přejděte na jemnější granularitu
   pouze pokud zjistíte konkrétní úzká místa v souběžnosti.

### Režim poslední zápis vyhrává

Pokud pro konfiguraci `conflictPolicy` zadáte prázdné pole `[]`, nebude prováděna žádná detekce konfliktů. To
znamená, že poslední transakce, která bude potvrzena, přepíše všechny předchozí změny bez jakéhokoliv konfliktu. Tento režim
umožňuje maximální souběžnost, ale měl by být použit pouze tehdy, pokud jste si jisti, že ve vaší aplikaci nemohou
nastat souběžné konfliktní zápisy.

## Konfigurace cache

Cache urychluje odpovědi na zcela nebo částečně identické dotazy. Cache může v některých případech zvýšit
průchodnost systému několikanásobně.

<Note type="warning">
V aktuální verzi doporučujeme cache vypnout, dokud nebude vyřešen [problém #37](https://github.com/FgForrest/evitaDB/issues/37).
</Note>

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Toto nastavení zcela povoluje nebo zakazuje používání cache.</p>
    </dd>
    <dt>reflection</dt>
    <dd>
        <p>**Výchozí:** `CACHE`</p>
        <p>Toto nastavení povoluje nebo zakazuje cachování informací o Java reflexi. Režim `CACHE` je obvykle doporučený,
        pokud nespouštíte nějaký druh testu.</p>
    </dd>
    <dt>reevaluateEachSeconds</dt>
    <dd>
        <p>**Výchozí:** `60`</p>
        <p>Definuje periodu pro přehodnocení adeptů na cache, kteří mají být propagováni do cache nebo odstraněni.
        Přehodnocení může být také spuštěno překročením maximálního povoleného počtu `anteroomRecordCount`, ale nejpozději
        po uplynutí `reevaluateEachSeconds` od posledního přehodnocení (s výjimkou situace, kdy není volné vlákno v 
        thread poolu pro obsluhu tohoto úkolu). Viz [detailní popis procesu cachování](../deep-dive/cache.md).</p>
    </dd>
    <dt>anteroomRecordCount</dt>
    <dd>
        <p>**Výchozí:** `100K`</p>
        <p>Definuje maximální počet záznamů v předsíni cache. Po dosažení tohoto počtu je automaticky spuštěn proces přehodnocení,
        který vede k vyčištění předsíně. Předsíň je také pravidelně čištěna každých `reevaluateEachSeconds`. Viz [detailní popis procesu cachování](../deep-dive/cache.md).</p>
    </dd>
    <dt>minimalComplexityThreshold</dt>
    <dd>
        <p>**Výchozí:** `10K`</p>
        <p>Určuje minimální výpočetní složitost, která musí být dosažena, aby byl výsledek uložen do cache.
        Je to virtuální číslo, takže není žádný návod, jak velké by mělo být. Pokud se cache zaplní
        mnoha výsledky s pochybným využitím, můžete zkusit tento práh zvýšit.</p>
    </dd>
    <dt>minimalUsageThreshold</dt>
    <dd>
        <p>**Výchozí:** `2`</p>
        <p>Určuje minimální počet opakovaných použití vypočteného výsledku před jeho uložením do cache. Pokud se cache
        zaplňuje hodnotami s nízkou úspěšností zásahů, můžete zkusit tento práh zvýšit.</p>
    </dd>
    <dt>cacheSizeInBytes</dt>
    <dd>
        <p>**Výchozí:** `null`, což znamená, že evitaDB použije 25 % volné paměti změřené při startu a načte do ní všechna data</p>
        <p>evitaDB se snaží odhadnout velikost paměti každého cachovaného objektu a zabránit překročení tohoto limitu.</p>

        <Note type="question">

        <NoteTitle toggles="true">

        ##### Jak měříme velikost objektu?
        </NoteTitle>

        Změřit přesné množství paměti, které každý objekt v Javě alokuje, není snadné, a v tuto chvíli jde pouze 
        o náš odhad. Podle našich zkušeností jsou naše odhady nastaveny výše než skutečnost a
        systém se zastaví přibližně na 90 % nastaveného limitu `cacheSizeInBytes` (ale tato zkušenost je založena na OS Linux, architektura x86_64).
        </Note>
    </dd>
</dl>

## Konfigurace API

Tato sekce konfigurace vám umožňuje selektivně povolit, zakázat a upravit specifická API.

<dl>
    <dt>workerGroupThreads</dt>
    <dd>
        <p>**Výchozí:** `počet CPU`</p>
        <p>Definuje počet IO vláken, která bude Armeria používat pro přijímání a odesílání HTTP payload.</p>
    </dd>
    <dt>idleTimeoutInMillis</dt>
    <dd>
        <p>**Výchozí:** `60K`</p>
        <p>Čas, po který může být spojení nečinné, než dojde k jeho ukončení. Nečinné spojení je spojení,
            u kterého nedošlo k přenosu dat během období nečinnosti. Všimněte si, že jde o poměrně hrubý přístup
            a malé hodnoty mohou způsobit problémy u požadavků s dlouhou dobou zpracování. Výchozí hodnota je
            komfortně nad výchozím intervalem keep-alive ping Java driveru (30 s, viz
            [Možnosti připojení](../use/connectors/java.md#connection-options)), takže aktivně pingující klientské
            spojení nebude nikdy ukončeno.</p>
    </dd>
    <dt>requestTimeoutInMillis</dt>
    <dd>
        <p>**Výchozí:** `2K`</p>
        <p>Čas, po který může být spojení nečinné bez zpracování požadavku, než je serverem uzavřeno.</p>
    </dd> 
    <dt>maxEntitySizeInBytes</dt>
    <dd>
        <p>**Výchozí:** `2MB`</p>
        <p>Výchozí maximální velikost entity požadavku. Pokud je tělo entity větší než tento limit, bude při čtení požadavku
            vyhozena výjimka IOException (při prvním čtení u požadavků s pevnou délkou, při načtení příliš velkého množství
            dat u chunkovaných požadavků).</p>
    </dd>
    <dt>accessLog</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Povoluje / zakazuje logování přístupových zpráv pro všechna API.</p>
    </dd> 
</dl>

### Konfigurace hlaviček

Hlavičky obsahují rozumné výchozí hodnoty, ale v některých případech je můžete chtít přepsat (například
hlavička `X-Forwarded-For` je někdy používána proxy servery mezi klientem a serverem).

Běžná konfigurace je v podsekci `headers` v rámci `api`.
Umožňuje nastavit tato nastavení:

<dl>
    <dd>
        <p>Tato sekce obsahuje konfiguraci názvů HTTP hlaviček, které jsou rozpoznávány evitaDB.</p>
        <dl>
            <dt>forwardedUri</dt>
            <dd>
                <p>**Výchozí:** `["X-Forwarded-Uri"]`</p>
                <p>Pole názvů hlaviček, které jsou rozpoznávány jako hlavičky předané URI. Tyto hlavičky se používají, když je evitaDB za proxy, aby bylo možné určit původní URI požadované klientem.</p>
            </dd>
            <dt>forwardedFor</dt>
            <dd>
                <p>**Výchozí:** `["Forwarded", "X-Forwarded-For", "X-Real-IP"]`</p>
                <p>Pole názvů hlaviček, které jsou rozpoznávány jako hlavičky předané IP adresy klienta. Tyto hlavičky se používají, když je evitaDB za proxy, aby bylo možné určit původní IP adresu klienta.</p>
            </dd>
            <dt>label</dt>
            <dd>
                <p>**Výchozí:** `["X-EvitaDB-Label"]`</p>
                <p>Pole názvů hlaviček pro meta štítky, které umožňují nastavit štítky pro záznam provozu prostřednictvím HTTP hlaviček.</p>
            </dd>
            <dt>clientId</dt>
            <dd>
                <p>**Výchozí:** `["X-EvitaDB-ClientID"]`</p>
                <p>Pole názvů hlaviček, které jsou rozpoznávány jako hlavičky identifikátoru klienta. Tyto hlavičky lze použít k identifikaci klientské aplikace, která požadavek provádí.</p>
            </dd>
            <dt>traceParent</dt>
            <dd>
                <p>**Výchozí:** `["traceparent"]`</p>
                <p>Pole názvů hlaviček, které jsou rozpoznávány jako trace parent hlavičky. Tyto hlavičky se používají pro distribuované trasování za účelem korelace požadavků napříč různými službami.</p>
            </dd>
        </dl>
    </dd>
</dl>

### Konfigurace TLS

Podpora TLS je ve výchozím nastavení povolena pro většinu API, ale lze ji individuálně zakázat pro každé API v jeho konfiguraci.
Všimněte si, že pokud nastavíte různé TLS nastavení pro každé API, musí mít každé API svůj vlastní port.

Běžná konfigurace je v podsekci `certificate` v rámci `api`.
Umožňuje nastavit tato nastavení:

<dl>
  <dt>generateAndUseSelfSigned</dt>
  <dd>
    <p>**Výchozí:** `true`</p>
    <p>Pokud je nastaveno na `true`, je při startu serveru automaticky vygenerována self-signed <Term location="/documentation/user/en/operate/tls.md">certifikační autorita</Term>
    <Term location="/documentation/user/en/operate/tls.md">certifikát</Term> a jeho
    <Term location="/documentation/user/en/operate/tls.md">soukromý klíč</Term> a jsou použity pro komunikaci s klienty.</p>
  </dd>
  <dt>folderPath</dt>
  <dd>
    <p>**Výchozí:** podsložka `evita-server-certificates` v pracovním adresáři</p>
    <p>Představuje cestu ke složce, kde jsou uloženy vygenerovaný certifikát autority a jeho soukromý klíč.
    Toto nastavení se používá pouze tehdy, když je `generateAndUseSelfSigned` nastaveno na `true`.</p>
  </dd>
  <dt>custom</dt>
  <dd>
    <p>Tato sekce umožňuje nastavit externě dodaný <Term location="/documentation/user/en/operate/tls.md">certifikát</Term>.
    Používá se pouze tehdy, pokud je `generateAndUseSelfSigned` nastaveno na `false`.</p>
    <p>Sekce vyžaduje tato vnořená nastavení:</p>
      - **`certificate`**: cesta k veřejné části souboru certifikátu (*.crt)
      - **`privateKey`**: cesta k soukromému klíči certifikátu (*.key)
      - **`privateKeyPassword`**: heslo k soukromému klíči

    <Note type="info">

    <NoteTitle toggles="false">
        
    ##### Tip

    </NoteTitle>

      Doporučujeme zadat heslo k soukromému klíči pomocí argumentu příkazové řádky (proměnné prostředí)
      `api.certificate.custom.privateKeyPasssword` a uložit jej do trezoru tajných klíčů CI serveru.
    </Note>

    <Note type="question">

    <NoteTitle toggles="true">
    
    ##### Existuje alternativa k této ruční konfiguraci?

    </NoteTitle>

    Ano, existuje. Můžete použít standardizovaný způsob importu
    <Term location="/documentation/user/en/operate/tls.md">certifikační autority</Term>
    <Term location="/documentation/user/en/operate/tls.md">certifikátu</Term> do Java trust store. Tento postup je
    detailně popsán v [tomto článku](https://medium.com/expedia-group-tech/how-to-import-public-certificates-into-javas-truststore-from-a-browser-a35e49a806dc).

    </Note>
  </dd>
</dl>

Pokud není nakonfigurován žádný vlastní certifikát, server se nespustí a bude vyhozena výjimka. Server neposkytuje
nezabezpečené spojení z bezpečnostních důvodů.

### Výchozí konfigurace endpointu

Výchozí nastavení endpointu se používají jako základ pro všechny endpointy, pokud nejsou přepsána v konkrétním endpointu.
To vám umožňuje nastavit společná nastavení pro všechny endpointy na jednom místě.

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Výchozí:** `true`</p>
        <p>Povoluje / zakazuje konkrétní webové API.</p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Výchozí:** `:5555`</p>
        <p>Určuje hostitele a port, na kterém má konkrétní API naslouchat. Pokud hostitel není definován,
        použije se zástupná adresa `0.0.0.0` pro IPv4 a `::` pro IPv6. Pokud je hostitel definován jako platná
        IP adresa, použije se přímo. Pokud je zadán název domény, je přeložen na IP adresu pomocí Java DNS lookup
        a použit místo toho (výsledná IP adresa nemusí být ta, kterou jste očekávali – ale výsledná IP je
        zalogována do logu a konzole při startu serveru evitaDB, takže si ji můžete snadno zkontrolovat).</p>
        <p>Můžete definovat více hostitelů / portů oddělených čárkou. Server bude naslouchat na všech.</p>
    </dd>
    <dt>exposeOn</dt>
    <dd>
        <p>**Výchozí:** `localhost`</p>
        <p>Když evitaDB běží v Docker kontejneru a porty jsou vystaveny na hostitelském systému,
           interně rozpoznaný lokální název hostitele a port obvykle neodpovídají názvu hostitele a portu,
           na kterém je evitaDB dostupná na hostitelském systému.</p>
        <p>Vlastnost `exposeOn` vám umožňuje přepsat nejen externí název hostitele a schéma, ale také určit
        externí port, ale minimální konfigurací je název hostitele. Pokud nezadáte schéma / port, předpokládá se,
        že bude použito výchozí schéma / port nakonfigurované pro webové API.</p>
    </dd>
    <dt>tlsMode</dt>
    <dd>
        <p>**Výchozí:** `FORCE_TLS`</p>
        <p>Zda povolit [TLS](./tls.md) pro konkrétní API. K dispozici jsou tři režimy:</p>
        <ol>
            <li>`FORCE_TLS`: Povolená je pouze šifrovaná (TLS) komunikace.</li>
            <li>`FORCE_NO_TLS`: Povolená je pouze nešifrovaná (non-TLS) komunikace.</li>
            <li>`RELAXED`: Obě varianty budou dostupné, podle volby klienta.</li>
        </ol>
    </dd>
    <dt>keepAlive</dt>
    <dd>
        <p>**Výchozí:** `true`</p>
        <p>Pokud je toto nastaveno na false, server uzavírá spojení pomocí HTTP `connection: close` po každém požadavku.</p>
    </dd>
    <dt>mTls.enabled</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Povoluje / zakazuje [vzájemnou autentizaci](tls.md#mutual-tls) pro konkrétní API.</p>
    </dd>
    <dt>mTls.allowedClientCertificatePaths</dt>
    <dd>
        <p>**Výchozí:** `[]`</p>
        <p>Umožňuje definovat nula nebo více cest k souborům veřejných <Term location="/documentation/user/en/operate/tls.md" name="certificate">klientských certifikátů</Term>, které mohou komunikovat pouze s tímto API.</p>
    </dd>
</dl>

### Konfigurace GraphQL API

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Výchozí:** `true`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Výchozí:** `:5555`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>exposeOn</dt>
    <dd>
        <p>**Výchozí:** `localhost:5555`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>tlsMode</dt>
    <dd>
        <p>**Výchozí:** `FORCE_TLS`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>parallelize</dt>
    <dd>
        <p>**Výchozí:** `true`</p>
        <p>Řídí, zda budou dotazy, které získávají data z jádra evitaDB, prováděny paralelně.</p>
    </dd>
    <dt>mTls.enabled</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>mTls.allowedClientCertificatePaths</dt>
    <dd>
        <p>**Výchozí:** `[]`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
</dl>

### Konfigurace REST API

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Výchozí:** `true`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Výchozí:** `:5555`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>exposeOn</dt>
    <dd>
        <p>**Výchozí:** `localhost:5555`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>tlsMode</dt>
    <dd>
        <p>**Výchozí:** `FORCE_TLS`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>mTls.enabled</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>mTls.allowedClientCertificatePaths</dt>
    <dd>
        <p>**Výchozí:** `[]`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
</dl>

### Konfigurace gRPC API

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Výchozí:** `true`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Výchozí:** `:5555`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>exposeOn</dt>
    <dd>
        <p>**Výchozí:** `localhost:5555`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>tlsMode</dt>
    <dd>
        <p>**Výchozí:** `FORCE_TLS`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>exposeDocsService</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Povoluje / zakazuje gRPC službu, která poskytuje dokumentaci pro gRPC API a umožňuje
        experimentálně volat libovolné služby z webového UI a zkoumat jejich výstup.</p>
    </dd>
    <dt>mTls.enabled</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>mTls.allowedClientCertificatePaths</dt>
    <dd>
        <p>**Výchozí:** `[]`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
</dl>

### Konfigurace System API

Existuje speciální endpoint `api.endpoints.system`, který umožňuje přístup přes nezabezpečený HTTP protokol. Protože jde o
jediný vystavený endpoint na nezabezpečeném http protokolu, musí běžet na samostatném portu. Endpoint umožňuje komukoliv
stáhnout veřejnou část serverového certifikátu.

Umožňuje také stáhnout výchozí klientský privátní/veřejný klíč, pokud jsou `api.certificate.generateAndUseSelfSigned` a
některé z `api.*.mTLS` současně nastaveny na `true`. Viz [výchozí nezabezpečené chování mTLS](tls.md#default-mtls-behaviour-not-secure) pro
více informací.

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Výchozí:** `true`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Výchozí:** `:5555`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>exposeOn</dt>
    <dd>
        <p>**Výchozí:** `localhost:5555`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>tlsMode</dt>
    <dd>
        <p>**Výchozí:** `FORCE_NO_TLS`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>mTls.enabled</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>mTls.allowedClientCertificatePaths</dt>
    <dd>
        <p>**Výchozí:** `[]`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
</dl>

### Konfigurace evitaLab

Konfigurace evitaLab primárně poskytuje přístup ke všem povoleným evitaDB API pro [evitaLab webového klienta](https://github.com/lukashornych/evitaLab).
Kromě toho může také vystavit a obsluhovat celou embedded verzi evitaLab webového klienta. Ve výchozí konfiguraci
vystaví embedded evitaLab webového klienta s přednastaveným připojením k serveru evitaDB podle konfigurace
ostatních API.

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Výchozí:** `true`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Výchozí:** `:5555`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>exposeOn</dt>
    <dd>
        <p>**Výchozí:** `localhost:5555`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>tlsMode</dt>
    <dd>
        <p>**Výchozí:** `FORCE_TLS`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>gui</dt>
    <dd>
        <p>[Viz konfiguraci](#gui-configuration)</p>
    </dd>
    <dt>mTls.enabled</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>mTls.allowedClientCertificatePaths</dt>
    <dd>
        <p>**Výchozí:** `[]`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
</dl>

#### Konfigurace GUI

Tato konfigurace určuje, jak bude vlastní evitaLab webový klient poskytován přes HTTP protokol.

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Výchozí**: `true`</p>
        <p>Zda má evitaDB poskytovat vestavěného evitaLab webového klienta spolu s evitaLab API.</p>
    </dd>
    <dt>readOnly</dt>
    <dd>
        <p>**Výchozí**: `false`</p>
        <p>Zda má být evitaLab webový klient poskytován v režimu pouze pro čtení. To znamená, že jeho runtime data a
        konfigurace nemohou být měněny. Neznamená to, že vám neumožní měnit data
        připojené instance evitaDB. Toto musí být nastaveno na [úrovni instance evitaDB](#server-configuration).</p>
    </dd>
</dl>

### Konfigurace pozorovatelnosti

Konfigurace řídí všechna pozorovací rozhraní vystavená externím systémům. Aktuálně jde o endpoint
pro scraping Prometheus metrik, OTEL trace exporter a záznamové funkce Java Flight Recorderu.

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Výchozí:** `true`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Výchozí:** `:5555`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>exposeOn</dt>
    <dd>
        <p>**Výchozí:** `localhost:5555`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>tlsMode</dt>
    <dd>
        <p>**Výchozí:** `FORCE_NO_TLS`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>tracing.serviceName</dt>
    <dd>
        <p>**Výchozí:** `evitaDB`</p>
        <p>Určuje název služby, pro kterou mají být trasování publikována.</p>
    </dd>
    <dt>tracing.endpoint</dt>
    <dd>
        <p>**Výchozí:** `null`</p>
        <p>Určuje URL na [OTEL collector](https://opentelemetry.io/docs/collector/), který sbírá trasování.
        Je vhodné spustit collector na stejném hostiteli jako evitaDB, aby mohl dále filtrovat trasování a
        zabránit zbytečné vzdálené síťové komunikaci.</p>
    </dd>
    <dt>tracing.protocol</dt>
    <dd>
        <p>**Výchozí:** `grpc`</p>
        <p>Určuje protokol použitý mezi aplikací a OTEL collectorem pro předávání trasování. Možné
        hodnoty jsou `grpc` a `http`. gRPC je mnohem výkonnější a je preferovanou volbou.</p>
    </dd>
    <dt>exportedQueryLabels</dt>
    <dd>
        <p>**Výchozí:** `null` (nic se neexportuje)</p>
        <p>Seznam názvů [štítků dotazu](../query/header/label.md), jejichž hodnota je vystavena jako Prometheus dimenze
        na metrikách dotazů. Názvy jsou libovolné a volí je operátor – evitaDB žádné nerezervuje – a každý je vystaven
        pod Prometheus-sanitizovanou podobou. Na rozdíl od většiny ostatních seznamů v evitaDB znamená nenastavený nebo
        prázdný seznam, že se *nic* neexportuje, nikoliv vše – viz [poznámky k bezpečnosti kardinality štítků](../query/header/label.md#label-cardinality-and-prometheus-export)
        pro vysvětlení, proč je toto výchozí nastavení opačné. Štítky s inherentně vysokou kardinalitou (`trace-id`, `client-id`, `ip-address`,
        `uri`) jsou rezervovány a při startu odmítnuty.</p>
    </dd>
    <dt>mTls.enabled</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
    <dt>mTls.allowedClientCertificatePaths</dt>
    <dd>
        <p>**Výchozí:** `[]`</p>
        <p>Viz [výchozí konfigurace endpointu](#default-endpoint-configuration)</p>
    </dd>
</dl>