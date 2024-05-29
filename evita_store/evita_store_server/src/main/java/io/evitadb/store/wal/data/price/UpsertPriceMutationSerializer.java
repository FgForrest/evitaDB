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

package io.evitadb.store.wal.data.price;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.data.mutation.parent.RemoveParentMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.dataType.DateTimeRange;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * Serializer for {@link RemoveParentMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class UpsertPriceMutationSerializer extends Serializer<UpsertPriceMutation> {

	@Override
	public void write(Kryo kryo, Output output, UpsertPriceMutation mutation) {
		final PriceKey priceKey = mutation.getPriceKey();
		output.writeInt(priceKey.priceId());
		output.writeString(priceKey.priceList());
		kryo.writeObject(output, priceKey.currency());
		kryo.writeObjectOrNull(output, mutation.getInnerRecordId(), Integer.class);
		kryo.writeObject(output, mutation.getPriceWithoutTax());
		kryo.writeObject(output, mutation.getTaxRate());
		kryo.writeObject(output, mutation.getPriceWithTax());
		kryo.writeObjectOrNull(output, mutation.getValidity(), DateTimeRange.class);
		output.writeBoolean(mutation.isSellable());
	}

	@Override
	public UpsertPriceMutation read(Kryo kryo, Input input, Class<? extends UpsertPriceMutation> type) {
		final int priceId = input.readInt();
		final String priceList = input.readString();
		final Currency currency = kryo.readObject(input, Currency.class);
		final Integer innerRecordId = kryo.readObjectOrNull(input, Integer.class);
		final BigDecimal priceWithoutTax = kryo.readObject(input, BigDecimal.class);
		final BigDecimal taxRate = kryo.readObject(input, BigDecimal.class);
		final BigDecimal priceWithTax = kryo.readObject(input, BigDecimal.class);
		final DateTimeRange validity = kryo.readObjectOrNull(input, DateTimeRange.class);
		final boolean sellable = input.readBoolean();
		return new UpsertPriceMutation(
			new PriceKey(
				priceId,
				priceList,
				currency
			),
			innerRecordId,
			priceWithoutTax,
			taxRate,
			priceWithTax,
			validity,
			sellable
		);
	}
}
