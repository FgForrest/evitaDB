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

package io.evitadb.api;


import io.evitadb.api.exception.IndexNotReady;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecording;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest;

import javax.annotation.Nonnull;
import java.util.stream.Stream;

/**
 * Implementations of this interface can access and read traffic recordings from the previously captured log.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface TrafficRecordingReader {

	/**
	 * Returns stream of recordings that occurred in the catalog that match the specified criteria
	 * in the request. The method returns the stream of recordings in the order of their execution within sessions, and
	 * sessions are ordered by the timestamp of their finalization. The oldest records are returned first.
	 *
	 * !!! Important: remember to close the stream after you are done with it to release the resources
	 *
	 * @param request request that specifies the criteria for the recordings to be returned, multiple criteria definitions
	 *                 are combined with logical OR
	 * @return stream of recordings that match the specified criteria in reversed order
	 * @throws TemporalDataNotAvailableException when data for particular moment is not available anymore
	 * @throws IndexNotReady when the index is not ready yet and the data cannot be read
	 */
	@Nonnull
	Stream<TrafficRecording> getRecordings(
		@Nonnull TrafficRecordingCaptureRequest request
	) throws TemporalDataNotAvailableException, IndexNotReady;

	/**
	 * Returns stream of recordings that occurred in the catalog that match the specified criteria
	 * in the request. The method returns the stream of recordings in the order of their execution within sessions, and
	 * sessions are ordered by the timestamp of their finalization. The newest records are returned first.
	 *
	 * !!! Important: remember to close the stream after you are done with it to release the resources
	 *
	 * @param request request that specifies the criteria for the recordings to be returned, multiple criteria definitions
	 *                 are combined with logical OR
	 * @return stream of recordings that match the specified criteria in reversed order
	 * @throws TemporalDataNotAvailableException when data for particular moment is not available anymore
	 * @throws IndexNotReady when the index is not ready yet and the data cannot be read
	 */
	@Nonnull
	Stream<TrafficRecording> getRecordingsReversed(
		@Nonnull TrafficRecordingCaptureRequest request
	) throws TemporalDataNotAvailableException, IndexNotReady;

}
