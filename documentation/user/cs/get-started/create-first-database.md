---
title: Vytvoření první databáze
perex: Tento článek vás provede základy práce s evitaDB API pro vytváření, aktualizaci, dotazování a mazání entit v katalogu.
date: '17.1.2023'
author: Ing. Jan Novotný
proofreading: done
preferredLang: java
commit: '4c8e30c17df75524da54fca556c6f170e32409b2'
---
<LS to="j">

Předpokládáme, že již máte následující úryvek kódu z [předchozí kapitoly](run-evitadb.md):

<SourceCodeTabs local>

[Příklad spuštění serveru evitaDB](/documentation/user/en/get-started/example/complete-startup.java)

</SourceCodeTabs>

Instance evitaDB je tedy nyní spuštěná a připravená ke komunikaci.

</LS>

<LS to="g,r,c">

Předpokládáme, že již máte spuštěný následující Docker image z [předchozí kapitoly](run-evitadb.md):

```shell
# Varianta pro Linux: spuštění v popředí, zničení kontejneru po ukončení, použití hostitelských portů bez NAT
docker run --name evitadb -i --rm --net=host \       
       index.docker.io/evitadb/evitadb:latest

# Windows / MacOS: je zde otevřený problém https://github.com/docker/roadmap/issues/238
# a porty je potřeba otevřít ručně
docker run --name evitadb -i --rm -p 5555:5555 \       
       index.docker.io/evitadb/evitadb:latest
```

Webový API server je tedy nyní spuštěný a připravený ke komunikaci.

</LS>

<LS to="j,c">

## Definujte nový katalog se schématem

Nyní můžete použít <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/EvitaClient.cs</SourceClass></LS>
k definování nového katalogu a vytvoření předdefinovaných schémat pro více kolekcí: `Brand`, `Category` a `Product`.
Každá kolekce obsahuje některé atributy (lokalizované nebo nelokalizované), kategorie je označena jako hierarchická entita,
která tvoří strom, produkt má povolené ceny:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java" langSpecificTabOnly local>

[Příklad definice katalogu a schématu pro entity kolekcí](/documentation/user/en/get-started/example/define-catalog-with-schema.java)
</SourceCodeTabs>

</LS>

<LS to="g">

## Definujte nový katalog se schématem

Nyní můžete použít [system API](/documentation/user/en/use/connectors/graphql.md#graphql-api-instances) přes URL
`https://your-server:5555/gql/system` k vytvoření nového prázdného katalogu:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java" langSpecificTabOnly local>

[Příklad vytvoření prázdného katalogu](/documentation/user/en/get-started/example/define-catalog.graphql)
</SourceCodeTabs>

a naplnit jej novými předdefinovanými schématy pro více kolekcí: `Brand`, `Category` a `Product` úpravou jeho schématu přes [catalog schema API](/documentation/user/en/use/connectors/graphql.md#graphql-api-instances) na URL
`https://your-server:5555/gql/evita/schema`. Každá kolekce
obsahuje některé atributy (lokalizované nebo nelokalizované), kategorie je označena jako hierarchická entita, která tvoří
strom, produkt má povolené ceny:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Příklad vytvoření prázdného katalogu](/documentation/user/en/get-started/example/define-schema-for-catalog.graphql)
</SourceCodeTabs>

</LS>

<LS to="r">

## Definujte nový katalog se schématem

Nyní můžete použít [system API](/documentation/user/en/use/connectors/rest.md#rest-api-instances) přes URL
`https://your-server:5555/rest/system/catalogs` k vytvoření nového prázdného katalogu:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java" langSpecificTabOnly local>

[Příklad vytvoření prázdného katalogu](/documentation/user/en/get-started/example/define-catalog.rest)
</SourceCodeTabs>

a naplnit jej novými předdefinovanými schématy pro více kolekcí: `Brand`, `Category` a `Product` úpravou jeho schématu přes [catalog schema API](/documentation/user/en/use/connectors/rest.md#rest-api-instances) na URL
`https://your-server:5555/rest/evita/schema`. Každá kolekce
obsahuje některé atributy (lokalizované nebo nelokalizované), kategorie je označena jako hierarchická entita, která tvoří
strom, produkt má povolené ceny:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Příklad vytvoření prázdného katalogu](/documentation/user/en/get-started/example/define-schema-for-catalog.rest)
</SourceCodeTabs>

</LS>

<LS to="j,c">

## Otevřete relaci ke katalogu a vložte svou první entitu

Jakmile je katalog vytvořen a schéma je známo, můžete do katalogu vložit první entitu:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/define-catalog-with-schema.java" langSpecificTabOnly local>

[Příklad vložení entity](/documentation/user/en/get-started/example/create-first-entity.java)
</SourceCodeTabs>

Relace je implicitně otevřena v rozsahu metody <LS to="j">`updateCatalog`. Analogická metoda `queryCatalog` na
evitaDB kontraktu</LS><LS to="c">`UpdateCatalog`. Analogická metoda `QueryCatalog` na
úrovni třídy evitaDB</LS> také otevírá relaci, ale pouze v režimu pro čtení, což neumožňuje aktualizovat katalog.
Rozlišování mezi relacemi pro zápis a pouze pro čtení umožňuje evitaDB optimalizovat zpracování dotazů a rozložit
zátěž v clusteru.

Podívejme se, jak můžete získat entitu, kterou jste právě vytvořili, v jiné relaci pouze pro čtení.

<SourceCodeTabs setup="/documentation/user/en/get-started/example/create-first-entity.java" langSpecificTabOnly local>

[Příklad načtení entity podle primárního klíče](/documentation/user/en/get-started/example/read-entity-by-pk.java)
</SourceCodeTabs>

</LS>
<LS to="g">

## Otevřete relaci ke katalogu a vložte svou první entitu

Jakmile je katalog vytvořen a schéma je známo, můžete do katalogu vložit první entitu přes
[catalog data API](/documentation/user/en/use/connectors/graphql.md#graphql-api-instances) na adrese
`https://your-server:5555/gql/evita`:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/define-catalog-with-schema.java" langSpecificTabOnly local>

[Příklad vložení entity](/documentation/user/en/get-started/example/create-first-entity.graphql)
</SourceCodeTabs>

Relace je implicitně otevřena v rámci jednoho GraphQL požadavku a je také automaticky uzavřena po jeho zpracování.
V závislosti na těle požadavku evitaDB buď vytvoří
relaci pouze pro čtení (pro dotazy), nebo relaci pro zápis (pro mutace).
Rozlišování mezi relacemi pro zápis a pouze pro čtení umožňuje evitaDB optimalizovat zpracování dotazů a rozložit
zátěž v clusteru.

Podívejme se, jak můžete získat entitu, kterou jste právě vytvořili, v jiné relaci pouze pro čtení přes stejné catalog data API
jako výše.

<SourceCodeTabs setup="/documentation/user/en/get-started/example/create-first-entity.java" langSpecificTabOnly local>

[Příklad načtení entity podle primárního klíče](/documentation/user/en/get-started/example/read-entity-by-pk.graphql)
</SourceCodeTabs>

</LS>
<LS to="r">

## Otevřete relaci ke katalogu a vložte svou první entitu

Jakmile je katalog vytvořen a schéma je známo, můžete do katalogu vložit první entitu přes
[catalog data API](/documentation/user/en/use/connectors/rest.md#rest-api-instances) na adrese
`https://your-server:5555/rest/evita/brand`:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/define-catalog-with-schema.java" langSpecificTabOnly local>

[Příklad vložení entity](/documentation/user/en/get-started/example/create-first-entity.rest)
</SourceCodeTabs>

Relace je implicitně otevřena v rámci jednoho REST požadavku a je také automaticky uzavřena po jeho zpracování.
V závislosti na typu požadavku evitaDB buď vytvoří
relaci pouze pro čtení (pro dotazy), nebo relaci pro zápis (pro mutace).
Rozlišování mezi relacemi pro zápis a pouze pro čtení umožňuje evitaDB optimalizovat zpracování dotazů a rozložit
zátěž v clusteru.

Podívejme se, jak můžete získat entitu, kterou jste právě vytvořili, v jiné relaci pouze pro čtení přes stejné catalog data API
jako výše.

<SourceCodeTabs setup="/documentation/user/en/get-started/example/create-first-entity.java" langSpecificTabOnly local>

[Příklad načtení entity podle primárního klíče](/documentation/user/en/get-started/example/read-entity-by-pk.rest)
</SourceCodeTabs>

</LS>

<LS to="j,g,r,c">

## Vytvořte malý dataset

Jakmile zvládnete základy, můžete si vytvořit malý dataset pro další práci:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/define-catalog-with-schema.java" langSpecificTabOnly local>

[Příklad vytvoření malého datasetu](/documentation/user/en/get-started/example/create-small-dataset.java)
</SourceCodeTabs>

Je to hodně kódu, ale ve skutečnosti byste pravděpodobně napsali transformační funkci z primárního modelu, který již
máte v relační databázi. Příklad ukazuje, jak definovat atributy, asociovaná data, reference a ceny.

</LS>

<LS to="j,g,r,c">

## Vypsání existujících entit

Abyste získali lepší představu o datech, pojďme vypsat existující entity z databáze.

<SourceCodeTabs setup="/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly local>

[Příklad vypsání entit](/documentation/user/en/get-started/example/list-entities.java)
</SourceCodeTabs>

Data můžete také filtrovat a řadit:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly local>

[Příklad filtrování a řazení entit](/documentation/user/en/get-started/example/filter-order-entities.java)
</SourceCodeTabs>

Nebo můžete filtrovat všechny produkty podle ceny v EUR vyšší než 300 € a seřadit podle ceny od nejlevnějšího:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly local>

[Příklad filtrování a řazení produktů podle ceny](/documentation/user/en/get-started/example/filter-order-products-by-price.java)
</SourceCodeTabs>

</LS>

<LS to="j,c">

## Aktualizujte libovolnou existující entitu

Aktualizace entity je podobná jako vytvoření nové entity:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly local>

[Příklad vypsání entit](/documentation/user/en/get-started/example/update-entity.java)
</SourceCodeTabs>

Hlavní rozdíl je v tom, že nejprve načtete entitu se všemi daty, která chcete aktualizovat, ze serveru evitaDB a
provedete na ní změny. Načtená entita je neměnná, proto ji musíte nejprve otevřít pro zápis. Tato akce vytvoří
builder, který obaluje původní neměnný objekt a umožňuje zachytit změny. Tyto změny jsou nakonec
shromážděny a předány serveru v metodě <LS to="j">`upsertVia`</LS><LS to="c">`UpsertVia`</LS>.

Více informací naleznete v [popisu write API](../use/api/write-data.md#upsert).

</LS>

<LS to="g">

## Aktualizujte libovolnou existující entitu

Aktualizace entity je podobná jako vytvoření nové entity:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly local>

[Příklad vypsání entit](/documentation/user/en/get-started/example/update-entity.graphql)
</SourceCodeTabs>

Hlavní rozdíl je v tom, že musíte předat primární klíč existující entity, kterou chcete upravit, a specifikovat pouze ty mutace,
které mění již existující data.

Více informací naleznete v [popisu write API](../use/api/write-data.md#upsert).

</LS>
<LS to="r">

## Aktualizujte libovolnou existující entitu

Aktualizace entity je podobná jako vytvoření nové entity:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly local>

[Příklad vypsání entit](/documentation/user/en/get-started/example/update-entity.rest)
</SourceCodeTabs>

Hlavní rozdíl je v tom, že musíte předat primární klíč existující entity, kterou chcete upravit, a specifikovat pouze ty mutace,
které mění již existující data.

Více informací naleznete v [popisu write API](../use/api/write-data.md#upsert).

</LS>

<LS to="j">

## Smazání libovolné existující entity

Entitu můžete smazat podle jejího primárního klíče:

<LS to="j">
<SourceCodeTabs setup="/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly local>

[Příklad smazání entity podle PK](/documentation/user/en/get-started/example/delete-entity-by-pk.java)
</SourceCodeTabs>
</LS>
<LS to="c">
<SourceCodeTabs setup="/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly local>

[Příklad smazání entity podle PK](/documentation/user/en/get-started/example/delete-entity-by-pk.cs)
</SourceCodeTabs>
</LS>

Nebo můžete zadat dotaz, který odstraní všechny entity odpovídající dotazu:

<LS to="j">
<SourceCodeTabs setup="/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly local>

[Příklad smazání entity pomocí dotazu](/documentation/user/en/get-started/example/delete-entity-by-query.java)
</SourceCodeTabs>
</LS>
<LS to="c">
<SourceCodeTabs setup="/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly local>

[Příklad smazání entity pomocí dotazu](/documentation/user/en/get-started/example/delete-entity-by-query.cs)
</SourceCodeTabs>
</LS>

Při mazání hierarchické entity si můžete zvolit, zda ji chcete smazat i se všemi jejími podřízenými entitami:

<LS to="j">
<SourceCodeTabs setup="/documentation/user/en/get-started/example/create-small-dataset.java" local>

[Příklad smazání hierarchické entity](/documentation/user/en/get-started/example/delete-hierarchical-entity.java)
</SourceCodeTabs>
</LS>
<LS to="c">
<SourceCodeTabs setup="/documentation/user/en/get-started/example/create-small-dataset.java" local>

[Příklad smazání hierarchické entity](/documentation/user/en/get-started/example/delete-hierarchical-entity.cs)
</SourceCodeTabs>
</LS>

Pro složitější příklady a vysvětlení viz [kapitolu o write API](../use/api/write-data.md#odstraňování).

</LS>

<LS to="g">

## Smazání libovolné existující entity

Můžete zadat dotaz, který odstraní všechny entity odpovídající dotazu pomocí stejného catalog data API, které
používáte pro vkládání, aktualizaci nebo načítání entit:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly local>

[Příklad smazání entity pomocí dotazu](/documentation/user/en/get-started/example/delete-entity-by-query.graphql)
</SourceCodeTabs>

Pro složitější příklady a vysvětlení viz [kapitolu o write API](../use/api/write-data.md#odstraňování).

</LS>
<LS to="r">

## Smazání libovolné existující entity

Entitu můžete smazat podle jejího primárního klíče:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly local>

[Příklad smazání entity podle PK](/documentation/user/en/get-started/example/delete-entity-by-pk.rest)
</SourceCodeTabs>

Nebo můžete zadat dotaz, který odstraní všechny entity odpovídající dotazu pomocí stejného catalog data API, které
používáte pro vkládání, aktualizaci nebo načítání entit:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly local>

[Příklad smazání entity pomocí dotazu](/documentation/user/en/get-started/example/delete-entity-by-query.rest)
</SourceCodeTabs>

Pro složitější příklady a vysvětlení viz [kapitolu o write API](../use/api/write-data.md#odstraňování).

</LS>

<LS to="e">

Vytváření nového katalogu v jiných API než Java, GraphQL a REST je v přípravě.

</LS>

## Co dál?

Pokud nechcete experimentovat s vlastními daty, [můžete si vyzkoušet náš dataset](query-our-dataset.md).
Můžete se také podrobněji seznámit v následujících kapitolách s tím, jak používat jednotlivé části
našeho [query API](../use/api/query-data.md), [write API](../use/api/write-data.md) nebo [schema API](../use/schema.md).
Také se můžete seznámit se [strukturou dat entity](../use/data-model.md) nebo dalšími aspekty naší databáze.