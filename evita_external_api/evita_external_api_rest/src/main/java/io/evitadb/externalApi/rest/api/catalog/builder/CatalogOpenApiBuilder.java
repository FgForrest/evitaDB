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
import io.evitadb.externalApi.rest.api.catalog.model.ErrorDescriptor;
import io.evitadb.externalApi.rest.api.dto.DataChunkType;
import io.evitadb.externalApi.rest.api.dto.OpenApiEnum;
import io.evitadb.externalApi.rest.api.dto.OpenApiSimpleType;
import io.evitadb.externalApi.rest.api.dto.OpenApiTypeReference;
import io.swagger.v3.oas.models.OpenAPI;
import io.undertow.server.RoutingHandler;
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
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.ENTITY_CURRENCY_ENUM;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.ENTITY_LOCALE_ENUM;
import static io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor.ASSOCIATED_DATA_SCALAR_ENUM;
import static io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor.SCALAR_ENUM;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.FORMAT_CURRENCY;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.FORMAT_LOCALE;
import static io.evitadb.externalApi.rest.api.catalog.builder.constraint.RequireSchemaBuilder.ALLOWED_CONSTRAINTS_FOR_LIST;
import static io.evitadb.externalApi.rest.api.dto.OpenApiEnum.enumFrom;
import static io.evitadb.externalApi.rest.api.dto.OpenApiEnum.newEnum;

/**
 * Creates OpenAPI specification for Evita's catalog.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
@RequiredArgsConstructor
public class CatalogOpenApiBuilder {

	@Nonnull private final CatalogSchemaBuildingContext context;
	@Nonnull private final OpenApiConstraintSchemaBuildingContext constraintSchemaBuildingCtx;

	@Nonnull private final PropertyDataTypeDescriptorToOpenApiTypeTransformer propertyDataTypeBuilderTransformer;
	@Nonnull private final PropertyDescriptorToOpenApiPropertyTransformer propertyBuilderTransformer;
	@Nonnull private final ObjectDescriptorToOpenApiObjectTransformer objectBuilderTransformer;
	@Nonnull private final PropertyDescriptorToOpenApiOperationPathParameterTransformer operationPathParameterBuilderTransformer;
	@Nonnull private final PropertyDescriptorToOpenApiOperationQueryParameterTransformer operationQueryParameterBuilderTransformer;

	@Nonnull private final PathItemBuilder pathItemBuilder;

	/**
	 * Creates new builder.
	 */
	public CatalogOpenApiBuilder(@Nonnull Evita evita, @Nonnull CatalogContract catalog) {
		this.context = new CatalogSchemaBuildingContext(evita, catalog);
		this.constraintSchemaBuildingCtx = new OpenApiConstraintSchemaBuildingContext(context);

		this.propertyDataTypeBuilderTransformer = new PropertyDataTypeDescriptorToOpenApiTypeTransformer(this.context);
		this.propertyBuilderTransformer = new PropertyDescriptorToOpenApiPropertyTransformer(propertyDataTypeBuilderTransformer);
		this.objectBuilderTransformer = new ObjectDescriptorToOpenApiObjectTransformer(propertyBuilderTransformer);
		this.operationPathParameterBuilderTransformer = new PropertyDescriptorToOpenApiOperationPathParameterTransformer(propertyDataTypeBuilderTransformer);
		this.operationQueryParameterBuilderTransformer = new PropertyDescriptorToOpenApiOperationQueryParameterTransformer(propertyDataTypeBuilderTransformer);

		this.pathItemBuilder = new PathItemBuilder(
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
	public OpenAPI build() {
		setupForCatalog();

		final List<OpenApiTypeReference> entityObjects = new LinkedList<>();
		final List<OpenApiTypeReference> localizedEntityObjects = new LinkedList<>();

		context.getEntitySchemas().forEach(entitySchema -> {
			final var entitySchemaBuildingContext = setupForCollection(entitySchema);
			pathItemBuilder.buildAndAddSingleEntityPathItem(entitySchemaBuildingContext, false);
			pathItemBuilder.buildAndAddEntityListPathItem(entitySchemaBuildingContext, false);
			pathItemBuilder.buildAndAddEntityQueryPathItem(entitySchemaBuildingContext, false);
			localizedEntityObjects.add(entitySchemaBuildingContext.getLocalizedEntityObject());

			if(entitySchemaBuildingContext.isLocalizedEntity()) {
				pathItemBuilder.buildAndAddSingleEntityPathItem(entitySchemaBuildingContext, true);
				pathItemBuilder.buildAndAddEntityListPathItem(entitySchemaBuildingContext, true);
				pathItemBuilder.buildAndAddEntityQueryPathItem(entitySchemaBuildingContext, true);

				entityObjects.add(entitySchemaBuildingContext.getEntityObject());
			}

			// todo lho split apis to data and schema first
//			pathItemBuilder.buildAndAddGetEntitySchemaPathItem(entitySchemaBuildingContext);
		});
		pathItemBuilder.buildAndAddOpenApiSpecificationPathItem(context);
		pathItemBuilder.buildAndAddCollectionsPathItem(context);

		final List<GlobalAttributeSchemaContract> globallyUniqueAttributes = getGloballyUniqueAttributes(context.getCatalog());
		if(!globallyUniqueAttributes.isEmpty()) {
			pathItemBuilder.buildAndUnknownSingleEntityPathItem(context, localizedEntityObjects, globallyUniqueAttributes, false);
			pathItemBuilder.buildAndUnknownSingleEntityPathItem(context, entityObjects, globallyUniqueAttributes, true);
			pathItemBuilder.buildAndUnknownEntityListPathItem(context, localizedEntityObjects, globallyUniqueAttributes, false);
			pathItemBuilder.buildAndUnknownEntityListPathItem(context, entityObjects, globallyUniqueAttributes, true);
		}

		// register gathered custom constraint schema types
		constraintSchemaBuildingCtx.getBuiltTypes().forEach(context::registerType);

		return context.buildOpenApi();
	}

	/**
	 * Gets routing handler which contains all URLs for each service defined in OpenAPI. This method has to be called
	 * after OpenAPI schema is built.
	 */
	public RoutingHandler getRoutingHandler() {
		return context.getRestApiHandlerRegistrar().getRoutingHandler();
	}

	private void setupForCatalog() {
		final OpenApiEnum scalarEnum = buildScalarEnum();
		context.registerType(scalarEnum);
		context.registerType(buildAssociatedDataScalarEnum(scalarEnum));
		context.registerType(ErrorDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(enumFrom(DataChunkType.class));

		context.registerType(HierarchicalPlacementDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(PriceDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(BucketDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(HistogramDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(QueryTelemetryDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(FacetRequestImpactDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(SchemaNameVariantsDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(AttributeSchemaDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(GlobalAttributeSchemaDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(AssociatedDataSchemaDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(EntityDescriptor.THIS_ENTITY_REFERENCE.to(objectBuilderTransformer).build());

		// upsert mutations
		context.registerType(RemoveAssociatedDataMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(UpsertAssociatedDataMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(ApplyDeltaAttributeMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(RemoveAttributeMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(UpsertAttributeMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(SetHierarchicalPlacementMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(SetPriceInnerRecordHandlingMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(RemovePriceMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(UpsertPriceMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(InsertReferenceMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(RemoveReferenceMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(SetReferenceGroupMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(RemoveReferenceGroupMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(ReferenceAttributeMutationDescriptor.THIS.to(objectBuilderTransformer).build());
		context.registerType(ReferenceAttributeMutationAggregateDescriptor.THIS.to(objectBuilderTransformer).build());
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

			context.registerType(localeEnumBuilder.build());
		}

		if (!entitySchema.getCurrencies().isEmpty()) {
			final OpenApiEnum.Builder currencyEnumBuilder = newEnum()
				.name(ENTITY_CURRENCY_ENUM.name(entitySchema))
				.description(ENTITY_CURRENCY_ENUM.description())
				.format(FORMAT_CURRENCY);
			entitySchema.getCurrencies().forEach(c -> currencyEnumBuilder.item(c.toString()));

			context.registerType(currencyEnumBuilder.build());
		}

		final OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx = new OpenApiEntitySchemaBuildingContext(
			context,
			constraintSchemaBuildingCtx,
			entitySchema
		);

		// build filter schema
		final OpenApiSimpleType filterBySchema = new FilterBySchemaBuilder(
			constraintSchemaBuildingCtx,
			entitySchemaBuildingCtx.getSchema().getName(),
			false
		).build();
		entitySchemaBuildingCtx.setFilterByInputObject(filterBySchema);

		final OpenApiSimpleType filterByLocalizedSchema = new FilterBySchemaBuilder(
			constraintSchemaBuildingCtx,
			entitySchemaBuildingCtx.getSchema().getName(),
			true
		).build();
		entitySchemaBuildingCtx.setFilterByLocalizedInputObject(filterByLocalizedSchema);

		// build order schema
		final OpenApiSimpleType orderBySchema = new OrderBySchemaBuilder(
			constraintSchemaBuildingCtx,
			entitySchemaBuildingCtx.getSchema().getName()
		).build();
		entitySchemaBuildingCtx.setOrderByInputObject(orderBySchema);

		// build require schema
		entitySchemaBuildingCtx.setRequiredForListInputObject(buildRequireSchemaForList(entitySchemaBuildingCtx));
		entitySchemaBuildingCtx.setRequiredForQueryInputObject(buildRequireSchemaForQuery(entitySchemaBuildingCtx));

		final EntityObjectBuilder entityObjectBuilder = new EntityObjectBuilder(
			entitySchemaBuildingCtx,
			propertyBuilderTransformer,
			objectBuilderTransformer
		);
		entitySchemaBuildingCtx.setLocalizedEntityObject(entityObjectBuilder.buildEntityObject(true));
		if(entitySchemaBuildingCtx.isLocalizedEntity()) {
			entitySchemaBuildingCtx.setEntityObject(entityObjectBuilder.buildEntityObject(false));
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

		new DataMutationSchemaBuilder(
			entitySchemaBuildingCtx,
			propertyBuilderTransformer,
			objectBuilderTransformer,
			pathItemBuilder
		).buildAndAddEntitiesAndPathItems();

		return entitySchemaBuildingCtx;
	}

	@Nonnull
	private OpenApiSimpleType buildRequireSchemaForList(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx) {
		return new RequireSchemaBuilder(
			constraintSchemaBuildingCtx,
			entitySchemaBuildingCtx.getSchema().getName(),
			ALLOWED_CONSTRAINTS_FOR_LIST
		).build();
	}

	@Nonnull
	private OpenApiSimpleType buildRequireSchemaForQuery(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx) {
		return new RequireSchemaBuilder(
			constraintSchemaBuildingCtx,
			entitySchemaBuildingCtx.getSchema().getName()
		).build();
	}

	@Nonnull
	public static List<GlobalAttributeSchemaContract> getGloballyUniqueAttributes(@Nonnull CatalogContract catalog) {
		return catalog
			.getSchema()
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
