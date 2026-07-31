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

package io.evitadb.spike;

import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;

import javax.annotation.Nonnull;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * End-to-end report of single-entity `ALIVE` write latency against a catalog carrying a large **localized sortable**
 * attribute — the shape in which sort-index first-touch cost is visible from outside the engine.
 *
 * It bulk-loads `N` distinct Czech titles in `WARM_UP`, goes live, then times individual single-entity upsert
 * transactions and reports the steady-state median. Unlike a JMH benchmark it deliberately measures the FIRST
 * operation of each transaction, which is where a per-transaction rebuild of a derived structure would surface;
 * JMH's steady-state averaging tends to hide exactly that.
 *
 * Run with `-Dreport.n=<distinct values>` (default 320,000) and `-Dreport.txs=<measured transactions>` (default 12).
 * Being a report rather than a benchmark, it prints to stdout and asserts nothing — the flat-scaling invariant it
 * exists to illustrate is guarded automatically by `SortIndexRankScalingTest` in the functional suite.
 *
 * The measured numbers, the conditions they were taken under and their interpretation live in
 * `documentation/performance/individual/SortIndexAliveWriteReport/README.md`. Re-run this report and update that
 * document whenever the sort index's first-touch path changes, so the two never drift apart.
 */
public class SortIndexAliveWriteReport implements EvitaTestSupport {
	private static final Locale CZECH = new Locale("cs");
	private static final String TEST_CATALOG_NAME = "sortIndexAliveWriteReport";
	private static final String ENTITY_PRODUCT = "Product";
	private static final String ATTRIBUTE_TITLE = "title";
	private static final String CZECH_ALPHABET = "aábcčdďeéěfghiíjklmnňoóprřsštťuúůvyýzž";

	/**
	 * Builds a deterministic pseudo-random Czech title for entity `i`, so every run indexes the identical corpus
	 * and pays the identical collation cost.
	 *
	 * @param i the entity primary key
	 * @return a distinct Czech-alphabet title
	 */
	@Nonnull
	private static String titleFor(int i) {
		final Random rnd = new Random(i * 0x9E3779B97F4A7C15L);
		final int length = 8 + rnd.nextInt(12);
		final StringBuilder sb = new StringBuilder(length + 12);
		for (int j = 0; j < length; j++) {
			if (j > 0 && j % 6 == 0) {
				sb.append(' ');
			}
			sb.append(CZECH_ALPHABET.charAt(rnd.nextInt(CZECH_ALPHABET.length())));
		}
		return sb.append(' ').append(i).toString();
	}

	public static void main(@Nonnull String[] args) {
		new SortIndexAliveWriteReport().run(
			Integer.getInteger("report.n", 320_000),
			Integer.getInteger("report.txs", 12)
		);
	}

	/**
	 * Executes the report: bulk import, go live, then timed single-entity transactions.
	 *
	 * @param n           number of distinct localized titles to bulk-load
	 * @param measuredTxs number of single-entity transactions to time
	 */
	private void run(int n, int measuredTxs) {
		final TestPaths paths = createTestPaths("SortIndexAliveWriteReport");
		final Evita evita = new Evita(
			newTestEvitaConfigurationBuilder(paths)
				.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
				.cache(CacheOptions.builder().enabled(false).build())
				.build()
		);
		try {
			evita.defineCatalog(TEST_CATALOG_NAME);

			final long warmUpStart = System.nanoTime();
			evita.updateCatalog(
				TEST_CATALOG_NAME,
				session -> {
					session.defineEntitySchema(ENTITY_PRODUCT)
						.withAttribute(
							ATTRIBUTE_TITLE, String.class,
							whichIs -> whichIs.filterable().sortable().localized()
						)
						.updateVia(session);
					for (int i = 1; i <= n; i++) {
						session.createNewEntity(ENTITY_PRODUCT, i)
							.setAttribute(ATTRIBUTE_TITLE, CZECH, titleFor(i))
							.upsertVia(session);
					}
				}
			);
			System.out.printf("warm-up insert of %d entities: %.1f s%n", n, (System.nanoTime() - warmUpStart) / 1e9);

			final long goLiveStart = System.nanoTime();
			evita.updateCatalog(TEST_CATALOG_NAME, session -> {
				session.goLiveAndClose();
			});
			System.out.printf("go-live: %.1f s%n", (System.nanoTime() - goLiveStart) / 1e9);

			final long[] txNanos = new long[measuredTxs];
			for (int i = 0; i < measuredTxs; i++) {
				final int pk = n + 1 + i;
				final long start = System.nanoTime();
				evita.updateCatalog(TEST_CATALOG_NAME, session -> {
					session.createNewEntity(ENTITY_PRODUCT, pk)
						.setAttribute(ATTRIBUTE_TITLE, CZECH, titleFor(pk))
						.upsertVia(session);
				});
				txNanos[i] = System.nanoTime() - start;
				System.out.printf("alive tx %02d: %.1f ms%n", i + 1, txNanos[i] / 1e6);
			}

			// the first transactions pay JIT compilation - report the median of the second half only
			final long[] tail = Arrays.copyOfRange(txNanos, measuredTxs / 2, measuredTxs);
			Arrays.sort(tail);
			System.out.printf(
				"STEADY-STATE MEDIAN: %.1f ms (n=%d, tail of %d transactions)%n",
				tail[tail.length / 2] / 1e6, n, tail.length
			);
		} finally {
			evita.close();
		}
	}
}
