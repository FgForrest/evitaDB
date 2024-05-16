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

import io.evitadb.core.metric.annotation.EventGroup;
import io.evitadb.core.metric.event.CustomMetricsExecutionEvent;
import io.evitadb.core.metric.event.storage.*;
import io.evitadb.core.metric.event.transaction.*;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ReflectionLookup;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * This class is used as a provider of custom metrics events. It provides a set of all registered custom metrics events
 * and a map of all registered custom metrics events by their package name.
 *
 * All package names containing custom metrics events must be registered here in {@link #EVENTS_TYPES}.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
@NoArgsConstructor
public class EvitaJfrEventRegistry {
	private static final Set<Class<? extends CustomMetricsExecutionEvent>> EVENTS_TYPES = Set.of(
		// transaction events
		TransactionStartedEvent.class,
		TransactionFinishedEvent.class,
		TransactionAcceptedEvent.class,
		TransactionAppendedToWalEvent.class,
		TransactionIncorporatedToTrunkEvent.class,
		TransactionProcessedEvent.class,
		TransactionQueuedEvent.class,
		NewCatalogVersionPropagatedEvent.class,
		WalRotationEvent.class,
		WalCacheSizeChangedEvent.class,
		IsolatedWalFileOpenedEvent.class,
		IsolatedWalFileClosedEvent.class,
		OffHeapMemoryAllocationChangeEvent.class,

		// storage events
		OffsetIndexFlushEvent.class,
		DataFileCompactEvent.class,
		OffsetIndexRecordTypeCountChangedEvent.class,
		OffsetIndexNonFlushedRecordsEvent.class,
		ObservableOutputChangeEvent.class,
		ReadOnlyHandleLimitSetEvent.class,
		ReadOnlyHandleOpenedEvent.class,
		ReadOnlyHandleClosedEvent.class,
		CatalogFlushEvent.class,
		EvitaDBCompositionChangedEvent.class
	);
	private static final Map<String, Class<? extends CustomMetricsExecutionEvent>> EVENT_MAP;
	private static final Map<String, Set<Class<? extends CustomMetricsExecutionEvent>>> EVENT_MAP_BY_PACKAGE;

	static {
		EVENT_MAP = EVENTS_TYPES
			.stream()
			.collect(Collectors.toMap(Class::getName, Function.identity()));
		EVENT_MAP_BY_PACKAGE = EVENTS_TYPES
			.stream()
			.collect(
				Collectors.groupingBy(
					EvitaJfrEventRegistry::getMetricsGroup,
					Collectors.toSet()
				)
			);
	}

	/**
	 * Gets the group name of the specified custom metrics event class.
	 * @param eventClass the custom metrics event class
	 * @return the group name of the specified custom metrics event class
	 */
	@Nonnull
	public static String getMetricsGroup(@Nonnull Class<? extends CustomMetricsExecutionEvent> eventClass) {
		final EventGroup group = ReflectionLookup.NO_CACHE_INSTANCE.getClassAnnotation(eventClass, EventGroup.class);
		Assert.isPremiseValid(
			group != null,
			"Custom metrics event class must be annotated with @EventGroup annotation that defines the event group assignment."
		);
		return group.value();
	}

	/**
	 * Gets {@link Class} for specified custom event class name.
	 */
	@Nullable
	public static Class<? extends CustomMetricsExecutionEvent> getEventClass(@Nonnull String eventClassName) {
		return EVENT_MAP.get(eventClassName);
	}

	/**
	 * Gets a set of {@link Class}es located in a specified package.
	 */
	@Nullable
	public static Set<Class<? extends CustomMetricsExecutionEvent>> getEventClassesFromPackage(@Nonnull String eventPackageWithWildcard) {
		return EVENT_MAP_BY_PACKAGE.get(eventPackageWithWildcard);
	}

	/**
	 * Returns a set of all registered classes fetched from the registry.
	 */
	@Nonnull
	public static Set<Class<? extends CustomMetricsExecutionEvent>> getEventClasses() {
		return EVENTS_TYPES;
	}
}
