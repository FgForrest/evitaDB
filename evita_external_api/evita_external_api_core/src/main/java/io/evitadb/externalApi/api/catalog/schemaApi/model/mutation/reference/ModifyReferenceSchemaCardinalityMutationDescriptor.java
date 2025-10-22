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

import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor representing {@link io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaCardinalityMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface ModifyReferenceSchemaCardinalityMutationDescriptor extends ReferenceSchemaMutationDescriptor {

	PropertyDescriptor CARDINALITY = PropertyDescriptor.builder()
		.name("cardinality")
		.description("""
			Cardinality describes the expected count of relations of this type. In evitaDB we define only one-way
			relationship from the perspective of the entity. We stick to the ERD modelling
			[standards](https://www.gleek.io/blog/crows-foot-notation.html) here. Cardinality affect the design
			of the client API (returning only single reference or collections) and also help us to protect the consistency
			of the data so that conforms to the creator mental model.
			""")
		.type(nonNull(Cardinality.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ModifyReferenceSchemaCardinalityMutation")
		.description("""
			Mutation is responsible for setting value to a `ReferenceSchema.cardinality`
			in `EntitySchema`.
			""")
		.staticProperties(List.of(MUTATION_TYPE, NAME, CARDINALITY))
		.build();
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("ModifyReferenceSchemaCardinalityMutationInput")
		.staticProperties(List.of(NAME, CARDINALITY))
		.build();
}
