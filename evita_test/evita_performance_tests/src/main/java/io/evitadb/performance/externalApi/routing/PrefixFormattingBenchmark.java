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
import io.evitadb.externalApi.utils.path.routing.UndertowLogger;
import io.evitadb.utils.CollectionUtils;
import org.openjdk.jmh.annotations.*;

import javax.annotation.Nonnull;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.evitadb.performance.externalApi.routing.RoutingBenchmarkFixtures.*;

/**
 * Attribution only: the exact UndertowLogger -> String.format call made on a prefix hit, with debug logging off.
 * This contains no lookup or header rewrite and is not an alternative router or an acceptance measurement.
 * Each full REST/GraphQL request pays this factor twice in FORK. Do not subtract confidence intervals as if
 * independent microbenchmarks were an exact additive decomposition: inlining and GC can interact with routing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@State(Scope.Thread)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class PrefixFormattingBenchmark {
	@Param({"1", "10", "100"})
	public int catalogs;
	@Param({"HIT"})
	public String requestShape;

	private String prefix;
	private String path;

	/** Checks debug is off and the identical prefix request reaches its intended handler in the fork. */
	@Setup(Level.Trial)
	public void setup() throws Exception {
		requireDebugOff();
		if (!this.requestShape.equals("HIT")) {
			throw new IllegalArgumentException(this.requestShape);
		}
		this.prefix = "/" + catalogName(this.catalogs / 2);
		this.path = this.prefix + "/Product/get/42";
		final HttpResponse expected = HttpResponse.of(HttpStatus.OK);
		final Map<String, HttpService> bindings = CollectionUtils.createHashMap(this.catalogs);
		for (int i = 0; i < this.catalogs; i++) {
			final HttpResponse response = i == this.catalogs / 2 ? expected : HttpResponse.of(HttpStatus.OK);
			bindings.put("/" + catalogName(i), terminal("FORK", response));
		}
		final HttpService router = prefixes("FORK", bindings);
		final HttpRequest request = RoutingBenchmarkFixtures.request(HttpMethod.GET, this.path);
		verify(router, ServiceRequestContext.builder(request).build(), request, expected, HttpStatus.OK);
	}

	/** Returns the exact formatted string separately so JMH consumes the formatter's output directly. */
	@Benchmark
	@Nonnull
	public String formatOnly() {
		return String.format("Matched prefix path %s for path %s", this.prefix, this.path);
	}

	/** Calls the actual unmodified fork adapter, including its varargs array and unconditional String.format. */
	@Benchmark
	public void formatMatch() {
		UndertowLogger.REQUEST_LOGGER.debugf("Matched prefix path %s for path %s", this.prefix, this.path);
	}
}
