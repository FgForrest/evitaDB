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

import io.evitadb.api.requestResponse.schema.mutation.entity.SetEntitySchemaWithHierarchyMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.grpc.generated.GrpcSetEntitySchemaWithHierarchyMutation;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Converts between {@link SetEntitySchemaWithHierarchyMutation} and {@link GrpcSetEntitySchemaWithHierarchyMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SetEntitySchemaWithHierarchyMutationConverter implements SchemaMutationConverter<SetEntitySchemaWithHierarchyMutation, GrpcSetEntitySchemaWithHierarchyMutation> {
	public static final SetEntitySchemaWithHierarchyMutationConverter INSTANCE = new SetEntitySchemaWithHierarchyMutationConverter();

	@Nonnull
	public SetEntitySchemaWithHierarchyMutation convert(@Nonnull GrpcSetEntitySchemaWithHierarchyMutation mutation) {
		return new SetEntitySchemaWithHierarchyMutation(
			mutation.getWithHierarchy(),
			mutation.getIndexedInScopesList()
			.stream()
				.map(EvitaEnumConverter::toScope)
				.toArray(Scope[]::new)
		);
	}

	@Nonnull
	public GrpcSetEntitySchemaWithHierarchyMutation convert(@Nonnull SetEntitySchemaWithHierarchyMutation mutation) {
		return GrpcSetEntitySchemaWithHierarchyMutation.newBuilder()
			.setWithHierarchy(mutation.isWithHierarchy())
			.addAllIndexedInScopes(
				Arrays.stream(mutation.getIndexedInScopes())
					.map(EvitaEnumConverter::toGrpcScope)
					.collect(Collectors.toList())
			)
			.build();
	}
}
