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
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference.ReferenceWithReferencedEntityDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLInterfaceTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLFieldTransformer;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static graphql.schema.GraphQLTypeReference.typeRef;

/**
 * Builds interface from {@link ReferenceWithReferencedEntityDescriptor} for a specific reference entity type.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class ReferenceWithReferencedEntityInterfaceBuilder {

	@Nonnull private final CatalogGraphQLSchemaBuildingContext buildingContext;
	@Nonnull private final ObjectDescriptorToGraphQLInterfaceTransformer interfaceBuilderTransformer;
	@Nonnull private final PropertyDescriptorToGraphQLFieldTransformer fieldBuilderTransformer;

	@Nonnull
	public GraphQLInterfaceType getOrBuild(@Nonnull ReferenceSchemaContract referenceSchema) {
		final String referencedEntityType = referenceSchema.getReferencedEntityType();

		final ReferenceWithReferencedEntityKey key = new ReferenceWithReferencedEntityKey(referencedEntityType);
		return this.buildingContext.getOrComputeReferenceWithReferencedEntityInterface(
			key,
			() -> {
				final String interfaceName = ReferenceWithReferencedEntityDescriptor.THIS_INTERFACE.name(referencedEntityType);

				final GraphQLInterfaceType.Builder interfaceBuilder = ReferenceWithReferencedEntityDescriptor.THIS_INTERFACE
					.to(this.interfaceBuilderTransformer)
					.name(interfaceName)
					.description(ReferenceWithReferencedEntityDescriptor.THIS_INTERFACE.description(referencedEntityType));

				// add custom fields

				interfaceBuilder.field(
					buildReferencedEntityField(referenceSchema)
				);

				return interfaceBuilder.build();
			}
		);
	}

	@Nonnull
	private GraphQLFieldDefinition buildReferencedEntityField(@Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract referencedEntitySchema;
		if (referenceSchema.isReferencedEntityTypeManaged()) {
			referencedEntitySchema = this.buildingContext
				.getSchema()
				.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType());
		} else {
			referencedEntitySchema = null;
		}
		final GraphQLOutputType referencedEntityObject = buildReferencedEntityObject(referencedEntitySchema);

		return ReferenceWithReferencedEntityDescriptor.REFERENCED_ENTITY
			.to(this.fieldBuilderTransformer)
			.type(referencedEntityObject)
			.build();
	}

	@Nonnull
	private static GraphQLOutputType buildReferencedEntityObject(@Nullable EntitySchemaContract referencedEntitySchema) {
		if (referencedEntitySchema != null) {
			return typeRef(EntityDescriptor.THIS.name(referencedEntitySchema));
		} else {
			return typeRef(EntityDescriptor.THIS_REFERENCE.name());
		}
	}
}
