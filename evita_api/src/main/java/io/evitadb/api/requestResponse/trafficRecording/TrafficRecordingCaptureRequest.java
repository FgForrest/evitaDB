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

package io.evitadb.api.requestResponse.trafficRecording;


import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Traffic recording capture request is used to specify the criteria for retrieving contents from the traffic recording
 * log.
 *
 * @param content determines whether only basic information about the traffic recording is returned or the actual content
 * @param since specifies the time from which the traffic recording should be returned
 * @param sinceSessionSequenceId specifies the session sequence ID from which the traffic recording should be returned
 * @param sinceRecordSessionOffset specifies the record session offset from which the traffic recording should be returned
 *                                 (the offset is relative to the session sequence ID and starts from 0), offset allows
 *                                 to continue fetching the traffic recording from the last fetched record when session
 *                                 was not fully fetched
 * @param type specifies the types of traffic recording to be returned
 * @param sessionId specifies the session ID from which the traffic recording should be returned
 * @param longerThan specifies the minimum duration of the traffic recording to be returned
 * @param fetchingMoreBytesThan specifies the minimum number of bytes that the traffic recording should contain
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record TrafficRecordingCaptureRequest(
	@Nonnull TrafficRecordingContent content,
	@Nullable OffsetDateTime since,
	@Nullable Long sinceSessionSequenceId,
	@Nullable Integer sinceRecordSessionOffset,
	@Nullable TrafficRecordingType[] type,
	@Nullable UUID sessionId,
	@Nullable Duration longerThan,
	@Nullable Integer fetchingMoreBytesThan
) {

	/**
	 * List of all possible traffic recording types.
	 */
	public enum TrafficRecordingType {
		/**
		 * evitaDB session opened.
		 */
		SESSION_START,
		/**
		 * evitaDB session closed.
		 */
		SESSION_CLOSE,
		/**
		 * Query received via. API from the client - container contains original string of the client query.
		 * API might call multiple queries related to the same source query.
		 */
		SOURCE_QUERY,
		/**
		 * Query received via. API from the client is finalized and sent to the client. Container contains the final
		 * statistics aggregated over all operations related to the source query.
		 */
		SOURCE_QUERY_STATISTICS,
		/**
		 * Internal evitaDB query (evitaQL) was executed.
		 */
		QUERY,
		/**
		 * Internal call to retrieve single evitaDB entity. Record is not created for entities fetched as a part of
		 * a query.
		 */
		FETCH,
		/**
		 * Internal call to enrich contents of the evitaDB entity.
		 */
		ENRICHMENT,
		/**
		 * Internal call to mutate the evitaDB entity or catalog schema.
		 */
		MUTATION
	}

}