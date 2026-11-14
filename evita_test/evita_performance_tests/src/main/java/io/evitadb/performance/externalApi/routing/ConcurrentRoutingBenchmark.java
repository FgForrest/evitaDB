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
import io.evitadb.externalApi.http.routing.PrefixRoutingService;
import io.evitadb.externalApi.utils.path.PathHandlingService;
import io.evitadb.utils.CollectionUtils;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import javax.annotation.Nonnull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.evitadb.performance.externalApi.routing.RoutingBenchmarkFixtures.*;

/**
 * Three stable readers traverse L2+L3 while one writer replaces a different catalog. FORK uses production's
 * remove/add pair; REGION uses replacePrefixPath. Both toggle between two prebuilt L3 routers and pay the same
 * bounded consumeCPU backoff after each update. The backoff bounds pressure; it does not enforce equal update rates.
 * Report the churn:reader secondary throughput, not the aggregate group score that includes writer operations.
 * Group GC allocation is reader plus writer allocation and cannot be interpreted as bytes per reader request.
 *
 * FORK retains URI parsing and header injection; the new arms consume context captures and do less work.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ConcurrentRoutingBenchmark {
	/**
	 * One L2 graph shared by a group; only the writer accesses the replacement toggle.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@State(Scope.Group)
	public static class ChurnState {
		@Param({"FORK", "REGION"})
		public String arm;
		@Param({"10", "100"})
		public int catalogs;
		@Param({"5", "50"})
		public int collections;
		@Param({"REST_PARAMETER", "GRAPHQL_SCHEMA"})
		public String requestShape;
		@Param({"10000"})
		public long writerTokens;

		private HttpService router;
		private HttpService first;
		private HttpService second;
		private String changedPrefix;
		private HttpResponse expected;
		private boolean alternate;

		/** Builds stable and replacement catalogs and validates dispatch before any group threads start. */
		@Setup(Level.Trial)
		public void setup() throws Exception {
			final List<RoutingBenchmarkRoute> routes = switch (this.requestShape) {
				case "REST_PARAMETER" -> rest(this.collections);
				case "GRAPHQL_SCHEMA" -> shortRoutes("GRAPHQL");
				default -> throw new IllegalArgumentException(this.requestShape);
			};
			this.expected = route(routes, HttpMethod.GET,
				this.requestShape.equals("REST_PARAMETER") ? "/Product/get/{primaryKey}" : "/schema").response();
			final HttpService stable = endpoints(this.arm, routes);
			final List<RoutingBenchmarkRoute> firstRoutes = this.requestShape.equals("REST_PARAMETER") ?
				rest(this.collections) : shortRoutes("GRAPHQL");
			final List<RoutingBenchmarkRoute> secondRoutes = this.requestShape.equals("REST_PARAMETER") ?
				rest(this.collections) : shortRoutes("GRAPHQL");
			this.first = endpoints(this.arm, firstRoutes);
			this.second = endpoints(this.arm, secondRoutes);
			final String template = this.requestShape.equals("REST_PARAMETER") ?
				"/Product/get/{primaryKey}" : "/schema";
			final HttpResponse firstResponse = route(firstRoutes, HttpMethod.GET, template).response();
			final HttpResponse secondResponse = route(secondRoutes, HttpMethod.GET, template).response();
			this.changedPrefix = "/" + catalogName(this.catalogs - 1);
			final Map<String, HttpService> bindings = CollectionUtils.createHashMap(this.catalogs);
			for (int i = 0; i < this.catalogs; i++) {
				bindings.put("/" + catalogName(i), i == this.catalogs / 2 ? stable : this.first);
			}
			this.router = prefixes(this.arm, bindings);
			final HttpRequest stableRequest = request();
			verify(this.router, ServiceRequestContext.builder(stableRequest).build(), stableRequest,
				this.expected, HttpStatus.OK);
			// One-shot writer checks validate both replacement objects; no benchmark method is called here.
			replace();
			final HttpRequest changed = RoutingBenchmarkFixtures.request(HttpMethod.GET, this.changedPrefix + tail());
			verify(this.router, ServiceRequestContext.builder(changed).build(), changed, secondResponse, HttpStatus.OK);
			replace();
			verify(this.router, ServiceRequestContext.builder(changed).build(), changed, firstResponse, HttpStatus.OK);
		}

		/** Supplies one real request per reader; the stable catalog is distinct from the changed last catalog. */
		@Nonnull
		private HttpRequest request() {
			return RoutingBenchmarkFixtures.request(HttpMethod.GET, "/" + catalogName(this.catalogs / 2) + tail());
		}

		/** Identifies the endpoint part of this explicit request fixture. */
		@Nonnull
		private String tail() {
			return this.requestShape.equals("REST_PARAMETER") ? "/Product/get/42" : "/schema";
		}

		/** Publishes one update through the same API the corresponding production caller uses. */
		private void replace() {
			final HttpService replacement = this.alternate ? this.first : this.second;
			this.alternate = !this.alternate;
			if (this.router instanceof PrefixRoutingService region) {
				region.replacePrefixPath(this.changedPrefix, replacement);
			} else if (this.router instanceof PathHandlingService fork) {
				fork.removePrefixPath(this.changedPrefix);
				fork.addPrefixPath(this.changedPrefix, replacement);
			} else {
				throw new IllegalStateException("Unexpected churn router: " + this.router);
			}
		}
	}

	/**
	 * Invocation-independent request/context owned by one reader, never shared with another reader or writer.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@State(Scope.Thread)
	public static class ReaderState {
		private HttpRequest request;
		private ServiceRequestContext context;

		/** Validates each reader's request against the selected shared routing arm. */
		@Setup(Level.Trial)
		public void setup(@Nonnull ChurnState shared) throws Exception {
			this.request = shared.request();
			this.context = ServiceRequestContext.builder(this.request).build();
			verify(shared.router, this.context, this.request, shared.expected, HttpStatus.OK);
		}
	}

	/** Measures stable-reader dispatch; JMH consumes the response and reports churn:reader separately. */
	@Benchmark
	@Group("churn")
	@GroupThreads(3)
	@Nonnull
	public HttpResponse reader(@Nonnull ChurnState shared, @Nonnull ReaderState reader,
		@Nonnull Blackhole sink) throws Exception {
		reader.context.setAttr(BLACKHOLE, sink);
		return shared.router.serve(reader.context, reader.request);
	}

	/** Replaces a different catalog and applies the same fixed, bounded writer backoff in both arms. */
	@Benchmark
	@Group("churn")
	@GroupThreads(1)
	public void writer(@Nonnull ChurnState shared) {
		shared.replace();
		Blackhole.consumeCPU(shared.writerTokens);
	}
}
