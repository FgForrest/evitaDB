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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.catalog;

import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaConflictResolutionMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ConflictResolutionDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;

import static io.evitadb.externalApi.api.model.TypePropertyDataTypeDescriptor.nullableRef;

/**
 * Descriptor representing {@link ModifyCatalogSchemaConflictResolutionMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface ModifyCatalogSchemaConflictResolutionMutationDescriptor extends MutationDescriptor {

	PropertyDescriptor CONFLICT_RESOLUTION = PropertyDescriptor.builder()
		.name("conflictResolution")
		.description("""
			The transaction conflict resolution declared at the catalog level. A `null` value clears the catalog-level
			override so the catalog inherits the engine-level default; a non-null value overrides that default for the
			whole catalog.
			""")
		.type(nullableRef(ConflictResolutionDescriptor.THIS))
		.build();
	PropertyDescriptor CONFLICT_RESOLUTION_INPUT = PropertyDescriptor.from(CONFLICT_RESOLUTION)
		.type(nullableRef(ConflictResolutionDescriptor.THIS_INPUT))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.implementing(THIS_INTERFACE)
		.representedClass(ModifyCatalogSchemaConflictResolutionMutation.class)
		.description("""
			Mutation is responsible for setting value to a `CatalogSchema.conflictResolution`
			in `CatalogSchema`.
			""")
		.staticProperty(CONFLICT_RESOLUTION)
		.build();
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS, INPUT_OBJECT_PROPERTIES_FILTER)
		.name("ModifyCatalogSchemaConflictResolutionMutationInput")
		.staticProperty(CONFLICT_RESOLUTION_INPUT)
		.build();
}
