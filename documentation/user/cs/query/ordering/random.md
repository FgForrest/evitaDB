---
title: Náhodné řazení
date: '25.6.2023'
perex: Náhodné řazení je užitečné v situacích, kdy chcete uživateli pokaždé zobrazit jedinečný seznam entit při jeho přístupu.
author: Ing. Jan Novotný
proofreading: needed
preferredLang: evitaql
translated: 'true'
commit: '77da5b36c170430534ee4d9a4a2903da4de68555'
---
## Náhodné

```evitaql-syntax
random()
```

Tato podmínka způsobí, že pořadí entit ve výsledku bude náhodné a nepřijímá žádné argumenty.

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Entities sorted randomly](/documentation/user/en/query/ordering/examples/random/random.evitaql)

</SourceCodeTabs>

Ukázkový dotaz vždy vrací jinou stránku produktů.

<Note type="info">

<NoteTitle toggles="true">

##### Seznam náhodně seřazených produktů
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Seznam náhodně seřazených produktů](/documentation/user/en/query/ordering/examples/random/randomized.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Seznam náhodně seřazených produktů](/documentation/user/en/query/ordering/examples/random/randomized.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Seznam náhodně seřazených produktů](/documentation/user/en/query/ordering/examples/random/randomized.rest.json.md)</MDInclude>

</LS>

</Note>

## Náhodné s použitím seedu

```evitaql-syntax
randomWithSeed(
    argument:long!
)
```

<dl>
    <dt>argument:long!</dt>
    <dd>
        definuje seed pro generátor náhodných čísel, což zajišťuje, že stejný seed vždy vytvoří stejný pořádek 
        entit ve výsledku 
    </dd>
</dl>

Tato podmínka způsobí, že pořadí entit ve výsledku je pseudo-náhodné na základě zadaného seedu. Seed je číslo, které určuje pořadí entit. Stejný seed vždy vytvoří stejné pořadí entit.

Tato varianta náhodného řazení je užitečná, když potřebujete, aby byl výstup náhodný, ale vždy stejným způsobem (například pro testovací účely nebo pro konzistentní výstup pro konkrétního uživatele).

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Entita seřazené pseudo-náhodně](/documentation/user/en/query/ordering/examples/random/pseudo-random.evitaql)

</SourceCodeTabs>

Ukázkový dotaz vždy vrací stejnou stránku produktů, která působí náhodně, ale je vždy stejná.

<Note type="info">

<NoteTitle toggles="true">

##### Seznam pseudo-náhodně seřazených produktů pomocí seedu
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Seznam pseudo-náhodně seřazených produktů pomocí seedu](/documentation/user/en/query/ordering/examples/random/pseudo-random.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Seznam pseudo-náhodně seřazených produktů pomocí seedu](/documentation/user/en/query/ordering/examples/random/pseudo-random.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Seznam pseudo-náhodně seřazených produktů pomocí seedu](/documentation/user/en/query/ordering/examples/random/pseudo-random.rest.json.md)</MDInclude>

</LS>

</Note>