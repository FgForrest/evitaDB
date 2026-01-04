---
title: Logické filtrování
perex: Logické výrazy jsou základem každého dotazovacího jazyka a evitaDB není výjimkou. Umožňují vám kombinovat více filtračních výrazů do jednoho jednoznačného výrazu.
date: '26.5.2023'
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
commit: cabcf999e7be5b00e0b13e1228a76a8d9e91cb78
---
<Note type="warning">

<NoteTitle toggles="false">

##### Co když není logická vazba nastavena explicitně?
</NoteTitle>

Aby byl dotazovací jazyk stručnější, vynecháváme logickou vazbu v omezujících podmínkách typu kontejneru a předpokládáme
logickou konjunkci „a zároveň“ ([`and`](#and)), pokud není explicitně nastavena jiná vazba.
Například můžete zadat následující dotaz:

<SourceCodeTabs requires="/evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad implicitní vazby](/documentation/user/en/query/filtering/examples/logical/implicit-binding.evitaql)
</SourceCodeTabs>

Jak můžete vidět – mezi podmínkami `entityPrimaryKeyInSet` a `attributeEquals` není žádná logická vazba, a v tomto případě bude použita logická konjunkce, což povede k vrácení jediného produktu s *kódem*
*lenovo-thinkpad-t495-2* v odpovědi.

</Note>

## And

```evitaql-syntax
and(
    filterConstraint:any+
)
```

<dl>
    <dt>filterConstraint:any+</dt>
    <dd>
        jedna nebo více povinných filtračních podmínek, které vytvoří logickou konjunkci
    </dd>
</dl>

Kontejner <LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/And.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Filter/And.cs</SourceClass></LS>
představuje [logickou konjunkci](https://cs.wikipedia.org/wiki/Konjunkce), což je znázorněno v následující tabulce:

|   A   |   B   | A ∧ B |
|:-----:|:-----:|:-----:|
|  True |  True |  True |
|  True | False | False |
| False |  True | False |
| False | False | False |

Následující dotaz:

<SourceCodeTabs requires="/evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad logické konjunkce](/documentation/user/en/query/filtering/examples/logical/and.evitaql)
</SourceCodeTabs>

... vrací jediný výsledek – produkt s primárním klíčem entity *106742*, což je jediný, který mají všechny tři podmínky `entityPrimaryKeyInSet` společné.

<Note type="info">

<NoteTitle toggles="true">

##### Seznam všech produktů odpovídajících filtru konjunkce
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Výsledek příkladu logické konjunkce](/documentation/user/en/query/filtering/examples/logical/and.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Výsledek příkladu logické konjunkce](/documentation/user/en/query/filtering/examples/logical/and.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Výsledek příkladu logické konjunkce](/documentation/user/en/query/filtering/examples/logical/and.rest.json.md)</MDInclude>

</LS>

</Note>

## Or

```evitaql-syntax
or(
    filterConstraint:any+
)
```

<dl>
    <dt>filterConstraint:any+</dt>
    <dd>
        jedna nebo více povinných filtračních podmínek, které vytvoří logickou disjunkci
    </dd>
</dl>

Kontejner <LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/Or.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Filter/Or.cs</SourceClass></LS>
představuje [logickou disjunkci](https://cs.wikipedia.org/wiki/Disjunkce), což je znázorněno v následující tabulce:

|   A   |   B   | A ∨ B |
|:-----:|:-----:|:-----:|
|  True |  True | True  |
|  True | False | True  |
| False |  True | True  |
| False | False | False |

Následující dotaz:

<SourceCodeTabs requires="/evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad logické disjunkce](/documentation/user/en/query/filtering/examples/logical/or.evitaql)
</SourceCodeTabs>

... vrací čtyři výsledky představující kombinaci všech primárních klíčů použitých v podmínkách `entityPrimaryKeyInSet`.

<Note type="info">

<NoteTitle toggles="true">

##### Seznam všech produktů odpovídajících filtru disjunkce
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Výsledek příkladu logické disjunkce](/documentation/user/en/query/filtering/examples/logical/or.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Výsledek příkladu logické disjunkce](/documentation/user/en/query/filtering/examples/logical/or.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Výsledek příkladu logické disjunkce](/documentation/user/en/query/filtering/examples/logical/or.rest.json.md)</MDInclude>

</LS>

</Note>

## Not

```evitaql-syntax
not(
    filterConstraint:any!
)
```

<dl>
    <dt>filterConstraint:any!</dt>
    <dd>
        jedna nebo více povinných filtračních podmínek, které budou odečteny od nadmnožiny všech entit
    </dd>
</dl>

Kontejner <LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/Not.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Filter/Not.cs</SourceClass></LS>
představuje [logickou negaci](https://cs.wikipedia.org/wiki/Negace), což je znázorněno v následující tabulce:

|   A   |  ¬ A  |
|:-----:|:-----:|
|  True | False |
| False | True  |

Následující dotaz:

<SourceCodeTabs requires="/evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad logické negace](/documentation/user/en/query/filtering/examples/logical/not.evitaql)
</SourceCodeTabs>

... vrací tisíce výsledků s výjimkou entit s primárními klíči uvedenými v podmínce `entityPrimaryKeyInSet`.

<Note type="info">

<NoteTitle toggles="true">

##### Seznam všech produktů odpovídajících filtru negace
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Výsledek příkladu logické negace](/documentation/user/en/query/filtering/examples/logical/not.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Výsledek příkladu logické negace](/documentation/user/en/query/filtering/examples/logical/not.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Výsledek příkladu logické negace](/documentation/user/en/query/filtering/examples/logical/not.rest.json.md)</MDInclude>

</LS>

</Note>

Protože je tato situace obtížně představitelná – zúžíme naši nadmnožinu pouze na několik entit:

<SourceCodeTabs requires="/evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad logické konjunkce](/documentation/user/en/query/filtering/examples/logical/not-narrowed.evitaql)
</SourceCodeTabs>

... což vrací pouze tři produkty, které nebyly vyloučeny následující podmínkou `not`.

<Note type="info">

<NoteTitle toggles="true">

##### Seznam všech produktů odpovídajících filtru negace (zúžený)
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Výsledek příkladu logické negace (zúžený)](/documentation/user/en/query/filtering/examples/logical/not-narrowed.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Výsledek příkladu logické negace (zúžený)](/documentation/user/en/query/filtering/examples/logical/not-narrowed.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Výsledek příkladu logické negace (zúžený)](/documentation/user/en/query/filtering/examples/logical/not-narrowed.rest.json.md)</MDInclude>

</LS>

</Note>