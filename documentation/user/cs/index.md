---
title: Úvod
date: '17.1.2023'
author: Ing. Jan Novotný
proofreading: done
commit: cef96d8320d36c91c100c5dfc9c45020b5a7ad0d
---
evitaDB pomáhá vývojářům vytvářet rychlé aplikace produktových katalogů, které jsou srdcem každého e-commerce webu.
Katalogy pracují s hierarchickými strukturami, fasetovým vyhledáváním, vyhledáváním podle ceny, rozsahem, lokalizací a fulltextem.
Všechny tyto požadavky lze řešit pomocí databází pro obecné účely – ať už relačních jako [PosgreSQL](https://www.postgresql.org/),
[MySQL](https://www.mysql.com/), nebo no-sql jako [Elasticsearch](https://www.elastic.co/), [MongoDB](https://www.mongodb.com/).
Ve skutečnosti jsou však tyto úlohy pro všechny tyto databáze poměrně složité a vyžadují mnoho práce ze strany
vývojáře aplikace. Problémy e-commerce lze v těchto databázích řešit různými způsoby, ale počáteční a často naivní implementace bývají velmi neefektivní, když dataset naroste, nebo se rychle stanou obtížně udržovatelnými a dále rozvíjenými.

Vývojáři evitaDB několik let implementovali e-commerce obchody na různých databázových platformách. Máme zkušenosti s vícestránkovými SQL dotazy, timeouty při čekání na zámky, denormalizací dat a dalšími vedlejšími efekty tradičních relačních řešení. Ochutnali jsme také stinnou stránku distribuovaných no-SQL databází, které rovněž vyžadovaly vícestránkové dotazy a odhalily problémy s (ne)dostatečnou transakčností, eventual consistency a obtížně pochopitelnou definicí schématu a dotazováním. Vždy jsme cítili, že za přijatelnou latenci platíme velkou složitostí, a proto míříme k systému, který bude jednoduchý i výkonný pro většinu e-commerce případů užití. Plug and play zařízení, které prostě funguje.

Proto jsme požádali o [grant EU na výzkum](https://evitadb.io/project-info), který nám umožnil věnovat potřebný čas pokusu vytvořit alternativu k databázím pro obecné účely, která by splnila naše potřeby. [Průběh a výsledky našeho výzkumu](https://evitadb.io/research/introduction) probíhajícího v letech 2019 až 2022 jsou zdokumentovány v samostatné části tohoto webu.

<Note type="warning">

<NoteTitle toggles="false">

##### Používejte na vlastní riziko a odpovědnost
</NoteTitle>

V létě 2024 jsme začali pravidelně vydávat beta verze evitaDB a nasazovat je u našich vlastních zákazníků, abychom získali přímou zkušenost z vlastního používání. evitaDB je aktuálně provozována v produkci a funguje spolehlivě v souladu s našimi očekáváními ohledně výkonu a stability.

Nicméně evitaDB je v současnosti ve verzi beta a stále probíhá intenzivní vývoj. Plánujeme dokončit plně vybavenou verzi v roce 2026. Do té doby se může kdykoli změnit formát úložiště, což může vyžadovat odstranění všech existujících dat a jejich opětovné indexování z primárního úložiště. Děláme maximum pro to, abychom poskytli automatické nástroje pro migraci dat, takže doufejme, že to nebude nutné, ale zatím to nemůžeme zaručit.

**Z výše uvedených důvodů prosím nepoužívejte evitaDB pro ukládání vašich primárních dat.**

</Note>

## Začínáme

1. [Spusťte evitaDB](get-started/run-evitadb.md)
   1. [Spusťte embedded ve své aplikaci](use/connectors/java.md)
   2. [Spusťte jako službu v Dockeru](operate/run.md)
2. [Vytvořte svou první databázi](get-started/create-first-database.md)
3. [Dotazujte se na náš dataset](get-started/query-our-dataset.md)

## Použití

1. [Datový model](use/data-model.md)
   1. [Datové typy](use/data-types.md)
   2. [Schéma](use/schema.md)
2. **Konektory**
   1. [GraphQL](use/connectors/graphql.md)
   2. [REST](use/connectors/rest.md)
   3. [gRPC](use/connectors/grpc.md)
   4. [Java](use/connectors/java.md)
   5. [C#](use/connectors/c-sharp.md)
3. **API**
   1. [Definujte schéma](use/api/schema-api.md)
   2. [Upsert dat](use/api/write-data.md)
   3. [Dotazování na data](use/api/query-data.md)
   4. [Psaní testů](use/api/write-tests.md)
   5. [Řešení problémů](use/api/troubleshoot.md)
   7. [Ovládání engine](use/api/control-engine.md)
   6. [Zachycení změn](use/api/capture-changes.md)

## Dotazování

1. [Základy](query/basics.md)
2. **Filtrování**
   1. [Behaviorální](query/filtering/behavioral.md)
   2. [Porovnatelné](query/filtering/comparable.md)
   3. [Konstantní](query/filtering/constant.md)
   4. [Hierarchie](query/filtering/hierarchy.md)
   5. [Lokalizace](query/filtering/locale.md)
   6. [Logické](query/filtering/logical.md)
   7. [Cena](query/filtering/price.md)
   8. [Rozsah](query/filtering/range.md)
   9. [Reference](query/filtering/references.md)
   10. [Řetězec](query/filtering/string.md)
3. **Řazení**
   1. [Behaviorální](query/ordering/behavioral.md)
   2. [Porovnatelné](query/ordering/comparable.md)
   3. [Konstantní](query/ordering/constant.md)
   4. [Cena](query/ordering/price.md)
   5. [Náhodné](query/ordering/random.md)
   6. [Reference](query/ordering/reference.md)
   7. [Segmentace](query/ordering/segment.md)
4. **Požadavky**
   1. [Behaviorální](query/requirements/behavioral.md)
   2. [Fasetové](query/requirements/facet.md)
   3. [Načítání](query/requirements/fetching.md)
   4. [Hierarchie](query/requirements/hierarchy.md)
   5. [Histogram](query/requirements/histogram.md)
   6. [Stránkování](query/requirements/paging.md)
   7. [Cena](query/requirements/price.md)
   8. [Telemetrie](query/requirements/telemetry.md)

## Provoz

1. [Konfigurace](operate/configure.md)
   1. [Nastavení TLS](operate/tls.md) 
2. [Spuštění](operate/run.md)
3. [Zálohování & Obnova](operate/backup-restore.md)
4. [Monitoring](operate/observe.md)

## Do hloubky

1. [Model úložiště](deep-dive/storage-model.md)
2. [Hromadné vs. inkrementální indexování](deep-dive/bulk-vs-incremental-indexing.md)
3. [Transakce](deep-dive/transactions.md)
4. [Výpočet ceny pro prodej](deep-dive/price-for-sale-calculation.md)
5. [Cache](deep-dive/cache.md)
6. [Zachycení změn](deep-dive/cc.md)

## Řešení

1. [Routování](solve/routing.md)
2. [Vykreslení menu kategorií](solve/render-category-menu.md)
   1. [Mega-menu](solve/render-category-menu.md#mega-menu)
   2. [Dynamické rozbalovací menu](solve/render-category-menu.md#dynamické-rozbalovací-menu)
   3. [Výpis podkategorií](solve/render-category-menu.md#výpis-podkategorií)
   4. [Hybridní menu](solve/render-category-menu.md#hybridní-menu)
   5. [Skrýt části menu kategorií](solve/render-category-menu.md#skrytí-částí-stromu-kategorií)
3. [Filtrování produktů v kategorii](solve/render-products-in-category.md)
   1. [S fasetovým vyhledáváním](solve/render-products-in-category.md#filtrování-podle-facety)
   2. [S cenovým filtrem](solve/render-products-in-category.md#cenový-filtr)
4. [Vykreslení referencované značky](solve/render-products-in-brand.md)
   1. [S výpisem produktů](solve/render-products-in-brand.md#výpis-produktů)
   2. [S výpisem zapojených kategorií](solve/render-products-in-brand.md#výpis-kategorií)
5. [Práce s obrázky & binárními daty](solve/handling-images-binaries.md)
6. [Modelování cenových politik](solve/model-price-policies.md)