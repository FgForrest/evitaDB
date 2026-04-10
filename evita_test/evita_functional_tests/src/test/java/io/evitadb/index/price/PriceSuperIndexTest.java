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

import com.carrotsearch.hppc.IntIntHashMap;
import com.carrotsearch.hppc.IntIntMap;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex.PriceListAndCurrencyPriceIndexTerminated;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.price.model.priceRecord.PriceRecordInnerRecordSpecific;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This class verifies contract of {@link PriceSuperIndex}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("PriceSuperIndex functionality")
class PriceSuperIndexTest implements TimeBoundedTestSupport {
	private static final Currency CURRENCY_CZK = Currency.getInstance("CZK");
	private static final String PRICE_LIST = "basic";
	private final AtomicInteger priceIdSequence = new AtomicInteger(0);
	private final PriceSuperIndex priceIndex = new PriceSuperIndex();
	private final IntIntMap priceToInternalId = new IntIntHashMap();

	@Nested
	@DisplayName("Price add operations")
	class PriceAddTest {

		@Test
		@DisplayName("add two standard prices with NONE handling")
		void shouldAddStandardPrice() {
			addStandardPrices();

			final PriceListAndCurrencyPriceSuperIndex priceAndCurrencyIndex = PriceSuperIndexTest.this.priceIndex.getPriceIndex(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE);
			assertNotNull(priceAndCurrencyIndex);
			assertFalse(priceAndCurrencyIndex.isEmpty());

			final PriceRecordContract[] priceRecords = priceAndCurrencyIndex.getPriceRecords();
			assertEquals(2, priceRecords.length);

			final PriceRecordInnerRecordSpecific price1 = new PriceRecordInnerRecordSpecific(1, 10, 1, 20, 1210, 1000);
			final PriceRecordInnerRecordSpecific price2 = new PriceRecordInnerRecordSpecific(2, 11, 2, 21, 2000, 999);
			assertEquals(price1, priceRecords[0]);
			assertEquals(price2, priceRecords[1]);

			assertArrayEquals(new PriceRecordContract[]{price1}, priceAndCurrencyIndex.getLowestPriceRecordsForEntity(priceRecords[0].entityPrimaryKey()));

			assertArrayEquals(new int[]{1, 2}, priceAndCurrencyIndex.getIndexedPriceEntityIds().getArray());
			assertArrayEquals(new int[]{1, 2}, priceAndCurrencyIndex.getIndexedRecordIdsValidInFormula(OffsetDateTime.now()).compute().getArray());
			assertArrayEquals(new int[]{1, 2}, priceAndCurrencyIndex.createPriceIndexFormulaWithAllRecords().compute().getArray());
		}

		@Test
		@DisplayName("add standard prices with validity ranges")
		void shouldAddStandardPriceWithValidity() {
			final DateTimeRange validity1 = DateTimeRange.between(OffsetDateTime.now().minusMinutes(10), OffsetDateTime.now().plusMinutes(10));
			final DateTimeRange validity2 = DateTimeRange.between(OffsetDateTime.now().plusHours(1).minusMinutes(10), OffsetDateTime.now().plusHours(1).plusMinutes(10));

			PriceSuperIndexTest.this.priceIndex.addPrice(null, 1, 1, new PriceKey(10, PRICE_LIST, CURRENCY_CZK), PriceInnerRecordHandling.NONE, null, validity1, 1000, 1210);
			PriceSuperIndexTest.this.priceIndex.addPrice(null, 2, 2, new PriceKey(11, PRICE_LIST, CURRENCY_CZK), PriceInnerRecordHandling.NONE, null, validity2, 999, 2000);
			final PriceListAndCurrencyPriceSuperIndex priceAndCurrencyIndex = PriceSuperIndexTest.this.priceIndex.getPriceIndex(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE);
			assertNotNull(priceAndCurrencyIndex);
			assertFalse(priceAndCurrencyIndex.isEmpty());

			assertArrayEquals(new int[]{1}, priceAndCurrencyIndex.getIndexedRecordIdsValidInFormula(OffsetDateTime.now()).compute().getArray());
			assertArrayEquals(new int[]{2}, priceAndCurrencyIndex.getIndexedRecordIdsValidInFormula(OffsetDateTime.now().plusHours(1)).compute().getArray());
		}

		@Test
		@DisplayName("add two prices with LOWEST_PRICE handling for same entity")
		void shouldAddLowestPricePrice() {
			addLowestPricePrices();

			final PriceListAndCurrencyPriceSuperIndex priceAndCurrencyIndex = PriceSuperIndexTest.this.priceIndex.getPriceIndex(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.LOWEST_PRICE);
			assertNotNull(priceAndCurrencyIndex);
			assertFalse(priceAndCurrencyIndex.isEmpty());

			final PriceRecordContract[] priceRecords = priceAndCurrencyIndex.getPriceRecords();
			assertEquals(2, priceRecords.length);

			final PriceRecordInnerRecordSpecific price1 = new PriceRecordInnerRecordSpecific(1, 10, 1, 20, 1210, 1000);
			final PriceRecordInnerRecordSpecific price2 = new PriceRecordInnerRecordSpecific(2, 11, 1, 21, 2000, 999);
			assertEquals(price1, priceRecords[0]);
			assertEquals(price2, priceRecords[1]);

			assertArrayEquals(new PriceRecordContract[]{price1, price2}, priceAndCurrencyIndex.getLowestPriceRecordsForEntity(1));

			assertArrayEquals(new int[]{1}, priceAndCurrencyIndex.getIndexedPriceEntityIds().getArray());
			assertArrayEquals(new int[]{1, 2}, priceAndCurrencyIndex.getIndexedRecordIdsValidInFormula(OffsetDateTime.now()).compute().getArray());
			assertArrayEquals(new int[]{1}, priceAndCurrencyIndex.createPriceIndexFormulaWithAllRecords().compute().getArray());
		}

		@Test
		@DisplayName("add two prices with SUM handling for same entity")
		void shouldAddSumPrice() {
			addSumPrices();

			final PriceListAndCurrencyPriceSuperIndex priceAndCurrencyIndex = PriceSuperIndexTest.this.priceIndex.getPriceIndex(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.SUM);
			assertNotNull(priceAndCurrencyIndex);
			assertFalse(priceAndCurrencyIndex.isEmpty());

			final PriceRecordContract[] priceRecords = priceAndCurrencyIndex.getPriceRecords();
			assertEquals(2, priceRecords.length);

			final PriceRecordInnerRecordSpecific price1 = new PriceRecordInnerRecordSpecific(1, 10, 1, 20, 1210, 1000);
			final PriceRecordInnerRecordSpecific price2 = new PriceRecordInnerRecordSpecific(2, 11, 1, 21, 2000, 999);
			assertEquals(price1, priceRecords[0]);
			assertEquals(price2, priceRecords[1]);

			assertArrayEquals(new PriceRecordContract[]{price1, price2}, priceAndCurrencyIndex.getLowestPriceRecordsForEntity(1));

			assertArrayEquals(new int[]{1}, priceAndCurrencyIndex.getIndexedPriceEntityIds().getArray());
			assertArrayEquals(new int[]{1, 2}, priceAndCurrencyIndex.getIndexedRecordIdsValidInFormula(OffsetDateTime.now()).compute().getArray());
			assertArrayEquals(new int[]{1}, priceAndCurrencyIndex.createPriceIndexFormulaWithAllRecords().compute().getArray());
		}
	}

	@Nested
	@DisplayName("Price remove operations")
	class PriceRemoveTest {

		@Test
		@DisplayName("remove standard prices one by one until sub-index is gone")
		void shouldRemoveStandardPrice() {
			addStandardPrices();

			PriceSuperIndexTest.this.priceIndex.priceRemove(null, 1, 1, new PriceKey(10, PRICE_LIST, CURRENCY_CZK), PriceInnerRecordHandling.NONE, 1, null, 1000, 1210);

			final PriceListAndCurrencyPriceSuperIndex priceAndCurrencyIndex = PriceSuperIndexTest.this.priceIndex.getPriceIndex(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE);
			assertNotNull(priceAndCurrencyIndex);
			assertFalse(priceAndCurrencyIndex.isEmpty());

			final PriceRecordContract[] priceRecords = priceAndCurrencyIndex.getPriceRecords();
			assertEquals(1, priceRecords.length);

			final PriceRecordInnerRecordSpecific price = new PriceRecordInnerRecordSpecific(2, 11, 2, 21, 2000, 999);
			assertEquals(price, priceRecords[0]);

			assertArrayEquals(new PriceRecordContract[]{price}, priceAndCurrencyIndex.getLowestPriceRecordsForEntity(priceRecords[0].entityPrimaryKey()));

			assertArrayEquals(new int[]{2}, priceAndCurrencyIndex.getIndexedPriceEntityIds().getArray());
			assertArrayEquals(new int[]{2}, priceAndCurrencyIndex.getIndexedRecordIdsValidInFormula(OffsetDateTime.now()).compute().getArray());
			assertArrayEquals(new int[]{2}, priceAndCurrencyIndex.createPriceIndexFormulaWithAllRecords().compute().getArray());

			PriceSuperIndexTest.this.priceIndex.priceRemove(null, 2, 2, new PriceKey(11, PRICE_LIST, CURRENCY_CZK), PriceInnerRecordHandling.NONE, 2, null, 999, 2000);
			assertNull(PriceSuperIndexTest.this.priceIndex.getPriceIndex(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE));
		}

		@Test
		@DisplayName("remove LOWEST_PRICE prices one by one until sub-index is gone")
		void shouldRemoveFirstOccurrencePrice() {
			addLowestPricePrices();

			PriceSuperIndexTest.this.priceIndex.priceRemove(null, 1, 1, new PriceKey(10, PRICE_LIST, CURRENCY_CZK), PriceInnerRecordHandling.LOWEST_PRICE, 1, null, 1000, 1210);

			final PriceListAndCurrencyPriceSuperIndex priceAndCurrencyIndex = PriceSuperIndexTest.this.priceIndex.getPriceIndex(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.LOWEST_PRICE);
			assertNotNull(priceAndCurrencyIndex);
			assertFalse(priceAndCurrencyIndex.isEmpty());

			final PriceRecordContract[] priceRecords = priceAndCurrencyIndex.getPriceRecords();
			assertEquals(1, priceRecords.length);

			final PriceRecordInnerRecordSpecific price = new PriceRecordInnerRecordSpecific(2, 11, 2, 21, 2000, 999);
			assertEquals(price, priceRecords[0]);

			assertArrayEquals(new PriceRecordContract[]{price}, priceAndCurrencyIndex.getLowestPriceRecordsForEntity(priceRecords[0].entityPrimaryKey()));

			assertArrayEquals(new int[]{1}, priceAndCurrencyIndex.getIndexedPriceEntityIds().getArray());
			assertArrayEquals(new int[]{2}, priceAndCurrencyIndex.getIndexedRecordIdsValidInFormula(OffsetDateTime.now()).compute().getArray());
			assertArrayEquals(new int[]{1}, priceAndCurrencyIndex.createPriceIndexFormulaWithAllRecords().compute().getArray());

			PriceSuperIndexTest.this.priceIndex.priceRemove(null, 1, 2, new PriceKey(11, PRICE_LIST, CURRENCY_CZK), PriceInnerRecordHandling.LOWEST_PRICE, 2, null, 999, 2000);
			assertNull(PriceSuperIndexTest.this.priceIndex.getPriceIndex(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.LOWEST_PRICE));
		}

		@Test
		@DisplayName("remove SUM prices one by one until sub-index is gone")
		void shouldRemoveSumPrice() {
			addSumPrices();

			PriceSuperIndexTest.this.priceIndex.priceRemove(null, 1, 1, new PriceKey(10, PRICE_LIST, CURRENCY_CZK), PriceInnerRecordHandling.SUM, 1, null, 1000, 1210);

			final PriceListAndCurrencyPriceSuperIndex priceAndCurrencyIndex = PriceSuperIndexTest.this.priceIndex.getPriceIndex(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.SUM);
			assertNotNull(priceAndCurrencyIndex);
			assertFalse(priceAndCurrencyIndex.isEmpty());

			final PriceRecordContract[] priceRecords = priceAndCurrencyIndex.getPriceRecords();
			assertEquals(1, priceRecords.length);

			final PriceRecordInnerRecordSpecific price = new PriceRecordInnerRecordSpecific(2, 11, 2, 21, 2000, 999);
			assertEquals(price, priceRecords[0]);

			assertArrayEquals(new PriceRecordContract[]{price}, priceAndCurrencyIndex.getLowestPriceRecordsForEntity(priceRecords[0].entityPrimaryKey()));

			assertArrayEquals(new int[]{1}, priceAndCurrencyIndex.getIndexedPriceEntityIds().getArray());
			assertArrayEquals(new int[]{2}, priceAndCurrencyIndex.getIndexedRecordIdsValidInFormula(OffsetDateTime.now()).compute().getArray());
			assertArrayEquals(new int[]{1}, priceAndCurrencyIndex.createPriceIndexFormulaWithAllRecords().compute().getArray());

			PriceSuperIndexTest.this.priceIndex.priceRemove(null, 1, 2, new PriceKey(11, PRICE_LIST, CURRENCY_CZK), PriceInnerRecordHandling.SUM, 2, null, 999, 2000);
			assertNull(PriceSuperIndexTest.this.priceIndex.getPriceIndex(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.SUM));
		}
	}

	@Nested
	@DisplayName("Generational proof tests")
	class GenerationalTest {

		@Test
		@DisplayName("specific regression scenario with add-then-remove sequence")
		void shouldGenerationalTest1() {
			final PriceListAndCurrencyPriceSuperIndex priceIndex = new PriceListAndCurrencyPriceSuperIndex(new PriceIndexKey(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE));
			addPrice(priceIndex, 11, 4, 21, DateTimeRange.between(OffsetDateTime.now().minusMinutes(1132), OffsetDateTime.now().plusMinutes(1132)), 841, 1017);
			addPrice(priceIndex, 4, 7, 1, DateTimeRange.between(OffsetDateTime.now().minusMinutes(2142), OffsetDateTime.now().plusMinutes(2142)), 676, 817);
			addPrice(priceIndex, 11, 9, 10, DateTimeRange.between(OffsetDateTime.now().minusMinutes(2141), OffsetDateTime.now().plusMinutes(2141)), 683, 826);
			addPrice(priceIndex, 1, 19, 1, DateTimeRange.between(OffsetDateTime.now().minusMinutes(6801), OffsetDateTime.now().plusMinutes(6801)), 739, 894);
			addPrice(priceIndex, 8, 28, 13, DateTimeRange.between(OffsetDateTime.now().minusMinutes(2382), OffsetDateTime.now().plusMinutes(2382)), 216, 261);
			addPrice(priceIndex, 0, 32, 9, DateTimeRange.between(OffsetDateTime.now().minusMinutes(469), OffsetDateTime.now().plusMinutes(469)), 35, 42);
			addPrice(priceIndex, 11, 33, 6, DateTimeRange.between(OffsetDateTime.now().minusMinutes(4092), OffsetDateTime.now().plusMinutes(4092)), 963, 1165);
			addPrice(priceIndex, 6, 38, 0, DateTimeRange.between(OffsetDateTime.now().minusMinutes(9076), OffsetDateTime.now().plusMinutes(9076)), 633, 765);
			addPrice(priceIndex, 8, 43, 20, DateTimeRange.between(OffsetDateTime.now().minusMinutes(8841), OffsetDateTime.now().plusMinutes(8841)), 513, 620);
			addPrice(priceIndex, 10, 47, 8, DateTimeRange.between(OffsetDateTime.now().minusMinutes(4377), OffsetDateTime.now().plusMinutes(4377)), 642, 776);
			addPrice(priceIndex, 5, 50, 7, DateTimeRange.between(OffsetDateTime.now().minusMinutes(8792), OffsetDateTime.now().plusMinutes(8792)), 346, 418);
			addPrice(priceIndex, 8, 51, 7, DateTimeRange.between(OffsetDateTime.now().minusMinutes(1046), OffsetDateTime.now().plusMinutes(1046)), 833, 1007);
			addPrice(priceIndex, 0, 53, 5, DateTimeRange.between(OffsetDateTime.now().minusMinutes(5718), OffsetDateTime.now().plusMinutes(5718)), 35, 42);
			addPrice(priceIndex, 8, 55, 2, DateTimeRange.between(OffsetDateTime.now().minusMinutes(8279), OffsetDateTime.now().plusMinutes(8279)), 246, 297);
			addPrice(priceIndex, 4, 59, 6, DateTimeRange.between(OffsetDateTime.now().minusMinutes(138), OffsetDateTime.now().plusMinutes(138)), 988, 1195);
			addPrice(priceIndex, 2, 62, 5, DateTimeRange.between(OffsetDateTime.now().minusMinutes(1556), OffsetDateTime.now().plusMinutes(1556)), 641, 775);
			addPrice(priceIndex, 4, 65, 18, DateTimeRange.between(OffsetDateTime.now().minusMinutes(2616), OffsetDateTime.now().plusMinutes(2616)), 109, 131);
			addPrice(priceIndex, 2, 75, 9, DateTimeRange.between(OffsetDateTime.now().minusMinutes(8838), OffsetDateTime.now().plusMinutes(8838)), 802, 970);
			addPrice(priceIndex, 3, 77, 7, DateTimeRange.between(OffsetDateTime.now().minusMinutes(71), OffsetDateTime.now().plusMinutes(71)), 90, 108);

			assertStateAfterCommit(
				priceIndex,
				pi -> {
					priceIndex.removePrice(0, PriceSuperIndexTest.this.priceToInternalId.get(32), DateTimeRange.between(OffsetDateTime.now().minusMinutes(469), OffsetDateTime.now().plusMinutes(469)));
					priceIndex.removePrice(11, PriceSuperIndexTest.this.priceToInternalId.get(33), DateTimeRange.between(OffsetDateTime.now().minusMinutes(4092), OffsetDateTime.now().plusMinutes(4092)));
					priceIndex.removePrice(0, PriceSuperIndexTest.this.priceToInternalId.get(53), DateTimeRange.between(OffsetDateTime.now().minusMinutes(5718), OffsetDateTime.now().plusMinutes(5718)));
				},
				(original, committed) -> {

				}
			);

		}

		@ParameterizedTest(name = "PriceSuperIndex should survive generational randomized test applying modifications on it")
		@Tag(LONG_RUNNING_TEST)
		@ArgumentsSource(TimeArgumentProvider.class)
		void generationalProofTest(GenerationalTestInput input) {
			final int maxPrices = 50;
			final PriceIndexKey key = new PriceIndexKey(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE);

			runFor(
				input,
				1_000,
				new TestState(
					new StringBuilder(),
					new PriceRecordWithValidity[0]
				),
				(random, testState) -> {
					final PriceRecordWithValidity[] initialRecords = testState.initialState();
					final PriceRecordContract[] priceRecords = buildPriceRecordsFrom(initialRecords);
					final RangeIndex validityIndex = buildValidityIndexFrom(initialRecords);
					final PriceListAndCurrencyPriceSuperIndex priceSuperIndex = new PriceListAndCurrencyPriceSuperIndex(key, validityIndex, priceRecords);

					final AtomicReference<PriceRecordWithValidity[]> nextArrayToCompare = new AtomicReference<>(testState.initialState());

					final StringBuilder codeBuffer = testState.code();
					codeBuffer.append("final PriceListAndCurrencyPriceSuperIndex priceIndex = new PriceListAndCurrencyPriceSuperIndex(new PriceIndexKey(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE));\n")
						.append(Arrays.stream(initialRecords)
							.map(it ->
								"priceIndex.addPrice(" +
									it.entityPrimaryKey() + "," +
									it.priceId() + "," +
									it.innerRecordId() + "," +
									"DateTimeRange.between(OffsetDateTime.now().minusMinutes(" + it.differenceInMinutes() + "), OffsetDateTime.now().plusMinutes(" + it.differenceInMinutes() + "))," +
									it.priceWithoutTax() + "," +
									it.priceWithTax() +
									");"
							)
							.collect(Collectors.joining("\n")));
					codeBuffer.append("\nOps:\n");

					assertStateAfterCommit(
						priceSuperIndex,
						original -> {
							final int operationsInTransaction = random.nextInt(10);
							final Set<Integer> addedInThisRound = new HashSet<>();
							final List<PriceRecordWithValidity> addedRecordInThisRound = new ArrayList<>();
							final Set<Integer> removedInThisRound = new HashSet<>();
							for (int i = 0; i < operationsInTransaction; i++) {
								final int length = priceSuperIndex.getPriceRecords().length;
								if (length < maxPrices && random.nextBoolean() || length < 10) {
									// insert new item
									int newPriceId;
									do {
										newPriceId = random.nextInt(maxPrices * 2);
									} while (addedInThisRound.contains(newPriceId) || ArrayUtils.binarySearch(nextArrayToCompare.get(), newPriceId, (priceRecordWithValidity, pid) -> Integer.compare(priceRecordWithValidity.priceId(), pid)) >= 0);

									final int newEntityId = random.nextInt(maxPrices / 4);
									final int newInnerRecordId = random.nextInt(maxPrices / 2);
									final int randomPriceWithoutTax = random.nextInt(1000);
									final int randomPriceWithTax = (int) (randomPriceWithoutTax * 1.21);
									final int differenceInMinutes = random.nextInt(10_000);
									final OffsetDateTime from = OffsetDateTime.now().minusMinutes(differenceInMinutes);
									final DateTimeRange validity = DateTimeRange.between(from, from.plusMinutes(differenceInMinutes));

									final int internalPriceId = PriceSuperIndexTest.this.priceIdSequence.incrementAndGet();
									final PriceRecordWithValidity priceRecord = new PriceRecordWithValidity(
										internalPriceId, newPriceId, newEntityId, newInnerRecordId,
										randomPriceWithTax, randomPriceWithoutTax, differenceInMinutes, validity
									);

									codeBuffer.append("priceIndex.addPrice(")
										.append(newEntityId).append(",")
										.append(newPriceId).append(",")
										.append(newInnerRecordId).append(",")
										.append("DateTimeRange.between(OffsetDateTime.now().minusMinutes(").append(differenceInMinutes).append("), OffsetDateTime.now().plusMinutes(").append(differenceInMinutes).append(")),")
										.append(randomPriceWithoutTax).append(",")
										.append(randomPriceWithTax)
										.append(");\n");

									try {
										priceSuperIndex.addPrice(
											new PriceRecordInnerRecordSpecific(internalPriceId, newPriceId, newEntityId, newInnerRecordId, randomPriceWithTax, randomPriceWithoutTax),
											validity
										);
										nextArrayToCompare.set(ArrayUtils.insertRecordIntoOrderedArray(priceRecord, nextArrayToCompare.get(), Comparator.comparingInt(PriceRecordWithValidity::priceId)));
										addedInThisRound.add(newPriceId);
										addedRecordInThisRound.add(priceRecord);
										removedInThisRound.remove(newPriceId);
									} catch (Exception ex) {
										fail(ex.getMessage() + "\n" + codeBuffer, ex);
									}

								} else {
									// remove existing item
									PriceRecordWithValidity recordToRemove;
									do {
										if (addedInThisRound.isEmpty() || random.nextInt(5) == 0) {
											recordToRemove = nextArrayToCompare.get()[random.nextInt(nextArrayToCompare.get().length)];
										} else {
											recordToRemove = addedRecordInThisRound.get(random.nextInt(addedRecordInThisRound.size()));
										}
									} while (removedInThisRound.contains(recordToRemove.priceId()));

									codeBuffer.append("priceIndex.removePrice(")
										.append(recordToRemove.entityPrimaryKey()).append(",")
										.append(recordToRemove.priceId()).append(",")
										.append("DateTimeRange.between(OffsetDateTime.now().minusMinutes(").append(recordToRemove.differenceInMinutes()).append("), OffsetDateTime.now().plusMinutes(").append(recordToRemove.differenceInMinutes()).append("))")
										.append(");\n");

									try {
										priceSuperIndex.removePrice(
											recordToRemove.entityPrimaryKey(),
											recordToRemove.internalPriceId(),
											recordToRemove.validity()
										);
										nextArrayToCompare.set(ArrayUtils.removeRecordFromOrderedArray(recordToRemove, nextArrayToCompare.get()));
										if (addedInThisRound.remove(recordToRemove.priceId())) {
											addedRecordInThisRound.remove(recordToRemove);
										} else {
											removedInThisRound.add(recordToRemove.priceId());
										}
									} catch (Exception ex) {
										fail(ex.getMessage() + "\n" + codeBuffer, ex);
									}
								}
							}
						},
						(original, committed) -> {
							final PriceRecordContract[] expectedPriceRecords = buildPriceRecordsFrom(nextArrayToCompare.get());
							assertArrayEquals(
								expectedPriceRecords, committed.getPriceRecords(),
								"\nExpected: " + Arrays.toString(expectedPriceRecords) + "\n" +
									"Actual:   " + Arrays.toString(committed.getPriceRecords()) + "\n\n" +
									codeBuffer
							);
						}
					);

					return new TestState(
						new StringBuilder(),
						nextArrayToCompare.get()
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("STM invariants")
	class StmInvariantsTest {

		@Test
		@DisplayName("getId() returns stable non-zero unique values across instances")
		void shouldReturnUniqueNonZeroIds() {
			final PriceSuperIndex index1 = new PriceSuperIndex();
			final PriceSuperIndex index2 = new PriceSuperIndex();

			assertNotEquals(0, index1.getId());
			assertNotEquals(0, index2.getId());
			assertNotEquals(index1.getId(), index2.getId());
		}

		@Test
		@DisplayName("removeLayer cleans priceIndexes map and PriceIndexChanges")
		void shouldCleanLayersOnRemoveLayer() {
			final PriceSuperIndex tested = new PriceSuperIndex();

			assertStateAfterRollback(
				tested,
				original -> {
					original.addPrice(
						null, 1, 1,
						new PriceKey(10, PRICE_LIST, CURRENCY_CZK),
						PriceInnerRecordHandling.NONE,
						null, null, 1000, 1210
					);
				},
				(original, committed) -> {
					// after rollback committed is null and original is unchanged
					assertNull(committed);
					assertTrue(original.isPriceIndexEmpty());
				}
			);
		}

		@Test
		@DisplayName("created-then-removed sub-index in same transaction is properly cleaned")
		void shouldCleanCreatedThenRemovedSubIndex() {
			final PriceSuperIndex tested = new PriceSuperIndex();

			assertStateAfterCommit(
				tested,
				original -> {
					// create a sub-index by adding a price
					original.addPrice(
						null, 1, 1,
						new PriceKey(10, PRICE_LIST, CURRENCY_CZK),
						PriceInnerRecordHandling.NONE,
						null, null, 1000, 1210
					);
					// remove it in the same transaction
					original.priceRemove(
						null, 1, 1,
						new PriceKey(10, PRICE_LIST, CURRENCY_CZK),
						PriceInnerRecordHandling.NONE,
						null, null, 1000, 1210
					);
				},
				(original, committed) -> {
					// committed copy should have no sub-indexes
					assertTrue(committed.isPriceIndexEmpty());
				}
			);
		}
	}

	@Nested
	@DisplayName("STM commit")
	class StmCommitTest {

		@Test
		@DisplayName("add price within transaction and verify committed copy")
		void shouldAddPriceAndCommit() {
			final PriceSuperIndex tested = new PriceSuperIndex();

			assertStateAfterCommit(
				tested,
				original -> {
					original.addPrice(
						null, 1, 1,
						new PriceKey(10, PRICE_LIST, CURRENCY_CZK),
						PriceInnerRecordHandling.NONE,
						20, null, 1000, 1210
					);
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					assertFalse(committed.isPriceIndexEmpty());
					final PriceListAndCurrencyPriceSuperIndex subIndex =
						committed.getPriceIndex(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE);
					assertNotNull(subIndex);
					assertEquals(1, subIndex.getPriceRecords().length);
					assertArrayEquals(
						new int[]{1},
						subIndex.getIndexedPriceEntityIds().getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("remove all prices for a price list within transaction and verify sub-index is gone")
		void shouldRemoveAllPricesAndVerifySubIndexGone() {
			final PriceSuperIndex tested = new PriceSuperIndex();
			tested.addPrice(
				null, 1, 1,
				new PriceKey(10, PRICE_LIST, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 1000, 1210
			);

			assertStateAfterCommit(
				tested,
				original -> {
					original.priceRemove(
						null, 1, 1,
						new PriceKey(10, PRICE_LIST, CURRENCY_CZK),
						PriceInnerRecordHandling.NONE,
						null, null, 1000, 1210
					);
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					assertTrue(committed.isPriceIndexEmpty());
					assertNull(
						committed.getPriceIndex(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE)
					);
				}
			);
		}

		@Test
		@DisplayName("original PriceSuperIndex unchanged after commit")
		void shouldLeaveOriginalUnchangedAfterCommit() {
			final PriceSuperIndex tested = new PriceSuperIndex();

			assertStateAfterCommit(
				tested,
				original -> {
					original.addPrice(
						null, 1, 1,
						new PriceKey(10, PRICE_LIST, CURRENCY_CZK),
						PriceInnerRecordHandling.NONE,
						null, null, 1000, 1210
					);
				},
				(original, committed) -> {
					// original should remain empty after commit
					assertTrue(original.isPriceIndexEmpty());
					// committed should have the price
					assertFalse(committed.isPriceIndexEmpty());
				}
			);
		}

		@Test
		@DisplayName("committing outer index also commits inner sub-index state")
		void shouldCommitInnerSubIndexState() {
			final PriceSuperIndex tested = new PriceSuperIndex();

			assertStateAfterCommit(
				tested,
				original -> {
					original.addPrice(
						null, 1, 1,
						new PriceKey(10, PRICE_LIST, CURRENCY_CZK),
						PriceInnerRecordHandling.NONE,
						20, null, 1000, 1210
					);
					original.addPrice(
						null, 2, 2,
						new PriceKey(11, PRICE_LIST, CURRENCY_CZK),
						PriceInnerRecordHandling.NONE,
						21, null, 500, 605
					);
				},
				(original, committed) -> {
					final PriceListAndCurrencyPriceSuperIndex subIndex =
						committed.getPriceIndex(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE);
					assertNotNull(subIndex);
					// verify sub-index has both prices committed
					final PriceRecordContract[] priceRecords = subIndex.getPriceRecords();
					assertEquals(2, priceRecords.length);
					assertArrayEquals(
						new int[]{1, 2},
						subIndex.getIndexedPriceEntityIds().getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("no-change commit returns new instance but preserves state")
		void shouldHandleNoChangeCommit() {
			final PriceSuperIndex tested = new PriceSuperIndex();
			tested.addPrice(
				null, 1, 1,
				new PriceKey(10, PRICE_LIST, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 1000, 1210
			);

			assertStateAfterCommit(
				tested,
				original -> {
					// no operations inside transaction
				},
				(original, committed) -> {
					// committed copy should still have the sub-index
					assertNotNull(
						committed.getPriceIndex(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE)
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("STM rollback")
	class StmRollbackTest {

		@Test
		@DisplayName("add price and rollback leaves original unmodified")
		void shouldRollbackAndLeaveOriginalUnmodified() {
			final PriceSuperIndex tested = new PriceSuperIndex();

			assertStateAfterRollback(
				tested,
				original -> {
					original.addPrice(
						null, 1, 1,
						new PriceKey(10, PRICE_LIST, CURRENCY_CZK),
						PriceInnerRecordHandling.NONE,
						null, null, 1000, 1210
					);
				},
				(original, committed) -> {
					// after rollback committed is null
					assertNull(committed);
					// original should remain empty
					assertTrue(original.isPriceIndexEmpty());
				}
			);
		}

		@Test
		@DisplayName("remove price and rollback leaves original with all prices intact")
		void shouldRollbackRemoveAndLeaveOriginalIntact() {
			final PriceSuperIndex tested = new PriceSuperIndex();
			tested.addPrice(
				null, 1, 1,
				new PriceKey(10, PRICE_LIST, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				20, null, 1000, 1210
			);

			assertStateAfterRollback(
				tested,
				original -> {
					original.priceRemove(
						null, 1, 1,
						new PriceKey(10, PRICE_LIST, CURRENCY_CZK),
						PriceInnerRecordHandling.NONE,
						20, null, 1000, 1210
					);
				},
				(original, committed) -> {
					assertNull(committed);
					// original should still have the price
					assertFalse(original.isPriceIndexEmpty());
					final PriceListAndCurrencyPriceSuperIndex subIndex =
						original.getPriceIndex(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE);
					assertNotNull(subIndex);
					assertEquals(1, subIndex.getPriceRecords().length);
				}
			);
		}
	}

	@Nested
	@DisplayName("Non-transactional mode")
	class NonTransactionalModeTest {

		@Test
		@DisplayName("getIndexedPriceIds memo-cache path outside transaction")
		void shouldUseMemoizedPriceIdsOutsideTransaction() {
			final PriceSuperIndex tested = new PriceSuperIndex();
			tested.addPrice(
				null, 1, 1,
				new PriceKey(10, PRICE_LIST, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 1000, 1210
			);

			final PriceListAndCurrencyPriceSuperIndex subIndex =
				tested.getPriceIndex(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE);
			assertNotNull(subIndex);

			// first call populates cache
			final int[] firstCall = subIndex.getIndexedPriceIds();
			// second call should return cached result
			final int[] secondCall = subIndex.getIndexedPriceIds();
			assertSame(firstCall, secondCall);
		}

		@Test
		@DisplayName("two-arg constructor with pre-populated map")
		void shouldCreateIndexWithPrePopulatedMap() {
			final PriceIndexKey key = new PriceIndexKey(
				PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE
			);
			final PriceListAndCurrencyPriceSuperIndex subIndex =
				new PriceListAndCurrencyPriceSuperIndex(key);
			subIndex.addPrice(
				new PriceRecordInnerRecordSpecific(1, 10, 1, 20, 1210, 1000),
				null
			);

			final Map<PriceIndexKey, PriceListAndCurrencyPriceSuperIndex> map = new HashMap<>(4);
			map.put(key, subIndex);
			final PriceSuperIndex tested = new PriceSuperIndex(map);

			assertFalse(tested.isPriceIndexEmpty());
			final PriceListAndCurrencyPriceSuperIndex retrieved =
				tested.getPriceIndex(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE);
			assertNotNull(retrieved);
			assertEquals(1, retrieved.getPriceRecords().length);
		}
	}

	@Nested
	@DisplayName("Functional coverage")
	class FunctionalCoverageTest {

		@Test
		@DisplayName("getPriceListAndCurrencyIndexes returns all sub-indexes")
		void shouldReturnAllSubIndexes() {
			final PriceSuperIndex tested = new PriceSuperIndex();
			tested.addPrice(
				null, 1, 1,
				new PriceKey(10, PRICE_LIST, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 1000, 1210
			);
			tested.addPrice(
				null, 2, 2,
				new PriceKey(11, "vip", CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 500, 605
			);

			final Collection<? extends PriceListAndCurrencyPriceIndex> indexes =
				tested.getPriceListAndCurrencyIndexes();
			assertEquals(2, indexes.size());
		}

		@Test
		@DisplayName("getPriceIndexesStream(currency, handling) filters by currency")
		void shouldFilterByCurrency() {
			final Currency eurCurrency = Currency.getInstance("EUR");
			final PriceSuperIndex tested = new PriceSuperIndex();
			tested.addPrice(
				null, 1, 1,
				new PriceKey(10, PRICE_LIST, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 1000, 1210
			);
			tested.addPrice(
				null, 2, 2,
				new PriceKey(11, PRICE_LIST, eurCurrency),
				PriceInnerRecordHandling.NONE,
				null, null, 500, 605
			);

			final long czkCount = tested.getPriceIndexesStream(
				CURRENCY_CZK, PriceInnerRecordHandling.NONE
			).count();
			final long eurCount = tested.getPriceIndexesStream(
				eurCurrency, PriceInnerRecordHandling.NONE
			).count();

			assertEquals(1, czkCount);
			assertEquals(1, eurCount);
		}

		@Test
		@DisplayName("getPriceIndexesStream(priceList, handling) filters by price list")
		void shouldFilterByPriceList() {
			final PriceSuperIndex tested = new PriceSuperIndex();
			tested.addPrice(
				null, 1, 1,
				new PriceKey(10, PRICE_LIST, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 1000, 1210
			);
			tested.addPrice(
				null, 2, 2,
				new PriceKey(11, "vip", CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 500, 605
			);

			final long basicCount = tested.getPriceIndexesStream(
				PRICE_LIST, PriceInnerRecordHandling.NONE
			).count();
			final long vipCount = tested.getPriceIndexesStream(
				"vip", PriceInnerRecordHandling.NONE
			).count();

			assertEquals(1, basicCount);
			assertEquals(1, vipCount);
		}

		@Test
		@DisplayName("isPriceIndexEmpty on empty and non-empty index")
		void shouldReportEmptyStateCorrectly() {
			final PriceSuperIndex tested = new PriceSuperIndex();
			assertTrue(tested.isPriceIndexEmpty());

			tested.addPrice(
				null, 1, 1,
				new PriceKey(10, PRICE_LIST, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 1000, 1210
			);
			assertFalse(tested.isPriceIndexEmpty());
		}

		@Test
		@DisplayName("resetDirty clears dirty flag on sub-indexes")
		void shouldResetDirtyFlag() {
			final PriceSuperIndex tested = new PriceSuperIndex();
			tested.addPrice(
				null, 1, 1,
				new PriceKey(10, PRICE_LIST, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 1000, 1210
			);

			final PriceListAndCurrencyPriceSuperIndex subIndex =
				tested.getPriceIndex(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE);
			assertNotNull(subIndex);
			// sub-index should be dirty after adding a price
			assertNotNull(subIndex.createStoragePart(1));

			tested.resetDirty();
			// after reset, sub-index should not produce a storage part
			assertNull(subIndex.createStoragePart(1));
		}

		@Test
		@DisplayName("multiple different currencies produce separate sub-indexes")
		void shouldCreateSeparateSubIndexesForDifferentCurrencies() {
			final Currency eurCurrency = Currency.getInstance("EUR");
			final Currency usdCurrency = Currency.getInstance("USD");
			final PriceSuperIndex tested = new PriceSuperIndex();

			tested.addPrice(
				null, 1, 1,
				new PriceKey(10, PRICE_LIST, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 1000, 1210
			);
			tested.addPrice(
				null, 2, 2,
				new PriceKey(11, PRICE_LIST, eurCurrency),
				PriceInnerRecordHandling.NONE,
				null, null, 500, 605
			);
			tested.addPrice(
				null, 3, 3,
				new PriceKey(12, PRICE_LIST, usdCurrency),
				PriceInnerRecordHandling.NONE,
				null, null, 200, 242
			);

			assertNotNull(tested.getPriceIndex(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE));
			assertNotNull(tested.getPriceIndex(PRICE_LIST, eurCurrency, PriceInnerRecordHandling.NONE));
			assertNotNull(tested.getPriceIndex(PRICE_LIST, usdCurrency, PriceInnerRecordHandling.NONE));
			assertEquals(3, tested.getPriceListAndCurrencyIndexes().size());
		}

		@Test
		@DisplayName("multiple different PriceInnerRecordHandling modes simultaneously")
		void shouldSupportMultipleHandlingModes() {
			final PriceSuperIndex tested = new PriceSuperIndex();

			tested.addPrice(
				null, 1, 1,
				new PriceKey(10, PRICE_LIST, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 1000, 1210
			);
			tested.addPrice(
				null, 2, 2,
				new PriceKey(11, PRICE_LIST, CURRENCY_CZK),
				PriceInnerRecordHandling.LOWEST_PRICE,
				20, null, 500, 605
			);
			tested.addPrice(
				null, 3, 3,
				new PriceKey(12, PRICE_LIST, CURRENCY_CZK),
				PriceInnerRecordHandling.SUM,
				30, null, 200, 242
			);

			assertNotNull(tested.getPriceIndex(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE));
			assertNotNull(tested.getPriceIndex(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.LOWEST_PRICE));
			assertNotNull(tested.getPriceIndex(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.SUM));
			assertEquals(3, tested.getPriceListAndCurrencyIndexes().size());
		}

		@Test
		@DisplayName("priceRemove when price list entry does not exist throws assertion")
		void shouldThrowWhenRemovingFromNonExistentPriceList() {
			final PriceSuperIndex tested = new PriceSuperIndex();

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> tested.priceRemove(
					null, 1, 1,
					new PriceKey(10, "nonexistent", CURRENCY_CZK),
					PriceInnerRecordHandling.NONE,
					null, null, 1000, 1210
				)
			);
		}

		@Test
		@DisplayName("getPriceIndex returns null for non-existent key")
		void shouldReturnNullForNonExistentPriceIndex() {
			final PriceSuperIndex tested = new PriceSuperIndex();

			assertNull(
				tested.getPriceIndex(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE)
			);
		}

		@Test
		@DisplayName("getPriceIndex with PriceIndexKey returns correct sub-index")
		void shouldReturnSubIndexByPriceIndexKey() {
			final PriceSuperIndex tested = new PriceSuperIndex();
			tested.addPrice(
				null, 1, 1,
				new PriceKey(10, PRICE_LIST, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 1000, 1210
			);

			final PriceIndexKey key = new PriceIndexKey(
				PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE
			);
			final PriceListAndCurrencyPriceSuperIndex subIndex =
				tested.getPriceIndex(key);
			assertNotNull(subIndex);
			assertEquals(1, subIndex.getPriceRecords().length);
		}

		@Test
		@DisplayName("sub-index terminated after removing all its prices")
		void shouldTerminateSubIndexWhenEmpty() {
			final PriceSuperIndex tested = new PriceSuperIndex();
			tested.addPrice(
				null, 1, 1,
				new PriceKey(10, PRICE_LIST, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 1000, 1210
			);

			final PriceListAndCurrencyPriceSuperIndex subIndex =
				tested.getPriceIndex(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE);
			assertNotNull(subIndex);
			assertFalse(subIndex.isTerminated());

			tested.priceRemove(
				null, 1, 1,
				new PriceKey(10, PRICE_LIST, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 1000, 1210
			);

			// sub-index is removed from map
			assertNull(
				tested.getPriceIndex(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE)
			);
			// and the old reference is now terminated
			assertTrue(subIndex.isTerminated());
			assertThrows(
				PriceListAndCurrencyPriceIndexTerminated.class,
				subIndex::getPriceRecords
			);
		}
	}

	/**
	 * Adds two standard prices with NONE handling to the shared price index.
	 * Used as setup by both add and remove tests.
	 */
	private void addStandardPrices() {
		this.priceIndex.addPrice(null, 1, 1, new PriceKey(10, PRICE_LIST, CURRENCY_CZK), PriceInnerRecordHandling.NONE, 20, null, 1000, 1210);
		this.priceIndex.addPrice(null, 2, 2, new PriceKey(11, PRICE_LIST, CURRENCY_CZK), PriceInnerRecordHandling.NONE, 21, null, 999, 2000);
	}

	/**
	 * Adds two prices with LOWEST_PRICE handling for the same entity to the shared price index.
	 * Used as setup by both add and remove tests.
	 */
	private void addLowestPricePrices() {
		this.priceIndex.addPrice(null, 1, 1, new PriceKey(10, PRICE_LIST, CURRENCY_CZK), PriceInnerRecordHandling.LOWEST_PRICE, 20, null, 1000, 1210);
		this.priceIndex.addPrice(null, 1, 2, new PriceKey(11, PRICE_LIST, CURRENCY_CZK), PriceInnerRecordHandling.LOWEST_PRICE, 21, null, 999, 2000);
	}

	/**
	 * Adds two prices with SUM handling for the same entity to the shared price index.
	 * Used as setup by both add and remove tests.
	 */
	private void addSumPrices() {
		this.priceIndex.addPrice(null, 1, 1, new PriceKey(10, PRICE_LIST, CURRENCY_CZK), PriceInnerRecordHandling.SUM, 1, null, 1000, 1210);
		this.priceIndex.addPrice(null, 1, 2, new PriceKey(11, PRICE_LIST, CURRENCY_CZK), PriceInnerRecordHandling.SUM, 2, null, 999, 2000);
	}

	private void addPrice(
		@Nonnull PriceListAndCurrencyPriceSuperIndex priceListIndex,
		int entityPrimaryKey,
		int priceId,
		@Nullable Integer innerRecordId,
		DateTimeRange validity,
		int priceWithoutTax,
		int priceWithTax
	) {
		final PriceRecordContract priceRecord = innerRecordId == null ?
			new PriceRecord(this.priceIdSequence.incrementAndGet(), priceId, entityPrimaryKey, priceWithTax, priceWithoutTax) :
			new PriceRecordInnerRecordSpecific(this.priceIdSequence.incrementAndGet(), priceId, entityPrimaryKey, innerRecordId, priceWithTax, priceWithoutTax);
		priceListIndex.addPrice(priceRecord, validity);
		this.priceToInternalId.put(priceRecord.priceId(), priceRecord.internalPriceId());
	}

	private static PriceRecordContract[] buildPriceRecordsFrom(PriceRecordWithValidity[] priceRecords) {
		final PriceRecordContract[] result = new PriceRecordContract[priceRecords.length];
		for (int i = 0; i < priceRecords.length; i++) {
			final PriceRecordWithValidity priceRecord = priceRecords[i];
			result[i] = new PriceRecordInnerRecordSpecific(
				priceRecord.internalPriceId(),
				priceRecord.priceId(),
				priceRecord.entityPrimaryKey(),
				priceRecord.innerRecordId(),
				priceRecord.priceWithTax(),
				priceRecord.priceWithoutTax()
			);
		}
		Arrays.sort(result, Comparator.comparingInt(PriceRecordContract::internalPriceId));
		return result;
	}

	private static RangeIndex buildValidityIndexFrom(PriceRecordWithValidity[] priceRecords) {
		final RangeIndex result = new RangeIndex();
		for (PriceRecordWithValidity priceRecord : priceRecords) {
			result.addRecord(
				priceRecord.validity().getFrom(),
				priceRecord.validity().getTo(),
				priceRecord.priceId()
			);
		}
		return result;
	}

	private record PriceRecordWithValidity(
		int internalPriceId,
		int priceId,
		int entityPrimaryKey,
		int innerRecordId,
		int priceWithTax,
		int priceWithoutTax,
		int differenceInMinutes,
		DateTimeRange validity
	) implements Comparable<PriceRecordWithValidity> {
		@Override
		public int compareTo(PriceRecordWithValidity o) {
			return Integer.compare(this.priceId, o.priceId);
		}

	}

	private record TestState(
		StringBuilder code,
		PriceRecordWithValidity[] initialState
	) {}

}
