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

import com.linecorp.armeria.common.HttpMethod;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.NotFoundEndpointResponse;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint.CollectionRestHandlingContext;
import io.evitadb.externalApi.rest.io.RestEndpointExecutionContext;
import io.evitadb.externalApi.rest.metric.event.request.ExecutedEvent;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Handles request for fetching entity schema
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public class GetEntitySchemaHandler extends EntitySchemaHandler {

	public GetEntitySchemaHandler(@Nonnull CollectionRestHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);
	}

	@Override
	@Nonnull
	protected CompletableFuture<EndpointResponse> doHandleRequest(@Nonnull RestEndpointExecutionContext executionContext) {
		return executionContext.executeAsyncInRequestThreadPool(
			() -> {
				final ExecutedEvent requestExecutedEvent = executionContext.requestExecutedEvent();
				requestExecutedEvent.finishInputDeserialization();

				final Optional<SealedEntitySchema> entitySchema = requestExecutedEvent.measureInternalEvitaDBExecution(() ->
					executionContext.session().getEntitySchema(this.restHandlingContext.getEntityType()));
				requestExecutedEvent.finishOperationExecution();

				return entitySchema
					.map(it -> (EndpointResponse) new SuccessEndpointResponse(convertResultIntoSerializableObject(executionContext, it)))
					.orElse(new NotFoundEndpointResponse());
			}
		);
	}

	@Nonnull
	@Override
	public Set<HttpMethod> getSupportedHttpMethods() {
		return Set.of(HttpMethod.GET);
	}
}
