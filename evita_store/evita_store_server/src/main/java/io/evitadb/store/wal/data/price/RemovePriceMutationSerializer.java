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

package io.evitadb.store.wal.data.price;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.data.mutation.price.RemovePriceMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.Price;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import lombok.EqualsAndHashCode;

import java.util.Currency;

/**
 * This mutation allows to remove existing {@link Price} of the {@link Entity}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(callSuper = true)
public class RemovePriceMutationSerializer extends Serializer<RemovePriceMutation> {

	@Override
	public void write(Kryo kryo, Output output, RemovePriceMutation mutation) {
		final PriceKey priceKey = mutation.getPriceKey();
		output.writeInt(priceKey.priceId());
		output.writeString(priceKey.priceList());
		kryo.writeObject(output, priceKey.currency());
	}

	@Override
	public RemovePriceMutation read(Kryo kryo, Input input, Class<? extends RemovePriceMutation> type) {
		return new RemovePriceMutation(
				new PriceKey(
					input.readInt(),
					input.readString(),
					kryo.readObject(input, Currency.class)
				)
		);
	}
}
