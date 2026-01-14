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

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLObjectType.Builder;
import graphql.schema.GraphQLOutputType;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.attribute.AttributesDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.attribute.AttributesProviderDescriptor;
import io.evitadb.externalApi.graphql.api.builder.BuiltFieldDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.CollectionGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.EntityObjectBuilder.EntityObjectVariant;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.attribute.AttributeFieldBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.AttributesFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.AttributesDataFetcher;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLObjectTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLArgumentTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLFieldTransformer;
import io.evitadb.externalApi.graphql.exception.GraphQLSchemaBuildingError;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

import static graphql.schema.GraphQLTypeReference.typeRef;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.LOCALE_ENUM;

/**
 * Decorates entity objects with attribute fields
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class EntityObjectAttributeDecorator implements EntityObjectDecorator {

	@Nonnull private final CatalogGraphQLSchemaBuildingContext buildingContext;
	@Nonnull private final PropertyDescriptorToGraphQLArgumentTransformer argumentBuilderTransformer;
	@Nonnull private final PropertyDescriptorToGraphQLFieldTransformer fieldBuilderTransformer;
	@Nonnull private final ObjectDescriptorToGraphQLObjectTransformer objectBuilderTransformer;

	@Nonnull private final AttributeFieldBuilder attributeFieldBuilder;

	@Override
	public void decorate(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
		@Nonnull EntityObjectVariant variant,
		@Nonnull String entityObjectName,
		@Nonnull Builder entityObjectBuilder
	) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		if (!entitySchema.getAttributes().isEmpty()) {
			this.buildingContext.registerFieldToObject(
				entityObjectName,
				entityObjectBuilder,
				buildEntityAttributesField(collectionBuildingContext, variant)
			);
		}
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityAttributesField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
		@Nonnull EntityObjectVariant version) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();
		final GraphQLOutputType attributesObject = switch (version) {
			case DEFAULT -> buildAttributesObject(entitySchema);
			case NON_HIERARCHICAL -> typeRef(AttributesDescriptor.THIS.name(entitySchema));
			default -> throw new GraphQLSchemaBuildingError("Unsupported version `" + version + "`.");
		};

		final GraphQLFieldDefinition.Builder attributesFieldBuilder = AttributesProviderDescriptor.ATTRIBUTES
			.to(this.fieldBuilderTransformer)
			.type(attributesObject);

		if (!entitySchema.getLocales().isEmpty()) {
			attributesFieldBuilder.argument(
				AttributesFieldHeaderDescriptor.LOCALE
					.to(this.argumentBuilderTransformer)
					.type(typeRef(LOCALE_ENUM.name()))
			);
		}

		return new BuiltFieldDescriptor(
			attributesFieldBuilder.build(),
			AttributesDataFetcher.getInstance()
		);
	}

	@Nonnull
	private GraphQLObjectType buildAttributesObject(@Nonnull EntitySchemaContract entitySchema) {
		final String objectName = AttributesDescriptor.THIS.name(entitySchema);

		final GraphQLObjectType.Builder attributesBuilder = AttributesDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(objectName);

		entitySchema.getAttributes().values().forEach(
			attributeSchema ->
				this.buildingContext.registerFieldToObject(
					objectName,
					attributesBuilder,
					this.attributeFieldBuilder.buildFieldDescriptor(attributeSchema, true)
				)
		);

		return attributesBuilder.build();
	}


}
