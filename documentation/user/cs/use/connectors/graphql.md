---
title: GraphQL
perex: GraphQL je open-source dotazovací a manipulační jazyk pro API, který poskytuje silnou alternativu k REST tím, že umožňuje klientům přesně specifikovat, jaká data potřebují, a tím snižuje nadměrné nebo nedostatečné načítání dat. Vyvinutý společností Facebook v roce 2012 a uvolněný jako open-source v roce 2015 umožňuje deklarativní získávání dat, kdy si klient může vyžádat přesně to, co potřebuje, a dostane právě to.
date: '21.3.2023'
author: Lukáš Hornych
preferredLang: graphql
commit: cef96d8320d36c91c100c5dfc9c45020b5a7ad0d
---
<LS to="e,j,c,r">
Tato kapitola popisuje GraphQL protokol pro evitaDB a nedává smysl pro jiné jazyky. Pokud vás zajímají
detaily implementace GraphQL, změňte prosím preferovaný jazyk v pravém horním rohu.
</LS>
<LS to="g">
[GraphQL](https://graphql.org/) API bylo vyvinuto tak, aby uživatelům umožnilo snadno dotazovat doménově specifická data z
evitaDB s vysokou mírou přizpůsobení dotazů a se samo-dokumentací, kterou GraphQL API poskytuje.

Hlavní myšlenkou naší implementace GraphQL API je, že schéma API je dynamicky generováno na základě
[interních schémat](/documentation/user/en/use/schema.md) evitaDB. To znamená, že uživatelé vidí pouze ta data, která
mohou skutečně získat. Nevidí žádné obecné řetězce názvů. Například, pokud jste v evitaDB definovali
entitu `Product` s atributy `code` a `name`, GraphQL API vám umožní dotazovat pouze tyto dva atributy
takto:

```graphql
getProduct {
    attributes {
        code
        name
    }
}
```

a navíc budou mít datové typy, které odpovídají těm, které jsou specifikovány v evitaDB, a ne nějaké obecné.

## Instance GraphQL API

<UsedTerms>
    <h4>Použité pojmy</h4>
    <dl>
        <dt>catalog data API</dt>
        <dd>
			Instance GraphQL API, která poskytuje způsoby dotazování a aktualizace skutečných dat (typicky entit a souvisejících dat)
            jednoho katalogu.

	        Vzor URL: `/gql/{catalog-name}`
        </dd>
        <dt>catalog schema API</dt>
        <dd>
            Instance GraphQL API, která poskytuje způsoby získání a úpravy vnitřní struktury jednoho katalogu.

	        Vzor URL: `/gql/{catalog-name}/schema`
        </dd>
        <dt>system API</dt>
        <dd>
            Instance GraphQL API, která poskytuje způsoby správy samotné evitaDB.

            Vzor URL: `/gql/system`
        </dd>
    </dl>
</UsedTerms>

Neexistuje jedna jediná instance GraphQL API pro celou instanci evitaDB. Místo toho má každý [katalog](/documentation/user/en/use/data-model.md#catalog)
v evitaDB své vlastní GraphQL API (ve skutečnosti má každý katalog dvě instance GraphQL
API, ale o tom později) na své vlastní URL pouze s daty z daného katalogu.
Kromě toho existuje ještě jedna instance GraphQL API, která je vyhrazena pro administraci evitaDB
(např. vytváření nových katalogů, odstraňování existujících katalogů), nazývaná <Term>system API</Term>.

Každá URL instance GraphQL API začíná `/gql`, následovanou názvem katalogu ve formátu URL pro konkrétní
API katalogu, nebo rezervovaným klíčovým slovem `system` pro výše zmíněné <Term>system API</Term>. Pro každý katalog existuje
<Term>catalog data API</Term> umístěné na základní URL `/gql/{catalog name}` a <Term>catalog schema API</Term> umístěné na
URL `/gql/{catalog name}/schema`. <Term>catalog data API</Term> slouží k získávání a aktualizaci skutečných dat daného katalogu.
<Term>catalog schema API</Term> je spíše "introspekční" API, protože umožňuje uživatelům zobrazit a upravit interní schémata evitaDB,
což následně ovlivňuje schéma GraphQL API.

Každá instance GraphQL API podporuje pouze HTTP metodu `POST` pro provádění dotazů a mutací.

### Získání schématu GraphQL

Každá instance GraphQL API podporuje standardní [introspekční možnosti](https://graphql.org/learn/introspection/) pro rekonstrukci schématu GraphQL.
Kromě toho každá instance umožňuje získat rekonstruované schéma GraphQL ve formátu DSL pomocí
HTTP požadavku `GET` na URL instance. Například pro získání schématu GraphQL katalogu `fashion` můžete provést
následující HTTP požadavek:
```http request
GET /gql/fashion
```

<Note type="info">

<NoteTitle toggles="true">

##### Význam metody `GET` ve specifikaci GraphQL
</NoteTitle>

Jsme si vědomi, že tato implementace není správným způsobem použití metody `GET` dle specifikace GraphQL.
Nicméně jsme se rozhodli pro tuto implementaci, protože stejný přístup používáme pro získání OpenAPI specifikací REST API.
Také neplánujeme podporovat provádění dotazů a mutací pomocí metody `GET`, protože to zahrnuje zbytečné
escapování dotazovacího řetězce a podobně.

</Note>

<Note type="example">

<NoteTitle toggles="true">

##### Jaké URL budou vystaveny pro sadu definovaných katalogů?
</NoteTitle>

Předpokládejme, že máte katalogy `fashion` a `electronics`. evitaDB by vystavila následující instance GraphQL API, každou
s vlastním relevantním schématem GraphQL:

- `/gql/fashion` - <Term>catalog data API</Term> pro dotazování nebo aktualizaci skutečných dat katalogu `fashion`
- `/gql/fashion/schema` - <Term>catalog schema API</Term> pro zobrazení a úpravu vnitřní struktury katalogu `fashion`
- `/gql/electronic` - <Term>catalog data API</Term> pro dotazování nebo aktualizaci skutečných dat katalogu `electronic`
- `/gql/electronic/schema` - <Term>catalog schema API</Term> pro zobrazení a úpravu vnitřní struktury katalogu `electronic`
- `/gql/system` - <Term>system API</Term> pro správu samotné evitaDB

</Note>

## Struktura API

### Struktura catalog data API

Jedno <Term>catalog data API</Term> pro jeden katalog obsahuje jen několik typů dotazů a mutací, ale většina z nich je "duplikována" pro
každou [kolekci](/documentation/user/en/use/data-model.md#collection) v rámci tohoto katalogu.
Každý dotaz nebo mutace pak přijímá argumenty a vrací data specifická pro danou kolekci a [její schéma](/documentation/user/en/use/schema.md#entity).

Kromě uživatelsky definovaných kolekcí existuje v GraphQL API pro každý katalog "virtuální" zjednodušená kolekce s názvem `entity`,
která umožňuje uživatelům získávat entity podle globálních atributů bez znalosti cílové kolekce. Nicméně "kolekce" `entity`
má k dispozici pouze omezenou sadu dotazů.

### Struktura catalog schema API

Jedno <Term>catalog schema API</Term> pro jeden katalog obsahuje pouze základní dotazy a mutace pro každou
[kolekci](/documentation/user/en/use/data-model.md#collection) a nadřazený [katalog](/documentation/user/en/use/data-model.md#catalog)
pro získání nebo změnu jeho schématu.

### Struktura system API

Na <Term>system API</Term> není nic speciálního, pouze sada základních dotazů a mutací.

## Dotazovací jazyk

evitaDB je dodávána s vlastním [dotazovacím jazykem](/documentation/user/en/query/basics.md), pro který má naše GraphQL API vlastní rozhraní.
Hlavní rozdíl mezi těmito dvěma je, že původní jazyk evitaDB má obecnou sadu omezení a nezajímá se o konkrétní
strukturu dat [kolekce](/documentation/user/en/use/data-model.md#collection), zatímco
verze GraphQL má stejná omezení, ale přizpůsobená na základě struktury dat [kolekce](/documentation/user/en/use/data-model.md#collection),
aby poskytla konkrétní dostupná omezení pro definovanou datovou strukturu.

Tato vlastní verze dotazovacího jazyka je možná, protože v našem GraphQL API je schéma dotazovacího jazyka dynamicky generováno
na základě [interních schémat kolekcí](/documentation/user/en/use/schema.md#entity), aby zobrazovalo pouze omezení, která
lze skutečně použít pro dotazování dat (což se také mění podle kontextu vnořených omezení). To také poskytuje argumenty omezení s datovými typy, které odpovídají
interním datům. To pomáhá se samo-dokumentací, protože nemusíte nutně znát
doménový model, jelikož většina GraphQL IDE automaticky doplňuje dostupná omezení ze schématu GraphQL API.

### Syntaxe dotazu a omezení

<MDInclude>[Syntaxe dotazu a omezení](/documentation/user/en/use/connectors/assets/dynamic-api-query-language-syntax.md)</MDInclude>

## Doporučené použití

Naše GraphQL API jsou v souladu s oficiální specifikací, takže je lze používat jako jakékoli jiné běžné GraphQL API, tj. se
standardními nástroji. Níže však uvádíme naše doporučení na nástroje atd., které používáme v evitaDB.

### Doporučená IDE

Vyvinuli jsme vlastní GUI nástroj s názvem [evitaLab](https://evitadb.io/blog/09-our-new-web-client-evitalab), který podporuje GraphQL s užitečnými nástroji (např. vizualizace dat).
Má také další užitečné nástroje pro prozkoumávání instancí evitaDB, nejen pro použití GraphQL API.
Proto je to naše doporučená volba IDE pro naše API.

Pokud však chcete použít obecný GraphQL nástroj, máme doporučení i pro to.
Během vývoje jsme narazili a vyzkoušeli několik nástrojů pro konzumaci GraphQL API, ale jen několik z nich můžeme doporučit.

Pro desktopové IDE k testování a prozkoumávání GraphQL API se velmi osvědčil klient [Altair](https://altairgraphql.dev/). Je to
skvělý desktopový klient pro GraphQL API s výborným doplňováním kódu a pěkným průzkumníkem schématu API.
Můžete také použít obecnější desktopový HTTP klient jako [Insomnia](https://insomnia.rest/), který také nabízí GraphQL
nástroje s průzkumníkem schématu API, i když omezeně. Lze použít i [Postman](https://www.postman.com/).
Obvykle můžete dokonce použít GraphQL pluginy ve svém kódovacím IDE, např. [IntelliJ IDEA](https://www.jetbrains.com/idea/)
nabízí [GraphQL plugin](https://plugins.jetbrains.com/plugin/8097-graphql), který se pěkně integruje do vašeho workflow,
i když bez dokumentace API jako nabízejí jiné samostatné IDE.

Pokud hledáte webového klienta, kterého můžete integrovat do své aplikace, existuje oficiální lehký
[GraphiQL](https://github.com/graphql/graphiql), který poskytuje všechny základní nástroje, které můžete potřebovat.

Základní myšlenkou použití IDE je nejprve získat schéma GraphQL API z jedné z výše zmíněných URL, které jsou
vystaveny evitaDB. Poté prozkoumat schéma API pomocí dokumentace/průzkumníka schématu IDE a začít psát dotaz nebo mutaci, kterou odešlete na server.
V případě např. [Altair](https://altairgraphql.dev/) nemusíte ani prozkoumávat schéma API ručně, protože
Altair, stejně jako mnoho dalších, má v editoru doplňování kódu na základě získaného schématu API.

### Doporučené klientské knihovny

Všechny dostupné knihovny najdete na [oficiální webové stránce GraphQL](https://graphql.org/code/#language-support), kde si můžete
vybrat pro svůj vlastní klientský jazyk. Některé dokonce dokáží generovat třídy/typy na základě schématu API, které můžete použít ve
své kódové základně.
</LS>