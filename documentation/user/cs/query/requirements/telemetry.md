---
title: Telemetrie
date: '7.12.2023'
perex: Když provozujete komplexní databázový systém, často potřebujete vědět, co se děje pod povrchem databázového enginu, abyste mohli optimalizovat své dotazy a podobně. Telemetrie je sada nástrojů, která vám pomáhá pochopit, jak jsou vaše akce plánovány a prováděny.
author: Bc. Lukáš Hornych
proofreading: done
preferredLang: evitaql
translated: 'true'
commit: ffba0d786ce7396025f98b7705b6054bb9df2887
---
## Telemetrie dotazu

<LS to="e,j,r,c">

```evitaql-syntax
queryTelemetry(
    argument:enum(TIMINGS|PLAN)
)
```

<dl>
	<dt>argument:enum(TIMINGS|PLAN)</dt>
	<dd>
		Jak podrobně má být profilování provedeno, `TIMINGS` je výchozí a implicitní argument — `queryTelemetry()`
		a `queryTelemetry(TIMINGS)` jsou stejným omezením a oba se zobrazují jako první varianta.
		`PLAN` navíc vrací plán vzorce, který engine dotazu sestavil — viz
		<LS to="j,e,r">[plán vzorce](#plán-vzorce)</LS><LS to="c">plán vzorce</LS> níže. Jedná se o dvě úrovně, nikoliv příznaky: profil obsahuje pouze časy, nebo časy *a* plán.
	</dd>
</dl>

</LS>

Požadavek <LS to="j,e,r"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/QueryTelemetry.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/QueryTelemetry.cs</SourceClass> požadavek</LS>
<LS to="g">pole `queryTelemetry` v extra výsledcích</LS>
požaduje vypočítanou telemetrii dotazu pro aktuální dotaz. Telemetrie obsahuje podrobné informace o čase zpracování dotazu
a jeho rozložení na jednotlivé operace.

Objekt telemetrie dotazu reprezentuje jednu provedenou operaci s případně vnořenými dalšími operacemi a skládá se z
následujících údajů:

<dl>
	<dt>operation</dt>
	<dd>
		Fáze provádění dotazu.
		Možné hodnoty lze nalézt ve třídě <LS to="j,e,r,g"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/extraResult/QueryTelemetry.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/ExtraResults/QueryTelemetry.cs</SourceClass></LS>.
	</dd>
	<dt>start</dt>
	<dd>
		Kdy tento krok začal, v nanosekundách. Toto <strong>není</strong> časová značka reálného času a nikdy by neměla být
		zobrazena jako datum.
		<LS to="j,e,c">Vloženě jde o surový monotónní čítač bez definované epochy — má smysl pouze
		ve vztahu k jinému odečtu provedenému ve stejné JVM.</LS>
		<LS to="g,r">Jde o počet nanosekund, které uplynuly od začátku kořenového kroku stromu, takže kořen
		vždy hlásí <code>0</code>.</LS>
	</dd>
	<dt>startedAt</dt>
	<dd>
		Okamžik reálného času, kdy dotaz začal. Uchovává pouze <strong>kořenový</strong> krok — je to to, co
		ukotvuje celý strom v čase, takže profil lze korelovat s logy, trasami nebo jiným dotazem. Každý další
		uzel hlásí <code>null</code> a jeho vlastní pozice v reálném čase je <code>startedAt</code> plus offset
		<code>start</code> tohoto uzlu.
	</dd>
	<LS to="j,e,c,r">
	<dt>steps</dt>
	<dd>
		Vnitřní kroky tohoto kroku telemetrie (dekompozice operace). Stejná struktura jako nadřazený objekt telemetrie.
	</dd>
	</LS>
	<LS to="g">
	<dt>level</dt>
	<dd>
		Hloubka tohoto kroku v profilu, kořen je vždy <code>1</code>. GraphQL vrací profil jako
		<strong>plochý seznam</strong> místo vnořeného stromu — viz poznámku níže — a toto je vlastnost,
		která nese strukturu. Kroky přicházejí v pre-order pořadí, takže rodičem každého kroku je nejbližší předchozí
		krok s nižší hodnotou <code>level</code>.
	</dd>
	<dt>stepsCount</dt>
	<dd>
		Počet přímých podkroků, na které se tento krok rozpadl, takže list lze rozpoznat bez nutnosti nahlížet
		dopředu v seznamu. Oprávněně může být <code>0</code> i na kořeni, u dotazu, jehož plánování bylo zkráceno.
	</dd>
	</LS>
	<dt>arguments</dt>
	<dd>
		Argumenty fáze zpracování — například, který index byl vybrán a s jakými odhadovanými náklady.
	</dd>
	<dt>spentTime</dt>
	<dd>
		Doba trvání v nanosekundách, zahrnující tento krok a vše vnořené pod ním.
	</dd>
	<LS to="g,r">
	<dt>selfTime</dt>
	<dd>
		Doba v nanosekundách, kterou tento krok strávil vlastní činností — jeho <code>strávený čas</code> minus čas
		připsaný jeho přímým potomkům. <code>strávený čas</code> rodiče <strong>není</strong> součtem jeho
		potomků, takže toto číslo říká, kolik z fáze je samotná fáze a ne fáze uvnitř ní.
	</dd>
	<dt>formattedSpentTime, formattedSelfTime</dt>
	<dd>
		Obě tyto doby trvání ve formátu čitelném pro člověka (např. <code>16,6 ms</code>), takže klient si je nemusí
		formátovat sám.
	</dd>
	</LS>
	<LS to="j,e,g,r">
	<dt>plan</dt>
	<dd>
		Struktura vzorce, který engine pro tuto fázi sestavil — viz
		<LS to="j,e,r">[plán vzorce](#plán-vzorce)</LS><LS to="g">plán vzorce</LS> níže. Přítomno
		pouze pokud byl dotaz na plán vznesen, a pak pouze na fázích, které vlastní vzorec.
	</dd>
	<dt>metrics</dt>
	<dd>
		Typované číselné metriky, které engine vypočítal při zodpovídání dotazu. Zatímco výše uvedené doby říkají
		<em>kam</em> čas šel, tyto říkají <em>proč</em> — viz tabulka níže. Zaznamenáno pouze na <strong>kořenovém</strong>
		kroku, takže každý jiný uzel hlásí <LS to="j,e">žádné metriky vůbec</LS><LS to="g,r"><code>null</code></LS>.
	</dd>
	</LS>
</dl>

<LS to="j,e,g,r">

Samotné metriky:

| Metrika | Význam |
|---|---|
| `estimatedCardinality` | Kolik záznamů **očekával** plánovač, že filtr odpovídá |
| `actualCardinality` | Kolik záznamů filtr **opravdu** odpovídal, před stránkováním |
| `estimatedCost` | Náklady, které plánovač odhadl pro zvolený vzorec |
| `actualCost` | Skutečné náklady tohoto vzorce po jeho spuštění |
| `recordsReturned` | Kolik záznamů bylo vráceno, tj. velikost požadované stránky |
| `ioFetchCount` | Kolikrát bylo čteno ze storage při sestavování odpovědi |
| `ioFetchedSizeBytes` | Kolik bajtů bylo přečteno ze storage |
| `prefetched` | Zda plánovač filtroval nad přednačtenými entitami místo konzultace indexů — přečtěte si toto před interpretací plánu, viz níže |

První dvojice, na kterou se vyplatí podívat, je `estimatedCardinality` oproti `actualCardinality`. Odhad, který se
liší o řády, je *důvod*, proč engine zvolil právě tento index, a je to obvyklé vysvětlení plánu, který vypadá špatně —
žádné množství časových údajů to neodhalí. `estimatedCost` a `actualCost` jsou stejným porovnáním na vlastní
bezejmenné škále plánovače: jsou srovnatelné mezi plány stejného dotazu, v absolutních číslech ale nemají význam.

<Note type="warning">

Každá metrika je **volitelná a chybějící není nula**. Metrika je zaznamenána tam, kde engine dané číslo vypočítá,
takže její absence znamená "neměřeno pro tuto fázi" — což je záměrně jiné než změřená hodnota `0`. Některé z těchto
metrik mohou být oprávněně nulové: dotaz zodpovězený pouze z indexů opravdu provede `ioFetchCount: 0` čtení ze storage.
Klient, který chybějící metriky nastaví na nulu, bude hlásit dotaz, který nic nenačetl, jako dotaz, který nic nenašel.

</Note>

</LS>

<LS to="g">

<Note type="info">

**GraphQL vrací profil zploštělý.** Ostatní API vnořují podkroky každého kroku do něj; GraphQL vrací
jediný seznam kroků v pre-order pořadí, každý s vlastností `level`. Důvodem je, že v GraphQL rozhoduje *klient*,
jak hluboko vybírá, takže vnořené pole `steps` by vás nutilo psát výběrovou množinu hlubokou tolik, jak hluboký
dotaz kdy očekáváte profilovat — a tiše by ořízlo vše hlubší. Plochý seznam nemá žádný limit hloubky a už má tvar,
který konzumuje flame chart. Extra výsledek `hierarchy` dělá totéž ze stejného důvodu.

Strom zrekonstruujte tak, že projdete seznam a každý krok připojíte k nejbližšímu předchozímu kroku s nižší
hodnotou `level`.

Všimněte si také, že doby v nanosekundách přicházejí jako **řetězce**, nikoliv čísla: `Long` je v tomto API vlastní
skalární typ, aby hodnoty přesahující bezpečný rozsah celých čísel v JavaScriptu zůstaly zachovány.

</Note>

</LS>

<LS to="r">

<Note type="warning">

**Tvar požadavku se změnil.** `queryTelemetry` se dříve zapisovalo jako `"queryTelemetry": true`, což je tvar,
který používá omezení bez argumentů. Nyní, když nese argument, je publikováno jako holá hodnota tohoto argumentu:

```json
"queryTelemetry": "TIMINGS"   // pouze časy — ekvivalent starého `true`
"queryTelemetry": "PLAN"      // časy plus plán vzorce
```

Jde o záměrnou nekompatibilní změnu, provedenou v době, kdy na starém tvaru ještě nic nezávisí, místo aby se
vrstvilo kompatibilní řešení na tvar, který by se stejně musel změnit.

</Note>

</LS>

<LS to="j,e,g,r">

## Plán vzorce

Časování říká, *kde* se dotaz spotřeboval; plán říká, *co dělal*. Požádejte o něj parametrizací
omezení — <LS to="j,e">`queryTelemetry(PLAN)`</LS><LS to="r">`"queryTelemetry": "PLAN"`</LS><LS to="g">výběrem
pole `plan`, což je způsob, jakým toto API volbu aktivuje</LS> — a kroky, které vlastní vzorec, navíc nesou
strukturu tohoto vzorce:

- každá **alternativa výběru indexu** nese kandidáta, kterého plánovač ocenil, *včetně těch, které prohrály*
- **kořen** nese plán, který byl skutečně proveden

První bod je ten, který stojí za to. Každý engine vám řekne, co udělal; málokterý vám řekne, co zvažoval a odmítl,
a s jakými odhadovanými náklady. To jsou informace, které vysvětlují plán, který vypadá špatně.

Každý uzel plánu uvádí:

| Vlastnost | Význam |
|---|---|
| `id` | Identita **instance** vzorce, stabilní napříč jeho výskyty v plánu |
| `refTo` | Nastaveno pouze při opakovaném výskytu, odkazuje zpět na `id`, který jej popisuje |
| `hash` | Strukturální hash — podle něj se klíčuje cache |
| `description` | Co vzorec je, v podobě čitelné pro člověka |
| `estimatedCost` | Co plánovač očekával, že tato část bude stát |
| `actualCost` | Co to skutečně stálo, nebo **chybí**, pokud nikdy nebylo spuštěno |
| `resultCount` | Kolik záznamů vyprodukovalo, nebo **chybí**, pokud nikdy nebylo spuštěno |

<Note type="info">

**Proč existuje `refTo`.** Plán je orientovaný acyklický graf, ne strom: výsledek vzorce je memoizován na
*instanci*, takže podvzorec dosažitelný dvěma cestami je spočítán **jednou** a každý další výskyt je zdarma.
Bez zpětného odkazu byste viděli stejný drahý podstrom dvakrát a logicky byste usoudili, že stál dvakrát tolik.
Uzel s nastaveným `refTo` nenese žádné detaily ani potomky — rozlište jej podle uzlu s tímto `id`.

</Note>

<Note type="warning">

**Chybějící `actualCost` není nulový náklad — znamená to, že vzorec nikdy neběžel.** Plánovač ocení každý kandidátní
index, ale provede pouze vítěze, takže odmítnutá alternativa oprávněně nehlásí žádné skutečné náklady, stejně jako
větev vítězného plánu, která byla přeskočena.

Existuje i třetí případ, a ten je nejčastěji špatně interpretován: když plánovač rozhodne, že je levnější načíst
malý počet entit a filtrovat nad nimi, uzel popsaný jako `APPLY PREDICATE ON PREFETCHED ENTITIES IF POSSIBLE`
zodpoví dotaz z načtených entit a **nikdy nevyhodnotí indexovou větev pod ním**. Celý tento podstrom je tedy
hlášen bez `actualCost` a `resultCount`, uvnitř plánu, který skutečně běžel. Metrika, která vám toto říká, je
`prefetched` — zkontrolujte ji, než usoudíte, že velká část vašeho plánu byla přeskočena z jiného důvodu.

To je záměrné a je to důvod, proč je vykreslení plánu bezpečné: **renderer nikdy nic nepočítá.** Kdyby volal
`compute()` pro doplnění těchto polí, požadavek na profil by spustil plány, které engine rozhodl přeskočit —
telemetrie by přestala dotaz pouze sledovat a začala by jej měnit.

Všimněte si také, že požadavek na plán **změní i samotná čísla profilu**, protože vykreslení probíhá uvnitř dotazu,
který je měřen. Spuštění s plánem tedy není přímo srovnatelné se spuštěním bez něj; očekávaný postup je dotaz
spustit znovu pro hlubší pohled.

</Note>

</LS>

<Note type="warning">

Sada fází **není** zaručena. Dotaz, jehož výběr indexu je zkrácen, nebo dry run, může oprávněně vrátit pouze
kořenový krok bez potomků — klienti to musí tolerovat a ne předpokládat pevný tvar stromu.

Všimněte si také, že s povolenou telemetrií nejsou absolutní čísla **produkční latence**: instrumentace každé fáze
něco stojí a tento náklad je zahrnut v tom, co čtete. Profil použijte k určení, kam čas v rámci dotazu plyne,
ne k citování absolutní hodnoty.

</Note>

Pro demonstraci informací, které telemetrie dotazu poskytuje, použijeme následující dotaz, který filtruje a řadí
entity:

<SourceCodeTabs requires="evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad dotazu pro výpočet telemetrie dotazu při komplexním filtrování a řazení](/documentation/user/en/query/requirements/examples/telemetry/queryTelemetry.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledná telemetrie dotazu pro filtrované a seřazené entity

</NoteTitle>

Výsledek obsahuje telemetrii dotazu a některé produkty (které jsme zde pro stručnost vynechali):

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.QueryTelemetry">[Výsledná telemetrie dotazu pro filtrované a seřazené entity](/documentation/user/en/query/requirements/examples/telemetry/queryTelemetryResult.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.queryTelemetry">[Výsledná telemetrie dotazu pro filtrované a seřazené entity](/documentation/user/en/query/requirements/examples/telemetry/queryTelemetryResult.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.queryTelemetry">[Výsledná telemetrie dotazu pro filtrované a seřazené entity](/documentation/user/en/query/requirements/examples/telemetry/queryTelemetryResult.rest.json.md)</MDInclude>

</LS>

</Note>