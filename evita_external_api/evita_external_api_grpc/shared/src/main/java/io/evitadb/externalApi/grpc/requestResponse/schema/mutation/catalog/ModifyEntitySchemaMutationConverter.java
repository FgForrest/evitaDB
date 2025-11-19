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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.catalog;

import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcEntitySchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcModifyEntitySchemaMutation;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.DelegatingEntitySchemaMutationConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;

/**
 * Converts between {@link ModifyEntitySchemaMutation} and {@link GrpcModifyEntitySchemaMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ModifyEntitySchemaMutationConverter implements SchemaMutationConverter<ModifyEntitySchemaMutation, GrpcModifyEntitySchemaMutation> {
	public static final ModifyEntitySchemaMutationConverter INSTANCE = new ModifyEntitySchemaMutationConverter();

	@Nonnull
	public ModifyEntitySchemaMutation convert(@Nonnull GrpcModifyEntitySchemaMutation mutation) {
		final LocalEntitySchemaMutation[] entitySchemaMutations = mutation.getEntitySchemaMutationsList()
			.stream()
			.map(DelegatingEntitySchemaMutationConverter.INSTANCE::convert)
			.peek(m -> Assert.isTrue(m instanceof LocalEntitySchemaMutation, "Expected LocalEntitySchemaMutation"))
			.map(LocalEntitySchemaMutation.class::cast)
			.toArray(LocalEntitySchemaMutation[]::new);

		return new ModifyEntitySchemaMutation(
			mutation.getEntityType(),
			entitySchemaMutations
		);
	}

	@Nonnull
	public GrpcModifyEntitySchemaMutation convert(@Nonnull ModifyEntitySchemaMutation mutation) {
		final List<GrpcEntitySchemaMutation> entitySchemaMutations = Arrays.stream(mutation.getSchemaMutations())
			.map(DelegatingEntitySchemaMutationConverter.INSTANCE::convert)
			.toList();

		return GrpcModifyEntitySchemaMutation.newBuilder()
			.setEntityType(mutation.getName())
			.addAllEntitySchemaMutations(entitySchemaMutations)
			.build();
	}
}
