/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.extraResult;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.requestResponse.EvitaResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Only passes {@link EvitaResponse} to individual scoped extra result fetchers so that they can safely extract their extra results.
 *
 * TOBEDONE: we can simplify it this way, because evitaDB doesn't support retrieving extra results in multiple scopes
 * at the same time (checkout issue https://github.com/FgForrest/evitaDB/issues/102). Each extra result can be present
 * in the query only once (scoped or not).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class InScopeDataFetcher implements DataFetcher<EvitaResponse<?>> {

	@Nullable
	private static InScopeDataFetcher INSTANCE;

	@Nonnull
	public static InScopeDataFetcher getInstance() {
		if (INSTANCE == null) {
			INSTANCE = new InScopeDataFetcher();
		}
		return INSTANCE;
	}

	@Override
	public EvitaResponse<?> get(DataFetchingEnvironment environment) throws Exception {
		return Objects.requireNonNull(environment.getSource());
	}
}
