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
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.api.observability.trace.DefaultTracingContext;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.query.head.Label;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.utils.ExternalApiTracingContext;
import io.grpc.Metadata;
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
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.OBSERVABILITY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link GrpcTracingContext} publishes the caller's identity and the start of the request being served
 * to the MDC, the way the JSON APIs already do, so that traffic recordings and any operator-supplied log layout can
 * see who called and how far into the request a line was written.
 *
 * The dynamic test over every entry point is the point of this class rather than an afterthought: which one a gRPC
 * service lands on is decided purely by whether its lambda returns a value and whether it completes synchronously,
 * neither of which says anything about whether client context is wanted. evitaDB's own services reach this class
 * exclusively through the {@link Runnable} variant, so instrumenting only the
 * {@link java.util.function.Supplier} one would leave every real call site without context while looking correct.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("GrpcTracingContext - client metadata publication")
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(OBSERVABILITY)
class GrpcTracingContextClientMetadataTest {

	/**
	 * The header names below are the ones `new HeaderOptions()` documents as defaults - the fixture configures that
	 * very instance, so a default renamed in the configuration makes these tests fail rather than pass vacuously.
	 */
	private static final Metadata.Key<String> FORWARDED_FOR =
		Metadata.Key.of("X-Forwarded-For", Metadata.ASCII_STRING_MARSHALLER);
	private static final Metadata.Key<String> FORWARDED_URI =
		Metadata.Key.of("X-Forwarded-Uri", Metadata.ASCII_STRING_MARSHALLER);
	private static final Metadata.Key<String> LABEL =
		Metadata.Key.of("X-EvitaDB-Label", Metadata.ASCII_STRING_MARSHALLER);

	/**
	 * Builds the context under test with the documented default header names.
	 *
	 * `new HeaderOptions()` carries those defaults; the builder starts every list empty, which is what a context
	 * nobody has configured reads - namely nothing at all.
	 *
	 * @return a configured context
	 */
	@Nonnull
	private static GrpcTracingContext newTracingContext() {
		final GrpcTracingContext tracingContext = new GrpcTracingContext(DefaultTracingContext.INSTANCE);
		tracingContext.configureHeaders(new HeaderOptions());
		return tracingContext;
	}

	/**
	 * Builds gRPC call metadata carrying the headers evitaDB's tracing decorator and clients set.
	 *
	 * @param ip     value of the forwarded-for header, null to omit
	 * @param uri    value of the forwarded-uri header, null to omit
	 * @param labels raw `name=value` label headers
	 * @return populated gRPC metadata
	 */
	@Nonnull
	private static Metadata metadata(@Nullable String ip, @Nullable String uri, @Nonnull String... labels) {
		final Metadata metadata = new Metadata();
		if (ip != null) {
			metadata.put(FORWARDED_FOR, ip);
		}
		if (uri != null) {
			metadata.put(FORWARDED_URI, uri);
		}
		for (final String label : labels) {
			metadata.put(LABEL, label);
		}
		return metadata;
	}

	/**
	 * Snapshot of what the MDC and the label thread-local held at a point in time.
	 *
	 * @param clientIp     value of the client IP MDC key
	 * @param clientUri    value of the client URI MDC key
	 * @param requestStart value of the request-start MDC key
	 * @param labels       contents of the client-label thread local
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
	@DisplayName("client metadata taken from the call headers")
	class ClientMetadataExtraction {

		private final GrpcTracingContext tracingContext = newTracingContext();

		@Test
		@DisplayName("publishes client IP, URI and labels taken from the call metadata")
		void shouldPublishClientMetadata() {
			final Metadata metadata = metadata("10.0.0.7", "/some/uri", "tenant=acme", "channel=mobile");

			final ContextSnapshot inside = this.tracingContext.executeWithinBlock(
				"gRPC", metadata, ContextSnapshot::current
			);

			assertEquals("10.0.0.7", inside.clientIp());
			assertEquals("/some/uri", inside.clientUri());
			assertArrayEquals(
				new Label[]{new Label("tenant", "acme"), new Label("channel", "mobile")},
				inside.labels()
			);
		}

		@Test
		@DisplayName("reads labels from the label headers, not from the client-address headers")
		void shouldNotDeriveLabelsFromForwardedForHeader() {
			// `Forwarded: for=1.2.3.4` parses into a `name=value` pair, so a context that sourced labels from the
			// client-address headers would invent a bogus `for` label out of the caller's IP - asserting the one
			// real label rather than an empty array keeps this from passing if label extraction breaks entirely
			final Metadata metadata = metadata(null, null, "tenant=acme");
			metadata.put(
				Metadata.Key.of("Forwarded", Metadata.ASCII_STRING_MARSHALLER), "for=1.2.3.4;proto=https"
			);

			final ContextSnapshot inside = this.tracingContext.executeWithinBlock(
				"gRPC", metadata, ContextSnapshot::current
			);

			assertArrayEquals(new Label[]{new Label("tenant", "acme")}, inside.labels());
		}

		@Test
		@DisplayName("drops a label header that carries no name/value separator")
		void shouldDropALabelHeaderWithoutASeparator() {
			// a usable label is sent alongside, so this cannot pass by label extraction having broken entirely
			final ContextSnapshot inside = this.tracingContext.executeWithinBlock(
				"gRPC", metadata(null, null, "justaname", "tenant=acme"), ContextSnapshot::current
			);

			assertArrayEquals(new Label[]{new Label("tenant", "acme")}, inside.labels());
		}

		@Test
		@DisplayName("splits a label on its first separator so the value may contain more of them")
		void shouldSplitALabelOnItsFirstSeparatorOnly() {
			final ContextSnapshot inside = this.tracingContext.executeWithinBlock(
				"gRPC", metadata(null, null, "filter=a=b"), ContextSnapshot::current
			);

			assertArrayEquals(new Label[]{new Label("filter", "a=b")}, inside.labels());
		}

		@Test
		@DisplayName("drops a label header whose name would be empty")
		void shouldDropALabelHeaderWithAnEmptyName() {
			// a label named by the empty string is not something a traffic recording can ever be filtered by; a
			// usable label is sent alongside so this cannot pass by label extraction having broken entirely
			final ContextSnapshot inside = this.tracingContext.executeWithinBlock(
				"gRPC", metadata(null, null, "=orphan", "tenant=acme"), ContextSnapshot::current
			);

			assertArrayEquals(new Label[]{new Label("tenant", "acme")}, inside.labels());
		}

		@Test
		@DisplayName("takes one client IP from a repeated forwarded-for header but every repeated label")
		void shouldReadOneClientIpButEveryLabelFromRepeatedHeaders() {
			final Metadata metadata = new Metadata();
			metadata.put(FORWARDED_FOR, "10.0.0.7");
			metadata.put(FORWARDED_FOR, "10.0.0.8");
			metadata.put(LABEL, "tenant=acme");
			metadata.put(LABEL, "channel=mobile");

			final ContextSnapshot inside = this.tracingContext.executeWithinBlock(
				"gRPC", metadata, ContextSnapshot::current
			);

			// the IP is read with the single-valued getter, which answers with the *last* entry; labels
			// deliberately use the multi-valued one, because a label header legitimately repeats - a later
			// refactor unifying the two would change one of these
			assertEquals("10.0.0.8", inside.clientIp());
			assertArrayEquals(
				new Label[]{new Label("tenant", "acme"), new Label("channel", "mobile")},
				inside.labels()
			);
		}

		@Test
		@DisplayName("reads the client IP from every configured forwarded-for header")
		void shouldReadTheClientIpFromEveryConfiguredForwardedForHeader() {
			// the defaults list `Forwarded`, `X-Forwarded-For` and `X-Real-IP`; a proxy typically sets exactly one
			// of them, so honouring only one name leaves `client_ip` empty behind the other two
			final Metadata metadata = new Metadata();
			metadata.put(Metadata.Key.of("X-Real-IP", Metadata.ASCII_STRING_MARSHALLER), "10.0.0.7");

			final ContextSnapshot inside = this.tracingContext.executeWithinBlock(
				"gRPC", metadata, ContextSnapshot::current
			);

			assertEquals("10.0.0.7", inside.clientIp());
		}

		@Test
		@DisplayName("prefers the first configured forwarded-for header the call carries")
		void shouldPreferTheFirstConfiguredForwardedForHeader() {
			// `Forwarded` is configured ahead of `X-Real-IP`, and the order the operator wrote is the order the
			// headers are tried in - the nearest proxy wins rather than whichever header happens to be read last
			final Metadata metadata = new Metadata();
			metadata.put(Metadata.Key.of("Forwarded", Metadata.ASCII_STRING_MARSHALLER), "for=1.2.3.4");
			metadata.put(Metadata.Key.of("X-Real-IP", Metadata.ASCII_STRING_MARSHALLER), "10.0.0.7");

			final ContextSnapshot inside = this.tracingContext.executeWithinBlock(
				"gRPC", metadata, ContextSnapshot::current
			);

			assertEquals("for=1.2.3.4", inside.clientIp());
		}
	}

	@Nested
	@DisplayName("request start")
	class RequestStart {

		private final GrpcTracingContext tracingContext = newTracingContext();

		@Test
		@DisplayName("publishes the start of the request being served")
		void shouldPublishTheStartOfTheServedRequest() {
			final ServiceRequestContext ctx = ServiceRequestContext.builder(
				HttpRequest.of(HttpMethod.POST, "/io.evitadb.EvitaService/Query")
			).build();

			try (SafeCloseable ignored = ctx.push()) {
				final ContextSnapshot inside = this.tracingContext.executeWithinBlock(
					"gRPC", metadata("10.0.0.7", "/uri"), ContextSnapshot::current
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
			// the gRPC session path opens this block on an evitaDB worker, where the captured context has already
			// been restored and no Armeria context is current to read a request start from
			MDC.put(TracingContext.MDC_REQUEST_START_PROPERTY, "4242");

			final ContextSnapshot inside = this.tracingContext.executeWithinBlock(
				"gRPC", metadata("10.0.0.7", "/uri"), ContextSnapshot::current
			);

			assertEquals("4242", inside.requestStart());
			assertEquals("4242", MDC.get(TracingContext.MDC_REQUEST_START_PROPERTY));
		}

		@Test
		@DisplayName("restores the previous context once the block finishes")
		void shouldNotLeakClientMetadata() {
			MDC.put(TracingContext.MDC_REQUEST_START_PROPERTY, "555");

			this.tracingContext.executeWithinBlock("gRPC", metadata("10.0.0.7", "/uri"), ContextSnapshot::current);

			assertNull(MDC.get(TracingContext.MDC_CLIENT_IP_ADDRESS));
			assertNull(MDC.get(TracingContext.MDC_CLIENT_URI));
			assertNull(TracingContext.CLIENT_LABELS.get());
			// the request start is *restored*, not removed - that is what makes these scopes nestable
			assertEquals("555", MDC.get(TracingContext.MDC_REQUEST_START_PROPERTY));
		}
	}

	@Nested
	@DisplayName("header configuration")
	class HeaderConfiguration {

		@Test
		@DisplayName("rejects a header name gRPC cannot represent while the context is being configured")
		void shouldRejectAHeaderNameGrpcCannotRepresent() {
			// header names arrive from free-form operator configuration, so the one name gRPC cannot represent has
			// to be reported once, while the server is being built, rather than on every call that arrives later
			final GrpcTracingContext tracingContext = new GrpcTracingContext(DefaultTracingContext.INSTANCE);

			final IllegalArgumentException ex = assertThrows(
				IllegalArgumentException.class,
				() -> tracingContext.configureHeaders(HeaderOptions.builder().label("X-Bad Header").build())
			);

			assertTrue(
				ex.getMessage().contains("X-Bad Header"),
				"the operator is told which header they configured: " + ex.getMessage()
			);
		}

		@Test
		@DisplayName("serves calls unharmed after a header configuration was rejected")
		void shouldServeCallsAfterARejectedHeaderConfiguration() {
			final GrpcTracingContext tracingContext = new GrpcTracingContext(DefaultTracingContext.INSTANCE);
			final Runnable noop = () -> {
			};

			assertThrows(
				IllegalArgumentException.class,
				() -> tracingContext.configureHeaders(HeaderOptions.builder().label("X-Bad Header").build())
			);

			// the rejected configuration is not applied even in part, so no call inherits the unusable name - which
			// is the whole point of resolving the keys before a request can reach them
			assertDoesNotThrow(() -> tracingContext.executeWithinBlock("gRPC", new Metadata(), noop));
		}
	}

	@Nested
	@DisplayName("every entry point")
	class EveryEntryPoint {

		private final GrpcTracingContext tracingContext = newTracingContext();

		/**
		 * A named way into the context, so a failing dynamic test says which entry point lost the client metadata.
		 *
		 * @param name       the entry point as it is written at a call site
		 * @param invocation the invocation itself, returning what the MDC held inside the block
		 */
		private record EntryPoint(
			@Nonnull String name,
			@Nonnull Function<Metadata, ContextSnapshot> invocation
		) {
		}

		/**
		 * Captures what the context held inside a block opened through one of the {@link Runnable} entry points.
		 *
		 * Those entry points hand their result to nobody, so the snapshot has to be carried out of the block by a
		 * side effect - written once here rather than in each of the three table rows that need it.
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
			final Metadata metadata = metadata("192.168.1.9", "/overload");

			final List<EntryPoint> entryPoints = List.of(
				new EntryPoint("Runnable", md -> viaRunnable(
					runnable -> this.tracingContext.executeWithinBlock("gRPC", md, runnable))),
				new EntryPoint("Supplier", md ->
					this.tracingContext.executeWithinBlock("gRPC", md, ContextSnapshot::current)),
				new EntryPoint("executeWithinBlockAsync", md ->
					this.tracingContext.executeWithinBlockAsync(
						"gRPC", md, () -> CompletableFuture.completedFuture(ContextSnapshot.current())
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
						final ContextSnapshot inside = entryPoint.invocation().apply(metadata);
						assertEquals(
							"192.168.1.9", inside.clientIp(), entryPoint.name() + " did not publish the client IP"
						);
						assertEquals(
							"/overload", inside.clientUri(), entryPoint.name() + " did not publish the client URI"
						);
					}
				)
			);
		}
	}
}
