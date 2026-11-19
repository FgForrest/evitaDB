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

package io.evitadb.externalApi.rest.api.system.builder;

import io.evitadb.externalApi.api.model.EndpointDescriptor;
import io.evitadb.externalApi.api.system.model.CatalogUnionDescriptor;
import io.evitadb.externalApi.api.system.model.progress.EngineMutationProgressHandleDescriptor;
import io.evitadb.externalApi.rest.api.dataType.DataTypesConverter;
import io.evitadb.externalApi.rest.api.model.PropertyDescriptorToOpenApiOperationPathParameterTransformer;
import io.evitadb.externalApi.rest.api.model.RestRootDescriptor;
import io.evitadb.externalApi.rest.api.openApi.OpenApiSimpleType;
import io.evitadb.externalApi.rest.api.openApi.OpenApiSystemEndpoint;
import io.evitadb.externalApi.rest.api.openApi.OpenApiVoid;
import io.evitadb.externalApi.rest.api.resolver.endpoint.OpenApiSpecificationHandler;
import io.evitadb.externalApi.rest.api.system.model.CatalogsHeaderDescriptor;
import io.evitadb.externalApi.rest.api.system.model.CreateCatalogRequestDescriptor;
import io.evitadb.externalApi.rest.api.system.model.LivenessDescriptor;
import io.evitadb.externalApi.rest.api.system.model.SystemRootDescriptor;
import io.evitadb.externalApi.rest.api.system.model.UpdateCatalogRequestDescriptor;
import io.evitadb.externalApi.rest.api.system.resolver.endpoint.CreateCatalogHandler;
import io.evitadb.externalApi.rest.api.system.resolver.endpoint.DeleteCatalogHandler;
import io.evitadb.externalApi.rest.api.system.resolver.endpoint.DuplicateCatalogHandler;
import io.evitadb.externalApi.rest.api.system.resolver.endpoint.EngineMutationProgressStreamHandler;
import io.evitadb.externalApi.rest.api.system.resolver.endpoint.GetCatalogHandler;
import io.evitadb.externalApi.rest.api.system.resolver.endpoint.ListCatalogsHandler;
import io.evitadb.externalApi.rest.api.system.resolver.endpoint.LivenessHandler;
import io.evitadb.externalApi.rest.api.system.resolver.endpoint.ReplaceCatalogHandler;
import io.evitadb.externalApi.rest.api.system.resolver.endpoint.SystemRestHandlingContext;
import io.evitadb.externalApi.rest.api.system.resolver.endpoint.UpdateCatalogHandler;
import io.evitadb.externalApi.rest.api.system.resolver.endpoint.ChangeSystemCaptureStreamHandler;
import io.evitadb.externalApi.rest.io.RestEndpointHandler;
import io.swagger.v3.oas.models.PathItem.HttpMethod;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.function.Function;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiAccepted.accepted;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiArray.arrayOf;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiNonNull.nonNull;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiSystemEndpoint.newSystemEndpoint;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;

/**
 * Creates OpenAPI {@link io.evitadb.externalApi.rest.api.openApi.OpenApiEndpoint} for each schema API endpoint.
 * for requests processing.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
public class SystemEndpointBuilder {

	/**
	 * Path item introducing a catalog action.
	 */
	private static final String ACTIONS_PATH_ITEM = "actions";
	/**
	 * Path item of the `duplicate` catalog action.
	 */
	private static final String DUPLICATE_ACTION = "duplicate";
	/**
	 * Path item of the `replace` catalog action.
	 */
	private static final String REPLACE_ACTION = "replace";
	/**
	 * Path item of the engine mutation progress WebSocket under `catalogs-async`.
	 */
	private static final String PROGRESS_PATH_ITEM = "progress";

	@Nonnull private final PropertyDescriptorToOpenApiOperationPathParameterTransformer operationPathParameterBuilderTransformer;

	@Nonnull
	public OpenApiSystemEndpoint buildOpenApiSpecificationEndpoint() {
		return newSystemEndpoint()
			.path(p -> p
				.staticItem(RestRootDescriptor.OPEN_API_SPECIFICATION.urlPathItem()))
			.method(HttpMethod.GET)
			.operationId(RestRootDescriptor.OPEN_API_SPECIFICATION.operation())
			.description(RestRootDescriptor.OPEN_API_SPECIFICATION.description())
			.successResponse(nonNull(DataTypesConverter.getOpenApiScalar(String.class)))
			.handler(OpenApiSpecificationHandler::new)
			.build();
	}

	@Nonnull
	public OpenApiSystemEndpoint buildLivenessEndpoint() {
		return newSystemEndpoint()
			.path(p -> p
				.staticItem(SystemRootDescriptor.LIVENESS.urlPathItem()))
			.method(HttpMethod.GET)
			.operationId(SystemRootDescriptor.LIVENESS.operation())
			.description(SystemRootDescriptor.LIVENESS.description())
			.successResponse(nonNull(typeRefTo(LivenessDescriptor.THIS.name())))
			.handler(LivenessHandler::new)
			.build();
	}

	@Nonnull
	public OpenApiSystemEndpoint buildGetCatalogEndpoint() {
		return newSystemEndpoint()
			.path(p -> p
				.staticItem(SystemRootDescriptor.GET_CATALOG.urlPathItem())
				.paramItem(CatalogsHeaderDescriptor.NAME.to(this.operationPathParameterBuilderTransformer)))
			.method(HttpMethod.GET)
			.operationId(SystemRootDescriptor.GET_CATALOG.operation())
			.description(SystemRootDescriptor.GET_CATALOG.description())
			.successResponse(typeRefTo(CatalogUnionDescriptor.THIS.name()))
			.handler(GetCatalogHandler::new)
			.build();
	}

	@Nonnull
	public OpenApiSystemEndpoint buildListCatalogsEndpoint() {
		return newSystemEndpoint()
			.path(p -> p
				.staticItem(SystemRootDescriptor.LIST_CATALOGS.urlPathItem()))
			.method(HttpMethod.GET)
			.operationId(SystemRootDescriptor.LIST_CATALOGS.operation())
			.description(SystemRootDescriptor.LIST_CATALOGS.description())
			.successResponse(nonNull(arrayOf(typeRefTo(CatalogUnionDescriptor.THIS.name()))))
			.handler(ListCatalogsHandler::new)
			.build();
	}

	@Nonnull
	public OpenApiSystemEndpoint buildCreateCatalogEndpoint() {
		return newSystemEndpoint()
			.path(p -> p
				.staticItem(SystemRootDescriptor.CREATE_CATALOG.urlPathItem()))
			.method(HttpMethod.POST)
			.operationId(SystemRootDescriptor.CREATE_CATALOG.operation())
			.description(SystemRootDescriptor.CREATE_CATALOG.description())
			.requestBody(typeRefTo(CreateCatalogRequestDescriptor.THIS.name()))
			.successResponse(nonNull(typeRefTo(CatalogUnionDescriptor.THIS.name())))
			.handler(CreateCatalogHandler::new)
			.build();
	}

	/**
	 * Builds the blocking (`/catalogs/{name}`) or the asynchronous (`/catalogs-async/{name}`) catalog update endpoint.
	 */
	@Nonnull
	public OpenApiSystemEndpoint buildUpdateCatalogEndpoint(boolean async) {
		final EndpointDescriptor endpoint = async ?
			SystemRootDescriptor.UPDATE_CATALOG_ASYNC : SystemRootDescriptor.UPDATE_CATALOG;
		return newSystemEndpoint()
			.path(p -> p
				.staticItem(endpoint.urlPathItem())
				.paramItem(CatalogsHeaderDescriptor.NAME.to(this.operationPathParameterBuilderTransformer)))
			.method(HttpMethod.PATCH)
			.operationId(endpoint.operation())
			.description(endpoint.description())
			.requestBody(typeRefTo(UpdateCatalogRequestDescriptor.THIS.name()))
			.successResponse(engineOperationResponse(async))
			.handler(ctx -> new UpdateCatalogHandler(ctx, async))
			.build();
	}

	/**
	 * Builds the blocking (`/catalogs/{name}`) or the asynchronous (`/catalogs-async/{name}`) catalog delete endpoint.
	 */
	@Nonnull
	public OpenApiSystemEndpoint buildDeleteCatalogEndpoint(boolean async) {
		final EndpointDescriptor endpoint = async ?
			SystemRootDescriptor.DELETE_CATALOG_ASYNC : SystemRootDescriptor.DELETE_CATALOG;
		final OpenApiSystemEndpoint.Builder builder = newSystemEndpoint()
			.path(p -> p
				.staticItem(endpoint.urlPathItem())
				.paramItem(CatalogsHeaderDescriptor.NAME.to(this.operationPathParameterBuilderTransformer)))
			.method(HttpMethod.DELETE)
			.operationId(endpoint.operation())
			.description(endpoint.description())
			.handler(ctx -> new DeleteCatalogHandler(ctx, async));
		if (async) {
			builder.successResponse(accepted(nonNull(typeRefTo(EngineMutationProgressHandleDescriptor.THIS.name()))));
		}
		return builder.build();
	}

	/**
	 * Builds the blocking or the asynchronous `duplicate` catalog action endpoint.
	 */
	@Nonnull
	public OpenApiSystemEndpoint buildDuplicateCatalogEndpoint(boolean async) {
		return buildCatalogActionEndpoint(
			async ? SystemRootDescriptor.DUPLICATE_CATALOG_ASYNC : SystemRootDescriptor.DUPLICATE_CATALOG,
			DUPLICATE_ACTION,
			async,
			ctx -> new DuplicateCatalogHandler(ctx, async)
		);
	}

	/**
	 * Builds the blocking or the asynchronous `replace` catalog action endpoint.
	 */
	@Nonnull
	public OpenApiSystemEndpoint buildReplaceCatalogEndpoint(boolean async) {
		return buildCatalogActionEndpoint(
			async ? SystemRootDescriptor.REPLACE_CATALOG_ASYNC : SystemRootDescriptor.REPLACE_CATALOG,
			REPLACE_ACTION,
			async,
			ctx -> new ReplaceCatalogHandler(ctx, async)
		);
	}

	/**
	 * Builds the WebSocket endpoint streaming the progress of an engine operation.
	 */
	@Nonnull
	public OpenApiSystemEndpoint buildEngineMutationProgressEndpoint() {
		return newSystemEndpoint()
			.path(p -> p
				.staticItem(SystemRootDescriptor.ENGINE_MUTATION_PROGRESS.urlPathItem())
				.staticItem(PROGRESS_PATH_ITEM))
			.method(HttpMethod.GET)
			.operationId(SystemRootDescriptor.ENGINE_MUTATION_PROGRESS.operation())
			.description(SystemRootDescriptor.ENGINE_MUTATION_PROGRESS.description())
			.successResponse(OpenApiVoid.INSTANCE)
			.handler(EngineMutationProgressStreamHandler::new)
			.build();
	}

	/**
	 * Builds an endpoint of a catalog action: `POST /{catalogs|catalogs-async}/{name}/actions/{action}/{newName}`.
	 */
	@Nonnull
	private OpenApiSystemEndpoint buildCatalogActionEndpoint(
		@Nonnull EndpointDescriptor endpoint,
		@Nonnull String action,
		boolean async,
		@Nonnull Function<SystemRestHandlingContext, RestEndpointHandler<SystemRestHandlingContext>> handlerBuilder
	) {
		return newSystemEndpoint()
			.path(p -> p
				.staticItem(endpoint.urlPathItem())
				.paramItem(CatalogsHeaderDescriptor.NAME.to(this.operationPathParameterBuilderTransformer))
				.staticItem(ACTIONS_PATH_ITEM)
				.staticItem(action)
				.paramItem(CatalogsHeaderDescriptor.NEW_NAME.to(this.operationPathParameterBuilderTransformer)))
			.method(HttpMethod.POST)
			.operationId(endpoint.operation())
			.description(endpoint.description())
			.successResponse(engineOperationResponse(async))
			.handler(handlerBuilder)
			.build();
	}

	/**
	 * Response of an engine operation endpoint: the resulting catalog for the blocking variant, `202 Accepted` with
	 * a progress handle for the asynchronous one. Neither is wrapped in `nonNull` as a whole, so that the operation
	 * documents the `404` both answer for a missing catalog.
	 */
	@Nonnull
	private static OpenApiSimpleType engineOperationResponse(boolean async) {
		return async ?
			accepted(nonNull(typeRefTo(EngineMutationProgressHandleDescriptor.THIS.name()))) :
			typeRefTo(CatalogUnionDescriptor.THIS.name());
	}

	@Nonnull
	public OpenApiSystemEndpoint buildChangeSystemCaptureEndpoint() {
		return newSystemEndpoint()
			.path(p -> p
				.staticItem(SystemRootDescriptor.CHANGE_SYSTEM_CAPTURE.urlPathItem()))
			.method(HttpMethod.GET)
			.operationId(SystemRootDescriptor.CHANGE_SYSTEM_CAPTURE.operation())
			.description(SystemRootDescriptor.CHANGE_SYSTEM_CAPTURE.description())
			.successResponse(OpenApiVoid.INSTANCE)
			.handler(ChangeSystemCaptureStreamHandler::new)
			.build();
	}
}
