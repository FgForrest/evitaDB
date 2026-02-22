---
title: Stránkování
perex: Omezení stránkovacích požadavků pomáhají procházet velké seznamy záznamů tím, že je rozdělí na několik částí, které jsou požadovány samostatně. Tato technika se používá ke snížení množství dat přenášených po síti a ke snížení zátěže serveru. evitaDB podporuje několik způsobů stránkování výsledků dotazů, které jsou popsány v této sekci.
date: '23.7.2023'
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
commit: cabcf999e7be5b00e0b13e1228a76a8d9e91cb78
translated: true
---
<LS to="g">

V GraphQL existuje několik různých způsobů, jak stránkovat výsledky. Hlavní rozdíl je mezi
[dotazy typu `list`](../../use/api/query-data.md#list-dotazy) a [dotazy typu `query`](../../use/api/query-data.md#query-dotazy).
Dotazy typu `query` se dále dělí na stránkování pomocí `page` a `strip`.

## Stránkování dotazů typu `list`

Jak je uvedeno v [podrobném popisu dotazů typu `list`](../../use/api/query-data.md#list-dotazy), dotazy typu `list`
jsou určeny pro rychlý výpis entit, a proto nabízejí pouze omezenou sadu funkcí pro stránkování.

Stránkování je řízeno pomocí argumentů `limit` a `offset` na poli `listCollectionName` a neposkytuje
žádná metadata o stránkování (např. celkový počet záznamů, číslo stránky apod.):

<SourceCodeTabs langSpecificTabOnly>

[Příklad získání třetí stránky výsledků pomocí dotazu typu list](/documentation/user/en/query/requirements/examples/paging/listEntities.graphql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek příkladu stránkování dotazu typu list
</NoteTitle>

Výsledek obsahuje záznamy od 11. do 15. záznamu výpisu. Vrací pouze primární klíč záznamů, protože nebyl specifikován požadavek na obsah, a je seřazen podle primárního klíče vzestupně, protože v dotazu nebylo zadáno žádné řazení.

<LS to="g">

<MDInclude sourceVariable="data.listProduct">[Datový blok s stránkovanými daty](/documentation/user/en/query/requirements/examples/paging/listEntities.graphql.json.md)</MDInclude>

</LS>

</Note>

</LS>

<LS to="g">

## Stránkování dotazů typu `query`

Plně vybavené [dotazy typu `query`](../../use/api/query-data.md#query-dotazy) podporují plnohodnotné stránkování.
Stránkování má v tomto případě dvě verze – `page` (pole `recordPage`) a `strip` (pole `recordStrip`) a obě poskytují metadata o stránkování.

### Page (`recordPage`)

</LS>
<LS to="e,j,r,c">

## Page

```evitaql-syntax
page(
    argument:int!,
    argument:int!,
    requireConstraint:spacing?
)
```

<dl>
    <dt>argument:int</dt>
    <dd>
        povinné číslo stránky, která má být vrácena, kladné celé číslo začínající od 1
    </dd>
    <dt>argument:int</dt>
    <dd>
        povinná velikost stránky, která má být vrácena, kladné celé číslo
    </dd>
    <dt>requireConstraint:spacing?</dt>
    <dd>
        volitelné omezení, které určuje pravidla pro ponechání mezer na určitých stránkách výsledku dotazu
        (viz kapitola [spacing constraint](#spacing) pro více detailů)
    </dd>
</dl>

</LS>

Pole `page`
<LS to="e,j,r">(<SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/Page.java</SourceClass>)</LS><LS to="c">(<SourceClass>EvitaDB.Client/Queries/Requires/Page.cs</SourceClass>) požadavek</LS>
<LS to="g">přístup</LS>
určuje počet a výřez entit vrácených v odpovědi na dotaz<LS to="g"> a je zadán použitím pole `recordPage` (v kombinaci s argumenty `number` a `size`)</LS>.
Pokud nejsou
<LS to="e,j,r,c">požadavky na stránku</LS>
<LS to="g">argumenty stránky</LS> použity
<LS to="e,j,r,c">v dotazu</LS>
<LS to="g">na poli</LS>,
je použita výchozí stránka `1` s výchozí velikostí stránky `20`. Pokud požadovaná stránka přesáhne počet dostupných
stránek, je vrácen výsledek s první stránkou. Prázdný výsledek je vrácen pouze tehdy, pokud dotaz nevrátí žádný výsledek
nebo je velikost stránky nastavena na nulu. Automatickým vrácením výsledku první stránky při překročení požadované stránky
se snažíme předejít nutnosti zadávat další požadavek pro získání dat.

Informace o skutečně vrácené stránce a statistikách dat lze nalézt v odpovědi na dotaz, která je zabalena
do tzv. objektu datového bloku. <LS to="e,j,r,c">V případě omezení `page`
je jako datový blok použit
<LS to="e,j,r"><SourceClass>evita_common/src/main/java/io/evitadb/dataType/PaginatedList.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/DataTypes/PaginatedList.cs</SourceClass></LS>.</LS> Objekt datového bloku obsahuje následující informace:

<dl>
    <dt>pageNumber</dt>
    <dd>
        číslo stránky vrácené v odpovědi na dotaz
    </dd>
    <dt>pageSize</dt>
    <dd>
        velikost stránky vrácené v odpovědi na dotaz
    </dd>
    <dt>lastPageNumber</dt>
    <dd>
        poslední číslo stránky dostupné pro dotaz, požadavek na `lastPageNumber + 1` vrací první stránku
    </dd>
    <dt>firstPageItemNumber</dt>
    <dd>
        offset prvního záznamu aktuální stránky s aktuální velikostí stránky
    </dd>
    <dt>lastPageItemNumber</dt>
    <dd>
        offset posledního záznamu aktuální stránky s aktuální velikostí stránky
    </dd>
    <dt>first</dt>
    <dd>
        `TRUE`, pokud je aktuální stránka první dostupnou stránkou
    </dd>
    <dt>last</dt>
    <dd>
        `TRUE`, pokud je aktuální stránka poslední dostupnou stránkou
    </dd>
    <dt>hasNext</dt>
    <dd>
        `TRUE`, pokud jsou k dispozici data pro další stránku (tj. `pageNumber + 1 <= lastPageNumber`)
    </dd>
    <dt>hasPrevious</dt>
    <dd>
        `TRUE`, pokud je aktuální stránka poslední dostupnou stránkou (tj. `pageNumber - 1 > 0`)
    </dd>
    <dt>empty</dt>
    <dd>
        `TRUE`, pokud dotaz nevrátil žádná data (tj. `totalRecordCount == 0`)
    </dd>
    <dt>singlePage</dt>
    <dd>
        `TRUE`, pokud dotaz vrátil přesně jednu stránku dat (tj. `pageNumber == 1 && lastPageNumber == 1 && totalRecordCount > 0`)
    </dd>
    <dt>totalRecordCount</dt>
    <dd>
        celkový počet entit dostupných pro dotaz
    </dd>
    <dt>data</dt>
    <dd>
        seznam entit vrácených v odpovědi na dotaz
    </dd>
</dl>

<LS to="e,j,r,c">Požadavek `page`</LS><LS to="g">Pole `recordPage`</LS>
je nejpřirozenějším a nejčastěji používaným požadavkem pro stránkování výsledků dotazu.
Chcete-li získat druhou stránku výsledku dotazu, použijte následující dotaz:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad získání druhé stránky výsledků](/documentation/user/en/query/requirements/examples/paging/page.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek příkladu druhé stránky
</NoteTitle>

Výsledek obsahuje záznamy od 6. do 10. záznamu výsledku dotazu. Vrací pouze primární klíč záznamů, protože nebyl specifikován požadavek na obsah, a je seřazen podle primárního klíče vzestupně, protože v dotazu nebylo zadáno žádné řazení.

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Datový blok s stránkovanými daty](/documentation/user/en/query/requirements/examples/paging/page.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Datový blok s stránkovanými daty](/documentation/user/en/query/requirements/examples/paging/page.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Datový blok s stránkovanými daty](/documentation/user/en/query/requirements/examples/paging/page.rest.json.md)</MDInclude>

</LS>

</Note>

<LS to="g">

### Strip (`recordStrip`)

</LS>

<LS to="e,j,r,c">

## Strip

```evitaql-syntax
strip(
    argument:int!,
    argument:int!
)
```

<dl>
    <dt>argument:int</dt>
    <dd>
        povinný offset prvního záznamu stránky, která má být vrácena, kladné celé číslo začínající od 0
    </dd>
    <dt>argument:int</dt>
    <dd>
        povinný limit záznamů, které mají být vráceny, kladné celé číslo
    </dd>
</dl>

</LS>

Pole `strip`
<LS to="e,j,r">(<SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/Strip.java</SourceClass>)</LS><LS to="c">(<SourceClass>EvitaDB.Client/Queries/Requires/Strip.cs</SourceClass>) požadavek</LS>
<LS to="g">přístup</LS>
určuje počet a výřez entit vrácených v odpovědi na dotaz<LS to="g"> a je zadán použitím pole `recordStrip` (v kombinaci s argumenty `limit` a `offset`)</LS>.
Pokud požadovaný strip přesáhne počet
dostupných záznamů, je vrácen výsledek od nultého offsetu s ponechaným limitem. Prázdný výsledek je vrácen pouze tehdy,
pokud dotaz nevrátí žádný výsledek nebo je limit nastaven na nulu. Automatickým vrácením výsledku prvního stripu při
překročení požadované stránky se snažíme předejít nutnosti zadávat další požadavek pro získání dat.

Informace o skutečně vrácené stránce a statistikách dat lze nalézt v odpovědi na dotaz, která je zabalena
do tzv. objektu datového bloku. <LS to="e,j,r,c">V případě omezení `strip`
je jako datový blok použit
<LS to="e,j,r"><SourceClass>evita_common/src/main/java/io/evitadb/dataType/StripList.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/DataTypes/StripList.cs</SourceClass></LS>.</LS> Objekt datového bloku obsahuje následující informace:

<dl>
    <dt>offset</dt>
    <dd>
        offset prvního záznamu vráceného v odpovědi na dotaz
    </dd>
    <dt>limit</dt>
    <dd>
        limit záznamů vrácených v odpovědi na dotaz
    </dd>
    <dt>first</dt>
    <dd>
        `TRUE`, pokud aktuální strip začíná prvními záznamy výsledku dotazu
    </dd>
    <dt>last</dt>
    <dd>
        `TRUE`, pokud aktuální strip končí posledními záznamy výsledku dotazu
    </dd>
    <dt>hasNext</dt>
    <dd>
        `TRUE`, pokud jsou k dispozici data pro další strip (tj. `last == false`)
    </dd>
    <dt>hasPrevious</dt>
    <dd>
        `TRUE`, pokud je aktuální stránka poslední dostupnou stránkou (tj. `first == false`)
    </dd>
    <dt>empty</dt>
    <dd>
        `TRUE`, pokud dotaz nevrátil žádná data (tj. `totalRecordCount == 0`)
    </dd>
    <dt>totalRecordCount</dt>
    <dd>
        celkový počet entit dostupných pro dotaz
    </dd>
    <dt>data</dt>
    <dd>
        seznam entit vrácených v odpovědi na dotaz
    </dd>
</dl>

Požadavek `strip` lze použít pro výpis záznamů dotazu neuniformním způsobem – například když je výpis entit
prokládán reklamou, která vyžaduje přeskočení vykreslení entity na určitých pozicích. Jinými slovy,
pokud víte, že na každých 20 záznamů je "reklamní" blok, což znamená, že entita musí být na této pozici přeskočena,
a chcete správně načíst záznamy pro 5. stránku, musíte požadovat strip s offsetem `76`
(4 stránky * 20 pozic na stránku – 4 záznamy vynechány na předchozích 4 stránkách) a limitem 19. Pro získání takového stripu použijte
následující dotaz:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad získání neuniformního stripu výsledků](/documentation/user/en/query/requirements/examples/paging/strip.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek příkladu požadovaného stripu
</NoteTitle>

Výsledek obsahuje záznamy od 76. do 95. záznamu výsledku dotazu. Vrací pouze primární klíč záznamů, protože nebyl specifikován požadavek na obsah, a je seřazen podle primárního klíče vzestupně, protože v dotazu nebylo zadáno žádné řazení.

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Datový blok se strip listem](/documentation/user/en/query/requirements/examples/paging/strip.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordStrip">[Datový blok se strip listem](/documentation/user/en/query/requirements/examples/paging/strip.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Datový blok se strip listem](/documentation/user/en/query/requirements/examples/paging/strip.rest.json.md)</MDInclude>

</LS>

</Note>

## Spacing

```evitaql-syntax
spacing(
    requireConstraint:gap+   
)
```

<dl>
    <dt>requireConstraint:gap+</dt>
    <dd>
        jedno nebo více omezení, která určují pravidla pro ponechání mezer na určitých stránkách výsledku dotazu
    </dd>
</dl>

Požadavek `spacing` je kontejner pro jedno nebo více omezení `gap`, která určují pravidla pro ponechání mezer na
určitých stránkách výsledku dotazu. Mění výchozí chování omezení [`page`](#page), snižuje
počet záznamů vrácených na určitých stránkách. Ovlivňuje také počet stránek (vlastnost `lastPageNumber`
<LS to="e,j,r"><SourceClass>evita_common/src/main/java/io/evitadb/dataType/PaginatedList.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/DataTypes/PaginatedList.cs</SourceClass></LS>). Pravidla [`gap`](#gap) jsou
aditivní a velikosti mezer jsou součtem všech pravidel gap, která platí pro danou stránku.

<Note type="info">

<NoteTitle toggles="true">

##### Výkonnostní hlediska

</NoteTitle>

Abyste nemuseli přepočítávat pravidlo pro každou stránku ve výsledné sadě, můžete rozsah omezit přidáním konstantního
výrazu do pravidla. Například pravidlo `$pageNumber % 2 == 0 && $pageNumber <= 10` bude přepočítáno pouze pro
prvních 10 stránek výsledku dotazu, protože interpret ví, že pravidlo nebude nikdy splněno pro
zbývající stránky.
</Note>

Omezení spacing jsou užitečná, pokud potřebujete uvolnit místo pro další obsah na určitých stránkách výsledku dotazu,
například pro reklamy, bannery, blogové příspěvky nebo jiný externí obsah, který chcete zobrazit mezi záznamy.
Například, pokud chcete zobrazit reklamu na každé sudé stránce až do 10. stránky a zároveň chcete zobrazit
blogový příspěvek na 1. a 4. stránce. K tomu byste použili následující dotaz:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad vloženého spacingu](/documentation/user/en/query/requirements/examples/paging/spacing_page1.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek příkladu požadované stránky s vloženým spacingem
</NoteTitle>

První stránka obsahuje 9 záznamů (jeden slot ponechán pro blogový příspěvek), druhá stránka obsahuje 9 záznamů (jeden slot ponechán pro reklamu, protože číslo stránky je sudé) a čtvrtá stránka obsahuje pouze 8 záznamů (jeden slot ponechán pro blogový příspěvek a jeden slot pro reklamu, protože číslo stránky je sudé), poslední číslo stránky je přepočítáno, protože na předních stránkách bylo ponecháno celkem 7 záznamů.

**První stránka:**

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Datový blok se strip listem](/documentation/user/en/query/requirements/examples/paging/spacing_page1.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Datový blok se strip listem](/documentation/user/en/query/requirements/examples/paging/spacing_page1.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Datový blok se strip listem](/documentation/user/en/query/requirements/examples/paging/spacing_page1.rest.json.md)</MDInclude>

</LS>

**Druhá stránka:**

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad vloženého spacingu](/documentation/user/en/query/requirements/examples/paging/spacing_page2.evitaql)

</SourceCodeTabs>

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Datový blok se strip listem](/documentation/user/en/query/requirements/examples/paging/spacing_page2.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Datový blok se strip listem](/documentation/user/en/query/requirements/examples/paging/spacing_page2.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Datový blok se strip listem](/documentation/user/en/query/requirements/examples/paging/spacing_page2.rest.json.md)</MDInclude>

</LS>

**Čtvrtá stránka:**

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad vloženého spacingu](/documentation/user/en/query/requirements/examples/paging/spacing_page4.evitaql)

</SourceCodeTabs>

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Datový blok se strip listem](/documentation/user/en/query/requirements/examples/paging/spacing_page4.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Datový blok se strip listem](/documentation/user/en/query/requirements/examples/paging/spacing_page4.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Datový blok se strip listem](/documentation/user/en/query/requirements/examples/paging/spacing_page4.rest.json.md)</MDInclude>

</LS>

</Note>

## Gap

```evitaql-syntax
gap(
    argument:int!,
    argument:expression!
)
```

<dl>
    <dt>argument:int</dt>
    <dd>
        povinné číslo určující velikost mezery, která má být na stránce ponechána prázdná (tj. počet záznamů,
        které mají být na stránce přeskočeny)
    </dd>
    <dt>argument:expression</dt>
    <dd>
        povinný [výraz](../expression-language.md), který musí být vyhodnocen na hodnotu boolean, určující,
        zda má být mezera na dané stránce použita či nikoliv.
    </dd>
</dl>

Požadavek `gap` určuje jedno pravidlo pro ponechání mezery dané velikosti na dané stránce, identifikované
výrazem pro každou stránku. Podrobný způsob použití je popsán v kapitole [spacing constraint](#spacing).

**Výraz může používat následující proměnné:**

<dl>
    <dt>variableName: `pageNumber` typu: `int`</dt>
    <dd>
        číslo stránky, která má být vyhodnocena
    </dd>
</dl>