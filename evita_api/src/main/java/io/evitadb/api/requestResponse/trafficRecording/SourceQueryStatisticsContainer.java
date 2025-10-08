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


import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest.TrafficRecordingType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * This container holds information about the source query finalization.
 *
 * @param sessionSequenceOrder   the session sequence order of the source query statistics (similar to session id but monotonic)
 * @param sessionId              the session id which the mutation belongs to
 * @param recordSessionOffset    the order (sequence) of the record in the session
 * @param sourceQueryId          the source query id
 * @param created                the time when the mutation was created
 * @param durationInMilliseconds the overall duration of the session in milliseconds
 * @param ioFetchCount           the overall number of IO fetches performed in this session
 * @param ioFetchedSizeBytes     the overall total size of the data fetched in this session in bytes
 * @param returnedRecordCount    the total number of records returned by the query ({@link EvitaResponse#getRecordData()} size)
 * @param totalRecordCount       the total number of records calculated by the query ({@link EvitaResponse#getTotalRecordCount()})
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record SourceQueryStatisticsContainer(
	@Nullable Long sessionSequenceOrder,
	@Nonnull UUID sessionId,
	int recordSessionOffset,
	@Nullable Integer sessionRecordsCount,
	@Nonnull UUID sourceQueryId,
	@Nonnull OffsetDateTime created,
	int durationInMilliseconds,
	int ioFetchCount,
	int ioFetchedSizeBytes,
	int returnedRecordCount,
	int totalRecordCount,
	@Nonnull Label[] labels,
	@Nullable String finishedWithError
) implements TransientTrafficRecording, TrafficRecordingWithLabels {

	public SourceQueryStatisticsContainer(
		@Nonnull UUID sessionId,
		int recordSessionOffset,
		@Nonnull UUID sourceQueryId,
		@Nonnull OffsetDateTime created,
		int durationInMilliseconds,
		int ioFetchCount,
		int ioFetchedSizeBytes,
		int returnedRecordCount,
		int totalRecordCount,
		@Nonnull Label[] labels,
		@Nullable String finishedWithError
	) {
		this(
			null,
			sessionId,
			recordSessionOffset,
			null,
			sourceQueryId,
			created,
			durationInMilliseconds,
			ioFetchCount,
			ioFetchedSizeBytes,
			returnedRecordCount,
			totalRecordCount,
			labels,
			finishedWithError
		);
	}

	@Nonnull
	@Override
	public TrafficRecordingType type() {
		return TrafficRecordingType.SOURCE_QUERY_STATISTICS;
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof SourceQueryStatisticsContainer that)) return false;

		return this.ioFetchCount == that.ioFetchCount &&
			this.totalRecordCount == that.totalRecordCount &&
			this.ioFetchedSizeBytes == that.ioFetchedSizeBytes &&
			this.recordSessionOffset == that.recordSessionOffset &&
			this.returnedRecordCount == that.returnedRecordCount &&
			this.durationInMilliseconds == that.durationInMilliseconds &&
			this.sessionId.equals(that.sessionId) &&
			Arrays.equals(this.labels, that.labels) &&
			this.sourceQueryId.equals(that.sourceQueryId) &&
			this.created.equals(that.created) &&
			Objects.equals(this.finishedWithError, that.finishedWithError) &&
			Objects.equals(this.sessionSequenceOrder, that.sessionSequenceOrder) &&
			Objects.equals(this.sessionRecordsCount, that.sessionRecordsCount);
	}

	@Override
	public int hashCode() {
		int result = Objects.hashCode(this.sessionSequenceOrder);
		result = 31 * result + this.sessionId.hashCode();
		result = 31 * result + this.recordSessionOffset;
		result = 31 * result + Objects.hashCode(this.sessionRecordsCount);
		result = 31 * result + this.sourceQueryId.hashCode();
		result = 31 * result + this.created.hashCode();
		result = 31 * result + this.durationInMilliseconds;
		result = 31 * result + this.ioFetchCount;
		result = 31 * result + this.ioFetchedSizeBytes;
		result = 31 * result + this.returnedRecordCount;
		result = 31 * result + this.totalRecordCount;
		result = 31 * result + Arrays.hashCode(this.labels);
		result = 31 * result + Objects.hashCode(this.finishedWithError);
		return result;
	}
}
