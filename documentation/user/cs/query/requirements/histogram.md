---
title: Histogram
date: '7.11.2023'
perex: Histogramy hrají klíčovou roli v parametrickém filtrování v e-commerce tím, že vizuálně zobrazují rozložení vlastností produktů, což zákazníkům umožňuje efektivně upravovat kritéria vyhledávání. Usnadňují interaktivnější a přesnější filtrování, protože uživatelé mohou měnit rozsah vlastností, jako je cena nebo velikost, na základě skutečné dostupnosti položek.
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
translated: 'true'
commit: '8b4d26cd809b1322669a71f16fbd20e464109a2f'
---
Ve skutečnosti existuje jen několik případů použití histogramů na e-commerce webech. Nejčastější je histogram cen, který slouží k filtrování produktů podle ceny. Příklad takového histogramu můžete vidět na webu Booking.com:

![Booking.com price histogram filter](../../../en/query/requirements/assets/price-histogram.png "Booking.com price histogram filter")

Je škoda, že histogram není používán častěji, protože je to velmi užitečný nástroj pro získání přehledu o rozložení hodnot atributů produktů s vysokou kardinalitou, jako je hmotnost, výška, šířka a podobně.

Datová struktura histogramu je optimalizována pro vykreslování na frontendu. Obsahuje následující pole:

- **`min`** – minimální hodnota atributu v aktuálním kontextu filtru
- **`max`** – maximální hodnota atributu v aktuálním kontextu filtru
- **`overallCount`** – počet prvků, jejichž hodnota atributu spadá do některého z intervalů (v podstatě součet všech výskytů v intervalech)
- **`buckets`** – *seřazené* pole intervalů, z nichž každý obsahuje následující pole:
  - **`threshold`** – minimální hodnota atributu v intervalu, maximální hodnota je threshold následujícího intervalu (nebo `max` pro poslední interval)
  - **`occurrences`** – počet prvků, jejichž hodnota atributu spadá do intervalu
  - **`relativeFrequency`** – hodnota používaná pro vizualizaci výšky intervalu v UI (škála 0–100):
    - Pro **standardní histogramy**: procento z celkového počtu výskytů, vypočteno jako `(occurrences / overallCount) * 100`
    - Pro **equalizované histogramy**: normalizovaná hustota hodnot, která zohledňuje jak počet výskytů, tak šířku intervalu:
      1. Hrubá frekvence se vypočte jako `occurrences * (totalRange / bucketWidth)` – tím jsou zvýhodněny intervaly s mnoha výskyty v úzkém rozsahu
      2. Hodnoty jsou pak normalizovány tak, aby jejich součet byl 100 napříč všemi intervaly
      3. Prázdné intervaly mají vždy relativeFrequency = 0
  - **`requested`**:
    - obsahuje `true`, pokud dotaz neobsahoval žádné omezení [attributeBetween](../filtering/comparable.md#atribut-mezi)
      nebo [priceBetween](../filtering/price.md#cena-v-rozmezí)
    - obsahuje `true`, pokud dotaz obsahoval omezení [attributeBetween](../filtering/comparable.md#atribut-mezi)
      nebo [priceBetween](../filtering/price.md#cena-v-rozmezí) pro konkrétní atribut / cenu
      a threshold intervalu leží v rozsahu (včetně) tohoto omezení
    - obsahuje `false` v ostatních případech

<Note type="info">

Identita `overallCount = součet výskytů v intervalech` vždy platí. U histogramů postavených nad **zdrojem typu rozsah** (pouze referenční histogramy — viz [referenční histogramy](../../use/schema.md#histogramy-referencí)), může jeden prvek spadat do více intervalů najednou, takže `overallCount` může přesáhnout počet unikátních přispívajících prvků. U každého histogramu nad skalárním zdrojem jsou tyto hodnoty shodné.

`relativeFrequency` zůstává platnou vizualizací v rozsahu 0–100 v obou případech — je to poměr `occurrences` ku `overallCount`
(standardní intervaly stále dávají součet 100, equalizované jsou také normalizovány na 100), a u zdroje typu rozsah se v čitateli i jmenovateli započítávají stejná překrytí. Jediný rozdíl je interpretační: výška intervalu u zdroje typu rozsah odráží podíl **(prvek × překrytý interval)**, nikoli podíl unikátních prvků, takže pozice pokryté více překrývajícími se rozsahy se zobrazí úměrně vyšší.

</Note>

## Histogram atributu

<LS to="e,j,r,c">

```evitaql-syntax
attributeHistogram(
    argument:int!,
    argument:enum(STANDARD|OPTIMIZED|EQUALIZED|EQUALIZED_OPTIMIZED),
    argument:string+
)
```

<dl>
    <dt>argument:int!</dt>
    <dd>
        počet sloupců (intervalů) v histogramu; počet by měl být zvolen tak, aby se histogram dobře vešel
        do dostupného prostoru na obrazovce
    </dd>
    <dt>argument:enum(STANDARD|OPTIMIZED|EQUALIZED|EQUALIZED_OPTIMIZED)</dt>
    <dd>
        Chování výpočtu histogramu:
        <ul>
            <li><strong>STANDARD</strong> (výchozí): Vrací přesně požadovaný počet intervalů se stejnou šířkou v celém rozsahu hodnot.</li>
            <li><strong>Optimalizováno</strong>: Vrací méně intervalů, pokud jsou data řídká, aby se předešlo velkým mezerám (prázdným intervalům).</li>
            <li><strong>Vyrovnáno</strong>: Vrací přesně požadovaný počet intervalů, ale hranice intervalů určuje podle kumulativní frekvence tak, aby každý interval pokrýval přibližně stejný podíl záznamů. To zlepšuje uživatelský zážitek při výrazně nevyvážených datech.</li>
            <li><strong>EQUALIZED_OPTIMIZED</strong>: Kombinuje equalizované intervaly s optimalizací pro snížení počtu prázdných intervalů.</li>
        </ul>
    </dd>
    <dt>argument:string+</dt>
    <dd>
        jeden nebo více názvů [atributů entity](../../use/schema.md#atributy), jejichž hodnoty budou použity pro generování histogramů
    </dd>
</dl>

</LS>

<LS to="e,j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/extraResult/AttributeHistogram.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/ExtraResults/AttributeHistogram.cs</SourceClass></LS>
<LS to="g,r">histogram atributu</LS>
lze vypočítat z jakéhokoliv [filtrovatelného atributu](../../use/data-model.md#atributy-unikátní-filtrovatelné-řaditelné-lokalizované),
jehož typ je číselný. Histogram je počítán pouze z atributů prvků, které odpovídají aktuální povinné části filtru. Výběry rozsahů na atributech umístěných uvnitř
[`userFilter`](../filtering/behavioral.md#uživatelský-filtr) — jak
[`attributeBetween`](../filtering/comparable.md#atribut-mezi), tak
[`histogramHaving`](../filtering/references.md#histogram-having) — jsou **vyloučeny** ze základny histogramu atributu, aby se jezdec nesmršťoval pod vlastní rukojetí při jeho posouvání uživatelem. Výběry faset
([`facetHaving`](../filtering/references.md#facet-having)) a cenový rozsah
([`priceBetween`](../filtering/price.md#cena-v-rozmezí)) zůstávají aplikovány, takže histogram odráží rozsah hodnot atributu, které jsou skutečně dosažitelné při aktuálním výběru faset a cen uživatele. Důvod a příklad jsou popsány v části [Uvolnění základny](#uvolnění-základny--slidery-se-nesmršťují-pod-vlastní-rukojetí) níže.

Pro ukázku použití histogramu použijeme následující příklad:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Histogram atributů nad atributy `width` a `height`](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram.evitaql)

</SourceCodeTabs>

Zjednodušený výsledek vypadá takto:

<MDInclude sourceVariable="extraResults.AttributeHistogram">[Výsledek histogramu atributů `width` a `height`](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram.evitaql.string.md)</MDInclude>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek histogramu atributů `width` a `height` ve formátu JSON

</NoteTitle>

Výsledek histogramu ve formátu JSON je trochu obsáhlejší, ale stále docela čitelný:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.AttributeHistogram">[Výsledek histogramu atributů `width` a `height` ve formátu JSON](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.attributeHistogram">[Výsledek histogramu atributů `width` a `height` ve formátu JSON](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.attributeHistogram">[Výsledek histogramu atributů `width` a `height` ve formátu JSON](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram.rest.json.md)</MDInclude>

</LS>

</Note>

### Optimalizace obsahu histogramu atributu

Během uživatelského testování jsme zjistili, že histogramy s řídkými daty nejsou příliš užitečné. Kromě toho, že nevypadají dobře, jsou často hůře ovladatelné widgetem, který histogram řídí a snaží se držet hranic intervalů. Proto jsme zavedli nový režim výpočtu histogramu – `OPTIMIZED`. V tomto režimu se algoritmus výpočtu histogramu snaží snížit počet intervalů, pokud jsou data řídká a mezi intervaly by byly velké mezery (prázdné intervaly). Výsledkem jsou kompaktnější histogramy, které poskytují lepší uživatelský zážitek.

Pro ukázku optimalizace histogramu použijeme následující příklad:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Optimalizovaný histogram atributu nad atributem `width`](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram-optimized.evitaql)

</SourceCodeTabs>

Zjednodušený výsledek vypadá takto:

<MDInclude sourceVariable="extraResults.AttributeHistogram">[Výsledek optimalizovaného histogramu atributu `width`](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram-optimized.evitaql.string.md)</MDInclude>

<Note type="info">

<NoteTitle toggles="true">

##### Optimalizovaný výsledek histogramu atributů `width` a `height` ve formátu JSON

</NoteTitle>

Optimalizovaný výsledek histogramu ve formátu JSON je trochu obsáhlejší, ale stále docela čitelný:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.AttributeHistogram">[Výsledek optimalizovaného histogramu atributu `width`](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram-optimized.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.attributeHistogram">[Výsledek optimalizovaného histogramu atributu `width`](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram-optimized.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.attributeHistogram">[Výsledek optimalizovaného histogramu atributu `width`](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram-optimized.rest.json.md)</MDInclude>

</LS>

</Note>

Jak vidíte, počet intervalů byl upraven tak, aby odpovídal datům, na rozdíl od výchozího chování.

### Equalizace histogramu atributu

Standardní histogramy používají intervaly stejné šířky v celém rozsahu hodnot. To funguje dobře u rovnoměrně rozložených dat, ale může být problematické, pokud jsou data silně vychýlená. Například pokud 90 % produktů má šířku mezi 10–50 cm a pouze 10 % mezi 50–500 cm, intervaly stejné šířky by natlačily většinu produktů do prvních několika intervalů a mnoho intervalů v horním rozsahu by zůstalo prázdných.

Chování **EQUALIZED** toto řeší tím, že hranice intervalů určuje podle kumulativní frekvence. Místo rozdělení rozsahu hodnot na stejné intervaly rozděluje *záznamy* do přibližně stejně velkých skupin. Každý interval tak pokrývá přibližně stejný počet položek, což poskytuje vyváženější a informativnější histogram.

Tato technika je inspirována [equalizací histogramu v obrazovém zpracování](https://www.howdoi.me/blog/slider-scale.html), upravená pro UX filtračních sliderů. Algoritmus:

1. Spočítá celkovou váhu (součet všech počtů záznamů)
2. Spočítá kumulativní frekvenci pro každou unikátní hodnotu
3. Umístí hranice intervalů v bodech, kde kumulativní frekvence překročí práh (i/početIntervalů)
4. Spočítá skutečné výskyty v každém výsledném intervalu

Pro ukázku equalizovaného histogramu použijeme následující příklad:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Equalizovaný histogram atributu nad atributem `width`](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram-equalized.evitaql)

</SourceCodeTabs>

Zjednodušený výsledek vypadá takto:

<MDInclude sourceVariable="extraResults.AttributeHistogram">[Výsledek equalizovaného histogramu atributu `width`](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram-equalized.evitaql.string.md)</MDInclude>

<Note type="info">

<NoteTitle toggles="true">

##### Equalizovaný výsledek histogramu atributu `width` ve formátu JSON

</NoteTitle>

Equalizovaný výsledek histogramu ve formátu JSON je trochu obsáhlejší, ale stále docela čitelný:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.AttributeHistogram">[Výsledek equalizovaného histogramu atributu `width`](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram-equalized.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.attributeHistogram">[Výsledek equalizovaného histogramu atributu `width`](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram-equalized.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.attributeHistogram">[Výsledek equalizovaného histogramu atributu `width`](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram-equalized.rest.json.md)</MDInclude>

</LS>

</Note>

Jak vidíte, na rozdíl od standardních histogramů, kde jsou šířky intervalů stejné, equalizované histogramy upravují šířky intervalů tak, aby rozložily záznamy rovnoměrněji. Díky tomu je histogram užitečnější pro filtrování při nevyváženém rozložení dat.

## Histogram cen

<LS to="e,j,r,c">

```evitaql-syntax
priceHistogram(
    argument:int!,
    argument:enum(STANDARD|OPTIMIZED|EQUALIZED|EQUALIZED_OPTIMIZED)
)
```

<dl>
    <dt>argument:int!</dt>
    <dd>
        počet sloupců (intervalů) v histogramu; počet by měl být zvolen tak, aby se histogram dobře vešel
        do dostupného prostoru na obrazovce
    </dd>
    <dt>argument:enum(STANDARD|OPTIMIZED|EQUALIZED|EQUALIZED_OPTIMIZED)</dt>
    <dd>
        Chování výpočtu histogramu:
        <ul>
            <li><strong>STANDARD</strong> (výchozí): Vrací přesně požadovaný počet intervalů se stejnou šířkou v celém rozsahu hodnot.</li>
            <li><strong>Optimalizováno</strong>: Vrací méně intervalů, pokud jsou data řídká, aby se předešlo velkým mezerám (prázdným intervalům).</li>
            <li><strong>Vyrovnáno</strong>: Vrací přesně požadovaný počet intervalů, ale hranice intervalů určuje podle kumulativní frekvence tak, aby každý interval pokrýval přibližně stejný podíl záznamů. To zlepšuje uživatelský zážitek při výrazně nevyvážených datech.</li>
            <li><strong>EQUALIZED_OPTIMIZED</strong>: Kombinuje equalizované intervaly s optimalizací pro snížení počtu prázdných intervalů.</li>
        </ul>
    </dd>
</dl>

</LS>

<LS to="e,j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/extraResult/PriceHistogram.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/ExtraResults/PriceHistogram.cs</SourceClass></LS>
<LS to="g,r">histogram cen</LS>
je počítán z [prodejní ceny](../filtering/price.md). Pouze
[`priceBetween`](../filtering/price.md#cena-v-rozmezí) umístěný uvnitř
[`userFilter`](../filtering/behavioral.md#uživatelský-filtr) je **vyloučen** ze základny histogramu cen, aby se jezdec ceny nesmršťoval pod vlastní rukojetí při jeho posouvání uživatelem. Výběry rozsahů atributů
([`attributeBetween`](../filtering/comparable.md#atribut-mezi),
[`histogramHaving`](../filtering/references.md#histogram-having)) a výběry faset
([`facetHaving`](../filtering/references.md#facet-having)) zůstávají aplikovány, takže histogram cen odráží ceny, které jsou skutečně dosažitelné při aktuálním výběru rozsahu atributů a faset uživatele.

Požadavek [`priceType`](price.md#typ-ceny) určuje zdrojovou vlastnost ceny pro výpočet histogramu. Pokud není zadán, histogram vizualizuje cenu s daní.

### Granularita histogramu cen a zpracování vnitřních záznamů {#price-histogram-granularity}

Histogram odpovídá na otázku *„jaké ceny jsou dosažitelné v kandidátní množině?“* Odpověď závisí na tom, jak kolekce zpracovává vnitřní záznamy (`PriceInnerRecordHandling`), protože to určuje, co představuje jeden datový bod ceny:

| Zpracování vnitřních záznamů | Datový bod histogramu na entitu |
|------------------------------|---------------------------------|
| `NONE`                       | Jeden — prodejní cena entity |
| `SUM`                        | Jeden — součtová cena všech vnitřních záznamů |
| `LOWEST_PRICE`               | **Jeden na každé id vnitřního záznamu** — vítězná cena každé varianty |

Pravidlo se aplikuje **na každou entitu**, podle jejího vlastního nastavení. Kandidátní množina, která míchá režimy zpracování — například kategorie obsahující jak jednoduché produkty, tak master/variant produkty — tedy přispívá sjednocením obou pravidel: každý master s `LOWEST_PRICE` se rozvine na jeden datový bod za variantu, zatímco jeho sousedé s `NONE` a `SUM` přispějí jednou prodejní cenou každý.

Pro ukázku použití histogramu použijeme následující příklad:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Histogram cen](/documentation/user/en/query/requirements/examples/histogram/price-histogram.evitaql)

</SourceCodeTabs>

Zjednodušený výsledek vypadá takto:

<MDInclude sourceVariable="extraResults.PriceHistogram">[Výsledek histogramu cen](/documentation/user/en/query/requirements/examples/histogram/price-histogram.evitaql.string.md)</MDInclude>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek histogramu cen ve formátu JSON

</NoteTitle>

Výsledek histogramu ve formátu JSON je trochu obsáhlejší, ale stále docela čitelný:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.PriceHistogram">[Výsledek histogramu cen ve formátu JSON](/documentation/user/en/query/requirements/examples/histogram/price-histogram.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.priceHistogram">[Výsledek histogramu cen ve formátu JSON](/documentation/user/en/query/requirements/examples/histogram/price-histogram.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.priceHistogram">[Výsledek histogramu cen ve formátu JSON](/documentation/user/en/query/requirements/examples/histogram/price-histogram.rest.json.md)</MDInclude>

</LS>

</Note>

### Optimalizace obsahu histogramu cen

Během uživatelského testování jsme zjistili, že histogramy s řídkými daty nejsou příliš užitečné. Kromě toho, že nevypadají dobře, jsou často hůře ovladatelné widgetem, který histogram řídí a snaží se držet hranic intervalů. Proto jsme zavedli nový režim výpočtu histogramu – `OPTIMIZED`. V tomto režimu se algoritmus výpočtu histogramu snaží snížit počet intervalů, pokud jsou data řídká a mezi intervaly by byly velké mezery (prázdné intervaly). Výsledkem jsou kompaktnější histogramy, které poskytují lepší uživatelský zážitek.

Pro ukázku optimalizace histogramu použijeme následující příklad:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Optimalizovaný histogram cen](/documentation/user/en/query/requirements/examples/histogram/price-histogram-optimized.evitaql)

</SourceCodeTabs>

Zjednodušený výsledek vypadá takto:

<MDInclude sourceVariable="extraResults.PriceHistogram">[Výsledek optimalizovaného histogramu cen](/documentation/user/en/query/requirements/examples/histogram/price-histogram-optimized.evitaql.string.md)</MDInclude>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek optimalizovaného histogramu cen ve formátu JSON

</NoteTitle>

Optimalizovaný výsledek histogramu ve formátu JSON je trochu obsáhlejší, ale stále docela čitelný:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.PriceHistogram">[Výsledek optimalizovaného histogramu cen](/documentation/user/en/query/requirements/examples/histogram/price-histogram-optimized.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.priceHistogram">[Výsledek optimalizovaného histogramu cen](/documentation/user/en/query/requirements/examples/histogram/price-histogram-optimized.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.priceHistogram">[Výsledek optimalizovaného histogramu cen](/documentation/user/en/query/requirements/examples/histogram/price-histogram-optimized.rest.json.md)</MDInclude>

</LS>

</Note>

Jak vidíte, počet intervalů byl upraven tak, aby odpovídal datům, na rozdíl od výchozího chování.

### Equalizace histogramu cen

Stejně jako u histogramů atributů, standardní histogramy cen používají intervaly stejné šířky, což může být problematické u vychýlených rozložení cen. Například na tržišti, kde většina položek stojí 10–50 $ a jen několik luxusních položek 500–5000 $, by intervaly stejné šířky zbytečně zabíraly místo na slideru v drahé (ale řídké) části.

Chování **EQUALIZED** u histogramů cen určuje hranice intervalů podle kumulativní frekvence, takže každý interval pokrývá přibližně stejný počet produktů. To poskytuje lepší zážitek při filtrování, zejména u e-commerce katalogů s rozmanitými cenovými rozsahy.

Pro ukázku equalizovaného histogramu cen použijeme následující příklad:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Equalizovaný histogram cen](/documentation/user/en/query/requirements/examples/histogram/price-histogram-equalized.evitaql)

</SourceCodeTabs>

Zjednodušený výsledek vypadá takto:

<MDInclude sourceVariable="extraResults.PriceHistogram">[Výsledek equalizovaného histogramu cen](/documentation/user/en/query/requirements/examples/histogram/price-histogram-equalized.evitaql.string.md)</MDInclude>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek equalizovaného histogramu cen ve formátu JSON

</NoteTitle>

Equalizovaný výsledek histogramu ve formátu JSON je trochu obsáhlejší, ale stále docela čitelný:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.PriceHistogram">[Výsledek equalizovaného histogramu cen](/documentation/user/en/query/requirements/examples/histogram/price-histogram-equalized.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.priceHistogram">[Výsledek equalizovaného histogramu cen](/documentation/user/en/query/requirements/examples/histogram/price-histogram-equalized.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.priceHistogram">[Výsledek equalizovaného histogramu cen](/documentation/user/en/query/requirements/examples/histogram/price-histogram-equalized.rest.json.md)</MDInclude>

</LS>

</Note>

Jak vidíte, hranice intervalů jsou nastaveny tak, aby produkty byly rozloženy rovnoměrněji po celé délce slideru.

## Uvolnění základny — slidery se nesmršťují pod vlastní rukojetí

Každý histogram odpovídá na „co kdyby“ otázku: *jaký rozsah hodnot by byl stále dosažitelný, kdybych pustil tento slider a posunul ho na krajní hodnoty?* Histogram, jehož `[min, max]` by se zmenšoval pokaždé, když uživatel posune slider dovnitř, by uživatele uvěznil v zužujícím se rozsahu — každý posun by zmenšil prostor pro další posun a návrat k širšímu rozsahu by byl nemožný bez resetování slideru na celý rozsah. Abychom tomu zabránili, základna `[min, max]` každého histogramu musí **skrýt uživatelovy vlastní výběry rozsahu**, ale zároveň respektovat výběry provedené na ostatních filtračních plochách (tlačítka faset, cenový slider atd.).

### Jak evitaDB uplatňuje uvolnění základny

evitaDB zařazuje každé dítě [`userFilter`](../filtering/behavioral.md#uživatelský-filtr) do jedné ze tří vzájemně se vylučujících *filtračních ploch*:

1. **Slidery rozsahu atributů** — [`attributeBetween`](../filtering/comparable.md#atribut-mezi) a
   [`histogramHaving`](../filtering/references.md#histogram-having). Ty řídí histogramy atributů, jak na běžných atributech entity, tak na referenčních úrovních histogramů.
2. **Výběry faset** — [`facetHaving`](../filtering/references.md#facet-having). Ty řídí souhrn faset a jeho výpočty dopadů.
3. **Cenový rozsah** — [`priceBetween`](../filtering/price.md#cena-v-rozmezí). Ten řídí histogram cen.

Když je vypočítána projekce extra-výsledku (histogram atributu, dopad souhrnu faset, histogram cen), evitaDB odstraňuje **pouze tu plochu, ke které projekce patří** a ostatní dvě ponechává aplikované. Hlavní stránka entity vrácená dotazem je stále zúžena **všemi třemi** plochami — uvolnění základny se týká výhradně rozsahů `[min, max]` a rozložení intervalů v projekcích extra-výsledků.

### Praktický příklad

Předpokládejme, že uživatel prohlíží `Product` a provedl tři nezávislé výběry:

```evitaql
userFilter(
    facetHaving("brand", entityHaving(attributeEquals("code", "amazon"))),
    attributeBetween("height", 50, 120),
    priceBetween(100, 500)
)
```

a dotaz také požaduje `attributeHistogram(20, "height", "width")`, `priceHistogram(20)` a souhrn faset s `IMPACT`. evitaDB vypočítá čtyři základny v jednom průchodu:

| Vlastní výpočet | Co základna skrývá | Co základna ponechává aplikované |
|------------------|---------------------|----------------------------------|
| **histogram výšky** | každý slider rozsahu atributu — `attributeBetween("height", …)` a každý další `attributeBetween` nebo `histogramHaving` ve stejném `userFilter` | `facetHaving("brand", …)`, `priceBetween(100, 500)` |
| **histogram šířky** | totéž — každý slider rozsahu atributu je odstraněn pro jakýkoli histogram atributu v dotazu | `facetHaving("brand", …)`, `priceBetween(100, 500)` |
| **dopad faset** pro jiné značky | každý výběr `facetHaving` | `attributeBetween("height", …)`, `priceBetween(100, 500)` |
| **histogram cen** | `priceBetween(100, 500)` | `facetHaving("brand", …)`, `attributeBetween("height", …)` |

To také znamená, že **přidání druhého slideru na stejné filtrační ploše nesmršťuje ten první**: pokud dotaz obsahuje jak `attributeBetween("height", 50, 120)`, tak `attributeBetween("width", 10, 40)`, každý histogram atributu je vypočítán s *oběma* slidery rozsahu odstraněnými, takže žádný slider nesmršťuje `[min, max]` toho druhého při posouvání.

### Doporučené nosiče rozsahu

Vyberte dítě `userFilter`, které odpovídá místu, kde slider žije — každý z nich je evitaDB rozpoznán jako nosič rozsahu a je odstraněn z příslušné základny histogramu:

| Slider je na … | Doporučené dítě `userFilter` |
|----------------|------------------------------|
| běžný atribut entity (`Product.width`, `Product.height`, …) | [`attributeBetween`](../filtering/comparable.md#atribut-mezi) |
| referenční úrovni histogramu (např. `parameterValues.height` na `Product`) | [`histogramHaving`](../filtering/references.md#histogram-having) — preferovaný nosič pro referenční histogramy; také rozlišuje mezi více histogramy na stejné referenci |
| prodejní ceně | [`priceBetween`](../filtering/price.md#cena-v-rozmezí) |
| výběru fasety | [`facetHaving`](../filtering/references.md#facet-having) |

Běžný [`referenceHaving`](../filtering/references.md#reference-having) **není** akceptován uvnitř `userFilter` — nemá sliderovou sémantiku a neúčastní se uvolnění základny. Pro slidery na referencích použijte [`histogramHaving`](../filtering/references.md#histogram-having).