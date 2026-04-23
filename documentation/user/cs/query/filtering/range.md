---
title: Filtrování podle rozsahu
date: '17.1.2023'
perex: Filtrování podle rozsahu se používá k filtrování entit podle hodnoty jejich atributů, které jsou typu rozsah. Umožňuje zúžit množinu entit na ty, jejichž atribut rozsahu zahrnuje hodnotu parametru. Pokud je hodnota parametru sama o sobě rozsahem, budou vybrány pouze ty entity, jejichž atribut rozsahu se překrývá s tímto rozsahem.
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
commit: cabcf999e7be5b00e0b13e1228a76a8d9e91cb78
translated: 'true'
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
        název [atributu entity](../../use/schema.md#atributy), jehož [číselný rozsah](../../use/data-types.md#numberrange)
        nebo [časový rozsah](../../use/data-types.md#datetimerange) začíná, končí nebo zahrnuje hodnotu
        ve druhém argumentu
    </dd>
    <dt>argument:comparable!</dt>
    <dd>
        hodnota, která musí být v rozsahu atributu, aby byla tato podmínka splněna
    </dd>
</dl>

`attributeInRange` ověřuje, zda je hodnota ve druhém argumentu v rozsahu hodnoty atributu.
Hodnota je v rozsahu, pokud je rovna začátku nebo konci rozsahu, nebo pokud se nachází mezi začátkem a koncem rozsahu.

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Produkty platné pro prosinec '23](/documentation/user/en/query/filtering/examples/range/attribute-in-range.evitaql)
</SourceCodeTabs>

Vrací seznam produktů s *platným* časovým rozsahem, který zahrnuje datum "2023-12-05T12:00:00+01:00" – jedná se o produkty určené pro vánoční akci. Ve skutečném dotazu byste pravděpodobně chtěli tuto podmínku zkombinovat s [atribut je](comparable.md#atribut-existuje) `NULL` pomocí [logického operátoru OR](logical.md#or), abyste zahrnuli i produkty, které nemají žádný časový rozsah.

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

## Atribut v rozsahu nyní

```evitaql-syntax
attributeInRangeNow(
    argument:string!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        název [atributu entity](../../use/schema.md#atributy), jehož [číselný rozsah](../../use/data-types.md#numberrange)
        nebo [časový rozsah](../../use/data-types.md#datetimerange) začíná, končí nebo zahrnuje hodnotu
        ve druhém argumentu
    </dd>
</dl>

`attributeInRangeNow` ověřuje, zda je aktuální datum a čas v rozsahu hodnoty atributu.
Aktuální datum a čas je v rozsahu, pokud je rovno začátku nebo konci rozsahu, nebo pokud se nachází mezi začátkem a koncem rozsahu.

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Produkty platné nyní](/documentation/user/en/query/filtering/examples/range/attribute-in-range-now.evitaql)
</SourceCodeTabs>

Vrací seznam produktů s *platným* časovým rozsahem, který zahrnuje aktuální datum a čas. Výsledek ukázkového dotazu zde nelze uvést, protože závisí na aktuálním datu a čase a může se v čase měnit.