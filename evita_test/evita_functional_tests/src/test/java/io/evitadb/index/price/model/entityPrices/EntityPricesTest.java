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

package io.evitadb.index.price.model.entityPrices;

import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.price.model.priceRecord.PriceRecordInnerRecordSpecific;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EntityPrices} verifying the polymorphic entity price hierarchy including
 * {@link SinglePriceEntityPrices}, {@link MultiplePriceEntityPrices}, and {@link FullBlownEntityPrices}.
 * Covers factory methods, type transitions, price lookup, inner record handling, and edge cases.
 *
 * @author evitaDB
 */
@DisplayName("EntityPrices functionality")
class EntityPricesTest {

	@Nested
	@DisplayName("Empty entity prices")
	class EmptyEntityPricesTest {

		@Test
		@DisplayName("should be empty")
		void shouldBeEmpty() {
			final EntityPrices empty = SinglePriceEntityPrices.EMPTY;

			assertTrue(empty.isEmpty());
		}

		@Test
		@DisplayName("should return zero size")
		void shouldReturnZeroSize() {
			final EntityPrices empty = SinglePriceEntityPrices.EMPTY;

			assertEquals(0, empty.getSize());
		}

		@Test
		@DisplayName("should return empty lowest prices array")
		void shouldReturnEmptyLowestPrices() {
			final EntityPrices empty = SinglePriceEntityPrices.EMPTY;

			final PriceRecordContract[] lowestPrices = empty.getLowestPriceRecords();

			assertNotNull(lowestPrices);
			assertEquals(0, lowestPrices.length);
		}

		@Test
		@DisplayName("should return empty internal price IDs array")
		void shouldReturnEmptyInternalPriceIds() {
			final EntityPrices empty = SinglePriceEntityPrices.EMPTY;

			final int[] internalPriceIds = empty.getInternalPriceIds();

			assertNotNull(internalPriceIds);
			assertEquals(0, internalPriceIds.length);
		}

		@Test
		@DisplayName("should not contain any price record")
		void shouldNotContainAnyPriceRecord() {
			final EntityPrices empty = SinglePriceEntityPrices.EMPTY;

			assertFalse(empty.containsPriceRecord(1));
			assertFalse(empty.containsPriceRecord(0));
			assertFalse(empty.containsPriceRecord(999));
		}

		@Test
		@DisplayName("should not contain any of given prices")
		void shouldNotContainAnyOf() {
			final EntityPrices empty = SinglePriceEntityPrices.EMPTY;
			final PriceRecordContract price = createPlainPrice(1, 100, 120);

			assertFalse(empty.containsAnyOf(new PriceRecordContract[]{price}));
		}
	}

	@Nested
	@DisplayName("Single price entity prices")
	class SinglePriceEntityPricesTest {

		@Test
		@DisplayName("should create single-price entity via factory")
		void shouldCreateSinglePrice() {
			final PriceRecordContract price = createPlainPrice(1, 100, 120);

			final EntityPrices result = EntityPrices.create(price);

			assertInstanceOf(SinglePriceEntityPrices.class, result);
		}

		@Test
		@DisplayName("should return size one")
		void shouldReturnSizeOne() {
			final EntityPrices result = EntityPrices.create(createPlainPrice(1, 100, 120));

			assertEquals(1, result.getSize());
		}

		@Test
		@DisplayName("should return the single price as the lowest price")
		void shouldReturnLowestPrice() {
			final PriceRecordContract price = createPlainPrice(5, 200, 250);

			final EntityPrices result = EntityPrices.create(price);

			final PriceRecordContract[] lowestPrices = result.getLowestPriceRecords();
			assertEquals(1, lowestPrices.length);
			assertEquals(5, lowestPrices[0].priceId());
			assertEquals(200, lowestPrices[0].priceWithTax());
			assertEquals(250, lowestPrices[0].priceWithoutTax());
		}

		@Test
		@DisplayName("should return internal price ID of the single price")
		void shouldReturnInternalPriceId() {
			final PriceRecordContract price = createPlainPrice(7, 300, 350);

			final EntityPrices result = EntityPrices.create(price);

			assertArrayEquals(new int[]{7}, result.getInternalPriceIds());
		}

		@Test
		@DisplayName("should contain price record by price ID")
		void shouldContainPriceRecordByPriceId() {
			final EntityPrices result = EntityPrices.create(createPlainPrice(3, 100, 120));

			assertTrue(result.containsPriceRecord(3));
		}

		@Test
		@DisplayName("should not contain non-existent price record")
		void shouldNotContainNonExistentPriceRecord() {
			final EntityPrices result = EntityPrices.create(createPlainPrice(3, 100, 120));

			assertFalse(result.containsPriceRecord(99));
		}

		@Test
		@DisplayName("should contain inner record when holding inner-record-specific price")
		void shouldContainInnerRecordWhenInnerRecordSpecific() {
			final PriceRecordContract innerPrice = createInnerRecordPrice(1, 42, 100, 120);

			final EntityPrices result = EntityPrices.create(innerPrice);

			assertTrue(result.containsInnerRecord(42));
			assertFalse(result.containsInnerRecord(99));
		}

		@Test
		@DisplayName("should not contain inner record for plain price")
		void shouldNotContainInnerRecordForPlainPrice() {
			final EntityPrices result = EntityPrices.create(createPlainPrice(1, 100, 120));

			assertFalse(result.containsInnerRecord(1));
			assertFalse(result.containsInnerRecord(0));
		}

		@Test
		@DisplayName("should not be empty")
		void shouldNotBeEmpty() {
			final EntityPrices result = EntityPrices.create(createPlainPrice(1, 100, 120));

			assertFalse(result.isEmpty());
		}
	}

	@Nested
	@DisplayName("Multiple price entity prices")
	class MultiplePriceEntityPricesTest {

		@Test
		@DisplayName("should create from two plain prices")
		void shouldCreateFromTwoPlainPrices() {
			final EntityPrices single = EntityPrices.create(createPlainPrice(1, 200, 250));

			final EntityPrices result = EntityPrices.addPriceRecord(single, createPlainPrice(2, 100, 150));

			assertInstanceOf(MultiplePriceEntityPrices.class, result);
		}

		@Test
		@DisplayName("should return correct size")
		void shouldReturnCorrectSize() {
			final EntityPrices ep = EntityPrices.create(createPlainPrice(1, 200, 250));
			final EntityPrices result = EntityPrices.addPriceRecord(ep, createPlainPrice(2, 100, 150));

			assertEquals(2, result.getSize());
		}

		@Test
		@DisplayName("should return lowest price by priceWithoutTax")
		void shouldReturnLowestPriceByWithoutTax() {
			final EntityPrices ep = EntityPrices.create(createPlainPrice(1, 200, 250));
			final EntityPrices result = EntityPrices.addPriceRecord(ep, createPlainPrice(2, 100, 150));

			final PriceRecordContract[] lowestPrices = result.getLowestPriceRecords();
			assertEquals(1, lowestPrices.length);
			// price 2 has priceWithoutTax=150, which is lower than price 1's 250
			assertEquals(2, lowestPrices[0].priceId());
			assertEquals(150, lowestPrices[0].priceWithoutTax());
		}

		@Test
		@DisplayName("should break tie in lowest price by internalPriceId")
		void shouldReturnLowestPriceBreakingTieByInternalPriceId() {
			// both prices have the same priceWithoutTax; tie broken by internalPriceId (ascending)
			final EntityPrices ep = EntityPrices.create(createPlainPrice(5, 100, 200));
			final EntityPrices result = EntityPrices.addPriceRecord(ep, createPlainPrice(2, 100, 200));

			final PriceRecordContract[] lowestPrices = result.getLowestPriceRecords();
			assertEquals(1, lowestPrices.length);
			// internalPriceId 2 < 5, so price with id 2 wins
			assertEquals(2, lowestPrices[0].priceId());
		}

		@Test
		@DisplayName("should return sorted internal price IDs")
		void shouldReturnSortedInternalPriceIds() {
			// add prices in descending order to verify sorting
			final EntityPrices ep = EntityPrices.create(createPlainPrice(10, 100, 120));
			final EntityPrices result = EntityPrices.addPriceRecord(ep, createPlainPrice(3, 200, 250));

			final int[] ids = result.getInternalPriceIds();
			// prices are stored sorted by internalPriceId via PRICE_ID_COMPARATOR
			assertArrayEquals(new int[]{3, 10}, ids);
		}

		@Test
		@DisplayName("should contain price record by price ID")
		void shouldContainPriceRecordByPriceId() {
			final EntityPrices ep = EntityPrices.create(createPlainPrice(1, 100, 120));
			final EntityPrices result = EntityPrices.addPriceRecord(ep, createPlainPrice(2, 200, 250));

			assertTrue(result.containsPriceRecord(1));
			assertTrue(result.containsPriceRecord(2));
		}

		@Test
		@DisplayName("should not contain non-existent price record")
		void shouldNotContainNonExistentPriceRecord() {
			final EntityPrices ep = EntityPrices.create(createPlainPrice(1, 100, 120));
			final EntityPrices result = EntityPrices.addPriceRecord(ep, createPlainPrice(2, 200, 250));

			assertFalse(result.containsPriceRecord(99));
		}

		@Test
		@DisplayName("should not contain any inner record")
		void shouldNotContainAnyInnerRecord() {
			final EntityPrices ep = EntityPrices.create(createPlainPrice(1, 100, 120));
			final EntityPrices result = EntityPrices.addPriceRecord(ep, createPlainPrice(2, 200, 250));

			assertFalse(result.containsInnerRecord(1));
			assertFalse(result.containsInnerRecord(0));
		}

		@Test
		@DisplayName("should not be empty")
		void shouldNotBeEmpty() {
			final EntityPrices ep = EntityPrices.create(createPlainPrice(1, 100, 120));
			final EntityPrices result = EntityPrices.addPriceRecord(ep, createPlainPrice(2, 200, 250));

			assertFalse(result.isEmpty());
		}
	}

	@Nested
	@DisplayName("Full-blown entity prices")
	class FullBlownEntityPricesTest {

		@Test
		@DisplayName("should create from inner record prices")
		void shouldCreateFromInnerRecordPrices() {
			final EntityPrices ep = EntityPrices.create(createInnerRecordPrice(1, 1, 100, 120));

			final EntityPrices result = EntityPrices.addPriceRecord(
				ep, createInnerRecordPrice(2, 1, 200, 250)
			);

			assertInstanceOf(FullBlownEntityPrices.class, result);
		}

		@Test
		@DisplayName("should return correct size")
		void shouldReturnCorrectSize() {
			final EntityPrices ep1 = EntityPrices.create(createInnerRecordPrice(1, 1, 123, 155));
			final EntityPrices ep2 = EntityPrices.addPriceRecord(
				ep1, createInnerRecordPrice(2, 1, 100, 200)
			);
			final EntityPrices ep3 = EntityPrices.addPriceRecord(
				ep2, createInnerRecordPrice(3, 2, 800, 850)
			);
			final EntityPrices result = EntityPrices.addPriceRecord(
				ep3, createInnerRecordPrice(4, 2, 500, 590)
			);

			assertEquals(4, result.getSize());
		}

		@Test
		@DisplayName("should return lowest price per inner record group")
		void shouldReturnLowestPricePerInnerRecordGroup() {
			final EntityPrices ep1 = EntityPrices.create(createInnerRecordPrice(1, 1, 123, 155));
			final EntityPrices ep2 = EntityPrices.addPriceRecord(
				ep1, createInnerRecordPrice(2, 1, 100, 200)
			);
			final EntityPrices ep3 = EntityPrices.addPriceRecord(
				ep2, createInnerRecordPrice(3, 2, 800, 850)
			);
			final EntityPrices result = EntityPrices.addPriceRecord(
				ep3, createInnerRecordPrice(4, 2, 500, 590)
			);

			final PriceRecordContract[] lowestPrices = result.getLowestPriceRecords();
			// two inner record groups (1 and 2), one lowest price per group
			assertEquals(2, lowestPrices.length);

			final int[] lowestPriceIds = Arrays.stream(lowestPrices)
				.mapToInt(PriceRecordContract::priceId)
				.toArray();
			// group 1: price 1 (priceWithoutTax=155) < price 2 (200) => price 1 wins
			// group 2: price 4 (priceWithoutTax=590) < price 3 (850) => price 4 wins
			// sorted by internalPriceId ascending
			assertArrayEquals(new int[]{1, 4}, lowestPriceIds);
		}

		@Test
		@DisplayName("should return sorted internal price IDs")
		void shouldReturnSortedInternalPriceIds() {
			final EntityPrices ep1 = EntityPrices.create(createInnerRecordPrice(1, 1, 123, 155));
			final EntityPrices ep2 = EntityPrices.addPriceRecord(
				ep1, createInnerRecordPrice(2, 1, 100, 200)
			);
			final EntityPrices ep3 = EntityPrices.addPriceRecord(
				ep2, createInnerRecordPrice(3, 2, 800, 850)
			);
			final EntityPrices result = EntityPrices.addPriceRecord(
				ep3, createInnerRecordPrice(4, 2, 500, 590)
			);

			assertArrayEquals(new int[]{1, 2, 3, 4}, result.getInternalPriceIds());
		}

		@Test
		@DisplayName("should contain price record by price ID")
		void shouldContainPriceRecordByPriceId() {
			final EntityPrices ep1 = EntityPrices.create(createInnerRecordPrice(1, 1, 100, 120));
			final EntityPrices result = EntityPrices.addPriceRecord(
				ep1, createInnerRecordPrice(2, 2, 200, 250)
			);

			assertTrue(result.containsPriceRecord(1));
			assertTrue(result.containsPriceRecord(2));
		}

		@Test
		@DisplayName("should not contain non-existent price record")
		void shouldNotContainNonExistentPriceRecord() {
			final EntityPrices ep1 = EntityPrices.create(createInnerRecordPrice(1, 1, 100, 120));
			final EntityPrices result = EntityPrices.addPriceRecord(
				ep1, createInnerRecordPrice(2, 2, 200, 250)
			);

			assertFalse(result.containsPriceRecord(99));
		}

		@Test
		@DisplayName("should contain inner record by ID")
		void shouldContainInnerRecord() {
			final EntityPrices ep1 = EntityPrices.create(createInnerRecordPrice(1, 10, 100, 120));
			final EntityPrices result = EntityPrices.addPriceRecord(
				ep1, createInnerRecordPrice(2, 20, 200, 250)
			);

			assertTrue(result.containsInnerRecord(10));
			assertTrue(result.containsInnerRecord(20));
		}

		@Test
		@DisplayName("should not contain non-existent inner record")
		void shouldNotContainNonExistentInnerRecord() {
			final EntityPrices ep1 = EntityPrices.create(createInnerRecordPrice(1, 10, 100, 120));
			final EntityPrices result = EntityPrices.addPriceRecord(
				ep1, createInnerRecordPrice(2, 20, 200, 250)
			);

			assertFalse(result.containsInnerRecord(99));
		}

		@Test
		@DisplayName("should not be empty")
		void shouldNotBeEmpty() {
			final EntityPrices ep1 = EntityPrices.create(createInnerRecordPrice(1, 1, 100, 120));
			final EntityPrices result = EntityPrices.addPriceRecord(
				ep1, createInnerRecordPrice(2, 2, 200, 250)
			);

			assertFalse(result.isEmpty());
		}
	}

	@Nested
	@DisplayName("Type transitions")
	class TypeTransitionsTest {

		@Test
		@DisplayName("should create SinglePriceEntityPrices from factory")
		void shouldCreateSingleFromFactory() {
			final EntityPrices result = EntityPrices.create(createPlainPrice(1, 100, 120));

			assertInstanceOf(SinglePriceEntityPrices.class, result);
		}

		@Test
		@DisplayName("should transition to MultiplePriceEntityPrices when adding plain price")
		void shouldTransitionToMultipleWhenAddingPlainPrice() {
			final EntityPrices single = EntityPrices.create(createPlainPrice(1, 100, 120));

			final EntityPrices result = EntityPrices.addPriceRecord(
				single, createPlainPrice(2, 200, 250)
			);

			assertInstanceOf(MultiplePriceEntityPrices.class, result);
		}

		@Test
		@DisplayName("should transition to FullBlownEntityPrices when adding inner-record price")
		void shouldTransitionToFullBlownWhenAddingInnerRecordPrice() {
			final EntityPrices single = EntityPrices.create(createPlainPrice(1, 100, 120));

			final EntityPrices result = EntityPrices.addPriceRecord(
				single, createInnerRecordPrice(2, 5, 200, 250)
			);

			assertInstanceOf(FullBlownEntityPrices.class, result);
		}

		@Test
		@DisplayName("should transition to FullBlownEntityPrices when original is inner-record-specific")
		void shouldTransitionToFullBlownWhenOriginalIsInnerRecordSpecific() {
			final EntityPrices single = EntityPrices.create(createInnerRecordPrice(1, 5, 100, 120));

			final EntityPrices result = EntityPrices.addPriceRecord(
				single, createPlainPrice(2, 200, 250)
			);

			assertInstanceOf(FullBlownEntityPrices.class, result);
		}

		@Test
		@DisplayName("should transition back to SinglePriceEntityPrices when removing from two")
		void shouldTransitionBackToSingleWhenRemovingFromTwo() {
			final EntityPrices single = EntityPrices.create(createPlainPrice(1, 100, 120));
			final EntityPrices multiple = EntityPrices.addPriceRecord(
				single, createPlainPrice(2, 200, 250)
			);

			final EntityPrices result = EntityPrices.removePrice(
				multiple, createPlainPrice(2, 200, 250)
			);

			assertInstanceOf(SinglePriceEntityPrices.class, result);
			assertEquals(1, result.getSize());
			assertEquals(1, result.getLowestPriceRecords()[0].priceId());
		}

		@Test
		@DisplayName("should transition to empty when removing last price")
		void shouldTransitionToEmptyWhenRemovingLastPrice() {
			final EntityPrices single = EntityPrices.create(createPlainPrice(1, 100, 120));

			final EntityPrices result = EntityPrices.removePrice(
				single, createPlainPrice(1, 100, 120)
			);

			assertTrue(result.isEmpty());
			assertEquals(0, result.getSize());
		}

		@Test
		@DisplayName("should stay FullBlownEntityPrices when removing from 3+ inner-record prices")
		void shouldStayFullBlownWhenRemovingFromThreePlusInnerRecord() {
			final EntityPrices ep1 = EntityPrices.create(createInnerRecordPrice(1, 1, 100, 120));
			final EntityPrices ep2 = EntityPrices.addPriceRecord(
				ep1, createInnerRecordPrice(2, 1, 200, 250)
			);
			final EntityPrices ep3 = EntityPrices.addPriceRecord(
				ep2, createInnerRecordPrice(3, 2, 300, 350)
			);

			final EntityPrices result = EntityPrices.removePrice(
				ep3, createInnerRecordPrice(2, 1, 200, 250)
			);

			assertInstanceOf(FullBlownEntityPrices.class, result);
			assertEquals(2, result.getSize());
		}

		@Test
		@DisplayName("should stay MultiplePriceEntityPrices when removing from 3+ plain prices")
		void shouldStayMultipleWhenRemovingFromThreePlusPlain() {
			final EntityPrices ep1 = EntityPrices.create(createPlainPrice(1, 100, 120));
			final EntityPrices ep2 = EntityPrices.addPriceRecord(ep1, createPlainPrice(2, 200, 250));
			final EntityPrices ep3 = EntityPrices.addPriceRecord(ep2, createPlainPrice(3, 300, 350));

			final EntityPrices result = EntityPrices.removePrice(ep3, createPlainPrice(2, 200, 250));

			assertInstanceOf(MultiplePriceEntityPrices.class, result);
			assertEquals(2, result.getSize());
		}
	}

	@Nested
	@DisplayName("containsAnyOf")
	class ContainsAnyOfTest {

		@Test
		@DisplayName("should return false for empty input array")
		void shouldReturnFalseForEmptyInputArray() {
			final EntityPrices ep = EntityPrices.create(createPlainPrice(1, 100, 120));

			assertFalse(ep.containsAnyOf(new PriceRecordContract[0]));
		}

		@Test
		@DisplayName("should return true when first element matches")
		void shouldReturnTrueWhenFirstElementMatches() {
			final PriceRecordContract price1 = createInnerRecordPrice(1, 1, 100, 120);
			final PriceRecordContract price2 = createInnerRecordPrice(2, 1, 200, 250);
			final PriceRecordContract price3 = createInnerRecordPrice(3, 2, 300, 350);
			final EntityPrices ep1 = EntityPrices.create(price1);
			final EntityPrices ep2 = EntityPrices.addPriceRecord(ep1, price2);
			final EntityPrices ep3 = EntityPrices.addPriceRecord(ep2, price3);

			// price1 is in the entity, so the first element matches
			assertTrue(ep3.containsAnyOf(new PriceRecordContract[]{price1}));
		}

		@Test
		@DisplayName("should return true when last element matches")
		void shouldReturnTrueWhenLastElementMatches() {
			final PriceRecordContract price1 = createInnerRecordPrice(1, 1, 100, 120);
			final PriceRecordContract price2 = createInnerRecordPrice(2, 1, 200, 250);
			final PriceRecordContract price5 = createInnerRecordPrice(5, 2, 300, 350);
			// non-existent with internalPriceId between existing ones (sorted order maintained)
			final PriceRecordContract nonExistent = createInnerRecordPrice(3, 5, 500, 600);
			final EntityPrices ep1 = EntityPrices.create(price1);
			final EntityPrices ep2 = EntityPrices.addPriceRecord(ep1, price2);
			final EntityPrices ep3 = EntityPrices.addPriceRecord(ep2, price5);

			// input sorted by internalPriceId: nonExistent(3) < price5(5)
			assertTrue(ep3.containsAnyOf(new PriceRecordContract[]{nonExistent, price5}));
		}

		@Test
		@DisplayName("should return false when none match")
		void shouldReturnFalseWhenNoneMatch() {
			final PriceRecordContract price1 = createInnerRecordPrice(1, 1, 100, 120);
			final PriceRecordContract price2 = createInnerRecordPrice(2, 1, 200, 250);
			final PriceRecordContract nonExistent1 = createInnerRecordPrice(50, 5, 500, 600);
			final PriceRecordContract nonExistent2 = createInnerRecordPrice(60, 6, 600, 700);
			final EntityPrices ep1 = EntityPrices.create(price1);
			final EntityPrices ep2 = EntityPrices.addPriceRecord(ep1, price2);

			assertFalse(ep2.containsAnyOf(new PriceRecordContract[]{nonExistent1, nonExistent2}));
		}

		@Test
		@DisplayName("should work with sparse internal price IDs")
		void shouldWorkWithSparseInternalPriceIds() {
			// create prices with non-contiguous internalPriceIds
			final PriceRecordContract price10 = createPlainPrice(10, 100, 120);
			final PriceRecordContract price100 = createPlainPrice(100, 200, 250);
			final PriceRecordContract price1000 = createPlainPrice(1000, 300, 350);
			final EntityPrices ep1 = EntityPrices.create(price10);
			final EntityPrices ep2 = EntityPrices.addPriceRecord(ep1, price100);
			final EntityPrices ep3 = EntityPrices.addPriceRecord(ep2, price1000);

			assertTrue(ep3.containsAnyOf(new PriceRecordContract[]{price100}));
			assertTrue(ep3.containsAnyOf(new PriceRecordContract[]{price1000}));

			final PriceRecordContract nonExistent = createPlainPrice(500, 400, 450);
			assertFalse(ep3.containsAnyOf(new PriceRecordContract[]{nonExistent}));
		}

		@Test
		@DisplayName("should work on single-price entity")
		void shouldWorkOnSinglePriceEntity() {
			final PriceRecordContract price = createPlainPrice(5, 100, 120);
			final EntityPrices ep = EntityPrices.create(price);

			assertTrue(ep.containsAnyOf(new PriceRecordContract[]{price}));

			final PriceRecordContract other = createPlainPrice(99, 200, 250);
			assertFalse(ep.containsAnyOf(new PriceRecordContract[]{other}));
		}
	}

	@Nested
	@DisplayName("Error handling")
	class ErrorHandlingTest {

		@Test
		@DisplayName("should throw when removing non-existent price from single-price entity")
		void shouldThrowWhenRemovingNonExistentPriceFromSingle() {
			// create a two-price entity, then remove one to get SinglePriceEntityPrices
			// from the removePrice size < 3 branch
			final EntityPrices single = EntityPrices.create(createPlainPrice(1, 100, 120));
			final EntityPrices two = EntityPrices.addPriceRecord(
				single, createPlainPrice(2, 200, 250)
			);

			// removing price id 99 (non-existent) from 2-element MultiplePriceEntityPrices
			// goes through computePricesRemoving which returns array without the element
			// but since price 99 is not in the array, the result still has 2 elements
			// and Assert.isPremiseValid("Expected single result!") fails
			final PriceRecordContract nonExistent = createPlainPrice(99, 300, 350);
			assertThrows(
				Exception.class,
				() -> EntityPrices.removePrice(two, nonExistent)
			);
		}
	}

	/**
	 * Creates a plain {@link PriceRecord} without inner record ID.
	 *
	 * @param priceId        serves as both priceId and internalPriceId
	 * @param priceWithTax   the price amount including tax
	 * @param priceWithoutTax the price amount excluding tax
	 * @return new price record for entity primary key 1
	 */
	@Nonnull
	private static PriceRecordContract createPlainPrice(int priceId, int priceWithTax, int priceWithoutTax) {
		return new PriceRecord(priceId, priceId, 1, priceWithTax, priceWithoutTax);
	}

	/**
	 * Creates an inner-record-specific {@link PriceRecordInnerRecordSpecific}.
	 *
	 * @param priceId        serves as both priceId and internalPriceId
	 * @param innerRecordId  the inner record group identifier
	 * @param priceWithTax   the price amount including tax
	 * @param priceWithoutTax the price amount excluding tax
	 * @return new inner-record-specific price record for entity primary key 1
	 */
	@Nonnull
	private static PriceRecordContract createInnerRecordPrice(
		int priceId,
		int innerRecordId,
		int priceWithTax,
		int priceWithoutTax
	) {
		return new PriceRecordInnerRecordSpecific(
			priceId, priceId, 1, innerRecordId, priceWithTax, priceWithoutTax
		);
	}
}
