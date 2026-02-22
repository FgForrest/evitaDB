---
title: Dotazovací jazyk
perex: Dotazovací jazyk je jádrem každého databázového systému. evitaDB zvolila funkcionální podobu jazyka namísto SQL-podobného jazyka, což je více v souladu s jejím vnitřním fungováním a především to umožňuje mnohem větší otevřenost vůči transformacím.
date: '15.12.2022'
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
commit: cef96d8320d36c91c100c5dfc9c45020b5a7ad0d
translated: 'true'
---
Jazyk dotazů evitaDB se skládá z vnořené sady funkcí, které představují jednotlivá „omezení“.
Každé omezení (funkce) má svůj název a sadu argumentů uzavřených v závorkách `constraintName(arguments)`,
argumentem může být prostá hodnota podporovaného [datového typu](../use/data-types.md) nebo další omezení.
Argumenty a omezení jsou odděleny čárkou
`argument1, argument2`. Řetězce jsou uzavřeny v `'toto je řetězec'` nebo `"toto je řetězec"`.

Jazyk dotazů evitaDB se skládá z vnořených JSON objektů a primitiv, které představují jednotlivá „omezení“.
Každé omezení (představované buď vnořeným objektem nebo jednoduchou primitivní hodnotou) má svůj název určený klíčem vlastnosti
a sadu argumentů definovanou jako hodnotu vlastnosti. Argumentem může být prostá hodnota podporovaného [datového typu](../use/data-types.md)
nebo další omezení. Argumenty a omezení lze zapsat několika různými styly, v závislosti na konkrétním omezení
a jeho podporovaných argumentech:

- jako primitivní hodnota, pokud omezení přijímá jediný primitivní argument
	- `constraintName: "řetězcový argument"`
	- `constraintName: [ hodnota1, hodnota2 ]`
- jako vnořený objekt obsahující více argumentů (např. více primitivních argumentů)
	- `constraintName: { argument1: 100, argument2: [ 45 ] }`
- jako vnořený objekt obsahující podřízená omezení
	- `constraintName: { childConstraintName: "řetězcový argument" }`

Mohou existovat i různé kombinace těchto možností.

Tento jazyk je určen pro použití lidskými operátory, na úrovni kódu je dotaz reprezentován
stromem objektů dotazu, který lze sestavit přímo bez jakékoli mezilehlé textové formy (na rozdíl od jazyka SQL, který je čistě textový).

Dotaz má tyto čtyři _logické_ části:

- **[header](#hlavička):** definuje kolekci entit, na kterou se dotazuje (je povinná, pokud filtr neobsahuje
  omezení zaměřená na globálně unikátní atributy) a umožňuje označit dotaz vlastními štítky
- **[filter](#filtrování):** definuje omezení, která omezují vrácené entity (volitelné, pokud chybí, vrací se všechny entity v kolekci)
- **[order](#řazení):** definuje pořadí, ve kterém jsou entity vraceny (volitelné, pokud chybí, entity jsou řazeny podle
  primárního celočíselného klíče vzestupně)
- **[require](#požadavky):** obsahuje další informace pro engine dotazu – například nastavení stránkování,
  požadavky na úplnost vracených entit a požadavky na výpočet doprovodných datových struktur
  (volitelné, pokud chybí, vrací se pouze primární klíče entit).

## Gramatika

Gramatika dotazu je následující:

Gramatika celého dotazu je následující:

[Ukázka gramatiky dotazu](/documentation/user/en/query/examples/grammar.evitaql)

Nebo složitější příklad:

[Ukázka gramatiky složitějšího dotazu](/documentation/user/en/query/examples/complexGrammar.evitaql)

Kde část _header_ (dotazovaná kolekce) je součástí samotného názvu GraphQL dotazu a části _filter_, _order_ a _require_
jsou definovány pomocí argumentů GraphQL dotazu.
Navíc má GraphQL jedinečnou reprezentaci části _require_. I když můžete definovat _require_
omezení jako argument GraphQL, jedná se pouze o obecná omezení, která definují pravidla pro výpočty.
Hlavní část require, která definuje úplnost vracených entit (a další výsledky), je definována pomocí výstupních polí
GraphQL dotazu. Tímto způsobem, na rozdíl od ostatních API, konkrétně definujete výstupní formu výsledku dotazu
z toho, co vám doménové schéma evitaDB umožňuje získat.

Další jednodušší varianty gramatiky GraphQL dotazu jsou podrobněji popsány [zde](../use/api/query-data.md#definice-dotazů-v-graphql-api),
ale základní logika je vždy stejná.

Kde část _header_ (dotazovaná kolekce) je součástí cesty URL a části _filter_, _order_ a _require_ jsou definovány
jako vlastnosti vstupního JSON objektu.

Další jednodušší varianty gramatiky REST dotazu jsou podrobněji popsány [zde](../use/api/query-data.md#definice-dotazů-v-rest-api),
ale základní logika je vždy stejná.

Každá část dotazu je volitelná. Pouze část `collection` je obvykle povinná, ale existuje výjimka z tohoto pravidla.
Pokud část `filterBy` obsahuje omezení, které cílí na globálně unikátní atribut, lze část `collection` také vynechat,
protože evitaDB může implicitně určit kolekci tohoto globálně unikátního atributu automaticky.
V dotazu však může být nejvýše jedna část každé z `collection`, `filterBy`, `orderBy` a `require`.
Pořadí částí lze libovolně měnit (na pořadí nezáleží). Například následující dotaz je stále platný a představuje
nejjednodušší možný dotaz:

Téměř každá část dotazu je volitelná. Pouze `collection` je obvykle povinná, ale existuje výjimka z tohoto pravidla.
Vždy musíte použít konkrétní <LS to="g">GraphQL dotaz</LS><LS to="r">REST endpoint</LS>,
kde je název kolekce již definován, avšak můžete použít
obecný <LS to="g">GraphQL dotaz</LS><LS to="r">REST endpoint</LS>
(i když je velmi omezený kvůli povaze generovaného schématu)
a použít omezení, které cílí na globálně unikátní atribut. V tomto případě lze část `collection`
vynechat, protože evitaDB může implicitně určit kolekci tohoto globálně unikátního atributu automaticky.
<LS to="g">Ostatní části definované pomocí argumentů jsou volitelné, ale kvůli povaze GraphQL musíte definovat alespoň jedno výstupní pole.</LS>
<LS to="r">Ostatní části definované jako vlastnosti vstupního JSON objektu jsou volitelné.</LS>
V dotazu však může být nejvýše jedna část každé z _header_, _filter_, _order_ a _require_.

Další specifikum v gramatice <LS to="g">GraphQL</LS><LS to="r">REST</LS>
dotazu je, že názvy omezení obvykle obsahují klasifikátory cílových dat (např. název atributu).
To je důležitý rozdíl oproti ostatním API, a je to proto, že tímto způsobem může
<LS to="g">GraphQL</LS><LS to="r">REST</LS>
schéma pro hodnotu vlastnosti omezení být specifické pro konkrétní omezení a cílová data a IDE může nabídnout
správné doplňování a validaci argumentů omezení.

Například následující dotaz je stále platný a představuje nejjednodušší možný dotaz:

[Ukázka nejjednoduššího dotazu](/documentation/user/en/query/examples/simplestQuery.evitaql)

... nebo i tento (i když je doporučeno zachovat pořadí pro lepší čitelnost:
`head`, `filterBy`, `orderBy`, `require`):

[Ukázka náhodného pořadí částí dotazu](/documentation/user/en/query/examples/randomOrderQuery.evitaql)

<Note type="info">

<NoteTitle toggles="true">

##### Chcete si přečíst více o tom, jak byla gramatika navržena?
</NoteTitle>

Více o specifikách gramatiky <LS to="g">GraphQL</LS><LS to="r">REST</LS>
dotazu si můžete přečíst [zde](/documentation/blog/en/02-designing-evita-query-language-for-graphql-api.md).

</Note>

### Formát syntaxe

V dokumentaci jsou omezení popsána v sekci **Syntaxe**, která má tento formát:

```evitaql-syntax
constraintName(
    argument:type,specification
    constraint:type,specification
)
```

<dl>
  <dt>argument:type,specification</dt>
  <dd>
    argument představuje argument určitého typu, například: `argument:string` představuje řetězcový argument na
    určité pozici.
  </dd>
  <dt>constraint:type,specification</dt>
  <dd>
    constraint představuje argument typu omezení – supertyp (`filter`/`order`/`require`) omezení je vždy uveden před dvojtečkou,
    například: `filterConstraint:any`;

    za dvojtečkou je uveden přesný typ povoleného omezení, nebo klíčové slovo `any`, pokud lze použít libovolné
    samostatné omezení
  </dd>
</dl>

<Note type="warning">

Tento formát syntaxe je aktuálně specifický pro základní jazyk evitaQL a neodráží rozdíly v
<LS to="g">GraphQL</LS><LS to="r">REST</LS> API.
Stále však můžete z této syntaxe těžit, protože názvy a přijímané argumenty jsou stejné (pouze v mírně odlišném formátu).
<LS to="g">Specifická dokumentace pro konkrétní GraphQL omezení/dotazy však existuje.</LS>

</Note>

#### Variadické argumenty

Pokud argument může být více hodnot stejného typu (pole), je specifikace rozšířena o speciální znak:

<dl>
  <dt>`*` (hvězdička)</dt>
  <dd>označuje, že argument může být uveden nula, jednou nebo vícekrát (volitelný vícenásobný argument).</dd>
  <dt>`+` (plus)</dt>
  <dd>označuje, že argument musí být uveden jednou nebo vícekrát (povinný vícenásobný argument).</dd>
</dl>

<Note type="info">

<NoteTitle toggles="false">

##### Příklad variadických argumentů
</NoteTitle>

<dl>
  <dt>`argument:string+`</dt>
  <dd>
    argument na této pozici přijímá pole <LS to="e,j,r,g">[řetězců](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)</LS><LS to="c">[řetězců](https://learn.microsoft.com/en-us/dotnet/api/system.string)</LS>,
    které musí obsahovat alespoň jednu položku
  </dd>
  <dt>`argument:int*`</dt>
  <dd>
    argument na této pozici přijímá pole <LS to="e,j,r,g">[intů](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)</LS><LS to="c">[intů](https://learn.microsoft.com/en-us/dotnet/api/system.int32)</LS>
    a může obsahovat nula nebo více položek
  </dd>
  <dt>`filterConstraint:any*`</dt>
  <dd>
    argument na této pozici přijímá pole libovolných samostatných filtračních omezení s nulovým nebo více výskyty
  </dd>
</dl>

</Note>

#### Povinné argumenty

Povinný argument je označen znakem `!` (vykřičník) nebo v případě variadických argumentů znakem `+` (plus).

<Note type="info">

<NoteTitle toggles="false">

##### Příklad povinných argumentů
</NoteTitle>

<dl>
  <dt>`argument:string`</dt>
  <dd>
    argument na této pozici přijímá hodnotu <LS to="e,j,r,g">[String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)</LS><LS to="c">[string](https://learn.microsoft.com/en-us/dotnet/api/system.string)</LS>,
    která může být null
  </dd>
  <dt>`argument:int!`</dt>
  <dd>
    argument na této pozici přijímá hodnotu <LS to="e,j,r,g">[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)</LS><LS to="c">[int](https://learn.microsoft.com/en-us/dotnet/api/system.int32)</LS>,
    která je povinná a musí být zadána
  </dd>
</dl>

</Note>

#### Kombinované argumenty

Seznam specifikací může obsahovat kombinovaný výraz pomocí `|` pro kombinaci více specifikací v logickém
součtu (booleovské NEBO) a závorek `()` pro agregaci.

<Note type="info">

<NoteTitle toggles="false">

##### Příklad kombinovaných argumentů
</NoteTitle>

<dl>
  <dt>`filterConstraint:(having|excluding)`</dt>
  <dd>
    buď `having` nebo `excluding`, nebo žádné, ale ne obě, a žádné jiné filtrační omezení není povoleno
  </dd>
  <dt>`filterConstraint:(having|excluding)!`</dt>
  <dd>
    buď `with` nebo `exclude` filtrační omezení, ale ne obě a ne žádné, ale žádné jiné filtrační omezení není povoleno
  </dd>
  <dt>`filterConstraint:(having|excluding)*`</dt>
  <dd>
    buď `having` nebo `excluding` filtrační omezení, nebo obě, nebo žádné, ale žádné jiné filtrační omezení není povoleno.
  </dd>
  <dt>`filterConstraint:(having|excluding)+`</dt>
  <dd>
    buď `having` nebo `excluding` filtrační omezení, nebo obě, ale alespoň jedno z nich a žádné filtrační omezení
    jiného typu není povoleno
  </dd>
</dl>

</Note>

### Pravidla pojmenovávání omezení

Aby byla omezení srozumitelnější, vytvořili jsme sadu interních pravidel pro pojmenovávání omezení:

1. název entity by měl být v takovém tvaru (čase), který odpovídá anglické dotazovací frázi: *dotaz na kolekci ..., a filtrovat entity podle ..., a řadit výsledek podle ..., a vyžadovat ...*
    - dotaz by měl být srozumitelný i někomu, kdo není obeznámen se syntaxí a vnitřními mechanismy evitaDB.
2. Název omezení začíná částí entity, na kterou cílí – tj. `entity`, `attribute`, `reference` – následovanou <LS to="g,r">obvykle klasifikátorem cílových dat, po kterém následuje</LS> slovem vystihujícím podstatu omezení.
3. Pokud má omezení smysl pouze v kontextu nějakého nadřazeného omezení, nesmí být použitelné jinde a může uvolnit pravidlo č. 2 (protože kontext bude zřejmý z nadřazeného omezení).

## Obecná pravidla dotazů

### Převod datových typů

Pokud hodnota, která má být porovnávána v argumentu omezení, neodpovídá datovému typu atributu, evitaDB se pokusí
automaticky ji převést na správný typ před porovnáním. Proto můžete také zadat *řetězcové* hodnoty
pro porovnání s číselnými typy. Samozřejmě je lepší poskytovat evitaDB správné typy a vyhnout se
automatickému převodu.

### Pole cílená omezením

Pokud omezení cílí na atribut, který je typu pole, omezení automaticky odpovídá entitě, pokud
**některá** z položek pole atributu splňuje omezení.

Například mějme pole atributu <LS to="e,j,r,g">[String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)</LS><LS to="c">[string](https://learn.microsoft.com/en-us/dotnet/api/system.string)</LS>
s názvem `oneDayDeliveryCountries` s následujícími hodnotami: `GB`, `FR`, `CZ`. Filtrační omezení
[`attributeEquals`](filtering/comparable.md#atribut-rovná-se) zapsané takto: <LS to="e,j,c">`attributeEquals("oneDayDeliveryCountries", "GB")`</LS>
<LS to="g">`attributeOneDayDeliveryCountriesEquals: "GB"`</LS>
<LS to="r">`"attributeOneDayDeliveryCountriesEquals": "GB"`</LS>
bude odpovídat entitě, protože *GB* je jednou z hodnot pole.

Podívejme se na složitější, ale užitečnější příklad. Mějme pole atributu [`DateTimeRange`](../use/data-types.md#datetimerange)
s názvem `validity`, které obsahuje několik časových období, kdy lze entitu použít:

```plain
[2023-01-01T00:00:00+01:00,2023-02-01T00:00:00+01:00]
[2023-06-01T00:00:00+01:00,2022-07-01T00:00:00+01:00]
[2023-12-01T00:00:00+01:00,2024-01-01T00:00:00+01:00]
```

Stručně řečeno, entita je platná pouze v lednu, červnu a prosinci 2023. Pokud chceme zjistit, zda je možné ji použít
(např. koupit produkt) v květnu pomocí omezení <LS to="e,j,c">`attributeInRange("validity", "2023-05-05T00:00:00+01:00")`</LS>
<LS to="g">`attributeValidityInRange: "2023-05-05T00:00:00+01:00"`</LS>
<LS to="r">`"attributeValidityInRange": "2023-05-05T00:00:00+01:00"`</LS>, bude výsledek prázdný, protože žádné z časových rozmezí v poli `validity` tomuto datu a času neodpovídá. Samozřejmě, pokud se zeptáme na entitu,
která je platná v červnu pomocí
<LS to="e,j,c">`attributeInRange("validity", "2023-06-05T00:00:00+01:00")`</LS>
<LS to="g">`attributeValidityInRange: "2023-06-05T00:00:00+01:00"`</LS>
<LS to="r">`"attributeValidityInRange": "2023-06-05T00:00:00+01:00"`</LS>, entita bude vrácena,
protože v poli je jedno časové rozmezí, které toto omezení splňuje.

## Hlavička

Část hlavičky umožňuje zadat omezení `collection`, které definuje kolekci entit, na kterou se tento dotaz zaměřuje.

Definice cílové kolekce entit je určena jako součást <LS to="g">názvu GraphQL dotazu</LS><LS to="r">URL endpointu</LS>.
Lze ji vynechat <LS to="g,r">při použití obecného <LS to="g">GraphQL dotazu</LS><LS to="r">endpointu</LS></LS>
pokud [filterBy](#filtrování) obsahuje omezení, které cílí na globálně unikátní atribut.
To je užitečné pro jeden z nejdůležitějších e-commerce scénářů, kdy požadované URI musí odpovídat jedné z existujících entit
(viz kapitola [routing](../solve/routing.md) pro podrobný návod).

Volitelně můžete zadat jeden nebo více [štítků](header/label.md) spojených s tímto dotazem, které budou
připojeny ke [stopám](../operate/observe.md#tracing) generovaným pro tento dotaz. Štítky jsou také zaznamenány s
dotazem v provozních stopách, které lze použít pro další analýzu nebo přehrání.

- [collection](header/collection.md)
- [label](header/label.md)

## Filtrování

Filtrační omezení vám umožňují vybrat pouze několik entit z mnoha, které existují v cílové kolekci. Je to
podobné jako klauzule „where“ v SQL. V současné době jsou k dispozici tato filtrační omezení.

### Logická omezení

Logická omezení slouží k provádění logických operací na výsledcích podřízených funkcí:

- [and](filtering/logical.md#and)
- [or](filtering/logical.md#or)
- [not](filtering/logical.md#not)

### Behaviorální omezení

Behaviorální omezení ovlivňují samotné chování procesu filtrování, nikoli filtrování entit na základě
jejich dat. Tato omezení obalují jiná filtrační omezení, aby upravila jejich chování:

- [in scope](filtering/behavioral.md#v-rozsahu)
- [user filter](filtering/behavioral.md#uživatelský-filtr)

### Konstantní omezení

Konstantní omezení přímo určují primární klíče entit, které se očekávají ve výstupu, nebo definují rozsah,
kde by mělo být filtrování aplikováno:

- [entity primary key in set](filtering/constant.md#primární-klíč-entity-v-množině)
- [scope](filtering/constant.md#scope)

### Lokalizační omezení

Lokalizační omezení vám umožňují zúžit [lokalizované atributy](../use/data-model.md#lokalizované-atributy)
na jednu <LS to="e,j,r,g">[localizaci](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Locale.html)</LS><LS to="c">[localizaci](https://learn.microsoft.com/en-us/dotnet/api/system.globalization.cultureinfo)</LS>, která se používá
k výběru správných hodnot pro porovnání v dalších filtračních omezeních, která cílí na tyto atributy:

- [entity locale equals](filtering/locale.md#entity-locale-equals)

### Porovnávací omezení

Porovnávací omezení porovnávají konstanty předané jako argumenty s konkrétním atributem entity a poté filtrují
výsledný výstup tak, aby obsahoval pouze hodnoty, které omezení splňují.

- [attribute equals](filtering/comparable.md#atribut-rovná-se)
- [attribute greater than](filtering/comparable.md#atribut-větší-než)
- [attribute greater than, equals](filtering/comparable.md#atribut-větší-nebo-roven)
- [attribute less than](filtering/comparable.md#atribut-menší-než)
- [attribute less than, equals](filtering/comparable.md#atribut-menší-nebo-roven)
- [attribute between](filtering/comparable.md#atribut-mezi)
- [attribute in set](filtering/comparable.md#atribut-v-množině)
- [attribute is](filtering/comparable.md#atribut-existuje)

### Řetězcová omezení

Řetězcová omezení jsou podobná [Porovnávacím](#porovnávací-omezení), ale fungují pouze na
<LS to="e,j,r,g">[String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)</LS><LS to="c">[string](https://learn.microsoft.com/en-us/dotnet/api/system.string)</LS> datovém typu atributu a
umožňují operace specifické pro tento typ:

- [attribute contains](filtering/string.md#atribut-obsahuje)
- [attribute starts with](filtering/string.md#atribut-začíná-na)
- [attribute ends with](filtering/string.md#atribut-končí-na)

### Rozsahová omezení

Rozsahová omezení jsou podobná [Porovnávacím](#porovnávací-omezení), ale fungují pouze na
<LS to="e,j,r,g"><SourceClass>evita_common/src/main/java/io/evitadb/dataType/Range.java</SourceClass></LS><LS to="e,j,r,g"><SourceClass>EvitaDB.Client/DataTypes/Range.cs</SourceClass></LS> datovém typu atributu a
umožňují operace specifické pro tento typ:

- [attribute in range](filtering/range.md#atribut-v-rozsahu)
- [attribute in range now](filtering/range.md#atribut-v-rozsahu-nyní)

### Cenová omezení

Cenová omezení umožňují filtrovat entity podle shody ceny, kterou mají:

- [price in currency](filtering/price.md#cena-v-měně)
- [price in price lists](filtering/price.md#cena-v-cenících)
- [price valid in](filtering/price.md#cena-platná-v)
- [price between](filtering/price.md#cena-v-rozmezí)

### Referenční omezení

Referenční omezení umožňují filtrovat entity podle existence referenčních atributů zadaných na jejich
referencích/vztazích k jiným entitám, nebo podle filtračního omezení na referencované entitě samotné:

- [reference having](filtering/references.md#reference-having)
- [entity having](filtering/references.md#entity-having)
- [facet having](filtering/references.md#facet-having)

### Hierarchická omezení

Hierarchická omezení využívají reference na hierarchickou sadu entit (tvořící strom) a umožňují
filtrovat entity podle toho, že odkazují na určitou část stromu:

- [hierarchy within](filtering/hierarchy.md#hierarchy-within)
- [hierarchy within root](filtering/hierarchy.md#hierarchy-within-root)
- [excluding root](filtering/hierarchy.md#excluding-root)
- [excluding](filtering/hierarchy.md#excluding)
- [having](filtering/hierarchy.md#having)
- [any having](filtering/hierarchy.md#any-having)
- [direct relation](filtering/hierarchy.md#direct-relation)

## Řazení

Omezení řazení vám umožňují definovat pravidlo, které určuje pořadí entit ve výsledku. Je to podobné jako
klauzule „order by“ v SQL. V současné době jsou k dispozici tato omezení řazení:

- [entity primary key in filter](ordering/constant.md#přesné-pořadí-primárních-klíčů-entit-použité-ve-filtru)
- [entity primary key exact](ordering/constant.md#přesné-pořadí-primárních-klíčů-entit)
- [entity primary key natural](ordering/comparable.md#primární-klíč-natural)
- [attribute set in filter](ordering/constant.md#přesné-pořadí-hodnot-atributu-entity-použité-ve-filtru)
- [attribute set exact](ordering/constant.md#přesné-pořadí-hodnot-atributu-entity)
- [attribute natural](ordering/comparable.md#atribut-natural)
- [price natural](ordering/price.md#přirozená-cena)
- [price discount](ordering/price.md#sleva-z-ceny)
- [reference property](ordering/reference.md#vlastnost-reference)
- [entity property](ordering/reference.md#vlastnost-entity)
- [entity group property](ordering/reference.md#vlastnost-skupiny-entity)
- [random](ordering/random.md#náhodné)
- [in scope](ordering/behavioral.md#v-rozsahu)

## Požadavky

Požadavky nemají přímou obdobu v jiných databázových jazycích. Definují vedlejší výpočty, stránkování, množství
dat načítaných pro každou vrácenou entitu atd., ale nikdy neovlivňují počet ani pořadí vracených entit.
V současné době jsou vám k dispozici tyto požadavky:

### Behaviorální omezení

Behaviorální omezení ovlivňují samotné chování procesu požadavků. Tato omezení obalují jiná požadavková
omezení, aby upravila jejich chování:

- [in scope](requirements/behavioral.md#v-rozsahu)

### Stránkování

Požadavky na stránkování určují, jak velká a která podmnožina velké filtrované množiny entit bude skutečně vrácena
ve výstupu.

- [page](requirements/paging.md#page)
- [strip](requirements/paging.md#strip)
- [gap](requirements/paging.md#gap)
- [spacing](requirements/paging.md#spacing)

- [`list` dotazy](requirements/paging.md#stránkování-dotazů-typu-list)
- [`query` dotazy – `recordPage`](requirements/paging.md#page-recordpage)
- [`query` dotazy – `recordStrip`](requirements/paging.md#page-recordpage)
- [gap](requirements/paging.md#gap)
- [spacing](requirements/paging.md#spacing)

### Načítání (úplnost)

Požadavky na načítání určují úplnost vracených entit. <LS to="e,j,r,c">Ve výchozím nastavení je ve výsledku dotazu vrácen pouze
<LS to="e,j,r"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/structure/EntityReference.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/Data/Structure/EntityReference.cs</SourceClass></LS>.
Aby bylo vráceno tělo entity, musí být součástí dotazu některý z následujících požadavků:</LS>

- [entity fetch](requirements/fetching.md#načtení-entity)
- [entity group fetch](requirements/fetching.md#načtení-skupiny-entit)
- [attribute content](requirements/fetching.md#obsah-atributů)
- [associated data content](requirements/fetching.md#obsah-souvisejících-dat)
- [price content](requirements/fetching.md#obsah-cen)
- [accompanying price content](requirements/fetching.md#doprovodný-obsah-ceny)
- [reference content](requirements/fetching.md#referenční-obsah)
- [hierarchy content](requirements/fetching.md#hierarchický-obsah)
- [data in locale](requirements/fetching.md#data-v-lokalizacích)

### Hierarchie

Požadavky na hierarchii spouštějí výpočet další datové struktury, kterou lze použít k vykreslení menu,
které organizuje entity do srozumitelnější stromové kategorizace:

- [hierarchy of self](requirements/hierarchy.md#hierarchie-sebe-sama)
- [hierarchy of reference](requirements/hierarchy.md#hierarchie-reference)
- [from root](requirements/hierarchy.md#from-root)
- [from node](requirements/hierarchy.md#from-node)
- [children](requirements/hierarchy.md#children)
- [siblings](requirements/hierarchy.md#siblings)
- [parents](requirements/hierarchy.md#parents)
- [stop at](requirements/hierarchy.md#stop-at)
- [distance](requirements/hierarchy.md#distance)
- [level](requirements/hierarchy.md#level)
- [node](requirements/hierarchy.md#node)
- [statistics](requirements/hierarchy.md#statistics)

### Facety

Požadavky na facety spouštějí výpočet další datové struktury, která vypisuje všechny facettované reference entit,
uspořádané do skupin s vypočteným počtem všech entit, které odpovídají každému příslušnému facetu. Alternativně
může souhrn obsahovat výpočet, kolik entit zůstane, když bude do filtru přidán konkrétní facet:

- [facet summary](requirements/facet.md#fasetový-souhrn)
- [facet summary of reference](requirements/facet.md#fasetový-souhrn-reference)
- [facet conjunction](requirements/facet.md#konjunkce-skupin-faset)
- [facet disjunction](requirements/facet.md#disjunkce-skupin-faset)
- [facet negation](requirements/facet.md#negace-skupin-faset)

### Histogram

Požadavky na histogram spouštějí výpočet další datové struktury, která obsahuje histogram entit
agregovaných podle jejich číselné hodnoty v konkrétním atributu nebo podle jejich prodejní ceny:

- [attribute histogram](requirements/histogram.md#histogram-atributu)
- [price histogram](requirements/histogram.md#cenový-histogram)

### Cena

Požadavek na cenu určuje, která forma prodejní ceny je brána v úvahu při filtrování, řazení
nebo výpočtu histogramů entit:

- [price type](requirements/price.md#typ-ceny)
- [default accompanying price lists](requirements/price.md#výchozí-doprovodné-ceníky)

### Telemetrie

Požadavky na telemetrii spouštějí výpočet dalších telemetrických dat pro nahlédnutí pod pokličku
databázového enginu:

- [query telemetry](requirements/telemetry.md#telemetrie-dotazu)