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

import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaUniqueMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.grpc.generated.GrpcScopedAttributeUniquenessType;
import io.evitadb.externalApi.grpc.generated.GrpcSetAttributeSchemaUniqueMutation;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Arrays;

import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toAttributeUniquenessType;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcAttributeUniquenessType;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toScope;

/**
 * Converts between {@link SetAttributeSchemaUniqueMutation} and {@link GrpcSetAttributeSchemaUniqueMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SetAttributeSchemaUniqueMutationConverter implements SchemaMutationConverter<SetAttributeSchemaUniqueMutation, GrpcSetAttributeSchemaUniqueMutation> {
	public static final SetAttributeSchemaUniqueMutationConverter INSTANCE = new SetAttributeSchemaUniqueMutationConverter();

	@Nonnull
	public SetAttributeSchemaUniqueMutation convert(@Nonnull GrpcSetAttributeSchemaUniqueMutation mutation) {
		final ScopedAttributeUniquenessType[] uniqueInScopes = mutation.getUniqueInScopesList().isEmpty() ?
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.DEFAULT_SCOPE, toAttributeUniquenessType(mutation.getUnique()))
			}
			:
			mutation.getUniqueInScopesList()
				.stream()
				.map(it -> new ScopedAttributeUniquenessType(toScope(it.getScope()), toAttributeUniquenessType(it.getUniquenessType())))
				.toArray(ScopedAttributeUniquenessType[]::new);
		return new SetAttributeSchemaUniqueMutation(
			mutation.getName(),
			uniqueInScopes
		);
	}

	@Nonnull
	public GrpcSetAttributeSchemaUniqueMutation convert(@Nonnull SetAttributeSchemaUniqueMutation mutation) {
		return GrpcSetAttributeSchemaUniqueMutation.newBuilder()
			.setName(mutation.getName())
			.setUnique(toGrpcAttributeUniquenessType(mutation.getUnique()))
			.addAllUniqueInScopes(
				Arrays.stream(mutation.getUniqueInScopes())
					.map(
						it -> GrpcScopedAttributeUniquenessType.newBuilder()
							.setScope(EvitaEnumConverter.toGrpcScope(it.scope()))
							.setUniquenessType(toGrpcAttributeUniquenessType(it.uniquenessType()))
							.build()
					)
					.toList()
			)
			.build();
	}
}
