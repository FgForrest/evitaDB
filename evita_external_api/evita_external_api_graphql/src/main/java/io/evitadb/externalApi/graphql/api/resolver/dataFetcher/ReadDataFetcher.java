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
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.api.ClientContext;
import io.evitadb.thread.ShortRunningSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Async data fetcher which executes the logic of delegate fetcher in the future. Should be used only for reading data fetchers
 * as mutating data fetcher shouldn't be run in parallel anyway.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@RequiredArgsConstructor
@Slf4j
public class ReadDataFetcher implements DataFetcher<Object> {

	/**
	 * Underlying data fetcher with actual fetching logic.
	 */
	@Nonnull private final DataFetcher<?> delegate;
	/**
	 * Client context provider. We need to pass the current client context to the async data fetcher.
	 */
	@Nonnull private final ClientContext clientContext;
	/**
	 * Executor responsible for executing data fetcher asynchronously. If null, data fetcher will work synchronously.
	 */
	@Nullable private final Executor executor;

	@Override
	public Object get(DataFetchingEnvironment environment) throws Exception {
		if (executor == null) {
			// no executor, no async call
			log.debug("No executor for processing data fetcher `" + getClass().getName() + "`, processing synchronously.");
			return delegate.get(environment);
		}

		final Optional<String> currentClientId = clientContext.getClientId();
		final Optional<String> currentRequestId = clientContext.getRequestId();
		return CompletableFuture.supplyAsync(
			new ShortRunningSupplier<>(() -> clientContext.executeWithClientAndRequestId(
				currentClientId.orElse(null),
				currentRequestId.orElse(null),
				() -> {
					try {
						return delegate.get(environment);
					} catch (Exception e) {
						if (e instanceof RuntimeException re) {
							throw re;
						} else {
							throw new GraphQLInternalError("Unexpected exception occurred during data fetching.", e);
						}
					}
				}
			)),
			executor
		);
	}
}
