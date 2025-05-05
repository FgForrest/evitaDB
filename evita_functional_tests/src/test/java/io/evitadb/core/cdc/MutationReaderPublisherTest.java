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

package io.evitadb.core.cdc;


import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.core.async.Scheduler;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.offsetIndex.io.OffHeapMemoryManager;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.wal.CatalogWriteAheadLog;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.utils.FileUtils;
import lombok.Getter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.evitadb.store.wal.CatalogWriteAheadLogIntegrationTest.writeWal;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * This test verifies contract of {@link MutationReaderPublisher} and its ability to publish events.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class MutationReaderPublisherTest {
	private static final Path WAL_DIRECTORY = Path.of(System.getProperty("java.io.tmpdir")).resolve("evita").resolve(MutationReaderPublisherTest.class.getSimpleName());
	private static final Pool<Kryo> CATALOG_KRYO_POOL = new Pool<>(false, false, 1) {
		@Override
		protected Kryo create() {
			return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		}
	};
	private static final Path ISOLATED_WAL_FILE_PATH = WAL_DIRECTORY.resolve("isolatedWal.tmp");
	private static final ObservableOutputKeeper OBSERVABLE_OUTPUT_KEEPER = new ObservableOutputKeeper(
		TEST_CATALOG,
		StorageOptions.builder()
			/* there are tests that rely on standard size of mutations on disk in this class */
			.compress(false)
			.build(),
		Mockito.mock(Scheduler.class)
	);
	private static final OffHeapMemoryManager OFF_HEAP_MEMORY_MANAGER = new OffHeapMemoryManager(TEST_CATALOG, 10_000_000, 128);
	private static final int[] TX_SIZES = new int[]{20, 30, 40, 50, 70, 90, 10};
	private static CatalogWriteAheadLog WAL;
	/**
	 * Single-threaded executor service used for running the publisher's tasks.
	 * Using a single thread ensures predictable execution order for tests.
	 */
	private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(1);

	@BeforeAll
	static void setUp() {
		// clear the WAL directory
		FileUtils.deleteDirectory(WAL_DIRECTORY);
		assertTrue(WAL_DIRECTORY.toFile().mkdirs());
		WAL = new CatalogWriteAheadLog(
			0L,
			TEST_CATALOG,
			WAL_DIRECTORY,
			CATALOG_KRYO_POOL,
			StorageOptions.builder()
				/* there are tests that rely on standard size of mutations on disk in this class */
				.compress(false)
				.build(),
			TransactionOptions.builder().walFileSizeBytes(Long.MAX_VALUE).build(),
			Mockito.mock(Scheduler.class),
			value -> {},
			firstActiveCatalogVersion -> {}
		);
		writeWal(
			OFF_HEAP_MEMORY_MANAGER,
			TX_SIZES,
			null,
			ISOLATED_WAL_FILE_PATH,
			OBSERVABLE_OUTPUT_KEEPER,
			WAL
		);
	}

	@AfterAll
	static void tearDown() throws IOException {
		OBSERVABLE_OUTPUT_KEEPER.close();
		WAL.close();
		EXECUTOR.shutdown();
		// clear the WAL directory
		FileUtils.deleteDirectory(WAL_DIRECTORY);
	}

	@Test
	void shouldPublishAllMutations() {
		final AtomicBoolean isCompleted = new AtomicBoolean(false);
		final int mutationCount = Arrays.stream(TX_SIZES).map(it -> it + 1).sum();
		final MockMutationSubscriber mockSubscriber = new MockMutationSubscriber(mutationCount);

		try (
			final MutationReaderPublisher<MockMutationSubscriber> publisher = new MutationReaderPublisher<>(
				EXECUTOR, mutationCount,
				0L, 0,
				() -> TX_SIZES.length,
				startVersion -> WAL.getCommittedMutationStream(startVersion),
				null,
				subscriber -> {
					assertSame(mockSubscriber, subscriber);
					isCompleted.set(true);
				}
			)
		) {
			publisher.subscribe(mockSubscriber);
			publisher.readAll();

			try {
				mockSubscriber.getFinished().get(1, TimeUnit.SECONDS);
			} catch (TimeoutException ex) {
				// timed out
			} catch (InterruptedException | ExecutionException e) {
				fail(e);
			}

			assertEquals(mutationCount, mockSubscriber.getCount());
			assertTrue(isCompleted.get());
			assertTrue(publisher.isClosed());
		}
	}

	@Test
	void shouldPublishAllMutationsSinceParticularVersion() {
		final AtomicBoolean isCompleted = new AtomicBoolean(false);
		final int mutationCount = Arrays.stream(TX_SIZES)
			.skip(2)
			.map(it -> it + 1)
			.sum() - 2;
		final MockMutationSubscriber mockSubscriber = new MockMutationSubscriber(mutationCount);

		try (
			final MutationReaderPublisher<MockMutationSubscriber> publisher = new MutationReaderPublisher<>(
				EXECUTOR, mutationCount,
				3L, 2,
				() -> TX_SIZES.length,
				startVersion -> WAL.getCommittedMutationStream(startVersion),
				null,
				subscriber -> {
					assertSame(mockSubscriber, subscriber);
					isCompleted.set(true);
				}
			)
		) {
			publisher.subscribe(mockSubscriber);
			publisher.readAll();

			try {
				mockSubscriber.getFinished().get(1, TimeUnit.SECONDS);
			} catch (TimeoutException ex) {
				// timed out
			} catch (InterruptedException | ExecutionException e) {
				fail(e);
			}

			assertEquals(mutationCount, mockSubscriber.getCount());
			assertTrue(isCompleted.get());
			assertTrue(publisher.isClosed());
		}
	}

	@Test
	void shouldPublishAllMutationsSinceParticularVersionUntilCurrentVersion() {
		final AtomicBoolean isCompleted = new AtomicBoolean(false);
		final int mutationCount = Arrays.stream(TX_SIZES)
			.skip(2)
			.map(it -> it + 1)
			.limit(2)
			.sum() - 2;
		final MockMutationSubscriber mockSubscriber = new MockMutationSubscriber(mutationCount);

		try (
			final MutationReaderPublisher<MockMutationSubscriber> publisher = new MutationReaderPublisher<>(
				EXECUTOR, mutationCount,
				3L, 2,
				() -> 4,
				startVersion -> WAL.getCommittedMutationStream(startVersion),
				null,
				subscriber -> {
					assertSame(mockSubscriber, subscriber);
					isCompleted.set(true);
				}
			)
		) {
			publisher.subscribe(mockSubscriber);
			publisher.readAll();

			try {
				mockSubscriber.getFinished().get(1, TimeUnit.SECONDS);
			} catch (TimeoutException ex) {
				// timed out
			} catch (InterruptedException | ExecutionException e) {
				fail(e);
			}

			assertEquals(mutationCount, mockSubscriber.getCount());
			assertTrue(isCompleted.get());
			assertTrue(publisher.isClosed());
		}
	}

	@Test
	void shouldReadAllDataOnMultipleRoundsIfSubscriberGetsSaturated() {
		final AtomicBoolean isCompleted = new AtomicBoolean(false);
		final int mutationCount = Arrays.stream(TX_SIZES)
			.map(it -> it + 1)
			.sum();

		final MockMutationSubscriber mockSubscriber = new MockMutationSubscriber(5, TimeUnit.MILLISECONDS, mutationCount);
		try (
			final MutationReaderPublisher<MockMutationSubscriber> publisher = new MutationReaderPublisher<>(
				EXECUTOR, 5,
				0L, 0,
				() -> TX_SIZES.length + 1,
				startVersion -> WAL.getCommittedMutationStream(startVersion),
				null,
				subscriber -> {
					assertSame(mockSubscriber, subscriber);
					isCompleted.set(true);
				}
			)
		) {
			publisher.subscribe(mockSubscriber);

			publisher.readAll();
			try {
				mockSubscriber.getFinished().get(1, TimeUnit.MINUTES);
			} catch (TimeoutException ex) {
				// timed out
			} catch (InterruptedException | ExecutionException e) {
				fail(e);
			}

			assertEquals(mutationCount, mockSubscriber.getCount());
			assertTrue(isCompleted.get());
			assertTrue(publisher.isClosed());
		}
	}

	@Test
	void shouldDiscardSubscribersOnSaturation() {
		// todo jno also verify that no other mutations are published to discarded subscribers
	}

	@Test
	void shouldConsumeNewVersionsAsTheyAreIntroduced() {

	}

	private static class MockMutationSubscriber implements Subscriber<Mutation> {
		private final long delay;
		private final TimeUnit delayTimeUnit;
		@Getter private final CompletableFuture<Void> finished = new CompletableFuture<>();
		private final int finishOnReachingCount;
		private Subscription subscription;
		private final LinkedHashSet<Mutation> mutations = new LinkedHashSet<>();

		public MockMutationSubscriber(int finishOnReachingCount) {
			this.delay = 0L;
			this.delayTimeUnit = TimeUnit.MILLISECONDS;
			this.finishOnReachingCount = finishOnReachingCount;
		}

		public MockMutationSubscriber(long delay, TimeUnit delayTimeUnit, int finishOnReachingCount) {
			this.delay = delay;
			this.delayTimeUnit = delayTimeUnit;
			this.finishOnReachingCount = finishOnReachingCount;
		}

		public long getCount() {
			return this.mutations.size();
		}

		@Override
		public void onSubscribe(Subscription subscription) {
			this.subscription = subscription;
			this.subscription.request(1);
		}

		@Override
		public void onNext(Mutation item) {
			if (!this.mutations.add(item)) {
				throw new IllegalStateException("Duplicate mutation received: " + item);
			}
			this.subscription.request(1);
			if (this.delay > 0L) {
				try {
					this.delayTimeUnit.sleep(this.delay);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			if (this.mutations.size() == this.finishOnReachingCount) {
				this.finished.complete(null);
			}
		}

		@Override
		public void onError(Throwable throwable) {
			this.finished.completeExceptionally(throwable);
		}

		@Override
		public void onComplete() {
			this.finished.complete(null);
		}
	}
}
