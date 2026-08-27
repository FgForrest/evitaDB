/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.index.attribute;

import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.dataType.Predecessor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Warm-up rollback coverage for {@link ChainIndex} — specifically for the **paged, head-aware** position tree it is
 * built on, which no other rollback suite reaches.
 *
 * `WarmUpSavepointUnorderedLookupRollbackTest` exercises `UnorderedLookupTree` through its three-argument constructor,
 * which produces the NON-paged shape the SortIndex family uses: leaves of `DEFAULT_BLOCK_SIZE` and no head structures.
 * `ChainIndex` builds `new TransactionalUnorderedIntArray(true)`, which is head-aware and therefore PAGED — leaves of
 * {@link io.evitadb.index.array.UnorderedLookupTree#PAGE_RECORDS} records plus a head bitset. Those are different node
 * shapes reached by different code, and conflating them is what let a whole leaf shape go unmeasured and untested
 * while a record said otherwise. This suite pins the paged one.
 *
 * Every test follows the same contract: capture the chain's exact order, mutate it inside an open
 * {@link WarmUpSavepoint}, roll back, and assert the order is restored **element for element** — not merely that the
 * chain is the right length or is internally consistent, since a rollback that dropped a record and re-added it
 * elsewhere would satisfy both of those while corrupting the ordering the index exists to provide.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see WarmUpSavepoint
 * @see ChainIndex
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(TRANSACTION)
@DisplayName("Warm-up savepoint rollback of the paged chain index")
class WarmUpSavepointChainIndexRollbackTest {

	/**
	 * Chain length that stays comfortably inside a single 1024-record leaf page, so the tests built on it exercise the
	 * in-page write paths without a split.
	 */
	private static final int SINGLE_PAGE_LENGTH = 400;
	/**
	 * Chain length that spans several leaf pages, so a burst on top of it drives real page splits and the
	 * whole-node-memento fallback those take.
	 */
	private static final int MULTI_PAGE_LENGTH = 2_600;

	/**
	 * Releases any savepoint a failing test left bound to this thread, so one failure cannot cascade into every test
	 * that runs after it on the same thread ({@link WarmUpSavepoint#open()} refuses to nest).
	 */
	@AfterEach
	void tearDown() {
		final WarmUpSavepoint leaked = WarmUpSavepoint.getIfOpen();
		if (leaked != null) {
			leaked.rollback();
		}
	}

	/**
	 * Builds a chain `1 -> 2 -> ... -> length` in a freshly created index.
	 *
	 * @param length number of elements to append
	 * @return the populated index
	 */
	@Nonnull
	private static ChainIndex newChain(int length) {
		final ChainIndex index = new ChainIndex(
			new AttributeIndexKey("category", "orderInCategory", null)
		);
		index.upsertPredecessor(Predecessor.HEAD, 1);
		for (int pk = 2; pk <= length; pk++) {
			index.upsertPredecessor(new Predecessor(pk - 1), pk);
		}
		return index;
	}

	/**
	 * Reads the index's whole logical order as a comparable reference value.
	 *
	 * @param index the index to read
	 * @return the ordered record ids
	 */
	@Nonnull
	private static int[] order(@Nonnull ChainIndex index) {
		return index.getUnorderedLookup().getArray();
	}

	@Test
	@DisplayName("Rollback undoes an append burst inside a single leaf page")
	void shouldRestoreChainAfterInPageAppendBurst() {
		final ChainIndex index = newChain(SINGLE_PAGE_LENGTH);
		final int[] expected = order(index);

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		for (int pk = SINGLE_PAGE_LENGTH + 1; pk <= SINGLE_PAGE_LENGTH + 50; pk++) {
			index.upsertPredecessor(new Predecessor(pk - 1), pk);
		}
		assertNotEquals(expected.length, order(index).length, "self-check: the burst must have changed the chain");
		savepoint.rollback();

		assertArrayEquals(expected, order(index), "Rollback must restore the exact pre-savepoint order.");
		assertTrue(index.isConsistent(), "Rollback must leave a consistent chain.");
	}

	@Test
	@DisplayName("Rollback undoes an append burst that split leaf pages")
	void shouldRestoreChainAfterPageSplittingAppendBurst() {
		final ChainIndex index = newChain(MULTI_PAGE_LENGTH);
		final int[] expected = order(index);

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		for (int pk = MULTI_PAGE_LENGTH + 1; pk <= MULTI_PAGE_LENGTH + 1_500; pk++) {
			index.upsertPredecessor(new Predecessor(pk - 1), pk);
		}
		assertNotEquals(expected.length, order(index).length, "self-check: the burst must have changed the chain");
		savepoint.rollback();

		assertArrayEquals(expected, order(index), "Rollback must restore the exact pre-savepoint order.");
		assertTrue(index.isConsistent(), "Rollback must leave a consistent chain.");
	}

	@Test
	@DisplayName("Rollback undoes removals")
	void shouldRestoreChainAfterRemovals() {
		final ChainIndex index = newChain(MULTI_PAGE_LENGTH);
		final int[] expected = order(index);

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		for (int pk = 100; pk <= 1_400; pk += 7) {
			index.removePredecessor(pk);
		}
		assertNotEquals(expected.length, order(index).length, "self-check: the removals must have changed the chain");
		savepoint.rollback();

		assertArrayEquals(expected, order(index), "Rollback must restore every removed record at its position.");
		assertTrue(index.isConsistent(), "Rollback must leave a consistent chain.");
	}

	@Test
	@DisplayName("Rollback undoes predecessor moves that relocate records")
	void shouldRestoreChainAfterPredecessorMoves() {
		final ChainIndex index = newChain(SINGLE_PAGE_LENGTH);
		final int[] expected = order(index);

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		// move a later element to directly follow an earlier one - a detach plus a re-insert, never a cycle
		for (int pk = 300; pk < 340; pk++) {
			index.upsertPredecessor(new Predecessor(pk - 250), pk);
		}
		assertNotEquals(
			Arrays.toString(expected), Arrays.toString(order(index)),
			"self-check: the moves must have changed the chain order"
		);
		savepoint.rollback();

		assertArrayEquals(expected, order(index), "Rollback must restore the exact pre-savepoint order.");
		assertTrue(index.isConsistent(), "Rollback must leave a consistent chain.");
	}

	@Test
	@DisplayName("Rollback undoes an interleaved burst of appends, removals and moves")
	void shouldRestoreChainAfterMixedBurst() {
		final ChainIndex index = newChain(MULTI_PAGE_LENGTH);
		final int[] expected = order(index);

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		int appended = MULTI_PAGE_LENGTH;
		for (int round = 0; round < 200; round++) {
			appended++;
			index.upsertPredecessor(new Predecessor(appended - 1), appended);
			index.removePredecessor(50 + round * 3);
			index.upsertPredecessor(new Predecessor(1_000 + round), 2_000 + round);
		}
		assertNotEquals(
			Arrays.toString(expected), Arrays.toString(order(index)),
			"self-check: the burst must have changed the chain order"
		);
		savepoint.rollback();

		assertArrayEquals(expected, order(index), "Rollback must restore the exact pre-savepoint order.");
		assertTrue(index.isConsistent(), "Rollback must leave a consistent chain.");
	}

	@Test
	@DisplayName("Commit keeps every change the savepoint bracketed")
	void shouldKeepChangesOnCommit() {
		final ChainIndex index = newChain(SINGLE_PAGE_LENGTH);

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		for (int pk = SINGLE_PAGE_LENGTH + 1; pk <= SINGLE_PAGE_LENGTH + 50; pk++) {
			index.upsertPredecessor(new Predecessor(pk - 1), pk);
		}
		final int[] afterBurst = order(index);
		savepoint.commit();

		assertArrayEquals(afterBurst, order(index), "Commit must not restore anything.");
		assertEquals(SINGLE_PAGE_LENGTH + 50, order(index).length, "Commit must keep every appended record.");
		assertTrue(index.isConsistent(), "Commit must leave a consistent chain.");
	}

}
