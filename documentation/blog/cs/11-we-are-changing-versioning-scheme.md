---
title: Měníme schéma verzování
perex: Pro své projekty jsem vždy používal schéma semantického verzování (SemVer). Po konzultaci s mým přítelem Lukášem Hornychem a dalším zkoumání s týmem jsme se však rozhodli změnit schéma verzování pro evitaDB. Nové schéma bude kalendářní verzování. Pokud vás zajímá náš rozhovor, čtěte dále.
date: '14.1.2024'
author: Jan Novotný
motive: ../en/assets/images/11-versioning-scheme.png
proofreading: done
commit: '706ac8d32e2f70b43bb106586805c482b2fc6cdd'
---
[Semantic Versioning](https://semver.org/) slibuje, že vaši uživatelé si mohou udělat představu o dopadu aktualizace na novou verzi vašeho softwaru. Ale je to opravdu pravda? Existuje řada článků, které tvrdí opak – doporučuji zejména [tuto diskuzi na Hacker News](https://news.ycombinator.com/item?id=21967879) a především [tento blogpost](https://sedimental.org/designing_a_version.html).

SemVer přináší do hry zajímavé psychologické faktory. Příliš často vidíme knihovny s desítkami menších vydání, které nikdy nedosáhnou verze 1.0. Proč? Možná se autor bojí zavázat ke stabilnímu API. Možná mu produkční vydání připadá jako příliš velká zodpovědnost. Sám jsem do této pasti spadl – vyhýbal jsem se vydání 1.0, dokud jsem při testování automatického vydání na GitHubu omylem nevydal verzi 10.1 místo 0.10. A protože Maven Central (z dobrých důvodů) nedovoluje [mazat artefakty](https://central.sonatype.org/faq/can-i-change-a-component/), zůstali jsme s tím. Možná si myslíte, že to není velký problém, ale do této pasti spadlo mnoho projektů, včetně poměrně známých jmen jako [ReactNative](https://reactnative.dev/versions), [Elm-Language](https://elm-lang.org/news), a dokonce existuje speciální [webová stránka](https://0ver.org/), která sleduje (nebo sledovala) projekty s nulovou verzí.

Na druhou stranu existují projekty, které velmi rychle zvyšují hlavní číslo verze, aby vypadaly vyspělejší nebo aktuálnější, nebo možná prostě proto, že jim na zpětné kompatibilitě „nezáleží“! Jedním z nejslavnějších příkladů boje o verzi bylo [Chrome vs. Firefox](https://sedimental.org/designing_a_version.html#case-study-chrome-vs-firefox) o nejvyšší číslo verze. Takže – pokud je hlavní verze spíš otázkou marketingu než skutečné stability API, má vůbec smysl se jí zabývat?

Garance kompatibility je také velmi ošemetná věc – pokud je váš projekt dostatečně populární, jakákoli změna, která nějak ovlivní jeho chování, [někomu rozbije kód](https://xkcd.com/1172/), i když API zůstane stejné. Nikdy nebudete schopni předvídat očekávání a předpoklady ostatních.

Existují také velmi dobré a vyvážené [obhajoby myšlenky SemVer](https://caremad.io/posts/2016/02/versioning-software/), které jsme zvažovali, ale nakonec jsme se rozhodli přejít na kalendářní verzování, konkrétně variantu `YYYY.MINOR.MICRO`, a to z následujících důvodů:

1. bez ohledu na to, jak moc se budeme snažit, nikdy nebudeme schopni zaručit zpětnou kompatibilitu – pokud nemáme velmi důkladnou testovací sadu, která pokrývá všechny možné případy použití, což evitaDB nemá a pravděpodobně nikdy mít nebude (buďme upřímní)
2. zavazujeme se snažit udržovat zpětnou kompatibilitu, a pokud ji vědomě porušíme, označíme vydání štítkem „breaking change“ (to už děláme na úrovni issue) a jakmile projekt opustí fázi „pre-release“, budeme se snažit konsolidovat zásadní změny do větších milníků.
3. pokud se změní pouze část `MICRO`, můžete si být jisti, že změny ve vydání jsou pouze opravy, které mají být zpětně kompatibilní.
4. pokud se změní část `MINOR`, znamená to, že přibyly nové funkce – vždy byste si měli zkontrolovat poznámky k vydání, abyste zjistili, co je nového a zda obsahují breaking changes.
5. vždy byste měli mít vlastní testovací sadu, abyste ověřili, že nová verze vám funguje – pokud ji nemáte, stejně byste knihovnu neměli aktualizovat
6. část `YYYY` se mění automaticky s prvním novým `MINOR` vydáním v roce – protože naše knihovna je licencována pod BSL, můžete snadno odhadnout, zda knihovna, kterou používáte, je stále pod BSL, nebo už přešla na Apache License 2.0.
7. část `YYYY` vám také pomůže zjistit, jak stará je verze, kterou používáte, a snadno identifikovat, zda pro ni poskytujeme bezpečnostní aktualizace a opravy (pokud s něčím takovým někdy přijdeme).

Takže CalVer pro nás dává smysl a uvidíme, jak to půjde. První verzi s tímto schématem vydáme v následujících dnech.