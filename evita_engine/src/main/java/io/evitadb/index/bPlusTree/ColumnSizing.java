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
 * The policy is **public to the engine, not to the API**: the same grow-and-trim arithmetic now also sizes the leaf
 * containers of {@link io.evitadb.index.array.UnorderedLookupTree}, which lives in a neighbouring package. Widening
 * the visibility was preferred to a second copy of the numbers — this class exists precisely so the two decisions
 * below are stated once and cannot drift.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class ColumnSizing {

	/**
	 * The smallest physical backing array a non-empty column ever holds.
	 */
	public static final int MIN_PHYSICAL_LENGTH = 4;

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
	public static int grownLength(int currentLength, int requiredLength, int capacity) {
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
	 * Computes the physical length a copy taken by the MVCC decouple should allocate when the layer's very first act
	 * will be an insert: the source's own length, except for an exactly-full column, which is copied straight to the
	 * length {@link #grownLength} would give its next insert.
	 *
	 * A committed leaf whose columns are exactly full is common rather than exotic — both halves of every split are
	 * born that way, and after a restart every bulk-loaded page is too. Copying such a column at its short length and
	 * growing it one statement later costs two allocations where one would do, which the cursor-allocation benchmark
	 * measured as +489 B per transactional insert. Anticipating the grow here folds the pair back into one copy.
	 *
	 * Two shapes are deliberately left at the source's length: an **empty** column, which stays parked on its shared
	 * empty array rather than allocating for an insert that may never come, and one whose backing has already reached
	 * the logical capacity, which has no room to grow into.
	 *
	 * @param size          the column's live entry count
	 * @param currentLength the array's present physical length
	 * @param capacity      the column's logical capacity (the leaf block size)
	 * @return the physical length the copy should allocate, never below {@code currentLength}
	 */
	public static int headroomLength(int size, int currentLength, int capacity) {
		if (size == 0 || size < currentLength || currentLength >= capacity) {
			return currentLength;
		}
		return grownLength(currentLength, Math.min(size + 1, capacity), capacity);
	}

	/**
	 * Refuses a bulk load whose entry count runs past the column's logical capacity.
	 *
	 * The incremental path carries this premise inside {@link #grownLength}, which every {@code ensurePhysicalLength}
	 * routes through. A bulk load sizes its backing array straight to {@code count} and never asks {@link #grownLength}
	 * anything, so without this it is the one way into the family that can build a column whose live run runs past
	 * the leaf block it belongs to — after which the leaf reports more buckets than it may hold, and reads the array
	 * could serve are refused as out of capacity by the empty-slot guards.
	 *
	 * @param count    the number of live entries the caller is loading
	 * @param capacity the column's logical capacity (the leaf block size)
	 */
	public static void assertLoadFitsCapacity(int count, int capacity) {
		Assert.isPremiseValid(
			count >= 0 && count <= capacity,
			() -> "A column can never be loaded with more entries (" + count + ") than its logical capacity ("
				+ capacity + ") — the page the entries come from is bounded by the very same block size."
		);
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
	public static int trimmedLength(int size, int currentLength, int capacity) {
		if (size > currentLength / TRIM_RATIO) {
			// not enough slack to pay for a copy — and shrinking here is what would make a leaf hovering around a
			// power of two alternate grow and trim on every commit
			return currentLength;
		}
		final int target = Math.min(Math.max(MIN_PHYSICAL_LENGTH, nextPowerOfTwo(size)), capacity);
		return Math.min(target, currentLength);
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
