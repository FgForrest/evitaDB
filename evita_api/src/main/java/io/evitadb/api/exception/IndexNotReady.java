/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.exception;


import io.evitadb.exception.EvitaInvalidUsageException;

import java.io.Serial;

/**
 * Exception thrown when an attempt is made to read traffic recording data before the index has been built.
 *
 * The traffic recording index is built lazily in the background when traffic recording data is first accessed.
 * During index construction, read operations will fail with this exception, signaling that the client should
 * retry the operation after a delay.
 *
 * This is a transient error condition that resolves once the index build completes. The exception message
 * includes the current build progress percentage to help clients implement appropriate retry strategies.
 *
 * **Usage Context:**
 * - Thrown by {@link io.evitadb.api.TrafficRecordingReader} when querying traffic data before indexing completes
 * - Used in {@link io.evitadb.store.traffic.OffHeapTrafficRecorder} to signal index unavailability
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class IndexNotReady extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -8131758666128500242L;

	/**
	 * Creates a new exception indicating that the traffic recording index is not yet ready.
	 *
	 * @param indexBuildPercentage percentage of index build completion (0-100); 0 means the index
	 *                             build has just started, 100 means complete (though the exception
	 *                             is not thrown in that case)
	 */
	public IndexNotReady(int indexBuildPercentage) {
		super(
			indexBuildPercentage == 0 ?
				"Index is not present - issuing creation, please try again later." :
				"Index is currently being build - " + indexBuildPercentage + "% done, please try again later."
		);
	}

}
