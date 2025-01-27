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

package io.evitadb.api.requestResponse.trafficRecording;


import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest.TrafficRecordingType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * This container holds information about the session closing (finalization).
 *
 * @param sessionSequenceOrder    the session sequence order of the session close (similar to session id but monotonic)
 * @param sessionId               the session id which the mutation belongs to
 * @param recordSessionOffset     the order (sequence) of the record in the session
 * @param catalogVersion          the version of the catalog
 * @param created                 the time when the mutation was created
 * @param durationInMilliseconds  the overall duration of the session in milliseconds
 * @param ioFetchCount            the overall number of IO fetches performed in this session
 * @param ioFetchedSizeBytes      the overall total size of the data fetched in this session in bytes
 * @param trafficRecordCount      the overall number of traffic records recorded for this session
 * @param queryCount              the overall number of queries executed in this session
 * @param entityFetchCount        the overall number of entities fetched in this session (excluding the entities fetched by queries)
 * @param mutationCount           the overall number of mutations executed in this session
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record SessionCloseContainer(
	@Nullable Long sessionSequenceOrder,
	@Nonnull UUID sessionId,
	int recordSessionOffset,
	@Nullable Integer sessionRecordsCount,
	long catalogVersion,
	@Nonnull OffsetDateTime created,
	int durationInMilliseconds,
	int ioFetchCount,
	int ioFetchedSizeBytes,
	int trafficRecordCount,
	int queryCount,
	int entityFetchCount,
	int mutationCount,
	@Nullable String finishedWithError
) implements TrafficRecording {

	public SessionCloseContainer(
		@Nonnull UUID sessionId,
		int recordSessionOffset,
		@Nullable Integer sessionRecordsCount,
		long catalogVersion,
		@Nonnull OffsetDateTime created,
		int durationInMilliseconds,
		int ioFetchCount,
		int ioFetchedSizeBytes,
		int trafficRecordCount,
		int trafficRecordsMissedOut,
		int queryCount,
		int entityFetchCount,
		int mutationCount,
		@Nullable String finishedWithError
	) {
		this(
			null,
			sessionId,
			recordSessionOffset,
			sessionRecordsCount,
			catalogVersion,
			created,
			durationInMilliseconds,
			ioFetchCount,
			ioFetchedSizeBytes,
			trafficRecordCount,
			queryCount,
			entityFetchCount,
			mutationCount,
			finishedWithError
		);
	}

	@Nonnull
	@Override
	public TrafficRecordingType type() {
		return TrafficRecordingType.SESSION_CLOSE;
	}

}
