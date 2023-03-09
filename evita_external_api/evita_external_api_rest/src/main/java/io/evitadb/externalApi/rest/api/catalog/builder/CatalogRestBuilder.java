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
import io.evitadb.externalApi.rest.api.catalog.model.CollectionDescriptor;
import io.evitadb.externalApi.rest.api.catalog.model.EntityUnion;
import io.evitadb.externalApi.rest.api.catalog.model.ErrorDescriptor;
import io.evitadb.externalApi.rest.api.catalog.model.FetchRequestDescriptor;
import io.evitadb.externalApi.rest.api.dto.DataChunkType;
import io.evitadb.externalApi.rest.api.dto.Rest;
import io.evitadb.externalApi.rest.api.openApi.OpenApiEnum;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObject;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObjectUnionType;
import io.evitadb.externalApi.rest.api.openApi.OpenApiSimpleType;
import io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference;
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
import static io.evitadb.externalApi.rest.api.openApi.OpenApiEnum.enumFrom;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiEnum.newEnum;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiScalar.FORMAT_CURRENCY;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiScalar.FORMAT_LOCALE;

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

		this.endpointBuilder = new EndpointBuilder(operationPathParameterBuilderTransformer, operationQueryParameterBuilderTransformer);
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

		buildingContext.registerType(CollectionDescriptor.THIS.to(objectBuilderTransformer).build());
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
			final CollectionRestBuildingContext collectionBuildingContext = setupForCollection(entitySchema);

			buildingContext.registerEndpoint(endpointBuilder.buildGetEntityEndpoint(collectionBuildingContext, false, false));
			buildingContext.registerEndpoint(endpointBuilder.buildGetEntityEndpoint(collectionBuildingContext, false, true));

			buildingContext.registerEndpoint(endpointBuilder.buildListEntityEndpoint(collectionBuildingContext, false));
			buildingContext.registerEndpoint(endpointBuilder.buildQueryEntityEndpoint(collectionBuildingContext, false));

			if(collectionBuildingContext.isLocalizedEntity()) {
				buildingContext.registerEndpoint(endpointBuilder.buildGetEntityEndpoint(collectionBuildingContext, true, false));
				buildingContext.registerEndpoint(endpointBuilder.buildGetEntityEndpoint(collectionBuildingContext, true, true));

				buildingContext.registerEndpoint(endpointBuilder.buildListEntityEndpoint(collectionBuildingContext, true));
				buildingContext.registerEndpoint(endpointBuilder.buildQueryEntityEndpoint(collectionBuildingContext, true));
			}

			buildingContext.registerEndpoint(endpointBuilder.buildUpsertEntityEndpoint(collectionBuildingContext, true));
			if (collectionBuildingContext.getSchema().isWithGeneratedPrimaryKey()) {
				buildingContext.registerEndpoint(endpointBuilder.buildUpsertEntityEndpoint(collectionBuildingContext, false));
			}

			buildingContext.registerEndpoint(endpointBuilder.buildDeleteEntityEndpoint(collectionBuildingContext));
			buildingContext.registerEndpoint(endpointBuilder.buildDeleteEntitiesByQueryEndpoint(collectionBuildingContext));
		});

		buildingContext.registerType(buildEntityUnionObject(false));
		buildingContext.registerType(buildEntityUnionObject(true));

		final List<GlobalAttributeSchemaContract> globallyUniqueAttributes = buildingContext.getSchema()
			.getAttributes()
			.values()
			.stream()
			.filter(GlobalAttributeSchemaContract::isUniqueGlobally)
			.toList();
		if(!globallyUniqueAttributes.isEmpty()) {
			endpointBuilder.buildGetUnknownEntityEndpoint(buildingContext, globallyUniqueAttributes, false)
				.ifPresent(buildingContext::registerEndpoint);
			endpointBuilder.buildListUnknownEntityEndpoint(buildingContext, globallyUniqueAttributes, false)
				.ifPresent(buildingContext::registerEndpoint);

			if (!buildingContext.getLocalizedEntityObjects().isEmpty()) {
				endpointBuilder.buildGetUnknownEntityEndpoint(buildingContext, globallyUniqueAttributes, true)
					.ifPresent(buildingContext::registerEndpoint);
				endpointBuilder.buildListUnknownEntityEndpoint(buildingContext, globallyUniqueAttributes, true)
					.ifPresent(buildingContext::registerEndpoint);
			}
		}
	}

	/**
	 * Prepare common data for specific collection schema building.
	 */
	@Nonnull
	private CollectionRestBuildingContext setupForCollection(@Nonnull EntitySchemaContract entitySchema) {
		if (!entitySchema.getLocales().isEmpty()) {
			final OpenApiEnum.Builder localeEnumBuilder = newEnum()
				.name(ENTITY_LOCALE_ENUM.name(entitySchema))
				.description(ENTITY_LOCALE_ENUM.description())
				.format(FORMAT_LOCALE);
			entitySchema.getLocales().forEach(l -> localeEnumBuilder.item(l.toLanguageTag()));

			buildingContext.registerCustomEnumIfAbsent(localeEnumBuilder.build());
		}

		if (!entitySchema.getCurrencies().isEmpty()) {
			final OpenApiEnum.Builder currencyEnumBuilder = newEnum()
				.name(ENTITY_CURRENCY_ENUM.name(entitySchema))
				.description(ENTITY_CURRENCY_ENUM.description())
				.format(FORMAT_CURRENCY);
			entitySchema.getCurrencies().forEach(c -> currencyEnumBuilder.item(c.toString()));

			buildingContext.registerCustomEnumIfAbsent(currencyEnumBuilder.build());
		}

		final CollectionRestBuildingContext collectionBuildingContext = new CollectionRestBuildingContext(
			buildingContext,
			constraintBuildingContext,
			entitySchema
		);

		// build filter schema
		collectionBuildingContext.setFilterByObject(buildFilterBySchema(collectionBuildingContext, false));
		collectionBuildingContext.setLocalizedFilterByObject(buildFilterBySchema(collectionBuildingContext, true));

		// build order schema
		collectionBuildingContext.setOrderByObject(buildOrderBySchema(collectionBuildingContext));

		// build require schema
		collectionBuildingContext.setRequiredForListObject(buildRequireSchemaForList(collectionBuildingContext));
		collectionBuildingContext.setRequiredForQueryObject(buildRequireSchemaForQuery(collectionBuildingContext));
		collectionBuildingContext.setRequiredForDeleteObject(buildRequireSchemaForDelete(collectionBuildingContext));

		final EntityObjectBuilder entityObjectBuilder = new EntityObjectBuilder(
			collectionBuildingContext,
			propertyBuilderTransformer,
			objectBuilderTransformer
		);
		buildingContext.registerLocalizedEntityObject(entityObjectBuilder.buildEntityObject(false));

		buildListRequestBodyObject(collectionBuildingContext, false);
		buildQueryRequestBodyObject(collectionBuildingContext, false);
		buildDeleteRequestBodyObject(collectionBuildingContext);

		final FullResponseObjectBuilder fullResponseObjectBuilder = new FullResponseObjectBuilder(
			collectionBuildingContext,
			propertyBuilderTransformer,
			objectBuilderTransformer
		);
		buildingContext.registerType(fullResponseObjectBuilder.buildFullResponseObject(false));

		if(collectionBuildingContext.isLocalizedEntity()) {
			buildingContext.registerEntityObject(entityObjectBuilder.buildEntityObject(true));

			buildListRequestBodyObject(collectionBuildingContext, true);
			buildQueryRequestBodyObject(collectionBuildingContext, true);

			buildingContext.registerType(fullResponseObjectBuilder.buildFullResponseObject(true));
		} else {
			/* When entity has no localized data than it makes no sense to create endpoints for this entity with Locale
			* in URL. But this entity may be referenced by other entities when localized URL is used. For such cases
			* is also appropriate entity schema created.*/
			collectionBuildingContext.getCatalogCtx().registerType(entityObjectBuilder.buildEntityObject(true));
		}

		new DataMutationBuilder(
			collectionBuildingContext,
			propertyBuilderTransformer,
			objectBuilderTransformer
		).buildEntityUpsertRequestObject();

		return collectionBuildingContext;
	}

	@Nonnull
	private OpenApiSimpleType buildFilterBySchema(@Nonnull CollectionRestBuildingContext collectionBuildingContext,
	                                              boolean localized) {
		return new FilterBySchemaBuilder(
			constraintBuildingContext,
			collectionBuildingContext.getSchema().getName(),
			localized
		).build();
	}

	@Nonnull
	private OpenApiSimpleType buildOrderBySchema(@Nonnull CollectionRestBuildingContext collectionBuildingContext) {
		return new OrderBySchemaBuilder(
			constraintBuildingContext,
			collectionBuildingContext.getSchema().getName()
		).build();
	}

	@Nonnull
	private OpenApiSimpleType buildRequireSchemaForList(@Nonnull CollectionRestBuildingContext entitySchemaBuildingCtx) {
		return new RequireSchemaBuilder(
			constraintBuildingContext,
			entitySchemaBuildingCtx.getSchema().getName(),
			ALLOWED_CONSTRAINTS_FOR_LIST
		).build();
	}

	@Nonnull
	private OpenApiSimpleType buildRequireSchemaForQuery(@Nonnull CollectionRestBuildingContext entitySchemaBuildingCtx) {
		return new RequireSchemaBuilder(
			constraintBuildingContext,
			entitySchemaBuildingCtx.getSchema().getName()
		).build();
	}

	@Nonnull
	private OpenApiSimpleType buildRequireSchemaForDelete(@Nonnull CollectionRestBuildingContext entitySchemaBuildingCtx) {
		return new RequireSchemaBuilder(
			entitySchemaBuildingCtx.getConstraintBuildingContext(),
			entitySchemaBuildingCtx.getSchema().getName(),
			RequireSchemaBuilder.ALLOWED_CONSTRAINTS_FOR_DELETE
		).build();
	}

	@Nonnull
	private OpenApiTypeReference buildListRequestBodyObject(@Nonnull CollectionRestBuildingContext entitySchemaBuildingContext,
	                                                        boolean localized) {
		final OpenApiObject.Builder objectBuilder = FetchRequestDescriptor.THIS_LIST
			.to(objectBuilderTransformer)
			.name(constructEntityListRequestBodyObjectName(entitySchemaBuildingContext.getSchema(), localized))
			.property(FetchRequestDescriptor.FILTER_BY.to(propertyBuilderTransformer).type(localized ? entitySchemaBuildingContext.getLocalizedFilterByObject() : entitySchemaBuildingContext.getFilterByObject()))
			.property(FetchRequestDescriptor.ORDER_BY.to(propertyBuilderTransformer).type(entitySchemaBuildingContext.getOrderByObject()))
			.property(FetchRequestDescriptor.REQUIRE.to(propertyBuilderTransformer).type(entitySchemaBuildingContext.getRequiredForListObject()));

		return entitySchemaBuildingContext.getCatalogCtx().registerType(objectBuilder.build());
	}

	@Nonnull
	private OpenApiTypeReference buildQueryRequestBodyObject(@Nonnull CollectionRestBuildingContext entitySchemaBuildingContext,
	                                                         boolean localized) {
		final OpenApiObject.Builder objectBuilder = FetchRequestDescriptor.THIS_QUERY
			.to(objectBuilderTransformer)
			.name(constructEntityQueryRequestBodyObjectName(entitySchemaBuildingContext.getSchema(), localized))
			.property(FetchRequestDescriptor.FILTER_BY.to(propertyBuilderTransformer).type(localized ? entitySchemaBuildingContext.getLocalizedFilterByObject() : entitySchemaBuildingContext.getFilterByObject()))
			.property(FetchRequestDescriptor.ORDER_BY.to(propertyBuilderTransformer).type(entitySchemaBuildingContext.getOrderByObject()))
			.property(FetchRequestDescriptor.REQUIRE.to(propertyBuilderTransformer).type(entitySchemaBuildingContext.getRequiredForQueryObject()));

		return entitySchemaBuildingContext.getCatalogCtx().registerType(objectBuilder.build());
	}

	@Nonnull
	private OpenApiTypeReference buildDeleteRequestBodyObject(@Nonnull CollectionRestBuildingContext entitySchemaBuildingContext) {
		final OpenApiObject.Builder objectBuilder = FetchRequestDescriptor.THIS_DELETE
			.to(objectBuilderTransformer)
			.name(FetchRequestDescriptor.THIS_DELETE.name(entitySchemaBuildingContext.getSchema()))
			.property(FetchRequestDescriptor.FILTER_BY.to(propertyBuilderTransformer).type(entitySchemaBuildingContext.getFilterByObject()))
			.property(FetchRequestDescriptor.ORDER_BY.to(propertyBuilderTransformer).type(entitySchemaBuildingContext.getOrderByObject()))
			.property(FetchRequestDescriptor.REQUIRE.to(propertyBuilderTransformer).type(entitySchemaBuildingContext.getRequiredForDeleteObject()));

		return entitySchemaBuildingContext.getCatalogCtx().registerType(objectBuilder.build());
	}

	@Nonnull
	private OpenApiObject buildEntityUnionObject(boolean localized) {
		if (localized) {
			final OpenApiObject.Builder localizedEntityUnionBuilder = EntityUnion.THIS_LOCALIZED
				.to(objectBuilderTransformer)
				.unionType(OpenApiObjectUnionType.ONE_OF)
				.unionDiscriminator(EntityDescriptor.TYPE.name());
			buildingContext.getLocalizedEntityObjects().forEach(localizedEntityUnionBuilder::unionObject);

			return localizedEntityUnionBuilder.build();
		} else {
			final OpenApiObject.Builder entityUnionBuilder = EntityUnion.THIS
				.to(objectBuilderTransformer)
				.unionType(OpenApiObjectUnionType.ONE_OF)
				.unionDiscriminator(EntityDescriptor.TYPE.name());
			buildingContext.getEntityObjects().forEach(entityUnionBuilder::unionObject);

			return entityUnionBuilder.build();
		}
	}

	@Nonnull
	private static OpenApiEnum buildScalarEnum() {
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
	private static OpenApiEnum buildAssociatedDataScalarEnum(@Nonnull OpenApiEnum scalarEnum) {
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
