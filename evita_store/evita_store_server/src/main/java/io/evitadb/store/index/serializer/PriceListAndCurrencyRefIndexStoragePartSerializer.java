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

package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencyRefIndexStoragePart;
import io.evitadb.store.index.serializer.PriceIndexHeaderSerializer.PriceIndexHeader;
import io.evitadb.store.index.serializer.util.SortedIntArrayCodec;
import lombok.RequiredArgsConstructor;

/**
 * Reads and writes {@link PriceListAndCurrencyRefIndexStoragePart} in binary format.
 *
 * The indexed price ID bitmap supplies IDs in ascending order. {@link SortedIntArrayCodec} encodes their deltas
 * as varints and checks the non-decreasing order on write.
 *
 * {@link PriceListAndCurrencyRefIndexStoragePartSerializer_2026_1} reads fixed-width price IDs with
 * epoch-second validity thresholds. {@link PriceListAndCurrencyRefIndexStoragePartSerializer_2026_2}
 * reads delta-varint price IDs with epoch-second validity thresholds.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class PriceListAndCurrencyRefIndexStoragePartSerializer extends Serializer<PriceListAndCurrencyRefIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, PriceListAndCurrencyRefIndexStoragePart priceIndex) {
		PriceIndexHeaderSerializer.write(kryo, output, priceIndex, this.keyCompressor);

		// the price ids are strictly ascending; route through the asserting codec (writes the count itself)
		SortedIntArrayCodec.writeAscendingInts(output, priceIndex.getPriceIds());
	}

	@Override
	public PriceListAndCurrencyRefIndexStoragePart read(Kryo kryo, Input input, Class<? extends PriceListAndCurrencyRefIndexStoragePart> type) {
		final PriceIndexHeader header = PriceIndexHeaderSerializer.read(kryo, input, this.keyCompressor);

		final int[] priceIds = SortedIntArrayCodec.readAscendingInts(input);

		return new PriceListAndCurrencyRefIndexStoragePart(
			header.entityIndexPrimaryKey(), header.priceIndexKey(), header.validityIndex(), priceIds,
			header.uniquePartId()
		);
	}

}
