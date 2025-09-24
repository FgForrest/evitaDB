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

package io.evitadb.store.service;

import com.esotericsoftware.kryo.Kryo;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.key.CompressiblePriceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.dataType.Scope;
import io.evitadb.store.dataType.serializer.EnumNameSerializer;
import io.evitadb.store.dataType.serializer.SerialVersionBasedSerializer;
import io.evitadb.store.serializer.AssociatedDataKeySerializer;
import io.evitadb.store.serializer.AttributeKeySerializer;
import io.evitadb.store.serializer.CompressiblePriceKeySerializer;
import io.evitadb.store.serializer.ReferenceKeySerializer;
import io.evitadb.store.serializer.ReferenceKeySerializer_2025_6;
import io.evitadb.utils.Assert;

import java.util.function.Consumer;

/**
 * This implementation registers all classes that are shared between entity / catalog and other serializations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class SharedClassesConfigurer implements Consumer<Kryo> {
	public static final SharedClassesConfigurer INSTANCE = new SharedClassesConfigurer();
	private static final int SHARED_BASE = 300;

	@Override
	public void accept(Kryo kryo) {
		int index = SHARED_BASE;

		kryo.register(AttributeKey.class, new SerialVersionBasedSerializer<>(new AttributeKeySerializer(), AttributeKey.class), index++);
		kryo.register(AssociatedDataKey.class, new SerialVersionBasedSerializer<>(new AssociatedDataKeySerializer(), AssociatedDataKey.class), index++);
		kryo.register(CompressiblePriceKey.class, new SerialVersionBasedSerializer<>(new CompressiblePriceKeySerializer(), CompressiblePriceKey.class), index++);
		kryo.register(
			ReferenceKey.class,
			new SerialVersionBasedSerializer<>(new ReferenceKeySerializer(), ReferenceKey.class)
				.addBackwardCompatibleSerializer(-1234554594699404014L, new ReferenceKeySerializer_2025_6()),
			index++
		);
		kryo.register(PriceInnerRecordHandling.class, new EnumNameSerializer<PriceInnerRecordHandling>(), index++);
		kryo.register(AttributeUniquenessType.class, new EnumNameSerializer<AttributeUniquenessType>(), index++);
		kryo.register(GlobalAttributeUniquenessType.class, new EnumNameSerializer<GlobalAttributeUniquenessType>(), index++);
		kryo.register(Scope.class, new EnumNameSerializer<Scope>(), index++);

		Assert.isPremiseValid(index < 400, "Index count overflow.");
	}

}
