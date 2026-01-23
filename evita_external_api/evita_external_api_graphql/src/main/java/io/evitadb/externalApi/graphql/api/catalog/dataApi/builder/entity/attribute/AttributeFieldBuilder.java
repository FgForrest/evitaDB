/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.attribute;

import graphql.schema.DataFetcher;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLOutputType;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.externalApi.graphql.api.builder.BuiltFieldDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.NonNullBigDecimalFieldDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.NullableBigDecimalFieldDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.BigDecimalDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.AttributeValueDataFetcher;
import io.evitadb.externalApi.graphql.api.dataType.DataTypesConverter;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLArgumentTransformer;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;

import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;

/**
 * Builds GraphQL field definition for a single {@link AttributeSchemaContract attribute}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class AttributeFieldBuilder {

	@Nonnull private final PropertyDescriptorToGraphQLArgumentTransformer argumentBuilderTransformer;

	@Nonnull
	public GraphQLFieldDefinition buildFieldDefinition(
		@Nonnull AttributeSchemaContract attributeSchema,
		boolean canBeRequired
 	) {
		final GraphQLFieldDefinition.Builder attributeFieldBuilder = newFieldDefinition()
			.name(attributeSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
			.description(attributeSchema.getDescription())
			.deprecate(attributeSchema.getDeprecationNotice());

		final Class<? extends Serializable> attributeType = attributeSchema.getType();
		if (BigDecimal.class.isAssignableFrom(attributeType)) {
			if (attributeSchema.isNullable() || !canBeRequired) {
				new NullableBigDecimalFieldDecorator(this.argumentBuilderTransformer).accept(attributeFieldBuilder);
			} else {
				new NonNullBigDecimalFieldDecorator(this.argumentBuilderTransformer).accept(attributeFieldBuilder);
			}

		} else {
			attributeFieldBuilder.type(
				(GraphQLOutputType) DataTypesConverter.getGraphQLScalarType(attributeType, canBeRequired && !attributeSchema.isNullable())
			);
		}

		return attributeFieldBuilder.build();
	}

	@Nonnull
	public BuiltFieldDescriptor buildFieldDescriptor(
		@Nonnull AttributeSchemaContract attributeSchema,
		boolean canBeRequired
	) {
		final GraphQLFieldDefinition attributeField = buildFieldDefinition(attributeSchema, canBeRequired);

		final DataFetcher<?> attributeFieldDataFetcher;
		if (BigDecimal.class.isAssignableFrom(attributeSchema.getType())) {
			attributeFieldDataFetcher = new BigDecimalDataFetcher(new AttributeValueDataFetcher<>(attributeSchema));
		} else {
			attributeFieldDataFetcher = new AttributeValueDataFetcher<>(attributeSchema);
		}

		return new BuiltFieldDescriptor(
			attributeField,
			attributeFieldDataFetcher
		);
	}
}
