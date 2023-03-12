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
import io.evitadb.externalApi.rest.api.builder.PartialRestBuilder;
import io.evitadb.externalApi.rest.api.catalog.builder.CatalogRestBuildingContext;
import io.evitadb.externalApi.rest.api.catalog.dataApi.builder.CollectionDataApiRestBuildingContext;
import io.evitadb.externalApi.rest.api.catalog.dataApi.builder.DataApiEndpointBuilder;
import io.evitadb.externalApi.rest.api.catalog.dataApi.builder.DataMutationBuilder;
import io.evitadb.externalApi.rest.api.catalog.dataApi.builder.EntityObjectBuilder;
import io.evitadb.externalApi.rest.api.catalog.dataApi.builder.FullResponseObjectBuilder;
import io.evitadb.externalApi.rest.api.catalog.dataApi.builder.constraint.FilterBySchemaBuilder;
import io.evitadb.externalApi.rest.api.catalog.dataApi.builder.constraint.OpenApiConstraintSchemaBuildingContext;
import io.evitadb.externalApi.rest.api.catalog.dataApi.builder.constraint.OrderBySchemaBuilder;
import io.evitadb.externalApi.rest.api.catalog.dataApi.builder.constraint.RequireSchemaBuilder;
import io.evitadb.externalApi.rest.api.catalog.dataApi.dto.DataChunkType;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.CollectionDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.EntityUnion;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.FetchRequestDescriptor;
import io.evitadb.externalApi.rest.api.openApi.OpenApiEnum;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObject;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObjectUnionType;
import io.evitadb.externalApi.rest.api.openApi.OpenApiSimpleType;
import io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference;
import io.evitadb.externalApi.rest.api.resolver.serializer.DataTypeSerializer;

import javax.annotation.Nonnull;
import java.util.List;

import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.ENTITY_CURRENCY_ENUM;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.ENTITY_LOCALE_ENUM;
import static io.evitadb.externalApi.api.catalog.model.CatalogRootDescriptor.ASSOCIATED_DATA_SCALAR_ENUM;
import static io.evitadb.externalApi.rest.api.catalog.dataApi.builder.DataApiNamesConstructor.constructEntityListRequestBodyObjectName;
import static io.evitadb.externalApi.rest.api.catalog.dataApi.builder.DataApiNamesConstructor.constructEntityQueryRequestBodyObjectName;
import static io.evitadb.externalApi.rest.api.catalog.dataApi.builder.constraint.RequireSchemaBuilder.ALLOWED_CONSTRAINTS_FOR_LIST;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiEnum.enumFrom;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiEnum.newEnum;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiScalar.FORMAT_CURRENCY;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiScalar.FORMAT_LOCALE;

/**
 * Builds data API part of catalog's REST API. Building of whole REST API is handled by {@link io.evitadb.externalApi.rest.api.catalog.CatalogRestBuilder}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class CatalogDataApiRestBuilder extends PartialRestBuilder<CatalogRestBuildingContext> {

	@Nonnull private final OpenApiConstraintSchemaBuildingContext constraintBuildingContext;
	@Nonnull private final DataApiEndpointBuilder endpointBuilder;
	@Nonnull private final EntityObjectBuilder entityObjectBuilder;
	@Nonnull private final FullResponseObjectBuilder fullResponseObjectBuilder;
	@Nonnull private final DataMutationBuilder dataMutationBuilder;

	public CatalogDataApiRestBuilder(@Nonnull CatalogRestBuildingContext buildingContext) {
		super(buildingContext);
		this.constraintBuildingContext = new OpenApiConstraintSchemaBuildingContext(buildingContext);

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
			objectBuilderTransformer
		);
		this.dataMutationBuilder = new DataMutationBuilder(
			buildingContext,
			constraintBuildingContext,
			propertyBuilderTransformer,
			objectBuilderTransformer
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
	private CollectionDataApiRestBuildingContext setupForCollection(@Nonnull EntitySchemaContract entitySchema) {
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

		final CollectionDataApiRestBuildingContext collectionBuildingContext = new CollectionDataApiRestBuildingContext(
			buildingContext,
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

		buildingContext.registerLocalizedEntityObject(entityObjectBuilder.buildEntityObject(entitySchema, false));

		buildListRequestBodyObject(collectionBuildingContext, false);
		buildQueryRequestBodyObject(collectionBuildingContext, false);
		buildDeleteRequestBodyObject(collectionBuildingContext);

		buildingContext.registerType(fullResponseObjectBuilder.buildFullResponseObject(entitySchema, false));

		if(collectionBuildingContext.isLocalizedEntity()) {
			buildingContext.registerEntityObject(entityObjectBuilder.buildEntityObject(entitySchema, true));

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
	private OpenApiSimpleType buildFilterBySchema(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext,
	                                              boolean localized) {
		return new FilterBySchemaBuilder(
			constraintBuildingContext,
			collectionBuildingContext.getSchema().getName(),
			localized
		).build();
	}

	@Nonnull
	private OpenApiSimpleType buildOrderBySchema(@Nonnull CollectionDataApiRestBuildingContext collectionBuildingContext) {
		return new OrderBySchemaBuilder(
			constraintBuildingContext,
			collectionBuildingContext.getSchema().getName()
		).build();
	}

	@Nonnull
	private OpenApiSimpleType buildRequireSchemaForList(@Nonnull CollectionDataApiRestBuildingContext entitySchemaBuildingCtx) {
		return new RequireSchemaBuilder(
			constraintBuildingContext,
			entitySchemaBuildingCtx.getSchema().getName(),
			ALLOWED_CONSTRAINTS_FOR_LIST
		).build();
	}

	@Nonnull
	private OpenApiSimpleType buildRequireSchemaForQuery(@Nonnull CollectionDataApiRestBuildingContext entitySchemaBuildingCtx) {
		return new RequireSchemaBuilder(
			constraintBuildingContext,
			entitySchemaBuildingCtx.getSchema().getName()
		).build();
	}

	@Nonnull
	private OpenApiSimpleType buildRequireSchemaForDelete(@Nonnull CollectionDataApiRestBuildingContext entitySchemaBuildingCtx) {
		return new RequireSchemaBuilder(
			constraintBuildingContext,
			entitySchemaBuildingCtx.getSchema().getName(),
			RequireSchemaBuilder.ALLOWED_CONSTRAINTS_FOR_DELETE
		).build();
	}

	@Nonnull
	private OpenApiTypeReference buildListRequestBodyObject(@Nonnull CollectionDataApiRestBuildingContext entitySchemaBuildingContext,
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
	private OpenApiTypeReference buildQueryRequestBodyObject(@Nonnull CollectionDataApiRestBuildingContext entitySchemaBuildingContext,
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
	private OpenApiTypeReference buildDeleteRequestBodyObject(@Nonnull CollectionDataApiRestBuildingContext entitySchemaBuildingContext) {
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
	private static OpenApiEnum buildAssociatedDataScalarEnum() {
		return newEnum(buildScalarEnum())
			.name(ASSOCIATED_DATA_SCALAR_ENUM.name())
			.description(ASSOCIATED_DATA_SCALAR_ENUM.description())
			.item(DataTypeSerializer.serialize(ComplexDataObject.class))
			.build();
	}

}
