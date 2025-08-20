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

package io.evitadb.store.entity;

import com.esotericsoftware.kryo.Kryo;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.Price;
import io.evitadb.api.requestResponse.data.structure.Prices;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.dataType.Scope;
import io.evitadb.store.dataType.serializer.EnumNameSerializer;
import io.evitadb.store.dataType.serializer.SerialVersionBasedSerializer;
import io.evitadb.store.entity.model.entity.AssociatedDataStoragePart;
import io.evitadb.store.entity.model.entity.AttributesStoragePart;
import io.evitadb.store.entity.model.entity.AttributesStoragePart.AttributesSetKey;
import io.evitadb.store.entity.model.entity.EntityBodyStoragePart;
import io.evitadb.store.entity.model.entity.PricesStoragePart;
import io.evitadb.store.entity.model.entity.ReferencesStoragePart;
import io.evitadb.store.entity.serializer.*;
import io.evitadb.store.model.EntityStoragePart;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import java.util.function.Consumer;

/**
 * This {@link Consumer} implementation takes default Kryo instance and registers additional serializers that are
 * required to (de)serialize {@link EntityStoragePart} implementations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class EntityStoragePartConfigurer implements Consumer<Kryo> {
	private final KeyCompressor keyCompressor;
	private static final int ENTITY_BASE = 500;

	@Override
	public void accept(Kryo kryo) {
		int index = ENTITY_BASE;

		kryo.register(
			EntityBodyStoragePart.class,
			new SerialVersionBasedSerializer<>(new EntityBodyStoragePartSerializer(this.keyCompressor), EntityBodyStoragePart.class)
				.addBackwardCompatibleSerializer(34998825794290379L, new EntityBodyStoragePartSerializer_2024_11(this.keyCompressor)),
			index++
		);
		kryo.register(PricesStoragePart.class, new SerialVersionBasedSerializer<>(new PricesStoragePartSerializer(), PricesStoragePart.class), index++);
		kryo.register(ReferencesStoragePart.class, new SerialVersionBasedSerializer<>(new ReferencesStoragePartSerializer(), ReferencesStoragePart.class), index++);
		kryo.register(AttributesStoragePart.class, new SerialVersionBasedSerializer<>(new AttributesStoragePartSerializer(this.keyCompressor), AttributesStoragePart.class), index++);
		kryo.register(AttributesSetKey.class, new SerialVersionBasedSerializer<>(new AttributesSetKeySerializer(), AttributesSetKey.class), index++);
		kryo.register(AssociatedDataStoragePart.class, new SerialVersionBasedSerializer<>(new AssociatedDataStoragePartSerializer(this.keyCompressor), AssociatedDataStoragePart.class), index++);

		kryo.register(AttributeValue.class, new SerialVersionBasedSerializer<>(new AttributeValueSerializer(this.keyCompressor), AttributeValue.class), index++);
		kryo.register(AssociatedDataValue.class, new SerialVersionBasedSerializer<>(new AssociatedDataValueSerializer(this.keyCompressor), AssociatedDataValue.class), index++);
		kryo.register(Reference.class, new SerialVersionBasedSerializer<>(new ReferenceSerializer(), Reference.class), index++);
		kryo.register(Prices.class, new SerialVersionBasedSerializer<>(new PricesSerializer(), Prices.class), index++);
		kryo.register(Price.class, new SerialVersionBasedSerializer<>(new PriceSerializer(this.keyCompressor), Price.class), index++);
		kryo.register(EntityReference.class, new SerialVersionBasedSerializer<>(new EntityReferenceSerializer(), EntityReference.class), index++);
		kryo.register(AttributesSetKey.class, new SerialVersionBasedSerializer<>(new AttributesSetKeySerializer(), AttributesSetKey.class), index++);
		kryo.register(Scope.class, new EnumNameSerializer<>(), index++);

		Assert.isPremiseValid(index < 600, "Index count overflow.");
	}

}
