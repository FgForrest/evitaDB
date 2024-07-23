/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.api.catalog.dataApi.model.extraResult;

import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nonNullListRef;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Represents {@link io.evitadb.api.requestResponse.extraResult.QueryTelemetry}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface QueryTelemetryDescriptor {

	PropertyDescriptor OPERATION = PropertyDescriptor.builder()
		.name("operation")
		.description("""
			Phase of the query processing.
			""")
		.type(nonNull(QueryPhase.class))
		.build();
	PropertyDescriptor START = PropertyDescriptor.builder()
		.name("start")
		.description("""
            Date and time of the start of this step in nanoseconds.
			""")
		.type(nonNull(Long.class))
		.build();
	PropertyDescriptor STEPS = PropertyDescriptor.builder()
		.name("steps")
		.description("""
			Internal steps of this telemetry step (operation decomposition).
			""")
		.type(nonNullListRef(() -> QueryTelemetryDescriptor.THIS))
		.build();
	PropertyDescriptor ARGUMENTS = PropertyDescriptor.builder()
		.name("arguments")
		.description("""
			Arguments of the processing phase.
			""")
		.type(nonNull(String[].class))
		.build();
	PropertyDescriptor SPENT_TIME = PropertyDescriptor.builder()
		.name("spentTime")
		.description("""
			Duration in nanoseconds.
			""")
		.type(nonNull(String.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("QueryTelemetry")
		.description("""
			This DTO contains detailed information about query processing time and its decomposition to single operations.
			""")
		.staticFields(List.of(OPERATION, START, STEPS, ARGUMENTS, SPENT_TIME))
		.build();
}
