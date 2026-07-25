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

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
	 * Wires the given ref index directly to the provided super index, mirroring how the owning
	 * entity collection wires its reduced indexes' price ref chains to the super price indexes of
	 * its own GLOBAL entity index after re-attachment.
	 */
	private static void wireRefIndexToSuperIndex(
		@Nonnull PriceListAndCurrencyPriceRefIndex refIndex,
		@Nonnull PriceListAndCurrencyPriceSuperIndex superIndex
	) {
		refIndex.wireSuperIndex(superIndex);
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
						tested.addPrice(id, validityPool[arrayIdx], baseSuperIndex);
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
					index -> applyRandomBatch(
						random, index, baseSuperIndex, maxPrices, nextTrackedIds, validityPool, codeBuffer
					),
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
	 * Generational proof that a **rolled-back** transaction discards every in-transaction price mutation and leaves
	 * the base {@link PriceListAndCurrencyPriceRefIndex} byte-for-byte intact — the atomic-rollback contract of Ref:
	 * #569. Each generation rebuilds a fresh ref index from the (random-walking) tracked-id model over a fixed super
	 * index pool, captures a value oracle of that base, applies a random batch of add/remove mutations inside a
	 * transaction that is then rolled back, and asserts the base index is unchanged and no committed value was
	 * published.
	 */
	@ParameterizedTest(
		name = "PriceListAndCurrencyPriceRefIndex rollback discards every in-transaction mutation and leaves the base intact"
	)
	@Tag(SLOW)
	@Tag(TRANSACTION)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Generational rollback proof test")
	void generationalRollbackProofTest(@Nonnull GenerationalTestInput input) {
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
						tested.addPrice(id, validityPool[arrayIdx], baseSuperIndex);
					}
					tested.resetDirty();
				} else {
					tested = createAttachedRefIndex(baseSuperIndex);
				}

				final AtomicReference<int[]> nextTrackedIds = new AtomicReference<>(trackedIds);
				final StringBuilder codeBuffer = testState.code();
				codeBuffer.setLength(0);

				// value oracle of the base state that the rollback must return to
				final PriceIndexSnapshot beforeRollback = snapshot(tested);

				assertStateAfterRollback(
					tested,
					index -> applyRandomBatch(
						random, index, baseSuperIndex, maxPrices, nextTrackedIds, validityPool, codeBuffer
					),
					(original, committed) -> {
						assertNull(
							committed,
							"A rolled-back transaction must not publish a committed value!\n" + codeBuffer
						);
						assertEquals(
							beforeRollback, snapshot(original),
							"PriceListAndCurrencyPriceRefIndex changed after rollback — atomic rollback leaked!\n" + codeBuffer
						);
					}
				);

				// the tracked-id model reflects the attempted (rolled-back) batch, so the next generation starts from a
				// different live state — a random walk that keeps the proof exploring fresh base indexes
				return new GenerationalTestState(
					codeBuffer,
					nextTrackedIds.get()
				);
			}
		);
	}

	/**
	 * Applies a random batch of 1–8 add/remove price mutations to `index`, mirroring each mutation into the
	 * `nextTrackedIds` reference model so the two stay in lockstep. Shared by the commit and rollback proofs so both
	 * drive the identical random-draw sequence. The `superIndex` must be the very instance `index` is wired to — the
	 * ref index verifies that identity on every mutation.
	 */
	private static void applyRandomBatch(
		@Nonnull Random random,
		@Nonnull PriceListAndCurrencyPriceRefIndex index,
		@Nonnull PriceListAndCurrencyPriceSuperIndex superIndex,
		int maxPrices,
		@Nonnull AtomicReference<int[]> nextTrackedIds,
		@Nonnull DateTimeRange[] validityPool,
		@Nonnull StringBuilder codeBuffer
	) {
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
					index.addPrice(newId, validityPool[arrayIdx], superIndex);
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
					index.removePrice(idToRemove, validityPool[arrayIdx], superIndex);
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
	}

	/**
	 * Reads the full logical content of the ref index into a value-comparable snapshot (indexed price ids, indexed
	 * entity ids and the ascending price records), so two snapshots taken before and after a rollback can be compared
	 * with `.equals` to prove exact restoration.
	 */
	@Nonnull
	static PriceIndexSnapshot snapshot(@Nonnull PriceListAndCurrencyPriceRefIndex index) {
		return new PriceIndexSnapshot(
			toList(index.getIndexedPriceIds()),
			toList(index.getIndexedPriceEntityIds().getArray()),
			toRecordList(index.getPriceRecords())
		);
	}

	/**
	 * Converts an ascending int array into a `List<Integer>` (a value type with deep `.equals`).
	 */
	@Nonnull
	private static List<Integer> toList(@Nonnull int[] array) {
		final List<Integer> list = new ArrayList<>(array.length);
		for (final int value : array) {
			list.add(value);
		}
		return list;
	}

	/**
	 * Converts the index's ascending price records into a list of value tuples capturing every content field, so a
	 * changed price amount (not just a changed id) is detected by snapshot equality.
	 */
	@Nonnull
	private static List<PriceRecordValue> toRecordList(@Nonnull PriceRecordContract[] priceRecords) {
		final List<PriceRecordValue> list = new ArrayList<>(priceRecords.length);
		for (final PriceRecordContract record : priceRecords) {
			list.add(new PriceRecordValue(
				record.internalPriceId(), record.priceId(), record.entityPrimaryKey(),
				record.priceWithTax(), record.priceWithoutTax(), record.innerRecordId()
			));
		}
		return list;
	}

	/**
	 * Value-comparable content of a single price record — all fields participate in equality so a changed amount is
	 * caught, unlike {@link PriceRecordContract#equals} which keys on the internal price id alone.
	 */
	record PriceRecordValue(
		int internalPriceId,
		int priceId,
		int entityPrimaryKey,
		int priceWithTax,
		int priceWithoutTax,
		int innerRecordId
	) {}

	/**
	 * Value-comparable snapshot of a {@link PriceListAndCurrencyPriceRefIndex}: its indexed price ids, indexed entity
	 * ids and ascending price records. Record equality gives deep structural comparison.
	 */
	record PriceIndexSnapshot(
		@Nonnull List<Integer> indexedPriceIds,
		@Nonnull List<Integer> indexedEntityIds,
		@Nonnull List<PriceRecordValue> priceRecords
	) {}

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
