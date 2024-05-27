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

package io.evitadb.core.query.algebra.price.priceIndex;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex;

import javax.annotation.Nonnull;

/**
 * Formula returns all records that belong to certain price list and currency combination. Formula is tightly linked
 * to the {@link PriceListAndCurrencyPriceIndex}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface PriceIndexProvidingFormula extends Formula, FilteredPriceRecordAccessor {

	/**
	 * Returns {@link PriceListAndCurrencyPriceIndex} that was used to produce result of {@link #compute()} method.
	 */
	@Nonnull
	PriceListAndCurrencyPriceIndex getPriceIndex();

	/**
	 * Returns delegate formula of this container.
	 */
	@Nonnull
	Formula getDelegate();

}
