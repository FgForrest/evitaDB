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

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.externalApi.api.model.PropertyDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Abstract descriptor for all {@link ReferenceMutation}s.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface ReferenceMutationDescriptor extends MutationDescriptor {

	PropertyDescriptor NAME = PropertyDescriptor.builder()
		.name("name")
		.description("""
			Unique identifier of the reference.
			""")
		.type(nonNull(String.class))
		.build();
	PropertyDescriptor PRIMARY_KEY = PropertyDescriptor.builder()
		.name("primaryKey")
		.description("""
			Primary key of the referenced entity. Might be also any integer
			that uniquely identifies some external resource not maintained by Evita.
			""")
		.type(nonNull(Integer.class))
		.build();
}
