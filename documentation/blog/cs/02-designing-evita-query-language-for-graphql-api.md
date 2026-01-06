---
title: Návrh dotazovacího jazyka Evita pro GraphQL API
perex: V evitaDB (stejně jako v mnoha dalších databázích) musíte pro získání jakýchkoli dat nějakým způsobem určit, o která data máte zájem. Jazyk GraphQL je však specifický a vyžaduje specifickou syntaxi.
date: '12.1.2022'
author: Lukáš Hornych
motive: ../en/assets/images/02-designing-evita-query-language-for-graphql-api.png
commit: '652bbcf668cc8c46d8e413ebc422fb7f5c455e65'
---
Sada těchto otázek se nazývá _dotaz_ (_query_). Každý _dotaz_ obsahuje několik otázek nebo nějaké nápovědy pro filtrování, řazení,
vrácení nebo formátování požadovaných dat. Tyto nazýváme _omezeními_ (_constraints_). Máme 4 základní typy _omezení_: _head_, _filter_,
_order_ a _require_. _Head_ omezení určují některá metadata, například: v jaké kolekci entit bude dotaz vyhledávat. _Filter_ omezení jednoduše filtrují entity podle definovaných podmínek. _Order_ omezení řadí entity podle jejich
vlastností (atributů, cen atd.). Nakonec _require_ omezení určují, jaká data budou ve výstupu:
budou to pouze entity? Jak bohaté tyto entity budou? Budou zde i další data jako souhrn faset, nadřazené
entity a podobně?

## Původní Java Query Language

Protože je evitaDB embedovatelná, vytvořili jsme původně dotazovací jazyk pouze v Javě pomocí základních POJO objektů a statických továrních
metod pro snadnější vytváření jednotlivých omezení. Tato omezení lze vnořovat do kontejnerů omezení, aby bylo možné
reprezentovat složitější dotazy.

Tímto jsme umožnili použití široké škály různých podmínek a jejich kombinací a umožnili jsme Java vývojářům používajícím
embedovanou verzi evitaDB i nám samotným testovat evitaDB typově bezpečným způsobem s podporou dokončování kódu.

## Dotazovací jazyk pro externí API

Bohužel tento přístup v Javě nelze použít s externími API jako _GraphQL_, _REST_ nebo _gRPC_. Proto jsme potřebovali
jiný způsob deklarace _dotazů_. Prvním pokusem bylo vytvořit
[DSL](https://en.wikipedia.org/wiki/Domain-specific_language) s parserem, který by kopíroval návrh Java _omezení_,
ale bylo by možné jej parsovat z libovolného řetězce. Toho jsme dosáhli pomocí
knihovny [ANTLR4](https://github.com/antlr/antlr4), kterou jsme použili k definici
našeho [DSL](https://en.wikipedia.org/wiki/Domain-specific_language), ze kterého byl vygenerován lexer a parser. Díky
tomuto parseru jsme byli schopni parsovat _dotaz_ z libovolného řetězce z jakéhokoliv API, i když mu chyběla typová bezpečnost a dokončování kódu
(nebyl čas vytvořit vlastní plugin nebo [LSP](https://en.wikipedia.org/wiki/Language_Server_Protocol)
pro validaci syntaxe a autokompletaci v IDE). Použili jsme jej pouze pro _gRPC_ API, protože _gRPC_ neumožňuje
jednoduché objekty s generikami a libovolnými parametry jako Java. Ale _GraphQL_ a _REST_ API pracují s JSON objekty,
takže jsme chtěli přijít s něčím trochu jiným, co by lépe zapadalo do jazyka JSON a mohlo by být
potenciálně podpořeno _GraphQL_ nebo _REST_ schématem (na rozdíl od prostého řetězcového dotazu).

## Dotazovací jazyk pro GraphQL API

Při tvorbě _GraphQL_ API nás nadchlo dokončování kódu a dokumentace přímo při psaní _GraphQL_ dotazů a také to, jak moc lze přizpůsobit formu vracených dat. To nám vnuklo myšlenku použít naše
interní _evitaDB_ schémata pro katalogy a entity k vytvoření mechanismu, který by generoval celé _GraphQL_ schéma
z našich interních schémat. To by přineslo „automatizovanou“ dokumentaci a umožnilo inteligentní dokončování kódu, které by
uživatele vedlo, co a jak dotazovat, a pomáhalo by jim vyhnout se chybám v dotazu. Ale tím jsme neskončili, chtěli jsme také
vzít náš dotazovací jazyk a vygenerovat _GraphQL_ schéma i pro něj, aby jej bylo možné používat s dokončováním kódu stejně jako
zbytek _GraphQL_ dotazů, jak bylo zmíněno výše.

### Naše požadavky

Abychom podpořili všechny funkce našeho původního dotazovacího jazyka, chtěli jsme:

* aby _GraphQL_ dotazovací jazyk byl podobný původnímu _Java_ dotazovacímu jazyku
* mít možnost definovat klasifikátory pro každé _omezení_ (pro lokalizaci dat)
* mít možnost definovat hodnoty pro porovnání
* mít možnost definovat podřízená _omezení_ uvnitř nadřazených _omezení_
* mít možnost použít jak jedno _omezení_, tak pole více _omezení_
* mít možnost kombinovat všechny výše uvedené možnosti

Kromě toho jsme chtěli využít sílu _GraphQL_ schématu a editorů a rozšířit ji o následující kritéria:

* zobrazit pouze ta omezení, která dávají smysl pro konkrétní kolekci entit
* ve vnořených omezeních zobrazit pouze ta omezení, která dávají smysl v daném kontextu
* místo spíše obecných omezení, kde se jako argumenty vkládá klasifikátor a libovolná porovnatelná hodnota, generovat
  konkrétní verze těchto obecných omezení na základě schémat entit, aby bylo možné klientovi přesněji sdělit, jaká data a datové typy
  může dotazovat

### Inspirace

Začali jsme výzkumem, s čím přišli ostatní návrháři databází v této oblasti. Našli jsme několik
článků a dokumentací o pokusech o vytvoření
takových [DSL](https://en.wikipedia.org/wiki/Domain-specific_language).

Následující příklady se nám příliš nelíbily, protože tímto způsobem editor nemůže klientovi nabídnout žádné dokončování kódu a psaní dotazu se speciálními znaky je nepohodlné.

* [https://www.linkedin.com/pulse/building-your-own-query-language-my-first-career-project-batra/](https://www.linkedin.com/pulse/building-your-own-query-language-my-first-career-project-batra/)
* [https://www.elastic.co/guide/en/elasticsearch/reference/current/query-filter-context.html](https://www.elastic.co/guide/en/elasticsearch/reference/current/query-filter-context.html)

Pak jsme narazili na jiné příklady, které se nám líbily pro jejich výstižnost a možnost dokončování kódu.

* [https://nordicapis.com/review-of-groq-a-new-json-query-language/](https://nordicapis.com/review-of-groq-a-new-json-query-language/)
* [https://github.com/clue/json-query-language/blob/master/SYNTAX.md](https://github.com/clue/json-query-language/blob/master/SYNTAX.md)
* [https://www.edgedb.com/docs/graphql/graphql](https://www.edgedb.com/docs/graphql/graphql)

### Náš přístup

Hlavně jsme se inspirovali přístupem _EdgeDB_, protože také generovali _GraphQL_ schéma pro dotazování ze
svého interního databázového schématu. Co se nám na několika těchto přístupech příliš nelíbilo, byla definice podmínek
vnořená uvnitř objektu s dalšími podmínkami s implicitní _AND_ podmínkou mezi nimi. To vytváří
zbytečné komplikace pro vývojáře, kteří musí psát spoustu složených závorek pro definici jednoduchých _omezení_ a komplikuje
zadávání více podmínek pro stejná data v _OR_ podmínkách (no, nekomplikuje, ale museli byste to zabalit do
dalšího objektu). Proto jsme přišli s myšlenkou tyto podmínky zkombinovat s klíčem a vytvořit složený klíč,
který obsahuje lokalizátor dat a podmínku:

```
{attributeName}{condition} -> codeEquals
```

Hodnota tohoto složeného klíče by pak byla jednoduše porovnatelná hodnota (nebo vnořená podřízená omezení) v nějakém podporovaném datovém
typu (v tomto případě datový typ atributu `code` v našem schématu entity). To by také umožnilo mít více výrazů
ve stejném nadřazeném objektu s různými podmínkami. Ale nebyli jsme první, kdo na to přišel.
[Článek o jazyce GORQ](https://nordicapis.com/review-of-groq-a-new-json-query-language/) ukazuje podobnou
_GraphQL_ syntaxi.

Bohužel na rozdíl od _EdgeDB_, kde existují pouze typy objektů a jejich pole, a tedy dotazovací jazyk
musí řešit pouze „obecná“ pole a typy objektů, _evitaDB_ obsahuje více typů dat v entitách, které lze
dotazovat. Například každá entita může mít _atributy_, _ceny_, _reference_, _hierarchické reference_, _fasety_ a tak
dále. Každý typ dat má svá vlastní _omezení_ a můžete dokonce dotazovat uvnitř některých z nich pomocí dalších _omezení_. Navíc každá entita může podporovat pouze některé z těchto typů podle svých dat.

Abychom snadno rozlišili mezi jednotlivými typy dat entity a nemuseli duplikovat podmínky pro více typů
dat při psaní dotazu, přišli jsme s prefixy pro _omezení_. Každý prefix reprezentuje, na jaký typ dat může
omezení působit:

* _generic_ – obecné omezení, obvykle nějaký wrapper jako _and_, _or_ nebo _not_
* _entity_ – zpracovává vlastnosti přímo přístupné z entity, například _primární klíč_
* _attribute_ – může působit na hodnoty atributů entity
* _associatedData_ – může působit na hodnoty přidružených dat entity
* _price_ – může působit na ceny entity
* _reference_ – může působit na reference entity
* _hierarchy_ – může působit na hierarchická data entity (hierarchická data mohou být i referencována z jiných
  entit)
* _facet_ – může působit na referencované fasety entity

Navíc jsme se rozhodli, že _generic_ omezení nebudou používat explicitní prefixy pro lepší čitelnost a některá
omezení nebudou potřebovat žádný klasifikátor. To nás dovedlo ke třem formátům složených klíčů, které podporujeme a používáme:

```
{condition} -> and (pouze pro obecná omezení)
{propertyType}{condition} -> hierarchyWithinSelf (v tomto případě je klasifikátor použité hierarchie implicitně 
definován zbytkem dotazu)
{propertyType}{classifier}{condition} -> attributeCodeEquals (klíč se všemi metadaty)
```

Kompletní jednoduché omezení by vypadalo například takto:

```json
attributeCodeEquals: "iphone7s"
```

```json
entityPrimaryKeyInSet: [10, 20]
```

#### Omezení JSON

To vše je způsobeno tím, že JSON objekty jsou _velmi_ omezené a neumožňují vytvářet pojmenované
konstruktory nebo alespoň tovární metody, které by nám při parsování dotazu řekly, s jakým omezením pracujeme. Proto my i ostatní vývojáři používáme pro tento účel JSON klíče, kde klíč obsahuje název (nebo
v našem případě více metadat) _omezení_ a hodnota obsahuje pouze porovnatelné hodnoty pro toto _omezení_.

To však přineslo několik nových problémů – hlavně s podřízenými omezeními. V Javě jednoduše určujeme, zda chceme podporovat
seznam _omezení_ nebo jedno _omezení_ jako parametry konstruktoru. V JSONu, pokud to chceme, musíme
každé podřízené omezení zabalit do dalšího JSON objektu, abychom měli přístup ke jménům podřízených omezení, ale pak máme
problém, že klient může zadat více omezení v tomto wrapper objektu, i když omezení může přijímat
pouze jedno podřízené omezení. Mohli bychom jednoduše vyhodit chybu, když to klient udělá, ale to by bylo dost neintuitivní
a klient by musel odeslat dotaz, aby zjistil, zda má správnou strukturu. Místo toho jsme se rozhodli, že každý
takový wrapper kontejner bude přeložen na implicitní _and_ omezení s implicitním _AND_ vztahem mezi
vnitřními _omezeními_ (a chybu vyhodíme pouze ve výjimečných případech, kdy tento wrapper _AND_ kontejner nedává smysl). Takový
přístup přináší novou složitost do resolveru dotazů, ale na druhou stranu řeší téměř všechny problémy
s podřízenými omezeními. Jako bonus klienti nemusí používat explicitní _and_ omezení, pokud jim vyhovuje
výchozí _AND_ vztah. To může být užitečné v omezeních, jako je _filterBy_ omezení, které přijímá pouze jedno podřízené omezení, ale
protože podřízené omezení musí být zabaleno do implicitního _and_ omezení, klient nemusí _and_
omezení vůbec použít.

#### Příklady finálního řešení

Vygenerované _GraphQL_ schéma dotazu vypadá například takto:

Abychom ilustrovali praktické použití, následující ukázka ukazuje implicitní _and_ podmínku mezi _equals_ a
_startsWith_ omezeními uvnitř kontejneru omezení _filterBy_:

```json
filterBy: {
    attributeCodeEquals: "iphone7s",
    attributeUrlStartsWith: "https://"
}
```

Další složitější příklad kontejneru _or_ s vnitřními implicitními _and_ kontejnery:

```json
filterBy: {
   or: [
      {
         entityPrimaryKeyInSet: [100, 200]
      },
      {
         attributeCodeStartsWith: "ipho",
         hierarchyCategoryWithin: {
            ofParent: {
                entityPrimaryKeyInSet: [20]
            }
         },
         priceBetween: ["100.0", "250.0"]
      }
   ]
}
```

Nakonec příklad s vnořenými podřízenými omezeními, která v tomto případě umožňují zcela jiná omezení než
umožňuje nadřazený kontejner _filterBy_ (je zde jiná sada atributů specifikovaných ve vztahu a rozsahu entity):

```json
filterBy: {
    referenceBrandHaving: {
       attributeCodeEquals: "apple"
    }
}
```

## Závěr

Nakonec jsme zvolili tento formát v naději, že bude vyžadovat méně speciálních znaků a bude se číst více jako
angličtina, což by mohlo výrazně pomoci s intuitivností jazyka. Nevýhodou je rozvláčnost
GraphQL dotazovacího API (a samozřejmě jsme nechtěli
[znovu zavést COBOL](https://www.quora.com/Why-do-they-say-that-that-old-programming-language-COBOL-is-very-verbose-How-was-it-verbose)),
ale věříme, že většinu dotazu doplní editor a vývojáři budou muset napsat jen
pár znaků pro každé omezení. Dalším argumentem je, že s naším přístupem se většina složitých dotazů vejde na jednu
obrazovku bez nutnosti rolování, protože jednoduchá _omezení_ obvykle zaberou jen jeden řádek oproti minimálně třem řádkům, například
v případě [EdgeDB přístupu](https://www.edgedb.com/docs/graphql/graphql). Tento formát jsme konzultovali s
několika front-end i back-end vývojáři a všichni se shodli, že v našem případě by tento přístup mohl fungovat mnohem
lépe než výše zmíněné. Tento přístup jsme aplikovali i na _order_ a _require_ omezení a
fungovalo to velmi dobře ve srovnání s výše uvedenými přístupy.