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

package io.evitadb.store.catalog.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.CatalogState;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.spi.model.CatalogHeader;
import io.evitadb.store.spi.model.reference.CollectionFileReference;
import io.evitadb.store.spi.model.reference.WalFileReference;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.UUIDUtil;

import java.util.Map;

/**
 * This {@link Serializer} implementation reads/writes {@link CatalogHeader} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Deprecated(since = "2024.8", forRemoval = true)
public class CatalogHeaderSerializer_2024_08 extends AbstractPersistentStorageHeaderSerializer<CatalogHeader> {

	@Override
	public void write(Kryo kryo, Output output, CatalogHeader object) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@Override
	public CatalogHeader read(Kryo kryo, Input input, Class<? extends CatalogHeader> type) {
		final int storageProtocolVersion = input.readVarInt(true);
		final String catalogName = input.readString();
		final long version = input.readVarLong(true);
		final int lastEntityCollectionPrimaryKey = input.readVarInt(true);
		final double activeRecordShare = input.readDouble();

		final WalFileReference walFileReference;
		if (input.readBoolean()) {
			final int walFileIndex = input.readVarInt(true);
			final long walStartingPosition = input.readVarLong(true);
			final int walRecordLength = input.readVarInt(true);
			walFileReference = new WalFileReference(
				catalogName,
				walFileIndex,
				new FileLocation(walStartingPosition, walRecordLength)
			);
		} else {
			walFileReference = null;
		}

		final int entityTypeCount = input.readVarInt(true);
		final Map<String, CollectionFileReference> collectionFileIndex = CollectionUtils.createHashMap(entityTypeCount);
		for (int i = 0; i < entityTypeCount; i++) {
			final String entityType = input.readString();
			final int entityTypePrimaryKey = input.readVarInt(true);
			final int entityTypeFileIndex = input.readVarInt(true);
			final long entityTypeStartingPosition = input.readVarLong(true);
			final int entityTypeRecordLength = input.readVarInt(true);
			collectionFileIndex.put(
				entityType,
				new CollectionFileReference(
					entityType,
					entityTypePrimaryKey,
					entityTypeFileIndex,
					new FileLocation(entityTypeStartingPosition, entityTypeRecordLength)
				)
			);
		}

		final Map<Integer, Object> compressedKeys = deserializeKeys(input, kryo);
		final CatalogState catalogState = kryo.readObject(input, CatalogState.class);

		return new CatalogHeader(
			storageProtocolVersion,
			version,
			walFileReference,
			collectionFileIndex,
			compressedKeys,
			UUIDUtil.randomUUID(),
			catalogName,
			catalogState,
			lastEntityCollectionPrimaryKey,
			activeRecordShare
		);
	}

}
