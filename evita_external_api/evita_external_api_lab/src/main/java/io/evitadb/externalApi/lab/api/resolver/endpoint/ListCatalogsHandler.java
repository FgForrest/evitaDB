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

package io.evitadb.externalApi.lab.api.resolver.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
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
 * Returns all known evitaDB catalogs.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class ListCatalogsHandler extends JsonRestHandler<LabApiHandlingContext> {

	@Nonnull
	private final CatalogJsonSerializer catalogJsonSerializer;

	public ListCatalogsHandler(@Nonnull LabApiHandlingContext restApiHandlingContext) {
		super(restApiHandlingContext);
		this.catalogJsonSerializer = new CatalogJsonSerializer(restApiHandlingContext);
	}

	@Nonnull
	@Override
	protected CompletableFuture<EndpointResponse> doHandleRequest(@Nonnull RestEndpointExecutionContext executionContext) {
		final ExecutedEvent requestExecutedEvent = executionContext.requestExecutedEvent();
		requestExecutedEvent.finishInputDeserialization();

		final Collection<CatalogContract> catalogs = requestExecutedEvent.measureInternalEvitaDBExecution(() ->
			restHandlingContext.getEvita().getCatalogs());
		requestExecutedEvent.finishOperationExecution();
		return CompletableFuture.supplyAsync(() -> {
			final JsonNode result = convertResultIntoSerializableObject(executionContext, catalogs);
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

	@Nonnull
	@Override
	protected JsonNode convertResultIntoSerializableObject(@Nonnull RestEndpointExecutionContext exchange, @Nonnull Object catalogs) {
		Assert.isPremiseValid(
			catalogs instanceof Collection,
			() -> new RestInternalError("Expected collection of catalogs, but got `" + catalogs.getClass().getName() + "`.")
		);
		//noinspection unchecked
		return catalogJsonSerializer.serialize((Collection<CatalogContract>) catalogs);
	}
}
