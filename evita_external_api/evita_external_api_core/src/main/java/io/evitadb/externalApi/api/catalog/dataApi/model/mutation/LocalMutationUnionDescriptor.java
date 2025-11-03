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

package io.evitadb.externalApi.api.catalog.dataApi.model.mutation;

import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.associatedData.*;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.*;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.entity.*;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price.*;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.*;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.RemoveReferenceMutationDescriptor;
import io.evitadb.externalApi.api.model.UnionDescriptor;
import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;

/**
 * Union descriptor for all possible types of mutations for entity data modification.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface LocalMutationUnionDescriptor {

	UnionDescriptor THIS = UnionDescriptor.builder()
		.name("LocalMutationUnion")
		.description("Lists all possible types of mutations for entity data modification.")
		.discriminator(MutationDescriptor.MUTATION_TYPE)
		.type(RemoveAssociatedDataMutationDescriptor.THIS)
		.type(UpsertAssociatedDataMutationDescriptor.THIS)
		.type(ApplyDeltaAttributeMutationDescriptor.THIS)
		.type(UpsertAttributeMutationDescriptor.THIS)
		.type(RemoveAttributeMutationDescriptor.THIS)
		.type(RemoveParentMutationDescriptor.THIS)
		.type(SetParentMutationDescriptor.THIS)
		.type(SetEntityScopeMutationDescriptor.THIS)
		.type(SetPriceInnerRecordHandlingMutationDescriptor.THIS)
		.type(RemovePriceMutationDescriptor.THIS)
		.type(UpsertPriceMutationDescriptor.THIS)
		.type(InsertReferenceMutationDescriptor.THIS)
		.type(RemoveReferenceMutationDescriptor.THIS)
		.type(SetReferenceGroupMutationDescriptor.THIS)
		.type(RemoveReferenceGroupMutationDescriptor.THIS)
		.type(ReferenceAttributeMutationDescriptor.THIS)
		.build();
}
