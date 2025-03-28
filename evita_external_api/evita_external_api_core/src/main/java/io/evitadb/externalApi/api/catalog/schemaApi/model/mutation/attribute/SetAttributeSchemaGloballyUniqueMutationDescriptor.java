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

package io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute;

import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaGloballyUniqueMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.model.ScopedGlobalAttributeUniquenessTypeDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nullableListRef;

/**
 * Descriptor representing {@link SetAttributeSchemaGloballyUniqueMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface SetAttributeSchemaGloballyUniqueMutationDescriptor extends AttributeSchemaMutationDescriptor {

	PropertyDescriptor UNIQUE_GLOBALLY_IN_SCOPES = PropertyDescriptor.builder()
		.name("uniqueGloballyInScopes")
		.description("""			
			Encapsulates the relationship between an attribute's
			uniqueness type and the scope in which this uniqueness characteristic is enforced.
			
			It makes use of two parameters:
			- scope: Defines the context or domain (live or archived) where the attribute resides.
			- uniquenessType: Determines the uniqueness enforcement (e.g., unique within the entire catalog or specific locale).
			
			The combination of these parameters allows for scoped uniqueness checks within attribute schemas,
			providing fine-grained control over attribute constraints based on the entity's scope.
			""")
		.type(nullableListRef(ScopedGlobalAttributeUniquenessTypeDescriptor.THIS_INPUT))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("SetAttributeSchemaGloballyUniqueMutation")
		.description("""
			Mutation is responsible for setting value to a `GlobalAttributeSchema.uniqueGlobally`
			in `EntitySchema`.
			Mutation can be used for altering also the existing `GlobalAttributeSchema` alone.
			""")
		.staticFields(List.of(NAME, UNIQUE_GLOBALLY_IN_SCOPES))
		.build();
}
