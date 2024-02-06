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

package io.evitadb.externalApi.observability.metric.provider;

import io.evitadb.core.metric.event.CustomMetricsExecutionEvent;
import io.evitadb.core.metric.event.QueryPlanStepExecutedEvent;
import io.evitadb.core.metric.event.TestEvent;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * This class is used as a provider of registered custom metrics events.
 *
 * All registered custom metrics events must be registered here.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class RegisteredCustomEventProvider {
	static final Set<Class<? extends CustomMetricsExecutionEvent>> REGISTERED_EVENTS = Set.of(
		QueryPlanStepExecutedEvent.class,
		TestEvent.class
	);
}
