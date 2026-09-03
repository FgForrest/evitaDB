/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencyRefIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencySuperIndexStoragePart;
import lombok.RequiredArgsConstructor;

/**
 * This {@link Serializer} implementation reads {@link PriceListAndCurrencySuperIndexStoragePart} from binary format.
 *
 * It reads the 2026.1 (released) binary format; retained for backward
 * compatibility only. That format wrote the price ids array as raw fixed 4-byte ints; the current serializer
 * delta-varints that (strictly ascending) array. Like the other deprecated readers its {@link #write(Kryo, Output,
 * PriceListAndCurrencyRefIndexStoragePart)} throws — this format must never be written again. The dispatcher delegates
 * writes only to the current serializer; the backward-compatible reading is validated end-to-end by the
 * backward-compatibility suite.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Deprecated(since = "2026.2", forRemoval = true)
@RequiredArgsConstructor
public class PriceListAndCurrencyRefIndexStoragePartSerializer_2026_1 extends Serializer<PriceListAndCurrencyRefIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, PriceListAndCurrencyRefIndexStoragePart priceIndex) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@Override
	public PriceListAndCurrencyRefIndexStoragePart read(Kryo kryo, Input input, Class<? extends PriceListAndCurrencyRefIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final PriceIndexKey priceIndexKey = this.keyCompressor.getKeyForId(input.readVarInt(true));
		// every format older than 2026.3 persisted the validity thresholds as epoch SECONDS - see
		// PriceIndexHeaderSerializer#readWithSecondGranularityValidity for why the rescale needs no type routing here
		final RangeIndex validityIndex = RangeIndex.rescaledFromSecondGranularity(
			kryo.readObject(input, RangeIndex.class)
		);

		final int tripleCount = input.readInt(true);
		final int[] priceIds = input.readInts(tripleCount);

		return new PriceListAndCurrencyRefIndexStoragePart(
			entityIndexPrimaryKey, priceIndexKey, validityIndex, priceIds, uniquePartId
		);
	}

}
