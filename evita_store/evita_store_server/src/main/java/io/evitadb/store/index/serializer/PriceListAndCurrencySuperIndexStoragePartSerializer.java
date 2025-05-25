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
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.price.model.priceRecord.PriceRecordInnerRecordSpecific;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.model.storageParts.index.PriceListAndCurrencySuperIndexStoragePart;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;

/**
 * This {@link Serializer} implementation reads/writes {@link PriceListAndCurrencySuperIndexStoragePart} from/to binary format.
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

		final PriceRecordContract[] triples = priceIndex.getPriceRecords();
		final int tripleCount = triples.length;
		output.writeInt(tripleCount, true);
		for (PriceRecordContract priceRecord : triples) {
			if (priceRecord instanceof PriceRecord) {
				output.writeBoolean(true);
				output.writeInt(priceRecord.internalPriceId());
				output.writeInt(priceRecord.priceId());
				output.writeInt(priceRecord.entityPrimaryKey());
				output.writeInt(priceRecord.priceWithTax(), true);
				output.writeInt(priceRecord.priceWithoutTax(), true);
			} else if (priceRecord instanceof PriceRecordInnerRecordSpecific) {
				output.writeBoolean(false);
				output.writeInt(priceRecord.internalPriceId());
				output.writeInt(priceRecord.priceId());
				output.writeInt(priceRecord.entityPrimaryKey());
				output.writeInt(priceRecord.innerRecordId());
				output.writeInt(priceRecord.priceWithTax(), true);
				output.writeInt(priceRecord.priceWithoutTax(), true);
			} else {
				throw new GenericEvitaInternalError("Unknown implementation `" + priceRecord.getClass() + "` of PriceRecordContract!");
			}
		}
	}

	@Override
	public PriceListAndCurrencySuperIndexStoragePart read(Kryo kryo, Input input, Class<? extends PriceListAndCurrencySuperIndexStoragePart> type) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final PriceIndexKey priceIndexKey = this.keyCompressor.getKeyForId(input.readVarInt(true));

		final RangeIndex validityIndex = kryo.readObject(input, RangeIndex.class);

		final int tripleCount = input.readInt(true);
		final PriceRecordContract[] priceRecords = new PriceRecordContract[tripleCount];
		for (int i = 0; i < tripleCount; i++) {
			final boolean thinPriceRecord = input.readBoolean();
			if (thinPriceRecord) {
				priceRecords[i] = new PriceRecord(
					input.readInt(),
					input.readInt(),
					input.readInt(),
					input.readInt(true),
					input.readInt(true)
				);
			} else {
				priceRecords[i] = new PriceRecordInnerRecordSpecific(
					input.readInt(),
					input.readInt(),
					input.readInt(),
					input.readInt(),
					input.readInt(true),
					input.readInt(true)
				);
			}
		}

		return new PriceListAndCurrencySuperIndexStoragePart(
			entityIndexPrimaryKey, priceIndexKey, validityIndex, priceRecords, uniquePartId
		);
	}

}
