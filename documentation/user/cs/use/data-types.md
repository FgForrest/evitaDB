---
title: Datové typy
perex: Článek poskytuje úvod do datových typů v dotazovacím jazyce EvitaDB, včetně základních a složených typů, a obsahuje ukázky kódu pro demonstraci jejich použití.
date: '23.8.2023'
author: Ing. Jan Novotný
proofreading: done
preferredLang: java
commit: cef96d8320d36c91c100c5dfc9c45020b5a7ad0d
---
Tento dokument uvádí všechny datové typy podporované evitaDB, které lze použít v [atributech](data-model.md#atributy-unikátní-filtrovatelné-řaditelné-lokalizované) nebo [asociovaných datech](data-model.md#přidružená-data) pro ukládání informací relevantních pro klienta.

Existují dvě kategorie datových typů:

1. [Jednoduché datové typy](#jednoduché-datové-typy), které lze použít jak pro [atributy](data-model.md#atributy-unikátní-filtrovatelné-řaditelné-lokalizované), tak pro [asociovaná data](data-model.md#přidružená-data)
2. [Komplexní datové typy](#komplexní-datové-typy), které lze použít pouze pro [asociovaná data](data-model.md#přidružená-data)

## Jednoduché datové typy

<LS to="e,j">

Datové typy evitaDB jsou omezeny na následující seznam:

- [String](#string),
    formátováno jako `"string"`
- [Byte](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Byte.html),
    formátováno jako `5`
- [Short](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Short.html),
    formátováno jako `5`
- [Integer](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Integer.html),
    formátováno jako `5`
- [Long](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Long.html),
    formátováno jako `5`
- [Boolean](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Boolean.html),
    formátováno jako `true`
- [Character](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Character.html),
    formátováno jako `'c'`
- [BigDecimal](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/math/BigDecimal.html),
    formátováno jako `1.124`
- [OffsetDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/OffsetDateTime.html),
    formátováno jako `2021-01-01T00:00:00+01:00`
- [LocalDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/LocalDateTime.html),
    formátováno jako `2021-01-01T00:00:00`
- [LocalDate](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/LocalDate.html),
    formátováno jako `2021-01-01`
- [LocalTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/LocalTime.html),
    formátováno jako `00:00:00`
- [DateTimeRange](#datetimerange),
    formátováno jako `[2021-01-01T00:00:00+01:00,2022-01-01T00:00:00+01:00]`
- [BigDecimalNumberRange](#numberrange),
    formátováno jako `[1.24,78]`
- [LongNumberRange](#numberrange),
    formátováno jako `[5,9]`
- [IntegerNumberRange](#numberrange),
    formátováno jako `[5,9]`
- [ShortNumberRange](#numberrange),
    formátováno jako `[5,9]`
- [ByteNumberRange](#numberrange),
    formátováno jako `[5,9]`
- [Locale](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Locale.html),
    formátováno jako jazykový tag `'cs-CZ'`
- [Currency](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Currency.html),
    formátováno jako `'CZK'`
- [UUID](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/UUID.html),
    formátováno jako `2fbbfcf2-d4bb-4db9-9658-acf1d287cbe9`
- [Predecessor](#predecessor),
    formátováno jako `789`

</LS>
<LS to="c">

Datové typy evitaDB jsou omezeny na následující seznam:

- [string](#string),
  formátováno jako `"string"`
- [byte](https://learn.microsoft.com/cs-cz/dotnet/api/system.byte),
  formátováno jako `5`
- [short](https://learn.microsoft.com/en-us/dotnet/api/system.int16),
  formátováno jako `5`
- [int](https://learn.microsoft.com/en-us/dotnet/api/system.int32),
  formátováno jako `5`
- [long](https://learn.microsoft.com/en-us/dotnet/api/system.int64),
  formátováno jako `5`
- [bool](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/builtin-types/bool),
  formátováno jako `true`
- [char](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/builtin-types/char),
  formátováno jako `'c'`
- [decimal](https://learn.microsoft.com/en-us/dotnet/api/system.decima),
  formátováno jako `1.124`
- [DateTimeOffset](https://learn.microsoft.com/en-us/dotnet/api/system.datetimeoffset),
  formátováno jako `2021-01-01T00:00:00+01:00`
- [DateTime](https://learn.microsoft.com/en-us/dotnet/api/system.datetime),
  formátováno jako `2021-01-01T00:00:00`
- [DateOnly](https://learn.microsoft.com/en-us/dotnet/api/system.dateonly),
  formátováno jako `00:00:00`
- [TimeOnly](https://learn.microsoft.com/en-us/dotnet/api/system.timeonly),
  formátováno jako `2021-01-01`
- [DateTimeRange](#datetimerange),
  formátováno jako `[2021-01-01T00:00:00+01:00,2022-01-01T00:00:00+01:00]`
- [DecimalNumberRange](#numberrange),
  formátováno jako `[1.24,78]`
- [LongNumberRange](#numberrange),
  formátováno jako `[5,9]`
- [IntegerNumberRange](#numberrange),
  formátováno jako `[5,9]`
- [ShortNumberRange](#numberrange),
  formátováno jako `[5,9]`
- [ByteNumberRange](#numberrange),
  formátováno jako `[5,9]`
- [CultureInfo](https://learn.microsoft.com/en-us/dotnet/api/system.globalization.cultureinfo),
  formátováno jako jazykový tag `'cs-CZ'`
- [Currency](https://github.com/FgForrest/evitaDB-C-Sharp-client/blob/master/EvitaDB.Client/DataTypes/Currency.cs),
  formátováno jako `'CZK'`
- [GUID](https://learn.microsoft.com/en-us/dotnet/api/system.guid),
  formátováno jako `2fbbfcf2-d4bb-4db9-9658-acf1d287cbe9`
- [Predecessor](#predecessor),
  formátováno jako `789`

</LS>
<LS to="g,r">

Datové typy jsou založeny na datových typech Javy, protože tak jsou ukládány interně. Jediný rozdíl
je v tom, jak jsou formátovány. Datové typy evitaDB jsou omezeny na následující seznam:

- [String](#string),
    formátováno jako `'string'`
- [Byte](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Byte.html),
    formátováno jako `5`
- [Short](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Short.html),
    formátováno jako `5`
- [Integer](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Integer.html),
    formátováno jako `5`
- [Long](#long),
    formátováno jako `"5"`
- [Boolean](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Boolean.html),
    formátováno jako `true`
- [Character](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Character.html),
    formátováno jako `"c"`
- [BigDecimal](#bigdecimal),
    formátováno jako `"1.124"`
- [OffsetDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/OffsetDateTime.html),
    formátováno jako `"2021-01-01T00:00:00+01:00"`
- [LocalDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/LocalDateTime.html),
    formátováno jako `"2021-01-01T00:00:00"`
- [LocalDate](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/LocalDate.html),
    formátováno jako `"00:00:00"`
- [LocalTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/LocalTime.html),
    formátováno jako `"2021-01-01"`
- [DateTimeRange](#datetimerange),
    formátováno jako `["2021-01-01T00:00:00+01:00", "2022-01-01T00:00:00+01:00"]`
- [BigDecimalNumberRange](#numberrange),
    formátováno jako `["1.24", "78"]`
- [LongNumberRange](#numberrange),
    formátováno jako `["5", "9"]`
- [IntegerNumberRange](#numberrange),
    formátováno jako `[5, 9]`
- [ShortNumberRange](#numberrange),
    formátováno jako `[5, 9]`
- [ByteNumberRange](#numberrange),
    formátováno jako `[5, 9]`
- [Locale](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Locale.html),
    formátováno jako jazykový tag `"cs-CZ"`
- [Currency](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Currency.html),
    formátováno jako `"CZK"`
- [UUID](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/UUID.html),
    formátováno jako `"2fbbfcf2-d4bb-4db9-9658-acf1d287cbe9"`
- [Predecessor](#predecessor),
  formátováno jako `789`

</LS>

<LS to="j,g,r,c">

Pole jednoduchého typu je stále jednoduchý datový typ. Všechny jednoduché typy lze zabalit do pole. Není možné míchat
pole a ne-pole v jednom schématu *atributu* / *asociovaných dat*. Jakmile schéma *atributu* nebo *asociovaných dat*
určí, že přijímá pole celých čísel, nemůže uložit jedinou hodnotu celého čísla, a naopak.
Atribut/Asociovaná data typu integer nikdy nepřijmou pole celých čísel.

<Note type="warning">
Protože evitaDB uchovává všechna data v indexech v hlavní paměti, důrazně doporučujeme používat nejkratší/nejmenší datové typy,
které mohou pojmout vaše data. Snažíme se minimalizovat paměťovou stopu databáze, ale klíčová rozhodnutí jsou na vaší straně,
takže pečlivě zvažte, jaký datový typ zvolíte a zda jej nastavíte jako filtrovatelný/tříditelný,
což vyžaduje paměťový index.
</Note>

</LS>

<LS to="j">

<Note type="info">

Aplikační logika spojená s datovými typy evitaDB se nachází ve třídě
<SourceClass>evita_common/src/main/java/io/evitadb/dataType/EvitaDataTypes.java</SourceClass>.

</Note>

</LS>

<LS to="c">

<Note type="info">

Aplikační logika spojená s datovými typy evitaDB se nachází ve třídě
<SourceClass>EvitaDB.Client/DataTypes/EvitaDataTypes.cs</SourceClass>.

</Note>

</LS>

### String

<LS to="j,e,g,r">
[Typ string](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html) je interně kódován pomocí znakové sady [UTF-8](https://en.wikipedia.org/wiki/UTF-8). Dotazovací jazyk evitaDB
a další I/O metody evitaDB implicitně používají toto kódování.
</LS>
<LS to="c">
[Typ string](https://learn.microsoft.com/en-us/dotnet/api/system.string) je interně kódován pomocí znakové sady [UTF-8](https://en.wikipedia.org/wiki/UTF-8). Dotazovací jazyk evitaDB
a další I/O metody evitaDB implicitně používají toto kódování.
</LS>

<LS to="g,r">

### Long

Protože datový typ [64bitové celé číslo long](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Long.html) pochází z Javy,
některé jazyky (např. [JavaScript](https://stackoverflow.com/a/17320771)) mohou mít problémy s jeho velikostí při
parsování velkých čísel z JSON do svých výchozích číselných datových typů.
Proto jsme se rozhodli formátovat datový typ long jako řetězec. Tím pádem zde není žádný limit velikosti a
klient může vždy číslo správně zpracovat bez obav, že výchozí číselný datový typ není dostatečně velký pro parsované číslo.

### BigDecimal

evitaDB podporuje datový typ [BigDecimal](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/math/BigDecimal.html)
namísto základních typů float nebo double, které se nacházejí ve většině programovacích jazyků. Hlavním důvodem je, že typy float a double
nejsou dostatečně přesné pro finanční výpočty. Z tohoto důvodu jsou hodnoty BigDecimal formátovány jako řetězce.
I když formát JSON má způsoby, jak do určité míry zajistit správnou přesnost, nemůžeme zaručit, že klientský programovací jazyk
při parsování čísla použije správný datový typ, který zachová přesnost.

</LS>

### Datum a čas

<LS to="j,e,g,r">
Ačkoli evitaDB podporuje *lokální* varianty data a času jako
[LocalDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/LocalDateTime.html), vždy je
převádí na [OffsetDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/OffsetDateTime.html)
pomocí výchozí časové zóny systému serveru evitaDB. Výchozí časovou zónu Javy můžete ovlivnit
[několika způsoby](https://www.baeldung.com/java-jvm-time-zone). Pokud jsou vaše data závislá na časové zóně, doporučujeme pracovat
přímo s [OffsetDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/OffsetDateTime.html)
na straně klienta a být explicitní ohledně offsetu již od začátku.
</LS>
<LS to="c">
Ačkoli evitaDB podporuje *lokální* varianty data a času jako
[DateTime](https://learn.microsoft.com/en-us/dotnet/api/system.datetime), vždy je
používána výchozí časová zóna systému serveru evitaDB. Výchozí časovou zónu Javy můžete ovlivnit
[několika způsoby](https://www.baeldung.com/java-jvm-time-zone). Pokud jsou vaše data závislá na časové zóně, doporučujeme pracovat
přímo s [DateTimeOffset](https://learn.microsoft.com/en-us/dotnet/api/system.datetimeoffset)
na straně klienta a být explicitní ohledně offsetu již od začátku.
</LS>

<Note type="question">

<LS to="j,e,g,r">
<NoteTitle toggles="true">
##### Proč interně používáme OffsetDateTime pro časové informace?
</NoteTitle>
</LS>

<LS to="c">
<NoteTitle toggles="true">
##### Proč interně používáme DateTimeOffset pro časové informace?
</NoteTitle>
</LS>

Zpracování offsetu/časové zóny se liší databázi od databáze. Chtěli jsme se vyhnout nastavování časové zóny v session nebo
konfiguračních vlastnostech databáze, protože tento mechanismus je náchylný k chybám a nepraktický. Ukládání/načítání data a času s
informací o časové zóně by bylo nejlepší možností, ale narážíme na problémy s
[parsováním](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Date/parse) v určitých
prostředích a pouze datum s informací o offsetu se zdá být široce podporováno. Informace o offsetu je pro náš případ dostatečná –
identifikuje globálně platný čas, který je znám v okamžiku uložení datové hodnoty.

</Note>

### DateTimeRange

<LS to="j,e,g,r">
DateTimeRange představuje konkrétní implementaci
<SourceClass>evita_common/src/main/java/io/evitadb/dataType/Range.java</SourceClass>, která definuje levý a pravý okraj
pomocí datových typů [OffsetDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/OffsetDateTime.html).
Offsetová data a časy jsou zapsány v ISO formátu.
</LS>
<LS to="c">
DateTimeRange představuje konkrétní implementaci
<SourceClass>EvitaDB.Client/DataTypes/Range.cs</SourceClass>, která definuje levý a pravý okraj
pomocí datových typů [DateTimeOffset](https://learn.microsoft.com/en-us/dotnet/api/system.datetimeoffset).
Offsetová data a časy jsou zapsány v ISO formátu.
</LS>

**Rozsah je zapsán jako:**

- když jsou zadány oba okraje:

<LS to="e,j,c">

```plain
[2021-01-01T00:00:00+01:00,2022-01-01T00:00:00+01:00]
```

</LS>
<LS to="g,r">

```json
["2021-01-01T00:00:00+01:00","2022-01-01T00:00:00+01:00"]
```

</LS>

- když je zadán levý okraj (od):

<LS to="e,j,c">

```plain
[2021-01-01T00:00:00+01:00,]
```

</LS>
<LS to="g,r">

```json
["2021-01-01T00:00:00+01:00",null]
```

</LS>

- když je zadán pravý okraj (do):

<LS to="e,j,c">

```plain
[,2022-01-01T00:00:00+01:00]
```

</LS>
<LS to="g,r">

```json
[null,"2022-01-01T00:00:00+01:00"]
```

</LS>

### NumberRange

<LS to="j,e,g,r">
NumberRange představuje konkrétní implementaci
<SourceClass>evita_common/src/main/java/io/evitadb/dataType/Range.java</SourceClass>
definující levý a pravý okraj pomocí datových typů [Number](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Number.html).
Podporované číselné typy jsou: Byte, Short, Integer, Long a BigDecimal.

Oba okraje číselného rozsahu musí být stejného typu – nelze například použít BigDecimal jako dolní mez
a Byte jako horní mez.

</LS>
<LS to="c">
NumberRange představuje konkrétní implementaci
<SourceClass>EvitaDB.Client/DataTypes/Range.cs</SourceClass>
definující levý a pravý okraj pomocí některého z podporovaných
datových typů. Podporované číselné typy jsou: byte, short, int, long a decimal.

Oba okraje číselného rozsahu musí být stejného typu – nelze například použít decimal jako dolní mez
a byte jako horní mez.

</LS>

**Rozsah je zapsán jako:**

- když jsou zadány oba okraje:

<LS to="e,j,c">

```plain
[1,3.256]
```

</LS>
<LS to="g,r">

```json
["1","3.256"]
```
</LS>

- když je zadán levý okraj (od):

<LS to="e,j,c">

```plain
[1,]
```

</LS>
<LS to="g,r">

```json
["1",null]
```

</LS>

- když je zadán pravý okraj (do):

<LS to="e,j,c">

```plain
[,3.256]
```

</LS>
<LS to="g,r">

```json
[null,"3.256"]
```

</LS>

### Predecessor

<LS to="e,j,r,g"><SourceClass>evita_common/src/main/java/io/evitadb/dataType/Predecessor.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/DataTypes/Predecessor.cs</SourceClass></LS> je speciální datový typ
používaný k definování jednosměrného propojeného seznamu entit stejného typu. Představuje ukazatel na předchozí entitu
v seznamu. Hlavní prvek je speciální případ a je reprezentován konstantou `Predecessor#HEAD`. Atribut predecessor
lze použít pouze v [atributech](data-model.md#atributy-unikátní-filtrovatelné-řaditelné-lokalizované) entity nebo její reference na jinou entitu. Nelze jej použít pro filtrování entit, ale je velmi užitečný pro řazení.

#### Motivace pro propojené seznamy při řazení v databázi

Propojený seznam je velmi optimální datová struktura pro řazení entit v databázi, která obsahuje velké množství dat.
Vložení nového prvku do propojeného seznamu je operace s konstantní časovou složitostí a vyžaduje pouze dvě aktualizace:

1) vložení nového prvku do seznamu, který ukazuje na existující prvek jako svého předchůdce
2) aktualizace původního prvku ukazujícího na předchůdce tak, aby ukazoval na nový prvek.

Přesun (aktualizace) prvku nebo odstranění existujícího prvku z propojeného seznamu je také operace s konstantní časovou složitostí,
vyžadující podobné dvě aktualizace. Nevýhodou propojeného seznamu je jeho špatný výkon při náhodném přístupu (získání prvku na n-tém indexu)
a při průchodu seznamem, což vyžaduje mnoho náhodných přístupů do různých částí paměti. Tyto nevýhody však lze zmírnit uchováváním propojeného seznamu ve formě pole nebo binárního stromu správně umístěných primárních klíčů.

<Note type="info">

<NoteTitle toggles="true">

##### Nejsou lepší přístupy pro uchování seřazeného seznamu entit?
</NoteTitle>

Existují alternativní přístupy k tomuto problému, ale všechny mají své nevýhody. Některé z nich jsou shrnuty v
[článku "Keeping an ordered collection in PostgreSQL" od Nicolase Goye](https://medium.com/the-missing-bit/keeping-an-ordered-collection-in-postgresql-9da0348c4bbe). Prošli jsme podobnou cestou a
došli jsme k závěru, že propojený seznam je nejmenší zlo:

- Nevyžaduje hromadné aktualizace okolních entit nebo občasné "přeskupení".
- nekomplikuje klientskou logiku (a dobře spolupracuje s UI drag'n'drop přesouváním)
- je velmi úsporný na data – vyžaduje pouze jeden <LS to="e,j,r,g">[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)</LS><LS to="c">[int](https://learn.microsoft.com/en-us/dotnet/api/system.int32)</LS>
  (4B) na jednu položku v seznamu

</Note>

#### Udržování konzistence propojeného seznamu

Sestavení propojeného seznamu může být z pohledu konzistence složitý proces – zejména ve
[fázi warm-up](api/write-data.md#hromadné-indexování), kdy potřebujete rekonstruovat data z externího primárního úložiště.
Abyste byli konzistentní za všech okolností, museli byste začít entitou, která představuje hlavu řetězce, pak vložit
jejího následníka, a tak dále. To často není triviální, a pokud máte dva atributy predecessor s různým "pořadím" pro stejné entity, je to naprosto nemožné.

Proto jsme navrhli naši implementaci propojeného seznamu tak, aby tolerovala částečné nekonzistence a konvergovala ke
konzistentnímu stavu, jakmile budou vložena chybějící data. Podporujeme tyto scénáře nekonzistence:

- více hlavních prvků
- více následníků pro jednoho předchůdce
- kruhové závislosti, kdy hlavní prvek ukazuje na prvek ve svém ocasu

Řazení podle nekonzistentního atributu predecessor řadí entity podle řetězců v následujícím pořadí:

1) řetězce začínající hlavním prvkem (od řetězce s nejvíce prvky po řetězec s nejméně prvky)
2) řetězce s prvky sdílejícími stejného předchůdce (od řetězce s nejvíce prvky po řetězec s nejméně prvky)
3) řetězce s kruhovými závislostmi (od řetězce s nejvíce prvky po řetězec s nejméně prvky)

Když budou závislosti opraveny, pořadí řazení se zkonverguje ke správnému.
<SourceClass>evita_engine/src/main/java/io/evitadb/index/attribute/ChainIndex.java</SourceClass> bude obsahovat pouze
jeden řetězec správně seřazených prvků a vrátí true při volání metody `isConsistent()`.

Nekonzistentní stav je povolen i v transakční fázi, ale doporučujeme se mu vyhnout a aktualizovat všechny
zúčastněné prvky (v libovolném pořadí) v rámci jedné transakce, což zajistí, že propojený seznam zůstane
konzistentní pro všechny ostatní transakce.

## Komplexní datové typy

<LS to="e,j,c">

Komplexní typy jsou typy, které nesplňují podmínky [jednoduchých typů evitaDB](#jednoduché-datové-typy) (nebo pole jednoduchých
typů evitaDB). Komplexní typy jsou ukládány ve struktuře
<LS to="e,j"><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ComplexDataObject.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/DataTypes/ComplexDataObject.cs</SourceClass></LS>, která je
záměrně podobná struktuře JSON, takže ji lze snadno převést do formátu JSON a také přijmout
a uložit jakýkoli platný JSON dokument.

</LS>

<LS to="g,r">

Komplexní typy jsou všechny typy, které nesplňují podmínky [jednoduchých typů evitaDB](#jednoduché-datové-typy) (nebo pole jednoduchých
typů evitaDB). Komplexní typy jsou zapsány jako JSON objekty, aby umožnily libovolnou strukturu objektu s jednoduchou serializací a deserializací.
To však přináší určitá omezení – datové typy vlastností komplexního typu jsou aktuálně omezeny pouze na datové typy
podporované čistým JSON. To znamená, že můžete například uložit datum a čas jako řetězec, jak byste to běžně dělali s
[jednoduchými datovými typy](#jednoduché-datové-typy), ale interně bude uloženo pouze jako řetězec (protože nemáme
informaci o konkrétním datovém typu) a je na vás, abyste provedli manuální konverzi na straně klienta.

</LS>

<LS to="j">

Komplexní typ v Javě je třída, která implementuje rozhraní Serializable a nepatří do balíčku `java` nebo není přímo podporována
[jednoduchými datovými typy](#jednoduché-datové-typy) (tj. `java.lang.URL` je zakázáno ukládat do evitaDB, i když je serializovatelná a patří do balíčku `java`, protože není přímo podporována [jednoduchými datovými typy](#jednoduché-datové-typy)). Komplexní typy jsou určeny pro klientské POJO třídy pro přenos větších dat nebo pro asociaci jednoduché logiky s daty.

</LS>
<LS to="c">

Komplexní typ v C# je record, který nepatří mezi `built-in` typy C#,
a není přímo podporován [jednoduchými datovými typy](#jednoduché-datové-typy) (tj. `System.Uri` je zakázáno ukládat do evitaDB, i když je serializovatelný (implementuje rozhraní ISerializable) a patří mezi `built-in` typy C#, protože není přímo podporován [jednoduchými datovými typy](#jednoduché-datové-typy)).
Komplexní typy jsou určeny pro klientské POCO třídy pro přenos větších dat nebo pro asociaci jednoduché logiky s daty.

</LS>

<LS to="e,j">

<Note type="info">
Asociovaná data mohou obsahovat i pole komplexních objektů. Taková data budou automaticky převedena na pole typů
`ComplexDataObject` – tj. `ComplexDataObject[]`.
</Note>

### Komplexní typ může obsahovat vlastnosti

- jakéhokoli [jednoduchého typu evitaDB](#jednoduché-datové-typy)
- jakéhokoli jiného komplexního typu (další vnořená POJO)
- generických [Listů](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/List.html)
- generických [Setů](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Set.html)
- generických [Map](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Map.html)
- libovolného pole [jednoduchých typů evitaDB](#jednoduché-datové-typy) nebo [komplexních typů](#komplexní-datové-typy)

</LS>
<LS to="c">

<Note type="info">
Asociovaná data mohou obsahovat i pole komplexních objektů. Taková data budou automaticky převedena na pole typů
`ComplexDataObject` – tj. `ComplexDataObject[]`.
</Note>

### Komplexní typ může obsahovat vlastnosti

- jakéhokoli [jednoduchého typu evitaDB](#jednoduché-datové-typy)
- jakéhokoli jiného komplexního typu (další vnořená POCO)
- generických [Listů](https://learn.microsoft.com/en-us/dotnet/api/system.collections.generic.ilist-1)
- generických [Setů](https://learn.microsoft.com/en-us/dotnet/api/system.collections.generic.iset-1)
- generických [Dictionaries](https://learn.microsoft.com/cs-cz/dotnet/api/system.collections.idictionary)
- libovolného pole [jednoduchých typů evitaDB](#jednoduché-datové-typy) nebo [komplexních typů](#komplexní-datové-typy)

</LS>
<LS to="g,r">

<Note type="info">
Asociovaná data mohou obsahovat i pole komplexních objektů. Taková data budou automaticky převedena na pole typů
`ComplexDataObject` – tj. `ComplexDataObjectArray`.
</Note>

</LS>

<LS to="c">

### Serializace

Všechny veřejné vlastnosti s veřejnými gettery a settery jsou serializovány do komplexního typu, pokud nejsou dekorovány
a nejsou anotovány <SourceClass>EvitaDB.Client/DataTypes/Data/NonSerializableDataAttribute.cs</SourceClass>.
Viz následující příklad:

<SourceCodeTabs local>
[Asociovaná data POCO](/documentation/user/en/use/examples/dto.cs)
</SourceCodeTabs>

Uložení komplexního typu do entity se provádí následovně:

<SourceCodeTabs requires="/documentation/user/en/use/examples/dto.java,/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java,/documentation/user/en/get-started/example/define-catalog-with-schema.java,/documentation/user/en/use/api/example/open-session-manually.java" local>

[Uložení asociovaných dat do entity](/documentation/user/en/use/examples/storing.cs)

</SourceCodeTabs>

Pokud proces serializace narazí na vlastnost, kterou nelze serializovat,
je vyhozena výjimka <SourceClass>EvitaDB.Client/Exceptions/EvitaInvalidUsageException.cs</SourceClass>.

### Deserializace

Načtení komplexního typu z entity se provádí následovně:

<SourceCodeTabs requires="/documentation/user/en/use/examples/storing.java" local>

[Načtení asociovaných dat z entity](/documentation/user/en/use/examples/loading.cs)

</SourceCodeTabs>

Komplexní typy jsou interně převedeny na typ
<SourceClass>EvitaDB.Client/DataTypes/ComplexDataObject.cs</SourceClass>,
který lze bezpečně uložit do úložiště evitaDB. Proces (de)serializace je navržen tak, aby zabránil ztrátě dat a
umožnil evoluci modelu.

Proces deserializace může selhat dvěma výjimkami:

- [FormatException](https://learn.microsoft.com/en-us/dotnet/api/System.FormatException)
  je vyhozena, pokud některou vlastnost nelze deserializovat kvůli nekompatibilitě
  se specifikovaným [kontraktem](#komplexní-typ-může-obsahovat-vlastnosti)
- [InvalidCastException](https://learn.microsoft.com/en-us/dotnet/api/System.InvalidCastException)
  je vyhozena, pokud některá ze serializovaných dat nebyla deserializována kvůli absenci mutační metody na třídě, do které je převáděna

</LS>

<LS to="j">

### Serializace

Všechny [vlastnosti odpovídající JavaBean konvencím pojmenování](https://www.baeldung.com/java-pojo-class#what-is-a-javabean) a
mají jak accessor, tak mutator metodu (tj. `get` a `set` metody pro vlastnost) a nejsou anotovány
<SourceClass>evita_common/src/main/java/io/evitadb/dataType/data/NonSerializedData.java</SourceClass>
anotací, jsou serializovány do komplexního typu. Viz následující příklad:

<SourceCodeTabs local>
[Asociovaná data POJO](/documentation/user/en/use/examples/dto.java)
</SourceCodeTabs>

Uložení komplexního typu do entity se provádí následovně:

<SourceCodeTabs requires="/documentation/user/en/use/examples/dto.java,/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java,/documentation/user/en/get-started/example/define-catalog-with-schema.java,/documentation/user/en/use/api/example/open-session-manually.java" local>

[Uložení asociovaných dat do entity](/documentation/user/en/use/examples/storing.java)

</SourceCodeTabs>

Jak vidíte, anotace lze umístit buď na metody, nebo na pole vlastnosti, takže pokud používáte
[podporu Lombok](https://projectlombok.org/), můžete stále snadno definovat třídu jako:

<SourceCodeTabs local>
[Asociovaná data Lombok POJO](/documentation/user/en/use/examples/dto-lombok.java)
</SourceCodeTabs>

Pokud proces serializace narazí na vlastnost, kterou nelze serializovat,
je vyhozena výjimka <SourceClass>evita_common/src/main/java/io/evitadb/dataType/exception/SerializationFailedException.java</SourceClass>.

#### Generické kolekce

V komplexních typech můžete používat kolekce, ale konkrétní typy kolekcí musí být zjistitelné z generik kolekce v době deserializace. Podívejte se na následující příklad:

<SourceCodeTabs local>
[Asociovaná data POJO s kolekcemi](/documentation/user/en/use/examples/dto-collection.java)
</SourceCodeTabs>

Tato třída se bude (de)serializovat bez problémů.

<Note type="warning">
Generika kolekcí musí být rozpoznatelná na přesnou třídu (to znamená, že generika s wildcard nejsou podporována). Komplexní
typ může být také neměnná třída, přijímající vlastnosti prostřednictvím parametrů konstruktoru. Neměnné třídy musí být
zkompilovány s argumentem javac `-parameters` a jejich názvy v konstruktoru musí odpovídat názvům vlastností getterů. To velmi dobře funguje s [Lombok @Data anotací](https://projectlombok.org/features/Data).
</Note>

#### Doporučení pro testování

Protože metody, které neodpovídají JavaBeans kontraktu, jsou tiše přeskočeny, je vysoce doporučeno vždy
uložit a načíst asociovaná data v unit testu a ověřit, že všechna důležitá data jsou skutečně uložena:

``` java
@Test
void verifyProductStockAvailabilityIsProperlySerialized() {
    final EntityBuilder entity = new InitialEntityBuilder("product");
    final ProductStockAvailability beforeStore = new ProductStockAvailability();
    entity.setAssociatedData("stockAvailability", beforeStore);
    //nějaká vlastní logika pro načtení entity
    final SealedEntity loadedEntity = entity();
    final ProductStockAvailability afterLoad = loadedEntity.getAssociatedData(
        "stockAvailability", ProductStockAvailability.class
    );
    assertEquals(
        beforeStore, afterLoad,
        "ProductStockAvailability nebyl kompletně serializován!"
    );
}
```

### Deserializace

Načtení komplexního typu z entity se provádí následovně:

<SourceCodeTabs requires="/documentation/user/en/use/examples/storing.java" local>

[Načtení asociovaných dat z entity](/documentation/user/en/use/examples/loading.java)

</SourceCodeTabs>

Komplexní typy jsou interně převedeny na typ
<SourceClass>evita_common/src/main/java/io/evitadb/dataType/ComplexDataObject.java</SourceClass>,
který lze bezpečně uložit do úložiště evitaDB. Proces (de)serializace je navržen tak, aby zabránil ztrátě dat a
umožnil evoluci modelu.

Proces deserializace může selhat dvěma výjimkami:

- <SourceClass>evita_common/src/main/java/io/evitadb/dataType/exception/UnsupportedDataTypeException.java</SourceClass>
  je vyhozena, pokud některou vlastnost nelze deserializovat kvůli nekompatibilitě
  se specifikovaným [kontraktem](#komplexní-typ-může-obsahovat-vlastnosti)
- <SourceClass>evita_common/src/main/java/io/evitadb/dataType/exception/IncompleteDeserializationException.java</SourceClass>
  je vyhozena, pokud některá ze serializovaných dat nebyla deserializována kvůli absenci mutační metody na třídě, do které je převáděna

#### Podpora evoluce modelu

##### Odstranění pole

Výjimka <SourceClass>evita_common/src/main/java/io/evitadb/dataType/exception/IncompleteDeserializationException.java</SourceClass>
chrání vývojáře před nechtěnou ztrátou dat při chybě v Java modelu a následném provedení:

- načtení existujícího komplexního typu
- změně několika vlastností
- opětovném uložení do evitaDB

Pokud existuje legální důvod pro odstranění některých dat uložených spolu s jejich komplexním typem v předchozích verzích aplikace,
můžete použít anotaci <SourceClass>evita_common/src/main/java/io/evitadb/dataType/data/DiscardedData.java</SourceClass>
na jakékoli třídě komplexního typu k deklaraci, že je v pořádku při deserializaci data zahodit.

<Note type="example">
Asociovaná data byla uložena s touto definicí třídy:

``` java
@Data
public class ProductStockAvailability implements Serializable {
    private int id;
    private String stockName;
}
```

V budoucích verzích se vývojář rozhodne, že pole `id` již není potřeba a může být odstraněno. Ale je zde
mnoho dat zapsaných předchozí verzí aplikace. Při odstraňování pole je tedy potřeba dát evitaDB najevo, že přítomnost jakýchkoli dat `id` je v pořádku, i když pro ně již neexistuje pole. Tato data budou
zahozena, když se asociovaná data přepíšou novou verzí třídy:

``` java
@Data
@DiscardedData("id")
public class ProductStockAvailability implements Serializable {
    private String stockName;
}
```
</Note>

##### Přejmenování pole a řízená migrace

Existují také situace, kdy potřebujete pole přejmenovat (například jste udělali překlep v předchozí verzi
Java Bean typu). V takovém případě také narazíte na
<SourceClass>evita_common/src/main/java/io/evitadb/dataType/exception/IncompleteDeserializationException.java</SourceClass>
když se pokusíte deserializovat typ s opravenou definicí Java Bean. V této situaci můžete použít
anotaci <SourceClass>evita_common/src/main/java/io/evitadb/dataType/data/RenamedData.java</SourceClass> k migraci
starších verzí dat.

<Note type="example">
První verze Java typu s chybou:

``` java
@Data
public class ProductStockAvailability implements Serializable {
    private String stockkName;
}
```

Příště se pokusíme překlep opravit:

``` java
@Data
public class ProductStockAvailability implements Serializable {
    @RenamedData("stockkName")
    private String stockname;
}
```

Ale uděláme další chybu, takže potřebujeme další opravu:

``` java
@Data
public class ProductStockAvailability implements Serializable {
    @RenamedData({"stockkName", "stockname"})
    private String stockName;
}
```
</Note>

Těchto anotací se můžeme zbavit, až si budeme jisti, že v evitaDB nejsou žádná data se starým obsahem.
Anotace <SourceClass>evita_common/src/main/java/io/evitadb/dataType/data/RenamedData.java</SourceClass>
může být také použita pro evoluci modelu – tj. automatický překlad starého datového formátu na nový.

<Note type="example">
Starý model:

``` java
@Data
public class ProductStockAvailability implements Serializable {
    private String stockName;
}
```

Nový model:

``` java
@Data
public class ProductStockAvailability implements Serializable {
    private String upperCasedStockName;

    @RenamedData
    public void setStockName(String stockName) {
        this.upperCasedStockName = stockName == null ?
            null : stockName.toUpperCase();
    }
}
```
</Note>

</LS>