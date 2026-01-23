---
title: Java
perex: Java API je nativní rozhraní pro komunikaci s evitaDB. Umožňuje spouštět evitaDB jako embedded databázi nebo se připojit k vzdálenému databázovému serveru. Je navrženo tak, aby sdílelo společná rozhraní pro oba scénáře, což vám umožňuje přepínat mezi embedded a vzdáleným režimem bez nutnosti měnit váš kód. To je obzvláště užitečné během vývoje nebo jednotkového testování, kdy můžete používat embedded databázi a v produkci přejít na vzdálenou databázi.
date: '26.10.2023'
author: Ing. Jan Novotný
preferredLang: java
commit: d5041ba065f96f215dbdd0cf6ffb0cf5c9f3a88b
---
<Tento kapitola popisuje Java driver pro evitaDB a nedává smysl pro jiné jazyky. Pokud vás zajímají detaily implementace Java driveru, změňte prosím preferovaný jazyk v pravém horním rohu.>

<Spuštění evitaDB v embedded režimu je podrobně popsáno v kapitole [Spuštění evitaDB](../../../en/get-started/run-evitadb?lang=java). Připojení ke vzdálené instanci databáze je popsáno v kapitole [Připojení ke vzdálené databázi](../../../en/get-started/query-our-dataset?lang=java). Totéž platí pro [query API](../../../en/use/api/query-data?lang=java) a [write API](../../../en/use/api/write-data?lang=java). Proto se těmito tématy zde zabývat nebudeme.

## Java remote client

Pro použití Java remote clienta stačí přidat následující závislost do vašeho projektu:

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

Java remote client staví na [gRPC API](./grpc.md). <SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass> je thread-safe a očekává se, že v aplikaci bude použita pouze jedna instance. Klient interně spravuje pool gRPC spojení pro paralelní komunikaci se serverem.

<Note type="info">
Instance klienta je vytvořena bez ohledu na to, zda je server dostupný. Pro ověření, že je server dosažitelný, je potřeba zavolat na klientovi nějakou metodu. Typickým scénářem je [otevření nové session](../../../en/get-started/create-first-database?lang=java#open-session-to-catalog-and-insert-your-first-entity) do existujícího <Term location="/documentation/user/en/index.md">katalogu</Term>.
</Note>

<Note type="warning">
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass> udržuje pool otevřených zdrojů a měl by být ukončen metodou `close()`, když jej přestanete používat.
</Note>

### Konfigurace

Minimální konfigurace klienta spočívá v zadání adresy serveru a portu. Následující příklad ukazuje, jak vytvořit instanci klienta, která se připojuje k serveru běžícímu na `localhost` na portu `5555`:

```java
var evita = new EvitaClient(
	EvitaClientConfiguration.builder()
		.host("localhost")
		.port(5555)
		.build()
);
```

Existuje však více možností, které lze konfigurovat. Následující tabulka popisuje všechny dostupné volby, které lze nastavit v <SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/config/EvitaClientConfiguration.java</SourceClass> na straně klienta:

<dl>
    <dt>clientId</dt>
    <dd>
        <p>**Výchozí: `gRPC client at hostname`**</p>
        <p>
          Tato vlastnost umožňuje odlišit požadavky tohoto konkrétního klienta od požadavků ostatních klientů. Tato informace může být použita v logování nebo při [řešení problémů](../../use/api/troubleshoot.md).
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
        <p>Identifikace portu serveru, na kterém běží system API evitaDB. System API slouží k automatickému nastavení klientského certifikátu pro mTLS nebo ke stažení self-signed certifikátu serveru. Viz [Konfigurace a principy TLS](../../operate/tls.md). System API není potřeba, pokud server používá důvěryhodný certifikát a mTLS je vypnuté, nebo pokud je privátní/veřejný klíč serveru/klienta distribuován "ručně" s klientem.</p>
    </dd>
    <dt>useGeneratedCertificate</dt>
    <dd>
        <p>**Výchozí: `true`**</p>
        <p>Pokud je nastaveno na `true`, klient automaticky stáhne root certifikát serverové CA z `system` endpointu. Pokud je nastaveno na `false`, klient očekává, že root certifikát bude poskytnut ručně prostřednictvím vlastnosti `serverCertificatePath`.</p>
    </dd>
    <dt>trustCertificate</dt>
    <dd>
        <p>**Výchozí: `false`**</p>
        <p>Pokud je nastaveno na `true`, certifikát získaný z endpointu `system` nebo ručně přes `serverCertificatePath` je automaticky přidán do lokálního trust store. Pokud je nastaveno na `false` a je poskytnut nedůvěryhodný (self-signed) certifikát, nebude klientem důvěřován a spojení se serverem selže. Použití `true` v produkci se obecně nedoporučuje.</p>
    </dd>
    <dt>tlsEnabled</dt>
    <dd>
        <p>**Výchozí: `true`**</p>
        <p>Pokud je nastaveno na `true`, klient použije pro komunikaci se serverem TLS šifrování. Pokud je nastaveno na `false`, klient použije HTTP/2 bez TLS šifrování. Odpovídající nastavení musí být nastaveno i na straně serveru.</p>
    </dd>
    <dt>mtlsEnabled</dt>
    <dd>
        <p>**Výchozí: `false`**</p>
        <p>Pokud je nastaveno na `true`, klient a server použijí vzájemnou TLS autentizaci. Klient se musí správně identifikovat pomocí páru veřejného/soukromého klíče, který je serverem znám a důvěryhodný, aby bylo možné navázat spojení. Viz [Konfigurace a principy TLS](../../operate/tls.md).</p>
    </dd>
    <dt>serverCertificatePath</dt>
    <dd>
        <p>**Výchozí: `null`**</p>
        <p>Relativní cesta k certifikátu serveru. Musí být zadána, pokud jsou `useGeneratedCertificate` a `trustCertificate` vypnuté a server používá nedůvěryhodný certifikát (například self-signed). Pokud je `useGeneratedCertificate` vypnuté, je nutné nastavit cestu k ručně poskytnutému certifikátu, jinak ověřovací proces selže a spojení nebude navázáno.</p>
    </dd>
    <dt>certificateFolderPath</dt>
    <dd>
        <p>**Výchozí: `evita-client-certificates`**</p>
        <p>Relativní cesta ke složce, kde bude umístěn klientský certifikát a privátní klíč, nebo pokud zde ještě nejsou, budou staženy. V druhém případě bude použita výchozí cesta v temp složce.</p>
    </dd>
    <dt>certificateFileName</dt>
    <dd>
        <p>**Výchozí: `null`**</p>
        <p>Relativní cesta z `certificateFolderPath` ke klientskému certifikátu. Musí být nakonfigurováno, pokud je mTLS povoleno a `useGeneratedCertificate` je nastaveno na `false`.</p>
    </dd>
    <dt>certificateKeyFileName</dt>
    <dd>
        <p>**Výchozí: `null`**</p>
        <p>Relativní cesta z `certificateFolderPath` k privátnímu klíči klienta. Musí být nakonfigurováno, pokud je mTLS povoleno a `useGeneratedCertificate` je nastaveno na `false`.</p>
    </dd>
    <dt>certificateKeyPassword</dt>
    <dd>
        <p>**Výchozí: `null`**</p>
        <p>Heslo k privátnímu klíči klienta (pokud je nastaveno). Musí být nakonfigurováno, pokud je mTLS povoleno a `useGeneratedCertificate` je nastaveno na `false`.</p>
    </dd>
    <dt>trustStorePassword</dt>
    <dd>
        <p>**Výchozí: `trustStorePassword`**</p>
        <p>Heslo pro trust store, který slouží k ukládání serverových certifikátů. Používá se, pokud je `trustCertificate` nastaveno na `true`.</p>
    </dd>
    <dt>reflectionLookupBehaviour</dt>
    <dd>
        <p>**Výchozí: `CACHE`**</p>
        <p>Chování třídy <SourceClass>evita_common/src/main/java/io/evitadb/utils/ReflectionLookup.java</SourceClass> při analýze tříd pro reflexivní informace. Určuje, zda se jednou analyzované informace budou cachovat, nebo se budou vždy znovu (a nákladně) získávat.</p>
    </dd>
    <dt>timeout</dt>
    <dd>
        <p>**Výchozí: `5`**</p>
        <p>Počet jednotek `timeoutUnit`, které má klient čekat na odpověď serveru před vyhozením výjimky nebo násilným ukončením spojení.</p>
    </dd>
    <dt>timeoutUnit</dt>
    <dd>
        <p>**Výchozí: `TimeUnit.SECONDS`**</p>
        <p>Časová jednotka pro vlastnost `timeout`.</p>
    </dd>
    <dt>streamingTimeout</dt>
    <dd>
        <p>**Výchozí: `3600`**</p>
        <p>Počet jednotek `streamingTimeoutUnit`, které má klient čekat, než server pošle další zprávu ve streamu, než klient stream zruší.</p>
    </dd>
    <dt>streamingTimeoutUnit</dt>
    <dd>
        <p>**Výchozí: `TimeUnit.SECONDS`**</p>
        <p>Časová jednotka pro vlastnost `streamingTimeout`.</p>
    </dd>
    <dt>openTelemetryInstance</dt>
    <dd>
        <p>**Výchozí: `null`**</p>
        <p>OpenTelemetry instance, která má být použita pro tracing. Pokud je nastavena na `null`, tracing nebude prováděn.</p>
    </dd>
    <dt>retry</dt>
    <dd>
        <p>**Výchozí: `false`**</p>
        <p>Zda klient zkusí opakovat volání v případě timeoutu nebo jiných síťových problémů.</p>
    </dd>
    <dt>trackedTaskLimit</dt>
    <dd>
        <p>**Výchozí: `100`**</p>
        <p>Maximální počet serverových úloh, které může klient sledovat. Pokud je limit dosažen, klient přestane sledovat nejstarší úlohy.</p>
    </dd>
    <dt>changeCaptureQueueSize</dt>
    <dd>
        <p>**Výchozí: `Flow.defaultBufferSize()`**</p>
        <p>Maximální počet událostí změn, které mohou být bufferovány pro každého odběratele. Pokud je tento limit dosažen, je odběrateli nahlášena chyba.</p>
    </dd>
</dl>

<Note type="warning">
Pokud je na straně serveru povoleno `mTLS` a `useGeneratedCertificate` je nastaveno na `false`, musíte v nastavení `certificateFileName` a `certificateKeyFileName` poskytnout svůj ručně vygenerovaný certifikát, jinak ověřovací proces selže a spojení nebude navázáno.
</Note>

### Caching schématu

Jak katalogová, tak entitní schémata jsou používána velmi často – každá načtená entita má referenci na své schéma. Zároveň je schéma poměrně složité a často se nemění. Je proto výhodné schéma na straně klienta cachovat a vyhnout se jeho opakovanému načítání ze serveru.

Cache je spravována třídou <SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaEntitySchemaCache.java</SourceClass>, která řeší dva scénáře přístupu ke schématu:

#### Přístup k posledním verzím schématu

Klient udržuje poslední známé verze schématu pro každý katalog. Tato cache je invalidována pokaždé, když konkrétní klient změní schéma, kolekce je přejmenována nebo smazána, nebo když klient načte entitu, která používá novější verzi schématu, než je poslední cachovaná verze.

#### Přístup ke konkrétním verzím schématu

Klient také udržuje cache konkrétních verzí schématu. Pokaždé, když klient načte entitu, entita vrácená ze serveru nese informaci o verzi schématu, na kterou se odkazuje. Klient se pokusí najít schéma této konkrétní verze ve své cache, a pokud jej nenajde, stáhne jej ze serveru a uloží do cache. Cache je čas od času invalidována (každou minutu) a stará schémata, která nebyla dlouho použita (4 hodiny), jsou odstraněna.

<Note type="info">

Výše uvedené intervaly nejsou aktuálně konfigurovatelné, protože věříme, že jsou optimální pro většinu případů použití. Pokud je potřebujete změnit, kontaktujte nás prosím se svým konkrétním případem a zvážíme přidání konfigurační volby.

</Note>

## Vlastní kontrakty

Java API obsahuje pouze dvě formy rozhraní datového modelu:

1. <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/EntityReferenceContract.java</SourceClass>
   představuje odlehčenou formu entity obsahující pouze primární klíč a typ entity
2. <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/SealedEntity.java</SourceClass>
   představuje částečnou nebo úplnou formu entity s jejími daty

Obě jsou platné a snadno použitelné datové struktury, ale ani jedna nemluví jazykem vašeho business domény. Vývojáři obecně dávají přednost práci s vlastními doménovými objekty, což chápeme. Jejich aplikace by obvykle obalila modelové třídy evitaDB do svých doménových objektů, což by vyžadovalo zdlouhavou ruční práci.

Abychom tento proces usnadnili, vytvořili jsme API pro vlastní kontrakty, které vám umožňuje definovat vlastní doménové objekty a mapovat je na entity evitaDB. Modelové objekty lze použít jak pro definici schémat entit, tak pro čtení a zápis entit z/do databáze. Vlastní kontrakty využívají knihovny [ByteBuddy](https://bytebuddy.net/#/) a [Proxycian](https://github.com/FgForrest/Proxycian) pro vytváření dynamických proxy vašich doménových objektů. S tímto řešením je spojena malá režie výkonu, která je však zanedbatelná ve srovnání s časem stráveným komunikací s databází. API je volitelné a lze jej používat paralelně se standardním API.

### Požadavky za běhu

API pro vlastní kontrakty používá pod kapotou Java proxy, což vyžaduje, aby byla knihovna [Proxycian](https://github.com/FgForrest/Proxycian) přítomna na classpath za běhu. Protože je API volitelné, nechtěli jsme zvětšovat JAR evitaDB o knihovnu Proxycian. Pokud však vývojář chce použít API pro vlastní kontrakty, je nutné přidat Proxycian jako závislost:

```xml
<dependency>
  <groupId>one.edee.oss</groupId>
  <artifactId>proxycian_bytebuddy</artifactId>
  <version>1.4.0</version>
</dependency>
```

a pokud aplikace používá [Java moduly](https://www.oracle.com/corporate/features/understanding-java-9-modules.html), je třeba použít parametr `--add-modules`

```shell
--add-modules proxycian.bytebuddy
```

### Definice schématu

Definice schématu se provádí anotací doménového objektu anotacemi z balíčku <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation</SourceClass> a je podrobně popsána v [kapitole o schema API](../../use/api/schema-api.md#deklarativní-definice-schématu).

### Načítání entit

Entitu ve formě vlastního kontraktu lze načíst z databáze pomocí speciálních metod na rozhraní <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass>:

<SourceCodeTabs requires="/documentation/user/en/use/connectors/examples/selective-imports.java,/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java,documentation/user/en/use/api/example/declarative-schema-definition.java" langSpecificTabOnly local>

[Načtení entity pomocí vlastního rozhraní](/documentation/user/en/use/connectors/examples/custom-contract-reading.java)

</SourceCodeTabs>

<Note type="info">

Příklad pracuje se stejnou definicí produktu jako [příklad v kapitole o schema API](../../use/api/schema-api.md#deklarativní-definice-schématu) <SourceClass>/documentation/user/en/use/api/example/declarative-model-example.java</SourceClass>.

</Note>

Načítání entit pouze pro čtení je podrobně popsáno v [kapitole o read API](../../use/api/query-data.md#vlastní-kontrakty).

### Zápis entit

Entitu ve formě vlastního kontraktu lze zapsat do databáze pomocí speciálních metod na rozhraní <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass>:

<SourceCodeTabs requires="/documentation/user/en/use/connectors/examples/selective-imports.java,/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java,documentation/user/en/use/api/example/declarative-schema-definition.java" langSpecificTabOnly local>

[Zápis entity pomocí vlastního rozhraní](/documentation/user/en/use/connectors/examples/custom-contract-writing.java)

</SourceCodeTabs>

Zápis dat pomocí vlastních kontraktů je podrobně popsán v [kapitole o write API](../../use/api/write-data.md#vlastní-kontrakty).

### Doporučení pro modelování dat

Můžete definovat jedno rozhraní jak pro čtení, tak pro zápis dat v evitaDB. Doporučujeme však oddělit rozhraní pro čtení a zápis a používat pro tyto účely různé instance datových objektů. Jinými slovy, řídit se podobnými principy, na kterých je postavena a které sama používá i evitaDB. Ačkoliv se to může na začátku zdát složitější, z dlouhodobého hlediska se to vyplatí. Důvody tohoto přístupu jsou:

1. instance pro čtení zůstávají neměnné a lze je bezpečně sdílet mezi vlákny a cachovat ve sdílené paměti
2. rozhraní pro čtení není zaneseno metodami, které nejsou potřeba pro čtení dat, a zůstává čisté a jednoduché

Tento princip nazýváme "sealed/open" a funguje následovně:

#### 1. definujte rozhraní pouze pro čtení

Definujete rozhraní nebo třídu s finálními poli, která jsou inicializována v konstruktoru:

<SourceCodeTabs requires="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Sealed instance ve vlastním rozhraní](/documentation/user/en/use/connectors/examples/sealed-instance-example.java)

</SourceCodeTabs>

Jak vidíte, rozhraní vypadá přesně jako [příklad v kapitole o Schema API](../../use/api/schema-api.md#deklarativní-definice-schématu), s tím rozdílem, že tato verze rozšiřuje rozhraní <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/SealedInstance.java</SourceClass>. Deklarace signalizuje, že `<READ_INTERFACE>` je rozhraní `Product` a `<WRITE_INTERFACE>` je rozhraní `ProductEditor`.

<Note type="info">

Předpokládáme, že rozhraní pro čtení bude použito jak pro čtení vašich dat, tak pro definici struktury schématu. Je dobrým zvykem mít definici schématu a rozhraní pro přístup k datům na jednom místě.

</Note>

#### 2. definujte rozhraní pro zápis

Poté definujete samostatné rozhraní pro úpravu dat:

<SourceCodeTabs requires="/documentation/user/en/use/connectors/examples/sealed-instance-example.java" langSpecificTabOnly local>

[Editor instance ve vlastním rozhraní](/documentation/user/en/use/connectors/examples/instance-editor-example.java)

</SourceCodeTabs>

Všimněte si, že toto rozhraní rozšiřuje rozhraní `Product` a přidává metody pro úpravu dat. Také rozšiřuje rozhraní <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/InstanceEditor.java</SourceClass> a specifikuje, že `<READ_INTERFACE>` je rozhraní `Product`.

#### 3. využijte princip sealed/open

Nyní můžeme výše popsaná rozhraní použít následujícím způsobem:

<SourceCodeTabs requires="/documentation/user/en/use/connectors/examples/custom-contract-writing.java" langSpecificTabOnly local>

[Otevření sealed rozhraní](/documentation/user/en/use/connectors/examples/sealed-open-lifecycle-example.java)

</SourceCodeTabs>

Princip sealed/open je o něco složitější než naivní přístup s jedním rozhraním pro čtení i zápis dat, ale jasně odděluje scénáře čtení a zápisu, což vám umožňuje mít pod kontrolou mutace a jejich viditelnost ve vícevláknovém prostředí.
</LS>