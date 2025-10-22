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

package io.evitadb.externalApi.rest.api.builder;

import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.dataType.DataTypeSerializer;
import io.evitadb.externalApi.rest.api.model.ObjectDescriptorToOpenApiDictionaryTransformer;
import io.evitadb.externalApi.rest.api.model.ObjectDescriptorToOpenApiObjectTransformer;
import io.evitadb.externalApi.rest.api.model.UnionDescriptorToOpenApiUnionTransformer;
import io.evitadb.externalApi.rest.api.model.PropertyDataTypeDescriptorToOpenApiTypeTransformer;
import io.evitadb.externalApi.rest.api.model.PropertyDescriptorToOpenApiOperationPathParameterTransformer;
import io.evitadb.externalApi.rest.api.model.PropertyDescriptorToOpenApiOperationQueryParameterTransformer;
import io.evitadb.externalApi.rest.api.model.PropertyDescriptorToOpenApiPropertyTransformer;
import io.evitadb.externalApi.rest.api.openApi.OpenApiEnum;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Locale;
import java.util.UUID;

import static io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor.SCALAR_ENUM;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiEnum.newEnum;

/**
 * Common ancestor for all {@link io.evitadb.externalApi.rest.api.Rest} builders.
 *
 * Builder should be mainly composed of `build...Property` and `build...Object` methods and so on.
 *
 * @see PartialRestBuilder
 * @see FinalRestBuilder
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public abstract class RestBuilder<C extends RestBuildingContext> {

	@Nonnull protected final PropertyDataTypeDescriptorToOpenApiTypeTransformer propertyDataTypeBuilderTransformer;
	@Nonnull protected final PropertyDescriptorToOpenApiPropertyTransformer propertyBuilderTransformer;
	@Nonnull protected final ObjectDescriptorToOpenApiObjectTransformer objectBuilderTransformer;
	@Nonnull protected final UnionDescriptorToOpenApiUnionTransformer unionBuilderTransformer;
	@Nonnull protected final ObjectDescriptorToOpenApiDictionaryTransformer dictionaryBuilderTransformer;
	@Nonnull protected final PropertyDescriptorToOpenApiOperationPathParameterTransformer operationPathParameterBuilderTransformer;
	@Nonnull protected final PropertyDescriptorToOpenApiOperationQueryParameterTransformer operationQueryParameterBuilderTransformer;

	@Nonnull
	protected final C buildingContext;

	protected RestBuilder(@Nonnull C buildingContext) {
		this.buildingContext = buildingContext;
		this.propertyDataTypeBuilderTransformer = new PropertyDataTypeDescriptorToOpenApiTypeTransformer(buildingContext);
		this.propertyBuilderTransformer = new PropertyDescriptorToOpenApiPropertyTransformer(this.propertyDataTypeBuilderTransformer);
		this.objectBuilderTransformer = new ObjectDescriptorToOpenApiObjectTransformer(this.propertyBuilderTransformer);
		this.unionBuilderTransformer = new UnionDescriptorToOpenApiUnionTransformer();
		this.dictionaryBuilderTransformer = new ObjectDescriptorToOpenApiDictionaryTransformer();
		this.operationPathParameterBuilderTransformer = new PropertyDescriptorToOpenApiOperationPathParameterTransformer(this.propertyDataTypeBuilderTransformer);
		this.operationQueryParameterBuilderTransformer = new PropertyDescriptorToOpenApiOperationQueryParameterTransformer(this.propertyDataTypeBuilderTransformer);
	}

	@Nonnull
	protected static OpenApiEnum buildScalarEnum() {
		final OpenApiEnum.Builder scalarEnumBuilder = newEnum()
			.name(SCALAR_ENUM.name())
			.description(SCALAR_ENUM.description());

		scalarEnumBuilder.item(DataTypeSerializer.serialize(String.class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(String[].class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(Byte.class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(Byte[].class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(Short.class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(Short[].class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(Integer.class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(Integer[].class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(Long.class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(Long[].class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(Boolean.class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(Boolean[].class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(Character.class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(Character[].class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(BigDecimal.class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(BigDecimal[].class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(OffsetDateTime.class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(OffsetDateTime[].class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(LocalDateTime.class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(LocalDateTime[].class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(LocalDate.class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(LocalDate[].class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(LocalTime.class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(LocalTime[].class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(DateTimeRange.class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(DateTimeRange[].class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(BigDecimalNumberRange.class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(BigDecimalNumberRange[].class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(ByteNumberRange.class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(ByteNumberRange[].class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(ShortNumberRange.class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(ShortNumberRange[].class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(IntegerNumberRange.class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(IntegerNumberRange[].class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(LongNumberRange.class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(LongNumberRange[].class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(Locale.class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(Locale[].class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(Currency.class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(Currency[].class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(UUID.class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(UUID[].class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(Predecessor.class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(ReferencedEntityPredecessor.class));
		scalarEnumBuilder.item(DataTypeSerializer.serialize(ComplexDataObject.class));

		return scalarEnumBuilder.build();
	}

	protected void registerMutations(@Nonnull ObjectDescriptor... mutationDescriptors) {
		for (final ObjectDescriptor mutationDescriptor : mutationDescriptors) {
			this.buildingContext.registerType(mutationDescriptor.to(this.objectBuilderTransformer).build());
		}
	}
}
