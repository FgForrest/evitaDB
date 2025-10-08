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

package io.evitadb.externalApi.graphql.api.resolver.dataFetcher;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.observability.trace.TracingContextReference;
import io.evitadb.core.Evita;
import io.evitadb.core.executor.ObservableExecutorService;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;

/**
 * Async data fetcher which executes the logic of delegate fetcher in the future. Should be used only for reading data fetchers
 * as mutating data fetcher shouldn't be run in parallel anyway.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Slf4j
public class AsyncDataFetcher implements DataFetcher<Object> {

	private final boolean enabled;

	/**
	 * Underlying data fetcher with actual fetching logic.
	 */
	@Nonnull private final DataFetcher<?> delegate;
	@Nonnull private final ObservableExecutorService executorService;

	/**
	 * Tracing context provider. We need to pass the current tracing context to the async data fetcher.
	 */
	@Nonnull private final TracingContext tracingContext;
	@Nonnull private final String tracingBlockDescription;

	public AsyncDataFetcher(
		@Nonnull DataFetcher<?> delegate,
		@Nonnull GraphQLOptions config,
		@Nonnull TracingContext tracingContext,
		@Nonnull Evita evita
	) {
		this.enabled = config.isParallelize();

		this.delegate = delegate;
		this.executorService = resolveExecutor(evita);

		this.tracingContext = tracingContext;
		this.tracingBlockDescription = resolveTracingBlockDescription();
	}

	@Override
	public Object get(DataFetchingEnvironment environment) throws Exception {
		if (!this.enabled) {
			// no executor, no async call
			log.debug("No executor for processing data fetcher `" + getClass().getName() + "`, processing synchronously.");
			return this.delegate.get(environment);
		}

		// We need to manually pass the context, because the completable future will be detached from this call.
		final TracingContextReference<?> parentContextReference = this.tracingContext.getCurrentContext();
		return CompletableFuture.supplyAsync(
			() -> this.tracingContext.executeWithinBlockWithParentContext(
				parentContextReference,
				this.tracingBlockDescription,
				() -> {
					try {
						return this.delegate.get(environment);
					} catch (Exception e) {
						if (e instanceof RuntimeException re) {
							throw re;
						} else {
							throw new GraphQLInternalError("Unexpected exception occurred during data fetching.", e);
						}
					}
				}
			),
			this.executorService
		);
	}

	@Nonnull
	private String resolveTracingBlockDescription() {
		if (ReadDataFetcher.class.isAssignableFrom(this.delegate.getClass())) {
			return "GraphQL query fetch";
		} else if (WriteDataFetcher.class.isAssignableFrom(this.delegate.getClass())) {
			return "GraphQL mutation write";
		} else {
			throw new GraphQLInternalError("Unsupported GraphQL root fetcher type on `" + this.delegate.getClass() + "`.");
		}
	}

	@Nonnull
	private ObservableExecutorService resolveExecutor(@Nonnull Evita evita) {
		if (ReadDataFetcher.class.isAssignableFrom(this.delegate.getClass())) {
			return evita.getRequestExecutor();
		} else if (WriteDataFetcher.class.isAssignableFrom(this.delegate.getClass())) {
			return evita.getTransactionExecutor();
		} else {
			throw new GraphQLInternalError("Unsupported GraphQL async fetcher type on `" + this.delegate.getClass() + "`.");
		}
	}
}
