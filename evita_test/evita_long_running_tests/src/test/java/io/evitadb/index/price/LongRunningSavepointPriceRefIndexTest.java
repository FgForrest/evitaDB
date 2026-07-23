/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;

/**
 * Generational randomized backfill proof that the aggregate {@link PriceRefIndex} — the price index driven by
 * {@link LongRunningPriceRefIndexTest} — snapshots and restores correctly under a per-entity savepoint (Ref: #1252).
 * Because the ref index is a {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} whose transactional
 * child map and per-child structures are all `Snapshotable`, the proof drives it directly and asserts its logical price
 * contents (each price key mapped to its child index contents) via
 * {@link LongRunningPriceRefIndexTest#snapshot(PriceRefIndex)}.
 *
 * Each generation seeds a fresh random non-empty ref index outside any transaction (wired to a super-index resolver
 * backed by a super index pre-populated with a fixed price pool), then within one real transaction applies a random
 * baseline batch of price mutations (standing for *prior* entities in the same transaction — these must SURVIVE the
 * savepoint rollback), opens a savepoint, applies a random in-savepoint batch preceded by a guaranteed-visible marker
 * mutation (standing for the *failing* entity — these must be REVERTED on rollback / KEPT on commit), and asserts the
 * price contents against the oracle captured at savepoint open. The transaction then commits so the commit-time
 * layer-sweep verification proves the restore left no dangling layer. All mutated price records live in the super index,
 * which is populated outside the transaction and only ever READ inside it, so the transaction enrolls the ref index
 * alone. The run is time-bounded; the random seed is echoed on failure for deterministic reproduction.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("PriceRefIndex savepoint rollback/commit backfill (generational fuzz)")
@Tag(INDEXING)
@Tag(PRICE)
@Tag(TRANSACTION)
class LongRunningSavepointPriceRefIndexTest implements TimeBoundedTestSupport {
	private static final int MAX_OPS = 10;
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

	@ParameterizedTest(name = "Savepoint rollback restores the exact pre-savepoint price contents")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint rollback restores the exact pre-savepoint price contents")
	void shouldRollBackPriceRefIndex(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final RefIndexState state = new RefIndexState(random);
			assertSavepointRollbackRestores(
				state.index,
				tested -> state.applyRandomMutations(random, 1 + random.nextInt(MAX_OPS)),
				LongRunningPriceRefIndexTest::snapshot,
				tested -> {
					// a guaranteed-visible mutation makes the in-savepoint batch non-vacuous
					state.forceMutation();
					state.applyRandomMutations(random, random.nextInt(MAX_OPS));
				}
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "Savepoint commit keeps the in-savepoint price contents")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint commit keeps the in-savepoint price contents")
	void shouldCommitPriceRefIndex(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final RefIndexState state = new RefIndexState(random);
			assertSavepointCommitKeeps(
				state.index,
				tested -> state.applyRandomMutations(random, 1 + random.nextInt(MAX_OPS)),
				LongRunningPriceRefIndexTest::snapshot,
				tested -> {
					state.forceMutation();
					state.applyRandomMutations(random, random.nextInt(MAX_OPS));
				}
			);
			return iteration + 1;
		});
	}

	/**
	 * A {@link PriceRefIndex} wired to a super-index resolver backed by a {@link PriceSuperIndex} pre-populated with a
	 * fixed price pool, paired with an in-test model (`tracked`: price key → internal price ids currently in the ref) so
	 * randomized mutations can be generated that keep the model and index in lockstep. Every internal price id maps to a
	 * single price key and is used as both the entity primary key and the price id (matching the sibling proof's
	 * convention), so every add uses a fresh slot. The pool (and a reserved block for forced mutations) is added to the
	 * super index outside any transaction; the ref index is seeded outside any transaction too, and its mutations happen
	 * within the framework's transaction — the super index is only READ there, so the transaction enrolls the ref alone.
	 */
	private static final class RefIndexState {
		private static final int POOL_PER_KEY = 40;
		private static final int FORCED_COUNT = 16;
		private static final int FORCED_BASE = 1_000_000;

		private final PriceIndexKey[] keys = {KEY_BASIC_CZK, KEY_VIP_CZK, KEY_BASIC_EUR};
		private final PriceSuperIndex superIndex = new PriceSuperIndex();
		private final PriceRefIndex index;
		private final Map<PriceIndexKey, List<Integer>> poolByKey = new HashMap<>();
		private final Map<PriceIndexKey, Set<Integer>> tracked = new HashMap<>();
		// reserved forced-id cursor; the reserved ids are pre-populated in the super so forced adds only touch the ref
		private int forcedCursor = 0;

		RefIndexState(@Nonnull Random random) {
			int ipId = 0;
			// build the pool and populate the super index (outside any transaction)
			for (final PriceIndexKey key : this.keys) {
				final List<Integer> pool = new ArrayList<>(POOL_PER_KEY);
				for (int i = 0; i < POOL_PER_KEY; i++) {
					ipId++;
					pool.add(ipId);
					addToSuper(ipId, key);
				}
				this.poolByKey.put(key, pool);
			}
			// reserved forced ids: pre-populated in the super so forceMutation only touches the ref inside the transaction
			for (int i = 0; i < FORCED_COUNT; i++) {
				addToSuper(FORCED_BASE + i, KEY_BASIC_CZK);
			}
			// attach the ref index and seed a random non-empty subset (outside any transaction)
			this.index = attach(this.superIndex);
			final int seedOperations = 20 + random.nextInt(20);
			for (int i = 0; i < seedOperations; i++) {
				addRandomPrice(random);
			}
		}

		/**
		 * Applies `count` random price add/remove mutations, mirrored into the model.
		 */
		void applyRandomMutations(@Nonnull Random random, int count) {
			for (int i = 0; i < count; i++) {
				if (isEmpty() || random.nextBoolean()) {
					addRandomPrice(random);
				} else {
					removeRandomPrice(random);
				}
			}
		}

		/**
		 * Applies one guaranteed-visible change: adds a price for a brand-new reserved internal price id that random ops
		 * never touch (and whose record is already present in the super index), so the in-savepoint batch is never a no-op.
		 */
		void forceMutation() {
			final int forcedId = FORCED_BASE + this.forcedCursor++;
			this.tracked.computeIfAbsent(KEY_BASIC_CZK, k -> new HashSet<>()).add(forcedId);
			addToRef(forcedId, KEY_BASIC_CZK);
		}

		private boolean isEmpty() {
			for (final Set<Integer> ids : this.tracked.values()) {
				if (!ids.isEmpty()) {
					return false;
				}
			}
			return true;
		}

		/**
		 * Adds a random not-yet-tracked pool price for a random key, mirrored into the model; bounded retries avoid an
		 * infinite spin on a collision and give up silently as a harmless no-op when the key's pool is exhausted.
		 */
		private void addRandomPrice(@Nonnull Random random) {
			final PriceIndexKey key = this.keys[random.nextInt(this.keys.length)];
			final List<Integer> pool = this.poolByKey.get(key);
			final Set<Integer> trackedForKey = this.tracked.computeIfAbsent(key, k -> new HashSet<>());
			for (int attempt = 0; attempt < 10; attempt++) {
				final int ipId = pool.get(random.nextInt(pool.size()));
				if (trackedForKey.add(ipId)) {
					addToRef(ipId, key);
					return;
				}
			}
		}

		/**
		 * Removes a random tracked price, mirrored into the model; a no-op when the model is empty.
		 */
		private void removeRandomPrice(@Nonnull Random random) {
			final List<PriceIndexKey> nonEmptyKeys = new ArrayList<>();
			for (final Map.Entry<PriceIndexKey, Set<Integer>> entry : this.tracked.entrySet()) {
				if (!entry.getValue().isEmpty()) {
					nonEmptyKeys.add(entry.getKey());
				}
			}
			if (nonEmptyKeys.isEmpty()) {
				return;
			}
			final PriceIndexKey key = nonEmptyKeys.get(random.nextInt(nonEmptyKeys.size()));
			final Set<Integer> trackedForKey = this.tracked.get(key);
			final List<Integer> ids = new ArrayList<>(trackedForKey);
			final int ipId = ids.get(random.nextInt(ids.size()));
			trackedForKey.remove(ipId);
			removeFromRef(ipId, key);
		}

		private void addToSuper(int ipId, @Nonnull PriceIndexKey key) {
			this.superIndex.addPrice(
				null, ipId, ipId,
				new PriceKey(ipId, key.getPriceList(), key.getCurrency()),
				key.getRecordHandling(),
				null, null, 10000, 12100
			);
		}

		private void addToRef(int ipId, @Nonnull PriceIndexKey key) {
			this.index.addPrice(
				null, ipId, ipId,
				new PriceKey(ipId, key.getPriceList(), key.getCurrency()),
				key.getRecordHandling(),
				null, null, 10000, 12100
			);
		}

		private void removeFromRef(int ipId, @Nonnull PriceIndexKey key) {
			this.index.priceRemove(
				null, ipId, ipId,
				new PriceKey(ipId, key.getPriceList(), key.getCurrency()),
				key.getRecordHandling(),
				null, null, 10000, 12100
			);
		}

		/**
		 * Builds a {@link PriceRefIndex} wired to a super-index resolver backed by the passed super index (no prices are
		 * added to the ref here).
		 */
		@Nonnull
		private static PriceRefIndex attach(@Nonnull PriceSuperIndex superIndex) {
			final PriceRefIndex refIndex = new PriceRefIndex(SCOPE);
			refIndex.wireSuperIndexes(superIndex::getPriceIndex);
			return refIndex;
		}
	}

}
