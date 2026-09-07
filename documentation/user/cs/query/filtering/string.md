---
title: Filtrování řetězců
date: '17.1.2023'
perex: Existuje několik filtračních omezení navržených speciálně pro práci s řetězcovými atributy. Jsou užitečná pro vyhledávání entit s atributy, které obsahují konkrétní řetězec.
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
translated: 'true'
commit: '044b3d295fbf419acd145e3f373e656a32e2aa16'
---
<Note type="info">
V kontextu omezení popsaných v této kapitole by vás mohly zajímat obecná pravidla pro práci s datovými typy a poli popsaná v [základech dotazovacího jazyka](../basics.md#obecná-pravidla-dotazů).
</Note>

## Attribut obsahuje

```evitaql-syntax
attributeContains(
    argument:string!,
    argument:string!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        název [atributu entity](../../use/schema.md#atributy), jehož hodnota bude prohledávána na výskyt řetězce z druhého argumentu
    </dd>
    <dt>argument:string!</dt>
    <dd>
        libovolná hodnota, kterou chcete v hodnotě atributu vyhledat (rozlišuje malá a velká písmena)
    </dd>
</dl>

`attributeContains` prohledává filtrovatelný nebo unikátní [atribut](../../use/data-model.md#atributy-unikátní-filtrovatelné-řaditelné-lokalizované) entity na výskyt zadaného řetězce. Omezení se chová přesně jako <LS to="e,j,r,g">[Java metoda `contains`](https://www.javatpoint.com/java-string-contains)</LS><LS to="c">[C# metoda `Contains`](https://learn.microsoft.com/en-us/dotnet/api/system.string.contains)</LS>.
Rozlišuje malá a velká písmena, funguje s národními znaky (protože pracujeme s řetězci v UTF-8) a vyžaduje přesnou shodu hledaného řetězce kdekoli v hodnotě atributu.

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Produkty obsahující řetězec `epix` v atributu `code`](/documentation/user/en/query/filtering/examples/string/attribute-contains.evitaql)

</SourceCodeTabs>

Vrací několik produktů obsahujících řetězec *epix* v atributu *code*.

<Note type="info">

<NoteTitle toggles="true">

##### Produkty obsahující řetězec `epix` v atributu `code`

</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Produkty obsahující řetězec `epix` v atributu `code`](/documentation/user/en/query/filtering/examples/string/attribute-contains.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Produkty obsahující řetězec `epix` v atributu `code`](/documentation/user/en/query/filtering/examples/string/attribute-contains.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Produkty obsahující řetězec `epix` v atributu `code`](/documentation/user/en/query/filtering/examples/string/attribute-contains.rest.json.md)</MDInclude>

</LS>

</Note>

<Note type="info">

<NoteTitle toggles="false">

##### Výkon na velkých kolekcích

</NoteTitle>

Běžný index je seřazen podle celých hodnot, což evitaDB nic neříká o tom, co se nachází *uprostřed* těchto hodnot.
Toto omezení je tedy vyhodnocováno tak, že se projde každá unikátní hodnota atributu a každá se otestuje.
To je dostatečně rychlé pro několik tisíc unikátních hodnot, ale jakmile jich jsou stovky tisíc, stává se to nejpomalejší částí dotazu.

Pokud je to váš případ, atributu lze přiřadit
[akcelerátor filtru `SUBSTRING_SEARCH`](../../use/schema.md#vyhledávání-podřetězce) – speciální index, který najde odpovídající hodnoty přímo, místo aby je všechny testoval. Nikdy nemění, které entity dotaz vrací, pouze ovlivňuje rychlost jejich nalezení, a hledání vzorů kratších než tři znaky se vrací k běžnému chování.

Důležitá věc, kterou je třeba vědět před plánováním: akcelerátor stojí paměť a **musí být deklarován na atributu před vložením první entity** – nelze jej zapnout pro kolekci, která již obsahuje data.
Také se nevyužívá u dotazů zapsaných v rámci read-write session, kde se vždy provádí skenování – viz [akcelerátory filtrů](../../use/schema.md#akcelerátory-filtru).

</Note>

## Attribut začíná na

```evitaql-syntax
attributeStartsWith(
    argument:string!,
    argument:string!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        název [atributu entity](../../use/schema.md#atributy), jehož hodnota bude testována, zda začíná na řetězec z druhého argumentu
    </dd>
    <dt>argument:string!</dt>
    <dd>
        libovolná hodnota, kterou chcete v hodnotě atributu vyhledat (rozlišuje malá a velká písmena)
    </dd>
</dl>

`attributeStartsWith` prohledává filtrovatelný nebo unikátní [atribut](../../use/data-model.md#atributy-unikátní-filtrovatelné-řaditelné-lokalizované) entity a ověřuje, zda začíná na zadaný řetězec. Omezení se chová přesně jako <LS to="e,j,r,g">[Java metoda `startsWith`](https://www.javatpoint.com/java-string-startswith)</LS><LS to="c">[C# metoda `StartsWith`](https://learn.microsoft.com/en-us/dotnet/api/system.string.startswith)</LS>.
Rozlišuje malá a velká písmena, funguje s národními znaky (protože pracujeme s řetězci v UTF-8) a vyžaduje přesnou shodu hledaného řetězce na začátku hodnoty atributu.

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Produkty začínající řetězcem `garmin` v atributu `code`](/documentation/user/en/query/filtering/examples/string/attribute-starts-with.evitaql)

</SourceCodeTabs>

Vrací několik stránek produktů, které začínají řetězcem *garmin* v atributu *code*.

<Note type="info">

<NoteTitle toggles="true">

##### Produkty začínající řetězcem `garmin` v atributu `code`

</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Produkty začínající řetězcem `garmin` v atributu `code`](/documentation/user/en/query/filtering/examples/string/attribute-starts-with.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Produkty začínající řetězcem `garmin` v atributu `code`](/documentation/user/en/query/filtering/examples/string/attribute-starts-with.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Produkty začínající řetězcem `garmin` v atributu `code`](/documentation/user/en/query/filtering/examples/string/attribute-starts-with.rest.json.md)</MDInclude>

</LS>

</Note>

<Note type="info">

Na rozdíl od [`attributeContains`](#attribut-obsahuje) a [`attributeEndsWith`](#attribut-končí-na) je toto omezení
už rychlé i na velkých kolekcích a nepotřebuje žádný
[akcelerátor filtru](../../use/schema.md#akcelerátory-filtru). Hodnoty jsou udržovány v setříděném pořadí, takže všechny začínající stejným prefixem jsou vedle sebe: evitaDB skočí přímo na první takovou hodnotu a čte dál, dokud prefix odpovídá, aniž by procházela zbytek atributu. Proto akcelerátor `SUBSTRING_SEARCH` záměrně nepokrývá `attributeStartsWith` – mohl by jej pouze zpomalit.

</Note>

## Attribut končí na

```evitaql-syntax
attributeEndsWith(
    argument:string!,
    argument:string!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        název [atributu entity](../../use/schema.md#atributy), jehož hodnota bude testována, zda končí na řetězec z druhého argumentu
    </dd>
    <dt>argument:string!</dt>
    <dd>
        libovolná hodnota, kterou chcete v hodnotě atributu vyhledat (rozlišuje malá a velká písmena)
    </dd>
</dl>

`attributeEndsWith` prohledává filtrovatelný nebo unikátní [atribut](../../use/data-model.md#atributy-unikátní-filtrovatelné-řaditelné-lokalizované) entity a ověřuje, zda končí na zadaný řetězec. Omezení se chová přesně jako
<LS to="e,j,r,g">[Java metoda `endsWith`](https://www.javatpoint.com/java-string-endswith)</LS><LS to="c">[C# metoda `EndsWith`](https://learn.microsoft.com/en-us/dotnet/api/system.string.endswith)</LS>.
Rozlišuje malá a velká písmena, funguje s národními znaky (protože pracujeme s řetězci v UTF-8) a vyžaduje přesnou shodu hledaného řetězce na konci hodnoty atributu.

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Produkty končící řetězcem `solar` v atributu `code`](/documentation/user/en/query/filtering/examples/string/attribute-ends-with.evitaql)

</SourceCodeTabs>

Vrací několik produktů, které končí řetězcem *solar* v atributu *code*.

<Note type="info">

<NoteTitle toggles="true">

##### Produkty končící řetězcem `solar` v atributu `code`
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Produkty končící řetězcem `solar` v atributu `code`](/documentation/user/en/query/filtering/examples/string/attribute-ends-with.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Produkty končící řetězcem `solar` v atributu `code`](/documentation/user/en/query/filtering/examples/string/attribute-ends-with.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Produkty končící řetězcem `solar` v atributu `code`](/documentation/user/en/query/filtering/examples/string/attribute-ends-with.rest.json.md)</MDInclude>

</LS>

</Note>

<Note type="info">

<NoteTitle toggles="false">

##### Výkon na velkých kolekcích

</NoteTitle>

Běžný index je seřazen podle celých hodnot, což evitaDB nic neříká o tom, co se nachází *uprostřed* těchto hodnot.
Toto omezení je tedy vyhodnocováno tak, že se projde každá unikátní hodnota atributu a každá se otestuje.
To je dostatečně rychlé pro několik tisíc unikátních hodnot, ale jakmile jich jsou stovky tisíc, stává se to nejpomalejší částí dotazu.

Pokud je to váš případ, atributu lze přiřadit
[akcelerátor filtru `SUBSTRING_SEARCH`](../../use/schema.md#vyhledávání-podřetězce) – speciální index, který najde odpovídající hodnoty přímo, místo aby je všechny testoval. Nikdy nemění, které entity dotaz vrací, pouze ovlivňuje rychlost jejich nalezení, a hledání vzorů kratších než tři znaky se vrací k běžnému chování.

Důležitá věc, kterou je třeba vědět před plánováním: akcelerátor stojí paměť a **musí být deklarován na atributu před vložením první entity** – nelze jej zapnout pro kolekci, která již obsahuje data.
Také se nevyužívá u dotazů zapsaných v rámci read-write session, kde se vždy provádí skenování – viz [akcelerátory filtrů](../../use/schema.md#akcelerátory-filtru).

</Note>