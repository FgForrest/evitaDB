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

package io.evitadb.externalApi.observability.metric;

import io.evitadb.utils.VersionUtils;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.InfoSnapshot;
import io.prometheus.metrics.model.snapshots.InfoSnapshot.InfoDataPointSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.OBSERVABILITY;
import static io.evitadb.test.TestTags.OBSERVABILITY_API;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link MetricHandler} exposes the static `evitadb_build_info` Prometheus
 * `info` metric with the expected labels — version, abbreviated commit hash and JVM
 * version. The labels are populated from {@link VersionUtils} and `java.version`, so the
 * concrete values depend on the build environment; the test asserts only that the metric
 * is registered, has all three labels and that none of them is null.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("MetricHandler build-info metric contract")
@Tag(OBSERVABILITY_API)
@Tag(OBSERVABILITY)
class MetricHandlerBuildInfoTest {

	@Test
	void shouldExposeBuildInfoMetricWithVersionCommitAndJavaVersionLabels() {
		// Touch any static field on MetricHandler so the class' static initializer runs and
		// registers `evitadb_build_info` with the default Prometheus registry. We pick
		// HEALTH_PROBLEMS arbitrarily — the build-info metric is intentionally not exposed as
		// a field (Prometheus owns the only live reference).
		assertNotNull(MetricHandler.HEALTH_PROBLEMS);

		final InfoDataPointSnapshot dataPoint = findBuildInfoDataPoint();
		assertNotNull(dataPoint, "evitadb_build_info metric is not registered with the default Prometheus registry");

		final Labels labels = dataPoint.getLabels();
		assertEquals(3, labels.size(), "evitadb_build_info should expose exactly three labels");
		assertTrue(labels.contains("version"), "evitadb_build_info is missing the `version` label");
		assertTrue(labels.contains("commit"), "evitadb_build_info is missing the `commit` label");
		assertTrue(labels.contains("java_version"), "evitadb_build_info is missing the `java_version` label");

		// Values are environment-dependent (no shaded jar in tests), so we only check non-null.
		assertNotNull(labels.get("version"));
		assertNotNull(labels.get("commit"));
		assertNotNull(labels.get("java_version"));
	}

	/**
	 * Walks the default registry, locates the `evitadb_build_info` snapshot and returns
	 * its first (and only) data point.
	 *
	 * @return the data point or `null` when the metric has not been registered
	 */
	private static InfoDataPointSnapshot findBuildInfoDataPoint() {
		for (MetricSnapshot snapshot : PrometheusRegistry.defaultRegistry.scrape()) {
			if (snapshot instanceof final InfoSnapshot infoSnapshot
				&& "evitadb_build".equals(infoSnapshot.getMetadata().getName())) {
				return infoSnapshot.getDataPoints().isEmpty()
					? null
					: infoSnapshot.getDataPoints().get(0);
			}
		}
		return null;
	}

}
