---
title: Filtrování podle lokalizace
perex: Mnoho e-commerce aplikací funguje v různých regionech a spoléhá na lokalizovaná data. Zatímco produktové štítky a popisy jsou jasnými příklady, existuje také několik číselných hodnot, které musí být specifické pro každou lokalitu kvůli rozdílům mezi metrickým systémem a imperiálními jednotkami. Proto evitaDB nabízí prvotřídní podporu lokalizace ve svých datových strukturách a dotazovacím jazyce.
date: '27.5.2023'
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
translated: 'true'
commit: ecc9ddd4a929f8020bca123be8bf4b2ed9b635b7
---
## Entity locale equals

```evitaql-syntax
entityLocaleEquals(
    argument:string!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        povinné určení [locale](https://en.wikipedia.org/wiki/IETF_language_tag), kterému musí odpovídat všechny
        lokalizované atributy cílené dotazem; příklady platných jazykových tagů jsou: `en-US` nebo
        `en-GB`, `cs` nebo `cs-CZ`, `de` nebo `de-AT`, `de-CH`, `fr` nebo `fr-CA` atd.
    </dd>
</dl>

Pokud pracujete s evitaDB v Javě, můžete použít <LS to="j">[`Locale`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Locale.html)</LS><LS to="c">[`CultureInfo`](https://learn.microsoft.com/en-us/dotnet/api/system.globalization.cultureinfo)</LS>
místo jazykového tagu. Toto je přirozený způsob práce s daty specifickými pro lokalitu na dané platformě.

<Note type="question">

<NoteTitle toggles="true">

##### Co je to jazykový tag?
</NoteTitle>

Jazykový tag, známý také jako locale nebo identifikátor jazyka, je standardizovaný formát používaný k reprezentaci konkrétního
jazyka nebo locale v počítačových systémech a softwaru. Umožňuje identifikovat a rozlišovat jazyky,
dialekty a regionální varianty.

Nejčastěji používaným formátem pro jazykové tagy je standard [BCP 47](https://www.rfc-editor.org/info/bcp47) (IETF Best
Current Practice 47). BCP 47 definuje syntaxi a soubor pravidel pro sestavování jazykových tagů.

Jazykový tag je obvykle sestaven kombinací subtágů, které reprezentují různé komponenty. Zde je
příklad rozkladu jazykového tagu: `en-US`.

1. **Primární jazykový subtag:** V uvedeném příkladu *en* označuje primární jazykový subtag, který určuje,
   že primárním jazykem je angličtina.

2. **Regionální subtag:** Regionální subtag je volitelný a označuje konkrétní region nebo zemi spojenou s
   jazykem. V příkladu *US* označuje Spojené státy americké.

Jazykové tagy mohou také obsahovat další subtagy pro specifikaci variant, jako je písmo, varianta a rozšíření,
což umožňuje ještě podrobnější identifikaci jazyka.

<LS to="g">

V GraphQL API pro snadnější použití převádíme všechny locale definované v uložených datech na enum, což zlepšuje doplňování kódu.
GraphQL však nepodporuje pomlčky v položkách enum, a proto místo nich používáme podtržítka. Jinak je syntaxe
stejná.

</LS>

</Note>

Pokud jakýkoli filtr v dotazu cílí na lokalizovaný atribut, musí být také zadán `entityLocaleEquals`,
jinak interpret dotazu vrátí chybu. Lokalizované atributy **musí** být identifikovány jak svým názvem,
tak jazykovým tagem, aby mohly být použity.

<Note type="warning">

Ve filtrační části dotazu je povoleno pouze jedno použití `entityLocaleEquals`. V současné době není možné
přepínat kontext mezi různými částmi filtru a sestavovat dotazy typu *najdi produkt, jehož název v `en-US`
je "screwdriver" nebo v `cs` je "šroubovák"*.

Také není možné vynechat specifikaci jazyka u lokalizovaného atributu a ptát se například: *najdi
produkt, jehož název v jakémkoli jazyce je "screwdriver"*.

Ačkoli je technicky možné implementovat podporu těchto úloh v evitaDB, jedná se o okrajové případy a
bylo potřeba řešit důležitější scénáře.

</Note>

Pro otestování dotazu specifického pro locale se zaměříme na kategorii *Vouchers for shareholders* v našem
[ukázkovém datasetu](../../get-started/query-our-dataset.md). Víme, že existují produkty, které mají pouze anglickou
(*en_US*) lokalizaci. Pro výběr produktů s anglickou lokalizací můžeme použít tento dotaz:

<SourceCodeTabs requires="/evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Výpis produktů s anglickou lokalizací](/documentation/user/en/query/filtering/examples/locale/locale.evitaql)

</SourceCodeTabs>

... a získáme seznam s jejich počtem.

<Note type="info">

<NoteTitle toggles="false">

##### Seznam všech produktů s anglickou lokalizací v kategorii
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Seznam všech produktů s anglickou lokalizací](/documentation/user/en/query/filtering/examples/locale/locale.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Seznam všech produktů s anglickou lokalizací](/documentation/user/en/query/filtering/examples/locale/locale.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Seznam všech produktů s anglickou lokalizací](/documentation/user/en/query/filtering/examples/locale/locale.rest.json.md)</MDInclude>

</LS>

Všimnete si, že výstup obsahuje dva sloupce: *code* a *name*. *code* není lokalizovaný atribut, zatímco
*name* ano. Názvy uvedené v odpovědi odpovídají anglické lokalizaci, která je součástí filtrační podmínky.

Pokud ve filtru použijete `entityLocaleEquals`, všechna vrácená lokalizovaná data (jak
[atributy](../../use/data-model.md#lokalizované-atributy), tak [asociovaná data](../../use/data-model.md#lokalizovaná-přidružená-data))
budou respektovat filtrovanou lokalizaci. Pokud potřebujete data pro jiné lokalizace, než je ta použitá ve filtrační podmínce,
můžete využít požadavek [`data-in-locale`](../requirements/fetching.md#data-v-lokalizacích).

</Note>

Ale když požádáme o produkty v české lokalizaci:

<SourceCodeTabs requires="/evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Výpis produktů s anglickou lokalizací](/documentation/user/en/query/filtering/examples/locale/locale_missing.evitaql)

</SourceCodeTabs>

... dotaz nevrátí žádný výsledek, i když víme, že v této kategorii produkty existují.

<Note type="info">

<NoteTitle toggles="true">

##### Seznam všech produktů s českou lokalizací v kategorii
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Seznam všech produktů s českou lokalizací](/documentation/user/en/query/filtering/examples/locale/locale_missing.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Seznam všech produktů s českou lokalizací](/documentation/user/en/query/filtering/examples/locale/locale_missing.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Seznam všech produktů s českou lokalizací](/documentation/user/en/query/filtering/examples/locale/locale_missing.rest.json.md)</MDInclude>

</LS>

</Note>