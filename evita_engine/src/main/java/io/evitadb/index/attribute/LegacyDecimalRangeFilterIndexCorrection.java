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
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Rebuilds release-written decimal-range companions and corrects bucket pages whose normalized keys do not ascend.
 * Every bound and record comes from the stored bucket rows. The protocol 6 to 7 migration reads those pages,
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
	 * Rebuilds every decimal-range companion at its frozen scale, including inline and already ordered filters.
	 * Released keys can invert or coincide at that scale; coinciding buckets contribute the union of their records.
	 * The range companion is rebuilt from those same precise bounds: a released bucket can carry a different decimal
	 * scale from the frozen index scale, so retaining its old thresholds would still miss range queries. Reordered
	 * bucket pages retain their capacities and identities; range pages are emitted above the previous high-water.
	 * Surplus bucket pages and all previous range pages are removed from the new offset index.
	 * Merged buckets keep the value id of their first bucket and drop the others. Nothing outside the filter refers to
	 * a value id of a range: the trigram index, the only other reader of value ids, accepts string attributes alone.
	 * Every replacement is an append published with the migration's bootstrap record.
	 *
	 * @param root the persisted filter root
	 * @param pages its bucket leaf pages in manifest order, empty for an inline root
	 * @return one final root, replacement pages and offset-index removals
	 */
	@Nonnull
	public static List<StoragePart> rewrite(
		@Nonnull FilterIndexStoragePart root,
		@Nonnull FilterIndexLeafPagePart[] pages
	) {
		if (!root.isPaged()) {
			return LegacyRangeFilterIndexCorrection.rewrite(root, pages);
		}
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
			return LegacyRangeFilterIndexCorrection.rewrite(root, pages);
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
		final FilterIndexStoragePart orderedRoot = new FilterIndexStoragePart(
			root.getEntityIndexPrimaryKey(), root.getAttributeIndexKey(), root.getAttributeType(),
			root.getHistogramPoints(), root.getRangeIndex(), root.getIndexedDecimalPlaces(), true,
			root.getHighWaterPageSequence(), Arrays.copyOf(root.getLeafPageSequences(), usedPages),
			root.isRangePaged(), root.getRangeHighWaterPageSequence(), root.getRangeLeafPageSequences(),
			root.getNextValueId(), root.getInlineValueIds(), root.getStoragePartPK()
		);
		parts.addAll(LegacyRangeFilterIndexCorrection.replaceCompanion(orderedRoot, buckets.values()));
		return parts;
	}
}
