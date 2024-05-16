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

package io.evitadb.store.entity.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.store.entity.model.entity.PricesStoragePart;
import io.evitadb.store.entity.model.entity.price.PriceWithInternalIds;
import lombok.RequiredArgsConstructor;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * This {@link Serializer} implementation reads/writes {@link PricesStoragePart} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
@RequiredArgsConstructor
public class PricesStoragePartSerializer extends Serializer<PricesStoragePart> {

	@Override
	public void write(Kryo kryo, Output output, PricesStoragePart object) {
		output.writeInt(object.getEntityPrimaryKey());
		output.writeVarInt(object.getVersion(), true);
		kryo.writeObject(output, object.getPriceInnerRecordHandling());

		final PriceContract[] prices = object.getPrices();
		output.writeVarInt(prices.length, true);
		for (PriceContract price : prices) {
			kryo.writeObject(output, price);
		}
	}

	@Override
	public PricesStoragePart read(Kryo kryo, Input input, Class<? extends PricesStoragePart> type) {
		final int entityPrimaryKey = input.readInt();
		final int version = input.readVarInt(true);
		final PriceInnerRecordHandling priceInnerRecordHandling = kryo.readObject(input, PriceInnerRecordHandling.class);

		final int priceCount = input.readVarInt(true);
		final PriceWithInternalIds[] prices = new PriceWithInternalIds[priceCount];
		for (int i = 0; i < priceCount; i++) {
			prices[i] = kryo.readObject(input, PriceWithInternalIds.class);
		}

		return new PricesStoragePart(
			entityPrimaryKey, version, priceInnerRecordHandling, prices
		);
	}

}
