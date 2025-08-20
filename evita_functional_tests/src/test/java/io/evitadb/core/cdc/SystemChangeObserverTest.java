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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.DuplicateCatalogMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.MakeCatalogAliveMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogMutabilityMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogStateMutation;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tool.ReflectionUtils.getNonnullFieldValue;

/**
 * This test class verifies the functionality of the {@link SystemChangeObserver} which is responsible for
 * capturing and publishing changes made to the Evita engine.
 *
 * The Change Data Capture (CDC) mechanism allows clients to subscribe to a stream of changes
 * occurring at the engine level, enabling real-time data synchronization and event-driven architectures.
 *
 * This test specifically:
 * 1. Sets up a test environment with sample engine mutations
 * 2. Creates a {@link SystemChangeObserver} instance
 * 3. Registers an observer to capture all mutations (even historical ones)
 * 4. Verifies that the observer correctly receives and publishes the expected number of mutations
 *
 * The test uses {@link MockSystemChangeSubscriber} to collect and verify the published changes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see SystemChangeObserver
 * @see MockSystemChangeSubscriber
 * @see ChangeCapturePublisher
 */
@DisplayName("SystemChangeObserver should")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
class SystemChangeObserverTest implements EvitaTestSupport {
	/**
	 * Name of the dataset used for this test. This identifier is used by the test framework
	 * to manage test data lifecycle.
	 */
	private static final String SYSTEM_CDC_TRANSACTIONS = "systemCdcTransactions";

	/**
	 * Processes the recorded events captured by the provided subscriber and updates the set of expected operations
	 * by removing operations that have been completed based on the type of mutation observed.
	 *
	 * @param subscriber         the {@link MockSystemChangeSubscriber} instance that captures mutation events
	 * @param expectedOperations the set of operation IDs that are expected to be processed, which are
	 *                           updated by removing operations corresponding to the processed events
	 */
	private static void processRecordedEvents(
		@Nonnull MockSystemChangeSubscriber subscriber,
		@Nonnull Set<String> expectedOperations
	) {
		for (ChangeSystemCapture capture : subscriber.getItems()) {
			EngineMutation<?> mutation = capture.body();
			if (mutation instanceof CreateCatalogSchemaMutation ccsm) {
				expectedOperations.remove("create_" + ccsm.getCatalogName());
			} else if (mutation instanceof ModifyCatalogSchemaMutation mcsm) {
				expectedOperations.remove("modify_" + mcsm.getCatalogName());
			} else if (mutation instanceof ModifyCatalogSchemaNameMutation mcsnm) {
				expectedOperations.remove("remove_" + mcsnm.getCatalogName());
				expectedOperations.remove("create_" + mcsnm.getNewCatalogName());
			} else if (mutation instanceof RemoveCatalogSchemaMutation rccsm) {
				expectedOperations.remove("remove_" + rccsm.getCatalogName());
			} else if (mutation instanceof MakeCatalogAliveMutation glcm) {
				expectedOperations.remove("goLive_" + glcm.getCatalogName());
			} else if (mutation instanceof SetCatalogStateMutation scsm) {
				expectedOperations.remove("setState_" + scsm.getCatalogName() + "_" + scsm.isActive());
			} else if (mutation instanceof SetCatalogMutabilityMutation scmm) {
				expectedOperations.remove("setMutability_" + scmm.getCatalogName() + "_" + scmm.isMutable());
			} else if (mutation instanceof DuplicateCatalogMutation dcmm) {
				expectedOperations.remove("duplicate_" + dcmm.getCatalogName() + "_" + dcmm.getNewCatalogName());
			}
		}
	}

	/**
	 * Sets up the test data for CDC (Change Data Capture) testing.
	 *
	 * This method is annotated with {@link DataSet} which tells the test framework to:
	 * 1. Use the specified dataset name for this test
	 * 2. Destroy the dataset after all tests in the class are complete
	 * 3. Expect the catalog to be in WARMING_UP state initially
	 *
	 * The method performs operations to create engine-level mutations:
	 * 1. Creates a test catalog
	 * 2. Modifies the catalog schema
	 * 3. Creates another test catalog
	 * 4. Removes one of the catalogs
	 *
	 * @param evita the Evita database instance injected by the test framework
	 */
	@DataSet(value = SYSTEM_CDC_TRANSACTIONS, destroyAfterClass = true, readOnly = false)
	protected void setUp(Evita evita) {
		// Modify the catalog schema
		evita.updateCatalog(
			TEST_CATALOG, session -> {
				session.getCatalogSchema()
				       .openForWrite()
				       .withDescription("Updated description")
				       .updateVia(session);
				return null;
			}
		);

		// Create another test catalog
		final String secondCatalog = TEST_CATALOG + "_second";
		evita.defineCatalog(secondCatalog);
		evita.updateCatalog(secondCatalog, EvitaSessionContract::goLiveAndClose);

		// Remove one of the catalogs
		evita.deleteCatalogIfExists(secondCatalog);
	}

	/**
	 * Tests that the {@link SystemChangeObserver} correctly registers an observer and publishes
	 * mutations to subscribers.
	 *
	 * This test:
	 * 1. Creates a new {@link SystemChangeObserver} with an immediate executor service
	 * (which executes tasks in the calling thread)
	 * 2. Creates a request to capture all mutations since version 0 (the beginning)
	 * 3. Registers an observer with this request
	 * 4. Creates a {@link MockSystemChangeSubscriber} that collects the received items
	 * 5. Subscribes the mock subscriber to the publisher
	 * 6. Verifies that the subscriber received the expected number of items
	 *
	 * The test uses try-with-resources to ensure the publisher is properly closed after the test.
	 *
	 * @param evita the Evita database instance with the test dataset already loaded
	 */
	@Test
	@DisplayName("receive all existing mutations from the beginning")
	void shouldRegisterObserverAndReceiveAllExistingMutations(
		@UseDataSet(value = SYSTEM_CDC_TRANSACTIONS) Evita evita
	) {
		// Create a request to capture all mutations since version 0 (the beginning)
		final ChangeSystemCaptureRequest catchAllRequest = new ChangeSystemCaptureRequest(
			0L,
			0,
			ChangeCaptureContent.BODY
		);

		// Use try-with-resources to ensure the publisher is properly closed after the test
		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher = evita.registerSystemChangeCapture(
				catchAllRequest)
		) {
			final MockSystemChangeSubscriber subscriber = new MockSystemChangeSubscriber();

			// Subscribe to the publisher to start receiving mutations
			publisher.subscribe(subscriber);

			// Verify that the subscriber received the expected number of items
			final Set<String> expectedOperations = new HashSet<>(
				Set.of(
					"create_" + TEST_CATALOG, // create catalog
					"modify_" + TEST_CATALOG, // modify catalog schema (description change)
					"goLive_" + TEST_CATALOG, // go live operation for the first catalog
					"create_" + TEST_CATALOG + "_second", // create second catalog
					"goLive_" + TEST_CATALOG + "_second", // go live operation for the second catalog
					"remove_" + TEST_CATALOG + "_second" // remove second catalog
				)
			);

			processRecordedEvents(subscriber, expectedOperations);

			// Verify that all expected operations were received
			assertTrue(
				expectedOperations.isEmpty(),
				"All expected operations should be received by the subscriber. " +
					"Remaining: " + String.join(",", expectedOperations)
			);
		}
	}

	/**
	 * Tests that the {@link SystemChangeObserver} correctly captures only new mutations
	 * that occur after the observer is registered.
	 *
	 * This test:
	 * 1. Creates a new {@link SystemChangeObserver} with an immediate executor service
	 * 2. Creates a request to capture mutations starting from the next version after the current engine version
	 * 3. Registers an observer with this request
	 * 4. Creates a {@link MockSystemChangeSubscriber} that collects the received items
	 * 5. Subscribes the mock subscriber to the publisher
	 * 6. Creates a new catalog
	 * 7. Verifies that the subscriber received exactly 1 item (the catalog creation mutation)
	 *
	 * @param evita the Evita database instance with the test dataset already loaded
	 */
	@UseDataSet(value = SYSTEM_CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("capture only new mutations after registration")
	void shouldRegisterObserverAndObtainOnlyNewMutations(@Nonnull Evita evita) {
		// Get the current engine version
		final long currentVersion = evita.getEngineState().version();

		// Create a request to capture only new mutations that occur after the current engine version
		final ChangeSystemCaptureRequest newMutationsRequest = new ChangeSystemCaptureRequest(
			currentVersion + 1,
			0,
			ChangeCaptureContent.BODY
		);

		// Use try-with-resources to ensure the publisher is properly closed after the test
		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher =
				evita.registerSystemChangeCapture(newMutationsRequest)
		) {
			final MockSystemChangeSubscriber subscriber = new MockSystemChangeSubscriber();

			// Subscribe to the publisher to start receiving mutations
			publisher.subscribe(subscriber);

			// Create a new catalog to generate a new mutation
			final String newCatalog = TEST_CATALOG + "_new";
			evita.defineCatalog(newCatalog);
			evita.updateCatalog(newCatalog, EvitaSessionContract::goLiveAndClose);

			// Define the expected operations for the new catalog
			final Set<String> expectedOperations = new HashSet<>(
				Set.of(
					"create_" + newCatalog, // create new catalog
					"goLive_" + newCatalog // make catalog go live
				)
			);

			// Verify the mutations received by the subscriber
			processRecordedEvents(subscriber, expectedOperations);

			// Verify that all expected operations were received
			assertTrue(
				expectedOperations.isEmpty(),
				"All expected operations should be received by the subscriber. " +
					"Remaining: " + String.join(",", expectedOperations)
			);

			// Verify that the subscriber received exactly 4 items (2x transaction mutation, create and go live)
			assertEquals(4, subscriber.getItems().size(), "Should receive 4 mutations");
		}
	}

	/**
	 * Tests that the {@link SystemChangeObserver} correctly unregisters an observer.
	 *
	 * This test:
	 * 1. Creates a new {@link SystemChangeObserver} with an immediate executor service
	 * 2. Registers an observer to capture all mutations
	 * 3. Verifies the observer receives mutations when a catalog is created
	 * 4. Unregisters the observer using its UUID
	 * 5. Creates another catalog
	 * 6. Verifies the unregistered observer doesn't receive new mutations
	 *
	 * @param evita the Evita database instance with the test dataset already loaded
	 */
	@UseDataSet(value = SYSTEM_CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("unregister an observer correctly")
	void shouldUnregisterObserverCorrectly(@Nonnull Evita evita) {
		// Get the current engine version
		final long currentVersion = evita.getEngineState().version();

		// Create a request to capture mutations
		final ChangeSystemCaptureRequest request = new ChangeSystemCaptureRequest(
			currentVersion + 1,
			0,
			ChangeCaptureContent.BODY
		);

		// Register an observer
		final ChangeCapturePublisher<ChangeSystemCapture> publisher = evita.registerSystemChangeCapture(request);
		final MockSystemChangeSubscriber subscriber = new MockSystemChangeSubscriber();
		publisher.subscribe(subscriber);

		// Create a new catalog to generate a mutation
		final String firstCatalog = TEST_CATALOG + "_first";
		evita.defineCatalog(firstCatalog);
		evita.updateCatalog(firstCatalog, EvitaSessionContract::goLiveAndClose);

		// Define the expected operations for the first catalog
		final Set<String> expectedOperations = new HashSet<>(
			Set.of(
				"create_" + firstCatalog, // create first catalog
				"goLive_" + firstCatalog // make catalog go live
			)
		);

		// Verify the mutations received by the subscriber
		processRecordedEvents(subscriber, expectedOperations);

		// Verify that all expected operations were received
		assertTrue(
			expectedOperations.isEmpty(),
			"All expected operations should be received by the subscriber. " +
				"Remaining: " + String.join(",", expectedOperations)
		);

		// Verify the subscriber received the mutations (2x transaction mutation, create and go live)
		assertEquals(4, subscriber.getItems().size(), "Should receive 4 mutations");

		final SystemChangeObserver tested = evita.getChangeObserver();

		// Unregister the observer
		assertTrue(
			tested.unregisterObserver(subscriber.getSubscriptionId()),
			"Unregistering the observer should return true"
		);

		// The subscription should be completed
		assertTrue(subscriber.isCompleted(), "The subscription should be completed after unregistering");

		// Try to unregister with a random UUID (should fail since the observer is already unregistered)
		assertFalse(
			tested.unregisterObserver(UUID.randomUUID()),
			"Unregistering a non-existent observer should return false"
		);

		// Create another catalog
		final String secondCatalog = TEST_CATALOG + "_second";
		evita.defineCatalog(secondCatalog);
		evita.updateCatalog(secondCatalog, EvitaSessionContract::goLiveAndClose);

		// Verify the subscriber still has only the original mutation
		assertEquals(
			4, subscriber.getItems().size(),
			"Should still have only 4 mutations after unregistering"
		);
	}

	/**
	 * Tests that the {@link SystemChangeObserver} correctly cleans inactive publishers.
	 *
	 * This test:
	 * 1. Creates a new {@link SystemChangeObserver} with an immediate executor service
	 * 2. Registers multiple observers
	 * 3. Closes some of the publishers to make them inactive
	 * 4. Calls the cleanSubscribers method
	 * 5. Verifies that inactive publishers are removed
	 *
	 * @param evita the Evita database instance with the test dataset already loaded
	 */
	@UseDataSet(value = SYSTEM_CDC_TRANSACTIONS)
	@Test
	@DisplayName("clean inactive publishers")
	void shouldCleanInactivePublishers(Evita evita) {
		// Get the sharedPublisher field
		final SystemChangeObserver systemChangeObserver = getNonnullFieldValue(evita, "changeObserver");

		// Register multiple observers with different requests
		final ChangeCapturePublisher<ChangeSystemCapture> publisher1 = evita.registerSystemChangeCapture(
			new ChangeSystemCaptureRequest(0L, 0, ChangeCaptureContent.BODY)
		);

		final ChangeCapturePublisher<ChangeSystemCapture> publisher2 = evita.registerSystemChangeCapture(
			new ChangeSystemCaptureRequest(1L, 0, ChangeCaptureContent.BODY)
		);

		final ChangeCapturePublisher<ChangeSystemCapture> publisher3 = evita.registerSystemChangeCapture(
			new ChangeSystemCaptureRequest(2L, 0, ChangeCaptureContent.BODY)
		);

		// Create subscribers for each publisher
		final MockSystemChangeSubscriber subscriber1 = new MockSystemChangeSubscriber();
		final MockSystemChangeSubscriber subscriber2 = new MockSystemChangeSubscriber();
		final MockSystemChangeSubscriber subscriber3 = new MockSystemChangeSubscriber();

		// Subscribe them to the publishers
		publisher1.subscribe(subscriber1);
		publisher2.subscribe(subscriber2);
		publisher3.subscribe(subscriber3);

		// Check initial subscriber count
		final int initialCount = systemChangeObserver.getSubscribersCount();
		assertEquals(4, initialCount, "Should have 3 + 1 subscribers initially");

		// Close some publishers to make them inactive
		publisher1.close();
		publisher2.close();

		assertTrue(subscriber1.isCompleted(), "Subscriber 1 should be completed after cleaning");
		assertTrue(subscriber2.isCompleted(), "Subscriber 2 should be completed after cleaning");
		assertFalse(subscriber3.isCompleted(), "Subscriber 3 should not be completed after cleaning");

		// Call cleanSubscribers
		systemChangeObserver.cleanSubscribers();

		// Verify that inactive publishers are removed
		final int countAfterClean = systemChangeObserver.getSubscribersCount();
		assertEquals(2, countAfterClean, "Should have 1 + 1 subscriber after cleaning");

		// Close the last publisher
		publisher3.close();

		// Clean again
		systemChangeObserver.cleanSubscribers();

		// Verify all publishers are removed (except the system publisher)
		assertEquals(1, systemChangeObserver.getSubscribersCount(), "All subscribers except system one should be removed after cleaning");
		assertTrue(subscriber1.isCompleted(), "Subscriber 1 should be completed after cleaning");
		assertTrue(subscriber2.isCompleted(), "Subscriber 2 should be completed after cleaning");
		assertTrue(subscriber3.isCompleted(), "Subscriber 3 should be completed after cleaning");
	}

	/**
	 * Tests that the {@link SystemChangeObserver} correctly handles multiple subscribers
	 * with different capture conditions.
	 *
	 * This test:
	 * 1. Creates a new {@link SystemChangeObserver} with an immediate executor service
	 * 2. Registers multiple subscribers at different times with different starting versions
	 * 3. Creates catalogs to generate mutations
	 * 4. Verifies each subscriber receives the expected data
	 *
	 * @param evita the Evita database instance with the test dataset already loaded
	 */
	@UseDataSet(value = SYSTEM_CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("handle multiple subscribers with different capture conditions")
	void shouldHandleMultipleSubscribersWithDifferentCaptureConditions(@Nonnull Evita evita) {
		// Get the current engine version
		final long currentVersion = evita.getEngineState().version();

		// 1. Subscriber that consumes entire WAL (since version 0)
		final ChangeSystemCaptureRequest entireWalRequest = new ChangeSystemCaptureRequest(
			0L,
			0,
			ChangeCaptureContent.BODY
		);

		// 2. Subscriber that starts from current version + 1
		final ChangeSystemCaptureRequest newMutationsRequest = new ChangeSystemCaptureRequest(
			currentVersion + 1,
			0,
			ChangeCaptureContent.BODY
		);

		// Register the subscribers
		try (
			final ChangeCapturePublisher<ChangeSystemCapture> entireWalPublisher =
				evita.registerSystemChangeCapture(entireWalRequest);
			final ChangeCapturePublisher<ChangeSystemCapture> newMutationsPublisher =
				evita.registerSystemChangeCapture(newMutationsRequest)
		) {
			// Create subscribers
			final MockSystemChangeSubscriber entireWalSubscriber = new MockSystemChangeSubscriber();
			final MockSystemChangeSubscriber newMutationsSubscriber = new MockSystemChangeSubscriber();

			// Subscribe to start receiving mutations
			entireWalPublisher.subscribe(entireWalSubscriber);
			newMutationsPublisher.subscribe(newMutationsSubscriber);

			// Define the expected operations for the initial state
			final Set<String> expectedInitialOperations = new HashSet<>(
				Set.of(
					"create_" + TEST_CATALOG, // create catalog
					"modify_" + TEST_CATALOG, // modify catalog schema (description change)
					"goLive_" + TEST_CATALOG, // go live operation for the first catalog
					"create_" + TEST_CATALOG + "_second", // create second catalog
					"goLive_" + TEST_CATALOG + "_second", // go live operation for the second catalog
					"remove_" + TEST_CATALOG + "_second" // remove second catalog
				)
			);

			// Verify the mutations received by the entireWalSubscriber
			processRecordedEvents(entireWalSubscriber, expectedInitialOperations);

			// Verify that all expected initial operations were received by entireWalSubscriber
			assertTrue(
				expectedInitialOperations.isEmpty(),
				"All expected initial operations should be received by the entireWalSubscriber. " +
					"Remaining: " + String.join(",", expectedInitialOperations)
			);

			// Verify that entireWalSubscriber received new 12 mutations
			assertEquals(
				12, entireWalSubscriber.getItems().size(),
				"entireWalSubscriber should receive 12 mutations"
			);

			// The newMutationsSubscriber should not have received any mutations yet
			assertEquals(
				0, newMutationsSubscriber.getItems().size(),
				"Should not receive any mutations yet"
			);

			// Create a new catalog to generate a mutation
			final String firstNewCatalog = TEST_CATALOG + "_first_new";
			evita.defineCatalog(firstNewCatalog);
			evita.updateCatalog(firstNewCatalog, EvitaSessionContract::goLiveAndClose);

			// Define the expected operations for the first new catalog
			final Set<String> expectedFirstNewOperations = new HashSet<>(
				Set.of(
					"create_" + firstNewCatalog, // create first new catalog
					"goLive_" + firstNewCatalog // make first new catalog go live
				)
			);
			final Set<String> expectedFirstNewOperationsCopy = new HashSet<>(expectedFirstNewOperations);

			// Verify the mutations received by the newMutationsSubscriber
			processRecordedEvents(entireWalSubscriber, expectedFirstNewOperations);
			processRecordedEvents(newMutationsSubscriber, expectedFirstNewOperationsCopy);

			// Verify that all expected operations for the first new catalog were received by entireWalSubscriber
			assertTrue(
				expectedFirstNewOperations.isEmpty(),
				"All expected operations for the first new catalog should be received by the entireWalSubscriber. " +
					"Remaining: " + String.join(",", expectedFirstNewOperations)
			);

			// Verify that all expected operations for the first new catalog were received by newMutationsSubscriber
			assertTrue(
				expectedFirstNewOperationsCopy.isEmpty(),
				"All expected operations for the first new catalog should be received by the newMutationsSubscriber. " +
					"Remaining: " + String.join(",", expectedFirstNewOperationsCopy)
			);

			// Verify that entireWalSubscriber received new 4 mutations (2x transaction mutation, create and go live)
			assertEquals(
				16, entireWalSubscriber.getItems().size(),
				"entireWalSubscriber should receive 16 mutations"
			);

			// Verify that entireWalSubscriber received 4 mutations (2x transaction mutation, create and go live)
			assertEquals(
				4, newMutationsSubscriber.getItems().size(),
				"newMutationsSubscriber should receive 4 mutations"
			);

			// Create a third subscriber that starts from the current version
			final ChangeSystemCaptureRequest latestMutationsRequest = new ChangeSystemCaptureRequest(
				evita.getEngineState().version(),
				0,
				ChangeCaptureContent.BODY
			);

			try (
				final ChangeCapturePublisher<ChangeSystemCapture> latestMutationsPublisher =
					evita.registerSystemChangeCapture(latestMutationsRequest)
			) {
				final MockSystemChangeSubscriber latestMutationsSubscriber = new MockSystemChangeSubscriber();
				latestMutationsPublisher.subscribe(latestMutationsSubscriber);

				// Create another catalog
				final String secondNewCatalog = TEST_CATALOG + "_second_new";
				evita.defineCatalog(secondNewCatalog);
				evita.updateCatalog(secondNewCatalog, EvitaSessionContract::goLiveAndClose);

				// Define the expected operations for the second new catalog
				final Set<String> expectedSecondNewOperations = new HashSet<>(
					Set.of(
						"goLive_" + firstNewCatalog, // make first new catalog go live
						"create_" + secondNewCatalog, // create second new catalog
						"goLive_" + secondNewCatalog // make second new catalog go live
					)
				);

				// Verify the mutations received by the latestMutationsSubscriber
				processRecordedEvents(latestMutationsSubscriber, expectedSecondNewOperations);

				// Verify that all expected operations for the second new catalog were received
				assertTrue(
					expectedSecondNewOperations.isEmpty(),
					"All expected operations for the second new catalog should be received by the latestMutationsSubscriber. " +
						"Remaining: " + String.join(",", expectedSecondNewOperations)
				);

				// Verify that latestMutationsSubscriber received new 4 mutations (2x transaction mutation, create and go live)
				// and two from the previous version (transaction + go live of the first new catalog)
				assertEquals(
					6, latestMutationsSubscriber.getItems().size(),
					"latestMutationsSubscriber should receive 6 mutations"
				);
			}
		}
	}
}
