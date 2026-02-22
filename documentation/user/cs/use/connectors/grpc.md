---
title: gRPC
perex: gRPC API byla navržena tak, aby poskytovala velmi výkonný a jednotný způsob ovládání databáze evitaDB z různých programovacích jazyků pomocí stávající Java API evitaDB.
date: '26.7.2023'
author: Ing. Tomáš Pozler
preferredLang: java
commit: '339a72a997423f553b529a6219130bed4f47582e'
translated: true
---
<LS to="e,g,r">
Tato kapitola popisuje gRPC protokol pro evitaDB a týká se pouze ovladačů pro Javu nebo C#. Pokud vás zajímají
detaily implementace gRPC, změňte prosím preferovaný jazyk v pravém horním rohu.
</LS>
<LS to="j,c">
Hlavní myšlenkou návrhu bylo definovat univerzální komunikační protokol, který se řídí návrhem [Java API](https://github.com/FgForrest/evitaDB/tree/dev/evita_api/src/main/java/io/evitadb/api).
Na rozdíl od [REST](rest.md) a [GraphQL](graphql.md) není toto API určeno pro přímé použití koncovými uživateli / vývojáři, ale
primárně jako stavební blok pro ovladače evitaDB nebo podobné nástroje vytvořené pro spotřebitele databáze.

## Struktura API

S evitaDB se manipuluje pomocí kontraktů vystavených v [evita_api](https://github.com/FgForrest/evitaDB/tree/dev/evita_api/src/main/java/io/evitadb/api),
konkrétně rozhraními <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass> a
<SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass>.
Navrhované gRPC API si klade za cíl definovat odpovídající služby s co nejmenšími odchylkami, ale v některých případech
bylo nutné model přizpůsobit možnostem gRPC. V návrhu nebylo možné přímo modelovat generika,
dědičnost nebo lambda funkce, ale tyto problémy bylo možné obejít úpravou definovaných modelů za cenu redundantních informací.

Není také možné volat metody na instancích objektů umístěných na serveru, jako jsou sessiony, které se používají
pro většinu operací s daty. Tyto problémy však lze vyřešit implementací
knihovny ovladače s bohatým API. Použití těchto metod na objektu session bylo nahrazeno nutností
specifikovat identifikátor uživatelem vytvořené session, který pak musí být předán v metadatech dotazu pro všechny metody
založené na rozhraní EvitaSessionContract.

Jedním ze způsobů, jak tento proces zjednodušit, je uložit ID session do sdíleného paměťového prostoru (příklad třídy
<SourceClass>evita_external_api/evita_external_api_grpc/shared/src/main/java/io/evitadb/externalApi/grpc/interceptor/ClientSessionInterceptor.java</SourceClass>)
a nastavit jej do metadat při každém volání metody pomocí <SourceClass>evita_external_api/evita_external_api_grpc/shared/src/main/java/io/evitadb/externalApi/grpc/interceptor/ClientSessionInterceptor.java</SourceClass>.
Kromě `sessionId` lze v metadatech nastavit další identifikátory pro [pozorovatelnost](../../operate/observe.md).
Konkrétně se jedná o parametry `clientId` a `clientVersion`, jejichž nastavení slouží k přidání informací o
klientovi, který v dané instanci databázi používá (například to může být název aplikace využívající evitaDB)
a případně další identifikátor pro každý dotaz nebo provedenou metodu. Nastavení a použití těchto parametrů je
zcela volitelné.

gRPC funguje na principu vzdáleného volání metod (RPC), tj. klient používá ve svém kódu metody definované v protobuf službách
a jejich implementace je volána na serveru, kde je požadavek zpracován a odpověď je odeslána zpět
klientovi. Pro výše zmíněné kontrakty <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass>
a <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass> existují odpovídající
služby ve formě <SourceClass>evita_external_api/evita_external_api_grpc/server/src/main/java/io/evitadb/externalApi/grpc/services/EvitaService.java</SourceClass>
a <SourceClass>evita_external_api/evita_external_api_grpc/server/src/main/java/io/evitadb/externalApi/grpc/services/EvitaSessionService.java</SourceClass>
s obsaženými metodami podporujícími stejnou funkcionalitu jako základní [Java API](https://github.com/FgForrest/evitaDB/tree/dev/evita_api/src/main/java/io/evitadb/api), ale méně pohodlným způsobem
kvůli omezením protokolu gRPC.

### Protocol buffers

Protocol buffers jsou způsob, jak definovat strukturu dat pomocí zpráv využívajících podporované datové typy a struktury.
Nejsou náhradou za formáty JSON nebo XML používané pro serializaci dat, ale spíše jazykově a platformně nezávislou alternativou.

Hlavní výhodou použití protocol buffers je možnost reprezentovat data strukturovaným způsobem. Objekty,
které odpovídají definovaným typům zpráv, lze serializovat do proudu bajtů pomocí příslušné knihovny.

V kombinaci s knihovnou gRPC je proces serializace ještě jednodušší. Nemusíte přímo manipulovat s protobuf soubory,
knihovna gRPC se postará o kompilaci protobuf souborů do nativních tříd a automaticky zajistí serializaci objektů.

Všechny *protobuf* soubory definující gRPC protokol najdete [ve složce META-INF](https://github.com/FgForrest/evitaDB/tree/dev/evita_external_api/evita_external_api_grpc/shared/src/main/resources/META-INF/io/evitadb/externalApi/grpc).
Níže je příklad návrhu <SourceClass>evita_external_api/evita_external_api_grpc/server/src/main/java/io/evitadb/externalApi/grpc/services/EvitaSessionService.java</SourceClass>
a několik vybraných procedur v protobuf souboru <SourceClass>evita_external_api/evita_external_api_grpc/shared/src/main/resources/META-INF/io/evitadb/externalApi/grpc/GrpcEvitaSessionAPI.proto</SourceClass>:

```protobuf
service EvitaSessionService {
  rpc GoLiveAndClose(google.protobuf.Empty) returns (GrpcGoLiveAndCloseResponse);
  rpc Query(GrpcQueryRequest) returns (GrpcQueryResponse);
}
```

spolu s definicí použitých vstupních/výstupních zpráv
(kromě typu [Empty](https://protobuf.dev/reference/protobuf/google.protobuf/#empty), který pochází z
[sady standardních typů zpráv](https://protobuf.dev/reference/protobuf/google.protobuf/), jež jsou součástí
protokolu a jsou tedy zahrnuty v knihovně):

```protobuf
message GrpcGoLiveAndCloseResponse {
  bool success = 1;
}

message GrpcQueryRequest {
  string query = 1;
  repeated GrpcQueryParam positionalQueryParams = 2;
  map<string, GrpcQueryParam> namedQueryParams = 3;
}

message GrpcQueryResponse {
  GrpcDataChunk recordPage = 1;
  GrpcExtraResults extraResults = 2;
}
```

<Note type="warning">

Všechny protobuf soubory definující gRPC API protokol by měly být vždy synchronizovány mezi klientem a serverem.
Ačkoliv gRPC poskytuje určité mechanismy pro zpětnou kompatibilitu, není zaručeno, že klient bude schopen
se serverem komunikovat, pokud protokol není totožný. Nezměněné RPC by měly fungovat i v případě rozdílného protokolu,
jakékoliv změny nebo rozšíření však fungovat nebudou.

Java klient, který poskytujeme, používá stejný verzovací schéma jako serverová knihovna. Verzi serveru lze snadno
zjistit voláním metody `ServerStatus` ze služby `GrpcEvitaManagementAPI`. Měli byste
se snažit udržovat hlavní/vedlejší verzi klientské a serverové knihovny stejnou, ale patch verze se může lišit.

</Note>

## Připojení k serveru a vytváření session

Pro připojení k serveru je potřeba vytvořit kanál a stub pro službu, kterou chcete použít. Můžete využít jednoduchý
gRPC java klient nebo výkonnější implementaci klienta jako [Armeria](https://armeria.dev/), která poskytuje další
funkce jako pooling spojení, vyvažování zátěže a další. Protože Armerii používáme i na straně serveru, doporučujeme
použít také jejich klienta.

Nejprve musíte vytvořit evita službu, ze které můžete vytvořit session. Poté musíte vytvořit session
službu, která je klíčová pro všechny operace s daty a odesílání dotazů. Session je vždy vázána na
konkrétní katalog a je potřeba ji dekorovat <SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/interceptor/ClientSessionInterceptor.java</SourceClass>, který bude propagovat session id na server při každém volání přes
session službu v gRPC metadatech.

<SourceCodeTabs>

[Příklad vytvoření Armeria gRPC klienta a připojení k serveru](/documentation/user/en/use/connectors/examples/grpc-create-session.java)

</SourceCodeTabs>

## Dotazování do databáze

Jednou z výzev návrhu bylo najít způsob, jak reprezentovat databázové dotazy pomocí možností protobuf. I když je možné
definovat typ zprávy, který reprezentuje dotaz a předat serializovaný dotaz v binární podobě, rozhodli jsme se použít
parametrizovanou řetězcovou formu, kde lze parametry nahradit symbolem `?` a seznam parametrů lze předat s tímto dotazem.
Alternativou je použití pojmenovaných parametrů s prefixem `@`, které se používají s unikátním názvem v dotazu a jsou
také vloženy do mapy jako klíče, kde každý pojmenovaný parametr má odpovídající hodnotu. Tato forma je ve vývojářské
komunitě dobře známá a používá ji mnoho databázových ovladačů – konkrétně ve formě JDBC statementů nebo
[named queries](https://www.baeldung.com/spring-jdbc-jdbctemplate#2-queries-with-named-parameters) zavedených frameworkem Spring.

V této podobě uživatel předá dotaz serveru, který zpracuje řetězcovou formu a vytvoří objekt reprezentující
požadovaný dotaz, připravený k provedení databází.

<SourceCodeTabs requires="/documentation/user/en/use/connectors/examples/grpc-create-session.java">

[Příklad vytvoření gRPC kanálu a služby nad ním a provedení dotazu](/documentation/user/en/use/connectors/examples/grpc-client-query-call.java)

</SourceCodeTabs>

Příklad využívá metodu `convertQueryParam` ze třídy <SourceClass>evita_external_api/evita_external_api_grpc/shared/src/main/java/io/evitadb/externalApi/grpc/query/QueryConverter.java</SourceClass>. Podobnou metodu je nutné implementovat v cílovém jazyce gRPC klienta, aby bylo možné registrovat
vstupní parametry konkrétního gRPC datového typu pro dotaz.

Primárním účelem evitaDB je provádění dotazů. Jak je však vidět na výše uvedeném příkladu, práce s databází
tímto způsobem není příliš uživatelsky přívětivá. Bohužel v současné době neexistuje podpora pro intellisense ani
validaci dotazů a zatím jsme nevyvinuli žádné IDE nástroje, které by tuto nevýhodu řešily.

<Note type="info">

<NoteTitle toggles="false">

##### Existuje lepší způsob volání dotazů přes gRPC API?
</NoteTitle>

Ano, existuje, a tento způsob použití v našich integračních testech neuvidíte. Místo toho pracujeme s dotazem v jeho
původní typově bezpečné podobě:

<SourceCodeTabs requires="/documentation/user/en/use/connectors/examples/grpc-create-session.java">

[Příklad alternativního volání gRPC dotazu](/documentation/user/en/use/connectors/examples/grpc-optimized-client-query-call.java)

</SourceCodeTabs>

Tento přístup však vyžaduje, aby byl model dotazovacího jazyka implementován v cílovém jazyce gRPC, což
vyžaduje značné úsilí. Vždy doporučujeme používat vhodné klientské ovladače, pokud jsou pro cílový jazyk dostupné,
protože to posouvá vývojářskou zkušenost na vyšší úroveň.

</Note>

## Doporučené použití

Pro maximální využití gRPC API důrazně doporučujeme použít některý z námi implementovaných ovladačů nebo si vytvořit
vlastní nástroj pro usnadnění dotazování. Výrazně tím zlepšíte svou zkušenost s evitaDB. Přímé použití gRPC API
vývojáři nebylo zamýšleno a bylo navrženo spíše jako rozhraní "stroj-stroj".

Pokud začínáte vyvíjet ovladač pro svůj preferovaný jazyk, doporučujeme prostudovat ovladač
<SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass>,
který obsahuje mnoho inspirativních řešení pro lepší výkon, například jak řešit pooling kanálů nebo jak vytvářet a
používat cache schémat.

Důležité je také nezapomenout na TLS certifikáty, které je nutné správně nastavit jak na straně serveru,
tak na straně klienta. gRPC komunikuje přes HTTP/2 a data jsou přenášena přes TCP spojení, přičemž se obecně doporučuje
používat TLS pro šifrování obousměrné komunikace. Naše API to vyžaduje, proto doporučujeme přečíst si
[instrukce](../../operate/tls.md) týkající se TLS a nastavit jej na serveru, pokud je to potřeba.

Pokud zvažujete vývoj vlastního ovladače, neváhejte se na nás obrátit pro podporu a poradenství v průběhu celého
procesu. Rádi vám poskytneme potřebnou zpětnou vazbu, doporučení a asistenci, abychom zajistili bezproblémové
fungování vašeho ovladače. Na vašem úspěchu nám záleží a jsme připraveni s vámi úzce spolupracovat, aby byla integrace
vašeho ovladače s naším API úspěšná a přínosná. Neváhejte nás kontaktovat. Jsme tu pro vás!

### Doporučené nástroje

Je důležité si uvědomit, že gRPC je navrženo pro použití z jakéhokoliv podporovaného programovacího jazyka pomocí
tříd generovaných z protobuf, ideálně s přívětivým IDE, které poskytuje například intellisense, takže jeho použití je
poměrně přímočaré a na webu je k dispozici mnoho zdrojů a návodů.
Pro testovací účely bez psaní kódu, tj. pro vyzkoušení dostupných služeb a volání jejich procedur,
je možné použít klasické nástroje pro testování API jako [Postman](https://www.postman.com/) nebo
[Insomnia](https://insomnia.rest/), které již podporují komunikaci s gRPC API.
Pokud však s gRPC začínáte, doporučujeme nástroj [BloomRPC](https://github.com/bloomrpc/bloomrpc), i když je
již neudržovaný, protože je intuitivnější a zaměřený přímo na tuto technologii. Alternativně existuje mnoho skvělých
alternativ pro testování gRPC API, které najdete [zde](https://github.com/grpc-ecosystem/awesome-grpc).

### Doporučené knihovny

Všechny oficiálně udržované knihovny najdete na webu [gRPC](https://grpc.io), kde si můžete vybrat knihovnu pro
váš programovací jazyk. Spolu s knihovnou je potřeba stáhnout také vhodný [kompilátor protokolu](https://grpc.io/docs/protoc-installation/),
který není přímo součástí žádné z knihoven. Přichází ve formě pluginů, nástrojů nebo zcela samostatných
knihoven – záleží na tom, jaký jazyk používáte.
</LS>