---
title: Filtrování podle hierarchie
perex: Filtrování podle hierarchie vám umožňuje dotazovat se na stromové struktury nebo položky, které odkazují na uzel v této struktuře. V e-commerce projektech je hierarchická struktura reprezentována stromem kategorií a položky, které na něj odkazují, jsou obvykle produkty nebo nějaký druh „inventáře“. Tato funkce úzce souvisí s procházením menu a výpisem položek relevantních pro aktuálně zobrazenou kategorii.
date: '5.5.2023'
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
translated: 'true'
commit: ecc9ddd4a929f8020bca123be8bf4b2ed9b635b7
---
Hierarchické filtrování lze použít pouze na entity [označené jako hierarchické](../../use/data-model.md#hierarchické-umístění)
nebo na entity, které [odkazují](../../use/data-model.md#reference) na tyto hierarchické entity. Hierarchické filtrování
umožňuje filtrovat všechny přímé nebo tranzitivní potomky daného uzlu hierarchie, nebo entity, které jsou přímo nebo
tranzitivně propojené s požadovaným uzlem hierarchie nebo jeho potomky. Filtrování umožňuje vyloučit (skrýt) určité části
stromu z vyhodnocení, což může být užitečné v situaci, kdy by měla být část obchodu (dočasně) skryta
před (některými) klienty.

Kromě filtrování existují v dotazu [rozšíření požadavků](../requirements/hierarchy.md), která umožňují
vypočítat data pro vykreslení (dynamických nebo statických) menu, která popisují hierarchický kontext požadovaný v dotazu.

**Typické případy použití související s hierarchickými omezeními:**

- [vypsání produktů v kategorii](../../solve/render-products-in-category.md)
- [vykreslení menu kategorií](../../solve/render-category-menu.md)
- [vypsání kategorií pro produkty konkrétní značky](../../solve/render-products-in-brand.md)

<Note type="warning">
V celém dotazu může být maximálně jedno jediné omezení filtru `hierarchyWithin` nebo `hierarchyRoot`.
</Note>

## Hierarchy within

Omezení <LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/HierarchyWithin.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Filter/HierarchyWithin.cs</SourceClass> </LS>
vám umožňuje omezit hledání pouze na ty entity, které jsou součástí stromu hierarchie začínajícího kořenovým
uzlem identifikovaným prvním argumentem tohoto omezení. V e-commerce systémech je typickým zástupcem
hierarchické entity *kategorie*, která bude použita ve všech našich příkladech. Příklady v této kapitole se zaměří
na kategorii *Příslušenství* v našem [demo datasetu](../../get-started/query-our-dataset.md) s následujícím rozložením:

![Accessories category listing](../../../en/query/filtering/assets/accessories-category-listing.png "Accessories category listing")

### Self

```evitaql-syntax
hierarchyWithin(
    filterConstraint:any!,
    filterConstraint:(directRelation|having|excluding|excludingRoot)*
)
```

<dl>
    <dt>filterConstraint:any!</dt>
    <dd>
        jedno povinné omezení filtru, které identifikuje **jeden nebo více** uzlů hierarchie, které fungují jako kořeny hierarchie;
        více omezení musí být uzavřeno do [AND](../logical.md#and) / [OR](../logical.md#or) kontejnerů
    </dd>
    <dt>filterConstraint:(directRelation|having|excluding|excludingRoot)*</dt>
    <dd>
        volitelná omezení umožňují zúžit rozsah hierarchie;
        žádné nebo všechna omezení mohou být přítomna:
        <ul>
            <li>[directRelation](#direct-relation)</li>
            <li>[having](#having)</li>
            <li>[excluding](#excluding)</li>
            <li>[excludingRoot](#excluding-root)</li>
        </ul>
    </dd>
</dl>

Nejpřímější použití je filtrování samotných hierarchických entit.

Pro vypsání všech vnořených kategorií kategorie *Příslušenství* použijte tento dotaz:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Transitive category listing](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-simple.evitaql)

</SourceCodeTabs>

... a v odpovědi byste měli získat o něco více než jednu stránku kategorií.

<Note type="info">

<NoteTitle toggles="true">

##### Seznam všech podkategorií kategorie *Příslušenství*
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Single root hierarchy example](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-simple.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Single root hierarchy example](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-simple.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Single root hierarchy example](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-simple.rest.json.md)</MDInclude>

</LS>

</Note>

První argument určuje, že filtr cílí na atributy entity `Category`. V tomto příkladu jsme použili
[attributeEquals](comparable.md#atribut-rovný) pro unikátní atribut `code`, ale můžete vybrat kategorii
podle lokalizovaného atributu `url` (ale pak musíte také zadat omezení [entityLocaleEquals](locale.md#entity-locale-equals)
pro určení správného jazyka), nebo použít [entityPrimaryKeyInSet](constant.md#primární-klíč-entity-v-množině)
a předat primární klíč kategorie.

<Note type="info">

<NoteTitle toggles="true">

##### Může omezení filtru rodičovského uzlu odpovídat více uzlům?

</NoteTitle>

Ano, může. Ačkoliv je to zjevně okrajový případ, je to možné. Tento dotaz:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Multiple category listing](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-multi.evitaql)

</SourceCodeTabs>

... vrátí všechny podkategorie *Bezdrátová sluchátka* a *Drátová sluchátka* a jejich podkategorie:

<LS to="e,j,c">

<MDInclude>[Multi-root hierarchy example](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-multi.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Multi-root hierarchy example](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-multi.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Multi-root hierarchy example](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-multi.rest.json.md)</MDInclude>

</LS>

![Accessories category listing](../../../en/query/filtering/assets/accessories-category-listing-multi.png "Accessories category listing")

</Note>

### Referencovaná entita

```evitaql-syntax
hierarchyWithin(
    argument:string!,
    filterConstraint:any!,
    filterConstraint:(directRelation|having|excluding|excludingRoot)*
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        povinný název [referenčního schématu](../../use/schema.md#reference) dotazované entity, které představuje
        vztah k hierarchickému typu entity; vaše entita může cílit na různé hierarchické entity v různých typech referencí,
        nebo může cílit na stejnou hierarchickou entitu prostřednictvím více sémanticky odlišných referencí, a proto se používá název reference místo cílového typu entity.
    </dd>
    <dt>filterConstraint:any!</dt>
    <dd>
        jedno povinné omezení filtru, které identifikuje **jeden nebo více** uzlů hierarchie, které fungují jako kořeny hierarchie;
        více omezení musí být uzavřeno do [AND](../logical.md#and) / [OR](../logical.md#or) kontejnerů
    </dd>
    <dt>filterConstraint:(directRelation|having|excluding|excludingRoot)*</dt>
    <dd>
        volitelná omezení umožňují zúžit rozsah hierarchie;
        žádné nebo všechna omezení mohou být přítomna:
        <ul>
            <li>[directRelation](#direct-relation)</li>
            <li>[having](#having)</li>
            <li>[excluding](#excluding)</li>
            <li>[excludingRoot](#excluding-root)</li>
        </ul>
    </dd>
</dl>

Omezení `hierarchyWithin` lze použít také pro entity, které přímo odkazují na hierarchický typ entity.
Nejčastějším případem z e-commerce světa je produkt, který je přiřazen do jedné nebo více kategorií. Pro vypsání všech
produktů v kategorii *Příslušenství* v našem [demo datasetu](../../get-started/query-our-dataset.md) použijeme následující dotaz:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Product listing from *Accessories* category](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-simple.evitaql)

</SourceCodeTabs>

Produkty přiřazené do dvou nebo více podkategorií kategorie *Příslušenství* se v odpovědi objeví pouze jednou (na rozdíl
od toho, co byste mohli očekávat, pokud máte zkušenosti s SQL).

Dotaz vrací první stránku z celkových 26 stránek položek.

<Note type="info">

<NoteTitle toggles="true">

##### Seznam všech produktů v kategorii *Příslušenství* nebo jejích podkategoriích

</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Product listing from *Accessories* category](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-simple.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Product listing from *Accessories* category](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-simple.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Product listing from *Accessories* category](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-simple.rest.json.md)</MDInclude>

</LS>

</Note>

Omezení filtru kategorie určuje podmínku, která cílí na referencovanou entitu (tj. atributy kategorie,
reference kategorie). V současné době není možné zadat omezení filtru, které by zohledňovalo produktovou
referenci vedoucí do její kategorie. [Problém #105](https://github.com/FgForrest/evitaDB/issues/105) je plánován
k vyřešení tohoto nedostatku.

## Hierarchy within root

Omezení <LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/HierarchyWithinRoot.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Filter/HierarchyWithinRoot.cs</SourceClass></LS>
vám umožňuje omezit hledání pouze na ty entity, které jsou součástí celého stromu hierarchie. V e-commerce
systémech je typickým zástupcem hierarchické entity *kategorie*, která bude použita ve všech našich příkladech.

Jediný rozdíl oproti [omezení hierarchyWithin](#hierarchy-within) je, že nepřijímá specifikaci kořenového uzlu.
Protože evitaDB umožňuje více kořenových uzlů ve vaší hierarchii entit, může být užitečné si představit,
že existuje neviditelný "virtuální" horní kořen nad všemi horními uzly (jejichž vlastnost `parent` zůstává `NULL`),
které máte ve své hierarchii entit, a tento virtuální horní kořen je cílem tohoto omezení.

![Root categories listing](../../../en/query/filtering/assets/category-listing.png "Root categories listing")

### Self

```evitaql-syntax
hierarchyWithinRoot(
    filterConstraint:(directRelation|having|excluding)*
)
```

<dl>
    <dt>filterConstraint:(directRelation|having|excluding)*</dt>
    <dd>
        volitelná omezení umožňují zúžit rozsah hierarchie;
        žádné nebo všechna omezení mohou být přítomna:
        <ul>
            <li>[directRelation](#direct-relation)</li>
            <li>[having](#having)</li>
            <li>[excluding](#excluding)</li>
        </ul>
    </dd>
</dl>

Omezení `hierarchyWithinRoot`, které cílí na kolekci `Category`, vrací všechny kategorie kromě těch,
které by ukazovaly na neexistující rodičovské uzly. Takové uzly hierarchie se nazývají [sirotci](../../use/schema.md#sirotčí-uzly-v-hierarchii)
a nevyhovují žádnému dotazu na hierarchii.

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Category listing](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-root-simple.evitaql)

</SourceCodeTabs>

Dotaz vrací první stránku z celkových 2 stránek položek.

<Note type="info">

<NoteTitle toggles="true">

##### Seznam všech kategorií ve stromu hierarchie

</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Category listing](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-root-simple.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Category listing](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-root-simple.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Category listing](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-root-simple.rest.json.md)</MDInclude>

</LS>

</Note>

### Referencovaná entita

```evitaql-syntax
hierarchyWithinRoot(
    argument:string!,
    filterConstraint:(having|excluding)*
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        povinný název [referenčního schématu](../../use/schema.md#reference) dotazované entity, které představuje
        vztah k hierarchickému typu entity; vaše entita může cílit na různé hierarchické entity v různých typech referencí,
        nebo může cílit na stejnou hierarchickou entitu prostřednictvím více sémanticky odlišných referencí, a proto se používá název reference místo cílového typu entity.
    </dd>
    <dt>filterConstraint:(having|excluding)*</dt>
    <dd>
        volitelná omezení umožňují zúžit rozsah hierarchie;
        žádné nebo všechna omezení mohou být přítomna:
        <ul>
            <li>[directRelation](#direct-relation)</li>
            <li>[having](#having)</li>
            <li>[excluding](#excluding)</li>
        </ul>
    </dd>
</dl>

Omezení `hierarchyWithinRoot` lze použít také pro entity, které přímo odkazují na hierarchický typ entity.
Nejčastějším případem z e-commerce světa je produkt, který je přiřazen do jedné nebo více kategorií. Pro vypsání všech
produktů přiřazených do jakékoli kategorie v našem [demo datasetu](../../get-started/query-our-dataset.md) použijeme následující dotaz:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Product listing assigned to a category](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-root-reference-simple.evitaql)

</SourceCodeTabs>

Produkty přiřazené pouze do jedné [sirotčí kategorie](../../use/schema.md#sirotčí-uzly-v-hierarchii) budou v
výsledku chybět. Produkty přiřazené do dvou nebo více kategorií se v odpovědi objeví pouze jednou (na rozdíl od toho,
co byste mohli očekávat, pokud máte zkušenosti s SQL).

Dotaz vrací první stránku z celkových 212 stránek položek:

<Note type="info">

<NoteTitle toggles="true">

##### Seznam všech produktů přiřazených do jakékoli kategorie ve stromu hierarchie

</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Product listing assigned to a category](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-root-reference-simple.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Product listing assigned to a category](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-root-reference-simple.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Product listing assigned to a category](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-root-reference-simple.rest.json.md)</MDInclude>

</LS>

</Note>

## Direct relation

Omezení <LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/HierarchyDirectRelation.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Filter/HierarchyDirectRelation.cs</SourceClass></LS>
je omezení, které lze použít pouze v rámci nadřazených omezení `hierarchyWithin` nebo `hierarchyWithinRoot`. Jinde
nedává smysl, protože mění výchozí chování těchto omezení. Hierarchická omezení vracejí
všechny potomky hierarchie rodičovského uzlu nebo entity, které jsou s nimi přímo nebo tranzitivně propojené, včetně samotného rodičovského uzlu. Pokud je použito `directRelation` jako podřízené omezení, toto chování se změní a jsou vráceni pouze přímí potomci nebo přímo odkazující entity.

```evitaql-syntax
directRelation()
```

### Self

Pokud hierarchické omezení cílí na hierarchickou entitu, `directRelation` způsobí, že budou vráceny pouze děti přímého
rodičovského uzlu. V případě omezení `hierarchyWithinRoot` je rodičem neviditelný "virtuální"
horní kořen – takže jsou vráceny pouze kategorie nejvyšší úrovně.

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Top categories listing](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-top-categories.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Seznam všech kořenových kategorií v hierarchii

</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Top categories listing](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-top-categories.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Top categories listing](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-top-categories.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Top categories listing](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-top-categories.rest.json.md)</MDInclude>

</LS>

</Note>

V případě `hierarchyWithin` bude výsledek obsahovat přímé děti filtrované kategorie (nebo kategorií).

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Accessories children categories listing](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-direct-categories.evitaql)

</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Seznam všech přímých podkategorií kategorie *Příslušenství*
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Accessories children categories listing](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-direct-categories.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Accessories children categories listing](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-direct-categories.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Accessories children categories listing](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-direct-categories.rest.json.md)</MDInclude>

</LS>

</Note>

### Referencovaná entita

Pokud hierarchické omezení cílí na nehierarchickou entitu, která odkazuje na hierarchickou (typický příklad je
produkt přiřazený do kategorie), lze ji použít pouze v nadřazeném omezení `hierarchyWithin`.

V případě `hierarchyWithinRoot` omezení `directRelation` nedává smysl, protože žádná entita nemůže být přiřazena
k "virtuálnímu" hornímu rodičovskému kořeni.

Můžeme tedy vypsat pouze produkty, které jsou přímo přiřazeny ke konkrétní kategorii – pokud se pokusíme vypsat produkty,
které mají přiřazenou kategorii *Příslušenství*:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Products directly assigned to Accessories category](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-direct-categories.evitaql)

</SourceCodeTabs>

... dostaneme prázdný výsledek. Neexistují žádné produkty přímo přiřazené ke kategorii *Příslušenství*, všechny odkazují
na některou z jejích podkategorií. Zkusme podkategorii *Chytré hodinky*:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Products directly assigned to Smartwatches category](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-direct-categories-smart.evitaql)

</SourceCodeTabs>

... a získáme seznam všech produktů přímo přiřazených ke kategorii *Chytré hodinky*.

<Note type="info">

<NoteTitle toggles="true">

##### Seznam všech produktů přímo přiřazených ke kategorii *Chytré hodinky*
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Product directly assigned to Smartwatches category](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-direct-categories-smart.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Product directly assigned to Smartwatches category](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-direct-categories-smart.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Product directly assigned to Smartwatches category](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-direct-categories-smart.rest.json.md)</MDInclude>

</LS>

</Note>

## Excluding root

Omezení <LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/HierarchyExcludingRoot.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Filter/HierarchyExcludingRoot.cs</SourceClass></LS>
je omezení, které lze použít pouze v rámci nadřazených omezení `hierarchyWithin` nebo `hierarchyWithinRoot`. Jinde
nedává smysl, protože mění výchozí chování těchto omezení. Hierarchická omezení vracejí
všechny potomky hierarchie rodičovského uzlu nebo entity, které jsou s nimi přímo nebo tranzitivně propojené, včetně samotného rodičovského uzlu. Pokud je použito `excludingRoot` jako podřízené omezení, toto chování se změní a samotný rodičovský uzel nebo entity přímo související s tímto uzlem jsou z výsledku vyloučeny.

```evitaql-syntax
excludingRoot()
```

### Self

Pokud hierarchické omezení cílí na hierarchickou entitu, `excludingRoot` vynechá požadovaný rodičovský uzel z výsledku.
V případě omezení `hierarchyWithinRoot` je rodičem neviditelný "virtuální" horní kořen a toto
omezení nedává smysl.

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Category listing excluding parent](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-excluding-root.evitaql)

</SourceCodeTabs>

Jak vidíme, požadovaná rodičovská kategorie *Příslušenství* je z výsledku vyloučena.

<Note type="info">

<NoteTitle toggles="true">

##### Seznam všech podkategorií kategorie *Příslušenství* kromě samotné kategorie *Příslušenství*

</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Category listing excluding parent](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-excluding-root.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Category listing excluding parent](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-excluding-root.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Category listing excluding parent](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-excluding-root.rest.json.md)</MDInclude>

</LS>

</Note>

### Referencovaná entita

Pokud hierarchické omezení cílí na nehierarchickou entitu, která odkazuje na hierarchickou (typický příklad je
produkt přiřazený do kategorie), lze omezení `excludingRoot` použít pouze v nadřazeném omezení `hierarchyWithin`.

V případě `hierarchyWithinRoot` omezení `excludingRoot` nedává smysl, protože žádná entita nemůže být přiřazena
k "virtuálnímu" hornímu rodičovskému kořeni.

Protože jsme zjistili, že kategorie *Příslušenství* nemá přímo přiřazené produkty, přítomnost omezení `excludingRoot`
by výsledek dotazu nijak neovlivnila. Proto zvolíme kategorii *Klávesnice* pro náš příklad. Když vypíšeme všechny produkty
v kategorii *Klávesnice* pomocí omezení `hierarchyWithin`, získáme **20 položek**. Pokud použijeme omezení `excludingRoot`:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Products in subcategories of Keyboard category](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-excluding-root.evitaql)

</SourceCodeTabs>

... získáme pouze **4 položky**, což znamená, že 16 bylo přiřazeno přímo ke kategorii *Klávesnice* a pouze 4 byly
přiřazeny ke kategorii *Exotické klávesnice*:

<Note type="info">

<NoteTitle toggles="true">

##### Seznam všech produktů souvisejících s podkategoriemi kategorie *Klávesnice* kromě samotné kategorie *Klávesnice*
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Products in subcategories of Keyboard category](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-excluding-root.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Products in subcategories of Keyboard category](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-excluding-root.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Products in subcategories of Keyboard category](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-excluding-root.rest.json.md)</MDInclude>

</LS>

</Note>

## Having

Omezení <LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/HierarchyHaving.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Filter/HierarchyHaving.cs</SourceClass></LS>
je omezení, které lze použít pouze v rámci nadřazených omezení `hierarchyWithin` nebo `hierarchyWithinRoot`. Jinde
nedává smysl, protože mění výchozí chování těchto omezení. Hierarchická omezení vracejí
všechny potomky hierarchie rodičovského uzlu nebo entity, které jsou s nimi přímo nebo tranzitivně propojené, včetně samotného rodičovského uzlu.

Omezení `having` vám umožňuje nastavit omezení, které musí být splněno všemi kategoriemi v rozsahu kategorie,
aby byly akceptovány filtrem hierarchy within. Toto omezení je zvláště užitečné, pokud chcete podmíněně
zobrazit určité části stromu. Představte si, že máte kategorii *Vánoční výprodej*, která by měla být dostupná pouze během
určitého období v roce, nebo kategorii *B2B Partneři*, která by měla být přístupná pouze určité roli uživatelů.
Všechny tyto scénáře mohou využít omezení `having` (ale existují i jiné přístupy k řešení výše uvedených případů).

<Note type="warning">

<NoteTitle toggles="false">

##### Vyhledávání se zastaví na prvním uzlu, který nesplňuje omezení!

</NoteTitle>

Hierarchický dotaz prochází od kořenových uzlů k listovým uzlům. U každého uzlu engine zkontroluje, zda je
omezení `having` stále platné, a pokud ne, vyloučí tento uzel hierarchie a všechny jeho poduzly (celý
podstrom).

</Note>

```evitaql-syntax
having(
    filterConstraint:+
)
```

<dl>
    <dt>filterConstraint:+</dt>
    <dd>
        jedno nebo více povinných omezení, která musí být splněna všemi vrácenými uzly hierarchie a která označují
        viditelnou část stromu; implicitní vztah mezi omezeními je logická konjunkce (boolean AND)
    </dd>
</dl>

### Self

Pokud hierarchické omezení cílí na hierarchickou entitu, děti, které nesplňují vnitřní omezení (a jejich děti,
ať už je splňují nebo ne), jsou z výsledku vyloučeny.

Pro demonstrační účely vypíšeme všechny kategorie v rámci kategorie *Příslušenství*, ale pouze ty, které jsou platné
k 1. říjnu 2023 v 01:00.

![Accessories category listing with validity constraint](../../../en/query/filtering/assets/accessories-category-listing-validity.png "Accessories category listing with validity constraint")

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Category listing excluding parent](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-having.evitaql)

</SourceCodeTabs>

Protože kategorie *Vánoční elektronika* má platnost nastavenu pouze mezi 1. prosincem a 24. prosincem, nebude ve výsledku zahrnuta.
Pokud by měla podkategorie, byly by také vyloučeny (i kdyby neměly žádná omezení platnosti).

<Note type="info">

<NoteTitle toggles="true">

##### Seznam všech platných podkategorií kategorie *Příslušenství*
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Accessories category listing with validity constraint](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-having.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Accessories category listing with validity constraint](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-having.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Accessories category listing with validity constraint](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-having.rest.json.md)</MDInclude>

</LS>

</Note>

### Referencovaná entita

Pokud hierarchické omezení cílí na nehierarchickou entitu, která odkazuje na hierarchickou (typický příklad je
produkt přiřazený do kategorie), je omezení `having` vyhodnocováno vůči hierarchické entitě (kategorii), ale
ovlivňuje dotazované nehierarchické entity (produkty). Vyloučí všechny produkty odkazující na kategorie, které nesplňují
vnitřní omezení `having`.

Opět použijeme náš příklad s *Vánoční elektronikou*, která je platná pouze mezi 1. a 24. prosincem. Pro vypsání všech
produktů dostupných 1. října 2023 v 01:00 použijte následující dotaz:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Category listing excluding parent](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-having.evitaql)

</SourceCodeTabs>

Můžete vidět, že vánoční produkty jako *Retlux Blue christmas lightning*, *Retlux Warm white christmas lightning* nebo
*Emos Candlestick* nejsou ve výpisu přítomny.

<Note type="info">

<NoteTitle toggles="true">

##### Seznam všech produktů *Příslušenství* platných v říjnu 2023

</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Accessories category product listing with validity constraint](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-having.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Accessories category product listing with validity constraint](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-having.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Accessories category product listing with validity constraint](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-having.rest.json.md)</MDInclude>

</LS>

</Note>

Pokud změníte datum a čas v rozsahovém omezení pro atribut *validity* na 2. prosince:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Category listing excluding parent](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-having-december.evitaql)

</SourceCodeTabs>

... uvidíte všechny tyto produkty v kategorii *Vánoční elektronika*.

<Note type="info">

<NoteTitle toggles="true">

##### Seznam všech produktů *Příslušenství* platných v prosinci 2023
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Accessories category product listing with validity constraint](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-having-december.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Accessories category product listing with validity constraint](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-having-december.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Accessories category product listing with validity constraint](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-having-december.rest.json.md)</MDInclude>

</LS>

</Note>

<Note type="warning">

<NoteTitle toggles="true">

##### Co když je produkt přiřazen do dvou kategorií – jedna splňuje omezení a druhá ne?

</NoteTitle>

V situaci, kdy je jeden produkt, například *Garmin Vivosmart 5*, jak v vyloučené kategorii *Vánoční elektronika*,
tak v zahrnuté kategorii *Chytré hodinky*, jak je znázorněno na následujícím schématu:

![Accessories category listing with validity constraint](../../../en/query/filtering/assets/accessories-category-listing-validity.png "Accessories category listing with validity constraint")

... zůstane ve výsledku dotazu, protože existuje alespoň jedna produktová reference, která je součástí viditelné části
stromu.

</Note>

## Any Having

Omezení <LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/HierarchyAnyHaving.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Filter/HierarchyAnyHaving.cs</SourceClass></LS>
je omezení, které lze použít pouze v rámci nadřazených omezení `hierarchyWithin` nebo `hierarchyWithinRoot`. Použití
kdekoli jinde nedává smysl, protože mění výchozí chování těchto omezení. Hierarchická omezení vracejí
všechny hierarchické potomky nadřazeného uzlu, stejně jako entity, které jsou s nimi přímo nebo nepřímo propojeny, a také samotný nadřazený uzel.

Omezení `anyHaving` vám umožňuje nastavit podmínku, kterou musí splnit alespoň jedna vnořená hierarchická entita,
aby filtr tuto entitu akceptoval.

```evitaql-syntax
anyHaving(
    filterConstraint:+
)
```

<dl>
    <dt>filterConstraint:+</dt>
    <dd>
        jedno nebo více povinných omezení, která musí být splněna alespoň jedním podřízeným uzlem zkoumaného hierarchického
        uzlu nebo přímo tímto zkoumaným hierarchickým uzlem, implicitní vztah mezi omezeními je logická
        konjunkce (logické AND)
    </dd>
</dl>

### Self

Představte si, že chcete strom kategorií a chcete ověřit, zda určité kategorie, buď přímo nebo nepřímo prostřednictvím
jejich podkategorií, obsahují alespoň jeden platný produkt. To lze provést pomocí omezení `anyHaving` ve
vašem dotazu.

Pokud hierarchické omezení cílí na hierarchickou entitu, potomci, kteří nemají žádného potomka splňujícího vnitřní omezení,
jsou z výsledku vyloučeni.

Například napišme dotaz pro následující situaci. V našem hierarchickém stromu máme dvě kategorie označené
štítkem *HP*:

![Kategorie označené HP s produkty](../../../en/query/filtering/assets/categories-with-products-and-HP-tag.png "Kategorie označené HP s produkty")

Chceme vypsat všechny kategorie obsahující označenou kategorii, ke které je přiřazen alespoň jeden aktivní produkt. Zohledňujeme pouze cesty stromu složené z aktivních kategorií. Dotaz bude vypadat takto:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Filtrace kategorií, které mají alespoň jeden produkt, tranzitivně](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-any-having.evitaql)

</SourceCodeTabs>

Naštěstí obě kategorie označené štítkem *HP* mají přiřazen alespoň jeden aktivní produkt, stejně jako všechny jejich
nadřazené kategorie. Dotaz tedy vrací pět kategorií, jak bylo očekáváno.

<Note type="info">

<NoteTitle toggles="true">

##### Seznam všech podkategorií se štítkem HP a jejich nadřazených kategorií
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Seznam všech podkategorií se štítkem HP a jejich nadřazených kategorií](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-any-having.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Seznam všech podkategorií se štítkem HP a jejich nadřazených kategorií](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-any-having.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Seznam všech podkategorií se štítkem HP a jejich nadřazených kategorií](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-any-having.rest.json.md)</MDInclude>

</LS>

</Note>

### Referencovaná entita

Pokud hierarchické omezení cílí na nehierarchickou entitu, která odkazuje na hierarchickou (typickým příkladem je
produkt přiřazený ke kategorii), omezení `anyHaving` je vyhodnocováno vůči hierarchické entitě (kategorii), ale
ovlivňuje dotazované nehierarchické entity (produkty). Vyloučí všechny produkty odkazující na kategorie, které
nesplňují vnitřní omezení `anyHaving`.

Opět použijme náš příklad kategorií označených štítkem *HP*. Zadejte následující dotaz:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Výpis kategorií bez nadřazené](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-any-having.evitaql)

</SourceCodeTabs>

V tomto případě bude výsledkem seznam všech aktivních produktů s typem produktu *BASIC* nebo *MASTER* v kategoriích
označených štítkem *HP*, stejně jako všechny produkty v jejich nadřazených kategoriích. Jak můžete vidět, produktů je poměrně hodně,
protože je vrácen i produkt z celé kategorie *Laptop* (která je nadřazenou kategorií *Macbooks*).

<Note type="info">

<NoteTitle toggles="true">

##### Seznam všech produktů v kategoriích se štítkem HP a jejich nadřazených kategoriích
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Seznam všech produktů v kategoriích se štítkem HP a jejich nadřazených kategoriích](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-any-having.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Seznam všech produktů v kategoriích se štítkem HP a jejich nadřazených kategoriích](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-any-having.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Seznam všech produktů v kategoriích se štítkem HP a jejich nadřazených kategoriích](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-any-having.rest.json.md)</MDInclude>

</LS>

</Note>

## Excluding

Omezení <LS to="e,j,r,g"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/filter/HierarchyExcluding.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Queries/Filter/HierarchyExcluding.cs</SourceClass></LS>
je omezení, které lze použít pouze v rámci nadřazených omezení `hierarchyWithin` nebo `hierarchyWithinRoot`. Jinde
nedává smysl, protože mění výchozí chování těchto omezení. Hierarchická omezení vracejí
všechny hierarchické potomky nadřazeného uzlu nebo entity, které jsou s nimi přímo nebo nepřímo propojeny, a také samotný nadřazený uzel.

Omezení `excluding` vám umožňuje vyloučit jeden nebo více podstromů z rozsahu filtru. Toto omezení je
přesným opakem omezení [`having`](#having). Pokud je omezení pravdivé pro hierarchickou entitu, je tato entita a všichni její potomci vyloučeni z dotazu. Omezení `excluding` je stejné jako deklarace
`having(not(expression))`, ale kvůli čitelnosti má své vlastní omezení.

<Note type="warning">

<NoteTitle toggles="false">

##### Vyhledávání se zastaví u prvního uzlu, který splní omezení!
</NoteTitle>

Hierarchický dotaz prochází od kořenových uzlů k listům. U každého uzlu engine ověří, zda je omezení
`excluding` splněno, a pokud ano, vyloučí tento hierarchický uzel i všechny jeho poduzly
(celý podstrom).

</Note>

```evitaql-syntax
excluding(
    filterConstraint:+
)
```

<dl>
    <dt>filterConstraint:+</dt>
    <dd>
        jedno nebo více povinných omezení, která musí být splněna všemi vrácenými hierarchickými uzly a která označují
        viditelnou část stromu, implicitní vztah mezi omezeními je logická konjunkce (logické AND)
    </dd>
</dl>

### Self

Pokud hierarchické omezení cílí na hierarchickou entitu, potomci, kteří splňují vnitřní omezení (a
jejich potomci, ať už je splňují nebo ne), jsou z výsledku vyloučeni.

Pro demonstrační účely vypíšeme všechny kategorie v rámci kategorie *Příslušenství*, ale přesně
vyloučíme podkategorii *Bezdrátová sluchátka*.

![Výpis kategorie příslušenství bez podkategorie *Bezdrátová sluchátka*](../../../en/query/filtering/assets/accessories-category-listing-excluding.png "Výpis kategorie příslušenství bez podkategorie *Bezdrátová sluchátka*")

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Výpis kategorií bez nadřazené](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-excluding.evitaql)

</SourceCodeTabs>

Kategorie *Bezdrátová sluchátka* a všechny její podkategorie se ve výsledném seznamu neobjeví.

<Note type="info">

<NoteTitle toggles="true">

##### Seznam všech podkategorií kategorie *Příslušenství* kromě *Bezdrátová sluchátka*
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Výpis kategorie příslušenství bez *Bezdrátová sluchátka*](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-excluding.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Výpis kategorie příslušenství bez *Bezdrátová sluchátka*](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-excluding.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Výpis kategorie příslušenství bez *Bezdrátová sluchátka*](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-self-excluding.rest.json.md)</MDInclude>

</LS>

</Note>

### Referencovaná entita

Pokud hierarchické omezení cílí na nehierarchickou entitu, která odkazuje na hierarchickou (typickým příkladem je
produkt přiřazený ke kategorii), omezení `excluding` je vyhodnocováno vůči hierarchické entitě (kategorii),
ale ovlivňuje dotazované nehierarchické entity (produkty). Vyloučí všechny produkty odkazující na kategorie, které
splňují vnitřní omezení `excluding`.

Vraťme se k našemu příkladovému dotazu, který vylučuje podstrom kategorie *Bezdrátová sluchátka*. Pro výpis všech produktů
dostupných v kategorii *Příslušenství* kromě těch, které souvisejí s kategorií *Bezdrátová sluchátka* nebo jejími podkategoriemi,
zadejte následující dotaz:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Výpis kategorií bez nadřazené](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-except.evitaql)

</SourceCodeTabs>

Můžete si všimnout, že produkty bezdrátových sluchátek jako *Huawei FreeBuds 4*, *Jabra Elite 3* nebo *Adidas FWD-02 Sport* nejsou
ve výpisu obsaženy.

<Note type="info">

<NoteTitle toggles="true">

##### Seznam všech produktů *Příslušenství* kromě *Bezdrátová sluchátka*
</NoteTitle>

<LS to="e,j,c">

<MDInclude>[Výpis produktů kategorie příslušenství kromě *Bezdrátová sluchátka*](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-except.evitaql.md)</MDInclude>

</LS>

<LS to="g">

<MDInclude>[Výpis produktů kategorie příslušenství kromě *Bezdrátová sluchátka*](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-except.graphql.json.md)</MDInclude>

</LS>

<LS to="r">

<MDInclude>[Výpis produktů kategorie příslušenství kromě *Bezdrátová sluchátka*](/documentation/user/en/query/filtering/examples/hierarchy/hierarchy-within-reference-except.rest.json.md)</MDInclude>

</LS>

</Note>

Pokud je produkt přiřazen ke dvěma kategoriím – jedné vyloučené a jedné, která je součástí viditelného stromu kategorií, produkt
zůstává ve výsledku. Viz [příklad](#co-když-je-produkt-přiřazen-do-dvou-kategorií--jedna-splňuje-omezení-a-druhá-ne).