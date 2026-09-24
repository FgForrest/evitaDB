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

package io.evitadb.core.query.algebra.price.filteredPriceRecords;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords.PriceRecordLookup;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.LazyEvaluatedEntityPriceRecords.PriceRecordIterator;
import io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.price.model.priceRecord.PriceRecordInnerRecordSpecific;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.PRICE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies the lookup {@link LazyEvaluatedEntityPriceRecords} hands out: it keeps no prices of its own and answers
 * every entity from the price indexes it was built over, taking the first index that holds the entity and consulting
 * no further one.
 *
 * The indexes here are real {@link PriceListAndCurrencyPriceSuperIndex} instances rather than stand-ins, because the
 * behaviour under test is precisely which of them is asked and what their entity-to-prices mapping streams back.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(ENGINE)
@Tag(PRICE)
@DisplayName("LazyEvaluatedEntityPriceRecords functionality")
class LazyEvaluatedEntityPriceRecordsTest {
	private static final Currency CURRENCY_CZK = Currency.getInstance("CZK");
	private static final PriceIndexKey BASIC_PRICE_INDEX_KEY =
		new PriceIndexKey("basic", CURRENCY_CZK, PriceInnerRecordHandling.NONE);
	private static final PriceIndexKey REFERENCE_PRICE_INDEX_KEY =
		new PriceIndexKey("reference", CURRENCY_CZK, PriceInnerRecordHandling.NONE);
	/**
	 * Primary key of the entity every test looks up. The second argument of
	 * {@link PriceRecordLookup#forEachPriceOfEntity(int, int, java.util.function.Consumer)} is only a scan hint, so it
	 * is passed the very same key throughout.
	 */
	private static final int ENTITY_PK = 42;

	@Nested
	@DisplayName("Index selection")
	class IndexSelectionTest {

		/**
		 * The entity is priced in both indexes, so the answer proves which one was consulted: only the first index's
		 * record may arrive, and the second index must not contribute a second price.
		 */
		@Test
		@DisplayName("should stream from the first index holding the entity and stop there")
		void shouldStreamFromTheFirstIndexHoldingTheEntity() {
			final PriceRecordContract firstIndexPrice = createPrice(10, ENTITY_PK);
			final PriceRecordContract secondIndexPrice = createPrice(20, ENTITY_PK);
			final LazyEvaluatedEntityPriceRecords tested = new LazyEvaluatedEntityPriceRecords(
				createIndex(BASIC_PRICE_INDEX_KEY, firstIndexPrice),
				createIndex(REFERENCE_PRICE_INDEX_KEY, secondIndexPrice)
			);

			final List<PriceRecordContract> streamed = new ArrayList<>();
			final boolean found = tested.getPriceRecordsLookup()
				.forEachPriceOfEntity(ENTITY_PK, ENTITY_PK, streamed::add);

			assertTrue(found);
			assertArrayEquals(
				new PriceRecordContract[]{firstIndexPrice},
				streamed.toArray(PriceRecordContract[]::new)
			);
		}

		@Test
		@DisplayName("should skip an index that does not hold the entity")
		void shouldSkipAnIndexThatDoesNotHoldTheEntity() {
			final PriceRecordContract secondIndexPrice = createPrice(20, ENTITY_PK);
			final LazyEvaluatedEntityPriceRecords tested = new LazyEvaluatedEntityPriceRecords(
				createIndex(BASIC_PRICE_INDEX_KEY, createPrice(10, 99)),
				createIndex(REFERENCE_PRICE_INDEX_KEY, secondIndexPrice)
			);

			final List<PriceRecordContract> streamed = new ArrayList<>();
			final boolean found = tested.getPriceRecordsLookup()
				.forEachPriceOfEntity(ENTITY_PK, ENTITY_PK, streamed::add);

			assertTrue(found);
			assertArrayEquals(
				new PriceRecordContract[]{secondIndexPrice},
				streamed.toArray(PriceRecordContract[]::new)
			);
		}

		@Test
		@DisplayName("should return a lookup answering over the indexes the holder was built with")
		void shouldReturnALookupOverTheSameIndexes() {
			final PriceRecordContract price = createPrice(10, ENTITY_PK);
			final LazyEvaluatedEntityPriceRecords tested = new LazyEvaluatedEntityPriceRecords(
				createIndex(BASIC_PRICE_INDEX_KEY, price)
			);

			final PriceRecordIterator lookup = tested.getPriceRecordsLookup();

			assertNotNull(lookup);
			final List<PriceRecordContract> streamed = new ArrayList<>();
			assertTrue(lookup.forEachPriceOfEntity(ENTITY_PK, ENTITY_PK, streamed::add));
			assertArrayEquals(new PriceRecordContract[]{price}, streamed.toArray(PriceRecordContract[]::new));
		}
	}

	@Nested
	@DisplayName("Streaming of an entity's prices")
	class StreamingTest {

		/**
		 * The entity's two prices sit in different inner-record groups, so its holder reports one lowest price per
		 * group and the lookup has to hand out both, in the order the array-returning accessor would.
		 */
		@Test
		@DisplayName("should stream every lowest price of the entity in index order")
		void shouldStreamEveryLowestPriceOfTheEntity() {
			final PriceListAndCurrencyPriceSuperIndex index = createIndex(
				BASIC_PRICE_INDEX_KEY,
				createInnerRecordSpecificPrice(10, ENTITY_PK, 1),
				createInnerRecordSpecificPrice(20, ENTITY_PK, 2)
			);
			final LazyEvaluatedEntityPriceRecords tested = new LazyEvaluatedEntityPriceRecords(index);

			final List<PriceRecordContract> streamed = new ArrayList<>();
			final boolean found = tested.getPriceRecordsLookup()
				.forEachPriceOfEntity(ENTITY_PK, ENTITY_PK, streamed::add);

			assertTrue(found);
			assertEquals(2, streamed.size());
			assertArrayEquals(
				index.getLowestPriceRecordsForEntity(ENTITY_PK),
				streamed.toArray(PriceRecordContract[]::new)
			);
		}
	}

	@Nested
	@DisplayName("Entity absent from every index")
	class NoMatchingIndexTest {

		@Test
		@DisplayName("should report false and stream nothing when no index holds the entity")
		void shouldReportFalseAndStreamNothingWhenNoIndexHoldsTheEntity() {
			final LazyEvaluatedEntityPriceRecords tested = new LazyEvaluatedEntityPriceRecords(
				createIndex(BASIC_PRICE_INDEX_KEY, createPrice(10, 99)),
				createIndex(REFERENCE_PRICE_INDEX_KEY, createPrice(20, 100))
			);

			final boolean found = tested.getPriceRecordsLookup().forEachPriceOfEntity(
				ENTITY_PK, ENTITY_PK, priceRecord -> fail("no index holds a price of entity " + ENTITY_PK + "!")
			);

			assertFalse(found);
		}

		@Test
		@DisplayName("should report false for a holder built over no index at all")
		void shouldReportFalseForAnEmptyIndexArray() {
			final LazyEvaluatedEntityPriceRecords tested = new LazyEvaluatedEntityPriceRecords();

			final boolean found = tested.getPriceRecordsLookup().forEachPriceOfEntity(
				ENTITY_PK, ENTITY_PK, priceRecord -> fail("there is no index to hand out a price record!")
			);

			assertFalse(found);
		}
	}

	/**
	 * Creates a super index of the given price-list / currency combination holding the passed price records.
	 *
	 * @param priceIndexKey the price-list, currency and inner record handling the index answers for
	 * @param prices        price records to index, at most one per price id of any single entity
	 * @return new super index holding exactly those prices, none of them validity-bound
	 */
	@Nonnull
	private static PriceListAndCurrencyPriceSuperIndex createIndex(
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull PriceRecordContract... prices
	) {
		final PriceListAndCurrencyPriceSuperIndex index = new PriceListAndCurrencyPriceSuperIndex(priceIndexKey);
		for (final PriceRecordContract price : prices) {
			index.addPrice(price, null);
		}
		return index;
	}

	/**
	 * Creates a plain {@link PriceRecord} of the given entity with fixed price values of 12100 (with tax) and
	 * 10000 (without tax).
	 *
	 * @param internalPriceId serves as both internalPriceId and priceId
	 * @param entityPrimaryKey primary key of the entity the price belongs to
	 * @return new price record
	 */
	@Nonnull
	private static PriceRecordContract createPrice(int internalPriceId, int entityPrimaryKey) {
		return new PriceRecord(internalPriceId, internalPriceId, entityPrimaryKey, 12100, 10000);
	}

	/**
	 * Creates a {@link PriceRecordInnerRecordSpecific} belonging to the given inner record group, with the same fixed
	 * price values as {@link #createPrice(int, int)}.
	 *
	 * @param internalPriceId  serves as both internalPriceId and priceId
	 * @param entityPrimaryKey primary key of the entity the price belongs to
	 * @param innerRecordId    the inner record group the price is the candidate lowest price of
	 * @return new inner-record-specific price record
	 */
	@Nonnull
	private static PriceRecordContract createInnerRecordSpecificPrice(
		int internalPriceId,
		int entityPrimaryKey,
		int innerRecordId
	) {
		return new PriceRecordInnerRecordSpecific(
			internalPriceId, internalPriceId, entityPrimaryKey, innerRecordId, 12100, 10000
		);
	}
}
