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
import io.evitadb.externalApi.rest.api.catalog.model.ErrorDescriptor;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
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

import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.ENTITY_CURRENCY_ENUM;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.ENTITY_LOCALE_ENUM;
import static io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor.ASSOCIATED_DATA_SCALAR_ENUM;
import static io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor.SCALAR_ENUM;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.TYPE_STRING;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createCurrencySchema;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createLocaleSchema;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createSchema;
import static io.evitadb.externalApi.rest.api.catalog.builder.constraint.RequireSchemaBuilder.ALLOWED_CONSTRAINTS_FOR_LIST;
import static io.evitadb.externalApi.rest.api.catalog.builder.transformer.Transformers.OBJECT_TRANSFORMER;

/**
 * Creates OpenAPI specification for Evita's catalog.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
@RequiredArgsConstructor
public class CatalogOpenApiBuilder {
	private final CatalogSchemaBuildingContext context;
	private final OpenApiConstraintSchemaBuildingContext constraintSchemaBuildingCtx;

	/**
	 * Creates new builder.
	 */
	public CatalogOpenApiBuilder(@Nonnull Evita evita, @Nonnull CatalogContract catalog) {
		this.context = new CatalogSchemaBuildingContext(evita, catalog);
		this.constraintSchemaBuildingCtx = new OpenApiConstraintSchemaBuildingContext(context.getCatalog());
	}

	/**
	 * Builds OpenAPI specification for provided catalog.
	 *
	 * @return OpenAPI specification
	 */
	public OpenAPI build() {
		setupForCatalog();

		final List<Schema<Object>> entityObjects = new LinkedList<>();
		final List<Schema<Object>> localizedEntityObjects = new LinkedList<>();

		final PathItemBuilder pathItemBuilder = new PathItemBuilder();
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

		final OpenApiSchemaReferenceValidator referenceValidator = new OpenApiSchemaReferenceValidator(context.getOpenAPI());
		if(!referenceValidator.validateSchemaReferences()) {
			log.error("Found missing schema in OpenAPI for catalog: " + context.getCatalog().getName());
			for (String missingSchema : referenceValidator.getMissingSchemas()) {
				log.error("Missing schema name: " + missingSchema);
			}
		}

		return context.getOpenAPI();
	}

	/**
	 * Gets routing handler which contains all URLs for each service defined in OpenAPI. This method has to be called
	 * after OpenAPI schema is built.
	 */
	public RoutingHandler getRoutingHandler() {
		return context.getRestApiHandlerRegistrar().getRoutingHandler();
	}

	private void setupForCatalog() {
		createInfo();

		context.registerType(ErrorDescriptor.THIS.to(OBJECT_TRANSFORMER));
		SchemaCreator.createAllDataTypes().forEach(context::registerType);
		context.registerType(buildScalarEnum());
		context.registerType(buildAssociatedDataScalarEnum());

		context.registerType(HierarchicalPlacementDescriptor.THIS.to(OBJECT_TRANSFORMER));
		context.registerType(PriceDescriptor.THIS.to(OBJECT_TRANSFORMER));
		context.registerType(BucketDescriptor.THIS.to(OBJECT_TRANSFORMER));
		context.registerType(HistogramDescriptor.THIS.to(OBJECT_TRANSFORMER));
		context.registerType(QueryTelemetryDescriptor.THIS.to(OBJECT_TRANSFORMER));
		context.registerType(FacetRequestImpactDescriptor.THIS.to(OBJECT_TRANSFORMER));
		context.registerType(SchemaNameVariantsDescriptor.THIS.to(OBJECT_TRANSFORMER));
		context.registerType(AttributeSchemaDescriptor.THIS.to(OBJECT_TRANSFORMER));
		context.registerType(GlobalAttributeSchemaDescriptor.THIS.to(OBJECT_TRANSFORMER));
		context.registerType(AssociatedDataSchemaDescriptor.THIS.to(OBJECT_TRANSFORMER));
		context.registerType(EntityDescriptor.THIS_ENTITY_REFERENCE.to(OBJECT_TRANSFORMER));

		// upsert mutations
		context.registerType(RemoveAssociatedDataMutationDescriptor.THIS.to(OBJECT_TRANSFORMER));
		context.registerType(UpsertAssociatedDataMutationDescriptor.THIS.to(OBJECT_TRANSFORMER));
		context.registerType(ApplyDeltaAttributeMutationDescriptor.THIS.to(OBJECT_TRANSFORMER));
		context.registerType(RemoveAttributeMutationDescriptor.THIS.to(OBJECT_TRANSFORMER));
		context.registerType(UpsertAttributeMutationDescriptor.THIS.to(OBJECT_TRANSFORMER));
		context.registerType(SetHierarchicalPlacementMutationDescriptor.THIS.to(OBJECT_TRANSFORMER));
		context.registerType(SetPriceInnerRecordHandlingMutationDescriptor.THIS.to(OBJECT_TRANSFORMER));
		context.registerType(RemovePriceMutationDescriptor.THIS.to(OBJECT_TRANSFORMER));
		context.registerType(UpsertPriceMutationDescriptor.THIS.to(OBJECT_TRANSFORMER));
		context.registerType(InsertReferenceMutationDescriptor.THIS.to(OBJECT_TRANSFORMER));
		context.registerType(RemoveReferenceMutationDescriptor.THIS.to(OBJECT_TRANSFORMER));
		context.registerType(SetReferenceGroupMutationDescriptor.THIS.to(OBJECT_TRANSFORMER));
		context.registerType(RemoveReferenceGroupMutationDescriptor.THIS.to(OBJECT_TRANSFORMER));
		context.registerType(ReferenceAttributeMutationDescriptor.THIS.to(OBJECT_TRANSFORMER));
		context.registerType(ReferenceAttributeMutationAggregateDescriptor.THIS.to(OBJECT_TRANSFORMER));
	}

	/**
	 * Prepare common data for specific collection schema building.
	 */
	@Nonnull
	protected OpenApiEntitySchemaBuildingContext setupForCollection(@Nonnull EntitySchemaContract entitySchema) {
		final Schema<Object> localeSchema;
		if (!entitySchema.getLocales().isEmpty()) {
			localeSchema = createLocaleSchema();
			localeSchema.setName(ENTITY_LOCALE_ENUM.name(entitySchema));
			entitySchema.getLocales().forEach(l -> localeSchema.addEnumItemObject(l.toLanguageTag()));
			context.registerType(localeSchema);
		} else {
			localeSchema = null;
		}

		final Schema<Object> currencySchema;
		if (!entitySchema.getCurrencies().isEmpty()) {
			currencySchema = createCurrencySchema();
			currencySchema.setName(ENTITY_CURRENCY_ENUM.name(entitySchema));
			entitySchema.getCurrencies().forEach(c -> currencySchema.addEnumItemObject(c.toString()));
			context.registerType(currencySchema);
		} else {
			currencySchema = null;
		}

		final OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx = new OpenApiEntitySchemaBuildingContext(
			context,
			constraintSchemaBuildingCtx,
			entitySchema,
			localeSchema,
			currencySchema
		);

		// build filter schema
		final Schema<Object> filterBySchemaDescriptor = new FilterBySchemaBuilder(
			constraintSchemaBuildingCtx,
			entitySchemaBuildingCtx.getSchema().getName(),
			false
		).build();
		entitySchemaBuildingCtx.setFilterByInputObject(filterBySchemaDescriptor);

		final Schema<Object> filterByLocalizedSchemaDescriptor = new FilterBySchemaBuilder(
			constraintSchemaBuildingCtx,
			entitySchemaBuildingCtx.getSchema().getName(),
			true
		).build();
		entitySchemaBuildingCtx.setFilterByLocalizedInputObject(filterByLocalizedSchemaDescriptor);

		// build order schema
		final Schema<Object> orderBySchemaDescriptor = new OrderBySchemaBuilder(
			constraintSchemaBuildingCtx,
			entitySchemaBuildingCtx.getSchema().getName()
		).build();
		entitySchemaBuildingCtx.setOrderByInputObject(orderBySchemaDescriptor);

		// build require schema
		entitySchemaBuildingCtx.setRequiredForListInputObject(buildRequireSchemaForList(entitySchemaBuildingCtx));
		entitySchemaBuildingCtx.setRequiredForQueryInputObject(buildRequireSchemaForQuery(entitySchemaBuildingCtx));

		final EntityObjectBuilder entityObjectBuilder = new EntityObjectBuilder(entitySchemaBuildingCtx);
		entitySchemaBuildingCtx.setLocalizedEntityObject(entityObjectBuilder.buildEntityObject(true));
		if(entitySchemaBuildingCtx.isLocalizedEntity()) {
			entitySchemaBuildingCtx.setEntityObject(entityObjectBuilder.buildEntityObject(false));
		} else {
			/* When entity has no localized data than it makes no sense to create endpoints for this entity with Locale
			* in URL. But this entity may be referenced by other entities when localized URL is used. For such cases
			* is also appropriate entity schema created.*/
			entitySchemaBuildingCtx.getCatalogCtx().registerType(entityObjectBuilder.buildEntityObject(false));
		}

		final EntitySchemaObjectBuilder entitySchemaObjectBuilder = new EntitySchemaObjectBuilder(entitySchemaBuildingCtx);
		entitySchemaObjectBuilder.buildEntitySchemaObject();

		new DataMutationSchemaBuilder(entitySchemaBuildingCtx).buildAndAddEntitiesAndPathItems();

		return entitySchemaBuildingCtx;
	}

	@Nonnull
	private Schema<Object> buildRequireSchemaForList(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx) {
		return new RequireSchemaBuilder(
			constraintSchemaBuildingCtx,
			entitySchemaBuildingCtx.getSchema().getName(),
			ALLOWED_CONSTRAINTS_FOR_LIST
		).build();
	}

	@Nonnull
	private Schema<Object> buildRequireSchemaForQuery(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx) {
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
	protected static Schema<Object> buildScalarEnum() {
		final Schema<Object> scalarEnum = createSchema(TYPE_STRING);
		scalarEnum
			.name(SCALAR_ENUM.name())
			.description(SCALAR_ENUM.description());

		registerScalarValue(scalarEnum, String.class);
		registerScalarValue(scalarEnum, String[].class);
		registerScalarValue(scalarEnum, Byte.class);
		registerScalarValue(scalarEnum, Byte[].class);
		registerScalarValue(scalarEnum, Short.class);
		registerScalarValue(scalarEnum, Short[].class);
		registerScalarValue(scalarEnum, Integer.class);
		registerScalarValue(scalarEnum, Integer[].class);
		registerScalarValue(scalarEnum, Long.class);
		registerScalarValue(scalarEnum, Long[].class);
		registerScalarValue(scalarEnum, Boolean.class);
		registerScalarValue(scalarEnum, Boolean[].class);
		registerScalarValue(scalarEnum, Character.class);
		registerScalarValue(scalarEnum, Character[].class);
		registerScalarValue(scalarEnum, BigDecimal.class);
		registerScalarValue(scalarEnum, BigDecimal[].class);
		registerScalarValue(scalarEnum, OffsetDateTime.class);
		registerScalarValue(scalarEnum, OffsetDateTime[].class);
		registerScalarValue(scalarEnum, LocalDateTime.class);
		registerScalarValue(scalarEnum, LocalDateTime[].class);
		registerScalarValue(scalarEnum, LocalDate.class);
		registerScalarValue(scalarEnum, LocalDate[].class);
		registerScalarValue(scalarEnum, LocalTime.class);
		registerScalarValue(scalarEnum, LocalTime[].class);
		registerScalarValue(scalarEnum, DateTimeRange.class);
		registerScalarValue(scalarEnum, DateTimeRange[].class);
		registerScalarValue(scalarEnum, BigDecimalNumberRange.class);
		registerScalarValue(scalarEnum, BigDecimalNumberRange[].class);
		registerScalarValue(scalarEnum, ByteNumberRange.class);
		registerScalarValue(scalarEnum, ByteNumberRange[].class);
		registerScalarValue(scalarEnum, ShortNumberRange.class);
		registerScalarValue(scalarEnum, ShortNumberRange[].class);
		registerScalarValue(scalarEnum, IntegerNumberRange.class);
		registerScalarValue(scalarEnum, IntegerNumberRange[].class);
		registerScalarValue(scalarEnum, LongNumberRange.class);
		registerScalarValue(scalarEnum, LongNumberRange[].class);
		registerScalarValue(scalarEnum, Locale.class);
		registerScalarValue(scalarEnum, Locale[].class);
		registerScalarValue(scalarEnum, Currency.class);
		registerScalarValue(scalarEnum, Currency[].class);
		registerScalarValue(scalarEnum, ComplexDataObject.class);

		return scalarEnum;
	}

	@Nonnull
	protected static Schema<Object> buildAssociatedDataScalarEnum() {
		final Schema<Object> scalarEnum = buildScalarEnum();
		scalarEnum
			.name(ASSOCIATED_DATA_SCALAR_ENUM.name())
			.description(ASSOCIATED_DATA_SCALAR_ENUM.description());

		registerScalarValue(scalarEnum, ComplexDataObject.class);

		return scalarEnum;
	}

	private static void registerScalarValue(@Nonnull Schema<Object> scalarEnum,
	                                        @Nonnull Class<? extends Serializable> javaType) {
		final String apiName;
		if (javaType.isArray()) {
			apiName = javaType.componentType().getSimpleName() + "Array";
		} else {
			apiName = javaType.getSimpleName();
		}

		scalarEnum.addEnumItemObject(apiName);
	}

	private void createInfo() {
		final var info = new Info();
		info.setTitle("Web services for catalog \"" + context.getCatalog().getName() + "\"");
		info.setContact(new Contact().email("novotny@fg.cz").url("https://www.fg.cz"));
		info.setVersion("1.0.0-oas3");
		context.getOpenAPI().info(info);
	}
}
