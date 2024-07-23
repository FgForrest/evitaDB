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

package io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

/**
 * Descriptor of aggregate object containing all implementations of {@link io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation}
 * aplicable to {@link io.evitadb.api.requestResponse.data.ReferenceContract} for schema-based external APIs.
 *
 * Note: this descriptor has static structure
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public interface ReferenceAttributeMutationAggregateDescriptor {

	PropertyDescriptor APPLY_DELTA_ATTRIBUTE_MUTATION = PropertyDescriptor.nullableFromObject(ApplyDeltaAttributeMutationDescriptor.THIS);
	PropertyDescriptor REMOVE_ATTRIBUTE_MUTATION = PropertyDescriptor.nullableFromObject(RemoveAttributeMutationDescriptor.THIS);
	PropertyDescriptor UPSERT_ATTRIBUTE_MUTATION = PropertyDescriptor.nullableFromObject(UpsertAttributeMutationDescriptor.THIS);


	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ReferenceAttributeMutationAggregate")
		.description("""
				Contains all possible attribute mutations for references.
				""")
		.staticFields(List.of(
			APPLY_DELTA_ATTRIBUTE_MUTATION,
			REMOVE_ATTRIBUTE_MUTATION,
			UPSERT_ATTRIBUTE_MUTATION
		))
		.build();
}
