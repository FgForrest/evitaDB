---
title: Původ evitaDB
perex: Naše společnost vstoupila do světa e-commerce kolem roku 2010. V té době jsme se již více než 10 let věnovali webovému vývoji, a to již před vznikem .com bubliny.
date: '12.1.2022'
author: Ing. Jan Novotný
motive: ../en/assets/images/01-origins-of-the-evita-db.png
commit: '652bbcf668cc8c46d8e413ebc422fb7f5c455e65'
---
Naši klienti byli středně velké firmy, které měly specifické požadavky na svou webovou prezentaci a integraci e-commerce, ale během let rostly spolu se svými požadavky. Portfolio jejich produktů začínalo na tisících položek a postupně narostlo na desítky tisíc a stovky tisíc. Přesunuli svou B2B komunikaci na web a vyžadovali specifické cenové politiky založené na mnoha faktorech. Místo několika cen za produkt tak potřebovali desítky a někdy i stovky různých cen. SEO optimalizace vyžadovala velké množství informací o produktu v několika jazycích. Každý produkt potřeboval nejen základní popis a název, ale také komplexní sadu vzájemně propojených lokalizovaných parametrů a atributů. Všechny tyto informace musely být vyhledatelné s milisekundovou latencí a aktualizovatelné během několika sekund. Náš příběh je velmi podobný příběhům jiných firem v tomto odvětví.

Tyto požadavky obvykle vedou ke vzniku opravdu složitého ekosystému vzájemně propojených stavebních bloků (relační databáze, nosql vyhledávací indexy, cache služby, fronty, load balancery), které většinou běží na mnoha výpočetních uzlech (VPS, lambdy, fyzické HW servery – co si jen vzpomenete). A i přes takové množství zdrojů není výsledné řešení často tak rychlé, jak by se očekávalo. Mnoho firem tak skončí s nákladnou infrastrukturou, která vyžaduje mnoho specializací a znalostí pro její provoz a vývoj (nebo některé náklady přesunou na cloudové poskytovatele). To byl ten moment, kdy jsme se zastavili a začali si klást otázku – je to všechno opravdu nutné?

Co kdybychom nepoužívali univerzální databáze a měli úložiště dat, které je od základu navrženo pro účely e-commerce? Bude mít lepší výkon? Bude jednodušší na používání? Dokáže vůbec konkurovat databázím, které nejlepší mozky vyvíjejí už desítky let?

Zní to bláznivě – nikdo z nás nemá Ph.D. z informatiky a nikdo z nás nikdy nenavrhoval databázi. Na druhou stranu známe svůj byznys, máme datové sady k vyzkoušení, víme, jak vypadá provoz, a toužíme si usnadnit život (a zmenšit rozpočet na krmení cloudových poskytovatelů). Máme také několik výhod oproti našim předchůdcům:

* naše pracovní datová sada (nebo alespoň indexy) se vejde do RAM, takže můžeme použít zcela odlišný přístup a datové struktury oproti databázím, které musí počítat s objemy dat přesahujícími kapacitu operační paměti a přistupovat k indexům na disku
* většina provozu jsou dotazy na čtení, které přistupují do katalogu (produkty, kategorie, parametry atd.), takže si můžeme dovolit snížit propustnost zápisu, pokud to přinese lepší propustnost čtení
* máme roky zkušeností s existujícími databázemi z pohledu vývojáře webových aplikací a známe jejich silné i slabé stránky – víme, že dobře navržené API je polovina úspěchu

Už jsme pracovali na několika testovacích prototypech, když se naskytla příležitost získat výzkumný grant. Úspěšně jsme o něj požádali a umožnil nám ponořit se hlouběji, než bychom si jinak mohli dovolit. Díky grantu jsme si mohli dovolit realizovat tři nezávislé implementace sdílené funkční testovací sady, která ověřovala naše e-commerce use-cases (více o tom později), a které jsme mohli vyhodnotit a porovnat z hlediska výkonu a hardwarových nároků. Ve spolupráci s Univerzitou Hradec Králové a doc. RNDr. Petrou Poulovou, Ph.D. a jejím týmem jsme vybrali technologie, které se soutěže zúčastní. Byly to:

* PostgreSQL jako zástupce široce používané open-source relační databáze
* Elasticsearch jako zástupce široce používané open-source no-sql databáze
* nově vyvíjená in-memory no-sql databáze

Všechny týmy dostaly všechny use-cases předem a všichni jsme implementovali požadovanou funkcionalitu na platformě Java. Datové sady byly sdílené mezi všemi stejně jako testovací sada výkonu založená na [JMH](https://github.com/openjdk/jmh). O tři roky později jsme mohli vidět, kam nás tato cesta zavedla.