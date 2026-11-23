/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.requestResponse.progress.Progress;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.system.model.progress.EngineMutationProgressHandleDescriptor;
import io.evitadb.externalApi.http.AcceptedEndpointResponse;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.NotFoundEndpointResponse;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.rest.api.system.model.CatalogsHeaderDescriptor;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.io.RestEndpointExecutionContext;
import io.evitadb.externalApi.rest.metric.event.request.ExecutedEvent;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * Ancestor of the endpoints running one engine operation (activation, renaming, duplication, ...) on the catalog
 * named by the `name` path parameter. The same handler serves the blocking variant under `/catalogs` - which waits
 * until the operation finishes and returns the resulting catalog - and the asynchronous one under
 * `/catalogs-async`, which answers `202 Accepted` with a progress handle as soon as the engine has accepted the
 * operation. A missing catalog is answered with `404` in both variants.
 *
 * Both run on the transaction thread pool: the blocking variant holds the thread for the whole operation, the
 * asynchronous one only for the acceptance (lock, checks and, for rename and replace, the drain of open sessions
 * and commits).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public abstract class EngineOperationHandler extends CatalogHandler {

	/**
	 * Whether the handler answers right after the acceptance of the operation with a progress handle.
	 */
	private final boolean async;

	protected EngineOperationHandler(@Nonnull SystemRestHandlingContext restHandlingContext, boolean async) {
		super(restHandlingContext);
		this.async = async;
	}

	@Override
	protected boolean modifiesData() {
		return true;
	}

	@Nonnull
	@Override
	protected CompletableFuture<EndpointResponse> doHandleRequest(
		@Nonnull RestEndpointExecutionContext executionContext
	) {
		final ExecutedEvent requestExecutedEvent = executionContext.requestExecutedEvent();
		final Map<String, Object> parameters = getParametersFromRequest(executionContext);
		final String catalogName = (String) parameters.get(CatalogsHeaderDescriptor.NAME.name());

		return prepareOperation(executionContext, parameters)
			.thenCompose(operation -> {
				requestExecutedEvent.finishInputDeserialization();

				return executionContext.executeAsyncInTransactionThreadPool(() -> {
					final Evita evita = this.restHandlingContext.getEvita();
					// explicit pre-check: the engine reports a missing catalog as an invalid usage, i.e. 400
					final Optional<CatalogContract> catalog = requestExecutedEvent.measureInternalEvitaDBExecution(
						() -> evita.getCatalogInstance(catalogName)
					);
					if (catalog.isEmpty()) {
						requestExecutedEvent.finishOperationExecution();
						requestExecutedEvent.finishResultSerialization();
						return new NotFoundEndpointResponse();
					}

					final Optional<Progress<?>> progress = requestExecutedEvent.measureInternalEvitaDBExecution(
						() -> operation.start().apply(evita, catalog.get())
					);
					if (progress.isEmpty()) {
						// the catalog has disappeared in the meantime
						requestExecutedEvent.finishOperationExecution();
						requestExecutedEvent.finishResultSerialization();
						return new NotFoundEndpointResponse();
					}

					if (this.async) {
						requestExecutedEvent.finishOperationExecution();
						final ObjectNode handle = serializeHandle(progress.get());
						requestExecutedEvent.finishResultSerialization();
						return new AcceptedEndpointResponse(handle);
					} else {
						requestExecutedEvent.measureInternalEvitaDBExecution(
							() -> progress.get().onCompletion().toCompletableFuture().join()
						);
						requestExecutedEvent.finishOperationExecution();
						final EndpointResponse response = createSyncResponse(
							executionContext, evita, operation.resultingCatalogName()
						);
						requestExecutedEvent.finishResultSerialization();
						return response;
					}
				});
			});
	}

	/**
	 * Reads the request and prepares the operation to run. Invalid requests fail the returned future.
	 *
	 * @param executionContext context of the request
	 * @param parameters       path and query parameters of the request
	 * @return future with the operation to run
	 */
	@Nonnull
	protected abstract CompletableFuture<EngineOperation> prepareOperation(
		@Nonnull RestEndpointExecutionContext executionContext,
		@Nonnull Map<String, Object> parameters
	);

	/**
	 * Creates the response of the blocking variant once the operation has finished. Returns the resulting catalog
	 * by default.
	 *
	 * @param executionContext      context of the request
	 * @param evita                 evitaDB instance
	 * @param resultingCatalogName  name of the catalog the operation resulted in
	 * @return the response
	 */
	@Nonnull
	protected EndpointResponse createSyncResponse(
		@Nonnull RestEndpointExecutionContext executionContext,
		@Nonnull Evita evita,
		@Nonnull String resultingCatalogName
	) {
		final CatalogContract resultingCatalog = evita.getCatalogInstance(resultingCatalogName)
			.orElseThrow(() -> new RestInternalError("Couldn't find catalog `" + resultingCatalogName + "`."));
		return new SuccessEndpointResponse(convertResultIntoSerializableObject(executionContext, resultingCatalog));
	}

	/**
	 * Serializes the progress handle of an accepted operation.
	 */
	@Nonnull
	private ObjectNode serializeHandle(@Nonnull Progress<?> progress) {
		final UUID progressId = progress.progressId()
			.orElseThrow(() -> new RestInternalError("Engine mutation progress has no id!"));
		final ObjectNode handle = this.restHandlingContext.getObjectMapper().createObjectNode();
		handle.put(EngineMutationProgressHandleDescriptor.PROGRESS_ID.name(), progressId.toString());
		return handle;
	}

	/**
	 * Engine operation prepared from a request.
	 *
	 * @param start                starts the operation on the existing target catalog; returns empty when there is
	 *                             nothing to do because the catalog no longer exists
	 * @param resultingCatalogName name of the catalog the blocking variant returns after the operation has finished
	 */
	protected record EngineOperation(
		@Nonnull BiFunction<Evita, CatalogContract, Optional<Progress<?>>> start,
		@Nonnull String resultingCatalogName
	) {
	}
}
