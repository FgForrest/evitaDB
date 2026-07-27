---
title: Když AI agent dostane do rukou debugger
perex: |
  Agentní programování je fascinující, ale má jednu zásadní slabinu — agent nevidí, co se v běžící aplikaci skutečně děje. Pustili jsme se do vývoje MCP serveru, který dává AI agentům plnohodnotný přístup k Java debuggeru přes JDWP protokol. V tomto článku se podíváme, jak to funguje, jak si to můžete sami vyzkoušet, a proč nám to pomáhá při vývoji evitaDB.
date: '9.4.2026'
author: 'Jan Novotný'
motive: ../en/assets/images/24-mcp-jdwp-java-plugin.png
proofreading: 'done'
---

Pokud pracujete s AI agenty na Java projektech, pravděpodobně znáte ten pocit — agent napíše kód, spustí test, test selže, a pak začne ta známá smyčka. V lepším případě se agent pokusí o opravu naslepo na základě chybového výpisu. V horším začne sypat do kódu `System.out.println` a debug logy, kvůli kterým musí celý projekt rekompilovat. Každý takový cyklus stojí čas — a ty debug výpisy pak v kódu zůstávají jako nevzhledné artefakty, které agent zapomene uklidit a vy je najdete až při code review.

Jako vývojáři bychom v takové situaci reflexivně sáhli po debuggeru, nastavili breakpoint a během pár minut viděli, co se skutečně děje. Žádné rekompilace, žádné zanesení kódu dočasnými výpisy. Agent tuto možnost dosud neměl.

Rozhodli jsme se to změnit a výsledkem je [MCP JDWP Inspector](https://github.com/FgForrest/mcp-jdwp-java) — MCP server, který dává AI agentům plnohodnotný přístup k Java debuggeru prostřednictvím standardního JDWP (Java Debug Wire Protocol) rozhraní. Jinými slovy — agent si teď může nastavit breakpoint, zastavit vlákno, podívat se na lokální proměnné, vyhodnotit výraz a pokračovat v běhu úplně stejně, jako to děláte vy v IntelliJ IDEA.

## Proč to děláme

Při vývoji [evitaDB](https://evitadb.io) se běžně dostáváme do situací, kdy je potřeba debugovat netriviální logiku — ať už jde o složité dotazy nad katalogem, práci s bitovými mapami nebo souběžný přístup k datovým strukturám. Řadu těchto problémů odchytáváme ve fuzzy testech, které generují náhodné kombinace vstupních dat a ověřují konzistenci výsledků. Když takový test selže, je příčina často hluboko zakopaná a ze samotného chybového výpisu ji neodhalíte.

Dříve jsme v takových případech museli výstup ručně analyzovat a pak sami sedět u debuggeru. Dnes agent připojený přes MCP JDWP Inspector dokáže celou analýzu provést autonomně — najde místo selhání, nastaví si breakpointy, projde si stav proměnných a v naprosté většině případů identifikuje kořenovou příčinu sám. Ušetřený čas se počítá v hodinách.

## Co všechno agent umí

MCP JDWP Inspector poskytuje 40 nástrojů organizovaných do logických skupin:

- **Breakpointy** — klasické řádkové breakpointy, podmíněné breakpointy (zastaví jen když je splněna Java podmínka), breakpointy na výjimky (zachytí je přímo v místě vyhození) a deferred breakpointy, které se aktivují automaticky při načtení dané třídy
- **Logpointy** — vyhodnotí výraz na daném řádku bez zastavení vlákna, ideální pro neinvazivní sledování
- **Inspekce** — procházení vláken, zásobníků volání, lokálních proměnných a polí objektů s automatickým filtrováním šumu z JVM internals a frameworků
- **Vyhodnocení výrazů** — zkompiluje a spustí libovolný Java kód přímo v kontextu zastavené aplikace s plným classpathm
- **Mutace za běhu** — umí měnit hodnoty lokálních proměnných i polí objektů, což je neocenitelné pro testování hypotéz
- **Watchers** — persistentní výrazy navázané na breakpointy, které se automaticky vyhodnocují při každém zásahu

Celé to běží lokálně, bez jakýchkoliv odchozích HTTP požadavků. Zdrojový kód se kompiluje přímo z repozitáře — žádné stažené binárky, plná auditovatelnost.

## Jak na to — rychlý setup

Potřebujete JDK 17+ (pozor, ne JRE — debuggovací rozhraní vyžaduje modul `jdk.jdi`) a Maven 3.8+. Instalace je možná dvěma způsoby:

- **Plugin marketplace** — dva příkazy v Claude Code a máte hotovo, JAR se zkompiluje automaticky z lokálního klonu
- **Ruční registrace** — naklonujete repozitář, sestavíte přes Maven a zaregistrujete MCP server v konfiguraci vašeho agenta

Cílovou Java aplikaci pak stačí spustit s JDWP agentem na portu 5005 (u Maven testů postačí `mvn test -Dmaven.surefire.debug`) a agent se k ní připojí.

Kompletní návod včetně konfiguračních příkladů najdete v [README projektu](https://github.com/FgForrest/mcp-jdwp-java#readme).

## Test-flight: vyzkoušejte si to na kůži vlastního agenta

Abyste nemuseli plugin zkoušet rovnou na svém projektu, připravili jsme v repozitáři modul `jdwp-sandbox` s pěti záměrně rozbitými Java třídami. Každá simuluje jiný typ reálné chyby — od tichých mutací přes problémy s `hashCode()` a spolknuté výjimky až po race conditions a porušení viditelnosti v Java Memory Modelu. Obtížnost roste od zahřívacích po skutečně zapeklité.

Stačí naklonovat [náš repozitář](https://github.com/FgForrest/mcp-jdwp-java), spustit testy v sandbox modulu s JDWP agentem a říct agentovi něco jako: *„Použij JDWP a oddebuguj třídu VanishingPenniesTest — najdi kořenovou příčinu."* Popis jednotlivých úloh a návod na spuštění najdete v [README](https://github.com/FgForrest/mcp-jdwp-java#-find-the-bug--learning-exercises).

Sledovat, jak agent systematicky prochází kód, nastavuje breakpointy a postupně se prokousává k příčině, je samo o sobě zážitek, který vám změní pohled na to, co agentní programování dokáže.

## Jak to používáme v evitaDB

V evitaDB máme rozsáhlou sadu fuzzy testů, které kombinují náhodné operace nad katalogem — vkládání entit, změny schémat, souběžné dotazy — a ověřují, že výsledky jsou vždy konzistentní. Když takový test selže, chyba je sice deterministická, ale projevuje se třeba až se statícící generaci a chvíli se na ní čeká. Zároveň je pro její vyřešení potřeba nastavit breakpointy s chirurgicky přesnými podmínkami, které zachytí konkrétní scénář, což je bez debuggeru velmi náročné a zdlouhavé.

MCP JDWP Inspector nám v těchto situacích dramaticky zrychlil analýzu. Agent si sám projde selhávající test, nastaví breakpointy na podezřelá místa, sleduje stav datových struktur a v drtivé většině případů identifikuje příčinu bez našeho zásahu. U složitějších problémů nám alespoň výrazně zúží oblast, kde je potřeba hledat.

Zvlášť užitečné je to u souběžných chyb — race conditions a deadlocků — kde je lidská analýza ze stack tracu často jako hledání jehly v kupce sena. Agent s přístupem k debuggeru dokáže systematicky projít stav jednotlivých vláken a zrekonstruovat sekvenci událostí mnohem spolehlivěji.

## Závěrem

MCP JDWP Inspector je open source projekt pod MIT licencí. Vychází z původní práce [Nicolase Vautrina](https://github.com/NicolasVautrin/mcp-jdwp-java), kterou jsme rozšířili o funkce potřebné pro reálný provoz — podmíněné breakpointy, deferred aktivaci, ochranu proti rekurzivním breakpointům, logpointy a řadu dalších. Zpětný pull request do jeho repositáře však nedával smysl, protože náš plugin výrazně mění původní koncept a přidává funkce, které jsou pro něj zbytečné.

Pokud pracujete na Java projektu a používáte AI agenty, vřele doporučujeme si plugin vyzkoušet — minimálně na těch pěti sandboxových hádankách. Je to nejrychlejší způsob, jak pochopit, jaký rozdíl dělá, když agent „vidí dovnitř" běžící aplikace.

Budeme rádi za vaše zkušenosti, nápady i případné hlášení chyb přímo na [GitHubu](https://github.com/FgForrest/mcp-jdwp-java/issues).