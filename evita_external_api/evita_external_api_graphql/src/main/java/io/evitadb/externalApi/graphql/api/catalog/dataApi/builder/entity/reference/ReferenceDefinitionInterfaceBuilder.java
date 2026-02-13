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
import graphql.schema.GraphQLOutputType;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.attribute.AttributesProviderDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference.ReferenceDefinitionDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.CollectionGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLInterfaceTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLFieldTransformer;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLTypeReference.typeRef;

/**
 * Builds interfaces from {@link ReferenceDefinitionDescriptor} for a specific reference definition.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class ReferenceDefinitionInterfaceBuilder {

	@Nonnull private final CatalogGraphQLSchemaBuildingContext buildingContext;
	@Nonnull private final ObjectDescriptorToGraphQLInterfaceTransformer interfaceBuilderTransformer;
	@Nonnull private final PropertyDescriptorToGraphQLFieldTransformer fieldBuilderTransformer;

	@Nonnull private final ReferenceInterfaceBuilder referenceInterfaceBuilder;
	@Nonnull private final ReferenceWithReferencedEntityInterfaceBuilder referenceWithReferencedEntityInterfaceBuilder;
	@Nonnull private final ReferenceDefinitionAttributesInterfaceBuilder referenceDefinitionAttributesInterfaceBuilder;

	@Nonnull
	public GraphQLInterfaceType getOrBuild(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final ReferenceDefinitionKey key = new ReferenceDefinitionKey(
			referenceSchema,
			collectionBuildingContext.getSchema().getName(),
			referenceSchema.getName()
		);

		return this.buildingContext.getOrComputeReferenceDefinitionInterface(
			key,
			() -> {
				final String interfaceName = ReferenceDefinitionDescriptor.THIS_INTERFACE.name(
					referenceSchema.getReferencedEntityType(),
					Long.toHexString(key.toHash())
				);

				final GraphQLInterfaceType.Builder interfaceBuilder = ReferenceDefinitionDescriptor.THIS_INTERFACE
					.to(this.interfaceBuilderTransformer)
					.name(interfaceName)
					.description(constructDescription(referenceSchema));

				// add dynamic interfaces and their fields

				final GraphQLInterfaceType[] interfaces = new GraphQLInterfaceType[] {
					this.referenceInterfaceBuilder.getOrBuild(),
					this.referenceWithReferencedEntityInterfaceBuilder.getOrBuild(referenceSchema)
				};
				for (final GraphQLInterfaceType interfaceType : interfaces) {
					interfaceBuilder.withInterface(interfaceType);
					for (final GraphQLFieldDefinition field : interfaceType.getFieldDefinitions()) {
						interfaceBuilder.field(field);
					}
				}

				// add custom fields

				if (referenceSchema.getReferencedGroupType() != null) {
					interfaceBuilder.field(
						buildGroupField(referenceSchema)
					);
				}

				if (!referenceSchema.getAttributes().isEmpty()) {
					interfaceBuilder.field(
						buildAttributesField(collectionBuildingContext, referenceSchema)
					);
				}

				return interfaceBuilder.build();
			}
		);
	}

	@Nullable
	private String constructDescription(@Nonnull ReferenceSchemaContract referenceSchema) {
		final String referencedEntityType = referenceSchema.getReferencedEntityType();
		final String referencedGroupType = referenceSchema.getReferencedGroupType();
		final boolean hasAttributes = !referenceSchema.getAttributes().isEmpty();

		if (referencedGroupType != null && hasAttributes) {
			return ReferenceDefinitionDescriptor.THIS_INTERFACE.description(
				referencedEntityType,
				" grouped by " + referencedGroupType + " with attributes."
			);
		} else if (referencedGroupType != null) {
			return ReferenceDefinitionDescriptor.THIS_INTERFACE.description(
				referencedEntityType,
				" grouped by " + referencedGroupType + "."
			);
		} else if (hasAttributes) {
			return ReferenceDefinitionDescriptor.THIS_INTERFACE.description(
				referencedEntityType,
				" with attributes."
			);
		} else {
			return ReferenceDefinitionDescriptor.THIS_INTERFACE.description(
				referencedEntityType,
				"."
			);
		}
	}

	@Nonnull
	private GraphQLFieldDefinition buildGroupField(@Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract referencedEntitySchema;
		if (referenceSchema.isReferencedGroupTypeManaged()) {
			referencedEntitySchema = this.buildingContext
				.getSchema()
				.getEntitySchemaOrThrowException(Objects.requireNonNull(referenceSchema.getReferencedGroupType()));
		} else {
			referencedEntitySchema = null;
		}
		final GraphQLOutputType referencedEntityObject = buildGroupObject(referencedEntitySchema);

		return ReferenceDefinitionDescriptor.GROUP_ENTITY
			.to(this.fieldBuilderTransformer)
			.type(nonNull(referencedEntityObject))
			.build();
	}

	@Nonnull
	private static GraphQLOutputType buildGroupObject(@Nullable EntitySchemaContract referencedEntitySchema) {
		if (referencedEntitySchema != null) {
			return typeRef(EntityDescriptor.THIS.name(referencedEntitySchema));
		} else {
			return typeRef(EntityDescriptor.THIS_REFERENCE.name());
		}
	}

	@Nonnull
	private GraphQLFieldDefinition buildAttributesField(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final GraphQLInterfaceType attributesInterface = this.referenceDefinitionAttributesInterfaceBuilder.getOrBuild(
			collectionBuildingContext,
			referenceSchema
		);

		return AttributesProviderDescriptor.ATTRIBUTES
			.to(this.fieldBuilderTransformer)
			.type(attributesInterface)
			.build();
	}

}
