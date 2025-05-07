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
import io.evitadb.utils.ImmediateExecutorService;
import lombok.Getter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static io.evitadb.store.wal.CatalogWriteAheadLogIntegrationTest.writeWal;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static org.junit.jupiter.api.Assertions.*;

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
	private static final ExecutorService EXECUTOR = new ImmediateExecutorService();

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

			assertEquals(mutationCount, mockSubscriber.getCount());
			assertTrue(isCompleted.get());
			assertTrue(publisher.isClosed());
		}
	}

	@Test
	void shouldDiscardSubscribersOnSaturation() {
		final AtomicBoolean isCompleted = new AtomicBoolean(false);
		final int mutationCount = Arrays.stream(TX_SIZES)
			.map(it -> it + 1)
			.sum();
		final List<MockMutationSubscriber> discardedSubscribers = new ArrayList<>();

		final MockMutationSubscriber mockSubscriber = new MockMutationSubscriber(5, TimeUnit.MILLISECONDS, mutationCount);
		try (
			final MutationReaderPublisher<MockMutationSubscriber> publisher = new MutationReaderPublisher<>(
				EXECUTOR, 5,
				0L, 0,
				() -> TX_SIZES.length + 1,
				startVersion -> WAL.getCommittedMutationStream(startVersion),
				discardedSubscribers::add,
				subscriber -> {
					assertSame(mockSubscriber, subscriber);
					isCompleted.set(true);
				}
			)
		) {
			publisher.subscribe(mockSubscriber);

			publisher.readAll();

			// this would be true if we'd used a multithreaded executor
			/*assertTrue(mockSubscriber.getCount() > 0);
			assertTrue(mockSubscriber.getCount() < mutationCount);*/

			// this is true for single threaded executor
			assertEquals(mutationCount, mockSubscriber.getCount());
			assertFalse(isCompleted.get());
			assertFalse(publisher.isClosed());
			assertEquals(1, discardedSubscribers.size());
			assertSame(mockSubscriber, discardedSubscribers.get(0));
		}
	}

	@Test
	void shouldConsumeNewVersionsAsTheyAreIntroduced() throws InterruptedException, ExecutionException {
		final Path walDirectoryForAppending = Path.of(System.getProperty("java.io.tmpdir"))
			.resolve("evita")
			.resolve(MutationReaderPublisherTest.class.getSimpleName() + "_append");

		// clear the WAL directory
		FileUtils.deleteDirectory(walDirectoryForAppending);
		assertTrue(walDirectoryForAppending.toFile().mkdirs());
		final CatalogWriteAheadLog appendableWAL = new CatalogWriteAheadLog(
			0L,
			TEST_CATALOG,
			walDirectoryForAppending,
			CATALOG_KRYO_POOL,
			StorageOptions.builder()
				/* there are tests that rely on standard size of mutations on disk in this class */
				.compress(false)
				.build(),
			TransactionOptions.builder().walFileSizeBytes(Long.MAX_VALUE).build(),
			Mockito.mock(Scheduler.class),
			value -> {
			},
			firstActiveCatalogVersion -> {
			}
		);

		// start with empty WAL
		final AtomicBoolean isCompleted = new AtomicBoolean(false);
		final AtomicLong currentVersion = new AtomicLong(0L);
		final MockMutationSubscriber mockSubscriber = new MockMutationSubscriber(5, TimeUnit.MILLISECONDS, Integer.MAX_VALUE);
		try (
			final MutationReaderPublisher<MockMutationSubscriber> publisher = new MutationReaderPublisher<>(
				EXECUTOR, 256,
				0L, 0,
				currentVersion::get,
				appendableWAL::getCommittedMutationStream,
				subscriber -> fail("Subscriber should not be discarded!"),
				null
			)
		) {
			publisher.subscribe(mockSubscriber);

			writeWal(
				OFF_HEAP_MEMORY_MANAGER,
				new int[] {5, 3},
				null,
				ISOLATED_WAL_FILE_PATH,
				OBSERVABLE_OUTPUT_KEEPER,
				appendableWAL
			);

			// this should not read anything, since the current version is still 0
			publisher.readAll();
			TimeUnit.SECONDS.sleep(1);
			assertEquals(0, mockSubscriber.getCount());

			// move the current version and read again
			currentVersion.set(1L);
			readMutations(mockSubscriber, publisher, 5 + 1);

			// move the current version and read again
			currentVersion.set(2L);
			readMutations(mockSubscriber, publisher, 3 + 1);

			// write new mutations to WAL
			writeWal(
				OFF_HEAP_MEMORY_MANAGER,
				new int[] {10, 4},
				null,
				ISOLATED_WAL_FILE_PATH,
				OBSERVABLE_OUTPUT_KEEPER,
				appendableWAL
			);

			// this should not read anything, since the current version is still 0
			final long startCount = mockSubscriber.getCount();
			publisher.readAll();
			TimeUnit.SECONDS.sleep(1);
			assertEquals(startCount, mockSubscriber.getCount());

			// move the current version and read again
			currentVersion.set(3L);
			readMutations(mockSubscriber, publisher, 10 + 1);

			// move the current version and read again
			currentVersion.set(4L);
			readMutations(mockSubscriber, publisher, 4 + 1);

			assertFalse(isCompleted.get());
			assertFalse(publisher.isClosed());
		}
	}

	/**
	 * Reads mutations from the given publisher and verifies that the expected number of mutations have been processed
	 * by the subscriber.
	 *
	 * @param mockSubscriber the mock subscriber that processes the mutations and tracks the count of received items.
	 * @param publisher the mutation reader publisher that provides all mutations to be consumed by the subscriber.
	 * @param mutationCount the expected number of mutations to be received by the subscriber.
	 */
	private static void readMutations(
		@Nonnull MockMutationSubscriber mockSubscriber,
		@Nonnull MutationReaderPublisher<MockMutationSubscriber> publisher,
		int mutationCount
	) {
		final int startCount = mockSubscriber.getCount();
		publisher.readAll();
		assertEquals(
			startCount + mutationCount, mockSubscriber.getCount(),
			"Expected " + mutationCount + " mutations, but got " + (mockSubscriber.getCount() - startCount)
		);
	}

	/**
	 * MockMutationSubscriber is a testing utility class that implements the Reactive Streams
	 * Subscriber interface to interact with a publisher of {@link Mutation} objects.
	 * It is designed to simulate mutation subscriptions*/
	private static class MockMutationSubscriber implements Subscriber<Mutation> {
		private final long delay;
		private final TimeUnit delayTimeUnit;
		private Subscription subscription;
		private final LinkedHashSet<Mutation> mutations = new LinkedHashSet<>();
		@Getter private boolean completed;

		public MockMutationSubscriber(int finishOnReachingCount) {
			this.delay = 0L;
			this.delayTimeUnit = TimeUnit.MILLISECONDS;
		}

		public MockMutationSubscriber(long delay, TimeUnit delayTimeUnit, int finishOnReachingCount) {
			this.delay = delay;
			this.delayTimeUnit = delayTimeUnit;
		}

		public int getCount() {
			return this.mutations.size();
		}

		@Override
		public void onSubscribe(Subscription subscription) {
			assertFalse(this.completed, "Subscriber should not be completed yet!");
			this.subscription = subscription;
			this.subscription.request(1);
		}

		@Override
		public void onNext(Mutation item) {
			assertFalse(this.completed, "Subscriber should not be completed yet!");
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
		}

		@Override
		public void onError(Throwable throwable) {
			fail(throwable);
		}

		@Override
		public void onComplete() {
			this.completed = true;
		}
	}

}
