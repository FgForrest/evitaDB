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

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLObjectType.Builder;
import graphql.schema.GraphQLUnionType;
import io.evitadb.api.query.require.HierarchyParentsBehaviour;
import io.evitadb.api.query.require.HierarchyStopAt;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.DataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.HierarchyDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.ParentPointerDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.ParentUnionDescriptor;
import io.evitadb.externalApi.graphql.api.builder.BuiltFieldDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.CollectionGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.EntityObjectBuilder.EntityObjectVariant;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.FilterConstraintSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.GraphQLConstraintSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.RequireConstraintSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GraphQLEntityDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity.ParentsFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.ParentPrimaryKeyDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.ParentUnionTypeResolver;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.ParentsCompleteDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.entity.ParentsDataFetcher;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLObjectTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLArgumentTransformer;
import io.evitadb.externalApi.graphql.api.model.PropertyDescriptorToGraphQLFieldTransformer;
import io.evitadb.externalApi.graphql.api.model.UnionDescriptorToGraphQLUnionTransformer;

import javax.annotation.Nonnull;
import java.util.concurrent.atomic.AtomicReference;

import static graphql.schema.GraphQLList.list;
import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLTypeReference.typeRef;

/**
 * Decorates entity objects with hierarchy fields.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public class EntityObjectHierarchyDecorator implements EntityObjectDecorator {

	@Nonnull private final CatalogGraphQLSchemaBuildingContext buildingContext;
	@Nonnull private final PropertyDescriptorToGraphQLArgumentTransformer argumentBuilderTransformer;
	@Nonnull private final ObjectDescriptorToGraphQLObjectTransformer objectBuilderTransformer;
	@Nonnull private final PropertyDescriptorToGraphQLFieldTransformer fieldBuilderTransformer;
	@Nonnull private final UnionDescriptorToGraphQLUnionTransformer unionBuilderTransformer;
	@Nonnull private final RequireConstraintSchemaBuilder hierarchyRequireConstraintSchemaBuilder;

	public EntityObjectHierarchyDecorator(
		@Nonnull CatalogGraphQLSchemaBuildingContext buildingContext,
		@Nonnull GraphQLConstraintSchemaBuildingContext constraintSchemaBuildingContext,
		@Nonnull FilterConstraintSchemaBuilder filterConstraintSchemaBuilder,
		@Nonnull PropertyDescriptorToGraphQLArgumentTransformer argumentBuilderTransformer,
		@Nonnull ObjectDescriptorToGraphQLObjectTransformer objectBuilderTransformer,
		@Nonnull PropertyDescriptorToGraphQLFieldTransformer fieldBuilderTransformer
	) {
		this.buildingContext = buildingContext;
		this.argumentBuilderTransformer = argumentBuilderTransformer;
		this.objectBuilderTransformer = objectBuilderTransformer;
		this.fieldBuilderTransformer = fieldBuilderTransformer;
		this.unionBuilderTransformer = new UnionDescriptorToGraphQLUnionTransformer();

		this.hierarchyRequireConstraintSchemaBuilder = RequireConstraintSchemaBuilder.forComplementaryRequire(
			constraintSchemaBuildingContext,
			new AtomicReference<>(filterConstraintSchemaBuilder)
		);
	}

	@Override
	public void decorate(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext,
		@Nonnull EntityObjectVariant variant,
		@Nonnull String entityObjectName,
		@Nonnull Builder entityObjectBuilder
	) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		if (entitySchema.isWithHierarchy() && variant == EntityObjectVariant.DEFAULT) {
			this.buildingContext.registerFieldToObject(
				entityObjectName,
				entityObjectBuilder,
				buildEntityParentPrimaryKeyField()
			);

			// both parent fields are bounded by the very same constraint, and the resolver requires the two arguments
			// to be equal whenever both fields are selected at once
			final GraphQLInputType stopAtConstraint = buildStopAtConstraint(entitySchema);

			this.buildingContext.registerFieldToObject(
				entityObjectName,
				entityObjectBuilder,
				buildEntityParentsField(entitySchema, stopAtConstraint)
			);

			this.buildingContext.registerFieldToObject(
				entityObjectName,
				entityObjectBuilder,
				buildEntityParentsCompleteField(entitySchema, stopAtConstraint)
			);
		}
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityParentPrimaryKeyField() {
		return new BuiltFieldDescriptor(
			GraphQLEntityDescriptor.PARENT_PRIMARY_KEY.to(this.fieldBuilderTransformer).build(),
			ParentPrimaryKeyDataFetcher.getInstance()
		);
	}

	/**
	 * Builds the input type of the `stopAt` argument bounding how far up the ancestor axis the traversal goes.
	 *
	 * @param entitySchema the schema of the decorated collection
	 * @return the input type of the `stopAt` argument
	 */
	@Nonnull
	private GraphQLInputType buildStopAtConstraint(@Nonnull EntitySchemaContract entitySchema) {
		final DataLocator selfHierarchyConstraintDataLocator = new HierarchyDataLocator(
			new ManagedEntityTypePointer(entitySchema.getName())
		);
		return this.hierarchyRequireConstraintSchemaBuilder.build(
			selfHierarchyConstraintDataLocator,
			HierarchyStopAt.class
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityParentsField(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull GraphQLInputType stopAtConstraint
	) {
		final GraphQLFieldDefinition field = GraphQLEntityDescriptor.PARENTS
			.to(this.fieldBuilderTransformer)
			.type(list(nonNull(typeRef(GraphQLEntityDescriptor.THIS_NON_HIERARCHICAL.name(entitySchema)))))
			.argument(
				ParentsFieldHeaderDescriptor.STOP_AT
					.to(this.argumentBuilderTransformer)
					.type(stopAtConstraint)
			)
			.build();

		return new BuiltFieldDescriptor(
			field,
			ParentsDataFetcher.getInstance()
		);
	}

	/**
	 * Builds the sibling of the `parents` field reporting the ancestor axis under
	 * {@link HierarchyParentsBehaviour#COMPLETE}. Its element type is a union, because an ancestor whose requested body
	 * could not be materialized is reported as a bodyless pointer rather than dropped, and the two shapes cannot be
	 * told apart by any field they share.
	 *
	 * @param entitySchema     the schema of the decorated collection
	 * @param stopAtConstraint the input type of the `stopAt` argument
	 * @return the built field together with its data fetcher
	 */
	@Nonnull
	private BuiltFieldDescriptor buildEntityParentsCompleteField(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull GraphQLInputType stopAtConstraint
	) {
		final String entityObjectName = GraphQLEntityDescriptor.THIS_NON_HIERARCHICAL.name(entitySchema);
		final String parentPointerObjectName = ParentPointerDescriptor.THIS.name(entitySchema);

		this.buildingContext.registerType(
			ParentPointerDescriptor.THIS
				.to(this.objectBuilderTransformer)
				.name(parentPointerObjectName)
				.build()
		);

		final GraphQLUnionType parentUnion = ParentUnionDescriptor.THIS
			.to(this.unionBuilderTransformer)
			.name(ParentUnionDescriptor.THIS.name(entitySchema))
			.possibleType(typeRef(entityObjectName))
			.possibleType(typeRef(parentPointerObjectName))
			.build();
		this.buildingContext.registerType(parentUnion);
		this.buildingContext.registerTypeResolver(
			parentUnion,
			new ParentUnionTypeResolver(entityObjectName, parentPointerObjectName)
		);

		final GraphQLFieldDefinition field = GraphQLEntityDescriptor.PARENTS_COMPLETE
			.to(this.fieldBuilderTransformer)
			.type(list(nonNull(typeRef(parentUnion.getName()))))
			.argument(
				ParentsFieldHeaderDescriptor.STOP_AT
					.to(this.argumentBuilderTransformer)
					.type(stopAtConstraint)
			)
			.build();

		return new BuiltFieldDescriptor(
			field,
			ParentsCompleteDataFetcher.getInstance()
		);
	}
}
