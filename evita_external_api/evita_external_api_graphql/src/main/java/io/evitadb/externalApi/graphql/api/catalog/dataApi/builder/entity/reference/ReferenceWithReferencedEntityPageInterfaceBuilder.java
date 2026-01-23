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
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference.ReferenceWithReferencedEntityPageDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLInterfaceTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLFieldTransformer;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

import static graphql.schema.GraphQLList.list;
import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLTypeReference.typeRef;

/**
 * Builds interface from {@link ReferenceWithReferencedEntityPageDescriptor} for a specific reference entity type.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class ReferenceWithReferencedEntityPageInterfaceBuilder {

	@Nonnull private final CatalogGraphQLSchemaBuildingContext buildingContext;
	@Nonnull private final ObjectDescriptorToGraphQLInterfaceTransformer interfaceBuilderTransformer;
	@Nonnull private final PropertyDescriptorToGraphQLFieldTransformer fieldBuilderTransformer;

	@Nonnull private final ReferencePageInterfaceBuilder referencePageInterfaceBuilder;
	@Nonnull private final ReferenceWithReferencedEntityInterfaceBuilder referenceWithReferencedEntityInterfaceBuilder;

	@Nonnull
	public GraphQLInterfaceType getOrBuild(@Nonnull ReferenceSchemaContract referenceSchema) {
		final String referencedEntityType = referenceSchema.getReferencedEntityType();

		final ReferenceWithReferencedEntityKey key = new ReferenceWithReferencedEntityKey(referencedEntityType);
		return this.buildingContext.getOrComputeReferenceWithReferencedEntityPageInterface(
			key,
			() -> {
				final String interfaceName = ReferenceWithReferencedEntityPageDescriptor.THIS_INTERFACE.name(referencedEntityType);

				final GraphQLInterfaceType.Builder interfaceBuilder = ReferenceWithReferencedEntityPageDescriptor.THIS_INTERFACE
					.to(this.interfaceBuilderTransformer)
					.name(interfaceName)
					.description(ReferenceWithReferencedEntityPageDescriptor.THIS_INTERFACE.description(referencedEntityType));

				// add dynamic interfaces and their fields

				interfaceBuilder.withInterface(typeRef(DataChunkDescriptor.THIS_INTERFACE.name()));
				interfaceBuilder.withInterface(typeRef(PaginatedListDescriptor.THIS_INTERFACE.name()));

				final GraphQLInterfaceType referencePageInterface = this.referencePageInterfaceBuilder.getOrBuild();
				interfaceBuilder.withInterface(
					referencePageInterface
				);
				for (final GraphQLFieldDefinition field : referencePageInterface.getFieldDefinitions()) {
					interfaceBuilder.field(field);
				}

				// add custom fields

				interfaceBuilder.field(
					buildDataField(referenceSchema)
				);

				return interfaceBuilder.build();
			}
		);
	}

	@Nonnull
	private GraphQLFieldDefinition buildDataField(@Nonnull ReferenceSchemaContract referenceSchema) {
		final GraphQLInterfaceType referenceWithReferencedEntityInterface = this.referenceWithReferencedEntityInterfaceBuilder.getOrBuild(referenceSchema);

		return ReferenceWithReferencedEntityPageDescriptor.DATA
			.to(this.fieldBuilderTransformer)
			.type(nonNull(list(nonNull(referenceWithReferencedEntityInterface))))
			.build();
	}
}
