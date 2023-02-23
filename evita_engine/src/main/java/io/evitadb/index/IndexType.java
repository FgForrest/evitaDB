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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.index;

import io.evitadb.index.price.PriceListAndCurrencyPriceIndex;

/**
 * Index key represents various type of indexes used in {@link EntityIndex} internals.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public enum IndexType {

	/**
	 * Represents {@link EntityIndex}.
	 */
	ENTITY_INDEX,
	/**
	 * Represents {@link EntityIndex} focused on single reference node.
	 */
	REFERENCE_INDEX,
	/**
	 * Represents {@link EntityIndex} focused on single hierarchy node.
	 */
	HIERARCHY_INDEX,
	/**
	 * Represents {@link io.evitadb.index.attribute.AttributeIndex}.
	 */
	ATTRIBUTE_INDEX,
	/**
	 * Represents {@link io.evitadb.index.attribute.SortIndex}.
	 */
	ATTRIBUTE_UNIQUE_INDEX,
	/**
	 * Represents {@link io.evitadb.index.attribute.FilterIndex}.
	 */
	ATTRIBUTE_FILTER_INDEX,
	/**
	 * Represents {@link io.evitadb.index.attribute.SortIndex}.
	 */
	ATTRIBUTE_SORT_INDEX,
	/**
	 * Represents {@link PriceListAndCurrencyPriceIndex}.
	 */
	PRICE_INDEX

}
