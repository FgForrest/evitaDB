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

package io.evitadb.externalApi.api.catalog.dataApi.model.mutation.associatedData;

import io.evitadb.api.requestResponse.data.mutation.associatedData.RemoveAssociatedDataMutation;
import io.evitadb.externalApi.api.model.ObjectDescriptor;

import java.util.List;

/**
 * Descriptor representing {@link RemoveAssociatedDataMutation}.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public interface RemoveAssociatedDataMutationDescriptor extends AssociatedDataMutationDescriptor {

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("RemoveAssociatedDataMutation")
		.description("""
			Remove associated data mutation will drop existing associatedData - ie.generates new version of the associated data with tombstone
			on it.
			""")
		.staticProperties(List.of(MUTATION_TYPE, NAME, LOCALE))
		.build();
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("RemoveAssociatedDataMutationInput")
		.staticProperties(List.of(NAME, LOCALE))
		.build();
}
