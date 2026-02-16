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
 * Enumeration of health problems that can be detected by the evitaDB server's liveness probe. These problems
 * represent system-level issues that may degrade performance or indicate resource exhaustion.
 *
 * **Health vs. Readiness**
 *
 * Health problems affect the "liveness" probe (is the server alive?), whereas {@link ReadinessState} affects the
 * "readiness" probe (is the server ready to accept traffic?). A server can be alive but not ready, or alive with
 * health problems but still accepting requests.
 *
 * **Detection and Reporting**
 *
 * Health problems are detected by {@link io.evitadb.externalApi.observability.metric.ObservabilityProbesDetector}
 * and reported via the System API's health endpoint and Prometheus metrics (`io_evitadb_health_problems`).
 *
 * **Usage Context**
 *
 * - {@link io.evitadb.externalApi.api.system.ProbesProvider#getHealthProblems} returns the current set of detected
 * problems
 * - {@link io.evitadb.externalApi.observability.ObservabilityManager#recordHealthProblem} records problems to
 * Prometheus metrics
 * - {@link io.evitadb.externalApi.observability.ObservabilityManager#clearHealthProblem} clears problems from metrics
 * when resolved
 * - gRPC API exposes these via `GrpcHealthProblem` enum in management service responses
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public enum HealthProblem {

	/**
	 * Signaled when the JVM memory usage exceeds 90% of maximum heap size and either:
	 * - Old generation garbage collection has occurred (G1 Old Generation, PS MarkSweep, ConcurrentMarkSweep), or
	 * - An `OutOfMemoryError` has been thrown
	 *
	 * This condition indicates memory pressure that typically leads to repeated expensive full GC cycles, degrading
	 * system performance and increasing CPU usage. The problem persists until memory usage drops below the threshold
	 * and no new GC cycles or OOM errors occur.
	 *
	 * **Detection Logic**: Memory usage > 90% AND (old GC count increased OR OOM count increased)
	 */
	MEMORY_SHORTAGE,

	/**
	 * Signaled when at least one configured external API (gRPC, REST, GraphQL, System, Observability, Lab) fails
	 * to respond to internal health check calls within the expected timeout.
	 *
	 * This indicates that an API endpoint that should be serving requests is unresponsive, even though the server
	 * is running. Health checks are performed by
	 * {@link io.evitadb.externalApi.observability.metric.ObservabilityProbesDetector} by checking the readiness
	 * state of each enabled API.
	 *
	 * **Detection Logic**: At least one enabled API has `isReady() == false` or does not respond to check
	 */
	EXTERNAL_API_UNAVAILABLE,

	/**
	 * Signaled when the server's request executor rejects tasks due to full input queues, indicating the server
	 * cannot keep up with incoming request load.
	 *
	 * The problem is reported when the ratio of rejected tasks to submitted tasks (since last check) is greater
	 * than or equal to 2. This means for every task accepted, at least 2 are being rejected.
	 *
	 * The flag is cleared when the rejection ratio falls below the threshold, indicating the server has recovered
	 * and can process requests at the incoming rate again.
	 *
	 * **Detection Logic**: (rejected - lastRejected) / max((submitted - lastSubmitted), 1) > 2
	 */
	INPUT_QUEUES_OVERLOADED,

	/**
	 * Signaled when Java `Error` exceptions occur within the JVM, indicating fatal internal problems such as
	 * `StackOverflowError`, `OutOfMemoryError`, `InternalError`, or other serious JVM failures.
	 *
	 * These errors are usually not caused by client requests but represent fatal problems within the JVM itself,
	 * such as bytecode verification failures, class loading issues, or resource exhaustion. The presence of Java
	 * errors indicates the JVM may be in an unstable state.
	 *
	 * Errors are captured by {@link io.evitadb.externalApi.observability.agent.ErrorMonitor} via bytecode
	 * instrumentation and counted via `MetricHandler.JAVA_ERRORS_TOTAL` Prometheus counter.
	 *
	 * **Detection Logic**: Java error count has increased since last check
	 */
	JAVA_INTERNAL_ERRORS

}
