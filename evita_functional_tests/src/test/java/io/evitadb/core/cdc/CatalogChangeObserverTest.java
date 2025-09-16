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
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Catalog;
import io.evitadb.core.Evita;
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
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;

import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static org.junit.jupiter.api.Assertions.*;
import static tool.ReflectionUtils.getNonnullFieldValue;

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
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
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
			assertEquals(40, subscriber.getItems().size(), "Should receive 40 mutations (20 entities × 2 mutations each)");
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
			assertEquals(40, entireWalSubscriber.getItems().size(), "Should receive 40 mutations (20 entities × 2 mutations each)");
			assertEquals(20, partialWalSubscriber.getItems().size(), "Should receive 20 mutations (10 entities × 2 mutations each)");

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
				assertEquals(40, partialOldAndNewSubscriber.getItems().size(), "Should receive 40 mutations (10 old + 10 new entities, both × 2 mutations)");
				assertEquals(20, onlyNewPartialSubscriber.getItems().size(), "Should receive 20 mutations (10 entities × 2 mutations)");

				// Verify initial subscribers received new data as well
				assertEquals(40 + 20, entireWalSubscriber.getItems().size(), "Should receive 40 mutations (20 entities × 2 mutations each)");
				assertEquals(20 + 20, partialWalSubscriber.getItems().size(), "Should receive 20 mutations (10 entities × 2 mutations each)");

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
				assertEquals(40 + 20 + 10, entireWalSubscriber.getItems().size(), "Should receive 5 × 2 mutations in new batch on top of the previous 60");
				assertEquals(20 + 20 + 10, partialWalSubscriber.getItems().size(), "Should receive 5 × 2 mutations in new batch on top of the previous 40");
				assertEquals(40 + 10, partialOldAndNewSubscriber.getItems().size(), "Should receive 5 × 2 mutations in new batch on top of the previous 40");
				assertEquals(20 + 10, onlyNewPartialSubscriber.getItems().size(), "Should receive 5 × 2 mutations in new batch on top of the previous 20");

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
		assertEquals(10, subscriber.getItems().size(), "Should receive 10 mutations (5 entities × 2 mutations each)");

		// Close the publisher, which will unregister the observer
		assertTrue(tested.unregisterObserver(subscriber.getSubscriptionId()));

		// The subscription should be completed
		assertTrue(subscriber.isCompleted());

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
		assertEquals(10, subscriber.getItems().size(), "Should still have only 10 mutations after unregistering");
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
