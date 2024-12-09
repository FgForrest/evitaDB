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
 * Container for a query and its metadata.
 *
 * @param sessionId              the session id which the mutation belongs to
 * @param query                  the query itself
 * @param created                the time when the query was issued
 * @param durationInMilliseconds the duration of the query execution within the session in milliseconds
 * @param totalRecordCount       the total number of records returned by the query
 * @param ioFetchCount           the number of IO fetches performed by the query
 * @param ioFetchedSizeBytes     the total size of the data fetched by the query in bytes
 * @param primaryKeys            the primary keys of the records returned by the query (in returned data chunk)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record QueryContainer(
	@Nonnull UUID sessionId,
	@Nonnull Query query,
	@Nonnull OffsetDateTime created,
	int durationInMilliseconds,
	int totalRecordCount,
	int ioFetchCount,
	int ioFetchedSizeBytes,
	@Nonnull int[] primaryKeys
) implements TrafficRecording {

	@Nonnull
	@Override
	public TrafficRecordingType type() {
		return TrafficRecordingType.QUERY;
	}

}
