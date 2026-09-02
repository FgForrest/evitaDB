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

import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * How many indexes one entity collection holds, split by {@link EntityIndexType} and {@link Scope}. Maintained
 * incrementally so that reporting the split costs a fixed number of array reads rather than a walk over the index map.
 *
 * **Why this exists rather than counting on demand.** A production collection reaches hundreds of thousands of
 * per-referenced-entity indexes, and the index summary is part of the *polled* statistics surface - a management screen
 * refreshes it on a timer. Counting them per request would make the poll's cost grow with the catalog's data volume,
 * which is exactly what the cost rule of the statistics API forbids.
 *
 * **Why the counts are only ever moved on the two paths that publish a change, never at the call sites.** The index map
 * is transactional: inside a transaction, creating or dropping an index writes into a diff layer that a rollback
 * discards. A counter incremented at that moment would keep its increment and stay wrong for the life of the process.
 * So the transactional path moves the counts once, at commit, from the delta the merge already computes
 * (`EntityCollection#pruneMergeIndexes`), and the non-transactional bulk-load path - which has no layer to discard and
 * mutates the map in place - moves them inline. Rollback correctness is therefore structural rather than something a
 * counter has to remember to undo.
 *
 * Not thread-safe by itself: an instance belongs to one {@link EntityCollection}, which is itself replaced (not
 * mutated) at every catalog version boundary, and the counts are moved only under the same guarantees that guard the
 * index map they describe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see EntityCollection#getIndexCount()
 */
final class IndexPopulation {
	/**
	 * Number of scopes, which is the stride of {@link #countsByTypeAndScope}.
	 */
	private static final int SCOPE_COUNT = Scope.values().length;
	/**
	 * One counter per (index type, scope) pair, indexed by `type.ordinal() * SCOPE_COUNT + scope.ordinal()`. A flat
	 * `int[]` of a dozen cells rather than a map: the whole point is that reading the split allocates nothing.
	 */
	private final int[] countsByTypeAndScope;

	/**
	 * Creates an empty population - the state of a collection that holds no index yet.
	 */
	IndexPopulation() {
		this.countsByTypeAndScope = new int[EntityIndexType.values().length * SCOPE_COUNT];
	}

	private IndexPopulation(@Nonnull int[] countsByTypeAndScope) {
		this.countsByTypeAndScope = countsByTypeAndScope;
	}

	/**
	 * Returns an independent copy, so the next catalog version can move its counts without disturbing the version it
	 * was derived from.
	 *
	 * @return a copy holding the same counts
	 */
	@Nonnull
	IndexPopulation copy() {
		return new IndexPopulation(Arrays.copyOf(this.countsByTypeAndScope, this.countsByTypeAndScope.length));
	}

	/**
	 * Records that the collection gained one index under `indexKey`.
	 *
	 * @param indexKey key of the created index
	 */
	void recordCreated(@Nonnull EntityIndexKey indexKey) {
		this.countsByTypeAndScope[slotOf(indexKey)]++;
	}

	/**
	 * Records that the collection lost the index under `indexKey`.
	 *
	 * @param indexKey key of the dropped index
	 */
	void recordRemoved(@Nonnull EntityIndexKey indexKey) {
		final int slot = slotOf(indexKey);
		// a counter that has gone negative means a drop was counted the create never was - the two paths that move
		// these counts have diverged, and every figure derived from them is wrong from here on. Better to say so at
		// the point of divergence than to serve a plausible-looking negative index count
		Assert.isPremiseValid(
			this.countsByTypeAndScope[slot] > 0,
			() -> "Index `" + indexKey + "` was dropped from a collection that holds none of its kind and scope!"
		);
		this.countsByTypeAndScope[slot]--;
	}

	/**
	 * Returns how many indexes of one type live in one scope.
	 *
	 * @param indexType type of index to count
	 * @param scope     scope to count within
	 * @return number of such indexes, `0` when there are none
	 */
	int countOf(@Nonnull EntityIndexType indexType, @Nonnull Scope scope) {
		return this.countsByTypeAndScope[indexType.ordinal() * SCOPE_COUNT + scope.ordinal()];
	}

	/**
	 * Returns the total number of indexes the collection holds, across every type and scope.
	 *
	 * @return total index count
	 */
	int total() {
		int total = 0;
		for (final int count : this.countsByTypeAndScope) {
			total += count;
		}
		return total;
	}

	/**
	 * Resolves the counter cell of one index key.
	 *
	 * @param indexKey key to locate
	 * @return index into {@link #countsByTypeAndScope}
	 */
	private static int slotOf(@Nonnull EntityIndexKey indexKey) {
		return indexKey.type().ordinal() * SCOPE_COUNT + indexKey.scope().ordinal();
	}

}
