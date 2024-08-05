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

import com.google.protobuf.Int32Value;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.externalApi.grpc.generated.GrpcEntityUpsertMutation;
import io.evitadb.externalApi.grpc.generated.GrpcLocalMutation;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Converts between {@link EntityUpsertMutation} and {@link GrpcEntityUpsertMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class EntityUpsertMutationConverter implements EntityMutationConverter<EntityUpsertMutation, GrpcEntityUpsertMutation> {
	public static final EntityUpsertMutationConverter INSTANCE = new EntityUpsertMutationConverter();

	@Nonnull
	@Override
	public GrpcEntityUpsertMutation convert(@Nonnull EntityUpsertMutation mutation) {
		final List<GrpcLocalMutation> grpcLocalMutations = mutation.getLocalMutations()
			.stream()
			.map(DelegatingLocalMutationConverter.INSTANCE::convert)
			.toList();

		final GrpcEntityUpsertMutation.Builder builder = GrpcEntityUpsertMutation.newBuilder()
			.setEntityType(mutation.getEntityType())
			.setEntityExistence(EvitaEnumConverter.toGrpcEntityExistence(mutation.expects()))
			.addAllMutations(grpcLocalMutations);

		if (mutation.getEntityPrimaryKey() != null) {
			builder.setEntityPrimaryKey(Int32Value.of(mutation.getEntityPrimaryKey()));
		}

		return builder.build();
	}

	@Nonnull
	@Override
	public EntityUpsertMutation convert(@Nonnull GrpcEntityUpsertMutation mutation) {
		return new EntityUpsertMutation(
			mutation.getEntityType(),
			mutation.hasEntityPrimaryKey() ? mutation.getEntityPrimaryKey().getValue() : null,
			EvitaEnumConverter.toEntityExistence(mutation.getEntityExistence()),
			mutation.getMutationsList()
				.stream()
				.map(DelegatingLocalMutationConverter.INSTANCE::convert)
				.toList()
		);
	}
}
