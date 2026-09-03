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
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencySuperIndexStoragePart;
import io.evitadb.store.index.serializer.util.PriceRecordCodec;
import lombok.RequiredArgsConstructor;

/**
 * Backward-compatible {@link Serializer} for the released 2026.1 {@link PriceListAndCurrencySuperIndexStoragePart} format
 * that predates the granular `PAGED` layout: it carried every price record inline and had NO trailing `SINGLE`/`PAGED`
 * discriminator. Reads such legacy blobs into a `SINGLE`-shaped part; the discriminator-bearing format is handled by
 * {@link PriceListAndCurrencySuperIndexStoragePartSerializer}. A legacy blob is always inline, so it reconstructs a
 * non-paged part whose page metadata defaults to the `SINGLE` sentinel. Like the other
 * deprecated readers its {@link #write} throws — this legacy format must never be written again.
 *
 * @deprecated only for backward compatibility purposes
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Deprecated(since = "2026.2", forRemoval = true)
@RequiredArgsConstructor
public class PriceListAndCurrencySuperIndexStoragePartSerializer_2026_1 extends Serializer<PriceListAndCurrencySuperIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, PriceListAndCurrencySuperIndexStoragePart priceIndex) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@Override
	public PriceListAndCurrencySuperIndexStoragePart read(Kryo kryo, Input input, Class<? extends PriceListAndCurrencySuperIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final PriceIndexKey priceIndexKey = this.keyCompressor.getKeyForId(input.readVarInt(true));

		// every format older than 2026.3 persisted the validity thresholds as epoch SECONDS - see
		// PriceIndexHeaderSerializer#readWithSecondGranularityValidity for why the rescale needs no type routing here
		final RangeIndex validityIndex = RangeIndex.rescaledFromSecondGranularity(
			kryo.readObject(input, RangeIndex.class)
		);

		final PriceRecordContract[] priceRecords = PriceRecordCodec.readPriceRecords(input);

		return new PriceListAndCurrencySuperIndexStoragePart(
			entityIndexPrimaryKey, priceIndexKey, validityIndex, priceRecords, uniquePartId
		);
	}

}
