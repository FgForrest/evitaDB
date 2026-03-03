---
title: Telemetrie
date: '7.12.2023'
perex: Když provozujete komplexní databázový systém, často potřebujete vědět, co se děje „pod kapotou“ databázového enginu, abyste mohli optimalizovat své dotazy a podobně. Telemetrie je sada nástrojů, která vám pomáhá pochopit, jak jsou vaše akce plánovány a prováděny.
author: Bc. Lukáš Hornych
proofreading: done
preferredLang: evitaql
commit: cabcf999e7be5b00e0b13e1228a76a8d9e91cb78
translated: true
---
## Telemetrie dotazu

<LS to="e,j,r,c">

```evitaql-syntax
queryTelemetry()
```

</LS>

Požadavek <LS to="j,e,r"><SourceClass>evita_query/src/main/java/io/evitadb/api/query/require/QueryTelemetry.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/Queries/Requires/QueryTelemetry.cs</SourceClass></LS>
<LS to="g">`queryTelemetry` pole s dodatečným výsledkem</LS>
vyžaduje vypočítanou telemetrii dotazu pro aktuální dotaz. Telemetrie obsahuje podrobné informace o době zpracování dotazu a jejím rozložení na jednotlivé operace.

Objekt telemetrie dotazu představuje jednu provedenou operaci, která může obsahovat vnořené další operace, a skládá se z následujících dat:

<dl>
	<dt>operation</dt>
	<dd>
		Fáze provádění dotazu.
		Možné hodnoty lze nalézt ve třídě <LS to="j,e,r,g"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/extraResult/QueryTelemetry.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/ExtraResults/QueryTelemetry.cs</SourceClass></LS>.
	</dd>
	<dt>start</dt>
	<dd>
		Datum a čas zahájení tohoto kroku v nanosekundách.
	</dd>
	<dt>steps</dt>
	<dd>
		Vnitřní kroky tohoto kroku telemetrie (dekompozice operace). Stejná struktura jako nadřazený objekt telemetrie.
	</dd>
	<dt>arguments</dt>
	<dd>
		Argumenty fáze zpracování.
	</dd>
	<dt>spentTime</dt>
	<dd>
		Doba trvání v nanosekundách.
	</dd>
</dl>

<LS to="g">
Ve výchozím nastavení jsou číselné hodnoty objektu telemetrie vraceny v surové podobě. Toto chování můžete změnit v GraphQL pomocí argumentu `format` na poli `queryTelemetry`. Tímto způsobem budou vráceny hodnoty v čitelném formátu.
</LS>

Pro ukázku informací, které telemetrie dotazu poskytuje, použijeme následující dotaz, který filtruje a řadí entity:

<SourceCodeTabs requires="evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Příklad dotazu pro výpočet telemetrie dotazu při komplexním filtrování a řazení](/documentation/user/en/query/requirements/examples/telemetry/queryTelemetry.evitaql)
</SourceCodeTabs>

<Note type="info">

<NoteTitle toggles="true">

##### Výsledná telemetrie dotazu pro filtrované a seřazené entity

</NoteTitle>

Výsledek obsahuje telemetrii dotazu a některé produkty (které jsme zde pro stručnost vynechali):

<LS to="e,j,c">

<MDInclude sourceVariable="extraResults.QueryTelemetry">[Výsledná telemetrie dotazu pro filtrované a seřazené entity](/documentation/user/en/query/requirements/examples/telemetry/queryTelemetryResult.evitaql.json.md)</MDInclude>

</LS>
<LS to="g">

<MDInclude sourceVariable="data.queryProduct.extraResults.queryTelemetry">[Výsledná telemetrie dotazu pro filtrované a seřazené entity](/documentation/user/en/query/requirements/examples/telemetry/queryTelemetryResult.graphql.json.md)</MDInclude>

</LS>
<LS to="r">

<MDInclude sourceVariable="extraResults.queryTelemetry">[Výsledná telemetrie dotazu pro filtrované a seřazené entity](/documentation/user/en/query/requirements/examples/telemetry/queryTelemetryResult.rest.json.md)</MDInclude>

</LS>

</Note>