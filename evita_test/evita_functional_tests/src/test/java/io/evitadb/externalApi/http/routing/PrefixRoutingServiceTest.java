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

package io.evitadb.externalApi.http.routing;

import com.linecorp.armeria.common.*;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.http.RoutableWebSocket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import java.util.concurrent.*;

import static io.evitadb.externalApi.http.routing.RoutingTestSupport.*;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Prefix precedence, normalization, immutable publication and handler isolation regressions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(EXTERNAL_API)
@Tag(MANAGEMENT)
@DisplayName("Prefix routing")
class PrefixRoutingServiceTest {
	/** Exact paths shadow prefixes even when the prefix is longer than another candidate. */
	@Test
	@DisplayName("exact wins over prefix")
	void shouldPreferExactPath() throws Exception {
		final ProbeService prefix = new ProbeService("prefix");
		final ProbeService exact = new ProbeService("exact");
		final PrefixRoutingService router = new PrefixRoutingService().addPrefixPath("/foo", prefix)
			.addExactPath("foo/bar///", exact);
		assertSame(exact, call(router, HttpMethod.GET, "/foo/bar").handler());
		assertEquals("", exact.request.headers().path());
	}

	/** Exact registrations match only the whole path; a longer request falls through to the prefixes. */
	@Test
	@DisplayName("exact path never acts as a prefix")
	void shouldNotTreatExactPathAsPrefix() throws Exception {
		final ProbeService exact = new ProbeService("exact");
		final ProbeService prefix = new ProbeService("prefix");
		final PrefixRoutingService router = new PrefixRoutingService().addExactPath("/foo/bar", exact);

		assertEquals(HttpStatus.NOT_FOUND, call(router, HttpMethod.GET, "/foo/bar/baz").response().status());

		router.addPrefixPath("/foo", prefix);

		assertSame(prefix, call(router, HttpMethod.GET, "/foo/bar/baz").handler());
		assertEquals("/bar/baz", prefix.request.headers().path());
		assertSame(exact, call(router, HttpMethod.GET, "/foo/bar").handler());
		assertEquals("", exact.request.headers().path());
	}

	/** Every mutation carries both kinds of binding over and changes only the kind it names. */
	@Test
	@DisplayName("keeps exact and prefix bindings across every mutation")
	void shouldKeepExactAndPrefixBindingsAcrossEveryMutation() throws Exception {
		final ProbeService exact = new ProbeService("exact");
		final ProbeService prefix = new ProbeService("prefix");
		final ProbeService replacement = new ProbeService("replacement");
		final PrefixRoutingService router = new PrefixRoutingService().addExactPath("/e", exact)
			.addPrefixPath("/p", prefix);

		assertSame(exact, call(router, HttpMethod.GET, "/e").handler());
		assertSame(prefix, call(router, HttpMethod.GET, "/p/x").handler());

		router.addExactPath("/p", exact);
		assertSame(exact, call(router, HttpMethod.GET, "/p").handler());
		assertSame(prefix, call(router, HttpMethod.GET, "/p/x").handler());

		router.removePrefixPath("/p");
		assertSame(exact, call(router, HttpMethod.GET, "/p").handler(), "Removing a prefix must keep the exact path");
		assertEquals(HttpStatus.NOT_FOUND, call(router, HttpMethod.GET, "/p/x").response().status());

		router.addExactPath("/e", replacement);
		assertSame(replacement, call(router, HttpMethod.GET, "/e").handler());
		assertSame(exact, call(router, HttpMethod.GET, "/p").handler());
	}

	/** An exact root consumes the whole root path, while the root default consumes nothing. */
	@Test
	@DisplayName("exact root consumes the root, root default consumes nothing")
	void shouldConsumeWholeRootForExactRootAndNothingForRootDefault() throws Exception {
		final ProbeService exact = new ProbeService("exact root");
		final ProbeService fallback = new ProbeService("default");
		final PrefixRoutingService router = new PrefixRoutingService().addExactPath("/", exact)
			.addPrefixPath("/", fallback);

		assertSame(exact, call(router, HttpMethod.GET, "/").handler());
		assertEquals("", exact.request.headers().path());
		assertSame(fallback, call(router, HttpMethod.GET, "/x").handler());
		assertEquals("/x", fallback.request.headers().path());
	}

	/** The root default answers only when no registered prefix ends on a segment boundary of the path. */
	@Test
	@DisplayName("default is used only when no prefix matches")
	void shouldUseDefaultOnlyWhenNoPrefixMatches() throws Exception {
		final ProbeService fallback = new ProbeService("default");
		final ProbeService foo = new ProbeService("foo");
		final ProbeService fooBar = new ProbeService("foo bar");
		final PrefixRoutingService router = new PrefixRoutingService().addPrefixPath("/", fallback)
			.addPrefixPath("/foo", foo).addPrefixPath("/foo/bar", fooBar);

		assertSame(fooBar, call(router, HttpMethod.GET, "/foo/bar/x").handler());
		assertSame(foo, call(router, HttpMethod.GET, "/foo/x").handler());
		assertEquals("/x", foo.request.headers().path());
		for (final String path : new String[]{"/bar/x", "/foobar"}) {
			assertSame(fallback, call(router, HttpMethod.GET, path).handler(), path);
			assertEquals(path, fallback.request.headers().path(), path);
		}

		router.removePrefixPath("/");

		assertEquals(HttpStatus.NOT_FOUND, call(router, HttpMethod.GET, "/bar/x").response().status());
		assertSame(foo, call(router, HttpMethod.GET, "/foo/x").handler());
	}

	/** Prefixes consume only complete segments, preferring the longest one. */
	@Test
	@DisplayName("longest segment prefix wins")
	void shouldPreferLongestSegmentPrefix() throws Exception {
		final ProbeService shortPath = new ProbeService("short");
		final ProbeService longPath = new ProbeService("long");
		final PrefixRoutingService router = new PrefixRoutingService().addPrefixPath("/foo", shortPath)
			.addPrefixPath("/foo/bar", longPath);
		assertSame(longPath, call(router, HttpMethod.GET, "/foo/bar/baz").handler());
		assertEquals("/baz", longPath.request.headers().path());
		assertSame(shortPath, call(router, HttpMethod.GET, "/foo/barbaz").handler());
		assertEquals(HttpStatus.NOT_FOUND, call(router, HttpMethod.GET, "/foobar").response().status());
	}

	/** The root default consumes nothing, including for an empty rewritten target. */
	@Test
	@DisplayName("root default and empty request")
	void shouldPreserveRootDefaultRemainder() throws Exception {
		final ProbeService handler = new ProbeService("default");
		final PrefixRoutingService router = new PrefixRoutingService().addPrefixPath("/", handler);
		assertSame(handler, call(router, HttpMethod.GET, "/").handler());
		assertEquals("/", handler.request.headers().path());
		final HttpRequest root = request(HttpMethod.GET, "/");
		call(
			router, ServiceRequestContext.builder(root).build(),
			root.withHeaders(root.headers().toBuilder().path("").build())
		);
		assertEquals("/", handler.request.headers().path());
		call(router, HttpMethod.GET, "/other");
		assertEquals("/other", handler.request.headers().path());
	}

	/** Normalization strips trailing slashes without collapsing interior slashes or case. */
	@Test
	@DisplayName("normalizes registration and request without collapsing interior slashes")
	void shouldNormalizeOnlyOuterSlashes() throws Exception {
		final ProbeService handler = new ProbeService("normalized");
		final PrefixRoutingService router = new PrefixRoutingService().addPrefixPath("foo///", handler);
		assertSame(handler, call(router, HttpMethod.GET, "/foo///").handler());
		assertEquals("", handler.request.headers().path());
		call(router, HttpMethod.GET, "/foo//bar///");
		assertEquals("//bar", handler.request.headers().path());
		assertEquals(HttpStatus.NOT_FOUND, call(router, HttpMethod.GET, "/Foo").response().status());
	}

	/** Rewriting drops the request query while retaining the original context query. */
	@Test
	@DisplayName("rewrites remainder and preserves context query")
	void shouldRewritePathAndPreserveContextQuery() throws Exception {
		final ProbeService handler = new ProbeService("query");
		final PrefixRoutingService router = new PrefixRoutingService().addPrefixPath("/foo", handler);
		call(router, HttpMethod.GET, "/foo/bar?q=one&q=two");
		assertEquals("/bar", handler.request.headers().path());
		assertEquals("q=one&q=two", handler.context.query());
	}

	/** Only `:path` changes; the context object, method, authority and other headers reach the handler as sent. */
	@Test
	@DisplayName("forwards the same context and request metadata")
	void shouldForwardSameContextAndRequestMetadata() throws Exception {
		final ProbeService handler = new ProbeService("metadata");
		final PrefixRoutingService router = new PrefixRoutingService().addPrefixPath("/foo", handler);
		final HttpRequest request = HttpRequest.of(RequestHeaders.builder(HttpMethod.POST, "/foo/bar")
			.scheme("http").authority("localhost").add("x-custom", "kept").build());
		final ServiceRequestContext context = ServiceRequestContext.builder(request).build();

		call(router, context, request);

		assertSame(context, handler.context);
		assertEquals(HttpMethod.POST, handler.request.method());
		assertEquals("localhost", handler.request.authority());
		assertEquals("kept", handler.request.headers().get("x-custom"));
		assertEquals("/bar", handler.request.headers().path());
	}

	/** Adds overwrite normalized aliases; unknown removals do not disturb existing routes. */
	@Test
	@DisplayName("overwrites normalized aliases and removes unknown prefixes safely")
	void shouldOverwriteAndRemovePrefixes() throws Exception {
		final ProbeService first = new ProbeService("first");
		final ProbeService second = new ProbeService("second");
		final PrefixRoutingService router = new PrefixRoutingService().addPrefixPath("/foo", first);
		router.addPrefixPath("foo/", second).removePrefixPath("/unknown");
		assertSame(second, call(router, HttpMethod.GET, "/foo").handler());
		router.removePrefixPath("foo///");
		assertEquals(HttpStatus.NOT_FOUND, call(router, HttpMethod.GET, "/foo").response().status());
		router.replacePrefixPath("/foo", first);
		assertSame(first, call(router, HttpMethod.GET, "/foo").handler());
		router.addPrefixPath("/", first).removePrefixPath("/");
		assertEquals(HttpStatus.NOT_FOUND, call(router, HttpMethod.GET, "/other").response().status());
	}

	/** An unmatched path gets a plain-text 404 with the standard body. */
	@Test
	@DisplayName("returns the 404 service")
	void shouldReturnNotFoundService() throws Exception {
		final RoutingResult result = call(new PrefixRoutingService(), HttpMethod.GET, "/missing");
		assertEquals(HttpStatus.NOT_FOUND, result.response().status());
		assertEquals("404 Not Found: The requested resource could not be found.", result.response().contentUtf8());
		assertEquals(MediaType.PLAIN_TEXT, result.response().contentType());
	}

	/** Invalid registrations fail before publication and leave the published bindings untouched. */
	@Test
	@DisplayName("rejects empty paths and null handlers")
	void shouldRejectInvalidRegistration() throws Exception {
		final ProbeService existing = new ProbeService("existing");
		final PrefixRoutingService router = new PrefixRoutingService().addPrefixPath("/live", existing);
		for (final Executable registration : new Executable[]{
			() -> router.addPrefixPath("", new ProbeService("invalid")),
			() -> router.addExactPath("", new ProbeService("invalid")),
			() -> router.removePrefixPath("")
		}) {
			final GenericEvitaInternalError error = assertThrows(GenericEvitaInternalError.class, registration);
			assertTrue(error.getMessage().contains("Path must be specified."), error.getMessage());
		}
		for (final Executable registration : new Executable[]{
			() -> router.addPrefixPath("/x", null),
			() -> router.replacePrefixPath("/x", null),
			() -> router.addExactPath("/x", null)
		}) {
			final NullPointerException error = assertThrows(NullPointerException.class, registration);
			assertEquals("Handler must be specified.", error.getMessage());
		}

		assertSame(existing, call(router, HttpMethod.GET, "/live").handler());
		assertEquals(HttpStatus.NOT_FOUND, call(router, HttpMethod.GET, "/x").response().status());
	}

	/** WebSocket dispatch on the prefix router alone strips the consumed prefix like HTTP dispatch does. */
	@Test
	@DisplayName("dispatches WebSockets with the remainder path")
	void shouldDispatchWebSocketRemainder() throws Exception {
		final SocketProbeService exact = new SocketProbeService("exact");
		final SocketProbeService prefix = new SocketProbeService("prefix");
		final SocketProbeService fallback = new SocketProbeService("default");
		final PrefixRoutingService router = new PrefixRoutingService().addExactPath("/e", exact)
			.addPrefixPath("/p", prefix).addPrefixPath("/", fallback);
		final HttpRequest request = request(HttpMethod.GET, "/");
		final ServiceRequestContext context = ServiceRequestContext.builder(request)
			.sessionProtocol(SessionProtocol.H1C).build();

		assertSame(exact.output, router.handle(context, socket("/e")));
		assertEquals("", exact.incoming.path());
		assertSame(prefix.output, router.handle(context, socket("/p/x")));
		assertEquals("/x", prefix.incoming.path());
		assertSame(fallback.output, router.handle(context, socket("/other/x")));
		assertEquals("/other/x", fallback.incoming.path());

		router.removePrefixPath("/");

		assertPolicyViolation(router.handle(context, socket("/other/x")));
	}

	/** Hash-collision chains wrap and preserve Unicode keys. */
	@Test
	@DisplayName("handles hash collisions and Unicode prefixes")
	void shouldResolveCollisions() throws Exception {
		final PrefixRoutingService router = new PrefixRoutingService();
		for (final String path : new String[]{"/Aa", "/BB", "/AaAa", "/BBBB", "/žluťoučký"}) {
			final ProbeService handler = new ProbeService(path);
			router.addPrefixPath(path, handler);
			assertSame(handler, call(router, HttpMethod.GET, path + "/tail").handler());
		}
		assertEquals("/Aa", call(router, HttpMethod.GET, "/Aa/tail").response().contentUtf8());
	}

	/** B's mutations retain the original A service object. */
	@Test
	@DisplayName("keeps unrelated handler identity across replace, remove and add")
	void shouldIsolateUnchangedHandler() throws Exception {
		final ProbeService stable = new ProbeService("A");
		final PrefixRoutingService router = new PrefixRoutingService().addPrefixPath("/A", stable)
			.addPrefixPath("/B", new ProbeService("old B"));
		assertSame(stable, call(router, HttpMethod.GET, "/A").handler());
		router.replacePrefixPath("/B", new ProbeService("new B"));
		assertSame(
			stable, call(router, HttpMethod.GET, "/A").handler(), "Replacing B must retain A's handler identity"
		);
		router.removePrefixPath("/B");
		assertSame(stable, call(router, HttpMethod.GET, "/A").handler());
		router.addPrefixPath("/B", new ProbeService("added B"));
		assertSame(stable, call(router, HttpMethod.GET, "/A").handler());
	}

	/** Pauses request-target extraction after the table read, proving the retained table still resolves old B. */
	@Test
	@DisplayName("request pinned before replacement still selects old handler")
	void shouldRetainOldTableDuringReplacement() throws Exception {
		final ProbeService oldHandler = new ProbeService("old");
		final ProbeService newHandler = new ProbeService("new");
		final PrefixRoutingService router = new PrefixRoutingService().addPrefixPath("/B", oldHandler);
		final CountDownLatch readTable = new CountDownLatch(1);
		final CountDownLatch resume = new CountDownLatch(1);
		final HttpRequest request = request(HttpMethod.GET, "/B");
		final ServiceRequestContext context = ServiceRequestContext.builder(request).build();
		final HttpRequest paused = new FilteredHttpRequest(request) {
			/** Leaves the actual stream untouched. */
			@Override
			protected HttpObject filter(@Nonnull HttpObject object) {
				return object;
			}

			/** Parks after the production table read without a test hook in production. */
			@Override
			public String path() {
				readTable.countDown();
				await(resume);
				return request.path();
			}
		};
		try (ExecutorService pool = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory())) {
			try {
				final Future<RoutingResult> pending = pool.submit(() -> call(router, context, paused));
				assertTrue(readTable.await(30, TimeUnit.SECONDS));
				router.replacePrefixPath("/B", newHandler);
				assertSame(newHandler, call(router, HttpMethod.GET, "/B").handler());
				resume.countDown();
				assertSame(oldHandler, pending.get(30, TimeUnit.SECONDS).handler(), "Pinned table must resolve old B");
			} finally {
				resume.countDown();
			}
		}
	}

	/** Wraps an empty real WebSocket stream with the path a WebSocket-enabling service would supply. */
	@Nonnull
	private static RoutableWebSocket socket(@Nonnull String path) {
		return new RoutableWebSocket(WebSocket.of(StreamMessage.of()), path);
	}

	/** Awaits a positive scheduling event with a bounded wait and preserved interrupt state. */
	private static void await(@Nonnull CountDownLatch latch) {
		try {
			assertTrue(latch.await(30, TimeUnit.SECONDS));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}
	/** Encoded prefix segments consume raw boundaries and retain encoded remainder text for the next level. */
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
		"/%50RODUCT/x|/x|200", "/PRODUCT/a%2Fb|/a%2Fb|200", "/PRODUCT/a%3Fb?q=v|/a%3Fb|200",
		"/PRODUCT/a%20b|/a%20b|200", "/PRODUCT/%C5%bE|/%C5%bE|200",
		"/PRODUCT/%252F|/%252F|200", "/PRODUCT/a+b|/a+b|200", "/PRODUCT/x///|/x|200",
		"/PRODUCT|EMPTY|200", "/%50RODUCT///|EMPTY|200", "/PRO%GGDUCT|unused|404",
		"/PRO%C3%28DUCT|unused|404", "/PRODUCTx/x|unused|404",
		"?|unused|404", "relative|unused|404", "/PRODUCT/%|unused|404",
		"/PRODUCT/x?only=%|/x|200", "/PRODUCT/%C3|unused|404", "/PRODUCT/%C3x|unused|404",
		"/PRODUCT/%C3/%A9|unused|404", "/PRODUCT/raw#hash|/raw#hash|200",
		"/safe/%GG|unused|404", "/safe/%C0%AF|unused|404"
	})
	@DisplayName("keeps encoded prefix boundaries and remainders")
	void shouldKeepEncodedRemainder(@Nonnull String path, @Nonnull String remainder, int status) throws Exception {
		final SocketProbeService handler = new SocketProbeService("encoded");
		final PrefixRoutingService router = new PrefixRoutingService().addPrefixPath("/PRODUCT", handler);
		final HttpRequest valid = request(HttpMethod.GET, "/");
		final HttpRequest input = valid.withHeaders(valid.headers().toBuilder().path(path).build());
		final RoutingResult result = call(router, ServiceRequestContext.builder(valid).build(), input);
		assertEquals(status, result.response().status().code());
		if (status == 200) {
			assertEquals(remainder.equals("EMPTY") ? "" : remainder, result.request().path());
		}
		for (final SessionProtocol protocol : new SessionProtocol[]{SessionProtocol.H1C, SessionProtocol.H2C}) {
			final ServiceRequestContext context = ServiceRequestContext.builder(valid).sessionProtocol(protocol).build();
			if (status == 200) {
				assertSame(handler.output, router.handle(context, socket(path)));
				assertEquals(remainder.equals("EMPTY") ? "" : remainder, handler.incoming.path());
			} else {
				assertPolicyViolation(router.handle(context, socket(path)));
				assertNull(handler.incoming);
				assertNull(handler.request);
			}
		}
		if (path.startsWith("/safe/")) {
			for (final String prefix : new String[]{"/safe", "/"}) {
				final SocketProbeService guarded = new SocketProbeService("prefix/default must not run");
				final PrefixRoutingService guardedRouter = new PrefixRoutingService().addPrefixPath(prefix, guarded);
				assertEquals(HttpStatus.NOT_FOUND,
					call(guardedRouter, ServiceRequestContext.builder(valid).build(), input).response().status());
				assertNull(guarded.request);
				for (final SessionProtocol protocol : new SessionProtocol[]{SessionProtocol.H1C, SessionProtocol.H2C}) {
					final ServiceRequestContext context = ServiceRequestContext.builder(valid)
						.sessionProtocol(protocol).build();
					assertPolicyViolation(guardedRouter.handle(context, socket(path)));
					assertNull(guarded.incoming);
				}
			}
		}
	}

	/** Two and three levels preserve escapes without URI parsing or repeated decoding of spaces and braces. */
	@Test
	@DisplayName("nested routers decode each segment only once")
	void shouldKeepEncodedTargetsThroughNestedRouters() throws Exception {
		final ProbeService handler = new ProbeService("nested");
		final EndpointRoutingService endpoint = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/{id}", handler).add(HttpMethod.GET, "/", handler).build();
		final PrefixRoutingService catalogs = new PrefixRoutingService().addPrefixPath("/cat alog", endpoint);
		final PrefixRoutingService root = new PrefixRoutingService().addPrefixPath("/a{pi}", catalogs);
		for (final String[] row : new String[][]{
			{"%252F", "%2F"}, {"%253F", "%3F"}, {"%2525", "%25"}, {"%2523", "%23"},
			{"a%20b", "a b"}, {"%7Bvalue%7D", "{value}"}, {"a%3Fb", "a?b"}, {"a%2Fb", "a/b"}
		}) {
			assertEquals(row[1], parameters(call(catalogs, HttpMethod.GET, "/cat%20alog/" + row[0])).get("id"));
			final RoutingResult result = call(root, HttpMethod.GET,
				"/a%7Bpi%7D/cat%20alog/" + row[0] + "?real=query");
			assertEquals(row[1], parameters(result).get("id"));
			assertEquals("/" + row[0], result.request().path());
			assertEquals("real=query", result.contextQuery());
		}
		assertSame(handler, call(root, HttpMethod.GET, "/a%7Bpi%7D/cat%20alog").handler());
		assertEquals(HttpStatus.NOT_FOUND, call(root, HttpMethod.GET, "/a%7Bpi%7D/missing%20catalog")
			.response().status());
		final PrefixRoutingService noDoubleDecode = new PrefixRoutingService().addPrefixPath("/api",
			new PrefixRoutingService().addPrefixPath("/catalog", handler));
		assertEquals(HttpStatus.NOT_FOUND, call(noDoubleDecode, HttpMethod.GET, "/api/catalog%252Fx")
			.response().status());
	}

	/** Replacement and removal rebuild the escaped index in the same publication as the ordinary table. */
	@Test
	@DisplayName("escaped prefix spelling follows replacement and removal")
	void shouldReplaceAndRemoveEscapedPrefix() throws Exception {
		final SocketProbeService oldHandler = new SocketProbeService("old B");
		final SocketProbeService newHandler = new SocketProbeService("new B");
		final PrefixRoutingService router = new PrefixRoutingService().addPrefixPath("/B", oldHandler);
		assertSame(oldHandler, call(router, HttpMethod.GET, "/%42").handler());
		router.replacePrefixPath("/B", newHandler);
		assertSame(newHandler, call(router, HttpMethod.GET, "/%42").handler());
		final HttpRequest valid = request(HttpMethod.GET, "/");
		for (final SessionProtocol protocol : new SessionProtocol[]{SessionProtocol.H1C, SessionProtocol.H2C}) {
			final ServiceRequestContext context = ServiceRequestContext.builder(valid).sessionProtocol(protocol).build();
			assertSame(newHandler.output, router.handle(context, socket("/%42")));
		}
		router.removePrefixPath("/B");
		assertEquals(HttpStatus.NOT_FOUND, call(router, HttpMethod.GET, "/%42").response().status());
		for (final SessionProtocol protocol : new SessionProtocol[]{SessionProtocol.H1C, SessionProtocol.H2C}) {
			final ServiceRequestContext context = ServiceRequestContext.builder(valid).sessionProtocol(protocol).build();
			assertPolicyViolation(router.handle(context, socket("/%42")));
		}
	}

	/** Escaped exact matches shadow prefixes; decoded slashes cannot invent registered segment boundaries. */
	@Test
	@DisplayName("encoded exact and prefix matching retain precedence")
	void shouldKeepEncodedPrecedence() throws Exception {
		final ProbeService exact = new ProbeService("exact");
		final ProbeService longest = new ProbeService("longest");
		final ProbeService fallback = new ProbeService("default");
		final PrefixRoutingService router = new PrefixRoutingService().addPrefixPath("/", fallback)
			.addPrefixPath("/a b", fallback).addPrefixPath("/a b/c?d", longest)
			.addExactPath("/a b/c?d", exact);
		assertSame(exact, call(router, HttpMethod.GET, "/a%20b/c%3Fd").handler());
		assertSame(longest, call(router, HttpMethod.GET, "/a%20b/c%3Fd/tail").handler());
		assertEquals("/tail", longest.request.path());
		assertSame(fallback, call(router, HttpMethod.GET, "/a%20b%2Fc%3Fd").handler());
	}

}
