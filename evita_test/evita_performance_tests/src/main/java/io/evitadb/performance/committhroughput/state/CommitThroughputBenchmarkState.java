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

package io.evitadb.performance.committhroughput.state;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.performance.artificial.ArtificialBenchmarkState;
import io.evitadb.performance.committhroughput.CommitThroughputBenchmark;
import io.evitadb.performance.setup.EvitaCatalogSetup;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import javax.annotation.Nonnull;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared state for {@link CommitThroughputBenchmark}: a transactional (ALIVE) catalog seeded with
 * a modest set of products, against which the benchmark commits many small transactions.
 *
 * This state exists because the sibling `ArtificialTransactionalWriteBenchmarkState` cannot measure
 * commit throughput: it caches a single read-write session for the whole iteration and closes it only
 * in tear-down, so an entire iteration is **one** transaction and the commit pipeline is exercised
 * exactly once. Here every benchmark invocation opens, writes and commits its own transaction, which
 * is what puts load on WAL appending and trunk incorporation.
 *
 * The database is built once per **trial** rather than per iteration - seeding is far more expensive
 * than a measurement iteration, and nothing a commit does invalidates the seed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@State(Scope.Benchmark)
public class CommitThroughputBenchmarkState extends ArtificialBenchmarkState
	implements EvitaCatalogSetup {
	/**
	 * Count of products present in the database before the measured phase starts. Deliberately smaller
	 * than the sibling artificial benchmarks use - the subject here is commit cost, not index size, and
	 * a smaller seed keeps trial setup short enough to sweep a configuration axis.
	 */
	public static final int INITIAL_COUNT_OF_PRODUCTS = 2_000;

	/**
	 * Counts committed transactions across all worker threads, reported in tear-down as a sanity check
	 * that the measured phase really did commit (rather than, say, failing every transaction silently).
	 */
	private final AtomicInteger committedTransactions = new AtomicInteger(0);

	/**
	 * Seeds the catalog and switches it to the transactional (ALIVE) state.
	 *
	 * Mirrors the reference entity set of the artificial benchmarks - brands, categories, price lists
	 * and stores - so generated products have something to reference and the produced mutations are
	 * representative rather than degenerate.
	 */
	@Setup(Level.Trial)
	public void setUp() {
		this.dataGenerator.clear();
		this.generatedEntities.clear();
		this.committedTransactions.set(0);
		final String catalogName = getCatalogName();
		this.evita = createEmptyEvitaInstance(catalogName);
		this.evita.updateCatalog(
			catalogName,
			session -> {
				this.dataGenerator.generateEntities(
						this.dataGenerator.getSampleBrandSchema(session),
						this.randomEntityPicker,
						SEED
					)
					.limit(5)
					.forEach(it -> createEntity(session, this.generatedEntities, it));

				this.dataGenerator.generateEntities(
						this.dataGenerator.getSampleCategorySchema(session),
						this.randomEntityPicker,
						SEED
					)
					.limit(10)
					.forEach(it -> createEntity(session, this.generatedEntities, it));

				this.dataGenerator.generateEntities(
						this.dataGenerator.getSamplePriceListSchema(session),
						this.randomEntityPicker,
						SEED
					)
					.limit(4)
					.forEach(it -> createEntity(session, this.generatedEntities, it));

				this.dataGenerator.generateEntities(
						this.dataGenerator.getSampleStoreSchema(session),
						this.randomEntityPicker,
						SEED
					)
					.limit(12)
					.forEach(it -> createEntity(session, this.generatedEntities, it));

				this.productSchema = this.dataGenerator.getSampleProductSchema(session);
				this.dataGenerator.generateEntities(
						this.productSchema,
						this.randomEntityPicker,
						SEED
					)
					.limit(INITIAL_COUNT_OF_PRODUCTS)
					.forEach(session::upsertEntity);

				// from here on every write goes through the transactional commit pipeline
				session.goLiveAndClose();
			}
		);
		this.productIterator = getProductStream().iterator();
	}

	/**
	 * Hands out the next generated product.
	 *
	 * The underlying generator is an infinite sequence, so every caller receives a distinct entity and
	 * concurrent workers never contend for the same primary key - upserting a shared entity from several
	 * threads would measure conflict resolution rather than commit cost. Access is synchronized because
	 * the iterator is not thread safe; generating an entity is cheap next to the device sync a commit
	 * pays, but at high worker counts this lock is the first thing to suspect if throughput plateaus.
	 *
	 * @return a freshly generated product builder, never shared with another invocation
	 */
	@Nonnull
	public synchronized EntityBuilder nextProduct() {
		return this.productIterator.next();
	}

	/**
	 * Records that a transaction was committed.
	 */
	public void recordCommit() {
		this.committedTransactions.incrementAndGet();
	}

	/**
	 * We need writable sessions here.
	 */
	@Override
	public EvitaSessionContract getSession() {
		return getSession(() -> this.evita.createReadWriteSession(getCatalogName()));
	}

	/**
	 * Returns name of the test catalog.
	 */
	@Override
	protected String getCatalogName() {
		return TEST_CATALOG + "_commitThroughput";
	}

	/**
	 * Publicly reachable alias of {@link #getCatalogName()} - the inherited accessor is protected and the
	 * benchmark class lives in a different package.
	 *
	 * @return name of the catalog the benchmark commits into
	 */
	@Nonnull
	public String getCatalogNameForBenchmark() {
		return getCatalogName();
	}

	/**
	 * Shuts the instance down and reports how many transactions the trial actually committed.
	 */
	@TearDown(Level.Trial)
	public void closeEvita() {
		this.evita.close();
		System.out.println("\nSeeded with " + INITIAL_COUNT_OF_PRODUCTS + " products.");
		System.out.println("Committed " + this.committedTransactions.get() + " transactions in trial.");
	}

}
