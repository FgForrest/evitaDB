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

package io.evitadb.externalApi.api.system.model.mutation.engine;

import io.evitadb.externalApi.api.model.UnionDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import io.evitadb.externalApi.api.transaction.model.mutation.TransactionMutationDescriptor;

/**
 * Union descriptor for engine mutation descriptors. Represents the {@link io.evitadb.api.requestResponse.mutation.EngineMutation} interface.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface EngineMutationUnionDescriptor {

	UnionDescriptor THIS = UnionDescriptor.builder()
		.name("EngineMutationUnion")
		.description("Lists all possible types of engine mutations.")
		.discriminator(MutationDescriptor.MUTATION_TYPE)

		.type(CreateCatalogSchemaMutationDescriptor.THIS)
		.type(DuplicateCatalogMutationDescriptor.THIS)
		.type(MakeCatalogAliveMutationDescriptor.THIS)
		.type(ModifyCatalogSchemaMutationDescriptor.THIS)
		.type(ModifyCatalogSchemaNameMutationDescriptor.THIS)
		.type(RemoveCatalogSchemaMutationDescriptor.THIS)
		.type(RestoreCatalogSchemaMutationDescriptor.THIS)
		.type(SetCatalogMutabilityMutationDescriptor.THIS)
		.type(SetCatalogStateMutationDescriptor.THIS)

		.type(TransactionMutationDescriptor.THIS)

		.build();
}
