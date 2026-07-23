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

package io.evitadb.externalApi.observability.configuration;

import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

import static io.evitadb.test.TestTags.OBSERVABILITY;
import static io.evitadb.test.TestTags.OBSERVABILITY_API;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the `exportedQueryLabels` contract of {@link ObservabilityOptions}: the names are arbitrary and
 * operator-chosen (evitaDB reserves none), an unset configuration means nothing is exported (unlike
 * {@link ObservabilityOptions#getAllowedEvents()}), and two guards fail fast at startup - reserved high-cardinality
 * names, and two names that collapse onto the same Prometheus dimension.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ObservabilityOptions exportedQueryLabels contract")
@Tag(OBSERVABILITY_API)
@Tag(OBSERVABILITY)
class ObservabilityOptionsTest {

	@Nonnull
	private static ObservabilityOptions withExportedQueryLabels(@Nullable List<String> exportedQueryLabels) {
		return new ObservabilityOptions(
			null, "localhost:5555", null, null, null, null, null,
			null, exportedQueryLabels, null
		);
	}

	@Test
	@DisplayName("Should default exportedQueryLabels to null (nothing exported) when not configured")
	void shouldDefaultToNullWhenNotConfigured() {
		assertNull(new ObservabilityOptions().getExportedQueryLabels());
		assertNull(new ObservabilityOptions("localhost:5555").getExportedQueryLabels());
	}

	@Test
	@DisplayName("Should accept arbitrary, operator-chosen label names (including ones needing sanitization)")
	void shouldAcceptArbitraryLabelNames() {
		final List<String> labels = List.of("job_name", "rest_method", "tenant", "some-custom.name");
		assertEquals(labels, withExportedQueryLabels(labels).getExportedQueryLabels());
	}

	@Test
	@DisplayName("Should reject each reserved high-cardinality label name with a clear message")
	void shouldRejectReservedLabelNames() {
		for (final String reserved : List.of("trace-id", "client-id", "ip-address", "uri")) {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> withExportedQueryLabels(List.of("job_name", reserved))
			);
			assertTrue(exception.getMessage().contains(reserved), "message should mention `" + reserved + "`");
		}
	}

	@Test
	@DisplayName("Should reject two labels that collapse onto the same Prometheus dimension")
	void shouldRejectCollidingLabelNames() {
		final EvitaInvalidUsageException exception = assertThrows(
			EvitaInvalidUsageException.class,
			() -> withExportedQueryLabels(List.of("rest-method", "rest.method"))
		);
		assertTrue(exception.getMessage().contains("rest_method"), "message should mention the shared dimension");
	}

}
