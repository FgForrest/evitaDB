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


import io.evitadb.api.requestResponse.trafficRecording.TrafficRecording;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest.TrafficRecordingType;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * This container holds information about the source query handling start.
 *
 * @param sessionId     unique identifier of the session the mutation belongs to
 * @param recordSessionOffset    the order (sequence) of the record in the session
 * @param sourceQueryId unique identifier of the source query
 * @param created       time when the mutation was created
 * @param sourceQuery   unparsed, raw source query in particular format
 * @param queryType     type of the query (e.g. GraphQL, REST, etc.)
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record SourceQueryContainer(
	@Nonnull UUID sessionId,
	int recordSessionOffset,
	@Nonnull UUID sourceQueryId,
	@Nonnull OffsetDateTime created,
	@Nonnull String sourceQuery,
	@Nonnull String queryType
) implements TrafficRecording {

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

}
