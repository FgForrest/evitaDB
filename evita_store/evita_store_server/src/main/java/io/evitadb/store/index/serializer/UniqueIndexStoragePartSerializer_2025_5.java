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
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexKey;
import io.evitadb.store.spi.model.storageParts.index.UniqueIndexStoragePart;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.Map;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * This {@link Serializer} implementation reads/writes {@link io.evitadb.index.attribute.UniqueIndex} from/to binary format.
 *
 * @deprecated only for backward compatibility purposes
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Deprecated(since = "2025.5", forRemoval = true)
@RequiredArgsConstructor
public class UniqueIndexStoragePartSerializer_2025_5 extends Serializer<UniqueIndexStoragePart>
	implements AttributeKeyToAttributeKeyIndexBridge {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, UniqueIndexStoragePart uniqueIndex) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@Override
	public UniqueIndexStoragePart read(Kryo kryo, Input input, Class<? extends UniqueIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final AttributeIndexKey attributeKey = getAttributeIndexKey(input, this.keyCompressor);
		@SuppressWarnings("unchecked") final Class<? extends Serializable> attributeType = kryo.readClass(input).getType();
		final TransactionalBitmap recordIds = kryo.readObject(input, TransactionalBitmap.class);

		final int uniqueValueCount = input.readVarInt(true);
		final Map<Serializable, Integer> uniqueIndex = createHashMap(uniqueValueCount);
		for (int i = 0; i < uniqueValueCount; i++) {
			final Serializable key = kryo.readObject(input, attributeType);
			final int value = input.readInt();
			uniqueIndex.put(key, value);
		}

		return new UniqueIndexStoragePart(
			entityIndexPrimaryKey, attributeKey, attributeType, uniqueIndex, recordIds, uniquePartId
		);
	}

}
