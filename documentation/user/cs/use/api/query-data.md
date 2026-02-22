---
title: Dotazování na data
perex: Tento článek obsahuje hlavní principy dotazování na data v evitaDB, popis datového API týkajícího se získávání entit a související doporučení.
date: '17.1.2023'
author: Ing. Jan Novotný
proofreading: done
preferredLang: java
commit: cef96d8320d36c91c100c5dfc9c45020b5a7ad0d
translated: true
---
[Dotaz v evitaDB](../../query/basics.md) je reprezentován jako strom vnořených "omezení" rozdělených do čtyř <LS to="g">_logických_</LS> částí:

<dl>
    <dt>`collection`</dt>
    <dd>identifikuje kolekci, nad kterou se dotaz provádí</dd>
    <dt>`filterBy`</dt>
    <dd>omezuje počet vrácených výsledků</dd>
    <dt>`orderBy`</dt>
    <dd>určuje pořadí, ve kterém budou výsledky vráceny</dd>
    <dt>`require`</dt>
    <dd>umožňuje předat dodatečné informace o tom, jak kompletní mají být vrácené entity,
    kolik jich je potřeba a jaké další výpočty se nad nimi mají provést</dd>
</dl>

<LS to="e,j,c">

Vstupní bod *evitaQL* (evitaDB Query Language) je reprezentován třídou
<LS to="e,j"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/Query.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Query.cs</SourceClass></LS> a vypadá podobně jako
[Lispovský jazyk](https://en.wikipedia.org/wiki/Lisp_(programming_language)). Vždy začíná názvem omezení, následovaným sadou argumentů v závorkách. V těchto argumentech můžete použít i další funkce.
Příklad takového dotazu může vypadat následovně:

</LS>
<LS to="g,r">

*evitaQL* (evitaDB Query Language) je reprezentován jako JSON objekt vnořených omezení. Každá vnořená vlastnost
vždy začíná názvem omezení, následovaným sadou argumentů jako hodnotou vlastnosti. V těchto argumentech můžete použít i další omezení.
Příklad takového dotazu může vypadat následovně:

</LS>

<SourceCodeTabs setup="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad evitaQL](/documentation/user/en/use/api/example/evita-query-example.java)

</SourceCodeTabs>

> *Dotaz vrátí první stránku 20 produktů v kategorii "lokální potraviny" a jejích podkategoriích, které mají*
> *českou lokalizaci a platnou cenu v jednom z ceníků "VIP", "věrný zákazník" nebo "běžné ceny" v měně CZK. Dále filtruje*
> *pouze produkty s prodejní cenou mezi 600 a 1 600 Kč včetně DPH a s parametry "bez lepku" a "původní receptura".*
>
> *Pro všechny odpovídající produkty bude také vypočten tzv. cenový histogram s maximálně 30 sloupci, aby mohl být zobrazen na vyhrazeném místě. Navíc bude vypočten souhrn parametrických filtrů (facets) s analýzou dopadu, jak by výsledek vypadal, pokud by uživatel vybral i jiné parametry než ty dvě zvolené.*

<LS to="e">

evitaQL je reprezentován jako jednoduchý
[String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html), který je parsován do
abstraktního syntaktického stromu složeného z omezení
(<SourceClass>evita_query/src/main/java/io/evitadb/api/query/Constraint.java</SourceClass>), zapouzdřených v objektu
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/Query.java</SourceClass>.
</LS>

Navrhli jsme řetězcovou reprezentaci *evitaQL* tak, aby vypadala podobně jako dotaz definovaný přímo v jazyce *Java*.
Snažíme se také zachovat "look & feel" původního evitaQL v různých jazycích / API jako REST, GraphQL nebo C#,
přičemž respektujeme konvence a možnosti příslušného jazyka.

evitaQL se používá v gRPC protokolu a volitelně jej lze použít i v embedded Java prostředí. Lze jej také použít
v [evitaDB konzoli](/documentation/blog/en/09-our-new-web-client-evitalab.md). GraphQL a REST Web API používají podobný formát, ale přizpůsobený
konvencím daného protokolu (abychom mohli využít Open API / GQL schéma).

<LS to="j">

## Definice dotazů v Java kódu

Pro vytvoření dotazu použijte statické metody `query` ve třídě
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/Query.java</SourceClass> a poté
skládejte vnitřní omezení pomocí statických metod ve třídě
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/QueryConstraints.java</SourceClass>.

Pokud tuto třídu importujete staticky, definice dotazu v Javě vypadá podobně jako řetězcová forma dotazu.
Díky typové inferenci vám IDE pomůže s automatickým doplňováním omezení, která dávají v daném kontextu smysl.

Toto je příklad, jak je dotaz složen a jak se volá evitaDB. Příklad staticky importuje dvě třídy:
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/Query.java</SourceClass> a
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/QueryConstraints.java</SourceClass>.

<SourceCodeTabs setup="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java">

[Příklad Java dotazu](/documentation/user/en/use/api/example/java-query-example.java)

</SourceCodeTabs>

### Automatické čištění dotazů

Dotaz může obsahovat i "nečisté" části – tedy null omezení a zbytečné části:

<SourceCodeTabs setup="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java">

[Příklad nečistého Java dotazu](/documentation/user/en/use/api/example/java-dirty-query-example.java)

</SourceCodeTabs>

Dotaz je před zpracováním v engine evitaDB automaticky vyčištěn a zbytečná omezení jsou odstraněna.

### Parsování dotazů

Třída <SourceClass>evita_query/src/main/java/io/evitadb/api/query/QueryParser.java</SourceClass> umožňuje parsovat
[String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html) dotaz do AST
formy třídy <SourceClass>evita_query/src/main/java/io/evitadb/api/query/Query.java</SourceClass>.
Řetězcovou notaci *evitaQL* lze kdykoliv vytvořit zavoláním metody `toString()` na objektu
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/Query.java</SourceClass>.

Parser podporuje předávání hodnot odkazem, obdobně jako JDBC
[prepared statement](https://docs.oracle.com/javase/tutorial/jdbc/basics/prepared.html),
což umožňuje použití znaku `?` v dotazu a vrací pole správně seřazených vstupních parametrů.

Podporuje také tzv.
[named queries](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/core/namedparam/NamedParameterJdbcTemplate.html),
které jsou hojně využívány ve [Spring frameworku](https://spring.io/projects/spring-data-jdbc), pomocí proměnných ve formátu `@name` a předáním [mapy](https://docs.oracle.com/javase/8/docs/api/java/util/Map.html) s pojmenovanými vstupními parametry.

V opačném směru nabízí metoda `toStringWithParameterExtraction` na objektu
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/Query.java</SourceClass>, která umožňuje
vytvořit řetězcový formát *evitaQL* ve formě *prepared statement* a extrahovat všechny parametry do samostatného pole.

### Práce s dotazem

Existuje několik užitečných visitorů (další budou přibývat), které umožňují pracovat s dotazem. Jsou umístěny v balíčku
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/visitor/</SourceClass> a některé mají zkratkové metody
ve třídě <SourceClass>evita_query/src/main/java/io/evitadb/api/query/QueryUtils.java</SourceClass>.

Dotaz lze "hezky vypsat" pomocí metody `prettyPrint` na třídě
<SourceClass>evita_query/src/main/java/io/evitadb/api/query/Query.java</SourceClass>.

## Získávání dat

Ve výchozím nastavení jsou ve výsledku dotazu vráceny pouze primární klíče entit. V tomto nejjednodušším případě je každá entita
reprezentována rozhraním
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/EntityReferenceContract.java</SourceClass>.

<SourceCodeTabs setup="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java">

[Příklad výchozího dotazu](/documentation/user/en/use/api/example/default-query-example.java)

</SourceCodeTabs>

Klientská aplikace může požadovat vrácení těla entit, ale to je třeba explicitně požadovat pomocí
specifického require omezení (nebo jejich kombinace):

- [entity fetch](../../query/requirements/fetching.md#načtení-entity)
- [attribute fetch](../../query/requirements/fetching.md#obsah-atributů)
- [associated data fetch](../../query/requirements/fetching.md#obsah-souvisejících-dat)
- [price fetch](../../query/requirements/fetching.md#obsah-cen)
- [reference fetch](../../query/requirements/fetching.md#referenční-obsah)

Pokud je použito takové require omezení, data budou načtena *nenasytně* při prvním požadavku. Odpovědní objekt
pak bude obsahovat entity ve formě
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/SealedEntity.java</SourceClass>.

<SourceCodeTabs setup="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java">

[Příklad načítání dat](/documentation/user/en/use/api/example/fetching-example.java)

</SourceCodeTabs>

Ačkoliv existují jednodušší varianty dotazování entit, typická metoda je `query`, která vrací komplexní objekt
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/EvitaResponse.java</SourceClass> obsahující:

- **<SourceClass>evita_common/src/main/java/io/evitadb/dataType/DataChunk.java</SourceClass>** s výslednými entitami ve
  formě <SourceClass>evita_common/src/main/java/io/evitadb/dataType/PaginatedList.java</SourceClass> nebo
  <SourceClass>evita_common/src/main/java/io/evitadb/dataType/StripList.java</SourceClass>
- [Map](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Map.html) s extra výsledky indexovanými jejich třídou (`<T extends EvitaResponseExtraResult> Map<Class<T>, T>`)

Další příklad dokumentuje načítání druhé stránky produktů v kategorii s vypočtenými statistikami facet:

<SourceCodeTabs setup="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java">

[Příklad načítání dat](/documentation/user/en/use/api/example/query-example.java)

</SourceCodeTabs>

Existují zkratky pro volání dotazu s očekávanou formou entity, takže není nutné deklarovat očekávanou formu entity ve druhém argumentu metody `query`:

- `queryEntityReference` vrací <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/structure/EntityReference.java</SourceClass>
- `querySealedEntity` vrací <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/SealedEntity.java</SourceClass>

### Líné načítání (obohacování)

Atributy, asociovaná data, ceny a reference lze načítat samostatně pomocí primárního klíče entity.
Počáteční entita načtená pomocí [entity fetch](../../query/requirements/fetching.md) s omezenou sadou požadavků
může být později obohacena o chybějící data.

Pro obohacení, tj. líné načtení chybějících dat do existující entity, musíte předat existující entitu metodě `enrichEntity`
a specifikovat sadu dodatečných require omezení, která mají být splněna. Díky vlastnostem neměnnosti vynuceným návrhem databáze
vrací obohacení entity nový objekt entity.

<SourceCodeTabs setup="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java">

[Příklad líného načítání](/documentation/user/en/use/api/example/lazy-fetch-example.java)

</SourceCodeTabs>

Líné načítání nemusí být nutné pro frontend navržený pomocí MVC architektury, kde jsou všechny požadavky na stránku známy před vykreslením.
Jiné architektury však mohou načítat "tenčí" formy entit a později zjistit, že potřebují více dat. Tento přístup sice není optimální z hlediska výkonu,
ale může vývojářům usnadnit práci a je mnohem efektivnější pouze obohatit existující entitu (vyhledáním podle primárního klíče a načtením pouze chybějících dat),
než znovu načítat celou entitu.

<Note type="warning">
Líné načítání je aktuálně plně implementováno pouze pro embedded evitaDB. Pokud používáte evitaDB vzdáleně přes
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass>,
můžete stále použít metodu `enrichEntity` na rozhraní
<SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass>, ale entita bude znovu načtena celá.
Tento scénář plánujeme v budoucnu optimalizovat.
</Note>

## Vlastní kontrakty

Data získaná z evitaDB jsou reprezentována interními datovými strukturami evitaDB, které používají doménové názvy
spojené s reprezentací evitaDB. Ve své aplikaci můžete chtít používat vlastní doménové názvy a datové struktury.
Naštěstí evitaDB umožňuje definovat vlastní kontrakty pro získávání dat a používat je k načítání a
[zápisu dat](write-data.md#vlastní-kontrakty) do evitaDB. Tato kapitola popisuje, jak definovat vlastní kontrakty pro získávání dat.
Základní požadavky a vzory použití jsou popsány v [kapitole Java konektoru](../connectors/java.md#vlastní-kontrakty).

Čtecí kontrakt je určen jak pro [definici schématu](schema-api.md#deklarativní-definice-schématu), tak pro získávání dat.
Můžete však mít více čtecích kontraktů s různým rozsahem, které reprezentují stejnou entitu.

Kromě [anotací pro řízení schématu](schema-api.md#anotace-pro-řízení-schématu), které můžete použít k popisu čtecího kontraktu,
můžete také použít zkrácené anotace, které nevyžadují opakování celé struktury entity potřebné pro definici schématu:

<dl>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/EntityRef.java</SourceClass></dt>
    <dd>
        Anotaci lze umístit na metody, které by měly vracet jiné tělo entity.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/PrimaryKeyRef.java</SourceClass></dt>
    <dd>
        Anotaci lze umístit na metody vracející číselný datový typ (obvykle [int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)),
        které by měly vracet primární klíč přiřazený entitě.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/AttributeRef.java</SourceClass></dt>
    <dd>
        Anotaci lze umístit na metody, které by měly vracet atribut entity nebo reference [attribute](../../use/schema.md#attribute).
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/AssociatedDataRef.java</SourceClass></dt>
    <dd>
        Anotaci lze umístit na metody, které by měly vracet [asociovaná data](../../use/schema.md#přidružená-data) entity.
        Pokud asociovaná data představují vlastní Java typ převedený na [komplexní datový typ](../data-types.md#komplexní-datové-typy),
        implementace poskytuje automatickou konverzi tohoto datového typu.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/PriceForSaleRef.java</SourceClass></dt>
    <dd>
        Anotaci lze umístit na metodu vracející typ
        <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceContract.java</SourceClass>
        pro přístup k prodejní ceně entity.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/AccompaniedPrice.java</SourceClass></dt>
    <dd>
        Anotaci lze umístit na metodu vracející typ
        <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceContract.java</SourceClass>
        pro přístup k dalším cenám vypočteným spolu s prodejní cenou entity. Takových cen může být více, rozlišených atributem `name`.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/ReferenceRef.java</SourceClass></dt>
    <dd>
        Anotaci lze umístit na metody, které by měly vracet [referenci nebo odraženou referenci](../../use/schema.md#reference) 
        na jinou entitu. Může odkazovat na jinou modelovou třídu (interface/class/record), která obsahuje vlastnosti pro 
        anotace `@ReferencedEntity` a `@ReferencedEntityGroup` a atributy relace, nebo přímo na jiný čtecí kontrakt entity
        anotovaný anotací `@Entity` nebo `@EntityRef`.
    </dd>
</dl>

<Note type="warning">

Protože evitaDB umožňuje částečné načítání entity, nemusí být všechna data v kontraktu dostupná. Pokud k nim přistoupíte,
dostanete hodnotu NULL, která může znamenat jak to, že data nejsou dostupná, tak to, že data neexistují.
Pokud potřebujete tyto dva případy rozlišit, měly by vaše metody používat
<SourceClass>evita_api/src/main/java/io/evitadb/api/exception/ContextMissingException.java</SourceClass>.
Tato výjimka je runtime výjimka, takže volající ji nemusí ošetřovat, ale signalizuje automatické implementaci evitaDB,
aby vyhodila výjimku, pokud požadovaná data nebyla s entitou načtena.

Líné načítání dat zatím není pro vlastní kontrakty podporováno, ale plánujeme automaticky načítat chybějící data,
pokud dojde k volání metody ve scope, kde je dostupná evita session.

</Note>

Všechny příklady v této kapitole jsou ve třech variantách: interface, record a class. Varianta interface je nejuniverzálnější
a lze ji použít i pro třídy. Pokud však použijete variantu record nebo class s final poli, kde jsou data předávána přes konstruktor,
jste omezeni v některých funkcích a chování:

- Nelze rozlišit, zda data nebyla načtena, nebo neexistují.
- Nelze použít kontrolovatelné přístupové metody, které mění výstup podle parametrů metody (například `String getName(Locale locale)`),
  protože není možné reprezentovat parametry metody v konstruktorových argumentech.

Varianty record a immutable class jsou vhodné pro sekundární datové struktury nebo zjednodušené struktury
vracené jako výsledek volání getterů referencí, kde nepotřebujete plnohodnotný čtecí kontrakt.

Metody anotované těmito anotacemi musí dodržovat očekávané konvence signatury metody:

### Primární klíč

Pro přístup k primárnímu klíči entity musíte použít číselný datový typ (obvykle
[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)) a anotovat jej anotací <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/PrimaryKey.java</SourceClass>
nebo <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/PrimaryKeyRef.java</SourceClass>:

<SourceAlternativeTabs variants="interface|record|class">

[Příklad rozhraní s přístupem k primárnímu klíči](/documentation/user/en/use/api/example/primary-key-read-interface.java)

</SourceAlternativeTabs>

### Atributy

Pro přístup k atributu entity nebo reference musíte použít odpovídající datový typ a anotovat jej
anotací <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Attribute.java</SourceClass>
nebo <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/AttributeRef.java</SourceClass>.
Datový typ může být zabalen v [Optional](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Optional.html)
(nebo jeho obdobách [OptionalInt](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/OptionalInt.html)
nebo [OptionalLong](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/OptionalLong.html)).

Pokud atribut představuje vícenásobný typ (pole), můžete jej také zabalit do [Collection](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Collection.html)
(nebo jejích specializací [List](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/List.html)
nebo [Set](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Set.html)). Pravidla platí jak pro atributy entity, tak reference:

<SourceAlternativeTabs variants="interface|record|class">

[Příklad rozhraní s přístupem k atributům](/documentation/user/en/use/api/example/attribute-read-interface.java)

</SourceAlternativeTabs>

<Note type="info">

Datové typy Java enum jsou automaticky převáděny na stringový datový typ evitaDB pomocí metody `name()` a zpět pomocí metody `valueOf()`.

</Note>

<Note type="warning">

Vyvarujte se deklarace metod, které vracejí primitivní datový typ bez vyhazování `ContextMissingException`. Volání metody
může selhat s `NullPointerException`, pokud data nebyla načtena, i když byla deklarována jako povinná (ne-nullovatelná).

</Note>

### Asociovaná data

Pro přístup k asociovaným datům entity nebo reference musíte použít odpovídající datový typ a anotovat jej
anotací <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/AssociatedData.java</SourceClass>
nebo <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/AssociatedDataRef.java</SourceClass>.
Datový typ může být zabalen v [Optional](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Optional.html)
(nebo jeho obdobách [OptionalInt](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/OptionalInt.html)
nebo [OptionalLong](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/OptionalLong.html)).

Pokud asociovaná data představují vícenásobný typ (pole), můžete jej také zabalit do [Collection](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Collection.html)
(nebo jejích specializací [List](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/List.html)
nebo [Set](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Set.html)).

<SourceAlternativeTabs variants="interface|record|class">

[Příklad rozhraní s přístupem k asociovaným datům](/documentation/user/en/use/api/example/associated-data-read-interface.java)

</SourceAlternativeTabs>

Pokud metoda vrací ["nepodporovaný datový typ"](../data-types.md#jednoduché-datové-typy), evitaDB automaticky převádí data
z ["komplexního datového typu"](../data-types.md#komplexní-datové-typy) pomocí [zdokumentovaných pravidel deserializace](../data-types.md#deserializace).

### Ceny

Pro přístup k cenám entity musíte vždy pracovat s datovým typem
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceContract.java</SourceClass>
a anotovat metody anotací <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Price.java</SourceClass>,
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/PriceForSale.java</SourceClass>,
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/PriceForSaleRef.java</SourceClass> nebo
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/AccompanyingPrice.java</SourceClass>.
Datový typ může být zabalen v
[Optional](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Optional.html)
(nebo jeho obdobách [OptionalInt](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/OptionalInt.html)
nebo [OptionalLong](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/OptionalLong.html)).

Pokud metoda může vracet více cen, musíte ji zabalit do [Collection](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Collection.html)
(nebo jejích specializací [List](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/List.html) nebo [Set](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Set.html)).

<SourceAlternativeTabs variants="interface|record|class">

[Příklad rozhraní s cenami](/documentation/user/en/use/api/example/price-read-interface.java)

</SourceAlternativeTabs>

Metoda může vracet null, pokud je entita kořenová. Proto se nedoporučuje používat primitivní datové typy,
protože v takovém případě může volání metody selhat s `NullPointerException`.

### Hierarchie

Pro přístup k informacím o umístění entity v hierarchii (tj. jejímu rodiči) musíte použít buď číselný datový typ,
vlastní typ rozhraní, <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/SealedEntity.java</SourceClass>
nebo <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/structure/EntityReference.java</SourceClass>
a anotovat jej anotací <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/ParentEntity.java</SourceClass>.
Datový typ může být zabalen v
[Optional](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Optional.html)
(nebo jeho obdobách [OptionalInt](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/OptionalInt.html)
nebo [OptionalLong](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/OptionalLong.html)).

<SourceAlternativeTabs variants="interface|record|class">

[Příklad rozhraní s přístupem k rodiči](/documentation/user/en/use/api/example/parent-read-interface.java)

</SourceAlternativeTabs>

Metoda může vracet null, pokud je entita kořenová. Proto se nedoporučuje používat primitivní datové typy,
protože v takovém případě může volání metody selhat s `NullPointerException`.

### Reference

Pro přístup k referencím entity musíte použít buď číselný datový typ, vlastní typ rozhraní,
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/EntityReferenceContract.java</SourceClass>
nebo <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/ReferenceContract.java</SourceClass>
a anotovat jej anotací <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Reference.java</SourceClass>
nebo <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/ReferenceRef.java</SourceClass>.
Datový typ může být zabalen v
[Optional](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Optional.html)
(nebo jeho obdobách [OptionalInt](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/OptionalInt.html)
nebo [OptionalLong](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/OptionalLong.html)).

Pokud metoda může vracet více referencí, musíte ji zabalit do [Collection](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Collection.html)
(nebo jejích specializací [List](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/List.html) nebo [Set](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Set.html)).

<SourceAlternativeTabs variants="interface|record|class">

[Příklad rozhraní s referencemi](/documentation/user/en/use/api/example/reference-read-interface.java)

</SourceAlternativeTabs>

Pokud deklarujete návrat vlastního rozhraní, můžete vracet buď vlastní rozhraní referencované entity
(tj. rozhraní anotované <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Entity.java</SourceClass>
nebo <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/EntityRef.java</SourceClass>)
nebo rozhraní mapující referenci. Druhá možnost umožňuje přístup k atributům reference a může obsahovat i další metody pro přístup k referencované entitě.
V prvním případě můžete přistupovat k referencované entitě, ale ne k atributům reference.

Metody anotované touto anotací by měly respektovat kardinalitu reference. Pokud je kardinalita
`EXACTLY_ONE` nebo `ZERO_OR_ONE`, měla by metoda přímo vracet entitu nebo referenci na ni. Pokud je kardinalita
`ZERO_OR_MORE` nebo `ONE_OR_MORE`, měla by metoda vracet kolekci nebo pole entit či referencí na ně.

### Přístup k datovým strukturám evitaDB

Váš čtecí kontrakt může implementovat následující rozhraní pro přístup k podkladovým datovým strukturám evitaDB:

<dl>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/proxy/WithVersion.java</SourceClass></dt>
  <dd>umožňuje přístup k `version` entity přes metodu `version()`</dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/proxy/WithLocales.java</SourceClass></dt>
  <dd>umožňuje přístup k `locales`, se kterými byla entita načtena, a `allLocales`, které představují všechny možné lokalizace této entity</dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/proxy/WithEntitySchema.java</SourceClass></dt>
  <dd>umožňuje přístup ke schématu entity přes metodu `entitySchema()`</dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/proxy/WithEntityContract.java</SourceClass></dt>
  <dd>umožňuje přístup k podkladové entitě evitaDB přes metodu `entity()`</dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/proxy/WithEntityBuilder.java</SourceClass></dt>
  <dd>umožňuje přístup k podkladovému builderu entity evitaDB přes metodu `entityBuilder()` (automaticky vytvoří nový, pokud ještě nebyl požadován) nebo metodu `entityBuilderIfPresent`.</dd>
</dl>

<Note type="info">

Všechny generované proxy automaticky implementují rozhraní <SourceClass>evita_api/src/main/java/io/evitadb/api/proxy/EvitaProxy.java</SourceClass>,
takže je můžete odlišit od ostatních tříd.

</Note>

<Note type="warning">

evitaDB nemůže automaticky implementovat tato rozhraní, pokud je váš čtecí kontrakt record nebo final/sealed třída.
V takovém případě musíte tato rozhraní implementovat ručně.

</Note>

## Poznámky k cachování

Pokud používáte embedded evitaDB a [nemáte tuto funkci vypnutou](../../operate/configure.md#konfigurace-cache),
engine evitaDB automaticky cachuje mezivýsledky výpočtů a často používaná těla entit až do definovaného limitu paměti.
Podrobnosti o cachování jsou [popsány zde](../../deep-dive/cache.md). Pro embedded prostředí se nedoporučuje implementovat vlastní cache nad cache evitaDB.

Pokud používáte
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass>,
implementace lokální cache vám může ušetřit síťové náklady a zlepšit latenci. Problém je v invalidaci cache.
Museli byste dotazovat pouze entity reference, které obsahují informaci o verzi, a entity, které nejsou v cache, načítat samostatným požadavkem.
Místo jednoho síťového požadavku tak musíte provést dva. Přínos lokální cache je proto poněkud sporný.

</LS>

<LS to="c">

## Definice dotazů v C# kódu

Pro vytvoření dotazu a skládání vnitřních omezení použijte statické metody ve třídě
<SourceClass>EvitaDB.Client/Queries/IQueryConstraints.cs</SourceClass>.

Pokud tuto třídu importujete staticky, definice dotazu v C# vypadá podobně jako řetězcová forma dotazu.
Díky typové inferenci vám IDE pomůže s automatickým doplňováním omezení, která dávají v daném kontextu smysl.

Toto je příklad, jak je dotaz složen a jak se volá evitaDB.
Příklad staticky importuje výše zmíněné rozhraní
<SourceClass>EvitaDB.Client/Queries/IQueryConstraints.cs</SourceClass>.

<SourceCodeTabs setup="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java">

[Příklad C# dotazu](/documentation/user/en/use/api/example/csharp-query-example.cs)

</SourceCodeTabs>

### Automatické čištění dotazů

Dotaz může obsahovat i "nečisté" části – tedy null omezení a zbytečné části:

<SourceCodeTabs setup="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java">

[Příklad nečistého C# dotazu](/documentation/user/en/use/api/example/csharp-dirty-query-example.cs)

</SourceCodeTabs>

Dotaz je před zpracováním v engine evitaDB automaticky vyčištěn a zbytečná omezení jsou odstraněna.

### Práce s dotazem

Existuje několik užitečných visitorů (další budou přibývat), které umožňují pracovat s dotazem. Jsou umístěny v namespace
`EvitaDB.Client.Queries.Visitor` a některé mají zkratkové metody
ve třídě <SourceClass>EvitaDB.Client/Utils/QueryUtils.cs</SourceClass>.

Dotaz lze "hezky vypsat" pomocí metody `PrettyPrint` na třídě
<SourceClass>EvitaDB.Client/Queries/Query.cs</SourceClass>.

### Získávání dat

Ve výchozím nastavení jsou ve výsledku dotazu vráceny pouze primární klíče entit. V tomto nejjednodušším případě je každá entita
reprezentována rozhraním
<SourceClass>EvitaDB.Client/Models/Data/IEntityReference.cs</SourceClass>.

<SourceCodeTabs setup="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java">

[Příklad výchozího dotazu](/documentation/user/en/use/api/example/default-query-example.cs)

</SourceCodeTabs>

Klientská aplikace může požadovat vrácení těla entit, ale to je třeba explicitně požadovat pomocí
specifického require omezení (nebo jejich kombinace):

- [entity fetch](../../query/requirements/fetching.md#načtení-entity)
- [attribute fetch](../../query/requirements/fetching.md#obsah-atributů)
- [associated data fetch](../../query/requirements/fetching.md#obsah-souvisejících-dat)
- [price fetch](../../query/requirements/fetching.md#obsah-cen)
- [reference fetch](../../query/requirements/fetching.md#referenční-obsah)

Pokud je použito takové `require` omezení, data budou načtena *nenasytně* při prvním požadavku. Odpovědní objekt
pak bude obsahovat entity ve formě
<SourceClass>EvitaDB.Client/Models/Data/ISealedEntity.cs</SourceClass>.

<SourceCodeTabs setup="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java">

[Příklad načítání dat](/documentation/user/en/use/api/example/fetching-example.cs)

</SourceCodeTabs>

Ačkoliv existují jednodušší varianty dotazování entit, typická metoda je `Query`, která vrací komplexní objekt
<SourceClass>EvitaDB.Client/Models/EvitaResponse.cs</SourceClass> obsahující:

- **<SourceClass>EvitaDB.Client/DataTypes/IDataChunk.cs</SourceClass>** s výslednými entitami ve
  formě <SourceClass>EvitaDB.Client/DataTypes/PaginatedList.cs</SourceClass> nebo
  <SourceClass>EvitaDB.Client/DataTypes/StripList.cs</SourceClass>
- [Dictionary](https://learn.microsoft.com/cs-cz/dotnet/api/system.collections.idictionary) s extra výsledky indexovanými jejich typem (`IDictionary<Type,IEvitaResponseExtraResult>`)

Další příklad dokumentuje načítání druhé stránky produktů v kategorii s vypočtenými statistikami facet:

<SourceCodeTabs setup="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java">

[Příklad načítání dat](/documentation/user/en/use/api/example/query-example.cs)

</SourceCodeTabs>

Existují zkratky pro volání dotazu s očekávanou formou entity, takže není nutné deklarovat očekávanou formu entity ve druhém argumentu metody `Query`:

- `QueryEntityReference` vrací <SourceClass>EvitaDB.Client/Models/Data/Structure/EntityReference.cs</SourceClass>
- `QuerySealedEntity` vrací <SourceClass>EvitaDB.Client/Models/Data/ISealedEntity.cs</SourceClass>

#### Líné načítání (obohacování)

Atributy, asociovaná data, ceny a reference lze načítat samostatně pomocí primárního klíče entity.
Počáteční entita načtená pomocí [entity fetch](../../query/requirements/fetching.md) s omezenou sadou požadavků
může být později obohacena o chybějící data.

Pro obohacení, tj. líné načtení chybějících dat do existující entity, musíte předat existující entitu metodě `EnrichEntity`
a specifikovat sadu dodatečných require omezení, která mají být splněna. Díky vlastnostem neměnnosti vynuceným návrhem databáze
vrací obohacení entity nový objekt entity.

<SourceCodeTabs setup="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java">

[Příklad líného načítání](/documentation/user/en/use/api/example/lazy-fetch-example.cs)

</SourceCodeTabs>

Líné načítání nemusí být nutné pro frontend navržený pomocí MVC architektury, kde jsou všechny požadavky na stránku známy před vykreslením.
Jiné architektury však mohou načítat "tenčí" formy entit a později zjistit, že potřebují více dat. Tento přístup sice není optimální z hlediska výkonu,
ale může vývojářům usnadnit práci a je mnohem efektivnější pouze obohatit existující entitu (vyhledáním podle primárního klíče a načtením pouze chybějících dat),
než znovu načítat celou entitu.

<Note type="warning">
Líné načítání je aktuálně plně implementováno pouze pro embedded evitaDB. Pokud používáte evitaDB vzdáleně přes
<SourceClass>EvitaDB.Client/EvitaClient.cs</SourceClass>, můžete stále použít metodu `EnrichEntity` na instanci
<SourceClass>EvitaDB.Client/EvitaClientSession.cs</SourceClass>, ale entita bude znovu načtena celá.
Tento scénář plánujeme v budoucnu optimalizovat.
</Note>

### Poznámky k cachování

Pokud používáte embedded evitaDB a [nemáte tuto funkci vypnutou](../../operate/configure.md#konfigurace-cache),
engine evitaDB automaticky cachuje mezivýsledky výpočtů a často používaná těla entit až do definovaného limitu paměti.
Podrobnosti o cachování jsou [popsány zde](../../deep-dive/cache.md). Pro embedded prostředí se nedoporučuje implementovat vlastní cache nad cache evitaDB.

Pokud používáte <SourceClass>EvitaDB.Client/EvitaClient.cs</SourceClass>, implementace lokální cache vám může ušetřit síťové náklady a
zlepšit latenci. Problém je v invalidaci cache. Museli byste dotazovat pouze entity reference, které obsahují informaci o verzi,
a entity, které nejsou v cache, načítat samostatným požadavkem.
Místo jednoho síťového požadavku tak musíte provést dva. Přínos lokální cache je proto poněkud sporný.

</LS>
<LS to="g">

## Definice dotazů v GraphQL API

V GraphQL API je původní dotaz evitaDB rozdělen na dvě místa, každé s vlastní syntaxí:

- argumenty polí dotazu
  - obsahují především filtrační a řadicí části původního dotazu evitaDB
  - mohou také obsahovat požadavky, které mění nastavení zpracování dotazu a přímo neovlivňují výstup dotazu, např. `facetGroupsConjuction`
- výstupní pole dotazu
  - definováním výstupních polí, tj. datové struktury, která má být vrácena, GraphQL API automaticky překládá požadovaná pole na požadavky evitaDB, takže je nemusíte specifikovat ručně

Každý GraphQL dotaz používá některou z výše uvedených forem syntaxe. Každá [kolekce entit](/documentation/user/en/use/data-model.md#collection)
má k dispozici následující GraphQL dotazy:

- `getCollecionName`
- `listCollectionName`
- `queryCollectionName`

kde `CollectionName` je název konkrétní [kolekce entit](/documentation/user/en/use/data-model.md#collection), např. `queryProduct` nebo `queryCategory`.

### `get` dotazy

Dotazy `getCollecionName` podporují pouze velmi zjednodušenou variantu filtrační části dotazu, ale podporují načítání
bohatých objektů entit. Výsledkem je, že získáte pouze konkrétní objekt entity bez zbytečných dat okolo.
Tyto zjednodušené dotazy jsou primárně určeny pro vývoj nebo prozkoumávání API podle unikátních klíčů,
protože poskytují rychlý přístup k entitám.

<SourceCodeTabs langSpecificTabOnly>

[Příklad GraphQL get dotazu](/documentation/user/en/use/api/example/graphql-get-query-example.graphql)

</SourceCodeTabs>

#### `getEntity` dotaz

Existuje také speciální varianta dotazů `get` s pevným klasifikátorem `entity` v názvu -> `getEntity`. Tento dotaz
je určen pro případy, kdy potřebujete načíst entitu, ale máte pouze globálně unikátní identifikátor a neznáte cílovou kolekci entit.
Dotaz pak vrátí obecný objekt entity, který bude obsahovat pouze data společná všem kolekcím entit:

<SourceCodeTabs langSpecificTabOnly>

[Příklad GraphQL get entity dotazu](/documentation/user/en/use/api/example/graphql-get-entity-query-example.graphql)

</SourceCodeTabs>

Můžete však použít pole `targetEntity` pro získání skutečného objektu entity (specifického pro určenou kolekci entit),
ale s jednou výhradou. Musíte použít [inline fragmenty](https://graphql.org/learn/queries/#inline-fragments),
abyste specifikovali strukturu pole pro každý cílový objekt entity, který chcete podporovat a získat skutečná data:

<SourceCodeTabs langSpecificTabOnly>

[Příklad GraphQL get entity s target entity](/documentation/user/en/use/api/example/graphql-get-entity-with-target-entity-query-example.graphql)

</SourceCodeTabs>

### `list` dotazy

Dotazy `listCollectionName` podporují plnou filtrační a řadicí část dotazu evitaDB jako argumenty dotazu a načítání
bohatých objektů entit. Výsledkem je, že získáte jednoduchý seznam entit bez nutnosti řešit
složitější odpověď s stránkováním a extra výsledky jako u dotazu `queryCollectionName`.
Tyto dotazy jsou určeny jako rychlý způsob/zkratka pro získání seznamu entit, pokud nejsou potřeba extra výsledky nebo pokročilejší stránkování.

<SourceCodeTabs langSpecificTabOnly>

[Příklad GraphQL list dotazu](/documentation/user/en/use/api/example/graphql-list-query-example.graphql)

</SourceCodeTabs>

#### `listEntity` dotaz

Existuje také speciální varianta dotazů `list` s pevným klasifikátorem `entity` v názvu -> `listEntity`. Tento dotaz
je v podstatě rozšířením [`getEntity`](#getentity-dotaz), který přijímá více identifikátorů a je určen pro případy,
kdy potřebujete načíst jednu nebo více entit, ale máte pouze globálně unikátní identifikátory a neznáte cílovou kolekci entit.
Protože dotaz `listEntity` přijímá více identifikátorů, každá vrácená entita může být z jiné kolekce entit.
Dotaz pak vrátí seznam obecných objektů entit, které obsahují pouze data společná všem kolekcím entit:

<SourceCodeTabs langSpecificTabOnly>

[Příklad GraphQL list entity dotazu](/documentation/user/en/use/api/example/graphql-list-entity-query-example.graphql)

</SourceCodeTabs>

Můžete však použít pole `targetEntity` pro získání skutečného objektu entity (specifického pro určenou kolekci entit),
ale s jednou výhradou. Musíte použít [inline fragmenty](https://graphql.org/learn/queries/#inline-fragments),
abyste specifikovali strukturu pole pro každý cílový objekt entity, který chcete podporovat a získat skutečná data:

<SourceCodeTabs langSpecificTabOnly>

[Příklad GraphQL list entity s target entity](/documentation/user/en/use/api/example/graphql-list-entity-with-target-entity-query-example.graphql)

</SourceCodeTabs>

### `query` dotazy

Dotazy `queryCollectionName` jsou plnohodnotné dotazy, které byste měli používat hlavně v případech,
kdy počet entit není předem znám nebo jsou potřeba extra výsledky, protože podporují
všechny funkce dotazování evitaDB. Kvůli všem těmto funkcím jsou odpovědi těchto dotazů složitější než u ostatních dvou typů dotazů.
Kromě těl entit však můžete v jednom dotazu získat i metadata stránkování a extra výsledky.

<SourceCodeTabs langSpecificTabOnly>

[Příklad plného GraphQL dotazu](/documentation/user/en/use/api/example/graphql-full-query-example.graphql)

</SourceCodeTabs>

</LS>
<LS to="r">

## Definice dotazů v REST API

V REST API existuje několik endpointů pro načítání entit, které přijímají evitaQL dotazy v jedné či druhé podobě. Tyto
endpointy mají následující tvary URL:

- `/rest/catalog-name/entity-collection/get`
- `/rest/catalog-name/entity-collection/list`
- `/rest/catalog-name/entity-collection/query`

kde `catalog-name` je název konkrétního [katalogu](/documentation/user/en/use/data-model.md#catalog) a
`entity-collection` je název konkrétní [kolekce entit](/documentation/user/en/use/data-model.md#collection),
například `/rest/evita/product/get` nebo `/rest/evita/category/query`.

### `get` dotazy

Endpointy `/get` podporují pouze velmi zjednodušenou variantu filtrační a požadavkové části dotazu pomocí URL query
parametrů. Výsledkem je, že získáte pouze konkrétní objekt entity bez zbytečných dat okolo.
Tyto zjednodušené endpointy jsou primárně určeny pro vývoj nebo prozkoumávání API podle unikátních klíčů,
protože poskytují rychlý přístup k entitám.

<SourceCodeTabs langSpecificTabOnly>

[Příklad REST get dotazu](/documentation/user/en/use/api/example/rest-get-query-example.rest)

</SourceCodeTabs>

#### `/entity/get` dotaz

Existuje také speciální varianta dotazů `get` s pevným klasifikátorem `entity` -> `/entity/get`. Tento dotaz
je určen pro případy, kdy potřebujete načíst entitu, ale máte pouze globálně unikátní identifikátor a neznáte cílovou kolekci entit.
Dotaz pak vrátí cílový objekt entity podle určeného typu entity vrácené entity:

<SourceCodeTabs langSpecificTabOnly>

[Příklad REST get entity dotazu](/documentation/user/en/use/api/example/rest-get-entity-query-example.rest)

</SourceCodeTabs>

### `list` dotazy

Endpointy `/list` podporují plnou filtrační a řadicí část dotazu evitaDB, ale požadavková část je omezena pouze
na načítání těl entit, nikoliv extra výsledků. Výsledkem je, že získáte jednoduchý seznam entit bez nutnosti řešit
složitější odpověď s stránkováním a extra výsledky jako u endpointu `/query`.
Tyto dotazy jsou určeny jako rychlý způsob/zkratka pro získání seznamu entit, pokud nejsou potřeba extra výsledky nebo pokročilejší stránkování.

<SourceCodeTabs langSpecificTabOnly>

[Příklad REST list dotazu](/documentation/user/en/use/api/example/rest-list-query-example.rest)

</SourceCodeTabs>

#### `/entity/list` dotaz

Existuje také speciální varianta dotazů `list` s pevným klasifikátorem `entity` -> `/entity/list`. Tento dotaz
je v podstatě rozšířením [`/entity/get`](#entityget-dotaz), který přijímá více identifikátorů a je určen pro případy,
kdy potřebujete načíst jednu nebo více entit, ale máte pouze globálně unikátní identifikátory a neznáte cílovou kolekci entit.
Protože dotaz `/entity/list` přijímá více identifikátorů, každá vrácená entita může být z jiné kolekce entit.
Dotaz pak vrátí seznam cílových objektů entit podle určeného typu entity každé entity:

<SourceCodeTabs langSpecificTabOnly>

[Příklad REST list entity dotazu](/documentation/user/en/use/api/example/rest-list-entity-query-example.rest)

</SourceCodeTabs>

### `query` dotazy

Endpointy `/query` podporují plnohodnotné dotazy, které byste měli používat hlavně v případech,
kdy počet entit není předem znám nebo jsou potřeba extra výsledky, protože podporují
všechny funkce dotazování evitaDB. Kvůli všem těmto funkcím jsou odpovědi těchto endpointů složitější než u ostatních dvou typů endpointů.
Kromě těl entit však v jednom dotazu získáte i metadata stránkování a extra výsledky.

<SourceCodeTabs langSpecificTabOnly>

[Příklad plného REST dotazu](/documentation/user/en/use/api/example/rest-full-query-example.rest)

</SourceCodeTabs>

</LS>