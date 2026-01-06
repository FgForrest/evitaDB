---
title: Zachycení změn
perex: |
  Change Data Capture (CDC) je vzor pro detekci a streamování vkládání, aktualizací a mazání z výchozí databáze téměř v reálném čase.
  Umožňuje replikaci dat s nízkou latencí, workflow řízené událostmi a udržuje služby synchronizované bez náročných dávkových úloh. Pokud vás zajímá, jak je CDC implementováno v evitaDB a jak zajišťujeme jeho spolehlivost, čtěte dále.
date: '1.11.2025'
author: Jan Novotný, Lukáš Hornych
commit: e436a1f995cb775fb4eaec09c4e947f876e5cd29
---
Change Data Capture (CDC) je v podstatě filtrovaný proud logických operací čtených z Write-Ahead Logu (WAL) databáze. Když provedete změny v databázi a potvrdíte je, tyto změny jsou nejprve zapsány do WAL, než jsou aplikovány do skutečného sdíleného stavu databáze a jejích datových souborů. To zajišťuje, že v případě havárie nebo selhání může databáze stále aplikovat všechny transakce, které byly potvrzeny a označeny jako trvalé.

V evitaDB je WAL implementován jako sekvence operací uzavřených v hranicích transakce. Pokud byste mohli číst náš binární formát, viděli byste něco jako toto:

```
TRANSACTION: txId=16c9...40c7, version=41, mutationCount = 2, walSizeInBytes=891, commitTimestamp=2025-10-23T13:31:12.283+02:00
ENTITY_UPSERT: entityPrimaryKey=1234, entityType="Product", entityExistence=MAY_EXIST, localMutations={
    UPSERT_ATTRIBUTE: attributeName="title", locale=Locale.ENGLISH, value="New product title"
    UPSERT_PRICE: priceId=5678, priceList="DEFAULT", currency="USD", priceWithTax=199.99, priceWithoutTax=179.99, taxRate=20
}
TRANSACTION: ...
```

## Implementace v Javě

Přirozeným způsobem, jak implementovat streamování změn v Javě, je použití `Flow API`, které bylo představeno v Javě 9. Definuje standard pro asynchronní zpracování proudů s neblokujícím zpětným tlakem (backpressure). Hlavními stavebními bloky Flow API jsou `Publisher`, `Subscriber` a `Subscription`. `Publisher` produkuje položky a posílá je `Subscriberům`, kteří se k němu přihlásili. `Subscription` představuje vztah mezi `Publisherem` a `Subscriberem`, umožňuje `Subscriberovi` požadovat položky a zrušit odběr.

Rozhraní je velmi minimalistické a nedává nám příliš prostoru pro návrh. Protože musíme umožnit klientům definovat strategii filtrování změn, které chtějí přijímat, musíme ji uzavřít do metody, která vytváří `Publisher`. Samotný publisher pak obsahuje pouze metodu `void subscribe(Subscriber)`. To se může na první pohled zdát složité, protože klient obvykle nepotřebuje přihlásit více subscriberů ke stejnému CDC proudu, ale je to jediný způsob, jak vyhovět specifikaci Flow API (která nám přináší potřebnou interoperabilitu). Takže vytvoření CDC proudu, který zachycuje všechny změny schématu a dat s plnými těly mutací, by vypadalo takto:

```java
// otevřete read-only session pro přístup ke katalogu
try (final EvitaSessionContract session = evita.createReadOnlySession("evita")) {
    // načtěte historii změn z katalogu
    final ChangeCapturePublisher<ChangeCatalogCapture> changePublisher =
	    session.registerChangeCatalogCapture(
	        ChangeCatalogCaptureRequest.builder()
	            // zachyťte změny schématu i dat
	            .criteria(
	                // zachyťte všechny změny schématu
	                ChangeCatalogCaptureCriteria.builder()
	                    .schemaArea()
	                    .build(),
	                // zachyťte všechny změny dat
	                ChangeCatalogCaptureCriteria.builder()
	                    .dataArea()
	                    .build()
	            )
	            // zahrňte plná těla mutací
	            .content(ChangeCaptureContent.BODY)
	            .build()
        );

    // přihlaste jednoho nebo více subscriberů ke stejnému publisheru
	changePublisher.subscribe(
        ... implementace subscriberu ...
	);
}
```

### Řízení zpětného tlaku (Backpressure handling)

Problém u CDC proudů spočívá v tom, že rychlost čtení závisí na latenci sítě a rychlosti zpracování klienta. Pokud je klient pomalý, server ho nesmí zahlcovat daty rychleji, než je schopen je zpracovat. Musíme také zabránit zpomalení zpracování WAL na serveru nebo vyčerpání paměti serveru přílišným bufferováním dat pro pomalé klienty. Na druhou stranu musíme doručovat data co nejrychleji rychlým klientům, abychom minimalizovali zpoždění mezi změnami dat a jejich přijetím. Čtení z WAL souboru pro každého klienta zvlášť by bylo pomalé a náročné na zdroje.

Proto máme dvourychlostní implementaci, která staví na dvou předpokladech:

1. většina CDC klientů má zájem o nejnovější změny
2. většina CDC klientů zvládá rychlost, jakou jsou změny začleňovány do sdíleného stavu (s použitím rozumného bufferování)

Pro každou unikátní strategii filtrování (predikát) udržujeme samostatnou sdílenou instanci <SourceClass>evita_engine/src/main/java/io/evitadb/core/cdc/ChangeCatalogCaptureSharedPublisher.java</SourceClass>, která obsahuje interní kruhový buffer s omezenou velikostí. Když transakční engine databáze zpracuje mutaci, vloží objektovou reprezentaci WAL záznamu do každého sdíleného publisheru, který aplikuje svůj unikátní predikát. Pouze pokud predikát odpovídá, publisher převede mutaci na CDC událost, která je odeslána klientovi. CDC události jsou okamžitě zapsány do interního kruhového bufferu. Tímto způsobem nedržíme objekty WAL mutací v paměti déle, než je nutné, a uchováváme pouze objekty CDC událostí, které jsou skutečně potřeba alespoň jednomu subscriberovi.

Tyto události ještě nemusí být připraveny k odeslání subscriberům, protože účinky mutace nemusely ještě dosáhnout sdíleného stavu databáze (změny musí být viditelné až na konci transakce, a pokud je více malých transakcí, jsou zpracovány a aplikovány hromadně během časových oken). Každý publisher proto udržuje svůj vlastní „watermark“ v kruhovém bufferu a pouze události, které jsou starší než poslední „publikovaná“ transakce, jsou zpřístupněny ke čtení. Tím zajišťujeme, že subscribery nikdy nečtou události, které ještě nejsou viditelné ve sdíleném stavu databáze.

Pro každého subscriberu udržujeme jeho vlastní subscription s interní frontou obsahující události k doručení subscriberu. Každý subscription také uchovává svůj vlastní watermark poslední přečtené události (ve skutečnosti ukazatel na pozici ve WAL), takže k jednomu publisheru mohou být připojeni různí subscribery čtoucí události různou rychlostí. Když subscriber požaduje více událostí, snažíme se je doručit z interní fronty. Pokud je fronta prázdná, snažíme se načíst další události ze sdíleného kruhového bufferu publisheru a mohou nastat tři možné výsledky:

1. ve sdíleném kruhovém bufferu publisheru je další událost—načteme ji, vložíme do fronty subscriptionu a doručíme subscriberu
2. ve sdíleném kruhovém bufferu publisheru není žádná nová událost—přestaneme číst a čekáme na příchod nových událostí
3. subscriber příliš zaostává a sdílený kruhový buffer publisheru již přepsal události, které subscriber ještě nepřečetl

V druhém případě „probudíme“ všechny uspávající subscriptiony, když do sdíleného kruhového bufferu publisheru dorazí nové události. To zajišťuje, že všichni rychlí subscribery dostanou své události co nejdříve (samozřejmě v případě vzdálených klientů je zde krátké zpoždění, než události převezme thread pool zajišťující propagaci událostí přes webová API).

Ve třetím případě přepneme subscription do „resync módu“, kdy začneme číst mutace přímo ze souboru WAL, přeskakujeme sdílený kruhový buffer publisheru, dokud nedosáhneme stavu, kdy lze subscriber bezpečně přepnout zpět na čtení ze sdíleného kruhového bufferu publisheru (až subscriber dožene zpoždění). Tím zajišťujeme, že pomalí subscribery neblokují celý CDC systém, ale vždy se mohou resynchronizovat na nejnovější stav.

Abychom zabránili zbytečné spotřebě paměti, události jsou z fronty subscriptionu odstraněny ihned po předání subscriberu. Události ve sdíleném kruhovém bufferu publisheru jsou odstraněny, jakmile všechny známé subscriptiony posunou své watermarks za ně. Pokud jsou tedy všichni subscribery dostatečně rychlí a odeslali všechny události svým klientům, všechny fronty i kruhové buffery jsou prázdné (ale co je důležitější—všechny objekty CDC událostí mohou být uvolněny garbage collectorem).

### Implementace gRPC

Náš Java klient staví na našem gRPC API, takže když použijete publisher/subscriber API v Java klientu, pod povrchem využívá gRPC streamování pro příjem událostí ze serveru. Na klientovi nastavíme gRPC stream ve chvíli, kdy je zavolána metoda `subscribe(...)` na publisheru. Vytvoření instance publisheru pouze vytvoří novou definici v paměti klienta, připravenou vytvořit nové gRPC předplatné předáním filtračních kritérií serveru, ale zatím se serverem nekomunikuje.

Java Flow API a gRPC streaming API jsou na straně klienta překládány pomocí adapter tříd, které implementují rozhraní Flow API a používají gRPC streaming stuby pro komunikaci se serverem. Řízení zpětného tlaku je implementováno pomocí mechanismů řízení toku v gRPC, takže když subscriber požaduje více položek, požadujeme více položek z gRPC streamu. Když je subscriber pomalý, přestaneme požadovat další položky z gRPC streamu, což automaticky zastaví server v zasílání dalších položek.

Díky možnostem streamování v gRPC můžeme kdykoliv zrušit subscription ze strany klienta, což uzavře gRPC stream a uvolní všechny prostředky i na straně serveru. Implementace CDC není omezena pouze na Java klienty. Jakýkoliv klient podporující gRPC může implementovat CDC subscriber se stejnými filtračními kritérii a přijímat stejné události jako Java klient.

### Implementace GraphQL

Náš GraphQL API využívá [subscriptions API](https://graphql.org/learn/subscriptions/) pro možnost přihlásit se k odběru CDC událostí. Zvolili jsme protokol [GraphQL over WebSocket](https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md) pro implementaci subscriptionů, aby se existující GraphQL klienti mohli snadno připojit k proudu.

Pod povrchem je WebSocket stream od klienta překládán na Java Flow API stream pro příjem událostí z engine. Když klient otevře WebSocket stream se subscriptionem, požaduje nový publisher s CDC streamem z evitaDB engine, který všechny budoucí události posílá zpět klientovi přes WebSocket stream.

Řízení zpětného tlaku je implementováno pomocí mechanismů řízení toku WebSocket, takže když klient požaduje více událostí, požadujeme více událostí z Java Flow streamu. Když je klient pomalý, přestaneme požadovat další události z Java Flow streamu. Díky možnostem streamování přes WebSocket můžeme kdykoliv zrušit subscription ze strany klienta, což na serveru uzavře Java Flow stream a uvolní všechny prostředky.

### Implementace REST

Náš REST API je implementován podobně jako GraphQL API. Specifikace OpenAPI však přímo neurčuje žádný standard pro API s real-time aktualizacemi, ani není možné jej v základní specifikaci OpenAPI zdokumentovat. Proto jsme se rozhodli vytvořit [vlastní WebSocket specifikaci](/documentation/user/en/use/connectors/rest-over-websocket-protocol.md) založenou na protokolu [GraphQL over WebSocket](https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md). Ačkoliv základní specifikace OpenAPI nám neumožňuje přímo dokumentovat vlastní protokol, prozatím jsme do OpenAPI specifikace zahrnuli typy CDC, aby měli vývojáři klientů alespoň nějaký základ (např. objekty mutací, objekty CDC událostí atd.).

Pod povrchem je WebSocket stream od klienta překládán na Java Flow API stream pro příjem událostí z engine. Když klient otevře WebSocket stream se subscriptionem, požaduje nový publisher s CDC streamem z evitaDB engine, který všechny budoucí události posílá zpět klientovi přes WebSocket stream.

Řízení zpětného tlaku funguje obdobně jako u implementace GraphQL, s využitím mechanismů řízení toku WebSocket.