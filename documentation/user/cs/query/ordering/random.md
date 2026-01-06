---
title: Náhodné řazení
date: '25.6.2023'
perex: Náhodné řazení je užitečné v situacích, kdy chcete uživateli pokaždé zobrazit jedinečný seznam entit při každém jeho přístupu.
author: Ing. Jan Novotný
proofreading: needed
preferredLang: evitaql
commit: cef96d8320d36c91c100c5dfc9c45020b5a7ad0d
---
## Náhodné

```evitaql-syntax
random()
```

Tato podmínka způsobí, že pořadí entit ve výsledku je náhodné a nepřijímá žádné argumenty.

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Entity seřazené náhodně](/documentation/user/en/query/ordering/examples/random/random.evitaql)

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

## Náhodné s vstupním seedem

```evitaql-syntax
randomWithSeed(
    argument:long!
)
```

<dl>
    <dt>argument:long!</dt>
    <dd>
        definuje seed pro generátor náhodných čísel; použití stejného seedu vždy vytvoří stejné pořadí 
        entit ve výsledku 
    </dd>
</dl>

Tato podmínka způsobí, že pořadí entit ve výsledku je pseudo-náhodné na základě zadaného seedu. Seed je číslo, které určuje pořadí entit. Stejný seed vždy vytvoří stejné pořadí entit.

Tato varianta náhodného řazení je užitečná, když potřebujete, aby byl výstup náhodný, ale vždy stejným způsobem (například pro testovací účely nebo pro konzistentní výstup pro daného uživatele).

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Entity seřazené pseudo-náhodně](/documentation/user/en/query/ordering/examples/random/pseudo-random.evitaql)

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