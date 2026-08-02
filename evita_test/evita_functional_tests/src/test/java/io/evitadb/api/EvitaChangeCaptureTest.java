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

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.cdc.DataSite;
import io.evitadb.api.requestResponse.cdc.SchemaSite;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.core.Evita;
import io.evitadb.dataType.ContainerType;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.CDC;

/**
 * This test contains various integration tests for {@link Evita}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
@Tag(CONTRACT)
@Tag(QUERY)
@Tag(CDC)
class EvitaChangeCaptureTest implements EvitaTestSupport {
	public static final String ATTRIBUTE_NAME = "name";
	public static final String ATTRIBUTE_URL = "url";
	public static final String PRICE_LIST_BASIC = "basic";
	public static final Currency CURRENCY_CZK = Currency.getInstance("CZK");
	public static final Currency CURRENCY_USD = Currency.getInstance("USD");
	private TestPaths paths;
	private Evita evita;

	private static void createSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(Entities.BRAND)
			.withoutGeneratedPrimaryKey()
			.withAttribute(ATTRIBUTE_NAME, String.class)
			.updateVia(session);

		session.defineEntitySchema(Entities.PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withAttribute(ATTRIBUTE_NAME, String.class)
			.withAttribute(ATTRIBUTE_URL, String.class)
			.withReferenceToEntity(Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE)
			.updateVia(session);
	}

	private static void createDataInSchema(@Nonnull EvitaSessionContract session) {
		session.createNewEntity(Entities.BRAND, 1)
			.setAttribute(ATTRIBUTE_NAME, "Brand 1")
			.upsertVia(session);
		session.createNewEntity(Entities.PRODUCT, 1)
			.setAttribute(ATTRIBUTE_NAME, "Product 1")
			.setAttribute(ATTRIBUTE_URL, "http://product1.com")
			.setReference(Entities.BRAND, 1)
			.upsertVia(session);
	}

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("EvitaChangeCaptureTest");
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	void shouldProvideNoCaptureInWarmUpStage() {
		try (final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG)) {
			createSchema(session);
			createDataInSchema(session);
		}

		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			assertTrue(
				session.getMutationsHistory(
						ChangeCatalogCaptureRequest.builder()
							.build()
					)
					.findFirst()
					.isEmpty()
			);
		}
	}

	@Test
	void shouldCaptureSchemaMutationsInAliveStage() {
		makeCatalogAliveAndCreateMutationSet();

		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			final List<ChangeCatalogCapture> reverseCaptures = session.getMutationsHistory(
				ChangeCatalogCaptureRequest.builder()
					.criteria(
						ChangeCatalogCaptureCriteria.builder()
							.schemaArea()
							.build()
					)
					.content(ChangeCaptureContent.BODY)
					.build()
			).toList();
			assertEquals(10, reverseCaptures.size());

			int index = Integer.MAX_VALUE;
			for (ChangeCatalogCapture reverseCapture : reverseCaptures) {
				assertTrue(
					reverseCapture.index() <= index,
					"Index " + reverseCapture.index() + " is not greater than " + index + " for " + reverseCapture
				);
				assertEquals(CaptureArea.SCHEMA, reverseCapture.area());
				index = reverseCapture.index();
			}
		}
	}

	@Test
	void shouldCaptureDataMutationsInAliveStage() {
		makeCatalogAliveAndCreateMutationSet();

		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			final List<ChangeCatalogCapture> reverseCaptures = session.getMutationsHistory(
				ChangeCatalogCaptureRequest.builder()
					.criteria(
						ChangeCatalogCaptureCriteria.builder()
							.dataArea()
							.build()
					)
					.content(ChangeCaptureContent.BODY)
					.build()
			).toList();
			assertEquals(6, reverseCaptures.size());

			int index = Integer.MAX_VALUE;
			for (ChangeCatalogCapture reverseCapture : reverseCaptures) {
				assertTrue(
					reverseCapture.index() <= index,
					"Index " + reverseCapture.index() + " is not greater than " + index + " for " + reverseCapture
				);
				assertEquals(CaptureArea.DATA, reverseCapture.area());
				index = reverseCapture.index();
			}
		}
	}

	@Test
	void shouldCaptureDataAndInfrastructureMutationsInAliveStage() {
		makeCatalogAliveAndCreateMutationSet();

		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			final List<ChangeCatalogCapture> reverseCaptures = session.getMutationsHistory(
				ChangeCatalogCaptureRequest.builder()
					.criteria(
						ChangeCatalogCaptureCriteria.builder()
							.dataArea()
							.build(),
						ChangeCatalogCaptureCriteria.builder()
							.area(CaptureArea.INFRASTRUCTURE)
							.build()
					)
					.content(ChangeCaptureContent.BODY)
					.build()
			).toList();
			assertEquals(7, reverseCaptures.size());

			// first mutation is transaction boundary mutation
			assertInstanceOf(TransactionMutation.class, reverseCaptures.get(0).body());

			int index = Integer.MAX_VALUE;
			for (ChangeCatalogCapture reverseCapture : reverseCaptures) {
				assertTrue(
					index == 0 || reverseCapture.index() <= index,
					"Index " + reverseCapture.index() + " is not greater than " + index + " for " + reverseCapture
				);
				assertTrue(reverseCapture.area() == CaptureArea.DATA || reverseCapture.area() == CaptureArea.INFRASTRUCTURE);
				index = reverseCapture.index();
			}
		}
	}

	@Test
	void shouldCombineBothDataAndSchemaMutations() {
		makeCatalogAliveAndCreateMutationSet();

		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			final List<ChangeCatalogCapture> reverseCaptures = session.getMutationsHistory(
				ChangeCatalogCaptureRequest.builder()
					.content(ChangeCaptureContent.BODY)
					.build()
			).toList();
			assertEquals(17, reverseCaptures.size());

			// first mutation is transaction boundary mutation
			assertInstanceOf(TransactionMutation.class, reverseCaptures.get(0).body());

			int index = Integer.MAX_VALUE;
			for (ChangeCatalogCapture reverseCapture : reverseCaptures) {
				assertTrue(
					index == 0 || reverseCapture.index() <= index,
					"Index " + reverseCapture.index() + " is not greater than " + index + " for " + reverseCapture
				);
				index = reverseCapture.index();
			}
		}
	}

	@Test
	void shouldFocusOnReplicableMutations() {
		makeCatalogAliveAndCreateMutationSet();

		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			final List<ChangeCatalogCapture> reverseCaptures = session.getMutationsHistory(
				ChangeCatalogCaptureRequest.builder()
					.criteria(
						ChangeCatalogCaptureCriteria.builder()
							.area(CaptureArea.DATA)
							.site(
								DataSite.builder()
									.containerType(ContainerType.ENTITY)
									.build()
							)
							.build(),
						ChangeCatalogCaptureCriteria.builder()
							.area(CaptureArea.SCHEMA)
							.site(
								SchemaSite.builder()
									.containerType(ContainerType.ENTITY)
									.build()
							)
							.build(),
						ChangeCatalogCaptureCriteria.builder()
							.area(CaptureArea.INFRASTRUCTURE)
							.build()
					)
					.content(ChangeCaptureContent.BODY)
					.build()
			).toList();
			assertEquals(7, reverseCaptures.size());

			// first mutation is transaction boundary mutation
			assertInstanceOf(TransactionMutation.class, reverseCaptures.get(0).body());

			int index = Integer.MAX_VALUE;
			for (ChangeCatalogCapture reverseCapture : reverseCaptures) {
				assertTrue(
					index == 0 || reverseCapture.index() <= index,
					"Index " + reverseCapture.index() + " is not greater than " + index + " for " + reverseCapture
				);
				index = reverseCapture.index();
			}
		}
	}

	@Test
	void shouldFocusOnLocalMutationsOfExactAttribute() {
		makeCatalogAliveAndCreateMutationSet();

		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			final List<ChangeCatalogCapture> reverseCaptures = session.getMutationsHistory(
				ChangeCatalogCaptureRequest.builder()
					.criteria(
						ChangeCatalogCaptureCriteria.builder()
							.area(CaptureArea.DATA)
							.site(
								DataSite.builder()
									.containerType(ContainerType.ATTRIBUTE)
									.containerName(ATTRIBUTE_NAME)
									.build()
							)
							.build()
					)
					.content(ChangeCaptureContent.BODY)
					.build()
			).toList();

			assertEquals(2, reverseCaptures.size());

			assertEquals(Entities.PRODUCT, reverseCaptures.get(0).entityType());
			assertEquals(Entities.BRAND, reverseCaptures.get(1).entityType());
		}
	}

	@Test
	void shouldFocusOnLocalMutationsOfPrices() {
		makeCatalogAliveAndCreateMutationSet();
		setPricesToTheProduct();

		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			final List<ChangeCatalogCapture> reverseCaptures = session.getMutationsHistory(
				ChangeCatalogCaptureRequest.builder()
					.criteria(
						ChangeCatalogCaptureCriteria.builder()
							.area(CaptureArea.DATA)
							.site(
								DataSite.builder()
									.containerType(ContainerType.PRICE)
									.build()
							)
							.build()
					)
					.content(ChangeCaptureContent.BODY)
					.build()
			).toList();

			assertEquals(2, reverseCaptures.size());

			assertEquals(Entities.PRODUCT, reverseCaptures.get(0).entityType());
			assertSame(CURRENCY_USD, ((UpsertPriceMutation)reverseCaptures.get(0).body()).getPriceKey().currency());
			assertEquals(Entities.PRODUCT, reverseCaptures.get(1).entityType());
			assertSame(CURRENCY_CZK, ((UpsertPriceMutation)reverseCaptures.get(1).body()).getPriceKey().currency());
		}
	}

	@Test
	void shouldFocusOnAllMutationsOfSingleEntity() {
		makeCatalogAliveAndCreateMutationSet();
		setPricesToTheProduct();

		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			final List<ChangeCatalogCapture> reverseCaptures = session.getMutationsHistory(
				ChangeCatalogCaptureRequest.builder()
					.criteria(
						ChangeCatalogCaptureCriteria.builder()
							.area(CaptureArea.DATA)
							.site(
								DataSite.builder()
									.entityType(Entities.PRODUCT)
									.entityPrimaryKey(1)
									.build()
							)
							.build()
					)
					.content(ChangeCaptureContent.BODY)
					.build()
			).toList();

			assertEquals(7, reverseCaptures.size());

			for (ChangeCatalogCapture reverseCapture : reverseCaptures) {
				assertEquals(Entities.PRODUCT, reverseCapture.entityType());
			}
		}
	}

	@Test
	void shouldFocusOnSchemaChangesOfSingleEntityType() {
		makeCatalogAliveAndCreateMutationSet();

		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			final List<ChangeCatalogCapture> reverseCaptures = session.getMutationsHistory(
				ChangeCatalogCaptureRequest.builder()
					.criteria(
						ChangeCatalogCaptureCriteria.builder()
							.area(CaptureArea.DATA)
							.site(
								DataSite.builder()
									.entityType(Entities.BRAND)
									.build()
							)
							.build()
					)
					.content(ChangeCaptureContent.BODY)
					.build()
			).toList();

			assertEquals(2, reverseCaptures.size());

			for (ChangeCatalogCapture reverseCapture : reverseCaptures) {
				assertEquals(Entities.BRAND, reverseCapture.entityType());
			}
		}
	}

	@Test
	void shouldCorrectlyHandleEntitySchemaRenaming() {
		makeCatalogAliveAndCreateMutationSet();

		try (final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG)) {
			assertTrue(session.renameCollection(Entities.BRAND, "brand_renamed"));
			session.defineEntitySchema("brand_renamed")
				.withAttribute(ATTRIBUTE_URL, String.class, AttributeSchemaEditor::nullable)
				.updateVia(session);

			session.getEntity("brand_renamed", 1, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.setAttribute(ATTRIBUTE_NAME, "Different brand")
				.upsertVia(session);
		}

		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			final List<ChangeCatalogCapture> brandDataReverseCaptures = session.getMutationsHistory(
				ChangeCatalogCaptureRequest.builder()
					.criteria(
						ChangeCatalogCaptureCriteria.builder()
							.area(CaptureArea.DATA)
							.site(
								DataSite.builder()
									.entityType(Entities.BRAND)
									.build()
							)
							.build()
					)
					.content(ChangeCaptureContent.BODY)
					.build()
			).toList();

			assertEquals(2, brandDataReverseCaptures.size());

			for (ChangeCatalogCapture reverseCapture : brandDataReverseCaptures) {
				assertEquals(Entities.BRAND, reverseCapture.entityType());
			}

			final List<ChangeCatalogCapture> brandSchemaReverseCaptures = session.getMutationsHistory(
				ChangeCatalogCaptureRequest.builder()
					.criteria(
						ChangeCatalogCaptureCriteria.builder()
							.area(CaptureArea.SCHEMA)
							.site(
								SchemaSite.builder()
									.entityType(Entities.BRAND)
									.build()
							)
							.build()
					)
					.content(ChangeCaptureContent.BODY)
					.build()
			).toList();

			assertEquals(4, brandSchemaReverseCaptures.size());

			for (ChangeCatalogCapture reverseCapture : brandSchemaReverseCaptures) {
				assertEquals(Entities.BRAND, reverseCapture.entityType());
			}

			final List<ChangeCatalogCapture> reverseDataCapturesAfterRename = session.getMutationsHistory(
				ChangeCatalogCaptureRequest.builder()
					.criteria(
						ChangeCatalogCaptureCriteria.builder()
							.area(CaptureArea.DATA)
							.site(
								DataSite.builder()
									.entityType("brand_renamed")
									.build()
							)
							.build()
					)
					.content(ChangeCaptureContent.BODY)
					.build()
			).toList();

			assertEquals(2, reverseDataCapturesAfterRename.size());

			for (ChangeCatalogCapture reverseCapture : reverseDataCapturesAfterRename) {
				assertEquals("brand_renamed", reverseCapture.entityType());
			}

			final List<ChangeCatalogCapture> reverseSchemaCapturesAfterRename = session.getMutationsHistory(
				ChangeCatalogCaptureRequest.builder()
					.criteria(
						ChangeCatalogCaptureCriteria.builder()
							.area(CaptureArea.SCHEMA)
							.site(
								SchemaSite.builder()
									.entityType("brand_renamed")
									.build()
							)
							.build()
					)
					.content(ChangeCaptureContent.BODY)
					.build()
			).toList();

			assertEquals(1, reverseSchemaCapturesAfterRename.size());

			for (ChangeCatalogCapture reverseCapture : reverseSchemaCapturesAfterRename) {
				assertEquals("brand_renamed", reverseCapture.entityType());
			}
		}
	}

	@Test
	void shouldCaptureDataMutationsInBothDirectionsConsistently() {
		makeCatalogAliveAndCreateMutationSet();

		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			final ChangeCatalogCaptureRequest request = ChangeCatalogCaptureRequest.builder()
				.criteria(
					ChangeCatalogCaptureCriteria.builder()
						.dataArea()
						.build()
				)
				.content(ChangeCaptureContent.BODY)
				.build();

			final List<ChangeCatalogCapture> reverseCaptures = session.getMutationsHistoryReversed(request).toList();
			final List<ChangeCatalogCapture> forwardCaptures = session.getMutationsHistoryForward(request).toList();

			assertEquals(6, forwardCaptures.size());
			assertEquals(reverseCaptures.size(), forwardCaptures.size());

			// forward and reverse traverse the exact same physical records - only in opposite order, with the
			// same index assigned to the same record regardless of direction
			final List<ChangeCatalogCapture> reversedForwardCaptures = new ArrayList<>(forwardCaptures);
			Collections.reverse(reversedForwardCaptures);
			assertEquals(reverseCaptures, reversedForwardCaptures);

			int index = -1;
			for (ChangeCatalogCapture forwardCapture : forwardCaptures) {
				assertTrue(
					forwardCapture.index() >= index,
					"Index " + forwardCapture.index() + " is not greater than or equal to " + index + " for " + forwardCapture
				);
				assertEquals(CaptureArea.DATA, forwardCapture.area());
				index = forwardCapture.index();
			}
		}
	}

	@Test
	void shouldEmitEntityAndLocalMutationCapturesInOppositeOrderByDirection() {
		makeCatalogAliveAndCreateMutationSet();
		setPricesToTheProduct();

		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			final ChangeCatalogCaptureRequest request = ChangeCatalogCaptureRequest.builder()
				.criteria(
					ChangeCatalogCaptureCriteria.builder()
						.area(CaptureArea.DATA)
						.site(
							DataSite.builder()
								.entityType(Entities.PRODUCT)
								.entityPrimaryKey(1)
								.build()
						)
						.build()
				)
				.content(ChangeCaptureContent.BODY)
				.build();

			// the price-setting upsert is the last of the seeded mutations (its own, later transaction), so its
			// record - one entity-level capture plus the two local price captures it fanned out into - is the
			// last group in forward (oldest-first) order
			final List<ChangeCatalogCapture> forwardCaptures = session.getMutationsHistoryForward(request).toList();
			final List<ChangeCatalogCapture> forwardPriceGroup = forwardCaptures.subList(forwardCaptures.size() - 3, forwardCaptures.size());
			assertInstanceOf(EntityUpsertMutation.class, forwardPriceGroup.get(0).body());
			assertInstanceOf(UpsertPriceMutation.class, forwardPriceGroup.get(1).body());
			assertInstanceOf(UpsertPriceMutation.class, forwardPriceGroup.get(2).body());

			// same record is the first group in reverse (newest-first) order, with locals preceding the
			// entity-level capture - the opposite nesting order from forward
			final List<ChangeCatalogCapture> reverseCaptures = session.getMutationsHistoryReversed(request).toList();
			final List<ChangeCatalogCapture> reversePriceGroup = reverseCaptures.subList(0, 3);
			assertInstanceOf(UpsertPriceMutation.class, reversePriceGroup.get(0).body());
			assertInstanceOf(UpsertPriceMutation.class, reversePriceGroup.get(1).body());
			assertInstanceOf(EntityUpsertMutation.class, reversePriceGroup.get(2).body());
		}
	}

	@Test
	void shouldAnchorForwardStreamAtInclusiveFloorVersion() {
		makeCatalogAliveAndCreateMutationSet();
		setPricesToTheProduct();

		try (final EvitaSessionContract session = this.evita.createReadOnlySession(TEST_CATALOG)) {
			final List<ChangeCatalogCapture> allCaptures = session.getMutationsHistoryForward(
				ChangeCatalogCaptureRequest.builder()
					.content(ChangeCaptureContent.HEADER)
					.build()
			).toList();

			// two transactions were committed (schema+data, then prices) - the price transaction's header is the
			// newest, i.e. the last INFRASTRUCTURE capture in forward order
			final ChangeCatalogCapture priceTransactionHeader = allCaptures.stream()
				.filter(capture -> capture.area() == CaptureArea.INFRASTRUCTURE)
				.reduce((first, second) -> second)
				.orElseThrow();

			final List<ChangeCatalogCapture> anchoredCaptures = session.getMutationsHistoryForward(
				ChangeCatalogCaptureRequest.builder()
					.sinceVersion(priceTransactionHeader.version())
					.content(ChangeCaptureContent.HEADER)
					.build()
			).toList();

			// the floor is inclusive: the anchor version's own header capture is the first one returned
			assertEquals(priceTransactionHeader.version(), anchoredCaptures.get(0).version());
			assertTrue(anchoredCaptures.stream().allMatch(capture -> capture.version() >= priceTransactionHeader.version()));
			// and it is exactly the tail of the unanchored stream - nothing gained or lost by anchoring
			assertEquals(
				allCaptures.subList(allCaptures.size() - anchoredCaptures.size(), allCaptures.size()),
				anchoredCaptures
			);
		}
	}

	private void makeCatalogAliveAndCreateMutationSet() {
		try (final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG)) {
			session.goLiveAndClose();
		}

		try (final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG)) {
			createSchema(session);
			createDataInSchema(session);
		}
	}

	private void setPricesToTheProduct() {
		try (final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG)) {
			session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
				.setPrice(2, PRICE_LIST_BASIC, CURRENCY_USD, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true)
				.upsertVia(session);
		}
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return getEvitaConfiguration(-1);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration(int inactivityTimeoutInSeconds) {
		return newTestEvitaConfigurationBuilder(this.paths)
			.server(
				ServerOptions.builder()
					.serviceThreadPool(
						ThreadPoolOptions.serviceThreadPoolBuilder()
							.minThreadCount(1)
							.maxThreadCount(1)
							.queueSize(10_000)
							.build()
					)
					.closeSessionsAfterSecondsOfInactivity(inactivityTimeoutInSeconds)
					.build()
			)
			.storage(
				StorageOptions.builder()
					.storageDirectory(this.paths.storage())
					.workDirectory(this.paths.work())
					.timeTravelEnabled(false)
					.build()
			)
			.build();
	}

}
