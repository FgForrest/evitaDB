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
import io.evitadb.externalApi.http.MimeTypes;
import io.evitadb.externalApi.rest.api.catalog.ParamDescriptor;
import io.evitadb.externalApi.rest.api.catalog.builder.constraint.RequireSchemaBuilder;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.ObjectDescriptorToOpenApiObjectTransformer;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.PropertyDescriptorToOpenApiOperationPathParameterTransformer;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.PropertyDescriptorToOpenApiOperationQueryParameterTransformer;
import io.evitadb.externalApi.rest.api.catalog.builder.transformer.PropertyDescriptorToOpenApiPropertyTransformer;
import io.evitadb.externalApi.rest.api.catalog.model.CollectionDescriptor;
import io.evitadb.externalApi.rest.api.catalog.model.ErrorDescriptor;
import io.evitadb.externalApi.rest.api.catalog.model.QueryRequestBodyDescriptor;
import io.evitadb.externalApi.rest.api.dto.OpenApiObject;
import io.evitadb.externalApi.rest.api.dto.OpenApiObjectUnionType;
import io.evitadb.externalApi.rest.api.dto.OpenApiOperationParameter;
import io.evitadb.externalApi.rest.api.dto.OpenApiSimpleType;
import io.evitadb.externalApi.rest.api.dto.OpenApiTypeReference;
import io.evitadb.externalApi.rest.dataType.DataTypesConverter;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Supplier;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.ARGUMENT_NAME_NAMING_CONVENTION;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.COLLECTIONS;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.ENTITY_CURRENCY_ENUM;
import static io.evitadb.externalApi.api.catalog.dataApi.model.CatalogDataApiRootDescriptor.ENTITY_LOCALE_ENUM;
import static io.evitadb.externalApi.rest.api.catalog.builder.PathItemsCreator.*;
import static io.evitadb.externalApi.rest.api.dto.OpenApiArray.arrayOf;
import static io.evitadb.externalApi.rest.api.dto.OpenApiObject.newObject;
import static io.evitadb.externalApi.rest.api.dto.OpenApiOperationParameter.newQueryParameter;
import static io.evitadb.externalApi.rest.api.dto.OpenApiTypeReference.typeRefTo;

/**
 * Creates OpenAPI {@link PathItem} for each endpoint and register path item into OpenAPI schema. Also register HTTP handlers
 * for requests processing.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class PathItemBuilder {

	@Nonnull private final PropertyDescriptorToOpenApiPropertyTransformer propertyBuilderTransformer;
	@Nonnull private final ObjectDescriptorToOpenApiObjectTransformer objectBuilderTransformer;
	@Nonnull private final PropertyDescriptorToOpenApiOperationPathParameterTransformer operationPathParameterBuilderTransformer;
	@Nonnull private final PropertyDescriptorToOpenApiOperationQueryParameterTransformer operationQueryParameterBuilderTransformer;

	public void buildAndAddSingleEntityPathItem(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx,
	                                            boolean localeInPath) {
		//this is correct as for localized URL (i.e. one locale) is non-localized entity sufficient
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();
		final OpenApiTypeReference entityObject = localeInPath ? entitySchemaBuildingCtx.getEntityObject() : entitySchemaBuildingCtx.getLocalizedEntityObject();

		final var operation = new Operation();
		operation.setDescription(String.format(CatalogDataApiRootDescriptor.ENTITY_GET.description(), entitySchema.getName()));
		if (entitySchema.getDeprecationNotice() != null) {
			operation.setDeprecated(Boolean.TRUE);
		}

		final List<OpenApiOperationParameter> parameters = buildSingleEntityParameters(entitySchemaBuildingCtx, localeInPath);
		operation.setParameters(parameters.stream().map(OpenApiOperationParameter::toParameter).toList());

		final var apiResponses = new ApiResponses();
		createAndAddOkResponse(apiResponses, entityObject);
		createAndAddAllErrorResponses(apiResponses, typeRefTo(ErrorDescriptor.THIS.name()));

		//makes sense only when getting single entity
		final var notFoundAllowedResponse = createSchemaResponse(typeRefTo(ErrorDescriptor.THIS.name()));
		notFoundAllowedResponse.setDescription("Requested entity wasn't found");
		apiResponses.addApiResponse(STATUS_CODE_NOT_FOUND, notFoundAllowedResponse);

		operation.setResponses(apiResponses);

		final var pathItem = new PathItem();
		pathItem.setGet(operation);
		entitySchemaBuildingCtx.getCatalogCtx().registerPath(UrlPathCreator.createBaseUrlPathToCatalog(entitySchemaBuildingCtx.getCatalogCtx().getCatalog()) +
			UrlPathCreator.createUrlPathToEntity(entitySchemaBuildingCtx, CatalogDataApiRootDescriptor.ENTITY_GET, localeInPath) + UrlPathCreator.URL_PATH_SEPARATOR + UrlPathCreator.URL_PRIMARY_KEY_PATH_VARIABLE, pathItem);
		entitySchemaBuildingCtx.getCatalogCtx().getRestApiHandlerRegistrar().registerSingleEntityHandler(entitySchemaBuildingCtx, localeInPath, pathItem);
	}

	public void buildAndAddEntityListPathItem(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx,
	                                          boolean localeInPath) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();
		//this is correct as for localized URL (i.e. one locale) is non-localized entity sufficient
		final OpenApiTypeReference entityObject = localeInPath ? entitySchemaBuildingCtx.getEntityObject() : entitySchemaBuildingCtx.getLocalizedEntityObject();

		final RequestBody requestBody = new RequestBody();
		final OpenApiSimpleType filterByInputObject = localeInPath ? entitySchemaBuildingCtx.getFilterByLocalizedInputObject() : entitySchemaBuildingCtx.getFilterByInputObject();
		final OpenApiObject requestListSchema = createRequestBodyObject(
			entitySchemaBuildingCtx,
			filterByInputObject,
			entitySchemaBuildingCtx.getOrderByInputObject(),
			entitySchemaBuildingCtx.getRequiredForListInputObject()
		);
		requestBody.setContent(createApplicationJsonContent(createMediaType(requestListSchema)));

		final var operation = new Operation();
		if (localeInPath) {
			operation.addParametersItem(ParamDescriptor.REQUIRED_LOCALE.to(operationPathParameterBuilderTransformer).build().toParameter());
		}

		operation.setRequestBody(requestBody);
		operation.setDescription(String.format(CatalogDataApiRootDescriptor.ENTITY_LIST.description(), entitySchema.getName()));
		if (entitySchema.getDeprecationNotice() != null) {
			operation.setDeprecated(Boolean.TRUE);
		}

		final var apiResponses = new ApiResponses();
		createSchemaArrayAndAddOkResponse(apiResponses, entityObject);
		createAndAddAllErrorResponses(apiResponses, typeRefTo(ErrorDescriptor.THIS.name()));
		operation.setResponses(apiResponses);

		final var pathItem = new PathItem();
		pathItem.setPost(operation);
		entitySchemaBuildingCtx.getCatalogCtx().registerPath(UrlPathCreator.createBaseUrlPathToCatalog(entitySchemaBuildingCtx.getCatalogCtx().getCatalog()) +
			UrlPathCreator.createUrlPathToEntity(entitySchemaBuildingCtx, CatalogDataApiRootDescriptor.ENTITY_LIST, localeInPath), pathItem);
		entitySchemaBuildingCtx.getCatalogCtx().getRestApiHandlerRegistrar().registerEntityListHandler(entitySchemaBuildingCtx, localeInPath, pathItem);
	}

	public void buildAndAddEntityQueryPathItem(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx,
	                                           boolean localeInPath) {
		//this is correct as for localized URL (i.e. one locale) is non-localized entity sufficient
		final var responseObject = new FullResponseObjectBuilder(
			entitySchemaBuildingCtx,
			propertyBuilderTransformer,
			objectBuilderTransformer,
			localeInPath
		).buildFullResponseObject();

		final var requestBody = new RequestBody();
		final OpenApiSimpleType filterByInputObject = localeInPath ? entitySchemaBuildingCtx.getFilterByLocalizedInputObject() : entitySchemaBuildingCtx.getFilterByInputObject();
		final var requestListSchema = createRequestBodyObject(
			entitySchemaBuildingCtx,
			filterByInputObject,
			entitySchemaBuildingCtx.getOrderByInputObject(),
			entitySchemaBuildingCtx.getRequiredForQueryInputObject()
		);
		requestBody.setContent(createApplicationJsonContent(createMediaType(requestListSchema)));

		final var operation = new Operation();
		if (localeInPath) {
			operation.addParametersItem(ParamDescriptor.REQUIRED_LOCALE.to(operationPathParameterBuilderTransformer).build().toParameter());
		}
		operation.setRequestBody(requestBody);
		operation.setDescription(String.format(CatalogDataApiRootDescriptor.ENTITY_QUERY.description(), entitySchemaBuildingCtx.getSchema().getName()));
		if (entitySchemaBuildingCtx.getSchema().getDeprecationNotice() != null) {
			operation.setDeprecated(Boolean.TRUE);
		}

		final var apiResponses = new ApiResponses();
		createSchemaArrayAndAddOkResponse(apiResponses, responseObject);
		createAndAddAllErrorResponses(apiResponses, typeRefTo(ErrorDescriptor.THIS.name()));
		operation.setResponses(apiResponses);

		final var pathItem = new PathItem();
		pathItem.setPost(operation);
		entitySchemaBuildingCtx.getCatalogCtx().registerPath(UrlPathCreator.createBaseUrlPathToCatalog(entitySchemaBuildingCtx.getCatalogCtx().getCatalog()) +
			UrlPathCreator.createUrlPathToEntity(entitySchemaBuildingCtx, CatalogDataApiRootDescriptor.ENTITY_QUERY, localeInPath), pathItem);
		entitySchemaBuildingCtx.getCatalogCtx().getRestApiHandlerRegistrar().registerEntityQueryHandler(entitySchemaBuildingCtx, localeInPath, pathItem);
	}

	public void buildAndAddOpenApiSpecificationPathItem(@Nonnull CatalogSchemaBuildingContext catalogCtx) {
		final Operation operation = new Operation();
		operation.setDescription("OpenAPI Specification in YAML format.");

		final ApiResponse okResponse = new ApiResponse()
			.description(operation.getDescription())
			.content(new Content()
				.addMediaType(MimeTypes.APPLICATION_YAML, createMediaType(DataTypesConverter.getOpenApiScalar(String.class))));

		operation.setResponses(new ApiResponses().addApiResponse(STATUS_CODE_OK, okResponse));

		final var pathItem = new PathItem();
		pathItem.setGet(operation);
		catalogCtx.registerPath(UrlPathCreator.createBaseUrlPathToCatalog(catalogCtx.getCatalog()), pathItem);
		catalogCtx.getRestApiHandlerRegistrar().registerOpenApiSchemaHandler(pathItem);
	}

	public void buildAndAddCollectionsPathItem(@Nonnull CatalogSchemaBuildingContext catalogCtx) {
		final var operation = new Operation();
		final OpenApiSimpleType collectionsObject = createCollectionsObject(catalogCtx);
		operation.setDescription(COLLECTIONS.description());
		operation.addParametersItem(ParamDescriptor.ENTITY_COUNT
			.to(operationQueryParameterBuilderTransformer)
			.type(DataTypesConverter.getOpenApiScalar(Boolean.class))
			.build()
			.toParameter());

		final var apiResponses = new ApiResponses();
		createAndAddOkResponse(apiResponses, collectionsObject);
		createAndAddAllErrorResponses(apiResponses, typeRefTo(ErrorDescriptor.THIS.name()));
		operation.setResponses(apiResponses);

		final var pathItem = new PathItem();
		pathItem.setGet(operation);
		catalogCtx.registerPath(UrlPathCreator.createBaseUrlPathToCatalog(catalogCtx.getCatalog()) + UrlPathCreator.createBaseUrlPathToCollections(), pathItem);
		catalogCtx.getRestApiHandlerRegistrar().registerCollectionsHandler(CatalogDataApiRootDescriptor.COLLECTIONS.operation(), pathItem);
	}

	public void buildAndUnknownSingleEntityPathItem(@Nonnull CatalogSchemaBuildingContext catalogCtx,
	                                                @Nonnull List<OpenApiTypeReference> entityObjects,
	                                                @Nonnull List<GlobalAttributeSchemaContract> globallyUniqueAttributes,
	                                                boolean localizedUrl) {
		if (entityObjects.isEmpty()) {
			return;
		}

		final OpenApiObject unknownEntityObject = createUnknownEntityObject(entityObjects);

		final var operation = new Operation();
		operation.setDescription(unknownEntityObject.getDescription());

		globallyUniqueAttributes.forEach(arg ->
			operation.addParametersItem(newQueryParameter()
				.name(arg.getNameVariant(ARGUMENT_NAME_NAMING_CONVENTION))
				.description(arg.getDescription())
				.deprecationNotice(arg.getDeprecationNotice())
				.type(DataTypesConverter.getOpenApiScalar(arg.getPlainType()))
				.build()
				.toParameter())
		);

		createAndAddFetchParamsForUnknownEntity(operation, localizedUrl);

		final var apiResponses = new ApiResponses();
		createAndAddOkResponse(apiResponses, unknownEntityObject);
		createAndAddAllErrorResponses(apiResponses, typeRefTo(ErrorDescriptor.THIS.name()));
		//makes sense only when getting single entity
		final var notFoundAllowedResponse = createSchemaResponse(typeRefTo(ErrorDescriptor.THIS.name()));
		notFoundAllowedResponse.setDescription("Requested entity wasn't found");
		apiResponses.addApiResponse(STATUS_CODE_NOT_FOUND, notFoundAllowedResponse);

		operation.setResponses(apiResponses);

		final var pathItem = new PathItem();
		pathItem.setGet(operation);
		catalogCtx.registerPath(UrlPathCreator.createBaseUrlPathToCatalog(catalogCtx.getCatalog()) + UrlPathCreator.createUrlPathToUnknownEntity(localizedUrl), pathItem);
		catalogCtx.getRestApiHandlerRegistrar().registerUnknownEntityHandler(localizedUrl, pathItem);
	}

	public void buildAndUnknownEntityListPathItem(@Nonnull CatalogSchemaBuildingContext catalogCtx,
	                                              @Nonnull List<OpenApiTypeReference> entityObjects,
	                                              @Nonnull List<GlobalAttributeSchemaContract> globallyUniqueAttributes,
	                                              boolean localizedUrl) {
		if (entityObjects.isEmpty()) {
			return;
		}

		final OpenApiObject unknownEntityObject = createUnknownEntityObject(entityObjects);

		final var operation = new Operation();
		operation.setDescription(unknownEntityObject.getDescription());

		operation.addParametersItem(ParamDescriptor.LIMIT
			.to(operationQueryParameterBuilderTransformer)
			.build()
			.toParameter());

		globallyUniqueAttributes.forEach(arg ->
			operation.addParametersItem(newQueryParameter()
				.name(arg.getNameVariant(ARGUMENT_NAME_NAMING_CONVENTION))
				.description(arg.getDescription())
				.deprecationNotice(arg.getDeprecationNotice())
				.type(arrayOf(DataTypesConverter.getOpenApiScalar(arg.getPlainType())))
				.build()
				.toParameter())
		);

		createAndAddFetchParamsForUnknownEntity(operation, localizedUrl);

		final var apiResponses = new ApiResponses();
		createAndAddOkResponse(apiResponses, unknownEntityObject);
		createAndAddAllErrorResponses(apiResponses, typeRefTo(ErrorDescriptor.THIS.name()));
		operation.setResponses(apiResponses);

		final var pathItem = new PathItem();
		pathItem.setGet(operation);
		catalogCtx.registerPath(UrlPathCreator.createBaseUrlPathToCatalog(catalogCtx.getCatalog()) + UrlPathCreator.createUrlPathToUnknownEntityList(localizedUrl), pathItem);
		catalogCtx.getRestApiHandlerRegistrar().registerUnknownEntityListHandler(localizedUrl, pathItem);
	}

	public void buildAndAddUpsertMutationOperationIntoPathItem(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingContext,
	                                                           @Nonnull OpenApiTypeReference requestObject,
	                                                           @Nonnull PathItem pathItem,
	                                                           boolean withPrimaryKeyInPath) {
		final OpenApiTypeReference entityObject = entitySchemaBuildingContext.getLocalizedEntityObject();

		final var requestBody = new RequestBody();
		requestBody.setContent(createApplicationJsonContent(createMediaType(requestObject)));

		final var operation = new Operation();
		if (withPrimaryKeyInPath) {
			operation.addParametersItem(UpsertEntityMutationHeaderDescriptor.PRIMARY_KEY
				.to(operationPathParameterBuilderTransformer)
				.type(DataTypesConverter.getOpenApiScalar(Integer.class, true))
				.build()
				.toParameter());
		}
		operation.setRequestBody(requestBody);
		operation.setDescription(String.format(CatalogDataApiRootDescriptor.ENTITY_UPSERT.description(), entitySchemaBuildingContext.getSchema().getName()));
		if (entitySchemaBuildingContext.getSchema().getDeprecationNotice() != null) {
			operation.setDeprecated(Boolean.TRUE);
		}

		final var apiResponses = new ApiResponses();
		createAndAddOkResponse(apiResponses, entityObject);
		createAndAddAllErrorResponses(apiResponses, typeRefTo(ErrorDescriptor.THIS.name()));
		operation.setResponses(apiResponses);

		if (withPrimaryKeyInPath) {
			pathItem.setPut(operation);
		} else {
			pathItem.setPost(operation);
		}

		entitySchemaBuildingContext.getCatalogCtx().getRestApiHandlerRegistrar().registerEntityUpsertHandler(entitySchemaBuildingContext, withPrimaryKeyInPath, pathItem);
	}

	protected PathItem buildAndAddDeleteEntitiesByQueryPathItem(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingContext) {
		final var requestBody = new RequestBody();
		final OpenApiSimpleType filterByInputObject = entitySchemaBuildingContext.getFilterByInputObject();

		final RequireSchemaBuilder requireSchemaBuilder = new RequireSchemaBuilder(entitySchemaBuildingContext.getConstraintSchemaBuildingCtx(),
			entitySchemaBuildingContext.getSchema().getName(), RequireSchemaBuilder.ALLOWED_CONSTRAINTS_FOR_DELETE);

		final var requestListSchema = createRequestBodyObject(
			entitySchemaBuildingContext,
			filterByInputObject,
			entitySchemaBuildingContext.getOrderByInputObject(),
			requireSchemaBuilder.build()
		);
		requestBody.setContent(createApplicationJsonContent(createMediaType(requestListSchema)));

		final var operation = new Operation();
		operation.setRequestBody(requestBody);

		operation.setDescription(String.format(CatalogDataApiRootDescriptor.ENTITY_DELETE.description(), entitySchemaBuildingContext.getSchema().getName()));
		if (entitySchemaBuildingContext.getSchema().getDeprecationNotice() != null) {
			operation.setDeprecated(Boolean.TRUE);
		}

		final var apiResponses = new ApiResponses();
		createSchemaArrayAndAddOkResponse(apiResponses, entitySchemaBuildingContext.getLocalizedEntityObject());
		createAndAddAllErrorResponses(apiResponses, typeRefTo(ErrorDescriptor.THIS.name()));
		operation.setResponses(apiResponses);

		final var pathItem = new PathItem();
		pathItem.setDelete(operation);

		entitySchemaBuildingContext.getCatalogCtx().registerPath(UrlPathCreator.createBaseUrlPathToCatalog(entitySchemaBuildingContext.getCatalogCtx().getCatalog()) +
			UrlPathCreator.createUrlPathToEntityMutation(entitySchemaBuildingContext, false), pathItem);
		entitySchemaBuildingContext.getCatalogCtx().getRestApiHandlerRegistrar().registerEntityListDeleteHandler(entitySchemaBuildingContext, pathItem);
		return pathItem;
	}

	public PathItem buildAndAddDeleteSingleEntityPathItem(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingContext) {
		final var operation = new Operation();

		final List<OpenApiOperationParameter> pathParameters = new LinkedList<>();
		pathParameters.add(DeleteEntitiesMutationHeaderDescriptor.PRIMARY_KEY
			.to(operationPathParameterBuilderTransformer)
			.type(DataTypesConverter.getOpenApiScalar(Integer.class, true))
			.build());
		pathParameters.addAll(buildSingleEntityFetchParameters(entitySchemaBuildingContext.getSchema()));
		operation.parameters(pathParameters.stream().map(OpenApiOperationParameter::toParameter).toList());

		operation.setDescription(String.format(CatalogDataApiRootDescriptor.ENTITY_DELETE.description(), entitySchemaBuildingContext.getSchema().getName()));
		if (entitySchemaBuildingContext.getSchema().getDeprecationNotice() != null) {
			operation.setDeprecated(Boolean.TRUE);
		}

		final var apiResponses = new ApiResponses();
		createAndAddOkResponse(apiResponses, entitySchemaBuildingContext.getLocalizedEntityObject());
		createAndAddAllErrorResponses(apiResponses, typeRefTo(ErrorDescriptor.THIS.name()));
		operation.setResponses(apiResponses);

		final var pathItem = new PathItem();
		pathItem.setDelete(operation);

		entitySchemaBuildingContext.getCatalogCtx().registerPath(UrlPathCreator.createBaseUrlPathToCatalog(entitySchemaBuildingContext.getCatalogCtx().getCatalog()) +
			UrlPathCreator.createUrlPathToEntityMutation(entitySchemaBuildingContext, true), pathItem);
		entitySchemaBuildingContext.getCatalogCtx().getRestApiHandlerRegistrar().registerEntityDeleteHandler(entitySchemaBuildingContext, pathItem);
		return pathItem;
	}

	public void buildAndAddGetEntitySchemaPathItem(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();
		final OpenApiTypeReference entitySchemaObject = typeRefTo(EntitySchemaDescriptor.THIS_SPECIFIC.name(entitySchema));
		final Operation operation = new Operation();
		operation.setDescription(String.format(CatalogSchemaApiRootDescriptor.GET_ENTITY_SCHEMA.description(), entitySchema.getName()));
		if (entitySchema.getDeprecationNotice() != null) {
			operation.setDeprecated(Boolean.TRUE);
		}

		final ApiResponses apiResponses = new ApiResponses();
		createAndAddOkResponse(apiResponses, entitySchemaObject);
		createAndAddAllErrorResponses(apiResponses, typeRefTo(ErrorDescriptor.THIS.name()));

		final ApiResponse notFoundAllowedResponse = createSchemaResponse(typeRefTo(ErrorDescriptor.THIS.name()));
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
		entitySchemaBuildingCtx.getCatalogCtx().getRestApiHandlerRegistrar().registerEntitySchemaHandler(entitySchemaBuildingCtx, pathItem);
	}

	private void createAndAddFetchParamsForUnknownEntity(@Nonnull Operation operation, boolean localizedUrl) {
		//build fetch params
		if (localizedUrl) {
			operation.addParametersItem(ParamDescriptor.REQUIRED_LOCALE.to(operationPathParameterBuilderTransformer).build().toParameter());
		} else {
			operation.addParametersItem(ParamDescriptor.LOCALE.to(operationQueryParameterBuilderTransformer).build().toParameter());
		}
		operation.addParametersItem(ParamDescriptor.DATA_IN_LOCALES.to(operationQueryParameterBuilderTransformer).build().toParameter());
		operation.addParametersItem(ParamDescriptor.FETCH_ALL.to(operationQueryParameterBuilderTransformer).build().toParameter());
		operation.addParametersItem(ParamDescriptor.BODY_FETCH.to(operationQueryParameterBuilderTransformer).build().toParameter());
		operation.addParametersItem(ParamDescriptor.ASSOCIATED_DATA_CONTENT_ALL.to(operationQueryParameterBuilderTransformer).build().toParameter());
		operation.addParametersItem(ParamDescriptor.ATTRIBUTE_CONTENT_ALL.to(operationQueryParameterBuilderTransformer).build().toParameter());
		operation.addParametersItem(ParamDescriptor.PRICE_CONTENT.to(operationQueryParameterBuilderTransformer).build().toParameter());
		operation.addParametersItem(ParamDescriptor.REFERENCE_CONTENT_ALL.to(operationQueryParameterBuilderTransformer).build().toParameter());
	}

	@Nonnull
	private OpenApiObject createUnknownEntityObject(@Nonnull List<OpenApiTypeReference> entityObjects) {
		final OpenApiObject.Builder objectBuilder = newObject()
			.name("UnknownEntity") // todo lho descriptor for it
			.unionType(OpenApiObjectUnionType.ONE_OF)
			.unionDiscriminator("type");// todo lho descriptor for it
		entityObjects.forEach(objectBuilder::unionObject);
		return objectBuilder.build();
	}

	@Nonnull
	private List<OpenApiOperationParameter> buildSingleEntityParameters(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingCtx, boolean localeInPath) {
		final EntitySchemaContract entitySchema = entitySchemaBuildingCtx.getSchema();

		final List<OpenApiOperationParameter> parameters = new LinkedList<>();

		parameters.add(ParamDescriptor.PRIMARY_KEY.to(operationPathParameterBuilderTransformer).build());

		// build locale argument
		if (!entitySchema.getLocales().isEmpty()) {
			final OpenApiTypeReference localeEnum = typeRefTo(ENTITY_LOCALE_ENUM.name(entitySchemaBuildingCtx.getSchema()));

			if (localeInPath) {
				final OpenApiOperationParameter localeParameter = ParamDescriptor.REQUIRED_LOCALE
					.to(operationPathParameterBuilderTransformer)
					.type(localeEnum)
					.build();
				parameters.add(localeParameter);
			} else {
				final OpenApiOperationParameter dataInLocalesParameter = ParamDescriptor.DATA_IN_LOCALES
					.to(operationQueryParameterBuilderTransformer)
					.type(arrayOf(localeEnum))
					.build();
				parameters.add(dataInLocalesParameter);

				final OpenApiOperationParameter localeParameter = ParamDescriptor.LOCALE
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

	private List<OpenApiOperationParameter> buildSingleEntityFetchParameters(@Nonnull EntitySchemaContract entitySchema) {
		final List<OpenApiOperationParameter> parameters = new LinkedList<>();
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
	private OpenApiSimpleType createCollectionsObject(@Nonnull CatalogSchemaBuildingContext catalogCtx) {
		final OpenApiTypeReference collectionObject = catalogCtx.registerType(CollectionDescriptor.THIS.to(objectBuilderTransformer).build());
		return arrayOf(collectionObject);
	}


	@Nonnull
	public OpenApiObject createRequestBodyObject(@Nonnull OpenApiEntitySchemaBuildingContext entitySchemaBuildingContext,
	                                             @Nonnull OpenApiSimpleType filterContainer,
	                                             @Nonnull OpenApiSimpleType orderContainer,
	                                             @Nullable OpenApiSimpleType requireContainer) {
		// todo lho after localized distinctions are refactored, this could be registered as well
		final OpenApiObject.Builder objectBuilder = QueryRequestBodyDescriptor.THIS
			.to(objectBuilderTransformer)
			.name(QueryRequestBodyDescriptor.THIS.name(entitySchemaBuildingContext.getSchema()))
			.property(QueryRequestBodyDescriptor.FILTER_BY.to(propertyBuilderTransformer).type(filterContainer))
			.property(QueryRequestBodyDescriptor.ORDER_BY.to(propertyBuilderTransformer).type(orderContainer));

		if(requireContainer != null) {
			objectBuilder.property(QueryRequestBodyDescriptor.REQUIRE.to(propertyBuilderTransformer).type(requireContainer));
		}
		return objectBuilder.build();
	}
}
