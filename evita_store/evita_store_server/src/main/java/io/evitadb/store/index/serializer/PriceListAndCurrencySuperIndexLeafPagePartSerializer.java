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
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencySuperIndexLeafPagePart;
import io.evitadb.store.index.serializer.util.PriceRecordCodec;

import javax.annotation.Nonnull;

/**
 * This {@link Serializer} implementation reads/writes a {@link PriceListAndCurrencySuperIndexLeafPagePart} — one leaf
 * page of a granular super-price-index record tree — from/to binary format. The `(streamId, pageSequence)` frame is owned
 * by {@link AbstractLeafPagePartSerializer}; this payload adds the leaf's price records. The records use the shared
 * {@link PriceRecordCodec} format so a record written inline in the `SINGLE` shape and the same record written in a leaf
 * page are byte-identical.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class PriceListAndCurrencySuperIndexLeafPagePartSerializer
	extends AbstractLeafPagePartSerializer<PriceListAndCurrencySuperIndexLeafPagePart> {

	@Override
	protected int streamId(@Nonnull PriceListAndCurrencySuperIndexLeafPagePart page) {
		return page.getStreamId();
	}

	@Override
	protected int pageSequence(@Nonnull PriceListAndCurrencySuperIndexLeafPagePart page) {
		return page.getPageSequence();
	}

	@Override
	protected void writePayload(
		@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull PriceListAndCurrencySuperIndexLeafPagePart page
	) {
		PriceRecordCodec.writePriceRecords(output, page.getPriceRecords());
	}

	@Nonnull
	@Override
	protected PriceListAndCurrencySuperIndexLeafPagePart readPayload(
		@Nonnull Kryo kryo, @Nonnull Input input, int streamId, int pageSequence
	) {
		final PriceRecordContract[] priceRecords = PriceRecordCodec.readPriceRecords(input);

		return new PriceListAndCurrencySuperIndexLeafPagePart(
			streamId, pageSequence, priceRecords,
			AbstractLeafPagePart.computeUniquePartId(streamId, pageSequence)
		);
	}

}
