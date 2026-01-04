---
title: Kontejnery pro pořadí chování
date: '29.11.2024'
perex: Speciální kontejnery pro pořadí chování se používají k definování rozsahu omezení pořadí.
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
commit: cef96d8320d36c91c100c5dfc9c45020b5a7ad0d
---
## V rozsahu

```evitaql-syntax
inScope(
    argument:enum(LIVE|ARCHIVED)
    orderConstraint:any+
)
```

<dl>
    <dt>argument:enum(LIVE|ARCHIVED)</dt>
    <dd>
        povinný argument typu enum, který představuje rozsah, na který se vztahují pořadová omezení ve druhém a dalších argumentech
    </dd>
    <dt>orderConstraint:any+</dt>
    <dd>
        jedno nebo více povinných pořadových podmínek, spojených logickým spojením, které se používají k řazení entit pouze v konkrétním rozsahu
    </dd>
</dl>

Kontejner pro pořadí `inScope` (<LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/ordering/OrderInScope.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/Queries/Ordering/OrderInScope.cs</SourceClass></LS>) slouží k omezení pořadových podmínek tak, aby se vztahovaly pouze na konkrétní rozsah.

Dotazovací engine evitaDB je striktní ohledně indexů a nedovoluje řadit nebo třídit podle dat (atributů, referencí atd.), pro která nebyl předem připraven index (snaží se tak zabránit situacím, kdy by úplné prohledání zhoršilo výkon dotazu). Rozsahy nám naopak umožňují zbavit se zbytečných indexů, pokud víme, že je nebudeme potřebovat (s archivovanými daty se neočekává tak časté dotazování jako s živými daty), a uvolnit tak prostředky pro důležitější úkoly.

Pořadová podmínka [inScope](#v-rozsahu) nám umožňuje dotazovat entity v obou rozsazích najednou, což by nebylo možné, pokud bychom nemohli určit, která pořadová podmínka se vztahuje na který rozsah. Kontejner `inScope` je navržen právě pro tuto situaci.

<Note type="info">

Je zřejmé, že kontejner `inScope` není nutný, pokud dotazujeme entity pouze v jednom rozsahu. Pokud jej však v tomto případě použijete, musí odpovídat rozsahu dotazu. Pokud použijete kontejner `inScope` s rozsahem `LIVE`, ale dotaz je proveden v rozsahu `ARCHIVED`, engine vrátí chybu.

</Note>

Například v naší demo databázi máme v archivu indexováno jen několik atributů – konkrétně `url` a `code` a několik dalších. V archivu není žádný atribut s indexem pro řazení. Pokud bychom tedy chtěli vybrat několik entit z obou rozsahů a pokusili se je seřadit podle `name`, dostali bychom chybu. Pokud však použijeme kontejner `inScope`, můžeme entity v živém rozsahu řadit podle `name` a archivované entity nechat seřadit podle pořadí v zadání dotazu.

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Odlišení řazení v různých rozsazích](/documentation/user/en/query/ordering/examples/behavioral/archived-entities-ordering.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek seřazených entit při filtrování z více rozsahů
</NoteTitle>

Výsledek obsahuje dvě živé entity seřazené podle atributu `code`. Ostatní entity pocházejí z rozsahu `ARCHIVED`, který neobsahuje žádný index pro řazení, a jsou seřazeny podle svých primárních klíčů.

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[##### Výsledek seřazených entit při filtrování z více rozsahů
](/documentation/user/en/query/ordering/examples/behavioral/archived-entities-ordering.evitaql.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="data.queryProduct.recordPage">[##### Výsledek seřazených entit při filtrování z více rozsahů
](/documentation/user/en/query/ordering/examples/behavioral/archived-entities-ordering.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[##### Výsledek seřazených entit při filtrování z více rozsahů
](/documentation/user/en/query/ordering/examples/behavioral/archived-entities-ordering.rest.json.md)</MDInclude>

</LS>

</Note>

<Note type="info">

Podobné kontejnery `inScope` jsou k dispozici také pro [filtrovací podmínky](../filtering/behavioral.md#v-rozsahu)
a [požadavkové podmínky](../requirements/behavioral.md#v-rozsahu) se stejným účelem a významem.

</Note>