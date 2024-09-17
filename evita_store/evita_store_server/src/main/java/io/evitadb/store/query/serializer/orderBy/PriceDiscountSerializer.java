/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.store.query.serializer.orderBy;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.PriceDiscount;
import lombok.RequiredArgsConstructor;

/**
 * This {@link Serializer} implementation reads/writes {@link io.evitadb.api.query.order.PriceDiscount} from/to binary format.
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class PriceDiscountSerializer extends Serializer<PriceDiscount> {

	@Override
	public void write(Kryo kryo, Output output, PriceDiscount object) {
		kryo.writeObject(output, object.getOrderDirection());
		final String[] priceLists = object.getPriceLists();
		output.writeVarInt(priceLists.length, true);
		for (String priceList : priceLists) {
			output.writeString(priceList);
		}
	}

	@Override
	public PriceDiscount read(Kryo kryo, Input input, Class<? extends PriceDiscount> type) {
		final OrderDirection order = kryo.readObject(input, OrderDirection.class);
		final int priceListsLength = input.readVarInt(true);
		final String[] priceLists = new String[priceListsLength];
		for (int i = 0; i < priceListsLength; i++) {
			priceLists[i] = input.readString();
		}
		return new PriceDiscount(order, priceLists);
	}

}
