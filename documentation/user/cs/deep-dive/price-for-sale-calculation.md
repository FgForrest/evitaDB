---
title: Algoritmus výpočtu prodejní ceny
date: '7.11.2023'
perex: Tato kapitola podrobně popisuje algoritmus výpočtu prodejní ceny, přičemž zkoumá, jak zohledňuje faktory jako výběr měny, uplatnitelné slevy a volbu ceníku na základě uživatelského kontextu. Projdeme si logiku pomocí ukázek kódu a reálných scénářů, abychom poskytli jasné pochopení toho, jak algoritmus funguje pro přesný výpočet prodejních cen v dynamickém prostředí e-commerce.
author: Ing. Jan Novotný
proofreading: needed
commit: '3ba6b0125c098b31c0b47f60be780ef4f90fd5f1'
---
<UsedTerms>
    <h4>Použité pojmy v tomto dokumentu</h4>
    <dl>
        <dt>ERP</dt>
        <dd>
            Enterprise Resource Planning (ERP) je typ softwarového systému, který pomáhá organizacím automatizovat a řídit
            klíčové obchodní procesy pro optimální výkon. ERP software koordinuje tok dat mezi obchodními procesy organizace,
            poskytuje jednotný zdroj pravdy a zjednodušuje provoz napříč celým podnikem. Je schopen propojit finanční,
            dodavatelský řetězec, provoz, obchodování, reportování, výrobu a personální činnosti společnosti na jedné platformě.

            [Více informací zde](https://en.wikipedia.org/wiki/Enterprise_resource_planning)
        </dd>
        <dt>produkt</dt>
		<dd>Produkt je entita, která představuje položku prodávanou v e-shopu. Produkty tvoří samotné jádro každé e-commerce aplikace.</dd>
		<dt>produkt s variantami</dt>
		<dd>Produkt s variantami je „virtuální produkt“, který nelze zakoupit přímo. Zákazník si místo toho musí vybrat jednu z jeho variant.
			Produkty s variantami se velmi často vyskytují v e-shopech s módou, kde oblečení existuje v různých velikostech a barvách.
			Jeden produkt může mít desítky kombinací velikostí a barev. Pokud by každá kombinace představovala standardní
			[produkt](#product), výpis produktů v [kategorii](#category) a na dalších místech by se stal nepoužitelným.
			V této situaci jsou produkty s variantami velmi užitečné. Tento „virtuální produkt“ může být uveden místo variant
			a výběr varianty probíhá při vkládání zboží do [košíku](#cart). Příklad:
			Máme tričko s obrázkem jednorožce. Tričko se vyrábí v různých velikostech a barvách – konkrétně:<br/><br/>
			&ndash; velikost: S, M, L, XL, XXL<br/>
			&ndash; barva: modrá, růžová, fialová<br/><br/>
			To představuje 15 možných kombinací (variant). Protože chceme mít v nabídce pouze jedno tričko s jednorožcem,
			vytvoříme produkt s variantami a všechny kombinace variant zahrneme do tohoto virtuálního produktu.</dd>
		<dt>sada produktů</dt>
		<dd>Sada produktů je produkt, který se skládá z několika dílčích produktů, ale je zakoupen jako celek. Skutečným příkladem takové
			sady produktů je zásuvka – skládá se z těla, dvířek a úchytek. Zákazník si může dokonce vybrat, jaký typ dvířek
			nebo úchytek chce v sadě – ale vždy bude nějaká výchozí volba.<br/>
			Při zobrazování a filtrování podle sady produktů ve výpisech na e-shopu potřebujeme mít přiřazenou nějakou cenu,
			ale nemusí být přiřazena přesná cena a vlastník e-shopu očekává, že cena bude vypočítána jako agregace cen dílčích produktů.
			Toto chování je podporováno nastavením správného
			[PriceInnerEntityReferenceHandling](../../en/deep-dive/classes/price_inner_entity_reference_handling).</dd>
    </dl>

</UsedTerms>

Hlavním zdrojem informací o cenách je obvykle firemní systém <Term>ERP</Term>. Existuje široká škála takových systémů <Term>ERP</Term>, často specifických pro zemi, ve které e-commerce podnikání působí. Tyto systémy mají své vlastní způsoby modelování a výpočtu cen a B2B cenové strategie bývají někdy velmi „kreativní“.

Logika výpočtu cen v evitaDB je navržena velmi jednoduše, aby podporovala běžné cenové mechanismy a umožnila přizpůsobení i neobvyklým případům.

Struktura jednotlivé ceny je definována rozhraním
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceContract.java</SourceClass>.

[Dotaz](../query/basics.md) vám umožňuje vyhledávat ceny:

- v konkrétní měně ([`priceInCurrency`](../query/filtering/price.md#cena-v-měně))
- které jsou platné v určitém čase ([`priceValidIn`](../query/filtering/price.md#cena-platná-v))
- které patří do některých definovaných sad nebo ceníků, ke kterým má koncový uživatel
  přístup ([`priceInPriceLists`](../query/filtering/price.md#cena-v-cenících))

Zpracování takového dotazu vede k seznamu cen, kde je k jednomu produktu přiřazeno více cen. Ceny patřící ke stejnému produktu budou seřazeny podle pořadí ceníků ve
([`priceInPriceLists`](../query/filtering/price.md#cena-v-cenících)) omezení použitých v dotazu. Seřazený seznam ceníků je procházen a pro každý produkt je vybrána pouze první cena, ostatní jsou přeskočeny, dokud není nalezena cena pro další produkt. To znamená, že v daném okamžiku může být právě jedna cena platná pro kombinaci ceníku a měny. Pokud by toto omezení nebylo vynuceno, engine by nebyl schopen vybrat vhodnou prodejní cenu produktu.

<Note type="warning">

Aby se předešlo nejednoznačnosti cen, konstruktory evitaDB vynucují, aby entity měly pouze jednu platnou cenu pro každý různý ceník. Přesto může dojít k nejednoznačnosti, pokud má entita dvě ceny s nepřekrývajícími se platnostmi a v dotazu evitaDB
[dotaz](../query/basics.md) chybí omezení [`priceValidIn`](../query/filtering/price.md#cena-platná-v), které
určuje přesný čas pro správné vyhodnocení ceny. V takových situacích bude vybrána „nedefinovaná“ cena.
Proto byste měli vždy specifikovat správné datum a čas pro určení správné ceny pro prodej (pokud si nejste jisti, že v databázi nejsou časově závislé ceny).

</Note>

Je třeba pečlivě promyslet, jak v evitaDB modelovat ceníky a priority. Jedním z intuitivnějších přístupů je převést ceníky z <Term>ERP</Term> (v poměru 1:1) do evitaDB. To většinou funguje – ale systémy <Term>ERP</Term> často používají ceny, které se počítají dynamicky podle určitých pravidel. To v evitaDB není možné a všechny ceny musí být „předpočítané“ ve statické podobě. Je to nutné pro rychlé vyhledávání cen. Můžete vytvořit tzv. „virtuální ceníky“, které napodobují pravidla <Term>ERP</Term> a uchovávají v nich všechny vypočítané ceny.

<Note type="warning">

Musíte také dávat pozor na explozivní růst kombinací (někdy označovaný jako
[kartézský součin](https://en.wikipedia.org/wiki/Cartesian_product)). Některá obchodní pravidla mohou vést k tak velkému množství možných kombinací cen, že je nelze předpočítat a uložit do paměti. Podívejme se na příklad:

*Společnost XYZ má 1 milion zákazníků, 1 milion produktů a každý zákazník může mít unikátní slevu na produkty.
Naivní přístup by znamenal spočítat 1 miliardu cen (tj. všechny možné kombinace). Chytřejší přístup je podívat se na rozložení slev. Můžeme zjistit, že existuje jen několik typů slev: 1 %, 2,5 %, 5 %, 10 %. Ceníky tedy nemodelujeme podle uživatele, ale podle hodnoty slevy – a potřebujeme tedy 4 miliony předpočítaných cen*.

</Note>

## Příklady modelů pro standardní případy

Mějme následující produkty:

| Produkt       | Základní cena | Ceník A | Ceník B                                               | Ceník C |
|---------------|---------------|---------|-------------------------------------------------------|---------|
| Honor 10      | €10000        |         | €9000<br/>(platí 1.1.2020 00:00:000 - 31.1.2020 23:59:59)  | €7500   |
| HUAWEI 20 Pro | €12000        | €14000  |                                                       | €8500   |
| iPhone Xs Max | €21000        | €23000  | €19000<br/>(platí 1.1.2020 01:00:000 - 31.1.2020 22:59:59) |         |

<Note type="info">

<NoteTitle toggles="true">

##### Co když potřebujete definovat platnost celého ceníku?

</NoteTitle>

Informace o platnosti vašeho `priceList` můžete uložit do samostatné entity evitaDB. Poté můžete platnost ceníku emulovat na straně klienta předtím, než je pole kódů ceníků předáno
omezení [`priceInPriceLists`](../query/filtering/price.md#cena-v-cenících), které se používá pro získání produktů.

Logika klienta může fungovat následovně: vypište všechny uživatelské ceníky, jejichž platnost se překrývá s intervalem od teď do +1 hodiny, seřazené podle priority/atributu pořadí, uložte do cache na jednu hodinu (to může být řešeno jediným dotazem do EvitaDB).

Když se má použít omezení `priceInPriceLists` pro výpis produktů, vyfiltrujte všechny lokálně uložené ceníky podle atributu platnosti pomocí metody `isValidFor(OffsetDateTime)` pro aktuální okamžik a použijte je jako argumenty ve stejném pořadí, v jakém byly načteny do cache.

</Note>

Tyto výsledky očekáváme pro následující dotazy:

#### První dotaz

- **ceníky:** `A`, `Baseline` (pořadí argumentů určuje prioritu ceníků)
- **platné v:** `1.11.2020 13:00:00`

**Očekávaný výsledek:**

| Produkt       | Prodejní cena |
|---------------|---------------|
| Honor 10      | €10000        |
| HUAWEI 20 Pro | €14000        |
| iPhone Xs Max | €23000        |

Ceník `A` má vyšší prioritu, a proto by měly být použity ceny z tohoto ceníku, pokud jsou dostupné.
`Honor 10` nemá v tomto ceníku cenu, proto bude použita základní cena `Baseline`.

#### Druhý dotaz

- **ceníky:** `B`, `A`, `Baseline`, `C` (pořadí argumentů určuje prioritu ceníků)
- **platné v:** `1.11.2020 13:00:00`

**Očekávaný výsledek:**

| Produkt       | Prodejní cena |
|---------------|---------------|
| Honor 10      | €10000        |
| HUAWEI 20 Pro | €14000        |
| iPhone Xs Max | €23000        |

V tomto případě je ceník `B` nejvíce prioritní, ale časová platnost jeho cen neodpovídá omezením, takže žádná z nich nebude zahrnuta do vyhodnocení. Ceník `C` má nejnižší prioritu, takže pokud je cena v ceníku `Baseline` nebo `A`, nebude použita. Proto je výsledek stejný jako u prvního dotazu.

#### Třetí dotaz

- **ceníky:** `B`, `A`, `Baseline`, `C` (pořadí argumentů určuje prioritu ceníků)
- **platné v:** `2.1.2020 13:00:00`

**Očekávaný výsledek:**

| Produkt       | Prodejní cena |
|---------------|---------------|
| Honor 10      | €9000         |
| HUAWEI 20 Pro | €14000        |
| iPhone Xs Max | €19000        |

Ceník `B` je nejvyšší prioritou v tomto dotazu a platnost jeho cen odpovídá požadavku, takže je zahrnut do vyhodnocení. Ceník `C` má nejnižší prioritu, takže pokud je cena v ceníku `Baseline` nebo `A`, nebude použita.

#### Čtvrtý dotaz

- **ceníky:** `B`, `A`, `Baseline`, `C` (pořadí argumentů určuje prioritu ceníků)
- **platné v:** `2.1.2020 13:00:00`
- **cena mezi:** `€8000` a `€10000` (včetně obou hranic)

**Očekávaný výsledek:**

| Produkt  | Prodejní cena |
|----------|---------------|
| Honor 10 | €9000         |

Dotaz je stejný jako třetí dotaz s přidáním filtru cenového rozpětí. Výsledek obsahuje pouze produkt `Honor 10`, který má odpovídající cenu. Ostatní produkty mají ceny, které by odpovídaly cenovému rozpětí v jiných cenících – ale tyto ceny nebyly vybrány jako prodejní, takže nemohou být použity v predikátu cenového rozpětí.

## Rozšíření pro varianty produktů

<Term name="product with variants">Produkt s variantami</Term> musí obsahovat ceny všech svých variant. Ceny variant musí být rozlišeny podle vlastnosti id vnitřní entity (viz rozhraní
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceContract.java</SourceClass>). Produkt
s variantami musí mít režim zpracování vnitřního odkazu na cenu
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceInnerRecordHandling.java</SourceClass>
nastaven na `LOWEST_PRICE`.

V tomto nastavení produktu je prodejní cena vybrána jako nejnižší prodejní cena ze všech variant. Tato cena se používá pro filtrování produktů podle ceny.

Entita také poskytne vypočítané ceny pro každou variantu produktu, přičemž pro každý identifikátor vnitřní entity je vybrána první cena podle priority. Tato informace může být použita k zobrazení cenového rozpětí produktu s variantami (tj. cena od €5 do €6,5) nebo k výpočtu průměrné ceny prodeje všech variant produktu.

### Příklad modelu

Mějme následující produkty:

| Produkt        | Hlavní produkt     | Základní cena | Ceník A | Ceník B                                            | Ceník C |
|----------------|-------------------|---------------|---------|----------------------------------------------------|---------|
| Varianta: modrá  | T-Shirt I Rock    | €10           |         | €9<br/>(platí 1.1.2020 00:00:000 - 31.1.2020 23:59:59)  | €7.5    |
| Varianta: červená| T-Shirt I Rock    | €12           | €14     |                                                    | €8.5    |
| Varianta: zelená | T-Shirt I Rock    | €21           | €23     | €19<br/>(platí 1.1.2020 01:00:000 - 31.1.2020 22:59:59) |         |
| Varianta: modrá  | Jumper X-Mas Deer | €26           |         | €19<br/>(platí 1.1.2020 02:00:000 - 31.1.2020 21:59:59) | €9      |
| Varianta: červená| Jumper X-Mas Deer | €26           | €22     |                                                    | €9      |
| Varianta: zelená | Jumper X-Mas Deer | €26           | €21     | €18<br/>(platí 1.1.2020 03:00:000 - 31.1.2020 20:59:59) |         |

Tyto výsledky očekáváme pro následující dotazy:

#### První dotaz

- **ceníky:** `Baseline`
- **platné v:** `1.11.2020 13:00:00`

**Očekávaný výsledek:**

| Produkt           | Prodejní cena    |
|-------------------|------------------|
| T-Shirt I Rock    | od €10 do €21    |
| Jumper X-Mas Deer | €26              |

`Jumper X-Mas Deer` má jednu prodejní cenu, protože všechny jeho varianty v ceníku `Baseline` mají stejnou cenu. `T-Shirt I Rock` musí signalizovat, že jeho nejlevnější cena je `€10` a nejdražší cena je `€21`.

#### Druhý dotaz

- **ceníky:** `B`, `Baseline`, `C` (pořadí argumentů určuje prioritu ceníků)
- **platné v:** `1.11.2020 13:00:00`

**Očekávaný výsledek:**

| Produkt           | Prodejní cena    |
|-------------------|------------------|
| T-Shirt I Rock    | od €10 do €21    |
| Jumper X-Mas Deer | €26              |

Výsledek v tomto dotazu je stejný jako v prvním dotazu. Ceny v ceníku `B` nelze použít, protože jejich období platnosti není platné a ceník `C` má nejnižší prioritu, ceny v `Baseline` jej přebijí.

#### Třetí dotaz

- **ceníky:** `B`, `A`, `Baseline`, `C` (pořadí argumentů určuje prioritu ceníků)
- **platné v:** `2.1.2020 13:00:00`

**Očekávaný výsledek:**

| Produkt           | Prodejní cena    |
|-------------------|------------------|
| T-Shirt I Rock    | od €9 do €19     |
| Jumper X-Mas Deer | od €18 do €22    |

Výsledek v tomto dotazu bude určen cenami z ceníku `B`, které jsou nyní všechny platné. Ceník `C` má nejnižší prioritu a nebude použit. Ceník `Baseline` by také nebyl použit, protože všechny produkty mají cenu v některém z ceníků s vyšší prioritou – tedy v cenících `A` a `B`.

#### Čtvrtý dotaz

- **ceníky:** `B`, `A`, `Baseline`, `C` (pořadí argumentů určuje prioritu ceníků)
- **platné v:** `2.1.2020 13:00:00`
- **cenové rozpětí:** `€8` až `€11` (včetně obou hranic)

**Očekávaný výsledek:**

| Produkt        | Prodejní cena |
|----------------|---------------|
| T-Shirt I Rock | od €9 do €19  |

Dotaz je stejný jako třetí dotaz s přidáním filtru cenového rozpětí. Výsledek obsahuje pouze produkt `T-Shirt I Rock`, který má alespoň jednu variantu s cenou odpovídající cenovému rozpětí.
`Jumper X-Mas Deer` má cenu, která by odpovídala cenovému rozpětí v jiných cenících – ale tyto ceny nebyly vybrány jako prodejní a proto nemohou být zahrnuty do filtru cenového rozpětí.

## Rozšíření pro sady produktů

<Term name="product set">Sada produktů</Term> musí obsahovat ceny všech svých komponent. Ceny komponent musí být rozlišeny podle vlastnosti id vnitřní entity (viz rozhraní
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceContract.java</SourceClass>.
Záznam produktu musí mít režim zpracování vnitřního odkazu na cenu
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceInnerRecordHandling.java</SourceClass>
nastaven na `SUM`.

V tomto nastavení je prodejní cena produktu vypočítána dynamicky jako součet prodejních cen všech jeho komponent.
Tato agregovaná cena se používá pro filtrování produktů podle ceny.

<Note type="warning">

Pokud komponenta nemá pro zadaný dotaz prodejní cenu, cena sady produktů je vypočítána bez této konkrétní komponenty.

</Note>

### Příklad modelu

Mějme následující produkty:

| Produkt             | Sada produktů | Základní cena | Ceník A | Ceník B                                             | Ceník C |
|---------------------|--------------|---------------|---------|-----------------------------------------------------|---------|
| Rám                 | Zásuvka      | €100          |         | €90<br/>(platí 1.1.2020 00:00:000 - 31.1.2020 23:59:59)  | €75     |
| Sada úchytek        | Zásuvka      | €120          | €140    |                                                     | €85     |
| Panty               | Zásuvka      | €210          | €230    | €190<br/>(platí 1.1.2020 01:00:000 - 31.1.2020 22:59:59) |         |
| Rošt čela/nohy      | Postel       | €260          |         | €190<br/>(platí 1.1.2020 02:00:000 - 31.1.2020 21:59:59) | €90     |
| Tělo                | Postel       | €260          | €220    |                                                     | €90     |
| Zásuvky             | Postel       | €260          | €210    | €180<br/>(platí 1.1.2020 03:00:000 - 31.1.2020 20:59:59) |         |

Tyto výsledky očekáváme pro následující dotazy:

#### První dotaz

- **ceníky:** `Baseline`
- **platné v:** `1.11.2020 13:00:00`

**Očekávaný výsledek:**

| Produkt | Prodejní cena               |
|---------|-----------------------------|
| Zásuvka | €430 (€100 + €120 + €210)   |
| Postel  | €780 (€260 + €260 + €260)   |

Sady produktů mají prodejní cenu, která je součtem prodejních cen jejich částí.

#### Druhý dotaz

- **ceníky:** `B`, `A`, `Baseline`, `C` (pořadí argumentů určuje prioritu ceníků)
- **platné v:** `1.11.2020 13:00:00`

**Očekávaný výsledek:**

| Produkt | Prodejní cena               |
|---------|-----------------------------|
| Zásuvka | €470 (€100 + €140 + €230)   |
| Postel  | €690 (€260 + €220 + €210)   |

Ceník `B` nelze použít pro výpočet, protože žádná z jeho cen nesplňuje podmínku platnosti. Ceník `C` by byl použit pouze, pokud by nebyly ceny v ceníku `Baseline` nebo `A`, což také není splněno.
Prodejní cena je vypočítána jako součet cen v ceníku `Baseline` a cen v ceníku `A`.

#### Třetí dotaz

- **ceníky:** `B`, `A`, `Baseline`, `C` (pořadí argumentů určuje prioritu ceníků)
- **platné v:** `2.1.2020 13:00:00`

**Očekávaný výsledek:**

| Produkt | Prodejní cena               |
|---------|-----------------------------|
| Zásuvka | €420 (€90 + €140 + €190)    |
| Postel  | €590 (€190 + €220 + €180)   |

Ceník `B` lze nyní použít, protože jeho podmínka platnosti je splněna cenami v tomto ceníku. Části, které nemají cenu v ceníku `B`, použijí cenu z druhého nejprioritnějšího ceníku – ceníku `A`.

#### Čtvrtý dotaz

- **ceníky:** `B`, `A`, `Baseline`, `C` (pořadí argumentů určuje prioritu ceníků)
- **platné v:** `2.1.2020 13:00:00`
- **cena mezi:** `€0` a `€500`

**Očekávaný výsledek:**

| Produkt | Prodejní cena              |
|---------|----------------------------|
| Zásuvka | €420 (€90 + €140 + €190)   |

Dotaz je stejný jako třetí dotaz s přidáním filtru cenového rozpětí. Výsledek bude obsahovat pouze produkt `Zásuvka`, jehož součet prodejních cen částí je v zadaném rozmezí.