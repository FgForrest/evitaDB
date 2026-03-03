---
title: Filtrování podle lokalizace
perex: Mnoho e-commerce aplikací funguje v různých regionech a spoléhá na lokalizovaná data. Zatímco produktové štítky a popisy jsou jasnými příklady, existuje také několik číselných hodnot, které musí být specifické pro každou lokalitu kvůli rozdílu mezi metrickým systémem a imperiálními jednotkami. Proto evitaDB nabízí prvotřídní podporu lokalizace ve svých datových strukturách a dotazovacím jazyce.
date: '27.5.2023'
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
commit: cef96d8320d36c91c100c5dfc9c45020b5a7ad0d
translated: true
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

##### Co je jazykový tag?
</NoteTitle>

Jazykový tag, známý také jako locale nebo jazykový identifikátor, je standardizovaný formát používaný k reprezentaci konkrétního
jazyka nebo lokality v počítačových systémech a softwaru. Poskytuje způsob, jak identifikovat a rozlišovat jazyky,
dialekty a regionální varianty.

Nejčastěji používaným formátem pro jazykové tagy je standard [BCP 47](https://www.rfc-editor.org/info/bcp47) (IETF Best
Current Practice 47). BCP 47 definuje syntaxi a sadu pravidel pro vytváření jazykových tagů.

Jazykový tag je obvykle sestaven kombinací subtágů, které reprezentují různé komponenty. Zde je
příklad rozkladu jazykového tagu: `en-US`.

1. **Primární jazykový subtag:** V uvedeném příkladu *en* představuje primární jazykový subtag, který označuje
   angličtinu jako primární jazyk.

2. **Regionální subtag:** Regionální subtag je volitelný a představuje konkrétní region nebo zemi spojenou s
   jazykem. V příkladu *US* označuje Spojené státy.

Jazykové tagy mohou také obsahovat další subtagy pro specifikaci variant, jako je skript, varianta a rozšíření,
což umožňuje detailnější identifikaci jazyka.

<LS to="g">

V GraphQL API pro snadnější použití převádíme všechny lokality definované v uložených datech na enum pro lepší doplňování kódu.
GraphQL však nepodporuje pomlčky v enum položkách, a proto používáme místo nich podtržítka. Jinak je syntaxe
stejná.

</LS>

</Note>

Pokud jakékoliv omezení filtru v dotazu cílí na lokalizovaný atribut, musí být také zadán `entityLocaleEquals`,
jinak interpret dotazu vrátí chybu. Lokalizované atributy **musí** být identifikovány jak svým názvem,
tak jazykovým tagem, aby mohly být použity.

<Note type="warning">

Ve filtrační části dotazu je povoleno pouze jedno použití `entityLocaleEquals`. V současnosti není možné
přepínat kontext mezi různými částmi filtru a sestavovat dotazy jako *najdi produkt, jehož název v `en-US`
je "screwdriver" nebo v `cs` je "šroubovák"*.

Také není možné vynechat specifikaci jazyka pro lokalizovaný atribut a ptát se například: *najdi
produkt, jehož název v jakémkoli jazyce je "screwdriver"*.

Ačkoliv je technicky možné implementovat podporu těchto úloh v evitaDB, jedná se o okrajové případy a bylo potřeba řešit důležitější scénáře.

</Note>

Pro otestování dotazu specifického pro lokalitu se musíme zaměřit na kategorii *Vouchers for shareholders* v našem
[ukázkovém datasetu](../../get-started/query-our-dataset.md). Víme, že existují produkty, které mají pouze anglickou
(*en_US*) lokalizaci. Pro výběr produktů s anglickou lokalizací můžeme použít tento dotaz:

<SourceCodeTabs requires="/evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

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
*name* ano. Názvy uvedené v odpovědi odrážejí anglickou lokalitu, která je součástí filtračního omezení.

Pokud použijete `entityLocaleEquals` ve svém filtru, všechna vrácená lokalizovaná data (jak
[atributy](../../use/data-model.md#lokalizované-atributy), tak [asociovaná data](../../use/data-model.md#lokalizovaná-přidružená-data))
budou respektovat filtrovanou lokalitu. Pokud potřebujete data pro jiné lokality než tu použitou ve filtračním omezení,
můžete použít požadavek [`data-in-locale`](../requirements/fetching.md#data-v-lokalizacích).

</Note>

Ale když požádáme o produkty v české lokalitě:

<SourceCodeTabs requires="/evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Výpis produktů s anglickou lokalizací](/documentation/user/en/query/filtering/examples/locale/locale_missing.evitaql)

</SourceCodeTabs>

... dotaz nevrátí žádný výsledek, i když víme, že v této kategorii produkty jsou.

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