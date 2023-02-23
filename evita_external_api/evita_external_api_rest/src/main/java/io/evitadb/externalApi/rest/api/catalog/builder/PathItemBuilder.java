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
import io.evitadb.externalApi.api.catalog.dataApi.model.UpsertEntityMutationHeaderDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.CatalogSchemaApiRootDescriptor;
import io.evitadb.externalApi.api.catalog.schemaApi.model.EntitySchemaDescriptor;
import io.evitadb.externalApi.api.model.EndpointDescriptor;
import io.evitadb.externalApi.http.MimeTypes;
import io.evitadb.externalApi.rest.api.catalog.ParamDescriptor;
import io.evitadb.externalApi.rest.api.catalog.builder.constraint.RequireSchemaBuilder;
import io.evitadb.externalApi.rest.api.catalog.model.CollectionDescriptor;
import io.evitadb.externalApi.rest.api.catalog.model.ErrorDescriptor;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

import javax.annotation.Nonnull;
import java.util.LinkedList;
import java.util.List;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.ARGUMENT_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.rest.api.catalog.builder.PathItemsCreator.*;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.*;
import static io.evitadb.externalApi.rest.api.catalog.builder.transformer.Transformers.OBJECT_TRANSFORMER;
import static io.evitadb.externalApi.rest.api.catalog.builder.transformer.Transformers.PROPERTY_TRANSFORMER;
import static io.evitadb.externalApi.rest.api.catalog.builder.transformer.Transformers.STATIC_ENDPOINT_TRANSFORMER;

/**
 * Creates OpenAPI {@link PathItem} for each endpoint and register path item into OpenAPI schema. Also register HTTP handlers
 * for requests processing.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
public class PathItemBuilder {

	public void buildAndAddSingleEntityPathItem(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx, boolean localeInPath) {
		//this is correct as for localized URL (i.e. one locale) is non-localized entity sufficient
		final var entitySchema = localeInPath ? entitySchemaBuildingCtx.getEntityObject() : entitySchemaBuildingCtx.getLocalizedEntityObject();
		final var operation = new Operation();
		operation.setParameters(buildSingleEntityParameters(entitySchemaBuildingCtx, localeInPath));
		operation.setDescription(String.format(CatalogDataApiRootDescriptor.ENTITY_GET.description(), entitySchema.getName()));
		if (entitySchemaBuildingCtx.getSchema().getDeprecationNotice() != null) {
			operation.setDeprecated(Boolean.TRUE);
		}

		final var apiResponses = new ApiResponses();
		createAndAddOkResponse(apiResponses, entitySchema);
		createAndAddAllErrorResponses(apiResponses, createReferenceSchema(ErrorDescriptor.THIS));

		//makes sense only when getting single entity
		final var notFoundAllowedResponse = createSchemaResponse(createReferenceSchema(ErrorDescriptor.THIS));
		notFoundAllowedResponse.setDescription("Requested entity wasn't found");
		apiResponses.addApiResponse(STATUS_CODE_NOT_FOUND, notFoundAllowedResponse);

		operation.setResponses(apiResponses);

		final var pathItem = new PathItem();
		pathItem.setGet(operation);
		entitySchemaBuildingCtx.getCatalogCtx().registerPath(UrlPathCreator.createBaseUrlPathToCatalog(entitySchemaBuildingCtx.getCatalogCtx().getCatalog()) +
			UrlPathCreator.createUrlPathToEntity(entitySchemaBuildingCtx, CatalogDataApiRootDescriptor.ENTITY_GET, localeInPath) + UrlPathCreator.URL_PATH_SEPARATOR + UrlPathCreator.URL_PRIMARY_KEY_PATH_VARIABLE, pathItem);
		entitySchemaBuildingCtx.getCatalogCtx().getRestApiHandlerRegistrar().registerSingleEntityHandler(entitySchemaBuildingCtx, localeInPath);
	}

	public void buildAndAddEntityListPathItem(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx, boolean localeInPath) {
		//this is correct as for localized URL (i.e. one locale) is non-localized entity sufficient
		final var entitySchema = localeInPath ? entitySchemaBuildingCtx.getEntityObject() : entitySchemaBuildingCtx.getLocalizedEntityObject();

		final var requestBody = new RequestBody();
		final Schema<Object> filterByInputObject = localeInPath ? entitySchemaBuildingCtx.getFilterByLocalizedInputObject() : entitySchemaBuildingCtx.getFilterByInputObject();
		final var requestListSchema = createRequestListSchema(filterByInputObject,
			entitySchemaBuildingCtx.getOrderByInputObject(), entitySchemaBuildingCtx.getRequiredForListInputObject());
		requestBody.setContent(createApplicationJsonContent(createMediaType(requestListSchema)));

		final var operation = new Operation();
		if (localeInPath) {
			operation.addParametersItem(PathItemsCreator.createPathParameter(ParamDescriptor.REQUIRED_LOCALE.to(PROPERTY_TRANSFORMER)));
		}

		operation.setRequestBody(requestBody);
		operation.setDescription(String.format(CatalogDataApiRootDescriptor.ENTITY_LIST.description(), entitySchema.getName()));
		if (entitySchemaBuildingCtx.getSchema().getDeprecationNotice() != null) {
			operation.setDeprecated(Boolean.TRUE);
		}

		final var apiResponses = new ApiResponses();
		createSchemaArrayAndAddOkResponse(apiResponses, entitySchema);
		createAndAddAllErrorResponses(apiResponses, createReferenceSchema(ErrorDescriptor.THIS));
		operation.setResponses(apiResponses);

		final var pathItem = new PathItem();
		pathItem.setPost(operation);
		entitySchemaBuildingCtx.getCatalogCtx().registerPath(UrlPathCreator.createBaseUrlPathToCatalog(entitySchemaBuildingCtx.getCatalogCtx().getCatalog()) +
			UrlPathCreator.createUrlPathToEntity(entitySchemaBuildingCtx, CatalogDataApiRootDescriptor.ENTITY_LIST, localeInPath), pathItem);
		entitySchemaBuildingCtx.getCatalogCtx().getRestApiHandlerRegistrar().registerEntityListHandler(entitySchemaBuildingCtx, localeInPath);
	}

	public void buildAndAddEntityQueryPathItem(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx, boolean localeInPath) {
		//this is correct as for localized URL (i.e. one locale) is non-localized entity sufficient
		final var responseObject = new FullResponseObjectBuilder(entitySchemaBuildingCtx, localeInPath).buildFullResponseObject();

		final var requestBody = new RequestBody();
		final Schema<Object> filterByInputObject = localeInPath ? entitySchemaBuildingCtx.getFilterByLocalizedInputObject() : entitySchemaBuildingCtx.getFilterByInputObject();
		final var requestListSchema = createRequestQuerySchema(filterByInputObject,
			entitySchemaBuildingCtx.getOrderByInputObject(), entitySchemaBuildingCtx.getRequiredForQueryInputObject());
		requestBody.setContent(createApplicationJsonContent(createMediaType(requestListSchema)));

		final var operation = new Operation();
		if (localeInPath) {
			operation.addParametersItem(PathItemsCreator.createPathParameter(ParamDescriptor.REQUIRED_LOCALE.to(PROPERTY_TRANSFORMER)));
		}
		operation.setRequestBody(requestBody);
		operation.setDescription(String.format(CatalogDataApiRootDescriptor.ENTITY_QUERY.description(), entitySchemaBuildingCtx.getSchema().getName()));
		if (entitySchemaBuildingCtx.getSchema().getDeprecationNotice() != null) {
			operation.setDeprecated(Boolean.TRUE);
		}

		final var apiResponses = new ApiResponses();
		createSchemaArrayAndAddOkResponse(apiResponses, responseObject);
		createAndAddAllErrorResponses(apiResponses, createReferenceSchema(ErrorDescriptor.THIS));
		operation.setResponses(apiResponses);

		final var pathItem = new PathItem();
		pathItem.setPost(operation);
		entitySchemaBuildingCtx.getCatalogCtx().registerPath(UrlPathCreator.createBaseUrlPathToCatalog(entitySchemaBuildingCtx.getCatalogCtx().getCatalog()) +
			UrlPathCreator.createUrlPathToEntity(entitySchemaBuildingCtx, CatalogDataApiRootDescriptor.ENTITY_QUERY, localeInPath), pathItem);
		entitySchemaBuildingCtx.getCatalogCtx().getRestApiHandlerRegistrar().registerEntityQueryHandler(entitySchemaBuildingCtx, localeInPath);
	}

	public void buildAndAddOpenApiSpecificationPathItem(@Nonnull CatalogSchemaBuildingContext catalogCtx) {
		final Operation operation = new Operation();
		operation.setDescription("OpenAPI Specification in YAML format.");

		final ApiResponse okResponse = new ApiResponse()
			.description(operation.getDescription())
			.content(new Content()
				.addMediaType(MimeTypes.APPLICATION_YAML, new MediaType().schema(createStringSchema())));

		operation.setResponses(new ApiResponses().addApiResponse(STATUS_CODE_OK, okResponse));

		final var pathItem = new PathItem();
		pathItem.setGet(operation);
		catalogCtx.registerPath(UrlPathCreator.createBaseUrlPathToCatalog(catalogCtx.getCatalog()), pathItem);
		catalogCtx.getRestApiHandlerRegistrar().registerOpenApiSchemaHandler();
	}

	public void buildAndAddCollectionsPathItem(@Nonnull CatalogSchemaBuildingContext catalogCtx) {
		final var operation = new Operation();
		final var collectionsSchema = createCollectionsObject(catalogCtx);
		operation.setDescription(collectionsSchema.getDescription());
		operation.addParametersItem(new QueryParameter()
			.name(ParamDescriptor.ENTITY_COUNT.name())
			.description(ParamDescriptor.ENTITY_COUNT.description())
			.schema(SchemaCreator.createBooleanSchema()));

		final var apiResponses = new ApiResponses();
		createAndAddOkResponse(apiResponses, collectionsSchema);
		createAndAddAllErrorResponses(apiResponses, createReferenceSchema(ErrorDescriptor.THIS));
		operation.setResponses(apiResponses);
		//there would be same description three times so description of schema is deleted
		collectionsSchema.setDescription(null);

		final var pathItem = new PathItem();
		pathItem.setGet(operation);
		catalogCtx.registerPath(UrlPathCreator.createBaseUrlPathToCatalog(catalogCtx.getCatalog()) + UrlPathCreator.createBaseUrlPathToCollections(), pathItem);
		catalogCtx.getRestApiHandlerRegistrar().registerCollectionsHandler(CatalogDataApiRootDescriptor.COLLECTIONS.operation());
	}

	public void buildAndUnknownSingleEntityPathItem(@Nonnull CatalogSchemaBuildingContext catalogCtx,
	                                                @Nonnull List<Schema<Object>> entityObjects,
	                                                @Nonnull List<GlobalAttributeSchemaContract> globallyUniqueAttributes,
	                                                boolean localizedUrl) {
		if (entityObjects.isEmpty()) {
			return;
		}

		final Schema<Object> unknownEntitySchema = createUnknownEntitySchema(entityObjects, CatalogDataApiRootDescriptor.UNKNOWN_ENTITY_GET);

		final var operation = new Operation();
		operation.setDescription(unknownEntitySchema.getDescription());

		globallyUniqueAttributes.forEach(arg ->
			operation.addParametersItem(new QueryParameter()
				.name(arg.getNameVariant(ARGUMENT_NAME_NAMING_CONVENTION))
				.description(arg.getDescription())
				.schema(createSchemaByJavaType(arg.getPlainType())))
		);

		createAndAddFetchParamsForUnknownEntity(operation, localizedUrl);

		final var apiResponses = new ApiResponses();
		createAndAddOkResponse(apiResponses, unknownEntitySchema);
		createAndAddAllErrorResponses(apiResponses, createReferenceSchema(ErrorDescriptor.THIS));
		//makes sense only when getting single entity
		final var notFoundAllowedResponse = createSchemaResponse(createReferenceSchema(ErrorDescriptor.THIS));
		notFoundAllowedResponse.setDescription("Requested entity wasn't found");
		apiResponses.addApiResponse(STATUS_CODE_NOT_FOUND, notFoundAllowedResponse);

		operation.setResponses(apiResponses);
		//there would be same description three times so description of schema is deleted
		unknownEntitySchema.setDescription(null);

		final var pathItem = new PathItem();
		pathItem.setGet(operation);
		catalogCtx.registerPath(UrlPathCreator.createBaseUrlPathToCatalog(catalogCtx.getCatalog()) + UrlPathCreator.createUrlPathToUnknownEntity(localizedUrl), pathItem);
		catalogCtx.getRestApiHandlerRegistrar().registerUnknownEntityHandler(localizedUrl);
	}

	public void buildAndUnknownEntityListPathItem(@Nonnull CatalogSchemaBuildingContext catalogCtx,
	                                              @Nonnull List<Schema<Object>> entityObjects,
	                                              @Nonnull List<GlobalAttributeSchemaContract> globallyUniqueAttributes,
	                                              boolean localizedUrl) {
		if (entityObjects.isEmpty()) {
			return;
		}

		final Schema<Object> unknownEntitySchema = createUnknownEntitySchema(entityObjects, CatalogDataApiRootDescriptor.UNKNOWN_ENTITY_LIST);

		final var operation = new Operation();
		operation.setDescription(unknownEntitySchema.getDescription());

		operation.addParametersItem(new QueryParameter()
			.name("limit")
			.schema(createIntegerSchema()));

		globallyUniqueAttributes.forEach(arg ->
			operation.addParametersItem(new QueryParameter()
				.name(arg.getNameVariant(ARGUMENT_NAME_NAMING_CONVENTION))
				.description(arg.getDescription())
				.schema(createArraySchemaOf(createSchemaByJavaType(arg.getPlainType()))))
		);

		createAndAddFetchParamsForUnknownEntity(operation, localizedUrl);

		final var apiResponses = new ApiResponses();
		createAndAddOkResponse(apiResponses, unknownEntitySchema);
		createAndAddAllErrorResponses(apiResponses, createReferenceSchema(ErrorDescriptor.THIS));
		operation.setResponses(apiResponses);
		//there would be same description three times so description of schema is deleted
		unknownEntitySchema.setDescription(null);

		final var pathItem = new PathItem();
		pathItem.setGet(operation);
		catalogCtx.registerPath(UrlPathCreator.createBaseUrlPathToCatalog(catalogCtx.getCatalog()) + UrlPathCreator.createUrlPathToUnknownEntityList(localizedUrl), pathItem);
		catalogCtx.getRestApiHandlerRegistrar().registerUnknownEntityListHandler(localizedUrl);
	}

	public void buildAndAddUpsertMutationOperationIntoPathItem(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingContext,
	                                                           @Nonnull Schema<Object> requestObjectSchema,
	                                                           @Nonnull PathItem pathItem,
	                                                           boolean withPrimaryKeyInPath) {
		final var entitySchema = entitySchemaBuildingContext.getLocalizedEntityObject();

		final var requestBody = new RequestBody();
		requestBody.setContent(createApplicationJsonContent(createMediaType(requestObjectSchema)));

		final var operation = new Operation();
		if (withPrimaryKeyInPath) {
			final Schema<Object> primaryKeySchema = createIntegerSchema();
			primaryKeySchema
				.name(UpsertEntityMutationHeaderDescriptor.PRIMARY_KEY.name())
				.description(UpsertEntityMutationHeaderDescriptor.PRIMARY_KEY.description());
			final Parameter pathParameter = createPathParameter(primaryKeySchema, true);
			primaryKeySchema.description(null);
			operation.addParametersItem(pathParameter);
		}
		operation.setRequestBody(requestBody);
		operation.setDescription(String.format(CatalogDataApiRootDescriptor.ENTITY_UPSERT.description(), entitySchemaBuildingContext.getSchema().getName()));
		if (entitySchemaBuildingContext.getSchema().getDeprecationNotice() != null) {
			operation.setDeprecated(Boolean.TRUE);
		}

		final var apiResponses = new ApiResponses();
		createAndAddOkResponse(apiResponses, entitySchema);
		createAndAddAllErrorResponses(apiResponses, createReferenceSchema(ErrorDescriptor.THIS));
		operation.setResponses(apiResponses);

		if (withPrimaryKeyInPath) {
			pathItem.setPut(operation);
		} else {
			pathItem.setPost(operation);
		}

		entitySchemaBuildingContext.getCatalogCtx().getRestApiHandlerRegistrar().registerEntityUpsertHandler(entitySchemaBuildingContext, withPrimaryKeyInPath);
	}

	protected PathItem buildAndAddDeleteEntitiesByQueryPathItem(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingContext) {
		final var requestBody = new RequestBody();
		final Schema<Object> filterByInputObject = entitySchemaBuildingContext.getFilterByInputObject();

		final RequireSchemaBuilder requireSchemaBuilder = new RequireSchemaBuilder(entitySchemaBuildingContext.getConstraintSchemaBuildingCtx(),
			entitySchemaBuildingContext.getSchema().getName(), RequireSchemaBuilder.ALLOWED_CONSTRAINTS_FOR_DELETE);

		final var requestListSchema = createRequestListSchema(filterByInputObject,
			entitySchemaBuildingContext.getOrderByInputObject(), requireSchemaBuilder.build());
		requestBody.setContent(createApplicationJsonContent(createMediaType(requestListSchema)));

		final var operation = new Operation();
		operation.setRequestBody(requestBody);

		operation.setDescription(String.format(CatalogDataApiRootDescriptor.ENTITY_DELETE.description(), entitySchemaBuildingContext.getSchema().getName()));
		if (entitySchemaBuildingContext.getSchema().getDeprecationNotice() != null) {
			operation.setDeprecated(Boolean.TRUE);
		}

		final var apiResponses = new ApiResponses();
		createSchemaArrayAndAddOkResponse(apiResponses, entitySchemaBuildingContext.getLocalizedEntityObject());
		createAndAddAllErrorResponses(apiResponses, createReferenceSchema(ErrorDescriptor.THIS));
		operation.setResponses(apiResponses);

		final var pathItem = new PathItem();
		pathItem.setDelete(operation);

		entitySchemaBuildingContext.getCatalogCtx().registerPath(UrlPathCreator.createBaseUrlPathToCatalog(entitySchemaBuildingContext.getCatalogCtx().getCatalog()) +
			UrlPathCreator.createUrlPathToEntityMutation(entitySchemaBuildingContext, false), pathItem);
		entitySchemaBuildingContext.getCatalogCtx().getRestApiHandlerRegistrar().registerEntityListDeleteHandler(entitySchemaBuildingContext);
		return pathItem;
	}

	public PathItem buildAndAddDeleteSingleEntityPathItem(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingContext) {
		final var operation = new Operation();
		final List<Parameter> pathParameters = new LinkedList<>();
		final Schema<Object> primaryKeySchema = createIntegerSchema();
		primaryKeySchema
			.name(DeleteEntitiesMutationHeaderDescriptor.PRIMARY_KEY.name())
			.description(DeleteEntitiesMutationHeaderDescriptor.PRIMARY_KEY.description());
		final Parameter pathParameter = createPathParameter(primaryKeySchema, true);
		primaryKeySchema.description(null);
		pathParameters.add(pathParameter);
		pathParameters.addAll(buildSingleEntityFetchParameters(entitySchemaBuildingContext.getSchema()));
		operation.parameters(pathParameters);

		operation.setDescription(String.format(CatalogDataApiRootDescriptor.ENTITY_DELETE.description(), entitySchemaBuildingContext.getSchema().getName()));
		if (entitySchemaBuildingContext.getSchema().getDeprecationNotice() != null) {
			operation.setDeprecated(Boolean.TRUE);
		}

		final var apiResponses = new ApiResponses();
		createAndAddOkResponse(apiResponses, entitySchemaBuildingContext.getLocalizedEntityObject());
		createAndAddAllErrorResponses(apiResponses, createReferenceSchema(ErrorDescriptor.THIS));
		operation.setResponses(apiResponses);

		final var pathItem = new PathItem();
		pathItem.setDelete(operation);

		entitySchemaBuildingContext.getCatalogCtx().registerPath(UrlPathCreator.createBaseUrlPathToCatalog(entitySchemaBuildingContext.getCatalogCtx().getCatalog()) +
			UrlPathCreator.createUrlPathToEntityMutation(entitySchemaBuildingContext, true), pathItem);
		entitySchemaBuildingContext.getCatalogCtx().getRestApiHandlerRegistrar().registerEntityDeleteHandler(entitySchemaBuildingContext);
		return pathItem;
	}

	public void buildAndAddGetEntitySchemaPathItem(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();
		final Schema<Object> entitySchemaObject = createReferenceSchema(EntitySchemaDescriptor.THIS_SPECIFIC.name(entitySchema));
		final Operation operation = new Operation();
		operation.setDescription(String.format(CatalogSchemaApiRootDescriptor.GET_ENTITY_SCHEMA.description(), entitySchema.getName()));
		if (entitySchema.getDeprecationNotice() != null) {
			operation.setDeprecated(Boolean.TRUE);
		}

		final ApiResponses apiResponses = new ApiResponses();
		createAndAddOkResponse(apiResponses, entitySchemaObject);
		createAndAddAllErrorResponses(apiResponses, createReferenceSchema(ErrorDescriptor.THIS));

		final ApiResponse notFoundAllowedResponse = createSchemaResponse(createReferenceSchema(ErrorDescriptor.THIS));
		notFoundAllowedResponse.setDescription("Requested entity schema wasn't found");
		apiResponses.addApiResponse(STATUS_CODE_NOT_FOUND, notFoundAllowedResponse);

		operation.setResponses(apiResponses);

		final PathItem pathItem = new PathItem();
		pathItem.setGet(operation);
		entitySchemaBuildingCtx.getCatalogCtx().registerPath(
			UrlPathCreator.createBaseUrlPathToCatalog(entitySchemaBuildingCtx.getCatalogCtx().getCatalog()) +
				UrlPathCreator.createUrlPathToEntitySchema(entitySchemaBuildingCtx, CatalogSchemaApiRootDescriptor.GET_ENTITY_SCHEMA),
			pathItem
		);
		entitySchemaBuildingCtx.getCatalogCtx().getRestApiHandlerRegistrar().registerEntitySchemaHandler(entitySchemaBuildingCtx);
	}

	private void createAndAddFetchParamsForUnknownEntity(@Nonnull Operation operation, boolean localizedUrl) {
		//build fetch params
		if (localizedUrl) {
			operation.addParametersItem(PathItemsCreator.createPathParameter(ParamDescriptor.REQUIRED_LOCALE.to(PROPERTY_TRANSFORMER)));
		} else {
			operation.addParametersItem(PathItemsCreator.createQueryParameter(ParamDescriptor.LOCALE.to(PROPERTY_TRANSFORMER)));
		}
		operation.addParametersItem(PathItemsCreator.createQueryParameter(ParamDescriptor.DATA_IN_LOCALES.to(PROPERTY_TRANSFORMER)));
		operation.addParametersItem(PathItemsCreator.createQueryParameter(ParamDescriptor.FETCH_ALL.to(PROPERTY_TRANSFORMER)));
		operation.addParametersItem(PathItemsCreator.createQueryParameter(ParamDescriptor.BODY_FETCH.to(PROPERTY_TRANSFORMER)));
		operation.addParametersItem(PathItemsCreator.createQueryParameter(ParamDescriptor.ASSOCIATED_DATA_CONTENT_ALL.to(PROPERTY_TRANSFORMER)));
		operation.addParametersItem(PathItemsCreator.createQueryParameter(ParamDescriptor.ATTRIBUTE_CONTENT_ALL.to(PROPERTY_TRANSFORMER)));
		operation.addParametersItem(PathItemsCreator.createQueryParameter(ParamDescriptor.PRICE_CONTENT.to(PROPERTY_TRANSFORMER)));
		operation.addParametersItem(PathItemsCreator.createQueryParameter(ParamDescriptor.REFERENCE_CONTENT_ALL.to(PROPERTY_TRANSFORMER)));
	}

	@SuppressWarnings("rawtypes")
	@Nonnull
	private Schema<Object> createUnknownEntitySchema(@Nonnull List<Schema<Object>> entityObjects, @Nonnull EndpointDescriptor endpointDescriptor) {
		final var unknownEntitySchema = STATIC_ENDPOINT_TRANSFORMER.apply(endpointDescriptor);
		unknownEntitySchema.oneOf(entityObjects.stream().map(it -> (Schema) it).toList());
		unknownEntitySchema.discriminator(new Discriminator().propertyName("type"));
		return unknownEntitySchema;
	}

	@Nonnull
	private List<Parameter> buildSingleEntityParameters(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx, boolean localeInPath) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final var parameters = new LinkedList<Parameter>();

		parameters.add(PathItemsCreator.createPathParameter(ParamDescriptor.PRIMARY_KEY.to(PROPERTY_TRANSFORMER)));

		// build locale argument
		if (!entitySchema.getLocales().isEmpty()) {
			if (localeInPath) {
				final Parameter localeParameter = createPathParameter(ParamDescriptor.REQUIRED_LOCALE.to(PROPERTY_TRANSFORMER));
				localeParameter.schema(createReferenceSchema(entitySchemaBuildingCtx.getLocaleEnum().getName()));
				parameters.add(localeParameter);
			} else {
				final Parameter dataInLocalesParameter = createQueryParameter(ParamDescriptor.DATA_IN_LOCALES.to(PROPERTY_TRANSFORMER));
				dataInLocalesParameter.schema(createArraySchemaOf(createReferenceSchema(entitySchemaBuildingCtx.getLocaleEnum().getName())));
				parameters.add(dataInLocalesParameter);

				final Parameter localeParameter = createQueryParameter(ParamDescriptor.LOCALE.to(PROPERTY_TRANSFORMER));
				localeParameter.schema(createReferenceSchema(entitySchemaBuildingCtx.getLocaleEnum().getName()));
				parameters.add(localeParameter);
			}
		}

		// build price arguments
		if (!entitySchema.getCurrencies().isEmpty()) {
			parameters.add(new QueryParameter()
				.name(ParamDescriptor.PRICE_IN_CURRENCY.name())
				.description(ParamDescriptor.PRICE_IN_CURRENCY.description())
				.schema(createReferenceSchema(entitySchemaBuildingCtx.getCurrencyEnum().getName())));
			parameters.add(PathItemsCreator.createQueryParameter(ParamDescriptor.PRICE_IN_PRICE_LISTS.to(PROPERTY_TRANSFORMER)));
			parameters.add(PathItemsCreator.createQueryParameter(ParamDescriptor.PRICE_VALID_IN.to(PROPERTY_TRANSFORMER)));
			parameters.add(PathItemsCreator.createQueryParameter(ParamDescriptor.PRICE_VALID_NOW.to(PROPERTY_TRANSFORMER)));
		}

		// build unique attribute filter arguments
		parameters.addAll(entitySchema.getAttributes()
			.values()
			.stream()
			.filter(AttributeSchemaContract::isUnique)
			.map(as -> {
				var param = new QueryParameter()
					.name(as.getNameVariant(ARGUMENT_NAME_NAMING_CONVENTION))
					.schema(createSchemaByJavaType(as.getPlainType()))
					.description(as.getDescription());
				if (as.getDeprecationNotice() != null) {
					param.deprecated(Boolean.TRUE);
				}
				return param;
			})
			.toList());


		//build fetch params
		parameters.addAll(buildSingleEntityFetchParameters(entitySchema));

		return parameters;
	}

	private static List<Parameter> buildSingleEntityFetchParameters(EntitySchemaContract entitySchema) {
		final List<Parameter> parameters = new LinkedList<>();
		parameters.add(PathItemsCreator.createQueryParameter(ParamDescriptor.FETCH_ALL.to(PROPERTY_TRANSFORMER)));
		parameters.add(PathItemsCreator.createQueryParameter(ParamDescriptor.BODY_FETCH.to(PROPERTY_TRANSFORMER)));
		if (!entitySchema.getAssociatedData().isEmpty()) {
			parameters.add(PathItemsCreator.createQueryParameter(ParamDescriptor.ASSOCIATED_DATA_CONTENT.to(PROPERTY_TRANSFORMER)));
			parameters.add(PathItemsCreator.createQueryParameter(ParamDescriptor.ASSOCIATED_DATA_CONTENT_ALL.to(PROPERTY_TRANSFORMER)));
		}
		if (!entitySchema.getAttributes().isEmpty()) {
			parameters.add(PathItemsCreator.createQueryParameter(ParamDescriptor.ATTRIBUTE_CONTENT.to(PROPERTY_TRANSFORMER)));
			parameters.add(PathItemsCreator.createQueryParameter(ParamDescriptor.ATTRIBUTE_CONTENT_ALL.to(PROPERTY_TRANSFORMER)));
		}
		if (!entitySchema.getCurrencies().isEmpty()) {
			parameters.add(PathItemsCreator.createQueryParameter(ParamDescriptor.PRICE_CONTENT.to(PROPERTY_TRANSFORMER)));
		}
		if (!entitySchema.getReferences().isEmpty()) {
			parameters.add(PathItemsCreator.createQueryParameter(ParamDescriptor.REFERENCE_CONTENT.to(PROPERTY_TRANSFORMER)));
			parameters.add(PathItemsCreator.createQueryParameter(ParamDescriptor.REFERENCE_CONTENT_ALL.to(PROPERTY_TRANSFORMER)));
		}

		return parameters;
	}

	@Nonnull
	private static Schema<Object> createCollectionsObject(@Nonnull CatalogSchemaBuildingContext catalogCtx) {
		final Schema<Object> collectionObject = catalogCtx.registerType(CollectionDescriptor.THIS.to(OBJECT_TRANSFORMER));
		final ArraySchema collectionsObject = createArraySchemaOf(collectionObject);
		collectionsObject.setDescription(CatalogDataApiRootDescriptor.COLLECTIONS.description());
		return collectionsObject;
	}
}
