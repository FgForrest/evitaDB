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

package io.evitadb.externalApi.rest.api.catalog.dataApi.builder;

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor;
import io.evitadb.externalApi.rest.api.catalog.builder.CatalogRestBuildingContext;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.CollectionDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.EntityUnion;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.EntityUpsertRequestDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.FetchRequestDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.ParamDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint.CollectionsHandler;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint.DeleteEntitiesByQueryHandler;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint.DeleteEntityHandler;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint.GetEntityHandler;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint.ListEntitiesHandler;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint.QueryEntitiesHandler;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint.UnknownEntityHandler;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint.UnknownEntityListHandler;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint.UpsertEntityHandler;
import io.evitadb.externalApi.rest.api.dataType.DataTypesConverter;
import io.evitadb.externalApi.rest.api.model.PropertyDescriptorToOpenApiOperationPathParameterTransformer;
import io.evitadb.externalApi.rest.api.model.PropertyDescriptorToOpenApiOperationQueryParameterTransformer;
import io.evitadb.externalApi.rest.api.openApi.OpenApiCatalogEndpoint;
import io.evitadb.externalApi.rest.api.openApi.OpenApiCollectionEndpoint;
import io.evitadb.externalApi.rest.api.openApi.OpenApiEndpointParameter;
import io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference;
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
import static io.evitadb.externalApi.rest.api.catalog.dataApi.builder.DataApiNamesConstructor.constructEntityFullResponseObjectName;
import static io.evitadb.externalApi.rest.api.catalog.dataApi.builder.DataApiNamesConstructor.constructEntityListRequestBodyObjectName;
import static io.evitadb.externalApi.rest.api.catalog.dataApi.builder.DataApiNamesConstructor.constructEntityObjectName;
import static io.evitadb.externalApi.rest.api.catalog.dataApi.builder.DataApiNamesConstructor.constructEntityQueryRequestBodyObjectName;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiArray.arrayOf;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiCatalogEndpoint.newCatalogEndpoint;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiCollectionEndpoint.newCollectionEndpoint;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiEndpointParameter.newQueryParameter;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiNonNull.nonNull;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;

/**
 * Creates OpenAPI {@link io.evitadb.externalApi.rest.api.openApi.OpenApiEndpoint} for each data API endpoint.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class DataApiEndpointBuilder {

	@Nonnull private final CatalogRestBuildingContext buildingContext;
	@Nonnull private final PropertyDescriptorToOpenApiOperationPathParameterTransformer operationPathParameterBuilderTransformer;
	@Nonnull private final PropertyDescriptorToOpenApiOperationQueryParameterTransformer operationQueryParameterBuilderTransformer;

	@Nonnull
	public OpenApiCollectionEndpoint buildGetEntityEndpoint(@Nonnull EntitySchemaContract entitySchema,
	                                                        boolean localized,
	                                                        boolean withPkInPath) {
		return newCollectionEndpoint(buildingContext.getSchema(), entitySchema)
			.path(localized, p -> p
				.staticItem(CatalogDataApiRootDescriptor.GET_ENTITY.operation(URL_NAME_NAMING_CONVENTION))
				.paramItem(withPkInPath ? ParamDescriptor.PRIMARY_KEY.to(operationPathParameterBuilderTransformer) : null))
			.method(HttpMethod.GET)
			.description(CatalogDataApiRootDescriptor.GET_ENTITY.description(entitySchema.getName()))
			.deprecationNotice(entitySchema.getDeprecationNotice())
			.queryParameters(buildGetEntityQueryParameters(entitySchema, localized, withPkInPath))
			.successResponse(typeRefTo(constructEntityObjectName(entitySchema, localized)))
			.handler(GetEntityHandler::new)
			.build();
	}

	@Nonnull
	public OpenApiCollectionEndpoint buildListEntityEndpoint(@Nonnull EntitySchemaContract entitySchema,
	                                                         boolean localized) {
		return newCollectionEndpoint(buildingContext.getSchema(), entitySchema)
			.path(localized, p -> p
				.staticItem(CatalogDataApiRootDescriptor.LIST_ENTITY.operation(URL_NAME_NAMING_CONVENTION)))
			.method(HttpMethod.POST)
			.description(CatalogDataApiRootDescriptor.LIST_ENTITY.description(entitySchema.getName()))
			.deprecationNotice(entitySchema.getDeprecationNotice())
			.requestBody(typeRefTo(constructEntityListRequestBodyObjectName(entitySchema, localized)))
			.successResponse(nonNull(typeRefTo(constructEntityObjectName(entitySchema, localized))))
			.handler(ListEntitiesHandler::new)
			.build();
	}

	@Nonnull
	public OpenApiCollectionEndpoint buildQueryEntityEndpoint(@Nonnull EntitySchemaContract entitySchema,
	                                                          boolean localized) {
		return newCollectionEndpoint(buildingContext.getSchema(), entitySchema)
			.path(localized, p -> p
				.staticItem(CatalogDataApiRootDescriptor.QUERY_ENTITY.operation(URL_NAME_NAMING_CONVENTION)))
			.method(HttpMethod.POST)
			.description(CatalogDataApiRootDescriptor.QUERY_ENTITY.description(entitySchema.getName()))
			.deprecationNotice(entitySchema.getDeprecationNotice())
			.requestBody(typeRefTo(constructEntityQueryRequestBodyObjectName(entitySchema, localized)))
			.successResponse(nonNull(typeRefTo(constructEntityFullResponseObjectName(entitySchema, localized))))
			.handler(QueryEntitiesHandler::new)
			.build();
	}

	@Nonnull
	public OpenApiCatalogEndpoint buildCollectionsEndpoint(@Nonnull CatalogRestBuildingContext buildingContext) {
		return newCatalogEndpoint(buildingContext.getSchema())
			.path(p -> p
				.staticItem(CatalogDataApiRootDescriptor.COLLECTIONS.operation(URL_NAME_NAMING_CONVENTION)))
			.method(HttpMethod.GET)
			.description(CatalogDataApiRootDescriptor.COLLECTIONS.description())
			.queryParameter(ParamDescriptor.ENTITY_COUNT
				.to(operationQueryParameterBuilderTransformer)
				.type(DataTypesConverter.getOpenApiScalar(Boolean.class))
				.build())
			.successResponse(nonNull(arrayOf(typeRefTo(CollectionDescriptor.THIS.name()))))
			.handler(CollectionsHandler::new)
			.build();
	}

	@Nonnull
	public Optional<OpenApiCatalogEndpoint> buildGetUnknownEntityEndpoint(@Nonnull CatalogRestBuildingContext buildingContext,
	                                                                      @Nonnull List<GlobalAttributeSchemaContract> globallyUniqueAttributes,
	                                                                      boolean localized) {
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
			newCatalogEndpoint(buildingContext.getSchema())
				.path(localized, p -> p
					.staticItem(CatalogDataApiRootDescriptor.GET_UNKNOWN_ENTITY.classifier(URL_NAME_NAMING_CONVENTION))
					.staticItem(CatalogDataApiRootDescriptor.GET_UNKNOWN_ENTITY.operation(URL_NAME_NAMING_CONVENTION)))
				.method(HttpMethod.GET)
				.description(CatalogDataApiRootDescriptor.GET_UNKNOWN_ENTITY.description())
				.queryParameters(queryParameters)
				.successResponse(typeRefTo(localized ? EntityUnion.THIS_LOCALIZED.name() : EntityUnion.THIS.name()))
				.handler(UnknownEntityHandler::new)
				.build()
		);
	}

	@Nonnull
	public Optional<OpenApiCatalogEndpoint> buildListUnknownEntityEndpoint(@Nonnull CatalogRestBuildingContext buildingContext,
	                                                                       @Nonnull List<GlobalAttributeSchemaContract> globallyUniqueAttributes,
	                                                                       boolean localized) {
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
			newCatalogEndpoint(buildingContext.getSchema())
				.path(localized, p -> p
					.staticItem(CatalogDataApiRootDescriptor.LIST_UNKNOWN_ENTITY.classifier(URL_NAME_NAMING_CONVENTION))
					.staticItem(CatalogDataApiRootDescriptor.LIST_UNKNOWN_ENTITY.operation(URL_NAME_NAMING_CONVENTION)))
				.method(HttpMethod.GET)
				.description(CatalogDataApiRootDescriptor.LIST_UNKNOWN_ENTITY.description())
				.queryParameters(queryParameters)
				.successResponse(nonNull(arrayOf(typeRefTo(localized ? EntityUnion.THIS_LOCALIZED.name() : EntityUnion.THIS.name()))))
				.handler(UnknownEntityListHandler::new)
				.build()
		);
	}

	@Nonnull
	public OpenApiCollectionEndpoint buildUpsertEntityEndpoint(@Nonnull EntitySchemaContract entitySchema,
	                                                           boolean withPrimaryKeyInPath) {
		return newCollectionEndpoint(buildingContext.getSchema(), entitySchema)
			.path(p -> p // directly at the collection root
				.paramItem(withPrimaryKeyInPath ? ParamDescriptor.PRIMARY_KEY.to(operationPathParameterBuilderTransformer) : null))
			.method(withPrimaryKeyInPath ? HttpMethod.PUT : HttpMethod.POST)
			.description(CatalogDataApiRootDescriptor.UPSERT_ENTITY.description(entitySchema.getName()))
			.deprecationNotice(entitySchema.getDeprecationNotice())
			.requestBody(typeRefTo(EntityUpsertRequestDescriptor.THIS.name(entitySchema)))
			.successResponse(nonNull(typeRefTo(constructEntityObjectName(entitySchema, false))))
			.handler(ctx -> new UpsertEntityHandler(ctx, withPrimaryKeyInPath))
			.build();
	}

	@Nonnull
	public OpenApiCollectionEndpoint buildDeleteEntityEndpoint(@Nonnull EntitySchemaContract entitySchema) {
		return newCollectionEndpoint(buildingContext.getSchema(), entitySchema)
			.path(p -> p // directly at the collection root
				.paramItem(ParamDescriptor.PRIMARY_KEY
					.to(operationPathParameterBuilderTransformer)
					.type(DataTypesConverter.getOpenApiScalar(Integer.class, true))))
			.method(HttpMethod.DELETE)
			.description(CatalogDataApiRootDescriptor.DELETE_ENTITY.description(entitySchema.getName()))
			.deprecationNotice(entitySchema.getDeprecationNotice())
			.queryParameters(buildEntityFetchQueryParameters(entitySchema))
			.successResponse(typeRefTo(constructEntityObjectName(entitySchema, false)))
			.handler(DeleteEntityHandler::new)
			.build();
	}

	@Nonnull
	public OpenApiCollectionEndpoint buildDeleteEntitiesByQueryEndpoint(@Nonnull EntitySchemaContract entitySchema) {
		return newCollectionEndpoint(buildingContext.getSchema(), entitySchema)
			.path(p -> p) // directly at the collection root
			.method(HttpMethod.DELETE)
			.description(CatalogDataApiRootDescriptor.DELETE_ENTITY.description(entitySchema.getName()))
			.deprecationNotice(entitySchema.getDeprecationNotice())
			.requestBody(typeRefTo(FetchRequestDescriptor.THIS_DELETE.name(entitySchema)))
			.successResponse(nonNull(typeRefTo(constructEntityObjectName(entitySchema, false))))
			.handler(DeleteEntitiesByQueryHandler::new)
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
	private List<OpenApiEndpointParameter> buildGetEntityQueryParameters(@Nonnull EntitySchemaContract entitySchema,
	                                                                     boolean localized,
	                                                                     boolean withPkInPath) {
		final List<OpenApiEndpointParameter> parameters = new LinkedList<>();

		if (!withPkInPath) {
			parameters.add(ParamDescriptor.PRIMARY_KEY.to(operationQueryParameterBuilderTransformer).build());
		}

		// build locale argument
		if (!entitySchema.getLocales().isEmpty()) {
			final OpenApiTypeReference localeEnum = typeRefTo(ENTITY_LOCALE_ENUM.name(entitySchema));

			if (!localized) {
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
				.type(typeRefTo(ENTITY_CURRENCY_ENUM.name(entitySchema)))
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
		parameters.addAll(buildEntityFetchQueryParameters(entitySchema));

		return parameters;
	}

	@Nonnull
	private List<OpenApiEndpointParameter> buildEntityFetchQueryParameters(@Nonnull EntitySchemaContract entitySchema) {
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
}
