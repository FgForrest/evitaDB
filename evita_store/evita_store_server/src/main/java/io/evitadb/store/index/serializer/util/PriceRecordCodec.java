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

package io.evitadb.store.index.serializer.util;

import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.price.model.priceRecord.PriceRecordInnerRecordSpecific;

import javax.annotation.Nonnull;

/**
 * Shared binary codec for the price-record element wire format used by both the monolithic
 * {@code PriceListAndCurrencySuperIndexStoragePart} and the granular
 * {@code PriceListAndCurrencySuperIndexLeafPagePart}. Centralising the per-record (and array) encoding keeps the two
 * paths byte-identical: a record persisted inline in the `SINGLE` shape and the same record persisted in a `PAGED` leaf
 * page are written exactly the same way, so the `PAGED -> SINGLE` collapse (and the reverse) never re-encodes.
 *
 * The format is the long-standing one: a leading boolean discriminates the compact {@link PriceRecord} (no inner record
 * id) from the {@link PriceRecordInnerRecordSpecific}; the identity ints (`internalPriceId`, `priceId`,
 * `entityPrimaryKey`, and the inner record id when present) are written as plain 4-byte ints, the two prices
 * (`priceWithTax`, `priceWithoutTax`) as positive-optimised var-ints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class PriceRecordCodec {

	private PriceRecordCodec() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated.");
	}

	/**
	 * Writes a single price record in the shared element format (a leading boolean discriminator followed by the
	 * record's fields).
	 *
	 * @param output      the Kryo output
	 * @param priceRecord the record to write
	 */
	public static void writePriceRecord(@Nonnull Output output, @Nonnull PriceRecordContract priceRecord) {
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
			throw new GenericEvitaInternalError(
				"Unknown implementation `" + priceRecord.getClass() + "` of PriceRecordContract!"
			);
		}
	}

	/**
	 * Reads a single price record previously written by {@link #writePriceRecord(Output, PriceRecordContract)}.
	 *
	 * @param input the Kryo input
	 * @return the read record
	 */
	@Nonnull
	public static PriceRecordContract readPriceRecord(@Nonnull Input input) {
		final boolean thinPriceRecord = input.readBoolean();
		if (thinPriceRecord) {
			return new PriceRecord(
				input.readInt(),
				input.readInt(),
				input.readInt(),
				input.readInt(true),
				input.readInt(true)
			);
		} else {
			return new PriceRecordInnerRecordSpecific(
				input.readInt(),
				input.readInt(),
				input.readInt(),
				input.readInt(),
				input.readInt(true),
				input.readInt(true)
			);
		}
	}

	/**
	 * Writes a length-prefixed price-record array (a positive-optimised var-int count followed by each record in order).
	 *
	 * @param output       the Kryo output
	 * @param priceRecords the records to write, in ascending internal-price-id order
	 */
	public static void writePriceRecords(@Nonnull Output output, @Nonnull PriceRecordContract[] priceRecords) {
		output.writeInt(priceRecords.length, true);
		for (final PriceRecordContract priceRecord : priceRecords) {
			writePriceRecord(output, priceRecord);
		}
	}

	/**
	 * Reads a length-prefixed price-record array previously written by
	 * {@link #writePriceRecords(Output, PriceRecordContract[])}.
	 *
	 * @param input the Kryo input
	 * @return the read records
	 */
	@Nonnull
	public static PriceRecordContract[] readPriceRecords(@Nonnull Input input) {
		final int count = input.readInt(true);
		final PriceRecordContract[] priceRecords = new PriceRecordContract[count];
		for (int i = 0; i < count; i++) {
			priceRecords[i] = readPriceRecord(input);
		}
		return priceRecords;
	}

}
