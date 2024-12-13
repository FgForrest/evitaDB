/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.traffic;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TrafficRecordingOptions;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.head.Label;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.core.async.Scheduler;
import io.evitadb.core.file.ExportFileService;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Traffic recorder captures incoming queries and mutations and stores them for later analysis / replay. It can be used
 * as a development tool or for debugging purposes, source of data for performance analysis, reproducing issues or
 * verifying the correctness of the new database version.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface TrafficRecorder {

	/**
	 * Initializes traffic recorder with server options. Method is guaranteed to be called before any other method.
	 *
	 * @param catalogName       name of the catalog
	 * @param exportFileService export file service
	 * @param scheduler         scheduler
	 * @param storageOptions    storage options
	 * @param recordingOptions  traffic recording options
	 */
	void init(
		@Nonnull String catalogName,
		@Nonnull ExportFileService exportFileService,
		@Nonnull Scheduler scheduler,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TrafficRecordingOptions recordingOptions
	);

	/**
	 * Function is called when a new session is created.
	 *
	 * @param sessionId      unique identifier of the session
	 * @param catalogVersion snapshot version of the catalog this session is working with
	 * @param created        timestamp when the session was created
	 */
	void createSession(
		@Nonnull UUID sessionId,
		long catalogVersion,
		@Nonnull OffsetDateTime created);

	/**
	 * Function is called when a session is closed.
	 *
	 * @param sessionId unique identifier of the session
	 */
	void closeSession(
		@Nonnull UUID sessionId
	);

	/**
	 * Function is called when a query is executed.
	 *
	 * @param sessionId          unique identifier of the session the query belongs to
	 * @param query              query that was executed
	 * @param totalRecordCount   total number of records that match the query
	 *                           (not necessarily all of them were fetched)
	 * @param ioFetchCount       number of IO fetches that were needed to fetch all records
	 * @param ioFetchedSizeBytes number of bytes fetched from the disk
	 * @param primaryKeys        primary keys that were returned by the query
	 */
	void recordQuery(
		@Nonnull UUID sessionId,
		@Nonnull Query query,
		@Nonnull Label[] labels,
		@Nonnull OffsetDateTime now,
		int totalRecordCount,
		int ioFetchCount,
		int ioFetchedSizeBytes,
		@Nonnull int... primaryKeys
	);

	/**
	 * Function is called when existing entity if being enriched of newly fetched data.
	 *
	 * @param sessionId          unique identifier of the session the query belongs to
	 * @param query              the query that was enriched
	 * @param ioFetchCount       number of IO fetches that were needed to fetch all records
	 * @param ioFetchedSizeBytes number of bytes fetched from the disk
	 * @param primaryKey         the primary key of the record that was enriched
	 */
	void recordFetch(
		@Nonnull UUID sessionId,
		@Nonnull Query query,
		@Nonnull OffsetDateTime now,
		int ioFetchCount,
		int ioFetchedSizeBytes,
		int primaryKey
	);

	/**
	 * Function is called when existing entity if being enriched of newly fetched data.
	 *
	 * @param sessionId          unique identifier of the session the query belongs to
	 * @param query              the query that was enriched
	 * @param ioFetchCount       number of IO fetches that were needed to fetch all records
	 * @param ioFetchedSizeBytes number of bytes fetched from the disk
	 * @param primaryKey         the primary key of the record that was enriched
	 */
	void recordEnrichment(
		@Nonnull UUID sessionId,
		@Nonnull Query query,
		@Nonnull OffsetDateTime now,
		int ioFetchCount,
		int ioFetchedSizeBytes,
		int primaryKey
	);

	/**
	 * Function is called when a mutation is executed.
	 *
	 * @param sessionId unique identifier of the session the mutation belongs to
	 * @param mutation  mutation that was executed
	 */
	void recordMutation(
		@Nonnull UUID sessionId,
		@Nonnull OffsetDateTime now,
		@Nonnull Mutation mutation
	);

	/**
	 * Method registers RAW input query and assigns a unique identifier to it. All queries in this session that are
	 * labeled with {@link Label#LABEL_SOURCE_QUERY} will be registered as sub-queries of this source query.
	 *
	 * @param sessionId unique identifier of the session the mutation belongs to
	 * @param sourceQueryId unique identifier of the source query
	 * @param sourceQuery   unparsed, raw source query in particular format
	 * @param queryType     type of the query (e.g. GraphQL, REST, etc.)
	 */
	void setupSourceQuery(
		@Nonnull UUID sessionId,
		@Nonnull UUID sourceQueryId,
		@Nonnull OffsetDateTime now,
		@Nonnull String sourceQuery,
		@Nonnull String queryType
	);

	/**
	 * Method closes the source query and marks it as finalized. Overall statistics for all registered sub-queries
	 * will be aggregated and stored in the traffic recording along with this record.
	 *
	 * @param sessionId unique identifier of the session the mutation belongs to
	 * @param sourceQueryId unique identifier of the source query
	 */
	void closeSourceQuery(
		@Nonnull UUID sessionId,
		@Nonnull UUID sourceQueryId
	);

}
