---
title: Implementace Change Data Capture
perex: |
  Change Data Capture (CDC) je zažitý způsob detekce a streamování aktualizací ze zdrojové databáze téměř v reálném čase.
  Umožňuje replikaci dat s nízkou latencí, event-driven workflow a udržuje služby synchronizované bez náročných dávkových úloh. Pokud vás zajímá, jak je CDC implementováno v evitaDB a jak zajišťujeme jeho spolehlivost, pokračujte ve čtení.
date: '1.11.2025'
author: 'Jan Novotný, Lukáš Hornych'
motive: ../en/assets/images/20-change-data-capture.png
proofreading: 'done'
commit: 360483b86834e15a0dde1d2f96642315ee4c7a5f
---

Change Data Capture (CDC) je v podstatě filtrovaný stream logických operací čtených z Write-Ahead Logu (WAL) databáze. Když zapíšete změny do databáze, tyto změny jsou nejprve zapsány do WAL, než jsou aplikovány na skutečný sdílený stav databáze a její datové soubory. To zajišťuje, že v případě pádu nebo selhání může databáze stále aplikovat všechny transakce, které byly potvrzeny jako trvalé.

V evitaDB je WAL implementován jako sekvence operací zabalených v transakčních hranicích. Pokud byste dokázali přečíst náš binární formát, viděli byste něco takového:

```
TRANSACTION: txId=16c9...40c7, version=41, mutationCount = 2, walSizeInBytes=891, commitTimestamp=2025-10-23T13:31:12.283+02:00
ENTITY_UPSERT: entityPrimaryKey=1234, entityType="Product", entityExistence=MAY_EXIST, localMutations={
    UPSERT_ATTRIBUTE: attributeName="title", locale=Locale.ENGLISH, value="New product title"
    UPSERT_PRICE: priceId=5678, priceList="DEFAULT", currency="USD", priceWithTax=199.99, priceWithoutTax=179.99, taxRate=20
}
TRANSACTION: ...
```

## Java implementace

Přirozeným způsobem, jak implementovat streamování změn v Javě, je použít `Flow API`, které bylo představeno v Javě 9. Definuje standard pro asynchronní zpracování streamů s neblokujícím backpressure. Hlavními stavebními bloky Flow API jsou `Publisher`, `Subscriber` a `Subscription`. `Publisher` produkuje položky a posílá je `Subscriber`ům, kteří se k němu přihlásili. `Subscription` představuje vztah mezi `Publisher`em a `Subscriber`em, což umožňuje `Subscriber`u požadovat položky a zrušit odběr.

Rozhraní je velmi minimalistické a nedává nám moc svobody pro design API. Protože potřebujeme umožnit klientům definovat filtrovací strategii pro změny, které chtějí přijímat, musíme ji zahrnout do metody, která vytváří `Publisher`. Samotný publisher pak obsahuje pouze metodu `void subscribe(Subscriber)`. To se může zpočátku zdát komplikované, protože klient obvykle nepotřebuje přihlásit více subscriber-ů ke stejnému CDC streamu, ale je to jediný způsob, jak být v souladu se specifikací Flow API (která nám přináší interoperabilitu, kterou potřebujeme). Takže pro vytvoření CDC streamu, který zachytává všechny změny schématu a dat s plnými těly mutací, by kód vypadal takto:

```java
// open a read-only session to access the catalog
try (final EvitaSessionContract session = evita.createReadOnlySession("evita")) {
    // retrieve change history from the catalog
    final ChangeCapturePublisher<ChangeCatalogCapture> changePublisher =
	    session.registerChangeCatalogCapture(
	        ChangeCatalogCaptureRequest.builder()
	            // capture both schema and data changes
	            .criteria(
	                // capture all schema changes
	                ChangeCatalogCaptureCriteria.builder()
	                    .schemaArea()
	                    .build(),
	                // capture all data changes
	                ChangeCatalogCaptureCriteria.builder()
	                    .dataArea()
	                    .build()
	            )
	            // include full mutation bodies
	            .content(ChangeCaptureContent.BODY)
	            .build()
        );

    // subscribe one or more subscribers to the same publisher
	changePublisher.subscribe(
        ... subscriber implementation ...
	);
}
```

### Zpracování backpressure

Problém s CDC streamy je, že rychlost čtení závisí na latenci sítě a rychlosti zpracování klienta. Pokud je klient pomalý, server ho nesmí zahlcovat daty rychleji, než je schopen zpracovat. Musíme také zabránit zpomalení zpracování WAL na serveru nebo vyčerpání paměti serveru bufferováním příliš velkého množství dat pro pomalé klienty. Na druhou stranu potřebujeme doručovat data co nejrychleji rychlým klientům, abychom minimalizovali zpoždění mezi změnami dat a jejich přijetím. Čtení z WAL souboru pro každého klienta zvlášť by bylo pomalé a náročné na zdroje.

Proto máme dvourychlostní implementaci, která staví na dvou předpokladech:

1. většina CDC klientů má zájem o nejnovější změny
2. většina CDC klientů dokáže držet krok s rychlostí, jakou jsou změny začleňovány do sdíleného stavu (s použitím rozumného bufferování)

Pro každou unikátní filtrovací strategii (predikát) udržujeme samostatnou sdílenou instanci <SourceClass>evita_engine/src/main/java/io/evitadb/core/cdc/ChangeCatalogCaptureSharedPublisher.java</SourceClass>, která obsahuje interní ring buffer omezené velikosti. Když transakční engine databáze zpracovává mutaci, přidá objektovou reprezentaci WAL záznamu do každého sdíleného publishera, který aplikuje svůj unikátní predikát. Pouze pokud predikát odpovídá, publisher převede mutaci na CDC událost, která má být odeslána klientovi. CDC události jsou okamžitě zapsány do interního ring bufferu. Tímto způsobem neudržujeme WAL mutační objekty v paměti déle, než je nutné, a udržujeme pouze CDC objekty událostí, které jsou skutečně potřebné alespoň jedním subscriber-em.

Tyto události však stále nemusí být připraveny k odeslání subscriber-ům, protože efekty mutací ještě nemusely dosáhnout sdíleného stavu databáze (změny musí být viditelné pouze na konci transakce, a když je více malých transakcí, jsou zpracovávány a aplikovány hromadně během časových oken). Proto každý publisher udržuje svůj vlastní "watermark" v ring bufferu a pouze události, které jsou starší než poslední "publikovaná" transakce, jsou k dispozici pro čtení. Tímto způsobem zajišťujeme, že subscriber-i nikdy nečtou události, které ještě nejsou viditelné ve sdíleném stavu databáze.

Pro každého subscriber-a udržujeme jeho vlastní subscription s interní frontou obsahující události, které mají být doručeny subscriber-ovi. Každá subscription také udržuje svůj vlastní watermark poslední přečtené události (ve skutečnosti ukazatel na pozici ve WAL), takže mohou existovat různí subscriber-i připojení ke stejnému publisheru, kteří čtou události různými rychlostmi. Když subscriber požaduje více událostí, snažíme se je doručit z interní fronty. Pokud je fronta prázdná, snažíme se přečíst více událostí ze sdíleného publisher ring bufferu, a existují tři možné výsledky:

1. v sdíleném publisher ring bufferu je další událost—přečteme ji, vložíme do fronty subscription a doručíme ji subscriber-ovi
2. v sdíleném publisher ring bufferu není žádná nová událost—přestaneme číst a čekáme na příchod nových událostí
3. subscriber zaostává příliš mnoho a sdílený publisher ring buffer již přepsal události, které subscriber ještě nepřečetl

V druhém případě "probudíme" všechny spící subscription, když nové události dorazí do sdíleného publisher ring bufferu. To zajišťuje, že všichni rychlí subscriber-i dostanou své události co nejdříve (samozřejmě, v případě vzdálených klientů existuje krátké zpoždění, než jsou události vyzvednuta thread poolem, který zpracovává propagaci událostí přes webová API).

Ve třetím případě přepneme subscription do "resync režimu", kde začneme číst mutace přímo ze WAL souboru, přeskočíme sdílený publisher ring buffer, dokud nedosáhneme stavu, kdy může být subscriber bezpečně přepnut zpět na čtení ze sdíleného publisher ring bufferu (když subscriber dožene). Tímto způsobem zajišťujeme, že pomalí subscriber-i neblokují celý CDC systém, ale mohou se vždy resynchronizovat s nejnovějším stavem.

Aby se zabránilo zbytečné spotřebě paměti, události jsou zahozeny z fronty subscription, jakmile jsou předány subscriber-ovi. Události ve sdíleném publisher ring bufferu jsou zahozeny, jakmile všechny známé subscription posunou své watermarky za ně. Takže pokud jsou všichni subscriber-i dostatečně rychlí a odeslali všechny události svým klientům, všechny fronty a ring buffery jsou prázdné (ale důležitější—všechny CDC objekty událostí mohou být garbage collected).

### gRPC implementace

Náš Java klient staví na našem gRPC API, takže když použijete publisher/subscriber API v Java klientovi, pod pokličkou používá gRPC streamování pro příjem událostí ze serveru. Na klientovi nastavíme gRPC stream v okamžiku, kdy je na publisheru zavolána metoda `subscribe(...)`. Vytvoření instance publishera pouze vytvoří novou definici v paměti klienta, připravenou vytvořit novou gRPC subscription předáním filtrovacích kritérií serveru, ale ve skutečnosti ještě nekomunikuje se serverem.

Java Flow API a gRPC streaming API jsou na straně klienta převedeny pomocí adaptérových tříd, které implementují rozhraní Flow API a používají gRPC streaming stuby pro komunikaci se serverem. Zpracování backpressure je implementováno pomocí mechanismů řízení toku gRPC, takže když subscriber požaduje více položek, požadujeme více položek z gRPC streamu. Když je subscriber pomalý, přestaneme požadovat více položek z gRPC streamu, což automaticky zastaví server v odesílání dalších položek.

Díky schopnostem gRPC streamování můžeme kdykoli zrušit subscription ze strany klienta, což zavře gRPC stream a uvolní všechny zdroje i na straně serveru. CDC implementace není omezena pouze na Java klienty. Jakýkoli klient schopný gRPC může implementovat CDC subscriber pomocí stejných filtrovacích kritérií a přijímat stejné události jako Java klient.

### GraphQL implementace

Naše GraphQL API používá [subscriptions API](https://graphql.org/learn/subscriptions/) k poskytnutí způsobu, jak se přihlásit k odběru CDC událostí. Zvolili jsme protokol [GraphQL over WebSocket](https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md) pro implementaci subscription, aby se stávající GraphQL klienti mohli snadno připojit ke streamu.

Pod pokličkou je WebSocket stream od klienta přeložen na Java Flow API stream pro příjem událostí z enginu. Když klient otevře WebSocket stream se subscription, požaduje nový publisher s CDC streamem z evitaDB enginu, který odesílá všechny budoucí události zpět přes WebSocket stream klientovi.

Zpracování backpressure je implementováno pomocí mechanismů řízení toku WebSocket, takže když klient požaduje více událostí, požadujeme více událostí z Java Flow streamu. Když je klient pomalý, přestaneme požadovat více událostí z Java Flow streamu. Díky schopnostem WebSocket streamování můžeme kdykoli zrušit subscription ze strany klienta, což zavře Java Flow stream na straně serveru a uvolní všechny zdroje.

### REST implementace

Naše REST API je implementováno podobně jako GraphQL API. OpenAPI specifikace však přímo nespecifikuje žádný standard pro real-time update API, ani není možné jej dokumentovat v rámci základní OpenAPI specifikace. Proto jsme se rozhodli vytvořit [vlastní WebSocket specifikaci](/documentation/user/en/use/connectors/rest-over-websocket-protocol.md) založenou na protokolu [GraphQL over WebSocket](https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md). Ačkoli nám základní OpenAPI specifikace neumožňuje přímo dokumentovat vlastní protokol, prozatím jsme zahrnuli CDC typy do OpenAPI specifikace, aby existoval alespoň nějaký základ pro vývojáře klientů (např. mutační objekty, CDC objekty událostí atd.).

Pod pokličkou je WebSocket stream od klienta přeložen na Java Flow API stream pro příjem událostí z enginu. Když klient otevře WebSocket stream se subscription, požaduje nový publisher s CDC streamem z evitaDB enginu, který odesílá všechny budoucí události zpět přes WebSocket stream klientovi.

Zpracování backpressure funguje podobným způsobem jako implementace GraphQL, pomocí mechanismů řízení toku WebSocket.

## Závěr

Věříme, že naše počáteční CDC implementace je dostatečně robustní a efektivní pro zvládnutí většiny případů použití. Jsme si však vědomi, že první verze obvykle potřebuje vylepšení na základě reálného použití. Proto jsme otevřeni zpětné vazbě a návrhům od našich uživatelů na další vylepšení CDC funkcionality. Pokud máte nějaké nápady nebo narazíte na jakékoli problémy při používání CDC v evitaDB, neváhejte nás kontaktovat. Vaše zpětná vazba je neocenitelná při pomoci nám vylepšovat a zdokonalovat tuto funkci.