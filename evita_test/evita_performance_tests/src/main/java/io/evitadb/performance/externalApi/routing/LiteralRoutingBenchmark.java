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

import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.evitadb.performance.externalApi.routing.RoutingBenchmarkFixtures.*;

/**
 * Small static endpoint dispatchers: GraphQL root/schema, standalone system literals, and lab's lone wildcard.
 * requestShape identifies the route family, registration count and request; there is no hidden route-set default.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@State(Scope.Thread)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class LiteralRoutingBenchmark {
	@Param({"REGION"})
	public String arm;
	@Param({"GRAPHQL_6_ROOT", "GRAPHQL_6_SCHEMA", "SYSTEM_7_STATUS", "SYSTEM_7_LIVENESS", "LAB_2_ASSET"})
	public String requestShape;

	private HttpService router;
	private HttpRequest request;
	private ServiceRequestContext context;

	/** Verifies the exact literal or wildcard handler before measurement. */
	@Setup(Level.Trial)
	public void setup() throws Exception {
		final String family = this.requestShape.substring(0, this.requestShape.indexOf('_'));
		final List<RoutingBenchmarkRoute> routes = shortRoutes(family);
		final String path = switch (this.requestShape) {
			case "GRAPHQL_6_ROOT" -> "/";
			case "GRAPHQL_6_SCHEMA" -> "/schema";
			case "SYSTEM_7_STATUS" -> "/status";
			case "SYSTEM_7_LIVENESS" -> "/liveness";
			case "LAB_2_ASSET" -> "/assets/app.js";
			default -> throw new IllegalArgumentException(this.requestShape);
		};
		this.router = endpoints(this.arm, routes);
		this.request = RoutingBenchmarkFixtures.request(HttpMethod.GET, path);
		this.context = ServiceRequestContext.builder(this.request).build();
		verify(this.router, this.context, this.request,
			route(routes, HttpMethod.GET, family.equals("LAB") ? "/*" : path).response(), HttpStatus.OK);
	}

	/** Measures static endpoint dispatch without a catalog prefix layer. */
	@Benchmark
	@Nonnull
	public HttpResponse literal(@Nonnull org.openjdk.jmh.infra.Blackhole sink) throws Exception {
		this.context.setAttr(BLACKHOLE, sink);
		return this.router.serve(this.context, this.request);
	}
}
