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

import io.evitadb.api.requestResponse.schema.mutation.entity.SetEntitySchemaWithPriceMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithPriceMutation;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * Converts between {@link SetEntitySchemaWithPriceMutation} and {@link GrpcSetEntitySchemaWithPriceMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SetEntitySchemaWithPriceMutationConverter implements SchemaMutationConverter<SetEntitySchemaWithPriceMutation, GrpcSetEntitySchemaWithPriceMutation> {
	public static final SetEntitySchemaWithPriceMutationConverter INSTANCE = new SetEntitySchemaWithPriceMutationConverter();

	@Nonnull
	public SetEntitySchemaWithPriceMutation convert(@Nonnull GrpcSetEntitySchemaWithPriceMutation mutation) {
		return new SetEntitySchemaWithPriceMutation(
			mutation.getWithPrice(),
			mutation.getIndexedInScopesList()
				.stream()
				.map(EvitaEnumConverter::toScope)
				.toArray(Scope[]::new),
			mutation.getIndexedPricePlaces()
		);
	}

	@Nonnull
	public GrpcSetEntitySchemaWithPriceMutation convert(@Nonnull SetEntitySchemaWithPriceMutation mutation) {
		return GrpcSetEntitySchemaWithPriceMutation.newBuilder()
			.setWithPrice(mutation.isWithPrice())
			.addAllIndexedInScopes(
				Arrays.stream(mutation.getIndexedInScopes())
					.map(EvitaEnumConverter::toGrpcScope)
					.toList()
			)
			.setIndexedPricePlaces(mutation.getIndexedPricePlaces())
			.build();
	}
}
