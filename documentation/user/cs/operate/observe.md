---
title: Observe
perex: Nástroje pro sledovatelnost (observability) v evitaDB jsou navrženy tak, aby vám pomohly monitorovat běžící instance evitaDB a také optimalizovat vaši aplikaci během vývoje. Všechny monitorovací nástroje vycházejí z našich provozních zkušeností a vývoje e-commerce projektů.
date: '17.1.2023'
author: Ing. Jan Novotný
proofreading: done
preferredLang: java
translated: 'true'
commit: '5f4c43c5ea6a1d0b36d705a54fc4aa214365a440'
---
**Práce ve vývoji**

Funkcionalita není finální – [viz issue #18](https://github.com/FgForrest/evitaDB/issues/18)
a [issue #628](https://github.com/FgForrest/evitaDB/issues/628).

## Logování

evitaDB používá logovací fasádu [SLF4J](https://www.slf4j.org/) pro logování jak aplikačních, tak přístupových logovacích zpráv. Ve výchozím nastavení
jsou povoleny pouze aplikační logy, přístupové logy je nutné [povolit v konfiguraci](#přístupový-log).

Naše výchozí konfiguraci logbacku můžete přepsat tím, že poskytnete vlastní `logback.xml` a nakonfigurujete jej standardním
způsobem, jak je [zdokumentováno na stránkách Logbacku](https://logback.qos.ch/manual/configuration.html#auto_configuration). Například předáním
`logback.configurationFile=/path/to/logback.xml` jako argument JVM.

Naši výchozí Logback konfiguraci najdete v GitHub repozitáři:
<SourceClass>https://github.com/FgForrest/evitaDB/blob/dev/evita_server/src/main/resources/META-INF/logback.xml</SourceClass>

### Přístupový log

Pokud je vlastnost `accessLog` nastavena na `true` v [konfiguraci](configure.md#konfigurace-api), server bude logovat
přístupové logovací zprávy pro všechny API pomocí
[Slf4j](https://www.slf4j.org/) logovací fasády. Tyto zprávy jsou logovány na úrovni `INFO` a obsahují marker `ACCESS_LOG`,
který můžete použít k oddělení standardních zpráv od přístupových logů. Přístupové logy jsou logovány
loggerem `com.linecorp.armeria.logging.access` (viz [dokumentace Armeria](https://armeria.dev/docs/server-access-log)).

### Serverové Logback utility

Server evitaDB je vybaven několika vlastními utilitami pro snadnější konfiguraci vlastních logovacích dat.

*Poznámka:* Tyto utility jsou dostupné pouze v serveru evitaDB, protože zbytek kódu evitaDB
není závislý na konkrétní implementaci logovací fasády [Slf4j](https://www.slf4j.org/).
Pokud je evitaDB použita jako embedded instance, následující nástroje nejsou dostupné, ale mohou sloužit jako reference pro
vlastní implementaci ve zvoleném frameworku.

#### Nástroje pro logovací agregátory

Pokud je pro zpracování logovacích zpráv evitaDB použit logovací agregátor, je často užitečné logovat aplikační logy jako jednorázové JSON objekty.
Proto je k dispozici [Logback](https://logback.qos.ch/) layout připravený k použití, který umožňuje snadno logovat aplikační logy jako JSON objekty.
Tento layout loguje zprávy jako JSON objekty a zajišťuje, že vše je správně escapováno, včetně znaků nového řádku
v logovacích zprávách (např. stack trace).

Layout je `io.evitadb.server.log.AppLogJsonLayout` a lze jej použít následovně:
```xml
<configuration>
    <!-- ... -->
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <target>System.out</target>
        <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
            <level>INFO</level>
        </filter>
        <encoder class="ch.qos.logback.core.encoder.LayoutWrappingEncoder">
            <layout class="io.evitadb.server.log.AppLogJsonLayout"/>
        </encoder>
    </appender>
    <!-- ... -->
</configuration>
```

## Readiness a liveness probe

Server evitaDB poskytuje endpointy pro Kubernetes [readiness a liveness probe](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/). Liveness probe je také
ve výchozím nastavení nakonfigurována jako [healthcheck](https://docs.docker.com/reference/dockerfile/#healthcheck) v našem Docker image. Výchozí healthcheck čeká `30s` před tím,
než začne kontrolovat zdraví serveru; pro větší databáze může být potřeba tuto hodnotu zvýšit pomocí proměnné prostředí
`HEALTHCHECK_START_DELAY`, aby měly dostatek času na načtení do paměti.

<Note type="warning">

<NoteTitle toggles="false">

##### Pokud změníte port systémového API, nezapomeňte nastavit proměnnou prostředí `SYSTEM_API_PORT`
</NoteTitle>

Healthcheck v Docker image je nakonfigurován pro výchozí port systémového API, což je `5555`. Pokud port změníte,
healthcheck okamžitě nahlásí nefunkční kontejner, protože nebude schopen dosáhnout na endpoint probe.
Musíte zadat nový port pomocí proměnné prostředí `SYSTEM_API_PORT` v Docker kontejneru.

</Note>

Obě probe jsou dostupné v `system` API a jsou přístupné na následujících endpointech:

### Readiness probe

```shell
curl -k "http://localhost:5555/system/readiness" \
     -H 'Content-Type: application/json'
```

Probe vrátí `200 OK`, pokud je server připraven přijímat provoz, jinak vrátí `503 Service Unavailable`.
Probe interně volá všechna povolená API přes HTTP na straně serveru, aby ověřila, zda jsou připravena obsluhovat provoz.
Příklad odpovědi:

```json
{
  "status": "READY",
  "apis": {
	"rest": "ready",
	"system": "ready",
	"graphQL": "ready",
	"lab": "ready",
	"observability": "ready",
	"gRPC": "ready"
  }
}
```

Celkový status může být jednou z následujících konstant:

<dl>
    <dt>STARTING</dt>
    <dd>Alespoň jedno API ještě není připraveno.</dd>
    <dt>READY</dt>
    <dd>Server je připraven obsluhovat provoz.</dd>
    <dt>STALLING</dt>
    <dd>Alespoň jedno API, které bylo připraveno, už připraveno není.</dd>
    <dt>SHUTDOWN</dt>
    <dd>Server se vypíná. Žádné z API není připraveno.</dd>
</dl>

Každé z povolených API má svůj vlastní status, takže můžete vidět, které konkrétní API není připraveno v případě statusu `STARTING`
nebo `STALLING`.

### Liveness probe

```shell
curl -k "http://localhost:5555/system/liveness" \
     -H 'Content-Type: application/json'
```

Pokud je server zdravý, probe vrátí `200 OK`. Jinak vrátí `503 Service Unavailable`.
Příklad odpovědi:

```json
{
  "status": "healthy",
  "problems": []
}
```

Pokud je server nezdravý, odpověď vypíše seznam problémů.

<dl>
    <dt>MEMORY_SHORTAGE</dt>
    <dd>Signalizováno, když spotřebovaná paměť nikdy neklesne pod 85 % maximální velikosti heapu a GC se pokusí uvolnit
    old generation alespoň jednou. To vede k opakovaným pokusům o náročný GC staré generace a zatěžuje CPU hostitele.</dd>
    <dt>INPUT_QUEUES_OVERLOADED</dt>
    <dd>Signalizováno, když jsou vstupní fronty plné a server není schopen zpracovávat příchozí požadavky. Problém
	je hlášen, když je poměr odmítnutých úloh k přijatým >= 2. Tento příznak je odstraněn, když poměr odmítnutí
	klesne pod stanovený práh, což signalizuje, že server je opět schopen zpracovávat požadavky.</dd>
    <dt>JAVA_INTERNAL_ERRORS</dt>
    <dd>Signalizováno, když dojde k interním chybám Javy. Tyto chyby jsou obvykle způsobeny samotným serverem
    a nesouvisejí s požadavky klienta. Java chyby signalizují fatální problémy uvnitř JVM.</dd>
    <dt>EXTERNAL_API_UNAVAILABLE</dt>
    <dd>Signalizováno, když readiness probe signalizuje, že alespoň jedno externí API, které je nakonfigurováno jako povolené,
	neodpovídá na interní HTTP kontrolu.</dd>
</dl>

## Metriky

Server evitaDB může publikovat [metriky](https://en.wikipedia.org/wiki/Observability_(software)#Metrics).
Jako populární řešení bylo zvoleno [Prometheus](https://prometheus.io/), aby byly metriky dostupné
mimo aplikaci. evitaDB vystavuje endpoint pro scraping, na který aplikace pravidelně publikuje nasbírané metriky,
které lze následně vizualizovat pomocí libovolného nástroje, například [Grafana](https://grafana.com/).

Prometheus nabízí 4 typy metrik, které mohou být publikovány z aplikací, více v oficiální [dokumentaci](https://prometheus.io/docs/concepts/metric_types/):

- Counter: kumulativní metrika, která reprezentuje monotónně rostoucí čítač, jehož hodnota může pouze růst nebo být
  resetována na nulu při startu.
- Gauge: reprezentuje jedinou číselnou hodnotu, která může libovolně růst i klesat.
- Histogram: vzorkuje pozorování (obvykle např. délky požadavků nebo velikosti odpovědí) a počítá je do
  konfigurovatelných bucketů. Také poskytuje součet všech pozorovaných hodnot.
- Summary: Podobně jako histogram, summary vzorkuje pozorování (obvykle délky požadavků a velikosti odpovědí).
  Kromě celkového počtu pozorování a součtu všech hodnot počítá konfigurovatelné kvantily v klouzavém časovém okně.

Databázový server vystavuje dva typy metrik:

- Metriky týkající se JVM: umožňují vizualizovat důležité systémové informace, které nám dávají přehled např. o stavu JVM,
  jako je využití CPU a paměti, počet vláken, nebo aktuální stav Garbage Collectoru.
- Interní metriky evitaDB: mají přímou vazbu na stav databáze, její data, indexy, rychlost zpracování dotazů atd.

### Nastavení Prometheus endpointu

Pro sběr metrik a jejich publikaci na scrape endpoint není třeba dělat nic jiného, než mít
*observability* API povolené v konfiguraci evitaDB – toto je výchozí chování. Lze také nastavit cestu k YAML
souboru, který může omezit, jaké metriky se skutečně sbírají. Bez jeho specifikace (nebo s prázdným souborem)
jsou automaticky sbírány všechny metriky z obou skupin. Metriky jsou pak dostupné na URL:
*http://[evita-server-name]:5555/observability/metrics*.

Níže je ukázka příslušné části konfiguračního souboru týkající se metrik.

```yaml
api:
  endpoints:
    observability:
      enabled: ${api.endpoints.observability.enabled:true}
      host: ${api.endpoints.observability.host:":5555"}
      exposeOn: ${api.endpoints.observability.exposeOn:"localhost:5555"}
      tlsMode: ${api.endpoints.observability.tlsMode:FORCE_NO_TLS}
      allowedEvents: !include ${api.endpoints.observability.allowedEvents:null}
```

Jak bylo zmíněno výše, lze specifikovat samostatné skupiny z obou skupin metrik (systémové – JVM, interní – databázové)
pro omezení sbíraných dat. Z kategorie JVM (
více [zde](https://prometheus.github.io/client_java/instrumentation/jvm/)), lze publikované metriky omezit
vyjmenováním vybraných názvů v YAML poli:

- `AllMetrics`
- `JvmThreadsMetrics`
- `JvmBufferPoolMetrics`
- `JvmClassLoadingMetrics`
- `JvmCompilationMetrics`
- `JvmGarbageCollectorMetrics`
- `JvmMemoryPoolAllocationMetrics`
- `JvmMemoryMetrics`
- `JvmRuntimeInfoMetric`
- `ProcessMetrics`

Z interní sekce metrik lze metriky také omezit pomocí *wildcard* vzoru ve spojení s Java package.
Lze tedy specifikovat název balíčku, který obsahuje metriky, které chceme povolit, se sufixem ".*",
což povolí sběr všech událostí v této kategorii (balíčku). Je také možné specifikovat jednotlivé
metriky uvedením plného názvu jejich třídy (*package_path.class_name*).

Interní metriky jsou zdokumentovány v sekci [Reference metrik](#referenční-dokumentace).

### Export štítků dotazů do Promethea

[Štítky dotazů](../query/header/label.md) jsou ve výchozím stavu viditelné pouze ve tracech, záznamech provozu a JFR událostech –
ne v Prometheu – protože hodnoty štítků jsou libovolná data dodaná klientem a často neomezená (viz
[poznámky k bezpečnosti kardinality štítků](../query/header/label.md#kardinalita-štítků-a-export-do-prometheus)). Můžete
vybrané názvy štítků povolit jako Prometheus dimenze na metrikách dotazů jejich vyjmenováním v konfiguraci `exportedQueryLabels`
(`job_name` a `rest_method` níže jsou pouze příklady – názvy jsou zcela na vás):

```yaml
api:
  endpoints:
    observability:
      enabled: ${api.endpoints.observability.enabled:true}
      host: ${api.endpoints.observability.host:":5555"}
      exposeOn: ${api.endpoints.observability.exposeOn:"localhost:5555"}
      tlsMode: ${api.endpoints.observability.tlsMode:FORCE_NO_TLS}
      exportedQueryLabels:
        - job_name
        - rest_method
```

evitaDB nerezervuje žádné názvy štítků – každý nakonfigurovaný název je jednoduše porovnán s tím, jaké štítky dotaz nese, a
stane se dimenzí metriky pojmenovanou podle své Prometheus-sanitizované podoby (znaky mimo `[a-zA-Z0-9_]` se stanou `_`). Dotaz,
který nenese nakonfigurovaný štítek, zobrazí tuto dimenzi jako `N/A`.

Na rozdíl od `allowedEvents` znamená nenastavený nebo prázdný `exportedQueryLabels`, že se *nic* neexportuje – toto je bezpečné výchozí nastavení,
protože neomezený štítek povýšený na dimenzi by způsobil explozi kardinality časových řad v Prometheu. Uvedení názvu
zde je vaším výslovným potvrzením, že jeho hodnoty jsou omezené. Několik inherentně vysoce-kardinalitních štítků připojených
automaticky systémem – `trace-id`, `client-id`, `ip-address` a `uri` – je rezervováno a nikdy nemůže být exportováno;
server se nespustí s jasnou chybou, pokud se některý z nich (nebo dva názvy, které se sanitizují na stejnou dimenzi) objeví
v seznamu.

Dvě další poznámky k exportovaným štítkům dotazů:
- Nakonfigurovaný název, jehož sanitizovaná podoba koliduje s vestavěnou dimenzí metriky (pro metriky dotazů
  `entityType` nebo `prefetched`), je odmítnut při registraci metrik krátce po startu – toto je zalogováno a
  sběr metrik se nespustí, místo aby se přerušil start serveru, jak to dělají dvě výše zmíněné ochrany konfigurace.
- Hodnoty se čtou z čárkou/rovná se odděleného seznamu, proto se vyhněte čárkám (`,`) nebo znakům rovná se (`=`)
  uvnitř hodnot exportovaných štítků; udržujte je omezené a jednoduché, jak to vyžaduje bezpečnost kardinality.

### JFR události

[JFR události](https://docs.oracle.com/javacomponents/jmc-5-4/jfr-runtime-guide/about.htm#JFRUH170) mohou být také
spojeny s metrikami, které slouží pro lokální diagnostiku Java aplikací a mohou poskytnout hlubší vhled do jejich fungování.
Na rozdíl od metrik, které publikují různé typy dat (counter, gauge, ...), JFR (Java Flight Recorder)
reaguje na vyvolání některé z událostí cílených aktuálním záznamem. Obvykle je žádoucí
spustit streamovací záznam, který sbírá data o všech registrovaných událostech během běhu, a lze jej uložit do souboru
po ukončení záznamu. S JFR lze také zjistit, jak dlouho trvalo zpracování sledovaných událostí nebo kolikrát byla každá událost
vyvolána během sledovaného času. Tyto interní události, které v případě evitaDB
sdílí třídu s Prometheus metrikami, nesou pomocné informace (název katalogu, parametry dotazu atd.), které mohou pomoci
při řešení složitějších problémů a hledání výkonových úzkých míst. Mimo jiné jsou samozřejmě v souvislosti s těmito daty
ukládány možné stack trace.

V evitaDB byl tento koncept integrován do zmíněného Observability API (URL: */observability/*), na kterém
existují tyto endpointy pro ovládání JFR:

#### Kontrola, zda běží záznam

Protože může běžet pouze jeden záznam najednou, je nutné ověřit, zda je záznam aktuálně spuštěn.
To lze provést voláním následujícího endpointu:

```shell
curl -k "http://localhost:5555/observability/checkRecording" \
     -H 'Content-Type: application/json'
```

Odpověď bude prázdná, pokud není aktivní žádný JFR záznam. Pokud je aktivní záznam, odpověď
vrátí JSON objekt reprezentující úlohu:

```json
{
  "taskType": "JfrRecorderTask",
  "taskName": "JFR recording",
  "taskId": "e36303cd-9e20-4e51-8972-2b98c9945dd4",
  "catalogName": null,
  "issued": "2024-07-22T17:20:28.157+02:00",
  "started": "2024-07-22T17:20:28.16+02:00",
  "finished": null,
  "progress": 0,
  "settings": {
    "allowedEvents": [
      "io.evitadb.query",
      "MemoryAllocation"
    ],
    "maxSizeInBytes": null,
    "maxAgeInSeconds": null
  },
  "result": null,
  "publicExceptionMessage": null,
  "exceptionWithStackTrace": null
}
```

#### Výpis skupin událostí pro zařazení do záznamu

Pro spuštění záznamu JFR událostí musíte určit, které skupiny JFR událostí chcete do záznamu zahrnout.
Všechny dostupné skupiny událostí lze vypsat voláním následujícího endpointu:

```shell
curl -k "http://localhost:5555/observability/getRecordingEventTypes" \
     -H 'Content-Type: application/json'
```

A obdržíte seznam všech dostupných skupin událostí:

```json
[
  {
    "id": "io.evitadb.cache",
    "name": "evitaDB - Cache",
    "description": "evitaDB události týkající se interní cache databáze."
  },
  {
    "id": "io.evitadb.externalApi.graphql.instance",
    "name": "evitaDB - GraphQL API",
    "description": "evitaDB události týkající se GraphQL API."
  },
  ... a další ...
]
```

Vlastnost `id` je identifikátor skupiny, který musíte zadat při spouštění záznamu.
Ostatní vlastnosti pouze popisují skupinu.

#### Spuštění záznamu

Záznam se spouští voláním následujícího endpointu:

```shell
curl -k -X POST "http://localhost:5555/observability/startRecording" \
     -H 'Content-Type: application/json' \
     -d '{
           "allowedEvents": [
             "io.evitadb.query",
             "MemoryAllocation"
           ]
         }'
```

Vlastnost `allowedEvents` je pole ID skupin událostí, které chcete do záznamu zahrnout.

<Note type="info">

Najednou může běžet pouze jeden záznam. Pokud se pokusíte spustit nový záznam, zatímco jiný běží,
server vrátí chybu.

</Note>

#### Zastavení záznamu

Záznam lze zastavit voláním následujícího endpointu:

```shell
curl -k -X POST "http://localhost:5555/observability/stopRecording" \
     -H 'Content-Type: application/json'
```

A dostanete podobnou odpověď jako při kontrole, zda záznam běží:

```json
{
  "taskType": "JfrRecorderTask",
  "taskName": "JFR recording",
  "taskId": "e36303cd-9e20-4e51-8972-2b98c9945dd4",
  "catalogName": null,
  "issued": "2024-07-22T17:20:28.157+02:00",
  "started": "2024-07-22T17:20:28.16+02:00",
  "finished": "2024-07-22T17:28:00.729+02:00",
  "progress": 100,
  "settings": {
    "allowedEvents": [
      "io.evitadb.query",
      "MemoryAllocation"
    ],
    "maxSizeInBytes": null,
    "maxAgeInSeconds": null
  },
  "result": {
    "fileId": "45cee61c-a233-4c36-ad3c-fa2325434bf6",
    "name": "jfr_recording_2024-07-22T17-20-28.157316739-02-00.jfr",
    "description": "JFR záznam spuštěn 2024-07-22T17:20:28.157619773+02:00 s událostmi: [io.evitadb.query, MemoryAllocation].",
    "contentType": "application/octet-stream",
    "totalSizeInBytes": 180281,
    "created": "2024-07-22T17:20:28.158+02:00",
    "origin": [
      "JfrRecorderTask"
    ]
  },
  "publicExceptionMessage": null,
  "exceptionWithStackTrace": null
}
```

Hlavní rozdíl je v tom, že výsledek úlohy JFR záznamu obsahuje soubor, který si můžete stáhnout ze serveru nebo
najít přímo ve složce exportu serveru. Soubor je automaticky odstraněn po uplynutí nastavené doby.

<Note type="info">

Protože vytvořené JFR soubory jsou binární a tedy přímo nečitelné (JDK nabízí terminálový nástroj *JMC*,
ale ten není ideální z hlediska čitelnosti a orientace ve výstupu), plánujeme přidat vizualizér do evitaLab.

</Note>

## Tracing

Jako další nástroj pro podporu observability nabízí evitaDB podporu pro tracing, který je zde implementován
pomocí [OpenTelemetry](https://opentelemetry.io/).
Umožňuje sbírat užitečné informace o všech dotazech do databáze v rámci požadavku provedeného přes libovolné externí
API. Tato data jsou exportována z databáze pomocí
[OTLP exporteru](https://opentelemetry.io/docs/specs/otel/protocol/exporter/) a dále předávána
[OpenTelemetry collectoru](https://opentelemetry.io/docs/collector/). Ten může data třídit, redukovat a předávat
dalším aplikacím, které je mohou vizualizovat (například [Grafana](https://grafana.com/)
pomocí modulu [Tempo](https://grafana.com/oss/tempo/), [Jaeger](https://www.jaegertracing.io/) atd.). Pro maximalizaci
efektu tracování lze použít také tzv. distribuovaný tracing, kdy se budou sbírat a předávat nejen data z evitaDB
související s prováděnými požadavky, ale i data ze spotřebitelských aplikací komunikujících s databází přes API.

Publikované informace (`spans` pomocí jejich `spanId`) lze agregovat podle stejného `traceId`, přičemž span je
v terminologii [OpenTelemetry](https://opentelemetry.io/) jeden konkrétní zaznamenaný úsek informací z
libovolné aplikace s metadaty o jeho zpracování, jako je délka provádění dané akce a další vlastní atributy. `Span`
může mít volitelně rodičovský span (`parent span`), který se stará o zpracování (
rozhodovací procesy o zachování nebo vyřazení částí stromu `span`) a propagaci výsledných rozhodnutí z
rodičovských `span` na jejich potomky (`child spans`).

### Nastavení tracingu v evitaDB

Stejně jako u metrik vyžaduje tracing povolené a nakonfigurované *observability* API v konfiguraci evitaDB,
což je výchozí stav. Pro jeho konfiguraci je potřeba zadat URL
[OpenTelemetry collectoru](https://opentelemetry.io/docs/collector/) a také protokol (HTTP, GRPC), přes který budou data
odesílána.

```yaml
Observability:
  enabled: ${api.endpoints.observability.enabled:true}
  host: ${api.endpoints.observability.host:":5555"}
  exposeOn: ${api.endpoints.observability.exposeOn:"localhost:5555"}
  tlsMode: ${api.endpoints.observability.tlsMode:FORCE_NO_TLS}
  tracing:
    serviceName: ${api.endpoints.observability.tracing.serviceName:evitaDB}
    endpoint: ${api.endpoints.observability.tracing.endpoint:null}
    protocol: ${api.endpoints.observability.tracing.protocol:grpc}
```

<LS to="j,c">

Při použití evitaDB driverů je možné předat instanci [OpenTelemetry](https://opentelemetry.io/), která je
použita ve spotřebitelské aplikaci, do konfigurační třídy driveru. Tato instance může být v aplikaci pouze jedna,
a protože drivery jsou pouze knihovny určené pro použití z aplikací, byla zvolena tato cesta místo
rekonfigurace endpointu [OpenTelemetry collectoru](https://opentelemetry.io/docs/collector/), což je v souladu
s přístupem ostatních autoinstrumentačních knihoven.

</LS>

### Propojení spotřebitelských aplikací

[OpenTelemetry](https://opentelemetry.io/) nabízí oficiální knihovny (pro vybrané technologie s možností
autoinstrumentace ve spotřebitelských aplikacích) pro tracing relevantních částí, jako je HTTP komunikace nebo
databázové operace. Pro většinu aplikací využívajících buď implicitní podporu, nebo doplňkové knihovny, není potřeba
žádná další konfigurace pro povolení tracingu, včetně automatického předávání do dalších služeb. Výše zmíněný
automatizovaný přístup však omezuje možnosti přidání vlastních trace, pro které je potřeba použít tracing SDK
a manuálně je vytvořit a integrovat s dalšími informacemi do stromu trace. Pro evitaDB zatím taková
knihovna není a proto je potřeba ručně nakonfigurovat [OpenTelemetry](https://opentelemetry.io/) včetně
zmíněného [OTLP exporteru](https://opentelemetry.io/docs/specs/otel/protocol/exporter/) a nastavit
[Propagaci kontextu](https://opentelemetry.io/docs/concepts/context-propagation/) – pro podporované technologie najdete
návody na oficiálních stránkách [OpenTelemetry](https://opentelemetry.io/).

Oficiální knihovny nabízejí metody `inject` a `extract`
na [Context](https://opentelemetry.io/docs/specs/otel/context/), které lze použít k nastavení (nebo získání) identifikátorů o
aktuálním kontextu do otevřeného spojení transportní vrstvy (HTTP, gRPC atd.). Tento přístup je silně
integrován do [OpenTelemetry](https://opentelemetry.io/) a je kompatibilní napříč všemi podporovanými technologiemi,
kde je použití této knihovny velmi podobné a tedy snadné integrovat do více služeb.

Interně [OpenTelemetry](https://opentelemetry.io/) používá hodnotu `traceparent` pro propagaci kontextu napříč službami, která může vypadat
například takto: `00-d4cda95b652f4a1592b449d5929fda1b-6e0c63257de34c92-01`.

Ta se skládá ze čtyř částí:

- **00:** představuje verzi `TraceContext`, dnes je tato hodnota neměnná,
- **d4cda95b652f4a1592b449d5929fda1b:** `traceId`,
- **6e0c63257de34c92:** `spanId` – případně může reprezentovat `parent-span-id`,
- **01:** rozhodnutí o vzorkování, tj. zda má být tento span a jeho potomci publikováni (`01` znamená, že span bude
  publikován, `00` znamená, že nebude).

Dostupné SDK poskytují možnosti pro získání aktuálního tracingového kontextu obsahujícího `traceId` a `spanId` napříč
aplikací, často jsou metody na třídě Context pojmenovány `current` nebo `active`.

<LS to="r,g">

Pro propojení aplikací využívajících REST a GraphQL API je nutné posílat HTTP hlavičku `traceparent` v rámci nějaké
otevřené HTTP komunikace do evitaDB. Výše zmíněné metody `inject` a `extract` vkládají hodnotu `traceparent` do
HTTP hlavičky (nebo ji z hlavičky získávají z aktuálního kontextu).

</LS>

<LS to="j,c">

Pro propojení aplikací využívajících gRPC API je nutné posílat metadata v rámci dotazů přes gRPC kanál do
evitaDB. Naším doporučeným způsobem je v tuto chvíli použít gRPC [Interceptor](https://grpc.io/docs/guides/interceptors/).
Výše zmíněné metody `inject` a `extract` vkládají hodnotu `traceparent` do gRPC Metadata (nebo ji získávají
z hlavičky aktuálního kontextu).

</LS>

<LS to="j">

evitaDB poskytuje dvě základní metody pro účely tracingu na rozhraní `TracingContext`, které je použito jak
interně, tak z externích API. Tyto metody zahrnují:

- executeWithinBlock: vytvoří rodičovský `span`, který, včetně všech dalších exportovaných trace uvnitř předané lambda
  funkce, bude implicitně zalogován a odeslán do
  [OpenTelemetry collectoru](https://opentelemetry.io/docs/collector/),
- executeWithinBlockIfParentContextAvailable: metoda pro tracing, pokud je aktuálně otevřen rodičovský kontext (záměrně
  jsou takto potlačeny některé interní trace, které nepocházejí z externích API).

</LS>

#### Zařazení `traceId` do logů

Trace identifikátory lze také použít ke skupinování aplikačních logovacích zpráv podle trace a span pro snadnější
ladění chyb, které nastaly během konkrétního požadavku. To je realizováno pomocí podpory [MDC](https://www.slf4j.org/manual.html#mdc).
evitaDB předává trace a span identifikátory do MDC kontextu pod názvy `traceId` a `spanId`.

Konkrétní použití závisí na použité implementaci logovací fasády [SLF4J](https://www.slf4j.org/). Například
v [Logback](https://logback.qos.ch/index.html) lze použít vzory `%X{traceId}` a `%X{spanId}` v logovacím patternu:

```xml
<encoder>
	<pattern>%d{HH:mm:ss.SSS} %-5level %logger{10} C:%X{traceId} R:%X{spanId} - %msg%n</pattern>
</encoder>
```

## Traffic recording

Kromě výše zmíněných nástrojů pro observabilitu nabízí evitaDB také možnost zaznamenávat veškerý příchozí provoz
na server. Tato funkce je užitečná pro ladění a vývoj, protože umožňuje přehrát zaznamenaný provoz a analyzovat
chování serveru do detailu. Funkce traffic recording je ve výchozím stavu vypnuta a je nutné ji povolit v serverové
[konfiguraci](configure.md#konfigurace-záznamu-provozu).

Tato nastavení jsou doporučena pro lokální vývoj:

```yaml
  trafficRecording:
    enabled: true
    sourceQueryTracking: true      
    trafficFlushIntervalInMilliseconds: 0
```

Pro testovací/staging prostředí vynechte `trafficFlushIntervalInMilliseconds` a ponechte výchozí hodnotu. Pokud povolíte
zaznamenávání provozu v produkci, vypněte `sourceQueryTracking`, protože v produkci obvykle nepotřebujete přístup ke zdrojovému kódu dotazu.
V produkci pravděpodobně nastavíte sampling rate pomocí `trafficSamplingPercentage`.

Kromě přístupu na záložku `Active Traffic Recording` v evitaLab, kde můžete procházet všechny session, dotazy, mutace a entity,
můžete také spustit úlohu traffic reporting, která uloží data provozu do ZIP souboru a zpřístupní jej ke stažení. Tento soubor lze použít
pro další analýzu nebo pro přehrání provozu na jiné instanci evitaDB.

Zaznamenaný provoz lze v evitaLab procházet a filtrovat a jakýkoli dotaz lze snadno spustit v příslušném
konzoli dotazů na aktuálním datasetu. Záznamy lze také filtrovat podle vlastních [štítků](../query/header/label.md#štítek),
traceId nebo typů protokolů. Snadno tak můžete izolovat sady záznamů provozu, které se vztahují k jednomu obchodnímu případu,
například k jednomu vykreslení stránky nebo jednomu API volání.

### Export záznamu provozu na vyžádání

Výše popsaná úloha traffic reporting *spustí* nový záznam a streamuje jej do ZIP souboru v reálném čase. Pokud
potřebujete provoz, který je *již* bufferován – tedy aktuální okno, které server uchovává na disku bez ohledu na to,
zda běží reporting úloha – použijte export na vyžádání. Ten pořídí konzistentní, jednorázový snapshot aktuálního bufferu
a zapíše jej do stahovatelného ZIP souboru bez spuštění, zastavení nebo jiného přerušení živého záznamu.

Export se spouští přes gRPC traffic-recording API (`ExportTrafficRecording` na `GrpcEvitaTrafficRecordingService`),
volitelně s předáním cílové velikosti chunk-souboru v bajtech; bufferované session jsou rozděleny do několika souborů
`traffic_recording_*.bin` přibližně této velikosti v archivu. Výsledný ZIP je získán jako každý jiný exportní soubor serveru,
přes API pro stahování souborů (`ListFilesToFetch` pro jeho nalezení, `FetchFile` pro stažení), a je automaticky odstraněn
ze složky exportu serveru po uplynutí nastavené doby uchování.

Každý `.bin` chunk obsahuje surové, nezměněné záznamy session, které lze přehrát na jiné instanci evitaDB. Archiv
také obsahuje `metadata.txt` shrnující běh; kromě časových a bajtových součtů sdílených se streamovacím reportem,
on-demand export navíc uvádí, kolik session bylo `skipped` (vysunuto z bufferu nebo zamčeno pro zápis živým rekordérem během snapshotu –
takové session jsou započítány, nikdy částečně emitovány) a kolik session snapshot celkem obsahoval. Vygenerovaný soubor je označen
původem exportní úlohy (`TrafficRecordingExportTask`, na rozdíl od streamovacího reportu `TrafficRecorderTask`), takže klient
listující soubory přes `ListFilesToFetch` může odlišit on-demand exporty od streamovaných záznamů.

## Change data capture

Change data capture umožňuje sledovat změny, které se dějí v databázi v téměř reálném čase. Můžete se přihlásit k odběru změn
týkajících se schématu katalogu nebo změn dat u entit ve specifických katalozích. Můžete také sledovat top-level
engine mutace jako je vytvoření nebo odstranění katalogu. Funkce je podporována ve všech vašich API – včetně webových,
jako jsou gRPC, REST a GraphQL. Více detailů o této funkci najdete v kapitole [Change data capture](../use/api/capture-changes.md).

## Referenční dokumentace

<MDInclude>[Java Flight Recorder události](/documentation/user/cs/operate/reference/jfr-events.md)</MDInclude>

<MDInclude>[Metriky](/documentation/user/cs/operate/reference/metrics.md)</MDInclude>