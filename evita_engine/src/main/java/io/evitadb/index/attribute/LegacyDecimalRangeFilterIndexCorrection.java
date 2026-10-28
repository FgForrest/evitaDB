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


package io.evitadb.index.attribute;

import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.Range;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.page.PageEmission;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.RangeIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.RangeIndexLeafPageRemoval;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Rewrites release-written decimal-range filter pages whose keys invert or coincide at the frozen index scale.
 * Every bound and record comes from the stored bucket pages. The protocol 6 to 7 migration reads those pages,
 * applies these replacements and removals, and publishes them with its bootstrap record; this class performs no IO.
 *
 * @deprecated used only by the migration of catalogs written by release 2026.2 and older
 */
@Deprecated(since = "2026.3", forRemoval = true)
public final class LegacyDecimalRangeFilterIndexCorrection {

	/** Prevents instantiation of the static correction. */
	private LegacyDecimalRangeFilterIndexCorrection() {
	}

	/**
	 * Reorders a non-unique decimal-range filter's stored pages after normalizing their keys at the frozen scale.
	 * Released keys can invert or coincide at that scale; coinciding buckets contribute the union of their records.
	 * The range companion is rebuilt from those same precise bounds: a released bucket can carry a different decimal
	 * scale from the frozen index scale, so retaining its old thresholds would still miss range queries. Bucket pages
	 * retain their capacities and identities; range pages are emitted above the previous high-water. Surplus bucket
	 * pages and all previous range pages are removed from the new offset index.
	 * Merged buckets keep the value id of their first bucket and drop the others. Nothing outside the filter refers to
	 * a value id of a range: the trigram index, the only other reader of value ids, accepts string attributes alone.
	 * Every replacement is an append published with the migration's bootstrap record.
	 *
	 * @param root the persisted paged filter root
	 * @param pages its leaf pages in manifest order
	 * @return replacement parts and removals, or an empty list when the normalized keys already ascend strictly
	 */
	@Nonnull
	public static List<StoragePart> rewrite(
		@Nonnull FilterIndexStoragePart root,
		@Nonnull FilterIndexLeafPagePart[] pages
	) {
		final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(
			BigDecimalNumberRange.class, root.getIndexedDecimalPlaces()
		);
		final TreeMap<BigDecimalNumberRange, ValueToRecordBitmap> buckets = new TreeMap<>();
		final Map<BigDecimalNumberRange, Integer> valueIds = new HashMap<>();
		BigDecimalNumberRange previous = null;
		boolean changed = false;
		for (final FilterIndexLeafPagePart page : pages) {
			for (int i = 0; i < page.getBuckets().length; i++) {
				final ValueToRecord bucket = page.getBuckets()[i];
				final BigDecimalNumberRange key = (BigDecimalNumberRange) normalizer.apply(bucket.getValue());
				changed |= previous != null && previous.compareTo(key) >= 0;
				previous = key;
				buckets.computeIfAbsent(key, ValueToRecordBitmap::new).addRecord(bucket.getRecordIds().getArray());
				if (page.getValueIds() != null) {
					valueIds.putIfAbsent(key, page.getValueIds()[i]);
				}
			}
		}
		if (!changed) {
			return List.of();
		}
		final List<StoragePart> parts = new ArrayList<>(pages.length + 1);
		final Iterator<Entry<BigDecimalNumberRange, ValueToRecordBitmap>> ordered = buckets.entrySet().iterator();
		final AttributeKeyWithIndexType streamKey =
			new AttributeKeyWithIndexType(root.getAttributeIndexKey(), AttributeIndexType.FILTER);
		int remaining = buckets.size();
		int usedPages = 0;
		for (final FilterIndexLeafPagePart page : pages) {
			if (remaining == 0) {
				parts.add(new FilterIndexLeafPageRemoval(
					root.getEntityIndexPrimaryKey(), streamKey, page.getPageSequence()
				));
				continue;
			}
			final int count = Math.min(page.getBuckets().length, remaining);
			final ValueToRecord[] rewritten = new ValueToRecord[count];
			final int[] rewrittenIds = page.getValueIds() == null ? null : new int[count];
			for (int i = 0; i < count; i++) {
				final Entry<BigDecimalNumberRange, ValueToRecordBitmap> entry = ordered.next();
				rewritten[i] = entry.getValue();
				if (rewrittenIds != null) {
					rewrittenIds[i] = valueIds.get(entry.getKey());
				}
			}
			parts.add(new FilterIndexLeafPagePart(
				root.getEntityIndexPrimaryKey(), streamKey, page.getPageSequence(), rewritten, rewrittenIds
			));
			remaining -= count;
			usedPages++;
		}
		final RangeIndex rangeIndex = rebuildDecimalRangeCompanion(buckets.values());
		final boolean rangePaged = rangeIndex.isPaged();
		final int rangeHighWater;
		final int[] rangePageSequences;
		if (rangePaged) {
			final int pageOffset = root.isRangePaged() ?
				Math.addExact(root.getRangeHighWaterPageSequence(), 1) : 0;
			final PageEmission<RangeIndex.RangePage> emission = rangeIndex.collectChangedPages();
			rangeHighWater = Math.addExact(pageOffset, emission.highWaterPageSequence());
			rangePageSequences = Arrays.stream(emission.orderedPageSequences())
				.map(sequence -> Math.addExact(pageOffset, sequence)).toArray();
			for (final RangeIndex.RangePage page : emission.changedPages()) {
				parts.add(new RangeIndexLeafPagePart(
					root.getEntityIndexPrimaryKey(), streamKey,
					Math.addExact(pageOffset, page.pageSequence()), page.points()
				));
			}
		} else {
			rangeHighWater = -1;
			rangePageSequences = new int[0];
		}
		for (final int sequence : root.getRangeLeafPageSequences()) {
			parts.add(new RangeIndexLeafPageRemoval(root.getEntityIndexPrimaryKey(), streamKey, sequence));
		}
		parts.add(new FilterIndexStoragePart(
			root.getEntityIndexPrimaryKey(), root.getAttributeIndexKey(), root.getAttributeType(),
			root.getHistogramPoints(), rangePaged ? null : rangeIndex, root.getIndexedDecimalPlaces(), true,
			root.getHighWaterPageSequence(), Arrays.copyOf(root.getLeafPageSequences(), usedPages),
			rangePaged, rangeHighWater, rangePageSequences,
			root.getNextValueId(), root.getInlineValueIds(), root.getStoragePartPK()
		));
		return parts;
	}

	/**
	 * Rebuilds range thresholds from the normalized buckets, consolidating each record's intervals as the live filter
	 * does for an array attribute. Every input bound and owner comes from the stored bucket pages.
	 *
	 * @param buckets normalized buckets in current key order
	 * @return the companion at the filter's frozen decimal scale
	 */
	@Nonnull
	private static RangeIndex rebuildDecimalRangeCompanion(@Nonnull Collection<ValueToRecordBitmap> buckets) {
		final Map<Integer, List<BigDecimalNumberRange>> rangesByRecord = new HashMap<>();
		for (final ValueToRecordBitmap bucket : buckets) {
			for (final int record : bucket.getRecordIds().getArray()) {
				rangesByRecord.computeIfAbsent(record, ignored -> new ArrayList<>())
					.add((BigDecimalNumberRange) bucket.getValue());
			}
		}
		final RangeIndex rangeIndex = new RangeIndex();
		for (final Entry<Integer, List<BigDecimalNumberRange>> entry : rangesByRecord.entrySet()) {
			for (final BigDecimalNumberRange range : Range.consolidateRange(
				entry.getValue().toArray(BigDecimalNumberRange[]::new)
			)) {
				rangeIndex.addRecord(range.getFrom(), range.getTo(), entry.getKey());
			}
		}
		return rangeIndex;
	}

}
