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

package io.evitadb.store.entity.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.data.key.CompressiblePriceKey;
import io.evitadb.api.requestResponse.data.structure.Price;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.store.entity.model.entity.price.PriceWithInternalIds;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * This {@link Serializer} implementation reads/writes {@link PriceWithInternalIds} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class PriceWithInternalIdsSerializer extends Serializer<PriceWithInternalIds> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, PriceWithInternalIds price) {
		Assert.isPremiseValid(
			price.getInternalPriceId() > 0,
			"Internal price id must be positive, but was: " + price.getInternalPriceId()
		);

		output.writeVarInt(price.version(), true);
		output.writeBoolean(price.indexed());
		output.writeInt(price.getInternalPriceId());
		output.writeInt(price.priceId());
		output.writeVarInt(this.keyCompressor.getId(new CompressiblePriceKey(price.priceKey())), true);
		final Integer innerRecordId = price.innerRecordId();
		if (innerRecordId == null) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			output.writeInt(innerRecordId);
		}
		kryo.writeObject(output, price.priceWithoutTax());
		kryo.writeObject(output, price.taxRate());
		kryo.writeObject(output, price.priceWithTax());
		kryo.writeObjectOrNull(output, price.validity(), DateTimeRange.class);
		output.writeBoolean(price.dropped());
	}

	@Override
	public PriceWithInternalIds read(Kryo kryo, Input input, Class<? extends PriceWithInternalIds> type) {
		final int version = input.readVarInt(true);
		final boolean indexed = input.readBoolean();
		final int internalPriceId = input.readInt();
		final int priceId = input.readInt();
		final CompressiblePriceKey priceKey = this.keyCompressor.getKeyForId(input.readVarInt(true));
		final boolean innerIdExists = input.readBoolean();
		final Integer innerRecordId;
		if (innerIdExists) {
			innerRecordId = input.readInt();
		} else {
			innerRecordId = null;
		}
		final BigDecimal priceWithoutTax = kryo.readObject(input, BigDecimal.class);
		final BigDecimal taxRate = kryo.readObject(input, BigDecimal.class);
		final BigDecimal priceWithTax = kryo.readObject(input, BigDecimal.class);
		final DateTimeRange validity = kryo.readObjectOrNull(input, DateTimeRange.class);
		final boolean dropped = input.readBoolean();

		return new PriceWithInternalIds(
			new Price(
				version,
				new PriceKey(priceId, priceKey.getPriceList(), priceKey.getCurrency()),
				innerRecordId,
				priceWithoutTax, taxRate, priceWithTax,
				validity, indexed, dropped
			),
			internalPriceId
		);
	}

}
