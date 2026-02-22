---
title: Porovnatelné filtrování
date: '29.5.2023'
perex: Všechny datové typy atributů v evitaDB mají vzájemně porovnatelné hodnoty – vždy je možné jednoznačně určit, zda jsou dvě hodnoty stejné, jedna menší než druhá, nebo naopak. Tato skutečnost je základem základní sady filtračních omezení, která běžně používáte při vytváření dotazů.
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
commit: cabcf999e7be5b00e0b13e1228a76a8d9e91cb78
translated: true
---
<Note type="info">
V souvislosti s omezeními popsanými v této kapitole by vás mohly zajímat obecná pravidla pro práci s datovými typy a poli, která jsou popsána v [základech dotazovacího jazyka](../basics.md#obecná-pravidla-dotazů).
</Note>

<Note type="warning">
Když porovnáváte dva datové typy <LS to="e,j,r,g">**[String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)**</LS><LS to="c">**[string](https://learn.microsoft.com/en-us/dotnet/api/system.string)**</LS>, řetězce jsou porovnávány abecedně od začátku řetězce. Například *Walther* je větší než *Adam*, ale *Jasmine* není větší než *Joanna*. Pro porovnání lokalizovaného atributu řetězce je použit správný <LS to="e,j,r,g">[collator](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/text/Collator.html)</LS><LS to="c">[culture info](https://learn.microsoft.com/en-us/dotnet/api/system.globalization.cultureinfo)</LS>, takže pořadí odpovídá národním zvyklostem daného jazyka.

Když porovnáváte dva datové typy **[Range](../../use/data-types.md#numberrange)**, větší je ten, jehož levá hranice je větší než levá hranice druhé hodnoty. Pokud jsou obě levé hranice stejné, větší je ten s větší pravou hranicí.

Datový typ <LS to="e,j,r,g">**[boolean](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)**</LS><LS to="c">**[bool](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/builtin-types/bool)**</LS> je porovnáván jako číselná hodnota, kde *true* je 1 a *false* je 0.
</Note>

## Atribut rovná se

```evitaql-syntax
attributeEquals(
    argument:string!,
    argument:comparable!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        název [atributu entity](../../use/schema.md#atributy), jehož hodnota bude porovnána s hodnotou ve druhém argumentu
    </dd>
    <dt>argument:comparable!</dt>
    <dd>
        libovolná hodnota, která bude porovnána s [atributem entity](../../use/schema.md#atributy) na rovnost
    </dd>
</dl>

`attributeEquals` porovnává filtrovatelný nebo unikátní [atribut](../../use/data-model.md#atributy-unikátní-filtrovatelné-řaditelné-lokalizované) entity na přesnou rovnost s předanou hodnotou.

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Produkt s atributem `code` roven `apple-iphone-13-pro-3`](/documentation/user/en/query/filtering/examples/comparable/attribute-equals.evitaql)
</SourceCodeTabs>

Vrací přesně jeden produkt s *code* rovným *apple-iphone-13-pro-3*.

<Note type="info">

<NoteTitle toggles="true">

##### Produkt nalezený podle atributu `code`
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Produkt s atributem `code` roven `apple-iphone-13-pro-3`](/documentation/user/en/query/filtering/examples/comparable/attribute-equals.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Produkt s atributem `code` roven `apple-iphone-13-pro-3`](/documentation/user/en/query/filtering/examples/comparable/attribute-equals.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Produkt s atributem `code` roven `apple-iphone-13-pro-3`](/documentation/user/en/query/filtering/examples/comparable/attribute-equals.rest.json.md)</MDInclude>

</LS>

</Note>

## Atribut větší než

```evitaql-syntax
attributeGreaterThan(
    argument:string!,
    argument:comparable!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        název [atributu entity](../../use/schema.md#atributy), jehož hodnota bude porovnána s hodnotou ve druhém argumentu
    </dd>
    <dt>argument:comparable!</dt>
    <dd>
        libovolná hodnota, která bude porovnána s [atributem entity](../../use/schema.md#atributy), aby byla větší než atribut zkoumané entity
    </dd>
</dl>

`attributeGreaterThan` porovnává filtrovatelný nebo unikátní [atribut](../../use/data-model.md#atributy-unikátní-filtrovatelné-řaditelné-lokalizované) entity s hodnotou ve druhém argumentu a je splněn pouze tehdy, pokud je atribut entity větší než hodnota.

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Produkty s atributem `battery-life` větším než 40 hodin](/documentation/user/en/query/filtering/examples/comparable/attribute-greater-than.evitaql)
</SourceCodeTabs>

Vrací přesně několik produktů s *battery-life* větším než *40* hodin.

<Note type="info">

<NoteTitle toggles="true">

##### Produkty s atributem `battery-life` větším než 40 hodin
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Produkty s atributem `battery-life` větším než 40 hodin](/documentation/user/en/query/filtering/examples/comparable/attribute-greater-than.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Produkty s atributem `battery-life` větším než 40 hodin](/documentation/user/en/query/filtering/examples/comparable/attribute-greater-than.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Produkty s atributem `battery-life` větším než 40 hodin](/documentation/user/en/query/filtering/examples/comparable/attribute-greater-than.rest.json.md)</MDInclude>

</LS>

</Note>

## Atribut větší nebo roven

```evitaql-syntax
attributeGreaterThanEquals(
    argument:string!,
    argument:comparable!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        název [atributu entity](../../use/schema.md#atributy), jehož hodnota bude porovnána s hodnotou ve druhém argumentu
    </dd>
    <dt>argument:comparable!</dt>
    <dd>
        libovolná hodnota, která bude porovnána s [atributem entity](../../use/schema.md#atributy), aby byla větší nebo rovna atributu zkoumané entity
    </dd>
</dl>

`attributeGreaterThanEquals` porovnává filtrovatelný nebo unikátní [atribut](../../use/data-model.md#atributy-unikátní-filtrovatelné-řaditelné-lokalizované) entity s hodnotou ve druhém argumentu a je splněn pouze tehdy, pokud je atribut entity větší nebo roven hodnotě.

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Produkty s atributem `battery-life` větším nebo rovným 40 hodin](/documentation/user/en/query/filtering/examples/comparable/attribute-greater-than-equals.evitaql)

</SourceCodeTabs>

Vrací přesně několik produktů s *battery-life* větším nebo rovným *40* hodin.

<Note type="info">

<NoteTitle toggles="true">

##### Produkty s atributem `battery-life` větším nebo rovným 40 hodin
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Produkty s atributem `battery-life` větším nebo rovným 40 hodin](/documentation/user/en/query/filtering/examples/comparable/attribute-greater-than-equals.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Produkty s atributem `battery-life` větším nebo rovným 40 hodin](/documentation/user/en/query/filtering/examples/comparable/attribute-greater-than-equals.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Produkty s atributem `battery-life` větším nebo rovným 40 hodin](/documentation/user/en/query/filtering/examples/comparable/attribute-greater-than-equals.rest.json.md)</MDInclude>

</LS>

</Note>

## Atribut menší než

```evitaql-syntax
attributeLessThan(
    argument:string!,
    argument:comparable!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        název [atributu entity](../../use/schema.md#atributy), jehož hodnota bude porovnána s hodnotou ve druhém argumentu
    </dd>
    <dt>argument:comparable!</dt>
    <dd>
        libovolná hodnota, která bude porovnána s [atributem entity](../../use/schema.md#atributy), aby byla menší než atribut zkoumané entity
    </dd>
</dl>

`attributeLessThan` porovnává filtrovatelný nebo unikátní [atribut](../../use/data-model.md#atributy-unikátní-filtrovatelné-řaditelné-lokalizované) entity s hodnotou ve druhém argumentu a je splněn pouze tehdy, pokud je atribut entity menší než hodnota.

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Produkty s atributem `battery-life` menším než 125 mWH](/documentation/user/en/query/filtering/examples/comparable/attribute-less-than.evitaql)
</SourceCodeTabs>

Vrací přesně několik produktů s *battery-capacity* menší než *125* mWH.

<Note type="info">

<NoteTitle toggles="true">

##### Produkty s atributem `battery-capacity` menším než 125 mWH
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Produkty s atributem `battery-life` menším než 125 mWH](/documentation/user/en/query/filtering/examples/comparable/attribute-less-than.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Produkty s atributem `battery-life` menším než 125 mWH](/documentation/user/en/query/filtering/examples/comparable/attribute-less-than.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Produkty s atributem `battery-life` menším než 125 mWH](/documentation/user/en/query/filtering/examples/comparable/attribute-less-than.rest.json.md)</MDInclude>

</LS>

</Note>

## Atribut menší nebo roven

```evitaql-syntax
attributeLessThanEquals(
    argument:string!,
    argument:comparable!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        název [atributu entity](../../use/schema.md#atributy), jehož hodnota bude porovnána s hodnotou ve druhém argumentu
    </dd>
    <dt>argument:comparable!</dt>
    <dd>
        libovolná hodnota, která bude porovnána s [atributem entity](../../use/schema.md#atributy), aby byla menší nebo rovna atributu zkoumané entity
    </dd>
</dl>

`attributeLessThanEquals` porovnává filtrovatelný nebo unikátní [atribut](../../use/data-model.md#atributy-unikátní-filtrovatelné-řaditelné-lokalizované) entity s hodnotou ve druhém argumentu a je splněn pouze tehdy, pokud je atribut entity menší nebo roven hodnotě.

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Produkty s atributem `battery-life` menším nebo rovným 125 mWH](/documentation/user/en/query/filtering/examples/comparable/attribute-less-than-equals.evitaql)
</SourceCodeTabs>

Vrací přesně několik produktů s *battery-capacity* menší nebo rovnou *125* mWH.

<Note type="info">

<NoteTitle toggles="true">

##### Produkty s atributem `battery-capacity` menším nebo rovným 125 mWH
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Produkty s atributem `battery-life` menším nebo rovným 125 mWH](/documentation/user/en/query/filtering/examples/comparable/attribute-less-than-equals.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Produkty s atributem `battery-life` menším nebo rovným 125 mWH](/documentation/user/en/query/filtering/examples/comparable/attribute-less-than-equals.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Produkty s atributem `battery-life` menším nebo rovným 125 mWH](/documentation/user/en/query/filtering/examples/comparable/attribute-less-than-equals.rest.json.md)</MDInclude>

</LS>

</Note>

## Atribut mezi

```evitaql-syntax
attributeBetween(
    argument:string!,
    argument:comparable!,
    argument:comparable!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        název [atributu entity](../../use/schema.md#atributy), jehož hodnota bude porovnána s hodnotou ve druhém argumentu
    </dd>
    <dt>argument:comparable!</dt>
    <dd>
        libovolná hodnota, která bude porovnána s [atributem entity](../../use/schema.md#atributy), aby byla větší nebo rovna atributu zkoumané entity
    </dd>
    <dt>argument:comparable!</dt>
    <dd>
        libovolná hodnota, která bude porovnána s [atributem entity](../../use/schema.md#atributy), aby byla menší nebo rovna atributu zkoumané entity
    </dd>
</dl>

`attributeBetween` porovnává filtrovatelný nebo unikátní [atribut](../../use/data-model.md#atributy-unikátní-filtrovatelné-řaditelné-lokalizované) entity a je splněn pouze tehdy, pokud je atribut entity menší nebo roven prvnímu argumentu a zároveň větší nebo roven druhému argumentu omezení.

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Produkty s atributem `battery-life` menším nebo rovným 125 mWH](/documentation/user/en/query/filtering/examples/comparable/attribute-between.evitaql)
</SourceCodeTabs>

Vrací přesně několik produktů s *battery-capacity* mezi *125* a *160* mWH.

<Note type="info">

<NoteTitle toggles="true">

##### Produkty s atributem `battery-capacity` mezi 125 mWH a 160 mWH
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Produkty s atributem `battery-life` menším nebo rovným 125 mWH](/documentation/user/en/query/filtering/examples/comparable/attribute-between.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Produkty s atributem `battery-life` menším nebo rovným 125 mWH](/documentation/user/en/query/filtering/examples/comparable/attribute-between.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Produkty s atributem `battery-life` menším nebo rovným 125 mWH](/documentation/user/en/query/filtering/examples/comparable/attribute-between.rest.json.md)</MDInclude>

</LS>

</Note>

## Atribut v množině

```evitaql-syntax
attributeInSet(
    argument:string!,
    argument:comparable+
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        název [atributu entity](../../use/schema.md#atributy), jehož hodnota bude porovnána s hodnotou ve druhém argumentu
    </dd>
    <dt>argument:comparable+</dt>
    <dd>
        jedna nebo více hodnot, které budou porovnány s [atributem entity](../../use/schema.md#atributy) na rovnost
    </dd>
</dl>

`attributeInSet` porovnává filtrovatelný nebo unikátní [atribut](../../use/data-model.md#atributy-unikátní-filtrovatelné-řaditelné-lokalizované) entity na přesnou rovnost s libovolnou z předaných hodnot.

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Produkt nalezený podle atributu `code` v dané množině](/documentation/user/en/query/filtering/examples/comparable/attribute-in-set.evitaql)
</SourceCodeTabs>

Vrací přesně tři produkty s *code* odpovídajícím jednomu z argumentů. Poslední z produktů nebyl nalezen v databázi a v výsledku chybí.

<Note type="info">

<NoteTitle toggles="true">

##### Produkt nalezený podle atributu `code` v dané množině
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Produkt nalezený podle atributu `code` v dané množině](/documentation/user/en/query/filtering/examples/comparable/attribute-in-set.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Produkt nalezený podle atributu `code` v dané množině](/documentation/user/en/query/filtering/examples/comparable/attribute-in-set.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Produkt nalezený podle atributu `code` v dané množině](/documentation/user/en/query/filtering/examples/comparable/attribute-in-set.rest.json.md)</MDInclude>

</LS>

</Note>

## Atribut existuje

```evitaql-syntax
attributeIs(
    argument:string!
    argument:enum(NULL|NOT_NULL)
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        název [atributu entity](../../use/schema.md#atributy), jehož hodnota bude kontrolována na (ne)existenci
    </dd>

</dl>

`attributeIs` lze použít k ověření existence [atributu](../../use/data-model.md#atributy-unikátní-filtrovatelné-řaditelné-lokalizované) entity se zadaným názvem.

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Produkt s přítomným atributem `catalogNumber`](/documentation/user/en/query/filtering/examples/comparable/attribute-is-not-null.evitaql)
</SourceCodeTabs>

Vrací stovky produktů s nastaveným atributem *catalogNumber*.

<Note type="info">

<NoteTitle toggles="true">

##### Produkty s přítomným atributem `catalogNumber`
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Produkt s přítomným atributem `catalogNumber`](/documentation/user/en/query/filtering/examples/comparable/attribute-is-not-null.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Produkt s přítomným atributem `catalogNumber`](/documentation/user/en/query/filtering/examples/comparable/attribute-is-not-null.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Produkt s přítomným atributem `catalogNumber`](/documentation/user/en/query/filtering/examples/comparable/attribute-is-not-null.rest.json.md)</MDInclude>

</LS>

</Note>

Když se pokusíte vypsat produkty bez tohoto atributu:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Produkt s chybějícím atributem `catalog-number`](/documentation/user/en/query/filtering/examples/comparable/attribute-is-null.evitaql)
</SourceCodeTabs>

... získáte pouze jeden:

<Note type="info">

<NoteTitle toggles="true">

##### Produkty s chybějícím atributem `catalog-number`
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Produkt s chybějícím atributem `catalog-number`](/documentation/user/en/query/filtering/examples/comparable/attribute-is-null.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Produkt s chybějícím atributem `catalog-number`](/documentation/user/en/query/filtering/examples/comparable/attribute-is-null.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Produkt s chybějícím atributem `catalog-number`](/documentation/user/en/query/filtering/examples/comparable/attribute-is-null.rest.json.md)</MDInclude>

</LS>

</Note>