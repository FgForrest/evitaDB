---
title: Zjednodušení datových modelů pomocí nativní podpory soft-delete v evitaDB
perex: Nativní podpora soft-delete v evitaDB zjednodušuje datové modely aplikací, zvyšuje výkon a snižuje složitost tím, že spravuje archivované entity přímo v databázi.
date: '23.09.2024'
author: Ing. Jan Novotný
motive: ../en/assets/images/15-soft-delete.png
proofreading: done
commit: '98e09c273f7f55c11bd66d04422c9e46d7fe7e67'
---
V neustále se vyvíjejícím prostředí e-commerce je potřeba udržovat přístup k zastaralým nebo ukončeným produktům důležitější než kdy dříve. Tyto produkty často slouží jako cenný zdroj technických informací, pro SEO optimalizaci a jako historická data navázaná na objednávky nebo zákaznickou historii. Tradičně však správa takto archivovaných entit přináší další složitost do datového modelu aplikace. Díky nativní podpoře soft mazání v evitaDB však nyní můžeme tento proces zjednodušit, snížit složitost a zlepšit celkový výkon systému.

Další významnou výhodou podpory soft mazání v evitaDB je možnost definovat indexační pravidla pro archivované entity odlišně od živých entit. Protože evitaDB uchovává všechny indexy v paměti, omezení počtu indexovaných dat pro archivované entity může výrazně snížit spotřebu paměti, přičemž si zachováte možnost přístupu k důležitým historickým datům.

## Složitost tradičních metod archivace
Obvykle, když produkty již nejsou aktivní, vývojáři vytvářejí samostatné kolekce entit, například ObsoleteProduct, pro jejich správu. Tento přístup, ač funkční, přináší několik problémů.
Datový model se stává složitějším, protože je nutné duplikovat nebo upravovat vztahy a schémata, aby vyhovovala novým typům entit. Tato duplikace nejen zvyšuje riziko nekonzistencí, ale také zvyšuje náročnost údržby, což ztěžuje správu a další rozvoj aplikace.

Dalším přístupem je implementace soft mazání na úrovni aplikace, kdy jsou entity označeny jako archivované, ale zůstávají ve stejné kolekci. Tento způsob sice zabraňuje duplikaci schémat, ale přináší vlastní sadu problémů. Vyžaduje, aby vývojáři přidávali do dotazů další podmínky pro filtrování archivovaných entit, což zvyšuje složitost aplikačního kódu a riziko chyb. Archivované entity navíc zůstávají v aktivních indexech, zabírají cennou RAM a ovlivňují celkový výkon.

## Nativní řešení soft mazání v evitaDB
Pro řešení těchto problémů evitaDB zavádí nativní podporu soft mazání prostřednictvím metod `archiveEntity` a `restoreEntity`. Archivace je tak řešena přímo v databázi, což eliminuje potřebu samostatných kolekcí nebo dalších příznaků spravovaných na úrovni aplikace. Tato nativní podpora zajišťuje, že archivované entity jsou správně označeny. V základním nastavení jsou archivované entity automaticky vyloučeny ze všech aktivních vyhledávacích indexů kromě indexů primárních klíčů, čímž se uvolňuje RAM a zlepšuje výkon vyhledávání pro „živé“ entity.

Vývojáři však mohou definovat vlastní indexační pravidla pro archivované entity, což jim umožňuje optimalizovat spotřebu paměti podle konkrétních potřeb aplikace a zároveň zachovat možnost vyhledávání archivovaných entit podle vybraných atributů nebo vztahů.

Při získávání entit mohou vývojáři použít filtrační omezení [`scope(LIVE, ARCHIVED)`](https://evitadb.io/documentation/query/filtering/behavioral#scope) k určení, zda chtějí do dotazů zahrnout živé entity, archivované entity nebo obojí. Tato flexibilita umožňuje přesnou kontrolu nad získávanými daty bez zvyšování složitosti aplikačního kódu. Filtrování zajišťuje databáze interně, což zaručuje konzistenci a snižuje riziko chyb.

## Správa unikátních omezení napříč scope
Jednou z výzev při správě archivovaných entit je správa unikátních omezení. V evitaDB jsou unikátní omezení vynucována v rámci stejného scope. Entita ve scope `ARCHIVED` však může sdílet unikátní hodnotu s entitou ve scope `LIVE` a naopak. Tato vlastnost umožňuje vývojářům znovu použít identifikátory, jako jsou kódy produktů nebo URL, pro nové entity bez konfliktu s archivovanými. Zjednodušuje to správu dat a zajišťuje, že aplikace může nadále používat smysluplné a konzistentní identifikátory.

Při obnově archivované entity nebo archivaci živé entity evitaDB automaticky kontroluje unikátní omezení v rámci stejného scope, aby zabránila konfliktům. Pokud je konflikt zjištěn, operace obnovení selže a vývojář musí nejprve entitě přiřadit novou unikátní hodnotu.

Při dotazování na entity podle unikátních hodnot evitaDB automaticky upřednostňuje entity ve scope `LIVE` před entitami v `ARCHIVED`, pokud dojde ke konfliktu unikátních hodnot. Toto chování zajišťuje, že aplikace získá nejrelevantnější a nejaktuálnější informace pro uživatele.

## Praktické využití v e-commerce
Představme si e-commerce platformu, kde jsou produkty pravidelně aktualizovány, ukončovány nebo znovu zaváděny. Díky podpoře soft mazání v evitaDB může vývojář při ukončení produktu jednoduše zavolat metodu `archiveEntity`. Produkt je poté označen jako archivovaný a odstraněn z aktivního vyhledávání, ale zůstává dostupný pro SEO účely nebo získání historických dat. Databáze udržuje vyhledávací index pro jeho vlastnost `URL` a primární klíč, takže pokud byl produkt odkazován v klientských objednávkách nebo seznamech přání, aplikace může stále zobrazit detaily archivovaného produktu.

Pokud je produkt znovu zaveden, zavoláním metody `restoreEntity` jej vrátíte zpět do scope `LIVE`, čímž se opět zobrazí v aktivním vyhledávání. Tento proces eliminuje potřebu produkt znovu vytvářet nebo provádět složité migrace dat. Zajišťuje také, že všechna historická data, jako jsou recenze zákazníků nebo historie prodeje, zůstanou zachována a přiřazena k produktu.

## Zjednodušení dotazů pomocí scope omezení
Použití filtračního omezení `scope(LIVE, ARCHIVED)` v dotazech poskytuje vývojářům silnou kontrolu nad získáváním dat. Například administrátorské rozhraní může potřebovat zobrazit jak živé, tak archivované produkty jedním dotazem pro účely správy. Zadáním obou scope v dotazu aplikace získá všechny relevantní entity bez nutnosti provádět dva dotazy a spojovat výsledky v aplikačním kódu.

Vývojáři mají stále možnost přistupovat k živým nebo archivovaným entitám samostatně tím, že v dotazu specifikují pouze jeden scope. Pokud není specifikováno žádné scope omezení, databáze ve výchozím nastavení použije scope `LIVE`, takže aplikace standardně získává pouze aktivní entity.

Tento přístup také zvyšuje bezpečnost a integritu dat. Protože databáze spravuje viditelnost entit na základě jejich scope, minimalizuje se riziko náhodného zpřístupnění archivovaných nebo citlivých dat. Aplikace se může spolehnout, že databáze vrátí pouze entity odpovídající zadanému scope, což snižuje možnost chyb nebo nechtěných úniků dat.

## Závěr
Nativní podpora soft mazání v evitaDB představuje významný pokrok v oblasti správy databází pro aplikace, které vyžadují archivaci. Díky internímu zpracování soft mazání evitaDB zjednodušuje datový model aplikace, snižuje složitost a zvyšuje výkon. Vývojáři se tak mohou vyhnout úskalím tradičních metod archivace, jako je duplikace schémat a složité filtrování dotazů, což vede k lépe udržovatelným a efektivnějším aplikacím.

Přijetím soft mazání na úrovni databáze mohou organizace zjednodušit vývojové procesy, zlepšit výkon systému a soustředit se na poskytování hodnoty uživatelům, aniž by se musely zabývat složitostmi správy dat.