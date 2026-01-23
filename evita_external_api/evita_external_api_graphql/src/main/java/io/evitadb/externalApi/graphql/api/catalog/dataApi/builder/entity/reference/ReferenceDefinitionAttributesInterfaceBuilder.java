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

import graphql.schema.GraphQLInterfaceType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference.ReferenceDefinitionAttributesDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.CollectionGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.attribute.AttributeFieldBuilder;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLInterfaceTransformer;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Builds interfaces from {@link ReferenceDefinitionAttributesDescriptor} for a specific reference definition.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class ReferenceDefinitionAttributesInterfaceBuilder {

	@Nonnull private final CatalogGraphQLSchemaBuildingContext buildingContext;
	@Nonnull private final ObjectDescriptorToGraphQLInterfaceTransformer interfaceBuilderTransformer;

	@Nonnull private final AttributeFieldBuilder attributeFieldBuilder;

	@Nonnull
	public GraphQLInterfaceType getOrBuild(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		final ReferenceDefinitionAttributesKey key = new ReferenceDefinitionAttributesKey(
			referenceSchema,
			collectionBuildingContext.getSchema().getName(),
			referenceSchema.getName()
		);

		return this.buildingContext.getOrComputeReferenceDefinitionAttributesInterface(
			key,
			() -> {
				final String interfaceName = ReferenceDefinitionAttributesDescriptor.THIS_INTERFACE.name(
					referenceSchema.getReferencedEntityType(),
					Long.toHexString(key.toHash())
				);

				final GraphQLInterfaceType.Builder interfaceBuilder = ReferenceDefinitionAttributesDescriptor.THIS_INTERFACE
					.to(this.interfaceBuilderTransformer)
					.name(interfaceName);

				referenceSchema.getAttributes().values().forEach(
					attributeSchema ->
						interfaceBuilder.field(
							this.attributeFieldBuilder.buildFieldDefinition(attributeSchema, true)
						)
				);

				return interfaceBuilder.build();
			}
		);
	}
}
