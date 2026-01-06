---
title: Odstraňování problémů
perex: Věci se stávají. A když nastane problém, je dobré být připraven. Tato kapitola vám má poskytnout znalosti a techniky pro diagnostiku problémů, když nastanou. Očekávejte, že tento článek bude rozšiřován, jakmile získáme více zkušeností s již nastalými situacemi.
date: '17.1.2023'
author: Ing. Jan Novotný
proofreading: done
preferredLang: java
commit: ccb0d5d50900bc53b89db18c46df73ddf40e95db
---
**Práce na dokumentu probíhají**

## Funkční problémy

### Dotaz vrací neplatný výsledek

### Webové API neodpovídá

## Výkonnostní problémy

### Dotaz je pomalý

### Zobrazování stránky je pomalé

### Databáze je celkově pomalá

Nejprve se podívejte na [nástroje pro pozorovatelnost](../../operate/observe.md) a ověřte:

- kolik dotazů je zpracováno za sekundu
- jaké background joby běží a jak dlouho
- kolik položek je načítáno z diskového úložiště
- jak moc jsou vyčerpány thread pooly
- které dotazy jsou nejpomalejší a která část zpracování dotazu zabírá nejvíce času

## Problémy se zdroji

### Datový adresář příliš narůstá

evitaDB používá [append-only storage](https://en.wikipedia.org/wiki/Append-only) vzor, a proto soubory databáze pouze narůstají a průběžně se plní historickými a smazanými daty. Aby se zabránilo zaplnění disku, je v jádru zabudován proces vakuování, který asynchronně čistí stará data, o kterých ví, že již nikdy nebudou použita nebo jsou mimo rozsah historických záznamů, které chce vlastník uchovávat.

Pokud adresář narůstá příliš nad očekávanou velikost pracovního datového souboru, je třeba zkontrolovat dvě věci:

1. **jaká [nastavení](../../operate/configure.md) jsou nastavena pro politiku vakuování** – pokud je evitaDB nakonfigurována tak, že uchovává příliš mnoho odpadu v datech nebo příliš dlouhou historii záznamů, může soubor narůst nad vaše očekávání
2. **zda proces vakuování [běží pravidelně](../../operate/observe.md)** – pokud je na systém vyvíjen velký tlak nebo je zpracováváno příliš mnoho zápisů, proces vakuování nemusí stíhat

Množství starých a nepotřebných dat spolu se statistikami procesu vakuování lze najít v [monitorovacím dashboardu](../../operate/observe.md).

<Note type="warning">
Proces vakuování zatím není implementován – sledujte [issue #41](https://github.com/FgForrest/evitaDB/issues/41).
Jakmile bude proces vakuování dokončen, budou do této kapitoly přidány přesné konfigurační možnosti a monitorovací metriky.
</Note>

### Je hlášeno nedostatek paměti

### Proces evitaDB je ukončen operačním systémem