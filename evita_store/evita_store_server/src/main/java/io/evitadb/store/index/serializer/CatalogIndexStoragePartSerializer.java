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
import io.evitadb.index.CatalogIndex;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.model.storageParts.index.CatalogIndexStoragePart;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;

import java.util.Set;

/**
 * This {@link Serializer} implementation reads/writes {@link CatalogIndex} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class CatalogIndexStoragePartSerializer extends Serializer<CatalogIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, CatalogIndexStoragePart catalogIndex) {
		output.writeVarInt(catalogIndex.getVersion(), true);

		final Set<AttributeKey> attributeKeys = catalogIndex.getSharedAttributeUniqueIndexes();
		output.writeVarInt(attributeKeys.size(), true);
		for (AttributeKey attributeKey : attributeKeys) {
			output.writeVarInt(keyCompressor.getId(attributeKey), true);
		}
	}

	@Override
	public CatalogIndexStoragePart read(Kryo kryo, Input input, Class<? extends CatalogIndexStoragePart> type) {
		final int version = input.readVarInt(true);
		final int attributeCount = input.readVarInt(true);

		final Set<AttributeKey> attributeKeys = CollectionUtils.createHashSet(attributeCount);
		for (int i = 0; i < attributeCount; i++) {
			attributeKeys.add(
				keyCompressor.getKeyForId(input.readVarInt(true))
			);
		}

		return new CatalogIndexStoragePart(version, attributeKeys);
	}

}
