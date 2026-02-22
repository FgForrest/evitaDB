---
title: Řídicí engine
perex: Databázový engine evitaDB lze plně ovládat programově prostřednictvím jeho řídicího API. Toto API umožňuje vývojářům spravovat katalogy, monitorovat stav engine a provádět různé administrativní úkoly přímo z jejich aplikací. Naše webová konzole evitaLab používá toto API pro veškeré své správcovské funkce. Tento dokument poskytuje přehled všech podporovaných operací na úrovni engine, které máte k dispozici.
date: '31.10.2025'
author: Ing. Jan Novotný
proofreading: needed
preferredLang: java
commit: faa71a48109132baded6fea7c9852354a01ab9e0
translated: 'true'
---
<LS to="j">
Engine API je přístupné z hlavního rozhraní <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass>. Řídicí metody obvykle existují ve dvou variantách – jedna je asynchronní, vrací <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/progress/Progress.java</SourceClass>, a druhá je synchronní blokující varianta. Asynchronní metody mají ve svém názvu příponu `WithProgress`.

Ne blokující varianty vrací objekt `Progress`, který vám umožňuje sledovat průběh operace a přistupovat k jejímu [CompletionStage](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/CompletionStage.html) pomocí metody `onCompletion()`. Synchronní metody blokují aktuální vlákno, dokud není operace dokončena, a výsledek vrací přímo, ale v pozadí také využívají asynchronní variantu.

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Oživení katalogu asynchronním způsobem](/documentation/user/en/use/api/example/engine-control-nonblocking.java)

</SourceCodeTabs>

Nyní se podívejme, jak definovat nový katalog a učinit jej neaktivním pomocí blokujícího přístupu:

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java" langSpecificTabOnly local>

[Definování nového katalogu a jeho deaktivace blokujícím způsobem](/documentation/user/en/use/api/example/engine-control-blocking.java)

</SourceCodeTabs>

Existuje také obecná metoda `applyMutation`, která přijímá řídicí mutace enginu a vrací <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/progress/Progress.java</SourceClass>. Tato metoda je skutečnou implementací všech řídicích operací. Každá řídicí operace má svou vlastní třídu mutace, která se této metodě předává.

<SourceCodeTabs setup="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Oživení katalogu pomocí obecné metody](/documentation/user/en/use/api/example/engine-control-generic.java)

</SourceCodeTabs>

Na úrovni enginu jsou podporovány následující operace:

<dl>
    <dt>Vytvoření nového katalogu</dt>
    <dd>Definuje nový katalog v rámci enginu. Je třeba zadat název katalogu a jeho počáteční definici schématu.</dd>
    <dt>Duplikace existujícího katalogu</dt>
    <dd>Vytvoří kopii existujícího katalogu pod novým názvem. Duplikovaný katalog je binární kopií původního katalogu a je vytvořen ve stavu "neaktivní", což znamená, že jej nelze okamžitě dotazovat ani aktualizovat, dokud jej neaktivujete.</dd>
    <dt>Změna názvu katalogu</dt>
    <dd>Změní název existujícího katalogu na nový. Můžete také použít název jiného existujícího katalogu pro jeho nahrazení pojmenovaným katalogem, ale tuto operaci nahrazení musíte potvrdit nastavením `overwriteTarget` na `true`.</dd>
    <dt>Úprava schématu katalogu</dt>
    <dd>Umožňuje upravit schéma katalogu. Tato mutace není povolena jako řídicí mutace enginu — je použitelná pouze v kontextu konkrétního katalogu a vyžaduje spuštění přes <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass>.</dd>
    <dt>Oživení katalogu</dt>
    <dd>Umožňuje přechod katalogu ze stavu `WARMING_UP` do stavu `ALIVE`. Katalog ve stavu `ALIVE` znamená, že je plně naplněn do svého počátečního stavu a je připraven k dotazování. Také všechny mutace katalogu jsou prováděny transakčně.</dd>
    <dt>Obnovení katalogu</dt>
    <dd>Tato operace je interní pro engine a není určena k běžnému použití. Zavádí nový "neaktivní" katalog do enginu. Obsah katalogu musí být již přítomen ve správném stavu ve složce s daty v souborovém systému. Tato operace pouze "odhalí" katalog systému.</dd>
    <dt>Nastavení mutability</dt>
    <dd>Umožňuje přepínat konkrétní katalog mezi režimem `read-only` a `read-write`. Pokud je katalog v režimu `read-only`, není povoleno vytvářet nové relace v režimu `read-write` a provádět změny v katalogu. Tato mutace enginu neovlivňuje aktuálně otevřené relace `read-write`, ani je nenutí k uzavření.</dd>
    <dt>Nastavení stavu katalogu</dt>
    <dd>Umožňuje načíst nebo uvolnit obsah katalogu do/paměti. Katalogy ve stavu "aktivní" mají všechna svá klíčová data v paměti a lze je dotazovat a aktualizovat. "Neaktivní" katalogy naopak leží nečinně v trvalém úložišti, nespotřebovávají žádné systémové prostředky, ale také je nelze dotazovat ani aktualizovat.</dd>
</dl>

<Note type="info">

<NoteTitle toggles="false">

##### Seznam všech řídicích mutací enginu
</NoteTitle>

- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/engine/CreateCatalogSchemaMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/engine/DuplicateCatalogMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/engine/MakeCatalogAliveMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/engine/ModifyCatalogSchemaMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/engine/ModifyCatalogSchemaNameMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/engine/RestoreCatalogSchemaMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/engine/SetCatalogMutabilityMutation.java</SourceClass>**
- **<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/schema/mutation/engine/SetCatalogStateMutation.java</SourceClass>**

</Note>

</LS>
<LS to="e,r,g,c">
Řízení enginu je aktuálně podporováno pouze v [Javě](control-engine.md?lang=java).
</LS>