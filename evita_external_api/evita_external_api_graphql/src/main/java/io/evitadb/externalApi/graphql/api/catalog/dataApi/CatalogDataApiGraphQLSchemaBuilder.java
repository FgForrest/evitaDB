/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.OrderConstraintSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.RequireConstraintSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.DeleteEntitiesMutationHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GetEntityHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.ListEntitiesHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.ListUnknownEntitiesHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.QueryEntitiesHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.UnknownEntityHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.UpsertEntityHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.CollectionSizeDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.CollectionsDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.GetEntityDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.GetUnknownEntityDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.ListEntitiesDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.ListUnknownEntitiesDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.QueryEntitiesDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.mutatingDataFetcher.DeleteEntitiesMutatingDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.mutatingDataFetcher.UpsertEntityMutatingDataFetcher;
import io.evitadb.externalApi.graphql.api.dataType.DataTypesConverter;
import io.evitadb.externalApi.graphql.api.model.EndpointDescriptorToGraphQLFieldTransformer;
import io.evitadb.externalApi.graphql.api.model.ObjectDescriptorToGraphQLEnumTypeTransformer;
import io.evitadb.externalApi.graphql.configuration.GraphQLConfig;

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
import static graphql.schema.GraphQLEnumType.newEnum;
import static graphql.schema.GraphQLList.list;
import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLTypeReference.typeRef;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.ARGUMENT_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.CATALOG_LOCALE_ENUM;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.ENTITY_CURRENCY_ENUM;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.ENTITY_LOCALE_ENUM;
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
	@Nonnull private final FilterConstraintSchemaBuilder filterConstraintSchemaBuilder;
	@Nonnull private final OrderConstraintSchemaBuilder orderConstraintSchemaBuilder;
	@Nonnull private final RequireConstraintSchemaBuilder mainRequireConstraintSchemaBuilder;

	@Nonnull private final EntityObjectBuilder entityObjectBuilder;
	@Nonnull private final FullResponseObjectBuilder fullResponseObjectBuilder;
	@Nonnull private final LocalMutationAggregateObjectBuilder localMutationAggregateObjectBuilder;

	public CatalogDataApiGraphQLSchemaBuilder(@Nonnull GraphQLConfig config,
	                                          @Nonnull Evita evita,
	                                          @Nonnull CatalogContract catalog) {
		super(new CatalogGraphQLSchemaBuildingContext(config, evita, catalog));
		this.constraintContext = new GraphQLConstraintSchemaBuildingContext(buildingContext);

		this.filterConstraintSchemaBuilder = new FilterConstraintSchemaBuilder(constraintContext);
		this.orderConstraintSchemaBuilder = new OrderConstraintSchemaBuilder(constraintContext);
		this.mainRequireConstraintSchemaBuilder = RequireConstraintSchemaBuilder.forMainRequire(
			constraintContext,
			new AtomicReference<>(filterConstraintSchemaBuilder)
		);

		this.entityObjectBuilder = new EntityObjectBuilder(
			buildingContext,
			constraintContext,
			filterConstraintSchemaBuilder,
			orderConstraintSchemaBuilder,
			CDO_OBJECT_MAPPER,
			argumentBuilderTransformer,
			interfaceBuilderTransformer,
			objectBuilderTransformer,
			fieldBuilderTransformer
		);
		this.fullResponseObjectBuilder = new FullResponseObjectBuilder(
			buildingContext,
			argumentBuilderTransformer,
			objectBuilderTransformer,
			inputObjectBuilderTransformer,
			fieldBuilderTransformer,
			inputFieldBuilderTransformer,
			constraintContext,
			filterConstraintSchemaBuilder,
			orderConstraintSchemaBuilder
		);
		this.localMutationAggregateObjectBuilder = new LocalMutationAggregateObjectBuilder(
			buildingContext,
			inputObjectBuilderTransformer,
			inputFieldBuilderTransformer
		);
	}

	@Override
    @Nonnull
	public GraphQLSchema build() {
		buildCommonTypes();
		buildFields();
		return buildingContext.buildGraphQLSchema();
	}

	private void buildCommonTypes() {
		buildCatalogEnum().ifPresent(buildingContext::registerCustomEnumIfAbsent);

		final GraphQLEnumType scalarEnum = buildScalarEnum();
		buildingContext.registerType(scalarEnum);
		buildingContext.registerType(buildAssociatedDataScalarEnum(scalarEnum));

		entityObjectBuilder.buildCommonTypes();
		fullResponseObjectBuilder.buildCommonTypes();
		localMutationAggregateObjectBuilder.buildCommonTypes();

	}

	private void buildFields() {
		// "collections" field
		buildingContext.registerQueryField(buildCollectionsField());

		// "getEntity" field
		buildingContext.registerQueryField(buildGetUnknownEntityField());

		// "listEntity" field
		buildingContext.registerQueryField(buildListUnknownEntityField());

		// collection-specific fields
		buildingContext.getEntitySchemas().forEach(entitySchema -> {
			final CollectionGraphQLSchemaBuildingContext collectionBuildingContext = setupForCollection(entitySchema);

			// collection specific "getEntity" field
			buildingContext.registerQueryField(buildGetEntityField(collectionBuildingContext));

			// collection specific "listEntity" field
			buildingContext.registerQueryField(buildListEntityField(collectionBuildingContext));

			// collection specific "queryEntity" field
			buildingContext.registerQueryField(buildQueryEntityField(collectionBuildingContext));

			// collection specific "countEntity" field
			buildingContext.registerQueryField(buildCountCollectionField(collectionBuildingContext));

			// collection specific "upsertEntity" field
			buildingContext.registerMutationField(buildUpsertEntityField(collectionBuildingContext));

			// collection specific "deleteEntity" field
			buildingContext.registerMutationField(buildDeleteEntitiesField(collectionBuildingContext));
		});

		// register gathered custom constraint types
		buildingContext.registerTypes(new HashSet<>(constraintContext.getBuiltTypes()));
	}


	/**
	 * Prepare common data for specific collection schema building.
	 */
	@Nonnull
	private CollectionGraphQLSchemaBuildingContext setupForCollection(@Nonnull EntitySchemaContract entitySchema) {
		if (!entitySchema.getLocales().isEmpty()) {
			final String localeEnumName = ENTITY_LOCALE_ENUM.name(entitySchema);
			final GraphQLEnumType.Builder localeEnumBuilder = newEnum()
				.name(localeEnumName)
				.description(CatalogDataApiRootDescriptor.ENTITY_LOCALE_ENUM.description());
			entitySchema.getLocales().forEach(l -> localeEnumBuilder.value(transformLocaleToGraphQLEnumString(l), l));
			buildingContext.registerCustomEnumIfAbsent(localeEnumBuilder.build());
		}

		if (!entitySchema.getCurrencies().isEmpty()) {
			final String currencyEnumName = ENTITY_CURRENCY_ENUM.name(entitySchema);
			final GraphQLEnumType.Builder currencyEnumBuilder = newEnum()
				.name(currencyEnumName)
				.description(CatalogDataApiRootDescriptor.ENTITY_CURRENCY_ENUM.description());
			entitySchema.getCurrencies().forEach(c -> currencyEnumBuilder.value(c.toString(), c));
			buildingContext.registerCustomEnumIfAbsent(currencyEnumBuilder.build());
		}

		final CollectionGraphQLSchemaBuildingContext collectionBuildingContext = new CollectionGraphQLSchemaBuildingContext(
			buildingContext,
			entitySchema
		);

		// build filter input object
		final GraphQLInputType filterByInputObject = filterConstraintSchemaBuilder.build(collectionBuildingContext.getSchema().getName());
		collectionBuildingContext.setFilterByInputObject(filterByInputObject);

		// build order input object
		final GraphQLInputType orderByInputObject = orderConstraintSchemaBuilder.build(collectionBuildingContext.getSchema().getName());
		collectionBuildingContext.setOrderByInputObject(orderByInputObject);

		// build require input object
		// build only if there are any prices or facets because these are only few allowed constraints in require builder
		if (!entitySchema.getCurrencies().isEmpty() ||
			entitySchema.getReferences().values().stream().anyMatch(ReferenceSchemaContract::isFaceted)) {
			final GraphQLInputType requireInputObject = mainRequireConstraintSchemaBuilder.build(collectionBuildingContext.getSchema().getName());
			collectionBuildingContext.setRequireInputObject(requireInputObject);
		}

		// build entity object specific to this schema
		// default entity object with all fields
		collectionBuildingContext.registerEntityObject(entityObjectBuilder.build(collectionBuildingContext));
		// non-hierarchical version of entity object with missing recursive parent entities
		collectionBuildingContext.registerEntityObject(entityObjectBuilder.build(collectionBuildingContext, EntityObjectVariant.NON_HIERARCHICAL));

		return collectionBuildingContext;
	}

	@Nonnull
	private BuiltFieldDescriptor buildCollectionsField() {
		return new BuiltFieldDescriptor(
			CatalogDataApiRootDescriptor.COLLECTIONS.to(staticEndpointBuilderTransformer).build(),
			new CollectionsDataFetcher(buildingContext.getEvitaExecutor().orElse(null))
		);
	}

	@Nullable
	private BuiltFieldDescriptor buildGetUnknownEntityField() {
		final GraphQLFieldDefinition.Builder getUnknownEntityFieldBuilder = CatalogDataApiRootDescriptor.GET_UNKNOWN_ENTITY
			.to(staticEndpointBuilderTransformer);

		// build globally unique attribute filters
		final List<GlobalAttributeSchemaContract> globalAttributes = buildingContext.getCatalog()
			.getSchema()
			.getAttributes()
			.values()
			.stream()
			.filter(GlobalAttributeSchemaContract::isUniqueGlobally)
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

		getUnknownEntityFieldBuilder.argument(UnknownEntityHeaderDescriptor.JOIN.to(argumentBuilderTransformer));

		return new BuiltFieldDescriptor(
			getUnknownEntityFieldBuilder.build(),
			new GetUnknownEntityDataFetcher(
				buildingContext.getEvitaExecutor().orElse(null),
				buildingContext.getSchema(),
				buildingContext.getSupportedLocales()
			)
		);
	}

	@Nullable
	private BuiltFieldDescriptor buildListUnknownEntityField() {
		final Builder listUnknownEntityFieldBuilder = CatalogDataApiRootDescriptor.LIST_UNKNOWN_ENTITY
			.to(staticEndpointBuilderTransformer)
			.argument(ListUnknownEntitiesHeaderDescriptor.LIMIT.to(argumentBuilderTransformer));

		// build globally unique attribute filters
		final List<GlobalAttributeSchemaContract> globalAttributes = buildingContext.getCatalog()
			.getSchema()
			.getAttributes()
			.values()
			.stream()
			.filter(GlobalAttributeSchemaContract::isUniqueGlobally)
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

		listUnknownEntityFieldBuilder.argument(ListUnknownEntitiesHeaderDescriptor.JOIN.to(argumentBuilderTransformer));

		return new BuiltFieldDescriptor(
			listUnknownEntityFieldBuilder.build(),
			new ListUnknownEntitiesDataFetcher(
				buildingContext.getEvitaExecutor().orElse(null),
				buildingContext.getSchema(),
				buildingContext.getSupportedLocales()
			)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildGetEntityField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final GraphQLFieldDefinition.Builder singleEntityFieldBuilder = CatalogDataApiRootDescriptor.GET_ENTITY
			.to(new EndpointDescriptorToGraphQLFieldTransformer(propertyDataTypeBuilderTransformer, entitySchema))
			.description(CatalogDataApiRootDescriptor.GET_ENTITY.description(entitySchema))
			.type(typeRef(EntityDescriptor.THIS.name(entitySchema)))
			.argument(GetEntityHeaderDescriptor.PRIMARY_KEY.to(argumentBuilderTransformer));

		// build locale argument
		if (!entitySchema.getLocales().isEmpty()) {
			singleEntityFieldBuilder.argument(GetEntityHeaderDescriptor.LOCALE
				.to(argumentBuilderTransformer)
				.type(typeRef(ENTITY_LOCALE_ENUM.name(entitySchema))));
		}

		// build price arguments
		if (!entitySchema.getCurrencies().isEmpty()) {
			singleEntityFieldBuilder
				.argument(GetEntityHeaderDescriptor.PRICE_IN_CURRENCY
					.to(argumentBuilderTransformer)
					.type(typeRef(ENTITY_CURRENCY_ENUM.name(entitySchema))))
				.argument(GetEntityHeaderDescriptor.PRICE_IN_PRICE_LISTS
					.to(argumentBuilderTransformer))
				.argument(GetEntityHeaderDescriptor.PRICE_VALID_IN
					.to(argumentBuilderTransformer))
				.argument(GetEntityHeaderDescriptor.PRICE_VALID_NOW
					.to(argumentBuilderTransformer));
		}

		// build unique attribute filter arguments
		entitySchema.getAttributes()
			.values()
			.stream()
			.filter(AttributeSchemaContract::isUnique)
			.map(as -> newArgument()
				.name(as.getNameVariant(ARGUMENT_NAME_NAMING_CONVENTION))
				.type(DataTypesConverter.getGraphQLScalarType(as.getPlainType()))
				.description(as.getDescription())
				.deprecate(as.getDeprecationNotice())
				.build())
			.forEach(singleEntityFieldBuilder::argument);

		return new BuiltFieldDescriptor(
			singleEntityFieldBuilder.build(),
			new GetEntityDataFetcher(
				buildingContext.getEvitaExecutor().orElse(null),
				buildingContext.getSchema(),
				entitySchema
			)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildListEntityField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final GraphQLFieldDefinition.Builder entityListFieldBuilder = CatalogDataApiRootDescriptor.LIST_ENTITY
			.to(new EndpointDescriptorToGraphQLFieldTransformer(propertyDataTypeBuilderTransformer, entitySchema))
			.description(CatalogDataApiRootDescriptor.LIST_ENTITY.description(entitySchema))
			.type(nonNull(list(nonNull(typeRef(EntityDescriptor.THIS.name(entitySchema))))))
			.argument(ListEntitiesHeaderDescriptor.FILTER_BY
				.to(argumentBuilderTransformer)
				.type(collectionBuildingContext.getFilterByInputObject()))
			.argument(ListEntitiesHeaderDescriptor.ORDER_BY
				.to(argumentBuilderTransformer)
				.type(collectionBuildingContext.getOrderByInputObject()))
			.argument(ListEntitiesHeaderDescriptor.OFFSET
				.to(argumentBuilderTransformer))
			.argument(ListEntitiesHeaderDescriptor.LIMIT
				.to(argumentBuilderTransformer));

		return new BuiltFieldDescriptor(
			entityListFieldBuilder.build(),
			new ListEntitiesDataFetcher(
				buildingContext.getEvitaExecutor().orElse(null),
				buildingContext.getSchema(),
				entitySchema
			)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildQueryEntityField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final GraphQLObjectType entityFullResponseObject = fullResponseObjectBuilder.build(collectionBuildingContext.getSchema());

		final GraphQLFieldDefinition.Builder entityQueryFieldBuilder = CatalogDataApiRootDescriptor.QUERY_ENTITY
			.to(new EndpointDescriptorToGraphQLFieldTransformer(propertyDataTypeBuilderTransformer, entitySchema))
			.description(CatalogDataApiRootDescriptor.QUERY_ENTITY.description(entitySchema))
			.type(nonNull(entityFullResponseObject))
			.argument(QueryEntitiesHeaderDescriptor.FILTER_BY
				.to(argumentBuilderTransformer)
				.type(collectionBuildingContext.getFilterByInputObject()))
			.argument(QueryEntitiesHeaderDescriptor.ORDER_BY
				.to(argumentBuilderTransformer)
				.type(collectionBuildingContext.getOrderByInputObject()));

		// build require constraints
		// build only if there are any prices or facets because these are only few allowed constraints in require builder
		collectionBuildingContext.getRequireInputObject().ifPresent(requireInputObject -> {
			entityQueryFieldBuilder.argument(QueryEntitiesHeaderDescriptor.REQUIRE
				.to(argumentBuilderTransformer)
				.type(requireInputObject));
		});

		return new BuiltFieldDescriptor(
			entityQueryFieldBuilder.build(),
			new QueryEntitiesDataFetcher(
				buildingContext.getEvitaExecutor().orElse(null),
				buildingContext.getSchema(),
				entitySchema
			)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildCountCollectionField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();
		return new BuiltFieldDescriptor(
			CatalogDataApiRootDescriptor.COUNT_COLLECTION
				.to(new EndpointDescriptorToGraphQLFieldTransformer(propertyDataTypeBuilderTransformer, entitySchema))
				.build(),
			new CollectionSizeDataFetcher(
				buildingContext.getEvitaExecutor().orElse(null),
				entitySchema
			)
		);
	}


	@Nonnull
	private BuiltFieldDescriptor buildUpsertEntityField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final GraphQLFieldDefinition.Builder upsertEntityFieldBuilder = CatalogDataApiRootDescriptor.UPSERT_ENTITY
			.to(new EndpointDescriptorToGraphQLFieldTransformer(propertyDataTypeBuilderTransformer, entitySchema))
			.type(nonNull(typeRef(EntityDescriptor.THIS.name(entitySchema))))
			.argument(UpsertEntityHeaderDescriptor.PRIMARY_KEY
				.to(argumentBuilderTransformer)
				.type(entitySchema.isWithGeneratedPrimaryKey() ? INT : nonNull(INT)))
			.argument(UpsertEntityHeaderDescriptor.ENTITY_EXISTENCE.to(argumentBuilderTransformer));

		final GraphQLInputObjectType localMutationAggregateObject = localMutationAggregateObjectBuilder.build(collectionBuildingContext.getSchema());
		if (localMutationAggregateObject != null) {
			upsertEntityFieldBuilder.argument(UpsertEntityHeaderDescriptor.MUTATIONS
				.to(argumentBuilderTransformer)
				.type(list(nonNull(localMutationAggregateObject))))
				.build();
		}

		return new BuiltFieldDescriptor(
			upsertEntityFieldBuilder.build(),
			new UpsertEntityMutatingDataFetcher(CDO_OBJECT_MAPPER, buildingContext.getSchema(), entitySchema)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildDeleteEntitiesField(@Nonnull CollectionGraphQLSchemaBuildingContext collectionBuildingContext) {
		final EntitySchemaContract entitySchema = collectionBuildingContext.getSchema();

		final GraphQLFieldDefinition deleteEntityByQueryField = CatalogDataApiRootDescriptor.DELETE_ENTITY
			.to(new EndpointDescriptorToGraphQLFieldTransformer(propertyDataTypeBuilderTransformer, entitySchema))
			.type(nonNull(list(nonNull(typeRef(EntityDescriptor.THIS.name(entitySchema))))))
			.argument(DeleteEntitiesMutationHeaderDescriptor.FILTER_BY
				.to(argumentBuilderTransformer)
				.type(collectionBuildingContext.getFilterByInputObject()))
			.argument(DeleteEntitiesMutationHeaderDescriptor.ORDER_BY
				.to(argumentBuilderTransformer)
				.type(collectionBuildingContext.getOrderByInputObject()))
			.argument(DeleteEntitiesMutationHeaderDescriptor.OFFSET
				.to(argumentBuilderTransformer))
			.argument(DeleteEntitiesMutationHeaderDescriptor.LIMIT
				.to(argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			deleteEntityByQueryField,
			new DeleteEntitiesMutatingDataFetcher(buildingContext.getSchema(), entitySchema)
		);
	}

	@Nonnull
	private static String transformLocaleToGraphQLEnumString(@Nonnull Locale locale) {
		return locale.toLanguageTag().replace("-", "_");
	}

	@Nonnull
	private Optional<GraphQLEnumType> buildCatalogEnum() {
		if (buildingContext.getSupportedLocales().isEmpty()) {
			return Optional.empty();
		}

		final GraphQLEnumType catalogLocaleEnum = CATALOG_LOCALE_ENUM
			.to(new ObjectDescriptorToGraphQLEnumTypeTransformer(
				buildingContext.getSupportedLocales().stream()
					.map(locale -> Map.entry(transformLocaleToGraphQLEnumString(locale), locale))
					.collect(Collectors.toSet())
			))
			.build();

		return Optional.of(catalogLocaleEnum);
	}
}
