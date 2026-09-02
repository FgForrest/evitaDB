---
title: Zobrazit nabídku kategorií
perex: Drtivá většina katalogů zobrazuje položky prostřednictvím hierarchicky uspořádané nabídky kategorií různých typů, obvykle tak, že zobrazuje položky z kategorie, kterou uživatel vybral, a také ze všech podkategorií této kategorie. Protože se jedná o velmi běžný scénář, nabízí evitaDB kompletní sadu výrazných nástrojů pro tuto oblast a zároveň optimalizuje své indexy tak, aby dotazy do hierarchické struktury byly rychlejší než dotazy bez tohoto zaměření.
date: '4.2.2023'
author: Ing. Jan Novotný
proofreading: done
translated: 'true'
commit: ecc9ddd4a929f8020bca123be8bf4b2ed9b635b7
---
Menu je běžný způsob navigace v katalogu. Často se používá k zobrazení kategorií a podkategorií. Tato kapitola poskytuje příklady, jak vykreslit menu kategorií v typických scénářích. Menu lze vykreslit společně s vypsanými položkami v rámci jednoho požadavku. Neměli byste potřebovat samostatný požadavek na vykreslení menu, pokud jej nepředvyrábíte kvůli cachování (což je dobrá praxe u velkých variant menu, jako je [mega-menu](#mega-menu)). Všechny příklady v této kapitole budou dotazovat kolekci `Product` pro získání příslušného menu kategorií, ale nebudou vypisovat samotné produkty, jak by tomu bylo v reálném scénáři.

Ukázkové dotazy také neobsahují žádná filtrační omezení na produkty. V reálném scénáři byste obvykle chtěli produkty filtrovat podle určitých kritérií, jako je dostupnost, cena nebo jiné atributy. Tato omezení byste museli do dotazu přidat podle svých požadavků. Přítomnost takových omezení by také ovlivnila výsledky výpočtu menu, automaticky by vyřadila ty kategorie, které neobsahují produkty odpovídající omezením (pokud neumožníte, aby [`LEAVE_EMPTY`](../query/requirements/hierarchy.md#hierarchie-reference) kategorie zůstaly).

## Mega menu

Mega menu obvykle zobrazuje dvě až tři úrovně kategorií a podkategorií. Často se používá ve velkých e-commerce aplikacích. Vypadá například takto:

![Mega-menu example](../../en/query/requirements/assets/mega-menu.png "Mega-menu example")

Následující příklad ukazuje, jak získat všechna data potřebná pro vykreslení mega-menu v rámci jednoho dotazu:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání dat pro mega menu do hloubky 2 úrovní](/documentation/user/en/solve/examples/render-category-menu/mega-menu.evitaql)

</SourceCodeTabs>

Což vrátí následující výsledek:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.Hierarchy.referenceHierarchies.categories.megaMenu">[Výsledek pro mega-menu](/documentation/user/en/solve/examples/render-category-menu/mega-menu.evitaql.json.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.hierarchy.categories.megaMenu">[Výsledek pro mega-menu](/documentation/user/en/solve/examples/render-category-menu/mega-menu.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude sourceVariable="extraResults.hierarchy.categories.megaMenu">[Výsledek pro mega-menu](/documentation/user/en/solve/examples/render-category-menu/mega-menu.rest.json.md)</MDInclude>

</LS>

Někdy budete chtít zobrazit počet produktů v každé kategorii. 
Toho lze dosáhnout přidáním požadavku na <LS to="e,j,c,r">[`QUERIED_ENTITY_COUNT` statistiky](../query/requirements/hierarchy.md#statistics)</LS><LS to="g">[`queriedEntityCount` statistiky](../query/requirements/hierarchy.md#statistics)</LS> do dotazu:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání dat pro mega menu do hloubky 2 úrovní s produktovými statistikami](/documentation/user/en/solve/examples/render-category-menu/mega-menu-with-product-statistics.evitaql)

</SourceCodeTabs>

<Note type="warning">

<strong>Dejte si však pozor!</strong> Výpočet statistik v tomto případě pravděpodobně vyžaduje projít všechny produkty v databázi (pokud jsou přiřazeny k některé z kategorií v hierarchii). To může být náročná operace a nedoporučujeme ji provádět při každém požadavku. Zvažte předvyrábění mega-menu a cachování výsledku. Nebo se ujistěte, že je cache v evitaDB povolena a správně nakonfigurována. Pokud se požadavek na mega-menu opakuje často, měl by být pravděpodobně cachován, protože výpočet menu je nákladná operace.

</Note>

Výsledky nyní také obsahují počet produktů odpovídajících filtru v každé z kategorií:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.Hierarchy.referenceHierarchies.categories.megaMenu">[Výsledek pro mega-menu](/documentation/user/en/solve/examples/render-category-menu/mega-menu-with-product-statistics.evitaql.json.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.hierarchy.categories.megaMenu">[Výsledek pro mega-menu](/documentation/user/en/solve/examples/render-category-menu/mega-menu-with-product-statistics.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude sourceVariable="extraResults.hierarchy.categories.megaMenu">[Výsledek pro mega-menu](/documentation/user/en/solve/examples/render-category-menu/mega-menu-with-product-statistics.rest.json.md)</MDInclude>

</LS>

## Dynamické rozbalovací menu

Dalším běžným scénářem je dynamické rozbalovací menu. Je podobné mega menu, ale obvykle se používá 
v administrátorských rozhraních. Pro ilustraci tohoto typu menu se podívejte na následující obrazovku:

![Příklad dynamického rozbalovacího menu](../../en/query/requirements/assets/dynamic-tree.png "Dynamic collapsible menu example")

Menu zobrazuje pouze jednu úroveň kategorií s možností otevřít každou z nich na požádání. Pro vykreslení takového menu 
potřebujete velmi jednoduchý dotaz, který však musí obsahovat požadavek na výpočet 
<LS to="e,j,c,r">[`CHILDREN_COUNT` statistiky](../query/requirements/hierarchy.md#statistics)</LS><LS to="g">[`childrenCount` statistiky](../query/requirements/hierarchy.md#statistics)</LS>:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání dat pro dynamické rozbalovací menu](/documentation/user/en/solve/examples/render-category-menu/dynamic-collapsible-menu.evitaql)

</SourceCodeTabs>

Výsledek bude obsahovat počet podkategorií v každé kategorii, takže můžete zobrazit znaménko plus vedle 
názvu kategorie a umožnit uživateli rozbalit kategorii:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.Hierarchy.referenceHierarchies.categories.dynamicMenu">[Výsledek pro horní úroveň dynamického menu](/documentation/user/en/solve/examples/render-category-menu/dynamic-collapsible-menu.evitaql.json.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.hierarchy.categories.dynamicMenu">[Výsledek pro horní úroveň dynamického menu](/documentation/user/en/solve/examples/render-category-menu/dynamic-collapsible-menu.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude sourceVariable="extraResults.hierarchy.categories.dynamicMenu">[Výsledek pro horní úroveň dynamického menu](/documentation/user/en/solve/examples/render-category-menu/dynamic-collapsible-menu.rest.json.md)</MDInclude>

</LS>

Když uživatel rozbalí kategorii, můžete zadat další dotaz pro získání podkategorií rozbalených 
kategorií podobným způsobem:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání dat pro vnořené kategorie v dynamickém menu](/documentation/user/en/solve/examples/render-category-menu/dynamic-collapsible-menu-sub-category.evitaql)

</SourceCodeTabs>

Všimněte si, že primární klíč nadřazené kategorie je použit ve filtru požadavku na výpočet podhierarchie. 
Také <LS to="e,j,c">`stop(level(1))`</LS><LS to="g,r">`stopAt: { level: 1 }`</LS> bylo nahrazeno <LS to="e,j,c">`stop(distance(1))`</LS><LS to="g,r">`stopAt: { distance: 1 }`</LS>,
protože úroveň je pro každou nadřazenou kategorii jiná, zatímco vzdálenost je relativní k nadřazenému uzlu a umožňuje 
nám obecněji vyjádřit požadovanou hloubku načtení. 
Výsledek bude totožný s výpisem kořenových kategorií:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.Hierarchy.referenceHierarchies.categories.dynamicMenuSubcategories">[Výsledek pro vnořené kategorie v dynamickém menu](/documentation/user/en/solve/examples/render-category-menu/dynamic-collapsible-menu-sub-category.evitaql.json.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.hierarchy.categories.dynamicMenuSubcategories">[Výsledek pro vnořené kategorie v dynamickém menu](/documentation/user/en/solve/examples/render-category-menu/dynamic-collapsible-menu-sub-category.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude sourceVariable="extraResults.hierarchy.categories.dynamicMenuSubcategories">[Výsledek pro vnořené kategorie v dynamickém menu](/documentation/user/en/solve/examples/render-category-menu/dynamic-collapsible-menu-sub-category.rest.json.md)</MDInclude>

</LS>

## Výpis podkategorií

Je poměrně běžné zobrazit několik propagovaných podkategorií aktuální kategorie těsně nad seznamem produktů. Podobné výpisy
najdete po celém webu:

![Příklad výpisu podkategorií](../../en/query/requirements/assets/category-listing.png "Sub-categories listing example")

Následující dotaz vám pomůže získat takový seznam pro libovolný z vykreslených výpisů kategorií:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání dat pro výpis podkategorií](/documentation/user/en/solve/examples/render-category-menu/sub-categories-listing.evitaql)

</SourceCodeTabs>

Protože používáme požadavek [`children`](../query/requirements/hierarchy.md#children), výsledek bude vypočítán
správně i v případě, že se aktuální kategorie změní ve filtrační části `hierarchyWithin`, a bude vždy obsahovat
aktuálně filtrovanou kategorii spolu s jednou úrovní jejích podkategorií:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.Hierarchy.referenceHierarchies.categories.subcategories">[Výsledek pro výpis podkategorií](/documentation/user/en/solve/examples/render-category-menu/sub-categories-listing.evitaql.json.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.hierarchy.categories.subcategories">[Výsledek pro výpis podkategorií](/documentation/user/en/solve/examples/render-category-menu/sub-categories-listing.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude sourceVariable="extraResults.hierarchy.categories.subcategories">[Výsledek pro výpis podkategorií](/documentation/user/en/solve/examples/render-category-menu/sub-categories-listing.rest.json.md)</MDInclude>

</LS>

## Hybridní menu

Existuje mnoho variant menu, ale pojďme náš článek zakončit příkladem hybridního menu. Toto menu se často používá jako vertikální menu, které zobrazuje kategorie na kořenové úrovni s otevřenou osou k aktuálně vybrané kategorii, doplněné o sourozenecké kategorie na stejné úrovni. Vypadá to takto:

![Příklad hybridního menu](../../en/query/requirements/assets/hybrid-menu.png "Hybrid menu example")

Toto menu musí být složeno ze tří vypočítaných výsledků. První, nazvaný `topLevel`, bude obsahovat kategorie na kořenové úrovni, druhý, nazvaný `siblings`, bude obsahovat sourozenecké kategorie aktuálně vybrané kategorie a třetí, nazvaný `parents`, bude obsahovat rodiče vybrané kategorie. Kombinací těchto tří výsledků můžete snadno vykreslit hybridní menu:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání dat pro hybridní menu](/documentation/user/en/solve/examples/render-category-menu/hybrid-menu.evitaql)

</SourceCodeTabs>

Výsledkem budou kategorie na kořenové úrovni a sourozenecké kategorie aktuálně vybrané kategorie:

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.Hierarchy.referenceHierarchies.categories">[Výsledek pro hybridní menu](/documentation/user/en/solve/examples/render-category-menu/hybrid-menu.evitaql.json.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.hierarchy.categories">[Výsledek pro hybridní menu](/documentation/user/en/solve/examples/render-category-menu/hybrid-menu.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude sourceVariable="extraResults.hierarchy.categories">[Výsledek pro hybridní menu](/documentation/user/en/solve/examples/render-category-menu/hybrid-menu.rest.json.md)</MDInclude>

</LS>

## Skrývání částí stromu kategorií

Možná jste si někdy všimli, že určitá část regálů v nákupních centrech je schovaná za závěsem – protože se tam připravuje nová prodejní plocha se speciální nabídkou. Podobně se v katalozích často připravují nové sekce, ke kterým mají přístup pouze lidé, kteří na nich pracují. V našem demo datasetu máme atribut s názvem `status`, který může nabývat hodnot `ACTIVE` nebo `PRIVATE`. Hodnota `ACTIVE` znamená, že kategorie ještě není připravena pro veřejnost, a proto by neměla být v menu viditelná ani přístupná. Abyste toho dosáhli, můžete pro návštěvníky vypsat produkty a vykreslit menu pomocí následujícího dotazu:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání dat pro menu bez privátních kategorií](/documentation/user/en/solve/examples/render-category-menu/excluding-private-categories.evitaql)

</SourceCodeTabs>

Dočasné nabídky lze řešit podobně elegantním způsobem. Představme si, že chceme v kategorii *Příslušenství* připravit předem sekci *„Vánoční elektronika“*, která bude obsahovat například LED vánoční osvětlení, pyrotechniku a podobně. Pokud v entitě kategorie vytvoříme atribut typu `DateTimeRange` s názvem `validity` a nastavíme jeho hodnotu pouze na období Vánoc (tak, jak jsme to udělali v našem demo datasetu), můžeme pak definovat následující dotaz:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání dat pro menu bez kategorií s prošlou platností](/documentation/user/en/solve/examples/render-category-menu/excluding-expired-categories.evitaql)

</SourceCodeTabs>

Tedy: vypiš mi všechny produkty v kategorii `accessories`, za předpokladu, že jsou v kategorii bez definované platnosti nebo mají nastavený rozsah platnosti, který zahrnuje aktuální okamžik. Všimněte si, že ve výsledku není kategorie *„Vánoční elektronika“*, protože v tuto chvíli není platná. Pokud však dotaz trochu upravíme a posuneme čas do období Vánoc, tuto kategorii ve výsledku získáme:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Získání dat pro menu v období Vánoc](/documentation/user/en/solve/examples/render-category-menu/excluding-expired-categories-at-correct-time.evitaql)

</SourceCodeTabs>

<Note type="info">

Některé položky bývají zařazeny do více než jedné kategorie – například žvýkačky najdete v sekci *cukrovinky* v obchodě, ale také u pokladen mezi produkty, na které máte čas se podívat před zaplacením. Pokud obchodní dům ohradí sekci cukrovinek kvůli její rekonstrukci, měli byste přijít o možnost koupit si žvýkačky u pokladny? Samozřejmě že ne. evitaDB se zachová stejně – pokud najde alespoň jeden odkaz na produkt ve viditelné části hierarchického stromu, zahrne tento produkt do výsledků vyhledávání.

</Note>