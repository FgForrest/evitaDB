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

package io.evitadb.core.query.algebra.identity;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Arrays;

/**
 * Reusable identity-pair set shared by nested comparisons on one thread.
 * Every outermost exit clears references, including unequal and exceptional exits.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NotThreadSafe
final class ComparisonScope {
	/** Per-thread scope, allocated only when side keys or distinct child references require recursion. */
	private static final ThreadLocal<ComparisonScope> SCOPES = new ThreadLocal<>();
	/** Initial reference-pair table capacity. */
	private static final int INITIAL_CAPACITY = 64;
	/** Retains at most 1,024 slots per array between comparisons; larger tables return to the initial capacity. */
	private static final int RETAINED_CAPACITY_LIMIT = 1_024;
	/** Left keys of reference pairs. */
	private FormulaKey[] left = new FormulaKey[INITIAL_CAPACITY];
	/** Right keys of reference pairs. */
	private FormulaKey[] right = new FormulaKey[INITIAL_CAPACITY];
	/** Number of proven equal pairs. */
	private int size;
	/** Active nested comparisons. */
	private int depth;

	/** Creates an empty reusable scope. */
	private ComparisonScope() {
	}

	/**
	 * Returns the comparing thread's scope.
	 *
	 * @return the current thread's reusable scope, allocated on first access and confined to that thread
	 */
	@Nonnull
	static ComparisonScope current() {
		final ComparisonScope scope = SCOPES.get();
		if (scope != null) {
			return scope;
		}
		final ComparisonScope newScope = new ComparisonScope();
		SCOPES.set(newScope);
		return newScope;
	}

	/** Opens one nested comparison. */
	void enter() {
		this.depth++;
	}

	/** Closes a comparison, releasing populated storage and bounding idle retention on the outermost exit. */
	void leave() {
		if (--this.depth == 0 && this.size != 0) {
			if (this.left.length > RETAINED_CAPACITY_LIMIT) {
				this.left = new FormulaKey[INITIAL_CAPACITY];
				this.right = new FormulaKey[INITIAL_CAPACITY];
			} else {
				Arrays.fill(this.left, null);
				Arrays.fill(this.right, null);
			}
			this.size = 0;
		}
	}

	/**
	 * Finds a reference pair without calling key equality.
	 *
	 * @param first the left key reference identifying the ordered comparison pair
	 * @param second the right key reference identifying the ordered comparison pair
	 * @return whether this exact ordered pair of references has already been proven equal in the active scope
	 */
	boolean contains(@Nonnull FormulaKey first, @Nonnull FormulaKey second) {
		return this.left[find(first, second)] != null;
	}

	/**
	 * Records a pair only after its complete comparison has succeeded.
	 *
	 * @param first the proven-equal left key reference retained until the outermost comparison exits
	 * @param second the proven-equal right key reference retained until the outermost comparison exits
	 */
	void add(@Nonnull FormulaKey first, @Nonnull FormulaKey second) {
		if ((this.size + 1) * 2 >= this.left.length) {
			final FormulaKey[] oldLeft = this.left;
			final FormulaKey[] oldRight = this.right;
			this.left = new FormulaKey[oldLeft.length * 2];
			this.right = new FormulaKey[oldRight.length * 2];
			this.size = 0;
			for (int i = 0; i < oldLeft.length; i++) {
				if (oldLeft[i] != null) {
					add(oldLeft[i], oldRight[i]);
				}
			}
		}
		final int slot = find(first, second);
		if (this.left[slot] == null) {
			this.left[slot] = first;
			this.right[slot] = second;
			this.size++;
		}
	}

	/**
	 * Returns the slot holding the reference pair, or the empty slot that ends its probe sequence.
	 *
	 * @param first the left key reference identifying the ordered comparison pair
	 * @param second the right key reference identifying the ordered comparison pair
	 * @return the occupied slot for this reference pair, or the empty slot where it can be inserted
	 */
	private int find(@Nonnull FormulaKey first, @Nonnull FormulaKey second) {
		int slot = slot(first, second);
		while (this.left[slot] != null && (this.left[slot] != first || this.right[slot] != second)) {
			slot = (slot + 1) & (this.left.length - 1);
		}
		return slot;
	}

	/**
	 * Hashes the identities of both references into the current table. Both identity hashes are packed into one word
	 * before mixing: as the seed of `mix`, a 32-bit value would not reach the low bits that select the slot.
	 *
	 * @param first the left key reference identifying the ordered comparison pair
	 * @param second the right key reference identifying the ordered comparison pair
	 * @return the initial probe slot in the current table, in the range from zero to table capacity minus one
	 */
	private int slot(@Nonnull FormulaKey first, @Nonnull FormulaKey second) {
		final long pair = ((long) System.identityHashCode(first) << 32) |
			(System.identityHashCode(second) & 0xFFFF_FFFFL);
		return (int) KeyAccumulator.mix(0, pair) & (this.left.length - 1);
	}
}
