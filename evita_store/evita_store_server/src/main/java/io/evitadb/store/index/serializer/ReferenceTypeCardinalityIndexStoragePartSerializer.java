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
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.cardinality.AttributeCardinalityIndex;
import io.evitadb.index.cardinality.ReferenceTypeCardinalityIndex;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.model.storageParts.index.ReferenceNameKey;
import io.evitadb.store.spi.model.storageParts.index.ReferenceTypeCardinalityIndexStoragePart;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.Map.Entry;

import static java.util.Optional.ofNullable;

/**
 * This {@link Serializer} implementation reads/writes {@link AttributeCardinalityIndex} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class ReferenceTypeCardinalityIndexStoragePartSerializer extends Serializer<ReferenceTypeCardinalityIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, ReferenceTypeCardinalityIndexStoragePart storagePart) {
		output.writeInt(storagePart.getEntityIndexPrimaryKey());
		final long uniquePartId = ofNullable(storagePart.getStoragePartPK()).orElseGet(() -> storagePart.computeUniquePartIdAndSet(this.keyCompressor));
		output.writeVarLong(uniquePartId, true);
		output.writeVarInt(this.keyCompressor.getId(new ReferenceNameKey(storagePart.getReferenceName())), true);

		final ReferenceTypeCardinalityIndex cardinalityIndex = storagePart.getCardinalityIndex();
		final Map<Long, Integer> cardinalities = cardinalityIndex.getCardinalities();
		output.writeVarInt(cardinalities.size(), true);
		for (Entry<Long, Integer> entry : cardinalities.entrySet()) {
			output.writeVarLong(entry.getKey(), false);
			output.writeVarInt(entry.getValue(), false);
		}

		final TransactionalMap<Integer, TransactionalBitmap> referencedPrimaryKeysIndex = cardinalityIndex.getReferencedPrimaryKeysIndex();
		output.writeVarInt(referencedPrimaryKeysIndex.size(), true);
		for (Entry<Integer, TransactionalBitmap> entry : referencedPrimaryKeysIndex.entrySet()) {
			output.writeVarInt(entry.getKey(), true);
			kryo.writeObject(output, entry.getValue());
		}
	}

	@Override
	public ReferenceTypeCardinalityIndexStoragePart read(Kryo kryo, Input input, Class<? extends ReferenceTypeCardinalityIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final ReferenceNameKey referenceNameKey = this.keyCompressor.getKeyForId(input.readVarInt(true));

		final int cardinalityCount = input.readVarInt(true);
		final Map<Long, Integer> cardinalities = CollectionUtils.createHashMap(cardinalityCount);
		for (int i = 0; i < cardinalityCount; i++) {
			final long key = input.readVarLong(false);
			final int cardinality = input.readVarInt(false);
			cardinalities.put(key, cardinality);
		}

		final int referencedPrimaryKeysIndexSize = input.readVarInt(true);
		final Map<Integer, TransactionalBitmap> referencedPrimaryKeysIndex = CollectionUtils.createHashMap(referencedPrimaryKeysIndexSize);
		for (int i = 0; i < referencedPrimaryKeysIndexSize; i++) {
			final int key = input.readVarInt(true);
			final TransactionalBitmap bitmap = kryo.readObject(input, TransactionalBitmap.class);
			referencedPrimaryKeysIndex.put(key, bitmap);
		}

		final ReferenceTypeCardinalityIndex cardinalityIndex = new ReferenceTypeCardinalityIndex(
			cardinalities, referencedPrimaryKeysIndex
		);
		return new ReferenceTypeCardinalityIndexStoragePart(
			entityIndexPrimaryKey, referenceNameKey.referenceName(), cardinalityIndex, uniquePartId
		);
	}

}
