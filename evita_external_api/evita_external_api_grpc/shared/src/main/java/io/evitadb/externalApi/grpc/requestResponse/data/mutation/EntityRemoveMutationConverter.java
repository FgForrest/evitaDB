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

package io.evitadb.externalApi.grpc.requestResponse.data.mutation;

import io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation;
import io.evitadb.externalApi.grpc.generated.GrpcEntityRemoveMutation;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Converts between {@link EntityRemoveMutation} and {@link GrpcEntityRemoveMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class EntityRemoveMutationConverter implements EntityMutationConverter<EntityRemoveMutation, GrpcEntityRemoveMutation> {
	public static final EntityRemoveMutationConverter INSTANCE = new EntityRemoveMutationConverter();

	@Nonnull
	@Override
	public GrpcEntityRemoveMutation convert(@Nonnull EntityRemoveMutation mutation) {
		return GrpcEntityRemoveMutation.newBuilder()
			.setEntityType(mutation.getEntityType())
			.setEntityPrimaryKey(mutation.getEntityPrimaryKey())
			.build();
	}

	@Nonnull
	@Override
	public EntityRemoveMutation convert(@Nonnull GrpcEntityRemoveMutation mutation) {
		return new EntityRemoveMutation(
			mutation.getEntityType(),
			mutation.getEntityPrimaryKey()
		);
	}
}
