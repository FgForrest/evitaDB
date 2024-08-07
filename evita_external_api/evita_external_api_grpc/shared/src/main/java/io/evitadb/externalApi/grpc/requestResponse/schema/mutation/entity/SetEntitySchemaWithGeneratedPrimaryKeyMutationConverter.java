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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.entity;

import io.evitadb.api.requestResponse.schema.mutation.entity.SetEntitySchemaWithGeneratedPrimaryKeyMutation;
import io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithGeneratedPrimaryKeyMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Converts between {@link SetEntitySchemaWithGeneratedPrimaryKeyMutation} and {@link GrpcSetEntitySchemaWithGeneratedPrimaryKeyMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SetEntitySchemaWithGeneratedPrimaryKeyMutationConverter implements SchemaMutationConverter<SetEntitySchemaWithGeneratedPrimaryKeyMutation, GrpcSetEntitySchemaWithGeneratedPrimaryKeyMutation> {
	public static final SetEntitySchemaWithGeneratedPrimaryKeyMutationConverter INSTANCE = new SetEntitySchemaWithGeneratedPrimaryKeyMutationConverter();

	@Nonnull
	public SetEntitySchemaWithGeneratedPrimaryKeyMutation convert(@Nonnull GrpcSetEntitySchemaWithGeneratedPrimaryKeyMutation mutation) {
		return new SetEntitySchemaWithGeneratedPrimaryKeyMutation(
			mutation.getWithGeneratedPrimaryKey()
		);
	}

	@Nonnull
	public GrpcSetEntitySchemaWithGeneratedPrimaryKeyMutation convert(@Nonnull SetEntitySchemaWithGeneratedPrimaryKeyMutation mutation) {
		return GrpcSetEntitySchemaWithGeneratedPrimaryKeyMutation.newBuilder()
			.setWithGeneratedPrimaryKey(mutation.isWithGeneratedPrimaryKey())
			.build();
	}
}
