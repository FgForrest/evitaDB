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

import com.github.javafaker.Faker;
import io.evitadb.api.CatalogState;
import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.executor.ImmediateExecutorService;
import io.evitadb.dataType.ContainerType;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.generator.DataGenerator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.utils.ReflectionUtils.getFieldValue;
import static io.evitadb.test.utils.ReflectionUtils.getNonnullFieldValue;
import static io.evitadb.test.utils.ReflectionUtils.setFieldValue;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.CDC;

/**
 * This test class verifies the functionality of the {@link CatalogChangeObserver} which is responsible for
 * capturing and publishing changes made to an Evita catalog.
 *
 * The Change Data Capture (CDC) mechanism allows clients to subscribe to a stream of changes
 * occurring in the database, enabling real-time data synchronization and event-driven architectures.
 *
 * This test specifically:
 * 1. Sets up a test catalog with sample brand entities
 * 2. Creates a {@link CatalogChangeObserver} instance
 * 3. Registers an observer to capture all mutations (even historical ones)
 * 4. Verifies that the observer correctly receives and publishes the expected number of mutations
 *
 * The test uses {@link MockCatalogChangeSubscriber} to collect and verify the published changes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see CatalogChangeObserver
 * @see MockCatalogChangeSubscriber
 * @see ChangeCapturePublisher
 */
@DisplayName("CatalogChangeObserver should")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(ENGINE)
@Tag(CDC)
class CatalogChangeObserverTest implements EvitaTestSupport {
	/**
	 * Name of the dataset used for this test. This identifier is used by the test framework
	 * to manage test data lifecycle.
	 */
	private static final String CDC_TRANSACTIONS = "cdcTransactions";

	/**
	 * Seed value for the random data generator to ensure reproducible test data.
	 */
	private static final int SEED = 40;

	/**
	 * Data generator instance used to create sample entities for testing.
	 * This generator creates random but consistent test data based on the seed value.
	 */
	protected final DataGenerator dataGenerator = new DataGenerator.Builder()
		.build();

	/**
	 * A function that always returns null when selecting an entity.
	 * This is used to indicate that no existing entity should be selected during data generation,
	 * ensuring that only new entities are created.
	 */
	final BiFunction<String, Faker, Integer> noEntityPicker = (s, faker) -> null;

	/**
	 * Sets up the test data for CDC (Change Data Capture) testing.
	 *
	 * This method is annotated with {@link DataSet} which tells the test framework to:
	 * 1. Use the specified dataset name for this test
	 * 2. Destroy the dataset after all tests in the class are complete
	 * 3. Expect the catalog to be in WARMING_UP state initially
	 *
	 * The method performs two main operations:
	 * 1. Switches the catalog to transactional mode (required for CDC)
	 * 2. Creates 20 brand entities with random but reproducible data
	 *
	 * @param evita the Evita database instance injected by the test framework
	 * @return a list of references to the created entities
	 */
	@DataSet(value = CDC_TRANSACTIONS, destroyAfterClass = true, expectedCatalogState = CatalogState.WARMING_UP, readOnly = false)
	protected SealedEntitySchema setUp(Evita evita) {
		// switch to the transactional mode to be able to set-up CDC
		evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);

		// create some entities
		return evita.updateCatalog(TEST_CATALOG, session -> {
			final SealedEntitySchema brandSchema = this.dataGenerator.getSampleBrandSchema(session);
			this.dataGenerator.generateEntities(
					brandSchema,
					this.noEntityPicker,
					SEED
				)
				.limit(20)
				.forEach(session::upsertEntity);
			return brandSchema;
		});
	}

	/**
	 * Tests that the {@link CatalogChangeObserver} correctly registers an observer and publishes
	 * mutations to subscribers.
	 *
	 * This test:
	 * 1. Creates a new {@link CatalogChangeObserver} with an immediate executor service
	 * (which executes tasks in the calling thread)
	 * 2. Notifies the observer about the catalog being present in the live view
	 * 3. Creates a request to capture all mutations since version 0 (the beginning)
	 * 4. Registers an observer with this request
	 * 5. Creates a {@link MockCatalogChangeSubscriber} that expects to receive 40 items
	 * 6. Subscribes the mock subscriber to the publisher
	 * 7. Verifies that the subscriber received exactly 40 items
	 *
	 * The test uses try-with-resources to ensure the publisher is properly closed after the test.
	 *
	 * @param evita the Evita database instance with the test dataset already loaded
	 */
	@Test
	@DisplayName("receive all existing mutations from the beginning")
	void shouldRegisterObserverAndReceiveAllExistingMutations(@UseDataSet(value = CDC_TRANSACTIONS) Evita evita) {
		final Catalog catalog = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();

		// Get and reconfigure the CatalogChangeObserver
		final CatalogChangeObserver tested = (CatalogChangeObserver) catalog.getTransactionManager().getChangeObserver();

		// Notify the observer about the catalog being present in the live view
		// This is required for the observer to start reading mutations
		tested.notifyCatalogPresentInLiveView(catalog);

		// Create a request to capture all mutations since version 0 (the beginning)
		// This ensures we get all historical mutations, not just new ones
		final ChangeCatalogCaptureRequest catchAllRequest = ChangeCatalogCaptureRequest.builder()
			.sinceVersion(0L)
			.content(ChangeCaptureContent.BODY)
			.criteria(
				ChangeCatalogCaptureCriteria.builder()
					.dataArea(builder -> builder.containerType(ContainerType.ENTITY).operation(Operation.UPSERT))
					.build()
			)
			.build();

		// Use try-with-resources to ensure the publisher is properly closed after the test
		try (
			final ChangeCapturePublisher<ChangeCatalogCapture> publisher = tested.registerObserver(catchAllRequest)
		) {
			final MockCatalogChangeSubscriber subscriber = new MockCatalogChangeSubscriber();

			// Subscribe to the publisher to start receiving mutations
			publisher.subscribe(subscriber);

			// Verify that the subscriber received exactly 40 items
			// For each upserted entity there are 2 mutations - entity creation and attribute update
			assertEquals(20, subscriber.getItems().size(), "Should receive 20 mutations (20 entities)");
		}
	}

	/**
	 * Tests that the {@link CatalogChangeObserver} correctly filters mutations based on criteria.
	 *
	 * This test:
	 * 1. Creates a new {@link CatalogChangeObserver} with an immediate executor service
	 * 2. Notifies the observer about the catalog being present in the live view
	 * 3. Creates a request to capture mutations since version 2 and index 100
	 * 4. Applies filtering criteria to only capture attribute mutations with name "code" and operation UPSERT
	 * 5. Registers an observer with this request
	 * 6. Creates a {@link MockCatalogChangeSubscriber} that expects to receive 9 items
	 * 7. Subscribes the mock subscriber to the publisher
	 * 8. Verifies that the subscriber received exactly 9 items
	 *
	 * @param evita the Evita database instance with the test dataset already loaded
	 */
	@Test
	@DisplayName("filter and receive only specific mutations")
	void shouldRegisterObserverAndReceiveAFewRecentMutations(@UseDataSet(CDC_TRANSACTIONS) Evita evita) {
		final Catalog catalog = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();

		// Get and reconfigure the CatalogChangeObserver
		final CatalogChangeObserver tested = (CatalogChangeObserver) catalog.getTransactionManager().getChangeObserver();

		// Notify the observer about the catalog being present in the live view
		// This is required for the observer to start reading mutations
		tested.notifyCatalogPresentInLiveView(catalog);

		// Create a request to capture mutations since version 2 and index 14
		// with specific filtering criteria
		final ChangeCatalogCaptureRequest catchAllRequest = ChangeCatalogCaptureRequest.builder()
			.sinceVersion(2L)
			.sinceIndex(14)
			.content(ChangeCaptureContent.BODY)
			.criteria(
				ChangeCatalogCaptureCriteria.builder()
					.dataArea(
						builder -> builder.containerType(ContainerType.ATTRIBUTE)
							.containerName("code")
							.operation(Operation.UPSERT)
					)
					.build()
			)
			.build();

		// Use try-with-resources to ensure the publisher is properly closed after the test
		try (
			final ChangeCapturePublisher<ChangeCatalogCapture> publisher = tested.registerObserver(catchAllRequest)
		) {
			final MockCatalogChangeSubscriber subscriber = new MockCatalogChangeSubscriber();

			// Subscribe to the publisher to start receiving mutations
			publisher.subscribe(subscriber);

			// Verify that the subscriber received exactly 9 items
			// These are the attribute mutations that match the specified criteria
			assertEquals(9, subscriber.getItems().size(), "Should receive 9 filtered attribute mutations");
		}
	}

	/**
	 * Tests that the {@link CatalogChangeObserver} correctly captures only new mutations
	 * that occur after the observer is registered.
	 *
	 * This test:
	 * 1. Creates a new {@link CatalogChangeObserver} with an immediate executor service
	 * 2. Notifies the observer about the catalog being present in the live view
	 * 3. Creates a request to capture mutations starting from the next version after the current catalog version
	 * 4. Applies filtering criteria to only capture attribute mutations with name "code" and operation UPSERT
	 * 5. Registers an observer with this request
	 * 6. Creates a {@link MockCatalogChangeSubscriber} that expects to receive 10 items
	 * 7. Subscribes the mock subscriber to the publisher
	 * 8. Creates 10 new entities in the catalog
	 * 9. Verifies that the subscriber received exactly 1 item (the code attribute mutation)
	 * 10. Verifies that the entity primary key in the captured mutation is greater than 20
	 * (confirming it's from the newly created entities)
	 *
	 * @param evita       the Evita database instance with the test dataset already loaded
	 * @param brandSchema the brand schema created during test setup
	 */
	@UseDataSet(value = CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("capture only new mutations after registration")
	void shouldRegisterObserverAndObtainOnlyNewMutations(@Nonnull Evita evita, @Nonnull SealedEntitySchema brandSchema) {
		final Catalog catalog = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();

		// Get and reconfigure the CatalogChangeObserver
		final CatalogChangeObserver tested = (CatalogChangeObserver) catalog.getTransactionManager().getChangeObserver();

		// Notify the observer about the catalog being present in the live view
		// This is required for the observer to start reading mutations
		tested.notifyCatalogPresentInLiveView(catalog);

		// Create a request to capture only new mutations that occur after the current catalog version
		final ChangeCatalogCaptureRequest catchAllRequest = ChangeCatalogCaptureRequest.builder()
			.sinceVersion(catalog.getVersion() + 1)
			.content(ChangeCaptureContent.BODY)
			.criteria(
				ChangeCatalogCaptureCriteria.builder()
					.dataArea(
						builder -> builder.containerType(ContainerType.ATTRIBUTE)
							.containerName("code")
							.operation(Operation.UPSERT)
					)
					.build()
			)
			.build();

		// Use try-with-resources to ensure the publisher is properly closed after the test
		try (
			final ChangeCapturePublisher<ChangeCatalogCapture> publisher = tested.registerObserver(catchAllRequest)
		) {
			final MockCatalogChangeSubscriber subscriber = new MockCatalogChangeSubscriber();

			// Subscribe to the publisher to start receiving mutations
			publisher.subscribe(subscriber);

			// Create 10 new entities in the catalog
			// These are generated with indices 20-29 (after skipping the first 20)
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					return this.dataGenerator.generateEntities(
							brandSchema,
							this.noEntityPicker,
							SEED
						)
						.skip(20)
						.limit(10)
						.map(session::upsertEntity)
						.toList();
				}
			);

			// Verify that the subscriber received exactly 1 item
			// Due to the filtering criteria (only code attribute mutations), we only get one mutation per entity
			assertEquals(10, subscriber.getItems().size(), "Should receive 10 code attribute mutation from the newly created entities");

			// Verify that each captured mutation has a valid entity primary key
			// and that the key is greater than 20, confirming it's from our newly created entities
			for (ChangeCatalogCapture capture : subscriber.getItems()) {
				assertNotNull(capture.entityPrimaryKey(), "Entity primary key should not be null");
				assertTrue(capture.entityPrimaryKey() > 20, "Entity primary key should be greater than 20");
			}
		}
	}

	/**
	 * Tests that the {@link CatalogChangeObserver} correctly handles multiple subscribers
	 * with different capture conditions.
	 *
	 * This test:
	 * 1. Creates a new {@link CatalogChangeObserver} with an immediate executor service
	 * 2. Notifies the observer about the catalog being present in the live view
	 * 3. Creates shared capture criteria but with different versions for different subscribers
	 * 4. Registers multiple subscribers at different times:
	 *    - Subscriber 1: Consumes entire WAL
	 *    - Subscriber 2: Consumes part of existing WAL
	 *    - Subscriber 3: Registered after new mutations, consumes part of old WAL and all new mutations
	 *    - Subscriber 4: Registered after new mutations, consumes only part of them
	 * 5. Verifies each subscriber receives the expected data
	 * 6. Generates another round of mutations
	 * 7. Verifies all subscribers receive the same content from the new batch of mutations
	 *
	 * @param evita       the Evita database instance with the test dataset already loaded
	 * @param brandSchema the brand schema created during test setup
	 */
	@UseDataSet(value = CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("handle multiple subscribers with different capture conditions")
	void shouldHandleMultipleSubscribersWithDifferentCaptureConditions(@Nonnull Evita evita, @Nonnull SealedEntitySchema brandSchema) throws ExecutionException, InterruptedException {
		final Catalog catalog = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();

		// Get and reconfigure the CatalogChangeObserver
		final CatalogChangeObserver tested = (CatalogChangeObserver) catalog.getTransactionManager().getChangeObserver();

		// Notify the observer about the catalog being present in the live view
		tested.notifyCatalogPresentInLiveView(catalog);

		// Create shared capture criteria for all subscribers
		final ChangeCatalogCaptureCriteria sharedCriteria = ChangeCatalogCaptureCriteria.builder()
			.dataArea(builder -> builder.containerType(ContainerType.ENTITY).operation(Operation.UPSERT))
			.build();

		// 1. Subscriber that consumes entire WAL (since version 0)
		final ChangeCatalogCaptureRequest entireWalRequest = ChangeCatalogCaptureRequest.builder()
			.sinceVersion(0L)
			.content(ChangeCaptureContent.BODY)
			.criteria(sharedCriteria)
			.build();

		// 2. Subscriber that consumes part of existing WAL (since version 2)
		final ChangeCatalogCaptureRequest partialWalRequest = ChangeCatalogCaptureRequest.builder()
			.sinceVersion(2L)
			.sinceIndex(13)
			.content(ChangeCaptureContent.BODY)
			.criteria(sharedCriteria)
			.build();

		// Register the first two subscribers
		try (
			final ChangeCapturePublisher<ChangeCatalogCapture> entireWalPublisher = tested.registerObserver(entireWalRequest);
			final ChangeCapturePublisher<ChangeCatalogCapture> partialWalPublisher = tested.registerObserver(partialWalRequest)
		) {
			// Create subscribers with expected counts
			// For each upserted entity there are 2 mutations - entity creation and attribute update
			final MockCatalogChangeSubscriber entireWalSubscriber = new MockCatalogChangeSubscriber();
			final MockCatalogChangeSubscriber partialWalSubscriber = new MockCatalogChangeSubscriber();

			// Subscribe to start receiving mutations
			entireWalPublisher.subscribe(entireWalSubscriber);
			partialWalPublisher.subscribe(partialWalSubscriber);

			// Verify initial subscribers received expected data
			assertEquals(20, entireWalSubscriber.getItems().size(), "Should receive 40 mutations (20 entities)");
			assertEquals(10, partialWalSubscriber.getItems().size(), "Should receive 10 mutations (10 entities)");

			// Create 10 new entities in the catalog
			final CommitVersions commitVersions = evita.updateCatalogAsync(
					TEST_CATALOG,
					session -> {
						this.dataGenerator.generateEntities(
								brandSchema,
								this.noEntityPicker,
								SEED
							)
							.skip(20)
							.limit(10)
							.forEach(session::upsertEntity);
					},
					CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
				)
				.onChangesVisible()
				.toCompletableFuture()
				.get();

			// 3. Subscriber that is registered after new mutations and consumes part of old WAL and all new mutations
			final ChangeCatalogCaptureRequest partialOldAndNewRequest = ChangeCatalogCaptureRequest.builder()
				.sinceVersion(2L)
				.sinceIndex(13)
				.content(ChangeCaptureContent.BODY)
				.criteria(sharedCriteria)
				.build();

			// 4. Subscriber that is registered after new mutations and consumes only part of them
			final ChangeCatalogCaptureRequest onlyNewPartialRequest = ChangeCatalogCaptureRequest.builder()
				.sinceVersion(commitVersions.catalogVersion())
				.content(ChangeCaptureContent.BODY)
				.criteria(sharedCriteria)
				.build();

			// Register the next two subscribers
			try (
				final ChangeCapturePublisher<ChangeCatalogCapture> partialOldAndNewPublisher = tested.registerObserver(partialOldAndNewRequest);
				final ChangeCapturePublisher<ChangeCatalogCapture> onlyNewPartialPublisher = tested.registerObserver(onlyNewPartialRequest)
			) {
				// Create subscribers with expected counts
				final MockCatalogChangeSubscriber partialOldAndNewSubscriber = new MockCatalogChangeSubscriber();
				final MockCatalogChangeSubscriber onlyNewPartialSubscriber = new MockCatalogChangeSubscriber();

				// Subscribe to start receiving mutations
				partialOldAndNewPublisher.subscribe(partialOldAndNewSubscriber);
				onlyNewPartialPublisher.subscribe(onlyNewPartialSubscriber);

				// Verify new subscribers received expected data
				assertEquals(20, partialOldAndNewSubscriber.getItems().size(), "Should receive 20 mutations (10 old + 10 new entities)");
				assertEquals(10, onlyNewPartialSubscriber.getItems().size(), "Should receive 10 mutations (10 entities)");

				// Verify initial subscribers received new data as well
				assertEquals(20 + 10, entireWalSubscriber.getItems().size(), "Should receive 30 mutations (10 old + 20 new entities)");
				assertEquals(10 + 10, partialWalSubscriber.getItems().size(), "Should receive 20 mutations (10 old + 10 new entities)");

				// Create another 5 new entities in the catalog
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						return this.dataGenerator.generateEntities(
								brandSchema,
								this.noEntityPicker,
								SEED
							)
							.skip(30)
							.limit(5)
							.map(session::upsertEntity)
							.toList();
					}
				);

				// Verify all subscribers received the same data from the second batch
				assertEquals(20 + 10 + 5, entireWalSubscriber.getItems().size(), "Should receive 5 in new batch on top of the previous 30");
				assertEquals(10 + 10 + 5, partialWalSubscriber.getItems().size(), "Should receive 5 in new batch on top of the previous 20");
				assertEquals(20 + 5, partialOldAndNewSubscriber.getItems().size(), "Should receive 5 in new batch on top of the previous 20");
				assertEquals(10 + 5, onlyNewPartialSubscriber.getItems().size(), "Should receive 5 in new batch on top of the previous 10");

				// Verify that all subscribers received entity IDs from the second batch (IDs > 30)
				verifySubscriberReceivedEntityIdsGreaterThan(entireWalSubscriber, 0);
				verifySubscriberReceivedEntityIdsGreaterThan(partialWalSubscriber, 10);
				verifySubscriberReceivedEntityIdsGreaterThan(partialOldAndNewSubscriber, 10);
				verifySubscriberReceivedEntityIdsGreaterThan(onlyNewPartialSubscriber, 20);
			}
		}

		final Map<ChangeCatalogCriteriaBundle, ChangeCatalogCaptureSharedPublisher> uniquePublishers = getNonnullFieldValue(tested, "uniquePublishers");
		for (ChangeCatalogCaptureSharedPublisher publisher : uniquePublishers.values()) {
			if (!publisher.isClosed()) {
				ChangeCaptureRingBuffer<ChangeCatalogCapture> lastCaptures = getNonnullFieldValue(publisher, "lastCaptures");
				ChangeCatalogCapture[] workspace = getNonnullFieldValue(lastCaptures, "workspace");
				for (ChangeCatalogCapture capture : workspace) {
					assertNull(capture, "All captures should be null after the test");
				}
			}
		}
	}

	/**
	 * Tests that the {@link CatalogChangeObserver} correctly unregisters an observer.
	 *
	 * This test:
	 * 1. Creates a new {@link CatalogChangeObserver} with an immediate executor service
	 * 2. Notifies the observer about the catalog being present in the live view
	 * 3. Registers an observer to capture all mutations
	 * 4. Verifies the observer receives mutations when entities are created
	 * 5. Unregisters the observer using its UUID
	 * 6. Creates more entities
	 * 7. Verifies the unregistered observer doesn't receive new mutations
	 *
	 * @param evita       the Evita database instance with the test dataset already loaded
	 * @param brandSchema the brand schema created during test setup
	 */
	@UseDataSet(value = CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("unregister an observer correctly")
	void shouldUnregisterObserverCorrectly(@Nonnull Evita evita, @Nonnull SealedEntitySchema brandSchema) {
		final Catalog catalog = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();

		// Get and reconfigure the CatalogChangeObserver
		final CatalogChangeObserver tested = (CatalogChangeObserver) catalog.getTransactionManager().getChangeObserver();

		// Notify the observer about the catalog being present in the live view
		tested.notifyCatalogPresentInLiveView(catalog);

		// Create a request to capture mutations
		final ChangeCatalogCaptureRequest request = ChangeCatalogCaptureRequest.builder()
			.sinceVersion(catalog.getVersion() + 1)
			.content(ChangeCaptureContent.BODY)
			.criteria(
				ChangeCatalogCaptureCriteria.builder()
					.dataArea(builder -> builder.containerType(ContainerType.ENTITY).operation(Operation.UPSERT))
					.build()
			)
			.build();

		// Register an observer
		final ChangeCapturePublisher<ChangeCatalogCapture> publisher = tested.registerObserver(request);
		final MockCatalogChangeSubscriber subscriber = new MockCatalogChangeSubscriber();
		publisher.subscribe(subscriber);

		// Create 5 new entities
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				return this.dataGenerator.generateEntities(
						brandSchema,
						this.noEntityPicker,
						SEED
					)
					.skip(20)
					.limit(5)
					.map(session::upsertEntity)
					.toList();
			}
		);

		// Verify the subscriber received mutations
		// For each upserted entity there are 2 mutations - entity creation and attribute update
		assertEquals(5, subscriber.getItems().size(), "Should receive 5 mutations (5 entities)");

		// Close the publisher, which will unregister the observer
		assertTrue(tested.unregisterObserver(subscriber.getSubscriptionId()));

		// The subscription should be completed
		assertTrue(subscriber.isClosed());
		assertFalse(subscriber.isCompleted());

		// Try to unregister with a random UUID (should fail since the observer is already unregistered)
		assertFalse(tested.unregisterObserver(UUID.randomUUID()), "Unregistering a non-existent observer should return false");

		// Create 5 more entities
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				return this.dataGenerator.generateEntities(
						brandSchema,
						this.noEntityPicker,
						SEED
					)
					.skip(25)
					.limit(5)
					.map(session::upsertEntity)
					.toList();
			}
		);

		// Verify the subscriber still has only the original mutations
		assertEquals(5, subscriber.getItems().size(), "Should still have only 5 mutations after unregistering");
	}

	/**
	 * A subscriber that terminates from inside its own {@code onSubscribe} must be fully unregistered by the time
	 * {@code subscribe} returns.
	 *
	 * This used to be the opposite assertion. {@code Subscriber#onSubscribe} was called by
	 * {@code DefaultChangeCaptureSubscription}'s constructor, which runs inside the
	 * {@code ConcurrentHashMap#computeIfAbsent} that registers the subscription - so a subscriber that requested
	 * from there (the engine's own {@code EngineStatisticsPublisher} does, and so does the gRPC one) drove the
	 * subscription to a terminal signal before the entry existed. The release then found nothing to remove and
	 * returned false into a Consumer that discards it, leaving the registration behind for the periodic sweep to
	 * collect.
	 *
	 * The publisher now publishes the entry first and calls {@code DefaultChangeCaptureSubscription#activate()}
	 * afterwards, so the release always finds its own entry and unregisters through the ordinary path. Nothing is
	 * left over, and this test pins that: the counts must already be clean before any sweep runs.
	 *
	 * The sweep still exists for the case it cannot reach any other way - a release the capture executor refused -
	 * which {@code ChangeCaptureSubscriptionFillFailureTest} covers directly.
	 *
	 * @param evita the Evita database instance with the test dataset already loaded
	 */
	@UseDataSet(value = CDC_TRANSACTIONS)
	@Test
	@DisplayName("unregister a subscriber that terminates from inside onSubscribe, without waiting for the sweep")
	void shouldUnregisterASubscriberThatTerminatesFromInsideOnSubscribe(Evita evita) {
		final Catalog catalog = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();

		final ChangeCatalogCaptureSharedPublisher publisher = new ChangeCatalogCaptureSharedPublisher(
			catalog,
			new ImmediateExecutorService(),
			16,
			16,
			ChangeCatalogCriteriaBundle.CATCH_ALL,
			capture -> {
			},
			closingPublisher -> {
			}
		);

		final long trackedVersion = catalog.getVersion() + 1;
		// a non-positive request from inside onSubscribe terminates the subscription immediately, which is the
		// ordering this test exists for
		publisher.subscribe(
			new Subscriber<ChangeCatalogCapture>() {
				@Override
				public void onSubscribe(Subscription subscription) {
					subscription.request(-1);
				}

				@Override
				public void onNext(ChangeCatalogCapture item) {
				}

				@Override
				public void onError(Throwable throwable) {
				}

				@Override
				public void onComplete() {
				}
			},
			new WalPointerWithContent(trackedVersion, 0, ChangeCaptureContent.BODY)
		);

		final ConcurrentSkipListMap<Long, Integer> versionSubscribersCount =
			getNonnullFieldValue(publisher, "versionSubscribersCount");

		assertEquals(
			0,
			publisher.getSubscribersCount(),
			"A subscription that terminated from inside onSubscribe was left registered. The release runs after " +
				"the entry is published, so it must find and remove its own entry rather than depending on the " +
				"periodic sweep to collect it later."
		);
		assertNull(
			versionSubscribersCount.get(trackedVersion),
			"The version slot survived a registration that terminated immediately. It is incremented before the " +
				"subscription is constructed, so it is only ever given back through the subscribers entry - and " +
				"the lowest key of this map is what anchors the ring buffer."
		);

		publisher.cleanFinishedSubscriptions();

		assertEquals(
			0,
			publisher.getSubscribersCount(),
			"The sweep changed a state that was already clean."
		);
	}

	/**
	 * Unsubscribing one of several subscribers must give back exactly one of the tracked version's slots.
	 *
	 * `unsubscribe` cancels the departing subscription, and cancelling releases it, and the release calls back
	 * into `unsubscribe` through the publisher's own `onCancellation` hook - so the body runs twice for one
	 * departing subscriber. Only the call that wins the removal from the subscribers map may do the accounting:
	 * the re-entrant inner call removes the entry and gives the slot back, and the outer call's `remove` then
	 * returns null and must do nothing. Removing before the cancel instead would be the other way to order this,
	 * and is not available - `unsubscribe` has to stay a `get` first, because removing a key whose mapping
	 * function is still running throws IllegalStateException("Recursive update").
	 *
	 * A single subscriber hides this, because two decrements of `{V: 1}` both land on "remove the key" and the
	 * result is right by accident. It takes two subscribers sharing a tracked version to see it: `{V: 2}` becomes
	 * `{}` instead of `{V: 1}`, and the survivor's position stops being tracked at all. That map's lowest key is
	 * what stops the ring buffer being trimmed, so the survivor can lose captures it has not read and fall back
	 * to reading the write-ahead log - or stall, where retention has already reclaimed that segment.
	 *
	 * @param evita the Evita database instance with the test dataset already loaded
	 */
	@UseDataSet(value = CDC_TRANSACTIONS)
	@Test
	@DisplayName("give back exactly one version slot when one of two subscribers unsubscribes")
	void shouldReleaseOnlyTheDepartingSubscribersVersionSlot(Evita evita) {
		final Catalog catalog = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();

		final ChangeCatalogCaptureSharedPublisher publisher = new ChangeCatalogCaptureSharedPublisher(
			catalog,
			new ImmediateExecutorService(),
			16,
			16,
			ChangeCatalogCriteriaBundle.CATCH_ALL,
			capture -> {
			},
			closingPublisher -> {
			}
		);

		// both subscribers sit at the same version, and neither requests anything, so both stay live - the
		// re-entrancy only fires for a subscription `unsubscribe` still has to cancel
		final long trackedVersion = catalog.getVersion() + 1;
		final WalPointerWithContent specification =
			new WalPointerWithContent(trackedVersion, 0, ChangeCaptureContent.BODY);
		final DefaultChangeCaptureSubscription<ChangeCatalogCapture> departing =
			publisher.subscribe(new SilentCatalogSubscriber(), specification);
		publisher.subscribe(new SilentCatalogSubscriber(), specification);

		final ConcurrentSkipListMap<Long, Integer> versionSubscribersCount =
			getNonnullFieldValue(publisher, "versionSubscribersCount");
		assertEquals(
			2,
			versionSubscribersCount.get(trackedVersion),
			"Both subscribers were expected to be tracked at the same version; without that this test cannot " +
				"distinguish one decrement from two."
		);

		publisher.unsubscribe(departing.getSubscriptionId());

		assertEquals(
			1,
			versionSubscribersCount.get(trackedVersion),
			"One subscriber left and the version lost both of its slots. `unsubscribe` cancelled before " +
				"removing, so the release re-entered it while the entry was still in the map and the " +
				"bookkeeping ran twice. The surviving subscriber is now untracked, and the lowest key of this " +
				"map is the only thing stopping the ring buffer being trimmed past captures it still needs."
		);
		assertEquals(
			1,
			publisher.getSubscribersCount(),
			"The surviving subscription was removed along with the departing one."
		);
	}

	/**
	 * A subscriber that accepts its subscription and asks for nothing, so the subscription stays live for the
	 * duration of the test rather than terminating itself from inside {@code onSubscribe}.
	 */
	private static class SilentCatalogSubscriber implements Subscriber<ChangeCatalogCapture> {
		@Override
		public void onSubscribe(Subscription subscription) {
		}

		@Override
		public void onNext(ChangeCatalogCapture item) {
		}

		@Override
		public void onError(Throwable throwable) {
		}

		@Override
		public void onComplete() {
		}
	}

	/**
	 * A subscriber that cancels from inside its own {@code onSubscribe} must not blow up the registration.
	 *
	 * `cancel()` releases the registration inline, and the release calls straight back into `unsubscribe`. The
	 * publisher hands the subscription to the subscriber through
	 * `DefaultChangeCaptureSubscription#activate()`, after the `computeIfAbsent` that registers it has published
	 * the entry, so that re-entrant call finds its own entry and removes it through the ordinary path.
	 *
	 * It used to happen inside the mapping function, because the constructor made the `onSubscribe` call. Removing
	 * a key there throws `IllegalStateException("Recursive update")` whenever the bin holds only the reservation,
	 * which for random UUID keys is nearly always, and the escape was the worse failure by far:
	 * `versionSubscribersCount` is incremented before the subscription is constructed, so it left that version
	 * incremented with no `subscribers` entry ever installed - and the periodic sweep walks `subscribers`, so
	 * nothing could ever release it. The ring buffer stayed anchored there for the lifetime of the process.
	 *
	 * This is reachable from the gRPC transport, not only in tests: `AbstractChangeCaptureSubscriber#onSubscribe`
	 * cancels synchronously when the stream was already finalized, which happens when a client disconnects during
	 * the hop between the service thread registering the cancel handler and the request executor running
	 * `subscribe()`.
	 *
	 * @param evita the Evita database instance with the test dataset already loaded
	 */
	@UseDataSet(value = CDC_TRANSACTIONS)
	@Test
	@DisplayName("survive a subscriber that cancels from inside onSubscribe")
	void shouldSurviveASubscriberThatCancelsFromInsideOnSubscribe(Evita evita) {
		final Catalog catalog = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();

		final ChangeCatalogCaptureSharedPublisher publisher = new ChangeCatalogCaptureSharedPublisher(
			catalog,
			new ImmediateExecutorService(),
			16,
			16,
			ChangeCatalogCriteriaBundle.CATCH_ALL,
			capture -> {
			},
			closingPublisher -> {
			}
		);

		final long trackedVersion = catalog.getVersion() + 1;
		assertDoesNotThrow(
			() -> publisher.subscribe(
				new CancellingCatalogSubscriber(),
				new WalPointerWithContent(trackedVersion, 0, ChangeCaptureContent.BODY)
			),
			"Registering a subscriber that cancels from its own onSubscribe blew up the registration itself. " +
				"The cancel releases inline and calls back into unsubscribe while computeIfAbsent is still " +
				"computing this key, so unsubscribe must not touch the map there. The escape also strands the " +
				"version count that was incremented just above the constructor, with no subscribers entry for " +
				"the sweep to find - a pinned ring buffer for the lifetime of the process."
		);

		final ConcurrentSkipListMap<Long, Integer> versionSubscribersCount =
			getNonnullFieldValue(publisher, "versionSubscribersCount");

		publisher.cleanFinishedSubscriptions();

		assertEquals(
			0,
			publisher.getSubscribersCount(),
			"The sweep did not release a subscription that terminated before it was registered."
		);
		assertNull(
			versionSubscribersCount.get(trackedVersion),
			"The version slot survived the sweep. It was incremented before the subscription was constructed, " +
				"so it is only ever given back through the subscribers entry - if the sweep cannot reach it, " +
				"the ring buffer stays anchored at this version permanently."
		);
	}

	/**
	 * A subscriber that cancels the moment it is handed its subscription, which the publisher does once the
	 * registration is published.
	 */
	private static class CancellingCatalogSubscriber implements Subscriber<ChangeCatalogCapture> {
		@Override
		public void onSubscribe(Subscription subscription) {
			subscription.cancel();
		}

		@Override
		public void onNext(ChangeCatalogCapture item) {
		}

		@Override
		public void onError(Throwable throwable) {
		}

		@Override
		public void onComplete() {
		}
	}

	/**
	 * A subscriber whose {@code onSubscribe} throws must leave nothing of its registration behind.
	 *
	 * The publisher increments {@code versionSubscribersCount} inside the {@code computeIfAbsent} that registers
	 * the subscription - the system publisher installs two per-subscriber filters there as well - and only then
	 * hands the subscription to the subscriber. An escaping {@code onSubscribe} would otherwise leave that
	 * bookkeeping applied with no entry behind it: the periodic sweep walks {@code subscribers}, so nothing could
	 * ever find it, the ring buffer would stay anchored at that version, and a client reconnecting in a loop would
	 * add another orphan on every attempt. On the system side that is permanent - it is a process-lifetime
	 * singleton that is never retired.
	 *
	 * @param evita the Evita database instance with the test dataset already loaded
	 */
	@UseDataSet(value = CDC_TRANSACTIONS)
	@Test
	@DisplayName("roll the whole registration back when onSubscribe throws")
	void shouldRollBackTheRegistrationWhenOnSubscribeThrows(Evita evita) {
		final Catalog catalog = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();

		final ChangeCatalogCaptureSharedPublisher publisher = new ChangeCatalogCaptureSharedPublisher(
			catalog,
			new ImmediateExecutorService(),
			16,
			16,
			ChangeCatalogCriteriaBundle.CATCH_ALL,
			capture -> {
			},
			closingPublisher -> {
			}
		);

		final long trackedVersion = catalog.getVersion() + 1;
		final IllegalStateException onSubscribeFailure =
			new IllegalStateException("subscriber refused its own subscription");

		final IllegalStateException propagated = assertThrows(
			IllegalStateException.class,
			() -> publisher.subscribe(
				new ThrowingCatalogSubscriber(onSubscribeFailure),
				new WalPointerWithContent(trackedVersion, 0, ChangeCaptureContent.BODY)
			),
			"A subscriber that refuses its subscription must not have its failure swallowed - the caller is the " +
				"only party that can report the registration did not happen."
		);
		assertSame(
			onSubscribeFailure,
			propagated,
			"The rollback replaced the subscriber's own failure, which is the one that explains what went wrong."
		);

		final ConcurrentSkipListMap<Long, Integer> versionSubscribersCount =
			getNonnullFieldValue(publisher, "versionSubscribersCount");

		assertEquals(
			0,
			publisher.getSubscribersCount(),
			"A registration whose onSubscribe threw left an entry in the subscribers map."
		);
		assertNull(
			versionSubscribersCount.get(trackedVersion),
			"A registration whose onSubscribe threw left its version slot pinned. It is incremented before the " +
				"subscription is constructed, and the periodic sweep can only reach it through a subscribers entry " +
				"- which was never installed. The ring buffer is anchored at this version for good, and every " +
				"further failed registration adds another."
		);
	}

	/**
	 * A subscriber whose {@code onSubscribe} throws rather than accepting the subscription.
	 */
	private static class ThrowingCatalogSubscriber implements Subscriber<ChangeCatalogCapture> {
		private final RuntimeException failure;

		ThrowingCatalogSubscriber(@Nonnull RuntimeException failure) {
			this.failure = failure;
		}

		@Override
		public void onSubscribe(Subscription subscription) {
			throw this.failure;
		}

		@Override
		public void onNext(ChangeCatalogCapture item) {
		}

		@Override
		public void onError(Throwable throwable) {
		}

		@Override
		public void onComplete() {
		}
	}

	/**
	 * A registration refused because the shared publisher was retired must be retried against a renewed one.
	 *
	 * The observer's periodic cleaner retires a shared publisher whose subscribers map it finds empty, and it
	 * decides that without any lock. A registration still inside {@code ConcurrentHashMap#computeIfAbsent} is
	 * invisible to that check - {@code isEmpty()}, {@code size()}, {@code containsKey()} and the entry iterator
	 * all skip the reservation until the mapping function returns - so the publisher the facade selected can be
	 * closed underneath it. The shared publisher detects that once its entry is published, undoes its own
	 * registration and refuses with {@link InstanceTerminatedException}; without the retry here the client would
	 * see that refusal for a subscribe call that had no reason to fail.
	 *
	 * The window itself cannot be opened deterministically from outside - nothing in the registration path calls
	 * out to test code any more, which is the point of the fix. This drives the same refusal through
	 * {@code assertActive()} instead, which raises the identical exception, and pins that the facade recovers by
	 * renewing rather than propagating.
	 *
	 * @param evita the Evita database instance with the test dataset already loaded
	 */
	@UseDataSet(value = CDC_TRANSACTIONS)
	@Test
	@DisplayName("renew the shared publisher and retry when a registration is refused")
	void shouldRenewTheSharedPublisherWhenARegistrationIsRefused(Evita evita) {
		final Catalog catalog = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final List<ChangeCatalogCaptureSharedPublisher> handedOut = new ArrayList<>();

		final ChangeCatalogCapturePublisher facade = new ChangeCatalogCapturePublisher(
			criteriaBundle -> {
				final ChangeCatalogCaptureSharedPublisher created = new ChangeCatalogCaptureSharedPublisher(
					catalog,
					new ImmediateExecutorService(),
					16,
					16,
					criteriaBundle,
					capture -> {
					},
					closingPublisher -> {
					}
				);
				// the first publisher the facade is handed has already been retired by the cleaner
				if (handedOut.isEmpty()) {
					created.close();
				}
				handedOut.add(created);
				return created;
			},
			new ChangeCatalogCaptureRequest(null, null, null, ChangeCaptureContent.BODY)
		);

		assertDoesNotThrow(
			() -> facade.subscribe(new SilentCatalogSubscriber()),
			"Subscribing failed because the shared publisher selected for it had been retired. The facade renews a " +
				"closed shared publisher, so a refusal has to be retried rather than handed to the client - which " +
				"would surface as a subscribe call failing for a reason the client can neither see nor act on."
		);

		assertEquals(
			2,
			handedOut.size(),
			"The facade did not renew the retired shared publisher."
		);
		assertEquals(
			1,
			handedOut.get(1).getSubscribersCount(),
			"The subscription did not land on the renewed publisher, so nothing would ever be delivered to it."
		);
	}

	/**
	 * A publisher retired while a registration is in flight must take the registration back in silence.
	 *
	 * The rollback cannot go through {@code unsubscribe}. That cancels the subscription, cancelling releases it,
	 * and the release closes an {@link AutoCloseable} subscriber - and the gRPC subscriber is one:
	 * {@code AbstractChangeCaptureSubscriber#close()} sends the client {@code UNAVAILABLE}. Rolling back that
	 * way ends the client's stream and only then hands the caller a refusal to retry, so the retry is inert:
	 * the subscriber it would retry with has already been finalised, and its {@code onSubscribe} cancels
	 * immediately on the renewed publisher, retiring that one too. The client sees an error for a subscribe
	 * that had no reason to fail.
	 *
	 * The subscriber was never given the subscription, so there is nothing it may legitimately be told. This
	 * pins that: no {@code onSubscribe}, no {@code close}, and no bookkeeping left behind.
	 *
	 * @param evita the Evita database instance with the test dataset already loaded
	 */
	@UseDataSet(value = CDC_TRANSACTIONS)
	@Test
	@DisplayName("take a registration back in silence when the publisher is retired mid-registration")
	void shouldRetractSilentlyWhenThePublisherIsRetiredMidRegistration(Evita evita) {
		final Catalog catalog = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final RetiringDuringRegistrationPublisher publisher = new RetiringDuringRegistrationPublisher(catalog);
		setFieldValue(publisher, "subscribers", new RetiringSubscriberMap(publisher));
		final TransportRecordingSubscriber subscriber = new TransportRecordingSubscriber();
		final long trackedVersion = catalog.getVersion() + 1;

		assertThrows(
			InstanceTerminatedException.class,
			() -> publisher.subscribe(
				subscriber,
				new WalPointerWithContent(trackedVersion, 0, ChangeCaptureContent.BODY)
			),
			"A registration against a publisher retired underneath it must be refused, so the caller can retry " +
				"against a renewed one."
		);

		assertFalse(
			subscriber.wasClosed(),
			"The retraction closed the subscriber's transport. For the gRPC subscriber that sends the client " +
				"UNAVAILABLE, which ends the very stream the caller is about to retry on a renewed publisher - so " +
				"the retry cannot help and the client sees an error for a subscribe that had no reason to fail."
		);
		assertFalse(
			subscriber.wasSubscribed(),
			"The subscriber was handed a subscription that was then withdrawn. Activation must not happen until " +
				"the registration is known to have stuck."
		);

		final ConcurrentSkipListMap<Long, Integer> versionSubscribersCount =
			getNonnullFieldValue(publisher, "versionSubscribersCount");
		assertEquals(
			0,
			publisher.getSubscribersCount(),
			"The withdrawn registration was left in the subscribers map."
		);
		assertNull(
			versionSubscribersCount.get(trackedVersion),
			"The withdrawn registration left its version slot pinned."
		);
	}

	/**
	 * A shared publisher exposing the package-private activity check as a deterministic retirement seam.
	 *
	 * {@link RetiringSubscriberMap} invokes the seam while the publisher lock is held and the subscription map still
	 * hides its in-progress insertion. This keeps the otherwise defensive post-insert closed check executable without
	 * retaining a redundant production activity assertion after the explicit closed-result branch.
	 */
	private static class RetiringDuringRegistrationPublisher extends ChangeCatalogCaptureSharedPublisher {
		private final AtomicBoolean armed = new AtomicBoolean(true);

		RetiringDuringRegistrationPublisher(@Nonnull Catalog catalog) {
			super(
				catalog,
				new ImmediateExecutorService(),
				16,
				16,
				ChangeCatalogCriteriaBundle.CATCH_ALL,
				capture -> {
				},
				closingPublisher -> {
				}
			);
		}

		@Override
		void assertActive() {
			super.assertActive();
			if (this.armed.compareAndSet(true, false)) {
				close();
			}
		}
	}

	/**
	 * Subscriber map that retires its publisher after the registration mapping function has constructed the
	 * subscription but before {@code computeIfAbsent} publishes the entry.
	 */
	private static final class RetiringSubscriberMap
		extends ConcurrentHashMap<UUID, DefaultChangeCaptureSubscription<ChangeCatalogCapture>> {
		private final RetiringDuringRegistrationPublisher publisher;
		private final AtomicBoolean retirementTriggered = new AtomicBoolean(false);

		/**
		 * Creates a map that retires the supplied publisher on its first insertion.
		 *
		 * @param publisher publisher to retire through its package-private test seam
		 */
		private RetiringSubscriberMap(@Nonnull RetiringDuringRegistrationPublisher publisher) {
			this.publisher = publisher;
		}

		/** {@inheritDoc} */
		@Override
		public DefaultChangeCaptureSubscription<ChangeCatalogCapture> computeIfAbsent(
			UUID key,
			Function<? super UUID, ? extends DefaultChangeCaptureSubscription<ChangeCatalogCapture>> mappingFunction
		) {
			return super.computeIfAbsent(
				key,
				subscriptionId -> {
					final DefaultChangeCaptureSubscription<ChangeCatalogCapture> subscription =
						mappingFunction.apply(subscriptionId);
					if (this.retirementTriggered.compareAndSet(false, true)) {
						this.publisher.assertActive();
					}
					return subscription;
				}
			);
		}
	}

	/**
	 * A subscriber that records whether it was ever handed a subscription or had its transport closed, the way
	 * the gRPC subscriber closes its response observer.
	 */
	private static class TransportRecordingSubscriber
		implements Subscriber<ChangeCatalogCapture>, AutoCloseable {
		private final AtomicBoolean subscribed = new AtomicBoolean(false);
		private final AtomicBoolean closed = new AtomicBoolean(false);

		boolean wasSubscribed() {
			return this.subscribed.get();
		}

		boolean wasClosed() {
			return this.closed.get();
		}

		@Override
		public void onSubscribe(Subscription subscription) {
			this.subscribed.set(true);
		}

		@Override
		public void onNext(ChangeCatalogCapture item) {
		}

		@Override
		public void onError(Throwable throwable) {
		}

		@Override
		public void onComplete() {
		}

		@Override
		public void close() {
			this.closed.set(true);
		}
	}

	/**
	 * Tests that the {@link CatalogChangeObserver} correctly cleans inactive publishers.
	 *
	 * This test:
	 * 1. Creates a new {@link CatalogChangeObserver} with an immediate executor service
	 * 2. Notifies the observer about the catalog being present in the live view
	 * 3. Registers multiple observers
	 * 4. Closes some of the publishers to make them inactive
	 * 5. Calls the cleanInactivePublishers method
	 * 6. Verifies that inactive publishers are removed from the uniquePublishers map
	 *
	 * @param evita the Evita database instance with the test dataset already loaded
	 */
	@UseDataSet(value = CDC_TRANSACTIONS)
	@Test
	@DisplayName("clean inactive publishers")
	void shouldCleanInactivePublishers(Evita evita) {
		final Catalog catalog = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();

		// Get and reconfigure the CatalogChangeObserver
		final CatalogChangeObserver tested = (CatalogChangeObserver) catalog.getTransactionManager().getChangeObserver();

		// Get the uniquePublishers map
		final Map<ChangeCatalogCriteriaBundle, ChangeCatalogCaptureSharedPublisher> uniquePublishers =
			getNonnullFieldValue(tested, "uniquePublishers");

		// Check initial size
		final int initialSize = uniquePublishers.size();

		// Notify the observer about the catalog being present in the live view
		tested.notifyCatalogPresentInLiveView(catalog);

		// Register multiple observers with different requests
		final ChangeCapturePublisher<ChangeCatalogCapture> publisher1 = tested.registerObserver(
			ChangeCatalogCaptureRequest.builder()
				.content(ChangeCaptureContent.BODY)
				.criteria(
					ChangeCatalogCaptureCriteria.builder()
						.dataArea(builder -> builder.containerType(ContainerType.ENTITY).operation(Operation.UPSERT))
						.build()
				)
				.build()
		);

		final ChangeCapturePublisher<ChangeCatalogCapture> publisher2 = tested.registerObserver(
			ChangeCatalogCaptureRequest.builder()
				.content(ChangeCaptureContent.BODY)
				.criteria(
					ChangeCatalogCaptureCriteria.builder()
						.dataArea(builder -> builder.containerType(ContainerType.ENTITY).operation(Operation.REMOVE))
						.build()
				)
				.build()
		);

		final ChangeCapturePublisher<ChangeCatalogCapture> publisher3 = tested.registerObserver(
			ChangeCatalogCaptureRequest.builder()
				.content(ChangeCaptureContent.BODY)
				.criteria(
					ChangeCatalogCaptureCriteria.builder()
						.dataArea(builder -> builder.containerType(ContainerType.ATTRIBUTE).operation(Operation.UPSERT))
						.build()
				)
				.build()
		);

		// Create subscribers for each publisher
		final MockCatalogChangeSubscriber subscriber1 = new MockCatalogChangeSubscriber();
		final MockCatalogChangeSubscriber subscriber2 = new MockCatalogChangeSubscriber();
		final MockCatalogChangeSubscriber subscriber3 = new MockCatalogChangeSubscriber();

		// Subscribe them to the publishers
		publisher1.subscribe(subscriber1);
		publisher2.subscribe(subscriber2);
		publisher3.subscribe(subscriber3);

		// Check size after registration
		final int sizeAfterRegistration = uniquePublishers.size();
		assertEquals(initialSize + 3, sizeAfterRegistration, "Should have at least three publishers");

		// Close some publishers to make them inactive
		publisher1.close();
		publisher2.close();

		// Call cleanInactivePublishers
		tested.cleanInactivePublishers();

		// Verify that inactive publishers are removed
		final int finalSize = uniquePublishers.size();
		assertEquals((sizeAfterRegistration - initialSize) - 2, finalSize, "Number of publishers should decrease after cleaning");

		// Close the last publisher
		publisher3.close();

		// Clean again
		tested.cleanInactivePublishers();

		// Verify all publishers are removed
		assertEquals(finalSize - 1, uniquePublishers.size(), "All publishers should be removed after cleaning");
	}

	/**
	 * Regression test for issue #1201: the periodic publisher cleanup must not crash when the
	 * subscriber-version map drains to empty inside `clearUnusedDataInRingBuffer`.
	 *
	 * Reproduces the exact production state observed in the crash: an initialised ring buffer plus a
	 * `versionSubscribersCount` whose only tracked version is strictly below the buffer's effective
	 * start version. The cleanup loop removes that stale entry, emptying the map; the old code then
	 * called {@link java.util.concurrent.ConcurrentSkipListMap#firstKey()} on the now-empty map and
	 * threw {@link java.util.NoSuchElementException}. The fix re-reads `firstEntry()`, which returns
	 * `null` on an empty map, so the loop terminates cleanly.
	 *
	 * @param evita       the Evita database instance with the test dataset already loaded
	 * @param brandSchema the brand schema created during test setup
	 */
	@UseDataSet(value = CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("clean ring buffer without crashing when subscriber-version map drains to empty")
	void shouldCleanRingBufferWhenVersionSubscribersCountDrainsToEmpty(@Nonnull Evita evita, @Nonnull SealedEntitySchema brandSchema) {
		final Catalog catalog = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final CatalogChangeObserver tested = (CatalogChangeObserver) catalog.getTransactionManager().getChangeObserver();
		tested.notifyCatalogPresentInLiveView(catalog);

		final ChangeCatalogCaptureRequest request = ChangeCatalogCaptureRequest.builder()
			.sinceVersion(catalog.getVersion() + 1)
			.content(ChangeCaptureContent.BODY)
			.criteria(
				ChangeCatalogCaptureCriteria.builder()
					.dataArea(builder -> builder.containerType(ContainerType.ENTITY).operation(Operation.UPSERT))
					.build()
			)
			.build();

		try (final ChangeCapturePublisher<ChangeCatalogCapture> publisher = tested.registerObserver(request)) {
			final MockCatalogChangeSubscriber subscriber = new MockCatalogChangeSubscriber();
			publisher.subscribe(subscriber);

			// drive one transactional mutation so the ring buffer (lastCaptures) gets initialised
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					this.dataGenerator.generateEntities(brandSchema, this.noEntityPicker, SEED)
						.skip(20)
						.limit(1)
						.forEach(session::upsertEntity);
				}
			);

			// locate the shared publisher backing this subscription (the only active one with an
			// initialised ring buffer)
			final Map<ChangeCatalogCriteriaBundle, ChangeCatalogCaptureSharedPublisher> uniquePublishers =
				getNonnullFieldValue(tested, "uniquePublishers");
			ChangeCatalogCaptureSharedPublisher sharedPublisher = null;
			ChangeCaptureRingBuffer<ChangeCatalogCapture> ringBuffer = null;
			for (final ChangeCatalogCaptureSharedPublisher candidate : uniquePublishers.values()) {
				if (candidate.isClosed()) {
					continue;
				}
				final ChangeCaptureRingBuffer<ChangeCatalogCapture> candidateBuffer = getFieldValue(candidate, "lastCaptures");
				if (candidateBuffer != null) {
					sharedPublisher = candidate;
					ringBuffer = candidateBuffer;
					break;
				}
			}
			assertNotNull(sharedPublisher, "Expected an active shared publisher with an initialised ring buffer");

			// craft the production state: the only tracked version sits strictly below the ring
			// buffer's effective start version, so cleanup removes it and empties the map
			final ConcurrentSkipListMap<Long, Integer> versionSubscribersCount =
				getNonnullFieldValue(sharedPublisher, "versionSubscribersCount");
			versionSubscribersCount.clear();
			versionSubscribersCount.put(ringBuffer.getEffectiveStartCatalogVersion() - 1, 1);

			// previously threw NoSuchElementException from firstKey() once the loop emptied the map
			final ChangeCatalogCaptureSharedPublisher publisherUnderTest = sharedPublisher;
			assertDoesNotThrow(publisherUnderTest::checkSubscribersLeft);
			assertTrue(
				versionSubscribersCount.isEmpty(),
				"Stale sub-threshold version entry should have been drained"
			);
		}
	}

	/**
	 * Utility method to verify that a subscriber received mutations for entity IDs greater than the specified value.
	 *
	 * @param subscriber the subscriber to check
	 * @param minEntityId the minimum entity ID value (exclusive)
	 */
	private static void verifySubscriberReceivedEntityIdsGreaterThan(MockCatalogChangeSubscriber subscriber, int minEntityId) {
		for (ChangeCatalogCapture capture : subscriber.getItems()) {
			if (capture.entityPrimaryKey() != null) {
				assertTrue(capture.entityPrimaryKey() > minEntityId,
					"Entity primary key should be greater than " + minEntityId);
			}
		}
	}
}
