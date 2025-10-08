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

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.ReferenceAttributeMutationAggregateDescriptor;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.ObjectPropertyDataTypeDescriptor.nonNullRef;

/**
 * Descriptor representing {@link ReferenceAttributeMutation}
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface ReferenceAttributeMutationDescriptor extends ReferenceMutationDescriptor {

	// todo lho input version
	PropertyDescriptor ATTRIBUTE_MUTATION = PropertyDescriptor.builder()
		.name("attributeMutation")
		.description("""
			One attribute mutation to update / insert / delete single attribute of the reference.
			""")
		.type(nonNullRef(ReferenceAttributeMutationAggregateDescriptor.THIS))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ReferenceAttributeMutation")
		.description("""
			This mutation allows to create / update / remove attribute of the reference.
			""")
		.staticFields(List.of(MUTATION_TYPE, NAME, PRIMARY_KEY, ATTRIBUTE_MUTATION))
		.build();
}
