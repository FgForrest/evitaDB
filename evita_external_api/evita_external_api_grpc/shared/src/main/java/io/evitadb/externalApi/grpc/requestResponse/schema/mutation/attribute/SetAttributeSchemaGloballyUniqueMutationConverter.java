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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.attribute;

import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedGlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaGloballyUniqueMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.grpc.generated.GrpcScopedGlobalAttributeUniquenessType;
import io.evitadb.externalApi.grpc.generated.GrpcSetAttributeSchemaGloballyUniqueMutation;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Arrays;

import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGlobalAttributeUniquenessType;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcGlobalAttributeUniquenessType;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toScope;

/**
 * Converts between {@link SetAttributeSchemaGloballyUniqueMutation} and {@link GrpcSetAttributeSchemaGloballyUniqueMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SetAttributeSchemaGloballyUniqueMutationConverter implements SchemaMutationConverter<SetAttributeSchemaGloballyUniqueMutation, GrpcSetAttributeSchemaGloballyUniqueMutation> {
	public static final SetAttributeSchemaGloballyUniqueMutationConverter INSTANCE = new SetAttributeSchemaGloballyUniqueMutationConverter();

	@Nonnull
	public SetAttributeSchemaGloballyUniqueMutation convert(@Nonnull GrpcSetAttributeSchemaGloballyUniqueMutation mutation) {
		final ScopedGlobalAttributeUniquenessType[] uniqueGloballyInScopes = mutation.getUniqueGloballyInScopesList().isEmpty() ?
			new ScopedGlobalAttributeUniquenessType[]{
				new ScopedGlobalAttributeUniquenessType(Scope.LIVE, toGlobalAttributeUniquenessType(mutation.getUniqueGlobally()))
			}
			:
			mutation.getUniqueGloballyInScopesList()
				.stream()
				.map(it -> new ScopedGlobalAttributeUniquenessType(toScope(it.getScope()), toGlobalAttributeUniquenessType(it.getUniquenessType())))
				.toArray(ScopedGlobalAttributeUniquenessType[]::new);

		return new SetAttributeSchemaGloballyUniqueMutation(
			mutation.getName(),
			uniqueGloballyInScopes
		);
	}

	@Nonnull
	public GrpcSetAttributeSchemaGloballyUniqueMutation convert(@Nonnull SetAttributeSchemaGloballyUniqueMutation mutation) {
		return GrpcSetAttributeSchemaGloballyUniqueMutation.newBuilder()
			.setName(mutation.getName())
			.setUniqueGlobally(toGrpcGlobalAttributeUniquenessType(mutation.getUniqueGlobally()))
			.addAllUniqueGloballyInScopes(
				Arrays.stream(mutation.getUniqueGloballyInScopes())
					.map(
						it -> GrpcScopedGlobalAttributeUniquenessType.newBuilder()
							.setScope(EvitaEnumConverter.toGrpcScope(it.scope()))
							.setUniquenessType(toGrpcGlobalAttributeUniquenessType(it.uniquenessType()))
							.build()
					)
					.toList()
			)
			.build();
	}
}
