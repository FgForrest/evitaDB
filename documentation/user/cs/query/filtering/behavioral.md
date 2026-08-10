---
title: Kontejnery pro behaviorální filtrování
date: '11.5.2026'
perex: Speciální kontejnery pro behaviorální filtrační omezení se používají k definování rozsahu filtračního omezení, které má odlišné zacházení při výpočtech, nebo k určení rozsahu, ve kterém jsou entity vyhledávány.
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
translated: 'true'
commit: '6731d435d03fc92c64c9d0cef383290b69a06df7'
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
        povinný enum argument představující rozsah, na který se aplikují filtrační podmínky ve druhém a následujících
        argumentech
    </dd>
    <dt>filterConstraint:any+</dt>
    <dd>
        jedna nebo více povinných filtračních podmínek, spojených logickým operátorem, které slouží k filtrování entit pouze v
        konkrétním rozsahu
    </dd>
</dl>

Kontejner filtru `inScope` (<LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/filtering/FilterInScope.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/Queries/Filtering/FilterInScope.cs</SourceClass></LS>) slouží k omezení filtračních podmínek tak, aby se vztahovaly pouze na konkrétní rozsah.

Dotazovací engine evitaDB je přísný ohledně indexů a neumožňuje filtrovat nebo řadit podle dat (atributů, referencí
atd.), pro která nebyl index připraven předem (snaží se tak zabránit situacím, kdy by plné prohledávání zhoršilo výkon dotazu). Rozsahy nám naopak umožňují zbavit se zbytečných indexů v případech, kdy víme, že je nebudeme potřebovat
(archivovaná data se neočekává, že budou dotazována tak často jako živá data), a uvolnit tak prostředky pro důležitější
úkoly.

Filtrační podmínka [scope](#v-rozsahu) nám umožňuje dotazovat entity v obou rozsazích najednou, což by nebylo možné, pokud
bychom nemohli určit, kterou filtrační podmínku použít pro který rozsah. Kontejner `inScope` je navržen právě pro tuto
situaci.

<Note type="info">

Je zřejmé, že kontejner `inScope` není nutný, pokud dotazujeme entity pouze v jednom rozsahu. Pokud jej však v tomto případě použijete, musí odpovídat rozsahu dotazu. Pokud použijete kontejner `inScope` s rozsahem `LIVE`, ale dotaz je proveden v rozsahu `ARCHIVED`, engine vrátí chybu.

</Note>

Například v naší demo databázi máme v archivu indexováno pouze několik atributů – konkrétně `url` a `code` a několik dalších. V archivním rozsahu neindexujeme reference, hierarchii ani ceny. Pokud chceme vyhledávat entity v obou
rozsazích a použít odpovídající filtrační podmínky, musíme použít kontejner `inScope` následujícím způsobem:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Rozlišení filtrů v různých rozsazích](/documentation/user/en/query/filtering/examples/behavioral/archived-entities-filtering.evitaql)

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
        jeden nebo více povinných filtračních omezení, která vytvoří logickou konjunkci
    </dd>
</dl>

<LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/UserFilter.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Filter/UserFilter.cs</SourceClass></LS>
funguje stejně jako omezení [`and`](logical.md#and), ale rozlišuje rozsah filtru, který je řízen uživatelem
prostřednictvím nějakého uživatelského rozhraní, od zbytku dotazu, který obsahuje povinná omezení na výslednou
množinu. Uživatelem definovaný rozsah může být během určitých výpočtů (například při výpočtu [souhrnu referencí](../requirements/reference.md#referenční-souhrn)
nebo [histogramu](../requirements/histogram.md)) upraven, zatímco povinná část mimo `userFilter` upravit nelze.

Podívejme se na příklad, kde je omezení [`facetHaving`](references.md#facet-having) použito uvnitř
kontejneru `userFilter`:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad kontejneru uživatelského filtru](/documentation/user/en/query/filtering/examples/behavioral/user-filter.evitaql)

</SourceCodeTabs>

A porovnejme to se situací, kdy kontejner `userFilter` odstraníme:

| Souhrn faset s `facetHaving` v `userFilter`       | Souhrn faset bez rozsahu `userFilter`          |
|---------------------------------------------------|------------------------------------------------|
| ![Před](../../../en/query/filtering/assets/user-filter-before.png "Před")     | ![Po](../../../en/query/filtering/assets/user-filter-after.png "Po")       |

Jak můžete vidět na druhém obrázku, souhrn faset je výrazně zredukován na jedinou možnost fasety, kterou vybral
uživatel. Protože je faseta v tomto případě považována za "povinné" omezení, chová se stejně jako
omezení [`referenceHaving`](references.md#reference-having), které je kombinováno s ostatními omezeními pomocí logické
disjunkce. Jelikož neexistuje žádná jiná entita, která by odkazovala jak na značku *amazon*, tak na jinou značku (produkt samozřejmě může mít pouze jednu značku), ostatní možné možnosti jsou automaticky odstraněny ze souhrnu faset,
protože by vedly k prázdné výsledné množině.

### Jak userFilter ovlivňuje predikce

Když dotaz požaduje v `require()` souhrn referencí, histogram nebo cenový histogram, server musí pro každou predikci odpovědět na jinou otázku zaměřenou na zákazníka — a každá otázka vyžaduje jiné výchozí podmínky. Omezení, která uživatel vloží do `userFilter`, tvoří tři disjunktní **rodiny nosičů**:

| Rodina nosičů                | Omezení                                      | Ovlivňuje predikci                                        |
|------------------------------|----------------------------------------------|-----------------------------------------------------------|
| **Nosiče faset**             | `facetHaving`                                | Počet a dopad faset v `referenceSummary`                  |
| **Nosiče hodnotových rozsahů** | `attributeBetween`, `histogramHaving`        | Histogramy atributů a parametrů (referencí)               |
| **Nosiče cenových rozsahů**  | `priceBetween`                               | Cenový histogram                                          |

Kompletní matice toho, co každá predikce vidí z `userFilter`:

| Výpočet predikce pro…                               | Nosiče faset                                 | Nosiče hodnotových rozsahů | Nosiče cenových rozsahů |
|-----------------------------------------------------|:---------------------------------------------:|:--------------------------:|:------------------------:|
| **Počet faset** (pro možnost, na úrovni univerza)   | vynecháno — celý `userFilter` je ignorován    | vynecháno                  | vynecháno                |
| **Dopad faset** (pro možnost, delta)                | zachováno; výběr simulován dle pravidel skupin| zachováno                  | zachováno                |
| **Histogram atributů / referencí (per parametr)**   | zachováno                                    | **vynecháno**              | zachováno                |
| **Cenový histogram**                                | zachováno                                    | zachováno                  | **vynecháno**            |

Tato asymetrie je záměrná. **Počet faset** je stabilní horní hranice — „jak velká je tato možnost v této kategorii“ — proto je celý `userFilter` vynechán. **Dopad faset** je odpovědí na „co bych viděl, kdybych tuto možnost právě teď vybral“ — proto je celý `userFilter` zachován a výběr je simulován podle pravidel skupiny (výchozí přidání pomocí OR, `facetGroupsExclusivity` nahrazuje, `facetGroupsConjunction` slučuje pomocí AND atd.). Stejná pravidla skupin ponechávají POČET beze změny, s jednou výjimkou: `facetGroupsNegation` převrací POČET na univerzum po vyloučení, protože pro přepínač „skrýt toto“ je to relevantní číslo. **Výchozí hodnoty histogramů** odpovídají na otázku „kde mají být úchyty tohoto posuvníku“ — respektují ostatní záměry uživatele, ale nikdy ne svou vlastní rodinu, takže se posuvníky nesbíhají a sourozenci ve stejné rodině si zachovávají rozsah v rámci celého katalogu.

Omezení mimo `userFilter` (kategorie, lokalizace, měna, rozsah, ceník) se nikdy neodstraňují. Definují univerzum; povolovací plochou je pouze `userFilter` a nic jiného.

Celý UX příběh za těmito pravidly — včetně bohaté algebry skupin faset
(`facetGroupsConjunction`, `…Disjunction`, `…Negation`, `…Exclusivity`) a důvodu, proč existuje samostatné `histogramHaving` místo opětovného použití `attributeBetween` — najdete v blogovém příspěvku
[*Skrytá choreografie panelu fasetového filtru*](/documentation/blog/en/25-faceted-filter-choreography.md).