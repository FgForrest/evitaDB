---
title: Pište testy
perex: Testy píše každý – i vy. Máme pravdu, že ano?! Psát testy by měla být radost, a proto se snažíme vám poskytnout podporu, abyste mohli s evitaDB snadno a rychle psát testy. Vaše integrační testy by neměly trvat minuty, ale sekundy.
date: '17.1.2023'
author: Ing. Jan Novotný
proofreading: done
preferredLang: java
commit: cef96d8320d36c91c100c5dfc9c45020b5a7ad0d
translated: 'true'
---
<LS to="e">
Výběr jazyka evitaQL pro tento článek nedává smysl.
</LS>

<LS to="c">
Bohužel zatím nemáme podporu napsanou pro klienta v C#. Chcete
[přispět](https://github.com/FgForrest/evitaDB)?
</LS>

<LS to="j,g,r">

<UsedTerms>
    <h4>Pojmy použité v tomto dokumentu</h4>
	<dl>
		<dt>autowiring</dt>
		<dd>
        Autowiring je proces vložení instance beanu do zadaného argumentu metody. Tento
        mechanismus je známý jako [dependency injection pattern](https://en.wikipedia.org/wiki/Dependency_injection)
        a umožňuje *frameworku* vyřešit správnou instanci pro argument daného jména a typu a
        automaticky ji poskytnout aplikačnímu kódu. Tento proces umožňuje oddělit aplikační logiku
        od integrační logiky a výrazně ji zjednodušuje.
        </dd>
	</dl>
</UsedTerms>

Abyste mohli využít naši podporu pro testování aplikací s evitaDB, musíte nejprve importovat artefakt `evita_test_support`
do svého projektu:

<CodeTabs>
<CodeTabsBlock>
```Maven
<dependency>
    <groupId>io.evitadb</groupId>
    <artifactId>evita_test_support</artifactId>
    <version>2025.8.0</version>
    <scope>test</scope>
</dependency>
```
</CodeTabsBlock>
<CodeTabsBlock>
```Gradle
implementation 'io.evitadb:evita_test_support:2025.8.0'
```
</CodeTabsBlock>
</CodeTabs>

Naše testovací podpora vyžaduje a je napsána pro testovací framework [JUnit 5](https://junit.org/junit5/docs/current/user-guide/).

## JUnit testovací třída

Podívejme se nejprve, jak by mohl vypadat běžně psaný automatizovaný test, který používá data v evitaDB:

<SourceCodeTabs local>
[Příklad testu JUnit5](/documentation/user/en/use/api/example/test-with-empty-dataset-example.java)
</SourceCodeTabs>

To je poměrně dost práce. Proto poskytujeme lepší podporu pro inicializaci serveru evitaDB pomocí
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/extension/DbInstanceParameterResolver.java</SourceClass>,
kterou můžete integrovat do svého testu pomocí JUnit 5 anotace `@ExtendWith(DbInstanceParameterResolver.class)`:

<SourceCodeTabs local>
[Alternativní příklad testu](/documentation/user/en/use/api/example/test-with-empty-dataset-alternative.java)
</SourceCodeTabs>

Tato ukázková třída vytvoří *anonymní instanci* prázdného embedded serveru evitaDB a ihned ji po skončení testu zničí. Pokud chcete instanci serveru evitaDB pojmenovat a použít ji ve více testech (a případně
s nějakým počátečním datovým fixture), musíte ve svém testu definovat novou inicializační funkci a použít dvě nové
anotace <SourceClass>evita_test_support/src/main/java/io/evitadb/test/annotation/DataSet.java</SourceClass>
a <SourceClass>evita_test_support/src/main/java/io/evitadb/test/annotation/UseDataSet.java</SourceClass>:

<SourceCodeTabs local>
[Příklad testu s pojmenovanou a naplněnou sadou dat](/documentation/user/en/use/api/example/test-with-prefilled-dataset.java)
</SourceCodeTabs>

Jak můžete vidět v příkladu, metoda `setUpData` deklaruje, že inicializuje datovou sadu s názvem
`dataSetWithAFewData`, vytvoří novou kolekci `Brand` s jednou entitou obsahující atribut `name` s
hodnotou `Siemens`. <SourceClass>evita_test_support/src/main/java/io/evitadb/test/extension/DbInstanceParameterResolver.java</SourceClass>
automaticky <Term name="autowiring">autowiruje</Term>
objekt <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass>,
který umožňuje komunikaci s instancí evitaDB (viz [reference](#reference-anotací) pro další možnosti
anotace `@DataSet`).

Dále je zde testovací metoda `exampleTestCaseWithAssertions`, která deklaruje, že používá datovou sadu s názvem
`dataSetWithAFewData` pomocí anotace `@UseDataSet`. Stejný
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/extension/DbInstanceParameterResolver.java</SourceClass>
automaticky <Term name="autowiring">autowiruje</Term> odkaz na další objekt session evitaDB, který je použit v
implementaci testovací metody pro dotazování a ověřování výsledků dat v databázi.

### Testování webových API

</LS>

<LS to="j">

Podobný přístup je možný s Java klientem evitaDB přes gRPC API. Při nastavování své datové sady jednoduše deklarujte, že také
chcete inicializovat gRPC web server a otevřít požadovanou sadu webových API:

<SourceCodeTabs local>
[Příklad testu webového API](/documentation/user/en/use/api/example/test-with-prefilled-dataset-and-grpc-web-api.java)
</SourceCodeTabs>

Příklad je totožný s předchozím s jediným podstatným rozdílem – místo komunikace
s embedded serverem evitaDB pomocí přímých volání metod používá
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass>,
který komunikuje se stejným embedded serverem evitaDB přes protokol gRPC pomocí HTTP/2 a lokální sítě. Server
otevře volný port, vygeneruje self-signed <Term location="/documentation/user/en/operate/tls.md">certifikační autoritu</Term>
certifikát. <SourceClass>evita_test_support/src/main/java/io/evitadb/test/extension/DbInstanceParameterResolver.java</SourceClass>
vytvoří
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass>
instanci, která je správně nakonfigurována pro komunikaci s tímto gRPC API, klient stáhne self-signed
<Term location="/documentation/user/en/operate/tls.md">certifikační autoritu</Term> certifikát a generický klientský
certifikát pro splnění [mTLS ověření](../../operate/tls.md#výchozí-chování-mtls-nebezpečné) a
komunikuje s *embedded evitaDB* po síti.

</LS>

<LS to="g">

Podobný přístup je možný s GraphQL API evitaDB. Při nastavování své datové sady jednoduše deklarujte, že také
chcete inicializovat GraphQL web server a otevřít požadované webové API:

<SourceCodeTabs local>
[Příklad testu webového API](/documentation/user/en/use/api/example/test-with-prefilled-dataset-and-graphql-web-api.java)
</SourceCodeTabs>

Příklad je podobný předchozímu s jediným podstatným rozdílem – místo komunikace
s embedded serverem evitaDB pomocí přímých volání metod používá
exponované GraphQL API,
které komunikuje se stejným embedded serverem evitaDB přes protokol GraphQL pomocí HTTP a lokální sítě. Server
otevře volný port a vygeneruje self-signed <Term location="/documentation/user/en/operate/tls.md">certifikační autoritu</Term>
certifikát. <SourceClass>evita_test_support/src/main/java/io/evitadb/test/extension/DbInstanceParameterResolver.java</SourceClass>
vytvoří
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/tester/GraphQLTester.java</SourceClass>
instanci, která je správně nakonfigurována pro komunikaci s tímto GraphQL API, a
komunikuje s *embedded evitaDB* po síti.
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/tester/GraphQLTester.java</SourceClass> je v podstatě
jen wrapper okolo knihovny [REST-assured](https://rest-assured.io/), který poskytuje přednastavený tester s builderem
požadavků specifickým pro naše GraphQL API. Po zavolání metody `.executeAndThen()` je požadavek odeslán
a můžete použít asserční metody poskytované přímo knihovnou [REST-assured](https://github.com/rest-assured/rest-assured/wiki/Usage#verifying-response-data).

</LS>

<LS to="r">

Podobný přístup je možný s REST API evitaDB. Při nastavování své datové sady jednoduše deklarujte, že také
chcete inicializovat REST web server a otevřít požadované webové API:

<SourceCodeTabs local>
[Příklad testu webového API](/documentation/user/en/use/api/example/test-with-prefilled-dataset-and-rest-web-api.java)
</SourceCodeTabs>

Příklad je podobný předchozímu s jediným podstatným rozdílem – místo komunikace
s embedded serverem evitaDB pomocí přímých volání metod používá
exponované REST API,
které komunikuje se stejným embedded serverem evitaDB přes protokol REST pomocí HTTP a lokální sítě. Server
otevře volný port a vygeneruje self-signed <Term location="/documentation/user/en/operate/tls.md">certifikační autoritu</Term>
certifikát. <SourceClass>evita_test_support/src/main/java/io/evitadb/test/extension/DbInstanceParameterResolver.java</SourceClass>
vytvoří
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/tester/RestTester.java</SourceClass>
instanci, která je správně nakonfigurována pro komunikaci s tímto REST API, a
komunikuje s *embedded evitaDB* po síti.
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/tester/RestTester.java</SourceClass> je v podstatě
jen wrapper okolo knihovny [REST-assured](https://rest-assured.io/), který poskytuje přednastavený tester s builderem
požadavků specifickým pro naše REST API. Po zavolání metody `.executeAndThen()` je požadavek odeslán
a můžete použít asserční metody poskytované přímo knihovnou [REST-assured](https://github.com/rest-assured/rest-assured/wiki/Usage#verifying-response-data).

</LS>

<LS to="j,g,r">

### Inicializace sdílených datových objektů

V inicializační metodě označené anotací `@DataSet` můžete vytvořit sadu dalších objektů, které budou
spojeny s testovací datovou sadou daného jména a budou dostupné pro <Term>autowiring</Term> v jakémkoli vašem
testu spolu s dalšími objekty souvisejícími s evitaDB. Podívejme se na následující příklad:

<SourceCodeTabs local>
[Inicializace objektů společnosti](/documentation/user/en/use/api/example/test-company-objects.java)
</SourceCodeTabs>

V počáteční metodě vracíme speciální
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/extension/DataCarrier.java</SourceClass>. Jedná se o
wrapper okolo [Map](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Map.html), který nese
sdílené *jméno* objektu a *hodnotu*. Všechny takové objekty, které jsou vráceny v
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/extension/DataCarrier.java</SourceClass>, budou
dostupné pro <Term>autowiring</Term> v argumentech vstupu testovací metody. Argumenty jsou <Term
name="autowiring">autowirovány</Term> primárně podle jména (a odpovídajícího typu), sekundárně podle kompatibility typu.
Pokud potřebujete propagovat pouze jeden sdílený objekt, můžete jej vrátit přímo jako návratovou hodnotu
inicializační metody bez zabalení do
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/extension/DataCarrier.java</SourceClass>.

V testovací metodě vidíte, že přijímáme argumenty `SealedEntity brand` a `String expectedBrandName`, které
přesně odpovídají pojmenovaným hodnotám poskytnutým v data carrieru inicializační metody.

### Izolace testů

Podpora datových sad umožňuje spouštět více izolovaných instancí evitaDB současně – zcela oddělených od sebe navzájem. Tato skutečnost vám umožňuje spouštět integrační testy paralelně. O této technice si můžete přečíst více v našem blogovém příspěvku [o bleskově rychlých integračních testech](../../../../blog/en/04-blazing-fast-integration-tests.md).

Každá datová sada je uložena ve složce s náhodně vygenerovaným názvem v dočasné složce operačního systému. Když datová sada otevře webové API,
otevřené porty jsou konzultovány s <SourceClass>evita_test_support/src/main/java/io/evitadb/test/PortManager.java</SourceClass>.
Ten poskytuje webovému serveru evitaDB informace o volných portech, které lze použít pro webová API datové sady.
Když je datová sada zničena, porty jsou uzavřeny a vráceny zpět do
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/PortManager.java</SourceClass>.

Všechny datové sady jsou po počátečním naplnění přepnuty do režimu pouze pro čtení, aby nemohlo dojít k
neúmyslné změně dat v implementacích testů a tím k problémům v různých testech běžících současně
nebo při opětovném použití stejné datové sady později.

Můžete přepnout datovou sadu do režimu pro zápis, ale doporučujeme si být této skutečnosti vědom a označit datovou sadu ke zničení po dokončení testovací metody nebo celé testovací třídy. Všechny objekty session evita, které jsou vytvořeny a
<Term>autowirovány</Term> v argumentech testovací metody, jsou vytvořeny jako sessiony pro zápis s
[příznakem dry-run](write-data.md#relace-na-sucho-dry-run). To znamená, že nikdy neovlivní data v datové sadě
mimo rozsah této konkrétní session. Tento vzor se nazývá
[transaction rollback teardown pattern](http://xunitpatterns.com/Transaction%20Rollback%20Teardown.html)
a úspěšně se používá již dlouhou dobu v
[Spring Framework Tests](https://relentlesscoding.com/posts/automatic-rollback-of-transactions-in-spring-tests/).

## Reference anotací

Všechny metody anotované testovacími anotacemi evitaDB mohou deklarovat následující argumenty, které budou
<Term>autowirovány</Term> naší testovací podporou:

<dl>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass></dt>
    <dd>Odkaz na aktivní instanci embedded evitaDB.</dd>
    <dt>`String` catalogName</dt>
    <dd>Název počátečního katalogu uvnitř instance evitaDB.</dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass></dt>
    <dd>
        Odkaz na otevřenou session evitaDB pro zápis. Session je označena jako
        [dry-run](write-data.md#relace-na-sucho-dry-run), pokud je použita v testovací metodě anotované `@UseDataSet`.
        Sessiony <Term name="autowiring">autowirované</Term> do init nebo teardown metod jsou čistě pro zápis.
    </dd>
    <dt><SourceClass>evita_server/src/main/java/io/evitadb/server/EvitaServer.java</SourceClass></dt>
    <dd>Odkaz na aktivní instanci "webového" serveru evitaDB.</dd>
</dl>

V metodách anotovaných `@UseDataSet` nebo `@OnDataSetTearDown` (jiných než inicializační metoda) můžete také použít
libovolné [sdílené objekty](#inicializace-sdílených-datových-objektů) inicializované a vrácené metodou anotovanou
anotací `@DataSet`.

### @DataSet

Anotace se očekává na netestovací metodě, která připravuje novou instanci evitaDB se vzorovou datovou sadou
pro použití v testech. Je analogická metodě JUnit `@BeforeEach`.

<dl>
    <dt>`value`</dt>
    <dd>Definuje název datové sady.</dd>
    <dt>`catalogName`</dt>
    <dd>
        <p>**Výchozí:** `testCatalog`</p>
        <p>Definuje název katalogu pro počáteční katalog vytvořený v nové instanci evitaDB.</p>
    </dd>
    <dt>`expectedCatalogState`</dt>
    <dd>
        <p>**Výchozí:** `ALIVE`</p>
        <p>Definuje stav počátečního katalogu. Ve výchozím nastavení, když je počáteční datová sada nastavena, je katalog
        přepnut do transakčního režimu, aby bylo možné otevřít více session v tomto katalogu (více testů může
        přistupovat k obsahu tohoto katalogu paralelně).</p>
    </dd>
    <dt>`openWebApi`</dt>
    <dd>
        <p>**Výchozí:** `none`</p>
        <p>Určuje sadu webových API, která se mají pro tuto datovou sadu otevřít. Platné hodnoty jsou:</p>
        <ul>
            <li>`gRPC` (`GrpcProvider.CODE`) – pro gRPC web API (vyžaduje systémové API)</li>
            <li>`system` (`SystemProvider.CODE`) – systémové API, které poskytuje přístup k certifikátům</li>
            <li>`rest` (`RestProvider.CODE`) – pro REST web API</li>
            <li>`graphQL` (`GraphQLProvider.CODE`) – pro GraphQL web API</li>
        </ul>
    </dd>
    <dt>`readOnly`</dt>
    <dd>
        <p>**Výchozí:** `true`</p>
        <p>Označuje záznam jako pouze pro čtení po dokončení inicializační metody. Jedná se o bezpečnostní pojistku. Pokud potřebujete
        zapisovat do datové sady z metod unit testů, pravděpodobně ji nechcete sdílet s ostatními testy nebo jen
        s kontrolovanou podmnožinou z nich (obvykle ve stejné testovací třídě).</p>
	    <p>Pokud vypnete bezpečnostní pojistku readOnly, měli byste pravděpodobně nastavit atributy `destroyAfterClass` nebo
        `destroyAfterTest` na `true`.</p>
	    <p>Proto je `readOnly` ve výchozím nastavení `true`, chceme, abyste o tom před vypnutím této pojistky přemýšleli.</p>
    </dd>
    <dt>`destroyAfterClass`</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Pokud je nastaveno na true, instance serveru evitaDB bude po provedení všech testovacích metod třídy,
        ve které je deklarována anotace `@DataSet`, uzavřena a smazána.</p>
        <p>Ve výchozím nastavení zůstává datová sada aktivní, aby ji mohly znovu použít jiné testovací třídy. Ujistěte se, prosím, že neexistuje více (odlišných) verzí inicializačních metod datové sady, které by ji inicializovaly různě. Doporučený přístup je mít jednu abstraktní třídu s jednou implementací setup metody a více testů, které z ní dědí a používají stejnou datovou sadu.</p>
        <p>Pokud máte různé implementace inicializační metody záznamu, není zaručeno, která verze bude zavolána jako první a která bude přeskočena (kvůli existenci již inicializovaného záznamu).</p>
    </dd>
</dl>

### @UseDataSet

Anotace se očekává na metodě `@Test` nebo na argumentu této metody. Spojuje argument / metodu s datovou sadou inicializovanou metodou [`@DataSet`](#dataset).

<dl>
    <dt>`value`</dt>
    <dd>Definuje název datové sady, která se má použít.</dd>
    <dt>`destroyAfterTest`</dt>
    <dd>
        <p>**Výchozí:** `false`</p>
        <p>Pokud je nastaveno na true, instance serveru evitaDB bude po dokončení této testovací metody uzavřena a smazána.</p>
        <p>Je lepší sdílet jednu datovou sadu mezi více testovacími metodami, ale pokud víte, že zápis do ní
        v rámci testovací metody ji výrazně poškodí, je lepší ji zcela zahodit a nechat systém připravit novou.</p>
    </dd>
</dl>

</LS>

<LS to="j">

Kromě standardních [autowirovaných argumentů](#reference-anotací) můžete také injectovat
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass>
do libovolného z definovaných argumentů. Klient otevře gRPC spojení na webové API
<SourceClass>evita_server/src/main/java/io/evitadb/server/EvitaServer.java</SourceClass> a můžete začít komunikovat
se serverem po síti, i když server běží lokálně jako embedded databáze.

</LS>

<LS to="g">

Kromě standardních [autowirovaných argumentů](#reference-anotací) můžete také injectovat
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/tester/GraphQLTester.java</SourceClass>
do libovolného z definovaných argumentů. Tester otevře GraphQL spojení na webové API
<SourceClass>evita_server/src/main/java/io/evitadb/server/EvitaServer.java</SourceClass> a můžete začít komunikovat
se serverem po síti, i když server běží lokálně jako embedded databáze.

</LS>

<LS to="r">

Kromě standardních [autowirovaných argumentů](#reference-anotací) můžete také injectovat
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/tester/RestTester.java</SourceClass>
do libovolného z definovaných argumentů. Tester otevře REST spojení na webové API
<SourceClass>evita_server/src/main/java/io/evitadb/server/EvitaServer.java</SourceClass> a můžete začít komunikovat
se serverem po síti, i když server běží lokálně jako embedded databáze.

</LS>

<LS to="j,g,r">

### @OnDataSetTearDown

Anotace se očekává na netestovací metodě. Metoda je volána frameworkem těsně před tím, než je datová sada
daného jména uzavřena a zničena. Je analogická metodě JUnit `@AfterEach`. Obvykle není potřeba nic dělat,
protože se postaráme o správné uzavření všech věcí souvisejících s evitaDB. Také voláme metodu `close`
na všech [sdílených objektech](#inicializace-sdílených-datových-objektů), které implementují rozhraní Java `AutoCloseable`.
Někdy ale můžete potřebovat speciální úklidový postup pro své sdílené objekty a v takovém případě oceníte tuto destroy callback podporu.

<dl>
    <dt>`value`</dt>
    <dd>Definuje název příslušné datové sady.</dd>
</dl>

</LS>