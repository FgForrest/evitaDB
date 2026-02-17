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

package io.evitadb.api.query.require;

import io.evitadb.dataType.SupportedEnum;

/**
 * Selects which price variant is used as the operative price for filtering, sorting, and histogram computation
 * throughout a single query. The active mode is configured via the {@link PriceType} require constraint; when that
 * constraint is absent the system defaults to {@link #WITH_TAX}.
 *
 * **Usage context**
 *
 * - `WITH_TAX` — standard B2C setting; consumers see and filter by gross prices including VAT.
 * - `WITHOUT_TAX` — standard B2B setting; business customers see and filter by net prices excluding VAT.
 *
 * The selected mode applies uniformly to {@link io.evitadb.api.query.filter.PriceBetween} filtering,
 * {@link io.evitadb.api.query.order.PriceNatural} ordering, and {@link PriceHistogram} computation within the
 * same query.
 */
@SupportedEnum
public enum QueryPriceMode {

	/**
	 * The price with tax is used for filtering and sorting.
	 */
	WITH_TAX,
	/**
	 * The price without tax is used for filtering and sorting.
	 */
	WITHOUT_TAX

}
