/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.core.Evita;
import io.evitadb.core.executor.CancellableRunnable;
import io.evitadb.core.executor.ObservableExecutorServiceWithCancellationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the abstract {@link EndpointExecutionContext} base class. Uses
 * a minimal concrete implementation ({@link TestEndpointExecutionContext}) to
 * test accessor methods, exception tracking, close callbacks, async execution
 * delegation, and the no-op {@code notifyError} method.
 *
 * @author evitaDB
 */
@DisplayName("EndpointExecutionContext - base class behavior")
class EndpointExecutionContextTest {

	private HttpRequest httpRequest;
	private Evita evita;
	private ServiceRequestContext serviceRequestContext;
	private ObservableExecutorServiceWithCancellationSupport requestExecutor;
	private ObservableExecutorServiceWithCancellationSupport transactionExecutor;
	private CompletableFuture<Throwable> cancelFuture;
	private TestEndpointExecutionContext context;

	@BeforeEach
	void setUp() {
		this.httpRequest = mock(HttpRequest.class);
		this.evita = mock(Evita.class);
		this.serviceRequestContext = mock(ServiceRequestContext.class);
		this.requestExecutor = mock(ObservableExecutorServiceWithCancellationSupport.class);
		this.transactionExecutor = mock(ObservableExecutorServiceWithCancellationSupport.class);
		this.cancelFuture = new CompletableFuture<>();

		when(this.evita.getRequestExecutor()).thenReturn(this.requestExecutor);
		when(this.evita.getTransactionExecutor()).thenReturn(this.transactionExecutor);
		when(this.serviceRequestContext.whenRequestCancelling()).thenReturn(this.cancelFuture);

		this.context = new TestEndpointExecutionContext(
			this.httpRequest, this.evita, this.serviceRequestContext
		);
	}

	@Nested
	@DisplayName("accessors")
	class Accessors {

		@Test
		@DisplayName("returns HTTP request")
		void shouldReturnHttpRequest() {
			assertSame(httpRequest, context.httpRequest());
		}

		@Test
		@DisplayName("returns service request context")
		void shouldReturnServiceRequestContext() {
			assertSame(
				serviceRequestContext,
				context.serviceRequestContext()
			);
		}
	}

	@Nested
	@DisplayName("exception tracking")
	class ExceptionTracking {

		@Test
		@DisplayName("returns empty list initially")
		void shouldReturnEmptyListInitially() {
			assertTrue(
				context.exceptions().isEmpty(),
				"Exceptions list should be empty initially"
			);
		}

		@Test
		@DisplayName("tracks multiple exceptions in order")
		void shouldTrackMultipleExceptionsInOrder() {
			final Exception e1 = new RuntimeException("first");
			final Exception e2 = new IllegalStateException("second");
			final Exception e3 = new IllegalArgumentException("third");

			context.addException(e1);
			context.addException(e2);
			context.addException(e3);

			final List<Exception> exceptions = context.exceptions();
			assertEquals(3, exceptions.size());
			assertSame(e1, exceptions.get(0));
			assertSame(e2, exceptions.get(1));
			assertSame(e3, exceptions.get(2));
		}

		@Test
		@DisplayName("returns unmodifiable list")
		void shouldReturnUnmodifiableList() {
			context.addException(new RuntimeException("test"));

			assertThrows(
				UnsupportedOperationException.class,
				() -> context.exceptions().add(new RuntimeException("bad"))
			);
		}
	}

	@Nested
	@DisplayName("close callbacks")
	class CloseCallbacks {

		@Test
		@DisplayName("invokes callback on close")
		void shouldInvokeCallbackOnClose() {
			final AtomicBoolean called = new AtomicBoolean(false);
			context.addCloseCallback(ctx -> called.set(true));

			context.close();

			assertTrue(
				called.get(),
				"Close callback should be invoked"
			);
		}

		@Test
		@DisplayName("invokes multiple callbacks in order")
		void shouldInvokeMultipleCallbacksInOrder() {
			final List<String> order = new ArrayList<>(2);
			context.addCloseCallback(ctx -> order.add("first"));
			context.addCloseCallback(ctx -> order.add("second"));

			context.close();

			assertEquals(
				List.of("first", "second"),
				order
			);
		}

		@Test
		@DisplayName("passes context to callback")
		void shouldPassContextToCallback() {
			final AtomicReference<EndpointExecutionContext> ref =
				new AtomicReference<>();
			context.addCloseCallback(ref::set);

			context.close();

			assertSame(
				context, ref.get(),
				"Callback should receive the context instance"
			);
		}
	}

	@Nested
	@DisplayName("async execution")
	class AsyncExecution {

		@Test
		@DisplayName("delegates to request executor")
		void shouldDelegateToRequestExecutor() throws Exception {
			configureMockExecutor(requestExecutor);

			final CompletableFuture<String> result =
				context.executeAsyncInRequestThreadPool(
					() -> "request-result"
				);

			assertEquals(
				"request-result",
				result.get(5, TimeUnit.SECONDS)
			);
			verify(requestExecutor).createTask(any(Runnable.class));
		}

		@Test
		@DisplayName("delegates to transaction executor")
		void shouldDelegateToTransactionExecutor() throws Exception {
			configureMockExecutor(transactionExecutor);

			final CompletableFuture<String> result =
				context.executeAsyncInTransactionThreadPool(
					() -> "tx-result"
				);

			assertEquals(
				"tx-result",
				result.get(5, TimeUnit.SECONDS)
			);
			verify(transactionExecutor).createTask(any(Runnable.class));
		}

		@Test
		@DisplayName("delegates async supplier to request executor")
		void shouldDelegateAsyncSupplierToRequestExecutor()
			throws Exception {

			configureMockExecutor(requestExecutor);

			final CompletableFuture<String> inner =
				new CompletableFuture<>();

			final CompletableFuture<String> result =
				context.executeAsyncSupplierInRequestThreadPool(
					() -> inner
				);

			inner.complete("async-value");

			assertEquals(
				"async-value",
				result.get(5, TimeUnit.SECONDS)
			);
		}

		@Test
		@DisplayName("propagates exception from supplier")
		void shouldPropagateExceptionFromSupplier() throws Exception {
			configureMockExecutor(requestExecutor);

			final CompletableFuture<String> result =
				context.executeAsyncInRequestThreadPool(() -> {
					throw new RuntimeException("supplier fail");
				});

			assertTrue(
				result.isCompletedExceptionally(),
				"Result should be exceptionally completed"
			);
		}
	}

	@Nested
	@DisplayName("notifyError")
	class NotifyError {

		@Test
		@DisplayName("is no-op in base class")
		void shouldBeNoOpInBaseClass() {
			final Exception error = new RuntimeException("test");

			// should not throw and should not affect exceptions list
			context.notifyError(error);

			assertTrue(
				context.exceptions().isEmpty(),
				"notifyError should not add to exceptions list"
			);
		}
	}

	/**
	 * Configures a mock executor to capture and immediately run created tasks.
	 */
	private void configureMockExecutor(
		@Nonnull ObservableExecutorServiceWithCancellationSupport executor
	) {
		final AtomicReference<CancellableRunnable> capturedTask =
			new AtomicReference<>();

		when(executor.createTask(any(Runnable.class)))
			.thenAnswer((Answer<CancellableRunnable>) inv -> {
				final Runnable lambda = inv.getArgument(0);
				final TestCancellableRunnable task =
					new TestCancellableRunnable(lambda);
				capturedTask.set(task);
				return task;
			});

		doAnswer(inv -> {
			final CancellableRunnable task = capturedTask.get();
			task.run();
			return null;
		}).when(executor).execute(any());
	}

	/**
	 * Minimal concrete implementation of {@link EndpointExecutionContext}
	 * for testing the base class behavior.
	 */
	private static class TestEndpointExecutionContext
		extends EndpointExecutionContext {

		private String requestBodyContentType;
		private String preferredResponseContentType;

		TestEndpointExecutionContext(
			@Nonnull HttpRequest httpRequest,
			@Nonnull Evita evita,
			@Nonnull ServiceRequestContext serviceRequestContext
		) {
			super(httpRequest, evita, serviceRequestContext);
		}

		@Override
		public void provideRequestBodyContentType(
			@Nonnull String contentType
		) {
			this.requestBodyContentType = contentType;
		}

		@Nullable
		@Override
		public String requestBodyContentType() {
			return this.requestBodyContentType;
		}

		@Override
		public void providePreferredResponseContentType(
			@Nonnull String contentType
		) {
			this.preferredResponseContentType = contentType;
		}

		@Nullable
		@Override
		public String preferredResponseContentType() {
			return this.preferredResponseContentType;
		}
	}

	/**
	 * Test implementation of {@link CancellableRunnable} that wraps a
	 * delegate runnable and tracks completion state.
	 */
	private static class TestCancellableRunnable
		implements CancellableRunnable {

		private final Runnable delegate;
		private final CompletableFuture<Void> completion =
			new CompletableFuture<>();
		private volatile boolean finished = false;

		TestCancellableRunnable(@Nonnull Runnable delegate) {
			this.delegate = delegate;
		}

		@Override
		public void run() {
			try {
				this.delegate.run();
				this.finished = true;
				this.completion.complete(null);
			} catch (Throwable t) {
				this.finished = true;
				this.completion.completeExceptionally(t);
			}
		}

		@Override
		public void cancel() {
			this.finished = true;
			this.completion.complete(null);
		}

		@Override
		public boolean isFinished() {
			return this.finished;
		}

		@Nonnull
		@Override
		public CompletionStage<Void> completionStage() {
			return this.completion;
		}
	}
}
