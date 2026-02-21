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

import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.core.executor.CancellableRunnable;
import io.evitadb.core.executor.ObservableExecutorServiceWithCancellationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CancellationSupport} verifying that
 * Armeria request cancellation is properly wired to executor
 * tasks in both synchronous ({@code submitWithCancellation})
 * and asynchronous ({@code submitAsyncWithCancellation}) modes.
 *
 * @author evitaDB
 */
@DisplayName(
	"CancellationSupport - request cancellation wiring"
)
class CancellationSupportTest {

	private ServiceRequestContext requestContext;
	private ObservableExecutorServiceWithCancellationSupport executor;
	private CompletableFuture<Throwable> cancelFuture;

	@BeforeEach
	void setUp() {
		this.requestContext = mock(ServiceRequestContext.class);
		this.executor = mock(
			ObservableExecutorServiceWithCancellationSupport.class
		);
		this.cancelFuture = new CompletableFuture<>();
		when(this.requestContext.whenRequestCancelling())
			.thenReturn(this.cancelFuture);
	}

	@Nested
	@DisplayName("submitWithCancellation")
	class SubmitWithCancellation {

		@Test
		@DisplayName(
			"completes result when supplier succeeds"
		)
		void shouldCompleteResultWhenSupplierSucceeds()
			throws Exception {

			final AtomicReference<CancellableRunnable>
				capturedTask = new AtomicReference<>();
			configureMockExecutor(capturedTask, true);

			final CompletableFuture<String> result =
				CancellationSupport.submitWithCancellation(
					requestContext,
					executor,
					() -> "hello"
				);

			assertEquals(
				"hello",
				result.get(5, TimeUnit.SECONDS)
			);
		}

		@Test
		@DisplayName(
			"completes exceptionally when supplier throws"
		)
		void shouldCompleteExceptionallyWhenSupplierThrows()
			throws Exception {

			final AtomicReference<CancellableRunnable>
				capturedTask = new AtomicReference<>();
			configureMockExecutor(capturedTask, true);

			final CompletableFuture<String> result =
				CancellationSupport.submitWithCancellation(
					requestContext,
					executor,
					() -> {
						throw new RuntimeException("boom");
					}
				);

			assertTrue(result.isCompletedExceptionally());
			try {
				result.get(5, TimeUnit.SECONDS);
				fail("Should have thrown");
			} catch (ExecutionException ex) {
				assertEquals(
					"boom",
					ex.getCause().getMessage()
				);
			}
		}

		@Test
		@DisplayName(
			"cancels result when request is cancelled"
		)
		void shouldCancelResultWhenRequestCancelled()
			throws Exception {

			final AtomicReference<CancellableRunnable>
				capturedTask = new AtomicReference<>();
			// do NOT execute immediately -- simulate
			// pending task
			configureMockExecutor(capturedTask, false);

			final CompletableFuture<String> result =
				CancellationSupport.submitWithCancellation(
					requestContext,
					executor,
					() -> "never"
				);

			// trigger request cancellation
			cancelFuture.complete(
				new TimeoutException("client timeout")
			);

			// mark the task finished so the completionStage
			// fires
			final CancellableRunnable task =
				capturedTask.get();
			if (task instanceof TestCancellableRunnable tcr) {
				tcr.markFinished();
			}

			// result should be completed exceptionally or
			// cancelled
			assertTrue(
				result.isCompletedExceptionally()
					|| result.isCancelled(),
				"Result should be done after cancellation"
			);
		}
	}

	@Nested
	@DisplayName("submitAsyncWithCancellation")
	class SubmitAsyncWithCancellation {

		@Test
		@DisplayName(
			"completes when inner future completes"
		)
		void shouldCompleteWhenInnerFutureCompletes()
			throws Exception {

			final AtomicReference<CancellableRunnable>
				capturedTask = new AtomicReference<>();
			configureMockExecutor(capturedTask, true);

			final CompletableFuture<String> innerFuture =
				new CompletableFuture<>();

			final CompletableFuture<String> result =
				CancellationSupport
					.submitAsyncWithCancellation(
						requestContext,
						executor,
						() -> innerFuture
					);

			// task body has run but result is still pending
			// (inner future not yet done)
			// this is the expected happy path for async mode

			innerFuture.complete("hello-async");

			assertEquals(
				"hello-async",
				result.get(5, TimeUnit.SECONDS)
			);
		}

		@Test
		@DisplayName(
			"task completes before inner future (normal)"
		)
		void shouldNotCancelResultWhenTaskCompletes()
			throws Exception {

			final AtomicReference<CancellableRunnable>
				capturedTask = new AtomicReference<>();
			configureMockExecutor(capturedTask, true);

			final CompletableFuture<String> innerFuture =
				new CompletableFuture<>();

			final CompletableFuture<String> result =
				CancellationSupport
					.submitAsyncWithCancellation(
						requestContext,
						executor,
						() -> innerFuture
					);

			// at this point the task body has executed
			// (returned innerFuture), but result should
			// still be pending because innerFuture is not
			// done yet
			// -- this is the key difference from
			// submitWithCancellation

			// completing the inner future should complete
			// the result
			innerFuture.complete("eventually");

			assertEquals(
				"eventually",
				result.get(5, TimeUnit.SECONDS)
			);
		}

		@Test
		@DisplayName(
			"completes exceptionally on request cancel"
		)
		void shouldCompleteExceptionallyWhenRequestCancelled()
			throws Exception {

			final AtomicReference<CancellableRunnable>
				capturedTask = new AtomicReference<>();
			configureMockExecutor(capturedTask, true);

			final CompletableFuture<String> innerFuture =
				new CompletableFuture<>();

			final CompletableFuture<String> result =
				CancellationSupport
					.submitAsyncWithCancellation(
						requestContext,
						executor,
						() -> innerFuture
					);

			// trigger request cancellation
			cancelFuture.complete(
				new TimeoutException("cancelled")
			);

			assertTrue(
				result.isCompletedExceptionally(),
				"Result should fail after request cancel"
			);
		}

		@Test
		@DisplayName(
			"completes exceptionally when supplier throws"
		)
		void shouldCompleteExceptionallyWhenAsyncSupplierThrows()
			throws Exception {

			final AtomicReference<CancellableRunnable>
				capturedTask = new AtomicReference<>();
			configureMockExecutor(capturedTask, true);

			final CompletableFuture<String> result =
				CancellationSupport
					.submitAsyncWithCancellation(
						requestContext,
						executor,
						() -> {
							throw new RuntimeException(
								"sync setup fail"
							);
						}
					);

			assertTrue(result.isCompletedExceptionally());
			try {
				result.get(5, TimeUnit.SECONDS);
				fail("Should have thrown");
			} catch (ExecutionException ex) {
				assertEquals(
					"sync setup fail",
					ex.getCause().getMessage()
				);
			}
		}
	}

	@Nested
	@DisplayName("wireCancellation(ctx, task)")
	class WireCancellationCtxTask {

		@Test
		@DisplayName(
			"cancels task when request is cancelled"
		)
		void shouldCancelTaskWhenRequestCancelled() {
			final TestCancellableRunnable task =
				new TestCancellableRunnable(() -> {});

			CancellationSupport.wireCancellation(
				requestContext, task
			);

			cancelFuture.complete(
				new TimeoutException("client timeout")
			);

			assertTrue(
				task.cancelled,
				"Task should be cancelled after request cancellation"
			);
		}

		@Test
		@DisplayName(
			"does not cancel task when already finished"
		)
		void shouldNotCancelTaskWhenAlreadyFinished() {
			final TestCancellableRunnable task =
				new TestCancellableRunnable(() -> {});
			// run the task so it finishes
			task.run();

			CancellationSupport.wireCancellation(
				requestContext, task
			);

			cancelFuture.complete(
				new TimeoutException("late cancel")
			);

			assertFalse(
				task.cancelled,
				"Task should not be cancelled when already finished"
			);
		}

		@Test
		@DisplayName(
			"is no-op when context is null"
		)
		void shouldBeNoOpWhenContextIsNull() {
			final TestCancellableRunnable task =
				new TestCancellableRunnable(() -> {});

			assertDoesNotThrow(
				() -> CancellationSupport.wireCancellation(
					null, task
				)
			);

			assertFalse(
				task.cancelled,
				"Task should be untouched when context is null"
			);
		}
	}

	@Nested
	@DisplayName("wireCancellation(ctx, task, result)")
	class WireCancellationCtxTaskResult {

		@Test
		@DisplayName(
			"completes result exceptionally on cancellation"
		)
		void shouldCompleteResultExceptionallyOnCancellation()
			throws Exception {
			final TestCancellableRunnable task =
				new TestCancellableRunnable(() -> {});
			final CompletableFuture<String> result =
				new CompletableFuture<>();

			CancellationSupport.wireCancellation(
				requestContext, task, result
			);

			// trigger request cancellation before task finishes
			final TimeoutException cause =
				new TimeoutException("timeout");
			cancelFuture.complete(cause);

			// fire task completion stage
			task.markFinished();

			assertTrue(
				result.isCompletedExceptionally(),
				"Result should be completed exceptionally"
			);
			try {
				result.get(5, TimeUnit.SECONDS);
				fail("Should have thrown");
			} catch (ExecutionException ex) {
				assertInstanceOf(
					TimeoutException.class,
					ex.getCause()
				);
			}
		}

		@Test
		@DisplayName(
			"cancels result when task completes without cancellation"
		)
		void shouldCancelResultWhenTaskCompletesWithoutCancellation() {
			final TestCancellableRunnable task =
				new TestCancellableRunnable(() -> {});
			final CompletableFuture<String> result =
				new CompletableFuture<>();

			CancellationSupport.wireCancellation(
				requestContext, task, result
			);

			// mark task finished without request cancellation
			task.markFinished();

			assertTrue(
				result.isCancelled(),
				"Result should be cancelled via completionStage fallback"
			);
		}

		@Test
		@DisplayName(
			"does not affect result when result is already done"
		)
		void shouldNotAffectResultWhenResultAlreadyDone()
			throws Exception {

			final TestCancellableRunnable task =
				new TestCancellableRunnable(() -> {});
			final CompletableFuture<String> result =
				new CompletableFuture<>();

			CancellationSupport.wireCancellation(
				requestContext, task, result
			);

			// complete result first with a normal value
			result.complete("original");

			// then mark task finished
			task.markFinished();

			assertEquals(
				"original",
				result.get(5, TimeUnit.SECONDS),
				"Result should retain original value"
			);
		}

		@Test
		@DisplayName(
			"still wires completionStage when context is null"
		)
		void shouldStillWireCompletionStageWhenContextIsNull() {
			final TestCancellableRunnable task =
				new TestCancellableRunnable(() -> {});
			final CompletableFuture<String> result =
				new CompletableFuture<>();

			// pass null context -- cancellation wiring is skipped
			// but completionStage wiring should still run
			CancellationSupport.wireCancellation(
				null, task, result
			);

			task.markFinished();

			assertTrue(
				result.isCancelled(),
				"Result should be cancelled via completionStage even with null ctx"
			);
		}
	}

	/**
	 * Configures the mock executor to capture created tasks
	 * and optionally execute them immediately.
	 *
	 * @param capturedTask   holder for the captured task
	 * @param executeImmediately whether to run the task body
	 *                          immediately on execute()
	 */
	private void configureMockExecutor(
		@Nonnull AtomicReference<CancellableRunnable> capturedTask,
		boolean executeImmediately
	) {
		when(this.executor.createTask(any(Runnable.class)))
			.thenAnswer((Answer<CancellableRunnable>) inv -> {
				final Runnable lambda = inv.getArgument(0);
				final TestCancellableRunnable task =
					new TestCancellableRunnable(lambda);
				capturedTask.set(task);
				return task;
			});

		if (executeImmediately) {
			doAnswer(inv -> {
				final CancellableRunnable task =
					capturedTask.get();
				task.run();
				return null;
			}).when(this.executor).execute(any());
		} else {
			doAnswer(inv -> null)
				.when(this.executor).execute(any());
		}
	}

	/**
	 * Test implementation of {@link CancellableRunnable} that
	 * wraps a delegate runnable and tracks completion state.
	 */
	private static class TestCancellableRunnable
		implements CancellableRunnable {

		private final Runnable delegate;
		private final CompletableFuture<Void> completion =
			new CompletableFuture<>();
		private volatile boolean finished = false;
		private volatile boolean cancelled = false;

		TestCancellableRunnable(
			@Nonnull Runnable delegate
		) {
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
			this.cancelled = true;
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

		/**
		 * Manually marks the task as finished and fires
		 * the completion stage. Used in tests where the
		 * task body is not executed immediately.
		 */
		void markFinished() {
			this.finished = true;
			this.completion.complete(null);
		}
	}
}
