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

import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.cdc.DataSite;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.mutation.Mutation.StreamDirection;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.cdc.predicate.MutationPredicateFactory;
import io.evitadb.dataType.ContainerType;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.Entities;
import io.evitadb.utils.UUIDUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link CapturePublisher} and its ability to publish events.
 * The CapturePublisher is a specialized implementation of SubmissionPublisher that handles
 * Change Data Capture (CDC) events in the evitaDB system.
 *
 * These tests ensure that:
 * 1. The publisher correctly captures and forwards mutations to subscribers
 * 2. Filtering of mutations works as expected based on predicates
 * 3. Error handling and completion signals are properly propagated
 * 4. Resource management (subscription handling, cleanup) works correctly
 * 5. The publisher correctly handles backpressure and lagging subscribers
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
class CapturePublisherTest {
	/**
	 * Unique identifier for the CapturePublisher instances created in tests.
	 */
	private final UUID id = UUID.randomUUID();

	/**
	 * Flag to track when the publisher has completed its work.
	 * This is set to true in the onCompletion callback.
	 */
	private final AtomicBoolean finished = new AtomicBoolean(false);

	/**
	 * Single-threaded executor service used for running the publisher's tasks.
	 * Using a single thread ensures predictable execution order for tests.
	 */
	private final ExecutorService executor = Executors.newFixedThreadPool(1);

	/**
	 * Predicate that fails the test if a mutation is dropped due to backpressure.
	 * Used in tests where we expect the subscriber to keep up with the publisher.
	 */
	private final BiPredicate<Subscriber<? super Mutation>, Mutation> failOnDrop =
		(subscriber1, mutation) -> fail("Subscriber doesn't keep up!");

	/**
	 * A predicate that accepts all mutations, used as a default in tests.
	 * Created using the factory with an empty request, which means no filtering.
	 */
	private final MutationPredicate catchAllPredicate = MutationPredicateFactory.createChangeCatalogCapturePredicate(ChangeCatalogCaptureRequest.builder().build());

	@AfterEach
	void tearDown() {
		this.executor.shutdown();
	}

	/**
	 * Tests that the CapturePublisher correctly captures and forwards all published mutations to its subscriber.
	 *
	 * This test:
	 * 1. Creates a subscriber that expects to receive 100 items
	 * 2. Sets up a publisher chain: mutationPublisher -> capturePublisher -> subscriber
	 * 3. Publishes 50 transactions (each containing 2 mutations: transaction + entity)
	 * 4. Verifies that all 100 mutations are received by the subscriber
	 * 5. Confirms that the publisher signals completion
	 */
	@Test
	void shouldCapturePublishedChanges() throws ExecutionException, InterruptedException, TimeoutException {
		// Create a subscriber that will complete after receiving 100 items
		final MockSubscriber subscriber = new MockSubscriber(100);

		// Create the publisher chain
		final CapturePublisher capturePublisher = createCapturePublisher(0, 0);
		final SubmissionPublisher<Mutation> mutationPublisher = new SubmissionPublisher<>(this.executor, 10);
		capturePublisher.subscribe(subscriber);
		mutationPublisher.subscribe(capturePublisher);

		// Publish 50 transactions (each with 2 mutations)
		publishMutationsWithFailOnLag(mutationPublisher, 50);

		// Wait for the subscriber to receive all items
		subscriber.getFuture().get(1, TimeUnit.SECONDS);

		// Verify that all 100 mutations were received
		assertEquals(100, subscriber.getItems().size());

		// Verify that the publisher signaled completion
		assertTrue(this.finished.get());
	}

	/**
	 * Tests that the CapturePublisher correctly filters mutations based on catalog version.
	 *
	 * This test:
	 * 1. Creates a subscriber that expects to receive 40 items
	 * 2. Creates a CapturePublisher that starts capturing from catalog version 31
	 * 3. Publishes 50 transactions (each containing 2 mutations)
	 * 4. Verifies that only 40 mutations (from version 31 onwards) are received by the subscriber
	 *
	 * This test demonstrates the publisher's ability to filter out mutations that occurred
	 * before a specified catalog version, which is important for resuming CDC streams.
	 */
	@Test
	void shouldCapturePublishedChangesSinceVersion() throws ExecutionException, InterruptedException, TimeoutException {
		// Create a subscriber that will complete after receiving 40 items
		final MockSubscriber subscriber = new MockSubscriber(40);

		// Create a publisher that starts capturing from catalog version 31
		final CapturePublisher capturePublisher = createCapturePublisher(31, 0);
		final SubmissionPublisher<Mutation> mutationPublisher = new SubmissionPublisher<>(this.executor, 10);
		capturePublisher.subscribe(subscriber);
		mutationPublisher.subscribe(capturePublisher);

		// Publish 50 transactions (each with 2 mutations)
		publishMutationsWithFailOnLag(mutationPublisher, 50);

		// Wait for the subscriber to receive all items
		subscriber.getFuture().get(1, TimeUnit.SECONDS);

		// Verify that only 40 mutations (from version 31 onwards) were received
		assertEquals(40, subscriber.getItems().size());

		// Verify that the publisher signaled completion
		assertTrue(this.finished.get());
	}

	/**
	 * Tests that the CapturePublisher correctly filters mutations based on both catalog version and index.
	 *
	 * This test:
	 * 1. Creates a subscriber that expects to receive 39 items
	 * 2. Creates a CapturePublisher that starts capturing from catalog version 31, index 1
	 *    (skipping the first mutation in version 31)
	 * 3. Publishes 50 transactions (each containing 2 mutations)
	 * 4. Verifies that only 39 mutations are received by the subscriber
	 *
	 * This test demonstrates the publisher's ability to filter out mutations based on both
	 * catalog version and index within that version, which is important for precise resumption
	 * of CDC streams after interruptions.
	 */
	@Test
	void shouldCapturePublishedChangesSinceVersionAndIndex() throws ExecutionException, InterruptedException, TimeoutException {
		// Create a subscriber that will complete after receiving 39 items
		final MockSubscriber subscriber = new MockSubscriber(39);

		// Create a publisher that starts capturing from catalog version 31, index 1
		// This skips the first mutation (index 0) in version 31
		final CapturePublisher capturePublisher = createCapturePublisher(31, 1);
		final SubmissionPublisher<Mutation> mutationPublisher = new SubmissionPublisher<>(this.executor, 10);
		capturePublisher.subscribe(subscriber);
		mutationPublisher.subscribe(capturePublisher);

		// Publish 50 transactions (each with 2 mutations)
		publishMutationsWithFailOnLag(mutationPublisher, 50);

		// Wait for the subscriber to receive all items
		subscriber.getFuture().get(1, TimeUnit.SECONDS);

		// Verify that only 39 mutations were received (40 from version 31 onwards, minus the first one)
		assertEquals(39, subscriber.getItems().size());

		// Verify that the publisher signaled completion
		assertTrue(this.finished.get());
	}

	/**
	 * Tests that the CapturePublisher correctly filters mutations based on a predicate.
	 *
	 * This test:
	 * 1. Creates a subscriber that expects to receive 50 items
	 * 2. Creates a CapturePublisher with a predicate that filters for entity mutations only
	 *    (excluding transaction mutations)
	 * 3. Publishes 50 transactions (each containing 2 mutations: transaction + entity)
	 * 4. Verifies that only 50 entity mutations are received by the subscriber
	 * 5. Confirms that no TransactionMutation objects are included in the received items
	 *
	 * This test demonstrates the publisher's ability to selectively filter mutations
	 * based on complex criteria, which is essential for CDC clients that are only
	 * interested in specific types of changes.
	 */
	@Test
	void shouldCapturePublishedFilteredChanges() throws ExecutionException, InterruptedException, TimeoutException {
		// Create a subscriber that will complete after receiving 50 items
		final MockSubscriber subscriber = new MockSubscriber(50);

		// Create a publisher with a predicate that filters for entity mutations only
		final CapturePublisher capturePublisher = createCapturePublisher(
			0,
			0,
			Objects.requireNonNull(
				MutationPredicateFactory.createCriteriaPredicate(
					ChangeCatalogCaptureCriteria.builder()
						.area(CaptureArea.DATA)  // Only data area (not transaction area)
						.site(DataSite.builder().containerType(ContainerType.ENTITY).build())  // Only entity container
						.build(),
					new MutationPredicateContext(StreamDirection.FORWARD)
				)
			)
		);
		final SubmissionPublisher<Mutation> mutationPublisher = new SubmissionPublisher<>(this.executor, 10);
		capturePublisher.subscribe(subscriber);
		mutationPublisher.subscribe(capturePublisher);

		// Publish 50 transactions (each with 2 mutations)
		publishMutationsWithFailOnLag(mutationPublisher, 50);

		// Wait for the subscriber to receive all items
		subscriber.getFuture().get(1, TimeUnit.SECONDS);

		// Verify that only 50 mutations were received (only the entity mutations, not the transaction mutations)
		assertEquals(50, subscriber.getItems().size());

		// Verify that none of the received items are TransactionMutation objects
		for (ChangeCatalogCapture item : subscriber.getItems()) {
			assertFalse(item.body() instanceof TransactionMutation);
		}

		// Verify that the publisher signaled completion
		assertTrue(this.finished.get());
	}

	/**
	 * Tests that the CapturePublisher correctly handles backpressure and can resume publishing
	 * when the subscriber's buffer becomes available again.
	 *
	 * This test:
	 * 1. Creates a subscriber that expects to receive 100 items
	 * 2. Creates a CapturePublisher with a callback for when the subscriber lags behind
	 * 3. Creates a special ReadLazilyPublisher that can detect when its buffer is full and resume later
	 * 4. Attempts to publish mutations, which may cause buffer overflow
	 * 5. When overflow occurs, the publisher records the version and index where it stopped
	 * 6. The publisher then resumes publishing from that point when the buffer is available again
	 * 7. Verifies that all 100 expected mutations are eventually received by the subscriber
	 *
	 * This test demonstrates the publisher's ability to handle backpressure situations where
	 * the subscriber cannot keep up with the rate of published events, which is crucial for
	 * maintaining system stability under load.
	 */
	@Test
	void shouldPullNewItemsOnEmptyBuffer() throws ExecutionException, InterruptedException, TimeoutException {
		// Create a subscriber that will complete after receiving 100 items
		final MockSubscriber subscriber = new MockSubscriber(100);

		// Track the version and index where publishing was interrupted due to backpressure
		final AtomicLong lagVersion = new AtomicLong(0);
		final AtomicInteger lagIndex = new AtomicInteger(0);

		// Create a publisher with a callback for when the subscriber lags behind
		final CapturePublisher capturePublisher = new CapturePublisher(
			this.id,
			0, 0,
			this.executor,
			this.catchAllPredicate,
			ChangeCaptureContent.BODY,
			cp -> {
				// When the subscriber lags behind, record where to continue from later
				lagVersion.set(cp.getContinueWithVersion());
				lagIndex.set(cp.getContinueWithIndex());
			},
			uuid -> {
				assertEquals(this.id, uuid);
				this.finished.set(true);
			}
		);

		// Keep track of all mutations that were published
		final List<Mutation> publishedMutations = new ArrayList<>();

		// Create a special publisher that can detect when its buffer is full and resume later
		final ReadLazilyPublisher mutationPublisher = new ReadLazilyPublisher(
			this.executor, 10,
			readLazilyPublisher ->
				() -> {
					try {
						// Try to publish more mutations when the buffer is available again
						publishMutations(readLazilyPublisher, 50, lagVersion.get(), lagIndex.get(), publishedMutations);
					} catch (SaturatedException e) {
						// If the buffer gets full again, record where to continue from
						lagVersion.set(e.getCatalogVersion());
						lagIndex.set(e.getIndex());
					}
				}
		);

		// Set up the publisher chain
		capturePublisher.subscribe(subscriber);
		mutationPublisher.subscribe(capturePublisher);

		try {
			// Initial attempt to publish mutations
			publishMutations(mutationPublisher, 50, 1, 0, publishedMutations);
		} catch (SaturatedException e) {
			// If the buffer gets full, record where to continue from
			lagVersion.set(e.getCatalogVersion());
			lagIndex.set(e.getIndex());
		}

		// Wait for the subscriber to receive all items (may take multiple publish attempts)
		subscriber.getFuture().get(100, TimeUnit.SECONDS);

		// Verify that all 100 expected mutations were received
		assertEquals(100, subscriber.getItems().size());

		// Verify that the publisher signaled completion
		assertTrue(this.finished.get());

		// Verify that the received items match the published mutations
		assertEquals(
			publishedMutations.stream().flatMap(it -> it.toChangeCatalogCapture(this.catchAllPredicate, ChangeCaptureContent.BODY)).toList(),
			subscriber.getItems()
		);
	}

	/**
	 * Tests that errors are properly propagated to subscribers.
	 * This test verifies that when an error occurs in the publisher chain,
	 * it is correctly propagated to all downstream subscribers.
	 */
	@Test
	void shouldPropagateErrorsToSubscribers() throws InterruptedException {
		// Create a subscriber that will receive the error
		final MockSubscriber subscriber = new MockSubscriber(100);

		// Create a publisher
		final CapturePublisher capturePublisher = createCapturePublisher(0, 0);

		// Subscribe the subscriber to the publisher
		capturePublisher.subscribe(subscriber);

		// Create a test error
		final RuntimeException testError = new RuntimeException("Test error");

		// Trigger an error in the publisher
		capturePublisher.onError(testError);

		// Wait for the error to be propagated
		assertTrue(subscriber.awaitError(1, TimeUnit.SECONDS));

		// Verify that the error was propagated to the subscriber
		assertEquals(testError, subscriber.getError());
	}

	/**
	 * Tests that completion is properly propagated to subscribers.
	 * This test verifies that when the publisher completes, the completion
	 * signal is correctly propagated to all downstream subscribers.
	 */
	@Test
	void shouldPropagateCompletionToSubscribers() throws InterruptedException {
		// Create a subscriber that will receive the completion signal
		final MockSubscriber subscriber = new MockSubscriber(100);

		// Create a publisher that will be used to publish mutations
		final CapturePublisher capturePublisher = createCapturePublisher(0, 0);
		final SubmissionPublisher<Mutation> mutationPublisher = new SubmissionPublisher<>(this.executor, 10);

		// Subscribe the subscriber to the publisher
		capturePublisher.subscribe(subscriber);
		mutationPublisher.subscribe(capturePublisher);

		// Publish a few mutations to ensure the subscription is active
		publishMutationsWithFailOnLag(mutationPublisher, 5);

		// Trigger completion in the publisher chain
		capturePublisher.onComplete();

		// Wait for the completion to be propagated
		assertTrue(subscriber.awaitCompletion(1, TimeUnit.SECONDS));

		// Verify that the completion was propagated to the subscriber
		assertTrue(subscriber.isCompleted());
	}

	/**
	 * Tests that an exception is thrown when attempting to add a second subscriber.
	 * This test verifies that the CapturePublisher enforces the constraint of
	 * having only one subscriber at a time.
	 */
	@Test
	void shouldThrowExceptionForMultipleSubscribers() {
		// Create a publisher
		final CapturePublisher capturePublisher = createCapturePublisher(0, 0);

		// Create two subscribers
		final MockSubscriber subscriber1 = new MockSubscriber(100);
		final MockSubscriber subscriber2 = new MockSubscriber(100);

		// Subscribe the first subscriber (should succeed)
		capturePublisher.subscribe(subscriber1);

		// Try to subscribe the second subscriber (should throw an exception)
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> capturePublisher.subscribe(subscriber2),
			"Expected an exception when adding a second subscriber"
		);
	}

	/**
	 * Tests that the close method properly cleans up resources.
	 * This test verifies that when the close method is called, the subscription
	 * is canceled and the publisher is properly closed.
	 */
	@Test
	void shouldCleanupResourcesOnClose() {
		// Create a publisher
		final CapturePublisher capturePublisher = createCapturePublisher(0, 0);

		// Create a subscriber
		final MockSubscriber subscriber = new MockSubscriber(100);

		// Subscribe the subscriber to the publisher
		capturePublisher.subscribe(subscriber);

		// Close the publisher
		capturePublisher.close();

		// Verify that the publisher has no subscribers
		assertEquals(0, capturePublisher.getNumberOfSubscribers());
	}

	/**
	 * Tests that the closeExceptionally method properly cleans up resources.
	 * This test verifies that when the closeExceptionally method is called,
	 * the subscription is canceled and the publisher is properly closed with an error.
	 */
	@Test
	void shouldCleanupResourcesOnCloseExceptionally() throws InterruptedException {
		// Create a publisher
		final CapturePublisher capturePublisher = createCapturePublisher(0, 0);

		// Create a subscriber
		final MockSubscriber subscriber = new MockSubscriber(100);

		// Subscribe the subscriber to the publisher
		capturePublisher.subscribe(subscriber);

		// Create a test error
		final RuntimeException testError = new RuntimeException("Test error");

		// Close the publisher exceptionally
		capturePublisher.closeExceptionally(testError);

		// Wait for the error to be propagated
		assertTrue(subscriber.awaitError(1, TimeUnit.SECONDS));

		// Verify that the publisher has no subscribers
		assertEquals(0, capturePublisher.getNumberOfSubscribers());

		// Verify that the error was propagated to the subscriber
		assertEquals(testError, subscriber.getError());
	}

	/**
	 * Tests that the equals and hashCode methods work correctly.
	 * This test verifies that two CapturePublisher instances with the same ID
	 * are considered equal, and two instances with different IDs are not equal.
	 */
	@Test
	void shouldImplementEqualsAndHashCodeCorrectly() {
		// Create two publishers with the same ID
		final UUID sharedId = UUID.randomUUID();
		final CapturePublisher publisher1 = new CapturePublisher(
			sharedId,
			0, 0,
			this.executor,
			this.catchAllPredicate,
			ChangeCaptureContent.BODY,
			cp -> {},
			uuid -> {}
		);

		final CapturePublisher publisher2 = new CapturePublisher(
			sharedId,
			0, 0,
			this.executor,
			this.catchAllPredicate,
			ChangeCaptureContent.BODY,
			cp -> {},
			uuid -> {}
		);

		// Create a publisher with a different ID
		final CapturePublisher publisher3 = new CapturePublisher(
			UUID.randomUUID(),
			0, 0,
			this.executor,
			this.catchAllPredicate,
			ChangeCaptureContent.BODY,
			cp -> {},
			uuid -> {}
		);

		// Verify that publishers with the same ID are equal
		assertEquals(publisher1, publisher2);
		assertEquals(publisher1.hashCode(), publisher2.hashCode());

		// Verify that publishers with different IDs are not equal
		assertNotEquals(publisher1, publisher3);
		assertNotEquals(publisher1.hashCode(), publisher3.hashCode());

		// Verify that a publisher is not equal to null or a different type
		assertNotEquals(null, publisher1);
		assertNotEquals("not a publisher", publisher1);
	}

	/**
	 * Creates a CapturePublisher with the specified start catalog version and index,
	 * using the default catch-all predicate.
	 *
	 * @param startCatalogVersion the catalog version from which to start capturing
	 * @param startIndex the index within the catalog version from which to start capturing
	 * @return a new CapturePublisher instance
	 */
	@Nonnull
	private CapturePublisher createCapturePublisher(long startCatalogVersion, int startIndex) {
		return createCapturePublisher(startCatalogVersion, startIndex, this.catchAllPredicate);
	}

	/**
	 * Creates a CapturePublisher with the specified start catalog version, index, and predicate.
	 *
	 * This method configures a CapturePublisher with:
	 * 1. The test's unique ID
	 * 2. The specified start catalog version and index
	 * 3. The test's executor
	 * 4. The specified predicate for filtering mutations
	 * 5. A content mode that includes the mutation body
	 * 6. An empty callback for when the subscriber lags behind
	 * 7. A callback that verifies the publisher's ID and marks the test as finished when the publisher completes
	 *
	 * @param startCatalogVersion the catalog version from which to start capturing
	 * @param startIndex the index within the catalog version from which to start capturing
	 * @param predicate the predicate to use for filtering mutations
	 * @return a new CapturePublisher instance
	 */
	@Nonnull
	private CapturePublisher createCapturePublisher(long startCatalogVersion, int startIndex, @Nonnull MutationPredicate predicate) {
		return new CapturePublisher(
			this.id,
			startCatalogVersion,
			startIndex,
			this.executor,
			predicate,
			ChangeCaptureContent.BODY,
			cp -> {
				// Empty callback for when the subscriber lags behind
			},
			uuid -> {
				// Verify the publisher's ID and mark the test as finished
				assertEquals(this.id, uuid);
				this.finished.set(true);
			}
		);
	}

	/**
	 * Publishes a series of mutations to the specified publisher, failing the test if any are dropped.
	 *
	 * This method:
	 * 1. Creates and publishes 'transactionCount' pairs of mutations (transaction + entity)
	 * 2. Uses a timeout of 1 second for each offer operation
	 * 3. Uses the failOnDrop predicate to fail the test if any mutation is dropped
	 * 4. Closes the publisher when all mutations have been published
	 *
	 * Each pair consists of:
	 * - A TransactionMutation with a unique ID and incremental catalog version
	 * - An EntityUpsertMutation for a product entity
	 *
	 * @param mutationPublisher the publisher to which mutations will be published
	 * @param transactionCount the number of transaction pairs to publish
	 */
	private void publishMutationsWithFailOnLag(SubmissionPublisher<Mutation> mutationPublisher, int transactionCount) {
		for (int i = 0; i < transactionCount; i++) {
			// Publish a transaction mutation
			mutationPublisher.offer(
				new TransactionMutation(
					UUIDUtil.randomUUID(), 1L + i, 2, Long.MAX_VALUE, OffsetDateTime.MIN
				),
				1, TimeUnit.SECONDS,
				this.failOnDrop  // Fail the test if this mutation is dropped
			);

			// Publish an entity mutation
			mutationPublisher.offer(
				new EntityUpsertMutation(
					Entities.PRODUCT, 1, EntityExistence.MUST_NOT_EXIST
				),
				1, TimeUnit.SECONDS,
				this.failOnDrop  // Fail the test if this mutation is dropped
			);
		}

		// Close the publisher when all mutations have been published
		mutationPublisher.close();
	}

	/**
	 * Publishes a series of mutations to the specified publisher, starting from a specific catalog version and index.
	 *
	 * This method:
	 * 1. Creates and publishes mutations starting from the specified catalog version and index
	 * 2. Throws a SaturatedException if the publisher's buffer becomes full
	 * 3. Adds all published mutations to the provided list
	 * 4. Closes the publisher when all mutations have been published
	 *
	 * The method handles two types of mutations:
	 * - TransactionMutation (index 0 within each catalog version)
	 * - EntityUpsertMutation (index 1 within each catalog version)
	 *
	 * If startIndex is 1, the TransactionMutation for the starting catalog version is skipped.
	 *
	 * @param mutationPublisher the publisher to which mutations will be published
	 * @param transactionCount the total number of transactions to publish
	 * @param startCatalogVersion the catalog version from which to start publishing
	 * @param startIndex the index within the start catalog version from which to start publishing
	 * @param publishedMutations a list to which all published mutations will be added
	 * @throws SaturatedException if the publisher's buffer becomes full
	 */
	private static void publishMutations(
		@Nonnull SubmissionPublisher<Mutation> mutationPublisher,
		int transactionCount,
		long startCatalogVersion,
		int startIndex,
		@Nonnull List<Mutation> publishedMutations
	) {
		for (int i = Math.toIntExact(startCatalogVersion - 1); i < transactionCount; i++) {
			final long catalogVersion = i + 1;
			assertTrue(mutationPublisher.hasSubscribers());

			// If startIndex is 0, publish a transaction mutation (index 0)
			if (startIndex < 1) {
				final TransactionMutation txMutation = new TransactionMutation(
					UUIDUtil.randomUUID(), catalogVersion, 2, Long.MAX_VALUE, OffsetDateTime.MIN
				);
				mutationPublisher.offer(
					txMutation,
					(subscriber, mutation) -> {
						// If the buffer is full, throw an exception with the current position
						throw new SaturatedException(catalogVersion, 0);
					}
				);
				publishedMutations.add(txMutation);
			}

			// Always publish an entity mutation (index 1)
			final EntityUpsertMutation upsertMutation = new EntityUpsertMutation(
				Entities.PRODUCT, (int) catalogVersion, EntityExistence.MUST_NOT_EXIST
			);
			mutationPublisher.offer(
				upsertMutation,
				(subscriber, mutation) -> {
					// If the buffer is full, throw an exception with the current position
					throw new SaturatedException(catalogVersion, 1);
				}
			);
			publishedMutations.add(upsertMutation);

			// Reset startIndex for the next iteration
			startIndex = 0;
		}

		// Close the publisher when all mutations have been published
		mutationPublisher.close();
	}

	/**
	 * A test implementation of {@link Subscriber} that collects received items and provides
	 * methods to wait for completion or errors.
	 *
	 * This class is used in tests to:
	 * 1. Subscribe to a CapturePublisher and collect the items it publishes
	 * 2. Complete a future when a specified number of items have been received
	 * 3. Track errors and completion signals
	 * 4. Provide mechanisms to wait for errors or completion signals
	 *
	 * The subscriber implements a simple flow control strategy by requesting one item at a time,
	 * which allows testing backpressure handling in the publisher.
	 */
	private static class MockSubscriber implements Subscriber<ChangeCatalogCapture> {
		/**
		 * The number of items to receive before completing the future and canceling the subscription.
		 */
		private final int completeAtCount;

		/**
		 * List of items received from the publisher.
		 */
		@Getter private final List<ChangeCatalogCapture> items;

		/**
		 * Future that completes when the expected number of items have been received.
		 */
		@Getter private final CompletableFuture<Void> future = new CompletableFuture<>();

		/**
		 * The error received from the publisher, if any.
		 */
		@Getter private Throwable error;

		/**
		 * Flag indicating whether the publisher has signaled completion.
		 */
		@Getter private boolean completed;

		/**
		 * The subscription to the publisher.
		 */
		private Subscription subscription;

		/**
		 * Latch that counts down when an error is received.
		 */
		private final CountDownLatch errorLatch = new CountDownLatch(1);

		/**
		 * Latch that counts down when completion is signaled.
		 */
		private final CountDownLatch completionLatch = new CountDownLatch(1);

		/**
		 * Creates a new MockSubscriber that will complete after receiving the specified number of items.
		 *
		 * @param completeAtCount the number of items to receive before completing
		 */
		public MockSubscriber(int completeAtCount) {
			this.completeAtCount = completeAtCount;
			this.items = new ArrayList<>(completeAtCount);
		}

		/**
		 * Called when the Subscriber is subscribed to a Publisher.
		 * Stores the subscription and requests the first item.
		 *
		 * @param subscription the subscription to the publisher
		 */
		@Override
		public void onSubscribe(Subscription subscription) {
			this.subscription = subscription;
			subscription.request(1);  // Request the first item
		}

		/**
		 * Called when a new item is received from the publisher.
		 * Stores the item, requests the next item, and completes the future
		 * if the expected number of items have been received.
		 *
		 * @param item the received item
		 */
		@Override
		public void onNext(ChangeCatalogCapture item) {
			this.items.add(item);
			this.subscription.request(1);  // Request the next item
			if (this.items.size() == this.completeAtCount) {
				this.future.complete(null);  // Complete the future when all items are received
				this.subscription.cancel();  // Cancel the subscription
			}
		}

		/**
		 * Called when an error occurs in the publisher.
		 * Stores the error and counts down the error latch.
		 *
		 * @param throwable the error that occurred
		 */
		@Override
		public void onError(Throwable throwable) {
			this.error = throwable;
			this.errorLatch.countDown();
		}

		/**
		 * Called when the publisher signals completion.
		 * Sets the completed flag and counts down the completion latch.
		 */
		@Override
		public void onComplete() {
			this.completed = true;
			this.completionLatch.countDown();
		}

		/**
		 * Waits for an error to be received by this subscriber.
		 *
		 * @param timeout the maximum time to wait
		 * @param unit the time unit of the timeout argument
		 * @return true if an error was received before the timeout, false otherwise
		 * @throws InterruptedException if the current thread is interrupted while waiting
		 */
		public boolean awaitError(long timeout, TimeUnit unit) throws InterruptedException {
			return this.errorLatch.await(timeout, unit);
		}

		/**
		 * Waits for completion to be received by this subscriber.
		 *
		 * @param timeout the maximum time to wait
		 * @param unit the time unit of the timeout argument
		 * @return true if completion was received before the timeout, false otherwise
		 * @throws InterruptedException if the current thread is interrupted while waiting
		 */
		public boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
			return this.completionLatch.await(timeout, unit);
		}
	}

	/**
	 * A specialized {@link SubmissionPublisher} that can detect when its buffer is empty
	 * and trigger a callback to publish more items.
	 *
	 * This class is used in tests to simulate a publisher that:
	 * 1. Publishes items until its buffer is full
	 * 2. Detects when all published items have been consumed
	 * 3. Triggers a callback to publish more items when the buffer is empty
	 *
	 * This allows testing the CapturePublisher's ability to handle backpressure and
	 * resume publishing when the subscriber's buffer becomes available again.
	 */
	static class ReadLazilyPublisher extends SubmissionPublisher<Mutation> {
		/**
		 * The executor used to run the callback when the buffer is empty.
		 */
		private final Executor executor;

		/**
		 * Callback that is triggered when all published items have been consumed.
		 */
		private final Runnable onEmptyQueueCallback;

		/**
		 * Counter that tracks the number of items delivered to subscribers.
		 */
		private final AtomicLong delivered = new AtomicLong(0);

		/**
		 * Creates a new ReadLazilyPublisher with the specified executor, buffer capacity, and callback factory.
		 *
		 * @param executor the executor to use for asynchronous delivery
		 * @param maxBufferCapacity the maximum buffer capacity
		 * @param onEmptyQueueCallbackFactory factory that creates a callback to be triggered when the buffer is empty
		 */
		public ReadLazilyPublisher(Executor executor, int maxBufferCapacity, Function<ReadLazilyPublisher, Runnable> onEmptyQueueCallbackFactory) {
			super(executor, maxBufferCapacity);
			this.executor = executor;
			this.onEmptyQueueCallback = onEmptyQueueCallbackFactory.apply(this);
		}

		/**
		 * Wraps the subscriber in a EmptyAwareSubscriber wrapper that can detect when all items have been consumed.
		 *
		 * @param subscriber the subscriber to wrap
		 */
		@Override
		public void subscribe(Subscriber<? super Mutation> subscriber) {
			super.subscribe(new EmptyAwareSubscriber<>(subscriber, () -> this.executor.execute(this.onEmptyQueueCallback)));
		}

		/**
		 * Offers an item to subscribers with a custom drop handler that tracks depletion.
		 *
		 * @param item the item to offer
		 * @param onDrop the action to perform if the item is dropped
		 * @return the number of subscribers that accepted the item
		 */
		@Override
		public int offer(Mutation item, BiPredicate<Subscriber<? super Mutation>, ? super Mutation> onDrop) {
			final int result = super.offer(
				item,
				(subscriber, mutation) -> {
					// Notify the subscriber about the number of items delivered so far
					((EmptyAwareSubscriber<?,?>) subscriber).emptyOnDepletion(this.delivered.get());
					return onDrop.test(subscriber, mutation);
				}
			);
			this.delivered.incrementAndGet();
			return result;
		}

		/**
		 * Offers an item to subscribers with a timeout and a custom drop handler that tracks depletion.
		 *
		 * @param item the item to offer
		 * @param timeout the maximum time to wait
		 * @param unit the time unit of the timeout argument
		 * @param onDrop the action to perform if the item is dropped
		 * @return the number of subscribers that accepted the item
		 */
		@Override
		public int offer(Mutation item, long timeout, TimeUnit unit, BiPredicate<Subscriber<? super Mutation>, ? super Mutation> onDrop) {
			final int result = super.offer(
				item, timeout, unit,
				(subscriber, mutation) -> {
					// Notify the subscriber about the number of items delivered so far
					((EmptyAwareSubscriber<?,?>) subscriber).emptyOnDepletion(this.delivered.get());
					return onDrop.test(subscriber, mutation);
				}
			);
			this.delivered.incrementAndGet();
			return result;
		}

	}

	/**
	 * Exception thrown when a publisher's buffer is full and cannot accept more items.
	 *
	 * This exception is used to signal that a publisher's buffer is saturated and to
	 * provide information about the catalog version and index at which the saturation occurred.
	 * This information is used to resume publishing from that point when the buffer becomes
	 * available again.
	 */
	@RequiredArgsConstructor
	@Getter
	static class SaturatedException extends RuntimeException {
		@Serial private static final long serialVersionUID = -3995017962840070194L;
		/**
		 * The catalog version at which the publisher's buffer became saturated.
		 */
		private final long catalogVersion;

		/**
		 * The index within the catalog version at which the publisher's buffer became saturated.
		 */
		private final int index;
	}

}
