---
title: Schema API
perex: 'V současné době můžete definovat schéma pomocí API v jazycích Java, C#, REST a GraphQL. Všechny tři přístupy jsou popsány v této kapitole.'
date: '17.1.2023'
author: Ing. Jan Novotný
proofreading: done
preferredLang: java
commit: '6bff47e566d31ba841d90f906ee68c2ca121b03f'
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
        Anotaci lze umístit pouze na typ v jazyce Java (rozhraní / třída / record) a označuje typ entity.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/PrimaryKey.java</SourceClass></dt>
    <dd>
        Anotaci lze umístit na [int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)
        pole / getter metodu / komponentu recordu a označuje [primární klíč](../../use/schema.md#generování-primárního-klíče) entity.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Attribute.java</SourceClass></dt>
    <dd>
        Anotaci lze umístit na pole / getter metodu / komponentu recordu a označuje [atribut](../../use/schema.md#attribute) entity.
        Výchozí hodnoty v případě rozhraní lze poskytnout pomocí výchozí implementace metody (viz příklad níže).
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/SortableAttributeCompound.java</SourceClass></dt>
    <dd>
        Anotaci lze umístit na třídu / record a označuje [sloučený atribut pro řazení](../../use/schema.md#složené-atributy-pro-řazení) entity,
        který agreguje více atributů třídy do jednoho řaditelného celku, ke kterému není možné přistupovat přímo, ale lze jej použít v dotazu
        pro řazení.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/SortableAttributeCompounds.java</SourceClass></dt>
    <dd>
        Anotaci lze umístit na třídu / record jako kontejner pro více anotací `@SortableAttributeCompound`.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/AssociatedData.java</SourceClass></dt>
    <dd>
        Anotaci lze umístit na pole / getter metodu / komponentu recordu a označuje
        [asociovaná data](../../use/schema.md#přidružená-data) entity.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/ParentEntity.java</SourceClass></dt>
    <dd>
        Anotaci lze umístit na pole / getter metodu / komponentu recordu a označuje odkaz na jinou entitu,
        která představuje hierarchického rodiče této entity. Modelová třída by měla být stejná jako třída entity
        (viz anotace `@Entity`).
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Price.java</SourceClass></dt>
    <dd>
        Anotaci lze umístit na pole / getter metodu / komponentu recordu kolekce / pole typu
        <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceContract.java</SourceClass>,
        které poskytuje přístup ke všem cenám entity. Použití této anotace v modelové třídě entity povolí
        [ceny](../../use/schema.md#ceny) ve schématu entity.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/PriceForSale.java</SourceClass></dt>
    <dd>
        Anotaci lze umístit na pole / getter metodu / komponentu recordu typu
        <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceContract.java</SourceClass>,
        které poskytuje přístup k prodejní ceně entity. Použití této anotace v modelové třídě entity povolí
        [ceny](../../use/schema.md#ceny) ve schématu entity.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Reference.java</SourceClass></dt>
    <dd>
        Anotaci lze umístit na pole / getter metodu / komponentu recordu a označuje entitu jako
        [referenci](../../use/schema.md#reference) na jinou entitu. Může odkazovat na jinou modelovou třídu (rozhraní/třída/record),
        která obsahuje vlastnosti pro anotace `@ReferencedEntity` a `@ReferencedEntityGroup` a atributy vztahu.
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/ReflectedReference.java</SourceClass></dt>
    <dd>
        <p>Anotaci lze umístit na pole / getter metodu / komponentu recordu a označuje entitu jako 
        [reflektovanou referenci](../../use/schema.md#reference) na jinou entitu. Může odkazovat na jinou modelovou třídu (rozhraní/třída/record),
        která obsahuje vlastnosti pro anotace `@ReferencedEntity` a `@ReferencedEntityGroup` a atributy vztahu.</p>
        <p>Původní reference nemusí být ve schématu ještě definována, ale musí být definována před potvrzením transakce
        nebo uzavřením session (ve fázi warm-up).</p>
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/ReferencedEntity.java</SourceClass></dt>
    <dd>
        Anotaci lze umístit na pole / getter metodu / komponentu recordu a označuje referenci na jinou entitu,
        která představuje referencovanou entitu pro tuto entitu. Modelová třída by měla reprezentovat model třídy entity
        (viz anotace `@Entity`).
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/ReferencedEntityGroup.java</SourceClass></dt>
    <dd>
        Anotaci lze umístit na pole / getter metodu / komponentu recordu a označuje referenci na jinou entitu,
        která představuje skupinu referencovaných entit pro tuto entitu. Modelová třída by měla reprezentovat model třídy entity
        (viz anotace `@Entity`).
    </dd>
</dl>

Metody / pole / komponenty recordu, které nejsou anotovány, jsou při definici schématu ignorovány. Pro lepší představu
si ukážeme příklad návrhu rozhraní produktové entity.

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java" local>

[Příklad modelového rozhraní](/documentation/user/en/use/api/example/declarative-model-example.java)

</SourceCodeTabs>

<Note type="info">

Smlouvu pro definici schématu můžete také použít v [dotazovacím API](./query-data.md) jako očekávaný typ výsledku
a evitaDB automaticky vygeneruje vhodnou proxy třídu, která mapuje obecnou podkladovou datovou strukturu
na smlouvu dle vaší představy. Více informací k tomuto tématu naleznete
v kapitole [Java konektor](../connectors/java.md#vlastní-kontrakty).

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