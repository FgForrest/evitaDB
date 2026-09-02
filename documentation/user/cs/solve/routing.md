---
title: Směrování
perex: Směrování v e-commerce katalozích je složitý problém. Hraje klíčovou roli v SEO a uživatelské zkušenosti a URL adresy jsou obvykle odvozeny z názvů entit bez jakýchkoli dalších informací s významnou strukturou. Ať už se nám jako vývojářům líbí nebo ne, obchodní požadavky určují pravidla a my je musíme dodržovat. V tomto článku se podíváme na některé přístupy k řešení problémů se směrováním v e-commerce katalozích.
date: '4.2.2024'
author: Ing. Jan Novotný
proofreading: done
translated: 'true'
commit: ecc9ddd4a929f8020bca123be8bf4b2ed9b635b7
---
Očekáváme, že entity, které jsou dosažitelné přes URL, budou mít atribut typu [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html), který obsahuje buď úplnou absolutní URL, nebo relativní. Rozhodnutí, zda použít absolutní nebo relativní URL, má své důsledky a mělo by být dobře promyšleno. Ukládání absolutních URL do databáze obvykle není dobrý nápad, protože to ztěžuje použití stejného záznamu v různých prostředích (produkce/testování/vývoj), která mají různá doménová jména. Obecně doporučujeme ukládat do databáze relativní URL bez protokolu a domény a úplné absolutní URL sestavovat až v aplikaci.

## Jedinečnost URL

Podle [URL](https://en.wikipedia.org/wiki/URL) standardu je URL jedinečný identifikátor zdroje. Proto by měl být v databázi označen jako jedinečný. Jelikož pracujeme s více typy entit, pravděpodobně potřebujeme definovat atribut URL platný v rámci celého katalogu, který bude použit u každé entity. Tento atribut pak musí být označen buď jako `UNIQUE_WITHIN_CATALOG`, nebo `UNIQUE_WITHIN_CATALOG_LOCALE`, v závislosti na tom, zda je katalog vícejazyčný a zda je součástí URL také lokalizace. V praxi jsme se setkali s oběma těmito scénáři:

1. lokalizace je zakódována v relativní části URL, např. `/en/product-name` nebo `/cs/product-name`
2. lokalizace je zakódována v doménové části URL, např. `https://example.com/product-name` nebo `https://example.cz/product-name`

V prvním scénáři můžeme použít typ jedinečnosti `UNIQUE_WITHIN_CATALOG`, protože relativní URL jsou jedinečné napříč všemi lokalizacemi. Ve druhém scénáři musíme použít typ jedinečnosti `UNIQUE_WITHIN_CATALOG_LOCALE`, protože můžeme mít stejnou relativní URL pro různé lokalizace, ale při vyhledávání cílové entity podle URL musíme také určit lokalizaci odvozenou z názvu domény.

Pokud je atribut označen jako jedinečný, můžeme jednoduše vyhledat vlastnickou entitu podle hodnoty atributu. Následující dotaz vrátí entitu podle kódu, což je jednoduchý jedinečný atribut entity (nikoli v rámci celého katalogu):

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání produktu podle jedinečného atributu](/documentation/user/en/solve/examples/routing/get-by-unique-attribute.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek dotazu na entitu podle jedinečného atributu
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Výsledek pro jedinečný atribut](/documentation/user/en/solve/examples/routing/get-by-unique-attribute.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Výsledek pro jedinečný atribut](/documentation/user/en/solve/examples/routing/get-by-unique-attribute.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Výsledek pro jedinečný atribut](/documentation/user/en/solve/examples/routing/get-by-unique-attribute.rest.json.md)</MDInclude>

</LS>

</Note>

Jak můžete vidět, pro získání entity podle kódu musíme specifikovat název kolekce. Protože je však URL jedinečná v rámci celého katalogu, můžeme entitu vyhledat bez zadání názvu kolekce:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly ignoreTest>

[Získání produktu podle globálně jedinečného atributu](/documentation/user/en/solve/examples/routing/get-by-globally-unique-attribute.evitaql)

</SourceCodeTabs>

<Note type="warning">

Tento dotaz selže na demo datasetu evitaDB, protože URL je označena jako `UNIQUE_WITHIN_CATALOG_LOCALE`, což vyžaduje při vyhledávání entity podle URL také specifikaci lokalizace. Tento dotaz je však správný, pokud váš dataset používá místo toho `UNIQUE_WITHIN_CATALOG`. Viz následující příklad.

</Note>

Pokud je URL jedinečná pouze v rámci lokalizace, je potřeba také zadat <LS to="e,j,c">omezení `entityLocaleEquals`</LS><LS to="g,r">parametr `locale`</LS>:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání produktu podle globálně jedinečného lokalizovaného atributu](/documentation/user/en/solve/examples/routing/get-by-globally-unique-locale-specific-attribute.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek dotazu na entitu podle globálně jedinečného lokalizovaného atributu
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Výsledek pro lokalizovaný globálně jedinečný atribut](/documentation/user/en/solve/examples/routing/get-by-globally-unique-locale-specific-attribute.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Výsledek pro lokalizovaný globálně jedinečný atribut](/documentation/user/en/solve/examples/routing/get-by-globally-unique-locale-specific-attribute.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Výsledek pro lokalizovaný globálně jedinečný atribut](/documentation/user/en/solve/examples/routing/get-by-globally-unique-locale-specific-attribute.rest.json.md)</MDInclude>

</LS>

</Note>

## Načítání neznámých entit podle URL

Další problém souvisí s tím, že před načtením entity z databáze neznáme její typ, a tedy nevíme, jaká data (atributy/přidružená data atd.) máme načíst. V různých protokolech evitaDB máme různé možnosti.

<LS to="e,j,c">

V čistém evitaQL můžete buď použít "zástupný znak" pro načtení všech dostupných dat, nebo můžete přesně určit,
která data (atributy / přidružená data atd.) chcete načíst. Při dotazování na entitu podle globálně jedinečného
atributu parser dotazu neověřuje existenci atributu ve schématu a vrátí pouze ta data,
která pro danou entitu najde.

<Note type="warning">

Můžete dokonce zadat atribut, který nikde neexistuje, a evitaDB si nebude stěžovat. Buďte tedy opatrní,
protože bezpečnostní síť validátoru dotazu u tohoto typu dotazu není k dispozici.

</Note>

Pro demonstraci chování takového dotazu si definujme dotaz, který kombinuje jedinečná data z kolekcí `Product` a
`Category` do jednoho dotazu, který nemusí dávat smysl pro žádnou z kolekcí samostatně, ale úspěšně se provede
pro dotaz na globálně jedinečný atribut:

</LS>
<LS to="g">

V GraphQL neexistuje "zástupný znak" pro načtení všech dostupných dat, na druhou stranu však můžete přesně určit,
jaká data (atributy/přidružená data atd.) chcete pro každý typ entity načíst zvlášť pomocí
[inline fragmentů](https://graphql.org/learn/queries/#inline-fragments).

Pro demonstraci chování takového dotazu si definujme dotaz, který kombinuje jedinečná data z kolekcí `Product` a
`Category` do jednoho dotazu, aby pro každý typ entity vrátil odlišná data:

</LS>
<LS to="r">

V REST můžete buď použít "zástupný znak" pro načtení všech dostupných dat, nebo použít "zástupný znak"
pro každou část entity (atributy/přidružená data atd.), kterou chcete načíst.
V tomto typu dotazu však aktuálně nemůžete určit jednotlivá data, která mají být načtena v REST. Typicky byste
museli provést další dotaz, jakmile znáte typ entity, abyste získali detailní data.

Pro demonstraci chování takového dotazu si definujme dotaz, který vyžaduje všechna data z kolekcí `Product` a
`Category`, ale vrací odlišná data pro každý typ entity:

</LS>

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Retrieve product with data by globally unique locale specific attribute](/documentation/user/en/solve/examples/routing/get-product-with-data.evitaql)
</SourceCodeTabs>

<LS to="e,j,c">

Všimněte si, že dotaz obsahuje odkaz na atribut `level`, který určitě není definován ve schématu entity `Product`.
Tento dotaz by selhal, pokud bychom zadali název kolekce, ale protože jej neuvádíme, je název akceptován a
v výsledku se pouze nevrátí. Totéž platí pro požadavek `hierarchyContent`, který nedává smysl pro entitu
`Product`, protože není hierarchická. Podívejte se na výsledek dotazu:

</LS>
<LS to="g">

Tento dotaz definuje, že pokud URL patří `Product`, vrátí atributy `code`, `available` a `brandCode`.
Pokud URL patří `Category`, vrátí atribut `level`. Tímto způsobem můžete mít zcela odlišné
datové struktury pro každý typ entity a přesto získat správná data pro neznámou entitu, i když to vyžaduje více práce.

<Note type="info">

<NoteTitle toggles="true">

##### Zjednodušení výčtu fragmentů v reálných případech použití
</NoteTitle>

Obvykle nebudete muset definovat alternativní fragmenty pro všechny vaše typy entit. Vezměme si například atribut `url`,
který se typicky používá pouze u několika typů entit, jako je `Product`, `Category` nebo `Brand`. S touto znalostí
stačí definovat alternativní fragmenty pouze pro tyto 3 typy entit.

I když to nemusí přímo platit pro vaši aplikaci, zkuste najít vzor ve vašich datech a využijte ho ke zjednodušení těchto
dotazů.

</Note>

GraphQL server pak automaticky vybere správný fragment podle typu entity, podívejte se na výsledek dotazu:

</LS>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek dotazu na produkt podle globálně jedinečného atributu s načtením dat
</NoteTitle>

<LS to="e,j,c">
<MDInclude>[Výsledek dotazu na produkt podle globálně jedinečného atributu s načtením dat](/documentation/user/en/solve/examples/routing/get-product-with-data.evitaql.md)</MDInclude>
</LS>
<LS to="g">
<MDInclude>[Výsledek dotazu na produkt podle globálně jedinečného atributu s načtením dat](/documentation/user/en/solve/examples/routing/get-product-with-data.graphql.json.md)</MDInclude>
</LS>
<LS to="r">
<MDInclude>[Výsledek dotazu na produkt podle globálně jedinečného atributu s načtením dat](/documentation/user/en/solve/examples/routing/get-product-with-data.rest.json.md)</MDInclude>
</LS>

</Note>

Odpověď obsahuje všechna data, která odpovídají schématu entity `Product`, a zbytek je jednoduše ignorován.
Nyní se podívejme na stejný dotaz, ale pro URL entity `Category`:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání kategorie s daty podle globálně jedinečného lokalizovaného atributu](/documentation/user/en/solve/examples/routing/get-category-with-data.evitaql)
</SourceCodeTabs>

Můžete si všimnout, že ve výsledku je informace o atributu `level` a informace o `parent`, které
nedávají smysl pro entitu `product`, ale dávají smysl pro entitu `category`:

<Note type="info">

<NoteTitle toggles="true">

##### Výsledek dotazu na produkt podle globálně jedinečného atributu s načtením dat
</NoteTitle>

<LS to="e,j,c">
<MDInclude>[Výsledek dotazu na produkt podle globálně jedinečného atributu s načtením dat](/documentation/user/en/solve/examples/routing/get-category-with-data.evitaql.md)</MDInclude>
</LS>
<LS to="g">
<MDInclude>[Výsledek dotazu na produkt podle globálně jedinečného atributu s načtením dat](/documentation/user/en/solve/examples/routing/get-category-with-data.graphql.json.md)</MDInclude>
</LS>
<LS to="r">
<MDInclude>[Výsledek dotazu na produkt podle globálně jedinečného atributu s načtením dat](/documentation/user/en/solve/examples/routing/get-category-with-data.rest.json.md)</MDInclude>
</LS>

</Note>

<LS to="g">

V některých případech možná budete chtít načíst pouze data, která jsou společná pro všechny typy entit, například primární klíč, typ nebo společné atributy.
V takovém případě nemusíte definovat alternativní fragmenty pro každý typ entity;
[můžete použít pole přímo na generickém objektu entity](../use/api/query-data.md#getentity-dotaz).

</LS>

<LS to="e,j,c,r">

Koncepce evitaDB se snaží minimalizovat počet klient-server požadavků, ale v tomto případě jsou možnosti omezené
a pravděpodobně budete potřebovat další dotaz, pokud znáte typ entity a chcete získat detailní data.

</LS>