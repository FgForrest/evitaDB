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
import io.evitadb.api.CatalogState;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.NotFoundEndpointResponse;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.rest.api.system.dto.UpdateCatalogRequestDto;
import io.evitadb.externalApi.rest.api.system.model.CatalogsHeaderDescriptor;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.externalApi.rest.io.RestEndpointExecutionContext;
import io.evitadb.externalApi.rest.metric.event.request.ExecutedEvent;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Updates and returns single evitaDB catalog by its name.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class UpdateCatalogHandler extends CatalogHandler {

	public UpdateCatalogHandler(@Nonnull SystemRestHandlingContext restApiHandlingContext) {
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
		return parseRequestBody(executionContext, UpdateCatalogRequestDto.class)
			.thenApply(requestBody -> {
				requestExecutedEvent.finishInputDeserialization();

				final String catalogName = (String) parameters.get(CatalogsHeaderDescriptor.NAME.name());
				final Optional<CatalogContract> catalog = requestExecutedEvent.measureInternalEvitaDBExecution(() ->
					this.restHandlingContext.getEvita().getCatalogInstance(catalogName));
				if (catalog.isEmpty()) {
					requestExecutedEvent.finishOperationExecution();
					requestExecutedEvent.finishResultSerialization();
					return new NotFoundEndpointResponse();
				};

				final CatalogContract updatedCatalog = requestExecutedEvent.measureInternalEvitaDBExecution(() -> {
					final Optional<String> newCatalogName = renameCatalog(catalog.get(), requestBody);
					switchCatalogToAliveState(catalog.get(), requestBody);

					final String nameOfUpdateCatalog = newCatalogName.orElse(catalogName);
					return this.restHandlingContext.getEvita().getCatalogInstance(nameOfUpdateCatalog)
						.orElseThrow(() -> new RestInternalError("Couldn't find updated catalog `" + nameOfUpdateCatalog + "`"));
				});
				requestExecutedEvent.finishOperationExecution();

				final Object result = convertResultIntoSerializableObject(executionContext, updatedCatalog);
				requestExecutedEvent.finishResultSerialization();

				return new SuccessEndpointResponse(result);
			});
	}

	@Nonnull
	@Override
	public Set<HttpMethod> getSupportedHttpMethods() {
		return Set.of(HttpMethod.PATCH);
	}

	@Nonnull
	@Override
	public Set<String> getSupportedRequestContentTypes() {
		return DEFAULT_SUPPORTED_CONTENT_TYPES;
	}

	@Nonnull
	private Optional<String> renameCatalog(@Nonnull CatalogContract catalog,
	                                       @Nonnull UpdateCatalogRequestDto requestBody) {
		final Evita evita = this.restHandlingContext.getEvita();

		final Optional<String> newCatalogName = Optional.ofNullable(requestBody.name());
		if (newCatalogName.isEmpty()) {
			return Optional.empty();
		}

		final boolean overwriteTarget = Optional.ofNullable(requestBody.overwriteTarget()).orElse(false);
		if (overwriteTarget) {
			evita.replaceCatalog(catalog.getName(), newCatalogName.get());
		} else {
			evita.renameCatalog(catalog.getName(), newCatalogName.get());
		}

		return newCatalogName;
	}

	private void switchCatalogToAliveState(
		@Nonnull CatalogContract catalog,
		@Nonnull UpdateCatalogRequestDto requestBody
	) {
		final Optional<CatalogState> newCatalogState = Optional.ofNullable(requestBody.catalogState());
		if (newCatalogState.isEmpty()) {
			return;
		}

		Assert.isTrue(
			newCatalogState.get() == CatalogState.ALIVE,
			() -> new RestInvalidArgumentException("A catalog can be switched only to the `ALIVE` state.")
		);
		Assert.isTrue(
			catalog.getCatalogState() == CatalogState.WARMING_UP,
			() -> new RestInvalidArgumentException("Only a catalog in the `WARMING_UP` state can be switched to the `ALIVE` state.")
		);

		this.restHandlingContext.getEvita().makeCatalogAlive(catalog.getName());
	}
}
