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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.reference;

import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaConflictResolutionOverrideMutation;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor representing {@link SetReferenceSchemaConflictResolutionOverrideMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface SetReferenceSchemaConflictResolutionOverrideMutationDescriptor extends ReferenceSchemaMutationDescriptor {

	PropertyDescriptor CONFLICT_RESOLUTION_OVERRIDE = PropertyDescriptor.builder()
		.name("conflictResolutionOverride")
		.description("""
			Determines the granularity at which transaction conflicts are detected for this reference. When set to
			`INHERITED` the reference follows the conflict resolution resolved for the entity, otherwise it overrides
			the resolved granularity for this reference alone.
			""")
		.type(nonNull(ConflictResolutionOverride.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.implementing(THIS_INTERFACE)
		.representedClass(SetReferenceSchemaConflictResolutionOverrideMutation.class)
		.description("""
			Mutation is responsible for setting value to a `ReferenceSchema.conflictResolutionOverride`
			in `EntitySchema`.
			Mutation can be used for altering also the existing `ReferenceSchema` alone.
			""")
		.staticProperty(NAME)
		.staticProperty(CONFLICT_RESOLUTION_OVERRIDE)
		.build();
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS, INPUT_OBJECT_PROPERTIES_FILTER)
		.name("SetReferenceSchemaConflictResolutionOverrideMutationInput")
		.build();
}
