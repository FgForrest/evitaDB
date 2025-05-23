/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.core.query.algebra.price;

import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import lombok.Data;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Simple DTO that contains array of all {@link PriceRecord} that pair 1:1 to entity primary key in filtering formula
 * output. It also contains array of all entity primary keys that have no connection to the price.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Data
public class FilteredPriceRecordsLookupResult {
	/**
	 * Contains array of price records matching 1:1 filtering formula output entity primary keys.
	 */
	@Nonnull private final PriceRecordContract[] priceRecords;
	/**
	 * Array of all entity primary keys for which no {@link PriceRecord} was found.
	 */
	@Nullable private final Bitmap notFoundEntities;

	public FilteredPriceRecordsLookupResult(@Nonnull PriceRecordContract[] priceRecords) {
		this.priceRecords = priceRecords;
		this.notFoundEntities = null;
	}

	public FilteredPriceRecordsLookupResult(@Nonnull PriceRecordContract[] priceRecords, @Nonnull Bitmap notFoundEntities) {
		this.priceRecords = priceRecords;
		this.notFoundEntities = notFoundEntities;
	}
}
