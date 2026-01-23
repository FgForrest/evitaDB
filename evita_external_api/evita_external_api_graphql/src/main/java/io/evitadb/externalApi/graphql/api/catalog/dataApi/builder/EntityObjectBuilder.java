/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.*;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.FilterConstraintSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.GraphQLConstraintSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.OrderConstraintSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.EntityObjectAssociatedDataDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.EntityObjectAttributeDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.EntityObjectDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.EntityObjectHierarchyDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.EntityObjectLocaleDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.EntityObjectPriceDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.EntityObjectReferenceDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.attribute.AttributeFieldBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.reference.*;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GraphQLEntityDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.*;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.EntityDtoTypeResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.*;
import io.evitadb.externalApi.graphql.api.catalog.resolver.dataFetcher.MappingTypeResolver.RegistryKey;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLInterfaceTransformer;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLObjectTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLArgumentTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLFieldTransformer;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.HelperInterfaceTypeResolver;
import io.evitadb.externalApi.graphql.exception.GraphQLSchemaBuildingError;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Builds object representing specific {@link io.evitadb.api.requestResponse.data.EntityContract} of specific collection.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class EntityObjectBuilder {

	public static final RegistryKey<String> ENTITY_DTO_TYPE_RESOLVER_REGISTRY_KEY = new RegistryKey<>();

	@Nonnull private final CatalogGraphQLSchemaBuildingContext buildingContext;
	@Nonnull private final ObjectDescriptorToGraphQLInterfaceTransformer interfaceBuilderTransformer;
	@Nonnull private final ObjectDescriptorToGraphQLObjectTransformer objectBuilderTransformer;

	@Nonnull private final List<EntityObjectDecorator> entityObjectDecorators;

	public EntityObjectBuilder(
		@Nonnull CatalogGraphQLSchemaBuildingContext buildingContext,
		@Nonnull GraphQLConstraintSchemaBuildingContext constraintSchemaBuildingContext,
		@Nonnull FilterConstraintSchemaBuilder filterConstraintSchemaBuilder,
		@Nonnull OrderConstraintSchemaBuilder orderConstraintSchemaBuilder,
		@Nonnull ObjectMapper cdoObjectMapper,
		@Nonnull PropertyDescriptorToGraphQLArgumentTransformer argumentBuilderTransformer,
		@Nonnull ObjectDescriptorToGraphQLInterfaceTransformer interfaceBuilderTransformer,
		@Nonnull ObjectDescriptorToGraphQLObjectTransformer objectBuilderTransformer,
		@Nonnull PropertyDescriptorToGraphQLFieldTransformer fieldBuilderTransformer
	) {
		this.buildingContext = buildingContext;
		this.interfaceBuilderTransformer = interfaceBuilderTransformer;
		this.objectBuilderTransformer = objectBuilderTransformer;

		final AttributeFieldBuilder attributeFieldBuilder = new AttributeFieldBuilder(argumentBuilderTransformer);
		final ReferenceInterfaceBuilder referenceInterfaceBuilder = new ReferenceInterfaceBuilder(
			buildingContext,
			interfaceBuilderTransformer
		);
		final ReferenceWithReferencedEntityInterfaceBuilder referenceWithReferencedEntityInterfaceBuilder = new ReferenceWithReferencedEntityInterfaceBuilder(
			buildingContext,
			interfaceBuilderTransformer,
			fieldBuilderTransformer
		);
		final ReferenceDefinitionAttributesInterfaceBuilder referenceDefinitionAttributesInterfaceBuilder = new ReferenceDefinitionAttributesInterfaceBuilder(
			buildingContext,
			interfaceBuilderTransformer,
			attributeFieldBuilder
		);
		final ReferenceDefinitionInterfaceBuilder referenceDefinitionInterfaceBuilder = new ReferenceDefinitionInterfaceBuilder(
			buildingContext,
			interfaceBuilderTransformer,
			fieldBuilderTransformer,
			referenceInterfaceBuilder,
			referenceWithReferencedEntityInterfaceBuilder,
			referenceDefinitionAttributesInterfaceBuilder
		);

		final EntityReferenceAttributesObjectBuilder entityReferenceAttributesObjectBuilder = new EntityReferenceAttributesObjectBuilder(
			buildingContext,
			objectBuilderTransformer,
			attributeFieldBuilder,
			referenceDefinitionAttributesInterfaceBuilder
		);
		final EntityReferenceObjectBuilder entityReferenceObjectBuilder = new EntityReferenceObjectBuilder(
			buildingContext,
			fieldBuilderTransformer,
			objectBuilderTransformer,
			referenceInterfaceBuilder,
			referenceWithReferencedEntityInterfaceBuilder,
			referenceDefinitionInterfaceBuilder,
			entityReferenceAttributesObjectBuilder
		);

		final ReferencePageInterfaceBuilder referencePageInterfaceBuilder = new ReferencePageInterfaceBuilder(
			buildingContext,
			interfaceBuilderTransformer
		);
		final ReferenceWithReferencedEntityPageInterfaceBuilder referenceWithReferencedEntityPageInterfaceBuilder = new ReferenceWithReferencedEntityPageInterfaceBuilder(
			buildingContext,
			interfaceBuilderTransformer,
			fieldBuilderTransformer,
			referencePageInterfaceBuilder,
			referenceWithReferencedEntityInterfaceBuilder
		);
		final ReferenceDefinitionPageInterfaceBuilder referenceDefinitionPageInterfaceBuilder = new ReferenceDefinitionPageInterfaceBuilder(
			buildingContext,
			interfaceBuilderTransformer,
			fieldBuilderTransformer,
			referencePageInterfaceBuilder,
			referenceWithReferencedEntityPageInterfaceBuilder,
			referenceDefinitionInterfaceBuilder
		);
		final EntityReferencePageObjectBuilder entityReferencePageObjectBuilder = new EntityReferencePageObjectBuilder(
			objectBuilderTransformer,
			fieldBuilderTransformer,
			referencePageInterfaceBuilder,
			referenceWithReferencedEntityPageInterfaceBuilder,
			referenceDefinitionPageInterfaceBuilder,
			entityReferenceObjectBuilder
		);

		final WithNamedReferenceInterfaceBuilder withNamedReferenceInterfaceBuilder = new WithNamedReferenceInterfaceBuilder(
			buildingContext,
			filterConstraintSchemaBuilder,
			orderConstraintSchemaBuilder,
			argumentBuilderTransformer,
			interfaceBuilderTransformer,
			fieldBuilderTransformer,
			referenceDefinitionInterfaceBuilder,
			referenceDefinitionPageInterfaceBuilder
		);

		this.entityObjectDecorators = List.of(
			new EntityObjectLocaleDecorator(fieldBuilderTransformer),
			new EntityObjectHierarchyDecorator(
				buildingContext,
				constraintSchemaBuildingContext,
				filterConstraintSchemaBuilder,
				argumentBuilderTransformer,
				fieldBuilderTransformer
			),
			new EntityObjectAttributeDecorator(
				buildingContext,
				argumentBuilderTransformer,
				fieldBuilderTransformer,
				objectBuilderTransformer,
				attributeFieldBuilder
			),
			new EntityObjectAssociatedDataDecorator(
				buildingContext,
				argumentBuilderTransformer,
				objectBuilderTransformer,
				fieldBuilderTransformer,
				cdoObjectMapper
			),
			new EntityObjectPriceDecorator(
				buildingContext,
				argumentBuilderTransformer,
				objectBuilderTransformer,
				fieldBuilderTransformer
			),
			new EntityObjectReferenceDecorator(
				buildingContext,
				interfaceBuilderTransformer,
				withNamedReferenceInterfaceBuilder,
				entityReferenceObjectBuilder,
				entityReferencePageObjectBuilder
			)
		);
	}

	public void buildCommonTypes() {
		final GraphQLInterfaceType entityClassifier = EntityDescriptor.THIS_CLASSIFIER.to(this.interfaceBuilderTransformer).build();
		this.buildingContext.registerType(entityClassifier);
		this.buildingContext.addMappingTypeResolver(
			entityClassifier,
			ENTITY_DTO_TYPE_RESOLVER_REGISTRY_KEY,
			new EntityDtoTypeResolver(this.buildingContext.getEntitySchemas().size())
		);
		this.buildingContext.registerType(EntityDescriptor.THIS_REFERENCE.to(this.objectBuilderTransformer).build());

		this.entityObjectDecorators.forEach(EntityObjectDecorator::prepare);
	}

	@Nonnull
	public GraphQLObjectType build(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		return build(collectionBuildingContext, EntityObjectVariant.DEFAULT);
	}

	@Nonnull
	public GraphQLObjectType build(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
		@Nonnull EntityObjectVariant variant
	) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		// build specific entity object
		final ObjectDescriptor entityDescriptor = switch (variant) {
			case DEFAULT -> EntityDescriptor.THIS;
			case NON_HIERARCHICAL -> GraphQLEntityDescriptor.THIS_NON_HIERARCHICAL;
			default -> throw new GraphQLSchemaBuildingError("Unsupported version `" + variant + "`.");
		};
		final String objectName = entityDescriptor.name(entitySchema);
		final GraphQLObjectType.Builder entityObjectBuilder = entityDescriptor
			.to(this.objectBuilderTransformer)
			.name(objectName)
			.description(entitySchema.getDescription());

		// decorate the entity object with dynamic structure
		this.entityObjectDecorators.forEach(decorator -> decorator.decorate(
			collectionBuildingContext,
			variant,
			objectName,
			entityObjectBuilder
		));

		return entityObjectBuilder.build();
	}

	/**
	 * Defines if entity object will have all possible fields for specified schema or there will be some restrictions.
	 */
	public enum EntityObjectVariant {
		/**
		 * Full entity object with all possible fields.
		 */
		DEFAULT,
		/**
		 * Restricted entity object which is same as {@link #DEFAULT} only without list of parent entities so that it
		 * cannot form recursive structure.
		 */
		NON_HIERARCHICAL
	}
}
