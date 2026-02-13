---
title: REST
perex: Protokol REST (Representational State Transfer) je standardizovaný přístup k vytváření webových služeb, které využívají HTTP metody pro vytváření, čtení, aktualizaci a mazání dat. Protokol je navržen kolem zdrojů, což může být jakýkoli objekt, data nebo služba, ke kterým má klient přístup. Díky své jednoduchosti, škálovatelnosti a výkonu je REST nejpopulárnějším protokolem pro API a je široce využíván v cloudových službách, mobilních službách a sociálních sítích.
date: '24.3.2023'
author: Lukáš Hornych
preferredLang: rest
commit: '151ed5c62c63f3e087500e5f2d76d7ffad686420'
---
<LS to="e,j,c,g">
Tato kapitola popisuje REST protokol pro evitaDB a nedává smysl pro jiné jazyky. Pokud vás zajímají
podrobnosti implementace REST, změňte prosím preferovaný jazyk v pravém horním rohu.
</LS>
<LS to="r">
[REST](https://restfulapi.net/) API s [OpenAPI schématem](https://swagger.io/specification/v3/) v evitaDB bylo
vyvinuto tak, aby uživatelům a vývojářům umožnilo snadno dotazovat doménově specifická data z evitaDB prostřednictvím univerzálního a dobře známého
API standardu, který REST API poskytují.

Hlavní myšlenkou naší implementace REST API je, že [OpenAPI schéma](https://swagger.io/specification/v3/) je dynamicky generováno na základě
[interních schémat](/documentation/user/en/use/schema.md) evitaDB. To znamená, že uživatelé vidí pouze ta data, která
mohou skutečně získat. Například pokud jste v evitaDB definovali entitu `product` s atributy `code` a
`name`, OpenAPI schéma bude obsahovat pouze tyto dva atributy v modelu entity `Product` s datovými typy
odpovídajícími těm, které jsou specifikovány v evitaDB, místo nějakých obecných typů.

## REST API instance

<UsedTerms>
    <h4>Použité pojmy</h4>
	<dl>
		<dt>catalog API</dt>
		<dd>
			REST API instance, která poskytuje způsoby, jak dotazovat a aktualizovat skutečná data (typicky entity a související data)
			jednoho katalogu, stejně jako způsoby, jak získat a upravit interní strukturu jednoho katalogu.

			Vzor URL: `/rest/{catalog-name}`
		</dd>
		<dt>system API</dt>
		<dd>
			REST API instance, která poskytuje způsoby, jak spravovat samotnou evitaDB.

			Vzor URL: `/rest/system`
		</dd>
	</dl>
</UsedTerms>

Neexistuje jedna REST API instance pro celou instanci evitaDB. Místo toho má každý [katalog](/documentation/user/en/use/data-model.md#catalog) evitaDB
své vlastní REST API na své vlastní URL s daty pouze z tohoto konkrétního katalogu.
Navíc existuje ještě jedna REST API instance, která je vyhrazena pro administraci evitaDB
(například vytváření nových katalogů, mazání existujících katalogů), nazývaná <Term>system API</Term>.

Každá URL instance REST API začíná `/rest`, následovanou URL-formátovaným názvem katalogu pro konkrétní
catalog API, nebo vyhrazeným klíčovým slovem `system` pro výše zmíněné <Term>system API</Term>.
[OpenAPI schéma](https://swagger.io/specification/v3/) každé REST API instance si můžete stáhnout zavoláním HTTP požadavku `GET`
na tuto základní URL, která pak vypíše všechny dostupné endpointy.

URL těchto REST API instancí s výše zmíněnými základními URL jsou dále rozšířeny o konkrétní zdroje.
V případě <Term>system API</Term> jsou to typicky katalogy. V případě <Term>catalog API</Term>
jsou zde zdroje pro jednotlivé [kolekce](/documentation/user/en/use/data-model.md#collection)
a jejich akce.

<Note type="example">

<NoteTitle toggles="true">

##### Jaké URL budou zpřístupněny pro sadu definovaných katalogů?
</NoteTitle>

Předpokládejme, že máte katalogy `fashion` a `electronics`. evitaDB by zpřístupnila následující REST API instance, každou
s vlastním relevantním [OpenAPI schématem](https://swagger.io/specification/v3/):

- `/rest/fashion` - <Term>catalog API</Term> pro dotazování nebo aktualizaci skutečných dat katalogu `fashion` nebo jeho struktury
- `/rest/electronic` - <Term>catalog API</Term> pro dotazování nebo aktualizaci skutečných dat katalogu `electronic` nebo jeho struktury
- `/rest/system` - <Term>system API</Term> pro správu samotné evitaDB

</Note>

## Struktura API

### Struktura catalog API

Jedno <Term>catalog API</Term> pro jeden katalog obsahuje pouze několik typů endpointů pro získání a aktualizaci dat nebo jeho
interního schématu, ale většina z nich je „duplikována“ pro
každou [kolekci](/documentation/user/en/use/data-model.md#collection) v rámci tohoto katalogu.
Také existuje sada endpointů pro získání a aktualizaci schématu nadřazeného [katalogu](/documentation/user/en/use/data-model.md#catalog).
Každý endpoint pak přijímá argumenty a vrací data specifická pro danou kolekci a [její schéma](/documentation/user/en/use/schema.md#entity).

Kromě uživatelsky definovaných kolekcí existuje v REST API pro každý katalog „virtuální“ zjednodušená kolekce s názvem `entity`,
která umožňuje uživatelům získávat entity podle globálních atributů bez znalosti cílové kolekce. Kolekce `entity`
má však k dispozici pouze omezenou sadu endpointů.

#### Model

Schéma se skládá především z:

- dynamických typů objektů entit
    - samostatné typy objektů pro každou kolekci na základě interního schématu evitaDB
- dynamických typů objektů schématu
- vstupních typů pro dotazovací omezení
    - kontejnery pro dotazování entit na základě definic omezení evitaDB a interního schématu evitaDB
- společných pomocných typů
    - výčty, mutace atd.

##### Znovupoužitelnost

Ačkoli je většina typů generována na základě uživatelsky definovaného schématu bez vzájemných vazeb, existují oblasti,
kde můžeme automaticky vypočítat znovupoužitelné typy rozhraní. To může výrazně pomoci klientskému kódu
vytvářet znovupoužitelné komponenty.

Znovupoužitelné typy rozhraní lze nalézt v:

**Datových blocích**. Existují dvě základní implementace: stránkované seznamy
a pásové seznamy. Obvykle entity a reference implementují své vlastní rozšíření těchto rozhraní.

<Note type="info">

Zkoumáme další místa, kde bychom mohli generovat znovupoužitelné typy rozhraní na základě reálných případů použití.
Nechceme schéma API zbytečně komplikovat jen kvůli této možnosti.

</Note>

### Struktura system API

Na <Term>system API</Term> není nic zvláštního, pouze sada základních endpointů.

## Dotazovací jazyk

evitaDB je dodávána s vlastním [dotazovacím jazykem](/documentation/user/en/query/basics.md), pro který má naše REST API vlastní fasádu.
Hlavní rozdíl mezi těmito dvěma je, že původní jazyk evitaDB má obecnou sadu omezení a
nezajímá se o konkrétní datovou strukturu [kolekce](/documentation/user/en/use/data-model.md#collection), kdežto
REST verze má stejná omezení, ale přizpůsobená podle datové struktury [kolekce](/documentation/user/en/use/data-model.md#collection),
aby poskytla konkrétní dostupná omezení pro definovanou datovou strukturu.

Tato vlastní verze dotazovacího jazyka je možná, protože v našem REST API je dotazovací jazyk dynamicky generován
na základě [interních schémat kolekcí](/documentation/user/en/use/schema.md#entity), aby zobrazoval pouze omezení, která
lze skutečně použít pro dotazování dat (což se také mění v závislosti na kontextu vnořených omezení). To také poskytuje argumenty omezení s datovými typy, které odpovídají
interním datům. To pomáhá se samo-dokumentací, protože nemusíte nutně znát
doménový model, protože [OpenAPI](https://swagger.io/specification/v3/) schéma lze použít k automatickému doplňování dostupných omezení.

### Syntaxe dotazu a omezení

<MDInclude>[Syntaxe dotazu a omezení](/documentation/user/en/use/connectors/assets/dynamic-api-query-language-syntax.md)</MDInclude>

## Doporučené použití

Naším cílem je navrhnout REST API tak, aby odpovídala [RESTful best practices](https://restfulapi.net/), nicméně to není
vždy jednoduché, zejména pokud je v pozadí poměrně obecná databáze, a proto zde existují některé ne zcela RESTful endpointy.
Nicméně REST API lze používat jako jakékoli jiné běžné REST API, tj. se
standardními nástroji. Níže však uvádíme naše doporučení na nástroje atd., které používáme v evitaDB.

### Doporučená vývojová prostředí (IDE)

Pro desktopové IDE k testování a prozkoumávání REST API můžete použít [Insomnia](https://insomnia.rest/) nebo [Postman](https://www.postman.com/).
Pro generování dokumentace z [OpenAPI schématu](https://swagger.io/specification/v3/) doporučujeme buď
nástroj [Redocly](https://redocly.com/docs/cli/commands/preview-docs/) nebo oficiální [Swagger Editor](https://github.com/swagger-api/swagger-editor),
který dokonce umožňuje generovat klienty pro váš programovací jazyk.

### Doporučené klientské knihovny

Můžete použít jakýkoli základní HTTP klient, který váš programovací jazyk podporuje. Pokud hledáte něco více, existují
generátory kódu, které mohou vygenerovat celý typovaný klient ve vašem preferovaném jazyce z OpenAPI schémat evitaDB.
Oficiální způsob, jak to udělat, je použití [Swagger Codegen](https://swagger.io/tools/swagger-codegen/).
</LS>