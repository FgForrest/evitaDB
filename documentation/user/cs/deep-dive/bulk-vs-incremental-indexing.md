---
title: Hromadné vs. inkrementální indexování
perex: 'evitaDB je navržena jako rychlá, transakční, pro čtení optimalizovaná databáze, která odlehčuje práci primárnímu datovému úložišti, jímž bývá obvykle nějaký typ relační databáze. Očekává se proto, že bude fungovat ve dvou odlišných fázích: počáteční indexování velkého datasetu a následná údržba indexu po celou dobu jeho životnosti. Tyto dvě fáze mají odlišné požadavky a proto jsou řešeny zvláštním způsobem.'
date: '24.8.2028'
author: Ing. Jan Novotný
translated: 'true'
commit: '77da5b36c170430534ee4d9a4a2903da4de68555'
---
## Hromadné indexování (FÁZE WARM-UP)

Hromadné indexování slouží k rychlému indexování velkého objemu zdrojových dat z externího datového úložiště. V této počáteční fázi životního cyklu katalogu není potřeba podpora transakcí ani souběžnosti. Jediným cílem je zaindexovat co nejvíce dat v co nejkratším čase. Tato fáze má následující charakteristiky:

1. V jeden okamžik může být otevřen pouze jeden klient (jedna relace).
2. Není možné provést rollback – pokud dojde k jakékoli chybě (i uprostřed zápisu jedné entity), klient musí obnovu řešit sám (viz [Atomicita jednotlivých zápisů](#atomicita-jednotlivých-zápisů)).
3. Všechny změny v indexech jsou uchovávány v paměti a zapsány až při uzavření relace; v případě pádu databáze jsou všechny změny ztraceny.

Po dokončení počátečního indexování se očekává, že klient ukončí fázi warm-up uzavřením relace a provedením mutace `MakeCatalogAlive`, která přepne katalog do fáze ALIVE (viz následující kapitola). <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass> poskytuje pro tento účel metodu `goLiveAndClose`. Přechod lze také vyvolat metodou `makeCatalogAlive` ve <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass>.</LS>
## Inkrementální indexování (FÁZE ALIVE)

Inkrementální indexování je fáze, ve které průběžně synchronizujeme změny z primárního datového úložiště do evitaDB. Může být otevřeno více klientů (relací) současně, některé pouze čtou, jiné zapisují. Každá čtecí-zapisovací relace definuje hranici transakce a změny lze atomicky potvrdit nebo vrátit zpět (podrobnosti o ACID najdete v [kapitole o transakcích](transactions.md)). Výkon zápisu je v této fázi výrazně nižší než při hromadném indexování, protože je nutné udržovat transakční integritu, souběžnost a trvanlivost změn. Výkon čtení tím není ovlivněn a zůstává velmi vysoký.

## Atomicita jednotlivých zápisů

Granularita, na které je zápis atomický, se liší mezi oběma fázemi. Jeden zápis — volání [`upsertEntity`](../use/api/write-data.md#upsert) nebo [`deleteEntity`](../use/api/write-data.md#odstraňování) spolu se všemi změnami indexů, které z toho vyplývají (atributy, reference, facety, ceny, umístění v hierarchii, odražené reference) — je považován za jeden pracovní celek.

### Fáze ALIVE — každý zápis je atomický

Ve fázi ALIVE je každý upsert nebo odstranění entity **atomické samo o sobě**, kromě atomarity obalující transakce. Pokud se aplikace jedné mutace entity nezdaří v průběhu — například proto, že poruší unikátní omezení nebo jiná pravidla konzistence poté, co už byly některé její indexové záznamy zapsány — engine chirurgicky vrátí zpět přesně tyto dílčí změny dané entity a okolní transakci ponechá nedotčenou. Selhávající volání vyvolá výjimku, ale všechny entity zapsané před ním v rámci stejné transakce zůstávají platné a klient může výjimku zachytit, pokračovat v zápisu dalších entit a poté transakci potvrdit. Jedna neúspěšná entita tedy nikdy nepoškodí transakci ani nezpůsobí únik napůl aplikovaného indexového záznamu (například osiřelého facetu nebo neexistující ceny) a jakákoli hodnota, kterou se pokusila rezervovat (například unikátní atribut), se okamžitě opět uvolní. Toto vrácení na úrovni entity je nezávislé na výsledku samotné obalující transakce: při potvrzení se zveřejní pouze úspěšné entity, při rollbacku se vše zahodí jako obvykle.

### Fáze WARM-UP — žádné vracení zápisů po jednotlivých operacích

Ve fázi WARM-UP (hromadné indexování) **nedochází k vracení zápisů po jednotlivých operacích**. Hromadné indexování záměrně zapisuje změny indexu přímo na místě, aby maximalizovalo propustnost, a neudržuje transakční diff vrstvy, na kterých závisí vracení změn na úrovni jednotlivých entit. Pokud se upsert nebo odstranění entity nezdaří v průběhu, změny, které již byly na této entitě provedeny, zůstávají v paměťovém indexu a katalog je pro tuto entitu v nekonzistentním stavu.

Protože engine v této fázi nemůže částečný zápis vrátit zpět, **obnova je zodpovědností klienta**. Existují dvě možnosti:

1. **Kompenzace na straně klienta** — detekovat selhání a znovu aplikovat správný, kompletní stav pro danou entitu (nebo ji odstranit), aby byl index před pokračováním opět konzistentní. Toto je bezpečné pouze tehdy, pokud dokážete přesně rekonstruovat, co bylo částečně zapsáno.
2. **Zahodit a znovu vybudovat** (doporučeno) — opustit rozpracovaný katalog a spustit hromadný import znovu od začátku. Protože fáze warm-up je navržena pro rychlé počáteční načtení, úplné znovuvybudování je obvykle nenáročné. Pokud potřebujete katalog znovu vybudovat za běhu s obsluhou živého provozu, vytvořte nový katalog na pozadí a po dokončení jej atomicky vyměňte — viz [Kompletní reindexace živého katalogu](#kompletní-reindexace-živého-katalogu) níže.

Pokud během počátečního načítání vyžadujete atomaritu na úrovni jednotlivých entit, přepněte katalog nejprve do fáze ALIVE a načítejte data prostřednictvím transakcí, přičemž přijmete nižší propustnost zápisu výměnou za tuto záruku.

## Kompletní reindexace živého katalogu

Nastávají situace, kdy je potřeba znovu zaindexovat celý katalog z primárního datového úložiště, a přitom stále obsluhovat živý provoz z aktuálních dat. Doporučený postup je vytvořit nový dočasný katalog a naplnit jej počáteční sadou dat pomocí hromadného indexování. Jakmile je nový katalog plně zaindexován, můžete aplikaci přepnout na nový katalog pomocí operace nahrazení katalogu. <LS to="j">Pro tento účel existuje metoda `replaceCatalog` v rozhraní <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass>.</LS> Nahrazení katalogu je velmi rychlá operace, která nevyžaduje kopírování dat – pouze aktualizuje název katalogu ve schématu a přejmenuje několik souborů na disku. Přestože je operace rychlá, relace používající starý katalog budou během procesu uzavřeny a pokusy o otevření nových relací budou čekat na dokončení operace. Přepnutí tedy není zcela bez dopadu, ale dopad je velmi krátkodobý. Starý katalog je během procesu smazán; pokud jej chcete zachovat, zálohujte jej před provedením operace nahrazení.