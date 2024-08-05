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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog;

import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveEntitySchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcRemoveEntitySchemaMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Converts between {@link RemoveEntitySchemaMutation} and {@link GrpcRemoveEntitySchemaMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class RemoveEntitySchemaMutationConverter implements SchemaMutationConverter<RemoveEntitySchemaMutation, GrpcRemoveEntitySchemaMutation> {
	public static final RemoveEntitySchemaMutationConverter INSTANCE = new RemoveEntitySchemaMutationConverter();

	@Nonnull
	public RemoveEntitySchemaMutation convert(@Nonnull GrpcRemoveEntitySchemaMutation mutation) {
		return new RemoveEntitySchemaMutation(mutation.getName());
	}

	@Nonnull
	public GrpcRemoveEntitySchemaMutation convert(@Nonnull RemoveEntitySchemaMutation mutation) {
		return GrpcRemoveEntitySchemaMutation.newBuilder()
			.setName(mutation.getName())
			.build();
	}
}
