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

package io.evitadb.core.collection;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that the collection's split index counts ({@link IndexPopulation}) rewind with the {@link WarmUpSavepoint}
 * bracketing the root entity mutation that moved them.
 *
 * These counts are the one piece of index bookkeeping whose rollback is not structural. The transactional path derives
 * them at commit from the merge delta, so a discarded diff layer takes any would-be increment with it; the warm-up path
 * has no layer and moves them in place, which is exactly the case this journaling covers.
 *
 * Every assertion below reads the counts back through {@link IndexPopulation#countOf} rather than only
 * {@link IndexPopulation#total}, because a decrement and an increment that cancel out in the total can still leave two
 * individual slots wrong.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(TRANSACTION)
@DisplayName("Warm-up savepoint rollback of the collection's index counts")
class WarmUpSavepointIndexPopulationRollbackTest {
	private static final EntityIndexKey GLOBAL_LIVE = new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE);
	private static final EntityIndexKey GLOBAL_ARCHIVED = new EntityIndexKey(EntityIndexType.GLOBAL, Scope.ARCHIVED);
	private static final EntityIndexKey REFERENCED_LIVE = new EntityIndexKey(
		EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, "brand"
	);

	/**
	 * Closes a savepoint a failing test might have left bound to this thread — the binding is thread-wide, so a leaked
	 * savepoint would otherwise fail every subsequent test in this fork with a bogus "already open" error.
	 */
	@AfterEach
	void closeLeakedSavepoint() {
		final WarmUpSavepoint leaked = WarmUpSavepoint.getIfOpen();
		if (leaked != null) {
			leaked.commit();
		}
	}

	@Test
	@DisplayName("Rollback restores every counter slot the failed mutation moved")
	void shouldRestoreCountsAfterMixedCreatesAndRemovals() {
		final IndexPopulation population = new IndexPopulation();
		population.recordCreated(GLOBAL_LIVE);
		population.recordCreated(GLOBAL_ARCHIVED);
		population.recordCreated(REFERENCED_LIVE);
		population.recordCreated(REFERENCED_LIVE);

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		// a reference-heavy entity creates several indexes and drops others in one mutation; the mix below leaves the
		// TOTAL unchanged, so only the per-slot assertions can tell a correct restore from a lucky one
		population.recordCreated(REFERENCED_LIVE);
		population.recordCreated(REFERENCED_LIVE);
		population.recordRemoved(GLOBAL_ARCHIVED);
		population.recordRemoved(REFERENCED_LIVE);
		assertEquals(3, population.countOf(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE), "self-check");
		assertEquals(0, population.countOf(EntityIndexType.GLOBAL, Scope.ARCHIVED), "self-check");
		assertEquals(4, population.total(), "self-check: the batch moved two slots and left the total where it was");
		savepoint.rollback();

		assertEquals(1, population.countOf(EntityIndexType.GLOBAL, Scope.LIVE));
		assertEquals(1, population.countOf(EntityIndexType.GLOBAL, Scope.ARCHIVED));
		assertEquals(2, population.countOf(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE));
		assertEquals(4, population.total(), "Rollback must restore every slot to its pre-savepoint count.");
	}

	@Test
	@DisplayName("Rollback restores counts moved only by removals")
	void shouldRestoreCountsAfterRemovalsOnly() {
		final IndexPopulation population = new IndexPopulation();
		population.recordCreated(GLOBAL_LIVE);
		population.recordCreated(REFERENCED_LIVE);

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		population.recordRemoved(GLOBAL_LIVE);
		population.recordRemoved(REFERENCED_LIVE);
		assertEquals(0, population.total(), "self-check: the collection was emptied inside the savepoint");
		savepoint.rollback();

		assertEquals(1, population.countOf(EntityIndexType.GLOBAL, Scope.LIVE));
		assertEquals(1, population.countOf(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE));
	}

	@Test
	@DisplayName("Commit keeps the counts moved inside the savepoint")
	void shouldKeepCountsOnCommit() {
		final IndexPopulation population = new IndexPopulation();
		population.recordCreated(GLOBAL_LIVE);

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		population.recordCreated(REFERENCED_LIVE);
		savepoint.commit();

		assertEquals(1, population.countOf(EntityIndexType.GLOBAL, Scope.LIVE));
		assertEquals(1, population.countOf(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE));
		assertEquals(2, population.total(), "Commit must keep the savepoint's writes.");
	}

	@Test
	@DisplayName("A copy taken for the commit-time merge is journaled independently of the live counts")
	void shouldNotRewindTheLiveCountsThroughACopy() {
		final IndexPopulation population = new IndexPopulation();
		population.recordCreated(GLOBAL_LIVE);

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		// `copy()` is what the transactional merge works on; it must be a participant of its own, so rewinding it can
		// never reach back into the collection's live counts
		final IndexPopulation copy = population.copy();
		copy.recordCreated(REFERENCED_LIVE);
		population.recordCreated(GLOBAL_ARCHIVED);
		savepoint.rollback();

		assertEquals(1, population.total(), "The live counts must be back at their pre-savepoint values.");
		assertEquals(1, copy.total(), "The copy must be rewound on its own, to ITS pre-savepoint values.");
	}

}
