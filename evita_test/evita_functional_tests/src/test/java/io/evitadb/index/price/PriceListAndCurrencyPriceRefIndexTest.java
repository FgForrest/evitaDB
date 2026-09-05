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

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex.PriceListAndCurrencyPriceIndexTerminated;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencyRefIndexStoragePart;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;

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
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.PRICE;

/**
 * Tests for {@link PriceListAndCurrencyPriceRefIndex} verifying catalog attachment, price add/remove
 * delegation to super index, storage part creation, transactional commit/rollback semantics,
 * and the generational proof of consistency.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("PriceListAndCurrencyPriceRefIndex functionality")
@Tag(INDEXING)
@Tag(PRICE)
class PriceListAndCurrencyPriceRefIndexTest implements TimeBoundedTestSupport {
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
	 * {@link #wireRefIndexToSuperIndex(PriceListAndCurrencyPriceRefIndex,
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
	 * Restores the given ref index's price-record tree from the provided super index, mirroring the only remaining
	 * attach-time price step the owning entity collection performs - repointing a ref index that was deserialized from
	 * disk at the shared price records the super index holds. It is a no-op for an in-memory ref index.
	 */
	private static void wireRefIndexToSuperIndex(
		@Nonnull PriceListAndCurrencyPriceRefIndex refIndex,
		@Nonnull PriceListAndCurrencyPriceSuperIndex superIndex
	) {
		refIndex.restorePriceRecordsFrom(superIndex);
	}

	/**
	 * Populates the super index with the specified price records (no validity) and returns
	 * a ref index wired to that super index.
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
		wireRefIndexToSuperIndex(newRefIndex, superIndex);
		return newRefIndex;
	}

	/**
	 * Creates a ref index from deserialized data (price ids constructor), wires it to the
	 * super index, and returns it ready for use.
	 */
	@Nonnull
	private static PriceListAndCurrencyPriceRefIndex createAttachedRefIndexFromPriceIds(
		@Nonnull PriceListAndCurrencyPriceSuperIndex superIndex,
		@Nonnull int[] priceIds
	) {
		final PriceListAndCurrencyPriceRefIndex newRefIndex =
			new PriceListAndCurrencyPriceRefIndex(SCOPE, PRICE_INDEX_KEY, new RangeIndex(), priceIds);
		wireRefIndexToSuperIndex(newRefIndex, superIndex);
		return newRefIndex;
	}

	/**
	 * Tests verifying the super-index wiring lifecycle of the ref index.
	 */
	@Nested
	@DisplayName("Super index wiring")
	class CatalogAttachmentTest {

		@Test
		@DisplayName("should wire and populate priceRecords from super index")
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
			assertArrayEquals(new int[]{1, 2, 3}, tested.getIndexedPriceIds().getArray());
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
			assertArrayEquals(new int[]{1, 3}, tested.getIndexedPriceIds().getArray());
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
			wireRefIndexToSuperIndex(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);

			// add price to ref
			final PriceRecordContract returned =
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(
					10, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex
				);

			assertEquals(price, returned);
			assertArrayEquals(
				new int[]{42},
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getIndexedPriceEntityIds().getArray()
			);
			assertArrayEquals(
				new int[]{10},
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getIndexedPriceIds().getArray()
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

			wireRefIndexToSuperIndex(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);

			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(
				10, validity, PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);

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

			wireRefIndexToSuperIndex(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);

			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(
				10, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(
				20, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);

			assertArrayEquals(
				new int[]{42},
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getIndexedPriceEntityIds().getArray()
			);
			assertArrayEquals(
				new int[]{10, 20},
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getIndexedPriceIds().getArray()
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

			wireRefIndexToSuperIndex(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);

			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(
				10, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(
				20, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);

			assertArrayEquals(
				new int[]{42, 99},
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getIndexedPriceEntityIds().getArray()
			);
		}
	}

	/**
	 * Tests verifying the `removePrice` method including the `containsAnyPriceOf` entity eviction logic.
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

			wireRefIndexToSuperIndex(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(
				10, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(
				20, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);

			// remove one price — entity 42 should remain because it still has price 20
			final PriceRecordContract removed =
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.removePrice(
					10, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex
				);

			assertEquals(price1, removed);
			// entity 42 still present (price 20 remains)
			assertArrayEquals(
				new int[]{42},
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getIndexedPriceEntityIds().getArray()
			);
			assertArrayEquals(
				new int[]{20},
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getIndexedPriceIds().getArray()
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

			wireRefIndexToSuperIndex(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(
				10, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);

			// remove the only price — entity should be evicted
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.removePrice(
				10, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);

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

			wireRefIndexToSuperIndex(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(
				10, validity, PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);

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
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.removePrice(
				10, validity, PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);

			assertTrue(PriceListAndCurrencyPriceRefIndexTest.this.refIndex.isEmpty());
		}

		@Test
		@DisplayName("should keep different entity when removing one entity's last price")
		void shouldKeepDifferentEntityWhenRemovingOneEntityLastPrice() {
			final PriceRecordContract price1 = createPriceRecord(10, 10, 42);
			final PriceRecordContract price2 = createPriceRecord(20, 20, 99);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price1, null);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(price2, null);

			wireRefIndexToSuperIndex(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(
				10, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(
				20, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);

			// remove entity 42's only price
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.removePrice(
				10, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);

			// entity 99 should still be present
			assertArrayEquals(
				new int[]{99},
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getIndexedPriceEntityIds().getArray()
			);
			assertFalse(PriceListAndCurrencyPriceRefIndexTest.this.refIndex.isEmpty());
		}

		@Test
		@DisplayName("should evict entity whose remaining prices live only in the super index")
		void shouldEvictEntityWhenRemainingPricesAreNotInThisIndex() {
			// entity 42 owns three prices in the super index, but this ref index tracks only one of
			// them - the eviction decision must therefore be answered against *this index's* content,
			// never against the entity's total price count
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(createPriceRecord(10, 10, 42), null);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(createPriceRecord(20, 20, 42), null);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(createPriceRecord(30, 30, 42), null);

			final PriceListAndCurrencyPriceRefIndex tested = createAttachedRefIndexFromPriceIds(
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex,
				new int[]{20}
			);
			assertArrayEquals(new int[]{42}, tested.getIndexedPriceEntityIds().getArray());

			tested.removePrice(20, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex);

			// entity 42 still has prices 10 and 30 in the SUPER index, yet none in this one - so it
			// must be evicted here regardless
			assertArrayEquals(new int[]{}, tested.getIndexedPriceEntityIds().getArray());
			assertTrue(tested.isEmpty());
		}

		@Test
		@DisplayName("should keep entity until its last price in this index is removed")
		void shouldKeepEntityUntilLastPriceInThisIndexRemoved() {
			// the entity's id set stays constant while the index shrinks, so each removal must be
			// judged against the prices still present rather than against the entity's id set
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(createPriceRecord(10, 10, 42), null);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(createPriceRecord(20, 20, 42), null);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(createPriceRecord(30, 30, 42), null);

			final PriceListAndCurrencyPriceRefIndex tested = createAttachedRefIndexFromPriceIds(
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex,
				new int[]{10, 20, 30}
			);

			tested.removePrice(20, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex);
			assertArrayEquals(new int[]{42}, tested.getIndexedPriceEntityIds().getArray());
			tested.removePrice(10, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex);
			assertArrayEquals(new int[]{42}, tested.getIndexedPriceEntityIds().getArray());

			tested.removePrice(30, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex);
			assertArrayEquals(new int[]{}, tested.getIndexedPriceEntityIds().getArray());
			assertTrue(tested.isEmpty());
		}

		@Test
		@DisplayName("should evict entity irrespective of the order prices are removed in")
		void shouldEvictEntityIrrespectiveOfRemovalOrder() {
			// guards against any assumption that the removed price, the entity's id set and the
			// index content are traversed in a compatible ascending order
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(createPriceRecord(5, 5, 42), null);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(createPriceRecord(1000, 1000, 42), null);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(createPriceRecord(7, 7, 99), null);

			final PriceListAndCurrencyPriceRefIndex tested = createAttachedRefIndexFromPriceIds(
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex,
				new int[]{5, 7, 1000}
			);

			// remove the highest internal price id first, then the lowest one of the same entity
			tested.removePrice(1000, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex);
			assertArrayEquals(new int[]{42, 99}, tested.getIndexedPriceEntityIds().getArray());

			tested.removePrice(5, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex);
			assertArrayEquals(new int[]{99}, tested.getIndexedPriceEntityIds().getArray());
			assertArrayEquals(new int[]{7}, tested.getIndexedPriceIds().getArray());
		}

		/**
		 * Every other fixture here gives a price the same number for its internal price id and its price id, which
		 * leaves an eviction walk that read price ids indistinguishable from one that reads internal ids. Here they
		 * differ: a walk over the price ids (710, 720) would find neither in the price-record tree and would evict
		 * entity 42 while one of its prices is still indexed.
		 */
		@Test
		@DisplayName("should keep an entity whose prices remain when its price ids differ from its internal ids")
		void shouldKeepEntityWhilePricesRemainWhenPriceIdsDifferFromInternalPriceIds() {
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(createPriceRecord(10, 710, 42), null);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(createPriceRecord(20, 720, 42), null);

			final PriceListAndCurrencyPriceRefIndex tested = createAttachedRefIndexFromPriceIds(
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex,
				new int[]{10, 20}
			);
			assertArrayEquals(new int[]{42}, tested.getIndexedPriceEntityIds().getArray());

			tested.removePrice(10, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex);

			assertArrayEquals(new int[]{42}, tested.getIndexedPriceEntityIds().getArray());
			assertArrayEquals(new int[]{20}, tested.getIndexedPriceIds());

			tested.removePrice(20, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex);

			assertArrayEquals(new int[]{}, tested.getIndexedPriceEntityIds().getArray());
			assertTrue(tested.isEmpty());
		}

		/**
		 * Entity 99's only price carries the price id 20, which is another entity's internal price id - legal, because
		 * the super index's duplicate guard is per entity. An eviction walk that probed the price-record tree with
		 * price ids would therefore find entity 42's record and keep entity 99 indexed with nothing left in this index.
		 */
		@Test
		@DisplayName("should evict a single-price entity whose price id collides with another entity's internal id")
		void shouldEvictASinglePriceEntityWhosePriceIdCollidesWithAnotherEntitysInternalPriceId() {
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(createPriceRecord(10, 710, 42), null);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(createPriceRecord(20, 720, 42), null);
			PriceListAndCurrencyPriceRefIndexTest.this.superIndex.addPrice(createPriceRecord(30, 20, 99), null);

			final PriceListAndCurrencyPriceRefIndex tested = createAttachedRefIndexFromPriceIds(
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex,
				new int[]{10, 20, 30}
			);
			assertArrayEquals(new int[]{42, 99}, tested.getIndexedPriceEntityIds().getArray());

			tested.removePrice(30, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex);

			assertArrayEquals(new int[]{42}, tested.getIndexedPriceEntityIds().getArray());
			assertArrayEquals(new int[]{10, 20}, tested.getIndexedPriceIds());
		}
	}

	/**
	 * Tests pinning that entity-level price lookups are NOT answered by a reduced index.
	 *
	 * These used to assert delegation to a super index this index held a pointer to. That pointer is gone: the caller
	 * resolves the super index itself and queries it directly, because only a super index owns the entity-to-prices
	 * mapping the lookups need. Reaching them on a reduced index is therefore a programming error, and the contract
	 * asserted here is that it surfaces as one instead of silently returning `null` - which would read as "this entity
	 * has no prices".
	 */
	@Nested
	@DisplayName("Entity-level lookups are rejected, not delegated")
	class EntityLookupRejectionTest {

		@Test
		@DisplayName("should reject getInternalPriceIdsForEntity")
		void shouldRejectGetInternalPriceIdsForEntity() {
			final GenericEvitaInternalError exception = assertThrows(
				GenericEvitaInternalError.class,
				() -> PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getInternalPriceIdsForEntity(42)
			);
			assertTrue(exception.getMessage().contains("super price index"));
		}

		@Test
		@DisplayName("should reject getLowestPriceRecordsForEntity")
		void shouldRejectGetLowestPriceRecordsForEntity() {
			final GenericEvitaInternalError exception = assertThrows(
				GenericEvitaInternalError.class,
				() -> PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getLowestPriceRecordsForEntity(42)
			);
			assertTrue(exception.getMessage().contains("super price index"));
		}

		@Test
		@DisplayName("should reject forEachLowestPriceRecordOfEntity through the inherited default")
		void shouldRejectForEachLowestPriceRecordOfEntity() {
			final GenericEvitaInternalError exception = assertThrows(
				GenericEvitaInternalError.class,
				() -> PriceListAndCurrencyPriceRefIndexTest.this.refIndex.forEachLowestPriceRecordOfEntity(
					42, priceRecord -> fail("no price record may be handed out by a reduced index!")
				)
			);
			assertTrue(exception.getMessage().contains("super price index"));
		}

		@Test
		@DisplayName("should reject the lookups for an unknown entity too, rather than reporting no prices")
		void shouldRejectRatherThanReturnNullForUnknownEntity() {
			// the distinction that matters: `null` here would be indistinguishable from "entity 999 has no prices"
			assertThrows(
				GenericEvitaInternalError.class,
				() -> PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getInternalPriceIdsForEntity(999)
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> PriceListAndCurrencyPriceRefIndexTest.this.refIndex.getLowestPriceRecordsForEntity(999)
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

			wireRefIndexToSuperIndex(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(
				10, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);

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
			wireRefIndexToSuperIndex(
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

			wireRefIndexToSuperIndex(
				PriceListAndCurrencyPriceRefIndexTest.this.refIndex,
				PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);
			PriceListAndCurrencyPriceRefIndexTest.this.refIndex.addPrice(
				10, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex
			);

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
				() -> tested.addPrice(
					1, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex
				)
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
				index -> index.addPrice(
					10, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex
				),
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
				index -> index.addPrice(
					10, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex
				),
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
						committed.getIndexedPriceIds().getArray()
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
			tested.addPrice(10, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex);

			assertStateAfterCommit(
				tested,
				index -> index.removePrice(
					10, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex
				),
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
				index -> index.addPrice(
					5, validity, PriceListAndCurrencyPriceRefIndexTest.this.superIndex
				),
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
				index -> index.addPrice(
					10, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex
				),
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
			tested.addPrice(10, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex);

			assertStateAfterRollback(
				tested,
				index -> index.removePrice(
					10, null, PriceListAndCurrencyPriceRefIndexTest.this.superIndex
				),
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

}
