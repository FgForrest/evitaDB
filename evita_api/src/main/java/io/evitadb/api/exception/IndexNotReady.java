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
 * Exception is thrown when TrafficRecording has not been indexed yet. The index is being built in the background lazily
 * on the first request for reading the traffic recording data and the request should be retried later.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class IndexNotReady extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -8131758666128500242L;

	public IndexNotReady(int indexBuildPercentage) {
		super(
			indexBuildPercentage == 0 ?
				"Index is not present - issuing creation, please try again later." :
				"Index is currently being build - " + indexBuildPercentage + "% done, please try again later."
		);
	}

}
