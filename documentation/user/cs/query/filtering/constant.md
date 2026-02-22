---
title: Konstantní filtrování
perex: Pokud potřebujete získat entity podle jejich primárních klíčů nebo ověřit, zda entity s konkrétními primárními klíči existují v databázi, je konstantní filtrační omezení tím správným místem. Filtrování entit podle jejich primárních klíčů je nejrychlejší způsob, jak přistupovat k entitám v evitaDB.
date: '26.5.2023'
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
commit: cef96d8320d36c91c100c5dfc9c45020b5a7ad0d
translated: 'true'
---
## Primární klíč entity v množině

```evitaql-syntax
entityPrimaryKeyInSet(
    argument:int+
)
```

<dl>
    <dt>argument:int+</dt>
    <dd>
        povinná množina primárních klíčů entit, které představují entity, jež mají být vráceny
    </dd>
</dl>

Tato podmínka omezuje seznam vrácených entit přesným určením jejich primárních klíčů.

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Seznam produktů filtrovaný podle primárního klíče entity](/documentation/user/en/query/filtering/examples/constant/entity-primary-key-in-set.evitaql)

</SourceCodeTabs>

Ukázkový dotaz vrací produkty, jejichž primární klíče jsou uvedeny v podmínce `entityPrimaryKeyInSet`. Na pořadí
primárních klíčů v podmínce nezáleží. Vrácené entity jsou vždy vráceny ve vzestupném pořadí
jejich primárních klíčů, pokud není v dotazu použita klauzule `orderBy`.

<Note type="info">

Pokud chcete, aby byly entity vráceny přesně ve stejném pořadí, v jakém jsou primární klíče uvedeny v argumentu
podmínky `entityPrimaryKeyInSet`, použijte
[omezení řazení `entityPrimaryKeyInFilter`](../ordering/constant.md#přesné-pořadí-primárních-klíčů-entit-použité-ve-filtru).

</Note>

<Note type="info">

<NoteTitle toggles="true">

##### Seznam produktů filtrovaný podle primárního klíče entity
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Entity filtrované podle primárních klíčů](/documentation/user/en/query/filtering/examples/constant/entity-primary-key-in-set.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Entity filtrované podle primárních klíčů](/documentation/user/en/query/filtering/examples/constant/entity-primary-key-in-set.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Entity filtrované podle primárních klíčů](/documentation/user/en/query/filtering/examples/constant/entity-primary-key-in-set.rest.json.md)</MDInclude>

</LS>

</Note>

## Scope

```evitaql-syntax
scope(
    argument:enum(LIVE|ARCHIVED)+
)
```

<dl>
    <dt>argument:enum(LIVE|ARCHIVED)+</dt>
    <dd>
        povinný jeden nebo více enum argumentů reprezentujících scope, ve kterém se má vyhledávat výsledek
    </dd>
</dl>

Filtrace `scope` (<LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/filtering/Scope.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/Queries/Filtering/Scope.cs</SourceClass></LS>) umožňuje určit,
v jakém scope se má výsledek vyhledávat. Jsou dostupné dva scopy:

- `LIVE` – výchozí scope, který vyhledává výsledek v živých datech
- `ARCHIVED` – scope, který vyhledává výsledek v archivovaných datech.

Scopy představují způsob, jakým evitaDB řeší tzv. „soft delete“. Aplikace si může vybrat mezi tvrdým
smazáním a archivací entity, což jednoduše přesune entitu do scope archivu. Podrobnosti o procesu archivace
jsou popsány v kapitole [scopy](../../use/schema.md#scopy) a důvody, proč tato funkce existuje, jsou vysvětleny
ve [speciálním blogovém příspěvku](https://evitadb.io/blog/15-soft-delete).

Ve výchozím nastavení se všechny dotazy chovají, jako by v části filtru byla přítomna podmínka `scope(LIVE)`, pokud
si scope neurčíte sami. To znamená, že žádná entita z archivního scope nebude vrácena. Pokud má entita
referenci na entitu v archivním scope, podmínka [`referenceHaving`](references.md#reference-having)
nebude splněna, pokud jsou dotazovány pouze entity ve scope `LIVE`. Pokud změníte scope na `scope(ARCHIVE)`, získáte
pouze entity z archivního scope. Můžete také kombinovat entity z obou scopů zadáním
`scope(LIVE, ARCHIVE)`, a v takovém případě může [`referenceHaving`](references.md#reference-having)
zahrnovat entity z různých scopů, než je ten, který je dotazován.

<Note type="warning">

<NoteTitle toggles="true">

##### Specifické chování týkající se unikátních klíčů

</NoteTitle>

Unikátní omezení jsou vynucována pouze v rámci stejného scope. To znamená, že dvě entity v různých scopech mohou mít
stejnou hodnotu unikátního atributu. Pokud přesunete entitu z jednoho scope do druhého, unikátní omezení v rámci
cílového scope se zkontrolují a pokud entita poruší unikátní omezení, přesun je odmítnut.

Pokud dotazujete entity v obou scopech pomocí filtru [inScope](behavioral.md#v-rozsahu) a použijete filtrační
omezení, které přesně odpovídá unikátnímu atributu ([attribute equals](comparable.md#atribut-rovná-se),
[attribute in set](comparable.md#atribut-v-množině), [attribute is](comparable.md#atribut-existuje)),
evitaDB upřednostní entitu z prvního scope uvedeného v podmínce `scope` před entitami ve scopech uvedených
později v této podmínce `scope`. To znamená, že pokud dotazujete jednu entitu podle hodnoty jejího unikátního atributu
(například `URL`) a hledáte entitu v obou scopech, vždy získáte entitu z prvního scope, který ve svém dotazu určíte.
Toto chování se neuplatňuje, pokud je použito pouze částečné shody (například [attribute starts with](string.md#atribut-začíná-na),
atd.).

</Note>

V našem demo datasetu je několik archivovaných entit. Naše schéma je nakonfigurováno tak, aby indexovalo pouze atributy
`URL` a `code` v archivním scope, takže můžeme vyhledávat archivované entity pouze podle těchto atributů a samozřejmě
podle primárního klíče.

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad přístupu k archivovaným entitám](/documentation/user/en/query/filtering/examples/behavioral/archived-entities-listing.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek dotazu na archivované entity
</NoteTitle>

<LS to="e,j,c">

<MDInclude sourceVariable="recordPage">[Výsledek dotazu na archivované entity](/documentation/user/en/query/filtering/examples/behavioral/archived-entities-listing.evitaql.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Výsledek dotazu na archivované entity](/documentation/user/en/query/filtering/examples/behavioral/archived-entities-listing.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledek dotazu na archivované entity](/documentation/user/en/query/filtering/examples/behavioral/archived-entities-listing.rest.json.md)</MDInclude>

</LS>

</Note>

Pokud potřebujeme vyhledávat podle atributu `URL`, který je obvykle unikátní, je zde důležitý rozdíl, a to ten, že
`URL` je unikátní pouze v rámci svého scope. To znamená, že stejná URL může být použita pro různé entity v různých
scopech. To je případ některých našich entit v demo datasetu. Konflikt unikátního klíče mezi různými entitami je
řešen v evitaDB tak, že je upřednostněna živá entita před archivovanou.