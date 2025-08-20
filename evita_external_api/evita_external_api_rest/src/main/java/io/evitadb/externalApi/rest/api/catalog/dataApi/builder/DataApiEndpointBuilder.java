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

package io.evitadb.externalApi.rest.api.catalog.dataApi.builder;

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor;
import io.evitadb.externalApi.rest.api.catalog.builder.CatalogRestBuildingContext;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.CollectionDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.EntityUnion;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.EntityUpsertRequestDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.FetchEntityRequestDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.CollectionsEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.DeleteEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.FetchEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.GetEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.ListUnknownEntitiesEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.ScopeAwareEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.UnknownEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.UpsertEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint.CollectionsHandler;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint.DeleteEntitiesByQueryHandler;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint.DeleteEntityHandler;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint.GetEntityHandler;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint.GetUnknownEntityHandler;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint.ListEntitiesHandler;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint.ListUnknownEntitiesHandler;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint.QueryEntitiesHandler;
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
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.ARGUMENT_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.CURRENCY_ENUM;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.LOCALE_ENUM;
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

	private static final String LOCALIZED_OPERATION_ID_SUFFIX = "Localized";
	private static final String GET_BY_ID_OPERATION_ID_SUFFIX = "ById";
	private static final String DELETE_BY_QUERY_OPERATION_ID_SUFFIX = "ByQuery";

	@Nonnull private final CatalogRestBuildingContext buildingContext;
	@Nonnull private final PropertyDescriptorToOpenApiOperationPathParameterTransformer operationPathParameterBuilderTransformer;
	@Nonnull private final PropertyDescriptorToOpenApiOperationQueryParameterTransformer operationQueryParameterBuilderTransformer;

	@Nonnull
	public OpenApiCollectionEndpoint buildGetEntityEndpoint(@Nonnull EntitySchemaContract entitySchema,
	                                                        boolean localized,
	                                                        boolean withPkInPath) {
		final String operationId;
		if (withPkInPath) {
			final String suffix = GET_BY_ID_OPERATION_ID_SUFFIX + Optional.ofNullable(getLocalizedSuffix(localized)).orElse("");
			operationId = CatalogDataApiRootDescriptor.GET_ENTITY.operation(entitySchema, suffix);
		} else {
			operationId = CatalogDataApiRootDescriptor.GET_ENTITY.operation(entitySchema, getLocalizedSuffix(localized));
		}

		return newCollectionEndpoint(this.buildingContext.getSchema(), entitySchema)
			.path(localized, p -> p
				.staticItem(CatalogDataApiRootDescriptor.GET_ENTITY.urlPathItem())
				.paramItem(withPkInPath ? GetEntityEndpointHeaderDescriptor.PRIMARY_KEY.to(this.operationPathParameterBuilderTransformer) : null))
			.method(HttpMethod.GET)
			.operationId(operationId)
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
		return newCollectionEndpoint(this.buildingContext.getSchema(), entitySchema)
			.path(localized, p -> p
				.staticItem(CatalogDataApiRootDescriptor.LIST_ENTITY.urlPathItem()))
			.method(HttpMethod.POST)
			.operationId(CatalogDataApiRootDescriptor.LIST_ENTITY.operation(entitySchema, getLocalizedSuffix(localized)))
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
		return newCollectionEndpoint(this.buildingContext.getSchema(), entitySchema)
			.path(localized, p -> p
				.staticItem(CatalogDataApiRootDescriptor.QUERY_ENTITY.urlPathItem()))
			.method(HttpMethod.POST)
			.operationId(CatalogDataApiRootDescriptor.QUERY_ENTITY.operation(entitySchema, getLocalizedSuffix(localized)))
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
				.staticItem(CatalogDataApiRootDescriptor.COLLECTIONS.urlPathItem()))
			.method(HttpMethod.GET)
			.operationId(CatalogDataApiRootDescriptor.COLLECTIONS.operation())
			.description(CatalogDataApiRootDescriptor.COLLECTIONS.description())
			.queryParameter(CollectionsEndpointHeaderDescriptor.ENTITY_COUNT
				.to(this.operationQueryParameterBuilderTransformer)
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
		if (buildingContext.getEntitySchemas().isEmpty()) {
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
		queryParameters.add(ScopeAwareEndpointHeaderDescriptor.SCOPE.to(this.operationQueryParameterBuilderTransformer).build());
		queryParameters.add(UnknownEntityEndpointHeaderDescriptor.FILTER_JOIN
			.to(this.operationQueryParameterBuilderTransformer)
			.build());

		final boolean localeArgumentNeeded = !buildingContext.getSupportedLocales().isEmpty();
		queryParameters.addAll(buildFetchQueryParametersForUnknownEntity(!localized || localeArgumentNeeded));

		return Optional.of(
			newCatalogEndpoint(buildingContext.getSchema())
				.path(localized, p -> p
					.staticItem(CatalogDataApiRootDescriptor.GET_UNKNOWN_ENTITY.classifier())
					.staticItem(CatalogDataApiRootDescriptor.GET_UNKNOWN_ENTITY.urlPathItem()))
				.method(HttpMethod.GET)
				.operationId(CatalogDataApiRootDescriptor.GET_UNKNOWN_ENTITY.operation(getLocalizedSuffix(localized)))
				.description(CatalogDataApiRootDescriptor.GET_UNKNOWN_ENTITY.description())
				.queryParameters(queryParameters)
				.successResponse(typeRefTo(localized ? EntityUnion.THIS_LOCALIZED.name() : EntityUnion.THIS.name()))
				.handler(GetUnknownEntityHandler::new)
				.build()
		);
	}

	@Nonnull
	public Optional<OpenApiCatalogEndpoint> buildListUnknownEntityEndpoint(@Nonnull CatalogRestBuildingContext buildingContext,
	                                                                       @Nonnull List<GlobalAttributeSchemaContract> globallyUniqueAttributes,
	                                                                       boolean localized) {
		if (buildingContext.getEntitySchemas().isEmpty()) {
			return Optional.empty();
		}

		final List<OpenApiEndpointParameter> queryParameters = new LinkedList<>();
		queryParameters.add(ListUnknownEntitiesEndpointHeaderDescriptor.LIMIT
			.to(this.operationQueryParameterBuilderTransformer)
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
		queryParameters.add(ScopeAwareEndpointHeaderDescriptor.SCOPE.to(this.operationQueryParameterBuilderTransformer).build());
		queryParameters.add(UnknownEntityEndpointHeaderDescriptor.FILTER_JOIN
			.to(this.operationQueryParameterBuilderTransformer)
			.build());

		final boolean localeArgumentNeeded = !buildingContext.getSupportedLocales().isEmpty();
		queryParameters.addAll(buildFetchQueryParametersForUnknownEntity(!localized || localeArgumentNeeded));

		return Optional.of(
			newCatalogEndpoint(buildingContext.getSchema())
				.path(localized, p -> p
					.staticItem(CatalogDataApiRootDescriptor.LIST_UNKNOWN_ENTITY.classifier())
					.staticItem(CatalogDataApiRootDescriptor.LIST_UNKNOWN_ENTITY.urlPathItem()))
				.method(HttpMethod.GET)
				.operationId(CatalogDataApiRootDescriptor.LIST_UNKNOWN_ENTITY.operation(getLocalizedSuffix(localized)))
				.description(CatalogDataApiRootDescriptor.LIST_UNKNOWN_ENTITY.description())
				.queryParameters(queryParameters)
				.successResponse(nonNull(arrayOf(typeRefTo(localized ? EntityUnion.THIS_LOCALIZED.name() : EntityUnion.THIS.name()))))
				.handler(ListUnknownEntitiesHandler::new)
				.build()
		);
	}

	@Nonnull
	public OpenApiCollectionEndpoint buildUpsertEntityEndpoint(@Nonnull EntitySchemaContract entitySchema,
	                                                           boolean withPrimaryKeyInPath) {
		return newCollectionEndpoint(this.buildingContext.getSchema(), entitySchema)
			.path(p -> p
				.staticItem(CatalogDataApiRootDescriptor.UPSERT_ENTITY.urlPathItem())
				.paramItem(withPrimaryKeyInPath ? UpsertEntityEndpointHeaderDescriptor.PRIMARY_KEY.to(this.operationPathParameterBuilderTransformer) : null))
			.method(withPrimaryKeyInPath ? HttpMethod.PUT : HttpMethod.POST)
			.operationId(CatalogDataApiRootDescriptor.UPSERT_ENTITY.operation(entitySchema))
			.description(CatalogDataApiRootDescriptor.UPSERT_ENTITY.description(entitySchema.getName()))
			.deprecationNotice(entitySchema.getDeprecationNotice())
			.requestBody(typeRefTo(EntityUpsertRequestDescriptor.THIS.name(entitySchema)))
			.successResponse(nonNull(typeRefTo(constructEntityObjectName(entitySchema, false))))
			.handler(ctx -> new UpsertEntityHandler(ctx, withPrimaryKeyInPath))
			.build();
	}

	@Nonnull
	public OpenApiCollectionEndpoint buildDeleteEntityEndpoint(@Nonnull EntitySchemaContract entitySchema) {
		return newCollectionEndpoint(this.buildingContext.getSchema(), entitySchema)
			.path(p -> p
				.staticItem(CatalogDataApiRootDescriptor.DELETE_ENTITY.urlPathItem())
				.paramItem(DeleteEntityEndpointHeaderDescriptor.PRIMARY_KEY
					.to(this.operationPathParameterBuilderTransformer)
					.type(DataTypesConverter.getOpenApiScalar(Integer.class, true))))
			.method(HttpMethod.DELETE)
			.operationId(CatalogDataApiRootDescriptor.DELETE_ENTITY.operation(entitySchema))
			.description(CatalogDataApiRootDescriptor.DELETE_ENTITY.description(entitySchema.getName()))
			.deprecationNotice(entitySchema.getDeprecationNotice())
			.queryParameters(buildEntityFetchQueryParameters(entitySchema))
			.successResponse(typeRefTo(constructEntityObjectName(entitySchema, false)))
			.handler(DeleteEntityHandler::new)
			.build();
	}

	@Nonnull
	public OpenApiCollectionEndpoint buildDeleteEntitiesByQueryEndpoint(@Nonnull EntitySchemaContract entitySchema) {
		return newCollectionEndpoint(this.buildingContext.getSchema(), entitySchema)
			.path(p -> p
				.staticItem(CatalogDataApiRootDescriptor.DELETE_ENTITY.urlPathItem()))
			.method(HttpMethod.DELETE)
			.operationId(CatalogDataApiRootDescriptor.DELETE_ENTITY.operation(entitySchema, DELETE_BY_QUERY_OPERATION_ID_SUFFIX))
			.description(CatalogDataApiRootDescriptor.DELETE_ENTITY.description(entitySchema.getName()))
			.deprecationNotice(entitySchema.getDeprecationNotice())
			.requestBody(typeRefTo(FetchEntityRequestDescriptor.THIS_DELETE.name(entitySchema)))
			.successResponse(nonNull(typeRefTo(constructEntityObjectName(entitySchema, false))))
			.handler(DeleteEntitiesByQueryHandler::new)
			.build();
	}

	@Nonnull
	private List<OpenApiEndpointParameter> buildFetchQueryParametersForUnknownEntity(boolean needsLocale) {
		final List<OpenApiEndpointParameter> queryParameters = new ArrayList<>(8);

		//build fetch params
		if (needsLocale) {
			queryParameters.add(FetchEntityEndpointHeaderDescriptor.LOCALE.to(this.operationQueryParameterBuilderTransformer).build());
		}
		queryParameters.add(FetchEntityEndpointHeaderDescriptor.DATA_IN_LOCALES.to(this.operationQueryParameterBuilderTransformer).build());
		queryParameters.add(FetchEntityEndpointHeaderDescriptor.FETCH_ALL.to(this.operationQueryParameterBuilderTransformer).build());
		queryParameters.add(FetchEntityEndpointHeaderDescriptor.BODY_FETCH.to(this.operationQueryParameterBuilderTransformer).build());
		queryParameters.add(FetchEntityEndpointHeaderDescriptor.ASSOCIATED_DATA_CONTENT_ALL.to(this.operationQueryParameterBuilderTransformer).build());
		queryParameters.add(FetchEntityEndpointHeaderDescriptor.ATTRIBUTE_CONTENT_ALL.to(this.operationQueryParameterBuilderTransformer).build());
		queryParameters.add(FetchEntityEndpointHeaderDescriptor.PRICE_CONTENT.to(this.operationQueryParameterBuilderTransformer).build());
		queryParameters.add(FetchEntityEndpointHeaderDescriptor.REFERENCE_CONTENT_ALL.to(this.operationQueryParameterBuilderTransformer).build());
		queryParameters.add(FetchEntityEndpointHeaderDescriptor.HIERARCHY_CONTENT.to(this.operationQueryParameterBuilderTransformer).build());

		return queryParameters;
	}

	@Nonnull
	private List<OpenApiEndpointParameter> buildGetEntityQueryParameters(@Nonnull EntitySchemaContract entitySchema,
	                                                                     boolean localized,
	                                                                     boolean withPkInPath) {
		final List<OpenApiEndpointParameter> parameters = new LinkedList<>();

		if (!withPkInPath) {
			parameters.add(GetEntityEndpointHeaderDescriptor.PRIMARY_KEY.to(this.operationQueryParameterBuilderTransformer).build());
		}

		// build locale argument
		if (!entitySchema.getLocales().isEmpty()) {
			final OpenApiTypeReference localeEnum = typeRefTo(LOCALE_ENUM.name());

			if (!localized) {
				final OpenApiEndpointParameter dataInLocalesParameter = FetchEntityEndpointHeaderDescriptor.DATA_IN_LOCALES
					.to(this.operationQueryParameterBuilderTransformer)
					.type(arrayOf(localeEnum))
					.build();
				parameters.add(dataInLocalesParameter);

				final OpenApiEndpointParameter localeParameter = FetchEntityEndpointHeaderDescriptor.LOCALE
					.to(this.operationQueryParameterBuilderTransformer)
					.type(localeEnum)
					.build();
				parameters.add(localeParameter);
			}
		}

		// build price arguments
		if (!entitySchema.getCurrencies().isEmpty()) {
			parameters.add(GetEntityEndpointHeaderDescriptor.PRICE_IN_CURRENCY
				.to(this.operationQueryParameterBuilderTransformer)
				.type(typeRefTo(CURRENCY_ENUM.name()))
				.build());
			parameters.add(GetEntityEndpointHeaderDescriptor.PRICE_IN_PRICE_LISTS.to(this.operationQueryParameterBuilderTransformer).build());
			parameters.add(GetEntityEndpointHeaderDescriptor.PRICE_VALID_IN.to(this.operationQueryParameterBuilderTransformer).build());
			parameters.add(GetEntityEndpointHeaderDescriptor.PRICE_VALID_NOW.to(this.operationQueryParameterBuilderTransformer).build());
		}

		// build unique attribute filter arguments
		if (!withPkInPath) {
			parameters.addAll(entitySchema.getAttributes()
				.values()
				.stream()
				.filter(AttributeSchemaContract::isUniqueInAnyScope)
				.map(as -> newQueryParameter()
					.name(as.getNameVariant(ARGUMENT_NAME_NAMING_CONVENTION))
					.description(as.getDescription())
					.deprecationNotice(as.getDeprecationNotice())
					.type(DataTypesConverter.getOpenApiScalar(as.getPlainType()))
					.build())
				.toList());
		}

		parameters.add(ScopeAwareEndpointHeaderDescriptor.SCOPE.to(this.operationQueryParameterBuilderTransformer).build());

		//build fetch params
		parameters.addAll(buildEntityFetchQueryParameters(entitySchema));

		return parameters;
	}

	@Nonnull
	private List<OpenApiEndpointParameter> buildEntityFetchQueryParameters(@Nonnull EntitySchemaContract entitySchema) {
		final List<OpenApiEndpointParameter> parameters = new LinkedList<>();

		parameters.add(FetchEntityEndpointHeaderDescriptor.FETCH_ALL.to(this.operationQueryParameterBuilderTransformer).build());
		parameters.add(FetchEntityEndpointHeaderDescriptor.BODY_FETCH.to(this.operationQueryParameterBuilderTransformer).build());
		if (!entitySchema.getAssociatedData().isEmpty()) {
			parameters.add(FetchEntityEndpointHeaderDescriptor.ASSOCIATED_DATA_CONTENT.to(this.operationQueryParameterBuilderTransformer).build());
			parameters.add(FetchEntityEndpointHeaderDescriptor.ASSOCIATED_DATA_CONTENT_ALL.to(this.operationQueryParameterBuilderTransformer).build());
		}
		if (!entitySchema.getAttributes().isEmpty()) {
			parameters.add(FetchEntityEndpointHeaderDescriptor.ATTRIBUTE_CONTENT.to(this.operationQueryParameterBuilderTransformer).build());
			parameters.add(FetchEntityEndpointHeaderDescriptor.ATTRIBUTE_CONTENT_ALL.to(this.operationQueryParameterBuilderTransformer).build());
		}
		if (!entitySchema.getCurrencies().isEmpty()) {
			parameters.add(FetchEntityEndpointHeaderDescriptor.PRICE_CONTENT.to(this.operationQueryParameterBuilderTransformer).build());
		}
		if (!entitySchema.getReferences().isEmpty()) {
			parameters.add(FetchEntityEndpointHeaderDescriptor.REFERENCE_CONTENT.to(this.operationQueryParameterBuilderTransformer).build());
			parameters.add(FetchEntityEndpointHeaderDescriptor.REFERENCE_CONTENT_ALL.to(this.operationQueryParameterBuilderTransformer).build());
		}
		if (entitySchema.isWithHierarchy()) {
			parameters.add(FetchEntityEndpointHeaderDescriptor.HIERARCHY_CONTENT.to(this.operationQueryParameterBuilderTransformer).build());
		}

		return parameters;
	}

	@Nullable
	private static String getLocalizedSuffix(boolean localized) {
		return localized ? LOCALIZED_OPERATION_ID_SUFFIX : null;
	}
}
