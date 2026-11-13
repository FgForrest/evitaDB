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
import com.linecorp.armeria.server.HttpService;
import io.evitadb.externalApi.http.CorsService;
import io.evitadb.externalApi.utils.path.PathHandlingService;
import io.evitadb.externalApi.utils.path.RoutingHandlerService;
import io.evitadb.externalApi.utils.path.URLUtils;
import io.evitadb.externalApi.utils.path.routing.PathTemplateMatcher;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.CertificateUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import java.util.*;

import static io.evitadb.externalApi.http.routing.RoutingTestSupport.*;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Bounded, seeded differential examples against the fork, including generated route-literal collection names.
 * Own-method hits require parity on raw paths and non-delimiter, once-decoded escapes; misses use the fork's
 * per-method matchers. Escaped slash/question mark and nested double escapes are intentionally separate cases.
 * The fork carries query and path values in headers; the replacement carries named path values in context.
 * The unused wildcard capture is deliberately omitted by the replacement.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(EXTERNAL_API)
@Tag(MANAGEMENT)
@DisplayName("Fork routing differential")
class ForkDifferentialRoutingTest {
	private static final long SEED = 0x5EED43L;
	private static final String[] COLLECTIONS = {
		"product", "brand", "en", "get", "list", "query", "schema", "collections", "entity"
	};

	/** Compares every generated valid path, nearby invalid paths and a bounded seeded sample across methods. */
	@Test
	@DisplayName("generated production endpoint inventory agrees with per-method fork oracle")
	void shouldAgreeOnGeneratedEndpointInventory() throws Exception {
		final List<List<RouteCase>> inventories = List.of(catalog(), restSystem(), graphQL(), system(), lab());
		for (int index = 0; index < inventories.size(); index++) {
			final List<RouteCase> routes = inventories.get(index);
			final RoutingHandlerService fork = new RoutingHandlerService();
			final EndpointRoutingService.Builder builder = new EndpointRoutingService.Builder();
			final Map<HttpMethod, PathTemplateMatcher<HttpService>> explicit = new EnumMap<>(HttpMethod.class);
			final Map<HttpMethod, PathTemplateMatcher<HttpService>> dispatch = new EnumMap<>(HttpMethod.class);
			for (final RouteCase route : routes) {
				registerBoth(fork, builder, route);
				registerOracle(explicit, route.method, route.template, route.handler);
				registerOracle(dispatch, route.method, route.template, route.handler);
				// Every generated registration uses the CORS wrapper, which itself supports WebSockets.
				if (route.method == HttpMethod.GET) {
					registerOracle(dispatch, HttpMethod.CONNECT, route.template, route.handler);
				}
			}
			final EndpointRoutingService replacement = builder.build();
			final LinkedHashSet<String> paths = CollectionUtils.createLinkedHashSet(routes.size() * 5);
			paths.addAll(List.of("/", "/missing", "/en/get/list", "/a//b", "/schema/extra"));
			for (final RouteCase route : routes) {
				final String path = example(route.template);
				paths.add(path);
				paths.add(path + "/");
				paths.add(path + "/near-miss");
				paths.add(path + "x");
				paths.add(path + "?primaryKey=query&primaryKey=second");
			}
			final Random random = new Random(SEED + index);
			final List<String> valid = new ArrayList<>(paths);
			for (int i = 0; i < 100; i++) {
				final String path = valid.get(random.nextInt(valid.size()));
				paths.add(path.replace("/", random.nextBoolean() ? "//" : "/") + random.nextInt(5));
			}
			for (final String path : paths) {
				for (final HttpMethod method : new HttpMethod[]{HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
					HttpMethod.DELETE, HttpMethod.PATCH, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.CONNECT}) {
					compare(
						fork, replacement, explicit, dispatch, method, path,
						"inventory=" + index + ", seed=" + (SEED + index)
					);
				}
			}
		}
	}

	/** Compares duplicate acceptance, silent retention and equivalent-name exceptions using actual registrations. */
	@Test
	@DisplayName("registration outcomes agree for canonical and equivalent duplicates")
	void shouldAgreeOnRegistrationOutcomes() throws Exception {
		final RoutingHandlerService fork = new RoutingHandlerService();
		final EndpointRoutingService.Builder builder = new EndpointRoutingService.Builder();
		for (final String template : new String[]{
			"/x/{id}", "/x/{id}", "/x/{other}", "bare", "bare"
		}) {
			final ProbeService handler = new ProbeService(template);
			final RouteCase route = new RouteCase(HttpMethod.GET, template, handler);
			final RoutingResult before = call(fork, HttpMethod.GET, example(template));
			final String outcome = registerBoth(fork, builder, route);
			if (outcome.equals("accepted")) {
				final RoutingResult oldResult = call(fork, HttpMethod.GET, example(template));
				final RoutingResult newResult = call(builder.build(), HttpMethod.GET, example(template));
				assertSame(oldResult.handler(), newResult.handler(), "Registration retention for " + template);
				if (before.handler() != null) {
					assertSame(
						before.handler(), newResult.handler(), "Identical registration must be silently ignored"
					);
				}
			}
		}
	}

	/** Compares two nested prefix levels with query preservation, decoded targets and observability literals. */
	@Test
	@DisplayName("nested prefix paths, encoding and observability agree")
	void shouldAgreeOnNestedPrefixRouting() throws Exception {
		final ProbeService service = new ProbeService("nested");
		final RoutingHandlerService oldEndpoints = new RoutingHandlerService().add(HttpMethod.GET, "/{id}", service)
			.add(HttpMethod.GET, "/", service);
		final EndpointRoutingService endpoints = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/{id}", service).add(HttpMethod.GET, "/", service).build();
		final PathHandlingService oldCatalogs = new PathHandlingService().addPrefixPath("/catalog", oldEndpoints);
		final PrefixRoutingService catalogs = new PrefixRoutingService().addPrefixPath("/catalog", endpoints);
		final PathHandlingService oldRoot = new PathHandlingService().addPrefixPath("/rest", oldCatalogs);
		final PrefixRoutingService root = new PrefixRoutingService().addPrefixPath("/rest", catalogs);
		for (final String path : new String[]{"/metrics", "/recording.jfr", "/startRecording", "/stopRecording",
			"/checkRecording", "/getRecordingEventTypes", "/liveness"}) {
			if (path.equals("/metrics")) {
				oldRoot.addPrefixPath(path, service);
				root.addPrefixPath(path, service);
			} else {
				oldRoot.addExactPath(path, service);
				root.addExactPath(path, service);
			}
		}
		oldRoot.addExactPath("/", service);
		root.addExactPath("/", service);
		final ProbeService endpointInput = new ProbeService("endpoint input");
		final PathHandlingService inputRoot = new PathHandlingService().addPrefixPath("/rest",
			new PathHandlingService().addPrefixPath("/catalog", endpointInput));
		final Map<HttpMethod, PathTemplateMatcher<HttpService>> matchers = new EnumMap<>(HttpMethod.class);
		registerOracle(matchers, HttpMethod.GET, "/", service);
		registerOracle(matchers, HttpMethod.GET, "/{id}", service);
		final String[] paths = {"/", "/rest/catalog", "/rest/catalog/", "/rest/catalog/value",
			"/rest/catalog/value?id=q1&id=q2", "/rest/catalog//value", "/rest/catalog/%76alue",
			"/rest/catalog/%C5%BE", "/rest/catalog/a+b", "/rest/catalogue",
			"/metrics", "/metrics/nested", "/metricsOther", "/recording.jfr", "/startRecording",
			"/stopRecording", "/checkRecording", "/getRecordingEventTypes", "/liveness"};
		final Executable[] comparisons = new Executable[paths.length];
		for (int i = 0; i < paths.length; i++) {
			final String path = paths[i];
			comparisons[i] = () -> {
				final RoutingResult input;
				try {
					input = call(inputRoot, HttpMethod.GET, path);
				} catch (Exception exception) {
					compareSameOutcome(oldRoot, root, HttpMethod.GET, path, "nested path=" + path);
					return;
				}
				if (input.handler() == endpointInput) {
					final String endpointPath = URLUtils.normalizeSlashes(input.request().uri().getPath());
					compareAtPath(oldRoot, root, matchers, matchers, HttpMethod.GET, path,
						endpointPath, "nested path=" + path);
				} else {
					compareSameOutcome(oldRoot, root, HttpMethod.GET, path, "nested path=" + path);
				}
			};
		}
		assertAll("Nested prefix parity, including every encoding case", comparisons);
	}

	/** Confirms empty values and non-delimiter escapes on the legacy-compatible subset. */
	@Test
	@DisplayName("template edge cases agree with the fork")
	void shouldAgreeOnTemplateEdgeCases() throws Exception {
		for (final String[] example : new String[][]{
			{"/a/{x}/b", "/a//b"}, {"/a/{x}", "/a/%76alue"}, {"/a/{}/b", "/a/value/b"},
			{"/*", "/a/value"}, {"/a/{x}/b", "/a/value/%62"}
		}) {
			final ProbeService service = new ProbeService(example[0]);
			final RoutingHandlerService fork = new RoutingHandlerService().add(HttpMethod.GET, example[0], service);
			final EndpointRoutingService replacement = new EndpointRoutingService.Builder()
				.add(HttpMethod.GET, example[0], service).build();
			assertSame(service, call(fork, HttpMethod.GET, example[1]).handler());
			compareSameOutcome(fork, replacement, HttpMethod.GET, example[1], example[0] + " " + example[1]);
		}
	}

	/** Delimiters and repeated escaping intentionally differ from the fork's whole-path decoding. */
	@Test
	@DisplayName("encoded delimiters have explicit new outcomes")
	void shouldKeepIntendedEncodedPathDifferences() throws Exception {
		final ProbeService handler = new ProbeService("new contract");
		final EndpointRoutingService endpoint = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/{id}", handler).build();
		final PrefixRoutingService nested = new PrefixRoutingService().addPrefixPath("/api", endpoint);
		for (final String[] row : new String[][]{
			{"%23", "#"}, {"a%2Fb", "a/b"}, {"a%3Fb", "a?b"}, {"%252F", "%2F"}, {"a%20b", "a b"}, {"%7B", "{"}
		}) {
			assertEquals(row[1], parameters(call(nested, HttpMethod.GET, "/api/" + row[0])).get("id"));
		}
	}

	/** Builds REST catalog data, localized reads, schema, specification and CDC registrations. */
	@Nonnull
	private static List<RouteCase> catalog() {
		final List<RouteCase> routes = new ArrayList<>(250);
		add(routes, HttpMethod.GET, "/", "/collections", "/entity/get", "/entity/list",
			"/{locale}/entity/get", "/{locale}/entity/list", "/change-captures", "/schema");
		add(routes, HttpMethod.PUT, "/schema");
		for (final String collection : COLLECTIONS) {
			final String prefix = "/" + collection;
			add(routes, HttpMethod.GET, prefix + "/get", prefix + "/get/{primaryKey}", prefix + "/schema");
			add(routes, HttpMethod.POST, prefix + "/list", prefix + "/query", prefix);
			add(routes, HttpMethod.PUT, prefix + "/{primaryKey}", prefix + "/schema");
			add(routes, HttpMethod.DELETE, prefix + "/{primaryKey}", prefix);
			add(routes, HttpMethod.GET, "/{locale}" + prefix + "/get", "/{locale}" + prefix + "/get/{primaryKey}");
			add(routes, HttpMethod.POST, "/{locale}" + prefix + "/list", "/{locale}" + prefix + "/query");
		}
		withOptions(routes);
		return routes;
	}

	/** Builds the REST system endpoint inventory. */
	@Nonnull
	private static List<RouteCase> restSystem() {
		final List<RouteCase> routes = new ArrayList<>(20);
		add(routes, HttpMethod.GET, "/", "/liveness", "/catalogs", "/catalogs/{name}", "/change-captures");
		add(routes, HttpMethod.POST, "/catalogs");
		add(routes, HttpMethod.PATCH, "/catalogs/{name}");
		add(routes, HttpMethod.DELETE, "/catalogs/{name}");
		withOptions(routes);
		return routes;
	}

	/** Builds GraphQL DATA, SCHEMA and system root shapes in one representative router. */
	@Nonnull
	private static List<RouteCase> graphQL() {
		final List<RouteCase> routes = new ArrayList<>(6);
		for (final String template : new String[]{"/", "/schema"}) {
			routes.add(new RouteCase(HttpMethod.GET, template, new SocketProbeService("GraphQL GET " + template)));
			add(routes, HttpMethod.POST, template);
		}
		withOptions(routes);
		return routes;
	}

	/** Builds the standalone system API, including optional certificate downloads. */
	@Nonnull
	private static List<RouteCase> system() {
		final List<RouteCase> routes = new ArrayList<>(8);
		add(routes, HttpMethod.GET, "/status", "/liveness", "/readiness", "/server-name");
		for (final String path : new String[]{
			"/" + CertificateUtils.getGeneratedServerCertificateFileName(),
			"/" + CertificateUtils.getGeneratedClientCertificateFileName(),
			"/" + CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName()
		}) {
			routes.add(new RouteCase(HttpMethod.GET, path, new ProbeService("certificate " + path), true));
		}
		return routes;
	}

	/** Builds the only generated wildcard family. */
	@Nonnull
	private static List<RouteCase> lab() {
		final List<RouteCase> routes = new ArrayList<>(2);
		add(routes, HttpMethod.GET, "/*");
		add(routes, HttpMethod.OPTIONS, "/*");
		return routes;
	}

	/** Adds one distinguishable handler per route. */
	private static void add(@Nonnull List<RouteCase> routes, @Nonnull HttpMethod method, @Nonnull String... templates) {
		for (final String template : templates) {
			final ProbeService handler = template.equals("/change-captures") ?
				new SocketProbeService(method + " " + template) : new ProbeService(method + " " + template);
			routes.add(new RouteCase(method, template, handler));
		}
	}

	/** Adds caller-generated OPTIONS once per distinct template. */
	private static void withOptions(@Nonnull List<RouteCase> routes) {
		final Set<String> paths = CollectionUtils.createLinkedHashSet(routes.size());
		for (final RouteCase route : routes) {
			paths.add(route.template);
		}
		for (final String path : paths) {
			add(routes, HttpMethod.OPTIONS, path);
		}
	}

	/** Materializes a valid representative path, including a valid locale for localized collection routes. */
	@Nonnull
	private static String example(@Nonnull String template) {
		final String path = template.replace("{locale}", "en").replace("{primaryKey}", "42")
			.replace("{name}", "catalog")
			.replace("{id}", "value").replace("{other}", "value").replace("*", "assets/app.js");
		return path.startsWith("/") ? path : "/" + path;
	}

	/** Registers the same object in both implementations, checking exception type as well as acceptance. */
	@Nonnull
	private static String registerBoth(
		@Nonnull RoutingHandlerService fork, @Nonnull EndpointRoutingService.Builder builder, @Nonnull RouteCase route
	) {
		final HttpService wrapper = CorsService.standaloneFilter(route.handler);
		RuntimeException oldFailure = null;
		RuntimeException newFailure = null;
		try {
			fork.add(route.method, route.template, wrapper);
		} catch (RuntimeException exception) {
			oldFailure = exception;
		}
		try {
			// The fork parses certificate filenames as templates; the replacement registers them literally.
			if (route.literal) {
				builder.addLiteral(route.method, route.template, wrapper);
			} else {
				builder.add(route.method, route.template, wrapper);
			}
		} catch (RuntimeException exception) {
			newFailure = exception;
		}
		assertEquals(oldFailure == null, newFailure == null, "Registration outcome: " + route.template);
		if (oldFailure != null) {
			assertEquals(oldFailure.getClass(), newFailure.getClass(), "Registration exception: " + route.template);
		}
		return oldFailure == null ? "accepted" : "thrown";
	}

	/** Registers a fork per-method matcher without automatic registration or silent-duplicate differences. */
	private static void registerOracle(
		@Nonnull Map<HttpMethod, PathTemplateMatcher<HttpService>> matchers, @Nonnull HttpMethod method,
		@Nonnull String template, @Nonnull HttpService handler
	) {
		final PathTemplateMatcher<HttpService> matcher = matchers.computeIfAbsent(
			method, key -> new PathTemplateMatcher<>()
		);
		if (matcher.get(template) == null) {
			matcher.add(template, handler);
		}
	}

	/** Computes the oracle against the normalized decoded path presented to an endpoint router. */
	private static void compare(
		@Nonnull HttpService fork, @Nonnull HttpService replacement,
		@Nonnull Map<HttpMethod, PathTemplateMatcher<HttpService>> explicit,
		@Nonnull Map<HttpMethod, PathTemplateMatcher<HttpService>> dispatch,
		@Nonnull HttpMethod method, @Nonnull String target, @Nonnull String label
	) throws Exception {
		final String path = URLUtils.normalizeSlashes(request(method, target).uri().getPath());
		compareAtPath(fork, replacement, explicit, dispatch, method, target, path, label + " " + method + " " + target);
	}

	/** Own-method hits require full parity; misses use only the explicit other-method matcher set. */
	private static void compareAtPath(
		@Nonnull HttpService fork, @Nonnull HttpService replacement,
		@Nonnull Map<HttpMethod, PathTemplateMatcher<HttpService>> explicit,
		@Nonnull Map<HttpMethod, PathTemplateMatcher<HttpService>> dispatch,
		@Nonnull HttpMethod method, @Nonnull String target, @Nonnull String path, @Nonnull String label
	) throws Exception {
		final PathTemplateMatcher<HttpService> own = dispatch.get(method);
		if (own != null && own.match(path) != null) {
			compareSameOutcome(fork, replacement, method, target, label);
			return;
		}
		final StringJoiner allow = new StringJoiner(", ");
		for (final HttpMethod other : HttpMethod.values()) {
			final PathTemplateMatcher<HttpService> matcher = explicit.get(other);
			if (other != method && matcher != null && matcher.match(path) != null) {
				allow.add(other.name());
			}
		}
		final RoutingResult result = call(replacement, method, target);
		assertEquals(allow.length() == 0 ? HttpStatus.NOT_FOUND : HttpStatus.METHOD_NOT_ALLOWED,
			result.response().status(), "Miss oracle: " + label);
		assertEquals(allow.length() == 0 ? null : allow.toString(),
			result.response().headers().get(HttpHeaderNames.ALLOW),
			"Allow oracle: " + label);
		assertNull(result.handler(), "A method miss must not invoke a handler: " + label);
	}

	/** Compares handler identity, rewritten path, all header values, response and context query without exceptions. */
	private static void compareSameOutcome(
		@Nonnull HttpService fork, @Nonnull HttpService replacement, @Nonnull HttpMethod method,
		@Nonnull String target, @Nonnull String label
	) throws Exception {
		RoutingResult oldResult = null;
		RoutingResult newResult = null;
		Exception oldFailure = null;
		Exception newFailure = null;
		try {
			oldResult = call(fork, method, target);
		} catch (Exception exception) {
			oldFailure = exception;
		}
		try {
			newResult = call(replacement, method, target);
		} catch (Exception exception) {
			newFailure = exception;
		}
		assertEquals(oldFailure == null, newFailure == null, "Request acceptance: " + label);
		if (oldFailure != null) {
			assertEquals(oldFailure.getClass(), newFailure.getClass(), "Request failure type: " + label);
			assertEquals(oldFailure.getMessage(), newFailure.getMessage(), "Request failure message: " + label);
			return;
		}
		assertNotNull(oldResult);
		assertNotNull(newResult);
		final HttpRequest oldRequest = oldResult.request();
		assertEquals(oldResult.response().status(), newResult.response().status(), label);
		assertSame(oldResult.handler(), newResult.handler(), "Handler identity: " + label);
		assertEquals(oldResult.response().contentUtf8(), newResult.response().contentUtf8(), label);
		assertEquals(oldResult.response().headers(), newResult.response().headers(), "Response headers: " + label);
		assertEquals(oldResult.contextQuery(), newResult.contextQuery(), "Context query: " + label);
		if (oldRequest != null) {
			final RouteParameters captures = newResult.handler().context.attr(RouteParameters.KEY);
			final RequestHeadersBuilder oldHeaders = oldRequest.headers().toBuilder();
			final java.util.List<Map.Entry<String, String>> expectedCaptures = new ArrayList<>();
			// Query headers can establish a name's insertion position before path captures. Read a path-only
			// oracle to compare template capture order independently of that unrelated query insertion order.
			final int question = target.indexOf('?');
			final HttpRequest pathOnly = question < 0 ? oldRequest :
				call(fork, method, target.substring(0, question)).request();
			for (final Map.Entry<io.netty.util.AsciiString, String> header : pathOnly.headers()) {
				if (header.getKey().startsWith("internal_") &&
					!header.getKey().toString().equals("internal_*")) {
					expectedCaptures.add(Map.entry(
						header.getKey().toString().substring("internal_".length()), header.getValue()));
				}
			}
			for (final CharSequence name : oldRequest.headers().names()) {
				if (name.toString().startsWith("internal_")) {
					oldHeaders.remove(name);
					assertTrue(newResult.request().headers().getAll(name).isEmpty(), label);
				}
			}
			final List<Map.Entry<String, String>> actualCaptures = new ArrayList<>();
			if (captures != null) {
				for (int i = 0; i < captures.size(); i++) {
					actualCaptures.add(Map.entry(captures.nameAt(i).toLowerCase(Locale.ROOT), captures.valueAt(i)));
				}
			}
			assertEquals(expectedCaptures, actualCaptures, "Path capture names, values and order: " + label);
			final RequestHeadersBuilder newHeaders = newResult.request().headers().toBuilder();
			if (!oldRequest.path().equals(newResult.request().path())) {
				// Prefix transport deliberately preserves encoded spelling; compare decoded text on this subset.
				newHeaders.path(newResult.request().uri().getPath());
			}
			assertEquals(oldHeaders.build(), newHeaders.build(), "Other forwarded headers and remainder: " + label);
		}
	}

	/**
	 * A generated registration and its actual handler object.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private record RouteCase(
		@Nonnull HttpMethod method, @Nonnull String template, @Nonnull ProbeService handler, boolean literal
	) {
		/** Constructs an ordinary template registration. */
		private RouteCase(@Nonnull HttpMethod method, @Nonnull String template, @Nonnull ProbeService handler) {
			this(method, template, handler, false);
		}
	}

	/** The old fallback matches the method text `/GET` as a parameter, producing a false 405. */
	@Test
	@DisplayName("fixes fork false 405 for GET //value with only GET root and id routes")
	void shouldFixForkFalse405ForOwnMethodMiss() throws Exception {
		final ProbeService service = new ProbeService("false 405");
		final RoutingHandlerService fork = new RoutingHandlerService().add(HttpMethod.GET, "/", service)
			.add(HttpMethod.GET, "/{id}", service);
		final EndpointRoutingService replacement = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/", service)
			.add(HttpMethod.GET, "/{id}", service).build();
		assertEquals(HttpStatus.METHOD_NOT_ALLOWED, call(fork, HttpMethod.GET, "//value").response().status());
		assertEquals(HttpStatus.NOT_FOUND, call(replacement, HttpMethod.GET, "//value").response().status());
		final Map<HttpMethod, PathTemplateMatcher<HttpService>> matchers = new EnumMap<>(HttpMethod.class);
		registerOracle(matchers, HttpMethod.GET, "/", service);
		registerOracle(matchers, HttpMethod.GET, "/{id}", service);
		compare(fork, replacement, matchers, matchers, HttpMethod.GET, "//value", "false 405 regression");
	}

	/** Unsupported registration shapes deliberately narrow the fork grammar rather than pretending parity. */
	@Test
	@DisplayName("new builder rejects three shapes outside the generated template grammar")
	void shouldRejectNarrowedTemplateShapes() {
		for (final String template : new String[]{"/literal/", "/{a}/x/{a}", "/literal?query"}) {
			final ProbeService handler = new ProbeService(template);
			assertDoesNotThrow(() -> new RoutingHandlerService().add(HttpMethod.GET, template, handler));
			assertThrows(RuntimeException.class,
				() -> new EndpointRoutingService.Builder().add(HttpMethod.GET, template, handler), template);
		}
	}

	/** Automatic CONNECT participates in dispatch parity but never in the miss oracle's explicit Allow set. */
	@Test
	@DisplayName("computed oracle distinguishes automatic CONNECT from explicit methods")
	void shouldExcludeAutomaticConnectFromMissOracle() throws Exception {
		final SocketProbeService handler = new SocketProbeService("automatic CONNECT");
		final RoutingHandlerService fork = new RoutingHandlerService().add(HttpMethod.GET, "/ws", handler);
		final EndpointRoutingService replacement = new EndpointRoutingService.Builder()
			.add(HttpMethod.GET, "/ws", handler).build();
		final Map<HttpMethod, PathTemplateMatcher<HttpService>> explicit = new EnumMap<>(HttpMethod.class);
		final Map<HttpMethod, PathTemplateMatcher<HttpService>> dispatch = new EnumMap<>(HttpMethod.class);
		registerOracle(explicit, HttpMethod.GET, "/ws", handler);
		registerOracle(dispatch, HttpMethod.GET, "/ws", handler);
		registerOracle(dispatch, HttpMethod.CONNECT, "/ws", handler);
		compare(fork, replacement, explicit, dispatch, HttpMethod.CONNECT, "/ws", "automatic CONNECT hit");
		compare(fork, replacement, explicit, dispatch, HttpMethod.PUT, "/ws", "automatic CONNECT exclusion");
		compare(fork, replacement, explicit, dispatch, HttpMethod.GET, "/missing", "own-method miss");
	}
}
