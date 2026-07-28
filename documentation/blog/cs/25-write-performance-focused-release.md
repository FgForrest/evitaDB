---
title: Vydání 2026.2 přináší vyšší výkon zápisu a cestu k milionům záznamů  
perex: | 
  Verze evitaDB 2026.2 přináší zásadní změny na zápisové straně databáze. Nové granulární a persistentní datové struktury výrazně omezují množství kopírovaných dat, snižují tlak na garbage collector a umožňují stabilní práci s datovými sadami v řádu milionů záznamů. Vedle vyšší propustnosti zápisu verze rozšiřuje možnosti řízení transakčních konfliktů, opravuje atomičnost operací nad entitami a přidává nové typy histogramů určené pro pokročilé filtrování v e-commerce aplikacích.
date: '27.07.2026'
author: 'Ing. Jan Novotný'
motive: assets/images/25-write-performance-focused-release.png
proofreading: 'todo'
draft: true
---

Právě vydaná verze `2026.2` kromě rozšíření možností histogramů cílí především na zápisovou stranu evitaDB. Od počátku implementačních prací jsme se zaměřovali především na latenci a propustnost operacích pro čtení dat z databáze, protože to je klíčová oblast provozu e-commerce aplikací. S nárůstem objemu dat, které evitaDB na komerčních instalacích spravuje jsme začali narážet na technické limity původně zvolených datových struktur a v této verzi jsme se rozhodli provést takové změny, abychom umožnili práci s řádově většími daty. Naše interní testy prokázaly stabilní výkony s daty v řádu milonů záznamů (původní implementace cílila na datové sady v řádek nižších stovek tisíc záznamů = rozumněj produktů), nicméně se těšíme na konkrétní čísla z produkčních instalací.

# Výkonnostní vylepšení

Pojďme se tedy zaměřit na vylepšení, které tato verze přináší. 

## Granulární datové struktury

Řada datových struktur pracovala s běžnými poli či bitmapami, které nebyly vnitřně nijak členěné. Při zápisu se tyto datové struktury stále zvětšovaly, a přestože evitaDB kvůli rychlosti čtení provádí výraznou segmentaci indexovaných dat, vznikala horká místa, kde se pracovalo s rozsáhlými poli a bitmapami. Díky softwarové transakční paměti, která vyžaduje imutabilní datové struktury docházelo (byť na dočasnou dobu) duplikaci těchto polí při modifikacích a to mělo negativní dopady na chování Java garbage collectoru.

Klíčovou změnou v této verzi byl přechod ke strukturám, které umožňují prakticky nekonečné zapisování dat aniž by došlo k saturaci systému. Seřazené pole byly nahrazeny vyváženými B+ stromy, které ukládají data formou menších polí, ke kterým vede cesta skrz nadřízené konktrolní uzly. B+ stromy mají řadu specializovaných variant používajících primitivní typy, či podporují "komprimované" uložení dat. Například řetězcové atributy používají kompresi pomocí společných prefixů, s optimalizací pro ASCII znakovou sadu, která umožňuje průchod bez materializace String objektů. Podobná komprese je použita i pro ukládání datumových typů, které se ukládají jako čísla datového typu `long` (`int64`), a které velmi často sdílí velkou část svého základu (řada časových značek sdílí stejné datum a liší se pouze v čase nebo pouze v sekundách).

Díky změně původních datových struktur bylo možné sloučit některé indexy do jednoho. Původní verze odlišovala indexy sloužící pro řazení entit dle atributů od vyhledávacích indexů. Jejich datové struktury byly jiné. V této verzi došlo k jejich sloučení a deriváty pro třídění (precomputed-rank scatter with bounded lookup) se vytvářejí až v případě potřeby. Obdobná opatření potkala i tzv. unikátní indexy, které byly dříve implementovány pomocí běžné hash mapy. Na tomto příkladě je vidět, že jsme museli provést řadu ústupků, které ovlivňují i čtecí stranu evitaDB. Unikátní indexy dříve podléhaly komplexitě O(1), nově jsou o něco pomalejší s komplexitou blíže k (O log n). Celkově jsme kladli důraz na to, aby regrese v čtecím výkonu byla co nejmenší a naopak jsme implementovali řadu vylepšení, které výkonnost čtení posouvají zase zpět.

Dalším důležitým prvkem bylo použití datové struktury [CHAMP](https://michael.steindorfer.name/publications/oopsla15.pdf) jako náhrady za běžné hash mapy na místech, kde jsme s mapou potřebovali pracovat jako s persistentní datovou strukturou, která nevyžaduje možnost vrácení změn (rollback) a použití B+ stromů, které v našem pojetí tuto podporu mají, by byla nadbytečná. Zajímavostí v tomto případě je fakt, že pro tuto datovou strukturu neexistuje v Javě rozumně spolehlivá implementace, kterou jsme z pohledu spolehlivosti pro naše případy potřebovali. Proto jsme se šli inspirovat do implementace v jazyce Scala, a s pomocí agentů naimplementovali podobnou datovou strukturu přímo v jádru evitaDB.

## Odloučení RoaringBitmap

Podobným případem jako byla CHAMP datová struktura byl i port knihovny [RoaringBitmap](https://github.com/RoaringBitmap/RoaringBitmap/). V původní implementaci tato datová struktura nesplňuje požadavky na persistentní datovou strukturu a v rámci našeho použití bylo při zápisu do ní potřeba provést její odklonování. Při zvětšujícím se množství uložených dat v této datové struktuře to znamenalo opět velmi negativní dopad při zápisu a tlak na GC. Přepis této datové struktury na persistentní je přitom možné, při zápisu stačí vyměnit pouze jeden z mnoha kontejnerů, které tato struktura vnitřně spravuje (a samozřejmě ukazatel na kontejner v hlavičkovém bloku). Částečné kroky k podobnému přístupu již byly v původní knihovně učiněny, ale nebyly dostatečné a o další posun tímto směrem [zřejmě tým nemá zájem](https://github.com/RoaringBitmap/RoaringBitmap/issues/826).

Proto jsme přistoupili k podobnému kroku jako u CHAMP struktury. S pomocí agentů jsme převzali myšlenky a základní implementaci z knihovny RoaringBitmap a upravili ji tak, aby odpovídala našim požadavkům na použití. V době před agenty by byl tento přístup nemožný a údržba takového forku v delším horizontu neúnosná. Tato matematika se ovšem s nástupem umělé inteligence zcela změnila. Nyní je relativně snadné podobný fork udržovat (tedy do něj průběžně zapracovávat změny a opravy z originální knihovny) a rozvíjet.

## Granulární kontrola nad vyhodnocením konfliktů

evitaDB používá pro všechny transakce tzv. "snapshot" izolaci - ta zjednodušeně znamená, že čtenáři vidí všechna data v databázi, tak jak vypadala ve chvíli otevření nového sezení (i kdyby toto sezení trvalo desítky minut). V rámci tohoto sezení pak klienti vidí pouze tento konzistentí výchozí stav a své změny v něm - nikdy nevidí práci ostatních klientů pracujících paralelně s ním a to i kdyby dokončili a úspěšně potvrdili své změny vůči serveru. Ve chvíli, kdy se tento klient pokusí potvrdit i tyto své změny, server zkontroluje, jestli jej náhodou nepředběhl jiný klient a stejnou "konfliktní oblast" nezměnili naráz. Podstata problému spočívá v definici konfliktní oblasti, protože její šířka ovlivňuje počet těchto konfliktních situací. Až doposud se tato konfliktní oblast definovala pro všechny katalogy a typy entit v celé instanci evitaDB shodně a ve výchozím nastavení spočívala v zákazu současných změn v jedné a té samé entitě (např. nebylo možné, aby dva klienti současně upravili cokoliv ve stejném produktu).

Nově umožňujeme velmi svobodnou kontrolu nad konfliktní oblastí. Základní režim je definovaný v konfiguraci databázového stroje, ale je možný jej ve schématu katalogu / entity / atributu / asociovaného data / reference upravit dle potřeby. Ve výchozím stavu se tato vlastnost dědí z nadřízeného schématu, ale je možné ji na libovolné úrovni změnit. Je tedy možné nastavit hybridní konfliktní schémata, kdy je vyloučen souběžný zápis jedné entity s výjimkou asociovaných dat, které zamezují současný přepis jen konkrétního asociovaného údaje, či třeba jen atributu s konkrétním názvem atp. Tyto nové možnosti výrazně posouvají hranice kontroly pro konkrétní e-commerce scénáře.

# Nové vlastnosti

Tato verze se z pohledu funkčních změn zaměřovala především na rozšíření možností histogramů tak, aby lépe odpovídaly potřebám v e-commerce sektoru.

## Histogramy nad atributy referencí

Základní limitací histogramů bylo omezení jejich použití na atributy entity. V praxi se totiž často používají alternativa k fasetovým filtrům v případech kdy množství hodnot je nepraktické reprezentovat výčtovým typem (zaškrtávacím polem) a je vhodnější je vizualizovat jako intervalový filtr (posuvník). Zároveň klient často předem neví o jaký histogram si má na konkrétní stránce požádat, protože jeho existence je často spjatá s typem záznamů, které jsou výsledkem primární filtrace. Například v kategorii "Lednice" dávají smysl intervalové filtry pro šířku / výšku / hloubku, kdežto v kategorii "Pečivo" takové zcela jistě neexistují. Z pohledu klienta (aplikace) je tedy praktičtější definovat dotaz tímto způsobem:

> Najdi mi všechny produkty v kategorii "Lednice" (a jejích podkategorií). Ze všech jejich parametrů, které nesou příznak (jsou zařazeny do skupiny s tímto příznakem), že jsou určeny pro filtraci, připrav:
> 
> a) fasetový filtr, pokud parametry (jejich skupiny) jsou označeny příznakem "výčtový typ"
> b) histogram, pokud parametry (jejich skupiny) jsou označeny příznakem "intervalový typ"
 
Touto formou je pak pro klienta velmi snadné vykreslovat skutečně komplexní filtrační možnosti, které umožní uživateli vymezit si vlastní mantinely pro zobrazované výsledky. Vzhledem k tomu, že potřebujeme výpočty provádět velmi rychle je nutné, aby databáze udržovala řadu ukazatelů předpočítaných. To vyžaduje, aby bylo možné takto komplexní zadání zakomponovat už do schématu databáze. Pro tyto účely vzniknul nový výrzazový jazyk, který umožňuje databázi vyhodnocovat, které struktury je nutné při změně dat entit aktualizovat.

## Optimalizované histogramy

Běžný histogram rozděluje celý rozsah hodnot na stejně široké intervaly. To ale u e-commerce dat často vede k nepraktickým výsledkům, protože hodnoty nebývají rozloženy rovnoměrně. Pokud se například ceny produktů pohybují od 100 Kč do 100 000 Kč, ale většina produktů stojí méně než 5 000 Kč, skončí téměř všechny v prvním intervalu a zbývající část histogramu zůstane prakticky prázdná.

Stejný problém se následně přenáší i do intervalových filtrů v klientské aplikaci. V oblasti s největším množstvím produktů musí uživatel pohybovat posuvníkem velmi přesně, zatímco velká část jeho dráhy ovlivňuje jen několik málo extrémních hodnot. Takový filtr sice matematicky odpovídá rozsahu dat, ale neodpovídá způsobu, jakým s ním uživatel skutečně pracuje.

Nově proto evitaDB dokáže připravit histogram, jehož intervaly nejsou stejně široké, ale obsahují přibližně stejné množství záznamů. V hustě obsazené části rozsahu tak vrací užší intervaly, zatímco v řídce obsazené části intervaly širší. Klientská aplikace může tyto údaje použít k vytvoření nelineární škály, která poskytuje jemnější ovládání právě tam, kde se nachází nejvíce výsledků, a efektivněji využívá celou dráhu posuvníku.

## Histogramy nad rozsahovým hodnotami

evitaDB umožňuje ukládat číselné atributy nejen jako jednu konkrétní hodnotu, ale také jako rozsah od–do. Typickým příkladem může být produkt, jehož rozměr lze podle požadavků zákazníka upravit v určitém intervalu, například pracovní deska zkrátitelná na délku 120 až 180 cm. Jedna nebo obě hranice rozsahu mohou být také otevřené, takže lze vyjádřit například hodnotu „od 100 výše“ nebo „nejvýše 50“.

Nově je možné vytvářet histogramy i nad těmito rozsahovými atributy. Produkt se přitom započítá do všech hodnot histogramu, které leží uvnitř jeho intervalu. Pokud tedy jeden produkt podporuje délku 120–180 cm, bude součástí výsledků pro hodnoty 120, 150 i 180 cm. Klientská aplikace tak může uživateli ukázat, kolik produktů vyhovuje konkrétní požadované hodnotě, nikoliv pouze kolik produktů má shodnou počáteční nebo koncovou hranici.

Výpočet využívá již existující rozsahové indexy, které evitaDB používá pro filtrování. Databáze prochází seřazené hranice jednotlivých intervalů a průběžně sleduje, které záznamy jsou v daném bodě aktivní. Díky tomu není nutné samostatně testovat každý produkt proti každé hodnotě histogramu a výpočet lze provést jediným průchodem nad indexovanými daty.

# Opravy chyb

S nástupem umělé inteligence se podařilo doplnit naši testovací sadu o další testy - od předchozí verze vydané zkraje roku 2026 jsme prakticky zdvojnásobili počet jednotkových a integračních testů (počet testů verze `2026.1` byl *12142*, u současné verze `2026.2` je to již *22555*). Díky tomu také došlo k odstranění řady drobných chyb souvisejících s okrajovými případy použití a současná verze je tedy mnohem stabilnější než ta předchozí.

## Atomičnost operací nad entitou

Atomičnost operace znamená, že se daná operace provede buď celá nebo se neprovede vůbec. Tato vlastnost platí pro celou transakci jako takovou, ale z praktického hlediska je výhodné se takto chovat i k nějakým zastřešujícím operacím v rámci transakce. V našem případě to platí pro operaci na úrovni entity. Ta může obsahovat několik tzv. lokálních mutací - např. aktualizace atributu, ceny, přidání reference atp. V rámci provádění akce se může X z Y těchto lokálních mutací provést než jedna z nich selže - například na omezení unikátnosti. V tuto chvíli je vhodné, aby databáze odstranila všechny efekty předcházejících lokálních mutací v této entitě a teprve potom vyhodila výjimku. Klient pak může takovou výjimku zachytit, upravit své chování a v původní transakci i nadále pokračovat (jinými slovy nemusí zahodit svoji předchozí práci v této transakci). Tento mechanismus bohužel v předchozích verzích nefungoval spolehlivě, což vedlo k potřeba transakci zahazovat v celém rozsahu. To vedlo k potřebě zmenšit množství operací v transakcích, což vede k častějším commit operacím, které jsou z pohledu databáze celkem drahé a to nás opět obloukem dovádí k bodu nízkého výkonu na zápisové straně.

Tento nedostatek je v současné verzi již odstraněn, takže velikost dávkových transakcí může být výrazně navýšena a tím se zvýší i propustnost zápisu dat do databáze.

## Časové limity volání gRPC

Článek uzavřeme zmínkou o poslední větší negativní vlastností předchozí verze a tou jsou časové limity (tzv. timeouty) na úrovni protokolu HTTP/2, které se konkrétně projevovaly v gRPC rozhraní. Pro tyto účely používáme vestavěný webový server Armeria, který je extrémně optimalizovaný a staví na reaktivním principu. V tomto ohledu jsme se i my museli historicky přizpůsobit a vše podřídit asynchronnímu reaktivnímu zpracování. Vyladit tento přístup chvíli trvalo a jedním z posledních problémů, které trápily předchozí verzi bylo využití nevhodného typu poolu pro zpracování úloh a nevhodné nastavení ping intervalu (bohužel ping interval neprodlužuje pouze životnost spojení, ale taktéž jej aktivně ukončuje, pokud nedostane odpověď druhé strany v limitu), které vedlo k občasnému výskytu CANCELLED stavů na úrovni gRPC. Tento problém byl korektně diagnostikován a opraven v současné verzi databáze.