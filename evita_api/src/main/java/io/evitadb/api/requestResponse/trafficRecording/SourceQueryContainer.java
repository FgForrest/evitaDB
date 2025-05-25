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
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * This container holds information about the source query handling start.
 *
 * @param sessionSequenceOrder the session sequence order of the source query (similar to session id but monotonic)
 * @param sessionId            unique identifier of the session the mutation belongs to
 * @param recordSessionOffset  the order (sequence) of the record in the session
 * @param sourceQueryId        unique identifier of the source query
 * @param created              time when the mutation was created
 * @param sourceQuery          unparsed, raw source query in particular format
 * @param labels               the client labels associated with the query
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record SourceQueryContainer(
	@Nullable Long sessionSequenceOrder,
	@Nonnull UUID sessionId,
	int recordSessionOffset,
	@Nullable Integer sessionRecordsCount,
	@Nonnull UUID sourceQueryId,
	@Nonnull OffsetDateTime created,
	@Nonnull String sourceQuery,
	@Nonnull Label[] labels,
	@Nullable String finishedWithError
) implements TransientTrafficRecording, TrafficRecordingWithLabels {

	public SourceQueryContainer(
		@Nonnull UUID sessionId,
		int recordSessionOffset,
		@Nonnull UUID sourceQueryId,
		@Nonnull OffsetDateTime created,
		@Nonnull String sourceQuery,
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
			sourceQuery,
			labels,
			finishedWithError
		);
	}

	@Nonnull
	@Override
	public TrafficRecordingType type() {
		return TrafficRecordingType.SOURCE_QUERY;
	}

	@Override
	public int durationInMilliseconds() {
		return 0;
	}

	@Override
	public int ioFetchedSizeBytes() {
		return 0;
	}

	@Override
	public int ioFetchCount() {
		return 0;
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof SourceQueryContainer that)) return false;

		return this.recordSessionOffset == that.recordSessionOffset &&
			this.sessionId.equals(that.sessionId) &&
			Arrays.equals(this.labels, that.labels) &&
			this.sourceQueryId.equals(that.sourceQueryId) &&
			this.sourceQuery.equals(that.sourceQuery) &&
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
		result = 31 * result + this.sourceQuery.hashCode();
		result = 31 * result + Arrays.hashCode(this.labels);
		result = 31 * result + Objects.hashCode(this.finishedWithError);
		return result;
	}
}
