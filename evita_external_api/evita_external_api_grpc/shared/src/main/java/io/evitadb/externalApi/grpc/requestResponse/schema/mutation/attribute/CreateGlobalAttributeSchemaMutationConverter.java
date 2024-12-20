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
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateGlobalAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedGlobalAttributeUniquenessType;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcCreateGlobalAttributeSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcScopedAttributeUniquenessType;
import io.evitadb.externalApi.grpc.generated.GrpcScopedGlobalAttributeUniquenessType;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.externalApi.grpc.requestResponse.schema.mutation.SchemaMutationConverter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Arrays;

import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.*;

/**
 * Converts between {@link CreateGlobalAttributeSchemaMutation} and {@link GrpcCreateGlobalAttributeSchemaMutation} in both directions.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CreateGlobalAttributeSchemaMutationConverter implements SchemaMutationConverter<CreateGlobalAttributeSchemaMutation, GrpcCreateGlobalAttributeSchemaMutation> {
	public static final CreateGlobalAttributeSchemaMutationConverter INSTANCE = new CreateGlobalAttributeSchemaMutationConverter();

	@Nonnull
	public CreateGlobalAttributeSchemaMutation convert(@Nonnull GrpcCreateGlobalAttributeSchemaMutation mutation) {
		final ScopedAttributeUniquenessType[] uniqueInScopes = mutation.getUniqueInScopesList().isEmpty() ?
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.DEFAULT_SCOPE, toAttributeUniquenessType(mutation.getUnique()))
			}
			:
			mutation.getUniqueInScopesList()
				.stream()
				.map(it -> new ScopedAttributeUniquenessType(toScope(it.getScope()), toAttributeUniquenessType(it.getUniquenessType())))
				.toArray(ScopedAttributeUniquenessType[]::new);
		final ScopedGlobalAttributeUniquenessType[] uniqueGloballyInScopes = mutation.getUniqueGloballyInScopesList().isEmpty() ?
			new ScopedGlobalAttributeUniquenessType[]{
				new ScopedGlobalAttributeUniquenessType(Scope.DEFAULT_SCOPE, toGlobalAttributeUniquenessType(mutation.getUniqueGlobally()))
			}
			:
			mutation.getUniqueGloballyInScopesList()
				.stream()
				.map(it -> new ScopedGlobalAttributeUniquenessType(toScope(it.getScope()), toGlobalAttributeUniquenessType(it.getUniquenessType())))
				.toArray(ScopedGlobalAttributeUniquenessType[]::new);
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

		return new CreateGlobalAttributeSchemaMutation(
			mutation.getName(),
			mutation.hasDescription() ? mutation.getDescription().getValue() : null,
			mutation.hasDeprecationNotice() ? mutation.getDeprecationNotice().getValue() : null,
			uniqueInScopes,
			uniqueGloballyInScopes,
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
	public GrpcCreateGlobalAttributeSchemaMutation convert(@Nonnull CreateGlobalAttributeSchemaMutation mutation) {
		final GrpcCreateGlobalAttributeSchemaMutation.Builder builder = GrpcCreateGlobalAttributeSchemaMutation.newBuilder()
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
