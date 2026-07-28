---
title: Konstantní pořadí
perex: Existují situace, kdy je pořadí entit určeno mimo evitaDB. Omezení konstantního pořadí umožňují ovládat pořadí vybraných entit podle logiky volajícího.
date: '17.1.2023'
author: Ing. Jan Novotný
proofreading: needed
preferredLang: evitaql
translated: 'true'
commit: '77da5b36c170430534ee4d9a4a2903da4de68555'
---
Konstantní pořadové omezení jsou obzvláště užitečná, pokud máte seřazenou množinu unikátních atributů nebo přímo primární klíče entit, které poskytuje externí systém a které je potřeba zachovat ve výstupu evitaDB (například představují relevanci těchto entit z fulltextového enginu).

## Přesné pořadí primárních klíčů entit použité ve filtru

```evitaql-syntax
entityPrimaryKeyInFilter()
```

Toto omezení umožňuje, aby byly výstupní entity seřazeny podle hodnot primárních klíčů přesně v tom pořadí, v jakém byly použity při filtrování.
Omezení vyžaduje přítomnost právě jednoho omezení [`entityPrimaryKeyInSet`](../filtering/constant.md#primární-klíč-entity-v-množině)
v části filtru dotazu. Používá zadané pole primárních klíčů entit k seřazení výsledku,
který dotaz vrátí.

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Entity seřazené podle pořadí filtrovaných primárních klíčů](/documentation/user/en/query/ordering/examples/constant/entity-primary-key-in-filter.evitaql)
</SourceCodeTabs>

Ukázkový dotaz vrátí přesně 4 produkty, které zachovávají pořadí filtrovaných primárních klíčů v dotazu,
který byl zadán.

<Note type="info">

<NoteTitle toggles="true">

##### Seznam produktů seřazených podle pořadí primárních klíčů entit ve filtru
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Entity seřazené podle pořadí filtrovaných primárních klíčů](/documentation/user/en/query/ordering/examples/constant/entity-primary-key-in-filter.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Entity seřazené podle pořadí filtrovaných primárních klíčů](/documentation/user/en/query/ordering/examples/constant/entity-primary-key-in-filter.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Entity seřazené podle pořadí filtrovaných primárních klíčů](/documentation/user/en/query/ordering/examples/constant/entity-primary-key-in-filter.rest.json.md)</MDInclude>

</LS>

</Note>

## Přesné pořadí primárních klíčů entit

```evitaql-syntax
entityPrimaryKeyExact(
    argument:int+
)
```

<dl>
    <dt>argument:int+</dt>
    <dd>
        povinná množina primárních klíčů entit, která určuje pořadí výsledků dotazu
    </dd>
</dl>

Tato podmínka umožňuje seřadit výstupní entity podle primárních klíčů entit v přesném pořadí, které je uvedeno v druhém až
N-tém argumentu této podmínky.

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Entity seřazené podle zadaného pořadí primárních klíčů](/documentation/user/en/query/ordering/examples/constant/entity-primary-key-exact.evitaql)
</SourceCodeTabs>

Ukázkový dotaz vrátí všechny produkty, jejichž kód začíná řetězcem *lenovo*, ale pořadí prvních tří
entit ve výstupu je určeno podle pořadí zadaného v podmínce `entityPrimaryKeyExact`. Protože dotaz vrací více
výsledků, než pro které má podmínka informace, zbytek výsledné množiny je seřazen *tradičně* podle
primárního klíče entity vzestupně. Pokud je v řetězci zadána další podmínka pro řazení, použije se pro seřazení
zbytku výsledků dotazu.

<Note type="info">

<NoteTitle toggles="true">

##### Seznam produktů seřazených podle přesného pořadí primárních klíčů entit
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Entity seřazené podle zadaného pořadí primárních klíčů](/documentation/user/en/query/ordering/examples/constant/entity-primary-key-exact.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Entity seřazené podle zadaného pořadí primárních klíčů](/documentation/user/en/query/ordering/examples/constant/entity-primary-key-exact.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Entity seřazené podle zadaného pořadí primárních klíčů](/documentation/user/en/query/ordering/examples/constant/entity-primary-key-exact.rest.json.md)</MDInclude>

</LS>

</Note>

## Přesné pořadí hodnot atributu entity použité ve filtru

```evitaql-syntax
attributeSetInFilter(
    argument:string!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        povinný název [atributu](../../use/schema.md#atributy), který určuje pořadí výsledků dotazu
    </dd>
</dl>

Tato podmínka umožňuje seřadit výstupní entity podle hodnot zadaného atributu v přesném pořadí, v jakém byly filtrovány. Podmínka vyžaduje přítomnost právě jedné [`attribute-in-set`](../filtering/comparable.md#atribut-v-množině) ve filtrační části dotazu, která odkazuje na atribut se stejným názvem, jaký je použit v prvním argumentu této podmínky. Pro řazení výsledků vrácených dotazem je použito zadané pole hodnot atributu.

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Entity seřazené podle pořadí atributu `code` filtrovaných entit](/documentation/user/en/query/ordering/examples/constant/attribute-set-in-filter.evitaql)
</SourceCodeTabs>

Ukázkový dotaz vrátí přesně 3 produkty, přičemž zachová pořadí hodnot atributu `code` entity, které bylo použito ve filtrační podmínce dotazu.

<Note type="info">

<NoteTitle toggles="true">

##### Seznam produktů seřazených podle pořadí atributu `code` ve filtru
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Entity seřazené podle pořadí atributu `code` filtrovaných entit](/documentation/user/en/query/ordering/examples/constant/attribute-set-in-filter.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Entity seřazené podle pořadí atributu `code` filtrovaných entit](/documentation/user/en/query/ordering/examples/constant/attribute-set-in-filter.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Entity seřazené podle pořadí atributu `code` filtrovaných entit](/documentation/user/en/query/ordering/examples/constant/attribute-set-in-filter.rest.json.md)</MDInclude>

</LS>

</Note>

## Přesné pořadí hodnot atributu entity

```evitaql-syntax
attributeSetExact(
    argument:string!,
    argument:comparable+
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        povinný název [atributu](../../use/schema.md#atributy), který určuje pořadí výsledků dotazu
    </dd>
    <dt>argument:comparable+</dt>
    <dd>
        povinná množina hodnot atributu, jejichž datový typ odpovídá [datovému typu atributu](../../use/schema.md#atributy),
        která definuje pořadí výsledků dotazu
    </dd>
</dl>

Tato podmínka umožňuje řadit výstupní entity podle hodnot atributu v přesném pořadí, které je určeno druhým až
posledním argumentem této podmínky.

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Entity seřazené podle zadaného pořadí hodnot atributu `code`](/documentation/user/en/query/ordering/examples/constant/attribute-set-exact.evitaql)
</SourceCodeTabs>

Ukázkový dotaz vrací všechny produkty, jejichž kód začíná řetězcem *lenovo*, ale pořadí prvních tří
entit ve výstupu je určeno podle pořadí zadaného v podmínce `attributeSetExact`. Protože dotaz vrací více
výsledků, než pro které je určeno pořadí v této podmínce, zbytek výsledné množiny je seřazen *tradičně*
podle primárního klíče entity vzestupně. Pokud je v řetězci použita další podmínka pro řazení, použije se k seřazení
zbytku výsledků dotazu.

<Note type="info">

<NoteTitle toggles="true">

##### Seznam produktů seřazených podle přesného pořadí hodnot atributu entity `code`
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Entity seřazené podle zadaného pořadí hodnot atributu `code`](/documentation/user/en/query/ordering/examples/constant/attribute-set-exact.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Entity seřazené podle zadaného pořadí hodnot atributu `code`](/documentation/user/en/query/ordering/examples/constant/attribute-set-exact.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Entity seřazené podle zadaného pořadí hodnot atributu `code`](/documentation/user/en/query/ordering/examples/constant/attribute-set-exact.rest.json.md)</MDInclude>

</LS>

</Note>