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
 * One catalog's generated REST routes. Each collection has localized variants, schema/data routes and OPTIONS.
 * The Product collection sits in the middle of the generated inventory. WRONG_METHOD is informational only:
 * FORK returns 404 via its defective method-name probe, whereas REGION/HASHMAP search other method tries and
 * build a 405 with Allow. Its scores cannot establish old/new performance acceptance.
 * HASHMAP preserves the method trie topology but replaces literal region probes with HashMap/substring probes.
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
public class EndpointRoutingBenchmark {
	@Param({"FORK", "REGION", "HASHMAP"})
	public String arm;
	@Param({"5", "50", "500"})
	public int collections;
	@Param({"LITERAL", "PARAMETER", "LOCALIZED", "OPTIONS", "NEAR_MISS", "WRONG_METHOD"})
	public String requestShape;

	private HttpService router;
	private HttpRequest request;
	private ServiceRequestContext context;

	/** Builds the common route set and verifies response identity for hits and exact status for misses. */
	@Setup(Level.Trial)
	public void setup() throws Exception {
		final List<RoutingBenchmarkRoute> routes = rest(this.collections);
		final HttpMethod method = switch (this.requestShape) {
			case "LITERAL" -> HttpMethod.POST;
			case "OPTIONS" -> HttpMethod.OPTIONS;
			case "WRONG_METHOD" -> HttpMethod.PATCH;
			case "PARAMETER", "LOCALIZED", "NEAR_MISS" -> HttpMethod.GET;
			default -> throw new IllegalArgumentException(this.requestShape);
		};
		final String path = switch (this.requestShape) {
			case "LITERAL", "WRONG_METHOD", "OPTIONS" -> "/Product/query";
			case "PARAMETER" -> "/Product/get/42";
			case "LOCALIZED" -> "/en/Product/get/42";
			case "NEAR_MISS" -> "/Product/get/42/absent";
			default -> throw new IllegalArgumentException(this.requestShape);
		};
		final String template = switch (this.requestShape) {
			case "PARAMETER" -> "/Product/get/{primaryKey}";
			case "LOCALIZED" -> "/{locale}/Product/get/{primaryKey}";
			default -> path;
		};
		final boolean miss = this.requestShape.equals("NEAR_MISS") || this.requestShape.equals("WRONG_METHOD");
		final HttpResponse expected = miss ? null : route(routes, method, template).response();
		final HttpStatus status = this.requestShape.equals("WRONG_METHOD") && !this.arm.equals("FORK") ?
			HttpStatus.METHOD_NOT_ALLOWED : HttpStatus.NOT_FOUND;
		this.router = endpoints(this.arm, routes);
		this.request = RoutingBenchmarkFixtures.request(method, path);
		this.context = ServiceRequestContext.builder(this.request).build();
		verify(this.router, this.context, this.request, expected, status);
	}

	/** Dispatches the same real request through either implementation, returning its response to JMH. */
	@Benchmark
	@Nonnull
	public HttpResponse endpoint(@Nonnull org.openjdk.jmh.infra.Blackhole sink) throws Exception {
		this.context.setAttr(BLACKHOLE, sink);
		return this.router.serve(this.context, this.request);
	}
}
