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

package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.model.storageParts.index.PriceListAndCurrencyRefIndexStoragePart;
import io.evitadb.store.spi.model.storageParts.index.PriceListAndCurrencySuperIndexStoragePart;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

/**
 * This {@link Serializer} implementation reads/writes {@link PriceListAndCurrencySuperIndexStoragePart} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class PriceListAndCurrencyRefIndexStoragePartSerializer extends Serializer<PriceListAndCurrencyRefIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, PriceListAndCurrencyRefIndexStoragePart priceIndex) {
		output.writeInt(priceIndex.getEntityIndexPrimaryKey());
		final Long uniquePartId = priceIndex.getStoragePartPK();
		Assert.notNull(uniquePartId, "Unique part id should have been computed by now!");
		output.writeVarLong(uniquePartId, true);
		output.writeVarInt(keyCompressor.getId(priceIndex.getPriceIndexKey()), true);

		kryo.writeObject(output, priceIndex.getValidityIndex());

		final int[] triples = priceIndex.getPriceIds();
		output.writeInt(triples.length, true);
		output.writeInts(triples, 0, triples.length);
	}

	@Override
	public PriceListAndCurrencyRefIndexStoragePart read(Kryo kryo, Input input, Class<? extends PriceListAndCurrencyRefIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final PriceIndexKey priceIndexKey = keyCompressor.getKeyForId(input.readVarInt(true));
		final RangeIndex validityIndex = kryo.readObject(input, RangeIndex.class);

		final int tripleCount = input.readInt(true);
		final int[] priceIds = input.readInts(tripleCount);

		return new PriceListAndCurrencyRefIndexStoragePart(
			entityIndexPrimaryKey, priceIndexKey, validityIndex, priceIds, uniquePartId
		);
	}

}
