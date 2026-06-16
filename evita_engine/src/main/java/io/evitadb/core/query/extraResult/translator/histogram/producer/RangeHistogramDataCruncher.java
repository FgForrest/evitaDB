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
 * - the grid is at most `bucketCount` uniform buckets over `[minValue, maxValue]` (the distinct value span),
 * - per output bucket `[lo, hi)` (last bucket closed `[lo, max]`): occurrences = distinct records whose
 *   `[fromValue, toValue]` overlaps it (`from < hi AND to >= lo`; last bucket `from <= max AND to >= lo`),
 * - relativeFrequency = `(occurrences / maxBucketOccurrences) * 100`,
 * - {@link #getOverallCount()} = number of distinct records (union cardinality), NOT the bucket-occurrence sum.
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
	@Getter private final CacheableBucket[] histogram;
	/**
	 * Inclusive upper bound of the last output bucket, expressed at the requested decimal scale.
	 */
	@Getter private final BigDecimal maxValue;
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
		Assert.isTrue(
			bucketCount > 1,
			() -> new InvalidHistogramBucketCountException("range histogram", bucketCount)
		);
		Assert.isTrue(sourceData.length > 0, "Source data for range histogram must not be empty!");

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
				final int idx = indexOf(recordIds, k);
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

		// cap the bucket count so each grid threshold is a distinct int key at the requested scale — when the
		// caller requests more buckets than there are representable integer steps, collapsing to the value span
		// avoids zero-width / duplicate-threshold buckets
		final int span = maxKey - minKey;
		final int effectiveBucketCount = Math.min(bucketCount, span);
		// integer lower-bound key per output bucket; the upper bound of bucket i is the lower bound of bucket i+1,
		// and the last bucket's inclusive upper bound is maxKey
		final int[] bucketLowerKey = new int[effectiveBucketCount];
		for (int i = 0; i < effectiveBucketCount; i++) {
			// evenly space lower bounds: floor of the proportional offset into the value span; the
			// strict-increase fixup below resolves any duplicate keys produced by this flooring
			final long offset = (long) span * i / effectiveBucketCount;
			bucketLowerKey[i] = minKey + (int) offset;
		}
		// guarantee strictly increasing lower bounds even under rounding collisions
		for (int i = 1; i < effectiveBucketCount; i++) {
			if (bucketLowerKey[i] <= bucketLowerKey[i - 1]) {
				bucketLowerKey[i] = bucketLowerKey[i - 1] + 1;
			}
		}

		// 4) compute overlap occurrences per output bucket
		final int[] occurrences = new int[effectiveBucketCount];
		int maxOccurrences = 0;
		for (int b = 0; b < effectiveBucketCount; b++) {
			final boolean last = b == effectiveBucketCount - 1;
			final int lo = bucketLowerKey[b];
			final int hi = last ? maxKey : bucketLowerKey[b + 1];
			int count = 0;
			for (int r = 0; r < distinctCount; r++) {
				final boolean overlaps = last
					// last bucket is closed [lo, maxKey]: from <= maxKey AND to >= lo
					? fromKey[r] <= maxKey && toKey[r] >= lo
					// half-open [lo, hi): from < hi AND to >= lo
					: fromKey[r] < hi && toKey[r] >= lo;
				if (overlaps) {
					count++;
				}
			}
			occurrences[b] = count;
			if (count > maxOccurrences) {
				maxOccurrences = count;
			}
		}

		// 5) materialize output buckets with relativeFrequency normalized to the busiest bucket
		final CacheableBucket[] result = new CacheableBucket[effectiveBucketCount];
		for (int b = 0; b < effectiveBucketCount; b++) {
			final BigDecimal relativeFrequency = maxOccurrences > 0
				? BigDecimal.valueOf(occurrences[b])
					.multiply(ONE_HUNDRED)
					.divide(BigDecimal.valueOf(maxOccurrences), 2, RoundingMode.HALF_UP)
				: BigDecimal.ZERO;
			result[b] = new CacheableBucket(
				toBigDecimal(bucketLowerKey[b], decimalPlaces).setScale(decimalPlaces, RoundingMode.HALF_UP),
				occurrences[b],
				relativeFrequency
			);
		}
		this.histogram = result;
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
			return bd.stripTrailingZeros().scaleByPowerOfTen(decimalPlaces).intValue();
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
	 * Binary search for `value` in the ascending `sortedArray`; returns the index or `-1` when absent.
	 */
	private static int indexOf(@Nonnull int[] sortedArray, int value) {
		int low = 0;
		int high = sortedArray.length - 1;
		while (low <= high) {
			final int mid = (low + high) >>> 1;
			final int midValue = sortedArray[mid];
			if (midValue < value) {
				low = mid + 1;
			} else if (midValue > value) {
				high = mid - 1;
			} else {
				return mid;
			}
		}
		return -1;
	}
}
