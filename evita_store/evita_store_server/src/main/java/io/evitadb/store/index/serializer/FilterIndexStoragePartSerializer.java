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
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

/**
 * This {@link Serializer} implementation reads/writes {@link FilterIndex} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class FilterIndexStoragePartSerializer extends Serializer<FilterIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, FilterIndexStoragePart filterIndex) {
		output.writeInt(filterIndex.getEntityIndexPrimaryKey());
		final Long uniquePartId = filterIndex.getStoragePartPK();
		Assert.notNull(uniquePartId, "Unique part id should have been computed by now!");
		output.writeVarLong(uniquePartId, true);
		output.writeVarInt(this.keyCompressor.getId(filterIndex.getAttributeIndexKey()), true);
		kryo.writeClass(output, filterIndex.getAttributeType());

		// SINGLE shape carries every bucket inline here; PAGED shape carries none (this writes length 0) — its buckets
		// live in individual FilterIndexLeafPagePart leaf pages.
		final ValueToRecordBitmap[] points = filterIndex.getHistogramPoints();
		output.writeInt(points.length);
		for (ValueToRecordBitmap range : points) {
			kryo.writeObject(output, range);
		}

		// the inline range companion: present only when the range is NOT paged (a range-PAGED part writes its threshold
		// tree as individual RangeIndexLeafPagePart leaf pages instead, and carries a null inline range)
		final boolean rangeIndex = filterIndex.getRangeIndex() != null;
		output.writeBoolean(rangeIndex);
		if (rangeIndex) {
			kryo.writeObject(output, filterIndex.getRangeIndex());
		}

		// the frozen `indexedDecimalPlaces` scale (0 for non-BigDecimal attributes)
		output.writeVarInt(filterIndex.getIndexedDecimalPlaces(), true);

		// the bucket SINGLE/PAGED discriminator + the PAGED page-stream metadata. Appended after the legacy fields
		// so the SINGLE shape stays a superset of the prior layout (just a trailing `false`).
		final boolean paged = filterIndex.isPaged();
		output.writeBoolean(paged);
		if (paged) {
			// the page-stream id is NOT persisted — it is recomputed at load from the sub-index identity
			output.writeVarInt(filterIndex.getHighWaterPageSequence(), true);
			final int[] leafPageSequences = filterIndex.getLeafPageSequences();
			output.writeVarInt(leafPageSequences.length, true);
			for (final int pageSequence : leafPageSequences) {
				output.writeVarInt(pageSequence, true);
			}
		}

		// the RANGE SINGLE/PAGED discriminator + the range page-stream metadata, independent of the
		// bucket axis. Appended last; the range stream id is recomputed at load with StreamKind.RANGE.
		final boolean rangePaged = filterIndex.isRangePaged();
		output.writeBoolean(rangePaged);
		if (rangePaged) {
			output.writeVarInt(filterIndex.getRangeHighWaterPageSequence(), true);
			final int[] rangeLeafPageSequences = filterIndex.getRangeLeafPageSequences();
			output.writeVarInt(rangeLeafPageSequences.length, true);
			for (final int pageSequence : rangeLeafPageSequences) {
				output.writeVarInt(pageSequence, true);
			}
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
			points[i] = kryo.readObject(input, ValueToRecordBitmap.class);
		}

		final boolean hasRangeIndex = input.readBoolean();
		final RangeIndex intRangeIndex = hasRangeIndex ? kryo.readObject(input, RangeIndex.class) : null;

		// the frozen `indexedDecimalPlaces` scale (0 for non-BigDecimal attributes)
		final int indexedDecimalPlaces = input.readVarInt(true);

		// the bucket SINGLE/PAGED discriminator + the PAGED page-stream metadata
		boolean paged = false;
		int highWaterPageSequence = -1;
		int[] leafPageSequences = ArrayUtils.EMPTY_INT_ARRAY;
		if (input.readBoolean()) {
			paged = true;
			// the page-stream id is recomputed at load from the sub-index identity, not read here
			highWaterPageSequence = input.readVarInt(true);
			final int leafCount = input.readVarInt(true);
			leafPageSequences = new int[leafCount];
			for (int i = 0; i < leafCount; i++) {
				leafPageSequences[i] = input.readVarInt(true);
			}
		}

		// the RANGE SINGLE/PAGED discriminator + the range page-stream metadata
		boolean rangePaged = false;
		int rangeHighWaterPageSequence = -1;
		int[] rangeLeafPageSequences = ArrayUtils.EMPTY_INT_ARRAY;
		if (input.readBoolean()) {
			rangePaged = true;
			rangeHighWaterPageSequence = input.readVarInt(true);
			final int rangeLeafCount = input.readVarInt(true);
			rangeLeafPageSequences = new int[rangeLeafCount];
			for (int i = 0; i < rangeLeafCount; i++) {
				rangeLeafPageSequences[i] = input.readVarInt(true);
			}
		}

		return new FilterIndexStoragePart(
			entityIndexPrimaryKey, attributeKey, attributeType, points, intRangeIndex, indexedDecimalPlaces,
			paged, highWaterPageSequence, leafPageSequences, rangePaged, rangeHighWaterPageSequence, rangeLeafPageSequences, uniquePartId
		);
	}

}
