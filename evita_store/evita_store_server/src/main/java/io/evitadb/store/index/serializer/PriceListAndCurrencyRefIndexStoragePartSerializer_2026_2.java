/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
 * Backward-compatible {@link Serializer} for the {@link PriceListAndCurrencyRefIndexStoragePart} format shipped by
 * release 2026.2 — the reference-index twin of
 * {@link PriceListAndCurrencySuperIndexStoragePartSerializer_2026_2}, and it exists for the same reason: the byte
 * layout is identical to the current one, but the header's validity thresholds were epoch **seconds** rather than the
 * epoch **milliseconds** {@code DateTimeRange} compares at now, and nothing in an untyped `long` says which.
 *
 * The header is deferred to {@link PriceIndexHeaderSerializer#readWithSecondGranularityValidity}, which rescales it;
 * the ascending price-id array that follows is read exactly as the current serializer reads it.
 *
 * This serializer only reads — writes always go through the current
 * {@link PriceListAndCurrencyRefIndexStoragePartSerializer}.
 *
 * @deprecated kept for backward compatibility; can be removed once no catalog written before `DateTimeRange` moved to
 *             millisecond comparison granularity is still in use.
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Deprecated(since = "2026.2", forRemoval = true)
@RequiredArgsConstructor
public class PriceListAndCurrencyRefIndexStoragePartSerializer_2026_2
	extends Serializer<PriceListAndCurrencyRefIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, PriceListAndCurrencyRefIndexStoragePart priceIndex) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used for writing.");
	}

	@Override
	public PriceListAndCurrencyRefIndexStoragePart read(
		Kryo kryo, Input input, Class<? extends PriceListAndCurrencyRefIndexStoragePart> type
	) {
		final PriceIndexHeader header = PriceIndexHeaderSerializer.readWithSecondGranularityValidity(
			kryo, input, this.keyCompressor
		);

		final int[] priceIds = SortedIntArrayCodec.readAscendingInts(input);

		return new PriceListAndCurrencyRefIndexStoragePart(
			header.entityIndexPrimaryKey(), header.priceIndexKey(), header.validityIndex(), priceIds,
			header.uniquePartId()
		);
	}

}
