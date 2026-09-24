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
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencySuperIndexStoragePart;
import io.evitadb.store.index.serializer.PagedStreamMetadataSerializer.PagedStreamMetadata;
import io.evitadb.store.index.serializer.PriceIndexHeaderSerializer.PriceIndexHeader;
import io.evitadb.store.index.serializer.util.PriceRecordCodec;
import lombok.RequiredArgsConstructor;

/**
 * Backward-compatible {@link Serializer} for the {@link PriceListAndCurrencySuperIndexStoragePart} format shipped by
 * release 2026.2. Its **byte layout is identical** to the one
 * {@link PriceListAndCurrencySuperIndexStoragePartSerializer} writes today — header, inline price records, then the
 * `SINGLE`/`PAGED` discriminator and its page-stream metadata. What changed is not the layout but the meaning of the
 * header's validity thresholds: they were epoch **seconds**, because {@code DateTimeRange} compared at second
 * granularity, and they are epoch **milliseconds** now.
 *
 * A `long` threshold carries nothing that says which scale it is in, so reading a 2026.2 blob with the current
 * serializer would inflate every price validity bound by a factor of a thousand and answer `priceValidIn` queries
 * with the wrong records — no exception, just wrong results. That is what this reader exists to prevent: it defers
 * the header to {@link PriceIndexHeaderSerializer#readWithSecondGranularityValidity} and reads the remainder exactly
 * as the current serializer does.
 *
 * This serializer only reads — writes always go through the current
 * {@link PriceListAndCurrencySuperIndexStoragePartSerializer}, which persists the millisecond form under the bumped
 * serial-version-uid.
 *
 * @deprecated kept for backward compatibility; can be removed once no catalog written before `DateTimeRange` moved to
 *             millisecond comparison granularity is still in use.
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Deprecated(since = "2026.2", forRemoval = true)
@RequiredArgsConstructor
public class PriceListAndCurrencySuperIndexStoragePartSerializer_2026_2
	extends Serializer<PriceListAndCurrencySuperIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, PriceListAndCurrencySuperIndexStoragePart priceIndex) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used for writing.");
	}

	@Override
	public PriceListAndCurrencySuperIndexStoragePart read(
		Kryo kryo, Input input, Class<? extends PriceListAndCurrencySuperIndexStoragePart> type
	) {
		final PriceIndexHeader header = PriceIndexHeaderSerializer.readWithSecondGranularityValidity(
			kryo, input, this.keyCompressor
		);

		final PriceRecordContract[] priceRecords = PriceRecordCodec.readPriceRecords(input);

		final PagedStreamMetadata metadata = PagedStreamMetadataSerializer.readOptional(input);

		return new PriceListAndCurrencySuperIndexStoragePart(
			header.entityIndexPrimaryKey(), header.priceIndexKey(), header.validityIndex(), priceRecords,
			metadata.paged(), metadata.highWaterPageSequence(), metadata.leafPageSequences(), header.uniquePartId()
		);
	}

}
