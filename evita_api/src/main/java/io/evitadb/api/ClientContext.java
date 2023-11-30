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

package io.evitadb.api;

import io.evitadb.utils.Assert;
import org.slf4j.MDC;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Optional;
import java.util.function.Supplier;

import static java.util.Optional.ofNullable;

/**
 * The class allows propagation of the evitaDB client context when it calls method on evitaDB contract interfaces.
 * The context allows specification of two client defined values:
 *
 * - clientId: string that will be constant per a connected client,
 * example values: Next.JS Middleware, evitaDB console etc.
 * - requestId: a randomized token - preferably UUID that will connect all queries and mutations issued by the client
 * in a single unit of work, that is controlled by the client (not the server); there might be different
 * request ids within single evita session and also there might be same request id among multiple different
 * evita sessions
 *
 * The context values are logged in the evitaDB log and might be taken into an account in monitoring and debugging
 * tools.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface ClientContext {

	/**
	 * Name of property representing the client identifier in the {@link MDC}.
	 */
	String MDC_CLIENT_ID_PROPERTY = "clientId";
	/**
	 * Name of property representing the request identifier in the {@link MDC}.
	 */
	String MDC_REQUEST_ID_PROPERTY = "requestId";

	/**
	 * Holds the client context for the current thread.
	 *
	 * TOBEDONE - the implementation should be switched to <a href="https://www.baeldung.com/java-20-scoped-values">scoped values</a>
	 * TOBEDONE - once the evitaDB is switched to Java 20
	 */
	ThreadLocal<Deque<Context>> CLIENT_CONTEXT = new ThreadLocal<>();

	/**
	 * Method executes the `lambda` function within the scope of client defined context.
	 *
	 * @param clientId  string that will be constant per a connected client,
	 *                  example values: Next.JS Middleware, evitaDB console etc.
	 * @param requestId a randomized token - preferably UUID that will connect all queries and mutations issued by the client
	 *                  in a single unit of work, that is controlled by the client (not the server); there might be different
	 *                  request ids within single evita session and also there might be same request id among multiple different
	 *                  evita sessions
	 * @param lambda    function to be executed
	 */
	default void executeWithClientAndRequestId(
		@Nonnull String clientId,
		@Nullable String requestId,
		@Nonnull Runnable lambda
	) {
		Deque<Context> context = CLIENT_CONTEXT.get();
		try {
			if (context == null) {
				context = new LinkedList<>();
				CLIENT_CONTEXT.set(context);
			}

			final Context newContext = new Context(clientId, requestId);
			context.push(newContext);
			newContext.init();

			lambda.run();
		} finally {
			final Context oldContext = context.pop();
			oldContext.tearDown();
			// restore parent context
			getContext().ifPresent(Context::init);
		}
	}

	/**
	 * Method executes the `lambda` function within the scope of client defined context.
	 *
	 * @param clientId string that will be constant per a connected client,
	 *                 example values: Next.JS Middleware, evitaDB console etc.
	 * @param lambda   function to be executed
	 */
	default void executeWithClientId(
		@Nonnull String clientId,
		@Nonnull Runnable lambda
	) {
		Deque<Context> context = CLIENT_CONTEXT.get();
		try {
			if (context == null) {
				context = new LinkedList<>();
				CLIENT_CONTEXT.set(context);
			}

			final Context newContext = new Context(clientId, null);
			context.push(newContext);
			newContext.init();

			lambda.run();
		} finally {
			final Context oldContext = context.pop();
			oldContext.tearDown();
			// restore parent context
			getContext().ifPresent(Context::init);
		}
	}

	/**
	 * Method executes the `lambda` function within the scope of client defined context. In order to use this function
	 * the {@link #executeWithClientAndRequestId(String, String, Runnable)} or {@link #executeWithClientId(String, Runnable)}
	 * must be called first to initialize the client identifier.
	 *
	 * @param requestId a randomized token - preferably UUID that will connect all queries and mutations issued by the client
	 *                  in a single unit of work, that is controlled by the client (not the server); there might be different
	 *                  request ids within single evita session and also there might be same request id among multiple different
	 *                  evita sessions
	 * @param lambda    function to be executed
	 */
	default void executeWithRequestId(
		@Nonnull String requestId,
		@Nonnull Runnable lambda
	) {
		final Deque<Context> context = CLIENT_CONTEXT.get();
		Assert.isTrue(!(context == null || context.isEmpty()), "When changing the request ID, the client ID must be set first!");
		try {
			final Context newContext = new Context(context.peek().clientId(), requestId);
			context.push(newContext);
			newContext.init();

			lambda.run();
		} finally {
			final Context oldContext = context.pop();
			oldContext.tearDown();
			// restore parent context
			getContext().ifPresent(Context::init);
		}
	}

	/**
	 * Method executes the `lambda` function within the scope of client-defined context and returns its result.
	 *
	 * @param clientId  string that will be constant per a connected client,
	 *                  example values: Next.JS Middleware, evitaDB console etc.
	 * @param requestId a randomized token - preferably UUID that will connect all queries and mutations issued by the client
	 *                  in a single unit of work, that is controlled by the client (not the server); there might be different
	 *                  request ids within single evita session and also there might be same request id among multiple different
	 *                  evita sessions
	 * @param lambda    function to be executed
	 * @return result of the lambda function
	 */
	default <T> T executeWithClientAndRequestId(
		@Nonnull String clientId,
		@Nullable String requestId,
		@Nonnull Supplier<T> lambda
	) {
		Deque<Context> context = CLIENT_CONTEXT.get();
		try {
			if (context == null) {
				context = new LinkedList<>();
				CLIENT_CONTEXT.set(context);
			}

			final Context newContext = new Context(clientId, requestId);
			context.push(newContext);
			newContext.init();

			return lambda.get();
		} finally {
			final Context oldContext = context.pop();
			oldContext.tearDown();
			// restore parent context
			getContext().ifPresent(Context::init);
		}
	}

	/**
	 * Method executes the `lambda` function within the scope of client defined context and returns its result.
	 *
	 * @param clientId string that will be constant per a connected client,
	 *                 example values: Next.JS Middleware, evitaDB console etc.
	 * @param lambda   function to be executed
	 * @return result of the lambda function
	 */
	default <T> T executeWithClientId(
		@Nonnull String clientId,
		@Nonnull Supplier<T> lambda
	) {
		Deque<Context> context = CLIENT_CONTEXT.get();
		try {
			if (context == null) {
				context = new LinkedList<>();
				CLIENT_CONTEXT.set(context);
			}

			final Context newContext = new Context(clientId, null);
			context.push(newContext);
			newContext.init();

			return lambda.get();
		} finally {
			final Context oldContext = context.pop();

			oldContext.tearDown();
			// restore parent context
			getContext().ifPresent(Context::init);
		}
	}

	/**
	 * Method executes the `lambda` function within the scope of client defined context and returns its result. In order
	 * to use this function the {@link #executeWithClientAndRequestId(String, String, Runnable)} or
	 * {@link #executeWithClientId(String, Runnable)} must be called first to initialize the client identifier.
	 *
	 * @param requestId a randomized token - preferably UUID that will connect all queries and mutations issued by the client
	 *                  in a single unit of work, that is controlled by the client (not the server); there might be different
	 *                  request ids within single evita session and also there might be same request id among multiple different
	 *                  evita sessions
	 * @param lambda    function to be executed
	 */
	default <T> T executeWithRequestId(
		@Nonnull String requestId,
		@Nonnull Supplier<T> lambda
	) {
		final Deque<Context> context = CLIENT_CONTEXT.get();
		try {
			Assert.isTrue(!(context == null || context.isEmpty()), "When changing the request ID, the client ID must be set first!");

			final Context newContext = new Context(context.peek().clientId(), requestId);
			context.push(newContext);
			newContext.init();

			return lambda.get();
		} finally {
			final Context oldContext = context.pop();
			oldContext.tearDown();
			// restore parent context
			getContext().ifPresent(Context::init);
		}
	}

	/**
	 * Returns a client identifier if it was set in current scope.
	 *
	 * @return string that will be constant per a connected client,
	 * example values: Next.JS Middleware, evitaDB console etc.
	 */
	@Nonnull
	default Optional<String> getClientId() {
		return getContext().map(Context::clientId);
	}

	/**
	 * Returns a request identifier if it was set in current scope.
	 *
	 * @return a randomized token - preferably UUID that will connect all queries and mutations issued by the client
	 * in a single unit of work, that is controlled by the client (not the server); there might be different
	 * request ids within single evita session and also there might be same request id among multiple different
	 * evita sessions
	 */
	@Nonnull
	default Optional<String> getRequestId() {
		return getContext().map(Context::requestId);
	}

	/*
	 * Private API
	 */

	/**
	 * Returns a context if it was set in current scope.
	 * @return context
	 */
	@Nonnull
	private static Optional<Context> getContext() {
		return ofNullable(CLIENT_CONTEXT.get())
			.map(it -> it.isEmpty() ? null : it.peek());
	}

	/**
	 * Internal context record for the client data.
	 * @param clientId client identifier
	 * @param requestId request identifier
	 */
	record Context(
		@Nonnull String clientId,
		@Nullable String requestId
	) {
		void init() {
			// remove any previous data just to make sure
			MDC.remove(MDC_CLIENT_ID_PROPERTY);
			MDC.remove(MDC_REQUEST_ID_PROPERTY);

			MDC.put(MDC_CLIENT_ID_PROPERTY, clientId);
			MDC.put(MDC_REQUEST_ID_PROPERTY, requestId);
		}

		void tearDown() {
			MDC.remove(MDC_CLIENT_ID_PROPERTY);
			MDC.remove(MDC_REQUEST_ID_PROPERTY);
		}
	}

}
