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

package io.evitadb.externalApi.graphql.api.tracing;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.Scalars;
import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters;
import graphql.schema.DataFetcher;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import io.evitadb.api.observability.trace.TracingBlockReference;
import io.evitadb.externalApi.graphql.api.catalog.GraphQLContextKey;
import io.evitadb.externalApi.observability.trace.JsonApiTracingContext;
import io.evitadb.externalApi.observability.trace.OpenTelemetryTracerSetup;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.OBSERVABILITY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * Verifies that the span {@link OperationTracingInstrumentation} opens for a GraphQL operation does not stay attached
 * to the thread that started the operation. The operation is started on a request thread inside the request's own
 * tracing block - {@link JsonApiTracingContext#executeWithinBlockAsync}, as `GraphQLWebHandler` uses it - while its
 * result is completed by whichever thread finishes the last asynchronous data fetcher. The span may end on any
 * thread, but its scope belongs to the thread that opened it.
 *
 * Tracing is switched on by a static mock of {@link OpenTelemetryTracerSetup} backed by an in-memory OpenTelemetry
 * SDK. A static mock is confined to the thread that opened it, so the request side runs on a single thread owned by
 * the test - which also keeps whatever context a failing run leaves behind away from the shared JUnit threads.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("OperationTracingInstrumentation - operation span scope")
@Tag(GRAPHQL)
@Tag(EXTERNAL_API)
@Tag(OBSERVABILITY)
class OperationTracingInstrumentationTest {

	/**
	 * Name of the GraphQL operation every test executes.
	 */
	private static final String OPERATION_NAME = "ProductListing";
	/**
	 * Query document every test executes - a single field served by the test's data fetcher.
	 */
	private static final String QUERY = "query " + OPERATION_NAME + " { value }";
	/**
	 * Name of the span the instrumentation opens for {@link #QUERY}.
	 */
	private static final String OPERATION_SPAN_NAME = "GraphQL query - " + OPERATION_NAME;
	/**
	 * Name of the span the request-level tracing block opens around the operation.
	 */
	private static final String REQUEST_SPAN_NAME = "GraphQL";
	/**
	 * Value the data fetcher of the `value` field resolves to.
	 */
	private static final String FETCHED_VALUE = "fetched";
	/**
	 * Upper bound of every wait in this test.
	 */
	private static final long TIMEOUT_SECONDS = 30L;

	/**
	 * Collects the spans ended during the test.
	 */
	private InMemorySpanExporter spanExporter;
	/**
	 * Tracer provider exporting synchronously into {@link #spanExporter}.
	 */
	private SdkTracerProvider tracerProvider;
	/**
	 * OpenTelemetry SDK the mocked {@link OpenTelemetryTracerSetup} hands out, propagating the W3C headers like
	 * the production setup does.
	 */
	private OpenTelemetry openTelemetry;
	/**
	 * Tracer the mocked {@link OpenTelemetryTracerSetup} hands out.
	 */
	private Tracer tracer;
	/**
	 * The single thread standing in for a thread of the request executor.
	 */
	private ExecutorService requestThread;
	/**
	 * The single thread standing in for the thread that finishes an asynchronous data fetcher.
	 */
	private ExecutorService fetcherThread;

	/**
	 * Creates the in-memory OpenTelemetry SDK and the two threads standing in for the request and fetcher threads.
	 */
	@BeforeEach
	void setUp() {
		this.spanExporter = InMemorySpanExporter.create();
		this.tracerProvider = SdkTracerProvider.builder()
			.addSpanProcessor(SimpleSpanProcessor.create(this.spanExporter))
			.build();
		this.openTelemetry = OpenTelemetrySdk.builder()
			.setTracerProvider(this.tracerProvider)
			.setPropagators(
				ContextPropagators.create(
					TextMapPropagator.composite(
						W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance()
					)
				)
			)
			.build();
		this.tracer = this.tracerProvider.get("test");
		this.requestThread = Executors.newSingleThreadExecutor(
			runnable -> new Thread(runnable, "graphql-tracing-test-request")
		);
		this.fetcherThread = Executors.newSingleThreadExecutor(
			runnable -> new Thread(runnable, "graphql-tracing-test-fetcher")
		);
	}

	/**
	 * Stops both threads and the tracer provider.
	 *
	 * @throws InterruptedException when interrupted while waiting for the threads to stop
	 */
	@AfterEach
	void tearDown() throws InterruptedException {
		this.requestThread.shutdownNow();
		this.fetcherThread.shutdownNow();
		this.requestThread.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		this.fetcherThread.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		this.tracerProvider.shutdown();
	}

	/**
	 * The operation is completed by another thread, as it is when an asynchronous data fetcher finishes last: the
	 * operation span must stay open until then, the request thread must be back in its original context and the
	 * completing thread must keep its own.
	 *
	 * @throws Exception when a thread fails or does not finish in time
	 */
	@Test
	@DisplayName("request thread gets its context back when the operation completes on another thread")
	void shouldRestoreRequestThreadContextWhenOperationCompletesOnAnotherThread() throws Exception {
		final CompletableFuture<Object> fetchedValue = new CompletableFuture<>();
		final AtomicReference<Span> spanSeenByFetcher = new AtomicReference<>();
		final RequestOutcome request = startRequestOnRequestThread(createGraphQL(fetchedValue, spanSeenByFetcher));

		// the operation is still waiting for its data fetcher, so it will be completed by the fetcher thread
		assertFalse(
			request.response().isDone(), "The operation must still be running when the request thread returns."
		);
		// the instrumentation really opened the operation span on the request thread
		assertEquals(OPERATION_SPAN_NAME, spanName(spanSeenByFetcher.get()));
		assertTrue(
			findSpans(OPERATION_SPAN_NAME).isEmpty(),
			"Releasing the scope must not end the operation span while the operation is still running."
		);

		final ThreadContexts fetcherContexts = completeOnFetcherThread(fetchedValue);
		request.response().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

		assertSame(
			fetcherContexts.before(), fetcherContexts.after(),
			() -> "Completing the operation changed the context of the fetcher thread to the span `" +
				spanName(Span.fromContext(fetcherContexts.after())) + "`."
		);
		assertSame(
			request.contextBefore(), request.contextAfter(),
			() -> "The request thread still has the span `" + spanName(Span.fromContext(request.contextAfter())) +
				"` current after the operation was dispatched."
		);
		assertOperationSpanEndedUnderRequestSpan();
	}

	/**
	 * The operation completes before the request thread returns, as it does with synchronous data fetchers: the
	 * request thread must be back in its original context as well.
	 *
	 * @throws Exception when a thread fails or does not finish in time
	 */
	@Test
	@DisplayName("request thread gets its context back when the operation completes before it returns")
	void shouldRestoreRequestThreadContextWhenOperationCompletesSynchronously() throws Exception {
		final AtomicReference<Span> spanSeenByFetcher = new AtomicReference<>();
		final RequestOutcome request = startRequestOnRequestThread(
			createGraphQL(CompletableFuture.completedFuture(FETCHED_VALUE), spanSeenByFetcher)
		);

		assertTrue(request.response().isDone(), "The operation must be complete when the request thread returns.");
		assertEquals(OPERATION_SPAN_NAME, spanName(spanSeenByFetcher.get()));
		assertSame(
			request.contextBefore(), request.contextAfter(),
			() -> "The request thread still has the span `" + spanName(Span.fromContext(request.contextAfter())) +
				"` current after the operation completed."
		);
		assertOperationSpanEndedUnderRequestSpan();
	}

	/**
	 * A request thread that served an operation completed elsewhere must not pass anything of it on to the next
	 * request it serves: without a `traceparent` header, that request must start a trace of its own.
	 *
	 * @throws Exception when a thread fails or does not finish in time
	 */
	@Test
	@DisplayName("next request on the same thread starts its own trace")
	void shouldStartNewTraceForNextRequestOnSameThreadWhenPreviousOperationCompletedElsewhere() throws Exception {
		final CompletableFuture<Object> firstFetchedValue = new CompletableFuture<>();
		final RequestOutcome firstRequest = startRequestOnRequestThread(
			createGraphQL(firstFetchedValue, new AtomicReference<>())
		);
		completeOnFetcherThread(firstFetchedValue);
		firstRequest.response().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

		final RequestOutcome secondRequest = startRequestOnRequestThread(
			createGraphQL(CompletableFuture.completedFuture(FETCHED_VALUE), new AtomicReference<>())
		);
		secondRequest.response().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

		// neither request carries a `traceparent` header, so each of them must open a trace of its own
		final List<SpanData> requestSpans = findSpans(REQUEST_SPAN_NAME);
		assertEquals(2, requestSpans.size());
		for (final SpanData requestSpan : requestSpans) {
			assertFalse(
				requestSpan.getParentSpanContext().isValid(),
				() -> "The request span `" + requestSpan.getSpanId() + "` was attached to the parent span `" +
					requestSpan.getParentSpanId() + "` left behind by an earlier request."
			);
		}
		assertNotEquals(requestSpans.get(0).getTraceId(), requestSpans.get(1).getTraceId());
	}

	/**
	 * An instrumentation chained after the tracing one fails as the operation starts, as
	 * `EvitaSessionManagingInstrumentation` does when it cannot open a session, so the operation is never dispatched.
	 * The failure completes the execution before the request thread returns, so the completion stage closing the
	 * operation's tracing block runs on the request thread and must release the scope there, ending the operation span
	 * as failed.
	 *
	 * @throws Exception when a thread fails or does not finish in time
	 */
	@Test
	@DisplayName("request thread gets its context back when a failure prevents the dispatch of the operation")
	void shouldRestoreRequestThreadContextWhenFailurePreventsDispatchOfOperation() throws Exception {
		final AtomicReference<Span> spanSeenByFetcher = new AtomicReference<>();
		final RequestOutcome request = startRequestOnRequestThread(
			createGraphQL(
				CompletableFuture.completedFuture(FETCHED_VALUE),
				spanSeenByFetcher,
				new ChainedInstrumentation(
					new OperationTracingInstrumentation(), new OperationStartFailingInstrumentation()
				)
			)
		);

		assertTrue(
			request.response().isCompletedExceptionally(),
			"The failed operation must be complete when the request thread returns."
		);
		assertNull(spanSeenByFetcher.get(), "The data fetcher must not run when the operation failed to start.");
		assertSame(
			request.contextBefore(), request.contextAfter(),
			() -> "The request thread still has the span `" + spanName(Span.fromContext(request.contextAfter())) +
				"` current after the operation failed to start."
		);
		assertOperationSpanEndedUnderRequestSpan();
		assertEquals(StatusCode.ERROR, findSpans(OPERATION_SPAN_NAME).get(0).getStatus().getStatusCode());
	}

	/**
	 * Starts a GraphQL request on {@link #requestThread} the way `GraphQLWebHandler` does: inside the request-level
	 * tracing block of {@link JsonApiTracingContext#executeWithinBlockAsync}, closing the operation's tracing block
	 * when the execution completes. Returns as soon as the request thread is done with its part, i.e. possibly
	 * before the operation completes.
	 *
	 * @param graphQL the GraphQL instance to execute {@link #QUERY} with
	 * @return contexts of the request thread around the request and the future of the operation's result
	 * @throws Exception when the request thread fails or does not finish in time
	 */
	@Nonnull
	private RequestOutcome startRequestOnRequestThread(@Nonnull GraphQL graphQL) throws Exception {
		return this.requestThread.submit(() -> {
			try (MockedStatic<OpenTelemetryTracerSetup> tracerSetup = mockStatic(OpenTelemetryTracerSetup.class)) {
				tracerSetup.when(OpenTelemetryTracerSetup::isTracingEnabled).thenReturn(true);
				tracerSetup.when(OpenTelemetryTracerSetup::getOpenTelemetry).thenReturn(this.openTelemetry);
				tracerSetup.when(OpenTelemetryTracerSetup::getTracer).thenReturn(this.tracer);

				final JsonApiTracingContext tracingContext = new JsonApiTracingContext();
				final HttpRequest httpRequest = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/gql/test"));
				final ExecutionInput executionInput = ExecutionInput.newExecutionInput(QUERY).build();

				final Context contextBefore = Context.current();
				final CompletableFuture<ExecutionResult> response = tracingContext.executeWithinBlockAsync(
					REQUEST_SPAN_NAME,
					httpRequest,
					() -> graphQL.executeAsync(executionInput)
						.handle((result, error) -> closeOperationBlock(executionInput, result, error))
				);
				return new RequestOutcome(contextBefore, Context.current(), response);
			}
		}).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	/**
	 * Completes the data fetcher's value on {@link #fetcherThread}, which therefore runs the rest of the operation
	 * including the completion stage closing the operation's tracing block.
	 *
	 * @param fetchedValue the pending value of the data fetcher
	 * @return contexts of the fetcher thread before and after the completion
	 * @throws Exception when the fetcher thread fails or does not finish in time
	 */
	@Nonnull
	private ThreadContexts completeOnFetcherThread(@Nonnull CompletableFuture<Object> fetchedValue) throws Exception {
		return this.fetcherThread.submit(() -> {
			final Context contextBefore = Context.current();
			fetchedValue.complete(FETCHED_VALUE);
			return new ThreadContexts(contextBefore, Context.current());
		}).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	/**
	 * Asserts that the operation span ended and is a child of the request span.
	 */
	private void assertOperationSpanEndedUnderRequestSpan() {
		final List<SpanData> requestSpans = findSpans(REQUEST_SPAN_NAME);
		final List<SpanData> operationSpans = findSpans(OPERATION_SPAN_NAME);
		assertEquals(1, requestSpans.size());
		assertEquals(1, operationSpans.size());
		assertEquals(requestSpans.get(0).getSpanId(), operationSpans.get(0).getParentSpanId());
	}

	/**
	 * Returns the ended spans of the given name in the order they ended.
	 *
	 * @param name name of the spans to return
	 * @return the matching spans
	 */
	@Nonnull
	private List<SpanData> findSpans(@Nonnull String name) {
		final List<SpanData> finishedSpans = this.spanExporter.getFinishedSpanItems();
		final List<SpanData> result = new ArrayList<>(finishedSpans.size());
		for (final SpanData span : finishedSpans) {
			if (name.equals(span.getName())) {
				result.add(span);
			}
		}
		return result;
	}

	/**
	 * Creates a GraphQL instance with the instrumentation under test alone and a single `value` field whose data
	 * fetcher returns the given future and records the span current while it runs.
	 *
	 * @param fetchedValue      the future the data fetcher returns
	 * @param spanSeenByFetcher receives the span current on the thread running the data fetcher
	 * @return the GraphQL instance
	 */
	@Nonnull
	private static GraphQL createGraphQL(
		@Nonnull CompletableFuture<Object> fetchedValue,
		@Nonnull AtomicReference<Span> spanSeenByFetcher
	) {
		return createGraphQL(fetchedValue, spanSeenByFetcher, new OperationTracingInstrumentation());
	}

	/**
	 * Creates a GraphQL instance with the given instrumentation and a single `value` field whose data fetcher returns
	 * the given future and records the span current while it runs.
	 *
	 * @param fetchedValue      the future the data fetcher returns
	 * @param spanSeenByFetcher receives the span current on the thread running the data fetcher
	 * @param instrumentation   the instrumentation of the GraphQL instance, including the instrumentation under test
	 * @return the GraphQL instance
	 */
	@Nonnull
	private static GraphQL createGraphQL(
		@Nonnull CompletableFuture<Object> fetchedValue,
		@Nonnull AtomicReference<Span> spanSeenByFetcher,
		@Nonnull Instrumentation instrumentation
	) {
		final DataFetcher<CompletableFuture<Object>> valueFetcher = environment -> {
			spanSeenByFetcher.set(Span.current());
			return fetchedValue;
		};
		final GraphQLSchema schema = GraphQLSchema.newSchema()
			.query(
				GraphQLObjectType.newObject()
					.name("Query")
					.field(GraphQLFieldDefinition.newFieldDefinition().name("value").type(Scalars.GraphQLString))
			)
			.codeRegistry(
				GraphQLCodeRegistry.newCodeRegistry()
					.dataFetcher(FieldCoordinates.coordinates("Query", "value"), valueFetcher)
					.build()
			)
			.build();
		return GraphQL.newGraphQL(schema)
			.instrumentation(instrumentation)
			.build();
	}

	/**
	 * Does what `GraphQLWebHandler` does when the execution of an operation completes, on whichever thread
	 * completed it: records the failure in the operation's tracing block, if there is any, and closes the block.
	 *
	 * @param executionInput the input the operation was started with
	 * @param result         the result of the operation, if it succeeded
	 * @param error          the failure of the operation, if it failed
	 * @return the result of the operation
	 */
	@Nullable
	private static ExecutionResult closeOperationBlock(
		@Nonnull ExecutionInput executionInput,
		@Nullable ExecutionResult result,
		@Nullable Throwable error
	) {
		final TracingBlockReference blockReference =
			executionInput.getGraphQLContext().get(GraphQLContextKey.OPERATION_TRACING_BLOCK);
		if (blockReference != null) {
			if (error != null) {
				blockReference.setError(error);
			}
			blockReference.close();
		}
		if (error != null) {
			throw new CompletionException(error);
		}
		return result;
	}

	/**
	 * Returns the name of the span, or a placeholder when the span is not recorded by the test SDK.
	 *
	 * @param span the span to name
	 * @return the name of the span
	 */
	@Nonnull
	private static String spanName(@Nullable Span span) {
		return span instanceof ReadableSpan readableSpan ? readableSpan.getName() : "<no recorded span>";
	}

	/**
	 * Fails the start of every operation, as an instrumentation chained after the tracing one does when it cannot
	 * prepare the operation - for example when no evitaDB session can be opened for it.
	 */
	private static class OperationStartFailingInstrumentation extends SimplePerformantInstrumentation {

		/**
		 * Throws instead of returning a context, so the operation fails before it is dispatched.
		 *
		 * @throws IllegalStateException always
		 */
		@Nonnull
		@Override
		public InstrumentationContext<ExecutionResult> beginExecuteOperation(
			@Nonnull InstrumentationExecuteOperationParameters parameters,
			@Nullable InstrumentationState state
		) {
			throw new IllegalStateException("The operation cannot be started.");
		}

	}

	/**
	 * State of a request started by {@link #startRequestOnRequestThread(GraphQL)}.
	 *
	 * @param contextBefore context of the request thread before the request started
	 * @param contextAfter  context of the request thread after it finished its part of the request
	 * @param response      the future of the operation's result
	 */
	private record RequestOutcome(
		@Nonnull Context contextBefore,
		@Nonnull Context contextAfter,
		@Nonnull CompletableFuture<ExecutionResult> response
	) {
	}

	/**
	 * Contexts of a thread before and after it did a piece of work.
	 *
	 * @param before context before the work
	 * @param after  context after the work
	 */
	private record ThreadContexts(
		@Nonnull Context before,
		@Nonnull Context after
	) {
	}

}
