---
title: Kontejnery pro behaviorální požadavky
date: '29.11.2024'
perex: Speciální kontejnery pro behaviorální požadavky se používají k definování rozsahu požadavkové podmínky.
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
commit: cef96d8320d36c91c100c5dfc9c45020b5a7ad0d
---
## V rozsahu

```evitaql-syntax
inScope(
    argument:enum(LIVE|ARCHIVED)
    requireConstraint:any+
)
```

<dl>
    <dt>argument:enum(LIVE|ARCHIVED)</dt>
    <dd>
        povinný enum argument představující rozsah, na který se vztahují require podmínky v druhém a dalších
        argumentech
    </dd>
    <dt>requireConstraint:any+</dt>
    <dd>
        jedna nebo více povinných require podmínek, spojených logickou vazbou, které slouží k vyžádání entit pouze v 
        konkrétním rozsahu
    </dd>
</dl>

Kontejner `inScope` (<LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/RequireInScope.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/Queries/Require/RequireInScope.cs</SourceClass></LS>) se používá
k omezení require podmínek tak, aby se vztahovaly pouze na konkrétní rozsah.

Dotazovací engine evitaDB je přísný ohledně indexů a neumožňuje vyžadovat nebo řadit podle dat (atributů, referencí,
atd.), pro která nebyl předem připraven index (snaží se vyhnout situacím, kdy by úplné prohledání zhoršilo výkon dotazu).
Rozsahy nám naopak umožňují zbavit se zbytečných indexů, pokud víme, že je nebudeme potřebovat
(archivovaná data se neočekává, že budou dotazována tak často jako živá data), a uvolnit tak prostředky pro důležitější
úkoly.

Require podmínka [inScope](#v-rozsahu) nám umožňuje dotazovat entity v obou rozsazích najednou,
což by nebylo možné, pokud bychom nemohli určit, která require podmínka se vztahuje na který rozsah. Kontejner `inScope`
je navržen právě pro tuto situaci.

<Note type="info">

Je zřejmé, že kontejner `inScope` není nutný, pokud dotazujeme entity pouze v jednom rozsahu. Pokud jej však v tomto případě použijete, musí odpovídat rozsahu dotazu. Pokud použijete kontejner `inScope` s rozsahem `LIVE`,
ale dotaz je prováděn v rozsahu `ARCHIVED`, engine vrátí chybu.

</Note>

<LS to="g">

<Note type="warning">

Kontejner podmínky `inScope` má omezenou podporu v GraphQL API (zatím lze v konkrétních rozsazích vyžádat pouze extra výsledky).
Stav této záležitosti můžete sledovat v issue [#752](https://github.com/FgForrest/evitaDB/issues/1012).

</Note>

</LS>

Například v naší demo databázi jsme nevytvořili indexy pro facety nebo hierarchii pro archivované entity. Informace o ceně
také není indexována. Pokud byste se pokusili vypočítat souhrn facet nebo histogram pro entity v
archivním rozsahu, engine dotazu by vrátil chybu. Pokud dotazujete entity ve více rozsazích, měli byste
použít kontejner `inScope` a omezit tyto výpočty pouze na ty rozsahy, kde jsou indexy připraveny:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Odlišení require podmínek v různých rozsazích](/documentation/user/en/query/requirements/examples/behavioral/archived-entities-requirements.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek požadovaného souhrnu facet a cenového histogramu pouze pro entity v živém rozsahu
</NoteTitle>

Jak je vidět, výsledek obsahuje výpočty pro data, která engine dokáže spočítat.

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.PriceHistogram">[##### Výsledek požadovaného cenového histogramu pouze pro entity v živém rozsahu
](/documentation/user/en/query/requirements/examples/behavioral/archived-entities-requirements.evitaql.string.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.inScope.priceHistogram">[##### Výsledek požadovaného cenového histogramu pouze pro entity v živém rozsahu
](/documentation/user/en/query/requirements/examples/behavioral/archived-entities-requirements.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.priceHistogram">[##### Výsledek požadovaného cenového histogramu pouze pro entity v živém rozsahu
](/documentation/user/en/query/requirements/examples/behavioral/archived-entities-requirements.rest.json.md)</MDInclude>

</LS>

</Note>

<Note type="info">

Podobné kontejnery `inScope` jsou k dispozici pro [filtrovací podmínky](../filtering/behavioral.md#v-rozsahu)
a [řadicí podmínky](../ordering/behavioral.md#v-rozsahu) se stejným účelem a významem.

</Note>

<Note type="info">

Některé require podmínky umožňují kombinovat výsledky z více facet. Například [souhrn facet](facet.md#fasetový-souhrn),
[atributový histogram](histogram.md#histogram-atributu) a [cenový histogram](histogram.md#cenový-histogram) lze 
vypočítat jak pro živé, tak pro archivované entity, pokud jsou k dispozici odpovídající indexy.

</Note>