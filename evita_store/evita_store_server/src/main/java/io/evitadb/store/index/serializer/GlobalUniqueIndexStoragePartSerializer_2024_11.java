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
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.dataType.Scope;
import io.evitadb.index.attribute.GlobalUniqueIndex;
import io.evitadb.index.attribute.GlobalUniqueIndex.EntityWithTypeTuple;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.model.storageParts.index.GlobalUniqueIndexStoragePart;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.util.Locale;
import java.util.Map;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * This {@link Serializer} implementation reads/writes {@link GlobalUniqueIndex} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Deprecated(since = "2024.11", forRemoval = true)
@RequiredArgsConstructor
public class GlobalUniqueIndexStoragePartSerializer_2024_11 extends Serializer<GlobalUniqueIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, GlobalUniqueIndexStoragePart uniqueIndex) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used anymore!");
	}

	@Override
	public GlobalUniqueIndexStoragePart read(Kryo kryo, Input input, Class<? extends GlobalUniqueIndexStoragePart> type) {
		final long uniquePartId = input.readVarLong(true);
		final AttributeKey attributeKey = keyCompressor.getKeyForId(input.readVarInt(true));
		@SuppressWarnings("unchecked") final Class<? extends Serializable> attributeType = kryo.readClass(input).getType();

		final int uniqueValueCount = input.readVarInt(true);
		final Map<Serializable, EntityWithTypeTuple> uniqueIndex = createHashMap(uniqueValueCount);
		for (int i = 0; i < uniqueValueCount; i++) {
			final Serializable key = kryo.readObject(input, attributeType);
			final int entityType = input.readVarInt(true);
			final int primaryKey = input.readInt();
			final int locale = input.readInt();
			uniqueIndex.put(key, new EntityWithTypeTuple(entityType, primaryKey, locale));
		}

		final int localeCount = input.readVarInt(true);
		final Map<Integer, Locale> localeIndex = createHashMap(localeCount);
		for (int i = 0; i < localeCount; i++) {
			localeIndex.put(
				input.readVarInt(true),
				kryo.readObject(input, Locale.class)
			);
		}

		return new GlobalUniqueIndexStoragePart(
			Scope.LIVE, attributeKey, attributeType, uniqueIndex, localeIndex, uniquePartId
		);
	}

}
