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

package io.evitadb.externalApi.api.system;

import io.evitadb.api.EvitaContract;
import io.evitadb.api.observability.HealthProblem;
import io.evitadb.api.observability.ReadinessState;
import io.evitadb.core.Evita;
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
	 * @param apiCodes API codes to check (which are enabled)
	 * @return set of health problems
	 * @see HealthProblem
	 */
	@Nonnull
	Set<HealthProblem> getHealthProblems(
		@Nonnull EvitaContract evitaContract,
		@Nonnull ExternalApiServer externalApiServer,
		@Nonnull String... apiCodes
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
	 * Called after the API server has opened its ports.
	 *
	 * @param evita engine served by the APIs
	 * @param externalApiServer running API server
	 */
	default void onServerStarted(@Nonnull Evita evita, @Nonnull ExternalApiServer externalApiServer) {
		// Providers without startup work have nothing to schedule.
	}

	/**
	 * Evaluates whether the initial API routing of every catalog has settled, independently of API-level readiness.
	 * Each call evaluates the current catalog states and the providers' routing obligations, so the pending count is
	 * current; once an evaluation finds nothing pending, the settlement is permanent.
	 *
	 * @param evita engine served by the APIs
	 * @param externalApiServer API server whose providers publish catalogs
	 * @return whether startup catalog routing has settled and the number of catalogs still pending
	 */
	@Nonnull
	CatalogRoutingSettlement getCatalogRoutingSettlement(
		@Nonnull Evita evita, @Nonnull ExternalApiServer externalApiServer
	);

	/**
	 * The outcome of evaluating catalog routing at startup.
	 *
	 * @param settled true once all startup catalog routing has settled, and permanently true thereafter
	 * @param catalogsPending number of catalogs still pending at evaluation, zero once settled
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	record CatalogRoutingSettlement(boolean settled, int catalogsPending) {

		/**
		 * Applies the startup publication gate to the API probe state.
		 *
		 * @param state readiness reported by the API probes
		 * @param settled whether startup catalog routing has settled
		 * @return the readiness state to report on the HTTP or gRPC surface
		 */
		@Nonnull
		public static ReadinessState resolveReadinessState(@Nonnull ReadinessState state, boolean settled) {
			return !settled && state == ReadinessState.READY ? ReadinessState.STARTING : state;
		}
	}

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

}
