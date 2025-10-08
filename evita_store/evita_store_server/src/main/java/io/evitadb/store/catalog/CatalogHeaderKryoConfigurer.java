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

package io.evitadb.store.catalog;

import com.esotericsoftware.kryo.Kryo;
import io.evitadb.api.CatalogState;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.store.catalog.serializer.CatalogHeaderSerializer;
import io.evitadb.store.catalog.serializer.CatalogHeaderSerializer_2024_05;
import io.evitadb.store.catalog.serializer.CatalogHeaderSerializer_2024_08;
import io.evitadb.store.catalog.serializer.EntityCollectionHeaderSerializer;
import io.evitadb.store.catalog.serializer.EntityCollectionHeaderSerializer_2024_11;
import io.evitadb.store.catalog.serializer.EntityCollectionHeaderSerializer_2024_5;
import io.evitadb.store.dataType.serializer.EnumNameSerializer;
import io.evitadb.store.dataType.serializer.SerialVersionBasedSerializer;
import io.evitadb.store.entity.model.entity.AttributesStoragePart.AttributesSetKey;
import io.evitadb.store.entity.serializer.AttributesSetKeySerializer;
import io.evitadb.store.index.serializer.AttributeKeyWithIndexTypeSerializer;
import io.evitadb.store.index.serializer.AttributeKeyWithIndexTypeSerializer_2025_5;
import io.evitadb.store.index.serializer.PriceIndexKeySerializer;
import io.evitadb.store.schema.serializer.CatalogSchemaSerializer;
import io.evitadb.store.spi.model.CatalogHeader;
import io.evitadb.store.spi.model.EntityCollectionHeader;
import io.evitadb.store.spi.model.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.function.Consumer;

/**
 * This {@link Consumer} implementation takes default Kryo instance and registers additional serializers that are
 * required to (de)serialize {@link CatalogHeader}.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CatalogHeaderKryoConfigurer implements Consumer<Kryo> {
	public static final CatalogHeaderKryoConfigurer INSTANCE = new CatalogHeaderKryoConfigurer();
	private static final int CATALOG_BASE = 700;

	@Override
	public void accept(Kryo kryo) {
		int index = CATALOG_BASE;

		kryo.register(
			CatalogHeader.class,
			new SerialVersionBasedSerializer<>(new CatalogHeaderSerializer(), CatalogHeader.class)
				.addBackwardCompatibleSerializer(-3595987669559870397L, new CatalogHeaderSerializer_2024_05())
				.addBackwardCompatibleSerializer(-5987715153038480011L, new CatalogHeaderSerializer_2024_08()),
			index++
		);
		kryo.register(CatalogSchema.class, new SerialVersionBasedSerializer<>(new CatalogSchemaSerializer(), CatalogSchema.class), index++);
		kryo.register(CatalogState.class, new EnumNameSerializer<>(), index++);
		kryo.register(
			EntityCollectionHeader.class,
			new SerialVersionBasedSerializer<>(new EntityCollectionHeaderSerializer(), EntityCollectionHeader.class)
				.addBackwardCompatibleSerializer(6342590529867272012L, new EntityCollectionHeaderSerializer_2024_11())
				.addBackwardCompatibleSerializer(1079906797886901404L, new EntityCollectionHeaderSerializer_2024_5()),
			index++
		);
		kryo.register(AttributesSetKey.class, new SerialVersionBasedSerializer<>(new AttributesSetKeySerializer(), AttributesSetKey.class), index++);
		kryo.register(
			AttributeKeyWithIndexType.class,
			new SerialVersionBasedSerializer<>(new AttributeKeyWithIndexTypeSerializer(), AttributeKeyWithIndexType.class)
				.addBackwardCompatibleSerializer(2526424804226344907L, new AttributeKeyWithIndexTypeSerializer_2025_5()),
			index++
		);
		kryo.register(PriceIndexKey.class, new SerialVersionBasedSerializer<>(new PriceIndexKeySerializer(), PriceIndexKey.class), index++);

		Assert.isPremiseValid(index < 800, "Index count overflow.");
	}

}
