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

package io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference;

import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor representing {@link SetReferenceGroupMutation}
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface SetReferenceGroupMutationDescriptor extends ReferenceMutationDescriptor {

	PropertyDescriptor GROUP_TYPE = PropertyDescriptor.builder()
		.name("groupType")
		.description("""
	        Type of the referenced entity representing group. Might be also any `String`
	        that identifies type in some external resource not maintained by Evita.
			""")
		.type(nullable(String.class))
		.build();
	PropertyDescriptor GROUP_PRIMARY_KEY = PropertyDescriptor.builder()
		.name("groupPrimaryKey")
		.description("""
			Primary key of the referenced entity representing group. Might be also any integer
			that uniquely identifies some external resource not maintained by Evita.
			""")
		.type(nonNull(Integer.class))
		.build();


	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("SetReferenceGroupMutation")
		.description("""
			This mutation allows to create / update group of the reference.
			""")
		.staticFields(List.of(
			NAME,
			PRIMARY_KEY,
			GROUP_TYPE,
			GROUP_PRIMARY_KEY
		))
		.build();
}
