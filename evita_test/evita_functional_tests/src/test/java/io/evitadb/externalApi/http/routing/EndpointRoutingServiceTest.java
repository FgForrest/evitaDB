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
import com.linecorp.armeria.common.websocket.*;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.http.RoutableWebSocket;
import io.evitadb.externalApi.http.CorsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;

import static io.evitadb.externalApi.http.routing.RoutingTestSupport.*;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Permanent endpoint routing regressions for generated templates, headers, methods and both WebSocket transports.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(EXTERNAL_API)
@Tag(MANAGEMENT)
@DisplayName("Endpoint routing")
class EndpointRoutingServiceTest {
	/** Configuration filenames must register without template parsing. */
	@Test
	@DisplayName("configuration filename is accepted literally")
	void shouldRegisterLiteralCertificateName() {
		assertDoesNotThrow(() -> new EndpointRoutingService.Builder()
			.addLiteral(HttpMethod.GET, "//server}prod.pem", new ProbeService("certificate")));
	}

	/** Reserved template characters and decoded request text remain literal, including a leading empty segment. */
	@Test
	@DisplayName("literal filenames keep braces, stars, question marks and percent signs")
	void shouldMatchLiteralFileNamesWithoutCaptures() throws Exception {
		final String[][] paths = {
			{"//server{prod.pem", "//server%7Bprod.pem"},
			{"//server}prod.pem", "//server%7Dprod.pem"},
			{"//{x}", "//%7Bx%7D"},
			{"//*", "//%2A"},
			{"//server*.pem", "//server*.pem"},
			{"//server?.pem", "//server%3F.pem"},
			{"//server%.pem", "//server%25.pem"},
			{"//server%7B.pem", "//server%257B.pem"},
			{"//folder//{x}/*/?", "//folder//%7Bx%7D/%2A/%3F"}
		};
		for (final String[] path : paths) {
			final ProbeService handler = new ProbeService(path[0]);
			final EndpointRoutingService router = new EndpointRoutingService.Builder()
				.addLiteral(HttpMethod.GET, path[0], handler).build();
			final RoutingResult result = call(router, HttpMethod.GET, path[1]);
			assertSame(handler, result.handler(), path[0]);
			assertTrue(result.request().headers().names().stream()
				.noneMatch(name -> name.toString().startsWith("internal_")), path[0]);
			assertEquals(HttpStatus.NOT_FOUND, call(router, HttpMethod.GET, "//different").response().status());
			assertEquals(HttpStatus.NOT_FOUND,
				call(router, HttpMethod.GET, path[1].substring(1)).response().status());
		}
	}

	/** Literal registration accepts every segment, including empty ones, without relaxing template validation. */
	@Test
	@DisplayName("literal paths supply a slash and accept empty segments")
	void shouldAcceptLiteralPathsWithoutGrammarValidation() throws Exception {
		final ProbeService handler = new ProbeService("literal");
		final EndpointRoutingService router = new EndpointRoutingService.Builder()
			.addLiteral(HttpMethod.GET, "plain", handler)
			.addLiteral(HttpMethod.GET, "/folder//{x}", handler)
			.addLiteral(HttpMethod.GET, "", handler).build();
		assertSame(handler, call(router, HttpMethod.GET, "/plain").handler());
		assertSame(handler, call(router, HttpMethod.GET, "/folder//%7Bx%7D").handler());
		assertEquals(HttpStatus.NOT_FOUND, call(router, HttpMethod.GET, "/folder/%7Bx%7D").response().status());
		assertSame(handler, call(router, HttpMethod.GET, "/").handler());
		assertDoesNotThrow(() -> new EndpointRoutingService.Builder().addLiteral(HttpMethod.GET, "/tail//", handler));
		assertThrows(NullPointerException.class,
			() -> new EndpointRoutingService.Builder().addLiteral(HttpMethod.GET, "/literal", null));
	}

	/** Literal and template registrations share duplicate handling and literal-first lookup in the same trie. */
	@Test
	@DisplayName("literal duplicates keep the first handler and share template tries")
	void shouldKeepFirstLiteralHandlerAndShareTemplateTrie() throws Exception {
		final ProbeService first = new ProbeService("first");
		final ProbeService ignored = new ProbeService("ignored");
		final ProbeService parameter = new ProbeService("parameter");
		final EndpointRoutingService router = new EndpointRoutingService.Builder()
			.addLiteral(HttpMethod.GET, "//{x}", first)
			.addLiteral(HttpMethod.GET, "//{x}", ignored)
			.add(HttpMethod.GET, "//{name}", parameter)
			.addLiteral(HttpMethod.GET, "/plain", first)
			.add(HttpMethod.GET, "/plain", ignored)
			.add(HttpMethod.GET, "/other", first)
			.addLiteral(HttpMethod.GET, "/other", ignored).build();
		assertSame(first, call(router, HttpMethod.GET, "//%7Bx%7D").handler());
		assertSame(parameter, call(router, HttpMethod.GET, "//value").handler());
		assertSame(first, call(router, HttpMethod.GET, "/plain").handler());
		assertSame(first, call(router, HttpMethod.GET, "/other").handler());
	}

	/** The sole-route wildcard restriction applies regardless of registration mode or order. */
	@Test
	@DisplayName("literal routes conflict with template wildcards in both orders")
	void shouldRejectLiteralAndWildcardNodeSharing() {
		final ProbeService handler = new ProbeService("wildcard");
		for (final String path : new String[]{"/files/*", "/files/{x}", "/files/name", "/files"}) {
			assertThrows(GenericEvitaInternalError.class, () -> new EndpointRoutingService.Builder()
				.add(HttpMethod.GET, "/files/*", handler).addLiteral(HttpMethod.GET, path, handler));
			assertThrows(GenericEvitaInternalError.class, () -> new EndpointRoutingService.Builder()
				.addLiteral(HttpMethod.GET, path, handler).add(HttpMethod.GET, "/files/*", handler));
		}
	}

	/** Automatic CONNECT dispatch and method negotiation use literal matches without adding capture headers. */
	@Test
	@DisplayName("literal WebSocket routes register CONNECT and report precise Allow")
	void shouldRegisterLiteralConnectAndNegotiateMethods() throws Exception {
		final SocketProbeService handler = new SocketProbeService("literal socket");
		final EndpointRoutingService router = new EndpointRoutingService.Builder()
			.addLiteral(HttpMethod.GET, "//{x}*?", handler)
			.addLiteral(HttpMethod.OPTIONS, "//{x}*?", new ProbeService("options")).build();
		final String path = "//%7Bx%7D%2A%3F";
		assertSame(handler, call(router, HttpMethod.GET, path).handler());
		assertSame(handler, call(router, HttpMethod.CONNECT, path).handler());
		final RoutingResult mismatch = call(router, HttpMethod.POST, path);
		assertEquals(HttpStatus.METHOD_NOT_ALLOWED, mismatch.response().status());
		assertEquals("OPTIONS, GET", mismatch.response().headers().get(HttpHeaderNames.ALLOW));
	}

	/** The requested method owns the entire path search, including localized fallback branches. */
	@Test
	@DisplayName("POST en/get/list reaches the localized list route")
	void shouldSelectMethodBeforePath() throws Exception {
		final ProbeService get = new ProbeService("GET primary key");
		final ProbeService post = new ProbeService("POST localized list");
		final EndpointRoutingService router = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/en/get/{primaryKey}", get)
			.add(HttpMethod.POST, "/{locale}/get/list", post).build();
		final RoutingResult result = call(router, HttpMethod.POST, "/en/get/list");
		assertSame(post, result.handler(), "POST must search its own trie and reach localized list");
		assertEquals("en", parameters(result).get("locale"));
	}

	/** Failed literal candidates backtrack without retaining their partial captures. */
	@Test
	@DisplayName("literal first with parameter backtracking")
	void shouldBacktrackFromLiteralToParameter() throws Exception {
		final ProbeService literal = new ProbeService("literal");
		final ProbeService parameter = new ProbeService("parameter");
		final EndpointRoutingService router = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/a/literal/{wrong}/end", literal)
			.add(HttpMethod.GET, "/a/{right}/value/other", parameter)
			.add(HttpMethod.GET, "/a/{right}/value/end", parameter).build();
		assertSame(literal, call(router, HttpMethod.GET, "/a/literal/value/end").handler());
		final RoutingResult result = call(router, HttpMethod.GET, "/a/literal/value/other");
		assertSame(parameter, result.handler());
		assertEquals("literal", parameters(result).get("right"));
		assertNull(parameters(result).get("wrong"));
	}

	/** Capture names are route-specific even when the trie topology shares the parameter position. */
	@Test
	@DisplayName("parameter names belong to route terminals")
	void shouldKeepNamesOnTerminals() throws Exception {
		final ProbeService service = new ProbeService("parameters");
		final EndpointRoutingService router = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/a/{x}/one", service)
			.add(HttpMethod.GET, "/a/{z}/three", service)
			.add(HttpMethod.POST, "/a/{y}/two", service).build();
		assertEquals("v", parameters(call(router, HttpMethod.GET, "/a/v/one")).get("x"));
		assertEquals("v", parameters(call(router, HttpMethod.GET, "/a/v/three")).get("z"));
		assertEquals("v", parameters(call(router, HttpMethod.POST, "/a/v/two")).get("y"));
	}

	/** Root and normalized empty requests share the same route. */
	@Test
	@DisplayName("root and empty rewritten target")
	void shouldMatchRootAndEmptyPath() throws Exception {
		final ProbeService service = new ProbeService("root");
		final EndpointRoutingService router = new EndpointRoutingService.Builder().add(HttpMethod.GET, "", service)
			.build();
		assertSame(service, call(router, HttpMethod.GET, "/").handler());
		final HttpRequest request = request(HttpMethod.GET, "/");
		assertSame(service, call(router, ServiceRequestContext.builder(request).build(),
			request.withHeaders(request.headers().toBuilder().path("").build())).handler());
	}

	/** Repeated interior slashes produce an empty capture; trailing request slashes are removed. */
	@Test
	@DisplayName("interior empty capture and trailing request slashes")
	void shouldPreserveInteriorEmptySegments() throws Exception {
		final ProbeService service = new ProbeService("empty");
		final EndpointRoutingService router = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/a/{x}/b", service).build();
		assertEquals("", parameters(call(router, HttpMethod.GET, "/a//b///")).get("x"));
		assertEquals(HttpStatus.NOT_FOUND, call(router, HttpMethod.GET, "/a/b").response().status());
	}

	/** Trailing template slashes have no production caller, except root itself. */
	@Test
	@DisplayName("rejects non-root trailing-slash templates")
	void shouldRejectTrailingSlashTemplates() {
		final ProbeService service = new ProbeService("trailing");
		for (final String template : new String[]{"/a/{x}/", "/literal/", "/{x}/"}) {
			final RuntimeException error = assertThrows(RuntimeException.class,
				() -> new EndpointRoutingService.Builder().add(HttpMethod.GET, template, service));
			assertTrue(error.getMessage().contains("Trailing slash"));
		}
		assertDoesNotThrow(() -> new EndpointRoutingService.Builder().add(HttpMethod.GET, "/", service));
	}

	/** Repeating a canonical registration silently preserves its original handler. */
	@Test
	@DisplayName("identical duplicate retains first handler")
	void shouldSilentlyIgnoreIdenticalDuplicate() throws Exception {
		final ProbeService first = new ProbeService("first");
		final EndpointRoutingService router = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/a/{x}", first)
			.add(HttpMethod.GET, "/a/{x}", new ProbeService("ignored")).build();
		assertSame(first, call(router, HttpMethod.GET, "/a/v").handler());
	}

	/**
	 * Re-registering a route under another parameter name, with a trailing slash, or repeated without its leading
	 * slash throws; only the identical spelling including the leading slash is retained silently.
	 */
	@Test
	@DisplayName("equivalent duplicate throws")
	void shouldRejectEquivalentTemplate() {
		final ProbeService service = new ProbeService("duplicates");
		final EndpointRoutingService.Builder builder = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/a/{x}", service);
		final GenericEvitaInternalError renamed = assertThrows(
			GenericEvitaInternalError.class, () -> builder.add(HttpMethod.GET, "/a/{y}", service)
		);
		assertTrue(renamed.getMessage().contains("Equivalent route already registered as /a/{x}"));
		final GenericEvitaInternalError trailing = assertThrows(
			GenericEvitaInternalError.class, () -> builder.add(HttpMethod.GET, "/a/{x}/", service)
		);
		assertTrue(trailing.getMessage().contains("Trailing slash"));
		final EndpointRoutingService.Builder alias = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "bare", service);
		final GenericEvitaInternalError unslashed = assertThrows(
			GenericEvitaInternalError.class, () -> alias.add(HttpMethod.GET, "bare", service)
		);
		assertTrue(unslashed.getMessage().contains("Equivalent route already registered as /bare"));
	}

	/** A registration without a handler fails immediately with the reason. */
	@Test
	@DisplayName("rejects a null handler")
	void shouldRejectNullHandler() {
		final NullPointerException error = assertThrows(
			NullPointerException.class,
			() -> new EndpointRoutingService.Builder().add(HttpMethod.GET, "/a", null)
		);
		assertEquals("Handler must be specified.", error.getMessage());
	}

	/** A parameterized leaf preserves the request identity, stores only captures and leaves queries to REST. */
	@Test
	@DisplayName("stores path values in the context and forwards the original request")
	void shouldStorePathValuesAndPreserveRequest() throws Exception {
		final ProbeService service = new ProbeService("parameters");
		final EndpointRoutingService router = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/{id}", service).build();
		final HttpRequest request = HttpRequest.of(RequestHeaders.builder(HttpMethod.GET, "/path?id=q1&id=q2")
			.scheme("http").authority("localhost").add("internal_id", "client").build());
		final ServiceRequestContext context = ServiceRequestContext.builder(request).build();
		final RoutingResult result = call(router, context, request);
		assertSame(request, result.request());
		assertEquals(List.of("client"), result.request().headers().getAll("internal_id"));
		assertEquals(java.util.Map.of("id", "path"), parameters(result));
		assertEquals("id=q1&id=q2", context.query());
		final ProbeService literal = new ProbeService("literal");
		final EndpointRoutingService noCaptures = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/literal", literal).build();
		final RoutingResult plain = call(noCaptures, HttpMethod.GET, "/literal?ignored=value");
		assertNull(literal.context.attr(RouteParameters.KEY));
		assertNull(plain.request().headers().get("internal_ignored"));
	}

	/** Generated templates give each captured position a distinct name. */
	@Test
	@DisplayName("rejects repeated parameter names within one template")
	void shouldRejectRepeatedParameterNames() {
		final RuntimeException error = assertThrows(RuntimeException.class,
				() -> new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/{a}/x/{a}", new ProbeService("repeated")));
		assertTrue(error.getMessage().contains("Repeated parameter name a"));
	}

	/** Template queries are unsupported even though decoded request paths can contain question marks. */
	@Test
	@DisplayName("rejects question marks in templates")
	void shouldRejectQuestionMarksInTemplates() {
		for (final String template : new String[]{"/literal?query", "/{name?}"}) {
			final RuntimeException error = assertThrows(RuntimeException.class,
				() -> new EndpointRoutingService.Builder()
				.add(HttpMethod.GET, template, new ProbeService("question mark")));
			assertTrue(error.getMessage().contains("Question marks"));
		}
	}

	/** Escaped question marks are data; wildcard tails have no production parameter reader. */
	@Test
	@DisplayName("escaped question marks remain capture data")
	void shouldPreserveDecodedQuestionMarks() throws Exception {
		final ProbeService service = new ProbeService("decoded");
		final EndpointRoutingService router = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/a/{id}", service).add(HttpMethod.GET, "/literal", service).build();
		assertEquals("value?ignored", parameters(call(router, HttpMethod.GET, "/a/value%3Fignored")).get("id"));
		assertEquals(HttpStatus.NOT_FOUND, call(router, HttpMethod.GET, "/literal%3Fignored").response().status());
	}

	/** Wildcards route complete remainders and root, without allocating unused parameter carriers. */
	@Test
	@DisplayName("wildcard routes without exposing unused captures")
	void shouldRouteWildcardRemainderWithoutCarrier() throws Exception {
		final ProbeService service = new ProbeService("wildcard");
		final EndpointRoutingService router = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/*", service).build();
		for (final String path : new String[]{"/", "/a/b", "/value%3Fignored"}) {
			assertSame(service, call(router, HttpMethod.GET, path).handler());
			assertNull(service.context.attr(RouteParameters.KEY));
		}
		final EndpointRoutingService nested = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/foo/*", service).build();
		assertEquals(HttpStatus.NOT_FOUND, call(nested, HttpMethod.GET, "/foo/").response().status());
		assertSame(service, call(nested, HttpMethod.GET, "/foo/tail").handler());
	}

	/** A wildcard must be the only route out of its node, independently of insertion order. */
	@Test
	@DisplayName("rejects unsupported and ambiguous wildcard grammar")
	void shouldRejectWildcardGrammar() {
		final ProbeService service = new ProbeService("grammar");
		for (final String[] malformed : new String[][]{
			{"/foo*", "Expected a whole literal"}, {"/{*}", "Invalid parameter segment"},
			{"/foo/*/after", "Wildcard must be the final whole segment"}, {"/foo/{x}tail", "Expected a whole literal"},
			{"/foo/{x", "Expected a whole literal"}
		}) {
			final GenericEvitaInternalError error = assertThrows(GenericEvitaInternalError.class,
				() -> new EndpointRoutingService.Builder().add(HttpMethod.GET, malformed[0], service), malformed[0]);
			assertTrue(error.getMessage().contains(malformed[1]), error.getMessage());
		}
		for (final String[] pair : new String[][]{
			{"/foo/*", "/foo/{id}"}, {"/foo/{a}/*", "/foo/{a}/{b}"},
			{"/foo/*", "/foo/{id}/detail"}, {"/", "/*"}, {"/{a}/b", "/{a}/*"}, {"/{a}/b/c", "/{a}/*"},
			{"/x//{q}", "/x/*"}, {"/a", "/a/*"}, {"/a/b", "/a/*"}
		}) {
			for (int order = 0; order < 2; order++) {
				final EndpointRoutingService.Builder builder = new EndpointRoutingService.Builder()
					.add(HttpMethod.GET, pair[order], service);
				final String conflicting = pair[1 - order];
				final GenericEvitaInternalError error = assertThrows(
					GenericEvitaInternalError.class, () -> builder.add(HttpMethod.GET, conflicting, service),
					pair[order] + " then " + conflicting
				);
				assertTrue(error.getMessage().contains("cannot share a node"), error.getMessage());
			}
		}
		final SocketProbeService socket = new SocketProbeService("lab");
		assertDoesNotThrow(() -> new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/*", CorsService.standaloneFilter(socket))
			.add(HttpMethod.OPTIONS, "/*", service));
	}

	/** Empty segments after a parameter are outside the grammar; before any parameter they stay literal. */
	@Test
	@DisplayName("rejects empty template segments after a parameter")
	void shouldRejectEmptySegmentAfterParameter() throws Exception {
		final ProbeService service = new ProbeService("empty segment");
		for (final String template : new String[]{"/{a}//{b}", "/{a}//b"}) {
			final GenericEvitaInternalError error = assertThrows(GenericEvitaInternalError.class,
				() -> new EndpointRoutingService.Builder().add(HttpMethod.GET, template, service), template);
			assertTrue(error.getMessage().contains("Empty segments"), template);
		}
		for (final String template : new String[]{"//server.crt", "/a//b", "/a//{x}"}) {
			assertDoesNotThrow(
				() -> new EndpointRoutingService.Builder().add(HttpMethod.GET, template, service), template
			);
		}
		final ProbeService certificate = new ProbeService("certificate");
		final EndpointRoutingService router = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/status", service)
			.add(HttpMethod.GET, "//server.crt", certificate).build();
		assertSame(certificate, call(router, HttpMethod.GET, "//server.crt").handler());
		assertEquals(HttpStatus.NOT_FOUND, call(router, HttpMethod.GET, "/server.crt").response().status());
	}

	/** A miss probes other methods, reporting exactly the path-matching set in enum order. */
	@Test
	@DisplayName("405 includes exactly the matching explicit methods")
	void shouldReturnExactAllowHeader() throws Exception {
		final ProbeService service = new ProbeService("allowed");
		final EndpointRoutingService router = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/a/{x}", service).add(HttpMethod.POST, "/a/value", service)
			.add(HttpMethod.DELETE, "/other", service).build();
		final RoutingResult result = call(router, HttpMethod.PUT, "/a/value");
		assertEquals(HttpStatus.METHOD_NOT_ALLOWED, result.response().status(), "Known path must produce 405");
		assertEquals("405 Method Not Allowed: The requested method is not supported.", result.response().contentUtf8());
		assertEquals("GET, POST", result.response().headers().get(HttpHeaderNames.ALLOW),
			"Allow must list exactly the methods whose tries match this path");
		assertEquals("GET", call(router, HttpMethod.HEAD, "/a/else").response().headers().get(HttpHeaderNames.ALLOW));
		assertEquals(HttpStatus.METHOD_NOT_ALLOWED, call(router, HttpMethod.OPTIONS, "/a/value").response().status());
	}

	/** Automatic CONNECT is callable but absent from Allow; explicit CONNECT belongs in Allow. */
	@Test
	@DisplayName("Allow excludes automatic CONNECT and includes explicit CONNECT")
	void shouldDistinguishAutomaticConnect() throws Exception {
		final SocketProbeService socket = new SocketProbeService("socket");
		final EndpointRoutingService automatic = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/ws", socket).build();
		assertSame(socket, call(automatic, HttpMethod.CONNECT, "/ws").handler());
		assertEquals("GET", call(automatic, HttpMethod.PUT, "/ws").response().headers().get(HttpHeaderNames.ALLOW));
		final EndpointRoutingService explicit = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/ws", socket).add(HttpMethod.CONNECT, "/ws", socket).build();
		assertEquals("GET, CONNECT", call(explicit, HttpMethod.PUT, "/ws").response().headers()
			.get(HttpHeaderNames.ALLOW));
	}

	/** The 405 probe ignores automatic CONNECT wildcards and lists explicit wildcard methods in enum order. */
	@Test
	@DisplayName("Allow honours wildcard registration provenance")
	void shouldHonourProvenanceOfWildcardsInAllowHeader() throws Exception {
		final SocketProbeService socket = new SocketProbeService("socket");
		final EndpointRoutingService automatic = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/ws/*", socket).build();

		assertSame(socket, call(automatic, HttpMethod.CONNECT, "/ws/x").handler());
		final RoutingResult automaticMiss = call(automatic, HttpMethod.PUT, "/ws/x");
		assertEquals(HttpStatus.METHOD_NOT_ALLOWED, automaticMiss.response().status());
		assertEquals("GET", automaticMiss.response().headers().get(HttpHeaderNames.ALLOW));

		final EndpointRoutingService lab = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/lab/*", socket)
			.add(HttpMethod.OPTIONS, "/lab/*", new ProbeService("options")).build();

		assertEquals(
			"OPTIONS, GET", call(lab, HttpMethod.PUT, "/lab/x").response().headers().get(HttpHeaderNames.ALLOW)
		);
	}

	/** Explicit CONNECT keeps its provenance and the first registered handler whichever registration comes first. */
	@Test
	@DisplayName("explicit CONNECT survives either registration order")
	void shouldKeepExplicitConnectRegardlessOfRegistrationOrder() throws Exception {
		final SocketProbeService socket = new SocketProbeService("socket");
		final SocketProbeService connect = new SocketProbeService("explicit connect");
		final EndpointRoutingService connectFirst = new EndpointRoutingService.Builder()
			.add(HttpMethod.CONNECT, "/ws", connect).add(HttpMethod.GET, "/ws", socket).build();
		final EndpointRoutingService getFirst = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/ws", socket).add(HttpMethod.CONNECT, "/ws", connect).build();

		assertEquals("GET, CONNECT", call(connectFirst, HttpMethod.PUT, "/ws").response().headers()
			.get(HttpHeaderNames.ALLOW));
		assertSame(connect, call(connectFirst, HttpMethod.CONNECT, "/ws").handler());
		assertSame(socket, call(connectFirst, HttpMethod.GET, "/ws").handler());
		assertEquals("GET, CONNECT", call(getFirst, HttpMethod.PUT, "/ws").response().headers()
			.get(HttpHeaderNames.ALLOW));
		assertSame(socket, call(getFirst, HttpMethod.CONNECT, "/ws").handler());
	}

	/** Every capture is read from its own segment, including empty values and values containing a decoded `?`. */
	@Test
	@DisplayName("copies every capture at its segment position")
	void shouldCopyEveryCaptureAtItsSegmentPosition() throws Exception {
		final ProbeService service = new ProbeService("captures");
		final EndpointRoutingService router = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/a/{x}/b/c/{y}", service)
			.add(HttpMethod.GET, "/{x}/{y}/{z}", service).build();
		final EndpointRoutingService wildcard = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/a/{x}/*", service).build();

		assertEquals(java.util.Map.of("x", "1", "y", "2"),
			parameters(call(router, HttpMethod.GET, "/a/1/b/c/2")));
		assertEquals(java.util.Map.of("x", "p", "y", "", "z", "q"),
			parameters(call(router, HttpMethod.GET, "/p//q")));
		assertEquals(java.util.Map.of("x", "p", "y", "q", "z", "r?s"),
			parameters(call(router, HttpMethod.GET, "/p/q/r%3Fs")));
		assertEquals(java.util.Map.of("x", "1"),
			parameters(call(wildcard, HttpMethod.GET, "/a/1/rest/of/path")));
	}

	/** Captures carry the percent-decoded segment text, while a plus sign stays literal. */
	@Test
	@DisplayName("captures decoded segment values")
	void shouldCaptureDecodedSegmentValues() throws Exception {
		final ProbeService service = new ProbeService("decoded");
		final EndpointRoutingService router = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/a/{id}", service).build();

		assertEquals("value", parameters(call(router, HttpMethod.GET, "/a/%76alue")).get("id"));
		assertEquals("ž", parameters(call(router, HttpMethod.GET, "/a/%C5%BE")).get("id"));
		assertEquals("a+b", parameters(call(router, HttpMethod.GET, "/a/a+b")).get("id"));
	}

	/** An empty first segment matches neither the root route nor a parameter route followed by more path. */
	@Test
	@DisplayName("own-method miss after an empty first segment is 404")
	void shouldReturnNotFoundForEmptyFirstSegment() throws Exception {
		final ProbeService service = new ProbeService("root and id");
		final EndpointRoutingService router = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/", service)
			.add(HttpMethod.GET, "/{id}", service).build();

		final RoutingResult result = call(router, HttpMethod.GET, "//value");

		assertEquals(HttpStatus.NOT_FOUND, result.response().status());
		assertNull(result.response().headers().get(HttpHeaderNames.ALLOW));
		assertNull(result.handler());
	}

	/** No match in any method returns the unchanged 404 body. */
	@Test
	@DisplayName("unknown path returns 404")
	void shouldReturnNotFound() throws Exception {
		final RoutingResult result = call(new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/known", new ProbeService("known")).build(), HttpMethod.POST, "/unknown");
		assertEquals(HttpStatus.NOT_FOUND, result.response().status());
		assertNull(result.response().headers().get(HttpHeaderNames.ALLOW));
		assertEquals("404 Not Found: The requested resource could not be found.", result.response().contentUtf8());
	}

	/** Builds detach every node from future builder additions. */
	@Test
	@DisplayName("built router is immutable after builder reuse")
	void shouldFreezeBuilderState() throws Exception {
		final ProbeService service = new ProbeService("immutable");
		final EndpointRoutingService.Builder builder = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/a", service);
		final EndpointRoutingService first = builder.build();
		builder.add(HttpMethod.GET, "/b", service);
		assertEquals(HttpStatus.NOT_FOUND, call(first, HttpMethod.GET, "/b").response().status());
		assertSame(service, call(builder.build(), HttpMethod.GET, "/b").handler());
	}

	/** Both transports pass through two prefix levels and the endpoint router using the registered outer object. */
	@Test
	@DisplayName("WebSocket GET and CONNECT dispatch through nested prefix routers")
	void shouldDispatchWebSocketThroughPrefixes() {
		for (final SessionProtocol protocol : new SessionProtocol[]{SessionProtocol.H1C, SessionProtocol.H2C}) {
			final SocketProbeService socket = new SocketProbeService("socket");
			final EndpointRoutingService endpoints = new EndpointRoutingService.Builder()
				.add(HttpMethod.GET, "/{id}", CorsService.standaloneFilter(socket)).build();
			final PrefixRoutingService catalogs = new PrefixRoutingService().addPrefixPath("/catalog", endpoints);
			final PrefixRoutingService root = new PrefixRoutingService().addPrefixPath("/api", catalogs);
			final HttpRequest request = request(
				protocol.isExplicitHttp1() ? HttpMethod.GET : HttpMethod.CONNECT, "/api/catalog/value?q=one"
			);
			final ServiceRequestContext context = ServiceRequestContext.builder(request)
				.sessionProtocol(protocol).build();
			final WebSocket incoming = WebSocket.of(StreamMessage.of());
			assertSame(socket.output, root.handle(context, new RoutableWebSocket(incoming, "/api/catalog/value")));
			assertSame(incoming, socket.incoming.incomingWebSocket());
			assertEquals("/value", socket.incoming.path());
			assertNull(context.request().headers().get("internal_id"));
			assertNull(context.request().headers().get("internal_q"));
		}
	}


	/** Outer CORS capability drives CONNECT registration even when its delegate cannot handle WebSockets. */
	@Test
	@DisplayName("retains outer wrapper capability without unwrapping")
	void shouldKeepOuterWrapperCapability() throws Exception {
		final ProbeService http = new ProbeService("wrapped HTTP");
		final HttpService wrapper = CorsService.standaloneFilter(http);
		final EndpointRoutingService router = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/ordinary", wrapper).build();
		assertSame(http, call(router, HttpMethod.CONNECT, "/ordinary").handler());
		for (final SessionProtocol protocol : new SessionProtocol[]{SessionProtocol.H1C, SessionProtocol.H2C}) {
			final HttpRequest request = request(HttpMethod.GET, "/ordinary");
			final ServiceRequestContext context = ServiceRequestContext.builder(request)
				.sessionProtocol(protocol).build();
			final RoutableWebSocket in = new RoutableWebSocket(WebSocket.of(StreamMessage.of()), "/ordinary");
			final IllegalStateException failure = assertThrows(
				IllegalStateException.class, () -> router.handle(context, in)
			);
			assertEquals("HttpService `CorsService` is not WebSocket handler.", failure.getMessage());
		}
	}

	/** Selected HTTP-only paths shadow WebSocket prefixes without falling back to a shorter match. */
	@Test
	@DisplayName("WebSocket capability is checked after path selection")
	void shouldNotBacktrackForWebSocketCapability() throws Exception {
		final SocketProbeService socket = new SocketProbeService("shorter");
		final HttpRequest request = request(HttpMethod.GET, "/api/value");
		final ServiceRequestContext context = ServiceRequestContext.builder(request)
			.sessionProtocol(SessionProtocol.H1C)
			.build();
		final RoutableWebSocket in = new RoutableWebSocket(WebSocket.of(StreamMessage.of()), "/api/value");
		final PrefixRoutingService root = new PrefixRoutingService().addPrefixPath("/api", socket)
			.addExactPath("/api/value", new ProbeService("http only"));
		assertPolicyViolation(root.handle(context, in));
		assertNull(socket.incoming);
		final EndpointRoutingService endpoint = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/api/{id}", socket)
			.add(HttpMethod.GET, "/api/value", new ProbeService("http only")).build();
		assertPolicyViolation(endpoint.handle(context, in));
		assertNull(socket.incoming);
	}

	/** A session protocol that is neither explicit HTTP/1 nor HTTP/2 selects no method, so the fallback answers. */
	@Test
	@DisplayName("WebSocket on an unresolved session protocol gets the fallback")
	void shouldReturnFallbackForUnresolvedSessionProtocol() throws Exception {
		final SocketProbeService socket = new SocketProbeService("socket");
		final EndpointRoutingService router = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/ws", socket).build();
		final HttpRequest request = request(HttpMethod.GET, "/ws");
		final ServiceRequestContext context = ServiceRequestContext.builder(request)
			.sessionProtocol(SessionProtocol.HTTP).build();

		assertPolicyViolation(router.handle(context, new RoutableWebSocket(WebSocket.of(StreamMessage.of()), "/ws")));
		assertNull(socket.incoming);
	}

	/** The enabling service's encoded path survives prefix rewrites, including encoded question marks. */
	@Test
	@DisplayName("WebSocket encoded question mark passes through prefix routers")
	void shouldRouteEncodedWebSocketQuestionMark() {
		for (final SessionProtocol protocol : new SessionProtocol[]{SessionProtocol.H1C, SessionProtocol.H2C}) {
			final SocketProbeService socket = new SocketProbeService("decoded socket");
			final EndpointRoutingService endpoints = new EndpointRoutingService.Builder()
				.add(HttpMethod.GET, "/{id}", socket).build();
			final PrefixRoutingService root = new PrefixRoutingService().addPrefixPath("/api",
				new PrefixRoutingService().addPrefixPath("/cat alog", endpoints));
			final HttpRequest request = request(HttpMethod.GET, "/api/cat%20alog/value%3Fignored?q=real");
			final ServiceRequestContext context = ServiceRequestContext.builder(request)
				.sessionProtocol(protocol).build();
			final RoutableWebSocket input = new RoutableWebSocket(
				WebSocket.of(StreamMessage.of()), context.path()
			);
			assertEquals("/api/cat%20alog/value%3Fignored", input.path());
			final io.evitadb.externalApi.http.WebSocketEnablingService enabling =
				new io.evitadb.externalApi.http.WebSocketEnablingService(root, root);
			assertSame(socket.output, enabling.handle(context, input.incomingWebSocket()));
			assertEquals("/value%3Fignored", socket.incoming.path());
		}
	}
	/** A compact encoded-target table checks captures, literal decoding and controlled malformed-input misses. */
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
		"/PRODUCT/a%2Fb|a/b|200", "/PRODUCT/a%3Fb?real=query|a?b|200",
		"/PRODUCT/a%20b|a b|200", "/PRODUCT/%C5%bE|ž|200", "/PRODUCT/%252F|%2F|200",
		"/PRODUCT/a+b|a+b|200", "/%50RODUCT/%23%25|#%|200", "/PRODUCT/value///|value|200",
		"/PRODUCT/%F0%9F%98%80|😀|200", "/PRODUCT/%GG|unused|404", "/PRODUCT/%0|unused|404",
		"/PRODUCT/%C3%28|unused|404", "/PRODUCT/%ED%A0%80|unused|404", "/PRODUCT/%F4%90%80%80|unused|404",
		"/PRODUCT/%80|unused|404", "/PRODUCT/%E0%80%80|unused|404",
		"?|unused|404", "relative|unused|404", "/PRODUCT/%|unused|404",
		"/PRODUCT/value?only=%|value|200", "/PRODUCT/%C3|unused|404", "/PRODUCT/%C3x|unused|404",
		"/PRODUCT/%C3/%A9|unused|404", "/PRODUCT/raw#hash|raw#hash|200",
		"/safe/%GG|unused|404", "/safe/%C0%AF|unused|404"
	})
	@DisplayName("matches encoded path segments once")
	void shouldMatchEncodedSegments(@Nonnull String path, @Nonnull String value, int status) throws Exception {
		final SocketProbeService handler = new SocketProbeService("encoded");
		final EndpointRoutingService router = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/PRODUCT/{id}", handler).build();
		final HttpRequest valid = request(HttpMethod.GET, "/");
		final HttpRequest input = valid.withHeaders(valid.headers().toBuilder().path(path).build());
		final RoutingResult result = call(router, ServiceRequestContext.builder(valid).build(), input);
		assertEquals(status, result.response().status().code());
		if (status == 200) {
			assertEquals(value, parameters(result).get("id"));
			assertSame(input, result.request());
		}
		for (final SessionProtocol protocol : new SessionProtocol[]{SessionProtocol.H1C, SessionProtocol.H2C}) {
			final ServiceRequestContext context = ServiceRequestContext.builder(valid).sessionProtocol(protocol).build();
			final RoutableWebSocket inputSocket = new RoutableWebSocket(WebSocket.of(StreamMessage.of()), path);
			if (status == 200) {
				assertSame(handler.output, router.handle(context, inputSocket));
			} else {
				assertPolicyViolation(router.handle(context, inputSocket));
				assertNull(handler.incoming);
				assertNull(handler.request);
			}
		}
		if (path.startsWith("/safe/")) {
			final SocketProbeService wildcard = new SocketProbeService("wildcard must not run");
			final EndpointRoutingService wildcardRouter = new EndpointRoutingService.Builder()
				.add(HttpMethod.GET, "/*", wildcard).build();
			for (final HttpMethod method : new HttpMethod[]{HttpMethod.GET, HttpMethod.POST}) {
				final HttpRequest malformed = input.withHeaders(input.headers().toBuilder().method(method).build());
				final RoutingResult rejected = call(wildcardRouter, ServiceRequestContext.builder(valid).build(), malformed);
				assertEquals(HttpStatus.NOT_FOUND, rejected.response().status(), "Invalid tails precede the 405 probe");
				assertNull(rejected.response().headers().get(HttpHeaderNames.ALLOW));
				assertNull(wildcard.request);
			}
			for (final SessionProtocol protocol : new SessionProtocol[]{SessionProtocol.H1C, SessionProtocol.H2C}) {
				final ServiceRequestContext context = ServiceRequestContext.builder(valid).sessionProtocol(protocol).build();
				assertPolicyViolation(wildcardRouter.handle(
					context, new RoutableWebSocket(WebSocket.of(StreamMessage.of()), path)
				));
				assertNull(wildcard.incoming);
			}
		}
	}

	/** Literal probes allocate no decoded capture text even when a deeper literal branch forces backtracking. */
	@Test
	@DisplayName("escaped literals and capture backtracking retain decoded text")
	void shouldBacktrackEscapedLiteralWithoutDecodingCapture() throws Exception {
		final ProbeService literal = new ProbeService("literal");
		final ProbeService capture = new ProbeService("capture");
		final EndpointRoutingService.Builder builder = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/{id}/capture", capture);
		for (final String key : new String[]{"😀", "ž😀", "Aa", "BB", "%2F"}) {
			builder.addLiteral(HttpMethod.GET, "/" + key + "/literal", literal);
		}
		final EndpointRoutingService router = builder.build();
		for (final String[] row : new String[][]{
			{"%F0%9F%98%80", "😀"}, {"ž%F0%9F%98%80", "ž😀"},
			{"%41a", "Aa"}, {"%42B", "BB"}, {"%252F", "%2F"}
		}) {
			assertSame(literal, call(router, HttpMethod.GET, "/" + row[0] + "/literal").handler());
			assertEquals(row[1], parameters(call(router, HttpMethod.GET, "/" + row[0] + "/capture")).get("id"));
		}
	}

	/** All filename metacharacters remain within a literal segment reached through encoded request text. */
	@Test
	@DisplayName("matches a literal with every filename metacharacter")
	void shouldMatchEncodedLiteralMetacharacters() throws Exception {
		final ProbeService handler = new ProbeService("literal");
		final EndpointRoutingService router = new EndpointRoutingService.Builder()
			.addLiteral(HttpMethod.GET, "/{ }*?%", handler).build();
		assertSame(handler, call(router, HttpMethod.GET, "/%7b%20%7D%2a%3f%25").handler());
		assertNull(handler.context.attr(RouteParameters.KEY));
	}

}
