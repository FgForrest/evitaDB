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
import io.evitadb.core.catalog.Catalog;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Currency;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Long-running generational randomized proof test for {@link PriceListAndCurrencyPriceRefIndex}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("PriceListAndCurrencyPriceRefIndex generational proof")
@Tag(INDEXING)
@Tag(PRICE)
class LongRunningPriceListAndCurrencyPriceRefIndexTest implements TimeBoundedTestSupport {

	private static final String ENTITY_TYPE = "product";
	private static final Scope SCOPE = Scope.LIVE;
	private static final Currency CURRENCY_CZK = Currency.getInstance("CZK");
	private static final String PRICE_LIST = "basic";
	private static final PriceIndexKey PRICE_INDEX_KEY =
		new PriceIndexKey(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE);

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

	@ParameterizedTest(
		name = "PriceListAndCurrencyPriceRefIndex should survive generational randomized test"
	)
	@Tag(SLOW)
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
