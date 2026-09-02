---
title: Cena
date: '9.6.2025'
perex: Tato kapitola se zabývá zpracováním cen s daní i bez daně pro B2C a B2B scénáře a také nastavením priorit výchozích ceníků pro zajištění konzistentních cen ve vašich dotazech.
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
translated: 'true'
commit: ecc9ddd4a929f8020bca123be8bf4b2ed9b635b7
---
## Typ ceny

```evitaql-syntax
priceType(
    argument:enum(WITH_TAX|WITHOUT_TAX)!
)
```

<dl>
    <dt>argument:enum(WITH_TAX|WITHOUT_TAX)!</dt>
    <dd>
        výběr typu ceny, který má být zohledněn při výpočtu prodejní ceny a
        filtrování nebo řazení podle ní
    </dd>
</dl>

Ve scénářích B2C jsou ceny obvykle zobrazovány včetně daně, aby zákazníci ihned viděli celkovou cenu nákupu,
což odpovídá maloobchodním standardům a předpisům. Pro segment B2B je naopak klíčové zobrazovat ceny bez daně,
protože to odpovídá jejich finančním procesům a umožňuje jim samostatně řešit odpočet DPH.

Proto je potřeba mít pod kontrolou, s jakým typem ceny v dotazech pracujeme, protože různé nastavení povede k odlišným
výsledkům. Požadavek [`priceType`](price.md#typ-ceny) nám toto umožňuje.

Požadavek <LS to="j,e,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/PriceType.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/PriceType.cs</SourceClass></LS>
určuje, jaký typ ceny bude použit při výpočtu prodejní ceny a při filtrování nebo řazení podle ní. Pokud tento
požadavek není specifikován, **výchozí je cena s daní**.

Abychom ukázali vliv tohoto požadavku, předpokládejme, že uživatel chce najít všechny produkty s prodejní cenou
mezi `€100` a `€105`. Následující dotaz to provede:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad dotazu pro filtrování produktů s cenou mezi `€100` a `€105`](/documentation/user/en/query/requirements/examples/price/price-type.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledky filtrované podle ceny mezi €100 a €105

</NoteTitle>

Výsledek obsahuje některé produkty, které můžete vidět v následující tabulce:

<LS to="e,j,c">

<MDInclude>[Výsledky filtrované podle ceny mezi €100 a €105](/documentation/user/en/query/requirements/examples/price/price-type.evitaql.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Výsledky filtrované podle ceny mezi €100 a €105](/documentation/user/en/query/requirements/examples/price/price-type.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Výsledky filtrované podle ceny mezi €100 a €105](/documentation/user/en/query/requirements/examples/price/price-type.rest.json.md)</MDInclude>

</LS>

</Note>

Pokud je však uživatel právnická osoba a může si od ceny odečíst DPH, pravděpodobně bude chtít najít všechny produkty v tomto rozmezí s cenou bez daně. K tomu je potřeba upravit dotaz a přidat požadavek `priceType`:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad dotazu pro filtrování produktů s cenou mezi `€100` a `€105` bez daně](/documentation/user/en/query/requirements/examples/price/price-type-without-tax.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Odlišné výsledky filtrované podle ceny mezi €100 a €105 bez daně

</NoteTitle>

A nyní výsledek obsahuje zcela jiné produkty (v ukázce je zobrazena cena s daní, abychom demonstrovali rozdíl – v běžném UI byste zvolili zobrazení ceny bez daně) s vhodnější cenou pro konkrétního uživatele:

<LS to="e,j,c">

<MDInclude>[Odlišné výsledky filtrované podle ceny mezi €100 a €105 bez daně](/documentation/user/en/query/requirements/examples/price/price-type-without-tax.evitaql.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.recordPage">[Odlišné výsledky filtrované podle ceny mezi €100 a €105 bez daně](/documentation/user/en/query/requirements/examples/price/price-type-without-tax.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="recordPage">[Odlišné výsledky filtrované podle ceny mezi €100 a €105 bez daně](/documentation/user/en/query/requirements/examples/price/price-type-without-tax.rest.json.md)</MDInclude>

</LS>

</Note>

## Výchozí doprovodné ceníky

```evitaql-syntax
defaultAccompanyingPriceLists(
    argument:string+
)
```

<dl>
    <dt>argument:string+</dt>
    <dd>
        Povinné určení jednoho nebo více názvů ceníků v pořadí od nejpreferovanějšího po nejméně preferovaný.
    </dd>
</dl>

Požadavek <LS to="j,e,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/DefaultAccompanyingPrice.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/DefaultAccompanyingPrice.cs</SourceClass></LS>
definuje prioritizovaný seznam názvů ceníků, které budou použity pro všechny požadavky [`accompanyingPriceContent`](fetching.md#doprovodný-obsah-ceny),
které si neurčí vlastní sekvenci ceníků. To je užitečné, pokud chcete nastavit výchozí pravidlo na nejvyšší úrovni dotazu,
abyste nemuseli opakovat stejnou sekvenci ceníků v každém [`accompanyingPriceContent`](fetching.md#doprovodný-obsah-ceny) vnořeném v kontejnerech [`entityFetch`](fetching.md#načtení-entity).