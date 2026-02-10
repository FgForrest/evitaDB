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
 * Enumeration representing the overall readiness state of the evitaDB server for accepting client requests.
 * Readiness state is determined by aggregating the readiness of all configured external API endpoints
 * (gRPC, REST, GraphQL, System, Observability, Lab).
 *
 * **Readiness vs. Health**
 *
 * Readiness state affects the "readiness" probe (is the server ready to accept traffic?), whereas
 * {@link HealthProblem} affects the "liveness" probe (is the server alive and healthy?). A server can be alive
 * but not ready (e.g., during startup), or ready with health problems (e.g., memory pressure but still serving
 * requests).
 *
 * **State Transitions**
 *
 * Typical state flow during server lifecycle:
 * 1. `STARTING` → Server is initializing, APIs are being started
 * 2. `READY` → All APIs are operational and accepting requests
 * 3. `STALLING` → One or more APIs became unresponsive (temporary issue or degradation)
 * 4. `SHUTDOWN` → Server is terminating, APIs are being stopped
 *
 * The `UNKNOWN` state is a fallback for race conditions or concurrent health checks.
 *
 * **Caching Behavior**
 *
 * Readiness checks are cached for 30 seconds (when state is `READY`) to avoid overwhelming the system with
 * concurrent health checks. Non-ready states trigger immediate re-evaluation.
 *
 * **Usage Context**
 *
 * - {@link io.evitadb.externalApi.api.system.ProbesProvider#getReadiness} returns readiness state with per-API
 * details
 * - {@link io.evitadb.externalApi.observability.metric.ObservabilityProbesDetector} performs readiness checks by
 * calling `isReady()` on each enabled API provider
 * - {@link io.evitadb.externalApi.observability.ObservabilityManager#recordReadiness} records per-API readiness
 * to Prometheus metrics (`io_evitadb_api_readiness`)
 * - gRPC API exposes this via `GrpcReadiness` enum in management service responses
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public enum ReadinessState {

	/**
	 * Server is in the startup phase and at least one configured external API is not yet ready to accept requests.
	 *
	 * This is the initial state when the server process starts. APIs are being initialized, endpoints are being
	 * registered, and resources are being allocated. Clients should retry requests or wait until the state
	 * transitions to `READY`.
	 *
	 * **Condition**: At least one API has `isReady() == false` AND server has never been fully ready before
	 */
	STARTING,

	/**
	 * All configured external APIs are ready and the server is accepting client requests.
	 *
	 * This is the healthy operational state. All enabled API providers (gRPC, REST, GraphQL, etc.) have
	 * completed initialization and their `isReady()` methods return `true`. The server is capable of processing
	 * incoming requests.
	 *
	 * **Condition**: All enabled APIs have `isReady() == true`
	 */
	READY,

	/**
	 * One or more APIs that were previously ready have become unresponsive or unavailable, indicating a degraded
	 * state.
	 *
	 * This state indicates a runtime problem occurred after successful startup. Possible causes include:
	 * - Network endpoint binding failures
	 * - Resource exhaustion preventing request handling
	 * - Internal errors in API provider logic
	 * - External dependency failures (though evitaDB is designed to be self-contained)
	 *
	 * Clients should treat this as a temporary issue and retry with backoff. If the problem persists, check
	 * server logs and health problems.
	 *
	 * **Condition**: At least one API has `isReady() == false` AND server was previously fully ready (has seen
	 * `READY` state)
	 */
	STALLING,

	/**
	 * Server is shutting down gracefully. All APIs are being stopped and no longer accept new requests.
	 *
	 * This state is entered when the server receives a shutdown signal (SIGTERM, SIGINT) or when `Evita.close()`
	 * is called programmatically. Existing in-flight requests may complete, but no new requests will be accepted.
	 *
	 * Clients should stop sending requests and establish connections to another server instance.
	 *
	 * **Condition**: Server shutdown has been initiated
	 */
	SHUTDOWN,

	/**
	 * Readiness state cannot be determined, typically due to concurrent health checks or initialization race
	 * conditions.
	 *
	 * This state should be rare and transient. It occurs when multiple threads attempt to evaluate readiness
	 * simultaneously and one thread cannot acquire the check lock. Clients should treat this as equivalent to
	 * `STARTING` and retry.
	 *
	 * **Condition**: Concurrent readiness check is already running OR API provider list is unavailable
	 */
	UNKNOWN

}
