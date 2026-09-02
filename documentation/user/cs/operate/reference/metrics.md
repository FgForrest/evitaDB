---
translated: 'true'
commit: ab1f0b542c8221dfa52db009d89f0ff2305e278f
---
### Metriky

<UsedTerms>
  <h4>Popisky použité v metrikách</h4>
  <dl>
    <dt>api</dt>
    <dd><strong>Typ API</strong>: Identifikace volaného API.</dd>
    <dt>api_type</dt>
    <dd><strong>Typ API</strong>: Externí API, jehož připravenost je hlášena (REST, GraphQL, gRPC, ...).</dd>
    <dt>area</dt>
    <dd><strong>Oblast</strong>: Oblast, pro kterou jsou publikovány události.</dd>
    <dt>buildType</dt>
    <dd><strong>Typ sestavení</strong>: Typ sestavení instance: NEW nebo REFRESH</dd>
    <dt>catalogName</dt>
    <dd><strong>Katalog</strong>: Název katalogu, ke kterému je tato událost/metrika přiřazena.</dd>
    <dt>commit</dt>
    <dd><strong>Hash commitu</strong>: Zkrácený Git commit hash vložený do manifestu při sestavení.</dd>
    <dt>conflictPolicy</dt>
    <dd><strong>Politika konfliktů</strong>: Hrubá politika konfliktů (NONE/CATALOG/COLLECTION/ENTITY) platná pro konfliktní rozsah.</dd>
    <dt>conflictScope</dt>
    <dd><strong>Rozsah konfliktu</strong>: Granularita konfliktního klíče (např. entita, atribut, cena, reference).</dd>
    <dt>direction</dt>
    <dd><strong>Směr GOAWAY</strong>: SENT když server uzavřel spojení, RECEIVED když to udělal protějšek.</dd>
    <dt>entityType</dt>
    <dd><strong>Typ entity</strong>: Název souvisejícího typu entity (kolekce).</dd>
    <dt>errorCode</dt>
    <dd><strong>Kód chyby GOAWAY</strong>: RFC 9113 kód chyby nesený rámcem GOAWAY, například ENHANCE_YOUR_CALM(11).</dd>
    <dt>error_type</dt>
    <dd><strong>Typ chyby</strong>: Třída počítané chyby.</dd>
    <dt>fileType</dt>
    <dd><strong>Typ souboru</strong>: Typ souboru, který byl zapsán. Jeden z: CATALOG, ENTITY_COLLECTION, WAL nebo BOOTSTRAP</dd>
    <dt>graphQLInstanceType</dt>
    <dd><strong>Typ instance GraphQL</strong>: Doména GraphQL API použitá v souvislosti s touto událostí/metrikou: SYSTEM, SCHEMA nebo DATA</dd>
    <dt>graphQLOperationType</dt>
    <dd><strong>Typ operace GraphQL</strong>: Typ operace specifikované v GQL požadavku: QUERY, MUTATION nebo SUBSCRIPTION.</dd>
    <dt>grpcResponseStatus</dt>
    <dd><strong>Stav odpovědi gRPC</strong>: Stav odpovědi gRPC (OK, ERROR nebo CANCELLED).</dd>
    <dt>httpMethod</dt>
    <dd><strong>HTTP metoda</strong>: HTTP metoda požadavku.</dd>
    <dt>httpStatusCode</dt>
    <dd><strong>HTTP stavový kód</strong>: HTTP stavový kód odpovědi, který byl odeslán klientovi.</dd>
    <dt>initiator</dt>
    <dd><strong>Iniciátor volání</strong>: Iniciátor gRPC volání (buď klient nebo server).</dd>
    <dt>instanceId</dt>
    <dd><strong>ID instance serveru</strong>: Unikátní název serveru převzatý z konfiguračního souboru.</dd>
    <dt>java_version</dt>
    <dd><strong>Verze JVM</strong>: Systémová vlastnost <code>java.version</code> JVM běžícího evitaDB.</dd>
    <dt>methodName</dt>
    <dd><strong>Název metody</strong>: Koncový bod nebo název metody z RequestLog.</dd>
    <dt>name</dt>
    <dd><strong>Logický název souboru</strong>: Logický název souboru, který byl zapsán. Identifikuje soubor přesněji.</dd>
    <dt>operationId</dt>
    <dd><strong>ID operace</strong>: ID operace, která byla provedena.</dd>
    <dt>operationName</dt>
    <dd><strong>Operace GraphQL</strong>: Název operace specifikované v GQL požadavku.</dd>
    <dt>prefetched</dt>
    <dd><strong>Přednačtený vs. nepřednačtený dotaz</strong>: Zda dotaz použil plán přednačtení. Plán přednačtení optimisticky načte dotazované entity předem a provádí operace přímo na nich (bez přístupu k indexům).</dd>
    <dt>probeResult</dt>
    <dd><strong>Výsledek kontroly připravenosti</strong>: Výsledek kontroly připravenosti (ok, timeout, error).</dd>
    <dt>problem_type</dt>
    <dd><strong>Typ zdravotního problému</strong>: Identifikátor aktivního zdravotního problému.</dd>
    <dt>procedureName</dt>
    <dd><strong>Název procedury</strong>: Název gRPC procedury, která byla volána (název metody).</dd>
    <dt>prospective</dt>
    <dd><strong>Perspektiva (klient/server)</strong>: Určuje, zda událost reprezentuje pohled serveru nebo klienta na připravenost.
Klientský pohled je doba z pohledu HTTP klienta ovlivněná timeouty, serverový pohled je skutečná
doba kontroly připravenosti.</dd>
    <dt>reason</dt>
    <dd><strong>Důvod</strong>: Proč nebyly záznamy/relace uloženy (např. SAMPLING, MEMORY_SHORTAGE, DISK_SHORTAGE, IO_ERROR, SERIALIZATION_ERROR).</dd>
    <dt>recordType</dt>
    <dd><strong>Typ záznamu</strong>: Typ záznamů, které se změnily v OffsetIndex.</dd>
    <dt>requestResult</dt>
    <dd><strong>Výsledek požadavku</strong>: Zjednodušený výsledek požadavku (SUCCESS, ERROR, TIMED_OUT, CANCELLED).</dd>
    <dt>resolution</dt>
    <dd><strong>Rozhodnutí transakce</strong>: Rozhodnutí transakce (commit nebo rollback).</dd>
    <dt>resolutionLayer</dt>
    <dd><strong>Vrstva rozhodnutí</strong>: Vrstva schématu, ze které byla politika rozhodnuta (ENTITY_SCHEMA/CATALOG_SCHEMA/ENGINE_DEFAULT).</dd>
    <dt>responseStatus</dt>
    <dd><strong>Stav odpovědi</strong>: Stav odpovědi: OK, ERROR, CANCELLED nebo TIMEOUT.</dd>
    <dt>restInstanceType</dt>
    <dd><strong>Typ instance REST</strong>: Doména REST API použitá v souvislosti s touto událostí/metrikou: SYSTEM nebo CATALOG</dd>
    <dt>restOperationType</dt>
    <dd><strong>Typ operace REST</strong>: Typ provedené operace. Jeden z: QUERY, MUTATION.</dd>
    <dt>serverVersion</dt>
    <dd><strong>Verze serveru</strong>: Přesná verze serveru evitaDB.</dd>
    <dt>serviceName</dt>
    <dd><strong>Název služby</strong>: Název gRPC služby, která byla volána (název Java třídy).</dd>
    <dt>sessionProtocol</dt>
    <dd><strong>Protokol relace</strong>: Protokol relace (H1C, H1, H2C, H2, atd.).</dd>
    <dt>stage</dt>
    <dd><strong>Fáze transakce</strong>: Název fáze, na kterou transakce čeká.</dd>
    <dt>taskName</dt>
    <dd><strong>Název úlohy</strong>: Název background úlohy.</dd>
    <dt>version</dt>
    <dd><strong>Verze evitaDB</strong>: Maven verze běžícího sestavení evitaDB.</dd>
  </dl>
</UsedTerms>

#### API

<dl>
  <dt><code>io_evitadb_external_api_http2_go_away_total</code> (COUNTER)</dt>
  <dd>HTTP/2 spojení ukončená s chybným GOAWAY celkem<br/><br/><strong>Popisky:</strong> <Term>směr</Term>, <Term>errorCode</Term><br/></dd>
  <dt><code>io_evitadb_external_api_http2_rst_flood_total</code> (COUNTER)</dt>
  <dd>Celkový počet detekovaných HTTP/2 RST_STREAM floodů</dd>
  <dt><code>io_evitadb_external_api_readiness_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba trvání kontroly připravenosti<br/><br/><strong>Popisky:</strong> <Term>api</Term>, <Term>probeResult</Term>, <Term>potenciální</Term><br/></dd>
  <dt><code>io_evitadb_external_api_readiness_total</code> (COUNTER)</dt>
  <dd>Celkový počet vyvolání kontroly připravenosti<br/><br/><strong>Popisky:</strong> <Term>api</Term>, <Term>probeResult</Term>, <Term>potenciální</Term><br/></dd>
  <dt><code>io_evitadb_external_api_request_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Celková doba trvání požadavku<br/><br/><strong>Popisky:</strong> <Term>api</Term>, <Term>httpStatusCode</Term>, <Term>methodName</Term>, <Term>výsledekPožadavku</Term>, <Term>sessionProtocol</Term><br/></dd>
  <dt><code>io_evitadb_external_api_request_request_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba příjmu požadavku</strong>: Čas potřebný k přijetí celého těla požadavku v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>api</Term>, <Term>httpStatusCode</Term>, <Term>methodName</Term>, <Term>výsledek požadavku</Term>, <Term>sessionProtocol</Term><br/></dd>
  <dt><code>io_evitadb_external_api_request_request_length_bytes</code> (HISTOGRAM)</dt>
  <dd><strong>Délka požadavku</strong>: Délka obsahu požadavku v bajtech.<br/><br/><strong>Popisky:</strong> <Term>api</Term>, <Term>httpStatusCode</Term>, <Term>methodName</Term>, <Term>výsledekPožadavku</Term>, <Term>sessionProtocol</Term><br/></dd>
  <dt><code>io_evitadb_external_api_request_response_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba odeslání odpovědi</strong>: Čas potřebný k odeslání odpovědi v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>api</Term>, <Term>httpStatusCode</Term>, <Term>methodName</Term>, <Term>výsledekPožadavku</Term>, <Term>sessionProtocol</Term><br/></dd>
  <dt><code>io_evitadb_external_api_request_response_length_bytes</code> (HISTOGRAM)</dt>
  <dd><strong>Délka odpovědi</strong>: Délka obsahu odpovědi v bajtech.<br/><br/><strong>Popisky:</strong> <Term>api</Term>, <Term>httpStatusCode</Term>, <Term>methodName</Term>, <Term>výsledek požadavku</Term>, <Term>sessionProtocol</Term><br/></dd>
  <dt><code>io_evitadb_external_api_request_total</code> (COUNTER)</dt>
  <dd>Celkový počet vyvolaných požadavků<br/><br/><strong>Popisky:</strong> <Term>api</Term>, <Term>httpStatusCode</Term>, <Term>methodName</Term>, <Term>výsledek požadavku</Term>, <Term>sessionProtocol</Term><br/></dd>
  <dt><code>io_evitadb_external_api_request_total_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Celková doba trvání</strong>: End-to-end doba trvání požadavku v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>api</Term>, <Term>httpStatusCode</Term>, <Term>methodName</Term>, <Term>výsledekPožadavku</Term>, <Term>sessionProtocol</Term><br/></dd>
</dl>

#### API / GraphQL / Instance / Schema

<dl>
  <dt><code>io_evitadb_external_api_graphql_instance_built_graph_ql_instance_build_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba sestavení API</strong>: Doba sestavení jednoho API v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>graphQLInstanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_instance_built_graph_ql_schema_build_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba sestavení GraphQL schématu</strong>: Doba sestavení jednoho schématu GraphQL API v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>graphQLInstanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_instance_built_graph_ql_schema_dsl_lines</code> (GAUGE)</dt>
  <dd><strong>Počet řádků</strong>: Počet řádků vygenerovaných v sestaveném GraphQL schema DSL.<br/><br/><strong>Popisky:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>graphQLInstanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_instance_built_total</code> (COUNTER)</dt>
  <dd>Celkový počet sestavených instancí GraphQL<br/><br/><strong>Popisky:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>graphQLInstanceType</Term><br/></dd>
</dl>

#### API / gRPC

<dl>
  <dt><code>io_evitadb_api_grpc_evita_procedure_called_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba volání gRPC evitaDB procedury<br/><br/><strong>Popisky:</strong> <Term>grpcResponseStatus</Term>, <Term>iniciátor</Term>, <Term>procedureName</Term>, <Term>serviceName</Term><br/></dd>
  <dt><code>io_evitadb_api_grpc_evita_procedure_called_total</code> (COUNTER)</dt>
  <dd>Celkový počet volání gRPC evitaDB procedury<br/><br/><strong>Popisky:</strong> <Term>grpcResponseStatus</Term>, <Term>iniciátor</Term>, <Term>procedureName</Term>, <Term>serviceName</Term><br/></dd>
  <dt><code>io_evitadb_api_grpc_session_procedure_called_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba volání gRPC session procedury<br/><br/><strong>Popisky:</strong> <Term>grpcResponseStatus</Term>, <Term>iniciátor</Term>, <Term>procedureName</Term>, <Term>serviceName</Term><br/></dd>
  <dt><code>io_evitadb_api_grpc_session_procedure_called_total</code> (COUNTER)</dt>
  <dd>Celkový počet volání gRPC session procedury<br/><br/><strong>Popisky:</strong> <Term>grpcResponseStatus</Term>, <Term>iniciátor</Term>, <Term>procedureName</Term>, <Term>serviceName</Term><br/></dd>
</dl>

#### CDC

<dl>
  <dt><code>io_evitadb_cdc_change_catalog_capture_statistics_events_published_total</code> (COUNTER)</dt>
  <dd><strong>Publikované události</strong>: Počet událostí publikovaných všem odběratelům.</dd>
  <dt><code>io_evitadb_cdc_change_catalog_capture_statistics_lagging_subscribers</code> (GAUGE)</dt>
  <dd><strong>Opoždění odběratelé</strong>: Počet odběratelů načítajících záznamy WAL.</dd>
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
  <dd><strong>Počet záznamů čekajících v předpokoji</strong>: Počet záznamů vhodných k uložení do cache, které ještě nejsou v cache a sbírají statistiky použití pro rozhodnutí o zařazení do cache.</dd>
  <dt><code>io_evitadb_cache_anteroom_wasted_total</code> (COUNTER)</dt>
  <dd>Celkový počet promarněných záznamů v předpokoji</dd>
</dl>

#### ExternalAPI / GraphQL / Request

<dl>
  <dt><code>io_evitadb_external_api_graphql_request_executed_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba provedení GraphQL požadavku<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_execution_api_overhead_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Režie provedení požadavku</strong>: Čas potřebný k provedení celého požadavku v milisekundách bez interního provedení evitaDB.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_input_deserialization_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba deserializace vstupu</strong>: Čas potřebný k deserializaci příchozího JSON vstupního GraphQL požadavku do interní struktury v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_internal_evitadb_input_reconstruction_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba rekonstrukce vstupu evitaDB</strong>: Čas potřebný k rekonstrukci vstupu dotazu do jádra evitaDB v milisekundách. Obvykle převádí JSON dotaz na interní reprezentaci dotazu evitaDB nebo JSON mutace na interní reprezentaci mutace evitaDB.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_operation_execution_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba provedení</strong>: Čas potřebný k provedení celé parsované a validované GraphQL operace serverovým enginem v milisekundách. Zahrnuje veškerou business logiku data fetcherů, včetně rekonstrukce vstupu evitaDB a provedení dotazu evitaDB.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_parse_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba parsování požadavku</strong>: Čas potřebný k parsování GraphQL požadavku (dotaz a proměnné) serverovým enginem z interní struktury pro validaci a provedení v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_preparation_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba přípravy požadavku</strong>: Čas potřebný k přípravě a inicializaci serverového enginu GraphQL pro parsování a provedení příchozího požadavku v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_result_serialization_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba serializace výsledku</strong>: Čas potřebný k serializaci finálního výsledku požadavku do výstupního JSON v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_root_fields_processed</code> (GAUGE)</dt>
  <dd><strong>Počet kořenových polí požadavku</strong>: Počet kořenových polí (dotazy, mutace) zpracovaných v rámci jednoho GraphQL požadavku.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_total</code> (COUNTER)</dt>
  <dd>Celkový počet provedených GraphQL požadavků<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_validation_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba validace</strong>: Čas potřebný k validaci parsovaného požadavku (dotaz a proměnné) serverovým enginem GraphQL před provedením v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
</dl>

#### ExternalAPI / REST / Instance / Schema

<dl>
  <dt><code>io_evitadb_external_api_rest_instance_built_registered_rest_endpoints</code> (GAUGE)</dt>
  <dd><strong>Počet endpointů</strong>: Počet registrovaných endpointů v sestaveném OpenAPI schématu<br/><br/><strong>Popisky:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>restInstanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_instance_built_rest_instance_build_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba sestavení API</strong>: Doba sestavení jednoho API v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>restInstanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_instance_built_rest_schema_build_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba sestavení REST schématu</strong>: Doba sestavení jednoho REST API schématu v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>restInstanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_instance_built_rest_schema_dsl_lines</code> (GAUGE)</dt>
  <dd><strong>Počet řádků</strong>: Počet řádků vygenerovaných v sestaveném REST schema DSL.<br/><br/><strong>Popisky:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>restInstanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_instance_built_total</code> (COUNTER)</dt>
  <dd>Celkový počet sestavených instancí REST API<br/><br/><strong>Popisky:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>restInstanceType</Term><br/></dd>
</dl>

#### ExternalAPI / REST / Request

<dl>
  <dt><code>io_evitadb_external_api_rest_request_executed_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba provedení REST požadavku<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>operationId</Term>, <Term>responseStatus</Term>, <Term>restInstanceType</Term>, <Term>restOperationType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_request_executed_execution_api_overhead_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Režie provedení požadavku</strong>: Čas potřebný k provedení požadavku v milisekundách bez interního provedení evitaDB.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>operationId</Term>, <Term>responseStatus</Term>, <Term>restInstanceType</Term>, <Term>restOperationType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_request_executed_input_deserialization_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba deserializace vstupu</strong>: Čas potřebný k deserializaci příchozího JSON vstupního REST požadavku do interní struktury v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>operationId</Term>, <Term>responseStatus</Term>, <Term>restInstanceType</Term>, <Term>restOperationType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_request_executed_internal_evitadb_input_reconstruction_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba rekonstrukce vstupu evitaDB</strong>: Čas potřebný k rekonstrukci vstupu dotazu do jádra evitaDB v milisekundách. Obvykle převádí JSON dotaz na interní reprezentaci dotazu evitaDB nebo JSON mutace na interní reprezentaci mutace evitaDB.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>operationId</Term>, <Term>responseStatus</Term>, <Term>restInstanceType</Term>, <Term>restOperationType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_request_executed_operation_execution_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba provedení</strong>: Čas potřebný k provedení celé parsované a validované REST operace serverovým enginem v milisekundách. Zahrnuje veškerou business logiku handlerů, včetně rekonstrukce vstupu evitaDB a provedení dotazu evitaDB.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>operationId</Term>, <Term>responseStatus</Term>, <Term>restInstanceType</Term>, <Term>restOperationType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_request_executed_result_serialization_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba serializace výsledku</strong>: Čas potřebný k serializaci finálního výsledku požadavku do výstupního JSON v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>operationId</Term>, <Term>responseStatus</Term>, <Term>restInstanceType</Term>, <Term>restOperationType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_request_executed_total</code> (COUNTER)</dt>
  <dd>Celkový počet provedených REST požadavků<br/><br/><strong>Popisky:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>operationId</Term>, <Term>responseStatus</Term>, <Term>restInstanceType</Term>, <Term>restOperationType</Term><br/></dd>
</dl>

#### Dotaz

<dl>
  <dt><code>io_evitadb_query_entity_enrich_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba obohacení entity v milisekundách<br/><br/><strong>Popisky:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_enrich_records</code> (COUNTER)</dt>
  <dd><strong>Celkový počet obohacených záznamů</strong>: Celkový počet záznamů, které byly obohaceny.<br/><br/><strong>Popisky:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_enrich_size_bytes</code> (HISTOGRAM)</dt>
  <dd><strong>Velikost obohacení v bajtech</strong>: Velikost v bajtech dodatečně načtených a obohacených dat.<br/><br/><strong>Popisky:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_enrich_total</code> (COUNTER)</dt>
  <dd>Obohacená entita<br/><br/><strong>Popisky:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_fetch_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba načtení entity v milisekundách<br/><br/><strong>Popisky:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_fetch_records</code> (COUNTER)</dt>
  <dd><strong>Celkový počet načtených záznamů</strong>: Celkový počet záznamů, které byly načteny.<br/><br/><strong>Popisky:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_fetch_size_bytes</code> (HISTOGRAM)</dt>
  <dd><strong>Velikost načtení v bajtech</strong>: Celková velikost načtených dat v bajtech.<br/><br/><strong>Popisky:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_fetch_total</code> (COUNTER)</dt>
  <dd>Načtená entita<br/><br/><strong>Popisky:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba trvání dotazu v milisekundách<br/><br/><strong>Popisky:</strong> <Term>entityType</Term>, <Term>přednačteno</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_estimated</code> (HISTOGRAM)</dt>
  <dd><strong>Odhadovaná složitost dotazu</strong>: Odhadovaná složitost dotazu.<br/><br/><strong>Popisky:</strong> <Term>entityType</Term>, <Term>přednačteno</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_execution_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba provedení dotazu v milisekundách</strong>: Doba potřebná k provedení vybraného plánu provedení dotazu.<br/><br/><strong>Popisky:</strong> <Term>entityType</Term>, <Term>přednačteno</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_fetched</code> (HISTOGRAM)</dt>
  <dd><strong>Celkový počet načtených záznamů</strong>: Celkový počet záznamů načtených z datového úložiště (bez záznamů nalezených v cache).<br/><br/><strong>Popisky:</strong> <Term>entityType</Term>, <Term>přednačteno</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_fetched_size_bytes</code> (HISTOGRAM)</dt>
  <dd><strong>Velikost načtení v bajtech</strong>: Celková velikost načtených dat v bajtech.<br/><br/><strong>Popisky:</strong> <Term>entityType</Term>, <Term>přednačteno</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_found</code> (HISTOGRAM)</dt>
  <dd><strong>Celkový počet nalezených záznamů</strong>: Celkový počet nalezených záznamů (odpovídajících dotazu).<br/><br/><strong>Popisky:</strong> <Term>entityType</Term>, <Term>přednačteno</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_plan_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Doba plánování dotazu v milisekundách</strong>: Doba potřebná k sestavení všech variant plánů provedení dotazu.<br/><br/><strong>Popisky:</strong> <Term>entityType</Term>, <Term>přednačteno</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_real</code> (HISTOGRAM)</dt>
  <dd><strong>Složitost filtru</strong>: Skutečná složitost dotazu.<br/><br/><strong>Popisky:</strong> <Term>entityType</Term>, <Term>přednačteno</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_returned</code> (HISTOGRAM)</dt>
  <dd><strong>Celkový počet vrácených záznamů</strong>: Celkový počet vrácených záznamů (zahrnutých ve výsledku).<br/><br/><strong>Popisky:</strong> <Term>entityType</Term>, <Term>přednačteno</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_scanned</code> (HISTOGRAM)</dt>
  <dd><strong>Celkový počet prohledaných záznamů</strong>: Celkový počet prohledaných záznamů (zahrnutých do výpočtu).<br/><br/><strong>Popisky:</strong> <Term>entityType</Term>, <Term>přednačteno</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_total</code> (COUNTER)</dt>
  <dd>Dokončený dotaz<br/><br/><strong>Popisky:</strong> <Term>entityType</Term>, <Term>přednačteno</Term><br/></dd>
  <dt><code>io_evitadb_store_traffic_traffic_recorder_vynechané_záznamy_zahozené_relace</code> (COUNTER)</dt>
  <dd><strong>Zahozené relace</strong>: Počet celých relací zahozených z tohoto důvodu od předchozího výstupu.<br/><br/><strong>Popisky:</strong> <Term>reason</Term><br/></dd>
  <dt><code>io_evitadb_store_traffic_traffic_recorder_vynechané_záznamy_chybějící_záznamy</code> (COUNTER)</dt>
  <dd><strong>Chybějící záznamy</strong>: Počet záznamů provozu, které nebyly uloženy z tohoto důvodu od předchozího výstupu.<br/><br/><strong>Popisky:</strong> <Term>reason</Term><br/></dd>
  <dt><code>io_evitadb_store_traffic_traffic_recorder_statistics_active_sessions</code> (GAUGE)</dt>
  <dd><strong>Aktivní relace</strong>: Počet živých relací aktuálně držících off-heap bloky.</dd>
  <dt><code>io_evitadb_store_traffic_traffic_recorder_statistics_blocks_allocated</code> (COUNTER)</dt>
  <dd><strong>Přidělené paměťové bloky</strong>: Počet off-heap paměťových bloků přidělených od předchozího výstupu.</dd>
  <dt><code>io_evitadb_store_traffic_traffic_recorder_statistics_created_sessions</code> (COUNTER)</dt>
  <dd><strong>Vytvořené relace</strong>: Počet relací přijatých k zaznamenání od předchozího výstupu.</dd>
  <dt><code>io_evitadb_store_traffic_traffic_recorder_statistics_disk_buffer_used_bytes</code> (GAUGE)</dt>
  <dd><strong>Použité bajty diskového bufferu</strong>: Počet bajtů aktuálně obsazených rezidentními relacemi v diskovém kruhovém bufferu.</dd>
  <dt><code>io_evitadb_store_traffic_traffic_recorder_statistics_disk_bytes_appended</code> (COUNTER)</dt>
  <dd><strong>Připojené bajty na disk</strong>: Počet bajtů připojených do diskového kruhového bufferu od předchozího výstupu.</dd>
  <dt><code>io_evitadb_store_traffic_traffic_recorder_statistics_disk_resident_sessions</code> (GAUGE)</dt>
  <dd><strong>Rezidentní relace na disku</strong>: Počet relací aktuálně rezidentních v diskovém kruhovém bufferu.</dd>
  <dt><code>io_evitadb_store_traffic_traffic_recorder_statistics_finalized_sessions_backlog</code> (GAUGE)</dt>
  <dd><strong>Backlog dokončených relací</strong>: Počet uzavřených relací čekajících na zapsání na disk (flush backlog).</dd>
  <dt><code>io_evitadb_store_traffic_traffic_recorder_statistics_finished_sessions</code> (COUNTER)</dt>
  <dd><strong>Dokončené relace</strong>: Počet relací čistě uzavřených a zařazených do fronty na disk od předchozího výstupu.</dd>
  <dt><code>io_evitadb_store_traffic_traffic_recorder_statistics_recorded_records</code> (COUNTER)</dt>
  <dd><strong>Zaznamenané záznamy</strong>: Počet záznamů provozu úspěšně zachycených od předchozího výstupu.</dd>
  <dt><code>io_evitadb_store_traffic_traffic_recorder_statistics_total_memory_blocks</code> (GAUGE)</dt>
  <dd><strong>Celkový počet paměťových bloků</strong>: Celkový počet off-heap paměťových bloků dostupných pro recorder.</dd>
  <dt><code>io_evitadb_store_traffic_traffic_recorder_statistics_used_memory_blocks</code> (GAUGE)</dt>
  <dd><strong>Použité paměťové bloky</strong>: Počet off-heap paměťových bloků aktuálně používaných (primární signál paměťového tlaku).</dd>
</dl>

#### Relace

<dl>
  <dt><code>io_evitadb_session_closed_active_sessions</code> (GAUGE)</dt>
  <dd><strong>Počet stále aktivních relací</strong>: Počet stále aktivních relací v okamžiku, kdy byla tato relace uzavřena.</dd>
  <dt><code>io_evitadb_session_closed_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba trvání relace v milisekundách</dd>
  <dt><code>io_evitadb_session_closed_mutations</code> (HISTOGRAM)</dt>
  <dd><strong>Počet volání mutací provedených v relaci</strong>: Počet mutací provedených během této relace.</dd>
  <dt><code>io_evitadb_session_closed_oldest_session_timestamp_seconds</code> (GAUGE)</dt>
  <dd><strong>Časové razítko nejstarší relace</strong>: Časové razítko nejstarší relace v okamžiku, kdy byla tato relace uzavřena.</dd>
  <dt><code>io_evitadb_session_closed_queries</code> (HISTOGRAM)</dt>
  <dd><strong>Počet dotazů provedených v relaci</strong>: Počet požadavků provedených během této relace.</dd>
  <dt><code>io_evitadb_session_closed_total</code> (COUNTER)</dt>
  <dd>Uzavřené relace</dd>
  <dt><code>io_evitadb_session_killed_total</code> (COUNTER)</dt>
  <dd>Ukončené relace</dd>
  <dt><code>io_evitadb_session_opened_total</code> (COUNTER)</dt>
  <dd>Otevřené relace</dd>
</dl>

#### Úložiště

<dl>
  <dt><code>io_evitadb_storage_catalog_checkpoint_cadence_milliseconds</code> (GAUGE)</dt>
  <dd><strong>Interval kontrolních bodů v milisekundách</strong>: Čas uplynulý od předchozího dokončeného kontrolního bodu. Porovnejte s nastaveným intervalem kontrolních bodů – trvale vyšší hodnoty znamenají, že kontrolní body nestíhají tempo zápisu.</dd>
  <dt><code>io_evitadb_storage_catalog_checkpoint_fence_depth_milliseconds</code> (GAUGE)</dt>
  <dd><strong>Hloubka oplocení v milisekundách</strong>: Jak dlouho nejstarší změna pokrytá tímto kontrolním bodem čekala, než se stala trvalou. Omezuje jak dobu uchování write-ahead logu, tak množství přehrávání při restartu. Nula, pokud byl kontrolní bod proveden bez odkladu.</dd>
  <dt><code>io_evitadb_storage_catalog_checkpoint_files_forced</code> (GAUGE)</dt>
  <dd><strong>Počet vynucených souborů</strong>: Počet datových souborů, které byly tímto kontrolním bodem vynuceny na fyzické zařízení.</dd>
  <dt><code>io_evitadb_storage_catalog_checkpoint_force_duration_milliseconds</code> (GAUGE)</dt>
  <dd><strong>Doba vynucení na zařízení v milisekundách</strong>: Čas strávený vynucením datových souborů na fyzické zařízení. Toto je náklad, který má interval kontrolních bodů rozložit – platí se jednou za kontrolní bod místo jednou za každé kolo trunku.</dd>
  <dt><code>io_evitadb_storage_catalog_checkpoint_total</code> (COUNTER)</dt>
  <dd>Kontrolní body katalogu.</dd>
  <dt><code>io_evitadb_storage_catalog_statistics_entity_collections</code> (GAUGE)</dt>
  <dd><strong>Počet kolekcí entit</strong>: Počet aktivních kolekcí entit (typů entit) v katalogu.</dd>
  <dt><code>io_evitadb_storage_catalog_statistics_occupied_disk_space_bytes</code> (GAUGE)</dt>
  <dd><strong>Celkový obsazený diskový prostor v bajtech</strong>: Celkové množství diskového prostoru využitého katalogem v bajtech.</dd>
  <dt><code>io_evitadb_storage_catalog_statistics_oldest_catalog_version_timestamp_seconds</code> (GAUGE)</dt>
  <dd><strong>Časové razítko nejstarší dostupné verze katalogu v sekundách</strong>: Stáří nejstarší dostupné verze katalogu v sekundách. Tato hodnota určuje, jak daleko do minulosti může katalog sahat.</dd>
  <dt><code>io_evitadb_storage_data_file_compact_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba komprimace OffsetIndex.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_data_file_compact_total</code> (COUNTER)</dt>
  <dd>Komprimace OffsetIndex.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
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
  <dd><strong>Velikost na disku v bajtech</strong>: Velikost OffsetIndex na disku v bajtech.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba flushování OffsetIndex na disk.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_estimated_memory_size_bytes</code> (GAUGE)</dt>
  <dd><strong>Odhadovaná velikost v paměti v bajtech</strong>: Odhadovaná velikost OffsetIndex v paměti v bajtech.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_max_record_size</code> (GAUGE)</dt>
  <dd><strong>Největší záznam v bajtech</strong>: Velikost největšího záznamu v OffsetIndex v bajtech.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_oldest_record_timestamp_seconds</code> (GAUGE)</dt>
  <dd><strong>Časové razítko nejstaršího záznamu v paměti v sekundách</strong>: Časové razítko v sekundách nejstaršího volatilního záznamu uchovávaného v paměti. Volatilní záznamy jsou ty, které ještě nebyly flushnuty na disk.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_total</code> (COUNTER)</dt>
  <dd>Flushování OffsetIndex na disk.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_history_kept_oldest_record_timestamp_seconds</code> (GAUGE)</dt>
  <dd><strong>Časové razítko nejstaršího záznamu v paměti v sekundách</strong>: Časové razítko nejstarších dat verze katalogu uchovávaných v paměti, v sekundách. Data z předchozích verzí se používají k udržení kontraktu SNAPSHOT izolace pro aktuálně otevřené relace zaměřené na starší verze katalogu. Nula, pokud nejsou uchovávána žádná data.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_non_flushed_record_size_bytes</code> (GAUGE)</dt>
  <dd><strong>Velikost záznamů čekajících na flush v bajtech</strong>: Velikost záznamů čekajících na flush v OffsetIndex v bajtech.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_non_flushed_records</code> (GAUGE)</dt>
  <dd><strong>Počet záznamů čekajících na flush</strong>: Počet volatilních záznamů čekajících na flush v OffsetIndex.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_record_type_count_changed_records</code> (GAUGE)</dt>
  <dd><strong>Počet záznamů</strong>: Celkový počet záznamů daného typu v OffsetIndex.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term>, <Term>recordType</Term><br/></dd>
  <dt><code>io_evitadb_storage_read_only_handle_closed_total</code> (COUNTER)</dt>
  <dd>Uzavřené read-only handly souborů.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_read_only_handle_opened_total</code> (COUNTER)</dt>
  <dd>Otevřené read-only handly souborů.<br/><br/><strong>Popisky:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
</dl>

#### Systém

<dl>
  <dt><code>io_evitadb_system_background_task_finished_total</code> (COUNTER)</dt>
  <dd>Dokončené úlohy na pozadí<br/><br/><strong>Popisky:</strong> <Term>taskName</Term><br/></dd>
  <dt><code>io_evitadb_system_background_task_rejected_total</code> (COUNTER)</dt>
  <dd>Zamítnuté úlohy na pozadí<br/><br/><strong>Popisky:</strong> <Term>taskName</Term><br/></dd>
  <dt><code>io_evitadb_system_background_task_started_total</code> (COUNTER)</dt>
  <dd>Zahájené úlohy na pozadí<br/><br/><strong>Popisky:</strong> <Term>taskName</Term><br/></dd>
  <dt><code>io_evitadb_system_background_task_timed_out_timed_out_tasks</code> (COUNTER)</dt>
  <dd><strong>Úlohy s vypršeným časem</strong>: Počet úloh, u kterých vypršel čas a byly zrušeny.<br/><br/><strong>Popisky:</strong> <Term>taskName</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_cache_anteroom_record_limit</code> (GAUGE)</dt>
  <dd><strong>Maximální počet záznamů v předsíni cache</strong>: Nastavený práh pro maximální počet záznamů v předsíni cache (`cache.anteroomRecordCount`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_cache_reevaluation_seconds</code> (GAUGE)</dt>
  <dd><strong>Interval přehodnocení cache v sekundách</strong>: Nastavený práh pro interval přehodnocení cache v sekundách (`cache.reevaluateEachSeconds`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_cache_size_in_bytes</code> (GAUGE)</dt>
  <dd><strong>Maximální velikost cache v bajtech</strong>: Nastavený práh pro maximální velikost cache v bajtech (`cache.cacheSizeInBytes`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_catalogs</code> (GAUGE)</dt>
  <dd><strong>Počet katalogů</strong>: Počet přístupných katalogů spravovaných touto instancí evitaDB.<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_uzavřít_relace_po_vteřinách_neaktivity</code> (GAUGE)</dt>
  <dd><strong>Uzavření relací po nečinnosti</strong>: Počet sekund, po kterých je relace uzavřena, pokud je neaktivní.<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_compaction_file_size_threshold_bytes</code> (GAUGE)</dt>
  <dd><strong>Minimální práh velikosti souboru pro zahájení komprese v bajtech</strong>: Nastavený práh pro minimální velikost souboru pro zahájení komprese v bajtech (`storage.fileSizeCompactionThresholdBytes`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_compaction_minimal_active_record_share_percent</code> (GAUGE)</dt>
  <dd><strong>Minimální procento aktivních záznamů v souboru pro zahájení komprese v %.</strong>: Nastavený práh pro minimální procento aktivních záznamů v souboru pro zahájení komprese v % (`storage.minimalActiveRecordShare`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_corrupted_catalogs</code> (GAUGE)</dt>
  <dd><strong>Počet poškozených katalogů</strong>: Počet poškozených katalogů, které evitaDB nemohla načíst.<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_inactive_catalogs</code> (GAUGE)</dt>
  <dd><strong>Počet neaktivních katalogů</strong>: Počet nepřístupných (nahraných do paměti) katalogů přítomných ve složce úložiště této instance evitaDB.<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_query_timeout_seconds</code> (GAUGE)</dt>
  <dd><strong>Timeout požadavku pouze pro čtení v sekundách</strong>: Nastavený práh pro timeout požadavku pouze pro čtení v sekundách (`server.queryTimeoutInMilliseconds`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_read_only_handles_limit</code> (GAUGE)</dt>
  <dd><strong>Maximální počet otevřených read-only handle</strong>: Nastavený práh pro maximální počet otevřených read-only handle (`storage.maxOpenedReadHandles`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_request_max_threads</code> (GAUGE)</dt>
  <dd><strong>Maximální počet vláken pro zpracování požadavků pouze pro čtení</strong>: Nastavený práh pro maximální počet vláken pro zpracování požadavků pouze pro čtení (`server.requestThreadPool.maxThreadCount`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_request_max_threads_queue_size</code> (GAUGE)</dt>
  <dd><strong>Maximální velikost fronty pro zpracování požadavků pouze pro čtení</strong>: Nastavený práh pro maximální velikost fronty pro zpracování požadavků pouze pro čtení (`server.requestThreadPool.queueSize`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_service_max_threads</code> (GAUGE)</dt>
  <dd><strong>Maximální počet vláken pro servisní úlohy</strong>: Nastavený práh pro maximální počet vláken pro servisní úlohy (`server.serviceThreadPool.maxThreadCount`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_service_max_threads_queue_size</code> (GAUGE)</dt>
  <dd><strong>Maximální velikost fronty pro servisní úlohy</strong>: Nastavený práh pro maximální velikost fronty pro servisní úlohy (`server.serviceThreadPool.queueSize`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_session_max_inactive_age_seconds</code> (GAUGE)</dt>
  <dd><strong>Maximální doba neaktivity relace v sekundách</strong>: Nastavený práh pro maximální dobu neaktivity relace v sekundách (`server.closeSessionsAfterSecondsOfInactivity`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_time_travel_enabled</code> (GAUGE)</dt>
  <dd><strong>Cestování v čase povoleno</strong>: Příznak indikující, zda je cestování v čase povoleno.<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_total</code> (COUNTER)</dt>
  <dd>Celkový počet spuštění Evita<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_traffic_recording_enabled</code> (GAUGE)</dt>
  <dd><strong>Záznam provozu povolen</strong>: Příznak indikující, zda je povolen záznam provozu.<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_transaction_flush_frequency_in_millis</code> (GAUGE)</dt>
  <dd><strong>Frekvence flushování transakcí</strong>: Frekvence flushování transakcí v milisekundách.<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_transaction_max_threads</code> (GAUGE)</dt>
  <dd><strong>Maximální počet vláken pro požadavky na čtení/zápis</strong>: Nastavený práh pro maximální počet vláken pro požadavky na čtení/zápis (`server.transactionThreadPool.maxThreadCount`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_transaction_max_threads_queue_size</code> (GAUGE)</dt>
  <dd><strong>Maximální velikost fronty pro požadavky na čtení/zápis</strong>: Nastavený práh pro maximální velikost fronty pro požadavky na čtení/zápis (`server.transactionThreadPool.queueSize`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_transaction_memory_buffer_limit_size_bytes</code> (GAUGE)</dt>
  <dd><strong>Velikost off-heap paměťového bufferu pro transakce v bajtech</strong>: Nastavený práh pro velikost off-heap paměťového bufferu pro transakce v bajtech (`transaction.transactionMemoryBufferLimitSizeBytes`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_transaction_memory_regions</code> (GAUGE)</dt>
  <dd><strong>Počet off-heap paměťových regionů pro transakce</strong>: Nastavený práh pro počet off-heap paměťových regionů pro transakce (`transaction.transactionMemoryRegionCount`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_transaction_timeout_seconds</code> (GAUGE)</dt>
  <dd><strong>Timeout požadavku na čtení/zápis v sekundách</strong>: Nastavený práh pro timeout požadavku na čtení/zápis v sekundách (`server.transactionTimeoutInMilliseconds`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_wal_max_file_count_kept</code> (GAUGE)</dt>
  <dd><strong>Maximální počet uchovávaných write-ahead log souborů</strong>: Nastavený práh pro maximální počet uchovávaných write-ahead log souborů (`transaction.walFileCountKept`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_wal_max_file_size_bytes</code> (GAUGE)</dt>
  <dd><strong>Maximální velikost write-ahead log souboru v bajtech</strong>: Nastavený práh pro maximální velikost write-ahead log souboru v bajtech (`transaction.walFileSizeBytes`).<br/><br/><strong>Popisky:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_request_thread_pool_statistics_active</code> (GAUGE)</dt>
  <dd><strong>Aktivní úlohy</strong>: Přibližný počet vláken, která aktuálně vykonávají úlohy</dd>
  <dt><code>io_evitadb_system_request_thread_pool_statistics_completed</code> (COUNTER)</dt>
  <dd><strong>Dokončené úlohy</strong>: Počet úloh, které byly dokončeny od předchozího měření; metrická pipeline akumuluje tyto přírůstky do celkového počítadla dokončených úloh</dd>
  <dt><code>io_evitadb_system_request_thread_pool_statistics_largest_pool_size</code> (GAUGE)</dt>
  <dd><strong>Největší počet pracovníků</strong>: Největší počet vláken, která kdy byla současně v poolu</dd>
  <dt><code>io_evitadb_system_request_thread_pool_statistics_pool_core</code> (GAUGE)</dt>
  <dd><strong>Minimální počet pracovníků</strong>: Základní počet vláken pro pool</dd>
  <dt><code>io_evitadb_system_request_thread_pool_statistics_pool_max</code> (GAUGE)</dt>
  <dd><strong>Maximální počet pracovníků</strong>: Maximální povolený počet vláken v poolu</dd>
  <dt><code>io_evitadb_system_request_thread_pool_statistics_pool_size</code> (GAUGE)</dt>
  <dd><strong>Aktuální počet pracovníků</strong>: Aktuální počet vláken v poolu</dd>
  <dt><code>io_evitadb_system_request_thread_pool_statistics_queue_remaining</code> (GAUGE)</dt>
  <dd><strong>Zbývající fronta</strong>: Přibližný počet dalších úloh, které může executor ještě přijmout do backlogu; přesný význam je specifický pro executor (rezerva vůči nastavenému měkkému limitu fronty pro request/transaction pooly, nebo zbývající kapacita podpůrné fronty pro plánovací pool)</dd>
  <dt><code>io_evitadb_system_request_thread_pool_statistics_queued</code> (GAUGE)</dt>
  <dd><strong>Zařazené úlohy</strong>: Přibližný počet zařazených úloh čekajících na provedení</dd>
  <dt><code>io_evitadb_system_ring_buffer_statistics_items_accepted</code> (COUNTER)</dt>
  <dd><strong>Přijaté položky</strong>: Celkový počet položek přijatých do bufferu od jeho vytvoření.</dd>
  <dt><code>io_evitadb_system_ring_buffer_statistics_items_available</code> (GAUGE)</dt>
  <dd><strong>Dostupné položky</strong>: Aktuální počet položek dostupných ke skenování/kopírování s ohledem na efektivní koncový watermark.</dd>
  <dt><code>io_evitadb_system_ring_buffer_statistics_items_copied</code> (COUNTER)</dt>
  <dd><strong>Zkopírované položky</strong>: Celkový počet položek zkopírovaných z bufferu pomocí operací kopírování od jeho vytvoření.</dd>
  <dt><code>io_evitadb_system_ring_buffer_statistics_items_present</code> (GAUGE)</dt>
  <dd><strong>Přítomné položky</strong>: Aktuální počet položek přítomných v bufferu.</dd>
  <dt><code>io_evitadb_system_ring_buffer_statistics_items_scanned</code> (COUNTER)</dt>
  <dd><strong>Prohledané položky</strong>: Celkový počet položek prohledaných pomocí operací forEach od jeho vytvoření.</dd>
  <dt><code>io_evitadb_system_scheduled_executor_statistics_active</code> (GAUGE)</dt>
  <dd><strong>Aktivní úlohy</strong>: Přibližný počet vláken, která aktuálně vykonávají úlohy</dd>
  <dt><code>io_evitadb_system_scheduled_executor_statistics_completed</code> (COUNTER)</dt>
  <dd><strong>Dokončené úlohy</strong>: Počet úloh, které byly dokončeny od předchozího měření; metrická pipeline akumuluje tyto přírůstky do celkového počítadla dokončených úloh</dd>
  <dt><code>io_evitadb_system_scheduled_executor_statistics_largest_pool_size</code> (GAUGE)</dt>
  <dd><strong>Největší počet pracovníků</strong>: Největší počet vláken, která kdy byla současně v poolu</dd>
  <dt><code>io_evitadb_system_scheduled_executor_statistics_pool_core</code> (GAUGE)</dt>
  <dd><strong>Minimální počet pracovníků</strong>: Základní počet vláken pro pool</dd>
  <dt><code>io_evitadb_system_scheduled_executor_statistics_pool_max</code> (GAUGE)</dt>
  <dd><strong>Maximální počet pracovníků</strong>: Maximální povolený počet vláken v poolu</dd>
  <dt><code>io_evitadb_system_scheduled_executor_statistics_pool_size</code> (GAUGE)</dt>
  <dd><strong>Aktuální počet pracovníků</strong>: Aktuální počet vláken v poolu</dd>
  <dt><code>io_evitadb_system_scheduled_executor_statistics_queue_remaining</code> (GAUGE)</dt>
  <dd><strong>Zbývající fronta</strong>: Přibližný počet dalších úloh, které může executor ještě přijmout do backlogu; přesný význam je specifický pro executor (rezerva vůči nastavenému měkkému limitu fronty pro request/transaction pooly, nebo zbývající kapacita podpůrné fronty pro plánovací pool)</dd>
  <dt><code>io_evitadb_system_scheduled_executor_statistics_queued</code> (GAUGE)</dt>
  <dd><strong>Zařazené úlohy</strong>: Přibližný počet zařazených úloh čekajících na provedení</dd>
  <dt><code>io_evitadb_system_transaction_thread_pool_statistics_active</code> (GAUGE)</dt>
  <dd><strong>Aktivní úlohy</strong>: Přibližný počet vláken, která aktuálně vykonávají úlohy</dd>
  <dt><code>io_evitadb_system_transaction_thread_pool_statistics_completed</code> (COUNTER)</dt>
  <dd><strong>Dokončené úlohy</strong>: Počet úloh, které byly dokončeny od předchozího měření; metrická pipeline akumuluje tyto přírůstky do celkového počítadla dokončených úloh</dd>
  <dt><code>io_evitadb_system_transaction_thread_pool_statistics_largest_pool_size</code> (GAUGE)</dt>
  <dd><strong>Největší počet pracovníků</strong>: Největší počet vláken, která kdy byla současně v poolu</dd>
  <dt><code>io_evitadb_system_transaction_thread_pool_statistics_pool_core</code> (GAUGE)</dt>
  <dd><strong>Minimální počet pracovníků</strong>: Základní počet vláken pro pool</dd>
  <dt><code>io_evitadb_system_transaction_thread_pool_statistics_pool_max</code> (GAUGE)</dt>
  <dd><strong>Maximální počet pracovníků</strong>: Maximální povolený počet vláken v poolu</dd>
  <dt><code>io_evitadb_system_transaction_thread_pool_statistics_pool_size</code> (GAUGE)</dt>
  <dd><strong>Aktuální počet pracovníků</strong>: Aktuální počet vláken v poolu</dd>
  <dt><code>io_evitadb_system_transaction_thread_pool_statistics_queue_remaining</code> (GAUGE)</dt>
  <dd><strong>Zbývající fronta</strong>: Přibližný počet dalších úloh, které může executor ještě přijmout do backlogu; přesný význam je specifický pro executor (rezerva vůči nastavenému měkkému limitu fronty pro request/transaction pooly, nebo zbývající kapacita podpůrné fronty pro plánovací pool)</dd>
  <dt><code>io_evitadb_system_transaction_thread_pool_statistics_queued</code> (GAUGE)</dt>
  <dd><strong>Zařazené úlohy</strong>: Přibližný počet zařazených úloh čekajících na provedení</dd>
</dl>

#### Transakce

<dl>
  <dt><code>io.evitadb.transaction.WalStatistics.oldestWalEntryTimestampSeconds</code> (GAUGE)</dt>
  <dd><strong>Časové razítko nejstarší položky WAL</strong>: Časové razítko nejstarší položky WAL v souborech WAL (aktivních nebo historických).</dd>
  <dt><code>io.evitadb.transaction.WalStatistics.oldestWalEntryTimestampSeconds</code> (GAUGE)</dt>
  <dd><strong>Časové razítko nejstarší položky WAL</strong>: Časové razítko nejstarší položky WAL v souborech WAL (aktivních nebo historických).</dd>
  <dt><code>io_evitadb_transaction_catalog_goes_live_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba přechodu katalogu do živého stavu</dd>
  <dt><code>io_evitadb_transaction_catalog_goes_live_total</code> (COUNTER)</dt>
  <dd>Počet přechodů katalogu do živého stavu</dd>
  <dt><code>io_evitadb_transaction_isolated_wal_file_closed_total</code> (COUNTER)</dt>
  <dd>Uzavřené soubory pro izolované WAL úložiště.</dd>
  <dt><code>io_evitadb_transaction_isolated_wal_file_opened_total</code> (COUNTER)</dt>
  <dd>Otevřené soubory pro izolované WAL úložiště.</dd>
  <dt><code>io_evitadb_transaction_new_catalog_version_propagated_collapsed_transactions</code> (COUNTER)</dt>
  <dd><strong>Transakce propagované do živého pohledu.</strong>: Počet transakcí, které byly propagovány do živého pohledu v rámci jednoho přechodu.</dd>
  <dt><code>io_evitadb_transaction_new_catalog_version_propagated_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba propagace nové verze katalogu v milisekundách</dd>
  <dt><code>io_evitadb_transaction_new_catalog_version_propagated_total</code> (COUNTER)</dt>
  <dd>Propagované verze katalogu</dd>
  <dt><code>io_evitadb_transaction_off_heap_memory_allocation_change_allocated_memory_bytes</code> (GAUGE)</dt>
  <dd><strong>Alokovaná paměť v bajtech</strong>: Množství paměti alokované pro off-heap úložiště v bajtech.</dd>
  <dt><code>io_evitadb_transaction_off_heap_memory_allocation_change_used_memory_bytes</code> (GAUGE)</dt>
  <dd><strong>Využitá paměť v bajtech</strong>: Množství paměti využité pro off-heap úložiště v bajtech.</dd>
  <dt><code>io_evitadb_transaction_transaction_accepted_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba řešení konfliktů v milisekundách<br/><br/><strong>Popisky:</strong> <Term>rozlišení</Term><br/></dd>
  <dt><code>io_evitadb_transaction_transaction_accepted_total</code> (COUNTER)</dt>
  <dd>Přijaté transakce<br/><br/><strong>Popisky:</strong> <Term>rozlišení</Term><br/></dd>
  <dt><code>io_evitadb_transaction_transaction_appended_to_wal_appended_atomic_mutations</code> (COUNTER)</dt>
  <dd><strong>Připojené atomické mutace.</strong>: Počet atomických mutací (schéma, schéma katalogu nebo entity) připojených do sdíleného WAL.</dd>
  <dt><code>io_evitadb_transaction_transaction_appended_to_wal_appended_wal_bytes</code> (COUNTER)</dt>
  <dd><strong>Velikost zapsaného WAL v bajtech.</strong>: Velikost zapsaného WAL v bajtech.</dd>
  <dt><code>io_evitadb_transaction_transaction_appended_to_wal_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba připojení transakce do sdíleného WAL v milisekundách</dd>
  <dt><code>io_evitadb_transaction_transaction_appended_to_wal_total</code> (COUNTER)</dt>
  <dd>Transakce připojené do WAL</dd>
  <dt><code>io_evitadb_transaction_transaction_conflict_total</code> (COUNTER)</dt>
  <dd>Detekované konflikty transakcí<br/><br/><strong>Popisky:</strong> <Term>conflictPolicy</Term>, <Term>conflictScope</Term>, <Term>resolutionLayer</Term><br/></dd>
  <dt><code>io_evitadb_transaction_transaction_finished_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba trvání životnosti transakce v milisekundách<br/><br/><strong>Popisky:</strong> <Term>rozlišení</Term><br/></dd>
  <dt><code>io_evitadb_transaction_transaction_finished_oldest_transaction_timestamp_seconds</code> (GAUGE)</dt>
  <dd><strong>Časové razítko nejstarší transakce</strong>: Časové razítko nejstarší nedokončené (běžící) transakce v katalogu.<br/><br/><strong>Popisky:</strong> <Term>rozlišení</Term><br/></dd>
  <dt><code>io_evitadb_transaction_transaction_finished_total</code> (COUNTER)</dt>
  <dd>Dokončené transakce<br/><br/><strong>Popisky:</strong> <Term>rozlišení</Term><br/></dd>
  <dt><code>io_evitadb_transaction_transaction_incorporated_to_trunk_collapsed_transactions</code> (COUNTER)</dt>
  <dd><strong>Transakce začleněné do sdílených datových struktur.</strong>: N/A</dd>
  <dt><code>io_evitadb_transaction_transaction_incorporated_to_trunk_incorporation_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba začlenění v milisekundách</dd>
  <dt><code>io_evitadb_transaction_transaction_incorporated_to_trunk_processed_atomic_mutations</code> (COUNTER)</dt>
  <dd><strong>Zpracované atomické mutace.</strong>: N/A</dd>
  <dt><code>io_evitadb_transaction_transaction_incorporated_to_trunk_processed_local_mutations</code> (COUNTER)</dt>
  <dd><strong>Zpracované lokální mutace.</strong>: N/A</dd>
  <dt><code>io_evitadb_transaction_transaction_processed_lag_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Zpoždění transakce</strong>: Čas, který trvalo, než se transakce stala viditelnou pro všechny nové relace. Jinými slovy, čas mezi potvrzením transakce a ovlivněním sdíleného pohledu.</dd>
  <dt><code>io_evitadb_transaction_transaction_queued_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba čekání transakce ve frontě.<br/><br/><strong>Popisky:</strong> <Term>stage</Term><br/></dd>
  <dt><code>io_evitadb_transaction_transaction_started_total</code> (COUNTER)</dt>
  <dd>Zahájené transakce</dd>
  <dt><code>io_evitadb_transaction_wal_cache_size_changed_locations_cached</code> (GAUGE)</dt>
  <dd><strong>Celkový počet cachovaných umístění v souboru WAL</strong>: Celkový počet cachovaných umístění (používaných pro rychlé vyhledávání mutací) ve sdíleném souboru WAL.</dd>
  <dt><code>io_evitadb_transaction_wal_rotation_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Doba rotace WAL v milisekundách</dd>
  <dt><code>io_evitadb_transaction_wal_rotation_total</code> (COUNTER)</dt>
  <dd>Rotace WAL</dd>
</dl>

#### Statické metriky

<dl>
  <dt><code>io_evitadb_build_info</code> (INFO)</dt>
  <dd><strong>Informace o sestavení evitaDB</strong>: konstantní metrika typu <code>info</code>, která zveřejňuje verzi běžícího serveru, zkrácený hash git commitu a verzi JVM. Užitečné pro sledování nasazení bez nutnosti nahlížet do logů.<br/><br/><strong>Štítky:</strong> <Term>verze</Term>, <Term>commit</Term>, <Term>java_version</Term><br/></dd>
  <dt><code>io_evitadb_probe_health_problem</code> (GAUGE)</dt>
  <dd><strong>Indikátor zdravotního problému</strong>: nastaveno na <code>1</code> po dobu, kdy je pojmenovaný zdravotní problém aktivní, a vrací se na <code>0</code> po jeho odstranění.<br/><br/><strong>Štítky:</strong> <Term>typ_problému</Term><br/></dd>
  <dt><code>io_evitadb_probe_api_readiness</code> (GAUGE)</dt>
  <dd><strong>Připravenost API</strong>: <code>1</code>, když je pojmenované externí API připraveno přijímat požadavky (ověřeno interním HTTP dotazem), jinak <code>0</code>.<br/><br/><strong>Štítky:</strong> <Term>api_type</Term><br/></dd>
  <dt><code>jvm_errors_total</code> (COUNTER)</dt>
  <dd><strong>Chyby JVM</strong>: celkový počet interních chyb JVM, rozdělených podle typu chyby.<br/><br/><strong>Štítky:</strong> <Term>error_type</Term><br/></dd>
  <dt><code>io_evitadb_errors_total</code> (COUNTER)</dt>
  <dd><strong>Chyby evitaDB</strong>: celkový počet interních chyb evitaDB, rozdělených podle typu chyby.<br/><br/><strong>Štítky:</strong> <Term>error_type</Term><br/></dd>
  <dt><code>io_evitadb_client_errors_total</code> (COUNTER)</dt>
  <dd><strong>Chyby klienta</strong>: celkový počet výskytů <code>EvitaInvalidUsageException</code> vyvolaných požadavky klienta, rozdělených podle typu chyby.<br/><br/><strong>Štítky:</strong> <Term>error_type</Term><br/></dd>
</dl>