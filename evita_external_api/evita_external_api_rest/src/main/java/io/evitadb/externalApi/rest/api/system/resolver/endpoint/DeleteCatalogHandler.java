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
import io.evitadb.core.Evita;
import io.evitadb.externalApi.http.EndpointResponse;
import io.evitadb.externalApi.http.SuccessEndpointResponse;
import io.evitadb.externalApi.rest.api.system.model.CatalogsHeaderDescriptor;
import io.evitadb.externalApi.rest.io.RestEndpointExecutionContext;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Deletes single evitaDB catalog by its name. Serves both the blocking `DELETE /catalogs/{name}`, which answers
 * `204` once the catalog is deleted, and the asynchronous `DELETE /catalogs-async/{name}`. A missing catalog is
 * answered with `404`.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class DeleteCatalogHandler extends EngineOperationHandler {

	public DeleteCatalogHandler(@Nonnull SystemRestHandlingContext restApiHandlingContext, boolean async) {
		super(restApiHandlingContext, async);
	}

	@Nonnull
	@Override
	protected CompletableFuture<EngineOperation> prepareOperation(
		@Nonnull RestEndpointExecutionContext executionContext,
		@Nonnull Map<String, Object> parameters
	) {
		final String catalogName = (String) parameters.get(CatalogsHeaderDescriptor.NAME.name());
		return CompletableFuture.completedFuture(
			new EngineOperation(
				(evita, catalog) -> evita.deleteCatalogIfExistsWithProgress(catalog.getName()).map(it -> it),
				catalogName
			)
		);
	}

	@Nonnull
	@Override
	protected EndpointResponse createSyncResponse(
		@Nonnull RestEndpointExecutionContext executionContext,
		@Nonnull Evita evita,
		@Nonnull String resultingCatalogName
	) {
		return SuccessEndpointResponse.NO_CONTENT;
	}

	@Nonnull
	@Override
	public Set<HttpMethod> getSupportedHttpMethods() {
		return Set.of(HttpMethod.DELETE);
	}
}
