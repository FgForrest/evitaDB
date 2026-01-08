---
title: Konstantní pořadí
perex: Existují situace, kdy je pořadí entit určeno mimo evitaDB. Omezení konstantního pořadí umožňují ovládat pořadí vybraných entit logikou volajícího.
date: '17.1.2023'
author: Ing. Jan Novotný
proofreading: needed
preferredLang: evitaql
commit: cabcf999e7be5b00e0b13e1228a76a8d9e91cb78
---
Konstantní pořadové omezení jsou obzvláště užitečná, pokud máte seřazenou množinu unikátních atributů nebo přímo primární klíče entit, které poskytuje externí systém a které je potřeba zachovat ve výstupu evitaDB (například představují relevanci těchto entit z fulltextového enginu).

## Přesné pořadí primárních klíčů entit použité ve filtru

```evitaql-syntax
entityPrimaryKeyInFilter()
```

Toto omezení umožňuje seřadit výstupní entity podle hodnot primárních klíčů přesně v tom pořadí, v jakém byly filtrovány. Omezení vyžaduje přítomnost právě jednoho omezení [`entityPrimaryKeyInSet`](../filtering/constant.md#primární-klíč-entity-v-množině) ve filtrační části dotazu. Používá zadané pole primárních klíčů entit k seřazení výsledku vráceného dotazem.

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Entity seřazené podle pořadí filtrovaných primárních klíčů](/documentation/user/en/query/ordering/examples/constant/entity-primary-key-in-filter.evitaql)
</SourceCodeTabs>

Ukázkový dotaz vrací přesně 4 produkty, které zachovávají pořadí filtrovaných primárních klíčů v dotazu, který byl zadán.

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
        povinná množina primárních klíčů entit, která určuje pořadí výsledku dotazu
    </dd>
</dl>

Toto omezení umožňuje seřadit výstupní entity podle primárních klíčů přesně v pořadí, které je určeno druhým až N-tým argumentem tohoto omezení.

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Entity seřazené podle zadaného pořadí primárních klíčů](/documentation/user/en/query/ordering/examples/constant/entity-primary-key-exact.evitaql)
</SourceCodeTabs>

Ukázkový dotaz vrací všechny produkty, jejichž kód začíná řetězcem *lenovo*, ale pro první tři entity ve výstupu použije pořadí určené omezením `entityPrimaryKeyExact`. Protože dotaz vrací více výsledků, než pro které má omezení informace o pořadí, zbytek výsledné množiny je seřazen *tradičně* podle primárního klíče entity vzestupně. Pokud je v řetězci další pořadové omezení, použije se pro seřazení zbytku výsledku dotazu.

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
        povinný název [atributu](../../use/schema.md#atributy), který určuje pořadí výsledku dotazu
    </dd>
</dl>

Toto omezení umožňuje seřadit výstupní entity podle hodnot zadaného atributu přesně v tom pořadí, v jakém byly filtrovány. Omezení vyžaduje přítomnost právě jednoho omezení [`attribute-in-set`](../filtering/comparable.md#atribut-v-množině) ve filtrační části dotazu, které odkazuje na atribut se stejným názvem, jaký je použit v prvním argumentu tohoto omezení. Používá zadané pole hodnot atributu k seřazení výsledku vráceného dotazem.

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Entity seřazené podle pořadí atributu `code` filtrovaných entit](/documentation/user/en/query/ordering/examples/constant/attribute-set-in-filter.evitaql)
</SourceCodeTabs>

Ukázkový dotaz vrací přesně 3 produkty, přičemž zachovává pořadí hodnot atributu `code` entity použitého ve filtračním omezení dotazu, který byl zadán.

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
        povinný název [atributu](../../use/schema.md#atributy), který určuje pořadí výsledku dotazu
    </dd>
    <dt>argument:comparable+</dt>
    <dd>
        povinná množina hodnot atributu, jejichž datový typ odpovídá [datovému typu atributu](../../use/schema.md#atributy),
        která určuje pořadí výsledku dotazu
    </dd>
</dl>

Toto omezení umožňuje seřadit výstupní entity podle hodnot atributu přesně v pořadí, které je určeno druhým až N-tým argumentem tohoto omezení.

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Entity seřazené podle zadaného pořadí hodnot atributu `code`](/documentation/user/en/query/ordering/examples/constant/attribute-set-exact.evitaql)
</SourceCodeTabs>

Ukázkový dotaz vrací všechny produkty, jejichž kód začíná řetězcem *lenovo*, ale pro první tři entity ve výstupu použije pořadí určené omezením `attributeSetExact`. Protože dotaz vrací více výsledků, než pro které má omezení informace o pořadí, zbytek výsledné množiny je seřazen *tradičně* podle primárního klíče entity vzestupně. Pokud je v řetězci další pořadové omezení, použije se pro seřazení zbytku výsledku dotazu.

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