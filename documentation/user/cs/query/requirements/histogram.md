---
title: Histogram
date: '7.11.2023'
perex: Histogramy hrají klíčovou roli v parametrickém filtrování v e-commerce tím, že vizuálně zobrazují rozložení produktových atributů, což zákazníkům umožňuje efektivně upravovat kritéria vyhledávání. Usnadňují interaktivnější a přesnější filtrování, kdy uživatelé mohou měnit rozsah vlastností, jako je cena nebo velikost, na základě skutečné dostupnosti položek.
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
commit: d74b2be9a27e5567316e0f6e0d3b160da262d2c2
translated: true
---
Ve skutečnosti existuje jen několik případů použití histogramů na e-commerce webech. Nejčastějším z nich je cenový histogram, který slouží k filtrování produktů podle ceny. Příklad takového histogramu můžete vidět na webu Booking.com:

![Booking.com price histogram filter](../../../en/query/requirements/assets/price-histogram.png "Booking.com price histogram filter")

Je škoda, že histogram není využíván častěji, protože jde o velmi užitečný nástroj pro získání přehledu o rozložení hodnot atributů s vysokou kardinalitou, jako je hmotnost, výška, šířka a podobně.

Datová struktura histogramu je optimalizovaná pro vykreslování na frontendu. Obsahuje následující pole:

- **`min`** – minimální hodnota atributu v aktuálním kontextu filtru
- **`max`** – maximální hodnota atributu v aktuálním kontextu filtru
- **`overallCount`** – počet prvků, jejichž hodnota atributu spadá do některého z bucketů (v podstatě součet všech výskytů v bucketech)
- **`buckets`** – *seřazené* pole bucketů, z nichž každý obsahuje následující pole:
  - **`threshold`** – minimální hodnota atributu v bucketu, maximální hodnota je threshold následujícího bucketu (nebo `max` pro poslední bucket)
  - **`occurrences`** – počet prvků, jejichž hodnota atributu spadá do daného bucketu
  - **`relativeFrequency`** – hodnota používaná pro vizualizaci výšky bucketu v UI (škála 0–100):
    - Pro **standardní histogramy**: procento z celkového počtu výskytů, vypočítané jako `(occurrences / overallCount) * 100`
    - Pro **ekvalizované histogramy**: normalizovaná hustota hodnot, která zohledňuje jak počet výskytů, tak šířku bucketu:
      1. Hrubá frekvence se vypočítá jako `occurrences * (totalRange / bucketWidth)` – tím jsou zvýhodněny buckety s mnoha výskyty v úzkém rozsahu
      2. Hodnoty jsou následně normalizovány tak, aby součet všech bucketů byl 100
      3. Prázdné buckety mají vždy relativeFrequency = 0
  - **`requested`**:
    - obsahuje `true`, pokud dotaz neobsahoval žádné omezení [attributeBetween](../filtering/comparable.md#atribut-mezi)
      nebo [priceBetween](../filtering/price.md#cena-v-rozmezí)
    - obsahuje `true`, pokud dotaz obsahoval omezení [attributeBetween](../filtering/comparable.md#atribut-mezi)
      nebo [priceBetween](../filtering/price.md#cena-v-rozmezí) pro konkrétní atribut / cenu
      a threshold bucketu leží v rozsahu (včetně krajních hodnot) tohoto omezení
    - jinak obsahuje `false`

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
        počet sloupců (bucketů) v histogramu; číslo by mělo být zvoleno tak, aby se histogram dobře vešel
        do dostupného prostoru na obrazovce
    </dd>
    <dt>argument:enum(STANDARD|OPTIMIZED|EQUALIZED|EQUALIZED_OPTIMIZED)</dt>
    <dd>
        Chování výpočtu histogramu:
        <ul>
            <li><strong>STANDARD</strong> (výchozí): Vrací přesně požadovaný počet bucketů se stejně širokými intervaly v celém rozsahu hodnot.</li>
            <li><strong>OPTIMIZED</strong>: Vrací méně bucketů, pokud jsou data řídká, aby se předešlo velkým mezerám (prázdným bucketům).</li>
            <li><strong>EQUALIZED</strong>: Vrací přesně požadovaný počet bucketů, ale hranice bucketů jsou určeny na základě kumulativního rozložení četnosti, takže každý bucket pokrývá přibližně stejnou část všech záznamů. To poskytuje lepší uživatelský zážitek při silně zkreslených datech.</li>
            <li><strong>EQUALIZED_OPTIMIZED</strong>: Kombinuje rozdělení EQUALIZED s optimalizací pro snížení počtu prázdných bucketů.</li>
        </ul>
    </dd>
    <dt>argument:string+</dt>
    <dd>
        jeden nebo více názvů [atributů entity](../../use/schema.md#atributy), jejichž hodnoty budou použity pro generování
        histogramů
    </dd>
</dl>

</LS>

<LS to="e,j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/extraResult/AttributeHistogram.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/ExtraResults/AttributeHistogram.cs</SourceClass></LS>
<LS to="g,r">attribute histogram</LS>
lze vypočítat z libovolného [filtrovatelného atributu](../../use/data-model.md#atributy-unikátní-filtrovatelné-řaditelné-lokalizované),
jehož typ je číselný. Histogram je počítán pouze z atributů prvků, které odpovídají aktuální povinné
části filtru. Intervalové restrikce – tj. [`attributeBetween`](../filtering/comparable.md#atribut-mezi)
a [`priceBetween`](../filtering/price.md#cena-v-rozmezí) v části [`userFilter`](../filtering/behavioral.md#uživatelský-filtr)
jsou pro účely výpočtu histogramu vyloučeny. Pokud by tomu tak nebylo, uživatel by při zužování filtrovaného rozsahu
na základě výsledků histogramu byl tlačen do stále užšího a užšího rozsahu a nakonec do slepé uličky.

Pro ukázku použití histogramu použijeme následující příklad:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Histogram atributů nad `width` a `height`](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram.evitaql)

</SourceCodeTabs>

Zjednodušený výsledek vypadá takto:

<MDInclude sourceVariable="extraResults.AttributeHistogram">[Výsledek histogramu atributů `width` a `height`](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram.evitaql.string.md)</MDInclude>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek histogramu atributů `width` a `height` ve formátu JSON

</NoteTitle>

Výsledek histogramu ve formátu JSON je trochu obsáhlejší, ale stále poměrně čitelný:

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

Během uživatelského testování jsme zjistili, že histogramy s řídkými daty nejsou příliš užitečné. Kromě toho, že nevypadají dobře,
jsou často obtížně ovladatelné pomocí widgetu, který histogram ovládá a snaží se držet prahových hodnot intervalů.
Proto jsme zavedli nový režim výpočtu histogramu – `OPTIMIZED`. V tomto režimu se algoritmus výpočtu histogramu snaží
snížit počet intervalů, pokud jsou data řídká a mezi intervaly by byly velké mezery (prázdné intervaly).
Výsledkem jsou kompaktnější histogramy, které poskytují lepší uživatelský zážitek.

Pro ukázku optimalizace histogramu použijeme následující příklad:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Optimalizovaný histogram atributu nad atributem `width`](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram-optimized.evitaql)

</SourceCodeTabs>

Zjednodušený výsledek vypadá takto:

<MDInclude sourceVariable="extraResults.AttributeHistogram">[Výsledek optimalizovaného histogramu atributu `width`](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram-optimized.evitaql.string.md)</MDInclude>

<Note type="info">

<NoteTitle toggles="true">

##### Optimalizovaný výsledek histogramu atributů `width` a `height` ve formátu JSON

</NoteTitle>

Optimalizovaný výsledek histogramu ve formátu JSON je trochu obsáhlejší, ale stále poměrně čitelný:

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

Jak můžete vidět, počet intervalů byl upraven tak, aby odpovídal datům, na rozdíl od výchozího chování.

### Equalizace histogramu atributu

Standardní histogramy používají stejně široké intervaly v celém rozsahu hodnot. To funguje dobře u rovnoměrně rozložených dat, ale může být problematické, když jsou data silně zkreslená. Například pokud 90 % produktů má šířku mezi 10–50 cm a pouze 10 % mezi 50–500 cm, stejně široké intervaly by natlačily většinu produktů do několika prvních intervalů, zatímco v horním rozsahu by zůstalo mnoho prázdných intervalů.

Chování **EQUALIZED** toto řeší tím, že hranice intervalů určuje na základě kumulativního rozdělení četnosti. Místo dělení hodnotového rozsahu na stejně velké intervaly rozděluje *záznamy* do přibližně stejně velkých skupin. Každý interval tak pokrývá přibližně stejný počet položek, což poskytuje vyváženější a informativnější histogram.

Tato technika je inspirována [equalizací histogramu v obrazovém zpracování](https://www.howdoi.me/blog/slider-scale.html), upravenou pro UX posuvníků filtrů. Algoritmus:

1. Spočítá celkovou váhu (součet všech počtů záznamů)
2. Spočítá kumulativní četnost pro každou unikátní hodnotu
3. Umístí hranice intervalů na body, kde kumulativní četnost překročí práh (i/bucketCount)
4. Spočítá skutečný výskyt v každém výsledném intervalu

Pro demonstraci equalizovaného histogramu použijeme následující příklad:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Equalizovaný histogram atributu `width`](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram-equalized.evitaql)

</SourceCodeTabs>

Zjednodušený výsledek vypadá takto:

<MDInclude sourceVariable="extraResults.AttributeHistogram">[Výsledek equalizovaného histogramu atributu `width`](/documentation/user/en/query/requirements/examples/histogram/attribute-histogram-equalized.evitaql.string.md)</MDInclude>

<Note type="info">

<NoteTitle toggles="true">

##### Equalizovaný výsledek histogramu atributu `width` ve formátu JSON

</NoteTitle>

Výsledek equalizovaného histogramu ve formátu JSON je o něco obsáhlejší, ale stále poměrně čitelný:

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

Jak vidíte, na rozdíl od standardních histogramů, kde jsou šířky intervalů stejné, equalizované histogramy upravují šířky intervalů tak, aby rozložily záznamy rovnoměrněji. Díky tomu je histogram užitečnější pro filtrování v případech, kdy jsou data zkreslená.

## Cenový histogram

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
        počet sloupců (košů) v histogramu; počet by měl být zvolen tak, aby se histogram dobře vešel
        do dostupného prostoru na obrazovce
    </dd>
    <dt>argument:enum(STANDARD|OPTIMIZED|EQUALIZED|EQUALIZED_OPTIMIZED)</dt>
    <dd>
        Chování výpočtu histogramu:
        <ul>
            <li><strong>STANDARD</strong> (výchozí): Vrací přesně požadovaný počet košů se stejně širokými intervaly v celém rozsahu hodnot.</li>
            <li><strong>OPTIMIZED</strong>: Vrací méně košů, pokud jsou data řídká, aby se předešlo velkým mezerám (prázdným košům).</li>
            <li><strong>EQUALIZED</strong>: Vrací přesně požadovaný počet košů, ale hranice košů určuje podle kumulativního rozložení četností, takže každý koš pokrývá přibližně stejný podíl záznamů. To poskytuje lepší uživatelský zážitek při silně zkreslených datech.</li>
            <li><strong>EQUALIZED_OPTIMIZED</strong>: Kombinuje equalizované rozdělení košů s optimalizací pro snížení počtu prázdných košů.</li>
        </ul>
    </dd>
</dl>

</LS>

<LS to="e,j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/extraResult/PriceHistogram.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/ExtraResults/PriceHistogram.cs</SourceClass></LS>
<LS to="g,r">cenový histogram</LS>
je počítán z [prodejní ceny](../filtering/price.md). Intervalové restrikce – tj.
[`attributeBetween`](../filtering/comparable.md#atribut-mezi) a [`priceBetween`](../filtering/price.md#cena-v-rozmezí)
v části [`userFilter`](../filtering/behavioral.md#uživatelský-filtr) jsou pro účely výpočtu histogramu ignorovány.
Pokud by tomu tak nebylo, uživatel by při zužování filtrovaného rozsahu podle výsledků histogramu byl veden do
stále užšího a užšího rozsahu a nakonec by skončil ve slepé uličce.

Požadavek [`priceType`](price.md#typ-ceny) určuje zdrojovou vlastnost ceny pro výpočet histogramu. Pokud není
požadavek zadán, histogram zobrazuje cenu s daní.

Pro ukázku použití histogramu použijeme následující příklad:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Cenový histogram](/documentation/user/en/query/requirements/examples/histogram/price-histogram.evitaql)

</SourceCodeTabs>

Zjednodušený výsledek vypadá takto:

<MDInclude sourceVariable="extraResults.PriceHistogram">[Výsledek cenového histogramu](/documentation/user/en/query/requirements/examples/histogram/price-histogram.evitaql.string.md)</MDInclude>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek cenového histogramu ve formátu JSON

</NoteTitle>

Výsledek histogramu ve formátu JSON je trochu obsáhlejší, ale stále poměrně čitelný:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.PriceHistogram">[Výsledek cenového histogramu ve formátu JSON](/documentation/user/en/query/requirements/examples/histogram/price-histogram.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.priceHistogram">[Výsledek cenového histogramu ve formátu JSON](/documentation/user/en/query/requirements/examples/histogram/price-histogram.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.priceHistogram">[Výsledek cenového histogramu ve formátu JSON](/documentation/user/en/query/requirements/examples/histogram/price-histogram.rest.json.md)</MDInclude>

</LS>

</Note>

### Optimalizace obsahu cenového histogramu

Během uživatelského testování jsme zjistili, že histogramy s řídkými daty nejsou příliš užitečné. Kromě toho, že nevypadají dobře,
jsou často obtížně ovladatelné pomocí widgetu, který histogram ovládá a snaží se držet prahových hodnot intervalů.
Proto jsme zavedli nový režim výpočtu histogramu – `OPTIMIZED`. V tomto režimu se algoritmus výpočtu histogramu snaží
snížit počet intervalů, pokud jsou data řídká a mezi intervaly by byly velké mezery (prázdné intervaly).
Výsledkem jsou kompaktnější histogramy, které poskytují lepší uživatelský zážitek.

Pro ukázku optimalizace histogramu použijeme následující příklad:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Optimalizovaný cenový histogram](/documentation/user/en/query/requirements/examples/histogram/price-histogram-optimized.evitaql)

</SourceCodeTabs>

Zjednodušený výsledek vypadá takto:

<MDInclude sourceVariable="extraResults.PriceHistogram">[Výsledek optimalizovaného cenového histogramu](/documentation/user/en/query/requirements/examples/histogram/price-histogram-optimized.evitaql.string.md)</MDInclude>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek optimalizovaného cenového histogramu ve formátu JSON

</NoteTitle>

Výsledek optimalizovaného histogramu ve formátu JSON je o něco obsáhlejší, ale stále poměrně čitelný:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.PriceHistogram">[Výsledek optimalizovaného cenového histogramu](/documentation/user/en/query/requirements/examples/histogram/price-histogram-optimized.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.priceHistogram">[Výsledek optimalizovaného cenového histogramu](/documentation/user/en/query/requirements/examples/histogram/price-histogram-optimized.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.priceHistogram">[Výsledek optimalizovaného cenového histogramu](/documentation/user/en/query/requirements/examples/histogram/price-histogram-optimized.rest.json.md)</MDInclude>

</LS>

</Note>

Jak můžete vidět, počet intervalů byl upraven tak, aby odpovídal datům, na rozdíl od výchozího chování.

### Vyrovnání cenového histogramu

Stejně jako u histogramů atributů používají standardní cenové histogramy intervaly se stejnou šířkou, což může být problematické u zkreslených rozložení cen. Například na tržišti, kde většina položek stojí 10–50 dolarů, ale několik luxusních položek stojí 500–5000 dolarů, by intervaly se stejnou šířkou zbytečně zabíraly místo na posuvníku v drahém (ale řídkém) konci.

Chování **EQUALIZED** u cenových histogramů určuje hranice intervalů na základě kumulativního rozložení četnosti, takže každý interval pokrývá přibližně stejný počet produktů. To poskytuje lepší zážitek při filtrování, zejména pro e-commerce katalogy s různorodými cenovými rozpětími.

Abychom demonstrovali vyrovnaný cenový histogram, použijeme následující příklad:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Vyrovnaný cenový histogram](/documentation/user/en/query/requirements/examples/histogram/price-histogram-equalized.evitaql)

</SourceCodeTabs>

Zjednodušený výsledek vypadá takto:

<MDInclude sourceVariable="extraResults.PriceHistogram">[Výsledek vyrovnaného cenového histogramu](/documentation/user/en/query/requirements/examples/histogram/price-histogram-equalized.evitaql.string.md)</MDInclude>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek vyrovnaného cenového histogramu ve formátu JSON

Výsledek vyrovnaného histogramu ve formátu JSON je o něco obsáhlejší, ale stále poměrně čitelný:

Jak vidíte, hranice intervalů jsou nastaveny tak, aby produkty byly rovnoměrněji rozloženy v celém rozsahu posuvníku.