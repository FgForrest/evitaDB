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

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.DeleteEntitiesMutationHeaderDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.CatalogSchemaApiRootDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.EntitySchemaDescriptor;
import io.evitadb.externalApi.rest.api.catalog.ParamDescriptor;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.ObjectDescriptorToOpenApiObjectTransformer;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.PropertyDescriptorToOpenApiOperationPathParameterTransformer;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.PropertyDescriptorToOpenApiOperationQueryParameterTransformer;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.PropertyDescriptorToOpenApiPropertyTransformer;
import io.evitadb.externalApi.rest.api.catalog.model.CollectionDescriptor;
import io.evitadb.externalApi.rest.api.catalog.model.EntityUnion;
import io.evitadb.externalApi.rest.api.catalog.model.QueryRequestBodyDescriptor;
import io.evitadb.externalApi.rest.api.dto.OpenApiCatalogEndpoint;
import io.evitadb.externalApi.rest.api.dto.OpenApiCollectionEndpoint;
import io.evitadb.externalApi.rest.api.dto.OpenApiEndpointParameter;
import io.evitadb.externalApi.rest.api.dto.OpenApiSimpleType;
import io.evitadb.externalApi.rest.api.dto.OpenApiTypeReference;
import io.evitadb.externalApi.rest.dataType.DataTypesConverter;
import io.evitadb.externalApi.rest.io.handler.*;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.PathItem.HttpMethod;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.ARGUMENT_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.api.ExternalApiNamingConventions.URL_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.ENTITY_CURRENCY_ENUM;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.ENTITY_LOCALE_ENUM;
import static io.evitadb.externalApi.rest.api.catalog.builder.NamesConstructor.constructEntityListRequestBodyObjectName;
import static io.evitadb.externalApi.rest.api.catalog.builder.NamesConstructor.constructEntityObjectName;
import static io.evitadb.externalApi.rest.api.catalog.builder.NamesConstructor.constructEntityQueryRequestBodyObjectName;
import static io.evitadb.externalApi.rest.api.dto.OpenApiArray.arrayOf;
import static io.evitadb.externalApi.rest.api.dto.OpenApiCatalogEndpoint.newCatalogEndpoint;
import static io.evitadb.externalApi.rest.api.dto.OpenApiCollectionEndpoint.newCollectionEndpoint;
import static io.evitadb.externalApi.rest.api.dto.OpenApiEndpointParameter.newQueryParameter;
import static io.evitadb.externalApi.rest.api.dto.OpenApiNonNull.nonNull;
import static io.evitadb.externalApi.rest.api.dto.OpenApiTypeReference.typeRefTo;

/**
 * Creates OpenAPI {@link PathItem} for each endpoint and register path item into OpenAPI schema. Also register HTTP handlers
 * for requests processing.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class EndpointBuilder {

	@Nonnull private final PropertyDescriptorToOpenApiPropertyTransformer propertyBuilderTransformer;
	@Nonnull private final ObjectDescriptorToOpenApiObjectTransformer objectBuilderTransformer;
	@Nonnull private final PropertyDescriptorToOpenApiOperationPathParameterTransformer operationPathParameterBuilderTransformer;
	@Nonnull private final PropertyDescriptorToOpenApiOperationQueryParameterTransformer operationQueryParameterBuilderTransformer;

	@Nonnull
	public OpenApiCollectionEndpoint buildEntityGetEndpoint(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx,
	                                                        boolean localized,
	                                                        boolean withPkInPath) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();
		//this is correct as for localized URL (i.e. one locale) is non-localized entity sufficient
		// todo lho the localizedUrl logic is confusing
		final OpenApiTypeReference entityObject = typeRefTo(constructEntityObjectName(entitySchemaBuildingCtx.getSchema(), !localized));

		final List<OpenApiEndpointParameter> queryParameters = buildSingleEntityParameters(entitySchemaBuildingCtx, localized, withPkInPath);

		return newCollectionEndpoint(entitySchemaBuildingCtx.getCatalogCtx().getSchema(), entitySchema)
			.path(localized, p -> p
				.staticItem(CatalogDataApiRootDescriptor.ENTITY_GET.operation(URL_NAME_NAMING_CONVENTION))
				.paramItem(withPkInPath ? ParamDescriptor.PRIMARY_KEY.to(operationPathParameterBuilderTransformer) : null))
			.method(HttpMethod.GET)
			.description(CatalogDataApiRootDescriptor.ENTITY_GET.description(entitySchema.getName()))
			.deprecationNotice(entitySchema.getDeprecationNotice())
			.queryParameters(queryParameters)
			.successResponse(entityObject)
			.handler(GetEntityHandler::new)
			.build();
	}

	@Nonnull
	public OpenApiCollectionEndpoint buildEntityListEndpoint(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx,
	                                                         boolean localeInPath) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();
		//this is correct as for localized URL (i.e. one locale) is non-localized entity sufficient
		// todo lho the localizedUrl logic is confusing
		final OpenApiTypeReference entityObject = typeRefTo(constructEntityObjectName(entitySchemaBuildingCtx.getSchema(), !localeInPath));

		return newCollectionEndpoint(entitySchemaBuildingCtx.getCatalogCtx().getSchema(), entitySchema)
			.path(localeInPath, p -> p
				.staticItem(CatalogDataApiRootDescriptor.ENTITY_LIST.operation(URL_NAME_NAMING_CONVENTION)))
			.method(HttpMethod.POST)
			.description(CatalogDataApiRootDescriptor.ENTITY_LIST.description(entitySchema.getName()))
			.deprecationNotice(entitySchema.getDeprecationNotice())
			.requestBody(typeRefTo(constructEntityListRequestBodyObjectName(entitySchema, !localeInPath)))
			.successResponse(nonNull(entityObject))
			.handler(ListEntityHandler::new)
			.build();
	}

	@Nonnull
	public OpenApiCollectionEndpoint buildEntityQueryEndpoint(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx,
	                                                          boolean localeInPath) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final var responseObject = new FullResponseObjectBuilder(
			entitySchemaBuildingCtx,
			propertyBuilderTransformer,
			objectBuilderTransformer,
			localeInPath
		).buildFullResponseObject();

		return newCollectionEndpoint(entitySchemaBuildingCtx.getCatalogCtx().getSchema(), entitySchema)
			.path(localeInPath, p -> p
				.staticItem(CatalogDataApiRootDescriptor.ENTITY_QUERY.operation(URL_NAME_NAMING_CONVENTION)))
			.method(HttpMethod.POST)
			.description(CatalogDataApiRootDescriptor.ENTITY_QUERY.description(entitySchema.getName()))
			.deprecationNotice(entitySchema.getDeprecationNotice())
			.requestBody(typeRefTo(constructEntityQueryRequestBodyObjectName(entitySchema, !localeInPath)))
			.successResponse(nonNull(responseObject))
			.handler(QueryEntityHandler::new)
			.build();
	}

	@Nonnull
	public OpenApiCatalogEndpoint buildOpenApiSpecificationEndpoint(@Nonnull CatalogRestBuildingContext catalogCtx) {
		return newCatalogEndpoint(catalogCtx.getSchema())
			.path(p -> p) // directly at the catalog root
			.method(HttpMethod.GET)
			.description("OpenAPI Specification in YAML format.")
			.successResponse(nonNull(DataTypesConverter.getOpenApiScalar(String.class)))
			.handler(OpenApiSpecificationHandler::new)
			.build();
	}

	@Nonnull
	public OpenApiCatalogEndpoint buildCollectionsEndpoint(@Nonnull CatalogRestBuildingContext catalogCtx) {
		final OpenApiSimpleType collectionsObject = createCollectionsObject(catalogCtx);

		return newCatalogEndpoint(catalogCtx.getSchema())
			.path(p -> p
				.staticItem(CatalogDataApiRootDescriptor.COLLECTIONS.operation(URL_NAME_NAMING_CONVENTION)))
			.method(HttpMethod.GET)
			.description(CatalogDataApiRootDescriptor.COLLECTIONS.description())
			.queryParameter(ParamDescriptor.ENTITY_COUNT
				.to(operationQueryParameterBuilderTransformer)
				.type(DataTypesConverter.getOpenApiScalar(Boolean.class))
				.build())
			.successResponse(nonNull(collectionsObject))
			.handler(CollectionsHandler::new)
			.build();
	}

	@Nonnull
	public Optional<OpenApiCatalogEndpoint> buildUnknownEntityGetEndpoint(@Nonnull CatalogRestBuildingContext catalogCtx,
	                                                                      @Nonnull List<OpenApiTypeReference> entityObjects,
	                                                                      @Nonnull List<GlobalAttributeSchemaContract> globallyUniqueAttributes,
	                                                                      boolean localized) {
		if (entityObjects.isEmpty()) {
			return Optional.empty();
		}

		final List<OpenApiEndpointParameter> queryParameters = new LinkedList<>();
		queryParameters.addAll(
			globallyUniqueAttributes.stream()
				.map(arg -> newQueryParameter()
					.name(arg.getNameVariant(ARGUMENT_NAME_NAMING_CONVENTION))
					.description(arg.getDescription())
					.deprecationNotice(arg.getDeprecationNotice())
					.type(DataTypesConverter.getOpenApiScalar(arg.getPlainType()))
					.build())
				.toList()
		);
		queryParameters.addAll(buildFetchQueryParametersForUnknownEntity(localized));

		return Optional.of(
			newCatalogEndpoint(catalogCtx.getSchema())
				.path(localized, p -> p
					.staticItem(CatalogDataApiRootDescriptor.UNKNOWN_ENTITY_GET.classifier(URL_NAME_NAMING_CONVENTION))
					.staticItem(CatalogDataApiRootDescriptor.UNKNOWN_ENTITY_GET.operation(URL_NAME_NAMING_CONVENTION)))
				.method(HttpMethod.GET)
				.description(CatalogDataApiRootDescriptor.UNKNOWN_ENTITY_GET.description())
				.queryParameters(queryParameters)
				.successResponse(nonNull(typeRefTo(localized ? EntityUnion.THIS_LOCALIZED.name() : EntityUnion.THIS.name())))
				.handler(UnknownEntityHandler::new)
				.build()
		);
	}

	@Nonnull
	public Optional<OpenApiCatalogEndpoint> buildUnknownEntityListEndpoint(@Nonnull CatalogRestBuildingContext catalogCtx,
	                                                                       @Nonnull List<OpenApiTypeReference> entityObjects,
	                                                                       @Nonnull List<GlobalAttributeSchemaContract> globallyUniqueAttributes,
	                                                                       boolean localized) {
		if (entityObjects.isEmpty()) {
			return Optional.empty();
		}

		final List<OpenApiEndpointParameter> queryParameters = new LinkedList<>();
		queryParameters.add(ParamDescriptor.LIMIT
			.to(operationQueryParameterBuilderTransformer)
			.build());
		queryParameters.addAll(
			globallyUniqueAttributes.stream()
				.map(arg -> newQueryParameter()
					.name(arg.getNameVariant(ARGUMENT_NAME_NAMING_CONVENTION))
					.description(arg.getDescription())
					.deprecationNotice(arg.getDeprecationNotice())
					.type(arrayOf(DataTypesConverter.getOpenApiScalar(arg.getPlainType())))
					.build())
				.toList()
		);
		queryParameters.addAll(buildFetchQueryParametersForUnknownEntity(localized));

		return Optional.of(
			newCatalogEndpoint(catalogCtx.getSchema())
				.path(localized, p -> p
					.staticItem(CatalogDataApiRootDescriptor.UNKNOWN_ENTITY_LIST.classifier(URL_NAME_NAMING_CONVENTION))
					.staticItem(CatalogDataApiRootDescriptor.UNKNOWN_ENTITY_LIST.operation(URL_NAME_NAMING_CONVENTION)))
				.method(HttpMethod.GET)
				.description(CatalogDataApiRootDescriptor.UNKNOWN_ENTITY_LIST.description())
				.queryParameters(queryParameters)
				.successResponse(nonNull(arrayOf(typeRefTo(localized ? EntityUnion.THIS_LOCALIZED.name() : EntityUnion.THIS.name()))))
				.handler(UnknownEntityListHandler::new)
				.build()
		);
	}

	@Nonnull
	public OpenApiCollectionEndpoint buildEntityUpsertEndpoint(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx,
	                                                           @Nonnull OpenApiTypeReference requestObject,
	                                                           boolean withPrimaryKeyInPath) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final OpenApiTypeReference entityObject = typeRefTo(constructEntityObjectName(entitySchema, false));

		return newCollectionEndpoint(entitySchemaBuildingCtx.getCatalogCtx().getSchema(), entitySchema)
			.path(p -> p // directly at the collection root
				.paramItem(withPrimaryKeyInPath ? ParamDescriptor.PRIMARY_KEY.to(operationPathParameterBuilderTransformer) : null))
			.method(withPrimaryKeyInPath ? HttpMethod.PUT : HttpMethod.POST)
			.description(CatalogDataApiRootDescriptor.ENTITY_UPSERT.description(entitySchema.getName()))
			.deprecationNotice(entitySchema.getDeprecationNotice())
			.requestBody(requestObject)
			.successResponse(nonNull(entityObject))
			.handler(ctx -> new UpsertEntityHandler(ctx, withPrimaryKeyInPath))
			.build();
	}

	@Nonnull
	protected OpenApiCollectionEndpoint buildEntitiesDeleteByQueryEndpoint(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final OpenApiTypeReference entityObject = typeRefTo(constructEntityObjectName(entitySchema, false));

		return newCollectionEndpoint(entitySchemaBuildingCtx.getCatalogCtx().getSchema(), entitySchema)
			.path(p -> p) // directly at the collection root
			.method(HttpMethod.DELETE)
			.description(CatalogDataApiRootDescriptor.ENTITY_DELETE.description(entitySchema.getName()))
			.deprecationNotice(entitySchema.getDeprecationNotice())
			.requestBody(typeRefTo(QueryRequestBodyDescriptor.THIS_DELETE.name(entitySchema)))
			.successResponse(nonNull(entityObject))
			.handler(DeleteEntityByQueryHandler::new)
			.build();
	}

	@Nonnull
	public OpenApiCollectionEndpoint buildEntityDeleteEndpoint(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final OpenApiTypeReference entityObject = typeRefTo(constructEntityObjectName(entitySchema, false));
		final List<OpenApiEndpointParameter> queryParameters = buildSingleEntityFetchParameters(entitySchema);

		return newCollectionEndpoint(entitySchemaBuildingCtx.getCatalogCtx().getSchema(), entitySchema)
			.path(p -> p // directly at the collection root
				.paramItem(DeleteEntitiesMutationHeaderDescriptor.PRIMARY_KEY
					.to(operationPathParameterBuilderTransformer)
					.type(DataTypesConverter.getOpenApiScalar(Integer.class, true))))
			.method(HttpMethod.DELETE)
			.description(CatalogDataApiRootDescriptor.ENTITY_DELETE.description(entitySchema.getName()))
			.deprecationNotice(entitySchema.getDeprecationNotice())
			.queryParameters(queryParameters)
			.successResponse(nonNull(entityObject))
			.handler(DeleteEntityHandler::new)
			.build();
	}

	@Nonnull
	public OpenApiCollectionEndpoint buildEntitySchemaGetEndpoint(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();
		final OpenApiTypeReference entitySchemaObject = typeRefTo(EntitySchemaDescriptor.THIS_SPECIFIC.name(entitySchema));

		return newCollectionEndpoint(entitySchemaBuildingCtx.getCatalogCtx().getSchema(), entitySchema)
			.path(p -> p
				.staticItem(CatalogSchemaApiRootDescriptor.GET_ENTITY_SCHEMA.operation(URL_NAME_NAMING_CONVENTION)))
			.method(HttpMethod.GET)
			.description(CatalogSchemaApiRootDescriptor.GET_ENTITY_SCHEMA.description(entitySchema.getName()))
			.deprecationNotice(entitySchema.getDeprecationNotice())
			.successResponse(nonNull(entitySchemaObject))
			.handler(GetEntitySchemaHandler::new)
			.build();
	}

	@Nonnull
	private List<OpenApiEndpointParameter> buildFetchQueryParametersForUnknownEntity(boolean localized) {
		final List<OpenApiEndpointParameter> queryParameters = new ArrayList<>(8);

		//build fetch params
		if (!localized) {
			queryParameters.add(ParamDescriptor.LOCALE.to(operationQueryParameterBuilderTransformer).build());
		}
		queryParameters.add(ParamDescriptor.DATA_IN_LOCALES.to(operationQueryParameterBuilderTransformer).build());
		queryParameters.add(ParamDescriptor.FETCH_ALL.to(operationQueryParameterBuilderTransformer).build());
		queryParameters.add(ParamDescriptor.BODY_FETCH.to(operationQueryParameterBuilderTransformer).build());
		queryParameters.add(ParamDescriptor.ASSOCIATED_DATA_CONTENT_ALL.to(operationQueryParameterBuilderTransformer).build());
		queryParameters.add(ParamDescriptor.ATTRIBUTE_CONTENT_ALL.to(operationQueryParameterBuilderTransformer).build());
		queryParameters.add(ParamDescriptor.PRICE_CONTENT.to(operationQueryParameterBuilderTransformer).build());
		queryParameters.add(ParamDescriptor.REFERENCE_CONTENT_ALL.to(operationQueryParameterBuilderTransformer).build());

		return queryParameters;
	}

	@Nonnull
	private List<OpenApiEndpointParameter> buildSingleEntityParameters(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx,
	                                                                   boolean localeInPath,
	                                                                   boolean withPkInPath) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final List<OpenApiEndpointParameter> parameters = new LinkedList<>();

		if (!withPkInPath) {
			parameters.add(ParamDescriptor.PRIMARY_KEY.to(operationQueryParameterBuilderTransformer).build());
		}

		// build locale argument
		if (!entitySchema.getLocales().isEmpty()) {
			final OpenApiTypeReference localeEnum = typeRefTo(ENTITY_LOCALE_ENUM.name(entitySchemaBuildingCtx.getSchema()));

			if (!localeInPath) {
				final OpenApiEndpointParameter dataInLocalesParameter = ParamDescriptor.DATA_IN_LOCALES
					.to(operationQueryParameterBuilderTransformer)
					.type(arrayOf(localeEnum))
					.build();
				parameters.add(dataInLocalesParameter);

				final OpenApiEndpointParameter localeParameter = ParamDescriptor.LOCALE
					.to(operationQueryParameterBuilderTransformer)
					.type(localeEnum)
					.build();
				parameters.add(localeParameter);
			}
		}

		// build price arguments
		if (!entitySchema.getCurrencies().isEmpty()) {
			parameters.add(ParamDescriptor.PRICE_IN_CURRENCY
				.to(operationQueryParameterBuilderTransformer)
				.type(typeRefTo(ENTITY_CURRENCY_ENUM.name(entitySchemaBuildingCtx.getSchema())))
				.build());
			parameters.add(ParamDescriptor.PRICE_IN_PRICE_LISTS.to(operationQueryParameterBuilderTransformer).build());
			parameters.add(ParamDescriptor.PRICE_VALID_IN.to(operationQueryParameterBuilderTransformer).build());
			parameters.add(ParamDescriptor.PRICE_VALID_NOW.to(operationQueryParameterBuilderTransformer).build());
		}

		// build unique attribute filter arguments
		parameters.addAll(entitySchema.getAttributes()
			.values()
			.stream()
			.filter(AttributeSchemaContract::isUnique)
			.map(as -> newQueryParameter()
				.name(as.getNameVariant(ARGUMENT_NAME_NAMING_CONVENTION))
				.description(as.getDescription())
				.deprecationNotice(as.getDeprecationNotice())
				.type(DataTypesConverter.getOpenApiScalar(as.getPlainType()))
				.build())
			.toList());


		//build fetch params
		parameters.addAll(buildSingleEntityFetchParameters(entitySchema));

		return parameters;
	}

	private List<OpenApiEndpointParameter> buildSingleEntityFetchParameters(@Nonnull EntitySchemaContract entitySchema) {
		final List<OpenApiEndpointParameter> parameters = new LinkedList<>();
		parameters.add(ParamDescriptor.FETCH_ALL.to(operationQueryParameterBuilderTransformer).build());
		parameters.add(ParamDescriptor.BODY_FETCH.to(operationQueryParameterBuilderTransformer).build());
		if (!entitySchema.getAssociatedData().isEmpty()) {
			parameters.add(ParamDescriptor.ASSOCIATED_DATA_CONTENT.to(operationQueryParameterBuilderTransformer).build());
			parameters.add(ParamDescriptor.ASSOCIATED_DATA_CONTENT_ALL.to(operationQueryParameterBuilderTransformer).build());
		}
		if (!entitySchema.getAttributes().isEmpty()) {
			parameters.add(ParamDescriptor.ATTRIBUTE_CONTENT.to(operationQueryParameterBuilderTransformer).build());
			parameters.add(ParamDescriptor.ATTRIBUTE_CONTENT_ALL.to(operationQueryParameterBuilderTransformer).build());
		}
		if (!entitySchema.getCurrencies().isEmpty()) {
			parameters.add(ParamDescriptor.PRICE_CONTENT.to(operationQueryParameterBuilderTransformer).build());
		}
		if (!entitySchema.getReferences().isEmpty()) {
			parameters.add(ParamDescriptor.REFERENCE_CONTENT.to(operationQueryParameterBuilderTransformer).build());
			parameters.add(ParamDescriptor.REFERENCE_CONTENT_ALL.to(operationQueryParameterBuilderTransformer).build());
		}

		return parameters;
	}

	@Nonnull
	private OpenApiSimpleType createCollectionsObject(@Nonnull CatalogRestBuildingContext catalogCtx) {
		final OpenApiTypeReference collectionObject = catalogCtx.registerType(CollectionDescriptor.THIS.to(objectBuilderTransformer).build());
		return arrayOf(collectionObject);
	}
}
