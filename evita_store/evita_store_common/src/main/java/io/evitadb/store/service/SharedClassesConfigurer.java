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

package io.evitadb.store.service;

import com.esotericsoftware.kryo.Kryo;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.key.CompressiblePriceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.store.dataType.serializer.EnumNameSerializer;
import io.evitadb.store.dataType.serializer.SerialVersionBasedSerializer;
import io.evitadb.store.serializer.AssociatedDataKeySerializer;
import io.evitadb.store.serializer.AttributeKeySerializer;
import io.evitadb.store.serializer.CompressiblePriceKeySerializer;
import io.evitadb.store.serializer.ReferenceKeySerializer;
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
		kryo.register(ReferenceKey.class, new SerialVersionBasedSerializer<>(new ReferenceKeySerializer(), ReferenceKey.class), index++);
		kryo.register(PriceInnerRecordHandling.class, new EnumNameSerializer<PriceInnerRecordHandling>(), index++);

		Assert.isPremiseValid(index < 400, "Index count overflow.");
	}

}
