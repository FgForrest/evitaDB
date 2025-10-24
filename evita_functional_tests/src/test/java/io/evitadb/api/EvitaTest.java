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

package io.evitadb.api;

import io.evitadb.api.CatalogStatistics.EntityCollectionStatistics;
import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.exception.*;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.mock.MockCatalogChangeCaptureSubscriber;
import io.evitadb.api.mock.MockEngineChangeCaptureSubscriber;
import io.evitadb.api.proxy.mock.ProductInterface;
import io.evitadb.api.proxy.mock.ProductParameterInterface;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.*;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.DuplicateCatalogMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogStateMutation;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.core.Evita;
import io.evitadb.core.EvitaManagement;
import io.evitadb.core.exception.AttributeNotFilterableException;
import io.evitadb.core.exception.AttributeNotSortableException;
import io.evitadb.core.exception.CatalogCorruptedException;
import io.evitadb.core.exception.ReferenceNotFacetedException;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.core.task.SessionKiller;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.CertificateOptions;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.grpc.configuration.GrpcOptions;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.externalApi.rest.configuration.RestOptions;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.PortManager;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.UUIDUtil;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.store.spi.CatalogPersistenceService.ENTITY_COLLECTION_FILE_SUFFIX;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test contains various integration tests for {@link Evita}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
@DisplayName("Evita Read/Write Integration Tests")
class EvitaTest implements EvitaTestSupport {
	public static final String ATTRIBUTE_NAME = "name";
	public static final String ATTRIBUTE_URL = "url";
	public static final String DIR_EVITA_TEST = "evitaTest";
	public static final String DIR_EVITA_TEST_EXPORT = "evitaTest_export";
	public static final String REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY = "productsInCategory";
	public static final String REFERENCE_PRODUCT_CATEGORY = "productCategory";
	private static final Locale LOCALE_CZ = new Locale("cs", "CZ");
	private static final Currency CURRENCY_CZK = Currency.getInstance("CZK");
	private static final Currency CURRENCY_EUR = Currency.getInstance("EUR");
	private static final String PRICE_LIST_BASIC = "basic";
	private static final String PRICE_LIST_VIP = "vip";
	private final MockEngineChangeCaptureSubscriber engineSubscriber = new MockEngineChangeCaptureSubscriber(
		Integer.MAX_VALUE);
	private Evita evita;

	@BeforeEach
	void setUp() {
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_TEST_EXPORT);
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);

		this.evita.registerSystemChangeCapture(
			new ChangeSystemCaptureRequest(null, null, ChangeCaptureContent.BODY)
		).subscribe(this.engineSubscriber);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_TEST_EXPORT);
	}

	/**
	 * Tests that a basic subscriber receives events when requested.
	 *
	 * The test verifies that:
	 * - Subscriber receives events it has requested
	 * - Subscriber doesn't receive events it hasn't requested
	 */
	@Test
	@DisplayName("Basic subscriber notification")
	void shouldNotifyBasicSubscriber() {
		final ChangeCapturePublisher<ChangeSystemCapture> publisher = this.evita.registerSystemChangeCapture(
			new ChangeSystemCaptureRequest(null, null, ChangeCaptureContent.BODY)
		);

		// subscriber is registered and wants one event when it happens
		final MockEngineChangeCaptureSubscriber subscriber = new MockEngineChangeCaptureSubscriber(2);
		publisher.subscribe(subscriber);

		this.evita.defineCatalog("newCatalog1");
		this.evita.defineCatalog("newCatalog2");

		// subscriber wants more events now, should receive `newCatalog2` and future `newCatalog3`
		subscriber.request(4);

		this.evita.defineCatalog("newCatalog3");

		// subscriber should receive 4 future events
		subscriber.request(4);

		this.evita.defineCatalog("newCatalog4");
		this.evita.defineCatalog("newCatalog5");

		// subscriber requested 2 events, this is third one, so it should be ignored
		this.evita.defineCatalog("newCatalog6");

		// subscriber received one requested event
		assertEquals(1, subscriber.getCatalogCreated("newCatalog1"));
		assertEquals(1, subscriber.getCatalogCreated("newCatalog2"));
		assertEquals(1, subscriber.getCatalogCreated("newCatalog3"));
		assertEquals(1, subscriber.getCatalogCreated("newCatalog4"));
		assertEquals(1, subscriber.getCatalogCreated("newCatalog5"));
		// subscriber didn't ask for more events, so it didn't receive any new events
		assertEquals(0, subscriber.getCatalogCreated("newCatalog6"));

		this.evita.deleteCatalogIfExists("newCatalog1");
		this.evita.deleteCatalogIfExists("newCatalog2");
		this.evita.deleteCatalogIfExists("newCatalog3");
		this.evita.deleteCatalogIfExists("newCatalog4");
		this.evita.deleteCatalogIfExists("newCatalog5");
		this.evita.deleteCatalogIfExists("newCatalog6");
	}

	/**
	 * Tests that subscribers receive events based on their registration time and request timing.
	 *
	 * The test verifies that:
	 * - Subscriber registered early but requesting events late receives past events
	 * - Subscriber registered late receives only events that occur after registration
	 */
	@Test
	@DisplayName("Late subscribers notification")
	void shouldNotifyLateSubscribers() {
		final ChangeCapturePublisher<ChangeSystemCapture> publisher = this.evita.registerSystemChangeCapture(
			new ChangeSystemCaptureRequest(null, null, ChangeCaptureContent.BODY)
		);

		// first subscriber is registered at the start, but it's not ready to receive events yet
		final MockEngineChangeCaptureSubscriber subscriberWithDelayedRequest = new MockEngineChangeCaptureSubscriber(0);
		publisher.subscribe(subscriberWithDelayedRequest);

		// should be ignored by both subscribers
		this.evita.defineCatalog("newCatalog1");

		// second subscriber is registered later but ready to receive events
		final MockEngineChangeCaptureSubscriber subscriberWithDelayedRegistration = new MockEngineChangeCaptureSubscriber(
			Integer.MAX_VALUE);
		publisher.subscribe(subscriberWithDelayedRegistration);

		// first subscriber is ready to receive events now, should get one
		subscriberWithDelayedRequest.request(Integer.MAX_VALUE);

		this.evita.defineCatalog("newCatalog2");

		// both should receive one late event
		assertEquals(1, subscriberWithDelayedRequest.getCatalogCreated("newCatalog1"));
		assertEquals(1, subscriberWithDelayedRequest.getCatalogCreated("newCatalog2"));
		assertEquals(0, subscriberWithDelayedRegistration.getCatalogCreated("newCatalog1"));
		assertEquals(1, subscriberWithDelayedRegistration.getCatalogCreated("newCatalog2"));

		this.evita.deleteCatalogIfExists("newCatalog1");
		this.evita.deleteCatalogIfExists("newCatalog2");

		// cancel both subscribers
		subscriberWithDelayedRequest.cancel();
		subscriberWithDelayedRegistration.cancel();
	}

	/**
	 * Tests that subscribers with a fixed initial version receive events correctly.
	 *
	 * The test verifies that:
	 * - Subscribers with fixed initial version receive all events from that version
	 * - Both early and late subscribers receive the same events when using fixed initial version
	 */
	@Test
	@DisplayName("Late subscribers with fixed initial version")
	void shouldNotifyLateSubscribersWithFixedInitialVersion() {
		final ChangeCapturePublisher<ChangeSystemCapture> publisher = this.evita.registerSystemChangeCapture(
			new ChangeSystemCaptureRequest(this.evita.getEngineState().version(), null, ChangeCaptureContent.BODY)
		);

		// first subscriber is registered at the start, but it's not ready to receive events yet
		final MockEngineChangeCaptureSubscriber subscriberWithDelayedRequest = new MockEngineChangeCaptureSubscriber(0);
		publisher.subscribe(subscriberWithDelayedRequest);

		// should be ignored by both subscribers
		this.evita.defineCatalog("newCatalog1");

		// second subscriber is registered later but ready to receive events
		final MockEngineChangeCaptureSubscriber subscriberWithDelayedRegistration = new MockEngineChangeCaptureSubscriber(
			Integer.MAX_VALUE);
		publisher.subscribe(subscriberWithDelayedRegistration);

		// first subscriber is ready to receive events now, should get one
		subscriberWithDelayedRequest.request(Integer.MAX_VALUE);

		this.evita.defineCatalog("newCatalog2");

		// both should receive one late event
		assertEquals(1, subscriberWithDelayedRequest.getCatalogCreated("newCatalog1"));
		assertEquals(1, subscriberWithDelayedRequest.getCatalogCreated("newCatalog2"));
		assertEquals(1, subscriberWithDelayedRegistration.getCatalogCreated("newCatalog1"));
		assertEquals(1, subscriberWithDelayedRegistration.getCatalogCreated("newCatalog2"));

		this.evita.deleteCatalogIfExists("newCatalog1");
		this.evita.deleteCatalogIfExists("newCatalog2");

		// cancel both subscribers
		subscriberWithDelayedRequest.cancel();
		subscriberWithDelayedRegistration.cancel();
	}

	/**
	 * Tests that multiple publishers can coexist and notify their subscribers independently.
	 *
	 * The test verifies that:
	 * - Multiple publishers can be registered with different configurations
	 * - Each publisher correctly notifies its own subscribers
	 * - Publishers with different content types (HEADER vs BODY) work correctly
	 */
	@Test
	@DisplayName("Multiple publishers notification")
	void shouldNotifyMultiplePublishers() {
		final ChangeCapturePublisher<ChangeSystemCapture> publisher1 = this.evita.registerSystemChangeCapture(
			new ChangeSystemCaptureRequest(null, null, ChangeCaptureContent.HEADER)
		);
		final MockEngineChangeCaptureSubscriber subscriber1 = new MockEngineChangeCaptureSubscriber(Integer.MAX_VALUE);
		publisher1.subscribe(subscriber1);

		final ChangeCapturePublisher<ChangeSystemCapture> publisher2 = this.evita.registerSystemChangeCapture(
			new ChangeSystemCaptureRequest(null, null, ChangeCaptureContent.BODY)
		);
		final MockEngineChangeCaptureSubscriber subscriber2 = new MockEngineChangeCaptureSubscriber(Integer.MAX_VALUE);
		publisher2.subscribe(subscriber2);

		this.evita.defineCatalog("newCatalog1");

		assertEquals(
			0,
			subscriber1.getCatalogCreated("newCatalog1")
		); // subscriber1 is subscribed to HEADER content, so it cannot recognize catalog name
		assertEquals(
			2,
			subscriber1.getReceived()
		); // at least 2 events should be received (transaction and create catalog mutation)
		assertEquals(1, subscriber2.getCatalogCreated("newCatalog1"));

		this.evita.deleteCatalogIfExists("newCatalog1");
	}

	/**
	 * Tests that parallel sessions are prevented in warm-up state.
	 *
	 * The test verifies that:
	 * - An exception is thrown when attempting to open parallel sessions in warm-up state
	 * - The exception is of type ConcurrentInitializationException
	 */
	@Test
	@DisplayName("Prevent parallel sessions in warm-up state")
	void shouldPreventOpeningParallelSessionsInWarmUpState() {
		assertThrows(
			ConcurrentInitializationException.class,
			() -> {
				try (final EvitaSessionContract theSession = this.evita.createReadOnlySession(TEST_CATALOG)) {
					this.evita.updateCatalog(
						TEST_CATALOG,
						session -> {
							session.defineEntitySchema(Entities.CATEGORY);
						}
					);
				}
			}
		);
	}

	/**
	 * Tests that transactions are automatically created when needed.
	 *
	 * The test verifies that:
	 * - When making changes without an explicit transaction, one is automatically created
	 * - The changes are correctly persisted
	 */
	@Test
	@DisplayName("Automatic transaction creation")
	void shouldAutomaticallyCreateTransactionIfNoneExists() {
		try (final EvitaSessionContract writeSession = this.evita.createReadWriteSession(TEST_CATALOG)) {
			writeSession.goLiveAndClose();
		}

		try (final EvitaSessionContract writeSession = this.evita.createReadWriteSession(TEST_CATALOG)) {
			writeSession.defineEntitySchema(Entities.CATEGORY).updateVia(writeSession);
		}

		try (final EvitaSessionContract readSession = this.evita.createReadOnlySession(TEST_CATALOG)) {
			assertNotNull(readSession.getEntitySchema(Entities.CATEGORY));
		}
	}

	/**
	 * Tests that filtering by non-indexed reference fails gracefully.
	 *
	 * The test verifies that:
	 * - Attempting to filter by a non-indexed reference throws ReferenceNotIndexedException
	 * - The exception contains appropriate information about the reference
	 */
	@Test
	@DisplayName("Graceful failure when filtering by non-indexed reference")
	void shouldFailGracefullyWhenTryingToFilterByNonIndexedReference() {
		try (final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG)) {
			session.defineEntitySchema(Entities.PRODUCT)
			       .withoutGeneratedPrimaryKey()
			       .withReferenceTo(Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE)
			       .updateVia(session);

			session.createNewEntity(
				       Entities.PRODUCT,
				       1
			       )
			       .setReference(Entities.BRAND, 1)
			       .upsertVia(session);

			assertThrows(
				ReferenceNotIndexedException.class,
				() -> session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							referenceHaving(Entities.BRAND, entityPrimaryKeyInSet(1, 2))
						)
					),
					EntityClassifier.class
				)
			);

		}
	}

	/**
	 * Tests that summarizing by non-faceted reference fails gracefully.
	 *
	 * The test verifies that:
	 * - Attempting to get facet summary for a non-faceted reference throws ReferenceNotFacetedException
	 * - The exception contains appropriate information about the reference
	 */
	@Test
	@DisplayName("Graceful failure when summarizing by non-faceted reference")
	void shouldFailGracefullyWhenTryingToSummarizeByNonFacetedReference() {
		try (final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG)) {
			session.defineEntitySchema(Entities.PRODUCT)
			       .withoutGeneratedPrimaryKey()
			       .withReferenceTo(Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE)
			       .updateVia(session);

			session.createNewEntity(
				       Entities.PRODUCT,
				       1
			       )
			       .setReference(Entities.BRAND, 1)
			       .upsertVia(session);

			assertThrows(
				ReferenceNotFacetedException.class,
				() -> session.query(
					query(
						collection(Entities.PRODUCT),
						require(
							facetSummaryOfReference(
								Entities.BRAND,
								FacetStatisticsDepth.COUNTS,
								entityFetch(entityFetchAllContent())
							)
						)
					),
					EntityClassifier.class
				)
			);

		}
	}

	/**
	 * Tests that adding indexing-required attributes to non-indexed references fails gracefully.
	 *
	 * The test verifies that:
	 * - Attempting to add a filterable attribute to a non-indexed reference throws InvalidSchemaMutationException
	 * - Attempting to add a unique attribute to a non-indexed reference throws InvalidSchemaMutationException
	 * - Attempting to add a sortable attribute to a non-indexed reference throws InvalidSchemaMutationException
	 */
	@Test
	@DisplayName("Graceful failure when adding indexing-required attributes to non-indexed reference")
	void shouldFailGracefullyWhenTryingToAddIndexingRequiredReferenceAttributeOnNonIndexedReference() {
		try (final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG)) {
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> session.defineEntitySchema(Entities.PRODUCT)
			             .withoutGeneratedPrimaryKey()
			             .withReferenceTo(
					       Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
					       whichIs -> whichIs.withAttribute(
						       ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::filterable)
				       )
			             .updateVia(session)
			);

			assertThrows(
				InvalidSchemaMutationException.class,
				() -> session.defineEntitySchema(Entities.PRODUCT)
			             .withoutGeneratedPrimaryKey()
			             .withReferenceTo(
					       Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
					       whichIs -> whichIs.withAttribute(
						       ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::unique)
				       )
			             .updateVia(session)
			);

			assertThrows(
				InvalidSchemaMutationException.class,
				() -> session.defineEntitySchema(Entities.PRODUCT)
			             .withoutGeneratedPrimaryKey()
			             .withReferenceTo(
					       Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
					       whichIs -> whichIs.withAttribute(
						       ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::sortable)
				       )
			             .updateVia(session)
			);
		}
	}

	/**
	 * Tests that filtering by non-filterable attribute fails gracefully.
	 *
	 * The test verifies that:
	 * - Attempting to filter by a non-filterable attribute throws AttributeNotFilterableException
	 * - The exception contains appropriate information about the attribute
	 */
	@Test
	@DisplayName("Graceful failure when filtering by non-filterable attribute")
	void shouldFailGracefullyWhenTryingToFilterByNonFilterableAttribute() {
		try (final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG)) {
			session.defineEntitySchema(Entities.PRODUCT)
			       .withoutGeneratedPrimaryKey()
			       .withAttribute(ATTRIBUTE_NAME, String.class)
			       .updateVia(session);

			session.createNewEntity(
				       Entities.PRODUCT,
				       1
			       )
			       .setAttribute(ATTRIBUTE_NAME, "It's me")
			       .upsertVia(session);

			assertThrows(
				AttributeNotFilterableException.class,
				() -> session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							attributeEquals(ATTRIBUTE_NAME, "ABC")
						)
					),
					EntityClassifier.class
				)
			);

		}
	}

	/**
	 * Tests that filtering by non-filterable reference attribute fails gracefully.
	 *
	 * The test verifies that:
	 * - Attempting to filter by a non-filterable reference attribute throws AttributeNotFilterableException
	 * - The exception contains appropriate information about the reference attribute
	 */
	@Test
	@DisplayName("Graceful failure when filtering by non-filterable reference attribute")
	void shouldFailGracefullyWhenTryingToFilterByNonFilterableReferenceAttribute() {
		try (final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG)) {
			session.defineEntitySchema(Entities.PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withReferenceTo(
					Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
					whichIs -> whichIs.indexedForFilteringAndPartitioning().withAttribute(ATTRIBUTE_NAME, String.class)
				)
				.updateVia(session);

			session.createNewEntity(
				       Entities.PRODUCT,
				       1
			       )
			       .setReference(Entities.BRAND, 1, thatIs -> thatIs.setAttribute(ATTRIBUTE_NAME, "It's me"))
			       .upsertVia(session);

			assertThrows(
				AttributeNotFilterableException.class,
				() -> session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							referenceHaving(
								Entities.BRAND,
								attributeEquals(ATTRIBUTE_NAME, "ABC")
							)
						)
					),
					EntityClassifier.class
				)
			);

		}
	}

	/**
	 * Tests that ordering by non-sortable attribute fails gracefully.
	 *
	 * The test verifies that:
	 * - Attempting to order by a non-sortable attribute throws AttributeNotSortableException
	 * - The exception contains appropriate information about the attribute
	 */
	@Test
	@DisplayName("Graceful failure when ordering by non-sortable attribute")
	void shouldFailGracefullyWhenTryingToOrderByNonSortableAttribute() {
		try (final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG)) {
			session.defineEntitySchema(Entities.PRODUCT)
			       .withoutGeneratedPrimaryKey()
			       .withAttribute(ATTRIBUTE_NAME, String.class)
			       .updateVia(session);

			session.createNewEntity(
				       Entities.PRODUCT,
				       1
			       )
			       .setAttribute(ATTRIBUTE_NAME, "It's me")
			       .upsertVia(session);

			assertThrows(
				AttributeNotSortableException.class,
				() -> session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							attributeNatural(ATTRIBUTE_NAME, OrderDirection.ASC)
						)
					),
					EntityClassifier.class
				)
			);

		}
	}

	/**
	 * Tests that ordering by non-sortable reference attribute fails gracefully.
	 *
	 * The test verifies that:
	 * - Attempting to order by a non-sortable reference attribute throws AttributeNotSortableException
	 * - The exception contains appropriate information about the reference attribute
	 */
	@Test
	@DisplayName("Graceful failure when ordering by non-sortable reference attribute")
	void shouldFailGracefullyWhenTryingToOrderByNonSortableReferenceAttribute() {
		try (final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG)) {
			session.defineEntitySchema(Entities.PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withReferenceTo(
					Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
					whichIs -> whichIs.indexedForFilteringAndPartitioning().withAttribute(ATTRIBUTE_NAME, String.class)
				)
				.updateVia(session);

			session.createNewEntity(
				       Entities.PRODUCT,
				       1
			       )
			       .setReference(Entities.BRAND, 1, thatIs -> thatIs.setAttribute(ATTRIBUTE_NAME, "It's me"))
			       .upsertVia(session);

			assertThrows(
				AttributeNotSortableException.class,
				() -> session.query(
					query(
						collection(Entities.PRODUCT),
						orderBy(
							referenceProperty(
								Entities.BRAND,
								attributeNatural(ATTRIBUTE_NAME, OrderDirection.ASC)
							)
						)
					),
					EntityClassifier.class
				)
			);

		}
	}

	/**
	 * Tests that querying an empty collection returns an empty result.
	 *
	 * The test verifies that:
	 * - Querying an empty collection returns a response with zero records
	 * - The returned record data is empty
	 */
	@Test
	@DisplayName("Handle querying empty collection")
	void shouldHandleQueryingEmptyCollection() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.BRAND);

				final EvitaResponse<SealedEntity> entities = session.query(
					query(
						collection(Entities.BRAND)
					),
					SealedEntity.class
				);

				// result is expected to be empty
				assertEquals(0, entities.getTotalRecordCount());
				assertTrue(entities.getRecordData().isEmpty());
			}
		);
	}

	/**
	 * Tests the behavior of queryOneEntityReference method.
	 *
	 * The test verifies that:
	 * - When no entity matches the query, null is returned
	 * - When exactly one entity matches the query, it is returned
	 * - When multiple entities match the query, an UnexpectedResultCountException is thrown
	 */
	@Test
	@DisplayName("Return zero or exactly one entity reference")
	void shouldReturnZeroOrExactlyOne() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.BRAND);

				session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 2));

				assertNull(
					session.queryOneEntityReference(
						query(collection(Entities.BRAND), filterBy(entityPrimaryKeyInSet(10)))
					).orElse(null)
				);

				assertNotNull(
					session.queryOneEntityReference(
						query(collection(Entities.BRAND), filterBy(entityPrimaryKeyInSet(1)))
					)
				);

				assertThrows(
					UnexpectedResultCountException.class,
					() -> session.queryOneEntityReference(
						query(collection(Entities.BRAND), filterBy(entityPrimaryKeyInSet(1, 2)))
					)
				);
			}
		);
	}

	/**
	 * Tests querying multiple entities with pagination.
	 *
	 * The test verifies that:
	 * - Querying non-existent entities returns an empty result
	 * - Querying with pagination returns the correct page of results
	 * - The total record count is correctly reported
	 * - Different entity types can be returned in the results
	 * - An exception is thrown when trying to get a single entity when multiple match
	 */
	@Test
	@DisplayName("Return multiple results with pagination")
	void shouldReturnMultipleResults() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.BRAND);

				final Integer[] pks = new Integer[50];
				for (int i = 0; i < 50; i++) {
					session.upsertEntity(session.createNewEntity(Entities.BRAND, i + 1));
					pks[i] = i + 1;
				}

				final EvitaResponse<EntityReference> emptyResult = session.query(
					query(collection(Entities.BRAND), filterBy(entityPrimaryKeyInSet(100))),
					EntityReference.class
				);

				assertEquals(0, emptyResult.getTotalRecordCount());
				assertTrue(emptyResult.getRecordData().isEmpty());

				final EvitaResponse<EntityReference> firstPageResult = session.query(
					query(collection(Entities.BRAND), filterBy(entityPrimaryKeyInSet(pks)), require(page(1, 5))),
					EntityReference.class
				);

				assertEquals(50, firstPageResult.getTotalRecordCount());
				assertArrayEquals(
					new int[]{1, 2, 3, 4, 5}, firstPageResult.getRecordData()
					                                         .stream()
					                                         .mapToInt(EntityReference::getPrimaryKey)
					                                         .toArray()
				);

				final EvitaResponse<SealedEntity> thirdPageResult = session.query(
					query(
						collection(Entities.BRAND), filterBy(entityPrimaryKeyInSet(pks)),
						require(page(3, 5), entityFetch())
					),
					SealedEntity.class
				);

				assertEquals(50, thirdPageResult.getTotalRecordCount());
				assertArrayEquals(
					new int[]{11, 12, 13, 14, 15}, thirdPageResult.getRecordData()
					                                              .stream()
					                                              .mapToInt(SealedEntity::getPrimaryKey)
					                                              .toArray()
				);

				assertThrows(
					UnexpectedResultException.class,
					() -> session.query(
						query(collection(Entities.BRAND), filterBy(entityPrimaryKeyInSet(1, 2))),
						SealedEntity.class
					)
				);
			}
		);
	}

	/**
	 * Tests that a catalog can be created and then loaded after restarting Evita.
	 *
	 * The test verifies that:
	 * - A catalog created in one Evita instance is still available after restarting
	 * - The catalog name is correctly preserved in the catalog list
	 */
	@Test
	@DisplayName("Create and load catalog after restart")
	void shouldCreateAndLoadCatalog() {
		this.evita.close();
		this.evita = new Evita(
			getEvitaConfiguration()
		);

		assertTrue(this.evita.getCatalogNames().contains(TEST_CATALOG));
	}

	/**
	 * Tests that inactive sessions are automatically killed after a timeout.
	 *
	 * The test verifies that:
	 * - Inactive sessions are detected and killed by the session killer
	 * - Active sessions remain active after the session killer runs
	 * - The count of active sessions is correctly updated
	 */
	@Test
	@DisplayName("Automatic killing of inactive sessions")
	void shouldKillInactiveSessionsAutomatically() throws NoSuchFieldException, IllegalAccessException {
		this.evita.updateCatalog(
			TEST_CATALOG,
			it -> {
				it.goLiveAndClose();
			}
		);
		this.evita.close();

		this.evita = new Evita(
			getEvitaConfiguration(1)
		);

		final EvitaSessionContract sessionInactive = this.evita.createReadOnlySession(TEST_CATALOG);
		final EvitaSessionContract sessionActive = this.evita.createReadOnlySession(TEST_CATALOG);

		assertEquals(2L, this.evita.getActiveSessions().count());

		final long start = System.currentTimeMillis();
		do {
			assertNotNull(sessionActive.getCatalogSchema());
		} while (!(System.currentTimeMillis() - start > 2000));

		final Field sessionKillerField = Evita.class.getDeclaredField("sessionKiller");
		sessionKillerField.setAccessible(true);
		final SessionKiller sessionKiller = (SessionKiller) sessionKillerField.get(this.evita);
		sessionKiller.run();

		assertFalse(sessionInactive.isActive());
		assertTrue(sessionActive.isActive());
		assertEquals(1L, this.evita.getActiveSessions().count());
	}

	/**
	 * Tests that a catalog can be created and then dropped.
	 *
	 * The test verifies that:
	 * - A catalog can be created with entities
	 * - The catalog can be deleted
	 * - The engine subscriber is notified of the catalog deletion
	 * - The catalog is no longer in the list of catalogs
	 */
	@Test
	@DisplayName("Create and drop catalog")
	void shouldCreateAndDropCatalog() {
		this.engineSubscriber.reset();

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.BRAND);

				session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 2));
				assertEquals(2, session.getEntityCollectionSize(Entities.BRAND));
			}
		);

		this.evita.deleteCatalogIfExists(TEST_CATALOG);

		assertEquals(1, this.engineSubscriber.getCatalogDeleted(TEST_CATALOG));

		assertFalse(this.evita.getCatalogNames().contains(TEST_CATALOG));
	}

	/**
	 * Tests that creating a catalog with a name that would be a duplicate in any naming convention fails.
	 *
	 * The test verifies that:
	 * - Attempting to create a catalog with a name that would be the same as an existing catalog in any naming convention throws CatalogAlreadyPresentException
	 * - The exception message correctly identifies the conflicting catalog names and the naming convention
	 */
	@Test
	@DisplayName("Fail to create catalog with duplicate name in any naming convention")
	void shouldFailToCreateCatalogWithDuplicateNameInOneOfNamingConventions() {
		try {
			this.evita.defineCatalog("test-catalog");
			fail("Duplicated catalog name should be refused!");
		} catch (CatalogAlreadyPresentException ex) {
			assertEquals(
				"Catalog `test-catalog` and existing catalog `testCatalog` produce the same name `testCatalog` " +
					"in `CAMEL_CASE` convention! Please choose different catalog name.",
				ex.getMessage()
			);
		}
	}

	/**
	 * Tests that entity collections can be created and deleted within a session.
	 *
	 * The test verifies that:
	 * - Multiple entity collections can be created in a single session
	 * - Entities can be added to these collections
	 * - A collection can be deleted within the session
	 * - After deletion, the collection is no longer accessible
	 * - Other collections remain accessible
	 */
	@Test
	@DisplayName("Create and delete entity collection within session")
	void shouldCreateAndDeleteEntityCollectionFromWithinTheSession() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.BRAND);

				session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 2));
				assertEquals(2, session.getEntityCollectionSize(Entities.BRAND));

				session.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 10));
				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 11));
				assertEquals(2, session.getEntityCollectionSize(Entities.PRODUCT));

				session.deleteCollection(Entities.BRAND);
			}
		);

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.BRAND));
				assertEquals(2, session.getEntityCollectionSize(Entities.PRODUCT));
				return null;
			}
		);
	}

	/**
	 * Validates the ability to create a catalog, add and read data from it,
	 * and subsequently verify data integrity after restarting the Evita instance.
	 *
	 * This test ensures that:
	 * 1. A catalog can be defined and updated with a specified entity schema.
	 * 2. Entities can be added to the catalog and the entity count is as expected.
	 * 3. Upon restarting the Evita instance, the catalog data is preserved and can be queried.
	 */
	@DisplayName("Create catalog and read data after Evita restart")
	@Test
	void shouldCreateCatalogAndReadDataAfterEvitaRestart() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.BRAND);

				session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 2));
				assertEquals(2, session.getEntityCollectionSize(Entities.BRAND));
			}
		);

		this.evita.close();

		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.waitUntilFullyInitialized();

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertEquals(TEST_CATALOG, session.getCatalogSchema().getName());
				assertEquals(2, session.getEntityCollectionSize(Entities.BRAND));
				return null;
			}
		);
	}

	/**
	 * Tests that a catalog can be renamed while in warm-up mode.
	 *
	 * The test verifies that:
	 * - A catalog in warm-up mode can be renamed
	 * - The renamed catalog retains all its data
	 * - The original catalog name is no longer available
	 * - The renamed catalog persists after Evita restart
	 */
	@Test
	@DisplayName("Rename catalog in warm-up mode")
	void shouldRenameExistingCatalogInWarmUpMode() {
		doRenameCatalog(CatalogState.WARMING_UP);
	}

	/**
	 * Tests that a catalog can be renamed while in transactional (alive) mode.
	 *
	 * The test verifies that:
	 * - A catalog in transactional mode can be renamed
	 * - The renamed catalog retains all its data
	 * - The original catalog name is no longer available
	 * - The renamed catalog persists after Evita restart
	 */
	@Test
	@DisplayName("Rename catalog in transactional mode")
	void shouldRenameExistingCatalogInTransactionalMode() {
		doRenameCatalog(CatalogState.ALIVE);
	}

	/**
	 * Tests that a catalog can be replaced while in warm-up mode.
	 *
	 * The test verifies that:
	 * - An attempt to replace non-existing catalog with existing one passes
	 * - The replacement catalog's data becomes available under the original catalog name
	 * - The replaced catalog persists after Evita restart
	 */
	@Test
	@DisplayName("Replace catalog in warm-up mode")
	void shouldReplaceNonExistingCatalogInWarmUpMode() {
		doReplaceNonExistingCatalog(CatalogState.WARMING_UP);
	}

	/**
	 * Tests that a catalog can be replaced while in alive mode.
	 *
	 * The test verifies that:
	 * - An attempt to replace non-existing catalog with existing one passes
	 * - The replacement catalog's data becomes available under the original catalog name
	 * - The replaced catalog persists after Evita restart
	 */
	@Test
	@DisplayName("Replace catalog in warm-up mode")
	void shouldReplaceNonExistingCatalogInTransactionalMode() {
		doReplaceNonExistingCatalog(CatalogState.ALIVE);
	}

	/**
	 * Tests that a catalog can be replaced while in warm-up mode.
	 *
	 * The test verifies that:
	 * - A catalog in warm-up mode can be replaced with another catalog
	 * - The replacement catalog's data becomes available under the original catalog name
	 * - The temporary catalog name is no longer available
	 * - The replaced catalog persists after Evita restart
	 */
	@Test
	@DisplayName("Replace catalog in warm-up mode")
	void shouldReplaceExistingCatalogInWarmUpMode() {
		doReplaceCatalog(CatalogState.WARMING_UP);
	}

	/**
	 * Tests that a catalog can be replaced while in transactional (alive) mode.
	 *
	 * The test verifies that:
	 * - A catalog in transactional mode can be replaced with another catalog
	 * - The replacement catalog's data becomes available under the original catalog name
	 * - The temporary catalog name is no longer available
	 * - The replaced catalog persists after Evita restart
	 */
	@Test
	@DisplayName("Replace catalog in transactional mode")
	void shouldReplaceExistingCatalogInTransactionalMode() {
		doReplaceCatalog(CatalogState.ALIVE);
	}

	/**
	 * Tests that a catalog can be activated (loaded into memory) from inactive state.
	 *
	 * The test verifies that:
	 * - A catalog can be created and made alive
	 * - The catalog can be deactivated (unloaded from memory)
	 * - The catalog state changes to INACTIVE after deactivation
	 * - The catalog can be activated again (loaded back into memory)
	 * - The catalog state changes to ALIVE after activation
	 * - Data remains accessible after activation
	 */
	@Test
	@DisplayName("Activate catalog from inactive state")
	void shouldActivateCatalogFromInactiveState() {
		final String testCatalogName = TEST_CATALOG + "_activation_test";

		// Create and setup a catalog with some data
		this.evita.defineCatalog(testCatalogName)
			.updateViaNewSession(this.evita);

		this.evita.updateCatalog(
			testCatalogName,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT);
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Test Product")
				);
				session.goLiveAndClose();
			}
		);

		// Verify catalog is alive
		assertEquals(CatalogState.ALIVE, this.evita.getCatalogState(testCatalogName).orElseThrow());

		// Deactivate the catalog
		this.evita.deactivateCatalog(testCatalogName);

		// Verify catalog is inactive
		assertEquals(CatalogState.INACTIVE, this.evita.getCatalogState(testCatalogName).orElseThrow());

		// Reinitialize the Evita instance to simulate a restart
		this.evita.close();
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.waitUntilFullyInitialized();

		// Verify catalog is inactive
		assertEquals(CatalogState.INACTIVE, this.evita.getCatalogState(testCatalogName).orElseThrow());

		// Activate the catalog again
		this.evita.activateCatalog(testCatalogName);

		// Verify catalog is alive again
		assertEquals(CatalogState.ALIVE, this.evita.getCatalogState(testCatalogName).orElseThrow());

		// Verify data is still accessible
		this.evita.queryCatalog(
			testCatalogName,
			session -> {
				final Optional<SealedEntity> product = session.getEntity(
					Entities.PRODUCT, 1, entityFetchAllContent()
				);
				assertTrue(product.isPresent());
				assertEquals("Test Product", product.get().getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));
				return null;
			}
		);

		// Reinitialize the Evita instance to simulate a restart
		this.evita.close();
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.waitUntilFullyInitialized();

		// Verify catalog is alive again
		assertEquals(CatalogState.ALIVE, this.evita.getCatalogState(testCatalogName).orElseThrow());

		// Clean up
		this.evita.deleteCatalogIfExists(testCatalogName);
	}

	/**
	 * Tests that a catalog can be made mutable (read-write) from immutable (read-only) state.
	 *
	 * The test verifies that:
	 * - A catalog can be created and made alive
	 * - The catalog can be made immutable (read-only)
	 * - Write operations throw ReadOnlyException when catalog is immutable
	 * - The catalog can be made mutable again (read-write)
	 * - Write operations work normally when catalog is mutable
	 */
	@Test
	@DisplayName("Make catalog mutable from immutable state")
	void shouldMakeCatalogMutableFromImmutableState() {
		final String testCatalogName = TEST_CATALOG + "_mutability_test";

		// Create and setup a catalog with some data
		this.evita.defineCatalog(testCatalogName)
			.updateViaNewSession(this.evita);

		this.evita.updateCatalog(
			testCatalogName,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT);
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Test Product")
				);
				session.goLiveAndClose();
			}
		);

		// Verify catalog is alive and mutable (write operations work)
		assertEquals(CatalogState.ALIVE, this.evita.getCatalogState(testCatalogName).orElseThrow());

		// Make catalog immutable
		this.evita.makeCatalogImmutable(testCatalogName);

		// Verify write operations throw ReadOnlyException when catalog is immutable
		assertThrows(
			ReadOnlyException.class,
			() -> this.evita.updateCatalog(
				testCatalogName,
				session -> {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 2)
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Another Product")
					);
				}
			)
		);

		// Make catalog mutable again
		this.evita.makeCatalogMutable(testCatalogName);

		// Verify write operations work when catalog is mutable
		this.evita.updateCatalog(
			testCatalogName,
			session -> {
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 2)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Another Product")
				);
			}
		);

		// Verify both entities are accessible
		this.evita.queryCatalog(
			testCatalogName,
			session -> {
				final Optional<SealedEntity> product1 = session.getEntity(
					Entities.PRODUCT, 1, entityFetchAllContent()
				);
				final Optional<SealedEntity> product2 = session.getEntity(
					Entities.PRODUCT, 2, entityFetchAllContent()
				);
				assertTrue(product1.isPresent());
				assertTrue(product2.isPresent());
				assertEquals("Test Product", product1.get().getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));
				assertEquals("Another Product", product2.get().getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));
				return null;
			}
		);

		// Clean up
		this.evita.deleteCatalogIfExists(testCatalogName);
	}

	/**
	 * Tests that a catalog can be made immutable (read-only) from mutable (read-write) state.
	 *
	 * The test verifies that:
	 * - A catalog can be created and made alive (mutable by default)
	 * - Write operations work normally when catalog is mutable
	 * - The catalog can be made immutable (read-only)
	 * - Write operations throw ReadOnlyException when catalog is immutable
	 * - Read operations also throw ReadOnlyException when catalog is immutable (no sessions allowed)
	 */
	@Test
	@DisplayName("Make catalog immutable from mutable state")
	void shouldMakeCatalogImmutableFromMutableState() {
		final String testCatalogName = TEST_CATALOG + "_immutable_test";

		// Create and setup a catalog with some data
		this.evita.defineCatalog(testCatalogName)
			.updateViaNewSession(this.evita);

		this.evita.updateCatalog(
			testCatalogName,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT);
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Test Product")
				);
				session.goLiveAndClose();
			}
		);

		// Verify catalog is alive and mutable (write operations work)
		assertEquals(CatalogState.ALIVE, this.evita.getCatalogState(testCatalogName).orElseThrow());

		// Verify write operations work when catalog is mutable
		this.evita.updateCatalog(
			testCatalogName,
			session -> {
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 2)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Another Product")
				);
			}
		);

		// Make catalog immutable
		this.evita.makeCatalogImmutable(testCatalogName);

		// Verify write operations throw ReadOnlyException when catalog is immutable
		assertThrows(
			ReadOnlyException.class,
			() -> this.evita.updateCatalog(
				testCatalogName,
				session -> {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 3)
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Third Product")
					);
				}
			)
		);

		// Verify read operations also throw ReadOnlyException when catalog is immutable
		// (immutable catalogs don't allow any session creation)
		assertThrows(
			ReadOnlyException.class,
			() -> this.evita.updateCatalog(
				testCatalogName,
				session -> {
					session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent());
					return null;
				}
			)
		);

		// Clean up
		this.evita.deleteCatalogIfExists(testCatalogName);
	}

	/**
	 * Tests that an entity collection can be created and then dropped.
	 *
	 * The test verifies that:
	 * - A catalog can be set up with multiple entity collections
	 * - A specific collection can be deleted
	 * - After deletion, the collection is no longer accessible
	 * - Other collections remain accessible
	 */
	@Test
	@DisplayName("Create and drop entity collection")
	void shouldCreateAndDropCollection() {
		setupCatalogWithProductAndCategory();

		this.evita.updateCatalog(
			TEST_CATALOG, session -> {
				session.deleteCollection(Entities.PRODUCT);
			}
		);

		this.evita.queryCatalog(
			TEST_CATALOG, session -> {
				assertThrows(
					CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.PRODUCT));
				assertEquals(2, session.getEntityCollectionSize(Entities.CATEGORY));
				return null;
			}
		);
	}

	/**
	 * Tests that an entity collection can be created and then renamed.
	 *
	 * The test verifies that:
	 * - A catalog can be set up with multiple entity collections
	 * - A specific collection can be renamed
	 * - After renaming, the collection is accessible under the new name
	 * - The original collection name is no longer accessible
	 * - Other collections remain accessible
	 */
	@Test
	@DisplayName("Create and rename entity collection")
	void shouldCreateAndRenameCollection() {
		setupCatalogWithProductAndCategory();

		final File theCollectionFile = getEvitaTestDirectory()
			.resolve(
				TEST_CATALOG + File.separator + Entities.PRODUCT.toLowerCase() + "-1_0" + ENTITY_COLLECTION_FILE_SUFFIX)
			.toFile();
		assertTrue(theCollectionFile.exists());

		this.evita.updateCatalog(
			TEST_CATALOG, session -> {
				session.renameCollection(Entities.PRODUCT, Entities.STORE);
				assertEquals(Entities.STORE, session.getEntitySchemaOrThrowException(Entities.STORE).getName());
			}
		);

		this.evita.queryCatalog(
			TEST_CATALOG, session -> {
				assertThrows(
					CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.PRODUCT));
				assertEquals(1, session.getEntityCollectionSize(Entities.STORE));
				assertEquals(2, session.getEntityCollectionSize(Entities.CATEGORY));
				return null;
			}
		);

		// the original file was immediately removed from the file system (we're in warm-up mode)
		assertFalse(theCollectionFile.exists());
	}

	/**
	 * Tests that an entity collection can be renamed.
	 *
	 * The test verifies that:
	 * - An entity collection can be renamed
	 * - After renaming, the collection is accessible under the new name
	 * - The original collection name is no longer accessible
	 * - The renamed collection retains all its data
	 */
	@Test
	@DisplayName("Rename entity collection")
	void shouldRenameEntityCollection() {
		setupCatalogWithProductAndCategory();

		final File theCollectionFile = getEvitaTestDirectory()
			.resolve(
				TEST_CATALOG + File.separator + Entities.PRODUCT.toLowerCase() + "-1_0" + ENTITY_COLLECTION_FILE_SUFFIX)
			.toFile();
		assertTrue(theCollectionFile.exists());

		this.evita.updateCatalog(
			TEST_CATALOG, session -> {
				session.renameCollection(Entities.PRODUCT, Entities.BRAND);
			}
		);

		this.evita.queryCatalog(
			TEST_CATALOG, session -> {
				assertThrows(
					CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.PRODUCT));
				assertEquals(2, session.getEntityCollectionSize(Entities.CATEGORY));
				assertEquals(1, session.getEntityCollectionSize(Entities.BRAND));
				final Optional<SealedEntity> brand = session.getEntity(Entities.BRAND, 1, entityFetchAllContent());
				assertTrue(brand.isPresent());
				assertEquals("The product", brand.get().getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));
				return null;
			}
		);
	}

	/**
	 * Tests that renaming a collection to an existing collection name fails.
	 *
	 * The test verifies that:
	 * - Attempting to rename a collection to a name that already exists throws EntityTypeAlreadyPresentInCatalogSchemaException
	 * - The exception message correctly identifies the conflicting collection names
	 */
	@Test
	@DisplayName("Fail to rename collection to existing collection name")
	void shouldFailToRenameCollectionToExistingCollection() {
		setupCatalogWithProductAndCategory();

		final File theCollectionFile = getEvitaTestDirectory()
			.resolve(
				TEST_CATALOG + File.separator + Entities.PRODUCT.toLowerCase() + "-1_0" + ENTITY_COLLECTION_FILE_SUFFIX)
			.toFile();
		assertTrue(theCollectionFile.exists());

		this.evita.updateCatalog(
			TEST_CATALOG, session -> {
				assertThrows(
					EntityTypeAlreadyPresentInCatalogSchemaException.class,
					() -> session.renameCollection(Entities.PRODUCT, Entities.CATEGORY)
				);
			}
		);
	}

	/**
	 * Tests that an entity collection can be created and then replaced.
	 *
	 * The test verifies that:
	 * - A catalog can be set up with multiple entity collections
	 * - A specific collection can be replaced with a new one
	 * - After replacement, the collection has the new schema and data
	 * - Other collections remain accessible
	 */
	@Test
	@DisplayName("Create and replace entity collection")
	void shouldCreateAndReplaceCollection() {
		setupCatalogWithProductAndCategory();

		final File theCollectionFile = getEvitaTestDirectory()
			.resolve(
				TEST_CATALOG + File.separator + Entities.PRODUCT.toLowerCase() + "-1_0" + ENTITY_COLLECTION_FILE_SUFFIX)
			.toFile();
		assertTrue(theCollectionFile.exists());

		this.evita.updateCatalog(
			TEST_CATALOG, session -> {
				session.replaceCollection(Entities.CATEGORY, Entities.PRODUCT);
			}
		);

		this.evita.queryCatalog(
			TEST_CATALOG, session -> {
				assertThrows(
					CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.PRODUCT));
				assertEquals(1, session.getEntityCollectionSize(Entities.CATEGORY));
				return null;
			}
		);

		// the original file was immediately removed from the file system (we're in warm-up mode)
		assertFalse(theCollectionFile.exists());
	}

	/**
	 * Tests that an entity collection can be created and renamed within a transaction.
	 *
	 * The test verifies that:
	 * - A catalog can be set up with multiple entity collections in transactional mode
	 * - A specific collection can be renamed within a transaction
	 * - After renaming, the collection is accessible under the new name
	 * - The original collection name is no longer accessible
	 * - Other collections remain accessible
	 * - The changes persist after Evita restart
	 */
	@Test
	@DisplayName("Create and rename entity collection in transaction")
	void shouldCreateAndRenameCollectionInTransaction() {
		setupCatalogWithProductAndCategory();

		this.evita.updateCatalog(
			TEST_CATALOG, session -> {
				session.goLiveAndClose();
			}
		);

		final MockCatalogChangeCaptureSubscriber catalogSubscriber = new MockCatalogChangeCaptureSubscriber(
			Integer.MAX_VALUE);

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.registerChangeCatalogCapture(
					ChangeCatalogCaptureRequest
						.builder()
						.content(ChangeCaptureContent.BODY)
						.build()
				).subscribe(catalogSubscriber);
				return null;
			}
		);

		final File theCollectionFile = getEvitaTestDirectory()
			.resolve(
				TEST_CATALOG + File.separator + Entities.PRODUCT.toLowerCase() + "-1_0" + ENTITY_COLLECTION_FILE_SUFFIX)
			.toFile();
		assertTrue(theCollectionFile.exists());

		try (final EvitaSessionContract oldSession = this.evita.createReadOnlySession(TEST_CATALOG)) {
			log.info("Old session catalog version: " + oldSession.getCatalogVersion());

			this.evita.updateCatalog(
				TEST_CATALOG, session -> {
					session.renameCollection(Entities.PRODUCT, Entities.STORE);
					assertEquals(Entities.STORE, session.getEntitySchemaOrThrowException(Entities.STORE).getName());
				}
			);

			assertEquals(1, catalogSubscriber.getEntityCollectionCreated(Entities.STORE));
			assertEquals(1, catalogSubscriber.getEntityCollectionDeleted(Entities.PRODUCT));

			this.evita.queryCatalog(
				TEST_CATALOG, session -> {
					assertThrows(
						CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.PRODUCT));
					assertEquals(1, session.getEntityCollectionSize(Entities.STORE));
					assertEquals(2, session.getEntityCollectionSize(Entities.CATEGORY));
					log.info("New session catalog version: " + session.getCatalogVersion());
					return null;
				}
			);

			// the file needs to remain on disk for the old session to be able to read it
			assertTrue(theCollectionFile.exists());

			// we can still read data from old session (and old entity collection)
			assertThrows(CollectionNotFoundException.class, () -> oldSession.getEntityCollectionSize(Entities.STORE));
			assertEquals(1, oldSession.getEntityCollectionSize(Entities.PRODUCT));
			assertEquals(2, oldSession.getEntityCollectionSize(Entities.CATEGORY));
		}

		// give async process some time to finish
		final long start = System.currentTimeMillis();
		do {
			Thread.onSpinWait();
		} while (theCollectionFile.exists() && System.currentTimeMillis() - start < 2000);

		// the original file is removed when old session is terminated - there is no other reader
		assertFalse(theCollectionFile.exists());
	}

	/**
	 * Tests that an entity collection can be created and replaced within a transaction.
	 *
	 * The test verifies that:
	 * - A catalog can be set up with multiple entity collections in transactional mode
	 * - A specific collection can be replaced with a new one within a transaction
	 * - After replacement, the collection has the new schema and data
	 * - Other collections remain accessible
	 * - The changes persist after Evita restart
	 */
	@Test
	@DisplayName("Create and replace entity collection in transaction")
	void shouldCreateAndReplaceCollectionInTransaction() {
		setupCatalogWithProductAndCategory();

		this.evita.updateCatalog(
			TEST_CATALOG, session -> {
				session.goLiveAndClose();
			}
		);

		final MockCatalogChangeCaptureSubscriber catalogSubscriber = new MockCatalogChangeCaptureSubscriber(
			Integer.MAX_VALUE);

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.registerChangeCatalogCapture(
					ChangeCatalogCaptureRequest
						.builder()
						.content(ChangeCaptureContent.BODY)
						.build()
				).subscribe(catalogSubscriber);
				return null;
			}
		);

		this.evita.updateCatalog(
			TEST_CATALOG, session -> {
				session.replaceCollection(Entities.CATEGORY, Entities.PRODUCT);
			}
		);

		assertEquals(1, catalogSubscriber.getEntityCollectionSchemaUpdated(Entities.CATEGORY));
		assertEquals(1, catalogSubscriber.getEntityCollectionDeleted(Entities.PRODUCT));

		this.evita.queryCatalog(
			TEST_CATALOG, session -> {
				assertThrows(
					CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.PRODUCT));
				assertEquals(1, session.getEntityCollectionSize(Entities.CATEGORY));
				return null;
			}
		);
	}

	/**
	 * Tests that entity collections can be created and dropped within a transaction.
	 *
	 * The test verifies that:
	 * - A catalog can be set up with multiple entity collections in transactional mode
	 * - A specific collection can be deleted within a transaction
	 * - After deletion, the collection is no longer accessible
	 * - Other collections remain accessible
	 * - The changes persist after Evita restart
	 */
	@Test
	@DisplayName("Create and drop entity collections in transaction")
	void shouldCreateAndDropCollectionsInTransaction() {
		setupCatalogWithProductAndCategory();

		this.evita.updateCatalog(
			TEST_CATALOG, session -> {
				session.goLiveAndClose();
			}
		);

		final MockCatalogChangeCaptureSubscriber catalogSubscriber = new MockCatalogChangeCaptureSubscriber(
			Integer.MAX_VALUE);

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.registerChangeCatalogCapture(
					ChangeCatalogCaptureRequest
						.builder()
						.content(ChangeCaptureContent.BODY)
						.build()
				).subscribe(catalogSubscriber);
				return null;
			}
		);

		this.evita.updateCatalog(
			TEST_CATALOG, session -> {
				session.deleteCollection(Entities.PRODUCT);
			}
		);

		assertEquals(1, catalogSubscriber.getEntityCollectionDeleted(Entities.PRODUCT));

		this.evita.queryCatalog(
			TEST_CATALOG, session -> {
				assertThrows(
					CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.PRODUCT));
				assertEquals(2, session.getEntityCollectionSize(Entities.CATEGORY));
				return null;
			}
		);
	}

	/**
	 * Tests that entity schema attributes referring to global ones are updated correctly.
	 *
	 * The test verifies that:
	 * - Global attributes can be defined at catalog level
	 * - Entity schemas can reference global attributes
	 * - When global attributes are updated, the changes are reflected in entity schemas
	 * - Uniqueness type changes in global attributes propagate to entity schemas
	 */
	@Test
	@DisplayName("Entity schema attributes referring to global ones are updated correctly")
	void shouldUpdateEntitySchemaAttributeDefinitionsReferringToGlobalOnes() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
				       .openForWrite()
				       .withAttribute(ATTRIBUTE_URL, String.class, whichIs -> whichIs.localized().uniqueGlobally())
				       .updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withGlobalAttribute(ATTRIBUTE_URL)
					.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
					.updateVia(session);

				final EntityAttributeSchemaContract firstAttribute = session
					.getEntitySchemaOrThrowException(Entities.PRODUCT)
					.getAttribute(ATTRIBUTE_URL)
					.orElseThrow();
				assertInstanceOf(GlobalAttributeSchemaContract.class, firstAttribute);
				assertTrue(firstAttribute.isLocalized());
				assertEquals(
					GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG,
					((GlobalAttributeSchemaContract) firstAttribute).getGlobalUniquenessType()
				);

				session.getCatalogSchema()
				       .openForWrite()
				       .withAttribute(
					       ATTRIBUTE_URL, String.class, GlobalAttributeSchemaEditor::uniqueGloballyWithinLocale)
				       .updateVia(session);

				final SealedCatalogSchema catalogSchema = session.getCatalogSchema();
				assertTrue(catalogSchema.getAttribute(ATTRIBUTE_URL).isPresent());
				assertEquals(
					GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG_LOCALE, catalogSchema.getAttribute(
						ATTRIBUTE_URL).orElseThrow().getGlobalUniquenessType()
				);

				final EntityAttributeSchemaContract secondAttribute = session
					.getEntitySchemaOrThrowException(Entities.PRODUCT)
					.getAttribute(ATTRIBUTE_URL)
					.orElseThrow();
				assertInstanceOf(GlobalAttributeSchemaContract.class, secondAttribute);
				assertTrue(secondAttribute.isLocalized());
				assertEquals(
					GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG_LOCALE,
					((GlobalAttributeSchemaContract) secondAttribute).getGlobalUniquenessType()
				);
			}
		);
	}

	/**
	 * Tests that entity attributes referring to global attributes are updated when global attributes change.
	 *
	 * The test verifies that:
	 * - Global attributes can be defined at catalog level
	 * - Multiple entity schemas can reference the same global attribute
	 * - When global attributes are updated (description, localization, uniqueness), the changes are reflected in all entity schemas
	 * - Entity instances with global attributes maintain consistency after global attribute changes
	 */
	@Test
	@DisplayName("Entity attributes referring to global attributes are updated when global attributes change")
	void shouldUpdateEntityAttributesReferringToGlobalAttributeThatIsChanged() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
				       .openForWrite()
				       .withAttribute(ATTRIBUTE_URL, String.class, whichIs -> whichIs.localized().uniqueGlobally())
				       .updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withGlobalAttribute(ATTRIBUTE_URL)
					.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
					.updateVia(session);

				session
					.defineEntitySchema(Entities.CATEGORY)
					.withGlobalAttribute(ATTRIBUTE_URL)
					.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
					.updateVia(session);
			}
		);

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityAttributeSchemaContract productUrl = session.getEntitySchema(Entities.PRODUCT).orElseThrow()
				                                                        .getAttribute(ATTRIBUTE_URL).orElseThrow();
				assertNull(productUrl.getDescription());
				assertTrue(productUrl.isLocalized());
				assertTrue(productUrl.isUnique());
				assertTrue(productUrl instanceof GlobalAttributeSchemaContract ga && ga.isUniqueGlobally());

				final EntityAttributeSchemaContract categoryUrl = session.getEntitySchema(Entities.CATEGORY)
				                                                         .orElseThrow()
				                                                         .getAttribute(ATTRIBUTE_URL)
				                                                         .orElseThrow();
				assertNull(categoryUrl.getDescription());
				assertTrue(categoryUrl.isLocalized());
				assertTrue(categoryUrl.isUnique());
				assertTrue(categoryUrl instanceof GlobalAttributeSchemaContract ga && ga.isUniqueGlobally());
			}
		);

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
				       .openForWrite()
				       .withAttribute(
					       ATTRIBUTE_URL, String.class,
					       whichIs -> whichIs
						       .withDescription("URL of the entity")
						       .localized(() -> false)
						       .uniqueGloballyWithinLocale()
				       )
				       .updateVia(session);
			}
		);

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityAttributeSchemaContract productUrl = session.getEntitySchema(Entities.PRODUCT).orElseThrow()
				                                                        .getAttribute(ATTRIBUTE_URL).orElseThrow();
				assertEquals("URL of the entity", productUrl.getDescription());
				assertFalse(productUrl.isLocalized());
				assertTrue(productUrl.isUnique());
				assertTrue(productUrl instanceof GlobalAttributeSchemaContract ga && ga.isUniqueGloballyWithinLocale());

				final EntityAttributeSchemaContract categoryUrl = session.getEntitySchema(Entities.CATEGORY)
				                                                         .orElseThrow()
				                                                         .getAttribute(ATTRIBUTE_URL)
				                                                         .orElseThrow();
				assertEquals("URL of the entity", categoryUrl.getDescription());
				assertFalse(categoryUrl.isLocalized());
				assertTrue(categoryUrl.isUnique());
				assertTrue(
					categoryUrl instanceof GlobalAttributeSchemaContract ga && ga.isUniqueGloballyWithinLocale());
			}
		);
	}

	/**
	 * Tests that a reflected reference can be created.
	 *
	 * The test verifies that:
	 * - A reflected reference can be created between two entity types
	 * - The reflected reference correctly inherits properties from the original reference
	 * - Entities can be queried through the reflected reference
	 */
	@Test
	@DisplayName("Create reflected reference")
	void shouldCreateReflectedReference() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// we can create reflected reference even before the main one is created
				session.defineEntitySchema(Entities.CATEGORY)
				       .withReflectedReferenceToEntity(
					       REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY,
					       whichIs -> whichIs.withAttributesInheritedExcept("note")
					                         .withFacetedInherited()
					                         .withAttribute("customNote", String.class)
				       )
				       .updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs
							.withDescription("Assigned category.")
							.deprecated("Already deprecated.")
							.withAttribute("categoryPriority", Long.class, AttributeSchemaEditor::sortable)
							.withAttribute("note", String.class)
							.faceted()
							.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);

				final SealedEntitySchema categorySchema = session.getEntitySchema(Entities.CATEGORY)
				                                                 .orElseThrow();

				final ReferenceSchemaContract reflectedReference = categorySchema.getReference(
					REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY).orElseThrow();
				assertInstanceOf(ReflectedReferenceSchemaContract.class, reflectedReference);

				assertEquals("Assigned category.", reflectedReference.getDescription());
				assertEquals("Already deprecated.", reflectedReference.getDeprecationNotice());
				assertEquals(Cardinality.ZERO_OR_ONE, reflectedReference.getCardinality());
				assertTrue(reflectedReference.isIndexed());
				assertTrue(reflectedReference.isFaceted());

				final Map<String, AttributeSchemaContract> attributes = reflectedReference.getAttributes();
				assertEquals(2, attributes.size());
				assertTrue(attributes.containsKey("categoryPriority"));
				assertFalse(attributes.get("categoryPriority").isFilterable());
				assertTrue(attributes.get("categoryPriority").isSortable());
				assertTrue(attributes.containsKey("customNote"));
			}
		);
	}

	/**
	 * Tests that a reflected reference retains inheritance during engine restart.
	 *
	 * The test verifies that:
	 * - A reflected reference can be created between two entity types
	 * - The reflected reference correctly inherits properties from the original reference
	 * - The inheritance is preserved after Evita restart
	 * - Entities can be queried through the reflected reference after restart
	 */
	@Test
	@DisplayName("Reflected reference retains inheritance during engine restart")
	void shouldCreateReflectedReferenceAndRetainInheritanceDuringEngineRestart() {
		shouldCreateReflectedReference();

		this.evita.close();

		this.evita = new Evita(
			getEvitaConfiguration()
		);

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntitySchema categorySchema = session.getEntitySchema(Entities.CATEGORY)
				                                                 .orElseThrow();

				final ReferenceSchemaContract reflectedReference = categorySchema.getReference(
					REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY).orElseThrow();
				assertInstanceOf(ReflectedReferenceSchemaContract.class, reflectedReference);

				assertEquals("Assigned category.", reflectedReference.getDescription());
				assertEquals("Already deprecated.", reflectedReference.getDeprecationNotice());
				assertEquals(Cardinality.ZERO_OR_ONE, reflectedReference.getCardinality());
				assertTrue(reflectedReference.isIndexed());
				assertTrue(reflectedReference.isFaceted());

				final Map<String, AttributeSchemaContract> attributes = reflectedReference.getAttributes();
				assertEquals(2, attributes.size());
				assertTrue(attributes.containsKey("categoryPriority"));
				assertFalse(attributes.get("categoryPriority").isFilterable());
				assertTrue(attributes.get("categoryPriority").isSortable());
				assertTrue(attributes.containsKey("customNote"));
			}
		);
	}

	/**
	 * Tests that inherited properties in a reflected reference are updated.
	 *
	 * The test verifies that:
	 * - A reflected reference can be created between two entity types
	 * - When properties of the original reference are updated, they are automatically inherited by the reflected reference
	 * - The updated properties can be used in queries through the reflected reference
	 */
	@Test
	@DisplayName("Update inherited properties in reflected reference")
	void shouldUpdateInheritedPropertiesInReflectedReference() {
		shouldCreateReflectedReference();
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// we can create reflected reference even before the main one is created
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.EXACTLY_ONE,
						whichIs -> whichIs
							.withDescription("Assigned category (updated).")
							.notDeprecatedAnymore()
							.withAttribute("categoryPriority", Long.class, thatIs -> thatIs.sortable().filterable())
							.withAttribute("note", String.class)
							.withAttribute("additionalAttribute", String.class)
							.indexedForFilteringAndPartitioning()
							.nonFaceted()
					)
					.updateVia(session);

				final SealedEntitySchema categorySchema = session.getEntitySchema(Entities.CATEGORY)
				                                                 .orElseThrow();

				final ReferenceSchemaContract reflectedReference = categorySchema.getReference(
					REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY).orElseThrow();
				assertInstanceOf(ReflectedReferenceSchemaContract.class, reflectedReference);

				assertEquals("Assigned category (updated).", reflectedReference.getDescription());
				assertNull(reflectedReference.getDeprecationNotice());
				assertEquals(Cardinality.EXACTLY_ONE, reflectedReference.getCardinality());
				assertTrue(reflectedReference.isIndexed());
				assertFalse(reflectedReference.isFaceted());

				final Map<String, AttributeSchemaContract> attributes = reflectedReference.getAttributes();
				assertEquals(3, attributes.size());
				assertTrue(attributes.containsKey("categoryPriority"));
				assertTrue(attributes.get("categoryPriority").isFilterable());
				assertTrue(attributes.get("categoryPriority").isSortable());
				assertTrue(attributes.containsKey("customNote"));
				assertTrue(attributes.containsKey("additionalAttribute"));
			}
		);
	}

	/**
	 * Tests that a reflected reference can be dropped and replaced with a regular reference of the same name.
	 *
	 * The test verifies that:
	 * - A reflected reference can be created between two entity types
	 * - The reflected reference can be dropped
	 * - A regular reference with the same name can be created after dropping the reflected reference
	 * - The new regular reference works correctly
	 */
	@Test
	@DisplayName("Drop reflected reference and create regular one with same name")
	void shouldDropReflectedReferenceAndCreateRegularOneOfTheSameName() {
		shouldCreateReflectedReference();
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// we can create reflected reference even before the main one is created
				session
					.defineEntitySchema(Entities.CATEGORY)
					.withoutReferenceTo(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY)
					.withReferenceToEntity(
						REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, Cardinality.ZERO_OR_ONE
					)
					.updateVia(session);

				final SealedEntitySchema categorySchema = session.getEntitySchema(Entities.CATEGORY)
				                                                 .orElseThrow();

				final ReferenceSchemaContract newReference = categorySchema.getReference(
					REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY).orElseThrow();
				assertFalse(newReference instanceof ReflectedReferenceSchemaContract);

				assertNull(newReference.getDescription());
				assertNull(newReference.getDeprecationNotice());
				assertEquals(Cardinality.ZERO_OR_ONE, newReference.getCardinality());
				assertFalse(newReference.isIndexed());
				assertFalse(newReference.isFaceted());

				final Map<String, AttributeSchemaContract> attributes = newReference.getAttributes();
				assertEquals(0, attributes.size());
			}
		);
	}

	/**
	 * Tests that a regular reference can be dropped and replaced with a reflected reference of the same name.
	 *
	 * The test verifies that:
	 * - A regular reference can be created between two entity types
	 * - The regular reference can be dropped
	 * - A reflected reference with the same name can be created after dropping the regular reference
	 * - The new reflected reference works correctly and inherits properties from its source reference
	 */
	@Test
	@DisplayName("Drop regular reference and create reflected one with same name")
	void shouldDropRegularReferenceAndCreateReflectedOneOfTheSameName() {
		shouldDropReflectedReferenceAndCreateRegularOneOfTheSameName();
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// we can create reflected reference even before the main one is created
				session.defineEntitySchema(Entities.CATEGORY)
				       .withoutReferenceTo(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY)
				       .withReflectedReferenceToEntity(
					       REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY,
					       whichIs -> whichIs.withAttributesInheritedExcept("note")
					                         .withAttribute("customNote", String.class)
				       )
				       .updateVia(session);

				final SealedEntitySchema categorySchema = session.getEntitySchema(Entities.CATEGORY)
				                                                 .orElseThrow();

				final ReferenceSchemaContract reflectedReference = categorySchema.getReference(
					REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY).orElseThrow();
				assertInstanceOf(ReflectedReferenceSchemaContract.class, reflectedReference);

				assertEquals("Assigned category.", reflectedReference.getDescription());
				assertEquals("Already deprecated.", reflectedReference.getDeprecationNotice());
				assertEquals(Cardinality.ZERO_OR_ONE, reflectedReference.getCardinality());
				assertTrue(reflectedReference.isIndexed());
				assertTrue(reflectedReference.isFaceted());

				final Map<String, AttributeSchemaContract> attributes = reflectedReference.getAttributes();
				assertEquals(2, attributes.size());
				assertTrue(attributes.containsKey("categoryPriority"));
				assertFalse(attributes.get("categoryPriority").isFilterable());
				assertTrue(attributes.get("categoryPriority").isSortable());
				assertTrue(attributes.containsKey("customNote"));
			}
		);
	}

	/**
	 * Tests that creating a non-indexed reference fails when a reflected reference exists.
	 *
	 * The test verifies that:
	 * - Attempting to create a non-indexed reference when a reflected reference exists throws InvalidSchemaMutationException
	 * - The exception message correctly explains that the reference must be indexed because a reflected reference exists
	 */
	@Test
	@DisplayName("Fail to create non-indexed reference when reflected reference exists")
	void shouldFailToCreateNonIndexedReferenceWhenReflectedReferenceExists() {
		assertThrows(
			InvalidSchemaMutationException.class,
			() ->
				this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.defineEntitySchema(Entities.CATEGORY)
						       .withReflectedReferenceToEntity(
							       REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT,
							       REFERENCE_PRODUCT_CATEGORY
						       )
						       .updateVia(session);

						session.defineEntitySchema(Entities.PRODUCT)
						       .withReferenceToEntity(
							       REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
							       ReferenceSchemaEditor::nonIndexed
						       )
						       .updateVia(session);
					}
				)
		);
	}

	/**
	 * Tests that creating a reflected reference to a non-indexed reference fails.
	 *
	 * The test verifies that:
	 * - Attempting to create a reflected reference to a non-indexed reference throws InvalidSchemaMutationException
	 * - The exception message correctly explains that the source reference must be indexed
	 */
	@Test
	@DisplayName("Fail to create reflected reference to non-indexed reference")
	void shouldFailToCreateReflectedReferenceToNonIndexedReference() {
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
					       .withReferenceToEntity(
						       REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
						       ReferenceSchemaEditor::nonIndexed
					       )
					       .updateVia(session);

					session.defineEntitySchema(Entities.CATEGORY)
					       .withReflectedReferenceToEntity(
						       REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY)
					       .updateVia(session);
				}
			)
		);
	}

	/**
	 * Tests that creating a non-managed reference fails when an entity with the same name exists.
	 *
	 * The test verifies that:
	 * - Attempting to create a non-managed reference with the same name as an existing entity throws InvalidSchemaMutationException
	 * - The exception message correctly explains the conflict between the reference name and entity name
	 */
	@Test
	@DisplayName("Fail to create non-managed reference when entity with same name exists")
	void shouldFailToCreateNonManagedReferenceWhenEntityOfSuchNameExists() {
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.CATEGORY)
					       .updateVia(session);

					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceTo(REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE)
						.updateVia(session);
				}
			)
		);
	}

	/**
	 * Tests that creating a managed reference fails when the referenced entity doesn't exist.
	 *
	 * The test verifies that:
	 * - Attempting to create a managed reference to a non-existent entity throws InvalidSchemaMutationException
	 * - The exception message correctly explains that the referenced entity must exist
	 */
	@Test
	@DisplayName("Fail to create managed reference when referenced entity doesn't exist")
	void shouldFailToCreateManagedReferenceWhenEntityOfSuchNameDoesntExist() {
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE)
						.updateVia(session);
				}
			)
		);
	}

	/**
	 * Tests that creating a non-managed group reference fails when an entity with the same name exists.
	 *
	 * The test verifies that:
	 * - Attempting to create a non-managed group reference with the same name as an existing entity throws InvalidSchemaMutationException
	 * - The exception message correctly explains the conflict between the reference name and entity name
	 */
	@Test
	@DisplayName("Fail to create non-managed group reference when entity with same name exists")
	void shouldFailToCreateNonManagedGroupReferenceWhenEntityOfSuchNameExists() {
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PARAMETER_GROUP)
					       .updateVia(session);

					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceTo(
							REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
							whichIs -> whichIs.withGroupType(Entities.PARAMETER_GROUP)
						)
						.updateVia(session);
				}
			)
		);
	}

	/**
	 * Tests that creating a managed group reference fails when the referenced entity doesn't exist.
	 *
	 * The test verifies that:
	 * - Attempting to create a managed group reference to a non-existent entity throws InvalidSchemaMutationException
	 * - The exception message correctly explains that the referenced entity must exist
	 */
	@Test
	@DisplayName("Fail to create managed group reference when referenced entity doesn't exist")
	void shouldFailToCreateManagedGroupReferenceWhenEntityOfSuchNameDoesntExist() {
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceTo(
							REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
							whichIs -> whichIs.withGroupTypeRelatedToEntity(Entities.PARAMETER_GROUP)
						)
						.updateVia(session);
				}
			)
		);
	}

	/**
	 * Tests that changing a reference to non-indexed fails when there is a filterable attribute present.
	 *
	 * The test verifies that:
	 * - Attempting to change a reference to non-indexed when it has a filterable attribute throws InvalidSchemaMutationException
	 * - The exception message correctly explains that the reference must remain indexed due to the filterable attribute
	 */
	@Test
	@DisplayName("Fail to change reference to non-indexed when filterable attribute is present")
	void shouldFailToChangeReferenceToNonIndexedWhenThereIsFilterableAttributePresent() {
		// set-up correctly created indexed schema with filterable attribute
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceTo(
						REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs
							.withAttribute("categoryPriority", Long.class, thatIs -> thatIs.filterable())
							.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);
			}
		);
		// now try to un-index the reference
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(
							REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
							ReferenceSchemaEditor::nonIndexed
						)
						.updateVia(session);
				}
			)
		);
	}

	/**
	 * Tests that changing a reference to non-indexed fails when there is a unique attribute present.
	 *
	 * The test verifies that:
	 * - Attempting to change a reference to non-indexed when it has a unique attribute throws InvalidSchemaMutationException
	 * - The exception message correctly explains that the reference must remain indexed due to the unique attribute
	 */
	@Test
	@DisplayName("Fail to change reference to non-indexed when unique attribute is present")
	void shouldFailToChangeReferenceToNonIndexedWhenThereIsUniqueAttributePresent() {
		// set-up correctly created indexed schema with filterable attribute
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceTo(
						REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs
							.withAttribute("categoryPriority", Long.class, thatIs -> thatIs.unique())
							.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);
			}
		);
		// now try to un-index the reference
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(
							REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
							ReferenceSchemaEditor::nonIndexed
						)
						.updateVia(session);
				}
			)
		);
	}

	/**
	 * Tests that changing a reference to non-indexed fails when it has a sortable attribute.
	 *
	 * The test verifies that:
	 * - A reference with a sortable attribute cannot be changed to non-indexed
	 * - An InvalidSchemaMutationException is thrown when attempting this change
	 */
	@Test
	@DisplayName("Fail to change reference to non-indexed when sortable attribute exists")
	void shouldFailToChangeReferenceToNonIndexedWhenThereIsSortableAttributePresent() {
		// set-up correctly created indexed schema with filterable attribute
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceTo(
						REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs
							.withAttribute("categoryPriority", Long.class, thatIs -> thatIs.sortable())
							.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);
			}
		);
		// now try to un-index the reference
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(
							REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
							ReferenceSchemaEditor::nonIndexed
						)
						.updateVia(session);
				}
			)
		);
	}

	/**
	 * Tests that changing a reference entity type fails when there is a reflected reference.
	 *
	 * The test verifies that:
	 * - When a reference is part of a reflected reference relationship, its entity type cannot be changed
	 * - An InvalidSchemaMutationException is thrown when attempting this change
	 */
	@Test
	@DisplayName("Fail to change reference entity type with reflected reference")
	void shouldFailToChangeReferenceEntityTypeWhenThereIsReflectedReference() {
		// first create intertwined references
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
				       .withReflectedReferenceToEntity(
					       REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY
				       )
				       .updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
						ReferenceSchemaEditor::indexedForFilteringAndPartitioning
					)
					.updateVia(session);
			}
		);

		// now try to change the target entity
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(REFERENCE_PRODUCT_CATEGORY, Entities.PARAMETER, Cardinality.ZERO_OR_ONE)
						.updateVia(session);
				}
			)
		);
	}

	/**
	 * Tests that changing a reference entity type to non-managed fails when there is a reflected reference.
	 *
	 * The test verifies that:
	 * - When a reference is part of a reflected reference relationship, it cannot be changed to non-managed
	 * - An InvalidSchemaMutationException is thrown when attempting this change
	 */
	@Test
	@DisplayName("Fail to change reference to non-managed with reflected reference")
	void shouldFailToChangeReferenceEntityTypeToNonManagedWhenThereIsReflectedReference() {
		// first create intertwined references
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
				       .withReflectedReferenceToEntity(
					       REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY
				       )
				       .updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
						ReferenceSchemaEditor::indexedForFilteringAndPartitioning
					)
					.updateVia(session);
			}
		);

		// now try to change the target entity
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceTo(REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE)
						.updateVia(session);
				}
			)
		);
	}

	/**
	 * Tests that changing a reflected reference to non-indexed fails.
	 *
	 * The test verifies that:
	 * - When a reference is part of a reflected reference relationship, it cannot be changed to non-indexed
	 * - An InvalidSchemaMutationException is thrown when attempting this change
	 */
	@Test
	@DisplayName("Fail to change reflected reference to non-indexed")
	void shouldFailToChangeReferenceToNonIndexed() {
		// set-up correctly created indexed schema with filterable attribute
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {

				session.defineEntitySchema(Entities.CATEGORY)
				       .withReflectedReferenceToEntity(
					       REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY)
				       .updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs
							.withAttribute("categoryPriority", Long.class)
							.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);
			}
		);
		// now try to un-index the reference, and it should be ok
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.CATEGORY)
						.withReflectedReferenceToEntity(
							REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY,
							ReflectedReferenceSchemaEditor::nonIndexed
						)
						.updateVia(session);
				}
			)
		);
	}

	/**
	 * Tests that changing a reference to non-indexed fails when there is an inherited filterable attribute in a reflected reference.
	 *
	 * The test verifies that:
	 * - When a reference has an inherited filterable attribute in a reflected reference, it cannot be changed to non-indexed
	 * - An InvalidSchemaMutationException is thrown when attempting this change
	 */
	@Test
	@DisplayName("Fail to change reference to non-indexed with inherited filterable attribute")
	void shouldFailToChangeReferenceToNonIndexedWhenThereIsInheritedFilterableAttributePresentInReflectedReference() {
		// set-up correctly created indexed schema with filterable attribute
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {

				session.defineEntitySchema(Entities.CATEGORY)
				       .withReflectedReferenceToEntity(
					       REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY,
					       ReflectedReferenceSchemaEditor::withAttributesInherited
				       )
				       .updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs
							.withAttribute("categoryPriority", Long.class, thatIs -> thatIs.filterable())
							.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);
			}
		);
		// now try to un-index the reference
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.CATEGORY)
						.withReflectedReferenceToEntity(
							REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY,
							ReflectedReferenceSchemaEditor::nonIndexed
						)
						.updateVia(session);
				}
			)
		);
	}

	/**
	 * Tests that changing a reference to non-indexed fails when there is an inherited unique attribute in a reflected reference.
	 *
	 * The test verifies that:
	 * - When a reference has an inherited unique attribute in a reflected reference, it cannot be changed to non-indexed
	 * - An InvalidSchemaMutationException is thrown when attempting this change
	 */
	@Test
	@DisplayName("Fail to change reference to non-indexed with inherited unique attribute")
	void shouldFailToChangeReferenceToNonIndexedWhenThereIsInheritedUniqueAttributePresentInReflectedReference() {
		// set-up correctly created indexed schema with filterable attribute
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {

				session.defineEntitySchema(Entities.CATEGORY)
				       .withReflectedReferenceToEntity(
					       REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY,
					       ReflectedReferenceSchemaEditor::withAttributesInherited
				       )
				       .updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs
							.withAttribute("categoryPriority", Long.class, thatIs -> thatIs.unique())
							.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);
			}
		);
		// now try to un-index the reference
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.CATEGORY)
						.withReflectedReferenceToEntity(
							REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY,
							ReflectedReferenceSchemaEditor::nonIndexed
						)
						.updateVia(session);
				}
			)
		);
	}

	/**
	 * Tests that changing a reference to non-indexed fails when there is an inherited sortable attribute in a reflected reference.
	 *
	 * The test verifies that:
	 * - When a reference has an inherited sortable attribute in a reflected reference, it cannot be changed to non-indexed
	 * - An InvalidSchemaMutationException is thrown when attempting this change
	 */
	@Test
	@DisplayName("Fail to change reference to non-indexed with inherited sortable attribute")
	void shouldFailToChangeReferenceToNonIndexedWhenThereIsInheritedSortableAttributePresentInReflectedReference() {
		// set-up correctly created indexed schema with filterable attribute
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {

				session.defineEntitySchema(Entities.CATEGORY)
				       .withReflectedReferenceToEntity(
					       REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY,
					       ReflectedReferenceSchemaEditor::withAttributesInherited
				       )
				       .updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs
							.withAttribute("categoryPriority", Long.class, thatIs -> thatIs.sortable())
							.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);
			}
		);
		// now try to un-index the reference
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.CATEGORY)
						.withReflectedReferenceToEntity(
							REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY,
							whichIs -> whichIs.nonIndexed()
						)
						.updateVia(session);
				}
			)
		);
	}

	/**
	 * Tests fetching an entity by localized global attribute with automatic locale selection.
	 *
	 * The test verifies that:
	 * - An entity can be fetched by a localized global attribute
	 * - The proper locale is automatically selected based on the attribute value
	 * - The entity's attributes are returned in the correct locale
	 */
	@Test
	@DisplayName("Fetch entity by localized global attribute with automatic locale selection")
	void shouldFetchEntityByLocalizedGlobalAttributeAutomaticallySelectingProperLocale() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
				       .openForWrite()
				       .withAttribute(ATTRIBUTE_URL, String.class, whichIs -> whichIs.localized().uniqueGlobally())
				       .updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withGlobalAttribute(ATTRIBUTE_URL)
					.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
					.updateVia(session);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
					       .setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Hence, product")
					       .setAttribute(ATTRIBUTE_NAME, LOCALE_CZ, "Hle, produkt")
					       .setAttribute(ATTRIBUTE_URL, Locale.ENGLISH, "/theProduct")
					       .setAttribute(ATTRIBUTE_URL, LOCALE_CZ, "/tenProdukt")
				);

				final SealedEntity result = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(attributeEquals(ATTRIBUTE_URL, "/tenProdukt")),
						require(entityFetch(attributeContent()))
					)
				).orElseThrow();

				assertEquals("Hle, produkt", result.getAttribute(ATTRIBUTE_NAME));

				final Set<Locale> locales = result.getLocales();
				assertEquals(1, locales.size());
				assertTrue(locales.contains(LOCALE_CZ));
				assertEquals(LOCALE_CZ, ((EntityDecorator) result).getImplicitLocale());
			}
		);
	}

	/**
	 * Tests fetching multiple entities by localized global attribute with automatic locale selection per entity.
	 *
	 * The test verifies that:
	 * - Multiple entities can be fetched by localized global attributes
	 * - The proper locale is automatically selected for each entity based on the attribute value
	 * - Each entity's attributes are returned in the correct locale
	 */
	@Test
	@DisplayName("Fetch entities by localized global attribute with per-entity locale selection")
	void shouldFetchEntityByLocalizedGlobalAttributeAutomaticallySelectingProperLocalePerEntity() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
				       .openForWrite()
				       .withAttribute(ATTRIBUTE_URL, String.class, whichIs -> whichIs.localized().uniqueGlobally())
				       .updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withGlobalAttribute(ATTRIBUTE_URL)
					.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
					.updateVia(session);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
					       .setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Hence, product")
					       .setAttribute(ATTRIBUTE_NAME, LOCALE_CZ, "Hle, produkt")
					       .setAttribute(ATTRIBUTE_URL, Locale.ENGLISH, "/theProduct")
					       .setAttribute(ATTRIBUTE_URL, LOCALE_CZ, "/tenProdukt")
				);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 2)
					       .setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Hence, slightly different product")
					       .setAttribute(ATTRIBUTE_NAME, LOCALE_CZ, "Hle, trochu jiný produkt")
					       .setAttribute(ATTRIBUTE_URL, Locale.ENGLISH, "/theOtherProduct")
					       .setAttribute(ATTRIBUTE_URL, LOCALE_CZ, "/tenJinýProdukt")
				);

				final List<SealedEntity> result = session.queryListOfSealedEntities(
					query(
						collection(Entities.PRODUCT),
						filterBy(attributeInSet(ATTRIBUTE_URL, "/tenProdukt", "/theOtherProduct")),
						require(entityFetch(attributeContent()))
					)
				);

				assertNotNull(result);

				final SealedEntity firstProduct = result.stream()
				                                        .filter(it -> Objects.equals(it.getPrimaryKey(), 1))
				                                        .findFirst()
				                                        .orElse(null);
				final SealedEntity secondProduct = result.stream()
				                                         .filter(it -> Objects.equals(it.getPrimaryKey(), 2))
				                                         .findFirst()
				                                         .orElse(null);
				assertNotNull(firstProduct);
				assertNotNull(secondProduct);

				assertEquals("Hle, produkt", firstProduct.getAttribute(ATTRIBUTE_NAME));
				final Set<Locale> firstProductLocales = firstProduct.getLocales();
				assertEquals(1, firstProductLocales.size());
				assertTrue(firstProductLocales.contains(LOCALE_CZ));
				assertEquals(LOCALE_CZ, ((EntityDecorator) firstProduct).getImplicitLocale());

				assertEquals("Hence, slightly different product", secondProduct.getAttribute(ATTRIBUTE_NAME));
				final Set<Locale> secondProductLocales = secondProduct.getLocales();
				assertEquals(1, secondProductLocales.size());
				assertTrue(secondProductLocales.contains(Locale.ENGLISH));
				assertEquals(Locale.ENGLISH, ((EntityDecorator) secondProduct).getImplicitLocale());
			}
		);
	}

	/**
	 * Tests updating an existing price in reduced price indexes.
	 *
	 * The test verifies that:
	 * - An existing price can be updated in a product
	 * - The price changes are correctly reflected in the database
	 * - The updated price can be retrieved with the correct values
	 */
	@Test
	@DisplayName("Update existing price in reduced price indexes")
	void shouldUpdateExistingPriceInReducedPriceIndexes() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
				       .withoutGeneratedPrimaryKey()
				       .updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withPrice(2)
					.withReferenceToEntity(Entities.CATEGORY, Entities.CATEGORY, Cardinality.ONE_OR_MORE, ReferenceSchemaEditor::indexedForFilteringAndPartitioning)
					.updateVia(session);

				session.createNewEntity(Entities.CATEGORY, 1).upsertVia(session);
				session.createNewEntity(Entities.PRODUCT, 1)
				       .setReference(Entities.CATEGORY, 1)
				       .setPrice(1, "basic", CURRENCY_CZK, null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
				       .upsertVia(session);

				session.createNewEntity(Entities.PRODUCT, 2)
				       .setReference(Entities.CATEGORY, 1)
				       .setPrice(2, "basic", CURRENCY_CZK, null, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
				       .upsertVia(session);

				session.goLiveAndClose();
			}
		);

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(Entities.PRODUCT, 1, priceContentAll())
				       .orElseThrow()
				       .openForWrite()
				       .setPrice(1, "basic", CURRENCY_CZK, null, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, false)
				       .upsertVia(session);
			}
		);

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity sealedEntity = session.getEntity(Entities.PRODUCT, 1, priceContentAll())
				                                         .orElseThrow();

				assertEquals(
					BigDecimal.TEN, sealedEntity.getPrice(1, "basic", CURRENCY_CZK)
					                            .orElseThrow()
					                            .priceWithTax()
				);
			}
		);
	}

	/**
	 * Tests that defining two entities with the same name in different case fails.
	 *
	 * The test verifies that:
	 * - Attempting to define two entities with names that differ only in case throws an exception
	 * - The EntityTypeAlreadyPresentInCatalogSchemaException is thrown
	 */
	@Test
	@DisplayName("Fail to define entities with same name in different case")
	void shouldFailToDefineTwoEntitiesSharingNameInSpecificNamingConvention() {
		assertThrows(
			EntityTypeAlreadyPresentInCatalogSchemaException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema("abc");
					session.defineEntitySchema("ABc");
				}
			)
		);
	}

	/**
	 * Tests that defining references to non-existent managed entities fails.
	 *
	 * The test verifies that:
	 * - Attempting to define a reference to a non-existent managed entity throws an exception
	 * - The InvalidSchemaMutationException is thrown with appropriate message
	 */
	@Test
	@DisplayName("Fail to define references to non-existent managed entities")
	void shouldFailToDefineReferencesToManagedEntitiesThatDontExist() {
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema("someEntity")
					       .withReferenceToEntity(
						       "someReference",
						       "nonExistingEntity",
						       Cardinality.ONE_OR_MORE
					       )
					       .updateVia(session);
				}
			)
		);
	}

	/**
	 * Tests that defining references to non-existent managed entity groups fails.
	 *
	 * The test verifies that:
	 * - Attempting to define a reference with a group type related to a non-existent entity throws an exception
	 * - The InvalidSchemaMutationException is thrown with appropriate message
	 */
	@Test
	@DisplayName("Fail to define references to non-existent managed entity groups")
	void shouldFailToDefineReferencesToManagedEntityGroupThatDoesntExist() {
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(
						       "someEntity"
					       )
					       .withReferenceTo(
						       "someReference",
						       "nonExistingEntityNonManagedEntity",
						       Cardinality.ONE_OR_MORE,
						       whichIs -> whichIs.withGroupTypeRelatedToEntity("nonExistingGroup")
					       )
					       .updateVia(session);
				}
			)
		);
	}

	/**
	 * Tests creating references to non-managed entities and groups.
	 *
	 * The test verifies that:
	 * - References to non-managed entities can be created
	 * - References with non-managed group types can be created
	 * - The entity schema is correctly updated with these references
	 */
	@Test
	@DisplayName("Create references to non-managed entities and groups")
	void shouldCreateReferencesToNonManagedEntityAndGroup() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(
					       "someEntity"
				       )
				       .withReferenceTo(
					       "someReference",
					       "nonExistingEntityNonManagedEntity",
					       Cardinality.ONE_OR_MORE,
					       whichIs -> whichIs.withGroupType("nonExistingNonManagedGroup")
				       ).updateVia(session);

				assertNotNull(session.getEntitySchema("someEntity"));
			}
		);
	}

	/**
	 * Tests creating circular references between managed entities.
	 *
	 * The test verifies that:
	 * - Circular references between managed entities can be created
	 * - Both entity schemas are correctly updated with these references
	 * - The circular references don't cause any issues during schema creation
	 */
	@Test
	@DisplayName("Create circular references between managed entities")
	void shouldCreateCircularReferencesToManagedEntitiesAndGroups() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final ModifyEntitySchemaMutation categoryMutation = session.defineEntitySchema(
					                                                           Entities.CATEGORY
				                                                           )
				                                                           .withReferenceToEntity(
					                                                           Entities.PRODUCT, Entities.PRODUCT,
					                                                           Cardinality.ONE_OR_MORE
				                                                           )
				                                                           .toMutation()
				                                                           .orElseThrow();

				final ModifyEntitySchemaMutation productMutation = session.defineEntitySchema(
					                                                          Entities.PRODUCT
				                                                          )
				                                                          .withReferenceToEntity(
					                                                          Entities.CATEGORY, Entities.CATEGORY,
					                                                          Cardinality.ONE_OR_MORE
				                                                          )
				                                                          .toMutation()
				                                                          .orElseThrow();

				session.updateCatalogSchema(
					categoryMutation, productMutation
				);

				assertNotNull(session.getEntitySchema(Entities.CATEGORY));
				assertNotNull(session.getEntitySchema(Entities.PRODUCT));
			}
		);
	}

	/**
	 * Tests that requesting hierarchy on a non-hierarchical entity fails gracefully.
	 *
	 * The test verifies that:
	 * - Attempting to query hierarchy on a non-hierarchical entity throws an exception
	 * - The EntityIsNotHierarchicalException is thrown with appropriate message
	 */
	@Test
	@DisplayName("Graceful failure when requesting hierarchy on non-hierarchical entity")
	void shouldFailGracefullyWhenRequestingHierarchyOnNonHierarchyEntity() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
				);

				assertThrows(
					EntityIsNotHierarchicalException.class,
					() -> session.queryListOfSealedEntities(
						query(
							collection(Entities.PRODUCT),
							filterBy(hierarchyWithinRootSelf()),
							require(entityFetch())
						)
					)
				);
			}
		);
	}

	/**
	 * Tests that overlapping ranges work correctly when updated.
	 *
	 * The test verifies that:
	 * - Entities with overlapping ranges can be created and queried
	 * - When ranges are updated, the query results are updated accordingly
	 * - Range queries correctly match entities based on the current range values
	 */
	@Test
	@DisplayName("Correctly handle overlapping ranges when updated")
	void shouldCorrectlyWorkWithOverlappingRangesWhenUpdated() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withAttribute("range", IntegerNumberRange[].class, AttributeSchemaEditor::filterable)
					.updateVia(session);

				session.createNewEntity(Entities.PRODUCT, 1)
				       .setAttribute(
					       "range",
					       new IntegerNumberRange[]{
						       IntegerNumberRange.between(1, 5),
						       IntegerNumberRange.between(5, 10),
					       }
				       ).upsertVia(session);

				IntFunction<EntityReferenceContract> getInRange = (threshold) -> session.queryOneEntityReference(
					query(
						collection(Entities.PRODUCT),
						filterBy(attributeInRange("range", threshold))
					)
				).orElse(null);

				assertEquals(
					new EntityReference(Entities.PRODUCT, 1),
					getInRange.apply(4)
				);
				assertEquals(
					new EntityReference(Entities.PRODUCT, 1),
					getInRange.apply(7)
				);

				session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
				       .orElseThrow()
				       .openForWrite()
				       .setAttribute("range", new IntegerNumberRange[]{IntegerNumberRange.between(1, 5)})
				       .upsertVia(session);

				assertEquals(
					new EntityReference(Entities.PRODUCT, 1),
					getInRange.apply(4)
				);
				assertNull(
					getInRange.apply(7)
				);
			}
		);
	}

	/**
	 * Tests that schema changes are isolated within transactions.
	 *
	 * The test verifies that:
	 * - Schema changes made in one transaction are not visible to other transactions until committed
	 * - After commit, the changes become visible to new sessions
	 * - Concurrent transactions can modify the schema without conflicts
	 */
	@Test
	@DisplayName("Isolate schema changes within transactions")
	void shouldIsolateChangesInSchemaWithinTransactions() {
		// create some initial state
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
				       .withAttribute("someAttribute", String.class)
				       .updateVia(session);
				session.goLiveAndClose();
			}
		);

		// now open a new session and modify something
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				assertTrue(session.isTransactionOpen());
				session.getCatalogSchema()
				       .openForWrite()
				       .withDescription("This is my beautiful catalog.")
				       .updateVia(session);

				session.getEntitySchema(Entities.PRODUCT)
				       .orElseThrow()
				       .openForWrite()
				       .withDescription("This is my beautiful product collection.")
				       .withAttribute("someAttribute", String.class, thatIs -> thatIs.localized().filterable())
				       .withAttribute("differentAttribute", Integer.class)
				       .updateVia(session);

				// create different session in parallel (original session is not yet committed)
				final CountDownLatch latch = new CountDownLatch(1);
				final Thread testThread = new Thread(() -> {
					try {
						this.evita.queryCatalog(
							TEST_CATALOG,
							parallelSession -> {
								assertNull(parallelSession.getCatalogSchema().getDescription());

								final SealedEntitySchema productSchema = parallelSession.getEntitySchema(
									Entities.PRODUCT).orElseThrow();
								assertNull(productSchema.getDescription());

								final AttributeSchemaContract someAttributeSchema = productSchema
									.getAttribute("someAttribute")
									.orElseThrow();

								assertFalse(someAttributeSchema.isLocalized());
								assertFalse(someAttributeSchema.isFilterable());

								final AttributeSchemaContract differentAttributeSchema = productSchema
									.getAttribute("differentAttribute")
									.orElse(null);

								assertNull(differentAttributeSchema);

							}
						);
					} finally {
						latch.countDown();
					}
				});
				testThread.start();
				try {
					latch.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					fail(e.getMessage());
				}

				log.info("Committing changes.");
			}
		);

		// verify the changes were propagated at last
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertEquals("This is my beautiful catalog.", session.getCatalogSchema().getDescription());

				final SealedEntitySchema productSchema = session.getEntitySchema(Entities.PRODUCT).orElseThrow();
				assertEquals("This is my beautiful product collection.", productSchema.getDescription());

				final AttributeSchemaContract someAttributeSchema = productSchema
					.getAttribute("someAttribute")
					.orElseThrow();

				assertTrue(someAttributeSchema.isLocalized());
				assertTrue(someAttributeSchema.isFilterable());

				final AttributeSchemaContract differentAttributeSchema = productSchema
					.getAttribute("differentAttribute")
					.orElseThrow();
				assertNotNull(differentAttributeSchema);
			}
		);
	}

	/**
	 * Tests that correct catalog and schema versions are returned.
	 *
	 * The test verifies that:
	 * - Catalog and schema versions are correctly incremented after changes
	 * - Concurrent transactions receive different version numbers
	 * - The version ordering is maintained (later commits get higher version numbers)
	 * - All schema changes are correctly applied and visible after commits
	 */
	@Test
	@DisplayName("Return correct catalog and schema versions")
	void shouldReturnCorrectCatalogAndSchemaVersions() {
		// create some initial state
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
				       .withAttribute("someAttribute", String.class)
				       .updateVia(session);
				session.goLiveAndClose();
			}
		);

		final int initialCatalogSchemaVersion = this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getCatalogSchema().version();
			}
		);

		// create two parallel sessions
		final EvitaSessionContract session1 = this.evita.createSession(
			new SessionTraits(TEST_CATALOG, SessionFlags.READ_WRITE));
		final EvitaSessionContract session2 = this.evita.createSession(
			new SessionTraits(TEST_CATALOG, SessionFlags.READ_WRITE));

		// in both modify entity schema
		session1.getEntitySchema(Entities.PRODUCT)
		        .orElseThrow()
		        .openForWrite()
		        .withDescription("This is my beautiful product collection.")
		        .updateVia(session1);

		session2.getEntitySchema(Entities.PRODUCT)
		        .orElseThrow()
		        .openForWrite()
		        .withAttribute("someAttribute", String.class, thatIs -> thatIs.localized().filterable())
		        .updateVia(session2);

		final List<String> worklog = new CopyOnWriteArrayList<>();

		// commit second first
		final CommitProgress session2CommitProgress = session2.closeNowWithProgress();
		session2CommitProgress.onConflictResolved().thenAccept(
			commitVersions -> worklog.add("Session 2 conflict resolved: " + commitVersions));
		session2CommitProgress.onWalAppended().thenAccept(
			commitVersions -> worklog.add("Session 2 WAL appended: " + commitVersions));

		// commit first
		final CommitProgress session1CommitProgress = session1.closeNowWithProgress();
		session1CommitProgress.onConflictResolved().thenAccept(
			commitVersions -> worklog.add("Session 1 conflict resolved: " + commitVersions));
		session1CommitProgress.onWalAppended().thenAccept(
			commitVersions -> worklog.add("Session 1 WAL appended: " + commitVersions));

		final CompletableFuture<CommitVersions> session1Future = session1CommitProgress.onChangesVisible()
		                                                                               .thenApply(commitVersions -> {
			                                                                               worklog.add(
				                                                                               "Session 1 changes visible: " + commitVersions);
			                                                                               return commitVersions;
		                                                                               })
		                                                                               .toCompletableFuture();
		final CompletableFuture<CommitVersions> session2Future = session2CommitProgress.onChangesVisible()
		                                                                               .thenApply(commitVersions -> {
			                                                                               worklog.add(
				                                                                               "Session 2 changes visible: " + commitVersions);
			                                                                               return commitVersions;
		                                                                               })
		                                                                               .toCompletableFuture();

		CompletableFuture.allOf(
			session1Future,
			session2Future
		).join();

		final CommitVersions versionsAssignedAfter = session1Future.getNow(null);
		final CommitVersions versionsAssigned = session2Future.getNow(null);

		// check work log
		assertEquals(
			6,
			worklog.size(),
			"Expected 6 log entries, but got: " + worklog.size() + ". Log: " + String.join(", ", worklog)
		);

		// versions in second session, committed first will be lesser
		assertTrue(versionsAssigned.catalogVersion() < versionsAssignedAfter.catalogVersion());
		assertTrue(versionsAssigned.catalogSchemaVersion() < versionsAssignedAfter.catalogSchemaVersion());

		// verify the changes were propagated at last
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertEquals(initialCatalogSchemaVersion + 2, session.getCatalogSchema().version());

				final SealedEntitySchema productSchema = session.getEntitySchema(Entities.PRODUCT).orElseThrow();
				assertEquals("This is my beautiful product collection.", productSchema.getDescription());

				final AttributeSchemaContract someAttributeSchema = productSchema
					.getAttribute("someAttribute")
					.orElseThrow();

				assertTrue(someAttributeSchema.isLocalized());
				assertTrue(someAttributeSchema.isFilterable());
			}
		);
	}

	/**
	 * Tests that changes are still visible even if an exception is thrown during WAL append.
	 *
	 * The test verifies that:
	 * - When an exception is thrown during the WAL append phase, the transaction still completes
	 * - The changes made in the transaction are still visible after the exception
	 * - The system recovers gracefully from exceptions during the commit process
	 */
	@Test
	@DisplayName("Changes visible despite exception during WAL append")
	void shouldThrowExceptionInOnWalAppendedAndVerifyChangesAreStillVisible() {
		// create some initial state
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
				       .withAttribute("testAttribute", String.class)
				       .updateVia(session);
				session.goLiveAndClose();
			}
		);

		// create a session and make a change
		final EvitaSessionContract session = this.evita.createSession(
			new SessionTraits(TEST_CATALOG, SessionFlags.READ_WRITE));

		// create an entity
		session.upsertEntity(
			session.createNewEntity(Entities.PRODUCT, 1)
			       .setAttribute("testAttribute", "Test value")
		);

		// throw exception in onWalAppended completion stage
		final CommitProgress commitProgress = session.closeNowWithProgress();
		final Function<CommitVersions, Object> exceptionThrower = commitVersions -> {
			throw new RuntimeException("Test exception in onWalAppended");
		};
		commitProgress.onConflictResolved().thenApply(exceptionThrower);
		commitProgress.onWalAppended().thenApply(exceptionThrower);
		commitProgress.onChangesVisible().thenApply(exceptionThrower);

		// wait for the changes to be visible
		// this should not throw an exception because the exception in onWalAppended should not stop transaction processing
		final CommitVersions commitVersions = commitProgress.onChangesVisible().toCompletableFuture().join();
		assertNotNull(commitVersions);

		// verify that changes are visible in a new session
		this.evita.queryCatalog(
			TEST_CATALOG,
			session2 -> {
				// verify entity was created
				final EvitaResponse<EntityReference> response = session2.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(1)
						)
					),
					EntityReference.class
				);
				assertEquals(1, response.getTotalRecordCount());
			}
		);

		// restart evita engine
		this.evita.close();
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.waitUntilFullyInitialized();

		// verify that changes are still visible after restart
		this.evita.queryCatalog(
			TEST_CATALOG,
			session3 -> {
				// verify entity was created
				final EvitaResponse<EntityReference> response = session3.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(1)
						)
					),
					EntityReference.class
				);
				assertEquals(1, response.getTotalRecordCount());
			}
		);
	}

	/**
	 * Tests that Evita can start even if one of the catalogs is corrupted.
	 *
	 * The test verifies that:
	 * - Evita can start with a corrupted catalog
	 * - The corrupted catalog is marked as CORRUPTED
	 * - External API servers can still be started
	 * - Non-corrupted catalogs can still be accessed
	 */
	@Test
	@DisplayName("Evita starts even with a corrupted catalog")
	void shouldStartEvenIfOneCatalogIsCorrupted() {
		assertTrue(this.evita.getCatalogState(TEST_CATALOG + "_1").isEmpty());

		this.evita.defineCatalog(TEST_CATALOG + "_1")
		          .updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			TEST_CATALOG + "_1",
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
				);
			}
		);

		this.evita.defineCatalog(TEST_CATALOG + "_2")
		          .updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			TEST_CATALOG + "_2",
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
				);
			}
		);

		assertEquals(CatalogState.WARMING_UP, this.evita.getCatalogState(TEST_CATALOG + "_1").orElseThrow());

		this.evita.close();

		// damage the TEST_CATALOG_1 contents
		try {
			final Path productCollectionFile = getEvitaTestDirectory().resolve(
				TEST_CATALOG + "_1" + File.separator + Entities.PRODUCT.toLowerCase() + "-1_0" + CatalogPersistenceService.ENTITY_COLLECTION_FILE_SUFFIX);
			Files.write(productCollectionFile, "Mangled content!".getBytes(StandardCharsets.UTF_8));
		} catch (Exception ex) {
			fail(ex);
		}

		this.evita = new Evita(
			getEvitaConfiguration()
		);

		assertEquals(CatalogState.CORRUPTED, this.evita.getCatalogState(TEST_CATALOG + "_1").orElseThrow());

		final PortManager portManager = getPortManager();
		final String dataSetName = "evitaTest";
		final int[] ports = portManager.allocatePorts(dataSetName, 3);
		try {
			try (ExternalApiServer externalApiServer = new ExternalApiServer(
				this.evita,
				ApiOptions.builder()
				          .certificate(
					          CertificateOptions.builder()
					                            .folderPath(getEvitaTestDirectory() + "-certificates")
					                            .build()
				          )
				          .enable(GraphQLProvider.CODE, new GraphQLOptions(":" + ports[0]))
				          .enable(GrpcProvider.CODE, new GrpcOptions(":" + ports[1]))
				          .enable(RestProvider.CODE, new RestOptions(":" + ports[2]))
				          .build()
			)) {
				externalApiServer.start();
			}

			final Set<String> catalogNames = this.evita.getCatalogNames();
			assertEquals(3, catalogNames.size());

			assertThrows(
				CatalogCorruptedException.class,
				() -> this.evita.updateCatalog(
					TEST_CATALOG + "_1",
					session -> {
						session.getAllEntityTypes();
					}
				)
			);

			final CatalogStatistics[] catalogStatistics = this.evita.management().getCatalogStatistics();
			assertNotNull(catalogStatistics);
			assertEquals(3, catalogStatistics.length);

			final CatalogStatistics statistics = Arrays.stream(catalogStatistics).filter(
				it -> TEST_CATALOG.equals(it.catalogName())).findFirst().orElseThrow();
			assertTrue(
				statistics.sizeOnDiskInBytes() > 400L && statistics.sizeOnDiskInBytes() < 600L,
				"Expected size on disk to be between 400 and 600 bytes, but was " + statistics.sizeOnDiskInBytes()
			);
			assertEquals(
				new CatalogStatistics(
					UUIDUtil.randomUUID(), TEST_CATALOG, false, false, CatalogState.WARMING_UP, 0L, 0, 1,
					statistics.sizeOnDiskInBytes(), new EntityCollectionStatistics[0]
				),
				statistics
			);

			final CatalogStatistics statistics1 = Arrays.stream(catalogStatistics).filter(
				it -> (TEST_CATALOG + "_1").equals(it.catalogName())).findFirst().orElseThrow();
			assertTrue(
				statistics1.sizeOnDiskInBytes() > 900L && statistics1.sizeOnDiskInBytes() < 1200L,
				"Expected size on disk to be between 900 and 1200 bytes, but was " + statistics1.sizeOnDiskInBytes()
			);
			assertEquals(
				new CatalogStatistics(
					UUIDUtil.randomUUID(), TEST_CATALOG + "_1", true, false, CatalogState.CORRUPTED, -1L, -1, -1,
					statistics1.sizeOnDiskInBytes(), new EntityCollectionStatistics[0]
				),
				statistics1
			);

			final CatalogStatistics statistics2 = Arrays.stream(catalogStatistics).filter(
				it -> (TEST_CATALOG + "_2").equals(it.catalogName())).findFirst().orElseThrow();
			assertTrue(
				statistics2.sizeOnDiskInBytes() > 1000L && statistics2.sizeOnDiskInBytes() < 1800L,
				"Expected size on disk to be between 1000 and 1700 bytes, but was " + statistics2.sizeOnDiskInBytes()
			);
			final EntityCollectionStatistics productStatistics = statistics2.entityCollectionStatistics()[0];
			assertTrue(
				productStatistics.sizeOnDiskInBytes() > 300L && productStatistics.sizeOnDiskInBytes() < 600L,
				"Expected size on disk to be between 300 and 600 bytes, but was " + productStatistics.sizeOnDiskInBytes()
			);
			assertEquals(
				new CatalogStatistics(
					UUIDUtil.randomUUID(), TEST_CATALOG + "_2", false, false, CatalogState.WARMING_UP, 0, 1, 2,
					statistics2.sizeOnDiskInBytes(),
					new EntityCollectionStatistics[]{
						new EntityCollectionStatistics(Entities.PRODUCT, 1, 1, productStatistics.sizeOnDiskInBytes())
					}
				),
				statistics2
			);

		} finally {
			portManager.releasePorts(dataSetName);
		}
	}

	/**
	 * Tests that a new catalog can be created even if one existing catalog is corrupted.
	 *
	 * The test verifies that:
	 * - Evita can start even with a corrupted catalog
	 * - The corrupted catalog is properly identified and marked as corrupted
	 * - New catalogs can still be created despite the presence of a corrupted catalog
	 * - Attempting to use the corrupted catalog throws a CatalogCorruptedException
	 */
	@Test
	@DisplayName("Create catalog even with a corrupted catalog present")
	void shouldCreateCatalogEvenIfOneCatalogIsCorrupted() {
		this.evita.defineCatalog(TEST_CATALOG + "_1")
		          .updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			TEST_CATALOG + "_1",
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
				);
			}
		);

		this.evita.defineCatalog(TEST_CATALOG + "_2")
		          .updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			TEST_CATALOG + "_2",
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
				);
			}
		);

		this.evita.close();

		// damage the TEST_CATALOG_1 contents
		try {
			final Path productCollectionFile = getEvitaTestDirectory().resolve(
				TEST_CATALOG + "_1" + File.separator + Entities.PRODUCT.toLowerCase() + "-1_0" + CatalogPersistenceService.ENTITY_COLLECTION_FILE_SUFFIX);
			Files.write(productCollectionFile, "Mangled content!".getBytes(StandardCharsets.UTF_8));
		} catch (Exception ex) {
			fail(ex);
		}

		this.evita = new Evita(
			getEvitaConfiguration()
		);

		final PortManager portManager = getPortManager();
		final String dataSetName = "evitaTest";
		final int[] ports = portManager.allocatePorts(dataSetName, 3);
		try {
			try (ExternalApiServer externalApiServer = new ExternalApiServer(
				this.evita,
				ApiOptions.builder()
				          .certificate(
					          CertificateOptions.builder()
					                            .folderPath(getEvitaTestDirectory() + "-certificates")
					                            .build()
				          )
				          .enable(GraphQLProvider.CODE, new GraphQLOptions(":" + ports[0]))
				          .enable(GrpcProvider.CODE, new GrpcOptions(":" + ports[1]))
				          .enable(RestProvider.CODE, new RestOptions(":" + ports[2]))
				          .build()
			)) {
				externalApiServer.start();
			}

			final Set<String> catalogNames = this.evita.getCatalogNames();
			assertEquals(3, catalogNames.size());

			assertThrows(
				CatalogCorruptedException.class,
				() -> this.evita.updateCatalog(
					TEST_CATALOG + "_1",
					session -> {
						session.getAllEntityTypes();
					}
				)
			);

			// but allow creating new catalog
			this.evita.defineCatalog(TEST_CATALOG + "_3")
			          .updateViaNewSession(this.evita);

			assertTrue(this.evita.getCatalogNames().contains(TEST_CATALOG + "_3"));

		} finally {
			portManager.releasePorts(dataSetName);
		}
	}

	/**
	 * Tests that a corrupted catalog can be replaced with a correct one.
	 *
	 * The test verifies that:
	 * - A corrupted catalog can be detected
	 * - A new catalog can be created even when a corrupted catalog exists
	 * - The corrupted catalog can be replaced with a correct one
	 * - After replacement, the catalog can be accessed normally
	 */
	@Test
	@DisplayName("Replace corrupted catalog with correct one")
	void shouldReplaceCorruptedCatalogWithCorrectOne() {
		this.evita.defineCatalog(TEST_CATALOG + "_1")
		          .updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			TEST_CATALOG + "_1",
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
				);
			}
		);

		this.evita.defineCatalog(TEST_CATALOG + "_2")
		          .updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			TEST_CATALOG + "_2",
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
				);
			}
		);

		this.evita.close();

		// damage the TEST_CATALOG_1 contents
		try {
			final Path productCollectionFile = getEvitaTestDirectory().resolve(
				TEST_CATALOG + "_1" + File.separator + Entities.PRODUCT.toLowerCase() + "-1_0" + CatalogPersistenceService.ENTITY_COLLECTION_FILE_SUFFIX);
			Files.write(productCollectionFile, "Mangled content!".getBytes(StandardCharsets.UTF_8));
		} catch (Exception ex) {
			fail(ex);
		}

		this.evita = new Evita(
			getEvitaConfiguration()
		);

		final PortManager portManager = getPortManager();
		final String dataSetName = "evitaTest";
		final int[] ports = portManager.allocatePorts(dataSetName, 3);
		try {
			try (ExternalApiServer externalApiServer = new ExternalApiServer(
				this.evita,
				ApiOptions.builder()
				          .certificate(
					          CertificateOptions.builder()
					                            .folderPath(getEvitaTestDirectory() + "-certificates")
					                            .build()
				          )
				          .enable(GraphQLProvider.CODE, new GraphQLOptions(":" + ports[0]))
				          .enable(GrpcProvider.CODE, new GrpcOptions(":" + ports[1]))
				          .enable(RestProvider.CODE, new RestOptions(":" + ports[2]))
				          .build()
			)) {
				externalApiServer.start();
			}

			final Set<String> catalogNames = this.evita.getCatalogNames();
			assertEquals(3, catalogNames.size());

			assertThrows(
				CatalogCorruptedException.class,
				() -> this.evita.updateCatalog(
					TEST_CATALOG + "_1",
					session -> {
						session.getAllEntityTypes();
					}
				)
			);

			// but allow creating new catalog
			this.evita.defineCatalog(TEST_CATALOG + "_3")
			          .updateViaNewSession(this.evita);

			assertTrue(this.evita.getCatalogNames().contains(TEST_CATALOG + "_3"));
			this.evita.replaceCatalog(TEST_CATALOG + "_3", TEST_CATALOG + "_1");

			final Set<String> catalogNamesAgain = this.evita.getCatalogNames();
			assertEquals(3, catalogNamesAgain.size());

			// exception should not be thrown again
			this.evita.updateCatalog(
				TEST_CATALOG + "_1",
				session -> {
					session.getAllEntityTypes();
				}
			);

		} finally {
			portManager.releasePorts(dataSetName);
		}
	}

	/**
	 * Tests that fetching entities that are not yet known is handled properly.
	 *
	 * The test verifies that:
	 * - References to entities that don't exist yet are properly handled
	 * - When fetching entity with references, non-existent referenced entities are properly marked
	 * - When not fetching referenced entity bodies, all references are returned regardless of existence
	 */
	@Test
	@DisplayName("Handle fetching of not yet known entities")
	void shouldProperlyHandleFetchingOfNotYetKnownEntities() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.BRAND)
				       .withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable())
				       .updateVia(session);

				session.defineEntitySchema(Entities.PRODUCT)
				       .withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::filterable)
				       .withReferenceToEntity(Entities.BRAND, Entities.BRAND, Cardinality.ONE_OR_MORE)
				       .withReferenceTo(Entities.PARAMETER, Entities.PARAMETER, Cardinality.ONE_OR_MORE)
				       .updateVia(session);

				session.createNewEntity(Entities.BRAND, 1)
				       .setAttribute(ATTRIBUTE_NAME, "Siemens")
				       .upsertVia(session);

				session.createNewEntity(Entities.PRODUCT, 1)
				       .setAttribute(ATTRIBUTE_NAME, "Mixer")
				       .setReference(Entities.BRAND, 1)
				       .setReference(Entities.BRAND, 2)
				       .setReference(Entities.PARAMETER, 3)
				       .upsertVia(session);

				final SealedEntity fullEntity = session.getEntity(
					                                       Entities.PRODUCT, 1,
					                                       entityFetchAllContentAnd(
						                                       referenceContent(Entities.BRAND, entityFetchAll()),
						                                       referenceContent(Entities.PARAMETER, entityFetchAll())
					                                       )
				                                       )
				                                       .orElseThrow();

				// we get only single brand because when brand with PK=2 was fetched it was not found, yet it should
				// be present since entity maps to evita managed entity
				assertEquals(2, fullEntity.getReferences(Entities.BRAND).size());
				assertEquals(1, fullEntity.getReferences(Entities.PARAMETER).size());

				assertTrue(fullEntity.getReference(Entities.BRAND, 1).orElseThrow().getReferencedEntity().isPresent());
				assertFalse(fullEntity.getReference(Entities.BRAND, 2).orElseThrow().getReferencedEntity().isPresent());

				final SealedEntity shortEntity = session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
				                                        .orElseThrow();

				// we get both brands because their bodies were not fetched and we had no chance to find out that
				// the brand with PK=2 is not (yet) present in the evita storage
				assertEquals(2, shortEntity.getReferences(Entities.BRAND).size());
				assertEquals(1, shortEntity.getReferences(Entities.PARAMETER).size());
			}
		);
	}

	/**
	 * Tests that groups with non-matching locales are not returned in query results.
	 *
	 * The test verifies that:
	 * - When querying with a specific locale, only groups matching that locale are returned
	 * - References to groups are properly handled when locales don't match
	 * - Both entity and custom class representations handle locale filtering correctly
	 */
	@Test
	@DisplayName("Don't return groups with non-matching locale")
	void shouldNotReturnGroupOfNonMatchingLocale() {
		shouldCreateReflectedReference();
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PARAMETER_GROUP)
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized())
					.updateVia(session);

				session
					.defineEntitySchema(Entities.PARAMETER)
					.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
					.withReferenceToEntity(Entities.PARAMETER_GROUP, Entities.PARAMETER_GROUP, Cardinality.ZERO_OR_ONE)
					.updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
					.withReferenceToEntity(
						Entities.PARAMETER, Entities.PARAMETER, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.withGroupTypeRelatedToEntity(Entities.PARAMETER_GROUP)
					)
					.updateVia(session);

				session.createNewEntity(Entities.PARAMETER_GROUP, 1)
				       .setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Group")
				       .upsertVia(session);

				session.createNewEntity(Entities.PARAMETER, 1)
				       .setAttribute(ATTRIBUTE_NAME, LOCALE_CZ, "Parametr")
				       .setReference(Entities.PARAMETER_GROUP, 1)
				       .upsertVia(session);

				session.createNewEntity(Entities.PRODUCT, 1)
				       .setAttribute(ATTRIBUTE_NAME, LOCALE_CZ, "Produkt")
				       .setReference(
						   Entities.PARAMETER,
						   1,
						   whichIs -> whichIs.setGroup(1)
				       )
				       .upsertVia(session);

				final SealedEntity product = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(1),
							entityLocaleEquals(LOCALE_CZ)
						),
						require(
							entityFetch(
								entityFetchAllContentAnd(
									referenceContent(Entities.PARAMETER, entityFetchAll(), entityGroupFetchAll())
								)
							)
						)
					)
				).orElseThrow();

				assertNotNull(product.getReference(Entities.PARAMETER, 1).orElse(null));
				assertNotNull(product.getReference(Entities.PARAMETER, 1).orElseThrow().getGroup().orElse(null));
				assertNull(product.getReference(Entities.PARAMETER, 1).orElseThrow().getGroupEntity().orElse(null));

				final ProductInterface productAsCustomClass = session.queryOne(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(1),
							entityLocaleEquals(LOCALE_CZ)
						),
						require(
							entityFetch(
								entityFetchAllContentAnd(
									referenceContent(Entities.PARAMETER, entityFetchAll(), entityGroupFetchAll())
								)
							)
						)
					),
					ProductInterface.class
				).orElseThrow();

				final ProductParameterInterface parameter = productAsCustomClass.getParameterById(1);
				assertNotNull(parameter);
				assertNotNull(parameter.getParameterGroup());
				assertNull(parameter.getParameterGroupEntity());
			}
		);
	}

	/**
	 * Tests that globally unique attributes are correctly localized.
	 *
	 * The test verifies that:
	 * - Globally unique attributes can be localized
	 * - Uniqueness constraint is enforced within the same locale
	 * - Querying by attribute value works with proper locale handling
	 * - Querying with a non-matching locale returns no results
	 */
	@Test
	@DisplayName("Correctly localize globally unique attributes")
	void shouldCorrectlyLocalizeGloballyUniqueAttribute() {
		this.evita.defineCatalog(TEST_CATALOG)
		          .withAttribute(ATTRIBUTE_URL, String.class, whichIs -> whichIs.localized().uniqueGlobally())
		          .updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withGlobalAttribute(ATTRIBUTE_URL)
					.updateVia(session);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
					       .setAttribute(ATTRIBUTE_URL, Locale.ENGLISH, "/theProduct")
				);
			}
		);

		assertThrows(
			UniqueValueViolationException.class,
			() -> this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 2)
						       .setAttribute(ATTRIBUTE_URL, Locale.ENGLISH, "/theProduct")
					);
				}
			)
		);

		assertEquals(
			new EntityReference(Entities.PRODUCT, 1),
			this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.queryOne(
						query(
							collection(Entities.PRODUCT),
							filterBy(attributeEquals(ATTRIBUTE_URL, "/theProduct"))
						),
						EntityReference.class
					).orElseThrow();
				}
			)
		);

		assertEquals(
			new EntityReference(Entities.PRODUCT, 1),
			this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.queryOne(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								attributeEquals(ATTRIBUTE_URL, "/theProduct"),
								entityLocaleEquals(Locale.ENGLISH)
							)
						),
						EntityReference.class
					).orElseThrow();
				}
			)
		);

		assertNull(
			this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.queryOne(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								attributeEquals(ATTRIBUTE_URL, "/theProduct"),
								entityLocaleEquals(Locale.FRENCH)
							)
						),
						EntityReference.class
					).orElse(null);
				}
			)
		);
	}

	/**
	 * Tests the backup and restore functionality for catalogs.
	 *
	 * The test verifies that:
	 * - A catalog can be backed up to a file
	 * - The backup file is created correctly
	 * - A new catalog can be restored from the backup file
	 * - The restored catalog has the same content as the original
	 */
	@Test
	@DisplayName("Create backup and restore catalog")
	void shouldCreateBackupAndRestoreCatalog() throws IOException, ExecutionException, InterruptedException {
		setupCatalogWithProductAndCategory();

		final CompletableFuture<FileForFetch> backupPathFuture = this.evita.management().backupCatalog(
			TEST_CATALOG, null, null, true);
		final Path backupPath = backupPathFuture.join().path(this.evita.getConfiguration().storage().exportDirectory());

		assertTrue(backupPath.toFile().exists());

		try (final BufferedInputStream inputStream = new BufferedInputStream(
			new FileInputStream(backupPath.toFile()))) {
			final CompletableFuture<Void> future = this.evita.management().restoreCatalog(
				TEST_CATALOG + "_restored",
				Files.size(backupPath),
				inputStream
			).getFutureResult();

			// wait for the restore to finish
			future.get();
		}

		// we need to activate the restored catalog first
		this.evita.activateCatalog(TEST_CATALOG + "_restored");

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// compare contents of both catalogs
				this.evita.queryCatalog(
					TEST_CATALOG + "_restored",
					session2 -> {
						final Set<String> allEntityTypes1 = session.getAllEntityTypes();
						final Set<String> allEntityTypes2 = session2.getAllEntityTypes();

						assertEquals(allEntityTypes1, allEntityTypes2);

						for (String entityType : allEntityTypes1) {
							session.queryList(
								query(
									collection(entityType),
									require(entityFetchAll(), page(1, 100))
								),
								SealedEntity.class
							).forEach(entity -> {
								final SealedEntity entity2 = session2.getEntity(
									entityType, entity.getPrimaryKey(), entityFetchAllContent()
								).orElseThrow();
								assertEquals(entity, entity2);
							});
						}
					}
				);
			}
		);
	}

	/**
	 * Tests the backup and restore functionality for transactional catalogs.
	 *
	 * The test verifies that:
	 * - A transactional catalog can be backed up to a file
	 * - The backup file is created correctly
	 * - A new catalog can be restored from the backup file
	 * - The restored catalog has the same content as the original
	 * - Both the original and restored catalogs can be modified after the restore operation
	 */
	@Test
	@DisplayName("Create backup and restore transactional catalog")
	void shouldCreateBackupAndRestoreTransactionalCatalog() throws IOException, ExecutionException, InterruptedException {
		setupCatalogWithProductAndCategory();

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.goLiveAndClose();
			}
		);

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
				       .orElseThrow()
				       .openForWrite()
				       .setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Changed name")
				       .upsertVia(session);
			}
		);

		final EvitaManagement management = this.evita.management();
		final CompletableFuture<FileForFetch> backupPathFuture = management.backupCatalog(
			TEST_CATALOG, null, null, true);
		final Path backupPath = backupPathFuture.join().path(this.evita.getConfiguration().storage().exportDirectory());

		assertTrue(backupPath.toFile().exists());

		try (final BufferedInputStream inputStream = new BufferedInputStream(
			new FileInputStream(backupPath.toFile()))) {
			final CompletableFuture<Void> future = management.restoreCatalog(
				TEST_CATALOG + "_restored",
				Files.size(backupPath),
				inputStream
			).getFutureResult();

			// wait for the restore to finish
			future.get();
		}

		// we need to activate the restored catalog first
		this.evita.activateCatalog(TEST_CATALOG + "_restored");

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// compare contents of both catalogs
				this.evita.queryCatalog(
					TEST_CATALOG + "_restored",
					session2 -> {
						final Set<String> allEntityTypes1 = session.getAllEntityTypes();
						final Set<String> allEntityTypes2 = session2.getAllEntityTypes();

						assertEquals(allEntityTypes1, allEntityTypes2);

						for (String entityType : allEntityTypes1) {
							session.queryList(
								query(
									collection(entityType),
									require(entityFetchAll(), page(1, 100))
								),
								SealedEntity.class
							).forEach(entity -> {
								final SealedEntity entity2 = session2.getEntity(
									entityType, entity.getPrimaryKey(), entityFetchAllContent()
								).orElseThrow();
								assertEquals(entity, entity2);
							});
						}
					}
				);
			}
		);

		// verify we can write to the original catalog again
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
				       .orElseThrow()
				       .openForWrite()
				       .setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Changed name again")
				       .upsertVia(session);
			}
		);

		// and to the restored one as well
		this.evita.updateCatalog(
			TEST_CATALOG + "_restored",
			session -> {
				session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
				       .orElseThrow()
				       .openForWrite()
				       .setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Changed name again")
				       .upsertVia(session);
			}
		);
	}

	/**
	 * Tests the task management functionality.
	 *
	 * The test verifies that:
	 * - Tasks can be created and executed
	 * - Tasks can be listed and their status can be retrieved
	 * - Tasks can be cancelled
	 * - Exported files can be listed, fetched, and deleted
	 */
	@Test
	@DisplayName("List and cancel tasks")
	void shouldListAndCancelTasks() {
		final int numberOfTasks = 20;

		this.evita.updateCatalog(
			TEST_CATALOG, session -> {
				session.goLiveAndClose();
			}
		);

		final EvitaManagement management = this.evita.management();
		ExecutorService executorService = Executors.newFixedThreadPool(numberOfTasks);

		// Step 2: Generate backup tasks using the custom executor
		final List<CompletableFuture<CompletableFuture<FileForFetch>>> backupTasks = Stream
			.generate(
				() -> CompletableFuture.supplyAsync(
					() -> management.backupCatalog(TEST_CATALOG, null, null, true),
					executorService
				)
			)
			.limit(numberOfTasks)
			.toList();

		// Optional: Wait for all tasks to complete
		CompletableFuture.allOf(backupTasks.toArray(new CompletableFuture[0])).join();
		executorService.shutdown();

		management.listTaskStatuses(1, numberOfTasks, null);

		// cancel 7 of them immediately
		final List<Boolean> cancellationResult = Stream
			.concat(
				management.listTaskStatuses(1, 1, null)
				          .getData()
				          .stream()
				          .map(it -> management.cancelTask(it.taskId())),
				backupTasks.subList(3, numberOfTasks - 1)
				           .stream()
				           .map(task -> task.getNow(null).cancel(true))
			)
			.toList();

		try {
			// wait for all task to complete
			CompletableFuture.allOf(
				backupTasks.stream().map(it -> it.getNow(null)).toArray(CompletableFuture[]::new)
			).get(3, TimeUnit.MINUTES);
		} catch (ExecutionException ignored) {
			// if tasks were cancelled, they will throw exception
		} catch (InterruptedException | TimeoutException e) {
			fail(e);
		}

		final PaginatedList<TaskStatus<?, ?>> taskStatuses = management.listTaskStatuses(1, numberOfTasks, null);
		assertEquals(numberOfTasks, taskStatuses.getTotalRecordCount());
		final int cancelled = cancellationResult.stream().mapToInt(b -> b ? 1 : 0).sum();
		// there is small chance, that cancelled task will finish after all (if it was cancelled in terminal stage)
		final long finishedTasks = taskStatuses.getData().stream().filter(
			task -> task.simplifiedState() == TaskSimplifiedState.FINISHED).count();
		assertTrue(Math.abs((backupTasks.size() - cancelled) - finishedTasks) < numberOfTasks * 0.1);
		assertEquals(
			numberOfTasks - finishedTasks, taskStatuses.getData()
			                                           .stream()
			                                           .filter(
				                                           task -> task.simplifiedState() == TaskSimplifiedState.FAILED)
			                                           .count()
		);

		// fetch all tasks by their ids
		management.getTaskStatuses(
			taskStatuses.getData().stream().map(TaskStatus::taskId).toArray(UUID[]::new)
		).forEach(Assertions::assertNotNull);

		// fetch tasks individually
		taskStatuses.getData().forEach(task -> assertNotNull(management.getTaskStatus(task.taskId())));

		// list exported files
		final PaginatedList<FileForFetch> exportedFiles = management.listFilesToFetch(1, numberOfTasks, Set.of());
		// some task might have finished even if cancelled (if they were cancelled in terminal phase)
		assertTrue(exportedFiles.getTotalRecordCount() >= backupTasks.size() - cancelled);
		exportedFiles.getData().forEach(file -> assertTrue(file.totalSizeInBytes() > 0));

		// get all files by their ids
		exportedFiles.getData().forEach(file -> assertNotNull(management.getFileToFetch(file.fileId())));

		// fetch all of them
		exportedFiles.getData().forEach(
			file -> {
				try (final InputStream inputStream = management.fetchFile(file.fileId())) {
					final Path tempFile = Files.createTempFile(String.valueOf(file.fileId()), ".zip");
					Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
					assertTrue(tempFile.toFile().exists());
					assertEquals(file.totalSizeInBytes(), Files.size(tempFile));
					Files.delete(tempFile);
				} catch (IOException e) {
					fail(e);
				}
			});

		// delete them
		final Set<UUID> deletedFiles = CollectionUtils.createHashSet(exportedFiles.getData().size());
		exportedFiles.getData()
		             .forEach(file -> {
			             management.deleteFile(file.fileId());
			             deletedFiles.add(file.fileId());
		             });

		// list them again and there should be none of them
		final PaginatedList<FileForFetch> exportedFilesAfterDeletion = management.listFilesToFetch(1, numberOfTasks, Set.of());
		assertTrue(exportedFilesAfterDeletion.getData().stream().noneMatch(file -> deletedFiles.contains(file.fileId())));
	}

	/**
	 * Helper method to test catalog renaming functionality.
	 *
	 * This method:
	 * 1. Creates a test catalog with brand entities
	 * 2. Renames the catalog
	 * 3. Verifies the catalog was renamed correctly
	 * 4. Restarts Evita to ensure persistence
	 * 5. Verifies the renamed catalog is still accessible
	 *
	 * @param catalogState whether the catalog should be in ALIVE state or not before renaming
	 */
	private void doRenameCatalog(@Nonnull CatalogState catalogState) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.BRAND);

				session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 2));
				assertEquals(2, session.getEntityCollectionSize(Entities.BRAND));
			}
		);

		final String renamedCatalogName = TEST_CATALOG + "_renamed";
		final AtomicInteger versionBeforeRename = new AtomicInteger();
		if (catalogState == CatalogState.ALIVE) {
			this.evita.updateCatalog(
				TEST_CATALOG, session -> {
					versionBeforeRename.set(session.getCatalogSchema().version());
					session.goLiveAndClose();
				}
			);
		} else {
			this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					versionBeforeRename.set(session.getCatalogSchema().version());
				}
			);
		}

		this.evita.renameCatalog(TEST_CATALOG, renamedCatalogName);

		assertFalse(this.evita.getCatalogNames().contains(TEST_CATALOG));
		assertTrue(this.evita.getCatalogNames().contains(renamedCatalogName));

		this.evita.queryCatalog(
			renamedCatalogName,
			session -> {
				assertEquals(renamedCatalogName, session.getCatalogSchema().getName());
				assertEquals(2, session.getEntityCollectionSize(Entities.BRAND));
				assertEquals(versionBeforeRename.get() + 1, session.getCatalogSchema().version());
				return null;
			}
		);

		this.evita.close();

		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.waitUntilFullyInitialized();

		this.evita.queryCatalog(
			renamedCatalogName,
			session -> {
				assertEquals(renamedCatalogName, session.getCatalogSchema().getName());
				assertEquals(2, session.getEntityCollectionSize(Entities.BRAND));
				return null;
			}
		);
	}

	/**
	 * Helper method to test catalog replacement functionality.
	 *
	 * This method:
	 * 1. Creates a test catalog with brand entities
	 * 2. Creates a temporary catalog with product entities
	 * 3. Replaces the original catalog with the temporary one
	 * 4. Verifies the replacement was successful
	 * 5. Restarts Evita to ensure persistence
	 * 6. Verifies the replaced catalog is still accessible with correct data
	 *
	 * @param catalogState whether the catalog should be in ALIVE state or not before replacement
	 */
	private void doReplaceCatalog(@Nonnull CatalogState catalogState) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.BRAND);

				session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 2));
				assertEquals(2, session.getEntityCollectionSize(Entities.BRAND));
			}
		);

		final String temporaryCatalogName = TEST_CATALOG + "_tmp";
		this.evita.defineCatalog(temporaryCatalogName);
		this.evita.updateCatalog(
			temporaryCatalogName,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 1));
				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 2));
				assertEquals(2, session.getEntityCollectionSize(Entities.PRODUCT));
			}
		);

		final AtomicInteger versionBeforeRename = new AtomicInteger();
		if (catalogState == CatalogState.ALIVE) {
			this.evita.updateCatalog(
				temporaryCatalogName, session -> {
					versionBeforeRename.set(session.getCatalogSchema().version());
					session.goLiveAndClose();
				}
			);
		} else {
			this.evita.queryCatalog(
				temporaryCatalogName,
				session -> {
					versionBeforeRename.set(session.getCatalogSchema().version());
				}
			);
		}

		this.evita.replaceCatalog(temporaryCatalogName, TEST_CATALOG);

		assertFalse(this.evita.getCatalogNames().contains(temporaryCatalogName));
		assertTrue(this.evita.getCatalogNames().contains(TEST_CATALOG));

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 3));
			}
		);

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertEquals(TEST_CATALOG, session.getCatalogSchema().getName());
				assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.BRAND));
				assertEquals(3, session.getEntityCollectionSize(Entities.PRODUCT));
				assertEquals(versionBeforeRename.get() + 1, session.getCatalogSchema().version());

				for (int i = 1; i <= 3; i++) {
					assertNotNull(session.getEntity(Entities.PRODUCT, i));
				}

				return null;
			}
		);

		this.evita.close();

		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.waitUntilFullyInitialized();

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 4));
			}
		);

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertEquals(TEST_CATALOG, session.getCatalogSchema().getName());
				assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.BRAND));
				assertEquals(4, session.getEntityCollectionSize(Entities.PRODUCT));

				for (int i = 1; i <= 4; i++) {
					assertNotNull(session.getEntity(Entities.PRODUCT, i));
				}

				return null;
			}
		);
	}

	/**
	 * Helper method to test catalog replacement functionality.
	 *
	 * This method:
	 * 1. Creates a test catalog with brand entities
	 * 2. Creates a temporary catalog with product entities
	 * 3. Replaces the original catalog with the temporary one
	 * 4. Verifies the replacement was successful
	 * 5. Restarts Evita to ensure persistence
	 * 6. Verifies the replaced catalog is still accessible with correct data
	 *
	 * @param catalogState whether the catalog should be in ALIVE state or not before replacement
	 */
	private void doReplaceNonExistingCatalog(@Nonnull CatalogState catalogState) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 1));
			}
		);

		final String temporaryCatalogName = TEST_CATALOG + "_tmp";
		final AtomicInteger versionBeforeRename = new AtomicInteger();
		if (catalogState == CatalogState.ALIVE) {
			this.evita.updateCatalog(
				TEST_CATALOG, session -> {
					versionBeforeRename.set(session.getCatalogSchema().version());
					session.goLiveAndClose();
				}
			);
		} else {
			this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					versionBeforeRename.set(session.getCatalogSchema().version());
				}
			);
		}

		this.evita.replaceCatalog(TEST_CATALOG, temporaryCatalogName);

		assertTrue(this.evita.getCatalogNames().contains(temporaryCatalogName));
		assertFalse(this.evita.getCatalogNames().contains(TEST_CATALOG));

		this.evita.updateCatalog(
			temporaryCatalogName,
			session -> {
				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 2));
			}
		);

		this.evita.queryCatalog(
			temporaryCatalogName,
			session -> {
				assertEquals(temporaryCatalogName, session.getCatalogSchema().getName());
				assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.BRAND));
				assertEquals(2, session.getEntityCollectionSize(Entities.PRODUCT));
				assertEquals(versionBeforeRename.get() + 1, session.getCatalogSchema().version());

				for (int i = 1; i <= 2; i++) {
					assertNotNull(session.getEntity(Entities.PRODUCT, i));
				}

				return null;
			}
		);

		this.evita.close();

		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.waitUntilFullyInitialized();

		this.evita.updateCatalog(
			temporaryCatalogName,
			session -> {
				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 3));
			}
		);

		this.evita.queryCatalog(
			temporaryCatalogName,
			session -> {
				assertEquals(temporaryCatalogName, session.getCatalogSchema().getName());
				assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.BRAND));
				assertEquals(3, session.getEntityCollectionSize(Entities.PRODUCT));

				for (int i = 1; i <= 3; i++) {
					assertNotNull(session.getEntity(Entities.PRODUCT, i));
				}

				return null;
			}
		);
	}

	private void setupCatalogWithProductAndCategory() {
		this.evita.updateCatalog(
			TEST_CATALOG, session -> {
				session.defineEntitySchema(Entities.PRODUCT);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.verifySchemaButCreateOnTheFly()
					.updateVia(session);

				final EntityBuilder product = session
					.createNewEntity(Entities.PRODUCT, 1)
					.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE)
					.setPrice(
						1, PRICE_LIST_BASIC, CURRENCY_CZK, BigDecimal.ONE,
						BigDecimal.ZERO, BigDecimal.ONE, true
					)
					.setPrice(
						2, PRICE_LIST_BASIC, CURRENCY_EUR, BigDecimal.ONE,
						BigDecimal.ZERO, BigDecimal.ONE, true
					)
					.setPrice(
						3, PRICE_LIST_VIP, CURRENCY_CZK, BigDecimal.ONE,
						BigDecimal.ZERO, BigDecimal.ONE, true
					)
					.setPrice(
						4, PRICE_LIST_VIP, CURRENCY_EUR, BigDecimal.ONE,
						BigDecimal.ZERO, BigDecimal.ONE, true
					)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "The product");

				session.upsertEntity(product);

				session.defineEntitySchema(Entities.CATEGORY);

				session
					.defineEntitySchema(Entities.CATEGORY)
					.withHierarchy()
					.updateVia(session);

				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1));
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 2));
			}
		);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return getEvitaConfiguration(-1);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration(int inactivityTimeoutInSeconds) {
		return EvitaConfiguration
			.builder()
			.server(
				ServerOptions
					.builder()
					.serviceThreadPool(
						ThreadPoolOptions
							.serviceThreadPoolBuilder()
							.minThreadCount(1)
							.maxThreadCount(1)
							.queueSize(10_000)
							.build()
					)
					.closeSessionsAfterSecondsOfInactivity(inactivityTimeoutInSeconds)
					.build()
			)
			.storage(
				StorageOptions
					.builder()
					.storageDirectory(getEvitaTestDirectory())
					.exportDirectory(getTestDirectory().resolve(DIR_EVITA_TEST_EXPORT))
					.timeTravelEnabled(false)
					.maxOpenedReadHandles(100)
					.build()
			)
			.build();
	}

	/**
	 * Helper method to create a session and define an entity schema.
	 *
	 * This method creates a read-write session and defines an entity schema for the specified entity type.
	 * It's useful for reducing code duplication in test methods that need to set up a basic entity schema.
	 *
	 * @param entityType the type of entity to define
	 * @return the created session
	 */
	@Nonnull
	private EvitaSessionContract createSessionAndDefineEntitySchema(@Nonnull String entityType) {
		final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG);
		session.defineEntitySchema(entityType)
		       .withoutGeneratedPrimaryKey()
		       .updateVia(session);
		return session;
	}

	/**
	 * Helper method to create a session and define an entity schema with a reference.
	 *
	 * This method creates a read-write session and defines an entity schema for the specified entity type
	 * with a reference to another entity type. It's useful for reducing code duplication in test methods
	 * that need to set up entity schemas with references.
	 *
	 * @param entityType    the type of entity to define
	 * @param referenceType the type of entity to reference
	 * @param referenceName the name of the reference
	 * @param indexed       whether the reference should be indexed
	 * @return the created session
	 */
	@Nonnull
	private EvitaSessionContract createSessionAndDefineEntitySchemaWithReference(
		@Nonnull String entityType,
		@Nonnull String referenceType,
		@Nonnull String referenceName,
		boolean indexed
	) {
		final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG);
		final EntitySchemaBuilder schemaBuilder = session
			.defineEntitySchema(entityType)
			.withoutGeneratedPrimaryKey();

		if (indexed) {
			schemaBuilder.withReferenceTo(
				referenceType,
				referenceName,
				Cardinality.ZERO_OR_ONE,
				it -> it.indexed()
			);
		} else {
			schemaBuilder.withReferenceTo(referenceType, referenceName, Cardinality.ZERO_OR_ONE);
		}

		schemaBuilder.updateVia(session);
		return session;
	}

	/**
	 * Helper to define multiple catalogs.
	 *
	 * @param catalogs catalog names to define
	 */
	private void defineCatalogs(@Nonnull String... catalogs) {
		for (final String catalog : catalogs) {
			this.evita.defineCatalog(catalog);
		}
	}

	/**
	 * Prepares catalog with BRAND, CATEGORY (hierarchy) and PRODUCT schema, seeds refs
	 * and inserts the requested number of products.
	 *
	 * PRODUCT has attributes `code` and `name` (both filterable + sortable) and references to BRAND and CATEGORY
	 * (both indexed + faceted).
	 *
	 * @param catalog the catalog name
	 * @param count   number of products to insert
	 */
	private void prepareCatalogWithProductSchemaAndData(@Nonnull String catalog, int count) {
		this.evita.updateCatalog(
			catalog,
			session -> {
				// define referenced entities
				session.defineEntitySchema(Entities.BRAND).updateVia(session);
				session.defineEntitySchema(Entities.CATEGORY).withHierarchy().updateVia(session);
				// define product schema with references and indexed attributes
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute("code", String.class, whichIs -> whichIs.filterable().sortable())
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable().sortable())
					.withReferenceToEntity(
						Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE, it -> it.indexed().faceted()
					)
					.withReferenceToEntity(
						Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE, it -> it.indexed().faceted()
					)
					.updateVia(session);

				// seed referenced entities
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 2));
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1));
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 2));

				// insert entities
				final int cnt = count;
				for (int i = 1; i <= cnt; i++) {
					final EntityBuilder builder = session.createNewEntity(Entities.PRODUCT, i)
						.setAttribute("code", "code-" + i)
						.setAttribute(ATTRIBUTE_NAME, "name-" + i)
						.setReference(Entities.BRAND, (i % 2) + 1)
						.setReference(Entities.CATEGORY, (i % 2) + 1);
					session.upsertEntity(builder);
				}
			}
		);
	}

	/**
	 * Asserts catalogs are WARMING_UP then deactivates them and asserts INACTIVE.
	 *
	 * @param catalogs catalog names
	 */
	private void assertWarmingUpThenDeactivate(@Nonnull String... catalogs) {
		for (final String catalog : catalogs) {
			assertEquals(CatalogState.WARMING_UP, this.evita.getCatalogState(catalog).orElseThrow());
			this.evita.deactivateCatalog(catalog);
			assertEquals(CatalogState.INACTIVE, this.evita.getCatalogState(catalog).orElseThrow());
		}
	}

	/**
	 * Activates the same catalog in two parallel tasks and expects a conflict.
	 *
	 * @param catalog catalog name
	 */
	private void activateCatalogTwiceInParallelExpectingConflict(@Nonnull String catalog) {
		try {
			final CompletableFuture<Void> f1 = CompletableFuture.runAsync(
				() -> this.evita
					.applyMutation(new SetCatalogStateMutation(catalog, true))
					.onCompletion()
					.toCompletableFuture()
					.join()
			);
			final CompletableFuture<Void> f2 = CompletableFuture.runAsync(
				() -> this.evita
					.applyMutation(new SetCatalogStateMutation(catalog, true))
					.onCompletion()
					.toCompletableFuture()
					.join()
			);

			CompletableFuture.allOf(f1, f2).join();
		} catch (CompletionException ex) {
			assertInstanceOf(ConflictingEngineMutationException.class, ex.getCause());
		} catch (ConflictingEngineMutationException ex) {
			// expected exception, as we are trying to activate the same catalog in two threads
		}
	}

	/**
	 * Activates provided catalogs in parallel and asserts they end up in active state.
	 *
	 * @param catalogs catalogs to activate
	 */
	private void activateCatalogsInParallelAndAssertActive(@Nonnull String... catalogs) {
		final CompletableFuture<?>[] futures = new CompletableFuture<?>[catalogs.length];
		for (int i = 0; i < catalogs.length; i++) {
			final String catalog = catalogs[i];
			futures[i] = CompletableFuture.runAsync(
				() -> this.evita
					.applyMutation(new SetCatalogStateMutation(catalog, true))
					.onCompletion()
					.toCompletableFuture()
					.join()
			);
		}
		CompletableFuture.allOf(futures).join();
		for (final String catalog : catalogs) {
			assertEquals(CatalogState.WARMING_UP, this.evita.getCatalogState(catalog).orElseThrow());
		}
	}

	/**
	 * Tests the functionality of duplicating an active catalog.
	 *
	 * This test verifies that:
	 * - An active catalog can be successfully duplicated
	 * - The duplicated catalog is initially in INACTIVE state
	 * - The duplicated catalog can be activated
	 * - Data can be queried from the duplicated catalog after activation
	 */
	@Test
	@DisplayName("Should duplicate active catalog and verify its functionality")
	void shouldDuplicateActiveCatalogAndVerifyFunctionality() {
		// Set up the original catalog with schema and data
		setupCatalogWithProductAndCategory();

		// Make the catalog alive (active) before duplication
		this.evita.makeCatalogAlive(TEST_CATALOG);

		// Wait a moment to ensure catalog is fully initialized
		try {
			Thread.sleep(100);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		final String duplicatedCatalogName = TEST_CATALOG + "_duplicated";

		// Duplicate the catalog using the public API
		final DuplicateCatalogMutation duplicateMutation = new DuplicateCatalogMutation(TEST_CATALOG, duplicatedCatalogName);
		this.evita.applyMutation(duplicateMutation, progress -> {
			// Progress observer - can be used for monitoring duplication progress
		}).onCompletion().toCompletableFuture().join();

		// Verify that the duplicated catalog exists and is in INACTIVE state
		assertTrue(this.evita.getCatalogNames().contains(duplicatedCatalogName));
		assertEquals(CatalogState.INACTIVE, this.evita.getCatalogState(duplicatedCatalogName).orElse(null));

		// Activate the duplicated catalog
		this.evita.activateCatalog(duplicatedCatalogName);

		// Verify that the duplicated catalog is now active
		assertEquals(CatalogState.ALIVE, this.evita.getCatalogState(duplicatedCatalogName).orElse(null));

		// Query data in the duplicated catalog to verify it contains the same data as the original
		this.evita.queryCatalog(
			duplicatedCatalogName,
			session -> {
				// Verify that the product entity exists with the expected data
				final SealedEntity product = session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow(() -> new AssertionError("Product entity should exist in duplicated catalog"));

				assertEquals("The product", product.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));

				// Verify that category entities exist
				final SealedEntity category1 = session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent())
					.orElseThrow(() -> new AssertionError("Category 1 should exist in duplicated catalog"));
				final SealedEntity category2 = session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent())
					.orElseThrow(() -> new AssertionError("Category 2 should exist in duplicated catalog"));

				assertNotNull(category1);
				assertNotNull(category2);

				// Verify that we can query entities
				final List<SealedEntity> products = session.queryList(
					query(
						collection(Entities.PRODUCT),
						require(entityFetchAll())
					),
					SealedEntity.class
				);
				assertEquals(1, products.size());

				final List<SealedEntity> categories = session.queryList(
					query(
						collection(Entities.CATEGORY),
						require(entityFetchAll())
					),
					SealedEntity.class
				);
				assertEquals(2, categories.size());
			}
		);

		// Clean up - delete the duplicated catalog
		this.evita.deleteCatalogIfExists(duplicatedCatalogName);
	}

	@Test
	@DisplayName("Should fail concurrent engine mutations on a single catalog")
	void shouldFailToExecuteTwoEngineLevelMutationsOnSingleCatalogInParallel() {
		final String catalog = TEST_CATALOG + "_concurrent_engine_single";
		// create and prepare catalog with data
		defineCatalogs(catalog);
		prepareCatalogWithProductSchemaAndData(catalog, 1_000);
		// ensure alive then deactivate
		assertWarmingUpThenDeactivate(catalog);
		// reinstantiate with async executors
		this.evita = reinstantiateEvitaWithEnabledAsynchronousExecutors(this.evita);
		try {
			// activate twice in parallel and expect conflict
			activateCatalogTwiceInParallelExpectingConflict(catalog);
		} finally {
			// clean up
			this.evita.deleteCatalogIfExists(catalog);
		}
	}

	@Test
	@DisplayName("Should allow concurrent engine mutations on different catalogs")
	void shouldAllowTwoEngineMutationsOnDifferentCatalogsInParallel() {
		final String catalog1 = TEST_CATALOG + "_concurrent_engine_multi1";
		final String catalog2 = TEST_CATALOG + "_concurrent_engine_multi2";

		// create catalogs and prepare data
		defineCatalogs(catalog1, catalog2);
		prepareCatalogWithProductSchemaAndData(catalog1, 500);
		prepareCatalogWithProductSchemaAndData(catalog2, 500);

		// ensure both are alive (warming up) then deactivate them
		assertWarmingUpThenDeactivate(catalog1, catalog2);

		// reinstantiate with async executors
		this.evita = reinstantiateEvitaWithEnabledAsynchronousExecutors(this.evita);

		try {
			// activate both catalogs in parallel - should not conflict
			activateCatalogsInParallelAndAssertActive(catalog1, catalog2);
		} finally {
			// clean up
			this.evita.deleteCatalogIfExists(catalog1);
			this.evita.deleteCatalogIfExists(catalog2);
		}
	}

	/**
	 * Reinstantiates the Evita instance with asynchronous executors enabled, based on
	 * the configuration of the provided Evita instance. The provided Evita instance
	 * will be closed and a new instance will be created.
	 *
	 * @param evita The existing Evita instance to be reinitialized with asynchronous executors enabled.
	 */
	@Nonnull
	private static Evita reinstantiateEvitaWithEnabledAsynchronousExecutors(@Nonnull Evita evita) {
		final EvitaConfiguration formerConfiguration = evita.getConfiguration();
		evita.close();

		final ServerOptions formerServerOptions = formerConfiguration.server();
		final Evita reinstantiatedEvita = new Evita(
			new EvitaConfiguration(
				formerConfiguration.name(),
				new ServerOptions(
					formerServerOptions.requestThreadPool(),
					formerServerOptions.transactionThreadPool(),
					formerServerOptions.serviceThreadPool(),
					formerServerOptions.queryTimeoutInMilliseconds(),
					formerServerOptions.transactionTimeoutInMilliseconds(),
					formerServerOptions.closeSessionsAfterSecondsOfInactivity(),
					formerServerOptions.changeDataCapture(),
					formerServerOptions.trafficRecording(),
					formerServerOptions.readOnly(),
					formerServerOptions.quiet(),
					false
				),
				formerConfiguration.storage(),
				formerConfiguration.transaction(),
				formerConfiguration.cache()
			)
		);
		reinstantiatedEvita.waitUntilFullyInitialized();
		return reinstantiatedEvita;
	}

	@Nonnull
	private Path getEvitaTestDirectory() {
		return getTestDirectory().resolve(DIR_EVITA_TEST);
	}

}
