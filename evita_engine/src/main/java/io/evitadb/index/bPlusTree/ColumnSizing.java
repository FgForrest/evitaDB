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

package io.evitadb.index.bPlusTree;

import io.evitadb.utils.Assert;

/**
 * The one place the {@link ValueColumn} / {@link RecordColumn} family's grow and trim arithmetic lives.
 *
 * Every column in the family keeps a **logical** {@code capacity()} — the leaf block size it was created with, which
 * never moves — while its **physical** backing array is sized to the live content and grows geometrically. Seven
 * implementations share that policy, so the two numeric decisions it rests on are stated here once rather than
 * re-derived (and drifted) in each of them.
 *
 * The policy, and why each number is what it is:
 *
 * - **An empty column allocates nothing.** Columns park on the JVM-wide shared empty arrays until their first write;
 *   see each implementation's constructor.
 * - **The first allocation is {@link #MIN_PHYSICAL_LENGTH} slots.** The reduced value trees this sizing exists for
 *   are dominated by 1–4 distinct values, so a floor of four covers the common case with one allocation and no
 *   reallocation at all.
 * - **Growth doubles, capped at the logical capacity** (4 → 8 → … → the block size). A leaf that fills completely
 *   pays at most six reallocations across its whole life, against one insert per slot.
 * - **Trimming needs a 4:1 gap** ({@link #TRIM_RATIO}) before it fires, and lands on a power of two. The gap is
 *   hysteresis: without it a leaf oscillating around a power-of-two occupancy would alternate grow and trim on every
 *   commit. It mirrors the promotion / demotion gap the trigram postings already use, for the same reason.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class ColumnSizing {

	/**
	 * The smallest physical backing array a non-empty column ever holds.
	 */
	static final int MIN_PHYSICAL_LENGTH = 4;

	/**
	 * How much slack a column must carry before {@code trimmed()} shrinks it: the live count has to fall to at most
	 * {@code physicalLength / TRIM_RATIO}.
	 */
	static final int TRIM_RATIO = 4;

	private ColumnSizing() {
		throw new UnsupportedOperationException("Static arithmetic holder, not instantiable");
	}

	/**
	 * Computes the physical length a backing array must grow to in order to hold {@code requiredLength} slots.
	 *
	 * @param currentLength  the array's present physical length
	 * @param requiredLength the number of slots the caller is about to address; must exceed {@code currentLength}
	 * @param capacity       the column's logical capacity (the leaf block size), the growth cap
	 * @return the new physical length, never below {@code requiredLength} and never above {@code capacity}
	 */
	static int grownLength(int currentLength, int requiredLength, int capacity) {
		Assert.isPremiseValid(
			requiredLength <= capacity,
			() -> "A column can never be asked for more slots (" + requiredLength + ") than its logical capacity ("
				+ capacity + ") — the leaf splits before that happens."
		);
		if (requiredLength > capacity >> 1) {
			// past half the block, doubling would overshoot the cap anyway - go straight to it, which also keeps
			// both arithmetic steps below safely away from integer overflow whatever block size a tree is given
			return capacity;
		}
		final int doubled = Math.max(currentLength << 1, MIN_PHYSICAL_LENGTH);
		final int grown = doubled >= requiredLength ? doubled : nextPowerOfTwo(requiredLength);
		return Math.min(grown, capacity);
	}

	/**
	 * Computes the physical length {@code trimmed()} should shrink a backing array to, or {@code currentLength} when
	 * the slack does not justify the copy.
	 *
	 * @param size          the column's live entry count
	 * @param currentLength the array's present physical length
	 * @param capacity      the column's logical capacity (the leaf block size)
	 * @return the target physical length; equal to {@code currentLength} when no trim is warranted
	 */
	static int trimmedLength(int size, int currentLength, int capacity) {
		if (size > currentLength / TRIM_RATIO) {
			// not enough slack to pay for a copy — and shrinking here is what would make a leaf hovering around a
			// power of two alternate grow and trim on every commit
			return currentLength;
		}
		final int target = Math.min(Math.max(MIN_PHYSICAL_LENGTH, nextPowerOfTwo(size)), capacity);
		return target < currentLength ? target : currentLength;
	}

	/**
	 * The largest argument {@link #nextPowerOfTwo} can answer without overflowing a signed {@code int}. Both call
	 * sites are bounded far below it by the leaf block size, but the method is visible to the package and the bound
	 * is not obvious from its body.
	 */
	static final int MAX_POWER_OF_TWO = 1 << 30;

	/**
	 * Rounds {@code value} up to the nearest power of two, mapping {@code 0} and {@code 1} to {@code 1}.
	 *
	 * @param value the value to round up; must be in {@code [0, MAX_POWER_OF_TWO]}
	 * @return the smallest power of two greater than or equal to {@code value}, at least {@code 1}
	 */
	static int nextPowerOfTwo(int value) {
		Assert.isPremiseValid(
			value >= 0 && value <= MAX_POWER_OF_TWO,
			() -> "Cannot round " + value + " up to a power of two — the result would not fit a signed int."
		);
		return value <= 1 ? 1 : Integer.highestOneBit(value - 1) << 1;
	}
}
