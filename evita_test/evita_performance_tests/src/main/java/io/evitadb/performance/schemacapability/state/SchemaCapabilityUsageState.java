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

package io.evitadb.performance.schemacapability.state;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.core.Evita;
import lombok.Getter;
import org.apache.commons.io.FileUtils;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JMH fixture measuring the cost the schema-capability usage counters add to the query path - deliberately written
 * against the **public query API only**, so the very same two source files compile and run unmodified on a commit
 * that predates the counters. That is what makes it a before/after gate: build the uber-jar once on the baseline
 * commit and once on the instrumented branch, run both, and any difference between the two scores is the
 * instrumentation's price, not a benchmark artifact.
 *
 * The fixture boots an embedded {@link Evita} with the **query cache disabled**, because a cached query would skip
 * the planning pass where the requested-capability recording lives - a cache-hit-heavy run would measure how often
 * the cache hits rather than what the recording costs. Every measured invocation therefore plans its query in full.
 *
 * The dataset is small on purpose: {@value #PRODUCT_COUNT} products with one unique, one filterable and one sortable
 * attribute. A small dataset keeps the physical work per query low, which makes the fixed per-query recording cost
 * the *largest possible fraction* of the measured time - the most sensitive configuration the gate can have. On a
 * production-sized catalog the same absolute overhead would be a smaller fraction, never a larger one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@State(Scope.Benchmark)
public class SchemaCapabilityUsageState {

	/**
	 * Name of the benchmarked catalog.
	 */
	public static final String CATALOG_NAME = "schemaCapabilityUsage";
	/**
	 * Name of the single benchmarked entity collection.
	 */
	public static final String PRODUCT = "Product";
	/**
	 * Unique attribute every thread of the adversarial case filters by - one attribute, one shared counter holder.
	 */
	public static final String ATTRIBUTE_CODE = "code";
	/**
	 * Filterable attribute the representative case filters by.
	 */
	public static final String ATTRIBUTE_QUANTITY = "quantity";
	/**
	 * Sortable attribute the representative case orders by.
	 */
	public static final String ATTRIBUTE_NAME = "name";
	/**
	 * Number of products inserted into the collection.
	 */
	public static final int PRODUCT_COUNT = 10_000;
	/**
	 * Quantity values cycle through `0..RANGE-1`, so a ten-wide between-filter matches ~100 products.
	 */
	public static final int QUANTITY_RANGE = 1_000;

	/**
	 * Storage root owned exclusively by this trial, deleted in {@link #tearDown()}.
	 */
	private Path storageDirectory;
	/**
	 * The embedded instance under measurement.
	 */
	@Getter private Evita evita;
	/**
	 * The unique codes of all inserted products, precomputed so a measured invocation picks one by index instead of
	 * formatting a fresh string - the allocation would be identical in both builds, but there is no reason to pay it.
	 */
	@Getter private String[] codes;

	/**
	 * Boots the instance, defines the schema, inserts the dataset and switches the catalog to the transactional
	 * (`ALIVE`) state queries are served from in production.
	 *
	 * @throws IOException when the storage directory cannot be created
	 */
	@Setup(Level.Trial)
	public void setUp() throws IOException {
		this.storageDirectory = Files.createTempDirectory("evita-schema-capability-benchmark");
		this.evita = new Evita(
			EvitaConfiguration.builder()
				.server(
					ServerOptions.builder()
						.queryTimeoutInMilliseconds(60_000)
						.transactionTimeoutInMilliseconds(60_000)
						.closeSessionsAfterSecondsOfInactivity(Integer.MAX_VALUE)
						.build()
				)
				.storage(
					StorageOptions.builder()
						.storageDirectory(this.storageDirectory)
						.build()
				)
				// disabled so every measured query goes through planning - the pass the recording lives in
				.cache(CacheOptions.builder().enabled(false).build())
				.build()
		);
		this.evita.defineCatalog(CATALOG_NAME);

		this.codes = new String[PRODUCT_COUNT];
		for (int i = 0; i < PRODUCT_COUNT; i++) {
			this.codes[i] = String.format("code-%05d", i);
		}

		this.evita.updateCatalog(
			CATALOG_NAME,
			session -> {
				session.defineEntitySchema(PRODUCT)
					.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.unique())
					.withAttribute(ATTRIBUTE_QUANTITY, Integer.class, whichIs -> whichIs.filterable())
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.sortable())
					.updateVia(session);
				for (int i = 0; i < PRODUCT_COUNT; i++) {
					session.createNewEntity(PRODUCT, i + 1)
						.setAttribute(ATTRIBUTE_CODE, this.codes[i])
						.setAttribute(ATTRIBUTE_QUANTITY, i % QUANTITY_RANGE)
						// the multiplier scrambles insertion order so the sort has genuine work to do
						.setAttribute(ATTRIBUTE_NAME, "name-" + ((i * 2_654_435_761L) & 0xFFFFFF))
						.upsertVia(session);
				}
				session.goLiveAndClose();
			}
		);
	}

	/**
	 * Closes the instance and removes its storage directory.
	 *
	 * @throws IOException when the storage directory cannot be deleted
	 */
	@TearDown(Level.Trial)
	public void tearDown() throws IOException {
		if (this.evita != null) {
			this.evita.close();
		}
		if (this.storageDirectory != null) {
			FileUtils.deleteDirectory(this.storageDirectory.toFile());
		}
	}

	/**
	 * Per-thread companion of the shared fixture: one long-lived read-only session plus a cursor deciding which
	 * value the thread queries next. The cursor exists so consecutive invocations ask for *different* values -
	 * a thread hammering one constant would measure a degenerate access pattern no workload produces - while the
	 * session is reused because opening one per invocation would drown the recording cost in session bookkeeping.
	 */
	@State(Scope.Thread)
	public static class ThreadState {

		/**
		 * The thread's own read-only session, valid for the whole trial.
		 */
		@Getter private EvitaSessionContract session;
		/**
		 * Advances by one per invocation; seeded from the thread identity so the threads do not march in lockstep
		 * over the same values.
		 */
		private int cursor;

		/**
		 * Opens the session against the shared fixture's instance.
		 *
		 * @param benchmarkState the shared fixture owning the embedded instance
		 */
		@Setup(Level.Trial)
		public void setUp(@Nonnull SchemaCapabilityUsageState benchmarkState) {
			this.session = benchmarkState.getEvita().createReadOnlySession(CATALOG_NAME);
			this.cursor = System.identityHashCode(this) & 0x7FFFFFFF;
		}

		/**
		 * Closes the thread's session.
		 */
		@TearDown(Level.Trial)
		public void tearDown() {
			if (this.session != null) {
				this.session.close();
			}
		}

		/**
		 * @param benchmarkState the shared fixture holding the precomputed codes
		 * @return the unique code the thread queries in this invocation
		 */
		@Nonnull
		public String nextCode(@Nonnull SchemaCapabilityUsageState benchmarkState) {
			return benchmarkState.getCodes()[(this.cursor++ & 0x7FFFFFFF) % PRODUCT_COUNT];
		}

		/**
		 * @return lower bound of the ten-wide quantity window the thread queries in this invocation
		 */
		public int nextQuantityFloor() {
			return ((this.cursor++ & 0x7FFFFFFF) % (QUANTITY_RANGE / 10)) * 10;
		}

	}

}
