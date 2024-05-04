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

package io.evitadb.externalApi.observability.metric;

import io.evitadb.api.EvitaContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.system.ProbesProvider;
import io.evitadb.externalApi.api.system.model.HealthProblem;
import io.evitadb.externalApi.http.ExternalApiProvider;
import io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.observability.ObservabilityManager;
import io.evitadb.externalApi.observability.ObservabilityProvider;
import io.evitadb.utils.CollectionUtils;
import org.jboss.threads.EnhancedQueueExecutor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
public class ObservabilityProbesDetector implements ProbesProvider {
	private static final Set<HealthProblem> NO_HEALTH_PROBLEMS = EnumSet.noneOf(HealthProblem.class);
	private static final Set<String> OLD_GENERATION_GC_NAMES = Set.of("G1 Old Generation", "PS MarkSweep", "ConcurrentMarkSweep");

	private final Runtime runtime = Runtime.getRuntime();
	private final List<GarbageCollectorMXBean> garbageCollectorMXBeans = ManagementFactory.getGarbageCollectorMXBeans()
		.stream()
		.filter(gc -> OLD_GENERATION_GC_NAMES.contains(gc.getName()))
		.toList();

	private ObservabilityManager observabilityManager;
	private final AtomicLong lastSeenRejectedTaskCount = new AtomicLong(0L);
	private final AtomicLong lastSeenSubmittedTaskCount = new AtomicLong(0L);
	private final AtomicLong lastSeenJavaErrorCount = new AtomicLong(0L);
	private final AtomicLong lastSeenJavaOOMErrorCount = new AtomicLong(0L);
	private final AtomicLong lastSeenEvitaErrorCount = new AtomicLong(0L);
	private final AtomicLong lastSeenJavaGarbageCollections = new AtomicLong(0L);
	private final AtomicBoolean seenReady = new AtomicBoolean();
	private final AtomicReference<Readiness> lastReadinessSeen = new AtomicReference<>();

	@Nonnull
	@Override
	public Set<HealthProblem> getHealthProblems(@Nonnull EvitaContract evitaContract, @Nonnull ExternalApiServer externalApiServer) {
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

		recordResult(checkApiReadiness(), healthProblems, theObservabilityManager);

		return healthProblems.isEmpty() ? NO_HEALTH_PROBLEMS : healthProblems;
	}

	/**
	 * Records the result of the health problem check.
	 * @param healthProblem the result of the health problem check
	 * @param healthProblems the set of health problems
	 * @param theObservabilityManager the observability manager for recording the health problem
	 */
	private static void recordResult(
		@Nonnull HealthProblemCheckResult healthProblem,
		@Nonnull Set<HealthProblem> healthProblems,
		@Nullable ObservabilityManager theObservabilityManager
	) {
		if (healthProblem.present()) {
			if (healthProblem.healthProblem() != null) {
				healthProblems.add(healthProblem.healthProblem());
			}
			if (theObservabilityManager != null) {
				theObservabilityManager.recordHealthProblem(healthProblem.healthProblemName());
			}
		} else {
			if (theObservabilityManager != null) {
				theObservabilityManager.clearHealthProblem(healthProblem.healthProblemName());
			}
		}
	}

	/**
	 * Checks the readiness of the external APIs.
	 * @return the result of the check
	 */
	@Nonnull
	private HealthProblemCheckResult checkApiReadiness() {
		final Readiness readiness = this.lastReadinessSeen.get();
		return new HealthProblemCheckResult(
			HealthProblem.EXTERNAL_API_UNAVAILABLE,
			readiness.state() != ReadinessState.READY
		);
	}

	/**
	 * Checks the memory shortage. If the current memory usage is above 90% of the total available memory and the number
	 * of errors has increased since the last check, the system is considered unhealthy.
	 * @param theObservabilityManager the observability manager
	 * @return the result of the check
	 */
	@Nonnull
	private HealthProblemCheckResult checkMemoryShortage(@Nonnull ObservabilityManager theObservabilityManager) {
		// if the number of errors has increased since the last check, we could consider the system as unhealthy
		final long javaOOMErrorCount = theObservabilityManager.getJavaOutOfMemoryErrorCount();
		// get used memory of the JVM
		final float usedMemory = 1.0f - ((float) runtime.freeMemory() / (float) runtime.maxMemory());
		final long oldGenerationCollectionCount = garbageCollectorMXBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
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
	 * @param evita the Evita instance
	 * @return the result of the check
	 */
	@Nonnull
	private HealthProblemCheckResult checkInputQueues(@Nonnull Evita evita) {
		final EnhancedQueueExecutor executor = evita.getExecutor();
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

	@Nonnull
	@Override
	public Readiness getReadiness(@Nonnull EvitaContract evitaContract, @Nonnull ExternalApiServer externalApiServer, @Nonnull String... apiCodes) {
		final Optional<ObservabilityManager> theObservabilityManager = getObservabilityManager(externalApiServer);
		// check the end-points availability
		//noinspection rawtypes
		final Collection<ExternalApiProviderRegistrar> availableExternalApis = ExternalApiServer.gatherExternalApiProviders();
		final Map<String, Boolean> readiness = CollectionUtils.createHashMap(availableExternalApis.size());
		for (String apiCode : apiCodes) {
			final ExternalApiProvider<?> apiProvider = externalApiServer.getExternalApiProviderByCode(apiCode);
			readiness.put(apiProvider.getCode(), apiProvider.isReady());
			theObservabilityManager.ifPresent(it -> it.recordReadiness(apiProvider.getCode(), apiProvider.isReady()));
		}
		final boolean ready = readiness.values().stream().allMatch(Boolean::booleanValue);
		if (ready) {
			this.seenReady.set(true);
		}
		final Readiness currentReadiness = new Readiness(
			ready ? ReadinessState.READY : (this.seenReady.get() ? ReadinessState.STALLING : ReadinessState.STARTING),
			readiness.entrySet().stream()
				.map(entry -> new ApiState(entry.getKey(), entry.getValue()))
				.toArray(ApiState[]::new)
		);
		this.lastReadinessSeen.set(currentReadiness);
		return currentReadiness;
	}

	/**
	 * Returns the observability manager from the external API server.
	 * @param externalApiServer the external API server
	 * @return the observability manager or NULL if it is not available
	 */
	@Nonnull
	private Optional<ObservabilityManager> getObservabilityManager(@Nonnull ExternalApiServer externalApiServer) {
		if (this.observabilityManager == null) {
			final Optional<ObservabilityProvider> apiProvider = Optional.ofNullable(
				externalApiServer.getExternalApiProviderByCode(ObservabilityProvider.CODE)
			);
			this.observabilityManager = apiProvider
				.map(ObservabilityProvider::getObservabilityManager)
				.orElse(null);
		}
		return Optional.ofNullable(this.observabilityManager);
	}

	/**
	 * This record represents the result of a health problem check.
	 * @param healthProblem type of health problem
	 * @param present true if the health problem is present, false otherwise
	 */
	private record HealthProblemCheckResult(
		@Nullable HealthProblem healthProblem,
		@Nonnull String healthProblemName,
		boolean present
	) {

		public HealthProblemCheckResult(@Nullable HealthProblem healthProblem, boolean present) {
			this(healthProblem, healthProblem.name(), present);
		}

		public HealthProblemCheckResult(@Nullable String healthProblem, boolean present) {
			this(null, healthProblem, present);
		}

	}

}
