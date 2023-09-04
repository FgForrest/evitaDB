/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.GrpcCatalogSchema;
import io.evitadb.externalApi.grpc.generated.GrpcGlobalAttributeSchema;
import io.evitadb.externalApi.grpc.generated.GrpcGlobalAttributeSchema.Builder;
import io.evitadb.externalApi.grpc.requestResponse.EvitaEnumConverter;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

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
	 */
	@Nonnull
	public static GrpcCatalogSchema convert(@Nonnull CatalogSchemaContract catalogSchema) {
		final GrpcCatalogSchema.Builder builder = GrpcCatalogSchema.newBuilder()
			.setName(catalogSchema.getName())
			.setVersion(catalogSchema.getVersion())
			.putAllAttributes(toGrpcGlobalAttributeSchemas(catalogSchema.getAttributes()));

		if (catalogSchema.getDescription() != null) {
			builder.setDescription(StringValue.of(catalogSchema.getDescription()));
		}

		for (CatalogEvolutionMode catalogEvolutionMode : catalogSchema.getCatalogEvolutionMode()) {
			builder.addCatalogEvolutionMode(EvitaEnumConverter.toGrpcCatalogEvolutionMode(catalogEvolutionMode));
		}

		return builder.build();
	}


	/**
	 * Creates {@link SealedCatalogSchema} from the {@link GrpcCatalogSchema}.
	 */
	@Nonnull
	public static CatalogSchema convert(@Nonnull GrpcCatalogSchema catalogSchema) {
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
				__ -> {
					throw new EvitaInternalError("Unsupported operation. Missing current session.");
				}
		);
	}

	/**
	 * From passed map where keys are representing attribute names and values {@link GlobalAttributeSchema} will be built a new map where values are converted
	 * to {@link GrpcGlobalAttributeSchema}.
	 *
	 * @param originalAttributeSchemas map of {@link GlobalAttributeSchema} to be converted to map of {@link GrpcGlobalAttributeSchema}
	 * @return map with same keys as original map and values of type {@link GrpcGlobalAttributeSchema}
	 */
	@Nonnull
	private static Map<String, GrpcGlobalAttributeSchema> toGrpcGlobalAttributeSchemas(@Nonnull Map<String, GlobalAttributeSchemaContract> originalAttributeSchemas) {
		final Map<String, GrpcGlobalAttributeSchema> attributeSchemas = CollectionUtils.createHashMap(originalAttributeSchemas.size());
		for (Map.Entry<String, GlobalAttributeSchemaContract> entry : originalAttributeSchemas.entrySet()) {
			attributeSchemas.put(entry.getKey(), toGrpcGlobalAttributeSchema(entry.getValue()));
		}
		return attributeSchemas;
	}

	/**
	 * Converts single {@link GlobalAttributeSchema} to {@link GrpcGlobalAttributeSchema}.
	 *
	 * @param attributeSchema instance of {@link GlobalAttributeSchema} to be converted to {@link GrpcGlobalAttributeSchema}
	 * @return built instance of {@link GrpcGlobalAttributeSchema}
	 */
	@Nonnull
	private static GrpcGlobalAttributeSchema toGrpcGlobalAttributeSchema(@Nonnull GlobalAttributeSchemaContract attributeSchema) {
		final Builder builder = GrpcGlobalAttributeSchema.newBuilder()
			.setName(attributeSchema.getName())
			.setUnique(attributeSchema.isUnique())
			.setFilterable(attributeSchema.isFilterable())
			.setSortable(attributeSchema.isSortable())
			.setLocalized(attributeSchema.isLocalized())
			.setNullable(attributeSchema.isNullable())
			.setType(EvitaDataTypesConverter.toGrpcEvitaDataType(attributeSchema.getType()))
			.setIndexedDecimalPlaces(attributeSchema.getIndexedDecimalPlaces())
			.setUniqueGlobally(attributeSchema.isUniqueGlobally());

		ofNullable(attributeSchema.getDefaultValue())
			.ifPresent(it -> builder.setDefaultValue(EvitaDataTypesConverter.toGrpcEvitaValue(it, null)));
		ofNullable(attributeSchema.getDescription())
			.ifPresent(it -> builder.setDescription(StringValue.of(it)));
		ofNullable(attributeSchema.getDeprecationNotice())
			.ifPresent(it -> builder.setDeprecationNotice(StringValue.of(it)));

		return builder.build();
	}

	/**
	 * Creates {@link GlobalAttributeSchema} from the {@link GrpcGlobalAttributeSchema}.
	 */
	@Nonnull
	private static GlobalAttributeSchemaContract toGlobalAttributeSchema(@Nonnull GrpcGlobalAttributeSchema attributeSchema) {
		return GlobalAttributeSchema._internalBuild(
			attributeSchema.getName(),
			attributeSchema.hasDescription() ? attributeSchema.getDescription().getValue() : null,
			attributeSchema.hasDeprecationNotice() ? attributeSchema.getDeprecationNotice().getValue() : null,
			attributeSchema.getUnique(),
			attributeSchema.getUniqueGlobally(),
			attributeSchema.getFilterable(),
			attributeSchema.getSortable(),
			attributeSchema.getLocalized(),
			attributeSchema.getNullable(),
			EvitaDataTypesConverter.toEvitaDataType(attributeSchema.getType()),
			attributeSchema.hasDefaultValue() ? EvitaDataTypesConverter.toEvitaValue(attributeSchema.getDefaultValue()) : null,
			attributeSchema.getIndexedDecimalPlaces()
		);
	}

}
