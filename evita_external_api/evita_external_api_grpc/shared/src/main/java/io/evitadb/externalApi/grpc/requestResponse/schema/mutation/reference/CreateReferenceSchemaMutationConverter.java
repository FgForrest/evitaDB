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

import com.google.protobuf.StringValue;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.grpc.generated.GrpcCreateReferenceSchemaMutation;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * Converts between {@link CreateReferenceSchemaMutation} and {@link GrpcCreateReferenceSchemaMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CreateReferenceSchemaMutationConverter implements SchemaMutationConverter<CreateReferenceSchemaMutation, GrpcCreateReferenceSchemaMutation> {
	public static final CreateReferenceSchemaMutationConverter INSTANCE = new CreateReferenceSchemaMutationConverter();

	@Nonnull
	public CreateReferenceSchemaMutation convert(@Nonnull GrpcCreateReferenceSchemaMutation mutation) {
		final ScopedReferenceIndexType[] indexedInScopes = mutation.getIndexedInScopesList().isEmpty() ?
			(mutation.getFilterable() ? new ScopedReferenceIndexType[] {new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING)} : ScopedReferenceIndexType.EMPTY)
			:
			mutation.getIndexedInScopesList()
				.stream()
				.map(scope -> new ScopedReferenceIndexType(EvitaEnumConverter.toScope(scope), ReferenceIndexType.FOR_FILTERING))
				.toArray(ScopedReferenceIndexType[]::new);
		final Scope[] facetedInScopes = mutation.getFacetedInScopesList().isEmpty() ?
			(mutation.getFaceted() ? Scope.DEFAULT_SCOPES : Scope.NO_SCOPE)
			:
			mutation.getFacetedInScopesList()
				.stream()
				.map(EvitaEnumConverter::toScope)
				.toArray(Scope[]::new);

		return new CreateReferenceSchemaMutation(
			mutation.getName(),
			mutation.hasDescription() ? mutation.getDescription().getValue() : null,
			mutation.hasDeprecationNotice() ? mutation.getDeprecationNotice().getValue() : null,
			EvitaEnumConverter.toCardinality(mutation.getCardinality()).orElse(null),
			mutation.getReferencedEntityType(),
			mutation.getReferencedEntityTypeManaged(),
			mutation.hasReferencedGroupType() ? mutation.getReferencedGroupType().getValue() : null,
			mutation.getReferencedGroupTypeManaged(),
			indexedInScopes,
			facetedInScopes
		);
	}

	@Nonnull
	public GrpcCreateReferenceSchemaMutation convert(@Nonnull CreateReferenceSchemaMutation mutation) {
		final GrpcCreateReferenceSchemaMutation.Builder builder = GrpcCreateReferenceSchemaMutation.newBuilder()
			.setName(mutation.getName())
			.setCardinality(EvitaEnumConverter.toGrpcCardinality(mutation.getCardinality()))
			.setReferencedEntityType(mutation.getReferencedEntityType())
			.setReferencedEntityTypeManaged(mutation.isReferencedEntityTypeManaged())
			.setReferencedGroupTypeManaged(mutation.isReferencedGroupTypeManaged())
			.setFilterable(mutation.isIndexed())
			.addAllIndexedInScopes(
				Arrays.stream(mutation.getIndexedInScopes())
					.map(scopedIndexType -> EvitaEnumConverter.toGrpcScope(scopedIndexType.scope()))
					.toList()
			)
			.setFaceted(mutation.isFaceted())
			.addAllFacetedInScopes(
				Arrays.stream(mutation.getFacetedInScopes())
					.map(EvitaEnumConverter::toGrpcScope)
					.toList()
			);

		if (mutation.getDescription() != null) {
			builder.setDescription(StringValue.of(mutation.getDescription()));
		}
		if (mutation.getDeprecationNotice() != null) {
			builder.setDeprecationNotice(StringValue.of(mutation.getDeprecationNotice()));
		}
		if (mutation.getReferencedGroupType() != null) {
			builder.setReferencedGroupType(StringValue.of(mutation.getReferencedGroupType()));
		}

		return builder.build();
	}
}
