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

package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.index.cardinality.AttributeCardinalityIndex.AttributeCardinalityKey;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStoragePart;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.Locale;
import java.util.Map;

/**
 * Kryo {@link Serializer} for {@link HistogramIndexStoragePart}. Serializes both the filter data (histogram points
 * and optional range index) and the cardinality data into a single binary blob.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class HistogramIndexStoragePartSerializer extends Serializer<HistogramIndexStoragePart> {

	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, HistogramIndexStoragePart part) {
		output.writeInt(part.getEntityIndexPrimaryKey());
		output.writeVarLong(
			part.computeUniquePartIdAndSet(this.keyCompressor),
			true
		);
		output.writeString(part.getHistogramName());
		kryo.writeObjectOrNull(output, part.getLocale(), Locale.class);

		// filter data
		kryo.writeClass(output, part.getValueType());
		final ValueToRecordBitmap[] histogramPoints = part.getHistogramPoints();
		output.writeInt(histogramPoints.length);
		for (ValueToRecordBitmap point : histogramPoints) {
			kryo.writeObject(output, point);
		}
		output.writeBoolean(part.getRangeIndex() != null);
		if (part.getRangeIndex() != null) {
			kryo.writeObject(output, part.getRangeIndex());
		}

		// cardinality data
		final AttributeCardinalityIndex cardinalityIndex = part.getCardinalityIndex();
		final Map<AttributeCardinalityKey, Integer> cardinalities = cardinalityIndex.getCardinalities();
		output.writeVarInt(cardinalities.size(), true);
		for (Map.Entry<AttributeCardinalityKey, Integer> entry : cardinalities.entrySet()) {
			kryo.writeObject(output, entry.getKey().value());
			output.writeVarInt(entry.getKey().recordId(), false);
			output.writeVarInt(entry.getValue(), true);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public HistogramIndexStoragePart read(Kryo kryo, Input input, Class<? extends HistogramIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final String histogramName = input.readString();
		final Locale locale = kryo.readObjectOrNull(input, Locale.class);

		// filter data
		final Class<? extends Serializable> valueType =
			(Class<? extends Serializable>) kryo.readClass(input).getType();
		final int pointCount = input.readInt();
		final ValueToRecordBitmap[] histogramPoints = new ValueToRecordBitmap[pointCount];
		for (int i = 0; i < pointCount; i++) {
			histogramPoints[i] = kryo.readObject(input, ValueToRecordBitmap.class);
		}
		final boolean hasRangeIndex = input.readBoolean();
		final RangeIndex rangeIndex = hasRangeIndex ? kryo.readObject(input, RangeIndex.class) : null;

		// cardinality data
		final int cardinalityCount = input.readVarInt(true);
		final Map<AttributeCardinalityKey, Integer> cardinalities =
			CollectionUtils.createHashMap(cardinalityCount);
		for (int i = 0; i < cardinalityCount; i++) {
			final Serializable value = kryo.readObject(input, valueType);
			final int recordId = input.readVarInt(false);
			final int cardinality = input.readVarInt(true);
			cardinalities.put(new AttributeCardinalityKey(recordId, value), cardinality);
		}

		return new HistogramIndexStoragePart(
			entityIndexPrimaryKey, histogramName, locale, valueType,
			histogramPoints, rangeIndex,
			new AttributeCardinalityIndex(valueType, cardinalities),
			uniquePartId
		);
	}

}
