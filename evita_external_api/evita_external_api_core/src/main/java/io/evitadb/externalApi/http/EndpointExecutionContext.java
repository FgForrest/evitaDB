/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.externalApi.http;

import com.linecorp.armeria.common.HttpRequest;
import io.evitadb.core.Evita;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Context data present during entire execution of certain endpoint.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class EndpointExecutionContext implements AutoCloseable {
	@Nonnull private final HttpRequest httpRequest;
	@Nonnull private final Evita evita;

	@Nonnull private final List<Exception> exceptions = new LinkedList<>();
	@Nonnull private final List<Consumer<EndpointExecutionContext>> closeCallbacks = new LinkedList<>();

	/**
	 * Underlying HTTP request
	 */
	@Nonnull
	public HttpRequest httpRequest() {
		return this.httpRequest;
	}

	/**
	 * Provides request body content type for the execution. Can be called only once.
	 */
	public abstract void provideRequestBodyContentType(@Nonnull String contentType);

	/**
	 * Parsed content type of request body, if any request body is present.
	 */
	@Nullable
	public abstract String requestBodyContentType();

	/**
	 * Provides preferred response body content type for the execution. Can be called only once.
	 */
	public abstract void providePreferredResponseContentType(@Nonnull String contentType);

	/**
	 * Preferred content type of response body, if any response body is will be send.
	 */
	@Nullable
	public abstract String preferredResponseContentType();

	/**
	 * Adds exception from execution. Can be used e.g. by callbacks to log it.
	 */
	public void addException(@Nonnull Exception e) {
		this.exceptions.add(e);
	}

	/**
	 * Returns all exceptions thrown during exception
	 */
	public List<Exception> exceptions() {
		return Collections.unmodifiableList(this.exceptions);
	}

	/**
	 * Adds callback that will be called when context gets closed.
	 */
	public void addCloseCallback(@Nonnull Consumer<EndpointExecutionContext> callback) {
		this.closeCallbacks.add(callback);
	}

	/**
	 * Called by endpoint when error occurred. Can be used for logging.
	 * The error should not be thrown.
	 */
	public void notifyError(@Nonnull Exception e) {
		// do nothing
	}

	@Override
	public void close() {
		this.closeCallbacks.forEach(it -> it.accept(this));
	}

	/**
	 * Asynchronously executes supplier lambda in the request thread pool.
	 * @param supplier supplier to be executed
	 * @return future with result of the supplier
	 * @param <T> type of the result
	 */
	@Nonnull
	public <T> CompletableFuture<T> executeAsyncInRequestThreadPool(@Nonnull Supplier<T> supplier) {
		return CompletableFuture.supplyAsync(supplier, this.evita.getRequestExecutor());
	}

	/**
	 * Asynchronously executes supplier lambda in the transactional thread pool.
	 * @param supplier supplier to be executed
	 * @return future with result of the supplier
	 * @param <T> type of the result
	 */
	@Nonnull
	public <T> CompletableFuture<T> executeAsyncInTransactionThreadPool(@Nonnull Supplier<T> supplier) {
		return CompletableFuture.supplyAsync(supplier, this.evita.getTransactionExecutor());
	}
}
