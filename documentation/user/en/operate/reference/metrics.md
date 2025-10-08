### Metrics

<UsedTerms>
  <h4>Labels used in metrics</h4>
  <dl>
    <dt>api</dt>
    <dd><strong>API type</strong>: The identification of the API being probed.</dd>
    <dt>area</dt>
    <dd><strong>Area</strong>: Area for which events are published.</dd>
    <dt>buildType</dt>
    <dd><strong>Build type</strong>: Type of the instance build: NEW or REFRESH</dd>
    <dt>catalogName</dt>
    <dd><strong>Catalog</strong>: The name of the catalog to which this event/metric is associated.</dd>
    <dt>entityType</dt>
    <dd><strong>Entity type</strong>: The name of the related entity type (collection).</dd>
    <dt>fileType</dt>
    <dd><strong>File type</strong>: The type of the file that was flushed. One of: CATALOG, ENTITY_COLLECTION, WAL, or BOOTSTRAP</dd>
    <dt>graphQLInstanceType</dt>
    <dd><strong>GraphQL instance type</strong>: Domain of the GraphQL API used in connection with this event/metric: SYSTEM, SCHEMA, or DATA</dd>
    <dt>graphQLOperationType</dt>
    <dd><strong>GraphQL operation type</strong>: The type of operation specified in the GQL request: QUERY, MUTATION, or SUBSCRIPTION.</dd>
    <dt>grpcResponseStatus</dt>
    <dd><strong>gRPC response status</strong>: State of the gRPC response (OK, ERROR, CANCELED).</dd>
    <dt>httpMethod</dt>
    <dd><strong>HTTP method</strong>: The HTTP method of the request.</dd>
    <dt>httpStatusCode</dt>
    <dd><strong>HTTP status code</strong>: The HTTP response status code that was sent to client.</dd>
    <dt>initiator</dt>
    <dd><strong>Initiator of the call</strong>: Initiator of the gRPC call (either client or server).</dd>
    <dt>instanceId</dt>
    <dd><strong>Server instance id</strong>: Unique server name taken from the configuration file.</dd>
    <dt>name</dt>
    <dd><strong>Logical file name</strong>: The logical name of the file that was flushed. Identifies the file more precisely.</dd>
    <dt>operationId</dt>
    <dd><strong>Operation ID</strong>: The ID of the operation that was executed.</dd>
    <dt>operationName</dt>
    <dd><strong>GraphQL operation</strong>: The name of the operation specified in the GQL request.</dd>
    <dt>prefetched</dt>
    <dd><strong>Prefetched vs. non-prefetched query</strong>: Whether or not the query used a prefetch plan. Prefetch plan optimistically fetches queried entities in advance and executes directly on them (without accessing the indexes).</dd>
    <dt>probeResult</dt>
    <dd><strong>Probe result</strong>: The result of the readiness probe (ok, timeout, error).</dd>
    <dt>procedureName</dt>
    <dd><strong>Procedure name</strong>: Name of the gRPC procedure that was called (the method name).</dd>
    <dt>prospective</dt>
    <dd><strong>Prospective (client/server)</strong>: Identifies whether the event represents whether event represents server or client view of readiness.
Client view is the duration viewed from the HTTP client side affected by timeouts, server view is the real
duration of the probe.</dd>
    <dt>recordType</dt>
    <dd><strong>Record type</strong>: Type of records that changed in the OffsetIndex.</dd>
    <dt>requestResult</dt>
    <dd><strong>Request result</strong>: Simplified result of the request (success, error, cancelled).</dd>
    <dt>resolution</dt>
    <dd><strong>Transaction resolution</strong>: The resolution of the transaction (either commit or rollback).</dd>
    <dt>responseStatus</dt>
    <dd><strong>Response status</strong>: The status of the response: OK or ERROR.</dd>
    <dt>restInstanceType</dt>
    <dd><strong>REST instance type</strong>: Domain of the REST API used in connection with this event/metric: SYSTEM, or CATALOG</dd>
    <dt>restOperationType</dt>
    <dd><strong>REST operation type</strong>: The type of operation that was executed. One of: QUERY, MUTATION.</dd>
    <dt>serverVersion</dt>
    <dd><strong>Server version</strong>: Precise version of the evitaDB server.</dd>
    <dt>serviceName</dt>
    <dd><strong>Service name</strong>: Name of the gRPC service that was called (the name of the Java class).</dd>
    <dt>stage</dt>
    <dd><strong>Transaction stage</strong>: The name of the stage the transaction is waiting for.</dd>
    <dt>taskName</dt>
    <dd><strong>Task name</strong>: Name of the background task.</dd>
  </dl>
</UsedTerms>

#### API

<dl>
  <dt><code>io_evitadb_external_api_readiness_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Readiness probe duration<br/><br/><strong>Labels:</strong> <Term>api</Term>, <Term>probeResult</Term>, <Term>prospective</Term><br/></dd>
  <dt><code>io_evitadb_external_api_readiness_total</code> (COUNTER)</dt>
  <dd>Readiness probe invoked total<br/><br/><strong>Labels:</strong> <Term>api</Term>, <Term>probeResult</Term>, <Term>prospective</Term><br/></dd>
  <dt><code>io_evitadb_external_api_request_total</code> (COUNTER)</dt>
  <dd>Requests invoked total<br/><br/><strong>Labels:</strong> <Term>api</Term>, <Term>httpStatusCode</Term>, <Term>requestResult</Term><br/></dd>
</dl>

#### API / GraphQL / Instance / Schema

<dl>
  <dt><code>io_evitadb_external_api_graphql_instance_built_graph_qlinstance_build_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>API build duration</strong>: Duration of build of a single API in milliseconds.<br/><br/><strong>Labels:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>graphQLInstanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_instance_built_graph_qlschema_build_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>GraphQL schema build duration</strong>: Duration of build of a single GraphQL API schema in milliseconds.<br/><br/><strong>Labels:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>graphQLInstanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_instance_built_graph_qlschema_dsl_lines</code> (GAUGE)</dt>
  <dd><strong>Number of lines</strong>: Number of lines generated in the built GraphQL schema DSL.<br/><br/><strong>Labels:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>graphQLInstanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_instance_built_total</code> (COUNTER)</dt>
  <dd>GraphQL instance built total<br/><br/><strong>Labels:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>graphQLInstanceType</Term><br/></dd>
</dl>

#### API / gRPC

<dl>
  <dt><code>io_evitadb_api_grpc_evita_procedure_called_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>gRPC evitaDB procedure called duration<br/><br/><strong>Labels:</strong> <Term>grpcResponseStatus</Term>, <Term>initiator</Term>, <Term>procedureName</Term>, <Term>serviceName</Term><br/></dd>
  <dt><code>io_evitadb_api_grpc_evita_procedure_called_total</code> (COUNTER)</dt>
  <dd>gRPC evitaDB procedure called total<br/><br/><strong>Labels:</strong> <Term>grpcResponseStatus</Term>, <Term>initiator</Term>, <Term>procedureName</Term>, <Term>serviceName</Term><br/></dd>
  <dt><code>io_evitadb_api_grpc_session_procedure_called_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>gRPC session procedure called duration<br/><br/><strong>Labels:</strong> <Term>grpcResponseStatus</Term>, <Term>initiator</Term>, <Term>procedureName</Term>, <Term>serviceName</Term><br/></dd>
  <dt><code>io_evitadb_api_grpc_session_procedure_called_total</code> (COUNTER)</dt>
  <dd>gRPC session procedure called total<br/><br/><strong>Labels:</strong> <Term>grpcResponseStatus</Term>, <Term>initiator</Term>, <Term>procedureName</Term>, <Term>serviceName</Term><br/></dd>
</dl>

#### CDC

<dl>
  <dt><code>io_evitadb_cdc_change_catalog_capture_statistics_events_published_total</code> (COUNTER)</dt>
  <dd><strong>Published events</strong>: The number of events published to all subscribers.</dd>
  <dt><code>io_evitadb_cdc_change_catalog_capture_statistics_lagging_subscribers</code> (GAUGE)</dt>
  <dd><strong>Lagging subscribers</strong>: The number of subscribers fetching the WAL records.</dd>
  <dt><code>io_evitadb_cdc_change_catalog_capture_statistics_per_area_events_published_total</code> (COUNTER)</dt>
  <dd><strong>Published events</strong>: The number of events published to all subscribers.<br/><br/><strong>Labels:</strong> <Term>area</Term><br/></dd>
  <dt><code>io_evitadb_cdc_change_catalog_capture_statistics_per_entity_type_events_published_total</code> (COUNTER)</dt>
  <dd><strong>Published events</strong>: The number of events published to all subscribers.<br/><br/><strong>Labels:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_cdc_change_catalog_capture_statistics_shared_publishers</code> (GAUGE)</dt>
  <dd><strong>Publisher count</strong>: The number of shared publishers active in the system.</dd>
  <dt><code>io_evitadb_cdc_change_catalog_capture_statistics_subscribers</code> (GAUGE)</dt>
  <dd><strong>Subscriber count</strong>: The number of subscribers active in the system.</dd>
</dl>

#### Cache

<dl>
  <dt><code>io_evitadb_cache_anteroom_record_statistics_updated_records</code> (GAUGE)</dt>
  <dd><strong>Number of records waiting in anteroom</strong>: The number of cacheable but not yet cached records that are collecting usage statistics to evaluate for becoming cached.</dd>
  <dt><code>io_evitadb_cache_anteroom_wasted_total</code> (COUNTER)</dt>
  <dd>Anteroom wasted total</dd>
</dl>

#### ExternalAPI / GraphQL / Request

<dl>
  <dt><code>io_evitadb_external_api_graphql_request_executed_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>GraphQL request execution duration<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_execution_api_overhead_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Request execution overhead</strong>: Time to execute the entire request in milliseconds without internal evitaDB execution.<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_input_deserialization_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Input deserialization duration</strong>: Time to deserialize the incoming JSON input GraphQL request to internal structure in milliseconds.<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_internal_evitadb_input_reconstruction_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>evitaDB input reconstruction duration</strong>: Time to reconstruct query input into evitaDB engine in milliseconds. Usually converts JSON query into internal evitaDB query representation or JSON mutations into internal evitaDB mutation representation.<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_operation_execution_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Execution duration</strong>: Time to execute the entire parsed and validated GraphQL operation by the GraphQL server engine in milliseconds. Includes all data fetcher business logic, including evitaDB input reconstruction and evitaDB query execution.<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_parse_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Request parsing duration</strong>: Time to parse the GraphQL request (query and variables) by the GraphQL server engine from internal structure for validation and execution in milliseconds.<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_preparation_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Request preparation duration</strong>: Time to prepare and initialize the GraphQL server engine for parsing and executing the incoming request in milliseconds.<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_result_serialization_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Result serializatio duration</strong>: Time to serialize the final request result into output JSON in milliseconds.<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_root_fields_processed</code> (GAUGE)</dt>
  <dd><strong>Request root fields count</strong>: Number of root fields (queries, mutations) processed within a single GraphQL request.<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_total</code> (COUNTER)</dt>
  <dd>GraphQL request executed total<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_validation_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Validation duration</strong>: Time to validate the parsed request (query and variables) by the GraphQL server engine before execution in milliseconds.<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>graphQLInstanceType</Term>, <Term>graphQLOperationType</Term>, <Term>operationName</Term>, <Term>responseStatus</Term><br/></dd>
</dl>

#### ExternalAPI / REST / Instance / Schema

<dl>
  <dt><code>io_evitadb_external_api_rest_instance_built_registered_rest_endpoints</code> (GAUGE)</dt>
  <dd><strong>Endpoints count</strong>: Number of registered endpoints in built OpenAPI schema<br/><br/><strong>Labels:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>restInstanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_instance_built_rest_instance_build_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>API build duration</strong>: Duration of build of a single API in milliseconds.<br/><br/><strong>Labels:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>restInstanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_instance_built_rest_schema_build_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>REST schema build duration</strong>: Duration of build of a single REST API schema in milliseconds.<br/><br/><strong>Labels:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>restInstanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_instance_built_rest_schema_dsl_lines</code> (GAUGE)</dt>
  <dd><strong>Number of lines</strong>: Number of lines generated in the built REST schema DSL.<br/><br/><strong>Labels:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>restInstanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_instance_built_total</code> (COUNTER)</dt>
  <dd>REST API instance built total<br/><br/><strong>Labels:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>restInstanceType</Term><br/></dd>
</dl>

#### ExternalAPI / REST / Request

<dl>
  <dt><code>io_evitadb_external_api_rest_request_executed_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>REST request execution duration<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>operationId</Term>, <Term>responseStatus</Term>, <Term>restInstanceType</Term>, <Term>restOperationType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_request_executed_execution_api_overhead_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Request execution overhead</strong>: Time to execute the request in milliseconds without internal evitaDB execution.<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>operationId</Term>, <Term>responseStatus</Term>, <Term>restInstanceType</Term>, <Term>restOperationType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_request_executed_input_deserialization_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Input deserialization duration</strong>: Time to deserialize the incoming JSON input REST request to internal structure in milliseconds.<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>operationId</Term>, <Term>responseStatus</Term>, <Term>restInstanceType</Term>, <Term>restOperationType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_request_executed_internal_evitadb_input_reconstruction_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>evitaDB input reconstruction duration</strong>: Time to reconstruct query input into evitaDB engine in milliseconds. Usually converts JSON query into internal evitaDB query representation or JSON mutations into internal evitaDB mutation representation.<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>operationId</Term>, <Term>responseStatus</Term>, <Term>restInstanceType</Term>, <Term>restOperationType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_request_executed_operation_execution_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Execution duration</strong>: Time to execute the entire parsed and validated REST operation by the server engine in milliseconds. Includes all handler business logic, including evitaDB input reconstruction and evitaDB query execution.<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>operationId</Term>, <Term>responseStatus</Term>, <Term>restInstanceType</Term>, <Term>restOperationType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_request_executed_result_serialization_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Result serializatio duration</strong>: Time to serialize the final request result into output JSON in milliseconds.<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>operationId</Term>, <Term>responseStatus</Term>, <Term>restInstanceType</Term>, <Term>restOperationType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_request_executed_total</code> (COUNTER)</dt>
  <dd>REST request executed total<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>operationId</Term>, <Term>responseStatus</Term>, <Term>restInstanceType</Term>, <Term>restOperationType</Term><br/></dd>
</dl>

#### Query

<dl>
  <dt><code>io_evitadb_query_entity_enrich_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Entity enrichment duration in milliseconds<br/><br/><strong>Labels:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_enrich_records</code> (COUNTER)</dt>
  <dd><strong>Records enriched total</strong>: The total number of records that were enriched.<br/><br/><strong>Labels:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_enrich_size_bytes</code> (HISTOGRAM)</dt>
  <dd><strong>Enrichment size in bytes</strong>: The size in Bytes of the additional fetched and enriched data.<br/><br/><strong>Labels:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_enrich_total</code> (COUNTER)</dt>
  <dd>Entity enriched<br/><br/><strong>Labels:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_fetch_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Entity fetch duration in milliseconds<br/><br/><strong>Labels:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_fetch_records</code> (COUNTER)</dt>
  <dd><strong>Records fetched total</strong>: The total number of records that were fetched.<br/><br/><strong>Labels:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_fetch_size_bytes</code> (HISTOGRAM)</dt>
  <dd><strong>Fetched size in bytes</strong>: The total size of the fetched data in Bytes.<br/><br/><strong>Labels:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_fetch_total</code> (COUNTER)</dt>
  <dd>Entity fetched<br/><br/><strong>Labels:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Query duration in milliseconds<br/><br/><strong>Labels:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_estimated</code> (HISTOGRAM)</dt>
  <dd><strong>Estimated complexity info</strong>: The estimated complexity of the query.<br/><br/><strong>Labels:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_execution_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Query execution duration in milliseconds</strong>: The time it took to execute the selected execution plan for the query.<br/><br/><strong>Labels:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_fetched</code> (HISTOGRAM)</dt>
  <dd><strong>Records fetched total</strong>: The total number of records fetched from the data storage (excluding records found in the cache).<br/><br/><strong>Labels:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_fetched_size_bytes</code> (HISTOGRAM)</dt>
  <dd><strong>Fetched size in bytes</strong>: The total size of the fetched data in Bytes.<br/><br/><strong>Labels:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_found</code> (HISTOGRAM)</dt>
  <dd><strong>Records found total</strong>: The total number of records found (matching the query).<br/><br/><strong>Labels:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_plan_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Query planning duration in milliseconds</strong>: The time it took to build all the query execution plan variants.<br/><br/><strong>Labels:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_real</code> (HISTOGRAM)</dt>
  <dd><strong>Filter complexity</strong>: The real complexity of the query.<br/><br/><strong>Labels:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_returned</code> (HISTOGRAM)</dt>
  <dd><strong>Records returned total</strong>: The total number of records returned (included in the result).<br/><br/><strong>Labels:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_scanned</code> (HISTOGRAM)</dt>
  <dd><strong>Records scanned total</strong>: The total number of records scanned (included in the calculation).<br/><br/><strong>Labels:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_total</code> (COUNTER)</dt>
  <dd>Query finished<br/><br/><strong>Labels:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_store_traffic_traffic_recorder_statistics_created_sessions</code> (COUNTER)</dt>
  <dd><strong>Created sessions</strong>: Created sessions.</dd>
  <dt><code>io_evitadb_store_traffic_traffic_recorder_statistics_dropped_sessions</code> (COUNTER)</dt>
  <dd><strong>Dropped sessions</strong>: Counter of dropped sessions due to memory shortage.</dd>
  <dt><code>io_evitadb_store_traffic_traffic_recorder_statistics_finished_sessions</code> (COUNTER)</dt>
  <dd><strong>Finished sessions</strong>: Recorded sessions.</dd>
  <dt><code>io_evitadb_store_traffic_traffic_recorder_statistics_missed_records</code> (COUNTER)</dt>
  <dd><strong>Missed records</strong>: Counter of missed records due to memory shortage or sampling.</dd>
</dl>

#### Session

<dl>
  <dt><code>io_evitadb_session_closed_active_sessions</code> (GAUGE)</dt>
  <dd><strong>Number of still active sessions</strong>: The number of still active sessions at the time this session was closed.</dd>
  <dt><code>io_evitadb_session_closed_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Session lifespan duration in milliseconds</dd>
  <dt><code>io_evitadb_session_closed_mutations</code> (HISTOGRAM)</dt>
  <dd><strong>Number of mutation calls performed in session</strong>: The number of mutations made during this session.</dd>
  <dt><code>io_evitadb_session_closed_oldest_session_timestamp_seconds</code> (GAUGE)</dt>
  <dd><strong>Oldest session timestamp</strong>: The timestamp of the oldest session at the time that session was closed.</dd>
  <dt><code>io_evitadb_session_closed_queries</code> (HISTOGRAM)</dt>
  <dd><strong>Number of queries performed in session</strong>: The number of requests made during this session.</dd>
  <dt><code>io_evitadb_session_closed_total</code> (COUNTER)</dt>
  <dd>Sessions closed</dd>
  <dt><code>io_evitadb_session_killed_total</code> (COUNTER)</dt>
  <dd>Sessions killed</dd>
  <dt><code>io_evitadb_session_opened_total</code> (COUNTER)</dt>
  <dd>Sessions opened</dd>
</dl>

#### Storage

<dl>
  <dt><code>io_evitadb_storage_catalog_statistics_entity_collections</code> (GAUGE)</dt>
  <dd><strong>Entity collection count</strong>: The number of active entity collections (entity types) in the catalog.</dd>
  <dt><code>io_evitadb_storage_catalog_statistics_occupied_disk_space_bytes</code> (GAUGE)</dt>
  <dd><strong>Total occupied disk space in Bytes</strong>: The total amount of disk space used by the catalog in Bytes.</dd>
  <dt><code>io_evitadb_storage_catalog_statistics_oldest_catalog_version_timestamp_seconds</code> (GAUGE)</dt>
  <dd><strong>Timestamp of the oldest catalog version available in seconds</strong>: The age of the oldest available catalog version, in seconds. This value determines the furthest back in time the catalog can go.</dd>
  <dt><code>io_evitadb_storage_data_file_compact_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Duration of OffsetIndex compaction.<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_data_file_compact_total</code> (COUNTER)</dt>
  <dd>OffsetIndex compaction.<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_observable_output_change_occupied_memory_bytes</code> (GAUGE)</dt>
  <dd><strong>Memory occupied by opened output buffers in Bytes</strong>: The amount of memory in bytes occupied by open OffsetIndex output buffers.</dd>
  <dt><code>io_evitadb_storage_observable_output_change_opened_buffers</code> (GAUGE)</dt>
  <dd><strong>Number of opened output buffers</strong>: The number of open buffers used to write data to OffsetIndexes.</dd>
  <dt><code>io_evitadb_storage_observable_output_change_total</code> (COUNTER)</dt>
  <dd>ObservableOutput buffer count changes.</dd>
  <dt><code>io_evitadb_storage_offset_index_flush_active_disk_size_bytes</code> (GAUGE)</dt>
  <dd><strong>Active part of disk size in Bytes</strong>: The size in Bytes of the active part of the OffsetIndex on disk.<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_active_records</code> (GAUGE)</dt>
  <dd><strong>Number of active records</strong>: The number of active (accessible) records in the OffsetIndex.<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_disk_size_bytes</code> (GAUGE)</dt>
  <dd><strong>Disk size in Bytes</strong>: The size in Bytes of the OffsetIndex on disk.<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Duration of OffsetIndex flush to disk.<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_estimated_memory_size_bytes</code> (GAUGE)</dt>
  <dd><strong>Estimated memory size in Bytes</strong>: The estimated size in Bytes of the OffsetIndex in memory.<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_max_record_size</code> (GAUGE)</dt>
  <dd><strong>Biggest record Bytes</strong>: The size in Bytes of the biggest record in the OffsetIndex.<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_oldest_record_timestamp_seconds</code> (GAUGE)</dt>
  <dd><strong>Oldest record kept in memory timestamp in seconds</strong>: The timestamp in seconds of the oldest volatile record kept in memory. Volatile records are records that are not yet flushed to disk.<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_total</code> (COUNTER)</dt>
  <dd>OffsetIndex flushes to disk.<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_history_kept_oldest_record_timestamp_seconds</code> (GAUGE)</dt>
  <dd><strong>Oldest record kept in memory timestamp in seconds</strong>: The timestamp of the oldest catalog version data held in memory, in seconds. Data from previous versions is used to maintain the SNAPSHOT isolation contract for currently open sessions targeting older catalog versions. Zero if no data is retained.<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_non_flushed_record_size_bytes</code> (GAUGE)</dt>
  <dd><strong>Size of records pending flush in Bytes</strong>: Size of records pending flush in Bytes in the OffsetIndex.<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_non_flushed_records</code> (GAUGE)</dt>
  <dd><strong>Number of records pending flush</strong>: Number of volatile records pending flush in the OffsetIndex.<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_record_type_count_changed_records</code> (GAUGE)</dt>
  <dd><strong>Number of records</strong>: Total number of records of the specified type in the OffsetIndex.<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term>, <Term>recordType</Term><br/></dd>
  <dt><code>io_evitadb_storage_read_only_handle_closed_total</code> (COUNTER)</dt>
  <dd>Closed file read handles.<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_read_only_handle_opened_total</code> (COUNTER)</dt>
  <dd>Opened file read handles.<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
</dl>

#### System

<dl>
  <dt><code>io_evitadb_system_background_task_finished_total</code> (COUNTER)</dt>
  <dd>Background tasks finished<br/><br/><strong>Labels:</strong> <Term>taskName</Term><br/></dd>
  <dt><code>io_evitadb_system_background_task_rejected_total</code> (COUNTER)</dt>
  <dd>Background tasks rejected<br/><br/><strong>Labels:</strong> <Term>taskName</Term><br/></dd>
  <dt><code>io_evitadb_system_background_task_started_total</code> (COUNTER)</dt>
  <dd>Background tasks started<br/><br/><strong>Labels:</strong> <Term>taskName</Term><br/></dd>
  <dt><code>io_evitadb_system_background_task_timed_out_timed_out_tasks</code> (COUNTER)</dt>
  <dd><strong>Timed out tasks</strong>: Number of timed out and canceled tasks.<br/><br/><strong>Labels:</strong> <Term>taskName</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_cache_anteroom_record_limit</code> (GAUGE)</dt>
  <dd><strong>Maximum number of records in the cache anteroom</strong>: Configured threshold for the maximum number of records in the cache anteroom (`cache.anteroomRecordCount`).<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_cache_reevaluation_seconds</code> (GAUGE)</dt>
  <dd><strong>Cache reevaluation interval in seconds</strong>: Configured threshold for the cache reevaluation interval in seconds (`cache.reevaluateEachSeconds`).<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_cache_size_in_bytes</code> (GAUGE)</dt>
  <dd><strong>Maximum cache size in Bytes</strong>: Configured threshold for the maximum cache size in Bytes (`cache.cacheSizeInBytes`).<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_catalogs</code> (GAUGE)</dt>
  <dd><strong>Catalog count</strong>: Number of accessible catalogs managed by this instance of evitaDB.<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_close_sessions_after_seconds_of_inactivity</code> (GAUGE)</dt>
  <dd><strong>Close sessions after inactivity</strong>: Number of seconds after which the session is closed if it is inactive.<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_compaction_file_size_threshold_bytes</code> (GAUGE)</dt>
  <dd><strong>Minimum file size threshold to start compress in bytes</strong>: Configured threshold for the minimum file size threshold to start compress in bytes (`storage.fileSizeCompactionThresholdBytes`).<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_compaction_minimal_active_record_share_percent</code> (GAUGE)</dt>
  <dd><strong>Minimum percentage of active records in the file to start compacting in %.</strong>: Configured threshold for the minimum percentage of active records in the file to start compacting in % (`storage.minimalActiveRecordShare`).<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_corrupted_catalogs</code> (GAUGE)</dt>
  <dd><strong>Corrupted catalog count</strong>: Number of corrupted catalogs that evitaDB could not load.<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_inactive_catalogs</code> (GAUGE)</dt>
  <dd><strong>Inactive catalog count</strong>: Number of inaccessible (not loaded to memory) catalogs present in storage directory of this instance of evitaDB.<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_query_timeout_seconds</code> (GAUGE)</dt>
  <dd><strong>Read-only request timeout in seconds</strong>: Configured threshold for the read-only request timeout in seconds (`server.queryTimeoutInMilliseconds`).<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_read_only_handles_limit</code> (GAUGE)</dt>
  <dd><strong>Maximum number of open read-only handles</strong>: Configured threshold for the maximum number of open read-only handles (`storage.maxOpenedReadHandles`).<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_request_max_threads</code> (GAUGE)</dt>
  <dd><strong>Maximum number of threads to handle read-only requests</strong>: Configured threshold for the maximum number of threads to handle read-only requests (`server.requestThreadPool.maxThreadCount`).<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_request_max_threads_queue_size</code> (GAUGE)</dt>
  <dd><strong>Maximum queue size for read-only request handling</strong>: Configured threshold for the maximum queue size for read-only request handling (`server.requestThreadPool.queueSize`).<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_service_max_threads</code> (GAUGE)</dt>
  <dd><strong>Maximum number of threads for service tasks</strong>: Configured threshold for the maximum number of threads for service tasks (`server.serviceThreadPool.maxThreadCount`).<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_service_max_threads_queue_size</code> (GAUGE)</dt>
  <dd><strong>Maximum queue size for service tasks</strong>: Configured threshold for the maximum queue size for service tasks (`server.serviceThreadPool.queueSize`).<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_session_max_inactive_age_seconds</code> (GAUGE)</dt>
  <dd><strong>Maximum session inactivity time in seconds</strong>: Configured threshold for the maximum session inactivity time in seconds (`server.closeSessionsAfterSecondsOfInactivity`).<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_time_travel_enabled</code> (GAUGE)</dt>
  <dd><strong>Time travel enabled</strong>: Flag indicating whether the time travel is enabled.<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_total</code> (COUNTER)</dt>
  <dd>Evita started total<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_traffic_recording_enabled</code> (GAUGE)</dt>
  <dd><strong>Traffic recording enabled</strong>: Flag indicating whether the traffic recording is enabled.<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_transaction_flush_frequency_in_millis</code> (GAUGE)</dt>
  <dd><strong>Transaction flush frequency</strong>: Frequency of transaction flush in milliseconds.<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_transaction_max_threads</code> (GAUGE)</dt>
  <dd><strong>Maximum number of threads for read/write requests</strong>: Configured threshold for the maximum number of threads for read/write requests (`server.transactionThreadPool.maxThreadCount`).<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_transaction_max_threads_queue_size</code> (GAUGE)</dt>
  <dd><strong>Maximum queue size for read/write requests</strong>: Configured threshold for the maximum queue size for read/write requests (`server.transactionThreadPool.queueSize`).<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_transaction_memory_buffer_limit_size_bytes</code> (GAUGE)</dt>
  <dd><strong>Off-heap memory buffer size for transactions in Bytes</strong>: Configured threshold for the off-heap memory buffer size for transactions in Bytes (`transaction.transactionMemoryBufferLimitSizeBytes`).<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_transaction_memory_regions</code> (GAUGE)</dt>
  <dd><strong>Number of off-heap memory regions for transactions</strong>: Configured threshold for the number of off-heap memory regions for transactions (`transaction.transactionMemoryRegionCount`).<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_transaction_timeout_seconds</code> (GAUGE)</dt>
  <dd><strong>Read/write request timeout in seconds</strong>: Configured threshold for the read/write request timeout in seconds (`server.transactionTimeoutInMilliseconds`).<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_wal_max_file_count_kept</code> (GAUGE)</dt>
  <dd><strong>Maximum number of write-ahead log files to keep</strong>: Configured threshold for the maximum number of write-ahead log files to keep (`transaction.walFileCountKept`).<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_statistics_wal_max_file_size_bytes</code> (GAUGE)</dt>
  <dd><strong>Maximum write-ahead log file size in Bytes</strong>: Configured threshold for the maximum write-ahead log file size in Bytes (`transaction.walFileSizeBytes`).<br/><br/><strong>Labels:</strong> <Term>instanceId</Term>, <Term>serverVersion</Term><br/></dd>
  <dt><code>io_evitadb_system_request_fork_join_pool_statistics_active</code> (GAUGE)</dt>
  <dd><strong>Workers active</strong>: An estimate of the number of threads that are currently stealing or executing tasks</dd>
  <dt><code>io_evitadb_system_request_fork_join_pool_statistics_queued</code> (GAUGE)</dt>
  <dd><strong>Tasks queued</strong>: An estimate of the total number of tasks currently held in queues by worker threads</dd>
  <dt><code>io_evitadb_system_request_fork_join_pool_statistics_running</code> (GAUGE)</dt>
  <dd><strong>Workers running</strong>: An estimate of the number of worker threads that are not blocked waiting to join tasks or for other managed synchronization threads</dd>
  <dt><code>io_evitadb_system_request_fork_join_pool_statistics_steals</code> (COUNTER)</dt>
  <dd><strong>Tasks stolen</strong>: Estimate of the total number of tasks stolen from one thread's work queue by another. The reported value underestimates the actual total number of steals when the pool is not quiescent</dd>
  <dt><code>io_evitadb_system_scheduled_executor_statistics_active</code> (GAUGE)</dt>
  <dd><strong>Tasks active</strong>: The approximate number of threads that are actively executing tasks</dd>
  <dt><code>io_evitadb_system_scheduled_executor_statistics_completed</code> (COUNTER)</dt>
  <dd><strong>Tasks completed</strong>: The approximate total number of tasks that have completed execution</dd>
  <dt><code>io_evitadb_system_scheduled_executor_statistics_pool_core</code> (GAUGE)</dt>
  <dd><strong>Minimal worker count</strong>: The core number of threads for the pool</dd>
  <dt><code>io_evitadb_system_scheduled_executor_statistics_pool_max</code> (GAUGE)</dt>
  <dd><strong>Max worker count</strong>: The maximum allowed number of threads in the pool</dd>
  <dt><code>io_evitadb_system_scheduled_executor_statistics_pool_size</code> (GAUGE)</dt>
  <dd><strong>Current worker count</strong>: The current number of threads in the pool</dd>
  <dt><code>io_evitadb_system_scheduled_executor_statistics_queue_remaining</code> (GAUGE)</dt>
  <dd><strong>Queue remaining</strong>: The number of additional elements that this queue can ideally accept without blocking</dd>
  <dt><code>io_evitadb_system_scheduled_executor_statistics_queued</code> (GAUGE)</dt>
  <dd><strong>Tasks queued</strong>: The approximate number of queued tasks that are waiting to be executed</dd>
  <dt><code>io_evitadb_system_transaction_fork_join_pool_statistics_active</code> (GAUGE)</dt>
  <dd><strong>Workers active</strong>: An estimate of the number of threads that are currently stealing or executing tasks</dd>
  <dt><code>io_evitadb_system_transaction_fork_join_pool_statistics_queued</code> (GAUGE)</dt>
  <dd><strong>Tasks queued</strong>: An estimate of the total number of tasks currently held in queues by worker threads</dd>
  <dt><code>io_evitadb_system_transaction_fork_join_pool_statistics_running</code> (GAUGE)</dt>
  <dd><strong>Workers running</strong>: An estimate of the number of worker threads that are not blocked waiting to join tasks or for other managed synchronization threads</dd>
  <dt><code>io_evitadb_system_transaction_fork_join_pool_statistics_steals</code> (COUNTER)</dt>
  <dd><strong>Tasks stolen</strong>: Estimate of the total number of tasks stolen from one thread's work queue by another. The reported value underestimates the actual total number of steals when the pool is not quiescent</dd>
</dl>

#### Transaction

<dl>
  <dt><code>io.evitadb.transaction.WalStatistics.oldestWalEntryTimestampSeconds</code> (GAUGE)</dt>
  <dd><strong>Oldest WAL entry timestamp</strong>: The timestamp of the oldest WAL entry in the WAL files (either active or historical).</dd>
  <dt><code>io.evitadb.transaction.WalStatistics.oldestWalEntryTimestampSeconds</code> (GAUGE)</dt>
  <dd><strong>Oldest WAL entry timestamp</strong>: The timestamp of the oldest WAL entry in the WAL files (either active or historical).</dd>
  <dt><code>io_evitadb_transaction_catalog_goes_live_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Catalog transition to alive state duration</dd>
  <dt><code>io_evitadb_transaction_catalog_goes_live_total</code> (COUNTER)</dt>
  <dd>Catalog goes live invocation count</dd>
  <dt><code>io_evitadb_transaction_isolated_wal_file_closed_total</code> (COUNTER)</dt>
  <dd>Closed files for isolated WAL storage.</dd>
  <dt><code>io_evitadb_transaction_isolated_wal_file_opened_total</code> (COUNTER)</dt>
  <dd>Opened files for isolated WAL storage.</dd>
  <dt><code>io_evitadb_transaction_new_catalog_version_propagated_collapsed_transactions</code> (COUNTER)</dt>
  <dd><strong>Transactions propagated to live view.</strong>: The number of transactions that were propagated to the live view in a single transition.</dd>
  <dt><code>io_evitadb_transaction_new_catalog_version_propagated_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>New catalog version propagation duration in milliseconds</dd>
  <dt><code>io_evitadb_transaction_new_catalog_version_propagated_total</code> (COUNTER)</dt>
  <dd>Catalog versions propagated</dd>
  <dt><code>io_evitadb_transaction_off_heap_memory_allocation_change_allocated_memory_bytes</code> (GAUGE)</dt>
  <dd><strong>Allocated memory bytes</strong>: Amount of memory allocated for off-heap storage in Bytes.</dd>
  <dt><code>io_evitadb_transaction_off_heap_memory_allocation_change_used_memory_bytes</code> (GAUGE)</dt>
  <dd><strong>Used memory bytes</strong>: Amount of memory used for off-heap storage in Bytes.</dd>
  <dt><code>io_evitadb_transaction_transaction_accepted_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Conflict resolution duration in milliseconds<br/><br/><strong>Labels:</strong> <Term>resolution</Term><br/></dd>
  <dt><code>io_evitadb_transaction_transaction_accepted_total</code> (COUNTER)</dt>
  <dd>Transactions accepted<br/><br/><strong>Labels:</strong> <Term>resolution</Term><br/></dd>
  <dt><code>io_evitadb_transaction_transaction_appended_to_wal_appended_atomic_mutations</code> (COUNTER)</dt>
  <dd><strong>Atomic mutations appended.</strong>: The number of atomic mutations (schema, catalog schema or entity mutations) appended to the shared WAL.</dd>
  <dt><code>io_evitadb_transaction_transaction_appended_to_wal_appended_wal_bytes</code> (COUNTER)</dt>
  <dd><strong>Size of the written WAL in Bytes.</strong>: The size of the written WAL in Bytes.</dd>
  <dt><code>io_evitadb_transaction_transaction_appended_to_wal_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Appending transaction to shared WAL duration in milliseconds</dd>
  <dt><code>io_evitadb_transaction_transaction_appended_to_wal_total</code> (COUNTER)</dt>
  <dd>Transactions appended to WAL</dd>
  <dt><code>io_evitadb_transaction_transaction_finished_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Transaction lifespan duration in milliseconds<br/><br/><strong>Labels:</strong> <Term>resolution</Term><br/></dd>
  <dt><code>io_evitadb_transaction_transaction_finished_oldest_transaction_timestamp_seconds</code> (GAUGE)</dt>
  <dd><strong>Oldest transaction timestamp</strong>: The timestamp of the oldest non-finished (running) transaction in the catalog.<br/><br/><strong>Labels:</strong> <Term>resolution</Term><br/></dd>
  <dt><code>io_evitadb_transaction_transaction_finished_total</code> (COUNTER)</dt>
  <dd>Transactions finished<br/><br/><strong>Labels:</strong> <Term>resolution</Term><br/></dd>
  <dt><code>io_evitadb_transaction_transaction_incorporated_to_trunk_collapsed_transactions</code> (COUNTER)</dt>
  <dd><strong>Transactions incorporated into shared data structures.</strong>: N/A</dd>
  <dt><code>io_evitadb_transaction_transaction_incorporated_to_trunk_incorporation_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Incorporation duration in milliseconds</dd>
  <dt><code>io_evitadb_transaction_transaction_incorporated_to_trunk_processed_atomic_mutations</code> (COUNTER)</dt>
  <dd><strong>Atomic mutations processed.</strong>: N/A</dd>
  <dt><code>io_evitadb_transaction_transaction_incorporated_to_trunk_processed_local_mutations</code> (COUNTER)</dt>
  <dd><strong>Local mutations processed.</strong>: N/A</dd>
  <dt><code>io_evitadb_transaction_transaction_processed_lag_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Transaction lag</strong>: The time it took for the transaction to become visible to all new sessions. In other words, the time between when the transaction was committed and when the shared view was affected.</dd>
  <dt><code>io_evitadb_transaction_transaction_queued_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Transaction wait time in a queue.<br/><br/><strong>Labels:</strong> <Term>stage</Term><br/></dd>
  <dt><code>io_evitadb_transaction_transaction_started_total</code> (COUNTER)</dt>
  <dd>Transactions initiated</dd>
  <dt><code>io_evitadb_transaction_wal_cache_size_changed_locations_cached</code> (GAUGE)</dt>
  <dd><strong>Total cached locations in WAL file</strong>: The total number of cached locations (used for fast mutation lookups) in the shared WAL file.</dd>
  <dt><code>io_evitadb_transaction_wal_rotation_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>WAL rotation duration in milliseconds</dd>
  <dt><code>io_evitadb_transaction_wal_rotation_total</code> (COUNTER)</dt>
  <dd>WAL rotations</dd>
</dl>

