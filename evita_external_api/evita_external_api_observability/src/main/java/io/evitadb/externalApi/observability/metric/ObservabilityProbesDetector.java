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

package io.evitadb.externalApi.observability.metric;

import io.evitadb.api.EvitaContract;
import io.evitadb.api.observability.HealthProblem;
import io.evitadb.api.observability.ReadinessState;
import io.evitadb.core.Evita;
import io.evitadb.core.executor.ObservableExecutorService;
import io.evitadb.externalApi.api.system.ProbesProvider;
import io.evitadb.externalApi.http.ExternalApiProvider;
import io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.observability.ObservabilityManager;
import io.evitadb.externalApi.observability.ObservabilityProvider;
import io.evitadb.utils.CollectionUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Optional.ofNullable;

/**
 * This class is responsible for detecting health problems in the system. It monitors:
 *
 * 1. The ratio of rejected tasks to submitted tasks in the executor. If the ratio is greater than 2, the input queues
 * are considered overloaded.
 * 2. The number of Java internal errors. If the number of errors has increased since the last check, the system is
 * considered unhealthy.
 * 3. The number of database internal errors. If the number of errors has increased since the last check, the system is
 * considered unhealthy.
 * 4. The number of Java OutOfMemory errors or Old generation garbage collections. If the current memory usage is above
 * 90% of the total available memory and the number of errors has increased since the last check, the system is considered
 * unhealthy.
 * 5. The readiness of the external APIs. If at least one external API is not ready, the system is considered unhealthy.
 */
@Slf4j
public class ObservabilityProbesDetector implements ProbesProvider, Closeable {
	private static final Set<HealthProblem> NO_HEALTH_PROBLEMS = EnumSet.noneOf(HealthProblem.class);
	private static final Set<String> OLD_GENERATION_GC_NAMES = Set.of("G1 Old Generation", "PS MarkSweep", "ConcurrentMarkSweep");
	private static final Duration HEALTH_CHECK_READINESS_RENEW_INTERVAL = Duration.ofSeconds(30);
	private static final AtomicBoolean HEALTH_CHECK_RUNNING = new AtomicBoolean(false);

	private final Runtime runtime = Runtime.getRuntime();
	private final List<GarbageCollectorMXBean> garbageCollectorMXBeans = ManagementFactory.getGarbageCollectorMXBeans()
		.stream()
		.filter(gc -> OLD_GENERATION_GC_NAMES.contains(gc.getName()))
		.toList();
	private final AtomicLong lastSeenRejectedTaskCount = new AtomicLong(0L);
	private final AtomicLong lastSeenSubmittedTaskCount = new AtomicLong(0L);
	private final AtomicLong lastSeenJavaErrorCount = new AtomicLong(0L);
	private final AtomicLong lastSeenJavaOOMErrorCount = new AtomicLong(0L);
	private final AtomicLong lastSeenEvitaErrorCount = new AtomicLong(0L);
	private final AtomicLong lastSeenJavaGarbageCollections = new AtomicLong(0L);
	private final AtomicBoolean seenReady = new AtomicBoolean();
	private final AtomicReference<ReadinessWithTimestamp> lastReadinessSeen = new AtomicReference<>();
	private ExecutorService internalExecutor;
	@Nullable private ObservabilityManager observabilityManager;

	/**
	 * Records the result of the health problem check.
	 *
	 * @param healthProblemCheckResult the result of the health problem check
	 * @param healthProblems           the set of health problems
	 * @param theObservabilityManager  the observability manager for recording the health problem
	 */
	private static void recordResult(
		@Nonnull HealthProblemCheckResult healthProblemCheckResult,
		@Nonnull Set<HealthProblem> healthProblems,
		@Nullable ObservabilityManager theObservabilityManager
	) {
		if (healthProblemCheckResult.present()) {
			if (healthProblemCheckResult.healthProblem() != null) {
				healthProblems.add(healthProblemCheckResult.healthProblem());
			}
			if (theObservabilityManager != null) {
				theObservabilityManager.recordHealthProblem(healthProblemCheckResult.healthProblemName());
			}
		} else {
			if (theObservabilityManager != null) {
				theObservabilityManager.clearHealthProblem(healthProblemCheckResult.healthProblemName());
			}
		}
	}

	@Nonnull
	@Override
	public Set<HealthProblem> getHealthProblems(
		@Nonnull EvitaContract evitaContract,
		@Nonnull ExternalApiServer externalApiServer,
		@Nonnull String... apiCodes
	) {
		final EnumSet<HealthProblem> healthProblems = EnumSet.noneOf(HealthProblem.class);
		final ObservabilityManager theObservabilityManager = getObservabilityManager(externalApiServer).orElse(null);

		if (evitaContract instanceof Evita evita) {
			recordResult(checkInputQueues(evita), healthProblems, theObservabilityManager);
		}

		if (theObservabilityManager != null) {
			recordResult(checkEvitaErrors(theObservabilityManager), healthProblems, theObservabilityManager);
			recordResult(checkMemoryShortage(theObservabilityManager), healthProblems, theObservabilityManager);
			recordResult(checkJavaErrors(theObservabilityManager), healthProblems, theObservabilityManager);
		}

		getReadiness(evitaContract, externalApiServer, apiCodes);

		recordResult(checkApiReadiness(), healthProblems, theObservabilityManager);

		return healthProblems.isEmpty() ? NO_HEALTH_PROBLEMS : healthProblems;
	}

	@Nonnull
	@Override
	public Readiness getReadiness(@Nonnull EvitaContract evitaContract, @Nonnull ExternalApiServer externalApiServer, @Nonnull String... apiCodes) {
		final ReadinessWithTimestamp readinessWithTimestamp = this.lastReadinessSeen.get();
		final Readiness currentReadiness;
		if (readinessWithTimestamp == null ||
			(OffsetDateTime.now().minus(HEALTH_CHECK_READINESS_RENEW_INTERVAL).isAfter(readinessWithTimestamp.timestamp()) ||
				readinessWithTimestamp.result().state() != ReadinessState.READY)
		) {
			if (HEALTH_CHECK_RUNNING.compareAndSet(false, true)) {
				try {
					// enforce renewal of readiness check
					final Optional<ObservabilityManager> theObservabilityManager = getObservabilityManager(externalApiServer);
					// check the end-points availability
					//noinspection rawtypes
					final Collection<ExternalApiProviderRegistrar> availableExternalApis = ExternalApiServer.gatherExternalApiProviders();
					final Map<String, Boolean> readiness = CollectionUtils.createHashMap(availableExternalApis.size());
					final CompletableFuture<?>[] futures = new CompletableFuture[apiCodes.length];
					for (int i = 0; i < apiCodes.length; i++) {
						final String apiCode = apiCodes[i];
						futures[i] = CompletableFuture.runAsync(
							() -> {
								final ExternalApiProvider<?> apiProvider = externalApiServer.getExternalApiProviderByCode(apiCode);
								final boolean ready = apiProvider != null && apiProvider.isReady();
								synchronized (readiness) {
									readiness.put(apiCode, ready);
								}
								theObservabilityManager.ifPresent(it -> it.recordReadiness(apiCode, ready));
							},
							getInternalExecutor(availableExternalApis.size())
						);
					}

					// run all checks in parallel
					CompletableFuture.allOf(futures).join();
					final boolean ready = readiness.values().stream().allMatch(Boolean::booleanValue);
					if (ready) {
						this.seenReady.set(true);
					}
					currentReadiness = new Readiness(
						ready ? ReadinessState.READY : (this.seenReady.get() ? ReadinessState.STALLING : ReadinessState.STARTING),
						readiness.entrySet().stream()
							.map(entry -> new ApiState(entry.getKey(), entry.getValue()))
							.toArray(ApiState[]::new)
					);
					this.lastReadinessSeen.set(
						new ReadinessWithTimestamp(currentReadiness, OffsetDateTime.now())
					);
				} finally {
					HEALTH_CHECK_RUNNING.compareAndSet(true, false);
				}
			} else {
				currentReadiness = ofNullable(this.lastReadinessSeen.get())
					.map(ReadinessWithTimestamp::result)
					.orElse(
						new Readiness(
							ReadinessState.UNKNOWN,
							ExternalApiServer.gatherExternalApiProviders()
								.stream()
								.map(it -> new ApiState(it.getExternalApiCode(), false))
								.toArray(ApiState[]::new)
						)
					);
			}
		} else {
			currentReadiness = readinessWithTimestamp.result();
		}
		return currentReadiness;
	}

	@Override
	public void close() throws IOException {
		if (this.internalExecutor != null) {
			this.internalExecutor.shutdown();
		}
	}

	/**
	 * We create separate thread pool service to avoid contention of default internal Java fork-join pool. We don't
	 * use evitaDB thread pools to avoid contention with the main thread pool as well.
	 *
	 * @param parallelism the parallelism level
	 * @return the executor service
	 */
	@Nonnull
	private ExecutorService getInternalExecutor(int parallelism) {
		if (this.internalExecutor == null) {
			this.internalExecutor = new ForkJoinPool(parallelism);
		}
		return this.internalExecutor;
	}

	/**
	 * Checks the readiness of the external APIs.
	 *
	 * @return the result of the check
	 */
	@Nonnull
	private HealthProblemCheckResult checkApiReadiness() {
		final ReadinessWithTimestamp readiness = this.lastReadinessSeen.get();
		return new HealthProblemCheckResult(
			HealthProblem.EXTERNAL_API_UNAVAILABLE,
			readiness == null || readiness.result().state() != ReadinessState.READY
		);
	}

	/**
	 * Checks the memory shortage. If the current memory usage is above 90% of the total available memory and the number
	 * of errors has increased since the last check, the system is considered unhealthy.
	 *
	 * @param theObservabilityManager the observability manager
	 * @return the result of the check
	 */
	@Nonnull
	private HealthProblemCheckResult checkMemoryShortage(@Nonnull ObservabilityManager theObservabilityManager) {
		// if the number of errors has increased since the last check, we could consider the system as unhealthy
		final long javaOOMErrorCount = theObservabilityManager.getJavaOutOfMemoryErrorCount();
		// get used memory of the JVM
		final float usedMemory = (float) (this.runtime.totalMemory() - this.runtime.freeMemory()) / (float) this.runtime.maxMemory();
		final long oldGenerationCollectionCount = this.garbageCollectorMXBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
		final HealthProblemCheckResult result = new HealthProblemCheckResult(
			HealthProblem.MEMORY_SHORTAGE,
			usedMemory > 0.9f &&
				(
					javaOOMErrorCount > this.lastSeenJavaOOMErrorCount.get() ||
						oldGenerationCollectionCount > this.lastSeenJavaGarbageCollections.get()
				)
		);
		this.lastSeenJavaOOMErrorCount.set(javaOOMErrorCount);
		this.lastSeenJavaGarbageCollections.set(oldGenerationCollectionCount);
		return result;
	}

	/**
	 * Checks the number of Java internal errors.
	 *
	 * @param theObservabilityManager the observability manager
	 * @return the result of the check
	 */
	@Nonnull
	private HealthProblemCheckResult checkJavaErrors(@Nonnull ObservabilityManager theObservabilityManager) {
		// if the number of errors has increased since the last check, we could consider the system as unhealthy
		final long javaErrorCount = theObservabilityManager.getJavaErrorCount();
		final HealthProblemCheckResult result = new HealthProblemCheckResult(
			HealthProblem.JAVA_INTERNAL_ERRORS,
			javaErrorCount > this.lastSeenJavaErrorCount.get()
		);
		this.lastSeenJavaErrorCount.set(javaErrorCount);
		return result;
	}

	/**
	 * Checks the rejection / submission rate of the executor input queues.
	 *
	 * @param evita the Evita instance
	 * @return the result of the check
	 */
	@Nonnull
	private HealthProblemCheckResult checkInputQueues(@Nonnull Evita evita) {
		final ObservableExecutorService executor = evita.getRequestExecutor();
		final long rejectedTaskCount = executor.getRejectedTaskCount();
		final long submittedTaskCount = executor.getSubmittedTaskCount();
		// if the ratio of rejected task to submitted tasks is greater than 2, we could consider queues as overloaded
		final HealthProblemCheckResult result = new HealthProblemCheckResult(
			HealthProblem.INPUT_QUEUES_OVERLOADED,
			(rejectedTaskCount - this.lastSeenRejectedTaskCount.get()) / (Math.max(submittedTaskCount - this.lastSeenSubmittedTaskCount.get(), 1)) > 2
		);
		this.lastSeenRejectedTaskCount.set(rejectedTaskCount);
		this.lastSeenSubmittedTaskCount.set(submittedTaskCount);
		return result;
	}

	/**
	 * Checks the number of database internal errors. This check doesn't affect the system health, but it's propagated
	 * to metrics.
	 *
	 * @param theObservabilityManager the observability manager
	 * @return the result of the check
	 */
	@Nonnull
	private HealthProblemCheckResult checkEvitaErrors(@Nonnull ObservabilityManager theObservabilityManager) {
		// if the number of errors has increased since the last check, we could consider the system as unhealthy
		final long evitaErrorCount = theObservabilityManager.getEvitaErrorCount();
		final HealthProblemCheckResult result = new HealthProblemCheckResult(
			"EVITA_DB_INTERNAL_ERRORS",
			evitaErrorCount > this.lastSeenEvitaErrorCount.get()
		);
		this.lastSeenEvitaErrorCount.set(evitaErrorCount);
		return result;
	}

	/**
	 * Returns the observability manager from the external API server.
	 *
	 * @param externalApiServer the external API server
	 * @return the observability manager or NULL if it is not available
	 */
	@Nonnull
	private Optional<ObservabilityManager> getObservabilityManager(@Nonnull ExternalApiServer externalApiServer) {
		if (this.observabilityManager == null) {
			final Optional<ObservabilityProvider> apiProvider = ofNullable(
				externalApiServer.getExternalApiProviderByCode(ObservabilityProvider.CODE)
			);
			this.observabilityManager = apiProvider
				.map(ObservabilityProvider::getObservabilityManager)
				.orElse(null);
		}
		return ofNullable(this.observabilityManager);
	}

	/**
	 * This record represents the result of a health problem check.
	 *
	 * @param healthProblem type of health problem
	 * @param present       true if the health problem is present, false otherwise
	 */
	private record HealthProblemCheckResult(
		@Nullable HealthProblem healthProblem,
		@Nonnull String healthProblemName,
		boolean present
	) {

		public HealthProblemCheckResult(@Nonnull HealthProblem healthProblem, boolean present) {
			this(healthProblem, healthProblem.name(), present);
		}

		public HealthProblemCheckResult(@Nonnull String healthProblem, boolean present) {
			this(null, healthProblem, present);
		}

	}

	/**
	 * Record keeps the readiness result and the detail of readiness result for each API along with the timestamp when
	 * it was recorded.
	 *
	 * @param result    overall readiness result (over all APIs)
	 * @param timestamp timestamp when the readiness was recorded
	 */
	private record ReadinessWithTimestamp(
		@Nonnull Readiness result,
		@Nonnull OffsetDateTime timestamp
	) {
	}

}
