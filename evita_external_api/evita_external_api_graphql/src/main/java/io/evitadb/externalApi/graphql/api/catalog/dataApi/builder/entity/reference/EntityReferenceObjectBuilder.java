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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.reference;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.attribute.AttributesProviderDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference.EntityReferenceDescriptor;
import io.evitadb.externalApi.graphql.api.builder.BuiltFieldDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.CollectionGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.AttributesDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.ReferenceGroupDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.ReferencedEntityDataFetcher;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLObjectTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLFieldTransformer;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Objects;

import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLTypeReference.typeRef;

/**
 * Builds reference object for a specific entity type from {@link EntityReferenceDescriptor}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class EntityReferenceObjectBuilder {

	@Nonnull private final CatalogGraphQLSchemaBuildingContext buildingContext;
	@Nonnull private final PropertyDescriptorToGraphQLFieldTransformer fieldBuilderTransformer;
	@Nonnull private final ObjectDescriptorToGraphQLObjectTransformer objectBuilderTransformer;

	@Nonnull private final ReferenceInterfaceBuilder referenceInterfaceBuilder;
	@Nonnull private final ReferenceWithReferencedEntityInterfaceBuilder referenceWithReferencedEntityInterfaceBuilder;
	@Nonnull private final ReferenceDefinitionInterfaceBuilder referenceDefinitionInterfaceBuilder;
	@Nonnull private final EntityReferenceAttributesObjectBuilder entityReferenceAttributesObjectBuilder;

	@Nonnull
	public GraphQLOutputType getOrBuild(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final EntityReferenceKey key = new EntityReferenceKey(
			collectionBuildingContext.getSchema().getName(),
			referenceSchema.getName()
		);

		return collectionBuildingContext.getOrComputeEntityReferenceObject(
			key,
			() -> {
				final String objectName = EntityReferenceDescriptor.THIS.name(
					collectionBuildingContext.getSchema(),
					referenceSchema
				);

				final String referencedGroupType = referenceSchema.getReferencedGroupType();
				final boolean hasAttributes = !referenceSchema.getAttributes().isEmpty();

				final GraphQLObjectType.Builder objectBuilder = EntityReferenceDescriptor.THIS
					.to(this.objectBuilderTransformer)
					.name(objectName)
					.description(constructDescription(referenceSchema));

				// add dynamic interfaces

				final GraphQLInterfaceType[] interfaces = new GraphQLInterfaceType[] {
					this.referenceInterfaceBuilder.getOrBuild(),
					this.referenceWithReferencedEntityInterfaceBuilder.getOrBuild(referenceSchema),
					this.referenceDefinitionInterfaceBuilder.getOrBuild(collectionBuildingContext, referenceSchema)
				};

				for (final GraphQLInterfaceType interfaceType : interfaces) {
					objectBuilder.withInterface(interfaceType);
				}

				// add overridden interface fields for the specific parent entity type

				objectBuilder.field(buildReferencedPrimaryKeyField());

				this.buildingContext.registerFieldToObject(
					objectName,
					objectBuilder,
					buildReferencedEntityField(referenceSchema)
				);

				if (referencedGroupType != null) {
					this.buildingContext.registerFieldToObject(
						objectName,
						objectBuilder,
						buildGroupField(referenceSchema)
					);
				}

				if (hasAttributes) {
					this.buildingContext.registerFieldToObject(
						objectName,
						objectBuilder,
						buildAttributesField(collectionBuildingContext, referenceSchema)
					);
				}

				return objectBuilder.build();
			}
		);
	}

	@Nonnull
	private GraphQLFieldDefinition buildReferencedPrimaryKeyField() {
		return EntityReferenceDescriptor.REFERENCED_PRIMARY_KEY
			.to(this.fieldBuilderTransformer)
			.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildReferencedEntityField(@Nonnull ReferenceSchemaContract referenceSchema) {
		final GraphQLOutputType referencedEntityObject;
		if (referenceSchema.isReferencedEntityTypeManaged()) {
			referencedEntityObject = typeRef(
				EntityDescriptor.THIS.name(
					this.buildingContext
						.getSchema()
						.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType())
				)
			);
		} else {
			referencedEntityObject = typeRef(EntityDescriptor.THIS_REFERENCE.name());
		}

		final GraphQLFieldDefinition referencedEntityField = EntityReferenceDescriptor.REFERENCED_ENTITY
			.to(this.fieldBuilderTransformer)
			.type(referencedEntityObject)
			.build();

		return new BuiltFieldDescriptor(
			referencedEntityField,
			ReferencedEntityDataFetcher.getInstance()
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildGroupField(@Nonnull ReferenceSchemaContract referenceSchema) {
		final GraphQLOutputType referencedEntityObject;
		if (referenceSchema.isReferencedGroupTypeManaged()) {
			referencedEntityObject = typeRef(
				EntityDescriptor.THIS.name(
					this.buildingContext
						.getSchema()
						.getEntitySchemaOrThrowException(Objects.requireNonNull(referenceSchema.getReferencedGroupType()))
				)
			);
		} else {
			referencedEntityObject = typeRef(EntityDescriptor.THIS_REFERENCE.name());
		}

		final GraphQLFieldDefinition referencedGroupField = EntityReferenceDescriptor.GROUP_ENTITY
			.to(this.fieldBuilderTransformer)
			.type(nonNull(referencedEntityObject))
			.build();

		return new BuiltFieldDescriptor(
			referencedGroupField,
			ReferenceGroupDataFetcher.getInstance()
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildAttributesField(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final GraphQLObjectType object = this.entityReferenceAttributesObjectBuilder.build(
			collectionBuildingContext,
			referenceSchema
		);

		final GraphQLFieldDefinition field = AttributesProviderDescriptor.ATTRIBUTES
			.to(this.fieldBuilderTransformer)
			.type(object)
			.build();

		return new BuiltFieldDescriptor(
			field,
			AttributesDataFetcher.getInstance()
		);
	}

	@Nullable
	private String constructDescription(@Nonnull ReferenceSchemaContract referenceSchema) {
		final String referencedEntityType = referenceSchema.getReferencedEntityType();
		final String referencedGroupType = referenceSchema.getReferencedGroupType();
		final boolean hasAttributes = !referenceSchema.getAttributes().isEmpty();

		if (referencedGroupType != null && hasAttributes) {
			return EntityReferenceDescriptor.THIS.description(
				referencedEntityType,
				" grouped by " + referencedGroupType + " with attributes."
			);
		} else if (referencedGroupType != null) {
			return EntityReferenceDescriptor.THIS.description(
				referencedEntityType,
				" grouped by " + referencedGroupType + "."
			);
		} else if (hasAttributes) {
			return EntityReferenceDescriptor.THIS.description(
				referencedEntityType,
				" with attributes."
			);
		} else {
			return EntityReferenceDescriptor.THIS.description(
				referencedEntityType,
				"."
			);
		}
	}
}
