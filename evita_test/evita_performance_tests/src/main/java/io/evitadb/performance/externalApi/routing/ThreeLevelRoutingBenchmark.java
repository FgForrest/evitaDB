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
import org.openjdk.jmh.annotations.*;

import javax.annotation.Nonnull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.evitadb.performance.externalApi.routing.RoutingBenchmarkFixtures.*;

/**
 * Acceptance scenario: real L1 API prefix -> L2 catalog prefix -> L3 endpoint serve, including two :path rewrites,
 * capture headers and one original context query parameter. REST and GraphQL share the same pipeline fixture.
 * Every setup checks the actual measured router's response identity, then constructs an identical verification
 * pipeline with a checking terminal to assert forwarded path, internal_* values and preserved context query.
 * Checking handlers are discarded after setup; measured terminals consume forwarding through a Blackhole.
 * FORK parses URI paths and builds query/capture headers; REGION retains encoded targets and context captures.
 *
 * FORK retains URI parsing and header injection; the new arms consume context captures and do less work.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@State(Scope.Thread)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ThreeLevelRoutingBenchmark {
	@Param({"FORK", "REGION"})
	public String arm;
	@Param({"1", "10", "100"})
	public int catalogs;
	@Param({"5", "50", "500"})
	public int collections;
	@Param({"REST_LITERAL", "REST_PARAMETER", "REST_LOCALIZED", "GRAPHQL_DATA", "GRAPHQL_SCHEMA"})
	public String requestShape;

	private HttpService router;
	private HttpRequest request;
	private ServiceRequestContext context;

	/** Builds and checks the complete acceptance pipeline without invoking any annotated benchmark method. */
	@Setup(Level.Trial)
	public void setup() throws Exception {
		final List<RoutingBenchmarkRoute> restRoutes = rest(this.collections);
		final List<RoutingBenchmarkRoute> graphQLRoutes = shortRoutes("GRAPHQL");
		final boolean graphQL = this.requestShape.startsWith("GRAPHQL_");
		final HttpMethod method = this.requestShape.equals("REST_LITERAL") ||
			this.requestShape.equals("GRAPHQL_DATA") ? HttpMethod.POST : HttpMethod.GET;
		final String remainder = switch (this.requestShape) {
			case "REST_LITERAL" -> "/Product/query";
			case "REST_PARAMETER" -> "/Product/get/42";
			case "REST_LOCALIZED" -> "/en/Product/get/42";
			case "GRAPHQL_DATA" -> "";
			case "GRAPHQL_SCHEMA" -> "/schema";
			default -> throw new IllegalArgumentException(this.requestShape);
		};
		final String template = switch (this.requestShape) {
			case "REST_PARAMETER" -> "/Product/get/{primaryKey}";
			case "REST_LOCALIZED" -> "/{locale}/Product/get/{primaryKey}";
			case "GRAPHQL_DATA" -> "/";
			default -> remainder;
		};
		final List<RoutingBenchmarkRoute> selectedRoutes = graphQL ? graphQLRoutes : restRoutes;
		final RoutingBenchmarkRoute expected = route(selectedRoutes, method, template);
		final String target = (graphQL ? "/gql/" : "/rest/") + catalogName(this.catalogs / 2) + remainder;
		this.request = RoutingBenchmarkFixtures.request(method, target + "?trace=one");
		this.context = ServiceRequestContext.builder(this.request).build();
		this.router = threeLevels(this.arm, this.catalogs,
			endpoints(this.arm, restRoutes), endpoints(this.arm, graphQLRoutes));
		verify(this.router, this.context, this.request, expected.response(), HttpStatus.OK);

		final List<RoutingBenchmarkRoute> checkedRoutes = new ArrayList<>(selectedRoutes);
		checkedRoutes.set(selectedRoutes.indexOf(expected), new RoutingBenchmarkRoute(
			method, template, (ctx, req) -> {
				if (!remainder.equals(req.headers().path()) || !target.equals(ctx.path()) ||
					!"trace=one".equals(ctx.query()) ||
					(this.arm.equals("FORK") && !"one".equals(req.headers().get("internal_trace"))) ||
					(this.requestShape.startsWith("REST_") && !this.requestShape.equals("REST_LITERAL") &&
						!"42".equals(parameter(this.arm, ctx, req, "primaryKey"))) ||
					(this.requestShape.equals("REST_LOCALIZED") &&
						!"en".equals(parameter(this.arm, ctx, req, "locale")))) {
					throw new IllegalStateException("Incorrect three-level forwarding: " + req.headers());
				}
				return expected.response();
			}, expected.response()
		));
		final HttpService checked = threeLevels(this.arm, this.catalogs,
			endpoints(this.arm, graphQL ? restRoutes : checkedRoutes),
			endpoints(this.arm, graphQL ? checkedRoutes : graphQLRoutes));
		verify(checked, this.context, this.request, expected.response(), HttpStatus.OK);
	}

	/** Returns the acceptance pipeline's response so JMH consumes every dispatch result. */
	@Benchmark
	@Nonnull
	public HttpResponse serve(@Nonnull org.openjdk.jmh.infra.Blackhole sink) throws Exception {
		this.context.setAttr(BLACKHOLE, sink);
		return this.router.serve(this.context, this.request);
	}
}
