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

package io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.HttpMethod;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.Require;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.MimeTypes;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.rest.api.catalog.dataApi.dto.UpsertEntityUpsertRequestDto;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.EntityUpsertRequestDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.FetchEntityRequestDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.UpsertEntityEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.FilterConstraintResolver;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.OrderConstraintResolver;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.RequireConstraintResolver;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.mutation.RestEntityUpsertMutationConverter;
import io.evitadb.externalApi.rest.api.openApi.SchemaUtils;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.externalApi.rest.io.RestEndpointExecutionContext;
import io.evitadb.externalApi.rest.metric.event.request.ExecutedEvent;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.media.Schema;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handles upsert request for entity.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class UpsertEntityHandler extends EntityHandler<CollectionRestHandlingContext> {

	@Nonnull private final RestEntityUpsertMutationConverter mutationResolver;
	@Nonnull private final RequireConstraintResolver requireConstraintResolver;

	private final boolean withPrimaryKeyInPath;

	public UpsertEntityHandler(@Nonnull CollectionRestHandlingContext restApiHandlingContext, boolean withPrimaryKeyInPath) {
		super(restApiHandlingContext);
		this.mutationResolver = new RestEntityUpsertMutationConverter(
			restApiHandlingContext.getObjectMapper(),
			restApiHandlingContext.getEntitySchema()
		);
		final FilterConstraintResolver filterConstraintResolver = new FilterConstraintResolver(restApiHandlingContext.getCatalogSchema());
		this.requireConstraintResolver = new RequireConstraintResolver(
			restApiHandlingContext.getCatalogSchema(),
			new AtomicReference<>(filterConstraintResolver),
			new AtomicReference<>(new OrderConstraintResolver(
				restApiHandlingContext.getCatalogSchema(),
				new AtomicReference<>(filterConstraintResolver)
			))
		);
		this.withPrimaryKeyInPath = withPrimaryKeyInPath;
	}

	@Override
	protected boolean modifiesData() {
		return true;
	}

	@Override
	@Nonnull
	protected CompletableFuture<EndpointResponse> doHandleRequest(@Nonnull RestEndpointExecutionContext executionContext) {
		final ExecutedEvent requestExecutedEvent = executionContext.requestExecutedEvent();
		return parseRequestBody(executionContext, UpsertEntityUpsertRequestDto.class)
			.thenApply(requestData -> {
				final Optional<Object> rawRequire = requestData.getRequire().map(it -> deserializeConstraintContainer(EntityUpsertRequestDescriptor.REQUIRE.name(), it));
				requestExecutedEvent.finishInputDeserialization();

				if (this.withPrimaryKeyInPath) {
					final Map<String, Object> parametersFromRequest = getParametersFromRequest(executionContext);
					Assert.isTrue(
						parametersFromRequest.containsKey(UpsertEntityEndpointHeaderDescriptor.PRIMARY_KEY.name()),
						() -> new RestInvalidArgumentException("Primary key is not present in request's URL path.")
					);
					requestData.setPrimaryKey((Integer) parametersFromRequest.get(UpsertEntityEndpointHeaderDescriptor.PRIMARY_KEY.name()));
				}

				final EntityMutation entityMutation = requestExecutedEvent.measureInternalEvitaDBInputReconstruction(() ->
					this.mutationResolver.convertFromInput(
						requestData.getPrimaryKey()
							.orElse(null),
						requestData.getEntityExistence()
							.orElseThrow(() -> new RestInvalidArgumentException("EntityExistence is not set in request data.")),
						requestData.getMutations()
							.orElseThrow(() -> new RestInvalidArgumentException("Mutations are not set in request data."))
					));

				final Optional<EntityContentRequire[]> requires = requestExecutedEvent.measureInternalEvitaDBInputReconstruction(() ->
					rawRequire.flatMap(this::getEntityContentRequires));

				final EntityClassifier upsertedEntity = requestExecutedEvent.measureInternalEvitaDBExecution(() ->
					requires.isPresent()
						? executionContext.session().upsertAndFetchEntity(entityMutation, requires.get())
						: executionContext.session().upsertEntity(entityMutation));
				requestExecutedEvent.finishOperationExecution();

				final Object result = convertResultIntoSerializableObject(executionContext, upsertedEntity);
				requestExecutedEvent.finishResultSerialization();

				return new SuccessEndpointResponse(result);
			});
	}

	@Nonnull
	@Override
	public Set<HttpMethod> getSupportedHttpMethods() {
		return Set.of(this.withPrimaryKeyInPath ? HttpMethod.PUT : HttpMethod.POST);
	}

	@Nonnull
	@Override
	public Set<String> getSupportedRequestContentTypes() {
		return DEFAULT_SUPPORTED_CONTENT_TYPES;
	}

	@Nonnull
	private Optional<EntityContentRequire[]> getEntityContentRequires(@Nonnull Object rawRequire) {
		return Optional.ofNullable((Require) this.requireConstraintResolver.resolve(this.restHandlingContext.getEntityType(), FetchEntityRequestDescriptor.REQUIRE.name(), rawRequire))
			.flatMap(require -> Arrays.stream(require.getChildren())
				.filter(EntityFetch.class::isInstance)
				.findFirst()
				.map(entityFetch -> {
					final RequireConstraint[] children = ((EntityFetch) entityFetch).getChildren();
					final EntityContentRequire[] requires = new EntityContentRequire[children.length];
					for (int i = 0; i < children.length; i++) {
						requires[i] = (EntityContentRequire) children[i];
					}
					return requires;
				})
			);
	}

	@Nullable
	private Object deserializeConstraintContainer(@Nonnull String key, Object value) {
		Assert.isPremiseValid(
			value instanceof JsonNode,
			() -> new RestInternalError("Input value is not a JSON node. Instead it is `" + value.getClass().getName() + "`.")
		);

		//noinspection rawtypes
		final Schema rootSchema = (Schema) SchemaUtils.getTargetSchema(
				this.restHandlingContext.getEndpointOperation()
					.getRequestBody()
					.getContent()
					.get(MimeTypes.APPLICATION_JSON)
					.getSchema(),
				this.restHandlingContext.getOpenApi()
			)
			.getProperties()
			.get(key);

		try {
			return this.dataDeserializer.deserializeTree(
				SchemaUtils.getTargetSchema(rootSchema, this.restHandlingContext.getOpenApi()),
				(JsonNode) value
			);
		} catch (Exception e) {
			throw new RestInvalidArgumentException("Could not parse query: " + e.getMessage());
		}
	}
}
