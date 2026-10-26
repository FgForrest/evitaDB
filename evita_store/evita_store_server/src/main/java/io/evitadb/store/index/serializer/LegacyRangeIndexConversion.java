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

package io.evitadb.store.index.serializer;

import io.evitadb.dataType.DateTimeRange;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.index.range.TransactionalRangePoint;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Converts range thresholds whose owning root's version identifies the released epoch-second format.
 * Inline companions belong to versioned readers; paged companions belong to the storage migration, because their
 * unversioned leaves cannot identify their own scale. Numeric range thresholds must never enter this transform.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LegacyRangeIndexConversion {

	/**
	 * Converts an inline companion only when its own record declares a DateTimeRange type, including arrays.
	 *
	 * @param declaredType the record's required persisted type
	 * @param index the decoded companion, or `null` when absent
	 * @return the millisecond companion, or the original numeric or absent companion
	 */
	@Nullable
	public static RangeIndex convertInline(@Nonnull Class<?> declaredType, @Nullable RangeIndex index) {
		return index != null && isDateTimeRange(declaredType) ? rescaledFromSecondGranularity(index) : index;
	}

	/**
	 * Identifies temporal range thresholds from the owning record's persisted type.
	 *
	 * @param declaredType the required persisted type, possibly an array
	 * @return whether the plain type is DateTimeRange
	 */
	public static boolean isDateTimeRange(@Nonnull Class<?> declaredType) {
		return DateTimeRange.class.equals(
			declaredType.isArray() ? declaredType.getComponentType() : declaredType
		);
	}

	/**
	 * Rebuilds a second-granularity index in milliseconds and unions points that collapse onto open sentinels.
	 * The caller establishes the legacy root version and temporal type before calling this method.
	 *
	 * @param index the decoded second-granularity index
	 * @return a fresh millisecond index with no persisted page identities
	 */
	@Nonnull
	public static RangeIndex rescaledFromSecondGranularity(@Nonnull RangeIndex index) {
		return new RangeIndex(rescaleSecondGranularityPoints(index.rangesIterator(), index.getRangePointCount()));
	}

	/**
	 * Rescales one threshold persisted while {@link DateTimeRange} still compared at **second** granularity into the
	 * millisecond scale it compares at now. Applicable **only** to a range index over `DateTimeRange`: the threshold
	 * is an untyped `long` shared with every `NumberRange` subtype, whose thresholds are the bounds' own numeric
	 * values and must never be touched.
	 *
	 * Three cases, and the separation between them is unambiguous by four orders of magnitude:
	 *
	 * - a threshold **below** {@link DateTimeRange#MIN_REPRESENTABLE_EPOCH_SECOND} is an open-from bound. The legacy
	 *   sentinel was `LocalDateTime.MIN.atOffset(other).toEpochSecond()` ≈ `-3.156e16`, and the index's own lower
	 *   border point is `Long.MIN_VALUE`; both land here and both map onto {@link DateTimeRange#OPEN_FROM_THRESHOLD},
	 *   which is what an open-from bound is worth today.
	 * - a threshold **above** {@link DateTimeRange#MAX_REPRESENTABLE_EPOCH_SECOND} is the symmetric open-to case
	 *   (legacy sentinel ≈ `+3.156e16`, upper border `Long.MAX_VALUE`) and maps onto
	 *   {@link DateTimeRange#OPEN_TO_THRESHOLD}.
	 * - anything else is a real moment — no date expressible as a scalar temporal attribute exceeds ~1e11 seconds,
	 *   while the window edge sits at ~9.22e15 — and is multiplied by 1000. The multiplication is exact: a value
	 *   written at second granularity has no millisecond component to recover, so every legacy range lands on a zero
	 *   millisecond remainder.
	 *
	 * The mapping is monotone but **not injective**: several legacy sentinels (one per zone offset they were paired
	 * with) collapse onto one open-bound constant, and onto the border point already sitting there. Callers must
	 * therefore merge colliding points rather than assume the thresholds stay distinct — see
	 * {@link #rescaleSecondGranularityPoints}.
	 *
	 * @param threshold the persisted second-granularity threshold
	 * @return the equivalent millisecond-granularity threshold
	 */
	public static long rescaleSecondGranularityThreshold(long threshold) {
		if (threshold < DateTimeRange.MIN_REPRESENTABLE_EPOCH_SECOND) {
			return DateTimeRange.OPEN_FROM_THRESHOLD;
		} else if (threshold > DateTimeRange.MAX_REPRESENTABLE_EPOCH_SECOND) {
			return DateTimeRange.OPEN_TO_THRESHOLD;
		} else {
			return threshold * 1000L;
		}
	}

	/**
	 * Walks an ascending stream of second-granularity points, rescales each threshold through
	 * {@link #rescaleSecondGranularityThreshold} and merges the runs that land on the same value by unioning their
	 * `starts` / `ends` bitmaps.
	 *
	 * The merge is not an edge case: every legacy open-from bound rescales onto the very `Long.MIN_VALUE` the index's
	 * lower border point already occupies (and symmetrically at the top), so any index holding a one-sided range
	 * collides. Merging also makes the converted index agree with the write path — after conversion the point
	 * carrying an open-ended range's records IS the border point, which is exactly where
	 * {@code FilterIndex.removeRecord} will look for it once the range is removed.
	 *
	 * @param points        the persisted points in ascending threshold order
	 * @param expectedCount the number of points the iterator will yield, used to pre-size the result
	 * @return the rescaled points, in ascending threshold order and with distinct thresholds
	 */
	@Nonnull
	private static TransactionalRangePoint[] rescaleSecondGranularityPoints(
		@Nonnull Iterator<TransactionalRangePoint> points, int expectedCount
	) {
		final List<TransactionalRangePoint> result = new ArrayList<>(expectedCount);
		// the point whose threshold the next one may still collide with; its merged bitmaps stay null until a
		// collision actually happens, so an index with nothing to merge allocates no bitmap it does not need
		TransactionalRangePoint carried = null;
		long carriedThreshold = 0L;
		BaseBitmap mergedStarts = null;
		BaseBitmap mergedEnds = null;
		while (points.hasNext()) {
			final TransactionalRangePoint point = points.next();
			final long threshold = rescaleSecondGranularityThreshold(point.getThreshold());
			if (carried == null) {
				carried = point;
				carriedThreshold = threshold;
			} else if (threshold == carriedThreshold) {
				if (mergedStarts == null) {
					mergedStarts = new BaseBitmap(carried.getStarts());
					mergedEnds = new BaseBitmap(carried.getEnds());
				}
				mergedStarts.addAll(point.getStarts());
				mergedEnds.addAll(point.getEnds());
			} else {
				result.add(materializeRescaledPoint(carried, carriedThreshold, mergedStarts, mergedEnds));
				carried = point;
				carriedThreshold = threshold;
				mergedStarts = null;
				mergedEnds = null;
			}
		}
		if (carried != null) {
			result.add(materializeRescaledPoint(carried, carriedThreshold, mergedStarts, mergedEnds));
		}
		return result.toArray(new TransactionalRangePoint[0]);
	}

	/**
	 * Materializes one rescaled point. A {@link TransactionalRangePoint}'s threshold is final, so a fresh instance is
	 * always minted; the record bitmaps are the merged ones when a collision produced them, otherwise the source
	 * point's own.
	 *
	 * @param source       the point the rescaled one is derived from
	 * @param threshold    the rescaled threshold
	 * @param mergedStarts the union of every colliding point's starts, or `null` when nothing collided
	 * @param mergedEnds   the union of every colliding point's ends, or `null` when nothing collided
	 * @return the rescaled point
	 */
	@Nonnull
	private static TransactionalRangePoint materializeRescaledPoint(
		@Nonnull TransactionalRangePoint source,
		long threshold,
		@Nullable BaseBitmap mergedStarts,
		@Nullable BaseBitmap mergedEnds
	) {
		return new TransactionalRangePoint(
			threshold,
			mergedStarts == null ? source.getStarts() : mergedStarts,
			mergedEnds == null ? source.getEnds() : mergedEnds
		);
	}

}
