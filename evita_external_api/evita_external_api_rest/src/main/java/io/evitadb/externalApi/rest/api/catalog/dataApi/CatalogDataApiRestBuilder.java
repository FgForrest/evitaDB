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

package io.evitadb.externalApi.rest.api.catalog.dataApi;

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.dataType.DataTypeSerializer;
import io.evitadb.externalApi.rest.api.builder.PartialRestBuilder;
import io.evitadb.externalApi.rest.api.catalog.builder.CatalogRestBuildingContext;
import io.evitadb.externalApi.rest.api.catalog.dataApi.builder.CollectionDataApiRestBuildingContext;
import io.evitadb.externalApi.rest.api.catalog.dataApi.builder.DataApiEndpointBuilder;
import io.evitadb.externalApi.rest.api.catalog.dataApi.builder.DataMutationBuilder;
import io.evitadb.externalApi.rest.api.catalog.dataApi.builder.EntityObjectBuilder;
import io.evitadb.externalApi.rest.api.catalog.dataApi.builder.FullResponseObjectBuilder;
import io.evitadb.externalApi.rest.api.catalog.dataApi.builder.constraint.FilterConstraintSchemaBuilder;
import io.evitadb.externalApi.rest.api.catalog.dataApi.builder.constraint.OpenApiConstraintSchemaBuildingContext;
import io.evitadb.externalApi.rest.api.catalog.dataApi.builder.constraint.OrderConstraintSchemaBuilder;
import io.evitadb.externalApi.rest.api.catalog.dataApi.builder.constraint.RequireConstraintSchemaBuilder;
import io.evitadb.externalApi.rest.api.catalog.dataApi.dto.DataChunkType;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.CollectionDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.EntityUnion;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.FetchEntityRequestDescriptor;
import io.evitadb.externalApi.rest.api.model.ObjectDescriptorToOpenApiEnumTransformer;
import io.evitadb.externalApi.rest.api.openApi.OpenApiConstants;
import io.evitadb.externalApi.rest.api.openApi.OpenApiEnum;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObject;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObjectUnionType;
import io.evitadb.externalApi.rest.api.openApi.OpenApiSimpleType;
import io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference;
import io.evitadb.externalApi.rest.api.openApi.OpenApiUnion;

import javax.annotation.Nonnull;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.CURRENCY_ENUM;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.LOCALE_ENUM;
import static io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor.ASSOCIATED_DATA_SCALAR_ENUM;
import static io.evitadb.externalApi.rest.api.catalog.dataApi.builder.DataApiNamesConstructor.constructEntityListRequestBodyObjectName;
import static io.evitadb.externalApi.rest.api.catalog.dataApi.builder.DataApiNamesConstructor.constructEntityQueryRequestBodyObjectName;
import static io.evitadb.externalApi.rest.api.catalog.dataApi.builder.constraint.RequireConstraintSchemaBuilder.ALLOWED_CONSTRAINTS_FOR_DELETE;
import static io.evitadb.externalApi.rest.api.catalog.dataApi.builder.constraint.RequireConstraintSchemaBuilder.ALLOWED_CONSTRAINTS_FOR_LIST;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiEnum.enumFrom;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiEnum.newEnum;

/**
 * Builds data API part of catalog's REST API. Building of whole REST API is handled by {@link io.evitadb.externalApi.rest.api.catalog.CatalogRestBuilder}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class CatalogDataApiRestBuilder extends PartialRestBuilder<CatalogRestBuildingContext> {

	@Nonnull private final OpenApiConstraintSchemaBuildingContext constraintBuildingContext;
	@Nonnull private final FilterConstraintSchemaBuilder filterConstraintSchemaBuilder;
	@Nonnull private final FilterConstraintSchemaBuilder localizedFilterConstraintSchemaBuilder;
	@Nonnull private final OrderConstraintSchemaBuilder orderConstraintSchemaBuilder;
	@Nonnull private final RequireConstraintSchemaBuilder listRequireConstraintSchemaBuilder;
	@Nonnull private final RequireConstraintSchemaBuilder localizedListRequireConstraintSchemaBuilder;
	@Nonnull private final RequireConstraintSchemaBuilder queryRequireConstraintSchemaBuilder;
	@Nonnull private final RequireConstraintSchemaBuilder localizedQueryRequireConstraintSchemaBuilder;
	@Nonnull private final RequireConstraintSchemaBuilder upsertRequireConstraintSchemaBuilder;
	@Nonnull private final RequireConstraintSchemaBuilder deleteRequireConstraintSchemaBuilder;

	@Nonnull private final DataApiEndpointBuilder endpointBuilder;
	@Nonnull private final EntityObjectBuilder entityObjectBuilder;
	@Nonnull private final FullResponseObjectBuilder fullResponseObjectBuilder;
	@Nonnull private final DataMutationBuilder dataMutationBuilder;

	public CatalogDataApiRestBuilder(@Nonnull CatalogRestBuildingContext buildingContext) {
		super(buildingContext);
		this.constraintBuildingContext = new OpenApiConstraintSchemaBuildingContext(buildingContext);

		this.filterConstraintSchemaBuilder = new FilterConstraintSchemaBuilder(constraintBuildingContext, false);
		this.localizedFilterConstraintSchemaBuilder = new FilterConstraintSchemaBuilder(constraintBuildingContext, true);
		this.orderConstraintSchemaBuilder = new OrderConstraintSchemaBuilder(constraintBuildingContext);
		this.listRequireConstraintSchemaBuilder = new RequireConstraintSchemaBuilder(
			constraintBuildingContext,
			ALLOWED_CONSTRAINTS_FOR_LIST,
			new AtomicReference<>(this.filterConstraintSchemaBuilder),
			new AtomicReference<>(this.orderConstraintSchemaBuilder)
		);
		this.localizedListRequireConstraintSchemaBuilder = new RequireConstraintSchemaBuilder(
			constraintBuildingContext,
			ALLOWED_CONSTRAINTS_FOR_LIST,
			new AtomicReference<>(this.localizedFilterConstraintSchemaBuilder),
			new AtomicReference<>(this.orderConstraintSchemaBuilder)
		);
		this.queryRequireConstraintSchemaBuilder = new RequireConstraintSchemaBuilder(
			constraintBuildingContext,
			new AtomicReference<>(this.filterConstraintSchemaBuilder),
			new AtomicReference<>(this.orderConstraintSchemaBuilder)
		);
		this.localizedQueryRequireConstraintSchemaBuilder = new RequireConstraintSchemaBuilder(
			constraintBuildingContext,
			new AtomicReference<>(this.localizedFilterConstraintSchemaBuilder),
			new AtomicReference<>(this.orderConstraintSchemaBuilder)
		);
		this.upsertRequireConstraintSchemaBuilder = new RequireConstraintSchemaBuilder(
			constraintBuildingContext,
			RequireConstraintSchemaBuilder.ALLOWED_CONSTRAINTS_FOR_UPSERT,
			new AtomicReference<>(filterConstraintSchemaBuilder),
			new AtomicReference<>(orderConstraintSchemaBuilder)
		);
		this.deleteRequireConstraintSchemaBuilder = new RequireConstraintSchemaBuilder(
			constraintBuildingContext,
			ALLOWED_CONSTRAINTS_FOR_DELETE,
			new AtomicReference<>(this.filterConstraintSchemaBuilder),
			new AtomicReference<>(this.orderConstraintSchemaBuilder)
		);

		this.endpointBuilder = new DataApiEndpointBuilder(
			buildingContext,
			operationPathParameterBuilderTransformer,
			operationQueryParameterBuilderTransformer
		);
		this.entityObjectBuilder = new EntityObjectBuilder(
			buildingContext,
			propertyBuilderTransformer,
			objectBuilderTransformer
		);
		this.fullResponseObjectBuilder = new FullResponseObjectBuilder(
			buildingContext,
			propertyBuilderTransformer,
			objectBuilderTransformer,
			unionBuilderTransformer,
			dictionaryBuilderTransformer
		);
		this.dataMutationBuilder = new DataMutationBuilder(
			buildingContext,
			propertyBuilderTransformer,
			objectBuilderTransformer,
			upsertRequireConstraintSchemaBuilder
		);
	}

	@Override
	public void build() {
		buildCommonTypes();
		buildEndpoints();

		// register gathered custom constraint schema types
		constraintBuildingContext.getBuiltTypes().forEach(buildingContext::registerType);
	}

	private void buildCommonTypes() {
		buildLocaleEnum().ifPresent(buildingContext::registerCustomEnumIfAbsent);
		buildCurrencyEnum().ifPresent(buildingContext::registerCustomEnumIfAbsent);

		buildingContext.registerType(buildAssociatedDataScalarEnum());
		buildingContext.registerType(enumFrom(DataChunkType.class));

		buildingContext.registerType(CollectionDescriptor.THIS.to(objectBuilderTransformer).build());

		entityObjectBuilder.buildCommonTypes();
		fullResponseObjectBuilder.buildCommonTypes();
		dataMutationBuilder.buildCommonTypes();
	}

	private void buildEndpoints() {
		buildingContext.registerEndpoint(endpointBuilder.buildCollectionsEndpoint(buildingContext));

		buildingContext.getEntitySchemas().forEach(entitySchema -> {
			final CollectionDataApiRestBuildingContext collectionBuildingContext = setupForCollection(entitySchema);

			buildingContext.registerEndpoint(endpointBuilder.buildGetEntityEndpoint(entitySchema, false, false));
			buildingContext.registerEndpoint(endpointBuilder.buildGetEntityEndpoint(entitySchema, false, true));

			buildingContext.registerEndpoint(endpointBuilder.buildListEntityEndpoint(entitySchema, false));
			buildingContext.registerEndpoint(endpointBuilder.buildQueryEntityEndpoint(entitySchema, false));

			if(collectionBuildingContext.isLocalizedEntity()) {
				buildingContext.registerEndpoint(endpointBuilder.buildGetEntityEndpoint(entitySchema, true, false));
				buildingContext.registerEndpoint(endpointBuilder.buildGetEntityEndpoint(entitySchema, true, true));

				buildingContext.registerEndpoint(endpointBuilder.buildListEntityEndpoint(entitySchema, true));
				buildingContext.registerEndpoint(endpointBuilder.buildQueryEntityEndpoint(entitySchema, true));
			}

			buildingContext.registerEndpoint(endpointBuilder.buildUpsertEntityEndpoint(entitySchema, true));
			if (entitySchema.isWithGeneratedPrimaryKey()) {
				buildingContext.registerEndpoint(endpointBuilder.buildUpsertEntityEndpoint(entitySchema, false));
			}

			buildingContext.registerEndpoint(endpointBuilder.buildDeleteEntityEndpoint(entitySchema));
			buildingContext.registerEndpoint(endpointBuilder.buildDeleteEntitiesByQueryEndpoint(entitySchema));
		});

		buildEntityUnion(false).ifPresent(buildingContext::registerType);
		buildEntityUnion(true).ifPresent(buildingContext::registerType);

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
	private CollectionDataApiRestBuildingContext setupForCollection(@Nonnull EntitySchemaContract entitySchema) {
		final CollectionDataApiRestBuildingContext collectionBuildingContext = new CollectionDataApiRestBuildingContext(
			buildingContext,
			entitySchema
		);

		// build filter schema
		final OpenApiSimpleType filterByObject = filterConstraintSchemaBuilder.build(entitySchema.getName());
		collectionBuildingContext.setFilterByObject(filterByObject);
		final OpenApiSimpleType localizedFilterByObject = localizedFilterConstraintSchemaBuilder.build(entitySchema.getName());
		collectionBuildingContext.setLocalizedFilterByObject(localizedFilterByObject);

		// build order schema
		final OpenApiSimpleType orderByObject = orderConstraintSchemaBuilder.build(entitySchema.getName());
		collectionBuildingContext.setOrderByObject(orderByObject);

		// build require schema
		final OpenApiSimpleType requireForListObject = listRequireConstraintSchemaBuilder.build(entitySchema.getName());
		collectionBuildingContext.setRequireForListObject(requireForListObject);
		final OpenApiSimpleType localizedRequireForListObject = localizedListRequireConstraintSchemaBuilder.build(entitySchema.getName());
		collectionBuildingContext.setLocalizedRequireForListObject(localizedRequireForListObject);
		final OpenApiSimpleType requireForQueryObject = queryRequireConstraintSchemaBuilder.build(entitySchema.getName());
		collectionBuildingContext.setRequireForQueryObject(requireForQueryObject);
		final OpenApiSimpleType localizedRequireForQueryObject = localizedQueryRequireConstraintSchemaBuilder.build(entitySchema.getName());
		collectionBuildingContext.setLocalizedRequireForQueryObject(localizedRequireForQueryObject);
		final OpenApiSimpleType requireForDeleteObject = deleteRequireConstraintSchemaBuilder.build(entitySchema.getName());
		collectionBuildingContext.setRequireForDeleteObject(requireForDeleteObject);

		buildingContext.registerEntityObject(entityObjectBuilder.buildEntityObject(entitySchema, false));

		buildListRequestBodyObject(collectionBuildingContext, false);
		buildQueryRequestBodyObject(collectionBuildingContext, false);
		buildDeleteRequestBodyObject(collectionBuildingContext);

		buildingContext.registerType(fullResponseObjectBuilder.buildFullResponseObject(entitySchema, false));

		if(collectionBuildingContext.isLocalizedEntity()) {
			buildingContext.registerLocalizedEntityObject(entityObjectBuilder.buildEntityObject(entitySchema, true));

			buildListRequestBodyObject(collectionBuildingContext, true);
			buildQueryRequestBodyObject(collectionBuildingContext, true);

			buildingContext.registerType(fullResponseObjectBuilder.buildFullResponseObject(entitySchema, true));
		} else {
			/* When entity has no localized data than it makes no sense to create endpoints for this entity with Locale
			 * in URL. But this entity may be referenced by other entities when localized URL is used. For such cases
			 * is also appropriate entity schema created.*/
			collectionBuildingContext.getCatalogCtx().registerType(entityObjectBuilder.buildEntityObject(entitySchema, true));
		}

		dataMutationBuilder.buildEntityUpsertRequestObject(entitySchema);

		return collectionBuildingContext;
	}

	@Nonnull
	private OpenApiTypeReference buildListRequestBodyObject(@Nonnull CollectionDataApiRestBuildingContext entitySchemaBuildingContext,
	                                                        boolean localized) {
		final OpenApiObject.Builder objectBuilder = FetchEntityRequestDescriptor.THIS_LIST
			.to(objectBuilderTransformer)
			.name(constructEntityListRequestBodyObjectName(entitySchemaBuildingContext.getSchema(), localized))
			.property(FetchEntityRequestDescriptor.FILTER_BY.to(propertyBuilderTransformer).type(localized ? entitySchemaBuildingContext.getLocalizedFilterByObject() : entitySchemaBuildingContext.getFilterByObject()))
			.property(FetchEntityRequestDescriptor.ORDER_BY.to(propertyBuilderTransformer).type(entitySchemaBuildingContext.getOrderByObject()))
			.property(FetchEntityRequestDescriptor.REQUIRE.to(propertyBuilderTransformer).type(localized ? entitySchemaBuildingContext.getLocalizedRequireForListObject() : entitySchemaBuildingContext.getRequireForListObject()));

		return entitySchemaBuildingContext.getCatalogCtx().registerType(objectBuilder.build());
	}

	@Nonnull
	private OpenApiTypeReference buildQueryRequestBodyObject(@Nonnull CollectionDataApiRestBuildingContext entitySchemaBuildingContext,
	                                                         boolean localized) {
		final OpenApiObject.Builder objectBuilder = FetchEntityRequestDescriptor.THIS_QUERY
			.to(objectBuilderTransformer)
			.name(constructEntityQueryRequestBodyObjectName(entitySchemaBuildingContext.getSchema(), localized))
			.property(FetchEntityRequestDescriptor.FILTER_BY.to(propertyBuilderTransformer).type(localized ? entitySchemaBuildingContext.getLocalizedFilterByObject() : entitySchemaBuildingContext.getFilterByObject()))
			.property(FetchEntityRequestDescriptor.ORDER_BY.to(propertyBuilderTransformer).type(entitySchemaBuildingContext.getOrderByObject()))
			.property(FetchEntityRequestDescriptor.REQUIRE.to(propertyBuilderTransformer).type(localized ? entitySchemaBuildingContext.getLocalizedRequireForQueryObject() : entitySchemaBuildingContext.getRequireForQueryObject()));

		return entitySchemaBuildingContext.getCatalogCtx().registerType(objectBuilder.build());
	}

	@Nonnull
	private OpenApiTypeReference buildDeleteRequestBodyObject(@Nonnull CollectionDataApiRestBuildingContext entitySchemaBuildingContext) {
		final OpenApiObject.Builder objectBuilder = FetchEntityRequestDescriptor.THIS_DELETE
			.to(objectBuilderTransformer)
			.name(FetchEntityRequestDescriptor.THIS_DELETE.name(entitySchemaBuildingContext.getSchema()))
			.property(FetchEntityRequestDescriptor.FILTER_BY.to(propertyBuilderTransformer).type(entitySchemaBuildingContext.getFilterByObject()))
			.property(FetchEntityRequestDescriptor.ORDER_BY.to(propertyBuilderTransformer).type(entitySchemaBuildingContext.getOrderByObject()))
			.property(FetchEntityRequestDescriptor.REQUIRE.to(propertyBuilderTransformer).type(entitySchemaBuildingContext.getRequireForDeleteObject()));

		return entitySchemaBuildingContext.getCatalogCtx().registerType(objectBuilder.build());
	}

	@Nonnull
	private Optional<OpenApiUnion> buildEntityUnion(boolean localized) {
		if (localized) {
			final List<OpenApiTypeReference> entityObjects = buildingContext.getLocalizedEntityObjects();
			if (entityObjects.isEmpty()) {
				return Optional.empty();
			}

			final OpenApiUnion.Builder localizedEntityUnionBuilder = EntityUnion.THIS_LOCALIZED
				.to(unionBuilderTransformer)
				.type(OpenApiObjectUnionType.ONE_OF)
				.discriminator(EntityDescriptor.TYPE.name());
			entityObjects.forEach(localizedEntityUnionBuilder::object);

			return Optional.of(localizedEntityUnionBuilder.build());
		} else {
			final List<OpenApiTypeReference> entityObjects = buildingContext.getEntityObjects();
			if (entityObjects.isEmpty()) {
				return Optional.empty();
			}
			final OpenApiUnion.Builder entityUnionBuilder = EntityUnion.THIS
				.to(unionBuilderTransformer)
				.type(OpenApiObjectUnionType.ONE_OF)
				.discriminator(EntityDescriptor.TYPE.name());
			entityObjects.forEach(entityUnionBuilder::object);

			return Optional.of(entityUnionBuilder.build());
		}
	}

	@Nonnull
	private static OpenApiEnum buildAssociatedDataScalarEnum() {
		return newEnum(buildScalarEnum())
			.name(ASSOCIATED_DATA_SCALAR_ENUM.name())
			.description(ASSOCIATED_DATA_SCALAR_ENUM.description())
			.item(DataTypeSerializer.serialize(ComplexDataObject.class))
			.build();
	}

	@Nonnull
	private Optional<OpenApiEnum> buildLocaleEnum() {
		if (buildingContext.getSupportedLocales().isEmpty()) {
			return Optional.empty();
		}

		final OpenApiEnum localeEnum = LOCALE_ENUM
			.to(new ObjectDescriptorToOpenApiEnumTransformer(
				buildingContext.getSupportedLocales().stream()
					.map(Locale::toLanguageTag)
					.collect(Collectors.toSet())
			))
			.format(OpenApiConstants.FORMAT_LOCALE)
			.build();

		return Optional.of(localeEnum);
	}

	@Nonnull
	private Optional<OpenApiEnum> buildCurrencyEnum() {
		if (buildingContext.getSupportedCurrencies().isEmpty()) {
			return Optional.empty();
		}

		final OpenApiEnum currencyEnum = CURRENCY_ENUM
			.to(new ObjectDescriptorToOpenApiEnumTransformer(
				buildingContext.getSupportedCurrencies().stream()
					.map(Currency::toString)
					.collect(Collectors.toSet())
			))
			.format(OpenApiConstants.FORMAT_CURRENCY)
			.build();

		return Optional.of(currencyEnum);
	}
}
