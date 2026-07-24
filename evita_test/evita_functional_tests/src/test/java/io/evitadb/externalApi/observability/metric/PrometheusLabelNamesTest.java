/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.evitadb.test.TestTags.OBSERVABILITY;
import static io.evitadb.test.TestTags.OBSERVABILITY_API;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link PrometheusLabelNames#sanitize(String)} turns arbitrary, operator-chosen query label names into
 * legal Prometheus dimension names (`[a-zA-Z_][a-zA-Z0-9_]*`). This is the shared rule the configuration layer uses to
 * detect two labels colliding on the same dimension, and the metric handler uses to name the dimension it registers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("PrometheusLabelNames sanitization")
@Tag(OBSERVABILITY_API)
@Tag(OBSERVABILITY)
class PrometheusLabelNamesTest {

	@Test
	@DisplayName("Should leave already-legal names unchanged")
	void shouldLeaveLegalNamesUnchanged() {
		assertEquals("job_name", PrometheusLabelNames.sanitize("job_name"));
		assertEquals("rest_method", PrometheusLabelNames.sanitize("rest_method"));
		assertEquals("_private", PrometheusLabelNames.sanitize("_private"));
		assertEquals("Tenant2", PrometheusLabelNames.sanitize("Tenant2"));
	}

	@Test
	@DisplayName("Should replace illegal characters with underscores")
	void shouldReplaceIllegalCharacters() {
		assertEquals("rest_method", PrometheusLabelNames.sanitize("rest-method"));
		assertEquals("a_b_c", PrometheusLabelNames.sanitize("a.b-c"));
		assertEquals("source_query", PrometheusLabelNames.sanitize("source-query"));
	}

	@Test
	@DisplayName("Should prefix names that would otherwise start with a digit")
	void shouldPrefixLeadingDigit() {
		assertEquals("_1st", PrometheusLabelNames.sanitize("1st"));
		assertEquals("_2_tenants", PrometheusLabelNames.sanitize("2-tenants"));
	}

	@Test
	@DisplayName("Should turn an empty name into a single underscore")
	void shouldHandleEmptyName() {
		assertEquals("_", PrometheusLabelNames.sanitize(""));
	}

	@Test
	@DisplayName("Distinct raw names may collapse onto the same sanitized dimension")
	void distinctNamesMayCollide() {
		assertEquals(
			PrometheusLabelNames.sanitize("rest.method"),
			PrometheusLabelNames.sanitize("rest-method")
		);
	}

	@Test
	@DisplayName("assignDimensions should map each label to its sanitized dimension, preserving order")
	void assignDimensionsShouldMapAndPreserveOrder() {
		final Map<String, String> dimensions = PrometheusLabelNames.assignDimensions(
			List.of("job_name", "rest-method", "tenant"), Set.of()
		);
		assertEquals(Map.of("job_name", "job_name", "rest-method", "rest_method", "tenant", "tenant"), dimensions);
		assertEquals(List.of("job_name", "rest-method", "tenant"), List.copyOf(dimensions.keySet()));
	}

	@Test
	@DisplayName("assignDimensions should reject two labels that collapse onto the same dimension")
	void assignDimensionsShouldRejectMutualCollision() {
		final EvitaInvalidUsageException exception = assertThrows(
			EvitaInvalidUsageException.class,
			() -> PrometheusLabelNames.assignDimensions(List.of("rest-method", "rest.method"), Set.of())
		);
		assertTrue(exception.getMessage().contains("rest_method"), "should mention the shared dimension");
	}

	@Test
	@DisplayName("assignDimensions should reject a label that collides with a reserved built-in dimension")
	void assignDimensionsShouldRejectReservedCollision() {
		final EvitaInvalidUsageException exception = assertThrows(
			EvitaInvalidUsageException.class,
			() -> PrometheusLabelNames.assignDimensions(List.of("prefetched"), Set.of("entityType", "prefetched"))
		);
		assertTrue(exception.getMessage().contains("prefetched"), "should mention the clashing dimension");
		assertTrue(exception.getMessage().contains("built-in"), "should explain it is a built-in dimension");
	}

	@Test
	@DisplayName("assignDimensions should accept labels that don't collide with reserved dimensions")
	void assignDimensionsShouldAcceptNonCollidingWithReserved() {
		final Map<String, String> dimensions = PrometheusLabelNames.assignDimensions(
			List.of("job_name", "tenant"), Set.of("entityType", "prefetched", "catalogName")
		);
		assertEquals(Map.of("job_name", "job_name", "tenant", "tenant"), dimensions);
	}

}
