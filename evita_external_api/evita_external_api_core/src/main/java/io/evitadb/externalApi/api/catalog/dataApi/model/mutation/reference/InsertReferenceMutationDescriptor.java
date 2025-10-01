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

package io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference;

import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor representing {@link InsertReferenceMutation}
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface InsertReferenceMutationDescriptor extends ReferenceMutationDescriptor {

	PropertyDescriptor CARDINALITY = PropertyDescriptor.builder()
		.name("cardinality")
		.description("""
			 Contains information about reference cardinality. This value is usually NULL except the case when the reference
			 is created for the first time and `EvolutionMode.ADDING_REFERENCES` is allowed.
			""")
		.type(nullable(Cardinality.class))
		.build();
	PropertyDescriptor REFERENCED_ENTITY_TYPE = PropertyDescriptor.builder()
		.name("referencedEntityType")
		.description("""
			Contains information about target entity type. This value is usually NULL except the case when the reference
			is created for the first time and `EvolutionMode.ADDING_REFERENCES` is allowed.
			""")
		.type(nullable(String.class))
		.build();


	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("InsertReferenceMutation")
		.description("""
			This mutation allows to create a reference in the entity.
			""")
		.staticFields(List.of(
			MUTATION_TYPE,
			NAME,
			PRIMARY_KEY,
			CARDINALITY,
			REFERENCED_ENTITY_TYPE
		))
		.build();
}
