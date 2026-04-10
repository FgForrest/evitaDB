/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.configuration;

import io.evitadb.api.configuration.metric.LoggedMetric;
import io.evitadb.api.configuration.metric.MetricType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LoggedMetric} record.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("LoggedMetric")
class LoggedMetricTest {

	@Test
	@DisplayName("should construct with all fields")
	void shouldConstructWithAllFields() {
		final LoggedMetric metric = new LoggedMetric(
			"test_metric",
			"A test metric",
			MetricType.COUNTER,
			null,
			"label1", "label2"
		);

		assertEquals("test_metric", metric.name());
		assertEquals("A test metric", metric.helpMessage());
		assertEquals(MetricType.COUNTER, metric.type());
		assertNull(metric.histogramSettings());
		assertArrayEquals(
			new String[]{"label1", "label2"},
			metric.labels()
		);
	}

	@Test
	@DisplayName("should construct with no labels")
	void shouldConstructWithNoLabels() {
		final LoggedMetric metric = new LoggedMetric(
			"test_gauge",
			"A gauge metric",
			MetricType.GAUGE,
			null
		);

		assertEquals("test_gauge", metric.name());
		assertEquals(MetricType.GAUGE, metric.type());
		assertEquals(0, metric.labels().length);
	}

	@Test
	@DisplayName("should support null histogram settings")
	void shouldSupportNullHistogramSettings() {
		final LoggedMetric metric = new LoggedMetric(
			"test_summary",
			"A summary metric",
			MetricType.SUMMARY,
			null
		);

		assertNull(metric.histogramSettings());
	}
}
