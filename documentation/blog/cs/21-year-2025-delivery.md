---
title: Co nového přinesl rok 2025
perex: |
  Konec roku je ideální čas ohlédnout se za tím, kam se v minulém roce podařilo evitaDB posunout. Klíčovým tématem letošního roku byla především stabilita a adaptace na potřeby z produkčních nasazení, kterých je stále víc a víc.
date: '30.12.2025'
author: 'Jan Novotný'
motive: assets/images/21-year-2025-delivery.png
proofreading: 'done'
---

Rekapitulaci rozdělím na několik tématických okruhů:

1. Vylepšení a nové funkce:
   1. [jádra evitaDB](#rozvoj-jadra-evitadb)
   2. [evitaLab](#posun-v-oblasti-evitalab-konzole)
   3. [infrastrukturní a architektonické změny](#infrastrukturní-a-architektonické-změny)
2. [Optimalizace a stabilita](#optimalizace-a-stabilita)
3. [Zamyšlení do nového roku 2026](#zamyšlení-do-nového-roku-2026)

V letošním roce jsme uzavřeli 92 ticketů a vydali 8 major verzí databáze. Ačkoliv došlo k zásadním změnám ve způsobu uložení dat na disku, je stále možné, díky automatickým migracím dat, bezproblémově upgradovat i z verzí vydaných v roce 2024. Pro tyto účely existují i automatizované testy, které proces migrace ověřují.

## Rozvoj jádra evitaDB

### Vícenásobné reference na stejnou entitu

Tato funkcionalita představovala největší přepracování jádra v letošním roce. V celé nahotě ukazuje, jaký dopad má porušení jednoho z klíčových předpokladů na implementační složitost. Při návrhu datového modelu se předpokládalo, že jedna konkrétní entita může jinou entitu v rámci jedné pojmenované reference odkazovat pouze jednou. Pokud by vyvstala potřeba mít v jedné referenci více odkazů na stejnou entitu, vytvoří se nový referenční typ. Lepší světlo na tuto mechaniku nám poskytne konkrétní příklad:

> Mějme produkt (entita *Product*) s názvem *Vánoční stromeček*. Tento produkt má své několik fotografií, které jsou reprezentovány entitou *Image*. Jedna z fotografií je použitá jako hlavní obrázek produktu a používá se typicky ve výpisech produktů. Další fotografie jsou pak použity v galerii produktu. Problém nastává, když chceme, aby hlavní obrázek byl zároveň i součástí galerie.

Původní návrh počítal s tím, že entita *Product* bude mít dva typy referencí: *mainImage* a *galleryImages*. Tím bude možné stejnou fotografii odkazovat v rámci stejného produktu dvakrát, ale pod různými referenčními typy. Tento přístup se však v praxi ukázal jako nepraktický, a to především proto, že názvy referencí jsou například v rámci GraphQL či OpenAPI schématu součástí názvu polí. V praxi jsou tyto názvy často „konfigurovatelné“ a mění se v rámci různých klientských projektů, což následně znamená nutnost úprav dotazů, které jsou zpravidla ve formě předpřipravených šablon. Z tohoto důvodu je mnohem praktičtější se k těmto „diskriminátorům“ chovat skutečně jako k atributům reference a ne jako k samostatným referenčním typům. Tím se přesunou z názvu polí do filtrů a vstupních parametrů, kde je dynamičnost již předpokládána.

Původní rozhodnutí nebylo čistě náhodné – umožňovalo totiž řadu optimalizací a celkově mnohem jednodušší implementaci. Odstranění tohoto předpokladu si vyžádalo rozsáhlé změny v celé architektuře databáze, včetně způsobu indexace, ukládání dat na disk a zpracování dotazů. Bylo nutné zavést nové datové struktury, které umožňují rozlišovat reference na základě unikátní kombinace „referenčních atributů“, které tvoří diskriminátory (na úrovni reference totiž není možné pracovat s žádným jiným jednoduchým primárním klíčem).

Jedním z výstupů této změny je i velmi intuitivní a praktické API pro práci s vícenásobnými referencemi v rámci dotazů:

```graphql
query {
  queryProduct(
    filterBy: {
      attributeCodeInSet: ["lenovo-yoga-tab-11", "samsung-galaxy-tab-s8-12-4-2022"]
    }
  ) {
    recordPage {
      data {
        mainMotive: media(filterBy: {attributeGalleryEquals: "hlavni-motiv"}) {
          ...media
        }
        gallery: media(filterBy: {attributeGalleryEquals: "galerie"}) {
          ...media
        }
      }
    }
  }
}

fragment media on ProductMediaReference {
  referencedEntity {
    attributes {
      fileName
      fileSize
      mediaUrl
    }
  }
}
```

Klíčovou součástí implementace bylo, kromě doplnění celé spousty nových testů, i implementace fuzzy testu, který simuluje náhodné operace s databází a následně ověřuje konzistenci dat. Vyladění všech okrajových situací, které není prakticky možné pokrýt běžnými testy, zabralo několik týdnů intenzivní práce.

### Kontrola průchodu hierarchickými referencemi

E-commerce systémy typicky operují v rámci nějaké hierarchické struktury (kategorie produktů, organizační struktura apod.). evitaDB pro tyto účely umožňuje definovat entity jako hierarchické a nabízí řadu speciálních operátorů pro filtraci a řazení dat v rámci těchto hierarchických struktur. V případě vícenásobných referencí (0..N) však vyvstává otázka, v jakém pořadí se mají tyto vícenásobné vazby procházet a zda má být průchod do šířky (tj. nejprve všechny přímé děti, poté děti dětí atd.) či do hloubky (tj. nejprve nejhlubší úroveň, poté úroveň nad ní atd.). Nyní je možné všechny tyto režimy explicitně řídit pomocí nových operátorů v dotazovacím jazyce a mít tak vše plně pod kontrolou. Detailněji si o tom můžete přečíst v [tomto článku](https://evitadb.io/blog/17-one-to-many-references).

### Integrované vyhledávání v hierarchii do souhrnu s facety

S hierarchiemi souvisí i další zajímavá novinka, a tou je integrace hierarchického vyhledávání do facetového filtru. Místo složitého vysvětlování je lepší si prohlédnout praktickou ukázku použití třeba na webu **Ikea.com**:

<p>
    <video width="850" height="478" controls="controls">
      <source src="https://evitadb.io/download/blog-21-hierarchy-in-facet-filter.webm" type="video/mp4"/>
        Your browser does not support the video tag.
    </video>
</p>

V rámci datové struktury facetového filtru umožňuje evitaDB nyní vrátit vypočtenou hierarchii, ve které byly nalezeny entity odpovídající zadaným kritériím. Jednotlivé uzly v této struktuře obsahují souhrnnou informaci o počtu nalezených entit, která správně reflektuje i vícenásobné zařazení té stejné entity v rámci dané hierarchie (tj. v součtech je započtena na každé úrovni pouze jednou).

### Načtení počtu referenčních entit bez načítání jejich obsahu

Pomalu se dostáváme k drobnějším, ale neméně užitečným vylepšením. Jedním z nich je možnost načíst si počet referencí na jiné entity bez nutnosti načítat jejich obsah. Tento přístup šetří paměť, výpočetní výkon i datový tok a je užitečný například pro zobrazení počtu recenzí produktu, aniž by bylo nutné načítat recenze samotné. Častým scénářem pro uživatelské rozhraní je totiž ověření existence alespoň jedné reference daného typu (například zda produkt má alespoň jednu recenzi) a následné vykreslení nebo naopak skrytí příslušného UI prvku (tlačítko pro zobrazení recenzí apod.).

### Doplnění chybějícího operátoru - pokud alespoň jedno z dětí splňuje podmínku

Při procházení hierarchických struktur bylo dosud možné omezit průchod přes rodiče, kteří splňují konkrétní podmínku. Například „procházej všechny kategorie, které mají stav ‚aktivní‘“. V praxi nastávají situace, které je možné elegantně vyřešit novým operátorem umožňujícím omezit průchod přes rodiče, pokud alespoň jedno z jejich dětí splňuje zadanou podmínku. Příkladem z praxe je třeba výpis stromu kategorií, kde některé z konkrétních podřízených kategorií jsou označeny speciálním štítkem. Správa štítků na koncových uzlech je pro uživatele velmi jednoduchá a zároveň je aplikace schopná zobrazit konzistentní strom kategorií od kořenového uzlu až po konkrétní označené kategorie. Nový operátor je [zdokumentován v příslušné sekci](https://evitadb.io/documentation/query/filtering/hierarchy?lang=evitaql#any-having).

### Výchozí nastavení pro doprovodnou cenu

Dalším drobným vylepšením je možnost definovat výchozí hodnoty pro výpočet doprovodných cen (například referenční/obvyklé ceny) hromadně pro celý dotaz. Ceny se v rámci jednoho dotazu počítají na řadě míst – kromě cen na hlavní entitě (například produktu) se počítají i na dalších provázaných entitách (například alternativní produkty, doplňkové služby apod.). U prodejní ceny se seznam ceníků, měna a další parametry odvozují z hlavní filtrovací podmínky, ale pro doprovodné ceny bylo nutné tyto parametry vždy specifikovat v místě použití. Nyní je možné je definovat jednou na úrovni celého dotazu a ušetřit si tak opakované psaní stejných hodnot. Nová syntaxe je popsána v [dokumentaci k cenám](https://evitadb.io/documentation/query/requirements/price?lang=evitaql#default-accompanying-price-lists).

## Posun v oblasti evitaLab

### Záznam a analýza provozu

Na začátku roku jsme v jádru databáze umožnili zaznamenávat dotazy (včetně možnosti samplingu), které na ni míří. Záznam z provozu se provádí asynchronně a je implementován tak, aby neovlivňoval latenci ani propustnost databáze. V rámci uživatelského rozhraní konzole evitaLab jsme pak zpřístupnili sekci pro analýzu těchto dotazů. V této sekci je možné procházet jednotlivé zaznamenané dotazy, filtrovat je podle různých kritérií (čas, typ dotazu, doba zpracování, počet vrácených záznamů apod.) a analyzovat jejich strukturu. Každý dotaz je možné si ihned nechat vykonat v rámci konzole a prozkoumat jeho výsledky nebo plán exekuce. Tato funkce je velmi užitečná pro ladění výkonu dotazů a identifikaci potenciálních problémů v dotazech, které mohou negativně ovlivňovat výkon databáze. Zároveň se dotaz zaznamenává jak v jeho původní formě (např. GraphQL), tak i v přeložené formě (evitaQL), což umožňuje detailní analýzu a optimalizaci na různých úrovních. Více o této funkci si můžete přečíst v [příslušném blogovém článku](https://evitadb.io/blog/16-traffic-recording).

### Aktualizace UI a opravy chyb

V létě došlo k velké aktualizaci uživatelského rozhraní konzole evitaLab, která přinesla spoustu oprav drobných chyb a také se podařilo dohnat technologický dluh způsobený tím, že některé nové funkce jádra evitaDB nebyly v konzoli dostupné. V rámci schématu jsou nyní správně vizualizované kardinality, vícenásobné reference a vizualizace indexů v rámci odlišných scope. V entity gridu je nově vizualizovaný scope u jednotlivých entit a je možné podle něj i filtrovat. Syntax highlighter a našeptávač rozpoznávají i nové operátory a funkce dotazovacího jazyka.

### Nová desktopová aplikace

Zároveň jsme představili i zcela novou desktopovou aplikaci evitaLab, která umožňuje pohodlnou práci s databází (respektive řadou různých databází) přímo z desktopu. Aplikace je dostupná pro Windows, macOS i Linux. Umožňuje komunikovat s databázemi v různých verzích a nabízí možnost automatizované aktualizace lokálního ovladače pro konkrétní databázové spojení. O aplikaci si můžete přečíst více v [příslušném blogovém článku](https://evitadb.io/blog/18-introducing-evitalab-desktop).

### Auditní log

Poslední velkou novinkou je zpřístupnění auditních informací přímo v konzoli evitaLab. Informace jsou čerpány z Write-Ahead logu databáze a jsou tudíž přesné, nicméně hloubka auditu odpovídá času retence Write-Ahead logu v dané instanci databáze. V uživatelském rozhraní je možné snadno procházet úplnou historii transakcí, filtrovat je podle jejich typu (změny v datech / schématu), typu entity či konkrétní entity nebo její součásti (atributy, ceny, reference). Pro každou transakci je možné zobrazit detailní informace o tom, jaké změny byly provedeny. V rámci entity gridu je možné zpřístupnit historii změn konkrétní entity nebo konkrétního atributu, asociovaného údaje, reference či ceny a procházet si jednotlivé verze v čase.

## Infrastrukturní a architektonické změny

### Change Data Capture

V druhé polovině roku jsme započali přípravu na provoz v clusteru a s tím související změny v infrastruktuře a architektuře databáze. První krokem bylo zavedení Change Data Capture (CDC) mechaniky, která umožňuje sledovat změny v datech v reálném čase. Tato mechanika je klíčová pro replikaci dat mezi uzly v clusteru a pro integraci s dalšími systémy, které potřebují být informovány o změnách v datech. Více o této funkci si můžete přečíst v [příslušném blogovém článku](https://evitadb.io/blog/20-change-data-capture).

### Write-Ahead log na úrovni enginu

S tím souvisí i zavedení nového Write-Ahead logu (WAL) na úrovni celého enginu, který umožňuje zaznamenávat a replikovat systémové operace, jako je vytváření nových katalogů, jejich přejmenovávání či mazání a další operace, které nejsou přímo spojené s konkrétními daty v rámci katalogu, pro který je původní WAL určený. Tento nový WAL je klíčový pro zajištění replikace těchto systémových operací v rámci clusteru a pro zajištění konzistence dat mezi jednotlivými uzly.

### Plné zálohy katalogu

Vedle existujících typů záloh (point-in-time a snapshot) jsme přidali i možnost provádět plné zálohy (full backup) celého katalogu. Plná záloha pracuje na binární úrovni a obsahuje všechna data katalogu. Po obnově z této plné zálohy, je katalog identický s tím původním a lze nad ním provádět i point-in-time zálohy / obnovy jako na tom původním. V rámci evitaLabu je nyní možné provádět všechny typy záloh a obnov přímo z uživatelského rozhraní.

## Optimalizace a stabilita

V oblasti optimalizací a stability jsme v roce 2025 urazili velký kus cesty. Kromě opravy spousty drobných chyb, které se v průběhu roku objevily v reálných projektech, jsme provedli i několik zásadních optimalizací, které výrazně zlepšily výkon a stabilitu databáze v produkčním prostředí.

### Asynchronní operace

Většina operací v evitaDB trvá velmi krátce, nicméně některé operace, jako je například commit rozsáhlé transakce, přepnutí katalogu z WARM-UP do transakčního režimu nebo některé operace managementového charakteru, mohou trvat i několik sekund. Doba trvání je špatně predikovatelná a ovlivňuje ji řada faktorů, takže prakticky není možné vhodně nastavit timeouty na straně klienta. Z tohoto důvodu jsme v jádru databáze zavedli asynchronní režim pro většinu těchto operací, kdy je klientovi vrácena okamžitá odpověď o přijetí požadavku a samotné zpracování probíhá na pozadí. V prostředí Java klienta se jedná o návrat objektu typu Future, který umožňuje svázat dokončení operace s dalším zpracováním nebo „zaparkovat“ vlákno do doby, než je operace dokončena. Tento přístup výrazně zlepšuje odezvu aplikací komunikujících s databází, umožňuje lépe využít dostupné zdroje a zároveň zvyšuje stabilitu tím, že eliminuje problémy s timeouty. Příklad tohoto přístupu je nastíněn v článku, který popisuje [asynchronní zpracování transakcí](https://evitadb.io/blog/19-transaction-processing).

### Komprese dat na disku

Další významnou optimalizací je zavedení komprese dat na disku, která výrazně snižuje nároky na úložný prostor a zároveň zlepšuje výkon při čtení dat. Komprese je implementována na úrovni jednotlivých datových struktur a je transparentní pro uživatele. Výsledkem je menší množství dat, které je nutné číst a zapisovat na disk, což snižuje latenci I/O operací a zmenšuje nároky na údržbu. Prozatím je ke kompresi využíván standardní algoritmus Deflate (Zlib), nicméně v budoucnu plánujeme přidat i další možnosti komprese lépe optimalizované pro specifické datové struktury používané v evitaDB.

### Optimalizace indexů

Analýzou reálných projektů se v průběhu roku ukázalo, že evitaDB udržuje řadu sekundárních indexů, které aplikace ve skutečnosti vůbec nevyužívají. Bylo to způsobeno i tím, že definice schématu neumožňovala granulárnější nastavení tvorby indexů. Zavedli jsme proto možnost explicitně určit, jak široký index se má pro různé reference entit udržovat, a tím snížit režii spojenou s jejich údržbou i paměťové nároky. Tato optimalizace přinesla na některých našich projektech až 50% snížení paměťové náročnosti při zachování plné funkčnosti dotazů.

### Možnost vypnout fSync pro testovací účely

Dalším drobným vylepšením je možnost vypnout fSync v rámci commitu transakce. Toto nastavení je určeno výhradně pro testovací účely, kdy je potřeba maximalizovat výkon zápisu dat na úkor jejich trvanlivosti v případě výpadku systému. V produkčním prostředí by toto nastavení nemělo být nikdy použito, protože by mohlo vést ke ztrátě dat v případě neočekávaného výpadku, nicméně v prostředí pro automatizované testy může výrazně zrychlit jejich běh. Na našich projektech jsme zaznamenali až 40% zrychlení běhu automatizovaných testů, které opakovaně prováděly vytváření nových katalogů, vkládání dat a jejich opětovné mazání.

## Zamyšlení do nového roku 2026

V uplynulém roce jsme vydali celkem 8 verzí evitaDB a uzavřeli 92 ticketů. Rozvoj již není tak rychlý jako v předchozích letech, protože náš čas musíme dělit i na konzultace a podporu reálných projektů, které nás i rozvoj evitaDB živí. Tento trend se ani v dalších letech nezmění, protože naším cílem je především stabilní a spolehlivá databáze, která bude dobře sloužit reálným projektům.

V příštím roce bychom chtěli dokončit přípravy na provoz v clusteru a získat zkušenosti s provozem single-writer, multiple-reader režimu v reálných projektech s možností horizontálního škálování, rolling aktualizací a dalšími praktickými scénáři, které s provozem v clusteru souvisí. Další mimořádnou výzvou bude i rozšíření databáze o fulltextové a sémantické vyhledávání, které je v dnešní době pro oblast e-commerce klíčové. Plánů pro rok 2026 máme spoustu a je jasné, že i příští rok nás čekají změny v prioritách a nové "naléhavější" výzvy, o kterých zatím ještě nemáme tušení. Těšíme se na ně a věříme, že i v příštím roce se nám podaří přinést spoustu užitečných vylepšení a funkcí, které pomohou našim vývojářům-uživatelům vytvářet ještě lepší aplikace nad evitaDB.