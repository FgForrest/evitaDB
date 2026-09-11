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

import io.evitadb.api.observability.trace.DefaultTracingContext;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.api.query.head.Label;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.grpc.Metadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import org.slf4j.MDC;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.OBSERVABILITY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies that {@link GrpcTracingContext} publishes the caller's identity to the MDC, the way the JSON APIs already
 * do, so that traffic recordings and any operator-supplied log layout can see who called.
 *
 * The dynamic test over all six `executeWithinBlock` overloads is the point of this class rather than an
 * afterthought: which overload a gRPC service lands on is decided purely by whether its lambda returns a value and
 * whether it passes span attributes, neither of which says anything about whether client context is wanted.
 * evitaDB's own services reach this class exclusively through the {@link Runnable} variants, so instrumenting only
 * the {@link java.util.function.Supplier} ones would leave every real call site without context while looking
 * correct.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("GrpcTracingContext - client metadata publication")
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(OBSERVABILITY)
class GrpcTracingContextClientMetadataTest {

	private static final String FORWARDED_FOR = "X-Forwarded-For";
	private static final String FORWARDED_URI = "X-Forwarded-Uri";
	private static final String LABEL = "X-EvitaDB-Label";

	private GrpcTracingContext tracingContext;

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
			metadata.put(Metadata.Key.of(FORWARDED_FOR, Metadata.ASCII_STRING_MARSHALLER), ip);
		}
		if (uri != null) {
			metadata.put(Metadata.Key.of(FORWARDED_URI, Metadata.ASCII_STRING_MARSHALLER), uri);
		}
		for (String label : labels) {
			metadata.put(Metadata.Key.of(LABEL, Metadata.ASCII_STRING_MARSHALLER), label);
		}
		return metadata;
	}

	/**
	 * Snapshot of what the MDC and the label thread-local held at a point in time.
	 *
	 * @param clientIp  value of the client IP MDC key
	 * @param clientUri value of the client URI MDC key
	 * @param labels    contents of the client-label thread local
	 */
	private record ContextSnapshot(
		@Nullable String clientIp,
		@Nullable String clientUri,
		@Nullable Label[] labels
	) {
		@Nonnull
		static ContextSnapshot current() {
			return new ContextSnapshot(
				MDC.get(TracingContext.MDC_CLIENT_IP_ADDRESS),
				MDC.get(TracingContext.MDC_CLIENT_URI),
				TracingContext.CLIENT_LABELS.get()
			);
		}
	}

	@BeforeEach
	void setUp() {
		this.tracingContext = new GrpcTracingContext(DefaultTracingContext.INSTANCE);
		// `new HeaderOptions()` carries the documented default header names; the builder starts every list empty
		this.tracingContext.configureHeaders(new HeaderOptions());
	}

	@AfterEach
	void tearDown() {
		TracingContext.clearContext();
	}

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
		// client-address headers would invent a bogus `for` label out of the caller's IP
		final Metadata metadata = new Metadata();
		metadata.put(Metadata.Key.of("Forwarded", Metadata.ASCII_STRING_MARSHALLER), "for=1.2.3.4;proto=https");

		final ContextSnapshot inside = this.tracingContext.executeWithinBlock(
			"gRPC", metadata, ContextSnapshot::current
		);

		assertArrayEquals(new Label[0], inside.labels());
	}

	@Test
	@DisplayName("restores the previous context once the block finishes")
	void shouldNotLeakClientMetadata() {
		this.tracingContext.executeWithinBlock("gRPC", metadata("10.0.0.7", "/uri"), ContextSnapshot::current);

		assertNull(MDC.get(TracingContext.MDC_CLIENT_IP_ADDRESS));
		assertNull(MDC.get(TracingContext.MDC_CLIENT_URI));
		assertNull(TracingContext.CLIENT_LABELS.get());
	}

	@TestFactory
	@DisplayName("every executeWithinBlock overload publishes the client context")
	Stream<DynamicTest> shouldPublishClientMetadataFromEveryOverload() {
		final Metadata metadata = metadata("192.168.1.9", "/overload");
		final TracingContext.SpanAttribute[] attributes = TracingContext.SpanAttribute.EMPTY_ARRAY;

		record Overload(String name, java.util.function.Function<Metadata, ContextSnapshot> invocation) {}

		final List<Overload> overloads = List.of(
			new Overload("Runnable + attributes", md -> {
				final ContextSnapshot[] seen = new ContextSnapshot[1];
				this.tracingContext.executeWithinBlock(
					"gRPC", md, () -> { seen[0] = ContextSnapshot.current(); }, attributes
				);
				return seen[0];
			}),
			new Overload("Supplier + attributes", md ->
				this.tracingContext.executeWithinBlock("gRPC", md, ContextSnapshot::current, attributes)),
			new Overload("Runnable + attribute supplier", md -> {
				final ContextSnapshot[] seen = new ContextSnapshot[1];
				this.tracingContext.executeWithinBlock(
					"gRPC", md, () -> { seen[0] = ContextSnapshot.current(); }, () -> attributes
				);
				return seen[0];
			}),
			new Overload("Supplier + attribute supplier", md ->
				this.tracingContext.executeWithinBlock("gRPC", md, ContextSnapshot::current, () -> attributes)),
			new Overload("Runnable", md -> {
				final ContextSnapshot[] seen = new ContextSnapshot[1];
				this.tracingContext.executeWithinBlock("gRPC", md, () -> { seen[0] = ContextSnapshot.current(); });
				return seen[0];
			}),
			new Overload("Supplier", md ->
				this.tracingContext.executeWithinBlock("gRPC", md, ContextSnapshot::current))
		);

		return overloads.stream().map(
			overload -> DynamicTest.dynamicTest(
				overload.name(),
				() -> {
					final ContextSnapshot inside = overload.invocation().apply(metadata);
					assertEquals("192.168.1.9", inside.clientIp(), overload.name() + " did not publish the client IP");
					assertEquals("/overload", inside.clientUri(), overload.name() + " did not publish the client URI");
				}
			)
		);
	}
}
