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

package io.evitadb.externalApi.api.system.model;

/**
 * This enum represents the possible health problems that can be signaled by the server.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public enum HealthProblem {

	/**
	 * Signalized when the consumed memory never goes below 85% of the maximum heap size and the GC tries to free
	 * old generation at least once (this situation usually leads to repeated attempts of expensive old generation GC
	 * and pressure on system CPUs).
	 */
	MEMORY_SHORTAGE,
	/**
	 * Signalized when the readiness probe signals that at least one external API, that is configured to be enabled
	 * doesn't respond to internal HTTP check call.
	 */
	EXTERNAL_API_UNAVAILABLE,
	/**
	 * Signalized when the input queues are full and the server is not able to process incoming requests. The problem
	 * is reported when there is ration of rejected tasks to accepted tasks >= 2. This flag is cleared when the rejection
	 * ratio decreases below the specified threshold, which signalizes that server is able to process incoming requests
	 * again.
	 */
	INPUT_QUEUES_OVERLOADED,
	/**
	 * Signaled when there are occurrences of Java internal errors. These errors are usually caused by the server
	 * itself and are not related to the client's requests. Java errors signal fatal problems inside the JVM.
	 */
	JAVA_INTERNAL_ERRORS

}
