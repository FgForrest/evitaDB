---
title: Tisíce integračních testů za méně než 30 sekund? Ano, je to možné.
perex: Rychlý testovací balík je klíčovým aspektem, který motivuje vývojáře psát více testů a spouštět je častěji. Ideální testovací balík by měl skončit během několika sekund nebo v řádu jednotek minut.
date: '25.3.2023'
author: Ing. Jan Novotný
motive: ../en/assets/images/04-blazing-fast-integration-tests.png
proofreading: needed
commit: f640dfcd764f7ef87a771ce62b6f437743278ace
---
Tento požadavek je snadno splněn pomocí čistých jednotkových testů, které nijak neinteragují s prostředím. Když však testy zahrnují komunikaci s externím systémem, například databází, je tuto podmínku často nemožné udržet. Než budete číst dál, zeptejte se sami sebe: jak dlouho běží vaše integrační testy?

Testovací sada evitaDB (více než 2700 testů), včetně integračních, běží na vývojářských noteboocích se 6 fyzickými CPU (12 vláken) přibližně 30 sekund. CPU na mém vývojářském stroji je `Intel(R) Core(TM) i7-10750H @ 2.60GHz`. Testy využívají všech **6 CPU** a vytvářejí **65 instancí databáze**, přičemž **13 běží paralelně** vedle sebe v největším zatížení, vkládají téměř **9 000 entit** do databází (**asi 50 MB, 100 tisíc záznamů**), přistupují k webovému API na **31 portech**, generují samopodepsané SSL certifikáty a testují paralelně z HTTP klientů přes šifrovaný protokol. Pokud mi nevěříte, podívejte se na video níže:

<p>
    <video width="850" height="478" controls="controls">
      <source src="https://evitadb.io/download/automated_testing.mp4" type="video/mp4"/>
        Váš prohlížeč nepodporuje video tag.
    </video>
</p>

**Nabízí se zřejmá otázka:**

## Jak jsme takových výsledků dosáhli?

EvitaDB má dvě hlavní výhody:

1. je to in-memory databáze, která je přirozeně velmi rychlá
2. je to lehká embedded databáze, kterou lze spustit během okamžiku

Ani jedna z těchto výhod však sama o sobě nezajistí výsledek, který jste viděli na obrazovce.

Používáme experimentální funkci 
[JUnit 5 Parallel Tests](https://junit.org/junit5/docs/snapshot/user-guide/#writing-tests-parallel-execution), 
která nám umožňuje spouštět testy plnou rychlostí na všech CPU hostitele. Protože evitaDB je převážně in-memory databáze,
využíváme CPU téměř na plnou kapacitu a I/O není překážkou. Pokud by testy běžely pouze v jednom vlákně, jejich spuštění by trvalo přibližně 90 sekund.

Povolení paralelních testů je otázkou několika řádků v souboru 
<SourceClass>evita_functional_tests/src/test/resources/junit-platform.properties</SourceClass>. 
Nejtěžší je napsat testy tak, aby bylo možné je spouštět paralelně.

## Principy rychlých paralelních integračních testů

Ve všech integračních sadách, bez ohledu na použitou technologii nebo databázi, existují implicitní překážky, které je třeba překonat, a principy, které je nutné dodržovat:

### Neměnná sdílená data a izolované zápisy

Aby byly integrační testy rychlé, je třeba zabránit tomu, aby každá testovací třída (nebo hůře každý testovací metod) vytvářela vlastní testovací dataset. To znamená, že je potřeba promyslet obsah datasetu tak, aby vyhovoval požadavkům co největšího počtu testů. Zároveň žádný test nesmí sdílená data v takovém datasetu měnit, nebo to musí dělat [způsobem, který neovlivní ostatní testy](http://xunitpatterns.com/Transaction%20Rollback%20Teardown.html).

### Více datasetů současně

První princip je v rozsáhlém týmu těžké udržet. Je těžké to samé zajistit i v jednom člověku po delší dobu, jak se vyvíjí kód, který píšete. Nové funkce vyžadují jiné složení datasetu a nechcete přepisovat starší testy. Vytvoření dalšího datasetu je přirozený a snadný způsob, jak testovat novou funkcionalitu systému s minimálními náklady.

Protože chceme testy spouštět paralelně, nemůžeme snadno řídit pořadí, ve kterém budou testy spuštěny. Snadno se může stát, že nejprve testy vyžadují dataset `A`, poté dataset `B` a pak JUnit framework opět spustí testy pracující s datasetem `A`.

Musíme být schopni provozovat více datasetů současně, aniž by se navzájem ovlivňovaly. Lze to udělat s běžnou databází? Pravděpodobně ano, ale za cenu značné režie. Pokud spouštíte databázový engine v Dockeru, můžete dynamicky spouštět nové instance kontejneru. Můžete také vytvořit nové databázové schéma ve stejném engine a zajistit, aby vaše aplikace používala správné schéma v konkrétní testovací metodě. Obě možnosti mají svá úskalí, ať už jde o spotřebu zdrojů, synchronizační problémy nebo složitost implementace.

### Udržujte kontrolu nad bojištěm

Psát paralelní aplikace je [samo o sobě složité](https://www.cs.cmu.edu/~jurgend/thesis/intro/node2.html). Je potřeba zajistit jednoduchý a předvídatelný mechanismus pro práci s datasety, aby se vývojáři používající tento mechanismus neztratili, měli kontrolu nad testy a vždy mohli zjistit, proč test selhal, pokud k tomu dojde.

Čím více překážek vaše databáze klade do cesty souběžnému testování, tím sofistikovanější a složitější konstrukce budete muset vymýšlet, abyste je překonali, a tím těžší bude pro vývojáře pochopit „zvláštní“ důvody selhání testů.

## Jak to řeší testovací sada evitaDB?

### Žádné sdílené úložiště

evitaDB ukládá svá data do lokálního souborového systému. Testovací datasety jsou ukládány do dočasné složky operačního systému – každá instance datasetu do vlastního podadresáře s náhodně generovaným názvem. Když spustíte naši testovací sadu, můžete pozorovat, jak se v adresáři `/tmp/evita` objevují a mizí různé podadresáře.

Stejný princip je použit i pro webový server evitaDB, který generuje samopodepsanou 
<Term location="/documentation/user/en/operate/tls.md">certifikační autoritu</Term> a serverové a klientské 
<Term location="/documentation/user/en/operate/tls.md" name="certificate">certifikáty</Term>. 
Všechny jsou uloženy v náhodně pojmenované složce, která je izolovaná od ostatních instancí.

evitaDB klient, který musí projít [mTLS ověřením](https://evitadb.io/documentation/operate/tls?mutual-tls)
a stáhnout obecný klientský certifikát, ukládá 
<Term location="/documentation/user/en/operate/tls.md" name="certificate">certifikáty</Term> do samostatné izolované složky.

Bez tohoto principu by některé testy mohly (a budou) přepisovat obsah datasetu/certifikátu, zatímco jiné testy běžící paralelně jej stále používají.

#### Správa portů

Během testů může běžet několik instancí evitaDB současně a některé z nich potřebují otevřít webová API, která jsou testována. Logicky tedy potřebujeme spravovat seznam síťových portů, které používá každá z instancí evitaDB, protože na jednom portu může naslouchat pouze jeden webový server. Tuto logiku řeší třída 
<SourceClass>evita_test_support/src/main/java/io/evitadb/test/PortManager.java</SourceClass>, která udržuje seznam portů používaných jednotlivými testovacími datasety a eviduje uvolněné porty při zničení datasetu.

### Žádný globálně sdílený stav

Žádná část kódu evitaDB nesmí používat tzv. [singletony](https://www.baeldung.com/java-singleton) nebo měnitelné statické proměnné. To platí nejen pro produkční kód, ale i pro celý testovací stack a implementace testů. Ačkoliv to může znít jednoduše, často je to těžké, pokud nemáte kontrolu nad celým stackem. Skutečnost, že naše testovací sada je schopna běžet masivně paralelně, je důkazem, že veškerá logika evitaDB je správně zapouzdřena a izolována v instancích tříd.

## Další krok je na vás

Dobrou zprávou je, že stejnou testovací podporu můžete využít i ve svých vlastních integračních testech s evitaDB.
Přečtěte si [naši dokumentaci](https://evitadb.io/documentation/use/api/write-tests?lang=java) a replikujte náš přístup ve svých integračních testech. Udržujte čas potřebný ke spuštění vaší integrační testovací sady na minimu a užijte si pohodlí spouštění všech testů lokálně po každé změně vašeho aplikačního kódu.