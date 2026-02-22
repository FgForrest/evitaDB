---
title: Spusťte evitaDB
perex: Pokud jste v evitaDB noví, vyzkoušejte tyto jednoduché kroky, abyste si zprovoznili vlastní server.
date: '1.3.2023'
author: Ing. Jan Novotný
proofreading: done
preferredLang: java
commit: '726d58606ce657f9de645077ee4cd695b39f73e0'
translated: true
---
evitaDB je [Java aplikace](https://openjdk.org/), kterou můžete spustit jako
[vestavěnou databázi](../use/connectors/java.md) v jakékoli Java aplikaci nebo jako
[samostatnou službu](../operate/run.md) připojenou k aplikacím přes
protokol HTTPS pomocí některého z poskytovaných webových API.

<LS to="j">

<Note type="question">

<NoteTitle toggles="true">

##### Jaké platformy jsou podporovány?
</NoteTitle>

Java aplikace podporují více platforem v závislosti na
[dodavateli JRE/JDK](https://wiki.openjdk.org/display/Build/Supported+Build+Platforms). Jsou podporovány všechny hlavní hardwarové
architektury (x86_64, ARM64) a operační systémy (Linux, MacOS, Windows). Vzhledem k velikosti našeho
týmu pravidelně testujeme evitaDB pouze na platformě Linux AMD64 (kterou můžete použít i na Windows díky
[Windows Linux Subsystem](https://learn.microsoft.com/en-us/windows/wsl/install)). Výkon může být horší
a při spuštění evitaDB v jiných (než Linuxových) prostředích můžete narazit na drobné problémy. Prosím, nahlaste nám jakékoli chyby,
na které narazíte, a pokusíme se je co nejdříve opravit.
</Note>

<Note type="question">

<NoteTitle toggles="true">

##### Jaké jsou výhody a nevýhody spuštění vestavěné evitaDB?
</NoteTitle>

Vestavěná evitaDB bude rychlejší, protože můžete pracovat přímo s datovými objekty načtenými z disku a nemusíte
procházet několika překladovými vrstvami, které jsou nutné pro vzdálený přístup přes API. Můžete také zakázat všechna standardní API
a vyhnout se spuštění vestavěného HTTP serveru, což snižuje zátěž systému.

Nevýhodou je, že halda vaší aplikace bude zahlcena velkými datovými strukturami evitaDB v paměťových indexech,
což ztěžuje hledání úniků paměti ve vaší aplikaci. Doporučujeme používat vestavěnou evitaDB pro
[psaní testů](../use/api/write-tests.md), což výrazně zjednodušuje integrační testování s evitaDB a umožňuje
rychlé a snadné nastavení / odstranění testovacích dat.
</Note>

<Note type="info">
Tento úvodní článek popisuje, jak spustit evitaDB v režimu vestavěné databáze. Pokud dáváte přednost spuštění evitaDB v režimu klient & server,
podívejte se na samostatné kapitoly popisující [jak spustit evitaDB v Dockeru](../operate/run.md) a
[jak nastavit EvitaClient](../use/connectors/java.md).
</Note>

### Zabalte evitaDB do své aplikace

Pro integraci evitaDB do vašeho projektu použijte následující kroky:

<CodeTabs>
<CodeTabsBlock>
```Maven
<dependency>
    <groupId>io.evitadb</groupId>
    <artifactId>evita_db</artifactId>
    <version>2025.8.0</version>
    <type>pom</type>
</dependency>
```
</CodeTabsBlock>
<CodeTabsBlock>
```Gradle
implementation 'io.evitadb:evita_db:2025.8.0'
```
</CodeTabsBlock>
</CodeTabs>

### Spusťte evitaDB server

Pro spuštění evitaDB serveru je potřeba vytvořit instanci <SourceClass>evita_engine/src/main/java/io/evitadb/core/Evita.java</SourceClass>
a uchovat si na ni referenci, aby ji vaše aplikace mohla používat podle potřeby.
<SourceClass>evita_engine/src/main/java/io/evitadb/core/Evita.java</SourceClass> je náročná na prostředky, protože při startu načítá všechny
indexy do paměti.

<SourceCodeTabs local>
[Příklad povolení webového API v Javě](/documentation/user/en/get-started/example/server-startup.java)
</SourceCodeTabs>

<Note type="warning">
Nezapomeňte zajistit, že metoda `close` bude zavolána dříve, než uvolníte referenci na instanci
<SourceClass>evita_engine/src/main/java/io/evitadb/core/Evita.java</SourceClass>. Pokud to neuděláte,
dojde k úniku obslužných rutin souborů a můžete také přijít o aktualizace uložené v cache, což znamená ztrátu
některých posledních změn v databázi.
</Note>

### Povolení webových API evitaDB

Pokud chcete, aby evitaDB mohla otevřít svá webová API (je potřeba [toto nakonfigurovat](../operate/configure.md)),
musíte také přidat závislosti na tyto varianty API. Pokud to neuděláte, dostanete
výjimku <SourceClass>evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/exception/ExternalApiInternalError.java</SourceClass>
při povolení příslušného API v konfiguraci evitaDB.

#### gRPC

<CodeTabs>
<CodeTabsBlock>
```Maven
<dependency>
    <groupId>io.evitadb</groupId>
    <artifactId>evita_external_api_grpc</artifactId>
    <version>2025.8.0</version>
    <type>pom</type>
</dependency>
```
</CodeTabsBlock>
<CodeTabsBlock>
```Gradle
implementation 'io.evitadb:evita_external_api_grpc:2025.8.0'
```
</CodeTabsBlock>
</CodeTabs>

#### GraphQL

<CodeTabs>
<CodeTabsBlock>
```Maven
<dependency>
    <groupId>io.evitadb</groupId>
    <artifactId>evita_external_api_graphql</artifactId>
    <version>2025.8.0</version>
    <type>pom</type>
</dependency>
```
</CodeTabsBlock>
<CodeTabsBlock>
```Gradle
implementation 'io.evitadb:evita_external_api_graphql:2025.8.0'
```
</CodeTabsBlock>
</CodeTabs>

#### REST

<CodeTabs>
<CodeTabsBlock>
```Maven
<dependency>
    <groupId>io.evitadb</groupId>
    <artifactId>evita_external_api_rest</artifactId>
    <version>2025.8.0</version>
    <type>pom</type>
</dependency>
```
</CodeTabsBlock>
<CodeTabsBlock>
```Gradle
implementation 'io.evitadb:evita_external_api_rest:2025.8.0'
```
</CodeTabsBlock>
</CodeTabs>

### Spusťte HTTP server pro webová API

Webová API evitaDB jsou spravována samostatnou třídou <SourceClass>evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/http/ExternalApiServer.java</SourceClass>.
Musíte tuto třídu vytvořit, nakonfigurovat a předat jí referenci na instanci
<SourceClass>evita_engine/src/main/java/io/evitadb/core/Evita.java</SourceClass>:

<SourceCodeTabs requires="/documentation/user/en/get-started/example/server-startup.java" local>
[Příklad spuštění webového API v Javě](/documentation/user/en/get-started/example/api-startup.java)
</SourceCodeTabs>

<Note type="warning">
Nezapomeňte uzavřít API při ukončení vaší aplikace zavoláním metody `close` na
instanci <SourceClass>evita_external_api/evita_external_api_core/src/main/java/io/evitadb/externalApi/http/ExternalApiServer.java</SourceClass>.
Jednou z možností je naslouchat ukončení procesu Java:

<SourceCodeTabs requires="/documentation/user/en/get-started/example/api-startup.java" local>
[Příklad vypnutí webového API v Javě](/documentation/user/en/get-started/example/server-teardown.java)
</SourceCodeTabs>

</Note>

Při spuštění webového serveru API byste měli v konzoli vidět následující informace:

```plain
Root CA Certificate fingerprint:        CERTIFICATE AUTHORITY FINGERPRINT
API `gRPC` listening on                 https://your-domain:5555/
API `graphQL` listening on              https://your-domain:5555/gql/
API `rest` listening on                 https://your-domain:5555/rest/
API `system` listening on               http://your-domain:5555/system/
```

</LS>
<LS to="e,g,r,c">

### Instalace Dockeru

Než začneme, je potřeba nainstalovat Docker. Návod pro vaši platformu najdete v
[dokumentaci Dockeru](https://docs.docker.com/get-docker/).

### Stažení a spuštění image

Jakmile máte Docker nainstalovaný, je potřeba stáhnout image evitaDB z
[Docker Hubu](https://hub.docker.com/repository/docker/evitadb/evitadb/general) a vytvořit kontejner.
Obojí můžete provést jedním příkazem pomocí `docker run`. Toto je nejjednodušší způsob, jak spustit evitaDB pro testovací účely:

```shell
# Varianta pro Linux: běží v popředí, po ukončení se kontejner smaže, používá hostitelské porty bez NAT
docker run --name evitadb -i --rm --net=host \       
       index.docker.io/evitadb/evitadb:latest

# Windows / MacOS: je zde otevřený issue https://github.com/docker/roadmap/issues/238
# a je potřeba ručně otevřít porty a předat IP adresu hostitele do kontejneru
docker run --name evitadb -i --rm -p 5555:5555 \      
       index.docker.io/evitadb/evitadb:latest
```

Po spuštění evitaDB serveru byste měli v konzoli vidět následující informace:

```plain
            _ _        ____  ____
  _____   _(_) |_ __ _|  _ \| __ )
 / _ \ \ / / | __/ _` | | | |  _ \
|  __/\ V /| | || (_| | |_| | |_) |
 \___| \_/ |_|\__\__,_|____/|____/

beta build 2025.8.0 (keep calm and report bugs 😉)
Visit us at: https://evitadb.io

Log config used: META-INF/logback.xml
Server name: evitaDB-a22be76c5dbd8c33
13:40:48.034 INFO  i.e.e.g.GraphQLManager - Built GraphQL API in 0.000002503s
13:40:48.781 INFO  i.e.e.r.RestManager - Built REST API in 0.000000746s
13:40:49.612 INFO  i.e.e.l.LabManager - Built Lab in 0.000000060s
Root CA Certificate fingerprint:        8A:78:A6:ED:E9:D6:83:0F:8D:99:A6:F2:1A:D5:41:B9:12:40:24:67:55:84:2C:4A:65:F7:B5:E7:33:00:35:9C
API `graphQL` listening on              https://localhost:5555/gql/
API `rest` listening on                 https://localhost:5555/rest/
API `gRPC` listening on                 https://localhost:5555/
API `system` listening on               http://localhost:5555/system/
   - server name served at:             http://localhost:5555/system/server-name
   - CA certificate served at:          http://localhost:5555/system/evitaDB-CA-selfSigned.crt
   - server certificate served at:      http://localhost:5555/system/server.crt
   - client certificate served at:      http://localhost:5555/system/client.crt
   - client private key served at:      http://localhost:5555/system/client.key

************************* WARNING!!! *************************
You use mTLS with automatically generated client certificate.
This is not safe for production environments!
Supply the certificate for production manually and set `useGeneratedCertificate` to false.
************************* WARNING!!! *************************

API `lab` listening on                  https://localhost:5555/lab/
```

Více informací o spuštění evitaDB Serveru v Dockeru najdete v [samostatné kapitole](../operate/run.md).

</LS>

## Co dál?

Možná budete chtít [vytvořit svou první databázi](create-first-database.md) nebo si [vyzkoušet náš dataset](query-our-dataset.md).