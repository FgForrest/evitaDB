---
title: Řazení podle ceny
date: '2.7.2023'
perex: Řazení podle prodejní ceny je jedním ze základních požadavků pro e-commerce aplikace. Omezení řazení podle ceny umožňuje třídit výstupní entity podle jejich prodejní ceny vzestupně nebo sestupně.
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
commit: cef96d8320d36c91c100c5dfc9c45020b5a7ad0d
---
## Přirozená cena

```evitaql-syntax
priceNatural(
    argument:enum(ASC|DESC)
)
```

<dl>
    <dt>argument:enum(ASC|DESC)</dt>
    <dd>
        směr řazení (vzestupně nebo sestupně), **výchozí hodnota** je `ASC`
    </dd>
</dl>

Omezení <LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/order/PriceNatural.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Order/PriceNatural.cs</SourceClass></LS>
umožňuje řadit výstupní entity podle jejich [prodejní ceny](../../deep-dive/price-for-sale-calculation.md)
v jejich přirozeném číselném pořadí. Vyžaduje pouze směr řazení a cenová omezení v sekci `filterBy`
dotazu. Cenová varianta (s nebo bez daně) je určena požadavkem [`priceType`](../requirements/price.md#typ-ceny)
dotazu (ve výchozím nastavení se používá cena s daní).

Pro seřazení produktů podle jejich prodejní ceny (aktuálně se zohledňuje pouze cenový seznam `basic` a měna `EUR`) můžeme použít
následující dotaz:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Seznam produktů seřazených podle prodejní ceny](/documentation/user/en/query/ordering/examples/price/price-natural.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Seznam produktů seřazených podle prodejní ceny
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Seznam produktů seřazených podle prodejní ceny](/documentation/user/en/query/ordering/examples/price/price-natural.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Seznam produktů seřazených podle prodejní ceny](/documentation/user/en/query/ordering/examples/price/price-natural.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Seznam produktů seřazených podle prodejní ceny](/documentation/user/en/query/ordering/examples/price/price-natural.rest.json.md)</MDInclude>

</LS>

</Note>

## Sleva z ceny

```evitaql-syntax
priceDiscount(
    argument:enum(ASC|DESC)?
    argument:string+
)
```

<dl>
    <dt>argument:enum(ASC|DESC)?</dt>
    <dd>
        směr řazení (vzestupně nebo sestupně), **výchozí hodnota** je `DESC`
    </dd>
    <dt>argument:string+</dt>
    <dd>
        Povinná specifikace jednoho nebo více názvů cenových seznamů v pořadí priority od nejpreferovanějšího po nejméně
        preferovaný, ze kterých má být referenční cena zohledněna pro výpočet slevy.
    </dd>
</dl>

Omezení <LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/order/PriceDiscount.java</SourceClass></LS>
umožňuje řadit výstupní entity podle rozdílu mezi jejich [prodejní cenou](../../deep-dive/price-for-sale-calculation.md)
a jakoukoli vypočtenou alternativou v jejich přirozeném číselném pořadí. Vyžaduje definici prioritní sady
názvů cenových seznamů, které určují pravidla pro výpočet alternativní ceny, a volitelně směr řazení
(výchozí je `DESC`). Cenová varianta (s nebo bez daně) je určena požadavkem [`priceType`](../requirements/price.md#typ-ceny)
dotazu (ve výchozím nastavení se používá cena s daní).

<Note type="info">

<NoteTitle toggles="true">

##### Jak se sleva počítá?

</NoteTitle>

Sleva se počítá z rozdílu mezi prodejní cenou a referenční cenou určenou cenami
nastavenými na entitě v pořadí cenových seznamů uvedených v omezení. Výpočetní algoritmus je stejný
jako pro výpočet [prodejní ceny](/documentation/user/en/deep-dive/price-for-sale-calculation.md) – najde první
dostupnou cenu v pořadí cenových seznamů a použije ji jako referenční cenu.

Pokud referenční cena není nalezena, entita / produkt není tímto omezením řaditelný a bude
seřazen podle dalšího řadicího omezení v dotazu (nebo podle svého primárního klíče vzestupně na konci seznamu).

Existují však speciální úpravy pro výpočet slevy. Pokud má produkt strategii zpracování vnitřních záznamů ceny `LOWEST_PRICE` nebo `SUM`,
referenční cena vychází ze záznamů vnitřních záznamů použitých k výběru ceny pro prodej.

###### Strategie nejnižší ceny

Strategie nejnižší ceny se obvykle používá pro virtuální produkty, které reprezentují více podobných produktů. Nakonec je pro prodej vybrán pouze jeden z produktů. Pokud dotazujeme produkty podle měny, sady cenových seznamů, časového razítka platnosti,
virtuální produkt se strategií LOWEST_PRICE se projeví jako subprodukt (varianta) s nejnižší cenou.
Při výpočtu slevy musíme použít cenu z jiných cenových seznamů, ale pouze cenu, která se vztahuje ke stejnému variantu produktu jako cena pro prodej.

Pokud dále dotaz zúžíme pomocí [omezení ceny mezi](../filtering/price.md#cena-v-rozmezí),
reprezentativní varianta bude ta s nejnižší cenou ve vybraném cenovém rozmezí. V tomto případě
musí být referenční cena také vypočtena z různých sad cenových seznamů pro stejnou variantu produktu.

###### Strategie součtu cen

Strategie součtu cen se typicky používá pro produkty, které se skládají z několika subproduktů (částí). Jejich prodejní cena se
počítá jako součet cen subproduktů. Pokud určitý subprodukt nemá cenu ve vybraných
cenových seznamech, je ze součtu vyloučen. Při výpočtu referenční ceny pro tento produkt musíme také vynechat
ceny těchto subproduktů, i když by pro ně byla referenční cena dostupná. Jinak by vypočtený
součet nebyl konzistentní s prodejní cenou.

</Note>

<Note type="warning">

<NoteTitle toggles="true">

##### Musí být ceny indexovány pro výpočet slevy?

</NoteTitle>

Ano, ceny musí být v databázi indexovány, aby bylo možné produkty řadit podle výše slevy. Neindexované ceny jsou
přístupné pouze při načtení těla entity z disku, což by bylo velmi neefektivní pro řazení velkých datových sad.
Proto i „neindexované“ ceny musí být indexovány a uchovávány v paměťových indexech, aby bylo možné efektivně vypočítat výši slevy.

</Note>

Pro seřazení produktů podle výše jejich slevy (tj. pro porovnání, jakou slevu získáte s `b2b-basic-price` oproti
cenovému seznamu `basic` a měně `EUR`) můžeme použít následující dotaz:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Seznam produktů s nejvyšší slevou na začátku](/documentation/user/en/query/ordering/examples/price/price-discount.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Seznam produktů s nejvyšší slevou na začátku
</NoteTitle>

<LS to="e,j">

<MDInclude>[Seznam produktů s nejvyšší slevou na začátku](/documentation/user/en/query/ordering/examples/price/price-discount.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Seznam produktů s nejvyšší slevou na začátku](/documentation/user/en/query/ordering/examples/price/price-discount.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Seznam produktů s nejvyšší slevou na začátku](/documentation/user/en/query/ordering/examples/price/price-discount.rest.json.md)</MDInclude>

</LS>

</Note>