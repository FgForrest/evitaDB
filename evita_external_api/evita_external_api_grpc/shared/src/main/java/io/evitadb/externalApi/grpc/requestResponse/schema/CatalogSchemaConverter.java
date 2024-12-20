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

package io.evitadb.externalApi.grpc.requestResponse.schema;

import com.google.protobuf.StringValue;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedGlobalAttributeUniquenessType;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcCatalogSchema;
import io.evitadb.externalApi.grpc.generated.GrpcGlobalAttributeSchema;
import io.evitadb.externalApi.grpc.generated.GrpcGlobalAttributeSchema.Builder;
import io.evitadb.externalApi.grpc.generated.GrpcScopedAttributeUniquenessType;
import io.evitadb.externalApi.grpc.generated.GrpcScopedGlobalAttributeUniquenessType;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter.*;
import static java.util.Optional.ofNullable;

/**
 * This class is used to convert between {@link GrpcCatalogSchema} from {@link CatalogSchema}.
 *
 * @author Tomáš Pozler, 2022
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 * @author Jan Novotný, FG Forrest a.s. (c) 2023
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CatalogSchemaConverter {

	/**
	 * Creates {@link GrpcCatalogSchema} from the {@link CatalogSchemaContract}.
	 *
	 * @param catalogSchema catalog schema to convert
	 * @param includeNameVariants if true, name variants will be included in the result
	 */
	@Nonnull
	public static GrpcCatalogSchema convert(@Nonnull CatalogSchemaContract catalogSchema, boolean includeNameVariants) {
		final GrpcCatalogSchema.Builder builder = GrpcCatalogSchema.newBuilder()
			.setName(catalogSchema.getName())
			.setVersion(catalogSchema.version())
			.putAllAttributes(toGrpcGlobalAttributeSchemas(catalogSchema.getAttributes(), includeNameVariants));

		if (catalogSchema.getDescription() != null) {
			builder.setDescription(StringValue.of(catalogSchema.getDescription()));
		}

		for (CatalogEvolutionMode catalogEvolutionMode : catalogSchema.getCatalogEvolutionMode()) {
			builder.addCatalogEvolutionMode(EvitaEnumConverter.toGrpcCatalogEvolutionMode(catalogEvolutionMode));
		}

		if (includeNameVariants) {
			catalogSchema.getNameVariants()
				.forEach(
					(namingConvention, nameVariant) -> builder.addNameVariant(EvitaDataTypesConverter.toGrpcNameVariant(namingConvention, nameVariant))
				);
		}

		return builder.build();
	}

	/**
	 * Creates {@link SealedCatalogSchema} from the {@link GrpcCatalogSchema}.
	 */
	@Nonnull
	public static CatalogSchema convert(@Nonnull GrpcCatalogSchema catalogSchema, @Nonnull EntitySchemaProvider entitySchemaProvider) {
		return CatalogSchema._internalBuild(
			catalogSchema.getVersion(),
			catalogSchema.getName(),
			NamingConvention.generate(catalogSchema.getName()),
			catalogSchema.hasDescription() ? catalogSchema.getDescription().getValue() : null,
			catalogSchema.getCatalogEvolutionModeList()
				.stream()
				.map(EvitaEnumConverter::toCatalogEvolutionMode)
				.collect(Collectors.toSet()),
			catalogSchema.getAttributesMap()
				.entrySet()
				.stream()
				.collect(Collectors.toMap(
					Entry::getKey,
					it -> toGlobalAttributeSchema(it.getValue())
				)),
			entitySchemaProvider
		);
	}

	/**
	 * From passed map where keys are representing attribute names and values {@link GlobalAttributeSchema} will be built a new map where values are converted
	 * to {@link GrpcGlobalAttributeSchema}.
	 *
	 * @param originalAttributeSchemas map of {@link GlobalAttributeSchema} to be converted to map of {@link GrpcGlobalAttributeSchema}
	 * @param includeNameVariants if true, name variants will be included in the result
	 * @return map with same keys as original map and values of type {@link GrpcGlobalAttributeSchema}
	 */
	@Nonnull
	private static Map<String, GrpcGlobalAttributeSchema> toGrpcGlobalAttributeSchemas(@Nonnull Map<String, GlobalAttributeSchemaContract> originalAttributeSchemas, boolean includeNameVariants) {
		final Map<String, GrpcGlobalAttributeSchema> attributeSchemas = CollectionUtils.createHashMap(originalAttributeSchemas.size());
		for (Map.Entry<String, GlobalAttributeSchemaContract> entry : originalAttributeSchemas.entrySet()) {
			attributeSchemas.put(entry.getKey(), toGrpcGlobalAttributeSchema(entry.getValue(), includeNameVariants));
		}
		return attributeSchemas;
	}

	/**
	 * Converts single {@link GlobalAttributeSchema} to {@link GrpcGlobalAttributeSchema}.
	 *
	 * @param attributeSchema instance of {@link GlobalAttributeSchema} to be converted to {@link GrpcGlobalAttributeSchema}
	 * @param includeNameVariants if true, name variants will be included in the result
	 * @return built instance of {@link GrpcGlobalAttributeSchema}
	 */
	@Nonnull
	private static GrpcGlobalAttributeSchema toGrpcGlobalAttributeSchema(@Nonnull GlobalAttributeSchemaContract attributeSchema, boolean includeNameVariants) {
		final Builder builder = GrpcGlobalAttributeSchema.newBuilder()
			.setName(attributeSchema.getName())
			.setUnique(EvitaEnumConverter.toGrpcAttributeUniquenessType(attributeSchema.getUniquenessType()))
			.addAllUniqueInScopes(
				Arrays.stream(Scope.values())
					.map(scope -> new ScopedAttributeUniquenessType(scope, attributeSchema.getUniquenessType(scope)))
					// filter default values
					.filter(scopedUniquenessType -> scopedUniquenessType.uniquenessType() != AttributeUniquenessType.NOT_UNIQUE)
					.map(
						scopedUniquenessType -> GrpcScopedAttributeUniquenessType.newBuilder()
							.setScope(EvitaEnumConverter.toGrpcScope(scopedUniquenessType.scope()))
							.setUniquenessType(toGrpcAttributeUniquenessType(scopedUniquenessType.uniquenessType()))
							.build()
					)
					.toList()
			)
			.setUniqueGlobally(toGrpcGlobalAttributeUniquenessType(attributeSchema.getGlobalUniquenessType()))
			.addAllUniqueGloballyInScopes(
				Arrays.stream(Scope.values())
					.map(scope -> new ScopedGlobalAttributeUniquenessType(scope, attributeSchema.getGlobalUniquenessType(scope)))
					// filter default values
					.filter(scopedUniquenessType -> scopedUniquenessType.uniquenessType() != GlobalAttributeUniquenessType.NOT_UNIQUE)
					.map(
						scopedUniquenessType -> GrpcScopedGlobalAttributeUniquenessType.newBuilder()
							.setScope(EvitaEnumConverter.toGrpcScope(scopedUniquenessType.scope()))
							.setUniquenessType(toGrpcGlobalAttributeUniquenessType(scopedUniquenessType.uniquenessType()))
							.build()
					)
					.toList()
			)
			.setFilterable(attributeSchema.isFilterable())
			.addAllFilterableInScopes(
				Arrays.stream(Scope.values())
					.filter(attributeSchema::isFilterableInScope)
					.map(EvitaEnumConverter::toGrpcScope)
					.toList()
			)
			.setSortable(attributeSchema.isSortable())
			.addAllSortableInScopes(
				Arrays.stream(Scope.values())
					.filter(attributeSchema::isSortableInScope)
					.map(EvitaEnumConverter::toGrpcScope)
					.toList()
			)
			.setLocalized(attributeSchema.isLocalized())
			.setNullable(attributeSchema.isNullable())
			.setType(EvitaDataTypesConverter.toGrpcEvitaDataType(attributeSchema.getType()))
			.setIndexedDecimalPlaces(attributeSchema.getIndexedDecimalPlaces());

		ofNullable(attributeSchema.getDefaultValue())
			.ifPresent(it -> builder.setDefaultValue(EvitaDataTypesConverter.toGrpcEvitaValue(it, null)));
		ofNullable(attributeSchema.getDescription())
			.ifPresent(it -> builder.setDescription(StringValue.of(it)));
		ofNullable(attributeSchema.getDeprecationNotice())
			.ifPresent(it -> builder.setDeprecationNotice(StringValue.of(it)));

		if (includeNameVariants) {
			attributeSchema.getNameVariants()
				.forEach(
					(namingConvention, nameVariant) -> builder.addNameVariant(EvitaDataTypesConverter.toGrpcNameVariant(namingConvention, nameVariant))
				);
		}

		return builder.build();
	}

	/**
	 * Creates {@link GlobalAttributeSchema} from the {@link GrpcGlobalAttributeSchema}.
	 */
	@Nonnull
	private static GlobalAttributeSchemaContract toGlobalAttributeSchema(@Nonnull GrpcGlobalAttributeSchema attributeSchema) {
		final ScopedAttributeUniquenessType[] uniqueInScopes = attributeSchema.getUniqueInScopesList().isEmpty() ?
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.DEFAULT_SCOPE, toAttributeUniquenessType(attributeSchema.getUnique()))
			}
			:
			attributeSchema.getUniqueInScopesList()
				.stream()
				.map(it -> new ScopedAttributeUniquenessType(toScope(it.getScope()), toAttributeUniquenessType(it.getUniquenessType())))
				.toArray(ScopedAttributeUniquenessType[]::new);
		final ScopedGlobalAttributeUniquenessType[] uniqueGloballyInScopes = attributeSchema.getUniqueGloballyInScopesList().isEmpty() ?
			new ScopedGlobalAttributeUniquenessType[]{
				new ScopedGlobalAttributeUniquenessType(Scope.DEFAULT_SCOPE, toGlobalAttributeUniquenessType(attributeSchema.getUniqueGlobally()))
			}
			:
			attributeSchema.getUniqueGloballyInScopesList()
				.stream()
				.map(it -> new ScopedGlobalAttributeUniquenessType(toScope(it.getScope()), toGlobalAttributeUniquenessType(it.getUniquenessType())))
				.toArray(ScopedGlobalAttributeUniquenessType[]::new);
		final Scope[] filterableInScopes = attributeSchema.getFilterableInScopesList().isEmpty() ?
			(attributeSchema.getFilterable() ? Scope.DEFAULT_SCOPES : Scope.NO_SCOPE)
			:
			attributeSchema.getFilterableInScopesList()
				.stream()
				.map(EvitaEnumConverter::toScope)
				.toArray(Scope[]::new);
		final Scope[] sortableInScopes = attributeSchema.getSortableInScopesList().isEmpty() ?
			(attributeSchema.getSortable() ? Scope.DEFAULT_SCOPES : Scope.NO_SCOPE)
			:
			attributeSchema.getSortableInScopesList()
				.stream()
				.map(EvitaEnumConverter::toScope)
				.toArray(Scope[]::new);

		return GlobalAttributeSchema._internalBuild(
			attributeSchema.getName(),
			attributeSchema.hasDescription() ? attributeSchema.getDescription().getValue() : null,
			attributeSchema.hasDeprecationNotice() ? attributeSchema.getDeprecationNotice().getValue() : null,
			uniqueInScopes,
			uniqueGloballyInScopes,
			filterableInScopes,
			sortableInScopes,
			attributeSchema.getLocalized(),
			attributeSchema.getNullable(),
			attributeSchema.getRepresentative(),
			EvitaDataTypesConverter.toEvitaDataType(attributeSchema.getType()),
			attributeSchema.hasDefaultValue() ? EvitaDataTypesConverter.toEvitaValue(attributeSchema.getDefaultValue()) : null,
			attributeSchema.getIndexedDecimalPlaces()
		);
	}

}
