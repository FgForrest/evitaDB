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

package io.evitadb.externalApi.rest.api.system.resolver.endpoint;

import com.linecorp.armeria.common.HttpMethod;
import io.evitadb.api.CatalogContract;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.rest.api.system.resolver.serializer.CatalogJsonSerializer;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.io.JsonRestHandler;
import io.evitadb.externalApi.rest.io.RestEndpointExecutionContext;
import io.evitadb.externalApi.rest.metric.event.request.ExecutedEvent;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Returns all evitaDB catalogs.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class ListCatalogsHandler extends JsonRestHandler<SystemRestHandlingContext> {

	@Nonnull
	private final CatalogJsonSerializer catalogJsonSerializer;

	public ListCatalogsHandler(@Nonnull SystemRestHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);
		this.catalogJsonSerializer = new CatalogJsonSerializer(restApiHandlingContext);
	}

	@Nonnull
	@Override
	protected CompletableFuture<EndpointResponse> doHandleRequest(@Nonnull RestEndpointExecutionContext executionContext) {
		return executionContext.executeAsyncInRequestThreadPool(
			() -> {
				final ExecutedEvent requestExecutedEvent = executionContext.requestExecutedEvent();
				requestExecutedEvent.finishInputDeserialization();

				final Collection<CatalogContract> catalogs = requestExecutedEvent.measureInternalEvitaDBExecution(() ->
					this.restHandlingContext.getEvita().getCatalogs());
				requestExecutedEvent.finishOperationExecution();

				final Object result = convertResultIntoSerializableObject(executionContext, catalogs);
				requestExecutedEvent.finishResultSerialization();

				return new SuccessEndpointResponse(result);
			}
		);
	}

	@Nonnull
	@Override
	public Set<HttpMethod> getSupportedHttpMethods() {
		return Set.of(HttpMethod.GET);
	}

	@Nonnull
	@Override
	public LinkedHashSet<String> getSupportedResponseContentTypes() {
		return DEFAULT_SUPPORTED_CONTENT_TYPES;
	}

	@Nonnull
	@Override
	protected Object convertResultIntoSerializableObject(@Nonnull RestEndpointExecutionContext exchange, @Nonnull Object catalogs) {
		// Collection<CatalogContract>
		Assert.isPremiseValid(
			catalogs instanceof Collection,
			() -> new RestInternalError("Expected collection of catalogs, but got `" + catalogs.getClass().getName() + "`.")
		);
		//noinspection unchecked
		return this.catalogJsonSerializer.serialize((Collection<CatalogContract>) catalogs);
	}
}
