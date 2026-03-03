---
title: Java
perex: Java API je nativní rozhraní pro komunikaci s evitaDB. Umožňuje spouštět evitaDB jako embedded databázi nebo se připojit k vzdálenému databázovému serveru. Je navrženo tak, aby sdílelo společná rozhraní pro oba scénáře, což vám umožňuje přepínat mezi embedded a vzdáleným režimem bez nutnosti měnit váš kód. To je obzvláště užitečné během vývoje nebo jednotkového testování, kdy můžete používat embedded databázi a v produkci přejít na vzdálenou databázi.
date: '26.10.2023'
author: Ing. Jan Novotný
preferredLang: java
commit: cbb24856cca1d8c0ee870ee47ea05cc39d4e5798
---
<LS to="e,c,g,r">
Tato kapitola popisuje Java driver pro evitaDB a nedává smysl pro jiné jazyky. Pokud vás zajímají detaily implementace Java driveru, změňte prosím preferovaný jazyk v pravém horním rohu.
</LS>
<LS to="j">
Spuštění evitaDB v embedded režimu je detailně popsáno v kapitole [Spuštění evitaDB](../../get-started/run-evitadb.md?lang=java).
Připojení k vzdálené instanci databáze je popsáno v kapitole [Připojení k vzdálené databázi](../../get-started/query-our-dataset.md?lang=java).
Totéž platí pro [query API](../api/query-data.md?lang=java) a [write API](../api/write-data.md?lang=java).
Žádné z těchto témat zde tedy nebudou pokryta.

## Java remote klient

Pro použití Java remote klienta stačí přidat následující závislost do vašeho projektu:

<CodeTabs>
<CodeTabsBlock>
```Maven
<dependency>
    <groupId>io.evitadb</groupId>
    <artifactId>evita_java_driver</artifactId>
    <version>2025.8.0</version>
</dependency>
```
</CodeTabsBlock>
<CodeTabsBlock>
```Gradle
implementation 'io.evitadb:evita_java_driver:2025.8.0'
```
</CodeTabsBlock>
</CodeTabs>

Java remote klient je postaven na [gRPC API](grpc.md). <SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass>
je thread-safe a očekává se, že v aplikaci bude použita pouze jediná instance. Klient interně spravuje
pool gRPC spojení pro zpracování paralelní komunikace se serverem.

<Note type="info">
Instance klienta je vytvořena bez ohledu na to, zda je server dostupný. Pro ověření, že je server dosažitelný, je třeba na něm zavolat nějakou metodu. Obvyklým scénářem je [otevření nové session](../../get-started/create-first-database.md?lang=java#otevřete-relaci-ke-katalogu-a-vložte-svou-první-entitu) do existujícího <Term location="/documentation/user/en/index.md">katalogu</Term>.
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

Pokročilejší příklad s TLS a nastavením timeoutů využívá ploché nastavení spojení v kombinaci se skupinovými TLS a timeout možnostmi:

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

Kompletní konfigurace je dostupná v
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
          Tato informace může být využita v logování nebo při [troubleshootingu](../api/troubleshoot.md).
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
        Viz [Konfigurace a principy TLS](../../operate/tls.md). System API není vyžadováno, pokud server používá důvěryhodný certifikát a mTLS je vypnuté, nebo pokud je privátní/veřejný klíč serveru/klienta distribuován „ručně“ s klientem.</p>
    </dd>
</dl>

#### Možnosti TLS

Nastavení TLS a certifikátů se konfiguruje přes
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/config/ClientTlsOptions.java</SourceClass>:

<dl>
    <dt>tlsEnabled</dt>
    <dd>
        <p>**Výchozí: `true`**</p>
        <p>Při nastavení na `true` bude klient používat TLS šifrování pro komunikaci se serverem. Při nastavení na `false` bude klient používat HTTP/2 bez TLS šifrování. Odpovídající nastavení musí být nastaveno i na straně serveru.</p>
    </dd>
    <dt>mtlsEnabled</dt>
    <dd>
        <p>**Výchozí: `false`**</p>
        <p>Při nastavení na `true` budou klient a server používat vzájemnou TLS autentizaci. Klient se musí správně identifikovat pomocí páru veřejného/soukromého klíče, který je serverem znám a důvěryhodný, aby bylo možné navázat spojení. Viz [Konfigurace a principy TLS](../../operate/tls.md).</p>
    </dd>
    <dt>useGeneratedCertificate</dt>
    <dd>
        <p>**Výchozí: `true`**</p>
        <p>Při nastavení na `true` klient automaticky stáhne root certifikát serverové CA z endpointu `system`. Při nastavení na `false` klient očekává, že root certifikát bude poskytnut ručně pomocí vlastnosti `serverCertificatePath`.</p>
    </dd>
    <dt>trustCertificate</dt>
    <dd>
        <p>**Výchozí: `false`**</p>
        <p>Při nastavení na `true` bude certifikát získaný z endpointu `system` nebo ručně přes `serverCertificatePath` automaticky přidán do lokálního trust store. Pokud je nastaveno na `false` a je poskytnut nedůvěryhodný (self-signed) certifikát, nebude klientem důvěřován a spojení se serverem selže. Použití hodnoty `true` v produkci se obecně nedoporučuje.</p>
    </dd>
    <dt>serverCertificatePath</dt>
    <dd>
        <p>**Výchozí: `null`**</p>
        <p>Relativní cesta k certifikátu serveru. Musí být zadána, pokud je vypnutý příznak `useGeneratedCertificate` a `trustCertificate` a server používá nedůvěryhodný certifikát (například self-signed). Pokud je příznak `useGeneratedCertificate` vypnutý, je nutné nastavit cestu k ručně poskytnutému certifikátu, jinak ověření selže a spojení nebude navázáno.</p>
    </dd>
    <dt>certificateFolderPath</dt>
    <dd>
        <p>**Výchozí: `evita-client-certificates`**</p>
        <p>Relativní cesta ke složce, kde bude umístěn klientský certifikát a privátní klíč, nebo pokud tam ještě nejsou, budou staženy. V druhém případě bude použita výchozí cesta v temp složce.</p>
    </dd>
    <dt>certificateFileName</dt>
    <dd>
        <p>**Výchozí: `null`**</p>
        <p>Relativní cesta od `certificateFolderPath` ke klientskému certifikátu. Musí být nakonfigurováno, pokud je mTLS povolen a `useGeneratedCertificate` je nastaveno na `false`.</p>
    </dd>
    <dt>certificateKeyFileName</dt>
    <dd>
        <p>**Výchozí: `null`**</p>
        <p>Relativní cesta od `certificateFolderPath` k privátnímu klíči klienta. Musí být nakonfigurováno, pokud je mTLS povolen a `useGeneratedCertificate` je nastaveno na `false`.</p>
    </dd>
    <dt>certificateKeyPassword</dt>
    <dd>
        <p>**Výchozí: `null`**</p>
        <p>Heslo k privátnímu klíči klienta (pokud je nastaveno). Musí být nakonfigurováno, pokud je mTLS povolen a `useGeneratedCertificate` je nastaveno na `false`.</p>
    </dd>
    <dt>trustStorePassword</dt>
    <dd>
        <p>**Výchozí: `trustStorePassword`**</p>
        <p>Heslo pro trust store používaný pro ukládání serverových certifikátů. Používá se, když je `trustCertificate` nastaveno na `true`.</p>
    </dd>
</dl>

<Note type="warning">
Pokud je na straně serveru povoleno `mTLS` a `useGeneratedCertificate` je nastaveno na `false`, musíte v nastavení `certificateFileName` a `certificateKeyFileName` poskytnout ručně vygenerovaný certifikát, jinak ověření selže a spojení nebude navázáno.
</Note>

#### Možnosti timeoutu

Nastavení timeoutu se konfiguruje přes
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/config/ClientTimeoutOptions.java</SourceClass>:

<dl>
    <dt>timeout</dt>
    <dd>
        <p>**Výchozí: `5`**</p>
        <p>Počet jednotek `timeoutUnit`, které má klient čekat na odpověď serveru, než vyhodí výjimku nebo násilně ukončí spojení.</p>
    </dd>
    <dt>timeoutUnit</dt>
    <dd>
        <p>**Výchozí: `TimeUnit.SECONDS`**</p>
        <p>Časová jednotka pro vlastnost `timeout`.</p>
    </dd>
    <dt>streamingTimeout</dt>
    <dd>
        <p>**Výchozí: `3600`**</p>
        <p>Počet jednotek `streamingTimeoutUnit`, které má klient čekat na další streamovanou zprávu od serveru, než zruší stream.</p>
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
        <p>Definuje limity pro thread pool na straně klienta, používaný pro asynchronní operace jako práce se session a background úkoly. Thread pool se konfiguruje pomocí
        <SourceClass>evita_api/src/main/java/io/evitadb/api/configuration/ThreadPoolOptions.java</SourceClass>
        s následujícími vlastnostmi:</p>
        <ul>
            <li>`minThreadCount` (výchozí: `0`) - minimální počet vláken udržovaných v poolu</li>
            <li>`maxThreadCount` (výchozí: `availableProcessors * 4`, minimálně `4`) - maximální počet vláken</li>
            <li>`threadPriority` (výchozí: `5`) - priorita vláken (1-10)</li>
            <li>`queueSize` (výchozí: `100`) - maximální počet úkolů čekajících ve frontě</li>
        </ul>
    </dd>
    <dt>reflectionLookupBehaviour</dt>
    <dd>
        <p>**Výchozí: `CACHE`**</p>
        <p>Chování třídy <SourceClass>evita_common/src/main/java/io/evitadb/utils/ReflectionLookup.java</SourceClass> analyzující třídy pro reflexivní informace. Řídí, zda mají být jednou analyzované informace cachovány, nebo pokaždé znovu (a nákladně) získávány.</p>
    </dd>
    <dt>openTelemetryInstance</dt>
    <dd>
        <p>**Výchozí: `null`**</p>
        <p>Instance OpenTelemetry, která má být použita pro tracing. Pokud je nastavena na `null`, tracing nebude prováděn.</p>
    </dd>
    <dt>retry</dt>
    <dd>
        <p>**Výchozí: `false`**</p>
        <p>Zda má klient opakovat volání v případě timeoutu nebo jiných síťových problémů.</p>
    </dd>
    <dt>trackedTaskLimit</dt>
    <dd>
        <p>**Výchozí: `100`**</p>
        <p>Maximální počet serverových úloh, které může klient sledovat. Pokud je limit dosažen, klient přestane sledovat nejstarší úlohy.</p>
    </dd>
    <dt>changeCaptureQueueSize</dt>
    <dd>
        <p>**Výchozí: `Flow.defaultBufferSize()`**</p>
        <p>Maximální počet událostí zachycení změn, které mohou být bufferovány pro každého odběratele.
        Pokud je tento limit dosažen, je odběrateli nahlášena chyba.</p>
    </dd>
</dl>

### Caching schémat

Jak katalogová, tak entitní schémata jsou používána poměrně často – každá získaná entita má referenci na své schéma. Zároveň je schéma poměrně složité a často se nemění. Proto je výhodné schéma na klientovi cachovat a vyhnout se jeho získávání ze serveru při každé potřebě.

Cache je spravována třídou <SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaEntitySchemaCache.java</SourceClass>,
která řeší dva scénáře přístupu ke schématu:

#### Přístup k posledním verzím schémat

Klient udržuje poslední známé verze schémat pro každý katalog. Tato cache je invalidována pokaždé, když konkrétní klient změní schéma, kolekce je přejmenována nebo smazána, nebo když klient získá entitu, která používá novější verzi schématu, než je poslední cachovaná verze entity.

#### Přístup ke konkrétním verzím schémat

Klient také udržuje cache konkrétních verzí schémat. Pokaždé, když klient získá entitu, nese entita ze serveru informaci o verzi schématu, na kterou se odkazuje. Klient se pokusí najít schéma této konkrétní verze ve své cache, a pokud jej nenajde, stáhne jej ze serveru a uloží do cache. Cache je čas od času invalidována (každou minutu) a stará schémata, která nebyla dlouho použita (4 hodiny), jsou odstraněna.

<Note type="info">

Výše uvedené intervaly aktuálně nelze konfigurovat, protože věříme, že jsou optimální pro většinu případů použití. Pokud je potřebujete změnit, kontaktujte nás prosím se svým konkrétním případem a zvážíme přidání konfigurační možnosti.

</Note>

## Vlastní kontrakty

Java API obsahuje pouze dvě formy rozhraní datového modelu:

1. <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/EntityReferenceContract.java</SourceClass>
   které představuje odlehčenou formu entity obsahující pouze její primární klíč a typ entity
2. <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/SealedEntity.java</SourceClass>
   které představuje částečnou nebo úplnou podobu entity s jejími daty

Obě jsou platné a snadno použitelné datové struktury, ale ani jedna nemluví jazykem vašeho business domény. Vývojáři
obecně preferují práci s vlastními doménovými objekty, a to chápeme. Jejich aplikace by obvykle obalila modelové třídy evitaDB do svých doménových objektů, což by vyžadovalo zdlouhavou ruční práci.

Abychom tento proces usnadnili, vytvořili jsme API pro vlastní kontrakty, které vám umožňuje definovat vlastní doménové objekty a mapovat je na entity evitaDB. Modelové objekty lze použít pro definici schémat entit i pro čtení a zápis entit z/do databáze. Vlastní kontrakty využívají knihovny [ByteBuddy](https://bytebuddy.net/#/) a [Proxycian](https://github.com/FgForrest/Proxycian) pro vytváření dynamických proxy vašich doménových objektů. S tím je spojen malý výkonový overhead, ale je zanedbatelný ve srovnání s časem stráveným komunikací s databází. API je volitelné a lze jej používat paralelně se standardním API.

### Požadavky za běhu

API pro vlastní kontrakty používá pod kapotou Java proxy, což vyžaduje, aby byla knihovna [Proxycian](https://github.com/FgForrest/Proxycian) přítomna na classpath za běhu. Protože je API volitelné, nechtěli jsme nafukovat JAR evitaDB knihovnou Proxycian. Pokud však vývojář chce používat API pro vlastní kontrakty, je třeba přidat knihovnu Proxycian jako závislost:

```xml
<dependency>
  <groupId>one.edee.oss</groupId>
  <artifactId>proxycian_bytebuddy</artifactId>
  <version>1.4.0</version>
</dependency>
```

a také, pokud aplikace používá [Java moduly](https://www.oracle.com/corporate/features/understanding-java-9-modules.html),
je nutné použít parametr `--add-modules`

```shell
--add-modules proxycian.bytebuddy
```

### Definice schématu

Definice schématu se provádí anotováním doménového objektu anotacemi z balíčku <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation</SourceClass> a je detailně popsána v [kapitole o schema API](../api/schema-api.md#deklarativní-definice-schématu).

### Načítání entit

Entita ve formě vlastního kontraktu může být načtena z databáze pomocí dedikovaných metod na rozhraní
<SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass>:

<SourceCodeTabs requires="/documentation/user/en/use/connectors/examples/selective-imports.java,/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java,documentation/user/en/use/api/example/declarative-schema-definition.java" langSpecificTabOnly local>

[Načtení entity pomocí vlastního rozhraní](/documentation/user/en/use/connectors/examples/custom-contract-reading.java)

</SourceCodeTabs>

<Note type="info">

Příklad pracuje se stejnou definicí produktu jako [příklad v kapitole o schema API](../api/schema-api.md#deklarativní-definice-schématu)
<SourceClass>/documentation/user/en/use/api/example/declarative-model-example.java</SourceClass>.

</Note>

Načítání entit pouze pro čtení je detailně popsáno v [kapitole o read API](../api/query-data.md#vlastní-kontrakty).

### Zápis entit

Entita ve formě vlastního kontraktu může být zapsána do databáze pomocí dedikovaných metod na rozhraní
<SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass>:

<SourceCodeTabs requires="/documentation/user/en/use/connectors/examples/selective-imports.java,/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java,documentation/user/en/use/api/example/declarative-schema-definition.java" langSpecificTabOnly local>

[Zápis entity pomocí vlastního rozhraní](/documentation/user/en/use/connectors/examples/custom-contract-writing.java)

</SourceCodeTabs>

Zápis dat pomocí vlastních kontraktů je detailně popsán v [kapitole o write API](../api/write-data.md#vlastní-kontrakty).

### Doporučení pro modelování dat

Můžete definovat jedno rozhraní jak pro čtení, tak pro zápis dat v evitaDB. Doporučuje se však oddělit rozhraní pro čtení a zápis a používat pro tyto účely různé instance datových objektů. Jinými slovy, řídit se podobnými principy, na kterých je postavena i samotná evitaDB. I když se to může na začátku zdát složitější,
z dlouhodobého hlediska se to vyplatí. Důvody jsou:

1. instance pro čtení zůstávají neměnné a mohou být bezpečně sdíleny mezi vlákny a cachovány ve sdílené paměti
2. rozhraní pro čtení není zaneseno metodami, které nejsou potřeba pro čtení dat, a zůstává čisté a jednoduché.

Tento princip nazýváme „sealed/open“ a funguje následovně:

#### 1. definujte pouze pro čtení rozhraní

Definujete rozhraní nebo třídu s finálními poli, která jsou inicializována v konstruktoru:

<SourceCodeTabs requires="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Sealed instance ve vlastním rozhraní](/documentation/user/en/use/connectors/examples/sealed-instance-example.java)

</SourceCodeTabs>

Jak vidíte, rozhraní vypadá přesně jako [příklad v kapitole o Schema API](../api/schema-api.md#deklarativní-definice-schématu)
s jediným rozdílem, že tato verze rozšiřuje rozhraní <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/SealedInstance.java</SourceClass>.
Deklarace signalizuje, že `<READ_INTERFACE>` je rozhraní `Product` a `<WRITE_INTERFACE>` je rozhraní `ProductEditor`.

<Note type="info">

Očekáváme, že rozhraní pro čtení bude použito jak pro čtení dat, tak pro definici struktury schématu. Je dobré
udržovat definici schématu a rozhraní pro přístup k datům na jednom místě.

</Note>

#### 2. definujte rozhraní pro zápis

Poté definujete samostatné rozhraní pro úpravu dat:

<SourceCodeTabs requires="/documentation/user/en/use/connectors/examples/sealed-instance-example.java" langSpecificTabOnly local>

[Editor instance ve vlastním rozhraní](/documentation/user/en/use/connectors/examples/instance-editor-example.java)

</SourceCodeTabs>

Všimněte si, že toto rozhraní rozšiřuje rozhraní `Product` a přidává metody pro úpravu dat. Také rozšiřuje rozhraní <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/InstanceEditor.java</SourceClass>
a specifikuje, že `<READ_INTERFACE>` je rozhraní `Product`.

#### 3. využijte princip sealed/open

Nyní můžeme výše popsaná rozhraní použít následujícím způsobem:

<SourceCodeTabs requires="/documentation/user/en/use/connectors/examples/custom-contract-writing.java" langSpecificTabOnly local>

[Otevření sealed rozhraní](/documentation/user/en/use/connectors/examples/sealed-open-lifecycle-example.java)

</SourceCodeTabs>

Princip sealed/open je o něco složitější než naivní přístup s jedním rozhraním pro čtení i zápis dat, ale jasně odděluje scénáře pro čtení a zápis, což vám umožňuje udržet kontrolu nad mutacemi a jejich viditelností v multithread prostředí.
</LS>