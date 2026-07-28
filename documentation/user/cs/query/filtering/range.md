---
title: Filtrování podle rozsahu
date: '17.1.2023'
perex: Filtrování podle rozsahu se používá k filtrování entit podle hodnoty jejich atributů, které jsou typu rozsah. Umožňuje vám zúžit množinu entit na ty, jejichž atribut rozsahu zahrnuje hodnotu parametru. Pokud je hodnota parametru sama o sobě rozsahem, budou vybrány pouze ty entity, jejichž rozsahový atribut se překrývá s tímto rozsahem.
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
translated: 'true'
commit: '77da5b36c170430534ee4d9a4a2903da4de68555'
---
<Note type="info">
V kontextu omezení popsaných v této kapitole by vás mohly zajímat obecná pravidla pro práci s datovými typy a poli, která jsou popsána v [základech dotazovacího jazyka](../basics.md#obecná-pravidla-dotazů).
</Note>

## Atribut v rozsahu

```evitaql-syntax
attributeInRange(
    argument:string!,
    argument:comparable!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        název [atributu entity](../../use/schema.md#atributy), jehož hodnota je [číselný rozsah](../../use/data-types.md#numberrange)
        nebo [časový rozsah](../../use/data-types.md#datetimerange) a začíná, končí nebo zahrnuje hodnotu
        ve druhém argumentu
    </dd>
    <dt>argument:comparable!</dt>
    <dd>
        hodnota, která musí být v rozsahu atributu, aby byla tato podmínka splněna
    </dd>
</dl>

`attributeInRange` ověřuje, zda je hodnota ve druhém argumentu v rozsahu hodnoty atributu.
Hodnota je v rozsahu, pokud je rovna začátku nebo konci rozsahu, nebo pokud je mezi začátkem a koncem
rozsahu.

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Produkty platné pro prosinec '23](/documentation/user/en/query/filtering/examples/range/attribute-in-range.evitaql)
</SourceCodeTabs>

Vrací seznam produktů s *platným* časovým rozsahem, který zahrnuje datum "2023-12-05T12:00:00+01:00" – jedná se o
produkty určené pro vánoční výprodej. V reálném dotazu byste pravděpodobně chtěli tuto podmínku zkombinovat
s [atribut je](comparable.md#atribut-existuje) `NULL` pomocí [logického operátoru OR](logical.md#or), abyste zahrnuli také
produkty, které žádný časový rozsah atributu nemají.

<Note type="info">

<NoteTitle toggles="true">

##### Produkty platné pro prosinec '23
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Produkty platné pro prosinec '23](/documentation/user/en/query/filtering/examples/range/attribute-in-range.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Produkty platné pro prosinec '23](/documentation/user/en/query/filtering/examples/range/attribute-in-range.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Produkty platné pro prosinec '23](/documentation/user/en/query/filtering/examples/range/attribute-in-range.rest.json.md)</MDInclude>

</LS>

</Note>

## Atribut v aktuálním rozsahu

```evitaql-syntax
attributeInRangeNow(
    argument:string!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        název [atributu entity](../../use/schema.md#atributy), jehož hodnota je [číselný rozsah](../../use/data-types.md#numberrange)
        nebo [časový rozsah](../../use/data-types.md#datetimerange), který začíná, končí nebo zahrnuje hodnotu
        ve druhém argumentu
    </dd>
</dl>

`attributeInRangeNow` ověřuje, zda aktuální datum a čas spadá do rozsahu hodnoty atributu.
Aktuální datum a čas je v rozsahu, pokud je rovno začátku nebo konci rozsahu, nebo pokud je mezi začátkem
a koncem rozsahu.

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Produkty platné nyní](/documentation/user/en/query/filtering/examples/range/attribute-in-range-now.evitaql)
</SourceCodeTabs>

Vrací seznam produktů s *platným* datovým/časovým rozsahem, který zahrnuje aktuální datum a čas. Výsledek
příkladového dotazu zde nelze uvést, protože závisí na aktuálním datu a čase a může se čas od času měnit.