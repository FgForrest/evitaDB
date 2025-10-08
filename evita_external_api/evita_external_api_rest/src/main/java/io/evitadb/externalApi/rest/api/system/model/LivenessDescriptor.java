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

package io.evitadb.externalApi.rest.api.system.model;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor for response body of {@link SystemRootDescriptor#LIVENESS}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface LivenessDescriptor {

	String LIVENESS_SUFFIX = "liveness";

	PropertyDescriptor LIVENESS = PropertyDescriptor.builder()
		.name(LIVENESS_SUFFIX)
		.description("""
			Whether REST API is alive and can handle requests.
			""")
		.type(nonNull(Boolean.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("Liveness")
		.description("""
			Holds information about liveness of REST API.
			""")
		.staticFields(List.of(LIVENESS))
		.build();
}
