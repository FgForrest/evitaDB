---
title: Hromadné vs. inkrementální indexování
perex: 'evitaDB je navržena jako rychlá, transakční, pro čtení optimalizovaná databáze, která odlehčuje práci primárnímu datovému úložišti, jímž bývá obvykle nějaká relační databáze. Očekává se proto, že bude fungovat ve dvou odlišných fázích: počáteční indexace velkého datasetu a následná údržba indexu po celou dobu jeho životnosti. Tyto dvě fáze mají odlišné požadavky a proto je k nim přistupováno speciálně.'
date: '24.8.2028'
author: Ing. Jan Novotný
commit: '540315a5aa4d3e8d181eaed02c1b6f1e26664bf6'
translated: true
---
## Hromadné indexování (FÁZE WARM-UP)

Hromadné indexování se používá pro rychlé indexování velkého množství zdrojových dat z externího datového úložiště. V této počáteční fázi životního cyklu katalogu není vyžadována podpora transakcí ani souběžnost. Cílem je pouze zaindexovat co nejvíce dat v co nejkratším čase. Tato fáze má následující charakteristiky:

1. V jeden okamžik může být otevřen pouze jeden klient (jedna relace).
2. Není možné provést návrat změn (rollback) – pokud dojde k chybě, klient musí obnovu řešit sám.
3. Veškeré změny indexů jsou uchovávány v paměti a zapsány až při uzavření relace; v případě pádu databáze jsou všechny změny ztraceny.

Po dokončení počátečního indexování by měl klient ukončit fázi warm-up uzavřením relace a provedením mutace `MakeCatalogAlive`, která katalog převede do fáze ALIVE (viz následující kapitola). <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass> poskytuje pro tento účel metodu `goLiveAndClose`. Tuto změnu můžete vyvolat také metodou `makeCatalogAlive` ve <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass>.</LS>

## Inkrementální indexování (FÁZE ALIVE)

Inkrementální indexování je fáze, ve které průběžně synchronizujeme změny z primárního datového úložiště do evitaDB. Může být otevřeno více klientů (relací) současně, některé pouze čtou, jiné zapisují. Každá čtecí-zapisovací relace definuje hranici transakce a změny lze atomicky potvrdit nebo vrátit zpět (podrobnosti o ACID najdete v [kapitole o transakcích](transactions.md)). Výkon zápisu je v této fázi výrazně nižší než při hromadném indexování, protože je nutné udržovat transakční integritu, souběžnost a trvanlivost změn. Výkon čtení tím není ovlivněn a zůstává velmi vysoký.

## Kompletní reindexace živého katalogu

Nastávají situace, kdy je potřeba znovu zaindexovat celý katalog z primárního datového úložiště, a přitom stále obsluhovat živý provoz z aktuálních dat. Doporučený postup je vytvořit nový dočasný katalog a naplnit jej počáteční sadou dat pomocí hromadného indexování. Jakmile je nový katalog plně zaindexován, můžete aplikaci přepnout na nový katalog pomocí operace nahrazení katalogu. <LS to="j">Pro tento účel existuje metoda `replaceCatalog` v rozhraní <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass>.</LS> Nahrazení katalogu je velmi rychlá operace, která nevyžaduje kopírování dat – pouze aktualizuje název katalogu ve schématu a přejmenuje několik souborů na disku. Přestože je operace rychlá, relace používající starý katalog budou během procesu uzavřeny a pokusy o otevření nových relací budou čekat na dokončení operace. Přepnutí tedy není zcela bez dopadu, ale dopad je velmi krátkodobý. Starý katalog je během procesu smazán; pokud jej chcete zachovat, zálohujte jej před provedením operace nahrazení.