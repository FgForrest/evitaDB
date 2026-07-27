/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.index.price;


import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.DateTimeRange;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * This interface defines write methods for the price index. It allows adding and removing prices from the index.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface PriceIndexWriteContract {

	/**
	 * Method registers new price to the index.
	 *
	 * @param superPriceIndex the price index of the GLOBAL entity index owning the memory-expensive
	 *                        {@link io.evitadb.index.price.model.priceRecord.PriceRecord} and
	 *                        {@link io.evitadb.index.price.model.entityPrices.EntityPrices} instances this index shares.
	 *                        A reduced ({@link PriceRefIndex}) index resolves them through it instead of holding
	 *                        a pointer of its own; a {@link PriceSuperIndex} owns them and therefore ignores it beyond
	 *                        asserting the caller handed over this very index.
	 */
	int addPrice(
		@Nullable ReferenceSchemaContract referenceSchema,
		int entityPrimaryKey,
		int internalPriceId,
		@Nonnull PriceKey priceKey,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nullable Integer innerRecordId,
		@Nullable DateTimeRange validity,
		int priceWithoutTax,
		int priceWithTax,
		@Nonnull PriceSuperIndex superPriceIndex
	);

	/**
	 * Method removes registered price from the index.
	 *
	 * @param superPriceIndex the price index of the GLOBAL entity index backing this one - see
	 *                        {@link #addPrice(ReferenceSchemaContract, int, int, PriceKey, PriceInnerRecordHandling, Integer, DateTimeRange, int, int, PriceSuperIndex)}
	 */
	void priceRemove(
		@Nullable ReferenceSchemaContract referenceSchema,
		int entityPrimaryKey,
		int internalPriceId,
		@Nonnull PriceKey priceKey,
		@Nonnull PriceInnerRecordHandling innerRecordHandling,
		@Nullable Integer innerRecordId,
		@Nullable DateTimeRange validity,
		int priceWithoutTax,
		int priceWithTax,
		@Nonnull PriceSuperIndex superPriceIndex
	);

}
