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
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
	private long lastSeenRejectedTaskCount;
	private long lastSeenSubmittedTaskCount;
	private long lastSeenJavaErrorCount;
	private long lastSeenJavaOOMErrorCount;
	private long lastSeenEvitaErrorCount;
	private long lastSeenJavaGarbageCollections;
	private boolean seenReady;

	@Nonnull
	@Override
	public Set<HealthProblem> getHealthProblems(@Nonnull EvitaContract evitaContract, @Nonnull ExternalApiServer externalApiServer) {
		final EnumSet<HealthProblem> healthProblems = EnumSet.noneOf(HealthProblem.class);
		final Optional<ObservabilityManager> theObservabilityManager = getObservabilityManager(externalApiServer);
		if (evitaContract instanceof Evita evita) {
			final EnhancedQueueExecutor executor = evita.getExecutor();
			final long rejectedTaskCount = executor.getRejectedTaskCount();
			final long submittedTaskCount = executor.getSubmittedTaskCount();
			// if the ratio of rejected task to submitted tasks is greater than 2, we could consider queues as overloaded
			if ((rejectedTaskCount - lastSeenRejectedTaskCount) / (Math.max(submittedTaskCount - lastSeenSubmittedTaskCount, 1)) > 2) {
				healthProblems.add(HealthProblem.INPUT_QUEUES_OVERLOADED);
				theObservabilityManager.ifPresent(it -> it.recordHealthProblem(HealthProblem.INPUT_QUEUES_OVERLOADED));
			} else {
				theObservabilityManager.ifPresent(it -> it.clearHealthProblem(HealthProblem.INPUT_QUEUES_OVERLOADED));
			}
			this.lastSeenRejectedTaskCount = rejectedTaskCount;
			this.lastSeenSubmittedTaskCount = submittedTaskCount;
		}

		// if the number of errors has increased since the last check, we could consider the system as unhealthy
		final long javaErrorCount = theObservabilityManager
			.map(ObservabilityManager::getJavaErrorCount)
			.orElse(0L);
		if (javaErrorCount > this.lastSeenJavaErrorCount) {
			healthProblems.add(HealthProblem.JAVA_INTERNAL_ERRORS);
			theObservabilityManager.ifPresent(it -> it.recordHealthProblem(HealthProblem.JAVA_INTERNAL_ERRORS));
		} else {
			theObservabilityManager.ifPresent(it -> it.clearHealthProblem(HealthProblem.JAVA_INTERNAL_ERRORS));
		}
		this.lastSeenJavaErrorCount = javaErrorCount;

		// if the number of errors has increased since the last check, we could consider the system as unhealthy
		final long evitaErrorCount = theObservabilityManager
			.map(ObservabilityManager::getEvitaErrorCount)
			.orElse(0L);
		if (evitaErrorCount > this.lastSeenEvitaErrorCount) {
			healthProblems.add(HealthProblem.EVITA_DB_INTERNAL_ERRORS);
			theObservabilityManager.ifPresent(it -> it.recordHealthProblem(HealthProblem.EVITA_DB_INTERNAL_ERRORS));
		} else {
			theObservabilityManager.ifPresent(it -> it.clearHealthProblem(HealthProblem.EVITA_DB_INTERNAL_ERRORS));
		}
		this.lastSeenEvitaErrorCount = evitaErrorCount;

		// if the number of errors has increased since the last check, we could consider the system as unhealthy
		final long javaOOMErrorCount = theObservabilityManager
			.map(ObservabilityManager::getJavaOutOfMemoryErrorCount)
			.orElse(0L);
		// get used memory of the JVM
		final float usedMemory = 1.0f - ((float) runtime.freeMemory() / (float) runtime.maxMemory());
		final long oldGenerationCollectionCount = garbageCollectorMXBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
		if (usedMemory > 0.9f && (javaOOMErrorCount > this.lastSeenJavaOOMErrorCount || oldGenerationCollectionCount > this.lastSeenJavaGarbageCollections)) {
			healthProblems.add(HealthProblem.MEMORY_SHORTAGE);
			theObservabilityManager.ifPresent(it -> it.recordHealthProblem(HealthProblem.MEMORY_SHORTAGE));
		} else {
			theObservabilityManager.ifPresent(it -> it.clearHealthProblem(HealthProblem.MEMORY_SHORTAGE));
		}
		this.lastSeenJavaOOMErrorCount = javaOOMErrorCount;
		this.lastSeenJavaGarbageCollections = oldGenerationCollectionCount;

		return healthProblems.isEmpty() ? NO_HEALTH_PROBLEMS : healthProblems;
	}

	@Nonnull
	@Override
	public Readiness getReadiness(@Nonnull EvitaContract evitaContract, @Nonnull ExternalApiServer externalApiServer, @Nonnull String... apiCodes) {
		final Optional<ObservabilityManager> theObservabilityManager = getObservabilityManager(externalApiServer);
		// check the end-points availability
		final Collection<ExternalApiProviderRegistrar> availableExternalApis = ExternalApiServer.gatherExternalApiProviders();
		final Map<String, Boolean> readiness = CollectionUtils.createHashMap(availableExternalApis.size());
		for (String apiCode : apiCodes) {
			final ExternalApiProvider<?> apiProvider = externalApiServer.getExternalApiProviderByCode(apiCode);
			readiness.put(apiProvider.getCode(), apiProvider.isReady());
			theObservabilityManager.ifPresent(it -> it.recordReadiness(apiProvider.getCode(), apiProvider.isReady()));
		}
		final boolean ready = readiness.values().stream().allMatch(Boolean::booleanValue);
		if (ready) {
			this.seenReady = true;
		}
		return new Readiness(
			ready ? ReadinessState.READY : (this.seenReady ? ReadinessState.STALLING : ReadinessState.STARTING),
			readiness.entrySet().stream()
				.map(entry -> new ApiState(entry.getKey(), entry.getValue()))
				.toArray(ApiState[]::new)
		);
	}

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


}
