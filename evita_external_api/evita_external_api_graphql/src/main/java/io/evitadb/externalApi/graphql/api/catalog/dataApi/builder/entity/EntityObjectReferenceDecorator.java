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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity;

import graphql.schema.DataFetcher;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.*;
import io.evitadb.externalApi.graphql.api.builder.BuiltFieldDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.CollectionGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.EntityObjectBuilder.EntityObjectVariant;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.reference.EntityReferenceObjectBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.reference.EntityReferencePageObjectBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.reference.EntityReferenceStripObjectBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.reference.WithNamedReferenceInterfaceBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.ReferenceChunkDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.ReferenceDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.ReferencesDataFetcher;
import io.evitadb.externalApi.graphql.exception.GraphQLSchemaBuildingError;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Collection;

import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLList.list;
import static graphql.schema.GraphQLNonNull.nonNull;

/**
 * Decorates entity objects with reference fields.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
public class EntityObjectReferenceDecorator implements EntityObjectDecorator {

	@Nonnull private final CatalogGraphQLSchemaBuildingContext buildingContext;

	@Nonnull private final WithNamedReferenceInterfaceBuilder withNamedReferenceInterfaceBuilder;
	@Nonnull private final EntityReferenceObjectBuilder entityReferenceObjectBuilder;
	@Nonnull private final EntityReferencePageObjectBuilder entityReferencePageObjectBuilder;
	@Nonnull private final EntityReferenceStripObjectBuilder entityReferenceStripObjectBuilder;

	public void decorate(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
		@Nonnull EntityObjectVariant variant,
		@Nonnull String entityObjectName,
		@Nonnull GraphQLObjectType.Builder entityObjectBuilder
	) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		if (!entitySchema.getReferences().isEmpty()) {
			final Collection<ReferenceSchemaContract> referenceSchemas = collectionBuildingContext.getSchema().getReferences().values();
			referenceSchemas.forEach(
				referenceSchema ->
					buildAndAddEntityReferenceFields(
						collectionBuildingContext,
						entityObjectName,
						entityObjectBuilder,
						variant,
						referenceSchema
					)
			);
		}
	}

	private void buildAndAddEntityReferenceFields(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
		@Nonnull String entityObjectName,
		@Nonnull GraphQLObjectType.Builder entityObjectBuilder,
		@Nonnull EntityObjectVariant version,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		// register the generic WithReference interface

		final GraphQLInterfaceType withReferenceInterface = this.withNamedReferenceInterfaceBuilder.build(
			collectionBuildingContext,
			referenceSchema,
			version
		);
		entityObjectBuilder.withInterface(withReferenceInterface);

		// implement the generic WithReference interface for this specific entity type

		this.buildingContext.registerFieldToObject(
			entityObjectName,
			entityObjectBuilder,
			buildReferenceField(
				collectionBuildingContext,
				referenceSchema,
				withReferenceInterface
			)
		);

		if (EntityObjectVariant.DEFAULT.equals(version) && isReferenceList(referenceSchema)) {
			this.buildingContext.registerFieldToObject(
				entityObjectName,
				entityObjectBuilder,
				buildReferencePageField(
					collectionBuildingContext,
					referenceSchema,
					withReferenceInterface
				)
			);
			this.buildingContext.registerFieldToObject(
				entityObjectName,
				entityObjectBuilder,
				buildReferenceStripField(
					collectionBuildingContext,
					referenceSchema,
					withReferenceInterface
				)
			);
		}
	}

	@Nonnull
	private BuiltFieldDescriptor buildReferenceField(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull GraphQLInterfaceType withReferenceInterface
	) {
		final String fieldName = EntityDescriptor.REFERENCE.name(referenceSchema);

		final GraphQLFieldDefinition fieldTemplate = withReferenceInterface.getFieldDefinition(fieldName);
		Assert.isPremiseValid(
			fieldTemplate != null,
			() -> new GraphQLSchemaBuildingError(
				"No base reference field found.",
				"No base reference field for reference `" + referenceSchema.getName() + "` found in `WithReference` interface."
			)
		);

		final Cardinality referenceCardinality = referenceSchema.getCardinality();
		final GraphQLOutputType referenceObject = this.entityReferenceObjectBuilder.build(collectionBuildingContext, referenceSchema);

		final GraphQLFieldDefinition.Builder referenceFieldBuilder = newFieldDefinition(fieldTemplate);
		final DataFetcher<?> referenceDataFetcher;
		if (referenceCardinality.getMax() > 1) {
			referenceFieldBuilder.type(nonNull(list(nonNull(referenceObject))));
			referenceDataFetcher = new ReferencesDataFetcher(referenceSchema);
		} else if (referenceCardinality.getMin() == 1) {
			referenceFieldBuilder.type(nonNull(referenceObject));
			referenceDataFetcher = new ReferenceDataFetcher(referenceSchema);
		} else {
			referenceFieldBuilder.type(referenceObject);
			referenceDataFetcher = new ReferenceDataFetcher(referenceSchema);
		}

		return new BuiltFieldDescriptor(
			referenceFieldBuilder.build(),
			referenceDataFetcher
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildReferencePageField(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull GraphQLInterfaceType withReferenceInterface
	) {
		final String fieldName = EntityDescriptor.REFERENCE_PAGE.name(referenceSchema);

		final GraphQLFieldDefinition fieldTemplate = withReferenceInterface.getFieldDefinition(fieldName);
		Assert.isPremiseValid(
			fieldTemplate != null,
			() -> new GraphQLSchemaBuildingError(
				"No reference page field found.",
				"No reference page field for reference `" + referenceSchema.getName() + "` found in `WithReference` interface."
			)
		);

		final GraphQLFieldDefinition referencePageField = newFieldDefinition(fieldTemplate)
			.type(this.entityReferencePageObjectBuilder.build(collectionBuildingContext, referenceSchema))
			.build();

		return new BuiltFieldDescriptor(
			referencePageField,
			new ReferenceChunkDataFetcher(referenceSchema)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildReferenceStripField(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull GraphQLInterfaceType withReferenceInterface
	) {
		final String fieldName = EntityDescriptor.REFERENCE_STRIP.name(referenceSchema);

		final GraphQLFieldDefinition fieldTemplate = withReferenceInterface.getFieldDefinition(fieldName);
		Assert.isPremiseValid(
			fieldTemplate != null,
			() -> new GraphQLSchemaBuildingError(
				"No reference strip field found.",
				"No reference strip field for reference `" + referenceSchema.getName() + "` found in `WithReference` interface."
			)
		);

		final GraphQLFieldDefinition referenceStripField = newFieldDefinition(fieldTemplate)
			.type(this.entityReferenceStripObjectBuilder.build(collectionBuildingContext, referenceSchema))
			.build();

		return new BuiltFieldDescriptor(
			referenceStripField,
			new ReferenceChunkDataFetcher(referenceSchema)
		);
	}

	private static boolean isReferenceList(@Nonnull ReferenceSchemaContract referenceSchema) {
		return referenceSchema.getCardinality().getMax() > 1;
	}
}
