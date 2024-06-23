### Metrics

<UsedTerms>
  <h4>Labels used in metrics</h4>
  <dl>
    <dt>fileType</dt>
    <dd><strong>File type</strong>: N/A</dd>
    <dt>name</dt>
    <dd><strong>Logical file name</strong>: N/A</dd>
    <dt>buildType</dt>
    <dd><strong>Build type</strong>: N/A</dd>
    <dt>catalogName</dt>
    <dd><strong>Catalog</strong>: N/A</dd>
    <dt>instanceType</dt>
    <dd><strong>Instance type</strong>: N/A</dd>
    <dt>stage</dt>
    <dd><strong>Transaction stage</strong>: N/A</dd>
    <dt>fileType</dt>
    <dd><strong>File type</strong>: N/A</dd>
    <dt>name</dt>
    <dd><strong>Logical file name</strong>: N/A</dd>
    <dt>fileType</dt>
    <dd><strong>File type</strong>: N/A</dd>
    <dt>name</dt>
    <dd><strong>Logical file name</strong>: N/A</dd>
    <dt>catalogName</dt>
    <dd><strong>Catalog</strong>: N/A</dd>
    <dt>entityType</dt>
    <dd><strong>Collection</strong>: N/A</dd>
    <dt>httpMethod</dt>
    <dd><strong>HTTP method</strong>: N/A</dd>
    <dt>instanceType</dt>
    <dd><strong>Instance type</strong>: N/A</dd>
    <dt>operationId</dt>
    <dd><strong>Operation ID</strong>: N/A</dd>
    <dt>operationType</dt>
    <dd><strong>Operation type</strong>: N/A</dd>
    <dt>responseStatus</dt>
    <dd><strong>Response status</strong>: N/A</dd>
    <dt>initiator</dt>
    <dd><strong>Initiator of the call (client or server)</strong>: N/A</dd>
    <dt>procedureName</dt>
    <dd><strong>Name of the procedure that was called</strong>: N/A</dd>
    <dt>responseState</dt>
    <dd><strong>State of the response (OK, ERROR, CANCELED)</strong>: N/A</dd>
    <dt>serviceName</dt>
    <dd><strong>Name of the service that was called</strong>: N/A</dd>
    <dt>resolution</dt>
    <dd><strong>Transaction resolution</strong>: N/A</dd>
    <dt>taskName</dt>
    <dd><strong>N/A</strong>: N/A</dd>
    <dt>taskName</dt>
    <dd><strong>N/A</strong>: N/A</dd>
    <dt>buildType</dt>
    <dd><strong>Build type</strong>: N/A</dd>
    <dt>catalogName</dt>
    <dd><strong>Catalog</strong>: N/A</dd>
    <dt>instanceType</dt>
    <dd><strong>Instance type</strong>: N/A</dd>
    <dt>entityType</dt>
    <dd><strong>Entity type</strong>: N/A</dd>
    <dt>prefetched</dt>
    <dd><strong>Prefetched vs. non-prefetched query</strong>: N/A</dd>
    <dt>resolution</dt>
    <dd><strong>Transaction resolution</strong>: N/A</dd>
    <dt>entityType</dt>
    <dd><strong>Entity type</strong>: N/A</dd>
    <dt>fileType</dt>
    <dd><strong>File type</strong>: N/A</dd>
    <dt>name</dt>
    <dd><strong>Logical file name</strong>: N/A</dd>
    <dt>taskName</dt>
    <dd><strong>N/A</strong>: N/A</dd>
    <dt>entityType</dt>
    <dd><strong>Entity type</strong>: N/A</dd>
    <dt>catalogName</dt>
    <dd><strong>Catalog</strong>: N/A</dd>
    <dt>instanceType</dt>
    <dd><strong>Instance type</strong>: N/A</dd>
    <dt>operationName</dt>
    <dd><strong>GraphQL operation</strong>: N/A</dd>
    <dt>operationType</dt>
    <dd><strong>Operation type</strong>: N/A</dd>
    <dt>responseStatus</dt>
    <dd><strong>Response status</strong>: N/A</dd>
    <dt>fileType</dt>
    <dd><strong>File type</strong>: N/A</dd>
    <dt>name</dt>
    <dd><strong>Logical file name</strong>: N/A</dd>
    <dt>fileType</dt>
    <dd><strong>File type</strong>: N/A</dd>
    <dt>name</dt>
    <dd><strong>Logical file name</strong>: N/A</dd>
    <dt>recordType</dt>
    <dd><strong>Record type</strong>: N/A</dd>
    <dt>taskName</dt>
    <dd><strong>N/A</strong>: N/A</dd>
    <dt>fileType</dt>
    <dd><strong>File type</strong>: N/A</dd>
    <dt>name</dt>
    <dd><strong>Logical file name</strong>: N/A</dd>
  </dl>
</UsedTerms>

#### API / GraphQL / Instance / Schema

<dl>
  <dt><code>io_evitadb_external_api_graphql_instance_built_instance_build_duration</code> (HISTOGRAM)</dt>
  <dd><strong>Duration of build of a single API</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>instanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_instance_built_schema_build_duration</code> (HISTOGRAM)</dt>
  <dd><strong>Duration of GraphQL schema build of a single API</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>instanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_instance_built_schema_dsl_lines</code> (GAUGE)</dt>
  <dd><strong>Number of lines in built GraphQL schema DSL</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>instanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_instance_built_total</code> (COUNTER)</dt>
  <dd>GraphQL instance built total<br/><br/><strong>Labels:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>instanceType</Term><br/></dd>
</dl>

#### API / gRPC

<dl>
  <dt><code>io_evitadb_api_grpc_procedure_called_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>gRPC procedure called duration<br/><br/><strong>Labels:</strong> <Term>initiator</Term>, <Term>procedureName</Term>, <Term>responseState</Term>, <Term>serviceName</Term><br/></dd>
  <dt><code>io_evitadb_api_grpc_procedure_called_total</code> (COUNTER)</dt>
  <dd>gRPC procedure called total<br/><br/><strong>Labels:</strong> <Term>initiator</Term>, <Term>procedureName</Term>, <Term>responseState</Term>, <Term>serviceName</Term><br/></dd>
</dl>

#### Cache

<dl>
  <dt><code>io_evitadb_cache_anteroom_record_statistics_updated_records</code> (GAUGE)</dt>
  <dd><strong>Number of records waiting in anteroom</strong>: N/A</dd>
  <dt><code>io_evitadb_cache_anteroom_wasted_total</code> (COUNTER)</dt>
  <dd>Anteroom wasted total</dd>
</dl>

#### ExternalAPI / GraphQL / Request

<dl>
  <dt><code>io_evitadb_external_api_graphql_request_executed_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>GraphQL request execution duration<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>instanceType</Term>, <Term>operationName</Term>, <Term>operationType</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_execution_api_overhead_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Overall request execution API overhead duration in milliseconds</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>instanceType</Term>, <Term>operationName</Term>, <Term>operationType</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_input_deserialization_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Input deserialization duration in milliseconds</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>instanceType</Term>, <Term>operationName</Term>, <Term>operationType</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_internal_evitadb_input_reconstruction_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Duration of all internal evitaDB input (query, mutations, ...) reconstructions in milliseconds</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>instanceType</Term>, <Term>operationName</Term>, <Term>operationType</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_operation_execution_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Request operation execution duration in milliseconds</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>instanceType</Term>, <Term>operationName</Term>, <Term>operationType</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_parse_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Request parsing duration in milliseconds</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>instanceType</Term>, <Term>operationName</Term>, <Term>operationType</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_preparation_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Request execution preparation duration in milliseconds</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>instanceType</Term>, <Term>operationName</Term>, <Term>operationType</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_result_serialization_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Request result serialization duration in milliseconds</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>instanceType</Term>, <Term>operationName</Term>, <Term>operationType</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_root_fields_processed</code> (GAUGE)</dt>
  <dd><strong>Number of root fields (queries, mutations) processed within single GraphQL request</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>instanceType</Term>, <Term>operationName</Term>, <Term>operationType</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_total</code> (COUNTER)</dt>
  <dd>GraphQL request executed total<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>instanceType</Term>, <Term>operationName</Term>, <Term>operationType</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_graphql_request_executed_validation_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Request validation duration in milliseconds</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>instanceType</Term>, <Term>operationName</Term>, <Term>operationType</Term>, <Term>responseStatus</Term><br/></dd>
</dl>

#### ExternalAPI / REST / Instance / Schema

<dl>
  <dt><code>io_evitadb_external_api_rest_instance_built_instance_build_duration</code> (HISTOGRAM)</dt>
  <dd><strong>Duration of build of a single REST API</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>instanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_instance_built_registered_endpoints</code> (GAUGE)</dt>
  <dd><strong>Number of registered endpoints in built OpenAPI schema</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>instanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_instance_built_schema_build_duration</code> (HISTOGRAM)</dt>
  <dd><strong>Duration of OpenAPI schema build of a single API</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>instanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_instance_built_schema_dsl_lines</code> (GAUGE)</dt>
  <dd><strong>Number of lines in built OpenAPI schema DSL</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>instanceType</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_instance_built_total</code> (COUNTER)</dt>
  <dd>REST API instance built total<br/><br/><strong>Labels:</strong> <Term>buildType</Term>, <Term>catalogName</Term>, <Term>instanceType</Term><br/></dd>
</dl>

#### ExternalAPI / REST / Request

<dl>
  <dt><code>io_evitadb_external_api_rest_request_executed_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>REST request execution duration<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>instanceType</Term>, <Term>operationId</Term>, <Term>operationType</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_request_executed_execution_api_overhead_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Overall request execution API overhead duration in milliseconds</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>instanceType</Term>, <Term>operationId</Term>, <Term>operationType</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_request_executed_input_deserialization_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Input deserialization duration in milliseconds</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>instanceType</Term>, <Term>operationId</Term>, <Term>operationType</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_request_executed_internal_evitadb_input_reconstruction_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Duration of all internal evitaDB input (query, mutations, ...) reconstructions in milliseconds</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>instanceType</Term>, <Term>operationId</Term>, <Term>operationType</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_request_executed_operation_execution_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Request operation execution duration in milliseconds</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>instanceType</Term>, <Term>operationId</Term>, <Term>operationType</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_request_executed_result_serialization_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Request result serialization duration in milliseconds</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>instanceType</Term>, <Term>operationId</Term>, <Term>operationType</Term>, <Term>responseStatus</Term><br/></dd>
  <dt><code>io_evitadb_external_api_rest_request_executed_total</code> (COUNTER)</dt>
  <dd>REST request executed total<br/><br/><strong>Labels:</strong> <Term>catalogName</Term>, <Term>entityType</Term>, <Term>httpMethod</Term>, <Term>instanceType</Term>, <Term>operationId</Term>, <Term>operationType</Term>, <Term>responseStatus</Term><br/></dd>
</dl>

#### Query

<dl>
  <dt><code>io_evitadb_query_entity_enrich_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Entity enrichment duration in milliseconds<br/><br/><strong>Labels:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_enrich_records</code> (COUNTER)</dt>
  <dd><strong>Records enriched total</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_enrich_size_bytes</code> (HISTOGRAM)</dt>
  <dd><strong>Enrichment size in bytes</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_enrich_total</code> (COUNTER)</dt>
  <dd>Entity enriched<br/><br/><strong>Labels:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_fetch_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Entity fetch duration in milliseconds<br/><br/><strong>Labels:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_fetch_records</code> (COUNTER)</dt>
  <dd><strong>Records fetched total</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_fetch_size_bytes</code> (HISTOGRAM)</dt>
  <dd><strong>Fetched size in bytes</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_entity_fetch_total</code> (COUNTER)</dt>
  <dd>Entity fetched<br/><br/><strong>Labels:</strong> <Term>entityType</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Query duration in milliseconds<br/><br/><strong>Labels:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_estimated</code> (HISTOGRAM)</dt>
  <dd><strong>Estimated complexity info</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_execution_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Query execution duration in milliseconds</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_fetched</code> (HISTOGRAM)</dt>
  <dd><strong>Records fetched total</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_fetched_size_bytes</code> (HISTOGRAM)</dt>
  <dd><strong>Fetched size in bytes</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_found</code> (HISTOGRAM)</dt>
  <dd><strong>Records found total</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_plan_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd><strong>Query planning duration in milliseconds</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_real</code> (HISTOGRAM)</dt>
  <dd><strong>Filter complexity</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_returned</code> (HISTOGRAM)</dt>
  <dd><strong>Records returned total</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_scanned</code> (HISTOGRAM)</dt>
  <dd><strong>Records scanned total</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
  <dt><code>io_evitadb_query_finished_total</code> (COUNTER)</dt>
  <dd>Query finished<br/><br/><strong>Labels:</strong> <Term>entityType</Term>, <Term>prefetched</Term><br/></dd>
</dl>

#### Session

<dl>
  <dt><code>io_evitadb_session_closed_active_sessions</code> (GAUGE)</dt>
  <dd><strong>Number of still active sessions</strong>: N/A</dd>
  <dt><code>io_evitadb_session_closed_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Session lifespan duration in milliseconds</dd>
  <dt><code>io_evitadb_session_closed_mutations</code> (HISTOGRAM)</dt>
  <dd><strong>Number of mutation calls performed in session</strong>: N/A</dd>
  <dt><code>io_evitadb_session_closed_oldest_session_timestamp_seconds</code> (GAUGE)</dt>
  <dd><strong>Oldest session timestamp</strong>: N/A</dd>
  <dt><code>io_evitadb_session_closed_queries</code> (HISTOGRAM)</dt>
  <dd><strong>Number of queries performed in session</strong>: N/A</dd>
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
  <dd><strong>Entity collection count</strong>: N/A</dd>
  <dt><code>io_evitadb_storage_catalog_statistics_occupied_disk_space_bytes</code> (GAUGE)</dt>
  <dd><strong>Total occupied disk space in Bytes</strong>: N/A</dd>
  <dt><code>io_evitadb_storage_catalog_statistics_oldest_catalog_version_timestamp_seconds</code> (GAUGE)</dt>
  <dd><strong>Timestamp of the oldest catalog version available in seconds</strong>: N/A</dd>
  <dt><code>io_evitadb_storage_data_file_compact_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Duration of OffsetIndex compaction.<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_data_file_compact_total</code> (COUNTER)</dt>
  <dd>OffsetIndex compaction.<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_evita_dbcomposition_changed_catalogs</code> (GAUGE)</dt>
  <dd><strong>Catalog count</strong>: N/A</dd>
  <dt><code>io_evitadb_storage_evita_dbcomposition_changed_corrupted_catalogs</code> (GAUGE)</dt>
  <dd><strong>Corrupted catalog count</strong>: N/A</dd>
  <dt><code>io_evitadb_storage_observable_output_change_occupied_memory_bytes</code> (GAUGE)</dt>
  <dd><strong>Memory occupied by opened output buffers in Bytes</strong>: N/A</dd>
  <dt><code>io_evitadb_storage_observable_output_change_opened_buffers</code> (GAUGE)</dt>
  <dd><strong>Number of opened output buffers</strong>: N/A</dd>
  <dt><code>io_evitadb_storage_observable_output_change_total</code> (COUNTER)</dt>
  <dd>ObservableOutput buffer count changes.</dd>
  <dt><code>io_evitadb_storage_offset_index_flush_active_disk_size_bytes</code> (GAUGE)</dt>
  <dd><strong>Active part of disk size in Bytes</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_active_records</code> (GAUGE)</dt>
  <dd><strong>Number of active records</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_disk_size_bytes</code> (GAUGE)</dt>
  <dd><strong>Disk size in Bytes</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Duration of OffsetIndex flush to disk.<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_estimated_memory_size_bytes</code> (GAUGE)</dt>
  <dd><strong>Estimated memory size in Bytes</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_max_record_size</code> (GAUGE)</dt>
  <dd><strong>Biggest record Bytes</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_oldest_record_timestamp_seconds</code> (GAUGE)</dt>
  <dd><strong>Oldest record kept in memory timestamp in seconds</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_flush_total</code> (COUNTER)</dt>
  <dd>OffsetIndex flushes to disk.<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_history_kept_oldest_record_timestamp_seconds</code> (GAUGE)</dt>
  <dd><strong>Oldest record kept in memory timestamp in seconds</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_non_flushed_record_size_bytes</code> (GAUGE)</dt>
  <dd><strong>Size of records pending flush in Bytes</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_non_flushed_records</code> (GAUGE)</dt>
  <dd><strong>Number of records pending flush</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term><br/></dd>
  <dt><code>io_evitadb_storage_offset_index_record_type_count_changed_records</code> (GAUGE)</dt>
  <dd><strong>Number of records</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>fileType</Term>, <Term>name</Term>, <Term>recordType</Term><br/></dd>
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
  <dd><strong>N/A</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>taskName</Term><br/></dd>
  <dt><code>io_evitadb_system_evita_started_cache_anteroom_record_limit</code> (GAUGE)</dt>
  <dd><strong>Maximal number of records in cache anteroom</strong>: N/A</dd>
  <dt><code>io_evitadb_system_evita_started_cache_reevaluation_seconds</code> (GAUGE)</dt>
  <dd><strong>Cache reevaluation interval in seconds</strong>: N/A</dd>
  <dt><code>io_evitadb_system_evita_started_cache_size_in_bytes</code> (GAUGE)</dt>
  <dd><strong>Maximal size of cache in Bytes</strong>: N/A</dd>
  <dt><code>io_evitadb_system_evita_started_compaction_file_size_threshold_bytes</code> (GAUGE)</dt>
  <dd><strong>Minimal file size threshold to start compaction in Bytes</strong>: N/A</dd>
  <dt><code>io_evitadb_system_evita_started_compaction_minimal_active_record_share_percent</code> (GAUGE)</dt>
  <dd><strong>Minimal share of active records in the file to start compaction in %</strong>: N/A</dd>
  <dt><code>io_evitadb_system_evita_started_query_timeout_seconds</code> (GAUGE)</dt>
  <dd><strong>Read only request timeout in seconds</strong>: N/A</dd>
  <dt><code>io_evitadb_system_evita_started_read_only_handles_limit</code> (GAUGE)</dt>
  <dd><strong>Maximal count of opened read-only handles</strong>: N/A</dd>
  <dt><code>io_evitadb_system_evita_started_request_max_threads</code> (GAUGE)</dt>
  <dd><strong>Maximal number of threads read only request handling</strong>: N/A</dd>
  <dt><code>io_evitadb_system_evita_started_request_max_threads_queue_size</code> (GAUGE)</dt>
  <dd><strong>Maximal queue size for read only request handling</strong>: N/A</dd>
  <dt><code>io_evitadb_system_evita_started_service_max_threads</code> (GAUGE)</dt>
  <dd><strong>Maximal number of threads for service tasks</strong>: N/A</dd>
  <dt><code>io_evitadb_system_evita_started_service_max_threads_queue_size</code> (GAUGE)</dt>
  <dd><strong>Maximal queue size for service tasks</strong>: N/A</dd>
  <dt><code>io_evitadb_system_evita_started_session_max_inactive_age_seconds</code> (GAUGE)</dt>
  <dd><strong>Maximal session inactivity age in seconds</strong>: N/A</dd>
  <dt><code>io_evitadb_system_evita_started_total</code> (COUNTER)</dt>
  <dd>Evita started total</dd>
  <dt><code>io_evitadb_system_evita_started_transaction_max_threads</code> (GAUGE)</dt>
  <dd><strong>Maximal number of threads for read/write requests</strong>: N/A</dd>
  <dt><code>io_evitadb_system_evita_started_transaction_max_threads_queue_size</code> (GAUGE)</dt>
  <dd><strong>Maximal queue size for read/write requests</strong>: N/A</dd>
  <dt><code>io_evitadb_system_evita_started_transaction_memory_buffer_limit_size_bytes</code> (GAUGE)</dt>
  <dd><strong>Size of off-heap memory buffer for transactions in Bytes</strong>: N/A</dd>
  <dt><code>io_evitadb_system_evita_started_transaction_memory_regions</code> (GAUGE)</dt>
  <dd><strong>Number of off-heap memory regions for transactions</strong>: N/A</dd>
  <dt><code>io_evitadb_system_evita_started_transaction_timeout_seconds</code> (GAUGE)</dt>
  <dd><strong>Read/write request timeout in seconds</strong>: N/A</dd>
  <dt><code>io_evitadb_system_evita_started_wal_max_file_count_kept</code> (GAUGE)</dt>
  <dd><strong>Maximal write-ahead log file count to keep</strong>: N/A</dd>
  <dt><code>io_evitadb_system_evita_started_wal_max_file_size_bytes</code> (GAUGE)</dt>
  <dd><strong>Maximal write-ahead log file size in Bytes</strong>: N/A</dd>
</dl>

#### Transaction

<dl>
  <dt><code>io.evitadb.transaction.WalStatistics.oldestWalEntryTimestampSeconds</code> (GAUGE)</dt>
  <dd><strong>Oldest WAL entry timestamp</strong>: N/A</dd>
  <dt><code>io.evitadb.transaction.WalStatistics.oldestWalEntryTimestampSeconds</code> (GAUGE)</dt>
  <dd><strong>Oldest WAL entry timestamp</strong>: N/A</dd>
  <dt><code>io_evitadb_transaction_catalog_goes_live_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Catalog transition to live state duration</dd>
  <dt><code>io_evitadb_transaction_catalog_goes_live_total</code> (COUNTER)</dt>
  <dd>Catalog goes live invocation count</dd>
  <dt><code>io_evitadb_transaction_isolated_wal_file_closed_total</code> (COUNTER)</dt>
  <dd>Closed files for isolated WAL storage.</dd>
  <dt><code>io_evitadb_transaction_isolated_wal_file_opened_total</code> (COUNTER)</dt>
  <dd>Opened files for isolated WAL storage.</dd>
  <dt><code>io_evitadb_transaction_new_catalog_version_propagated_collapsed_transactions</code> (COUNTER)</dt>
  <dd><strong>Transactions propagated to live view.</strong>: N/A</dd>
  <dt><code>io_evitadb_transaction_new_catalog_version_propagated_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>New catalog version propagation duration in milliseconds</dd>
  <dt><code>io_evitadb_transaction_new_catalog_version_propagated_total</code> (COUNTER)</dt>
  <dd>Catalog versions propagated</dd>
  <dt><code>io_evitadb_transaction_off_heap_memory_allocation_change_allocated_memory_bytes</code> (GAUGE)</dt>
  <dd><strong>Allocated memory bytes</strong>: N/A</dd>
  <dt><code>io_evitadb_transaction_off_heap_memory_allocation_change_used_memory_bytes</code> (GAUGE)</dt>
  <dd><strong>Used memory bytes</strong>: N/A</dd>
  <dt><code>io_evitadb_transaction_transaction_accepted_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Conflict resolution duration in milliseconds<br/><br/><strong>Labels:</strong> <Term>resolution</Term><br/></dd>
  <dt><code>io_evitadb_transaction_transaction_accepted_total</code> (COUNTER)</dt>
  <dd>Transactions accepted<br/><br/><strong>Labels:</strong> <Term>resolution</Term><br/></dd>
  <dt><code>io_evitadb_transaction_transaction_appended_to_wal_appended_atomic_mutations</code> (COUNTER)</dt>
  <dd><strong>Atomic mutations appended.</strong>: N/A</dd>
  <dt><code>io_evitadb_transaction_transaction_appended_to_wal_appended_wal_bytes</code> (COUNTER)</dt>
  <dd><strong>Size of the written WAL in Bytes.</strong>: N/A</dd>
  <dt><code>io_evitadb_transaction_transaction_appended_to_wal_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Appending transaction to shared WAL duration in milliseconds</dd>
  <dt><code>io_evitadb_transaction_transaction_appended_to_wal_total</code> (COUNTER)</dt>
  <dd>Transactions appended to WAL</dd>
  <dt><code>io_evitadb_transaction_transaction_finished_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Transaction lifespan duration in milliseconds<br/><br/><strong>Labels:</strong> <Term>resolution</Term><br/></dd>
  <dt><code>io_evitadb_transaction_transaction_finished_oldest_transaction_timestamp_seconds</code> (GAUGE)</dt>
  <dd><strong>Oldest transaction timestamp</strong>: N/A<br/><br/><strong>Labels:</strong> <Term>resolution</Term><br/></dd>
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
  <dd><strong>Transaction lag between being committed and finally visible to all</strong>: N/A</dd>
  <dt><code>io_evitadb_transaction_transaction_queued_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>Transaction waiting time in queue.<br/><br/><strong>Labels:</strong> <Term>stage</Term><br/></dd>
  <dt><code>io_evitadb_transaction_transaction_started_total</code> (COUNTER)</dt>
  <dd>Transactions initiated</dd>
  <dt><code>io_evitadb_transaction_wal_cache_size_changed_locations_cached</code> (GAUGE)</dt>
  <dd><strong>Total cached locations in WAL file</strong>: N/A</dd>
  <dt><code>io_evitadb_transaction_wal_rotation_duration_milliseconds</code> (HISTOGRAM)</dt>
  <dd>WAL rotation duration in milliseconds</dd>
  <dt><code>io_evitadb_transaction_wal_rotation_total</code> (COUNTER)</dt>
  <dd>WAL rotations</dd>
</dl>

