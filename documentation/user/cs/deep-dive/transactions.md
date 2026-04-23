---
title: Transakce
perex: Transakce jsou základní součástí databázového systému. Zajišťují, že databáze zůstává v konzistentním stavu i v případě selhání nebo souběžného přístupu více uživatelů. V tomto článku se budeme zabývat pojmem transakce, jejich vlastnostmi a tím, jak jsou implementovány v evitaDB.
date: '16.5.2025'
author: Jan Novotný
commit: c16b18f3b2b3adb0dbb4706690390fde6e39a39a
translated: 'true'
---
Čtenáři, kteří jsou obeznámeni s úrovněmi izolace databází, si možná vzpomenou, že evitaDB podporuje pouze [snapshot isolation](https://en.wikipedia.org/wiki/Snapshot_isolation), takže mohou přeskočit tuto úvodní kapitolu, která popisuje kontext vztahující se k této úrovni izolace.

Klíčovým konceptem transakce je, že data zapsaná transakcí jsou viditelná pouze v rámci této transakce a nikoliv v ostatních současně probíhajících čtecích sezeních/transakcích. Jakmile je transakce potvrzena (commit), její změny se stanou viditelnými pro všechny čtenáře, kteří otevřou novou transakci poté. V terminologii řízení souběžnosti s více verzemi (multi-version concurrency control) se tomu říká 'snapshot isolation': každý klient se chová, jako by měl plnou kopii databáze v okamžiku, kdy transakce začíná. Pokud klient transakci neotevře explicitně, je otevřena implicitně pro každý dotaz nebo aktualizační příkaz a automaticky uzavřena po jeho dokončení. Tato úroveň izolace zabraňuje konfliktům při zápisu — pokud se dvě paralelní transakce pokusí aktualizovat stejný záznam, jedna z nich bude vrácena zpět (rollback). Nezabraňuje však konfliktům při čtení a zápisu, známým také jako write skew. Například pokud existují dvě transakce A a B, transakce A přečte záznam X, přičte 1 k přečtené hodnotě a uloží výsledek do záznamu Y, a transakce B přečte záznam Y, odečte 1 od hodnoty a uloží výsledek do záznamu X, obě transakce budou úspěšně potvrzeny bez konfliktu. Nedoporučujeme používat evitaDB pro bankovní systémy, ale pro většinu ostatních případů použití je tato úroveň izolace dostačující.

## Životní cyklus transakce

Nejprve se posuňme k bodu, kdy je transakce potvrzena (commit). Poté vysvětlíme, jak izolujeme změny provedené nekomitovanými paralelními transakcemi. Commit transakce je vícekrokový proces, který zajišťuje, že všechny změny provedené během transakce jsou bezpečně zapsány do databáze a zpřístupněny ostatním čtenářům, jakmile jsou plně integrovány do všech indexů. Proces commitování se skládá z následujících kroků:

1. řešení konfliktů paralelních transakcí
2. zápis transakce do write-ahead logu (WAL)
3. zpracování obsahu WAL a vytvoření nové "verze" databáze
   - začlenění změn do indexů a zápis payloadů záznamů do datových souborů
   - zápis změn v indexech do datových souborů
   - kompakce datových souborů, pokud je to nutné ([podrobněji popsáno zde](storage-model.md#úklid-nepořádku))
4. výměna nové verze databáze za aktuální
5. propagace změn na čtecí uzly v clusterovém prostředí

V následujících sekcích popíšeme každý z těchto kroků podrobněji.

### 1. Řešení konfliktů

Prvním krokem je, aby procesor transakcí zkontroloval, že změny provedené paralelními transakcemi nejsou vzájemně vylučující. Každá transakce zná verzi databáze (`catalogVersion`), se kterou začala. Při potvrzení transakce je jí přiřazena nová `catalogVersion`, ve které budou změny této transakce začleněny. Verze katalogu je monotonicky rostoucí číslo, které se zvyšuje o jedna pro každou transakci.

Řešitel konfliktů musí prozkoumat všechny změny provedené mezi počáteční a koncovou verzí katalogu transakce. Všechny mutace v transakci vytvářejí tzv. klíč konfliktu, který je porovnáván s klíči konfliktů generovanými dříve potvrzenými transakcemi. Pokud dojde ke konfliktu, je vyvolána výjimka a transakce je vrácena zpět.

K dispozici jsou následující politiky řešení konfliktů:

1. Zamítnout změny stejné části entity.
    * Jednotlivý atribut entity
    * Jednotlivá asociovaná data
    * Jednotlivá cena
    * Jednotlivý odkaz
    * Umístění v hierarchii
2. Zamítnout změny stejné entity (výchozí strategie).
3. Zamítnout změny ve stejném katalogu.
4. Povolit konfliktní změny (poslední zápis vyhrává). Tato politika nikdy nevyvolá výjimku konfliktu.

Výchozí politika konfliktů je nastavena pro celý databázový engine v jeho konfiguraci. Strategie je řešena pro každou mutaci od nejkonkrétnější po nejméně konkrétní úroveň. Například pro mutaci atributu je pořadí řešení následující:

1. Politika konfliktů na úrovni atributu entity
2. Politika konfliktů na úrovni typu entity
3. Politika konfliktů na úrovni katalogu
4. Poslední zápis vyhrává.

První použitelná politika nalezená v tomto pořadí je použita k vyřešení konfliktu.

Mutace schématu neumožňují přizpůsobitelné politiky konfliktů a vždy používají stejnou strategii klíče konfliktu: mutace schématu entity zamítají změny ve stejné kolekci entit a mutace schématu katalogu zamítají změny ve stejném katalogu.

<Note type="info">

Existují také speciální typy bezpečných mutací, které mohou pomoci minimalizovat konflikty. Jedním příkladem je mutace atributu typu "delta", kterou lze bezpečně aplikovat paralelně.

</Note>

<Note type="warning">

Granulární řešení konfliktů je ve vývoji (viz [issue #503](https://github.com/FGForrest/evitaDB/issues/503)).

Naším cílem je, aby obecná politika konfliktů byla přepsatelná na úrovni katalogu, schématu, typu entity nebo podsystému schématu (např. atribut, asociovaná data atd.). Věříme, že tento přístup poskytuje potřebnou univerzálnost a jemné řízení.

</Note>

### 2. Zápis do write-ahead logu

Všechny transakce a jejich mutace v databázích evitaDB jsou nejprve zapsány do [write-ahead logu](https://en.wikipedia.org/wiki/Write-ahead_logging). Write-ahead log (zkráceně WAL) je podrobněji popsán v [článku o úložném modelu](storage-model.md#write-ahead-log-wal). Transakce jsou do WAL přidávány v pořadí, v jakém jsou potvrzovány, přičemž verze katalogu následujících transakcí jsou vždy vyšší než u předchozích transakcí. Zápis do WAL je blokující operace, což znamená, že procesor transakcí čeká, dokud není celá transakce úspěšně zapsána do WAL souboru a synchronizována s diskem. To zajišťuje, že je transakce trvalá a lze ji obnovit v případě havárie.

<Note type="info">

Ve skutečnosti existují dva typy WAL souborů. První typ, "izolovaný" WAL, je vytvořen pro každou transakci v samostatném souboru. To umožňuje zapisovat transakce do jejich příslušných izolovaných WAL souborů současně (paralelně bez vzájemného blokování). Druhým typem je "globální" WAL soubor, do kterého jsou izolované WAL soubory transakcí kopírovány v jedné nízkoúrovňové operaci. Tato kopírovací operace je prováděna sekvenčně ve fázi "WAL persistence", jakmile je každá transakce zpracována. Izolované WAL soubory jsou ihned po zkopírování obsahu do globálního WAL souboru odstraněny.

**Poznámka:** Usnadnili jsme si život tím, že jsme vynutili jediného zapisovače pro všechny datové soubory. Jinými slovy, to znamená, že můžeme zpracovávat transakce sekvenčně. Pokud bychom věděli, že transakce zapisují do neprolínajících se datových souborů, mohli bychom data zapisovat paralelně. Nicméně, protože to neočekáváme jako běžný případ použití, rozhodli jsme se to neimplementovat.

</Note>

WAL se neúčastní standardního [procesu kompakce](storage-model.md#úklid-nepořádku), takže by rostl donekonečna. Proto je nakonfigurován práh pro omezení maximální velikosti WAL souboru. Pokud je tohoto prahu dosaženo, evitaDB začne zapisovat do samostatného souboru (segmentu), ale původní soubor ponechá na místě. Jedna transakce musí být vždy plně zapsána do stejného WAL segmentu, takže obrovské transakce mohou způsobit, že velikost WAL souboru překročí nastavené limity. Počet uchovávaných WAL souborů je omezen a tento limit může být překročen pouze v případě, že změny v nich ještě nebyly aplikovány do indexů. WAL soubory jsou odstraněny, jakmile procesor transakcí potvrdí, že všechny změny byly aplikovány do indexů (a pokud běží v distribuovaném režimu, také propagovány na všechny ostatní uzly).

WAL soubory jsou klíčovou součástí databáze a slouží k následujícím účelům:

* aplikace zmeškané potvrzené transakce do indexů na primárním uzlu (obnova po havárii)
* replikace změn mezi více čtecími uzly
* [záloha a obnova v čase (PITR)](../operate/backup-restore.md)
* [zachycení změn dat (CDC)](../use/api/capture-changes.md) — streamování změn do externích systémů
* auditování v evitaLab

Až do tohoto bodu, pokud dojde k výjimce, je transakce efektivně vrácena zpět, ale svět se točí dál.

### 3. Zpracování obsahu WAL

V této fázi čteme nezpracovaný obsah WAL souboru a aplikujeme změny do indexů aktuální verze katalogu a vytváříme payload záznamy ve sdílených datových souborech. Tyto změny jsou izolované a stále neviditelné pro ostatní čtenáře. Tato fáze funguje v režimu "časového okna", což znamená, že se snaží přehrát co nejvíce transakcí v rámci vyhrazeného časového okna. Když je dosaženo časového limitu nebo byl zpracován celý obsah WAL souboru, procesor transakcí vytvoří novou instanci datové struktury katalogu s konkrétní verzí (tj. poslední zpracovaná transakce dostane hodnotu proměnné catalogVersion). Tato nová instance je poté předána do další fáze.

Pokud engine selže v této fázi, veškeré zpracování transakcí bude zastaveno. Engine nemůže přeskočit potvrzenou transakci uloženou ve WAL souboru, ani nemůže postoupit k další. Bude se neustále pokoušet přehrát poslední transakci z WAL souboru. To nakonec signalizuje chybu v databázovém engine, kterou je třeba analyzovat a opravit. Mechanismus však nezacyklí vlákno do nekonečné smyčky; vždy se pokusí zpracovat problematickou transakci znovu, jakmile bude potvrzena nová transakce.

<Note type="info">

Úložný model evitaDB je striktně pouze pro přidávání (append-only). Jakmile je záznam zapsán do datového souboru, nelze jej změnit ani smazat. Toto zásadní rozhodnutí nám umožňuje vyhnout se složitosti synchronizace mezi zapisovacími a čtecími vlákny. Záznamy v souboru jsou lokalizovány pomocí ukazatelů uložených v indexech. Pokud v indexu není ukazatel na záznam, je záznam považován za odpad a je automaticky odstraněn při příští kompakci.

</Note>

### 4. Propagace nové verze katalogu

Tato fáze je velmi rychlá a jednoduše zahrnuje nahrazení kořenového ukazatele "aktuální" instance katalogu nově vytvořenou. Jakmile je hotovo, nově vytvořená sezení/transakce uvidí změny provedené v této konkrétní transakci. Starší instance katalogu není okamžitě odstraněna, ale je ponechána v paměti, dokud nejsou uzavřena všechna sezení používající tuto verzi katalogu (jak vyžaduje úroveň izolace snapshot). Jakmile již žádná sezení nepoužívají starou instanci katalogu, je odstraněna z paměti a poté se o ni postará garbage collector Javy.

## Horizontální škálování

<Note type="warning">

Distribuovaný režim evitaDB je stále ve vývoji (viz [issue #109](https://github.com/FgForrest/evitaDB/issues/109)).

</Note>

Plánujeme implementovat replikační model podobný [streaming replication v PostgreSQL](https://scalegrid.io/blog/comparing-logical-streaming-replication-postgresql/)
nebo [statement-based replication v MySQL](https://dev.mysql.com/doc/refman/8.0/en/replication-sbr-rbr.html). evitaDB je navržena pro prostředí s převahou čtení, proto plánujeme model s jedním masterem a více čtecími uzly s dynamickou volbou mastera (leader election). Všechny zápisy budou směřovat na master uzel, který bude udržovat primární [WAL](storage-model.md#write-ahead-log-wal).

Všechny čtecí uzly udržují otevřené spojení s master uzlem, streamují a lokálně přehrávají změny v WAL souboru. Toto spojení je vždy otevřené a stahuje všechny změny obsažené ve WAL souboru na každý replikační uzel. To znamená, že jakmile zpracování transakce dosáhne finální fáze [propagace katalogu](#4-propagace-nové-verze-katalogu), mutace se začnou streamovat na repliky. Všechny konflikty jsou řešeny na master uzlu před potvrzením transakce a zápisem do WAL souboru, takže se očekává, že všechny mutace budou úspěšně zpracovány všemi replikami.

Když je do clusteru přidán nový replikační uzel, vybere si jinou repliku nebo master uzel a stáhne si binární verzi jejich datových souborů, které jsou očištěny od zastaralých záznamů (viz [aktivní záloha](storage-model.md#zálohování-a-obnova)). Streamovací producent zahazuje zastaralé záznamy od správce datových souborů (vybraný replikační nebo master uzel) za běhu, takže na nový uzel není streamována žádná zastaralá data. Proces zálohování je vždy uzamčen na platná data v konkrétní verzi katalogu (včetně pozice ve WAL souboru) a díky append-only povaze datových souborů není nutné pozastavovat zpracování nových transakcí na tomto uzlu a uzel funguje jako obvykle. Všechny tyto operace probíhají na "binární úrovni", takže vytvoření nové repliky je poměrně rychlý proces.

## Izolace paralelních transakcí podrobně

Je třeba řešit dvě oblasti týkající se transakcí:

1. payload dat záznamů
2. indexy odkazující na tato data

### Izolace nekomitovaných payload dat záznamů

Nekomitované transakce zapisují svá payload data do dočasné paměti, která je vymazána po dokončení transakce (ať už commit, nebo rollback). Dlouhé transakce s velkými payloady mohou aktuálně ovlivnit zdraví databázového engine, proto doporučujeme se takovým transakcím vyhýbat.

<Note type="warning">

Transakční paměť se nachází na Java heapu spolu se všemi ostatními klíčovými datovými strukturami evitaDB. Pokud je transakce velmi velká, může spotřebovat veškerou dostupnou paměť, což způsobí `OutOfMemoryException` a ovlivní zbytek systému, včetně pouze-čtecích sezení. Abychom tomu zabránili, musíme omezit rozsah transakce. Získání informací o velikosti datových struktur však není na [Java platformě](https://dzone.com/articles/java-object-size-estimation-measuring-verifying) jednoduchý úkol a můžeme získat pouze hrubý odhad. Plánujeme vypočítat odhad velikosti transakce a omezit celkovou velikost transakce i celkovou velikost všech paralelně zpracovávaných transakcí, abychom zabránili vyčerpání veškeré volné paměti. Ale toto je stále ve vývoji - [viz issue #877](https://github.com/FgForrest/evitaDB/issues/877).

</Note>

### Izolace změn v paměťových indexech

Druhá část — indexy — je místo, kde leží veškerá složitost. Často používaným přístupem v databázích je mechanismus zamykání tabulek/řádků nebo číslo ID transakce uložené vedle ukazatele na záznam v B-stromu. To se konzultuje, když má být záznam přečten/aktualizován v konkrétní transakci (viz [Goetz Graefe Modern B-Tree Techniques](https://w6113.github.io/files/papers/btreesurvey-graefe.pdf), nebo tým Helsinské univerzity [Concurrency Control for B-Trees with Differential Indices](https://citeseerx.ist.psu.edu/viewdoc/download?doi=10.1.1.85.602&rep=rep1&type=pdf)).

Protože všechny indexy držíme v paměti, uchovávání 8B `catalogVersion` vedle každého záznamu by vyžadovalo hodně paměti, a proto můžeme použít jiný přístup: softwarová transakční paměť. Myšlenka je udržovat původní datovou strukturu neměnnou a vytvořit vrstvu rozdílů (diff), která se aplikuje za běhu při čtení upravených dat, ale pouze vláknem, které zpracovává otevřenou transakci. Tímto způsobem se vyhneme zamykání indexů a umožníme více transakcím číst a zapisovat do sdílené "neměnné" základny současně.

Naše indexy se skládají z více transakčních datových struktur, které jsou podrobněji vysvětleny v [referenční sekci](#transakční-datové-struktury).

#### Softwarová transakční paměť

Izolujeme souběžné aktualizace indexů prováděné různými vlákny tím, že je ukládáme do samostatných paměťových bloků ve formě překryvné vrstvy rozdílů pro čtení a zápis, která obaluje původní, neměnnou datovou strukturu. Když vlákno čte data během transakce, přistupuje k nim přes překryvnou vrstvu, která aplikuje rozdíl v reálném čase, což umožňuje transakci dynamicky vidět své vlastní změny. Pokud v transakci nejsou žádné aktualizace, nejsou ani žádné vrstvy rozdílů, což znamená, že transakce čte přímo z podkladové neměnné datové struktury. Protože jsou překryvné vrstvy uloženy v ThreadLocal objektu navázaném na vlákno zpracovávající konkrétní transakci, transakce nemohou vidět změny ostatních. Tento přístup je často označován jako softwarová transakční paměť [STM](https://en.wikipedia.org/wiki/Software_transactional_memory).

##### Atomické transakce

Jediný způsob, jak učinit transakční změny atomickými, je shromáždit všechny změny do volatilní vrstvy rozdílů, která je použita pouze pro konkrétní transakci. Při potvrzení transakce musí být vytvořena nová instance celé datové struktury katalogu (tj. nové instance aktualizovaných kolekcí entit, indexů atd.). Tato nová instance poté nahradí aktuální instanci katalogu jediným voláním metody `AtomicReference#compareAndExchange`.

<Note type="info">

Ačkoli uvádíme, že celá datová struktura katalogu má být reinstancována, není to zcela pravda. Pokud by tomu tak bylo, byly by transakce příliš nákladné pro velké datové sady a mechanismus by nebyl proveditelný. Ve skutečnosti vytváříme nové instance pouze pro změněné části datových struktur katalogu a samotný katalog. Představte si datovou strukturu katalogu jako strom, s instancí katalogu na vrcholu a všemi vnitřními daty jako orientovaný acyklický graf. Uvědomíte si, že nové instance jsou potřeba pouze pro změněnou datovou strukturu samotnou plus všechny její "rodiče" směrem ke katalogu na vrcholu grafu. Tato technika se nazývá [path copying](https://en.wikipedia.org/wiki/Persistent_data_structure#Path_copying).

</Note>

Pokud je transakce vrácena zpět, jednoduše zahodíme celý paměťový blok vrstvy rozdílů z ThreadLocal proměnné a necháme garbage collector Javy, aby se o něj postaral.

<Note type="info">

Přístup s vrstvou rozdílů se nepoužívá ve speciálním případě <SourceClass>evita_store/evita_store_key_value/src/main/java/io/evitadb/store/offsetIndex/OffsetIndex.java</SourceClass>. Tento index sleduje všechny pozice záznamů v datovém souboru v jedné hash mapě, což umožňuje rychlý přístup k payload záznamům v čase O(1). Přestavba tohoto indexu pomocí vrstvy rozdílů by znamenala neustálé realokace celé hash mapy, což by bylo neefektivní. Proto tento index uchovává pouze nejnovější ukazatele na záznamy a sleduje všechny změny této mapy mezi aktuální a předchozí verzí katalogu. Tato historie je uchovávána tak dlouho, dokud existuje sezení používající konkrétní starou verzi katalogu. Sezení zaměřená na staré verze katalogu narazí na hodnotu, kterou by neměla vidět, takže analyzují historii tohoto záznamu a získají správný ukazatel pro svou verzi katalogu.

</Note>

##### Prevence ztráty aktualizací

Klíčovým problémem popsaného přístupu je, že aktualizace mohou být snadno ztraceny, pokud vrstva rozdílů není aplikována na původní datovou strukturu a zahrnuta do procesu nové instanciace katalogu.

Abychom tomuto problému předešli, sledujeme každou vrstvu rozdílů vytvořenou v konkrétní transakci a označíme ji jako spotřebovanou procesem instanciace při potvrzení transakce. Nakonec na konci transakce zkontrolujeme, že všechny vrstvy rozdílů byly označeny jako zpracované; pokud ne, je vyvolána výjimka a transakce je vrácena zpět. To připomíná [podvojné účetnictví](https://www.investopedia.com/terms/d/double-entry.asp), kde každá kladná operace musí být doprovázena zápornou.

Takové problémy se vyskytují během vývoje, takže musí existovat způsob, jak tyto problémy identifikovat a vyřešit. To je ve skutečnosti velmi obtížné, protože může existovat tisíce vrstev rozdílů a přiřazení konkrétní vrstvy jejímu tvůrci/vlastníkovi je náročné. Proto přiřazujeme jedinečné ID verze transakčního objektu při vytvoření vrstvy rozdílů a zahrnujeme jej do výjimky vyvolané pro nesebrané vrstvy rozdílů. Když problémovou transakci replikujeme v testu, vrstva rozdílů dostane opakovaně stejné ID verze a můžeme sledovat přesný okamžik a místo jejího vzniku umístěním podmíněného breakpointu do třídy generátoru ID verze.

##### Testování

Integrita datových struktur je pro databázi zásadní. Kromě standardních jednotkových testů existuje vždy jeden "generační" test, který používá [property based testing approach](https://en.wikipedia.org/wiki/Property_testing). Tyto testy používají dvě datové struktury: naši testovanou STM implementaci a [test double](https://en.wikipedia.org/wiki/Test_double) reprezentovanou ověřenou externí implementací. Například v generačním testu datové struktury <SourceClass>evita_engine/src/main/java/io/evitadb/index/map/TransactionalMap.java</SourceClass> používáme implementaci JDK HashMap jako test double.

Testovací sekvence je vždy podobná.

1. na začátku testu jsou vytvořeny jak testovaná instance, tak test double instance
2. obě jsou naplněny stejnými počátečními náhodnými daty
3. v iteraci s náhodným počtem opakování:
   - je vybrána náhodná operace a provedena s náhodnou hodnotou na obou instancích (například "vložit hodnotu X" nebo "odstranit hodnotu Y")
   - test poté ověří, že změny jsou ihned viditelné na testované instanci
   - transakce je potvrzena (commit)
4. po commitu jsou obsahy obou datových struktur porovnány a musí být shodné
5. nové instance datových struktur jsou vytvořeny s počátečními daty převzatými z výsledku commitu
6. kroky 3-5 se opakují do nekonečna

Jakmile tento generační test běží několik minut bez problémů, můžeme si být jisti, že STM datová struktura je správně implementována. Vždy však existuje malá šance, že samotný test je nesprávný. [Quis custodiet ipsos custodes?](https://en.wikipedia.org/wiki/Quis_custodiet_ipsos_custodes%3F)

## Transakční datové struktury

<Note type="warning">

**Datové struktury budou nahrazeny**

Většina transakčních datových struktur je suboptimální, protože při commitu kopíruje celý obsah do nové instance třídy (původní instance musí zůstat nezměněná pro ostatní čtenáře). Naším hlavním cílem byl výkon čtení, takže výkon zápisu nebyl prioritou. Plánujeme v budoucnu zlepšit výkon těchto datových struktur (viz issue [#760](https://github.com/FgForrest/evitaDB/issues/760)) a vytvořit Clojure datové struktury, které jsou ověřené a fungují v prostředí přátelském k neměnnosti (například [HAMT](https://en.wikipedia.org/wiki/Hash_array_mapped_trie)).

</Note>

### Transakční pole (seřazené)

Transakční pole (např. <SourceClass>evita_engine/src/main/java/io/evitadb/index/array/TransactionalIntArray.java</SourceClass> a podobné) napodobuje chování běžného pole a existuje několik jeho implementací.

- TransactionalIntArray: pro ukládání primitivních čísel `int`
- TransactionalObjectArray: pro ukládání běžných objektů libovolného typu
- TransactionalComplexObjectArray: pro ukládání vnořených objektových struktur, které umožňují slučování a automatické odstranění "prázdných" kontejnerů.

Všechna pole jsou přirozeně seřazena. V případě objektových implementací musí být objekt porovnatelný. Implementace pole neumožňuje duplicitní hodnoty. Proto v případě jakýchkoli vkládání/odstraňování pole interně ví, které indexy budou ovlivněny. Není možné nastavit hodnotu na index předaný z vnější logiky.

Všechny tyto implementace sdílejí stejnou myšlenku: v transakčním režimu všechny aktualizace směřují do transakční překryvné vrstvy, která zachycuje:

- vkládání na určité indexy v interním poli vložených hodnot
- mazání na určité indexy v interním poli odstraněných indexů.

Pomocí těchto informací může STM pole sestavit nové pole kombinující původní hodnoty se všemi změnami. Aby se zabránilo vytváření nového pole (a alokacím paměti) pro každou operaci, existují optimalizované metody, které pracují přímo na diffu:

* `indexOf`
* `contains`
* `length`

Třída <SourceClass>evita_engine/src/main/java/io/evitadb/index/array/TransactionalComplexObjArray.java</SourceClass> je mnohem složitější — přijímá funkce, které pracují s vnořenými strukturami.

- `BiConsumer producer`: tato funkce vezme dva kontejnery a spojí je do jednoho výstupního kontejneru obsahujícího agregovaná vnořená data
- `BiConsumer reducer`: tato funkce vezme dva kontejnery a odstraní/odečte vnořená data druhého kontejneru od prvního
- `Predicate obsoleteChecker`: tato funkce testuje, zda kontejner obsahuje nějaká vnořená data. Pokud ne, může být kontejner považován za odstraněný; predikát je konzultován po redukční operaci

Tato implementace umožňuje částečně aktualizovat objekty, které obsahuje. Například uvažujme záznam s následující strukturou:

* `String: label`
* `int[]: recordIds`

Pokud pak vložíme dva takové záznamy do TransactionalComplexObjectArray s následujícími daty:

* label = `a` recordIds = `[1, 2]`
* label = `a` recordIds = `[3, 4]`

Pole vrátí výsledek s jedním záznamem: `a`: `[1, 2, 3, 4]`.

Pokud odstraníme záznam `a`: `[2, 4]`, pole vrátí výsledek: `a`: `[1, 3]`.

Pokud odstranění aplikujeme znovu - `a`: `[1, 3]`, pole vrátí prázdný výsledek.

Bohužel s touto implementací nemůžeme poskytnout optimalizované metody jako:

* `indexOf`
* `length`

Nejprve musíme vypočítat celé sloučené pole, abychom k těmto vlastnostem získali přístup. Tato datová struktura by mohla být významně optimalizována, ale je také poměrně náročná na správnou implementaci kvůli povaze vnořených struktur.

### Transakční neuspořádané pole

Tato verze transakčního pole se liší od [předchozí](#transakční-pole-seřazené) tím, že umožňuje duplicitní hodnoty. Je také neuspořádané a umožňuje klientovi řídit, kam budou nové hodnoty vloženy nebo stávající odstraněny.

Databáze vyžaduje pouze jednu implementaci této struktury: <SourceClass>evita_engine/src/main/java/io/evitadb/index/array/TransactionalUnorderedIntArray.java</SourceClass>.

Implementace vrstvy rozdílů pro neuspořádané pole je v podstatě stejná jako pro [uspořádané pole](#transakční-pole-seřazené), s jednou výjimkou. Vložené hodnoty si uchovávají informaci o relativním indexu v rámci segmentu vloženého na konkrétní pozici v původním poli.

Toto pole má speciální, rychlou implementaci, která pracuje na vrstvě rozdílů pro následující metody:

* `indexOf`
* `contains`
* `length`

### Transakční seznam

<SourceClass>evita_engine/src/main/java/io/evitadb/index/list/TransactionalList.java</SourceClass> napodobuje chování rozhraní Java util List, což umožňuje obsahovat libovolný objekt. Seznam může obsahovat duplicity a je neuspořádaný. Aktuální implementace je suboptimální a mohla by být vylepšena podobně jako [neuspořádané pole](#transakční-neuspořádané-pole).

Vrstva rozdílů obsahuje seřazenou množinu indexů, které byly odstraněny z původního seznamu, a také mapu nových hodnot a indexů, na které byly vloženy. Když je do vrstvy rozdílů vložena nebo z ní odstraněna nová položka, všechny indexy za touto hodnotou je třeba inkrementovat nebo dekrementovat. Proto má operace "přidat/odebrat první" vždy složitost O(N). Naproti tomu neuspořádané pole rozděluje vkládání do více segmentů, takže složitost je obvykle nižší — O(N) je pouze nejhorší případ pro neuspořádané pole.

### Transakční mapa

Třída <SourceClass>evita_engine/src/main/java/io/evitadb/index/map/TransactionalMap.java</SourceClass> napodobuje chování rozhraní Java `java.util.Map`, což umožňuje obsahovat libovolné páry klíč/hodnota. V tomto případě je implementace přímočará: vrstva rozdílů obsahuje množinu všech klíčů odstraněných z původní mapy a také mapu všech párů klíč/hodnota, které byly aktualizovány nebo vloženy do mapy.

Když se logika pokusí získat hodnotu z mapy, nejprve se konzultuje vrstva rozdílů, aby se zjistilo, zda klíč existuje v původní mapě bez příkazu k odstranění ve vrstvě, nebo zda byl do vrstvy přidán. Iterátor položek/klíčů/hodnot nejprve iteruje přes všechny existující, neodstraněné položky, a poté přes položky přidané do vrstvy rozdílů.

### Transakční množina

Třída <SourceClass>evita_engine/src/main/java/io/evitadb/index/set/TransactionalSet.java</SourceClass> napodobuje chování rozhraní Java `java.util.Set`. V tomto případě je implementace přímočará: vrstva rozdílů obsahuje množinu všech klíčů odstraněných z původní mapy a také množinu přidaných klíčů.

### Transakční bitmapa

Bitmapa je množina unikátních celých čísel v rostoucím pořadí. Tato datová struktura je podobná [transakčnímu poli](#transakční-pole-seřazené), ale je omezena pouze na celá čísla a umožňuje mnohem rychlejší operace s množinou čísel. Tato implementace obaluje instanci interní třídy RoaringBitmap. Důvody použití této datové struktury a podrobnější informace o [RoaringBitmaps](https://github.com/RoaringBitmap/RoaringBitmap) jsou uvedeny v [kapitole o vyhodnocování dotazů](https://evitadb.io/research/in-memory/thesis#boolean-algebra). RoaringBitmap je mutabilní datová struktura a knihovna třetí strany. Protože nad ní nemáme kontrolu, chtěli jsme ji skrýt pomocí našeho rozhraní. Proto bylo vytvořeno rozhraní <SourceClass>evita_engine/src/main/java/io/evitadb/index/bitmap/Bitmap.java</SourceClass>, aby celý kód pracoval s ním místo přímo s `RoaringBitmap`.

<SourceClass>evita_engine/src/main/java/io/evitadb/index/bitmap/TransactionalBitmap.java</SourceClass> umožňuje zachytit vkládání a mazání z původní bitmapy ve vrstvě rozdílů. Když je bitmapa potřeba pro čtení, je nová `RoaringBitmap` vypočtena aplikací vkládání pomocí booleovského AND na původní bitmapu a aplikací mazání pomocí booleovského AND NOT za běhu. Aby se zabránilo této nákladné operaci, výsledek je uložen do mezipaměti pro následné čtecí požadavky, ale musí být vymazán při prvním následném zápisu.

Tato výpočetní metoda dvakrát klonuje celou původní `RoaringBitmap` a je tedy suboptimální. Bohužel `RoaringBitmap` nám neposkytuje lepší možnosti. Ideální implementace by vyžadovala, aby byla `RoaringBitmap` interně neměnná a při každé zápisové operaci vytvářela novou instanci. Protože `RoaringBitmaps` interně pracují se samostatnými bloky dat, nová neměnná verze by mohla znovu použít všechny bloky, které nebyly zápisem ovlivněny, a klonovat nebo měnit pouze bloky, kde ke změnám došlo. To by však vyžadovalo zásadní změny v interní implementaci a pravděpodobně by to bylo autorským týmem zamítnuto.

### B+ strom

Jedná se o standardní implementaci B+ stromu s transakční vrstvou rozdílů. Implementace vytváří vrstvu rozdílů pro každý upravený listový segment stromu a také pro řetězec rodičů až ke kořeni, s novými instancemi, které odkazují na podkladové vrstvy rozdílů (a původní, neupravené listy). Nový B+ strom je materializován v okamžiku commitu. Celý proces připomíná copy-on-write datové struktury, ale kopírované bloky jsou poměrně malé ve srovnání s celým stromem. B+ strom se používá pro všechny indexy, které vyžadují seřazené klíče.

### Sekvence

evitaDB používá několik sekvencí pro přiřazování unikátních, monotonicky rostoucích identifikátorů různým objektům. Tyto sekvence nejsou součástí transakčního procesu a jejich průběh není vracen zpět. Všechny sekvence jsou interně implementovány buď jako `AtomicLong`, nebo `AtomicInteger`, což umožňuje získání inkrementovaných hodnot thread-safe způsobem.

V současnosti neplánujeme podporovat režim více zapisovačů v [distribuovaném prostředí](#horizontální-škálování), což znamená, že můžeme bezpečně používat interní atomické typy Javy pro správu sekvencí, protože nová čísla budou vždy vydávána jediným master uzlem (JVM).

Sekvence spravované evitaDB jsou:

* sekvence typů entit: přiřazuje nové ID každé kolekci entit, což umožňuje adresovat kolekci malým číslem místo duplikování a zvětšování velikosti řetězcové hodnoty
* sekvence primárních klíčů entit: přiřazuje nové ID každé vytvořené entitě, pokud schéma entity vyžaduje automatické přiřazení primárního klíče
* sekvence primárních klíčů indexu: přiřazuje nové ID každému vytvořenému internímu indexu
* sekvence ID transakce: přiřazuje nové ID každé otevřené transakci