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

package io.evitadb.store.entity.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.structure.Price;
import io.evitadb.api.requestResponse.data.structure.Prices;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * This {@link Serializer} implementation reads/writes {@link Prices} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class PricesSerializer extends Serializer<Prices> {

	@Override
	public void write(Kryo kryo, Output output, Prices prices) {
		output.writeVarInt(prices.version(), true);
		kryo.writeObjectOrNull(output, prices.getPriceInnerRecordHandling(), PriceInnerRecordHandling.class);
		output.writeVarInt(prices.getPrices().size(), true);
		// the prices are always sorted to ensure the same order of prices in the serialized form
		prices.getPrices().stream().sorted(Comparator.comparing(PriceContract::priceKey))
			.forEach(price -> kryo.writeObject(output, price));
	}

	@Override
	public Prices read(Kryo kryo, Input input, Class<? extends Prices> type) {
		final int version = input.readVarInt(true);
		final PriceInnerRecordHandling priceInnerRecordHandling = kryo.readObjectOrNull(input, PriceInnerRecordHandling.class);
		final int priceCount = input.readVarInt(true);
		final List<PriceContract> prices = new ArrayList<>(priceCount);
		for (int i = 0; i < priceCount; i++) {
			prices.add(kryo.readObject(input, Price.class));
		}

		return new Prices(
			EntitySchemaContext.getEntitySchema(),
			version, prices, priceInnerRecordHandling
		);
	}

}
