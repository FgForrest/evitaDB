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
import io.evitadb.externalApi.rest.api.system.model.CatalogsHeaderDescriptor;
import io.evitadb.externalApi.rest.io.RestEndpointExecutionContext;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Replaces the catalog `{newName}` with the catalog `{name}`, i.e. `{name}` is the catalog to be replaced with
 * (`nameToBeReplacedWith` of the engine operation) and `{newName}` the catalog to be replaced (`nameToBeReplaced`).
 * Serves both the blocking `POST /catalogs/{name}/actions/replace/{newName}`, which returns the catalog under its new
 * name, and its asynchronous counterpart under `/catalogs-async`.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class ReplaceCatalogHandler extends EngineOperationHandler {

	public ReplaceCatalogHandler(@Nonnull SystemRestHandlingContext restApiHandlingContext, boolean async) {
		super(restApiHandlingContext, async);
	}

	@Nonnull
	@Override
	protected CompletableFuture<EngineOperation> prepareOperation(
		@Nonnull RestEndpointExecutionContext executionContext,
		@Nonnull Map<String, Object> parameters
	) {
		final String newCatalogName = (String) parameters.get(CatalogsHeaderDescriptor.NEW_NAME.name());
		return CompletableFuture.completedFuture(
			new EngineOperation(
				(evita, catalog) -> Optional.of(evita.replaceCatalogWithProgress(catalog.getName(), newCatalogName)),
				newCatalogName
			)
		);
	}

	@Nonnull
	@Override
	public Set<HttpMethod> getSupportedHttpMethods() {
		return Set.of(HttpMethod.POST);
	}
}
