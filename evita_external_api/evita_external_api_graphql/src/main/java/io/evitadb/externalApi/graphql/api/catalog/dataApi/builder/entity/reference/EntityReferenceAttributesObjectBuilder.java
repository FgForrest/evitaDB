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
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference.EntityReferenceAttributesDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference.ReferenceDefinitionAttributesDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.CollectionGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLObjectTransformer;
import io.evitadb.externalApi.graphql.exception.GraphQLSchemaBuildingError;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Builds an object from {@link EntityReferenceAttributesDescriptor} for a
 * specific reference of a specific entity type. Implements {@link ReferenceDefinitionAttributesDescriptor#THIS_INTERFACE} interface
 * for a corresponding reference definition.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class EntityReferenceAttributesObjectBuilder {

	@Nonnull private final ObjectDescriptorToGraphQLObjectTransformer objectBuilderTransformer;

	@Nonnull private final ReferenceDefinitionAttributesInterfaceBuilder referenceDefinitionAttributesInterfaceBuilder;

	@Nonnull
	public GraphQLObjectType build(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		Assert.isPremiseValid(
			!referenceSchema.getAttributes().isEmpty(),
			() -> new GraphQLSchemaBuildingError("Reference `" + referenceSchema.getName() + "` has no attributes but reference attribute interface is requested.")
		);

		final String objectName = EntityReferenceAttributesDescriptor.THIS.name(
			collectionBuildingContext.getSchema(),
			referenceSchema
		);

		final GraphQLObjectType.Builder interfaceBuilder = EntityReferenceAttributesDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(objectName);

		// add interfaces and their fields

		final GraphQLInterfaceType referenceDefinitionAttributesInterface = this.referenceDefinitionAttributesInterfaceBuilder.build(
			collectionBuildingContext,
			referenceSchema
		);
		interfaceBuilder.withInterface(referenceDefinitionAttributesInterface);
		for (final GraphQLFieldDefinition field : referenceDefinitionAttributesInterface.getFieldDefinitions()) {
			interfaceBuilder.field(field);
		}

		return interfaceBuilder.build();
	}
}
