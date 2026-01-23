---
title: Spuštění
perex: Pokud chcete provozovat evitaDB jako samostatnou službu na svém serveru, můžete použít Docker. Tato kapitola popisuje, jak spustit evitaDB v Dockeru a jak ji nakonfigurovat.
date: '17.1.2023'
author: Ing. Jan Novotný
proofreading: done
commit: cef96d8320d36c91c100c5dfc9c45020b5a7ad0d
---
Docker image je založen na RedHat JDK / Linux (viz <SourceClass>docker/Dockerfile</SourceClass>) základním
image (rodina Fedora) a je publikován na [Docker Hub](https://hub.docker.com/repository/docker/evitadb/evitadb/general).

### Instalace Dockeru

Než začneme, musíte si nainstalovat Docker. Návod pro vaši platformu najdete v
[dokumentaci Dockeru](https://docs.docker.com/get-docker/).

### Stažení a spuštění image

Jakmile máte Docker nainstalovaný, potřebujete stáhnout image evitaDB z
[Docker Hubu](https://hub.docker.com/repository/docker/evitadb/evitadb/general) a vytvořit kontejner.
Obojí můžete provést jedním příkazem pomocí `docker run`. Toto je nejjednodušší způsob, jak spustit evitaDB pro testovací účely:

```shell
# Varianta pro Linux: běh v popředí, zničení kontejneru po ukončení, použití portů hostitele bez NAT
docker run --name evitadb -i --rm --net=host \       
       index.docker.io/evitadb/evitadb:latest

# Windows / MacOS: je zde otevřený problém https://github.com/docker/roadmap/issues/238
# a je třeba ručně otevřít porty a propagovat IP adresu hostitele do kontejneru
docker run --name evitadb -i --rm -p 5555:5555 \       
       index.docker.io/evitadb/evitadb:latest
```

Po spuštění serveru evitaDB byste měli v konzoli vidět následující informace:

```plain
            _ _        ____  ____
  _____   _(_) |_ __ _|  _ \| __ )
 / _ \ \ / / | __/ _` | | | |  _ \
|  __/\ V /| | || (_| | |_| | |_) |
 \___| \_/ |_|\__\__,_|____/|____/

You'll see some version here
Visit us at: https://evitadb.io

Root CA Certificate fingerprint:        You'll see some fingerprint here
API `graphQL` listening on              https://your-server:5555/gql/
API `rest` listening on                 https://your-server:5555/rest/
API `gRPC` listening on                 https://your-server:5555/
API `system` listening on               http://your-server:5555/system/
```

<Note type="info">

<NoteTitle toggles="true">

##### Co znamenají argumenty příkazu?
</NoteTitle>

<Table>
    <Thead>
        <Tr>
            <Th>Argument</Th>
            <Th>Popis</Th>
        </Tr>
    </Thead>
    <Tbody>
        <Tr>
            <Td>`--name`</Td>
            <Td>
                přiřadí kontejneru jméno `evitadb`.

                pokud kontejneru jméno nedáte, Docker mu vygeneruje náhodné jméno.
            </Td>
        </Tr>
        <Tr>
            <Td>`-i`</Td>
            <Td>
                ponechá STDIN otevřený – kontejner poběží v popředí a uvidíte jeho standardní/chybový výstup v konzoli.
                Kontejner můžete zastavit odesláním signálu do terminálu (obvykle klávesová kombinace `Ctrl`+`C`, nebo `Command`+`.` na MacOS).
            </Td>
        </Tr>
        <Tr>
            <Td>`--rm`</Td>
            <Td>
                odstraní souborový systém (data) vytvořený evitaDB, po ukončení evitaDB nezůstane na vašem systému nic,
                tento argument je zvláště užitečný pro testovací účely.
            </Td>
        </Tr>
        <Tr>
            <Td>`--net=host`</Td>
            <Td>
            instruuje Docker, aby použil přímo síťový stack hostitelského systému, tímto způsobem se evitaDB chová,
            jako by běžela přímo na síti hostitele. Pokud je port nastavený v konfiguraci evitaDB již na systému obsazen,
            Evita se nedokáže nastavit příslušné webové API (viz další kapitola pro přemapování portů nebo
            [konfigurace evitaDB](../operate/configure.md) pro specifikaci otevřených portů).
            </Td>
        </Tr>
    </Tbody>
</Table>
</Note>

#### Otevření / přemapování portů

Zjednodušený příkaz sdílí síť s hostitelem, což není vždy nejlepší řešení. Můžete selektivně
otevírat/přemapovávat porty otevřené uvnitř Docker kontejneru následujícím způsobem:

```shell
# běh v popředí, zničení kontejneru po ukončení, přesné mapování portů hostitele
docker run --name evitadb -i --rm \
        -p 5555:5555 \              
        index.docker.io/evitadb/evitadb:latest
```

<Note type="info">

Argument `-e "EVITA_ARGS=api.endpointDefaults.exposeOn=localhost"` by měl být použit, pokud je evitaLab a/nebo Open API 
schéma generováno a používáno z hostitelského systému, na kterém běží Docker kontejner. Tento argument není nutný, 
pokud kontejner sdílí síť s hostitelem pomocí argumentu `--net=host`. Argument říká evitaDB běžící v kontejneru, 
aby pro generované URL ve schématech a síťové požadavky evitaLab použila jako doménu `localhost`. Jinak by použila
vnitřní hostname kontejneru, který není z hostitelského systému dostupný.

</Note>

<Note type="info">

<NoteTitle toggles="true">

##### Co znamenají argumenty příkazu?
</NoteTitle>

<Table>
    <Thead>
        <Tr>
            <Th>Argument</Th>
            <Th>Popis</Th>
        </Tr>
    </Thead>
    <Tbody>
        <Tr>
            <Td>`-p`</Td>
            <Td>
            přemapování portů ve formátu `port hostitele`:`port kontejneru`, takže můžete přemapovat výchozí porty
            otevřené evitaDB uvnitř kontejneru na jiné porty hostitelského systému.
            </Td>
        </Tr>
    </Tbody>
</Table>
</Note>

### Nastavení perzistentního úložiště databáze

Pro běžné použití budete pravděpodobně chtít určit složku pro ukládání dat evitaDB a snadno k nim přistupovat
ve struktuře souborového systému hostitele. Můžete zadat libovolnou (původně) prázdnou složku hostitele pro ukládání
databázových souborů evitaDB:

```shell
## běh v popředí, použití portů hostitele bez NAT, vlastní datový adresář
docker run -i --net=host \
-v "__data_dir__:/evita/data" \
index.docker.io/evitadb/evitadb:latest
```

<Note type="info">
Musíte nahradit `__data_dir__` cestou ke složce na vašem hostitelském systému.
</Note>

Složka se začne plnit daty, jakmile vytvoříte první katalogy a kolekce entit. Organizace
složky bude vypadat takto:

```plain
├── catalogA
├── catalogB
└── catalogC
```

Každá složka bude obsahovat jeden nebo více souborů představujících obsah katalogu.

<Note type="question">

<NoteTitle toggles="true">

##### Chcete vědět více o DB souborech, zálohování a obnově?
</NoteTitle>

Více informací o struktuře složek a obsahu souborů je popsáno v kapitole
[zálohování a obnova](../operate/backup-restore.md).

</Note>

### Konfigurace evitaDB v kontejneru

Všechna nastavení evitaDB v kontejneru můžete ovládat pomocí proměnných prostředí zadaných v příkazu `run`:

```shell
## běh interaktivně, použití portů hostitele bez NAT, vlastní datový adresář a další konfigurační možnosti
docker run --name evitadb -i --net=host \
-v "__data_dir__:/evita/data" \
-v "__certificate_dir__:/evita/certificates" \
-e "EVITA_JAVA_OPTS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5000" \
-e "EVITA_ARGS=api.endpoints.graphQL.enabled=false api.endpoints.grpc.enabled=false" \
index.docker.io/evitadb/evitadb:latest
```

<Note type="info">
Ukázkový příkaz výše spustí evitaDB a otevře Java debug na portu `5000`. Také zakáže GraphQL a gRPC webová API
a ponechá pouze REST API (ve výchozím nastavení evitaDB spouští všechna dostupná API).
</Note>

Můžete využít všechny následující proměnné:

<Table caption="Seznam všech konfigurovatelných proměnných prostředí">
    <Thead>
        <Tr>
            <Th>Název proměnné</Th>
            <Th>Význam</Th>
        </Tr>
    </Thead>
    <Tbody>
        <Tr>
            <Td>**`EVITA_CONFIG_FILE`**</Td>
            <Td>Cesta ke konfiguračnímu souboru, výchozí: `/evita/conf/evita-configuration.yaml`</Td>
        </Tr>
        <Tr>
            <Td>**`EVITA_STRICT_CONFIG_FILE_CHECK`**</Td>
            <Td>Volitelný příznak, který může nastavit validaci konfiguračního souboru na přísnou. Výchozí: `false`</Td>
        </Tr>
        <Tr>
            <Td>**`EVITA_STORAGE_DIR`**</Td>
            <Td>Cesta k adresáři pro úložiště, výchozí: `/evita/data`</Td>
        </Tr>
        <Tr>
            <Td>**`EVITA_CERTIFICATE_DIR`**</Td>
            <Td>Cesta k adresáři s automaticky generovanými serverovými certifikáty. Výchozí: `/evita/certificates`</Td>
        </Tr>
        <Tr>
            <Td>**`EVITA_JAVA_OPTS`**</Td>
            <Td>Java příkazové argumenty
            (seznam základních argumentů [najdete zde](https://docs.oracle.com/en/java/javase/17/docs/specs/man/java.html#overview-of-java-options)),
            výchozí: žádné (prázdný řetězec)</Td>
        </Tr>
        <Tr>
            <Td>**`EVITA_ARGS`**</Td>
            <Td>
            Příkazové argumenty serveru evitaDB, výchozí: žádné (prázdný řetězec)

            seznam všech dostupných argumentů je uveden v konfiguračním souboru:
            <SourceClass>evita_server/src/main/resources/evita-configuration.yaml</SourceClass>
            formát argumentu je viditelný ve tvaru proměnných `${argument_name:default_value}`

            pro předání argumentu Java aplikaci je třeba jej předřadit `-D`, správný název argumentu pro
            proměnnou `${storage.lockTimeoutSeconds:50}` je `-Dstorage.lockTimeoutSeconds=90`
            </Td>
        </Tr>
    </Tbody>
</Table>

<Note type="info">

<NoteTitle toggles="true">

##### Alternativní způsob konfigurace evitaDB
</NoteTitle>

Celý konfigurační YAML soubor můžete také poskytnout pomocí speciálního svazku tímto způsobem:

</Note>

```shell
## běh interaktivně, zničení kontejneru po ukončení, použití portů hostitele bez NAT, vlastní datový adresář a konfigurační soubor
docker run --name evitadb -i --net=host \
-v "__config_file__:/evita/conf/evita-configuration.yaml" \
-v "__data_dir__:/evita/data" \
-v "__certificate_dir__:/evita/certificates" \
index.docker.io/evitadb/evitadb:latest
```

Musíte nahradit `__config_file__` cestou k YAML souboru a `__data_dir__`, `__certificate_dir__` existujícími složkami
na hostitelském systému.

<Note type="info">

Obsah by měl odpovídat výchozímu konfiguračnímu souboru
<SourceClass>evita_server/src/main/resources/evita-configuration.yaml</SourceClass>, ale v některých nastaveních můžete
uvádět konstanty místo proměnných.

</Note>

### Kontrola stavu kontejneru

Stav kontejneru můžete zkontrolovat spuštěním příkazu `docker ps`, a uvidíte podobný výstup:

```shell
host@username:~ $ docker ps
CONTAINER ID   IMAGE                    COMMAND            CREATED         STATUS         PORTS     NAMES
0e4483f9c32e   evitadb/evitadb:latest   "/entrypoint.sh"   7 seconds ago   Up 6 seconds             evitadb
```

<Note type="info">

Pokud používáte síťový stack hostitele (`--net=host`), v tomto výstupu neuvidíte žádné porty. Pokud použijete [přemapování/otevření portů](#otevření--přemapování-portů),
uvidíte zde také konfiguraci portů.

</Note>

### Kontrola stavů API

Pro kontrolu stavu všech povolených API použijte příkaz `curl` a naši [readiness probe](./observe.md#readiness-probe).
Tato sonda ověřuje, že API jsou připravena obsluhovat požadavky prostřednictvím interních HTTP volání a vrací jejich stav v jedné odpovědi.

Stavy GraphQL a REST API můžete také zkontrolovat ručně pomocí příkazu `curl`.

#### GraphQL

Pro GraphQL API spusťte (platí pro výchozí konfiguraci evitaDB):
```shell
curl -k -X POST "https://localhost:5555/gql/system" \
  -H 'Content-Type: application/json' \
  -d '{"query":"{liveness}"}'
```
to by mělo vrátit následující potvrzení o stavu liveness GraphQL API:
```json
{"data":{"liveness":true}}
```

#### REST

Pro REST API spusťte (platí pro výchozí konfiguraci evitaDB):
```shell
curl -k "https://localhost:5555/rest/system/liveness" \
  -H 'Content-Type: application/json'
```
to by mělo vrátit následující potvrzení o stavu liveness REST API:
```json
{"liveness":true}
```

### Řízení logování

evitaDB používá [Slf4j](https://www.slf4j.org/) logovací fasádu s implementací [Logback](https://logback.qos.ch/), ale
můžete ji libovolně změnit. Výchozí konfigurace logbacku je definována v souboru
<SourceClass>evita_server/src/main/resources/META-INF/logback.xml</SourceClass>.

Výchozí konfiguraci logbacku můžete zcela přepsat poskytnutím vlastního
[logback konfiguračního souboru](https://logback.qos.ch/manual/configuration.html#syntax) ve svazku:

```shell
## běh interaktivně, zničení kontejneru po ukončení, použití portů hostitele bez NAT, vlastní datový adresář a konfigurační soubor
docker run --name evitadb -i --net=host \
-v "__config_file__:/evita/conf/evita-configuration.yaml" \
-v "__data_dir__:/evita/data" \
-v "__certificate_dir__:/evita/certificates" \
-v "__path_to_log_file__:/evita/logback.xml" \
index.docker.io/evitadb/evitadb:latest
```

Musíte nahradit `__path_to_log_file__` cestou ke svému logback konfiguračnímu souboru.

### Restartování existujícího kontejneru

Běžící (a pojmenovaný) kontejner lze zastavit a znovu spustit pomocí následujících příkazů:

```shell
# restart běžícího kontejneru
docker restart evitadb
```

Případně můžete použít `docker ps` pro získání ID běžícího kontejneru a restartovat jej pomocí
[krátkého UUID identifikátoru](https://docs.docker.com/engine/reference/run/#name---name):

```shell
docker restart b0c7b140c6a7
```

### Docker Compose

Pokud chcete používat evitaDB v orchestrace s dalšími službami nebo vlastní Dockerizovanou aplikací, můžete
evitaDB použít v Docker compose souboru. Základní konfigurace může vypadat takto:

```yaml
version: "3.7"
services:
  evita:
    image: index.docker.io/evitadb/evitadb:latest
    environment:
      - EVITA_JAVA_OPTS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5000
    volumes:
      - ./path/toYourDataDirectory:/evita/data
      - ./path/toYourCertificateDirectory:/evita/certificates
    ports:
      - 5000:5000
      - 5555:5555
```

Všechny dříve dokumentované možnosti použití Dockeru platí i pro Docker Compose:

- použijte [proměnné prostředí](#konfigurace-evitadb-v-kontejneru) pro konfiguraci evitaDB
- použijte [svazky](#nastavení-perzistentního-úložiště-databáze) pro nastavení datové složky
- použijte [porty](#otevření--přemapování-portů) pro mapování portů v docker kompozici