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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.reference;

import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaIndexedMutation;
import io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaIndexedMutation;
import io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaIndexedMutation.Builder;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Arrays;

import static java.util.Optional.ofNullable;

/**
 * Converts between {@link SetReferenceSchemaIndexedMutation} and {@link GrpcSetReferenceSchemaIndexedMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SetReferenceSchemaIndexedMutationConverter implements SchemaMutationConverter<SetReferenceSchemaIndexedMutation, GrpcSetReferenceSchemaIndexedMutation> {
	public static final SetReferenceSchemaIndexedMutationConverter INSTANCE = new SetReferenceSchemaIndexedMutationConverter();

	@Nonnull
	public SetReferenceSchemaIndexedMutation convert(@Nonnull GrpcSetReferenceSchemaIndexedMutation mutation) {
		if (mutation.getInherited()) {
			return new SetReferenceSchemaIndexedMutation(mutation.getName(), (ScopedReferenceIndexType[]) null);
		}

		// Handle new scopedIndexTypes field with backward compatibility
		final ScopedReferenceIndexType[] indexedInScopes;
		if (!mutation.getScopedIndexTypesList().isEmpty()) {
			// Use new scoped index types if available
			indexedInScopes = mutation.getScopedIndexTypesList()
				.stream()
				.map(scopedType -> new ScopedReferenceIndexType(
					EvitaEnumConverter.toScope(scopedType.getScope()),
					EvitaEnumConverter.toReferenceIndexType(scopedType.getIndexType())
				))
				.toArray(ScopedReferenceIndexType[]::new);
		} else if (!mutation.getIndexedInScopesList().isEmpty()) {
			// Fall back to old indexedInScopes field for backward compatibility
			indexedInScopes = mutation.getIndexedInScopesList()
				.stream()
				.map(scope -> new ScopedReferenceIndexType(EvitaEnumConverter.toScope(scope), ReferenceIndexType.FOR_FILTERING))
				.toArray(ScopedReferenceIndexType[]::new);
		} else {
			// No indexing specified
			indexedInScopes = ScopedReferenceIndexType.EMPTY;
		}

		return new SetReferenceSchemaIndexedMutation(mutation.getName(), indexedInScopes);
	}

	@Nonnull
	public GrpcSetReferenceSchemaIndexedMutation convert(@Nonnull SetReferenceSchemaIndexedMutation mutation) {
		final Builder builder = GrpcSetReferenceSchemaIndexedMutation.newBuilder()
			.setName(mutation.getName());
		final boolean indexedInherited = mutation.getIndexedInScopes() == null;
		builder.setInherited(indexedInherited);
		if (!indexedInherited) {
			ofNullable(mutation.getIndexedInScopes())
				.ifPresentOrElse(
					scopedIndexTypes -> {
						// Populate new scopedIndexTypes field
						builder.addAllScopedIndexTypes(
							Arrays.stream(scopedIndexTypes)
								.map(scopedType -> io.evitadb.externalApi.grpc.generated.GrpcScopedReferenceIndexType.newBuilder()
									.setScope(EvitaEnumConverter.toGrpcScope(scopedType.scope()))
									.setIndexType(EvitaEnumConverter.toGrpcReferenceIndexType(scopedType.indexType()))
									.build())
								.toList()
						);
						// Populate old indexedInScopes field for backward compatibility
						builder.addAllIndexedInScopes(
							Arrays.stream(scopedIndexTypes)
								.filter(it -> it.indexType() != ReferenceIndexType.NONE)
								.map(scopedType -> EvitaEnumConverter.toGrpcScope(scopedType.scope()))
								.toList()
						);
					},
					() -> builder.setInherited(true)
				);
		}
		return builder.build();
	}
}
