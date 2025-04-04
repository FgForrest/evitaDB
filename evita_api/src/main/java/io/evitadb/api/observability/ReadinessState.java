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

package io.evitadb.api.observability;


/**
 * Enum representing overall readiness state of the server.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public enum ReadinessState {

	/**
	 * At least one API is not ready.
	 */
	STARTING,
	/**
	 * All APIs are ready.
	 */
	READY,
	/**
	 * At least one API that was ready is not ready anymore.
	 */
	STALLING,
	/**
	 * Server is shutting down. None of the APIs are ready.
	 */
	SHUTDOWN,
	/**
	 * Unknown state - cannot determine the state of the APIs (should not happen).
	 */
	UNKNOWN

}
