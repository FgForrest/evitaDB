---
title: Zachytávání změn dat
perex: Zachytávání změn dat (CDC) je návrhový vzor používaný ke sledování a zaznamenávání změn provedených ve schématu a datech v databázi. evitaDB podporuje CDC prostřednictvím všech svých API, což vývojářům umožňuje velmi snadno monitorovat a reagovat na změny dat téměř v reálném čase v jejich preferovaném programovacím jazyce. Tento dokument vysvětluje, jak implementovat CDC pomocí našeho API.
date: '21.10.2025'
author: Ing. Jan Novotný
proofreading: done
preferredLang: java
commit: d5041ba065f96f215dbdd0cf6ffb0cf5c9f3a88b
translated: true
---
Databáze udržuje takzvaný [Write-Ahead Log (WAL)](https://en.wikipedia.org/wiki/Write-ahead_logging), který zaznamenává všechny změny provedené v databázi. Tento log slouží k zajištění integrity a trvanlivosti dat, ale může být také využit k implementaci funkce zachytávání změn dat (CDC). Jakmile je katalog přepnut do fáze `ACTIVE` (transakční), klienti mohou začít konzumovat informace o změnách provedených jak ve schématu, tak v datech katalogu.

K dispozici je také speciální CDC pro celý databázový engine, která umožňuje klientům sledovat operace na vyšší úrovni, jako je vytvoření katalogu, jeho smazání a další globální události (podrobnosti naleznete v [kapitole Control Engine](control-engine.md)).

<Note type="warning">

Zachytávání změn dat není dostupné pro katalogy ve fázi `WARMING_UP`, protože v této fázi se WAL nezaznamenává.
Tato fáze je považována za "úvodní" a klienti by s daty v této fázi stejně neměli pracovat (dotazovat se na ně). Klienti by měli počkat, dokud katalog nedosáhne fáze `ACTIVE`, a vnímat všechna data v tomto okamžiku jako konzistentní snímek první verze katalogu.

</Note>

<Note type="info">

Engine a katalogové CDC nelze kombinovat do jediného streamu, protože fungují na různých úrovních (engine vs. katalog). Katalogové CDC je vždy vázáno na konkrétní katalog (jméno). Pokud potřebujete zachytit všechny změny napříč všemi katalogy, musíte se přihlásit k odběru engine-level CDC a poté pro každý katalog zvlášť ke katalogovému CDC. Engine-level CDC informuje o událostech vytvoření/smazání katalogu, takže klienti se mohou dynamicky přihlašovat/odhlašovat k odběru katalogových CDC podle toho, jak jsou katalogy vytvářeny/smazány.

</Note>

Základní princip ve všech API je stejný:

1. klienti definují predikát/podmínku, která určuje, o jaké změny mají zájem,
2. definují výchozí bod ve formě verze katalogu, od které chtějí začít přijímat změny,
3. a přihlásí se k odběru streamu změn.

Od tohoto okamžiku budou klienti dostávat notifikace o všech změnách, které odpovídají jejich kritériím. Změny jsou doručovány v pořadí, v jakém byly provedeny, což zajišťuje, že je klienti mohou zpracovávat sekvenčně. Druhý krok je volitelný — pokud není zadána výchozí verze, stream změn začne od následující verze katalogu.

## Životní cyklus odběru

Jakmile je odběr aktivní, stream změn zůstává aktivní, dokud nenastane jedna z následujících situací:

1. klient explicitně zruší odběr
2. klient nestíhá zpracovávat příchozí změny (backpressure)
3. klient vyvolá výjimku během zpracování
4. klient nereaguje v rámci časového limitu
5. server se vypne nebo je katalog smazán
6. server nereaguje v rámci časového limitu
7. vyprší TTL (time-to-live) odběru – viz [nastavení konfigurace](../connectors/java.md#konfigurace)

Jak vidíte, existuje mnoho důvodů, proč může odběr skončit. Klienti by proto měli být připraveni tyto situace správně ošetřit. Standardní přístup je implementovat rozhraní `AutoCloseable` ve vašem subscriberu a obnovit odběr v metodě `close()` nebo naplánovat obnovení jinou aplikační službou. Váš subscriber by si měl také pamatovat poslední úspěšně zpracovanou verzi a index, aby mohl při obnovení pokračovat od správného bodu. Kritéria pracují s verzí a indexem jako inkluzivními, takže byste měli po obnovení přeskočit první událost, pokud odpovídá poslední zpracované verzi a indexu.

## Hierarchie mutací

Ne všechny mutace fungují na stejné úrovni a některé mutace mohou zahrnovat jiné. Například při upsertu entity může obsahovat více mutací uvnitř sebe (více operací s atributy, asociovanými daty, cenami atd.). Hierarchie mutací je následující:

- <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/EngineMutation.java</SourceClass> ([úplný výpis](control-engine.md), dostupné v [engine change capture](#zachytávání-změn-na-úrovni-engine))
    - <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/CatalogBoundMutation.java</SourceClass> ([úplný výpis](../schema.md), dostupné v [catalog schema change capture](#zachytávání-změn-na-úrovni-katalogu))
        - <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/LocalCatalogSchemaMutation.java</SourceClass>
            - <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/LocalEntitySchemaMutation.java</SourceClass>
                - <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/reference/ModifyReferenceAttributeSchemaMutation.java</SourceClass> 
    - <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/EntityMutation.java</SourceClass> ([úplný výpis](../data-model.md), dostupné v [catalog data change capture](#zachytávání-změn-na-úrovni-katalogu))
        - <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/LocalMutation.java</SourceClass>
- <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass> (dostupné ve všech streamech zachytávání změn)

Pokud nezadáte žádná filtrační kritéria, obdržíte všechny mutace v "zploštělé" podobě, tj. obdržíte všechny mutace bez ohledu na jejich hierarchii. Například upsert atributu entity bude doručen jednou jako součást mutace upsert entity a jednou jako samostatná mutace upsert atributu. V praxi klient obvykle chce buď informace na vyšší úrovni o změnách entity (tedy pouze entity mutace), nebo velmi specifické změny na nízké úrovni (např. pouze změny atributů konkrétního jména). Přístup s jednoduchým zploštělým streamem, který je filtrován jedním predikátem, pokrývá všechny tyto případy velmi dobře a je velmi snadno pochopitelný a implementovatelný.

<LS to="g">

## Nastavení GraphQL klienta

Pro konzumaci streamů zachytávání změn je potřeba nastavit GraphQL klienta pro odesílání požadavků na odběr
na server přes WebSockets pomocí protokolu
[GraphQL over WebSocket](https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md).
WebSocket URL jsou stejná jako pro dotazy/mutace.

Každá [instance API](/documentation/user/en/use/connectors/graphql.md#graphql-api-instances) poskytuje specifické
odběry pro doménu instance API (viz níže); všechny instance API však poskytují určité CDC odběry.

Významným aspektem naší implementace GraphQL je, že každý odběr je dostupný ve dvou verzích:

- typovaný
- netypovaný

To existuje proto, že specifikace GraphQL vyžaduje, aby klient specifikoval všechna požadovaná výstupní pole každého
odběru, což může být pro CDC streamy poměrně zdlouhavé. Existují desítky implementací mutací, které může server zaslat.
V tradičním GraphQL API by klient musel specifikovat všechna pole všech implementací mutací. To může být užitečné, pokud se filtrační strategie zaměřuje pouze na určitou sadu typů mutací; klient však může potřebovat podporovat širokou škálu mutací nebo dokonce všechny. Proto jsme implementovali výše zmíněné dvě verze každého odběru.

Typovaná verze plně odpovídá specifikaci GraphQL a vyžaduje, aby klient specifikoval všechna požadovaná výstupní pole pro každý typ mutace (i když existují některé restriktivní unie).
Netypovaná verze vystavuje `body` jako obecný `Object`.
Tímto způsobem klient obdrží všechny typy mutací se všemi jejich daty skrze jediné výstupní pole. To má zjevné nevýhody: klient je zodpovědný za mapování JSON objektu a extrakci požadovaných dat.
Tuto možnost používejte pouze v případě, že klient skutečně potřebuje všechna data.

---

## Nastavení REST klienta

Pro konzumaci streamů zachytávání změn je potřeba nastavit WebSocket klienta pro odesílání požadavků na odběr
na server pomocí našeho
[vlastního WebSocket protokolu](/documentation/user/en/use/connectors/rest-over-websocket-protocol.md) založeného na
[GraphQL over WebSocket](https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md) protokolu.
WebSocket URL jsou stejná jako pro dotazy/mutace.

<Note type="info">

<NoteTitle toggles="true">

##### REST over WebSocket protokol

</NoteTitle>

OpenAPI specifikace nedefinuje žádný standard pro API s real-time aktualizacemi, ani není možné jej v základní specifikaci dokumentovat. Proto jsme se rozhodli vytvořit
[vlastní WebSocket protokol](/documentation/user/en/use/connectors/rest-over-websocket-protocol.md) založený na
[GraphQL over WebSocket](https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md) protokolu.
Ačkoli základní OpenAPI specifikace nám neumožňuje přímo dokumentovat vlastní protokol, prozatím jsme
zahrnuli CDC typy do OpenAPI specifikace, aby alespoň existoval solidní základ pro vývojáře klientů (např. objekty mutací, objekty CDC událostí atd.).

</Note>

</LS>

<LS to="j,g,r">

## Zachytávání změn na úrovni engine

<LS to="j,r">

<LS to="j">

Stream zachytávání na úrovni engine přijímá <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeSystemCaptureRequest.java</SourceClass>
pro vytvoření [Java Flow Publisher](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Flow.Publisher.html). Jeden nebo více klientů se pak může přihlásit k tomuto publisheru a přijímat
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeSystemCapture.java</SourceClass>
instance reprezentující změny provedené v engine.

</LS>
<LS to="r">

Stream zachytávání na úrovni engine přijímá <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeSystemCaptureRequest.java</SourceClass>
pro vytvoření CDC streamu. Klienti pak přijímají <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeSystemCapture.java</SourceClass>
instance reprezentující změny provedené v engine.

</LS>

Požadavek umožňuje zadat následující parametry:

<dl>
  <dt>long `sinceVersion` (volitelné)</dt>
  <dd>
    Verze katalogu (včetně), od které chcete začít přijímat změny. Pokud není zadáno, stream změn začne od další verze katalogu (tj. změny provedené v katalogu v budoucnu).
  </dd>
  <dt>int `sinceIndex` (volitelné)</dt>
  <dd>
    Index mutace v rámci stejné transakce, od kterého chcete začít přijímat změny. Pokud není zadáno, stream změn začne od první mutace zadané verze. Index vám umožňuje přesně určit výchozí bod v případě, že jste již některé mutace dané verze zpracovali.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCaptureContent.java</SourceClass> `content`</dt>
  <dd>
    Výčtový typ, který určuje, zda klient chce detailní informace o každé mutaci, nebo pouze informace na vyšší úrovni o tom, že došlo k určitému typu mutace. Výčtový typ má následující hodnoty:
    <ul>
        <li>`HEADER` - odesílá se pouze hlavička události</li>
        <li>`BODY` - odesílá se celé tělo mutace, která událost vyvolala</li>
    </ul>
  </dd>
</dl>

Události zachytávání engine jsou reprezentovány instancemi <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeSystemCapture.java</SourceClass>, které obsahují následující informace:

<LS to="j">

<dl>
  <dt>long `version`</dt>
  <dd>
    Verze evitaDB, ve které k mutaci dochází.
  </dd>
  <dt>int `index`</dt>
  <dd>
    Index mutace v rámci stejné transakce. Index `0` je vždy infrastrukturní mutace typu <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass>.
  </dd>
  <dt>`operation`</dt>
  <dd>
    Klasifikace mutace definovaná výčtem:
    <ul>
        <li>`UPSERT` - Vytvoření nebo aktualizace. Pokud již existovala data s touto identitou, byla aktualizována. Pokud ne, byla vytvořena.</li>
        <li>`REMOVE` - Odebrání – tj. předtím existovala data s touto identitou a byla odstraněna.</li>
        <li>`TRANSACTION` - Omezující operace signalizující začátek transakce.</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/EngineMutation.java</SourceClass> `body` (volitelné)</dt>
  <dd>
    Volitelné tělo operace, pokud je požadováno zvoleným <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCaptureContent.java</SourceClass>.
  </dd>
</dl>

</LS>
<LS to="r">

<dl>
  <dt>long `version`</dt>
  <dd>
    Verze evitaDB, ve které k mutaci dochází.
  </dd>
  <dt>int `index`</dt>
  <dd>
    Index mutace v rámci stejné transakce. Index `0` je vždy infrastrukturní mutace typu <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass>.
  </dd>
  <dt>`operation`</dt>
  <dd>
    Klasifikace mutace definovaná výčtem:
    <ul>
        <li>`UPSERT` - Vytvoření nebo aktualizace. Pokud již existovala data s touto identitou, byla aktualizována. Pokud ne, byla vytvořena.</li>
        <li>`REMOVE` - Odebrání – tj. předtím existovala data s touto identitou a byla odstraněna.</li>
        <li>`TRANSACTION` - Omezující operace signalizující začátek transakce.</li>
    </ul>
  </dd>
  <dt>`EngineMutationUnion` `body` (volitelné)</dt>
  <dd>
    Volitelné tělo operace, pokud je požadováno zvoleným <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCaptureContent.java</SourceClass>.
  </dd>
</dl>

</LS>

### Jak nastavit nové zachytávání změn na úrovni engine

<LS to="j">

Nastavení je velmi jednoduché a skládá se ze tří kroků:

1. vytvořte [Java Flow Publisher](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Flow.Publisher.html) pomocí <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass>
2. definujte subscriber implementující [Java Flow Subscriber](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Flow.Subscriber.html)
3. přihlaste subscriber k publisheru

Příklad nastavení zachytávání změn na úrovni engine v Javě:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java,/documentation/user/en/use/api/example/finalization-of-warmup-mode.java" langSpecificTabOnly local>

[Nastavení minimálního zachytávání změn na úrovni engine](/documentation/user/en/use/api/example/engine-change-capture.java)

</SourceCodeTabs>

Subscriber začne přijímat události změn, jakmile k nim v engine dojde. Metoda `onComplete` subscriberu se nikdy nevolá, protože stream změn je nekonečný.

</LS>
<LS to="r">

Stream zachytávání na úrovni engine je dostupný v system API přes endpoint `/rest/system/change-captures`.

Nastavení je velmi jednoduché:
1. otevřete WebSocket spojení odesláním `GET` požadavku s požadavkem na upgrade spojení,
2. odešlete zprávu `connection_init` v rámci WebSocket spojení
3. odešlete zprávu `subscribe` v rámci WebSocket spojení s `ChangeSystemCaptureRequest` definujícím
   filtrační strategii (jak je specifikováno ve
   [specifikaci WebSocket](/documentation/user/en/use/connectors/rest-over-websocket-protocol.md)).

CDC stream nyní bude klientovi zasílat objekty `ChangeSystemCapture` zabalené do zpráv `next`.

Příklad nastavení zachytávání změn na úrovni engine v REST over WebSocket API:

<SourceAlternativeTabs variants="rest">

[Nastavení minimálního zachytávání změn na úrovni engine](/documentation/user/en/use/api/example/engine-change-capture-rest.json)

</SourceAlternativeTabs>

Subscriber začne přijímat události změn, jakmile k nim v engine dojde. Metoda `Complete` subscriberu se nikdy nevolá, protože stream změn je nekonečný.

</LS>
</LS>
<LS to="g">

Stream zachytávání na úrovni engine umožňuje klientům odebírat `ChangeSystemCapture` (nebo `GenericChangeSystemCapture`,
dle zvoleného typu odběru)
instance reprezentující změny provedené v engine.

Požadavek umožňuje zadat následující parametry:

<dl>
  <dt>long `sinceVersion` (volitelné)</dt>
  <dd>
    Verze katalogu (včetně), od které chcete začít přijímat změny. Pokud není zadáno, stream změn začne od další verze katalogu (tj. změny provedené v katalogu v budoucnu).
  </dd>
  <dt>int `sinceIndex` (volitelné)</dt>
  <dd>
    Index mutace v rámci stejné transakce, od kterého chcete začít přijímat změny. Pokud není zadáno, stream změn začne od první mutace zadané verze. Index vám umožňuje přesně určit výchozí bod v případě, že jste již některé mutace dané verze zpracovali.
  </dd>
</dl>

Události zachytávání engine jsou reprezentovány objektem `ChangeSystemCapture` (nebo `GenericChangeSystemCapture`, dle zvoleného typu odběru), který obsahuje následující informace:

<dl>
  <dt>long `version`</dt>
  <dd>
    Verze evitaDB, ve které k mutaci dochází.
  </dd>
  <dt>int `index`</dt>
  <dd>
    Index mutace v rámci stejné transakce. Index `0` je vždy infrastrukturní mutace typu <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass>.
  </dd>
  <dt>`operation`</dt>
  <dd>
    Klasifikace mutace definovaná výčtem:
    <ul>
        <li>`UPSERT` - Vytvoření nebo aktualizace. Pokud již existovala data s touto identitou, byla aktualizována. Pokud ne, byla vytvořena.</li>
        <li>`REMOVE` - Odebrání – tj. předtím existovala data s touto identitou a byla odstraněna.</li>
        <li>`TRANSACTION` - Omezující operace signalizující začátek transakce.</li>
    </ul>
  </dd>
  <dt>`EngineMutationUnion` `body`</dt>
  <dd>
    Tělo operace.
  </dd>
</dl>

### Jak nastavit nové zachytávání změn na úrovni engine

Stream zachytávání na úrovni engine je dostupný v system API prostřednictvím následujících typů odběrů:

- `onSystemChange`
- `onSystemChangeUntyped`

Nastavení je velmi jednoduché: definujte jeden odběr s požadovanými parametry a přihlaste se ke streamu
přes WebSocket protokol. WebSocket stream pak bude klientovi zasílat události změn podle definovaného
výstupu.

<SourceCodeTabs langSpecificTabOnly ignoreTest>

[Nastavení minimálního zachytávání změn na úrovni engine](/documentation/user/en/use/api/example/engine-change-capture-graphql.graphql)

</SourceCodeTabs>

Subscriber začne přijímat události změn, jakmile k nim v engine dojde. Metoda `Complete` subscriberu se nikdy nevolá, protože stream změn je nekonečný.

</LS>

<Note type="info">

V současné době nelze více engine mutací zabalit do jedné transakce. Každá operace na engine je reprezentována samostatnou transakční mutací. Můžete tedy očekávat, že stream mutací engine bude vždy obsahovat transakční mutaci následovanou jednou top-level engine mutací.

</Note>

## Zachytávání změn na úrovni katalogu

<LS to="j,r">

<LS to="j">

Stream zachytávání na úrovni katalogu přijímá <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCaptureRequest.java</SourceClass>
pro vytvoření [Java Flow Publisher](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Flow.Publisher.html). Jeden nebo více klientů se pak může přihlásit k tomuto publisheru a přijímat
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCapture.java</SourceClass>
instance reprezentující změny provedené v katalogu.

</LS>
<LS to="r">

Stream zachytávání na úrovni katalogu přijímá <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCaptureRequest.java</SourceClass>
pro vytvoření CDC streamu. Klienti pak přijímají
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCapture.java</SourceClass>
instance reprezentující změny provedené v katalogu.

</LS>

Požadavek umožňuje zadat následující parametry:

<dl>
  <dt>long `sinceVersion` (volitelné)</dt>
  <dd>
    Verze katalogu (včetně), od které chcete začít přijímat změny. Pokud není zadáno, stream změn začne od další verze katalogu (tj. změny provedené v katalogu v budoucnu).
  </dd>
  <dt>int `sinceIndex` (volitelné)</dt>
  <dd>
    Index mutace v rámci stejné transakce, od kterého chcete začít přijímat změny. Pokud není zadáno, stream změn začne od první mutace zadané verze. Index vám umožňuje přesně určit výchozí bod v případě, že jste již některé mutace dané verze zpracovali.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCaptureCriteria.java</SourceClass>[] `criteria` (volitelné)</dt>
  <dd>
    Pole kritérií, která určují, o jaké změny máte zájem. Pokud není zadáno, zachytávají se všechny změny. Pokud je zadáno více kritérií, stačí splnit alespoň jedno z nich (logika OR). Každé kritérium se skládá z:
    <ul>
        <li>`area` - oblast zachytávání (<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/CaptureArea.java</SourceClass>)</li>
        <li>`site` - místo zachytávání (<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/CaptureSite.java</SourceClass>) pro jemnější filtrování</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCaptureContent.java</SourceClass> `content`</dt>
  <dd>
    Výčtový typ, který určuje, zda klient chce detailní informace o každé mutaci, nebo pouze informace na vyšší úrovni o tom, že došlo k určitému typu mutace. Výčtový typ má následující hodnoty:
    <ul>
        <li>`HEADER` - odesílá se pouze hlavička události</li>
        <li>`BODY` - odesílá se celé tělo mutace, která událost vyvolala</li>
    </ul>
  </dd>
</dl>

Události zachytávání katalogu jsou reprezentovány instancemi <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCapture.java</SourceClass>, které obsahují následující informace:

<LS to="j">

<dl>
  <dt>long `version`</dt>
  <dd>
    Verze katalogu, ve které k mutaci dochází.
  </dd>
  <dt>int `index`</dt>
  <dd>
    Index mutace v rámci stejné transakce. Index `0` je vždy infrastrukturní mutace typu <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass>.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/CaptureArea.java</SourceClass> `area`</dt>
  <dd>
    Oblast operace, která byla provedena:
    <ul>
        <li>`SCHEMA` - zachycují se změny ve schématu</li>
        <li>`DATA` - zachycují se změny v datech</li>
        <li>`INFRASTRUCTURE` - infrastrukturní mutace, které nejsou ani schéma, ani data</li>
    </ul>
  </dd>
  <dt>String `entityType` (volitelné)</dt>
  <dd>
    Název typu entity, který byl operací ovlivněn. Toto pole je null, pokud byla operace provedena přímo na schématu katalogu.
  </dd>
  <dt>Integer `entityPrimaryKey` (volitelné)</dt>
  <dd>
    Primární klíč entity, která byla operací ovlivněna. Přítomno pouze u operací v oblasti dat.
  </dd>
  <dt>`operation`</dt>
  <dd>
    Klasifikace mutace definovaná výčtem:
    <ul>
        <li>`UPSERT` - Vytvoření nebo aktualizace. Pokud již existovala data s touto identitou, byla aktualizována. Pokud ne, byla vytvořena.</li>
        <li>`REMOVE` - Odebrání – tj. předtím existovala data s touto identitou a byla odstraněna.</li>
        <li>`TRANSACTION` - Omezující operace signalizující začátek transakce.</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/CatalogBoundMutation.java</SourceClass> `body` (volitelné)</dt>
  <dd>
    Volitelné tělo operace, pokud je požadováno zvoleným <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCaptureContent.java</SourceClass>.
  </dd>
</dl>

</LS>
<LS to="r">

<dl>
  <dt>long `version`</dt>
  <dd>
    Verze katalogu, ve které k mutaci dochází.
  </dd>
  <dt>int `index`</dt>
  <dd>
    Index mutace v rámci stejné transakce. Index `0` je vždy infrastrukturní mutace typu <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass>.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/CaptureArea.java</SourceClass> `area`</dt>
  <dd>
    Oblast operace, která byla provedena:
    <ul>
        <li>`SCHEMA` - zachycují se změny ve schématu</li>
        <li>`DATA` - zachycují se změny v datech</li>
        <li>`INFRASTRUCTURE` - infrastrukturní mutace, které nejsou ani schéma, ani data</li>
    </ul>
  </dd>
  <dt>String `entityType` (volitelné)</dt>
  <dd>
    Název typu entity, který byl operací ovlivněn. Toto pole je null, pokud byla operace provedena přímo na schématu katalogu.
  </dd>
  <dt>Integer `entityPrimaryKey` (volitelné)</dt>
  <dd>
    Primární klíč entity, která byla operací ovlivněna. Přítomno pouze u operací v oblasti dat.
  </dd>
  <dt>`operation`</dt>
  <dd>
    Klasifikace mutace definovaná výčtem:
    <ul>
        <li>`UPSERT` - Vytvoření nebo aktualizace. Pokud již existovala data s touto identitou, byla aktualizována. Pokud ne, byla vytvořena.</li>
        <li>`REMOVE` - Odebrání – tj. předtím existovala data s touto identitou a byla odstraněna.</li>
        <li>`TRANSACTION` - Omezující operace signalizující začátek transakce.</li>
    </ul>
  </dd>
  <dt>`CatalogBoundMutationUnion` `body` (volitelné)</dt>
  <dd>
    Volitelné tělo operace, pokud je požadováno zvoleným <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCaptureContent.java</SourceClass>.
  </dd>
</dl>

</LS>

### Oblasti a místa zachytávání

Katalogové CDC rozlišuje tři různé **oblasti zachytávání**, které odpovídají různým typům operací:

#### Oblast zachytávání schématu

Oblast zachytávání schématu sleduje změny ve schématu katalogu a schématech entit. To zahrnuje operace jako:

- Vytváření, aktualizace nebo mazání schémat entit
- Úpravy definic atributů, referencí a asociovaných dat entit
- Změny nastavení schématu na úrovni katalogu

Oblast schématu využívá <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/SchemaSite.java</SourceClass> pro filtrování, což umožňuje zadat:

<dl>
  <dt>String `entityType` (volitelné)</dt>
  <dd>
    Filtrování podle konkrétního názvu typu entity. Pokud není zadáno, zachycují se změny všech typů entit.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/Operation.java</SourceClass>[] `operation` (volitelné)</dt>
  <dd>
    Filtrování podle typu operace. Pokud není zadáno, zachycují se všechny operace. Možné hodnoty:
    <ul>
      <li>`UPSERT` - Vytvoření nebo aktualizace</li>
      <li>`REMOVE` - Odebrání</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ContainerType.java</SourceClass>[] `containerType` (volitelné)</dt>
  <dd>
    Filtrování podle typu kontejneru. Pokud není zadáno, zachycují se změny všech typů kontejnerů. Možné hodnoty:
    <ul>
      <li>`CATALOG` - Změny schématu na úrovni katalogu</li>
      <li>`ENTITY` - Změny schématu entity</li>
      <li>`ATTRIBUTE` - Změny schématu atributu</li>
      <li>`ASSOCIATED_DATA` - Změny schématu asociovaných dat</li>
      <li>`PRICE` - Změny schématu cen</li>
      <li>`REFERENCE` - Změny schématu referencí</li>
    </ul>
  </dd>
</dl>

#### Oblast zachytávání dat

Oblast zachytávání dat sleduje změny v datech entit v rámci katalogu. To zahrnuje operace jako:

- Vytváření, aktualizace nebo mazání entit
- Úpravy hodnot atributů, referencí a asociovaných dat entit
- Aktualizace cen a hierarchického zařazení

Oblast dat využívá <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/DataSite.java</SourceClass> pro filtrování, což umožňuje zadat:

<dl>
  <dt>String `entityType` (volitelné)</dt>
  <dd>
    Filtrování podle konkrétního názvu typu entity. Pokud není zadáno, zachycují se změny všech typů entit.
  </dd>
  <dt>Integer `entityPrimaryKey` (volitelné)</dt>
  <dd>
    Filtrování podle konkrétního primárního klíče entity. Pokud není zadáno, zachycují se změny všech entit.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/Operation.java</SourceClass>[] `operation` (volitelné)</dt>
  <dd>
    Filtrování podle typu operace. Pokud není zadáno, zachycují se všechny operace. Možné hodnoty:
    <ul>
      <li>`UPSERT` - Vytvoření nebo aktualizace</li>
      <li>`REMOVE` - Odebrání</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ContainerType.java</SourceClass>[] `containerType` (volitelné)</dt>
  <dd>
    Filtrování podle typu kontejneru. Pokud není zadáno, zachycují se změny všech typů kontejnerů. Možné hodnoty:
    <ul>
      <li>`ENTITY` - Změny na úrovni entity</li>
      <li>`ATTRIBUTE` - Změny hodnot atributů</li>
      <li>`ASSOCIATED_DATA` - Změny hodnot asociovaných dat</li>
      <li>`PRICE` - Změny cen</li>
      <li>`REFERENCE` - Změny referencí</li>
    </ul>
  </dd>
  <dt>String[] `containerName` (volitelné)</dt>
  <dd>
    Filtrování podle konkrétního názvu kontejneru (např. konkrétní název atributu jako `name`, `code`). Pokud není zadáno, zachycují se změny všech kontejnerů.
  </dd>
</dl>

#### Oblast zachytávání infrastruktury

Oblast zachytávání infrastruktury sleduje transakční a další infrastrukturní mutace, které nespadají do kategorií schéma nebo data. To zahrnuje:

- Omezující operace transakcí
- Systémové operace

Oblast infrastruktury nepoužívá žádné místo zachytávání pro filtrování — aktuálně zachycuje všechny infrastrukturní mutace reprezentované <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass>.

<dl>
  <dt>Žádné filtrační parametry</dt>
  <dd>
    Oblast infrastruktury zachycuje všechny transakční a systémové mutace bez jakýchkoli filtračních možností. Pro zachycení infrastrukturních mutací zadejte `CaptureArea.INFRASTRUCTURE` ve svých kritériích bez capture site.
  </dd>
</dl>

Tato oblast existuje samostatně, protože hranice transakcí a systémové operace jsou ortogonální ke změnám schématu i dat a klienti mohou potřebovat sledovat hranice transakcí nezávisle pro správné seskupování událostí a zajištění konzistence.

### Jak nastavit nové zachytávání změn na úrovni katalogu

Nastavení zachytávání změn na úrovni katalogu se liší od zachytávání na úrovni engine tím, že funguje na úrovni katalogu.

<LS to="j">

Nastavení se skládá z:

1. Otevřete session (read-only nebo read-write) ke katalogu
2. Zavolejte `registerChangeCatalogCapture` s <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCaptureRequest.java</SourceClass>
3. Zpracujte vrácený stream událostí <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCapture.java</SourceClass>

Příklad získání historie změn katalogu v Javě:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java,/documentation/user/en/use/api/example/finalization-of-warmup-mode.java" langSpecificTabOnly local>

[Nastavení minimálního zachytávání změn katalogu](/documentation/user/en/use/api/example/catalog-change-capture.java)

</SourceCodeTabs>

Níže naleznete také další užitečné příklady:

<Note type="info">

<NoteTitle toggles="true">

##### Získání transakčních oddělovačů a změn pro všechny entity určitého typu

</NoteTitle>

Tento publisher bude doručovat všechny transakční oddělovače a všechny změny provedené na entitách typu `Product` počínaje
další verzí katalogu.

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java,/documentation/user/en/use/api/example/finalization-of-warmup-mode.java" langSpecificTabOnly local>

[Požadavek na změny na úrovni entity v transakčních blocích](/documentation/user/en/use/api/example/capture-entity-mutations-with-transaction.java)

</SourceCodeTabs>

</Note>

<Note type="info">

<NoteTitle toggles="true">

##### Získání změn pro atribut s názvem `quantityOnStock` konkrétní entity typu `Product`

</NoteTitle>

Následující publisher bude doručovat všechny změny provedené na atributu `quantityOnStock` entity typu `Product` s primárním
klíčem `745` počínaje další verzí katalogu.

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java,/documentation/user/en/use/api/example/finalization-of-warmup-mode.java" langSpecificTabOnly local>

[Požadavek na změny na úrovni entity](/documentation/user/en/use/api/example/capture-attribute-mutation.java)

</SourceCodeTabs>

</Note>

</LS>
<LS to="r">

Stream zachytávání na úrovni katalogu je dostupný v catalog API přes endpoint `/rest/{catalogName}/change-captures`.

Nastavení je velmi jednoduché:
1. otevřete WebSocket spojení odesláním `GET` požadavku s požadavkem na upgrade spojení
2. odešlete zprávu `connection_init` v rámci WebSocket spojení
3. odešlete zprávu `subscribe` v rámci WebSocket spojení s `ChangeCatalogCaptureRequest` definujícím
   filtrační strategii (jak je specifikováno ve
   [specifikaci WebSocket](/documentation/user/en/use/connectors/rest-over-websocket-protocol.md)).

CDC stream nyní bude klientovi zasílat objekty `ChangeCatalogCapture` zabalené do zpráv `next`.

Příklad získání historie změn katalogu v protokolu WebSocket pro REST:

<SourceAlternativeTabs variants="rest">

[Nastavení minimálního zachytávání změn katalogu](/documentation/user/en/use/api/example/catalog-change-capture-rest.json)

</SourceAlternativeTabs>

Níže naleznete také další užitečné příklady:

<Note type="info">

<NoteTitle toggles="true">

##### Získání transakčních oddělovačů a změn pro všechny entity určitého typu

</NoteTitle>

Tento odběr bude doručovat všechny transakční oddělovače a všechny změny provedené na entitách typu `Product` počínaje
další verzí katalogu.

<SourceAlternativeTabs variants="rest">

[Požadavek na změny na úrovni entity v transakčních blocích](/documentation/user/en/use/api/example/capture-entity-mutations-with-transaction-rest.json)

</SourceAlternativeTabs>

</Note>

<Note type="info">

<NoteTitle toggles="true">

##### Získání změn pro atribut s názvem `quantityOnStock` konkrétní entity typu `Product`

</NoteTitle>

Následující odběr bude doručovat všechny změny provedené na atributu `quantityOnStock` entity typu `Product` s primárním
klíčem `745` počínaje další verzí katalogu.

<SourceAlternativeTabs variants="rest">

[Požadavek na změny na úrovni entity](/documentation/user/en/use/api/example/capture-attribute-mutation-rest.json)

</SourceAlternativeTabs>

</Note>

</LS>

<LS to="j">

### Často kladené otázky ohledně mechanismu zachytávání změn

<Note type="question">

<NoteTitle toggles="true">

##### Musím si uchovávat instanci publisheru?

</NoteTitle>

Ne — můžete ji nechat garbage collectnout. Publisher je pouze továrna pro vytváření subscriberů. Jakmile je subscriber vytvořen a přihlášen, udržuje si svůj vlastní stav a spojení s engine. Reference na subscriber je uchovávána v instanci evitaDB (client), což brání jeho garbage collectnutí, dokud je instance aktivní.

Reference na publisher potřebujete uchovávat pouze v případě, že plánujete k němu přihlásit více subscriberů.

</Note>

<Note type="question">

<NoteTitle toggles="true">

##### Potřebuji platnou session pro odběr změn katalogu?

</NoteTitle>

Ne, session potřebujete pouze pro vytvoření publisheru. Jakmile je publisher vytvořen, subscribeři se k němu mohou přihlásit i bez aktivní session. Publisher interně otevře dedikovanou session pro každého subscriberu, pokud odběr není vytvořen v rámci aktivní session.

</Note>

<Note type="question">

<NoteTitle toggles="true">

##### Co když se k publisheru přihlásím později – od jakého bodu budu přijímat změny?

</NoteTitle>

Publisher "zmrazí" parametry CDC požadavku (včetně výchozí verze) v okamžiku svého vytvoření. Pokud požadavek obsahuje výchozí verzi katalogu, každý subscriber obdrží změny od verze uvedené v CDC požadavku použitém k vytvoření publisheru, bez ohledu na to, kdy se k publisheru přihlásí. Pokud požadavek neobsahuje výchozí verzi, každý subscriber obdrží změny od další verze katalogu v okamžiku svého přihlášení.

</Note>

<Note type="question">

<NoteTitle toggles="true">

##### Jak správně uzavřít a uvolnit prostředky?

</NoteTitle>

Pokud vaše třída subscriber implementuje rozhraní [AutoCloseable](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/AutoCloseable.html), můžete se spolehnout na to, že instance evitaDB (client) ji automaticky uzavře při uzavření instance klienta. Metoda close bude automaticky zavolána při zrušení odběru nebo při uzavření instance klienta.

</Note>

</LS>
</LS>

<LS to="g">

Stream zachytávání na úrovni katalogu poskytuje přístup k
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCapture.java</SourceClass>
instancím reprezentujícím změny provedené v katalogu.

Události zachytávání katalogu jsou reprezentovány instancemi <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCapture.java</SourceClass>, které obsahují následující informace:

<dl>
  <dt>long `version`</dt>
  <dd>
    Verze katalogu, ve které k mutaci dochází.
  </dd>
  <dt>int `index`</dt>
  <dd>
    Index mutace v rámci stejné transakce. Index `0` je vždy infrastrukturní mutace typu <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass>.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/CaptureArea.java</SourceClass> `area`</dt>
  <dd>
    Oblast operace, která byla provedena:
    <ul>
        <li>`SCHEMA` - zachycují se změny ve schématu</li>
        <li>`DATA` - zachycují se změny v datech</li>
        <li>`INFRASTRUCTURE` - infrastrukturní mutace, které nejsou ani schéma, ani data</li>
    </ul>
  </dd>
  <dt>String `entityType` (volitelné)</dt>
  <dd>
    Název typu entity, který byl operací ovlivněn. Toto pole je null, pokud byla operace provedena přímo na schématu katalogu.
  </dd>
  <dt>Integer `entityPrimaryKey` (volitelné)</dt>
  <dd>
    Primární klíč entity, která byla operací ovlivněna. Přítomno pouze u operací v oblasti dat.
  </dd>
  <dt>`operation`</dt>
  <dd>
    Klasifikace mutace definovaná výčtem:
    <ul>
        <li>`UPSERT` - Vytvoření nebo aktualizace. Pokud již existovala data s touto identitou, byla aktualizována. Pokud ne, byla vytvořena.</li>
        <li>`REMOVE` - Odebrání – tj. předtím existovala data s touto identitou a byla odstraněna.</li>
        <li>`TRANSACTION` - Omezující operace signalizující začátek transakce.</li>
    </ul>
  </dd>
  <dt>`CatalogBoundMutationUnion` `body` (volitelné)</dt>
  <dd>
    Volitelné tělo operace, pokud je požadováno zvoleným <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCaptureContent.java</SourceClass>.
  </dd>
</dl>

Existuje několik způsobů, jak přistupovat ke streamu zachytávání změn katalogu, každý s mírně odlišným účelem:

### System API

GraphQL system API vystavuje odběr `onCatalogChange`/`onCatalogChangeUntyped`, který umožňuje přihlásit se
ke streamu zachytávání změn katalogu libovolného katalogu s plně vlastním nastavením filtračních kritérií.

To je užitečné, pokud potřebujete reagovat na **všechny** změny (transakční, data, schéma) v katalogu.

Odběr přijímá následující parametry:

<dl>
  <dt>String `catalogName`</dt>
  <dd>
    Název katalogu, ke kterému se chcete přihlásit.
  </dd>
  <dt>long `sinceVersion` (volitelné)</dt>
  <dd>
    Verze katalogu (včetně), od které chcete začít přijímat změny. Pokud není zadáno, stream změn začne od další verze katalogu (tj. změny provedené v katalogu v budoucnu).
  </dd>
  <dt>int `sinceIndex` (volitelné)</dt>
  <dd>
    Index mutace v rámci stejné transakce, od kterého chcete začít přijímat změny. Pokud není zadáno, stream změn začne od první mutace zadané verze. Index vám umožňuje přesně určit výchozí bod v případě, že jste již některé mutace dané verze zpracovali.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCaptureCriteria.java</SourceClass>[] `criteria` (volitelné)</dt>
  <dd>
    Pole kritérií, která určují, o jaké změny máte zájem. Pokud není zadáno, zachytávají se všechny změny. Pokud je zadáno více kritérií, stačí splnit alespoň jedno z nich (logika OR). Každé kritérium se skládá z:
    <ul>
        <li>`area` - oblast zachytávání (<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/CaptureArea.java</SourceClass>)</li>
        <li>`site` - místo zachytávání (<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/CaptureSite.java</SourceClass>) pro jemnější filtrování</li>
    </ul>
  </dd>
</dl>

#### Oblasti a místa zachytávání

Katalogové CDC rozlišuje tři různé **oblasti zachytávání**, které odpovídají různým typům operací:

##### Oblast zachytávání schématu

Oblast zachytávání schématu sleduje změny ve schématu katalogu a schématech entit. To zahrnuje operace jako:

- Vytváření, aktualizace nebo mazání schémat entit
- Úpravy definic atributů, referencí a asociovaných dat entit
- Změny nastavení schématu na úrovni katalogu

Oblast schématu využívá <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/SchemaSite.java</SourceClass> pro filtrování, což umožňuje zadat:

<dl>
  <dt>String `entityType` (volitelné)</dt>
  <dd>
    Filtrování podle konkrétního názvu typu entity. Pokud není zadáno, zachycují se změny všech typů entit.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/Operation.java</SourceClass>[] `operation` (volitelné)</dt>
  <dd>
    Filtrování podle typu operace. Pokud není zadáno, zachycují se všechny operace. Možné hodnoty:
    <ul>
      <li>`UPSERT` - Vytvoření nebo aktualizace</li>
      <li>`REMOVE` - Odebrání</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ContainerType.java</SourceClass>[] `containerType` (volitelné)</dt>
  <dd>
    Filtrování podle typu kontejneru. Pokud není zadáno, zachycují se změny všech typů kontejnerů. Možné hodnoty:
    <ul>
      <li>`CATALOG` - Změny schématu na úrovni katalogu</li>
      <li>`ENTITY` - Změny schématu entity</li>
      <li>`ATTRIBUTE` - Změny schématu atributu</li>
      <li>`ASSOCIATED_DATA` - Změny schématu asociovaných dat</li>
      <li>`PRICE` - Změny schématu cen</li>
      <li>`REFERENCE` - Změny schématu referencí</li>
    </ul>
  </dd>
</dl>

##### Oblast zachytávání dat

Oblast zachytávání dat sleduje změny v datech entit v rámci katalogu. To zahrnuje operace jako:

- Vytváření, aktualizace nebo mazání entit
- Úpravy hodnot atributů, referencí a asociovaných dat entit
- Aktualizace cen a hierarchického zařazení

Oblast dat využívá <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/DataSite.java</SourceClass> pro filtrování, což umožňuje zadat:

<dl>
  <dt>String `entityType` (volitelné)</dt>
  <dd>
    Filtrování podle konkrétního názvu typu entity. Pokud není zadáno, zachycují se změny všech typů entit.
  </dd>
  <dt>Integer `entityPrimaryKey` (volitelné)</dt>
  <dd>
    Filtrování podle konkrétního primárního klíče entity. Pokud není zadáno, zachycují se změny všech entit.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/Operation.java</SourceClass>[] `operation` (volitelné)</dt>
  <dd>
    Filtrování podle typu operace. Pokud není zadáno, zachycují se všechny operace. Možné hodnoty:
    <ul>
      <li>`UPSERT` - Vytvoření nebo aktualizace</li>
      <li>`REMOVE` - Odebrání</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ContainerType.java</SourceClass>[] `containerType` (volitelné)</dt>
  <dd>
    Filtrování podle typu kontejneru. Pokud není zadáno, zachycují se změny všech typů kontejnerů. Možné hodnoty:
    <ul>
      <li>`ENTITY` - Změny na úrovni entity</li>
      <li>`ATTRIBUTE` - Změny hodnot atributů</li>
      <li>`ASSOCIATED_DATA` - Změny hodnot asociovaných dat</li>
      <li>`PRICE` - Změny cen</li>
      <li>`REFERENCE` - Změny referencí</li>
    </ul>
  </dd>
  <dt>String[] `containerName` (volitelné)</dt>
  <dd>
    Filtrování podle konkrétního názvu kontejneru (např. konkrétní název atributu jako `name`, `code`). Pokud není zadáno, zachycují se změny všech kontejnerů.
  </dd>
</dl>

##### Oblast zachytávání infrastruktury

Oblast zachytávání infrastruktury sleduje transakční a další infrastrukturní mutace, které nespadají do kategorií schéma nebo data. To zahrnuje:

- Omezující operace transakcí
- Systémové operace

Oblast infrastruktury nepoužívá žádné místo zachytávání pro filtrování — aktuálně zachycuje všechny infrastrukturní mutace reprezentované <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass>.

<dl>
  <dt>Žádné filtrační parametry</dt>
  <dd>
    Oblast infrastruktury zachycuje všechny transakční a systémové mutace bez jakýchkoli filtračních možností. Pro zachycení infrastrukturních mutací zadejte `CaptureArea.INFRASTRUCTURE` ve svých kritériích bez capture site.
  </dd>
</dl>

Tato oblast existuje samostatně, protože hranice transakcí a systémové operace jsou ortogonální ke změnám schématu i dat a klienti mohou potřebovat sledovat hranice transakcí nezávisle pro správné seskupování událostí a zajištění konzistence.

#### Jak nastavit nové zachytávání změn na úrovni katalogu v system API

Nastavení je velmi jednoduché: definujte jeden odběr s požadovanými parametry a přihlaste se ke streamu
přes WebSocket protokol. WebSocket stream pak bude klientovi zasílat události změn podle definovaného
výstupu.

Příklad získání historie změn katalogu v GraphQL system API:

<SourceCodeTabs langSpecificTabOnly ignoreTest>

[Nastavení minimálního zachytávání změn katalogu](/documentation/user/en/use/api/example/catalog-change-capture-graphql.graphql)

</SourceCodeTabs>

Níže naleznete také další užitečné příklady:

<Note type="info">

<NoteTitle toggles="true">

##### Získání transakčních oddělovačů a změn pro všechny entity určitého typu

</NoteTitle>

Tento odběr bude doručovat všechny transakční oddělovače a všechny změny provedené na entitách typu `Product` počínaje
další verzí katalogu.

<SourceCodeTabs langSpecificTabOnly ignoreTest>

[Požadavek na změny na úrovni entity v transakčních blocích](/documentation/user/en/use/api/example/capture-entity-mutations-with-transaction-graphql.graphql)

</SourceCodeTabs>

</Note>

<Note type="info">

<NoteTitle toggles="true">

##### Získání změn pro atribut s názvem `quantityOnStock` konkrétní entity typu `Product`

</NoteTitle>

Následující odběr bude doručovat všechny změny provedené na atributu `quantityOnStock` entity typu `Product` s primárním
klíčem `745` počínaje další verzí katalogu.

<SourceCodeTabs langSpecificTabOnly ignoreTest>

[Požadavek na změny na úrovni entity](/documentation/user/en/use/api/example/capture-attribute-mutation-graphql.graphql)

</SourceCodeTabs>

</Note>

### Catalogue data API

Pokud nepotřebujete plně vybavené odběry CDC system API, GraphQL data API vystavuje dva zjednodušené odběry:

Prvním je odběr `onDataChange`/`onDataChangeUntyped`, který umožňuje přihlásit se ke streamu zachytávání _datových_ změn
celého katalogu specifikovaného API (se všemi kolekcemi entit).

To je užitečné, pokud potřebujete reagovat pouze na datové změny a _nic víc_. Pokud ano, tento odběr poskytuje jednodušší rozhraní
s menší sadou mutací, které je třeba řešit.

Odběr přijímá následující parametry:

<dl>
  <dt>long `sinceVersion` (volitelné)</dt>
  <dd>
    Verze katalogu (včetně), od které chcete začít přijímat změny. Pokud není zadáno, stream změn začne od další verze katalogu (tj. změny provedené v katalogu v budoucnu).
  </dd>
  <dt>int `sinceIndex` (volitelné)</dt>
  <dd>
    Index mutace v rámci stejné transakce, od kterého chcete začít přijímat změny. Pokud není zadáno, stream změn začne od první mutace zadané verze. Index vám umožňuje přesně určit výchozí bod v případě, že jste již některé mutace dané verze zpracovali.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/Operation.java</SourceClass>[] `operation` (volitelné)</dt>
  <dd>
    Filtrování podle typu operace. Pokud není zadáno, zachycují se všechny operace. Možné hodnoty:
    <ul>
      <li>`UPSERT` - Vytvoření nebo aktualizace</li>
      <li>`REMOVE` - Odebrání</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ContainerType.java</SourceClass>[] `containerType` (volitelné)</dt>
  <dd>
    Filtrování podle typu kontejneru. Pokud není zadáno, zachycují se změny všech typů kontejnerů. Možné hodnoty:
    <ul>
      <li>`CATALOG` - Změny schématu na úrovni katalogu</li>
      <li>`ENTITY` - Změny schématu entity</li>
      <li>`ATTRIBUTE` - Změny schématu atributu</li>
      <li>`ASSOCIATED_DATA` - Změny schématu asociovaných dat</li>
      <li>`PRICE` - Změny schématu cen</li>
      <li>`REFERENCE` - Změny schématu referencí</li>
    </ul>
  </dd>
  <dt>String[] `containerName` (volitelné)</dt>
  <dd>
    Filtrování podle konkrétního názvu kontejneru (např. konkrétní název atributu jako `name`, `code`). Pokud není zadáno, zachycují se změny všech kontejnerů.
  </dd>
</dl>

Druhým je odběr `on{entityType}DataChange`/`on{entityType}DataChangeUntyped`, který umožňuje přihlásit se
ke streamu zachytávání _datových_ změn konkrétní kolekce entit v rámci API-specifikovaného katalogu.

To je užitečné, pokud potřebujete reagovat pouze na datové změny _konkrétní kolekce entit_ a _nic víc_. Pokud ano,
tento odběr poskytuje jednodušší rozhraní s menší sadou mutací, které je třeba řešit.

Odběr přijímá následující parametry:

<dl>
  <dt>Integer `entityPrimaryKey` (volitelné)</dt>
  <dd>
    Filtrování podle konkrétního primárního klíče entity. Pokud není zadáno, zachycují se změny všech entit.
  </dd>
  <dt>long `sinceVersion` (volitelné)</dt>
  <dd>
    Verze katalogu (včetně), od které chcete začít přijímat změny. Pokud není zadáno, stream změn začne od další verze katalogu (tj. změny provedené v katalogu v budoucnu).
  </dd>
  <dt>int `sinceIndex` (volitelné)</dt>
  <dd>
    Index mutace v rámci stejné transakce, od kterého chcete začít přijímat změny. Pokud není zadáno, stream změn začne od první mutace zadané verze. Index vám umožňuje přesně určit výchozí bod v případě, že jste již některé mutace dané verze zpracovali.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/Operation.java</SourceClass>[] `operation` (volitelné)</dt>
  <dd>
    Filtrování podle typu operace. Pokud není zadáno, zachycují se všechny operace. Možné hodnoty:
    <ul>
      <li>`UPSERT` - Vytvoření nebo aktualizace</li>
      <li>`REMOVE` - Odebrání</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ContainerType.java</SourceClass>[] `containerType` (volitelné)</dt>
  <dd>
    Filtrování podle typu kontejneru. Pokud není zadáno, zachycují se změny všech typů kontejnerů. Možné hodnoty:
    <ul>
      <li>`CATALOG` - Změny schématu na úrovni katalogu</li>
      <li>`ENTITY` - Změny schématu entity</li>
      <li>`ATTRIBUTE` - Změny schématu atributu</li>
      <li>`ASSOCIATED_DATA` - Změny schématu asociovaných dat</li>
      <li>`PRICE` - Změny schématu cen</li>
      <li>`REFERENCE` - Změny schématu referencí</li>
    </ul>
  </dd>
  <dt>String[] `containerName` (volitelné)</dt>
  <dd>
    Filtrování podle konkrétního názvu kontejneru (např. konkrétní název atributu jako `name`, `code`). Pokud není zadáno, zachycují se změny všech kontejnerů.
  </dd>
</dl>

#### Jak nastavit nové zachytávání změn na úrovni katalogu v catalogue data API

Nastavení je velmi jednoduché: definujte jeden odběr s požadovanými parametry a přihlaste se ke streamu
přes WebSocket protokol. WebSocket stream pak bude klientovi zasílat události změn podle definovaného
výstupu.

Příklad získání historie změn katalogu v GraphQL catalogue data API:

<SourceCodeTabs langSpecificTabOnly ignoreTest>

[Nastavení minimálního zachytávání změn katalogu](/documentation/user/en/use/api/example/catalog-change-capture-data-api.graphql)

</SourceCodeTabs>

Níže naleznete také další užitečné příklady:

<Note type="info">

<NoteTitle toggles="true">

##### Získání změn pro atribut s názvem `quantityOnStock` konkrétní entity typu `Product`

</NoteTitle>

Následující odběr bude doručovat všechny změny provedené na atributu `quantityOnStock` entity typu `Product` s primárním
klíčem `745` počínaje další verzí katalogu.

<SourceCodeTabs langSpecificTabOnly ignoreTest>

[Požadavek na změny na úrovni entity](/documentation/user/en/use/api/example/capture-attribute-mutation-data-api.graphql)

</SourceCodeTabs>

</Note>

### Catalogue schema API

Pokud nepotřebujete plně vybavené odběry CDC system API, GraphQL schema API vystavuje dva zjednodušené odběry:

Prvním je odběr `onSchemaChange`/`onSchemaChangeUntyped`, který umožňuje přihlásit se ke streamu zachytávání _schématických_ změn
celého katalogu specifikovaného API (se všemi kolekcemi entit).

To je užitečné, pokud potřebujete reagovat pouze na změny schématu a _nic víc_. Pokud ano, tento odběr poskytuje jednodušší rozhraní
s menší sadou mutací, které je třeba řešit.

Odběr přijímá následující parametry:

<dl>
  <dt>long `sinceVersion` (volitelné)</dt>
  <dd>
    Verze katalogu (včetně), od které chcete začít přijímat změny. Pokud není zadáno, stream změn začne od další verze katalogu (tj. změny provedené v katalogu v budoucnu).
  </dd>
  <dt>int `sinceIndex` (volitelné)</dt>
  <dd>
    Index mutace v rámci stejné transakce, od kterého chcete začít přijímat změny. Pokud není zadáno, stream změn začne od první mutace zadané verze. Index vám umožňuje přesně určit výchozí bod v případě, že jste již některé mutace dané verze zpracovali.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/Operation.java</SourceClass>[] `operation` (volitelné)</dt>
  <dd>
    Filtrování podle typu operace. Pokud není zadáno, zachycují se všechny operace. Možné hodnoty:
    <ul>
      <li>`UPSERT` - Vytvoření nebo aktualizace</li>
      <li>`REMOVE` - Odebrání</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ContainerType.java</SourceClass>[] `containerType` (volitelné)</dt>
  <dd>
    Filtrování podle typu kontejneru. Pokud není zadáno, zachycují se změny všech typů kontejnerů. Možné hodnoty:
    <ul>
      <li>`CATALOG` - Změny schématu na úrovni katalogu</li>
      <li>`ENTITY` - Změny schématu entity</li>
      <li>`ATTRIBUTE` - Změny schématu atributu</li>
      <li>`ASSOCIATED_DATA` - Změny schématu asociovaných dat</li>
      <li>`PRICE` - Změny schématu cen</li>
      <li>`REFERENCE` - Změny schématu referencí</li>
    </ul>
  </dd>
</dl>

Druhým je odběr `on{entityType}SchemaChange`/`on{entityType}SchemaChangeUntyped`, který umožňuje přihlásit se
ke streamu zachytávání _schématických_ změn konkrétní kolekce entit v rámci API-specifikovaného katalogu.

To je užitečné, pokud potřebujete reagovat pouze na změny schématu _konkrétní kolekce entit_ a _nic víc_. Pokud ano,
tento odběr poskytuje jednodušší rozhraní s menší sadou mutací, které je třeba řešit.

Odběr přijímá následující parametry:

<dl>
  <dt>long `sinceVersion` (volitelné)</dt>
  <dd>
    Verze katalogu (včetně), od které chcete začít přijímat změny. Pokud není zadáno, stream změn začne od další verze katalogu (tj. změny provedené v katalogu v budoucnu).
  </dd>
  <dt>int `sinceIndex` (volitelné)</dt>
  <dd>
    Index mutace v rámci stejné transakce, od kterého chcete začít přijímat změny. Pokud není zadáno, stream změn začne od první mutace zadané verze. Index vám umožňuje přesně určit výchozí bod v případě, že jste již některé mutace dané verze zpracovali.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/Operation.java</SourceClass>[] `operation` (volitelné)</dt>
  <dd>
    Filtrování podle typu operace. Pokud není zadáno, zachycují se všechny operace. Možné hodnoty:
    <ul>
      <li>`UPSERT` - Vytvoření nebo aktualizace</li>
      <li>`REMOVE` - Odebrání</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ContainerType.java</SourceClass>[] `containerType` (volitelné)</dt>
  <dd>
    Filtrování podle typu kontejneru. Pokud není zadáno, zachycují se změny všech typů kontejnerů. Možné hodnoty:
    <ul>
      <li>`CATALOG` - Změny schématu na úrovni katalogu</li>
      <li>`ENTITY` - Změny schématu entity</li>
      <li>`ATTRIBUTE` - Změny schématu atributu</li>
      <li>`ASSOCIATED_DATA` - Změny schématu asociovaných dat</li>
      <li>`PRICE` - Změny schématu cen</li>
      <li>`REFERENCE` - Změny schématu referencí</li>
    </ul>
  </dd>
</dl>

#### Jak nastavit nové zachytávání změn na úrovni katalogu v catalogue schema API

Nastavení je velmi jednoduché: definujte jeden odběr s požadovanými parametry a přihlaste se ke streamu
přes WebSocket protokol. WebSocket stream pak bude klientovi zasílat události změn podle definovaného
výstupu.

Příklad získání historie změn katalogu v GraphQL catalogue schema API:

<SourceCodeTabs langSpecificTabOnly ignoreTest>

[Nastavení minimálního zachytávání změn katalogu](/documentation/user/en/use/api/example/catalog-change-capture-schema-api.graphql)

</SourceCodeTabs>

Níže naleznete také další užitečné příklady:

<Note type="info">

<NoteTitle toggles="true">

##### Získání změn atributů konkrétní entity typu `Product`

</NoteTitle>

Následující odběr bude doručovat všechny změny schématu provedené na atributech entity typu `Product` počínaje
další verzí katalogu.

<SourceCodeTabs langSpecificTabOnly ignoreTest>

[Požadavek na změny na úrovni entity](/documentation/user/en/use/api/example/capture-attribute-mutation-schema-api.graphql)

</SourceCodeTabs>

</Note>

</LS>

</LS>

<LS to="c">
Odběr CDC zatím není v C# klientovi podporován.
</LS>