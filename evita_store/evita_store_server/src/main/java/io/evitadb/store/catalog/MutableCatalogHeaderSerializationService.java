/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.catalog;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.store.catalog.model.MutableCatalogEntityHeader;
import io.evitadb.store.catalog.model.MutableCatalogHeader;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.service.SerializationService;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * This class takes care of (de)serialization of dictionary contents from and to binary format.
 * Currently, simple implementation that keeps single kryo instance with all necessary classes registered. Implementation
 * is not thread safe.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
@NotThreadSafe
public class MutableCatalogHeaderSerializationService implements SerializationService<MutableCatalogHeader> {

	@Override
	public void serialize(@Nonnull MutableCatalogHeader header, @Nonnull Output output) {
		final Kryo kryo = KryoFactory.createKryo(CatalogHeaderKryoConfigurer.INSTANCE);

		output.writeString(header.getCatalogName());

		output.writeVarInt(header.getEntityTypes().size(), true);
		for (MutableCatalogEntityHeader entityHeader : header.getEntityTypesIndex().values()) {
			output.writeString(entityHeader.getEntityType());
			output.writeVarInt(entityHeader.getRecordCount(), true);
			serializeKeys(entityHeader.getIdToKeyIndex(), output, kryo);
		}
	}
	
	@Override
	public MutableCatalogHeader deserialize(@Nonnull Input input) {
		final String catalogName = input.readString();
		final Kryo kryo = KryoFactory.createKryo(CatalogHeaderKryoConfigurer.INSTANCE);

		final int entityTypeCount = input.readVarInt(true);
		final List<MutableCatalogEntityHeader> entityTypeHeaders = new ArrayList<>(entityTypeCount);
		for (int i = 0; i < entityTypeCount; i++) {
			final String entityType = input.readString();
			final int entityCount = input.readVarInt(true);
			final Map<Integer, Object> keys = deserializeKeys(input, kryo);
			entityTypeHeaders.add(new MutableCatalogEntityHeader(entityType, entityCount, keys));
		}
		return new MutableCatalogHeader(catalogName, entityTypeHeaders);
	}

	private void serializeKeys(@Nonnull Map<Integer, Object> keys, Output output, Kryo kryo) {
		output.writeVarInt(keys.size(), true);
		for (Entry<Integer, Object> entry : keys.entrySet()) {
			output.writeVarInt(entry.getKey(), true);
			kryo.writeClassAndObject(output, entry.getValue());
		}
	}

	private Map<Integer, Object> deserializeKeys(Input input, Kryo kryo) {
		final Map<Integer, Object> keys = new HashMap<>();
		final int keyCount = input.readVarInt(true);
		for (int i = 1; i <= keyCount; i++) {
			keys.put(
				input.readVarInt(true),
				kryo.readClassAndObject(input)
			);
		}
		return keys;
	}

}
