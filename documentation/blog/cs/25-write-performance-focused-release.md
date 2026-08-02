---
title: Vydání 2026.2 přináší vyšší výkon zápisu a cestu k milionům záznamů  
perex: | 
  Verze evitaDB 2026.2 přináší zásadní změny na zápisové straně databáze. Nové granulární a persistentní datové struktury výrazně omezují množství kopírovaných dat, snižují tlak na garbage collector a spolu s ukládáním jen skutečně změněných částí indexů umožňují stabilní práci s datovými sadami v řádu milionů záznamů. Vedle vyšší propustnosti zápisu verze rozšiřuje možnosti řízení transakčních konfliktů, opravuje atomičnost operací nad entitami a přidává histogramy nad atributy referencí a nad rozsahovými hodnotami, které cílí na pokročilé filtrování v e-commerce aplikacích.
date: '28.07.2026'
author: 'Ing. Jan Novotný'
motive: ../en/assets/images/25-write-performance-focused-release.png
proofreading: 'done'
---

Právě vydaná verze `2026.2` kromě rozšíření možností histogramů cílí především na zápisovou stranu evitaDB. Klíčová pro nás byla vždy latence a propustnost čtení, protože tam se odehrává drtivá většina provozu e-commerce aplikací, zápis byl pro nás vždy až na druhém místě. Datové struktury prvních verzí byly tomuto cíli podřízené a počítaly s katalogy v řádu nižších stovek tisíc produktů — laťka, která tehdejším instalacím bohatě stačila. Objemy dat, které evitaDB dnes na komerčních instalacích spravuje, ji ale posunuly, a tak jsme si na zápisovou stranu posvítili se stejnou důkladností jako kdysi na tu čtecí. Naše interní testy prokázaly stabilní výkony s daty v řádu milionů záznamů, nicméně se těšíme na konkrétní čísla z produkčních instalací.

Zásah jde tentokrát hodně hluboko do základů databáze. Než evitaDB prohlásíme za hotovou pro obecné použití, chceme mít právě tyhle základy pořádně usazené — a takové operace se do hotové databáze dělají mnohem hůř než teď.

# Výkonnostní vylepšení

Pojďme se tedy zaměřit na vylepšení, které tato verze přináší. 

## Granulární datové struktury

Řada datových struktur pracovala s běžnými poli či bitmapami, které nebyly vnitřně nijak členěné. Při zápisu se tyto datové struktury stále zvětšovaly, a přestože evitaDB kvůli rychlosti čtení provádí výraznou segmentaci indexovaných dat, vznikala horká místa, kde se pracovalo s rozsáhlými poli a bitmapami. Díky softwarové transakční paměti, která vyžaduje imutabilní datové struktury, docházelo (byť na dočasnou dobu) k duplikaci těchto polí při modifikacích, a to mělo negativní dopady na chování Java garbage collectoru.

Klíčovou změnou v této verzi byl přechod ke strukturám, do kterých lze zapisovat prakticky bez omezení, aniž by přitom docházelo k saturaci systému. Seřazená pole byla nahrazena vyváženými B+ stromy, které ukládají data formou menších polí, ke kterým vede cesta skrz nadřízené kontrolní uzly. B+ stromy mají řadu specializovaných variant používajících primitivní typy či „komprimované“ uložení dat. Řetězcové atributy se například v listech ukládají po způsobu telefonního seznamu — každá hodnota si pamatuje jen to, čím se liší od své předchůdkyně (tenhle trik zná Lucene ze svých slovníků termů). Na reálných datech, kde unikátní řetězce bývají URL adresy nebo EAN kódy sdílející dvacet a více znaků prefixu, to znamená zhruba dvojnásobnou úsporu na disku a čtyřnásobnou v paměti. Vyhledávání navíc u běžných textů porovnává rovnou bajty a objekt `String` vyrobí jen tam, kde je potřeba jazyková kolace nebo se objeví znak mimo základní rovinu Unicode. Čísla, data a časové značky zase putují do primitivních polí místo do polí obalových objektů — každý takový typ má definovaný převod na primitivní hodnotu, který zachovává původní řazení, takže binární vyhledávání běží přímo nad primitivy a na horké cestě nevzniká ani jeden `Integer` nebo `OffsetDateTime` navíc.

Díky změně původních datových struktur také přestaly některé indexy zbytečně existovat dvakrát. Původní verze držela zvlášť indexy sloužící pro řazení entit dle atributů a zvlášť indexy vyhledávací — každý s vlastní kopií hodnot a vlastní datovou strukturou. Nově, pokud je atribut zároveň filtrovatelný i řaditelný, obě role sdílejí jednu strukturu hodnot a řadicí index si drží už jen to, co je skutečně jeho: pořadí záznamů. Obdobná léčba potkala i tzv. unikátní indexy, které byly dříve jedna velká hash mapa serializovaná při každé změně celá. Nově jde o B+ strom s výše popsanou prefixovou kompresí, takže se při úpravě jednoho záznamu přepisuje jedna stránka místo stovek kilobajtů.

Tyto změny samozřejmě nejsou zdarma. Vyhledání v unikátním indexu dřív běželo v konstantním čase, nově je blíž k O(log n) — jenže je to logaritmus s velmi vysokým základem, takže i milionová kolekce se vejde do tří až čtyř skoků. Za tuhle přesně ohraničenou cenu jsme koupili neohraničený strop na zápisu. Regresi na čtecí straně jsme přitom měli stále na paměti a na řadě jiných míst ji vykompenzovali novými optimalizacemi.

Dalším důležitým prvkem bylo použití datové struktury [CHAMP](https://michael.steindorfer.name/publications/oopsla15.pdf) jako náhrady za běžné hash mapy na místech, kde jsme s mapou potřebovali pracovat jako s persistentní datovou strukturou, která nevyžaduje možnost vrácení změn (rollback), a použití B+ stromů, které v našem pojetí tuto podporu mají, by bylo nadbytečné. Java sice nějaké persistentní kolekce nabízí, my jsme ale potřebovali variantu šitou na míru našemu vzoru použití: jeden zapisovatel, mnoho současně platných verzí a levné hromadné sestavení nové verze. Šli jsme se proto inspirovat do původního článku a do implementace ve standardní knihovně jazyka Scala a s pomocí agentů naimplementovali podobnou datovou strukturu přímo v jádru evitaDB.

## Granulární ukládání na disk

Přechod na stromové struktury měl ještě jeden klíčový dopad, který je pro zápis možná podstatnější než datové struktury v paměti. Dosud platilo, že index byl z pohledu úložiště jeden nedělitelný blok — změna jediného atributu jediné entity znamenala, že se při potvrzení transakce serializoval a zapsal celý index znovu. U kolekce s vysokou kardinalitou to byly stovky kilobajtů zápisu kvůli jedné drobnosti.

Nově se atributové, filtrační, řadicí, cenové i referenční indexy ukládají po jednotlivých stránkách stromu a transakce zapíše jen ty stránky, které se skutečně změnily. Kromě objemu zápisu to výrazně ulevuje i pravidelné kompakci datových souborů, která do té doby musela uklízet po každé transakci mnohonásobně víc odpadu. Samotné kompakci jsme navíc osekali alokační režii — na velkých souborech dokázala vyvolat až dvousekundovou pauzu garbage collectoru, kterou souběžně běžící dotazy poznaly jako timeout. Jak evitaDB ukládá data na disk, popisuje [kapitola o úložném modelu](https://evitadb.io/documentation/deep-dive/storage-model).

Když už jsme byli v útrobách úložiště, přitvrdili jsme i v jeho odolnosti. Transakční log nese nově průběžný kontrolní součet CRC32 přes všechny předchozí záznamy, takže se jeho poškození odhalí na úrovni jednotlivých bajtů (stávající soubory se do nového formátu převedou samy). A stav databázového stroje se už nemůže posunout dřív, než je odpovídající záznam bezpečně v logu.

## Vlastní verze RoaringBitmap

Podobným případem jako CHAMP byla i knihovna [RoaringBitmap](https://github.com/RoaringBitmap/RoaringBitmap/). V původní implementaci tato datová struktura nesplňuje požadavky na persistentní datovou strukturu a v rámci našeho použití bylo při zápisu do ní potřeba provést její naklonování. Při zvětšujícím se množství uložených dat to znamenalo opět velmi negativní dopad při zápisu a tlak na GC. Přepis této datové struktury na persistentní je přitom možný — při zápisu stačí vyměnit pouze jeden z mnoha kontejnerů, které tato struktura vnitřně spravuje (a samozřejmě ukazatel na kontejner v hlavičkovém bloku). Částečné kroky k podobnému přístupu již byly v původní knihovně učiněny, ale nebyly dostatečné.

Připravili jsme proto prototyp a [otevřeli k němu diskusi v upstreamu](https://github.com/RoaringBitmap/RoaringBitmap/issues/826). Zásah jde ale hluboko do jádra knihovny a bez autorského týmu ho dotáhnout nelze — a ten se, zřejmě z čirého nedostatku času, zatím nevyjádřil. Přistoupili jsme tedy ke stejnému kroku jako u struktury CHAMP: s pomocí agentů jsme převzali myšlenky a základní implementaci a upravili je tak, aby odpovídaly našim požadavkům. Produkční kód evitaDB dnes běží na naší vlastní verzi, původní knihovna zůstala jen v benchmarcích jako srovnávací měřítko.

V době před agenty by byl tento přístup nemožný a údržba takového forku v delším horizontu neúnosná. Tato matematika se ovšem s nástupem umělé inteligence zcela změnila — a není to jen teorie: naši verzi průběžně dosynchronizováváme s upstreamem, včetně opravy [reverzních iterátorů](https://github.com/RoaringBitmap/RoaringBitmap/pull/837), při jejímž zapracování jsme mimochodem odhalili a v upstreamu opravili dvě zděděné chyby navíc.

## Granulární kontrola nad vyhodnocením konfliktů

evitaDB používá pro všechny transakce tzv. „snapshot“ izolaci — ta zjednodušeně znamená, že čtenáři vidí všechna data v databázi tak, jak vypadala ve chvíli otevření nového sezení (i kdyby toto sezení trvalo desítky minut). V rámci tohoto sezení pak klienti vidí pouze tento konzistentní výchozí stav a své změny v něm — nikdy nevidí práci ostatních klientů pracujících paralelně s nimi, a to i kdyby ji tito dokončili a úspěšně potvrdili vůči serveru. Ve chvíli, kdy se klient pokusí potvrdit i své změny, server zkontroluje, jestli jej náhodou nepředběhl někdo jiný a stejnou „konfliktní oblast“ nezměnili naráz. Podstata problému spočívá v definici konfliktní oblasti, protože její šířka ovlivňuje počet těchto konfliktních situací. Až doposud se tato konfliktní oblast definovala pro všechny katalogy a typy entit v celé instanci evitaDB shodně a ve výchozím nastavení spočívala v zákazu současných změn v jedné a té samé entitě (např. nebylo možné, aby dva klienti současně upravili cokoliv ve stejném produktu).

Nově je toto vymezení výrazně pružnější a stojí na dvou úrovních. První je *hrubá* politika, kterou nese schéma — uplatní se ta nejbližší deklarovaná (schéma entity → schéma katalogu → výchozí nastavení databázového stroje) a platí vždy jako celek. Kromě klasické entity je možné konfliktní oblast rozšířit na celou kolekci či celý katalog, nebo ji naopak zúžit na jednotlivé atributy, asociovaná data, ceny, umístění v hierarchii či reference.

Druhou úrovní je *výjimka na konkrétní položce*: atribut, asociovaný údaj nebo reference si ve svém schématu může vyžádat vlastní konfliktní klíč, nebo sdílet konfliktní klíč celé entity. Díky tomu je možné nechat produkt jako celek serializovaný a uvolnit jen ty položky, o které se perou nezávislé procesy — typicky feedové exporty nebo dopočítávaná data, která do produktu zapisuje úplně jiná úloha než ta hlavní indexační. Podrobnosti i doporučení, kdy sáhnout po které úrovni, najdete v [kapitole o transakcích](https://evitadb.io/documentation/deep-dive/transactions#conflict-resolution-levels).

# Nové vlastnosti

Tato verze se z pohledu funkčních změn zaměřovala především na rozšíření možností histogramů tak, aby lépe odpovídaly potřebám v e-commerce sektoru.

## Histogramy nad atributy referencí

Základní limitací histogramů bylo omezení jejich použití na atributy entity. V praxi se totiž často používají jako alternativa k fasetovým filtrům v případech, kdy množství hodnot je nepraktické reprezentovat výčtovým typem (zaškrtávacím polem) a je vhodnější je vizualizovat jako intervalový filtr (posuvník). Zároveň klient často předem neví, o jaký histogram si má na konkrétní stránce požádat, protože jeho existence je často spjatá s typem záznamů, které jsou výsledkem primární filtrace. Například v kategorii „Lednice“ dávají smysl intervalové filtry pro šířku / výšku / hloubku, kdežto v kategorii „Pečivo“ takové zcela jistě neexistují. Z pohledu klienta (aplikace) je tedy praktičtější definovat dotaz tímto způsobem:

> Najdi mi všechny produkty v kategorii „Lednice“ (a jejích podkategoriích). Ze všech jejich parametrů, které nesou příznak (jsou zařazeny do skupiny s tímto příznakem), že jsou určeny pro filtraci, připrav:
> 
> a) fasetový filtr, pokud parametry (jejich skupiny) jsou označeny příznakem „výčtový typ“
> b) histogram, pokud parametry (jejich skupiny) jsou označeny příznakem „intervalový typ“
 
Touto formou je pak pro klienta velmi snadné vykreslovat skutečně komplexní filtrační možnosti, které umožní uživateli vymezit si vlastní mantinely pro zobrazované výsledky. Vzhledem k tomu, že potřebujeme výpočty provádět velmi rychle, je nutné, aby databáze udržovala řadu ukazatelů předpočítaných — a to vyžaduje zakomponovat takto komplexní zadání už do schématu databáze. Použili jsme k tomu [výrazový jazyk evitaEL](https://evitadb.io/documentation/query/expression-language), který v evitaDB existuje už od roku 2024 a dosud sloužil hlavně ke stránkování s mezerami. Nově se jím ve schématu reference popíše, odkud se hodnota do histogramu bere (například `$reference.referencedEntity.attributes['basicUnitValue']`), a databáze si z toho sama odvodí, které struktury je nutné při změně dat entit aktualizovat. Jedna reference přitom může nést histogramů hned několik, každý pod svým jménem. Celý mechanismus popisuje [dokumentace schématu](https://evitadb.io/documentation/use/schema#reference-histograms).

## Histogramy nad rozsahovými hodnotami

Právě popsaný mechanismus histogramů nad referencemi jsme rozšířili ještě jedním směrem. evitaDB totiž umožňuje ukládat číselné atributy nejen jako jednu konkrétní hodnotu, ale také jako rozsah od–do. Typickým příkladem může být produkt, jehož rozměr lze podle požadavků zákazníka upravit v určitém intervalu, například pracovní deska zkrátitelná na délku 120 až 180 cm. Jedna nebo obě hranice rozsahu mohou být také otevřené, takže lze vyjádřit například hodnotu „od 100 výše“ nebo „nejvýše 50“.

Nově tedy hodnotový výraz histogramu na referenci nemusí ukazovat jen na prosté číslo, ale i na takový rozsahový atribut. Produkt se přitom započítá do všech hodnot histogramu, které leží uvnitř jeho intervalu, a to včetně obou krajních. Pokud tedy jeden produkt podporuje délku 120–180 cm, bude součástí výsledků pro hodnoty 120, 150 i 180 cm. Klientská aplikace tak může uživateli ukázat, kolik produktů vyhovuje konkrétní požadované hodnotě, nikoliv pouze kolik produktů má shodnou počáteční nebo koncovou hranici.

Výpočet využívá již existující rozsahové indexy, které evitaDB používá pro filtrování. Databáze prochází seřazené hranice jednotlivých intervalů a průběžně sleduje, které záznamy jsou v daném bodě aktivní. Díky tomu není nutné samostatně testovat každý produkt proti každé hodnotě histogramu a výpočet lze provést jediným průchodem nad indexovanými daty.

## Vsuvka: histogramy s vyrovnanou četností

Běžný histogram rozděluje celý rozsah hodnot na stejně široké intervaly. To u e-commerce dat často vede k nepraktickým výsledkům, protože hodnoty nebývají rozloženy rovnoměrně. Pokud se ceny produktů pohybují od 100 Kč do 100 000 Kč, ale většina produktů stojí méně než 5 000 Kč, skončí téměř všechny v prvním intervalu a zbytek histogramu zůstane prakticky prázdný. Uživatel pak musí posuvníkem v hustě obsazené oblasti mířit velmi přesně, zatímco velká část jeho dráhy ovlivňuje jen několik málo extrémních hodnot.

evitaDB proto umí připravit histogram, jehož intervaly nejsou stejně široké, ale obsahují přibližně stejné množství záznamů — v hustě obsazené části rozsahu vrací užší intervaly, v řídké naopak širší. Klientská aplikace z toho může postavit nelineární škálu, která nabízí jemnější ovládání právě tam, kde je nejvíc výsledků. Tuhle schopnost jsme přitom vypustili do světa už ve verzi `2026.1`; zmiňujeme ji tu proto, že ji aktuální verze zpřístupnila i pro rozsahové histogramy popsané výše. Detaily i to, jak se kombinuje se zahazováním prázdných intervalů, najdete v [dokumentaci histogramů](https://evitadb.io/documentation/query/requirements/histogram#attribute-histogram-equalization).

## Drobnosti, které se hodí

Mimo histogramy přibylo i pár menších věcí, po kterých jste se ptali. Nové filtrační omezení [`groupHaving`](https://evitadb.io/documentation/query/filtering/references#group-having) umožňuje vybírat entity podle toho, jestli *skupina* jejich reference splňuje zadanou podmínku — dřív to nešlo vyjádřit vůbec. U primárních klíčů lze nově filtrovat nejen rovností, ale i nerovnostmi a rozsahem. `stopAt(distance(0))` dovolí zastavit průchod hierarchií rovnou na výchozím uzlu, což se hodí třeba pro drobečkovou navigaci se statistikami za celý podstrom. A `PricesContract` umí jedním voláním vrátit nejnižší i nejvyšší cenu napříč variantami jednoho master produktu, takže se cenové rozpětí ve výpisu dopočítá bez dalších dotazů.

# Opravy chyb

S nástupem umělé inteligence se podařilo doplnit naši testovací sadu o další testy — od předchozí verze vydané zkraje roku 2026 jsme prakticky zdvojnásobili počet jednotkových a integračních testů (počet testů verze `2026.1` byl *12142*, u současné verze `2026.2` je to již *22555*). Díky tomu také došlo k odstranění řady drobných chyb souvisejících s okrajovými případy použití a současná verze je tedy mnohem stabilnější než ta předchozí.

## Atomičnost operací nad entitou

Atomičnost znamená, že se operace provede buď celá, nebo vůbec. Samozřejmě to platí pro celou transakci, prakticky užitečné je ale chovat se tak i k jejím dílčím celkům — u nás k operaci nad jednou entitou. Ta se skládá z několika tzv. lokálních mutací: úprava atributu, změna ceny, přidání reference. Když X z Y těchto mutací projde a další narazí třeba na omezení unikátnosti, měla by databáze účinky těch předchozích uklidit a teprve potom vyhodit výjimku. Klient ji zachytí, zareaguje na ni a v původní transakci pokračuje dál — nemusí zahodit celou svou dosavadní práci. V předchozích verzích se na tenhle úklid nedalo úplně spolehnout, takže bylo bezpečnější zahazovat transakci celou. To tlačilo vývojáře k menším transakcím, tedy k častějším commitům, a ty jsou z pohledu databáze drahé — a jsme obloukem zpátky u nízkého výkonu na zápisové straně.

Tento nedostatek je v současné verzi již odstraněn — a spolu s ním i skrytá past, kvůli které cena takového částečného návratu rostla kvadraticky s počtem entit už zpracovaných ve stejné transakci. Velikost dávkových transakcí tak může být výrazně navýšena a tím se zvýší i propustnost zápisu dat do databáze.

## Časové limity volání gRPC

Článek uzavřeme posledním otevřeným bodem předchozí verze — časovými limity (tzv. timeouty) na úrovni protokolu HTTP/2, které se projevovaly v gRPC rozhraní. Používáme k němu vestavěný webový server Armeria, který je extrémně optimalizovaný a staví na reaktivním principu; přizpůsobit se mu znamenalo podřídit všechno asynchronnímu zpracování a doladit to chvíli trvalo. Posledním kamenem úrazu byl typ poolu použitého pro zpracování úloh v kombinaci s nastavením ping intervalu. Sám protokol HTTP/2 přitom u pingu žádnou lhůtu na odpověď nezná — že se neopětovaný ping bere jako mrtvé spojení, je až konvence gRPC. A Armeria ji implementuje po svém: nemá zvlášť periodu pingu a zvlášť lhůtu na jeho potvrzení, obojím je tentýž údaj. Kdo tedy pinguje častěji, zkracuje si tím zároveň lhůtu na odpověď — a spojení pak padá i ve chvíli, kdy se po něm zrovna přenáší rozpracovaný požadavek. Dohromady s vytíženým poolem se to občas projevilo stavem CANCELLED na úrovni gRPC. Příčinu jsme dohledali a opravili.

Na úplný závěr ještě jedno upozornění pro ty, kdo budou povyšovat: `2026.2` s sebou nese i několik nekompatibilních změn. Histogramy se na referenci deklarují polem (`@Reference(bucketed = {@Histogram(...)})`), GraphQL pole `attributeHistograms` nahradily statistiky přímo na souhrnech referencí, REST `referenceSummary` očekává `requirements` jako pole a cenový histogram u entit v režimu `LOWEST_PRICE` nově počítá s cenami jednotlivých variant, takže vrátí jiné rozložení než dřív. Nejtišší z nich je ta poslední: odebrání anotačního příznaku na modelové třídě v Java klientu se nově při update schématu skutečně promítne, místo ponechání původní hodnoty. Kompletní seznam včetně vysvětlení najdete v [poznámkách k vydání](https://github.com/FgForrest/evitaDB/releases/tag/v2026.2.0).
