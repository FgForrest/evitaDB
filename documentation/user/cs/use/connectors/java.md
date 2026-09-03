---
title: Java
perex: Java API je nativní rozhraní pro komunikaci s evitaDB. Umožňuje spouštět evitaDB jako embedded databázi nebo se připojit k vzdálenému databázovému serveru. Je navrženo tak, aby sdílelo společná rozhraní pro oba scénáře, což vám umožňuje přepínat mezi embedded a vzdálenou databází bez nutnosti měnit váš kód. To je obzvláště užitečné během vývoje nebo jednotkového testování, kdy můžete použít embedded databázi a v produkci přejít na vzdálenou databázi.
date: '26.10.2023'
author: Ing. Jan Novotný
preferredLang: java
translated: 'true'
commit: '86bd2ba279d36c05731249a31ee12a0782e9b713'
---
<LS to="e,c,g,r">
Tato kapitola popisuje Java driver pro evitaDB a nedává smysl pro jiné jazyky. Pokud vás zajímají detaily implementace Java driveru, změňte prosím preferovaný jazyk v pravém horním rohu.
</LS>
<LS to="j">
Spuštění evitaDB v embedded režimu je podrobně popsáno v kapitole [Spuštění evitaDB](../../get-started/run-evitadb.md?lang=java).
Připojení ke vzdálené instanci databáze je popsáno v kapitole [Připojení ke vzdálené databázi](../../get-started/query-our-dataset.md?lang=java).
Totéž platí pro [query API](../api/query-data.md?lang=java) a [write API](../api/write-data.md?lang=java).
Žádné z těchto témat tedy zde nebudou pokryta.

## Java remote client

Pro použití Java remote clienta stačí přidat následující závislost do vašeho projektu:

<CodeTabs>
<CodeTabsBlock>
```Maven
<dependency>
    <groupId>io.evitadb</groupId>
    <artifactId>evita_java_driver</artifactId>
    <version>2026.1.0</version>
</dependency>
```
</CodeTabsBlock>
<CodeTabsBlock>
```Gradle
implementation 'io.evitadb:evita_java_driver:2026.1.0'
```
</CodeTabsBlock>
</CodeTabs>

Java remote client staví na [gRPC API](grpc.md). <SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass>
je thread-safe a očekává se, že v aplikaci bude použita pouze jedna instance. Klient interně spravuje
pool gRPC spojení pro paralelní komunikaci se serverem.

<Note type="info">
Instance klienta je vytvořena bez ohledu na to, zda je server dostupný. Pro ověření, že je server dosažitelný, je potřeba zavolat na něm nějakou metodu. Obvyklým scénářem je [otevření nové session](../../get-started/create-first-database.md?lang=java#otevřete-relaci-ke-katalogu-a-vložte-svou-první-entitu) ke stávajícímu <Term location="/documentation/user/en/index.md">katalogu</Term>.
</Note>

<Note type="warning">
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass>
udržuje pool otevřených zdrojů a měl by být ukončen metodou `close()`, když jej přestanete používat.
</Note>

### Konfigurace

Minimální konfigurace vyžaduje pouze host a port serveru:

```java
final EvitaClient evita = new EvitaClient(
	EvitaClientConfiguration.builder()
		.host("localhost")
		.port(5555)
		.build()
);
```

Pokročilejší příklad s TLS a nastavením timeoutů využívá ploché nastavení spojení v kombinaci se skupinovými TLS a
timeout možnostmi:

```java
final EvitaClient evita = new EvitaClient(
	EvitaClientConfiguration.builder()
		.host("server.example.com")
		.port(5555)
		.tls(
			ClientTlsOptions.builder()
				.useGeneratedCertificate(false)
				.serverCertificatePath(Path.of("/certs/server.crt"))
				.build()
		)
		.timeouts(
			ClientTimeoutOptions.builder()
				.timeout(10, TimeUnit.SECONDS)
				.streamingTimeout(30, TimeUnit.MINUTES)
				.build()
		)
		.retry(true)
		.build()
);
```

Plná konfigurace je k dispozici v
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/config/EvitaClientConfiguration.java</SourceClass>.
Následující sekce popisují všechny dostupné možnosti uspořádané podle skupin konfigurace.

#### Možnosti spojení

Nastavení spojení se konfiguruje přes
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/config/ClientConnectionOptions.java</SourceClass>:

<dl>
    <dt>clientId</dt>
    <dd>
        <p>**Výchozí: `gRPC client at hostname`**</p>
        <p>
          Tato vlastnost umožňuje rozlišit požadavky tohoto konkrétního klienta od požadavků ostatních klientů.
          Tato informace může být použita v logu nebo při [troubleshootingu](../api/troubleshoot.md).
        </p>
    </dd>
    <dt>host</dt>
    <dd>
        <p>**Výchozí: `localhost`**</p>
        <p>Identifikace serveru, na kterém běží evitaDB. Může to být název hostitele nebo IP adresa.</p>
    </dd>
    <dt>port</dt>
    <dd>
        <p>**Výchozí: `5555`**</p>
        <p>Identifikace portu serveru, na kterém běží evitaDB.</p>
    </dd>
    <dt>systemApiPort</dt>
    <dd>
        <p>**Výchozí: `5555`**</p>
        <p>Identifikace portu serveru, na kterém běží system API evitaDB. System API slouží k automatickému nastavení klientského certifikátu pro mTLS nebo ke stažení self-signed certifikátu serveru.
        Viz [Konfigurace a principy TLS](../../operate/tls.md). System API není potřeba, pokud server používá důvěryhodný certifikát a mTLS je vypnuté, nebo pokud je privátní/veřejný klíč serveru/klienta distribuován "ručně" s klientem.</p>
    </dd>
    <dt>pingIntervalMillis</dt>
    <dd>
        <p>**Výchozí: `30000` (30 s)**</p>
        <p>Interval HTTP/2 keep-alive PING v milisekundách. Pokud spojení nevidí žádný provoz po tuto dobu, klient odešle PING; pokud protistrana neodpoví během stejného intervalu, spojení je uzavřeno. Interval je tedy *stall budget* — musí pohodlně přesahovat nejhorší tolerovatelnou GC/CPU pauzu, neslouží jako frekvence sondování, jinak může být pomalý, ale živý požadavek ukončen uprostřed běhu; proto je interval 30 s místo kratší sondovací frekvence. Nastavte `0` pro úplné vypnutí pingu klienta (spojení je pak ukončeno pouze `idleTimeoutMillis`); jakákoli jiná hodnota musí být alespoň `1000` ms. Ping musí být také přísně menší než `idleTimeoutMillis`, jinak jej podkladový HTTP klient tiše vypne — výchozí dvojice (`30000` ping, `300000` idle) toto splňuje a klient vypíše varování, pokud vlastní dvojice tuto podmínku nesplní. Samostatně musí být také menší než *serverův* vlastní `idleTimeoutInMillis` (viz [API konfigurace](../../operate/configure.md#konfigurace-api), výchozí 60 s), jinak server ukončí spojení dříve, než naplánovaný ping stihne proběhnout — výchozí hodnota serveru je zvolena právě s ohledem na tento 30 s výchozí ping klienta.</p>
    </dd>
    <dt>idleTimeoutMillis</dt>
    <dd>
        <p>**Výchozí: `300000` (300 s)**</p>
        <p>Jak dlouho může pooled spojení zůstat bez aplikačního provozu, než bude uzavřeno. Toto je záměrně **odděleno od per-call `timeout`** (viz [Timeout možnosti](#timeout-možnosti)): krátký deadline požadavku nesmí způsobit, že se fyzické spojení mezi požadavky znovu naváže. Výchozí hodnota 300 s je dostatečně nad 30 s pingem, takže watchdog keep-alive zůstává aktivní a zdravá spojení jsou udržována — potvrzené pingy se počítají jako aktivita, takže živé spojení nikdy nevyprší, zatímco mrtvá protistrana je detekována během jednoho ping intervalu. Nastavte `0` pro úplné vypnutí idle timeoutu (spojení pak žije, dokud jej neukončí protistrana, chyba pingu nebo pool spojení). Hodnota musí být přísně vyšší než `pingIntervalMillis`.</p>
    </dd>
</dl>

#### TLS možnosti

Nastavení TLS a certifikátů se konfiguruje přes
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/config/ClientTlsOptions.java</SourceClass>:

<dl>
    <dt>tlsEnabled</dt>
    <dd>
        <p>**Výchozí: `true`**</p>
        <p>Pokud je nastaveno na `true`, klient použije pro komunikaci se serverem TLS šifrování. Pokud je nastaveno na `false`, klient použije HTTP/2 bez TLS šifrování. Odpovídající nastavení musí být na straně serveru.</p>
    </dd>
    <dt>mtlsEnabled</dt>
    <dd>
        <p>**Výchozí: `false`**</p>
        <p>Pokud je nastaveno na `true`, klient a server použijí vzájemnou TLS autentizaci. Klient se musí správně identifikovat pomocí páru veřejného/soukromého klíče, který server zná a důvěřuje mu, aby bylo možné navázat spojení. Viz [Konfigurace a principy TLS](../../operate/tls.md).</p>
    </dd>
    <dt>useGeneratedCertificate</dt>
    <dd>
        <p>**Výchozí: `true`**</p>
        <p>Pokud je nastaveno na `true`, klient automaticky stáhne root certifikát serverové CA z endpointu `system`. Pokud je nastaveno na `false`, klient očekává, že root certifikát bude poskytnut ručně přes vlastnost `serverCertificatePath`.</p>
    </dd>
    <dt>trustCertificate</dt>
    <dd>
        <p>**Výchozí: `false`**</p>
        <p>Pokud je nastaveno na `true`, certifikát získaný z endpointu `system` nebo ručně přes `serverCertificatePath` je automaticky přidán do lokálního trust store. Pokud je nastaveno na `false` a je poskytnut nedůvěryhodný (self-signed) certifikát, nebude klientem důvěřován a spojení se serverem selže. Použití `true` v produkci se obecně nedoporučuje.</p>
    </dd>
    <dt>serverCertificatePath</dt>
    <dd>
        <p>**Výchozí: `null`**</p>
        <p>Relativní cesta k certifikátu serveru. Musí být poskytnuta, pokud jsou vypnuty příznaky `useGeneratedCertificate` a `trustCertificate` a server používá nedůvěryhodný certifikát (například self-signed). Pokud je příznak `useGeneratedCertificate` vypnutý, je nutné nastavit cestu k ručně poskytnutému certifikátu, jinak ověřovací proces selže a spojení nebude navázáno.</p>
    </dd>
    <dt>certificateFolderPath</dt>
    <dd>
        <p>**Výchozí: `evita-client-certificates`**</p>
        <p>Relativní cesta ke složce, kde bude umístěn klientský certifikát a privátní klíč, nebo pokud tam již nejsou, budou staženy. V druhém případě bude použita výchozí cesta v temp složce.</p>
    </dd>
    <dt>certificateFileName</dt>
    <dd>
        <p>**Výchozí: `null`**</p>
        <p>Relativní cesta z `certificateFolderPath` ke klientskému certifikátu. Musí být nastavena, pokud je mTLS povolen a `useGeneratedCertificate` je nastaveno na `false`.</p>
    </dd>
    <dt>certificateKeyFileName</dt>
    <dd>
        <p>**Výchozí: `null`**</p>
        <p>Relativní cesta z `certificateFolderPath` k privátnímu klíči klienta. Musí být nastavena, pokud je mTLS povolen a `useGeneratedCertificate` je nastaveno na `false`.</p>
    </dd>
    <dt>certificateKeyPassword</dt>
    <dd>
        <p>**Výchozí: `null`**</p>
        <p>Heslo k privátnímu klíči klienta (pokud je nastaveno). Musí být nastaveno, pokud je mTLS povolen a `useGeneratedCertificate` je nastaveno na `false`.</p>
    </dd>
    <dt>trustStorePassword</dt>
    <dd>
        <p>**Výchozí: `trustStorePassword`**</p>
        <p>Heslo k trust store, který slouží k ukládání certifikátů serveru. Používá se, pokud je `trustCertificate` nastaveno na `true`.</p>
    </dd>
</dl>

<Note type="warning">
Pokud je na straně serveru povoleno `mTLS` a `useGeneratedCertificate` je nastaveno na `false`, musíte v nastavení `certificateFileName` a `certificateKeyFileName` poskytnout svůj ručně vygenerovaný certifikát, jinak ověřovací proces selže a spojení nebude navázáno.
</Note>

#### Timeout možnosti

Nastavení timeoutů se konfiguruje přes
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/config/ClientTimeoutOptions.java</SourceClass>:

<dl>
    <dt>timeout</dt>
    <dd>
        <p>**Výchozí: `5`**</p>
        <p>Počet časových jednotek `timeoutUnit`, které má klient čekat na odpověď serveru před vyhozením výjimky nebo násilným ukončením spojení.</p>
    </dd>
    <dt>timeoutUnit</dt>
    <dd>
        <p>**Výchozí: `TimeUnit.SECONDS`**</p>
        <p>Časová jednotka pro vlastnost `timeout`.</p>
    </dd>
    <dt>streamingTimeout</dt>
    <dd>
        <p>**Výchozí: `300`**</p>
        <p>Počet časových jednotek `streamingTimeoutUnit`, které má klient čekat, než server pošle další zprávu ve streamu, než stream zruší.</p>
    </dd>
    <dt>streamingTimeoutUnit</dt>
    <dd>
        <p>**Výchozí: `TimeUnit.SECONDS`**</p>
        <p>Časová jednotka pro vlastnost `streamingTimeout`.</p>
    </dd>
</dl>

#### Ostatní možnosti

Následující možnosti se konfigurují přímo na
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/config/EvitaClientConfiguration.java</SourceClass>:

<dl>
    <dt>threadPool</dt>
    <dd>
        <p>**Výchozí: `ThreadPoolOptions.clientThreadPoolBuilder().build()`**</p>
        <p>Definuje limity pro klientský thread pool používaný pro asynchronní operace jako je správa session a background úkoly. Thread pool je konfigurován pomocí
        <SourceClass>evita_api/src/main/java/io/evitadb/api/configuration/ThreadPoolOptions.java</SourceClass>
        s následujícími vlastnostmi:</p>
        <ul>
            <li>`minThreadCount` (výchozí: `0`) - minimální počet vláken udržovaných v poolu</li>
            <li>`maxThreadCount` (výchozí: `availableProcessors * 4`, minimálně `4`) - maximální počet vláken</li>
            <li>`threadPriority` (výchozí: `5`) - priorita vlákna (1-10)</li>
            <li>`queueSize` (výchozí: `100`) - maximální počet úkolů čekajících ve frontě</li>
        </ul>
    </dd>
    <dt>reflectionLookupBehaviour</dt>
    <dd>
        <p>**Výchozí: `CACHE`**</p>
        <p>Chování třídy <SourceClass>evita_common/src/main/java/io/evitadb/utils/ReflectionLookup.java</SourceClass> analyzující třídy pro reflexní informace. Řídí, zda mají být jednou analyzované reflexní informace cachovány, nebo pokaždé znovu (a nákladně) získávány.</p>
    </dd>
    <dt>openTelemetryInstance</dt>
    <dd>
        <p>**Výchozí: `null`**</p>
        <p>Instance OpenTelemetry, která má být použita pro tracing. Pokud je nastavena na `null`, tracing nebude prováděn.</p>
    </dd>
    <dt>retry</dt>
    <dd>
        <p>**Výchozí: `false`**</p>
        <p>Zda je aktivní širší sada pravidel pro opakování požadavků, která může vést k duplicitám: timeouty, stavy `503`/`504`/`UNKNOWN` a back-off pro `429`. Tyto situace mohou odpovídat požadavku, který server již zpracoval (například mutace, jejíž odpověď byla ztracena kvůli přerušení přenosu), proto je tato možnost volitelná. Nezávisle na tomto příznaku je požadavek, o kterém Armeria může dokázat, že nikdy nedorazil na server (odmítnuté spojení, nebo GOAWAY přijaté před přijetím streamu požadavku), vždy automaticky opakován s backoffem, omezený per-call timeoutem, protože jeho opakování nikdy nemůže duplikovat již provedenou mutaci.</p>
    </dd>
    <dt>trackedTaskLimit</dt>
    <dd>
        <p>**Výchozí: `100`**</p>
        <p>Maximální počet serverových úkolů, které může klient sledovat. Pokud je limit dosažen, klient přestane sledovat nejstarší úkoly.</p>
    </dd>
    <dt>changeCaptureQueueSize</dt>
    <dd>
        <p>**Výchozí: `Flow.defaultBufferSize()`**</p>
        <p>Maximální počet událostí zachycení změn, které mohou být vyrovnány pro každého odběratele.
        Pokud je tento limit dosažen, je odběrateli nahlášena chyba.</p>
    </dd>
</dl>

### Caching schémat

Oba typy schémat, katalogová i entitní, jsou používány velmi často – každá načtená entita má referenci na své schéma. Zároveň je schéma poměrně složité a často se nemění. Proto je výhodné schéma na straně klienta cachovat a vyhnout se opakovanému stahování ze serveru při každé potřebě.

Cache je spravována třídou <SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaEntitySchemaCache.java</SourceClass>,
která řeší dva scénáře přístupu ke schématu:

#### Přístup k posledním verzím schémat

Klient udržuje poslední známé verze schémat pro každý katalog. Tato cache je invalidována pokaždé, když daný klient schéma změní, kolekce je přejmenována nebo smazána, nebo když klient načte entitu, která používá novější verzi schématu, než je poslední cachovaná verze.

#### Přístup ke konkrétním verzím schémat

Klient také udržuje cache konkrétních verzí schémat. Pokaždé, když klient načte entitu, entita vrácená ze serveru obsahuje informaci o verzi schématu, na kterou se odkazuje. Klient se pokusí najít schéma této konkrétní verze ve své cache, a pokud jej nenajde, stáhne jej ze serveru a uloží do cache. Cache je čas od času invalidována (každou minutu) a stará schémata, která nebyla dlouho použita (4 hodiny), jsou odstraněna.

<Note type="info">

Výše uvedené intervaly nejsou aktuálně konfigurovatelné, protože věříme, že jsou optimální pro většinu případů použití. Pokud potřebujete jejich změnu, kontaktujte nás prosím se svým konkrétním případem a zvážíme přidání konfigurační možnosti.

</Note>

## Vlastní kontrakty

Java API obsahuje pouze dvě formy rozhraní datového modelu:

1. <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/EntityReferenceContract.java</SourceClass>
   které reprezentuje odlehčenou formu entity obsahující pouze její primární klíč a typ entity
2. <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/SealedEntity.java</SourceClass>
   které reprezentuje částečnou nebo kompletní formu entity s jejími daty

Obě jsou platné a snadno použitelné datové struktury, ale ani jedna nemluví jazykem vašeho business domény. Vývojáři
obecně preferují práci s vlastními doménovými objekty, což chápeme. Jejich aplikace by obvykle obalila modelové třídy evitaDB do svých doménových objektů, což by vyžadovalo zdlouhavou ruční práci.

Abychom tento proces usnadnili, vytvořili jsme API pro vlastní kontrakty, které vám umožní definovat vlastní doménové objekty a mapovat je na entity evitaDB. Modelové objekty lze použít jak pro definici schémat entit, tak pro čtení a zápis entit z/do databáze. Vlastní kontrakty využívají knihovny [ByteBuddy](https://bytebuddy.net/#/) a [Proxycian](https://github.com/FgForrest/Proxycian)
pro vytváření dynamických proxy vašich doménových objektů. S tím je spojen malý výkonový overhead, který je však zanedbatelný ve srovnání s časem stráveným komunikací s databází. API je volitelné a lze jej používat paralelně se standardním API.

### Požadavky za běhu

API pro vlastní kontrakty používá pod kapotou Java proxy, což vyžaduje přítomnost knihovny [Proxycian](https://github.com/FgForrest/Proxycian)
na classpath za běhu. Protože je API volitelné, nechtěli jsme zvětšovat evitaDB JAR o knihovnu Proxycian. Pokud však vývojář chce použít API pro vlastní kontrakty, je třeba přidat Proxycian jako závislost:

```xml
<dependency>
  <groupId>one.edee.oss</groupId>
  <artifactId>proxycian_bytebuddy</artifactId>
  <version>1.4.0</version>
</dependency>
```

a pokud aplikace používá [Java Moduly](https://www.oracle.com/corporate/features/understanding-java-9-modules.html),
je nutné použít parametr `--add-modules`

```shell
--add-modules proxycian.bytebuddy
```

### Definice schématu

Definice schématu se provádí anotováním doménového objektu anotacemi z balíčku <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation</SourceClass> a je
podrobně popsána v [kapitole o schema API](../api/schema-api.md#deklarativní-definice-schématu).

### Načítání entit

Entitu ve formě vlastního kontraktu lze načíst z databáze pomocí dedikovaných metod na rozhraní
<SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass>:

<SourceCodeTabs requires="/documentation/user/en/use/connectors/examples/selective-imports.java,/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java,documentation/user/en/use/api/example/declarative-schema-definition.java" langSpecificTabOnly local>

[Načtení entity pomocí vlastního rozhraní](/documentation/user/en/use/connectors/examples/custom-contract-reading.java)

</SourceCodeTabs>

<Note type="info">

Příklad pracuje se stejnou definicí produktu jako [příklad v kapitole o schema API](../api/schema-api.md#deklarativní-definice-schématu)
<SourceClass>/documentation/user/en/use/api/example/declarative-model-example.java</SourceClass>.

</Note>

Načítání entit pouze pro čtení je podrobně popsáno v [kapitole o read API](../api/query-data.md#vlastní-kontrakty).

### Zápis entit

Entitu ve formě vlastního kontraktu lze zapsat do databáze pomocí dedikovaných metod na rozhraní
<SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass>:

<SourceCodeTabs requires="/documentation/user/en/use/connectors/examples/selective-imports.java,/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java,documentation/user/en/use/api/example/declarative-schema-definition.java" langSpecificTabOnly local>

[Zápis entity pomocí vlastního rozhraní](/documentation/user/en/use/connectors/examples/custom-contract-writing.java)

</SourceCodeTabs>

Zápis dat pomocí vlastních kontraktů je podrobně popsán v [kapitole o write API](../api/write-data.md#vlastní-kontrakty).

### Doporučení pro modelování dat

Můžete definovat jedno rozhraní jak pro čtení, tak pro zápis dat v evitaDB. Nicméně doporučujeme oddělit rozhraní pro čtení a zápis a používat pro tyto účely různé instance datových objektů. Jinými slovy, řídit se podobnými principy, na kterých je postavena i samotná evitaDB. Ačkoli se to může na začátku zdát složitější, v dlouhodobém horizontu se to vyplatí. Důvody tohoto přístupu jsou:

1. instance pro čtení zůstávají neměnné a mohou být bezpečně sdíleny mezi vlákny a cachovány ve sdílené paměti
2. rozhraní pro čtení není zaneseno metodami, které nejsou potřeba pro čtení dat, a zůstává čisté a jednoduché.

Tento princip nazýváme "sealed/open" a funguje následovně:

#### 1. definujte pouze pro čtení rozhraní

Definujete rozhraní nebo třídu s finálními poli, která jsou inicializována v konstruktoru:

<SourceCodeTabs requires="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Sealed instance ve vlastním rozhraní](/documentation/user/en/use/connectors/examples/sealed-instance-example.java)

</SourceCodeTabs>

Jak vidíte, rozhraní vypadá přesně jako [příklad v kapitole o Schema API](../api/schema-api.md#deklarativní-definice-schématu)
s tím rozdílem, že tato verze rozšiřuje rozhraní <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/SealedInstance.java</SourceClass>.
Deklarace signalizuje, že `<READ_INTERFACE>` je rozhraní `Product` a `<WRITE_INTERFACE>` je rozhraní `ProductEditor`.

<Note type="info">

Očekáváme, že rozhraní pro čtení bude použito jak pro čtení vašich dat, tak pro definici struktury schématu. Je dobrým zvykem mít definici schématu a rozhraní pro přístup k datům na jednom místě.

</Note>

#### 2. definujte rozhraní pro zápis

Poté definujete samostatné rozhraní pro úpravu dat:

<SourceCodeTabs requires="/documentation/user/en/use/connectors/examples/sealed-instance-example.java" langSpecificTabOnly local>

[Instance editor ve vlastním rozhraní](/documentation/user/en/use/connectors/examples/instance-editor-example.java)

</SourceCodeTabs>

Všimněte si, že toto rozhraní rozšiřuje rozhraní `Product` a přidává metody pro úpravu dat. Také rozšiřuje rozhraní <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/InstanceEditor.java</SourceClass>
a specifikuje, že `<READ_INTERFACE>` je rozhraní `Product`.

#### 3. využijte princip sealed/open

Nyní můžeme výše popsaná rozhraní použít následujícím způsobem:

<SourceCodeTabs requires="/documentation/user/en/use/connectors/examples/custom-contract-writing.java" langSpecificTabOnly local>

[Otevření sealed rozhraní](/documentation/user/en/use/connectors/examples/sealed-open-lifecycle-example.java)

</SourceCodeTabs>

Princip sealed/open je o něco složitější než naivní přístup s jedním rozhraním pro čtení i zápis dat, ale jasně odděluje scénáře čtení a zápisu, což vám umožňuje udržet kontrolu nad mutacemi a jejich viditelností v multithreadovém prostředí.
</LS>