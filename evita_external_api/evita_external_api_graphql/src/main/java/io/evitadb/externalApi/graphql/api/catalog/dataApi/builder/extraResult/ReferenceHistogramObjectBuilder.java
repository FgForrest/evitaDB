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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.extraResult;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ReferenceHistogramDescriptor;
import io.evitadb.externalApi.graphql.api.builder.BuiltFieldDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.BucketsFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult.ReferenceHistogramMaxEntityDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult.ReferenceHistogramMinEntityDataFetcher;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLObjectTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLArgumentTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLFieldTransformer;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static graphql.schema.GraphQLTypeReference.typeRef;

/**
 * Builds a per-referenced-entity-type `{EntityType}Histogram` concrete GraphQL object type. The
 * object implements the shared `Histogram` interface (from
 * {@link HistogramDescriptor#THIS_INTERFACE}) and exposes two additional fields — `minReferencedEntity`
 * and `maxReferencedEntity` — typed as the concrete referenced entity object.
 *
 * One concrete type is built per unique referenced entity type and cached on the
 * {@link CatalogGraphQLSchemaBuildingContext}, mirroring
 * {@code ReferenceWithReferencedEntityInterfaceBuilder}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class ReferenceHistogramObjectBuilder {

	@Nonnull private final CatalogGraphQLSchemaBuildingContext buildingContext;
	@Nonnull private final ObjectDescriptorToGraphQLObjectTransformer objectBuilderTransformer;
	@Nonnull private final PropertyDescriptorToGraphQLFieldTransformer fieldBuilderTransformer;
	@Nonnull private final PropertyDescriptorToGraphQLArgumentTransformer argumentBuilderTransformer;

	/**
	 * Returns (and when absent, builds and caches) the concrete per-referenced-entity-type
	 * `{EntityType}Histogram` object type.
	 */
	@Nonnull
	public GraphQLObjectType getOrBuild(@Nonnull ReferenceSchemaContract referenceSchema) {
		final String referencedEntityType = referenceSchema.getReferencedEntityType();
		final ReferenceHistogramKey key = new ReferenceHistogramKey(referencedEntityType);
		return this.buildingContext.getOrComputeReferenceHistogramObject(
			key,
			() -> buildReferenceHistogramObject(referenceSchema, referencedEntityType)
		);
	}

	@Nonnull
	private GraphQLObjectType buildReferenceHistogramObject(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String referencedEntityType
	) {
		final String typeName = ReferenceHistogramDescriptor.THIS.name(referencedEntityType);

		final GraphQLObjectType.Builder typeBuilder = ReferenceHistogramDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.name(typeName)
			.withInterface(typeRef(HistogramDescriptor.THIS_INTERFACE.name()))
			.field(buildBucketsField());

		// register the concrete object with its MIN/MAX referenced entity fields wired to dedicated
		// data fetchers — types are resolved from the target referenced entity schema (managed
		// references) or fall back to the generic entity reference type (unmanaged references)
		final GraphQLOutputType referencedEntityObject = buildReferencedEntityObject(
			resolveReferencedEntitySchema(referenceSchema)
		);
		this.buildingContext.registerFieldToObject(
			typeName,
			typeBuilder,
			new BuiltFieldDescriptor(
				ReferenceHistogramDescriptor.MIN_REFERENCED_ENTITY
					.to(this.fieldBuilderTransformer)
					.type(referencedEntityObject)
					.build(),
				ReferenceHistogramMinEntityDataFetcher.getInstance()
			)
		);
		this.buildingContext.registerFieldToObject(
			typeName,
			typeBuilder,
			new BuiltFieldDescriptor(
				ReferenceHistogramDescriptor.MAX_REFERENCED_ENTITY
					.to(this.fieldBuilderTransformer)
					.type(referencedEntityObject)
					.build(),
				ReferenceHistogramMaxEntityDataFetcher.getInstance()
			)
		);

		return typeBuilder.build();
	}

	@Nonnull
	private GraphQLFieldDefinition buildBucketsField() {
		return HistogramDescriptor.BUCKETS
			.to(this.fieldBuilderTransformer)
			.argument(BucketsFieldHeaderDescriptor.REQUESTED_COUNT.to(this.argumentBuilderTransformer))
			.argument(BucketsFieldHeaderDescriptor.BEHAVIOR.to(this.argumentBuilderTransformer))
			.build();
	}

	@Nullable
	private EntitySchemaContract resolveReferencedEntitySchema(@Nonnull ReferenceSchemaContract referenceSchema) {
		if (!referenceSchema.isReferencedEntityTypeManaged()) {
			return null;
		}
		return this.buildingContext
			.getSchema()
			.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType());
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
