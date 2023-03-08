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

package io.evitadb.externalApi.rest.api.catalog.builder;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.HierarchicalPlacementDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.PriceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.FacetSummaryDescriptor.FacetRequestImpactDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.HistogramDescriptor.BucketDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.extraResult.QueryTelemetryDescriptor;
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
import io.evitadb.externalApi.api.catalog.schemaApi.model.AssociatedDataSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.AttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.GlobalAttributeSchemaDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.SchemaNameVariantsDescriptor;
import io.evitadb.externalApi.rest.api.catalog.builder.constraint.FilterBySchemaBuilder;
import io.evitadb.externalApi.rest.api.catalog.builder.constraint.OpenApiConstraintSchemaBuildingContext;
import io.evitadb.externalApi.rest.api.catalog.builder.constraint.OrderBySchemaBuilder;
import io.evitadb.externalApi.rest.api.catalog.builder.constraint.RequireSchemaBuilder;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.ObjectDescriptorToOpenApiObjectTransformer;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.PropertyDataTypeDescriptorToOpenApiTypeTransformer;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.PropertyDescriptorToOpenApiOperationPathParameterTransformer;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.PropertyDescriptorToOpenApiOperationQueryParameterTransformer;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.PropertyDescriptorToOpenApiPropertyTransformer;
import io.evitadb.externalApi.rest.api.catalog.model.EntityUnion;
import io.evitadb.externalApi.rest.api.catalog.model.ErrorDescriptor;
import io.evitadb.externalApi.rest.api.catalog.model.QueryRequestBodyDescriptor;
import io.evitadb.externalApi.rest.api.dto.DataChunkType;
import io.evitadb.externalApi.rest.api.dto.OpenApiEnum;
import io.evitadb.externalApi.rest.api.dto.OpenApiObject;
import io.evitadb.externalApi.rest.api.dto.OpenApiObjectUnionType;
import io.evitadb.externalApi.rest.api.dto.OpenApiSimpleType;
import io.evitadb.externalApi.rest.api.dto.OpenApiTypeReference;
import io.evitadb.externalApi.rest.api.dto.Rest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.ENTITY_CURRENCY_ENUM;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.ENTITY_LOCALE_ENUM;
import static io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor.ASSOCIATED_DATA_SCALAR_ENUM;
import static io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor.SCALAR_ENUM;
import static io.evitadb.externalApi.rest.api.catalog.builder.NamesConstructor.constructEntityListRequestBodyObjectName;
import static io.evitadb.externalApi.rest.api.catalog.builder.NamesConstructor.constructEntityQueryRequestBodyObjectName;
import static io.evitadb.externalApi.rest.api.catalog.builder.constraint.RequireSchemaBuilder.ALLOWED_CONSTRAINTS_FOR_LIST;
import static io.evitadb.externalApi.rest.api.dto.OpenApiEnum.enumFrom;
import static io.evitadb.externalApi.rest.api.dto.OpenApiEnum.newEnum;
import static io.evitadb.externalApi.rest.api.dto.OpenApiScalar.FORMAT_CURRENCY;
import static io.evitadb.externalApi.rest.api.dto.OpenApiScalar.FORMAT_LOCALE;

/**
 * Creates OpenAPI specification for Evita's catalog.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
@RequiredArgsConstructor
public class CatalogRestBuilder {

	@Nonnull private final CatalogRestBuildingContext buildingContext;
	@Nonnull private final OpenApiConstraintSchemaBuildingContext constraintBuildingContext;

	@Nonnull private final PropertyDataTypeDescriptorToOpenApiTypeTransformer propertyDataTypeBuilderTransformer;
	@Nonnull private final PropertyDescriptorToOpenApiPropertyTransformer propertyBuilderTransformer;
	@Nonnull private final ObjectDescriptorToOpenApiObjectTransformer objectBuilderTransformer;
	@Nonnull private final PropertyDescriptorToOpenApiOperationPathParameterTransformer operationPathParameterBuilderTransformer;
	@Nonnull private final PropertyDescriptorToOpenApiOperationQueryParameterTransformer operationQueryParameterBuilderTransformer;

	@Nonnull private final EndpointBuilder endpointBuilder;

	/**
	 * Creates new builder.
	 */
	public CatalogRestBuilder(@Nonnull Evita evita, @Nonnull CatalogContract catalog) {
		this.buildingContext = new CatalogRestBuildingContext(evita, catalog);
		this.constraintBuildingContext = new OpenApiConstraintSchemaBuildingContext(buildingContext);

		this.propertyDataTypeBuilderTransformer = new PropertyDataTypeDescriptorToOpenApiTypeTransformer(this.buildingContext);
		this.propertyBuilderTransformer = new PropertyDescriptorToOpenApiPropertyTransformer(propertyDataTypeBuilderTransformer);
		this.objectBuilderTransformer = new ObjectDescriptorToOpenApiObjectTransformer(propertyBuilderTransformer);
		this.operationPathParameterBuilderTransformer = new PropertyDescriptorToOpenApiOperationPathParameterTransformer(propertyDataTypeBuilderTransformer);
		this.operationQueryParameterBuilderTransformer = new PropertyDescriptorToOpenApiOperationQueryParameterTransformer(propertyDataTypeBuilderTransformer);

		this.endpointBuilder = new EndpointBuilder(
			propertyBuilderTransformer,
			objectBuilderTransformer,
			operationPathParameterBuilderTransformer,
			operationQueryParameterBuilderTransformer
		);
	}

	/**
	 * Builds OpenAPI specification for provided catalog.
	 *
	 * @return OpenAPI specification
	 */
	public Rest build() {
		buildCommonTypes();
		buildEndpoints();

		// register gathered custom constraint schema types
		constraintBuildingContext.getBuiltTypes().forEach(buildingContext::registerType);

		return buildingContext.buildRest();
	}

	private void buildCommonTypes() {
		final OpenApiEnum scalarEnum = buildScalarEnum();
		buildingContext.registerType(scalarEnum);
		buildingContext.registerType(buildAssociatedDataScalarEnum(scalarEnum));
		buildingContext.registerType(ErrorDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(enumFrom(DataChunkType.class));

		buildingContext.registerType(HierarchicalPlacementDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(PriceDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(BucketDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(HistogramDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(QueryTelemetryDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(FacetRequestImpactDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(SchemaNameVariantsDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(AttributeSchemaDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(GlobalAttributeSchemaDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(AssociatedDataSchemaDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(EntityDescriptor.THIS_ENTITY_REFERENCE.to(objectBuilderTransformer).build());

		// upsert mutations
		buildingContext.registerType(RemoveAssociatedDataMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(UpsertAssociatedDataMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ApplyDeltaAttributeMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(RemoveAttributeMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(UpsertAttributeMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(SetHierarchicalPlacementMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(SetPriceInnerRecordHandlingMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(RemovePriceMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(UpsertPriceMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(InsertReferenceMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(RemoveReferenceMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(SetReferenceGroupMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(RemoveReferenceGroupMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ReferenceAttributeMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		buildingContext.registerType(ReferenceAttributeMutationAggregateDescriptor.THIS.to(objectBuilderTransformer).build());
	}

	private void buildEndpoints() {
		buildingContext.registerEndpoint(endpointBuilder.buildOpenApiSpecificationEndpoint(buildingContext));

		buildingContext.registerEndpoint(endpointBuilder.buildCollectionsEndpoint(buildingContext));

		buildingContext.getEntitySchemas().forEach(entitySchema -> {
			final OpenApiEntitySchemaBuildingContext collectionContext = setupForCollection(entitySchema);

			buildingContext.registerEndpoint(endpointBuilder.buildEntityGetEndpoint(collectionContext, false, false));
			buildingContext.registerEndpoint(endpointBuilder.buildEntityGetEndpoint(collectionContext, false, true));

			buildingContext.registerEndpoint(endpointBuilder.buildEntityListEndpoint(collectionContext, false));
			buildingContext.registerEndpoint(endpointBuilder.buildEntityQueryEndpoint(collectionContext, false));

			if(collectionContext.isLocalizedEntity()) {
				buildingContext.registerEndpoint(endpointBuilder.buildEntityGetEndpoint(collectionContext, true, false));
				buildingContext.registerEndpoint(endpointBuilder.buildEntityGetEndpoint(collectionContext, true, true));

				buildingContext.registerEndpoint(endpointBuilder.buildEntityListEndpoint(collectionContext, true));
				buildingContext.registerEndpoint(endpointBuilder.buildEntityQueryEndpoint(collectionContext, true));
			}

			// todo lho split apis to data and schema first
//			pathItemBuilder.buildAndAddGetEntitySchemaPathItem(collectionContext);
		});

		final OpenApiObject.Builder entityUnionBuilder = EntityUnion.THIS
			.to(objectBuilderTransformer)
			.unionType(OpenApiObjectUnionType.ONE_OF)
			.unionDiscriminator(EntityDescriptor.TYPE.name());
		buildingContext.getEntityObjects().forEach(entityUnionBuilder::unionObject);
		buildingContext.registerType(entityUnionBuilder.build());

		final OpenApiObject.Builder localizedEntityUnionBuilder = EntityUnion.THIS_LOCALIZED
			.to(objectBuilderTransformer)
			.unionType(OpenApiObjectUnionType.ONE_OF)
			.unionDiscriminator(EntityDescriptor.TYPE.name());
		buildingContext.getLocalizedEntityObjects().forEach(localizedEntityUnionBuilder::unionObject);
		buildingContext.registerType(localizedEntityUnionBuilder.build());

		final List<GlobalAttributeSchemaContract> globallyUniqueAttributes = getGloballyUniqueAttributes(buildingContext.getSchema());
		if(!globallyUniqueAttributes.isEmpty()) {
			endpointBuilder.buildUnknownEntityGetEndpoint(buildingContext, buildingContext.getLocalizedEntityObjects(), globallyUniqueAttributes, false)
				.ifPresent(buildingContext::registerEndpoint);
			endpointBuilder.buildUnknownEntityGetEndpoint(buildingContext, buildingContext.getEntityObjects(), globallyUniqueAttributes, true)
				.ifPresent(buildingContext::registerEndpoint);
			endpointBuilder.buildUnknownEntityListEndpoint(buildingContext, buildingContext.getLocalizedEntityObjects(), globallyUniqueAttributes, false)
				.ifPresent(buildingContext::registerEndpoint);
			endpointBuilder.buildUnknownEntityListEndpoint(buildingContext, buildingContext.getEntityObjects(), globallyUniqueAttributes, true)
				.ifPresent(buildingContext::registerEndpoint);
		}
	}

	/**
	 * Prepare common data for specific collection schema building.
	 */
	@Nonnull
	protected OpenApiEntitySchemaBuildingContext setupForCollection(@Nonnull EntitySchemaContract entitySchema) {
		if (!entitySchema.getLocales().isEmpty()) {
			final OpenApiEnum.Builder localeEnumBuilder = newEnum()
				.name(ENTITY_LOCALE_ENUM.name(entitySchema))
				.description(ENTITY_LOCALE_ENUM.description())
				.format(FORMAT_LOCALE);
			entitySchema.getLocales().forEach(l -> localeEnumBuilder.item(l.toLanguageTag()));

			buildingContext.registerType(localeEnumBuilder.build());
		}

		if (!entitySchema.getCurrencies().isEmpty()) {
			final OpenApiEnum.Builder currencyEnumBuilder = newEnum()
				.name(ENTITY_CURRENCY_ENUM.name(entitySchema))
				.description(ENTITY_CURRENCY_ENUM.description())
				.format(FORMAT_CURRENCY);
			entitySchema.getCurrencies().forEach(c -> currencyEnumBuilder.item(c.toString()));

			buildingContext.registerType(currencyEnumBuilder.build());
		}

		final OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx = new OpenApiEntitySchemaBuildingContext(
			buildingContext,
			constraintBuildingContext,
			entitySchema
		);

		// build filter schema
		final OpenApiSimpleType filterBySchema = new FilterBySchemaBuilder(
			constraintBuildingContext,
			entitySchemaBuildingCtx.getSchema().getName(),
			false
		).build();
		entitySchemaBuildingCtx.setFilterByInputObject(filterBySchema);

		final OpenApiSimpleType filterByLocalizedSchema = new FilterBySchemaBuilder(
			constraintBuildingContext,
			entitySchemaBuildingCtx.getSchema().getName(),
			true
		).build();
		entitySchemaBuildingCtx.setFilterByLocalizedInputObject(filterByLocalizedSchema);

		// build order schema
		final OpenApiSimpleType orderBySchema = new OrderBySchemaBuilder(
			constraintBuildingContext,
			entitySchemaBuildingCtx.getSchema().getName()
		).build();
		entitySchemaBuildingCtx.setOrderByInputObject(orderBySchema);

		// build require schema
		entitySchemaBuildingCtx.setRequiredForListInputObject(buildRequireSchemaForList(entitySchemaBuildingCtx));
		entitySchemaBuildingCtx.setRequiredForQueryInputObject(buildRequireSchemaForQuery(entitySchemaBuildingCtx));
		entitySchemaBuildingCtx.setRequiredForDeleteInputObject(buildRequireSchemaForDelete(entitySchemaBuildingCtx));


		final EntityObjectBuilder entityObjectBuilder = new EntityObjectBuilder(
			entitySchemaBuildingCtx,
			propertyBuilderTransformer,
			objectBuilderTransformer
		);
		buildingContext.registerLocalizedEntityObject(entityObjectBuilder.buildEntityObject(true));

		buildListRequestBodyObject(entitySchemaBuildingCtx, false);
		buildQueryRequestBodyObject(entitySchemaBuildingCtx, false);
		buildDeleteRequestBodyObject(entitySchemaBuildingCtx);

		if(entitySchemaBuildingCtx.isLocalizedEntity()) {
			buildingContext.registerEntityObject(entityObjectBuilder.buildEntityObject(false));

			buildListRequestBodyObject(entitySchemaBuildingCtx, true);
			buildQueryRequestBodyObject(entitySchemaBuildingCtx, true);
		} else {
			/* When entity has no localized data than it makes no sense to create endpoints for this entity with Locale
			* in URL. But this entity may be referenced by other entities when localized URL is used. For such cases
			* is also appropriate entity schema created.*/
			entitySchemaBuildingCtx.getCatalogCtx().registerType(entityObjectBuilder.buildEntityObject(false));
		}

//		final EntitySchemaObjectBuilder entitySchemaObjectBuilder = new EntitySchemaObjectBuilder(
//			entitySchemaBuildingCtx,
//			propertyBuilderTransformer,
//			objectBuilderTransformer
//		);
//		entitySchemaObjectBuilder.buildEntitySchemaObject();

		new DataMutationBuilder(
			entitySchemaBuildingCtx,
			propertyBuilderTransformer,
			objectBuilderTransformer,
			endpointBuilder
		).buildAndAddEntitiesAndPathItems();

		return entitySchemaBuildingCtx;
	}

	@Nonnull
	private OpenApiSimpleType buildRequireSchemaForList(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx) {
		return new RequireSchemaBuilder(
			constraintBuildingContext,
			entitySchemaBuildingCtx.getSchema().getName(),
			ALLOWED_CONSTRAINTS_FOR_LIST
		).build();
	}

	@Nonnull
	private OpenApiSimpleType buildRequireSchemaForQuery(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx) {
		return new RequireSchemaBuilder(
			constraintBuildingContext,
			entitySchemaBuildingCtx.getSchema().getName()
		).build();
	}

	@Nonnull
	private OpenApiSimpleType buildRequireSchemaForDelete(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx) {
		return new RequireSchemaBuilder(
			entitySchemaBuildingCtx.getConstraintSchemaBuildingCtx(),
			entitySchemaBuildingCtx.getSchema().getName(),
			RequireSchemaBuilder.ALLOWED_CONSTRAINTS_FOR_DELETE
		).build();
	}

	@Nonnull
	public OpenApiTypeReference buildListRequestBodyObject(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingContext,
	                                                       boolean localized) {
		final OpenApiObject.Builder objectBuilder = QueryRequestBodyDescriptor.THIS_LIST
			.to(objectBuilderTransformer)
			.name(constructEntityListRequestBodyObjectName(entitySchemaBuildingContext.getSchema(), !localized))
			.property(QueryRequestBodyDescriptor.FILTER_BY.to(propertyBuilderTransformer).type(!localized ? entitySchemaBuildingContext.getFilterByLocalizedInputObject() : entitySchemaBuildingContext.getFilterByInputObject()))
			.property(QueryRequestBodyDescriptor.ORDER_BY.to(propertyBuilderTransformer).type(entitySchemaBuildingContext.getOrderByInputObject()))
			.property(QueryRequestBodyDescriptor.REQUIRE.to(propertyBuilderTransformer).type(entitySchemaBuildingContext.getRequiredForListInputObject()));

		return entitySchemaBuildingContext.getCatalogCtx().registerType(objectBuilder.build());
	}

	@Nonnull
	public OpenApiTypeReference buildQueryRequestBodyObject(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingContext,
	                                                        boolean localized) {
		final OpenApiObject.Builder objectBuilder = QueryRequestBodyDescriptor.THIS_QUERY
			.to(objectBuilderTransformer)
			.name(constructEntityQueryRequestBodyObjectName(entitySchemaBuildingContext.getSchema(), !localized))
			.property(QueryRequestBodyDescriptor.FILTER_BY.to(propertyBuilderTransformer).type(!localized ? entitySchemaBuildingContext.getFilterByLocalizedInputObject() : entitySchemaBuildingContext.getFilterByInputObject()))
			.property(QueryRequestBodyDescriptor.ORDER_BY.to(propertyBuilderTransformer).type(entitySchemaBuildingContext.getOrderByInputObject()))
			.property(QueryRequestBodyDescriptor.REQUIRE.to(propertyBuilderTransformer).type(entitySchemaBuildingContext.getRequiredForQueryInputObject()));

		return entitySchemaBuildingContext.getCatalogCtx().registerType(objectBuilder.build());
	}

	@Nonnull
	public OpenApiTypeReference buildDeleteRequestBodyObject(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingContext) {
		final OpenApiObject.Builder objectBuilder = QueryRequestBodyDescriptor.THIS_DELETE
			.to(objectBuilderTransformer)
			.name(QueryRequestBodyDescriptor.THIS_DELETE.name(entitySchemaBuildingContext.getSchema()))
			.property(QueryRequestBodyDescriptor.FILTER_BY.to(propertyBuilderTransformer).type(entitySchemaBuildingContext.getFilterByInputObject()))
			.property(QueryRequestBodyDescriptor.ORDER_BY.to(propertyBuilderTransformer).type(entitySchemaBuildingContext.getOrderByInputObject()))
			.property(QueryRequestBodyDescriptor.REQUIRE.to(propertyBuilderTransformer).type(entitySchemaBuildingContext.getRequiredForDeleteInputObject()));

		return entitySchemaBuildingContext.getCatalogCtx().registerType(objectBuilder.build());
	}

	@Nonnull
	public static List<GlobalAttributeSchemaContract> getGloballyUniqueAttributes(@Nonnull CatalogSchemaContract catalogSchema) {
		return catalogSchema
			.getAttributes()
			.values()
			.stream()
			.filter(GlobalAttributeSchemaContract::isUniqueGlobally)
			.toList();
	}

	@Nonnull
	protected static OpenApiEnum buildScalarEnum() {
		final OpenApiEnum.Builder scalarEnumBuilder = newEnum()
			.name(SCALAR_ENUM.name())
			.description(SCALAR_ENUM.description());

		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(String.class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(String[].class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(Byte.class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(Byte[].class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(Short.class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(Short[].class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(Integer.class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(Integer[].class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(Long.class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(Long[].class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(Boolean.class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(Boolean[].class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(Character.class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(Character[].class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(BigDecimal.class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(BigDecimal[].class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(OffsetDateTime.class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(OffsetDateTime[].class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(LocalDateTime.class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(LocalDateTime[].class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(LocalDate.class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(LocalDate[].class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(LocalTime.class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(LocalTime[].class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(DateTimeRange.class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(DateTimeRange[].class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(BigDecimalNumberRange.class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(BigDecimalNumberRange[].class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(ByteNumberRange.class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(ByteNumberRange[].class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(ShortNumberRange.class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(ShortNumberRange[].class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(IntegerNumberRange.class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(IntegerNumberRange[].class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(LongNumberRange.class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(LongNumberRange[].class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(Locale.class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(Locale[].class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(Currency.class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(Currency[].class));
		scalarEnumBuilder.item(convertJavaTypeNameToScalarName(ComplexDataObject.class));

		return scalarEnumBuilder.build();
	}

	@Nonnull
	protected static OpenApiEnum buildAssociatedDataScalarEnum(@Nonnull OpenApiEnum scalarEnum) {
		return newEnum(scalarEnum)
			.name(ASSOCIATED_DATA_SCALAR_ENUM.name())
			.description(ASSOCIATED_DATA_SCALAR_ENUM.description())
			.item(convertJavaTypeNameToScalarName(ComplexDataObject.class))
			.build();
	}

	// todo lho move somewhere, i think it is used somewhere
	@Nonnull
	private static String convertJavaTypeNameToScalarName(@Nonnull Class<? extends Serializable> javaType) {
		if (javaType.isArray()) {
			return javaType.componentType().getSimpleName() + "Array";
		} else {
			return javaType.getSimpleName();
		}
	}
}
