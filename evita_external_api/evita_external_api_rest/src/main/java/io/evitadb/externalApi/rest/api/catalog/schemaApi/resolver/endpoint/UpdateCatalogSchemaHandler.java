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

package io.evitadb.externalApi.rest.api.catalog.schemaApi.resolver.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.linecorp.armeria.common.HttpMethod;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.LocalCatalogSchemaMutationInputAggregateConverter;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.rest.api.catalog.resolver.endpoint.CatalogRestHandlingContext;
import io.evitadb.externalApi.rest.api.catalog.resolver.mutation.RestMutationObjectMapper;
import io.evitadb.externalApi.rest.api.catalog.resolver.mutation.RestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.rest.api.catalog.schemaApi.dto.CreateOrUpdateEntitySchemaRequestData;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.externalApi.rest.io.RestEndpointExecutionContext;
import io.evitadb.externalApi.rest.metric.event.request.ExecutedEvent;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Handles update request for catalog schema.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public class UpdateCatalogSchemaHandler extends CatalogSchemaHandler {

	@Nonnull private final LocalCatalogSchemaMutationInputAggregateConverter mutationAggregateResolver;

	public UpdateCatalogSchemaHandler(@Nonnull CatalogRestHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);
		this.mutationAggregateResolver = new LocalCatalogSchemaMutationInputAggregateConverter(
			new RestMutationObjectMapper(restApiHandlingContext.getObjectMapper()),
			RestMutationResolvingExceptionFactory.INSTANCE
		);
	}

	@Override
	protected boolean modifiesData() {
		return true;
	}

	@Override
	@Nonnull
	protected CompletableFuture<EndpointResponse> doHandleRequest(@Nonnull RestEndpointExecutionContext executionContext) {
		final ExecutedEvent requestExecutedEvent = executionContext.requestExecutedEvent();
		return parseRequestBody(executionContext, CreateOrUpdateEntitySchemaRequestData.class)
			.thenApply(requestData -> {
				requestExecutedEvent.finishInputDeserialization();

				final List<LocalCatalogSchemaMutation> schemaMutations = requestExecutedEvent.measureInternalEvitaDBInputReconstruction(() -> {
					final List<LocalCatalogSchemaMutation> convertedSchemaMutations = new LinkedList<>();
					final JsonNode inputMutations = requestData.getMutations()
						.orElseThrow(() -> new RestInvalidArgumentException("Mutations are not set in request data."));
					for (Iterator<JsonNode> schemaMutationsIterator = inputMutations.elements(); schemaMutationsIterator.hasNext(); ) {
						convertedSchemaMutations.addAll(this.mutationAggregateResolver.convertFromInput(schemaMutationsIterator.next()));
					}
					return convertedSchemaMutations;
				});

				final CatalogSchemaContract updatedCatalogSchema = requestExecutedEvent.measureInternalEvitaDBExecution(() ->
					executionContext.session().updateAndFetchCatalogSchema(schemaMutations.toArray(LocalCatalogSchemaMutation[]::new)));
				requestExecutedEvent.finishOperationExecution();

				final Object result = convertResultIntoSerializableObject(executionContext, updatedCatalogSchema);
				requestExecutedEvent.finishResultSerialization();

				return new SuccessEndpointResponse(result);
			});
	}

	@Nonnull
	@Override
	public Set<HttpMethod> getSupportedHttpMethods() {
		return Set.of(HttpMethod.PUT);
	}

	@Nonnull
	@Override
	public Set<String> getSupportedRequestContentTypes() {
		return DEFAULT_SUPPORTED_CONTENT_TYPES;
	}
}
