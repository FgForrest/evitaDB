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

package io.evitadb.externalApi.graphql.api.system.resolver.mutatingDataFetcher;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.CatalogContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.graphql.api.resolver.dataFetcher.WriteDataFetcher;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
/**
 * Ancestor of the data fetchers of the blocking engine mutations of the system API (create, activate, rename, ...).
 * It runs the operation, waits until it finishes and returns the resulting catalog - a usable `Catalog` or an
 * `UnusableCatalog` (e.g. after deactivation), which is why the mutations return `CatalogUnion`.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class EngineOperationMutatingDataFetcher implements DataFetcher<CatalogContract>, WriteDataFetcher {

	protected final Evita evita;

	@Nonnull
	@Override
	public CatalogContract get(DataFetchingEnvironment environment) throws Exception {
		final String resultingCatalogName = executeOperation(environment);
		return this.evita.getCatalogInstanceOrThrowException(resultingCatalogName);
	}

	/**
	 * Executes the operation with arguments taken from the environment and waits until it finishes.
	 *
	 * @param environment environment carrying the arguments of the mutation
	 * @return name of the catalog to be returned as the result of the mutation
	 */
	@Nonnull
	protected abstract String executeOperation(@Nonnull DataFetchingEnvironment environment);
}
