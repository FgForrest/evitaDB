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

package io.evitadb.performance.externalApi.routing;

import com.linecorp.armeria.common.*;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.externalApi.http.routing.EndpointRoutingService;
import io.evitadb.externalApi.http.routing.RouteParameters;
import io.netty.util.AttributeKey;
import org.openjdk.jmh.infra.Blackhole;
import io.evitadb.externalApi.http.routing.PrefixRoutingService;
import io.evitadb.utils.CertificateUtils;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared generated inventories and one-shot setup checks. No benchmark runner or measurement loop lives here.
 * Each route owns a distinct prebuilt response, so response identity proves selection of the expected handler.
 *
 * Requests and contexts are built once per thread (per group reader where applicable).
 * REGION/HASHMAP use encoded bounds and attach named captures to context. Prefixes rewrite requests in every arm.
 * Measured terminals consume forwarded path, context query and context captures through a JMH
 * Blackhole supplied by each benchmark invocation. No benchmark or setup is executed during compile-only validation.
 * Trivial handlers return their prebuilt response without subscribing to it. Setup never aggregates a hit response,
 * so it remains unused; only fresh fallback responses are aggregated. This reuse is identical for every arm.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class RoutingBenchmarkFixtures {
	/** Invocation-supplied sink, private to each reader's context. */
	static final AttributeKey<Blackhole> BLACKHOLE = AttributeKey.valueOf(RoutingBenchmarkFixtures.class, "sink");
	/** Prevents construction of the fixture namespace. */
	private RoutingBenchmarkFixtures() {
	}

	/** Supplies the pseudo-headers present on real incoming requests. */
	@Nonnull
	static HttpRequest request(@Nonnull HttpMethod method, @Nonnull String pathAndQuery) {
		return HttpRequest.of(RequestHeaders.builder(method, pathAndQuery)
			.scheme("http").authority("localhost").build());
	}

	/** Builds a terminal that observes precisely the forwarded path and context captures. */
	@Nonnull
	static HttpService terminal(@Nonnull HttpResponse response) {
		return consuming((ctx, req) -> response);
	}

	/** Wraps once during setup; no lambda or sink is allocated on the measured request path. */
	@Nonnull
	private static HttpService consuming(@Nonnull HttpService delegate) {
		return (ctx, req) -> {
			final Blackhole sink = ctx.attr(BLACKHOLE);
			if (sink != null) {
				sink.consume(req.path());
				sink.consume(ctx.query());
				final RouteParameters parameters = ctx.attr(RouteParameters.KEY);
				if (parameters != null) {
					for (int i = 0; i < parameters.size(); i++) {
						sink.consume(parameters.nameAt(i));
						sink.consume(parameters.valueAt(i));
					}
				}
			}
			return delegate.serve(ctx, req);
		};
	}

	/** Reads one named capture from the context carrier consumed by production REST handlers. */
	@Nullable
	static String parameter(@Nonnull ServiceRequestContext ctx, @Nonnull String name) {
		final RouteParameters parameters = ctx.attr(RouteParameters.KEY);
		if (parameters != null) {
			for (int i = 0; i < parameters.size(); i++) {
				if (parameters.nameAt(i).equals(name)) {
					return parameters.valueAt(i);
				}
			}
		}
		return null;
	}

	/** Produces varied catalog lengths, including the same length for multiple catalogs. */
	@Nonnull
	static String catalogName(int index) {
		return "catalog" + "x".repeat(index % 13) + index;
	}

	/** Builds the selected prefix implementation with exactly the supplied bindings. */
	@Nonnull
	static HttpService prefixes(@Nonnull String arm, @Nonnull Map<String, HttpService> bindings) {
		return switch (arm) {
			case "REGION" -> {
				final PrefixRoutingService router = new PrefixRoutingService();
				bindings.forEach(router::addPrefixPath);
				yield router;
			}
			case "HASHMAP" -> new SubstringRoutingReference(bindings);
			default -> throw new IllegalArgumentException("Unknown routing arm: " + arm);
		};
	}

	/** Builds the selected endpoint implementation from one common route inventory. */
	@Nonnull
	static HttpService endpoints(@Nonnull String arm, @Nonnull List<RoutingBenchmarkRoute> routes) {
		final List<RoutingBenchmarkRoute> consumed = new ArrayList<>(routes.size());
		for (final RoutingBenchmarkRoute route : routes) {
			consumed.add(new RoutingBenchmarkRoute(route.method(), route.template(),
				consuming(route.handler()), route.response()));
		}
		return switch (arm) {
			case "REGION" -> {
				final EndpointRoutingService.Builder builder = new EndpointRoutingService.Builder();
				for (final RoutingBenchmarkRoute route : consumed) {
					builder.add(route.method(), route.template(), route.handler());
				}
				yield builder.build();
			}
			case "HASHMAP" -> new SubstringRoutingReference(consumed);
			default -> throw new IllegalArgumentException("Unknown routing arm: " + arm);
		};
	}

	/** Generates the REST catalog route inventory, including localized reads and per-path OPTIONS. */
	@Nonnull
	static List<RoutingBenchmarkRoute> rest(int collections) {
		final List<RoutingBenchmarkRoute> routes = new ArrayList<>(collections * 28 + 24);
		add(routes, HttpMethod.GET, "/", "/collections", "/entity/get", "/entity/list",
			"/{locale}/entity/get", "/{locale}/entity/list", "/change-captures", "/schema");
		add(routes, HttpMethod.PUT, "/schema");
		for (int i = 0; i < collections; i++) {
			final String prefix = i == collections / 2 ? "/Product" : "/Collection" + "x".repeat(i % 9) + i;
			add(routes, HttpMethod.GET, prefix + "/get", prefix + "/get/{primaryKey}", prefix + "/schema");
			add(routes, HttpMethod.POST, prefix + "/list", prefix + "/query", prefix);
			add(routes, HttpMethod.PUT, prefix + "/{primaryKey}", prefix + "/schema");
			add(routes, HttpMethod.DELETE, prefix + "/{primaryKey}", prefix);
			add(routes, HttpMethod.GET, "/{locale}" + prefix + "/get", "/{locale}" + prefix + "/get/{primaryKey}");
			add(routes, HttpMethod.POST, "/{locale}" + prefix + "/list", "/{locale}" + prefix + "/query");
		}
		options(routes);
		return routes;
	}

	/** Generates short GraphQL, system API and lab inventories without introducing arbitrary route shapes. */
	@Nonnull
	static List<RoutingBenchmarkRoute> shortRoutes(@Nonnull String family) {
		final List<RoutingBenchmarkRoute> routes = new ArrayList<>(12);
		switch (family) {
			case "GRAPHQL" -> {
				add(routes, HttpMethod.GET, "/", "/schema");
				add(routes, HttpMethod.POST, "/", "/schema");
				options(routes);
			}
			case "SYSTEM" -> add(routes, HttpMethod.GET, "/status", "/liveness", "/readiness", "/server-name",
				"/" + CertificateUtils.getGeneratedServerCertificateFileName(),
				"/" + CertificateUtils.getGeneratedClientCertificateFileName(),
				"/" + CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName());
			case "LAB" -> {
				add(routes, HttpMethod.GET, "/*");
				add(routes, HttpMethod.OPTIONS, "/*");
			}
			default -> throw new IllegalArgumentException("Unknown route family: " + family);
		}
		return routes;
	}

	/** Creates distinguishable trivial services returning prebuilt responses. */
	static void add(
		@Nonnull List<RoutingBenchmarkRoute> routes, @Nonnull HttpMethod method, @Nonnull String... templates
	) {
		for (final String template : templates) {
			final HttpResponse response = HttpResponse.of(HttpStatus.OK);
			routes.add(new RoutingBenchmarkRoute(method, template, (ctx, req) -> response, response));
		}
	}

	/** Adds one OPTIONS registration per distinct generated path, just as RestRouter does. */
	private static void options(@Nonnull List<RoutingBenchmarkRoute> routes) {
		final Set<String> paths = CollectionUtils.createLinkedHashSet(routes.size());
		for (final RoutingBenchmarkRoute route : routes) {
			paths.add(route.template());
		}
		for (final String path : paths) {
			add(routes, HttpMethod.OPTIONS, path);
		}
	}

	/** Finds the explicitly expected route; this is fixture metadata, never an oracle calling another router. */
	@Nonnull
	static RoutingBenchmarkRoute route(
		@Nonnull List<RoutingBenchmarkRoute> routes, @Nonnull HttpMethod method, @Nonnull String template
	) {
		for (final RoutingBenchmarkRoute route : routes) {
			if (route.method() == method && route.template().equals(template)) {
				return route;
			}
		}
		throw new IllegalStateException("Missing fixture route: " + method + " " + template);
	}

	/** Checks the actual measured router once, without executing any annotated benchmark method. */
	static void verify(
		@Nonnull HttpService router, @Nonnull ServiceRequestContext context, @Nonnull HttpRequest request,
		@Nullable HttpResponse expected, @Nonnull HttpStatus status
	) throws Exception {
		if (request.headers().scheme() == null || request.headers().authority() == null) {
			throw new IllegalStateException("Benchmark request lacks :scheme/:authority: " + request.headers());
		}
		final HttpResponse actual = router.serve(context, request);
		if (expected != null) {
			if (actual != expected) {
				throw new IllegalStateException("Wrong handler for " + request.headers());
			}
		} else if (!actual.aggregate().join().status().equals(status)) {
			throw new IllegalStateException("Wrong fallback status for " + request.headers() + "; expected " + status);
		}
	}

	/** Builds real L1 API prefixes, L2 catalogs and the selected L3 catalog router. */
	@Nonnull
	static HttpService threeLevels(
		@Nonnull String arm, int catalogs, @Nonnull HttpService rest, @Nonnull HttpService graphQL
	) {
		final Map<String, HttpService> restCatalogs = CollectionUtils.createHashMap(catalogs);
		final Map<String, HttpService> graphQLCatalogs = CollectionUtils.createHashMap(catalogs);
		for (int i = 0; i < catalogs; i++) {
			// Non-target catalogs have distinct responses so an incorrect L2 choice cannot pass setup.
			final HttpResponse other = HttpResponse.of(HttpStatus.OK);
			final HttpService decoy = terminal(other);
			restCatalogs.put("/" + catalogName(i), i == catalogs / 2 ? rest : decoy);
			graphQLCatalogs.put("/" + catalogName(i), i == catalogs / 2 ? graphQL : decoy);
		}
		final Map<String, HttpService> apis = CollectionUtils.createHashMap(4);
		apis.put("/rest", prefixes(arm, restCatalogs));
		apis.put("/gql", prefixes(arm, graphQLCatalogs));
		apis.put("/system", endpoints(arm, shortRoutes("SYSTEM")));
		apis.put("/lab", endpoints(arm, shortRoutes("LAB")));
		return prefixes(arm, apis);
	}

	/**
	 * Registration metadata with a response identity oracle independent of dispatch implementation.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	record RoutingBenchmarkRoute(
		@Nonnull HttpMethod method, @Nonnull String template,
		@Nonnull HttpService handler, @Nonnull HttpResponse response
	) {
	}
}
