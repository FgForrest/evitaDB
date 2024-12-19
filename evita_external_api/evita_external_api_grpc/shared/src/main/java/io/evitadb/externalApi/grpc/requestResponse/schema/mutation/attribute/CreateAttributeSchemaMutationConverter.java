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

import com.google.protobuf.StringValue;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcCreateAttributeSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcScopedAttributeUniquenessType;
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
 * Converts between {@link CreateAttributeSchemaMutation} and {@link GrpcCreateAttributeSchemaMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CreateAttributeSchemaMutationConverter implements SchemaMutationConverter<CreateAttributeSchemaMutation, GrpcCreateAttributeSchemaMutation> {
	public static final CreateAttributeSchemaMutationConverter INSTANCE = new CreateAttributeSchemaMutationConverter();

	@Nonnull
	public CreateAttributeSchemaMutation convert(@Nonnull GrpcCreateAttributeSchemaMutation mutation) {
		final ScopedAttributeUniquenessType[] uniqueInScopes = mutation.getUniqueInScopesList().isEmpty() ?
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.DEFAULT_SCOPE, toAttributeUniquenessType(mutation.getUnique()))
			}
			:
			mutation.getUniqueInScopesList()
				.stream()
				.map(it -> new ScopedAttributeUniquenessType(toScope(it.getScope()), toAttributeUniquenessType(it.getUniquenessType())))
				.toArray(ScopedAttributeUniquenessType[]::new);
		final Scope[] filterableInScopes = mutation.getFilterableInScopesList().isEmpty() ?
			(mutation.getFilterable() ? Scope.DEFAULT_SCOPES : Scope.NO_SCOPE)
			:
			mutation.getFilterableInScopesList()
				.stream()
				.map(EvitaEnumConverter::toScope)
				.toArray(Scope[]::new);
		final Scope[] sortableInScopes = mutation.getSortableInScopesList().isEmpty() ?
			(mutation.getSortable() ? Scope.DEFAULT_SCOPES : Scope.NO_SCOPE)
			:
			mutation.getSortableInScopesList()
				.stream()
				.map(EvitaEnumConverter::toScope)
				.toArray(Scope[]::new);

		return new CreateAttributeSchemaMutation(
			mutation.getName(),
			mutation.hasDescription() ? mutation.getDescription().getValue() : null,
			mutation.hasDeprecationNotice() ? mutation.getDeprecationNotice().getValue() : null,
			uniqueInScopes,
			filterableInScopes,
			sortableInScopes,
			mutation.getLocalized(),
			mutation.getNullable(),
			mutation.getRepresentative(),
			EvitaDataTypesConverter.toEvitaDataType(mutation.getType()),
			mutation.hasDefaultValue() ? EvitaDataTypesConverter.toEvitaValue(mutation.getDefaultValue()) : null,
			mutation.getIndexedDecimalPlaces()
		);
	}

	@Nonnull
	public GrpcCreateAttributeSchemaMutation convert(@Nonnull CreateAttributeSchemaMutation mutation) {
		final GrpcCreateAttributeSchemaMutation.Builder builder = GrpcCreateAttributeSchemaMutation.newBuilder()
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
			.setFilterable(mutation.isFilterable())
			.addAllFilterableInScopes(
				Arrays.stream(mutation.getFilterableInScopes())
					.map(EvitaEnumConverter::toGrpcScope)
					.toList()
			)
			.setSortable(mutation.isSortable())
			.addAllSortableInScopes(
				Arrays.stream(mutation.getSortableInScopes())
					.map(EvitaEnumConverter::toGrpcScope)
					.toList()
			)
			.setLocalized(mutation.isLocalized())
			.setNullable(mutation.isNullable())
			.setType(EvitaDataTypesConverter.toGrpcEvitaDataType(mutation.getType()))
			.setIndexedDecimalPlaces(mutation.getIndexedDecimalPlaces());

		if (mutation.getDescription() != null) {
			builder.setDescription(StringValue.of(mutation.getDescription()));
		}
		if (mutation.getDeprecationNotice() != null) {
			builder.setDeprecationNotice(StringValue.of(mutation.getDeprecationNotice()));
		}
		if (mutation.getDefaultValue() != null) {
			builder.setDefaultValue(EvitaDataTypesConverter.toGrpcEvitaValue(mutation.getDefaultValue()));
		}

		return builder.build();
	}
}
