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
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramCardinalityStoragePart;
import io.evitadb.store.index.serializer.HistogramIdentitySerializer.HistogramIdentity;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * Kryo {@link Serializer} for the {@link HistogramCardinalityStoragePart} sibling. Serializes the
 * {@link AttributeCardinalityIndex} of a histogram entry — its value type plus the flat `(value, recordId) -> count`
 * entry list — using the same self-describing scaled-value encoding the (former inline) histogram cardinality block
 * used.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class HistogramCardinalityStoragePartSerializer extends Serializer<HistogramCardinalityStoragePart> {

	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, HistogramCardinalityStoragePart part) {
		HistogramIdentitySerializer.write(kryo, output, part, this.keyCompressor);

		final AttributeCardinalityIndex cardinalityIndex = part.getCardinalityIndex();
		kryo.writeClass(output, cardinalityIndex.getValueType());
		final Map<AttributeCardinalityKey, Integer> cardinalities = cardinalityIndex.getCardinalities();
		output.writeVarInt(cardinalities.size(), true);
		for (final Map.Entry<AttributeCardinalityKey, Integer> entry : cardinalities.entrySet()) {
			// the key value is self-describing: a BigDecimal value type stores an order-preserving scaled Integer here,
			// so the concrete runtime type is written alongside the value
			kryo.writeClassAndObject(output, entry.getKey().value());
			output.writeVarInt(entry.getKey().recordId(), false);
			output.writeVarInt(entry.getValue(), true);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public HistogramCardinalityStoragePart read(
		Kryo kryo, Input input, Class<? extends HistogramCardinalityStoragePart> type
	) {
		final HistogramIdentity identity = HistogramIdentitySerializer.read(kryo, input);

		final Class<? extends Serializable> valueType =
			(Class<? extends Serializable>) kryo.readClass(input).getType();
		final int cardinalityCount = input.readVarInt(true);
		final Map<AttributeCardinalityKey, Integer> cardinalities =
			CollectionUtils.createHashMap(cardinalityCount);
		for (int i = 0; i < cardinalityCount; i++) {
			final Serializable value = (Serializable) kryo.readClassAndObject(input);
			final int recordId = input.readVarInt(false);
			final int cardinality = input.readVarInt(true);
			cardinalities.put(new AttributeCardinalityKey(recordId, value), cardinality);
		}

		return new HistogramCardinalityStoragePart(
			identity.entityIndexPrimaryKey(), identity.histogramName(), identity.locale(),
			new AttributeCardinalityIndex(valueType, cardinalities),
			identity.uniquePartId()
		);
	}

}
