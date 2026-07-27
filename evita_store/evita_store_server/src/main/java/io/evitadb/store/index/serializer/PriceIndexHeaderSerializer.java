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
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencyIndexStoragePart;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;

/**
 * Shared read/write of the header both price-index serializers open with — the owning entity index primary key, the
 * storage part primary key, the compressed {@link PriceIndexKey} and the {@link RangeIndex} validity index. These are
 * exactly the fields carried by the {@link PriceListAndCurrencyIndexStoragePart} base, from which both the
 * {@code PriceListAndCurrencySuperIndexStoragePart} and {@code PriceListAndCurrencyRefIndexStoragePart} descend; the
 * serializer-side header mirrors that part-side base so the two stay byte-identical over this region by construction.
 *
 * Unlike the histogram header, the primary key is expected to be already computed here: it is read straight off the part
 * ({@link PriceListAndCurrencyIndexStoragePart#getStoragePartPK()}) with a non-null assertion, not (re)computed during
 * the write. What each serializer writes AFTER this header (the super index's price records, the ref index's ascending
 * price ids) is serializer-specific and stays in each.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class PriceIndexHeaderSerializer {

	private PriceIndexHeaderSerializer() {
		throw new UnsupportedOperationException("This class is not intended to be instantiated!");
	}

	/**
	 * Writes the header: the entity index primary key, the (already computed) storage part primary key, the compressed
	 * price index key and the validity range index.
	 *
	 * @param kryo          the kryo instance
	 * @param output        the target output
	 * @param part          the price index part whose header is written
	 * @param keyCompressor the key compressor used to compress the price index key
	 */
	public static void write(
		@Nonnull Kryo kryo,
		@Nonnull Output output,
		@Nonnull PriceListAndCurrencyIndexStoragePart part,
		@Nonnull KeyCompressor keyCompressor
	) {
		output.writeInt(part.getEntityIndexPrimaryKey());
		final Long uniquePartId = part.getStoragePartPK();
		Assert.notNull(uniquePartId, "Unique part id should have been computed by now!");
		output.writeVarLong(uniquePartId, true);
		output.writeVarInt(keyCompressor.getId(part.getPriceIndexKey()), true);
		kryo.writeObject(output, part.getValidityIndex());
	}

	/**
	 * Reads the header written by {@link #write} into a {@link PriceIndexHeader}.
	 *
	 * @param kryo          the kryo instance
	 * @param input         the source input
	 * @param keyCompressor the key compressor used to resolve the price index key
	 * @return the decoded entity index primary key, storage part primary key, price index key and validity index
	 */
	@Nonnull
	public static PriceIndexHeader read(
		@Nonnull Kryo kryo,
		@Nonnull Input input,
		@Nonnull KeyCompressor keyCompressor
	) {
		final int entityIndexPrimaryKey = input.readInt();
		final long uniquePartId = input.readVarLong(true);
		final PriceIndexKey priceIndexKey = keyCompressor.getKeyForId(input.readVarInt(true));
		final RangeIndex validityIndex = kryo.readObject(input, RangeIndex.class);
		return new PriceIndexHeader(entityIndexPrimaryKey, uniquePartId, priceIndexKey, validityIndex);
	}

	/**
	 * The decoded header shared by both price-index parts.
	 *
	 * @param entityIndexPrimaryKey primary key of the owning entity index
	 * @param uniquePartId          the storage part primary key
	 * @param priceIndexKey         the price list + currency identification of the index
	 * @param validityIndex         the validity range index
	 */
	public record PriceIndexHeader(
		int entityIndexPrimaryKey,
		long uniquePartId,
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull RangeIndex validityIndex
	) {
	}

}
