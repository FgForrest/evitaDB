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

package io.evitadb.store.catalog;

import io.evitadb.dataType.DateTimeRange;
import io.evitadb.index.attribute.LegacyOwnerSortIndexCorrection;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Protocol 6 → 7 correction of stored filter and histogram bucket keys, used only by {@link Migration_2026_3}.
 * The supported-window clamp precedes the type-level canonicalizer; declared normalization chains never run here.
 * Collisions retain the first bucket's value id and union every record. A paged tree is corrected only when its
 * stored keys ascend strictly under the current comparator and their canonical keys keep that order, or when the
 * whole sequence ascends strictly under the released second-granularity range comparison. Anything else - an
 * inversion, or a stored key repeated across a page boundary - is a stale twin and must reach the loader's
 * corruption diagnostics unchanged: two distinct stored values may meet under one canonical key, one stored value
 * twice may not.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class LegacyInvertedIndexCorrection {
	/** Prevents instantiation of the migration helper. */
	private LegacyInvertedIndexCorrection() {
	}

	/**
	 * Canonicalizes a whole bucket axis without mutating its persisted arrays.
	 *
	 * @param plainType the non-array declared type selecting the type-level function
	 * @param indexedDecimalPlaces the persisted decimal scale
	 * @param pages the stored buckets in root page order, a single array for inline trees
	 * @param valueIds the aligned stored id columns, or `null` for a tree without ids
	 * @param comparator the comparator the production loader builds
	 * @param paged whether the tree is paged; a paged tree out of strict order is corrected only in a released order
	 * @return corrected pages and ids, or `null` when unchanged or when a paged order no release wrote stays as is
	 */
	@Nullable
	@SuppressWarnings({"rawtypes", "unchecked"})
	static CorrectedBuckets correct(
		@Nonnull Class<?> plainType,
		int indexedDecimalPlaces,
		@Nonnull ValueToRecord[][] pages,
		@Nullable int[][] valueIds,
		@Nonnull Comparator comparator,
		boolean paged
	) {
		final Function<Object, Serializable> normalizer =
			LegacyOwnerSortIndexCorrection.createClampingNormalizer(plainType, indexedDecimalPlaces);
		Assert.isPremiseValid(
			valueIds == null || valueIds.length == pages.length,
			"The persisted value id columns must align with the bucket pages."
		);
		int bucketCount = 0;
		for (final ValueToRecord[] page : pages) {
			bucketCount += page.length;
		}
		final List<PersistedBucket> flattened = new ArrayList<>(bucketCount);
		boolean changed = false;
		boolean inverted = false;
		boolean storedOrderBroken = false;
		Serializable previous = null;
		Serializable previousStored = null;
		for (int i = 0; i < pages.length; i++) {
			Assert.isPremiseValid(
				valueIds == null || valueIds[i] != null && valueIds[i].length == pages[i].length,
				"Each persisted page must carry exactly one value id per bucket."
			);
			for (int j = 0; j < pages[i].length; j++) {
				final ValueToRecord bucket = pages[i][j];
				final Serializable key = normalizer.apply(bucket.getValue());
				changed |= !Objects.equals(key, bucket.getValue());
				if (previous != null) {
					final int comparison = comparator.compare(previous, key);
					changed |= comparison == 0;
					inverted |= comparison > 0;
					// a release kept distinct stored keys strictly ascending; a repeated one is a stale twin even
					// when canonicalization reports no inversion
					storedOrderBroken |= paged && comparator.compare(previousStored, bucket.getValue()) >= 0;
				}
				flattened.add(new PersistedBucket(key, bucket, valueIds == null ? 0 : valueIds[i][j]));
				previous = key;
				previousStored = bucket.getValue();
			}
		}
		if ((inverted || storedOrderBroken) && paged && !isSecondGranularityRangeOrder(flattened)) {
			return null;
		}
		if (!changed && !inverted) {
			return null;
		}
		if (inverted) {
			// Stable sorting keeps the first persisted id when two keys meet after the reorder.
			flattened.sort((left, right) -> comparator.compare(left.key(), right.key()));
		}
		final List<PersistedBucket> retained = new ArrayList<>(flattened.size());
		for (final PersistedBucket bucket : flattened) {
			final int last = retained.size() - 1;
			if (last >= 0 && comparator.compare(retained.get(last).key(), bucket.key()) == 0) {
				final PersistedBucket target = retained.get(last);
				final BaseBitmap records = new BaseBitmap(target.bucket().getRecordIds());
				records.addAll(bucket.bucket().getRecordIds());
				// The merge target is the retained list itself. Page arrays are materialized only after every merge,
				// so a trimmed page can never leave a cross-page merge writing through an abandoned pre-copy array.
				retained.set(last, new PersistedBucket(
					target.key(), new ValueToRecordBitmap(target.key(), records), target.valueId()
				));
			} else {
				retained.add(new PersistedBucket(
					bucket.key(), new ValueToRecordBitmap(bucket.key(), bucket.bucket().getRecordIds()),
					bucket.valueId()
				));
			}
		}
		final ValueToRecord[][] corrected = new ValueToRecord[pages.length][];
		final int[][] correctedIds = valueIds == null ? null : new int[pages.length][];
		int cursor = 0;
		int count = 0;
		for (final ValueToRecord[] page : pages) {
			if (cursor == retained.size()) {
				break;
			}
			final int size = Math.min(page.length, retained.size() - cursor);
			final ValueToRecord[] buckets = new ValueToRecord[size];
			final int[] ids = correctedIds == null ? null : new int[size];
			for (int j = 0; j < size; j++) {
				final PersistedBucket bucket = retained.get(cursor++);
				buckets[j] = bucket.bucket();
				if (ids != null) {
					ids[j] = bucket.valueId();
				}
			}
			corrected[count] = buckets;
			if (correctedIds != null) {
				correctedIds[count] = ids;
			}
			count++;
		}
		return new CorrectedBuckets(
			Arrays.copyOf(corrected, count), correctedIds == null ? null : Arrays.copyOf(correctedIds, count)
		);
	}

	/**
	 * Recognizes exactly the range ordering a second-granularity release could persist.
	 *
	 * @param buckets canonical keys in persisted page order
	 * @return whether every key is a range and all ascend strictly under the released comparator
	 */
	private static boolean isSecondGranularityRangeOrder(@Nonnull List<PersistedBucket> buckets) {
		DateTimeRange previous = null;
		for (final PersistedBucket bucket : buckets) {
			if (!(bucket.key() instanceof DateTimeRange range)) {
				return false;
			}
			if (previous != null && LegacyOwnerSortIndexCorrection.compareAtSecondGranularity(previous, range) >= 0) {
				return false;
			}
			previous = range;
		}
		return true;
	}

	/**
	 * Corrected bucket columns, materialized after every cross-page merge has completed.
	 *
	 * @param pages canonical buckets chunked no larger than the persisted pages
	 * @param valueIds surviving ids aligned with the corrected pages, or `null` when absent
	 */
	record CorrectedBuckets(@Nonnull ValueToRecord[][] pages, @Nullable int[][] valueIds) {
	}

	/**
	 * One bucket and the identity that follows it through sorting and merging.
	 *
	 * @param key the canonical key
	 * @param bucket the records retained under that key
	 * @param valueId the original id, ignored for trees without ids
	 */
	private record PersistedBucket(@Nonnull Serializable key, @Nonnull ValueToRecord bucket, int valueId) {
	}
}
