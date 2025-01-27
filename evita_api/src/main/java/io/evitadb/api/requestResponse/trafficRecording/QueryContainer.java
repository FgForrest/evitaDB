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

import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest.TrafficRecordingType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Container for a query and its metadata.
 *
 * @param sessionSequenceOrder   the session sequence order of the query (similar to session id but monotonic)
 * @param sessionId              the session id which the mutation belongs to
 * @param recordSessionOffset    the order (sequence) of the record in the session
 * @param query                  the query itself
 * @param labels                 the client labels associated with the query
 * @param created                the time when the query was issued
 * @param durationInMilliseconds the duration of the query execution within the session in milliseconds
 * @param totalRecordCount       the total number of records calculated by the query ({@link EvitaResponse#getTotalRecordCount()})
 * @param ioFetchCount           the number of IO fetches performed by the query
 * @param ioFetchedSizeBytes     the total size of the data fetched by the query in bytes
 * @param primaryKeys            the primary keys of the records returned by the query (in returned data chunk)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record QueryContainer(
	@Nullable Long sessionSequenceOrder,
	@Nonnull UUID sessionId,
	int recordSessionOffset,
	@Nullable Integer sessionRecordsCount,
	@Nonnull String queryDescription,
	@Nonnull Query query,
	@Nonnull Label[] labels,
	@Nonnull OffsetDateTime created,
	int durationInMilliseconds,
	int totalRecordCount,
	int ioFetchCount,
	int ioFetchedSizeBytes,
	@Nonnull int[] primaryKeys,
	@Nullable String finishedWithError
) implements TrafficRecording {

	public QueryContainer(
		@Nonnull UUID sessionId,
		int recordSessionOffset,
		@Nonnull String queryDescription,
		@Nonnull Query query,
		@Nonnull Label[] labels,
		@Nonnull OffsetDateTime created,
		int durationInMilliseconds,
		int totalRecordCount,
		int ioFetchCount,
		int ioFetchedSizeBytes,
		@Nonnull int[] primaryKeys,
		@Nullable String finishedWithError
	) {
		this(
			null,
			sessionId,
			recordSessionOffset,
			null,
			queryDescription,
			query,
			labels,
			created,
			durationInMilliseconds,
			totalRecordCount,
			ioFetchCount,
			ioFetchedSizeBytes,
			primaryKeys,
			finishedWithError
		);
	}

	@Nonnull
	@Override
	public TrafficRecordingType type() {
		return TrafficRecordingType.QUERY;
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof QueryContainer that)) return false;

		return ioFetchCount == that.ioFetchCount &&
			totalRecordCount == that.totalRecordCount &&
			ioFetchedSizeBytes == that.ioFetchedSizeBytes &&
			recordSessionOffset == that.recordSessionOffset &&
			durationInMilliseconds == that.durationInMilliseconds &&
			query.equals(that.query) &&
			sessionId.equals(that.sessionId) &&
			Arrays.equals(labels, that.labels) &&
			Arrays.equals(primaryKeys, that.primaryKeys) &&
			created.equals(that.created) &&
			Objects.equals(sessionSequenceOrder, that.sessionSequenceOrder) &&
			Objects.equals(sessionRecordsCount, that.sessionRecordsCount) &&
			Objects.equals(finishedWithError, that.finishedWithError);
	}

	@Override
	public int hashCode() {
		int result = Objects.hashCode(sessionSequenceOrder);
		result = 31 * result + sessionId.hashCode();
		result = 31 * result + recordSessionOffset;
		result = 31 * result + Objects.hashCode(sessionRecordsCount);
		result = 31 * result + query.hashCode();
		result = 31 * result + Arrays.hashCode(labels);
		result = 31 * result + created.hashCode();
		result = 31 * result + durationInMilliseconds;
		result = 31 * result + totalRecordCount;
		result = 31 * result + ioFetchCount;
		result = 31 * result + ioFetchedSizeBytes;
		result = 31 * result + Arrays.hashCode(primaryKeys);
		result = 31 * result + Objects.hashCode(finishedWithError);
		return result;
	}
}
