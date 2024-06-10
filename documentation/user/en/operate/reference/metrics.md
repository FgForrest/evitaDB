### Metrics

<UsedTerms>
  <h4>Labels used in metrics</h4>
  <dl>
    <dt>fileType</dt>
    <dd>**File type**: N/A</dd>
    <dt>name</dt>
    <dd>**Logical file name**: N/A</dd>
    <dt>fileType</dt>
    <dd>**File type**: N/A</dd>
    <dt>name</dt>
    <dd>**Logical file name**: N/A</dd>
    <dt>fileType</dt>
    <dd>**File type**: N/A</dd>
    <dt>name</dt>
    <dd>**Logical file name**: N/A</dd>
    <dt>entityType</dt>
    <dd>**Entity type**: N/A</dd>
    <dt>entityType</dt>
    <dd>**Entity type**: N/A</dd>
    <dt>resolution</dt>
    <dd>**Transaction resolution**: N/A</dd>
    <dt>fileType</dt>
    <dd>**File type**: N/A</dd>
    <dt>name</dt>
    <dd>**Logical file name**: N/A</dd>
    <dt>stage</dt>
    <dd>**Transaction stage**: N/A</dd>
    <dt>resolution</dt>
    <dd>**Transaction resolution**: N/A</dd>
    <dt>fileType</dt>
    <dd>**File type**: N/A</dd>
    <dt>name</dt>
    <dd>**Logical file name**: N/A</dd>
    <dt>recordType</dt>
    <dd>**Record type**: N/A</dd>
    <dt>fileType</dt>
    <dd>**File type**: N/A</dd>
    <dt>name</dt>
    <dd>**Logical file name**: N/A</dd>
    <dt>fileType</dt>
    <dd>**File type**: N/A</dd>
    <dt>name</dt>
    <dd>**Logical file name**: N/A</dd>
    <dt>taskName</dt>
    <dd>**N/A**: N/A</dd>
    <dt>initiator</dt>
    <dd>**Initiator of the call (client or server)**: N/A</dd>
    <dt>procedureName</dt>
    <dd>**Name of the procedure that was called**: N/A</dd>
    <dt>responseState</dt>
    <dd>**State of the response (OK, ERROR, CANCELED)**: N/A</dd>
    <dt>serviceName</dt>
    <dd>**Name of the service that was called**: N/A</dd>
    <dt>taskName</dt>
    <dd>**N/A**: N/A</dd>
    <dt>entityType</dt>
    <dd>**Entity type**: N/A</dd>
    <dt>prefetched</dt>
    <dd>**Prefetched vs. non-prefetched query**: N/A</dd>
  </dl>
</UsedTerms>

#### API / gRPC

<dl>
  <dt>**io_evitadb_api_grpc_procedure_called_duration_milliseconds** (HISTOGRAM)</dt>
  <dd>gRPC procedure called duration

    **Labels:** <Term>initiator</Term>, <Term>procedureName</Term>, <Term>responseState</Term>, <Term>serviceName</Term>
</dd>
  <dt>**io_evitadb_api_grpc_procedure_called_total** (COUNTER)</dt>
  <dd>gRPC procedure called total

    **Labels:** <Term>initiator</Term>, <Term>procedureName</Term>, <Term>responseState</Term>, <Term>serviceName</Term>
</dd>
</dl>

#### Cache

<dl>
  <dt>**io_evitadb_cache_anteroom_record_statistics_updated_records** (GAUGE)</dt>
  <dd>**Number of records waiting in anteroom**: N/A</dd>
  <dt>**io_evitadb_cache_anteroom_wasted_total** (COUNTER)</dt>
  <dd>Anteroom wasted total</dd>
</dl>

#### Query

<dl>
  <dt>**io_evitadb_query_entity_enrich_duration_milliseconds** (HISTOGRAM)</dt>
  <dd>Entity enrichment duration in milliseconds

    **Labels:** <Term>entityType</Term>
</dd>
  <dt>**io_evitadb_query_entity_enrich_records** (COUNTER)</dt>
  <dd>**Records enriched total**: N/A

    **Labels:** <Term>entityType</Term>
</dd>
  <dt>**io_evitadb_query_entity_enrich_size_bytes** (HISTOGRAM)</dt>
  <dd>**Enrichment size in bytes**: N/A

    **Labels:** <Term>entityType</Term>
</dd>
  <dt>**io_evitadb_query_entity_enrich_total** (COUNTER)</dt>
  <dd>Entity enriched

    **Labels:** <Term>entityType</Term>
</dd>
  <dt>**io_evitadb_query_entity_fetch_duration_milliseconds** (HISTOGRAM)</dt>
  <dd>Entity fetch duration in milliseconds

    **Labels:** <Term>entityType</Term>
</dd>
  <dt>**io_evitadb_query_entity_fetch_records** (COUNTER)</dt>
  <dd>**Records fetched total**: N/A

    **Labels:** <Term>entityType</Term>
</dd>
  <dt>**io_evitadb_query_entity_fetch_size_bytes** (HISTOGRAM)</dt>
  <dd>**Fetched size in bytes**: N/A

    **Labels:** <Term>entityType</Term>
</dd>
  <dt>**io_evitadb_query_entity_fetch_total** (COUNTER)</dt>
  <dd>Entity fetched

    **Labels:** <Term>entityType</Term>
</dd>
  <dt>**io_evitadb_query_finished_duration_milliseconds** (HISTOGRAM)</dt>
  <dd>Query duration in milliseconds

    **Labels:** <Term>entityType</Term>, <Term>prefetched</Term>
</dd>
  <dt>**io_evitadb_query_finished_estimated** (HISTOGRAM)</dt>
  <dd>**Estimated complexity info**: N/A

    **Labels:** <Term>entityType</Term>, <Term>prefetched</Term>
</dd>
  <dt>**io_evitadb_query_finished_execution_duration_milliseconds** (HISTOGRAM)</dt>
  <dd>**Query execution duration in milliseconds**: N/A

    **Labels:** <Term>entityType</Term>, <Term>prefetched</Term>
</dd>
  <dt>**io_evitadb_query_finished_fetched** (HISTOGRAM)</dt>
  <dd>**Records fetched total**: N/A

    **Labels:** <Term>entityType</Term>, <Term>prefetched</Term>
</dd>
  <dt>**io_evitadb_query_finished_fetched_size_bytes** (HISTOGRAM)</dt>
  <dd>**Fetched size in bytes**: N/A

    **Labels:** <Term>entityType</Term>, <Term>prefetched</Term>
</dd>
  <dt>**io_evitadb_query_finished_found** (HISTOGRAM)</dt>
  <dd>**Records found total**: N/A

    **Labels:** <Term>entityType</Term>, <Term>prefetched</Term>
</dd>
  <dt>**io_evitadb_query_finished_plan_duration_milliseconds** (HISTOGRAM)</dt>
  <dd>**Query planning duration in milliseconds**: N/A

    **Labels:** <Term>entityType</Term>, <Term>prefetched</Term>
</dd>
  <dt>**io_evitadb_query_finished_real** (HISTOGRAM)</dt>
  <dd>**Filter complexity**: N/A

    **Labels:** <Term>entityType</Term>, <Term>prefetched</Term>
</dd>
  <dt>**io_evitadb_query_finished_returned** (HISTOGRAM)</dt>
  <dd>**Records returned total**: N/A

    **Labels:** <Term>entityType</Term>, <Term>prefetched</Term>
</dd>
  <dt>**io_evitadb_query_finished_scanned** (HISTOGRAM)</dt>
  <dd>**Records scanned total**: N/A

    **Labels:** <Term>entityType</Term>, <Term>prefetched</Term>
</dd>
  <dt>**io_evitadb_query_finished_total** (COUNTER)</dt>
  <dd>Query finished

    **Labels:** <Term>entityType</Term>, <Term>prefetched</Term>
</dd>
</dl>

#### Session

<dl>
  <dt>**io_evitadb_session_closed_active_sessions** (GAUGE)</dt>
  <dd>**Number of still active sessions**: N/A</dd>
  <dt>**io_evitadb_session_closed_duration_milliseconds** (HISTOGRAM)</dt>
  <dd>Session lifespan duration in milliseconds</dd>
  <dt>**io_evitadb_session_closed_mutations** (HISTOGRAM)</dt>
  <dd>**Number of mutation calls performed in session**: N/A</dd>
  <dt>**io_evitadb_session_closed_oldest_session_timestamp_seconds** (GAUGE)</dt>
  <dd>**Oldest session timestamp**: N/A</dd>
  <dt>**io_evitadb_session_closed_queries** (HISTOGRAM)</dt>
  <dd>**Number of queries performed in session**: N/A</dd>
  <dt>**io_evitadb_session_closed_total** (COUNTER)</dt>
  <dd>Sessions closed</dd>
  <dt>**io_evitadb_session_killed_total** (COUNTER)</dt>
  <dd>Sessions killed</dd>
  <dt>**io_evitadb_session_opened_total** (COUNTER)</dt>
  <dd>Sessions opened</dd>
</dl>

#### Storage

<dl>
  <dt>**io_evitadb_storage_catalog_statistics_entity_collections** (GAUGE)</dt>
  <dd>**Entity collection count**: N/A</dd>
  <dt>**io_evitadb_storage_catalog_statistics_occupied_disk_space_bytes** (GAUGE)</dt>
  <dd>**Total occupied disk space in Bytes**: N/A</dd>
  <dt>**io_evitadb_storage_catalog_statistics_oldest_catalog_version_timestamp_seconds** (GAUGE)</dt>
  <dd>**Timestamp of the oldest catalog version available in seconds**: N/A</dd>
  <dt>**io_evitadb_storage_data_file_compact_duration_milliseconds** (HISTOGRAM)</dt>
  <dd>Duration of OffsetIndex compaction.

    **Labels:** <Term>fileType</Term>, <Term>name</Term>
</dd>
  <dt>**io_evitadb_storage_data_file_compact_total** (COUNTER)</dt>
  <dd>OffsetIndex compaction.

    **Labels:** <Term>fileType</Term>, <Term>name</Term>
</dd>
  <dt>**io_evitadb_storage_evita_dbcomposition_changed_catalogs** (GAUGE)</dt>
  <dd>**Catalog count**: N/A</dd>
  <dt>**io_evitadb_storage_evita_dbcomposition_changed_corrupted_catalogs** (GAUGE)</dt>
  <dd>**Corrupted catalog count**: N/A</dd>
  <dt>**io_evitadb_storage_observable_output_change_occupied_memory_bytes** (GAUGE)</dt>
  <dd>**Memory occupied by opened output buffers in Bytes**: N/A</dd>
  <dt>**io_evitadb_storage_observable_output_change_opened_buffers** (GAUGE)</dt>
  <dd>**Number of opened output buffers**: N/A</dd>
  <dt>**io_evitadb_storage_observable_output_change_total** (COUNTER)</dt>
  <dd>ObservableOutput buffer count changes.</dd>
  <dt>**io_evitadb_storage_offset_index_flush_active_disk_size_bytes** (GAUGE)</dt>
  <dd>**Active part of disk size in Bytes**: N/A

    **Labels:** <Term>fileType</Term>, <Term>name</Term>
</dd>
  <dt>**io_evitadb_storage_offset_index_flush_active_records** (GAUGE)</dt>
  <dd>**Number of active records**: N/A

    **Labels:** <Term>fileType</Term>, <Term>name</Term>
</dd>
  <dt>**io_evitadb_storage_offset_index_flush_disk_size_bytes** (GAUGE)</dt>
  <dd>**Disk size in Bytes**: N/A

    **Labels:** <Term>fileType</Term>, <Term>name</Term>
</dd>
  <dt>**io_evitadb_storage_offset_index_flush_duration_milliseconds** (HISTOGRAM)</dt>
  <dd>Duration of OffsetIndex flush to disk.

    **Labels:** <Term>fileType</Term>, <Term>name</Term>
</dd>
  <dt>**io_evitadb_storage_offset_index_flush_estimated_memory_size_bytes** (GAUGE)</dt>
  <dd>**Estimated memory size in Bytes**: N/A

    **Labels:** <Term>fileType</Term>, <Term>name</Term>
</dd>
  <dt>**io_evitadb_storage_offset_index_flush_max_record_size** (GAUGE)</dt>
  <dd>**Biggest record Bytes**: N/A

    **Labels:** <Term>fileType</Term>, <Term>name</Term>
</dd>
  <dt>**io_evitadb_storage_offset_index_flush_oldest_record_timestamp_seconds** (GAUGE)</dt>
  <dd>**Oldest record kept in memory timestamp in seconds**: N/A

    **Labels:** <Term>fileType</Term>, <Term>name</Term>
</dd>
  <dt>**io_evitadb_storage_offset_index_flush_total** (COUNTER)</dt>
  <dd>OffsetIndex flushes to disk.

    **Labels:** <Term>fileType</Term>, <Term>name</Term>
</dd>
  <dt>**io_evitadb_storage_offset_index_history_kept_oldest_record_timestamp_seconds** (GAUGE)</dt>
  <dd>**Oldest record kept in memory timestamp in seconds**: N/A

    **Labels:** <Term>fileType</Term>, <Term>name</Term>
</dd>
  <dt>**io_evitadb_storage_offset_index_non_flushed_record_size_bytes** (GAUGE)</dt>
  <dd>**Size of records pending flush in Bytes**: N/A

    **Labels:** <Term>fileType</Term>, <Term>name</Term>
</dd>
  <dt>**io_evitadb_storage_offset_index_non_flushed_records** (GAUGE)</dt>
  <dd>**Number of records pending flush**: N/A

    **Labels:** <Term>fileType</Term>, <Term>name</Term>
</dd>
  <dt>**io_evitadb_storage_offset_index_record_type_count_changed_records** (GAUGE)</dt>
  <dd>**Number of records**: N/A

    **Labels:** <Term>fileType</Term>, <Term>name</Term>, <Term>recordType</Term>
</dd>
  <dt>**io_evitadb_storage_read_only_handle_closed_total** (COUNTER)</dt>
  <dd>Closed file read handles.

    **Labels:** <Term>fileType</Term>, <Term>name</Term>
</dd>
  <dt>**io_evitadb_storage_read_only_handle_opened_total** (COUNTER)</dt>
  <dd>Opened file read handles.

    **Labels:** <Term>fileType</Term>, <Term>name</Term>
</dd>
</dl>

#### System

<dl>
  <dt>**io_evitadb_system_background_task_finished_total** (COUNTER)</dt>
  <dd>Background tasks finished

    **Labels:** <Term>taskName</Term>
</dd>
  <dt>**io_evitadb_system_background_task_started_total** (COUNTER)</dt>
  <dd>Background tasks started

    **Labels:** <Term>taskName</Term>
</dd>
  <dt>**io_evitadb_system_evita_started_cache_anteroom_record_limit** (GAUGE)</dt>
  <dd>**Maximal number of records in cache anteroom**: N/A</dd>
  <dt>**io_evitadb_system_evita_started_cache_reevaluation_seconds** (GAUGE)</dt>
  <dd>**Cache reevaluation interval in seconds**: N/A</dd>
  <dt>**io_evitadb_system_evita_started_cache_size_in_bytes** (GAUGE)</dt>
  <dd>**Maximal size of cache in Bytes**: N/A</dd>
  <dt>**io_evitadb_system_evita_started_compaction_file_size_threshold_bytes** (GAUGE)</dt>
  <dd>**Minimal file size threshold to start compaction in Bytes**: N/A</dd>
  <dt>**io_evitadb_system_evita_started_compaction_minimal_active_record_share_percent** (GAUGE)</dt>
  <dd>**Minimal share of active records in the file to start compaction in %**: N/A</dd>
  <dt>**io_evitadb_system_evita_started_max_threads** (GAUGE)</dt>
  <dd>**Maximal number of background threads**: N/A</dd>
  <dt>**io_evitadb_system_evita_started_max_threads_queue_size** (GAUGE)</dt>
  <dd>**Maximal queue size for background threads**: N/A</dd>
  <dt>**io_evitadb_system_evita_started_read_only_handles_limit** (GAUGE)</dt>
  <dd>**Maximal count of opened read-only handles**: N/A</dd>
  <dt>**io_evitadb_system_evita_started_session_max_inactive_age_seconds** (GAUGE)</dt>
  <dd>**Maximal session inactivity age in seconds**: N/A</dd>
  <dt>**io_evitadb_system_evita_started_short_tasks_timeout_seconds** (GAUGE)</dt>
  <dd>**Short running tasks timeout in seconds**: N/A</dd>
  <dt>**io_evitadb_system_evita_started_total** (COUNTER)</dt>
  <dd>Evita started total</dd>
  <dt>**io_evitadb_system_evita_started_transaction_max_queue_size** (GAUGE)</dt>
  <dd>**Maximal count of commited transactions in queue**: N/A</dd>
  <dt>**io_evitadb_system_evita_started_transaction_memory_buffer_limit_size_bytes** (GAUGE)</dt>
  <dd>**Size of off-heap memory buffer for transactions in Bytes**: N/A</dd>
  <dt>**io_evitadb_system_evita_started_transaction_memory_regions** (GAUGE)</dt>
  <dd>**Number of off-heap memory regions for transactions**: N/A</dd>
  <dt>**io_evitadb_system_evita_started_wal_max_file_count_kept** (GAUGE)</dt>
  <dd>**Maximal write-ahead log file count to keep**: N/A</dd>
  <dt>**io_evitadb_system_evita_started_wal_max_file_size_bytes** (GAUGE)</dt>
  <dd>**Maximal write-ahead log file size in Bytes**: N/A</dd>
</dl>

#### Transaction

<dl>
  <dt>**io.evitadb.transaction.WalStatistics.oldestWalEntryTimestampSeconds** (GAUGE)</dt>
  <dd>**Oldest WAL entry timestamp**: N/A</dd>
  <dt>**io.evitadb.transaction.WalStatistics.oldestWalEntryTimestampSeconds** (GAUGE)</dt>
  <dd>**Oldest WAL entry timestamp**: N/A</dd>
  <dt>**io_evitadb_transaction_catalog_goes_live_duration_milliseconds** (HISTOGRAM)</dt>
  <dd>Catalog transition to live state duration</dd>
  <dt>**io_evitadb_transaction_catalog_goes_live_total** (COUNTER)</dt>
  <dd>Catalog goes live invocation count</dd>
  <dt>**io_evitadb_transaction_isolated_wal_file_closed_total** (COUNTER)</dt>
  <dd>Closed files for isolated WAL storage.</dd>
  <dt>**io_evitadb_transaction_isolated_wal_file_opened_total** (COUNTER)</dt>
  <dd>Opened files for isolated WAL storage.</dd>
  <dt>**io_evitadb_transaction_new_catalog_version_propagated_collapsed_transactions** (COUNTER)</dt>
  <dd>**Transactions propagated to live view.**: N/A</dd>
  <dt>**io_evitadb_transaction_new_catalog_version_propagated_duration_milliseconds** (HISTOGRAM)</dt>
  <dd>New catalog version propagation duration in milliseconds</dd>
  <dt>**io_evitadb_transaction_new_catalog_version_propagated_total** (COUNTER)</dt>
  <dd>Catalog versions propagated</dd>
  <dt>**io_evitadb_transaction_off_heap_memory_allocation_change_allocated_memory_bytes** (GAUGE)</dt>
  <dd>**Allocated memory bytes**: N/A</dd>
  <dt>**io_evitadb_transaction_off_heap_memory_allocation_change_used_memory_bytes** (GAUGE)</dt>
  <dd>**Used memory bytes**: N/A</dd>
  <dt>**io_evitadb_transaction_transaction_accepted_duration_milliseconds** (HISTOGRAM)</dt>
  <dd>Conflict resolution duration in milliseconds

    **Labels:** <Term>resolution</Term>
</dd>
  <dt>**io_evitadb_transaction_transaction_accepted_total** (COUNTER)</dt>
  <dd>Transactions accepted

    **Labels:** <Term>resolution</Term>
</dd>
  <dt>**io_evitadb_transaction_transaction_appended_to_wal_appended_atomic_mutations** (COUNTER)</dt>
  <dd>**Atomic mutations appended.**: N/A</dd>
  <dt>**io_evitadb_transaction_transaction_appended_to_wal_appended_wal_bytes** (COUNTER)</dt>
  <dd>**Size of the written WAL in Bytes.**: N/A</dd>
  <dt>**io_evitadb_transaction_transaction_appended_to_wal_duration_milliseconds** (HISTOGRAM)</dt>
  <dd>Appending transaction to shared WAL duration in milliseconds</dd>
  <dt>**io_evitadb_transaction_transaction_appended_to_wal_total** (COUNTER)</dt>
  <dd>Transactions appended to WAL</dd>
  <dt>**io_evitadb_transaction_transaction_finished_duration_milliseconds** (HISTOGRAM)</dt>
  <dd>Transaction lifespan duration in milliseconds

    **Labels:** <Term>resolution</Term>
</dd>
  <dt>**io_evitadb_transaction_transaction_finished_oldest_transaction_timestamp_seconds** (GAUGE)</dt>
  <dd>**Oldest transaction timestamp**: N/A

    **Labels:** <Term>resolution</Term>
</dd>
  <dt>**io_evitadb_transaction_transaction_finished_total** (COUNTER)</dt>
  <dd>Transactions finished

    **Labels:** <Term>resolution</Term>
</dd>
  <dt>**io_evitadb_transaction_transaction_incorporated_to_trunk_collapsed_transactions** (COUNTER)</dt>
  <dd>**Transactions incorporated into shared data structures.**: N/A</dd>
  <dt>**io_evitadb_transaction_transaction_incorporated_to_trunk_incorporation_duration_milliseconds** (HISTOGRAM)</dt>
  <dd>Incorporation duration in milliseconds</dd>
  <dt>**io_evitadb_transaction_transaction_incorporated_to_trunk_processed_atomic_mutations** (COUNTER)</dt>
  <dd>**Atomic mutations processed.**: N/A</dd>
  <dt>**io_evitadb_transaction_transaction_incorporated_to_trunk_processed_local_mutations** (COUNTER)</dt>
  <dd>**Local mutations processed.**: N/A</dd>
  <dt>**io_evitadb_transaction_transaction_processed_lag_milliseconds** (HISTOGRAM)</dt>
  <dd>**Transaction lag between being committed and finally visible to all**: N/A</dd>
  <dt>**io_evitadb_transaction_transaction_queued_duration_milliseconds** (HISTOGRAM)</dt>
  <dd>Transaction waiting time in queue.

    **Labels:** <Term>stage</Term>
</dd>
  <dt>**io_evitadb_transaction_transaction_started_total** (COUNTER)</dt>
  <dd>Transactions initiated</dd>
  <dt>**io_evitadb_transaction_wal_cache_size_changed_locations_cached** (GAUGE)</dt>
  <dd>**Total cached locations in WAL file**: N/A</dd>
  <dt>**io_evitadb_transaction_wal_rotation_duration_milliseconds** (HISTOGRAM)</dt>
  <dd>WAL rotation duration in milliseconds</dd>
  <dt>**io_evitadb_transaction_wal_rotation_total** (COUNTER)</dt>
  <dd>WAL rotations</dd>
</dl>

