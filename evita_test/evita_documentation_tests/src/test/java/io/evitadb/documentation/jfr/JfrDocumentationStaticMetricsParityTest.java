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

package io.evitadb.documentation.jfr;

import io.evitadb.externalApi.observability.metric.MetricHandler;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Info;
import io.prometheus.metrics.core.metrics.Metric;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static io.evitadb.test.TestTags.OBSERVABILITY;
import static io.evitadb.test.TestTags.OBSERVABILITY_API;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Guards against silent drift between the Prometheus metrics that
 * {@link MetricHandler} registers directly (statically, at class load) and the
 * hand-curated descriptors in {@link JfrDocumentation#STATIC_METRICS} that drive the
 * generated `metrics.md`. If somebody adds a new static metric to {@link MetricHandler}
 * but forgets to add a descriptor here, this test fails — keeping the documentation
 * in lockstep with the runtime.
 *
 * Only metrics registered at MetricHandler class-load are compared; JFR-driven and
 * JVM library metrics are registered lazily and are intentionally out of scope.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("JfrDocumentation STATIC_METRICS list must mirror MetricHandler registrations")
@Tag(OBSERVABILITY_API)
@Tag(OBSERVABILITY)
class JfrDocumentationStaticMetricsParityTest {

	/**
	 * The build-info metric is registered through {@link MetricHandler}'s static initializer
	 * but the {@link io.prometheus.metrics.core.metrics.Info} instance is intentionally not
	 * exposed as a field (Prometheus owns the only live reference). The reflection-based
	 * scan below cannot see it, so it is added explicitly to the expected set.
	 */
	private static final String BUILD_INFO_METRIC_NAME = "evitadb_build_info";

	@Test
	void shouldDeclareEveryStaticallyRegisteredMetricInStaticMetricsList() throws IllegalAccessException {
		// trigger MetricHandler's static initializer so the eagerly registered metrics
		// (counters, probes, build-info) are present in the default registry
		assertNotNull(MetricHandler.HEALTH_PROBLEMS);

		final Set<String> declaredNames = JfrDocumentation.STATIC_METRICS.stream()
			.map(JfrDocumentation.Metric::name)
			.collect(Collectors.toCollection(TreeSet::new));

		// enumerate every public static Prometheus Metric field declared in MetricHandler.
		// scrape() can't be used here because Counter/Gauge metrics with declared label
		// dimensions but no observed values yield empty data points and are filtered
		// out of the snapshot list — we'd see only the build-info Info metric. Both
		// `getName()` and `getPrometheusName()` return the base name; the Prometheus
		// exposition format only re-applies the `_total` / `_info` suffix at serialization
		// time, so we restore the suffix manually here to align with the names used in
		// {@link JfrDocumentation#STATIC_METRICS}.
		final Set<String> registeredStaticNames = new TreeSet<>();
		registeredStaticNames.add(BUILD_INFO_METRIC_NAME);
		for (final Field field : MetricHandler.class.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers()) && Metric.class.isAssignableFrom(field.getType())) {
				field.setAccessible(true);
				final Metric metric = (Metric) field.get(null);
				final String baseName = metric.collect().getMetadata().getPrometheusName();
				final String suffix = metric instanceof Counter ? "_total"
					: metric instanceof Info ? "_info" : "";
				registeredStaticNames.add(baseName + suffix);
			}
		}

		assertEquals(
			declaredNames,
			registeredStaticNames,
			"STATIC_METRICS in JfrDocumentation must list exactly the metrics that " +
				"MetricHandler registers eagerly. Update STATIC_METRICS (and the labels in " +
				"STATIC_METRIC_LABELS) when adding or removing a static metric."
		);
	}

}
