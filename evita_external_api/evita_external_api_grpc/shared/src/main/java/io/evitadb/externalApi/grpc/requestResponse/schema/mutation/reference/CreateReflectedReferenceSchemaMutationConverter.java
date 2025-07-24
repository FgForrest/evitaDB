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

import com.google.protobuf.BoolValue;
import com.google.protobuf.StringValue;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReflectedReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.grpc.generated.GrpcCreateReflectedReferenceSchemaMutation;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Arrays;

import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toAttributeInheritanceBehavior;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toCardinality;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcAttributeInheritanceBehavior;
import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.toGrpcCardinality;

/**
 * Converts between {@link CreateReflectedReferenceSchemaMutation} and {@link GrpcCreateReflectedReferenceSchemaMutation} in both directions.
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2024
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CreateReflectedReferenceSchemaMutationConverter implements SchemaMutationConverter<CreateReflectedReferenceSchemaMutation, GrpcCreateReflectedReferenceSchemaMutation> {
	public static final CreateReflectedReferenceSchemaMutationConverter INSTANCE = new CreateReflectedReferenceSchemaMutationConverter();

	@Nonnull
	public CreateReflectedReferenceSchemaMutation convert(@Nonnull GrpcCreateReflectedReferenceSchemaMutation mutation) {
		final ScopedReferenceIndexType[] indexedInScopes = mutation.getIndexedInScopesList().isEmpty() ?
			new ScopedReferenceIndexType[] {new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING)}
			:
			mutation.getIndexedInScopesList()
				.stream()
				.map(scope -> new ScopedReferenceIndexType(EvitaEnumConverter.toScope(scope), ReferenceIndexType.FOR_FILTERING))
				.toArray(ScopedReferenceIndexType[]::new);
		final Scope[] facetedInScopes = mutation.getFacetedInScopesList().isEmpty() ?
			(mutation.hasFaceted() && mutation.getFaceted().getValue() ? Scope.DEFAULT_SCOPES : Scope.NO_SCOPE)
			:
			mutation.getFacetedInScopesList()
				.stream()
				.map(EvitaEnumConverter::toScope)
				.toArray(Scope[]::new);

		return new CreateReflectedReferenceSchemaMutation(
			mutation.getName(),
			mutation.hasDescription() ? mutation.getDescription().getValue() : null,
			mutation.hasDeprecationNotice() ? mutation.getDeprecationNotice().getValue() : null,
			toCardinality(mutation.getCardinality()).orElse(null),
			mutation.getReferencedEntityType(),
			mutation.getReflectedReferenceName(),
			mutation.getIndexedInherited() ? null : indexedInScopes,
			mutation.getFacetedInherited() ? null : facetedInScopes,
			toAttributeInheritanceBehavior(mutation.getAttributeInheritanceBehavior()),
			mutation.getAttributeInheritanceFilterList().toArray(String[]::new)
		);
	}

	@Nonnull
	public GrpcCreateReflectedReferenceSchemaMutation convert(@Nonnull CreateReflectedReferenceSchemaMutation mutation) {
		final GrpcCreateReflectedReferenceSchemaMutation.Builder builder = GrpcCreateReflectedReferenceSchemaMutation.newBuilder()
			.setName(mutation.getName())
			.setCardinality(toGrpcCardinality(mutation.getCardinality()))
			.setReferencedEntityType(mutation.getReferencedEntityType())
			.setReflectedReferenceName(mutation.getReflectedReferenceName())
			.setAttributeInheritanceBehavior(toGrpcAttributeInheritanceBehavior(mutation.getAttributeInheritanceBehavior()));

		if (mutation.getDescription() != null) {
			builder.setDescription(StringValue.of(mutation.getDescription()));
		}
		if (mutation.getDeprecationNotice() != null) {
			builder.setDeprecationNotice(StringValue.of(mutation.getDeprecationNotice()));
		}
		if (mutation.getIndexedInScopes() != null) {
			builder.addAllIndexedInScopes(
				Arrays.stream(mutation.getIndexedInScopes())
					.map(scopedIndexType -> EvitaEnumConverter.toGrpcScope(scopedIndexType.scope()))
					.toList()
			);
		} else {
			builder.setIndexedInherited(true);
		}
		if (mutation.getFacetedInScopes() != null) {
			builder.setFaceted(BoolValue.of(mutation.isFaceted()));
			builder.addAllFacetedInScopes(
				Arrays.stream(mutation.getFacetedInScopes())
					.map(EvitaEnumConverter::toGrpcScope)
					.toList()
			);
		} else {
			builder.setFacetedInherited(true);
		}
		for (String attribute : mutation.getAttributeInheritanceFilter()) {
			builder.addAttributeInheritanceFilter(attribute);
		}

		return builder.build();
	}
}
