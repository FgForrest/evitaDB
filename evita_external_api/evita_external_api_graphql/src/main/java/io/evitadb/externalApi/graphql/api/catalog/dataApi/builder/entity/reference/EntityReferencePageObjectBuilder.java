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
import graphql.schema.GraphQLTypeReference;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.DataChunkDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PaginatedListDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference.EntityReferencePageDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference.ReferenceDefinitionPageDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.CollectionGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLObjectTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLFieldTransformer;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static graphql.schema.GraphQLList.list;
import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLTypeReference.typeRef;

/**
 * Builds objects from {@link io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference.EntityReferencePageDescriptor}
 * for a specific entity type.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class EntityReferencePageObjectBuilder {

	@Nonnull private final ObjectDescriptorToGraphQLObjectTransformer objectBuilderTransformer;
	@Nonnull private final PropertyDescriptorToGraphQLFieldTransformer fieldBuilderTransformer;

	@Nonnull private final ReferencePageInterfaceBuilder referencePageInterfaceBuilder;
	@Nonnull private final ReferenceWithReferencedEntityPageInterfaceBuilder referenceWithReferencedEntityPageInterfaceBuilder;
	@Nonnull private final ReferenceDefinitionPageInterfaceBuilder referenceDefinitionPageInterfaceBuilder;
	@Nonnull private final EntityReferenceObjectBuilder entityReferenceObjectBuilder;

	@Nonnull
	public GraphQLObjectType build(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final String objectName = EntityReferencePageDescriptor.THIS.name(
			collectionBuildingContext.getSchema(),
			referenceSchema
		);

		final GraphQLObjectType.Builder objectBuilder = EntityReferencePageDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(objectName)
			.description(constructDescription(referenceSchema));

		// add dynamic interfaces and their fields

		objectBuilder.withInterface(typeRef(DataChunkDescriptor.THIS_INTERFACE.name()));
		objectBuilder.withInterface(typeRef(PaginatedListDescriptor.THIS_INTERFACE.name()));

		final GraphQLInterfaceType[] interfaces = new GraphQLInterfaceType[] {
			this.referencePageInterfaceBuilder.getOrBuild(),
			this.referenceWithReferencedEntityPageInterfaceBuilder.getOrBuild(referenceSchema),
			this.referenceDefinitionPageInterfaceBuilder.getOrBuild(collectionBuildingContext, referenceSchema)
		};

		for (final GraphQLInterfaceType interfaceType : interfaces) {
			objectBuilder.withInterface(interfaceType);
			for (final GraphQLFieldDefinition field : interfaceType.getFieldDefinitions()) {
				objectBuilder.field(field);
			}
		}

		// add custom fields

		objectBuilder.field(
			buildDataField(collectionBuildingContext, referenceSchema)
		);

		return objectBuilder.build();
	}

	@Nullable
	private String constructDescription(@Nonnull ReferenceSchemaContract referenceSchema) {
		final String referencedEntityType = referenceSchema.getReferencedEntityType();
		final String referencedGroupType = referenceSchema.getReferencedGroupType();
		final boolean hasAttributes = !referenceSchema.getAttributes().isEmpty();

		if (referencedGroupType != null && hasAttributes) {
			return ReferenceDefinitionPageDescriptor.THIS_INTERFACE.description(
				referencedEntityType,
				" grouped by " + referencedGroupType + " with attributes"
			);
		} else if (referencedGroupType != null) {
			return ReferenceDefinitionPageDescriptor.THIS_INTERFACE.description(
				referencedEntityType,
				" grouped by " + referencedGroupType
			);
		} else if (hasAttributes) {
			return ReferenceDefinitionPageDescriptor.THIS_INTERFACE.description(
				referencedEntityType,
				" with attributes"
			);
		} else {
			return ReferenceDefinitionPageDescriptor.THIS_INTERFACE.description(
				referencedEntityType,
				""
			);
		}
	}

	@Nonnull
	private GraphQLFieldDefinition buildDataField(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final GraphQLOutputType entityReferenceObject = this.entityReferenceObjectBuilder.getOrBuild(
			collectionBuildingContext,
			referenceSchema
		);

		return ReferenceDefinitionPageDescriptor.DATA
			.to(this.fieldBuilderTransformer)
			.type(nonNull(list(nonNull(entityReferenceObject))))
			.build();
	}
}
