---
title: Datový model
perex: Tento článek popisuje strukturu databázové entity (alternativu k záznamu v relační databázi nebo dokumentu v některých NoSQL databázích). Porozumění struktuře entity je zásadní pro práci s evitaDB.
date: '17.1.2023'
author: Ing. Jan Novotný
proofreading: done
preferredLang: java
commit: ad421e827459646612395d321e5ebb1ad5b6bbe2
---
<UsedTerms>
    <h4>Použité pojmy v tomto dokumentu</h4>
	<dl>
		<dt>facet</dt>
		<dd>Facet je vlastnost entity, která slouží uživateli k rychlému filtrování entit. Zobrazuje se jako
        zaškrtávací políčko v liště filtrů nebo jako posuvník v případě velkého množství různých číselných hodnot. Facety pomáhají
        zákazníkovi zúžit aktuální seznam kategorií, výrobců nebo výsledků fulltextového vyhledávání. Bylo by obtížné, aby zákazník procházel desítky stránek výsledků a pravděpodobně by byl nucen hledat nějakou podkategorii nebo najít lepší vyhledávací frázi. Pro uživatele je to frustrující a facety mohou tento proces usnadnit. Několika kliknutími může uživatel zúžit výsledky na relevantní facety. Klíčové je zde poskytnout dostatek informací a vést uživatele k nejrelevantnějším kombinacím facet. Je velmi užitečné ignorovat facety, které by již nevracely žádné výsledky, nebo dokonce uživatele informovat, že výběr konkrétní facet by zúžil výsledky na velmi málo záznamů a jeho volnost výběru bude výrazně omezena.</dd>
		<dt>facet group</dt>
		<dd>Skupina facet slouží ke sdružení facet stejného typu. Skupina facet řídí mechanismus filtrování facet.
        To znamená, že skupiny facet umožňují definovat, zda budou facety ve skupině při filtrování kombinovány pomocí booleovských vztahů OR, AND. Umožňuje také definovat, jak bude tato skupina facet kombinována s ostatními skupinami facet ve stejném dotazu (tj. AND, OR, NOT). Tento typ booleovské logiky ovlivňuje výpočet statistik facet a je klíčovou součástí vyhodnocování facet.</dd>
	</dl>
</UsedTerms>

Datový model evitaDB se skládá ze tří vrstev:

1. [katalog](#katalog)
2. [kolekce entit](#kolekce)
3. [entita](#entita) (data)

Každý katalog zabírá jednu složku ve složce `data` v evitaDB. Každá kolekce v tomto katalogu je obvykle
reprezentována jediným souborem (key/value store) v této složce. Entity jsou uloženy v binárním formátu v souboru kolekce. Více [detailů o formátu uložení](../deep-dive/storage-model.md) najdete v samostatné kapitole.

## Katalog

Katalog je nejvyšší izolační vrstva. Je ekvivalentem *databáze* v jiných databázových termínech. Katalog
obsahuje sadu kolekcí entit, které uchovávají data pro jednoho nájemce (tenant). evitaDB nepodporuje dotazy, které by
zasahovaly do více katalogů. Katalogy jsou na disku i v paměti zcela oddělené.

Katalog je popsán svým [schématem](schema.md#katalog). Změny ve struktuře katalogu lze provádět pouze pomocí
mutací schématu katalogu.

## Kolekce

Kolekce je úložná jednotka pro data vztahující se ke stejnému [typu entity](#typ-entity). Je ekvivalentem
*kolekce* v termínech jiných NoSQL databází jako MongoDB. V relačním světě je nejbližším pojmem *tabulka*,
ale kolekce v evitaDB spravuje mnohem více dat, než by mohla jedna relační tabulka. Správnou projekcí
v relačním světě by byla „sada logicky propojených tabulek“.

Kolekce v evitaDB nejsou izolované a entity v nich mohou být propojeny s entitami v různých kolekcích.
V současnosti jsou vztahy pouze jednosměrné.

<Note type="info">

<NoteTitle toggles="true">

##### Musím definovat schéma před vložením dat?
</NoteTitle>

Ačkoliv evitaDB vyžaduje schéma pro každý typ entity, podporuje automatický vývoj schématu, pokud to povolíte. Pokud
nespecifikujete jinak, evitaDB se učí o atributech entity, jejich datových typech a všech potřebných vztazích při
přidávání nových dat. Jakmile jsou atributy, přidružená data nebo jiné obrysy entity známy, jsou vynucovány evitaDB.
Tento mechanismus je do jisté míry podobný přístupu bez schématu, ale vede k mnohem konzistentnějšímu úložišti dat.

</Note>

Kolekce je popsána svým [schématem](schema.md#entita). Změny v definici typu entity lze provádět pouze
pomocí mutací schématu entity.

## Entita

Minimální definice entity se skládá z:

- [Typ entity](#typ-entity)
- [Primární klíč](#primární-klíč)

Ostatní data entity jsou čistě volitelná a nemusí být použita vůbec. Primární klíč lze nastavit na `NULL` a nechat
jej generovat databází automaticky.
<LS to="j,c">
Tato minimální struktura entity je pokryta rozhraním
<LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/EntityReferenceContract.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/Models/Data/IEntityReference.cs</SourceClass></LS>.

Plná entita s daty, referencemi, atributy a přidruženými daty je reprezentována rozhraním
<LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/EntityContract.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/Models/Data/IEntity.cs</SourceClass></LS>.
</LS>

### Typ entity

Typ entity musí být <LS to="e,j,r,g">[String type](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)</LS>
<LS to="c">[string type](https://learn.microsoft.com/en-us/dotnet/api/system.string)</LS>.

Typ entity je hlavní obchodní klíč (ekvivalent *názvu tabulky* v relační databázi) – všechna data entit stejného typu jsou uložena v samostatném indexu. V rámci typu entity je entita jednoznačně reprezentována
[primárním klíčem](#primární-klíč).

### Primární klíč

Primární klíč musí být <LS to="e,j,r,g">[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)</LS>
<LS to="c">[int](https://learn.microsoft.com/en-us/dotnet/api/system.int32)</LS> kladné číslo (max. 2<sup>63</sup>-1). Lze jej použít pro rychlé vyhledávání entity (entit). Primární klíč musí být jedinečný
v rámci stejného [typu entity](#typ-entity).

Lze jej ponechat jako `NULL`, pokud má být generován databází automaticky. Primární klíč umožňuje evitaDB rozhodnout,
zda má být entita vložena jako nová, nebo zda má být aktualizována již existující entita.

<Note type="question">

<NoteTitle toggles="true">

##### Proč byl pro primární klíč zvolen omezený typ `int`?

</NoteTitle>

Všechny primární klíče jsou ukládány ve struktuře dat zvané "[RoaringBitmap](https://github.com/RoaringBitmap/RoaringBitmap)".
Ta byla původně napsána v [C](https://github.com/RoaringBitmap/CRoaring) [Danielem Lemirem](https://lemire.me/blog/),
a tým vedený [Richardem Statinem](https://richardstartin.github.io/) ji portoval do Javy. Tato knihovna je
používána v mnoha existujících databázích pro podobné účely (Lucene, Druid, Spark, Pinot a mnoho dalších).

Tuto knihovnu jsme zvolili ze dvou hlavních důvodů:

1. umožňuje ukládat pole int v kompaktnějším formátu než jednoduché pole primitivních integerů,
2. obsahuje algoritmy pro rychlé booleovské operace nad těmito množinami integerů

Tato datová struktura funguje nejlépe pro celá čísla, která jsou blízko u sebe. To dobře koresponduje s databázovými sekvencemi,
které generují čísla inkrementovaná o jedna. Existuje varianta stejné datové struktury, která funguje s typem `long`,
ale má dvě nevýhody:

1. používá dvakrát více paměti
2. je mnohem pomalejší pro booleovské operace

Protože evitaDB je in-memory databáze, očekáváme, že počet entit nepřesáhne dvě miliardy.

</Note>

<Note type="info">

<NoteTitle toggles="false">

##### Seznam všech mutací nejvyšší úrovně pro entity
</NoteTitle>

- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/EntityUpsertMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/EntityRemoveMutation.java</SourceClass>**

</Note>

### Hierarchické umístění

Entity mohou být organizovány hierarchicky. To znamená, že entita může odkazovat na jednu nadřazenou entitu a může být
odkazována více podřízenými entitami. Hierarchie se vždy skládá z entit stejného typu.

Každá entita může být součástí nejvýše jedné hierarchie (stromu).

<LS to="j">

<Note type="info">

Hierarchické umístění je reprezentováno polem `parent` v:
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/EntityContract.java</SourceClass>.

Definice hierarchie je součástí hlavního schématu entity:
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/EntitySchemaContract.java</SourceClass>

</Note>

</LS>
<LS to="c">

<Note type="info">

Hierarchické umístění je reprezentováno vlastností `Parent` v:
<SourceClass>EvitaDB.Client/Models/Data/IEntity.cs</SourceClass>.

Definice hierarchie je součástí hlavního schématu entity:
<SourceClass>EvitaDB.Client/Models/Schemas/IEntitySchema.cs</SourceClass>

</Note>

</LS>
<LS to="g">

<Note type="info">

Hierarchické umístění je reprezentováno poli `parentPrimaryKey` a `parents` v objektu entity.
Definice hierarchie je součástí hlavního schématu entity.

</Note>

</LS>
<LS to="r">

<Note type="info">
Hierarchické umístění je reprezentováno poli `parent` a `parentEntity` v objektu entity.
Definice hierarchie je součástí hlavního schématu entity.
</Note>

</LS>

<Note type="info">

<NoteTitle toggles="false">

##### Seznam všech mutací souvisejících s hierarchickým umístěním
</NoteTitle>

- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/parent/SetParentMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/parent/RemoveParentMutation.java</SourceClass>**

</Note>

<Note type="question">

<NoteTitle toggles="true">

##### Proč podporujeme hierarchické entity jako prvořadé objekty?
</NoteTitle>

Většina e-commerce systémů organizuje své produkty v hierarchickém systému kategorií. Kategorie jsou zdrojem
pro katalogová menu a když uživatel prochází obsah kategorie, obvykle vidí produkty v celém podstromu dané kategorie.
Proto jsou hierarchie v evitaDB přímo podporovány.
</Note>

Více detailů o hierarchickém umístění je popsáno v [kapitole o definici schématu](schema.md#umístění-v-hierarchii).

### Atributy (unikátní, filtrovatelné, řaditelné, lokalizované)

Atributy entity umožňují definovat sadu dat, která mají být hromadně načtena spolu s tělem entity.
Každé schéma atributu může být označeno jako filtrovatelné pro umožnění filtrování podle něj, nebo řaditelné
pro umožnění řazení podle něj.

<Note type="warning">
Atributy jsou automaticky filtrovatelné / řaditelné, pokud jsou automaticky přidány mechanismem automatického vývoje schématu, aby byl přístup „je mi to jedno“ ke schématu snadný a „prostě fungoval“. Nicméně filtrovatelné nebo řaditelné atributy vyžadují indexy, které jsou v evitaDB udržovány zcela v paměti, a tento přístup vede k plýtvání prostředky. Proto doporučujeme použít přístup „schema-first“ a označit jako filtrovatelné / řaditelné pouze ty atributy, které jsou skutečně používány pro filtrování / řazení.
</Note>

Atributy se také doporučuje používat pro často používaná data, která doprovázejí entitu (například „název“,
„perex“, „hlavní motiv“), i když je nutně nepotřebujete pro filtrování/řazení. evitaDB ukládá a načítá všechny atributy v jednom bloku, takže uchovávání těchto často používaných dat v atributech snižuje celkovou I/O zátěž.

<LS to="j,c">

<Note type="info">

Poskytovatel atributů ([entita](#typ-entity) nebo [reference](#reference)) je reprezentován rozhraním:
<LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/AttributesContract.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/Models/Data/IAttributes.cs</SourceClass></LS>

Schéma atributu je popsáno:
<LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/AttributeSchemaContract.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/Models/Schemas/IAttributeSchema.cs</SourceClass></LS>

</Note>

</LS>

Více detailů o atributech je popsáno v [kapitole o definici schématu](schema.md#atributy).

#### Lokalizované atributy

Atribut může obsahovat lokalizované hodnoty. To znamená, že pro filtrování/řazení a vracení spolu s entitou by měly být použity různé hodnoty v závislosti na konkrétním jazyce v
[dotazu na vyhledávání](../query/filtering/locale.md). Lokalizované atributy jsou standardní součástí většiny e-commerce systémů a proto pro ně evitaDB poskytuje speciální zacházení.

#### Datové typy v atributech

Atributy umožňují použití [různých datových typů](data-types.md) a jejich polí. Databáze podporuje všechny základní typy,
typy data a času a <LS to="e,j,r,g"><SourceClass>evita_common/src/main/java/io/evitadb/dataType/Range.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/DataTypes/Range.cs</SourceClass></LS> typy. Hodnoty rozsahu jsou povoleny pomocí speciálního typu [dotazu](../query/basics.md) filtračního omezení –
[`inRange`](../query/filtering/range.md). Toto filtrační omezení umožňuje filtrovat entity, které spadají do hranic rozsahu.

<Note type="info">

<NoteTitle toggles="false">

##### Seznam všech mutací souvisejících s atributy
</NoteTitle>

- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/attribute/UpsertAttributeMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/attribute/RemoveAttributeMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/attribute/ApplyDeltaAttributeMutation.java</SourceClass>**

</Note>

<Note type="question">

<NoteTitle toggles="true">

##### Mohu uložit více hodnot do jednoho atributu?
</NoteTitle>

Jakýkoliv z podporovaných datových typů může být zabalen do pole – tedy atribut může reprezentovat více hodnot najednou. Takový atribut nelze použít pro řazení, ale lze jej použít pro filtrování, kde splní filtrační podmínku, pokud **jakákoliv** z hodnot v poli odpovídá predikátu. To je obzvlášť užitečné pro rozsahy, kde můžete jednoduše definovat více období platnosti, například, a omezení [`inRange`](../query/filtering/range.md) bude odpovídat všem entitám, které mají alespoň jedno období zahrnující zadané datum a čas (což je další běžný případ použití v e-commerce systémech).

</Note>

### Složeniny řaditelných atributů

Složeniny řaditelných atributů nejsou vkládány do entity, ale jsou automaticky vytvářeny databází při vložení entity a udržují index pro definované hodnoty atributů entity / reference. Složeniny atributů lze použít pouze pro řazení entit stejným způsobem jako atribut.

### Přidružená data

Přidružená data nesou další položky dat, které nikdy nejsou používány pro filtrování / řazení, ale mohou být potřeba načíst spolu s entitou, aby bylo možné zobrazit data cílovému konzumentovi (tj. uživateli / API / botovi). Přidružená data umožňují ukládat všechny základní [datové typy](data-types.md#jednoduché-datové-typy) a také [komplexní](data-types.md#komplexní-datové-typy), dokumentové typy.

[Dotaz na vyhledávání](../query/basics.md) musí obsahovat konkrétní
[požadavek](../query/requirements/fetching.md#obsah-souvisejících-dat) na načtení přidružených dat spolu s entitou.
Přidružená data jsou ukládána a načítána samostatně podle jejich názvu a *jazyka* (pokud jsou přidružená data
[lokalizovaná](#lokalizovaná-přidružená-data)).

<LS to="j,c">
<Note type="info">
Poskytovatel AssociatedData ([entita](#typ-entity)) je reprezentován rozhraním:
<LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/AssociatedDataContract.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/Models/Data/IAssociatedData.cs</SourceClass></LS>

Schéma přidružených dat je popsáno:
<LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/AssociatedDataSchemaContract.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/Models/Schemas/IAssociatedDataSchema.cs</SourceClass></LS>.

</Note>

</LS>

Více detailů o přidružených datech je popsáno v [kapitole o definici schématu](schema.md#přidružená-data).

#### Lokalizovaná přidružená data

Hodnota přidružených dat může obsahovat lokalizované hodnoty. To znamená, že spolu s entitou budou vráceny různé hodnoty, pokud je v [dotazu na vyhledávání](../query/basics.md) použit určitý jazyk. Lokalizovaná data jsou standardní součástí většiny e-commerce systémů a proto pro ně evitaDB poskytuje speciální zacházení.

<Note type="info">

<NoteTitle toggles="false">

##### Seznam všech mutací souvisejících s přidruženými daty
</NoteTitle>

- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/associatedData/UpsertAssociatedDataMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/associatedData/RemoveAssociatedDataMutation.java</SourceClass>**

</Note>

### Reference

Reference, jak název napovídá, odkazují na jiné entity (stejného nebo jiného typu entity). Reference umožňují filtrování entit podle atributů definovaných na referenčním vztahu nebo podle atributů referencovaných entit. Reference umožňují výpočet [statistik](../query/requirements/facet.md), pokud je pro tento typ referencované entity povolen index facet. Reference je primárně reprezentována <LS to="e,j,r,g">[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)</LS><LS to="c">[int](https://learn.microsoft.com/en-us/dotnet/api/system.int32)</LS> kladným číslem (max. 2<sup>63</sup>-1) a <LS to="e,j,r,g">[String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)</LS><LS to="c">[string](https://learn.microsoft.com/en-us/dotnet/api/system.string)</LS> typem entity a může reprezentovat <Term>facet</Term>, který je součástí jedné nebo více <Term name="facet group">skupin facet</Term>, také identifikovaných pomocí <LS to="e,j,r,g">[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)</LS><LS to="c">[int](https://learn.microsoft.com/en-us/dotnet/api/system.int32)</LS>. Identifikátor reference v entitě je jedinečný a patří do jedné skupiny. U více entit může reference na stejnou referencovanou entitu být součástí různých skupin.

Typ referencované entity může odkazovat na jinou entitu spravovanou evitaDB, nebo může odkazovat na jakoukoli externí entitu, která má jedinečný <LS to="e,j,r,g">[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)</LS>
<LS to="c">[int](https://learn.microsoft.com/en-us/dotnet/api/system.int32)</LS> klíč jako svůj identifikátor. Očekáváme, že evitaDB bude spravovat data pouze částečně a že bude koexistovat s dalšími systémy za běhu – například systémy pro správu obsahu, skladovými systémy, ERP atd.

Reference jsou jednosměrné, což znamená, že pokud reference směřuje z entity A na entitu B, neznamená to, že entita B automaticky odkazuje zpět na entitu A. Je možné nastavit obousměrnou referenci vytvořením tzv. „reflektované reference“ na druhém typu entity a identifikovat původní referenci, která má být reflektována.

Reference mohou nést další párová data vztahující se k tomuto vztahu entit (například počet položek na vztahu ke skladu). Data na referencích podléhají stejným pravidlům jako [atributy entity](#atributy-unikátní-filtrovatelné-řaditelné-lokalizované).

<LS to="j,c">

<Note type="info">

Reference je reprezentována rozhraním:
<LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/ReferenceContract.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/Models/Data/IReference.cs</SourceClass></LS>.

Schéma reference je popsáno:
<LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/ReferenceSchemaContract.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/Models/Schemas/IReferenceSchema.cs</SourceClass></LS>.

Reflektovaná reference je reprezentována rozhraním:
<LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/ReflectedReferenceContract.java</SourceClass></LS>
<LS to="c">(zatím není podporováno v C# driveru – viz [issue 8](https://github.com/FgForrest/evitaDB-C-Sharp-client/issues/8))</LS>.

</Note>

</LS>

Více detailů o referencích je popsáno v [kapitole o definici schématu](schema.md#reference).

<Note type="info">

<NoteTitle toggles="false">

##### Seznam všech mutací souvisejících s referencemi
</NoteTitle>

- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/reference/InsertReferenceMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/reference/RemoveReferenceMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/reference/SetReferenceGroupMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/reference/RemoveReferenceGroupMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/reference/ReferenceAttributeMutation.java</SourceClass> která musí mít přesně jednu z [mutací atributu](#seznam-všech-mutací-souvisejících-s-atributy) vnořenou**

</Note>

### Ceny

Ceny jsou specifické pro velmi málo typů entit (obvykle produkty, způsoby dopravy atd.), ale protože správný výpočet ceny je velmi složitou a důležitou součástí e-commerce systémů a výrazně ovlivňuje výkon filtrování a řazení entit, zaslouží si v modelu entity prvořadou podporu. Je poměrně běžné v B2B systémech, že jeden produkt má přiřazeny desítky cen pro různé zákazníky.

Cena má následující strukturu:

<dl>
    <dt>
        <LS to="e,j,r,g">[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) `priceId`</LS>
        <LS to="c">[int](https://learn.microsoft.com/en-us/dotnet/api/system.int32) `PriceId`</LS>
    </dt>
    <dd>
	    Obsahuje identifikaci ceny v externích systémech. Toto ID se očekává pro synchronizaci ceny ve vztahu k primárnímu zdroji cen. Cena se stejným ID musí být jedinečná v rámci jedné entity. Ceny se stejným ID v různých entitách by měly reprezentovat stejnou cenu z hlediska ostatních hodnot – jako je platnost, měna, ceník, samotná cena a všechny ostatní vlastnosti. Tyto hodnoty se mohou lišit po omezenou dobu (například ceny Entity A a Entity B mohou být stejné, ale Entity A je aktualizována v jiné relaci/transakci a v jiném čase než Entity B).
    </dd>
    <dt>
        <LS to="e,j,r,g">[String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html) `priceList`</LS>
        <LS to="c">[string](https://learn.microsoft.com/en-us/dotnet/api/system.string) `PriceList`</LS>
    </dt>
    <dd>
        Obsahuje identifikaci ceníku v externím systému. Každá cena musí odkazovat na ceník.
        Identifikace ceníku může odkazovat na jinou entitu Evita nebo obsahovat jakoukoli externí identifikaci ceníku
        (například ID nebo jedinečný název ceníku v externím systému).
		Očekává se, že jedna entita bude mít jednu cenu pro ceník, pokud není zadána `validity`. Jinými slovy, nemá smysl mít více současně platných cen pro stejnou entitu, které vycházejí ze stejného ceníku.
    </dd>
    <dt>
        <LS to="e,j,r,g">[Currency](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Currency.html) `currency`</LS>
        <LS to="c">[Currency](https://github.com/FgForrest/evitaDB-C-Sharp-client/blob/master/EvitaDB.Client/DataTypes/Currency.cs) `Currency`</LS>
    </dt>
    <dd>
        Identifikace měny. Třípísmenný kód podle [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217).
    </dd>
    <dt>
        <LS to="e,j,r,g">[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) `innerRecordId`</LS>
        <LS to="c">[int](https://learn.microsoft.com/en-us/dotnet/api/system.int32) `InnerRecordId`</LS>
    </dt>
    <dd>
        Některé speciální produkty (například hlavní produkty nebo sady produktů) mohou obsahovat ceny všech „podřízených“ produktů, aby agregující produkt mohl zobrazit jejich ceny v určitých pohledech produktu. V tomto případě je nutné rozlišit promítané ceny podřízených produktů v produktu, který je zastupuje.
    </dd>
    <dt>
        <LS to="e,j,r,g">[BigDecimal](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/math/BigDecimal.html) `priceWithoutTax`</LS>
        <LS to="c">[decimal](https://learn.microsoft.com/en-us/dotnet/api/system.decimal) `PriceWithoutTax`</LS>
    </dt>
    <dd>
        Cena bez daně.
    </dd>
    <dt>
        <LS to="e,j,r,g">[BigDecimal](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/math/BigDecimal.html) `priceWithTax`</LS>
        <LS to="c">[decimal](https://learn.microsoft.com/en-us/dotnet/api/system.decimal) `PriceWithTax`</LS>
    </dt>
    <dd>
        Cena s daní.
    </dd>
    <dt>
        <LS to="e,j,r,g">[BigDecimal](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/math/BigDecimal.html) `taxRate`</LS>
        <LS to="c">[decimal](https://learn.microsoft.com/en-us/dotnet/api/system.decimal) `TaxRate`</LS>
    </dt>
    <dd>
        Procento daně (tj. pro 19 % bude 19.00)
    </dd>
    <dt>
        [DateTimeRange](data-types.md#datetimerange) <LS to="e,j,r,g">`validity`</LS>
        <LS to="c">`Validity`</LS>
    </dt>
    <dd>
        Datum a časový interval, pro který je cena platná (včetně).
    </dd>
    <dt>
        <LS to="e,j,r,g">[boolean](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) `indexed`</LS>
        <LS to="c">[bool](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/builtin-types/bool) `Indexed`</LS>
    </dt>
    <dd>
        Určuje, zda je cena předmětem logiky filtrování/řazení, neindexované ceny budou načteny spolu s entitou, ale nebudou zohledněny při vyhodnocování dotazu. Tyto ceny mohou být použity pro „informativní“ ceny, například referenční cenu (přeškrtnutá cena často uváděná na e-shopech jako „obvyklá cena“), ale nejsou použity jako „prodejní cena“.
    </dd>
</dl>

<LS to="j,c">

<Note type="info">

Poskytovatel cen je reprezentován rozhraním:
<LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PricesContract.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/Models/Data/IPrices.cs</SourceClass></LS>

Jednotlivá cena je reprezentována rozhraním:
<LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceContract.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/Models/Data/IPrice.cs</SourceClass></LS>

Schéma ceny je součástí hlavního schématu entity:
<LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/EntitySchemaContract.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/Models/Schemas/IEntitySchema.cs</SourceClass></LS>

</Note>

</LS>

<Note type="info">

<NoteTitle toggles="false">

##### Seznam všech mutací souvisejících s cenami
</NoteTitle>

- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/price/UpsertPriceMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/price/RemovePriceMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/price/SetPriceInnerRecordHandlingMutation.java</SourceClass>**

</Note>

<Note type="question">

<NoteTitle toggles="true">

##### Chcete vědět více o tom, jak se vypočítává prodejní cena?
</NoteTitle>

Algoritmus je poměrně složitý a pro jeho pochopení je potřeba mnoho příkladů. Proto existuje
[samostatná kapitola na toto téma](../deep-dive/price-for-sale-calculation.md).

</Note>

Více detailů o cenách je popsáno v [kapitole o definici schématu](schema.md#ceny).

### Scope

Scopy jsou oddělené oblasti paměti, kde jsou ukládány indexy entit. Scopy se používají k oddělení živých dat od archivovaných dat. Scopy slouží k řešení tzv. „soft delete“ – aplikace si může zvolit mezi tvrdým smazáním a archivací entity, což jednoduše přesune entitu do archivačního scope. Podrobnosti o procesu archivace jsou popsány v kapitole [Archivace](../use/schema.md#scopy) a důvody této funkce jsou vysvětleny v [samostatném blogovém příspěvku](https://evitadb.io/blog/15-soft-delete).

<Note type="info">

<NoteTitle toggles="false">

##### Seznam všech mutací souvisejících se scope
</NoteTitle>

- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/scope/SetEntityScopeMutation.java</SourceClass>**

</Note>