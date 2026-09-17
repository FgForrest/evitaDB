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

package io.evitadb.externalApi.observability.trace;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.api.observability.trace.DefaultTracingContext;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.query.head.Label;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.slf4j.MDC;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.OBSERVABILITY;
import static io.evitadb.test.TestTags.OBSERVABILITY_API;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies that {@link JsonApiTracingContext} publishes the caller's identity and the start of the request being
 * served to the MDC from **every** entry point it offers, not only the ones evitaDB's own handlers happen to use
 * today.
 *
 * The dynamic test over every entry point is the point of this class rather than an afterthought. Which overload a
 * caller lands on is decided purely by whether its lambda returns a value and whether it completes synchronously,
 * and neither says anything about whether client context is wanted: REST and GraphQL reach this class through the
 * {@link java.util.function.Supplier} and async variants only because they hand their result back to Armeria. An
 * API entry point added later that returns nothing would bind the {@link Runnable} variant instead and log without
 * a caller identity while looking perfectly correct at its call site.
 *
 * The sibling {@link GrpcTracingContextClientMetadataTest} makes the same claim for the gRPC implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("JsonApiTracingContext - client metadata publication")
@Tag(OBSERVABILITY_API)
@Tag(EXTERNAL_API)
@Tag(OBSERVABILITY)
class JsonApiTracingContextClientMetadataTest {

	/**
	 * Builds the context under test with the documented default header names.
	 *
	 * `new HeaderOptions()` carries those defaults; the builder starts every list empty, which is what a context
	 * nobody has configured reads - namely nothing at all.
	 *
	 * @return a configured context
	 */
	@Nonnull
	private static JsonApiTracingContext newTracingContext() {
		final JsonApiTracingContext tracingContext = new JsonApiTracingContext(DefaultTracingContext.INSTANCE);
		tracingContext.configureHeaders(new HeaderOptions());
		return tracingContext;
	}

	/**
	 * Builds an HTTP request carrying the headers a reverse proxy and an evitaDB client set.
	 *
	 * The header names are the ones `new HeaderOptions()` documents as defaults, so a default renamed in the
	 * configuration makes these tests fail rather than pass vacuously.
	 *
	 * @param ip  value of the forwarded-for header
	 * @param uri value of the forwarded-uri header
	 * @return a request carrying those headers
	 */
	@Nonnull
	private static HttpRequest request(@Nonnull String ip, @Nonnull String uri) {
		return HttpRequest.of(
			RequestHeaders.builder(HttpMethod.POST, "/gql")
				.add("X-Forwarded-For", ip)
				.add("X-Forwarded-Uri", uri)
				.add("X-EvitaDB-Label", "tenant=acme")
				.build()
		);
	}

	/**
	 * What the MDC and the label thread-local held inside a tracing block.
	 *
	 * @param clientIp     the published client IP address
	 * @param clientUri    the published client URI
	 * @param requestStart the published request start
	 * @param labels       the published client labels
	 */
	private record ContextSnapshot(
		@Nullable String clientIp,
		@Nullable String clientUri,
		@Nullable String requestStart,
		@Nullable Label[] labels
	) {
		/**
		 * Reads all four values off the calling thread as they stand right now.
		 *
		 * @return what the MDC and the label thread-local hold on this thread at this moment
		 */
		@Nonnull
		static ContextSnapshot current() {
			return new ContextSnapshot(
				MDC.get(TracingContext.MDC_CLIENT_IP_ADDRESS),
				MDC.get(TracingContext.MDC_CLIENT_URI),
				MDC.get(TracingContext.MDC_REQUEST_START_PROPERTY),
				TracingContext.CLIENT_LABELS.get()
			);
		}
	}

	@BeforeEach
	void setUp() {
		// sibling classes share the surefire worker thread, so an ambient MDC value would satisfy the assertions
		// below without the code under test doing anything
		TracingContext.clearContext();
	}

	@AfterEach
	void tearDown() {
		TracingContext.clearContext();
	}

	@Nested
	@DisplayName("request start")
	class RequestStart {

		private final JsonApiTracingContext tracingContext = newTracingContext();

		@Test
		@DisplayName("publishes the start of the request being served")
		void shouldPublishTheStartOfTheServedRequest() {
			final ServiceRequestContext ctx = ServiceRequestContext.builder(
				HttpRequest.of(HttpMethod.POST, "/gql")
			).build();

			try (SafeCloseable ignored = ctx.push()) {
				final ContextSnapshot inside = this.tracingContext.executeWithinBlock(
					"GraphQL", request("10.0.0.7", "/uri"), ContextSnapshot::current
				);

				assertEquals(
					Long.toString(ctx.log().partial().requestStartTimeMillis()),
					inside.requestStart()
				);
			}
		}

		@Test
		@DisplayName("leaves a request start restored on a worker thread alone")
		void shouldNotEraseTheRequestStartWhenNoServedRequestIsCurrent() {
			// a handler that opens this block on an evitaDB worker finds the captured context already restored and
			// no Armeria context current to read a request start from
			MDC.put(TracingContext.MDC_REQUEST_START_PROPERTY, "4242");

			final ContextSnapshot inside = this.tracingContext.executeWithinBlock(
				"GraphQL", request("10.0.0.7", "/uri"), ContextSnapshot::current
			);

			assertEquals("4242", inside.requestStart());
		}
	}

	@Nested
	@DisplayName("every entry point")
	class EveryEntryPoint {

		private final JsonApiTracingContext tracingContext = newTracingContext();

		/**
		 * A named way into the context, so a failing dynamic test says which entry point lost the client metadata.
		 *
		 * @param name       the entry point as it is written at a call site
		 * @param invocation the invocation itself, returning what the MDC held inside the block
		 */
		private record EntryPoint(
			@Nonnull String name,
			@Nonnull Function<HttpRequest, ContextSnapshot> invocation
		) {
		}

		/**
		 * Captures what the context held inside a block opened through the {@link Runnable} entry point.
		 *
		 * That entry point hands its result to nobody, so the snapshot has to be carried out of the block by a side
		 * effect.
		 *
		 * @param invoker opens the entry point under test with the runnable it is handed
		 * @return what the context held while the runnable ran, or null when the runnable never ran
		 */
		@Nullable
		private static ContextSnapshot viaRunnable(@Nonnull Consumer<Runnable> invoker) {
			final AtomicReference<ContextSnapshot> seen = new AtomicReference<>();
			invoker.accept(() -> seen.set(ContextSnapshot.current()));
			return seen.get();
		}

		@TestFactory
		@DisplayName("publishes the client context whichever entry point the caller lands on")
		Stream<DynamicTest> shouldPublishClientMetadataFromEveryEntryPoint() {
			final HttpRequest request = request("192.168.1.9", "/overload");

			final List<EntryPoint> entryPoints = List.of(
				new EntryPoint("Runnable", rq -> viaRunnable(
					runnable -> this.tracingContext.executeWithinBlock("GraphQL", rq, runnable))),
				new EntryPoint("Supplier", rq ->
					this.tracingContext.executeWithinBlock("GraphQL", rq, ContextSnapshot::current)),
				new EntryPoint("executeWithinBlockAsync", rq ->
					this.tracingContext.executeWithinBlockAsync(
						"GraphQL", rq, () -> CompletableFuture.completedFuture(ContextSnapshot.current())
					).join())
			);

			// the claim is a quantifier, so an entry point added to the interface must break this rather than
			// quietly go unexercised - and an empty list must never read as a pass
			final long declared = Stream.of(ExternalApiTracingContext.class.getDeclaredMethods())
				.filter(method -> !method.isSynthetic())
				.filter(method -> method.getName().startsWith("executeWithinBlock"))
				.count();
			assertEquals(
				declared, entryPoints.size(),
				"ExternalApiTracingContext declares an entry point this factory does not exercise"
			);

			return entryPoints.stream().map(
				entryPoint -> DynamicTest.dynamicTest(
					entryPoint.name(),
					() -> {
						final ContextSnapshot inside = entryPoint.invocation().apply(request);
						assertNotNull(inside, entryPoint.name() + " never ran the block");
						assertEquals(
							"192.168.1.9", inside.clientIp(), entryPoint.name() + " did not publish the client IP"
						);
						assertEquals(
							"/overload", inside.clientUri(), entryPoint.name() + " did not publish the client URI"
						);
						assertNotNull(
							inside.labels(), entryPoint.name() + " did not publish the client labels"
						);
						assertEquals(
							1, inside.labels().length, entryPoint.name() + " did not publish the client labels"
						);
					}
				)
			);
		}
	}
}
