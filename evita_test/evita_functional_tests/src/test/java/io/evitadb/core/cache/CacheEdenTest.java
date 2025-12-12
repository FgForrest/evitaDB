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

package io.evitadb.core.cache;

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.CumulatedVirtualPriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.price.model.priceRecord.PriceRecordInnerRecordSpecific;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Currency;
import java.util.Random;

/**
 * This test verifies behaviour of {@link CacheEden}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class CacheEdenTest {
	public static final String SOME_ENTITY = "Product";
	public static final Currency CZK = Currency.getInstance("CZK");
	private final Random random = new Random(42);
	private final PriceListAndCurrencyPriceSuperIndex priceIndex = new PriceListAndCurrencyPriceSuperIndex(new PriceIndexKey("basic", CZK, PriceInnerRecordHandling.NONE));

	/* TOBEDONE #46 - use when cache serialization is implemented */
	/*@Test
	void shouldSerializeAndDeserializeFlattenedFormula() {
		final CacheEden cacheEden = new CacheEden(1_000_000_000, 2, 1L);
		final long[] randoms = generateRandomLongs(100_000);
		final byte[] serializedForm = cacheEden.serializeFormulaHeader(new CachePayloadHeader(Long.MAX_VALUE, Long.MAX_VALUE, randoms));
		final CachePayloadHeader header = cacheEden.deserializeFormulaHeader(serializedForm);

		assertEquals(Long.MAX_VALUE, header.getRecordHash());
		assertEquals(Long.MAX_VALUE, header.getTransactionalIdHash());
		assertArrayEquals(randoms, header.getTransactionalDataIds());
	}

	@Test
	void shouldSerializeAndDeserializeFlattenedIntegerFormula() {
		final CacheEden cacheEden = new CacheEden(1_000_000_000, 2, 1L);
		final long[] bitmapIds = generateRandomLongs(50);
		final RoaringBitmapBackedBitmap data = new BaseBitmap(generateRandomIntegers(100_000));
		final byte[] serializedForm = cacheEden.serializeFormula(new FlattenedFormula(Long.MAX_VALUE, Long.MAX_VALUE, bitmapIds, data));
		final FlattenedFormula deserializedFormula = (FlattenedFormula) cacheEden.deserializeCachedRecord(Mockito.mock(EvitaSession.class), SOME_ENTITY, serializedForm);

		assertEquals(Long.MAX_VALUE, deserializedFormula.getRecordHash());
		assertEquals(Long.MAX_VALUE, deserializedFormula.getTransactionalIdHash());
		assertArrayEquals(bitmapIds, deserializedFormula.getTransactionalDataIds());
		assertArrayEquals(data.getArray(), deserializedFormula.compute().getArray());
	}

	@Test
	void shouldSerializeAndDeserializeFlattenedIntegerFormulaWithFilteredPrices() {
		final CacheEden cacheEden = new CacheEden(1_000_000_000, 2, 1L);
		final long[] bitmapIds = generateRandomLongs(50);
		final RoaringBitmapBackedBitmap data = new BaseBitmap(generateRandomIntegers(100_000));
		final PriceRecordContract[] priceRecords = generateRandomPriceRecords(50_000);
		Arrays.stream(priceRecords)
			.filter(it -> it instanceof PriceRecord)
			.forEach(it -> priceIndex.addPrice(it, null));
		final PriceEvaluationContext priceEvaluationContext = new PriceEvaluationContext(new Serializable[]{"A", "B", "C"}, CZK);
		final byte[] serializedForm = cacheEden.serializeFormula(
			new FlattenedFormulaWithFilteredPrices(
				Long.MAX_VALUE, Long.MAX_VALUE, bitmapIds, data,
				new ResolvedFilteredPriceRecords(priceRecords, SortingForm.ENTITY_PK),
				priceEvaluationContext
			)
		);

		final EvitaSession evitaSession = Mockito.mock(EvitaSession.class);
		final GlobalEntityIndex globalEntityIndex = Mockito.mock(GlobalEntityIndex.class);
		Mockito.when(evitaSession.getGlobalEntityIndexForType(SOME_ENTITY)).thenReturn(globalEntityIndex);
		Mockito.when(globalEntityIndex.getPriceIndex(Mockito.any())).thenReturn(priceIndex);

		final FlattenedFormulaWithFilteredPrices deserializedFormula = (FlattenedFormulaWithFilteredPrices) cacheEden.deserializeCachedRecord(
			evitaSession, SOME_ENTITY, serializedForm
		);

		assertEquals(Long.MAX_VALUE, deserializedFormula.getRecordHash());
		assertEquals(Long.MAX_VALUE, deserializedFormula.getTransactionalIdHash());
		assertArrayEquals(bitmapIds, deserializedFormula.getTransactionalDataIds());
		assertArrayEquals(data.getArray(), deserializedFormula.compute().getArray());
		assertArrayEquals(priceRecords, ((NonResolvedFilteredPriceRecords)deserializedFormula.getFilteredPriceRecords()).toResolvedFilteredPriceRecords().getPriceRecords());
		assertEquals(priceEvaluationContext, deserializedFormula.getPriceEvaluationContext());
	}

	@Test
	void shouldSerializeAndDeserializeFlattenedIntegerFormulaWithFilteredOutRecords() {
		final CacheEden cacheEden = new CacheEden(1_000_000_000, 2, 1L);
		final long[] bitmapIds = generateRandomLongs(50);
		final RoaringBitmapBackedBitmap data = new BaseBitmap(generateRandomIntegers(100_000));
		final RoaringBitmapBackedBitmap filteredOutRecords = new BaseBitmap(generateRandomIntegers(100_000));
		final PriceEvaluationContext priceEvaluationContext = new PriceEvaluationContext(new Serializable[]{"A", "B", "C"}, CZK);
		final byte[] serializedForm = cacheEden.serializeFormula(
			new FlattenedFormulaWithFilteredOutRecords(
				Long.MAX_VALUE, Long.MAX_VALUE, bitmapIds, data, filteredOutRecords,
				priceEvaluationContext
			)
		);
		final FlattenedFormulaWithFilteredOutRecords deserializedFormula = (FlattenedFormulaWithFilteredOutRecords) cacheEden.deserializeCachedRecord(Mockito.mock(EvitaSession.class), SOME_ENTITY, serializedForm);

		assertEquals(Long.MAX_VALUE, deserializedFormula.getRecordHash());
		assertEquals(Long.MAX_VALUE, deserializedFormula.getTransactionalIdHash());
		assertArrayEquals(bitmapIds, deserializedFormula.getTransactionalDataIds());
		assertArrayEquals(data.getArray(), deserializedFormula.compute().getArray());
		assertArrayEquals(filteredOutRecords.getArray(), deserializedFormula.getRecordsFilteredOutByPredicate().getArray());
		assertEquals(priceEvaluationContext, deserializedFormula.getPriceEvaluationContext());
	}

	@Test
	void shouldSerializeAndDeserializeFlattenedIntegerFormulaWithFilteredPricesAndFilteredOutRecords() {
		final CacheEden cacheEden = new CacheEden(1_000_000_000, 2, 1L);
		final long[] bitmapIds = generateRandomLongs(50);
		final RoaringBitmapBackedBitmap data = new BaseBitmap(generateRandomIntegers(100_000));
		final PriceRecordContract[] priceRecords = generateRandomPriceRecords(10_000);
		Arrays.stream(priceRecords)
			.filter(it -> it instanceof PriceRecord)
			.forEach(it -> priceIndex.addPrice(it, null));
		final RoaringBitmapBackedBitmap filteredOutRecords = new BaseBitmap(generateRandomIntegers(2_000));
		final PriceEvaluationContext priceEvaluationContext = new PriceEvaluationContext(new Serializable[]{"A", "B", "C"}, CZK);

		final byte[] serializedForm = cacheEden.serializeFormula(
			new FlattenedFormulaWithFilteredPricesAndFilteredOutRecords(
				Long.MAX_VALUE, Long.MAX_VALUE, bitmapIds, data,
				new ResolvedFilteredPriceRecords(priceRecords, SortingForm.NOT_SORTED),
				filteredOutRecords,
				priceEvaluationContext
			)
		);

		final EvitaSession evitaSession = Mockito.mock(EvitaSession.class);
		final GlobalEntityIndex globalEntityIndex = Mockito.mock(GlobalEntityIndex.class);
		Mockito.when(evitaSession.getGlobalEntityIndexForType(SOME_ENTITY)).thenReturn(globalEntityIndex);
		Mockito.when(globalEntityIndex.getPriceIndex(Mockito.any())).thenReturn(priceIndex);

		final FlattenedFormulaWithFilteredPricesAndFilteredOutRecords deserializedFormula = (FlattenedFormulaWithFilteredPricesAndFilteredOutRecords) cacheEden.deserializeCachedRecord(evitaSession, SOME_ENTITY, serializedForm);

		assertEquals(Long.MAX_VALUE, deserializedFormula.getRecordHash());
		assertEquals(Long.MAX_VALUE, deserializedFormula.getTransactionalIdHash());
		assertArrayEquals(bitmapIds, deserializedFormula.getTransactionalDataIds());
		assertArrayEquals(data.getArray(), deserializedFormula.compute().getArray());
		assertArrayEquals(filteredOutRecords.getArray(), deserializedFormula.getRecordsFilteredOutByPredicate().getArray());
		assertArrayEquals(priceRecords, ((NonResolvedFilteredPriceRecords)deserializedFormula.getFilteredPriceRecords()).toResolvedFilteredPriceRecords().getPriceRecords());
		assertEquals(priceEvaluationContext, deserializedFormula.getPriceEvaluationContext());
	}*/

	private int[] generateRandomIntegers(int number) {
		final int[] result = new int[number];
		for (int i = 0; i < number; i++) {
			result[i] = this.random.nextInt();
		}
		return result;
	}

	private long[] generateRandomLongs(int number) {
		final long[] result = new long[number];
		for (int i = 0; i < number; i++) {
			result[i] = this.random.nextLong();
		}
		return result;
	}

	private static PriceRecordContract[] generateRandomPriceRecords(int number) {
		final Random random = new Random(42);
		final PriceRecordContract[] result = new PriceRecordContract[number];
		int priceId = 0;
		for (int i = 0; i < number; i++) {
			final int price = Math.abs(random.nextInt());
			final int entityPrimaryKey = random.nextInt();
			priceId += 1 + random.nextInt(1000);
			if (random.nextBoolean()) {
				result[i] = new PriceRecord(
					priceId,
					priceId,
					entityPrimaryKey,
					(int) (price * 1.21),
					price
				);
			} else {
				final boolean withTax = random.nextBoolean();
				final IntObjectMap<PriceRecordContract> innerRecordIds = new IntObjectHashMap<>();
				for (int j = 0; j < 1 + random.nextInt(10); j++) {
					final int innerRecordId = random.nextInt();
					innerRecordIds.put(
						innerRecordId,
						new PriceRecordInnerRecordSpecific(
							priceId,
							priceId,
							entityPrimaryKey,
							innerRecordId,
							(int) (price * 1.21),
							price
						)
					);
				}
				result[i] = new CumulatedVirtualPriceRecord(
					entityPrimaryKey,
					price,
					withTax ? QueryPriceMode.WITH_TAX : QueryPriceMode.WITHOUT_TAX,
					innerRecordIds
				);
			}
		}
		Arrays.sort(result, Comparator.comparingLong(PriceRecordContract::entityPrimaryKey));
		return result;
	}

}
