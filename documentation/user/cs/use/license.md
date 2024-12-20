---
title: Licence
perex: |
    evitaDB je licencována pod licencí Business Source License 1.1. Technicky vzato se nejedná o open source licenci, 
    ale je to přátelská licence s ohledem na open source, protože se po uplynutí 4 let automaticky převádí na open 
    source licenci.
date: '22.10.2024'
author: 'Ing. Jan Novotný'
proofreading: 'done'
---

evitaDB je licencována pod [Business Source License 1.1](https://github.com/FgForrest/evitaDB/blob/dev/LICENSE). 
Technicky vzato se nejedná o open source licenci, ale je to [open source přátelská](https://itsfoss.com/making-the-business-source-license-open-source-compliant/) licence, protože se po uplynutí určité doby specifikované v licenci automaticky převádí na open source licenci.

Jsme fanoušci open source a z open source softwaru velmi těžíme (dokonce i databázový engine některé z nich využívá). 
Implementace databáze si vyžádala tisíce člověkodnů a pokud bude úspěšná, bude stát ještě několik tisíc dalších. 
Měli jsme štěstí a získali [grant EU](https://evitadb.io/project-info), který částečně financoval počáteční implementaci,
ale z dlouhodobého hlediska potřebujeme vybudovat soběstačný projekt. [Naše společnost](https://www.fg.cz/en) používá
evitaDB pro své vlastní komerční projekty, takže vývoj databáze je zaručen, ale bez dodatečných příjmů by byl vývoj 
omezen. Proto jsme zvolili tento typ licence, ale nakonec vám — našim uživatelům — umožňujeme téměř jakékoli použití.

**Ve zkratce:**

- licence BSL pokrývá období 4 let od data vydání softwaru
- 4 roky stará verze evitaDB se stává [permisivní licencí Apache License, v.2](https://fossa.com/blog/open-source-licenses-101-apache-license-2-0/)
- obě licence, BSL i Apache, vám umožňují používat evitaDB pro OSS a/nebo komerční projekty zdarma
- existuje pouze jedna výjimka — nesmíte nabízet a prodávat evitaDB jako službu třetím stranám

To je vše.

<Note type="question">

<NoteTitle toggles="true">

##### Mohu používat evitaDB zdarma pro komerční účely?

</NoteTitle>

Můžete jej používat pro vývoj, testování a produkční nasazení jakéhokoli softwaru — nekomerčního i komerčního — který
vyvíjíte a distribuujete svým zákazníkům. Můžete zahrnout evitaDB do své softwarové distribuce nebo jej používat jako
samostatně běžící službu v režimu klient-server.

Nemůžete vzít evitaDB a prodávat jej jako [DBaaS](https://www.geeksforgeeks.org/overview-of-database-as-a-service/) bez 
sjednání licence s námi.

</Note>

<Note type="question">

<NoteTitle toggles="true">

##### Ovlivňuje licence data uložená v databázi?

</NoteTitle>

Ne, data stále vlastníte vy a rozhodujete o tom, co s nimi uděláte. Licence BSL pokrývá pouze software a zdrojový kód 
evitaDB. Neshromažďujeme ani nezískáváme žádné statistiky z běžícího softwaru. Pokud však požádáte o naši pomoc při 
řešení problému se softwarem, můžeme vás požádat o poskytnutí některých dat, která problém demonstrují.

</Note>

<Note type="question">

<NoteTitle toggles="true">

##### Mohu použít evitaDB v open source knihovně licencované pod jinou licencí?

</NoteTitle>

Ano, můžete, pokud nekopírujete obsah zdrojového kódu evitaDB do své knihovny a používáte pouze API evitaDB 
prostřednictvím připojené knihovny nebo webového API. Pokud forknete repozitář evitaDB a provedete změny v zdrojovém
kódu, stále se na něj vztahuje licence BSL.

</Note>

<Note type="question">

<NoteTitle toggles="true">

##### Můžu přispět k vývoji evitaDB nebo poskytnout opravu chyby?

</NoteTitle>

Samozřejmě můžete a budeme rádi, když tak uděláte. Stačí, když svůj kód výslovně licencujete pod
[novou BSD licencí](https://opensource.org/license/bsd-3-clause/), abychom jej mohli zahrnout do naší codebase.
Vaše jméno bude přidáno do našeho seznamu přispěvatelů s poděkováním.

</Note>

<Note type="question">

<NoteTitle toggles="true">

##### Nenašli jste zde odpovědi na všechny své otázky?

</NoteTitle>

Pak se možná budete chtít podívat na [Otázky a odpovědi na webu MariaDB](https://mariadb.com/bsl-faq-adopting/). 
MariaDB je autorem a průkopníkem licence BSL. Mohlo by vás také zajímat [článek](https://blog.adamretter.org.uk/business-source-license-adoption/), 
který reflektuje licencování databází a jeho změny v posledních letech. 
Samozřejmě se můžete vždy [zeptat nás přímo](https://evitadb.io/contacts).

</Note>