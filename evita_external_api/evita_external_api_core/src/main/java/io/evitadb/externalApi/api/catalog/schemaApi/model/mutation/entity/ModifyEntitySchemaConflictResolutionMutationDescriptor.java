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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.entity;

import io.evitadb.api.requestResponse.schema.mutation.entity.ModifyEntitySchemaConflictResolutionMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ConflictResolutionDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nullableRef;

/**
 * Descriptor representing {@link ModifyEntitySchemaConflictResolutionMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface ModifyEntitySchemaConflictResolutionMutationDescriptor extends EntitySchemaMutationDescriptor {

	PropertyDescriptor CONFLICT_RESOLUTION = PropertyDescriptor.builder()
		.name("conflictResolution")
		.description("""
			The transaction conflict resolution declared at the entity level. A `null` value clears the entity-level
			override so the entity inherits the conflict resolution resolved for the catalog; a non-null value overrides
			that default for this entity collection.
			""")
		.type(nullableRef(ConflictResolutionDescriptor.THIS))
		.build();
	PropertyDescriptor CONFLICT_RESOLUTION_INPUT = PropertyDescriptor.from(CONFLICT_RESOLUTION)
		.type(nullableRef(ConflictResolutionDescriptor.THIS_INPUT))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.implementing(THIS_INTERFACE)
		.representedClass(ModifyEntitySchemaConflictResolutionMutation.class)
		.description("""
			Mutation is responsible for setting a `EntitySchema.conflictResolution`
			in `EntitySchema`.
			""")
		.staticProperty(CONFLICT_RESOLUTION)
		.build();
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS, INPUT_OBJECT_PROPERTIES_FILTER)
		.name("ModifyEntitySchemaConflictResolutionMutationInput")
		.staticProperty(CONFLICT_RESOLUTION_INPUT)
		.build();
}
