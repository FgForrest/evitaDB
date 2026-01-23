---
commit: '62220555030a727bfde88523ab0b9ea080c880c1'
---
# WebSocket protokol pro REST API

Tento protokol představuje komunikační schéma pro WebSocket endpointy definované v REST API systému evitaDB.
Je založen na [GraphQL Transport WS protokolu](https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md).

## Nomenklatura

- **Socket** je hlavní WebSocket komunikační kanál mezi _serverem_ a _klientem_
- **Connection** je spojení **v rámci již navázaného socketu**, které popisuje „spojení“, přes které budou komunikovány požadavky na operace

## Komunikace

WebSocket subprotokol pro tuto specifikaci je: `rest-transport-ws`.

Zprávy jsou reprezentovány pomocí JSON struktury a před odesláním po síti jsou serializovány do řetězce. Jsou obousměrné, což znamená, že jak server, tak klient musí dodržovat specifikovanou strukturu zpráv.

**Všechny** zprávy obsahují pole `type`, které určuje akci, kterou tato zpráva popisuje.

Zprávy odpovídající operacím musí obsahovat pole `id`, které slouží k jednoznačné identifikaci odpovědí serveru a jejich propojení s požadavky klienta.

Více operací identifikovaných různými ID může být aktivních současně a jejich zprávy mohou být v rámci spojení prokládány.

Server může socket (odpojit klienta) kdykoliv uzavřít. Událost uzavření vyvolaná serverem slouží k popisu fatální chyby klientovi.

Klient uzavírá socket a spojení odesláním události `1000: Normal Closure` serveru, čímž indikuje normální ukončení.

## Typy zpráv

### `ConnectionInit`

Směr: **Klient -> Server**

Indikuje, že klient chce navázat spojení v rámci existujícího socketu. Toto spojení **není** samotný WebSocket komunikační kanál, ale spíše rámec v jeho rámci, kterým klient žádá server o povolení budoucích požadavků na operace.

Server musí obdržet inicializační zprávu spojení v rámci povoleného čekacího času, který je určen parametrem `connectionInitWaitTimeout` při nastavení serveru. Pokud klient nepožádá o spojení v rámci povoleného timeoutu, server uzavře socket s událostí: `4408: Connection initialisation timeout`.

Pokud server obdrží více než jednu zprávu `ConnectionInit` v daném okamžiku, uzavře socket s událostí `4429: Too many initialisation requests`.

Pokud chce server spojení odmítnout, například během autentizace, doporučuje se uzavřít socket s událostí `4403: Forbidden`.

```typescript
interface ConnectionInitMessage {
  type: 'connection_init';
  payload?: Record<string, unknown> | null;
}
```

### `ConnectionAck`

Směr: **Server -> Klient**

Očekávaná odpověď na zprávu `ConnectionInit` od klienta, která potvrzuje úspěšné navázání spojení se serverem.

Server může využít volitelné pole `payload` k přenosu dalších detailů o spojení.

```typescript
interface ConnectionAckMessage {
  type: 'connection_ack';
  payload?: Record<string, unknown> | null;
}
```

Klient je nyní **připraven** žádat o operace typu subscription.

### `Ping`

Směr: **obousměrný**

Užitečné pro detekci selhaných spojení, zobrazování metrik latence nebo jiné typy síťového testování.

Na přijatou zprávu `Ping` musí protistrana co nejdříve odpovědět zprávou `Pong`.

Zprávu `Ping` lze odeslat kdykoliv v rámci navázaného socketu.

Volitelné pole `payload` lze využít k přenosu dalších detailů o pingu.

```typescript
interface PingMessage {
  type: 'ping';
  payload?: Record<string, unknown> | null;
}
```

### `Pong`

Směr: **obousměrný**

Odpověď na zprávu `Ping`. Musí být odeslána ihned po přijetí zprávy `Ping`.

Zprávu `Pong` lze odeslat kdykoliv v rámci navázaného socketu. Navíc může být zpráva `Pong` odeslána i nevyžádaně jako jednosměrný heartbeat.

Volitelné pole `payload` lze využít k přenosu dalších detailů o pongu.

```typescript
interface PongMessage {
  type: 'pong';
  payload?: Record<string, unknown> | null;
}
```

### `Subscribe`

Směr: **Klient -> Server**

Žádá o nové předplatné definované WS endpointem s parametry uvedenými v poli `payload`. Tato zpráva obsahuje unikátní pole ID (doporučujeme použít UUID), které propojí publikované zprávy s operací požadovanou touto zprávou.

Pokud již existuje aktivní odběratel pro operaci se stejným ID, bez ohledu na typ operace, server **musí** socket okamžitě uzavřít s událostí `4409: Subscriber for <unique-operation-id> already exists`.

Server musí sledovat ID pouze po dobu, kdy je předplatné aktivní. Jakmile klient operaci dokončí, může toto ID znovu použít.

```typescript
interface SubscribeMessage {
  id: '<unique-operation-id>';
  type: 'subscribe';
  payload: Record<string, unknown>;
}
```

Provádění operací je povoleno **pouze** po potvrzení spojení serverem prostřednictvím zprávy `ConnectionAck`. Pokud spojení není potvrzeno, socket bude okamžitě uzavřen s událostí `4401: Unauthorized`.

### `Next`

Směr: **Server -> Klient**

Výsledek(y) provedení operace ze zdrojového streamu vytvořeného vazbou na zprávu `Subscribe`. Po odeslání všech výsledků následuje zpráva `Complete`, která indikuje dokončení streamu.

```typescript
interface NextMessage {
  id: '<unique-operation-id>';
  type: 'next';
  payload: {
    data: Record<string, unknown> | null;
    error: { message: string } | null;
  }
}
```

### `Error`

Směr: **Server -> Klient**

Chyba(y) při provádění operace v reakci na zprávu `Subscribe`. Může nastat _před_ zahájením provádění, obvykle kvůli validačním chybám, nebo _během_ provádění požadavku. Tato zpráva ukončuje operaci a žádné další zprávy již nebudou odeslány.

```typescript
interface ErrorMessage {
  id: '<unique-operation-id>';
  type: 'error';
  payload: { 
    error: { message: string } | null;
  };
}
```

### `Complete`

Směr: **obousměrný**

- **Server -> Klient** indikuje, že požadovaná operace byla dokončena. Pokud server odeslal zprávu `Error` vztahující se k původní zprávě `Subscribe`, zpráva `Complete` již nebude odeslána.

- **Klient -> Server** indikuje, že klient přestal naslouchat a chce předplatné ukončit. Žádné další události týkající se původního předplatného by již neměly být odesílány. I když klient odešle zprávu `Complete` pro _operaci s jedním výsledkem_ dříve, než je vyřešena, výsledek by již neměl být odeslán.

Poznámka: Asynchronní povaha plně duplexního spojení znamená, že klient může odeslat zprávu `Complete` serveru i v době, kdy jsou zprávy na cestě ke klientovi, nebo když server již operaci sám dokončil (pomocí zprávy `Error` nebo `Complete`). Klient i server proto musí být připraveni přijímat (a ignorovat) zprávy pro operace, které již považují za dokončené.

```typescript
interface CompleteMessage {
  id: '<unique-operation-id>';
  type: 'complete';
}
```

### Neplatná zpráva

Směr: **obousměrný**

Přijetí zprávy typu nebo formátu, který není v tomto dokumentu specifikován, povede k **okamžitému** uzavření socketu s událostí `4400: <error-message>`. `<error-message>` může být stručně popisné, proč je přijatá zpráva neplatná.

Přijetí zprávy (jiné než `Subscribe`) s ID, které patří operaci, jež již byla dříve dokončena, není považováno za chybu. Je přípustné jednoduše ignorovat všechna _neznámá_ ID bez uzavření spojení.

## Příklady

Pro přehlednost následující příklady demonstrují komunikační protokol.

<h3 id="successful-connection-initialisation">Úspěšná inicializace spojení</h3>

1. _Klient_ odešle požadavek na WebSocket handshake se subprotokolem: `rest-transport-ws`
1. _Server_ přijme handshake a naváže WebSocket komunikační kanál (který nazýváme „socket“)
1. _Klient_ okamžitě odešle zprávu `ConnectionInit` a volitelně poskytne payload dle dohody se serverem
1. _Server_ ověří požadavek na inicializaci spojení a odešle klientovi zprávu `ConnectionAck` při úspěšném navázání spojení
1. _Klient_ obdržel potvrzovací zprávu a je nyní připraven žádat o provedení operací

### Timeout inicializace spojení

1. _Klient_ odešle požadavek na WebSocket handshake se subprotokolem: `rest-transport-ws`
1. _Server_ přijme handshake a naváže WebSocket komunikační kanál (který nazýváme „socket“)
1. _Klient_ neodešle zprávu `ConnectionInit`
1. _Server_ čeká na zprávu `ConnectionInit` po dobu určenou parametrem `connectionInitWaitTimeout`
1. _Serveru_ uplynula čekací doba
1. _Server_ uzavře socket odesláním události `4408: Connection initialisation timeout`