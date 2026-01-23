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
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.DataChunkDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PaginatedListDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference.ReferenceDefinitionPageDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.CollectionGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLInterfaceTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLFieldTransformer;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static graphql.schema.GraphQLList.list;
import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLTypeReference.typeRef;

/**
 * Builds interfaces from {@link ReferenceDefinitionPageDescriptor} for a specific reference definition.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class ReferenceDefinitionPageInterfaceBuilder {

	@Nonnull private final CatalogGraphQLSchemaBuildingContext buildingContext;
	@Nonnull private final ObjectDescriptorToGraphQLInterfaceTransformer interfaceBuilderTransformer;
	@Nonnull private final PropertyDescriptorToGraphQLFieldTransformer fieldBuilderTransformer;

	@Nonnull private final ReferencePageInterfaceBuilder referencePageInterfaceBuilder;
	@Nonnull private final ReferenceWithReferencedEntityPageInterfaceBuilder referenceWithReferencedEntityPageInterfaceBuilder;
	@Nonnull private final ReferenceDefinitionInterfaceBuilder referenceDefinitionInterfaceBuilder;

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

		return this.buildingContext.getOrComputeReferenceDefinitionPageInterface(
			key,
			() -> {
				final String interfaceName = ReferenceDefinitionPageDescriptor.THIS_INTERFACE.name(
					referenceSchema.getReferencedEntityType(),
					Long.toHexString(key.toHash())
				);

				final GraphQLInterfaceType.Builder interfaceBuilder = ReferenceDefinitionPageDescriptor.THIS_INTERFACE
					.to(this.interfaceBuilderTransformer)
					.name(interfaceName)
					.description(constructDescription(referenceSchema));

				// add dynamic interfaces and their fields

				interfaceBuilder.withInterface(typeRef(DataChunkDescriptor.THIS_INTERFACE.name()));
				interfaceBuilder.withInterface(typeRef(PaginatedListDescriptor.THIS_INTERFACE.name()));

				final GraphQLInterfaceType[] interfaces = new GraphQLInterfaceType[] {
					this.referencePageInterfaceBuilder.getOrBuild(),
					this.referenceWithReferencedEntityPageInterfaceBuilder.getOrBuild(referenceSchema)
				};

				for (final GraphQLInterfaceType interfaceType : interfaces) {
					interfaceBuilder.withInterface(interfaceType);
					for (final GraphQLFieldDefinition field : interfaceType.getFieldDefinitions()) {
						interfaceBuilder.field(field);
					}
				}

				// add custom fields

				interfaceBuilder.field(
					buildDataField(collectionBuildingContext, referenceSchema)
				);

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
		final GraphQLInterfaceType referenceDefinitionInterface = this.referenceDefinitionInterfaceBuilder.getOrBuild(
			collectionBuildingContext,
			referenceSchema
		);

		return ReferenceDefinitionPageDescriptor.DATA
			.to(this.fieldBuilderTransformer)
			.type(nonNull(list(nonNull(referenceDefinitionInterface))))
			.build();
	}

}
