---
title: Filtrování podle ceny
date: '11.5.2026'
perex: 'V oblasti e-commerce uživatelé očekávají, že uvidí ceny přizpůsobené jejich kontextu: místní měnu pro snadné porozumění, přesné prodejní ceny ze správného ceníku a aktuální nabídky, které mohou být platné pouze v určitých obdobích. Splnění těchto očekávání pomocí sofistikovaného filtrování v databázi nejen zlepšuje uživatelský zážitek, ale také zjednodušuje nákupní proces, což zvyšuje spokojenost i prodeje.'
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
translated: 'true'
commit: '77da5b36c170430534ee4d9a4a2903da4de68555'
---
Tato kapitola obsahuje popis constraintů evitaDB, které vám pomáhají kontrolovat výběr prodejní ceny a filtrovat produkty podle ceny.

## Rychlý průvodce filtrováním podle ceny

### Typické použití cenových omezení

Ve většině případů bude váš dotaz na entity s cenami vypadat takto:

<SourceCodeTabs requires="/evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Výpis produktů s platnou cenou v EUR](/documentation/user/en/query/filtering/examples/price/price.evitaql)

</SourceCodeTabs>

Pro správné určení vhodné prodejní ceny musíte zadat všechny tři omezení v logickém součtu (disjunkci):

1. [`priceInCurrency`](#cena-v-měně) – měna ceny pro prodej
2. [`priceValidIn`](#cena-platná-v) – datum a čas, kdy musí být cena pro prodej platná
3. [`priceInPriceLists`](#cena-v-cenících) – sada ceníků, na které má zákazník nárok, seřazená
   od nejpreferovanějšího po nejméně preferovaný.

<Note type="warning">

V části filtru dotazu je povoleno pouze jediné použití kteréhokoliv z těchto tří omezení. V současné době
není možné přepínat kontext mezi různými částmi filtru a vytvářet dotazy jako *najdi produkt, jehož cena
je buď v měně "CZK" nebo "EUR" v tomto či onom čase* pomocí tohoto omezení.

Ačkoliv je technicky možné implementovat podporu těchto úloh v evitaDB, jedná se o okrajové případy a bylo
nutné řešit důležitější scénáře. Vícenásobné kombinace těchto omezení ve skutečnosti znemožní nalezení
správné prodejní ceny a umožní pouze vracet odpovídající entity bez prodejní ceny.

</Note>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek výpisu produktů s platnou cenou v EUR

</NoteTitle>

Výsledná množina obsahuje pouze produkty, které mají platnou cenu pro prodej v měně EUR:

<LS to="e,j,c">

<MDInclude>[Výsledek výpisu produktů s platnou cenou v EUR](/documentation/user/en/query/filtering/examples/price/price.evitaql.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Výsledek výpisu produktů s platnou cenou v EUR](/documentation/user/en/query/filtering/examples/price/price.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek výpisu produktů s platnou cenou v EUR](/documentation/user/en/query/filtering/examples/price/price.rest.json.md)</MDInclude>

</LS>
</Note>

### Výběr prodejní ceny v kostce

<Note type="warning">

Pokud vám budou některá tvrzení v této kapitole nejasná, přečtěte si [dokumentaci k algoritmu výpočtu prodejní ceny](/documentation/user/en/deep-dive/price-for-sale-calculation.md), kde najdete více příkladů a podrobný rozbor postupu krok za krokem.

</Note>

Výběr prodejní ceny závisí na <LS to="e,j,r,g"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceInnerRecordHandling.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/Data/PriceInnerRecordHandling.cs</SourceClass></LS>
režimu, který má [entita nastavený](../../use/data-model.md#entita), ale pro všechny režimy platí společný jmenovatel –
prodejní cena je vybírána z [cen](../../use/data-model.md#ceny) označených jako `indexed`, které odpovídají
zvolené [měně](#cena-v-měně) a [ceníkům](#cena-v-cenících) a jsou platné v určeném [čase](#cena-platná-v). První cena, která splňuje všechna tato kritéria v pořadí ceníků v [argumentu constraintu ceníku](#cena-v-cenících), je vybrána jako prodejní cena.

Pro nedefaultní režimy zpracování vnitřních záznamů ceny se cena vypočítává takto:

<dl>
   <dt>LOWEST_PRICE</dt>
   <dd>
      Prodejní cena je vybrána jako nejnižší prodejní cena vypočítaná zvlášť pro bloky cen se stejným
      ID vnitřního záznamu. Pokud je zadán constraint [price between](#cena-v-rozmezí), cena je nejnižší prodejní cena platná pro zadaný cenový rozsah.
   </dd>
   <dt>SUM</dt>
   <dd>
      Prodejní cena je vypočítána jako součet prodejních cen vypočítaných zvlášť pro bloky cen se
      stejným ID vnitřního záznamu. Pokud je zadán constraint [price between](#cena-v-rozmezí), je prodejní cena platná pouze pokud je součet v zadaném cenovém rozsahu.
   </dd>
</dl>

## Cena v měně

```evitaql-syntax
priceInCurrency(
    argument:string!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        Povinné určení měny, ve které musí být všechny ceny cílené dotazem.

        Kód měny musí být [třímístný kód dle ISO 4217](https://en.wikipedia.org/wiki/ISO_4217).
    </dd>
</dl>

<LS to="j">

Pokud pracujete s evitaDB v Javě, můžete místo [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html) ISO kódu použít [`Currency`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Currency.html).
Toto je přirozený způsob práce s lokalizovanými daty na této platformě.

</LS>
<LS to="c">

Pokud pracujete s evitaDB v C#, můžete použít vlastní třídu <SourceClass>EvitaDB.Client/DataTypes/Currency.cs</SourceClass>,
která byla vytvořena pro kompatibilitu s evitaDB Java API. Jedná se o jednoduchý wrapper kolem [string](https://docs.microsoft.com/en-us/dotnet/api/system.string) ISO kódu,
který lze předat i v surové podobě bez nutnosti vytvářet instanci třídy.

</LS>

Constraint <LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/PriceInCurrency.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Filter/PriceInCurrency.cs</SourceClass></LS>
lze použít k omezení výsledné množiny pouze na entity, které mají cenu v dané měně. Kromě [standardního použití](#typické-použití-cenových-omezení)
můžete vytvořit i dotaz pouze s tímto constraintem:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Výpis produktů s jakoukoliv cenou v měně EUR](/documentation/user/en/query/filtering/examples/price/price-in-currency.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek výpisu produktů s jakoukoliv cenou v měně EUR

</NoteTitle>

Výsledná množina obsahuje pouze produkty, které mají alespoň jednu cenu v měně EUR:

<LS to="e,j,c">

<MDInclude>[Výsledek výpisu produktů s jakoukoliv cenou v měně EUR](/documentation/user/en/query/filtering/examples/price/price-in-currency.evitaql.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Výsledek výpisu produktů s jakoukoliv cenou v měně EUR](/documentation/user/en/query/filtering/examples/price/price-in-currency.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek výpisu produktů s jakoukoliv cenou v měně EUR](/documentation/user/en/query/filtering/examples/price/price-in-currency.rest.json.md)</MDInclude>

</LS>

</Note>

## Cena v cenících

```evitaql-syntax
priceInPriceLists(
    argument:string+
)
```

<dl>
    <dt>argument:string+</dt>
    <dd>
        Povinné zadání jednoho nebo více názvů ceníků v pořadí od nejpreferovanějšího po nejméně preferovaný.
    </dd>
</dl>

Omezení <LS to="j,e,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/PriceInPriceLists.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Filter/PriceInPriceLists.cs</SourceClass></LS>
definuje povolenou množinu ceníků, které musí entita obsahovat, aby byla zahrnuta do výsledné množiny. Pořadí
ceníků v argumentu je důležité pro výpočet finální prodejní ceny – viz
[dokumentace k algoritmu výpočtu prodejní ceny](/documentation/user/en/deep-dive/price-for-sale-calculation.md).
Názvy ceníků jsou reprezentovány běžným <LS to="j,e,r,g">[String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)</LS><LS to="c">[string](https://docs.microsoft.com/en-us/dotnet/api/system.string)</LS>
a rozlišují velikost písmen. Ceníky nemusí být v databázi uložené jako entita, a pokud jsou, aktuálně nejsou
spojeny s kódem ceníku definovaným v cenách jiných entit. Cenová struktura je nyní jednoduchá
a plochá (to se však může v budoucnu změnit).

Kromě [standardního použití](#typické-použití-cenových-omezení) můžete také vytvořit dotaz pouze s tímto omezením:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Výpis produktů s jakoukoliv cenou v některém z VIP ceníků](/documentation/user/en/query/filtering/examples/price/price-in-price-lists.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek výpisu produktů s jakoukoliv cenou v některém z VIP ceníků

</NoteTitle>

Výsledná množina obsahuje pouze produkty, které mají alespoň jednu cenu v některém z uvedených VIP ceníků:

<LS to="e,j,c">

<MDInclude>[Výsledek výpisu produktů s jakoukoliv cenou v některém z VIP ceníků](/documentation/user/en/query/filtering/examples/price/price-in-price-lists.evitaql.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Výsledek výpisu produktů s jakoukoliv cenou v některém z VIP ceníků](/documentation/user/en/query/filtering/examples/price/price-in-price-lists.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek výpisu produktů s jakoukoliv cenou v některém z VIP ceníků](/documentation/user/en/query/filtering/examples/price/price-in-price-lists.rest.json.md)</MDInclude>

</LS>

</Note>

## Cena platná v

```evitaql-syntax
priceValidIn(
    argument:offsetDateTime!
)
```

<dl>
    <dt>argument:offsetDateTime!</dt>
    <dd>
        Povinný argument představující datum a čas (s časovým posunem) ve formátu `yyyy-MM-ddTHH:mm:ssXXX`, například
        `2007-12-03T10:15:30+01:00`. <LS to="j,e,r,g">V jazyce Java můžete přímo použít [OffsetDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/OffsetDateTime.html)</LS><LS to="c">V jazyce C# můžete přímo použít [DateTimeOffset](https://learn.microsoft.com/en-us/dotnet/api/system.datetimeoffset)</LS>
    </dd>
</dl>

<LS to="j,e,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/PriceValidIn.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Filter/PriceValidIn.cs</SourceClass></LS> vyloučí všechny entity,
které nemají platnou cenu pro prodej v zadaném datu a čase. Pokud cena nemá určenou vlastnost platnosti,
projde všemi kontrolami platnosti.

Abychom ukázali efekt platnostních omezení, vytvoříme dotaz, který vypíše produkty v kategorii *Vánoční elektronika*
a pokusí se získat ceny z jejich *Vánočního ceníku*, s případným přepnutím na *Základní ceník*, přičemž jako referenční
bod pro kontrolu platnosti ceny použije datum a čas z jarního období svátků:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Výpis produktů s vánočními cenami v květnu](/documentation/user/en/query/filtering/examples/price/price-valid-in.evitaql)

</SourceCodeTabs>

Nyní dotaz upravíme tak, aby použil datum a čas v prosinci:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Výpis produktů s vánočními cenami v prosinci](/documentation/user/en/query/filtering/examples/price/price-valid-in-correct.evitaql)

</SourceCodeTabs>

Jak můžete vidět, získáte poněkud odlišnou prodejní cenu, protože vánoční ceny jsou nyní aplikovány:

<LS to="e,j,c">

<MDInclude>[Porovnání prosincových cen s květnovými](/documentation/user/en/query/filtering/examples/price/price-valid-in-correct.evitaql.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Porovnání prosincových cen s květnovými](/documentation/user/en/query/filtering/examples/price/price-valid-in-correct.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Porovnání prosincových cen s květnovými](/documentation/user/en/query/filtering/examples/price/price-valid-in-correct.rest.json.md)</MDInclude>

</LS>

<Note type="info">

<NoteTitle toggles="true">

##### Porovnání prosincových cen s květnovými

</NoteTitle>

Ceny pro prodej v květnu byly jiné, protože vánoční ceny v té době nebyly platné:

<LS to="e,j,c">

<MDInclude>[Porovnání prosincových cen s květnovými](/documentation/user/en/query/filtering/examples/price/price-valid-in.evitaql.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Porovnání prosincových cen s květnovými](/documentation/user/en/query/filtering/examples/price/price-valid-in.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Porovnání prosincových cen s květnovými](/documentation/user/en/query/filtering/examples/price/price-valid-in.rest.json.md)</MDInclude>

</LS>

</Note>

## Cena platná nyní

```evitaql-syntax
priceValidInNow()
```

Toto je varianta constraintu [`priceValidIn`](#cena-platná-v), která používá aktuální datum a čas jako referenční bod pro kontrolu platnosti ceny. Constraint [`priceValidIn`](#cena-platná-v) vám umožňuje zadat libovolné datum a čas v budoucnosti nebo minulosti jako referenční bod.

## Cena v rozmezí

```evitaql-syntax
priceBetween(
    argument:bigDecimal!,
    argument:bigDecimal!
)
```

<LS to="j,e,r,g">

<dl>
    <dt>argument:bigDecimal!</dt>
    <dd>
        Povinný argument určující dolní hranici cenového rozmezí. Cenové rozmezí je včetně hranic, takže cena musí být větší nebo rovna dolní hranici. V jazyce Java můžete přímo použít [BigDecimal](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/math/BigDecimal.html) v textovém formátu, je třeba použít řetězcovou reprezentaci čísla.
    </dd>
    <dt>argument:bigDecimal!</dt>
    <dd>
        Povinný argument určující horní hranici cenového rozmezí. Cenové rozmezí je včetně hranic, takže cena musí být menší nebo rovna horní hranici. V jazyce Java můžete přímo použít [BigDecimal](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/math/BigDecimal.html) v textovém formátu, je třeba použít řetězcovou reprezentaci čísla.
    </dd>
</dl>

Constraint <SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/PriceBetween.java</SourceClass>
omezuje výslednou množinu na položky, které mají prodejní cenu v zadaném cenovém rozmezí. Tento constraint je
typicky nastaven uživatelským rozhraním, aby uživatel mohl filtrovat produkty podle ceny, a měl by být vnořen
do kontejneru constraintu [`userFilter`](behavioral.md#uživatelský-filtr), aby mohl být správně zpracován
výpočty [referenčního souhrnu](../requirements/reference.md) nebo [histogramu](../requirements/histogram.md).

</LS>

<LS to="c">

<dl>
    <dt>argument:decimal!</dt>
    <dd>
        Povinný argument určující dolní hranici cenového rozmezí. Cenové rozmezí je včetně hranic, takže cena musí být větší nebo rovna dolní hranici. V jazyce C# můžete přímo použít [decimal](https://learn.microsoft.com/en-us/dotnet/api/system.decimal) v textovém formátu, je třeba použít řetězcovou reprezentaci čísla.
    </dd>
    <dt>argument:decimal!</dt>
    <dd>
        Povinný argument určující horní hranici cenového rozmezí. Cenové rozmezí je včetně hranic, takže cena musí být menší nebo rovna horní hranici. V jazyce C# můžete přímo použít [decimal](https://learn.microsoft.com/en-us/dotnet/api/system.decimal) v textovém formátu, je třeba použít řetězcovou reprezentaci čísla.
    </dd>
</dl>

Constraint <SourceClass>EvitaDB.Client/Queries/Filter/PriceBetween.cs</SourceClass>
omezuje výslednou množinu na položky, které mají prodejní cenu v zadaném cenovém rozmezí. Tento constraint je
typicky nastaven uživatelským rozhraním, aby uživatel mohl filtrovat produkty podle ceny, a měl by být vnořen
do kontejneru constraintu [`userFilter`](behavioral.md#uživatelský-filtr), aby mohl být správně zpracován
výpočty [referenčního souhrnu](../requirements/reference.md) nebo [histogramu](../requirements/histogram.md).

</LS>

Pro demonstraci constraintu cenového rozmezí vytvoříme dotaz, který vypíše produkty v kategorii *E-čtečky* a
vyfiltruje pouze ty, jejichž cena je mezi `€150` a `€170.5`:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Výpis e-čteček s cenou mezi `€150` a `€170.5`](/documentation/user/en/query/filtering/examples/price/price-between.evitaql)

</SourceCodeTabs>

Rozmezí je poměrně úzké, takže výsledná množina obsahuje pouze jeden produkt:

<LS to="e,j,c">

<MDInclude>[Porovnejte prosincové ceny s květnovými](/documentation/user/en/query/filtering/examples/price/price-between.evitaql.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Porovnejte prosincové ceny s květnovými](/documentation/user/en/query/filtering/examples/price/price-between.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Porovnejte prosincové ceny s květnovými](/documentation/user/en/query/filtering/examples/price/price-between.rest.json.md)</MDInclude>

</LS>