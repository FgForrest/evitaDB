/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldDefinition.Builder;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.model.cdc.ChangeCatalogCaptureDescriptor;
import io.evitadb.externalApi.graphql.api.builder.BuiltFieldDescriptor;
import io.evitadb.externalApi.graphql.api.builder.FinalGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.CollectionGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.EntityObjectBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.EntityObjectBuilder.EntityObjectVariant;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.FullResponseObjectBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.LocalMutationAggregateObjectBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.FilterConstraintSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.GraphQLConstraintSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.HeadConstraintSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.OrderConstraintSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.RequireConstraintSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.*;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.CollectionSizeDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.CollectionsDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.GetEntityDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.GetUnknownEntityDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.ListEntitiesDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.ListUnknownEntitiesDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.QueryEntitiesDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.mutatingDataFetcher.DeleteEntitiesMutatingDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.mutatingDataFetcher.UpsertEntityMutatingDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.subscribingDataFetcher.ChangeCatalogDataCaptureBodyDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.subscribingDataFetcher.OnCatalogDataChangeCaptureSubscribingDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.subscribingDataFetcher.OnCollectionDataChangeCaptureSubscribingDataFetcher;
import io.evitadb.externalApi.graphql.api.dataType.DataTypesConverter;
import io.evitadb.externalApi.graphql.api.dataType.GraphQLScalars;
import io.evitadb.externalApi.graphql.api.model.EndpointDescriptorToGraphQLFieldTransformer;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLEnumTypeTransformer;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.AsyncDataFetcher;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static graphql.schema.GraphQLArgument.newArgument;
import static graphql.schema.GraphQLList.list;
import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLTypeReference.typeRef;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.ARGUMENT_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.CURRENCY_ENUM;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.LOCALE_ENUM;
import static io.evitadb.externalApi.graphql.api.dataType.GraphQLScalars.INT;

/**
 * Implementation of {@link FinalGraphQLSchemaBuilder} for building entity data manipulation schema.
 * Fields operate on actual stored entity data.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class CatalogDataApiGraphQLSchemaBuilder extends FinalGraphQLSchemaBuilder<CatalogGraphQLSchemaBuildingContext> {

	private static final ObjectMapper CDO_OBJECT_MAPPER = new ObjectMapper();

	@Nonnull private final GraphQLConstraintSchemaBuildingContext constraintContext;
	@Nonnull private final HeadConstraintSchemaBuilder headConstraintSchemaBuilder;
	@Nonnull private final FilterConstraintSchemaBuilder filterConstraintSchemaBuilder;
	@Nonnull private final OrderConstraintSchemaBuilder orderConstraintSchemaBuilder;
	@Nonnull private final RequireConstraintSchemaBuilder mainQueryRequireConstraintSchemaBuilder;
	@Nonnull private final RequireConstraintSchemaBuilder mainListRequireConstraintSchemaBuilder;

	@Nonnull private final EntityObjectBuilder entityObjectBuilder;
	@Nonnull private final FullResponseObjectBuilder fullResponseObjectBuilder;
	@Nonnull private final LocalMutationAggregateObjectBuilder localMutationAggregateObjectBuilder;

	@Nonnull
	private static String transformLocaleToGraphQLEnumString(@Nonnull Locale locale) {
		return locale.toLanguageTag().replace("-", "_");
	}

	public CatalogDataApiGraphQLSchemaBuilder(
		@Nonnull GraphQLOptions config,
		@Nonnull Evita evita,
		@Nonnull CatalogContract catalog
	) {
		super(new CatalogGraphQLSchemaBuildingContext(config, evita, catalog));
		this.constraintContext = new GraphQLConstraintSchemaBuildingContext(this.buildingContext);

		this.headConstraintSchemaBuilder = new HeadConstraintSchemaBuilder(this.constraintContext);
		this.filterConstraintSchemaBuilder = new FilterConstraintSchemaBuilder(this.constraintContext);
		this.orderConstraintSchemaBuilder = new OrderConstraintSchemaBuilder(
			this.constraintContext,
			new AtomicReference<>(this.filterConstraintSchemaBuilder)
		);
		this.mainQueryRequireConstraintSchemaBuilder = RequireConstraintSchemaBuilder.forMainQueryRequire(
			this.constraintContext,
			new AtomicReference<>(this.filterConstraintSchemaBuilder)
		);
		this.mainListRequireConstraintSchemaBuilder = RequireConstraintSchemaBuilder.forMainListRequire(
			this.constraintContext,
			new AtomicReference<>(this.filterConstraintSchemaBuilder)
		);

		this.entityObjectBuilder = new EntityObjectBuilder(
			this.buildingContext,
			this.constraintContext,
			this.filterConstraintSchemaBuilder,
			this.orderConstraintSchemaBuilder,
			CDO_OBJECT_MAPPER,
			this.argumentBuilderTransformer,
			this.interfaceBuilderTransformer,
			this.objectBuilderTransformer,
			this.fieldBuilderTransformer
		);
		this.fullResponseObjectBuilder = new FullResponseObjectBuilder(
			this.buildingContext,
			this.argumentBuilderTransformer,
			this.objectBuilderTransformer,
			this.inputObjectBuilderTransformer,
			this.fieldBuilderTransformer,
			this.inputFieldBuilderTransformer,
			this.constraintContext,
			this.filterConstraintSchemaBuilder,
			this.orderConstraintSchemaBuilder
		);
		this.localMutationAggregateObjectBuilder = new LocalMutationAggregateObjectBuilder(
			this.buildingContext,
			this.inputObjectBuilderTransformer,
			this.inputFieldBuilderTransformer
		);
	}

	@Override
	@Nonnull
	public GraphQLSchema build() {
		buildCommonTypes();
		buildFields();
		return this.buildingContext.buildGraphQLSchema();
	}

	private void buildCommonTypes() {
		buildLocaleEnum().ifPresent(this.buildingContext::registerCustomEnumIfAbsent);
		buildCurrencyEnum().ifPresent(this.buildingContext::registerCustomEnumIfAbsent);
		this.buildingContext.registerType(buildChangeCatalogCaptureObject());
		this.buildingContext.registerType(QueryLabelDescriptor.THIS.to(this.inputObjectBuilderTransformer).build());

		final GraphQLEnumType scalarEnum = buildScalarEnum();
		this.buildingContext.registerType(scalarEnum);
		this.buildingContext.registerType(buildAssociatedDataScalarEnum(scalarEnum));

		this.entityObjectBuilder.buildCommonTypes();
		this.fullResponseObjectBuilder.buildCommonTypes();
		this.localMutationAggregateObjectBuilder.buildCommonTypes();
	}

	private void buildFields() {
		// "collections" field
		this.buildingContext.registerQueryField(buildCollectionsField());

		// "getEntity" field
		this.buildingContext.registerQueryField(buildGetUnknownEntityField());

		// "listEntity" field
		this.buildingContext.registerQueryField(buildListUnknownEntityField());

		// "onDataChange" field
		this.buildingContext.registerSubscriptionField(buildOnCatalogDataChangeField());

		// collection-specific fields
		this.buildingContext.getEntitySchemas().forEach(entitySchema -> {
			final CollectionGraphQLSchemaBuildingContext collectionBuildingContext = setupForCollection(entitySchema);

			// collection specific "getEntity" field
			this.buildingContext.registerQueryField(buildGetEntityField(collectionBuildingContext));

			// collection specific "listEntity" field
			this.buildingContext.registerQueryField(buildListEntityField(collectionBuildingContext));

			// collection specific "queryEntity" field
			this.buildingContext.registerQueryField(buildQueryEntityField(collectionBuildingContext));

			// collection specific "countEntity" field
			this.buildingContext.registerQueryField(buildCountCollectionField(collectionBuildingContext));

			// collection specific "upsertEntity" field
			this.buildingContext.registerMutationField(buildUpsertEntityField(collectionBuildingContext));

			// collection specific "deleteEntity" field
			this.buildingContext.registerMutationField(buildDeleteEntitiesField(collectionBuildingContext));

			// collection specific "onDataChange" field
			this.buildingContext.registerSubscriptionField(buildCollectionOnDataChangeField(collectionBuildingContext));
		});

		// register gathered custom constraint types
		this.buildingContext.registerTypes(new HashSet<>(this.constraintContext.getBuiltTypes()));
	}

	/**
	 * Prepare common data for specific collection schema building.
	 */
	@Nonnull
	private CollectionGraphQLSchemaBuildingContext setupForCollection(@Nonnull EntitySchemaContract entitySchema) {
		final CollectionGraphQLSchemaBuildingContext collectionBuildingContext = new CollectionGraphQLSchemaBuildingContext(
			this.buildingContext,
			entitySchema
		);

		// build head input objects
		final GraphQLInputType headInputObject = this.headConstraintSchemaBuilder.build(
			collectionBuildingContext.getSchema().getName());
		collectionBuildingContext.setHeadInputObject(headInputObject);

		// build filter input object
		final GraphQLInputType filterByInputObject = this.filterConstraintSchemaBuilder.build(
			collectionBuildingContext.getSchema().getName());
		collectionBuildingContext.setFilterByInputObject(filterByInputObject);

		// build order input object
		final GraphQLInputType orderByInputObject = this.orderConstraintSchemaBuilder.build(
			collectionBuildingContext.getSchema().getName());
		collectionBuildingContext.setOrderByInputObject(orderByInputObject);

		// build require input objects
		// build only if there are any prices because these are only a few allowed constraints in the require builder
		if (!entitySchema.getCurrencies().isEmpty()) {
			final GraphQLInputType requireInputObject = this.mainListRequireConstraintSchemaBuilder.build(
				collectionBuildingContext.getSchema().getName());
			collectionBuildingContext.setListRequireInputObject(requireInputObject);
		}
		// build only if there are any prices or facets because these are only a few allowed constraints in the require builder
		if (!entitySchema.getCurrencies().isEmpty() ||
			entitySchema.getReferences().values().stream().anyMatch(ReferenceSchemaContract::isFacetedInAnyScope)) {
			final GraphQLInputType requireInputObject = this.mainQueryRequireConstraintSchemaBuilder.build(
				collectionBuildingContext.getSchema().getName());
			collectionBuildingContext.setQueryRequireInputObject(requireInputObject);
		}

		// build entity object specific to this schema
		// default entity object with all fields
		collectionBuildingContext.registerEntityObject(this.entityObjectBuilder.build(collectionBuildingContext));
		// non-hierarchical version of entity object with missing recursive parent entities
		collectionBuildingContext.registerEntityObject(
			this.entityObjectBuilder.build(collectionBuildingContext, EntityObjectVariant.NON_HIERARCHICAL));

		return collectionBuildingContext;
	}

	@Nonnull
	private BuiltFieldDescriptor buildCollectionsField() {
		return new BuiltFieldDescriptor(
			CatalogDataApiRootDescriptor.COLLECTIONS.to(this.staticEndpointBuilderTransformer).build(),
			new AsyncDataFetcher(
				CollectionsDataFetcher.getInstance(),
				this.buildingContext.getConfig(),
				this.buildingContext.getTracingContext(),
				this.buildingContext.getEvita()
			)
		);
	}

	@Nullable
	private BuiltFieldDescriptor buildGetUnknownEntityField() {
		final GraphQLFieldDefinition.Builder getUnknownEntityFieldBuilder = CatalogDataApiRootDescriptor.GET_UNKNOWN_ENTITY
			.to(this.staticEndpointBuilderTransformer)
			.type(typeRef(GlobalEntityDescriptor.THIS.name()));

		// build globally unique attribute filters
		final List<GlobalAttributeSchemaContract> globalAttributes = this.buildingContext.getCatalog()
		                                                                                 .getSchema()
		                                                                                 .getAttributes()
		                                                                                 .values()
		                                                                                 .stream()
		                                                                                 .filter(
			                                                                                 GlobalAttributeSchemaContract::isUniqueGloballyInAnyScope)
		                                                                                 .toList();
		if (globalAttributes.isEmpty()) {
			// this field doesn't make sense without global attributes as user wouldn't have way to query any entity
			return null;
		}
		globalAttributes
			.stream()
			.map(as -> newArgument()
				.name(as.getNameVariant(ARGUMENT_NAME_NAMING_CONVENTION))
				.type(DataTypesConverter.getGraphQLScalarType(as.getPlainType()))
				.description(as.getDescription())
				.deprecate(as.getDeprecationNotice())
				.build())
			.forEach(getUnknownEntityFieldBuilder::argument);

		if (!this.buildingContext.getSupportedLocales().isEmpty()) {
			getUnknownEntityFieldBuilder.argument(UnknownEntityHeaderDescriptor.LOCALE
				                                      .to(this.argumentBuilderTransformer)
				                                      .type(typeRef(LOCALE_ENUM.name())));
		}

		getUnknownEntityFieldBuilder
			.argument(UnknownEntityHeaderDescriptor.JOIN.to(this.argumentBuilderTransformer))
			.argument(ScopeAwareFieldHeaderDescriptor.SCOPE.to(this.argumentBuilderTransformer))
			.argument(MetadataAwareFieldHeaderDescriptor.LABELS.to(this.argumentBuilderTransformer));

		return new BuiltFieldDescriptor(
			getUnknownEntityFieldBuilder.build(),
			new AsyncDataFetcher(
				new GetUnknownEntityDataFetcher(
					this.buildingContext.getSchema(),
					this.buildingContext.getSupportedLocales()
				),
				this.buildingContext.getConfig(),
				this.buildingContext.getTracingContext(),
				this.buildingContext.getEvita()
			)
		);
	}

	@Nullable
	private BuiltFieldDescriptor buildListUnknownEntityField() {
		final Builder listUnknownEntityFieldBuilder = CatalogDataApiRootDescriptor.LIST_UNKNOWN_ENTITY
			.to(this.staticEndpointBuilderTransformer)
			.type(nonNull(list(nonNull(typeRef(GlobalEntityDescriptor.THIS.name())))))
			.argument(ListUnknownEntitiesHeaderDescriptor.LIMIT.to(this.argumentBuilderTransformer));

		// build globally unique attribute filters
		final List<GlobalAttributeSchemaContract> globalAttributes = this.buildingContext.getCatalog()
		                                                                                 .getSchema()
		                                                                                 .getAttributes()
		                                                                                 .values()
		                                                                                 .stream()
		                                                                                 .filter(
			                                                                                 GlobalAttributeSchemaContract::isUniqueGloballyInAnyScope)
		                                                                                 .toList();
		if (globalAttributes.isEmpty()) {
			// this field doesn't make sense without global attributes as user wouldn't have way to query any entity
			return null;
		}
		globalAttributes
			.stream()
			.map(as -> newArgument()
				.name(as.getNameVariant(ARGUMENT_NAME_NAMING_CONVENTION))
				.type(list(nonNull(DataTypesConverter.getGraphQLScalarType(as.getPlainType()))))
				.description(as.getDescription())
				.deprecate(as.getDeprecationNotice())
				.build())
			.forEach(listUnknownEntityFieldBuilder::argument);

		if (!this.buildingContext.getSupportedLocales().isEmpty()) {
			listUnknownEntityFieldBuilder.argument(UnknownEntityHeaderDescriptor.LOCALE
				                                       .to(this.argumentBuilderTransformer)
				                                       .type(typeRef(LOCALE_ENUM.name())));
		}

		listUnknownEntityFieldBuilder
			.argument(UnknownEntityHeaderDescriptor.JOIN.to(this.argumentBuilderTransformer))
			.argument(ScopeAwareFieldHeaderDescriptor.SCOPE.to(this.argumentBuilderTransformer))
			.argument(MetadataAwareFieldHeaderDescriptor.LABELS.to(this.argumentBuilderTransformer));

		return new BuiltFieldDescriptor(
			listUnknownEntityFieldBuilder.build(),
			new AsyncDataFetcher(
				new ListUnknownEntitiesDataFetcher(
					this.buildingContext.getSchema(),
					this.buildingContext.getSupportedLocales()
				),
				this.buildingContext.getConfig(),
				this.buildingContext.getTracingContext(),
				this.buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildGetEntityField(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext
	) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final GraphQLFieldDefinition.Builder singleEntityFieldBuilder = CatalogDataApiRootDescriptor.GET_ENTITY
			.to(new EndpointDescriptorToGraphQLFieldTransformer(this.propertyDataTypeBuilderTransformer, entitySchema))
			.description(CatalogDataApiRootDescriptor.GET_ENTITY.description(entitySchema))
			.type(typeRef(EntityDescriptor.THIS.name(entitySchema)))
			.argument(GetEntityHeaderDescriptor.PRIMARY_KEY.to(this.argumentBuilderTransformer));

		// build locale argument
		if (!entitySchema.getLocales().isEmpty()) {
			singleEntityFieldBuilder.argument(GetEntityHeaderDescriptor.LOCALE
				                                  .to(this.argumentBuilderTransformer)
				                                  .type(typeRef(LOCALE_ENUM.name())));
		}

		// build price arguments
		if (!entitySchema.getCurrencies().isEmpty()) {
			singleEntityFieldBuilder
				.argument(GetEntityHeaderDescriptor.PRICE_IN_CURRENCY
					          .to(this.argumentBuilderTransformer)
					          .type(typeRef(CURRENCY_ENUM.name())))
				.argument(GetEntityHeaderDescriptor.PRICE_IN_PRICE_LISTS
					          .to(this.argumentBuilderTransformer))
				.argument(GetEntityHeaderDescriptor.PRICE_VALID_IN
					          .to(this.argumentBuilderTransformer))
				.argument(GetEntityHeaderDescriptor.PRICE_VALID_NOW
					          .to(this.argumentBuilderTransformer))
				.argument(GetEntityHeaderDescriptor.PRICE_TYPE
					          .to(this.argumentBuilderTransformer))
				.argument(ScopeAwareFieldHeaderDescriptor.SCOPE
					          .to(this.argumentBuilderTransformer))
				.argument(MetadataAwareFieldHeaderDescriptor.LABELS
					          .to(this.argumentBuilderTransformer));
		}

		// build unique attribute filter arguments
		entitySchema.getAttributes()
		            .values()
		            .stream()
		            .filter(AttributeSchemaContract::isUniqueInAnyScope)
		            .map(as -> newArgument()
			            .name(as.getNameVariant(ARGUMENT_NAME_NAMING_CONVENTION))
			            .type(DataTypesConverter.getGraphQLScalarType(as.getPlainType()))
			            .description(as.getDescription())
			            .deprecate(as.getDeprecationNotice())
			            .build())
		            .forEach(singleEntityFieldBuilder::argument);

		return new BuiltFieldDescriptor(
			singleEntityFieldBuilder.build(),
			new AsyncDataFetcher(
				new GetEntityDataFetcher(
					this.buildingContext.getSchema(),
					entitySchema
				),
				this.buildingContext.getConfig(),
				this.buildingContext.getTracingContext(),
				this.buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildListEntityField(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext
	) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final GraphQLFieldDefinition.Builder entityListFieldBuilder = CatalogDataApiRootDescriptor.LIST_ENTITY
			.to(new EndpointDescriptorToGraphQLFieldTransformer(this.propertyDataTypeBuilderTransformer, entitySchema))
			.description(CatalogDataApiRootDescriptor.LIST_ENTITY.description(entitySchema))
			.type(nonNull(list(nonNull(typeRef(EntityDescriptor.THIS.name(entitySchema))))))
			.argument(HeadAwareFieldHeaderDescriptor.HEAD
				          .to(this.argumentBuilderTransformer)
				          .type(collectionBuildingContext.getHeadInputObject()))
			.argument(FilterByAwareFieldHeaderDescriptor.FILTER_BY
				          .to(this.argumentBuilderTransformer)
				          .type(collectionBuildingContext.getFilterByInputObject()))
			.argument(OrderByAwareFieldHeaderDescriptor.ORDER_BY
				          .to(this.argumentBuilderTransformer)
				          .type(collectionBuildingContext.getOrderByInputObject()))
			.argument(ListEntitiesHeaderDescriptor.OFFSET
				          .to(this.argumentBuilderTransformer))
			.argument(ListEntitiesHeaderDescriptor.LIMIT
				          .to(this.argumentBuilderTransformer));

		// build require constraints
		// build only if there are any prices or facets because these are only a few allowed constraints in require builder
		collectionBuildingContext
			.getListRequireInputObject()
			.ifPresent(
				requireInputObject -> entityListFieldBuilder.argument(
					RequireAwareFieldHeaderDescriptor.REQUIRE.to(this.argumentBuilderTransformer)
					                                         .type(requireInputObject)
				)
			);

		return new BuiltFieldDescriptor(
			entityListFieldBuilder.build(),
			new AsyncDataFetcher(
				new ListEntitiesDataFetcher(
					this.buildingContext.getSchema(),
					entitySchema
				),
				this.buildingContext.getConfig(),
				this.buildingContext.getTracingContext(),
				this.buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildQueryEntityField(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext
	) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final GraphQLObjectType entityFullResponseObject = this.fullResponseObjectBuilder.build(
			collectionBuildingContext.getSchema());

		final GraphQLFieldDefinition.Builder entityQueryFieldBuilder = CatalogDataApiRootDescriptor.QUERY_ENTITY
			.to(new EndpointDescriptorToGraphQLFieldTransformer(this.propertyDataTypeBuilderTransformer, entitySchema))
			.description(CatalogDataApiRootDescriptor.QUERY_ENTITY.description(entitySchema))
			.type(nonNull(entityFullResponseObject))
			.argument(HeadAwareFieldHeaderDescriptor.HEAD
				          .to(this.argumentBuilderTransformer)
				          .type(collectionBuildingContext.getHeadInputObject()))
			.argument(FilterByAwareFieldHeaderDescriptor.FILTER_BY
				          .to(this.argumentBuilderTransformer)
				          .type(collectionBuildingContext.getFilterByInputObject()))
			.argument(OrderByAwareFieldHeaderDescriptor.ORDER_BY
				          .to(this.argumentBuilderTransformer)
				          .type(collectionBuildingContext.getOrderByInputObject()));

		// build require constraints
		// build only if there are any prices or facets because these are only few allowed constraints in require builder
		collectionBuildingContext.getQueryRequireInputObject().ifPresent(
			requireInputObject -> entityQueryFieldBuilder.argument(RequireAwareFieldHeaderDescriptor.REQUIRE
				                                                       .to(this.argumentBuilderTransformer)
				                                                       .type(requireInputObject)));

		return new BuiltFieldDescriptor(
			entityQueryFieldBuilder.build(),
			new AsyncDataFetcher(
				new QueryEntitiesDataFetcher(
					this.buildingContext.getSchema(),
					entitySchema
				),
				this.buildingContext.getConfig(),
				this.buildingContext.getTracingContext(),
				this.buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildCountCollectionField(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext
	) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();
		return new BuiltFieldDescriptor(
			CatalogDataApiRootDescriptor.COUNT_COLLECTION
				.to(new EndpointDescriptorToGraphQLFieldTransformer(
					this.propertyDataTypeBuilderTransformer, entitySchema))
				.build(),
			new AsyncDataFetcher(
				new CollectionSizeDataFetcher(entitySchema),
				this.buildingContext.getConfig(),
				this.buildingContext.getTracingContext(),
				this.buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildUpsertEntityField(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext
	) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final GraphQLFieldDefinition.Builder upsertEntityFieldBuilder = CatalogDataApiRootDescriptor.UPSERT_ENTITY
			.to(new EndpointDescriptorToGraphQLFieldTransformer(this.propertyDataTypeBuilderTransformer, entitySchema))
			.type(nonNull(typeRef(EntityDescriptor.THIS.name(entitySchema))))
			.argument(UpsertEntityHeaderDescriptor.PRIMARY_KEY
				          .to(this.argumentBuilderTransformer)
				          .type(entitySchema.isWithGeneratedPrimaryKey() ? INT : nonNull(INT)))
			.argument(UpsertEntityHeaderDescriptor.ENTITY_EXISTENCE.to(this.argumentBuilderTransformer));

		final GraphQLInputObjectType localMutationAggregateObject = this.localMutationAggregateObjectBuilder.build(
			collectionBuildingContext.getSchema());
		if (localMutationAggregateObject != null) {
			upsertEntityFieldBuilder.argument(UpsertEntityHeaderDescriptor.MUTATIONS
				                                  .to(this.argumentBuilderTransformer)
				                                  .type(list(nonNull(localMutationAggregateObject))))
			                        .build();
		}

		return new BuiltFieldDescriptor(
			upsertEntityFieldBuilder.build(),
			new AsyncDataFetcher(
				new UpsertEntityMutatingDataFetcher(CDO_OBJECT_MAPPER, this.buildingContext.getSchema(), entitySchema),
				this.buildingContext.getConfig(),
				this.buildingContext.getTracingContext(),
				this.buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildDeleteEntitiesField(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext
	) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final GraphQLFieldDefinition deleteEntityByQueryField = CatalogDataApiRootDescriptor.DELETE_ENTITY
			.to(new EndpointDescriptorToGraphQLFieldTransformer(this.propertyDataTypeBuilderTransformer, entitySchema))
			.type(nonNull(list(nonNull(typeRef(EntityDescriptor.THIS.name(entitySchema))))))
			.argument(DeleteEntitiesMutationHeaderDescriptor.FILTER_BY
				          .to(this.argumentBuilderTransformer)
				          .type(collectionBuildingContext.getFilterByInputObject()))
			.argument(DeleteEntitiesMutationHeaderDescriptor.ORDER_BY
				          .to(this.argumentBuilderTransformer)
				          .type(collectionBuildingContext.getOrderByInputObject()))
			.argument(DeleteEntitiesMutationHeaderDescriptor.OFFSET
				          .to(this.argumentBuilderTransformer))
			.argument(DeleteEntitiesMutationHeaderDescriptor.LIMIT
				          .to(this.argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			deleteEntityByQueryField,
			new AsyncDataFetcher(
				new DeleteEntitiesMutatingDataFetcher(this.buildingContext.getSchema(), entitySchema),
				this.buildingContext.getConfig(),
				this.buildingContext.getTracingContext(),
				this.buildingContext.getEvita()
			)
		);
	}

	@Nonnull
	private Optional<GraphQLEnumType> buildLocaleEnum() {
		if (this.buildingContext.getSupportedLocales().isEmpty()) {
			return Optional.empty();
		}

		final GraphQLEnumType localeEnum = LOCALE_ENUM
			.to(new ObjectDescriptorToGraphQLEnumTypeTransformer(
				this.buildingContext.getSupportedLocales().stream()
				                    .map(locale -> Map.entry(transformLocaleToGraphQLEnumString(locale), locale))
				                    .collect(Collectors.toSet())
			))
			.build();

		return Optional.of(localeEnum);
	}

	@Nonnull
	private Optional<GraphQLEnumType> buildCurrencyEnum() {
		if (this.buildingContext.getSupportedCurrencies().isEmpty()) {
			return Optional.empty();
		}

		final GraphQLEnumType currencyEnum = CURRENCY_ENUM
			.to(new ObjectDescriptorToGraphQLEnumTypeTransformer(
				this.buildingContext.getSupportedCurrencies().stream()
				                    .map(currency -> Map.entry(currency.toString(), currency))
				                    .collect(Collectors.toSet())
			))
			.build();

		return Optional.of(currencyEnum);
	}

	@Nonnull
	private GraphQLObjectType buildChangeCatalogCaptureObject() {
		this.buildingContext.registerDataFetcher(
			ChangeCatalogCaptureDescriptor.THIS,
			ChangeCatalogCaptureDescriptor.BODY,
			new ChangeCatalogDataCaptureBodyDataFetcher(CDO_OBJECT_MAPPER)
		);

		return ChangeCatalogCaptureDescriptor.THIS
			.to(this.objectBuilderTransformer)
			.field(ChangeCatalogCaptureDescriptor.BODY.to(this.fieldBuilderTransformer).type(nonNull(GraphQLScalars.OBJECT)))
			.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildOnCatalogDataChangeField() {
		final GraphQLFieldDefinition onDataChangeField = GraphQLCatalogDataApiRootDescriptor.ON_CATALOG_DATA_CHANGE
			.to(this.staticEndpointBuilderTransformer)
			.argument(OnCatalogDataChangeHeaderDescriptor.SINCE_VERSION.to(this.argumentBuilderTransformer))
			.argument(OnCatalogDataChangeHeaderDescriptor.SINCE_INDEX.to(this.argumentBuilderTransformer))
			.argument(OnCatalogDataChangeHeaderDescriptor.OPERATION.to(this.argumentBuilderTransformer))
			.argument(OnCatalogDataChangeHeaderDescriptor.CONTAINER_TYPE.to(this.argumentBuilderTransformer))
			.argument(OnCatalogDataChangeHeaderDescriptor.CONTAINER_NAME.to(this.argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			onDataChangeField,
			new OnCatalogDataChangeCaptureSubscribingDataFetcher(this.buildingContext.getEvita())
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildCollectionOnDataChangeField(
		@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext
	) {
		final GraphQLFieldDefinition onDataChangeField = GraphQLCatalogDataApiRootDescriptor.ON_COLLECTION_DATA_CHANGE
			.to(new EndpointDescriptorToGraphQLFieldTransformer(
				this.propertyDataTypeBuilderTransformer, collectionBuildingContext.getSchema()))
			.argument(OnCollectionDataChangeHeaderDescriptor.SINCE_VERSION.to(this.argumentBuilderTransformer))
			.argument(OnCollectionDataChangeHeaderDescriptor.SINCE_INDEX.to(this.argumentBuilderTransformer))
			.argument(OnCollectionDataChangeHeaderDescriptor.OPERATION.to(this.argumentBuilderTransformer))
			.argument(OnCollectionDataChangeHeaderDescriptor.CONTAINER_TYPE.to(this.argumentBuilderTransformer))
			.argument(OnCollectionDataChangeHeaderDescriptor.CONTAINER_NAME.to(this.argumentBuilderTransformer))
			.argument(OnCollectionDataChangeHeaderDescriptor.ENTITY_PRIMARY_KEY.to(this.argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			onDataChangeField,
			new OnCollectionDataChangeCaptureSubscribingDataFetcher(
				this.buildingContext.getEvita(), collectionBuildingContext.getSchema()
			)
		);
	}
}
