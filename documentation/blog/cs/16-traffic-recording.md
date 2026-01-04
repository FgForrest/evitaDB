---
title: Kouzlo zaznamenávání, analýzy a přehrávání provozu
perex: Představte si, že byste mohli zaznamenat veškerý provoz na vaší databázi, analyzovat ho a znovu přehrát. O kolik jednodušší by byl váš život? Určitě stojí za to věnovat nějaké úsilí nastavení správných nástrojů, které to umožní. Ale co kdybych vám řekl, že veškeré toto kouzlo lze provést pouhým otočením jediného přepínače?
date: '24.01.2025'
author: Ing. Jan Novotný
motive: ../en/assets/images/16-traffic-recording.png
proofreading: done
commit: c4b87b6dcfb310e8a4c7f2628de81274ed221fb6
---
V nejnovější verzi `2025.1` evitaDB jsme představili novou funkci s názvem `Traffic Recording`. Ve výchozím nastavení je vypnutá, ale můžete ji snadno zapnout nastavením hodnoty `server.trafficRecording.enabled` na `true`. I když to neuděláte, vždy ji můžete ručně spustit v konzoli evitaLab. Tato funkce umožňuje zaznamenávat veškerý provoz, který prochází databází – relace, dotazy, mutace, načítání entit – zkrátka vše.

Záznamový engine je navržen tak, aby byl co nejlehčí, takže jej můžete spustit i v produkčním prostředí. Pokud je provoz velmi silný, můžete také nakonfigurovat engine pro záznam tak, aby provoz vzorkoval, čímž snížíte množství uložených dat a omezíte dopad na výkon, ale stále budete mít dostatek dat pro analýzu vzorců provozu. Data o provozu jsou serializována do omezeného bufferu v paměti, což je velmi rychlé, a poté asynchronně zapisována do souboru bufferu na disku. Pokud proces zápisu na disk nestíhá, engine pro záznam automaticky zahodí veškerý provoz, který se nevejde do paměťového bufferu. Nejhorší, co se může stát, je, že přijdete o část dat pro analýzu, ale databáze bude nadále fungovat bez problémů a vaši zákazníci si ničeho nevšimnou.

<p>
    <video width="850" height="478" controls="controls">
      <source src="https://evitadb.io/download/blog-16-traffic-recording-1.mp4" type="video/mp4"/>
        Váš prohlížeč nepodporuje video tag.
    </video>
</p>

Diskový buffer je navržen tak, aby se choval jako kruhový buffer a je alokován při spuštění databáze, takže vám nikdy nedojde místo na disku. Když buffer dosáhne svého konce, engine pro záznam začne přepisovat nejstarší data od začátku. Můžete spustit další asynchronní úlohu, která zachytí všechna data ze souboru diskového bufferu dříve, než budou přepsána, a uloží je do dalšího komprimovaného souboru pro pozdější analýzu. Tuto úlohu lze nastavit tak, aby se automaticky zastavila po dosažení určité velikosti dat nebo po uplynutí určité doby.

Nyní, když máte základní představu o tom, jak nástroj funguje, podívejme se na několik praktických příkladů jeho využití.

## Pomoc při vývoji

Na stejné aplikaci pracuje několik vývojářů a týmů. Často nejsou vývojáři využívající webová API evitaDB ti samí, kteří navrhují schéma databáze nebo spravují data v ní. Často se setkáváme se situací, kdy frontendoví vývojáři navrhující webovou stránku v moderních JavaScriptových frameworkech jako React, Angular nebo Vue nemohou snadno poskytnout kompletní GraphQL / REST dotazy, které jsou v jejich aplikacích dynamicky sestavovány, aby získali správnou podporu a radu od backendových vývojářů. Mohou použít nástroje jako Open Telemetry Tracing (který evitaDB také podporuje) pro získání potřebných dat, ale často je zde mnoho záznamů a najít ten správný může být výzva. Dalším problémem je velikost dotazu, která může být poměrně velká a nemusí se vejít do záznamu trasování nebo logu a může být zkrácena. Všechny tyto přístupy jsou proveditelné, ale nejsou příliš přívětivé pro vývojáře.

S Traffic Recording může být evitaDB nastavena tak, aby automaticky zachytávala veškerý provoz na vývojářském stroji nebo v testovacím prostředí, a pokud je to potřeba, frontendový vývojář může přes rozhraní evitaLab přistupovat ke kterémukoliv svému dotazu. Příslušný požadavek lze najít různými způsoby, z nichž nejpohodlnější je vyhledávání podle automatických nebo vývojářem přiřazených štítků. Vývojář okamžitě vidí základní telemetrická data – například dobu trvání dotazu, počet načtených řádků, počet odpovídajících záznamů atd. – a může dotaz znovu spustit v konzoli evitaLab, aby viděl výsledek dotazu a případně jej upravit. Tímto způsobem získá vývojář okamžitou zpětnou vazbu na dotaz a může jej snadno sdílet s backendovým vývojářem pro další pomoc.

Traffic Recorder zachycuje dotazy v jejich původním formátu (GraphQL, REST, gRPC) i ve vnitřním formátu evitaQL. Často jeden GraphQL dotaz může představovat několik evitaQL dotazů, které jsou spojeny do jednoho výsledku a ve skutečnosti jsou vykonávány paralelně dotazovacím enginem. Všechny tyto vztahy jsou zachyceny a vizualizovány v rozhraní evitaLab. Všechny vstupní dotazy jsou k dispozici v přesné podobě, v jaké dorazily do databáze, takže můžete přistupovat k proměnným, fragmentům atd. přesně tak, jak byly odeslány klientem.

<p>
    <video width="850" height="478" controls="controls">
      <source src="https://evitadb.io/download/blog-16-traffic-recording-2.mp4" type="video/mp4"/>
        Váš prohlížeč nepodporuje video tag.
    </video>
</p>

Traffic recorder také zaznamenává všechny neplatné požadavky, takže můžete vidět požadavky, které nebyly možné zpracovat nebo byly databází z nějakého důvodu odmítnuty. To může být velmi užitečné při ladění a vylepšování klientské aplikace.

Filtr umožňuje filtrovat dotazy podle různých kritérií – například můžete filtrovat pouze dotazy, které trvaly déle než určitou dobu, nebo pouze dotazy, které načetly nadměrné množství dat z disku. To vám může pomoci včas identifikovat úzká místa výkonu ve vaší aplikaci již v průběhu vývoje.

## Analýza výkonu

Protože můžete zachytit reprezentativní vzorek provozu, můžete jej použít k analýze výkonu vaší aplikace. Můžete vidět, které dotazy jsou nejčastější, které jsou nejpomalejší, které načítají nejvíce dat atd. Můžete obnovit zálohy svých produkčních dat a přehrát zachycený provoz na různých strojích, abyste otestovali různé hardwarové konfigurace, nebo provést zátěžový test databáze zvýšením rychlosti přehrávání nebo jejím násobením paralelním přehráváním provozu více klienty.

<p>
    <video width="850" height="478" controls="controls">
      <source src="https://evitadb.io/download/blog-16-traffic-recording-3.mp4" type="video/mp4"/>
        Váš prohlížeč nepodporuje video tag.
    </video>
</p>

Toto je velmi nákladově efektivní způsob, jak objevit limity vaší aplikace v reálných scénářích na přesných datech a učinit správné závěry o výkonu vaší aplikace a potřebných hardwarových požadavcích. Traffic recording můžete také použít k porovnání výkonu různých verzí vaší aplikace nebo databázového enginu.

## Přehrávání provozu a testování

Kromě výkonových důvodů lze přehrávání provozu použít k nalezení těžko reprodukovatelných chyb ve vaší aplikaci nebo samotné databázi. Jednou z takových chyb je [issue #37](https://github.com/FgForrest/evitaDB/issues/37), která souvisí s interní cache evitaDB. Vyskytuje se náhodně a jen občas a nepodařilo se nám ji zachytit, proto doporučujeme cache vypnout (a ve výchozím nastavení je vypnutá), i když víme, že tato interní cache výrazně zlepšuje výkon databáze. S Traffic Recorderem v našem arzenálu můžeme zachytit dostatek provozu na produkčních datech, takže pokud jej přehrajeme lokálně na dvou paralelních instancích evitaDB – jedné s cache zapnutou a druhé s cache vypnutou – můžeme snadno identifikovat situaci, kdy k chybě dochází a které dotazy nebo stav obsahu cache ji vyvolávají. Jsme si jisti, že se nám podaří chybu zachytit v některém z příštích vydání a cache opět zapnout ve výchozím nastavení.

## Bezpečnostní audit

Použití záznamu provozu pro bezpečnostní audit je okrajový scénář, ale pokud si můžete dovolit uchovávat veškerý provoz po potřebnou dobu, můžete jej využít k auditu všech přístupů do databáze – kdo s ní komunikoval, kdy a k jakým datům měl přístup.

## Závěr

Traffic Recording je výkonný nástroj, který vám může pomoci mnoha způsoby. Je snadné jej nastavit a používat a může vám ušetřit spoustu času a úsilí při vývoji, analýze výkonu, testování i bezpečnostním auditu. Věříme, že je to nezbytná funkce pro každý moderní databázový engine, přesto ji nabízí jen velmi málo databází. Tato funkce obvykle vyžaduje samostatné nástroje a mnoho úsilí na nastavení a údržbu. S evitaDB ji získáte ihned po instalaci a je navržena tak, aby byla co nejlehčí a nejsnadněji použitelná.

Doufáme, že pro vás bude stejně užitečná jako pro nás. Pokud máte jakékoli dotazy nebo potřebujete pomoc s nastavením, neváhejte nás kontaktovat. Jsme tu pro vás!