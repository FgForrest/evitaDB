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

package io.evitadb.core.traffic;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TrafficRecordingOptions;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.head.Label;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.file.ExportFileService;
import io.evitadb.store.spi.SessionSink;
import io.evitadb.store.spi.TrafficRecorder;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * No-op implementation of the {@link TrafficRecorder} interface. This implementation does not record any traffic and
 * is used when recording is disabled by the {@link io.evitadb.api.configuration.TrafficRecordingOptions#enabled()} option.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class NoOpTrafficRecorder implements TrafficRecorder {
	public static final NoOpTrafficRecorder INSTANCE = new NoOpTrafficRecorder();

	@Override
	public void init(
		@Nonnull String catalogName,
		@Nonnull ExportFileService exportFileService,
		@Nonnull Scheduler scheduler,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TrafficRecordingOptions recordingOptions
	) {
		// no-op
	}

	@Override
	public void setSamplingPercentage(int samplingPercentage) {
		// no-op
	}

	@Override
	public void setSessionSink(@Nullable SessionSink sessionSink) {
		// no-op
	}

	@Override
	public void createSession(
		@Nonnull UUID sessionId,
		long catalogId,
		@Nonnull OffsetDateTime created
	) {
		// no-op
	}

	@Override
	public void closeSession(
		@Nonnull UUID sessionId,
		@Nullable String finishedWithError
	) {
		// no-op
	}

	@Override
	public void recordQuery(
		@Nonnull UUID sessionId,
		@Nonnull String queryDescription,
		@Nonnull Query query,
		@Nonnull Label[] labels,
		@Nonnull OffsetDateTime now,
		int totalRecordCount, int ioFetchCount, int ioFetchedSizeBytes,
		@Nonnull int[] primaryKeys,
		@Nullable String finishedWithError
	) {
		// no-op
	}

	@Override
	public void recordFetch(
		@Nonnull UUID sessionId, @Nonnull Query query, @Nonnull OffsetDateTime now,
		int ioFetchCount, int ioFetchedSizeBytes, int primaryKey,
		@Nullable String finishedWithError
	) {
		// no-op
	}

	@Override
	public void recordEnrichment(
		@Nonnull UUID sessionId, @Nonnull Query query, @Nonnull OffsetDateTime now,
		int ioFetchCount, int ioFetchedSizeBytes, int primaryKey,
		@Nullable String finishedWithError
	) {
		// no-op
	}

	@Override
	public void recordMutation(
		@Nonnull UUID sessionId,
		@Nonnull OffsetDateTime now,
		@Nonnull Mutation mutation,
		@Nullable String finishedWithError
	) {
		// no-op
	}

	@Override
	public void setupSourceQuery(
		@Nonnull UUID sessionId,
		@Nonnull UUID sourceQueryId,
		@Nonnull OffsetDateTime now,
		@Nonnull String sourceQuery,
		@Nonnull Label[] labels,
		@Nullable String finishedWithError
	) {
		// no-op
	}

	@Override
	public void closeSourceQuery(
		@Nonnull UUID sessionId,
		@Nonnull UUID sourceQueryId,
		@Nullable String finishedWithError
	) {
		// no-op
	}

	@Override
	public void close() {
		// no-op
	}

}
