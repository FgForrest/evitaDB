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

package io.evitadb.externalApi.api.system;

import io.evitadb.api.EvitaContract;
import io.evitadb.externalApi.api.system.model.HealthProblem;
import io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
import io.evitadb.externalApi.http.ExternalApiServer;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Integration interface allowing to provide health and readiness probes for the system API from different module.
 * In our case from the observability module.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface ProbesProvider {

	/**
	 * Method returns set of health problems identified on the server side.
	 * Result of this method is used for determining the "liveness" or "healthiness" of the server.
	 *
	 * @param evitaContract evita instance
	 * @param externalApiServer external API server
	 * @return set of health problems
	 * @see HealthProblem
	 */
	@Nonnull
	Set<HealthProblem> getHealthProblems(
		@Nonnull EvitaContract evitaContract,
		@Nonnull ExternalApiServer externalApiServer
	);

	/**
	 * Method returns data for determining whether it has already completely started and is ready to serve requests.
	 *
	 * @param evitaContract evita instance
	 * @param externalApiServer external API server
	 * @param apiCodes API codes to check (which are enabled)
	 * @return readiness data
	 */
	@Nonnull
	Readiness getReadiness(
		@Nonnull EvitaContract evitaContract,
		@Nonnull ExternalApiServer externalApiServer,
		@Nonnull String... apiCodes
	);

	/**
	 * Method returns data for determining whether server has already completely started and is ready to serve requests.
	 *
	 * @param state overall readiness state (over all APIs)
	 * @param apiStates detail of readiness state for each API
	 */
	record Readiness(
		@Nonnull ReadinessState state,
		@Nonnull ApiState[] apiStates
	) {

	}

	/**
	 * Detail of readiness state for particular API.
	 * @param apiCode API code representing {@link ExternalApiProviderRegistrar#getExternalApiCode()}
	 * @param isReady true if API is ready
	 */
	record ApiState(
		@Nonnull String apiCode,
		boolean isReady
	) {}

	/**
	 * Enum representing overall readiness state of the server.
	 */
	enum ReadinessState {

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
		SHUT_DOWN,
		/**
		 * Unknown state - cannot determine the state of the APIs (should not happen).
		 */
		UNKNOWN

	}

}
