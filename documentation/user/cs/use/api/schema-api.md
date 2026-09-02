---
title: Schema API
perex: 'V současné době můžete definovat schéma pomocí rozhraní Java, C#, REST a GraphQL API. Všechny tři přístupy jsou popsány v této kapitole.'
date: '17.1.2023'
author: Ing. Jan Novotný
proofreading: done
preferredLang: java
translated: 'true'
commit: '939634b9ad902a7fb058d9e91ef6e2b6c637964d'
---
<LS to="j">

## Imperativní definice schématu

Schéma lze programově definovat tímto způsobem:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Imperativní definice schématu přes Java API](/documentation/user/en/use/api/example/imperative-schema-definition.java)
</SourceCodeTabs>

## Deklarativní definice schématu

evitaDB nabízí alternativní způsob, jak definovat schéma typu entity. Můžete definovat modelovou třídu anotovanou
anotacemi evitaDB, které popisují strukturu entity, se kterou chcete ve svém projektu pracovat. Poté stačí požádat
<SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass> o definování schématu entity
za vás:

<SourceCodeTabs setup="/documentation/user/en/use/api/example/declarative-model-example.java,/documentation/user/en/get-started/example/define-test-catalog.java" local>

[Deklarativní definice schématu přes Java API](/documentation/user/en/use/api/example/declarative-schema-definition.java)
</SourceCodeTabs>

Šablona modelu může být:

- [rozhraní](https://www.baeldung.com/java-interfaces)
- [třída](https://www.baeldung.com/java-pojo-class)
- [record](https://www.baeldung.com/java-record-keyword)

### Anotace pro řízení schématu

Očekává se, že model bude anotován následujícími anotacemi:

<dl>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Entity.java</SourceClass></dt>
    <dd>
        Anotace může být umístěna pouze na typ Java (interface / třída / record) a označuje typ entity.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/PrimaryKey.java</SourceClass></dt>
    <dd>
        Anotace může být umístěna na [int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)
        pole / getter metodu / komponentu recordu a označuje [primární klíč](../schema.md#generování-primárního-klíče) entity.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Attribute.java</SourceClass></dt>
    <dd>
        Anotace může být umístěna na pole / getter metodu / komponentu recordu a označuje [atribut](../schema.md#atributy) entity.
        Výchozí hodnoty v případě rozhraní lze poskytnout pomocí výchozí implementace metody (viz příklad
        níže).
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/SortableAttributeCompound.java</SourceClass></dt>
    <dd>
        Anotace může být umístěna na třídu / record a označuje [sloučený atribut pro řazení](../schema.md#složené-atributy-pro-řazení) entity,
        který agreguje více atributů třídy do jednoho složeného atributu pro řazení, který nelze přímo přistupovat, ale lze jej použít v dotazu
        pro řazení.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/SortableAttributeCompounds.java</SourceClass></dt>
    <dd>
        Anotace může být umístěna na třídu / record jako kontejner pro více anotací `@SortableAttributeCompound`.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/AssociatedData.java</SourceClass></dt>
    <dd>
        Anotace může být umístěna na pole / getter metodu / komponentu recordu a označuje
        [asociovaná data](../schema.md#přidružená-data) entity.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/ParentEntity.java</SourceClass></dt>
    <dd>
        Anotace může být umístěna na pole / getter metodu / komponentu recordu a označuje referenci na jinou entitu,
        která představuje hierarchického rodiče této entity. Modelová třída by měla být stejná jako třída entity
        (viz anotace `@Entity`).
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Price.java</SourceClass></dt>
    <dd>
        Anotace může být umístěna na pole / getter metodu / komponentu recordu kolekce / pole typu
        <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceContract.java</SourceClass>,
        které poskytuje přístup ke všem cenám entity. Použití této anotace v modelové třídě entity povolí
        [ceny](../schema.md#ceny) ve schématu entity.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/PriceForSale.java</SourceClass></dt>
    <dd>
        Anotace může být umístěna na pole / getter metodu / komponentu recordu typu
        <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceContract.java</SourceClass>,
        které poskytuje přístup k prodejní ceně entity. Použití této anotace v modelové třídě entity povolí
        [ceny](../schema.md#ceny) ve schématu entity.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Reference.java</SourceClass></dt>
    <dd>
        <p>Anotace může být umístěna na pole / getter metodu / komponentu recordu a označuje entitu jako
        [referenci](../schema.md#reference) na jinou entitu. Může odkazovat na jinou modelovou třídu (interface/třída/record),
        která obsahuje vlastnosti pro anotace `@ReferencedEntity` a `@ReferencedEntityGroup` a relační
        atributy.</p>
        <p>Kromě základní konfigurace reference (`indexed`, `faceted`) anotace podporuje
        [podmíněné indexování facet](../schema.md#podmíněné-indexování-pomocí-výrazů) pomocí `facetedPartially`
        a [podmíněné histogramové indexování](../schema.md#referenční-histogramy) pomocí `bucketed`
        a `bucketedPartially`. Přepsání pro konkrétní scope lze specifikovat pomocí vnořené anotace `@ScopeReferenceSettings`.</p>
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/ReflectedReference.java</SourceClass></dt>
    <dd>
        <p>Anotace může být umístěna na pole / getter metodu / komponentu recordu a označuje entitu jako 
        [reflektovanou referenci](../schema.md#reference) na jinou entitu. Může odkazovat na jinou modelovou třídu (interface/třída/record),
        která obsahuje vlastnosti pro anotace `@ReferencedEntity` a `@ReferencedEntityGroup` a relační
        atributy.</p>
        <p>Původní reference nemusí být ve schématu ještě definována, ale musí být definována před potvrzením transakce
        nebo uzavřením session (ve fázi warm-up).</p>
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/ReferencedEntity.java</SourceClass></dt>
    <dd>
        Anotace může být umístěna na pole / getter metodu / komponentu recordu a označuje referenci na jinou entitu,
        která představuje referencovanou entitu pro tuto entitu. Modelová třída by měla reprezentovat model třídy entity
        (viz anotace `@Entity`).
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/ReferencedEntityGroup.java</SourceClass></dt>
    <dd>
        Anotace může být umístěna na pole / getter metodu / komponentu recordu a označuje referenci na jinou entitu,
        která představuje skupinu referencovaných entit pro tuto entitu. Modelová třída by měla reprezentovat model třídy
        entity (viz anotace `@Entity`).
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Expression.java</SourceClass></dt>
    <dd>
        <p>Anotace reprezentující hodnotu [EvitaEL výrazu](../../query/expression-language.md). Používá se v rámci
        jiných anotací — `@Reference` / `@ScopeReferenceSettings` — k definici vypočítaných hodnot nebo boolean podmínek.</p>
        <p>Například `@Expression("$reference.referencedEntity.attributes['status'] == 'ACTIVE'")` definuje podmínku
        pro [podmíněné indexování facet](../schema.md#podmíněné-indexování-pomocí-výrazů), a
        `@Expression("$reference.referencedEntity.attributes['basicUnitValue'] ?? 0.0")` definuje hodnotu
        pro [histogramové indexování](../schema.md#referenční-histogramy). Prázdný řetězec (výchozí) znamená,
        že není definován žádný výraz.</p>
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Histogram.java</SourceClass></dt>
    <dd>
        <p>Konfiguruje [histogramové (bucketované) indexování](../schema.md#referenční-histogramy) na referenci.
        Pokud je použito v rámci `@Reference` nebo `@ScopeReferenceSettings`, definuje pojmenovaný histogramový index s volitelným
        výrazem hodnoty, který vypočítává hodnotu bucketu pro každou referencovanou entitu.</p>
        <p>Atribut `nameOfTheIndex` identifikuje slot histogramu (jedna reference může mít více pojmenovaných histogramů).
        Atribut `value` přijímá `@Expression`, který se vyhodnotí na číselný atribut — například,
        `@Histogram(nameOfTheIndex = "priceHistogram", value = @Expression("$reference.referencedEntity.attributes['price']"))`.</p>
        <p>Výraz `value` může být vyhodnocen na skalární číselný atribut **nebo** na číselný atribut typu `NumberRange`
        (`ByteNumberRange` … `BigDecimalNumberRange`); zdroj rozsahu rozdělí každou instanci do všech bucketů, které jeho
        interval překrývá, a nesmí používat výchozí hodnotu `??` (viz [Reference histograms](../schema.md#referenční-histogramy)).
        Volitelný prvek `assignedWhen` přijímá `@Expression`, který funguje jako selektor partice pro daný histogram — mezi
        instancemi způsobilými přes `bucketedPartially` pouze ty, pro které `assignedWhen` vyhodnotí na `true`, vstupují do tohoto
        konkrétního histogramu.</p>
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/ScopeReferenceSettings.java</SourceClass></dt>
    <dd>
        <p>Používá se v rámci `@Reference` nebo `@ReflectedReference` k definici přepsání nastavení indexování referencí
        pro konkrétní scope. Každá instance konfiguruje jeden scope (např. `LIVE` nebo `ARCHIVED`) a může přepsat `indexed`,
        `faceted`, `facetedPartially`, `bucketed` a `bucketedPartially` nezávisle na výchozích hodnotách.</p>
        <p>Pokud jsou nastavení scope specifikována pro `LIVE`, obecná nastavení na `@Reference` jsou zcela ignorována.</p>
    </dd>
</dl>

Metody / pole / komponenty recordu, které nejsou anotovány, jsou při definici schématu ignorovány. Pro lepší představu
si ukažme ukázku návrhu rozhraní produktové entity.

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java" local>

[Příklad rozhraní modelu](/documentation/user/en/use/api/example/declarative-model-example.java)

</SourceCodeTabs>

<Note type="info">

Smlouvu můžete použít také pro definici schématu v [query API](query-data.md) jako očekávaný typ výsledku
a evitaDB automaticky vygeneruje vhodnou proxy třídu, která mapuje generickou podkladovou datovou strukturu
na smlouvu podle vaší představy. Více informací k tomuto tématu najdete
v kapitole [Java Connector](../connectors/java.md#vlastní-kontrakty).

</Note>

</LS>

<LS to="g">

Na rozdíl od přístupu v Javě podporuje GraphQL API pouze imperativní definici schématu. Schéma je definováno pomocí
atomických mutací, kde každá mutace přidává, mění nebo odebírá malou část celého schématu. Pro definici celého schématu
je obvykle potřeba předat sadu více mutací.

<Note type="question">

<NoteTitle toggles="true">

##### Proč používáme pro definici schématu přístup s mutacemi?
</NoteTitle>

Víme, že tento přístup není příliš uživatelsky přívětivý. Myšlenkou tohoto přístupu je však poskytnout jednoduchý a univerzální
způsob, jak programově definovat schéma (ve skutečnosti takto funguje evitaDB interně,
takže kolekce mutací je předána přímo do enginu na serveru). Očekává se, že vývojář
používající GraphQL API si vytvoří knihovnu např. s buildry schémat entit, které vygenerují kolekci mutací pro
definici schématu.

</Note>

Nové schéma katalogu můžete definovat nebo existující aktualizovat pomocí
[catalog schema API](/documentation/user/en/use/connectors/graphql.md#graphql-api-instances)
na adrese `https://your-server:5555/gql/evita/schema`:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Imperativní definice schématu katalogu přes GraphQL API](/documentation/user/en/use/api/example/imperative-catalog-schema-definition.graphql)
</SourceCodeTabs>

nebo aktualizovat schéma konkrétní kolekce entit na stejné adrese pomocí GraphQL mutace vybrané kolekce například takto:

<SourceCodeTabs setup="/documentation/user/en/use/api/example/imperative-schema-definition.java" langSpecificTabOnly local>

[Imperativní definice schématu kolekce přes GraphQL API](/documentation/user/en/use/api/example/imperative-collection-schema-definition.graphql)
</SourceCodeTabs>

</LS>

<LS to="r">

Na rozdíl od přístupu v Javě podporuje REST API pouze imperativní definici schématu. Schéma je definováno pomocí
atomických mutací, kde každá mutace přidává, mění nebo odebírá malou část celého schématu. Pro definici celého schématu
je obvykle potřeba předat sadu více mutací.

<Note type="question">

<NoteTitle toggles="true">

##### Proč používáme pro definici schématu přístup s mutacemi?
</NoteTitle>

Víme, že tento přístup není příliš uživatelsky přívětivý. Myšlenkou tohoto přístupu je však poskytnout jednoduchý a univerzální
způsob, jak programově definovat schéma s ohledem na transakce (ve skutečnosti takto funguje evitaDB interně,
takže kolekce mutací je předána přímo do enginu na serveru). Očekává se, že vývojář
používající REST API si vytvoří knihovnu např. s buildry schémat entit, které vygenerují kolekci mutací pro
definici schématu.

</Note>

Nové schéma katalogu můžete definovat nebo existující aktualizovat pomocí
[catalog API](/documentation/user/en/use/connectors/rest.md#rest-api-instances)
na adrese `https://your-server:5555/rest/evita/schema`:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Imperativní definice schématu katalogu přes REST API](/documentation/user/en/use/api/example/imperative-catalog-schema-definition.rest)
</SourceCodeTabs>

nebo aktualizovat schéma konkrétní kolekce entit například na adrese `https://your-server:5555/rest/evita/product/schema`
pro kolekci `Product` pomocí REST mutace vybrané kolekce například takto:

<SourceCodeTabs setup="/documentation/user/en/use/api/example/imperative-schema-definition.java" langSpecificTabOnly local>

[Imperativní definice schématu kolekce přes REST API](/documentation/user/en/use/api/example/imperative-collection-schema-definition.rest)
</SourceCodeTabs>

</LS>

<LS to="c">

Na rozdíl od přístupu v Javě podporuje C# klient pouze imperativní definici schématu.
Schéma je definováno pomocí builder patternu, který poskytuje rozhraní <SourceClass>EvitaDB.Client/Models/Schemas/IEntitySchemaBuilder.cs</SourceClass>.
Na pozadí je instance takového builderu převedena na kolekci mutací, které jsou odeslány na server.

## Imperativní definice schématu

Schéma lze programově definovat tímto způsobem:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Imperativní definice schématu přes AP evitaDB API](/documentation/user/en/use/api/example/imperative-schema-definition.cs)
</SourceCodeTabs>

</LS>

<LS to="e">
Bohužel v současné době není možné definovat schéma pomocí EvitaQL. Tato rozšíření také nejsou v blízké budoucnosti plánována,
protože věříme, že pro definici schématu jsou k dispozici dostatečné možnosti (Java, GraphQL, REST API).
</LS>