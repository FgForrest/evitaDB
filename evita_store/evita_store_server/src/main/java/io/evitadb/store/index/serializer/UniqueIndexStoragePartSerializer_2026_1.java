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
 * Backward-compatible {@link Serializer} that reads the 2026.1 binary format of
 * {@link io.evitadb.index.attribute.UniqueIndex} data.
 *
 * The 2026.1 format **always** wrote the record-id bitmap followed by the value-to-record map immediately after the
 * attribute type. The current serializer made those two sections optional — a unique attribute that is folded
 * into the shared filter tree (view mode) derives its values and record ids from the shared `FilterIndexStoragePart`
 * and therefore omits them, writing a single boolean marker instead. This serializer reads the legacy always-present
 * layout so catalogs written by 2026.1 (and earlier catalogs that share its `serialVersionUID`) keep loading; the
 * loaded part is always a full (owner) part.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Deprecated(since = "2026.2", forRemoval = true)
@RequiredArgsConstructor
public class UniqueIndexStoragePartSerializer_2026_1 extends Serializer<UniqueIndexStoragePart> {
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
	}

}
