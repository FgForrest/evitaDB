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
import graphql.schema.*;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.RequestImpact;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.*;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.AttributeHistogramDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.ExtraResultsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetGroupStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetRequestImpactDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor.ParentsOfEntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyParentsDescriptor.ParentsOfEntityDescriptor.ParentsOfReferenceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyStatisticsDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HierarchyStatisticsDescriptor.HierarchyStatisticsLevelInfoDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor.BucketDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.LocalMutationAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.associatedData.RemoveAssociatedDataMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.associatedData.UpsertAssociatedDataMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.ApplyDeltaAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.ReferenceAttributeMutationAggregateDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.RemoveAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.UpsertAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.entity.SetHierarchicalPlacementMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price.RemovePriceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price.SetPriceInnerRecordHandlingMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.price.UpsertPriceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.InsertReferenceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.ReferenceAttributeMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.RemoveReferenceGroupMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.RemoveReferenceMutationDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.mutation.reference.SetReferenceGroupMutationDescriptor;
import io.evitadb.externalApi.graphql.api.builder.BuiltFieldDescriptor;
import io.evitadb.externalApi.graphql.api.builder.FinalGraphQLSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.EntitySchemaGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.NonNullBigDecimalFieldDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.NullableBigDecimalFieldDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.PriceBigDecimalFieldDecorator;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.FilterBySchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.GraphQLConstraintSchemaBuildingContext;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.OrderBySchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.constraint.RequireSchemaBuilder;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.EntityHeaderDescriptor.AssociatedDataFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.EntityHeaderDescriptor.AttributesFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.EntityHeaderDescriptor.PriceFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.EntityHeaderDescriptor.PriceForSaleFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.EntityHeaderDescriptor.PricesFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.GetEntityQueryHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.ListEntitiesQueryHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.ListUnknownEntitiesQueryHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.QueryEntitiesQueryHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.ResponseHeaderDescriptor.BucketsFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.ResponseHeaderDescriptor.QueryTelemetryFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.ResponseHeaderDescriptor.RecordPageFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.ResponseHeaderDescriptor.RecordStripFieldHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.model.UpsertEntityMutationHeaderDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.*;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult.AttributeHistogramDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult.AttributeHistogramsDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult.ExtraResultsDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult.FacetGroupStatisticsDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult.FacetSummaryDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult.HierarchyParentsDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult.HierarchyStatisticsDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult.PriceHistogramDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult.QueryTelemetryDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.mutatingDataFetcher.DeleteEntitiesMutatingDataFetcher;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.mutatingDataFetcher.UpsertEntityMutatingDataFetcher;
import io.evitadb.externalApi.graphql.api.model.EndpointDescriptorToGraphQLFieldTransformer;
import io.evitadb.externalApi.graphql.api.dataType.DataTypesConverter;
import io.evitadb.externalApi.graphql.exception.GraphQLSchemaBuildingError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import static graphql.schema.GraphQLArgument.newArgument;
import static graphql.schema.GraphQLEnumType.newEnum;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLInputObjectType.newInputObject;
import static graphql.schema.GraphQLList.list;
import static graphql.schema.GraphQLNonNull.nonNull;
import static graphql.schema.GraphQLTypeReference.typeRef;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.ARGUMENT_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.FIELD_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.TYPE_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.ENTITY_CURRENCY_ENUM;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.ENTITY_LOCALE_ENUM;
import static io.evitadb.externalApi.graphql.api.dataType.GraphQLScalars.BOOLEAN;
import static io.evitadb.externalApi.graphql.api.dataType.GraphQLScalars.INT;
import static io.evitadb.externalApi.graphql.api.dataType.GraphQLScalars.OBJECT;
import static io.evitadb.externalApi.graphql.api.dataType.GraphQLScalars.STRING;

/**
 * Implementation of {@link FinalGraphQLSchemaBuilder} for building entity data manipulation schema.
 * Fields operate on actual stored entity data.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class CatalogDataApiGraphQLSchemaBuilder extends FinalGraphQLSchemaBuilder<CatalogGraphQLSchemaBuildingContext> {

	private static final ObjectMapper CDO_OBJECT_MAPPER = new ObjectMapper();
	private static final ObjectMapper QUERY_TELEMETRY_OBJECT_MAPPER = new ObjectMapper();
	private static final PriceBigDecimalFieldDecorator PRICE_FIELD_DECORATOR = new PriceBigDecimalFieldDecorator();

	@Nonnull
	private final GraphQLConstraintSchemaBuildingContext constraintContext;

	public CatalogDataApiGraphQLSchemaBuilder(@Nonnull EvitaContract evita, @Nonnull CatalogContract catalog) {
		super(new CatalogGraphQLSchemaBuildingContext(evita, catalog));
		this.constraintContext = new GraphQLConstraintSchemaBuildingContext(context);
	}

	@Override
    @Nonnull
	public GraphQLSchema build() {
		buildCommonTypes();
		buildFields();
		return context.buildGraphQLSchema();
	}

	private void buildCommonTypes() {
		final GraphQLEnumType scalarEnum = buildScalarEnum();
		context.registerType(scalarEnum);
		context.registerType(buildAssociatedDataScalarEnum(scalarEnum));

		context.registerType(EntityDescriptor.THIS_ENTITY_REFERENCE.to(objectBuilderTransformer).build());

		// query objects
		final GraphQLInterfaceType entityClassifierInterface = EntityDescriptor.THIS_INTERFACE.to(interfaceBuilderTransformer).build();
		context.registerType(entityClassifierInterface);
		context.registerTypeResolver(
			entityClassifierInterface,
			new EntityDtoTypeResolver(context.getEntityTypeToEntityObject())
		);
		context.registerType(HierarchicalPlacementDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(buildPriceObject());
		context.registerType(BucketDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(buildHistogramObject());
		// todo lho: remove after https://gitlab.fg.cz/hv/evita/-/issues/120 is implemented
		context.registerType(buildAttributeNamedHistogramObject());
		context.registerType(buildFacetRequestImpactObject());

		// upsert objects
		context.registerType(RemoveAssociatedDataMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(UpsertAssociatedDataMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(ApplyDeltaAttributeMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(RemoveAttributeMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(UpsertAttributeMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(SetHierarchicalPlacementMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(SetPriceInnerRecordHandlingMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(RemovePriceMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(UpsertPriceMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(InsertReferenceMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(RemoveReferenceMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(SetReferenceGroupMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(RemoveReferenceGroupMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(ReferenceAttributeMutationDescriptor.THIS.to(inputObjectBuilderTransformer).build());
		context.registerType(ReferenceAttributeMutationAggregateDescriptor.THIS.to(inputObjectBuilderTransformer).build());
	}

	private void buildFields() {
		// "collections" field
		context.registerQueryField(buildCollectionsField());

		// "get_entity" field
		context.registerQueryField(buildUnknownSingleEntityField());

		// "list_entity" field
		context.registerQueryField(buildUnknownEntityListField());

		// collection-specific fields
		context.getEntitySchemas().forEach(entitySchema -> {
			final EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx = setupForCollection(entitySchema);

			// collection specific "get_entity" field
			context.registerQueryField(buildSingleEntityField(entitySchemaBuildingCtx));

			// collection specific "list_entity" field
			context.registerQueryField(buildEntityListField(entitySchemaBuildingCtx));

			// collection specific "query_entity" field
			context.registerQueryField(buildEntityQueryField(entitySchemaBuildingCtx));

			// collection specific "count_entity" field
			context.registerQueryField(buildCollectionSizeField(entitySchemaBuildingCtx));

			// collection specific "upsert_entity" field
			context.registerMutationField(buildUpsertEntityField(entitySchemaBuildingCtx));

			// collection specific "delete_entity" field
			context.registerMutationField(buildDeleteEntitiesField(entitySchemaBuildingCtx));
		});

		// register gathered custom constraint types
		context.registerTypes(new HashSet<>(constraintContext.getBuiltTypes()));
	}


	/**
	 * Prepare common data for specific collection schema building.
	 */
	@Nonnull
	private EntitySchemaGraphQLSchemaBuildingContext setupForCollection(@Nonnull EntitySchemaContract entitySchema) {
		if (!entitySchema.getLocales().isEmpty()) {
			final String localeEnumName = ENTITY_LOCALE_ENUM.name(entitySchema);
			final GraphQLEnumType.Builder localeEnumBuilder = newEnum()
				.name(localeEnumName)
				.description(CatalogDataApiRootDescriptor.ENTITY_LOCALE_ENUM.description());
			entitySchema.getLocales().forEach(l -> localeEnumBuilder.value(l.toLanguageTag().replace("-", "_"), l));
			context.registerCustomEnumIfAbsent(localeEnumBuilder.build());
		}

		if (!entitySchema.getCurrencies().isEmpty()) {
			final String currencyEnumName = ENTITY_CURRENCY_ENUM.name(entitySchema);
			final GraphQLEnumType.Builder currencyEnumBuilder = newEnum()
				.name(currencyEnumName)
				.description(CatalogDataApiRootDescriptor.ENTITY_CURRENCY_ENUM.description());
			entitySchema.getCurrencies().forEach(c -> currencyEnumBuilder.value(c.toString(), c));
			context.registerCustomEnumIfAbsent(currencyEnumBuilder.build());
		}

		final EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx = new EntitySchemaGraphQLSchemaBuildingContext(
			context,
			entitySchema
		);

		// build filter schema
		final GraphQLType filterBySchemaDescriptor = new FilterBySchemaBuilder(
			constraintContext,
			entitySchemaBuildingCtx.getSchema().getName()
		).build();
		entitySchemaBuildingCtx.setFilterByInputObject(filterBySchemaDescriptor);

		// build order schema
		final GraphQLType orderBySchemaDescriptor = new OrderBySchemaBuilder(
			constraintContext,
			entitySchemaBuildingCtx.getSchema().getName()
		).build();
		entitySchemaBuildingCtx.setOrderByInputObject(orderBySchemaDescriptor);

		// build entity object specific to this schema
		entitySchemaBuildingCtx.registerEntityObject(buildEntityObject(entitySchemaBuildingCtx));

		return entitySchemaBuildingCtx;
	}

	@Nonnull
	private BuiltFieldDescriptor buildCollectionsField() {
		return new BuiltFieldDescriptor(
			CatalogDataApiRootDescriptor.COLLECTIONS.to(staticEndpointBuilderTransformer).build(),
			new CollectionsDataFetcher()
		);
	}

	@Nullable
	private BuiltFieldDescriptor buildUnknownSingleEntityField() {
		final GraphQLFieldDefinition.Builder unknownSingleEntityFieldBuilder = CatalogDataApiRootDescriptor.GET_UNKNOWN_ENTITY
			.to(staticEndpointBuilderTransformer)
 			.type(typeRef(EntityDescriptor.THIS_INTERFACE.name()));

		// build globally unique attribute filters
		final List<GlobalAttributeSchemaContract> globalAttributes = context.getCatalog()
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
			.forEach(unknownSingleEntityFieldBuilder::argument);

		return new BuiltFieldDescriptor(
			unknownSingleEntityFieldBuilder.build(),
			new GetUnknownEntityDataFetcher(context.getSchema(), context.getEntitySchemas())
		);
	}

	@Nullable
	private BuiltFieldDescriptor buildUnknownEntityListField() {
		final GraphQLFieldDefinition.Builder unknownEntityListFieldBuilder = CatalogDataApiRootDescriptor.LIST_UNKNOWN_ENTITY
			.to(staticEndpointBuilderTransformer)
			.type(list(nonNull(typeRef(EntityDescriptor.THIS_INTERFACE.name()))))
			.argument(ListUnknownEntitiesQueryHeaderDescriptor.LIMIT.to(argumentBuilderTransformer));

		// build globally unique attribute filters
		final List<GlobalAttributeSchemaContract> globalAttributes = context.getCatalog()
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
			.forEach(unknownEntityListFieldBuilder::argument);

		return new BuiltFieldDescriptor(
			unknownEntityListFieldBuilder.build(),
			new ListUnknownEntitiesDataFetcher(context.getSchema(), context.getEntitySchemas())
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildSingleEntityField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final GraphQLFieldDefinition.Builder singleEntityFieldBuilder = CatalogDataApiRootDescriptor.GET_ENTITY
			.to(new EndpointDescriptorToGraphQLFieldTransformer(propertyDataTypeBuilderTransformer, entitySchema))
			.type(typeRef(EntityDescriptor.THIS.name(entitySchema)))
			.argument(GetEntityQueryHeaderDescriptor.PRIMARY_KEY.to(argumentBuilderTransformer));

		// build locale argument
		if (!entitySchema.getLocales().isEmpty()) {
			singleEntityFieldBuilder.argument(GetEntityQueryHeaderDescriptor.LOCALE
				.to(argumentBuilderTransformer)
				.type(typeRef(ENTITY_LOCALE_ENUM.name(entitySchema))));
		}

		// build price arguments
		if (!entitySchema.getCurrencies().isEmpty()) {
			singleEntityFieldBuilder
				.argument(GetEntityQueryHeaderDescriptor.PRICE_IN_CURRENCY
					.to(argumentBuilderTransformer)
					.type(typeRef(ENTITY_CURRENCY_ENUM.name(entitySchema))))
				.argument(GetEntityQueryHeaderDescriptor.PRICE_IN_PRICE_LISTS
					.to(argumentBuilderTransformer))
				.argument(GetEntityQueryHeaderDescriptor.PRICE_VALID_IN
					.to(argumentBuilderTransformer))
				.argument(GetEntityQueryHeaderDescriptor.PRICE_VALID_NOW
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
			new GetEntityDataFetcher(context.getSchema(), entitySchema)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityListField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final GraphQLFieldDefinition.Builder entityListFieldBuilder = CatalogDataApiRootDescriptor.LIST_ENTITY
			.to(new EndpointDescriptorToGraphQLFieldTransformer(propertyDataTypeBuilderTransformer, entitySchema))
			.type(nonNull(list(nonNull(typeRef(EntityDescriptor.THIS.name(entitySchema))))))
			.argument(ListEntitiesQueryHeaderDescriptor.FILTER_BY
				.to(argumentBuilderTransformer)
				.type(entitySchemaBuildingCtx.getFilterByInputObject()))
			.argument(ListEntitiesQueryHeaderDescriptor.ORDER_BY
				.to(argumentBuilderTransformer)
				.type(entitySchemaBuildingCtx.getOrderByInputObject()))
			.argument(ListEntitiesQueryHeaderDescriptor.LIMIT
				.to(argumentBuilderTransformer));

		return new BuiltFieldDescriptor(
			entityListFieldBuilder.build(),
			new ListEntitiesDataFetcher(context.getSchema(), entitySchema)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityQueryField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final GraphQLObjectType entityFullResponseObject = buildFullResponseObject(entitySchemaBuildingCtx);

		final GraphQLFieldDefinition.Builder entityQueryFieldBuilder = CatalogDataApiRootDescriptor.QUERY_ENTITY
			.to(new EndpointDescriptorToGraphQLFieldTransformer(propertyDataTypeBuilderTransformer, entitySchema))
			.type(nonNull(entityFullResponseObject))
			.argument(QueryEntitiesQueryHeaderDescriptor.FILTER_BY
				.to(argumentBuilderTransformer)
				.type(entitySchemaBuildingCtx.getFilterByInputObject()))
			.argument(QueryEntitiesQueryHeaderDescriptor.ORDER_BY
				.to(argumentBuilderTransformer)
				.type(entitySchemaBuildingCtx.getOrderByInputObject()));

		// build require constraints
		// build only if there are any prices or facets because these are only allowed constraints in require builder
		if (!entitySchema.getCurrencies().isEmpty() ||
			entitySchema.getReferences().values().stream().anyMatch(ReferenceSchemaContract::isFaceted)) {
			final GraphQLType requireSchemaDescriptor = new RequireSchemaBuilder(
				constraintContext,
				entitySchemaBuildingCtx.getSchema().getName()
			).build();

			entityQueryFieldBuilder.argument(QueryEntitiesQueryHeaderDescriptor.REQUIRE
				.to(argumentBuilderTransformer)
				.type((GraphQLInputType) requireSchemaDescriptor));
		}

		return new BuiltFieldDescriptor(
			entityQueryFieldBuilder.build(),
			new QueryEntitiesDataFetcher(context.getSchema(), entitySchema)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildCollectionSizeField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();
		return new BuiltFieldDescriptor(
			CatalogDataApiRootDescriptor.COUNT_COLLECTION
				.to(new EndpointDescriptorToGraphQLFieldTransformer(propertyDataTypeBuilderTransformer, entitySchema))
				.build(),
			new CollectionSizeDataFetcher(entitySchema)
		);
	}


	@Nonnull
	private GraphQLObjectType buildEntityObject(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		// build specific entity object
		final GraphQLObjectType.Builder entityObjectBuilder = EntityDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(entitySchema.getNameVariant(TYPE_NAME_NAMING_CONVENTION))
			.description(entitySchema.getDescription())
			.withInterface(typeRef(EntityDescriptor.THIS_INTERFACE.name()));

		// build locale fields
		if (!entitySchema.getLocales().isEmpty()) {
			entityObjectBuilder.field(EntityDescriptor.LOCALES.to(fieldBuilderTransformer));
			entityObjectBuilder.field(EntityDescriptor.ALL_LOCALES.to(fieldBuilderTransformer));
		}

		// build hierarchy placement field
		if (entitySchema.isWithHierarchy()) {
			entityObjectBuilder.field(EntityDescriptor.HIERARCHICAL_PLACEMENT.to(fieldBuilderTransformer));
		}

		// build price fields
		if (!entitySchema.getCurrencies().isEmpty()) {
			entitySchemaBuildingCtx.registerEntityField(
				entityObjectBuilder,
				buildEntityPriceForSaleField(entitySchemaBuildingCtx)
			);

			entitySchemaBuildingCtx.registerEntityField(
				entityObjectBuilder,
				buildEntityPriceField(entitySchemaBuildingCtx)
			);

			entitySchemaBuildingCtx.registerEntityField(
				entityObjectBuilder,
				buildEntityPricesField(entitySchemaBuildingCtx)
			);

			entityObjectBuilder.field(EntityDescriptor.PRICE_INNER_RECORD_HANDLING.to(fieldBuilderTransformer));
		}

		// build attributes
		if (!entitySchema.getAttributes().isEmpty()) {
			entitySchemaBuildingCtx.registerEntityField(
				entityObjectBuilder,
				buildEntityAttributesField(entitySchemaBuildingCtx)
			);
		}

		// build associated data fields
		if (!entitySchema.getAssociatedData().isEmpty()) {
			entitySchemaBuildingCtx.registerEntityField(
				entityObjectBuilder,
				buildEntityAssociatedDataField(entitySchemaBuildingCtx)
			);
		}

		// build reference fields
		if (!entitySchema.getReferences().isEmpty()) {
			final List<BuiltFieldDescriptor> referenceFieldDescriptors = buildEntityReferenceFields(entitySchemaBuildingCtx);
			referenceFieldDescriptors.forEach(referenceFieldDescriptor -> entitySchemaBuildingCtx.registerEntityField(
				entityObjectBuilder,
				referenceFieldDescriptor
			));
		}

		return entityObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityPriceForSaleField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final GraphQLFieldDefinition field = EntityDescriptor.PRICE_FOR_SALE
			.to(fieldBuilderTransformer)
			.argument(PriceForSaleFieldHeaderDescriptor.PRICE_LIST
				.to(argumentBuilderTransformer))
			.argument(PriceForSaleFieldHeaderDescriptor.CURRENCY
				.to(argumentBuilderTransformer)
				.type(typeRef(ENTITY_CURRENCY_ENUM.name(entitySchema))))
			.argument(PriceForSaleFieldHeaderDescriptor.VALID_IN
				.to(argumentBuilderTransformer))
			.argument(PriceForSaleFieldHeaderDescriptor.VALID_NOW
				.to(argumentBuilderTransformer))
			.argument(PriceForSaleFieldHeaderDescriptor.LOCALE
				.to(argumentBuilderTransformer)
				.type(typeRef(ENTITY_LOCALE_ENUM.name(entitySchema))))
			.build();

		return new BuiltFieldDescriptor(field, new PriceForSaleDataFetcher());
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityPriceField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final GraphQLFieldDefinition field = EntityDescriptor.PRICE
			.to(fieldBuilderTransformer)
			.argument(PriceFieldHeaderDescriptor.PRICE_LIST
				.to(argumentBuilderTransformer))
			.argument(PriceFieldHeaderDescriptor.CURRENCY
				.to(argumentBuilderTransformer)
				.type(typeRef(ENTITY_CURRENCY_ENUM.name(entitySchema))))
			.argument(PriceFieldHeaderDescriptor.LOCALE
				.to(argumentBuilderTransformer)
				.type(typeRef(ENTITY_LOCALE_ENUM.name(entitySchema))))
			.build();

		return new BuiltFieldDescriptor(field, new PriceDataFetcher());
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityPricesField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final GraphQLFieldDefinition field = EntityDescriptor.PRICES
			.to(fieldBuilderTransformer)
			.argument(PricesFieldHeaderDescriptor.PRICE_LISTS
				.to(argumentBuilderTransformer))
			.argument(PricesFieldHeaderDescriptor.CURRENCY
				.to(argumentBuilderTransformer)
				.type(typeRef(ENTITY_CURRENCY_ENUM.name(entitySchema))))
			.argument(PricesFieldHeaderDescriptor.LOCALE
				.to(argumentBuilderTransformer)
				.type(typeRef(ENTITY_LOCALE_ENUM.name(entitySchema))))
			.build();

		return new BuiltFieldDescriptor(field, new PricesDataFetcher());
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityAttributesField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();
		final GraphQLObjectType attributesObject = buildAttributesObject(
			entitySchema.getAttributes().values(),
			AttributesDescriptor.THIS.name(entitySchema)
		);

		final GraphQLFieldDefinition.Builder attributesFieldBuilder = EntityDescriptor.ATTRIBUTES
			.to(fieldBuilderTransformer)
			.type(nonNull(attributesObject));

		if (!entitySchema.getLocales().isEmpty()) {
			attributesFieldBuilder.argument(AttributesFieldHeaderDescriptor.LOCALE
				.to(argumentBuilderTransformer)
				.type(typeRef(ENTITY_LOCALE_ENUM.name(entitySchema))));
		}

		return new BuiltFieldDescriptor(
			attributesFieldBuilder.build(),
			new AttributesDataFetcher()
		);
	}

	@Nonnull
	private GraphQLObjectType buildAttributesObject(@Nonnull Collection<AttributeSchemaContract> attributeSchemas,
	                                                @Nonnull String objectName) {
		final GraphQLObjectType.Builder attributesBuilder = AttributesDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		attributeSchemas.forEach(attributeSchema ->
			context.registerFieldToObject(
				objectName,
				attributesBuilder,
				buildAttributeField(attributeSchema)
			)
		);

		return attributesBuilder.build();
	}

	@Nonnull
	private static BuiltFieldDescriptor buildAttributeField(@Nonnull AttributeSchemaContract attributeSchema) {
		final GraphQLFieldDefinition.Builder attributeFieldBuilder = newFieldDefinition()
			.name(attributeSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
			.description(attributeSchema.getDescription())
			.deprecate(attributeSchema.getDeprecationNotice());
		final DataFetcher<?> attributeFieldDataFetcher;

		final Class<? extends Serializable> attributeType = attributeSchema.getType();
		if (BigDecimal.class.isAssignableFrom(attributeType)) {
			if (attributeSchema.isNullable()) {
				new NullableBigDecimalFieldDecorator().accept(attributeFieldBuilder);
			} else {
				new NonNullBigDecimalFieldDecorator().accept(attributeFieldBuilder);
			}

			attributeFieldDataFetcher = new BigDecimalDataFetcher(new AttributeValueDataFetcher<>(attributeSchema));
		} else {
			attributeFieldBuilder.type(
				(GraphQLOutputType) DataTypesConverter.getGraphQLScalarType(attributeType, !attributeSchema.isNullable())
			);
			attributeFieldDataFetcher = new AttributeValueDataFetcher<>(attributeSchema);
		}

		return new BuiltFieldDescriptor(
			attributeFieldBuilder.build(),
			attributeFieldDataFetcher
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildEntityAssociatedDataField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();
		final GraphQLObjectType associatedDataObject = buildAssociatedDataObject(entitySchemaBuildingCtx);

		final GraphQLFieldDefinition.Builder associatedDataFieldBuilder = EntityDescriptor.ASSOCIATED_DATA
			.to(fieldBuilderTransformer)
			.type(nonNull(associatedDataObject));

		if (!entitySchema.getLocales().isEmpty()) {
			associatedDataFieldBuilder.argument(AssociatedDataFieldHeaderDescriptor.LOCALE
				.to(argumentBuilderTransformer)
				.type(typeRef(ENTITY_LOCALE_ENUM.name(entitySchema))));
		}

		return new BuiltFieldDescriptor(
			associatedDataFieldBuilder.build(),
			new AssociatedDataDataFetcher()
		);
	}

	@Nonnull
	private GraphQLObjectType buildAssociatedDataObject(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final String objectName = AssociatedDataDescriptor.THIS.name(entitySchemaBuildingCtx.getSchema());
		final GraphQLObjectType.Builder associatedDataBuilder = AssociatedDataDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		entitySchemaBuildingCtx.getSchema().getAssociatedData().values().forEach(associatedDataSchema ->
			context.registerFieldToObject(
				objectName,
				associatedDataBuilder,
				buildSingleAssociatedDataField(associatedDataSchema)
			)
		);

		return associatedDataBuilder.build();
	}

	@Nonnull
	private static BuiltFieldDescriptor buildSingleAssociatedDataField(@Nonnull AssociatedDataSchemaContract associatedDataSchema) {
		final GraphQLFieldDefinition.Builder associatedDataFieldBuilder = newFieldDefinition();
		final DataFetcher<?> associatedDataFieldDataFetcher;

		final Class<? extends Serializable> associatedDataType = associatedDataSchema.getType();
		if (BigDecimal.class.isAssignableFrom(associatedDataType)) {
			if (associatedDataSchema.isNullable()) {
				new NullableBigDecimalFieldDecorator().accept(associatedDataFieldBuilder);
			} else {
				new NonNullBigDecimalFieldDecorator().accept(associatedDataFieldBuilder);
			}

			associatedDataFieldDataFetcher = new BigDecimalDataFetcher(new AssociatedDataValueDataFetcher<>(CDO_OBJECT_MAPPER, associatedDataSchema));
		} else {
			associatedDataFieldBuilder.type(
				(GraphQLOutputType) DataTypesConverter.getGraphQLScalarType(associatedDataType, !associatedDataSchema.isNullable())
			);
			associatedDataFieldDataFetcher = new AssociatedDataValueDataFetcher<>(CDO_OBJECT_MAPPER, associatedDataSchema);
		}

		associatedDataFieldBuilder
			.name(associatedDataSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
			.description(associatedDataSchema.getDescription())
			.deprecate(associatedDataSchema.getDeprecationNotice());

		return new BuiltFieldDescriptor(
			associatedDataFieldBuilder.build(),
			associatedDataFieldDataFetcher
		);
	}

	@Nonnull
	private List<BuiltFieldDescriptor> buildEntityReferenceFields(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final Collection<ReferenceSchemaContract> referenceSchemas = entitySchemaBuildingCtx.getSchema().getReferences().values();

		return referenceSchemas.stream()
			.map(referenceSchema -> {
				final GraphQLObjectType referenceObject = buildReferenceObject(entitySchemaBuildingCtx, referenceSchema);

				final GraphQLFieldDefinition.Builder referenceFieldBuilder = newFieldDefinition()
					.name(referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
					.description(referenceSchema.getDescription())
					.deprecate(referenceSchema.getDeprecationNotice());

				switch (referenceSchema.getCardinality()) {
					case ZERO_OR_ONE -> referenceFieldBuilder.type(referenceObject);
					case EXACTLY_ONE -> referenceFieldBuilder.type(nonNull(referenceObject));
					case ZERO_OR_MORE, ONE_OR_MORE -> referenceFieldBuilder.type(nonNull(list(nonNull(referenceObject))));
				}

				final DataFetcher<?> referenceDataFetcher = switch (referenceSchema.getCardinality()) {
					case ZERO_OR_ONE, EXACTLY_ONE -> new ReferenceDataFetcher(referenceSchema);
					case ZERO_OR_MORE, ONE_OR_MORE -> new ReferencesDataFetcher(referenceSchema);
				};

				return new BuiltFieldDescriptor(
					referenceFieldBuilder.build(),
					referenceDataFetcher
				);
			})
			.toList();
	}

	@Nonnull
	private GraphQLObjectType buildReferenceObject(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx,
	                                               @Nonnull ReferenceSchemaContract referenceSchema) {
		final String referenceObjectName = ReferenceDescriptor.THIS.name(entitySchemaBuildingCtx.getSchema(), referenceSchema);

		final GraphQLObjectType.Builder referenceObjectBuilder = ReferenceDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(referenceObjectName)
			.description(referenceSchema.getDescription());

		context.registerFieldToObject(
			referenceObjectName,
			referenceObjectBuilder,
			buildReferenceReferencedEntityField(referenceSchema)
		);

		if (referenceSchema.getReferencedGroupType() != null) {
			context.registerFieldToObject(
				referenceObjectName,
				referenceObjectBuilder,
				buildReferenceGroupEntityField(referenceSchema)
			);
		}

		if (!referenceSchema.getAttributes().isEmpty()) {
			context.registerFieldToObject(
				referenceObjectName,
				referenceObjectBuilder,
				buildReferenceAttributesField(entitySchemaBuildingCtx, referenceSchema)
			);
		}

		return referenceObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildReferenceAttributesField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx,
	                                                           @Nonnull ReferenceSchemaContract referenceSchema) {
		final String referenceAttributesObjectName = AttributesDescriptor.THIS.name(entitySchemaBuildingCtx.getSchema(), referenceSchema);

		final GraphQLObjectType attributesObject = buildAttributesObject(
			referenceSchema.getAttributes().values(),
			referenceAttributesObjectName
		);

		final GraphQLFieldDefinition attributesField = ReferenceDescriptor.ATTRIBUTES
			.to(fieldBuilderTransformer)
			.type(nonNull(attributesObject))
			.build();

		return new BuiltFieldDescriptor(
			attributesField,
			new AttributesDataFetcher()
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildReferenceReferencedEntityField(@Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract referencedEntitySchema;
		if (referenceSchema.isReferencedEntityTypeManaged()) {
			referencedEntitySchema = context
				.getSchema()
				.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType());
		} else {
			referencedEntitySchema = null;
		}
		final GraphQLOutputType referencedEntityObject = buildReferencedEntityObject(referencedEntitySchema);

		final GraphQLFieldDefinition referencedEntityField = ReferenceDescriptor.REFERENCED_ENTITY
			.to(fieldBuilderTransformer)
			.type(nonNull(referencedEntityObject))
			.build();

		return new BuiltFieldDescriptor(
			referencedEntityField,
			new ReferencedEntityDataFetcher()
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildReferenceGroupEntityField(@Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract referencedEntitySchema;
		if (referenceSchema.isReferencedGroupTypeManaged()) {
			referencedEntitySchema = context
				.getSchema()
				.getEntitySchemaOrThrowException(referenceSchema.getReferencedGroupType());
		} else {
			referencedEntitySchema = null;
		}
		final GraphQLOutputType referencedEntityObject = buildReferencedEntityObject(referencedEntitySchema);

		final GraphQLFieldDefinition referencedEntityField = ReferenceDescriptor.GROUP_ENTITY
			.to(fieldBuilderTransformer)
			.type(nonNull(referencedEntityObject))
			.build();

		return new BuiltFieldDescriptor(
			referencedEntityField,
			new ReferencedGroupDataFetcher()
		);
	}

	@Nonnull
	private GraphQLObjectType buildFullResponseObject(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();
		final String objectName = ResponseDescriptor.THIS.name(entitySchema);

		final GraphQLObjectType.Builder responseObjectBuilder = ResponseDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		final List<BuiltFieldDescriptor> responseFields = new LinkedList<>();

		responseFields.add(buildRecordPageField(entitySchemaBuildingCtx));
		responseFields.add(buildRecordStripField(entitySchemaBuildingCtx));
		buildExtraResultsField(entitySchemaBuildingCtx).ifPresent(responseFields::add);

		responseFields.forEach(responseField ->
			context.registerFieldToObject(
				objectName,
				responseObjectBuilder,
				responseField
			)
		);

		return responseObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildRecordPageField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final GraphQLObjectType recordPageObject = buildRecordPageObject(entitySchemaBuildingCtx);

		final GraphQLFieldDefinition recordPageField = ResponseDescriptor.RECORD_PAGE
			.to(fieldBuilderTransformer)
			.type(recordPageObject)
			.argument(RecordPageFieldHeaderDescriptor.NUMBER.to(argumentBuilderTransformer))
			.argument(RecordPageFieldHeaderDescriptor.SIZE.to(argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			recordPageField,
			new RecordPageDataFetcher()
		);
	}

	@Nonnull
	private GraphQLObjectType buildRecordPageObject(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final String objectName = RecordPageDescriptor.THIS.name(entitySchema);

		return RecordPageDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName)
			.field(DataChunkDescriptor.DATA
				.to(fieldBuilderTransformer)
				.type(nonNull(list(nonNull(typeRef(EntityDescriptor.THIS.name(entitySchema)))))))
			.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildRecordStripField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final GraphQLObjectType recordStripObject = buildRecordStripObject(entitySchemaBuildingCtx);

		final GraphQLFieldDefinition recordStripField = ResponseDescriptor.RECORD_STRIP
			.to(fieldBuilderTransformer)
			.type(recordStripObject)
			.argument(RecordStripFieldHeaderDescriptor.OFFSET.to(argumentBuilderTransformer))
			.argument(RecordStripFieldHeaderDescriptor.LIMIT.to(argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			recordStripField,
			new RecordStripDataFetcher()
		);
	}

	@Nonnull
	private GraphQLObjectType buildRecordStripObject(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final String objectName = RecordStripDescriptor.THIS.name(entitySchemaBuildingCtx.getSchema());

		return RecordStripDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName)
			.field(DataChunkDescriptor.DATA
				.to(fieldBuilderTransformer)
				.type(nonNull(list(nonNull(typeRef(EntityDescriptor.THIS.name(entitySchemaBuildingCtx.getSchema())))))))
			.build();
	}

	@Nonnull
	private Optional<BuiltFieldDescriptor> buildExtraResultsField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final Optional<GraphQLObjectType> extraResultsObject = buildExtraResultsObject(entitySchemaBuildingCtx);
		if (extraResultsObject.isEmpty()) {
			return Optional.empty();
		}

		final GraphQLFieldDefinition extraResultsField = ResponseDescriptor.EXTRA_RESULTS
			.to(fieldBuilderTransformer)
			.type(nonNull(extraResultsObject.get()))
			.build();

		return Optional.of(new BuiltFieldDescriptor(
			extraResultsField,
			new ExtraResultsDataFetcher()
		));
	}

	@Nonnull
	private Optional<GraphQLObjectType> buildExtraResultsObject(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final String objectName = ExtraResultsDescriptor.THIS.name(entitySchema);

		final GraphQLObjectType.Builder extraResultsObjectBuilder = ExtraResultsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		final List<BuiltFieldDescriptor> extraResultFields = new ArrayList<>(10);

		buildAttributeHistogramField(entitySchemaBuildingCtx).ifPresent(extraResultFields::add);
		// todo lho: remove after https://gitlab.fg.cz/hv/evita/-/issues/120 is implemented
		buildAttributeHistogramsField(entitySchemaBuildingCtx).ifPresent(extraResultFields::add);
		buildPriceHistogramField(entitySchemaBuildingCtx).ifPresent(extraResultFields::add);
		buildFacetSummaryField(entitySchemaBuildingCtx).ifPresent(extraResultFields::add);
		extraResultFields.addAll(buildHierarchyExtraResultFields(entitySchemaBuildingCtx));
		extraResultFields.add(buildQueryTelemetryField());

		if (extraResultFields.isEmpty()) {
			return Optional.empty();
		}

		extraResultFields.forEach(extraResultField ->
			context.registerFieldToObject(
				objectName,
				extraResultsObjectBuilder,
				extraResultField
			)
		);
		return Optional.of(extraResultsObjectBuilder.build());
	}

	@Nonnull
	private Optional<BuiltFieldDescriptor> buildAttributeHistogramField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final Optional<GraphQLObjectType> attributeHistogramObject = buildAttributeHistogramObject(entitySchemaBuildingCtx);
		if (attributeHistogramObject.isEmpty()) {
			return Optional.empty();
		}

		final GraphQLFieldDefinition attributeHistogramField = ExtraResultsDescriptor.ATTRIBUTE_HISTOGRAM
			.to(fieldBuilderTransformer)
			.type(attributeHistogramObject.get())
			.build();

		return Optional.of(new BuiltFieldDescriptor(
			attributeHistogramField,
			new AttributeHistogramDataFetcher()
		));
	}

	@Nonnull
	private Optional<GraphQLObjectType> buildAttributeHistogramObject(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final List<AttributeSchemaContract> attributeSchemas = entitySchema
			.getAttributes()
			.values()
			.stream()
			.filter(attributeSchema -> attributeSchema.isFilterable() &&
				Number.class.isAssignableFrom(attributeSchema.getPlainType()))
			.toList();

		if (attributeSchemas.isEmpty()) {
			return Optional.empty();
		}

		final GraphQLObjectType.Builder attributeHistogramsObjectBuilder = AttributeHistogramDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(AttributeHistogramDescriptor.THIS.name(entitySchema));
		attributeSchemas.forEach(attributeSchema ->
			attributeHistogramsObjectBuilder.field(f -> f
				.name(attributeSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
				.type(typeRef(HistogramDescriptor.THIS.name())))
		);

		return Optional.of(attributeHistogramsObjectBuilder.build());
	}

	// todo lho: remove after https://gitlab.fg.cz/hv/evita/-/issues/120 is implemented
	@Nonnull
	private static Optional<BuiltFieldDescriptor> buildAttributeHistogramsField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final GraphQLFieldDefinition attributeHistogramField = newFieldDefinition()
			.name("attributeHistograms")
			.type(list(nonNull(typeRef("AttributeNamedHistogram"))))
			.argument(a -> a
				.name("attributes")
				.type(nonNull(list(nonNull(STRING)))))
			.build();

		return Optional.of(new BuiltFieldDescriptor(
			attributeHistogramField,
			new AttributeHistogramsDataFetcher(entitySchemaBuildingCtx.getSchema())
		));
	}

	@Nonnull
	private Optional<BuiltFieldDescriptor> buildPriceHistogramField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		if (entitySchemaBuildingCtx.getSchema().getCurrencies().isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(new BuiltFieldDescriptor(
			ExtraResultsDescriptor.PRICE_HISTOGRAM.to(fieldBuilderTransformer).build(),
			new PriceHistogramDataFetcher()
		));
	}

	@Nonnull
	private Optional<BuiltFieldDescriptor> buildFacetSummaryField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final Optional<GraphQLObjectType> facetSummaryObject = buildFacetSummaryObject(entitySchemaBuildingCtx);
		if (facetSummaryObject.isEmpty()) {
			return Optional.empty();
		}

		final GraphQLFieldDefinition facetSummaryField = ExtraResultsDescriptor.FACET_SUMMARY
			.to(fieldBuilderTransformer)
			.type(facetSummaryObject.get())
			.build();

		return Optional.of(new BuiltFieldDescriptor(
			facetSummaryField,
			new FacetSummaryDataFetcher()
		));
	}

	@Nonnull
	private Optional<GraphQLObjectType> buildFacetSummaryObject(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();
		final List<ReferenceSchemaContract> referenceSchemas = entitySchema
			.getReferences()
			.values()
			.stream()
			.filter(ReferenceSchemaContract::isFaceted)
			.toList();

		if (referenceSchemas.isEmpty()) {
			return Optional.empty();
		}

		final String objectName = FacetSummaryDescriptor.THIS.name(entitySchema);

		final GraphQLObjectType.Builder facetSummaryObjectBuilder = FacetSummaryDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);


		referenceSchemas.forEach(referenceSchema -> {
			final BuiltFieldDescriptor facetGroupStatisticsField = buildFacetGroupStatisticsField(
				entitySchemaBuildingCtx,
				referenceSchema
			);

			context.registerFieldToObject(
				objectName,
				facetSummaryObjectBuilder,
				facetGroupStatisticsField
			);
		});

		return Optional.of(facetSummaryObjectBuilder.build());
	}

	@Nonnull
	private BuiltFieldDescriptor buildFacetGroupStatisticsField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx,
	                                                            @Nonnull ReferenceSchemaContract referenceSchema) {
		final GraphQLObjectType facetGroupStatisticsObject = buildFacetGroupStatisticsObject(
			entitySchemaBuildingCtx,
			referenceSchema
		);

		final GraphQLFieldDefinition facetGroupStatisticsField =  newFieldDefinition()
			.name(referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
			.type(list(nonNull(facetGroupStatisticsObject)))
			.build();

		return new BuiltFieldDescriptor(
			facetGroupStatisticsField,
			new FacetGroupStatisticsDataFetcher(referenceSchema)
		);
	}

	@Nonnull
	private GraphQLObjectType buildFacetGroupStatisticsObject(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx,
	                                                          @Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract groupEntitySchema = referenceSchema.isReferencedGroupTypeManaged() ?
			Optional.ofNullable(referenceSchema.getReferencedGroupType())
				.map(groupType -> context
					.getSchema()
					.getEntitySchemaOrThrowException(groupType))
				.orElse(null) :
			null;

		final GraphQLOutputType groupEntityObject = buildReferencedEntityObject(groupEntitySchema);
		final GraphQLObjectType facetStatisticsObject = buildFacetStatisticsObject(entitySchemaBuildingCtx, referenceSchema);

		return FacetGroupStatisticsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(FacetGroupStatisticsDescriptor.THIS.name(entitySchemaBuildingCtx.getSchema(), referenceSchema))
			.field(FacetGroupStatisticsDescriptor.GROUP_ENTITY
				.to(fieldBuilderTransformer)
				.type(groupEntityObject))
			.field(FacetGroupStatisticsDescriptor.FACET_STATISTICS
				.to(fieldBuilderTransformer)
				.type(nonNull(list(nonNull(facetStatisticsObject)))))
			.build();
	}

	@Nonnull
	private GraphQLObjectType buildFacetStatisticsObject(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx,
	                                                     @Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract facetEntitySchema = referenceSchema.isReferencedEntityTypeManaged() ?
			context
				.getSchema()
				.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType()) :
			null;
		final GraphQLOutputType facetEntityObject = buildReferencedEntityObject(facetEntitySchema);

		return FacetStatisticsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(FacetStatisticsDescriptor.THIS.name(entitySchemaBuildingCtx.getSchema(), referenceSchema))
			.field(FacetStatisticsDescriptor.FACET_ENTITY
				.to(fieldBuilderTransformer)
				.type(facetEntityObject))
			.build();
	}

	@Nonnull
	private List<BuiltFieldDescriptor> buildHierarchyExtraResultFields(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final List<ReferenceSchemaContract> referenceSchemas = entitySchema
			.getReferences()
			.values()
			.stream()
			.filter(referenceSchema -> referenceSchema.isReferencedEntityTypeManaged() &&
				context.getSchema().getEntitySchema(referenceSchema.getReferencedEntityType())
					.map(EntitySchemaContract::isWithHierarchy)
					.orElseThrow(() -> new GraphQLSchemaBuildingError("Reference `" + referenceSchema.getName() + "` should have existing entity schema but no schema found.")))
			.toList();

		if (referenceSchemas.isEmpty() && !entitySchema.isWithHierarchy()) {
			return List.of();
		}

		final List<BuiltFieldDescriptor> hierarchyExtraResultFields = new ArrayList<>(2);

		final GraphQLObjectType parentsObject = buildParentsObject(entitySchemaBuildingCtx, referenceSchemas);
		final GraphQLFieldDefinition parentsField = ExtraResultsDescriptor.HIERARCHY_PARENTS
			.to(fieldBuilderTransformer)
			.type(parentsObject)
			.build();
		hierarchyExtraResultFields.add(new BuiltFieldDescriptor(
			parentsField,
			new HierarchyParentsDataFetcher(entitySchema.getReferences().values())
		));

		final GraphQLObjectType hierarchyStatisticsObject = buildHierarchyStatisticsObject(entitySchemaBuildingCtx, referenceSchemas);
		final GraphQLFieldDefinition hierarchyStatisticsField = ExtraResultsDescriptor.HIERARCHY_STATISTICS
			.to(fieldBuilderTransformer)
			.type(hierarchyStatisticsObject)
			.build();
		hierarchyExtraResultFields.add(new BuiltFieldDescriptor(
			hierarchyStatisticsField,
			new HierarchyStatisticsDataFetcher(entitySchema.getReferences().values())
		));

		return hierarchyExtraResultFields;
	}

	@Nonnull
	private GraphQLObjectType buildParentsObject(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx,
	                                             @Nonnull List<ReferenceSchemaContract> referenceSchemas) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final String objectName = HierarchyParentsDescriptor.THIS.name(entitySchema);

		final GraphQLObjectType.Builder parentsObjectBuilder = HierarchyParentsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		if (entitySchema.isWithHierarchy()) {
			context.registerFieldToObject(
				objectName,
				parentsObjectBuilder,
				buildSelfParentsOfEntityField(entitySchemaBuildingCtx)
			);
		}
		referenceSchemas.forEach(referenceSchema ->
			context.registerFieldToObject(
				objectName,
				parentsObjectBuilder,
				buildParentsOfEntityField(entitySchemaBuildingCtx, referenceSchema)
			)
		);

		return parentsObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildSelfParentsOfEntityField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final GraphQLObjectType parentsOfEntityObject = buildSelfParentsOfEntityObject(entitySchemaBuildingCtx);

		final GraphQLFieldDefinition parentsField = HierarchyParentsDescriptor.SELF
			.to(fieldBuilderTransformer)
			.type(list(nonNull(parentsOfEntityObject)))
			.build();

		return new BuiltFieldDescriptor(parentsField, null);
	}

	@Nonnull
	private GraphQLObjectType buildSelfParentsOfEntityObject(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final String objectName = ParentsOfEntityDescriptor.THIS.name(entitySchema, entitySchema);

		final GraphQLObjectType.Builder parentsOfEntityObjectBuilder = ParentsOfEntityDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		context.registerFieldToObject(
			objectName,
			parentsOfEntityObjectBuilder,
			buildSelfParentsOfEntityParentEntitiesField(entitySchemaBuildingCtx)
		);

		return parentsOfEntityObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildSelfParentsOfEntityParentEntitiesField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final GraphQLFieldDefinition parentEntitiesField = ParentsOfEntityDescriptor.PARENT_ENTITIES
			.to(fieldBuilderTransformer)
			.type(nonNull(list(nonNull(typeRef(EntityDescriptor.THIS.name(entitySchemaBuildingCtx.getSchema()))))))
			.build();

		return new BuiltFieldDescriptor(
			parentEntitiesField,
			new SingleParentsOfReferenceDataFetcher()
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildParentsOfEntityField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx,
	                                                       @Nonnull ReferenceSchemaContract referenceSchema) {
		final GraphQLObjectType parentsOfEntityObject = buildParentsOfEntityObject(entitySchemaBuildingCtx, referenceSchema);

		final GraphQLFieldDefinition singleParentsField = newFieldDefinition()
			.name(referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
			.type(list(nonNull(parentsOfEntityObject)))
			.build();

		return new BuiltFieldDescriptor(singleParentsField, null);
	}

	@Nonnull
	private GraphQLObjectType buildParentsOfEntityObject(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx,
	                                                     @Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final String objectName = ParentsOfEntityDescriptor.THIS.name(entitySchema, referenceSchema);

		final GraphQLObjectType.Builder parentsOfEntityObjectBuilder = ParentsOfEntityDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		context.registerFieldToObject(
			objectName,
			parentsOfEntityObjectBuilder,
			buildParentsOfEntityParentEntitiesField(referenceSchema)
		);

		context.registerFieldToObject(
			objectName,
			parentsOfEntityObjectBuilder,
			buildParentsOfEntityReferencesField(entitySchemaBuildingCtx, referenceSchema)
		);

		return parentsOfEntityObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildParentsOfEntityReferencesField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx,
	                                                                 @Nonnull ReferenceSchemaContract referenceSchema) {
		final GraphQLObjectType object = buildParentsOfEntityReferencesObject(entitySchemaBuildingCtx, referenceSchema);

		final GraphQLFieldDefinition referencesField = ParentsOfEntityDescriptor.REFERENCES
			.to(fieldBuilderTransformer)
			.type(nonNull(list(nonNull(object))))
			.build();

		return new BuiltFieldDescriptor(referencesField, null);
	}

	@Nonnull
	private GraphQLObjectType buildParentsOfEntityReferencesObject(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx,
	                                                               @Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract referencedEntitySchema = context
			.getSchema()
			.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType());
		final String referencedEntityObjectName = referencedEntitySchema.getNameVariant(TYPE_NAME_NAMING_CONVENTION);

		return ParentsOfReferenceDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(ParentsOfReferenceDescriptor.THIS.name(entitySchemaBuildingCtx.getSchema(), referenceSchema))
			.field(ParentsOfReferenceDescriptor.PARENT_ENTITIES
				.to(fieldBuilderTransformer)
				.type(nonNull(list(nonNull(typeRef(referencedEntityObjectName))))))
			.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildParentsOfEntityParentEntitiesField(@Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract referencedEntitySchema = context
			.getSchema()
			.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType());
		final String referencedEntityObjectName = referencedEntitySchema.getNameVariant(TYPE_NAME_NAMING_CONVENTION);

		final GraphQLFieldDefinition parentEntitiesField = ParentsOfEntityDescriptor.PARENT_ENTITIES
			.to(fieldBuilderTransformer)
			.type(nonNull(list(nonNull(typeRef(referencedEntityObjectName)))))
			.build();

		return new BuiltFieldDescriptor(
			parentEntitiesField,
			new SingleParentsOfReferenceDataFetcher()
		);
	}

	@Nonnull
	private GraphQLObjectType buildHierarchyStatisticsObject(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx,
	                                                         @Nonnull List<ReferenceSchemaContract> referenceSchemas) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final String objectName = HierarchyStatisticsDescriptor.THIS.name(entitySchema);
		final GraphQLObjectType.Builder hierarchyStatisticsObjectBuilder = HierarchyStatisticsDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName);

		if (entitySchema.isWithHierarchy()) {
			context.registerFieldToObject(
				objectName,
				hierarchyStatisticsObjectBuilder,
				buildSelfLevelInfoField(entitySchemaBuildingCtx)
			);
		}
		referenceSchemas.forEach(referenceSchema ->
			context.registerFieldToObject(
				objectName,
				hierarchyStatisticsObjectBuilder,
				buildLevelInfoField(entitySchemaBuildingCtx, referenceSchema)
			)
		);

		return hierarchyStatisticsObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildSelfLevelInfoField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final GraphQLObjectType selfLevelInfoObject = buildSelfLevelInfoObject(entitySchemaBuildingCtx);
		final GraphQLFieldDefinition selfLevelInfoField = HierarchyStatisticsDescriptor.SELF
			.to(fieldBuilderTransformer)
			.type(list(nonNull(selfLevelInfoObject)))
			.build();
		return new BuiltFieldDescriptor(selfLevelInfoField, null);
	}

	@Nonnull
	private GraphQLObjectType buildSelfLevelInfoObject(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final String objectName = HierarchyStatisticsLevelInfoDescriptor.THIS.name(entitySchema, entitySchema);

		final GraphQLObjectType.Builder selfLevelInfoObjectBuilder = HierarchyStatisticsLevelInfoDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName)
			.field(HierarchyStatisticsLevelInfoDescriptor.CHILDREN_STATISTICS
				.to(fieldBuilderTransformer)
				.type(nonNull(list(nonNull(typeRef(objectName))))));

		context.registerFieldToObject(
			objectName,
			selfLevelInfoObjectBuilder,
			buildSelfLevelInfoEntityField(entitySchemaBuildingCtx)
		);

		return selfLevelInfoObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildSelfLevelInfoEntityField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();
		final String referencedEntityObjectName = entitySchema.getNameVariant(TYPE_NAME_NAMING_CONVENTION);

		final GraphQLFieldDefinition entityField = HierarchyStatisticsLevelInfoDescriptor.ENTITY
			.to(fieldBuilderTransformer)
			.type(nonNull(typeRef(referencedEntityObjectName)))
			.build();

		return new BuiltFieldDescriptor(
			entityField,
			null
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildLevelInfoField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx,
	                                                 @Nonnull ReferenceSchemaContract referenceSchema) {
		final GraphQLObjectType levelInfoObject = buildLevelInfoObject(entitySchemaBuildingCtx, referenceSchema);
		final GraphQLFieldDefinition levelInfoField = newFieldDefinition()
			.name(referenceSchema.getNameVariant(FIELD_NAME_NAMING_CONVENTION))
			.type(list(nonNull(levelInfoObject)))
			.build();
		return new BuiltFieldDescriptor(levelInfoField, null);
	}

	@Nonnull
	private GraphQLObjectType buildLevelInfoObject(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx,
	                                               @Nonnull ReferenceSchemaContract referenceSchema) {
		final String objectName = HierarchyStatisticsLevelInfoDescriptor.THIS.name(entitySchemaBuildingCtx.getSchema(), referenceSchema);

		final GraphQLObjectType.Builder levelInfoObjectBuilder = HierarchyStatisticsLevelInfoDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(objectName)
			.field(HierarchyStatisticsLevelInfoDescriptor.CHILDREN_STATISTICS
				.to(fieldBuilderTransformer)
				.type(nonNull(list(nonNull(typeRef(objectName))))));

		context.registerFieldToObject(
			objectName,
			levelInfoObjectBuilder,
			buildLevelInfoEntityField(referenceSchema)
		);

		return levelInfoObjectBuilder.build();
	}

	@Nonnull
	private BuiltFieldDescriptor buildLevelInfoEntityField(@Nonnull ReferenceSchemaContract referenceSchema) {
		final EntitySchemaContract referencedEntitySchema = context
			.getSchema()
			.getEntitySchemaOrThrowException(referenceSchema.getReferencedEntityType());
		final String referencedEntityObjectName = referencedEntitySchema.getNameVariant(TYPE_NAME_NAMING_CONVENTION);

		final GraphQLFieldDefinition entityField = HierarchyStatisticsLevelInfoDescriptor.ENTITY
			.to(fieldBuilderTransformer)
			.type(nonNull(typeRef(referencedEntityObjectName)))
			.build();

		return new BuiltFieldDescriptor(
			entityField,
			null
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildQueryTelemetryField() {
		return new BuiltFieldDescriptor(
			ExtraResultsDescriptor.QUERY_TELEMETRY
				.to(fieldBuilderTransformer)
				.type(nonNull(OBJECT)) // workaround because GQL doesn't support infinite recursive structures
				.argument(QueryTelemetryFieldHeaderDescriptor.FORMATTED.to(argumentBuilderTransformer))
				.build(),
			new QueryTelemetryDataFetcher(QUERY_TELEMETRY_OBJECT_MAPPER)
		);
	}

	@Nonnull
	private static GraphQLOutputType buildReferencedEntityObject(@Nullable EntitySchemaContract referencedEntitySchema) {
		if (referencedEntitySchema != null) {
			return typeRef(EntityDescriptor.THIS.name(referencedEntitySchema));
		} else {
			return typeRef(EntityDescriptor.THIS_ENTITY_REFERENCE.name());
		}
	}


	@Nonnull
	private BuiltFieldDescriptor buildUpsertEntityField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final GraphQLFieldDefinition.Builder upsertEntityFieldBuilder = CatalogDataApiRootDescriptor.UPSERT_ENTITY
			.to(new EndpointDescriptorToGraphQLFieldTransformer(propertyDataTypeBuilderTransformer, entitySchema))
			.type(nonNull(typeRef(EntityDescriptor.THIS.name(entitySchema))))
			.argument(UpsertEntityMutationHeaderDescriptor.PRIMARY_KEY
				.to(argumentBuilderTransformer)
				.type(entitySchema.isWithGeneratedPrimaryKey() ? INT : nonNull(INT)))
			.argument(UpsertEntityMutationHeaderDescriptor.ENTITY_EXISTENCE.to(argumentBuilderTransformer));

		final GraphQLInputObjectType localMutationAggregateObject = buildLocalMutationAggregateObject(entitySchemaBuildingCtx);
		if (localMutationAggregateObject != null) {
			upsertEntityFieldBuilder.argument(UpsertEntityMutationHeaderDescriptor.MUTATIONS
				.to(argumentBuilderTransformer)
				.type(list(nonNull(localMutationAggregateObject))))
				.build();
		}

		return new BuiltFieldDescriptor(
			upsertEntityFieldBuilder.build(),
			new UpsertEntityMutatingDataFetcher(CDO_OBJECT_MAPPER, context.getSchema(), entitySchema)
		);
	}

	@Nonnull
	private BuiltFieldDescriptor buildDeleteEntitiesField(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final GraphQLFieldDefinition deleteEntityByQueryField = CatalogDataApiRootDescriptor.DELETE_ENTITY
			.to(new EndpointDescriptorToGraphQLFieldTransformer(propertyDataTypeBuilderTransformer, entitySchema))
			.type(nonNull(list(nonNull(typeRef(EntityDescriptor.THIS.name(entitySchema))))))
			.argument(DeleteEntitiesMutationHeaderDescriptor.FILTER_BY
				.to(argumentBuilderTransformer)
				.type(entitySchemaBuildingCtx.getFilterByInputObject()))
			.argument(DeleteEntitiesMutationHeaderDescriptor.ORDER_BY
				.to(argumentBuilderTransformer)
				.type(entitySchemaBuildingCtx.getOrderByInputObject()))
			.argument(DeleteEntitiesMutationHeaderDescriptor.OFFSET
				.to(argumentBuilderTransformer))
			.argument(DeleteEntitiesMutationHeaderDescriptor.LIMIT
				.to(argumentBuilderTransformer))
			.build();

		return new BuiltFieldDescriptor(
			deleteEntityByQueryField,
			new DeleteEntitiesMutatingDataFetcher(context.getSchema(), entitySchema)
		);
	}


	@Nonnull
	private GraphQLObjectType buildPriceObject() {
		context.registerDataFetcher(
			PriceDescriptor.THIS,
			PriceDescriptor.PRICE_WITH_TAX,
			new PriceBigDecimalDataFetcher(PriceDescriptor.PRICE_WITH_TAX.name())
		);
		context.registerDataFetcher(
			PriceDescriptor.THIS,
			PriceDescriptor.PRICE_WITHOUT_TAX,
			new PriceBigDecimalDataFetcher(PriceDescriptor.PRICE_WITHOUT_TAX.name())
		);
		context.registerDataFetcher(
			PriceDescriptor.THIS,
			PriceDescriptor.TAX_RATE,
			new PriceBigDecimalDataFetcher(PriceDescriptor.TAX_RATE.name())
		);

		return PriceDescriptor.THIS
			.to(objectBuilderTransformer)
			.field(PriceDescriptor.PRICE_WITHOUT_TAX.to(fieldBuilderTransformer.with(PRICE_FIELD_DECORATOR)))
			.field(PriceDescriptor.PRICE_WITH_TAX.to(fieldBuilderTransformer.with(PRICE_FIELD_DECORATOR)))
			.field(PriceDescriptor.TAX_RATE.to(fieldBuilderTransformer.with(PRICE_FIELD_DECORATOR)))
			.build();
	}

	@Nonnull
	private GraphQLObjectType buildHistogramObject() {
		return HistogramDescriptor.THIS
			.to(objectBuilderTransformer)
			.field(HistogramDescriptor.BUCKETS
				.to(fieldBuilderTransformer)
				.argument(BucketsFieldHeaderDescriptor.REQUESTED_COUNT.to(argumentBuilderTransformer)))
			.build();
	}

	// todo lho: remove after https://gitlab.fg.cz/hv/evita/-/issues/120 is implemented
	@Nonnull
	private GraphQLObjectType buildAttributeNamedHistogramObject() {
		return HistogramDescriptor.THIS
			.to(objectBuilderTransformer)
			.name("AttributeNamedHistogram")
			.field(f -> f.name("attributeName").type(nonNull(STRING)))
			.field(HistogramDescriptor.BUCKETS
				.to(fieldBuilderTransformer)
				.argument(BucketsFieldHeaderDescriptor.REQUESTED_COUNT.to(argumentBuilderTransformer)))
			.build();
	}

	@Nonnull
	private GraphQLObjectType buildFacetRequestImpactObject() {
		// register custom data fetcher because of the request impact being java record
		context.registerDataFetcher(
			FacetRequestImpactDescriptor.THIS,
			FacetRequestImpactDescriptor.DIFFERENCE,
			PropertyDataFetcher.fetching(RequestImpact::difference)
		);
		context.registerDataFetcher(
			FacetRequestImpactDescriptor.THIS,
			FacetRequestImpactDescriptor.MATCH_COUNT,
			PropertyDataFetcher.fetching(RequestImpact::matchCount)
		);
		context.registerDataFetcher(
			FacetRequestImpactDescriptor.THIS,
			FacetRequestImpactDescriptor.HAS_SENSE,
			PropertyDataFetcher.fetching(RequestImpact::hasSense)
		);

		return FacetRequestImpactDescriptor.THIS
			.to(objectBuilderTransformer)
			.build();
	}

	@Nullable
	private GraphQLInputObjectType buildLocalMutationAggregateObject(@Nonnull EntitySchemaGraphQLSchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final GraphQLInputObjectType.Builder localMutationAggregateObjectBuilder = newInputObject()
			.name(LocalMutationAggregateDescriptor.THIS.name(entitySchema))
			.description(LocalMutationAggregateDescriptor.THIS.description(entitySchema.getName()));

		boolean hasAnyMutations = false;

		if (!entitySchema.getAssociatedData().isEmpty() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_ASSOCIATED_DATA)) {
			hasAnyMutations = true;
			localMutationAggregateObjectBuilder
				.field(LocalMutationAggregateDescriptor.REMOVE_ASSOCIATED_DATA_MUTATION.to(inputFieldBuilderTransformer))
				.field(LocalMutationAggregateDescriptor.UPSERT_ASSOCIATED_DATA_MUTATION.to(inputFieldBuilderTransformer));
		}

		if (!entitySchema.getAttributes().isEmpty() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_ATTRIBUTES)) {
			hasAnyMutations = true;
			localMutationAggregateObjectBuilder
				.field(LocalMutationAggregateDescriptor.APPLY_DELTA_ATTRIBUTE_MUTATION.to(inputFieldBuilderTransformer))
				.field(LocalMutationAggregateDescriptor.REMOVE_ATTRIBUTE_MUTATION.to(inputFieldBuilderTransformer))
				.field(LocalMutationAggregateDescriptor.UPSERT_ATTRIBUTE_MUTATION.to(inputFieldBuilderTransformer));
		}

		if (entitySchema.isWithHierarchy() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_HIERARCHY)) {
			hasAnyMutations = true;
			localMutationAggregateObjectBuilder
				.field(LocalMutationAggregateDescriptor.REMOVE_HIERARCHICAL_PLACEMENT_MUTATION.to(inputFieldBuilderTransformer)
					.type(BOOLEAN))
				.field(LocalMutationAggregateDescriptor.SET_HIERARCHICAL_PLACEMENT_MUTATION.to(inputFieldBuilderTransformer));
		}

		if (entitySchema.isWithPrice() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_PRICES)) {
			hasAnyMutations = true;
			localMutationAggregateObjectBuilder
				.field(LocalMutationAggregateDescriptor.SET_PRICE_INNER_RECORD_HANDLING_MUTATION.to(inputFieldBuilderTransformer))
				.field(LocalMutationAggregateDescriptor.REMOVE_PRICE_MUTATION.to(inputFieldBuilderTransformer))
				.field(LocalMutationAggregateDescriptor.UPSERT_PRICE_MUTATION.to(inputFieldBuilderTransformer));
		}

		if (!entitySchema.getReferences().isEmpty() || entitySchema.getEvolutionMode().contains(EvolutionMode.ADDING_REFERENCES)) {
			hasAnyMutations = true;
			localMutationAggregateObjectBuilder
				.field(LocalMutationAggregateDescriptor.INSERT_REFERENCE_MUTATION.to(inputFieldBuilderTransformer))
				.field(LocalMutationAggregateDescriptor.REMOVE_REFERENCE_MUTATION.to(inputFieldBuilderTransformer))
				.field(LocalMutationAggregateDescriptor.SET_REFERENCE_GROUP_MUTATION.to(inputFieldBuilderTransformer))
				.field(LocalMutationAggregateDescriptor.REMOVE_REFERENCE_GROUP_MUTATION.to(inputFieldBuilderTransformer))
				.field(LocalMutationAggregateDescriptor.REFERENCE_ATTRIBUTE_MUTATION.to(inputFieldBuilderTransformer));
		}

		if (!hasAnyMutations) {
			return null;
		}
		return localMutationAggregateObjectBuilder.build();
	}
}
