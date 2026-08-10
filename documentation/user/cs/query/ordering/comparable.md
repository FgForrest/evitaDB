---
title: Porovnatelné řazení
perex: Přirozené řazení na porovnatelných datových typech je nejběžnějším typem řazení. Umožňuje třídit entity podle jejich atributů v přirozeném pořadí (číselném, abecedním, časovém atd.).
date: '25.6.2023'
author: Ing. Jan Novotný
proofreading: needed
preferredLang: evitaql
translated: 'true'
commit: ecc9ddd4a929f8020bca123be8bf4b2ed9b635b7
---
## Atribut natural

```evitaql-syntax
attributeNatural(
    argument:string!
    argument:enum(ASC|DESC)
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        povinný název atributu, podle kterého lze řadit
    </dd>
    <dt>argument:enum(ASC|DESC)</dt>
    <dd>
        směr řazení (vzestupně nebo sestupně), **výchozí hodnota** je `ASC`
    </dd>
</dl>

Tato podmínka umožňuje řadit výstupní entity podle jejich atributů v jejich přirozeném pořadí (číselném, abecedním,
časovém). Vyžaduje specifikaci jednoho [atributu](../../use/data-model.md#atributy-unikátní-filtrovatelné-řaditelné-lokalizované)
a směru řazení.

Pro seřazení produktů podle počtu jejich prodejů (nejprodávanější produkty jako první) můžeme použít následující dotaz:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Seznam produktů seřazených podle číselného atributu](/documentation/user/en/query/ordering/examples/comparable/attribute-natural-non-localized.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Seznam produktů seřazených podle číselného atributu
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Seznam produktů seřazených podle číselného atributu](/documentation/user/en/query/ordering/examples/comparable/attribute-natural-non-localized.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Seznam produktů seřazených podle číselného atributu](/documentation/user/en/query/ordering/examples/comparable/attribute-natural-non-localized.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Seznam produktů seřazených podle číselného atributu](/documentation/user/en/query/ordering/examples/comparable/attribute-natural-non-localized.rest.json.md)</MDInclude>

</LS>

</Note>

Pokud chcete řadit produkty podle jejich názvu, který je lokalizovaným atributem, je třeba ve filtru dotazu specifikovat
omezení `entityLocaleEquals`:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Seznam produktů seřazených podle lokalizovaného atributu](/documentation/user/en/query/ordering/examples/comparable/attribute-natural-localized.evitaql)
</SourceCodeTabs>

Správný <LS to="e,j,r,g">[collator](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/text/Collator.html)</LS><LS to="c">collator na straně databáze</LS> je použit
pro řazení lokalizovaného řetězce atributu tak, aby pořadí odpovídalo národním zvyklostem daného jazyka.

<Note type="info">

<NoteTitle toggles="true">

##### Seznam produktů seřazených podle lokalizovaného atributu
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Seznam produktů seřazených podle lokalizovaného atributu](/documentation/user/en/query/ordering/examples/comparable/attribute-natural-localized.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Seznam produktů seřazených podle lokalizovaného atributu](/documentation/user/en/query/ordering/examples/comparable/attribute-natural-localized.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Seznam produktů seřazených podle lokalizovaného atributu](/documentation/user/en/query/ordering/examples/comparable/attribute-natural-localized.rest.json.md)</MDInclude>

</LS>

</Note>

Mechanismus řazení v evitaDB se poněkud liší od toho, na co můžete být zvyklí. Pokud řadíte entity podle dvou
atributů v klauzuli `orderBy` dotazu, evitaDB je nejprve seřadí podle prvního atributu (pokud je přítomen) a poté
podle druhého (ale pouze ty, u kterých první atribut chybí). Pokud mají dvě entity stejnou hodnotu prvního
atributu, nejsou dále řazeny podle druhého atributu, ale podle primárního klíče (vzestupně).

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Seznam produktů seřazených podle více atributů](/documentation/user/en/query/ordering/examples/comparable/attribute-natural-multiple.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Seznam produktů seřazených podle více atributů
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Seznam produktů seřazených podle více atributů](/documentation/user/en/query/ordering/examples/comparable/attribute-natural-multiple.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Seznam produktů seřazených podle více atributů](/documentation/user/en/query/ordering/examples/comparable/attribute-natural-multiple.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Seznam produktů seřazených podle více atributů](/documentation/user/en/query/ordering/examples/comparable/attribute-natural-multiple.rest.json.md)</MDInclude>

</LS>

</Note>

Pokud chceme využít rychlé „předřazené“ indexy, není jiná možnost, protože sekundární pořadí by nebylo známo až do
doby vykonání dotazu. Pokud chcete řadit podle více atributů konvenčním způsobem, je potřeba předem definovat
[compound atribut pro řazení](../../use/schema.md#složené-atributy-pro-řazení) a použít jeho název místo
výchozího názvu atributu. Tento compound atribut pokrývá více atributů a připraví speciální index pro řazení právě
této kombinace atributů, přičemž respektuje předdefinované pořadí a chování hodnot NULL.
V dotazu pak můžete použít název compound atributu místo výchozího názvu atributu a dosáhnout očekávaných výsledků.

## Přirozené řazení podle primárního klíče

```evitaql-syntax
primaryKeyNatural(
    argument:enum(ASC|DESC)
)
```

<dl>
    <dt>argument:enum(ASC|DESC)</dt>
    <dd>
        směr řazení (vzestupně nebo sestupně), **výchozí hodnota** je `ASC`
    </dd>
</dl>

Pokud v dotazu není uvedeno žádné omezení řazení, entity jsou seřazeny podle svého primárního klíče vzestupně.
Pokud je chcete řadit sestupně, můžete použít omezení `primaryKeyNatural` s argumentem `DESC`.
Ačkoliv omezení přijímá také argument `ASC`, nemá smysl jej používat, protože toto je výchozí
chování řazení.

Pro seřazení produktů podle jejich primárního klíče sestupně můžeme použít následující dotaz:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Seznam produktů seřazených podle primárního klíče sestupně](/documentation/user/en/query/ordering/examples/comparable/primary-key-natural.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Seznam produktů seřazených podle primárního klíče sestupně
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Seznam produktů seřazených podle primárního klíče sestupně](/documentation/user/en/query/ordering/examples/comparable/primary-key-natural.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Seznam produktů seřazených podle primárního klíče sestupně](/documentation/user/en/query/ordering/examples/comparable/primary-key-natural.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Seznam produktů seřazených podle primárního klíče sestupně](/documentation/user/en/query/ordering/examples/comparable/primary-key-natural.rest.json.md)</MDInclude>

</LS>

</Note>