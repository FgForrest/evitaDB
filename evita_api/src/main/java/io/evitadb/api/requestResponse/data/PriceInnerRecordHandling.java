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

package io.evitadb.api.requestResponse.data;

/**
 * Price inner record handling controls how prices that share same `inner entity id` will behave during filtering and sorting.
 * It can be one of the following behaviours:
 *
 * - FIRST_OCCURRENCE: prices with same inner entity id will be sorted descending by priority value and first one
 *   (i.e. the one with the biggest priority) will be used (others won't be considered at all)
 * - SUM: prices with same inner entity id will be added up to a new computed aggregated price, prices must share same
 *   tax rate percentage, currency and price list id in order to be added up
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public enum PriceInnerRecordHandling {

	/**
	 * No special strategy set. Inner record id is not taken into account at all.
	 */
	NONE,
	/**
	 * Prices with same inner entity id will be sorted by price and the one with the lowest price will be used as
	 * the final price.
	 */
	LOWEST_PRICE,
	/**
	 * Prices with same inner entity id will be added up to a new computed aggregated price, prices must share same
	 * tax rate percentage, currency and price list id in order to be added up
	 */
	SUM,
	/**
	 * Price handling mode that is used in cases when the information has not been fetched along with entity, and
	 * is therefore unknown (even if some strategy is associated with the entity in reality).
	 */
	UNKNOWN

}
