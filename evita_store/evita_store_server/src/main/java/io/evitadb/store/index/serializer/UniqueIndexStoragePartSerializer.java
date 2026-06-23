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
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexStoragePart;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * This {@link Serializer} implementation reads/writes {@link io.evitadb.index.attribute.UniqueIndex} from/to binary
 * format.
 *
 * The current (slim) format drops the redundant record-id bitmap from an owner-mode part: that bitmap always equals
 * the set of the value-to-record map values (see {@link io.evitadb.index.attribute.OwnerUniqueIndex}) and is rebuilt
 * from them on read, so it is no longer persisted. Each record id is written as a zig-zag varint instead of a fixed
 * 4-byte int. A folded (view-mode) part carries no value-to-record map at all — its data lives in the shared
 * `FilterIndexStoragePart` — and writes only a single boolean marker. The released-minor formats are read by
 * {@link UniqueIndexStoragePartSerializer_2025_5} / {@link UniqueIndexStoragePartSerializer_2026_1}; the prior 2026.2
 * development format was never released, so it has no backward-compatible reader.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class UniqueIndexStoragePartSerializer extends Serializer<UniqueIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, UniqueIndexStoragePart uniqueIndex) {
		output.writeInt(uniqueIndex.getEntityIndexPrimaryKey());
		final Long uniquePartId = uniqueIndex.getStoragePartPK();
		Assert.notNull(uniquePartId, "Unique part id should have been computed by now!");
		output.writeVarLong(uniquePartId, true);
		output.writeVarInt(this.keyCompressor.getId(uniqueIndex.getAttributeIndexKey()), true);

		final Class<?> plainType = uniqueIndex.getType().isArray() ? uniqueIndex.getType().getComponentType() : uniqueIndex.getType();
		kryo.writeClass(output, plainType);

		// a folded (view-mode) unique index derives its value-to-record map and record-id bitmap from the shared
		// FilterIndexStoragePart, so a slim part carries neither. The marker records whether the value map section
		// follows; only owner-mode (standalone) parts write it.
		final boolean dataPresent = uniqueIndex.isDataPresent();
		output.writeBoolean(dataPresent);
		if (dataPresent) {
			// the record-id bitmap is redundant: it always equals the set of the map values (see OwnerUniqueIndex)
			// and is reconstructed from them on read, so it is no longer persisted
			final Map<Serializable, Integer> uniqueValueToRecordId = Objects.requireNonNull(uniqueIndex.getUniqueValueToRecordId());
			output.writeVarInt(uniqueValueToRecordId.size(), true);
			for (Entry<Serializable, Integer> entry : uniqueValueToRecordId.entrySet()) {
				kryo.writeObject(output, entry.getKey());
				// record ids are primary keys; zig-zag keeps small magnitudes compact regardless of sign
				output.writeVarInt(entry.getValue(), false);
			}
		}
	}

	@Override
	public UniqueIndexStoragePart read(Kryo kryo, Input input, Class<? extends UniqueIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final AttributeIndexKey attributeIndexKey = this.keyCompressor.getKeyForId(input.readVarInt(true));
		@SuppressWarnings("unchecked") final Class<? extends Serializable> attributeType = kryo.readClass(input).getType();

		// the value map section is present only for owner-mode parts; a slim (view-mode) part wrote a `false` marker
		// and omitted it — it is re-derived from the shared FilterIndexStoragePart on load.
		final boolean dataPresent = input.readBoolean();
		if (dataPresent) {
			final int uniqueValueCount = input.readVarInt(true);
			final Map<Serializable, Integer> uniqueIndex = createHashMap(uniqueValueCount);
			final int[] recordIdValues = new int[uniqueValueCount];
			for (int i = 0; i < uniqueValueCount; i++) {
				final Serializable key = kryo.readObject(input, attributeType);
				final int value = input.readVarInt(false);
				uniqueIndex.put(key, value);
				recordIdValues[i] = value;
			}
			// the record-id bitmap is rebuilt from the map values (it is exactly the set of those values); the bitmap
			// dedupes, so duplicate values across the (distinct-keyed) map collapse just as the owner index expects
			final TransactionalBitmap recordIds = new TransactionalBitmap(recordIdValues);

			return new UniqueIndexStoragePart(
				entityIndexPrimaryKey, attributeIndexKey, attributeType, uniqueIndex, recordIds, uniquePartId
			);
		} else {
			return new UniqueIndexStoragePart(
				entityIndexPrimaryKey, attributeIndexKey, attributeType, uniquePartId
			);
		}
	}

}
