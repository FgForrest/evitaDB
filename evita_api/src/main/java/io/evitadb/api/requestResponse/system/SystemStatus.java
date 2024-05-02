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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.system;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Contains basic information about evitaDB server.
 *
 * @param version version of evitaDB server taken from the MANIFEST.MF file
 * @param startedAt date and time when the server was started
 * @param uptime duration of time since the server was started
 * @param instanceId unique identifier of the server instance
 * @param catalogsCorrupted number of corrupted catalogs
 * @param catalogsOk number of catalogs that are ok
 * @param healthProblems set of flags that indicate health problems of the server
 */
public record SystemStatus(
	@Nonnull String version,
	@Nonnull OffsetDateTime startedAt,
	@Nonnull Duration uptime,
	@Nonnull String instanceId,
	int catalogsCorrupted,
	int catalogsOk,
	@Nonnull Set<HealthProblem> healthProblems
) implements Serializable {

	public enum HealthProblem {

		/**
		 * Signalized when the consumed memory never goes below 85% of the maximum heap size and the GC tries to free
		 * old generation multiple times consuming a log of CPU power.
		 */
		MEMORY_SHORTAGE,
		/**
		 * Signalized when the input queues are full and the server is not able to process incoming requests. This flag
		 * is cleared when the server is able to process incoming requests again.
		 */
		INPUT_QUEUES_OVERLOADED

	}

}
