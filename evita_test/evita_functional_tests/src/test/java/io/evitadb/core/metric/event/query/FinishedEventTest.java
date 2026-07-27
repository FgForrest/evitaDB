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

package io.evitadb.core.metric.event.query;

import io.evitadb.api.query.head.Label;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.OBSERVABILITY;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that {@link FinishedEvent} encodes the query's labels into the comma-delimited `name=value` bag that the
 * observability layer later reads to surface operator-configured query labels as Prometheus dimensions (via
 * `QueryLabelBag`). This is the producer side of that format contract - a regression in the encoding here would
 * silently break query-label export downstream.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("FinishedEvent label bag encoding")
@Tag(ENGINE)
@Tag(OBSERVABILITY)
class FinishedEventTest {

	@Test
	@DisplayName("Should encode labels as a comma-delimited name=value bag, in order")
	void shouldEncodeLabelsAsBag() {
		final FinishedEvent event = new FinishedEvent(
			"testCatalog",
			"Product",
			new Label[]{
				new Label("job_name", "feed-job"),
				new Label("rest_method", "GET /products"),
				new Label("tenant", "acme")
			}
		);
		assertEquals("job_name=feed-job,rest_method=GET /products,tenant=acme", event.getLabels());
	}

	@Test
	@DisplayName("Should produce an empty bag when the query carries no labels")
	void shouldProduceEmptyBagForNullLabels() {
		assertEquals("", new FinishedEvent("testCatalog", "Product", null).getLabels());
	}

}
