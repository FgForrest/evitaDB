/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex;
import io.evitadb.index.price.model.priceRecord.PriceRecord;

import javax.annotation.Nonnull;

/**
 * Interface marks formulas that work with prices and provide access to {@link PriceRecord} that are connected with
 * those prices (price ids). This is crucial to funnel down rather big sets kept in {@link PriceListAndCurrencyPriceIndex indexes}
 * so that additional logic that needs to work with the prices (mainly sorting) could perform quickly.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface FilteredPriceRecordAccessor {

	/**
	 * Returns array of {@link PriceRecord price records} that are connected with price ids that are produced by
	 * {@link Formula#compute()} method of the formula or different computational method.
	 * This is crucial to funnel down rather big sets kept in {@link PriceListAndCurrencyPriceIndex indexes} so that
	 * additional logic that needs to work with the prices (mainly sorting) could perform quickly.
	 */
	@Nonnull
	FilteredPriceRecords getFilteredPriceRecords(@Nonnull QueryExecutionContext context);

}
