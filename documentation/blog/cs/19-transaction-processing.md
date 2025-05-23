---
title: Naslouchání tepu při zpracování transakcí
perex: |
  Zápis dat v evitaDB je specifický, protože k datům na disku se chová jako k neměnným. Všechny změny jsou připojovány na konec souborů formou přírůstků. Tento přístup má své pozitivní i negativní stránky. Jednou z nevýhod je to, že musí docházet ke kompakci dat, protože soubory se postupně plní zastaralými záznamy, které se provádí v rámci zpracování transakce synchronně, když jsou splněny podmínky pro detekci nadměrného množství zastaralých dat v datovém souboru. Tento fakt se může propisovat do latence při commitu transakce. V tomto článku se podíváme na to, jak můžeme na tento fakt reagovat na straně klienta. 
date: '16.05.2025'
author: 'Jan Novotný'
motive: assets/images/19-transaction-processing.png
proofreading: 'done'
draft: true
---
Na začátku si pojďme ukázat nejjednodušší formu transakčního zápisu dat do evitaDB:

```java
final int appleBrandPk = evita.updateCatalog(
		"evita-tutorial",
		session -> {
			// create a new brand
			return session.createNewEntity(BrandEditor.class)
				.setName("Apple", Locale.ENGLISH)
				.upsertVia(session)
				.getPrimaryKey();
		}
	);
```

Z pohledu klienta vypadá těchto pár řádků jednoduše, ale pod kapotou databázového serveru se děje [spousta věcí](https://evitadb.io/documentation/deep-dive/transactions#lifecycle-of-a-transaction). Když příklad jen o trošku rozšíříme, začneme se objevovat další zajímavé otázky:

```java
final int appleBrandPk = evita.updateCatalog(
    "evita-tutorial",
    session -> {
        // create a new brand
        return session.createNewEntity(BrandEditor.class)
            .setName("Apple", Locale.ENGLISH)
            .upsertVia(session)
            .getPrimaryKey();
    },
    CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
);
```

Druhý příklad, který je ekvivalentní tomu prvnímu, explicitně stanovuje, že klient chce po ukončení sezení / transakce čekat na propagaci změn do sdíleného stavu databáze, kdy jsou vidět všem klientům. Tedy, že chce synchronně čekat na úplné dokončení transakce. Asynchronní varianta téhož příkladu by vypadala takto:

```java
final CompletionStage<Integer> appleBrandPk = evita.updateCatalogAsync(
    "evita-tutorial",
    session -> {
        // create a new brand
        return session.createNewEntity(BrandEditor.class)
                .setName("Apple", Locale.ENGLISH)
                .upsertVia(session)
                .getPrimaryKey();
    },
    CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
);
appleBrandPk.thenAccept(pk -> System.out.println("Brand created with pk: " + pk));
```

V tomto případě klient nečeká na dokončení transakce, ale okamžitě po ukončení lambdy dostává "příslib" ve formě `CompletionStage`, na který se může napojit a reagovat na úspěšné dokončení transakce a přidělený primární klíč značky Apple zpracovat. Tento přístup se často používá v tzv. reaktivním programování, kdy se snažíme minimalizovat blokování vláken a čekání na dokončení operací.

## Fáze zpracování transakce

Enumerace <SourceClass>evita_api/src/main/java/io/evitadb/api/TransactionContract.java</SourceClass> naznačuje další možnosti, které klient může zvolit. Pojďme si je v krátkosti představit:

- **`WAIT_FOR_CONFLICT_RESOLUTION`**: tato fáze je ukončena ve chvíli, kdy může serverová část potvrdit, že naše transakce neobsahuje konfliktní změny s ostatními transakcemi, které běžely paralelně a dokončily se dřív než naše transakce 
- **`WAIT_FOR_WAL_PERSISTENCE`**: tato fáze je ukončena ve chvíli, kdy server bezpečně uloží všechny změny naší transakce na disk a dokončí operaci [fSync](https://en.wikipedia.org/wiki/Sync_(Unix)), po dokončení této fáze se může klient spolehnout na to, že data se jednou bezpečně dostanou do sdíleného stavu, i když tam ještě stále nejsou
- **`WAIT_FOR_CHANGES_VISIBLE`**: tato fáze je ukončena ve chvíli, kdy se do sdíleného stavu databáze dostaly všechny změny naší transakce a jsou viditelné pro ostatní klienty, kteří se připojí k databázi

Jak jsme již naznačili v úvodu tohoto článku, nejsložitější fází v celém zpracování transakce je fáze zapracování našich změn do sdíleného stavu databáze. Zároveň na konci této fáze může dojít k tzv. [kompakci dat](https://evitadb.io/documentation/deep-dive/storage-model#cleaning-up-the-clutter), který vyžaduje nemálo iops a tím pádem může způsobit viditelnou latenci při zápisu dat do sdíleného stavu.

Většina klientů nepotřebuje své změny vidět ihned potom, co dokončí transakci - obvykle stačí, když dostane ujištění, že všechny změny jsou akceptovány a zapsány do trvalého úložitě a již se nikde "neztratí" a tudíž není třeba operace v rámci transakci opakovat, či jiným způsobem reagovat na selhání zápisu. Těmto klientům stačí tedy čekat na fázi `WAIT_FOR_WAL_PERSISTENCE` a tím si i zkrátit čekání na ukončení transakce z jejich pohledu.

## Potíže s timeoutem v gRPC protokolu

Pokud používáme klient/server architekturu evitaDB (tj. neprovozujeme evitaDB jako součást naší Java aplikace), komunikuje klient se serverem pomocí [protokolu gRPC](https://grpc.io/docs/). Tento protokol je postaven na HTTP/2 a umožňuje efektivní komunikaci mezi klientem a serverem. Jednou z vlastností gRPC je možnost nastavit timeout pro jednotlivé volání. Pokud server neodpoví do stanoveného časového limitu, klient obdrží chybu.

Operace pro ukončení sezení / transakce je v gRPC implementována jako unární volání, což znamená, že klient odešle požadavek a čeká na odpověď. Pokud server neodpoví do stanoveného časového limitu, klient obdrží chybu. Pokud klient čeká na propagaci změn do sdíleného stavu a timeout je nastaven přísně, může se stát, že se této fáze nedočká a obdrží chybu. V tu chvíli si však nemůže být klient jistý, zda byla transakce úspěšně zpracována nebo ne. Transakce mohla být serverem úspěšně přijata, potvrzena a zapsána do WAL, jen se ještě nestihla propsat do sdíleného stavu. Klient se tedy může rozhodnout transakci opakovat, jenže změny mezitím již mohou aplikovat a propsat do sdíleného stavu, takže je celé opakování zbytečné.

<Note type="info">

Samozřejmě ve hře je stále situace, kdy vypadne spojení mezi klientem a serverem ještě před tím, než je klientovi potvrzena fáze `WAIT_FOR_WAL_PERSISTENCE`. V tu chvíli reaguje gRPC klient i server typicky vyhozením výjimky, která na straně serveru ukončí zpracování transakce a na straně klienta vyhodí výjimku a v takovém případě je transakci nutné opakovat. Pokud již došlo k dokončení fáze `WAIT_FOR_WAL_PERSISTENCE`, tak již k zastavení transakce na serveru nedojde a server transakci zpracovává dál. Zároveň i klient ví, že transakce se dostala ve zpracování dostatečně daleko, aby ji nemusel opakovat.

</Note>

Z toho důvodu existuje třetí varianta volání, která umožňuje dokonalou kontrolu nad zpracováním transakce:

```java
final CommitProgress commitProgress = evita.updateCatalogAsync(
    "evita-tutorial",
    session -> {
        // create a new brand
        session.createNewEntity(BrandEditor.class)
                .setName("Apple", Locale.ENGLISH)
                .upsertVia(session);
    }
);
commitProgress.onConflictResolved()
    .thenAccept(
        commitVersions -> System.out.println(
            "Tx accepted, changes will be visible in version: " + commitVersions.catalogVersion() + "."
        )
    );
commitProgress.onWalAppended()
    .thenAccept(
        commitVersions -> System.out.println(
            "Tx written to WAL."
        )
    );
commitProgress.onChangesVisible()
    .thenAccept(
        commitVersions -> System.out.println(
            "Tx changes visible to all now."
        )
    );
```

Výstupem tohoto volání je objekt <SourceClass>evita_api/src/main/java/io/evitadb/api/CommitProgress.java</SourceClass>, který obsahuje přísliby (`CompletionStage`) pro každou fázi zpracování transakce. Klient tím tedy získává plnou kontrolu nad tím, v jaké fázi se zpracování dat na straně serveru nachází. V implementaci protokolu gRPC je tato metoda implementována jako [streaming](https://grpc.io/docs/what-is-grpc/core-concepts/#server-streaming-rpc), ke kterému se může klient chovat odlišně a pomocí heartbeat zpráv udržovat spojení se serverem i po delší dobu. Pokud používáte Java rozhraní klienta, jsou toto implementační detaily, které nemusíte typicky řešit.

Výše uvedený příklad nám do konzole vypíše tyto zprávy:

```
Tx accepted, changes will be visible in version: 2.
Tx written to WAL.
Tx changes visible to all now.
```

## Závěr

O něco komplikovanější variantu můžete vidět i v našem [tutoriálu](https://github.com/FgForrest/evitaDB-tutorial/blob/01.07-listening-to-transaction-processing/src/main/java/io/evitadb/tutorial/Main.java). Jednou z dalších výhod přístupu evitaDB je to, že po akceptaci transakce, dostáváte ihned informaci o tom, v jaké verzi se změny objeví v sdíleném stavu (v podobě `catalogVersion`). Na základě této informace je možné na straně klienta vyvozovat jasné závěry. Například při otevření session pro čtení dat, můžete z této session vyčíst verzi katalogu a porovnat ji s verzí, kterou jste dostali při akceptaci transakce.

```java
final long currentCatalogVersion = evita.queryCatalog(
    "evita-tutorial",
    EvitaSessionContract::getCatalogVersion
);
```

Pokud je verze katalogu stejná, víte, že změny ještě nejsou viditelné a můžete se rozhodnout, zda chcete čekat na jejich zviditelnění nebo pokračovat v práci s daty, která jsou aktuálně dostupná.

V řadě situací je tato transparentnost ze strany databáze užitečná - nicméně pro běžné používání databáze není detailní znalost toho, co se děje na serveru, nutná. Většině klientů stačí pravděpodobně používat synchronní varianty zápisu dat a neřešit detaily zpracování transakce. Je však dobré vědět, že v případě potřeby je možné se do těchto detailů ponořit a mít plnou kontrolu nad tím, co se na serveru děje.