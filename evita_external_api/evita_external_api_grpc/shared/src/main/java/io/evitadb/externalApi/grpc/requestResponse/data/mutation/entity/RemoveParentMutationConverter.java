/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.grpc.requestResponse.data.mutation.entity;

import io.evitadb.api.requestResponse.data.mutation.parent.RemoveParentMutation;
import io.evitadb.externalApi.grpc.generated.GrpcRemoveParentMutation;
import io.evitadb.externalApi.grpc.requestResponse.data.mutation.LocalMutationConverter;

import javax.annotation.Nonnull;

/**
 * Converts between {@link RemoveParentMutation} and {@link GrpcRemoveParentMutation} in both directions.
 *
 * @author Tomáš Pozler, 2022
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class RemoveParentMutationConverter implements LocalMutationConverter<RemoveParentMutation, GrpcRemoveParentMutation> {

	@Override
	@Nonnull
	public RemoveParentMutation convert(@Nonnull GrpcRemoveParentMutation mutation) {
		return new RemoveParentMutation();
	}

	@Override
	@Nonnull
	public GrpcRemoveParentMutation convert(@Nonnull RemoveParentMutation mutation) {
		return GrpcRemoveParentMutation.newBuilder().build();
	}
}
