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

package io.evitadb.store.index;

import com.esotericsoftware.kryo.Kryo;
import io.evitadb.store.catalog.CatalogHeaderKryoConfigurer;
import io.evitadb.store.dataType.serializer.SerialVersionBasedSerializer;
import io.evitadb.store.index.serializer.AttributeIndexKeySerializer;
import io.evitadb.store.index.serializer.ReferenceNameKeySerializer;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.model.CatalogHeader;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexKey;
import io.evitadb.store.spi.model.storageParts.index.ReferenceNameKey;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.function.Consumer;

/**
 * This {@link Consumer} implementation takes default Kryo instance and registers additional serializers that are
 * required to (de)serialize {@link StoragePart} implementations.
 *
 * This specialized configurer is necessary because {@link IndexStoragePartConfigurer} requires {@link KeyCompressor}
 * to be instantiated which is not possible in all places where {@link CatalogHeaderKryoConfigurer} is used. But we
 * still need to have these serializers registered in those places because {@link CatalogHeader#compressedKeys()} might
 * contain these keys.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SharedIndexStoragePartConfigurer implements Consumer<Kryo> {
	public static final SharedIndexStoragePartConfigurer INSTANCE = new SharedIndexStoragePartConfigurer();
	private static final int INDEX_BASE = 800;

	@Override
	public void accept(Kryo kryo) {
		int index = INDEX_BASE;

		kryo.register(ReferenceNameKey.class, new SerialVersionBasedSerializer<>(new ReferenceNameKeySerializer(), ReferenceNameKey.class), index++);
		kryo.register(AttributeIndexKey.class, new SerialVersionBasedSerializer<>(new AttributeIndexKeySerializer(), AttributeIndexKey.class), index++);

		Assert.isPremiseValid(index < 900, "Index count overflow.");
	}

}
