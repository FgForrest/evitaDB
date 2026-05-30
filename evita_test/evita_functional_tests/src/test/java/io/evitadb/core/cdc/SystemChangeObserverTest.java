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

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.EvitaSessionTerminationCallback;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.cdc.HostSystemEvent;
import io.evitadb.api.requestResponse.cdc.SystemCaptureArea;
import io.evitadb.api.requestResponse.cdc.SystemCaptureBody;
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
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.utils.ReflectionUtils.getNonnullFieldValue;
import static io.evitadb.test.utils.ReflectionUtils.setFieldValue;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.CDC;

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
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(ENGINE)
@Tag(CDC)
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
			final SystemCaptureBody mutation = capture.body();
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
			null,
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
			null,
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
			null,
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
		assertTrue(subscriber.isClosed(), "The subscription should be completed after unregistering");
		assertFalse(subscriber.isCompleted(), "The subscription should not be completed yet");

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
			new ChangeSystemCaptureRequest(0L, 0, null, ChangeCaptureContent.BODY)
		);

		final ChangeCapturePublisher<ChangeSystemCapture> publisher2 = evita.registerSystemChangeCapture(
			new ChangeSystemCaptureRequest(1L, 0, null, ChangeCaptureContent.BODY)
		);

		final ChangeCapturePublisher<ChangeSystemCapture> publisher3 = evita.registerSystemChangeCapture(
			new ChangeSystemCaptureRequest(2L, 0, null, ChangeCaptureContent.BODY)
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

		assertTrue(subscriber1.isClosed(), "Subscriber 1 should be completed after cleaning");
		assertTrue(subscriber2.isClosed(), "Subscriber 2 should be completed after cleaning");
		assertFalse(subscriber3.isClosed(), "Subscriber 3 should not be completed after cleaning");

		// none should be completed
		assertFalse(subscriber1.isCompleted(), "Subscriber 1 should not be completed yet");
		assertFalse(subscriber2.isCompleted(), "Subscriber 2 should not be completed yet");
		assertFalse(subscriber3.isCompleted(), "Subscriber 3 should not be completed yet");

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
		assertTrue(subscriber1.isClosed(), "Subscriber 1 should be completed after cleaning");
		assertTrue(subscriber2.isClosed(), "Subscriber 2 should be completed after cleaning");
		assertTrue(subscriber3.isClosed(), "Subscriber 3 should be completed after cleaning");

		// none should be completed
		assertFalse(subscriber1.isCompleted(), "Subscriber 1 should not be completed yet");
		assertFalse(subscriber2.isCompleted(), "Subscriber 2 should not be completed yet");
		assertFalse(subscriber3.isCompleted(), "Subscriber 3 should not be completed yet");
	}

	/**
	 * Regression test for issue #1201: the periodic subscriber cleanup must not crash when the
	 * subscriber-version map drains to empty inside `clearUnusedDataInRingBuffer`.
	 *
	 * Mirrors the catalog-level regression for the system publisher. Reproduces the exact production
	 * state: an initialised ring buffer plus a `versionSubscribersCount` whose only tracked version is
	 * strictly below the buffer's effective start version. The cleanup loop removes that stale entry,
	 * emptying the map; the old code then called
	 * {@link java.util.concurrent.ConcurrentSkipListMap#firstKey()} on the now-empty map and threw
	 * {@link java.util.NoSuchElementException}. The fix re-reads `firstEntry()`, which returns `null`
	 * on an empty map, so the loop terminates cleanly.
	 *
	 * @param evita the Evita database instance with the test dataset already loaded
	 */
	@UseDataSet(value = SYSTEM_CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("clean ring buffer without crashing when subscriber-version map drains to empty")
	void shouldCleanRingBufferWhenVersionSubscribersCountDrainsToEmpty(@Nonnull Evita evita) {
		final SystemChangeObserver tested = evita.getChangeObserver();
		final ChangeSystemCaptureSharedPublisher sharedPublisher = getNonnullFieldValue(tested, "sharedPublisher");

		final long currentVersion = evita.getEngineState().version();
		final ChangeSystemCaptureRequest request = new ChangeSystemCaptureRequest(
			currentVersion + 1, 0, null, ChangeCaptureContent.BODY
		);

		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher = evita.registerSystemChangeCapture(request)
		) {
			final MockSystemChangeSubscriber subscriber = new MockSystemChangeSubscriber();
			publisher.subscribe(subscriber);

			// drive an engine mutation so the ring buffer (lastCaptures) is initialised
			final String catalog = TEST_CATALOG + "_drain";
			evita.defineCatalog(catalog);
			evita.updateCatalog(catalog, EvitaSessionContract::goLiveAndClose);

			// craft the production state: the only tracked version sits strictly below the ring
			// buffer's effective start version, so cleanup removes it and empties the map
			final ChangeCaptureRingBuffer<ChangeSystemCapture> ringBuffer =
				getNonnullFieldValue(sharedPublisher, "lastCaptures");
			final ConcurrentSkipListMap<Long, Integer> versionSubscribersCount =
				getNonnullFieldValue(sharedPublisher, "versionSubscribersCount");
			versionSubscribersCount.clear();
			versionSubscribersCount.put(ringBuffer.getEffectiveStartCatalogVersion() - 1, 1);

			// previously threw NoSuchElementException from firstKey() once the loop emptied the map
			assertDoesNotThrow(sharedPublisher::checkSubscribersLeft);
			assertTrue(
				versionSubscribersCount.isEmpty(),
				"Stale sub-threshold version entry should have been drained"
			);
		}
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
			null,
			ChangeCaptureContent.BODY
		);

		// 2. Subscriber that starts from current version + 1
		final ChangeSystemCaptureRequest newMutationsRequest = new ChangeSystemCaptureRequest(
			currentVersion + 1,
			0,
			null,
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
				null,
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

	/**
	 * Counts the number of {@link HostSystemEvent}s received by a subscriber.
	 *
	 * @param subscriber the subscriber whose received items should be inspected
	 * @return the number of host events in the subscriber's recorded item list
	 */
	private static int countHostEvents(@Nonnull MockSystemChangeSubscriber subscriber) {
		int count = 0;
		for (final ChangeSystemCapture capture : subscriber.getItems()) {
			if (capture.body() instanceof HostSystemEvent) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Counts the number of {@link EngineMutation}s received by a subscriber.
	 *
	 * @param subscriber the subscriber whose received items should be inspected
	 * @return the number of engine mutations in the subscriber's recorded item list
	 */
	private static int countEngineMutations(@Nonnull MockSystemChangeSubscriber subscriber) {
		int count = 0;
		for (final ChangeSystemCapture capture : subscriber.getItems()) {
			if (capture.body() instanceof EngineMutation<?>) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Filters the captures received by the subscriber that match the given catalog name and event type.
	 *
	 * @param subscriber  the subscriber whose received items should be inspected
	 * @param catalogName the catalog name to filter by
	 * @param eventType   the host-event subtype class to filter by
	 * @return list of host events matching the filter, in arrival order
	 */
	@Nonnull
	private static <T extends HostSystemEvent> List<T> hostEventsFor(
		@Nonnull MockSystemChangeSubscriber subscriber,
		@Nonnull String catalogName,
		@Nonnull Class<T> eventType
	) {
		final List<T> result = new ArrayList<>();
		for (final ChangeSystemCapture capture : subscriber.getItems()) {
			final SystemCaptureBody body = capture.body();
			if (eventType.isInstance(body)) {
				final T host = eventType.cast(body);
				if (host.catalogName().equals(catalogName)) {
					result.add(host);
				}
			}
		}
		return result;
	}

	/**
	 * Verifies that with default null-criteria the subscriber receives no host events even when the
	 * observer's `processHostEvent` is invoked. This locks down the deliberate divergence documented
	 * on `ChangeSystemCaptureRequest`: legacy / default-criteria clients keep the engine-only stream
	 * shape they always saw.
	 */
	@UseDataSet(value = SYSTEM_CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("deliver host events only to host-area subscribers")
	void shouldDeliverHostEventsOnlyToHostSubscribers(@Nonnull Evita evita) {
		final long currentVersion = evita.getEngineState().version();

		// engine-only subscriber (default null criteria)
		final ChangeSystemCaptureRequest engineOnlyRequest = ChangeSystemCaptureRequest.builder()
			.sinceVersion(currentVersion + 1)
			.content(ChangeCaptureContent.BODY)
			.build();
		// host-area subscriber
		final ChangeSystemCaptureRequest infraRequest = ChangeSystemCaptureRequest.builder()
			.sinceVersion(currentVersion + 1)
			.content(ChangeCaptureContent.BODY)
			.hostArea()
			.build();

		try (
			final ChangeCapturePublisher<ChangeSystemCapture> enginePublisher =
				evita.registerSystemChangeCapture(engineOnlyRequest);
			final ChangeCapturePublisher<ChangeSystemCapture> infraPublisher =
				evita.registerSystemChangeCapture(infraRequest)
		) {
			final MockSystemChangeSubscriber engineSubscriber = new MockSystemChangeSubscriber();
			final MockSystemChangeSubscriber infraSubscriber = new MockSystemChangeSubscriber();
			enginePublisher.subscribe(engineSubscriber);
			infraPublisher.subscribe(infraSubscriber);

			// directly emit a host event through the observer; this is the hot path used by Evita
			// after replaceCatalogReference / a catalog state settled
			final HostSystemEvent.CatalogInstalledIntoLiveView event =
				new HostSystemEvent.CatalogInstalledIntoLiveView(
					"hostEvent_target", CatalogState.ALIVE, currentVersion
				);
			evita.getChangeObserver().processHostEvent(event);

			// engine-only subscriber must NOT see the host event
			assertEquals(
				0, countHostEvents(engineSubscriber),
				"Default-criteria (engine-only) subscriber must not receive host events"
			);
			// host subscriber must see exactly one host event
			assertEquals(
				1, countHostEvents(infraSubscriber),
				"Host-criteria subscriber must receive the emitted host event"
			);
			final List<HostSystemEvent.CatalogInstalledIntoLiveView> received = hostEventsFor(
				infraSubscriber, "hostEvent_target", HostSystemEvent.CatalogInstalledIntoLiveView.class
			);
			assertEquals(1, received.size(), "Should have exactly one matching host event");
			assertEquals(CatalogState.ALIVE, received.get(0).observedState());
		}
	}

	/**
	 * Symmetric test: a subscriber whose criteria opt only into HOST must NOT receive
	 * engine mutations even though they arrive on the same stream.
	 */
	@UseDataSet(value = SYSTEM_CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("deliver engine mutations only to engine-area subscribers")
	void shouldDeliverEngineMutationsOnlyToEngineSubscribers(@Nonnull Evita evita) {
		final long currentVersion = evita.getEngineState().version();

		final ChangeSystemCaptureRequest infraRequest = ChangeSystemCaptureRequest.builder()
			.sinceVersion(currentVersion + 1)
			.content(ChangeCaptureContent.BODY)
			.hostArea()
			.build();

		try (
			final ChangeCapturePublisher<ChangeSystemCapture> infraPublisher =
				evita.registerSystemChangeCapture(infraRequest)
		) {
			final MockSystemChangeSubscriber infraSubscriber = new MockSystemChangeSubscriber();
			infraPublisher.subscribe(infraSubscriber);

			// Trigger real engine mutations by creating a catalog
			final String catalog = TEST_CATALOG + "_infra_only";
			evita.defineCatalog(catalog);
			evita.updateCatalog(catalog, EvitaSessionContract::goLiveAndClose);

			// the infra subscriber must NOT receive any engine mutations from this activity
			assertEquals(
				0, countEngineMutations(infraSubscriber),
				"Host-only subscriber must not receive engine mutations"
			);
		}
	}

	/**
	 * Subscriber that opts into BOTH engine and host areas must receive both kinds.
	 */
	@UseDataSet(value = SYSTEM_CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("deliver both engine mutations and host events to combined-criteria subscriber")
	void shouldDeliverBothToCombinedCriteriaSubscriber(@Nonnull Evita evita) {
		final long currentVersion = evita.getEngineState().version();

		final ChangeSystemCaptureRequest combinedRequest = ChangeSystemCaptureRequest.builder()
			.sinceVersion(currentVersion + 1)
			.content(ChangeCaptureContent.BODY)
			.criteria(
				new ChangeSystemCaptureCriteria(SystemCaptureArea.ENGINE),
				new ChangeSystemCaptureCriteria(SystemCaptureArea.HOST)
			)
			.build();

		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher =
				evita.registerSystemChangeCapture(combinedRequest)
		) {
			final MockSystemChangeSubscriber subscriber = new MockSystemChangeSubscriber();
			publisher.subscribe(subscriber);

			// Trigger an engine mutation
			final String catalog = TEST_CATALOG + "_combined";
			evita.defineCatalog(catalog);
			evita.updateCatalog(catalog, EvitaSessionContract::goLiveAndClose);

			// Now emit a synthetic host event manually
			evita.getChangeObserver().processHostEvent(
				new HostSystemEvent.CatalogInstalledIntoLiveView(
					catalog, CatalogState.ALIVE, evita.getEngineState().version()
				)
			);

			assertTrue(
				countEngineMutations(subscriber) >= 1,
				"Combined subscriber must receive engine mutations"
			);
			assertTrue(
				countHostEvents(subscriber) >= 1,
				"Combined subscriber must receive host events"
			);
		}
	}

	/**
	 * A late subscriber attaching with an old `sinceVersion` reads historical engine mutations from
	 * the WAL — but historical host events are not replayed (live-tail only).
	 */
	@UseDataSet(value = SYSTEM_CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("not replay historical host events to a late subscriber")
	void shouldNotReplayHistoricalHostEventsToLateSubscriber(@Nonnull Evita evita) {
		final long startVersion = evita.getEngineState().version();

		// Generate engine mutations + emit a host event before the subscriber attaches
		final String catalog = TEST_CATALOG + "_late";
		evita.defineCatalog(catalog);
		evita.updateCatalog(catalog, EvitaSessionContract::goLiveAndClose);

		evita.getChangeObserver().processHostEvent(
			new HostSystemEvent.CatalogInstalledIntoLiveView(
				catalog, CatalogState.ALIVE, evita.getEngineState().version()
			)
		);

		// late attach — sinceVersion=startVersion+1 so we get the engine mutations issued above
		final ChangeSystemCaptureRequest lateRequest = ChangeSystemCaptureRequest.builder()
			.sinceVersion(startVersion + 1)
			.content(ChangeCaptureContent.BODY)
			.criteria(
				new ChangeSystemCaptureCriteria(SystemCaptureArea.ENGINE),
				new ChangeSystemCaptureCriteria(SystemCaptureArea.HOST)
			)
			.build();

		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher =
				evita.registerSystemChangeCapture(lateRequest)
		) {
			final MockSystemChangeSubscriber subscriber = new MockSystemChangeSubscriber();
			publisher.subscribe(subscriber);

			// historical mutations must be replayed
			assertTrue(
				countEngineMutations(subscriber) >= 1,
				"Late subscriber should receive historical engine mutations from WAL"
			);
			// historical host events must NOT be replayed
			assertEquals(
				0, countHostEvents(subscriber),
				"Late subscriber must NOT see historical host events (live-tail only)"
			);
		}
	}

	/**
	 * Verifies the ordering guarantee documented on `HostSystemEvent`: a host event emitted after a
	 * mutation on the same engine state lock must land strictly after that mutation in the
	 * subscriber's stream.
	 *
	 * Caveat: due to backpressure, a host event delivered out-of-band via `deliverImmediate` is
	 * dropped if there is no demand at the moment. The MockSystemChangeSubscriber requests one item
	 * at a time, so we wait until prior mutations are drained before emitting the event.
	 */
	@UseDataSet(value = SYSTEM_CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("order host event strictly after preceding mutation")
	void shouldOrderHostEventStrictlyAfterPrecedingMutation(@Nonnull Evita evita) throws Exception {
		final long startVersion = evita.getEngineState().version();

		final ChangeSystemCaptureRequest combinedRequest = ChangeSystemCaptureRequest.builder()
			.sinceVersion(startVersion + 1)
			.content(ChangeCaptureContent.BODY)
			.criteria(
				new ChangeSystemCaptureCriteria(SystemCaptureArea.ENGINE),
				new ChangeSystemCaptureCriteria(SystemCaptureArea.HOST)
			)
			.build();

		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher =
				evita.registerSystemChangeCapture(combinedRequest)
		) {
			final MockSystemChangeSubscriber subscriber = new MockSystemChangeSubscriber();
			publisher.subscribe(subscriber);

			// Trigger an engine mutation. `defineCatalog` and `updateCatalog(...goLiveAndClose)`
			// both block on completion of the underlying mutation operators, so by the time these
			// calls return the engine mutations have been processed by the observer chain. We do
			// NOT busy-wait for delivery here — the deterministic ordering established by issue
			// #1151 guarantees prior engine mutations land before the host event we emit next.
			final String catalog = TEST_CATALOG + "_ordering";
			evita.defineCatalog(catalog);
			evita.updateCatalog(catalog, EvitaSessionContract::goLiveAndClose);

			// Emit the host event immediately. There is no race between the engine-mutation
			// drain and this host event because `processHostEvent` enqueues the delivery via the
			// per-subscription executor — the same executor that drains queued mutations — so the
			// host event lands strictly after every engine mutation submitted before it.
			evita.getChangeObserver().processHostEvent(
				new HostSystemEvent.CatalogInstalledIntoLiveView(
					catalog, CatalogState.ALIVE, evita.getEngineState().version()
				)
			);

			assertTrue(
				countEngineMutations(subscriber) >= 1,
				"Should have received at least one engine mutation before the host event"
			);
			assertTrue(
				countHostEvents(subscriber) >= 1,
				"Host event should have been delivered after the engine mutation"
			);
			// Find the index of the first host event in the subscriber's stream and verify that
			// at least one engine mutation arrived earlier — i.e. the host event landed strictly
			// after preceding mutation traffic.
			int hostEventIndex = -1;
			for (int i = 0; i < subscriber.getItems().size(); i++) {
				if (subscriber.getItems().get(i).body() instanceof HostSystemEvent) {
					hostEventIndex = i;
					break;
				}
			}
			assertTrue(
				hostEventIndex > 0,
				"Host event should not be the first item — at least one mutation must precede it"
			);
		}
	}

	/**
	 * Verifies that a `null`-area sentinel inside an explicit criterion entry behaves like
	 * "match any area" — host events are delivered to the subscriber even though the criterion
	 * does not name `HOST` explicitly. This is the documented OR-of-criteria semantics
	 * on `ChangeSystemCaptureCriteria`, distinct from the null-criteria-array default.
	 *
	 * @param evita the Evita database instance
	 */
	@UseDataSet(value = SYSTEM_CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("deliver host events when criteria contains a null-area sentinel")
	void shouldDeliverHostEventWithSinglyNullAreaCriterion(@Nonnull Evita evita) {
		final long currentVersion = evita.getEngineState().version();

		// Single-criterion request with `null` area means "match any area on the system stream"
		// — the host event must be delivered, just like an explicit HOST opt-in.
		final ChangeSystemCaptureRequest nullAreaRequest = new ChangeSystemCaptureRequest(
			currentVersion + 1,
			0,
			new ChangeSystemCaptureCriteria[] { new ChangeSystemCaptureCriteria(null) },
			ChangeCaptureContent.BODY
		);

		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher =
				evita.registerSystemChangeCapture(nullAreaRequest)
		) {
			final MockSystemChangeSubscriber subscriber = new MockSystemChangeSubscriber();
			publisher.subscribe(subscriber);

			evita.getChangeObserver().processHostEvent(
				new HostSystemEvent.CatalogInstalledIntoLiveView(
					"nullAreaTarget", CatalogState.ALIVE, currentVersion
				)
			);

			assertEquals(
				1, countHostEvents(subscriber),
				"Null-area criterion (match-any) must deliver host events"
			);
		}
	}

	/**
	 * An explicitly empty criteria array selects no areas — neither engine mutations nor host
	 * events should be delivered. This is the FalsePredicate semantic on the predicate factory.
	 *
	 * @param evita the Evita database instance
	 */
	@UseDataSet(value = SYSTEM_CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("deliver no host events when criteria array is empty")
	void shouldNotDeliverHostEventWhenCriteriaArrayIsEmpty(@Nonnull Evita evita) {
		final long currentVersion = evita.getEngineState().version();

		// Empty criteria array — explicitly select nothing. The predicate factory installs a
		// FalsePredicate for the engine path and a constant-false predicate for the host event
		// path; the subscriber must therefore see no host events.
		final ChangeSystemCaptureRequest emptyRequest = new ChangeSystemCaptureRequest(
			currentVersion + 1,
			0,
			new ChangeSystemCaptureCriteria[0],
			ChangeCaptureContent.BODY
		);

		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher =
				evita.registerSystemChangeCapture(emptyRequest)
		) {
			final MockSystemChangeSubscriber subscriber = new MockSystemChangeSubscriber();
			publisher.subscribe(subscriber);

			evita.getChangeObserver().processHostEvent(
				new HostSystemEvent.CatalogInstalledIntoLiveView(
					"emptyCriteriaTarget", CatalogState.ALIVE, currentVersion
				)
			);

			assertEquals(
				0, countHostEvents(subscriber),
				"Empty criteria array must reject every host event"
			);
		}
	}

	/**
	 * End-to-end verification that deleting a catalog through the engine drives the
	 * `CatalogRemovedFromLiveView` host event to a HOST subscriber. This exercises
	 * the actual `RemoveCatalogSchemaMutation` path and the operator's completion-phase emit,
	 * not a synthetic `processHostEvent` call.
	 *
	 * @param evita the Evita database instance
	 */
	@UseDataSet(value = SYSTEM_CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("emit CatalogRemovedFromLiveView when a catalog is deleted")
	void shouldEmitCatalogRemovedFromLiveViewOnDeleteCatalog(@Nonnull Evita evita) throws InterruptedException {
		// Pre-create a catalog that will be deleted. Subscribing AFTER the create avoids
		// interleaving CatalogInstalledIntoLiveView events that would muddy the assertion.
		final String victim = TEST_CATALOG + "_to_delete";
		evita.defineCatalog(victim);
		evita.updateCatalog(victim, EvitaSessionContract::goLiveAndClose);

		final long currentVersion = evita.getEngineState().version();
		final ChangeSystemCaptureRequest infraRequest = ChangeSystemCaptureRequest.builder()
			.sinceVersion(currentVersion + 1)
			.content(ChangeCaptureContent.BODY)
			.hostArea()
			.build();

		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher =
				evita.registerSystemChangeCapture(infraRequest)
		) {
			final MockSystemChangeSubscriber subscriber = new MockSystemChangeSubscriber();
			publisher.subscribe(subscriber);

			// Drive the actual RemoveCatalogSchemaMutation path through the public engine API.
			// `deleteCatalogIfExists` blocks on completion via `Progress#onCompletion().toCompletableFuture().join()`,
			// so when this call returns the catalog has been fully removed from the engine.
			evita.deleteCatalogIfExists(victim);

			// Poll for the asynchronous host event delivery; the dispatcher is async and there
			// is no synchronous handle to wait on.
			final long deadline = System.currentTimeMillis() + 5_000L;
			while (
				hostEventsFor(subscriber, victim, HostSystemEvent.CatalogRemovedFromLiveView.class).isEmpty()
					&& System.currentTimeMillis() < deadline
			) {
				Thread.sleep(50L);
			}

			final List<HostSystemEvent.CatalogRemovedFromLiveView> received = hostEventsFor(
				subscriber, victim, HostSystemEvent.CatalogRemovedFromLiveView.class
			);
			assertFalse(
				received.isEmpty(),
				"Deleting a catalog must emit CatalogRemovedFromLiveView for that catalog"
			);
			assertEquals(victim, received.get(0).catalogName());
		}
	}

	/**
	 * Polls the subscriber's captured items until at least `expectedCount` host events of the given
	 * type for the given catalog have been observed, or the deadline elapses. Returns the matching
	 * events (which may include more than `expectedCount` if extra events were delivered while the
	 * loop was blocked on `Thread.sleep`). Callers assert the exact count on the returned list so
	 * spurious extras surface as failures rather than being silently masked.
	 *
	 * The 50 ms cadence mirrors the existing `shouldEmitCatalogRemovedFromLiveViewOnDeleteCatalog`
	 * polling loop in this class — short enough to keep the test wall-clock close to dispatch
	 * latency, long enough to avoid CPU spin.
	 *
	 * @param subscriber    subscriber whose captured items are scanned; never `null`
	 * @param catalogName   catalog name to match on the host event; never `null`
	 * @param eventType     host-event subtype class to match; never `null`
	 * @param expectedCount minimum number of matching events required before returning early;
	 *                      pass `0` to simply drain the dispatcher up to the timeout
	 * @param timeoutMs     maximum wall-clock time to wait, in milliseconds
	 * @return list of host events matching the filter, in arrival order
	 */
	@Nonnull
	private static <T extends HostSystemEvent> List<T> waitForHostEventsFor(
		@Nonnull MockSystemChangeSubscriber subscriber,
		@Nonnull String catalogName,
		@Nonnull Class<T> eventType,
		final int expectedCount,
		final long timeoutMs
	) throws InterruptedException {
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			final List<T> matches = hostEventsFor(subscriber, catalogName, eventType);
			if (expectedCount > 0 && matches.size() >= expectedCount) {
				return matches;
			}
			Thread.sleep(50L);
		}
		return hostEventsFor(subscriber, catalogName, eventType);
	}

	/**
	 * Verifies the coalescing contract for WARMING_UP sessions: a single read-write
	 * session that defines an entity schema and adds five attributes must produce **exactly one**
	 * `CatalogSchemaUpdated` host event when it closes, regardless of how many internal
	 * `ModifyCatalogSchemaMutation` operators ran. The event's `catalogName` must match the target
	 * catalog and its `newSchemaVersion` must be the catalog's settled schema version on this host
	 * (`>= 1`).
	 *
	 * Why this matters: GraphQL/REST observers used to refresh per individual schema mutation,
	 * causing a refresh storm during multi-attribute schema bootstrap. The coalesced event lets
	 * those observers refresh exactly once per session.
	 *
	 * @param evita the Evita database instance
	 */
	@UseDataSet(value = SYSTEM_CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("emit exactly one CatalogSchemaUpdated per WARMING_UP session that bumps the schema")
	void shouldEmitOneCatalogSchemaUpdatedPerWarmingUpSession(@Nonnull Evita evita) throws InterruptedException {
		// pre-create the target catalog so the WARMING_UP session below starts with a known
		// `startCatalogSchemaVersion`; the create itself fires `CatalogInstalledIntoLiveView` (not a
		// schema-updated event), so it does not pollute the count we assert on.
		final String catalog = TEST_CATALOG + "_warmingUpSchema";
		evita.defineCatalog(catalog);

		// subscribe to HOST events AFTER the create so the activation host events do not interleave
		// with the schema-updated event we are isolating.
		final long currentVersion = evita.getEngineState().version();
		final ChangeSystemCaptureRequest infraRequest = ChangeSystemCaptureRequest.builder()
			.sinceVersion(currentVersion + 1)
			.content(ChangeCaptureContent.BODY)
			.hostArea()
			.build();

		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher =
				evita.registerSystemChangeCapture(infraRequest)
		) {
			final MockSystemChangeSubscriber subscriber = new MockSystemChangeSubscriber();
			publisher.subscribe(subscriber);

			// drive a WARMING_UP read-write session: define an entity schema and add 5 attributes.
			// The catalog stays in WARMING_UP because we never call `goLiveAndClose`, so the emit
			// site is `EvitaSession#executeTerminationSteps` — which gates on
			// `finalSchemaVersion > startCatalogSchemaVersion`. Multiple internal schema mutations
			// must coalesce into a single CatalogSchemaUpdated.
			evita.updateCatalog(
				catalog, session -> {
					session.defineEntitySchema("Product")
						.withAttribute("code", String.class)
						.withAttribute("name", String.class)
						.withAttribute("priority", Long.class)
						.withAttribute("ean", String.class)
						.withAttribute("manufactured", java.time.LocalDate.class)
						.updateVia(session);
					return null;
				}
			);

			// poll up to a generous deadline — async dispatcher latency on a loaded CI host can run
			// into a few hundred milliseconds.
			final List<HostSystemEvent.CatalogSchemaUpdated> received = waitForHostEventsFor(
				subscriber, catalog, HostSystemEvent.CatalogSchemaUpdated.class, 1, 5_000L
			);
			assertEquals(
				1, received.size(),
				"Exactly one CatalogSchemaUpdated must fire per WARMING_UP session that bumped the schema; got " + received.size()
			);
			final HostSystemEvent.CatalogSchemaUpdated event = received.get(0);
			assertEquals(catalog, event.catalogName());
			assertTrue(
				event.newSchemaVersion() >= 1,
				"newSchemaVersion must be >= 1 after entity-schema creation; got " + event.newSchemaVersion()
			);
		} finally {
			// Tear down the helper catalog so the destroyAfterTest path is not surprised by leftovers.
			evita.deleteCatalogIfExists(catalog);
		}
	}

	/**
	 * F5 regression guard: a WARMING_UP session whose termination callback throws
	 * must STILL emit `CatalogSchemaUpdated` for the schema mutations it persisted. The schema is
	 * already committed by the time the termination callback runs, so failing to emit would leave
	 * downstream observers (GraphQL/REST) with a stale schema view.
	 *
	 * The fix moves the emit into the `finally` block of `EvitaSession#executeTerminationSteps`
	 * with the gating decision captured BEFORE the try-catch — so the rethrown
	 * `TransactionException` from the callback failure does not skip the emit.
	 *
	 * @param evita the Evita database instance
	 */
	@UseDataSet(value = SYSTEM_CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("emit CatalogSchemaUpdated even when termination callback throws on WARMING_UP session")
	void shouldEmitCatalogSchemaUpdatedWhenTerminationCallbackThrows(@Nonnull Evita evita)
		throws InterruptedException {
		final String catalog = TEST_CATALOG + "_warmingUpThrowingCallback";
		evita.defineCatalog(catalog);

		final long currentVersion = evita.getEngineState().version();
		final ChangeSystemCaptureRequest infraRequest = ChangeSystemCaptureRequest.builder()
			.sinceVersion(currentVersion + 1)
			.content(ChangeCaptureContent.BODY)
			.hostArea()
			.build();

		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher =
				evita.registerSystemChangeCapture(infraRequest)
		) {
			final MockSystemChangeSubscriber subscriber = new MockSystemChangeSubscriber();
			publisher.subscribe(subscriber);

			final AtomicBoolean callbackInvoked = new AtomicBoolean(false);

			// Open a WARMING_UP session, then reflectively replace its `terminationCallback` with
			// one that runs the original engine cleanup AND throws. The public
			// `SessionTraits.onTermination` parameter is silently dropped by the engine path
			// (`Evita.createInternalSession` builds its own cleanup callback), so the only way to
			// inject a misbehaving termination callback is via direct field write on `EvitaSession`.
			// Schema mutations are then persisted; `close()` runs the throwing callback inside
			// `executeTerminationSteps`. The F5 fix puts the schema-updated emit in `finally`, so
			// the HOST subscriber must still see the event despite the callback failure.
			//
			// FRAGILE: this test depends on three private surface points whose names are NOT
			// part of any compile-time contract. If a future refactor renames any of them, this
			// test will fail with a reflection error rather than a real regression — please
			// update the field/handler names below rather than deleting the test:
			//   1. `Proxy.getInvocationHandler(session)` must return the `EvitaSessionProxy`
			//      handler (depends on `EvitaSession` being JDK-proxied at session-creation time).
			//   2. The proxy handler's underlying `EvitaSession` instance is read from the
			//      `evitaSession` field of `EvitaSessionProxy`.
			//   3. The termination callback is held in `EvitaSession.terminationCallback`.
			try (final EvitaSessionContract session = evita.createSession(new SessionTraits(catalog, SessionFlags.READ_WRITE))) {
				final Object proxyHandler = Proxy.getInvocationHandler(session);
				final Object underlyingSession = getNonnullFieldValue(proxyHandler, "evitaSession");
				final EvitaSessionTerminationCallback originalCallback =
					getNonnullFieldValue(underlyingSession, "terminationCallback");
				final EvitaSessionTerminationCallback throwingCallback = sessionRef -> {
					originalCallback.onTermination(sessionRef);
					callbackInvoked.set(true);
					throw new RuntimeException("synthetic termination failure for F5 regression test");
				};
				setFieldValue(underlyingSession, "terminationCallback", throwingCallback);

				session.defineEntitySchema("Product")
					.withAttribute("code", String.class)
					.withAttribute("name", String.class)
					.updateVia(session);
			}
			assertTrue(
				callbackInvoked.get(),
				"Throwing termination callback must have actually run"
			);

			// The load-bearing assertion: despite the throwing callback, the schema-updated emit
			// must still have fired (now lives in `finally`, not after the try-catch).
			final List<HostSystemEvent.CatalogSchemaUpdated> received = waitForHostEventsFor(
				subscriber, catalog, HostSystemEvent.CatalogSchemaUpdated.class, 1, 5_000L
			);
			assertEquals(
				1, received.size(),
				"CatalogSchemaUpdated must fire even when the termination callback throws; got "
					+ received.size() + " events"
			);
			final HostSystemEvent.CatalogSchemaUpdated event = received.get(0);
			assertEquals(catalog, event.catalogName());
			assertTrue(
				event.newSchemaVersion() >= 1,
				"newSchemaVersion must reflect the post-mutation schema version; got " + event.newSchemaVersion()
			);
		} finally {
			evita.deleteCatalogIfExists(catalog);
		}
	}

	/**
	 * Verifies the gating contract for read-only WARMING_UP sessions: a session that
	 * never advances the schema version (here, a session that only runs a query) must not emit any
	 * `CatalogSchemaUpdated` event when it closes. The
	 * `EvitaSession#executeTerminationSteps` guard `finalSchemaVersion > startCatalogSchemaVersion`
	 * is the mechanism under test.
	 *
	 * @param evita the Evita database instance
	 */
	@UseDataSet(value = SYSTEM_CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("emit no CatalogSchemaUpdated for read-only WARMING_UP session")
	void shouldNotEmitCatalogSchemaUpdatedForReadOnlyWarmingUpSession(
		@Nonnull Evita evita
	) throws InterruptedException {
		// pre-create the target catalog and subscribe AFTER the create, identically to the
		// happy-path test above — only the session content differs.
		final String catalog = TEST_CATALOG + "_warmingUpReadOnly";
		evita.defineCatalog(catalog);

		final long currentVersion = evita.getEngineState().version();
		final ChangeSystemCaptureRequest infraRequest = ChangeSystemCaptureRequest.builder()
			.sinceVersion(currentVersion + 1)
			.content(ChangeCaptureContent.BODY)
			.hostArea()
			.build();

		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher =
				evita.registerSystemChangeCapture(infraRequest)
		) {
			final MockSystemChangeSubscriber subscriber = new MockSystemChangeSubscriber();
			publisher.subscribe(subscriber);

			// Pure read session — no schema mutations. The session opens, lists the (empty) entity
			// types, and closes. `finalSchemaVersion == startCatalogSchemaVersion` so the gate
			// rejects the emit.
			try (final EvitaSessionContract session = evita.createReadOnlySession(catalog)) {
				// trivial read; the result is intentionally ignored — what matters is that no
				// schema-mutating operation runs.
				session.getAllEntityTypes();
			}

			// Drain the dispatcher for a short window to give any spurious emit time to land —
			// then assert zero.
			final List<HostSystemEvent.CatalogSchemaUpdated> received = waitForHostEventsFor(
				subscriber, catalog, HostSystemEvent.CatalogSchemaUpdated.class, 0, 500L
			);
			assertEquals(
				0, received.size(),
				"Read-only WARMING_UP session must not emit CatalogSchemaUpdated; got " + received.size()
			);
		} finally {
			evita.deleteCatalogIfExists(catalog);
		}
	}

	/**
	 * Verifies the coalescing contract for ALIVE catalogs: a single transaction that
	 * adds three attributes to an existing entity schema must produce **exactly one**
	 * `CatalogSchemaUpdated` host event after commit, regardless of the number of internal schema
	 * mutations. The emit site under test is `Evita#replaceCatalogReference`, gated on
	 * `newSchemaVersion > priorSchemaVersion`.
	 *
	 * @param evita the Evita database instance
	 */
	@UseDataSet(value = SYSTEM_CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("emit exactly one CatalogSchemaUpdated per schema transaction on ALIVE catalog")
	void shouldEmitOneCatalogSchemaUpdatedPerSchemaTransactionOnAlive(
		@Nonnull Evita evita
	) throws InterruptedException {
		// Bring a fresh catalog to ALIVE first. The activation path emits warming-up host events
		// that we do not want to interleave with the assertion — so we subscribe AFTER ALIVE.
		final String catalog = TEST_CATALOG + "_aliveSchema";
		evita.defineCatalog(catalog);
		// seed an entity schema in WARMING_UP so the ALIVE transaction below has something to
		// modify (adding attributes to an existing collection rather than creating one).
		evita.updateCatalog(
			catalog, session -> {
				session.defineEntitySchema("Product").updateVia(session);
				return null;
			}
		);
		evita.updateCatalog(catalog, EvitaSessionContract::goLiveAndClose);
		assertEquals(
			CatalogState.ALIVE, evita.getCatalogState(catalog).orElseThrow(),
			"Pre-condition: catalog must be ALIVE before the schema transaction is issued"
		);

		final long currentVersion = evita.getEngineState().version();
		final ChangeSystemCaptureRequest infraRequest = ChangeSystemCaptureRequest.builder()
			.sinceVersion(currentVersion + 1)
			.content(ChangeCaptureContent.BODY)
			.hostArea()
			.build();

		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher =
				evita.registerSystemChangeCapture(infraRequest)
		) {
			final MockSystemChangeSubscriber subscriber = new MockSystemChangeSubscriber();
			publisher.subscribe(subscriber);

			// Add three attributes within one transaction. The transaction-commit path funnels
			// through `Evita#replaceCatalogReference`, which emits a single CatalogSchemaUpdated
			// for the whole commit (all three attribute mutations coalesce).
			evita.updateCatalog(
				catalog, session -> {
					session.getEntitySchemaOrThrowException("Product")
						.openForWrite()
						.withAttribute("code", String.class)
						.withAttribute("name", String.class)
						.withAttribute("priority", Long.class)
						.updateVia(session);
					return null;
				}
			);

			final List<HostSystemEvent.CatalogSchemaUpdated> received = waitForHostEventsFor(
				subscriber, catalog, HostSystemEvent.CatalogSchemaUpdated.class, 1, 5_000L
			);
			assertEquals(
				1, received.size(),
				"Exactly one CatalogSchemaUpdated must fire per ALIVE schema transaction; got " + received.size()
			);
			final HostSystemEvent.CatalogSchemaUpdated event = received.get(0);
			assertEquals(catalog, event.catalogName());
			assertTrue(
				event.newSchemaVersion() >= 1,
				"newSchemaVersion must reflect the post-commit schema version; got " + event.newSchemaVersion()
			);
		} finally {
			evita.deleteCatalogIfExists(catalog);
		}
	}

	/**
	 * Verifies the gating contracts for data-only ALIVE transactions. When ~10 entities
	 * are upserted in a single transaction without touching the schema, **no** new
	 * `CatalogSchemaUpdated` events should fire (data-only commits do not advance schema version)
	 * AND **no** new `CatalogInstalledIntoLiveView` events should fire (the per-commit pulse was
	 * deliberately dropped; only real state transitions emit `CatalogInstalledIntoLiveView`).
	 *
	 * The latter is the critical regression guard for the gating change in
	 * `Evita#replaceCatalogReference`: previously every commit emitted a `CatalogInstalledIntoLiveView`
	 * heartbeat regardless of whether the catalog actually transitioned states.
	 *
	 * @param evita the Evita database instance
	 */
	@UseDataSet(value = SYSTEM_CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("emit no host events for data-only transaction on ALIVE catalog")
	void shouldNotEmitCatalogSchemaUpdatedForDataOnlyTransactionOnAlive(
		@Nonnull Evita evita
	) throws InterruptedException {
		// Build an ALIVE catalog with a Product schema sufficient for raw upserts. We define a
		// minimal entity collection (no required attributes) so the upsert path does not need to
		// run any schema mutation.
		final String catalog = TEST_CATALOG + "_aliveDataOnly";
		evita.defineCatalog(catalog);
		evita.updateCatalog(
			catalog, session -> {
				session.defineEntitySchema("Product").updateVia(session);
				return null;
			}
		);
		evita.updateCatalog(catalog, EvitaSessionContract::goLiveAndClose);
		assertEquals(
			CatalogState.ALIVE, evita.getCatalogState(catalog).orElseThrow(),
			"Pre-condition: catalog must be ALIVE before the data-only transaction is issued"
		);

		final long currentVersion = evita.getEngineState().version();
		final ChangeSystemCaptureRequest infraRequest = ChangeSystemCaptureRequest.builder()
			.sinceVersion(currentVersion + 1)
			.content(ChangeCaptureContent.BODY)
			.hostArea()
			.build();

		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher =
				evita.registerSystemChangeCapture(infraRequest)
		) {
			final MockSystemChangeSubscriber subscriber = new MockSystemChangeSubscriber();
			publisher.subscribe(subscriber);

			// Pure data-only transaction: upsert 10 Product entities. No schema mutation runs, so
			// `replaceCatalogReference` sees `newSchemaVersion == priorSchemaVersion` AND
			// `priorState == catalog.getCatalogState()` — both gates reject the emit.
			evita.updateCatalog(
				catalog, session -> {
					for (int i = 1; i <= 10; i++) {
						session.upsertEntity(session.createNewEntity("Product", i));
					}
					return null;
				}
			);

			// Drain the dispatcher long enough for any spurious emit to land.
			Thread.sleep(500L);

			final List<HostSystemEvent.CatalogSchemaUpdated> schemaEvents = hostEventsFor(
				subscriber, catalog, HostSystemEvent.CatalogSchemaUpdated.class
			);
			assertEquals(
				0, schemaEvents.size(),
				"Data-only ALIVE transaction must not emit CatalogSchemaUpdated; got " + schemaEvents.size()
			);

			final List<HostSystemEvent.CatalogInstalledIntoLiveView> installedEvents = hostEventsFor(
				subscriber, catalog, HostSystemEvent.CatalogInstalledIntoLiveView.class
			);
			assertEquals(
				0, installedEvents.size(),
				"Data-only ALIVE transaction must not emit CatalogInstalledIntoLiveView (per-commit pulse "
					+ "was deliberately dropped); got " + installedEvents.size()
			);
		} finally {
			evita.deleteCatalogIfExists(catalog);
		}
	}

	/**
	 * Alternate framing of `shouldNotEmitCatalogSchemaUpdatedForDataOnlyTransactionOnAlive` that
	 * focuses **only** on the `CatalogInstalledIntoLiveView` gating side. Kept separate so the two
	 * regression guards can be split into different test classes later without losing either
	 * intent: this test is the canonical "no per-commit heartbeat" guard.
	 *
	 * The `Evita#replaceCatalogReference` change gates the install event on
	 * `priorState != catalog.getCatalogState()`; a pure data-only commit on an ALIVE catalog has
	 * `priorState == ALIVE == newState`, so no install event must fire.
	 *
	 * @param evita the Evita database instance
	 */
	@UseDataSet(value = SYSTEM_CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("emit no CatalogInstalledIntoLiveView per data-only commit on ALIVE catalog")
	void shouldNotEmitCatalogInstalledIntoLiveViewPerCommit(
		@Nonnull Evita evita
	) throws InterruptedException {
		// Same setup as the data-only test above. The duplication is intentional: this test asserts
		// the gating side from the operator/state-transition perspective rather than the schema
		// version perspective. Splitting these into two test methods makes either failure mode
		// triage to a single, focused signal.
		final String catalog = TEST_CATALOG + "_aliveNoPulse";
		evita.defineCatalog(catalog);
		evita.updateCatalog(
			catalog, session -> {
				session.defineEntitySchema("Product").updateVia(session);
				return null;
			}
		);
		evita.updateCatalog(catalog, EvitaSessionContract::goLiveAndClose);

		final long currentVersion = evita.getEngineState().version();
		final ChangeSystemCaptureRequest infraRequest = ChangeSystemCaptureRequest.builder()
			.sinceVersion(currentVersion + 1)
			.content(ChangeCaptureContent.BODY)
			.hostArea()
			.build();

		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher =
				evita.registerSystemChangeCapture(infraRequest)
		) {
			final MockSystemChangeSubscriber subscriber = new MockSystemChangeSubscriber();
			publisher.subscribe(subscriber);

			// Five back-to-back data-only commits to maximise the chance that any reintroduced
			// per-commit pulse would surface as a count > 0.
			for (int i = 0; i < 5; i++) {
				final int batchOffset = i * 10;
				evita.updateCatalog(
					catalog, session -> {
						for (int j = 1; j <= 10; j++) {
							session.upsertEntity(session.createNewEntity("Product", batchOffset + j));
						}
						return null;
					}
				);
			}

			Thread.sleep(500L);

			final List<HostSystemEvent.CatalogInstalledIntoLiveView> installedEvents = hostEventsFor(
				subscriber, catalog, HostSystemEvent.CatalogInstalledIntoLiveView.class
			);
			assertEquals(
				0, installedEvents.size(),
				"No CatalogInstalledIntoLiveView heartbeat must fire for data-only commits on an "
					+ "ALIVE catalog (state did not transition); got " + installedEvents.size()
			);
		} finally {
			evita.deleteCatalogIfExists(catalog);
		}
	}

	/**
	 * G1: post-`evita.close()` invocation of `notifyCatalogSchemaUpdated` must be a
	 * silent no-op (mirroring `notifyCatalogStateSettled`'s shutdown-defensive contract). This
	 * locks down the late-emit shutdown path so an in-flight session-close termination step that
	 * reaches the emit after the engine has begun tearing down does not wedge the operator's
	 * future with `InstanceTerminatedException` — host events are best-effort on shutdown.
	 *
	 * Mirrors `BootTimeAutoUpgradeHostEventTest#shouldNotThrowWhenHostEventEmittedAfterClose` for
	 * the new `CatalogSchemaUpdated` variant.
	 */
	@Test
	@DisplayName("not throw when notifyCatalogSchemaUpdated fires after engine close")
	void shouldNotThrowWhenSchemaUpdatedEmittedAfterClose() {
		// build a one-shot Evita instance so we can close it without taking down the shared dataset
		final TestPaths testPaths = createTestPaths(
			SystemChangeObserverTest.class.getSimpleName() + "_g1ShutdownDefensive"
		);
		final Evita standalone = new Evita(newTestEvitaConfigurationBuilder(testPaths).build());
		try {
			standalone.defineCatalog(TEST_CATALOG + "_g1");
			standalone.close();
			// post-close emit must NOT throw — silent no-op contract from issue #1151
			standalone.notifyCatalogSchemaUpdated(TEST_CATALOG + "_g1", 5);
		} finally {
			cleanupTestPaths(testPaths);
		}
	}

	/**
	 * G2: a fresh `defineCatalog` (first install) emits exactly
	 * `CatalogInstalledIntoLiveView` so downstream observers (GraphQL/REST/gRPC) register
	 * endpoints on first creation. The first-install path does NOT go through
	 * `Evita#replaceCatalogReference` — `CreateCatalogMutationOperator` calls
	 * `notifyCatalogStateSettled` directly — so the coalesced `CatalogSchemaUpdated` event
	 * does not fire on a bare `defineCatalog`. Schema-update fan-out is driven by the
	 * `CreateCatalogSchemaMutation` engine-mutation branch in the GraphQL/REST observers
	 * (which calls `registerCatalog`), not by a host event.
	 *
	 * Design note: only `CatalogInstalledIntoLiveView` fires on first install — not
	 * `CatalogSchemaUpdated`. This is intentional: `registerCatalog` already handles
	 * first-install endpoint setup directly via the `CreateCatalogSchemaMutation` engine
	 * mutation. Whether to also emit `CatalogSchemaUpdated` for symmetry with
	 * `replaceCatalogReference` is a candidate follow-up but not in scope for the schema-coalescing change.
	 */
	@UseDataSet(value = SYSTEM_CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("emit CatalogInstalledIntoLiveView on first install (defineCatalog)")
	void shouldEmitInstalledIntoLiveViewOnFirstInstall(@Nonnull Evita evita) throws InterruptedException {
		final long currentVersion = evita.getEngineState().version();

		final ChangeSystemCaptureRequest infraRequest = ChangeSystemCaptureRequest.builder()
			.sinceVersion(currentVersion + 1)
			.content(ChangeCaptureContent.BODY)
			.hostArea()
			.build();

		final String catalog = TEST_CATALOG + "_g2FirstInstall";
		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher =
				evita.registerSystemChangeCapture(infraRequest)
		) {
			final MockSystemChangeSubscriber subscriber = new MockSystemChangeSubscriber();
			publisher.subscribe(subscriber);

			// first install via `defineCatalog` goes through `CreateCatalogMutationOperator` which
			// calls `notifyCatalogStateSettled` directly, bypassing `replaceCatalogReference`. As a
			// result only `CatalogInstalledIntoLiveView` fires here — `CatalogSchemaUpdated` is NOT
			// emitted on bare `defineCatalog`. Schema fan-out for first-install is driven by the
			// `CreateCatalogSchemaMutation` engine-mutation branch in the GraphQL/REST observers
			// (which calls `registerCatalog`). The asymmetry vs `replaceCatalogReference` is
			// intentional and tracked as a follow-up candidate for the schema-coalescing work.
			evita.defineCatalog(catalog);

			final List<HostSystemEvent.CatalogInstalledIntoLiveView> installedEvents = waitForHostEventsFor(
				subscriber, catalog, HostSystemEvent.CatalogInstalledIntoLiveView.class, 1, 5_000L
			);
			final List<HostSystemEvent.CatalogSchemaUpdated> schemaUpdatedEvents = waitForHostEventsFor(
				subscriber, catalog, HostSystemEvent.CatalogSchemaUpdated.class, 0, 500L
			);
			assertFalse(
				installedEvents.isEmpty(),
				"First install must emit CatalogInstalledIntoLiveView; got "
					+ installedEvents.size() + " events"
			);
			assertTrue(
				schemaUpdatedEvents.isEmpty(),
				"First install via defineCatalog must NOT emit CatalogSchemaUpdated " +
					"(CreateCatalogMutationOperator bypasses replaceCatalogReference); got "
					+ schemaUpdatedEvents.size() + " events"
			);
		} finally {
			evita.deleteCatalogIfExists(catalog);
		}
	}

	/**
	 * G3: a state-only transition (WARMING_UP → ALIVE via `goLiveAndClose` without
	 * any preceding schema work in the WARMING_UP session) must emit `CatalogInstalledIntoLiveView`
	 * but NOT `CatalogSchemaUpdated`. The state gate opens (priorState != newState) while the
	 * schema gate stays closed (newSchemaVersion == priorSchemaVersion).
	 *
	 * This is the regression guard for the "state changed but schema did not" symmetric case to
	 * `shouldNotEmitCatalogSchemaUpdatedForDataOnlyTransactionOnAlive` (which tests the opposite
	 * "neither changed" case).
	 */
	@UseDataSet(value = SYSTEM_CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("emit only CatalogInstalledIntoLiveView for state-only transition with no schema work")
	void shouldNotEmitCatalogSchemaUpdatedForStateOnlyTransition(
		@Nonnull Evita evita
	) throws InterruptedException {
		// pre-create the catalog in WARMING_UP — its first-install events fire on this `defineCatalog`
		// call; we subscribe AFTER so those don't pollute the assertion below.
		final String catalog = TEST_CATALOG + "_g3StateOnly";
		evita.defineCatalog(catalog);

		final long currentVersion = evita.getEngineState().version();
		final ChangeSystemCaptureRequest infraRequest = ChangeSystemCaptureRequest.builder()
			.sinceVersion(currentVersion + 1)
			.content(ChangeCaptureContent.BODY)
			.hostArea()
			.build();

		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher =
				evita.registerSystemChangeCapture(infraRequest)
		) {
			final MockSystemChangeSubscriber subscriber = new MockSystemChangeSubscriber();
			publisher.subscribe(subscriber);

			// pure state transition WARMING_UP → ALIVE; no schema-mutating operation runs in this
			// session, so the schema version stays unchanged.
			evita.updateCatalog(catalog, EvitaSessionContract::goLiveAndClose);

			// CatalogInstalledIntoLiveView(ALIVE) must arrive — state actually changed
			final List<HostSystemEvent.CatalogInstalledIntoLiveView> installedEvents = waitForHostEventsFor(
				subscriber, catalog, HostSystemEvent.CatalogInstalledIntoLiveView.class, 1, 5_000L
			);
			assertEquals(
				1, installedEvents.size(),
				"Exactly one CatalogInstalledIntoLiveView must fire for the WARMING_UP→ALIVE transition; got "
					+ installedEvents.size()
			);
			assertEquals(
				CatalogState.ALIVE, installedEvents.get(0).observedState(),
				"observedState must be ALIVE for the goLiveAndClose transition"
			);

			// drain dispatcher long enough for any spurious CatalogSchemaUpdated to appear, then assert zero
			final List<HostSystemEvent.CatalogSchemaUpdated> schemaUpdatedEvents = waitForHostEventsFor(
				subscriber, catalog, HostSystemEvent.CatalogSchemaUpdated.class, 0, 500L
			);
			assertEquals(
				0, schemaUpdatedEvents.size(),
				"State-only transition (no schema work) must not emit CatalogSchemaUpdated; got "
					+ schemaUpdatedEvents.size()
			);
		} finally {
			evita.deleteCatalogIfExists(catalog);
		}
	}

	/**
	 * G5: in a WARMING_UP session that BOTH bumps the schema version AND triggers a
	 * state transition (via `goLiveAndClose` after schema work), exactly ONE `CatalogSchemaUpdated`
	 * event must fire — not two. The double-emit guard lives in `EvitaSession#executeTerminationSteps`:
	 * it only fires the schema-updated emit when `theCatalog.getCatalogState() != ALIVE` so the
	 * transaction-commit path (`Evita#replaceCatalogReference`) owns the emit once the catalog goes
	 * ALIVE.
	 */
	@UseDataSet(value = SYSTEM_CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("emit exactly one CatalogSchemaUpdated when WARMING_UP session bumps schema and goes live")
	void shouldEmitOneCatalogSchemaUpdatedWhenSchemaBumpAndStateTransitionInOneSession(
		@Nonnull Evita evita
	) throws InterruptedException {
		// pre-create the catalog so its first-install events do not pollute the assertion
		final String catalog = TEST_CATALOG + "_g5DoubleEmit";
		evita.defineCatalog(catalog);

		final long currentVersion = evita.getEngineState().version();
		final ChangeSystemCaptureRequest infraRequest = ChangeSystemCaptureRequest.builder()
			.sinceVersion(currentVersion + 1)
			.content(ChangeCaptureContent.BODY)
			.hostArea()
			.build();

		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher =
				evita.registerSystemChangeCapture(infraRequest)
		) {
			final MockSystemChangeSubscriber subscriber = new MockSystemChangeSubscriber();
			publisher.subscribe(subscriber);

			// First session: bump the schema in WARMING_UP. The session-close path emits exactly one
			// CatalogSchemaUpdated because the catalog is still WARMING_UP (the gate
			// `theCatalog.getCatalogState() != ALIVE` opens) AND finalSchemaVersion > startVersion.
			evita.updateCatalog(
				catalog, session -> {
					session.defineEntitySchema("Product").updateVia(session);
					return null;
				}
			);
			// Second session: trigger the state transition WARMING_UP → ALIVE. By the time this
			// session's `executeTerminationSteps` runs, the catalog state has already settled to
			// ALIVE during the goLiveAndClose call so the double-emit gate suppresses any
			// session-close emit. The transaction-commit path (replaceCatalogReference) does not
			// fire a schema-updated event either because schema version did not change.
			evita.updateCatalog(catalog, EvitaSessionContract::goLiveAndClose);

			// Drain dispatcher long enough for any extra event to land; assert exactly one.
			Thread.sleep(500L);
			final List<HostSystemEvent.CatalogSchemaUpdated> schemaUpdatedEvents = hostEventsFor(
				subscriber, catalog, HostSystemEvent.CatalogSchemaUpdated.class
			);
			assertEquals(
				1, schemaUpdatedEvents.size(),
				"Exactly one CatalogSchemaUpdated must fire for a WARMING_UP-then-ALIVE session sequence "
					+ "regardless of how many internal mutations ran (double-emit guard); got "
					+ schemaUpdatedEvents.size()
			);
		} finally {
			evita.deleteCatalogIfExists(catalog);
		}
	}

	/**
	 * G7: a late subscriber attaching AFTER a `CatalogSchemaUpdated` host event has
	 * already fired must NOT receive a historical replay of that event. Host events are
	 * live-tail only (documented on `HostSystemEvent`). Mirrors
	 * `shouldNotReplayHistoricalHostEventsToLateSubscriber` for the new `CatalogSchemaUpdated`
	 * variant.
	 */
	@UseDataSet(value = SYSTEM_CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("not replay historical CatalogSchemaUpdated to a late subscriber")
	void shouldNotReplayHistoricalCatalogSchemaUpdatedToLateSubscriber(
		@Nonnull Evita evita
	) throws InterruptedException {
		final long startVersion = evita.getEngineState().version();

		// drive a CatalogSchemaUpdated emit BEFORE any subscriber attaches
		final String catalog = TEST_CATALOG + "_g7LateSubscriber";
		evita.defineCatalog(catalog);
		// directly emit a synthetic CatalogSchemaUpdated event so the test does not depend on the
		// timing of an asynchronous session-close emit landing before we subscribe
		evita.getChangeObserver().processHostEvent(
			new HostSystemEvent.CatalogSchemaUpdated(catalog, 5, evita.getEngineState().version())
		);
		// give the dispatcher a chance to drain so the historical event is firmly in the past
		Thread.sleep(100L);

		// late subscriber attaches AFTER the event has fired
		final ChangeSystemCaptureRequest lateRequest = ChangeSystemCaptureRequest.builder()
			.sinceVersion(startVersion + 1)
			.content(ChangeCaptureContent.BODY)
			.criteria(
				new ChangeSystemCaptureCriteria(SystemCaptureArea.ENGINE),
				new ChangeSystemCaptureCriteria(SystemCaptureArea.HOST)
			)
			.build();

		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher =
				evita.registerSystemChangeCapture(lateRequest)
		) {
			final MockSystemChangeSubscriber subscriber = new MockSystemChangeSubscriber();
			publisher.subscribe(subscriber);

			// drain the dispatcher long enough to be confident no replay happened
			Thread.sleep(500L);

			final List<HostSystemEvent.CatalogSchemaUpdated> schemaUpdatedEvents = hostEventsFor(
				subscriber, catalog, HostSystemEvent.CatalogSchemaUpdated.class
			);
			assertEquals(
				0, schemaUpdatedEvents.size(),
				"Late subscriber must NOT receive historical CatalogSchemaUpdated (live-tail only); got "
					+ schemaUpdatedEvents.size()
			);
		} finally {
			evita.deleteCatalogIfExists(catalog);
		}
	}

	/**
	 * G8: a `CatalogSchemaUpdated` host event must arrive STRICTLY AFTER the
	 * engine mutations that drove the schema-version bump. Mirrors
	 * `shouldOrderHostEventStrictlyAfterPrecedingMutation` for the new variant. The strict
	 * ordering is the contract that lets refresh observers correlate the host event with the
	 * schema-mutating WAL records that preceded it.
	 */
	@UseDataSet(value = SYSTEM_CDC_TRANSACTIONS, destroyAfterTest = true)
	@Test
	@DisplayName("order CatalogSchemaUpdated strictly after preceding schema mutations")
	void shouldOrderCatalogSchemaUpdatedStrictlyAfterPrecedingMutations(
		@Nonnull Evita evita
	) throws Exception {
		// pre-create the target catalog so the schema-bump session below has a known starting point;
		// the create itself fires its own host events that we subscribe AFTER to keep the assertion focused.
		final String catalog = TEST_CATALOG + "_g8Ordering";
		evita.defineCatalog(catalog);

		final long startVersion = evita.getEngineState().version();
		final ChangeSystemCaptureRequest combinedRequest = ChangeSystemCaptureRequest.builder()
			.sinceVersion(startVersion + 1)
			.content(ChangeCaptureContent.BODY)
			.criteria(
				new ChangeSystemCaptureCriteria(SystemCaptureArea.ENGINE),
				new ChangeSystemCaptureCriteria(SystemCaptureArea.HOST)
			)
			.build();

		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher =
				evita.registerSystemChangeCapture(combinedRequest)
		) {
			final MockSystemChangeSubscriber subscriber = new MockSystemChangeSubscriber();
			publisher.subscribe(subscriber);

			// Drive a schema bump in WARMING_UP; the session-close path emits CatalogSchemaUpdated
			// after the engine mutations have already been processed by the observer chain, so the
			// host event is guaranteed to land STRICTLY AFTER those mutations in the subscriber's
			// stream — same per-subscription executor that drains queued mutations also dispatches
			// the host event.
			evita.updateCatalog(
				catalog, session -> {
					session.defineEntitySchema("Product")
						.withAttribute("code", String.class)
						.updateVia(session);
					return null;
				}
			);

			// Wait until the CatalogSchemaUpdated lands so the ordering scan sees the full stream
			final List<HostSystemEvent.CatalogSchemaUpdated> received = waitForHostEventsFor(
				subscriber, catalog, HostSystemEvent.CatalogSchemaUpdated.class, 1, 5_000L
			);
			assertEquals(
				1, received.size(),
				"Exactly one CatalogSchemaUpdated expected; got " + received.size()
			);

			// Locate the CatalogSchemaUpdated in the captured stream and verify at least one
			// engine mutation precedes it — the strict-ordering contract documented on
			// HostSystemEvent.
			int schemaUpdatedIndex = -1;
			for (int i = 0; i < subscriber.getItems().size(); i++) {
				if (subscriber.getItems().get(i).body() instanceof HostSystemEvent.CatalogSchemaUpdated) {
					schemaUpdatedIndex = i;
					break;
				}
			}
			assertTrue(
				schemaUpdatedIndex > 0,
				"CatalogSchemaUpdated must not be the first item — at least one engine mutation "
					+ "must precede it (strict-ordering contract); index was "
					+ schemaUpdatedIndex
			);
		} finally {
			evita.deleteCatalogIfExists(catalog);
		}
	}
}
