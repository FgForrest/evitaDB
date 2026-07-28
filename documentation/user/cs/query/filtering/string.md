---
title: Filtrování řetězců
date: '17.1.2023'
perex: Existuje několik filtračních omezení navržených speciálně pro práci s řetězcovými atributy. Jsou užitečná pro vyhledávání entit s atributy, které obsahují konkrétní řetězec.
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
translated: 'true'
commit: '77da5b36c170430534ee4d9a4a2903da4de68555'
---
<Note type="info">
V souvislosti s omezeními popsanými v této kapitole by vás mohly zajímat obecná pravidla pro práci s datovými typy a poli, která jsou popsána v [základech dotazovacího jazyka](../basics.md#obecná-pravidla-dotazů).
</Note>

## attributeContains

```evitaql-syntax
attributeContains(
    argument:string!,
    argument:string!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        název [atributu entity](../../use/schema.md#atributy), jehož hodnota bude prohledávána
        na výskyt řetězce zadaného ve druhém argumentu
    </dd>
    <dt>argument:string!</dt>
    <dd>
        libovolná hodnota, kterou chcete v hodnotě atributu vyhledat (rozlišuje velká a malá písmena)
    </dd>
</dl>

`attributeContains` vyhledává výskyt řetězce ve filtrovatelném nebo unikátním [atributu entity](../../use/data-model.md#atributy-unikátní-filtrovatelné-řaditelné-lokalizované).
Omezení se chová přesně jako <LS to="e,j,r,g">[Java metoda `contains`](https://www.javatpoint.com/java-string-contains)</LS><LS to="c">[C# metoda `Contains`](https://learn.microsoft.com/en-us/dotnet/api/system.string.contains)</LS>.
Rozlišuje velká a malá písmena, pracuje s národními znaky (protože používáme řetězce v UTF-8) a vyžaduje přesnou shodu
hledaného řetězce kdekoliv v hodnotě atributu.

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Produkty obsahující řetězec `epix` v atributu `code`](/documentation/user/en/query/filtering/examples/string/attribute-contains.evitaql)
</SourceCodeTabs>

Vrací několik produktů, které obsahují řetězec *epix* v atributu *code*.

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

## Atribut začíná na

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
        libovolná hodnota, kterou hledáte na začátku hodnoty atributu (rozlišuje velká a malá písmena)
    </dd>
</dl>

`attributeStartsWith` prohledává filtrovatelný nebo unikátní [atribut](../../use/data-model.md#atributy-unikátní-filtrovatelné-řaditelné-lokalizované) entity
a ověřuje, zda začíná zadaným řetězcem. Omezení se chová přesně jako <LS to="e,j,r,g">[metoda `startsWith` v Javě](https://www.javatpoint.com/java-string-startswith)</LS><LS to="c">[metoda `StartsWith` v C#](https://learn.microsoft.com/en-us/dotnet/api/system.string.startswith)</LS>.
Rozlišuje velká a malá písmena, funguje s národními znaky (protože pracujeme s řetězci v UTF-8) a vyžaduje přesnou shodu hledaného řetězce na začátku hodnoty atributu.

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Produkty, které mají na začátku atributu `code` řetězec `garmin`](/documentation/user/en/query/filtering/examples/string/attribute-starts-with.evitaql)
</SourceCodeTabs>

Vrátí několik stránek produktů, jejichž atribut *code* začíná řetězcem *garmin*.

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

## Atribut končí na

```evitaql-syntax
attributeEndsWith(
    argument:string!,
    argument:string!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        název [atributu entity](../../use/schema.md#atributy), jehož hodnota bude testována, zda končí
        na řetězec zadaný ve druhém argumentu
    </dd>
    <dt>argument:string!</dt>
    <dd>
        libovolná hodnota, kterou hledáte v hodnotě atributu (rozlišuje malá a velká písmena)
    </dd>
</dl>

`attributeEndsWith` prohledává filtrovatelný nebo unikátní [atribut entity](../../use/data-model.md#atributy-unikátní-filtrovatelné-řaditelné-lokalizované)
a kontroluje, zda končí na zadaný řetězec. Omezení se chová přesně stejně jako
<LS to="e,j,r,g">[Java metoda `endsWith`](https://www.javatpoint.com/java-string-endswith)</LS><LS to="c">[C# metoda `EndsWith`](https://learn.microsoft.com/en-us/dotnet/api/system.string.endswith)</LS>.
Rozlišuje malá a velká písmena, funguje s národními znaky (protože pracujeme s řetězci v UTF-8) a vyžaduje přesnou
shodu hledaného řetězce na konci hodnoty atributu.

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Produkty, které mají na konci atributu `code` řetězec `solar`](/documentation/user/en/query/filtering/examples/string/attribute-ends-with.evitaql)
</SourceCodeTabs>

Vrací několik produktů, které končí na řetězec *solar* v atributu *code*.

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