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

package io.evitadb.externalApi.observability.metric.provider;

import io.evitadb.core.metric.event.CustomMetricsExecutionEvent;
import io.evitadb.utils.CollectionUtils;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This class is used as a provider of custom metrics events. It provides a set of all registered custom metrics events
 * and a map of all registered custom metrics events by their package name.
 *
 * All package names containing custom metrics events must be registered here.
 *
 * @see RegisteredCustomEventProvider
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
@NoArgsConstructor
public class CustomEventProvider {
	private static final Set<Class<? extends CustomMetricsExecutionEvent>> CUSTOM_EVENTS;
	private static final Map<String, Class<? extends CustomMetricsExecutionEvent>> EVENT_MAP;
	private static final Map<String, Set<Class<? extends CustomMetricsExecutionEvent>>> EVENT_MAP_BY_PACKAGE;

	static {
		CUSTOM_EVENTS = RegisteredCustomEventProvider.REGISTERED_EVENTS;
		EVENT_MAP = CollectionUtils.createHashMap(CUSTOM_EVENTS.size());
		CUSTOM_EVENTS.forEach(e -> EVENT_MAP.put(e.getName(), e));

		final Set<String> eventPackageNames = Set.of(
			"io.evitadb.core.metric.event.*"
		);

		EVENT_MAP_BY_PACKAGE = CollectionUtils.createHashMap(eventPackageNames.size());

		eventPackageNames.forEach(p -> EVENT_MAP_BY_PACKAGE.put(p, EVENT_MAP.values()
			.stream()
			.filter(e -> e.getPackage().getName().equals(p.substring(0, p.length() - 2)))
			.collect(Collectors.toSet()))
		);
	}

	/**
	 * Gets {@link Class} for specified custom event class name.
	 */
	public static Class<? extends CustomMetricsExecutionEvent> getEventClass(String eventClassName) {
		return EVENT_MAP.get(eventClassName);
	}

	/**
	 * Gets a set of {@link Class}es located in a specified package.
	 */
	public static Set<Class<? extends CustomMetricsExecutionEvent>> getEventClassesFromPackage(String eventPackageWithWildcard) {
		return EVENT_MAP_BY_PACKAGE.get(eventPackageWithWildcard);
	}

	/**
	 * Returns a set of all registered classes fetched from the registry.
	 */
	public static Set<Class<? extends CustomMetricsExecutionEvent>> getEventClasses() {
		return Collections.unmodifiableSet(CUSTOM_EVENTS);
	}
}
