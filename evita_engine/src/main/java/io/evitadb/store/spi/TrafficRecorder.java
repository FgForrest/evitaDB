/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.store.spi;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TrafficRecordingOptions;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.head.Label;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecording;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingWithLabels;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.file.ExportFileService;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Traffic recorder captures incoming queries and mutations and stores them for later analysis / replay. It can be used
 * as a development tool or for debugging purposes, source of data for performance analysis, reproducing issues or
 * verifying the correctness of the new database version.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface TrafficRecorder extends Closeable {

	/**
	 * Creates a predicate for filtering TrafficRecording objects based on the criteria specified
	 * in the given TrafficRecordingCaptureRequest. The predicate checks each TrafficRecording
	 * against multiple criteria including type, sessionId, creation time, fetched bytes, and duration.
	 *
	 * @param request the TrafficRecordingCaptureRequest containing the criteria to be used for filtering
	 * @return a predicate that evaluates to true for TrafficRecording objects matching the specified criteria
	 */
	@Nonnull
	static Predicate<TrafficRecording> createRequestPredicate(
		@Nonnull TrafficRecordingCaptureRequest request,
		@Nonnull StreamDirection direction
	) {
		Predicate<TrafficRecording> requestPredicate = tr -> true;
		if (request.sessionId() != null) {
			requestPredicate = requestPredicate.and(
				tr -> Arrays.stream(request.sessionId()).anyMatch(sid -> sid.equals(tr.sessionId()))
			);
		}
		if (request.sinceRecordSessionOffset() != null) {
			Assert.isTrue(
				request.sinceSessionSequenceId() != null,
				"Filter `sinceRecordSessionOffset` requires `sinceSessionSequenceId` to be set!"
			);
			if (direction == StreamDirection.FORWARD) {
				requestPredicate = requestPredicate.and(
					tr -> !Objects.equals(tr.sessionSequenceOrder(), request.sinceSessionSequenceId()) ||
						tr.recordSessionOffset() >= request.sinceRecordSessionOffset()
				);
			} else {
				requestPredicate = requestPredicate.and(
					tr -> !Objects.equals(tr.sessionSequenceOrder(), request.sinceSessionSequenceId()) ||
						tr.recordSessionOffset() <= request.sinceRecordSessionOffset()
				);
			}
		}
		if (request.since() != null) {
			if (direction == StreamDirection.FORWARD) {
				requestPredicate = requestPredicate.and(
					tr -> tr.created().isAfter(request.since()) || tr.created().isEqual(request.since())
				);
			} else {
				requestPredicate = requestPredicate.and(
					tr -> tr.created().isBefore(request.since()) || tr.created().isEqual(request.since())
				);
			}
		}
		if (request.fetchingMoreBytesThan() != null) {
			requestPredicate = requestPredicate.and(
				tr -> tr.ioFetchedSizeBytes() >= request.fetchingMoreBytesThan()
			);
		}
		if (request.longerThan() != null) {
			final long thresholdMillis = request.longerThan().toMillis();
			requestPredicate = requestPredicate.and(
				trafficRecording -> trafficRecording.durationInMilliseconds() >= thresholdMillis
			);
		}
		if (request.types() != null) {
			requestPredicate = requestPredicate.and(
				tr -> Arrays.stream(request.types()).anyMatch(it -> it == tr.type())
			);
		}
		if (request.labels() != null) {
			requestPredicate = requestPredicate.and(
				tr -> tr instanceof TrafficRecordingWithLabels cwl &&
					request.labelsGroupedByName()
						.entrySet()
						.stream()
						.allMatch(
							it -> {
								for (io.evitadb.api.requestResponse.trafficRecording.Label label : cwl.labels()) {
									final String containerKey = it.getKey();
									if (Objects.equals(containerKey, label.name())) {
										for (Serializable value : it.getValue()) {
											// this is a bit tricky, data can come in formatted form, so we need to compare it in both ways
											final Serializable containerValue = label.value();
											if (Objects.equals(value, containerValue) ||
												(value instanceof String && Objects.equals(value, EvitaDataTypes.formatValue(containerValue)))
											) {
												return true;
											}
										}
									}
								}
								return false;
							}
						)
			);
		}
		return requestPredicate;
	}

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
	 * Sets the sampling rate for the traffic recording. The sampling rate determines how many sessions are recorded.
	 * Initial values is set in {@link #init(String, ExportFileService, Scheduler, StorageOptions, TrafficRecordingOptions)}
	 * from {@link TrafficRecordingOptions#trafficSamplingPercentage()}
	 *
	 * @param samplingPercentage sampling rate in percentage (1 - 100)
	 */
	void setSamplingPercentage(int samplingPercentage);

	/**
	 * Sets the session sink that will be used to store the traffic recording. Might be null if the no persistent
	 * recording should remain and only data in current buffer should be used.
	 *
	 * @param sessionSink session sink implementation
	 */
	void setSessionSink(@Nullable SessionSink sessionSink);

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
		@Nonnull OffsetDateTime created
	);

	/**
	 * Function is called when a session is closed.
	 *
	 * @param sessionId         unique identifier of the session
	 * @param finishedWithError error message if the session was closed with an error
	 */
	void closeSession(
		@Nonnull UUID sessionId,
		@Nullable String finishedWithError
	);

	/**
	 * Function is called when a query is executed.
	 *
	 * @param sessionId          unique identifier of the session the query belongs to
	 * @param query              query that was executed
	 * @param labels             labels associated with the query
	 * @param totalRecordCount   total number of records that match the query
	 *                           (not necessarily all of them were fetched)
	 * @param ioFetchCount       number of IO fetches that were needed to fetch all records
	 * @param ioFetchedSizeBytes number of bytes fetched from the disk
	 * @param primaryKeys        primary keys that were returned by the query
	 * @param finishedWithError  error message if the query was not executed due to an error
	 */
	void recordQuery(
		@Nonnull UUID sessionId,
		@Nonnull String queryDescription,
		@Nonnull Query query,
		@Nonnull Label[] labels,
		@Nonnull OffsetDateTime now,
		int totalRecordCount,
		int ioFetchCount,
		int ioFetchedSizeBytes,
		@Nonnull int[] primaryKeys,
		@Nullable String finishedWithError
	);

	/**
	 * Function is called when existing entity if being enriched of newly fetched data.
	 *
	 * @param sessionId          unique identifier of the session the query belongs to
	 * @param query              the query that was fetched
	 * @param ioFetchCount       number of IO fetches that were needed to fetch all records
	 * @param ioFetchedSizeBytes number of bytes fetched from the disk
	 * @param primaryKey         the primary key of the record that was fetched
	 * @param finishedWithError  error message if the entity was not fetched due to an error
	 */
	void recordFetch(
		@Nonnull UUID sessionId,
		@Nonnull Query query,
		@Nonnull OffsetDateTime now,
		int ioFetchCount,
		int ioFetchedSizeBytes,
		int primaryKey,
		@Nullable String finishedWithError
	);

	/**
	 * Function is called when existing entity if being enriched of newly fetched data.
	 *
	 * @param sessionId          unique identifier of the session the query belongs to
	 * @param query              the query that was enriched
	 * @param ioFetchCount       number of IO fetches that were needed to fetch all records
	 * @param ioFetchedSizeBytes number of bytes fetched from the disk
	 * @param primaryKey         the primary key of the record that was enriched
	 * @param finishedWithError  error message if the entity was not enriched due to an error
	 */
	void recordEnrichment(
		@Nonnull UUID sessionId,
		@Nonnull Query query,
		@Nonnull OffsetDateTime now,
		int ioFetchCount,
		int ioFetchedSizeBytes,
		int primaryKey,
		@Nullable String finishedWithError
	);

	/**
	 * Function is called when a mutation is executed.
	 *
	 * @param sessionId         unique identifier of the session the mutation belongs to
	 * @param mutation          mutation that was executed
	 * @param finishedWithError error message if the mutation was not executed due to an error
	 */
	void recordMutation(
		@Nonnull UUID sessionId,
		@Nonnull OffsetDateTime now,
		@Nonnull Mutation mutation,
		@Nullable String finishedWithError
	);

	/**
	 * Method registers RAW input query and assigns a unique identifier to it. All queries in this session that are
	 * labeled with {@link Label#LABEL_SOURCE_QUERY} will be registered as sub-queries of this source query.
	 *
	 * @param sessionId         unique identifier of the session the mutation belongs to
	 * @param sourceQueryId     unique identifier of the source query
	 * @param sourceQuery       unparsed, raw source query in particular format
	 * @param labels            labels associated with the query
	 * @param finishedWithError error message if the source query was not registered due to an error
	 */
	void setupSourceQuery(
		@Nonnull UUID sessionId,
		@Nonnull UUID sourceQueryId,
		@Nonnull OffsetDateTime now,
		@Nonnull String sourceQuery,
		@Nonnull Label[] labels,
		@Nullable String finishedWithError
	);

	/**
	 * Method closes the source query and marks it as finalized. Overall statistics for all registered sub-queries
	 * will be aggregated and stored in the traffic recording along with this record.
	 *
	 * @param sessionId         unique identifier of the session the mutation belongs to
	 * @param sourceQueryId     unique identifier of the source query
	 * @param finishedWithError error message if the source query was not closed due to an error
	 */
	void closeSourceQuery(
		@Nonnull UUID sessionId,
		@Nonnull UUID sourceQueryId,
		@Nullable String finishedWithError
	);

	/**
	 * Method is called when the traffic recording should be stopped. It is guaranteed that no other method will be called
	 * after this one.
	 */
	@Override
	void close() throws IOException;

	/**
	 * Direction of the record stream.
	 */
	enum StreamDirection {

		FORWARD, REVERSE

	}

}
