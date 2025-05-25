/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.performance.check;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.Input;
import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.ExtraResultRequireConstraint;
import io.evitadb.api.query.visitor.FinderVisitor;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.store.query.QuerySerializationKryoConfigurer;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * This tool allows to iterate over all synthetic queries over real dataset and report count of returned records.
 * Execute the tool with following parameters:
 *
 * 1. catalog name
 * 2. absolute path to directory with queries (containing `queries.kryo` file)
 * 3. number of threads which will emit queries to the database
 * 4. number of queries that will be fetched from underlying data store at maximum
 *
 * The tool will load all synthetic queries and execute them against the target database. It records queries per second
 * digestion rate, total records matching, entity bodies and primary keys really returned.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class SanityChecker implements EvitaTestSupport {
	private static final int PRELOADED_QUERY_COUNT = 100_000;
	private final Object monitor = new Object();
	private int queryLimit;
	private int preloadedQueryCount;
	private Kryo kryo;
	private Input input;
	private boolean finished;
	private int queriesFetched;

	public static void main(String[] args) throws FileNotFoundException {
		new SanityChecker().execute(args);
	}

	public void execute(String[] args) throws FileNotFoundException {
		Assert.isTrue(args.length >= 2, "Expected exactly two arguments! First: absolute path to data directory, second: absolute path to query directory");
		final String catalogName = args[0];
		final Path queryDirectory = getAndVerifyDirectory(args[1]);
		final int threadCount = args.length > 2 ? Integer.parseInt(args[2]) : Runtime.getRuntime().availableProcessors();
		this.queryLimit = args.length > 3 ? Integer.parseInt(args[3]) : Integer.MAX_VALUE;
		this.preloadedQueryCount = Math.min(this.queryLimit / threadCount, PRELOADED_QUERY_COUNT);
		this.input = new ByteBufferInput(new FileInputStream(queryDirectory.resolve("queries.kryo").toFile()), 8_192);
		this.kryo = KryoFactory.createKryo(QuerySerializationKryoConfigurer.INSTANCE);

		System.out.println("Loading evita database ...");
		try (final Evita evitaInstance = createEvitaInstance(catalogName)) {
			System.out.println("Evita database loaded. Starting workers with parallelization " + threadCount + " ...");

			final CountDownLatch countDownLatch = new CountDownLatch(threadCount);
			final Statistics overallStatistics = new Statistics(threadCount);

			Stream.generate(() -> new Worker(this::fetchNewQueries, countDownLatch, catalogName, evitaInstance, overallStatistics))
				.limit(threadCount)
				.forEach(it -> new Thread(it).start());


			final Thread statusWriter = new Thread(new StatusWriter(overallStatistics));
			statusWriter.start();

			countDownLatch.await();
			statusWriter.interrupt();

			System.out.println("-".repeat(80));
			System.out.println(" T O T A L   R E S U L T S :");
			System.out.println("-".repeat(80));
			System.out.println(overallStatistics);
			System.exit(0);
		} catch (Exception ex) {
			ex.printStackTrace();
			System.exit(1);
		}
	}

	/**
	 * Creates new Evita instance for specific implementation based on existing data from previous run.
	 */
	protected Evita createEvitaInstance(@Nonnull String catalogName) {
		final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.storage(
					StorageOptions.builder()
						.storageDirectory(getTestDirectory())
						.build()
				)
				.cache(
					CacheOptions.builder()
						.enabled(true)
						.reevaluateEachSeconds(60)
						.anteroomRecordCount(10_000)
						.minimalComplexityThreshold(50_000)
						.minimalUsageThreshold(5)
						.cacheSizeInBytes(1_000_000_000L)
						.build()
				)
				.build()
		);
		evita.defineCatalog(catalogName);
		return evita;
	}

	@RequiredArgsConstructor
	private static class StatusWriter implements Runnable {
		private final Statistics overallStatistics;

		@Override
		public void run() {
			try {
				while (true) {
					synchronized (this) {
						System.out.println(this.overallStatistics.toString());
						Thread.sleep(10_000);
					}
				}
			} catch (InterruptedException e) {
				// finish
			}
		}

	}

	@RequiredArgsConstructor
	private static class Worker
		implements Runnable {
		private final Supplier<Deque<Query>> querySupplier;
		private final CountDownLatch countDownLatch;
		private final String catalogName;
		private final Evita evitaInstance;
		private final Statistics overallStatistics;
		private Deque<Query> preloadedQueries;

		@Override
		public void run() {
			try {
				this.preloadedQueries = this.querySupplier.get();
				while (!this.preloadedQueries.isEmpty()) {
					this.evitaInstance.queryCatalog(
						this.catalogName, session -> {
							while (!this.preloadedQueries.isEmpty()) {
								final Query theQuery = this.preloadedQueries.removeFirst();
								final boolean pkOnly = FinderVisitor.findConstraints(theQuery.getRequire(), EntityContentRequire.class::isInstance, ExtraResultRequireConstraint.class::isInstance).isEmpty();
								final long start = System.nanoTime();
								if (pkOnly) {
									final EvitaResponse<EntityReference> response = session.query(theQuery, EntityReference.class);
									this.overallStatistics.recordResponseEntityReference(System.nanoTime() - start, response);
								} else {
									final EvitaResponse<SealedEntity> response = session.query(theQuery, SealedEntity.class);
									this.overallStatistics.recordResponseSealedEntity(System.nanoTime() - start, response);
								}
							}
							return null;
						}
					);
					this.preloadedQueries = this.querySupplier.get();
				}
			} catch (Exception ex) {
				ex.printStackTrace();
			} finally {
				this.countDownLatch.countDown();
			}
		}

	}

	@Nonnull
	private static Path getAndVerifyDirectory(String absolutePath) {
		final Path directoryPath = Path.of(absolutePath);
		final File file = directoryPath.toFile();
		Assert.isTrue(file.exists(), "The directory " + absolutePath + " doesn't exist!");
		Assert.isTrue(file.isDirectory(), "The directory " + absolutePath + " doesn't exist!");
		return directoryPath;
	}

	private Deque<Query> fetchNewQueries() {
		synchronized (this.monitor) {
			final LinkedList<Query> fetchedQueries = new LinkedList<>();
			if (!this.finished) {
				for (int i = 0; i < this.preloadedQueryCount && this.queriesFetched++ < this.queryLimit; i++) {
					if (!this.input.canReadInt()) {
						this.input.close();
						this.finished = true;
						break;
					}
					fetchedQueries.add(this.kryo.readObject(this.input, Query.class));
				}
			}
			return fetchedQueries;
		}
	}

}
