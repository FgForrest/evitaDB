---
commit: c382cf89a086e9b35e810e2c3651673c0f862dc3
translated: 'true'
---
# Výrazový jazyk (EvitaEL)

Výrazový jazyk evitaDB (EvitaEL) je lehký jazyk bez vedlejších efektů pro zápis
vložených výrazů, které se vyhodnocují na jednu hodnotu. Používá se v dotazovacích omezeních a ve vzorcích
pro výpočet atributů tam, kde je potřeba dynamické vyhodnocení. Výrazy mohou provádět aritmetické operace, porovnávat
hodnoty, volat matematické funkce, procházet složité objektové struktury a elegantně pracovat s hodnotami null.

## Datové typy

EvitaEL podporuje následující literální datové typy:

| Typ | Popis | Příklady |
|---|---|---|
| `long` | 64bitové celé číslo se znaménkem | `123`, `-42`, `0` |
| `decimal` | Desetinné číslo s libovolnou přesností (BigDecimal) | `3.14`, `-0.5`, `100.0` |
| `boolean` | Pravdivostní hodnota | `true`, `false` |
| `string` | Text v jednoduchých nebo dvojitých uvozovkách | `'hello'`, `"world"` |

ale pod povrchem zvládá všechny datové typy podporované evitaDB.

Kolekce (seznamy, pole, mapy) nelze definovat jako literály, ale běžně se s nimi setkáte jako
hodnotami vrácenými z proměnných a výrazů pro přístup k objektům.

## Proměnné

Proměnné se odkazují pomocí prefixu `$` následovaného identifikátorem:

```
$pageNumber
$entity
$price
```

Identifikátory proměnných musí začínat malým písmenem a mohou obsahovat písmena a číslice
(`[a-z][a-zA-Z0-9]*`). Proměnné poskytuje vyhodnocovací kontext a nelze je definovat
přímo ve výrazu.

Samotný symbol `$` (bez identifikátoru) odkazuje na `this` v aktuálním kontextu. Obvykle se používá pro odkaz
na položku kolekce uvnitř [rozšiřovacího výrazu](#rozšiřovací-operátor-expr).

## Aritmetické operátory

| Operátor | Popis | Příklad | Výsledek |
|---|---|---|---|
| `+` | Sčítání | `1 + 3 + 5` | `9` |
| `-` | Odčítání | `2 - 5` | `-3` |
| `*` | Násobení | `2 * (8 - 4)` | `8` |
| `/` | Dělení | `$pageNumber / 2` | polovina pageNumber |
| `%` | Modulo (zbytek) | `$pageNumber % 2` | `0` nebo `1` |
| `+` (unární) | Kladné znaménko | `+1` | `1` |
| `-` (unární) | Negace | `-(1 + 2)` | `-3` |
| `()` | Seskupování | `(2 + 4) * 2` | `12` |

Platí standardní matematická priorita: násobení, dělení a modulo se vyhodnocují před
sčítáním a odčítáním. Prioritu lze změnit pomocí závorek.

## Porovnávací operátory

| Operátor | Popis | Příklad | Výsledek |
|---|---|---|---|
| `==` | Rovnost | `5 == 5` | `true` |
| `!=` | Nerovnost | `5 != 4` | `true` |
| `>` | Větší než | `10 > 5` | `true` |
| `>=` | Větší nebo rovno | `$pageNumber >= 5` | závisí na proměnné |
| `<` | Menší než | `5 < 10` | `true` |
| `<=` | Menší nebo rovno | `$pageNumber <= 5` | závisí na proměnné |

Porovnávací operátory fungují s číselnými hodnotami i řetězci. Porovnání řetězců používá
syntaxi `'abc' == 'abc'`.

## Logické operátory

| Operátor | Popis | Příklad | Výsledek |
|---|---|---|---|
| `&&` | Logické AND | `true && false` | `false` |
| `\|\|` | Logické OR | `true \|\| false` | `true` |
| `!` | Logické NOT | `!true` | `false` |
| `^` | Logické XOR | `true ^ false` | `true` |

Logické operátory lze kombinovat s porovnávacími operátory pro tvorbu složitých predikátů:

```
$pageNumber > 2 && $pageNumber < 10 && $pageNumber % 2 == 0
```

## Matematické funkce

K dispozici jsou následující vestavěné matematické funkce:

| Funkce | Popis | Příklad | Výsledek |
|---|---|---|---|
| `abs(x)` | Absolutní hodnota | `abs(-4)` | `4` |
| `ceil(x)` | Zaokrouhlení nahoru na nejbližší celé číslo | `ceil($n / 2)` | zaokrouhlení nahoru |
| `floor(x)` | Zaokrouhlení dolů na nejbližší celé číslo | `floor($n / 2)` | zaokrouhlení dolů |
| `round(x)` | Zaokrouhlení na nejbližší celé číslo (půl nahoru) | `round(2.5)` | `3` |
| `sqrt(x)` | Druhá odmocnina | `sqrt(3 + 13)` | `4` |
| `log(x)` | Přirozený logaritmus | `round(log(20))` | `3` |
| `pow(x, y)` | Umocnění x na y | `pow(2, 6)` | `64` |
| `max(x, y)` | Větší ze dvou hodnot | `max(4, 8)` | `8` |
| `min(x, y)` | Menší ze dvou hodnot | `min(4, 8)` | `4` |
| `random()` | Náhodná long hodnota | `random()` | náhodné long číslo |
| `random(x)` | Náhodné long číslo v rozsahu [0, x) | `random(5)` | `0`..`4` |

Funkce lze vnořovat:

```
floor(sqrt($pageNumber))
round(log(20))
```

## Přístup k objektům

EvitaEL umožňuje procházet složité objektové struktury pomocí tečkové notace pro vlastnosti a hranaté
závorky pro prvky.

### Přístup k vlastnostem (tečková notace)

Přístup k pojmenovaným vlastnostem objektů pomocí operátoru tečka (`.`):

```
$entity.attributes
$entity.references
```

### Přístup k prvkům (hranaté závorky)

Přístup k prvkům podle klíče (řetězec) nebo indexu (celé číslo) pomocí hranatých závorek (`[]`):

```
$entity.attributes['code']
$entity.references['tags'][0]
```

Identifikátor prvku uvnitř hranatých závorek musí být vyhodnocen jako řetězec nebo celé číslo.

### Řetězení přístupu

Přístup k vlastnostem a prvkům lze řetězit pro procházení hluboce vnořených struktur:

```
$entity.references['brand'].attributes['distributor']
$entity.references['brand'].referencedPrimaryKey
```

### Specifické přístupy pro entity

Při práci s objekty entit evitaDB jsou k dispozici následující přístupové cesty:

**Vlastnosti entity:**

| Vlastnost | Popis |
|---|---|
| `$entity.attributes` | Přístup ke nelokalizovaným (globálním) atributům |
| `$entity.localizedAttributes` | Přístup k lokalizovaným atributům (vrací mapu locale na hodnotu) |
| `$entity.associatedData` | Přístup k nelokalizovaným asociovaným datům |
| `$entity.localizedAssociatedData` | Přístup k lokalizovaným asociovaným datům |
| `$entity.references` | Přístup k referencím entity podle názvu |

**Přístup k atributům:**

```
$entity.attributes['code']                   // hodnota globálního atributu
$entity.attributes['tags']                   // globální pole atributů
$entity.attributes['tags'][0]                // první prvek globálního pole atributů
$entity.localizedAttributes['url']           // mapa locale -> lokalizovaná hodnota
$entity.localizedAttributes['url']['en']     // lokalizovaná hodnota pro locale 'en'
```

Přístup k lokalizovanému atributu přes `.attributes` (nebo ke globálnímu atributu přes
`.localizedAttributes`) vyvolá chybu. Použijte správný přístup pro každý typ atributu.

**Přístup k referencím:**

```
$entity.references['brand']                              // jedna reference
$entity.references['brand'].referencedPrimaryKey         // PK referencované entity
$entity.references['brand'].attributes['distributor']    // atribut reference
$entity.references['categories']                         // seznam referencí
```

Pokud existuje právě jedna reference daného názvu, výsledkem je jeden referenční objekt.
Pokud je referencí více (např. `categories`), výsledkem je seznam.

**Vlastnosti reference:**

| Vlastnost | Popis |
|---|---|
| `$ref.referencedPrimaryKey` | Primární klíč referencované entity |
| `$ref.attributes` | Přístup k nelokalizovaným atributům reference |
| `$ref.localizedAttributes` | Přístup k lokalizovaným atributům reference |

## Rozšiřovací operátor (`.*[expr]`)

Rozšiřovací operátor aplikuje mapovací výraz na každý prvek kolekce nebo mapy.
Uvnitř mapovacího výrazu odkazuje samotná proměnná `$` na aktuálně zpracovávanou položku.

### Základní rozšiřování

```
$entity.references['categories'].*[$.referencedPrimaryKey]
// získá referencedPrimaryKey z každé reference kategorie -> [1, 2]

$entity.references['categories'].*[$.attributes['categoryPriority']]
// získá categoryPriority z každé reference -> [16, 17]
```

### Rozšiřování s transformacemi

Mapovací výraz může obsahovat libovolný platný výraz EvitaEL:

```
$entity.references['categories'].*[-$.attributes['categoryPriority']]
// neguje každou prioritu -> [-16, -17]

$obj.mapWithNumbers.*[max($, 7)]
// nastaví minimální hodnotu 7
```

### Kompaktní varianta (`.*![expr]`)

Kompaktní varianta odfiltruje hodnoty `null` z výsledku:

```
$entity.references['categories'].*![$.attributes['categoryTag']]
// získá categoryTag, přeskočí null -> ['new']
```

Porovnejte s nekompaktní variantou:

```
$entity.references['categories'].*![$.attributes['categoryTag']]
// zahrnuje null -> ["new", null]
```

### Rozšiřování map

Pokud je rozšiřovací operátor použit na mapu, mapuje přes **hodnoty** a zachovává klíče:

```
$obj.map.*[$]
// identita: vrací stejnou mapu -> {"a": 5, "b": 6, "c": 7, "d": 8}

$obj.map.*[max($, 7)]
// nastaví minimální hodnotu 7 -> {"a": 7, "b": 7, "c": 7, "d": 8}
```

### Položky mapy

Pro přístup ke klíčům i hodnotám mapy použijte vlastnost `.entries`, která mapu převede na
seznam objektů položek, přes které lze rozšiřovat. Každá položka má vlastnosti `.key` a `.value`:

```
$obj.map.entries.*[$.key]
// získá všechny klíče -> ["a", "b", "c", "d"]

$obj.map.entries.*[$.value]
// získá všechny hodnoty -> [5, 6, 7, 8]

$obj.map.entries.*[min($.value, 6)]
// nastaví maximální hodnotu 6 -> [5, 6, 6, 6]
```

## Null-bezpečnost (bezpečná navigace)

Ve výchozím nastavení vyvolá přístup k vlastnosti nebo prvku na hodnotě `null` chybu. Použijte
bezpečnostní operátory pro zkrácení na `null` místo chyby:

| Syntaxe | Popis |
|---|---|
| `?.` | Bezpečný přístup k vlastnosti |
| `?[` | Bezpečný přístup k prvku |
| `?.*` | Bezpečný rozšiřovací přístup |

Příklady:

```
$obj.optionalNested?.map['c'].list[0]   // vrátí null, pokud je optionalNested null
$obj.optionalList?[0]                   // vrátí null, pokud je optionalList null
$obj.optionalMap?['b']                  // vrátí null, pokud je optionalMap null
```

Operátor null-bezpečnosti zkrátí celý zbývající řetězec přístupu. V
`$obj.optionalNested?.map['c'].list[0]`, pokud je `optionalNested` `null`, celý výraz
se vyhodnotí na `null` bez pokusu o přístup k `map`, `'c'`, `list` nebo `[0]`.

**Kdy je null-bezpečnost nutná:** Kdykoli může být operand vlevo od `.`, `[`, nebo `.*`
`null`, musíte použít bezpečnou variantu. Bez ní je vyvolána chyba:

```
// CHYBA: vyvolá ExpressionEvaluationException, pokud je optionalNested null
$obj.optionalNested.map['c']

// BEZPEČNÉ: vrátí null, pokud je optionalNested null
$obj.optionalNested?.map['c']
```

Při práci s kolekcemi, které mohou obsahovat null položky, může být reference `$` (this) uvnitř rozšiřovacího výrazu také null. Použijte `$?` pro bezpečný přístup k jejím vlastnostem:

```
$obj.listWithMissingValues.*[$?.attribute]
```

Použijte `?.*`, pokud samotná kolekce může být null:

```
$obj.optionalList?.*[$ + 1]
// vrátí null, pokud je optionalList null, jinak mapuje každou položku
```

### Operátor null-koalescence (`??`)

Operátor null-koalescence poskytuje výchozí hodnotu, pokud se výraz vyhodnotí na `null`:

```
expression ?? defaultValue
```

Pokud je levá strana nenulová, vrací se její hodnota. Pokud je levá strana `null`, vyhodnotí se a vrátí pravá strana.

Příklady:

```
$entity.attributes['ean'] ?? '1234'
// vrátí hodnotu atributu ean, nebo '1234', pokud je ean null

$entity.references['brand'].attributes['brandTag'] ?? 'new'
// vrátí brandTag, nebo 'new', pokud je null
```

Operátor `??` lze použít i uvnitř rozšiřovacích výrazů:

```
$obj.items.*[$?.name ?? 'unknown']
```

#### Rozšiřovací null-koalescence (`*?` a `?*?`)

Rozšiřovací operátor null-koalescence nahrazuje hodnoty `null` uvnitř kolekce nebo mapy
výchozí hodnotou. To se liší od `??`, který pracuje s jednou hodnotou – `*?` pracuje s každým
prvkem kolekce:

```
kolekce *? defaultValue
```

Iteruje přes všechny prvky a nahrazuje všechny `null` prvky výchozí hodnotou:

```
$entity.references['categories'].*[$.attributes['categoryTag']] *? 'new'
// nahradí null categoryTags hodnotou 'new' -> ["new", "new"]

$obj.objectListWithMissingValues.*[$?.attribute] *? 10
// nahradí null atributy hodnotou 10 -> ["basic attribute", 10]
```

U map jsou koaleskovány pouze hodnoty, klíče zůstávají zachovány:

```
$obj.mapWithPrimitiveMissingValues *? 10
// {"a": 1, "b": null} -> {"a": 1, "b": 10}
```

Pokud samotná kolekce může být `null`, použijte `?*?` pro vrácení `null` místo vyvolání chyby:

```
$entity.localizedAttributes['prevUrl'] *? 'https://example.com'
// nahradí null hodnoty locale výchozí URL

$maybeNullList ?*? 0
// vrátí null, pokud je seznam null, jinak koaleskuje null položky hodnotou 0
```

Bez `?` vyvolá použití `*?` na operand `null` chybu. Můžete to kombinovat s operátorem null-koalescence pro vrácení výchozí hodnoty celé kolekce.

## Priorita operátorů

Operátory jsou seřazeny od nejvyšší po nejnižší prioritu:

| Priorita | Operátory | Popis |
|---|---|---|
| 1 | `()` | Seskupování závorkami |
| 2 | `.` `?.` `[]` `?[]` `.*[]` `?.*[]` | Přístup k objektu, prvku, rozšiřování |
| 3 | `!` `+` (unární) `-` (unární) | Negace, unární plus/mínus |
| 4 | `*` `/` `%` | Násobení, dělení, modulo |
| 5 | `+` `-` | Sčítání, odčítání |
| 6 | `>` `>=` `<` `<=` | Relační porovnání |
| 7 | `==` `!=` | Porovnání rovnosti |
| 8 | `^` | Logické XOR |
| 9 | `&&` | Logické AND |
| 10 | `\|\|` | Logické OR |
| 11 | `*?` `?*?` | Rozšiřovací null-koalescence |
| 12 | `??` | Null-koalescence |