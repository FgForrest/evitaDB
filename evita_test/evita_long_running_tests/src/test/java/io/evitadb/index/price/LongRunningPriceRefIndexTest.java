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
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.dataType.Scope;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Long-running generational randomized proof test for {@link PriceRefIndex}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("PriceRefIndex generational proof")
@Tag(INDEXING)
@Tag(PRICE)
class LongRunningPriceRefIndexTest implements TimeBoundedTestSupport {

	private static final String ENTITY_TYPE = "product";
	private static final Scope SCOPE = Scope.LIVE;
	private static final Currency CURRENCY_CZK = Currency.getInstance("CZK");
	private static final Currency CURRENCY_EUR = Currency.getInstance("EUR");
	private static final String PRICE_LIST_BASIC = "basic";
	private static final String PRICE_LIST_VIP = "vip";
	private static final PriceIndexKey KEY_BASIC_CZK = new PriceIndexKey(
		PRICE_LIST_BASIC, CURRENCY_CZK, PriceInnerRecordHandling.NONE
	);
	private static final PriceIndexKey KEY_VIP_CZK = new PriceIndexKey(
		PRICE_LIST_VIP, CURRENCY_CZK, PriceInnerRecordHandling.NONE
	);
	private static final PriceIndexKey KEY_BASIC_EUR = new PriceIndexKey(
		PRICE_LIST_BASIC, CURRENCY_EUR, PriceInnerRecordHandling.NONE
	);

	@Tag(SLOW)
	@DisplayName("generational proof test with random add/remove operations")
	@ParameterizedTest(name = "generational proof test with seed {0}")
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(@Nonnull GenerationalTestInput input) {
		final AtomicInteger globalInternalPriceId = new AtomicInteger(0);

		// keys used for random operations
		final PriceIndexKey[] keys = {KEY_BASIC_CZK, KEY_VIP_CZK, KEY_BASIC_EUR};

		runFor(
			input, 1_000,
			new TestState(
				new StringBuilder(8192),
				new HashMap<>(8)
			),
			(random, testState) -> {
				final StringBuilder codeBuffer = testState.code();
				codeBuffer.setLength(0);

				// rebuild a fresh super index and attached ref index from the tracked state each iteration
				final Map<PriceIndexKey, Set<Integer>> currentState = testState.trackedPricesByKey();
				final PriceSuperIndex superIndex = buildSuperIndex(currentState);
				final PriceRefIndex priceRefIndex = buildAttachedRefIndex(superIndex, currentState);

				// plan the random batch (shared with the rollback proof so both drive the identical draw sequence)
				final PlannedBatch batch = planRandomOps(random, keys, currentState, globalInternalPriceId, codeBuffer);
				final Map<PriceIndexKey, Set<Integer>> nextState = batch.nextState();

				// execute super index additions OUTSIDE the transaction
				applySuperAdditions(superIndex, keys, batch.addOps());

				try {
					assertStateAfterCommit(
						priceRefIndex,
						original -> applyPlannedOps(original, superIndex, keys, batch.addOps(), batch.removeOps()),
						(original, committed) -> {
							for (final PriceIndexKey key : keys) {
								final Set<Integer> expectedPrices =
									nextState.getOrDefault(key, Set.of());
								final PriceListAndCurrencyPriceRefIndex childIndex =
									committed.getPriceIndex(key);

								if (expectedPrices.isEmpty()) {
									assertNull(
										childIndex,
										"Expected no child for " + key +
											" but found one.\n" + codeBuffer
									);
								} else {
									assertNotNull(
										childIndex,
										"Expected child for " + key +
											" but found none.\n" + codeBuffer
									);
									assertFalse(
										childIndex.isEmpty(),
										"Child for " + key +
											" is empty but should not be.\n" + codeBuffer
									);
								}
							}
						}
					);
				} catch (Exception ex) {
					fail(ex.getMessage() + "\n" + codeBuffer, ex);
				}

				return new TestState(
					new StringBuilder(8192),
					nextState
				);
			}
		);
	}

	/**
	 * Generational proof that a **rolled-back** transaction discards every in-transaction price mutation and leaves
	 * the base {@link PriceRefIndex} byte-for-byte intact — the atomic-rollback contract of Ref: #569. Each generation
	 * rebuilds a fresh super + attached ref index from the (random-walking) reference model, captures a value oracle of
	 * that base, applies a random batch of add/remove mutations inside a transaction that is then rolled back, and
	 * asserts the base index is unchanged and no committed value was published.
	 */
	@Tag(SLOW)
	@Tag(TRANSACTION)
	@DisplayName("rollback discards every in-transaction mutation and leaves the base intact")
	@ParameterizedTest(name = "rollback proof test with seed {0}")
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalRollbackProofTest(@Nonnull GenerationalTestInput input) {
		final AtomicInteger globalInternalPriceId = new AtomicInteger(0);

		// keys used for random operations
		final PriceIndexKey[] keys = {KEY_BASIC_CZK, KEY_VIP_CZK, KEY_BASIC_EUR};

		runFor(
			input, 1_000,
			new TestState(
				new StringBuilder(8192),
				new HashMap<>(8)
			),
			(random, testState) -> {
				final StringBuilder codeBuffer = testState.code();
				codeBuffer.setLength(0);

				// rebuild a fresh super index and attached ref index from the tracked state each iteration
				final Map<PriceIndexKey, Set<Integer>> currentState = testState.trackedPricesByKey();
				final PriceSuperIndex superIndex = buildSuperIndex(currentState);
				final PriceRefIndex priceRefIndex = buildAttachedRefIndex(superIndex, currentState);

				// plan the random batch (shared with the commit proof so both drive the identical draw sequence)
				final PlannedBatch batch = planRandomOps(random, keys, currentState, globalInternalPriceId, codeBuffer);
				final Map<PriceIndexKey, Set<Integer>> nextState = batch.nextState();

				// execute super index additions OUTSIDE the transaction
				applySuperAdditions(superIndex, keys, batch.addOps());

				// value oracle of the base state that the rollback must return to
				final RefIndexSnapshot beforeRollback = snapshot(priceRefIndex);

				try {
					assertStateAfterRollback(
						priceRefIndex,
						original -> applyPlannedOps(original, superIndex, keys, batch.addOps(), batch.removeOps()),
						(original, committed) -> {
							assertNull(
								committed,
								"A rolled-back transaction must not publish a committed value!\n" + codeBuffer
							);
							assertEquals(
								beforeRollback, snapshot(original),
								"PriceRefIndex changed after rollback — atomic rollback leaked!\n" + codeBuffer
							);
						}
					);
				} catch (Exception ex) {
					fail(ex.getMessage() + "\n" + codeBuffer, ex);
				}

				// the reference model reflects the attempted (rolled-back) batch, so the next generation starts from a
				// different live state — a random walk that keeps the proof exploring fresh base indexes
				return new TestState(
					new StringBuilder(8192),
					nextState
				);
			}
		);
	}

	/**
	 * Builds a fresh {@link PriceSuperIndex} holding, for every tracked (key, internal price id) pair, a price whose
	 * entity primary key and price id both equal the internal price id (matching the existing proof's convention).
	 */
	@Nonnull
	private static PriceSuperIndex buildSuperIndex(@Nonnull Map<PriceIndexKey, Set<Integer>> currentState) {
		final PriceSuperIndex superIndex = new PriceSuperIndex();
		for (final Map.Entry<PriceIndexKey, Set<Integer>> entry : currentState.entrySet()) {
			for (final Integer ipId : entry.getValue()) {
				// use ipId as both entityPK and priceId for simplicity
				superIndex.addPrice(
					null, ipId, ipId,
					new PriceKey(ipId, entry.getKey().getPriceList(), entry.getKey().getCurrency()),
					entry.getKey().getRecordHandling(),
					null, null, 10000, 12100, superIndex
				);
			}
		}
		return superIndex;
	}

	/**
	 * Builds a fresh {@link PriceRefIndex} wired to a super-index resolver backed by the passed super index, then
	 * populates it from the tracked state. The population happens outside any transaction, so the base index carries
	 * the seeded prices directly.
	 */
	@Nonnull
	private static PriceRefIndex buildAttachedRefIndex(
		@Nonnull PriceSuperIndex superIndex,
		@Nonnull Map<PriceIndexKey, Set<Integer>> currentState
	) {
		final PriceRefIndex priceRefIndex = new PriceRefIndex(SCOPE);
		priceRefIndex.restorePriceRecords(superIndex);

		// populate the ref index from state
		for (final Map.Entry<PriceIndexKey, Set<Integer>> entry : currentState.entrySet()) {
			for (final Integer ipId : entry.getValue()) {
				priceRefIndex.addPrice(
					null, ipId, ipId,
					new PriceKey(ipId, entry.getKey().getPriceList(), entry.getKey().getCurrency()),
					entry.getKey().getRecordHandling(),
					null, null, 10000, 12100, superIndex
				);
			}
		}
		return priceRefIndex;
	}

	/**
	 * Plans a random batch of 1–5 add/remove operations over the three price keys, returning the ops together with the
	 * post-batch reference model (`nextState`). Adds allocate a fresh globally-unique internal price id; removes target
	 * an existing tracked price. Shared by the commit and rollback proofs so both drive the identical random-draw
	 * sequence. Each op is `{internalPriceId, keyIdx}`.
	 */
	@Nonnull
	private static PlannedBatch planRandomOps(
		@Nonnull Random random,
		@Nonnull PriceIndexKey[] keys,
		@Nonnull Map<PriceIndexKey, Set<Integer>> currentState,
		@Nonnull AtomicInteger globalInternalPriceId,
		@Nonnull StringBuilder codeBuffer
	) {
		final Map<PriceIndexKey, Set<Integer>> nextState = new HashMap<>(8);
		for (final Map.Entry<PriceIndexKey, Set<Integer>> entry : currentState.entrySet()) {
			nextState.put(entry.getKey(), new HashSet<>(entry.getValue()));
		}

		final List<int[]> addOps = new ArrayList<>(8);
		final List<int[]> removeOps = new ArrayList<>(8);

		final int opCount = 1 + random.nextInt(5);
		for (int i = 0; i < opCount; i++) {
			final int keyIdx = random.nextInt(keys.length);
			final PriceIndexKey selectedKey = keys[keyIdx];
			final Set<Integer> pricesForKey =
				nextState.computeIfAbsent(selectedKey, k -> new HashSet<>(4));

			if (pricesForKey.isEmpty() || random.nextBoolean()) {
				// plan an add -- use ipId as entityPK and priceId for consistency
				final int ipId = globalInternalPriceId.incrementAndGet();

				codeBuffer.append("ADD: ipId=").append(ipId)
					.append(" key=").append(selectedKey).append('\n');

				addOps.add(new int[]{ipId, keyIdx});
				pricesForKey.add(ipId);
			} else {
				// plan a remove
				final Integer ipIdToRemove = pricesForKey.iterator().next();

				codeBuffer.append("REMOVE: ipId=").append(ipIdToRemove)
					.append(" key=").append(selectedKey).append('\n');

				removeOps.add(new int[]{ipIdToRemove, keyIdx});
				pricesForKey.remove(ipIdToRemove);
				if (pricesForKey.isEmpty()) {
					nextState.remove(selectedKey);
				}
			}
		}
		return new PlannedBatch(nextState, addOps, removeOps);
	}

	/**
	 * Executes the planned additions on the super index — done OUTSIDE the transaction so the shared price records are
	 * resolvable when the ref index adds the same ids inside the transaction.
	 */
	private static void applySuperAdditions(
		@Nonnull PriceSuperIndex superIndex,
		@Nonnull PriceIndexKey[] keys,
		@Nonnull List<int[]> addOps
	) {
		for (final int[] op : addOps) {
			final PriceIndexKey key = keys[op[1]];
			superIndex.addPrice(
				null, op[0], op[0],
				new PriceKey(op[0], key.getPriceList(), key.getCurrency()),
				key.getRecordHandling(),
				null, null, 10000, 12100, superIndex
			);
		}
	}

	/**
	 * Applies the planned additions then removals to the ref index — the transaction body shared by the commit and
	 * rollback proofs. The `superIndex` is the GLOBAL price index backing `index`; the ref index resolves each
	 * combination's super index out of it and verifies the wiring identity on every mutation.
	 */
	private static void applyPlannedOps(
		@Nonnull PriceRefIndex index,
		@Nonnull PriceSuperIndex superIndex,
		@Nonnull PriceIndexKey[] keys,
		@Nonnull List<int[]> addOps,
		@Nonnull List<int[]> removeOps
	) {
		for (final int[] op : addOps) {
			final PriceIndexKey key = keys[op[1]];
			index.addPrice(
				null, op[0], op[0],
				new PriceKey(op[0], key.getPriceList(), key.getCurrency()),
				key.getRecordHandling(),
				null, null, 10000, 12100, superIndex
			);
		}
		for (final int[] op : removeOps) {
			final PriceIndexKey key = keys[op[1]];
			index.priceRemove(
				null, op[0], op[0],
				new PriceKey(op[0], key.getPriceList(), key.getCurrency()),
				key.getRecordHandling(),
				null, null, 10000, 12100, superIndex
			);
		}
	}

	/**
	 * Reads the full logical content of the aggregate ref index into a value-comparable snapshot: a per-key map of the
	 * child index contents, so two snapshots taken before and after a rollback can be compared with `.equals`.
	 */
	@Nonnull
	static RefIndexSnapshot snapshot(@Nonnull PriceRefIndex index) {
		final Map<PriceIndexKey, PriceIndexSnapshot> perKey = new HashMap<>();
		for (final PriceListAndCurrencyPriceRefIndex child : index.getPriceIndexes().values()) {
			perKey.put(child.getPriceIndexKey(), leafSnapshot(child));
		}
		return new RefIndexSnapshot(perKey);
	}

	/**
	 * Reads a single child price index into a value-comparable snapshot (indexed price ids, indexed entity ids and the
	 * ascending price records).
	 */
	@Nonnull
	private static PriceIndexSnapshot leafSnapshot(@Nonnull PriceListAndCurrencyPriceRefIndex index) {
		return new PriceIndexSnapshot(
			toList(index.getIndexedPriceIds().getArray()),
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
	 * The planned batch: the post-batch reference model plus the add/remove ops (`{internalPriceId, keyIdx}`).
	 */
	private record PlannedBatch(
		@Nonnull Map<PriceIndexKey, Set<Integer>> nextState,
		@Nonnull List<int[]> addOps,
		@Nonnull List<int[]> removeOps
	) {}

	/**
	 * Value-comparable content of a single price record — all fields participate in equality so a changed amount is
	 * caught, unlike {@link PriceRecordContract#equals} which keys on the internal price id alone.
	 */
	private record PriceRecordValue(
		int internalPriceId,
		int priceId,
		int entityPrimaryKey,
		int priceWithTax,
		int priceWithoutTax,
		int innerRecordId
	) {}

	/**
	 * Value-comparable snapshot of one child price index: its indexed price ids, indexed entity ids and ascending
	 * price records.
	 */
	private record PriceIndexSnapshot(
		@Nonnull List<Integer> indexedPriceIds,
		@Nonnull List<Integer> indexedEntityIds,
		@Nonnull List<PriceRecordValue> priceRecords
	) {}

	/**
	 * Value-comparable snapshot of the aggregate {@link PriceRefIndex}: each price key mapped to its child snapshot.
	 * Record equality gives deep structural comparison.
	 */
	record RefIndexSnapshot(
		@Nonnull Map<PriceIndexKey, PriceIndexSnapshot> perKey
	) {}

	/**
	 * State carried across generational test iterations.
	 *
	 * @param code              StringBuilder for debugging output on failure
	 * @param trackedPricesByKey mapping from `PriceIndexKey` to set of internal price ids
	 *                          currently in the ref index
	 */
	private record TestState(
		@Nonnull StringBuilder code,
		@Nonnull Map<PriceIndexKey, Set<Integer>> trackedPricesByKey
	) {
	}

}
