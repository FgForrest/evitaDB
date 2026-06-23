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
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexStoragePart;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.Map;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * This {@link Serializer} implementation reads {@link io.evitadb.index.attribute.UniqueIndex} from binary format.
 *
 * It reads the pre-granular-slimming format current in the 2026.2 development line; retained for backward
 * compatibility only. That format wrote, for an owner-mode part, the record-id bitmap (via `kryo.writeObject`) followed
 * by the value-to-record map with each record id as a fixed 4-byte int. The current serializer drops the redundant
 * bitmap (it equals the set of map values and is rebuilt on read) and writes each record id as a zig-zag varint.
 * Like the other deprecated readers its {@link #write(Kryo, Output, UniqueIndexStoragePart)} throws — this format must
 * never be written again. The dispatcher delegates writes only to the current serializer; the backward-compatible
 * reading is validated end-to-end by the backward-compatibility suite.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Deprecated(since = "2026.2", forRemoval = true)
@RequiredArgsConstructor
public class UniqueIndexStoragePartSerializer_2026_2 extends Serializer<UniqueIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, UniqueIndexStoragePart uniqueIndex) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@Override
	public UniqueIndexStoragePart read(Kryo kryo, Input input, Class<? extends UniqueIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final AttributeIndexKey attributeIndexKey = this.keyCompressor.getKeyForId(input.readVarInt(true));
		@SuppressWarnings("unchecked") final Class<? extends Serializable> attributeType = kryo.readClass(input).getType();

		// the record-id bitmap + value map sections are present only for owner-mode parts; a slim (view-mode) part
		// wrote a `false` marker and omitted them — they are re-derived from the shared FilterIndexStoragePart on load.
		final boolean dataPresent = input.readBoolean();
		if (dataPresent) {
			final TransactionalBitmap recordIds = kryo.readObject(input, TransactionalBitmap.class);

			final int uniqueValueCount = input.readVarInt(true);
			final Map<Serializable, Integer> uniqueIndex = createHashMap(uniqueValueCount);
			for (int i = 0; i < uniqueValueCount; i++) {
				final Serializable key = kryo.readObject(input, attributeType);
				final int value = input.readInt();
				uniqueIndex.put(key, value);
			}

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
