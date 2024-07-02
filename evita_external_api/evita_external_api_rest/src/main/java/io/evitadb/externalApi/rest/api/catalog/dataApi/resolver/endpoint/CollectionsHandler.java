/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import com.linecorp.armeria.common.HttpMethod;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.rest.api.catalog.dataApi.dto.CollectionPointer;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.header.CollectionsEndpointHeaderDescriptor;
import io.evitadb.externalApi.rest.api.catalog.resolver.endpoint.CatalogRestHandlingContext;
import io.evitadb.externalApi.rest.io.JsonRestHandler;
import io.evitadb.externalApi.rest.io.RestEndpointExecutionContext;
import io.evitadb.externalApi.rest.metric.event.request.ExecutedEvent;
import io.evitadb.externalApi.rest.io.RestEndpointExchange;

import javax.annotation.Nonnull;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * This handler is used to get list of names (and counts) of existing collections withing one catalog.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
public class CollectionsHandler extends JsonRestHandler<CatalogRestHandlingContext> {

	public CollectionsHandler(@Nonnull CatalogRestHandlingContext restHandlingContext) {
		super(restHandlingContext);
	}

	@Nonnull
	@Override
	protected CompletableFuture<EndpointResponse> doHandleRequest(@Nonnull RestEndpointExchange exchange) {
		final ExecutedEvent requestExecutedEvent = executionContext.requestExecutedEvent();

		final Map<String, Object> parametersFromRequest = getParametersFromRequest(exchange);
		final Boolean withCounts = (Boolean) parametersFromRequest.get(CollectionsEndpointHeaderDescriptor.ENTITY_COUNT.name());

		requestExecutedEvent.finishInputDeserialization();

		final List<CollectionPointer> collections = requestExecutedEvent.measureInternalEvitaDBExecution(() ->
			executionContext.session()
				.getAllEntityTypes()
				.stream()
				.map(entityType -> new CollectionPointer(
					entityType,
					withCounts != null && withCounts ? executionContext.session().getEntityCollectionSize(entityType) : null
				))
				.toList());
		requestExecutedEvent.finishOperationExecution();

		return CompletableFuture.supplyAsync(() -> {
			final Object result = convertResultIntoSerializableObject(executionContext, collections);
			requestExecutedEvent.finishResultSerialization();

			return new SuccessEndpointResponse(result);
		});
	}

	@Nonnull
	@Override
	public Set<String> getSupportedHttpMethods() {
		return Set.of(HttpMethod.GET.name());
	}

	@Nonnull
	@Override
	public LinkedHashSet<String> getSupportedResponseContentTypes() {
		return DEFAULT_SUPPORTED_CONTENT_TYPES;
	}
}
