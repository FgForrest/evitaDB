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

package io.evitadb.core.query.extraResult.translator.histogram.producer;

import io.evitadb.api.exception.InvalidHistogramBucketCountException;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.core.query.extraResult.translator.histogram.cache.CacheableHistogramContract.CacheableBucket;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;

/**
 * Computes a range-bucketed histogram with overlap semantics from the raw range sweep emitted by
 * {@link io.evitadb.index.attribute.FilterIndex#getRangeHistogramOfAllRecords(Class, int)}.
 *
 * Unlike {@link HistogramDataCruncher}, which treats every source bucket as a single point and counts the records
 * sitting exactly at that threshold, this cruncher reconstructs each distinct record's value interval
 * `[fromValue, toValue]` and counts, per output bucket, how many DISTINCT records OVERLAP the bucket interval. A
 * single record spanning the whole histogram therefore renders a SOLID BAR (occurrences = 1 in every bucket its
 * range covers) instead of an inflated spike at each threshold it is active at.
 *
 * Input expectations (mirroring the sweep contract):
 *
 * - the source `ValueToRecordBitmap[]` is sorted ascending by threshold value,
 * - each bitmap is the rolling ACTIVE SET — every record whose `[from, to]` covers that threshold,
 * - thresholds are the source attribute's inner numeric values (`Byte`/`Short`/`Integer`/`Long`/`BigDecimal`).
 *
 * Output semantics:
 *
 * - the grid is at most `bucketCount` buckets over `[minValue, maxValue]` (the distinct value span); under
 *   {@link HistogramBehavior#STANDARD} the buckets are uniform-width, under {@link HistogramBehavior#OPTIMIZED} the
 *   grid is widened to collapse runs of empty (uncovered) buckets — the same adaptive heuristic
 *   {@link HistogramDataCruncher#createOptimalHistogram} applies to point histograms,
 * - per output bucket `[lo, hi)` (last bucket closed `[lo, max]`): occurrences = distinct records whose
 *   `[fromValue, toValue]` overlaps it (`from < hi AND to >= lo`; last bucket `from <= max AND to >= lo`),
 * - relativeFrequency = `(occurrences / maxBucketOccurrences) * 100`,
 * - {@link #getOverallCount()} = number of distinct records (union cardinality), NOT the bucket-occurrence sum.
 *
 * This cruncher serves only the equal-width behaviors ({@link HistogramBehavior#STANDARD},
 * {@link HistogramBehavior#OPTIMIZED}). The frequency-equalised behaviors ({@link HistogramBehavior#EQUALIZED},
 * {@link HistogramBehavior#EQUALIZED_OPTIMIZED}) are served by {@link EqualizedHistogramDataCruncher} fed with the
 * same sweep: there each record is accounted at every global stop its `[from, to]` covers (the sweep's rolling
 * active set), which yields the fixed total mass cumulative-frequency equalisation requires — a target that overlap
 * counting, whose bucket-occurrence sum varies with the grid, cannot provide.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class RangeHistogramDataCruncher {
	/**
	 * Constant for BigDecimal value of 100 used in relative-frequency computation.
	 */
	private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
	/**
	 * Computed output histogram buckets ordered by ascending threshold. Never empty.
	 */
	@Getter @Nonnull private final CacheableBucket[] histogram;
	/**
	 * Inclusive upper bound of the last output bucket, expressed at the requested decimal scale.
	 */
	@Getter @Nonnull private final BigDecimal maxValue;
	/**
	 * Number of distinct records observed across the whole sweep.
	 */
	@Getter private final int overallCount;

	/**
	 * @param sourceData    ascending-by-threshold range sweep (the rolling active set per threshold) from
	 *                      {@link io.evitadb.index.attribute.FilterIndex#getRangeHistogramOfAllRecords(Class, int)};
	 *                      must be non-empty
	 * @param bucketCount   requested maximum number of uniform output buckets; the effective count may be lower when
	 *                      the value span has fewer representable integer steps. Must be `> 1`
	 * @param decimalPlaces decimal scale used to project thresholds to int keys and back to {@link BigDecimal},
	 *                      mirroring {@link AttributeHistogramComputer}
	 * @throws InvalidHistogramBucketCountException when `bucketCount <= 1`
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when `sourceData` is empty
	 */
	public RangeHistogramDataCruncher(
		@Nonnull ValueToRecordBitmap[] sourceData,
		int bucketCount,
		int decimalPlaces
	) {
		this(sourceData, bucketCount, decimalPlaces, HistogramBehavior.STANDARD);
	}

	/**
	 * @param sourceData    ascending-by-threshold range sweep (the rolling active set per threshold) from
	 *                      {@link io.evitadb.index.attribute.FilterIndex#getRangeHistogramOfAllRecords(Class, int)};
	 *                      must be non-empty
	 * @param bucketCount   requested maximum number of output buckets; the effective count may be lower when the value
	 *                      span has fewer representable integer steps, or when {@code behavior} collapses empty runs.
	 *                      Must be `> 1`
	 * @param decimalPlaces decimal scale used to project thresholds to int keys and back to {@link BigDecimal},
	 *                      mirroring {@link AttributeHistogramComputer}
	 * @param behavior      must be {@link HistogramBehavior#STANDARD} (uniform grid) or
	 *                      {@link HistogramBehavior#OPTIMIZED} (uniform grid widened to drop empty coverage gaps); the
	 *                      frequency-equalised behaviors are not served here — see the class documentation
	 * @throws InvalidHistogramBucketCountException when `bucketCount <= 1`
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when `sourceData` is empty
	 */
	public RangeHistogramDataCruncher(
		@Nonnull ValueToRecordBitmap[] sourceData,
		int bucketCount,
		int decimalPlaces,
		@Nonnull HistogramBehavior behavior
	) {
		Assert.isTrue(
			bucketCount > 1,
			() -> new InvalidHistogramBucketCountException("range histogram", bucketCount)
		);
		Assert.isTrue(sourceData.length > 0, "Source data for range histogram must not be empty!");
		Assert.isTrue(
			behavior == HistogramBehavior.STANDARD || behavior == HistogramBehavior.OPTIMIZED,
			"RangeHistogramDataCruncher serves only STANDARD and OPTIMIZED behaviors; got " + behavior + "."
		);

		// 1) project each source threshold to an int key in the requested decimal scale; the keys are
		// monotonically ascending by the sweep contract
		final int sourceCount = sourceData.length;
		final int[] thresholdKeys = new int[sourceCount];
		for (int i = 0; i < sourceCount; i++) {
			thresholdKeys[i] = toIntKey(sourceData[i].getValue(), decimalPlaces);
		}

		// 2) reconstruct each distinct record's [fromKey, toKey] interval: ascending pass records the first
		// threshold a record appears at (fromKey); descending pass records the last (toKey). A union bitmap
		// collects all distinct records, whose cardinality is the overallCount.
		final BaseBitmap distinctRecords = new BaseBitmap();
		for (final ValueToRecordBitmap sourceDatum : sourceData) {
			distinctRecords.addAll(sourceDatum.getRecordIds());
		}
		final int distinctCount = distinctRecords.size();
		this.overallCount = distinctCount;

		final int[] recordIds = distinctRecords.getArray();
		final int[] fromKey = new int[distinctCount];
		final int[] toKey = new int[distinctCount];
		Arrays.fill(fromKey, Integer.MAX_VALUE);
		Arrays.fill(toKey, Integer.MIN_VALUE);
		// map record id -> dense index via binary search on the ascending recordIds array
		for (int i = 0; i < sourceCount; i++) {
			final int key = thresholdKeys[i];
			final Bitmap active = sourceData[i].getRecordIds();
			final int[] activeArray = active.getArray();
			for (final int k : activeArray) {
				final int idx = Arrays.binarySearch(recordIds, k);
				if (idx < 0) {
					throw new GenericEvitaInternalError(
						"Active record " + k + " missing from distinct record set — " +
							"range sweep contract violated."
					);
				}
				if (key < fromKey[idx]) {
					fromKey[idx] = key;
				}
				if (key > toKey[idx]) {
					toKey[idx] = key;
				}
			}
		}

		// 3) build the uniform output grid over [minKey, maxKey]
		final int minKey = thresholdKeys[0];
		final int maxKey = thresholdKeys[sourceCount - 1];
		this.maxValue = toBigDecimal(maxKey, decimalPlaces).setScale(decimalPlaces, RoundingMode.UNNECESSARY);

		if (minKey == maxKey) {
			// degenerate span — emit a single bucket covering the only value
			final BigDecimal relativeFrequency = distinctCount > 0 ? ONE_HUNDRED : BigDecimal.ZERO;
			this.histogram = new CacheableBucket[] {
				new CacheableBucket(
					toBigDecimal(minKey, decimalPlaces).setScale(decimalPlaces, RoundingMode.HALF_UP),
					distinctCount,
					relativeFrequency.setScale(2, RoundingMode.HALF_UP)
				)
			};
			return;
		}

		// 4) build the first-shot uniform grid; STANDARD uses it directly, OPTIMIZED may rebuild it once with a
		// wider step to collapse the longest run of empty (uncovered) buckets
		OverlapGrid grid = buildGrid(bucketCount, minKey, maxKey, fromKey, toKey, distinctCount, decimalPlaces);
		if (behavior == HistogramBehavior.OPTIMIZED) {
			final int optimizedBucketCount = chooseOptimizedBucketCount(grid, minKey, maxKey);
			if (optimizedBucketCount != grid.occurrences().length) {
				grid = buildGrid(optimizedBucketCount, minKey, maxKey, fromKey, toKey, distinctCount, decimalPlaces);
			}
		}
		this.histogram = grid.buckets();
	}

	/**
	 * Builds a uniform grid of at most {@code targetBucketCount} buckets over `[minKey, maxKey]` and counts, per
	 * bucket, the number of distinct records whose `[fromKey, toKey]` interval overlaps it.
	 *
	 * The occupancy is computed with a difference array: because the buckets partition the value span contiguously,
	 * each record overlaps exactly the contiguous run of buckets from the one containing its `fromKey` to the one
	 * containing its `toKey`, so a single +1/-1 delta pair per record plus one prefix-sum pass yields every bucket's
	 * occupancy — O(records × log buckets + buckets) rather than the O(buckets × records) pairwise overlap test.
	 */
	@Nonnull
	private static OverlapGrid buildGrid(
		int targetBucketCount,
		int minKey,
		int maxKey,
		@Nonnull int[] fromKey,
		@Nonnull int[] toKey,
		int distinctCount,
		int decimalPlaces
	) {
		// cap the bucket count so each grid threshold is a distinct int key at the requested scale — when more
		// buckets are requested than there are representable integer steps, collapsing to the value span avoids
		// zero-width / duplicate-threshold buckets. The span is computed in long because the full int range
		// (Integer.MAX_VALUE - Integer.MIN_VALUE) overflows a signed int; the degenerate minKey == maxKey case
		// is handled before this method, so span >= 1 always holds here.
		final long span = (long) maxKey - (long) minKey;
		final int effectiveBucketCount = (int) Math.min(targetBucketCount, span);
		// integer lower-bound key per output bucket; the upper bound of bucket i is the lower bound of bucket i+1,
		// and the last bucket's inclusive upper bound is maxKey
		final int[] bucketLowerKey = new int[effectiveBucketCount];
		for (int i = 0; i < effectiveBucketCount; i++) {
			// evenly space lower bounds: floor of the proportional offset into the value span; the
			// strict-increase fixup below resolves any duplicate keys produced by this flooring. The offset
			// stays in long and is added to minKey before the int cast — truncating (int) offset first would
			// corrupt the key for large spans, whereas minKey + offset always lies within [minKey, maxKey].
			final long offset = span * i / effectiveBucketCount;
			bucketLowerKey[i] = (int) (minKey + offset);
		}
		// guarantee strictly increasing lower bounds even under rounding collisions
		for (int i = 1; i < effectiveBucketCount; i++) {
			if (bucketLowerKey[i] <= bucketLowerKey[i - 1]) {
				bucketLowerKey[i] = bucketLowerKey[i - 1] + 1;
			}
		}

		final int[] delta = new int[effectiveBucketCount + 1];
		for (int r = 0; r < distinctCount; r++) {
			final int bStart = bucketOf(bucketLowerKey, fromKey[r]);
			final int bEnd = bucketOf(bucketLowerKey, toKey[r]);
			delta[bStart]++;
			delta[bEnd + 1]--;
		}
		final int[] occurrences = new int[effectiveBucketCount];
		int maxOccurrences = 0;
		int running = 0;
		for (int b = 0; b < effectiveBucketCount; b++) {
			running += delta[b];
			occurrences[b] = running;
			if (running > maxOccurrences) {
				maxOccurrences = running;
			}
		}

		// materialize output buckets with relativeFrequency normalized to the busiest bucket
		final CacheableBucket[] buckets = new CacheableBucket[effectiveBucketCount];
		for (int b = 0; b < effectiveBucketCount; b++) {
			final BigDecimal relativeFrequency = maxOccurrences > 0
				? BigDecimal.valueOf(occurrences[b])
					.multiply(ONE_HUNDRED)
					.divide(BigDecimal.valueOf(maxOccurrences), 2, RoundingMode.HALF_UP)
				: BigDecimal.ZERO;
			buckets[b] = new CacheableBucket(
				toBigDecimal(bucketLowerKey[b], decimalPlaces).setScale(decimalPlaces, RoundingMode.HALF_UP),
				occurrences[b],
				relativeFrequency
			);
		}
		return new OverlapGrid(buckets, bucketLowerKey, occurrences);
	}

	/**
	 * Picks a (possibly smaller) bucket count that collapses the longest run of consecutive empty (uncovered)
	 * buckets, mirroring {@link HistogramDataCruncher#createOptimalHistogram} in integer key space. When the largest
	 * empty run would leave at most two non-empty buckets the grid drops to two buckets; when it spans at least two
	 * buckets the step is widened by half the empty span and a new (smaller) count derived; otherwise the current
	 * grid is kept. The result is clamped to `[2, current bucket count]`, so it never grows the grid.
	 */
	private static int chooseOptimizedBucketCount(@Nonnull OverlapGrid grid, int minKey, int maxKey) {
		final int[] occurrences = grid.occurrences();
		final int[] lowerKeys = grid.lowerKeys();
		final int bucketCount = occurrences.length;
		int longestRun = 0;
		int spanStartKey = lowerKeys[0];
		int spanEndKey = lowerKeys[0];
		int currentRun = 0;
		int previousNonEmptyKey = lowerKeys[0];
		for (int b = 0; b < bucketCount; b++) {
			if (occurrences[b] == 0) {
				currentRun++;
			} else {
				if (currentRun > longestRun) {
					// the gap is bracketed by the last non-empty bucket and this one; trailing gaps are ignored,
					// matching the point-histogram heuristic which only records a gap once a later bar closes it
					longestRun = currentRun;
					spanStartKey = previousNonEmptyKey;
					spanEndKey = lowerKeys[b];
				}
				currentRun = 0;
				previousNonEmptyKey = lowerKeys[b];
			}
		}

		final int emptyColumns = longestRun;
		if (bucketCount - emptyColumns <= 2) {
			return 2;
		} else if (emptyColumns >= 2) {
			// widen each operand to double before subtracting so the full int range does not overflow first —
			// this applies to both the overall span and the empty-gap span bracketed by spanStartKey/spanEndKey
			final double span = (double) maxKey - (double) minKey;
			final double optimalStep = span / bucketCount;
			final double recomputedStep = optimalStep + ((double) spanEndKey - (double) spanStartKey) / 2.0;
			final int newBucketCount = (int) Math.floor(span / recomputedStep) + 2;
			return Math.max(2, Math.min(newBucketCount, bucketCount));
		} else {
			return bucketCount;
		}
	}

	/**
	 * Projects a source threshold value to an int key at the requested decimal scale. Mirrors the conversion in
	 * {@link AttributeHistogramComputer} so the same threshold values map identically.
	 */
	private static int toIntKey(@Nonnull Serializable value, int decimalPlaces) {
		if (value instanceof Byte b) {
			return b;
		} else if (value instanceof Short s) {
			return s;
		} else if (value instanceof Integer i) {
			return i;
		} else if (value instanceof Long l) {
			final int converted = l.intValue();
			if (l != (long) converted) {
				throw new ArithmeticException("int overflow: " + value);
			}
			return converted;
		} else if (value instanceof BigDecimal bd) {
			// mirror the Long branch's overflow guard: surface a clear error instead of the silent
			// int wrap plain intValue() would produce for an out-of-range scaled value
			final long scaled = bd.stripTrailingZeros().scaleByPowerOfTen(decimalPlaces).longValueExact();
			final int converted = (int) scaled;
			if (scaled != (long) converted) {
				throw new ArithmeticException("int overflow: " + value);
			}
			return converted;
		} else {
			throw new GenericEvitaInternalError(
				"Unsupported range histogram threshold type: " + value.getClass().getName() +
					", supported are Byte, Short, Integer, Long, BigDecimal."
			);
		}
	}

	/**
	 * Converts an int key back to a {@link BigDecimal} at the requested decimal scale.
	 */
	@Nonnull
	private static BigDecimal toBigDecimal(int key, int decimalPlaces) {
		return decimalPlaces == 0
			? new BigDecimal(key)
			: new BigDecimal(key).scaleByPowerOfTen(-1 * decimalPlaces);
	}

	/**
	 * Returns the index of the output bucket containing `value` — the rightmost bucket whose lower bound is
	 * `<= value`. `bucketLowerKey` is strictly ascending and `value` is always within
	 * `[bucketLowerKey[0], maxKey]`, so the result is never negative.
	 */
	private static int bucketOf(@Nonnull int[] bucketLowerKey, int value) {
		final int idx = Arrays.binarySearch(bucketLowerKey, value);
		// exact match → that bucket; miss → insertion point minus one is the floor (the containing bucket)
		return idx >= 0 ? idx : -idx - 2;
	}

	/**
	 * Immutable holder for one computed grid: the output {@code buckets}, their ascending integer lower-bound keys
	 * ({@code lowerKeys}), and the per-bucket overlap {@code occurrences}. The latter two let the OPTIMIZED pass
	 * locate empty runs without recomputing them.
	 */
	private record OverlapGrid(
		@Nonnull CacheableBucket[] buckets,
		@Nonnull int[] lowerKeys,
		@Nonnull int[] occurrences
	) {
	}
}
