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

package io.evitadb.index.price;

import io.evitadb.api.CatalogState;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex.PriceListAndCurrencyPriceIndexTerminated;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencyRefIndexStoragePart;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Currency;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for {@link PriceListAndCurrencyPriceRefIndex} verifying catalog attachment, price add/remove
 * delegation to super index, storage part creation, transactional commit/rollback semantics,
 * and the generational proof of consistency.
 *
 * @author evitaDB
 */
@DisplayName("PriceListAndCurrencyPriceRefIndex functionality")
class PriceListAndCurrencyPriceRefIndexTest implements TimeBoundedTestSupport {

	private static final String ENTITY_TYPE = "product";
	private static final Scope SCOPE = Scope.LIVE;
	private static final Currency CURRENCY_CZK = Currency.getInstance("CZK");
	private static final String PRICE_LIST = "basic";
	private static final PriceIndexKey PRICE_INDEX_KEY =
		new PriceIndexKey(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE);

	private PriceListAndCurrencyPriceSuperIndex superIndex;
	private PriceListAndCurrencyPriceRefIndex refIndex;

	/**
	 * Initializes a fresh super index and an empty ref index before each test. The ref index
	 * is **not** attached to a catalog by default -- each test decides whether to call
	 * {@link #attachRefIndexToCatalog(PriceListAndCurrencyPriceRefIndex,
	 * PriceListAndCurrencyPriceSuperIndex)}.
	 */
	@BeforeEach
	void setUp() {
		this.superIndex = new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
		this.refIndex = new PriceListAndCurrencyPriceRefIndex(SCOPE, PRICE_INDEX_KEY);
	}

	/**
	 * Creates a {@link PriceRecord} with the given internal price id, price id, entity primary key
	 * and fixed price values of 12100 (with tax) and 10000 (without tax).
	 */
	@Nonnull
	private static PriceRecordContract createPriceRecord(
		int internalPriceId,
		int priceId,
		int entityPrimaryKey
	) {
		return new PriceRecord(internalPriceId, priceId, entityPrimaryKey, 12100, 10000);
	}

	/**
	 * Creates a {@link PriceRecord} with the given internal price id, entity primary key,
	 * and custom price values.
	 */
	@Nonnull
	private static PriceRecordContract createPriceRecordWithPrice(
		int internalPriceId,
		int priceId,
		int entityPrimaryKey,
		int priceWithTax,
		int priceWithoutTax
	) {
		return new PriceRecord(internalPriceId, priceId, entityPrimaryKey, priceWithTax, priceWithoutTax);
	}

	/**
	 * Attaches the given ref index to a mocked catalog that returns the provided super index
	 * through the standard `Catalog -> GlobalEntityIndex -> PriceSuperIndex` chain.
	 */
	private static void attachRefIndexToCatalog(
		@Nonnull PriceListAndCurrencyPriceRefIndex refIndex,
		@Nonnull PriceListAndCurrencyPriceSuperIndex superIndex
	) {
		final PriceSuperIndex priceSuperIndex = Mockito.mock(PriceSuperIndex.class);
		Mockito.when(priceSuperIndex.getPriceIndex(PRICE_INDEX_KEY)).thenReturn(superIndex);

		final GlobalEntityIndex globalEntityIndex = Mockito.mock(GlobalEntityIndex.class);
		Mockito.when(globalEntityIndex.getPriceIndex(PRICE_INDEX_KEY)).thenReturn(superIndex);

		final Catalog catalog = Mockito.mock(Catalog.class);
		Mockito.when(catalog.getEntityIndexIfExists(
			ArgumentMatchers.eq(ENTITY_TYPE),
			ArgumentMatchers.eq(new EntityIndexKey(EntityIndexType.GLOBAL, SCOPE)),
			ArgumentMatchers.eq(GlobalEntityIndex.class)
		)).thenReturn(Optional.of(globalEntityIndex));

		refIndex.attachToCatalog(ENTITY_TYPE, catalog);
	}

	/**
	 * Populates the super index with the specified price records (no validity) and returns
	 * a ref index attached to that super index via catalog mock.
	 */
	@Nonnull
	private static PriceListAndCurrencyPriceRefIndex createAttachedRefIndex(
		@Nonnull PriceListAndCurrencyPriceSuperIndex superIndex,
		@Nonnull PriceRecordContract... pricesToAddToSuper
	) {
		for (final PriceRecordContract price : pricesToAddToSuper) {
			superIndex.addPrice(price, null);
		}
		final PriceListAndCurrencyPriceRefIndex newRefIndex =
			new PriceListAndCurrencyPriceRefIndex(SCOPE, PRICE_INDEX_KEY);
		attachRefIndexToCatalog(newRefIndex, superIndex);
		return newRefIndex;
	}

	/**
	 * Creates a ref index from deserialized data (price ids constructor), attaches it to the
	 * catalog mock, and returns it ready for use.
	 */
	@Nonnull
	private static PriceListAndCurrencyPriceRefIndex createAttachedRefIndexFromPriceIds(
		@Nonnull PriceListAndCurrencyPriceSuperIndex superIndex,
		@Nonnull int[] priceIds
	) {
		final PriceListAndCurrencyPriceRefIndex newRefIndex =
			new PriceListAndCurrencyPriceRefIndex(SCOPE, PRICE_INDEX_KEY, new RangeIndex(), priceIds);
		attachRefIndexToCatalog(newRefIndex, superIndex);
		return newRefIndex;
	}

	/**
	 * Tests verifying the catalog attachment lifecycle of the ref index.
	 */
	@Nested
	@DisplayName("Catalog attachment")
	class CatalogAttachmentTest {

		@Test
		@DisplayName("should attach and populate priceRecords from super index")
		void shouldAttachAndPopulatePriceRecordsFromSuperIndex() {
			final PriceRecordContract price1 = createPriceRecord(1, 1, 100);
			final PriceRecordContract price2 = createPriceRecord(2, 2, 200);
			final PriceRecordContract price3 = createPriceRecord(3, 3, 300);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price1, null);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price2, null);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price3, null);

			// construct ref index from "deserialized" price ids
			final PriceListAndCurrencyPriceRefIndex tested = createAttachedRefIndexFromPriceIds(
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex,
				new int[]{1, 2, 3}
			);

			// verify price records were populated from super index
			final PriceRecordContract[] priceRecords = tested.getPriceRecords();
			assertEquals(3, priceRecords.length);
			assertArrayEquals(new int[]{100, 200, 300}, tested.getIndexedPriceEntityIds().getArray());
			assertArrayEquals(new int[]{1, 2, 3}, tested.getIndexedPriceIds());
		}

		@Test
		@DisplayName("should throw when entity type is null")
		void shouldThrowWhenEntityTypeIsNull() {
			final Catalog catalog = Mockito.mock(Catalog.class);

			assertThrows(
				GenericEvitaInternalError.class,
				() -> PriceListAndCurrencyPriceRefIndexTest.this.refIndex.attachToCatalog(null, catalog)
			);
		}

		@Test
		@DisplayName("should throw when already attached")
		void shouldThrowWhenAlreadyAttached() {
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(
				createPriceRecord(1, 1, 100), null
			);
			attachRefIndexToCatalog(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);

			final Catalog catalog = Mockito.mock(Catalog.class);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> PriceListAndCurrencyPriceRefIndexTest.this.refIndex.attachToCatalog(ENTITY_TYPE, catalog)
			);
		}

		@Test
		@DisplayName("should throw when super index not found in catalog")
		void shouldThrowWhenSuperIndexNotFound() {
			final Catalog catalog = Mockito.mock(Catalog.class);
			Mockito.when(catalog.getEntityIndexIfExists(
				ArgumentMatchers.eq(ENTITY_TYPE),
				ArgumentMatchers.eq(new EntityIndexKey(EntityIndexType.GLOBAL, SCOPE)),
				ArgumentMatchers.eq(GlobalEntityIndex.class)
			)).thenReturn(Optional.empty());

			assertThrows(
				GenericEvitaInternalError.class,
				() -> PriceListAndCurrencyPriceRefIndexTest.this.refIndex.attachToCatalog(ENTITY_TYPE, catalog)
			);
		}

		@Test
		@DisplayName("should populate only matching prices from super index")
		void shouldPopulateOnlyMatchingPrices() {
			// super has 3 prices but ref only tracks 2
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(
				createPriceRecord(1, 1, 100), null
			);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(
				createPriceRecord(2, 2, 200), null
			);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(
				createPriceRecord(3, 3, 300), null
			);

			final PriceListAndCurrencyPriceRefIndex tested = createAttachedRefIndexFromPriceIds(
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex,
				new int[]{1, 3}
			);

			final PriceRecordContract[] priceRecords = tested.getPriceRecords();
			assertEquals(2, priceRecords.length);
			assertArrayEquals(new int[]{100, 300}, tested.getIndexedPriceEntityIds().getArray());
			assertArrayEquals(new int[]{1, 3}, tested.getIndexedPriceIds());
		}
	}

	/**
	 * Tests verifying the `addPrice` method that delegates PriceRecord lookup to the super index.
	 */
	@Nested
	@DisplayName("Add price")
	class AddPriceTest {

		@Test
		@DisplayName("should add price by delegating to super index for record lookup")
		void shouldAddPriceByDelegatingToSuperIndex() {
			// pre-populate super with a price
			final PriceRecordContract price = createPriceRecord(10, 10, 42);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price, null);

			// create empty attached ref
			attachRefIndexToCatalog(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);

			// add price to ref
			final PriceRecordContract returned =
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(10, null);

			assertEquals(price, returned);
			assertArrayEquals(
				new int[]{42},
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getIndexedPriceEntityIds().getArray()
			);
			assertArrayEquals(
				new int[]{10},
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getIndexedPriceIds()
			);
			assertEquals(1, PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getPriceRecords().length);
		}

		@Test
		@DisplayName("should add price with validity and verify validity index")
		void shouldAddPriceWithValidity() {
			final OffsetDateTime validFrom =
				OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
			final OffsetDateTime validTo =
				OffsetDateTime.of(2024, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC);
			final DateTimeRange validity = DateTimeRange.between(validFrom, validTo);

			final PriceRecordContract price = createPriceRecord(10, 10, 42);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price, validity);

			attachRefIndexToCatalog(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);

			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(10, validity);

			// verify validity index returns price at midpoint
			final OffsetDateTime midPoint =
				OffsetDateTime.of(2024, 6, 15, 12, 0, 0, 0, ZoneOffset.UTC);
			final int[] validIds = PriceListAndCurrencyPriceRefIndexTest.this.refIndex
				.getIndexedRecordIdsValidInFormula(midPoint)
				.compute()
				.getArray();
			assertTrue(ArrayUtils.indexOf(10, validIds) >= 0);
		}

		@Test
		@DisplayName("should add multiple prices for same entity")
		void shouldAddMultiplePricesForSameEntity() {
			final PriceRecordContract price1 = createPriceRecord(10, 10, 42);
			final PriceRecordContract price2 = createPriceRecord(20, 20, 42);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price1, null);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price2, null);

			attachRefIndexToCatalog(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);

			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(10, null);
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(20, null);

			assertArrayEquals(
				new int[]{42},
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getIndexedPriceEntityIds().getArray()
			);
			assertArrayEquals(
				new int[]{10, 20},
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getIndexedPriceIds()
			);
			assertEquals(
				2,
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getPriceRecords().length
			);
		}

		@Test
		@DisplayName("should add prices for different entities")
		void shouldAddPricesForDifferentEntities() {
			final PriceRecordContract price1 = createPriceRecord(10, 10, 42);
			final PriceRecordContract price2 = createPriceRecord(20, 20, 99);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price1, null);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price2, null);

			attachRefIndexToCatalog(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);

			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(10, null);
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(20, null);

			assertArrayEquals(
				new int[]{42, 99},
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getIndexedPriceEntityIds().getArray()
			);
		}
	}

	/**
	 * Tests verifying the `removePrice` method including the `containsAnyOf` entity eviction logic.
	 */
	@Nested
	@DisplayName("Remove price")
	class RemovePriceTest {

		@Test
		@DisplayName("should remove price but keep entity when other prices exist")
		void shouldRemovePriceButKeepEntityWhenOtherPricesExist() {
			final PriceRecordContract price1 = createPriceRecord(10, 10, 42);
			final PriceRecordContract price2 = createPriceRecord(20, 20, 42);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price1, null);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price2, null);

			attachRefIndexToCatalog(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(10, null);
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(20, null);

			// remove one price — entity 42 should remain because it still has price 20
			final PriceRecordContract removed =
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.removePrice(10, null);

			assertEquals(price1, removed);
			// entity 42 still present (price 20 remains)
			assertArrayEquals(
				new int[]{42},
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getIndexedPriceEntityIds().getArray()
			);
			assertArrayEquals(
				new int[]{20},
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getIndexedPriceIds()
			);
			assertEquals(
				1,
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getPriceRecords().length
			);
		}

		@Test
		@DisplayName("should remove price and entity when it is the last price")
		void shouldRemovePriceAndEntityWhenLastPrice() {
			final PriceRecordContract price = createPriceRecord(10, 10, 42);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price, null);

			attachRefIndexToCatalog(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(10, null);

			// remove the only price — entity should be evicted
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.removePrice(10, null);

			assertTrue(PriceListAndCurrencyPriceRefIndexTest.this.refIndex.isEmpty());
			assertEquals(0, PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getPriceRecords().length);
			assertArrayEquals(
				new int[]{},
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getIndexedPriceEntityIds().getArray()
			);
		}

		@Test
		@DisplayName("should remove validity entry on remove")
		void shouldRemoveValidityOnRemove() {
			final OffsetDateTime validFrom =
				OffsetDateTime.of(2024, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC);
			final OffsetDateTime validTo =
				OffsetDateTime.of(2024, 3, 31, 23, 59, 59, 0, ZoneOffset.UTC);
			final DateTimeRange validity = DateTimeRange.between(validFrom, validTo);

			final PriceRecordContract price = createPriceRecord(10, 10, 42);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price, validity);

			attachRefIndexToCatalog(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(10, validity);

			// verify validity is recorded
			final OffsetDateTime midPoint =
				OffsetDateTime.of(2024, 3, 15, 12, 0, 0, 0, ZoneOffset.UTC);
			assertTrue(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex
					.getIndexedRecordIdsValidInFormula(midPoint)
					.compute()
					.getArray().length > 0
			);

			// remove with same validity
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.removePrice(10, validity);

			assertTrue(PriceListAndCurrencyPriceRefIndexTest.this.refIndex.isEmpty());
		}

		@Test
		@DisplayName("should keep different entity when removing one entity's last price")
		void shouldKeepDifferentEntityWhenRemovingOneEntityLastPrice() {
			final PriceRecordContract price1 = createPriceRecord(10, 10, 42);
			final PriceRecordContract price2 = createPriceRecord(20, 20, 99);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price1, null);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price2, null);

			attachRefIndexToCatalog(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(10, null);
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(20, null);

			// remove entity 42's only price
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.removePrice(10, null);

			// entity 99 should still be present
			assertArrayEquals(
				new int[]{99},
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getIndexedPriceEntityIds().getArray()
			);
			assertFalse(PriceListAndCurrencyPriceRefIndexTest.this.refIndex.isEmpty());
		}
	}

	/**
	 * Tests verifying delegation of entity-level lookups to the super index.
	 */
	@Nested
	@DisplayName("Delegation to super index")
	class DelegationTest {

		@Test
		@DisplayName("should delegate getInternalPriceIdsForEntity to super index")
		void shouldDelegateGetInternalPriceIdsForEntity() {
			final PriceRecordContract price1 = createPriceRecord(10, 10, 42);
			final PriceRecordContract price2 = createPriceRecord(20, 20, 42);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price1, null);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price2, null);

			attachRefIndexToCatalog(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);

			final int[] refResult =
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getInternalPriceIdsForEntity(42);
			final int[] superResult =
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex.getInternalPriceIdsForEntity(42);

			assertNotNull(refResult);
			assertArrayEquals(superResult, refResult);
		}

		@Test
		@DisplayName("should delegate getLowestPriceRecordsForEntity to super index")
		void shouldDelegateGetLowestPriceRecordsForEntity() {
			final PriceRecordContract price = createPriceRecord(10, 10, 42);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price, null);

			attachRefIndexToCatalog(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);

			final PriceRecordContract[] refResult =
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getLowestPriceRecordsForEntity(42);
			final PriceRecordContract[] superResult =
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex.getLowestPriceRecordsForEntity(42);

			assertNotNull(refResult);
			assertArrayEquals(superResult, refResult);
		}

		@Test
		@DisplayName("should return null for unknown entity")
		void shouldReturnNullForUnknownEntity() {
			attachRefIndexToCatalog(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);

			assertNull(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getInternalPriceIdsForEntity(999)
			);
			assertNull(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getLowestPriceRecordsForEntity(999)
			);
		}
	}

	/**
	 * Tests verifying storage part creation and dirty flag management.
	 */
	@Nested
	@DisplayName("Storage part")
	class StoragePartTest {

		@Test
		@DisplayName("should create PriceListAndCurrencyRefIndexStoragePart when dirty")
		void shouldCreateRefIndexStoragePartWhenDirty() {
			final PriceRecordContract price = createPriceRecord(10, 10, 42);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price, null);

			attachRefIndexToCatalog(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(10, null);

			final StoragePart part =
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.createStoragePart(1);

			assertNotNull(part);
			assertInstanceOf(PriceListAndCurrencyRefIndexStoragePart.class, part);

			final PriceListAndCurrencyRefIndexStoragePart refPart =
				(PriceListAndCurrencyRefIndexStoragePart) part;
			assertArrayEquals(new int[]{10}, refPart.getPriceIds());
		}

		@Test
		@DisplayName("should return null when clean (no mutations)")
		void shouldReturnNullWhenClean() {
			attachRefIndexToCatalog(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);

			final StoragePart part =
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.createStoragePart(1);

			assertNull(part);
		}

		@Test
		@DisplayName("should return null after resetDirty()")
		void shouldReturnNullAfterResetDirty() {
			final PriceRecordContract price = createPriceRecord(10, 10, 42);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price, null);

			attachRefIndexToCatalog(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(10, null);

			// dirty — should produce storage part
			assertNotNull(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.createStoragePart(1)
			);

			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.resetDirty();

			// clean — should be null
			assertNull(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.createStoragePart(1)
			);
		}
	}

	/**
	 * Tests verifying `createCopyForNewCatalogAttachment` produces a fresh detached copy.
	 */
	@Nested
	@DisplayName("Copy for new catalog attachment")
	class CopyTest {

		@Test
		@DisplayName("should create a distinct copy with same key and bitmaps")
		void shouldCreateCopyForNewCatalogAttachment() {
			final PriceRecordContract price = createPriceRecord(10, 10, 42);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price, null);

			attachRefIndexToCatalog(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(10, null);

			final PriceListAndCurrencyPriceRefIndex copy =
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.createCopyForNewCatalogAttachment(
					CatalogState.ALIVE
				);

			assertNotSame(PriceListAndCurrencyPriceRefIndexTest.this.refIndex, copy);
			assertEquals(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getPriceIndexKey(),
				copy.getPriceIndexKey()
			);
			// copy does NOT have priceRecords or superIndex until reattached,
			// but shares indexedPriceEntityIds and indexedPriceIds TransactionalBitmaps
			assertArrayEquals(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getIndexedPriceIds(),
				copy.getIndexedPriceIds()
			);
		}
	}

	/**
	 * Tests verifying unique ID assignment, toString format, and termination.
	 */
	@Nested
	@DisplayName("STM invariants")
	class StmInvariantsTest {

		@Test
		@DisplayName("should assign unique ID to each instance")
		void shouldAssignUniqueId() {
			final PriceListAndCurrencyPriceRefIndex first =
				new PriceListAndCurrencyPriceRefIndex(SCOPE, PRICE_INDEX_KEY);
			final PriceListAndCurrencyPriceRefIndex second =
				new PriceListAndCurrencyPriceRefIndex(SCOPE, PRICE_INDEX_KEY);

			assertNotEquals(first.getId(), second.getId());
		}

		@Test
		@DisplayName("should include scope prefix in toString")
		void shouldIncludeScopeInToString() {
			final PriceListAndCurrencyPriceRefIndex tested =
				new PriceListAndCurrencyPriceRefIndex(Scope.LIVE, PRICE_INDEX_KEY);

			final String str = tested.toString();
			assertTrue(str.startsWith("Live "), "Expected 'Live' prefix, got: " + str);
			assertFalse(str.contains("(TERMINATED)"));
		}

		@Test
		@DisplayName("should include scope 'Archived' for ARCHIVED scope")
		void shouldIncludeArchivedScopeInToString() {
			final PriceListAndCurrencyPriceRefIndex tested =
				new PriceListAndCurrencyPriceRefIndex(Scope.ARCHIVED, PRICE_INDEX_KEY);

			final String str = tested.toString();
			assertTrue(str.startsWith("Archived "), "Expected 'Archived' prefix, got: " + str);
		}

		@Test
		@DisplayName("should include '(TERMINATED)' suffix after terminate()")
		void shouldIncludeTerminatedInToString() {
			final PriceListAndCurrencyPriceRefIndex tested =
				new PriceListAndCurrencyPriceRefIndex(SCOPE, PRICE_INDEX_KEY);

			tested.terminate();

			final String str = tested.toString();
			assertTrue(str.contains("(TERMINATED)"), "Expected (TERMINATED) suffix, got: " + str);
		}

		@Test
		@DisplayName("should throw PriceListAndCurrencyPriceIndexTerminated after terminate()")
		void shouldThrowAfterTerminate() {
			final PriceListAndCurrencyPriceRefIndex tested =
				new PriceListAndCurrencyPriceRefIndex(SCOPE, PRICE_INDEX_KEY);

			tested.terminate();

			assertThrows(PriceListAndCurrencyPriceIndexTerminated.class, tested::isEmpty);
			assertThrows(PriceListAndCurrencyPriceIndexTerminated.class, tested::getPriceRecords);
			assertThrows(
				PriceListAndCurrencyPriceIndexTerminated.class,
				() -> tested.addPrice(1, null)
			);
		}

		@Test
		@DisplayName("removeLayer rolls back all nested fields cleanly")
		void shouldRemoveLayerFromAllNestedProducers() {
			final PriceRecordContract price = createPriceRecord(10, 10, 42);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price, null);

			final PriceListAndCurrencyPriceRefIndex tested =
				createAttachedRefIndex(PriceListAndCurrencyPriceRefIndexTest.this.superIndex);

			assertStateAfterRollback(
				tested,
				index -> index.addPrice(10, null),
				(original, committed) -> {
					assertNull(committed);
					assertTrue(original.isEmpty());
				}
			);
		}
	}

	/**
	 * Tests verifying transactional commit semantics: mutations visible only in committed copy.
	 */
	@Nested
	@DisplayName("Transactional commit")
	class TransactionalCommitTest {

		@Test
		@DisplayName("committed copy contains added price, original remains empty")
		void shouldCommitAddedPrice() {
			final PriceRecordContract price = createPriceRecord(10, 10, 42);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price, null);

			final PriceListAndCurrencyPriceRefIndex tested =
				createAttachedRefIndex(PriceListAndCurrencyPriceRefIndexTest.this.superIndex);

			assertStateAfterCommit(
				tested,
				index -> index.addPrice(10, null),
				(original, committed) -> {
					assertNotSame(original, committed);
					// committed has the price
					assertFalse(committed.isEmpty());
					assertArrayEquals(
						new int[]{42},
						committed.getIndexedPriceEntityIds().getArray()
					);
					assertArrayEquals(
						new int[]{10},
						committed.getIndexedPriceIds()
					);
					// original is unchanged (empty)
					assertTrue(original.isEmpty());
				}
			);
		}

		@Test
		@DisplayName("committed copy reflects price removal")
		void shouldCommitRemovedPrice() {
			final PriceRecordContract price = createPriceRecord(10, 10, 42);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price, null);

			final PriceListAndCurrencyPriceRefIndex tested =
				createAttachedRefIndex(PriceListAndCurrencyPriceRefIndexTest.this.superIndex);
			// pre-add the price outside transaction
			tested.addPrice(10, null);

			assertStateAfterCommit(
				tested,
				index -> index.removePrice(10, null),
				(original, committed) -> {
					assertNotSame(original, committed);
					// committed is empty after removal
					assertTrue(committed.isEmpty());
					assertArrayEquals(
						new int[]{},
						committed.getIndexedPriceEntityIds().getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("committed copy includes validity changes")
		void shouldCommitPriceWithValidity() {
			final OffsetDateTime validFrom =
				OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
			final OffsetDateTime validTo =
				OffsetDateTime.of(2024, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC);
			final DateTimeRange validity = DateTimeRange.between(validFrom, validTo);

			final PriceRecordContract price = createPriceRecord(5, 5, 42);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price, validity);

			final PriceListAndCurrencyPriceRefIndex tested =
				createAttachedRefIndex(PriceListAndCurrencyPriceRefIndexTest.this.superIndex);

			assertStateAfterCommit(
				tested,
				index -> index.addPrice(5, validity),
				(original, committed) -> {
					assertNotSame(original, committed);
					final OffsetDateTime midPoint =
						OffsetDateTime.of(2024, 6, 15, 12, 0, 0, 0, ZoneOffset.UTC);
					final int[] validIds = committed
						.getIndexedRecordIdsValidInFormula(midPoint)
						.compute()
						.getArray();
					assertArrayEquals(new int[]{5}, validIds);
				}
			);
		}
	}

	/**
	 * Tests verifying transactional rollback semantics: original instance remains unmodified.
	 */
	@Nested
	@DisplayName("Transactional rollback")
	class TransactionalRollbackTest {

		@Test
		@DisplayName("original unchanged after rollback of add")
		void shouldLeaveOriginalUnchangedAfterRollbackOfAdd() {
			final PriceRecordContract price = createPriceRecord(10, 10, 42);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price, null);

			final PriceListAndCurrencyPriceRefIndex tested =
				createAttachedRefIndex(PriceListAndCurrencyPriceRefIndexTest.this.superIndex);

			assertStateAfterRollback(
				tested,
				index -> index.addPrice(10, null),
				(original, committed) -> {
					assertNull(committed);
					assertTrue(original.isEmpty());
					assertEquals(0, original.getPriceRecords().length);
				}
			);
		}

		@Test
		@DisplayName("original unchanged after rollback of remove")
		void shouldLeaveOriginalUnchangedAfterRollbackOfRemove() {
			final PriceRecordContract price = createPriceRecord(10, 10, 42);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price, null);

			final PriceListAndCurrencyPriceRefIndex tested =
				createAttachedRefIndex(PriceListAndCurrencyPriceRefIndexTest.this.superIndex);
			// pre-add outside transaction
			tested.addPrice(10, null);

			assertStateAfterRollback(
				tested,
				index -> index.removePrice(10, null),
				(original, committed) -> {
					assertNull(committed);
					assertFalse(original.isEmpty());
					assertEquals(1, original.getPriceRecords().length);
					assertArrayEquals(
						new int[]{42},
						original.getIndexedPriceEntityIds().getArray()
					);
				}
			);
		}
	}

	/**
	 * Generational proof test that exercises random add/remove sequences within transactions,
	 * verifying the committed state matches the expected price subset after each iteration.
	 */
	@ParameterizedTest(
		name = "PriceListAndCurrencyPriceRefIndex should survive generational randomized test"
	)
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Generational proof test")
	void generationalProofTest(@Nonnull GenerationalTestInput input) {
		final int maxPrices = 50;
		final AtomicInteger priceIdSequence = new AtomicInteger(0);

		// pre-populate super index with a pool of prices
		final PriceRecordContract[] pricePool = new PriceRecordContract[maxPrices];
		final DateTimeRange[] validityPool = new DateTimeRange[maxPrices];
		for (int i = 0; i < maxPrices; i++) {
			final int internalId = priceIdSequence.incrementAndGet();
			final int entityPk = 1 + (i % 10);
			pricePool[i] = createPriceRecordWithPrice(
				internalId, internalId, entityPk,
				(int) ((100 + i * 10) * 1.21), 100 + i * 10
			);
			final OffsetDateTime from = OffsetDateTime.now().minusDays(30 + i);
			validityPool[i] = DateTimeRange.between(from, from.plusDays(60));
		}

		final PriceListAndCurrencyPriceSuperIndex baseSuperIndex =
			new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
		for (int i = 0; i < maxPrices; i++) {
			baseSuperIndex.addPrice(pricePool[i], validityPool[i]);
		}

		runFor(
			input,
			1_000,
			new GenerationalTestState(
				new StringBuilder(256),
				new int[0]
			),
			(random, testState) -> {
				// build ref from current tracked state
				final int[] trackedIds = testState.trackedInternalPriceIds();
				final PriceListAndCurrencyPriceRefIndex tested;
				if (trackedIds.length > 0) {
					tested = createAttachedRefIndexFromPriceIds(baseSuperIndex, trackedIds);
					// also add each price to make ref aware of them
					for (final int id : trackedIds) {
						final int arrayIdx = id - 1;
						tested.addPrice(id, validityPool[arrayIdx]);
					}
					tested.resetDirty();
				} else {
					tested = createAttachedRefIndex(baseSuperIndex);
				}

				final AtomicReference<int[]> nextTrackedIds = new AtomicReference<>(trackedIds);
				final StringBuilder codeBuffer = testState.code();
				codeBuffer.setLength(0);

				assertStateAfterCommit(
					tested,
					index -> {
						final int operationsInTransaction = 1 + random.nextInt(8);
						final Set<Integer> addedInThisRound = new HashSet<>(8);
						final Set<Integer> removedInThisRound = new HashSet<>(8);

						for (int i = 0; i < operationsInTransaction; i++) {
							final int currentLength = nextTrackedIds.get().length;
							if ((currentLength < maxPrices / 2 && random.nextBoolean())
								|| currentLength < 3) {
								// add a random price not already tracked
								int newId;
								int attempts = 0;
								do {
									newId = 1 + random.nextInt(maxPrices);
									attempts++;
								} while (
									(addedInThisRound.contains(newId) ||
										ArrayUtils.indexOf(newId, nextTrackedIds.get()) >= 0) &&
										attempts < 100
								);

								if (attempts >= 100) {
									continue;
								}

								final int arrayIdx = newId - 1;
								codeBuffer.append("addPrice(").append(newId).append(")\n");

								try {
									index.addPrice(newId, validityPool[arrayIdx]);
									final int finalNewId = newId;
									final int[] current = nextTrackedIds.get();
									final int[] updated = new int[current.length + 1];
									System.arraycopy(current, 0, updated, 0, current.length);
									updated[current.length] = finalNewId;
									Arrays.sort(updated);
									nextTrackedIds.set(updated);
									addedInThisRound.add(newId);
									removedInThisRound.remove(newId);
								} catch (Exception ex) {
									fail(ex.getMessage() + "\n" + codeBuffer, ex);
								}
							} else if (currentLength > 0) {
								// remove a random tracked price
								int idToRemove;
								int attempts = 0;
								do {
									final int[] current = nextTrackedIds.get();
									idToRemove = current[random.nextInt(current.length)];
									attempts++;
								} while (
									removedInThisRound.contains(idToRemove) && attempts < 100
								);

								if (attempts >= 100) {
									continue;
								}

								final int arrayIdx = idToRemove - 1;
								codeBuffer.append("removePrice(")
									.append(idToRemove).append(")\n");

								try {
									index.removePrice(idToRemove, validityPool[arrayIdx]);
									final int[] current = nextTrackedIds.get();
									final int removeIdx = ArrayUtils.indexOf(
										idToRemove, current
									);
									final int[] updated =
										new int[current.length - 1];
									System.arraycopy(
										current, 0,
										updated, 0, removeIdx
									);
									System.arraycopy(
										current, removeIdx + 1,
										updated, removeIdx,
										current.length - removeIdx - 1
									);
									nextTrackedIds.set(updated);
									removedInThisRound.add(idToRemove);
									addedInThisRound.remove(idToRemove);
								} catch (Exception ex) {
									fail(ex.getMessage() + "\n" + codeBuffer, ex);
								}
							}
						}
					},
					(original, committed) -> {
						final int[] expectedIds = nextTrackedIds.get();
						Arrays.sort(expectedIds);

						// verify indexedPriceIds match expected
						assertArrayEquals(
							expectedIds,
							committed.getIndexedPriceIds(),
							"IndexedPriceIds mismatch.\n" + codeBuffer
						);

						// verify entity ids
						final Set<Integer> expectedEntityIds = new HashSet<>(8);
						for (final int id : expectedIds) {
							expectedEntityIds.add(pricePool[id - 1].entityPrimaryKey());
						}
						final int[] actualEntityIds =
							committed.getIndexedPriceEntityIds().getArray();
						assertEquals(
							expectedEntityIds.size(),
							actualEntityIds.length,
							"Entity id count mismatch.\n" + codeBuffer
						);
						for (final int entityId : actualEntityIds) {
							assertTrue(
								expectedEntityIds.contains(entityId),
								"Unexpected entity id " + entityId + ".\n" + codeBuffer
							);
						}
					}
				);

				return new GenerationalTestState(
					codeBuffer,
					nextTrackedIds.get()
				);
			}
		);
	}

	/**
	 * Holds the state carried between generational proof test iterations.
	 *
	 * @param code                     debug code buffer for reproducibility
	 * @param trackedInternalPriceIds  sorted array of internal price ids currently tracked by the ref index
	 */
	private record GenerationalTestState(
		@Nonnull StringBuilder code,
		@Nonnull int[] trackedInternalPriceIds
	) {
	}

}
