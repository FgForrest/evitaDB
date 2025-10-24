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
import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.index.cardinality.AttributeCardinalityIndex.AttributeCardinalityKey;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.model.storageParts.index.AttributeCardinalityIndexStoragePart;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexKey;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.Map;
import java.util.Map.Entry;

import static java.util.Optional.ofNullable;

/**
 * This {@link Serializer} implementation reads/writes {@link AttributeCardinalityIndex} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class AttributeCardinalityIndexStoragePartSerializer extends Serializer<AttributeCardinalityIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, AttributeCardinalityIndexStoragePart storagePart) {
		output.writeInt(storagePart.getEntityIndexPrimaryKey());
		final long uniquePartId = ofNullable(storagePart.getStoragePartPK()).orElseGet(() -> storagePart.computeUniquePartIdAndSet(this.keyCompressor));
		output.writeVarLong(uniquePartId, true);
		output.writeVarInt(this.keyCompressor.getId(storagePart.getAttributeIndexKey()), true);

		final AttributeCardinalityIndex cardinalityIndex = storagePart.getCardinalityIndex();
		kryo.writeClass(output, cardinalityIndex.getValueType());
		final Map<AttributeCardinalityKey, Integer> cardinalities = cardinalityIndex.getCardinalities();
		output.writeVarInt(cardinalities.size(), true);
		for (Entry<AttributeCardinalityKey, Integer> entry : cardinalities.entrySet()) {
			kryo.writeObject(output, entry.getKey().value());
			output.writeVarInt(entry.getKey().recordId(), false);
			output.writeVarInt(entry.getValue(), true);
		}
	}

	@Override
	public AttributeCardinalityIndexStoragePart read(Kryo kryo, Input input, Class<? extends AttributeCardinalityIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final AttributeIndexKey attributeKey = this.keyCompressor.getKeyForId(input.readVarInt(true));

		@SuppressWarnings("unchecked") final Class<? extends Serializable> valueType = kryo.readClass(input).getType();
		final int cardinalityCount = input.readVarInt(true);
		final Map<AttributeCardinalityKey, Integer> cardinalities = CollectionUtils.createHashMap(cardinalityCount);
		for (int i = 0; i < cardinalityCount; i++) {
			final Serializable value = kryo.readObject(input, valueType);
			final int recordId = input.readVarInt(false);
			final int cardinality = input.readVarInt(true);
			cardinalities.put(new AttributeCardinalityKey(recordId, value), cardinality);
		}
		final AttributeCardinalityIndex cardinalityIndex = new AttributeCardinalityIndex(valueType, cardinalities);
		return new AttributeCardinalityIndexStoragePart(entityIndexPrimaryKey, attributeKey, cardinalityIndex, uniquePartId);
	}

}
