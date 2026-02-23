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

import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaIndexedMutation;
import io.evitadb.externalApi.grpc.generated.GrpcEntityScope;
import io.evitadb.externalApi.grpc.generated.GrpcScopedReferenceIndexType;
import io.evitadb.externalApi.grpc.generated.GrpcScopedReferenceIndexedComponents;
import io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaIndexedMutation;
import io.evitadb.externalApi.grpc.generated.GrpcSetReferenceSchemaIndexedMutation.Builder;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

import static java.util.Optional.ofNullable;

/**
 * Converts between {@link SetReferenceSchemaIndexedMutation} and {@link GrpcSetReferenceSchemaIndexedMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SetReferenceSchemaIndexedMutationConverter
	implements SchemaMutationConverter<SetReferenceSchemaIndexedMutation, GrpcSetReferenceSchemaIndexedMutation> {
	public static final SetReferenceSchemaIndexedMutationConverter INSTANCE = new SetReferenceSchemaIndexedMutationConverter();

	/**
	 * Converts a list of {@link GrpcScopedReferenceIndexedComponents} to an array of
	 * {@link ScopedReferenceIndexedComponents}, or returns null if the list is empty.
	 */
	@Nullable
	public static ScopedReferenceIndexedComponents[] getIndexedComponentsInScopes(
		@Nonnull List<GrpcScopedReferenceIndexedComponents> grpcComponents
	) {
		if (grpcComponents.isEmpty()) {
			return null;
		}
		return grpcComponents.stream()
			.map(
				grpcCmp -> new ScopedReferenceIndexedComponents(
					EvitaEnumConverter.toScope(grpcCmp.getScope()),
					grpcCmp.getIndexedComponentsList()
						.stream()
						.map(EvitaEnumConverter::toReferenceIndexedComponents)
						.toArray(ReferenceIndexedComponents[]::new)
				)
			)
			.toArray(ScopedReferenceIndexedComponents[]::new);
	}

	/**
	 * Converts an array of {@link ScopedReferenceIndexedComponents} to a list of
	 * {@link GrpcScopedReferenceIndexedComponents}.
	 */
	@Nonnull
	public static List<GrpcScopedReferenceIndexedComponents> toGrpcScopedIndexedComponents(
		@Nonnull ScopedReferenceIndexedComponents[] components
	) {
		return Arrays.stream(components)
			.map(
				cmp -> GrpcScopedReferenceIndexedComponents.newBuilder()
					.setScope(EvitaEnumConverter.toGrpcScope(cmp.scope()))
					.addAllIndexedComponents(
						Arrays.stream(cmp.indexedComponents())
							.map(EvitaEnumConverter::toGrpcReferenceIndexedComponents)
							.toList()
					)
					.build()
			)
			.toList();
	}

	/**
	 * Resolves indexed scopes from gRPC message fields with
	 * backward compatibility. Tries the new `scopedIndexTypes`
	 * field first, falls back to legacy `indexedInScopes`
	 * (assuming {@link ReferenceIndexType#FOR_FILTERING}),
	 * and finally uses the provided fallback value.
	 *
	 * @param scopedIndexTypes      new-style scoped index type list
	 * @param legacyIndexedInScopes legacy scope list
	 * @param fallback              value when both lists are empty
	 * @return resolved array of scoped reference index types
	 */
	@Nonnull
	public static ScopedReferenceIndexType[] resolveIndexedInScopes(
		@Nonnull List<GrpcScopedReferenceIndexType> scopedIndexTypes,
		@Nonnull List<GrpcEntityScope> legacyIndexedInScopes,
		@Nonnull ScopedReferenceIndexType[] fallback
	) {
		if (!scopedIndexTypes.isEmpty()) {
			return scopedIndexTypes.stream()
				.map(
					scopedType -> new ScopedReferenceIndexType(
						EvitaEnumConverter.toScope(scopedType.getScope()),
						EvitaEnumConverter.toReferenceIndexType(
							scopedType.getIndexType()
						)
					)
				)
				.toArray(ScopedReferenceIndexType[]::new);
		} else if (!legacyIndexedInScopes.isEmpty()) {
			return legacyIndexedInScopes.stream()
				.map(
					scope -> new ScopedReferenceIndexType(
						EvitaEnumConverter.toScope(scope),
						ReferenceIndexType.FOR_FILTERING
					)
				)
				.toArray(ScopedReferenceIndexType[]::new);
		} else {
			return fallback;
		}
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
								.map(
									scopedType -> GrpcScopedReferenceIndexType.newBuilder()
										.setScope(EvitaEnumConverter.toGrpcScope(scopedType.scope()))
										.setIndexType(
											EvitaEnumConverter.toGrpcReferenceIndexType(scopedType.indexType()))
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
		// Handle indexed components
		ofNullable(mutation.getIndexedComponentsInScopes())
			.ifPresent(components -> builder.addAllScopedIndexedComponents(
				toGrpcScopedIndexedComponents(components)
			));
		return builder.build();
	}

	@Nonnull
	public SetReferenceSchemaIndexedMutation convert(@Nonnull GrpcSetReferenceSchemaIndexedMutation mutation) {
		if (mutation.getInherited()) {
			return new SetReferenceSchemaIndexedMutation(mutation.getName(), (ScopedReferenceIndexType[]) null);
		}

		final ScopedReferenceIndexType[] indexedInScopes = resolveIndexedInScopes(
			mutation.getScopedIndexTypesList(),
			mutation.getIndexedInScopesList(),
			ScopedReferenceIndexType.EMPTY
		);

		// Handle indexed components
		final ScopedReferenceIndexedComponents[] indexedComponentsInScopes =
			getIndexedComponentsInScopes(mutation.getScopedIndexedComponentsList());

		return new SetReferenceSchemaIndexedMutation(
			mutation.getName(), indexedInScopes, indexedComponentsInScopes
		);
	}
}
