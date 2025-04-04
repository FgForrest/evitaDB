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


import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest.TrafficRecordingType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * This container holds a mutation and its metadata.
 *
 * @param sessionSequenceOrder   the session sequence order of the mutation (similar to session id but monotonic)
 * @param sessionId              the session id which the mutation belongs to
 * @param recordSessionOffset    the order (sequence) of the record in the session
 * @param created                the time when the mutation was created
 * @param durationInMilliseconds the duration of the mutation execution within the session in milliseconds
 * @param mutation               the mutation itself
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record MutationContainer(
	@Nullable Long sessionSequenceOrder,
	@Nonnull UUID sessionId,
	int recordSessionOffset,
	@Nullable Integer sessionRecordsCount,
	@Nonnull OffsetDateTime created,
	int durationInMilliseconds,
	@Nonnull Mutation mutation,
	@Nullable String finishedWithError
) implements TrafficRecording {

	public MutationContainer(
		@Nonnull UUID sessionId,
		int recordSessionOffset,
		@Nonnull OffsetDateTime created,
		int durationInMilliseconds,
		@Nonnull Mutation mutation,
		@Nullable String finishedWithError
	) {
		this(
			null,
			sessionId,
			recordSessionOffset,
			null,
			created,
			durationInMilliseconds,
			mutation,
			finishedWithError
		);
	}

	@Nonnull
	@Override
	public TrafficRecordingType type() {
		return TrafficRecordingType.MUTATION;
	}

	@Override
	public int ioFetchedSizeBytes() {
		return 0;
	}

	@Override
	public int ioFetchCount() {
		return 0;
	}

}
