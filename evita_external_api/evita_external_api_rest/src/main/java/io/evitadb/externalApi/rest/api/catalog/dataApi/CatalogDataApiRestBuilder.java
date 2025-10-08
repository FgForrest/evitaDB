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
import io.evitadb.externalApi.rest.api.catalog.dataApi.builder.constraint.HeadConstraintSchemaBuilder;
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
	@Nonnull private final HeadConstraintSchemaBuilder headConstraintSchemaBuilder;
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

		this.headConstraintSchemaBuilder = new HeadConstraintSchemaBuilder(this.constraintBuildingContext);
		this.filterConstraintSchemaBuilder = new FilterConstraintSchemaBuilder(this.constraintBuildingContext, false);
		this.localizedFilterConstraintSchemaBuilder = new FilterConstraintSchemaBuilder(this.constraintBuildingContext, true);
		this.orderConstraintSchemaBuilder = new OrderConstraintSchemaBuilder(
			this.constraintBuildingContext,
			new AtomicReference<>(this.filterConstraintSchemaBuilder)
		);
		this.listRequireConstraintSchemaBuilder = new RequireConstraintSchemaBuilder(
			this.constraintBuildingContext,
			ALLOWED_CONSTRAINTS_FOR_LIST,
			new AtomicReference<>(this.filterConstraintSchemaBuilder),
			new AtomicReference<>(this.orderConstraintSchemaBuilder)
		);
		this.localizedListRequireConstraintSchemaBuilder = new RequireConstraintSchemaBuilder(
			this.constraintBuildingContext,
			ALLOWED_CONSTRAINTS_FOR_LIST,
			new AtomicReference<>(this.localizedFilterConstraintSchemaBuilder),
			new AtomicReference<>(this.orderConstraintSchemaBuilder)
		);
		this.queryRequireConstraintSchemaBuilder = new RequireConstraintSchemaBuilder(
			this.constraintBuildingContext,
			new AtomicReference<>(this.filterConstraintSchemaBuilder),
			new AtomicReference<>(this.orderConstraintSchemaBuilder)
		);
		this.localizedQueryRequireConstraintSchemaBuilder = new RequireConstraintSchemaBuilder(
			this.constraintBuildingContext,
			new AtomicReference<>(this.localizedFilterConstraintSchemaBuilder),
			new AtomicReference<>(this.orderConstraintSchemaBuilder)
		);
		this.upsertRequireConstraintSchemaBuilder = new RequireConstraintSchemaBuilder(
			this.constraintBuildingContext,
			RequireConstraintSchemaBuilder.ALLOWED_CONSTRAINTS_FOR_UPSERT,
			new AtomicReference<>(this.filterConstraintSchemaBuilder),
			new AtomicReference<>(this.orderConstraintSchemaBuilder)
		);
		this.deleteRequireConstraintSchemaBuilder = new RequireConstraintSchemaBuilder(
			this.constraintBuildingContext,
			ALLOWED_CONSTRAINTS_FOR_DELETE,
			new AtomicReference<>(this.filterConstraintSchemaBuilder),
			new AtomicReference<>(this.orderConstraintSchemaBuilder)
		);

		this.endpointBuilder = new DataApiEndpointBuilder(
			buildingContext,
			this.operationPathParameterBuilderTransformer,
			this.operationQueryParameterBuilderTransformer
		);
		this.entityObjectBuilder = new EntityObjectBuilder(
			buildingContext,
			this.propertyBuilderTransformer,
			this.objectBuilderTransformer,
			this.dictionaryBuilderTransformer
		);
		this.fullResponseObjectBuilder = new FullResponseObjectBuilder(
			buildingContext,
			this.propertyBuilderTransformer,
			this.objectBuilderTransformer,
			this.unionBuilderTransformer,
			this.dictionaryBuilderTransformer
		);
		this.dataMutationBuilder = new DataMutationBuilder(
			buildingContext,
			this.propertyBuilderTransformer,
			this.objectBuilderTransformer,
			this.upsertRequireConstraintSchemaBuilder
		);
	}

	@Override
	public void build() {
		buildCommonTypes();
		buildEndpoints();

		// register gathered custom constraint schema types
		this.constraintBuildingContext.getBuiltTypes().forEach(this.buildingContext::registerType);
	}

	private void buildCommonTypes() {
		buildLocaleEnum().ifPresent(this.buildingContext::registerCustomEnumIfAbsent);
		buildCurrencyEnum().ifPresent(this.buildingContext::registerCustomEnumIfAbsent);

		this.buildingContext.registerType(buildAssociatedDataScalarEnum());
		this.buildingContext.registerType(enumFrom(DataChunkType.class));

		this.buildingContext.registerType(CollectionDescriptor.THIS.to(this.objectBuilderTransformer).build());

		this.entityObjectBuilder.buildCommonTypes();
		this.fullResponseObjectBuilder.buildCommonTypes();
		this.dataMutationBuilder.buildCommonTypes();
	}

	private void buildEndpoints() {
		this.buildingContext.registerEndpoint(this.endpointBuilder.buildCollectionsEndpoint());

		this.buildingContext.getEntitySchemas().forEach(entitySchema -> {
			final CollectionDataApiRestBuildingContext collectionBuildingContext = setupForCollection(entitySchema);

			this.buildingContext.registerEndpoint(this.endpointBuilder.buildGetEntityEndpoint(entitySchema, false, false));
			this.buildingContext.registerEndpoint(this.endpointBuilder.buildGetEntityEndpoint(entitySchema, false, true));

			this.buildingContext.registerEndpoint(this.endpointBuilder.buildListEntityEndpoint(entitySchema, false));
			this.buildingContext.registerEndpoint(this.endpointBuilder.buildQueryEntityEndpoint(entitySchema, false));

			if(collectionBuildingContext.isLocalizedEntity()) {
				this.buildingContext.registerEndpoint(this.endpointBuilder.buildGetEntityEndpoint(entitySchema, true, false));
				this.buildingContext.registerEndpoint(this.endpointBuilder.buildGetEntityEndpoint(entitySchema, true, true));

				this.buildingContext.registerEndpoint(this.endpointBuilder.buildListEntityEndpoint(entitySchema, true));
				this.buildingContext.registerEndpoint(this.endpointBuilder.buildQueryEntityEndpoint(entitySchema, true));
			}

			this.buildingContext.registerEndpoint(this.endpointBuilder.buildUpsertEntityEndpoint(entitySchema, true));
			if (entitySchema.isWithGeneratedPrimaryKey()) {
				this.buildingContext.registerEndpoint(this.endpointBuilder.buildUpsertEntityEndpoint(entitySchema, false));
			}

			this.buildingContext.registerEndpoint(this.endpointBuilder.buildDeleteEntityEndpoint(entitySchema));
			this.buildingContext.registerEndpoint(this.endpointBuilder.buildDeleteEntitiesByQueryEndpoint(entitySchema));
		});

		buildEntityUnion(false).ifPresent(this.buildingContext::registerType);
		buildEntityUnion(true).ifPresent(this.buildingContext::registerType);

		final List<GlobalAttributeSchemaContract> globallyUniqueAttributes = this.buildingContext.getSchema()
			.getAttributes()
			.values()
			.stream()
			.filter(GlobalAttributeSchemaContract::isUniqueGloballyInAnyScope)
			.toList();
		if(!globallyUniqueAttributes.isEmpty()) {
			this.endpointBuilder.buildGetUnknownEntityEndpoint(globallyUniqueAttributes, false)
				.ifPresent(this.buildingContext::registerEndpoint);
			this.endpointBuilder.buildListUnknownEntityEndpoint(globallyUniqueAttributes, false)
				.ifPresent(this.buildingContext::registerEndpoint);

			if (!this.buildingContext.getLocalizedEntityObjects().isEmpty()) {
				this.endpointBuilder.buildGetUnknownEntityEndpoint(globallyUniqueAttributes, true)
					.ifPresent(this.buildingContext::registerEndpoint);
				this.endpointBuilder.buildListUnknownEntityEndpoint(globallyUniqueAttributes, true)
					.ifPresent(this.buildingContext::registerEndpoint);
			}
		}
	}

	/**
	 * Prepare common data for specific collection schema building.
	 */
	@Nonnull
	private CollectionDataApiRestBuildingContext setupForCollection(@Nonnull EntitySchemaContract entitySchema) {
		final CollectionDataApiRestBuildingContext collectionBuildingContext = new CollectionDataApiRestBuildingContext(
			this.buildingContext,
			entitySchema
		);

		// build header schema
		final OpenApiSimpleType headerObject = this.headConstraintSchemaBuilder.build(entitySchema.getName());
		collectionBuildingContext.setHeaderObject(headerObject);

		// build filter by schema
		final OpenApiSimpleType filterByObject = this.filterConstraintSchemaBuilder.build(entitySchema.getName());
		collectionBuildingContext.setFilterByObject(filterByObject);
		final OpenApiSimpleType localizedFilterByObject = this.localizedFilterConstraintSchemaBuilder.build(entitySchema.getName());
		collectionBuildingContext.setLocalizedFilterByObject(localizedFilterByObject);

		// build order by schema
		final OpenApiSimpleType orderByObject = this.orderConstraintSchemaBuilder.build(entitySchema.getName());
		collectionBuildingContext.setOrderByObject(orderByObject);

		// build require schema
		final OpenApiSimpleType requireForListObject = this.listRequireConstraintSchemaBuilder.build(entitySchema.getName());
		collectionBuildingContext.setRequireForListObject(requireForListObject);
		final OpenApiSimpleType localizedRequireForListObject = this.localizedListRequireConstraintSchemaBuilder.build(entitySchema.getName());
		collectionBuildingContext.setLocalizedRequireForListObject(localizedRequireForListObject);
		final OpenApiSimpleType requireForQueryObject = this.queryRequireConstraintSchemaBuilder.build(entitySchema.getName());
		collectionBuildingContext.setRequireForQueryObject(requireForQueryObject);
		final OpenApiSimpleType localizedRequireForQueryObject = this.localizedQueryRequireConstraintSchemaBuilder.build(entitySchema.getName());
		collectionBuildingContext.setLocalizedRequireForQueryObject(localizedRequireForQueryObject);
		final OpenApiSimpleType requireForDeleteObject = this.deleteRequireConstraintSchemaBuilder.build(entitySchema.getName());
		collectionBuildingContext.setRequireForDeleteObject(requireForDeleteObject);

		this.buildingContext.registerEntityObject(this.entityObjectBuilder.buildEntityObject(entitySchema, false));

		buildListRequestBodyObject(collectionBuildingContext, false);
		buildQueryRequestBodyObject(collectionBuildingContext, false);
		buildDeleteRequestBodyObject(collectionBuildingContext);

		this.buildingContext.registerType(this.fullResponseObjectBuilder.buildFullResponseObject(entitySchema, false));

		if(collectionBuildingContext.isLocalizedEntity()) {
			this.buildingContext.registerLocalizedEntityObject(this.entityObjectBuilder.buildEntityObject(entitySchema, true));

			buildListRequestBodyObject(collectionBuildingContext, true);
			buildQueryRequestBodyObject(collectionBuildingContext, true);

			this.buildingContext.registerType(this.fullResponseObjectBuilder.buildFullResponseObject(entitySchema, true));
		} else {
			/* When entity has no localized data than it makes no sense to create endpoints for this entity with Locale
			 * in URL. But this entity may be referenced by other entities when localized URL is used. For such cases
			 * is also appropriate entity schema created.*/
			collectionBuildingContext.getCatalogCtx().registerType(this.entityObjectBuilder.buildEntityObject(entitySchema, true));
		}

		this.dataMutationBuilder.buildEntityUpsertRequestObject(entitySchema);

		return collectionBuildingContext;
	}

	@Nonnull
	private OpenApiTypeReference buildListRequestBodyObject(@Nonnull CollectionDataApiRestBuildingContext entitySchemaBuildingContext,
	                                                        boolean localized) {
		final OpenApiObject.Builder objectBuilder = FetchEntityRequestDescriptor.THIS_LIST
			.to(this.objectBuilderTransformer)
			.name(constructEntityListRequestBodyObjectName(entitySchemaBuildingContext.getSchema(), localized))
			.property(FetchEntityRequestDescriptor.HEAD.to(this.propertyBuilderTransformer).type(entitySchemaBuildingContext.getHeaderObject()))
			.property(FetchEntityRequestDescriptor.FILTER_BY.to(this.propertyBuilderTransformer).type(localized ? entitySchemaBuildingContext.getLocalizedFilterByObject() : entitySchemaBuildingContext.getFilterByObject()))
			.property(FetchEntityRequestDescriptor.ORDER_BY.to(this.propertyBuilderTransformer).type(entitySchemaBuildingContext.getOrderByObject()))
			.property(FetchEntityRequestDescriptor.REQUIRE.to(this.propertyBuilderTransformer).type(localized ? entitySchemaBuildingContext.getLocalizedRequireForListObject() : entitySchemaBuildingContext.getRequireForListObject()));

		return entitySchemaBuildingContext.getCatalogCtx().registerType(objectBuilder.build());
	}

	@Nonnull
	private OpenApiTypeReference buildQueryRequestBodyObject(@Nonnull CollectionDataApiRestBuildingContext entitySchemaBuildingContext,
	                                                         boolean localized) {
		final OpenApiObject.Builder objectBuilder = FetchEntityRequestDescriptor.THIS_QUERY
			.to(this.objectBuilderTransformer)
			.name(constructEntityQueryRequestBodyObjectName(entitySchemaBuildingContext.getSchema(), localized))
			.property(FetchEntityRequestDescriptor.HEAD.to(this.propertyBuilderTransformer).type(entitySchemaBuildingContext.getHeaderObject()))
			.property(FetchEntityRequestDescriptor.FILTER_BY.to(this.propertyBuilderTransformer).type(localized ? entitySchemaBuildingContext.getLocalizedFilterByObject() : entitySchemaBuildingContext.getFilterByObject()))
			.property(FetchEntityRequestDescriptor.ORDER_BY.to(this.propertyBuilderTransformer).type(entitySchemaBuildingContext.getOrderByObject()))
			.property(FetchEntityRequestDescriptor.REQUIRE.to(this.propertyBuilderTransformer).type(localized ? entitySchemaBuildingContext.getLocalizedRequireForQueryObject() : entitySchemaBuildingContext.getRequireForQueryObject()));

		return entitySchemaBuildingContext.getCatalogCtx().registerType(objectBuilder.build());
	}

	@Nonnull
	private OpenApiTypeReference buildDeleteRequestBodyObject(@Nonnull CollectionDataApiRestBuildingContext entitySchemaBuildingContext) {
		final OpenApiObject.Builder objectBuilder = FetchEntityRequestDescriptor.THIS_DELETE
			.to(this.objectBuilderTransformer)
			.name(FetchEntityRequestDescriptor.THIS_DELETE.name(entitySchemaBuildingContext.getSchema()))
			.property(FetchEntityRequestDescriptor.HEAD.to(this.propertyBuilderTransformer).type(entitySchemaBuildingContext.getHeaderObject()))
			.property(FetchEntityRequestDescriptor.FILTER_BY.to(this.propertyBuilderTransformer).type(entitySchemaBuildingContext.getFilterByObject()))
			.property(FetchEntityRequestDescriptor.ORDER_BY.to(this.propertyBuilderTransformer).type(entitySchemaBuildingContext.getOrderByObject()))
			.property(FetchEntityRequestDescriptor.REQUIRE.to(this.propertyBuilderTransformer).type(entitySchemaBuildingContext.getRequireForDeleteObject()));

		return entitySchemaBuildingContext.getCatalogCtx().registerType(objectBuilder.build());
	}

	@Nonnull
	private Optional<OpenApiUnion> buildEntityUnion(boolean localized) {
		if (localized) {
			final List<OpenApiTypeReference> entityObjects = this.buildingContext.getLocalizedEntityObjects();
			if (entityObjects.isEmpty()) {
				return Optional.empty();
			}

			final OpenApiUnion.Builder localizedEntityUnionBuilder = EntityUnion.THIS_LOCALIZED
				.to(this.unionBuilderTransformer)
				.type(OpenApiObjectUnionType.ONE_OF)
				.discriminator(EntityDescriptor.TYPE.name());
			entityObjects.forEach(localizedEntityUnionBuilder::object);

			return Optional.of(localizedEntityUnionBuilder.build());
		} else {
			final List<OpenApiTypeReference> entityObjects = this.buildingContext.getEntityObjects();
			if (entityObjects.isEmpty()) {
				return Optional.empty();
			}
			final OpenApiUnion.Builder entityUnionBuilder = EntityUnion.THIS
				.to(this.unionBuilderTransformer)
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
		if (this.buildingContext.getSupportedLocales().isEmpty()) {
			return Optional.empty();
		}

		final OpenApiEnum localeEnum = LOCALE_ENUM
			.to(new ObjectDescriptorToOpenApiEnumTransformer(
				this.buildingContext.getSupportedLocales().stream()
					.map(Locale::toLanguageTag)
					.collect(Collectors.toSet())
			))
			.format(OpenApiConstants.FORMAT_LOCALE)
			.build();

		return Optional.of(localeEnum);
	}

	@Nonnull
	private Optional<OpenApiEnum> buildCurrencyEnum() {
		if (this.buildingContext.getSupportedCurrencies().isEmpty()) {
			return Optional.empty();
		}

		final OpenApiEnum currencyEnum = CURRENCY_ENUM
			.to(new ObjectDescriptorToOpenApiEnumTransformer(
				this.buildingContext.getSupportedCurrencies().stream()
					.map(Currency::toString)
					.collect(Collectors.toSet())
			))
			.format(OpenApiConstants.FORMAT_CURRENCY)
			.build();

		return Optional.of(currencyEnum);
	}
}
