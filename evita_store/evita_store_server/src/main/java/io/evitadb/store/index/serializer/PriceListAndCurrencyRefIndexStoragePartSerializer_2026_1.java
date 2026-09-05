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
import lombok.RequiredArgsConstructor;

/**
 * Backward-compatible {@link Serializer} for the {@link PriceListAndCurrencyRefIndexStoragePart} format shipped by
 * release 2026.1 — the reference-index twin of
 * {@link PriceListAndCurrencySuperIndexStoragePartSerializer_2026_1}.
 *
 * Two things separate that format from the current one. Its price ids array was written as raw fixed 4-byte ints,
 * where the current serializer delta-varints the (strictly ascending) array — a **layout** difference, which is why
 * the tail is decoded here rather than delegated. And its validity thresholds were epoch **seconds** rather than the
 * epoch **milliseconds** {@code DateTimeRange} compares at now — a difference in **meaning** that no untyped `long`
 * carries a marker for, so the scale can only be known from the serial-version-uid that routed the read here. The
 * index is therefore rescaled by {@link RangeIndex#rescaledFromSecondGranularity} on the way out; a price validity
 * index is the one range structure that needs no type routing to decide that, and
 * {@link PriceIndexHeaderSerializer#readWithSecondGranularityValidity} holds the full argument for why.
 *
 * This serializer only reads — writes always go through the current
 * {@link PriceListAndCurrencyRefIndexStoragePartSerializer}, and
 * {@link #write(Kryo, Output, PriceListAndCurrencyRefIndexStoragePart)} throws to make that unmistakable: this format
 * must never be written again. The reading itself is validated end-to-end by the backward-compatibility suite.
 *
 * @deprecated kept for backward compatibility; can be removed once no catalog written before `DateTimeRange` moved to
 *             millisecond comparison granularity is still in use.
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
