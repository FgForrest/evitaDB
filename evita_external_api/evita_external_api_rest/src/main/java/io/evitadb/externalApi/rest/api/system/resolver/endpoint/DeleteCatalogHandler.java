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

package io.evitadb.externalApi.rest.api.system.resolver.endpoint;

import com.linecorp.armeria.common.HttpMethod;
import io.evitadb.api.CatalogContract;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.NotFoundEndpointResponse;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.rest.api.system.model.CatalogsHeaderDescriptor;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.io.JsonRestHandler;
import io.evitadb.externalApi.rest.io.RestEndpointExecutionContext;
import io.evitadb.externalApi.rest.metric.event.request.ExecutedEvent;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Deletes single evitaDB catalog by its name.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class DeleteCatalogHandler extends JsonRestHandler<SystemRestHandlingContext> {

	public DeleteCatalogHandler(@Nonnull SystemRestHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);
	}

	@Override
	protected boolean modifiesData() {
		return true;
	}

	@Nonnull
	@Override
	protected CompletableFuture<EndpointResponse> doHandleRequest(@Nonnull RestEndpointExecutionContext executionContext) {
		final ExecutedEvent requestExecutedEvent = executionContext.requestExecutedEvent();

		final Map<String, Object> parameters = getParametersFromRequest(executionContext);
		requestExecutedEvent.finishInputDeserialization();

		final String catalogName = (String) parameters.get(CatalogsHeaderDescriptor.NAME.name());
		return executionContext.executeAsyncInTransactionThreadPool(
			() -> {
				final Optional<CatalogContract> catalog = requestExecutedEvent.measureInternalEvitaDBExecution(() ->
					restHandlingContext.getEvita().getCatalogInstance(catalogName));
				if (catalog.isEmpty()) {
					requestExecutedEvent.finishOperationExecution();
					requestExecutedEvent.finishResultSerialization();
					return new NotFoundEndpointResponse();
				}

				final boolean deleted = requestExecutedEvent.measureInternalEvitaDBExecution(() ->
					restHandlingContext.getEvita().deleteCatalogIfExists(catalog.get().getName()));
				Assert.isPremiseValid(
					deleted,
					() -> new RestInternalError("Could not delete catalog `" + catalog.get().getName() + "`, even though it should exist.")
				);

				requestExecutedEvent.finishOperationExecution();
				requestExecutedEvent.finishResultSerialization();
				return new SuccessEndpointResponse();
			}
		);
	}

	@Nonnull
	@Override
	public Set<HttpMethod> getSupportedHttpMethods() {
		return Set.of(HttpMethod.DELETE);
	}
}
