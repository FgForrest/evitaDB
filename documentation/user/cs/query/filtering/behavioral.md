---
title: Kontejnery pro behaviorální filtrování
date: '29.11.2024'
perex: Speciální kontejnery pro behaviorální filtrační omezení se používají k definování rozsahu filtračního omezení, který má odlišné zacházení při výpočtech, nebo k určení rozsahu, ve kterém jsou entity vyhledávány.
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
commit: cef96d8320d36c91c100c5dfc9c45020b5a7ad0d
---
## V rozsahu

```evitaql-syntax
inScope(
    argument:enum(LIVE|ARCHIVED)
    filterConstraint:any+
)
```

<dl>
    <dt>argument:enum(LIVE|ARCHIVED)</dt>
    <dd>
        povinný enum argument představující rozsah, na který jsou aplikovány filtrační podmínky ve druhém a následujících
        argumentech
    </dd>
    <dt>filterConstraint:any+</dt>
    <dd>
        jedna nebo více povinných filtračních podmínek, spojených logickým operátorem, používaných k filtrování entit pouze v
        konkrétním rozsahu
    </dd>
</dl>

Kontejner filtru `inScope` (<LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/filtering/FilterInScope.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/Queries/Filtering/FilterInScope.cs</SourceClass></LS>) se používá
k omezení filtračních podmínek tak, aby se vztahovaly pouze na konkrétní rozsah.

Dotazovací engine evitaDB je striktní ohledně indexů a neumožňuje filtrovat nebo řadit podle dat (atributů, referencí,
atd.), pro která nebyl index připraven předem (snaží se vyhnout situacím, kdy by úplné prohledání zhoršilo výkon dotazu).
Rozsahy nám naopak umožňují zbavit se zbytečných indexů, když víme, že je nebudeme potřebovat
(archivovaná data se neočekává, že budou dotazována tak často jako živá data), a uvolnit tak prostředky pro důležitější
úkoly.

Filtrační podmínka [scope](#v-rozsahu) nám umožňuje dotazovat entity v obou rozsazích najednou, což by nebylo možné,
pokud bychom nemohli určit, kterou filtrační podmínku aplikovat na který rozsah. Kontejner `inScope` je navržen právě
pro tuto situaci.

<Note type="info">

Je zřejmé, že kontejner `inScope` není nutný, pokud dotazujeme entity pouze v jednom rozsahu. Pokud jej však v tomto
případě použijete, musí odpovídat rozsahu dotazu. Pokud použijete kontejner `inScope` s rozsahem `LIVE`, ale dotaz je
proveden v rozsahu `ARCHIVED`, engine vrátí chybu.

</Note>

Například v naší demo databázi máme v archivu indexováno pouze několik atributů – konkrétně `url` a `code` a několik
dalších. V archivním rozsahu neindexujeme reference, hierarchii ani ceny. Pokud chceme vyhledávat entity v obou
rozsazích a použít odpovídající filtrační podmínky, musíme použít kontejner `inScope` následujícím způsobem:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Odlišení filtrů v různých rozsazích](/documentation/user/en/query/filtering/examples/behavioral/archived-entities-filtering.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek vybraných entit ve více rozsazích
</NoteTitle>

Výsledek obsahuje dvě entity vybrané podle atributu URL. Entita v živém rozsahu také splňuje hierarchické a cenové
podmínky uvedené v kontejneru `inScope`. Tyto podmínky však nemusí být platné pro entitu v archivním rozsahu, jak je
vidět při pohledu na vstupní dotaz.

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Výsledek vybraných entit ve více rozsazích](/documentation/user/en/query/filtering/examples/behavioral/archived-entities-filtering.evitaql.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Výsledek vybraných entit ve více rozsazích](/documentation/user/en/query/filtering/examples/behavioral/archived-entities-listing.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek vybraných entit ve více rozsazích](/documentation/user/en/query/filtering/examples/behavioral/archived-entities-filtering.rest.json.md)</MDInclude>

</LS>

</Note>

<Note type="info">

Podobné kontejnery `scope` jsou dostupné také pro [řadicí podmínky](../ordering/behavioral.md#v-rozsahu)
a [požadavkové podmínky](../requirements/behavioral.md#v-rozsahu) se stejným účelem a významem.

</Note>

## Uživatelský filtr

```evitaql-syntax
userFilter(
    filterConstraint:any+
)
```

<dl>
    <dt>filterConstraint:any+</dt>
    <dd>
        jedna nebo více povinných filtračních podmínek, které budou spojeny logickou konjunkcí
    </dd>
</dl>

<LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/UserFilter.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Filter/UserFilter.cs</SourceClass></LS>
funguje totožně jako podmínka [`and`](logical.md#and), ale rozlišuje rozsah filtru, který je ovládán uživatelem
prostřednictvím nějakého uživatelského rozhraní, od zbytku dotazu, který obsahuje povinné podmínky na výslednou
množinu. Uživatelsky definovaný rozsah lze měnit během určitých výpočtů (například při výpočtu [facety](../requirements/facet.md#fasetový-souhrn)
nebo [histogramu](../requirements/histogram.md)), zatímco povinná část mimo `userFilter` zůstává neměnná.

Podívejme se na příklad, kde je podmínka [`facetHaving`](references.md#facet-having) použita uvnitř
kontejneru `userFilter`:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad kontejneru user filter](/documentation/user/en/query/filtering/examples/behavioral/user-filter.evitaql)

</SourceCodeTabs>

A porovnejme to se situací, kdy kontejner `userFilter` odstraníme:

| Souhrn facety s `facetHaving` v `userFilter`      | Souhrn facety bez rozsahu `userFilter`         |
|---------------------------------------------------|------------------------------------------------|
| ![Před](../../../en/query/filtering/assets/user-filter-before.png "Before")   | ![Po](../../../en/query/filtering/assets/user-filter-after.png "After")    |

Jak je vidět na druhém obrázku, souhrn facety je výrazně zredukován na jedinou možnost facety, kterou vybral
uživatel. Protože je faceta v tomto případě považována za "povinnou" podmínku, chová se stejně jako
podmínka [`referenceHaving`](references.md#reference-having), která je kombinována s ostatními podmínkami pomocí logické
disjunkce. Protože neexistuje žádná jiná entita, která by odkazovala jak na značku *amazon*, tak na jinou značku (produkt
může mít samozřejmě pouze jednu značku), ostatní možné možnosti jsou automaticky odstraněny ze souhrnu facety,
protože by vedly k prázdné výsledné množině.