---
commit: '6f067f6807adc51959f1921abade4fe252f5721e'
---
### Metriky

<UsedTerms>
  <h4>Popisky používané v metrikách</h4>
  <dl>
    <dt>api</dt>
    <dd><strong>Typ API</strong>: Identifikace API, které je testováno.</dd>
    <dt>area</dt>
    <dd><strong>Oblast</strong>: Oblast, pro kterou jsou publikovány události.</dd>
    <dt>buildType</dt>
    <dd><strong>Typ sestavení</strong>: Typ sestavení instance: NEW nebo REFRESH</dd>
    <dt>catalogName</dt>
    <dd><strong>Katalog</strong>: Název katalogu, ke kterému je tato událost/metrika přiřazena.</dd>
    <dt>entityType</dt>
    <dd><strong>Typ entity</strong>: Název souvisejícího typu entity (kolekce).</dd>
    <dt>fileType</dt>
    <dd><strong>Typ souboru</strong>: Typ souboru, který byl zapsán. Jedna z možností: CATALOG, ENTITY_COLLECTION, WAL nebo BOOTSTRAP</dd>
    <dt>graphQLInstanceType</dt>
    <dd><strong>Typ instance GraphQL</strong>: Doména GraphQL API použitého v souvislosti s touto událostí/metrikou: SYSTEM, SCHEMA nebo DATA</dd>
    <dt>graphQLOperationType</dt>
    <dd><strong>Typ operace GraphQL</strong>: Typ operace specifikované v GQL požadavku: QUERY, MUTATION nebo SUBSCRIPTION.</dd>
    <dt>grpcResponseStatus</dt>
    <dd><strong>Stav odpovědi gRPC</strong>: Stav odpovědi gRPC (OK, ERROR, CANCELED).</dd>
    <dt>httpMethod</dt>
    <dd><strong>HTTP metoda</strong>: HTTP metoda požadavku.</dd>
    <dt>httpStatusCode</dt>
    <dd><strong>HTTP status kód</strong>: HTTP status kód odpovědi, který byl odeslán klientovi.</dd>
    <dt>initiator</dt>
    <dd><strong>Iniciátor volání</strong>: Iniciátor gRPC volání (klient nebo server).</dd>
    <dt>instanceId</dt>
    <dd><strong>ID instance serveru</strong>: Unikátní název serveru převzatý z konfiguračního souboru.</dd>
    <dt>name</dt>
    <dd><strong>Logický název souboru</strong>: Logický název souboru, který byl zapsán. Přesněji identifikuje soubor.</dd>
    <dt>operationId</dt>
    <dd><strong>ID operace</strong>: ID provedené operace.</dd>
    <dt>operationName</dt>
    <dd><strong>Operace GraphQL</strong>: Název operace specifikované v GQL požadavku.</dd>
    <dt>prefetched</dt>
    <dd><strong>Přednačtený vs. nepřednačtený dotaz</strong>: Zda dotaz využil přednačítací plán. Přednačítací plán optimisticky načte dotazované entity předem a provádí operace přímo na nich (bez přístupu k indexům).</dd>
    <dt>probeResult</dt>
    <dd><strong>Výsledek sondy</strong>: Výsledek readiness sondy (ok, timeout, error).</dd>
    <dt>procedureName</dt>
    <dd><strong>Název procedury</strong>: Název volané gRPC procedury (název metody).</dd>
    <dt>prospective</dt>
    <dd><strong>Perspektiva (klient/server)</strong>: Určuje, zda událost reprezentuje pohled serveru nebo klienta na readiness.
Klientský pohled je doba vnímaná z pohledu HTTP klienta ovlivněná timeouty, serverový pohled je skutečná doba trvání sondy.</dd>
    <dt>recordType</dt>
    <dd><strong>Typ záznamu</strong>: Typ záznamů, které se změnily v OffsetIndex.</dd>
    <dt>requestResult</dt>
    <dd><strong>Výsledek požadavku</strong>: Zjednodušený výsledek požadavku (success, error, cancelled).</dd>
    <dt>resolution</dt>
    <dd><strong>Výsledek transakce</strong>: Výsledek transakce (commit nebo rollback).</dd>
    <dt>responseStatus</dt>
    <dd><strong>Stav odpovědi</strong>: Stav odpovědi: OK nebo ERROR.</dd>
    <dt>restInstanceType</dt>
    <dd><strong>Typ instance REST</strong>: Doména REST API použitého v souvislosti s touto událostí/metrikou: SYSTEM nebo CATALOG</dd>
    <dt>restOperationType</dt>
    <dd><strong>Typ operace REST</strong>: Typ provedené operace. Jedna z možností: QUERY, MUTATION.</dd>
    <dt>serverVersion</dt>
    <dd><strong>Verze serveru</strong>: Přesná verze serveru evitaDB.</dd>
    <dt>serviceName</dt>
    <dd><strong>Název služby</strong>: Název volané gRPC služby (název Java třídy).</dd>
    <dt>stage</dt>
    <dd><strong>Fáze transakce</strong>: Název fáze, na kterou transakce čeká.</dd>
    <dt>taskName</dt>
    <dd><strong>Název úlohy</strong>: Název background úlohy.</dd>
  </dl>
</UsedTerms>

#### API

<dl>
  <dt><code>io_evitadb_external_api_readiness_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba trvání readiness sondy<br/><br/><strong>Popisky:</strong> <Term>api</Term>, <Term>probeResult</Term>, <Term>prospective</Term><br/></dd>
  <dt><code>io_evitadb_external_api_readiness_total</code> (COUNTER)</dt>
  <dd>Celkový počet vyvolání readiness sondy<br/><br/><strong>Popisky:</strong> <Term>api</Term>, <Term>probeResult</Term>, <Term>prospective</Term><br/></dd>
  <dt><code>io_evitadb_external_api_request_total</code> (COUNTER)</dt>
  <dd>Celkový počet požadavků<br/><br/><strong>Popisky:</strong> <Term>api</Term>, <Term>httpStatusCode</Term>, <Term>requestResult</Term><br/></dd>
</dl>

#### API / GraphQL / Instance / Schéma

<dl>
  <dt><code>io_evitadb_external_api_graphql_instance_built_graph_ql_instance_build_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba sestavení API</strong>: Doba sestavení jedné API v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>graphQLInstanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_instance_built_graph_ql_schema_build_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba sestavení GraphQL schématu</strong>: Doba sestavení jednoho schématu GraphQL API v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>graphQLInstanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_instance_built_graph_ql_schema_dsl_lines</code> (GAUGE)</dt>
  <dd><strong>Počet řádků</strong>: Počet řádků vygenerovaných v sestaveném GraphQL schema DSL.<br/><br/><strong>Popisky:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>graphQLInstanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_instance_built_total</code> (COUNTER)</dt>
  <dd>Celkový počet sestavených GraphQL instancí<br/><br/><strong>Popisky:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>graphQLInstanceType</Term><br/></dd>
</dl>
#### API / gRPC

<dl>
  <dt><code>io_evitadb_api_grpc_evita_procedure_called_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba trvání volání gRPC evitaDB procedury<br/><br/><strong>Popisky:</strong> <Term>grpcResponseStatus</Term>, <Term>initiator</Term>, <Term>procedureName</Term>, <Term>serviceName</Term><br/></dd>
  <dt><code>io_evitadb_api_grpc_evita_procedure_called_total</code> (COUNTER)</dt>
  <dd>Celkový počet volání gRPC evitaDB procedury<br/><br/><strong>Popisky:</strong> <Term>grpcResponseStatus</Term>, <Term>initiator</Term>, <Term>procedureName</Term>, <Term>serviceName</Term><br/></dd>
  <dt><code>io_evitadb_api_grpc_session_procedure_called_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba trvání volání gRPC session procedury<br/><br/><strong>Popisky:</strong> <Term>grpcResponseStatus</Term>, <Term>initiator</Term>, <Term>procedureName</Term>, <Term>serviceName</Term><br/></dd>
  <dt><code>io_evitadb_api_grpc_session_procedure_called_total</code> (COUNTER)</dt>
  <dd>Celkový počet volání gRPC session procedury<br/><br/><strong>Popisky:</strong> <Term>grpcResponseStatus</Term>, <Term>initiator</Term>, <Term>procedureName</Term>, <Term>serviceName</Term><br/></dd>
</dl>

#### CDC

<dl>
  <dt><code>io_evitadb_cdc_change_catalog_capture_statistics_events_published_total</code> (COUNTER)</dt>
  <dd><strong>Publikované události</strong>: Počet událostí publikovaných všem odběratelům.</dd>
  <dt><code>io_evitadb_cdc_change_catalog_capture_statistics_lagging_subscribers</code> (GAUGE)</dt>
  <dd><strong>Zpoždění odběratelé</strong>: Počet odběratelů načítajících záznamy WAL.</dd>
  <dt><code>io_evitadb_cdc_change_catalog_capture_statistics_per_area_events_published_total</code> (COUNTER)</dt>
  <dd><strong>Publikované události</strong>: Počet událostí publikovaných všem odběratelům.<br/><br/><strong>Popisky:</strong> <Term>area</Term><br/></dd>
  <dt><code>io_evitadb_cdc_change_catalog_capture_statistics_per_entity_type_events_published_total</code> (COUNTER)</dt>
  <dd><strong>Publikované události</strong>: Počet událostí publikovaných všem odběratelům.<br/><br/><strong>Popisky:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_cdc_change_catalog_capture_statistics_shared_publishers</code> (GAUGE)</dt>
  <dd><strong>Počet publisherů</strong>: Počet aktivních sdílených publisherů v systému.</dd>
  <dt><code>io_evitadb_cdc_change_catalog_capture_statistics_subscribers</code> (GAUGE)</dt>
  <dd><strong>Počet odběratelů</strong>: Počet aktivních odběratelů v systému.</dd>
</dl>

#### Cache

<dl>
  <dt><code>io_evitadb_cache_anteroom_record_statistics_updated_records</code> (GAUGE)</dt>
  <dd><strong>Počet záznamů čekajících v předpokoji</strong>: Počet záznamů vhodných pro cache, ale dosud nezařazených do cache, které sbírají statistiky využití pro vyhodnocení zařazení do cache.</dd>
  <dt><code>io_evitadb_cache_anteroom_wasted_total</code> (COUNTER)</dt>
  <dd>Předpokoj promarněn celkem</dd>
</dl>

#### ExternalAPI / GraphQL / Request

<dl>
  <dt><code>io_evitadb_external_api_graphql_request_executed_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba trvání zpracování GraphQL požadavku<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_execution_api_overhead_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Režie zpracování požadavku</strong>: Čas potřebný ke zpracování celého požadavku v milisekundách bez interního provedení evitaDB.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_input_deserialization_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba deserializace vstupu</strong>: Čas potřebný k deserializaci příchozího JSON vstupu GraphQL požadavku do interní struktury v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_internal_evitadb_input_reconstruction_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba rekonstrukce vstupu evitaDB</strong>: Čas potřebný k rekonstrukci vstupu dotazu do jádra evitaDB v milisekundách. Obvykle převádí JSON dotaz na interní reprezentaci dotazu evitaDB nebo JSON mutace na interní reprezentaci mutace evitaDB.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_operation_execution_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba provedení</strong>: Čas potřebný k provedení celé parsované a validované GraphQL operace enginem GraphQL serveru v milisekundách. Zahrnuje veškerou business logiku data fetcherů, včetně rekonstrukce vstupu evitaDB a provedení dotazu evitaDB.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_parse_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba parsování požadavku</strong>: Čas potřebný k parsování GraphQL požadavku (dotaz a proměnné) enginem GraphQL serveru z interní struktury pro validaci a provedení v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_preparation_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba přípravy požadavku</strong>: Čas potřebný k přípravě a inicializaci enginu GraphQL serveru pro parsování a provedení příchozího požadavku v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_result_serialization_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba serializace výsledku</strong>: Čas potřebný k serializaci výsledku požadavku do výstupního JSON v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_root_fields_processed</code> (GAUGE)</dt>
  <dd><strong>Počet root fields požadavku</strong>: Počet root fields (dotazy, mutace) zpracovaných v rámci jednoho GraphQL požadavku.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_total</code> (COUNTER)</dt>
  <dd>Celkový počet provedených GraphQL požadavků<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_validation_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba validace</strong>: Čas potřebný k validaci parsovaného požadavku (dotaz a proměnné) enginem GraphQL serveru před provedením v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
</dl>

#### ExternalAPI / REST / Instance / Schema

<dl>
  <dt><code>io_evitadb_external_api_rest_instance_built_registered_rest_endpoints</code> (GAUGE)</dt>
  <dd><strong>Počet endpointů</strong>: Počet registrovaných endpointů v sestaveném OpenAPI schématu<br/><br/><strong>Popisky:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>restInstanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_instance_built_rest_instance_build_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba sestavení API</strong>: Doba sestavení jedné API v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>restInstanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_instance_built_rest_schema_build_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba sestavení REST schématu</strong>: Doba sestavení jednoho schématu REST API v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>restInstanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_instance_built_rest_schema_dsl_lines</code> (GAUGE)</dt>
  <dd><strong>Počet řádků</strong>: Počet řádků vygenerovaných v sestaveném REST schema DSL.<br/><br/><strong>Popisky:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>restInstanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_instance_built_total</code> (COUNTER)</dt>
  <dd>Celkový počet sestavených REST API instancí<br/><br/><strong>Popisky:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>restInstanceType</Term><br/></dd>
</dl>

#### ExternalAPI / REST / Request

<dl>
  <dt><code>io_evitadb_external_api_rest_request_executed_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba trvání zpracování REST požadavku<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>operationId</Term>, <Term>responseStatus</Term>, <Term>restInstanceType</Term>, <Term>restOperationType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_request_executed_execution_api_overhead_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Režie zpracování požadavku</strong>: Čas potřebný ke zpracování požadavku v milisekundách bez interního provedení evitaDB.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>operationId</Term>, <Term>responseStatus</Term>, <Term>restInstanceType</Term>, <Term>restOperationType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_request_executed_input_deserialization_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba deserializace vstupu</strong>: Čas potřebný k deserializaci příchozího JSON vstupu REST požadavku do interní struktury v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>operationId</Term>, <Term>responseStatus</Term>, <Term>restInstanceType</Term>, <Term>restOperationType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_request_executed_internal_evitadb_input_reconstruction_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba rekonstrukce vstupu evitaDB</strong>: Čas potřebný k rekonstrukci vstupu dotazu do jádra evitaDB v milisekundách. Obvykle převádí JSON dotaz na interní reprezentaci dotazu evitaDB nebo JSON mutace na interní reprezentaci mutace evitaDB.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>operationId</Term>, <Term>responseStatus</Term>, <Term>restInstanceType</Term>, <Term>restOperationType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_request_executed_operation_execution_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba provedení</strong>: Čas potřebný k provedení celé parsované a validované REST operace enginem serveru v milisekundách. Zahrnuje veškerou logiku handlerů, včetně rekonstrukce vstupu evitaDB a provedení dotazu evitaDB.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>operationId</Term>, <Term>responseStatus</Term>, <Term>restInstanceType</Term>, <Term>restOperationType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_request_executed_result_serialization_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba serializace výsledku</strong>: Čas potřebný k serializaci výsledku požadavku do výstupního JSON v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>operationId</Term>, <Term>responseStatus</Term>, <Term>restInstanceType</Term>, <Term>restOperationType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_request_executed_total</code> (COUNTER)</dt>
  <dd>Celkový počet provedených REST požadavků<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>operationId</Term>, <Term>responseStatus</Term>, <Term>restInstanceType</Term>, <Term>restOperationType</Term><br/></dd>
</dl>

#### Dotaz

<dl>
  <dt><code>io_evitadb_query_entity_enrich_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba obohacení entity v milisekundách<br/><br/><strong>Popisky:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_enrich_records</code> (COUNTER)</dt>
  <dd><strong>Celkem obohacených záznamů</strong>: Celkový počet záznamů, které byly obohaceny.<br/><br/><strong>Popisky:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_enrich_size_bytes</code> (HISTOGRAM)</dt>
  <dd><strong>Velikost obohacení v bajtech</strong>: Velikost v bajtech dodatečně načtených a obohacených dat.<br/><br/><strong>Popisky:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_enrich_total</code> (COUNTER)</dt>
  <dd>Obohacené entity<br/><br/><strong>Popisky:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_fetch_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba načtení entity v milisekundách<br/><br/><strong>Popisky:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_fetch_records</code> (COUNTER)</dt>
  <dd><strong>Celkem načtených záznamů</strong>: Celkový počet záznamů, které byly načteny.<br/><br/><strong>Popisky:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_fetch_size_bytes</code> (HISTOGRAM)</dt>
  <dd><strong>Velikost načtených dat v bajtech</strong>: Celková velikost načtených dat v bajtech.<br/><br/><strong>Popisky:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_fetch_total</code> (COUNTER)</dt>
  <dd>Načtené entity<br/><br/><strong>Popisky:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba trvání dotazu v milisekundách<br/><br/><strong>Popisky:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_estimated</code> (HISTOGRAM)</dt>
  <dd><strong>Odhadovaná složitost dotazu</strong>: Odhadovaná složitost dotazu.<br/><br/><strong>Popisky:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_execution_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba provedení dotazu v milisekundách</strong>: Čas potřebný k provedení vybraného prováděcího plánu pro dotaz.<br/><br/><strong>Popisky:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_fetched</code> (HISTOGRAM)</dt>
  <dd><strong>Celkem načtených záznamů</strong>: Celkový počet záznamů načtených z datového úložiště (kromě záznamů nalezených v cache).<br/><br/><strong>Popisky:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_fetched_size_bytes</code> (HISTOGRAM)</dt>
  <dd><strong>Velikost načtených dat v bajtech</strong>: Celková velikost načtených dat v bajtech.<br/><br/><strong>Popisky:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_found</code> (HISTOGRAM)</dt>
  <dd><strong>Celkem nalezených záznamů</strong>: Celkový počet nalezených záznamů (odpovídajících dotazu).<br/><br/><strong>Popisky:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_plan_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba plánování dotazu v milisekundách</strong>: Čas potřebný k sestavení všech variant prováděcího plánu dotazu.<br/><br/><strong>Popisky:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_real</code> (HISTOGRAM)</dt>
  <dd><strong>Složitost filtru</strong>: Skutečná složitost dotazu.<br/><br/><strong>Popisky:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_returned</code> (HISTOGRAM)</dt>
  <dd><strong>Celkem vrácených záznamů</strong>: Celkový počet záznamů vrácených (zahrnutých ve výsledku).<br/><br/><strong>Popisky:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_scanned</code> (HISTOGRAM)</dt>
  <dd><strong>Celkem prohledaných záznamů</strong>: Celkový počet záznamů prohledaných (zahrnutých do výpočtu).<br/><br/><strong>Popisky:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_total</code> (COUNTER)</dt>
  <dd>Dokončené dotazy<br/><br/><strong>Popisky:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_store_traffic_traffic_recorder_statistics_created_sessions</code> (COUNTER)</dt>
  <dd><strong>Vytvořené relace</strong>: Vytvořené relace.</dd>
  <dt><code>io_evitadb_store_traffic_traffic_recorder_statistics_dropped_sessions</code> (COUNTER)</dt>
  <dd><strong>Zahozené relace</strong>: Počet zahozených relací kvůli nedostatku paměti.</dd>
  <dt><code>io_evitadb_store_traffic_traffic_recorder_statistics_finished_sessions</code> (COUNTER)</dt>
  <dd><strong>Dokončené relace</strong>: Zaznamenané relace.</dd>
  <dt><code>io_evitadb_store_traffic_traffic_recorder_statistics_missed_records</code> (COUNTER)</dt>
  <dd><strong>Chybějící záznamy</strong>: Počet chybějících záznamů kvůli nedostatku paměti nebo vzorkování.</dd>
</dl>

#### Relace

<dl>
  <dt><code>io_evitadb_session_closed_active_sessions</code> (GAUGE)</dt>
  <dd><strong>Počet stále aktivních relací</strong>: Počet stále aktivních relací v okamžiku uzavření této relace.</dd>
  <dt><code>io_evitadb_session_closed_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba trvání relace v milisekundách</dd>
  <dt><code>io_evitadb_session_closed_mutations</code> (HISTOGRAM)</dt>
  <dd><strong>Počet provedených mutací v relaci</strong>: Počet mutací provedených během této relace.</dd>
  <dt><code>io_evitadb_session_closed_oldest_session_timestamp_seconds</code> (GAUGE)</dt>
  <dd><strong>Časové razítko nejstarší relace</strong>: Časové razítko nejstarší relace v okamžiku jejího uzavření.</dd>
  <dt><code>io_evitadb_session_closed_queries</code> (HISTOGRAM)</dt>
  <dd><strong>Počet provedených dotazů v relaci</strong>: Počet požadavků provedených během této relace.</dd>
  <dt><code>io_evitadb_session_closed_total</code> (COUNTER)</dt>
  <dd>Uzavřené relace</dd>
  <dt><code>io_evitadb_session_killed_total</code> (COUNTER)</dt>
  <dd>Ukončené relace</dd>
  <dt><code>io_evitadb_session_opened_total</code> (COUNTER)</dt>
  <dd>Otevřené relace</dd>
</dl>

#### Úložiště

<dl>
  <dt><code>io_evitadb_storage_catalog_statistics_entity_collections</code> (GAUGE)</dt>
  <dd><strong>Počet kolekcí entit</strong>: Počet aktivních kolekcí entit (typů entit) v katalogu.</dd>
  <dt><code>io_evitadb_storage_catalog_statistics_occupied_disk_space_bytes</code> (GAUGE)</dt>
  <dd><strong>Celkový obsazený diskový prostor v bajtech</strong>: Celkové množství diskového prostoru využitého katalogem v bajtech.</dd>
  <dt><code>io_evitadb_storage_catalog_statistics_oldest_catalog_version_timestamp_seconds</code> (GAUGE)</dt>
  <dd><strong>Časové razítko nejstarší dostupné verze katalogu v sekundách</strong>: Stáří nejstarší dostupné verze katalogu v sekundách. Tato hodnota určuje, jak daleko do minulosti lze v katalogu jít.</dd>
  <dt><code>io_evitadb_storage_data_file_compact_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba kompakce OffsetIndex.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_data_file_compact_total</code> (COUNTER)</dt>
  <dd>Kompakce OffsetIndex.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_observable_output_change_occupied_memory_bytes</code> (GAUGE)</dt>
  <dd><strong>Paměť obsazená otevřenými výstupními buffery v bajtech</strong>: Množství paměti v bajtech obsazené otevřenými výstupními buffery OffsetIndex.</dd>
  <dt><code>io_evitadb_storage_observable_output_change_opened_buffers</code> (GAUGE)</dt>
  <dd><strong>Počet otevřených výstupních bufferů</strong>: Počet otevřených bufferů používaných pro zápis dat do OffsetIndexů.</dd>
  <dt><code>io_evitadb_storage_observable_output_change_total</code> (COUNTER)</dt>
  <dd>Změny počtu bufferů ObservableOutput.</dd>
  <dt><code>io_evitadb_storage_offset_index_flush_active_disk_size_bytes</code> (GAUGE)</dt>
  <dd><strong>Aktivní část velikosti disku v bajtech</strong>: Velikost aktivní části OffsetIndex na disku v bajtech.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_active_records</code> (GAUGE)</dt>
  <dd><strong>Počet aktivních záznamů</strong>: Počet aktivních (přístupných) záznamů v OffsetIndex.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_disk_size_bytes</code> (GAUGE)</dt>
  <dd><strong>Velikost OffsetIndex na disku v bajtech</strong>: Velikost OffsetIndex na disku v bajtech.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba zápisu OffsetIndex na disk.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_estimated_memory_size_bytes</code> (GAUGE)</dt>
  <dd><strong>Odhadovaná velikost paměti v bajtech</strong>: Odhadovaná velikost OffsetIndex v paměti v bajtech.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_max_record_size</code> (GAUGE)</dt>
  <dd><strong>Největší záznam v bajtech</strong>: Velikost největšího záznamu v OffsetIndex v bajtech.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_oldest_record_timestamp_seconds</code> (GAUGE)</dt>
  <dd><strong>Časové razítko nejstaršího záznamu v paměti v sekundách</strong>: Časové razítko nejstaršího volatilního záznamu v paměti v sekundách. Volatilní záznamy jsou záznamy, které ještě nebyly zapsány na disk.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_total</code> (COUNTER)</dt>
  <dd>Zápisy OffsetIndex na disk.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_history_kept_oldest_record_timestamp_seconds</code> (GAUGE)</dt>
  <dd><strong>Časové razítko nejstaršího záznamu v paměti v sekundách</strong>: Časové razítko nejstarších dat verze katalogu držených v paměti v sekundách. Data z předchozích verzí se používají k udržení kontraktu SNAPSHOT izolace pro aktuálně otevřené relace zaměřené na starší verze katalogu. Nula, pokud nejsou uchovávána žádná data.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_non_flushed_record_size_bytes</code> (GAUGE)</dt>
  <dd><strong>Velikost záznamů čekajících na zápis v bajtech</strong>: Velikost záznamů čekajících na zápis v OffsetIndex v bajtech.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_non_flushed_records</code> (GAUGE)</dt>
  <dd><strong>Počet záznamů čekajících na zápis</strong>: Počet volatilních záznamů čekajících na zápis v OffsetIndex.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_record_type_count_changed_records</code> (GAUGE)</dt>
  <dd><strong>Počet záznamů</strong>: Celkový počet záznamů daného typu v OffsetIndex.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term>, <Term>recordType</Term><br/></dd>
  <dt><code>io_evitadb_storage_read_only_handle_closed_total</code> (COUNTER)</dt>
  <dd>Uzavřené handly pro čtení souborů.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_read_only_handle_opened_total</code> (COUNTER)</dt>
  <dd>Otevřené handly pro čtení souborů.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
</dl>

#### Systém

<dl>
  <dt><code>io_evitadb_system_background_task_finished_total</code> (COUNTER)</dt>
  <dd>Dokončené background úlohy<br/><br/><strong>Popisky:</strong> <Term>taskName</Term><br/></dd>
  <dt><code>io_evitadb_system_background_task_rejected_total</code> (COUNTER)</dt>
  <dd>Zamítnuté background úlohy<br/><br/><strong>Popisky:</strong> <Term>taskName</Term><br/></dd>
  <dt><code>io_evitadb_system_background_task_started_total</code> (COUNTER)</dt>
  <dd>Zahájené background úlohy<br/><br/><strong>Popisky:</strong> <Term>taskName</Term><br/></dd>
  <dt><code>io_evitadb_system_background_task_timed_out_timed_out_tasks</code> (COUNTER)</dt>
  <dd><strong>Úlohy s timeoutem</strong>: Počet úloh, které vypršely a byly zrušeny.<br/><br/><strong>Popisky:</strong> <Term>taskName</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_cache_anteroom_record_limit</code> (GAUGE)</dt>
  <dd><strong>Maximální počet záznamů v cache předpokoji</strong>: Nastavený limit pro maximální počet záznamů v cache předpokoji (`cache.anteroomRecordCount`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_cache_reevaluation_seconds</code> (GAUGE)</dt>
  <dd><strong>Interval přehodnocení cache v sekundách</strong>: Nastavený limit pro interval přehodnocení cache v sekundách (`cache.reevaluateEachSeconds`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_cache_size_in_bytes</code> (GAUGE)</dt>
  <dd><strong>Maximální velikost cache v bajtech</strong>: Nastavený limit pro maximální velikost cache v bajtech (`cache.cacheSizeInBytes`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_catalogs</code> (GAUGE)</dt>
  <dd><strong>Počet katalogů</strong>: Počet přístupných katalogů spravovaných touto instancí evitaDB.<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_close_sessions_after_seconds_of_inactivity</code> (GAUGE)</dt>
  <dd><strong>Uzavření relací po nečinnosti</strong>: Počet sekund, po kterých je relace uzavřena, pokud je nečinná.<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_compaction_file_size_threshold_bytes</code> (GAUGE)</dt>
  <dd><strong>Minimální velikost souboru pro spuštění komprese v bajtech</strong>: Nastavený limit pro minimální velikost souboru pro spuštění komprese v bajtech (`storage.fileSizeCompactionThresholdBytes`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_compaction_minimal_active_record_share_percent</code> (GAUGE)</dt>
  <dd><strong>Minimální procento aktivních záznamů v souboru pro spuštění kompakce v %.</strong>: Nastavený limit pro minimální procento aktivních záznamů v souboru pro spuštění kompakce v % (`storage.minimalActiveRecordShare`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_corrupted_catalogs</code> (GAUGE)</dt>
  <dd><strong>Počet poškozených katalogů</strong>: Počet poškozených katalogů, které evitaDB nemohla načíst.<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_inactive_catalogs</code> (GAUGE)</dt>
  <dd><strong>Počet neaktivních katalogů</strong>: Počet nepřístupných (nenačtených do paměti) katalogů přítomných ve složce úložiště této instance evitaDB.<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_query_timeout_seconds</code> (GAUGE)</dt>
  <dd><strong>Timeout pro read-only požadavky v sekundách</strong>: Nastavený limit pro timeout read-only požadavků v sekundách (`server.queryTimeoutInMilliseconds`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_read_only_handles_limit</code> (GAUGE)</dt>
  <dd><strong>Maximální počet otevřených read-only handle</strong>: Nastavený limit pro maximální počet otevřených read-only handle (`storage.maxOpenedReadHandles`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_request_max_threads</code> (GAUGE)</dt>
  <dd><strong>Maximální počet vláken pro read-only požadavky</strong>: Nastavený limit pro maximální počet vláken pro read-only požadavky (`server.requestThreadPool.maxThreadCount`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_request_max_threads_queue_size</code> (GAUGE)</dt>
  <dd><strong>Maximální velikost fronty pro read-only požadavky</strong>: Nastavený limit pro maximální velikost fronty pro read-only požadavky (`server.requestThreadPool.queueSize`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_service_max_threads</code> (GAUGE)</dt>
  <dd><strong>Maximální počet vláken pro servisní úlohy</strong>: Nastavený limit pro maximální počet vláken pro servisní úlohy (`server.serviceThreadPool.maxThreadCount`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_service_max_threads_queue_size</code> (GAUGE)</dt>
  <dd><strong>Maximální velikost fronty pro servisní úlohy</strong>: Nastavený limit pro maximální velikost fronty pro servisní úlohy (`server.serviceThreadPool.queueSize`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_session_max_inactive_age_seconds</code> (GAUGE)</dt>
  <dd><strong>Maximální doba nečinnosti relace v sekundách</strong>: Nastavený limit pro maximální dobu nečinnosti relace v sekundách (`server.closeSessionsAfterSecondsOfInactivity`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_time_travel_enabled</code> (GAUGE)</dt>
  <dd><strong>Time travel povoleno</strong>: Příznak, zda je povolen time travel.<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_total</code> (COUNTER)</dt>
  <dd>Celkový počet spuštění Evita<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_traffic_recording_enabled</code> (GAUGE)</dt>
  <dd><strong>Záznam provozu povolen</strong>: Příznak, zda je povolen záznam provozu.<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_transaction_flush_frequency_in_millis</code> (GAUGE)</dt>
  <dd><strong>Frekvence zápisu transakcí</strong>: Frekvence zápisu transakcí v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_transaction_max_threads</code> (GAUGE)</dt>
  <dd><strong>Maximální počet vláken pro read/write požadavky</strong>: Nastavený limit pro maximální počet vláken pro read/write požadavky (`server.transactionThreadPool.maxThreadCount`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_transaction_max_threads_queue_size</code> (GAUGE)</dt>
  <dd><strong>Maximální velikost fronty pro read/write požadavky</strong>: Nastavený limit pro maximální velikost fronty pro read/write požadavky (`server.transactionThreadPool.queueSize`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_transaction_memory_buffer_limit_size_bytes</code> (GAUGE)</dt>
  <dd><strong>Velikost off-heap bufferu pro transakce v bajtech</strong>: Nastavený limit pro velikost off-heap bufferu pro transakce v bajtech (`transaction.transactionMemoryBufferLimitSizeBytes`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_transaction_memory_regions</code> (GAUGE)</dt>
  <dd><strong>Počet off-heap paměťových regionů pro transakce</strong>: Nastavený limit pro počet off-heap paměťových regionů pro transakce (`transaction.transactionMemoryRegionCount`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_transaction_timeout_seconds</code> (GAUGE)</dt>
  <dd><strong>Timeout pro read/write požadavky v sekundách</strong>: Nastavený limit pro timeout read/write požadavků v sekundách (`server.transactionTimeoutInMilliseconds`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_wal_max_file_count_kept</code> (GAUGE)</dt>
  <dd><strong>Maximální počet uchovávaných WAL souborů</strong>: Nastavený limit pro maximální počet uchovávaných WAL souborů (`transaction.walFileCountKept`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_wal_max_file_size_bytes</code> (GAUGE)</dt>
  <dd><strong>Maximální velikost WAL souboru v bajtech</strong>: Nastavený limit pro maximální velikost WAL souboru v bajtech (`transaction.walFileSizeBytes`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_request_fork_join_pool_statistics_active</code> (GAUGE)</dt>
  <dd><strong>Aktivní pracovníci</strong>: Odhad počtu vláken, která aktuálně kradou nebo vykonávají úlohy</dd>
  <dt><code>io_evitadb_system_request_fork_join_pool_statistics_queued</code> (GAUGE)</dt>
  <dd><strong>Úlohy ve frontě</strong>: Odhad celkového počtu úloh aktuálně držených ve frontách pracovními vlákny</dd>
  <dt><code>io_evitadb_system_request_fork_join_pool_statistics_running</code> (GAUGE)</dt>
  <dd><strong>Běžící pracovníci</strong>: Odhad počtu pracovních vláken, která nejsou blokována čekáním na spojení úloh nebo na jiné synchronizační vlákna</dd>
  <dt><code>io_evitadb_system_request_fork_join_pool_statistics_steals</code> (COUNTER)</dt>
  <dd><strong>Ukradené úlohy</strong>: Odhad celkového počtu úloh ukradených z fronty jednoho vlákna jiným vláknem. Uváděná hodnota podhodnocuje skutečný počet ukradených úloh, pokud pool není v klidovém stavu</dd>
  <dt><code>io_evitadb_system_ring_buffer_statistics_items_accepted</code> (COUNTER)</dt>
  <dd><strong>Přijaté položky</strong>: Celkový počet položek přijatých do bufferu od jeho vytvoření.</dd>
  <dt><code>io_evitadb_system_ring_buffer_statistics_items_available</code> (GAUGE)</dt>
  <dd><strong>Dostupné položky</strong>: Aktuální počet položek dostupných ke skenování/kopírování s ohledem na efektivní koncovou značku.</dd>
  <dt><code>io_evitadb_system_ring_buffer_statistics_items_copied</code> (COUNTER)</dt>
  <dd><strong>Zkopírované položky</strong>: Celkový počet položek zkopírovaných z bufferu pomocí kopírovacích operací od jeho vytvoření.</dd>
  <dt><code>io_evitadb_system_ring_buffer_statistics_items_present</code> (GAUGE)</dt>
  <dd><strong>Přítomné položky</strong>: Aktuální počet položek přítomných v bufferu.</dd>
  <dt><code>io_evitadb_system_ring_buffer_statistics_items_scanned</code> (COUNTER)</dt>
  <dd><strong>Skenované položky</strong>: Celkový počet položek proskanovaných pomocí forEach operací od vytvoření.</dd>
  <dt><code>io_evitadb_system_scheduled_executor_statistics_active</code> (GAUGE)</dt>
  <dd><strong>Aktivní úlohy</strong>: Přibližný počet vláken, která aktuálně vykonávají úlohy</dd>
  <dt><code>io_evitadb_system_scheduled_executor_statistics_completed</code> (COUNTER)</dt>
  <dd><strong>Dokončené úlohy</strong>: Přibližný celkový počet úloh, které byly dokončeny</dd>
  <dt><code>io_evitadb_system_scheduled_executor_statistics_pool_core</code> (GAUGE)</dt>
  <dd><strong>Minimální počet pracovníků</strong>: Základní počet vláken v poolu</dd>
  <dt><code>io_evitadb_system_scheduled_executor_statistics_pool_max</code> (GAUGE)</dt>
  <dd><strong>Maximální počet pracovníků</strong>: Maximální povolený počet vláken v poolu</dd>
  <dt><code>io_evitadb_system_scheduled_executor_statistics_pool_size</code> (GAUGE)</dt>
  <dd><strong>Aktuální počet pracovníků</strong>: Aktuální počet vláken v poolu</dd>
  <dt><code>io_evitadb_system_scheduled_executor_statistics_queue_remaining</code> (GAUGE)</dt>
  <dd><strong>Zbývající fronta</strong>: Počet dalších prvků, které může tato fronta ideálně přijmout bez blokování</dd>
  <dt><code>io_evitadb_system_scheduled_executor_statistics_queued</code> (GAUGE)</dt>
  <dd><strong>Úlohy ve frontě</strong>: Přibližný počet úloh ve frontě čekajících na vykonání</dd>
  <dt><code>io_evitadb_system_transaction_fork_join_pool_statistics_active</code> (GAUGE)</dt>
  <dd><strong>Aktivní pracovníci</strong>: Odhad počtu vláken, která aktuálně kradou nebo vykonávají úlohy</dd>
  <dt><code>io_evitadb_system_transaction_fork_join_pool_statistics_queued</code> (GAUGE)</dt>
  <dd><strong>Úlohy ve frontě</strong>: Odhad celkového počtu úloh aktuálně držených ve frontách pracovními vlákny</dd>
  <dt><code>io_evitadb_system_transaction_fork_join_pool_statistics_running</code> (GAUGE)</dt>
  <dd><strong>Běžící pracovníci</strong>: Odhad počtu pracovních vláken, která nejsou blokována čekáním na spojení úloh nebo na jiné synchronizační vlákna</dd>
  <dt><code>io_evitadb_system_transaction_fork_join_pool_statistics_steals</code> (COUNTER)</dt>
  <dd><strong>Ukradené úlohy</strong>: Odhad celkového počtu úloh ukradených z fronty jednoho vlákna jiným vláknem. Uváděná hodnota podhodnocuje skutečný počet ukradených úloh, pokud pool není v klidovém stavu</dd>
</dl>

#### Transakce

<dl>
  <dt><code>io.evitadb.transaction.WalStatistics.oldestWalEntryTimestampSeconds</code> (GAUGE)</dt>
  <dd><strong>Časové razítko nejstaršího záznamu WAL</strong>: Časové razítko nejstaršího záznamu WAL ve WAL souborech (aktivních nebo historických).</dd>
  <dt><code>io.evitadb.transaction.WalStatistics.oldestWalEntryTimestampSeconds</code> (GAUGE)</dt>
  <dd><strong>Časové razítko nejstaršího záznamu WAL</strong>: Časové razítko nejstaršího záznamu WAL ve WAL souborech (aktivních nebo historických).</dd>
  <dt><code>io_evitadb_transaction_catalog_goes_live_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba přechodu katalogu do stavu "živý"</dd>
  <dt><code>io_evitadb_transaction_catalog_goes_live_total</code> (COUNTER)</dt>
  <dd>Počet přechodů katalogu do stavu "živý"</dd>
  <dt><code>io_evitadb_transaction_isolated_wal_file_closed_total</code> (COUNTER)</dt>
  <dd>Uzavřené soubory pro izolované WAL úložiště.</dd>
  <dt><code>io_evitadb_transaction_isolated_wal_file_opened_total</code> (COUNTER)</dt>
  <dd>Otevřené soubory pro izolované WAL úložiště.</dd>
  <dt><code>io_evitadb_transaction_new_catalog_version_propagated_collapsed_transactions</code> (COUNTER)</dt>
  <dd><strong>Transakce propagované do živého pohledu.</strong>: Počet transakcí, které byly propagovány do živého pohledu při jednom přechodu.</dd>
  <dt><code>io_evitadb_transaction_new_catalog_version_propagated_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba propagace nové verze katalogu v milisekundách</dd>
  <dt><code>io_evitadb_transaction_new_catalog_version_propagated_total</code> (COUNTER)</dt>
  <dd>Propagované verze katalogu</dd>
  <dt><code>io_evitadb_transaction_off_heap_memory_allocation_change_allocated_memory_bytes</code> (GAUGE)</dt>
  <dd><strong>Alokovaná paměť v bajtech</strong>: Množství paměti alokované pro off-heap úložiště v bajtech.</dd>
  <dt><code>io_evitadb_transaction_off_heap_memory_allocation_change_used_memory_bytes</code> (GAUGE)</dt>
  <dd><strong>Využitá paměť v bajtech</strong>: Množství paměti využité pro off-heap úložiště v bajtech.</dd>
  <dt><code>io_evitadb_transaction_transaction_accepted_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba řešení konfliktů v milisekundách<br/><br/><strong>Popisky:</strong> <Term>resolution</Term><br/></dd>
  <dt><code>io_evitadb_transaction_transaction_accepted_total</code> (COUNTER)</dt>
  <dd>Přijaté transakce<br/><br/><strong>Popisky:</strong> <Term>resolution</Term><br/></dd>
  <dt><code>io_evitadb_transaction_transaction_appended_to_wal_appended_atomic_mutations</code> (COUNTER)</dt>
  <dd><strong>Připojené atomické mutace.</strong>: Počet atomických mutací (schéma, schéma katalogu nebo entity) připojených do sdíleného WAL.</dd>
  <dt><code>io_evitadb_transaction_transaction_appended_to_wal_appended_wal_bytes</code> (COUNTER)</dt>
  <dd><strong>Velikost zapsaného WAL v bajtech.</strong>: Velikost zapsaného WAL v bajtech.</dd>
  <dt><code>io_evitadb_transaction_transaction_appended_to_wal_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba připojení transakce do sdíleného WAL v milisekundách</dd>
  <dt><code>io_evitadb_transaction_transaction_appended_to_wal_total</code> (COUNTER)</dt>
  <dd>Transakce připojené do WAL</dd>
  <dt><code>io_evitadb_transaction_transaction_finished_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba trvání transakce v milisekundách<br/><br/><strong>Popisky:</strong> <Term>resolution</Term><br/></dd>
  <dt><code>io_evitadb_transaction_transaction_finished_oldest_transaction_timestamp_seconds</code> (GAUGE)</dt>
  <dd><strong>Časové razítko nejstarší transakce</strong>: Časové razítko nejstarší nedokončené (běžící) transakce v katalogu.<br/><br/><strong>Popisky:</strong> <Term>resolution</Term><br/></dd>
  <dt><code>io_evitadb_transaction_transaction_finished_total</code> (COUNTER)</dt>
  <dd>Dokončené transakce<br/><br/><strong>Popisky:</strong> <Term>resolution</Term><br/></dd>
  <dt><code>io_evitadb_transaction_transaction_incorporated_to_trunk_collapsed_transactions</code> (COUNTER)</dt>
  <dd><strong>Transakce začleněné do sdílených datových struktur.</strong>: N/A</dd>
  <dt><code>io_evitadb_transaction_transaction_incorporated_to_trunk_incorporation_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba začlenění v milisekundách</dd>
  <dt><code>io_evitadb_transaction_transaction_incorporated_to_trunk_processed_atomic_mutations</code> (COUNTER)</dt>
  <dd><strong>Zpracované atomické mutace.</strong>: N/A</dd>
  <dt><code>io_evitadb_transaction_transaction_incorporated_to_trunk_processed_local_mutations</code> (COUNTER)</dt>
  <dd><strong>Zpracované lokální mutace.</strong>: N/A</dd>
  <dt><code>io_evitadb_transaction_transaction_processed_lag_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Zpoždění transakce</strong>: Čas potřebný k tomu, aby se transakce stala viditelnou pro všechny nové relace. Jinými slovy, čas mezi potvrzením transakce a ovlivněním sdíleného pohledu.</dd>
  <dt><code>io_evitadb_transaction_transaction_queued_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba čekání transakce ve frontě.<br/><br/><strong>Popisky:</strong> <Term>stage</Term><br/></dd>
  <dt><code>io_evitadb_transaction_transaction_started_total</code> (COUNTER)</dt>
  <dd>Zahájené transakce</dd>
  <dt><code>io_evitadb_transaction_wal_cache_size_changed_locations_cached</code> (GAUGE)</dt>
  <dd><strong>Celkový počet cachovaných umístění ve WAL souboru</strong>: Celkový počet cachovaných umístění (použitých pro rychlé vyhledávání mutací) ve sdíleném WAL souboru.</dd>
  <dt><code>io_evitadb_transaction_wal_rotation_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba rotace WAL v milisekundách</dd>
  <dt><code>io_evitadb_transaction_wal_rotation_total</code> (COUNTER)</dt>
  <dd>Rotace WAL</dd>
</dl>