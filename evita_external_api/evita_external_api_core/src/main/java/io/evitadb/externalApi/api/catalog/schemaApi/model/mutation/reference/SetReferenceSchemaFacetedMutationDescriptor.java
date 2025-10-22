/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaFacetedMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor representing {@link SetReferenceSchemaFacetedMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface SetReferenceSchemaFacetedMutationDescriptor extends ReferenceSchemaMutationDescriptor {

	PropertyDescriptor FACETED_IN_SCOPES = PropertyDescriptor.builder()
		.name("facetedInScopes")
		.description("""
			Whether the statistics data for this reference should be maintained and this allowing to get
			`facetSummary` for this reference or use `facet_{reference name}_inSet`
			filtering query.
			
			Do not mark reference as faceted unless you want it among `FacetStatistics`. Each faceted reference
			occupies (memory/disk) space in the form of index.
			Reference that was marked as faceted is called Facet.
			
			This array defines in which scopes the reference will be faceted. It will not be faceted in not-specified scopes.
			""")
		.type(nullable(Scope[].class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("SetReferenceSchemaFacetedMutation")
		.description("""
			Mutation is responsible for setting value to a `ReferenceSchema.faceted`
			in `EntitySchema`.
			Mutation can be used for altering also the existing `ReferenceSchema` alone.
			""")
		.staticProperties(List.of(MUTATION_TYPE, NAME, FACETED_IN_SCOPES))
		.build();
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("SetReferenceSchemaFacetedMutationInput")
		.staticProperties(List.of(NAME, FACETED_IN_SCOPES))
		.build();
}
