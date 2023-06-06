/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.graphql.api.resolver.dataFetcher;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Async data fetcher which hides the async implementation of data fetcher. Should be used only for reading data fetchers
 * as mutating data fetcher shouldn't be run in parallel anyway.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
@Slf4j
public abstract class ReadDataFetcher<T> implements DataFetcher<Object> {

	/**
	 * Executor responsible for executing data fetcher asynchronously. If null, data fetcher will work synchronously.
	 */
	@Nullable private final Executor executor;

	@Override
	public Object get(DataFetchingEnvironment environment) throws Exception {
		if (executor == null) {
			// no executor, no async call
			log.debug("No executor for processing data fetcher `" + getClass().getName() + "`, processing synchronously.");
			return doGet(environment);
		}
		return CompletableFuture.supplyAsync(() -> {
			try {
				return doGet(environment);
			} catch (Throwable e) {
				// todo lho for debug, should be deleted after
				log.error("DEBUG: Error occurred during async data fetcher call: ", e);
				throw e;
			}
		}, executor);
	}

	/**
	 * Actual data fetching logic.
	 */
	@Nonnull
	protected abstract T doGet(@Nonnull DataFetchingEnvironment environment);
}
