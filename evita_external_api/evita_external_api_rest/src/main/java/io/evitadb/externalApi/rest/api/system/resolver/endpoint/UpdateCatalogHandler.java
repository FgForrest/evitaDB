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

import com.linecorp.armeria.common.HttpMethod;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.requestResponse.progress.Progress;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.rest.api.system.dto.UpdateCatalogRequestDto;
import io.evitadb.externalApi.rest.api.system.model.CatalogsHeaderDescriptor;
import io.evitadb.externalApi.rest.api.system.model.UpdateCatalogRequestDescriptor;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.externalApi.rest.io.RestEndpointExecutionContext;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Changes a single evitaDB catalog by its name: renames it, changes its state or its mutability - exactly one of
 * these per request, so that each request performs exactly one engine operation. Serves both the blocking
 * `PATCH /catalogs/{name}` and the asynchronous `PATCH /catalogs-async/{name}`.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class UpdateCatalogHandler extends EngineOperationHandler {

	public UpdateCatalogHandler(@Nonnull SystemRestHandlingContext restApiHandlingContext, boolean async) {
		super(restApiHandlingContext, async);
	}

	@Nonnull
	@Override
	protected CompletableFuture<EngineOperation> prepareOperation(
		@Nonnull RestEndpointExecutionContext executionContext,
		@Nonnull Map<String, Object> parameters
	) {
		final String catalogName = (String) parameters.get(CatalogsHeaderDescriptor.NAME.name());
		return parseRequestBody(executionContext, UpdateCatalogRequestDto.class)
			.thenApply(requestBody -> toOperation(catalogName, requestBody));
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

	/**
	 * Translates the request body into the engine operation it requests.
	 *
	 * @throws RestInvalidArgumentException when the body does not contain exactly one change or requests an
	 *                                      unsupported state
	 */
	@Nonnull
	private static EngineOperation toOperation(@Nonnull String catalogName, @Nonnull UpdateCatalogRequestDto body) {
		final int changes = (body.name() == null ? 0 : 1) + (body.state() == null ? 0 : 1) +
			(body.readOnly() == null ? 0 : 1);
		if (changes != 1) {
			throw new RestInvalidArgumentException(
				"Exactly one of `" + UpdateCatalogRequestDescriptor.NAME.name() + "`, `" +
					UpdateCatalogRequestDescriptor.STATE.name() + "` and `" +
					UpdateCatalogRequestDescriptor.READ_ONLY.name() + "` must be set, but " + changes + " were."
			);
		}

		final String newName = body.name();
		if (newName != null) {
			return new EngineOperation(
				(evita, catalog) -> Optional.of(evita.renameCatalogWithProgress(catalog.getName(), newName)),
				newName
			);
		}
		final Boolean readOnly = body.readOnly();
		if (readOnly != null) {
			return new EngineOperation(
				(evita, catalog) -> Optional.of(
					readOnly ?
						evita.makeCatalogImmutableWithProgress(catalog.getName()) :
						evita.makeCatalogMutableWithProgress(catalog.getName())
				),
				catalogName
			);
		}
		final CatalogState state = body.state();
		if (state == CatalogState.ALIVE) {
			return new EngineOperation(
				(evita, catalog) -> Optional.of(makeAlive(evita, catalog)),
				catalogName
			);
		} else if (state == CatalogState.INACTIVE) {
			return new EngineOperation(
				(evita, catalog) -> Optional.of(evita.deactivateCatalogWithProgress(catalog.getName())),
				catalogName
			);
		} else {
			throw new RestInvalidArgumentException(
				"A catalog can be switched only to the `" + CatalogState.ALIVE + "` or `" + CatalogState.INACTIVE +
					"` state, but `" + state + "` was requested."
			);
		}
	}

	/**
	 * `ALIVE` activates an inactive catalog and makes a warming-up catalog alive; the engine refuses whatever does
	 * not apply to the current state of the catalog.
	 */
	@Nonnull
	private static Progress<?> makeAlive(@Nonnull Evita evita, @Nonnull CatalogContract catalog) {
		return catalog.getCatalogState() == CatalogState.INACTIVE ?
			evita.activateCatalogWithProgress(catalog.getName()) :
			evita.makeCatalogAliveWithProgress(catalog.getName());
	}
}
