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

package io.evitadb.core.query.algebra.base;

import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;

import javax.annotation.Nonnull;

/**
 * Second implementation of the signed-multiplicity computation
 * (`{ v : count(plus, v) - count(minus, v) > 0 }`), carrying the counts as **binary planes of roaring bitmaps**
 * instead of as a scatter into a dense counter array.
 *
 * ## The representation
 *
 * A family's per-record count is held as `B = ceil(log2(k + 1))` bitmaps, where `v` belongs to plane *i* exactly
 * when bit *i* of that record's count is set. Adding one operand is a binary increment restricted to that operand's
 * members - a ripple of `XOR` (sum) and `AND` (carry) across the planes - and the ripple stops the moment the carry
 * is empty, which is the common case because carries only survive where counts actually overlap.
 *
 * Comparing the two families is a borrow-propagating subtraction across the plane pairs. `cp > cm` holds exactly
 * where the difference is non-zero **and** no borrow escaped the top plane.
 *
 * ## This is a TEST implementation. It lost, and it is kept on purpose.
 *
 * It was proposed as the better fit for the measured workload and **measured 2.6x SLOWER** than
 * {@link RangeCountKernel} at the production shape (19,764 vs 3,746 µs/op), allocating **87.6 MB/op** against the
 * baseline's 18.3 MB - because `PersistentRoaringBitmap` set operations are immutable, so each of the ~200,000
 * plane operations (13 planes x 15,205 operands) allocates a fresh bitmap. "Container-native and
 * density-independent" said nothing about allocation, and that is why it lost.
 *
 * It lives in test sources rather than in the engine because shipping a losing kernel would be dead code. Its
 * remaining value is as a THIRD independent implementation in the differential test: a defect would have to
 * appear identically in a map-based counter, a chunked scatter and a plane network to go unnoticed. Do not
 * re-propose it as a production kernel without a measurement that contradicts the one above.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class RangeBitSlicedKernel {

	private RangeBitSlicedKernel() {
		throw new UnsupportedOperationException("Kernel holder must not be instantiated!");
	}

	/**
	 * Computes the signed multiplicity result of the two families.
	 *
	 * @param plus  bitmaps contributing `+1` per membership
	 * @param minus bitmaps contributing `-1` per membership
	 * @return records whose signed count is strictly positive
	 */
	@Nonnull
	static Bitmap compute(@Nonnull Bitmap[] plus, @Nonnull Bitmap[] minus) {
		final int plusCount = countNonEmpty(plus);
		if (plusCount == 0) {
			// nothing can reach a strictly positive count
			return EmptyBitmap.INSTANCE;
		}
		final int minusCount = countNonEmpty(minus);
		// the planes must be wide enough to hold the LARGER of the two counts, because the subtraction below reads
		// both families at the same width
		final int planeCount = 32 - Integer.numberOfLeadingZeros(Math.max(plusCount, minusCount));

		final PersistentRoaringBitmap[] plusPlanes = buildPlanes(plus, planeCount);
		if (minusCount == 0) {
			// with nothing to subtract, any non-zero count is strictly positive - that is the union of the family
			return toBitmap(orAll(plusPlanes));
		}
		final PersistentRoaringBitmap[] minusPlanes = buildPlanes(minus, planeCount);

		// borrow-propagating subtraction: d_i = p_i ^ m_i ^ borrow, borrow' = (~p_i & (m_i | borrow)) | (m_i & borrow)
		PersistentRoaringBitmap borrow = new PersistentRoaringBitmap();
		PersistentRoaringBitmap differenceAny = new PersistentRoaringBitmap();
		for (int i = 0; i < planeCount; i++) {
			final PersistentRoaringBitmap p = plusPlanes[i];
			final PersistentRoaringBitmap m = minusPlanes[i];
			final PersistentRoaringBitmap difference = PersistentRoaringBitmap.xor(
				PersistentRoaringBitmap.xor(p, m), borrow
			);
			differenceAny = PersistentRoaringBitmap.or(differenceAny, difference);
			borrow = PersistentRoaringBitmap.or(
				PersistentRoaringBitmap.andNot(PersistentRoaringBitmap.or(m, borrow), p),
				PersistentRoaringBitmap.and(m, borrow)
			);
		}
		// strictly greater == the difference is non-zero AND no borrow escaped the top plane
		return toBitmap(PersistentRoaringBitmap.andNot(differenceAny, borrow));
	}

	/**
	 * Builds the binary planes carrying each record's membership count within one family.
	 *
	 * @param family     the operands to count
	 * @param planeCount how many bits the count is carried in
	 * @return the planes, least significant first
	 */
	@Nonnull
	private static PersistentRoaringBitmap[] buildPlanes(@Nonnull Bitmap[] family, int planeCount) {
		final PersistentRoaringBitmap[] planes = new PersistentRoaringBitmap[planeCount];
		for (int i = 0; i < planeCount; i++) {
			planes[i] = new PersistentRoaringBitmap();
		}
		for (final Bitmap operand : family) {
			if (operand.isEmpty()) {
				continue;
			}
			// increment by one exactly on this operand's members; the carry dies as soon as it stops overlapping
			PersistentRoaringBitmap carry = RoaringBitmapBackedBitmap.getRoaringBitmap(operand);
			for (int i = 0; i < planeCount && !carry.isEmpty(); i++) {
				final PersistentRoaringBitmap nextCarry = PersistentRoaringBitmap.and(planes[i], carry);
				planes[i] = PersistentRoaringBitmap.xor(planes[i], carry);
				carry = nextCarry;
			}
			// planeCount is derived from the operand count, so a carry can never survive the top plane
		}
		return planes;
	}

	/**
	 * Unions every plane - the set of records whose count is non-zero.
	 *
	 * @param planes the planes to union
	 * @return records present in at least one plane
	 */
	@Nonnull
	private static PersistentRoaringBitmap orAll(@Nonnull PersistentRoaringBitmap[] planes) {
		return PersistentRoaringBitmap.or(planes);
	}

	/**
	 * Counts operands that carry at least one record.
	 *
	 * @param family the operands to inspect
	 * @return how many are non-empty
	 */
	private static int countNonEmpty(@Nonnull Bitmap[] family) {
		int count = 0;
		for (final Bitmap operand : family) {
			if (!operand.isEmpty()) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Wraps a roaring result in the engine's bitmap type, collapsing an empty result to the shared singleton.
	 *
	 * @param result the computed roaring bitmap
	 * @return the engine-facing bitmap
	 */
	@Nonnull
	private static Bitmap toBitmap(@Nonnull PersistentRoaringBitmap result) {
		return result.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(result);
	}

}
