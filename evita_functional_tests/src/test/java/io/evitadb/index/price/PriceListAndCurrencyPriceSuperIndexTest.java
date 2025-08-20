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
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import java.util.Arrays;
import java.util.Currency;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This class verifies contract of {@link PriceListAndCurrencyPriceSuperIndex}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class PriceListAndCurrencyPriceSuperIndexTest {
	private static final PriceIndexKey PRICE_INDEX_KEY = new PriceIndexKey("basic", Currency.getInstance("CZK"), PriceInnerRecordHandling.NONE);
	private static final Consumer<PriceRecordContract> NOOP_PRICE_RECORD_CALLBACK = priceRecordContract -> {};
	private static final IntConsumer NOOP_NOT_FOUND_CALLBACK = notFound -> {};
	private static PriceRecordContract[] PRICE_RECORDS;

	@BeforeAll
	static void beforeAll() {
		PRICE_RECORDS = generateRandomPriceRecords(5000);
	}

	@AfterAll
	static void afterAll() {
		PRICE_RECORDS = null;
	}

	@Test
	void shouldFindAllPricesById() {
		final PriceListAndCurrencyPriceSuperIndex tested = new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY, new RangeIndex(), PRICE_RECORDS);

		final BaseBitmap allIds = new BaseBitmap(Arrays.stream(PRICE_RECORDS).mapToInt(PriceRecordContract::internalPriceId).toArray());
		final PriceRecordContract[] foundPriceRecords = tested.getPriceRecords(allIds, NOOP_PRICE_RECORD_CALLBACK, NOOP_NOT_FOUND_CALLBACK);
		assertArrayEquals(PRICE_RECORDS, foundPriceRecords);
	}

	@Test
	void shouldFindFirstPriceOnlyById() {
		final PriceListAndCurrencyPriceSuperIndex tested = new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY, new RangeIndex(), PRICE_RECORDS);

		final BaseBitmap firstPriceId = new BaseBitmap(PRICE_RECORDS[0].internalPriceId());
		final NotFoundCollector notFoundCollector = new NotFoundCollector();
		final PriceRecordContract[] foundPriceRecords = tested.getPriceRecords(firstPriceId, NOOP_PRICE_RECORD_CALLBACK, notFoundCollector);

		assertArrayEquals(Arrays.copyOfRange(PRICE_RECORDS, 0, 1), foundPriceRecords);
		assertEquals(0, notFoundCollector.getArray().length);
	}

	@Test
	void shouldFindLastPriceOnlyById() {
		final PriceListAndCurrencyPriceSuperIndex tested = new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY, new RangeIndex(), PRICE_RECORDS);

		final BaseBitmap lastPriceId = new BaseBitmap(PRICE_RECORDS[PRICE_RECORDS.length - 1].internalPriceId());
		final NotFoundCollector notFoundCollector = new NotFoundCollector();
		final PriceRecordContract[] foundPriceRecords = tested.getPriceRecords(lastPriceId, NOOP_PRICE_RECORD_CALLBACK, notFoundCollector);

		assertArrayEquals(Arrays.copyOfRange(PRICE_RECORDS, PRICE_RECORDS.length - 1, PRICE_RECORDS.length), foundPriceRecords);
		assertEquals(0, notFoundCollector.getArray().length);
	}

	@Test
	void shouldFindRandomPricesWithin() {
		final PriceListAndCurrencyPriceSuperIndex tested = new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY, new RangeIndex(), PRICE_RECORDS);

		final Random random = new Random(42);
		for (int i = 0; i < 100; i++) {
			final int includeEach = 5 + random.nextInt(45);
			final int excludeCount = 5 + random.nextInt(300);
			final PriceRecordContract[] pickedRecords = Arrays.stream(PRICE_RECORDS)
				.filter(it -> random.nextInt(includeEach) == 0)
				.toArray(PriceRecordContract[]::new);

			final BaseBitmap randomExistingPrices = new BaseBitmap(
				Arrays.stream(pickedRecords)
					.mapToInt(PriceRecordContract::internalPriceId)
					.toArray()
			);
			final BaseBitmap randomNonExistingPrices = new BaseBitmap(
				IntStream.generate(() -> 1 + random.nextInt(PRICE_RECORDS.length * 2))
					.filter(it -> ArrayUtils.binarySearch(PRICE_RECORDS, it, (price, pid) -> Integer.compare(price.internalPriceId(), pid)) < 0)
					.limit(excludeCount)
					.toArray()
			);
			final Bitmap aggregate = new OrFormula(new ConstantFormula(randomExistingPrices), new ConstantFormula(randomNonExistingPrices)).compute();

			final NotFoundCollector notFoundCollector = new NotFoundCollector();
			final PriceRecordContract[] foundPriceRecords = tested.getPriceRecords(aggregate, NOOP_PRICE_RECORD_CALLBACK, notFoundCollector);

			assertArrayEquals(pickedRecords, foundPriceRecords);
			assertArrayEquals(randomNonExistingPrices.getArray(), notFoundCollector.getArray());
		}
	}

	private static PriceRecordContract[] generateRandomPriceRecords(int number) {
		final Random random = new Random(42);
		final PriceRecordContract[] result = new PriceRecordContract[number];
		int priceId = 0;
		for (int i = 0; i < number; i++) {
			final int price = Math.abs(random.nextInt());
			final int entityPrimaryKey = random.nextInt();
			priceId += 1 + random.nextInt(1000);
			result[i] = new PriceRecord(
				priceId,
				priceId,
				entityPrimaryKey,
				(int) (price * 1.21),
				price
			);
		}
		return result;
	}

	private static class NotFoundCollector implements IntConsumer {
		private final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();

		@Override
		public void accept(int value) {
			this.writer.add(value);
		}

		public int[] getArray() {
			return this.writer.get().toArray();
		}

	}
}
