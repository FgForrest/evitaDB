---
title: Kontejnery s požadavky na chování
date: '11.5.2026'
perex: Speciální kontejnery s požadavky na chování se používají k definování rozsahu požadavkových omezení.
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
translated: 'true'
commit: '4f4bf94120e5667bded014a2e6e81839c94d4a17'
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
        povinný argument typu enum, který představuje rozsah, na který se vztahují require podmínky v druhém a následujících
        argumentech
    </dd>
    <dt>requireConstraint:any+</dt>
    <dd>
        jedna nebo více povinných require podmínek, které jsou spojeny logickým operátorem a slouží k vyžádání entit pouze
        v konkrétním rozsahu
    </dd>
</dl>

Kontejner `inScope` (<LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/RequireInScope.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/Queries/Require/RequireInScope.cs</SourceClass></LS>) se používá
k omezení require podmínek tak, aby se vztahovaly pouze na konkrétní rozsah.

Dotazovací engine evitaDB je striktní ohledně indexů a neumožňuje vyžadovat nebo řadit podle dat (atributů, referencí
atd.), pro která nebyl předem připraven index (snaží se vyhnout situacím, kdy by úplné prohledání zhoršilo výkon dotazu).
Rozsahy nám naopak umožňují zbavit se zbytečných indexů, pokud víme, že je nebudeme potřebovat
(archivovaná data se neočekávají, že budou dotazována tak často jako živá data), a uvolnit tak prostředky pro důležitější
úkoly.

Require omezení [inScope](#v-rozsahu) nám umožňuje dotazovat entity v obou rozsazích najednou,
což by nebylo možné, pokud bychom nemohli určit, která require podmínka se vztahuje na který rozsah. Kontejner `inScope`
je navržen právě pro tuto situaci.

<Note type="info">

Je zřejmé, že kontejner `inScope` není nutný, pokud dotazujeme entity pouze v jednom rozsahu. Pokud jej však v tomto
případě použijete, musí odpovídat rozsahu dotazu. Pokud použijete kontejner `inScope` s rozsahem `LIVE`, ale dotaz je
prováděn v rozsahu `ARCHIVED`, engine vrátí chybu.

</Note>

<LS to="g">

<Note type="warning">

Kontejner omezení `inScope` má omezenou podporu v GraphQL API (zatím lze v konkrétních rozsazích požadovat pouze
dodatečné výsledky).
Stav této záležitosti můžete sledovat v issue [#752](https://github.com/FgForrest/evitaDB/issues/1012).

</Note>

</LS>

Například v naší demo databázi jsme pro archivované entity nevytvořili indexy pro facety ani hierarchii. Informace o
cenách také nejsou indexovány. Pokud byste se pokusili vypočítat souhrn facet nebo histogram pro entity v archivním
rozsahu, engine dotazu by vrátil chybu. Pokud dotazujete entity ve více rozsazích, měli byste použít kontejner `inScope`
a omezit tyto výpočty pouze na ty rozsahy, kde jsou indexy připraveny:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Odlišení require v různých rozsazích](/documentation/user/en/query/requirements/examples/behavioral/archived-entities-requirements.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek požadovaného souhrnu facet a histogramu cen pouze pro entity v živém rozsahu
</NoteTitle>

Jak vidíte, výsledek obsahuje výpočty pouze pro data, která engine dokáže spočítat.

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.PriceHistogram">[##### Výsledek požadovaného histogramu cen pouze pro entity v živém rozsahu
](/documentation/user/en/query/requirements/examples/behavioral/archived-entities-requirements.evitaql.string.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.inScope.priceHistogram">[##### Výsledek požadovaného histogramu cen pouze pro entity v živém rozsahu
](/documentation/user/en/query/requirements/examples/behavioral/archived-entities-requirements.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.priceHistogram">[##### Výsledek požadovaného histogramu cen pouze pro entity v živém rozsahu
](/documentation/user/en/query/requirements/examples/behavioral/archived-entities-requirements.rest.json.md)</MDInclude>

</LS>

</Note>

<Note type="info">

Podobné kontejnery `inScope` jsou k dispozici také pro [filtrační omezení](../filtering/behavioral.md#v-rozsahu)
a [řadicí omezení](../ordering/behavioral.md#v-rozsahu) se stejným účelem a významem.

</Note>

<Note type="info">

Některá require omezení umožňují kombinovat výsledky z více referencí. Například [souhrn referencí](reference.md#referenční-souhrn),
[histogram atributů](histogram.md#histogram-atributu) a [histogram cen](histogram.md#cenový-histogram) lze
vypočítat jak pro živé, tak pro archivované entity, pokud jsou k dispozici odpovídající indexy.

</Note>