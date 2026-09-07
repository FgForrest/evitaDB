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
import io.evitadb.core.transaction.memory.AbstractSavepointFuzzTest;
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.index.price.LongRunningPriceSuperIndexTest.PriceIndexSnapshot;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.TRANSACTION;

/**
 * Generational randomized backfill proof that {@link PriceListAndCurrencyPriceSuperIndex} — the leaf price index driven
 * by {@link LongRunningPriceSuperIndexTest} — snapshots and restores correctly under a per-entity savepoint (Ref:
 * #1252). Because the leaf index is a {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} whose
 * transactional structures are all `Snapshotable`, the proof drives it directly and asserts its logical price contents
 * (indexed price ids, indexed entity ids and the ascending price records) via
 * {@link LongRunningPriceSuperIndexTest#snapshot(PriceListAndCurrencyPriceSuperIndex)}.
 *
 * Each generation seeds a fresh random non-empty index outside any transaction, then within one real transaction
 * applies a random baseline batch of price mutations (standing for *prior* entities in the same transaction — these
 * must SURVIVE the savepoint rollback), opens a savepoint, applies a random in-savepoint batch preceded by a
 * guaranteed-visible marker mutation (standing for the *failing* entity — these must be REVERTED on rollback / KEPT on
 * commit), and asserts the price contents against the oracle captured at savepoint open. The transaction then commits
 * so the commit-time layer-sweep verification proves the restore left no dangling layer. The run is time-bounded; the
 * random seed is echoed on failure for deterministic reproduction.
 *
 * The scenario is declared once and run by {@link AbstractSavepointFuzzTest} in BOTH phases: the transactional
 * savepoint described above, and the WARM_UP savepoint where the same writes land straight on the delegate
 * structures and are rewound from the inverses they journal themselves. See that class for the shape of one
 * generation, for the mid-savepoint read every case is asserted through, and for why the warm-up half runs
 * exclusively.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("PriceSuperIndex savepoint rollback/commit backfill (generational fuzz)")
@Tag(INDEXING)
@Tag(PRICE)
@Tag(TRANSACTION)
class LongRunningSavepointPriceSuperIndexTest extends AbstractSavepointFuzzTest<PriceIndexSnapshot> {
	private static final int MAX_OPS = 10;
	private static final Currency CURRENCY_CZK = Currency.getInstance("CZK");
	private static final String PRICE_LIST = "basic";
	private static final PriceIndexKey KEY = new PriceIndexKey(PRICE_LIST, CURRENCY_CZK, PriceInnerRecordHandling.NONE);

	@Nonnull
	@Override
	protected FuzzGeneration<PriceIndexSnapshot> newGeneration(@Nonnull Random random) {
		return new SuperIndexState(random);
	}

	/**
	 * A {@link PriceListAndCurrencyPriceSuperIndex} paired with an in-test model of its live price records (keyed by
	 * internal price id) so randomized mutations can be generated that keep the model and index in lockstep. The initial
	 * non-empty index is seeded outside any transaction; mutations are applied to the index (and mirrored in the model)
	 * within the framework's transaction. Globally unique price-id / internal-price-id sequences guarantee every add
	 * uses a fresh (entityPrimaryKey, priceId) slot, so no price is ever rejected as already assigned.
	 */
	private static final class SuperIndexState implements FuzzGeneration<PriceIndexSnapshot> {
		private static final int MAX_ENTITY_ID = 10;

		private final PriceListAndCurrencyPriceSuperIndex index = new PriceListAndCurrencyPriceSuperIndex(KEY);
		private final Map<Integer, PriceEntry> model = new HashMap<>();
		private int priceIdSeq = 0;
		private int internalPriceIdSeq = 0;
		// reserved entity-id sequence for guaranteed-new forced mutations, kept clear of the 1..MAX_ENTITY_ID range
		private int forcedEntitySeq = 1000;

		SuperIndexState(@Nonnull Random random) {
			final int seedOperations = 20 + random.nextInt(20);
			for (int i = 0; i < seedOperations; i++) {
				addRandomPrice(random);
			}
		}

		@Nonnull
		@Override
		public TransactionalStateProducer<?> subject() {
			return this.index;
		}

		@Nonnull
		@Override
		public PriceIndexSnapshot contents() {
			return LongRunningPriceSuperIndexTest.snapshot(this.index);
		}

		@Override
		public void applyBaselineOperations(@Nonnull Random random) {
			applyRandomMutations(random, 1 + random.nextInt(MAX_OPS));
		}

		@Override
		public void applySavepointOperations(@Nonnull Random random) {
			applyRandomMutations(random, random.nextInt(MAX_OPS));
			// applied LAST: a marker applied first enters the model and a later random operation can undo it
			forceMutation();
		}

		/**
		 * Applies `count` random price add/remove mutations, mirrored into the model.
		 */
		void applyRandomMutations(@Nonnull Random random, int count) {
			for (int i = 0; i < count; i++) {
				if (this.model.isEmpty() || random.nextBoolean()) {
					addRandomPrice(random);
				} else {
					removeRandomPrice(random);
				}
			}
		}

		/**
		 * Applies one guaranteed-visible change: adds a price for a brand-new entity id drawn from a reserved sequence
		 * that random ops never touch, so the in-savepoint batch is never a no-op.
		 */
		void forceMutation() {
			final int entityId = ++this.forcedEntitySeq;
			final int priceId = ++this.priceIdSeq;
			final int internalPriceId = ++this.internalPriceIdSeq;
			this.index.addPrice(new PriceRecord(internalPriceId, priceId, entityId, 121, 100), null);
			this.model.put(internalPriceId, new PriceEntry(entityId, internalPriceId, null));
		}

		/**
		 * Adds a random price for a not-yet-used (globally unique) price-id slot, mirrored into the model.
		 */
		private void addRandomPrice(@Nonnull Random random) {
			final int entityId = 1 + random.nextInt(MAX_ENTITY_ID);
			final int priceId = ++this.priceIdSeq;
			final int internalPriceId = ++this.internalPriceIdSeq;
			final int priceWithoutTax = random.nextInt(1000);
			final int priceWithTax = (int) (priceWithoutTax * 1.21);
			final int differenceInMinutes = 1 + random.nextInt(10_000);
			final OffsetDateTime from = OffsetDateTime.now().minusMinutes(differenceInMinutes);
			final DateTimeRange validity = DateTimeRange.between(from, from.plusMinutes(differenceInMinutes));
			this.index.addPrice(
				new PriceRecord(internalPriceId, priceId, entityId, priceWithTax, priceWithoutTax),
				validity
			);
			this.model.put(internalPriceId, new PriceEntry(entityId, internalPriceId, validity));
		}

		/**
		 * Removes a random present price, passing the exact validity used on insertion so the validity range index is
		 * unwound cleanly; mirrored into the model.
		 */
		private void removeRandomPrice(@Nonnull Random random) {
			final List<Integer> ids = new ArrayList<>(this.model.keySet());
			final int internalPriceId = ids.get(random.nextInt(ids.size()));
			final PriceEntry entry = this.model.remove(internalPriceId);
			this.index.removePrice(entry.entityPrimaryKey(), entry.internalPriceId(), entry.validity());
		}
	}

	/**
	 * In-test model entry mirroring a single indexed price: the entity primary key, its internal price id and the exact
	 * validity used on insertion (`null` meaning always-valid), so removals can be replayed against the index.
	 */
	private record PriceEntry(
		int entityPrimaryKey,
		int internalPriceId,
		@Nullable DateTimeRange validity
	) {}

}
