---
title: Automatizované překlady dokumentace do světových jazyků
perex: |
  Připravujeme novou verzi našeho webu, jehož podstatná součást je uživatelská a vývojářská dokumentace. Není v našich silách udržovat dokumentaci aktuální ve více než jednom jazyce, a proto jsme zvolili angličtinu, ale ambice poskytovat dokumentaci v rodném jazyce vývojářů nás neopustila a v tomto článku nabízíme pohled na mechanismus, jak toho chceme dosáhnout.
date: '3.1.2026'
author: 'Jan Novotný'
motive: ../en/assets/images/23-maven-comenius-plugin.png
proofreading: 'done'
--- 

V malém týmu není prakticky možné udržovat rozsáhlou dokumentaci, nedejbože ve více jazycích konzistentní a aktuální. Respektive toto tvrzení platilo do nedávné doby, ale s nástupem LLM se všechno radikálně mění. Jedna z vlastností, ve které řada LLM vyniká, jsou právě překlady mezi jazyky a díky agentním nástrojům je aktuálně možné ověřovat konzistenci a platnost mnohem větší dokumentace, než je možné prostou lidskou silou. Kupodivu jsem nenašel žádný nástroj, integrovatelný s Maven build systémem, který by umožňoval automatizovaný překlad MarkDown souborů, a tak jsem se na přelomu roku rozhodl vyzkoušet sílu vibe-codingu a napsat plugin vlastní. Jeho první verze je aktuálně hotova a vy si ho můžete sami hned [vyzkoušet](https://github.com/FgForrest/comenius-maven-plugin).

## Základní funkcionalita Comenius pluginu

Princip fungování pluginu je jednoduchý - ukážete mu na složku s vaší primární dokumentací ve formátu MarkDown (resp. MDX, ve kterém můžete používat i neznámé HTML tagy, které představují bohatší reprezentaci) a definujete složku (nebo více složek, pokud chcete překládat do více jazyků), kde mají být umístěny přeložené soubory. Dodáte HTTP adresu LLM API včetně typu modelu, který se má použít, a autorizačního klíče. Naši dokumentaci aktuálně překládáme pomocí OpenAI `gpt-4.1`, protože mi její výstup v češtině (mém rodném jazyce) přišel subjektivně lepší než výstupy modernějších modelů.

**Tip:** pro kontrolu nastavení je možné použít goal `show-config`

Před vlastním spuštěním překladu je potřeba, aby všechna zdrojová dokumentace byla minimálně commitnutá a plugin si dokázal získat commit hash souboru, který překládá. Tento commit hash následně vkládá do [front matter](https://docs.github.com/en/contributing/writing-for-github-docs/using-yaml-frontmatter), aby bylo jasné, jaké konkrétní zdrojové verzi překlad odpovídá. Před vlastním překladem je vhodné nechat zkontrolovat integritu zdrojové dokumentace (tj. funkční odkazy) pomocí goal `check`.

Kód následně prochází všechny soubory (samozřejmě případné excludy máte pod kontrolou) a paralelně volá nakonfigurovaný LLM model a jednotlivé soubory překládá. Když kompletní překlad doběhne, následuje druhá fáze, kdy v přeložených souborech opraví odkazy na vnitřní kapitoly (anchor), které již neodpovídají, protože nadpisy byly přeloženy do cílového jazyka. Aby to bylo možné, vynucuje plugin konzistentní strukturu nadpisů i po překladu a při hledání cílového nadpisu pro anchor se orientuje podle pořadí a struktury nadpisů v daném dokumentu. Jako poslední krok provádí plugin kontrolu validity odkazů v přeloženém dokumentu (proto je nutné, aby tam nebyly žádné chyby ještě před překladem).

V rámci implementace jsem zjistil, že překlad rozsáhlých stránek nedělají LLM dobře a mají tendenci překlad zkrátit a doplnit jej jednoduchým "... a tak dále". Proto plugin před vlastním překladem ověří velikost článku a pokud přesahuje 32 kB, tak se jej pokusí rozdělit na několik menších částí při respektování struktury nadpisů (např. se je pokusí oddělit podle H2 nadpisů) tak, aby dosáhl několika kusů, které se optimálně blíží 32 kB, které se ukázaly při překladech jako spolehlivé. Po dokončení překladu jednotlivých částí dojde k jejich spojení do jediného souhrnného dokumentu odpovídajícího originálu.

**Poznámka:** překlad kompletní dokumentace evitaDB čítající mnoho stovek stránek do našeho rodného jazyka trval pouhé nižší desítky minut a vyšlo přibližně na 5$.

## Inkrementální překlady

Je samozřejmé, že v rámci dalšího rozvoje dokumentace nechcete neustále překládat kompletní verze původní dokumentace. Proto má plugin implementovanou speciální variantu překladů pro inkrementální aktualizace. Ve chvíli, kdy zjistí, že odpovídající cílový soubor již existuje, podívá se na commit hash, který je v něm uveden. Následně si vyklonuje originální dokument v dané verzi a porovná jej s aktuální verzí zdrojového dokumentu. Pro jednotlivé bloky (tj. kapitoly) si spočítá hash jejich obsahu a k překladu posílá pouze bloky (kapitoly), které identifikuje jako změněné společně s nějakou částí obsahu před a po ní pro zachycení většího kontextu.

Po dokončení inkrementálního překladu se opět kontroluje integrita struktury nadpisů, aktualizují odkazy v celé již přeložené dokumentaci a kontroluje se validita odkazů.

## Závěrem

Buďte k aktuální verzi pluginu shovívaví - je to verze `1.0`, kterou sami teprve testujeme, ale když se podíváte na [její výstupy](https://github.com/FgForrest/evitaDB/blob/dev/documentation/user/cs/index.md), zdají se nám již použitelné. Pokud se rozhodnete plugin vyzkoušet, budeme velmi rádi za vaše názory, zkušenosti i případný reporting chyb. Zároveň nesuďte kvalitu kódu - kompletním autorem je Claude Code a pro mě to byl vlastně první ucelený pokus o kompletní vibe coding pouze se supervizí.