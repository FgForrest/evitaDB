---
title: Zachytávání změn dat
perex: Zachytávání změn dat (CDC) je návrhový vzor používaný ke sledování a zachycování změn provedených ve schématu a datech v databázi. evitaDB podporuje CDC prostřednictvím všech svých API, což vývojářům umožňuje velmi snadno monitorovat a reagovat na změny dat téměř v reálném čase ve svém preferovaném programovacím jazyce. Tento dokument vysvětluje, jak implementovat CDC pomocí našeho API.
date: '21.10.2025'
author: Ing. Jan Novotný
proofreading: needed
preferredLang: java
translated: 'true'
commit: '4efe27359c0fe06993ff265eae9305969f91fe50'
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
    - <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/CatalogBoundMutation.java</SourceClass> ([úplný výpis](../schema.md), dostupné v [catalog schema change capture](#zachytávání-změn-na-úrovni-engine))
        - <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/LocalCatalogSchemaMutation.java</SourceClass>
            - <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/LocalEntitySchemaMutation.java</SourceClass>
                - <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/reference/ModifyReferenceAttributeSchemaMutation.java</SourceClass> 
    - <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/EntityMutation.java</SourceClass> ([úplný výpis](../data-model.md), dostupné v [catalog data change capture](#zachytávání-změn-na-úrovni-engine))
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
instance <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeSystemCapture.java</SourceClass> reprezentující změny provedené v engine.

</LS>
<LS to="r">

Stream zachytávání na úrovni engine přijímá <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeSystemCaptureRequest.java</SourceClass>
pro vytvoření CDC streamu. Klienti pak přijímají instance <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeSystemCapture.java</SourceClass>
reprezentující změny provedené v engine.

</LS>

Požadavek umožňuje zadat následující parametry:

<dl>
  <dt>long `sinceVersion` (volitelné)</dt>
  <dd>
    Verze engine (včetně), od které chcete začít přijímat změny. Pokud není zadáno, stream změn začne od následující verze engine (tj. změny provedené v engine v budoucnu). Verze engine je monotónně rostoucí čítač svázaný s instancí evitaDB a zvyšuje se s každou mutací engine; liší se od verzí jednotlivých katalogů, které poskytuje stream zachytávání změn katalogu.
  </dd>
  <dt>int `sinceIndex` (volitelné)</dt>
  <dd>
    Index mutace v rámci stejné transakce, od kterého chcete začít přijímat změny. Pokud není zadáno, stream změn začne od první mutace zadané verze. Index vám umožňuje přesně určit výchozí bod v případě, že jste již některé mutace dané verze zpracovali.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeSystemCaptureCriteria.java</SourceClass>[] `criteria` (volitelné)</dt>
  <dd>
    Pole kritérií, která určují, o které oblasti systémového streamu máte zájem. Pokud je zadáno více kritérií, stačí splnění kteréhokoli z nich (logika OR). Každé kritérium aktuálně vybírá jednu <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/SystemCaptureArea.java</SourceClass>:
    <ul>
        <li>`ENGINE` — trvalé, WAL-replikované mutace engine (obsahuje těla <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/EngineMutation.java</SourceClass>).</li>
        <li>`HOST` — host-lokální, nereplikovatelné události hostitele o živém pohledu na katalogy na tomto hostiteli (obsahuje těla <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/HostSystemEvent.java</SourceClass>). Viz [Události systémového hostitele](#události-hostitelského-systému) níže.</li>
    </ul>
    **Odlišnost výchozích kritérií oproti streamu katalogu.** Pokud není tato vlastnost zadána (nebo je `null`), je odběr považován pouze za `ENGINE` — události `HOST` **nikdy** nejsou doručeny bez explicitního kritéria, které je povolí. Stream katalogu ve výchozím nastavení zahrnuje všechny oblasti; systémový stream ve výchozím nastavení pouze engine, protože `HOST` nese sémantiku, do které starší klienti neoptovali (host-lokální, pouze live-tail, bez historického přehrávání).
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCaptureContent.java</SourceClass> `content`</dt>
  <dd>
    Výčtový typ určující, zda klient požaduje detailní informace o každé mutaci, nebo pouze základní informaci, že došlo k určitému typu mutace. Výčtový typ má následující hodnoty:
    <ul>
        <li>`HEADER` - odesílá se pouze hlavička události</li>
        <li>`BODY` - odesílá se celý obsah mutace, která událost vyvolala</li>
    </ul>
  </dd>
</dl>

Události zachytávání na úrovni engine jsou reprezentovány instancemi <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeSystemCapture.java</SourceClass>, které obsahují následující informace:

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
    Klasifikace mutace definovaná výčtovým typem:
    <ul>
        <li>`UPSERT` - Vytvoření nebo aktualizace. Pokud již existovala data s touto identitou, byla aktualizována. Pokud ne, byla vytvořena.</li>
        <li>`REMOVE` - Odstranění — tzn. dříve existovala data s touto identitou a byla odstraněna.</li>
        <li>`TRANSACTION` - Omezující operace signalizující začátek transakce.</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/SystemCaptureBody.java</SourceClass> `body` (volitelné)</dt>
  <dd>
    Volitelné tělo události, pokud je požadováno nastavením <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCaptureContent.java</SourceClass>. Tělo je polymorfní — pro oblast `ENGINE` obsahuje <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/EngineMutation.java</SourceClass>; pro oblast `HOST` obsahuje <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/HostSystemEvent.java</SourceClass> (viz [Události systémového hostitele](#události-hostitelského-systému) níže).
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
    Klasifikace mutace definovaná výčtovým typem:
    <ul>
        <li>`UPSERT` - Vytvoření nebo aktualizace. Pokud již existovala data s touto identitou, byla aktualizována. Pokud ne, byla vytvořena.</li>
        <li>`REMOVE` - Odstranění — tzn. dříve existovala data s touto identitou a byla odstraněna.</li>
        <li>`TRANSACTION` - Omezující operace signalizující začátek transakce.</li>
    </ul>
  </dd>
  <dt>`SystemCaptureBodyUnion` `body` (volitelné)</dt>
  <dd>
    Volitelné tělo události, pokud je požadováno nastavením <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCaptureContent.java</SourceClass>. Tělo je union — pro oblast `ENGINE` obsahuje hodnotu `EngineMutationUnion`; pro oblast `HOST` obsahuje hodnotu `HostSystemEventUnion` (viz [Události systémového hostitele](#události-hostitelského-systému) níže).
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

Stream zachytávání na úrovni engine je dostupný v system API prostřednictvím endpointu `/rest/system/change-captures`.

Nastavení je velmi jednoduché:
1. otevřete WebSocket spojení odesláním požadavku `GET` s požadavkem na upgrade spojení,
2. v rámci WebSocket spojení odešlete zprávu `connection_init`
3. v rámci WebSocket spojení odešlete zprávu `subscribe` s objektem `ChangeSystemCaptureRequest` definujícím
   strategii filtrování (dle [specifikace WebSocket](/documentation/user/en/use/connectors/rest-over-websocket-protocol.md)).

CDC stream nyní bude klientovi zasílat objekty `ChangeSystemCapture` zabalené do zpráv `next`.

Příklad nastavení zachytávání změn na úrovni engine v REST přes WebSocket API:

<SourceAlternativeTabs variants="rest">

[Nastavení minimálního zachytávání změn na úrovni engine](/documentation/user/en/use/api/example/engine-change-capture-rest.json)

</SourceAlternativeTabs>

Subscriber začne přijímat události změn, jakmile k nim v engine dojde. Metoda `Complete` subscriberu se nikdy nevolá, protože stream změn je nekonečný.

</LS>
<LS to="g">

Stream zachytávání na úrovni engine umožňuje klientům přihlásit se k odběru instancí `ChangeSystemCapture` (nebo `GenericChangeSystemCapture`,
v závislosti na zvoleném typu odběru), které reprezentují změny provedené v engine.

Požadavek umožňuje zadat následující parametry:

<dl>
  <dt>long `sinceVersion` (volitelné)</dt>
  <dd>
    Verze engine (včetně), od které chcete začít přijímat změny. Pokud není zadáno, stream změn začne od následující verze engine (tj. změny provedené v engine v budoucnu). Verze engine je monotónně rostoucí čítač svázaný s instancí evitaDB a zvyšuje se s každou mutací engine; liší se od verzí jednotlivých katalogů, které poskytuje stream zachytávání změn katalogu.
  </dd>
  <dt>int `sinceIndex` (volitelné)</dt>
  <dd>
    Index mutace v rámci stejné transakce, od kterého chcete začít přijímat změny. Pokud není zadáno, stream změn začne od první mutace zadané verze. Index vám umožňuje přesně určit výchozí bod v případě, že jste již některé mutace dané verze zpracovali.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeSystemCaptureCriteria.java</SourceClass>[] `criteria` (volitelné)</dt>
  <dd>
    Pole kritérií, která určují, o které oblasti systémového streamu máte zájem. Pokud je zadáno více kritérií, stačí splnění kteréhokoli z nich (logika OR). Každé kritérium aktuálně vybírá jednu <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/SystemCaptureArea.java</SourceClass>:
    <ul>
        <li>`ENGINE` — trvalé, WAL-replikované mutace engine.</li>
        <li>`HOST` — host-lokální, nereplikovatelné události o živém pohledu na katalogy na tomto hostiteli (viz [Události systémového hostitele](#události-hostitelského-systému) níže).</li>
    </ul>
    **Odlišnost výchozích kritérií oproti streamu katalogu.** Pokud není tato vlastnost zadána, je odběr považován pouze za `ENGINE` — události `HOST` **nikdy** nejsou doručeny bez explicitního kritéria, které je povolí. Stream katalogu ve výchozím nastavení zahrnuje všechny oblasti; systémový stream ve výchozím nastavení pouze engine, protože `HOST` nese sémantiku, do které starší klienti neoptovali (host-lokální, pouze live-tail, bez historického přehrávání).
  </dd>
</dl>

Události zachytávání na úrovni engine jsou reprezentovány objektem `ChangeSystemCapture` (nebo `GenericChangeSystemCapture`, dle zvoleného typu odběru), který obsahuje následující informace:

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
    Klasifikace mutace definovaná výčtovým typem:
    <ul>
        <li>`UPSERT` - Vytvoření nebo aktualizace. Pokud již existovala data s touto identitou, byla aktualizována. Pokud ne, byla vytvořena.</li>
        <li>`REMOVE` - Odstranění — tzn. dříve existovala data s touto identitou a byla odstraněna.</li>
        <li>`TRANSACTION` - Omezující operace signalizující začátek transakce.</li>
    </ul>
  </dd>
  <dt>`SystemCaptureBodyUnion` `body`</dt>
  <dd>
    Tělo události. Tělo je union — pro oblast `ENGINE` obsahuje hodnotu `EngineMutationUnion`; pro oblast `HOST` obsahuje hodnotu `HostSystemEventUnion` (viz [Události systémového hostitele](#události-hostitelského-systému) níže).
  </dd>
</dl>

</LS>

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

### Události hostitelského systému

Kromě trvalých mutací engine může systémový CDC stream doručovat také **události hostitelského systému** — lokální, nereplikovatelné notifikace o aktuálním stavu katalogů na hostiteli, který je vygeneroval. Tyto události slouží k zachycení přechodů, které samotná mutace engine nedokáže popsat, zejména okamžiku, kdy se katalog automaticky upgradovaný při startu skutečně stane na tomto hostiteli použitelným (tedy mezi trvalou mutací upgradu ve WAL a instalací reference katalogu do in-memory live view).

Události hostitelského systému jsou reprezentovány třídou <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/HostSystemEvent.java</SourceClass> a tvoří uzavřenou rodinu se dvěma variantami:

<dl>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/HostSystemEvent.java</SourceClass>`.CatalogInstalledIntoLiveView`</dt>
  <dd>
    Vysílá se pokaždé, když je katalog (znovu)instalován do live view na hostiteli. Nese název katalogu a pozorovaný <SourceClass>evita_api/src/main/java/io/evitadb/api/CatalogState.java</SourceClass>, do kterého katalog přešel. Aktivní stavy (`ALIVE`, `WARMING_UP`) znamenají, že je katalog dotazovatelný; neaktivní ustálené stavy (`INACTIVE`, `OUT_OF_DATE`, `CORRUPTED`, `MISSING`) znamenají, že není dostupný přes externí API.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/HostSystemEvent.java</SourceClass>`.CatalogRemovedFromLiveView`</dt>
  <dd>
    Vysílá se, když je katalog odstraněn z live view na hostiteli (mazání, označení jako chybějící apod.). Nese název katalogu. Subscriber by měl zahodit jakýkoli cacheovaný endpoint nebo schéma pro daný katalog.
  </dd>
</dl>

#### Sémantika doručování

Události hostitelského systému mají záměrně užší garance doručení než mutace engine:

- **Pouze aktuální proud.** Události hostitele nejsou persistovány a **nejsou** přehrávány pro opožděné odběratele pomocí `sinceVersion`. Předplatné, které se otevře s minulou hodnotou `sinceVersion`, obdrží historické mutace engine od této verze dále, ale pouze události hostitele, které jsou vysílány od okamžiku připojení předplatného.
- **Lokální pro hostitele a nereplikovatelné.** Každý hostitel generuje své vlastní události hostitelského systému pro svůj vlastní live view; nejsou součástí WAL a nešíří se mezi replikami.
- **Volitelné.** Jak je popsáno výše v parametru `criteria`, události hostitelského systému jsou doručovány pouze tehdy, pokud předplatné explicitně zahrnuje oblast `HOST` ve svých kritériích. Výchozí nastavení (vynechaná kritéria) je pouze `ENGINE`.
- **Pouze pro korelaci — ne jako kurzor verze.** Každý záznam zachycující událost hostitelského systému uvádí verzi engine pozorovanou v okamžiku vyslání pro korelaci s okolním tokem mutací engine, ale vyslání události hostitelského systému neposouvá čítač verzí engine.

## Zachycení změn katalogu

<LS to="j,r">

<LS to="j">

Stream pro zachycení změn na úrovni katalogu přijímá <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCaptureRequest.java</SourceClass>
pro vytvoření [Java Flow Publisher](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Flow.Publisher.html). Jeden nebo více klientů se poté může přihlásit k tomuto publisheru, aby obdrželi 
instance <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCapture.java</SourceClass>
reprezentující změny provedené v katalogu.

</LS>
<LS to="r">

Stream pro zachycení změn na úrovni katalogu přijímá <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCaptureRequest.java</SourceClass>
pro vytvoření CDC streamu. Klienti poté obdrží
instance <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCapture.java</SourceClass>
reprezentující změny provedené v katalogu.

</LS>

Požadavek umožňuje specifikovat následující parametry:

<dl>
  <dt>long `sinceVersion` (volitelné)</dt>
  <dd>
    Verze katalogu (včetně), od které chcete začít přijímat změny. Pokud není zadáno, stream změn začne od další verze katalogu (tj. změny provedené v katalogu v budoucnosti).
  </dd>
  <dt>int `sinceIndex` (volitelné)</dt>
  <dd>
    Index mutace v rámci stejné transakce, od kterého chcete začít přijímat změny. Pokud není zadáno, stream změn začne od první mutace zadané verze. Index vám umožňuje přesně určit počáteční bod v případě, že jste již některé mutace dané verze zpracovali.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCaptureCriteria.java</SourceClass>[] `criteria` (volitelné)</dt>
  <dd>
    Pole kritérií, která určují, o jaké změny máte zájem. Pokud není zadáno, jsou zachyceny všechny změny. Pokud je zadáno více kritérií, stačí splnit alespoň jedno z nich (logika OR). Každé kritérium se skládá z:
    <ul>
        <li>`area` – oblast zachycení (<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/CaptureArea.java</SourceClass>)</li>
        <li>`site` – místo zachycení (<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/CaptureSite.java</SourceClass>) umožňující detailní filtrování</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCaptureContent.java</SourceClass> `content`</dt>
  <dd>
    Výčtový typ určující, zda klient požaduje detailní informace o každé mutaci, nebo pouze základní informaci, že došlo k určitému typu mutace. Výčtový typ má následující hodnoty:
    <ul>
        <li>`HEADER` – odesílá se pouze hlavička události</li>
        <li>`BODY` – odesílá se celé tělo mutace, která událost vyvolala</li>
    </ul>
  </dd>
</dl>

Události zachycení změn katalogu jsou reprezentovány instancemi <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCapture.java</SourceClass>, které obsahují následující informace:

<LS to="j">

<dl>
  <dt>long `version`</dt>
  <dd>
    Verze katalogu, ve které mutace nastala.
  </dd>
  <dt>int `index`</dt>
  <dd>
    Index mutace v rámci stejné transakce. Index `0` je vždy infrastrukturní mutace typu <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass>.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/CaptureArea.java</SourceClass> `area`</dt>
  <dd>
    Oblast operace, která byla provedena:
    <ul>
        <li>`SCHEMA` – jsou zachyceny změny ve schématu</li>
        <li>`DATA` – jsou zachyceny změny v datech</li>
        <li>`INFRASTRUCTURE` – infrastrukturní mutace, které nejsou ani schéma, ani data</li>
    </ul>
  </dd>
  <dt>String `entityType` (volitelné)</dt>
  <dd>
    Název typu entity, který byl ovlivněn operací. Toto pole je null, pokud je operace provedena přímo na schématu katalogu.
  </dd>
  <dt>Integer `entityPrimaryKey` (volitelné)</dt>
  <dd>
    Primární klíč entity, která byla ovlivněna operací. Přítomno pouze u operací v oblasti dat.
  </dd>
  <dt>`operation`</dt>
  <dd>
    Klasifikace mutace definovaná výčtovým typem:
    <ul>
        <li>`UPSERT` – Vytvoření nebo aktualizace. Pokud již data s touto identitou existovala, byla aktualizována. Pokud ne, byla vytvořena.</li>
        <li>`REMOVE` – Odstranění – tj. data s touto identitou existovala a byla odstraněna.</li>
        <li>`TRANSACTION` – Vymezující operace signalizující začátek transakce.</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/mutation/CatalogBoundMutation.java</SourceClass> `body` (volitelné)</dt>
  <dd>
    Volitelné tělo operace, pokud je požadováno nastavením <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCaptureContent.java</SourceClass>.
  </dd>
</dl>

</LS>
<LS to="r">

<dl>
  <dt>long `version`</dt>
  <dd>
    Verze katalogu, ve které mutace nastala.
  </dd>
  <dt>int `index`</dt>
  <dd>
    Index mutace v rámci stejné transakce. Index `0` je vždy infrastrukturní mutace typu <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass>.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/CaptureArea.java</SourceClass> `area`</dt>
  <dd>
    Oblast operace, která byla provedena:
    <ul>
        <li>`SCHEMA` – jsou zachyceny změny ve schématu</li>
        <li>`DATA` – jsou zachyceny změny v datech</li>
        <li>`INFRASTRUCTURE` – infrastrukturní mutace, které nejsou ani schéma, ani data</li>
    </ul>
  </dd>
  <dt>String `entityType` (volitelné)</dt>
  <dd>
    Název typu entity, který byl ovlivněn operací. Toto pole je null, pokud je operace provedena přímo na schématu katalogu.
  </dd>
  <dt>Integer `entityPrimaryKey` (volitelné)</dt>
  <dd>
    Primární klíč entity, která byla ovlivněna operací. Přítomno pouze u operací v oblasti dat.
  </dd>
  <dt>`operation`</dt>
  <dd>
    Klasifikace mutace definovaná výčtovým typem:
    <ul>
        <li>`UPSERT` – Vytvoření nebo aktualizace. Pokud již data s touto identitou existovala, byla aktualizována. Pokud ne, byla vytvořena.</li>
        <li>`REMOVE` – Odstranění – tj. data s touto identitou existovala a byla odstraněna.</li>
        <li>`TRANSACTION` – Vymezující operace signalizující začátek transakce.</li>
    </ul>
  </dd>
  <dt>`CatalogBoundMutationUnion` `body` (volitelné)</dt>
  <dd>
    Volitelné tělo operace, pokud je požadováno nastavením <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCaptureContent.java</SourceClass>.
  </dd>
</dl>

</LS>

### Záruky pořadí

V celém streamu zachycení změn jsou změny doručovány ve striktně monotónním pořadí verzí katalogu — nikdy nejsou doručeny ve špatném pořadí a nikdy nejsou přeskočeny, odpovídají pořadí, ve kterém byly transakce potvrzeny.

V rámci jedné transakce (jedna verze katalogu):

- Mutace, která vymezuje transakci — `operation = TRANSACTION`, vždy na `index = 0` — je vždy doručena jako první.
- `index` identifikuje jeden fyzický záznam mutace uvnitř transakce. Jedna mutace entity nebo schématu se může rozšířit do několika událostí zachycení — například upsert entity je zachycen jednou jako změna na úrovni entity a znovu pro každou změnu atributu, ceny nebo reference, kterou obsahuje — a všechny tyto události sdílejí stejný pár `(version, index)`. Považujte `(version, index)` za identifikátor jedné změny na úrovni entity nebo schématu spolu se vším, co z ní vychází, nikoli jednoho doručeného eventu.
- `index` je přiřazen před aplikací jakýchkoliv filtračních kritérií, takže zůstává stabilní bez ohledu na to, jak úzce filtrujete — obnovení od konkrétního `(version, index)` se chová stejně, ať už vaše kritéria odpovídají všemu, nebo jen jednomu atributu.

### Kontrola úplnosti

Protože jeden pár `(version, index)` může nést několik událostí zachycení, počítání doručených událostí vám neřekne, zda jste obdrželi vše, co transakce nebo mutace vytvořila. Místo toho to umožňují dva čítače:

- `TransactionMutation.mutationCount`, obsažený v těle zachycení s `operation = TRANSACTION`, je počet záznamů mutací na nejvyšší úrovni, které transakce obsahuje.
- velikost lokálního seznamu mutací obsaženého v těle zachycení na úrovni entity říká, kolik událostí zachycení sdílí daný pár `(version, index)` entity.

Obojí vyžaduje `content = BODY` — v režimu `HEADER` není k dispozici žádný z těchto čítačů.

Obojí je také pevnou vlastností *nefiltrované* transakce, nikoli toho, co vaše kritéria propustí. `mutationCount` je zapsán jednou, v době potvrzení, před tím, než se kdokoli přihlásí — stejná hodnota je předána každému odběrateli bez ohledu na jeho kritéria, takže vždy počítá všechny záznamy mutací na nejvyšší úrovni, které transakce vytvořila, nikdy jen ty, které odpovídají vašemu filtru. Totéž platí pro lokální seznam mutací entity. To znamená, že výše uvedená kontrola je spolehlivá pouze tehdy, když vaše kritéria nic nefiltrují — žádná oblast užší než všechny tři, a žádné omezení na úrovni místa jako `entityType`.

Filtr pouze na oblast `SCHEMA` nebo `DATA` to ještě komplikuje: také úplně odstraní událost hlavičky oblasti `INFRASTRUCTURE`, takže nemůžete ani přečíst `mutationCount`. Přidání explicitního kritéria `CaptureArea.INFRASTRUCTURE` (bez místa zachycení) do vašeho seznamu kritérií způsobí, že bude hlavička opět doručena — filtrování oblasti zůstává záměrně doslovné a neuděluje výjimky implicitně, takže toto je podporovaný způsob, jak se znovu přihlásit — ale tím pouze obnovíte možnost *číst* čítač. Samotný čítač však stále nebude odpovídat streamu, který jinak filtrujete (např. na jeden typ entity nebo jméno atributu), protože nikdy nebyl omezen na kritéria jednoho odběratele.

### Oblasti a místa zachycení

Katalogové CDC rozlišuje tři různé **oblasti zachycení**, které odpovídají různým typům operací:

#### Oblast zachycení schématu

Oblast zachycení schématu sleduje změny schématu katalogu a schémat entit. Zahrnuje operace jako:

- Vytváření, aktualizace nebo odstraňování schémat entit
- Úpravy atributů entit, referencí a definic přidružených dat
- Změny nastavení schématu na úrovni katalogu

Oblast schématu používá <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/SchemaSite.java</SourceClass> pro filtrování, což umožňuje zadat:

<dl>
  <dt>String `entityType` (volitelné)</dt>
  <dd>
    Filtrování podle konkrétního názvu typu entity. Pokud není zadáno, jsou zachyceny změny všech typů entit.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/Operation.java</SourceClass>[] `operation` (volitelné)</dt>
  <dd>
    Filtrování podle typu operace. Pokud není zadáno, jsou zachyceny všechny operace. Možné hodnoty:
    <ul>
      <li>`UPSERT` – Vytvoření nebo aktualizace</li>
      <li>`REMOVE` – Odstranění</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ContainerType.java</SourceClass>[] `containerType` (volitelné)</dt>
  <dd>
    Filtrování podle typu kontejneru. Pokud není zadáno, jsou zachyceny změny všech typů kontejnerů. Možné hodnoty:
    <ul>
      <li>`CATALOG` – Změny schématu na úrovni katalogu</li>
      <li>`ENTITY` – Změny schématu entity</li>
      <li>`ATTRIBUTE` – Změny schématu atributu</li>
      <li>`ASSOCIATED_DATA` – Změny schématu přidružených dat</li>
      <li>`PRICE` – Změny schématu ceny</li>
      <li>`REFERENCE` – Změny schématu reference</li>
    </ul>
  </dd>
</dl>

#### Oblast zachycení dat

Oblast zachycení dat sleduje změny dat entit v rámci katalogu. Zahrnuje operace jako:

- Vytváření, aktualizace nebo odstraňování entit
- Úpravy atributů entit, referencí a hodnot přidružených dat
- Aktualizace cen a hierarchického umístění

Oblast dat používá <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/DataSite.java</SourceClass> pro filtrování, což umožňuje zadat:

<dl>
  <dt>String `entityType` (volitelné)</dt>
  <dd>
    Filtrování podle konkrétního názvu typu entity. Pokud není zadáno, jsou zachyceny změny všech typů entit.
  </dd>
  <dt>Integer `entityPrimaryKey` (volitelné)</dt>
  <dd>
    Filtrování podle konkrétního primárního klíče entity. Pokud není zadáno, jsou zachyceny změny všech entit.
  </dd>
  <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/Operation.java</SourceClass>[] `operation` (volitelné)</dt>
  <dd>
    Filtrování podle typu operace. Pokud není zadáno, jsou zachyceny všechny operace. Možné hodnoty:
    <ul>
      <li>`UPSERT` – Vytvoření nebo aktualizace</li>
      <li>`REMOVE` – Odstranění</li>
    </ul>
  </dd>
  <dt><SourceClass>evita_common/src/main/java/io/evitadb/dataType/ContainerType.java</SourceClass>[] `containerType` (volitelné)</dt>
  <dd>
    Filtrování podle typu kontejneru. Pokud není zadáno, jsou zachyceny změny všech typů kontejnerů. Možné hodnoty:
    <ul>
      <li>`ENTITY` – Změny na úrovni entity</li>
      <li>`ATTRIBUTE` – Změny hodnoty atributu</li>
      <li>`ASSOCIATED_DATA` – Změny hodnoty přidružených dat</li>
      <li>`PRICE` – Změny ceny</li>
      <li>`REFERENCE` – Změny reference</li>
    </ul>
  </dd>
  <dt>String[] `containerName` (volitelné)</dt>
  <dd>
    Filtrování podle konkrétního názvu kontejneru (např. konkrétní název atributu jako `name`, `code`). Pokud není zadáno, jsou zachyceny změny všech kontejnerů.
  </dd>
</dl>

#### Oblast zachycení infrastruktury

Oblast zachycení infrastruktury sleduje transakční a další infrastrukturní mutace, které nespadají do kategorií schéma nebo data. Zahrnuje:

- Vymezující operace transakcí
- Operace na úrovni systému

Oblast infrastruktury nepoužívá žádné místo zachycení pro filtrování — aktuálně zachycuje všechny infrastrukturní mutace reprezentované <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/transaction/TransactionMutation.java</SourceClass>.

<dl>
  <dt>Žádné filtrační parametry</dt>
  <dd>
    Oblast infrastruktury zachycuje všechny transakční a systémové mutace bez jakýchkoli možností filtrování. Pro zachycení infrastrukturních mutací zadejte `CaptureArea.INFRASTRUCTURE` do vašich kritérií bez místa zachycení.
  </dd>
</dl>

Tato oblast existuje samostatně, protože hranice transakcí a systémové operace jsou ortogonální ke změnám schématu i dat a klienti mohou potřebovat sledovat hranice transakcí nezávisle pro správné seskupování událostí a zajištění konzistence.

### Jak nastavit nové zachycení změn katalogu

Nastavení zachycení změn katalogu se liší od zachycení změn engine v tom, že funguje na úrovni katalogu.

<LS to="j">

Nastavení se skládá z:

1. Otevření relace (read-only nebo read-write) ke katalogu
2. Zavolání `registerChangeCatalogCapture` s <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCaptureRequest.java</SourceClass>
3. Zpracování vráceného streamu událostí <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCapture.java</SourceClass>

Příklad získání historie změn katalogu v Javě:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java,/documentation/user/en/use/api/example/finalization-of-warmup-mode.java" langSpecificTabOnly local>

[Nastavení minimálního zachycení změn katalogu](/documentation/user/en/use/api/example/catalog-change-capture.java)

</SourceCodeTabs>

Níže naleznete také další užitečné příklady:

<Note type="info">

<NoteTitle toggles="true">

##### Získání vymezujících transakcí a změn pro všechny entity konkrétního typu

</NoteTitle>

Tento publisher doručí všechny vymezující transakce a všechny změny provedené na entitách typu `Product` počínaje
další verzí katalogu.

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java,/documentation/user/en/use/api/example/finalization-of-warmup-mode.java" langSpecificTabOnly local>

[Požadavek na změny na úrovni entity v transakčních blocích](/documentation/user/en/use/api/example/capture-entity-mutations-with-transaction.java)

</SourceCodeTabs>

</Note>

<Note type="info">

<NoteTitle toggles="true">

##### Získání změn pro atribut s názvem `quantityOnStock` konkrétní entity typu `Product`

</NoteTitle>

Následující publisher doručí všechny změny provedené na atributu `quantityOnStock` entity typu `Product` s primárním
klíčem `745` počínaje další verzí katalogu.

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java,/documentation/user/en/use/api/example/finalization-of-warmup-mode.java" langSpecificTabOnly local>

[Požadavek na změny na úrovni entity](/documentation/user/en/use/api/example/capture-attribute-mutation.java)

</SourceCodeTabs>

</Note>

</LS>
<LS to="r">

Stream pro zachycení změn na úrovni katalogu je dostupný v katalogovém API přes endpoint `/rest/{catalogName}/change-captures`.

Nastavení je velmi jednoduché:
1. otevřete WebSocket spojení odesláním `GET` požadavku s požadavkem na upgrade spojení
2. odešlete zprávu `connection_init` v rámci WebSocket spojení
3. odešlete zprávu `subscribe` v rámci WebSocket spojení s objektem `ChangeCatalogCaptureRequest` definujícím
   strategii filtrování (jak je specifikováno v
   [specifikaci WebSocket](/documentation/user/en/use/connectors/rest-over-websocket-protocol.md)).

CDC stream nyní bude klientovi zasílat objekty `ChangeCatalogCapture` zabalené do zpráv `next`.

Příklad získání historie změn katalogu v protokolu WebSocket pro REST:

<SourceAlternativeTabs variants="rest">

[Nastavení minimálního zachycení změn katalogu](/documentation/user/en/use/api/example/catalog-change-capture-rest.json)

</SourceAlternativeTabs>

Níže naleznete také další užitečné příklady:

<Note type="info">

<NoteTitle toggles="true">

##### Získání vymezujících transakcí a změn pro všechny entity konkrétního typu

</NoteTitle>

Tento subscription doručí všechny vymezující transakce a všechny změny provedené na entitách typu `Product` počínaje
další verzí katalogu.

<SourceAlternativeTabs variants="rest">

[Požadavek na změny na úrovni entity v transakčních blocích](/documentation/user/en/use/api/example/capture-entity-mutations-with-transaction-rest.json)

</SourceAlternativeTabs>

</Note>

<Note type="info">

<NoteTitle toggles="true">

##### Získání změn pro atribut s názvem `quantityOnStock` konkrétní entity typu `Product`

</NoteTitle>

Následující subscription doručí všechny změny provedené na atributu `quantityOnStock` entity typu `Product` s primárním
klíčem `745` počínaje další verzí katalogu.

<SourceAlternativeTabs variants="rest">

[Požadavek na změny na úrovni entity](/documentation/user/en/use/api/example/capture-attribute-mutation-rest.json)

</SourceAlternativeTabs>

</Note>

</LS>

<LS to="j">

### Získání historie mutací

Kromě přihlášení k živému streamu budoucích změn může relace také požádat o omezený,
jednorázový pohled na mutace již zaznamenané v write-ahead logu katalogu, prostřednictvím
<SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass>:

<dl>
  <dt>`getMutationsHistoryForward`</dt>
  <dd>
    Vrací `Stream<ChangeCatalogCapture>` v chronologickém pořadí vpřed. `sinceVersion` je
    inkluzivní dolní mez; pokud není nastavena, stream začíná u nejstarší verze známé v
    historii mutací katalogu.
  </dd>
  <dt>`getMutationsHistoryReversed`</dt>
  <dd>
    Vrací stejný typ streamu v opačném chronologickém pořadí. `sinceVersion` je tentokrát inkluzivní
    horní mez; pokud není nastavena, stream začíná u nejnověji potvrzené verze.
  </dd>
</dl>

Obě metody přijímají stejný <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/cdc/ChangeCatalogCaptureRequest.java</SourceClass>
jako pro otevření živého odběru a obě vrací uzavíratelný stream — vždy jej spotřebujte uvnitř
try-with-resources bloku, protože po dobu otevření drží otevřený file handle na write-ahead log.

Tato funkce je dostupná pouze přes Java driver — neexistuje ekvivalent v GraphQL ani REST.

Směr ovlivňuje víc než jen pořadí iterace:

- Hlavička transakce (`operation = TRANSACTION`, `index = 0`) je doručena jako první bez ohledu na
  směr.
- `index` je směr-stabilní fyzická pozice — stejný záznam mutace má stejný index bez ohledu na směr
  čtení — ale v rámci transakce není v opačném směru monotónní: zpětný stream navštíví `index = 0`,
  pak `mutationCount` odpočítává dolů k `1`.
- Vnořené pořadí mutace entity a lokálních mutací sdílejících její `(version, index)`
  se také obrací: vpřed je nejprve doručena událost na úrovni entity, pak její lokální mutace; zpětně
  jsou nejprve doručeny lokální mutace, pak událost na úrovni entity.

<Note type="warning">

Zastavení uprostřed zpracování zachycení jedné transakce — například po určité velikosti dávky — vás může nechat pouze s hlavičkou bez jejího obsahu, nebo s obsahem bez toho, abyste někdy viděli hlavičku. [Kontrola úplnosti](#kontrola-úplnosti) výše vám něco řekne až poté, co jste skutečně viděli zachycení hlavičky transakce.

</Note>

### Často kladené dotazy ohledně mechanismu zachycení změn

<Note type="question">

<NoteTitle toggles="true">

##### Musím si uchovávat instanci publisheru?

</NoteTitle>

Ne — můžete ji nechat garbage collectnout. Publisher je pouze továrna na vytváření subscriberů. Jakmile je subscriber vytvořen a přihlášen, udržuje si svůj vlastní stav a spojení s enginem. Reference na subscriber je uchovávána v instanci evitaDB (client), což brání jeho garbage collectnutí, dokud je instance aktivní.

Reference na publisher musíte uchovávat pouze v případě, že plánujete přihlásit více subscriberů.

</Note>

<Note type="question">

<NoteTitle toggles="true">

##### Potřebuji platnou relaci pro přihlášení k zachycení změn katalogu?

</NoteTitle>

Ne, relaci potřebujete pouze pro vytvoření publisheru. Jakmile je publisher vytvořen, subscribeři se k němu mohou přihlásit i bez aktivní relace. Publisher interně otevře dedikovanou relaci pro každého subscriber, pokud není subscription vytvořeno v rámci aktivní relace.

</Note>

<Note type="question">

<NoteTitle toggles="true">

##### Co když se k publisheru přihlásím později — od jakého bodu budu přijímat změny?

</NoteTitle>

Publisher zmrazí parametry CDC požadavku (včetně počáteční verze) v okamžiku svého vytvoření. Pokud požadavek obsahuje počáteční verzi katalogu, každý subscriber bude přijímat změny počínaje verzí uvedenou v CDC požadavku použitém při vytvoření publisheru, bez ohledu na to, kdy se subscriber přihlásí. Pokud požadavek neobsahuje počáteční verzi, každý subscriber bude přijímat změny počínaje další verzí katalogu v okamžiku své subscription.

</Note>

<Note type="question">

<NoteTitle toggles="true">

##### Jak správně uzavřít a uvolnit prostředky?

</NoteTitle>

Pokud vaše třída subscriber implementuje rozhraní [AutoCloseable](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/AutoCloseable.html), můžete se spolehnout na to, že instance evitaDB (client) ji automaticky uzavře při uzavření klientské instance. Close bude automaticky zavoláno při zrušení subscription nebo při uzavření klientské instance.

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