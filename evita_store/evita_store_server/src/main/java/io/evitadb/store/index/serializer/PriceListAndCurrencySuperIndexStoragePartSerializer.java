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
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

/**
 * This {@link Serializer} implementation reads/writes {@link PriceListAndCurrencySuperIndexStoragePart} from/to binary
 * format. A `SINGLE`-shaped part carries every price record inline; a `PAGED`-shaped part carries none (its records live
 * in individual {@code PriceListAndCurrencySuperIndexLeafPagePart} leaf pages) and instead carries the page-stream
 * high-water and the ordered live leaf-page list. The `SINGLE`/`PAGED` discriminator is appended after the legacy fields
 * so a `SINGLE` part stays a byte superset of the prior (2026.1) layout — the matching pre-paged blob is read by
 * {@link PriceListAndCurrencySuperIndexStoragePartSerializer_2026_1}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class PriceListAndCurrencySuperIndexStoragePartSerializer extends Serializer<PriceListAndCurrencySuperIndexStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, PriceListAndCurrencySuperIndexStoragePart priceIndex) {
		output.writeInt(priceIndex.getEntityIndexPrimaryKey());
		final Long uniquePartId = priceIndex.getStoragePartPK();
		Assert.notNull(uniquePartId, "Unique part id should have been computed by now!");
		output.writeVarLong(uniquePartId, true);
		output.writeVarInt(this.keyCompressor.getId(priceIndex.getPriceIndexKey()), true);

		kryo.writeObject(output, priceIndex.getValidityIndex());

		// SINGLE shape carries every record inline here; PAGED shape carries none (this writes count 0) — its records
		// live in individual PriceListAndCurrencySuperIndexLeafPagePart leaf pages
		PriceRecordCodec.writePriceRecords(output, priceIndex.getPriceRecords());

		// the SINGLE/PAGED discriminator + the PAGED page-stream metadata. Appended after the legacy fields so the
		// SINGLE shape stays a superset of the prior (2026.1) layout (just a trailing `false`). The page-stream id is
		// NOT persisted — it is recomputed at load from the sub-index identity.
		final boolean paged = priceIndex.isPaged();
		output.writeBoolean(paged);
		if (paged) {
			output.writeVarInt(priceIndex.getHighWaterPageSequence(), true);
			final int[] leafPageSequences = priceIndex.getLeafPageSequences();
			output.writeVarInt(leafPageSequences.length, true);
			for (final int pageSequence : leafPageSequences) {
				output.writeVarInt(pageSequence, true);
			}
		}
	}

	@Override
	public PriceListAndCurrencySuperIndexStoragePart read(Kryo kryo, Input input, Class<? extends PriceListAndCurrencySuperIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final PriceIndexKey priceIndexKey = this.keyCompressor.getKeyForId(input.readVarInt(true));

		final RangeIndex validityIndex = kryo.readObject(input, RangeIndex.class);

		final PriceRecordContract[] priceRecords = PriceRecordCodec.readPriceRecords(input);

		// the SINGLE/PAGED discriminator + the PAGED page-stream metadata
		boolean paged = false;
		int highWaterPageSequence = -1;
		int[] leafPageSequences = ArrayUtils.EMPTY_INT_ARRAY;
		if (input.readBoolean()) {
			paged = true;
			// the page-stream id is recomputed at load from the sub-index identity, not read here
			highWaterPageSequence = input.readVarInt(true);
			final int leafCount = input.readVarInt(true);
			leafPageSequences = new int[leafCount];
			for (int i = 0; i < leafCount; i++) {
				leafPageSequences[i] = input.readVarInt(true);
			}
		}

		return new PriceListAndCurrencySuperIndexStoragePart(
			entityIndexPrimaryKey, priceIndexKey, validityIndex, priceRecords,
			paged, highWaterPageSequence, leafPageSequences, uniquePartId
		);
	}

}
