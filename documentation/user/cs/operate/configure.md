---
title: Konfigurace
perex: Tento článek je kompletním průvodcem konfigurací instance evitaDB.
date: '14.7.2024'
author: Ing. Jan Novotný
proofreading: done
commit: '550d04a927cac92ff1a2e14d5aaa23b87f101618'
translated: 'true'
---
Server evitaDB je konfigurován ve formátu YAML a jeho výchozí nastavení je nejlépe popsáno následujícím ukázkovým kódem:

```yaml
name: evitaDB                                     # [viz konfigurace Name](#name)

server:                                           # [viz konfigurace Server](#konfigurace-serveru)
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
  transactionTimeoutInMilliseconds: 5M
  closeSessionsAfterSecondsOfInactivity: 60
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

storage:                                          # [viz konfigurace Storage](#konfigurace-úložiště)
  storageDirectory: "./data"
  workDirectory: "/tmp"
  lockTimeoutSeconds: 60
  waitOnCloseSeconds: 60
  outputBufferSize: 4MB
  maxOpenedReadHandles: 12
  syncWrites: true
  computeCRC32C: true
  compress: false
  minimalActiveRecordShare: 0.5
  fileSizeCompactionThresholdBytes: 100MB
  timeTravelEnabled: false

export:                                           # [viz konfigurace Export](#konfigurace-exportu)
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

transaction:                                      # [viz konfigurace Transaction](#konfigurace-transakcí)
  transactionWorkDirectory: /tmp/evitaDB/transaction
  transactionMemoryBufferLimitSizeBytes: 16MB
  transactionMemoryRegionCount: 256
  walFileSizeBytes: 16MB
  walFileCountKept: 8
  waitForTransactionAcceptanceInMillis: 20s
  flushFrequencyInMillis: 1s
  conflictPolicy: [ENTITY]

cache:                                            # [viz konfigurace Cache](#konfigurace-cache)
  enabled: false
  reflection: CACHE
  reevaluateEachSeconds: 60
  anteroomRecordCount: 100K
  minimalComplexityThreshold: 10K
  minimalUsageThreshold: 2
  cacheSizeInBytes: null

api:                                              # [viz konfigurace API](#konfigurace-api)
  workerGroupThreads: 4
  idleTimeoutInMillis: 2K
  requestTimeoutInMillis: 2K  
  maxEntitySizeInBytes: 2MB
  accessLog: false
  headers:
    forwardedUri: ["X-Forwarded-Uri"]
    forwardedFor: ["Forwarded", "X-Forwarded-For", "X-Real-IP"]
    label: ["X-EvitaDB-Label"]
    clientId: ["X-EvitaDB-ClientID"]
    traceParent: ["traceparent"]
  certificate:                                    # [viz konfigurace TLS](#konfigurace-tls) 
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
    system:                                       # [viz konfigurace System API](#konfigurace-system-api)
      enabled: null
      host: null
      exposeOn: null
      tlsMode: FORCE_NO_TLS
      keepAlive: null
      mTLS:
        enabled: null
        allowedClientCertificatePaths: null
    graphQL:                                      # [viz konfigurace GraphQL API](#konfigurace-graphql-api)
      enabled: null
      host: null
      exposeOn: null
      tlsMode: null
      keepAlive: null
      parallelize: true
      mTLS:
        enabled: null
        allowedClientCertificatePaths: null
    rest:                                         # [viz konfigurace REST API](#konfigurace-rest-api)
      enabled: null
      host: null
      exposeOn: null
      tlsMode: null
      keepAlive: null
      mTLS:
        enabled: null
        allowedClientCertificatePaths: null
    gRPC:                                         # [viz konfigurace gRPC API](#konfigurace-grpc-api)
      enabled: null
      host: null
      exposeOn: null
      tlsMode: null
      keepAlive: null
      exposeDocsService: false
      mTLS:
        enabled: null
        allowedClientCertificatePaths: null
    lab:                                          # [viz konfigurace evitaLab](#konfigurace-evitalab)
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
    observability:                                # [viz konfigurace Observability](#konfigurace-observability)
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

Výchozí konfigurační soubor se nachází ve <SourceClass>evita_server/src/main/resources/evita-configuration.yaml</SourceClass>.
Jak můžete vidět, obsahuje proměnné, které umožňují propagaci argumentů z příkazové řádky / proměnných prostředí,
které jsou přítomny při startu serveru. Formát použitý v tomto souboru je:

```
${argument_name:defaultValue}
```
</Note>

## Přepisování výchozích hodnot

Existuje několik způsobů, jak přepsat výchozí hodnoty uvedené v souboru <SourceClass>evita_server/src/main/resources/evita-configuration.yaml</SourceClass> 
na classpath.

### Proměnné prostředí

Jakoukoli konfigurační vlastnost lze přepsat nastavením proměnné prostředí se speciálně vytvořeným názvem. Název
proměnné lze odvodit z proměnné použité ve výchozím konfiguračním souboru, která je vždy sestavena z cesty
k vlastnosti v konfiguračním souboru. Výpočet spočívá v převedení názvu proměnné na velká písmena a
nahrazení všech teček podtržítky. Například vlastnost `server.coreThreadCount` lze přepsat nastavením
proměnné prostředí `SERVER_CORETHREADCOUNT`.

### Argumenty příkazové řádky

Jakoukoli konfigurační vlastnost lze také přepsat nastavením argumentu příkazové řádky v následujícím formátu

```shell
java -jar "target/evita-server.jar" "storage.storageDirectory=../data"
```

Argumenty aplikace mají přednost před proměnnými prostředí.

<Note type="info">

<NoteTitle toggles="true">

##### Jak nastavím argumenty aplikace v Docker kontejneru?

</NoteTitle>

Při použití Docker kontejnerů můžete nastavit argumenty aplikace v proměnné prostředí `EVITA_ARGS` – například

```shell
docker run -i --rm --net=host -e EVITA_ARGS="storage.storageDirectory=../data" index.docker.io/evitadb/evitadb:latest
```

</Note>

### Vlastní konfigurační soubor

Nakonec lze konfigurační soubor přepsat zadáním vlastního konfiguračního souboru ve složce konfigurace
určené argumentem aplikace `configDir`. Vlastní konfigurační soubor musí být ve stejném YAML formátu jako
výchozí konfigurace, ale může obsahovat pouze podmnožinu vlastností, které chcete přepsat. Je také možné
definovat více přepisovacích souborů. Soubory jsou aplikovány v abecedním pořadí jejich názvů. Pokud si stavíte
vlastní Docker image, můžete použít následující příkaz pro přepsání konfiguračního souboru:

```shell
COPY "your_file.yaml" "$EVITA_CONFIG_DIR"
```

Pokud máte složitější řetězec pipeline, můžete kopírovat více souborů do této složky v různých fázích
pipeline – ale musíte zachovat správné abecední pořadí souborů, aby se přepsání aplikovala podle vašich představ.

## Name

Název serveru je unikátní jméno instance serveru evitaDB a měl by být unikátní pro každou instanci (prostředí)
instalace evitaDB. Pokud není zadán žádný název a výchozí `evitaDB` zůstane zachován, automaticky se k němu
připojí hash hodnota vypočítaná z názvu hostitele serveru, cesty k hlavnímu úložišti serveru a
časového razítka vytvoření úložného adresáře. To je zajištěno proto, aby byl název serveru unikátní i v případě,
že je server spuštěn vícekrát na stejném stroji. Název serveru se používá v klientech k rozlišení jednoho serveru
od druhého a ke správnému zpracování unikátních serverových certifikátů.

## Konfigurace serveru

Tato sekce obsahuje obecná nastavení serveru evitaDB. Umožňuje konfigurovat thread pooly, fronty, timeouty:

<dl>
    <dt>requestThreadPool</dt>
    <dd>
        <p>Nastavuje limity základního thread poolu používaného pro obsluhu všech příchozích požadavků. Vlákna z tohoto poolu obsluhují všechny
        dotazy a aktualizace až do okamžiku potvrzení/vrácení transakce. Více informací viz [samostatná kapitola](#konfigurace-thread-poolu).</p>
    </dd>
    <dt>transactionThreadPool</dt>
    <dd>
        <p>Nastavuje limity thread poolu transakcí používaného pro zpracování transakcí při jejich potvrzení. Tj. řešení konfliktů,
        začlenění do hlavní větve a nahrazení sdílených indexů. Více informací viz [samostatná kapitola](#konfigurace-thread-poolu).</p>
    </dd>
    <dt>serviceThreadPool</dt>
    <dd>
        <p>Nastavuje limity thread poolu služeb používaného pro servisní úlohy jako údržba, vytváření záloh, obnovení záloh atd.
        Více informací viz [samostatná kapitola](#konfigurace-thread-poolu).</p>
    </dd>
    <dt>queryTimeoutInMilliseconds</dt>
    <dd>
        <p>**Výchozí:** `5s`</p>
        <p>Nastavuje timeout v milisekundách, po jehož uplynutí by měly vlákna provádějící read-only požadavky ukončit svou činnost a
        přerušit provádění.</p>
    </dd>
    <dt>transactionTimeoutInMilliseconds</dt>
    <dd>
        <p>**Výchozí:** `5m`</p>
        <p>Nastavuje timeout v milisekundách, po jehož uplynutí by měly vlákna provádějící read-write požadavky ukončit svou činnost a
        přerušit provádění.</p>
    </dd>
    <dt>closeSessionsAfterSecondsOfInactivity</dt>
    <dd>
        <p>**Výchozí:** `60`</p>
        <p>Určuje maximální přípustnou dobu nečinnosti <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass> před
        jejím nuceným uzavřením ze strany serveru.</p>
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
        <p>Zakáže logování pomocných informačních zpráv (např. informace o spuštění). Nezakáže však hlavní logování
           spravované pomocí [Slf4j](https://www.slf4j.org/).</p>
         <Note type="warning">
            Toto nastavení by nemělo být použito při běhu více instancí serveru v rámci jedné JVM, protože v současnosti
            není thread-safe.            
        </Note>
    </dd>
</dl>

### Konfigurace thread poolu

<dl>
    <dt>coreThreadCount</dt>
    <dd>
        <p>**Výchozí:** `4`</p>
        <p>Definuje minimální počet vláken v hlavním thread poolu evitaDB, která jsou používána pro zpracování dotazů,
        transakčních aktualizací a servisních úloh (údržba, revalidace cache). Hodnota by měla být alespoň rovna
        počtu jader stroje.</p>
    </dd>
    <dt>maxThreadCount</dt>
    <dd>
        <p>**Výchozí:** `16`</p>
        <p>Definuje maximální počet vláken v hlavním thread poolu evitaDB. Hodnota by měla být násobkem hodnoty
        `coreThreadCount`.</p>
    </dd>
    <dt>threadPriority</dt>
    <dd>
        <p>**Výchozí:** `5`</p>
        <p>Definuje prioritu vláken vytvářených v poolu (pro budoucí využití).</p> 
    </dd>
    <dt>queueSize</dt>
    <dd>
        <p>**Výchozí:** `100`</p>
        <p>Definuje maximální počet úloh, které se mohou nahromadit ve frontě čekající na volné vlákno z thread poolu.
        Úlohy, které tento limit překročí, budou zahazovány (nové požadavky/úlohy selžou s výjimkou).</p>
    </dd>
</dl>

### Konfigurace záznamu provozu

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Při nastavení na `true` server zaznamenává veškerý provoz do databáze (všechny katalogy) do jednoho sdíleného
        paměťového a diskového bufferu, který může být volitelně uložen do souboru. Pokud je záznam provozu vypnutý,
        lze jej stále zapnout na vyžádání přes API (ale nebude automaticky zapnut a zaznamenán). Záznam je optimalizován
        pro nízkou režii výkonu, ale neměl by být povolen v produkčních systémech (proto je výchozí hodnota `false`).</p>
    </dd>
    <dt>sourceQueryTracking</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Při nastavení na `true` server zaznamená dotaz v jeho původní podobě (GraphQL / REST / gRPC) a sleduje
        poddotazy související s původním dotazem. To je užitečné pro ladění a analýzu výkonu, ale není
        nezbytné pro přehrávání provozu.</p>
    </dd>
    <dt>trafficMemoryBufferSizeInBytes</dt>
    <dd>
        <p>**Výchozí:** `4MB`</p>
        <p>Nastavuje velikost paměťového bufferu v bajtech pro záznam provozu. I když je `enabled` nastaveno na `false`,
        tato vlastnost se použije při požadavku na záznam provozu na vyžádání. Tato vlastnost ovlivňuje počet
        paralelních relací, které jsou zaznamenávány. Všechny požadavky v rámci jedné relace musí být nejprve
        shromážděny v tomto paměťovém bufferu, než jsou sekvenčně uloženy do diskového bufferu.</p> 
    </dd>
    <dt>trafficDiskBufferSizeInBytes</dt>
    <dd>
        <p>**Výchozí:** `32MB`</p>
        <p>Nastavuje velikost diskového bufferu v bajtech pro záznam provozu. I když je `enabled` nastaveno na `false`,
        tato vlastnost se použije při požadavku na záznam provozu na vyžádání. Diskový buffer představuje kruhový
        buffer, který je indexován a dostupný k prohlížení v rozhraní evitaLab. Čím větší buffer, tím více
        historických dat může uchovávat.</p>
    </dd>
    <dt>exportFileChunkSizeInBytes</dt>
    <dd>
        <p>**Výchozí:** `16MB`</p>
        <p>Nastavuje velikost chunku exportovaného souboru v bajtech. Při exportu obsahu záznamu provozu je soubor
        rozdělen na chunky této velikosti. Chunky jsou následně komprimovány a ukládány do exportního adresáře.</p>
    </dd>
    <dt>trafficSamplingPercentage</dt>
    <dd>
        <p>**Výchozí:** `100`</p>
        <p>Určuje procento provozu, které má být zachyceno. Hodnota je mezi 0 a 100 – nula znamená, že se nezachytává
           žádný provoz (ekvivalentní `enabled: false`) a 100 znamená, že se pokusí zachytit veškerý provoz.</p>
    </dd>
    <dt>trafficFlushIntervalInMilliseconds</dt>
    <dd>
        <p>**Výchozí:** `1m`</p>
        <p>Nastavuje interval v milisekundách, po kterém je buffer provozu zapsán na disk. Pro vývoj
        (tj. nízký provoz, okamžité ladění) lze nastavit na 0. Pro produkci by měla být nastavena rozumná
        hodnota (např. 60000 = minuta).</p>
    </dd>
</dl>

## Konfigurace úložiště

Tato sekce obsahuje možnosti konfigurace vrstvy úložiště databáze.

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
        aplikace nebo v absolutní podobě (doporučeno). Ve výchozím nastavení je použita Java temp složka, ale lze ji
        přesměrovat, pokud je temp složka příliš malá nebo nevhodná pro dočasné pracovní soubory.</p>
    </dd>
    <dt>lockTimeoutSeconds</dt>
    <dd>
        <p>**Výchozí:** `60`</p>
        <p>Určuje maximální dobu, po kterou může vlákno čekat na získání exkluzivního WRITE zámku na soubor pro zápis
        dat. Změna této hodnoty by neměla být nutná, pokud vše funguje správně.</p>
    </dd>
    <dt>waitOnCloseSeconds</dt>
    <dd>
        <p>**Výchozí:** `60`</p>
        <p>Určuje timeout, po který evitaDB čeká na uvolnění read handle na soubor. Pokud není handle uvolněn
        v rámci timeoutu, volající proces obdrží výjimku. Změna této hodnoty by neměla být nutná, pokud vše funguje správně.</p>
    </dd>
    <dt>outputBufferSize</dt>
    <dd>
        <p>**Výchozí:** `4MB`</p>
        <p>Velikost výstupního bufferu určuje, jak velký buffer je držen v paměti pro výstupní účely. Velikost bufferu
        omezuje maximální velikost jednotlivého záznamu v key/value úložišti.</p>
    </dd>
    <dt>maxOpenedReadHandles</dt>
    <dd>
        <p>**Výchozí:** `12`</p>
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
        <p>Určuje, zda úložiště vynucuje operačnímu systému flush interních bufferů na disk v pravidelných "bezpečných bodech".
        Výchozí je true, aby nedošlo ke ztrátě dat při výpadku napájení.
        Existují situace, kdy vypnutí této funkce může zlepšit výkon a klient může akceptovat riziko
        ztráty dat (např. při automatizovaných testech apod.).</p>
    </dd>
    <dt>computeCRC32C</dt>
    <dd>
        <p>**Výchozí:** `true`</p>
        <p>Určuje, zda se pro zapisované záznamy v key/value úložišti počítají kontrolní součty CRC32C a zda se kontroluje
        CRC32C při čtení záznamu.</p>
        <Note type="warning">
            Důrazně doporučujeme nastavit tuto hodnotu na `true`, protože umožňuje co nejdříve odhalit potenciálně poškozené záznamy.
        </Note>
    </dd>
    <dt>compress</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Určuje, zda se mají data komprimovat. Pokud je nastaveno na true, budou všechna data komprimována, ale pouze ta,
        jejichž komprimovaná velikost je menší než původní, budou uložena v komprimované podobě. Nastavení této vlastnosti
        na `true` může zpomalit zápisy (i když ne výrazně) a zvýšit rychlost čtení a propustnost, protože je méně pomalého diskového I/O.
        V současnosti je použita standardní komprese ZIP/deflate.</p>
    </dd>
    <dt>minimalActiveRecordShare</dt>
    <dd>
        <p>**Výchozí:** `0.5` (při plýtvání nad 50 % je soubor komprimován)</p>
        <p>Minimální podíl aktivních záznamů v datovém souboru. Pokud je podíl nižší a velikost souboru překročí také
            limit `fileSizeCompactionThresholdBytes`, soubor bude komprimován. To znamená, že nový soubor obsahující pouze
            aktivní záznamy bude zapsán vedle původního souboru.</p>
    </dd>
    <dt>fileSizeCompactionThresholdBytes</dt>
    <dd>
        <p>**Výchozí:** `100MB`</p>
        <p>Minimální velikost souboru pro komprimaci. Pokud je velikost souboru menší, nebude komprimován ani
            v případě, že podíl aktivních záznamů je nižší než minimální podíl.</p>
    </dd>
    <dt>timeTravelEnabled</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Při nastavení na true nejsou datové soubory po komprimaci ihned odstraněny, ale jsou ponechány na disku tak dlouho,
        dokud je v WAL logu dostupná historie. To umožňuje vytvořit snímek databáze v libovolném bodě historie pokryté WAL logem.
        Ze snímku lze databázi obnovit do přesného bodu v čase se všemi daty dostupnými v té době.</p>
    </dd>
</dl>

## Konfigurace exportu

Tato sekce obsahuje možnosti konfigurace exportní funkce. evitaDB podporuje export dat buď do
lokálního souborového systému, nebo do S3-kompatibilního úložiště. Aktivní může být vždy pouze jeden exportní backend – pokud
je povoleno více backendů (`enabled: true`), při startu bude vyhozena chyba.

### Konfigurace exportu do souborového systému

Konfigurace backendu exportu do lokálního souborového systému. Toto je výchozí backend, pokud není povolen žádný jiný backend.

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Výchozí:** `null` (výchozí je true, pokud není povolen žádný jiný backend)</p>
        <p>Při nastavení na `true` povoluje backend exportu do lokálního souborového systému. Pokud jsou obě hodnoty
        `fileSystem.enabled` a `s3.enabled` `null`, použije se výchozí backend souborového systému.</p>
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
        <p>Definuje složku, kam evitaDB ukládá exportované soubory. Cestu lze zadat relativně k pracovnímu adresáři
        aplikace nebo v absolutní podobě (doporučeno). Soubory jsou automaticky odstraňovány podle limitů
        definovaných v `historyExpirationSeconds` a `sizeLimitBytes`.</p>
    </dd>
</dl>

### Konfigurace exportu do S3

Konfigurace backendu exportu do S3-kompatibilního úložiště. Vyžaduje modul `evita_export_s3` na classpath.

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Výchozí:** `null` (zakázáno)</p>
        <p>Při nastavení na `true` povoluje backend exportu do S3-kompatibilního úložiště. Pole `endpoint`, `bucket`,
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
        <p>Název S3 bucketu, kam budou exportované soubory ukládány. Povinné při povolení S3.</p>
    </dd>
    <dt>accessKey</dt>
    <dd>
        <p>**Výchozí:** `null`</p>
        <p>Přístupový klíč pro autentizaci S3. Povinné při povolení S3.</p>
    </dd>
    <dt>secretKey</dt>
    <dd>
        <p>**Výchozí:** `null`</p>
        <p>Tajný klíč pro autentizaci S3. Povinné při povolení S3.</p>
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
        <p>Určuje timeout aplikovaný na všechny externí S3 operace prováděné exportní službou.
        Timeout se používá při čekání na dokončení asynchronních volání MinIO klienta, jako je
        vytváření bucketu, upload, download, mazání a čtení metadat. Zvyšte tuto hodnotu, pokud
        váš S3 poskytovatel nebo síť vykazuje vyšší latence.</p>
    </dd>
</dl>

## Konfigurace transakcí

Tato sekce obsahuje možnosti konfigurace vrstvy úložiště databáze určené pro zpracování transakcí.

<dl>
    <dt>transactionWorkDirectory</dt>
    <dd>
        <p>**Výchozí:** `/tmp/evitaDB/transaction`</p>
        <p>Adresář na lokálním disku, kde Evita vytváří dočasné složky a soubory pro transakční zpracování.
            Ve výchozím nastavení je použita dočasná složka – ale je vhodné nastavit vlastní adresář, abyste předešli problémům
            s místem na disku.</p>
    </dd>
    <dt>transactionMemoryBufferLimitSizeBytes</dt>
    <dd>
        <p>**Výchozí:** `16MB`</p>
        <p>Počet bajtů alokovaných v off-heap paměti pro transakční paměťový buffer. Tento buffer slouží k
            dočasnému ukládání (izolovaných) transakčních dat před jejich potvrzením do databáze.
            Pokud je buffer plný, transakční data jsou ihned zapsána na disk a zpracování transakce se zpomalí.</p>
    </dd>
    <dt>transactionMemoryRegionCount</dt>
    <dd>
        <p>**Výchozí:** `256`</p>
        <p>Počet částí bufferu `transactionMemoryBufferLimitSizeBytes`.
            Čím více částí, tím menší jsou a tím vyšší je pravděpodobnost, že buffer bude plný a bude nutné jej
            zapsat na disk.</p>
    </dd>
    <dt>walFileSizeBytes</dt>
    <dd>
        <p>**Výchozí:** `16MB`</p>
        <p>Velikost souboru Write-Ahead Logu (WAL) v bajtech před jeho rotací.</p>
    </dd>
    <dt>walFileCountKept</dt>
    <dd>
        <p>**Výchozí:** `8`</p>
        <p>Počet uchovávaných WAL souborů. Zvyšte tento počet v kombinaci s `walFileSizeBytes`, pokud chcete
            uchovávat delší historii změn.</p>
    </dd>
    <dt>waitForTransactionAcceptanceInMillis</dt>
    <dd>
        <p>**Výchozí:** `20s`</p>
        <p>Maximální doba v milisekundách, po kterou systém čeká na přijetí zapisovací transakce,
            tj. zapsání do sdíleného transakčního WAL. Toto časové rozpětí pokrývá jak fázi řešení konfliktů,
            tak přidání do sdíleného WAL souboru. Pokud operace vyprší, celá transakce bude
            vrácena zpět.</p>
    </dd>
    <dt>flushFrequencyInMillis</dt>
    <dd>
        <p>**Výchozí:** `1s`</p>
        <p>Frekvence zápisu transakčních dat na disk při jejich sekvenčním zpracování.
            Pokud databáze zpracuje (malou) transakci velmi rychle, může se rozhodnout zpracovat další transakci před
            zápisem změn na disk. Pokud klient čeká na `WAIT_FOR_CHANGES_VISIBLE`, může čekat celou
            dobu `flushFrequencyInMillis`, než dostane odpověď.</p>
    </dd>
    <dt>conflictPolicy</dt>
    <dd>
        <p>**Výchozí:** `[ENTITY]`</p>
        <p>Sada politik řešení konfliktů, které budou použity pro řešení konfliktů s jinými paralelními relacemi během
            potvrzení transakce. Politika konfliktů určuje úroveň podrobnosti, na které jsou zjišťovány a serializovány konflikty zápisu.
            Čím jemnější rozsah, tím více mutací lze zpracovat současně bez blokování;
            čím hrubší rozsah, tím méně konfliktů je možné, ale za cenu nižší paralelnosti.
            Viz sekce [Politiky konfliktů](#politiky-konfliktů) pro detailní popis dostupných politik.</p>
        <p>Můžete zadat více politik jako pole. Prázdné pole znamená "vítězí poslední zapisovatel" – nedochází k žádné detekci konfliktů. Příklady:</p>
        <ul>
            <li>`[ENTITY]` – výchozí, konflikty detekovány na úrovni entity</li>
            <li>`[ENTITY_ATTRIBUTE, REFERENCE_ATTRIBUTE]` – jemnozrnné konflikty pouze pro atributy, mutace
                 ostatních dat negenerují konflikty (vítězí poslední zapisovatel)</li>
            <li>`[ENTITY, ENTITY_ATTRIBUTE, REFERENCE_ATTRIBUTE]` – jemnozrnné konflikty pouze pro atributy,
                 mutace ostatních dat generují konflikty na úrovni celé entity</li>
            <li>`[]` – žádná detekce konfliktů (vítězí poslední zapisovatel)</li>
        </ul>
    </dd>
</dl>

## Politiky konfliktů

Politiky konfliktů řídí úroveň podrobnosti, na které jsou v evitaDB detekovány a serializovány konflikty zápisu. Pokud se více
transakcí pokouší současně upravit stejná data, politika konfliktů určuje, zda tyto operace
jsou ve vzájemném konfliktu, nebo mohou probíhat nezávisle.

EvitaDB odvozuje klíč konfliktu pro každou příchozí zápisovou mutaci. Rozsah tohoto klíče je řízen politikou konfliktů:
čím jemnější rozsah, tím více mutací lze zpracovat současně bez blokování; čím hrubší rozsah,
tím méně konfliktů je možné, ale za cenu nižší paralelnosti.

### Dostupné politiky konfliktů

<dl>
    <dt>CATALOG</dt>
    <dd>
        <p>Tato politika generuje klíče konfliktů na úrovni celého katalogu. Každý zápis do katalogu bude
            považován za potenciálně konfliktní s jakýmkoli jiným zápisem do stejného katalogu, což v praxi znamená,
            že nebude povolen žádný souběžný zápis do stejného katalogu.</p>
        <p>**Použití:** Maximální bezpečnost, pokud potřebujete zajistit striktní pořadí všech úprav katalogu,
            za cenu nejnižší paralelnosti.</p>
    </dd>
    <dt>COLLECTION</dt>
    <dd>
        <p>Tato politika generuje klíče konfliktů na úrovni kolekcí v katalogu. Mutace zaměřené
            na různé kolekce lze zpracovávat souběžně, zatímco souběžné mutace zaměřené na stejnou kolekci
            generují konflikty.</p>
        <p>**Použití:** Pokud potřebujete zajistit konzistenci v rámci každé kolekce nezávisle, ale umožnit
            souběžné úpravy různých kolekcí.</p>
    </dd>
    <dt>ENTITY</dt>
    <dd>
        <p>**Výchozí politika.** Tato politika generuje klíče konfliktů na úrovni jednotlivých entit v kolekci.
            Mutace zaměřené na různé entity lze zpracovávat souběžně, zatímco souběžné mutace zaměřené na stejnou entitu
            generují konflikty.</p>
        <p>**Použití:** Doporučeno pro většinu aplikací. Poskytuje dobrý kompromis mezi paralelností a bezpečností,
            zajišťuje, že úpravy stejné entity jsou správně serializovány.</p>
    </dd>
    <dt>ENTITY_ATTRIBUTE</dt>
    <dd>
        <p>Tato politika generuje klíče konfliktů na úrovni konkrétních atributů entit. Souběžné mutace
            zaměřené na stejný atribut stejné entity generují konflikty, zatímco mutace zaměřené na různé
            atributy, části stejné entity nebo různé entity lze zpracovávat souběžně.</p>
        <p>**Poznámka:** Tato politika nepokrývá atributy referencí, viz <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/conflict/ConflictPolicy.java</SourceClass> pro více informací.</p>
        <p>**Použití:** Maximální paralelnost, pokud lze různé části stejné entity bezpečně upravovat
            nezávisle (např. současná úprava popisu a množství na skladě produktu).</p>
    </dd>
    <dt>REFERENCE</dt>
    <dd>
        <p>Tato politika generuje klíče konfliktů na úrovni konkrétních referencí entit. Souběžné mutace
            zaměřené na stejnou referenci stejné entity generují konflikty, zatímco mutace zaměřené na různé
            reference, části stejné entity nebo různé entity lze zpracovávat souběžně.</p>
        <p>**Použití:** Pokud potřebujete jemnozrnné řízení vztahů entit a chcete umožnit souběžné
            úpravy různých referencí stejné entity.</p>
    </dd>
    <dt>REFERENCE_ATTRIBUTE</dt>
    <dd>
        <p>Tato politika generuje klíče konfliktů na úrovni konkrétních atributů referencí v entitách.
            Souběžné mutace zaměřené na stejný atribut stejné reference stejné entity generují
            konflikty, zatímco mutace zaměřené na různé atributy, reference, části stejné entity nebo různé
            entity lze zpracovávat souběžně.</p>
        <p>**Použití:** Nejjemnější granularita pro atributy referencí, umožňuje maximální paralelnost při úpravách
            různých atributů referencí entit.</p>
    </dd>
    <dt>ASSOCIATED_DATA</dt>
    <dd>
        <p>Tato politika generuje klíče konfliktů na úrovni asociovaných dat entit. Souběžné mutace
            zaměřené na stejná asociovaná data stejné entity generují konflikty, zatímco mutace zaměřené
            na různá asociovaná data, části stejné entity nebo různé entity lze zpracovávat souběžně.</p>
        <p>**Použití:** Pokud potřebujete umožnit souběžné úpravy různých položek asociovaných dat
            stejné entity.</p>
    </dd>
    <dt>PRICE</dt>
    <dd>
        <p>Tato politika generuje klíče konfliktů na úrovni cen entit. Souběžné mutace
            zaměřené na stejnou cenu stejné entity generují konflikty, zatímco mutace zaměřené na různé
            ceny, části stejné entity nebo různé entity lze zpracovávat souběžně.</p>
        <p>**Použití:** Pokud potřebujete umožnit souběžné úpravy různých cen stejné entity
            (např. nezávislé úpravy různých ceníků).</p>
    </dd>
    <dt>HIERARCHY</dt>
    <dd>
        <p>Tato politika generuje klíče konfliktů na úrovni hierarchie entit. Souběžné mutace
            zaměřené na stejnou pozici v hierarchii stejné entity generují konflikty, zatímco mutace
            zaměřené na různé pozice, části stejné entity nebo různé entity lze zpracovávat souběžně.</p>
        <p>**Použití:** Pokud potřebujete zajistit konzistenci hierarchických vztahů a zároveň umožnit
            souběžné úpravy různých částí hierarchie.</p>
    </dd>
</dl>

### Výběr správné politiky konfliktů

Při výběru politik konfliktů pro vaši aplikaci zvažte:

1. **Požadavky na paralelnost:** Jemnozrnné politiky (např. `ENTITY_ATTRIBUTE`, `REFERENCE_ATTRIBUTE`) umožňují více
   souběžných operací, ale vyžadují pečlivé zvážení datových závislostí.

2. **Požadavky na konzistenci dat:** Hrubozrnné politiky (např. `ENTITY`, `COLLECTION`) poskytují silnější
   záruky konzistence, ale mohou omezit paralelnost.

3. **Vzor použití aplikace:** Pokud vaše aplikace často současně upravuje různé části stejné entity,
   zvažte použití více jemnozrnných politik současně.

4. **Kompromis mezi výkonem a bezpečností:** Začněte s výchozí politikou `ENTITY` a přejděte na jemnější granularitu
   pouze v případě, že zjistíte konkrétní úzká místa v paralelním zpracování.

### Režim vítězí poslední zapisovatel

Pokud zadáte prázdné pole `[]` pro konfiguraci `conflictPolicy`, nebude prováděna žádná detekce konfliktů. To
znamená, že poslední transakce, která se potvrdí, přepíše všechny předchozí změny bez jakékoli kontroly konfliktů. Tento režim
umožňuje maximální paralelnost, ale měl by být použit pouze tehdy, když máte jistotu, že ve vaší aplikaci nemohou
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
        <p>Toto nastavení povoluje nebo zakazuje používání cache jako celku.</p>
    </dd>
    <dt>reflection</dt>
    <dd>
        <p>**Výchozí:** `CACHE`</p>
        <p>Toto nastavení povoluje nebo zakazuje cachování informací o Java reflexi. Režim `CACHE` je obvykle doporučený,
        pokud neprovádíte nějaký druh testu.</p>
    </dd>
    <dt>reevaluateEachSeconds</dt>
    <dd>
        <p>**Výchozí:** `60`</p>
        <p>Definuje periodu pro opětovné vyhodnocení adeptů na cache, kteří mají být zařazeni do cache nebo odstraněni.
        Opětovné vyhodnocení může být také spuštěno překročením maximálního povoleného `anteroomRecordCount`, ale nejpozději
        po uplynutí `reevaluateEachSeconds` od posledního vyhodnocení (s výjimkou situace, kdy není volné vlákno
        v thread poolu pro tuto úlohu). Viz [detailní popis procesu cachování](../deep-dive/cache.md).</p>
    </dd>
    <dt>anteroomRecordCount</dt>
    <dd>
        <p>**Výchozí:** `100K`</p>
        <p>Definuje maximální počet záznamů v předsíni cache. Po dosažení tohoto počtu je automaticky spuštěn proces
        opětovného vyhodnocení, což vede k vyčištění předsíně. Předsíň je také periodicky čištěna
        každých `reevaluateEachSeconds`. Viz [detailní popis procesu cachování](../deep-dive/cache.md).</p>
    </dd>
    <dt>minimalComplexityThreshold</dt>
    <dd>
        <p>**Výchozí:** `10K`</p>
        <p>Určuje minimální výpočetní složitost, která musí být dosažena, aby byl výsledek uložen do cache.
        Je to virtuální číslo, takže neexistuje žádný návod, jak velké by mělo být. Pokud se cache zaplní
        mnoha výsledky pochybné užitečnosti, můžete zkusit zvýšit tento práh na vyšší hodnoty.</p>
    </dd>
    <dt>minimalUsageThreshold</dt>
    <dd>
        <p>**Výchozí:** `2`</p>
        <p>Určuje minimální počet opakovaného využití vypočteného výsledku před jeho uložením do cache. Pokud se cache
        plní hodnotami s nízkým poměrem zásahů, můžete zkusit zvýšit tento práh na vyšší hodnoty.</p>
    </dd>
    <dt>cacheSizeInBytes</dt>
    <dd>
        <p>**Výchozí:** `null`, což znamená, že evitaDB použije 25 % volné paměti změřené v okamžiku spuštění a vše do ní nahraje</p>
        <p>evitaDB se snaží odhadnout velikost paměti každého cachovaného objektu a zabránit překročení tohoto limitu.</p>

        <Note type="question">

        <NoteTitle toggles="true">

        ##### Jak měříme velikost objektu?
        </NoteTitle>

        Změřit přesné množství paměti, které každý objekt v Javě zabírá, není snadné a v tuto chvíli jde pouze
        o náš odhad. Podle našich zkušeností jsou naše odhady nastaveny výše než realita a systém se zastaví
        přibližně na 90 % nastaveného limitu `cacheSizeInBytes` (ale tato zkušenost je založena na OS Linux, architektura x86_64).
        </Note>
    </dd>
</dl>

## Konfigurace API

Tato sekce konfigurace vám umožňuje selektivně povolit, zakázat a upravit konkrétní API.

<dl>
    <dt>workerGroupThreads</dt>
    <dd>
        <p>**Výchozí:** `počet CPU`</p>
        <p>Definuje počet IO vláken, která budou použita Armeria pro přijímání a odesílání HTTP payloadu.</p>
    </dd>
    <dt>idleTimeoutInMillis</dt>
    <dd>
        <p>**Výchozí:** `2K`</p>
        <p>Doba, po kterou může být spojení nečinné, než dojde k jeho ukončení. Nečinné spojení je spojení,
            které nemělo žádný přenos dat v období nečinnosti. Toto je poměrně hrubý přístup,
            a malé hodnoty způsobí problémy u požadavků s dlouhou dobou zpracování.</p>
    </dd>
    <dt>requestTimeoutInMillis</dt>
    <dd>
        <p>**Výchozí:** `2K`</p>
        <p>Doba, po kterou může spojení zůstat nečinné bez zpracování požadavku, než je serverem uzavřeno.</p>
    </dd> 
    <dt>maxEntitySizeInBytes</dt>
    <dd>
        <p>**Výchozí:** `2MB`</p>
        <p>Výchozí maximální velikost entity požadavku. Pokud je tělo entity větší než tento limit, bude
            při čtení požadavku vyhozena IOException (u požadavků s pevnou délkou při prvním čtení, u chunkovaných
            požadavků při překročení limitu).</p>
    </dd>
    <dt>accessLog</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Povoluje / zakazuje logování zpráv access logu pro všechna API.</p>
    </dd> 
</dl>

### Konfigurace hlaviček

Hlavičky obsahují rozumné výchozí hodnoty, ale v některých případech je můžete chtít přepsat (například
hlavička `X-Forwarded-For` je někdy používána proxy servery mezi klientem a serverem).

Běžná konfigurace je v podsekci `headers` sekce `api`.
Umožňuje konfigurovat tato nastavení:

<dl>
    <dd>
        <p>Tato sekce obsahuje konfiguraci názvů HTTP hlaviček, které evitaDB rozpoznává.</p>
        <dl>
            <dt>forwardedUri</dt>
            <dd>
                <p>**Výchozí:** `["X-Forwarded-Uri"]`</p>
                <p>Pole názvů hlaviček, které jsou rozpoznávány jako hlavičky předaného URI. Tyto hlavičky se používají, když je evitaDB za proxy, pro určení původního URI požadovaného klientem.</p>
            </dd>
            <dt>forwardedFor</dt>
            <dd>
                <p>**Výchozí:** `["Forwarded", "X-Forwarded-For", "X-Real-IP"]`</p>
                <p>Pole názvů hlaviček, které jsou rozpoznávány jako hlavičky předané IP adresy klienta. Tyto hlavičky se používají, když je evitaDB za proxy, pro určení původní IP adresy klienta.</p>
            </dd>
            <dt>label</dt>
            <dd>
                <p>**Výchozí:** `["X-EvitaDB-Label"]`</p>
                <p>Pole názvů hlaviček pro meta labely, které umožňují nastavit labely záznamu provozu přes HTTP hlavičky.</p>
            </dd>
            <dt>clientId</dt>
            <dd>
                <p>**Výchozí:** `["X-EvitaDB-ClientID"]`</p>
                <p>Pole názvů hlaviček, které jsou rozpoznávány jako hlavičky identifikátoru klienta. Tyto hlavičky lze použít k identifikaci klientské aplikace, která požadavek provádí.</p>
            </dd>
            <dt>traceParent</dt>
            <dd>
                <p>**Výchozí:** `["traceparent"]`</p>
                <p>Pole názvů hlaviček, které jsou rozpoznávány jako trace parent hlavičky. Tyto hlavičky se používají pro distribuované trasování k propojení požadavků napříč různými službami.</p>
            </dd>
        </dl>
    </dd>
</dl>

### Konfigurace TLS

Podpora TLS je ve výchozím nastavení povolena pro většinu API, ale lze ji individuálně zakázat v konfiguraci jednotlivých API.
Pokud nastavíte, že každé API má jiné nastavení TLS, musí mít každé API svůj vlastní port.

Běžná konfigurace je v podsekci `certificate` sekce `api`.
Umožňuje konfigurovat tato nastavení:

<dl>
  <dt>generateAndUseSelfSigned</dt>
  <dd>
    <p>**Výchozí:** `true`</p>
    <p>Při nastavení na `true` je při startu serveru automaticky vygenerována self-signed <Term location="/documentation/user/en/operate/tls.md">certifikační autorita</Term>
    <Term location="/documentation/user/en/operate/tls.md">certifikát</Term> a jeho
    <Term location="/documentation/user/en/operate/tls.md">soukromý klíč</Term> a použity pro komunikaci s klienty.</p>
  </dd>
  <dt>folderPath</dt>
  <dd>
    <p>**Výchozí:** podsložka `evita-server-certificates` v pracovním adresáři</p>
    <p>Představuje cestu ke složce, kde jsou uložené vygenerované certifikáty autority a jejich soukromý klíč.
    Toto nastavení se používá pouze pokud je `generateAndUseSelfSigned` nastaveno na `true`.</p>
  </dd>
  <dt>custom</dt>
  <dd>
    <p>Tato sekce umožňuje nakonfigurovat externě dodaný <Term location="/documentation/user/en/operate/tls.md">certifikát</Term>.
    Používá se pouze pokud je `generateAndUseSelfSigned` nastaveno na `false`.</p>
    <p>Sekce vyžaduje následující vnořená nastavení:</p>
      - **`certificate`**: cesta k veřejné části souboru certifikátu (*.crt)
      - **`privateKey`**: cesta k soukromému klíči certifikátu (*.key)
      - **`privateKeyPassword`**: heslo k soukromému klíči

    <Note type="info">

    <NoteTitle toggles="false">
        
    ##### Tip

    </NoteTitle>

      Doporučujeme zadávat heslo k soukromému klíči pomocí argumentu příkazové řádky (proměnné prostředí)
      `api.certificate.custom.privateKeyPasssword` a uložit jej v trezoru tajemství CI serveru.
    </Note>

    <Note type="question">

    <NoteTitle toggles="true">
    
    ##### Existuje alternativa k této ruční konfiguraci?

    </NoteTitle>

    Ano, existuje. Můžete použít standardizovaný způsob importu
    <Term location="/documentation/user/en/operate/tls.md">certifikační autority</Term>
    <Term location="/documentation/user/en/operate/tls.md">certifikátu</Term> do Java trust store. Tento postup je
    podrobně popsán v [tomto článku](https://medium.com/expedia-group-tech/how-to-import-public-certificates-into-javas-truststore-from-a-browser-a35e49a806dc).

    </Note>
  </dd>
</dl>

Pokud není nakonfigurován žádný vlastní certifikát, server se nespustí a bude vyhozena výjimka. Server neposkytuje
nezabezpečené spojení z bezpečnostních důvodů.

### Výchozí konfigurace endpointu

Výchozí nastavení endpointu jsou použita jako základ pro všechny endpointy, pokud nejsou přepsána v konkrétním endpointu.
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
        <p>Určuje hostitele a port, na kterých má konkrétní API naslouchat. Pokud není hostitel definován,
        použije se zástupná adresa `0.0.0.0` pro IPv4 a `::` pro IPv6. Pokud je hostitel definován jako platná
        IP adresa, použije se přímo. Pokud je zadán název domény, je přeložen na IP adresu pomocí Java
        DNS lookupu a použit místo něj (výsledná IP adresa nemusí být ta, kterou jste očekávali – ale výsledná IP je
        zalogována do logu a konzole při startu serveru evitaDB, takže ji můžete snadno zkontrolovat).</p>
        <p>Můžete definovat více hostitelů / portů oddělených čárkou. Server bude naslouchat na všech.</p>
    </dd>
    <dt>exposeOn</dt>
    <dd>
        <p>**Výchozí:** `localhost`</p>
        <p>Když evitaDB běží v Docker kontejneru a porty jsou vystaveny na hostitelském systému,
           interně rozpoznaný lokální hostitel a port obvykle neodpovídají hostiteli a portu,
           na kterých je evitaDB dostupná na hostitelském systému.</p> 
        <p>Vlastnost `exposedHost` umožňuje přepsat nejen externí hostname, schéma, ale i zadat
        externí port, minimální konfigurace je hostname. Pokud nezadáte schéma / port, exposed
        host předpokládá, že bude použito výchozí schéma / port nakonfigurované pro webové API.</p>
    </dd>
    <dt>tlsMode</dt>
    <dd>
        <p>**Výchozí:** `FORCE_TLS`</p>
        <p>Zda povolit [TLS](tls.md) pro konkrétní API. K dispozici jsou tři režimy:</p>
        <ol>
            <li>`FORCE_TLS`: Povolená je pouze šifrovaná (TLS) komunikace.</li>
            <li>`FORCE_NO_TLS`: Povolená je pouze nešifrovaná (non-TLS) komunikace.</li>
            <li>`RELAXED`: Budou dostupné obě varianty, podle volby klienta.</li>
        </ol>
    </dd>
    <dt>keepAlive</dt>
    <dd>
        <p>**Výchozí:** `true`</p>
        <p>Pokud je nastaveno na false, server po každém požadavku uzavře spojení přes HTTP `connection: close`.</p>
    </dd>
    <dt>mTls.enabled</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Povoluje / zakazuje [vzájemnou autentizaci](tls.md#vzájemné-tls) pro konkrétní API.</p>
    </dd>
    <dt>mTls.allowedClientCertificatePaths</dt>
    <dd>
        <p>**Výchozí:** `[]`</p>
        <p>Umožňuje definovat nula nebo více cest k veřejným <Term location="/documentation/user/en/operate/tls.md" name="certificate">klientským certifikátům</Term>, které mohou komunikovat pouze s tímto API.</p>
    </dd>
</dl>

### Konfigurace GraphQL API

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Výchozí:** `true`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Výchozí:** `:5555`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>exposedHost</dt>
    <dd>
        <p>**Výchozí:** `localhost:5555`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>tlsMode</dt>
    <dd>
        <p>**Výchozí:** `FORCE_TLS`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>parallelize</dt>
    <dd>
        <p>**Výchozí:** `true`</p>
        <p>Řídí, zda budou dotazy, které získávají data z jádra evitaDB, prováděny paralelně.</p>
    </dd>
    <dt>mTls.enabled</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>mTls.allowedClientCertificatePaths</dt>
    <dd>
        <p>**Výchozí:** `[]`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
</dl>

### Konfigurace REST API

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Výchozí:** `true`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Výchozí:** `:5555`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>exposedHost</dt>
    <dd>
        <p>**Výchozí:** `localhost:5555`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>tlsMode</dt>
    <dd>
        <p>**Výchozí:** `FORCE_TLS`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>mTls.enabled</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>mTls.allowedClientCertificatePaths</dt>
    <dd>
        <p>**Výchozí:** `[]`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
</dl>

### Konfigurace gRPC API

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Výchozí:** `true`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Výchozí:** `:5555`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>exposedHost</dt>
    <dd>
        <p>**Výchozí:** `localhost:5555`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>tlsMode</dt>
    <dd>
        <p>**Výchozí:** `FORCE_TLS`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>exposeDocsService</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Povoluje / zakazuje službu gRPC, která poskytuje dokumentaci pro gRPC API a umožňuje
        experimentálně volat libovolné služby z webového UI a zkoumat jejich výstup.</p>
    </dd>
    <dt>mTls.enabled</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>mTls.allowedClientCertificatePaths</dt>
    <dd>
        <p>**Výchozí:** `[]`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
</dl>

### Konfigurace System API

Existuje speciální endpoint `api.endpoints.system`, který umožňuje přístup přes nezabezpečený HTTP protokol. Protože se jedná o
jediný vystavený endpoint na nezabezpečeném http protokolu, musí běžet na samostatném portu. Endpoint umožňuje komukoli
stáhnout veřejnou část serverového certifikátu.

Umožňuje také stáhnout výchozí klientský privátní/veřejný klíč, pokud jsou `api.certificate.generateAndUseSelfSigned` a
některé z `api.*.mTLS` nastaveny na `true`. Viz [výchozí nezabezpečené chování mTLS](tls.md#výchozí-chování-mtls-nebezpečné) pro
více informací.

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Výchozí:** `true`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Výchozí:** `:5555`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>exposedHost</dt>
    <dd>
        <p>**Výchozí:** `localhost:5555`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>tlsMode</dt>
    <dd>
        <p>**Výchozí:** `FORCE_NO_TLS`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>mTls.enabled</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>mTls.allowedClientCertificatePaths</dt>
    <dd>
        <p>**Výchozí:** `[]`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
</dl>

### Konfigurace evitaLab

Konfigurace evitaLab primárně poskytuje přístup ke všem povoleným API evitaDB pro [webového klienta evitaLab](https://github.com/lukashornych/evitaLab).
Kromě toho může také vystavit a obsluhovat celou vestavěnou verzi webového klienta evitaLab. Ve výchozí konfiguraci
vystaví vestavěného webového klienta evitaLab s přednastaveným připojením k serveru evitaDB na základě konfigurace
ostatních API.

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Výchozí:** `true`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Výchozí:** `:5555`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>exposedHost</dt>
    <dd>
        <p>**Výchozí:** `localhost:5555`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>tlsMode</dt>
    <dd>
        <p>**Výchozí:** `FORCE_TLS`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>gui</dt>
    <dd>
        <p>[Viz konfigurace](#konfigurace-gui)</p>
    </dd>
    <dt>mTls.enabled</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>mTls.allowedClientCertificatePaths</dt>
    <dd>
        <p>**Výchozí:** `[]`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
</dl>

#### Konfigurace GUI

Tato konfigurace řídí, jak bude skutečný webový klient evitaLab obsluhován přes HTTP protokol.

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Výchozí**: `true`</p>
        <p>Zda má evitaDB obsluhovat vestavěného webového klienta evitaLab společně s evitaLab API.</p>
    </dd>
    <dt>readOnly</dt>
    <dd>
        <p>**Výchozí**: `false`</p>
        <p>Zda má být webový klient evitaLab obsluhován v režimu pouze pro čtení. To znamená, že jeho runtime data a
        konfiguraci nelze měnit. Neznamená to, že nebude umožněno měnit data
        připojené instance evitaDB. To je nutné konfigurovat na [úrovni instance evitaDB](#konfigurace-serveru).</p>
    </dd>
</dl>

### Konfigurace Observability

Konfigurace řídí všechny nástroje pro observabilitu vystavené externím systémům. Aktuálně jde o endpoint
pro scraping Prometheus metrik, OTEL trace exporter a záznam událostí Java Flight Recorder.

<dl>
    <dt>enabled</dt>
    <dd>
        <p>**Výchozí:** `true`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Výchozí:** `:5555`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>exposedHost</dt>
    <dd>
        <p>**Výchozí:** `localhost:5555`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>tlsMode</dt>
    <dd>
        <p>**Výchozí:** `FORCE_NO_TLS`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>tracing.serviceName</dt>
    <dd>
        <p>**Výchozí:** `evitaDB`</p>
        <p>Určuje název služby, pro kterou mají být publikovány trace záznamy.</p>
    </dd>
    <dt>tracing.endpoint</dt>
    <dd>
        <p>**Výchozí:** `null`</p>
        <p>Určuje URL na [OTEL collector](https://opentelemetry.io/docs/collector/), který sbírá trace záznamy.
        Je vhodné spustit collector na stejném hostiteli jako evitaDB, aby mohl dále filtrovat trace a
        zabránit zbytečné vzdálené síťové komunikaci.</p>
    </dd>
    <dt>tracing.protocol</dt>
    <dd>
        <p>**Výchozí:** `grpc`</p>
        <p>Určuje protokol použitý mezi aplikací a OTEL collectorem pro předávání trace záznamů. Možné
        hodnoty jsou `grpc` a `http`. gRPC je výrazně výkonnější a je preferovanou možností.</p>
    </dd>
    <dt>mTls.enabled</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
    <dt>mTls.allowedClientCertificatePaths</dt>
    <dd>
        <p>**Výchozí:** `[]`</p>
        <p>Viz [výchozí konfigurace endpointu](#výchozí-konfigurace-endpointu)</p>
    </dd>
</dl>