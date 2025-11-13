/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.model.mutation;

import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.EntityRemoveMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.EntityUpsertMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.LocalMutationUnionDescriptor;
import io.evitadb.externalApi.api.model.UnionDescriptor;

/**
 * Union representing all data mutations for catalog API.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface CatalogDataMutationUnionDescriptor {

	UnionDescriptor THIS = UnionDescriptor.builder()
		.name("CatalogDataMutationUnion")
		.description("Union of all catalog data mutation types.")

		.type(EntityUpsertMutationDescriptor.THIS)
		.type(EntityRemoveMutationDescriptor.THIS)

		.typesFrom(LocalMutationUnionDescriptor.THIS)

		.build();
}
