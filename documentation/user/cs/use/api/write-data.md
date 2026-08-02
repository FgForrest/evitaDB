---
title: Zápis dat
perex: Tento článek obsahuje hlavní principy pro vytváření dat v evitaDB, popis datového API týkajícího se vkládání a mazání entit a související doporučení.
date: '31.10.2023'
author: Ing. Jan Novotný
proofreading: done
preferredLang: java
translated: 'true'
commit: a5770aa82b3fd870e35cecd84d9243fd87cc77e3
---
<LS to="e">

Bohužel v současné době není možné zapisovat data pomocí EvitaQL. Tato rozšíření také nejsou v nejbližší době plánována k implementaci, protože se domníváme, že jsou k dispozici dostatečné možnosti (Java, GraphQL, REST API, gRPC a C#).

</LS>

<LS to="j,g,r,c">

## Indexační režimy

evitaDB předpokládá, že nebude primárním úložištěm vašich dat. Protože evitaDB je relativně nová implementace databáze, je rozumné uchovávat primární data v osvědčené, časem prověřené a spolehlivé technologii, jako je relační databáze. evitaDB vám přináší potřebné funkce s nízkou latencí a optimalizované pro e-commerce jako sekundární rychlý index pro čtení, kde zrcadlíte/transformujete data z vašeho primárního úložiště. Rádi bychom se jednou stali vaším primárním úložištěm dat, ale buďme upřímní – zatím tam nejsme.

Tato úvaha nás vedla k návrhu dvou různých typů ingestování [dat entity](../data-model.md) a odpovídajících stavů katalogu:

- [hromadné indexování](#hromadné-indexování), stav: <LS to="j,g,r">`WARMUP`</LS><LS to="c">`Warmup`</LS>
- [inkrementální indexování](#inkrementální-indexování), stav: <LS to="j,g,r">`ALIVE`</LS><LS to="c">`Alive`</LS>

### Hromadné indexování

Hromadné indexování se používá pro rychlé indexování velkého množství zdrojových dat. Používá se pro počáteční vytvoření katalogu z externích (primárních) úložišť dat. Nepotřebuje podporovat transakce a umožňuje otevřít pouze jednu relaci (jeden vlákno) ze strany klienta. Katalog je v takzvaném stavu <LS to="j,g,r">`WARMUP`</LS><LS to="c">`Warmup`</LS> (<LS to="j,g,r"><SourceClass>evita_api/src/main/java/io/evitadb/api/CatalogState.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Session/CatalogState.cs</SourceClass></LS>). Klient může data zapisovat i dotazovat se na zapsaná data, ale žádný jiný klient nemůže otevřít další relaci, protože by pro něj nemohla být zaručena konzistence dat. Cílem je zde indexovat stovky nebo tisíce entit za sekundu.

Pokud databáze během tohoto počátečního hromadného indexování havaruje, je třeba považovat stav a konzistenci dat za poškozené a celý katalog by měl být zcela vyprázdněn a znovu sestaven od začátku. Protože neexistuje žádný jiný klient než ten, který data zapisuje, můžeme si to dovolit.

Totéž platí pro *jednotlivý* neúspěšný zápis: hromadné indexování **nepodporuje zpětné vrácení na úrovni entity** (rollback), takže pokud operace `upsertEntity` / `deleteEntity` selže v průběhu, může zanechat indexové záznamy entity pouze částečně aplikované. Obnova je odpovědností klienta — buď kompenzovat částečný zápis, nebo (doporučeno) znovu sestavit katalog. Podrobnosti a rozdíly oproti transakční fázi (`ALIVE`) najdete v [Atomicita jednotlivých zápisů](../../deep-dive/bulk-vs-incremental-indexing.md#atomicita-jednotlivých-zápisů).

</LS>

<LS to="j,c">

Každý nově vytvořený katalog začíná ve stavu <LS to="j">`WARMUP`</LS><LS to="c">`Warmup`</LS> a musí být ručně přepnut do *transakčního* režimu provedením:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Ukončení režimu zahřívání](/documentation/user/en/use/api/example/finalization-of-warmup-mode.java)

</SourceCodeTabs>

Metoda <LS to="j">`goLiveAndClose`</LS><LS to="c">`GoLiveAndClose`</LS> nastaví katalog do stavu <LS to="j">`ALIVE`</LS><LS to="c">`Alive`</LS> (transakčního) a uzavře aktuální relaci. Od tohoto okamžiku mohou k tomuto konkrétnímu katalogu paralelně otevírat relace pro čtení i zápis více klientů.

</LS>
<LS to="g,r">

Každý nově vytvořený katalog začíná ve stavu `WARMUP` a musí být ručně přepnut do *transakčního* režimu pomocí <LS to="g">[system API](/documentation/user/en/use/connectors/graphql.md#graphql-api-instances)</LS><LS to="r">[system API](/documentation/user/en/use/connectors/rest.md#rest-api-instances)</LS> provedením:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Ukončení režimu zahřívání](/documentation/user/en/use/api/example/finalization-of-warmup-mode.graphql)

</SourceCodeTabs>

<LS to="g">Mutace `switchCatalogToAliveState`</LS><LS to="r">Endpoint `/catalogs/{catalog-name}` s metodou `PATCH`</LS> nastaví katalog do stavu `ALIVE` (transakčního). Od tohoto okamžiku mohou k tomuto konkrétnímu katalogu paralelně posílat dotazy nebo mutace více klientů.

</LS>

<LS to="j,g,r,c">

### Inkrementální indexování

Režim inkrementálního indexování se používá k udržení aktuálnosti indexu vůči primárnímu úložišti dat během jeho životnosti. Očekáváme, že v primárním úložišti dat bude implementován nějaký proces [zachycení změn dat](https://en.wikipedia.org/wiki/Change_data_capture). Jedním z nejzajímavějších nedávných vývojů v této oblasti je [projekt Debezium](https://debezium.io/), který umožňuje snadné streamování změn z primárních úložišť do sekundárních indexů.

Ve stavu <LS to="j">`ALIVE`</LS><LS to="c">`Alive`</LS> může ke stejnému katalogu číst a zapisovat data více klientů. Každá aktualizace katalogu je zabalena do *transakce*, která splňuje [úroveň izolace snapshot](https://en.wikipedia.org/wiki/Snapshot_isolation). Více informací o práci s transakcemi najdete v [samostatné kapitole](../../deep-dive/transactions.md).

V této fázi je každý zápis entity navíc **atomický sám o sobě**: pokud operace `upsertEntity` / `deleteEntity` selže v průběhu (například kvůli porušení unikátního omezení), pouze částečné změny této entity jsou vráceny zpět a zbytek transakce zůstává platný, takže klient může zachytit chybu a pokračovat v zápisu před potvrzením. Viz [Atomicita jednotlivých mutací entity](../../deep-dive/transactions.md#atomicita-jednotlivých-mutací-entit).

## Charakteristiky modelu

Náš model má několik vlastností, které byste měli mít na paměti a využít je ve svůj prospěch:

### Neměnnost

Všechny modelové třídy jsou **navrženy jako neměnné**. Důvodem je jednoduchost, implicitně správné chování při souběžném přístupu (jinými slovy, entity lze cachovat bez obav z race conditions) a snadné ověřování identity (kde stačí pouze primární klíč a verze, abychom mohli tvrdit, že dva datové objekty stejného typu jsou identické).

</LS>

<LS to="j,c">

Všechny modelové třídy jsou popsány pomocí rozhraní a neměl by být důvod používat nebo instancovat přímo třídy. Rozhraní mají tuto strukturu:

<dl>
    <dt>ModelNameContract</dt>
    <dd>obsahuje všechny metody pro čtení, představuje základní kontrakt pro model</dd>
    <dt>ModelNameEditor</dt>
    <dd>obsahuje všechny metody pro úpravy</dd>
    <dt>ModelNameBuilder</dt>
    <dd>kombinuje rozhraní **Contract** a **Editor** a slouží k vytvoření instance</dd>
</dl>

Při vytváření nové entity pomocí evitaDB API získáte builder, a můžete ihned začít nastavovat data pro entitu a poté ji uložit do databáze:

<SourceCodeTabs setup="/documentation/user/en/use/api/example/finalization-of-warmup-mode.java,/documentation/user/en/use/api/example/open-session-manually.java" langSpecificTabOnly local>

[Vytvoření nové entity vrací builder](/documentation/user/en/use/api/example/create-new-entity-shortened.java)

</SourceCodeTabs>

Při čtení existující entity z katalogu získáte pouze pro čtení <LS to="java>"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/SealedEntity.java</SourceClass></LS><LS to="csharp>"><SourceClass>EvitaDB.Client/Models/Data/ISealedEntity.cs</SourceClass></LS>, což je v podstatě kontraktové rozhraní s několika metodami umožňujícími převést ho na builder, který lze použít pro aktualizaci dat:

<LS to="j,c">
<SourceCodeTabs setup="/documentation/user/en/use/api/example/finalization-of-warmup-mode.java,/documentation/user/en/get-started/example/create-small-dataset.java,/documentation/user/en/use/api/example/open-session-manually.java" langSpecificTabOnly local>

[Získání existující entity vrací sealed entity](/documentation/user/en/use/api/example/update-existing-entity-shortened.java)

</SourceCodeTabs>
</LS>

</LS>

<LS to="g,r">

V <LS to="g">GraphQL</LS><LS to="r">REST</LS> API je neměnnost implicitní díky návrhu. Můžete sice v klientské aplikaci upravit vrácené objekty entit, ale tyto změny nelze propagovat na server evitaDB, proto doporučujeme, aby byl i váš klientský model neměnný (inspirujte se Java API). Jediný způsob, jak upravit data, je použít <LS to="g">[catalog data API](/documentation/user/en/use/connectors/graphql.md#graphql-api-instances)</LS><LS to="r">[catalog API](/documentation/user/en/use/connectors/rest.md#rest-api-instances)</LS> a ručně posílat evitaDB mutace s jednotlivými změnami pomocí <LS to="g">`updateCollectionName` GraphQL mutací specifických pro</LS><LS to="r">REST endpointů pro úpravu dat</LS> vaší vybrané [entity kolekce](/documentation/user/en/use/data-model.md#collection).

</LS>

<LS to="j,g,r,c">

### Verzování

Všechny modelové třídy jsou verzované — jinými slovy, když je instance modelu upravena, číslo verze nové instance vytvořené z upraveného stavu se zvýší o jedna.

</LS>

<LS to="j,c">

Informace o verzi jsou dostupné nejen na úrovni <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/EntityContract.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/Data/IEntity.cs</SourceClass></LS>, ale také na jemnějších úrovních (například <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/AttributesContract.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/Data/IAttributes.cs</SourceClass></LS>, <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/ReferenceContract.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/Data/IReference.cs</SourceClass></LS> nebo <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/AssociatedDataContract.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/Data/IAssociatedData.cs</SourceClass></LS>). Všechny modelové třídy, které podporují verzování, implementují rozhraní <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/Versioned.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/Data/IVersioned.cs</SourceClass></LS>.

</LS>

<LS to="g,r">

Informace o verzi jsou dostupné na úrovni entity.

</LS>

<LS to="j,g,r,c">

Informace o verzi slouží dvěma účelům:

1. **rychlé hashování & kontrola rovnosti:** pouze informace o primaryKey + verzi stačí k určení, zda jsou dvě instance stejné, a to i v situaci, kdy byla z persistentního úložiště načtena pouze [část entity](query-data.md#líné-načítání-obohacení)
2. **optimistické zamykání:** pokud dojde ke konkurenční aktualizaci stejné entity, můžeme konflikt automaticky vyřešit, pokud se samotné změny nepřekrývají.

</LS>

<LS to="j,c">

<Note type="info">
Protože je entita *neměnná* a *verzovaná*, výchozí implementace metod `hashCode` a `equals` bere v úvahu tyto tři komponenty:

1. typ entity
2. primární klíč
3. verze

Pokud potřebujete důkladné porovnání, které porovná všechna data modelu, musíte použít metodu <LS to="j">`differsFrom`</LS><LS to="c">`DiffersFrom`</LS> definovanou v rozhraní <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/ContentComparator.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/Data/IContentComparator.cs</SourceClass></LS> a implementovanou třídou <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/EntityContract.java</SourceClass></LS><LS to="c"><SourceClass><SourceClass>EvitaDB.Client/Models/Data/IEntity.cs</SourceClass></SourceClass></LS>.

</Note>

</LS>

<LS to="j,g,r,c">

## Relace & transakce

</LS>

<LS to="j,c">

Komunikace s instancí evitaDB vždy probíhá prostřednictvím rozhraní <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/EvitaClientSession.cs</SourceClass></LS>. Relace je jednovláknový komunikační kanál identifikovaný unikátním [náhodným UUID](https://en.wikipedia.org/wiki/Universally_unique_identifier).

Ve webovém prostředí je dobré mít jednu relaci na požadavek, při dávkovém zpracování je doporučeno udržovat jednu relaci pro celou dávku.

<Note type="warning">
Pro úsporu prostředků server automaticky uzavírá relace po určité době nečinnosti. Interval je ve výchozím nastavení nastaven na `60 sekund`, ale [lze jej změnit](https://evitadb.io/documentation/operate/configure#server-configuration) na jinou hodnotu. Nečinnost znamená, že na rozhraní <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/EvitaClientSession.cs</SourceClass></LS> není zaznamenána žádná aktivita. Pokud potřebujete relaci uměle udržet aktivní, musíte pravidelně volat některou metodu bez vedlejších účinků na rozhraní relace, například:

<dl>
    <dt>`isActive`</dt>
    <dd>V případě použití embedded evitaDB.</dd>
    <dt>`getEntityCollectionSize`</dt>
    <dd>
    V případě vzdáleného použití evitaDB. V tomto případě je opravdu nutné zavolat metodu, která vyvolá síťovou komunikaci. Mnoho metod v <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/EvitaClientSession.cs</SourceClass></LS> vrací pouze lokálně cachované výsledky, aby se zabránilo drahým a zbytečným síťovým voláním.
    </dd>
</dl>
</Note>

<LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/TransactionContract.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/EvitaClientTransaction.cs</SourceClass></LS> je obálka pro "jednotku práce" s evitaDB. Transakce existuje v rámci relace a je zaručeno, že má [úroveň izolace snapshot](https://en.wikipedia.org/wiki/Snapshot_isolation) pro čtení. Změny v transakci jsou vždy izolované od ostatních transakcí a stávají se viditelnými až po potvrzení transakce. Pokud je transakce označena jako *pouze pro rollback*, všechny změny budou při uzavření transakce zahozeny a nikdy se nedostanou do sdíleného stavu databáze. V relaci může být aktivní vždy pouze jedna transakce, ale během životnosti relace může být více po sobě jdoucích transakcí.

</LS>

<LS to="g,r">

Komunikace s instancí evitaDB pomocí <LS to="g">GraphQL</LS><LS to="r">REST</LS> API vždy používá nějaký typ relace. V případě <LS to="g">GraphQL</LS><LS to="r">REST</LS> API je relace komunikační kanál na úrovni požadavku, který je používán na pozadí.

Transakce je obálka pro "jednotku práce" s evitaDB. V <LS to="g">GraphQL</LS><LS to="r">REST</LS> API existuje transakce po dobu trvání relace, přesněji řečeno po dobu požadavku na <LS to="g">GraphQL</LS><LS to="r">REST</LS> API, a je zaručeno, že má [úroveň izolace snapshot](https://en.wikipedia.org/wiki/Snapshot_isolation) pro čtení. Změny v transakci jsou vždy izolované od ostatních transakcí a stávají se viditelnými až po potvrzení transakce, tj. po úspěšném zpracování požadavku na <LS to="g">GraphQL</LS><LS to="r">REST</LS> API. Pokud požadavek na <LS to="g">GraphQL</LS><LS to="r">REST</LS> API skončí chybou, transakce je automaticky vrácena zpět.

</LS>

<LS to="j,g,r,c">

### Relace pouze pro čtení vs. relace pro čtení a zápis

evitaDB rozlišuje dva typy relací:

</LS>

<LS to="j,c">

<dl>
    <dt>pouze pro čtení (výchozí)</dt>
	<dd>Relace pouze pro čtení se otevírají voláním metody <LS to="j">`queryCatalog`</LS><LS to="c">`QueryCatalog`</LS>. V relaci pouze pro čtení nejsou povoleny žádné zápisové operace. To také umožňuje evitaDB optimalizovat své chování při práci s databází.</dd>
    <dt>pro čtení a zápis</dt>
    <dd>Relace pro čtení a zápis se otevírají voláním metody <LS to="j">`updateCatalog`</LS><LS to="c">`UpdateCatalog`</LS></dd>
</dl>

</LS>
<LS to="g">

<dl>
    <dt>pouze pro čtení</dt>
    <dd>Relace pouze pro čtení se otevírají voláním GraphQL dotazů, tj. `getCollectionName`, `listCollectionName`, `queryCollectionName` atd. V relaci pouze pro čtení nejsou povoleny žádné zápisové operace, což umožňuje evitaDB optimalizovat své chování při práci s databází.</dd>
    <dt>pro čtení a zápis</dt>
    <dd>Relace pro čtení a zápis se otevírají voláním GraphQL mutací, tj. `upsertCollectionName`, `deleteCollectionName` atd.</dd>
</dl>

</LS>
<LS to="r">

<dl>
    <dt>pouze pro čtení</dt>
    <dd>Relace pouze pro čtení se otevírají voláním endpointů, které pouze vracejí data, typicky endpointy končící na `/get`, `/list`, `/query` atd. V relaci pouze pro čtení nejsou povoleny žádné zápisové operace, což umožňuje evitaDB optimalizovat své chování při práci s databází.</dd>
    <dt>pro čtení a zápis</dt>
    <dd>Relace pro čtení a zápis se otevírají voláním endpointů, které upravují jakákoli data.</dd>
</dl>

</LS>

<LS to="j,g,r,c">

Do budoucna bude možné relace pouze pro čtení distribuovat na více uzlů pro čtení, zatímco relace pro čtení a zápis musí komunikovat s hlavním uzlem.

</LS>

<LS to="j,c">

#### Nebezpečný životní cyklus relace

Doporučujeme otevírat relace pomocí metod <LS to="j">`queryCatalog` / `updateCatalog`</LS><LS to="c">`QueryCatalog` / `UpdateCatalog`</LS>, které přijímají lambda funkci pro provedení vaší business logiky. Tímto způsobem může evitaDB bezpečně spravovat životní cyklus *relací* a *transakcí*. Pokud je použita metoda `updateCatalog`, transakce se automaticky otevře při startu relace a uzavře na konci lambda funkce. Pokud během provádění lamdy dojde k výjimce a není zachycena uvnitř lamdy (tj. je znovu vyhozena mimo rozsah lamdy), transakce je automaticky vrácena zpět. Pokud lambda skončí úspěšně, transakce je automaticky potvrzena.

Tento přístup není vždy přijatelný — například pokud vaše aplikace potřebuje být integrována do existujícího frameworku, který poskytuje pouze callback metody životního cyklu, není možné "obalit" celou business logiku do lambda funkce.

Proto existuje alternativa – ne tak bezpečný – přístup ke správě relací a transakcí:

<SourceCodeTabs setup="/documentation/user/en/use/api/example/finalization-of-warmup-mode.java" langSpecificTabOnly local>

[Ručně řízená relace a transakce](/documentation/user/en/use/api/example/manual-transaction-management.java)

</SourceCodeTabs>

<Note type="warning">
Pokud používáte ruční správu *relací*, musíte zajistit, že pro každé otevření rozsahu existuje odpovídající uzavření (i když během volání vaší business logiky dojde k výjimce).
</Note>

Obě rozhraní <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/EvitaClientSession.cs</SourceClass></LS> a <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/TransactionContract.java</SourceClass> implementují Java `Autocloseable`</LS><LS to="c"><SourceClass>EvitaDB.Client/EvitaClientTransaction.cs</SourceClass> implementuje C# `IDisposable`</LS> rozhraní, takže je můžete použít tímto způsobem:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Využití chování Autocloseable](/documentation/user/en/use/api/example/autocloseable-transaction-management.java)

</SourceCodeTabs>

Tento přístup je bezpečný, ale má stejnou nevýhodu jako použití metod <LS to="j">`queryCatalog` / `updateCatalog`</LS><LS to="c">`QueryCatalog` / `UpdateCatalog`</LS> – musíte mít veškerou business logiku proveditelnou v jednom bloku.

#### Relace pro suchý běh (dry-run)

Pro účely testování existuje speciální příznak, který lze použít při otevírání nové relace – příznak **dry run**:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Otevření relace pro suchý běh](/documentation/user/en/use/api/example/dry-run-session.java)

</SourceCodeTabs>

V této relaci budou všechny transakce automaticky mít při otevření nastaven příznak *rollback*, aniž by bylo nutné jej nastavovat ručně. To výrazně zjednodušuje [pattern rollback transakce při ukončení testu](http://xunitpatterns.com/Transaction%20Rollback%20Teardown.html) při implementaci vašich testů, nebo se může hodit, pokud chcete zajistit, že změny nebudou v konkrétní relaci potvrzeny a nemáte snadný přístup k místům, kde se transakce otevírá.

</LS>

<LS to="j,g,r,c">

### Upsert

</LS>

<LS to="j,c">

Očekává se, že většina instancí entit bude vytvářena službami evitaDB – například <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/EvitaClientSession.cs</SourceClass></LS>. Každopádně existuje také [možnost jejich přímého vytvoření](#vytváření-entit-v-odděleném-režimu).

Obvykle bude vytvoření entity vypadat takto:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-catalog-with-schema.java" langSpecificTabOnly local>

[Příklad vytvoření nové entity](/documentation/user/en/use/api/example/create-new-entity.java)

</SourceCodeTabs>

Tímto způsobem lze vytvořenou entitu ihned ověřit vůči schématu. Tato forma kódu je zhuštěná verze a může být rozdělena do několika částí, což odhalí použitý "builder".

Pokud potřebujete upravit existující entitu, nejprve ji načtete ze serveru, otevřete ji pro zápis (což ji převede na builder), upravíte ji a nakonec shromáždíte změny a odešlete je na server.

<SourceCodeTabs setup="/documentation/user/en/use/api/example/finalization-of-warmup-mode.java,/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly local>

[Příklad aktualizace existující entity](/documentation/user/en/use/api/example/update-existing-entity.java)

</SourceCodeTabs>

<Note type="info">

Metoda <LS to="j">`upsertVia`</LS><LS to="c">`UpsertVia`</LS> je zkratka pro volání <LS to="j">`session.upsertEntity(builder.buildChangeSet())`</LS><LS to="c">`session.UpsertEntity(builder.BuildChangeSet())`</LS>. Pokud se podíváte na <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/BuilderContract.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/Data/IBuilder.cs</SourceClass></LS>, uvidíte, že na něm můžete volat buď:

<LS to="j">

<dl>
    <dt>`buildChangeSet`</dt>
    <dd>Vytvoří proud *mutací*, které reprezentují změny, které jste provedli na neměnném objektu.</dd>
    <dt>`toInstance`</dt>
    <dd>Vytvoří novou verzi neměnného objektu entity se všemi aplikovanými změnami. To vám umožní vytvořit novou instanci objektu lokálně bez odeslání změn na server. Když stejnou instanci znovu načtete ze serveru, uvidíte, že žádné změny nebyly na databázovou entitu aplikovány.</dd>
</dl>

</LS>
<LS to="c">

<dl>
	<dt>`BuildChangeSet`</dt>
	<dd>Vytvoří proud *mutací*, které reprezentují změny, které jste provedli na neměnném objektu.</dd>
	<dt>`ToInstance`</dt>
	<dd>Vytvoří novou verzi neměnného objektu entity se všemi aplikovanými změnami. To vám umožní vytvořit novou instanci objektu lokálně bez odeslání změn na server. Když stejnou instanci znovu načtete ze serveru, uvidíte, že žádné změny nebyly na databázovou entitu aplikovány.</dd>
</dl>

</LS>

</Note>

<LS to="j">

<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/EntityMutation.java</SourceClass> nebo <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/BuilderContract.java</SourceClass> lze předat metodě `upsert` rozhraní <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass>, která vrací <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/structure/EntityReference.java</SourceClass> obsahující pouze typ entity a (případně přidělený) primární klíč. Můžete také použít metodu `upsertAndFetchEntity`, která vloží nebo vytvoří entitu a vrátí její tělo ve formě a velikosti, kterou určíte v argumentu `require`.

</LS>
<LS to="c">
<SourceClass>EvitaDB.Client/Models/Data/Mutations/IEntityMutation.cs</SourceClass> nebo <SourceClass>EvitaDB.Client/Models/Data/IBuilder.cs</SourceClass> lze předat metodě `Upsert` rozhraní <SourceClass>EvitaDB.Client/EvitaClientSession.cs</SourceClass>, která vrací <SourceClass>EvitaDB.Client/Models/Data/Structure/EntityReference.cs</SourceClass> obsahující pouze typ entity a (případně přidělený) primární klíč. Můžete také použít metodu `UpsertAndFetchEntity`, která vloží nebo vytvoří entitu a vrátí její tělo ve formě a velikosti, kterou určíte v argumentu `Require`.
</LS>

</LS>

<LS to="j,c">

#### Vytváření entit v odděleném režimu

Instance entit lze vytvářet i v případě, že není k dispozici žádná instance evitaDB:

<SourceCodeTabs langSpecificTabOnly>
[Příklad oddělené (detached) instance](/documentation/user/en/use/api/example/detached-instantiation.java)
</SourceCodeTabs>

Ačkoli tuto možnost pravděpodobně využijete pouze při psaní testovacích případů, stále vám umožňuje vytvořit proud mutací, které lze odeslat a zpracovat serverem evitaDB, jakmile se vám podaří získat jeho instanci.

Existuje analogický builder, který přijímá existující entitu a sleduje provedené změny.

<SourceCodeTabs setup="/documentation/user/en/use/api/example/detached-existing-entity-preparation.java" langSpecificTabOnly>

[Příklad oddělené existující entity](/documentation/user/en/use/api/example/detached-existing-entity-instantiation.java)

</SourceCodeTabs>

</LS>

<LS to="g,r">

V <LS to="g">GraphQL</LS><LS to="r">REST</LS> API není možné odeslat na server celý objekt entity k uložení. Místo toho odesíláte kolekci mutací, které přidávají, mění nebo odstraňují jednotlivá data z entity (nové nebo existující), podobně jako je schéma definováno v <LS to="g">GraphQL</LS><LS to="r">REST</LS> API.

<Note type="question">

<NoteTitle toggles="true">

##### Proč používáme přístup s mutacemi pro definici entity?
</NoteTitle>

Víme, že tento přístup není příliš uživatelsky přívětivý. Myšlenkou tohoto přístupu je však poskytnout jednoduchý a univerzální způsob, jak programově sestavit entitu s ohledem na transakce (ve skutečnosti takto funguje evitaDB interně, takže kolekce mutací je předána přímo enginu na serveru). Očekává se, že vývojáři používající <LS to="g">GraphQL</LS><LS to="r">REST</LS> API vytvoří knihovnu např. s entity buildery, které budou generovat kolekci mutací pro definici entity (inspirujte se Java API).

</Note>

<LS to="g">

Novou entitu můžete vytvořit nebo aktualizovat existující pomocí [catalog data API](/documentation/user/en/use/connectors/graphql.md#graphql-api-instances) na adrese `https://your-server:5555/gql/evita`. Toto API obsahuje GraphQL mutace `upsertCollectionName` pro každou [kolekci entit](/documentation/user/en/use/data-model.md#collection), které jsou přizpůsobeny schématům kolekcí ([schemas](/documentation/user/en/use/schema.md#entity)). Tyto mutace přijímají kolekci evitaDB mutací, které definují změny, které mají být na entitě aplikovány. V jednom kroku můžete také získat entitu se změnami, pokud definujete návratová data.

</LS>
<LS to="r">

Novou entitu můžete vytvořit nebo aktualizovat existující pomocí [catalog API](/documentation/user/en/use/connectors/rest.md#rest-api-instances) na endpointu kolekce, například `https://your-server:5555/test/evita/product` s HTTP metodou `PUT`. Tyto endpointy jsou přizpůsobeny schématům kolekcí ([schemas](/documentation/user/en/use/schema.md#entity)). Endpointy přijímají kolekci evitaDB mutací, které definují změny, které mají být na entitě aplikovány. V jednom kroku můžete také získat entitu se změnami, pokud definujete požadavky (requirements).

</LS>

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-catalog-with-schema.java" langSpecificTabOnly local>

[Příklad vytvoření nové entity](/documentation/user/en/use/api/example/create-new-entity.graphql)

</SourceCodeTabs>

Protože tyto <LS to="g">GraphQL mutace</LS><LS to="r">endpointy</LS> slouží také k aktualizaci existujících entit, evitaDB automaticky buď vytvoří novou entitu se zadanými mutacemi (a případně primárním klíčem), nebo aktualizuje existující, pokud je zadán primární klíč existující entity. Chování mutace můžete dále přizpůsobit pomocí argumentu `entityExistence`.

<SourceCodeTabs setup="/documentation/user/en/use/api/example/finalization-of-warmup-mode.java,/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly local>

[Příklad aktualizace existující entity](/documentation/user/en/use/api/example/update-existing-entity.graphql)

</SourceCodeTabs>

</LS>

<LS to="j,c">

#### Přímá manipulace s entitou pomocí mutací

Kromě použití builderů můžete také ručně vytvořit seznam mutací a odeslat je na server:

<SourceCodeTabs setup="/documentation/user/en/use/api/example/finalization-of-warmup-mode.java,/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly local>

[Ručně řízené mutace](/documentation/user/en/use/api/example/manual-mutation-handling.java)

</SourceCodeTabs>

Entity builder můžete také inicializovat pomocí seznamu mutací:

<SourceCodeTabs setup="/documentation/user/en/use/api/example/detached-existing-entity-preparation.java" langSpecificTabOnly local>

[Ručně řízené mutace](/documentation/user/en/use/api/example/builder-bootstrap-using-mutations.java)

</SourceCodeTabs>

</LS>

<LS to="j,g,r,c">

### Odstraňování

</LS>

<LS to="j,r,c">

Nejjednodušší způsob, jak odstranit entitu, je podle *primárního klíče*. Pokud však potřebujete odstranit více entit najednou, musíte definovat dotaz, který odpovídá všem entitám k odstranění:

</LS>
<LS to="g">

Pro odstranění jedné nebo více entit musíte definovat dotaz, který odpovídá všem entitám k odstranění:

</LS>

<LS to="j,g,r,c">

<SourceCodeTabs setup="/documentation/user/en/use/api/example/finalization-of-warmup-mode.java,/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly local>

[Odstranění všech entit, jejichž název začíná na `A`](/documentation/user/en/use/api/example/delete-entities-by-query.java)

</SourceCodeTabs>

</LS>

<LS to="j,c">

Metoda <LS to="j">`deleteEntities`</LS><LS to="c">`DeleteEntities`</LS> vrací počet odstraněných entit. Pokud chcete vrátit těla odstraněných entit, můžete použít alternativní metodu <LS to="j">`deleteEntitiesAndReturnBodies`</LS><LS to="c">`DeleteEntitiesAndReturnBodies`</LS>.

</LS>

<LS to="g,r">

<LS to="g">Mutace pro odstranění</LS><LS to="r">Oba endpointy pro mazání</LS> mohou vracet těla entit, takže si můžete definovat návratovou strukturu dat podle potřeby, stejně jako při běžném načítání entit.

</LS>

<LS to="j,r,g,c">

<Note type="warning">

evitaDB nemusí odstranit všechny entity odpovídající filtru v dotazu. Odstranění entit podléhá logice <LS to="j,r">podmínek `require` [`page` nebo `strip`](../../query/requirements/paging.md)</LS><LS to="c">podmínek `Require` [`Page` nebo `Strip`](../../query/requirements/paging.md)</LS><LS to="g">p paginačních argumentů [`offset` a `limit`](../../query/requirements/paging.md)</LS>. I když je zcela vynecháte, implicitně se použije stránkování <LS to="j,r">(`page(1, 20)`)</LS><LS to="c">(`Page(1, 20)`)</LS><LS to="g">(`offset: 1, limit: 20`)</LS>. Pokud je počet odstraněných entit roven velikosti definovaného stránkování, měli byste příkaz pro odstranění zopakovat.

Hromadné odstraňování entit je lepší provádět v několika transakčních kolech než v jedné velké transakci<LS to="g,r">, tj. v několika požadavcích</LS>. Je to minimálně dobrá praxe, protože velké a dlouhotrvající transakce zvyšují pravděpodobnost konfliktů, které vedou k rollbackům ostatních transakcí.

</Note>

</LS>

<LS to="j,c">

Pokud odstraňujete hierarchickou entitu a potřebujete odstranit nejen samotnou entitu, ale i celou její podstrom, můžete využít metodu <LS to="j">`deleteEntityAndItsHierarchy`</LS><LS to="c">`DeleteEntityAndItsHierarchy`</LS>. Ve výchozím nastavení metoda vrací počet odstraněných entit, ale alternativně může vrátit tělo odstraněné kořenové entity ve velikosti a formě, kterou určíte v jejím argumentu <LS to="j">`require`</LS><LS to="c">`Require`</LS>. Pokud odstraníte pouze kořenový uzel bez jeho potomků, potomci se stanou [sirotky](../schema.md#sirotčí-uzly-v-hierarchii) a budete je muset znovu připojit k jinému existujícímu rodiči.

</LS>

<LS to="j,g,r,c">

<Note type="question">

<NoteTitle toggles="true">

##### Jak evitaDB interně zpracovává odstraňování?
</NoteTitle>

Žádná data nejsou po vytvoření a uložení skutečně odstraněna. Pokud odstraníte referenci/atribut/cokoli, zůstává v entitě a je pouze označena jako `dropped`. Viz implementace rozhraní <LS to="j,g,r"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/Droppable.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/Data/IDroppable.cs</SourceClass></LS>.

Existuje několik důvodů pro toto rozhodnutí:

1. je dobré mít poslední známou verzi dat k dispozici, když se něco pokazí, abychom se mohli vrátit do předchozího stavu.
2. umožňuje nám sledovat změny v entitě během jejího životního cyklu pro účely ladění
3. je to v souladu s naším *pouze přidávacím* přístupem ke storage, kde musíme v případě odstranění entity nebo jiného objektu zapisovat [tombstones](https://en.wikipedia.org/wiki/Tombstone_(data_store))

</Note>

</LS>

<LS to="j">

## Vlastní kontrakty

Podobně jako při [dotazování na data pomocí vlastních kontraktů](query-data.md#vlastní-kontrakty) můžete také vytvářet nové entity a upravovat existující pomocí vlastních kontraktů. To vám umožňuje zcela obejít práci s interním modelem evitaDB a držet se vlastního – doménově specifického – modelu. Při modelování vašich read/write kontraktů doporučujeme držet se [principu sealed/open](../connectors/java.md#doporučení-pro-modelování-dat).

Váš zápisový kontrakt pravděpodobně rozšíří read kontrakty pomocí anotací popsaných v [schema API](schema-api.md#anotace-pro-řízení-schématu) a/nebo [query data API](query-data.md#vlastní-kontrakty). Pokud dodržujete konvenci pojmenování JavaBeans, nemusíte používat anotace na zápisových metodách, ale pokud chcete použít jiná jména nebo upřesnit svůj zápisový kontrakt, stačí použít [anotace query data](query-data.md#vlastní-kontrakty) na zápisových metodách. V některých případech můžete chtít použít následující dodatečné anotace:

<dl>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/CreateWhenMissing.java</SourceClass></dt>
    <dd>
        Tato anotace může být použita na metodách přijímajících typ [Consumer](/https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/function/Consumer.html)
        nebo na metodách, které vracejí/přijímají váš vlastní kontrakt. Když je metoda zavolána, automatická implementační logika vytvoří novou instanci tohoto kontraktu, se kterou můžete pracovat. Nová instance je uložena spolu s entitou, která byla zodpovědná za její vytvoření (podrobnosti viz následující odstavce).
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/RemoveWhenExists.java</SourceClass></dt>
    <dd>
        Tato anotace může být použita na metodách a spustí odstranění konkrétních dat entity – atributu, asociovaných dat, reference na rodiče, reference na entitu nebo ceny. Odstranění ovlivní pouze samotnou entitu, nikdy cílovou entitu. Fyzické odstranění je provedeno pouze tehdy, když je samotná entita upsertována do databáze.
    </dd>
</dl>

Zápisové metody mohou vracet několik typů (v některých případech je podporovaný seznam ještě delší – tyto případy jsou popsány v příslušných sekcích):

- `void` – metoda provede úpravu a nevrací žádnou hodnotu
- `selfType` – metoda provede úpravu a vrací referenci na samotný kontrakt, což umožňuje řetězit více zápisových volání dohromady ([builder pattern](https://blogs.oracle.com/javamagazine/post/exploring-joshua-blochs-builder-design-pattern-in-java))

V následujících sekcích podrobně popíšeme chování automatické implementační logiky a uvedeme příklady:

<Note type="info">

Příklady obsahují pouze definice rozhraní/tříd, protože Java records jsou pouze pro čtení. Příklady popisují read/write kontrakt ve stejné třídě, což je jednodušší přístup, ale není zcela bezpečný z hlediska paralelního přístupu k datům. Pokud chcete dodržet doporučený [princip sealed/open](../connectors/java.md#doporučení-pro-modelování-dat), měli byste deklarovat `extends SealedEntity<MyEntity, MyEntityEditor>` v kontraktu pro čtení a `extends InstanceEditor<MyEntity>` v kontraktu pro zápis.

</Note>

<Note type="warning">

Když vytváříte nové (neexistující) entity pomocí metod anotovaných `@CreateWhenMissing`, tyto entity jsou drženy v lokální paměti a jejich uložení je odloženo, dokud entita, která je vytvořila, není uložena pomocí metody `upsertDeeply`. Pokud tuto metodu nezavoláte, nebo pokud zavoláte pouze jednoduchou metodu `upsert`, vytvořené entity a reference na ně budou ztraceny. Možná je také budete chtít uložit samostatně nebo před hlavní entitou, která je vytvořila. V tomto případě můžete zavolat metodu `upsert` přímo na nich.

API vám umožňuje vytvořit řetězec závislých entit do nekonečné hloubky a logika `upsertDeeply` / `upsert` bude fungovat správně na všech úrovních. Pokud vytvoříte entitu `A`, ve které jste vytvořili referenci na entitu `B`, ve které jste vytvořili další referenci na entitu `C`, metoda `upsertDeeply` zavolaná na entitě `A` uloží všechny tři entity ve správném pořadí (`C`, `B`, `A`). Pokud zavoláte metodu `upsertDeeply` na entitě `B`, uloží pouze podřízené entity ve správném pořadí (`C`, `B`). Můžete také ručně zavolat metodu `upsert` na entitě `C`, poté na `B` a nakonec na `A`. Pokud však uložíte entitu `A` bez předchozího uložení entit `B` a `C`, reference mezi `A` a `B` bude ztracena. Stále však můžete zavolat `upsertDeeply` na entitě `B`, což zachová referenci mezi `B` a `C`.

</Note>

### Primární klíč

Primární klíč může být přiřazen evitaDB, ale může být také nastaven zvenčí. Aby bylo možné nastavit primární klíč, musíte deklarovat metodu přijímající číselný datový typ (obvykle
[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)) a anotovat ji anotací `@PrimaryKey` nebo `@PrimaryKeyRef`:

<SourceAlternativeTabs requires="documentation/user/en/use/api/example/primary-key-read-interface.java" variants="interface|class">

[Příklad rozhraní s modifikátorem primárního klíče](/documentation/user/en/use/api/example/primary-key-write-interface.java)

</SourceAlternativeTabs>

### Atributy

Pro nastavení atributu entity nebo reference musíte použít odpovídající datový typ a anotovat jej
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Attribute.java</SourceClass>
nebo <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/AttributeRef.java</SourceClass>
anotací, případně mít odpovídající getter (nebo pole) s touto anotací ve stejné třídě.

Pokud atribut představuje vícenásobný typ (pole), můžete jej také zabalit do [Collection](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Collection.html)
(nebo jeho specializací [List](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/List.html)
nebo [Set](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Set.html)), nebo jej předat jako jednoduché pole hodnot. Pravidla platí jak pro atributy entity, tak pro atributy reference:

<SourceAlternativeTabs requires="documentation/user/en/use/api/example/attribute-read-interface.java" variants="interface|class">

[Příklad rozhraní s modifikátorem atributu](/documentation/user/en/use/api/example/attribute-write-interface.java)

</SourceAlternativeTabs>

<Note type="info">

Datové typy Java enum jsou automaticky převáděny na stringový datový typ evitaDB pomocí metody `name()` a zpět pomocí metody `valueOf()`.

</Note>

### Asociovaná data

Pro nastavení asociovaných dat entity musíte použít odpovídající datový typ a anotovat jej
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/AssociatedData.java</SourceClass>
nebo <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/AssociatedDataRef.java</SourceClass>
anotací, případně mít odpovídající getter (nebo pole) s touto anotací ve stejné třídě.

Pokud asociovaná data představují vícenásobný typ (pole), můžete je také zabalit do [Collection](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Collection.html)
(nebo jeho specializací [List](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/List.html)
nebo [Set](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Set.html)), nebo je předat jako jednoduché pole hodnot.

<SourceAlternativeTabs requires="documentation/user/en/use/api/example/associated-data-read-interface.java" variants="interface|class">

[Příklad rozhraní s modifikátorem asociovaných dat](/documentation/user/en/use/api/example/associated-data-write-interface.java)

</SourceAlternativeTabs>

Pokud metoda přijímá ["nepodporovaný datový typ"](../data-types.md#jednoduché-datové-typy), evitaDB automaticky převádí data na ["komplexní datový typ"](../data-types.md#komplexní-datové-typy) pomocí [zdokumentovaných pravidel deserializace](../data-types.md#deserializace).

### Ceny

Pro nastavení cen entity můžete pracovat s datovým typem
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceContract.java</SourceClass>
nebo předat všechna potřebná data v parametrech metody a anotovat metody anotací
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Price.java</SourceClass>.
Můžete nastavit (vytvořit nebo aktualizovat) jednu cenu podle jejího business klíče, který se skládá z:

- **`priceId`** – číselný datový typ s externím identifikátorem ceny
- **`currency`** – [Currency](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Currency.html) nebo [string](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html) datový typ akceptující 3písmenný měnový ISO kód
- **`priceList`** – [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html) datový typ s názvem ceníku

nebo můžete nastavit všechny ceny pomocí metody, která přijímá pole, [Collection](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Collection.html), [List](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/List.html) nebo
[Set](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Set.html) parametr se všemi cenami entity.

<SourceAlternativeTabs requires="documentation/user/en/use/api/example/price-read-interface.java" variants="interface|class">

[Příklad rozhraní s modifikátorem ceny](/documentation/user/en/use/api/example/price-write-interface.java)

</SourceAlternativeTabs>

### Hierarchie

Pro nastavení informací o umístění entity v hierarchii (tj. jejího rodiče) musíte použít buď číselný datový typ, vlastní typ rozhraní, <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/SealedEntity.java</SourceClass>
nebo <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/structure/EntityReference.java</SourceClass>
datový typ a anotovat jej anotací <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/ParentEntity.java</SourceClass>
nebo mít odpovídající getter (nebo pole) s touto anotací ve stejné třídě.

<SourceAlternativeTabs requires="documentation/user/en/use/api/example/parent-read-interface.java" variants="interface|class">

[Příklad rozhraní s modifikátorem rodiče](/documentation/user/en/use/api/example/parent-write-interface.java)

</SourceAlternativeTabs>

Pokud nastavíte hodnotu na `NULL`, entita se stane kořenovou entitou.

### Reference

Pro nastavení referencí entity musíte použít buď číselný datový typ, vlastní typ rozhraní,
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/EntityReferenceContract.java</SourceClass>
nebo <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/ReferenceContract.java</SourceClass>
datový typ a anotovat jej anotací <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Reference.java</SourceClass>
nebo <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/ReferenceRef.java</SourceClass>
anotací, případně mít odpovídající getter (nebo pole) s touto anotací ve stejné třídě.

Pokud má reference kardinalitu `ZERO_OR_MORE` nebo `ONE_OR_MORE`, můžete ji také zabalit do [Collection](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Collection.html)
(nebo jeho specializací [List](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/List.html)
nebo [Set](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Set.html)), nebo ji předat jako jednoduché pole pro přepsání všech hodnot najednou. Pokud má reference kardinalitu `ZERO_OR_ONE` a předáte hodnotu `NULL`, reference je automaticky odstraněna.

<SourceAlternativeTabs requires="documentation/user/en/use/api/example/reference-read-interface.java" variants="interface|class">

[Příklad rozhraní s modifikátorem reference](/documentation/user/en/use/api/example/reference-write-interface.java)

</SourceAlternativeTabs>

</LS>