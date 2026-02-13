/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLObjectType.Builder;
import graphql.schema.GraphQLOutputType;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.AssociatedDataDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.graphql.api.builder.BuiltFieldDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.CollectionGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.EntityObjectBuilder.EntityObjectVariant;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.NonNullBigDecimalFieldDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.NullableBigDecimalFieldDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.AssociatedDataFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.BigDecimalDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.AssociatedDataDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.AssociatedDataValueDataFetcher;
import io.evitadb.externalApi.graphql.api.dataType.DataTypesConverter;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLObjectTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLArgumentTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLFieldTransformer;
import io.evitadb.externalApi.graphql.exception.GraphQLSchemaBuildingError;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;

import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLTypeReference.typeRef;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.PROPERTY_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.LOCALE_ENUM;

/**
 * Decorates entity objects with associated data fields.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class EntityObjectAssociatedDataDecorator implements EntityObjectDecorator {

	@Nonnull private final CatalogGraphQLSchemaBuildingContext buildingContext;
	@Nonnull private final PropertyDescriptorToGraphQLArgumentTransformer argumentBuilderTransformer;
	@Nonnull private final ObjectDescriptorToGraphQLObjectTransformer objectBuilderTransformer;
	@Nonnull private final PropertyDescriptorToGraphQLFieldTransformer fieldBuilderTransformer;
	@Nonnull private final ObjectMapper cdoObjectMapper;

	@Override
	public void decorate(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
		@Nonnull EntityObjectVariant variant,
		@Nonnull String entityObjectName,
		@Nonnull Builder entityObjectBuilder
	) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		if (!entitySchema.getAssociatedData().isEmpty()) {
			this.buildingContext.registerFieldToObject(
				entityObjectName,
				entityObjectBuilder,
				buildEntityAssociatedDataField(collectionBuildingContext, variant)
			);
		}
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityAssociatedDataField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
		@Nonnull EntityObjectVariant version) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();
		final GraphQLOutputType associatedDataObject = switch (version) {
			case DEFAULT -> buildAssociatedDataObject(collectionBuildingContext);
			case NON_HIERARCHICAL -> typeRef(AssociatedDataDescriptor.THIS.name(collectionBuildingContext.getSchema()));
			default -> throw new GraphQLSchemaBuildingError("Unsupported version `" + version + "`.");
		};

		final GraphQLFieldDefinition.Builder associatedDataFieldBuilder = EntityDescriptor.ASSOCIATED_DATA
			.to(this.fieldBuilderTransformer)
			.type(associatedDataObject);

		if (!entitySchema.getLocales().isEmpty()) {
			associatedDataFieldBuilder.argument(
				AssociatedDataFieldHeaderDescriptor.LOCALE
					.to(this.argumentBuilderTransformer)
					.type(typeRef(LOCALE_ENUM.name()))
			);
		}

		return new BuiltFieldDescriptor(
			associatedDataFieldBuilder.build(),
			AssociatedDataDataFetcher.getInstance()
		);
	}

	@Nonnull
	private GraphQLObjectType buildAssociatedDataObject(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final String objectName = AssociatedDataDescriptor.THIS.name(collectionBuildingContext.getSchema());
		final GraphQLObjectType.Builder associatedDataBuilder = AssociatedDataDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(objectName);

		collectionBuildingContext.getSchema().getAssociatedData().values().forEach(
			associatedDataSchema ->
				this.buildingContext.registerFieldToObject(
					objectName,
					associatedDataBuilder,
					buildSingleAssociatedDataField(associatedDataSchema)
				)
		);

		return associatedDataBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildSingleAssociatedDataField(@Nonnull AssociatedDataSchemaContract associatedDataSchema) {
		final GraphQLFieldDefinition.Builder associatedDataFieldBuilder = newFieldDefinition();
		final DataFetcher<?> associatedDataFieldDataFetcher;

		final Class<? extends Serializable> associatedDataType = associatedDataSchema.getType();
		if (BigDecimal.class.isAssignableFrom(associatedDataType)) {
			if (associatedDataSchema.isNullable()) {
				new NullableBigDecimalFieldDecorator(this.argumentBuilderTransformer).accept(associatedDataFieldBuilder);
			} else {
				new NonNullBigDecimalFieldDecorator(this.argumentBuilderTransformer).accept(associatedDataFieldBuilder);
			}

			associatedDataFieldDataFetcher = new BigDecimalDataFetcher(
				new AssociatedDataValueDataFetcher<>(this.cdoObjectMapper, associatedDataSchema)
			);
		} else {
			associatedDataFieldBuilder.type(
				(GraphQLOutputType) DataTypesConverter.getGraphQLScalarType(associatedDataType, !associatedDataSchema.isNullable())
			);
			associatedDataFieldDataFetcher = new AssociatedDataValueDataFetcher<>(this.cdoObjectMapper, associatedDataSchema);
		}

		associatedDataFieldBuilder
			.name(associatedDataSchema.getNameVariant(PROPERTY_NAME_NAMING_CONVENTION))
			.description(associatedDataSchema.getDescription())
			.deprecate(associatedDataSchema.getDeprecationNotice());

		return new BuiltFieldDescriptor(
			associatedDataFieldBuilder.build(),
			associatedDataFieldDataFetcher
		);
	}
}
