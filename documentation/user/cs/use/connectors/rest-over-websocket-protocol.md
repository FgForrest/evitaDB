---
translated: 'true'
commit: fd07cee44cf344113bd19e9c9ef7d17f27a13fe2
---
# WebSocket protokol pro REST API

Tento protokol představuje komunikační schéma pro WebSocket endpointy definované v REST API evitaDB.
Je založen na [GraphQL Transport WS protokolu](https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md).

## Nomenklatura

- **Socket** je hlavní WebSocket komunikační kanál mezi _serverem_ a _klientem_
- **Connection** je spojení **uvnitř navázaného socketu** popisující "spojení", skrze které budou komunikovány požadavky na operace

## Komunikace

WebSocket subprotokol pro tuto specifikaci je: `rest-transport-ws`.

Zprávy jsou reprezentovány pomocí JSON struktury a jsou před odesláním po síti serializovány do řetězce. Jsou obousměrné, což znamená, že jak server, tak klient musí dodržovat stanovenou strukturu zpráv.

**Všechny** zprávy obsahují pole `type`, které určuje akci, kterou tato zpráva popisuje.

Zprávy odpovídající operacím musí obsahovat pole `id`, které slouží k jednoznačné identifikaci odpovědí serveru a propojení s požadavky klienta.

Více operací identifikovaných různými ID může být aktivních současně a jejich zprávy mohou být na spojení prokládány.

Server může socket kdykoliv uzavřít (odpojit klienta). Událost uzavření vyvolaná serverem slouží k popsání fatální chyby klientovi.

Klient uzavírá socket a spojení tím, že serveru odešle událost uzavření `1000: Normal Closure`, čímž indikuje normální ukončení.

## Typy zpráv

### `ConnectionInit`

Směr: **Klient -> Server**

Indikuje, že klient chce navázat spojení v rámci existujícího socketu. Toto spojení **není** samotný WebSocket komunikační kanál, ale spíše rámec v něm, který žádá server o povolení budoucích požadavků na operace.

**Poznámka:** aktuální implementace serveru nevynucuje speciální časový limit pro přijetí této zprávy — neexistuje parametr `connectionInitWaitTimeout` a není vyvolána událost uzavření `4408`. Klient, který nikdy nepošle `ConnectionInit`, udržuje socket otevřený, dokud jej sám neuzavře nebo není podkladové spojení jinak ukončeno.

Pokud server obdrží více než jednu zprávu `ConnectionInit` v daném okamžiku, server uzavře socket s událostí `4429: Too many initialisation requests`.

Pokud chce server spojení odmítnout, například během autentizace, doporučuje se uzavřít socket s kódem `4403: Forbidden`.

```typescript
interface ConnectionInitMessage {
  type: 'connection_init';
  payload?: Record<string, unknown> | null;
}
```

### `ConnectionAck`

Směr: **Server -> Klient**

Očekávaná odpověď na zprávu `ConnectionInit` od klienta potvrzující úspěšné navázání spojení se serverem.

Server může volitelné pole `payload` použít k přenosu dalších detailů o spojení.

```typescript
interface ConnectionAckMessage {
  type: 'connection_ack';
  payload?: Record<string, unknown> | null;
}
```

Klient je nyní **připraven** žádat o operace typu subscription.

### `Ping`

Směr: **obousměrný**

Užitečné pro detekci selhaných spojení, zobrazování metrik latence nebo jiné typy síťového sondování.

Na přijatou zprávu `Ping` musí být co nejdříve zaslána odpověď `Pong`.

Zpráva `Ping` může být odeslána kdykoliv v rámci navázaného socketu.

Volitelné pole `payload` může být použito k přenosu dalších detailů o pingu.

```typescript
interface PingMessage {
  type: 'ping';
  payload?: Record<string, unknown> | null;
}
```

### `Pong`

Směr: **obousměrný**

Odpověď na zprávu `Ping`. Musí být odeslána ihned po přijetí zprávy `Ping`.

Zpráva `Pong` může být odeslána kdykoliv v rámci navázaného socketu. Navíc může být zpráva `Pong` odeslána i nevyžádaně jako jednosměrný heartbeat.

Volitelné pole `payload` může být použito k přenosu dalších detailů o pongu.

```typescript
interface PongMessage {
  type: 'pong';
  payload?: Record<string, unknown> | null;
}
```

### `Subscribe`

Směr: **Klient -> Server**

Žádá o nové předplatné definované WS endpointem s parametry uvedenými v `payload`. Tato zpráva poskytuje unikátní pole ID (doporučujeme použít UUID), které propojí publikované zprávy s operací požadovanou touto zprávou.

Pokud již existuje aktivní odběratel pro operaci odpovídající zadanému ID, bez ohledu na typ operace, server **musí** socket okamžitě uzavřít s událostí `4409: Subscriber for <unique-operation-id> already exists`.

Server si musí pamatovat ID pouze po dobu, kdy je předplatné aktivní. Jakmile klient operaci dokončí, může toto ID znovu použít.

```typescript
interface SubscribeMessage {
  id: '<unique-operation-id>';
  type: 'subscribe';
  payload: Record<string, unknown>;
}
```

Provádění operací je povoleno **pouze** poté, co server potvrdil spojení zprávou `ConnectionAck`, pokud spojení není potvrzeno, socket bude okamžitě uzavřen s událostí `4401: Unauthorized`.

### `Next`

Směr: **Server -> Klient**

Výsledek(y) provedení operace ze zdrojového streamu vytvořeného navazující zprávou `Subscribe`. Po odeslání všech výsledků bude následovat zpráva `Complete`, která indikuje dokončení streamu.

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

Chyba(y) při provádění operace v reakci na zprávu `Subscribe`. Může nastat _před_ zahájením provádění, obvykle kvůli validačním chybám, nebo _během_ provádění požadavku. Tato zpráva ukončuje operaci a žádné další zprávy již nebudou zaslány.

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

- **Klient -> Server** indikuje, že klient přestal naslouchat a chce dokončit předplatné. Žádné další události vztahující se k původnímu předplatnému by již neměly být odesílány. I když klient odešle zprávu `Complete` pro _operaci s jedním výsledkem_ dříve, než je vyřešena, výsledek by již neměl být odeslán.

Poznámka: Asynchronní povaha plně duplexního spojení znamená, že klient může odeslat zprávu `Complete` serveru i v případě, že jsou zprávy na cestě ke klientovi, nebo když server již operaci sám dokončil (pomocí zprávy `Error` nebo `Complete`). Klient i server proto musí být připraveni přijímat (a ignorovat) zprávy pro operace, které již považují za dokončené.

```typescript
interface CompleteMessage {
  id: '<unique-operation-id>';
  type: 'complete';
}
```

### Neplatná zpráva

Směr: **obousměrný**

Přijetí zprávy typu nebo formátu, který není v tomto dokumentu specifikován, povede k **okamžitému** uzavření socketu s událostí `4400: <error-message>`. `<error-message>` může být stručně popisné, proč je přijatá zpráva neplatná.

Přijetí zprávy (jiné než `Subscribe`) s ID, které patří operaci, která již byla dokončena, není považováno za chybu. Je přípustné jednoduše ignorovat všechna _neznámá_ ID bez uzavření spojení.

## Příklady

Pro větší přehlednost následující příklady demonstrují komunikační protokol.

<h3 id="successful-connection-initialisation">Úspěšná inicializace spojení</h3>

1. _Klient_ odešle požadavek na WebSocket handshake se subprotokolem: `rest-transport-ws`
1. _Server_ přijme handshake a naváže WebSocket komunikační kanál (který nazýváme "socket")
1. _Klient_ ihned odešle zprávu `ConnectionInit` a volitelně poskytne payload dle dohody se serverem
1. _Server_ ověří požadavek na inicializaci spojení a v případě úspěšného spojení odešle klientovi zprávu `ConnectionAck`
1. _Klient_ obdrží potvrzovací zprávu a je nyní připraven žádat o provedení operací