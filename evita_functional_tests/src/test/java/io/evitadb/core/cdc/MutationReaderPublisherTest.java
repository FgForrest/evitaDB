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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static io.evitadb.store.wal.CatalogWriteAheadLogIntegrationTest.writeWal;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies the contract of {@link MutationReaderPublisher} and its ability to publish mutation events.
 * The test suite covers various scenarios including:
 * <ul>
 *   <li>Publishing all mutations from the beginning</li>
 *   <li>Publishing mutations from a specific version</li>
 *   <li>Publishing mutations within a version range</li>
 *   <li>Handling subscriber saturation (when subscriber can't keep up with the publisher)</li>
 *   <li>Consuming new versions as they are introduced to the WAL</li>
 * </ul>
 *
 * The tests use a Write-Ahead Log (WAL) to store mutations and verify that the publisher correctly
 * reads and publishes these mutations to subscribers following the Reactive Streams pattern.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class MutationReaderPublisherTest {
	/**
	 * Directory where the Write-Ahead Log (WAL) files will be stored for testing.
	 * Uses a temporary directory with a unique name based on the test class.
	 */
	private static final Path WAL_DIRECTORY = Path.of(System.getProperty("java.io.tmpdir")).resolve("evita").resolve(MutationReaderPublisherTest.class.getSimpleName());

	/**
	 * Pool of Kryo serializers used for serializing and deserializing mutations in the WAL.
	 * Limited to a single instance for testing purposes.
	 */
	private static final Pool<Kryo> CATALOG_KRYO_POOL = new Pool<>(false, false, 1) {
		@Override
		protected Kryo create() {
			return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		}
	};

	/**
	 * Path to an isolated WAL file used for specific test scenarios.
	 */
	private static final Path ISOLATED_WAL_FILE_PATH = WAL_DIRECTORY.resolve("isolatedWal.tmp");

	/**
	 * Keeper for observable outputs used in serialization processes.
	 * Compression is disabled to ensure consistent mutation sizes on disk for testing.
	 */
	private static final ObservableOutputKeeper OBSERVABLE_OUTPUT_KEEPER = new ObservableOutputKeeper(
		TEST_CATALOG,
		StorageOptions.builder()
			/* there are tests that rely on standard size of mutations on disk in this class */
			.compress(false)
			.build(),
		Mockito.mock(Scheduler.class)
	);

	/**
	 * Memory manager for off-heap storage used in the tests.
	 * Allocated with 10MB of memory and 128-byte alignment.
	 */
	private static final OffHeapMemoryManager OFF_HEAP_MEMORY_MANAGER = new OffHeapMemoryManager(TEST_CATALOG, 10_000_000, 128);

	/**
	 * Array defining the sizes of transactions to be created in the WAL.
	 * Each number represents the number of mutations in a transaction (plus one for the transaction itself).
	 */
	private static final int[] TX_SIZES = new int[]{20, 30, 40, 50, 70, 90, 10};

	/**
	 * The Write-Ahead Log instance used for testing.
	 * Initialized in the {@link #setUp()} method.
	 */
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

	/**
	 * Tests that the publisher correctly publishes all mutations from the WAL to the subscriber.
	 *
	 * This test verifies:
	 * - All mutations are correctly read from the WAL and published
	 * - The completion callback is invoked when all mutations are published
	 * - The publisher is properly closed after all mutations are published
	 */
	@Test
	void shouldPublishAllMutations() {
		// Flag to track if the completion callback was invoked
		final AtomicBoolean isCompleted = new AtomicBoolean(false);
		// Calculate total number of mutations (each transaction size + 1 for the transaction itself)
		final int mutationCount = Arrays.stream(TX_SIZES).map(it -> it + 1).sum();
		// Create a subscriber that will receive all mutations
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

	/**
	 * Tests that the publisher correctly publishes mutations starting from a specific version.
	 *
	 * This test verifies:
	 * - Only mutations from the specified version onwards are published
	 * - The completion callback is invoked when all relevant mutations are published
	 * - The publisher is properly closed after all relevant mutations are published
	 */
	@Test
	void shouldPublishAllMutationsSinceParticularVersion() {
		// Flag to track if the completion callback was invoked
		final AtomicBoolean isCompleted = new AtomicBoolean(false);
		// Calculate mutation count starting from the 3rd transaction (index 2)
		// Subtract 2 to account for mutations that would be skipped within the starting transaction
		final int mutationCount = Arrays.stream(TX_SIZES)
			.skip(2)
			.map(it -> it + 1)
			.sum() - 2;
		// Create a subscriber that will receive the mutations
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

	/**
	 * Tests that the publisher correctly publishes mutations within a specific version range.
	 *
	 * This test verifies:
	 * - Only mutations between the specified start version and end version are published
	 * - The completion callback is invoked when all relevant mutations are published
	 * - The publisher is properly closed after all relevant mutations are published
	 */
	@Test
	void shouldPublishAllMutationsSinceParticularVersionUntilCurrentVersion() {
		// Flag to track if the completion callback was invoked
		final AtomicBoolean isCompleted = new AtomicBoolean(false);
		// Calculate mutation count starting from the 3rd transaction (index 2)
		// Limit to only 2 transactions and subtract 2 to account for mutations
		// that would be skipped within the starting transaction
		final int mutationCount = Arrays.stream(TX_SIZES)
			.skip(2)
			.map(it -> it + 1)
			.limit(2)
			.sum() - 2;
		// Create a subscriber that will receive the mutations
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

	/**
	 * Tests that the publisher correctly handles a subscriber that processes items slowly.
	 *
	 * This test verifies:
	 * - The publisher can handle a subscriber that gets saturated (processes items slowly)
	 * - All mutations are eventually published even when the subscriber is slow
	 * - The completion callback is invoked when all mutations are published
	 * - The publisher is properly closed after all mutations are published
	 */
	@Test
	void shouldReadAllDataOnMultipleRoundsIfSubscriberGetsSaturated() {
		// Flag to track if the completion callback was invoked
		final AtomicBoolean isCompleted = new AtomicBoolean(false);
		// Calculate total number of mutations (each transaction size + 1 for the transaction itself)
		final int mutationCount = Arrays.stream(TX_SIZES)
			.map(it -> it + 1)
			.sum();

		// Create a subscriber that processes items slowly (5ms delay per item)
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

	/**
	 * Tests that the publisher correctly discards subscribers that can't keep up with the publishing rate.
	 *
	 * This test verifies:
	 * - The publisher detects when a subscriber is too slow (saturated)
	 * - The publisher correctly discards saturated subscribers
	 * - The discard callback is invoked with the correct subscriber
	 * - The publisher continues operating even after discarding a subscriber
	 *
	 * Note: When using a single-threaded executor (ImmediateExecutorService), the completion callback
	 * may be invoked even when the subscriber is discarded, due to the synchronous nature of the executor.
	 * In a multi-threaded environment, the completion callback would not be invoked for discarded subscribers.
	 */
	@Test
	void shouldDiscardSubscribersOnSaturation() throws ExecutionException, InterruptedException, TimeoutException {
		// Flag to track if the completion callback was invoked
		final AtomicBoolean isCompleted = new AtomicBoolean(false);
		final CompletableFuture<Void> isDiscarded = new CompletableFuture<>();
		// Calculate total number of mutations (each transaction size + 1 for the transaction itself)
		final int mutationCount = Arrays.stream(TX_SIZES)
			.map(it -> it + 1)
			.sum();
		// List to collect discarded subscribers
		final List<MockMutationSubscriber> discardedSubscribers = new ArrayList<>();

		// Create a subscriber that processes items slowly (5ms delay per item)
		final MockMutationSubscriber mockSubscriber = new MockMutationSubscriber(5, TimeUnit.MILLISECONDS, mutationCount);
		try (
			final MutationReaderPublisher<MockMutationSubscriber> publisher = new MutationReaderPublisher<>(
				Executors.newFixedThreadPool(1), 5,
				0L, 0,
				() -> TX_SIZES.length + 1,
				startVersion -> WAL.getCommittedMutationStream(startVersion),
				subscriber -> {
					discardedSubscribers.add(subscriber);
					isDiscarded.complete(null);
				},
				subscriber -> {
					assertSame(mockSubscriber, subscriber);
					isCompleted.set(true);
				}
			)
		) {
			publisher.subscribe(mockSubscriber);

			publisher.readAll();

			// this would be true if we'd used a multithreaded executor
			assertTrue(mockSubscriber.getCount() > 0);
			assertTrue(mockSubscriber.getCount() < mutationCount);

			isDiscarded.get(10, TimeUnit.SECONDS);
			assertEquals(1, discardedSubscribers.size());
			assertSame(mockSubscriber, discardedSubscribers.get(0));

			assertFalse(isCompleted.get());
			assertFalse(publisher.isClosed());
		}
	}

	/**
	 * Tests that the publisher correctly consumes new versions as they are introduced to the WAL.
	 *
	 * This test verifies:
	 * - The publisher doesn't read mutations beyond the current version
	 * - When the current version advances, the publisher reads the newly available mutations
	 * - The publisher correctly handles multiple version advances
	 * - The publisher correctly handles new mutations being added to the WAL
	 */
	@Test
	void shouldConsumeNewVersionsAsTheyAreIntroduced() throws InterruptedException, ExecutionException {
		// Create a separate WAL directory for this test to avoid interference with other tests
		final Path walDirectoryForAppending = Path.of(System.getProperty("java.io.tmpdir"))
			.resolve("evita")
			.resolve(MutationReaderPublisherTest.class.getSimpleName() + "_append");

		// Clear the WAL directory to ensure a clean state
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

		// Start with empty WAL and track the current version with an AtomicLong
		final AtomicBoolean isCompleted = new AtomicBoolean(false);
		final AtomicLong currentVersion = new AtomicLong(0L);
		// Create a subscriber with a small delay to simulate real-world processing
		final MockMutationSubscriber mockSubscriber = new MockMutationSubscriber(5, TimeUnit.MILLISECONDS, Integer.MAX_VALUE);
		try (
			// Create a publisher that will read mutations up to the current version
			// Buffer size of 256 is large enough for this test
			final MutationReaderPublisher<MockMutationSubscriber> publisher = new MutationReaderPublisher<>(
				EXECUTOR, 256,
				0L, 0, // Start from version 0, transaction 0
				currentVersion::get, // Use the atomic long to determine the current version
				appendableWAL::getCommittedMutationStream, // Get mutations from our test WAL
				subscriber -> fail("Subscriber should not be discarded!"), // Should never discard in this test
				null // No completion callback needed
			)
		) {
			publisher.subscribe(mockSubscriber);

			// Write initial transactions to the WAL (5 mutations in first tx, 3 in second)
			writeWal(
				OFF_HEAP_MEMORY_MANAGER,
				new int[] {5, 3},
				null,
				ISOLATED_WAL_FILE_PATH,
				OBSERVABLE_OUTPUT_KEEPER,
				appendableWAL
			);

			// This should not read anything, since the current version is still 0
			// (WAL has versions 1 and 2, but we're limiting to version 0)
			publisher.readAll();
			TimeUnit.SECONDS.sleep(1); // Give time for async processing
			assertEquals(0, mockSubscriber.getCount());

			// Move the current version to 1 and read the first transaction (5 mutations + 1 tx)
			currentVersion.set(1L);
			readMutations(mockSubscriber, publisher, 5 + 1);

			// Move the current version to 2 and read the second transaction (3 mutations + 1 tx)
			currentVersion.set(2L);
			readMutations(mockSubscriber, publisher, 3 + 1);

			// Write more transactions to the WAL (10 mutations in third tx, 4 in fourth)
			writeWal(
				OFF_HEAP_MEMORY_MANAGER,
				new int[] {10, 4},
				null,
				ISOLATED_WAL_FILE_PATH,
				OBSERVABLE_OUTPUT_KEEPER,
				appendableWAL
			);

			// This should not read anything new, since the current version is still 2
			// (WAL now has versions 1-4, but we're limiting to version 2)
			final long startCount = mockSubscriber.getCount();
			publisher.readAll();
			TimeUnit.SECONDS.sleep(1); // Give time for async processing
			assertEquals(startCount, mockSubscriber.getCount());

			// Move the current version to 3 and read the third transaction (10 mutations + 1 tx)
			currentVersion.set(3L);
			readMutations(mockSubscriber, publisher, 10 + 1);

			// Move the current version to 4 and read the fourth transaction (4 mutations + 1 tx)
			currentVersion.set(4L);
			readMutations(mockSubscriber, publisher, 4 + 1);

			// Verify the publisher is still active (not completed or closed)
			assertFalse(isCompleted.get());
			assertFalse(publisher.isClosed());
		}
	}

	/**
	 * Helper method that reads mutations from the given publisher and verifies that the expected number of mutations
	 * have been processed by the subscriber.
	 *
	 * This method:
	 * 1. Records the current count of mutations received by the subscriber
	 * 2. Triggers the publisher to read and publish mutations
	 * 3. Verifies that the subscriber received exactly the expected number of new mutations
	 *
	 * @param mockSubscriber the mock subscriber that processes the mutations and tracks the count of received items
	 * @param publisher the mutation reader publisher that provides mutations to be consumed by the subscriber
	 * @param mutationCount the expected number of new mutations to be received by the subscriber in this read operation
	 * @throws AssertionError if the subscriber doesn't receive exactly the expected number of mutations
	 */
	private static void readMutations(
		@Nonnull MockMutationSubscriber mockSubscriber,
		@Nonnull MutationReaderPublisher<MockMutationSubscriber> publisher,
		int mutationCount
	) {
		// Record the current count of mutations before reading new ones
		final int startCount = mockSubscriber.getCount();

		// Trigger the publisher to read and publish mutations
		publisher.readAll();

		// Verify that exactly the expected number of new mutations were received
		assertEquals(
			startCount + mutationCount, mockSubscriber.getCount(),
			"Expected " + mutationCount + " mutations, but got " + (mockSubscriber.getCount() - startCount)
		);
	}

	/**
	 * MockMutationSubscriber is a testing utility class that implements the Reactive Streams
	 * Subscriber interface to interact with a publisher of {@link Mutation} objects.
	 * It is designed to simulate mutation subscriptions with configurable processing delays
	 * to test different subscriber behaviors.
	 *
	 * The class tracks received mutations and provides methods to check the state of the subscription.
	 */
	private static class MockMutationSubscriber implements Subscriber<Mutation> {
		/**
		 * Delay in milliseconds to simulate processing time for each mutation.
		 * Used to test how the publisher handles slow subscribers.
		 */
		private final long delay;

		/**
		 * Time unit for the delay value.
		 */
		private final TimeUnit delayTimeUnit;

		/**
		 * The subscription object received from the publisher.
		 * Used to request more items from the publisher.
		 */
		private Subscription subscription;

		/**
		 * Set of received mutations. Using LinkedHashSet to maintain order
		 * while ensuring uniqueness of mutations.
		 */
		private final LinkedHashSet<Mutation> mutations = new LinkedHashSet<>();

		/**
		 * Flag indicating whether the subscription has been completed.
		 */
		@Getter private boolean completed;

		/**
		 * Creates a subscriber with no processing delay.
		 *
		 * @param finishOnReachingCount The expected number of mutations to receive (not used in current implementation)
		 */
		public MockMutationSubscriber(int finishOnReachingCount) {
			this.delay = 0L;
			this.delayTimeUnit = TimeUnit.MILLISECONDS;
		}

		/**
		 * Creates a subscriber with a specified processing delay.
		 *
		 * @param delay The amount of time to delay processing each mutation
		 * @param delayTimeUnit The time unit for the delay value
		 * @param finishOnReachingCount The expected number of mutations to receive (not used in current implementation)
		 */
		public MockMutationSubscriber(long delay, TimeUnit delayTimeUnit, int finishOnReachingCount) {
			this.delay = delay;
			this.delayTimeUnit = delayTimeUnit;
		}

		/**
		 * Returns the number of mutations received by this subscriber.
		 *
		 * @return The count of unique mutations received
		 */
		public int getCount() {
			return this.mutations.size();
		}

		/**
		 * Called when the publisher establishes a subscription with this subscriber.
		 * Stores the subscription and immediately requests the first item.
		 *
		 * @param subscription The subscription object from the publisher
		 */
		@Override
		public void onSubscribe(Subscription subscription) {
			assertFalse(this.completed, "Subscriber should not be completed yet!");
			this.subscription = subscription;
			this.subscription.request(1); // Request the first item
		}

		/**
		 * Called by the publisher when a new mutation is available.
		 * Stores the mutation, requests the next item, and applies the configured delay.
		 *
		 * @param item The mutation received from the publisher
		 * @throws IllegalStateException if a duplicate mutation is received
		 */
		@Override
		public void onNext(Mutation item) {
			assertFalse(this.completed, "Subscriber should not be completed yet!");
			// Ensure we don't receive duplicates
			if (!this.mutations.add(item)) {
				throw new IllegalStateException("Duplicate mutation received: " + item);
			}
			// Request the next item
			this.subscription.request(1);
			// Apply the configured delay to simulate processing time
			if (this.delay > 0L) {
				try {
					this.delayTimeUnit.sleep(this.delay);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}

		/**
		 * Called when the publisher encounters an error.
		 * Fails the test with the provided throwable.
		 *
		 * @param throwable The error encountered by the publisher
		 */
		@Override
		public void onError(Throwable throwable) {
			fail(throwable);
		}

		/**
		 * Called when the publisher has no more items to publish.
		 * Marks this subscriber as completed.
		 */
		@Override
		public void onComplete() {
			this.completed = true;
		}
	}

}
