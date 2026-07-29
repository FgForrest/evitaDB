---
title: Souhrn referencí
date: '11.5.2026'
perex: 'Souhrny referencí — historicky nazývané *filtrování podle faset* — jsou základní datovou strukturou, která pohání parametrizované filtrační uživatelské rozhraní. Dotaz požaduje, aby evitaDB vrátila spolu s nalezenými entitami také strom všech souvisejících referencí (značka, parametr, skupina, …) s počty výskytů, volitelnými predikcemi dopadu a volitelnými číselnými histogramy. Přepínání těchto referencí v uživatelském rozhraní umožňuje uživatelům v reálném čase a s ohledem na počty výskytů zpřesňovat výslednou množinu. Výhody jsou dvojí: zlepšuje uživatelský zážitek díky mnohem cílenějšímu vyhledávání a prokazatelně zvyšuje konverzi na e-commerce stránkách tím, že pomáhá zákazníkům rychleji najít produkty, které odpovídají jejich kritériím.'
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
translated: 'true'
commit: '77da5b36c170430534ee4d9a4a2903da4de68555'
---
![Příklad filtrování pomocí faset](../../../en/query/requirements/assets/facet-filtering.png "Příklad filtrování pomocí faset")

Klíčovým faktorem úspěchu referenčně řízeného (fasetového) vyhledávání je pomoci uživatelům vyhnout se kombinacím, které vracejí nulové výsledky. Nejlépe funguje, když uživatelské rozhraní postupně omezuje možnosti, které by nedávaly smysl vzhledem k již zvoleným možnostem, a poskytuje přesnou, okamžitou a v reálném čase zpětnou vazbu o počtu výsledků, které by výběr další možnosti rozšířil nebo omezil.

Reference jsou obvykle prezentovány jako seznamy zaškrtávacích políček, přepínačů, rozbalovacích nabídek nebo posuvníků a jsou organizovány do skupin. Možnosti v jedné skupině obvykle rozšiřují aktuální výběr (logická disjunkce), skupiny jsou obvykle kombinovány logickou konjunkcí. Některé možnosti lze negovat (logická negace) pro vyloučení entit, které jim odpovídají.

Možnosti s vysokou kardinalitou jsou někdy prezentovány jako vyhledávací pole nebo intervalový posuvník, často ve spojení s histogramem rozložení hodnot, aby uživatelé mohli zadat přesnou hodnotu nebo číselný rozsah. evitaDB podporuje všechny tyto tvary prostřednictvím omezení popsaných v této kapitole.

## Vizualizace v evitaLab

Pokud si chcete vyzkoušet, jak se referenční souhrn počítá, zkuste záložku vizualizace v [evitaLab](https://demo.evitadb.io):

![Vizualizace referenčního souhrnu v konzoli evitaLab](../../../en/query/requirements/assets/facet-visualization.png "Vizualizace referenčního souhrnu v konzoli evitaLab")

Vizualizace zrcadlí strukturu samotného souhrnu:

| Ikona                                                                                          | Význam                                                                                                                                                                                                              |
|-----------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| ![Reference](../../../en/query/requirements/assets/link-variant-custom.png)                                                  | Na nejvyšší úrovni vidíte reference, označené ikonou řetězu.                                                                                                                 |
| ![Skupina referencí](../../../en/query/requirements/assets/format-list-group-custom.png)                                     | Pod nimi jsou skupiny nalezené uvnitř těchto referencí, označené ikonou skupiny, a pod skupinami jsou jednotlivé referenční možnosti.                                        |
| ![Výsledky odpovídající možnosti](../../../en/query/requirements/assets/counter-custom.png)                                  | Počet vrácených entit, které odpovídají této referenční možnosti, když uživatel nemá vybranou žádnou jinou možnost (tj. [`userFilter`](../filtering/behavioral.md#uživatelský-filtr) je prázdný).                         |
| ![Aktuální počet výsledků / rozdíl při výběru](../../../en/query/requirements/assets/set-right-custom.png)                   | Aktuální počet entit odpovídajících filtračním omezením; lomítko odděluje tento počet od rozdílu v počtu výsledků, pokud by byla tato možnost přidána do uživatelského filtru.                                      |
| ![Celkový počet výsledků s touto možností](../../../en/query/requirements/assets/set-all-custom.png)                         | Celkový počet entit, které by výsledek obsahoval, pokud by byla tato možnost vybrána (tj. velikost datasetu, který odpovídá této možnosti).                                                                         |

### Výchozí pravidla výpočtu referencí

1. Referenční souhrn je počítán pouze pro entity vrácené aktuálním dotazem (bez vlivu části [`userFilter`](../filtering/behavioral.md#uživatelský-filtr), pokud je přítomna).
2. Výpočet respektuje všechna filtrační omezení umístěná mimo kontejner [`userFilter`](../filtering/behavioral.md#uživatelský-filtr).
3. Výchozí vztah mezi možnostmi v jedné skupině je logická disjunkce (logické NEBO), pokud není změněno.
4. Výchozí vztah mezi možnostmi v různých skupinách / referencích je logická konjunkce (logické A), pokud není změněno.

<Note type="info">

Výchozí vztahy výpočtu můžete změnit pomocí [`facetCalculationRules`](#pravidla-výpočtu-facet) v části require dotazu. Historické pojmenování `facet*` je zachováno u čtyř omezení měnících chování (`facetGroupsConjunction`, `facetGroupsDisjunction`, `facetGroupsNegation`, `facetGroupsExclusivity`, `facetCalculationRules`) kvůli zpětné kompatibilitě — platí pro reference bez ohledu na název omezení.

</Note>

## Referenční souhrn

<LS to="e,j,c">

```evitaql-syntax
referenceSummary(
    argument:enum(COUNTS|IMPACT)?,
    filterConstraint:filterBy,
    filterConstraint:filterGroupBy,
    orderConstraint:orderBy,
    orderConstraint:orderGroupBy,
    requireConstraint:entityFetch,
    requireConstraint:entityGroupFetch,
    requireConstraint:histogramStatistics*
)
```

<dl>
    <dt>argument:enum(COUNTS|IMPACT)?</dt>
    <dd>
        <p>**Výchozí:** `COUNTS`</p>
        <p>volitelný argument typu <LS to="j,e,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/FacetStatisticsDepth.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/FacetStatisticsDepth.cs</SourceClass></LS>
            určující, jak hluboké budou statistiky pro jednotlivé možnosti:</p>
        <p>
        - **COUNTS** *(výchozí, implicitní)*: každá možnost nese pouze počet vrácených entit, které ji obsahují
        - **IMPACT**: každá nevybraná možnost navíc nese predikci dopadu (`matchCount`, `difference`, `hasSense`), která ukazuje, co by se stalo, kdyby ji uživatel vybral; ovlivněno [konjunkcí](#konjunkce-skupin-facet), [disjunkcí](#disjunkce-skupin-facet), [negací](#negace-skupin-facet) a [pravidly výpočtu](#pravidla-výpočtu-facet)
        </p>
    </dd>
    <dt>filterConstraint:filterBy</dt>
    <dd>
        volitelný filtr omezující, které **jednotlivé referenční možnosti** se objeví v souhrnu; může cílit pouze na vlastnosti sdílené **všemi** typy referencovaných entit — pro referenčně specifické filtry použijte místo toho [`referenceSummaryOfReference`](#souhrn-referenčního-souhrnu)
    </dd>
    <dt>filterConstraint:filterGroupBy</dt>
    <dd>
        volitelný filtr omezující, které **skupiny referencí** se objeví v souhrnu; platí stejná omezení jako výše
    </dd>
    <dt>orderConstraint:orderBy</dt>
    <dd>
        volitelné omezení řazení, které určuje pořadí referenčních možností v rámci každé skupiny
    </dd>
    <dt>orderConstraint:orderGroupBy</dt>
    <dd>
        volitelné omezení řazení, které určuje pořadí skupin referencí
    </dd>
    <dt>requireConstraint:entityFetch</dt>
    <dd>
        maximálně jeden požadavek `entityFetch`, který určuje, která pole **referenční (možnosti) entity** se načtou; identická sémantika jako [`entityFetch`](fetching.md#načtení-entity) jinde — podporuje vnořený `referenceContent` s dalším `entityFetch` / `entityGroupFetch` pro sledování grafu entit
    </dd>
    <dt>requireConstraint:entityGroupFetch</dt>
    <dd>
        maximálně jeden požadavek `entityGroupFetch`, který určuje, která pole **entity skupiny referencí** se načtou
    </dd>
    <dt>requireConstraint:histogramStatistics*</dt>
    <dd>
        nula nebo více potomků [`histogramStatistics`](#histogramové-statistiky), jeden pro **pojmenovaný bucketovaný index** deklarovaný ve schématu reference (`bucketed` na referenci). Každý potomek vytváří histogram na úrovni skupiny, klíčovaný primárním klíčem entity skupiny, a je zdrojem dat pro posuvníkové widgety řízené pomocí [`histogramHaving`](../filtering/references.md#histogram-having). Povolené pouze tehdy, když cílená reference je nakonfigurována s `bucketed` indexy; jinak odmítnuto při konstrukci.
    </dd>
</dl>

</LS>
<LS to="r">

```evitaql-syntax
referenceSummary(
    argument:enum(COUNTS|IMPACT)?,
    requireConstraint:entityFetch,
    requireConstraint:histogramStatistics*
)
```

<dl>
    <dt>argument:enum(COUNTS|IMPACT)?</dt>
    <dd>
        <p>**Výchozí:** `COUNTS`</p>
        <p>hloubka statistik — viz záložka *Java/EvitaQL/C#* pro plnou sémantiku</p>
    </dd>
    <dt>requireConstraint:entityFetch</dt>
    <dd>
        volitelné načtení referenční entity
    </dd>
    <dt>requireConstraint:histogramStatistics*</dt>
    <dd>
        nula nebo více potomků [`histogramStatistics`](#histogramové-statistiky), jeden pro pojmenovaný bucketovaný index ve schématu reference; vytváří histogramy na úrovni skupiny klíčované primárním klíčem skupiny
    </dd>
</dl>

</LS>

<LS to="e,j,r,c">

Požadavek <LS to="j,e,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/ReferenceSummary.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/ReferenceSummary.cs</SourceClass></LS>
spouští výpočet <LS to="j,e,r"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/extraResult/ReferenceSummary.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/ExtraResults/ReferenceSummary.cs</SourceClass></LS>
extra výsledku. Souhrn je **vždy vypočítán jako vedlejší efekt hlavního dotazu na entity** a respektuje stejný rozsah filtrování jako hlavní výsledek (bez části [`userFilter`](../filtering/behavioral.md#uživatelský-filtr)). Pokrývá každou referenci, jejíž schéma ji označuje jako `faceted`. Přepsání na úrovni jednotlivých referencí — různá nastavení načítání / filtrování / řazení nebo různé požadavky na histogram — lze dodat pomocí [`referenceSummaryOfReference`](#souhrn-referenčního-souhrnu); omezení na úrovni reference **zcela nahrazuje** odpovídající konfiguraci z obecného `referenceSummary` místo toho, aby se s ní slučovalo.

</LS>

<LS to="g">

Referenční souhrn je zpřístupněn jako pole `referenceSummary` v rámci `extraResults`. Každá fasetová reference je dotazována zvlášť, takže konfigurace načítání / filtrování / řazení / histogramu pro jednotlivé reference jsou přímo na odpovídajícím poli reference (není potřeba samostatné `referenceSummaryOfReference` v GraphQL).

</LS>

Pro demonstraci výpočtu si vyžádejme souhrn pro produkty v kategorii *e-čtečky*:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Výpočet referenčního souhrnu pro produkty v kategorii *e-čtečky*](/documentation/user/en/query/requirements/examples/facet/reference-summary.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Referenční souhrn v kategorii *e-čtečky*

</NoteTitle>

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.ReferenceSummary">[Referenční souhrn v kategorii *e-čtečky*](/documentation/user/en/query/requirements/examples/facet/reference-summary.evitaql.string.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.referenceSummary">[Referenční souhrn v kategorii *e-čtečky*](/documentation/user/en/query/requirements/examples/facet/reference-summary.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.referenceSummary">[Referenční souhrn v kategorii *e-čtečky*](/documentation/user/en/query/requirements/examples/facet/reference-summary.rest.json.md)</MDInclude>

</LS>

</Note>

### Struktura referenčního souhrnu

Souhrn obsahuje pouze entity referencované entitami vrácenými v aktuální odpovědi na dotaz (bez vlivu části `userFilter`) a je organizován do tříúrovňové struktury:

- **[reference](#1-úroveň-reference)**: nejvyšší úroveň — názvy referencí označených jako `faceted` ve [schématu entity](../../use/schema.md)
- **[skupina referencí](#2-úroveň-skupina-referencí)**: druhá úroveň — skupiny specifikované v [referencích entity](../../use/data-model.md#reference)
- **[možnost reference](#3-úroveň-možnost-reference)**: třetí úroveň — entity z [referencí entity](../../use/data-model.md#reference) vrácené entity

#### 1. úroveň: reference

Pro každou referenci označenou jako `faceted` existuje samostatný kontejner obsahující [skupiny referencí druhé úrovně](#2-úroveň-skupina-referencí). Pokud možnosti této reference nejsou organizovány do skupin (reference nemá informace o skupině), souhrn obsahuje jedinou skupinu nazvanou *nezařazené možnosti*.

#### 2. úroveň: skupina referencí

Skupina referencí uvádí všechny [možnosti reference](#3-úroveň-možnost-reference) dostupné pro danou kombinaci skupina / reference. Nese také `count` všech entit v aktuálním výsledku dotazu, které odpovídají alespoň jedné možnosti ve skupině / referenci.
<LS to="e,j,c,r">
Volitelně obsahuje tělo entity skupiny, pokud je zadán požadavek [`entityGroupFetch`](#načítání-skupiny-entit).
</LS>
<LS to="g">
Volitelně obsahuje tělo entity skupiny, pokud je zadáno pole `groupEntity`.
</LS>

Může zde být také speciální "skupina" pro možnosti, které nejsou přiřazeny ke skupině.
<LS to="e,j,c">
Tato skupina je v souhrnu jako vlastnost `nonGroupedStatistics`.
</LS>
<LS to="g,r">
Tato skupina je vrácena jako jediná skupina uvnitř reference.
</LS>

#### 3. úroveň: možnost reference

Možnost reference obsahuje statistiky pro jednotlivé možnosti:

<dl>
  <dt>count</dt>
  <dd>
    Počet entit v aktuálním výsledku dotazu (včetně omezení uživatelského filtru), které mají tuto možnost (tj. odkazují na entitu s tímto primárním klíčem).
  </dd>
  <dt>requested</dt>
  <dd>
    `TRUE`, pokud se tato možnost vyskytuje v kontejneru [`userFilter`](../filtering/behavioral.md#uživatelský-filtr) tohoto dotazu, jinak `FALSE` (aby UI mohlo vykreslit příslušné zaškrtávací políčko jako zaškrtnuté).
  </dd>
</dl>

<LS to="e,j,c,r">
Volitelně tělo entity možnosti, pokud je zadán požadavek [`entityFetch`](#načítání-entity).
Pokud je v souhrnu požadována hloubka statistik `IMPACT`, statistiky pro jednotlivé možnosti obsahují také analýzu dopadu s následujícími údaji:
</LS>
<LS to="g">
Volitelně tělo entity možnosti, pokud je zadáno pole `facetEntity`.
Pokud je požadován objekt `impact`, statistiky pro jednotlivé možnosti obsahují také analýzu dopadu s následujícími údaji:
</LS>

<dl>
  <dt>matchCount</dt>
  <dd>
    Počet entit, které by odpovídaly novému dotazu odvozenému z aktuálního, pokud by byla tato možnost vybrána (měla referenci na entitu s tímto primárním klíčem). Aktuální dotaz zůstává beze změny, včetně [`userFilter`](../filtering/behavioral.md#uživatelský-filtr), ale možnost je virtuálně přidána pro výpočet hypotetického dopadu.
  </dd>
  <dt>difference</dt>
  <dd>
    Rozdíl mezi `matchCount` (hypotetický výsledek) a aktuálním počtem vrácených entit — velikost dopadu. Může být kladný (možnost by výsledek rozšířila), záporný (možnost by jej omezila) nebo `0` (beze změny).
  </dd>
  <dt>hasSense</dt>
  <dd>
    `TRUE`, pokud kombinace možnosti s aktuálním dotazem stále vrací nějaké výsledky (matchCount > 0), jinak `FALSE`. Umožňuje UI označit příslušné zaškrtávací políčko jako *neaktivní*, pokud by jeho výběr vedl k nulovým výsledkům.
  </dd>
</dl>

### Načítání těl referencí (skupin)

<LS to="e,j,c,r">

Samotný souhrn bez těl referenčních možností a jejich skupin nedává příliš smysl. Pro jejich získání přidejte do dotazu [`entityFetch`](#načítání-entity) nebo [`entityGroupFetch`](#načítání-skupiny-entit). Rozšiřme základní příklad, abychom získali *kódy* možností a jejich skupin:

</LS>
<LS to="g">

Samotný souhrn bez těl referenčních možností a jejich skupin nedává příliš smysl. Pro jejich získání požadujte pole [`facetEntity`](#načítání-entity) nebo [`groupEntity`](#načítání-skupiny-entit). Rozšiřme základní příklad, abychom získali *kódy* možností a jejich skupin:

</LS>

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Referenční souhrn s těly pro produkty v kategorii *e-čtečky*](/documentation/user/en/query/requirements/examples/facet/reference-summary-bodies.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Referenční souhrn v kategorii *e-čtečky* včetně těl referencovaných entit

</NoteTitle>

Nyní souhrn obsahuje nejen primární klíče, ale také čitelné kódy možností a jejich skupin:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.ReferenceSummary">[Referenční souhrn včetně těl referencovaných entit](/documentation/user/en/query/requirements/examples/facet/reference-summary-bodies.evitaql.string.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.referenceSummary">[Referenční souhrn včetně těl referencovaných entit](/documentation/user/en/query/requirements/examples/facet/reference-summary-bodies.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.referenceSummary">[Referenční souhrn včetně těl referencovaných entit](/documentation/user/en/query/requirements/examples/facet/reference-summary-bodies.rest.json.md)</MDInclude>

</LS>

</Note>

Pokud do dotazu přidáte požadovaný jazyk a místo kódů požádáte o lokalizovaná jména, získáte výsledek velmi blízký tomu, co by uživatel viděl v UI:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Referenční souhrn s lokalizovanými názvy pro produkty v kategorii *e-čtečky*](/documentation/user/en/query/requirements/examples/facet/reference-summary-localized-bodies.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Referenční souhrn s lokalizovanými názvy

</NoteTitle>

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.ReferenceSummary">[Referenční souhrn s lokalizovanými názvy](/documentation/user/en/query/requirements/examples/facet/reference-summary-localized-bodies.evitaql.string.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.referenceSummary">[Referenční souhrn s lokalizovanými názvy](/documentation/user/en/query/requirements/examples/facet/reference-summary-localized-bodies.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.referenceSummary">[Referenční souhrn s lokalizovanými názvy](/documentation/user/en/query/requirements/examples/facet/reference-summary-localized-bodies.rest.json.md)</MDInclude>

</LS>

</Note>

### Filtrování referenčního souhrnu

Souhrn může být velmi rozsáhlý; kromě toho, že je zbytečné jej celý zobrazovat, je také náročný na výpočet. Pro jeho zúžení použijte omezení [`filterBy`](../basics.md#filtrování) a `filterGroupBy` (druhé je stejné jako `filterBy`, ale působí na celé skupiny referencí místo jednotlivých možností).

<LS to="g">

`filterGroupBy` lze zadat na každé poli pro jednotlivé reference vracející skupiny; `filterBy` je hlouběji uvnitř definice skupiny na poli `facetStatistics`, které vrací skutečné možnosti.

</LS>

<Note type="warning">

<LS to="e,j,c">

Pokud vložíte filtrování do obecného požadavku `referenceSummary`, omezení mohou cílit pouze na filtrovatelné vlastnosti **sdílené všemi** typy referencovaných entit. Pokud to není možné, rozdělte obecný `referenceSummary` na jeden nebo více požadavků [`referenceSummaryOfReference`](#souhrn-referenčního-souhrnu), každý s vlastním referenčně specifickým filtrem.

</LS>

<LS to="r">

Možnosti a skupiny lze filtrovat pouze pomocí `referenceXxxSummary` (pole REST pro jednotlivé reference), protože kontejner filtru je specifický pro konkrétní kolekci entit — a ta není předem známa pro obecný `referenceSummary`.

</LS>

<MDInclude>[Chování filtrování na referencovaných entitách](/documentation/user/en/query/requirements/assets/referenced-filter-note.md)</MDInclude>

</Note>

Je těžké najít nepřikrášlený příklad filtrování *obecného* referenčního souhrnu i na našem demo datasetu, takže příklad je záměrně umělý. Zobrazme pouze možnosti, jejichž atribut *code* obsahuje podřetězec *ar*, a pouze ve skupinách, jejichž *code* začíná písmenem *o*:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Filtrování možností referenčního souhrnu](/documentation/user/en/query/requirements/examples/facet/reference-summary-filtering.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek filtrování referenčního souhrnu

</NoteTitle>

Neomezujeme hledání na konkrétní hierarchii — samotný filtr je dostatečně selektivní:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.ReferenceSummary">[Výsledek filtrování referenčního souhrnu](/documentation/user/en/query/requirements/examples/facet/reference-summary-filtering.evitaql.string.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.referenceSummary">[Výsledek filtrování referenčního souhrnu](/documentation/user/en/query/requirements/examples/facet/reference-summary-filtering.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.referenceSummary">[Výsledek filtrování referenčního souhrnu](/documentation/user/en/query/requirements/examples/facet/reference-summary-filtering.rest.json.md)</MDInclude>

</LS>

</Note>

### Řazení referenčního souhrnu

Obvykle je souhrn seřazen tak, aby nejrelevantnější možnosti byly nahoře; totéž platí pro řazení skupin referencí. Použijte [`orderBy`](../basics.md#řazení) pro řazení možností a `orderGroupBy` (stejný tvar, ale pro skupiny místo možností) pro úroveň skupin.

<LS to="g">

`orderGroupBy` lze zadat na každé poli pro jednotlivé reference vracející skupiny; `orderBy` je hlouběji uvnitř definice skupiny na poli `facetStatistics`, které vrací skutečné možnosti.

</LS>

<Note type="warning">

<LS to="e,j,c">

Při řazení uvnitř obecného `referenceSummary` mohou omezení cílit pouze na řaditelné vlastnosti **sdílené všemi** typy referencovaných entit. Pokud to není možné, rozdělte obecný `referenceSummary` na jeden nebo více požadavků [`referenceSummaryOfReference`](#souhrn-referenčního-souhrnu) s referenčně specifickým řazením.

</LS>

<LS to="r">

Možnosti a skupiny lze řadit pouze pomocí `referenceXxxSummary` (pole REST pro jednotlivé reference), protože kontejner řazení je specifický pro konkrétní kolekci entit — a ta není předem známa pro obecný `referenceSummary`.

</LS>

<MDInclude>[Chování řazení na referencovaných entitách](/documentation/user/en/query/requirements/assets/referenced-order-note.md)</MDInclude>

</Note>

Seřaďme skupiny referencí i možnosti abecedně podle jejich anglických názvů:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Řazení možností referenčního souhrnu](/documentation/user/en/query/requirements/examples/facet/reference-summary-ordering.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek řazení referenčního souhrnu

</NoteTitle>

Souhrn je nyní seřazen tam, kde to dává smysl:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.ReferenceSummary">[Výsledek řazení referenčního souhrnu](/documentation/user/en/query/requirements/examples/facet/reference-summary-ordering.evitaql.string.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.referenceSummary">[Výsledek řazení referenčního souhrnu](/documentation/user/en/query/requirements/examples/facet/reference-summary-ordering.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.referenceSummary">[Výsledek řazení referenčního souhrnu](/documentation/user/en/query/requirements/examples/facet/reference-summary-ordering.rest.json.md)</MDInclude>

</LS>

</Note>

### Histogramové statistiky

```evitaql-syntax
histogramStatistics(
    argument:int!,
    argument:enum(STANDARD|OPTIMIZED|EQUALIZED|EQUALIZED_OPTIMIZED)?,
    requireConstraint:entityFetch?,
    argument:string!+
)
```

<dl>
    <dt>argument:int!</dt>
    <dd>
        povinný `requestedBucketCount` — požadovaný počet sloupců histogramu k výpočtu. Zvolte hodnotu odpovídající šířce histogramového widgetu v UI v pixelech; typické hodnoty jsou **10–50**. Skutečný počet bucketů může být nižší u `OPTIMIZED` / `EQUALIZED_OPTIMIZED` (prázdné buckety jsou odstraněny), ale nikdy vyšší.
    </dd>
    <dt>argument:enum(STANDARD|OPTIMIZED|EQUALIZED|EQUALIZED_OPTIMIZED)?</dt>
    <dd>
        <p>**Výchozí:** `STANDARD`</p>

        <p>volitelný <LS to="j,e,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/HistogramBehavior.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/HistogramBehavior.cs</SourceClass></LS>
        určující, jak jsou stanoveny hranice bucketů a zda jsou zachovány prázdné buckety:</p>

        <p>
        - **STANDARD**: přesně `requestedBucketCount` stejně širokých bucketů, včetně prázdných
        - **OPTIMIZED**: stejně jako `STANDARD`, ale prázdné buckety jsou odstraněny pro hustší zobrazení (skutečný počet ≤ požadovaný)
        - **EQUALIZED**: přesně `requestedBucketCount` bucketů s **frekvenčně vyrovnanými** hranicemi (každý bucket má přibližně stejný počet výskytů)
        - **EQUALIZED_OPTIMIZED**: frekvenčně vyrovnané hranice s potlačením prázdných bucketů
        </p>
    </dd>
    <dt>requireConstraint:entityFetch?</dt>
    <dd>
        volitelné načtení popisující, jak bohatě mají být **referencované (možnosti) entity**, které přispěly do histogramu, načteny; odpovídá standardnímu [`entityFetch`](fetching.md#načtení-entity)
    </dd>
    <dt>argument:string!+</dt>
    <dd>
        jeden nebo více **názvů histogramových indexů** deklarovaných v klauzuli `bucketed` schématu reference. Každý název vytváří samostatnou položku histogramu ve výsledku, klíčovanou názvem histogramu; instance bez názvů indexů je odmítnuta při konstrukci.
    </dd>
</dl>

Omezení <LS to="j,e,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/ReferenceHistogramStatistics.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/ReferenceHistogramStatistics.cs</SourceClass></LS>
může být použito pouze jako potomek [`referenceSummary`](#referenční-souhrn) nebo [`referenceSummaryOfReference`](#souhrn-referenčního-souhrnu) a pouze na referencích, které deklarují alespoň jeden `bucketed` index. Každý histogram je počítán **pro skupinu** cílené reference: pokud je reference `parameterValues` a bucketovaný index je `intervalParameterValues`, získáte jeden histogram na každou skupinu parametrů (*výška*, *hmotnost*, *tloušťka*, …) uvnitř odpovídající skupiny v souhrnu.

Číselná hodnota vykreslená v každém bucketu pochází z `valueExpression` deklarovaného v bucketovaném indexu schématu reference (typicky číselný atribut na referenci nebo její referencované entitě, například `basicUnitValue`). Výstupní histogram poskytuje:

- celokatalogový rozsah `[min, max]` základní hodnoty (vnější úchyty posuvníku)
- seznam bucketů s `threshold` (dolní hranice, včetně), `occurrences` a `relativeFrequency`
- příznak `requested` pro každý bucket, který označuje, zda se překrývá s aktivním rozsahem [`histogramHaving`](../filtering/references.md#histogram-having)

<Note type="info">

Pokud `valueExpression` odkazuje na **číselný rozsah** atributu (`ByteNumberRange`, `ShortNumberRange`, `IntegerNumberRange`, `LongNumberRange`, `BigDecimalNumberRange`) místo skaláru, histogram je počítán přes intervaly: každá přispívající instance reference je započítána do **každého bucketu, který její interval `[from, to]` překrývá** (uzavřený interval). Pro zdroje rozsahů tedy `overallCount` i výskyty v bucketech počítají *přiřazení*, nikoliv různé instance referencí — jedna instance přesahující N bucketů přidá 1 do každého z těchto N bucketů — a `[min, max]` je určeno z krajních hodnot rozsahů. Viz [Histogramy typované rozsahem](../../use/schema.md#referenční-histogramy) pro detaily na úrovni schématu.

Pokud cílený histogram deklaruje selektor `assignedWhen`, přispívají do něj pouze instance odpovídající tomuto selektoru (a referenční bráně `bucketedPartially`), takže dva histogramy na stejné referenci mohou prezentovat různé řezy stejné základní hodnoty.

</Note>

Rozsah `[min, max]` je počítán **odloupnutím** každého nositele hodnotového rozsahu pod `userFilter` — jak `histogramHaving`, tak sourozenců `attributeBetween` — takže posunutí posuvníku nezmenšuje jeho vlastní vnější úchyty a sourozenecké posuvníky ve stejné rodině si také zachovávají svůj celokatalogový rozsah. Viz [pravidlo peel-by-family v behaviorálním filtrování](../filtering/behavioral.md#jak-userfilter-ovlivňuje-predikce) pro úplnou matici.

Pro připojení histogramů k referenčnímu souhrnu použijte v Javě / C# dedikované tovární varianty `withHistograms` (`referenceSummaryWithHistograms` / `referenceSummaryOfReferenceWithHistograms`), které existují kvůli vyhnutí se nejednoznačnosti varargs s továrnami `EntityFetchRequire...` — omezení generovaná do EvitaQL jsou stále běžná `referenceSummary` / `referenceSummaryOfReference`:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Referenční souhrn e-čteček s histogramy hmotnosti, výšky a tloušťky](/documentation/user/en/query/requirements/examples/facet/reference-summary-histograms.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Histogramové statistiky pro e-čtečky

</NoteTitle>

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.ReferenceSummary">[Histogramové statistiky pro e-čtečky](/documentation/user/en/query/requirements/examples/facet/reference-summary-histograms.evitaql.string.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.referenceSummary">[Histogramové statistiky pro e-čtečky](/documentation/user/en/query/requirements/examples/facet/reference-summary-histograms.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.referenceSummary">[Histogramové statistiky pro e-čtečky](/documentation/user/en/query/requirements/examples/facet/reference-summary-histograms.rest.json.md)</MDInclude>

</LS>

</Note>

## Souhrn referenčního souhrnu

```evitaql-syntax
referenceSummaryOfReference(
    argument:string!,
    argument:enum(COUNTS|IMPACT)?,
    filterConstraint:filterBy,
    filterConstraint:filterGroupBy,
    orderConstraint:orderBy,
    orderConstraint:orderGroupBy,
    requireConstraint:entityFetch,
    requireConstraint:entityGroupFetch,
    requireConstraint:histogramStatistics*
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
      povinný název reference, jak je deklarován ve [schématu entity](../../use/schema.md#reference); reference musí být označena jako `faceted`
    </dd>
    <dt>argument:enum(COUNTS|IMPACT)?</dt>
    <dd>
        hloubka statistik, stejná sémantika jako v [`referenceSummary`](#referenční-souhrn); výchozí je `COUNTS`
    </dd>
    <dt>filterConstraint:filterBy</dt>
    <dd>
        filtr na **referencovanou (volitelnou) entitu** — protože omezení cílí přesně na jeden typ reference, můžete použít jakoukoliv filtrovatelnou vlastnost této entity, nejen vlastnosti sdílené všemi faceted referencemi
    </dd>
    <dt>filterConstraint:filterGroupBy</dt>
    <dd>
        filtr na **entitu skupiny reference**; stejná volnost jako výše pro každou referenci zvlášť
    </dd>
    <dt>orderConstraint:orderBy</dt>
    <dd>
        řazení možností reference v rámci každé skupiny; lze použít jakoukoliv řaditelnou vlastnost referenční entity
    </dd>
    <dt>orderConstraint:orderGroupBy</dt>
    <dd>
        řazení skupin referencí podle řaditelných vlastností skupinové entity
    </dd>
    <dt>requireConstraint:entityFetch / entityGroupFetch</dt>
    <dd>
        maximálně jeden z každého, identická sémantika jako v [`referenceSummary`](#referenční-souhrn)
    </dd>
    <dt>requireConstraint:histogramStatistics*</dt>
    <dd>
        nula nebo více [`histogramStatistics`](#histogramové-statistiky) — stejná pravidla jako pro `referenceSummary`, vztahuje se pouze na tuto referenci
    </dd>
</dl>

Požadavek <LS to="e,j,r"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/ReferenceSummaryOfReference.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/ReferenceSummaryOfReference.cs</SourceClass></LS>
může stát samostatně (pokud je třeba souhrn pouze pro jednu referenci) nebo koexistovat s obecným
[`referenceSummary`](#referenční-souhrn) pro **přepsání jeho výchozího chování pro tuto konkrétní referenci**. Přepsání je
úplné: každé omezení ve variantě pro konkrétní referenci nahrazuje odpovídající omezení z obecné varianty — nikdy se neslučují. Tento vzor vám umožní mít jednorázový obecný základ a upravit pouze ty reference, které to potřebují.

Zobrazme referenční souhrn pro produkty v kategorii *e-readery*, ale vypočítejme jej pouze pro reference `brand`
a `parameterValues`. Možnosti uvnitř `brand` mají být řazeny abecedně podle názvu; možnosti uvnitř
`parameterValues` mají být řazeny podle atributu `order` (na úrovni skupiny i možnosti) a zobrazí se pouze
skupiny (`parameter`), jejichž příznak `isVisibleInFilter` je `TRUE`:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Referenční souhrn pro vybrané reference](/documentation/user/en/query/requirements/examples/facet/reference-summary-of-reference.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek souhrnu vybraných referencí

</NoteTitle>

Poměrně složitý scénář, který využívá všechny klíčové vlastnosti souhrnu podle reference:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.ReferenceSummary">[Výsledek souhrnu vybraných referencí](/documentation/user/en/query/requirements/examples/facet/reference-summary-of-reference.evitaql.string.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.referenceSummary">[Výsledek souhrnu vybraných referencí](/documentation/user/en/query/requirements/examples/facet/reference-summary-of-reference.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.referenceSummary">[Výsledek souhrnu vybraných referencí](/documentation/user/en/query/requirements/examples/facet/reference-summary-of-reference.rest.json.md)</MDInclude>

</LS>

</Note>

## Načítání skupiny entit

<LS to="e,j,c,r">

Omezení `entityGroupFetch` použité uvnitř [`referenceSummary`](#referenční-souhrn) nebo
[`referenceSummaryOfReference`](#souhrn-referenčního-souhrnu) je identické jako
[`entityFetch`](fetching.md#načtení-entity). Jediný rozdíl je, že `entityGroupFetch` odkazuje na schéma skupinové entity
deklarované ve faceted [referenčním schématu](../../use/schema.md#reference) a je pojmenováno odlišně, aby bylo možné
rozlišit požadavek na referencovanou entitu od požadavku na její skupinu.

</LS>
<LS to="g">

Pole `groupEntity` použité uvnitř objektu skupiny reference v [`referenceSummary`](#referenční-souhrn) má stejný význam jako [standardní načítání entity](fetching.md#načtení-entity). Jediný rozdíl je, že `groupEntity` odkazuje na schéma skupinové entity deklarované ve faceted [referenčním schématu](../../use/schema.md#reference).

</LS>

## Načítání entity

<LS to="e,j,c,r">

Omezení `entityFetch` použité uvnitř [`referenceSummary`](#referenční-souhrn) nebo
[`referenceSummaryOfReference`](#souhrn-referenčního-souhrnu) je identické jako
[`entityFetch`](fetching.md#načtení-entity). Jediný rozdíl je, že `entityFetch` odkazuje na schéma entity
deklarované ve faceted [referenčním schématu](../../use/schema.md#reference).

</LS>

<LS to="g">

Pole `facetEntity` použité uvnitř objektu referenční možnosti v [`referenceSummary`](#referenční-souhrn) má stejný význam jako [standardní načítání entity](fetching.md#načtení-entity). Jediný rozdíl je, že `facetEntity` odkazuje na schéma entity deklarované ve faceted [referenčním schématu](../../use/schema.md#reference).

</LS>

## Konjunkce skupin facet

```evitaql-syntax
facetGroupsConjunction(
    argument:string!,
    argument:enum(WITH_DIFFERENT_FACETS_IN_GROUP|WITH_DIFFERENT_GROUPS),
    filterConstraint:filterBy
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        Povinný argument určující název [reference](../../use/schema.md#reference), ke které se toto omezení vztahuje.
    </dd>
    <dt>argument:enum(WITH_DIFFERENT_FACETS_IN_GROUP|WITH_DIFFERENT_GROUPS)</dt>
    <dd>
        <p>**Výchozí: `WITH_DIFFERENT_FACETS_IN_GROUP`**</p>
        <p>Volitelný výčtový argument určující, zda se typ vztahu má aplikovat na možnosti na určité úrovni (v rámci stejné skupiny reference, nebo na možnosti v různých skupinách referencí / referencích).</p>
    </dd>
    <dt>filterConstraint:filterBy</dt>
    <dd>
        Volitelné filtrační omezení, které vybírá jednu nebo více skupin referencí, jejichž možnosti budou kombinovány pomocí logické AND namísto výchozí logické OR.

        Pokud filtr není definován, chování se vztahuje na všechny skupiny dané reference v souhrnu.
    </dd>
</dl>

Požadavek <LS to="j,e,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/FacetGroupsConjunction.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/FacetGroupsConjunction.cs</SourceClass></LS>
mění výchozí chování výpočtu referenčního souhrnu pro skupiny určené v omezení `filterBy`.
Namísto výchozího vztahu ([buď systémové výchozí hodnoty](#výchozí-pravidla-výpočtu-referencí) nebo
[přepsaná výchozí pravidla](#pravidla-výpočtu-facet)) jsou možnosti v cílených skupinách na dané úrovni kombinovány
pomocí logické AND.

<Note type="warning">

<MDInclude>[Chování filtrování na referencovaných entitách v omezení facet groups conjunction](/documentation/user/en/query/requirements/assets/referenced-filter-note.md)</MDInclude>

</Note>

Pro zobrazení rozdílu oproti výchozímu chování porovnejte stejný dotaz s tímto požadavkem a bez něj. Potřebujeme dotaz,
který cílí na nějakou referenci (například `groups`) a předstírá, že některé možnosti již byly požadovány (zaškrtnuty).
Pokud nyní vypočítáme analýzu `IMPACT` pro zbytek možností ve skupině, uvidíme, že se čísla změní:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad konjunkce skupin facet](/documentation/user/en/query/requirements/examples/facet/facet-groups-conjunction.evitaql)

</SourceCodeTabs>

<Note type="info">

V tomto příkladu `facetGroupsConjunction` neobsahuje `filterBy`, takže se vztahuje na každou skupinu v souhrnu — nebo, v tomto konkrétním případě, na možnosti v referenci `groups`, které nejsou součástí žádné skupiny.
Nezadáváme ani úroveň, takže výchozí je `WITH_DIFFERENT_FACETS_IN_GROUP`.

</Note>

| Výchozí chování                                       | Změněné chování                                    |
|-------------------------------------------------------|----------------------------------------------------|
| ![Před](../../../en/query/requirements/assets/facet-conjunction-before.png "Před")   | ![Po](../../../en/query/requirements/assets/facet-conjunction-after.png "Po")     |

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek s obráceným chováním vztahu možností

</NoteTitle>

Namísto zvýšení počtu výsledků nyní analýza dopadu předpovídá snížení:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.ReferenceSummary">[Výsledek s obráceným chováním vztahu možností](/documentation/user/en/query/requirements/examples/facet/facet-groups-conjunction.evitaql.string.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.referenceSummary">[Výsledek s obráceným chováním vztahu možností](/documentation/user/en/query/requirements/examples/facet/facet-groups-conjunction.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.referenceSummary">[Výsledek s obráceným chováním vztahu možností](/documentation/user/en/query/requirements/examples/facet/facet-groups-conjunction.rest.json.md)</MDInclude>

</LS>

</Note>

## Disjunkce skupin facet

```evitaql-syntax
facetGroupsDisjunction(
    argument:string!,
    argument:enum(WITH_DIFFERENT_FACETS_IN_GROUP|WITH_DIFFERENT_GROUPS),
    filterConstraint:filterBy
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        Povinný argument určující název [reference](../../use/schema.md#reference), ke které se toto omezení vztahuje.
    </dd>
    <dt>argument:enum(WITH_DIFFERENT_FACETS_IN_GROUP|WITH_DIFFERENT_GROUPS)</dt>
    <dd>
        <p>**Výchozí: `WITH_DIFFERENT_FACETS_IN_GROUP`**</p>
        <p>Volitelný výčtový argument určující, zda se typ vztahu má aplikovat na možnosti na určité úrovni (v rámci stejné skupiny reference, nebo na možnosti v různých skupinách referencí / referencích).</p>
    </dd>
    <dt>filterConstraint:filterBy</dt>
    <dd>
        Volitelné filtrační omezení, které vybírá jednu nebo více skupin referencí, jejichž možnosti budou kombinovány logickou disjunkcí (logická OR) s možnostmi z různých skupin namísto výchozí logické konjunkce (logická AND).

        Pokud filtr není definován, chování se vztahuje na všechny skupiny dané reference v souhrnu.
    </dd>
</dl>

Požadavek <LS to="j,e,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/FacetGroupsDisjunction.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/FacetGroupsDisjunction.cs</SourceClass></LS>
mění výchozí chování výpočtu referenčního souhrnu pro skupiny určené v omezení `filterBy`.
Namísto výchozího vztahu ([buď systémové výchozí hodnoty](#výchozí-pravidla-výpočtu-referencí) nebo
[přepsaná výchozí pravidla](#pravidla-výpočtu-facet)), jsou možnosti v cílených skupinách na dané úrovni kombinovány
pomocí logické OR.

<Note type="warning">

<MDInclude>[Chování filtrování na referencovaných entitách v omezení facet groups disjunction](/documentation/user/en/query/requirements/assets/referenced-filter-note.md)</MDInclude>

</Note>

Pro srovnání s výchozím chováním použijeme dotaz, který cílí na nějakou referenci (například `parameterValues`) a předstírá, že uživatel již požadoval některé možnosti. Analýza `IMPACT` pro druhou skupinu pak předpovídá rozšíření namísto snížení:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad disjunkce skupin facet](/documentation/user/en/query/requirements/examples/facet/facet-groups-disjunction.evitaql)

</SourceCodeTabs>

| Výchozí chování                                       | Změněné chování                                    |
|-------------------------------------------------------|----------------------------------------------------|
| ![Před](../../../en/query/requirements/assets/facet-disjunction-before.png "Před")   | ![Po](../../../en/query/requirements/assets/facet-disjunction-after.png "Po")     |

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek s obráceným chováním vztahu skupin

</NoteTitle>

Namísto snížení počtu výsledků nyní analýza dopadu předpovídá rozšíření:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.ReferenceSummary">[Výsledek s obráceným chováním vztahu skupin](/documentation/user/en/query/requirements/examples/facet/facet-groups-disjunction.evitaql.string.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.referenceSummary">[Výsledek s obráceným chováním vztahu skupin](/documentation/user/en/query/requirements/examples/facet/facet-groups-disjunction.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.referenceSummary">[Výsledek s obráceným chováním vztahu skupin](/documentation/user/en/query/requirements/examples/facet/facet-groups-disjunction.rest.json.md)</MDInclude>

</LS>

</Note>

## Negace skupin facet

```evitaql-syntax
facetGroupsNegation(
    argument:string!,
    argument:enum(WITH_DIFFERENT_FACETS_IN_GROUP|WITH_DIFFERENT_GROUPS),
    filterConstraint:filterBy
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        Povinný argument určující název [reference](../../use/schema.md#reference), ke které se toto omezení vztahuje.
    </dd>
    <dt>argument:enum(WITH_DIFFERENT_FACETS_IN_GROUP|WITH_DIFFERENT_GROUPS)</dt>
    <dd>
        <p>**Výchozí: `WITH_DIFFERENT_FACETS_IN_GROUP`**</p>
        <p>Volitelný výčtový argument určující, zda se typ vztahu má aplikovat na možnosti na určité úrovni (v rámci stejné skupiny reference, nebo na možnosti v různých skupinách referencí / referencích).</p>
    </dd>
    <dt>filterConstraint:filterBy</dt>
    <dd>
        Volitelné filtrační omezení, které vybírá jednu nebo více skupin referencí, jejichž možnosti jsou negovány. Namísto vrácení položek, které referencují danou entitu, výsledek vrací položky, které ji **nereferencují**.

        Pokud filtr není definován, chování se vztahuje na všechny skupiny dané reference v souhrnu.
    </dd>
</dl>

Požadavek <LS to="j,e,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/FacetGroupsNegation.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/FacetGroupsNegation.cs</SourceClass></LS>
mění chování možností ve všech skupinách vybraných pomocí `filterBy`. Namísto vrácení položek, které referencují danou entitu, dotaz vrací položky, které ji nereferencují.

<Note type="info">

Dokud druhý argument zůstává na systémové výchozí hodnotě, nezáleží na tom, zda nastavíte NEGATION na úrovni ve stejné skupině reference nebo mezi různými skupinami: podle [De Morganových zákonů](https://en.wikipedia.org/wiki/De_Morgan%27s_laws) je výsledek stejný (`!a && !b` je ekvivalentní `!(a || b)`).

</Note>

<Note type="warning">

<MDInclude>[Chování filtrování na referencovaných entitách v omezení facet groups negation](/documentation/user/en/query/requirements/assets/referenced-filter-note.md)</MDInclude>

</Note>

Pro demonstraci efektu použijeme dotaz cílený na nějakou referenci (například `parameterValues`) a označíme některé její skupiny jako negované:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad negace skupin facet](/documentation/user/en/query/requirements/examples/facet/facet-groups-negation.evitaql)

</SourceCodeTabs>

| Výchozí chování                                    | Změněné chování                                    |
|----------------------------------------------------|----------------------------------------------------|
| ![Před](../../../en/query/requirements/assets/facet-negation-before.png "Před")   | ![Po](../../../en/query/requirements/assets/facet-negation-after.png "Po")        |

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek s negovaným chováním vztahu možností ve skupině

</NoteTitle>

Předpokládané výsledky v negovaných skupinách jsou mnohem větší než při výchozím chování: výběr jakékoliv možnosti ve skupině RAM nyní předpovídá tisíce výsledků, zatímco skupina ROM s výchozím chováním předpovídá pouze několik desítek:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.ReferenceSummary">[Výsledek s negovaným chováním vztahu možností ve skupině](/documentation/user/en/query/requirements/examples/facet/facet-groups-negation.evitaql.string.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.referenceSummary">[Výsledek s negovaným chováním vztahu možností ve skupině](/documentation/user/en/query/requirements/examples/facet/facet-groups-negation.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.referenceSummary">[Výsledek s negovaným chováním vztahu možností ve skupině](/documentation/user/en/query/requirements/examples/facet/facet-groups-negation.rest.json.md)</MDInclude>

</LS>

</Note>

## Exkluzivita skupin facet

```evitaql-syntax
facetGroupsExclusivity(
    argument:string!,
    argument:enum(WITH_DIFFERENT_FACETS_IN_GROUP|WITH_DIFFERENT_GROUPS),
    filterConstraint:filterBy
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        Povinný argument určující název [reference](../../use/schema.md#reference), ke které se toto omezení vztahuje.
    </dd>
    <dt>argument:enum(WITH_DIFFERENT_FACETS_IN_GROUP|WITH_DIFFERENT_GROUPS)</dt>
    <dd>
        <p>**Výchozí: `WITH_DIFFERENT_FACETS_IN_GROUP`**</p>
        <p>Volitelný výčtový argument určující, zda se typ vztahu má aplikovat na možnosti na určité úrovni (v rámci stejné skupiny reference, nebo na možnosti v různých skupinách referencí / referencích).</p>
    </dd>
    <dt>filterConstraint:filterBy</dt>
    <dd>
        Volitelné filtrační omezení, které vybírá jednu nebo více skupin referencí, jejichž možnosti jsou vzájemně exkluzivní.

        Pokud filtr není definován, chování se vztahuje na všechny skupiny dané reference v souhrnu.
    </dd>
</dl>

Požadavek <LS to="j,e,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/FacetGroupsExclusivity.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/FacetGroupsExclusivity.cs</SourceClass></LS>
mění chování možností ve všech skupinách vybraných pomocí `filterBy`. Tento vztah neovlivňuje výstup dotazu.
Je na klientovi, aby zajistil, že na dané úrovni je vybrána pouze jedna možnost. Pokud klient poskytne více než jednu, systém se vrátí k [systémovým výchozím hodnotám](#výchozí-pravidla-výpočtu-referencí) (logická OR ve stejné skupině, logická AND mezi různými skupinami).

[Statistiky dopadu](#3-úroveň-možnost-reference) jsou vypočítány pro situaci, kdy je vybrána pouze tato konkrétní možnost a žádná jiná ve stejné skupině / v různých skupinách není.

<Note type="info">

Protože tento operátor neovlivňuje skutečný výstup výsledné množiny, lze jej použít pouze pro výpočet dopadu, když chcete vidět efekt výběru pouze jedné možnosti na určité úrovni.

</Note>

Pro demonstraci efektu použijeme dotaz cílený na nějakou referenci (například `parameterValues`) a označíme některé její skupiny jako exkluzivní:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad exkluzivity skupin facet](/documentation/user/en/query/requirements/examples/facet/facet-groups-exclusivity.evitaql)

</SourceCodeTabs>

| Výchozí chování                                       | Změněné chování                                    |
|-------------------------------------------------------|----------------------------------------------------|
| ![Před](../../../en/query/requirements/assets/facet-exclusion-before.png "Před")     | ![Po](../../../en/query/requirements/assets/facet-exclusion-after.png "Po")       |

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek s exkluzivním chováním vztahu možností ve skupině

</NoteTitle>

Předpokládané výsledky v exkluzivních skupinách se liší od výchozích vždy, když existuje nějaký výběr. S exkluzivitou zůstává aktuální výběr možnosti ve skupině RAM bez vlivu na předpovídané počty — zůstávají stejné jako v případě bez výběru:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.ReferenceSummary">[Výsledek s exkluzivním chováním vztahu možností ve skupině](/documentation/user/en/query/requirements/examples/facet/facet-groups-exclusivity.evitaql.string.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.referenceSummary">[Výsledek s exkluzivním chováním vztahu možností ve skupině](/documentation/user/en/query/requirements/examples/facet/facet-groups-exclusivity.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.referenceSummary">[Výsledek s exkluzivním chováním vztahu možností ve skupině](/documentation/user/en/query/requirements/examples/facet/facet-groups-exclusivity.rest.json.md)</MDInclude>

</LS>

</Note>

## Pravidla výpočtu facet

```evitaql-syntax
facetCalculationRules(
    argument:enum(DISJUNCTION|CONJUNCTION|NEGATION|EXCLUSIVITY)!,
    argument:enum(DISJUNCTION|CONJUNCTION|NEGATION|EXCLUSIVITY)!
)
```

<dl>
    <dt>argument:enum(DISJUNCTION|CONJUNCTION|NEGATION|EXCLUSIVITY)!</dt>
    <dd>
        Povinný argument určující výchozí chování vztahu pro možnosti ve stejné skupině reference. Výchozí logickou disjunkci (logická OR) můžete změnit na jinou hodnotu.
    </dd>
    <dt>argument:enum(DISJUNCTION|CONJUNCTION|NEGATION|EXCLUSIVITY)!</dt>
    <dd>
        Povinný argument určující výchozí chování vztahu pro možnosti mezi různými skupinami referencí nebo referencemi. Výchozí logickou konjunkci (logická AND) můžete změnit na jinou hodnotu.
    </dd>
</dl>

Požadavek <LS to="j,e,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/FacetCalculationRules.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/FacetCalculationRules.cs</SourceClass></LS>
mění [výchozí chování](#výchozí-pravidla-výpočtu-referencí) výpočtu referenčního souhrnu na zadané logické operátory. První argument nastavuje výchozí vztah pro možnosti ve stejné skupině reference; druhý pro možnosti mezi různými skupinami nebo referencemi.

**Podporované logické operátory:**

<dl>
    <dt>DISJUNCTION</dt>
    <dd>
        Logická OR.

        Efekt na [chování facet-having](../filtering/references.md#facet-having): entita je ve výsledku, jakmile má alespoň jednu z vybraných možností na dané úrovni (ve stejné skupině reference / mezi různými skupinami).

        Efekt na [statistiky dopadu](#3-úroveň-možnost-reference): logická OR pravděpodobně rozšíří počet výsledků ve výsledné množině.
    </dd>
    <dt>CONJUNCTION</dt>
    <dd>
        Logická AND.

        Efekt na [chování facet-having](../filtering/references.md#facet-having): entita je ve výsledku, jakmile má všechny vybrané možnosti na dané úrovni (ve stejné skupině reference / mezi různými skupinami).

        Efekt na [statistiky dopadu](#3-úroveň-možnost-reference): logická AND pravděpodobně sníží počet výsledků ve výsledné množině.
    </dd>
    <dt>NEGATION</dt>
    <dd>
        Logická AND NOT.

        Efekt na [chování facet-having](../filtering/references.md#facet-having): entita je ve výsledku, jakmile nemá žádnou z vybraných možností na dané úrovni. Dokud druhý argument zůstává na systémové výchozí hodnotě, nezáleží na tom, zda je NEGATION nastaveno ve stejné skupině reference nebo mezi různými skupinami: podle [De Morganových zákonů](https://en.wikipedia.org/wiki/De_Morgan%27s_laws) je výsledek stejný (`!a && !b` je ekvivalentní `!(a || b)`).

        Efekt na [statistiky dopadu](#3-úroveň-možnost-reference): logická AND NOT pravděpodobně rozšíří počet výsledků, pokud entity obvykle obsahují pouze malou část všech možných možností.
    </dd>
    <dt>EXCLUSIVITY</dt>
    <dd>
        Speciální operátor určující, že na dané úrovni (ve stejné skupině reference / mezi různými skupinami) může být vybrána pouze jedna možnost. Užitečné pro vzájemně exkluzivní reference.

        Efekt na [chování facet-having](../filtering/references.md#facet-having): žádný — je na klientovi, aby zajistil, že na dané úrovni je vybrána pouze jedna možnost. Pokud klient poskytne více než jednu, systém se vrátí k systémovým výchozím hodnotám (logická OR ve stejné skupině, logická AND mezi různými skupinami).

        Efekt na [statistiky dopadu](#3-úroveň-možnost-reference): vypočítaný počet shod a dopad bude vypočítán pro situaci, kdy je vybrána pouze tato konkrétní možnost a žádná jiná ve stejné skupině / v různých skupinách není.

        **Poznámka**: protože tento operátor neovlivňuje skutečný výstup výsledné množiny, lze jej použít pouze pro konkrétní výpočet dopadu, pokud chcete vidět dopad výběru pouze jedné možnosti na určité úrovni.
    </dd>
</dl>

<Note type="info">

Změna výchozích pravidel výpočtu referenčního souhrnu je podobná konfiguraci vztahu každé jednotlivé skupiny pomocí dedikovaných požadavků:

- [Konjunkce skupin facet](#konjunkce-skupin-facet)
- [Disjunkce skupin facet](#disjunkce-skupin-facet)
- [Negace skupin facet](#negace-skupin-facet)
- [Exkluzivita skupin facet](#exkluzivita-skupin-facet)

</Note>

Ukázkový dotaz, který mění výchozí pravidla výpočtu:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad změny výchozích pravidel výpočtu](/documentation/user/en/query/requirements/examples/facet/change-default-calculation-rules.evitaql)

</SourceCodeTabs>