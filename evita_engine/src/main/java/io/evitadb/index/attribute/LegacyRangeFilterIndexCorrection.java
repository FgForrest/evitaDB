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
import io.evitadb.index.page.PageEmission;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.RangeIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.RangeIndexLeafPageRemoval;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Reconstructs release-written range companions from surviving value-to-record rows without performing IO.
 * Each stored record contributes its consolidated union exactly once. The storage migration applies the returned
 * replacements and offset-index removals and publishes them through its existing bootstrap callback.
 *
 * @deprecated used only by the migration of catalogs written by release 2026.2 and older
 */
@Deprecated(since = "2026.3", forRemoval = true)
public final class LegacyRangeFilterIndexCorrection {
	/** Prevents instantiation of the migration-only transform. */
	private LegacyRangeFilterIndexCorrection() {
	}

	/**
	 * Rebuilds only the range axis, preserving the root's entire bucket axis and every stored record identity.
	 * Inline roots supply their own rows; paged roots supply exactly the pages listed by their manifest.
	 *
	 * @param root the persisted filter root
	 * @param pages its bucket pages in manifest order, empty for inline buckets
	 * @return range-page replacements, old range-page removals and one final replacement root
	 */
	@Nonnull
	public static List<StoragePart> rewrite(
		@Nonnull FilterIndexStoragePart root, @Nonnull FilterIndexLeafPagePart[] pages
	) {
		final List<ValueToRecord> buckets = new ArrayList<>();
		if (root.isPaged()) {
			for (final FilterIndexLeafPagePart page : pages) {
				buckets.addAll(Arrays.asList(page.getBuckets()));
			}
		} else {
			buckets.addAll(Arrays.asList(root.getHistogramPoints()));
		}
		return replaceCompanion(root, buckets);
	}

	/**
	 * Emits a new companion for the final bucket axis selected by the migration's decimal ordering correction.
	 * Decimal values are normalized at the persisted scale before consolidation; temporal bounds come directly
	 * from decoded values in the current millisecond key space. Old threshold payloads are never consulted.
	 * Fresh range pages use checked sequence allocation above the persisted high-water. Retirements address only
	 * records in the new offset-index view; publication and data-file lifetime belong to the storage migration.
	 *
	 * @param root the final bucket root, still carrying the old companion's retirement metadata
	 * @param buckets the surviving value-to-record rows
	 * @return range-page replacements, old range-page removals and one final replacement root
	 */
	@Nonnull
	@SuppressWarnings({"rawtypes", "unchecked"})
	static List<StoragePart> replaceCompanion(
		@Nonnull FilterIndexStoragePart root, @Nonnull Iterable<? extends ValueToRecord> buckets
	) {
		final Map<Integer, List<Range>> rangesByRecord = new HashMap<>();
		for (final ValueToRecord bucket : buckets) {
			final Range range = bucket.getValue() instanceof BigDecimalNumberRange decimal ?
				(Range) FilterIndex.getNormalizer(BigDecimalNumberRange.class, root.getIndexedDecimalPlaces())
					.apply(decimal) : (Range) bucket.getValue();
			for (final int record : bucket.getRecordIds().getArray()) {
				rangesByRecord.computeIfAbsent(record, ignored -> new ArrayList<>()).add(range);
			}
		}
		final RangeIndex companion = new RangeIndex();
		for (final Entry<Integer, List<Range>> entry : rangesByRecord.entrySet()) {
			for (final Range range : Range.consolidateRange(entry.getValue().toArray(Range[]::new))) {
				companion.addRecord(range.getFrom(), range.getTo(), entry.getKey());
			}
		}
		final List<StoragePart> parts = new ArrayList<>();
		final AttributeKeyWithIndexType streamKey =
			new AttributeKeyWithIndexType(root.getAttributeIndexKey(), AttributeIndexType.FILTER);
		final boolean paged = companion.isPaged();
		final int highWater;
		final int[] sequences;
		if (paged) {
			final int offset = root.isRangePaged() ? Math.addExact(root.getRangeHighWaterPageSequence(), 1) : 0;
			final PageEmission<RangeIndex.RangePage> emission = companion.collectChangedPages();
			highWater = Math.addExact(offset, emission.highWaterPageSequence());
			sequences = Arrays.stream(emission.orderedPageSequences())
				.map(sequence -> Math.addExact(offset, sequence)).toArray();
			for (final RangeIndex.RangePage page : emission.changedPages()) {
				parts.add(new RangeIndexLeafPagePart(root.getEntityIndexPrimaryKey(), streamKey,
					Math.addExact(offset, page.pageSequence()), page.points()));
			}
		} else {
			highWater = -1;
			sequences = new int[0];
		}
		for (final int sequence : root.getRangeLeafPageSequences()) {
			parts.add(new RangeIndexLeafPageRemoval(root.getEntityIndexPrimaryKey(), streamKey, sequence));
		}
		parts.add(new FilterIndexStoragePart(
			root.getEntityIndexPrimaryKey(), root.getAttributeIndexKey(), root.getAttributeType(),
			root.getHistogramPoints(), paged ? null : companion, root.getIndexedDecimalPlaces(), root.isPaged(),
			root.getHighWaterPageSequence(), root.getLeafPageSequences(), paged, highWater, sequences,
			root.getNextValueId(), root.getInlineValueIds(), root.getStoragePartPK()
		));
		return parts;
	}
}
