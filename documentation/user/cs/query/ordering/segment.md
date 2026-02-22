---
title: Segmentace
perex: Segmentace umožňuje řadit různé části výsledků vyhledávání odlišným způsobem. Některé e-shopy preferují v základním zobrazení zobrazovat nejprve nové produkty, zatímco jiné mohou využívat top doporučení pro uživatele na základě jejich preferencí (nejlevnější, nejkvalitnější, zlatý střed apod.). Právě zde přichází ke slovu segmentace.
date: '15.10.2024'
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
commit: cef96d8320d36c91c100c5dfc9c45020b5a7ad0d
translated: true
---
Bez podpory segmentace by klient musel spouštět více dotazů a výsledky slučovat na straně klienta, přičemž každý další dotaz by vylučoval výsledky předchozího. To je nejen neefektivní, ale také náchylné k chybám. Díky segmentaci mohou vývojáři jednoduše definovat pravidla pro řazení různých segmentů, omezit velikost každého segmentu a nechat těžkou práci na serveru.

### Segmenty

```evitaql-syntax
segments(
    requireConstraint:segment+   
)
```

<dl>
    <dt>requireConstraint:segment+</dt>
    <dd>
        jeden nebo více omezení, která určují pravidla pro každý segment
    </dd>
</dl>

Kontejner omezení segments vám umožňuje definovat více segmentů s omezenou velikostí a různými pravidly řazení.
Entity uvedené v předchozích segmentech jsou vyloučeny z následujících segmentů. Každý segment uvádí všechny entity, které poskytují data pro konkrétní řazení (viz Poznámka pro více detailů), dokud není dosaženo limitu. Pořadí segmentů je důležité, protože určuje pořadí segmentů ve výsledném výstupu.

<Note type="info">

<NoteTitle toggles="false">

##### Co znamená „poskytovat data pro konkrétní řazení“?

</NoteTitle>

Pokud řadíte podle dat, která nemusí být přítomna u všech entit, například atribut, entity, které tento atribut pro řazení nemají, jsou ze segmentu automaticky vyloučeny. Jedná se o implicitní filtrování pro každý ze segmentů, které není nutné explicitně uvádět v omezení [`segment`](#segment).

</Note>

Každý segment vám umožňuje definovat další filtrovací omezení, které je aplikováno na výsledek dotazu, aby vybral pouze podmnožinu entit, které mohou být zahrnuty do daného segmentu.

Podívejme se na příklad. Řekněme, že chceme nejprve zobrazit dva nově přidané produkty, poté nejprodávanější produkt s cenou nad 500 €, poté nejprodávanější produkt s cenou pod 500 €, poté zbytek produktů, které jsou aktuálně skladem, a nakonec zbytek produktů, které musíme objednat od našich dodavatelů. Segmenty můžeme definovat následovně:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Segmentované řazení v praxi](/documentation/user/en/query/ordering/examples/segment/segments.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek segmentovaného řazení v praxi

</NoteTitle>

Jak můžete vidět, první dvě pozice zaujímají nově přidané produkty. Třetí pozici zaujímá nejprodávanější produkt s cenou nad 500 €. Čtvrtou pozici zaujímá nejprodávanější produkt s cenou pod 500 €. Pátou a další pozice zaujímají produkty, které jsou aktuálně skladem. Pokud dojdou produkty skladem, zbývající pozice zaujímají produkty, které musíme objednat od našich dodavatelů.

<LS to="e,j,c">

<MDInclude>[Výsledek segmentovaného řazení v praxi](/documentation/user/en/query/ordering/examples/segment/segments.evitaql.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude>[Výsledek segmentovaného řazení v praxi](/documentation/user/en/query/ordering/examples/segment/segments.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude>[Výsledek segmentovaného řazení v praxi](/documentation/user/en/query/ordering/examples/segment/segments.rest.json.md)</MDInclude>

</LS>

</Note>

### Segment

```evitaql-syntax
segment(
    filterConstraint:entityHaving?,
    orderConstraint:orderBy,
    requireConstraint:limit?
)
```

<dl>
    <dt>filterConstraint:entityHaving?</dt>
    <dd>
        volitelné filtrovací omezení, které vezme výsledek dotazu a aplikuje na něj další filtrování, aby vybral pouze entity, které mohou být zahrnuty do tohoto konkrétního segmentu
    </dd>
    <dt>orderConstraint:orderBy</dt>
    <dd>
        omezení řazení, které určuje, jak mají být entity v tomto segmentu seřazeny
    </dd>
    <dt>requireConstraint:limit?</dt>
    <dd>
        volitelné omezení, které určuje maximální počet entit, které mají být do tohoto segmentu zahrnuty
    </dd>
</dl>

Požadavek `segment` určuje jedno pravidlo pro segmentaci výsledku dotazu. Podrobné použití je popsáno v kapitole [omezení segments](#segmenty).