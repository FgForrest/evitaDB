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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.builder;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLType;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.attribute.AttributesDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.attribute.AttributesProviderDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.graphql.api.builder.BuiltFieldDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.attribute.AttributeFieldBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GlobalEntityDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.AttributesFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.AttributesDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.TargetEntityDataFetcher;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLObjectTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLArgumentTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLFieldTransformer;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLTypeReference.typeRef;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.LOCALE_ENUM;

/**
 * Builds global entity object.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class GlobalEntityObjectBuilder {

	@Nonnull private final CatalogGraphQLSchemaBuildingContext buildingContext;
	@Nonnull private final PropertyDescriptorToGraphQLArgumentTransformer argumentBuilderTransformer;
	@Nonnull private final ObjectDescriptorToGraphQLObjectTransformer objectBuilderTransformer;
	@Nonnull private final PropertyDescriptorToGraphQLFieldTransformer fieldBuilderTransformer;

	@Nonnull private final AttributeFieldBuilder attributeFieldBuilder;

	@Nonnull
	public GraphQLObjectType build() {
		final CatalogSchemaContract catalogSchema = this.buildingContext.getSchema();

		final GraphQLObjectType.Builder globalEntityObjectBuilder = GlobalEntityDescriptor.THIS.to(this.objectBuilderTransformer);

		if (!this.buildingContext.getSupportedLocales().isEmpty()) {
			globalEntityObjectBuilder.field(EntityDescriptor.LOCALES.to(this.fieldBuilderTransformer));
			globalEntityObjectBuilder.field(EntityDescriptor.ALL_LOCALES.to(this.fieldBuilderTransformer));
		}

		if (!catalogSchema.getAttributes().isEmpty()) {
			this.buildingContext.registerFieldToObject(
				GlobalEntityDescriptor.THIS,
				globalEntityObjectBuilder,
				buildGlobalEntityAttributesField()
			);
		}

		this.buildingContext.registerDataFetcher(
			GlobalEntityDescriptor.THIS,
			GlobalEntityDescriptor.TARGET_ENTITY,
			TargetEntityDataFetcher.getInstance()
		);

		return globalEntityObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildGlobalEntityAttributesField() {
		final CatalogSchemaContract catalogSchema = this.buildingContext.getSchema();
		final GraphQLType attributesObject = buildAttributesObject(catalogSchema);

		final GraphQLFieldDefinition.Builder attributesFieldBuilder = AttributesProviderDescriptor.ATTRIBUTES
			.to(this.fieldBuilderTransformer)
			.type(nonNull(attributesObject));

		if (!this.buildingContext.getSupportedLocales().isEmpty()) {
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
	private GraphQLObjectType buildAttributesObject(@Nonnull CatalogSchemaContract catalogSchema) {
		final String objectName = AttributesDescriptor.THIS_GLOBAL.name();

		final GraphQLObjectType.Builder attributesBuilder = AttributesDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(objectName);

		catalogSchema.getAttributes().values().forEach(
			attributeSchema ->
				this.buildingContext.registerFieldToObject(
					objectName,
					attributesBuilder,
					this.attributeFieldBuilder.buildFieldDescriptor(attributeSchema, false)
				)
		);

		return attributesBuilder.build();
	}
}
