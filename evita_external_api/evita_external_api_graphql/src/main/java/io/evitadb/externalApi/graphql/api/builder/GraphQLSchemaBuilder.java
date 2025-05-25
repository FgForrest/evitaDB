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

package io.evitadb.externalApi.graphql.api.builder;

import graphql.schema.GraphQLEnumType;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.externalApi.dataType.DataTypeSerializer;
import io.evitadb.externalApi.graphql.api.model.EndpointDescriptorToGraphQLFieldTransformer;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLInputObjectTransformer;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLInterfaceTransformer;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLObjectTransformer;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLUnionTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDataTypeDescriptorToGraphQLTypeTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLArgumentTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLFieldTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLInputFieldTransformer;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Locale;
import java.util.UUID;

import static graphql.schema.GraphQLEnumType.newEnum;
import static io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor.ASSOCIATED_DATA_SCALAR_ENUM;
import static io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor.SCALAR_ENUM;

/**
 * Common ancestor for all {@link graphql.schema.GraphQLSchema} builders.
 *
 * Builder should be mainly composed of `build...Field` and `build...Object` methods starting by `build...Field` method because
 * fields are what clients see. Then, for those field backing objects are created. For those objects another fields are created
 * and so on.
 *
 * @see PartialGraphQLSchemaBuilder
 * @see FinalGraphQLSchemaBuilder
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public abstract class GraphQLSchemaBuilder<C extends GraphQLSchemaBuildingContext> {

	@Nonnull protected final PropertyDataTypeDescriptorToGraphQLTypeTransformer propertyDataTypeBuilderTransformer;
	@Nonnull protected final EndpointDescriptorToGraphQLFieldTransformer staticEndpointBuilderTransformer;
	@Nonnull protected final PropertyDescriptorToGraphQLArgumentTransformer argumentBuilderTransformer;
	@Nonnull protected final ObjectDescriptorToGraphQLInterfaceTransformer interfaceBuilderTransformer;
	@Nonnull protected final ObjectDescriptorToGraphQLObjectTransformer objectBuilderTransformer;
	@Nonnull protected final ObjectDescriptorToGraphQLUnionTransformer unionBuilderTransformer;
	@Nonnull protected final ObjectDescriptorToGraphQLInputObjectTransformer inputObjectBuilderTransformer;
	@Nonnull protected final PropertyDescriptorToGraphQLFieldTransformer fieldBuilderTransformer;
	@Nonnull protected final PropertyDescriptorToGraphQLInputFieldTransformer inputFieldBuilderTransformer;

	@Nonnull
	protected final C buildingContext;

	protected GraphQLSchemaBuilder(@Nonnull C buildingContext) {
		this.buildingContext = buildingContext;
		this.propertyDataTypeBuilderTransformer = new PropertyDataTypeDescriptorToGraphQLTypeTransformer(buildingContext);
		this.staticEndpointBuilderTransformer = new EndpointDescriptorToGraphQLFieldTransformer(this.propertyDataTypeBuilderTransformer);
		this.argumentBuilderTransformer = new PropertyDescriptorToGraphQLArgumentTransformer(this.propertyDataTypeBuilderTransformer);
		this.fieldBuilderTransformer = new PropertyDescriptorToGraphQLFieldTransformer(this.propertyDataTypeBuilderTransformer);
		this.inputFieldBuilderTransformer = new PropertyDescriptorToGraphQLInputFieldTransformer(this.propertyDataTypeBuilderTransformer);
		this.interfaceBuilderTransformer = new ObjectDescriptorToGraphQLInterfaceTransformer(this.fieldBuilderTransformer);
		this.objectBuilderTransformer = new ObjectDescriptorToGraphQLObjectTransformer(this.fieldBuilderTransformer);
		this.unionBuilderTransformer = new ObjectDescriptorToGraphQLUnionTransformer();
		this.inputObjectBuilderTransformer = new ObjectDescriptorToGraphQLInputObjectTransformer(this.inputFieldBuilderTransformer);
	}


	@Nonnull
	protected static GraphQLEnumType buildScalarEnum() {
		final GraphQLEnumType.Builder scalarBuilder = newEnum()
			.name(SCALAR_ENUM.name())
			.description(SCALAR_ENUM.description());

		registerScalarValue(scalarBuilder, String.class);
		registerScalarValue(scalarBuilder, String[].class);
		registerScalarValue(scalarBuilder, Byte.class);
		registerScalarValue(scalarBuilder, Byte[].class);
		registerScalarValue(scalarBuilder, Short.class);
		registerScalarValue(scalarBuilder, Short[].class);
		registerScalarValue(scalarBuilder, Integer.class);
		registerScalarValue(scalarBuilder, Integer[].class);
		registerScalarValue(scalarBuilder, Long.class);
		registerScalarValue(scalarBuilder, Long[].class);
		registerScalarValue(scalarBuilder, Boolean.class);
		registerScalarValue(scalarBuilder, Boolean[].class);
		registerScalarValue(scalarBuilder, Character.class);
		registerScalarValue(scalarBuilder, Character[].class);
		registerScalarValue(scalarBuilder, BigDecimal.class);
		registerScalarValue(scalarBuilder, BigDecimal[].class);
		registerScalarValue(scalarBuilder, OffsetDateTime.class);
		registerScalarValue(scalarBuilder, OffsetDateTime[].class);
		registerScalarValue(scalarBuilder, LocalDateTime.class);
		registerScalarValue(scalarBuilder, LocalDateTime[].class);
		registerScalarValue(scalarBuilder, LocalDate.class);
		registerScalarValue(scalarBuilder, LocalDate[].class);
		registerScalarValue(scalarBuilder, LocalTime.class);
		registerScalarValue(scalarBuilder, LocalTime[].class);
		registerScalarValue(scalarBuilder, DateTimeRange.class);
		registerScalarValue(scalarBuilder, DateTimeRange[].class);
		registerScalarValue(scalarBuilder, BigDecimalNumberRange.class);
		registerScalarValue(scalarBuilder, BigDecimalNumberRange[].class);
		registerScalarValue(scalarBuilder, ByteNumberRange.class);
		registerScalarValue(scalarBuilder, ByteNumberRange[].class);
		registerScalarValue(scalarBuilder, ShortNumberRange.class);
		registerScalarValue(scalarBuilder, ShortNumberRange[].class);
		registerScalarValue(scalarBuilder, IntegerNumberRange.class);
		registerScalarValue(scalarBuilder, IntegerNumberRange[].class);
		registerScalarValue(scalarBuilder, LongNumberRange.class);
		registerScalarValue(scalarBuilder, LongNumberRange[].class);
		registerScalarValue(scalarBuilder, Locale.class);
		registerScalarValue(scalarBuilder, Locale[].class);
		registerScalarValue(scalarBuilder, Currency.class);
		registerScalarValue(scalarBuilder, Currency[].class);
		registerScalarValue(scalarBuilder, UUID.class);
		registerScalarValue(scalarBuilder, UUID[].class);
		registerScalarValue(scalarBuilder, Predecessor.class);
		registerScalarValue(scalarBuilder, ReferencedEntityPredecessor.class);
		registerScalarValue(scalarBuilder, ComplexDataObject.class);
		registerScalarValue(scalarBuilder, ExpressionNode.class);

		return scalarBuilder.build();
	}

	@Nonnull
	protected static GraphQLEnumType buildAssociatedDataScalarEnum(@Nonnull GraphQLEnumType scalarEnum) {
		final GraphQLEnumType.Builder scalarBuilder = newEnum(scalarEnum)
			.name(ASSOCIATED_DATA_SCALAR_ENUM.name())
			.description(ASSOCIATED_DATA_SCALAR_ENUM.description());

		registerScalarValue(scalarBuilder, ComplexDataObject.class);

		return scalarBuilder.build();
	}

	private static void registerScalarValue(@Nonnull GraphQLEnumType.Builder scalarBuilder,
	                                        @Nonnull Class<? extends Serializable> javaType) {
		final String apiName = DataTypeSerializer.serialize(javaType);
		scalarBuilder.value(apiName, javaType);
	}
}
