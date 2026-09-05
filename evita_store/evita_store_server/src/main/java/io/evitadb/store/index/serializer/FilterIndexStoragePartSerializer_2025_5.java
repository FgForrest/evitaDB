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

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import lombok.RequiredArgsConstructor;

/**
 * Backward-compatible reader for the {@link FilterIndexStoragePart} format shipped by release 2025.5 — the oldest
 * shape still loadable, in which the part's buckets and its optional range index were both written inline, no
 * `indexedDecimalPlaces` scale was persisted, and the attribute key may still be a plain
 * {@code AttributesContract.AttributeKey} that {@link AttributeKeyToAttributeKeyIndexBridge} lifts into an
 * {@link AttributeIndexKey}.
 *
 * It only reads: {@link #write} throws, because the current
 * {@link FilterIndexStoragePartSerializer} owns every write and this shape must never be produced again.
 *
 * Like every format that predates the millisecond move, it persisted its range thresholds at **second** granularity,
 * so it marks each part it produces with {@link FilterIndexStoragePart#isSecondGranularityRangeThresholds()}. The
 * rescale deliberately does not happen here: a range index whose axis is `PAGED` keeps its thresholds in leaf-page
 * records this serializer never sees, and a threshold is an untyped `long` shared by `DateTimeRange` and every
 * `NumberRange` subtype, so only the declared attribute type can decide which parts to repair — both facts belong to
 * the load path. See {@code AttributeIndexLoader#loadRangeIndex}.
 *
 * @deprecated only for backward compatibility purposes; can be removed once no catalog written by release 2025.5 or
 *             earlier is still in use.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Deprecated(since = "2025.7", forRemoval = true)
@RequiredArgsConstructor
public class FilterIndexStoragePartSerializer_2025_5 extends Serializer<FilterIndexStoragePart>
	implements AttributeKeyToAttributeKeyIndexBridge {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, FilterIndexStoragePart filterIndex) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@Override
	public FilterIndexStoragePart read(Kryo kryo, Input input, Class<? extends FilterIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final AttributeIndexKey attributeKey = getAttributeIndexKey(input, this.keyCompressor);
		final Class<?> attributeType = kryo.readClass(input).getType();

		final int pointCount = input.readInt();
		final ValueToRecordBitmap[] points = new ValueToRecordBitmap[pointCount];
		for (int i = 0; i < pointCount; i++) {
			points[i] = kryo.readObject(input, ValueToRecordBitmap.class);
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

}
