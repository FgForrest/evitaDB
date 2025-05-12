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
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Catalog;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.ImmediateExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.function.BiFunction;

import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * The test uses {@link MockSubscriber} to collect and verify the published changes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 * @see CatalogChangeObserver
 * @see MockSubscriber
 * @see ChangeCapturePublisher
 */
@DisplayName("Evita CDC test")
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
	@DataSet(value = CDC_TRANSACTIONS, destroyAfterClass = true, expectedCatalogState = CatalogState.WARMING_UP)
	protected List<EntityReference> setUp(Evita evita) {
		// switch to the transactional mode to be able to set-up CDC
		evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
		// create some entities
		return evita.updateCatalog(TEST_CATALOG, session -> {
			return this.dataGenerator.generateEntities(
					this.dataGenerator.getSampleBrandSchema(session),
					this.noEntityPicker,
					SEED
				)
				.limit(20)
				.map(session::upsertEntity)
				.toList();
		});
	}

	/**
	 * Tests that the {@link CatalogChangeObserver} correctly registers an observer and publishes
	 * mutations to subscribers.
	 *
	 * This test:
	 * 1. Creates a new {@link CatalogChangeObserver} with an immediate executor service
	 *    (which executes tasks in the calling thread)
	 * 2. Notifies the observer about the catalog being present in the live view
	 * 3. Creates a request to capture all mutations since version 0 (the beginning)
	 * 4. Registers an observer with this request
	 * 5. Creates a {@link MockSubscriber} that expects to receive 1000 items
	 * 6. Subscribes the mock subscriber to the publisher
	 * 7. Verifies that the subscriber received exactly 1000 items
	 *
	 * The test uses try-with-resources to ensure the publisher is properly closed after the test.
	 *
	 * @param evita the Evita database instance with the test dataset already loaded
	 */
	@Test
	void shouldRegisterObserverAndStartReceivingMutations(@UseDataSet(CDC_TRANSACTIONS) Evita evita) {
		// Create a new CatalogChangeObserver with an immediate executor service
		// (ImmediateExecutorService executes tasks in the calling thread)
		final CatalogChangeObserver tested = new CatalogChangeObserver(new ImmediateExecutorService());

		// Notify the observer about the catalog being present in the live view
		// This is required for the observer to start reading mutations
		tested.notifyCatalogPresentInLiveView((Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow());

		// Create a request to capture all mutations since version 0 (the beginning)
		// This ensures we get all historical mutations, not just new ones
		final ChangeCatalogCaptureRequest catchAllRequest = ChangeCatalogCaptureRequest.builder()
			.sinceVersion(0L)
			.build();

		// Use try-with-resources to ensure the publisher is properly closed after the test
		try (
			final ChangeCapturePublisher<ChangeCatalogCapture> publisher = tested.registerObserver(catchAllRequest)
		) {
			// Create a mock subscriber that expects to receive 1000 items
			final MockSubscriber subscriber = new MockSubscriber(1000);

			// Subscribe to the publisher to start receiving mutations
			publisher.subscribe(subscriber);

			// Verify that the subscriber received exactly 1000 items
			assertEquals(1000, subscriber.getItems().size());
		}
	}
}
