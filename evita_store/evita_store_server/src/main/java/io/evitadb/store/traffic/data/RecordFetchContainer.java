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

package io.evitadb.store.traffic.data;

import io.evitadb.api.query.Query;
import io.evitadb.core.traffic.TrafficRecording;
import io.evitadb.core.traffic.TrafficRecordingCaptureRequest.TrafficRecordingType;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * This container holds information about single entity fetch.
 *
 * @param sessionId              the session id which the mutation belongs to
 * @param query                  the query accompanying the fetch
 * @param created                the time when the fetch was executed
 * @param durationInMilliseconds the duration of the fetch execution within the session in milliseconds
 * @param ioFetchCount           the number of IO fetches performed by the fetch
 * @param ioFetchedSizeBytes     the total size of the data fetched by the fetch in bytes
 * @param primaryKey             the primary key of the record being fetched
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record RecordFetchContainer(
	@Nonnull UUID sessionId,
	@Nonnull Query query,
	@Nonnull OffsetDateTime created,
	int durationInMilliseconds,
	int ioFetchCount,
	int ioFetchedSizeBytes,
	int primaryKey
) implements TrafficRecording {

	@Nonnull
	@Override
	public TrafficRecordingType type() {
		return TrafficRecordingType.FETCH;
	}

}
