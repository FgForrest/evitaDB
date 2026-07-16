/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.externalApi.api.catalog.schemaApi.model;

import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.mutation.conflict.GranularConflictPolicy;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor representing the transaction {@link ConflictResolution} declared at catalog or entity level.
 * It is used to represent both input in mutations and output in schemas.
 *
 * Note: this descriptor has static structure.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface ConflictResolutionDescriptor {

	PropertyDescriptor POLICY = PropertyDescriptor.builder()
		.name("policy")
		.description("""
			The coarse, mutually exclusive scope at which transaction conflicts are detected.

			- NONE: conflicts are never detected
			- CATALOG: conflicts are detected at the level of the whole catalog
			- COLLECTION: conflicts are detected at the level of a single entity collection
			- ENTITY: conflicts are detected at the level of a single entity (allowing sub-entity granularity)
			""")
		.type(nonNull(ConflictPolicy.class))
		.build();

	PropertyDescriptor GRANULARITY = PropertyDescriptor.builder()
		.name("granularity")
		.description("""
			The set of sub-entity refinements that further narrow the scope of conflict detection. A non-empty
			granularity is legal only when the coarse `policy` is `ENTITY`; otherwise it must be empty.
			""")
		.type(nonNull(GranularConflictPolicy[].class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ConflictResolution")
		.description("""
			Represents the transaction conflict resolution combining the coarse conflict policy and its optional
			sub-entity granularity.
			""")
		.staticProperties(List.of(POLICY, GRANULARITY))
		.build();

	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("InputConflictResolution")
		.build();
}
