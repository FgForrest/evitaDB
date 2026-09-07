/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Backward-compatible {@link Serializer} for the pre-freeze {@link FilterIndexStoragePart} format that did NOT persist
 * the `indexedDecimalPlaces` scale (the scale was re-derived from the schema at load). Reads such legacy blobs into a
 * part whose scale defaults to `0`; the scale-bearing format is handled by {@link FilterIndexStoragePartSerializer}.
 * Every `BigDecimal` filter part is re-keyed and re-written with the current serializer by `Migration_2026_2`, so a
 * legacy blob is only ever read for a non-`BigDecimal` part, whose correct scale is `0`.
 *
 * Like every format that predates the millisecond move, it persisted its range thresholds at **second** granularity,
 * so it marks each part it produces with {@link FilterIndexStoragePart#isSecondGranularityRangeThresholds()}. The
 * rescale deliberately does not happen here: a range index whose axis is `PAGED` keeps its thresholds in leaf-page
 * records this serializer never sees, and a threshold is an untyped `long` shared by `DateTimeRange` and every
 * `NumberRange` subtype, so only the declared attribute type can decide which parts to repair — both facts belong to
 * the load path. See {@code AttributeIndexLoader#loadRangeIndex}.
 *
 * @deprecated only for backward compatibility purposes
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Deprecated(since = "2026.2", forRemoval = true)
@RequiredArgsConstructor
public class FilterIndexStoragePartSerializer_2026_1 extends Serializer<FilterIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, FilterIndexStoragePart filterIndex) {
		output.writeInt(filterIndex.getEntityIndexPrimaryKey());
		final Long uniquePartId = filterIndex.getStoragePartPK();
		Assert.notNull(uniquePartId, "Unique part id should have been computed by now!");
		output.writeVarLong(uniquePartId, true);
		output.writeVarInt(this.keyCompressor.getId(filterIndex.getAttributeIndexKey()), true);
		kryo.writeClass(output, filterIndex.getAttributeType());

		final ValueToRecordBitmap[] points = filterIndex.getHistogramPoints();
		output.writeInt(points.length);
		for (ValueToRecordBitmap range : points) {
			kryo.writeObject(output, range);
		}

		final boolean rangeIndex = filterIndex.getRangeIndex() != null;
		output.writeBoolean(rangeIndex);
		if (rangeIndex) {
			kryo.writeObject(output, filterIndex.getRangeIndex());
		}
	}

	@Override
	public FilterIndexStoragePart read(Kryo kryo, Input input, Class<? extends FilterIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final AttributeIndexKey attributeKey = this.keyCompressor.getKeyForId(input.readVarInt(true));
		final Class<?> attributeType = kryo.readClass(input).getType();

		final int pointCount = input.readInt();
		final ValueToRecordBitmap[] points = new ValueToRecordBitmap[pointCount];
		for (int i = 0; i < pointCount; i++) {
			points[i] = anchorLegacyLocalDateTime(kryo.readObject(input, ValueToRecordBitmap.class));
		}

		final boolean hasRangeIndex = input.readBoolean();
		final RangeIndex intRangeIndex = hasRangeIndex ? kryo.readObject(input, RangeIndex.class) : null;
		final FilterIndexStoragePart part = new FilterIndexStoragePart(
			entityIndexPrimaryKey, attributeKey, attributeType, points, intRangeIndex, uniquePartId
		);
		// every FilterIndexStoragePart format older than the millisecond change persisted its range thresholds as
		// epoch SECONDS; mark the provenance so AttributeIndexLoader can rescale the ones that belong to a
		// `DateTimeRange` attribute (a threshold is an untyped long shared with every NumberRange subtype, so the
		// declared attribute type - not this flag alone - decides)
		part.setSecondGranularityRangeThresholds(true);
		return part;
	}

	/**
	 * Re-anchors a legacy `LocalDateTime` bucket value at UTC so it lands in the `Instant` space the current
	 * `FilterIndex.getNormalizer` keys `LocalDateTime` attributes with.
	 *
	 * `2026.1` had no `LocalDateTime` branch in its normalizer, so it persisted the raw wall-clock value; the current
	 * tree keys such an attribute by an {@code Instant} held in a primitive {@code long} column, and would fail with a
	 * `ClassCastException` while rehydrating those buckets. Anchoring at UTC is exactly what the normalizer now does
	 * on the write path, and because the offset is constant the mapping preserves the bucket ordering the reload path
	 * relies on.
	 *
	 * The conversion is self-healing: once the index is written again it is persisted through the current serializer
	 * with `Instant` keys, and this legacy reader is no longer consulted for it.
	 *
	 * @param bucket bucket just read from a legacy blob
	 * @return the bucket, with a `LocalDateTime` value replaced by its UTC instant
	 */
	@Nonnull
	private static ValueToRecordBitmap anchorLegacyLocalDateTime(@Nonnull ValueToRecordBitmap bucket) {
		return bucket.getValue() instanceof LocalDateTime localDateTime
			? new ValueToRecordBitmap(localDateTime.toInstant(ZoneOffset.UTC), bucket.getRecordIds())
			: bucket;
	}

}
