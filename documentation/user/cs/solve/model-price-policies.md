---
title: Cenové politiky modelů
perex: Cenové politiky v B2B prostředí jsou často složité a vyžadují velkou dávku kreativity pro jejich přesné modelování při zachování efektivních a rychlých výpočtů. Tento článek obsahuje různé přístupy, které jsme v minulosti použili a které by pro vás mohly být užitečné. Očekáváme, že se tento článek bude v průběhu času rozšiřovat, jak budeme objevovat nové přístupy.
date: '25.2.2023'
author: Ing. Jan Novotný
proofreading: done
commit: '734e5a570edecd6d7697813622fe09fc1de1cd83'
translated: true
---
## Přirozené modelování

Ve většině scénářů existuje jeden hlavní ceník s prodejními cenami – obvykle jej nazýváme *základní*, který obsahuje ceny pro všechny produkty. Velmi často existuje také další ceník nazývaný *"referenční"*, který obsahuje referenční tržní ceny nebo nejnižší cenu za poslední období. Ceny v *"referenčním"* ceníku jsou označeny jako neprodejní a slouží ke srovnání se skutečnou prodejní cenou a k výpočtu slevy.

Dále existují různé další ceníky, které obvykle obsahují ceny pouze pro podmnožinu produktů a mají přednost před *základním* ceníkem. Tyto ceníky se obvykle používají pro speciální zákazníky, speciální produkty, speciální regiony apod.

Když je uživatel autentizován, systém vybere sadu ceníků relevantních pro daného uživatele a seřadí je podle priority. Tato sada je uložena v uživatelově relaci nebo v [JWT tokenu](https://en.wikipedia.org/wiki/JSON_Web_Token) a používá se pro všechny výpočty cen.

## Vyhněte se unikátnímu ceníku pro každého zákazníka

V některých případech je lákavé vytvořit unikátní ceník pro každého zákazníka. Doporučujeme tomuto přístupu se co nejdéle vyhýbat, protože není dobře škálovatelný. Počet ceníků roste s počtem zákazníků a produktů a databáze velmi rychle narůstá. Také není běžné, aby měl prodejce skutečně unikátní cenu za produkt pro každého zákazníka, protože by bylo velmi obtížné takovou databázi udržovat. Pokud se pokusíte odhalit pozadí tohoto mechanismu, obvykle zjistíte, že existují nějaká pravidla, která lze využít ke snížení počtu ceníků.

### Sleva na zákazníka

Někdy jsou ceny v B2B systémech počítány jako sleva ze základního ceníku. Prodejce se vás může snažit přesvědčit, že každý zákazník má unikátní slevu. V takovém případě se pokuste získat rozložení těchto slev a možná zjistíte, že nejvyšší sleva je 15 % a slevy jsou vždy zaokrouhleny na celá procenta. Tuto situaci lze poměrně snadno modelovat – místo ukládání slevy pro každého zákazníka můžete mít speciální ceník pro každou výši slevy (tím vznikne pouze 15 takových ceníků) a pak jen vyberete správný ceník podle slevy zákazníka.