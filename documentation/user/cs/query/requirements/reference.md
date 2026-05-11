---
title: Souhrn referencí
date: '11.5.2026'
perex: |
  Souhrny referencí — historicky známé jako *fasetové filtrování* — jsou základní datovou strukturou,
  která pohání parametrizovaná filtrovací uživatelská rozhraní. Dotaz požaduje od evitaDB, aby vedle
  nalezených entit vrátil i strom všech souvisejících referencí (značka, parametr, skupina, …) s počty
  výskytů, volitelnými predikcemi dopadu výběru a volitelnými číselnými histogramy. Přepínání těchto
  referencí v UI poskytuje uživatelům způsob, jak v reálném čase a se znalostí počtů procházet výsledky.
  Přínosy jsou dvojí: zlepšuje uživatelský zážitek tím, že vyhledávání je cílenější, a měřitelně zvyšuje
  konverze v e-shopech tím, že nakupujícím pomáhá rychle najít produkty splňující jejich kritéria.
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'evitaql'
translated: 'true'
---

![Příklad filtrování podle faset](../../../en/query/requirements/assets/facet-filtering.png "Facet filter example")

Klíčovým faktorem úspěchu vyhledávání řízeného referencemi (fasetového vyhledávání) je pomoci uživatelům
vyhnout se kombinacím, které nevrátí žádné výsledky. Funguje nejlépe, pokud UI postupně omezuje možnosti,
které nedávají smysl vzhledem k již vybraným, a poskytuje přesnou, okamžitou a v reálném čase zpětnou
vazbu o tom, jak by výběr další možnosti rozšířil nebo omezil aktuální výsledek.

Reference se obvykle prezentují jako seznamy zaškrtávacích políček, přepínačů, rozbalovacích nabídek
nebo posuvníků a jsou organizovány do skupin. Možnosti v rámci skupiny obvykle rozšiřují aktuální výběr
(logická disjunkce) a skupiny se obvykle kombinují logickou konjunkcí. Některé možnosti lze negovat
(logická negace) pro vyloučení entit, které jim odpovídají.

Možnosti s vysokou kardinalitou se někdy prezentují jako vyhledávací pole nebo intervalový posuvník,
často spárovaný s histogramem distribuce hodnot, který uživateli umožňuje zadat přesnou hodnotu nebo
číselný rozsah. evitaDB podporuje všechny tyto formy prostřednictvím omezení popsaných v této kapitole.

## Vizualizace v evitaLab

Pokud chcete získat lepší představu o tom, jak je souhrn referencí vypočítán, vyzkoušejte vizualizační
záložku v [evitaLab](https://demo.evitadb.io):

![Vizualizace souhrnu referencí v konzoli evitaLab](../../../en/query/requirements/assets/facet-visualization.png "Reference summary visualization in the evitaLab console")

Vizualizace odráží samotnou strukturu souhrnu:

| Ikona                                                                                                    | Význam                                                                                                                                                                                                                          |
|----------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| ![Reference](../../../en/query/requirements/assets/link-variant-custom.png)                              | Na nejvyšší úrovni vidíte reference, označené ikonou řetězu.                                                                                                                                                                    |
| ![Skupina referencí](../../../en/query/requirements/assets/format-list-group-custom.png)                 | Pod nimi jsou skupiny nalezené uvnitř těchto referencí, označené ikonou skupiny, a pod skupinami jsou jednotlivé možnosti reference.                                                                                            |
| ![Výsledky odpovídající dané možnosti](../../../en/query/requirements/assets/counter-custom.png)         | Počet vrácených entit, které odpovídají této možnosti reference, pokud uživatel nemá vybrané žádné jiné možnosti (tj. [`userFilter`](../filtering/behavioral.md#uživatelský-filtr) je prázdný).                                 |
| ![Aktuální počet výsledků / rozdíl při výběru](../../../en/query/requirements/assets/set-right-custom.png) | Aktuální počet entit odpovídajících filtračním omezením; lomítko jej odděluje od rozdílu v počtu výsledků, pokud by tato možnost byla přidána do uživatelského filtru.                                                       |
| ![Celkový počet výsledků s vybranou možností](../../../en/query/requirements/assets/set-all-custom.png)  | Celkový počet entit, které by výsledek obsahoval, pokud by byla tato možnost vybrána (tj. velikost datové sady, která možnosti odpovídá).                                                                                       |

### Výchozí pravidla výpočtu souhrnu referencí

1. Souhrn referencí je vypočítán pouze pro entity vrácené aktuálním dotazem (s vyloučením vlivu části
   [`userFilter`](../filtering/behavioral.md#uživatelský-filtr) dotazu, pokud je přítomna).
2. Výpočet respektuje každé filtrační omezení umístěné mimo kontejner
   [`userFilter`](../filtering/behavioral.md#uživatelský-filtr).
3. Výchozí vztah mezi možnostmi v rámci skupiny je logická disjunkce (logické OR), pokud není změněn.
4. Výchozí vztah mezi možnostmi v různých skupinách / referencích je logická konjunkce (logické AND),
   pokud není změněn.

<Note type="info">

Výchozí výpočetní vztahy lze změnit pomocí [`facetCalculationRules`](#pravidla-výpočtu-faset) v require
části dotazu. Historické pojmenování `facet*` je zachováno na čtyřech omezeních měnících chování
(`facetGroupsConjunction`, `facetGroupsDisjunction`, `facetGroupsNegation`, `facetGroupsExclusivity`,
`facetCalculationRules`) z důvodu zpětné kompatibility — bez ohledu na název omezení se uplatňují na
reference.

</Note>

## Souhrn referencí

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
        - **IMPACT**: každá nevybraná možnost navíc nese predikci dopadu (`matchCount`,
            `difference`, `hasSense`), která ukazuje, co by se stalo, kdyby ji uživatel vybral; ovlivněno
            [konjunkcí](#konjunkce-skupin-faset), [disjunkcí](#disjunkce-skupin-faset),
            [negací](#negace-skupin-faset) a [pravidly výpočtu](#pravidla-výpočtu-faset)
        </p>
    </dd>
    <dt>filterConstraint:filterBy</dt>
    <dd>
        volitelný filtr omezující, které **jednotlivé možnosti reference** se objeví v souhrnu; může cílit
        pouze na vlastnosti sdílené **všemi** typy odkazovaných entit — pro filtry specifické pro konkrétní
        referenci použijte [`referenceSummaryOfReference`](#souhrn-vybrané-reference)
    </dd>
    <dt>filterConstraint:filterGroupBy</dt>
    <dd>
        volitelný filtr omezující, které **skupiny referencí** se objeví v souhrnu; platí stejné omezení
        sdílených vlastností jako výše
    </dd>
    <dt>orderConstraint:orderBy</dt>
    <dd>
        volitelné řazení, které řídí pořadí možností reference v rámci každé skupiny
    </dd>
    <dt>orderConstraint:orderGroupBy</dt>
    <dd>
        volitelné řazení, které řídí pořadí skupin referencí
    </dd>
    <dt>requireConstraint:entityFetch</dt>
    <dd>
        nejvýše jeden požadavek `entityFetch`, který řídí, která pole **entity reference (možnosti)** se
        načtou; sémantika je shodná s [`entityFetch`](fetching.md#načtení-entity) jinde — podporuje vnořený
        `referenceContent` s dalším `entityFetch` / `entityGroupFetch` pro procházení grafu entit
    </dd>
    <dt>requireConstraint:entityGroupFetch</dt>
    <dd>
        nejvýše jeden požadavek `entityGroupFetch`, který řídí, která pole **entity skupiny reference** se
        načtou
    </dd>
    <dt>requireConstraint:histogramStatistics*</dt>
    <dd>
        nula a více potomků [`histogramStatistics`](#statistiky-histogramu), jeden na každý **pojmenovaný
        bucketovaný index** deklarovaný ve schématu reference (`bucketed` v referenci). Každý potomek
        produkuje histogram pro každou skupinu, klíčovaný primárním klíčem entity skupiny, a slouží jako
        zdroj dat pro posuvníkové widgety řízené pomocí
        [`histogramHaving`](../filtering/references.md#histogram-having). Povoleno pouze tehdy, pokud má
        cílová reference nakonfigurované `bucketed` indexy; jinak je odmítnuto při konstrukci.
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
        <p>hloubka statistik — viz záložka *Java/EvitaQL/C#* pro úplnou sémantiku</p>
    </dd>
    <dt>requireConstraint:entityFetch</dt>
    <dd>
        volitelné načtení referenční entity
    </dd>
    <dt>requireConstraint:histogramStatistics*</dt>
    <dd>
        nula a více potomků [`histogramStatistics`](#statistiky-histogramu), jeden na každý pojmenovaný
        bucketovaný index ve schématu reference; produkuje histogramy pro každou skupinu, klíčované
        primárním klíčem entity skupiny
    </dd>
</dl>

</LS>

<LS to="e,j,r,c">

Požadavek <LS to="j,e,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/ReferenceSummary.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/ReferenceSummary.cs</SourceClass></LS>
spouští výpočet extra výsledku <LS to="j,e,r"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/extraResult/ReferenceSummary.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/ExtraResults/ReferenceSummary.cs</SourceClass></LS>.
Souhrn je **vždy vypočítán jako vedlejší efekt hlavního dotazu na entity** a respektuje stejný rozsah
filtrování jako hlavní výsledek (s vyloučením části
[`userFilter`](../filtering/behavioral.md#uživatelský-filtr)). Pokrývá každou referenci, kterou schéma
označuje jako `faceted`. Přepsání pro konkrétní referenci — odlišná nastavení načítání / filtrování /
řazení nebo odlišné požadavky na histogramy — lze poskytnout pomocí
[`referenceSummaryOfReference`](#souhrn-vybrané-reference); omezení specifické pro referenci
**zcela nahrazuje** odpovídající konfiguraci z generického `referenceSummary`, nikoliv slučuje s ní.

</LS>

<LS to="g">

Souhrn referencí je vystaven jako pole `referenceSummary` v `extraResults`. Každá faseta reference je
dotazována samostatně, takže konfigurace načítání / filtrování / řazení / histogramů specifická pro
referenci se nachází přímo na odpovídajícím poli reference (v GraphQL není potřeba samostatný
`referenceSummaryOfReference`).

</LS>

Pro demonstraci výpočtu si vyžádejme souhrn pro produkty v kategorii *e-čtečky*:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Výpočet souhrnu referencí pro produkty v kategorii *e-čtečky*](/documentation/user/en/query/requirements/examples/facet/reference-summary.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Souhrn referencí v kategorii *e-čtečky*

</NoteTitle>

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.ReferenceSummary">[Souhrn referencí v kategorii *e-čtečky*](/documentation/user/en/query/requirements/examples/facet/reference-summary.evitaql.string.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.referenceSummary">[Souhrn referencí v kategorii *e-čtečky*](/documentation/user/en/query/requirements/examples/facet/reference-summary.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.referenceSummary">[Souhrn referencí v kategorii *e-čtečky*](/documentation/user/en/query/requirements/examples/facet/reference-summary.rest.json.md)</MDInclude>

</LS>

</Note>

### Struktura souhrnu referencí

Souhrn obsahuje pouze entity odkazované entitami vrácenými v aktuální odpovědi dotazu (s vyloučením
vlivu části `userFilter`) a je organizován do třístupňové struktury:

- **[reference](#1-úroveň-reference)**: nejvyšší úroveň — názvy referencí označených jako `faceted`
  ve [schématu entity](../../use/schema.md)
- **[skupina referencí](#2-úroveň-skupina-referencí)**: druhá úroveň — skupiny určené v
  [referencích vrácené entity](../../use/data-model.md#references)
- **[možnost reference](#3-úroveň-možnost-reference)**: třetí úroveň — entity
  [referencí](../../use/data-model.md#references) vrácené entity

#### 1. úroveň: reference

Pro každou referenci označenou jako `faceted` existuje samostatný kontejner obsahující
[skupiny referencí 2. úrovně](#2-úroveň-skupina-referencí). Pokud možnosti pro tuto referenci nejsou
organizovány do skupin (reference postrádá informaci o skupině), souhrn obsahuje jednu skupinu nazvanou
*nezařazené možnosti*.

#### 2. úroveň: skupina referencí

Skupina referencí obsahuje seznam všech [možností reference](#3-úroveň-možnost-reference) dostupných
pro danou kombinaci skupina / reference. Nese také `count` všech entit v aktuálním výsledku dotazu, které
odpovídají alespoň jedné možnosti ve skupině / referenci.
<LS to="e,j,c,r">
Volitelně obsahuje tělo entity skupiny, pokud je specifikován požadavek
[`entityGroupFetch`](#načtení-skupinové-entity).
</LS>
<LS to="g">
Volitelně obsahuje tělo entity skupiny, pokud je specifikováno pole `groupEntity`.
</LS>

Může existovat také speciální „skupina“ pro možnosti, které nejsou přiřazeny k žádné skupině.
<LS to="e,j,c">
Tato skupina je v souhrnu jako vlastnost `nonGroupedStatistics`.
</LS>
<LS to="g,r">
Tato skupina je vrácena jako jedna skupina uvnitř reference.
</LS>

#### 3. úroveň: možnost reference

Možnost reference obsahuje statistiky pro danou možnost:

<dl>
  <dt>count</dt>
  <dd>
    Počet entit v aktuálním výsledku dotazu (včetně omezení uživatelského filtru), které tuto možnost
    obsahují (tj. odkazují na entitu s tímto primárním klíčem).
  </dd>
  <dt>requested</dt>
  <dd>
    `TRUE`, pokud se tato možnost objeví v kontejneru
    [`userFilter`](../filtering/behavioral.md#uživatelský-filtr) tohoto dotazu, jinak `FALSE` (aby UI
    mohlo vykreslit odpovídající zaškrtávací políčko jako zaškrtnuté).
  </dd>
</dl>

<LS to="e,j,c,r">
Volitelně tělo entity možnosti, pokud je specifikován požadavek [`entityFetch`](#načtení-entity).
Pokud je v souhrnu požadována hloubka statistik `IMPACT`, statistiky jednotlivých možností obsahují i
analýzu dopadu s následujícími údaji:
</LS>
<LS to="g">
Volitelně tělo entity možnosti, pokud je specifikováno pole `facetEntity`.
Pokud je požadován objekt `impact`, statistiky jednotlivých možností obsahují i analýzu dopadu s
následujícími údaji:
</LS>

<dl>
  <dt>matchCount</dt>
  <dd>
    Počet entit, které by odpovídaly novému dotazu odvozenému od aktuálního, kdyby byla vybrána tato
    možnost (kdyby existoval odkaz na entitu s tímto primárním klíčem). Aktuální dotaz zůstává
    nezměněn, včetně [`userFilter`](../filtering/behavioral.md#uživatelský-filtr), ale možnost je
    virtuálně přidána pro výpočet hypotetického dopadu.
  </dd>
  <dt>difference</dt>
  <dd>
    Rozdíl mezi `matchCount` (hypotetický výsledek) a aktuálním počtem vrácených entit — velikost
    dopadu. Může být kladný (možnost by výsledek rozšířila), záporný (možnost by jej omezila) nebo `0`
    (žádná změna).
  </dd>
  <dt>hasSense</dt>
  <dd>
    `TRUE`, pokud možnost zkombinovaná s aktuálním dotazem stále vrací nějaké výsledky (matchCount > 0),
    jinak `FALSE`. Umožňuje UI označit odpovídající zaškrtávací políčko jako *zakázané*, pokud by jeho
    výběr přinesl nulové výsledky.
  </dd>
</dl>

### Načítání těl referencí (a skupin)

<LS to="e,j,c,r">

Holý souhrn dává malý smysl bez těl možností reference a jejich skupin. Pro jejich načtení přidejte do
dotazu [`entityFetch`](#načtení-entity) nebo [`entityGroupFetch`](#načtení-skupinové-entity). Rozšiřme
základní příklad tak, abychom získali *kódy* možností a jejich skupin:

</LS>
<LS to="g">

Holý souhrn dává malý smysl bez těl možností reference a jejich skupin. Pro jejich načtení vyžádejte pole
[`facetEntity`](#načtení-entity) nebo [`groupEntity`](#načtení-skupinové-entity). Rozšiřme základní
příklad tak, abychom získali *kódy* možností a jejich skupin:

</LS>

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Souhrn referencí s těly pro produkty v kategorii *e-čtečky*](/documentation/user/en/query/requirements/examples/facet/reference-summary-bodies.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Souhrn referencí v kategorii *e-čtečky* včetně těl odkazovaných entit

</NoteTitle>

Souhrn nyní obsahuje nejen primární klíče, ale také čitelné kódy možností a jejich skupin:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.ReferenceSummary">[Souhrn referencí včetně těl odkazovaných entit](/documentation/user/en/query/requirements/examples/facet/reference-summary-bodies.evitaql.string.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.referenceSummary">[Souhrn referencí včetně těl odkazovaných entit](/documentation/user/en/query/requirements/examples/facet/reference-summary-bodies.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.referenceSummary">[Souhrn referencí včetně těl odkazovaných entit](/documentation/user/en/query/requirements/examples/facet/reference-summary-bodies.rest.json.md)</MDInclude>

</LS>

</Note>

Pokud do dotazu přidáte požadovanou lokalizaci a vyžádáte si lokalizované názvy místo kódů, získáte
výsledek velmi blízký tomu, co by uživatel viděl v UI:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Souhrn referencí s lokalizovanými názvy pro produkty v kategorii *e-čtečky*](/documentation/user/en/query/requirements/examples/facet/reference-summary-localized-bodies.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Souhrn referencí s lokalizovanými názvy

</NoteTitle>

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.ReferenceSummary">[Souhrn referencí s lokalizovanými názvy](/documentation/user/en/query/requirements/examples/facet/reference-summary-localized-bodies.evitaql.string.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.referenceSummary">[Souhrn referencí s lokalizovanými názvy](/documentation/user/en/query/requirements/examples/facet/reference-summary-localized-bodies.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.referenceSummary">[Souhrn referencí s lokalizovanými názvy](/documentation/user/en/query/requirements/examples/facet/reference-summary-localized-bodies.rest.json.md)</MDInclude>

</LS>

</Note>

### Filtrování souhrnu referencí

Souhrn může narůst do velmi velké podoby; kromě toho, že je nepoužitelné jej zobrazovat celý, je také
nákladný na výpočet. Pro jeho zúžení použijte omezení [`filterBy`](../basics.md#filter-by) a
`filterGroupBy` (druhé je stejné jako `filterBy`, ale operuje nad celými skupinami referencí místo
jednotlivých možností).

<LS to="g">

`filterGroupBy` lze specifikovat na každém poli reference vracejícím skupiny; `filterBy` se nachází
hlouběji v definici skupiny na poli `facetStatistics`, které vrací jednotlivé možnosti.

</LS>

<Note type="warning">

<LS to="e,j,c">

Pokud filtr umístíte uvnitř generického požadavku `referenceSummary`, omezení mohou cílit pouze na
filtrovatelné vlastnosti **sdílené všemi** typy odkazovaných entit. Pokud to není možné, rozdělte
generický `referenceSummary` na jeden nebo více požadavků
[`referenceSummaryOfReference`](#souhrn-vybrané-reference), z nichž každý má vlastní filtry specifické
pro danou referenci.

</LS>

<LS to="r">

Možnosti a skupiny lze filtrovat pouze prostřednictvím `referenceXxxSummary` (pole REST specifické pro
referenci), protože filtrační kontejner je specifický pro konkrétní kolekci entit — a tato kolekce není
předem známa pro generický `referenceSummary`.

</LS>

<MDInclude>[Chování filtrování na odkazovaných entitách](/documentation/user/en/query/requirements/assets/referenced-filter-note.md)</MDInclude>

</Note>

Najít neumělý příklad pro filtrování *generického* souhrnu referencí i v naší demo datové sadě je
obtížné, takže příklad je záměrně umělý. Zobrazme pouze možnosti, jejichž atribut *code* obsahuje
podřetězec *ar*, a pouze ve skupinách, jejichž *code* začíná písmenem *o*:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Filtrování možností souhrnu referencí](/documentation/user/en/query/requirements/examples/facet/reference-summary-filtering.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek filtrování souhrnu referencí

</NoteTitle>

Vyhledávání neomezujeme na konkrétní hierarchii — samotný filtr je dostatečně selektivní:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.ReferenceSummary">[Výsledek filtrování souhrnu referencí](/documentation/user/en/query/requirements/examples/facet/reference-summary-filtering.evitaql.string.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.referenceSummary">[Výsledek filtrování souhrnu referencí](/documentation/user/en/query/requirements/examples/facet/reference-summary-filtering.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.referenceSummary">[Výsledek filtrování souhrnu referencí](/documentation/user/en/query/requirements/examples/facet/reference-summary-filtering.rest.json.md)</MDInclude>

</LS>

</Note>

### Řazení souhrnu referencí

Obvykle je souhrn řazen tak, aby byly nejrelevantnější možnosti zobrazeny jako první; totéž platí pro
řazení skupin referencí. Použijte [`orderBy`](../basics.md#order-by) pro řazení možností a `orderGroupBy`
(stejný tvar, aplikovaný na skupiny místo možností) pro úroveň skupin.

<LS to="g">

`orderGroupBy` lze specifikovat na každém poli reference vracejícím skupiny; `orderBy` se nachází
hlouběji v definici skupiny na poli `facetStatistics`, které vrací jednotlivé možnosti.

</LS>

<Note type="warning">

<LS to="e,j,c">

Při řazení uvnitř generického `referenceSummary` mohou omezení cílit pouze na řaditelné vlastnosti
**sdílené všemi** typy odkazovaných entit. Pokud to není možné, rozdělte generický `referenceSummary` na
jeden nebo více požadavků [`referenceSummaryOfReference`](#souhrn-vybrané-reference) s řazením
specifickým pro referenci.

</LS>

<LS to="r">

Možnosti a skupiny lze řadit pouze prostřednictvím `referenceXxxSummary` (pole REST specifické pro
referenci), protože kontejner pořadí je specifický pro konkrétní kolekci entit — a tato kolekce není
předem známa pro generický `referenceSummary`.

</LS>

<MDInclude>[Chování řazení na odkazovaných entitách](/documentation/user/en/query/requirements/assets/referenced-order-note.md)</MDInclude>

</Note>

Seřaďme jak skupiny referencí, tak možnosti abecedně podle jejich anglických názvů:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Řazení možností souhrnu referencí](/documentation/user/en/query/requirements/examples/facet/reference-summary-ordering.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek řazení souhrnu referencí

</NoteTitle>

Souhrn je nyní seřazen tam, kde to dává smysl:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.ReferenceSummary">[Výsledek řazení souhrnu referencí](/documentation/user/en/query/requirements/examples/facet/reference-summary-ordering.evitaql.string.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.referenceSummary">[Výsledek řazení souhrnu referencí](/documentation/user/en/query/requirements/examples/facet/reference-summary-ordering.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.referenceSummary">[Výsledek řazení souhrnu referencí](/documentation/user/en/query/requirements/examples/facet/reference-summary-ordering.rest.json.md)</MDInclude>

</LS>

</Note>

### Statistiky histogramu

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
        povinný `requestedBucketCount` — požadovaný počet sloupců histogramu k vypočítání. Zvolte hodnotu,
        která odpovídá šířce histogramového widgetu v UI v pixelech; typické hodnoty jsou **10–50**.
        Skutečný počet bucketů může být nižší u `OPTIMIZED` / `EQUALIZED_OPTIMIZED` (prázdné buckety se
        odstraní), nikdy však vyšší.
    </dd>
    <dt>argument:enum(STANDARD|OPTIMIZED|EQUALIZED|EQUALIZED_OPTIMIZED)?</dt>
    <dd>
        <p>**Výchozí:** `STANDARD`</p>

        <p>volitelný <LS to="j,e,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/HistogramBehavior.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/HistogramBehavior.cs</SourceClass></LS>
        řídící, jak jsou umístěny hranice bucketů a zda se zachovávají prázdné buckety:</p>

        <p>
        - **STANDARD**: přesně `requestedBucketCount` bucketů stejné šířky, včetně prázdných
        - **OPTIMIZED**: stejné jako `STANDARD`, ale prázdné buckety jsou odstraněny pro hustší zobrazení
            (skutečný počet ≤ požadovaný)
        - **EQUALIZED**: přesně `requestedBucketCount` bucketů s **frekvenčně vyrovnanými** hranicemi
            (každý bucket obsahuje přibližně stejný počet výskytů)
        - **EQUALIZED_OPTIMIZED**: frekvenčně vyrovnané hranice s odstraněním prázdných bucketů
        </p>
    </dd>
    <dt>requireConstraint:entityFetch?</dt>
    <dd>
        volitelné načtení popisující, jak bohatě se mají načíst **odkazované entity (možnosti)**, které
        přispěly do histogramu; odráží standardní [`entityFetch`](fetching.md#načtení-entity)
    </dd>
    <dt>argument:string!+</dt>
    <dd>
        jeden nebo více **názvů indexů histogramu** deklarovaných v klauzuli `bucketed` schématu reference.
        Každý název produkuje samostatný záznam histogramu ve výsledku, klíčovaný názvem histogramu;
        instance bez názvů indexů je odmítnuta při konstrukci.
    </dd>
</dl>

Omezení <LS to="j,e,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/ReferenceHistogramStatistics.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/ReferenceHistogramStatistics.cs</SourceClass></LS>
require se může vyskytovat pouze jako potomek [`referenceSummary`](#souhrn-referencí) nebo
[`referenceSummaryOfReference`](#souhrn-vybrané-reference) a pouze na referencích, které deklarují
alespoň jeden `bucketed` index. Každý histogram je počítán **na úrovni skupiny** cílové reference: pokud
je reference `parameterValues` a bucketovaný index je `intervalParameterValues`, dostanete jeden
histogram na každou skupinu parametrů (*výška*, *váha*, *tloušťka*, …) v odpovídající skupině reference
v souhrnu.

Číselná hodnota vykreslená v každém bucketu pochází z `valueExpression` deklarovaného v bucketovaném
indexu schématu reference (typicky číselný atribut na referenci nebo její odkazované entitě, například
`basicUnitValue`). Výstupní histogram poskytuje:

- celokatalogový rozsah `[min, max]` podkladové hodnoty (vnější úchyty posuvníku)
- seznam bucketů s `threshold` (dolní hranice, inkluzivní), `occurrences` a `relativeFrequency`
- příznak `requested` na každý bucket indikující, zda se protíná s aktivním rozsahem
  [`histogramHaving`](../filtering/references.md#histogram-having)

Rozsah `[min, max]` je vypočítán **odlupováním** všech sourozeneckých `histogramHaving` pod `userFilter`,
které cílí na stejnou trojici `(referenceName, histogramName, groupSelector)`, takže pohyb posuvníkem
nezužuje vlastní vnější úchyty — viz
[invariant tří skupin v behaviorálním filtrování](../filtering/behavioral.md#posuvníky-nezužují-pod-vlastními-úchyty).

Pro připojení histogramů k souhrnu reference použijte v Java / C# vyhrazené factory varianty
`withHistograms` (`referenceSummaryWithHistograms` / `referenceSummaryOfReferenceWithHistograms`), které
existují, aby obešly nejednoznačnost varargs přetížení s factory `EntityFetchRequire...` — omezení
emitovaná do EvitaQL jsou stále běžné `referenceSummary` / `referenceSummaryOfReference`:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Souhrn referencí pro e-čtečky s histogramy váhy, výšky a tloušťky](/documentation/user/en/query/requirements/examples/facet/reference-summary-histograms.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Statistiky histogramu pro e-čtečky

</NoteTitle>

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.ReferenceSummary">[Statistiky histogramu pro e-čtečky](/documentation/user/en/query/requirements/examples/facet/reference-summary-histograms.evitaql.string.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.referenceSummary">[Statistiky histogramu pro e-čtečky](/documentation/user/en/query/requirements/examples/facet/reference-summary-histograms.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.referenceSummary">[Statistiky histogramu pro e-čtečky](/documentation/user/en/query/requirements/examples/facet/reference-summary-histograms.rest.json.md)</MDInclude>

</LS>

</Note>

## Souhrn vybrané reference

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
      povinný název reference deklarovaný ve [schématu entity](../../use/schema.md#reference); reference
      musí být označena jako `faceted`
    </dd>
    <dt>argument:enum(COUNTS|IMPACT)?</dt>
    <dd>
        hloubka statistik, stejná sémantika jako u [`referenceSummary`](#souhrn-referencí); výchozí
        hodnota je `COUNTS`
    </dd>
    <dt>filterConstraint:filterBy</dt>
    <dd>
        filtr na **odkazované entitě (možnosti)** — protože omezení cílí na právě jeden typ reference,
        můžete použít libovolnou filtrovatelnou vlastnost této entity, nikoliv pouze vlastnosti sdílené
        napříč všemi fasetovanými referencemi
    </dd>
    <dt>filterConstraint:filterGroupBy</dt>
    <dd>
        filtr na **entitě skupiny reference**; stejná svoboda specifická pro referenci jako výše
    </dd>
    <dt>orderConstraint:orderBy</dt>
    <dd>
        řazení možností reference v rámci každé skupiny; lze použít libovolnou řaditelnou vlastnost
        entity reference
    </dd>
    <dt>orderConstraint:orderGroupBy</dt>
    <dd>
        řazení skupin referencí podle řaditelných vlastností entity skupiny
    </dd>
    <dt>requireConstraint:entityFetch / entityGroupFetch</dt>
    <dd>
        nejvýše jeden z každého, sémantika shodná s [`referenceSummary`](#souhrn-referencí)
    </dd>
    <dt>requireConstraint:histogramStatistics*</dt>
    <dd>
        nula a více [`histogramStatistics`](#statistiky-histogramu) — stejná pravidla jako pro
        `referenceSummary`, omezeno pouze na tuto referenci
    </dd>
</dl>

Požadavek <LS to="e,j,r"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/ReferenceSummaryOfReference.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/ReferenceSummaryOfReference.cs</SourceClass></LS>
buď stojí samostatně (pokud potřebuje souhrn pouze jedna reference), nebo koexistuje s generickým
[`referenceSummary`](#souhrn-referencí) a **přepisuje jeho výchozí nastavení pro tuto jednu referenci**.
Přepsání je úplné: každé omezení ve variantě specifické pro referenci nahrazuje odpovídající omezení
z generické varianty — nikdy se neslučují. Tento vzorec umožňuje udržovat jednořádkovou generickou
základnu a přizpůsobit pouze ty reference, které to vyžadují.

Zobrazme souhrn referencí pro produkty v kategorii *e-čtečky*, ale vypočtěme jej pouze pro reference
`brand` a `parameterValues`. Možnosti uvnitř `brand` mají být seřazeny abecedně podle názvu; možnosti
uvnitř `parameterValues` mají být seřazeny podle jejich atributu `order` (jak na úrovni skupiny, tak
na úrovni možností) a měly by se objevit pouze skupiny (`parameter`), jejichž příznak
`isVisibleInFilter` je `TRUE`:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Souhrn referencí pro vybrané reference](/documentation/user/en/query/requirements/examples/facet/reference-summary-of-reference.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek souhrnu vybraných referencí

</NoteTitle>

Poměrně komplexní scénář, který procvičuje každou klíčovou funkci souhrnu specifického pro referenci:

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

## Načtení skupinové entity

<LS to="e,j,c,r">

Omezení `entityGroupFetch` použité uvnitř [`referenceSummary`](#souhrn-referencí) nebo
[`referenceSummaryOfReference`](#souhrn-vybrané-reference) je shodné s
[`entityFetch`](fetching.md#načtení-entity). Jediný rozdíl je v tom, že `entityGroupFetch` odkazuje na
schéma entity skupiny deklarované v fasetovaném [schématu reference](../../use/schema.md#reference)
a je pojmenováno odlišně, aby se odlišil požadavek na odkazovanou entitu od požadavku na její skupinu.

</LS>
<LS to="g">

Pole `groupEntity` použité uvnitř objektu skupiny reference v [`referenceSummary`](#souhrn-referencí)
má stejný význam jako [standardní načtení entity](fetching.md#načtení-entity). Jediný rozdíl je v tom,
že `groupEntity` odkazuje na schéma entity skupiny deklarované v fasetovaném
[schématu reference](../../use/schema.md#reference).

</LS>

## Načtení entity

<LS to="e,j,c,r">

Omezení `entityFetch` použité uvnitř [`referenceSummary`](#souhrn-referencí) nebo
[`referenceSummaryOfReference`](#souhrn-vybrané-reference) je shodné s
[`entityFetch`](fetching.md#načtení-entity). Jediný rozdíl je v tom, že `entityFetch` odkazuje na
schéma entity deklarované v fasetovaném [schématu reference](../../use/schema.md#reference).

</LS>

<LS to="g">

Pole `facetEntity` použité uvnitř objektu možnosti reference v [`referenceSummary`](#souhrn-referencí)
má stejný význam jako [standardní načtení entity](fetching.md#načtení-entity). Jediný rozdíl je v tom,
že `facetEntity` odkazuje na schéma entity deklarované v fasetovaném
[schématu reference](../../use/schema.md#reference).

</LS>

## Konjunkce skupin faset

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
        Povinný argument specifikující název [reference](../../use/schema.md#reference), na kterou se toto
        omezení vztahuje.
    </dd>
    <dt>argument:enum(WITH_DIFFERENT_FACETS_IN_GROUP|WITH_DIFFERENT_GROUPS)</dt>
    <dd>
        <p>**Výchozí: `WITH_DIFFERENT_FACETS_IN_GROUP`**</p>
        <p>Volitelný výčtový argument specifikující, zda se má typ vztahu uplatnit na možnosti na konkrétní
        úrovni (v rámci stejné skupiny reference, nebo na možnosti v různých skupinách referencí /
        referencích).</p>
    </dd>
    <dt>filterConstraint:filterBy</dt>
    <dd>
        Volitelné filtrační omezení, které vybere jednu nebo více skupin referencí, jejichž možnosti budou
        kombinovány logickým AND místo výchozího logického OR.

        Pokud filtr není definován, chování se uplatní na všechny skupiny dané reference v souhrnu.
    </dd>
</dl>

Omezení <LS to="j,e,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/FacetGroupsConjunction.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/FacetGroupsConjunction.cs</SourceClass></LS>
mění výchozí chování výpočtu souhrnu referencí pro skupiny specifikované v omezení `filterBy`. Místo
výchozího vztahu ([systémových výchozích hodnot](#výchozí-pravidla-výpočtu-souhrnu-referencí) nebo
[přepsaných výchozích hodnot](#pravidla-výpočtu-faset)) jsou možnosti v cílených skupinách na dané
úrovni kombinovány logickým AND.

<Note type="warning">

<MDInclude>[Chování filtrování na odkazovaných entitách v omezení konjunkce skupin faset](/documentation/user/en/query/requirements/assets/referenced-filter-note.md)</MDInclude>

</Note>

Pro porovnání s výchozím chováním porovnejte stejný dotaz s tímto požadavkem a bez něj. Potřebujeme
dotaz, který cílí na nějakou referenci (řekněme `groups`) a předstírá, že některé možnosti již byly
vyžádány (zaškrtnuty). Pokud nyní vypočítáme analýzu `IMPACT` pro zbytek možností ve skupině, uvidíme,
že se čísla mění:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad konjunkce skupin faset](/documentation/user/en/query/requirements/examples/facet/facet-groups-conjunction.evitaql)

</SourceCodeTabs>

<Note type="info">

`facetGroupsConjunction` v tomto příkladu nenese `filterBy`, takže se uplatní na každou skupinu v
souhrnu — nebo, v tomto konkrétním případě, na možnosti v referenci `groups`, které nejsou součástí
žádné skupiny. Nespecifikujeme ani úroveň, takže se použije výchozí `WITH_DIFFERENT_FACETS_IN_GROUP`.

</Note>

| Výchozí chování                                                                                | Změněné chování                                                                              |
|------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| ![Před](../../../en/query/requirements/assets/facet-conjunction-before.png "Před")             | ![Po](../../../en/query/requirements/assets/facet-conjunction-after.png "Po")                |

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek s invertovaným chováním vztahu možností

</NoteTitle>

Místo zvýšení počtu výsledků analýza dopadu nyní predikuje snížení:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.ReferenceSummary">[Výsledek s invertovaným chováním vztahu možností](/documentation/user/en/query/requirements/examples/facet/facet-groups-conjunction.evitaql.string.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.referenceSummary">[Výsledek s invertovaným chováním vztahu možností](/documentation/user/en/query/requirements/examples/facet/facet-groups-conjunction.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.referenceSummary">[Výsledek s invertovaným chováním vztahu možností](/documentation/user/en/query/requirements/examples/facet/facet-groups-conjunction.rest.json.md)</MDInclude>

</LS>

</Note>

## Disjunkce skupin faset

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
        Povinný argument specifikující název [reference](../../use/schema.md#reference), na kterou se toto
        omezení vztahuje.
    </dd>
    <dt>argument:enum(WITH_DIFFERENT_FACETS_IN_GROUP|WITH_DIFFERENT_GROUPS)</dt>
    <dd>
        <p>**Výchozí: `WITH_DIFFERENT_FACETS_IN_GROUP`**</p>
        <p>Volitelný výčtový argument specifikující, zda se má typ vztahu uplatnit na možnosti na konkrétní
        úrovni (v rámci stejné skupiny reference, nebo na možnosti v různých skupinách referencí /
        referencích).</p>
    </dd>
    <dt>filterConstraint:filterBy</dt>
    <dd>
        Volitelné filtrační omezení, které vybere jednu nebo více skupin referencí, jejichž možnosti budou
        kombinovány logickou disjunkcí (logickým OR) s možnostmi z různých skupin místo výchozí logické
        konjunkce (logického AND).

        Pokud filtr není definován, chování se uplatní na všechny skupiny dané reference v souhrnu.
    </dd>
</dl>

Omezení <LS to="j,e,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/FacetGroupsDisjunction.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/FacetGroupsDisjunction.cs</SourceClass></LS>
mění výchozí chování výpočtu souhrnu referencí pro skupiny specifikované v omezení `filterBy`. Místo
výchozího vztahu ([systémových výchozích hodnot](#výchozí-pravidla-výpočtu-souhrnu-referencí) nebo
[přepsaných výchozích hodnot](#pravidla-výpočtu-faset)) jsou možnosti v cílených skupinách na dané
úrovni kombinovány logickým OR.

<Note type="warning">

<MDInclude>[Chování filtrování na odkazovaných entitách v omezení disjunkce skupin faset](/documentation/user/en/query/requirements/assets/referenced-filter-note.md)</MDInclude>

</Note>

Pro porovnání s výchozím chováním použijeme dotaz, který cílí na nějakou referenci (řekněme
`parameterValues`) a předstírá, že uživatel již vyžádal některé možnosti. Analýza `IMPACT` pro druhou
skupinu pak predikuje rozšíření místo snížení:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad disjunkce skupin faset](/documentation/user/en/query/requirements/examples/facet/facet-groups-disjunction.evitaql)

</SourceCodeTabs>

| Výchozí chování                                                                                | Změněné chování                                                                              |
|------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| ![Před](../../../en/query/requirements/assets/facet-disjunction-before.png "Před")             | ![Po](../../../en/query/requirements/assets/facet-disjunction-after.png "Po")                |

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek s invertovaným chováním vztahu skupin

</NoteTitle>

Místo snížení počtu výsledků analýza dopadu nyní predikuje rozšíření:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.ReferenceSummary">[Výsledek s invertovaným chováním vztahu skupin](/documentation/user/en/query/requirements/examples/facet/facet-groups-disjunction.evitaql.string.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.referenceSummary">[Výsledek s invertovaným chováním vztahu skupin](/documentation/user/en/query/requirements/examples/facet/facet-groups-disjunction.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.referenceSummary">[Výsledek s invertovaným chováním vztahu skupin](/documentation/user/en/query/requirements/examples/facet/facet-groups-disjunction.rest.json.md)</MDInclude>

</LS>

</Note>

## Negace skupin faset

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
        Povinný argument specifikující název [reference](../../use/schema.md#reference), na kterou se toto
        omezení vztahuje.
    </dd>
    <dt>argument:enum(WITH_DIFFERENT_FACETS_IN_GROUP|WITH_DIFFERENT_GROUPS)</dt>
    <dd>
        <p>**Výchozí: `WITH_DIFFERENT_FACETS_IN_GROUP`**</p>
        <p>Volitelný výčtový argument specifikující, zda se má typ vztahu uplatnit na možnosti na konkrétní
        úrovni (v rámci stejné skupiny reference, nebo na možnosti v různých skupinách referencí /
        referencích).</p>
    </dd>
    <dt>filterConstraint:filterBy</dt>
    <dd>
        Volitelné filtrační omezení, které vybere jednu nebo více skupin referencí, jejichž možnosti jsou
        negovány. Místo vrácení položek, které odkazují na danou entitu, výsledek vrátí položky, které
        na ni **neodkazují**.

        Pokud filtr není definován, chování se uplatní na všechny skupiny dané reference v souhrnu.
    </dd>
</dl>

Omezení <LS to="j,e,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/FacetGroupsNegation.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/FacetGroupsNegation.cs</SourceClass></LS>
mění chování možností v každé skupině vybrané pomocí `filterBy`. Místo vrácení položek, které odkazují
na danou entitu, dotaz vrátí položky, které na ni neodkazují.

<Note type="info">

Pokud zůstane druhý argument na systémové výchozí hodnotě, nezáleží na tom, zda nastavíte NEGATION na
úrovni v rámci stejné skupiny reference nebo mezi různými skupinami: podle [De Morganových
zákonů](https://en.wikipedia.org/wiki/De_Morgan%27s_laws) je výsledek stejný (`!a && !b` je ekvivalentní
`!(a || b)`).

</Note>

<Note type="warning">

<MDInclude>[Chování filtrování na odkazovaných entitách v omezení negace skupin faset](/documentation/user/en/query/requirements/assets/referenced-filter-note.md)</MDInclude>

</Note>

Pro demonstraci efektu použijeme dotaz cílící na nějakou referenci (řekněme `parameterValues`) a
označíme některé z jejích skupin jako negované:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad negace skupin faset](/documentation/user/en/query/requirements/examples/facet/facet-groups-negation.evitaql)

</SourceCodeTabs>

| Výchozí chování                                                                          | Změněné chování                                                                          |
|------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------|
| ![Před](../../../en/query/requirements/assets/facet-negation-before.png "Před")          | ![Po](../../../en/query/requirements/assets/facet-negation-after.png "Po")               |

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek s negovaným chováním vztahu možností ve skupině

</NoteTitle>

Predikované výsledky v negovaných skupinách jsou mnohem větší než při výchozím chování: výběr
jakékoliv možnosti ve skupině RAM nyní predikuje tisíce výsledků, zatímco skupina ROM s výchozím
chováním predikuje pouze desítku:

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

## Exkluzivita skupin faset

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
        Povinný argument specifikující název [reference](../../use/schema.md#reference), na kterou se toto
        omezení vztahuje.
    </dd>
    <dt>argument:enum(WITH_DIFFERENT_FACETS_IN_GROUP|WITH_DIFFERENT_GROUPS)</dt>
    <dd>
        <p>**Výchozí: `WITH_DIFFERENT_FACETS_IN_GROUP`**</p>
        <p>Volitelný výčtový argument specifikující, zda se má typ vztahu uplatnit na možnosti na konkrétní
        úrovni (v rámci stejné skupiny reference, nebo na možnosti v různých skupinách referencí /
        referencích).</p>
    </dd>
    <dt>filterConstraint:filterBy</dt>
    <dd>
        Volitelné filtrační omezení, které vybere jednu nebo více skupin referencí, jejichž možnosti jsou
        vzájemně exkluzivní.

        Pokud filtr není definován, chování se uplatní na všechny skupiny dané reference v souhrnu.
    </dd>
</dl>

Omezení <LS to="j,e,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/FacetGroupsExclusivity.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/FacetGroupsExclusivity.cs</SourceClass></LS>
mění chování možností v každé skupině vybrané pomocí `filterBy`. Tento vztah neovlivňuje výstup
dotazu. Je na klientovi, aby zajistil, že na dané úrovni je vybrána pouze jedna možnost. Pokud klient
poskytne více než jednu, systém se vrátí k
[systémovým výchozím hodnotám](#výchozí-pravidla-výpočtu-souhrnu-referencí) (logické OR v rámci stejné
skupiny, logické AND mezi různými skupinami).

[Statistiky dopadu](#3-úroveň-možnost-reference) jsou počítány pro situaci, kdy je vybrána pouze tato
konkrétní možnost a žádné jiné ve stejné skupině / různých skupinách.

<Note type="info">

Protože tento operátor neovlivňuje skutečný výstup výsledku, lze jej použít pouze pro výpočet dopadu,
když chcete vidět efekt výběru pouze jedné možnosti na konkrétní úrovni.

</Note>

Pro demonstraci efektu použijeme dotaz cílící na nějakou referenci (řekněme `parameterValues`) a
označíme některé z jejích skupin jako exkluzivní:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad exkluzivity skupin faset](/documentation/user/en/query/requirements/examples/facet/facet-groups-exclusivity.evitaql)

</SourceCodeTabs>

| Výchozí chování                                                                            | Změněné chování                                                                          |
|--------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------|
| ![Před](../../../en/query/requirements/assets/facet-exclusion-before.png "Před")           | ![Po](../../../en/query/requirements/assets/facet-exclusion-after.png "Po")              |

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek s exkluzivním chováním vztahu možností ve skupině

</NoteTitle>

Predikované výsledky v exkluzivních skupinách se liší od výchozích, kdykoliv existuje nějaký výběr.
Při exkluzivitě aktuální výběr možnosti ve skupině RAM neovlivní predikované počty — zůstávají shodné
s případem bez výběru:

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

## Pravidla výpočtu faset

```evitaql-syntax
facetCalculationRules(
    argument:enum(DISJUNCTION|CONJUNCTION|NEGATION|EXCLUSIVITY)!,
    argument:enum(DISJUNCTION|CONJUNCTION|NEGATION|EXCLUSIVITY)!
)
```

<dl>
    <dt>argument:enum(DISJUNCTION|CONJUNCTION|NEGATION|EXCLUSIVITY)!</dt>
    <dd>
        Povinný argument specifikující výchozí chování vztahu pro možnosti v rámci stejné skupiny
        reference. Výchozí logickou disjunkci (logické OR) můžete změnit na jinou hodnotu.
    </dd>
    <dt>argument:enum(DISJUNCTION|CONJUNCTION|NEGATION|EXCLUSIVITY)!</dt>
    <dd>
        Povinný argument specifikující výchozí chování vztahu pro možnosti mezi různými skupinami
        referencí nebo referencemi. Výchozí logickou konjunkci (logické AND) můžete změnit na jinou
        hodnotu.
    </dd>
</dl>

Požadavek <LS to="j,e,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/FacetCalculationRules.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/FacetCalculationRules.cs</SourceClass></LS>
mění [výchozí chování](#výchozí-pravidla-výpočtu-souhrnu-referencí) výpočtu souhrnu referencí na
specifikované logické operátory. První argument nastavuje výchozí vztah pro možnosti v rámci stejné
skupiny reference; druhý jej nastavuje pro možnosti mezi různými skupinami nebo referencemi.

**Podporované logické operátory:**

<dl>
    <dt>DISJUNCTION</dt>
    <dd>
        Logické OR.

        Vliv na [chování facet-having](../filtering/references.md#facet-having): entita je přítomna ve
        výsledku, pokud má alespoň jednu z vybraných možností na dané úrovni (v rámci stejné skupiny
        reference / mezi různými skupinami).

        Vliv na [statistiky dopadu](#3-úroveň-možnost-reference): logické OR pravděpodobně rozšíří
        počet výsledků v konečné množině.
    </dd>
    <dt>CONJUNCTION</dt>
    <dd>
        Logické AND.

        Vliv na [chování facet-having](../filtering/references.md#facet-having): entita je přítomna ve
        výsledku, pokud má všechny vybrané možnosti na dané úrovni (v rámci stejné skupiny reference /
        mezi různými skupinami).

        Vliv na [statistiky dopadu](#3-úroveň-možnost-reference): logické AND pravděpodobně sníží počet
        výsledků v konečné množině.
    </dd>
    <dt>NEGATION</dt>
    <dd>
        Logické AND NOT.

        Vliv na [chování facet-having](../filtering/references.md#facet-having): entita je přítomna ve
        výsledku, pokud nemá žádnou z vybraných možností na dané úrovni. Pokud zůstane druhý argument na
        systémové výchozí hodnotě, nezáleží na tom, zda je NEGATION nastaveno v rámci stejné skupiny
        reference nebo mezi různými skupinami: podle [De Morganových
        zákonů](https://en.wikipedia.org/wiki/De_Morgan%27s_laws) je výsledek stejný (`!a && !b` je
        ekvivalentní `!(a || b)`).

        Vliv na [statistiky dopadu](#3-úroveň-možnost-reference): logické AND NOT pravděpodobně
        rozšíří počet výsledků, pokud entity v průměru nesou pouze malou část všech možností.
    </dd>
    <dt>EXCLUSIVITY</dt>
    <dd>
        Speciální operátor stanovující, že na dané úrovni lze vybrat pouze jednu možnost (v rámci stejné
        skupiny reference / mezi různými skupinami). Užitečné pro vzájemně exkluzivní reference.

        Vliv na [chování facet-having](../filtering/references.md#facet-having): žádný — je na
        klientovi, aby zajistil, že na dané úrovni je vybrána pouze jedna možnost. Pokud klient poskytne
        více než jednu, systém se vrátí k systémovým výchozím hodnotám (logické OR v rámci stejné
        skupiny, logické AND mezi různými skupinami).

        Vliv na [statistiky dopadu](#3-úroveň-možnost-reference): vypočítaný počet shod a dopad budou
        vypočítány pro situaci, kdy je vybrána pouze tato konkrétní možnost a žádné jiné ve stejné
        skupině / různých skupinách.

        **Poznámka**: protože tento operátor neovlivňuje skutečný výstup výsledku, lze jej použít pouze
        pro specifický výpočet dopadu, když chcete vidět dopad výběru pouze jedné možnosti na konkrétní
        úrovni.
    </dd>
</dl>

<Note type="info">

Změna výchozích pravidel výpočtu souhrnu referencí je obdobná konfiguraci každého jednotlivého vztahu
skupiny pomocí vyhrazených požadavků:

- [Konjunkce skupin faset](#konjunkce-skupin-faset)
- [Disjunkce skupin faset](#disjunkce-skupin-faset)
- [Negace skupin faset](#negace-skupin-faset)
- [Exkluzivita skupin faset](#exkluzivita-skupin-faset)

</Note>

Příklad dotazu, který mění výchozí pravidla výpočtu:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad změny výchozích pravidel výpočtu](/documentation/user/en/query/requirements/examples/facet/change-default-calculation-rules.evitaql)

</SourceCodeTabs>
