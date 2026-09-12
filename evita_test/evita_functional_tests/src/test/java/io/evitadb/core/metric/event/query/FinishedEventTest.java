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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.OBSERVABILITY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

	@Nested
	@DisplayName("Lazy fetch statistics resolution")
	class LazyStatisticsResolutionTest {

		/**
		 * Counts how many times the supplier was consulted and always answers with `answer`.
		 *
		 * @param invocations counter incremented on every resolution
		 * @param answer      value the supplier resolves to
		 * @return the counting supplier
		 */
		@Nonnull
		private IntSupplier countingSupplier(@Nonnull AtomicInteger invocations, int answer) {
			return () -> {
				invocations.incrementAndGet();
				return answer;
			};
		}

		@Test
		@DisplayName("Should resolve the two fetch aggregates only for an event that will be written")
		void shouldResolveFetchStatisticsOnlyWhenEventWillBeCommitted() {
			// resolving them walks the reference graph of every returned entity, so an event nobody is subscribed
			// to must not pay for it - the expectation is expressed against the event's own answer rather than
			// hard-coded, because whether a recording is running is a property of the fork, not of the code
			final AtomicInteger fetchedResolutions = new AtomicInteger();
			final AtomicInteger sizeResolutions = new AtomicInteger();
			final FinishedEvent event = new FinishedEvent("testCatalog", "Product", null);

			event.startExecuting()
				.finish(
					false, 10, 5, 7,
					countingSupplier(fetchedResolutions, 3),
					countingSupplier(sizeResolutions, 300),
					11L, 13L
				);

			final int expectedResolutions = event.shouldCommit() ? 1 : 0;
			assertEquals(expectedResolutions, fetchedResolutions.get());
			assertEquals(expectedResolutions, sizeResolutions.get());
			assertEquals(event.shouldCommit() ? 3 : 0, event.getFetched());
			assertEquals(event.shouldCommit() ? 300 : 0, event.getFetchedSizeBytes());
			// every sibling aggregate is assigned unconditionally, so those are readable either way
			assertEquals(10, event.getScanned());
			assertEquals(5, event.getReturned());
			assertEquals(7, event.getFound());
		}

		@Test
		@DisplayName("Should never consult a fetch statistics supplier twice")
		void shouldResolveFetchStatisticsAtMostOnce() {
			// the suppliers resolve a chain of deferred decorators, and a second resolution there is exactly the
			// double-count the deferral is exposed to
			final AtomicInteger fetchedResolutions = new AtomicInteger();
			final AtomicInteger sizeResolutions = new AtomicInteger();
			final FinishedEvent event = new FinishedEvent("testCatalog", "Product", null);

			event.startExecuting()
				.finish(
					true, 10, 5, 7,
					countingSupplier(fetchedResolutions, 3),
					countingSupplier(sizeResolutions, 300),
					11L, 13L
				);

			assertTrue(fetchedResolutions.get() <= 1);
			assertTrue(sizeResolutions.get() <= 1);
		}
	}

}
