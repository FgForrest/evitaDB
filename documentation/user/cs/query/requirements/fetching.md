---
title: Načítání
perex: Omezení načítání požadavků pomáhají řídit množství dat vrácených v odpovědi na dotaz. Tato technika se používá ke snížení objemu dat přenášených po síti a ke snížení zátěže serveru. Načítání je podobné spojování tabulek a výběru sloupců v SQL, ale je inspirováno načítáním dat v protokolu GraphQL, kdy se postupně sledují vztahy v datech.
date: '23.7.2023'
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
commit: cef96d8320d36c91c100c5dfc9c45020b5a7ad0d
---
<LS to="e,j,c,r">

Pokud v dotazu není použita žádná požadovaná obsahová podmínka, výsledek bude obsahovat pouze primární klíč entity. I když to může být pro některé dotazy dostačující, obvykle je nutné získat některá data z entity nebo dokonce z dalších entit, které s entitou souvisejí. K tomuto účelu slouží požadavek [`entityFetch`](#načtení-entity) a vnořené *content* požadavky popsané v této sekci:

## Načtení entity

```evitaql-syntax
entityFetch(
    requireConstraint:(
        attributeContent|
        attributeContentAll|
        associatedDataContent|
        associatedDataContentAll|
        dataInLocales|
        dataInLocalesAll|
        hierarchyContent|
        priceContent|
        priceContentAll|
        priceContentRespectingFilter|
        referenceContent|
        referenceContentWithAttributes|
        referenceContentAll|
        referenceContentAllWithAttributes|
        accompanyingPriceContent
    )*
)
```

<dl>
    <dt>requireConstraint:(...)*</dt>
    <dd>
        volitelné jedna nebo více podmínek, které vám umožňují instruovat evitaDB, aby načetla obsah entity;
        může být přítomna jedna nebo všechny z těchto podmínek:
        <ul>
            <li>[attributeContent](#obsah-atributů)</li>
            <li>[attributeContentAll](#všechny-atributy)</li>
            <li>[associatedDataContent](#obsah-souvisejících-dat)</li>
            <li>[associatedDataContentAll](#všechna-související-data)</li>
            <li>[dataInLocales](#data-v-lokalizacích)</li>
            <li>[dataInLocalesAll](#všechna-data-ve-všech-lokalizacích)</li>
            <li>[hierarchyContent](#hierarchický-obsah)</li>
            <li>[priceContent](#obsah-cen)</li>
            <li>[priceContentAll](#obsah-všech-cen)</li>
            <li>[priceContentRespectingFilter](#obsah-cen-respektující-filtr)</li>
            <li>[referenceContent](#referenční-obsah)</li>
            <li>[referenceContentAll](#všechen-referenční-obsah)</li>
            <li>[referenceContentWithAttributes](#referenční-obsah-s-atributy)</li>
            <li>[referenceContentAllWithAttributes](#všechen-referenční-obsah-s-atributy)</li>
            <li>[accompanyingPriceContent](#doprovodný-obsah-ceny)</li>
        </ul>
    </dd>
</dl>

</LS>

Požadavek `entityFetch` (<LS to="e,j,r"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/EntityFetch.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/EntityFetch.cs</SourceClass></LS>)
slouží ke spuštění načtení jednoho nebo více datových kontejnerů entity z disku podle jejího primárního klíče.
Tato operace vyžaduje přístup na disk, pokud již entita není načtena v cache databáze (často načítané entity mají větší šanci zůstat v cache).

<LS to="j,c">
<LS to="j">V Java API</LS><LS to="c">V C# klientu</LS>,
zařazení požadavku `entityFetch` do dotazu mění typ výstupu v odpovědi.
Namísto vrácení <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/structure/EntityReference.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/Data/Structure/EntityReference.cs</SourceClass></LS>
pro každou entitu je vrácen typ <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/SealedEntity.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/Data/ISealedEntity.cs</SourceClass></LS>.
</LS>

<LS to="e,j,c,r">

## Načtení skupiny entit

```evitaql-syntax
entityGroupFetch(
    requireConstraint:(
        attributeContent|
        attributeContentAll|
        associatedDataContent|
        associatedDataContentAll|
        dataInLocales|
        dataInLocalesAll|
        hierarchyContent|
        priceContent|
        priceContentAll|
        priceContentRespectingFilter|
        referenceContent|
        referenceContentWithAttributes|
        referenceContentAll|
        referenceContentAllWithAttributes
    )*
)
```

<dl>
    <dt>requireConstraint:(...)*</dt>
    <dd>
        volitelné jedna nebo více podmínek, které vám umožňují instruovat evitaDB, aby načetla obsah skupinové entity;
        může být přítomna jedna nebo všechny z těchto podmínek:
        <ul>
            <li>[attributeContent](#obsah-atributů)</li>
            <li>[attributeContentAll](#všechny-atributy)</li>
            <li>[associatedDataContent](#obsah-souvisejících-dat)</li>
            <li>[associatedDataContentAll](#všechna-související-data)</li>
            <li>[dataInLocales](#data-v-lokalizacích)</li>
            <li>[dataInLocalesAll](#všechna-data-ve-všech-lokalizacích)</li>
            <li>[hierarchyContent](#hierarchický-obsah)</li>
            <li>[priceContent](#obsah-cen)</li>
            <li>[priceContentAll](#obsah-všech-cen)</li>
            <li>[priceContentRespectingFilter](#obsah-cen-respektující-filtr)</li>
            <li>[referenceContent](#referenční-obsah)</li>
            <li>[referenceContentAll](#všechen-referenční-obsah)</li>
            <li>[referenceContentWithAttributes](#referenční-obsah-s-atributy)</li>
            <li>[referenceContentAllWithAttributes](#všechen-referenční-obsah-s-atributy)</li>
        </ul>
    </dd>
</dl>

Stejné jako [`entityFetch`](#načtení-entity), ale používá se pro načítání entit, které představují referenční skupinu.

</LS>

<LS to="g">

## Obsah entity

Nejjednodušší data, která můžete s entitou načíst, jsou její primární klíč a typ. Tyto informace vrací evitaDB s každým dotazem,
bez ohledu na to, jak bohatý obsah entity požadujete.

<SourceCodeTabs langSpecificTabOnly>

[Získání kódu a názvu značky](/documentation/user/en/query/requirements/examples/fetching/basicEntityContent.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek načtení entity pouze se základními daty
</NoteTitle>

Dotaz vrací následující základní data entity `Brand`:

<MDInclude sourceVariable="data.queryBrand.recordPage">[Výsledek načtení entity pouze se základními daty](/documentation/user/en/query/requirements/examples/fetching/basicEntityContent.graphql.json.md)</MDInclude>

</Note>

Dále můžete s entitou načíst i další pole (což povede k načtení celého těla entity na pozadí):

<SourceCodeTabs langSpecificTabOnly>

[Získání kódu a názvu značky](/documentation/user/en/query/requirements/examples/fetching/entityContent.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek načtení entity s tělem entity
</NoteTitle>

Dotaz vrací následující tělo entity `Brand`:

<MDInclude sourceVariable="data.queryBrand.recordPage">[Výsledek načtení entity s tělem entity](/documentation/user/en/query/requirements/examples/fetching/entityContent.graphql.json.md)</MDInclude>

</Note>

</LS>

## Obsah atributů

<LS to="e,j,c,r">

```evitaql-syntax
attributeContent(
    argument:string+
)
```

<dl>
    <dt>argument:string+</dt>
    <dd>
        jeden nebo více povinných názvů atributů entity nebo reference, které mají být načteny spolu s entitou
    </dd>
</dl>

Požadavek `attributeContent` (<LS to="e,j,r"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/AttributeContent.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/AttributeContent.cs</SourceClass></LS>)
slouží k načtení jednoho nebo více [atributů](../../use/data-model.md#atributy-unikátní-filtrovatelné-řaditelné-lokalizované) entity nebo reference. [Lokalizované atributy](../../use/data-model.md#lokalizované-atributy)
jsou načteny pouze tehdy, pokud je v dotazu *kontext lokalizace*, buď pomocí filtrační podmínky [`entityLocaleEquals`](../filtering/locale.md#entity-locale-equals)
nebo požadavkem [`dataInLocales`](#data-v-lokalizacích).

<Note type="info">

Všechny atributy entity jsou z disku načítány hromadně, takže pokud v požadavku `attributeContent` uvedete pouze některé z nich,
snížíte pouze množství dat přenášených po síti. Není na škodu načíst všechny atributy entity pomocí [`attributeContentAll`](#všechny-atributy).

</Note>

Chcete-li vybrat atributy `code` a lokalizovaný `name` pro entitu `Brand`, použijte následující dotaz:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání kódu a názvu značky](/documentation/user/en/query/requirements/examples/fetching/attributeContent.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek načtení entity s pojmenovanými atributy
</NoteTitle>

Dotaz vrací následující atributy entity `Brand`:

<LS to="e,j,c">

<MDInclude>[Výsledek načtení entity s pojmenovanými atributy](/documentation/user/en/query/requirements/examples/fetching/attributeContent.evitaql.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek načtení entity s pojmenovanými atributy](/documentation/user/en/query/requirements/examples/fetching/attributeContent.rest.json.md)</MDInclude>

</LS>

Jak vidíte, název je v anglické lokalizaci díky filtrační podmínce `entityLocaleEquals` v dotazu.

</Note>

</LS>

<LS to="g">

Chcete-li načíst [atributy](../../use/data-model.md#atributy-unikátní-filtrovatelné-řaditelné-lokalizované) entity nebo reference,
použijte pole `attributes` v rámci objektu entity nebo reference a uveďte požadované názvy atributů jako podpole.
[Lokalizované atributy](../../use/data-model.md#lokalizované-atributy) vyžadují *kontext lokalizace* v dotazu,
buď pomocí filtrační podmínky [`entityLocaleEquals`](../filtering/locale.md#entity-locale-equals) nebo
explicitním zadáním argumentu `locale` pole `attributes`. Pomocí aliasů GraphQL můžete načíst stejné atributy ve více lokalizacích v jednom dotazu.

<Note type="info">

Všechny atributy entity jsou z disku načítány hromadně, takže pokud v poli `attributes` uvedete pouze některé z nich,
snížíte pouze množství dat přenášených po síti. Není na škodu načíst všechny atributy entity.

</Note>

Chcete-li vybrat atributy `code` a lokalizovaný `name` pro entitu `Brand`, použijte následující dotaz:

<SourceCodeTabs langSpecificTabOnly>

[Získání kódu a názvu značky](/documentation/user/en/query/requirements/examples/fetching/attributeContent.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek načtení entity s pojmenovanými atributy
</NoteTitle>

<MDInclude sourceVariable="data.queryBrand.recordPage">[Výsledek načtení entity s pojmenovanými atributy](/documentation/user/en/query/requirements/examples/fetching/attributeContent.graphql.json.md)</MDInclude>

Jak vidíte, název je v anglické lokalizaci díky filtrační podmínce `entityLocaleEquals` v dotazu.

</Note>

Pokud v dotazu chybí filtrační podmínka pro lokalizaci, ale přesto chcete přistupovat k lokalizovaným datům, můžete explicitně zadat argument `locale`
u pole `attributes` tak, jak je uvedeno výše:

<SourceCodeTabs langSpecificTabOnly>

[Získání lokalizovaného názvu značky](/documentation/user/en/query/requirements/examples/fetching/localizedAttributes.graphql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek načtení entity s explicitní lokalizací
</NoteTitle>

Dotaz vrací následující lokalizované atributy entity `Brand`:

<MDInclude sourceVariable="data.queryBrand.recordPage">[Výsledek načtení entity s lokalizovanými atributy](/documentation/user/en/query/requirements/examples/fetching/localizedAttributes.graphql.json.md)</MDInclude>

Pokud by argument `locale` nebyl v dotazu použit, přístup k atributu *name* by vrátil chybu.
V uvedeném příkladu je atribut *name* dostupný v české lokalizaci, i když filtrační podmínka `entityLocaleEquals` nebyla použita.

</Note>

Pro ukázku druhého scénáře, pokud chcete filtrovat značku, která má českou lokalizaci, ale chcete získat hodnoty atributu *name* v češtině, němčině a angličtině, použijte následující dotaz:

<SourceCodeTabs langSpecificTabOnly>

[Získání kódu a názvu značky ve více lokalizacích](/documentation/user/en/query/requirements/examples/fetching/localizedAttributesWithFilter.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek načtení entity s více lokalizacemi
</NoteTitle>

Dotaz vrací následující lokalizované atributy entity `Brand`:

<MDInclude sourceVariable="data.queryBrand.recordPage">[Výsledek načtení entity s lokalizovanými atributy ve více lokalizacích](/documentation/user/en/query/requirements/examples/fetching/localizedAttributesWithFilter.graphql.json.md)</MDInclude>

Jak vidíte, lokalizované atributy jsou dostupné pro českou a anglickou lokalizaci, ale ne pro německou.
Entita je však stále ve výsledku, protože filtrační podmínka vynucuje český kontext lokalizace, který je entitou splněn.

</Note>

</LS>

<LS to="e,j,c,r">

### Všechny atributy

```evitaql-syntax
attributeContentAll()
```

Tato podmínka je zkratkou pro podmínku `attributeContent` se všemi atributy entity nebo reference definovanými ve schématu entity nebo reference. Tato varianta podmínky je alternativou k použití SQL zástupného znaku `*` v klauzuli `SELECT`.

Chcete-li vybrat všechny nelokalizované atributy entity `Brand`, použijte následující dotaz:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání kódu a názvu značky](/documentation/user/en/query/requirements/examples/fetching/attributeContentAll.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek načtení entity se všemi atributy
</NoteTitle>

Dotaz vrací následující atributy entity `Brand`:

<LS to="e,j,c">

<MDInclude>[Výsledek načtení entity se všemi atributy](/documentation/user/en/query/requirements/examples/fetching/attributeContentAll.evitaql.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek načtení entity se všemi atributy](/documentation/user/en/query/requirements/examples/fetching/attributeContentAll.rest.json.md)</MDInclude>

</LS>

Všechny lokalizované atributy chybí, protože v dotazu není přítomen žádný kontext lokalizace.

</Note>

</LS>

## Obsah souvisejících dat

<LS to="e,j,c,r">

```evitaql-syntax
associatedDataContent(
    argument:string+
)
```

<dl>
    <dt>argument:string+</dt>
    <dd>
        jeden nebo více povinných názvů souvisejících dat entity, která mají být načtena spolu s entitou
    </dd>
</dl>

Požadavek `associatedDataContent` (<LS to="e,j,r"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/AssociatedDataContent.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/AssociatedDataContent.cs</SourceClass></LS>)
slouží k načtení jednoho nebo více [souvisejících dat](../../use/data-model.md#přidružená-data) entity.
[Lokalizovaná související data](../../use/data-model.md#lokalizovaná-přidružená-data) jsou načtena pouze tehdy,
pokud je v dotazu *kontext lokalizace*, buď pomocí filtrační podmínky [`entityLocaleEquals`](../filtering/locale.md#entity-locale-equals)
nebo požadavkem [`dataInLocales`](#data-v-lokalizacích).

Chcete-li vybrat *allActiveUrls* a lokalizovaná *localization* související data entity `Brand`, použijte následující dotaz:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání kódu a názvu značky](/documentation/user/en/query/requirements/examples/fetching/associatedDataContent.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek načtení entity s pojmenovanými souvisejícími daty
</NoteTitle>

Dotaz vrací následující související data entity `Brand`:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Výsledek načtení entity s pojmenovanými souvisejícími daty](/documentation/user/en/query/requirements/examples/fetching/associatedDataContent.evitaql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek načtení entity s pojmenovanými souvisejícími daty](/documentation/user/en/query/requirements/examples/fetching/associatedDataContent.rest.json.md)</MDInclude>

</LS>

Jak vidíte, položka *localization* obsahuje texty v anglické lokalizaci díky filtrační podmínce `entityLocaleEquals` v dotazu.
*allActiveUrls* je nelokalizovaná položka souvisejících dat, která obsahuje aktivní URL adresy pro konkrétní značku v různých jazycích,
což lze použít například pro generování jazykového výběru pro tento záznam značky.

</Note>

</LS>

<LS to="g">

Chcete-li načíst [související data](../../use/data-model.md#přidružená-data) entity,
použijte pole `associatedData` v rámci objektu entity a uveďte požadované názvy souvisejících dat jako podpole.
[Lokalizovaná související data](../../use/data-model.md#lokalizovaná-přidružená-data) vyžadují *kontext lokalizace* v dotazu,
buď pomocí filtrační podmínky [`entityLocaleEquals`](../filtering/locale.md#entity-locale-equals) nebo
explicitním zadáním argumentu `locale` pole `associatedData`. Pomocí aliasů GraphQL můžete načíst stejná související data ve více lokalizacích v jednom dotazu.

Chcete-li načíst *allActiveUrls* a lokalizovaná *localization* související data entity `Brand`, použijte následující dotaz:

<SourceCodeTabs langSpecificTabOnly>

[Získání kódu a názvu značky](/documentation/user/en/query/requirements/examples/fetching/associatedDataContent.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek načtení entity s pojmenovanými souvisejícími daty
</NoteTitle>

Dotaz vrací následující související data entity `Brand`:

<MDInclude sourceVariable="data.queryBrand.recordPage">[Výsledek načtení entity s pojmenovanými souvisejícími daty](/documentation/user/en/query/requirements/examples/fetching/associatedDataContent.graphql.json.md)</MDInclude>

</Note>

Pokud v dotazu chybí filtrační podmínka pro lokalizaci, ale přesto chcete přistupovat k lokalizovaným datům, můžete explicitně zadat argument `locale`
u pole `associatedData` tak, jak je uvedeno výše:

<SourceCodeTabs langSpecificTabOnly>

[Získání lokalizovaného názvu značky](/documentation/user/en/query/requirements/examples/fetching/localizedAssociatedData.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek načtení entity s explicitní lokalizací
</NoteTitle>

Dotaz vrací následující lokalizovaná související data entity `Brand`:

<MDInclude sourceVariable="data.queryBrand.recordPage">[Výsledek načtení entity s lokalizovanými atributy](/documentation/user/en/query/requirements/examples/fetching/localizedAssociatedData.graphql.json.md)</MDInclude>

Pokud by argument `locale` nebyl v dotazu použit, přístup k souvisejícím datům *localization* by vrátil chybu.
V uvedeném příkladu jsou související data *localization* dostupná v české lokalizaci, i když filtrační podmínka `entityLocaleEquals` nebyla použita.

</Note>

Pro ukázku druhého scénáře, pokud chcete filtrovat značku, která má českou lokalizaci, ale chcete získat hodnoty souvisejících dat *localization* v češtině, němčině a angličtině, použijte následující dotaz:

<SourceCodeTabs langSpecificTabOnly>

[Získání kódu a názvu značky ve více lokalizacích](/documentation/user/en/query/requirements/examples/fetching/localizedAssociatedDataWithFilter.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek načtení entity s více lokalizacemi
</NoteTitle>

Dotaz vrací následující lokalizovaná související data entity `Brand`:

<MDInclude sourceVariable="data.queryBrand.recordPage">[Výsledek načtení entity s lokalizovanými atributy ve více lokalizacích](/documentation/user/en/query/requirements/examples/fetching/localizedAssociatedDataWithFilter.graphql.json.md)</MDInclude>

Jak vidíte, lokalizovaná související data jsou dostupná pro českou a anglickou lokalizaci, ale ne pro německou.
Entita je však stále ve výsledku, protože filtrační podmínka vynucuje český kontext lokalizace, který je entitou splněn.

</Note>

</LS>

<LS to="e,j,c,r">

### Všechna související data

```evitaql-syntax
associatedDataContentAll()
```

Tato podmínka je zkratkou pro podmínku `associatedDataContent` se všemi souvisejícími daty entity definovanými ve schématu entity. Tato varianta podmínky je alternativou k použití SQL zástupného znaku `*` v klauzuli `SELECT`.

<Note type="warning">

Protože se očekává, že související data budou obsahovat velké množství nestrukturovaných dat, každá z těchto dat je uložena jako samostatný záznam. Vždy byste měli načítat pouze ta související data, která skutečně potřebujete, protože načtení všech zpomalí zpracování požadavku. Požadavek [`associatedDataContentAll`](#všechna-související-data) by měl být používán pouze pro ladicí nebo průzkumné účely a neměl by být součástí produkčního kódu.

</Note>

Chcete-li vybrat všechna nelokalizovaná související data entity `Brand`, použijte následující dotaz:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání kódu a názvu značky](/documentation/user/en/query/requirements/examples/fetching/associatedDataContentAll.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek načtení entity se všemi souvisejícími daty
</NoteTitle>

Dotaz vrací následující související data entity `Brand`:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Výsledek načtení entity se všemi souvisejícími daty](/documentation/user/en/query/requirements/examples/fetching/associatedDataContentAll.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryBrand.recordPage">[Výsledek načtení entity se všemi souvisejícími daty](/documentation/user/en/query/requirements/examples/fetching/associatedDataContentAll.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek načtení entity se všemi souvisejícími daty](/documentation/user/en/query/requirements/examples/fetching/associatedDataContentAll.rest.json.md)</MDInclude>

</LS>

Všechna lokalizovaná související data chybí, protože v dotazu není přítomen žádný kontext lokalizace.

</Note>

</LS>

<LS to="e,j,c,r">

## Data v lokalizacích

```evitaql-syntax
dataInLocales(
    argument:string+
)
```

<dl>
    <dt>argument:string+</dt>
    <dd>
        povinné určení jedné nebo více [lokalizací](https://en.wikipedia.org/wiki/IETF_language_tag), ve kterých budou načteny lokalizované atributy entity nebo reference a související data entity; příklady
        platných jazykových tagů jsou: `en-US` nebo `en-GB`, `cs` nebo `cs-CZ`, `de` nebo `de-AT`, `de-CH`, `fr` nebo `fr-CA` atd.
    </dd>
</dl>

Požadavek `dataInLocales` (<LS to="e,j,r"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/DataInLocales.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/DataInLocales.cs</SourceClass></LS>)
se používá ve dvou scénářích:

1. v části filtru dotazu není *kontext lokalizace*, protože nechcete vyloučit entity bez požadované lokalizace z výsledku, ale chcete načíst lokalizovaná data v jednom nebo více jazycích, pokud jsou pro entitu nebo referenci k dispozici
2. v části filtru dotazu je *kontext lokalizace*, ale chcete načíst lokalizovaná data v jiných nebo dodatečných jazycích, než je ten, který je určen v *kontextu lokalizace*

Pokud v dotazu chybí filtrační podmínka pro lokalizaci, ale přesto chcete přistupovat k lokalizovaným datům, můžete použít následující dotaz:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání lokalizovaného názvu značky](/documentation/user/en/query/requirements/examples/fetching/dataInLocales.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek načtení entity s explicitní lokalizací
</NoteTitle>

Dotaz vrací následující lokalizované atributy entity `Brand`:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Výsledek načtení entity s lokalizovanými atributy](/documentation/user/en/query/requirements/examples/fetching/dataInLocales.evitaql.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek načtení entity s lokalizovanými atributy](/documentation/user/en/query/requirements/examples/fetching/dataInLocales.rest.json.md)</MDInclude>

</LS>

Pokud by požadavek `dataInLocales` nebyl v dotazu použit, přístup k atributu *name* by vyvolal výjimku.
V uvedeném příkladu je atribut *name* dostupný v české lokalizaci, i když filtrační podmínka `entityLocaleEquals` nebyla použita.

</Note>

Pro ukázku druhého scénáře, pokud chcete filtrovat značku, která má českou lokalizaci, ale chcete získat hodnoty atributu *name* v češtině a angličtině, použijte následující dotaz:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání kódu a názvu značky ve více lokalizacích](/documentation/user/en/query/requirements/examples/fetching/dataInLocalesWithFilter.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek načtení entity s více lokalizacemi
</NoteTitle>

Dotaz vrací následující lokalizované atributy entity `Brand`:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Výsledek načtení entity s lokalizovanými atributy ve více lokalizacích](/documentation/user/en/query/requirements/examples/fetching/dataInLocalesWithFilter.evitaql.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek načtení entity s lokalizovanými atributy ve více lokalizacích](/documentation/user/en/query/requirements/examples/fetching/dataInLocalesWithFilter.rest.json.md)</MDInclude>

</LS>

Jak vidíte, lokalizované atributy jsou dostupné jak pro českou, tak pro anglickou lokalizaci.
Entita je však stále ve výsledku, protože filtrační podmínka vynucuje český kontext lokalizace, který je entitou splněn.

</Note>

</LS>

<LS to="e,j,c,r">

### Všechna data ve všech lokalizacích

```evitaql-syntax
dataInLocalesAll()
```

Požadavek `dataInLocalesAll` vám umožňuje načíst atributy a související data ve všech dostupných lokalizacích. To je obvykle užitečné ve scénářích, kdy publikujete data z primárního datového zdroje a potřebujete vytvořit/aktualizovat všechna data najednou. Pokud k datům přistupujete jako klientská aplikace, pravděpodobně budete vždy chtít načíst data v konkrétní lokalizaci, což znamená, že použijete požadavek `dataInLocales` s jednou lokalizací nebo filtrační podmínku `entityLocaleEquals`.

Chcete-li načíst entitu ve všech dostupných lokalizacích, použijte následující dotaz:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání lokalizovaného názvu značky](/documentation/user/en/query/requirements/examples/fetching/dataInLocalesAll.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek načtení entity ve všech dostupných lokalizacích
</NoteTitle>

Dotaz vrací následující lokalizované atributy entity `Brand`:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Výsledek načtení entity ve všech dostupných lokalizacích](/documentation/user/en/query/requirements/examples/fetching/dataInLocalesAll.evitaql.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryBrand.recordPage">[Výsledek načtení entity ve všech dostupných lokalizacích](/documentation/user/en/query/requirements/examples/fetching/dataInLocalesAll.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek načtení entity ve všech dostupných lokalizacích](/documentation/user/en/query/requirements/examples/fetching/dataInLocalesAll.rest.json.md)</MDInclude>

</LS>

Jak vidíte, entita je vrácena s českou a anglickou lokalizací, pro které jsou dostupné lokalizované atributy nebo související data.

</Note>

</LS>

## Hierarchický obsah

<LS to="e,j,c,r">

```evitaql-syntax
hierarchyContent(
    requireConstraint:(entityFetch|stopAt)*
)
```

<dl>
    <dt>requireConstraint:(entityFetch|stopAt)*</dt>
    <dd>
        volitelně jeden nebo více omezení, která vám umožní definovat úplnost hierarchických entit a
        rozsah procházeného stromu hierarchie;
        může být přítomno libovolné nebo obě omezení:
        <ul>
            <li>[entityFetch](fetching.md#načtení-entity)</li>
            <li>[stopAt](hierarchy.md#stop-at)</li>
        </ul>
    </dd>
</dl>

Požadavek `hierarchyContent` (<LS to="e,j,r"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/HierarchyContent.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/HierarchyContent.cs</SourceClass></LS>)
vám umožňuje přistupovat k informacím o hierarchickém umístění entity.

Pokud nejsou zadána žádná další omezení, entita bude obsahovat celý řetězec primárních klíčů rodičů až ke kořeni
stromu hierarchie. Velikost tohoto řetězce můžete omezit pomocí omezení `stopAt` – například pokud vás zajímá pouze
přímý rodič každé vrácené entity, můžete použít omezení `stopAt(distance(1))`. Výsledek je podobný použití omezení
[`parents`](hierarchy.md#parents), ale je omezený tím, že neposkytuje informace o statistikách a možnost vypsat sourozence rodičovských entit. Na druhou stranu je jednodušší na použití – protože hierarchické umístění je přímo dostupné v načteném objektu entity.

Pokud zadáte vnořené omezení [`entityFetch`](#načtení-entity), hierarchická informace bude obsahovat těla rodičovských entit v požadované šířce. [`attributeContent`](#obsah-atributů) uvnitř `entityFetch` vám umožní přistupovat k atributům rodičovských entit atd.

Pro načtení entity se základními hierarchickými informacemi použijte následující dotaz:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání lokalizovaného názvu značky](/documentation/user/en/query/requirements/examples/fetching/hierarchyContent.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek entity načtené s jejím hierarchickým umístěním
</NoteTitle>

Dotaz vrací následující hierarchii entity `Category`:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Výsledek načtení entity s hierarchickým umístěním](/documentation/user/en/query/requirements/examples/fetching/hierarchyContent.evitaql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek načtení entity s hierarchickým umístěním](/documentation/user/en/query/requirements/examples/fetching/hierarchyContent.rest.json.md)</MDInclude>

</LS>

Entita `Category` je vrácena s hierarchickými informacemi až ke kořeni stromu hierarchie.

</Note>

Pro ukázku složitějšího a užitečnějšího příkladu načtěme produkt s referencí na kategorii a pro kategorii
načtěme její kompletní hierarchické umístění až ke kořeni stromu hierarchie s atributy `code` a `name` těchto kategorií. Dotaz vypadá takto:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání lokalizovaného názvu značky](/documentation/user/en/query/requirements/examples/fetching/hierarchyContentViaReference.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek entity načtené s jejím hierarchickým umístěním
</NoteTitle>

Dotaz vrací následující produkt s referencí na kompletní řetězec hierarchie entity `Category`:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Výsledek načtení entity s hierarchickým umístěním](/documentation/user/en/query/requirements/examples/fetching/hierarchyContentViaReference.evitaql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek načtení entity s hierarchickým umístěním](/documentation/user/en/query/requirements/examples/fetching/hierarchyContentViaReference.rest.json.md)</MDInclude>

</LS>

Tento poměrně složitý příklad využívá požadavek [`referenceContent`](#referenční-obsah), který je popsán v následující kapitole.

</Note>

</LS>

<LS to="g">

Pro přístup k informacím o hierarchickém umístění entity použijte pole `parentPrimaryKey` nebo `parents` (nebo obě).

Pole `parentPrimaryKey` vrací pouze primární klíč přímého rodiče entity. Pokud entita nemá rodiče
(tj. je kořenovou entitou), pole vrací `null`.

Pole `parents` vám umožňuje přistupovat k celému řetězci rodičovských entit až ke kořeni stromu hierarchie. Uvnitř
můžete použít standardní pole entity kromě pole `parents` pro načtené rodičovské entity. Pokud nejsou zadány žádné další argumenty,
seznam bude obsahovat celý řetězec rodičovských entit až ke kořeni stromu hierarchie. Velikost řetězce můžete omezit pomocí argumentu `stopAt` – například pokud vás zajímá pouze přímý rodič každé vrácené entity,
můžete použít argument `stopAt: { distance: 1 }`. Argument přijímá speciální požadavky pro určení,
na kterém uzlu zastavit procházení hierarchie.
Výsledek je podobný použití požadavku [`parents`](hierarchy.md#parents), ale je omezený tím, že neposkytuje
informace o statistikách a možnost vypsat sourozence rodičovských entit. Na druhou stranu je jednodušší na použití – protože hierarchické umístění je přímo dostupné v načteném objektu entity.

Pro načtení entity se základními hierarchickými informacemi použijte následující dotaz:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání lokalizovaného názvu značky](/documentation/user/en/query/requirements/examples/fetching/hierarchyContent.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek entity načtené s jejím hierarchickým umístěním
</NoteTitle>

Dotaz vrací následující hierarchii entity `Category`:

<MDInclude sourceVariable="data.queryCategory.recordPage">[Výsledek načtení entity s hierarchickým umístěním](/documentation/user/en/query/requirements/examples/fetching/hierarchyContent.graphql.json.md)</MDInclude>

Entita `Category` je vrácena s hierarchickými informacemi až ke kořeni stromu hierarchie.

</Note>

Pro ukázku složitějšího a užitečnějšího příkladu načtěme produkt s referencí na kategorii a pro kategorii
načtěme její kompletní hierarchické umístění až ke kořeni stromu hierarchie s atributy `code` a `name` těchto kategorií. Dotaz vypadá takto:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání lokalizovaného názvu značky](/documentation/user/en/query/requirements/examples/fetching/hierarchyContentViaReference.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek entity načtené s jejím hierarchickým umístěním
</NoteTitle>

Dotaz vrací následující produkt s referencí na kompletní řetězec hierarchie entity `Category`:

<MDInclude sourceVariable="data.queryProduct.recordPage">[Výsledek načtení entity s hierarchickým umístěním](/documentation/user/en/query/requirements/examples/fetching/hierarchyContentViaReference.graphql.json.md)</MDInclude>

Tento poměrně složitý příklad využívá [referenční pole kategorie](#referenční-obsah), které je popsáno v následující kapitole.

</Note>

</LS>

## Obsah cen

<LS to="e,j,c,r">

```evitaql-syntax
priceContent(
    argument:enum(NONE|RESPECTING_FILTER|ALL),
    argument:string*
)
```

<dl>
    <dt>argument:enum(NONE|RESPECTING_FILTER|ALL)</dt>
    <dd>
        volitelný argument typu <LS to="e,j,r"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/PriceContentMode.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/PriceContentMode.cs</SourceClass></LS>
        enum, který vám umožní určit, zda se mají načíst všechny, vybrané nebo žádné cenové záznamy entity:

        - **NONE**: žádné ceny nebudou pro entitu načteny (i když filtr obsahuje cenové omezení)
        - **RESPECTING_FILTER**: budou načteny pouze ceny v cenových seznamech vybraných filtrem
        - **ALL**: budou načteny všechny ceny entity (bez ohledu na cenové omezení ve filtru)

    </dd>
    <dt>argument:string*</dt>
    <dd>
        volitelně jeden nebo více řetězcových argumentů představujících názvy cenových seznamů, které se přidají k seznamu cenových seznamů předaných v
        cenovém omezení filtru, které dohromady tvoří množinu cenových seznamů, pro které se mají ceny entity načíst
    </dd>
</dl>

Požadavek `priceContent` (<LS to="e,j,r"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/PriceContent.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/PriceContent.cs</SourceClass></LS>)
vám umožňuje přistupovat k informacím o cenách entity.

Pokud je použito režimu `RESPECTING_FILTER`, požadavek `priceContent` načte pouze ceny vybrané omezením
[`priceInPriceLists`](../filtering/price.md#cena-v-cenících). Pokud je zadán enum `NONE`, ceny nejsou vráceny vůbec, pokud je zadán enum `ALL`, jsou vráceny všechny ceny entity bez ohledu na omezení
`priceInPriceLists` ve filtru (omezení stále určuje, zda bude entita vrácena).

Můžete také přidat další cenové seznamy k seznamu cenových seznamů předaných v omezení `priceInPriceLists` tím, že
zadáte názvy cenových seznamů jako řetězcové argumenty požadavku `priceContent`. To je užitečné, pokud chcete
načíst neindexované ceny entity, které nemohou (a nemají) být použity k filtrování entit, ale přesto je chcete načíst pro zobrazení uživateli v UI.

<LS to="r">

U entit, které mají vnitřní zpracování záznamů `LOWEST_PRICE` nebo `SUM`, je vrácena vlastnost `multiplePricesForSaleAvailable`,
která označuje, zda je k dispozici více _unikátních_ cen k prodeji (seskupených podle `innerRecordId`).
Je důležité poznamenat, že nevrací pouze počet všech cen k prodeji.
Místo toho používá omezení [`priceType`](../requirements/price.md#typ-ceny) k určení jedinečnosti každé
cenové hodnoty. To znamená, že i když jsou například 3 ceny k prodeji, ale všechny mají stejnou hodnotu, tato vlastnost
vrátí `false`. To je užitečné zejména pro
UI, aby bylo možné určit, zda zobrazit cenové rozpětí nebo jen jednu cenu, aniž by bylo nutné načítat všechny ceny k prodeji.

</LS>

Pro získání entity s cenami, podle kterých filtrujete, použijte následující dotaz:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání entity s cenami a referenční cenou](/documentation/user/en/query/requirements/examples/fetching/priceContent.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek entity načtené s vybranými cenami
</NoteTitle>

Dotaz vrací následující seznam cen entity `Product`:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Výsledek entity načtené s vybranými cenami](/documentation/user/en/query/requirements/examples/fetching/priceContent.evitaql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek entity načtené s vybranými cenami](/documentation/user/en/query/requirements/examples/fetching/priceContent.rest.json.md)</MDInclude>

</LS>

Jak vidíte, jsou vráceny ceny pro filtrované cenové seznamy *employee-basic-price* a *basic*. Tento dotaz je
ekvivalentní použití aliasu [`priceContentRespectingFilter`](#obsah-cen-respektující-filtr).

</Note>

### Obsah cen respektující filtr

```evitaql-syntax
priceContent(
    argument:string*
)
```

<dl>
    <dt>argument:string*</dt>
    <dd>
        volitelně jeden nebo více řetězcových argumentů představujících názvy cenových seznamů, které se přidají k seznamu cenových seznamů předaných v
        cenovém omezení filtru, které dohromady tvoří množinu cenových seznamů, pro které se mají ceny entity načíst
    </dd>
</dl>

Požadavek `priceContentRespectingFilter` (<LS to="e,j,r"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/PriceContent.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/PriceContent.cs</SourceClass></LS>)
vám umožňuje přistupovat k informacím o cenách entity. Načítá pouze ceny vybrané omezením
[`priceInPriceLists`](../filtering/price.md#cena-v-cenících).

Můžete také přidat další cenové seznamy k seznamu cenových seznamů předaných v omezení `priceInPriceLists` tím, že
zadáte názvy cenových seznamů jako řetězcové argumenty požadavku `priceContent`. To je užitečné, pokud chcete
načíst neindexované ceny entity, které nemohou (a nemají) být použity k filtrování entit, ale přesto je chcete načíst pro zobrazení uživateli v UI.

Tento požadavek je pouze variantou obecného požadavku [`priceContent`](#obsah-cen).

Pro získání entity s cenami, podle kterých filtrujete, a *referenční* cenou navíc, použijte následující dotaz:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání entity s filtrovanými cenami a referenční cenou](/documentation/user/en/query/requirements/examples/fetching/priceContentRespectingFilter.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek entity načtené s vybranými cenami a referenční cenou
</NoteTitle>

Dotaz vrací následující seznam cen entity `Product`:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Výsledek entity načtené s vybranými cenami a referenční cenou](/documentation/user/en/query/requirements/examples/fetching/priceContentRespectingFilter.evitaql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek entity načtené s vybranými cenami a referenční cenou](/documentation/user/en/query/requirements/examples/fetching/priceContentRespectingFilter.rest.json.md)</MDInclude>

</LS>

Jak vidíte, jsou vráceny ceny pro filtrované cenové seznamy *employee-basic-price* a *basic*, stejně jako
cena v *reference* cenových seznamech požadovaných požadavkem `priceContent`.

</Note>

### Obsah všech cen

```evitaql-syntax
priceContentAll()
```

Požadavek `priceContentAll` (<LS to="e,j,r"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/PriceContent.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/PriceContent.cs</SourceClass></LS>)
vám umožňuje přistupovat ke všem informacím o cenách entity bez ohledu na zadaná filtrační omezení v dotazu.

Tento požadavek je pouze variantou obecného požadavku [`priceContent`](#obsah-cen).

Pro získání entity se všemi jejími cenami použijte následující dotaz:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání entity s cenami a referenční cenou](/documentation/user/en/query/requirements/examples/fetching/priceContentAll.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek entity načtené se všemi jejími cenami
</NoteTitle>

Dotaz vrací následující seznam cen entity `Product`:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Výsledek entity načtené se všemi jejími cenami](/documentation/user/en/query/requirements/examples/fetching/priceContentAll.evitaql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek entity načtené se všemi jejími cenami](/documentation/user/en/query/requirements/examples/fetching/priceContentAll.rest.json.md)</MDInclude>

</LS>

Jak vidíte, jsou vráceny všechny ceny entity ve všech dostupných měnách – nejen filtrované cenové seznamy
*employee-basic-price* a *basic*. Díky `priceContentAll` máte přehled o všech cenách entity.

</Note>

</LS>
<LS to="g">

Pro načtení informací o cenách entity je k dispozici několik různých polí v rámci entity: `priceForSale`,
`allPricesForSale`, `multiplePricesForSaleAvailable`, `price` a `prices`.
Každé má jiný účel a vrací různé informace.

Objekt ceny vrací různá data, která může server naformátovat pro zobrazení uživateli. Konkrétně
skutečné číselné hodnoty ceny v objektu ceny mohou být vráceny naformátované podle zadané lokalizace a mohou dokonce
obsahovat symbol měny. Toto je řízeno argumenty `formatted` a `withCurrency` u příslušných polí objektu ceny.
Lokalizace je buď odvozena z kontextu dotazu
(buď z lokalizovaného unikátního atributu ve filtru nebo z omezení `entityLocaleEquals` ve filtru), nebo může být
zadána přímo u nadřazeného pole ceny argumentem `locale`.

### Ceny k prodeji

Pole `priceForSale` vrací jeden objekt ceny představující cenu k prodeji entity.
Ve výchozím nastavení je tato cena [vypočítána na základě vstupních filtračních omezení](../filtering/price.md), konkrétně:
`priceInPriceLists`, `priceInCurrency` a `priceValidIn`. Toto je očekávaný nejběžnější případ použití, protože
také filtruje vrácené entity podle těchto podmínek.

<SourceCodeTabs langSpecificTabOnly>

[Získání entity s cenou k prodeji](/documentation/user/en/query/requirements/examples/fetching/priceForSaleField.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek entity načtené s cenou k prodeji na základě filtračních omezení
</NoteTitle>

Dotaz vrací následující cenu k prodeji entity `Product`:

<MDInclude sourceVariable="data.queryProduct.recordPage">[Výsledek entity načtené s cenou k prodeji](/documentation/user/en/query/requirements/examples/fetching/priceForSaleField.graphql.json.md)</MDInclude>

Jak vidíte, je vrácena cena k prodeji odpovídající filtračním omezením.

</Note>

Alternativně, pokud nechcete filtrovat entity podle cenových filtračních omezení, ale přesto chcete vypočítat a načíst
konkrétní cenu k prodeji, můžete určit, kterou cenu k prodeji chcete vypočítat, pomocí argumentů `priceList`, `currency` a
`validIn`/`validNow` přímo u pole `priceForSale`. Tyto dva přístupy lze i kombinovat,
v takovém případě argumenty u pole `priceForSale` jednoduše přepíší odpovídající cenová omezení použitá ve filtru.
Teoreticky tak můžete filtrovat entity podle jiných cenových podmínek, než které použijete pro výpočet vrácené ceny k prodeji.

<SourceCodeTabs langSpecificTabOnly>

[Získání entity s cenou k prodeji na základě vlastních argumentů](/documentation/user/en/query/requirements/examples/fetching/priceForSaleFieldWithArguments.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek entity načtené s cenou k prodeji na základě vlastních argumentů
</NoteTitle>

Dotaz vrací následující cenu k prodeji entity `Product`:

<MDInclude sourceVariable="data.queryProduct.recordPage">[Výsledek entity načtené s cenou k prodeji](/documentation/user/en/query/requirements/examples/fetching/priceForSaleFieldWithArguments.graphql.json.md)</MDInclude>

Jak vidíte, je vrácena cena k prodeji odpovídající vlastním argumentům.

</Note>

Podobně můžete použít `allPricesForSale`, což je téměř totéž jako `priceForSale`, ale vrací všechny ceny k prodeji
entity seskupené podle `innerRecordId`. To má smysl zejména pro hlavní produkty s variantami
(tj. vnitřní zpracování záznamů `LOWEST_PRICE`), kde má hlavní produkt ceny pro všechny své varianty a můžete
chtít znát (a zobrazit) ceny k prodeji pro každou variantu (nebo nějaké rozpětí). Pro vnitřní zpracování záznamů `NONE`
to vždy vrátí maximálně jednu skutečnou cenu k prodeji. Pro vnitřní zpracování záznamů `SUM` to vrátí ceny k prodeji
pro každý `innerRecordId` stejně jako pro `FIRST_OCCURRENCE`, ale použití je omezené.

Vrácený seznam cen je seřazen podle hodnoty ceny od nejnižší po nejvyšší v závislosti na použití omezení [`priceType`](../requirements/price.md#typ-ceny).

Existuje také jednodušší pole `multiplePricesForSaleAvailable`, které vrací boolean určující, zda je
k dispozici více _unikátních_ cen k prodeji. Je důležité poznamenat, že nevrací pouze počet `allPricesForSale`.
Místo toho používá omezení [`priceType`](../requirements/price.md#typ-ceny) k určení jedinečnosti každé
cenové hodnoty. To znamená, že i když jsou například 3 ceny k prodeji, ale všechny mají stejnou hodnotu, toto pole
vrátí `false` (na rozdíl od `allPricesForSale`, které by vrátilo všechny ceny). To je užitečné zejména pro
UI, aby bylo možné určit, zda zobrazit cenové rozpětí nebo jen jednu cenu, aniž by bylo nutné načítat všechny ceny k prodeji.

<SourceCodeTabs langSpecificTabOnly>

[Získání entity se všemi cenami k prodeji](/documentation/user/en/query/requirements/examples/fetching/allPricesForSaleField.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek entity načtené se všemi jejími cenami k prodeji na základě filtračních omezení
</NoteTitle>

Dotaz vrací následující ceny k prodeji entity `Product`:

<MDInclude sourceVariable="data.queryProduct.recordPage">[Výsledek entity načtené se všemi jejími cenami k prodeji](/documentation/user/en/query/requirements/examples/fetching/allPricesForSaleField.graphql.json.md)</MDInclude>

Jak vidíte, je vrácena cena k prodeji odpovídající filtračním omezením, stejně jako všechny ostatní ceny k prodeji a
příznak označující, že je k dispozici více cen k prodeji.

</Note>

### Doprovodné ceny

Někdy můžete potřebovat nejen konkrétní [cenu k prodeji](#ceny-k-prodeji), ale také její doprovodné ceny, např.
referenční cenu (používanou pouze pro porovnání, protože je obvykle vyšší než cena k prodeji).
Tento výpočet se stává poměrně složitým, když je třeba zpracovat ceny s různým vnitřním zpracováním záznamů
([LOWEST_PRICE, SUM](https://evitadb.io/documentation/query/filtering/price#price-for-sale-selection-in-a-nutshell)).
V takových případech musí doprovodné ceny správně odrážet ceny k prodeji pro každý vnitřní záznam. Ceny lze
vypočítat jak z indexovaných, tak neindexovaných cenových seznamů. Řídí se stejnými [pravidly výpočtu](/documentation/user/en/deep-dive/price-for-sale-calculation.md) jako cena k prodeji,
ale počítají se pouze z entit, které se účastní výpočtu ceny k prodeji.

Tyto ceny můžete snadno vypočítat pomocí pole `accompanyingPrice` uvnitř polí `priceForSale` nebo `allPricesForSale`.
Takto požadovaná doprovodná cena bude vždy odkazovat na nadřazenou cenu k prodeji (i vlastní). Jediným
možným argumentem je `priceLists`, který definuje, pro které cenové seznamy má být doprovodná cena vypočítána
(pořadí cenových seznamů určuje prioritu stejně jako u polí `priceForSale` a `allPricesForSale`).
Ostatní parametry budou zděděny z nadřazeného požadavku na cenu k prodeji.

Můžete vypočítat více doprovodných cen najednou, ale musíte zadat název každé doprovodné ceny, abyste je od sebe odlišili.

Následující dotaz požaduje výpočet ceny k prodeji i referenční ceny pro vypočtenou cenu k prodeji:

Ukažme si tento princip na složitějším příkladu:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad dotazu na výpočet různých doprovodných cen](/documentation/user/en/query/requirements/examples/fetching/accompanying-price-content.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledky výpočtu různých doprovodných cen

</NoteTitle>

Pro náš příklad jsme vybrali produkt s vnitřním zpracováním záznamů `LOWEST_PRICE` pro výpočet ceny k prodeji.
Zároveň jsme požadovali tři doprovodné ceny.

1. první nemá název ani zadané cenové seznamy, bude vypočítána jako `default` doprovodná cena podle pořadí cenových seznamů definovaného v požadavku `defaultAccompanyingPriceLists`;
2. druhá má název `custom` a nemá zadané cenové seznamy, bude vypočítána jako `custom` doprovodná cena podle pořadí cenových seznamů definovaného v požadavku `defaultAccompanyingPriceLists`;
3. třetí má název `special` a používá cenové seznamy `employee-basic-price` a `b2b-basic-price` pro výpočet ceny;

Výsledky dotazu, který tyto ceny vypočítává, jsou uvedeny níže:

<MDInclude sourceVariable="data.queryProduct.recordPage">[Výsledky výpočtu různých doprovodných cen](/documentation/user/en/query/requirements/examples/fetching/accompanying-price-content.graphql.json.md)</MDInclude>

Protože byla použita strategie `LOWEST_PRICE`, cena k prodeji je vypočítána zvlášť pro každý vnitřní záznam produktu
a poté je vybrána nejnižší cena. Následně jsou vypočítány doprovodné ceny, ale pouze z cen, které sdílejí
stejný vnitřní záznam jako cena k prodeji.

Pokud by byla použita strategie `SUM`, doprovodné ceny by byly vypočítány pouze pro ceny vztahující se k vnitřním záznamům,
které jsou součástí výpočtu ceny k prodeji. Proto i kdyby existovaly ceny pro cenové seznamy použité v
`default` nebo `special` doprovodných cenách, nebyly by součástí součtové ceny `default` nebo `special`, protože jejich
prodejní protějšek neexistuje.

</Note>

Následující dotaz je téměř totožný s předchozím, pouze navíc vypočítává všechny ceny k prodeji a jejich referenční ceny pro
vnitřní záznamy:

<SourceCodeTabs langSpecificTabOnly>

[Získání entity se všemi cenami k prodeji a referenčními cenami](/documentation/user/en/query/requirements/examples/fetching/allPricesForSaleFieldWithReferencePrices.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek entity načtené s cenami k prodeji a referenčními cenami
</NoteTitle>

Dotaz vrací následující všechny ceny k prodeji a referenční ceny entity `Product`:

<MDInclude sourceVariable="data.queryProduct.recordPage">[Výsledek entity načtené s cenami k prodeji a referenčními cenami](/documentation/user/en/query/requirements/examples/fetching/allPricesForSaleFieldWithReferencePrices.graphql.json.md)</MDInclude>

Jak vidíte, jsou vráceny ceny k prodeji i vlastní referenční ceny.

</Note>

### Ceny

Pole `prices` vrací všechny ceny entity. Jak indexované, tak neindexované.

<SourceCodeTabs langSpecificTabOnly>

[Získání entity se všemi cenami](/documentation/user/en/query/requirements/examples/fetching/pricesField.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek entity načtené se všemi jejími cenami
</NoteTitle>

Dotaz vrací následující seznam všech cen entity `Product`:

<MDInclude sourceVariable="data.queryProduct.recordPage">[Výsledek entity načtené se všemi jejími cenami](/documentation/user/en/query/requirements/examples/fetching/pricesField.graphql.json.md)</MDInclude>

Jak vidíte, je vrácen seznam cen.

</Note>

Pokud však potřebujete pouze konkrétní seznam cen, můžete vrácené ceny filtrovat pomocí argumentů `priceLists` a `currency`.
Na rozdíl od ostatních cenových polí toto pole nezískává data z filtračních omezení, protože by to ztížilo
vrácení všech cen.

<SourceCodeTabs langSpecificTabOnly>

[Získání entity s filtrovanými všemi cenami](/documentation/user/en/query/requirements/examples/fetching/pricesFieldFiltered.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek entity načtené s filtrovanými všemi jejími cenami
</NoteTitle>

Dotaz vrací následující seznam všech cen entity `Product`:

<MDInclude sourceVariable="data.queryProduct.recordPage">[Výsledek entity načtené se všemi jejími cenami](/documentation/user/en/query/requirements/examples/fetching/pricesFieldFiltered.graphql.json.md)</MDInclude>

Jak vidíte, je vrácen filtrovaný seznam cen.

</Note>

</LS>

## Referenční obsah

<LS to="e,j,c,r">

```evitaql-syntax
referenceContent(
    argument:enum(ANY|EXISTING)?,
    argument:string*,
    filterConstraint:filterBy?,
    orderConstraint:orderBy?,
    requireConstraint:entityFetch?,
    requireConstraint:entityGroupFetch?,
    requireConstraint:(page|strip)?
)
```

<dl>
    <dt>argument:enum(ANY|EXISTING)?</dt>
    <dd>
        <p>**Výchozí:** `ANY`</p>

        <p>
        nepovinný argument, pokud je nastaven na `EXISTING`, vrací pouze existující reference na spravované entity;
        výchozí chování je nastaveno na `ANY`, což vrací všechny reference nastavené na entitě, bez ohledu na to,
        zda ukazují na existující nebo neexistující entity (podrobnosti viz kapitola [chování spravovaných referencí](../requirements/reference.md#managed-references-behaviour))
        </p>
    </dd>
    <dt>argument:string*</dt>
    <dd>
        nepovinné nula nebo více řetězcových argumentů představujících názvy referencí, které se mají pro entitu načíst;
        pokud je v argumentu zadáno více názvů, jakékoli odpovídající omezení ve stejném kontejneru `referenceContent`
        se vztahuje na všechny z nich;
        pokud nejsou zadány žádné, načtou se všechny reference a jakékoli odpovídající omezení ve stejném kontejneru
        `referenceContent` se vztahuje na všechny z nich
    </dd>
    <dt>filterConstraint:filterBy?</dt>
    <dd>
        nepovinné filtrační omezení, které umožňuje filtrovat reference, které se mají pro entitu načíst;
        filtrační omezení je zaměřeno na atributy reference, takže pokud chcete filtrovat podle vlastností referencované
        entity, musíte použít omezení [`entityHaving`](../filtering/references.md#entity-having)
    </dd>
    <dt>orderConstraint:orderBy?</dt>
    <dd>
        nepovinné omezení řazení, které umožňuje seřadit načtené reference; omezení řazení je zaměřeno
        na atributy reference, takže pokud chcete řadit podle vlastností referencované entity, musíte použít
        omezení [`entityProperty`](../ordering/references.md#entity-property)
    </dd>
    <dt>requireConstraint:entityFetch?</dt>
    <dd>
        nepovinné požadavkové omezení, které umožňuje načíst tělo referencované entity; omezení `entityFetch`
        může obsahovat vnořené `referenceContent` s dalšími omezeními `entityFetch` / `entityGroupFetch`,
        což umožňuje načítat entity grafovým způsobem do "nekonečné" hloubky
    </dd>
    <dt>requireConstraint:entityGroupFetch?</dt>
    <dd>
        nepovinné požadavkové omezení, které umožňuje načíst tělo skupiny referencované entity; omezení `entityGroupFetch`
        může obsahovat vnořené `referenceContent` s dalšími omezeními `entityFetch` / `entityGroupFetch`,
        což umožňuje načítat entity grafovým způsobem do "nekonečné" hloubky
    </dd>
    <dt>requireConstraint:(page|strip)?</dt>
    <dd>
        nepovinné požadavkové omezení, které umožňuje omezit počet vrácených referencí, pokud je jich
        velké množství; omezení `page` umožňuje stránkování referencí, zatímco omezení `strip`
        umožňuje specifikovat offset a limit pro vrácené reference
    </dd>
</dl>

Požadavek `referenceContent` (<LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/ReferenceContent.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/ReferenceContent.cs</SourceClass></LS>)
vám umožňuje přistupovat k informacím o referencích, které entita má vůči jiným entitám (ať už
spravovaným samotnou evitaDB nebo jakýmkoli jiným externím systémem). Tato varianta `referenceContent` nevrací
atributy nastavené na samotné referenci – pokud tyto atributy potřebujete, použijte variantu
[`referenceContentWithAttributes`](#referenční-obsah-s-atributy).

</LS>
<LS to="g">

Referenční pole umožňují přístup k informacím o referencích, které entita má vůči jiným entitám (ať už
spravovaným samotnou evitaDB nebo jakýmkoli jiným externím systémem). Referenční pole se však od ostatních
poli entity trochu liší. Jsou dynamicky generována na základě schémat referencí, takže k nim můžete přistupovat
přímo z objektu entity podle názvu definovaného ve schématu, a také každá referenční instance má mírně odlišnou
strukturu podle konkrétního schématu reference.

</LS>

Chcete-li získat entitu s referencí na kategorie a značku, použijte následující dotaz:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání referencí na kategorii a značku entity](/documentation/user/en/query/requirements/examples/fetching/referenceContent.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek entity načtené s referencemi na kategorii a značku
</NoteTitle>

Vrácená entita `Product` bude obsahovat primární klíče všech kategorií a značky, na které odkazuje:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Výsledek entity načtené s referencemi na kategorii a značku](/documentation/user/en/query/requirements/examples/fetching/referenceContent.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Výsledek entity načtené s referencemi na kategorii a značku](/documentation/user/en/query/requirements/examples/fetching/referenceContent.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek entity načtené s referencemi na kategorii a značku](/documentation/user/en/query/requirements/examples/fetching/referenceContent.rest.json.md)</MDInclude>

</LS>

</Note>

#### Načítání referencovaných entit (skupin)

V mnoha scénářích budete potřebovat načíst nejen primární klíče referencovaných entit, ale také jejich těla a
těla skupin, na které reference odkazují. Jedním z běžných scénářů je načítání parametrů produktu:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání hodnot parametrů entity](/documentation/user/en/query/requirements/examples/fetching/referenceContentBodies.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek entity načtené s těly referencovaných parametrů a skupin
</NoteTitle>

Vrácená entita `Product` bude obsahovat seznam všech kódů parametrů, na které odkazuje, a kód skupiny,
do které každý parametr patří:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Výsledek entity načtené s těly referencovaných parametrů a skupin](/documentation/user/en/query/requirements/examples/fetching/referenceContentBodies.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Výsledek entity načtené s těly referencovaných parametrů a skupin](/documentation/user/en/query/requirements/examples/fetching/referenceContentBodies.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek entity načtené s těly referencovaných parametrů a skupin](/documentation/user/en/query/requirements/examples/fetching/referenceContentBodies.rest.json.md)</MDInclude>

</LS>

Příklad uvádí pouze atribut *code* pro každou referencovanou entitu a skupinu pro stručnost, ale můžete načíst libovolný
jejich obsah – asociovaná data, ceny, hierarchie nebo i vnořené reference.

</Note>

Pro demonstraci grafového načítání více úrovní referencí načteme produkt s jeho přiřazením ke skupině a
pro každou skupinu načteme její tagy a pro každý tag název kategorie tagu. Dotaz obsahuje 4 úrovně
souvisejících entit: produkt → skupina → tag → kategorie tagu. Dotaz vypadá takto:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání skupin entity a jejich tagů](/documentation/user/en/query/requirements/examples/fetching/referenceContentNested.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek entity načtené s referencovanými skupinami, jejich tagy a kategoriemi tagů
</NoteTitle>

Vrácená entita `Product` bude obsahovat seznam všech skupin, na které odkazuje, pro každou skupinu seznam všech jejích tagů a
pro každý tag jeho přiřazení ke kategorii:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Výsledek entity načtené s těly referencovaných parametrů a skupin](/documentation/user/en/query/requirements/examples/fetching/referenceContentNested.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Výsledek entity načtené s těly referencovaných parametrů a skupin](/documentation/user/en/query/requirements/examples/fetching/referenceContentNested.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek entity načtené s těly referencovaných parametrů a skupin](/documentation/user/en/query/requirements/examples/fetching/referenceContentNested.rest.json.md)</MDInclude>

</LS>

Kategorie tagu není entita spravovaná evitaDB, a proto načítáme pouze její primární klíč.

</Note>

<LS to="g">

#### Načítání atributů referencí

Kromě načítání těl referencovaných entit můžete také načítat atributy nastavené přímo na referenci. Atributy
každé reference lze načíst pomocí pole `attributes`, kde jsou dostupné všechny možné atributy dané reference,
podobně jako atributy entity.

Chcete-li získat entitu s referencí na hodnotu parametru, která ukazuje, která asociace definuje unikátní kombinaci
produkt-varianta a které hodnoty parametrů jsou pouze informativní, použijte následující dotaz:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání entity s referencemi a jejich atributy](/documentation/user/en/query/requirements/examples/fetching/referenceContentWithAttributes.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek entity načtené s referencemi na parametry a jejich atributy
</NoteTitle>

Vrácená entita `Product` bude obsahovat reference na hodnoty parametrů a pro každou z nich určuje typ
vztahu mezi produktem a hodnotou parametru:

<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Výsledek entity načtené se všemi referencemi](/documentation/user/en/query/requirements/examples/fetching/referenceContentWithAttributes.graphql.json.md)</MDInclude>

</LS>

Jak vidíte, hodnoty parametrů *cellular-true*, *display-size-10-2*, *ram-memory-4*, *rom-memory-256* a *color-yellow*
definují variantu produktu, zatímco ostatní parametry pouze popisují dodatečné vlastnosti produktu.

</Note>

</LS>

<LS to="e,j,c,r">

#### Chování spravovaných referencí

evitaDB je určena jako sekundární databáze pro rychlý přístup ke čtení, takže nevynucuje cizí klíče na
referencích. To znamená, že můžete mít reference na entity, které v evitaDB neexistují – možná proto, že budou
indexovány později. V určitých situacích a klientech může být obtížné pracovat s referencemi ukazujícími na neexistující
entity, a proto můžete instruovat evitaDB, aby to udělala za vás. Pokud nastavíte první nepovinný argument
omezení `referenceContent` na `EXISTING`, evitaDB zajistí, že budou vráceny pouze reference na existující entity.

Dodatečné filtrování těchto referencí není zdarma, proto to není výchozí chování. I když entitu načítáte
za účelem provedení změn, můžete chtít vidět všechny reference, včetně těch, které ukazují na neexistující entity.
Jinak byste mohli posílat zbytečné upsert mutace na server.

</LS>

#### Filtrování referencí

Někdy mají vaše entity mnoho referencí a v určitých scénářích je všechny nepotřebujete. V tomto případě můžete
použít filtrační omezení k odfiltrování nepotřebných referencí.

<Note type="info">

Filtr <LS to="e,j,r,c">`referenceContent`</LS>
<LS to="g">na referenčních polích</LS>
implicitně cílí na atributy na stejné referenci, na kterou ukazuje, takže nemusíte
specifikovat omezení [`referenceHaving`](../filtering/references.md#reference-having). Pokud však potřebujete deklarovat
omezení na atributech referencované entity, musíte je zabalit do kontejneru [`entityHaving`](../filtering/references.md#entity-having).

</Note>

Například váš produkt má mnoho parametrů, ale na detailní stránce produktu potřebujete načíst pouze ty, které jsou
součástí skupiny obsahující atribut *isVisibleInDetail* nastavený na *TRUE*. Pro načtení pouze těchto parametrů použijte
následující dotaz:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání hodnot parametrů entity viditelných na detailní stránce](/documentation/user/en/query/requirements/examples/fetching/referenceContentFilter.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek entity načtené s těly referencovaných parametrů, které patří do skupiny viditelné na detailní stránce
</NoteTitle>

Vrácená entita `Product` bude obsahovat seznam všech kódů parametrů, na které odkazuje, a kód skupiny,
do které každý parametr patří:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Výsledek entity načtené s těly referencovaných parametrů, které patří do skupiny viditelné na detailní stránce](/documentation/user/en/query/requirements/examples/fetching/referenceContentFilter.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Výsledek entity načtené s těly referencovaných parametrů, které patří do skupiny viditelné na detailní stránce](/documentation/user/en/query/requirements/examples/fetching/referenceContentFilter.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek entity načtené s těly referencovaných parametrů, které patří do skupiny viditelné na detailní stránce](/documentation/user/en/query/requirements/examples/fetching/referenceContentFilter.rest.json.md)</MDInclude>

</LS>

Jak vidíte, jsou vráceny pouze parametry skupin, které mají *isVisibleInDetail* nastaveno na *TRUE*.

</Note>

#### Řazení referencí

Ve výchozím nastavení jsou reference řazeny podle primárního klíče referencované entity. Pokud chcete reference řadit
podle jiné vlastnosti – buď atributu nastaveného na samotné referenci, nebo vlastnosti referencované entity –
můžete použít omezení řazení uvnitř požadavku `referenceContent`.

<Note type="info">

Řazení <LS to="e,j,r,c">`referenceContent`</LS>
<LS to="g">na referenčních polích</LS> implicitně cílí na atributy na stejné referenci,
takže nemusíte specifikovat omezení [`referenceProperty`](../ordering/reference.md#vlastnost-reference).
Pokud však potřebujete deklarovat
omezení na atributech referencované entity, musíte je zabalit do kontejneru [`entityProperty`](../ordering/reference.md#vlastnost-entity).

</Note>

Řekněme, že chcete, aby vaše parametry byly seřazeny podle anglického názvu parametru. K tomu použijte následující
dotaz:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání hodnot parametrů entity seřazených podle názvu](/documentation/user/en/query/requirements/examples/fetching/referenceContentOrder.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek entity načtené s referencovanými parametry seřazenými podle názvu
</NoteTitle>

Vrácená entita `Product` bude obsahovat seznam všech parametrů v očekávaném pořadí:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Výsledek entity načtené s referencovanými parametry seřazenými podle názvu](/documentation/user/en/query/requirements/examples/fetching/referenceContentOrder.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Výsledek entity načtené s referencovanými parametry seřazenými podle názvu](/documentation/user/en/query/requirements/examples/fetching/referenceContentOrder.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek entity načtené s referencovanými parametry seřazenými podle názvu](/documentation/user/en/query/requirements/examples/fetching/referenceContentOrder.rest.json.md)</MDInclude>

</LS>

</Note>

#### Stránkování / omezení počtu načtených referencí

Ve výchozím nastavení jsou s entitou vráceny všechny reference s požadovaným názvem reference. Pokud je možné
mít velké množství referencí, můžete počet vrácených referencí omezit použitím [`page`](paging.md#page) nebo
[`strip`](paging.md#strip) jako posledního omezení v kontejneru `referenceContent` a přistupovat k nim stránkovaně.
Stránkování můžete kombinovat s omezeními `filterBy` a `orderBy` dle potřeby.

<Note type="info">

Vyhněte se načítání příliš velkého množství dat, pokud je nepotřebujete; vaše dotazy budou rychlejší, pokud načtete
jen data, která opravdu potřebujete. Při komunikaci server/klient přes síť záleží na každém bajtu. Pokud tedy
potřebujete pouze zjistit, zda existují reference určitého typu, použijte `strip` s `limit: 0` nebo `page` s `size: 0`
a načtěte pouze počet referencí bez načítání skutečných dat.

</Note>

Řekněme, že potřebujete pouze zjistit, zda existuje nějaký parametr pro produkt. K tomu použijte následující dotaz:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání počtu hodnot parametrů entity bez jejich načtení](/documentation/user/en/query/requirements/examples/fetching/referenceContentPageEmpty.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek entity načtené pouze s počtem parametrů
</NoteTitle>

Vrácená entita `Product` bude obsahovat pouze celkový počet hodnot parametrů a žádná skutečná data:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Výsledek entity načtené pouze s počtem parametrů](/documentation/user/en/query/requirements/examples/fetching/referenceContentPageEmpty.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Výsledek entity načtené pouze s počtem parametrů](/documentation/user/en/query/requirements/examples/fetching/referenceContentPageEmpty.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek entity načtené pouze s počtem parametrů](/documentation/user/en/query/requirements/examples/fetching/referenceContentPageEmpty.rest.json.md)</MDInclude>

</LS>

</Note>

Pokud je možný počet hodnot parametrů velký, je lepší omezit vrácený počet na velikost, kterou klient skutečně
může zpracovat/zobrazit. V této situaci pravděpodobně budete chtít zobrazit nejdůležitější jako první, takže
se vám bude hodit omezení `orderBy`:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání počtu hodnot parametrů entity bez jejich načtení](/documentation/user/en/query/requirements/examples/fetching/referenceContentPage.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek entity načtené pouze s top 3 parametry
</NoteTitle>

Vrácená entita `Product` bude obsahovat maximálně 3 parametry a poskytne přístup k celkovému počtu hodnot parametrů:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Výsledek entity načtené pouze s top 3 parametry](/documentation/user/en/query/requirements/examples/fetching/referenceContentPage.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Výsledek entity načtené pouze s top 3 parametry](/documentation/user/en/query/requirements/examples/fetching/referenceContentPage.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek entity načtené pouze s top 3 parametry](/documentation/user/en/query/requirements/examples/fetching/referenceContentPage.rest.json.md)</MDInclude>

</LS>

</Note>

<LS to="e,j,c">

### Všechen referenční obsah

```evitaql-syntax
referenceContentAll(
    filterConstraint:filterBy?,
    orderConstraint:filterBy?,
    requireConstraint:entityFetch?,
    requireConstraint:entityGroupFetch?,
    requireConstraint:(page|strip)?
)
```

<dl>
    <dt>filterConstraint:filterBy?</dt>
    <dd>
        nepovinné filtrační omezení, které umožňuje filtrovat reference, které se mají pro entitu načíst;
        filtrační omezení je zaměřeno na atributy reference, takže pokud chcete filtrovat podle vlastností referencované
        entity, musíte použít omezení [`entityHaving`](../filtering/references.md#entity-having)
    </dd>
    <dt>orderConstraint:orderBy?</dt>
    <dd>
        nepovinné omezení řazení, které umožňuje seřadit načtené reference; omezení řazení je zaměřeno
        na atributy reference, takže pokud chcete řadit podle vlastností referencované entity, musíte použít
        omezení [`entityProperty`](../ordering/references.md#entity-property)
    </dd>
    <dt>requireConstraint:entityFetch?</dt>
    <dd>
        nepovinné požadavkové omezení, které umožňuje načíst tělo referencované entity; omezení `entityFetch`
        může obsahovat vnořené `referenceContent` s dalšími omezeními `entityFetch` / `entityGroupFetch`,
        což umožňuje načítat entity grafovým způsobem do "nekonečné" hloubky
    </dd>
    <dt>requireConstraint:entityGroupFetch?</dt>
    <dd>
        nepovinné požadavkové omezení, které umožňuje načíst tělo skupiny referencované entity; omezení `entityGroupFetch`
        může obsahovat vnořené `referenceContent` s dalšími omezeními `entityFetch` / `entityGroupFetch`,
        což umožňuje načítat entity grafovým způsobem do "nekonečné" hloubky
    </dd>
    <dt>requireConstraint:(page|strip)?</dt>
    <dd>
        nepovinné požadavkové omezení, které umožňuje omezit počet vrácených referencí, pokud je jich
        velké množství; omezení `page` umožňuje stránkování referencí, zatímco omezení `strip`
        umožňuje specifikovat offset a limit pro vrácené reference
    </dd>
</dl>

`referenceContentAll` (<LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/ReferenceContent.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/ReferenceContent.cs</SourceClass></LS>)
je varianta požadavku [`referenceContent`](#referenční-obsah), která umožňuje přístup k informacím
o referencích, které entita má vůči jiným entitám (ať už spravovaným samotnou evitaDB nebo jakýmkoli jiným externím
systémem). `referenceContentAll` je zkratka, která jednoduše cílí na všechny reference definované pro entitu. Lze ji
použít pro rychlé zjištění všech možných referencí entity.

Podrobné informace naleznete v kapitole požadavku [`referenceContent`](#referenční-obsah).

Chcete-li získat entitu se všemi dostupnými referencemi, použijte následující dotaz:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání entity se všemi referencemi](/documentation/user/en/query/requirements/examples/fetching/referenceContentAll.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek entity načtené se všemi referencemi
</NoteTitle>

Vrácená entita `Product` bude obsahovat primární klíče a kódy všech svých referencí:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Výsledek entity načtené se všemi referencemi](/documentation/user/en/query/requirements/examples/fetching/referenceContentAll.evitaql.json.md)</MDInclude>

</LS>

</Note>

</LS>

<LS to="e,j,c,r">

### Referenční obsah s atributy

```evitaql-syntax
referenceContentWithAttributes(
    argument:string*,
    filterConstraint:filterBy?,
    orderConstraint:orderBy?,
    requireConstraint:attributeContent?,
    requireConstraint:entityFetch?,
    requireConstraint:entityGroupFetch?,
    requireConstraint:(page|strip)?
)
```

<dl>
    <dt>argument:string*</dt>
    <dd>
        nepovinné nula nebo více řetězcových argumentů představujících názvy referencí, které se mají pro entitu načíst;
        pokud je v argumentu zadáno více názvů, jakékoli odpovídající omezení ve stejném kontejneru `referenceContent`
        se vztahuje na všechny z nich;
        pokud nejsou zadány žádné, načtou se všechny reference a jakékoli odpovídající omezení ve stejném kontejneru
        `referenceContent` se vztahuje na všechny z nich
    </dd>
    <dt>filterConstraint:filterBy?</dt>
    <dd>
        nepovinné filtrační omezení, které umožňuje filtrovat reference, které se mají pro entitu načíst;
        filtrační omezení je zaměřeno na atributy reference, takže pokud chcete filtrovat podle vlastností referencované
        entity, musíte použít omezení [`entityHaving`](../filtering/references.md#entity-having)
    </dd>
    <dt>orderConstraint:filterBy?</dt>
    <dd>
        nepovinné omezení řazení, které umožňuje seřadit načtené reference; omezení řazení je zaměřeno
        na atributy reference, takže pokud chcete řadit podle vlastností referencované entity, musíte použít
        omezení [`entityProperty`](../ordering/references.md#entity-property)
    </dd>
    <dt>requireConstraint:attributeContent?</dt>
    <dd>
        nepovinné požadavkové omezení, které umožňuje omezit sadu atributů reference, které se mají načíst;
        pokud není specifikováno omezení `attributeContent`, načtou se všechny atributy reference
    </dd>
    <dt>requireConstraint:entityFetch?</dt>
    <dd>
        nepovinné požadavkové omezení, které umožňuje načíst tělo referencované entity; omezení `entityFetch`
        může obsahovat vnořené `referenceContent` s dalšími omezeními `entityFetch` / `entityGroupFetch`,
        což umožňuje načítat entity grafovým způsobem do "nekonečné" hloubky
    </dd>
    <dt>requireConstraint:entityGroupFetch?</dt>
    <dd>
        nepovinné požadavkové omezení, které umožňuje načíst tělo skupiny referencované entity; omezení `entityGroupFetch`
        může obsahovat vnořené `referenceContent` s dalšími omezeními `entityFetch` / `entityGroupFetch`,
        což umožňuje načítat entity grafovým způsobem do "nekonečné" hloubky
    </dd>
    <dt>requireConstraint:(page|strip)?</dt>
    <dd>
        nepovinné požadavkové omezení, které umožňuje omezit počet vrácených referencí, pokud je jich
        velké množství; omezení `page` umožňuje stránkování referencí, zatímco omezení `strip`
        umožňuje specifikovat offset a limit pro vrácené reference
    </dd>
</dl>

`referenceContentWithAttributes` (<LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/ReferenceContent.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/ReferenceContent.cs</SourceClass></LS>)
je varianta požadavku [`referenceContent`](#referenční-obsah), která umožňuje přístup k informacím
o referencích, které entita má vůči jiným entitám (ať už spravovaným samotnou evitaDB nebo jakýmkoli jiným externím
systémem) a atributům nastaveným na těchto referencích. `referenceContentWithAttributes` umožňuje specifikovat seznam
atributů, které se mají načíst, ale ve výchozím nastavení načítá všechny atributy na referenci.

Podrobné informace naleznete v kapitole požadavku [`referenceContent`](#referenční-obsah).

Chcete-li získat entitu s referencí na hodnotu parametru, která ukazuje, která asociace definuje unikátní kombinaci
produkt-varianta a které hodnoty parametrů jsou pouze informativní, použijte následující dotaz:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání entity s referencemi a jejich atributy](/documentation/user/en/query/requirements/examples/fetching/referenceContentWithAttributes.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek entity načtené s referencemi na parametry a jejich atributy
</NoteTitle>

Vrácená entita `Product` bude obsahovat reference na hodnoty parametrů a pro každou z nich určuje typ
vztahu mezi produktem a hodnotou parametru:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Výsledek entity načtené se všemi referencemi](/documentation/user/en/query/requirements/examples/fetching/referenceContentWithAttributes.evitaql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek entity načtené se všemi referencemi](/documentation/user/en/query/requirements/examples/fetching/referenceContentWithAttributes.rest.json.md)</MDInclude>

</LS>

Jak vidíte, hodnoty parametrů *cellular-true*, *display-size-10-2*, *ram-memory-4*, *rom-memory-256* a *color-yellow*
definují variantu produktu, zatímco ostatní parametry pouze popisují dodatečné vlastnosti produktu.

</Note>

</LS>

<LS to="e,j,c">

### Všechen referenční obsah s atributy

```evitaql-syntax
referenceContentAllWithAttributes(
    filterConstraint:filterBy?,
    orderConstraint:orderBy?,
    requireConstraint:attributeContent?,
    requireConstraint:entityFetch?,
    requireConstraint:entityGroupFetch?,
    requireConstraint:(page|strip)?
)
```

<dl>
    <dt>filterConstraint:filterBy?</dt>
    <dd>
        nepovinné filtrační omezení, které umožňuje filtrovat reference, které se mají pro entitu načíst;
        filtrační omezení je zaměřeno na atributy reference, takže pokud chcete filtrovat podle vlastností referencované
        entity, musíte použít omezení [`entityHaving`](../filtering/references.md#entity-having)
    </dd>
    <dt>orderConstraint:orderBy?</dt>
    <dd>
        nepovinné omezení řazení, které umožňuje seřadit načtené reference; omezení řazení je zaměřeno
        na atributy reference, takže pokud chcete řadit podle vlastností referencované entity, musíte použít
        omezení [`entityProperty`](../ordering/references.md#entity-property)
    </dd>
    <dt>requireConstraint:attributeContent?</dt>
    <dd>
        nepovinné požadavkové omezení, které umožňuje omezit sadu atributů reference, které se mají načíst;
        pokud není specifikováno omezení `attributeContent`, načtou se všechny atributy reference
    </dd>
    <dt>requireConstraint:entityFetch?</dt>
    <dd>
        nepovinné požadavkové omezení, které umožňuje načíst tělo referencované entity; omezení `entityFetch`
        může obsahovat vnořené `referenceContent` s dalšími omezeními `entityFetch` / `entityGroupFetch`,
        což umožňuje načítat entity grafovým způsobem do "nekonečné" hloubky
    </dd>
    <dt>requireConstraint:entityGroupFetch?</dt>
    <dd>
        nepovinné požadavkové omezení, které umožňuje načíst tělo skupiny referencované entity; omezení `entityGroupFetch`
        může obsahovat vnořené `referenceContent` s dalšími omezeními `entityFetch` / `entityGroupFetch`,
        což umožňuje načítat entity grafovým způsobem do "nekonečné" hloubky
    </dd>
    <dt>requireConstraint:(page|strip)?</dt>
    <dd>
        nepovinné požadavkové omezení, které umožňuje omezit počet vrácených referencí, pokud je jich
        velké množství; omezení `page` umožňuje stránkování referencí, zatímco omezení `strip`
        umožňuje specifikovat offset a limit pro vrácené reference
    </dd>
</dl>

`referenceContentAllWithAttributes` (<LS to="e,j,r"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/ReferenceContent.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/ReferenceContent.cs</SourceClass></LS>)
je varianta požadavku [`referenceContent`](#referenční-obsah), která umožňuje přístup k informacím
o referencích, které entita má vůči jiným entitám (ať už spravovaným samotnou evitaDB nebo jakýmkoli jiným externím
systémem) a atributům nastaveným na těchto referencích. `referenceContentAllWithAttributes` umožňuje specifikovat seznam
atributů, které se mají načíst, ale ve výchozím nastavení načítá všechny atributy na referenci. Neumožňuje
specifikovat názvy referencí – protože cílí na všechny z nich, a tak můžete specifikovat pouze omezení a atributy,
které jsou společné pro všechny reference. Toto omezení je užitečné pouze v průzkumných scénářích.

</LS>

Podrobné informace naleznete v kapitole požadavku [`referenceContent`](#referenční-obsah).

<LS to="g">

<Note type="question">

<NoteTitle toggles="true">

##### Potřebujete různé konfigurace stejné reference?
</NoteTitle>

Pokud potřebujete různé konfigurace stejné reference, můžete využít [GraphQL aliasy](https://graphql.org/learn/queries/#aliases)
k definici více referenčních polí s různými parametry.

Chcete-li rozlišit mediální soubory podle galerie jako samostatné kolekce, můžete definovat více referenčních polí s
unikátními aliasy a vlastním filtrováním: 

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad dotazu pro požadavek různých konfigurací stejného typu reference](/documentation/user/en/query/requirements/examples/fetching/namedReferenceContent.graphql)

</SourceCodeTabs>

To povede k následujícímu výsledku:

<MDInclude sourceVariable="data.listProduct">[Výsledek entity načtené s různými konfiguracemi stejné reference](/documentation/user/en/query/requirements/examples/fetching/namedReferenceContent.graphql.json.md)</MDInclude>

</Note>

</LS>

<LS to="e,j,c,r">

Chcete-li získat entitu se všemi referencemi a jejich atributy, použijte následující dotaz:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání entity se všemi referencemi a jejich atributy](/documentation/user/en/query/requirements/examples/fetching/referenceContentAllWithAttributes.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek entity načtené se všemi referencemi a jejich atributy
</NoteTitle>

Vrácená entita `Product` bude obsahovat všechny reference a atributy nastavené na tomto vztahu:

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Výsledek entity načtené se všemi referencemi](/documentation/user/en/query/requirements/examples/fetching/referenceContentAllWithAttributes.evitaql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek entity načtené se všemi referencemi](/documentation/user/en/query/requirements/examples/fetching/referenceContentAllWithAttributes.rest.json.md)</MDInclude>

</LS>

</Note>

</LS>

<LS to="e,j,c,r">

## Doprovodný obsah ceny

```evitaql-syntax
accompanyingPriceContent(
    argument:string?,
    argument:string*
)
```

<dl>
    <dt>argument:string?</dt>
    <dd>
        Volitelná specifikace názvu doprovodné ceny, která by měla být použita k odlišení této doprovodné ceny
        od ostatních. Pokud není zadáno, použije se název doprovodné ceny `default`.
    </dd>
    <dt>argument:string*</dt>
    <dd>
        Volitelný prioritizovaný seznam názvů ceníků, které by měly být použity pro výpočet doprovodné ceny.
        Pokud není zadáno, použije se požadavek [`defaultAccompanyingPriceLists`](price.md#výchozí-doprovodné-ceníky) 
        pro určení tohoto prioritizovaného seznamu ceníků. V opačném případě dojde k chybě.
    </dd>
</dl>

Požadavek <LS to="j,e,r"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/AccompanyingPriceContent.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/AccompanyingPriceContent.cs</SourceClass></LS>
určuje, které další ceny mají být vypočítány vedle prodejní ceny. Tyto ceny jsou úzce svázány s prodejní cenou a nelze je počítat nezávisle. Ceny lze počítat jak z indexovaných, tak neindexovaných ceníků. Řídí se stejnými [pravidly výpočtu](/documentation/user/en/deep-dive/price-for-sale-calculation.md) jako prodejní cena,
ale počítají se pouze z entit, které jsou zahrnuty do výpočtu prodejní ceny.

Můžete vypočítat více doprovodných cen najednou, ale musíte zadat název každé doprovodné ceny, abyste je od sebe odlišili.

<LS to="r">

<Note type="warning">

V současné době v REST API je současně podporována pouze jedna výchozí doprovodná cena a jedna vlastní pojmenovaná doprovodná cena
kvůli omezením JSON dotazů.
Podrobnosti si můžete ověřit v [tomto issue](https://github.com/FgForrest/evitaDB/issues/895#issuecomment-2958723100).

</Note>

</LS>

Tento princip si ukážeme na složitějším příkladu:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad dotazu pro výpočet různých doprovodných cen](/documentation/user/en/query/requirements/examples/fetching/accompanying-price-content.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledky výpočtu různých doprovodných cen

</NoteTitle>

Pro náš příklad jsme vybrali produkt s použitím strategie zpracování vnitřních záznamů `LOWEST_PRICE` pro výpočet prodejní ceny.
Zároveň jsme požadovali více doprovodných cen.

<LS to="e,j,c">

1. první nemá název ani zadané ceníky, bude vypočítána jako `default` doprovodná cena s použitím pořadí ceníků definovaného v požadavku `defaultAccompanyingPriceLists`;
2. druhá má název `custom` a nemá zadané ceníky, bude vypočítána jako `custom` doprovodná cena s použitím pořadí ceníků definovaného v požadavku `defaultAccompanyingPriceLists`;
3. třetí má název `special` a pro výpočet ceny používá ceníky `employee-basic-price` a `b2b-basic-price`;

</LS>
<LS to="r">

1. první nemá název ani zadané ceníky, bude vypočítána jako `default` doprovodná cena s použitím pořadí ceníků definovaného v požadavku `defaultAccompanyingPriceLists`;
2. druhá má název `special` a pro výpočet ceny používá ceníky `employee-basic-price` a `b2b-basic-price`;

</LS>

Výsledky dotazu, který tyto ceny vypočítává, jsou uvedeny níže:

<LS to="e,j,c">

<MDInclude>[Výsledky výpočtu různých doprovodných cen](/documentation/user/en/query/requirements/examples/fetching/accompanying-price-content.evitaql.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledky výpočtu různých doprovodných cen](/documentation/user/en/query/requirements/examples/fetching/accompanying-price-content.rest.json.md)</MDInclude>

</LS>

Protože byla použita strategie `LOWEST_PRICE`, prodejní cena se počítá zvlášť pro každý vnitřní záznam produktu
a poté se vybere nejnižší cena. Následně se vypočítají doprovodné ceny, ale pouze z cen, které sdílejí
stejný vnitřní záznam jako prodejní cena.

Pokud by byla použita strategie `SUM`, doprovodné ceny by se počítaly pouze pro ceny vztahující se k vnitřním záznamům,
které jsou součástí výpočtu prodejní ceny. Proto i když existují ceny pro ceníky použité ve
`default` nebo `special` doprovodných cenách, nebyly by součástí součtové ceny `default` nebo `special`, protože jejich
prodejní protějšek neexistuje.

</Note>

</LS>