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
import io.evitadb.utils.CollectionUtils;
import org.openjdk.jmh.annotations.*;

import javax.annotation.Nonnull;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.evitadb.performance.externalApi.routing.RoutingBenchmarkFixtures.*;

/**
 * L2 prefix dispatch at catalog scale, including remainder rewriting. FORK and REGION use their public serve API;
 * HASHMAP is the plain substring reference. Prefix formatting cost is reported by PrefixFormattingBenchmark separately.
 * The middle catalog has a distinct response from every other catalog. MISS uses an unregistered catalog name.
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
public class PrefixRoutingBenchmark {
	@Param({"FORK", "REGION", "HASHMAP"})
	public String arm;
	@Param({"1", "10", "100"})
	public int catalogs;
	@Param({"HIT", "MISS"})
	public String requestShape;

	private HttpService router;
	private HttpRequest request;
	private ServiceRequestContext context;

	/** Builds one immutable request fixture and verifies the actual measured arm's handler or status. */
	@Setup(Level.Trial)
	public void setup() throws Exception {
		final Map<String, HttpService> bindings = CollectionUtils.createHashMap(this.catalogs);
		HttpResponse expected = null;
		for (int i = 0; i < this.catalogs; i++) {
			final HttpResponse response = HttpResponse.of(HttpStatus.OK);
			bindings.put("/" + catalogName(i), terminal(this.arm, response));
			if (i == this.catalogs / 2) {
				expected = response;
			}
		}
		final String catalog = switch (this.requestShape) {
			case "HIT" -> catalogName(this.catalogs / 2);
			case "MISS" -> "catalogAbsent";
			default -> throw new IllegalArgumentException(this.requestShape);
		};
		this.router = prefixes(this.arm, bindings);
		this.request = RoutingBenchmarkFixtures.request(HttpMethod.GET, "/" + catalog + "/Product/get/42");
		this.context = ServiceRequestContext.builder(this.request).build();
		verify(this.router, this.context, this.request, this.requestShape.equals("HIT") ? expected : null,
			HttpStatus.NOT_FOUND);
	}

	/** Returns the selected prebuilt response, or the freshly allocated fallback, for JMH consumption. */
	@Benchmark
	@Nonnull
	public HttpResponse prefix(@Nonnull org.openjdk.jmh.infra.Blackhole sink) throws Exception {
		this.context.setAttr(BLACKHOLE, sink);
		return this.router.serve(this.context, this.request);
	}
}
